package com.indagium.ai

import com.indagium.debug.ControlServer
import com.indagium.debug.IndagiumToolDescriptor
import com.indagium.debug.IndagiumToolGateway
import com.indagium.debug.MCP_TOOLS
import com.indagium.debug.schema
import com.indagium.ui.AppState
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val INITIALIZE_REQUEST =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
        """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
private val HTTP_OK_RANGE = 200..299
private const val TINY_PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

/** A managed run with its own gateway sees exactly that gateway's tools over its private MCP endpoint. */
class ManagedMcpGatewayOverrideTest {
    private lateinit var state: AppState
    private lateinit var server: ControlServer
    private val client = HttpClient.newHttpClient()
    private val pings = AtomicInteger()
    private val globalNames = MCP_TOOLS.map { it.name }

    @BeforeTest
    fun setUp() {
        state = AppState(autosaveFile = File.createTempFile("openlog-managed-override", ".cache"))
        server = ControlServer(state, 0)
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        state.close()
    }

    private fun laneGateway() = IndagiumToolGateway(
        catalog = listOf(
            IndagiumToolDescriptor("lane_ping", "Ping the lane", schema("word" to "string")),
            IndagiumToolDescriptor("take_screenshot", "Look at the screen", schema()),
        ),
        handlers = emptyMap(),
        suspendHandlers = mapOf(
            "lane_ping" to { args: Map<String, Any?> -> pings.incrementAndGet(); mapOf("pong" to args["word"]) },
            "take_screenshot" to { _: Map<String, Any?> ->
                mapOf("imageBase64" to TINY_PNG_BASE64, "mimeType" to "image/png", "width" to 1, "height" to 1)
            },
        ),
    )

    private fun mcp(json: String, token: String, sessionId: String? = null): HttpResponse<String> {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.boundPort}/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.ofString(json))
        if (sessionId != null) request = request.header("Mcp-Session-Id", sessionId)
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun session(token: String): String {
        val init = mcp(INITIALIZE_REQUEST, token)
        assertTrue(init.statusCode() in HTTP_OK_RANGE, "initialize failed: ${init.statusCode()} ${init.body()}")
        val id = init.headers().firstValue("mcp-session-id").orElseThrow { AssertionError("no session id:\n${init.body()}") }
        mcp("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", token, id)
        return id
    }

    @Test
    fun theManagedServerListsOnlyTheOverrideGatewaysTools() {
        val run = AiRun(tabId = "tab")
        val access = assertNotNull(server.registerManagedMcpRun(run, laneGateway()))
        val id = session(access.token)

        val body = mcp("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""", access.token, id).body()

        assertTrue(body.contains("\"lane_ping\""), body)
        assertTrue(body.contains("\"take_screenshot\""), body)
        globalNames.forEach { assertFalse(body.contains("\"$it\""), "global tool $it must not be listed:\n$body") }
        server.releaseManagedMcpRun(access)
    }

    @Test
    fun aRunWithoutAGatewayStillSeesTheWholeCatalogue() {
        val access = assertNotNull(server.registerManagedMcpRun(AiRun(tabId = "tab")))
        val id = session(access.token)

        val body = mcp("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""", access.token, id).body()

        globalNames.forEach { assertTrue(body.contains("\"$it\""), "tools/list missing $it") }
        assertFalse(body.contains("\"lane_ping\""))
        server.releaseManagedMcpRun(access)
    }

    @Test
    fun overrideToolCallsRunThroughTheRunsCoordinatorAndBudget() {
        val run = AiRun(tabId = "tab", maxToolCalls = 2)
        val access = assertNotNull(server.registerManagedMcpRun(run, laneGateway()))
        val id = session(access.token)

        fun ping() = mcp(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"lane_ping","arguments":{"word":"hello"}}}""",
            access.token, id,
        ).body()

        assertTrue(ping().contains("pong=hello"))
        assertTrue(ping().contains("pong=hello"))
        assertTrue(ping().contains("budget exhausted"))

        assertEquals(2, pings.get())
        assertEquals(3, run.history.filterIsInstance<AiRunEvent.ToolCompleted>().size)
        server.releaseManagedMcpRun(access)
    }

    @Test
    fun aGlobalToolCannotBeCalledThroughAnOverrideRun() {
        val access = assertNotNull(server.registerManagedMcpRun(AiRun(tabId = "tab"), laneGateway()))
        val id = session(access.token)

        val body = mcp(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list_tabs","arguments":{}}}""",
            access.token, id,
        ).body()

        assertFalse(body.contains("\"tabs\""), body)
        assertTrue(body.contains("error", ignoreCase = true), body)
        server.releaseManagedMcpRun(access)
    }

    @Test
    fun aScreenshotResultIsReturnedAsMcpImageContent() {
        val access = assertNotNull(server.registerManagedMcpRun(AiRun(tabId = "tab"), laneGateway()))
        val id = session(access.token)

        val body = mcp(
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"take_screenshot","arguments":{}}}""",
            access.token, id,
        ).body()

        assertTrue(body.contains("\"type\":\"image\""), body)
        assertTrue(body.contains(TINY_PNG_BASE64), body)
        assertTrue(body.contains("Screenshot dimensions: 1×1"), body)
        server.releaseManagedMcpRun(access)
    }

    @Test
    fun theLeaseStartsAManagedServerForAGateway() {
        val lease = ManagedMcpServerLease.start(state, AiRun(tabId = "tab"), laneGateway())
        try {
            val request = HttpRequest.newBuilder(URI.create(lease.url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer ${lease.token}")
                .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE_REQUEST))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertTrue(response.statusCode() in HTTP_OK_RANGE, response.body())
            assertTrue(response.body().contains("indagium-managed-agent"), response.body())
            assertFalse(response.body().contains("Device capture tools control"), "lane servers describe their own tools")
        } finally {
            lease.close()
        }
    }
}
