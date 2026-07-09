package com.openlog.utils

import com.openlog.model.LogEntry
import java.util.concurrent.ConcurrentHashMap

private data class RegexKey(val pattern: String, val ignoreCase: Boolean)

private val regexCache = ConcurrentHashMap<RegexKey, Result<Regex>>()

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
): Boolean {
    if (!regex) return haystack.contains(pattern, ignoreCase = ignoreCase)
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    val compiled = regexCache.getOrPut(RegexKey(pattern, ignoreCase)) {
        runCatching { Regex(pattern, options) }
    }.getOrNull() ?: return false
    return compiled.containsMatchIn(haystack)
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
): Boolean {
    if (regex) return containsPattern("$tag $msg", pattern, regex = true, ignoreCase = ignoreCase)
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
): String? {
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    val compiled = regexCache.getOrPut(RegexKey(pattern, ignoreCase)) {
        runCatching { Regex(pattern, options) }
    }.getOrNull() ?: return null
    return compiled.find(haystack)?.value
}

internal fun regexRanges(
    haystack: String,
    pattern: String,
    ignoreCase: Boolean = true,
): List<Pair<Int, Int>> {
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    val compiled = regexCache.getOrPut(RegexKey(pattern, ignoreCase)) {
        runCatching { Regex(pattern, options) }
    }.getOrNull() ?: return emptyList()
    return compiled.findAll(haystack)
        .map { it.range.first to it.range.last + 1 }
        .toList()
}
