package com.openlog

import androidx.compose.ui.graphics.Color
import com.openlog.model.LogEntry
import com.openlog.model.LogItem
import com.openlog.model.LogLevel
import com.openlog.utils.RegexEvaluationContext
import com.openlog.utils.computeSearchMatches
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogSearchTest {
    private fun row(id: Int, tag: String = "App", msg: String, level: LogLevel = LogLevel.I): LogItem.Row =
        LogItem.Row(LogEntry(id, "10:00:00.$id", level, tag, msg), indent = 0)

    private fun header(id: Int, tag: String = "App", msg: String): LogItem.SeqHeader =
        LogItem.SeqHeader(
            entry = LogEntry(id, "10:00:00.$id", LogLevel.I, tag, msg),
            gid = "sg_$id",
            indent = 0,
            expanded = false,
            count = 3,
            color = Color.Red,
        )

    // ── literal / regex matching ───────────────────────────────────────────

    @Test
    fun literalQueryMatchesSubstringAcrossRows() {
        val items = listOf(
            row(1, msg = "connection established"),
            row(2, msg = "connection lost"),
            row(3, msg = "unrelated line"),
        )
        val result = computeSearchMatches(items, "connection", caseSensitive = false, RegexEvaluationContext())
        assertContentEquals(intArrayOf(1, 2), result.matchIds)
        assertFalse(result.invalidPattern)
        assertFalse(result.timedOut)
    }

    @Test
    fun regexQueryMatchesPattern() {
        val items = listOf(
            row(1, msg = "error code 404"),
            row(2, msg = "error code 500"),
            row(3, msg = "all good"),
        )
        val result = computeSearchMatches(items, """code \d+""", caseSensitive = false, RegexEvaluationContext())
        assertContentEquals(intArrayOf(1, 2), result.matchIds)
    }

    // ── case sensitivity ────────────────────────────────────────────────────

    @Test
    fun caseInsensitiveByDefault() {
        val items = listOf(row(1, msg = "Boom happened"))
        val result = computeSearchMatches(items, "boom", caseSensitive = false, RegexEvaluationContext())
        assertContentEquals(intArrayOf(1), result.matchIds)
    }

    @Test
    fun caseSensitiveWhenRequested() {
        val items = listOf(row(1, msg = "Boom happened"))
        val insensitive = computeSearchMatches(items, "boom", caseSensitive = true, RegexEvaluationContext())
        assertTrue(insensitive.matchIds.isEmpty())

        val sensitive = computeSearchMatches(items, "Boom", caseSensitive = true, RegexEvaluationContext())
        assertContentEquals(intArrayOf(1), sensitive.matchIds)
    }

    // ── invalid pattern ─────────────────────────────────────────────────────

    @Test
    fun invalidPatternSetsInvalidFlagAndReportsNoMatches() {
        val items = listOf(row(1, msg = "anything"))
        val result = computeSearchMatches(items, "[unterminated", caseSensitive = false, RegexEvaluationContext())
        assertTrue(result.invalidPattern)
        assertTrue(result.matchIds.isEmpty())
    }

    // ── no matches / empty query ────────────────────────────────────────────

    @Test
    fun noMatchesReturnsEmptyWithoutInvalidFlag() {
        val items = listOf(row(1, msg = "hello"), row(2, msg = "world"))
        val result = computeSearchMatches(items, "xyz-not-present", caseSensitive = false, RegexEvaluationContext())
        assertTrue(result.matchIds.isEmpty())
        assertFalse(result.invalidPattern)
    }

    @Test
    fun emptyQueryReturnsEmptyWithoutInvalidFlag() {
        val items = listOf(row(1, msg = "hello"))
        val result = computeSearchMatches(items, "", caseSensitive = false, RegexEvaluationContext())
        assertTrue(result.matchIds.isEmpty())
        assertFalse(result.invalidPattern)
    }

    // ── header rows ──────────────────────────────────────────────────────────

    @Test
    fun matchesOnHeaderRowsAreFound() {
        val items = listOf(
            header(1, msg = "sequence start needle"),
            row(2, msg = "inside the group, no match here"),
            row(3, msg = "also nothing"),
        )
        val result = computeSearchMatches(items, "needle", caseSensitive = false, RegexEvaluationContext())
        assertContentEquals(intArrayOf(1), result.matchIds)
    }

    @Test
    fun matchesSpanBothPlainRowsAndHeaderRows() {
        val items = listOf(
            header(1, msg = "group needle one"),
            row(2, msg = "unrelated"),
            row(3, msg = "needle two here"),
        )
        val result = computeSearchMatches(items, "needle", caseSensitive = false, RegexEvaluationContext())
        assertContentEquals(intArrayOf(1, 3), result.matchIds)
    }

    // ── SEC-2: catastrophic-backtracking pattern must degrade via the budget ─

    @Test
    fun catastrophicPatternDegradesViaBudgetInsteadOfHanging() {
        // Same classic ReDoS pattern/haystack shape as TextMatchTest's catastrophic-regex suite.
        // A microscopic 1ns budget (matching that suite's own deterministic-timeout tests) is what
        // makes the timeout itself deterministic here — the real 100ms production default is
        // "prompt" for this haystack size without necessarily tripping the deadline at all, which
        // is exactly what TextMatchTest's own default-budget catastrophic tests rely on (they only
        // assert elapsed time and empty results, never regexContext.hasTimedOut).
        val haystack = "a".repeat(40) + "!"
        val items = (1..5).map { id -> row(id, msg = haystack) }
        val ctx = RegexEvaluationContext(matchBudgetNanos = 1L)

        val start = System.currentTimeMillis()
        val result = computeSearchMatches(items, "(a+)+$", caseSensitive = false, ctx)
        val elapsed = System.currentTimeMillis() - start

        assertTrue(elapsed < 2_000L, "expected a prompt return, took ${elapsed}ms")
        assertTrue(result.matchIds.isEmpty())
        assertTrue(result.timedOut)
    }

    @Test
    fun equalsAndHashCodeCompareContentNotArrayIdentity() {
        val items = listOf(row(1, msg = "needle"))
        val a = computeSearchMatches(items, "needle", caseSensitive = false, RegexEvaluationContext())
        val b = computeSearchMatches(items, "needle", caseSensitive = false, RegexEvaluationContext())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
