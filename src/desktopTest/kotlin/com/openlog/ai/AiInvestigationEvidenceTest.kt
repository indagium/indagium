package com.openlog.ai

import com.openlog.model.AnnBlock
import com.openlog.model.Annotations
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.model.LogTab
import com.openlog.ui.AppState
import com.openlog.ui.RightSidebarTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiInvestigationEvidenceTest {
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
            assertEquals(RightSidebarTab.AI, state.rightSidebarTab)
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

            state.rightSidebarTab = RightSidebarTab.AI
            state.navigateAiEvidence(AiEvidence.Note("second", "note-21"))
            assertEquals(RightSidebarTab.NOTES, state.rightSidebarTab)
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
