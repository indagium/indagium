package com.openlog

import androidx.compose.ui.graphics.Color
import com.openlog.model.Highlighter
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.model.ProcessNameMode
import com.openlog.ui.PROCESS_NAME_MAX_CHARS
import com.openlog.ui.buildFullLineAnnotation
import com.openlog.ui.pidFieldCharWidth
import com.openlog.ui.pointerInsidePidFieldX
import com.openlog.ui.processNamesVisible
import com.openlog.ui.remapPidFieldRange
import com.openlog.ui.shouldShowProcessNamePopup
import com.openlog.ui.toggledProcessNameMode
import com.openlog.utils.visibleLogLineText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Change 3 (process-names rework): the pid FIELD (name-or-number) now renders inline, in a uniform
// per-tab character width, inside the row's single BasicTextField — see ui/LogViewer.kt's
// appendTsPidTid/buildFullLineAnnotation and remapPidFieldRange's own doc for why that requires
// remapping highlight/search offsets computed against visibleLogLineText's fixed 5-char pid field.
class ProcessNameRenderingTest {
    // ── pidFieldCharWidth: the uniform per-tab pid-field width ─────────────────────────

    @Test
    fun offAlwaysReturnsTheOriginalFiveCharWidthRegardlessOfKnownNames() {
        assertEquals(5, pidFieldCharWidth(ProcessNameMode.OFF, mapOf(1 to "a-very-long-package-name"), emptySet()))
    }

    @Test
    fun allWithNoKnownNamesReturnsTheOriginalFiveCharWidth() {
        assertEquals(5, pidFieldCharWidth(ProcessNameMode.ALL, emptyMap(), emptySet()))
    }

    @Test
    fun allFloorsAtFiveEvenWhenEveryKnownNameIsShorter() {
        assertEquals(5, pidFieldCharWidth(ProcessNameMode.ALL, mapOf(1 to "ab", 2 to "xyz"), emptySet()))
    }

    @Test
    fun allSizesToTheLongestKnownNameWhenBetweenTheFloorAndTheCap() {
        assertEquals(12, pidFieldCharWidth(ProcessNameMode.ALL, mapOf(1 to "short", 2 to "twelve_chars"), emptySet()))
    }

    @Test
    fun allCapsAtProcessNameMaxCharsForAnOutlierLongName() {
        // Asserted against the constant, not a literal: the cap is a tuning value (raised once
        // already, when 20 was eliding names as ordinary as "com.usbmonitor.fixture"), and pinning
        // the number here only makes the test fail on the next tune without catching a real defect.
        val width = pidFieldCharWidth(ProcessNameMode.ALL, mapOf(1 to "a".repeat(200)), emptySet())
        assertEquals(PROCESS_NAME_MAX_CHARS, width)
    }

    @Test
    fun theCapAppliesEvenToOrdinaryPackageNamesLongerThanIt() {
        // The column stays narrow on purpose (Theme.kt's own doc on PROCESS_NAME_MAX_CHARS) — a
        // name this shape and length is unremarkable but still gets middle-ellipsised in the field;
        // LogRow's hover popup (shouldShowProcessNamePopup), not a wider column, is what reveals it.
        val name = "com.usbmonitor.fixture"
        assertTrue(name.length > PROCESS_NAME_MAX_CHARS, "test name must actually exceed the cap")
        assertEquals(PROCESS_NAME_MAX_CHARS, pidFieldCharWidth(ProcessNameMode.ALL, mapOf(1 to name), emptySet()))
    }

    // ALL is unaffected by manualPicks — every known name is a candidate regardless of picks, since
    // ALL can show any of them at any time (unlike MANUAL below).
    @Test
    fun allIgnoresManualPicksEntirely() {
        assertEquals(12, pidFieldCharWidth(ProcessNameMode.ALL, mapOf(1 to "twelve_chars", 2 to "x"), setOf(2)))
    }

    @Test
    fun manualWithNoPicksReturnsTheOriginalFiveCharWidthEvenWithKnownNames() {
        // The state every restart lands in, since picks are session-only (LogTab.manualProcessNamePicks'
        // own doc) — must not reserve column space for a name no row is actually rendering.
        assertEquals(5, pidFieldCharWidth(ProcessNameMode.MANUAL, mapOf(1 to "twelve_chars"), emptySet()))
    }

    @Test
    fun manualWithOnePickSizesToThatPicksNameOnly() {
        assertEquals(12, pidFieldCharWidth(ProcessNameMode.MANUAL, mapOf(1 to "twelve_chars"), setOf(1)))
    }

    @Test
    fun manualSizesToTheLongestPickedNameNotTheLongestKnownName() {
        // pid 2's name is known but never picked, and is the LONGER of the two — it must not widen
        // the column; only pid 1 (picked) counts.
        val names = mapOf(1 to "short", 2 to "a-much-longer-name-than-short")
        assertEquals(5, pidFieldCharWidth(ProcessNameMode.MANUAL, names, setOf(1)))
    }

    @Test
    fun manualSizesToTheLongestAmongSeveralPicksOfDifferingLengths() {
        // pid 4 is known and the longest of all four, but never picked — the width must come from
        // the longest AMONG THE PICKS (pid 3, 12 chars), not the longest known overall.
        val names = mapOf(1 to "ab", 2 to "eight_ch", 3 to "twelve_chars", 4 to "an-unpicked-name-longer-than-every-pick")
        assertEquals(12, pidFieldCharWidth(ProcessNameMode.MANUAL, names, setOf(1, 2, 3)))
    }

