package com.indagium.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage

/**
 * Cursors the app shares.
 *
 * [move] exists because the JDK's `Cursor.MOVE_CURSOR` is not usable on current macOS: the macOS AWT backend
 * (`CCursorManager.nativeSetBuiltInCursor`) maps it to a private `+[NSCursor javaMoveCursor]`, which returns an NSCursor
 * whose image is 0x0 on macOS 26 (the system artwork it relied on is gone), so the pointer simply stays an arrow. Every other
 * predefined cursor used here (E/S resize, HAND, TEXT) has a real AppKit cursor behind it and works. On macOS we therefore draw our
 * own four-arrow glyph; everywhere else the stock cursor is fine.
 */
internal object AppCursors {
    /** The "this can be dragged to move" cursor. Never throws: any failure to build the custom one falls back to the stock cursor. */
    val move: Cursor by lazy { buildMoveCursor(useCustomGlyph = isMacOs) }

    val movePointerIcon: PointerIcon by lazy { PointerIcon(move) }
}

/** Logical (point) size of the macOS move cursor; macOS system cursors are roughly this big. */
internal const val MOVE_CURSOR_LOGICAL_SIZE = 24

/** Builds the move cursor. [useCustomGlyph] false (or any failure, e.g. a headless JVM) returns the stock `MOVE_CURSOR`. */
internal fun buildMoveCursor(useCustomGlyph: Boolean): Cursor {
    val fallback = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
    if (!useCustomGlyph || GraphicsEnvironment.isHeadless()) return fallback
    return runCatching {
        val toolkit = Toolkit.getDefaultToolkit()
        // Ask for our logical size; macOS gives it back unchanged (it only clamps to half the screen), other
        // platforms would round to a supported size, which only matters if this path is ever enabled there.
        val best = toolkit.getBestCursorSize(MOVE_CURSOR_LOGICAL_SIZE, MOVE_CURSOR_LOGICAL_SIZE)
        val size = if (best.width > 0 && best.height > 0) minOf(best.width, best.height) else MOVE_CURSOR_LOGICAL_SIZE
        // Base (1x) + Retina (2x) variants: the JDK registers every variant on the NSImage, AppKit picks per display.
        val image = BaseMultiResolutionImage(0, moveGlyphImage(size, 1), moveGlyphImage(size, 2))
        val hotspot = Point(size / 2, size / 2)
        toolkit.createCustomCursor(image, hotspot, "indagium-move")
    }.getOrDefault(fallback)
}

/** A 4-arrow "move" glyph, black with a white outline (like macOS system cursors), [logicalSize] points drawn at [scale]x. */
internal fun moveGlyphImage(logicalSize: Int, scale: Int): BufferedImage {
    val px = logicalSize * scale
    val img = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        // The outline is drawn around a path authored in a 24x24 box centred on (12, 12), shrunk a little so the 1.5px
        // outside half of the white stroke still fits inside the image.
        val unit = px / MOVE_CURSOR_LOGICAL_SIZE.toDouble()
        val shape = moveGlyphPath().createTransformedShape(
            AffineTransform().apply {
                translate(px / HALF, px / HALF)
                scale(unit * GLYPH_SCALE, unit * GLYPH_SCALE)
                translate(-GLYPH_CENTER, -GLYPH_CENTER)
            },
        )
        g.color = Color.WHITE
        g.stroke = BasicStroke((OUTLINE_PT * 2 * scale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(shape)
        g.color = Color.BLACK
        g.fill(shape)
    } finally {
        g.dispose()
    }
    return img
}

private const val HALF = 2.0
private const val GLYPH_CENTER = 12.0
private const val GLYPH_SCALE = 0.9
private const val OUTLINE_PT = 1.25

/** One closed outline of the four arrows, authored in a 24x24 box centred on (12, 12). */
@Suppress("MagicNumber") // Glyph coordinates are artwork data, not tunable behaviour.
private fun moveGlyphPath(): Path2D.Double = Path2D.Double().apply {
    moveTo(12.0, 1.5)   // top tip
    lineTo(15.5, 6.0)
    lineTo(13.2, 6.0)
    lineTo(13.2, 10.8)
    lineTo(18.0, 10.8)
    lineTo(18.0, 8.5)
    lineTo(22.5, 12.0)  // right tip
    lineTo(18.0, 15.5)
    lineTo(18.0, 13.2)
    lineTo(13.2, 13.2)
    lineTo(13.2, 18.0)
    lineTo(15.5, 18.0)
    lineTo(12.0, 22.5)  // bottom tip
    lineTo(8.5, 18.0)
    lineTo(10.8, 18.0)
    lineTo(10.8, 13.2)
    lineTo(6.0, 13.2)
    lineTo(6.0, 15.5)
    lineTo(1.5, 12.0)   // left tip
    lineTo(6.0, 8.5)
    lineTo(6.0, 10.8)
    lineTo(10.8, 10.8)
    lineTo(10.8, 6.0)
    lineTo(8.5, 6.0)
    closePath()
}

/** Hover cursor for anything the user can drag to move/reorder. */
internal fun Modifier.moveCursor(): Modifier = pointerHoverIcon(AppCursors.movePointerIcon)

/** Hover cursor for a stock AWT resize cursor ([Cursor.SE_RESIZE_CURSOR] etc.). */
internal fun Modifier.resizeCursor(awtType: Int): Modifier = pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(awtType)))

/**
 * `detectDragGestures` that holds the move cursor for the whole window while a drag is in progress (via
 * [dragCursorOverride], the same overlay the dividers use), so the cursor does not flicker back to an arrow when the dragged
 * row moves out from under the pointer. The override is set only once the drag really starts (past touch slop), and is always
 * cleared again on end, cancel, and when the gesture coroutine itself is cancelled (row recomposed away mid-drag).
 */
internal suspend fun PointerInputScope.detectMoveDragGestures(
    onDragStart: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    try {
        detectDragGestures(
            onDragStart = {
                dragCursorOverride.value = AppCursors.move
                onDragStart(it)
            },
            onDragEnd = {
                releaseMoveDragCursor()
                onDragEnd()
            },
            onDragCancel = {
                releaseMoveDragCursor()
                onDragCancel()
            },
            onDrag = onDrag,
        )
    } finally {
        releaseMoveDragCursor()
    }
}

/** Clears the window-wide drag cursor, but only if it is still the move cursor (never a divider's resize cursor). */
internal fun releaseMoveDragCursor() {
    if (dragCursorOverride.value === AppCursors.move) dragCursorOverride.value = null
}

/** Marks the start of a whole-row reorder drag (no grip, so no hover cursor): holds the move cursor until [releaseMoveDragCursor]. */
internal fun holdMoveDragCursor() {
    dragCursorOverride.value = AppCursors.move
}

/**
 * `awaitPointerEventScope` for the hand-rolled press/move/release reorder loops (tab bar, compare picker, sequence and
 * saved-filter rows): same as the stock call, but guarantees the move cursor held by [holdMoveDragCursor] is released when the loop
 * ends for any reason (key change, row left composition mid-drag), so the window can never be stuck on the move cursor.
 */
internal suspend fun <R> PointerInputScope.awaitReorderPointerScope(block: suspend AwaitPointerEventScope.() -> R): R =
    try {
        awaitPointerEventScope(block)
    } finally {
        releaseMoveDragCursor()
    }
