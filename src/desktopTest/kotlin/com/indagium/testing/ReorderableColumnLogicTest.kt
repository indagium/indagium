package com.indagium.testing

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.indagium.ui.altArrowDirection
import com.indagium.ui.reorderAltTarget
import com.indagium.ui.reorderButtonTarget
import com.indagium.ui.reorderCommitIndex
import com.indagium.ui.reorderDragTranslation
import com.indagium.ui.reorderOrderDuringDrag
import com.indagium.ui.reorderRowHeights
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The index math behind ReorderableColumn: no Compose, only the pure helpers it is built on. */
class ReorderableColumnLogicTest {
    private val ids = listOf("a", "b", "c", "d")
    private val rowHeight = 40f

    private fun fixedOrder(dragged: String, offset: Float) =
        reorderOrderDuringDrag(ids, dragged, ids.indexOf(dragged), offset, rowHeight) { rowHeight }

    @Test
    fun aFixedHeightDragBelowTheSnapThresholdKeepsTheOrder() {
        assertEquals(ids, fixedOrder("a", 0f))
        assertEquals(ids, fixedOrder("a", 25f), "centre plus snap bias is still above b's centre")
        assertEquals(ids, fixedOrder("c", -5f))
    }

    @Test
    fun aFixedHeightDragDownPastTheNextCentreMovesTheRowOnePlaceAtATime() {
        assertEquals(listOf("b", "a", "c", "d"), fixedOrder("a", 31f))
        assertEquals(listOf("b", "c", "a", "d"), fixedOrder("a", 80f))
        assertEquals(listOf("b", "c", "d", "a"), fixedOrder("a", 500f))
    }

    @Test
    fun aFixedHeightDragUpMirrorsTheDownDrag() {
        assertEquals(listOf("a", "d", "b", "c"), fixedOrder("d", -80f))
        assertEquals(listOf("d", "a", "b", "c"), fixedOrder("d", -500f))
    }

    @Test
    fun anUnknownDraggedRowLeavesTheOrderAlone() {
        assertEquals(ids, reorderOrderDuringDrag(ids, "zzz", 0, 100f, rowHeight) { rowHeight })
        assertEquals(ids, reorderOrderDuringDrag(ids, null, 0, 100f, rowHeight) { rowHeight })
    }

    @Test
    fun variableHeightsUseTheMeasuredRowsNotAnAverage() {
        val heights = mapOf("a" to 40f, "b" to 200f, "c" to 40f, "d" to 40f)

        fun order(dragged: String, offset: Float) =
            reorderOrderDuringDrag(ids, dragged, ids.indexOf(dragged), offset, fixedRowHeight = null) { heights.getValue(it) }

        // b is 200 tall: its centre sits at 40 + 100 = 140, far below what a 40px row would put it at.
        assertEquals(ids, order("a", 100f), "a's centre (20 + 100 + bias 10) is still above b's centre")
        assertEquals(listOf("b", "a", "c", "d"), order("a", 115f))
        assertEquals(listOf("b", "c", "a", "d"), order("a", 260f))
        // Dragging c up: its centre has to cross b's centre (140) to pass it.
        assertEquals(ids, order("c", -80f))
        assertEquals(listOf("a", "c", "b", "d"), order("c", -120f))
    }

    @Test
    fun theDragCommitIndexIsTheLiveOrderPositionOrNullWhenUnmoved() {
        assertNull(reorderCommitIndex(ids, ids, "b"))
        assertEquals(2, reorderCommitIndex(ids, listOf("a", "c", "b", "d"), "b"))
        assertEquals(0, reorderCommitIndex(ids, listOf("c", "a", "b", "d"), "c"))
        assertNull(reorderCommitIndex(ids, listOf("a", "b"), "zzz"))
    }

    @Test
    fun altArrowTargetsStopAtTheEdges() {
        assertNull(reorderAltTarget(ids, "a", up = true))
        assertEquals(1, reorderAltTarget(ids, "a", up = false))
        assertEquals(0, reorderAltTarget(ids, "b", up = true))
        assertEquals(3, reorderAltTarget(ids, "c", up = false))
        assertNull(reorderAltTarget(ids, "d", up = false))
        assertNull(reorderAltTarget(ids, "zzz", up = true))
        assertNull(reorderAltTarget(emptyList(), "a", up = false))
    }

    @Test
    fun moveButtonsUseTheSameTargetsAndAreInertAtTheEdges() {
        assertNull(reorderButtonTarget(ids, "a", -1))
        assertEquals(1, reorderButtonTarget(ids, "a", +1))
        assertEquals(2, reorderButtonTarget(ids, "b", +1))
        assertNull(reorderButtonTarget(ids, "d", +1))
    }

    @Test
    fun rowHeightsPreferFixedThenMeasuredThenTheEstimate() {
        assertEquals(mapOf("a" to 40f, "b" to 40f), reorderRowHeights(listOf("a", "b"), 40f, mapOf("a" to 99f), 10f))
        assertEquals(mapOf("a" to 99f, "b" to 10f), reorderRowHeights(listOf("a", "b"), null, mapOf("a" to 99f), 10f))
    }

    @Test
    fun theDraggedRowFollowsThePointerWhateverSlotItNowOccupies() {
        // Started at top 80, dragged 50 down: it should be drawn at 130. Its slot in the live layout is 120 (it just
        // swapped places with the row below), so it needs a +10 shift; before any swap the slot is still 80 (+50).
        assertEquals(10f, reorderDragTranslation(startTop = 80f, dragOffsetY = 50f, liveTop = 120f))
        assertEquals(50f, reorderDragTranslation(startTop = 80f, dragOffsetY = 50f, liveTop = 80f))
        assertEquals(-10f, reorderDragTranslation(startTop = 80f, dragOffsetY = 50f, liveTop = 140f))
    }

    @Test
    fun onlyAltArrowKeyDownsAreReorderGestures() {
        assertEquals(true, altArrowDirection(Key.DirectionUp, KeyEventType.KeyDown, altPressed = true))
        assertEquals(false, altArrowDirection(Key.DirectionDown, KeyEventType.KeyDown, altPressed = true))
        assertNull(altArrowDirection(Key.DirectionDown, KeyEventType.KeyDown, altPressed = false))
        assertNull(altArrowDirection(Key.DirectionDown, KeyEventType.KeyUp, altPressed = true))
        assertNull(altArrowDirection(Key.Enter, KeyEventType.KeyDown, altPressed = true))
    }
}
