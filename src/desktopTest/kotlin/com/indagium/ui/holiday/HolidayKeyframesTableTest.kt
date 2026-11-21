@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The table-driven CSS animation evaluator against the stops, durations, delays and timing functions of holiday.css. */
class HolidayKeyframesTableTest {
    private fun frame(cls: String, t: Float) = assertNotNull(holidayAnimFrame(cls, t), cls)

    @Test fun anUnknownClassHasNoFrame() {
        assertNull(holidayAnimFrame("s-nope", 1f))
        assertNull(holidayStillFrame("s-nope"))
        assertTrue(!isHolidayAnimClass("s-nope"))
    }

    @Test fun swayWaveAlternateBetweenTheirAngles() {
        assertEquals(-1.6f, frame("s-sway", 0f).rotateDeg, .001f)
        assertEquals(0f, frame("s-sway", 1.9f).rotateDeg, .001f)
        assertEquals(1.6f, frame("s-sway", 3.8f).rotateDeg, .001f)
        assertEquals(0f, frame("s-sway", 5.7f).rotateDeg, .001f)
        assertEquals(-1.6f, frame("s-sway", 7.6f).rotateDeg, .001f)
        assertEquals(-7f, frame("s-wave", 0f).rotateDeg, .001f)
        assertEquals(9f, frame("s-wave", 1.6f).rotateDeg, .001f)
        assertEquals(-7f, frame("s-wave", 3.2f).rotateDeg, .001f)
        assertEquals(9f, frame("s-wave2", 1.2f).rotateDeg, .001f)
        // The same curve the hand-written sway uses.
        listOf(.3f, 1.1f, 2.9f, 4.4f, 6.6f).forEach { assertEquals(swayAngle(it, 3.8f), frame("s-sway", it).rotateDeg, .001f) }
    }

    @Test fun snowFallsLinearlyAndFadesAtBothEnds() {
        assertEquals(-30f, frame("s-snow", 0f).translateY, .001f)
        assertEquals(0f, frame("s-snow", 0f).opacity, .001f)
        assertEquals(1f, frame("s-snow", .6f).opacity, .001f)
        assertEquals(145f, frame("s-snow", 3f).translateY, .01f)
        assertEquals(.5f, frame("s-snow", 5.7f).opacity, .001f)
        // s-snow2 started 2.5 s ago (animation-delay -2.5s) over 7.5 s: a third of the way down at t = 0, back at the top at t = 5.
        assertEquals(-30f + 350f / 3f, frame("s-snow2", 0f).translateY, .01f)
        assertEquals(1f, frame("s-snow2", 0f).opacity, .001f)
        assertEquals(-30f, frame("s-snow2", 5f).translateY, .01f)
        // s-snow3: 5.2 s from -4 s.
        assertEquals(-30f + 350f * (4f / 5.2f), frame("s-snow3", 0f).translateY, .01f)
    }

    @Test fun twinkleAndPulseBreatheThroughTheirMidStop() {
        frame("s-twinkle", 0f).let {
            assertEquals(.35f, it.opacity, .001f)
            assertEquals(.7f, it.scaleX, .001f)
            assertEquals(.7f, it.scaleY, .001f)
        }
        frame("s-twinkle", .9f).let {
            assertEquals(1f, it.opacity, .001f)
            assertEquals(1f, it.scaleX, .001f)
        }
        assertEquals(.35f, frame("s-twinkle", 1.8f).opacity, .001f)
        assertEquals(.35f, frame("s-twinkle2", 1.3f).opacity, .001f, "2.3 s from -1 s: back at the first stop at 1.3 s")
        frame("s-pulse", 0f).let {
            assertEquals(.8f, it.opacity, .001f)
            assertEquals(1f, it.scaleX, .001f)
        }
        frame("s-pulse", 1.6f).let {
            assertEquals(1f, it.opacity, .001f)
            assertEquals(1.07f, it.scaleX, .001f)
        }
        assertEquals(1.07f, frame("s-star", 1.2f).scaleY, .001f)
        assertEquals(1.07f, frame("s-nose", .7f).scaleY, .001f)
    }

