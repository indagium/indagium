package com.indagium.ui

import com.indagium.model.SearchMode
import com.indagium.model.SearchScopeBehavior

internal data class SearchPolicyResolution(
    val mode: SearchMode,
    val revealOriginal: Boolean,
)

/** Pure Find-mode policy resolution, shared by normal opens and policy-focused tests. */
internal fun resolveSearchPolicy(
    behavior: SearchScopeBehavior,
    lastManualMode: SearchMode,
    originalVisible: Boolean,
    compareMode: Boolean = false,
): SearchPolicyResolution {
    if (compareMode) return SearchPolicyResolution(SearchMode.FILTERED, revealOriginal = false)
    val mode = when (behavior) {
        SearchScopeBehavior.FILTERED -> SearchMode.FILTERED
        SearchScopeBehavior.UNFILTERED -> SearchMode.UNFILTERED
        SearchScopeBehavior.LAST_USED -> lastManualMode
        SearchScopeBehavior.FOLLOW_VIEW -> if (originalVisible) SearchMode.UNFILTERED else SearchMode.FILTERED
    }
    return SearchPolicyResolution(mode = mode, revealOriginal = mode != SearchMode.FILTERED)
}

/** Auto mode follows only deliberate visibility changes. Search-triggered reveals must not call
 *  this transition, which prevents an Unfiltered open from feeding itself. */
internal fun modeAfterOriginalVisibilityChange(
    current: SearchMode,
    originalVisible: Boolean,
    behavior: SearchScopeBehavior,
): SearchMode = when {
    !originalVisible -> SearchMode.FILTERED
    behavior == SearchScopeBehavior.FOLLOW_VIEW -> SearchMode.UNFILTERED
    else -> current
}
