package com.openlog

import com.openlog.utils.containsPattern
import com.openlog.utils.firstRegexMatch
import com.openlog.utils.regexCacheSizeForTesting
import com.openlog.utils.regexRanges
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextMatchTest {
    // ── containsPattern — plain text ─────────────────────────────────────────

    @Test
    fun plainMatchIsCaseInsensitive() {
        assertTrue(containsPattern("Hello World", "hello", regex = false))
        assertTrue(containsPattern("Hello World", "WORLD", regex = false))
    }

    @Test
    fun plainMatchReturnsFalseWhenAbsent() {
        assertFalse(containsPattern("Hello World", "xyz", regex = false))
    }

    @Test
    fun plainMatchCaseSensitiveWhenRequested() {
        assertFalse(containsPattern("Hello World", "hello", regex = false, ignoreCase = false))
        assertTrue(containsPattern("Hello World", "Hello", regex = false, ignoreCase = false))
    }

    @Test
    fun emptyPatternAlwaysMatchesPlain() {
        // String.contains("") is always true in Kotlin
        assertTrue(containsPattern("Hello", "", regex = false))
        assertTrue(containsPattern("", "", regex = false))
    }

    // ── containsPattern — regex ───────────────────────────────────────────────

    @Test
    fun regexMatchFindsPattern() {
        assertTrue(containsPattern("error code 404", """\d+""", regex = true))
    }

    @Test
    fun regexMatchIsCaseInsensitiveByDefault() {
        assertTrue(containsPattern("Hello World", "hello", regex = true))
        assertTrue(containsPattern("EXCEPTION", "exception", regex = true))
    }

    @Test
    fun regexMatchCaseSensitiveWhenRequested() {
        assertFalse(containsPattern("Hello World", "hello", regex = true, ignoreCase = false))
        assertTrue(containsPattern("Hello World", "Hello", regex = true, ignoreCase = false))
    }

    @Test
    fun invalidRegexReturnsFalseWithoutException() {
        // Unclosed bracket is not a valid regex
        assertFalse(containsPattern("Hello", "[broken", regex = true))
    }

    @Test
    fun emptyPatternAlwaysMatchesRegex() {
        // Regex("") matches everywhere
        assertTrue(containsPattern("Hello", "", regex = true))
    }

    // ── regexRanges ───────────────────────────────────────────────────────────

    @Test
    fun regexRangesReturnsCorrectBoundaries() {
        // "Hello World 123" — "123" starts at index 12, length 3, end exclusive = 15
        val ranges = regexRanges("Hello World 123", """\d+""")
        assertEquals(1, ranges.size)
        assertEquals(12 to 15, ranges.single())
    }

    @Test
    fun regexRangesMultipleMatches() {
        // "abc 123 def 456" — two number groups
        val ranges = regexRanges("abc 123 def 456", """\d+""")
        assertEquals(2, ranges.size)
        assertEquals(4 to 7, ranges[0])
        assertEquals(12 to 15, ranges[1])
    }

    @Test
    fun regexRangesEmptyOnNoMatch() {
        val ranges = regexRanges("no numbers here", """\d+""")
        assertTrue(ranges.isEmpty())
    }

    @Test
    fun invalidRegexRangesReturnsEmptyWithoutException() {
        val ranges = regexRanges("Hello", "[broken")
        assertTrue(ranges.isEmpty())
    }

    @Test
    fun regexRangesIsCaseInsensitiveByDefault() {
        val ranges = regexRanges("Hello World", "hello")
        assertEquals(1, ranges.size)
        assertEquals(0 to 5, ranges.single())
    }

    // ── SEC-2: catastrophic-backtracking regex must not hang the caller ───────

    // A classic ReDoS pattern: against a run of 'a's with no trailing match for "$", a naive
    // backtracking engine explodes exponentially. Without the DeadlineCharSequence guard in
    // TextMatch.kt this call does not return in any reasonable time.
    private val catastrophicPattern = "(a+)+$"
    private val timeoutTestBudgetMs = 2_000L

    private fun catastrophicHaystack() = "a".repeat(40) + "!"

    @Test
    fun catastrophicRegexContainsPatternReturnsFalsePromptlyInsteadOfHanging() {
        val start = System.currentTimeMillis()
        val matched = containsPattern(catastrophicHaystack(), catastrophicPattern, regex = true)
        val elapsed = System.currentTimeMillis() - start
        assertFalse(matched)
        assertTrue(elapsed < timeoutTestBudgetMs, "expected a prompt return, took ${elapsed}ms")
    }

    @Test
    fun catastrophicRegexFirstMatchReturnsNullPromptlyInsteadOfHanging() {
        val start = System.currentTimeMillis()
        val match = firstRegexMatch(catastrophicHaystack(), catastrophicPattern)
        val elapsed = System.currentTimeMillis() - start
        assertNull(match)
        assertTrue(elapsed < timeoutTestBudgetMs, "expected a prompt return, took ${elapsed}ms")
    }

    @Test
    fun catastrophicRegexRangesReturnsEmptyPromptlyInsteadOfHanging() {
        val start = System.currentTimeMillis()
        val ranges = regexRanges(catastrophicHaystack(), catastrophicPattern)
        val elapsed = System.currentTimeMillis() - start
        assertTrue(ranges.isEmpty())
        assertTrue(elapsed < timeoutTestBudgetMs, "expected a prompt return, took ${elapsed}ms")
    }

    @Test
    fun normalRegexPatternsStillMatchCorrectlyAfterTimeoutGuardAdded() {
        // The timeout machinery must be invisible for any legitimate pattern/line combination.
        assertTrue(containsPattern("error code 404", """\d+""", regex = true))
        assertEquals("404", firstRegexMatch("error code 404", """\d+"""))
        assertEquals(listOf(11 to 14), regexRanges("error code 404", """\d+"""))
    }

    // ── SEC-3: bounded regex cache ─────────────────────────────────────────────

    @Test
    fun regexCacheStaysBoundedAfterManyDistinctPatterns() {
        repeat(300) { i -> containsPattern("line $i", "pattern-$i-[0-9]+", regex = true) }
        assertTrue(regexCacheSizeForTesting() <= 256, "cache grew to ${regexCacheSizeForTesting()}, expected <= 256")
    }

    @Test
    fun regexCacheEvictionDoesNotBreakSubsequentMatching() {
        repeat(300) { i -> containsPattern("line $i", "pattern-$i-[0-9]+", regex = true) }
        // A pattern evicted long ago (or never cached) must still compile and match correctly.
        assertTrue(containsPattern("Hello World", "hello", regex = true))
        assertFalse(containsPattern("Hello World", "xyz", regex = true))
    }
}
