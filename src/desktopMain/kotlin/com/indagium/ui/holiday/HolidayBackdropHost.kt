package com.indagium.ui.holiday

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.indagium.ui.*
import kotlin.math.roundToInt

// The theme's backdrop arts (the picture, the companion) at the app window's bottom corners, in two passes that both take no input, focus or
// semantics: BEHIND everything (full strength, wherever the window background shows: free space, gaps, margins) and OVER everything (the same
// arts as a faint wash through any tile). The content rectangle comes from ChromeScaffold through [HolidayArtFrame]. One clock drives both passes
// and is read only while drawing.

internal const val HOLIDAY_FILTER_PICTURE_TAG = "holiday-filter-picture"
internal const val HOLIDAY_NOTES_COMPANION_TAG = "holiday-notes-companion"
internal const val HOLIDAY_ART_WASH_TAG = "holiday-art-wash"

/** Alpha of the over-pass wash on a dark and on a light theme; low so text stays readable. */
internal const val HOLIDAY_PICTURE_UNDER_CARD_ALPHA_DARK = 0.14f
internal const val HOLIDAY_PICTURE_UNDER_CARD_ALPHA_LIGHT = 0.10f

/** One art to place: its [anchor], design size, the [tag] its behind-pass canvas carries and how it [draw]s at time t in its own design coordinates. */
internal class HolidayBackdropArt(
    val anchor: HolidayArtAnchor,
    val designWidthDp: Float,
    val designHeightDp: Float,
    val tag: String,
    val draw: (DrawScope, Float, Boolean) -> Unit,
)

/**
 * A full-window page's own background (the log view, the workspaces): the window colour, left out while a seasonal pack's corner arts are
 * drawn behind the page. The window root already paints that colour, so the only difference is that the arts show at full strength in the
 * page's free space instead of only as the faint wash.
 */
@Composable
internal fun Modifier.pageBackground(): Modifier {
    val look = LocalHoliday.current
    val hasBehindEffect = look?.effects?.activeEffects?.any { it is HolidayParticleEffect && it.behindContent } == true
    return if (look != null && (holidayBackdropArts(look).isNotEmpty() || hasBehindEffect)) this else background(tc().bg)
}

/** The arts [look]'s theme provides and the user has on: the picture (bottom-left), then the companion (bottom-right). */
internal fun holidayBackdropArts(look: HolidayLook): List<HolidayBackdropArt> = buildList {
    val picture = look.theme.picture
    if (look.effects.filterPicture && picture != null) {
        add(
            HolidayBackdropArt(
                HolidayArtAnchor.BOTTOM_START, picture.designWidthDp, picture.designHeightDp, HOLIDAY_FILTER_PICTURE_TAG, picture::draw,
            ),
        )
    }
    val companion = look.theme.companion
    if (look.effects.notesCompanion && companion != null) {
        add(
            HolidayBackdropArt(
                HolidayArtAnchor.BOTTOM_END, companion.designWidthDp, companion.designHeightDp, HOLIDAY_NOTES_COMPANION_TAG, companion::draw,
            ),
        )
    }
}

/** The window's content area in root pixels, reported by [ChromeScaffold]; null until it is laid out (or where there is no scaffold). */
internal class HolidayArtFrame {
    var rootRect by mutableStateOf<Rect?>(null)
}

/** Provided by App.kt; null where there is no app (tests of a single view): the arts then have no place. */
internal val LocalHolidayArtFrame = staticCompositionLocalOf<HolidayArtFrame?> { null }

/** The shared clock of both art passes; null means a still frame. Provided by App.kt. */
internal val LocalHolidayArtClock = staticCompositionLocalOf<State<Float>?> { null }

/** An art in root pixels: [left] and [top] from the window's origin, [widthDp] x [heightDp]. */
private class PlacedArt(val art: HolidayBackdropArt, val left: Int, val top: Int, val widthDp: Float, val heightDp: Float)

private fun placedArts(look: HolidayLook, frame: HolidayArtFrame?, density: Float): List<PlacedArt> {
    val rect = frame?.rootRect ?: return emptyList()
    return holidayArtPlacements(holidayBackdropArts(look), rect.width / density, rect.height / density).map { (art, r) ->
        PlacedArt(art, (rect.left + r.x * density).roundToInt(), (rect.top + r.y * density).roundToInt(), r.w, r.h)
    }
}

