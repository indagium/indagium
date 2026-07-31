package com.openlog

import androidx.compose.ui.graphics.Color
import com.openlog.model.Highlighter
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.model.ProcessNameMode
import com.openlog.ui.buildFullLineAnnotation
import com.openlog.ui.pidFieldCharWidth
import com.openlog.ui.remapPidFieldRange
import com.openlog.ui.toggledProcessNameMode
import com.openlog.utils.visibleLogLineText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Change 3 (process-names rework): the pid FIELD (name-or-number) now renders inline, in a uniform
// per-tab character width, inside the row's single BasicTextField — see ui/LogViewer.kt's
// appendTsPidTid/buildFullLineAnnotation and remapPidFieldRange's own doc for why that requires
// remapping highlight/search offsets computed against visibleLogLineText's fixed 5-char pid field.
class ProcessNameRenderingTest {
    // ── pidFieldCharWidth: the uniform per-tab pid-field width ─────────────────────────

    @Test
    fun offAlwaysReturnsTheOriginalFiveCharWidthRegardlessOfKnownNames() {
        assertEquals(5, pidFieldCharWidth(ProcessNameMode.OFF, mapOf(1 to "a-very-long-package-name")))
    }

    @Test
    fun allWithNoKnownNamesReturnsTheOriginalFiveCharWidth() {
        assertEquals(5, pidFieldCharWidth(ProcessNameMode.ALL, emptyMap()))
    }

    @Test
    fun allFloorsAtFiveEvenWhenEveryKnownNameIsShorter() {
        assertEquals(5, pidFieldCharWidth(ProcessNameMode.ALL, mapOf(1 to "ab", 2 to "xyz")))
    }

    @Test
    fun allSizesToTheLongestKnownNameWhenBetweenTheFloorAndTheCap() {
        assertEquals(12, pidFieldCharWidth(ProcessNameMode.ALL, mapOf(1 to "short", 2 to "twelve_chars")))
    }

    @Test
    fun allCapsAtProcessNameMaxCharsForAnOutlierLongName() {
        val width = pidFieldCharWidth(ProcessNameMode.ALL, mapOf(1 to "a".repeat(200)))
        assertEquals(20, width)
    }

    @Test
    fun manualSizesTheSameWayAllDoesFromEveryKnownNameNotJustThePicks() {
        // Deliberately NOT filtered by manualProcessNamePicks — see pidFieldCharWidth's own doc for
        // why sizing shouldn't jitter as the user picks/hides individual pids.
        assertEquals(12, pidFieldCharWidth(ProcessNameMode.MANUAL, mapOf(1 to "twelve_chars")))
    }

    // ── toggledProcessNameMode: the toolbar popup's two-state toggle (Change 1) ────────

    @Test
    fun offTogglesToAll() {
        assertEquals(ProcessNameMode.ALL, toggledProcessNameMode(ProcessNameMode.OFF))
    }

    @Test
    fun allTogglesBackToOff() {
        assertEquals(ProcessNameMode.OFF, toggledProcessNameMode(ProcessNameMode.ALL))
    }

    @Test
    fun manualAlsoTogglesBackToOff() {
        assertEquals(ProcessNameMode.OFF, toggledProcessNameMode(ProcessNameMode.MANUAL))
    }

    // ── remapPidFieldRange: the offset math directly ────────────────────────────────────

    @Test
    fun aRangeEntirelyBeforeTheFieldIsUnaffected() {
        assertEquals(2 to 8, remapPidFieldRange(2 to 8, pidFieldStart = 15, pidFieldEndVisible = 20, delta = 5))
    }

    @Test
    fun aRangeTouchingTheFieldStartFromBeforeIsUnaffected() {
        // end == pidFieldStart exactly: no overlap, still "before."
        assertEquals(10 to 15, remapPidFieldRange(10 to 15, pidFieldStart = 15, pidFieldEndVisible = 20, delta = 5))
    }

    @Test
    fun aRangeEntirelyAfterTheFieldShiftsByDeltaKeepingItsWidth() {
        assertEquals(25 to 30, remapPidFieldRange(20 to 25, pidFieldStart = 15, pidFieldEndVisible = 20, delta = 5))
    }

    @Test
    fun aRangeExactlyMatchingTheFieldWidensToTheFullRenderedField() {
        assertEquals(15 to 25, remapPidFieldRange(15 to 20, pidFieldStart = 15, pidFieldEndVisible = 20, delta = 5))
    }

    @Test
    fun aRangeStartingBeforeAndEndingInsideTheFieldExtendsToTheRenderedFieldEnd() {
        assertEquals(10 to 25, remapPidFieldRange(10 to 17, pidFieldStart = 15, pidFieldEndVisible = 20, delta = 5))
    }

