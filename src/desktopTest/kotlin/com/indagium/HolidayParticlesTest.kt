// Window sizes and durations of the effects under test are literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium

import com.indagium.ui.holiday.HOLIDAY_FLASH_MAX_ALPHA
import com.indagium.ui.holiday.HOLIDAY_FLASH_SEC
import com.indagium.ui.holiday.holidayFlashAlpha
import com.indagium.ui.holiday.pumpkin.Bat
import com.indagium.ui.holiday.pumpkin.BatField
import com.indagium.ui.holiday.pumpkin.Ember
import com.indagium.ui.holiday.pumpkin.EmberField
import com.indagium.ui.holiday.pumpkin.PumpkinBats
import com.indagium.ui.holiday.pumpkin.PumpkinEmbers
import com.indagium.ui.holiday.pumpkin.batAt
import com.indagium.ui.holiday.pumpkin.batWing
import com.indagium.ui.holiday.pumpkin.emberAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HolidayParticlesTest {
    private val times = (0..3000).map { it * 0.173f }

    // Full, Calm and Off as the framework resolves them: (count scale, speed scale).
    private val full = 1f to 1f
    private val calm = 0.4f to 0.6f
    private val off = 0f to 0f

    private fun embers(seed: Int, scales: Pair<Float, Float>): List<Ember> = (PumpkinEmbers.spawn(seed, scales.first, scales.second) as EmberField).embers

    private fun bats(seed: Int, scales: Pair<Float, Float>): List<Bat> = (PumpkinBats.spawn(seed, scales.first, scales.second) as BatField).bats

    @Test
    fun theSameSeedGivesTheSameParticlesAndAnotherSeedDoesNot() {
        assertEquals(embers(7, full), embers(7, full))
        assertNotEquals(embers(7, full), embers(8, full))
        assertEquals(bats(7, full), bats(7, full))
        assertNotEquals(bats(7, full), bats(8, full))
    }

    @Test
    fun fullHas36EmbersAnd3Bats() {
        assertEquals(36, embers(1, full).size)
        assertEquals(3, bats(1, full).size)
    }

    @Test
    fun calmHasFewerAndSlowerParticles() {
        val f = embers(1, full)
        val c = embers(1, calm)
        assertEquals(14, c.size)
        assertEquals(1, bats(1, calm).size)
        assertTrue(c.size < f.size && bats(1, calm).size < bats(1, full).size)
        // The same ember draws are slower: every period is divided by the 0.6 speed scale.
        assertTrue(c.first().periodSec > f.first().periodSec)
        assertEquals(f.first().periodSec / 0.6f, c.first().periodSec, 1e-3f)
    }

    @Test
    fun intensityOffHasNoParticles() {
        assertTrue(embers(1, off).isEmpty() && bats(1, off).isEmpty())
    }

    @Test
    fun theEffectsAreIndependentSwitchesWithStableIds() {
        assertEquals(setOf("pumpkin.embers", "pumpkin.bats"), setOf(PumpkinEmbers.id, PumpkinBats.id))
        assertEquals(PumpkinEmbers.spawn(1, 1f, 1f).count, 36)
        assertEquals(PumpkinBats.spawn(1, 1f, 1f).count, 3)
    }

    @Test
    fun embersStayInTheWindowAndFadeInAndOut() {
        val w = 1200f
        val h = 800f
        embers(3, full).forEach { e ->
            times.forEach { t ->
                val p = emberAt(e, t, w, h)
                assertTrue(p.x in 0f..w && p.y in 0f..h, "ember out of bounds at $t: $p")
                assertTrue(p.alpha in 0f..0.85f, "ember alpha ${p.alpha}")
            }
        }
    }

    @Test
    fun batsFlyAcrossTheWindowWithinItsHeight() {
        val w = 1200f
        val h = 800f
        bats(3, full).forEach { b ->
            val xs = times.map { batAt(b, it, w, h).x }
            times.forEach { t ->
                val p = batAt(b, t, w, h)
                assertTrue(p.y in 0f..h && p.alpha in 0f..1f, "bat at $t: $p")
                assertTrue(p.extra in 0.35f..1f, "wing factor ${p.extra}")
                assertTrue(p.x >= -2f * b.size - 0.01f && p.x <= w + 2f * b.size + 0.01f, "bat x ${p.x}")
            }
            assertTrue(xs.min() < 0f && xs.max() > w, "the bat enters and leaves the window")
        }
        times.forEach { assertTrue(batWing(it) in 0.35f..1f) }
    }

    @Test
    fun flashesAreFaintBriefAndRareForFullAndCalm() {
        for ((minGap, seed) in listOf(20f to 5, 45f to 9, 20f to 12345)) {
            // Sampled at 10 ms over ten minutes.
            val step = 0.01f
            var inFlash = false
            var flashStart = 0f
            var lastEnd = Float.NEGATIVE_INFINITY
            var flashes = 0
            for (i in 0..60_000) {
                val t = i * step
                val a = holidayFlashAlpha(seed, t, minGap)
                assertTrue(a in 0f..HOLIDAY_FLASH_MAX_ALPHA, "alpha $a at $t")
                if (a > 0f && !inFlash) {
                    inFlash = true
                    flashStart = t
                    flashes++
                    assertTrue(flashStart - lastEnd >= minGap - 0.05f, "flashes $minGap s apart: ${flashStart - lastEnd} at $t")
                } else if (a == 0f && inFlash) {
                    inFlash = false
                    lastEnd = t
                    assertTrue(t - flashStart <= HOLIDAY_FLASH_SEC + step, "flash lasted ${t - flashStart}")
                }
            }
            assertTrue(flashes in 1..(600 / minGap.toInt()), "flashes in ten minutes: $flashes")
        }
    }

    @Test
    fun flashesAreDeterministic() {
        times.forEach { assertEquals(holidayFlashAlpha(4, it, 20f), holidayFlashAlpha(4, it, 20f)) }
        assertEquals(0f, holidayFlashAlpha(4, -1f, 20f))
    }
}
