package com.indagium.ui.holiday

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
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

private const val OVERLAY_SEED = 31

/** The effects over the window; nothing at all while there is nothing to animate. */
@Composable
internal fun HolidayEffectsOverlay(modifier: Modifier = Modifier) {
    val holiday = LocalHoliday.current ?: return
    val fx = holiday.effects
    if (fx.activeEffects.none { it.moves } || fx.speedScale <= 0f) return
    if (!holidayPicturesRunning(animate = true)) return
    val clock = rememberHolidayClock(running = true)
    val fields = remember(fx) {
        fx.activeEffects.filterIsInstance<HolidayParticleEffect>().mapIndexed { i, e -> e.spawn(OVERLAY_SEED + i, fx.countScale, fx.speedScale) }
    }
    val flashes = remember(fx) { fx.activeEffects.filterIsInstance<HolidayFlashEffect>() }
    val exclusions = LocalHolidayExclusions.current
    val dark = holiday.isDark
    Canvas(modifier.graphicsLayer().testTag(HOLIDAY_EFFECTS_OVERLAY_TAG)) {
        val t = clock.value
        val clear = exclusions?.let { holder ->
            holder.version
            holder.path()
        }
        if (clear == null) {
            drawEffects(t, fields, flashes, fx.flashMinGapSec, dark, density)
        } else {
            clipPath(clear, ClipOp.Difference) { drawEffects(t, fields, flashes, fx.flashMinGapSec, dark, density) }
        }
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
) {
    withTransform({ scale(density, density, Offset.Zero) }) {
        val w = size.width / density
        val h = size.height / density
        // All flash effects share one schedule (and so the same minimum gap); the slot says which of them takes the flash.
        if (flashes.isNotEmpty()) {
            val frame = holidayFlashFrame(OVERLAY_SEED, t, flashGapSec)
            if (frame.alpha > 0f) flashes[frame.slot.mod(flashes.size)].draw(this, frame.alpha.coerceAtMost(HOLIDAY_FLASH_MAX_ALPHA), w, h, dark)
        }
        fields.forEach { it.draw(this, t, w, h, dark) }
    }
}
