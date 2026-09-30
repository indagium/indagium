package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.HighlightTarget
import com.indagium.model.Highlighter
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.utils.CANCELLATION_CHECK_INTERVAL
import com.indagium.utils.CancellationCheck
import com.indagium.utils.countHighlighterRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighlightCountTest {
    private val entries = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "Net", "Request done"),
        LogEntry(2, "10:00:00.001", LogLevel.I, "Net", "request failed"),
        LogEntry(3, "10:00:00.002", LogLevel.E, "Db", "Request queued"),
        LogEntry(4, "10:00:00.003", LogLevel.I, "Db", "idle"),
    )

    private fun hl(
        id: String,
        pattern: String,
        regex: Boolean = false,
        target: HighlightTarget = HighlightTarget.ANY,
        tag: String? = null,
        caseSensitive: Boolean = false,
        on: Boolean = true,
        wholeLine: Boolean = false,
    ) = Highlighter(id, pattern, regex, Color.Yellow, on, wholeLine = wholeLine, target = target, tag = tag, caseSensitive = caseSensitive)

    private val never = CancellationCheck {}

    @Test
    fun countsRowsPerHighlighterThroughTheSharedMatcher() {
        val result = countHighlighterRows(
            entries,
            listOf(
                hl("any", "request"),
                hl("cs", "Request", caseSensitive = true),
                hl("rx", "done|idle", regex = true),
                hl("tag", "Db", target = HighlightTarget.TAG),
                hl("msg", "Request", target = HighlightTarget.MESSAGE, tag = "Db"),
            ),
            never,
        )
        assertEquals(3, result.counts["any"])
        assertEquals(2, result.counts["cs"])
        assertEquals(2, result.counts["rx"])
        assertEquals(2, result.counts["tag"])
        assertEquals(1, result.counts["msg"])
        assertFalse(result.capped)
        assertEquals(4, result.scanned)
    }

    @Test
    fun anOffHighlighterAndWholeLineAreCountedLikeAnyOtherBecauseTheyOnlyChangePainting() {
        val result = countHighlighterRows(entries, listOf(hl("off", "request", on = false), hl("line", "request", wholeLine = true)), never)
        assertEquals(3, result.counts["off"])
        assertEquals(3, result.counts["line"])
    }

    @Test
    fun blankPatternsAreNotCounted() {
        val result = countHighlighterRows(entries, listOf(hl("blank", "  ")), never)
        assertTrue(result.counts.isEmpty())
    }

    @Test
    fun aSupersededScanStopsThroughItsCancellationCheck() {
        val many = List(CANCELLATION_CHECK_INTERVAL * 2 + 10) { LogEntry(it + 1, "10:00:00.000", LogLevel.I, "T", "row $it") }
        var checks = 0
        assertFailsWith<IllegalStateException> {
            countHighlighterRows(many, listOf(hl("a", "row")), CancellationCheck { checks++; error("cancelled") })
        }
        assertEquals(1, checks, "the scan stops at the first failed check instead of finishing")
    }

    @Test
    fun theScanIsCappedAndReportsItSoTheCountCanShowAsAtLeast() {
        val many = List(100) { LogEntry(it + 1, "10:00:00.000", LogLevel.I, "T", "row $it") }
        val result = countHighlighterRows(many, listOf(hl("a", "row")), never, scanLimit = 30)
        assertEquals(30, result.counts["a"])
        assertEquals(30, result.scanned)
        assertTrue(result.capped)
        assertFalse(countHighlighterRows(many, listOf(hl("a", "row")), never, scanLimit = 100).capped)
    }
}
