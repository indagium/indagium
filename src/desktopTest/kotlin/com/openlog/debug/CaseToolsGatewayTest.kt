package com.openlog.debug

import com.openlog.cases.writeCaseNote
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.ui.AppState
import com.openlog.ui.mkTab
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage of the four "similar past issues" MCP tools (search_similar_cases,
 * get_case, set_case_metadata, reindex_cases) through the real [OpenLogToolGateway] — the same
 * entry point the localhost MCP server, REST server, managed-agent MCP, and the in-app AI model
 * all share, so this exercises the whole wiring end to end, not just CaseSearch in isolation
 * (see com.openlog.cases.CaseSearchTest for that).
 *
 * Uses AppState's injectable [notesDir] so this never touches the real on-disk notes folder.
 */
class CaseToolsGatewayTest {
    private lateinit var notesDir: File
    private lateinit var state: AppState
    private lateinit var operations: OpenLogToolOperations

    @BeforeTest
    fun setUp() {
        notesDir = createTempDirectory("openlog-case-gateway-notes").toFile()
        state = AppState(autosaveFile = File.createTempFile("openlog-case-gateway", ".cache"), notesDir = notesDir)
        state.tabs = listOf(
            mkTab("t1", "sample.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello")))
                .copy(sourcePath = "/logs/sample.log"),
        )
        operations = OpenLogToolOperations(state)
    }

    @AfterTest
    fun tearDown() {
        state.close()
    }

    @Test
    fun searchThenGetCaseSurfacesAPriorNoteAndItsFullText() {
        writeCaseNote(
            notesDir, "prior_crash_analysis",
            title = "ANR investigation",
            issueDescription = "Application not responding while loading the dashboard",
            tags = listOf("ActivityManager"),
            decisiveTags = listOf("ActivityManager"),
            appVersion = "1.5.0",
            extraMdText = "Root cause: a synchronous disk read on the main thread.",
        )

        val searchResult = operations.toolGateway.execute(
            "search_similar_cases",
            mapOf("query" to "application not responding while loading the dashboard", "tags" to listOf("ActivityManager")),
        ) as Map<*, *>

        val matches = searchResult["matches"] as List<*>
        assertTrue(matches.isNotEmpty(), "expected at least one match, got: $searchResult")
        val first = matches.first() as Map<*, *>
        assertEquals("ANR investigation", first["title"])
        assertTrue((first["matchedTags"] as List<*>).contains("ActivityManager"))

        val caseResult = operations.toolGateway.execute("get_case", mapOf("id" to first["id"])) as Map<*, *>
        assertEquals("ANR investigation", caseResult["title"])
        assertTrue((caseResult["text"] as String).contains("Root cause: a synchronous disk read on the main thread."))
        assertEquals(listOf("ActivityManager"), caseResult["decisiveTags"])
        assertEquals("1.5.0", caseResult["appVersion"])
    }

    @Test
    fun getCaseReturnsErrorForUnknownId() {
        val result = operations.toolGateway.execute("get_case", mapOf("id" to "/nonexistent_analysis.md")) as Map<*, *>
        assertNotNull(result["error"])
    }

    @Test
    fun searchSimilarCasesRejectsBlankQuery() {
        val result = operations.toolGateway.execute("search_similar_cases", mapOf("query" to "  ")) as Map<*, *>
        assertNotNull(result["error"])
    }

    @Test
    fun setCaseMetadataWritesOntoTheTabsAnnotationsThroughTheNormalMutationPath() {
        val result = operations.toolGateway.execute(
            "set_case_metadata",
            mapOf("tabId" to "t1", "appVersion" to "2.1.0", "decisiveTags" to listOf("Bluetooth", "Scanner")),
        ) as Map<*, *>

        assertEquals(true, result["ok"])
        val annotations = state.tab("t1")!!.annotations
        assertEquals("2.1.0", annotations.appVersion)
        assertEquals(listOf("Bluetooth", "Scanner"), annotations.decisiveTags)
        assertEquals(OpenLogToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("set_case_metadata"))
    }

    @Test
    fun setCaseMetadataRejectsUnknownTab() {
        val result = operations.toolGateway.execute(
            "set_case_metadata",
            mapOf("tabId" to "missing", "appVersion" to "1.0.0"),
        ) as Map<*, *>
        assertNotNull(result["error"])
    }

    @Test
    fun reindexCasesIsAutomaticAndForcesAFullRebuild() {
        assertEquals(OpenLogToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("reindex_cases"))
        assertEquals(OpenLogToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("search_similar_cases"))
        assertEquals(OpenLogToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("get_case"))

        writeCaseNote(notesDir, "another_analysis", title = "Another issue", issueDescription = "distinct keyword phrase here")
        val result = operations.toolGateway.execute("reindex_cases", emptyMap()) as Map<*, *>
        assertEquals(true, result["ok"])

        val search = operations.toolGateway.execute(
            "search_similar_cases",
            mapOf("query" to "distinct keyword phrase here"),
        ) as Map<*, *>
        assertTrue((search["matches"] as List<*>).isNotEmpty())
    }

    @Test
    fun handCopiedAnnFileIsFoundOnTheNextSearchWithNoReindexCall() {
        // Simulate a note dropped straight into the notes folder without going through the app at
        // all — the auto-rescan inside CaseSearch must pick it up on the very next search.
        writeCaseNote(notesDir, "pasted", title = "unused", issueDescription = "hand pasted evidence marker", writeMd = false)

        val search = operations.toolGateway.execute(
            "search_similar_cases",
            mapOf("query" to "hand pasted evidence marker"),
        ) as Map<*, *>

        val matches = search["matches"] as List<*>
        assertTrue(matches.isNotEmpty())
        assertEquals("pasted", (matches.first() as Map<*, *>)["title"])
    }
}
