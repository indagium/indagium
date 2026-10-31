package com.indagium.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.test.assertTrue

private const val CHANNEL_MASK = 0xff
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val DEFAULT_CHANNEL_TOLERANCE = 2

/** [base] with the group colour [group] laid over it at [alpha]: what a tinted tab shows where it is otherwise filled with [base]. */
internal fun tintedOver(base: Color, group: Color, alpha: Float): Int =
    group.copy(alpha = alpha).compositeOver(base.copy(alpha = 1f)).toArgb()

/** Asserts two packed ARGB colours are within [tolerance] on every colour channel (rounding of the blend, anti-aliasing). */
internal fun assertArgbNear(expected: Int, actual: Int, message: String, tolerance: Int = DEFAULT_CHANNEL_TOLERANCE) {
    fun channel(value: Int, shift: Int) = (value shr shift) and CHANNEL_MASK
    val within = listOf(RED_SHIFT, GREEN_SHIFT, 0).all { abs(channel(expected, it) - channel(actual, it)) <= tolerance }
    assertTrue(within, "$message: expected #${expected.toUInt().toString(16)} but was #${actual.toUInt().toString(16)}")
}
