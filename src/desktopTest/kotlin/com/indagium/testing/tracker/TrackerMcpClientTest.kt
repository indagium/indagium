package com.indagium.testing.tracker

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKEN = "super-secret-token-9f2c"
private const val URL = "http://tracker.test/mcp"
private const val SESSION = "session-42"
private const val OVERFLOW_CHARS = 5_000
private const val METHOD_NOT_FOUND_CODE = -32601

private val connection = TrackerConnection(URL, "Authorization", "Bearer $TOKEN")

/** One request the fake tracker saw. */
private class Seen(val method: HttpMethod, val headers: Map<String, String>, val rpcMethod: String?, val params: JsonObject?)

/** A fake MCP server over Ktor's MockEngine: JSON-RPC over POST, answers as JSON or as a server-sent event, hands out a session id. */
private class FakeTracker(
    private val sse: Boolean = false,
    private val initializeStatus: HttpStatusCode = HttpStatusCode.OK,
    private val callDelayMs: Long = 0L,
    private val redirectTo: String? = null,
) {
    val seen = CopyOnWriteArrayList<Seen>()

    val posts: List<Seen> get() = seen.filter { it.method == HttpMethod.Post }

    fun client(): HttpClient = HttpClient(MockEngine { request -> handle(request) }) {
        install(SSE)
        followRedirects = false
        expectSuccess = false
    }

    private fun reply(
        scope: MockRequestHandleScope,
        id: JsonElement?,
        result: JsonObject?,
        error: JsonObject? = null,
        session: Boolean = true,
    ): HttpResponseData {
        val json = buildJsonObject {
            put("jsonrpc", "2.0")
            id?.let { put("id", it) }
            result?.let { put("result", it) }
            error?.let { put("error", it) }
        }.toString()
        val headers = Headers.build {
            append(HttpHeaders.ContentType, if (sse) "text/event-stream" else "application/json")
            if (session) append("Mcp-Session-Id", SESSION)
        }
        return scope.respond(if (sse) "event: message\ndata: $json\n\n" else json, HttpStatusCode.OK, headers)
    }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val body = request.body.toByteArray().toString(Charsets.UTF_8)
        val rpc = if (body.isBlank()) null else Json.parseToJsonElement(body).jsonObject
        val method = rpc?.get("method")?.jsonPrimitive?.contentOrNull
        val params = rpc?.get("params") as? JsonObject
        seen += Seen(request.method, request.headers.entries().associate { (k, v) -> k.lowercase() to v.joinToString(",") }, method, params)
        redirectTo?.let { return respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, it)) }
        if (request.method != HttpMethod.Post) return respond("", HttpStatusCode.MethodNotAllowed)
        val id = rpc?.get("id")
        return when (method) {
            "initialize" -> if (initializeStatus != HttpStatusCode.OK) {
                respond("""{"error":"nope"}""", initializeStatus, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                reply(
                    this, id,
                    buildJsonObject {
                        put("protocolVersion", params?.get("protocolVersion")?.jsonPrimitive?.contentOrNull ?: "2025-06-18")
                        put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                        put("serverInfo", buildJsonObject { put("name", "mock-tracker"); put("version", "1") })
                    },
                )
            }
            "notifications/initialized" -> respond("", HttpStatusCode.Accepted)
            "tools/list" -> reply(this, id, toolsList())
            "tools/call" -> {
                if (callDelayMs > 0) delay(callDelayMs)
                toolCall(this, id, params)
            }
            else -> respond("", HttpStatusCode.Accepted)
        }
    }

    private fun toolsList() = buildJsonObject {
        put(
            "tools",
            kotlinx.serialization.json.buildJsonArray {
                add(
                    buildJsonObject {
                        put("name", "create_issue")
                        put("description", "Create an issue")
                        put(
                            "inputSchema",
                            buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject { put("title", buildJsonObject { put("type", "string") }) })
                                put("required", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("title")) })
                            },
                        )
                    },
                )
            },
        )
    }

    private fun textResult(text: String, isError: Boolean = false) = buildJsonObject {
        put("content", kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
        put("isError", isError)
    }

    private fun toolCall(scope: MockRequestHandleScope, id: JsonElement?, params: JsonObject?): HttpResponseData {
        val name = params?.get("name")?.jsonPrimitive?.contentOrNull
        val title = (params?.get("arguments") as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull
        return when (name) {
            "create_issue" -> reply(scope, id, textResult("created PROJ-1: $title"))
            "big" -> reply(scope, id, textResult("x".repeat(MAX_TRACKER_RESULT_CHARS + OVERFLOW_CHARS)))
            "fails" -> reply(scope, id, textResult("the project does not exist", isError = true))
            else -> reply(scope, id, null, error = buildJsonObject { put("code", METHOD_NOT_FOUND_CODE); put("message", "unknown tool $name") })
        }
    }
}

class TrackerMcpClientTest {
    private fun sdk(fake: FakeTracker, connection: TrackerConnection = com.indagium.testing.tracker.connection, callTimeoutMs: Long = 5_000L) =
        SdkTrackerMcpClient(connection, fake.client(), connectTimeoutMs = 5_000L, callTimeoutMs = callTimeoutMs)

    private fun scenario(fake: FakeTracker) = runBlocking<Unit> {
        val client = sdk(fake)
        try {
            client.connect()
            val tools = client.listTools()
            assertEquals(listOf("create_issue"), tools.map { it.name })
            assertEquals("Create an issue", tools.single().description)
            val schema = tools.single().inputSchema
            assertEquals("object", schema["type"]?.jsonPrimitive?.content)
            assertTrue(schema["properties"]!!.jsonObject.containsKey("title"), schema.toString())

            val result = client.callTool("create_issue", mapOf("title" to "Crash on login"))
            assertEquals("created PROJ-1: Crash on login", result.text)
            assertFalse(result.isError)
            assertFalse(result.truncated)
        } finally {
            client.close()
        }
        // The auth header rides on every request; the session id the server handed out rides on every request after the first.
        val posts = fake.posts
        assertTrue(posts.size >= 4, posts.map { it.rpcMethod }.toString())
        posts.forEach { assertEquals("Bearer $TOKEN", it.headers["authorization"], "Authorization on ${it.rpcMethod}") }
        assertNull(posts.first { it.rpcMethod == "initialize" }.headers["mcp-session-id"], "no session before the server issued one")
        posts.filter { it.rpcMethod != "initialize" }.forEach { assertEquals(SESSION, it.headers["mcp-session-id"], "session id on ${it.rpcMethod}") }
        assertEquals(
            listOf("initialize", "notifications/initialized", "tools/list", "tools/call"),
            posts.mapNotNull { it.rpcMethod }.take(4),
        )
        assertEquals("Crash on login", posts.last { it.rpcMethod == "tools/call" }.params!!["arguments"]!!.jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun jsonResponsesAreParsedAndTheAuthHeaderAndSessionIdAreCarried() = scenario(FakeTracker(sse = false))

    @Test
    fun serverSentEventResponsesAreParsedTheSameWay() = scenario(FakeTracker(sse = true))

    @Test
    fun aTrackerWithoutAuthenticationGetsNoAuthorizationHeader() = runBlocking<Unit> {
        val fake = FakeTracker()
        val client = sdk(fake, TrackerConnection(URL, "", ""))
        try {
            client.connect()
            client.listTools()
        } finally {
            client.close()
        }
        assertTrue(fake.posts.isNotEmpty())
        fake.posts.forEach { assertNull(it.headers["authorization"], "no Authorization header") }
    }

    @Test
    fun aCustomHeaderNameCarriesTheRawToken() = runBlocking<Unit> {
        val fake = FakeTracker()
        val client = sdk(fake, TrackerConnection(URL, "X-Api-Key", TOKEN))
        try {
            client.connect()
        } finally {
            client.close()
        }
        fake.posts.forEach { assertEquals(TOKEN, it.headers["x-api-key"]) }
    }

    // ── Errors ───────────────────────────────────────────────────────

    @Test
    fun anUnauthorizedHandshakeIsATrackerErrorThatNeverContainsTheToken() = runBlocking<Unit> {
        val client = sdk(FakeTracker(initializeStatus = HttpStatusCode.Unauthorized))
        val failure = assertFailsWith<TrackerMcpException> { client.connect() }
        client.close()

        assertTrue(failure.message!!.contains("did not accept"), failure.message)
        assertFalse(failure.message!!.contains(TOKEN), failure.message)
    }

    @Test
    fun aToolThatReportsAnErrorIsAResultNotAnException() = runBlocking<Unit> {
        val client = sdk(FakeTracker())
        client.connect()
        val result = client.callTool("fails", emptyMap())
        client.close()

        assertTrue(result.isError)
        assertEquals("the project does not exist", result.text)
    }

    @Test
    fun aJsonRpcErrorBecomesATrackerErrorWithoutTheToken() = runBlocking<Unit> {
        val client = sdk(FakeTracker())
        client.connect()
        val failure = assertFailsWith<TrackerMcpException> { client.callTool("missing_tool", emptyMap()) }
        client.close()

        assertTrue(failure.message!!.contains("unknown tool missing_tool"), failure.message)
        assertFalse(failure.message!!.contains(TOKEN))
    }

    @Test
    fun aCallThatTakesTooLongTimesOut() = runBlocking<Unit> {
        val client = SdkTrackerMcpClient(connection, FakeTracker(callDelayMs = 3_000L).client(), connectTimeoutMs = 5_000L, callTimeoutMs = 200L)
        client.connect()
        val failure = assertFailsWith<TrackerMcpException> { client.callTool("create_issue", mapOf("title" to "t")) }
        client.close()

        assertTrue(failure.message!!.contains("did not answer"), failure.message)
    }

    @Test
    fun callingBeforeConnectIsATrackerError() = runBlocking<Unit> {
        val failure = assertFailsWith<TrackerMcpException> { sdk(FakeTracker()).listTools() }

        assertTrue(failure.message!!.contains("not open"), failure.message)
    }

    // ── Size cap ─────────────────────────────────────────────────────

    @Test
    fun aHugeResultIsCutAtTheCapAndFlaggedAsTruncated() = runBlocking<Unit> {
        val client = sdk(FakeTracker())
        client.connect()
        val result = client.callTool("big", emptyMap())
        client.close()

        assertEquals(MAX_TRACKER_RESULT_CHARS, result.text.length)
        assertTrue(result.truncated)
    }

    // ── Redirects ────────────────────────────────────────────────────

    @Test
    fun aRedirectIsNotFollowedSoTheTokenCannotBeForwardedToAnotherHost() = runBlocking<Unit> {
        val fake = FakeTracker(redirectTo = "http://evil.example/steal")
        val client = sdk(fake)
        assertFailsWith<TrackerMcpException> { client.connect() }
        client.close()

        assertTrue(fake.seen.all { it.headers["host"]?.contains("evil") != true }, "no request went to the redirect target")
    }

    // ── Scrubbing ────────────────────────────────────────────────────

    @Test
    fun scrubRemovesBothTheHeaderValueAndTheBareToken() {
        val text = connection.scrub("failed with Authorization: Bearer $TOKEN and token $TOKEN here")

        assertFalse(text.contains(TOKEN), text)
        assertTrue(text.contains("[REDACTED]"))
        assertEquals("TrackerConnection(url=$URL, header=Authorization)", connection.toString())
        assertFalse(connection.toString().contains(TOKEN))
    }

    // ── Against a real MCP server (the SDK's own server over Ktor CIO) ──

    @Test
    fun theClientTalksToARealStreamableHttpServerThatDemandsTheTokenAndASession() = runBlocking<Unit> {
        val called = CopyOnWriteArrayList<String>()
        val engine = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                intercept(ApplicationCallPipeline.Call) {
                    if (call.request.header("Authorization") != "Bearer $TOKEN") {
                        call.respondText("""{"error":"unauthorized"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.Unauthorized)
                        finish()
                    }
                }
                mcpStreamableHttp {
                    Server(
                        Implementation("real-tracker", "1"),
                        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
                    ).also { server ->
                        server.addTool(
                            name = "create_issue", description = "Create an issue",
                            inputSchema = ToolSchema(
                                properties = buildJsonObject { put("title", buildJsonObject { put("type", "string") }) },
                                required = listOf("title"),
                            ),
                        ) { request ->
                            val title = (request.arguments?.get("title") as? JsonPrimitive)?.content.orEmpty()
                            called += title
                            CallToolResult(content = listOf(TextContent("created REAL-7: $title")))
                        }
                    }
                }
            }
        }
        engine.start(wait = false)
        try {
            val port = engine.engine.resolvedConnectors().first().port
            val good = SdkTrackerMcpClient(TrackerConnection("http://127.0.0.1:$port/mcp", "Authorization", "Bearer $TOKEN"))
            try {
                good.connect()
                assertEquals(listOf("create_issue"), good.listTools().map { it.name })
                val result = good.callTool("create_issue", mapOf("title" to "From the app"))
                assertEquals("created REAL-7: From the app", result.text)
                assertEquals(listOf("From the app"), called.toList())
            } finally {
                good.close()
            }

            val bad = SdkTrackerMcpClient(TrackerConnection("http://127.0.0.1:$port/mcp", "Authorization", "Bearer wrong"))
            val failure = assertFailsWith<TrackerMcpException> { bad.connect() }
            bad.close()
            assertNotNull(failure.message)
        } finally {
            engine.stop(100, 500)
        }
    }
}
