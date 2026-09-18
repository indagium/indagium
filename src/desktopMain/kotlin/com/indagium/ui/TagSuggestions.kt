package com.indagium.ui

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

// Lifted out of ui/FilterPanel.kt's own `combinedTagCandidates` remember block (Tags-mode unified
// pkg-prefix/tag search field) so ui/FilterBar.kt (the horizontal filter bar) can share the exact
// same candidate ordering without either duplicating the logic or reaching into FilterPanel.kt's
// private composable state — FilterPanel.kt itself keeps its own inline copy untouched (see that
// file's own combinedTagCandidates remember() for why: it is the one composable in this codebase
// explicitly frozen against edits while the horizontal bar is exploratory).
//
// Result shape mirrors the panel's: pkg-prefix matches first (only when [search] has input), then
// tag matches — each entry paired with whether it's a package prefix (true) or a plain tag (false),
// so a caller can render/apply the two differently (add-prefix vs include/exclude-tag) without a
// second lookup.
fun combinedTagCandidates(
    sortedTags: List<String>,
    search: String,
    packagePrefixes: Set<String>,
    tagUsage: Map<String, Int>,
    mostUsedTagLimit: Int,
): List<Pair<String, Boolean>> {
    val pkgs = packagePrefixCandidates(sortedTags, search, limit = 4).map { it to true }
    val tags = tagCandidates(
        sortedTags = sortedTags,
        search = search,
        selectedTags = emptySet(),
        packagePrefixes = packagePrefixes,
        tagUsage = tagUsage,
        mostUsedLimit = mostUsedTagLimit,
    ).map { it to false }
    return pkgs + tags
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

/** The deepest dotted prefix shared by every tag in [tags], e.g. `com.example.bt` for
 *  `com.example.bt.Adapter` + `com.example.bt.Scanner`. Never returns a prefix equal to one of
 *  [tags] itself — `"A"` + `"A.B"` share the dotted prefix `"A"`, but `"A"` is one of the tags,
 *  not a package above them, so this returns null rather than a name indistinguishable from the
 *  tag it would be proposed for. Fewer than two tags has no "shared" prefix to speak of. */
fun commonPackagePrefix(tags: Set<String>): String? {
    if (tags.size < 2) return null
    val partsByTag = tags.map { it.split('.') }
    val shortest = partsByTag.minOf { it.size }
    var shared = 0
    while (shared < shortest && partsByTag.all { it[shared] == partsByTag[0][shared] }) shared++
    if (shared == 0) return null
    val prefix = partsByTag[0].take(shared).joinToString(".")
    return prefix.takeUnless { it in tags }
}

/**
 * Proposes a display name for a new multi-tag component. The result is **never applied
 * automatically** — it is surfaced as an explicit "use" action for the user to accept, the same
 * convention the rule-suggestion "Add" buttons follow (SeqDiagramDialog.kt: suggestions are never
 * inferred or injected silently).
 *
 * A single tag proposes nothing (its alias is the user's own words, not an inference to second-
 * guess). Otherwise: the last segment of [tags]' [commonPackagePrefix], if they share one; else,
 * only when every one of [tags]' in-range rows came from exactly one pid, that pid's process name
 * (last segment); else null.
 */
fun proposeComponentName(
    tags: Set<String>,
    pidsByTag: Map<String, Set<Int>>,
    processNames: Map<Int, String>,
): String? {
    if (tags.size < 2) return null
    commonPackagePrefix(tags)?.let { prefix -> return prefix.substringAfterLast('.') }
    val pids = tags.flatMapTo(mutableSetOf()) { pidsByTag[it].orEmpty() }
    val onlyPid = pids.singleOrNull() ?: return null
    return processNames[onlyPid]?.substringAfterLast('.')
}
