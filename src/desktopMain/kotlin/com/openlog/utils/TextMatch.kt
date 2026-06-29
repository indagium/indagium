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
