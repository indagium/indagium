package com.indagium.ui

import com.indagium.model.HighlightTarget
import com.indagium.model.Highlighter
import com.indagium.model.MessageTemplate
import com.indagium.utils.messageRuleSpecForTemplate

// Pure (UI-free) half of the Highlighters section's search-to-add field: parsing what the user
// typed, building the dropdown's candidates and describing an existing highlighter's row. Kept out
// of HighlighterSection.kt so tests can pin ranking and parsing without composing anything.

/** Dropdown group a candidate belongs to; the entries are declared in the order they are listed. */
internal enum class HighlightCandidateGroup(val label: String) {
    TEXT("TEXT"),
    TAGS("TAGS"),
    MESSAGES("MESSAGES"),
}

/**
 * One suggestion of the add field. Everything needed to create the highlighter is carried here;
 * whether it becomes a match or a whole-line highlighter is chosen when it is added, and the
 * default is always match-only.
 */
internal data class HighlightCandidate(
    val group: HighlightCandidateGroup,
    val label: String,
    val pattern: String,
    val regex: Boolean,
    val target: HighlightTarget,
    // exact-tag scope; null = any tag
    val tag: String? = null,
    // rows the candidate is known to cover; null = not known up front (typed text is counted later)
    val count: Int? = null,
    // dimmed second piece of text (a message template's tag)
    val detail: String? = null,
    // a package prefix ("com.foo.*"), which is matched inside the tag column but is no exact tag
    val isPackage: Boolean = false,
)

/** What the add field currently means: an optional exact-tag scope plus the text after it. */
internal data class HighlighterQuery(val scopeTag: String?, val text: String, val regex: Boolean) {
    val isBlank: Boolean get() = scopeTag == null && text.isBlank()
}

