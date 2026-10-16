package com.indagium.ui

import java.awt.Cursor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppCursorsTest {
    @AfterTest
    fun clearOverride() {
        dragCursorOverride.value = null
    }

    @Test
    fun nonMacBuildsTheStockMoveCursor() {
        assertEquals(Cursor.MOVE_CURSOR, buildMoveCursor(useCustomGlyph = false).type)
    }

    @Test
    fun customGlyphRequestNeverThrowsAndAlwaysYieldsACursor() {
        // Headless JVM: Toolkit.createCustomCursor throws HeadlessException, which must fall back to the stock cursor.
        // Headful JVM: a real custom cursor is built. Either way the caller gets a usable cursor.
        assertNotNull(buildMoveCursor(useCustomGlyph = true))
        assertNotNull(AppCursors.move)
        assertNotNull(AppCursors.movePointerIcon)
    }

    @Test
    fun glyphIsOpaqueBlackAtTheCentreAndTransparentInTheCorners() {
        for (scale in 1..2) {
            val img = moveGlyphImage(MOVE_CURSOR_LOGICAL_SIZE, scale)
            val px = MOVE_CURSOR_LOGICAL_SIZE * scale
            assertEquals(px, img.width)
            assertEquals(px, img.height)
            assertEquals(0xFF000000.toInt(), img.getRGB(px / 2, px / 2), "centre of the cross is solid black")
            assertEquals(0, img.getRGB(0, 0) ushr 24, "corner stays transparent")
            assertEquals(0, img.getRGB(px - 1, px - 1) ushr 24, "corner stays transparent")
        }
    }

    @Test
    fun glyphHasAWhiteOutlineAroundTheBlackFill() {
        val img = moveGlyphImage(MOVE_CURSOR_LOGICAL_SIZE, 2)
        val px = img.width
        // Walk in from the left edge along the horizontal arm: first non-transparent pixel is white outline, then black.
        val y = px / 2
        val first = (0 until px).first { img.getRGB(it, y) ushr 24 != 0 }
        val rgb = img.getRGB(first, y)
        assertTrue((rgb ushr 16 and 0xFF) > 200 && (rgb ushr 8 and 0xFF) > 200 && (rgb and 0xFF) > 200, "outline is white")
    }

    @Test
    fun releaseOnlyClearsTheMoveCursorOverride() {
        val resize = Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
        dragCursorOverride.value = resize
        releaseMoveDragCursor()
        assertSame(resize, dragCursorOverride.value, "a divider's resize override must survive")

        holdMoveDragCursor()
        assertSame(AppCursors.move, dragCursorOverride.value)
        releaseMoveDragCursor()
        assertNull(dragCursorOverride.value)
    }
}
