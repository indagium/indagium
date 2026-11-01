// Times and amplitudes are the design canvas' animation values, literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium

import com.indagium.ui.holiday.*
import com.indagium.ui.holiday.HOLIDAY_STILL_TIME
import com.indagium.ui.holiday.pumpkin.batBob
import com.indagium.ui.holiday.pumpkin.batFlap
import com.indagium.ui.holiday.pumpkin.catBlink
import com.indagium.ui.holiday.pumpkin.catTailAngle
import com.indagium.ui.holiday.pumpkin.fireflyAt
import com.indagium.ui.holiday.pumpkin.moonPulse
import com.indagium.ui.holiday.pumpkin.pumpkinFlicker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HolidayAnimationTest {
    private val times = (0..2000).map { it * 0.0137f }

    @Test
    fun theFlickerStaysBetween078And1AndRepeatsEvery26Seconds() {
        times.forEach { t ->
            val v = pumpkinFlicker(t)
            assertTrue(v in 0.75f..1f, "flicker $v at $t")
            assertEquals(v, pumpkinFlicker(t + 2.6f), 1e-3f, "periodic at $t")
        }
        assertEquals(0.78f, pumpkinFlicker(0.07f * 2.6f), 1e-3f)
        assertEquals(1f, pumpkinFlicker(0f), 1e-6f)
    }

    @Test
    fun theSmallPumpkinFlickersOnItsOwnPeriodAndDelay() {
        times.forEach { t ->
            assertTrue(pumpkinFlicker(t, 2.1f, -0.7f) in 0.75f..1f)
            assertEquals(pumpkinFlicker(t, 2.1f, -0.7f), pumpkinFlicker(t + 2.1f, 2.1f, -0.7f), 1e-3f)
        }
        assertTrue(pumpkinFlicker(1f) != pumpkinFlicker(1f, 2.1f, -0.7f), "the two flickers are out of step")
    }

    @Test
    fun theBlinkStaysBetween012And1AndEyesAreOpenInTheStillFrame() {
        times.forEach { t ->
            val v = catBlink(t)
            assertTrue(v in 0.12f..1f, "blink $v at $t")
            assertEquals(v, catBlink(t + 4.6f), 1e-3f)
        }
        assertEquals(0.12f, catBlink(4.6f * 0.94f), 1e-3f)
        assertEquals(1f, catBlink(HOLIDAY_STILL_TIME), 1e-6f)
        assertEquals(1f, catBlink(0f), 1e-6f)
    }

    @Test
    fun theTailSwishesBetweenMinus7And9AndRepeats() {
        times.forEach { t ->
            val a = catTailAngle(t)
            assertTrue(a in -7f..9f, "tail $a at $t")
            assertEquals(a, catTailAngle(t + 2.8f), 1e-3f)
        }
        assertEquals(-7f, catTailAngle(0f), 1e-4f)
        assertEquals(9f, catTailAngle(1.4f), 1e-4f)
    }

    @Test
    fun theBobsStayWithinTenDpAndOneAndAHalfDegrees() {
        times.forEach { t ->
            for (i in 0..3) {
                val b = fireflyAt(i, t)
                assertTrue(b.dy in -10f..0f && b.deg in -1.5f..1.5f, "firefly $i at $t: $b")
            }
            for (i in 0..1) {
                val b = batBob(i, t)
                assertTrue(b.dy in -10f..0f && b.deg in -1.5f..1.5f, "bat $i at $t: $b")
            }
            assertTrue(batFlap(t) in 0.35f..1f)
        }
        assertEquals(batBob(0, 0.3f).dy, batBob(0, 0.3f + 4.2f).dy, 1e-3f)
    }

    @Test
    fun theHaloPulsesBetweenTheCanvasBounds() {
        times.forEach { t ->
            val p = moonPulse(t)
            assertTrue(p.alpha in 0.8f..1f && p.scale in 1f..1.07f, "pulse $p at $t")
        }
    }
}