/** The clock for [look]: running only while some art has a place and the arts should move (animation on, not paused, window focused). */
@Composable
internal fun rememberHolidayArtClock(look: HolidayLook?, frame: HolidayArtFrame, paused: Boolean): State<Float> {
    val density = LocalDensity.current.density
    val focused = LocalWindowInfo.current.isWindowFocused
    val running = look != null && look.effects.animatePicture && !paused && focused && placedArts(look, frame, density).isNotEmpty()
    return rememberHolidayClock(running)
}

private fun DrawScope.drawArt(placed: PlacedArt, time: Float, dark: Boolean) {
    withTransform({ scale(size.width / placed.art.designWidthDp, size.width / placed.art.designWidthDp, Offset.Zero) }) { placed.art.draw(this, time, dark) }
}

/** The arts at full strength behind everything, above the window background; the root Box's first child. */
@Composable
internal fun HolidayArtsBehind(modifier: Modifier = Modifier) {
    val look = LocalHoliday.current ?: return
    val density = LocalDensity.current.density
    val placed = placedArts(look, LocalHolidayArtFrame.current, density)
    if (placed.isEmpty()) return
    val clock = LocalHolidayArtClock.current
    val speed = look.effects.speedScale.takeIf { it > 0f } ?: 1f
    val dark = look.isDark
    Box(modifier.fillMaxSize()) {
        placed.forEach { p ->
            Canvas(Modifier.offset { IntOffset(p.left, p.top) }.size(p.widthDp.dp, p.heightDp.dp).graphicsLayer().testTag(p.art.tag)) {
                drawArt(p, (clock?.value ?: HOLIDAY_STILL_TIME) * speed, dark)
            }
        }
    }
}

/**
 * The same arts as a faint wash over everything (a light theme multiplies it in, so it only darkens and dark text keeps its contrast; a dark theme
 * lays it over as a plain faint wash), clipped to the arts' own rectangles. Under Calm it is a still frame and keeps off the main-content tiles
 * (the effects overlay's exclusions); under Full it covers them too.
 */
@Composable
internal fun HolidayArtsWash(modifier: Modifier = Modifier) {
    val look = LocalHoliday.current ?: return
    val density = LocalDensity.current.density
    val placed = placedArts(look, LocalHolidayArtFrame.current, density)
    if (placed.isEmpty()) return
    val clock = LocalHolidayArtClock.current
    val exclusions = LocalHolidayExclusions.current
    val speed = look.effects.speedScale.takeIf { it > 0f } ?: 1f
    val moving = look.effects.animatePicture && look.effects.overLog
    val dark = look.isDark
    val alpha = if (dark) HOLIDAY_PICTURE_UNDER_CARD_ALPHA_DARK else HOLIDAY_PICTURE_UNDER_CARD_ALPHA_LIGHT
    Canvas(modifier.fillMaxSize().graphicsLayer().testTag(HOLIDAY_ART_WASH_TAG)) {
        val time = if (moving) (clock?.value ?: HOLIDAY_STILL_TIME) * speed else HOLIDAY_STILL_TIME
        val clear = exclusions?.let { holder ->
            holder.version
            holder.path()
        }
        val draw: DrawScope.() -> Unit = { placed.forEach { drawWashArt(it, time, alpha, dark) } }
        if (clear == null) draw() else clipPath(clear, ClipOp.Difference) { draw() }
    }
}

private fun DrawScope.drawWashArt(p: PlacedArt, time: Float, alpha: Float, dark: Boolean) {
    val bounds = Rect(p.left.toFloat(), p.top.toFloat(), p.left + p.widthDp * density, p.top + p.heightDp * density)
    drawIntoCanvas { canvas ->
        val layer = Paint().apply {
            this.alpha = alpha
            blendMode = if (dark) BlendMode.SrcOver else BlendMode.Multiply
        }
        canvas.saveLayer(bounds, layer)
        withTransform({ translate(bounds.left, bounds.top) }) {
            val s = p.widthDp * density / p.art.designWidthDp
            withTransform({ scale(s, s, Offset.Zero) }) { p.art.draw(this, time, dark) }
        }
        canvas.restore()
    }
}
