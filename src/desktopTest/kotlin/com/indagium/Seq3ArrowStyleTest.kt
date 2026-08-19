package com.indagium

import com.indagium.diagram3.Seq3ArrowStyle
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.seq3ArrowStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins [seq3ArrowStyle]'s values to the exact numbers `Seq3Raster`'s pre-existing `strokeFor`/
 *  `paintArrowRow` used before this descriptor existed (`DASH_RETURN`=6/4, `DASH_ASYNC`=3/3,
 *  `STROKE_THIN`=1f/`STROKE_THICK`=1.6f) — see Seq3ArrowStyle.kt's own header. A regression here
 *  would silently change the exported PNG, not just the (not-yet-wired, WP2) Compose canvas. */
class Seq3ArrowStyleTest {
    @Test
    fun returnIsDashedOpenHeadedAndThin() {
        assertEquals(Seq3ArrowStyle(dash = listOf(6f, 4f), filledHead = false, thin = true), seq3ArrowStyle(Seq3Kind.RETURN))
    }

    @Test
    fun asyncIsDashedOpenHeadedAndThin() {
        assertEquals(Seq3ArrowStyle(dash = listOf(3f, 3f), filledHead = false, thin = true), seq3ArrowStyle(Seq3Kind.ASYNC))
    }

    @Test
    fun everyOtherKindIsSolidFilledAndThick() {
        for (kind in listOf(Seq3Kind.CALL, Seq3Kind.SELF, Seq3Kind.NOTE)) {
            assertEquals(Seq3ArrowStyle(dash = null, filledHead = true, thin = false), seq3ArrowStyle(kind), "unexpected style for $kind")
        }
    }

    @Test
    fun structuralEqualityHoldsAcrossDistinctCallsSoACacheKeyCanTrustIt() {
        // The whole point of List<Float> over FloatArray (see Seq3ArrowStyle.kt's own doc): two
        // independently-produced instances for the same kind must be `==`, not merely
        // reference-equal.
        assertEquals(seq3ArrowStyle(Seq3Kind.RETURN), seq3ArrowStyle(Seq3Kind.RETURN))
        assertEquals(seq3ArrowStyle(Seq3Kind.RETURN).hashCode(), seq3ArrowStyle(Seq3Kind.RETURN).hashCode())
    }
}
