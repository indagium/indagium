// The flash slot arithmetic and the photosensitivity limits are fixed constants of the framework, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.indagium.model.HolidaySettings
import com.indagium.ui.*
import kotlin.math.floor

// The window-wide effects a theme may declare. Continuous ones (embers, bats, snow, confetti) are particle fields the theme owns; a FLASH
// (lightning, fireworks) is only ever scheduled and dimmed by the framework below, so no theme can exceed the photosensitivity limits:
// at most HOLIDAY_FLASH_MAX_ALPHA, at most HOLIDAY_FLASH_SEC per flash, and flashes (of all effects together) more than the minimum gap apart.

/** A switch the theme offers: a stable globally unique [id] (stored in the settings), its menu [label], and a one-word [noun] for hints. */
internal interface HolidayEffectSpec {
    val id: String
    val label: String
    val noun: String
    val defaultOn: Boolean

    /** Whether it needs the frame clock (a static effect would not). */
    val moves: Boolean get() = true
}

/** A particle effect: [spawn] gives the same field for the same arguments (seeded), so a frame is a function of the clock only. */
internal interface HolidayParticleEffect : HolidayEffectSpec {
    fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField
}

/** The particles of one effect. Everything is in dp of a [widthDp] x [heightDp] window; the framework has scaled the scope. */
internal interface HolidayParticleField {
    val count: Int

    fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean)
}

/** A window flash: the framework decides WHEN and how strong ([alpha], already within the limits); the theme only says how it looks. */
internal interface HolidayFlashEffect : HolidayEffectSpec {
    fun draw(scope: DrawScope, alpha: Float, widthDp: Float, heightDp: Float, dark: Boolean)
}

/** The most a flash lightens the window, kept low for photosensitivity. */
internal const val HOLIDAY_FLASH_MAX_ALPHA = 0.12f

/** A flash lasts at most this long, in seconds. */
internal const val HOLIDAY_FLASH_SEC = 0.15f

/** The least time between two flashes (end of one to start of the next), Full and Calm intensity. */
internal const val HOLIDAY_FLASH_GAP_FULL_SEC = 20f
internal const val HOLIDAY_FLASH_GAP_CALM_SEC = 45f

/** The flash showing now: its [alpha] (0 when none) and the [slot] it belongs to (which of several flash effects gets it: slot % count). */
internal data class HolidayFlashFrame(val alpha: Float, val slot: Int)

// A cheap integer hash to [0, 1): no allocation, so the overlay can ask for a flash every frame.
private fun hash01(seed: Int, k: Int): Float {
    var h = seed * -1640531535 + k * 668265263
    h = (h xor (h ushr 15)) * -2048144789
    h = (h xor (h ushr 13)) * -1028477387
    h = h xor (h ushr 16)
    return (h ushr 8) / 16777216f
}

/**
 * The window flash at [t] seconds: alpha 0 almost always, and at most [HOLIDAY_FLASH_MAX_ALPHA] for a single [HOLIDAY_FLASH_SEC] flash that
 * fades out. Time is cut into slots of 1.5 x [minGapSec]; each holds one flash at a seeded offset in its first part, so consecutive
 * flashes are always more than [minGapSec] apart, and never more than one flash a second.
 */
internal fun holidayFlashFrame(seed: Int, t: Float, minGapSec: Float): HolidayFlashFrame {
    if (t < 0f || minGapSec <= 0f) return HolidayFlashFrame(0f, 0)
    val slotLength = minGapSec * 1.5f
    val k = floor(t / slotLength).toInt()
    val start = k * slotLength + hash01(seed, k) * (minGapSec * 0.5f - HOLIDAY_FLASH_SEC)
    val d = t - start
    val alpha = if (d < 0f || d >= HOLIDAY_FLASH_SEC) 0f else HOLIDAY_FLASH_MAX_ALPHA * (1f - d / HOLIDAY_FLASH_SEC)
    return HolidayFlashFrame(alpha.coerceIn(0f, HOLIDAY_FLASH_MAX_ALPHA), k)
}

internal fun holidayFlashAlpha(seed: Int, t: Float, minGapSec: Float): Float = holidayFlashFrame(seed, t, minGapSec).alpha

/** Whether the user has [spec] on: their explicit choice, else the spec's own default (so a new effect appears as its author intended). */
internal fun HolidaySettings.isEffectOn(spec: HolidayEffectSpec): Boolean = effectToggles[spec.id] ?: spec.defaultOn
