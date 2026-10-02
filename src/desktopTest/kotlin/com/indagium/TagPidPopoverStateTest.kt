package com.indagium

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.indagium.ui.RightOfEdgePositionProvider
import com.indagium.ui.TagPidPopoverState
import com.indagium.ui.tagPopoverX
import com.indagium.utils.TagProcessInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagPidPopoverStateTest {
    private fun info(pid: Int) = TagProcessInfo(pid, null, 1, 1, 1, "10:00:00.000", "10:00:01.000")

    private class Harness {
        val state = TagPidPopoverState()
        var refocused = 0
        val committed = mutableListOf<String>()
        var tabbed = 0

        init {
            state.refocus = { refocused++ }
            state.commitRule = { committed += it }
        }

        fun key(key: Key) = state.handleKey(key) { tabbed++ }
    }

    @Test
    fun openRefocusesStartsScanningAndCloseReleases() {
        val h = Harness()

        h.state.open("Tag")

        assertEquals("Tag", h.state.tag)
        assertNull(h.state.infos)
        assertEquals(1, h.refocused)
        h.state.close()
        assertFalse(h.state.isOpen)
        assertEquals(2, h.refocused)
    }

    @Test
    fun firstScanSeedsSelectionAndLaterScansKeepTheUsersChoice() {
        val h = Harness()
        h.state.open("Tag")

        h.state.onScanResult(listOf(info(1), info(2)))
        assertEquals(setOf(1, 2), h.state.selected)
        assertEquals(0, h.state.cursor)

        h.state.togglePid(2)
        h.state.onScanResult(listOf(info(1), info(2), info(3)))
        assertEquals(setOf(1), h.state.selected)
    }

    @Test
    fun keysAreIgnoredWhileClosedAndConsumedWhileOpen() {
        val h = Harness()
        assertFalse(h.key(Key.DirectionDown))

        h.state.open("Tag")
        h.state.onScanResult(listOf(info(1)))
        assertTrue(h.key(Key.DirectionDown))
        assertEquals(1, h.state.cursor)
        assertTrue(h.key(Key.DirectionLeft))
        assertFalse(h.key(Key.A), "unrelated keys fall through")
    }

    @Test
    fun spaceTogglesAPidThenTheKeepFollowingRow() {
        val h = Harness()
        h.state.open("Tag")
        h.state.onScanResult(listOf(info(1), info(2)))

        h.key(Key.Spacebar) // cursor on pid 1
        assertEquals(setOf(2), h.state.selected)
        h.key(Key.Spacebar)
        assertEquals(setOf(1, 2), h.state.selected)

        h.key(Key.DirectionDown)
        h.key(Key.DirectionDown) // keep-following row
        h.key(Key.Spacebar)
        assertFalse(h.state.keepFollowing)
    }

    @Test
    fun enterAddsAPidRuleAndClosesOnlyWhenSomethingIsChecked() {
        val h = Harness()
        h.state.open("Tag")
        assertTrue(h.key(Key.Enter), "still scanning: consumed but nothing added")
        assertTrue(h.committed.isEmpty())
        assertTrue(h.state.isOpen)

        h.state.onScanResult(listOf(info(7)))
        h.key(Key.Enter)

        assertEquals(1, h.committed.size)
        assertTrue(h.committed.single().contains("Tag"), "keep-following token rule names the tag")
        assertFalse(h.state.isOpen)
    }

    @Test
    fun escapeAndTabCloseAndTabHandsOffFocus() {
        val h = Harness()
        h.state.open("A")
        h.key(Key.Escape)
        assertFalse(h.state.isOpen)

        h.state.open("A")
        h.key(Key.Tab)
        assertFalse(h.state.isOpen)
        assertEquals(1, h.tabbed)
    }

    @Test
    fun toggleForOpensClosesAndIgnoresTheClickRightAfterAnOutsideDismiss() {
        val h = Harness()

        h.state.toggleFor("A", nowMs = 1_000)
        assertEquals("A", h.state.tag)
        h.state.toggleFor("A", nowMs = 1_001)
        assertFalse(h.state.isOpen)

        h.state.open("A")
        h.state.dismissByOutsideClick(nowMs = 5_000)
        h.state.toggleFor("A", nowMs = 5_100)
        assertFalse(h.state.isOpen, "the same click that dismissed must not reopen")
        h.state.toggleFor("A", nowMs = 5_300)
        assertEquals("A", h.state.tag)
    }

    @Test
    fun closeIfMissingKeepsTheAnchorRowAndClosesWhenItVanished() {
        val h = Harness()
        h.state.open("A")

        h.state.closeIfMissing(listOf("pkg" to true, "A" to false))
        assertTrue(h.state.isOpen)
        h.state.closeIfMissing(listOf("A" to true, "B" to false))
        assertFalse(h.state.isOpen, "a package row of the same name is not the tag row")
    }

    @Test
    fun popoverOpensRightOfTheEdgeAndFlipsLeftWhenItDoesNotFit() {
        assertEquals(306, tagPopoverX(rightEdgePx = 300, leftEdgePx = null, popupWidthPx = 400, windowWidthPx = 1000))
        assertEquals(306, tagPopoverX(300, leftEdgePx = 100, popupWidthPx = 400, windowWidthPx = 1000))
        // No room on the right: left of the dropdown when it fits there...
        assertEquals(494, tagPopoverX(rightEdgePx = 900, leftEdgePx = 900, popupWidthPx = 400, windowWidthPx = 1000))
        // ...otherwise clamped into the window (also the panel's no-left-edge behaviour).
        assertEquals(600, tagPopoverX(rightEdgePx = 900, leftEdgePx = null, popupWidthPx = 400, windowWidthPx = 1000))
        assertEquals(600, tagPopoverX(rightEdgePx = 900, leftEdgePx = 200, popupWidthPx = 400, windowWidthPx = 1000))
        assertEquals(0, tagPopoverX(rightEdgePx = 10, leftEdgePx = null, popupWidthPx = 400, windowWidthPx = 300))
    }

    @Test
    fun positionProviderTopAlignsWithTheAnchorRowAndClampsVertically() {
        val provider = RightOfEdgePositionProvider(rightEdgePx = 300)
        val window = IntSize(1000, 800)
        val popup = IntSize(400, 300)

        val a = provider.calculatePosition(IntRect(280, 120, 300, 140), window, LayoutDirection.Ltr, popup)
        assertEquals(306 to 120, a.x to a.y)
        val b = provider.calculatePosition(IntRect(280, 700, 300, 720), window, LayoutDirection.Ltr, popup)
        assertEquals(500, b.y)
    }
}
