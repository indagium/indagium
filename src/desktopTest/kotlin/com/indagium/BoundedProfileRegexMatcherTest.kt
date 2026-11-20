package com.indagium

import com.indagium.utils.BoundedProfileRegexMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundedProfileRegexMatcherTest {
    // With a 1 ns budget the deadline check (every 1024 characters read) trips on any record longer than
    // that, while a short record never reaches a check: a deterministic stand-in for a slow match.
    private val pattern = "(\\d+)"
    private val bad = "a".repeat(5_000)
    private val good = "abc 1234"

    private fun matcher() = BoundedProfileRegexMatcher(pattern, matchBudgetNanos = 1L)

    @Test
    fun oneSlowRecordStaysRawButTheRecordsAfterItStillParse() {
        val matcher = matcher()
        assertEquals(listOf("1234", "1234"), matcher.groups(good))
        assertFalse(matcher.timedOut)
        assertNull(matcher.groups(bad))
        assertTrue(matcher.timedOut, "a record that timed out twice is reported")
        assertNotNull(matcher.groups(good), "the next record gets a fresh budget")
        assertNotNull(matcher.groups(good))
        assertTrue(matcher.timedOut, "the flag stays set once any record finally timed out")
    }

    @Test
    fun threeSlowRecordsInARowDisableTheMatcherButASuccessInBetweenResetsTheStreak() {
        val resetting = matcher()
        repeat(2) { assertNull(resetting.groups(bad)) }
        assertNotNull(resetting.groups(good))
        repeat(2) { assertNull(resetting.groups(bad)) }
        assertNotNull(resetting.groups(good), "two slow records after a success do not disable it")

        val disabled = matcher()
        repeat(3) { assertNull(disabled.groups(bad)) }
        assertNull(disabled.groups(good), "after three in a row the rest of the parse stays raw")
        assertTrue(disabled.timedOut)
    }

    @Test
    fun scatteredSlowRecordsDisableTheMatcherOnceTheirTotalReachesTheCap() {
        val matcher = matcher()
        repeat(7) {
            assertNull(matcher.groups(bad))
            assertNotNull(matcher.groups(good), "a success between slow records keeps it going below the cap")
        }
        assertNull(matcher.groups(bad))
        assertNull(matcher.groups(good), "the eighth slow record in one parse ends matching for the rest of it")
    }
}
