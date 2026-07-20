package com.openlog.utils

import com.openlog.model.LogItem
import com.openlog.model.entry

// Result of one in-view "Find" pass (ui/SearchBar.kt, AppState.scheduleSearchRecompute). Pure and
// off the UI thread: computeSearchMatches below never touches AppState, Compose state, or LogTab
// directly — it only walks the item list it's handed, exactly like utils/Filter.kt's computeItems.
internal data class SearchComputeResult(
    val matchIds: IntArray,
    val invalidPattern: Boolean,
    val timedOut: Boolean,
) {
    // Same reasoning as LogSearchState's own equals()/hashCode() override in model/Model.kt —
    // IntArray's default equals() is identity-based, which would make two computations with
    // identical matches compare unequal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SearchComputeResult) return false
        return invalidPattern == other.invalidPattern &&
            timedOut == other.timedOut &&
            matchIds.contentEquals(other.matchIds)
    }

    override fun hashCode(): Int {
        var result = invalidPattern.hashCode()
        result = 31 * result + timedOut.hashCode()
        result = 31 * result + matchIds.contentHashCode()
        return result
    }
}

private val EMPTY_SEARCH_RESULT = SearchComputeResult(IntArray(0), invalidPattern = false, timedOut = false)

// Walks `items` (expected fully expanded — see AppState.scheduleSearchRecompute — so a match
// hidden inside a currently-collapsed group is still found; the jump itself is what expands that
// group) and matches `query` as a regex against each item's visibleLogLineText, exactly the text
// buildFullLineAnnotation renders so match offsets line up with the highlight. Always regex — this
// is a "Find" bar, not the plain-text/regex-toggle keyword filter — through regexRanges/
// RegexEvaluationContext only, same catastrophic-backtracking budget and compile cache as every
// other regex path in the app (see TextMatch.kt); never a raw Regex().
//
// An empty query intentionally reports zero matches without being "invalid" — nothing is
// highlighted until the user actually types something. A syntactically-broken pattern instead
// reports invalidPattern=true so the find bar can show it as invalid rather than "0 matches",
// a distinction regexRanges alone can't make (it collapses both to an empty result).
internal fun computeSearchMatches(
    items: List<LogItem>,
    query: String,
    caseSensitive: Boolean,
    ctx: RegexEvaluationContext,
): SearchComputeResult {
    if (query.isEmpty()) return EMPTY_SEARCH_RESULT
    val ignoreCase = !caseSensitive
    if (!isValidRegexPattern(query, ignoreCase = ignoreCase)) {
        return SearchComputeResult(IntArray(0), invalidPattern = true, timedOut = false)
    }
    val ids = ArrayList<Int>()
    for (item in items) {
        val entry = item.entry
        val ranges = regexRanges(visibleLogLineText(entry), query, ignoreCase = ignoreCase, regexContext = ctx)
        if (ranges.isNotEmpty()) ids.add(entry.id)
    }
    return SearchComputeResult(ids.toIntArray(), invalidPattern = false, timedOut = ctx.hasTimedOut)
}
