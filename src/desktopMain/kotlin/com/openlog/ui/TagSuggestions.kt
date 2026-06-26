package com.openlog.ui

fun tagCandidates(
    sortedTags: List<String>,
    search: String,
    selectedTags: Set<String>,
    packagePrefixes: Set<String>,
    tagUsage: Map<String, Int>,
    excludeTags: Set<String> = emptySet(),
    mostUsedLimit: Int = 5,
    searchLimit: Int = 50,
): List<String> {
    val rank = sortedTags.withIndex().associate { it.value to it.index }
    fun ordered(tags: List<String>) =
        tags.sortedWith(compareByDescending<String> { tagUsage[it] ?: 0 }.thenBy { rank[it] ?: Int.MAX_VALUE })
    fun available(tags: List<String>) =
        tags.filter { it !in selectedTags && it !in excludeTags }

    val base = when {
        search.isNotBlank() -> available(sortedTags.filter { it.contains(search, ignoreCase = true) })
        packagePrefixes.isNotEmpty() -> ordered(available(sortedTags.filter { tagMatchesAnyPrefix(it, packagePrefixes) }))
            .take(mostUsedLimit.coerceAtLeast(0))
        else -> ordered(available(sortedTags.filter { (tagUsage[it] ?: 0) > 0 }))
            .take(mostUsedLimit.coerceAtLeast(0))
    }

    val limit = if (search.isBlank()) mostUsedLimit else searchLimit
    return base
        .distinct()
        .take(limit.coerceAtLeast(0))
}

fun packagePrefixCandidates(sortedTags: List<String>, search: String, limit: Int = 8): List<String> {
    if (search.isBlank()) return emptyList()
    val needle = search.trim()
    return sortedTags
        .flatMap { packagePrefixesContaining(it, needle) }
        .distinct()
        .take(limit.coerceAtLeast(0))
}

fun displayTagForPrefix(tag: String, packagePrefixes: Set<String>): Pair<String, String?> {
    val prefix = matchingPrefix(tag, packagePrefixes) ?: return tag to null
    val suffix = tag.removePrefix(prefix).removePrefix(".").ifBlank { tag }
    return suffix to prefix
}

private fun tagMatchesAnyPrefix(tag: String, packagePrefixes: Set<String>) =
    matchingPrefix(tag, packagePrefixes) != null

private fun matchingPrefix(tag: String, packagePrefixes: Set<String>): String? =
    packagePrefixes
        .filter { prefix -> tag == prefix || tag.startsWith("$prefix.") }
        .maxByOrNull { it.length }

private fun packagePrefixesContaining(tag: String, search: String): List<String> {
    val parts = tag.split('.')
    if (parts.size < 2) return emptyList()
    return (1 until parts.size)
        .map { parts.take(it).joinToString(".") }
        .filter { it.contains(search, ignoreCase = true) }
}
