@file:Suppress("MagicNumber") // Explicit generated timestamp and metadata examples.

package com.indagium.ram.compact

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.utils.deltaMillis
import com.indagium.utils.parseMillisOfDay
import com.indagium.utils.unrollLogTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompactCandidatesTest {
    private fun row(ts: String) = LogEntry(7, ts, LogLevel.W, "生成", "message", 12, 34)

    @Test fun canonicalAndFallbackRetainExactText() {
        val cases = mapOf("00:00:00.000" to 0, "12:34:56.789" to 45_296_789,
            "23:59:59.999" to 86_399_999, "12:34:56" to -1,
            "12:34:56.1" to -1, "12:34:56.123456" to -1, "" to -1,
            "24:00:00.000" to -1, "12:60:00.000" to -1, "12:34:56.１２３" to -1,
            "2026-11-04 12:34:56.789" to -1, "12:34:56.789Z" to -1)
        for ((text, expected) in cases) {
            assertEquals(expected, encodeCanonicalTimestamp(text), text)
            val candidate = TimestampCandidate(row(text))
            assertEquals(text, candidate.text(), text)
            assertEquals(row(text), candidate.toEntry(), text)
            assertEquals(parseMillisOfDay(text), parseMillisOfDay(candidate.text()), text)
        }
    }

    @Test fun sampledCanonicalTimesRoundTripExactly() {
        for (millis in 0 until 86_400_000 step 9973) {
            val expected = decodeCanonicalTimestamp(millis)
            assertEquals(millis, encodeCanonicalTimestamp(expected))
            assertEquals(millis.toLong(), parseMillisOfDay(expected))
        }
    }

    @Test fun optionalMetadataPreservesPlainMergedDltAndMixedRows() {
        val plain = row("01:02:03.004")
        val merged = plain.copy(sourceTag = "source-A")
        val dlt = plain.copy(dltEcuId = "ECU", dltAppId = "APP", dltContextId = "CTX",
            dltMessageType = "log", dltTimestamp = 123456789L, dltTimestampSource = "viewer")
        val mixed = dlt.copy(sourceTag = "source-B", dayOfYear = 365)
        assertNull(MetadataCandidate(plain).metadata)
        for (entry in listOf(plain, merged, dlt, mixed, plain.copy(dltTimestamp = 0L))) {
            assertEquals(entry, MetadataCandidate(entry).toEntry())
            assertEquals(entry, TimestampCandidate(entry).toEntry())
        }
        assertNotNull(MetadataCandidate(merged).metadata)
        assertNotNull(MetadataCandidate(dlt).metadata)
    }

    @Test fun datesAndMidnightTimelineSurviveBothConversions() {
        val rows = listOf(row("23:59:59.999").copy(id = 1, dayOfYear = 365),
            row("00:00:00.001").copy(id = 2, dayOfYear = 0),
            row("00:00:00.123456").copy(id = 3, dayOfYear = 0))
        assertEquals(2L, deltaMillis(rows[0].ts, rows[1].ts))
        val expected = unrollLogTimeline(rows)
        assertEquals(expected, unrollLogTimeline(rows.map { MetadataCandidate(it).toEntry() }))
        assertEquals(expected, unrollLogTimeline(rows.map { TimestampCandidate(it).toEntry() }))
        assertTrue(TimestampCandidate(rows.last()).fallback != null)
    }
}