    @Test
    fun aRangeStartingInsideAndEndingAfterTheFieldExtendsFromTheFieldStart() {
        assertEquals(15 to 30, remapPidFieldRange(17 to 25, pidFieldStart = 15, pidFieldEndVisible = 20, delta = 5))
    }

    @Test
    fun aRangeEntirelyInsideTheFieldWidensToTheFullRenderedField() {
        assertEquals(15 to 25, remapPidFieldRange(16 to 18, pidFieldStart = 15, pidFieldEndVisible = 20, delta = 5))
    }

    @Test
    fun zeroDeltaIsTheIdentityForRangesThatDoNotOverlapTheField() {
        // delta == 0 is what a pidFieldWidth of 5 always passes (mode OFF, or a tab with no name
        // over 5 chars) — a range entirely before or entirely after the field must come back
        // byte-identical, since neither its position nor its width has anything to shift.
        for (range in listOf(2 to 8, 20 to 25)) {
            assertEquals(range, remapPidFieldRange(range, pidFieldStart = 15, pidFieldEndVisible = 20, delta = 0))
        }
    }

    @Test
    fun zeroDeltaStillWidensAnOverlappingRangeToTheField() {
        // Width unchanged (delta == 0) does NOT mean content is unchanged — a resolved name no
        // wider than 5 chars still replaces the pid's digits at that same width (see
        // buildFullLineAnnotation's own doc). A range overlapping the field is still widened to the
        // field's full span rather than assumed to still point at the original digits.
        assertEquals(15 to 20, remapPidFieldRange(16 to 18, pidFieldStart = 15, pidFieldEndVisible = 20, delta = 0))
    }

    // ── buildFullLineAnnotation integration: highlight ranges before/after/overlapping the pid
    // field, for both a named row and a numeric-fallback row, both under the SAME widened field ──

