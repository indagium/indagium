package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.HighlightTarget
import com.indagium.model.Highlighter
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.highlighterMatches
import com.indagium.utils.regexHighlightRanges
import com.indagium.utils.resolveLineHighlight
import com.indagium.utils.visibleLogLineText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HighlightMatchTest {
    private val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "NetTag", "Request done Request", pid = 123, tid = 456)
    private val lineText = visibleLogLineText(entry)
    private val ctx get() = RegexEvaluationContext()

    private fun hl(
        id: String,
        pattern: String,
        regex: Boolean = false,
        wholeLine: Boolean = false,
        target: HighlightTarget = HighlightTarget.ANY,
        tag: String? = null,
        caseSensitive: Boolean = false,
        textColor: Color? = null,
        groupsOnly: Boolean = false,
        on: Boolean = true,
    ) = Highlighter(
        id, pattern, regex, Color.Yellow, on,
        wholeLine = wholeLine, target = target, tag = tag, caseSensitive = caseSensitive,
        textColor = textColor, captureGroupsOnly = groupsOnly,
    )

    private fun spans(vararg hls: Highlighter, e: LogEntry = entry) =
        resolveLineHighlight(e, visibleLogLineText(e), hls.toList(), ctx)

    // ── case ──

    @Test
    fun plainTextIsCaseInsensitiveByDefaultAndCaseSensitiveOnRequest() {
        val insensitive = spans(hl("a", "request"))
        assertEquals(2, insensitive.spans.size)
        val sensitive = spans(hl("a", "request", caseSensitive = true))
        assertTrue(sensitive.spans.isEmpty())
        assertEquals(2, spans(hl("a", "Request", caseSensitive = true)).spans.size)
    }

    @Test
    fun regexHonoursCaseSensitivity() {
        assertEquals(2, spans(hl("a", "req\\w+", regex = true)).spans.size)
        assertTrue(spans(hl("a", "req\\w+", regex = true, caseSensitive = true)).spans.isEmpty())
    }

    @Test
    fun matchesCheckHonoursCase() {
        assertTrue(highlighterMatches(hl("a", "REQUEST"), entry, lineText, ctx))
        assertFalse(highlighterMatches(hl("a", "REQUEST", caseSensitive = true), entry, lineText, ctx))
    }

    @Test
    fun disabledOrBlankHighlightersNeverMatch() {
        assertFalse(highlighterMatches(hl("a", "Request", on = false), entry, lineText, ctx))
        assertFalse(highlighterMatches(hl("a", " "), entry, lineText, ctx))
        assertTrue(spans(hl("a", "Request", on = false)).spans.isEmpty())
    }

    // ── target / scope ──

    @Test
    fun anyTargetMatchesAcrossTheWholeRenderedLine() {
        val result = spans(hl("a", "NetTag: Request"))
        val span = result.spans.single()
        assertEquals(lineText.indexOf("NetTag: Request"), span.start)
        assertEquals(span.start + "NetTag: Request".length, span.end)
    }

    @Test
    fun messageTargetShiftsOffsetsIntoLineCoordinates() {
        val result = spans(hl("a", "Request", target = HighlightTarget.MESSAGE))
        assertEquals(2, result.spans.size)
        result.spans.forEach { assertEquals("Request", lineText.substring(it.start, it.end)) }
        // The tag text "NetTag" is not part of the message, so a message-only "Tag" never matches it.
        assertTrue(spans(hl("a", "Tag", target = HighlightTarget.MESSAGE)).spans.isEmpty())
        // ...and the first hit is the one in the message, not an earlier one elsewhere on the line.
        assertEquals(lineText.indexOf("Request"), result.spans.first().start)
    }

    @Test
    fun tagTargetOnlyMatchesTheTagAndShiftsOffsets() {
        val result = spans(hl("a", "Tag", target = HighlightTarget.TAG))
        val span = result.spans.single()
        assertEquals("Tag", lineText.substring(span.start, span.end))
        assertEquals(lineText.indexOf("NetTag") + 3, span.start)
        assertTrue(spans(hl("a", "Request", target = HighlightTarget.TAG)).spans.isEmpty())
    }

    @Test
    fun targetOffsetsWorkForRowsWithoutAPid() {
        val noPid = LogEntry(2, "10:00:00.001", LogLevel.D, "T", "hello")
        val text = visibleLogLineText(noPid)
        val tagSpan = spans(hl("a", "T", target = HighlightTarget.TAG), e = noPid).spans.single()
        val msgSpan = spans(hl("a", "hello", target = HighlightTarget.MESSAGE), e = noPid).spans.single()
        assertEquals("T", text.substring(tagSpan.start, tagSpan.end))
        assertEquals("hello", text.substring(msgSpan.start, msgSpan.end))
    }

    @Test
    fun tagLimitSkipsOtherTags() {
        val limited = hl("a", "Request", target = HighlightTarget.MESSAGE, tag = "OtherTag")
        assertTrue(spans(limited).spans.isEmpty())
        assertFalse(highlighterMatches(limited, entry, lineText, ctx))
        val matching = hl("a", "Request", target = HighlightTarget.MESSAGE, tag = "NetTag")
        assertEquals(2, spans(matching).spans.size)
        // A blank tag means "no limit".
        assertEquals(2, spans(hl("a", "Request", tag = "  ")).spans.size)
    }

    // ── capture groups ──

    @Test
    fun groupsOnlyEmitsTheCapturedGroups() {
        val ranges = regexHighlightRanges("id=42 name=bob", "id=(\\d+) name=(\\w+)", ignoreCase = true, groupsOnly = true)
        assertEquals(listOf(3 to 5, 11 to 14), ranges)
    }

    @Test
    fun groupsOnlySkipsAGroupThatDidNotTakePart() {
        // The second alternative never participates: only group 1 is emitted.
        val ranges = regexHighlightRanges("key=abc", "key=(abc)|other=(xyz)", ignoreCase = true, groupsOnly = true)
        assertEquals(listOf(4 to 7), ranges)
        // A middle group that did not participate is skipped, the later one still counts.
        val middle = regexHighlightRanges("a-c", "(a)(b)?-(c)", ignoreCase = true, groupsOnly = true)
        assertEquals(listOf(0 to 1, 2 to 3), middle)
    }

    @Test
    fun groupsOnlyWithoutGroupsEmitsTheWholeMatch() {
        assertEquals(listOf(0 to 3), regexHighlightRanges("abc def", "abc", ignoreCase = true, groupsOnly = true))
    }

    @Test
    fun withoutGroupsOnlyTheWholeMatchIsEmittedEvenWhenGroupsExist() {
        assertEquals(listOf(0 to 7), regexHighlightRanges("id=abcd", "id=(\\w+)", ignoreCase = true, groupsOnly = false))
    }

    @Test
    fun groupsOnlyPaintsNothingWhenNoGroupTookPart() {
        // klogg has no whole-match fallback: `foo` matches, but no group captured.
        assertTrue(regexHighlightRanges("foo", "foo(bar)?", ignoreCase = true, groupsOnly = true).isEmpty())
    }

    @Test
    fun kloggWholeLineWithGroupsNeedsACapturedGroupToOwnTheRow() {
        val e = LogEntry(4, "10:00:00.003", LogLevel.I, "T", "foo only")
        val rule = hl("k", "foo(bar)?", regex = true, wholeLine = true, textColor = Color.Black, groupsOnly = true)
        assertNull(spans(rule, e = e).wholeLine)
        assertFalse(highlighterMatches(rule, e, visibleLogLineText(e), ctx))
        val hit = LogEntry(5, "10:00:00.004", LogLevel.I, "T", "foobar")
        assertEquals(rule, spans(rule, e = hit).wholeLine)
    }

    @Test
    fun groupsOnlyDropsEmptyGroups() {
        assertEquals(listOf(0 to 2), regexHighlightRanges("ab", "(ab)()", ignoreCase = true, groupsOnly = true))
    }

    @Test
    fun captureGroupsOnlyFlowsThroughResolveLineHighlight() {
        val e = LogEntry(3, "10:00:00.002", LogLevel.I, "T", "id=42 done")
        val result = spans(hl("a", "id=(\\d+)", regex = true, groupsOnly = true), e = e)
        val span = result.spans.single()
        assertEquals("42", visibleLogLineText(e).substring(span.start, span.end))
    }

    // ── whole-line ordering ──

    @Test
    fun theFirstMatchingWholeLineHighlighterWins() {
        val first = hl("first", "Request", wholeLine = true)
        val second = hl("second", "done", wholeLine = true)
        assertEquals("first", spans(first, second).wholeLine?.id)
        assertEquals("second", spans(second, first).wholeLine?.id)
    }

    @Test
    fun aNonMatchingWholeLineHighlighterDoesNotWin() {
        val miss = hl("miss", "nothing here", wholeLine = true)
        val hit = hl("hit", "done", wholeLine = true)
        assertEquals("hit", spans(miss, hit).wholeLine?.id)
        assertNull(spans(miss).wholeLine)
    }

    @Test
    fun wholeLineHighlightersContributeNoSpans() {
        assertTrue(spans(hl("a", "Request", wholeLine = true)).spans.isEmpty())
    }

    @Test
    fun kloggWinnerDropsTheSpansOfMatchHighlightersBelowIt() {
        val above = hl("above", "Request")
        val winner = hl("winner", "done", wholeLine = true, textColor = Color.Black)
        val below = hl("below", "Net")
        val result = spans(above, winner, below)
        assertEquals("winner", result.wholeLine?.id)
        assertEquals(setOf("above"), result.spans.map { it.hl.id }.toSet())
    }

    @Test
    fun indagiumWinnerKeepsEveryMatchSpan() {
        val above = hl("above", "Request")
        val winner = hl("winner", "done", wholeLine = true)
        val below = hl("below", "Net")
        val result = spans(above, winner, below)
        assertEquals("winner", result.wholeLine?.id)
        assertEquals(setOf("above", "below"), result.spans.map { it.hl.id }.toSet())
    }

    @Test
    fun indagiumSpansKeepListPaintOrderAndKloggSpansPaintFirstOnTop() {
        val a = hl("a", "Request")
        val b = hl("b", "Request")
        assertEquals(listOf("a", "a", "b", "b"), spans(a, b).spans.map { it.hl.id })
        val k1 = hl("k1", "Request", textColor = Color.Black)
        val k2 = hl("k2", "Request", textColor = Color.Black)
        // Later spans paint on top, so klogg's first-in-list highlighter must come last.
        assertEquals(listOf("k2", "k2", "k1", "k1"), spans(k1, k2).spans.map { it.hl.id })
    }

    // ── plain-text scan ──

    @Test
    fun indagiumPlainScanOverlapsButKloggPlainScanDoesNot() {
        val e = LogEntry(4, "10:00:00.003", LogLevel.I, "T", "aaaa")
        assertEquals(3, spans(hl("a", "aa", target = HighlightTarget.MESSAGE), e = e).spans.size)
        assertEquals(
            2,
            spans(hl("a", "aa", target = HighlightTarget.MESSAGE, textColor = Color.Black), e = e).spans.size,
        )
    }

    @Test
    fun invalidRegexNeverMatches() {
        assertTrue(spans(hl("a", "[", regex = true)).spans.isEmpty())
        assertFalse(highlighterMatches(hl("a", "[", regex = true), entry, lineText, ctx))
    }
}
