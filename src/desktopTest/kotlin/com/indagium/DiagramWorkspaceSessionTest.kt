package com.indagium

import com.indagium.diagram.DiagramRange
import com.indagium.diagram.parseDiagramNote
import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.ActiveSurface
import com.indagium.ui.AppState
import com.indagium.ui.DiagramCandidateState
import com.indagium.ui.diagramWorkspaceIdsForWidth
import com.indagium.ui.mkTab
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagramWorkspaceSessionTest {
    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }

    private fun await(timeoutMs: Long = 4_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLI
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition did not become true within ${timeoutMs}ms")
    }

    private fun state(): AppState {
        val root = createTempDirectory("indagium-diagram-workspaces").toFile()
        return AppState(File(root, "state.cache"), notesDir = File(root, "notes")).also { state ->
            state.tabs = listOf(
                mkTab(
                    "log", "sample.log", listOf(
                        LogEntry(1, "10:00:00.000", LogLevel.I, "one", "first"),
                        LogEntry(2, "10:00:00.010", LogLevel.I, "two", "second"),
                    )
                )
            )
            state.activateTab("log")
        }
    }

    @Test
    fun diagramSessionsAreIndependentOfLogTabsAndRemainViewableAfterSourceClose() {
        val state = state()
        state.seqDiagrams.begin("log", setOf(1))
        val first = state.seqDiagrams.activeWorkspaceId!!
        state.seqDiagrams.begin("log", setOf(2))
        val second = state.seqDiagrams.activeWorkspaceId!!

        assertEquals(1, state.tabs.size, "diagram workspaces must not be inserted into AppState.tabs")
        assertEquals(2, state.seqDiagrams.workspaces.size)
        assertIs<ActiveSurface.Diagram>(state.activeSurface)
        assertTrue(state.seqDiagrams.activateWorkspace(first))

        state.closeTab("log")

        assertEquals(2, state.seqDiagrams.workspaces.size)
        assertNull(
            state.seqDiagrams.activeSession?.request,
            "closing the log disables regeneration, not cached viewing"
        )
        assertTrue(state.seqDiagrams.relinkWorkspace(second, "missing").not())
    }

    @Test
    fun titleEditsDoNotRescanCandidatesOrRebuildPreview() {
        val state = state()
        state.seqDiagrams.begin("log", setOf(1, 2))
        await {
            state.seqDiagrams.preview.diagramOrNull != null &&
                state.seqDiagrams.candidatePreview is DiagramCandidateState.Computed
        }
        val scans = state.seqDiagrams.candidateScanCount.get()
        val builds = state.seqDiagrams.previewBuildCount.get()
        val spec = state.seqDiagrams.request!!.spec

        state.seqDiagrams.updateSpec(spec.copy(title = "A metadata-only title"))
        Thread.sleep(350)

        assertEquals(scans, state.seqDiagrams.candidateScanCount.get())
        assertEquals(builds, state.seqDiagrams.previewBuildCount.get())
    }

    @Test
    fun noSelectionUsesExplicitDisabledComponentModeAndDirtyCloseIsDeferred() {
        val state = state()
        state.seqDiagrams.begin("log")
        val id = state.seqDiagrams.activeWorkspaceId!!
        val initial = state.seqDiagrams.request!!.spec
        assertTrue(initial.components.isNotEmpty())
        assertTrue(initial.components.none { it.enabled })

        state.seqDiagrams.updateSpec(initial.copy(title = "dirty"))
        state.seqDiagrams.requestCloseWorkspace(id)

        assertEquals(id, state.seqDiagrams.pendingCloseWorkspaceId)
        assertEquals(1, state.seqDiagrams.workspaces.size)
        state.seqDiagrams.cancelWorkspaceClose()
        assertNull(state.seqDiagrams.pendingCloseWorkspaceId)
    }

    @Test
    fun candidatePipelineKeepsAllFilteredViewTagsAcrossRapidRangeChanges() {
        val state = state()
        state.seqDiagrams.begin("log", setOf(1))
        val spec = state.seqDiagrams.request!!.spec
        state.seqDiagrams.updateSpec(spec.copy(range = DiagramRange.Ids(2, 2)))
        await {
            (state.seqDiagrams.candidatePreview as? DiagramCandidateState.Computed)
                ?.values?.map { it.tag }?.toSet() == setOf("one", "two")
        }
        assertEquals(2, (state.seqDiagrams.request!!.spec.range as DiagramRange.Ids).from)
    }

    @Test
    fun pendingSentinelIsExcludedFromPersistedDiagramRoundTrip() {
        val state = state()
        state.seqDiagrams.begin("log")
        await {
            state.seqDiagrams.preview.diagramOrNull != null &&
                state.seqDiagrams.candidatePreview is DiagramCandidateState.Computed
        }

        val blockId = state.seqDiagrams.confirm()!!
        val note = state.tab("log")!!.annotations.blocks
            .single { it.id == blockId } as AnnBlock.Note
        val parsed = parseDiagramNote(note.text)!!

        assertEquals(setOf("one", "two"), parsed.spec.components.flatMap { it.tagIds }.toSet())
        assertTrue(parsed.spec.components.none { it.tagIds.isEmpty() })
        assertTrue(parsed.spec.components.none { it.enabled })
    }

    @Test
    fun missingSourceRegenerationKeepsCachedPreview() {
        val state = state()
        state.seqDiagrams.begin("log", setOf(1, 2))
        await { state.seqDiagrams.preview.diagramOrNull != null }
        val spec = state.seqDiagrams.request!!.spec
        val cached = state.seqDiagrams.preview.diagramOrNull
        state.closeTab("log")

        state.seqDiagrams.requestPreview("log", spec)
        Thread.sleep(300)

        assertEquals(cached, state.seqDiagrams.preview.diagramOrNull)
    }

    @Test
    fun diagramTabOverflowKeepsActiveWorkspaceVisible() {
        val (visible, overflow) = diagramWorkspaceIdsForWidth(
            ids = listOf("one", "two", "three", "four"),
            activeId = "one",
            capacity = 2,
        )
        assertEquals(listOf("one", "four"), visible)
        assertEquals(listOf("two", "three"), overflow)
    }
}