    @Test fun lightsBlinkOutOfStep() {
        assertEquals(1f, frame("s-light", 0f).opacity, .001f)
        assertEquals(.35f, frame("s-light", .8f).opacity, .001f)
        assertEquals(.35f, frame("s-light2", .25f).opacity, .001f, "-.55 s")
        assertEquals(.35f, frame("s-light3", 1.3f).opacity, .001f, "-1.1 s")
    }

    @Test fun steamRisesWidensAndFades() {
        frame("s-steam", 0f).let {
            assertEquals(6f, it.translateY, .001f)
            assertEquals(.9f, it.scaleX, .001f)
            assertEquals(0f, it.opacity, .001f)
        }
        val peak = frame("s-steam", 3.4f * .35f)
        assertEquals(.8f, peak.opacity, .001f)
        assertEquals(6f - 32f * easeInOut(.35f), peak.translateY, .001f)
        assertEquals(.9f + .35f * easeInOut(.35f), peak.scaleX, .001f)
        assertEquals(0f, frame("s-steam2", 1.7f).opacity, .001f, "-1.7 s: starts a half cycle behind")
        assertEquals(6f, frame("s-steam2", 1.7f).translateY, .001f)
    }

    @Test fun earsTwitchAtTheEndOfEachCycle() {
        assertEquals(0f, frame("s-ear", 2.4f).rotateDeg, .001f)
        assertEquals(-9f, frame("s-ear", 2.58f).rotateDeg, .001f)
        assertEquals(6f, frame("s-ear", 2.76f).rotateDeg, .001f)
        assertEquals(0f, frame("s-ear", 3f).rotateDeg, .001f)
        assertEquals(-9f, frame("s-ear2", 2.38f).rotateDeg, .001f, "-.2 s")
    }

    @Test fun blinkClosesTheEyesBriefly() {
        assertEquals(1f, frame("s-blink", 4.14f).scaleY, .001f)
        assertEquals(.12f, frame("s-blink", 4.324f).scaleY, .001f)
        assertEquals(1f, frame("s-blink", 4.6f).scaleY, .001f)
        assertEquals(1f, frame("s-blink", 4.324f).scaleX, .001f, "only the height changes")
    }

    @Test fun burstGrowsAndFadesWithEaseOut() {
        frame("s-burst", 0f).let {
            assertEquals(.15f, it.scaleX, .001f)
            assertEquals(0f, it.opacity, .001f)
        }
        assertEquals(1f, frame("s-burst", 3.4f * .08f).opacity, .001f)
        assertEquals(.9f, frame("s-burst", 3.4f * .55f).opacity, .001f)
        // Ease-out: further along than linear at the middle of the 8 % to 55 % leg... and in scale at a quarter of the cycle.
        assertTrue(frame("s-burst", 3.4f * .25f).scaleX > .15f + .85f * .25f)
        assertEquals(.15f, frame("s-burst2", 2.2f).scaleX, .001f, "3.4 s from -1.2 s: restarts at 2.2 s")
        assertEquals(.15f, frame("s-burst3", 1.1f).scaleX, .001f, "3.4 s from -2.3 s: restarts at 1.1 s")
    }

    @Test fun tickHoldsEachStepUntilTheNext() {
        assertEquals(-24f, frame("s-tick", 0f).rotateDeg, .001f)
        assertEquals(-24f, frame("s-tick", .99f).rotateDeg, .001f)
        assertEquals(-18f, frame("s-tick", 1.01f).rotateDeg, .001f)
        assertEquals(-12f, frame("s-tick", 2.01f).rotateDeg, .001f)
        assertEquals(-6f, frame("s-tick", 3.01f).rotateDeg, .001f)
        assertEquals(0f, frame("s-tick", 4.01f).rotateDeg, .001f)
        assertEquals(0f, frame("s-tick", 4.99f).rotateDeg, .001f)
        assertEquals(-24f, frame("s-tick", 5.01f).rotateDeg, .001f)
    }

