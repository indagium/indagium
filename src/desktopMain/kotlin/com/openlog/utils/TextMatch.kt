package com.openlog.utils

import java.util.concurrent.ConcurrentHashMap

private data class RegexKey(val pattern: String, val ignoreCase: Boolean)

private val regexCache = ConcurrentHashMap<RegexKey, Result<Regex>>()

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
