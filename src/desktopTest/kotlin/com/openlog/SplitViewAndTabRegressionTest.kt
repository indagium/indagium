package com.openlog

import com.openlog.model.LogEntry
import com.openlog.model.LogItem
import com.openlog.model.LogLevel
import com.openlog.ui.AnnotationNavigationTarget
import com.openlog.ui.AppState
import com.openlog.ui.LogViewerScrollStateStore
import com.openlog.ui.annotationNavigationTarget
import com.openlog.ui.browserTabOrderDuringDrag
import com.openlog.ui.logItemStableKey
import com.openlog.ui.mkTab
import com.openlog.ui.nextOriginalSelectionAfterFilteredSelection
import com.openlog.ui.splitTabsForVisibility
import com.openlog.ui.tabOrderAfterVisibleReorder
import com.openlog.ui.tabRenderX
import com.openlog.ui.visibleRowRangeIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

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
    fun filteredRangeSelectionMirrorsIntoOriginalPanel() {
        val next = nextOriginalSelectionAfterFilteredSelection(
            filteredSelection = setOf(4, 5, 6),
        )

        assertEquals(setOf(4, 5, 6), next)
    }

    @Test
    fun filteredSingleClickReplacesExistingOriginalRangeSelection() {
        val next = nextOriginalSelectionAfterFilteredSelection(
            filteredSelection = setOf(4),
        )

        assertEquals(setOf(4), next)
    }

    @Test
    fun filteredSingleClickMirrorsWhenOriginalSelectionIsNotARange() {
        val next = nextOriginalSelectionAfterFilteredSelection(
            filteredSelection = setOf(4),
        )

        assertEquals(setOf(4), next)
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

    @Test
    fun logItemKeysIncludeTabIdToAvoidCrossTabContentReuse() {
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "App", "same row id")

        assertEquals("tab-a:r1", logItemStableKey("tab-a", LogItem.Row(entry, 0)))
        assertEquals("tab-b:r1", logItemStableKey("tab-b", LogItem.Row(entry, 0)))
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