    private val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "Tag", "hello world message text", pid = 12345, tid = 67890)
    private val lineText = visibleLogLineText(entry)

    // entry.ts is 13 chars ("10:00:00.000"); visibleLogLineText's pid field always sits at
    // [ts.length + 2, ts.length + 7) — see utils/TextMatch.kt:125-133.
    private val pidFieldStart = entry.ts.length + 2
    private val pidFieldEndVisible = pidFieldStart + 5
    private val pidFieldWidth = 10 // delta = 5
    private val pidFieldDelta = pidFieldWidth - 5

    private fun highlightersFor(pattern: String, color: Color) =
        listOf(Highlighter(id = pattern, pattern = pattern, regex = false, color = color, on = true))

    private fun spanFor(annotation: androidx.compose.ui.text.AnnotatedString, color: Color) =
        annotation.spanStyles.singleOrNull { it.item.background == color.copy(alpha = 0.6f) }

    @Test
    fun namedRowRemapsAHighlightRangeBeforeTheFieldToItself() {
        val pattern = entry.ts.substring(0, 4)
        val start = lineText.indexOf(pattern)
        val annotation = buildFullLineAnnotation(
            entry, highlightersFor(pattern, Color.Yellow), Color.Gray, Color.Gray, Color.Gray, Color.Gray,
            keywordRegexFilter = null, regexContext = com.openlog.utils.RegexEvaluationContext(),
            processDisplay = "com.usbmon", pidFieldWidth = pidFieldWidth,
        )
        val span = spanFor(annotation, Color.Yellow)
        assertTrue(start < pidFieldStart, "test pattern must land before the pid field")
        assertEquals(start, span?.start)
        assertEquals(start + pattern.length, span?.end)
    }

    @Test
    fun namedRowRemapsAHighlightRangeAfterTheFieldByTheFullDelta() {
        val pattern = "Tag"
        val start = lineText.indexOf(pattern)
        val annotation = buildFullLineAnnotation(
            entry, highlightersFor(pattern, Color.Yellow), Color.Gray, Color.Gray, Color.Gray, Color.Gray,
            keywordRegexFilter = null, regexContext = com.openlog.utils.RegexEvaluationContext(),
            processDisplay = "com.usbmon", pidFieldWidth = pidFieldWidth,
        )
        val span = spanFor(annotation, Color.Yellow)
        assertTrue(start >= pidFieldEndVisible, "test pattern must land after the pid field")
        assertEquals(start + pidFieldDelta, span?.start)
        assertEquals(start + pattern.length + pidFieldDelta, span?.end)
    }

    @Test
    fun namedRowRemapsAHighlightRangeOverlappingTheFieldToTheFullRenderedFieldPlusTail() {
        // "12345" (pid) then " " then "67890" (tid) are both exactly 5 digits, no padding spaces —
        // "345 678" is a real contiguous substring spanning from inside the pid field, across the
        // single-space separator, into the start of the tid.
        val pattern = "345 678"
        val start = lineText.indexOf(pattern)
        val annotation = buildFullLineAnnotation(
            entry, highlightersFor(pattern, Color.Yellow), Color.Gray, Color.Gray, Color.Gray, Color.Gray,
            keywordRegexFilter = null, regexContext = com.openlog.utils.RegexEvaluationContext(),
            processDisplay = "com.usbmon", pidFieldWidth = pidFieldWidth,
        )
        val span = spanFor(annotation, Color.Yellow)
        assertTrue(start in pidFieldStart until pidFieldEndVisible, "test pattern must start inside the pid field")
        assertEquals(pidFieldStart, span?.start)
        assertEquals(start + pattern.length + pidFieldDelta, span?.end)
    }

    @Test
    fun numericFallbackRowRemapsTheSameThreeRangesIdenticallyToTheNamedRow() {
        // No processDisplay (numeric fallback), but the SAME widened pidFieldWidth a mode-on tab
        // would still apply to a row whose own pid isn't (yet) known — the offset math must not
        // care whether the field ends up showing a name or a padded number.
        val before = entry.ts.substring(0, 4)
        val after = "Tag"
        val overlap = "345 678"
        val colors = Triple(Color.Yellow, Color.Cyan, Color.Magenta)
        val annotation = buildFullLineAnnotation(
            entry,
            highlightersFor(before, colors.first) + highlightersFor(after, colors.second) + highlightersFor(overlap, colors.third),
            Color.Gray, Color.Gray, Color.Gray, Color.Gray,
            keywordRegexFilter = null, regexContext = com.openlog.utils.RegexEvaluationContext(),
            processDisplay = null, pidFieldWidth = pidFieldWidth,
        )
        val beforeStart = lineText.indexOf(before)
        val afterStart = lineText.indexOf(after)
        val overlapStart = lineText.indexOf(overlap)

        assertEquals(beforeStart to beforeStart + before.length, spanFor(annotation, colors.first)?.let { it.start to it.end })
        assertEquals(
            (afterStart + pidFieldDelta) to (afterStart + after.length + pidFieldDelta),
            spanFor(annotation, colors.second)?.let { it.start to it.end },
        )
        assertEquals(
            pidFieldStart to (overlapStart + overlap.length + pidFieldDelta),
            spanFor(annotation, colors.third)?.let { it.start to it.end },
        )
    }

    @Test
    fun namedAndNumericRowsRenderTheSameWidthPidFieldSoEverythingFromTidOnwardLinesUp() {
        val named = buildFullLineAnnotation(
            entry, emptyList(), Color.Gray, Color.Gray, Color.Gray, Color.Gray,
            keywordRegexFilter = null, regexContext = com.openlog.utils.RegexEvaluationContext(),
            processDisplay = "com.usbmon", pidFieldWidth = pidFieldWidth,
        )
        val numeric = buildFullLineAnnotation(
            entry, emptyList(), Color.Gray, Color.Gray, Color.Gray, Color.Gray,
            keywordRegexFilter = null, regexContext = com.openlog.utils.RegexEvaluationContext(),
            processDisplay = null, pidFieldWidth = pidFieldWidth,
        )
        assertEquals(named.text.length, numeric.text.length)
        // Same length AND identical from the pid field's rendered end onward (TID/LVL/TAG/MESSAGE) —
        // only the pid field itself (name vs padded number) may differ.
        val fieldEndRendered = pidFieldStart + pidFieldWidth
        assertEquals(named.text.substring(fieldEndRendered), numeric.text.substring(fieldEndRendered))
    }

    @Test
    fun offModeProducesExactlyThePreFeatureVisibleLineTextByteForByte() {
        val annotation = buildFullLineAnnotation(
            entry, emptyList(), Color.Gray, Color.Gray, Color.Gray, Color.Gray,
            keywordRegexFilter = null, regexContext = com.openlog.utils.RegexEvaluationContext(),
        )
        assertEquals(visibleLogLineText(entry), annotation.text)
    }

    @Test
    fun offModeStillProducesThePreFeatureTextForAPidLessRawFallbackRow() {
        val rawEntry = LogEntry(2, "", LogLevel.I, "Tag", "raw fallback line")
        val annotation = buildFullLineAnnotation(
            rawEntry, emptyList(), Color.Gray, Color.Gray, Color.Gray, Color.Gray,
            keywordRegexFilter = null, regexContext = com.openlog.utils.RegexEvaluationContext(),
        )
        assertEquals(visibleLogLineText(rawEntry), annotation.text)
    }
}
