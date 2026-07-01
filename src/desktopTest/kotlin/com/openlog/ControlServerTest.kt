package com.openlog

import com.openlog.debug.ControlServer
import com.openlog.debug.Json
import com.openlog.model.FilterMode
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

class ControlServerTest {
    private lateinit var state: AppState
    private lateinit var server: ControlServer
    private val client = HttpClient.newHttpClient()

    @BeforeTest
    fun setUp() {
        // Point autosave at a throwaway temp file — openFile() writes autosave synchronously,
        // and tests must never touch the user's real ~/.openlog2/autosave.cache.
        state = AppState(autosaveFile = File.createTempFile("openlog-control-server-test", ".cache"))
        server = ControlServer(state, 0) // port 0 → OS picks a free port
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    private fun base() = "http://127.0.0.1:${server.boundPort}"

    private fun get(path: String): String {
        val req = HttpRequest.newBuilder(URI.create(base() + path)).GET().build()
        return client.send(req, HttpResponse.BodyHandlers.ofString()).body()
    }

    private fun post(path: String, body: String): String {
        val req = HttpRequest.newBuilder(URI.create(base() + path))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(req, HttpResponse.BodyHandlers.ofString()).body()
    }

    @Test
    fun listTabsReturnsEmptyListWhenNoTabsOpen() {
        assertEquals("[]", get("/tabs"))
    }

    @Test
    fun listTabsReflectsOpenTabs() {
        state.tabs = listOf(mkTab("t1", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))
        val body = get("/tabs")
        assertTrue(body.contains("\"id\":\"t1\""))
        assertTrue(body.contains("\"filename\":\"test.log\""))
        assertTrue(body.contains("\"entryCount\":1"))
    }

    @Test
    fun getVisibleLinesReturnsRowsForOpenTab() {
        state.tabs = listOf(
            mkTab(
                "t1", "test.log",
                listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.I, "App", "first"),
                    LogEntry(2, "10:00:00.001", LogLevel.E, "App", "second"),
                ),
            ),
        )
        val body = get("/visible?tabId=t1")
        assertTrue(body.contains("\"totalCount\":2"))
        assertTrue(body.contains("\"type\":\"Row\""))
        assertTrue(body.contains("\"msg\":\"first\""))
    }

    @Test
    fun getVisibleLinesRespectsLimitAndOffset() {
        val entries = (1..5).map { LogEntry(it, "10:00:00.00$it", LogLevel.I, "App", "line$it") }
        state.tabs = listOf(mkTab("t1", "test.log", entries))
        val body = get("/visible?tabId=t1&limit=2&offset=2")
        assertTrue(body.contains("\"totalCount\":5"))
        assertTrue(body.contains("\"msg\":\"line3\""))
        assertTrue(body.contains("\"msg\":\"line4\""))
        assertTrue(!body.contains("\"msg\":\"line1\""))
    }

    @Test
    fun setFilterAppliesPartialUpdate() {
        state.tabs = listOf(mkTab("t1", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))
        post("/filter", """{"tabId":"t1","kwText":"hello","mode":"KEYWORD"}""")
        val filter = state.tab("t1")!!.filter
        assertEquals("hello", filter.kwText)
        assertEquals(FilterMode.KEYWORD, filter.mode)
    }

    @Test
    fun getFilterReturnsCurrentFilterState() {
        state.tabs = listOf(mkTab("t1", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))
        state.upFlt("t1") { it.copy(kwText = "boom", mode = FilterMode.KEYWORD) }
        val body = get("/filter?tabId=t1")
        assertTrue(body.contains("\"kwText\":\"boom\""))
        assertTrue(body.contains("\"mode\":\"KEYWORD\""))
    }

    @Test
    fun toggleGroupTogglesExpandedMembership() {
        state.tabs = listOf(mkTab("t1", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))
        post("/toggle", """{"tabId":"t1","gid":"sg_1_1"}""")
        assertTrue(state.tab("t1")!!.expanded.contains("sg_1_1"))
        post("/toggle", """{"tabId":"t1","gid":"sg_1_1"}""")
        assertTrue(!state.tab("t1")!!.expanded.contains("sg_1_1"))
    }

    @Test
    fun getTagsReturnsDistinctSortedTags() {
        state.tabs = listOf(
            mkTab(
                "t1", "test.log",
                listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.I, "Zeta", "a"),
                    LogEntry(2, "10:00:00.001", LogLevel.I, "Alpha", "b"),
                    LogEntry(3, "10:00:00.002", LogLevel.I, "Alpha", "c"),
                ),
            ),
        )
        assertEquals("""{"tags":["Alpha","Zeta"]}""", get("/tags?tabId=t1"))
    }

    @Test
    fun getCrashSitesReturnsStackTraceAndAnrSites() {
        state.tabs = listOf(
            mkTab(
                "t1", "test.log",
                listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
                    LogEntry(2, "10:00:00.001", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
                    LogEntry(3, "10:00:01.000", LogLevel.E, "ActivityManager", "ANR in com.example.app", pid = 200),
                ),
            ),
        )
        val body = get("/crashes?tabId=t1")
        assertTrue(body.contains("\"kind\":\"EXCEPTION\""))
        assertTrue(body.contains("\"kind\":\"ANR\""))
        assertTrue(body.contains("\"logId\":1"))
        assertTrue(body.contains("\"logId\":3"))
    }

    @Test
    fun getRoutesReturnErrorForUnknownTab() {
        assertTrue(get("/filter?tabId=nope").contains("\"error\""))
        assertTrue(get("/visible?tabId=nope").contains("\"error\""))
        assertTrue(get("/tags?tabId=nope").contains("\"error\""))
        assertTrue(get("/crashes?tabId=nope").contains("\"error\""))
    }

    @Test
    fun openLogFileParsesRealFileAndReturnsTabInfo() {
        val file = File.createTempFile("openlog-control-server-fixture", ".log")
        file.writeText(
            "07-01 10:00:00.000  1234  1234 I MyTag: hello world\n" +
                "07-01 10:00:00.001  1234  1234 E MyTag: boom\n",
        )
        val body = post("/open", """{"path":"${file.absolutePath.replace("\\", "\\\\")}"}""")
        assertTrue(body.contains("\"entryCount\":2"))
        assertTrue(body.contains("\"filename\":\"${file.name}\""))

        // Re-opening the same path should hit the "already open" fast path, not create a
        // second tab.
        val tabId = (Json.decode(body) as Map<*, *>)["tabId"] as String
        post("/open", """{"path":"${file.absolutePath.replace("\\", "\\\\")}"}""")
        assertEquals(1, state.tabs.size)
        assertEquals(tabId, state.tabs.single().id)

        file.delete()
    }

    @Test
    fun closeTabRemovesIt() {
        state.tabs = listOf(mkTab("t1", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))
        post("/close", """{"tabId":"t1"}""")
        assertTrue(state.tabs.isEmpty())
    }
}