    // ── toggledProcessNameMode: the toolbar popup's two-state toggle (Change 1) ────────

    @Test
    fun offTogglesToAll() {
        assertEquals(ProcessNameMode.ALL, toggledProcessNameMode(ProcessNameMode.OFF, emptySet()))
    }

    @Test
    fun allTogglesBackToOff() {
        assertEquals(ProcessNameMode.OFF, toggledProcessNameMode(ProcessNameMode.ALL, emptySet()))
    }

    @Test
    fun manualWithPicksTogglesBackToOff() {
        assertEquals(ProcessNameMode.OFF, toggledProcessNameMode(ProcessNameMode.MANUAL, setOf(1234)))
    }

    @Test
    fun manualWithNoPicksTogglesToAllBecauseNothingIsOnScreenToHide() {
        // Reported from the UI: show one process from a row menu, hide it again, then open the
        // toolbar popup — it offered "Hide process names" with no name anywhere on screen. MANUAL
        // with an empty pick set renders nothing, so the only sensible action is to show.
        assertEquals(ProcessNameMode.ALL, toggledProcessNameMode(ProcessNameMode.MANUAL, emptySet()))
    }

    @Test
    fun processNamesVisibleTracksWhatIsActuallyRenderedNotJustTheMode() {
        assertFalse(processNamesVisible(ProcessNameMode.OFF, setOf(1234)))
        assertTrue(processNamesVisible(ProcessNameMode.ALL, emptySet()))
        assertTrue(processNamesVisible(ProcessNameMode.MANUAL, setOf(1234)))
        assertFalse(processNamesVisible(ProcessNameMode.MANUAL, emptySet()))
    }

    // ── shouldShowProcessNamePopup: whether LogRow's hover popup has anything to reveal ──────

    @Test
    fun popupNeverShowsWhenNoNameIsDisplayed() {
        assertFalse(shouldShowProcessNamePopup(null, 20))
    }

    @Test
    fun popupDoesNotShowWhenTheNameAlreadyFitsTheField() {
        assertFalse(shouldShowProcessNamePopup("short", 20))
    }

    @Test
    fun popupDoesNotShowWhenTheNameExactlyFillsTheFieldWithNoRoomLeft() {
        // Exactly at the width is NOT elided — middleEllipsis (LogViewer.kt) is a no-op at
        // text.length == maxChars, so there is nothing hidden for the popup to reveal.
        assertFalse(shouldShowProcessNamePopup("twelve_chars", 12))
    }

    @Test
    fun popupShowsWhenTheNameIsWiderThanTheFieldAndWasActuallyElided() {
        assertTrue(shouldShowProcessNamePopup("a-name-longer-than-the-field", 12))
    }

    // ── pointerInsidePidFieldX: pointer-in-field hit testing for the hover popup ─────────────

    @Test
    fun pointerBeforeTheFieldIsOutside() {
        assertFalse(pointerInsidePidFieldX(9f, fieldStartX = 10f, fieldEndX = 50f))
    }

    @Test
    fun pointerAfterTheFieldIsOutside() {
        assertFalse(pointerInsidePidFieldX(51f, fieldStartX = 10f, fieldEndX = 50f))
    }

    @Test
    fun pointerAtEitherEdgeIsInside() {
        assertTrue(pointerInsidePidFieldX(10f, fieldStartX = 10f, fieldEndX = 50f))
        assertTrue(pointerInsidePidFieldX(50f, fieldStartX = 10f, fieldEndX = 50f))
    }

    @Test
    fun pointerInsideTheFieldIsInside() {
        assertTrue(pointerInsidePidFieldX(30f, fieldStartX = 10f, fieldEndX = 50f))
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

    // MANUAL with an empty pick set is the state every restart lands in (picks are session-only —
    // LogTab.manualProcessNamePicks' own doc). It must render byte-identically to OFF: no row has a
    // name to show (resolveProcessDisplayName), and the field reserves no extra width for one
    // (pidFieldCharWidth) — same as the OFF test directly above, but driven through both of those
    // pure functions with a known name present in processNames, to prove the empty PICK set (not an
    // empty processNames map) is what collapses this back to the pre-feature render.
    @Test
    fun manualModeWithNoPicksProducesExactlyThePreFeatureVisibleLineTextByteForByte() {
        val processNames = mapOf(entry.pid to "com.usbmon")
        val pidFieldWidth = pidFieldCharWidth(ProcessNameMode.MANUAL, processNames, emptySet())
        val processDisplay = com.openlog.utils.resolveProcessDisplayName(
            ProcessNameMode.MANUAL, processNames, emptySet(), entry.pid,
        )
        assertEquals(5, pidFieldWidth)
        assertEquals(null, processDisplay)
        val annotation = buildFullLineAnnotation(
            entry, emptyList(), Color.Gray, Color.Gray, Color.Gray, Color.Gray,
            keywordRegexFilter = null, regexContext = com.openlog.utils.RegexEvaluationContext(),
            processDisplay = processDisplay, pidFieldWidth = pidFieldWidth,
        )
        assertEquals(visibleLogLineText(entry), annotation.text)
    }
}
