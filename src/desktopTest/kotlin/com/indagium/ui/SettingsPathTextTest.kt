package com.indagium.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [middleEllipsis], the one rule every Settings path line is cut by. A glyph is one unit wide here, so a limit is a character count. */
class SettingsPathTextTest {
    private val path = "/Users/someone/AndroidStudioProjects/MyApplication/app/src/main/java"

    private fun cut(text: String, max: Int) = middleEllipsis(text, max) { it.length }

    @Test
    fun aPathThatFitsIsShownAsItIs() {
        assertEquals(path, cut(path, path.length))
        assertEquals("", cut("", 0))
    }

    @Test
    fun aLongerPathKeepsItsStartAndItsEndAroundAnEllipsis() {
        val shown = cut(path, 30)
        assertEquals(30, shown.length, "it uses the whole width")
        assertTrue(shown.startsWith("/Users/some"), shown)
        assertTrue(shown.endsWith("src/main/java"), shown)
        assertEquals(1, shown.count { it == '…' })
    }

    @Test
    fun theEndGetsTheLargerShare() {
        val shown = cut(path, 20)
        val (head, tail) = shown.split('…')
        assertTrue(tail.length > head.length, shown)
    }

    @Test
    fun aWidthTooSmallForAnythingLeavesJustTheEllipsis() {
        assertEquals("…", cut(path, 1))
    }
}
