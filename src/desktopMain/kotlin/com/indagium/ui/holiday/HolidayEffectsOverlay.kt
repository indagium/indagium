package com.indagium.ui.holiday

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.testTag
import com.indagium.ui.*

// The window-wide effects of the active seasonal theme, drawn over the whole window (App.kt places it above the content, below any blocker):
// the theme's particle fields (embers, bats, snow, ...) and its flashes, scheduled here under the photosensitivity limits of
// HolidayEffectKit.kt. It takes no input at all (no pointer, focus or semantics modifiers except a test tag), so every click goes through to
// what is underneath; native mirror surfaces stay above it and it is not one of their occluders. It is not composed at all (so no frame loop
// runs) when no pack is active, nothing is switched on, the app is capturing or playing video, or the window has no focus. The clock is
// read only in the draw lambda.

internal const val HOLIDAY_EFFECTS_OVERLAY_TAG = "holiday-effects-overlay"
internal const val HOLIDAY_EFFECTS_BEHIND_TAG = "holiday-effects-behind"
internal val LocalHolidayEffectClock = staticCompositionLocalOf<State<Float>?> { null }

private const val OVERLAY_SEED = 31

/** The effects over the window; nothing at all while there is nothing to animate. */
@Composable
internal fun HolidayEffectsOverlay(modifier: Modifier = Modifier, behindContent: Boolean = false) {
    val holiday = LocalHoliday.current ?: return
    val fx = holiday.effects
    val particles = fx.activeEffects.filterIsInstance<HolidayParticleEffect>().filter { !behindContent || it.behindContent }
    val flashes = if (behindContent) emptyList() else fx.activeEffects.filterIsInstance<HolidayFlashEffect>()
    if ((particles.none { it.moves } && flashes.none { it.moves }) || fx.speedScale <= 0f) return
    if (!holidayPicturesRunning(animate = true)) return
    val sharedClock = LocalHolidayEffectClock.current
    val fallbackClock = rememberHolidayClock(running = sharedClock == null)
    val clock = sharedClock ?: fallbackClock
    val fields = remember(fx, behindContent) {
        fx.activeEffects.filterIsInstance<HolidayParticleEffect>().mapIndexedNotNull { i, e ->
            if (e.behindContent == behindContent) e.spawn(OVERLAY_SEED + i, fx.countScale, fx.speedScale) else null
        }
    }
    val washFields = remember(fx, behindContent) {
        if (behindContent) emptyList() else fx.activeEffects.filterIsInstance<HolidayParticleEffect>().mapIndexedNotNull { i, e ->
            if (e.behindContent) e.spawn(OVERLAY_SEED + i, fx.countScale, fx.speedScale) else null
        }
    }
    val exclusions = LocalHolidayExclusions.current
    val landingAreas = LocalHolidayLandingAreas.current
    val dark = holiday.isDark
    Canvas(modifier.graphicsLayer().testTag(if (behindContent) HOLIDAY_EFFECTS_BEHIND_TAG else HOLIDAY_EFFECTS_OVERLAY_TAG)) {
        val t = clock.value
        val anchors = landingAreas?.let { holder ->
            holder.version
            holder.bounds().map { Rect(it.left / density, it.top / density, it.right / density, it.bottom / density) }
        } ?: emptyList()
        val clear = exclusions?.let { holder ->
            holder.version
            holder.path()
        }
        val draw: DrawScope.() -> Unit = {
            drawEffects(t, fields, flashes, fx.flashMinGapSec, dark, density, anchors)
            if (washFields.isNotEmpty()) {
                drawIntoCanvas { canvas ->
                    val paint = Paint().apply {
                        alpha = if (dark) HOLIDAY_PICTURE_UNDER_CARD_ALPHA_DARK else HOLIDAY_PICTURE_UNDER_CARD_ALPHA_LIGHT
                        blendMode = if (dark) BlendMode.SrcOver else BlendMode.Multiply
                    }
                    canvas.saveLayer(Rect(Offset.Zero, size), paint)
                    drawEffects(t, washFields, emptyList(), fx.flashMinGapSec, dark, density, anchors)
                    canvas.restore()
                }
            }
        }
        if (clear == null) draw() else clipPath(clear, ClipOp.Difference) { draw() }
    }
}

// All geometry is in dp: the scope is scaled by the density once.
private fun DrawScope.drawEffects(
    t: Float,
    fields: List<HolidayParticleField>,
    flashes: List<HolidayFlashEffect>,
    flashGapSec: Float,
    dark: Boolean,
    density: Float,
    anchors: List<Rect>,
) {
    withTransform({ scale(density, density, Offset.Zero) }) {
        val w = size.width / density
        val h = size.height / density
        // All flash effects share one schedule (and so the same minimum gap); the slot says which of them takes the flash.
        if (flashes.isNotEmpty()) {
            val frame = holidayFlashFrame(OVERLAY_SEED, t, flashGapSec)
            if (frame.alpha > 0f) flashes[frame.slot.mod(flashes.size)].draw(this, frame.alpha.coerceAtMost(HOLIDAY_FLASH_MAX_ALPHA), w, h, dark)
        }
        fields.forEach { if (it is HolidayLandingField) it.drawLanding(this, t, w, h, dark, anchors) else it.draw(this, t, w, h, dark) }
    }
}
