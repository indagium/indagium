package com.indagium.ui

import com.indagium.model.SearchMode
import com.indagium.model.SearchScopeBehavior
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchScopePolicyTest {
    @Test
    fun openPolicyResolvesAllFourChoicesAndRememberedDualMode() {
        assertEquals(
            SearchPolicyResolution(SearchMode.FILTERED, false),
            resolveSearchPolicy(SearchScopeBehavior.FILTERED, SearchMode.TWO_SEARCHES, originalVisible = true),
        )
        assertEquals(
            SearchPolicyResolution(SearchMode.UNFILTERED, true),
            resolveSearchPolicy(SearchScopeBehavior.UNFILTERED, SearchMode.FILTERED, originalVisible = false),
        )
        assertEquals(
            SearchPolicyResolution(SearchMode.TWO_SEARCHES, true),
            resolveSearchPolicy(SearchScopeBehavior.LAST_USED, SearchMode.TWO_SEARCHES, originalVisible = false),
        )
        assertEquals(
            SearchPolicyResolution(SearchMode.UNFILTERED, true),
            resolveSearchPolicy(SearchScopeBehavior.FOLLOW_VIEW, SearchMode.TWO_SEARCHES, originalVisible = true),
        )
        assertEquals(
            SearchPolicyResolution(SearchMode.FILTERED, false),
            resolveSearchPolicy(SearchScopeBehavior.FOLLOW_VIEW, SearchMode.UNFILTERED, originalVisible = false),
        )
    }

    @Test
    fun visibilityTransitionsOnlyAutoSwitchUnderFollowViewAndAlwaysConstrainWhenHidden() {
        SearchScopeBehavior.entries.forEach { behavior ->
            assertEquals(
                SearchMode.FILTERED,
                modeAfterOriginalVisibilityChange(
                    SearchMode.TWO_SEARCHES,
                    originalVisible = false,
                    behavior = behavior,
                    lastManualMode = SearchMode.TWO_SEARCHES,
                ),
            )
        }
        assertEquals(
            SearchMode.UNFILTERED,
            modeAfterOriginalVisibilityChange(
                SearchMode.TWO_SEARCHES,
                originalVisible = true,
                behavior = SearchScopeBehavior.FOLLOW_VIEW,
                lastManualMode = SearchMode.FILTERED,
            ),
        )
        listOf(
            SearchScopeBehavior.FILTERED,
            SearchScopeBehavior.UNFILTERED,
            SearchScopeBehavior.LAST_USED,
        ).forEach { behavior ->
            assertEquals(
                SearchMode.TWO_SEARCHES,
                modeAfterOriginalVisibilityChange(
                    SearchMode.TWO_SEARCHES,
                    originalVisible = true,
                    behavior = behavior,
                    lastManualMode = SearchMode.FILTERED,
                ),
            )
        }
    }

    @Test
    fun compareModeAlwaysConstrainsSearchToFilteredWithoutRevealingOriginal() {
        val resolved = resolveSearchPolicy(
            SearchScopeBehavior.UNFILTERED,
            SearchMode.TWO_SEARCHES,
            originalVisible = true,
            compareMode = true,
        )
        assertEquals(SearchMode.FILTERED, resolved.mode)
        assertFalse(resolved.revealOriginal)
        assertTrue(resolveSearchPolicy(SearchScopeBehavior.UNFILTERED, SearchMode.FILTERED, false).revealOriginal)
    }
}
