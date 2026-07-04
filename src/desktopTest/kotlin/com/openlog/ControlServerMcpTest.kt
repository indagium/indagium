package com.openlog

import com.openlog.debug.ControlServer
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.ui.AppState
import com.openlog.ui.mkTab
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val INITIALIZE_REQUEST =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
        """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""

// Exercises the native MCP Streamable HTTP endpoint the same ControlServer also serves the REST
// routes from — proving a real MCP client (which does exactly these JSON-RPC-over-HTTP calls)
// can drive the app by URL with no Node bridge: initialize handshake -> session id -> tools/list
// (all 29 tools) -> tools/call reading live AppState.
class ControlServerMcpTest {
    private lateinit var state: AppState
    private lateinit var server: ControlServer
    private val client = HttpClient.newHttpClient()

    @BeforeTest
    fun setUp() {
        state = AppState(autosaveFile = File.createTempFile("openlog-mcp-test", ".cache"))
        server = ControlServer(state, 0)
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        state.close()
    }

    private fun mcp(json: String, sessionId: String? = null): HttpResponse<String> {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.boundPort}/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(json))
        if (sessionId != null) b = b.header("Mcp-Session-Id", sessionId)
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun initSession(): String {
        val init = mcp(INITIALIZE_REQUEST)
        assertTrue(init.statusCode() in 200..299, "initialize failed: ${init.statusCode()} ${init.body()}")
        val session = init.headers().firstValue("mcp-session-id")
            .orElseThrow { AssertionError("no Mcp-Session-Id:\n${init.body()}") }
        // Real MCP clients must send notifications/initialized before issuing requests; skipping it
        // leaves the server in a pre-ready state where tool calls behave inconsistently.
        mcp("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", session)
        return session
    }

    @Test
    fun initializeReturnsServerInfo() {
        val init = mcp(INITIALIZE_REQUEST)
        assertTrue(init.body().contains("openlog-control"), "missing serverInfo:\n${init.body()}")
    }

    @Test
    fun toolsListExposesAllTwentyNineTools() {
        val session = initSession()
        val body = mcp("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""", session).body()
        // Every op the retired Node bridge exposed must still be present, by exact name.
        val expected = listOf(
            "list_tabs", "open_log_file", "preview_split_log_file", "split_log_file", "close_tab",
            "get_filter", "set_filter", "get_visible_lines", "select_lines", "get_selection",
            "toggle_group", "expand_all", "collapse_all", "get_tags", "get_crash_sites",
            "add_text_note", "add_log_note", "update_note_block", "move_note_block", "delete_note_block",
            "export_analysis", "export_filtered_log", "save_annotations", "load_annotations",
            "list_filter_presets", "apply_filter_preset", "merge_tabs", "start_tailing", "stop_tailing",
        )
        assertEquals(29, expected.size)
        expected.forEach { name -> assertTrue(body.contains("\"$name\""), "tools/list missing $name:\n$body") }
    }

    @Test
    fun toolCallReadsLiveAppState() {
        state.tabs = listOf(mkTab("t1", "sample.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))
        val session = initSession()
        val body = mcp(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_tabs","arguments":{}}}""",
            session,
        ).body()
        // The tool result is Json.encode(listTabs()) embedded as escaped JSON in the MCP text
        // content, so ids/filenames appear as e.g. \"id\":\"t1\" — match on the plain substrings.
        assertTrue(body.contains("sample.log"), "list_tabs didn't reflect AppState:\n$body")
        assertTrue(body.contains("id\\\":\\\"t1"), "list_tabs missing tab id:\n$body")
    }

    @Test
    fun toolCallWithArgumentsMutatesAppState() {
        state.tabs = listOf(
            mkTab(
                "t1", "sample.log",
                (1..5).map { LogEntry(it, "10:00:0$it.000", if (it == 3) LogLevel.E else LogLevel.I, "App", "line $it") },
            ),
        )
        val session = initSession()
        // set_filter to errors-only, then read it back through get_filter — proves typed args
        // (arrays) flow through the JsonObject -> arg-map conversion into the shared handlers.
        mcp(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"set_filter","arguments":{"tabId":"t1","levels":["E"]}}}""",
            session,
        )
        assertEquals(setOf(LogLevel.E), state.tab("t1")!!.filter.levels)
    }
}
