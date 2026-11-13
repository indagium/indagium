package com.indagium

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.indagium.ui.ErrorRowsStylePopupPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorRowsStylePopupPositionTest {
    private val shadowPad = 7
    private val provider = ErrorRowsStylePopupPosition(shadowPad)
    private val window = IntSize(1000, 700)

    private fun place(anchor: IntRect, popup: IntSize) =
        provider.calculatePosition(anchor, window, LayoutDirection.Ltr, popup)

    @Test
    fun cardStartsAtTheButtonsStartEdgeBelowIt() {
        val anchor = IntRect(200, 100, 280, 124)
        val popup = IntSize(534, 90)
        val offset = place(anchor, popup)
        // The popup's content carries the shadow padding, so the visible card sits shadowPad inside it.
        assertEquals(anchor.left, offset.x + shadowPad)
        assertTrue(offset.y + shadowPad > anchor.bottom, "card must open below the button")
    }

    @Test
    fun cardIsShiftedLeftToStayInsideTheWindow() {
        val anchor = IntRect(800, 100, 880, 124)
        val popup = IntSize(534, 90)
        val offset = place(anchor, popup)
        assertTrue(offset.x >= 0 && offset.x + popup.width <= window.width, "popup at $offset must stay inside $window")
    }

    @Test
    fun cardFlipsAboveTheButtonWhenThereIsNoRoomBelow() {
        val anchor = IntRect(200, 640, 280, 664)
        val popup = IntSize(534, 90)
        val offset = place(anchor, popup)
        assertTrue(offset.y + popup.height - shadowPad <= anchor.top, "card must end above the button")
        assertTrue(offset.y >= 0)
    }
}
