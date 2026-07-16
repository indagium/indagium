package com.openlog.utils

import com.openlog.model.LogEntry
import java.util.Collections

private data class RegexKey(val pattern: String, val ignoreCase: Boolean)

// (SEC-3) Unbounded ConcurrentHashMap here previously meant every distinct pattern a user or an
// authenticated MCP client (set_filter) ever typed stayed cached for the life of the process — a
// slow but real per-session memory leak on a long-running instance fed a stream of one-off
// patterns. LinkedHashMap in access-order mode + removeEldestEntry gives a plain LRU; wrapping in
// Collections.synchronizedMap keeps every individual get/put call thread-safe against the filter
// hot path calling in from multiple threads, matching the ConcurrentHashMap it replaces. getOrPut
// below (like the ConcurrentHashMap.getOrPut it replaces) is still only get-then-put, not a single
// atomic op — a rare concurrent-miss race recompiles the same pattern twice, which is harmless
// (compiling the same pattern string is deterministic) and was already possible before this change.
private const val REGEX_CACHE_CAPACITY = 256

private val regexCache: MutableMap<RegexKey, Result<Regex>> = Collections.synchronizedMap(
    object : LinkedHashMap<RegexKey, Result<Regex>>(REGEX_CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RegexKey, Result<Regex>>): Boolean =
            size > REGEX_CACHE_CAPACITY
    },
)

/** Test-only peephole into cache bounding — the cache itself stays private, only its size leaks. */
internal fun regexCacheSizeForTesting(): Int = regexCache.size

// (SEC-2) User-authored and MCP-authored (set_filter) regex patterns run against arbitrary log
// text on compute threads (and synchronously on the UI thread on some paths). java.util.regex has
// no built-in step/time budget, so a catastrophic-backtracking pattern like "(a+)+$" against a
// long non-matching line can pin a thread indefinitely — the filter's own cancellation check only
// runs between entries, never inside a single Matcher call. DeadlineCharSequence wraps the real
// haystack and throws once a monotonic-clock budget elapses, checked periodically inside charAt() —
// the exact call java.util.regex.Matcher drives per character it inspects, backtracking included.
// 100ms is generous for any legitimate pattern/line combination in this app (interactive filter
// typing already debounces well above that) while bounding the worst case to "one slow frame,"
// never "the thread never comes back."
private const val REGEX_MATCH_BUDGET_MS = 100L
private const val REGEX_MATCH_BUDGET_NANOS = REGEX_MATCH_BUDGET_MS * 1_000_000L
private const val DEADLINE_CHECK_INTERVAL = 1024
internal const val MAX_REGEX_TIMEOUTS_PER_OPERATION = 3

/**
 * Computation-local containment for user-authored regexes.
 *
 * A pattern that times out is skipped for the rest of this one operation, and after a small
 * number of distinct patterns time out all further regex evaluation is skipped. Instances must
 * stay local to one filter/export/render/tool computation: a new operation gets a new instance
 * and retries patterns that timed out previously.
 */
internal class RegexEvaluationContext(
    // Overridden only by deterministic tests; every production operation uses the 100ms default.
    internal val matchBudgetNanos: Long = REGEX_MATCH_BUDGET_NANOS,
) {
    private val timedOutPatterns = HashSet<RegexKey>()
    private var timeoutCount = 0

    internal val timeoutCountForTesting: Int get() = timeoutCount

    internal val hasTimedOut: Boolean get() = timeoutCount > 0

    private fun canEvaluate(key: RegexKey): Boolean =
        timeoutCount < MAX_REGEX_TIMEOUTS_PER_OPERATION && key !in timedOutPatterns

    private fun recordTimeout(key: RegexKey) {
        if (timedOutPatterns.add(key)) timeoutCount++
    }

    internal fun <T> evaluate(
        pattern: String,
        ignoreCase: Boolean,
        timedOutResult: T,
        block: () -> T,
    ): T {
        val key = RegexKey(pattern, ignoreCase)
        if (!canEvaluate(key)) return timedOutResult
        return try {
            block()
        } catch (ignoredTimeout: RegexTimeoutException) {
            recordTimeout(key)
            timedOutResult
        }
    }
}

private class RegexTimeoutException : RuntimeException() {
    // This is a control-flow signal thrown on a hot path, not a real exceptional condition worth
    // paying for a stack trace capture on every catastrophic-backtracking match attempt.
    override fun fillInStackTrace(): Throwable = this
}

