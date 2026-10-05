package com.indagium.testing.tracker

import com.indagium.security.scrubSecret
import kotlinx.serialization.json.JsonObject

// The issue tracker is a remote MCP server. This is all the issue creator needs of one: connect, list its tools, call a tool,
// close. The implementation (SdkTrackerMcpClient) speaks MCP over Streamable HTTP with the official Kotlin SDK client; the
// interface keeps the creator and its tests free of any transport. Failures are [TrackerMcpException]s whose text is already
// free of the access token.

const val TRACKER_CONNECT_TIMEOUT_MS = 20_000L
const val TRACKER_CALL_TIMEOUT_MS = 60_000L
const val TRACKER_CLOSE_TIMEOUT_MS = 2_000L
const val MAX_TRACKER_TOOLS = 64
const val MAX_TRACKER_RESULT_CHARS = 64 * 1024

/** One tool of the tracker: [inputSchema] is its JSON Schema object as the server sent it. */
data class TrackerTool(val name: String, val description: String, val inputSchema: JsonObject)

/** A tool's answer as text. [truncated]: more than [MAX_TRACKER_RESULT_CHARS] came back and the rest was dropped. */
data class TrackerToolResult(val text: String, val isError: Boolean, val truncated: Boolean = false)

class TrackerMcpException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface TrackerMcpClient {
    /** Connects and performs the MCP handshake. Throws [TrackerMcpException]. */
    suspend fun connect()

    /** The server's tools (at most [MAX_TRACKER_TOOLS]). Needs [connect] first. */
    suspend fun listTools(): List<TrackerTool>

    /** Calls a tool. An error the TOOL reports comes back as a result with `isError`; a transport failure throws [TrackerMcpException]. */
    suspend fun callTool(name: String, arguments: Map<String, Any?>): TrackerToolResult

    suspend fun close()
}

/** Makes a client for one connection; the seam tests replace so no network is needed. */
fun interface TrackerMcpClientFactory {
    fun create(connection: TrackerConnection): TrackerMcpClient
}

/** [text] without the connection's token (neither the bare token nor the whole header value), as a short message. */
internal fun TrackerConnection.scrub(text: String): String {
    val value = headerValue()
    val bare = value.removePrefix("Bearer ")
    var result = text
    for (secret in listOf(value, bare)) if (secret.isNotEmpty()) result = scrubSecret(result, secret)
    return scrubSecret(result, null)
}
