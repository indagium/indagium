package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.buildFullLineAnnotation
import com.indagium.utils.RegexEvaluationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Task 2: the ts/pid-tid CELL background wash a thread-scoped ("async") sequence row gets, in
// addition to its existing foreground tint — see ui/LogViewer.kt's appendTsPidTid/
// buildFullLineAnnotation (cellBg param) and LogRow's own cellBg computation. Pure AnnotatedString
// construction, so — like ProcessNameRenderingTest's own buildFullLineAnnotation coverage — this is
// testable without a Compose UI harness even though the actual pixel result is not.
class ScopedSeqCellBackgroundTest {
    // pid=123, tid=456 -> "  123   456" isn't needed here; only the span BOUNDARIES matter, which
    // this test derives from entry.ts.length rather than hardcoding, so it stays correct if the
    // fixture ever changes.
    private val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "Tag", "hello world", pid = 123, tid = 456)
    private val tsSpanEnd = entry.ts.length
    private val pidFieldStart = entry.ts.length + 2
    // Default pidFieldWidth (5) + " " + 5-digit-padded tid = 11 chars after pidFieldStart.
    private val pidTidSpanEnd = pidFieldStart + 11

    private fun annotation(cellBg: Color?) = buildFullLineAnnotation(
        entry, emptyList(), Color.Gray, Color.Gray, Color.Gray, Color.Gray,
        keywordRegexFilter = null, regexContext = RegexEvaluationContext(),
        cellBg = cellBg,
    )

    @Test
    fun unscopedRowGetsNoBackgroundSpanAtAll() {
        // The default (every pre-existing caller) — must reproduce the pre-feature render, meaning
        // no addStyle/withStyle span anywhere carries a real (non-Unspecified) background.
        val built = annotation(cellBg = null)
        val realBackgrounds = built.spanStyles.filter { it.item.background != Color.Unspecified }
        assertTrue(realBackgrounds.isEmpty(), "unscoped row must carry no background span: $realBackgrounds")
    }

    @Test
    fun scopedRowPaintsTheTsCellBackground() {
        val bg = Color(0xFF8957e5).copy(alpha = 0.22f)
        val built = annotation(cellBg = bg)
        val tsSpan = built.spanStyles.singleOrNull { it.item.background == bg && it.start == 0 }
        assertEquals(0 to tsSpanEnd, tsSpan?.let { it.start to it.end })
    }

    @Test
    fun scopedRowPaintsTheSeparatePidTidCellBackground() {
        val bg = Color(0xFF8957e5).copy(alpha = 0.22f)
        val built = annotation(cellBg = bg)
        val pidTidSpan = built.spanStyles.singleOrNull { it.item.background == bg && it.start == pidFieldStart }
        assertEquals(pidFieldStart to pidTidSpanEnd, pidTidSpan?.let { it.start to it.end })
    }

    @Test
    fun tsAndPidTidBackgroundsAreTwoSeparateSpansNotOneCoveringTheGapBetweenThem() {
        // appendTsPidTid's own doc: two per-field washes, not one spanning the "  " gap — so exactly
        // two spans carry this background, and neither one's range covers the gap itself.
        val bg = Color(0xFF8957e5).copy(alpha = 0.22f)
        val built = annotation(cellBg = bg)
        val spans = built.spanStyles.filter { it.item.background == bg }
        assertEquals(2, spans.size)
        assertTrue(spans.none { it.start < pidFieldStart && it.end > tsSpanEnd })
    }

    @Test
    fun aPidLessRawFallbackRowOnlyPaintsTheTsCellNoPidTidSpanToPaint() {
        // entry.pid <= 0 rows never render a pid/tid field at all (appendTsPidTid's own `if
        // (entry.pid > 0)` guard) — the ts cell background still applies, but there is no second
        // span to paint.
        val rawEntry = LogEntry(2, "10:00:00.000", LogLevel.I, "Tag", "raw fallback line")
        val bg = Color(0xFF8957e5).copy(alpha = 0.22f)
        val built = buildFullLineAnnotation(
            rawEntry, emptyList(), Color.Gray, Color.Gray, Color.Gray, Color.Gray,
            keywordRegexFilter = null, regexContext = RegexEvaluationContext(),
            cellBg = bg,
        )
        val spans = built.spanStyles.filter { it.item.background == bg }
        assertEquals(1, spans.size)
        assertEquals(0 to rawEntry.ts.length, spans.single().let { it.start to it.end })
    }
}
