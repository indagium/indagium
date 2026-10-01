package com.indagium

import com.indagium.ui.TAG_ACTION_PID
import com.indagium.ui.canAddTagProcess
import com.indagium.ui.canKeepFollowing
import com.indagium.ui.clampPopoverCursor
import com.indagium.ui.clampTagRowAction
import com.indagium.ui.movePopoverCursor
import com.indagium.ui.nextTagRowAction
import com.indagium.ui.popoverItemCount
import com.indagium.utils.TagProcessInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagProcessKeyboardTest {
    private fun info(pid: Int) = TagProcessInfo(pid, null, 1, 1, 1, "10:00:00.000", "10:00:01.000")

    @Test
    fun leftFromIncludeReachesPidOnlyWhenARowHasOne() {
        assertEquals(TAG_ACTION_PID, nextTagRowAction(0, -1, pidAvailable = true))
        assertEquals(0, nextTagRowAction(0, -1, pidAvailable = false), "no pid button: stays on include")
    }

    @Test
    fun rightFromPidReturnsToIncludeAndStillReachesExclude() {
        assertEquals(0, nextTagRowAction(TAG_ACTION_PID, +1, pidAvailable = true))
        assertEquals(1, nextTagRowAction(0, +1, pidAvailable = true))
        assertEquals(1, nextTagRowAction(1, +1, pidAvailable = true), "clamped at exclude")
        assertEquals(TAG_ACTION_PID, nextTagRowAction(TAG_ACTION_PID, -1, pidAvailable = true), "clamped at pid")
    }

    @Test
    fun existingIncludeExcludeSwitchingIsUnchangedWithoutAPid() {
        assertEquals(1, nextTagRowAction(0, +1, pidAvailable = false))
        assertEquals(0, nextTagRowAction(1, -1, pidAvailable = false))
    }

    @Test
    fun moveToARowWithoutPidsClampsAPidActionToInclude() {
        assertEquals(0, clampTagRowAction(TAG_ACTION_PID, pidAvailable = false))
        assertEquals(TAG_ACTION_PID, clampTagRowAction(TAG_ACTION_PID, pidAvailable = true))
        assertEquals(1, clampTagRowAction(1, pidAvailable = false))
    }

    @Test
    fun popoverCursorClampsAtBothEndsWithoutWrapping() {
        assertEquals(1, movePopoverCursor(0, +1, itemCount = 3))
        assertEquals(2, movePopoverCursor(2, +1, itemCount = 3))
        assertEquals(0, movePopoverCursor(0, -1, itemCount = 3))
        assertEquals(1, movePopoverCursor(2, -1, itemCount = 3))
    }

    @Test
    fun popoverCursorWithNoCursorLandsOnTheFirstItem() {
        assertEquals(0, movePopoverCursor(-1, +1, itemCount = 3))
        assertEquals(0, movePopoverCursor(-1, -1, itemCount = 3))
        assertEquals(-1, movePopoverCursor(-1, +1, itemCount = 0))
    }

    @Test
    fun popoverCursorIsClampedWhenARescanShrinksTheList() {
        assertEquals(1, clampPopoverCursor(4, itemCount = 2))
        assertEquals(0, clampPopoverCursor(0, itemCount = 2))
        assertEquals(-1, clampPopoverCursor(0, itemCount = 0))
    }

    @Test
    fun popoverHasOneItemPerPidPlusTheKeepFollowingRow() {
        assertEquals(1, popoverItemCount(null))
        assertEquals(3, popoverItemCount(listOf(info(1), info(2))))
    }

    @Test
    fun keepFollowingNeedsEveryPidCheckedAndATokenableTag() {
        val infos = listOf(info(1), info(2))

        assertTrue(canKeepFollowing("Tag", infos, setOf(1, 2)))
        assertFalse(canKeepFollowing("Tag", infos, setOf(1)))
        assertFalse(canKeepFollowing("My Tag", infos, setOf(1, 2)))
        assertFalse(canKeepFollowing("Tag", null, setOf(1)))
        assertFalse(canKeepFollowing("Tag", emptyList(), emptySet()))
    }

    @Test
    fun addNeedsAFinishedScanAndACheckedPid() {
        assertTrue(canAddTagProcess(listOf(info(1)), setOf(1)))
        assertFalse(canAddTagProcess(listOf(info(1)), emptySet()))
        assertFalse(canAddTagProcess(null, setOf(1)))
    }
}