    @Test fun risingSparksEaseInAndFade() {
        assertEquals(0f, frame("s-rise", 0f).translateY, .001f)
        assertEquals(0f, frame("s-rise", 0f).opacity, .001f)
        assertEquals(1f, frame("s-rise", 2.4f * .15f).opacity, .001f)
        val mid = frame("s-rise", 1.2f)
        assertTrue(mid.translateY < 0f && mid.translateY > -40f, "ease-in is behind linear halfway: ${mid.translateY}")
        assertEquals(0f, frame("s-rise2", 1.9f).translateY, .001f, "2.9 s from -1 s: restarts at 1.9 s")
        assertEquals(0f, frame("s-rise3", .6f).translateY, .001f, "2.2 s from -1.6 s: restarts at .6 s")
    }

    @Test fun floatBobsAndTilts() {
        frame("s-float", 0f).let {
            assertEquals(0f, it.translateY, .001f)
            assertEquals(-1.5f, it.rotateDeg, .001f)
        }
        frame("s-float", 2.1f).let {
            assertEquals(-10f, it.translateY, .001f)
            assertEquals(1.5f, it.rotateDeg, .001f)
        }
        assertEquals(0f, frame("s-float", 4.2f).translateY, .001f)
        assertEquals(0f, frame("s-float2", 3.6f).translateY, .001f, "5.4 s from -1.8 s")
        assertEquals(-10f, frame("s-float3", 0.9f).translateY, .001f, "3.6 s from -.9 s: the mid stop at .9 s")
    }

    @Test fun gridSpinAndScrollAreLinearOrStepped() {
        assertEquals(11f, frame("s-grid", .6f).translateY, .001f)
        assertEquals(0f, frame("s-grid", 1.2f).translateY, .001f)
        assertEquals(180f, frame("s-spin2", 1.8f).rotateDeg, .01f)
        assertEquals(180f, frame("s-spin", 4.5f).rotateDeg, .01f)
        assertEquals(0f, frame("s-scroll", 0f).translateY, .001f)
        assertEquals(0f, frame("s-scroll", .99f).translateY, .001f)
        assertEquals(-18f, frame("s-scroll", 1.01f).translateY, .001f)
        assertEquals(-144f, frame("s-scroll", 8.99f).translateY, .001f)
        assertEquals(0f, frame("s-scroll", 9.01f).translateY, .001f)
    }

    @Test fun cursorsBlinkOnAndOffWithSteps() {
        listOf("s-cursor", "s-cursorbar").forEach {
            assertEquals(1f, frame(it, .25f).opacity, 0f)
            assertEquals(0f, frame(it, .75f).opacity, 0f)
            assertEquals(1f, frame(it, 1.25f).opacity, 0f)
        }
    }

    @Test fun everyClassStaysFiniteAndInRange() {
        for (cls in HOLIDAY_ANIM_CLASSES) {
            for (i in 0..400) {
                val f = frame(cls, i * .137f)
                val all = listOf(f.translateX, f.translateY, f.rotateDeg, f.scaleX, f.scaleY, f.opacity)
                assertTrue(all.all { it.isFinite() }, "$cls at $i")
                assertTrue(f.opacity in 0f..1f, "$cls opacity ${f.opacity}")
            }
        }
    }

    @Test fun stillPosesAreCalmExceptForBurstsAndRisingSparks() {
        for (cls in HOLIDAY_ANIM_CLASSES) {
            val still = assertNotNull(holidayStillFrame(cls))
            when (cls) {
                "s-burst", "s-burst2", "s-burst3" -> assertEquals(HolidayAnimFrame(opacity = .9f), still, cls)
                "s-rise", "s-rise2", "s-rise3" -> assertEquals(HolidayAnimFrame(opacity = 0f), still, cls)
                else -> assertEquals(HolidayAnimFrame(), still, cls)
            }
        }
    }
}
