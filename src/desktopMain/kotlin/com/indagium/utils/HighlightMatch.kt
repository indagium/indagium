package com.indagium.utils

import com.indagium.model.HighlightTarget
import com.indagium.model.Highlighter
import com.indagium.model.LogEntry

// The one highlighter matcher: LogRow's rendering (ui/LogViewer.kt buildLogLineRender) and the
// minimap (ui/Minimap.kt) both go through here, so "which highlighter owns this row" can never
// drift between what is painted and what the overview strip shows. UI-free on purpose.

/** One painted match, as [start, end) offsets into visibleLogLineText(entry) — remap them with
 *  remapPidFieldRange before applying them to the rendered text. */
internal data class HlSpan(val start: Int, val end: Int, val hl: Highlighter)

/** What the highlighters do to one row: the [wholeLine] highlighter that owns the row's tint (null
 *  when none does) and the match [spans] in paint order — a later span paints on top of an earlier
 *  one. */
internal data class LineHighlight(val wholeLine: Highlighter?, val spans: List<HlSpan>) {
    companion object {
        val NONE = LineHighlight(null, emptyList())
    }
}

/** A non-null text colour marks a klogg-imported highlighter, which keeps klogg's own rules. */
internal fun Highlighter.isKloggStyle(): Boolean = textColor != null

private fun tagLimitAllows(hl: Highlighter, entry: LogEntry): Boolean {
    val exact = hl.tag?.takeIf { it.isNotBlank() } ?: return true
    return entry.tag == exact
}

// The text a highlighter's pattern runs against, and where that text starts inside
// visibleLogLineText(entry) (which ends with "tag: msg"), so spans come back in line coordinates.
private fun scopeText(hl: Highlighter, entry: LogEntry, lineText: String): String = when (hl.target) {
    HighlightTarget.ANY -> lineText
    HighlightTarget.TAG -> entry.tag
    HighlightTarget.MESSAGE -> entry.msg
}

private fun scopeOffset(hl: Highlighter, entry: LogEntry, lineText: String): Int = when (hl.target) {
    HighlightTarget.ANY -> 0
    HighlightTarget.TAG -> lineText.length - entry.msg.length - ": ".length - entry.tag.length
    HighlightTarget.MESSAGE -> lineText.length - entry.msg.length
}

private fun plainRanges(text: String, pattern: String, ignoreCase: Boolean, overlapping: Boolean): List<Pair<Int, Int>> =
    buildList {
        var i = 0
        while (true) {
            val idx = text.indexOf(pattern, i, ignoreCase = ignoreCase)
            if (idx < 0) break
            add(idx to idx + pattern.length)
            i = if (overlapping) idx + 1 else idx + pattern.length
        }
    }

/** Whether an enabled [hl] finds its pattern on this row (honouring its tag limit and target). */
internal fun highlighterMatches(
    hl: Highlighter,
    entry: LogEntry,
    lineText: String,
    regexContext: RegexEvaluationContext,
): Boolean {
    if (!hl.on || hl.pattern.isBlank() || !tagLimitAllows(hl, entry)) return false
    if (hl.regex && hl.captureGroupsOnly) {
        return regexHighlightHits(scopeText(hl, entry, lineText), hl.pattern, !hl.caseSensitive, regexContext)
    }
    return containsPattern(
        scopeText(hl, entry, lineText),
        hl.pattern,
        hl.regex,
        ignoreCase = !hl.caseSensitive,
        regexContext = regexContext,
    )
}

private fun highlighterSpans(
    hl: Highlighter,
    entry: LogEntry,
    lineText: String,
    regexContext: RegexEvaluationContext,
): List<HlSpan> {
    if (!hl.on || hl.pattern.isBlank() || !tagLimitAllows(hl, entry)) return emptyList()
    val text = scopeText(hl, entry, lineText)
    val ignoreCase = !hl.caseSensitive
    val ranges = if (hl.regex) {
        regexHighlightRanges(text, hl.pattern, ignoreCase, hl.captureGroupsOnly, regexContext)
    } else {
        // Ours keeps the overlapping scan every highlighter always had; klogg's escaped-regex
        // globalMatch never overlaps.
        plainRanges(text, hl.pattern, ignoreCase, overlapping = !hl.isKloggStyle())
    }
    val offset = scopeOffset(hl, entry, lineText)
    return ranges.map { (s, e) -> HlSpan(s + offset, e + offset, hl) }
}

/**
 * Resolves every highlighter against one row.
 *
 * - The first enabled whole-line highlighter (list order) that matches owns the row.
 * - A klogg-style owner keeps only the match highlighters listed ABOVE it (klogg stops at the first
 *   whole-line hit); an Indagium owner keeps every match highlighter.
 * - Paint order: Indagium spans in list order (as they always were), then klogg spans in reverse
 *   list order, so the first klogg highlighter in the list ends up on top like it does in klogg.
 */
internal fun resolveLineHighlight(
    entry: LogEntry,
    lineText: String,
    highlighters: List<Highlighter>,
    regexContext: RegexEvaluationContext,
): LineHighlight {
    val active = highlighters.filter { it.on && it.pattern.isNotBlank() }
    if (active.isEmpty()) return LineHighlight.NONE
    val winnerIdx = active.indexOfFirst { it.wholeLine && highlighterMatches(it, entry, lineText, regexContext) }
    val winner = active.getOrNull(winnerIdx)
    val candidates = when {
        winner == null -> active
        winner.isKloggStyle() -> active.subList(0, winnerIdx)
        else -> active
    }.filter { !it.wholeLine }
    if (winner == null && candidates.isEmpty()) return LineHighlight.NONE
    val indagium = candidates.filter { !it.isKloggStyle() }
    val klogg = candidates.filter { it.isKloggStyle() }.asReversed()
    val spans = (indagium + klogg).flatMap { highlighterSpans(it, entry, lineText, regexContext) }
    return LineHighlight(winner, spans)
}
