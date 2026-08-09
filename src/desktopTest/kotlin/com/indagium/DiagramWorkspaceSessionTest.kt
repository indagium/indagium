package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.diagram.DiagramRange
import com.indagium.diagram.parseDiagramNote
import com.indagium.model.AnnBlock
import com.indagium.model.Filter
import com.indagium.model.LogAnalysis
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.ManualCollapseBlock
import com.indagium.model.ManualCollapseDirection
import com.indagium.model.SequenceDef
import com.indagium.model.StackTraceGroup
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

    // Every earlier test in this file builds its own AppState from one fixed two-line tab, inline.
    // The fold-expansion tests below need a variety of tab shapes (a sequence, a stack trace, a
    // manual block), so the tab is now a parameter and the original two-line fixture becomes the
    // no-arg overload's default — existing tests are unaffected.
    private fun stateFor(tab: LogTab): AppState {
        val root = createTempDirectory("indagium-diagram-workspaces").toFile()
        return AppState(File(root, "state.cache"), notesDir = File(root, "notes")).also { state ->
            state.tabs = listOf(tab)
            state.activateTab(tab.id)
        }
    }

    private fun state(): AppState = stateFor(
        mkTab(
            "log", "sample.log", listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "one", "first"),
                LogEntry(2, "10:00:00.010", LogLevel.I, "two", "second"),
            )
        )
    )

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

    // ── Fold expansion on the seed path (Step 6) ────────────────────────────────────────────────
    // begin()'s effective-selection derivation, exercised end to end through the coordinator rather
    // than utils/Filter.kt directly — FoldSelectionExpansionTest.kt covers expandSelectionThrough-
    // CollapsedBlocks itself; these confirm begin() actually wires it into both range and components.

    private val outerSeq = SequenceDef("outer", "outer start", priority = 1, color = Color.Red, tag = "Outer", endMatchText = "outer end", endTag = "Outer")
    private val innerSeq = SequenceDef("inner", "inner start", priority = 2, color = Color.Blue, tag = "Inner", endMatchText = "inner end", endTag = "Inner")

    private fun seqEntries(): List<LogEntry> = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "Outer", "outer start"),
        LogEntry(2, "10:00:00.010", LogLevel.I, "Inner", "inner start"),
        LogEntry(3, "10:00:00.020", LogLevel.I, "Inner", "inner work"),
        LogEntry(4, "10:00:00.030", LogLevel.I, "Inner", "inner end"),
        LogEntry(5, "10:00:00.040", LogLevel.I, "Outer", "outer tail"),
        LogEntry(6, "10:00:00.050", LogLevel.I, "Outer", "outer end"),
    )

    private fun manualEntries(): List<LogEntry> =
        (1..5).map { LogEntry(it, "10:00:00.0${it}0", LogLevel.I, "App", "line $it") }

    @Test
    fun beginOnCollapsedSeqHeaderCoversTheWholeGroup() {
        val tab = mkTab("log", "sample.log", seqEntries()).copy(filter = Filter(sequences = listOf(outerSeq, innerSeq)))
        val state = stateFor(tab)

        state.seqDiagrams.begin("log", setOf(1))

        val range = state.seqDiagrams.request!!.spec.range as DiagramRange.Ids
        assertEquals(1, range.from)
        assertEquals(6, range.to)
        // Every interior tag — including the nested "Inner" group's own — becomes an enabled
        // component, so unmappedTagPolicy's HIDE default never swallows it back into hiddenEntries.
        val spec = state.seqDiagrams.request!!.spec
        assertEquals(setOf("Outer", "Inner"), spec.components.flatMap { it.tagIds }.toSet())
        assertTrue(spec.components.all { it.enabled })
    }

    @Test
    fun beginOnCollapsedNestedSeqHeaderCoversOnlyThatChild() {
        val tab = mkTab("log", "sample.log", seqEntries())
            .copy(filter = Filter(sequences = listOf(outerSeq, innerSeq)), expanded = setOf("sg_outer_1"))
        val state = stateFor(tab)

        state.seqDiagrams.begin("log", setOf(2))

        val range = state.seqDiagrams.request!!.spec.range as DiagramRange.Ids
        assertEquals(2, range.from)
        assertEquals(4, range.to)
    }

    @Test
    fun beginOnCollapsedStackTraceHeaderCoversItsMembers() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "before"),
            LogEntry(2, "10:00:00.010", LogLevel.E, "App", "Exception"),
            LogEntry(3, "10:00:00.020", LogLevel.E, "App", "at foo"),
            LogEntry(4, "10:00:00.030", LogLevel.E, "App", "at bar"),
            LogEntry(5, "10:00:00.040", LogLevel.I, "App", "after"),
        )
        val analysis = LogAnalysis(stackTraceGroups = listOf(StackTraceGroup(gid = "st_2", rid = 2, memberIds = listOf(3, 4))))
        val state = stateFor(mkTab("log", "sample.log", logs, analysis = analysis))

        state.seqDiagrams.begin("log", setOf(2))

        val range = state.seqDiagrams.request!!.spec.range as DiagramRange.Ids
        assertEquals(2, range.from)
        assertEquals(4, range.to)
    }

    @Test
    fun beginOnToStartManualHeaderCoversEverythingBeforeItsAnchor() {
        val tab = mkTab("log", "sample.log", manualEntries())
            .copy(manualBlocks = listOf(ManualCollapseBlock("m1", 3, ManualCollapseDirection.TO_START)))
        val state = stateFor(tab)

        state.seqDiagrams.begin("log", setOf(3))

        val range = state.seqDiagrams.request!!.spec.range as DiagramRange.Ids
        // The critical case: TO_START's swallowed ids are strictly LOWER than the anchor, so
        // range.from must reach all the way down to the first visible id, not just the seed.
        assertEquals(1, range.from)
        assertEquals(3, range.to)
    }

    @Test
    fun beginOnToEndManualHeaderCoversEverythingAfterItsAnchor() {
        val tab = mkTab("log", "sample.log", manualEntries())
            .copy(manualBlocks = listOf(ManualCollapseBlock("m1", 3, ManualCollapseDirection.TO_END)))
        val state = stateFor(tab)

        state.seqDiagrams.begin("log", setOf(3))

        val range = state.seqDiagrams.request!!.spec.range as DiagramRange.Ids
        assertEquals(3, range.from)
        assertEquals(5, range.to)
    }

    @Test
    fun beginOnRangeManualHeaderCoversBetweenAnchorAndEnd() {
        val tab = mkTab("log", "sample.log", manualEntries())
            .copy(manualBlocks = listOf(ManualCollapseBlock("m1", 4, ManualCollapseDirection.RANGE, endId = 2)))
        val state = stateFor(tab)

        state.seqDiagrams.begin("log", setOf(4))

        val range = state.seqDiagrams.request!!.spec.range as DiagramRange.Ids
        assertEquals(2, range.from)
        assertEquals(4, range.to)
    }

    @Test
    fun expandedFoldsAreNotExpandedAgain() {
        val tab = mkTab("log", "sample.log", manualEntries())
            .copy(
                manualBlocks = listOf(ManualCollapseBlock("m1", 3, ManualCollapseDirection.TO_START)),
                expanded = setOf("m1"),
            )
        val state = stateFor(tab)

        state.seqDiagrams.begin("log", setOf(3))

        val range = state.seqDiagrams.request!!.spec.range as DiagramRange.Ids
        assertEquals(3, range.from)
        assertEquals(3, range.to)
    }

    @Test
    fun selectionPillAndBeginAgreeOnTheSameRange() {
        val tab = mkTab("log", "sample.log", manualEntries())
            .copy(manualBlocks = listOf(ManualCollapseBlock("m1", 3, ManualCollapseDirection.TO_START)))
        val state = stateFor(tab)
        state.tabs = state.tabs.map { if (it.id == "log") it.copy(selected = setOf(3)) else it }

        // What the Selection pill would derive right now, computed BEFORE begin() runs, so this
        // proves the two call sites agree rather than one merely mirroring the other's own output.
        val pillRange = state.seqDiagrams.selectionRange("log")

        state.seqDiagrams.begin("log", state.tab("log")!!.selected)

        assertEquals(pillRange, state.seqDiagrams.request!!.spec.range as DiagramRange.Ids)
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
