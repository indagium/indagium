package com.openlog

import com.openlog.debug.ControlServer
import com.openlog.debug.Json
import com.openlog.debug.loadOrCreateControlToken
import com.openlog.debug.regenerateControlToken
import com.openlog.model.AnnBlock
import com.openlog.model.FilterMode
import com.openlog.model.LogAnalysis
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.source.SourceIndexStore
import com.openlog.source.SourceIndexer
import com.openlog.ui.AppState
import com.openlog.ui.mkTab
import com.openlog.utils.SPLIT_PROMPT_BYTES
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val RAW_SOCKET_TIMEOUT_MS = 3_000

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
        state.close() // also cancels any FileTailer left running by a tail test
    }

    private fun base() = "http://127.0.0.1:${server.boundPort}"

    // Every route now requires the per-instance token (see debug/ControlServer.kt's start()
    // intercept) — attach it here so the ~40 existing behavior tests below don't each need to know
    // about auth. Dedicated auth-rejection tests below build requests without this helper.
    private fun get(path: String): String {
        val req = HttpRequest.newBuilder(URI.create(base() + path))
            .header("Authorization", "Bearer ${server.token}")
            .GET().build()
        return client.send(req, HttpResponse.BodyHandlers.ofString()).body()
    }

    private fun post(path: String, body: String): String {
        val req = HttpRequest.newBuilder(URI.create(base() + path))
            .header("Authorization", "Bearer ${server.token}")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(req, HttpResponse.BodyHandlers.ofString()).body()
    }

    private fun restartServerWith(newState: AppState) {
        server.stop()
        state.close()
        state = newState
        server = ControlServer(state, 0)
        server.start()
    }

    private fun waitUntil(timeoutMs: Long = 2_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
        assertTrue(predicate(), "condition was not met before timeout")
    }

    // Builds an AppState whose source index is already on disk (built synchronously via
    // SourceIndexer.build, bypassing the async reindexSources() path) so resolve_log_source tests
    // don't race a background scan — AppState's init still loads it via loadPersistedSourceIndex()
    // on ioScope, so callers must waitUntil { state.sourceIndex != null } before resolving.
    private fun buildIndexedAppState(dir: File): AppState {
        val srcDir = File(dir, "src").apply { mkdirs() }
        File(srcDir, "Widgets.kt").writeText(
            """
            package demo

            const val TAG = "Widgets"

            class WidgetHost {
                fun attach(id: String) {
                    Log.d(TAG, "widget attached ${'$'}id")
                }
            }
            """.trimIndent(),
        )
        val indexFile = File(dir, "source-index")
        SourceIndexStore.save(SourceIndexer.build(listOf(srcDir)), indexFile)
        val indexed = AppState(autosaveFile = File(dir, "state.cache"), sourceIndexFile = indexFile)
        indexed.updateSettings { it.copy(sourceFolders = listOf(srcDir.absolutePath)) }
        return indexed
    }

    private fun getAsClient(path: String, clientId: String, clientName: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder(URI.create(base() + path))
            .header("Authorization", "Bearer ${server.token}")
            .header("X-OpenLog-Client-Id", clientId)
            .header("X-OpenLog-Client-Name", clientName)
            .GET()
            .build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
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
    fun setFilterAutoSwitchesToKeywordModeWhenRegexSuppliedWithoutExplicitMode() {
        // Regression: mode defaults to TAGS, and passesTagOrKeywordFilter never evaluates
        // kwText/kwRegex in TAGS mode — so a client setting a regex filter with no mode used to
        // get a silent no-op (the view stayed unfiltered). Mirrors the UI's own Tags/Regex tabs,
        // which set mode as a side effect of the tab click.
        state.tabs = listOf(
            mkTab(
                "t1", "test.log",
                listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.I, "App", "boom happened"),
                    LogEntry(2, "10:00:00.001", LogLevel.I, "App", "all quiet"),
                ),
            ),
        )
        assertEquals(FilterMode.TAGS, state.tab("t1")!!.filter.mode)
        post("/filter", """{"tabId":"t1","kwText":"boom","kwRegex":true}""")
        assertEquals(FilterMode.KEYWORD, state.tab("t1")!!.filter.mode)

        val body = get("/visible?tabId=t1")
        assertTrue(body.contains("boom happened"), body)
        assertTrue(!body.contains("all quiet"), body)
    }

    @Test
    fun setFilterAutoSwitchesToTagsModeWhenActiveTagsSuppliedWithoutExplicitMode() {
        state.tabs = listOf(mkTab("t1", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))
        state.upFlt("t1") { it.copy(mode = FilterMode.KEYWORD) }
        post("/filter", """{"tabId":"t1","activeTags":["App"]}""")
        assertEquals(FilterMode.TAGS, state.tab("t1")!!.filter.mode)
    }

    @Test
    fun setFilterClearingAFieldDoesNotForceModeSwitch() {
        state.tabs = listOf(mkTab("t1", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))
        state.upFlt("t1") { it.copy(mode = FilterMode.KEYWORD) }
        // Explicitly clearing activeTags (empty list) must not force a switch back to TAGS.
        post("/filter", """{"tabId":"t1","activeTags":[]}""")
        assertEquals(FilterMode.KEYWORD, state.tab("t1")!!.filter.mode)

        state.upFlt("t1") { it.copy(mode = FilterMode.TAGS) }
        // Same for clearing kwText to blank while in TAGS mode.
        post("/filter", """{"tabId":"t1","kwText":""}""")
        assertEquals(FilterMode.TAGS, state.tab("t1")!!.filter.mode)
    }

    @Test
    fun setFilterWithBothTagAndKeywordSignalsLeavesModeUnchangedAndWarns() {
        state.tabs = listOf(mkTab("t1", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))
        val before = state.tab("t1")!!.filter.mode
        val body = post("/filter", """{"tabId":"t1","activeTags":["App"],"kwText":"boom"}""")
        assertEquals(before, state.tab("t1")!!.filter.mode)
        assertTrue(body.contains("\"warning\""), body)
        // Both fields are still saved even though mode itself was left alone.
        assertEquals(setOf("App"), state.tab("t1")!!.filter.activeTags)
        assertEquals("boom", state.tab("t1")!!.filter.kwText)
    }

    @Test
    fun setFilterExplicitModeWinsOverConflictingSignalsWithNoWarning() {
        state.tabs = listOf(mkTab("t1", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))
        val body = post("/filter", """{"tabId":"t1","activeTags":["App"],"kwText":"boom","mode":"KEYWORD"}""")
        assertEquals(FilterMode.KEYWORD, state.tab("t1")!!.filter.mode)
        assertTrue(!body.contains("\"warning\""), body)
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
    fun selectionRoutesSetAndReturnSelectedRows() {
        state.tabs = listOf(
            mkTab(
                "t1",
                "test.log",
                listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.I, "App", "first"),
                    LogEntry(2, "10:00:00.001", LogLevel.I, "App", "second"),
                ),
            ),
        )

        val setBody = post("/selection", """{"tabId":"t1","lineIds":[2,1]}""")
        val getBody = get("/selection?tabId=t1")

        assertTrue(setBody.contains("\"selected\":[1,2]"))
        assertTrue(getBody.contains("\"selected\":[1,2]"))
        assertEquals(setOf(1, 2), state.tab("t1")!!.selected)
    }

    @Test
    fun annotationRoutesCreateEditMoveDeleteAndExport() {
        state.tabs = listOf(
            mkTab(
                "t1",
                "test.log",
                listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.I, "App", "first"),
                    LogEntry(2, "10:00:00.001", LogLevel.E, "App", "second"),
                ),
            ),
        )
        val noteBody = post("/annotations/note", """{"tabId":"t1","text":"Initial note"}""")
        val noteId = (Json.decode(noteBody) as Map<*, *>)["blockId"] as String
        val logBody = post("/annotations/log", """{"tabId":"t1","lineIds":[2],"caption":"Failure line"}""")
        val logId = (Json.decode(logBody) as Map<*, *>)["blockId"] as String

        post("/annotations/update", """{"tabId":"t1","blockId":"$noteId","text":"Updated note"}""")
        post("/annotations/move", """{"tabId":"t1","blockId":"$logId","delta":-1}""")
        val blocks = state.tab("t1")!!.annotations.blocks
        assertEquals(logId, blocks.first().id)
        assertEquals("Updated note", assertIs<AnnBlock.Note>(blocks[1]).text)

        val exportFile = File.createTempFile("openlog-control-export", ".md")
        post("/export/analysis", """{"tabId":"t1","path":"${exportFile.absolutePath.replace("\\", "\\\\")}"}""")
        assertTrue(exportFile.readText().contains("Failure line"))

        post("/annotations/delete", """{"tabId":"t1","blockId":"$noteId"}""")
        assertEquals(listOf(logId), state.tab("t1")!!.annotations.blocks.map { it.id })
        exportFile.delete()
    }

    @Test
    fun annotationSidecarRoutesSaveAndLoadAnnotations() {
        state.tabs = listOf(mkTab("t1", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "first"))))
        val sidecar = File.createTempFile("openlog-control-ann", ".ann")
        post("/annotations/note", """{"tabId":"t1","text":"Saved note"}""")

        post("/annotations/save", """{"tabId":"t1","path":"${sidecar.absolutePath.replace("\\", "\\\\")}"}""")
        state.tabs = listOf(state.tab("t1")!!.copy(annotations = com.openlog.model.Annotations()))
        post("/annotations/load", """{"tabId":"t1","path":"${sidecar.absolutePath.replace("\\", "\\\\")}"}""")

        assertEquals("Saved note", assertIs<AnnBlock.Note>(state.tab("t1")!!.annotations.blocks.single()).text)
        sidecar.delete()
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
    fun getIssueDescriptionReturnsAnnotationTextNotExposedInAnyExport() {
        state.tabs = listOf(mkTab("t1", "test.log", emptyList()))
        state.setIssueDescription("t1", "root cause: null pointer in PetMapper")

        assertEquals(
            """{"issueDescription":"root cause: null pointer in PetMapper"}""",
            get("/annotations/issue-description?tabId=t1"),
        )
    }

    @Test
    fun annotationSectionRoutesReadAndAppendNotesWithoutReplacingThem() {
        state.tabs = listOf(mkTab("t1", "test.log", emptyList()))
        state.setPrefix("t1", "Investigating startup")

        assertEquals(
            """{"tabId":"t1","prefix":"Investigating startup","suffix":""}""",
            get("/annotations/sections?tabId=t1"),
        )
        assertEquals(
            mapOf(
                "ok" to true,
                "tabId" to "t1",
                "section" to "prefix",
                "content" to "Investigating startup\n\nChecked cold start",
            ),
            Json.decode(post("/annotations/section/append", """{"tabId":"t1","section":"prefix","text":"Checked cold start"}""")),
        )
        assertEquals(
            mapOf("ok" to true, "tabId" to "t1", "section" to "suffix", "content" to "- Re-run smoke test"),
            Json.decode(post("/annotations/section/append", """{"tabId":"t1","section":"suffix","text":"- Re-run smoke test"}""")),
        )
    }

    @Test
    fun getPackagesReturnsDottedPrefixesWithCounts() {
        state.tabs = listOf(
            mkTab(
                "t1", "test.log",
                listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Net", "a"),
                    LogEntry(2, "10:00:00.001", LogLevel.I, "com.app.Net", "b"),
                    LogEntry(3, "10:00:00.002", LogLevel.I, "com.app.Auth", "c"),
                    LogEntry(4, "10:00:00.003", LogLevel.I, "org.lib.Foo", "d"),
                    LogEntry(5, "10:00:00.004", LogLevel.I, "BareTag", "e"),
                ),
            ),
        )
        val body = get("/packages?tabId=t1")
        // The parent package (before the last dot) is the bucket: all com.app.* fold into com.app
        // (count 3, sorted first); org.lib count 1; BareTag has no dot so is omitted.
        assertTrue(body.contains("\"prefix\":\"com.app\",\"count\":3"), body)
        assertTrue(body.contains("\"prefix\":\"org.lib\",\"count\":1"), body)
        assertTrue(!body.contains("BareTag"), body)
    }

    @Test
    fun getFilterExposesMessageRulesAndSequencesThatSetFilterCanClear() {
        state.tabs = listOf(mkTab("t1", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))
        // A client authors a rule + sequence, reads them back, then clears both — the exact
        // detect-and-undo path that was impossible before these fields were exposed over MCP.
        post("/filter", """{"tabId":"t1","messageRules":[{"include":true,"pattern":"boom","tag":"App"}],"sequences":[{"matchText":"start"}]}""")
        assertEquals(1, state.tab("t1")!!.filter.messageRules.size)
        assertEquals(1, state.tab("t1")!!.filter.sequences.size)

        val body = get("/filter?tabId=t1")
        assertTrue(body.contains("\"pattern\":\"boom\""), body)
        assertTrue(body.contains("\"matchText\":\"start\""), body)

        post("/filter", """{"tabId":"t1","clearMessageRules":true,"clearSequences":true}""")
        assertTrue(state.tab("t1")!!.filter.messageRules.isEmpty())
        assertTrue(state.tab("t1")!!.filter.sequences.isEmpty())
    }

    @Test
    fun setFilterRejectsUnknownLevelKeyAndLeavesLevelsIntact() {
        state.tabs = listOf(mkTab("t1", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))
        val before = state.tab("t1")!!.filter.levels
        val body = post("/filter", """{"tabId":"t1","levels":["Error"]}""")
        assertTrue(body.contains("\"error\""), body)
        assertTrue(body.contains("unknown level key"), body)
        assertEquals(before, state.tab("t1")!!.filter.levels)
    }

    @Test
    fun getLineContextReturnsUnfilteredNeighborsIgnoringActiveFilter() {
        state.tabs = listOf(
            mkTab(
                "t1", "test.log",
                (1..10).map { LogEntry(it, "10:00:0$it.000", if (it == 5) LogLevel.E else LogLevel.I, "App", "line $it") },
            ),
        )
        // Filter down to errors-only — line 5 is the only visible row — but context must still see
        // its Info neighbors, which the filter hides.
        state.upFlt("t1") { it.copy(levels = setOf(LogLevel.E)) }
        val body = get("/context?tabId=t1&lineId=5&before=2&after=2")
        listOf("line 3", "line 4", "line 5", "line 6", "line 7").forEach {
            assertTrue(body.contains(it), "context missing $it:\n$body")
        }
        assertTrue(!body.contains("line 2"), "context should not extend past before window:\n$body")
    }

    @Test
    fun getVisibleLinesCompactAndFieldsShrinkOutputButDefaultIsUnchanged() {
        state.tabs = listOf(
            mkTab("t1", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi", pid = 42, tid = 7))),
        )
        val full = get("/visible?tabId=t1")
        assertTrue(full.contains("\"pid\":42"), full)
        assertTrue(full.contains("\"tid\":7"), full)
        assertTrue(full.contains("\"indent\":0"), full)

        // compact is a query-string boolean here; drop the low-signal columns.
        val compact = get("/visible?tabId=t1&compact=true")
        assertTrue(!compact.contains("\"pid\""), compact)
        assertTrue(!compact.contains("\"tid\""), compact)
        assertTrue(!compact.contains("\"indent\""), compact)
        assertTrue(compact.contains("\"msg\":\"hi\""), compact)
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
    fun getCrashSitesReportsPendingInsteadOfRecomputingFromRawLogData() {
        // P-02: while analysis is still pending, get_crash_sites must not eagerly recompute from
        // tab.logData (that's the same expensive scan the background analysis is already doing) —
        // it must report pending:true so a polling client can tell "still analyzing" apart from
        // "analyzed, found nothing," and it must not do the scan itself just to answer this call.
        val crashEntries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(2, "10:00:00.001", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
        )
        state.tabs = listOf(mkTab("t1", "test.log", crashEntries, analysis = LogAnalysis(pending = true)))

        val body = get("/crashes?tabId=t1")

        assertTrue(body.contains("\"pending\":true"), body)
        assertTrue(body.contains("\"sites\":[]"), body)
    }

    @Test
    fun getCrashSitesReturnsTheCachedResultOnceAnalysisIsComplete() {
        // Complements the pending test above: once complete, the cached (possibly empty) result
        // is trusted directly rather than recomputed on every call.
        state.tabs = listOf(mkTab("t1", "clean.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "all quiet"))))

        val body = get("/crashes?tabId=t1")

        assertTrue(body.contains("\"sites\":[]"), body)
        assertTrue(!body.contains("\"pending\""), "a completed analysis response should not claim pending")
    }

    @Test
    fun getRoutesReturnErrorForUnknownTab() {
        assertTrue(get("/filter?tabId=nope").contains("\"error\""))
        assertTrue(get("/visible?tabId=nope").contains("\"error\""))
        assertTrue(get("/tags?tabId=nope").contains("\"error\""))
        assertTrue(get("/crashes?tabId=nope").contains("\"error\""))
        assertTrue(get("/annotations/issue-description?tabId=nope").contains("\"error\""))
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

    @Test
    fun filteredExportRouteWritesRequestedFormat() {
        state.tabs = listOf(
            mkTab(
                "t1",
                "test.log",
                listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.I, "App", "keep"),
                    LogEntry(2, "10:00:00.001", LogLevel.E, "App", "drop"),
                ),
            ),
        )
        state.upFlt("t1") { it.copy(levels = setOf(LogLevel.I)) }
        val file = File.createTempFile("openlog-filtered", ".txt")

        post("/export/filtered", """{"tabId":"t1","path":"${file.absolutePath.replace("\\", "\\\\")}","format":"txt"}""")

        assertTrue(file.readText().contains("keep"))
        assertTrue(!file.readText().contains("drop"))
        file.delete()
    }

    @Test
    fun expandCollapseAllAndFilterPresetRoutesDriveAppState() {
        state.tabs = listOf(mkTab("t1", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))
        state.upFlt("t1") { it.copy(levels = setOf(LogLevel.I)) }
        state.saveFilter("t1", "Info only")
        state.upFlt("t1") { it.copy(levels = setOf(LogLevel.E)) }

        val presetsBody = get("/filter/presets")
        val presetId = state.savedFilters.single().id
        assertTrue(presetsBody.contains("\"name\":\"Info only\""))

        post("/filter/apply-preset", """{"tabId":"t1","presetId":"$presetId"}""")
        assertEquals(setOf(LogLevel.I), state.tab("t1")!!.filter.levels)

        post("/expand-all", """{"tabId":"t1"}""")
        assertTrue(post("/collapse-all", """{"tabId":"t1"}""").contains("\"ok\":true"))
    }

    @Test
    fun mergeTabsCombinesTwoTabsIntoANewOne() {
        state.tabs = listOf(
            mkTab("t1", "main.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "first"))),
            mkTab("t2", "system.log", listOf(LogEntry(1, "10:00:01.000", LogLevel.I, "Sys", "second"))),
        )

        val body = post("/merge", """{"tabIds":["t1","t2"],"newTabName":"Combined"}""")

        assertTrue(body.contains("\"filename\":\"Combined\""))
        assertTrue(body.contains("\"entryCount\":2"))
        assertEquals(3, state.tabs.size)
    }

    @Test
    fun mergeTabsErrorsForFewerThanTwoIds() {
        state.tabs = listOf(mkTab("t1", "main.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))
        assertTrue(post("/merge", """{"tabIds":["t1"]}""").contains("\"error\""))
    }

    @Test
    fun mergeTabsErrorsForUnknownTabId() {
        state.tabs = listOf(mkTab("t1", "main.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))
        assertTrue(post("/merge", """{"tabIds":["t1","nope"]}""").contains("\"error\""))
    }

    @Test
    fun tailStartAndStopToggleTabTailingState() {
        val file = File.createTempFile("openlog-control-server-tail", ".log")
        file.writeText("10:00:00.000  100  100 I App: first\n")
        val openBody = post("/open", """{"path":"${file.absolutePath.replace("\\", "\\\\")}"}""")
        val tabId = (Json.decode(openBody) as Map<*, *>)["tabId"] as String

        val startBody = post("/tail/start", """{"tabId":"$tabId"}""")
        assertTrue(startBody.contains("\"tailing\":true"))
        assertTrue(state.tab(tabId)!!.tailing)

        val stopBody = post("/tail/stop", """{"tabId":"$tabId"}""")
        assertTrue(stopBody.contains("\"tailing\":false"))
        assertTrue(!state.tab(tabId)!!.tailing)

        file.delete()
    }

    @Test
    fun tailStartErrorsForUnknownTab() {
        assertTrue(post("/tail/start", """{"tabId":"nope"}""").contains("\"error\""))
    }

    // ── resolve_log_source ────────────────────────────────────────────

    @Test
    fun resolveLogSourceReturnsErrorWhenNoSourceFoldersConfigured() {
        // Default fixture AppState has settings.sourceFolders empty — the feature isn't configured.
        val body = post("/resolve_log_source", """{"message":"anything"}""")
        assertTrue(body.contains("\"error\""), body)
        assertTrue(body.contains("no source folders configured"), body)
    }

    @Test
    fun resolveLogSourceReturnsErrorWhenFoldersConfiguredButNotYetIndexed() {
        val dir = kotlin.io.path.createTempDirectory("openlog-resolve-not-indexed").toFile()
        val notIndexed = AppState(autosaveFile = File(dir, "state.cache"), sourceIndexFile = File(dir, "source-index"))
        notIndexed.updateSettings { it.copy(sourceFolders = listOf(dir.absolutePath)) }
        restartServerWith(notIndexed)

        val body = post("/resolve_log_source", """{"message":"anything"}""")

        assertTrue(body.contains("\"error\""), body)
        assertTrue(body.contains("not indexed yet"), body)
    }

    @Test
    fun resolveLogSourceByTagAndMessageReturnsMatchForIndexedFixture() {
        val dir = kotlin.io.path.createTempDirectory("openlog-resolve-tag-msg").toFile()
        restartServerWith(buildIndexedAppState(dir))
        waitUntil { state.sourceIndex != null }

        val body = post("/resolve_log_source", """{"tag":"Widgets","message":"widget attached 42"}""")

        assertTrue(body.contains("\"matches\""), body)
        assertTrue(body.contains("\"methodName\":\"attach\""), body)
        assertTrue(body.contains("\"filePath\""), body)
        assertTrue(body.contains("\"stale\":false"), body)
    }

    @Test
    fun resolveLogSourceByTabIdAndLineIdReturnsMatch() {
        val dir = kotlin.io.path.createTempDirectory("openlog-resolve-tab-line").toFile()
        restartServerWith(buildIndexedAppState(dir))
        waitUntil { state.sourceIndex != null }
        state.tabs = listOf(
            mkTab("t1", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.D, "Widgets", "widget attached 42"))),
        )

        val body = post("/resolve_log_source", """{"tabId":"t1","lineId":1}""")

        assertTrue(body.contains("\"methodName\":\"attach\""), body)
    }

    @Test
    fun resolveLogSourceReturnsEmptyMatchesNotErrorWhenNothingMatches() {
        val dir = kotlin.io.path.createTempDirectory("openlog-resolve-no-match").toFile()
        restartServerWith(buildIndexedAppState(dir))
        waitUntil { state.sourceIndex != null }

        val body = post("/resolve_log_source", """{"message":"totally unrelated text that matches nothing"}""")

        assertEquals("""{"matches":[]}""", body)
    }

    private fun buildZipFixture(entries: Map<String, String>): File {
        val file = File.createTempFile("openlog-control-server-fixture", ".zip")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (path, content) ->
                zos.putNextEntry(java.util.zip.ZipEntry(path))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return file
    }

    @Test
    fun openLogFileAutoOpensASingleCandidateZip() {
        val zip = buildZipFixture(mapOf("main_log.txt" to "07-01 10:00:00.000  1234  1234 I MyTag: hello"))

        val body = post("/open", """{"path":"${zip.absolutePath.replace("\\", "\\\\")}"}""")

        assertTrue(body.contains("\"entryCount\":1"))
        assertTrue(body.contains("\"filename\":\"main_log.txt\""))
        zip.delete()
    }

    @Test
    fun requestsWithoutClientHeadersAreNotTracked() {
        get("/tabs")
        assertTrue(server.connectedClients().isEmpty())
    }

    @Test
    fun connectedClientsTracksRequestsCarryingClientHeaders() {
        getAsClient("/tabs", "client-1", "My Tool")
        val clients = server.connectedClients()
        assertEquals(1, clients.size)
        assertEquals("client-1", clients.single().id)
        assertEquals("My Tool", clients.single().name)
        assertTrue(!clients.single().blocked)
    }

    @Test
    fun blockedClientIsForbiddenUntilUnblocked() {
        getAsClient("/tabs", "client-2", "Blockable Tool")
        server.blockClient("client-2")

        val blockedResponse = getAsClient("/tabs", "client-2", "Blockable Tool")
        assertEquals(403, blockedResponse.statusCode())
        assertTrue(blockedResponse.body().contains("\"error\""))
        assertTrue(server.connectedClients().single().blocked)

        server.unblockClient("client-2")
        val unblockedResponse = getAsClient("/tabs", "client-2", "Blockable Tool")
        assertEquals(200, unblockedResponse.statusCode())
        assertTrue(!server.connectedClients().single().blocked)
    }

    @Test
    fun openLogFileReturnsCandidatesForAMultiCandidateZipUntilEntryPathIsGiven() {
        val zip = buildZipFixture(
            mapOf(
                "main_log.txt" to "07-01 10:00:00.000  1234  1234 I MyTag: hello",
                "system_log.txt" to "07-01 10:00:00.000  5678  5678 I MyTag: world",
            ),
        )

        val listBody = post("/open", """{"path":"${zip.absolutePath.replace("\\", "\\\\")}"}""")
        assertTrue(listBody.contains("\"needsSelection\":true"))
        assertTrue(listBody.contains("\"entryPath\":\"main_log.txt\""))
        assertTrue(listBody.contains("\"entryPath\":\"system_log.txt\""))
        assertTrue(state.tabs.isEmpty())

        val pickedBody = post(
            "/open",
            """{"path":"${zip.absolutePath.replace("\\", "\\\\")}","entryPath":"system_log.txt"}""",
        )
        assertTrue(pickedBody.contains("\"filename\":\"system_log.txt\""))
        assertEquals(1, state.tabs.size)
        zip.delete()
    }

    @Test
    fun openLogFileReportsNeedsSplitForOversizedPlainFile() {
        val dir = kotlin.io.path.createTempDirectory("openlog-control-split-open").toFile()
        val file = File(dir, "large.log")
        RandomAccessFile(file, "rw").use { it.setLength(SPLIT_PROMPT_BYTES) }

        val body = post("/open", """{"path":"${file.absolutePath.replace("\\", "\\\\")}"}""")

        assertTrue(body.contains("\"needsSplit\":true"))
        assertTrue(body.contains("\"displayName\":\"large.log\""))
        assertTrue(body.contains("\"suggestedPartCount\":1"))
        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun splitPreviewReportsPlanWithoutPromptingOrOpening() {
        val dir = kotlin.io.path.createTempDirectory("openlog-control-split-preview").toFile()
        val file = File(dir, "large.log").apply { writeText("one\ntwo\n") }

        val body = post("/split/preview", """{"path":"${file.absolutePath.replace("\\", "\\\\")}"}""")

        assertTrue(body.contains("\"needsSplit\":true"))
        assertTrue(body.contains("\"defaultPostfix\":\"part\""))
        assertTrue(body.contains("\"defaultDestinationDir\":\"${dir.absolutePath.replace("\\", "\\\\")}\""))
        assertEquals(null, state.pendingSplitPrompt)
        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun openLogFileCanSplitOversizedPlainFileWhenRequested() {
        val dir = kotlin.io.path.createTempDirectory("openlog-control-open-split-mode").toFile()
        val source = File(dir, "large.log").apply { writeText("one\ntwo\nthree\nfour\n") }
        val out = File(dir, "parts")

        val body = post(
            "/open",
            """
            {
              "path":"${source.absolutePath.replace("\\", "\\\\")}",
              "splitMode":"split",
              "destinationDir":"${out.absolutePath.replace("\\", "\\\\")}",
              "partCount":2
            }
            """.trimIndent(),
        )

        assertTrue(body.contains("\"outputPaths\""))
        waitUntil { state.tabs.size == 2 && !state.isLoading }
        assertEquals(listOf("large_part_1.log", "large_part_2.log"), state.tabs.map { it.filename })
    }

    @Test
    fun openLogFileCanOpenOversizedPlainFileAsIsWhenRequested() {
        val dir = kotlin.io.path.createTempDirectory("openlog-control-open-as-is-mode").toFile()
        val file = File(dir, "large.log")
        RandomAccessFile(file, "rw").use { it.setLength(SPLIT_PROMPT_BYTES) }
        restartServerWith(
            AppState(
                autosaveFile = File(dir, "state.cache"),
                parser = { listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "loaded")) },
            ),
        )

        val body = post(
            "/open",
            """{"path":"${file.absolutePath.replace("\\", "\\\\")}","splitMode":"open_as_is"}""",
        )

        waitUntil { state.tabs.size == 1 && !state.isLoading }
        assertTrue(body.contains("\"entryCount\":1"))
        assertEquals(null, state.pendingSplitPrompt)
        assertTrue(state.tabs.single().largeFileMode)
    }

    @Test
    fun splitRouteWritesPartsAndOpensThem() {
        val dir = kotlin.io.path.createTempDirectory("openlog-control-split-route").toFile()
        val source = File(dir, "large.log").apply { writeText("one\ntwo\nthree\nfour\n") }
        val out = File(dir, "out")

        val body = post(
            "/split",
            """
            {
              "path":"${source.absolutePath.replace("\\", "\\\\")}",
              "destinationDir":"${out.absolutePath.replace("\\", "\\\\")}",
              "postfix":"part",
              "partCount":2
            }
            """.trimIndent(),
        )

        assertTrue(body.contains("\"outputPaths\""))
        assertTrue(body.contains("large_part_1.log"))
        assertTrue(body.contains("large_part_2.log"))
        waitUntil { state.tabs.size == 2 && !state.isLoading }
        assertEquals(listOf("large_part_1.log", "large_part_2.log"), state.tabs.map { it.filename })
    }

    // ── Authentication / origin (S-01) ──────────────────────────────────
    // These deliberately build raw requests instead of using get()/post(), which always attach a
    // valid token — the whole point here is proving what happens when they don't.

    @Test
    fun rejectsReadRouteWithNoAuthorizationHeader() {
        val req = HttpRequest.newBuilder(URI.create(base() + "/tabs")).GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(401, resp.statusCode())
    }

    @Test
    fun rejectsReadRouteWithWrongToken() {
        val req = HttpRequest.newBuilder(URI.create(base() + "/tabs"))
            .header("Authorization", "Bearer not-the-real-token")
            .GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(401, resp.statusCode())
    }

    @Test
    fun rejectsReadRouteWithMalformedAuthorizationScheme() {
        // Missing the "Bearer " prefix — a client that just pastes the raw token value.
        val req = HttpRequest.newBuilder(URI.create(base() + "/tabs"))
            .header("Authorization", server.token)
            .GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(401, resp.statusCode())
    }

    @Test
    fun rejectsFileOperationRouteWithoutAuth() {
        // Prove the gate covers write/file routes too, not only reads — an unauthenticated caller
        // must not be able to trigger open_log_file.
        val req = HttpRequest.newBuilder(URI.create(base() + "/open"))
            .POST(HttpRequest.BodyPublishers.ofString("""{"path":"/etc/hosts"}"""))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(401, resp.statusCode())
        assertTrue(state.tabs.isEmpty(), "unauthenticated request must not have opened a tab")
    }

    @Test
    fun rejectsRequestWithForeignHostHeader() {
        // The JDK HttpClient refuses to let callers set a custom Host header (it's a restricted
        // header), so this goes over a raw socket the same way SingleInstance's tests drive its own
        // wire protocol directly.
        assertEquals(403, rawRequestStatus(host = "evil.example.com", includeAuth = true))
    }

    @Test
    fun acceptsRequestWithLoopbackHostHeader() {
        assertEquals(200, rawRequestStatus(host = "127.0.0.1:${server.boundPort}", includeAuth = true))
    }

    private fun rawRequestStatus(host: String, includeAuth: Boolean): Int {
        java.net.Socket("127.0.0.1", server.boundPort).use { socket ->
            socket.soTimeout = RAW_SOCKET_TIMEOUT_MS
            val authLine = if (includeAuth) "Authorization: Bearer ${server.token}\r\n" else ""
            val request = "GET /tabs HTTP/1.1\r\nHost: $host\r\n$authLine" + "Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.UTF_8))
                flush()
            }
            val statusLine = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                ?: error("no response for Host: $host")
            return statusLine.split(" ")[1].toInt()
        }
    }

    // ── CORS / browser-client opt-in (SEC-1) ─────────────────────────────
    // The Host-allowlist + bearer-token intercept (tested above) gates every request regardless of
    // this flag; these tests only cover the separate CORS-plugin behavior, which controls whether
    // a *browser* is additionally allowed to read cross-origin responses at all.

    private fun mcpPreflight(target: ControlServer): HttpResponse<String> {
        val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${target.boundPort}/mcp"))
            .header("Origin", "http://example.com")
            .header("Access-Control-Request-Method", "POST")
            .header(
                "Access-Control-Request-Headers",
                "Authorization, Content-Type, Mcp-Session-Id, Mcp-Protocol-Version",
            )
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun defaultConstructionGrantsNoCorsForMcpPreflight() {
        // `server` (from setUp()) is the plain `ControlServer(state, 0)` construction every other
        // test in this suite already relies on — reasserting it here ties the CORS-off default
        // explicitly to SEC-1 rather than leaving it merely implicit in the rest of the suite.
        val resp = mcpPreflight(server)
        assertTrue(resp.headers().firstValue("Access-Control-Allow-Origin").isEmpty, "default construction must install no CORS block at all")
    }

    @Test
    fun allowBrowserClientsTrueAllowsUnauthenticatedMcpPreflight() {
        val browserState = AppState(autosaveFile = File.createTempFile("openlog-control-server-cors-test", ".cache"))
        val browserServer = ControlServer(browserState, 0, allowBrowserClients = true)
        browserServer.start()
        try {
            val resp = mcpPreflight(browserServer)
            assertEquals(200, resp.statusCode())
            assertTrue(
                resp.headers().firstValue("Access-Control-Allow-Origin").isPresent,
                "allowBrowserClients=true must install the CORS block",
            )
        } finally {
            browserServer.stop()
            browserState.close()
        }
    }

    @Test
    fun allowBrowserClientsFalseStillRejectsUnauthenticatedRequests() {
        // The CORS flag must never weaken the auth/Host gate — off or on, an unauthenticated
        // request is still rejected.
        val req = HttpRequest.newBuilder(URI.create(base() + "/tabs")).GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(401, resp.statusCode())
    }

    // ── Persisted control token (loadOrCreateControlToken) ──────────────
    // Users otherwise had to re-copy the MCP client config after every app launch, since a fresh
    // random token was generated per ControlServer instance with nothing persisted to disk.

    @Test
    fun loadOrCreateControlTokenCreatesAndPersistsATokenWhenNoneExists() {
        val file = File(createTempDirectory("openlog-token-test").toFile(), "control-token")
        assertTrue(!file.exists())

        val token = loadOrCreateControlToken(file)

        assertTrue(file.isFile, "token must be written to disk")
        assertEquals(token, file.readText().trim())
        assertEquals(32, token.length)
    }

    @Test
    fun loadOrCreateControlTokenReusesAnExistingValidToken() {
        val file = File(createTempDirectory("openlog-token-test").toFile(), "control-token")
        val first = loadOrCreateControlToken(file)

        val second = loadOrCreateControlToken(file)

        assertEquals(first, second, "an existing valid token on disk must be reused, not replaced")
    }

    @Test
    fun loadOrCreateControlTokenRegeneratesWhenFileContentIsMalformed() {
        val dir = createTempDirectory("openlog-token-test").toFile()
        val file = File(dir, "control-token").apply { writeText("not-a-valid-hex-token") }

        val token = loadOrCreateControlToken(file)

        assertNotEquals("not-a-valid-hex-token", token)
        assertEquals(32, token.length)
        assertEquals(token, file.readText().trim())
    }

    @Test
    fun regenerateControlTokenAlwaysOverwritesEvenAValidExistingToken() {
        // Backs the Settings "Regenerate token" action — unlike loadOrCreateControlToken, this must
        // never reuse what's already on disk, or "Regenerate" would silently do nothing.
        val file = File(createTempDirectory("openlog-token-test").toFile(), "control-token")
        val original = loadOrCreateControlToken(file)

        val rotated = regenerateControlToken(file)

        assertNotEquals(original, rotated)
        assertEquals(32, rotated.length)
        assertEquals(rotated, file.readText().trim())
        assertEquals(rotated, loadOrCreateControlToken(file), "the newly written token must now be what gets reused")
    }
}
