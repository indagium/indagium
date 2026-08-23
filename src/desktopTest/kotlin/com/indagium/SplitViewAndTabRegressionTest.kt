package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.CtxMenuState
import com.indagium.model.Filter
import com.indagium.model.LogEntry
import com.indagium.model.LogItem
import com.indagium.model.LogLevel
import com.indagium.model.ManualCollapseBlock
import com.indagium.model.ManualCollapseDirection
import com.indagium.model.SequenceDef
import com.indagium.ui.AnnotationNavigationTarget
import com.indagium.ui.AppState
import com.indagium.ui.DANGER_RED
import com.indagium.ui.LogViewerScrollStateStore
import com.indagium.ui.TabRef
import com.indagium.ui.annotationNavigationTarget
import com.indagium.ui.browserTabOrderDuringDrag
import com.indagium.ui.centerAnchorIndex
import com.indagium.ui.comparePickerOrderAfterOverflowSelection
import com.indagium.ui.effectiveLogWrapLimitChars
import com.indagium.ui.expansionAndIndexForEntry
import com.indagium.ui.isCrashGroupRow
import com.indagium.ui.isItemPlacementConverged
import com.indagium.ui.keyboardCopyTextForLogPanel
import com.indagium.ui.logItemStableKey
import com.indagium.ui.mkTab
import com.indagium.ui.orderedTabsForComparePicker
import com.indagium.ui.panelCopySelectionIds
import com.indagium.ui.partitionTabOrder
import com.indagium.ui.reconcileTabOrder
import com.indagium.ui.splitTabsForVisibility
import com.indagium.ui.stripVisualWrapBreaks
import com.indagium.ui.tabOrderAfterVisibleReorder
import com.indagium.ui.tabRenderX
import com.indagium.ui.visibleRowRangeIds
import com.indagium.ui.visualLogLineForWrapLimit
import com.indagium.utils.computeItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SplitViewAndTabRegressionTest {
    @Test
    fun reorderTabsCanMoveDraggedTabToEnd() {
        val state = AppState()
        state.tabs = listOf(
            mkTab("a", "a.log", emptyList()),
            mkTab("b", "b.log", emptyList()),
            mkTab("c", "c.log", emptyList()),
        )

        state.reorderTabs("a", beforeId = null)

        assertEquals(listOf("b", "c", "a"), state.tabs.map { it.id })
    }

    @Test
    fun keyboardCopyUsesEffectivePanelSelection() {
        val tab = mkTab(
            "log",
            "test.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "RAW", "first"),
                LogEntry(2, "10:00:00.001", LogLevel.I, "RAW", "second"),
                LogEntry(3, "10:00:00.002", LogLevel.I, "RAW", "third"),
            ),
        )

        assertEquals(setOf(1, 2), panelCopySelectionIds(tab.copy(selected = setOf(1, 2))))
    }

    @Test
    fun keyboardCopyPrefersSelectedTextOverSelectedRows() {
        val copied = keyboardCopyTextForLogPanel(
            selectedText = "only this substring",
            selectedRowsText = { "10:00:00.000  I  App: whole row" },
        )

        assertEquals("only this substring", copied)
    }

    @Test
    fun visualLogLineWrappingOnlyAddsBreaks() {
        val original = "1234567890ABCDEFGHIJ"
        val wrapped = visualLogLineForWrapLimit(original, limitChars = 8)

        assertEquals("12345678\n90ABCDEF\nGHIJ", wrapped)
        assertEquals(original, stripVisualWrapBreaks(wrapped))
    }

    @Test
    fun visualLogLineWrappingBreaksAtWordBoundaryInsteadOfMidWord() {
        // Regression: naive char-count chunking split "stacktrace" into "stacktr"/"ace" — the
        // budget boundary must back up to the last space instead of cutting the word itself.
        val original = "settings_config.xml with stacktrace"
        val wrapped = visualLogLineForWrapLimit(original, limitChars = 30)

        assertEquals("settings_config.xml with \nstacktrace", wrapped)
        assertEquals(original, stripVisualWrapBreaks(wrapped))
    }

    @Test
    fun visualLogLineWrappingHardBreaksAnUnbrokenTokenLongerThanTheLimit() {
        // No space anywhere within budget (a long URI/base64 blob) — must still hard-break so a
        // single token can never overflow the viewport unbounded.
        val original = "prefix_word_" + "x".repeat(20) + "_suffix"
        val wrapped = visualLogLineForWrapLimit(original, limitChars = 10)

        assertEquals(original, stripVisualWrapBreaks(wrapped))
        assertTrue(wrapped.lines().all { it.length <= 10 })
    }

    @Test
    fun isCrashGroupRowOnlyWhenSettingOnAndColorIsExactlyDangerRed() {
        assertTrue(isCrashGroupRow(DANGER_RED, highlightEntireCrashGroup = true))
        assertFalse(isCrashGroupRow(DANGER_RED, highlightEntireCrashGroup = false))
        // A sequence/manual-collapse groupColor is never exactly DANGER_RED (see computeItems) —
        // must not be treated as a crash-group row even with the setting on.
        assertFalse(isCrashGroupRow(Color.Red, highlightEntireCrashGroup = true))
        assertFalse(isCrashGroupRow(null, highlightEntireCrashGroup = true))
    }

    @Test
    fun autoLogWrapLimitFollowsVisibleWidth() {
        val narrow = effectiveLogWrapLimitChars(
            auto = true,
            configuredLimitChars = 480,
            visibleWidthDp = 500f,
            charWidthDp = 7f,
        )
        val wide = effectiveLogWrapLimitChars(
            auto = true,
            configuredLimitChars = 480,
            visibleWidthDp = 1000f,
            charWidthDp = 7f,
        )
        val enoughForContext = effectiveLogWrapLimitChars(
            auto = true,
            configuredLimitChars = 480,
            visibleWidthDp = 760f,
            charWidthDp = 7f,
        )

        assertEquals(480, effectiveLogWrapLimitChars(false, 480, visibleWidthDp = 500f, charWidthDp = 7f))
        assertTrue(narrow < wide)
        assertTrue(narrow in 80 until 480)
        assertTrue(enoughForContext >= 100)
    }

    @Test
    fun autoLogWrapLimitPacksFewerCharsWhenGlyphsAreWider() {
        // Regression: an underestimated char width lets visualLogLineForWrapLimit pack more
        // characters into a manual line-break than the font actually renders, so
        // BasicTextField's own wrapping silently adds an extra real line on top of the intended
        // count. A wider measured glyph width must yield a smaller (not larger) char limit.
        val withNarrowGlyphs = effectiveLogWrapLimitChars(
            auto = true, configuredLimitChars = 480, visibleWidthDp = 760f, charWidthDp = 6f,
        )
        val withWiderGlyphs = effectiveLogWrapLimitChars(
            auto = true, configuredLimitChars = 480, visibleWidthDp = 760f, charWidthDp = 8f,
        )

        assertTrue(withWiderGlyphs < withNarrowGlyphs)
    }

    @Test
    fun annotationNavigationPrefersFilteredPanelWhenReferencedLineIsVisible() {
        val target = annotationNavigationTarget(
            referencedIds = listOf(4, 9),
            filteredVisibleIds = listOf(1, 4, 7),
            originalOpen = false,
        )

        assertEquals(AnnotationNavigationTarget(filteredEntryId = 4, originalEntryId = null), target)
    }

    @Test
    fun annotationNavigationTargetsOriginalOnlyWhenOriginalIsOpen() {
        val target = annotationNavigationTarget(
            referencedIds = listOf(4, 9),
            filteredVisibleIds = listOf(1, 4, 7),
            originalOpen = true,
        )

        assertEquals(AnnotationNavigationTarget(filteredEntryId = 4, originalEntryId = 4), target)
    }

    @Test
    fun annotationNavigationDoesNotAutoOpenOriginalWhenFiltersHideReferencedLines() {
        val target = annotationNavigationTarget(
            referencedIds = listOf(4, 9),
            filteredVisibleIds = listOf(1, 2, 3),
            originalOpen = false,
        )

        assertEquals(null, target)
    }

    @Test
    fun annotationNavigationUsesAlreadyOpenOriginalWhenFiltersHideReferencedLines() {
        val target = annotationNavigationTarget(
            referencedIds = listOf(4, 9),
            filteredVisibleIds = listOf(1, 2, 3),
            originalOpen = true,
        )

        assertEquals(AnnotationNavigationTarget(filteredEntryId = null, originalEntryId = 4), target)
    }

    @Test
    fun annotationNavigationCanRevealLineInsideCollapsedManualBlock() {
        val tab = mkTab(
            "log",
            "test.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "App", "first"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "App", "referenced"),
                LogEntry(3, "10:00:00.200", LogLevel.I, "App", "manual anchor"),
            ),
        ).copy(
            manualBlocks = listOf(ManualCollapseBlock("m1", 3, ManualCollapseDirection.TO_START)),
        )

        val target = expansionAndIndexForEntry(tab, applyFilter = true, entryId = 2)

        assertEquals(setOf("m1"), target?.expanded)
        val expandedItems = computeItems(tab.copy(expanded = target!!.expanded), applyFilter = true)
        assertEquals(2, expandedItems[target.index].let { (it as LogItem.Row).entry.id })
    }

    // The manual-block case above and the filter-exclusion case below are the existing pair; this
    // proves expansionAndIndexForEntry resolves the third kind of fold — a collapsed SEQUENCE group
    // (SeqComputer.kt, not a manual block) — the same way, since it's what backs Follow's reveal
    // path (AppState.followRevealTarget) for a sequence-folded row.
    @Test
    fun annotationNavigationCanRevealLineInsideCollapsedSequenceGroup() {
        val tab = mkTab(
            "log",
            "test.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Auth", "request started"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "com.app.Auth", "referenced"),
                LogEntry(3, "10:00:00.200", LogLevel.I, "com.app.Auth", "third"),
            ),
        ).copy(
            filter = Filter(
                sequences = listOf(
                    SequenceDef("auth-start", "request started", priority = 1, color = Color.Red, tag = "com.app.Auth"),
                ),
            ),
            // expanded stays empty (default) -> the sequence group renders collapsed, folding 2 and 3
            // under row 1's header exactly like SequenceGroupingTest's own no-end-pattern fixture.
        )

        val target = expansionAndIndexForEntry(tab, applyFilter = true, entryId = 2)

        assertEquals(1, assertNotNull(target).expanded.size)
        val expandedItems = computeItems(tab.copy(expanded = target.expanded), applyFilter = true)
        assertEquals(2, expandedItems[target.index].let { (it as LogItem.Row).entry.id })
    }

    @Test
    fun jumpToEntryLandsOnACollapsedStackTraceHeaderWithoutExpandingIt() {
        // CHANGE A (reverted "expand the header that displays the target" behaviour): a
        // StackTraceHeader displays its own root entry whether folded or open (Filter.kt:685-688), so
        // the Issues panel's CrashSite — which points straight at that root — must land on the header
        // as-is and leave it COLLAPSED; the user opens the trace themselves. Fixture mirrors
        // StackTraceComputerTest's real-dump shape.
        val tab = mkTab(
            "log",
            "test.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "ActivityManager", "unrelated line", pid = 100),
                LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
                LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "java.lang.NullPointerException: boom", pid = 100),
                LogEntry(4, "10:00:00.300", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
                LogEntry(5, "10:00:00.400", LogLevel.E, "AndroidRuntime", "    at android.app.Activity.performCreate(Activity.java:1)", pid = 100),
            ),
        )

        val target = expansionAndIndexForEntry(tab, applyFilter = true, entryId = 2)

        assertEquals(tab.expanded, assertNotNull(target).expanded)
        val expandedItems = computeItems(tab.copy(expanded = target.expanded), applyFilter = true)
        val header = expandedItems[target.index] as LogItem.StackTraceHeader
        assertEquals(2, header.entry.id)
        assertFalse(header.expanded)
    }

    @Test
    fun jumpToEntryLandsOnACollapsedManualHeaderWithoutExpandingIt() {
        // CHANGE A: a TO_START ManualHeader displays the block's own anchor entry, and the anchor's
        // own Row is deliberately filtered out of the block's rendered interior (Filter.kt:673-678) —
        // landing on the anchor id itself must resolve directly to that header without opening it.
        val tab = mkTab(
            "log",
            "test.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "App", "first"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "App", "referenced"),
                LogEntry(3, "10:00:00.200", LogLevel.I, "App", "manual anchor"),
            ),
        ).copy(
            manualBlocks = listOf(ManualCollapseBlock("m1", 3, ManualCollapseDirection.TO_START)),
        )

        val target = expansionAndIndexForEntry(tab, applyFilter = true, entryId = 3)

        assertEquals(tab.expanded, assertNotNull(target).expanded)
        val expandedItems = computeItems(tab.copy(expanded = target.expanded), applyFilter = true)
        val header = expandedItems[target.index] as LogItem.ManualHeader
        assertEquals(3, header.entry.id)
        assertFalse(header.expanded)
    }

    @Test
    fun jumpToEntryLandsOnACollapsedSequenceHeaderWithoutExpandingIt() {
        // CHANGE A: a SeqHeader displays the sequence's own root entry whether folded or open
        // (Filter.kt:630) — companion to the "referenced line inside the group" test above, but here
        // the target IS the header's own line, which must resolve directly to it, collapsed.
        val tab = mkTab(
            "log",
            "test.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Auth", "request started"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "com.app.Auth", "referenced"),
                LogEntry(3, "10:00:00.200", LogLevel.I, "com.app.Auth", "third"),
            ),
        ).copy(
            filter = Filter(
                sequences = listOf(
                    SequenceDef("auth-start", "request started", priority = 1, color = Color.Red, tag = "com.app.Auth"),
                ),
            ),
        )

        val target = expansionAndIndexForEntry(tab, applyFilter = true, entryId = 1)

        assertEquals(tab.expanded, assertNotNull(target).expanded)
        val expandedItems = computeItems(tab.copy(expanded = target.expanded), applyFilter = true)
        val header = expandedItems[target.index] as LogItem.SeqHeader
        assertEquals(1, header.entry.id)
        assertFalse(header.expanded)
    }

    @Test
    fun jumpToEntryExpandsOnlyTheOuterManualBlockWhenAStackTraceRootSitsInsideIt() {
        // The Issues-panel scenario: a crash site (a StackTraceHeader root) nested inside a collapsed
        // TO_START manual block. entryId resolves through the OUTER fold ONLY — found via the
        // ranked-candidate search, since nothing displays entryId while the block is collapsed —
        // landing on the still-collapsed inner StackTraceHeader itself. CHANGE A: the inner trace is
        // deliberately left collapsed; the loop must not also expand the header that now displays the
        // target once the outer block opens.
        val tab = mkTab(
            "log",
            "test.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "ActivityManager", "unrelated line", pid = 100),
                LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
                LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "java.lang.NullPointerException: boom", pid = 100),
                LogEntry(4, "10:00:00.300", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
                LogEntry(5, "10:00:00.400", LogLevel.E, "AndroidRuntime", "    at android.app.Activity.performCreate(Activity.java:1)", pid = 100),
                LogEntry(6, "10:00:00.500", LogLevel.I, "App", "manual anchor", pid = 100),
            ),
        ).copy(
            manualBlocks = listOf(ManualCollapseBlock("m1", 6, ManualCollapseDirection.TO_START)),
        )

        val target = expansionAndIndexForEntry(tab, applyFilter = true, entryId = 2)

        assertEquals(setOf("m1"), target?.expanded)
        val expandedItems = computeItems(tab.copy(expanded = target!!.expanded), applyFilter = true)
        val header = expandedItems[target.index] as LogItem.StackTraceHeader
        assertEquals(2, header.entry.id)
        assertFalse(header.expanded)
    }

    @Test
    fun jumpToEntryLeavesExpansionUntouchedWhenTargetIsAlreadyAPlainRow() {
        // Guard: an entry that's already visible as a plain Row (no fold involved at all) must not
        // trigger any expansion — this is the ordinary, overwhelmingly common case, and the fix
        // above must not regress it into probing/expanding groups it never needed to touch.
        val tab = mkTab(
            "log",
            "test.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "App", "first"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "App", "second"),
                LogEntry(3, "10:00:00.200", LogLevel.I, "App", "third"),
            ),
        )

        val target = expansionAndIndexForEntry(tab, applyFilter = true, entryId = 2)

        assertEquals(tab.expanded, assertNotNull(target).expanded)
        val items = computeItems(tab.copy(expanded = target.expanded), applyFilter = true)
        assertEquals(2, (items[target.index] as LogItem.Row).entry.id)
    }

    @Test
    fun expansionAndIndexForEntryReturnsNullImmediatelyForAnEntryExcludedByTheFilter() {
        // Regression: before the fast-fail check, this burned up to 24 rounds of full
        // computeItems() recomputation trying every collapsed header in the file for an entry
        // that can never be surfaced by expanding anything — on a large log, a bulk hide/exclude
        // ctx action that requested navigation to its own (now-excluded) row felt like a hang.
        val tab = mkTab(
            "log",
            "test.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "App", "first"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "App", "boom"),
                LogEntry(3, "10:00:00.200", LogLevel.I, "App", "third"),
            ),
        ).copy(filter = Filter(excludeKw = "boom"))

        val target = expansionAndIndexForEntry(tab, applyFilter = true, entryId = 2)

        assertEquals(null, target)
    }

    @Test
    fun centerAnchorIndexUsesCurrentViewportHeight() {
        val currentSplitViewportRows = List(16) { 32 }

        val anchorIndex = centerAnchorIndex(index = 14, viewportHeight = 16 * 32, visibleItemSizes = currentSplitViewportRows)

        assertEquals(6, anchorIndex)
    }

    // 1.3(d): isItemPlacementConverged is centerOnItem's stopping condition, extracted as a pure
    // function the same way centerAnchorIndex above is. Covers the normal (fits-in-viewport) case
    // from both sides plus the tall-row case (wrap-on-overflow can make a single row taller than
    // the viewport — see the function's doc for why "top pinned at 0" is the best available
    // placement there, not "fits entirely").
    @Test
    fun itemPlacementConvergedForNormalRowCenteredInViewport() {
        // A 32px row sitting mid-viewport (offset 84 .. 116 inside a 200px viewport) fits
        // entirely, so this is already converged.
        assertTrue(isItemPlacementConverged(offset = 84, size = 32, viewportHeight = 200))
    }

    @Test
    fun itemPlacementNotConvergedWhenRowPartlyAboveViewport() {
        // Negative offset means the row's top has scrolled above the viewport start.
        assertFalse(isItemPlacementConverged(offset = -10, size = 32, viewportHeight = 200))
    }

    @Test
    fun itemPlacementNotConvergedWhenRowPartlyBelowViewport() {
        // offset + size spills past viewportHeight.
        assertFalse(isItemPlacementConverged(offset = 190, size = 32, viewportHeight = 200))
    }

    @Test
    fun itemPlacementConvergedForRowExactlyViewportHeight() {
        // size == viewportHeight is the boundary between the two branches of the predicate;
        // pinned at the top it fits exactly, so it must converge either way it's evaluated.
        assertTrue(isItemPlacementConverged(offset = 0, size = 200, viewportHeight = 200))
    }

    @Test
    fun itemPlacementConvergedForRowTallerThanViewportWithTopAtZero() {
        // A single very-long wrapped line (raw stack trace / JSON dump / base64 blob) can be
        // taller than the whole viewport. There is no placement that shows all of it, so pinning
        // its top edge at the viewport top is the best available placement and must converge.
        assertTrue(isItemPlacementConverged(offset = 0, size = 5000, viewportHeight = 200))
    }

    @Test
    fun itemPlacementNotConvergedForTallRowScrolledPastItsTop() {
        // Same oversized row, but scrolled so its top has already moved above the viewport start
        // (offset negative) — its beginning is hidden, which is exactly what this predicate must
        // reject even though "fits entirely" could never be satisfied for a row this tall.
        assertFalse(isItemPlacementConverged(offset = -50, size = 5000, viewportHeight = 200))
    }

    @Test
    fun selectedFilteredRowsCanBeCopiedAfterRangeSelection() {
        val state = AppState()
        state.tabs = listOf(
            mkTab(
                "log",
                "test.log",
                listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.I, "App", "one"),
                    LogEntry(2, "10:00:00.100", LogLevel.I, "App", "two"),
                    LogEntry(3, "10:00:00.200", LogLevel.I, "App", "three"),
                ),
            ),
        )

        state.setSelectedRows("log", listOf(1, 2, 3))

        assertEquals(setOf(1, 2, 3), state.tabs.single().selected)
    }

    @Test
    fun selectedLineTextCanUsePanelLocalSelection() {
        val state = AppState()
        state.tabs = listOf(
            mkTab(
                "log",
                "test.log",
                listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.I, "App", "filtered"),
                    LogEntry(2, "10:00:00.100", LogLevel.W, "Binder", "original one", pid = 42, tid = 7),
                    LogEntry(3, "10:00:00.200", LogLevel.E, "Binder", "original two", pid = 42, tid = 7),
                ),
            ).copy(selected = setOf(1)),
        )

        val text = state.selectedLinesText("log", explicitIds = setOf(2, 3))

        assertEquals(
            "10:00:00.100  W/Binder  original one\n" +
                "10:00:00.200  E/Binder  original two",
            text,
        )
    }

    @Test
    fun selectedMarkdownTextCanUsePanelLocalSelection() {
        val state = AppState()
        state.tabs = listOf(
            mkTab(
                "log",
                "test.log",
                listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.I, "App", "filtered"),
                    LogEntry(2, "10:00:00.100", LogLevel.W, "Binder", "original one"),
                    LogEntry(3, "10:00:00.200", LogLevel.E, "Binder", "original two"),
                ),
            ).copy(selected = setOf(1)),
        )

        val text = state.selectedLinesMarkdownText("log", explicitIds = setOf(2, 3))

        assertEquals(
            "**[10:00:00.100] `W/Binder`:** original one\n" +
                "**[10:00:00.200] `E/Binder`:** original two",
            text,
        )
    }

    @Test
    fun selectedMarkdownTextFallsBackToTabSelection() {
        val state = AppState()
        state.tabs = listOf(
            mkTab(
                "log",
                "test.log",
                listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.I, "App", "one"),
                    LogEntry(2, "10:00:00.100", LogLevel.W, "Binder", "two"),
                ),
            ).copy(selected = setOf(1, 2)),
        )

        val text = state.selectedLinesMarkdownText("log")

        assertEquals(
            "**[10:00:00.000] `I/App`:** one\n" +
                "**[10:00:00.100] `W/Binder`:** two",
            text,
        )
    }

    @Test
    fun visibleRowRangeIdsUsesCurrentPanelVisibleOrder() {
        val visibleIds = listOf(10, 20, 30, 40)

        assertEquals(listOf(20, 30, 40), visibleRowRangeIds(20, 40, visibleIds))
        assertEquals(listOf(20, 30, 40), visibleRowRangeIds(40, 20, visibleIds))
    }

    @Test
    fun browserTabOrderDuringDragMovesTabAsItsCenterCrossesNeighbors() {
        val order = browserTabOrderDuringDrag(
            visibleIds = listOf("a", "b", "c", "d"),
            draggedId = "a",
            dragStartIndex = 0,
            dragOffsetX = 360f,
            tabWidth = 100f,
        )

        assertEquals(listOf("b", "c", "d", "a"), order)
    }

    @Test
    fun browserTabOrderDuringDragMovesBeforeCenterFullyCrossesNeighbor() {
        val order = browserTabOrderDuringDrag(
            visibleIds = listOf("a", "b", "c"),
            draggedId = "a",
            dragStartIndex = 0,
            dragOffsetX = 76f,
            tabWidth = 100f,
        )

        assertEquals(listOf("b", "a", "c"), order)
    }

    @Test
    fun browserTabOrderDuringDragCanMoveTabLeft() {
        val order = browserTabOrderDuringDrag(
            visibleIds = listOf("a", "b", "c", "d"),
            draggedId = "d",
            dragStartIndex = 3,
            dragOffsetX = -360f,
            tabWidth = 100f,
        )

        assertEquals(listOf("d", "a", "b", "c"), order)
    }

    @Test
    fun releasedDraggedTabRendersAtFinalTargetInsteadOfAnimatedOrigin() {
        val x = tabRenderX(
            isDragging = false,
            isJustReleased = true,
            pointerX = 176f,
            targetX = 100f,
            animatedX = 8f,
        )

        assertEquals(100f, x)
    }

    @Test
    fun visibleTabsUseTailOfTabOrderWhenCapped() {
        val tabs = (1..10).map { idx -> mkTab("t$idx", "tab-$idx.log", emptyList()) }

        val (visible, overflow) = splitTabsForVisibility(
            tabs = tabs,
            containerPx = 2000,
            minTabPx = 80,
            overflowButtonPx = 40,
            visibleTabLimit = 8,
        )

        assertEquals((3..10).map { "t$it" }, visible.map { it.id })
        assertEquals(listOf("t1", "t2"), overflow.map { it.id })
    }

    @Test
    fun comparePickerCanOverflowWhenManyTabsDoNotFit() {
        val tabs = (1..8).map { idx -> mkTab("t$idx", "tab-$idx.log", emptyList()) }

        val (visible, overflow) = splitTabsForVisibility(
            tabs = tabs,
            containerPx = 360,
            minTabPx = 100,
            overflowButtonPx = 44,
            visibleTabLimit = 8,
        )

        assertEquals(listOf("t6", "t7", "t8"), visible.map { it.id })
        assertEquals((1..5).map { "t$it" }, overflow.map { it.id })
    }

    @Test
    fun comparePickerOrderingCanDifferFromMainTabsOrder() {
        val tabs = (1..5).map { idx -> mkTab("t$idx", "tab-$idx.log", emptyList()) }

        val ordered = orderedTabsForComparePicker(
            tabs = tabs,
            orderIds = listOf("t1", "t3", "t2", "t4", "t5"),
        )

        assertEquals(listOf("t1", "t3", "t2", "t4", "t5"), ordered.map { it.id })
        assertEquals((1..5).map { "t$it" }, tabs.map { it.id })
    }

    @Test
    fun comparePickerOverflowSelectionPromotesOnlyCompareOrder() {
        val mainOrder = (1..5).map { idx -> "t$idx" }

        val compareOrder = comparePickerOrderAfterOverflowSelection(mainOrder, "t1")

        assertEquals(listOf("t2", "t3", "t4", "t5", "t1"), compareOrder)
        assertEquals((1..5).map { "t$it" }, mainOrder)
    }

    @Test
    fun newlyAddedTabPushesFirstVisibleTabToOverflow() {
        val tabs = (1..5).map { idx -> mkTab("t$idx", "tab-$idx.log", emptyList()) }

        val (visible, overflow) = splitTabsForVisibility(
            tabs = tabs,
            containerPx = 2000,
            minTabPx = 80,
            overflowButtonPx = 40,
            visibleTabLimit = 4,
        )

        assertEquals(listOf("t2", "t3", "t4", "t5"), visible.map { it.id })
        assertEquals(listOf("t1"), overflow.map { it.id })
    }

    @Test
    fun visibleDragKeepsOverflowBeforeVisibleTail() {
        val order = tabOrderAfterVisibleReorder(
            visibleIds = listOf("t3", "t4", "t5", "t6"),
            overflowIds = listOf("t1", "t2"),
        )

        assertEquals(listOf("t1", "t2", "t3", "t4", "t5", "t6"), order)
    }

    // ── WP6: unified log/diagram tab strip (ui/TabBar.kt) ──────────────────────────────────────

    @Test
    fun reconcileTabOrderPreservesSurvivingEntriesOrderAcrossAnOpenAndAClose() {
        // "a" closes, a new diagram "seq3-2" opens — "seq3-1" and "b" must stay exactly where they
        // were relative to each other, and the newcomer lands at the end.
        val previous = listOf(TabRef.Log("a"), TabRef.Diagram("seq3-1"), TabRef.Log("b"))

        val reconciled = reconcileTabOrder(
            previousOrder = previous,
            logTabIds = listOf("b"),
            diagramSessionIds = listOf("seq3-1", "seq3-2"),
        )

        assertEquals(
            listOf(TabRef.Diagram("seq3-1"), TabRef.Log("b"), TabRef.Diagram("seq3-2")),
            reconciled,
        )
    }

    @Test
    fun reconcileTabOrderAppendsBrandNewTabsInEachStoresOwnOrder() {
        val reconciled = reconcileTabOrder(
            previousOrder = emptyList(),
            logTabIds = listOf("a", "b"),
            diagramSessionIds = listOf("seq3-1"),
        )

        assertEquals(listOf(TabRef.Log("a"), TabRef.Log("b"), TabRef.Diagram("seq3-1")), reconciled)
    }

    @Test
    fun reconcileTabOrderDropsClosedTabsWithoutDisturbingSurvivors() {
        val previous = listOf(TabRef.Log("a"), TabRef.Log("b"), TabRef.Diagram("seq3-1"))

        val reconciled = reconcileTabOrder(
            previousOrder = previous,
            logTabIds = listOf("a"),
            diagramSessionIds = emptyList(),
        )

        assertEquals(listOf(TabRef.Log("a")), reconciled)
    }

    @Test
    fun partitionTabOrderSeparatesLogAndDiagramIdsPreservingEachKindsRelativeOrder() {
        val order = listOf(
            TabRef.Log("a"),
            TabRef.Diagram("seq3-1"),
            TabRef.Log("b"),
            TabRef.Diagram("seq3-2"),
            TabRef.Log("c"),
        )

        val (logIds, diagramIds) = partitionTabOrder(order)

        assertEquals(listOf("a", "b", "c"), logIds)
        assertEquals(listOf("seq3-1", "seq3-2"), diagramIds)
    }

    @Test
    fun partitionTabOrderHandlesADiagramDraggedBetweenTwoLogTabs() {
        val order = listOf(TabRef.Log("a"), TabRef.Log("b"), TabRef.Diagram("seq3-1"), TabRef.Log("c"))

        val (logIds, diagramIds) = partitionTabOrder(order)

        assertEquals(listOf("a", "b", "c"), logIds)
        assertEquals(listOf("seq3-1"), diagramIds)
    }

    @Test
    fun partitionTabOrderHandlesALogTabDraggedBetweenTwoDiagramTabs() {
        val order = listOf(TabRef.Diagram("seq3-1"), TabRef.Log("a"), TabRef.Diagram("seq3-2"))

        val (logIds, diagramIds) = partitionTabOrder(order)

        assertEquals(listOf("a"), logIds)
        assertEquals(listOf("seq3-1", "seq3-2"), diagramIds)
    }

    @Test
    fun splitTabsForVisibilityHonoursLimitOverAMixedLogAndDiagramList() {
        val refs = listOf(
            TabRef.Log("a"),
            TabRef.Log("b"),
            TabRef.Diagram("seq3-1"),
            TabRef.Log("c"),
            TabRef.Diagram("seq3-2"),
        )

        val (visible, overflow) = splitTabsForVisibility(
            tabs = refs,
            containerPx = 2000,
            minTabPx = 80,
            overflowButtonPx = 40,
            visibleTabLimit = 3,
        )

        assertEquals(listOf(TabRef.Diagram("seq3-1"), TabRef.Log("c"), TabRef.Diagram("seq3-2")), visible)
        assertEquals(listOf(TabRef.Log("a"), TabRef.Log("b")), overflow)
    }

    @Test
    fun splitTabsForVisibilityLimitAloneCanOverflowAMixedListEvenWithRoomToSpare() {
        val refs = listOf(TabRef.Log("a"), TabRef.Diagram("seq3-1"), TabRef.Log("b"), TabRef.Diagram("seq3-2"))

        val (visible, overflow) = splitTabsForVisibility(
            tabs = refs,
            containerPx = 2000,
            minTabPx = 80,
            overflowButtonPx = 40,
            visibleTabLimit = 2,
        )

        assertEquals(listOf(TabRef.Log("b"), TabRef.Diagram("seq3-2")), visible)
        assertEquals(listOf(TabRef.Log("a"), TabRef.Diagram("seq3-1")), overflow)
    }

    @Test
    fun logItemKeysIncludeTabIdToAvoidCrossTabContentReuse() {
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "App", "same row id")

        assertEquals("tab-a:r1", logItemStableKey("tab-a", LogItem.Row(entry, 0)))
        assertEquals("tab-b:r1", logItemStableKey("tab-b", LogItem.Row(entry, 0)))
    }

    @Test
    fun addingSequenceFromTheNonActiveComparePanelLandsOnThatTabNotTheActiveOne() {
        // Regression: BoundFilterPanel's onAddSeq (FileView.kt) is bound per-tab and always has
        // the correct tab.id in scope — addSequence must honor it even when a *different* tab
        // (tab "a") is the compare-mode activeTabId.
        val state = AppState()
        state.tabs = listOf(
            mkTab("a", "a.log", emptyList()),
            mkTab("b", "b.log", emptyList()),
        )
        state.activeTabId = "a"
        state.compareMode = true
        state.compareTabId = "b"

        state.addSequence("b", "boot sequence", false, Color.Red)

        assertEquals(listOf("boot sequence"), state.tab("b")!!.filter.sequences.map { it.matchText })
        assertTrue(state.tab("a")!!.filter.sequences.isEmpty())
    }

    @Test
    fun addingSequenceFromContextMenuOnTheNonActiveComparePanelLandsOnThatTab() {
        // Regression: addSeqFromCtx resolves its target entry via ctx.tabId, but used to call the
        // (then tab-less) addSequence which silently wrote to activeTabId instead — landing the
        // sequence on the wrong side of a compare-mode split.
        val state = AppState()
        state.tabs = listOf(
            mkTab(
                "a",
                "a.log",
                listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "irrelevant")),
            ),
            mkTab(
                "b",
                "b.log",
                listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Boot", "boot sequence")),
            ),
        )
        state.activeTabId = "a"
        state.compareMode = true
        state.compareTabId = "b"
        state.ctx = CtxMenuState("b", 1, 0f, 0f, "")

        state.addSeqFromCtx()

        assertEquals(listOf("boot sequence"), state.tab("b")!!.filter.sequences.map { it.matchText })
        assertTrue(state.tab("a")!!.filter.sequences.isEmpty())
    }

    @Test
    fun removingASequenceFromTheNonActiveComparePanelChangesOnlyThatTab() {
        // Regression: removeSequence (like updateSequence/toggleSequence/setSequenceColor) used
        // to hardcode upFlt(activeTabId) exactly like addSequence did — BoundFilterPanel's
        // onRemoveSeq is bound per-tab (FileView.kt) and must target the panel's own tab, not
        // whichever tab happens to be compare-mode active.
        val state = AppState()
        state.tabs = listOf(
            mkTab("a", "a.log", emptyList()).copy(
                filter = Filter(sequences = listOf(SequenceDef("seq-a", "in a", priority = 1, color = Color.Red))),
            ),
            mkTab("b", "b.log", emptyList()).copy(
                filter = Filter(sequences = listOf(SequenceDef("seq-b", "in b", priority = 1, color = Color.Blue))),
            ),
        )
        state.activeTabId = "a"
        state.compareMode = true
        state.compareTabId = "b"

        state.removeSequence("b", "seq-b")

        assertTrue(state.tab("b")!!.filter.sequences.isEmpty())
        assertEquals(listOf("in a"), state.tab("a")!!.filter.sequences.map { it.matchText })
    }

    @Test
    fun reorderingSequencesOnTheNonActiveComparePanelChangesOnlyThatTab() {
        // Regression: reorderSequence (and moveSequenceUp/Down) had the same hardcoded
        // upFlt(activeTabId) bug — dragging a sequence in the non-active compare panel used to
        // silently reorder the active tab's sequence list instead.
        val state = AppState()
        state.tabs = listOf(
            mkTab("a", "a.log", emptyList()).copy(
                filter = Filter(sequences = listOf(SequenceDef("seq-a", "in a", priority = 1, color = Color.Red))),
            ),
            mkTab("b", "b.log", emptyList()).copy(
                filter = Filter(
                    sequences = listOf(
                        SequenceDef("first", "first", priority = 1, color = Color.Red),
                        SequenceDef("second", "second", priority = 2, color = Color.Blue),
                    ),
                ),
            ),
        )
        state.activeTabId = "a"
        state.compareMode = true
        state.compareTabId = "b"

        state.moveSequenceUp("b", "second")

        assertEquals(listOf("second", "first"), state.tab("b")!!.filter.sequences.map { it.id })
        assertEquals(listOf("in a"), state.tab("a")!!.filter.sequences.map { it.matchText })
    }

    @Test
    fun scrollStateStoreKeepsPositionStatePerTabPanelAcrossSwitches() {
        val store = LogViewerScrollStateStore()

        val first = store.lazyState("tab-a:main")
        first.requestScrollToItem(12, 3)

        store.lazyState("tab-b:main")
        val firstAgain = store.lazyState("tab-a:main")

        assertSame(first, firstAgain)
        assertEquals(12, firstAgain.firstVisibleItemIndex)
        assertEquals(3, firstAgain.firstVisibleItemScrollOffset)
        assertNotSame(first, store.lazyState("tab-b:main"))
    }
}
