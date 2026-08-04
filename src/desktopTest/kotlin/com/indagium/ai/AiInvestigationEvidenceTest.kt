package com.indagium.ai

import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.ui.AppState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiInvestigationEvidenceTest {
    @Test
    fun issueInvestigationPromptRequiresBoundedEvidenceCritiqueAndNotes() {
        val prompt = AiQuickAction.ISSUE_INVESTIGATION.prompt

        assertTrue(prompt.contains("Never call unfiltered `get_visible_lines`"))
        assertTrue(prompt.contains("get_sequence_summary"))
        assertTrue(prompt.contains("critic pass"))
        assertTrue(prompt.contains("root-cause functional area different"))
        assertTrue(prompt.contains("append_annotation_section"))
        assertTrue(prompt.contains("get_annotation_blocks"))
        assertTrue(prompt.contains("add_log_note"))
        assertTrue(prompt.contains("add_text_note"))
        assertTrue(prompt.contains("maxContentChars: 4000"))
        assertTrue(prompt.contains("about 20 focused evidence/operational calls"))
    }

    @Test
    fun docsDescribeTheMcpCallBudgetAndProjectContentCap() {
        val readme = File("README.md").readText()
        val mcpGuide = File("docs/mcp/README.md").readText()

        assertTrue(readme.contains("Max MCP tool calls per request"))
        assertTrue(readme.contains("Notes and annotation reads/writes are unlimited"))
        assertTrue(!readme.contains("95% is for"))
        assertTrue(mcpGuide.contains("maxContentChars"))
    }

    @Test
    fun quickActionPinsTheExplicitTabAndSelectedLine() {
        val state = stateWithTabs()
        try {
            assertTrue(state.requestAiInvestigation("second", AiQuickAction.ROOT_CAUSE))

            val request = assertNotNull(state.pendingAiPromptRequest)
            assertEquals("second", request.context.tabId)
            assertEquals(21, request.context.lineId)
            assertEquals(AiQuickAction.ROOT_CAUSE, request.context.action)
            assertEquals("second", state.activeTabId)
            assertTrue(state.aiPanelVisible)
        } finally {
            state.close()
        }
    }

    @Test
    fun selectedLinesCanBeQueuedAsAiContextWithoutSendingARequest() {
        val state = stateWithTabs()
        try {
            assertTrue(state.requestAiContext("second", listOf(21, 999, 21)))

            val request = assertNotNull(state.pendingAiContextRequest)
            assertEquals("second", request.tabId)
            assertEquals(listOf(21), request.lineIds)
            assertEquals("second", state.activeTabId)
            assertTrue(state.aiPanelVisible)

            state.consumeAiContextRequest(request.id)
            assertNull(state.pendingAiContextRequest)
        } finally {
            state.close()
        }
    }

    @Test
    fun evidenceExtractorUsesOnlyGatewayResultIdentifiers() {
        val lineEvidence = AiEvidenceExtractor.from(
            "get_line_context",
            mapOf("tabId" to "second", "lines" to listOf(mapOf("id" to 21), mapOf("id" to 22))),
        ).single()
        assertEquals(AiEvidence.LogRows("second", listOf(21, 22)), lineEvidence)

        val sourceEvidence = AiEvidenceExtractor.from(
            "resolve_log_source",
            mapOf(
                "matches" to listOf(
                    mapOf(
                        "filePath" to "/tmp/Widget.kt", "methodName" to "render", "methodStartLine" to 10,
                        "methodEndLine" to 30, "callLine" to 18, "tag" to "Widget", "confidence" to 0.9, "stale" to false,
                    ),
                ),
            ),
        ).single()
        assertIs<AiEvidence.Source>(sourceEvidence)
        assertEquals("/tmp/Widget.kt", sourceEvidence.filePath)
        assertEquals(18, sourceEvidence.callLine)

        assertTrue(AiEvidenceExtractor.from("get_line_context", mapOf("lines" to listOf(mapOf("id" to 21)))).isEmpty())
        assertTrue(AiEvidenceExtractor.from("add_text_note", mapOf("tabId" to "second")).isEmpty())
    }

    // Crash grouping (CrashSite gaining signature/occurrenceCount/firstLogId) is purely additive —
    // sites[].logId is still what crashRows keys off, so a get_crash_sites response with the new
    // fields present alongside logId must extract evidence exactly as before. If logId ever moved
    // into a nested "occurrences" array this would start returning empty evidence silently.
    @Test
    fun crashSiteEvidenceStillKeysOffLogIdAlongsideTheNewGroupingFields() {
        val evidence = AiEvidenceExtractor.from(
            "get_crash_sites",
            mapOf(
                "tabId" to "second",
                "sites" to listOf(
                    mapOf(
                        "id" to "crash_5", "kind" to "EXCEPTION", "groupGid" to "st_5", "isFatal" to true,
                        "logId" to 5, "ts" to "10:00:00.000", "level" to "E", "tag" to "AndroidRuntime", "msg" to "boom",
                        "signature" to "EXC:java.lang.NullPointerException|com.app.Main.onCreate(Main.java:10)",
                        "occurrenceCount" to 3, "firstLogId" to 5,
                    ),
                    mapOf(
                        "id" to "crash_9", "kind" to "EXCEPTION", "groupGid" to "st_9", "isFatal" to true,
                        "logId" to 9, "ts" to "10:00:01.000", "level" to "E", "tag" to "AndroidRuntime", "msg" to "boom",
                        "signature" to "EXC:java.lang.NullPointerException|com.app.Main.onCreate(Main.java:10)",
                        "occurrenceCount" to 3, "firstLogId" to 5,
                    ),
                ),
            ),
        ).single()

        assertEquals(AiEvidence.LogRows("second", listOf(5, 9)), evidence)
    }

    @Test
    fun evidenceNavigationUsesTheReturnedTarget() {
        val state = stateWithTabs()
        try {
            state.navigateAiEvidence(AiEvidence.LogRows("second", listOf(21)))
            assertEquals("second", state.activeTabId)
            assertEquals(setOf(21), state.tab("second")!!.selected)

            state.navigateAiEvidence(
                AiEvidence.Source("/tmp/Widget.kt", "render", 10, 30, 18, "Widget", 0.9, false),
            )
            assertEquals("/tmp/Widget.kt", state.sourceCodeView!!.matches.single().site.filePath)

            // Notes and AI are independent toggles now: navigating to a note evidence card opens
            // Notes without hiding an already-visible AI panel.
            state.updateAiPanelVisible(true)
            state.navigateAiEvidence(AiEvidence.Note("second", "note-21"))
            assertTrue(state.annotationVisible)
            assertTrue(state.aiPanelVisible)
            assertEquals(AiEvidence.Note("second", "note-21"), state.aiEvidenceNoteTarget)
        } finally {
            state.close()
        }
    }

    @Suppress("MagicNumber") // Fixed log rows make this navigation fixture readable.
    private fun stateWithTabs(): AppState {
        val first = LogTab(
            id = "first", filename = "first.log", logData = listOf(LogEntry(11, "10:00", LogLevel.I, "First", "first")),
            rmap = mapOf(11 to LogEntry(11, "10:00", LogLevel.I, "First", "first")),
        )
        val secondEntry = LogEntry(21, "10:01", LogLevel.E, "Second", "boom")
        val second = LogTab(
            id = "second", filename = "second.log", logData = listOf(secondEntry), rmap = mapOf(21 to secondEntry),
            selected = setOf(21), annotations = Annotations(blocks = listOf(AnnBlock.Note("note-21", "created by tool"))),
        )
        return AppState(restoreOnCreate = false).also {
            it.tabs = listOf(first, second)
            it.activeTabId = "first"
        }
    }
}