private val TAG_SCOPE_INPUT = Regex("^tag:(\\S+)(?:\\s+(.*))?$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/**
 * Reads the add field. `tag:Name rest` scopes the highlighter to the exact tag `Name` (the same
 * result as picking a tag candidate with Tab and then typing `rest`); `/pattern/` is a regex; any
 * other text is a plain substring unless [regexMode] (the `.*` pill) is on. A chip picked earlier
 * ([scopeChip]) applies unless the text names a tag itself.
 */
internal fun parseHighlighterQuery(input: String, scopeChip: String?, regexMode: Boolean): HighlighterQuery {
    val trimmed = input.trim()
    val scoped = TAG_SCOPE_INPUT.matchEntire(trimmed)
    val scopeTag = scoped?.groupValues?.get(1) ?: scopeChip?.takeIf { it.isNotBlank() }
    val text = if (scoped != null) scoped.groupValues[2].trim() else trimmed
    val wrapped = slashWrappedRegex(text)
    return if (wrapped != null) {
        HighlighterQuery(scopeTag, wrapped, regex = true)
    } else {
        HighlighterQuery(scopeTag, text, regexMode)
    }
}

private const val MAX_TAG_CANDIDATES = 6
private const val MAX_PACKAGE_CANDIDATES = 3
private const val MAX_MESSAGE_CANDIDATES = 6

/**
 * The dropdown for [query], best-first and grouped: the typed text itself (or, for a bare
 * `tag:Name`, that tag), then matching tags and package prefixes, then message templates from the
 * Log composition histogram. With a tag scope only that tag's templates are offered and no other
 * tags are suggested. Regex queries offer just the regex itself, since tags and templates are only
 * searched as plain text.
 */
internal fun highlighterCandidates(
    query: HighlighterQuery,
    sortedTags: List<String>,
    tagCounts: Map<String, Int>,
    tagUsage: Map<String, Int>,
    mostUsedTagLimit: Int,
    templates: List<MessageTemplate>,
): List<HighlightCandidate> {
    val scope = query.scopeTag
    val text = query.text
    if (query.isBlank) return emptyList()
    if (text.isBlank()) {
        // A bare tag scope: the only sensible highlighter is "this tag".
        return listOf(tagCandidate(scope.orEmpty(), tagCounts))
    }
    val literal = HighlightCandidate(
        group = HighlightCandidateGroup.TEXT,
        label = if (query.regex) "/$text/" else text,
        pattern = text,
        regex = query.regex,
        target = if (scope != null) HighlightTarget.MESSAGE else HighlightTarget.ANY,
        tag = scope,
    )
    if (query.regex) return listOf(literal)
    val tags = if (scope != null) {
        emptyList()
    } else {
        val packages = packagePrefixCandidates(sortedTags, text, limit = MAX_PACKAGE_CANDIDATES).map { prefix ->
            HighlightCandidate(
                group = HighlightCandidateGroup.TAGS,
                label = "$prefix.*",
                pattern = prefix,
                regex = false,
                target = HighlightTarget.TAG,
                count = tagCounts.filterKeys { it.contains(prefix, ignoreCase = true) }.values.sum(),
                isPackage = true,
            )
        }
        val exact = tagCandidates(
            sortedTags = sortedTags,
            search = text,
            selectedTags = emptySet(),
            packagePrefixes = emptySet(),
            tagUsage = tagUsage,
            mostUsedLimit = mostUsedTagLimit,
            searchLimit = MAX_TAG_CANDIDATES,
        ).map { tagCandidate(it, tagCounts) }
        packages + exact
    }
    val messages = templates.asSequence()
        .filter { it.template.contains(text, ignoreCase = true) }
        .filter { scope == null || it.tag.trim() == scope }
        .take(MAX_MESSAGE_CANDIDATES)
        .map { template ->
            val spec = messageRuleSpecForTemplate(template)
            HighlightCandidate(
                group = HighlightCandidateGroup.MESSAGES,
                label = template.template,
                pattern = spec.pattern,
                regex = spec.regex,
                target = HighlightTarget.MESSAGE,
                tag = template.tag.trim().takeIf { it.isNotBlank() },
                count = template.count,
                detail = template.tag.trim().takeIf { it.isNotBlank() },
            )
        }
        .toList()
    return listOf(literal) + tags + messages
}

private fun tagCandidate(tag: String, tagCounts: Map<String, Int>) = HighlightCandidate(
    group = HighlightCandidateGroup.TAGS,
    label = tag,
    pattern = tag,
    regex = false,
    // Same shape as "Highlight tag" in the row context menu: matched in the tag column, and limited
    // to exactly this tag so "Foo" does not also light up "FooBar".
    target = HighlightTarget.TAG,
    tag = tag,
    count = tagCounts[tag],
)

/**
 * Whether adding a candidate paints the whole line. [rowAction] is what ←/→ picked for the row
 * (0 = Match, 1 = Line, null = nothing picked) and [defaultWholeLine] the Match text | Whole line
 * control, which starts on Match text, so nothing is ever whole-line unless the user chose it.
 */
internal fun highlightModeFor(rowAction: Int?, defaultWholeLine: Boolean): Boolean = when (rowAction) {
    0 -> false
    1 -> true
    else -> defaultWholeLine
}

/** The tag Tab turns a candidate into a scope chip for; null for anything but an exact tag. */
internal fun scopeChipFor(candidate: HighlightCandidate): String? =
    candidate.tag.takeIf { candidate.group == HighlightCandidateGroup.TAGS && !candidate.isPackage }

/** The highlighter that already has [candidate]'s shape, so adding it again edits that one rather
 *  than stacking a duplicate. */
internal fun existingHighlighterFor(highlighters: List<Highlighter>, candidate: HighlightCandidate): Highlighter? =
    highlighters.firstOrNull {
        it.pattern == candidate.pattern &&
            it.regex == candidate.regex &&
            it.target == candidate.target &&
            it.tag?.takeIf { tag -> tag.isNotBlank() } == candidate.tag?.takeIf { tag -> tag.isNotBlank() }
    }

/** A highlighter row's label, split so the scope can be dimmed: `tag:Name ` then the pattern. */
internal data class HighlighterLabel(val scopePrefix: String?, val body: String)

internal fun highlighterLabel(hl: Highlighter): HighlighterLabel {
    val tag = hl.tag?.takeIf { it.isNotBlank() }
    // A tag highlighter's scope IS its pattern, so repeating it as a prefix would read "tag:Foo Foo".
    val redundantScope = hl.target == HighlightTarget.TAG && !hl.regex && hl.pattern == tag
    val body = if (hl.regex) "/${hl.pattern}/" + (if (hl.caseSensitive) "" else "i") else hl.pattern
    return HighlighterLabel(if (tag != null && !redundantScope) "tag:$tag " else null, body)
}

/** The small tags shown after a highlighter's pattern, in this order. */
internal fun highlighterBadges(hl: Highlighter): List<String> = buildList {
    if (hl.caseSensitive) add("Aa")
    when (hl.target) {
        HighlightTarget.TAG -> add("tag")
        HighlightTarget.MESSAGE -> add("msg")
        HighlightTarget.ANY -> Unit
    }
    if (hl.textColor != null) add("klogg")
}

/** The fields that decide which rows a highlighter matches; colour, on/off and whole-line are not
 *  among them, so changing those must not trigger a recount. */
internal data class HighlighterMatchKey(
    val id: String,
    val pattern: String,
    val regex: Boolean,
    val target: HighlightTarget,
    val tag: String?,
    val caseSensitive: Boolean,
    val captureGroupsOnly: Boolean,
)

internal fun Highlighter.matchKey() = HighlighterMatchKey(id, pattern, regex, target, tag, caseSensitive, captureGroupsOnly)

/** "12", or "≥12" when only the first part of a large file was scanned. */
internal fun formatHighlightCount(count: Int, capped: Boolean): String = if (capped) "≥$count" else count.toString()
