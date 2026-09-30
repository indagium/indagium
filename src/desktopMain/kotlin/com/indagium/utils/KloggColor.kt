@file:Suppress("MagicNumber") // Qt's 16-bit HSV maths and the minstd_rand0 constants are fixed by the algorithms ported here

package com.indagium.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.min

// klogg's "variate colours" option, reproduced. For a match-only highlighter with variate_colors on,
// klogg (Highlighter::vairateColors) picks a factor in [100 - variance, 100 + variance] with a
// std::uniform_int_distribution over a std::minstd_rand0 seeded with the CRC32 of the coloured
// text's UTF-8 bytes, and paints both colours through QColor::darker(factor). Equal matches
// therefore always get the same shade and different ones drift apart.
//
// minstd_rand0 and the QColor HSV maths (Qt's 16-bit-per-channel implementation) are fully
// specified, so they match klogg exactly. The distribution step follows libstdc++ (klogg's Linux
// builds); libc++ (macOS) and MSVC's STL draw the factor differently, so shades can differ from
// klogg on those platforms while staying deterministic.

private const val USHRT_MAX = 65535
private const val HUE_UNDEFINED = USHRT_MAX

private fun qRound(d: Double): Int = if (d >= 0.0) (d + 0.5).toInt() else (d - 0.5).toInt()

private fun fuzzyEq(a: Double, b: Double): Boolean = abs(a - b) * 1e12 <= min(abs(a), abs(b))

private class Hsv16(val hue: Int, val saturation: Int, val value: Int)

// QColor::toHsv from an 8-bit RGB colour (Qt widens each channel to 16 bits as c * 0x101).
private fun toHsv16(r8: Int, g8: Int, b8: Int): Hsv16 {
    val r = (r8 * 257) / USHRT_MAX.toDouble()
    val g = (g8 * 257) / USHRT_MAX.toDouble()
    val b = (b8 * 257) / USHRT_MAX.toDouble()
    val max = maxOf(r, g, b)
    val delta = max - minOf(r, g, b)
    val value = qRound(max * USHRT_MAX)
    if (abs(delta) <= 1e-12) return Hsv16(HUE_UNDEFINED, 0, value)
    val saturation = qRound((delta / max) * USHRT_MAX)
    var hue = when {
        fuzzyEq(r, max) -> (g - b) / delta
        fuzzyEq(g, max) -> 2.0 + (b - r) / delta
        else -> 4.0 + (r - g) / delta
    }
    hue *= 60.0
    if (hue < 0.0) hue += 360.0
    return Hsv16(qRound(hue * 100), saturation, value)
}

// QColor::convertTo(Rgb) from HSV, then the >> 8 that QColor::rgba() applies.
private fun hsv16ToRgb8(hsv: Hsv16): Triple<Int, Int, Int> {
    if (hsv.saturation == 0 || hsv.hue == HUE_UNDEFINED) {
        val v8 = hsv.value shr 8
        return Triple(v8, v8, v8)
    }
    val h = if (hsv.hue == 36000) 0.0 else hsv.hue / 6000.0
    val s = hsv.saturation / USHRT_MAX.toDouble()
    val v = hsv.value / USHRT_MAX.toDouble()
    val i = h.toInt()
    val f = h - i
    val p = v * (1.0 - s)
    val (r, g, b) = if (i and 1 != 0) {
        val q = v * (1.0 - (s * f))
        when (i) {
            1 -> Triple(q, v, p)
            3 -> Triple(p, q, v)
            else -> Triple(v, p, q)
        }
    } else {
        val t = v * (1.0 - (s * (1.0 - f)))
        when (i) {
            0 -> Triple(v, t, p)
            2 -> Triple(p, v, t)
            else -> Triple(t, p, v)
        }
    }
    return Triple(qRound(r * USHRT_MAX) shr 8, qRound(g * USHRT_MAX) shr 8, qRound(b * USHRT_MAX) shr 8)
}

private fun withHsv(color: Color, transform: (Hsv16) -> Hsv16): Color {
    val argb = color.toArgb()
    val hsv = transform(toHsv16((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF))
    val (r, g, b) = hsv16ToRgb8(hsv)
    return Color(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255), (argb ushr 24) and 0xFF)
}

/** QColor::darker: a factor of 100 or more scales the HSV value down; below 100 it lightens instead. */
internal fun qtDarker(color: Color, factor: Int): Color = when {
    factor <= 0 -> color
    factor < 100 -> qtLighter(color, 10000 / factor)
    else -> withHsv(color) { hsv -> Hsv16(hsv.hue, hsv.saturation, (hsv.value * 100) / factor) }
}

/** QColor::lighter: a factor above 100 scales the HSV value up (overflow drains saturation); below 100 it darkens. */
internal fun qtLighter(color: Color, factor: Int): Color = when {
    factor <= 0 -> color
    factor < 100 -> qtDarker(color, 10000 / factor)
    else -> withHsv(color) { hsv ->
        var v = (factor * hsv.value.toLong()) / 100
        var s = hsv.saturation
        if (v > USHRT_MAX) {
            s = maxOf(0, s - (v - USHRT_MAX).toInt())
            v = USHRT_MAX.toLong()
        }
        Hsv16(hsv.hue, s, v.toInt())
    }
}

/** std::minstd_rand0 (Park–Miller, multiplier 16807, modulus 2^31 - 1). [next] returns 1..2^31-2. */
internal class MinstdRand0(seed: Long) {
    private var x: Long = Math.floorMod(seed, MODULUS).let { if (it == 0L) 1L else it }

    fun next(): Long {
        x = (x * MULTIPLIER) % MODULUS
        return x
    }

    companion object {
        const val MODULUS = 2147483647L
        const val MULTIPLIER = 16807L
        const val MIN = 1L
        const val MAX = MODULUS - 1
    }
}

/** libstdc++'s std::uniform_int_distribution<int>(a, b) over minstd_rand0: its generator range is
 *  not a full 32 bits, so every libstdc++ version takes this plain downscaling path. */
internal fun MinstdRand0.uniformInt(a: Int, b: Int): Int {
    val urange = b.toLong() - a
    if (urange <= 0L) return a
    val urngRange = MinstdRand0.MAX - MinstdRand0.MIN
    val uerange = urange + 1
    val scaling = urngRange / uerange
    // A range wider than the generator's own would make `past` 0 and the loop below spin forever;
    // libstdc++ upscales there, which no caller of ours needs — just stay bounded.
    if (scaling == 0L) return a + ((next() - MinstdRand0.MIN) % uerange).toInt()
    val past = uerange * scaling
    var ret: Long
    do {
        ret = next() - MinstdRand0.MIN
    } while (ret >= past)
    return a + (ret / scaling).toInt()
}

/** klogg's own colour-variance ceiling; anything larger (a crafted token) is clamped to it. */
internal const val MAX_COLOR_VARIANCE = 100

/** The (fore, back) pair klogg paints a match of [matched] with when variate_colors is on. */
internal fun kloggVariedColors(fore: Color, back: Color, variance: Int, matched: String): Pair<Color, Color> {
    if (variance <= 0) return fore to back
    val spread = minOf(variance, MAX_COLOR_VARIANCE)
    val crc = CRC32().apply { update(matched.toByteArray(Charsets.UTF_8)) }.value
    val factor = MinstdRand0(crc).uniformInt(100 - spread, 100 + spread)
    return qtDarker(fore, factor) to qtDarker(back, factor)
}
