package com.indagium.cases

import com.indagium.model.Filter
import com.indagium.model.LogLevel
import com.indagium.model.MessageRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaseModelTest {
    @Test
    fun camelCaseCompoundWordIndexesAsWholeAndBothHalves() {
        val tokens = tokenize("DeviceManager")

        assertEquals(setOf("devicemanager", "device", "manager"), tokens)
    }

    @Test
    fun digitBoundariesSplitIntoSeparateSubTokensAlongsideTheWholeWord() {
        val tokens = tokenize("Error42Handler")

        assertEquals(setOf("error42handler", "error", "42", "handler"), tokens)
    }

    @Test
    fun acronymRunSplitsBeforeTheTrailingCapitalizedWord() {
        val tokens = tokenize("HTTPServer")

        assertEquals(setOf("httpserver", "http", "server"), tokens)
    }

    @Test
    fun plainLowercaseWordsAreUnaffected() {
        val tokens = tokenize("application not responding")

        assertEquals(setOf("application", "not", "responding"), tokens)
    }

    @Test
    fun tokensShorterThanTheMinimumLengthAreDroppedButLongerOnesAndTheirSubTokensSurvive() {
        val tokens = tokenize("a I DeviceX")

        // "a"/"I" are single characters, below the 2-char floor, and dropped entirely. "DeviceX"
        // keeps its whole (lowercased) form AND splits on the lower-to-upper boundary into
        // "Device"/"X" — "X" alone is then dropped for being too short, but "device" survives.
        assertEquals(setOf("devicex", "device"), tokens)
    }

    @Test
    fun describeFilterSummarizesActiveTagsAndAContiguousLevelFloor() {
        val filter = Filter(activeTags = setOf("DeviceManager"), levels = setOf(LogLevel.W, LogLevel.E, LogLevel.A))

        assertEquals("tag=DeviceManager, level≥W", describeFilter(filter))
    }

    @Test
    fun describeFilterFallsBackToAnExplicitLevelListWhenLevelsArentContiguousFromAFloor() {
        val filter = Filter(levels = setOf(LogLevel.V, LogLevel.E))

        assertEquals("levels=V,E", describeFilter(filter))
    }

    @Test
    fun describeFilterReportsNoConstraintsForAnEmptyDefaultFilter() {
        assertEquals("No filter constraints", describeFilter(Filter()))
    }

    @Test
    fun describeFilterIncludesKeywordAndMessageRuleCounts() {
        val filter = Filter(kwText = "boot failure", messageRules = listOf(dummyMessageRule(), dummyMessageRule()))

        val summary = describeFilter(filter)

        assertTrue(summary.contains("kw=\"boot failure\""))
        assertTrue(summary.contains("2 message rules"))
    }

    private fun dummyMessageRule() = MessageRule(id = "r${System.nanoTime()}", include = true, pattern = "x")
}
