package com.indagium

import com.indagium.model.CompareFilterTarget
import com.indagium.ui.AppState
import com.indagium.ui.compareStateToken
import com.indagium.ui.restoreCompareState
import com.indagium.ui.tokenFields
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The compare token is positional and decoded with getOrNull, so the filter target is APPENDED as field 13. A token written before it
 * (13 fields) restores A / BOTH from the legacy compareFilterRight field; a new token round-trips B and still writes that legacy field.
 */
class CompareTokenTest {
    // The decoded field values, and the raw (still encoded) fields for building an older-format token by cutting fields off the end.
    private fun fields(token: String): List<String> = token.tokenFields()

    private fun rawFields(token: String): List<String> = token.split("|")

    @Test
    fun theTargetIsTheLastFieldAndTheLegacyFlagIsStillWritten() {
        val state = AppState()
        state.compareFilterTarget = CompareFilterTarget.B
        val written = fields(state.compareStateToken())
        assertEquals(14, written.size)
        assertEquals("B", written[13])
        assertEquals("false", written[2], "legacy compareFilterRight is target == BOTH")
        state.close()

        val both = AppState()
        both.compareFilterTarget = CompareFilterTarget.BOTH
        val writtenBoth = fields(both.compareStateToken())
        assertEquals("true", writtenBoth[2])
        assertEquals("BOTH", writtenBoth[13])
        both.close()
    }

    @Test
    fun aNewTokenRoundTripsEveryTarget() {
        CompareFilterTarget.entries.forEach { target ->
            val source = AppState()
            source.compareFilterTarget = target
            val token = source.compareStateToken()
            source.close()

            val restored = AppState()
            restored.restoreCompareState(token)
            assertEquals(target, restored.compareFilterTarget)
            restored.close()
        }
    }

    @Test
    fun aLegacyTokenWithoutTheTargetRestoresFromTheOldFlag() {
        val source = AppState()
        source.compareFilterTarget = CompareFilterTarget.BOTH
        val legacyOn = rawFields(source.compareStateToken()).take(13).joinToString("|")
        source.compareFilterTarget = CompareFilterTarget.A
        val legacyOff = rawFields(source.compareStateToken()).take(13).joinToString("|")
        source.close()
        assertEquals("true", fields(legacyOn)[2])
        assertEquals("false", fields(legacyOff)[2])
        assertEquals(13, fields(legacyOn).size)

        val on = AppState()
        on.compareFilterTarget = CompareFilterTarget.B
        on.restoreCompareState(legacyOn)
        assertEquals(CompareFilterTarget.BOTH, on.compareFilterTarget)
        assertTrue(on.compareFilterRight)
        on.close()

        val off = AppState()
        off.restoreCompareState(legacyOff)
        assertEquals(CompareFilterTarget.A, off.compareFilterTarget)
        assertEquals(false, off.compareFilterRight)
        off.close()
    }

    @Test
    fun anUnknownTargetNameFallsBackToTheLegacyFlag() {
        val source = AppState()
        source.compareFilterTarget = CompareFilterTarget.A
        val damaged = (rawFields(source.compareStateToken()).take(13) + tokenFields("SIDEWAYS")).joinToString("|")
        source.close()

        val restored = AppState()
        restored.restoreCompareState(damaged)
        assertEquals(CompareFilterTarget.A, restored.compareFilterTarget)
        restored.close()
    }

    @Test
    fun compareFilterRightIsAMappedViewOfTheTarget() {
        val state = AppState()
        assertEquals(CompareFilterTarget.BOTH, state.compareFilterTarget, "the default stays 'filter both panes'")
        state.compareFilterRight = false
        assertEquals(CompareFilterTarget.A, state.compareFilterTarget)
        state.compareFilterTarget = CompareFilterTarget.B
        assertEquals(false, state.compareFilterRight)
        state.compareFilterRight = false
        assertEquals(CompareFilterTarget.B, state.compareFilterTarget, "off is already true for B; it must not jump to A")
        state.compareFilterRight = true
        assertEquals(CompareFilterTarget.BOTH, state.compareFilterTarget)
        state.close()
    }
}
