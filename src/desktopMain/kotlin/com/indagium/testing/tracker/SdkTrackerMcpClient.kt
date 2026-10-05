package com.indagium.testing.tracker

import com.indagium.generated.BuildInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttpTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

// The tracker client on the official MCP Kotlin SDK (kotlin-sdk-client 0.14.0): Streamable HTTP, JSON or SSE responses and the
// Mcp-Session-Id header are the SDK's own. What this adds: the one auth header (carried on every request, and the HTTP client
// never follows a redirect, so the token cannot be forwarded to another host), timeouts, a cap on how much text a tool may
// return, and failures as TrackerMcpException with the token removed.

private const val MAX_LIST_PAGES = 5
private const val CLIENT_NAME = "indagium"
private val schemaJson = Json { ignoreUnknownKeys = true }

/** The HTTP client the tracker connection uses: SSE support for streamed responses, no redirects, no whole-call timeout. */
internal fun defaultTrackerHttpClient(): HttpClient = HttpClient(CIO) {
    expectSuccess = false
    followRedirects = false
    install(SSE)
    engine {
        // A tool call may take a while; the call timeouts below bound it instead.
        requestTimeout = 0
    }
}

internal class SdkTrackerMcpClient(
    private val connection: TrackerConnection,
    private val httpClient: HttpClient = defaultTrackerHttpClient(),
    private val connectTimeoutMs: Long = TRACKER_CONNECT_TIMEOUT_MS,
    private val callTimeoutMs: Long = TRACKER_CALL_TIMEOUT_MS,
) : TrackerMcpClient {
    private var client: Client? = null

    override suspend fun connect() {
        val transport = httpClient.mcpStreamableHttpTransport(connection.url) {
            if (connection.hasAuth) header(connection.headerName, connection.headerValue())
        }
        val created = Client(Implementation(CLIENT_NAME, BuildInfo.APP_VERSION), ClientOptions())
        guarded("connect", connectTimeoutMs) { created.connect(transport) }
        client = created
    }

    override suspend fun listTools(): List<TrackerTool> {
        val connected = connected()
        val tools = ArrayList<Tool>()
        var cursor: String? = null
        repeat(MAX_LIST_PAGES) {
            val page = guarded("list tools", callTimeoutMs) {
                connected.listTools(ListToolsRequest(cursor?.let { PaginatedRequestParams(cursor = it) }))
            }
            tools += page.tools
            cursor = page.nextCursor
            if (cursor == null || tools.size >= MAX_TRACKER_TOOLS) return tools.take(MAX_TRACKER_TOOLS).map(::toTrackerTool)
        }
        return tools.take(MAX_TRACKER_TOOLS).map(::toTrackerTool)
    }

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): TrackerToolResult {
        val connected = connected()
        val result = guarded("call $name", callTimeoutMs) { connected.callTool(name, arguments) }
        return result?.toTrackerResult() ?: TrackerToolResult("The tool returned no result.", isError = true)
    }

    override suspend fun close() {
        val open = client
        client = null
        withContext(NonCancellable) {
            if (open != null) withTimeoutOrNull(TRACKER_CLOSE_TIMEOUT_MS) { runCatching { open.close() } }
            runCatching { httpClient.close() }
        }
    }

    private fun connected(): Client = client ?: throw TrackerMcpException("The tracker connection is not open.")

    /** Runs [block] with a time limit; anything it throws (except cancellation) becomes a [TrackerMcpException] without the token. */
    @Suppress("TooGenericExceptionCaught") // The SDK and Ktor throw many kinds of exception; all of them mean "the call failed".
    private suspend fun <T> guarded(what: String, timeoutMs: Long, block: suspend () -> T): T {
        val outcome: Result<T>? = try {
            withTimeoutOrNull(timeoutMs) { Result.success(block()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        val result = outcome ?: fail("The issue tracker did not answer ($what) within ${timeoutMs / MILLIS_PER_SECOND} s.")
        return result.getOrElse { failure -> fail("The issue tracker did not accept the request to $what: ${connection.scrub(reasonOf(failure))}", failure) }
    }

    private fun reasonOf(failure: Throwable): String = failure.message ?: failure::class.simpleName.orEmpty()

    private fun fail(message: String, cause: Throwable? = null): Nothing = throw TrackerMcpException(message, cause)
}

private const val MILLIS_PER_SECOND = 1_000L

private fun toTrackerTool(tool: Tool): TrackerTool {
    val schema = runCatching { schemaJson.encodeToJsonElement(ToolSchema.serializer(), tool.inputSchema) as JsonObject }.getOrNull()
    return TrackerTool(tool.name, tool.description.orEmpty(), schema ?: JsonObject(emptyMap()))
}

private fun CallToolResult.toTrackerResult(): TrackerToolResult {
    val text = content.joinToString("\n") { block -> if (block is TextContent) block.text else "[${block.type.name.lowercase()} content omitted]" }
        .ifEmpty { structuredContent?.toString().orEmpty() }
    val truncated = text.length > MAX_TRACKER_RESULT_CHARS
    return TrackerToolResult(if (truncated) text.take(MAX_TRACKER_RESULT_CHARS) else text, isError == true, truncated)
}

/** The production factory: the SDK client over a fresh HTTP client per connection. */
internal val sdkTrackerMcpClientFactory = TrackerMcpClientFactory { connection -> SdkTrackerMcpClient(connection) }
