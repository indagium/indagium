package com.indagium

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.indagium.diagram.ArrowHit
import com.indagium.diagram.DiagramRange
import com.indagium.diagram.RenderedDiagram
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
import com.indagium.ui.DiagramZoomMode
import com.indagium.ui.diagramWorkspaceIdsForWidth
import com.indagium.ui.diagramWorkspaceOrderAfterVisibleReorder
import com.indagium.ui.mkTab
import com.indagium.ui.resolveCanvasClickHit
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
        // Expectation moved (Part A task): diagramWorkspaceIdsForWidth used to force-swap the
        // active id into slot 0 — ["one", "four"] here — which silently discarded any order the
        // user had just dragged into place. It now preserves relative order and only slides the
        // trailing default window left far enough to include the active id, so with "one" active
        // and capacity 2 the window becomes the LEADING pair ["one", "two"], not a reshuffled one.
        val (visible, overflow) = diagramWorkspaceIdsForWidth(
            ids = listOf("one", "two", "three", "four"),
            activeId = "one",
            capacity = 2,
        )
        assertEquals(listOf("one", "two"), visible)
        assertEquals(listOf("three", "four"), overflow)
    }

    @Test
    fun diagramWorkspaceIdsForWidthPreservesOrderWhenActiveAlreadyVisible() {
        // The default (no adjustment needed) case: the active id already sits inside the trailing
        // window, so the window is exactly ids.takeLast(capacity), order untouched.
        val (visible, overflow) = diagramWorkspaceIdsForWidth(
            ids = listOf("one", "two", "three", "four"),
            activeId = "four",
            capacity = 2,
        )
        assertEquals(listOf("three", "four"), visible)
        assertEquals(listOf("one", "two"), overflow)
    }

    @Test
    fun diagramWorkspaceIdsForWidthSlidesWindowForAMiddleActiveId() {
        // The active id sits strictly between the trailing window and the very front: the window
        // must slide left just enough to include it, producing overflow on BOTH sides — the case
        // tabOrderAfterVisibleReorder's simple "overflow is always a prefix" assumption can't
        // express, which is exactly why diagramWorkspaceOrderAfterVisibleReorder exists separately.
        val (visible, overflow) = diagramWorkspaceIdsForWidth(
            ids = listOf("a", "b", "c", "d", "e"),
            activeId = "b",
            capacity = 2,
        )
        assertEquals(listOf("b", "c"), visible)
        assertEquals(listOf("a", "d", "e"), overflow)
    }

    @Test
    fun diagramWorkspaceIdsForWidthAtCapacityOneAlwaysIsolatesTheActiveId() {
        val (visible, overflow) = diagramWorkspaceIdsForWidth(
            ids = listOf("a", "b", "c", "d"),
            activeId = "b",
            capacity = 1,
        )
        assertEquals(listOf("b"), visible)
        assertEquals(listOf("a", "c", "d"), overflow)
    }

    @Test
    fun diagramWorkspaceIdsForWidthWithNoActiveIdKeepsTheTrailingWindow() {
        val (visible, overflow) = diagramWorkspaceIdsForWidth(
            ids = listOf("a", "b", "c", "d"),
            activeId = null,
            capacity = 2,
        )
        assertEquals(listOf("c", "d"), visible)
        assertEquals(listOf("a", "b"), overflow)
    }

    @Test
    fun diagramWorkspaceOrderAfterVisibleReorderCommitsOverTheFullListNotJustTheWindow() {
        // A middle window (see the slide test above) reordered among itself must land back in the
        // SAME slots, leaving overflow ids on both sides exactly where they were.
        // "b" and "c" swapped within their own window.
        val committed = diagramWorkspaceOrderAfterVisibleReorder(
            allIds = listOf("a", "b", "c", "d", "e"),
            newVisibleOrder = listOf("c", "b"),
        )
        assertEquals(listOf("a", "c", "b", "d", "e"), committed)
    }

    @Test
    fun diagramWorkspaceOrderAfterVisibleReorderHandlesATrailingWindow() {
        val committed = diagramWorkspaceOrderAfterVisibleReorder(
            allIds = listOf("one", "two", "three", "four"),
            newVisibleOrder = listOf("four", "three"),
        )
        assertEquals(listOf("one", "two", "four", "three"), committed)
    }

    @Test
    fun reorderWorkspacesAppliesOrderIgnoresUnknownIdsKeepsOmittedWorkspacesAndActiveSurvives() {
        val state = state()
        state.seqDiagrams.begin("log", setOf(1))
        val first = state.seqDiagrams.activeWorkspaceId!!
        state.seqDiagrams.begin("log", setOf(2))
        val second = state.seqDiagrams.activeWorkspaceId!!
        state.seqDiagrams.begin("log", setOf(1, 2))
        val third = state.seqDiagrams.activeWorkspaceId!!
        state.seqDiagrams.activateWorkspace(second)

        // Reverse order, plus an id that doesn't exist (must be ignored) and omitting `third`
        // (must survive by being appended, not dropped).
        state.seqDiagrams.reorderWorkspaces(listOf(second, "does-not-exist", first))

        assertEquals(listOf(second, first, third), state.seqDiagrams.workspaces.map { it.id })
        assertEquals(second, state.seqDiagrams.activeWorkspaceId, "reordering must not change which workspace is active")
        assertIs<ActiveSurface.Diagram>(state.activeSurface)
        assertEquals(second, (state.activeSurface as ActiveSurface.Diagram).workspaceId)
    }

    @Test
    fun reorderWorkspacesWithAWhollyStaleOrderNeverDropsALiveWorkspace() {
        val state = state()
        state.seqDiagrams.begin("log", setOf(1))
        val first = state.seqDiagrams.activeWorkspaceId!!
        state.seqDiagrams.begin("log", setOf(2))
        val second = state.seqDiagrams.activeWorkspaceId!!

        // An order referring only to ids that no longer exist — every live workspace must still
        // come back out, appended in their prior order.
        state.seqDiagrams.reorderWorkspaces(listOf("stale-a", "stale-b"))

        assertEquals(setOf(first, second), state.seqDiagrams.workspaces.map { it.id }.toSet())
        assertEquals(2, state.seqDiagrams.workspaces.size)
    }

    // ── Viewport (Part B) ────────────────────────────────────────────────────────────────────────

    @Test
    fun updateViewportRoundTripsZoomAndMode() {
        val state = state()
        state.seqDiagrams.begin("log", setOf(1))
        assertEquals(DiagramZoomMode.FIT, state.seqDiagrams.activeSession!!.zoomMode, "FIT is the default so a fresh workspace auto-fits once")
        assertEquals(1f, state.seqDiagrams.activeSession!!.zoom)

        state.seqDiagrams.updateViewport(zoom = 1.8f, mode = DiagramZoomMode.MANUAL)

        assertEquals(1.8f, state.seqDiagrams.activeSession!!.zoom)
        assertEquals(DiagramZoomMode.MANUAL, state.seqDiagrams.activeSession!!.zoomMode)

        state.seqDiagrams.updateViewport(mode = DiagramZoomMode.FIT_WIDTH)

        // Omitted zoom leaves the prior value untouched — only mode changes.
        assertEquals(1.8f, state.seqDiagrams.activeSession!!.zoom)
        assertEquals(DiagramZoomMode.FIT_WIDTH, state.seqDiagrams.activeSession!!.zoomMode)
    }

    @Test
    fun updateViewportCoercesZoomIntoRange() {
        val state = state()
        state.seqDiagrams.begin("log", setOf(1))

        state.seqDiagrams.updateViewport(zoom = 50f)
        assertEquals(2.5f, state.seqDiagrams.activeSession!!.zoom)

        state.seqDiagrams.updateViewport(zoom = -3f)
        assertEquals(.15f, state.seqDiagrams.activeSession!!.zoom)
    }

    @Test
    fun twoWorkspacesKeepIndependentViewports() {
        val state = state()
        state.seqDiagrams.begin("log", setOf(1))
        val first = state.seqDiagrams.activeWorkspaceId!!
        state.seqDiagrams.updateViewport(zoom = 1.2f, mode = DiagramZoomMode.MANUAL)

        state.seqDiagrams.begin("log", setOf(2))
        val second = state.seqDiagrams.activeWorkspaceId!!
        state.seqDiagrams.updateViewport(zoom = 2f, mode = DiagramZoomMode.FIT_WIDTH)

        val byId = state.seqDiagrams.workspaces.associateBy { it.id }
        assertEquals(1.2f, byId[first]!!.zoom)
        assertEquals(DiagramZoomMode.MANUAL, byId[first]!!.zoomMode)
        assertEquals(2f, byId[second]!!.zoom)
        assertEquals(DiagramZoomMode.FIT_WIDTH, byId[second]!!.zoomMode)

        state.seqDiagrams.activateWorkspace(first)
        assertEquals(1.2f, state.seqDiagrams.activeSession!!.zoom, "switching back to the first workspace must not have inherited the second's zoom")
    }

    @Test
    fun updateInspectorAndUpdateViewportDoNotClobberEachOther() {
        val state = state()
        state.seqDiagrams.begin("log", setOf(1))

        state.seqDiagrams.updateInspector(open = false, width = 400f)
        state.seqDiagrams.updateViewport(zoom = 1.4f, mode = DiagramZoomMode.MANUAL)

        val session = state.seqDiagrams.activeSession!!
        assertEquals(false, session.inspectorOpen)
        assertEquals(400f, session.inspectorWidth)
        assertEquals(1.4f, session.zoom)
        assertEquals(DiagramZoomMode.MANUAL, session.zoomMode)

        state.seqDiagrams.updateViewport(mode = DiagramZoomMode.FIT)

        // A later updateViewport call must not have reset the inspector fields it never touches.
        val after = state.seqDiagrams.activeSession!!
        assertEquals(false, after.inspectorOpen)
        assertEquals(400f, after.inspectorWidth)
    }

    // ── Canvas click-to-navigate coordinate mapping (Part C) ────────────────────────────────────

    private fun hit(entryId: Int, x: Int, y: Int, w: Int = 20, h: Int = 20) = ArrowHit(0, entryId, x, y, w, h)

    @Test
    fun resolveCanvasClickHitAtIdentityZoomAndDensityMatchesRawImagePixels() {
        val rendered = RenderedDiagram(
            image = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB),
            hits = listOf(hit(entryId = 7, x = 100, y = 40)),
            widthPx = 400, heightPx = 200, scale = 1f,
        )
        val resolved = resolveCanvasClickHit(
            rendered = rendered,
            clickPositionPx = Offset(110f, 50f),
            horizontalScrollPx = 0f, verticalScrollPx = 0f,
            zoom = 1f, density = 1f,
        )
        assertEquals(7, resolved?.entryId)
    }

    @Test
    fun resolveCanvasClickHitAccountsForZoomAndDensity() {
        // rendered.scale = 2f (typical raster scale), zoom = 2f, density = 2f (Retina): the
        // on-screen pixel-per-image-pixel ratio is (zoom*density)/scale = (2*2)/2 = 2 — a click at
        // on-screen (220, 100) should land on image pixel (110, 50), inside the hit box.
        val rendered = RenderedDiagram(
            image = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB),
            hits = listOf(hit(entryId = 3, x = 100, y = 40)),
            widthPx = 800, heightPx = 400, scale = 2f,
        )
        val resolved = resolveCanvasClickHit(
            rendered = rendered,
            clickPositionPx = Offset(220f, 100f),
            horizontalScrollPx = 0f, verticalScrollPx = 0f,
            zoom = 2f, density = 2f,
        )
        assertEquals(3, resolved?.entryId)
    }

    @Test
    fun resolveCanvasClickHitAddsScrollOffsetBeforeMapping() {
        // The hit lives deep in the content (image pixel ~505-515), far past what's visible at the
        // canvas's own top-left. A click near the viewport's origin misses it with no pan; panning
        // by 500px first brings that same on-screen position over the hit, and only THAT click
        // resolves — proving the scroll offset is actually being added, not ignored.
        val rendered = RenderedDiagram(
            image = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB),
            hits = listOf(hit(entryId = 9, x = 505, y = 505, w = 10, h = 10)),
            widthPx = 1000, heightPx = 1000, scale = 1f,
        )
        val missed = resolveCanvasClickHit(
            rendered = rendered,
            clickPositionPx = Offset(5f, 5f),
            horizontalScrollPx = 0f, verticalScrollPx = 0f,
            zoom = 1f, density = 1f,
        )
        assertNull(missed, "without panning, screen position (5,5) is nowhere near the hit at image pixel ~505,505")

        val resolved = resolveCanvasClickHit(
            rendered = rendered,
            clickPositionPx = Offset(5f, 5f),
            horizontalScrollPx = 500f, verticalScrollPx = 500f,
            zoom = 1f, density = 1f,
        )
        assertEquals(9, resolved?.entryId)
    }

    @Test
    fun resolveCanvasClickHitMissesWhenNoArrowIsUnderThePointer() {
        val rendered = RenderedDiagram(
            image = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB),
            hits = listOf(hit(entryId = 1, x = 0, y = 0)),
            widthPx = 400, heightPx = 400, scale = 1f,
        )
        val resolved = resolveCanvasClickHit(
            rendered = rendered,
            clickPositionPx = Offset(300f, 300f),
            horizontalScrollPx = 0f, verticalScrollPx = 0f,
            zoom = 1f, density = 1f,
        )
        assertNull(resolved)
    }
}