// length must stay exact (the regex engine relies on it for bounds/anchors); only charAt (and, for
// safety, subSequence) does the deadline check. deadlineAtNanos is computed once per top-level
// containsPattern/firstRegexMatch/regexRanges call and shared by every charAt during that one
// match/find/findAll pass — NOT reset per character — so the budget bounds the whole operation.
private class DeadlineCharSequence(
    private val real: CharSequence,
    private val deadlineAtNanos: Long,
) : CharSequence {
    private var calls = 0

    override val length: Int get() = real.length

    override fun get(index: Int): Char {
        calls++
        if (calls % DEADLINE_CHECK_INTERVAL == 0 && System.nanoTime() - deadlineAtNanos >= 0L) {
            throw RegexTimeoutException()
        }
        return real[index]
    }

    // Matcher.group()/MatchResult.value slice a small already-matched span via subSequence, not a
    // new pass over the whole haystack — materializing it directly is simplest and doesn't need
    // its own deadline check.
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = real.subSequence(startIndex, endIndex).toString()
}

private fun deadlineWrap(haystack: String, regexContext: RegexEvaluationContext): CharSequence =
    DeadlineCharSequence(haystack, System.nanoTime() + regexContext.matchBudgetNanos)

// This is the exact text presented by LogViewer. Keyword-regex filtering and its visual
// highlight must use one representation so punctuation at the tag/message boundary cannot make
// a row match without highlighting it (or vice versa).
internal fun visibleLogLineText(entry: LogEntry): String = buildString {
    append(entry.ts)
    if (entry.pid > 0) {
        append("  ")
        append(entry.pid.toString().padStart(5))
        append(" ")
        append(entry.tid.toString().padStart(5))
    }
    append("  ")
    append(entry.level.key)
    append("  ")
    append(entry.tag)
    append(": ")
    append(entry.msg)
}

internal fun containsPattern(
    haystack: String,
    pattern: String,
    regex: Boolean,
    ignoreCase: Boolean = true,
    regexContext: RegexEvaluationContext = RegexEvaluationContext(),
): Boolean {
    if (!regex) return haystack.contains(pattern, ignoreCase = ignoreCase)
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    val compiled = regexCache.getOrPut(RegexKey(pattern, ignoreCase)) {
        runCatching { Regex(pattern, options) }
    }.getOrNull() ?: return false
    return regexContext.evaluate(pattern, ignoreCase, timedOutResult = false) {
        compiled.containsMatchIn(deadlineWrap(haystack, regexContext))
    }
}

// Exactly containsPattern("$tag $msg", ...) without materializing the concatenation — a full-file
// keyword filter pass calls this once per entry, and the throwaway concat strings alone were
// ~2GB of GC churn per keystroke on a 10M-line file. Regex patterns still need the real string
// (a match can't be evaluated piecewise), so only the plain-text path avoids the allocation.
internal fun tagMsgContainsPattern(
    tag: String,
    msg: String,
    pattern: String,
    regex: Boolean,
    ignoreCase: Boolean = true,
    regexContext: RegexEvaluationContext = RegexEvaluationContext(),
): Boolean {
    if (regex) {
        return containsPattern("$tag $msg", pattern, regex = true, ignoreCase = ignoreCase, regexContext = regexContext)
    }
    if (pattern.isEmpty()) return true
    val tagLen = tag.length
    val total = tagLen + 1 + msg.length
    val patLen = pattern.length
    if (patLen > total) return false

    fun charAt(i: Int): Char = when {
        i < tagLen -> tag[i]
        i == tagLen -> ' '
        else -> msg[i - tagLen - 1]
    }

    for (start in 0..(total - patLen)) {
        var k = 0
        while (k < patLen && charAt(start + k).equals(pattern[k], ignoreCase = ignoreCase)) k++
        if (k == patLen) return true
    }
    return false
}

// The substring the pattern actually matched, e.g. for "avc.*denied" against
// "avc: denied : word 1 word 2" this returns "avc: denied" — the useful candidate text,
// as opposed to the whole line or a naive prefix split.
internal fun firstRegexMatch(
    haystack: String,
    pattern: String,
    ignoreCase: Boolean = true,
    regexContext: RegexEvaluationContext = RegexEvaluationContext(),
): String? {
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    val compiled = regexCache.getOrPut(RegexKey(pattern, ignoreCase)) {
        runCatching { Regex(pattern, options) }
    }.getOrNull() ?: return null
    return regexContext.evaluate(pattern, ignoreCase, timedOutResult = null) {
        compiled.find(deadlineWrap(haystack, regexContext))?.value
    }
}

internal fun regexRanges(
    haystack: String,
    pattern: String,
    ignoreCase: Boolean = true,
    regexContext: RegexEvaluationContext = RegexEvaluationContext(),
): List<Pair<Int, Int>> {
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    val compiled = regexCache.getOrPut(RegexKey(pattern, ignoreCase)) {
        runCatching { Regex(pattern, options) }
    }.getOrNull() ?: return emptyList()
    return regexContext.evaluate(pattern, ignoreCase, timedOutResult = emptyList()) {
        compiled.findAll(deadlineWrap(haystack, regexContext))
            .map { it.range.first to it.range.last + 1 }
            .toList()
    }
}
