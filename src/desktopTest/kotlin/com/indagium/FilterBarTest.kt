package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.Highlighter
import com.indagium.model.LogLevel
import com.indagium.model.MessageRule
import com.indagium.model.SequenceDef
import com.indagium.ui.combinedTagCandidates
import com.indagium.ui.filterBarCandidatesStayVisible
import com.indagium.ui.filterBarPillCount
import com.indagium.ui.filterBarResidualSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the pure helpers backing the horizontal filter bar (ui/FilterBar.kt) — see that file's
 * own header for the feature this supports. Split across two phases as the bar was built:
 * `combinedTagCandidates` (lifted out of ui/FilterPanel.kt's own inline concatenation, see
 * ui/TagSuggestions.kt's doc comment on it) landed first, `filterBarResidualSummary` /
 * `filterBarPillCount` once ui/FilterBar.kt itself existed.
 */
class FilterBarTest {
    private val sortedTags = listOf("com.example.Alpha", "com.example.Beta", "com.other.Gamma", "Standalone")
    private val tagUsage = mapOf("com.example.Alpha" to 5, "com.example.Beta" to 2, "com.other.Gamma" to 1, "Standalone" to 0)

    @Test
    fun blankSearchReturnsMostUsedTagsOnlyNoPackagePrefixes() {
        val result = combinedTagCandidates(
            sortedTags = sortedTags,
            search = "",
            packagePrefixes = emptySet(),
            tagUsage = tagUsage,
            mostUsedTagLimit = 5,
        )

        // packagePrefixCandidates itself returns nothing for a blank search (see its own doc) —
        // the combined list must therefore contain plain tags only, ranked by usage.
        assertTrue(result.none { (_, isPkg) -> isPkg }, "a blank search must propose no package prefixes")
        assertEquals(listOf("com.example.Alpha", "com.example.Beta", "com.other.Gamma"), result.map { it.first })
    }

    // Bug report (user): "if there is no text and I press the field, there are no variants of most
    // frequent tags, but in the tags panel they appear." Step 1 of the investigation this test
    // records: pin down whether the SHARED function (this one, used by both ui/FilterBar.kt and
    // ui/FilterPanel.kt's own inline equivalent) can even produce a non-empty result for a blank
    // search with real tagUsage/mostUsedTagLimit data — i.e. rule out (or confirm) the bug being in
    // shared logic before looking at either UI layer. This passes, which rules it out: the function
    // itself returns the same non-empty, usage-ranked list either caller would get. See
    // ui/FilterBar.kt's Bug 1 fix (the popup-positioning bug, its own comments there) for where the
    // real defect was — the candidates were always present, just rendered off the field's visible
    // area by the BottomStart-with-no-offset popup bug, which made an already-correct list look like
    // an empty one on screen.
    @Test
    fun blankSearchWithNonEmptyTagUsageAndPositiveLimitReturnsNonEmptyCandidates() {
        val result = combinedTagCandidates(
            sortedTags = sortedTags,
            search = "",
            packagePrefixes = emptySet(),
            tagUsage = tagUsage,
            mostUsedTagLimit = 5,
        )

        assertTrue(result.isNotEmpty(), "a blank search with non-empty tagUsage must still propose most-used tags")
    }

    @Test
    fun candidatePopupStaysVisibleWhileFieldOrPopupIsActive() {
        assertTrue(filterBarCandidatesStayVisible(fieldFocused = true, candidatesHovered = false))
        assertTrue(filterBarCandidatesStayVisible(fieldFocused = false, candidatesHovered = true))
        assertFalse(filterBarCandidatesStayVisible(fieldFocused = false, candidatesHovered = false))
    }

    @Test
    fun nonBlankSearchOrdersPackagePrefixesBeforeTagMatches() {
        val result = combinedTagCandidates(
            sortedTags = sortedTags,
            search = "example",
            packagePrefixes = emptySet(),
            tagUsage = tagUsage,
            mostUsedTagLimit = 5,
        )

        // "com.example" is a package-prefix match (shared by Alpha/Beta) and must be offered
        // before the tag matches it's a prefix of — this is what makes typing a dotted stem
        // propose "scope to this whole package" ahead of "just this one tag".
        val firstPkgIdx = result.indexOfFirst { (_, isPkg) -> isPkg }
        val firstTagIdx = result.indexOfFirst { (_, isPkg) -> !isPkg }
        assertTrue(firstPkgIdx in 0 until firstTagIdx, "package-prefix candidates must precede tag candidates")
        assertEquals("com.example", result[firstPkgIdx].first)
    }

    @Test
    fun eachEntryIsPairedWithWhetherItIsAPackagePrefix() {
        val result = combinedTagCandidates(
            sortedTags = sortedTags,
            search = "com.example",
            packagePrefixes = emptySet(),
            tagUsage = tagUsage,
            mostUsedTagLimit = 5,
        )

        val (value, isPkg) = result.first()
        assertEquals("com.example", value)
        assertTrue(isPkg)
        assertTrue(result.drop(1).all { (tag, isPkg2) -> !isPkg2 && tag.startsWith("com.example") })
    }

    // ── filterBarResidualSummary ─────────────────────────────────────────
    // See ui/FilterBar.kt's own header ("Why not describeFilter") for why this is a separate,
    // narrower function from cases/CaseModel.kt's describeFilter.

    @Test
    fun defaultFilterHasNoResidualSummary() {
        assertEquals("", filterBarResidualSummary(Filter()))
    }

    @Test
    fun aFullyRenderedTagsOnlyFilterHasNoResidualSummary() {
        // Everything here is already shown by the bar itself as a pill or a field value — none of
        // it should leak into the residual chip.
        val filter = Filter(
            activeTags = setOf("App"),
            excludeTags = setOf("Noisy"),
            pkgPrefixes = setOf("com.example"),
            kwInTag = "boot",
            messageRules = listOf(MessageRule(id = "r1", include = true, pattern = "boom", mode = FilterMode.TAGS)),
        )

        assertEquals("", filterBarResidualSummary(filter))
    }

    @Test
    fun contiguousLevelsFromTheMinimumReportAsLevelGte() {
        val filter = Filter(levels = setOf(LogLevel.W, LogLevel.E, LogLevel.A))

        assertEquals("level≥W", filterBarResidualSummary(filter))
    }

    @Test
    fun nonContiguousLevelsReportAsAnExplicitList() {
        val filter = Filter(levels = setOf(LogLevel.V, LogLevel.E))

        assertEquals("levels=V,E", filterBarResidualSummary(filter))
    }

    @Test
    fun excludeKwIsReportedQuoted() {
        val filter = Filter(excludeKw = "spam")

        assertEquals("excl-kw=\"spam\"", filterBarResidualSummary(filter))
    }

    @Test
    fun pidTidFilterIsReported() {
        val filter = Filter(pidTidFilter = "1234")

        assertEquals("pid/tid=1234", filterBarResidualSummary(filter))
    }

    @Test
    fun onlyEnabledHighlightersAreCounted() {
        val filter = Filter(
            highlighters = listOf(
                Highlighter(id = "h1", pattern = "a", regex = false, color = Color.Red, on = true),
                Highlighter(id = "h2", pattern = "b", regex = false, color = Color.Blue, on = false),
            ),
        )

        assertEquals("1 highlighter", filterBarResidualSummary(filter))
    }

    @Test
    fun sequencesAreNeverReportedRegardlessOfSeqOn() {
        // Design feedback: drop sequences from the bar's residual summary entirely — the user does
        // not want sequence info surfaced in this bar, in any state (on, off, any count).
        val seq = SequenceDef(id = "s1", matchText = "start", priority = 0, color = Color.Green)
        val enabled = Filter(sequences = listOf(seq), seqOn = true)
        val disabled = Filter(sequences = listOf(seq), seqOn = false)

        assertEquals("", filterBarResidualSummary(enabled))
        assertEquals("", filterBarResidualSummary(disabled))
    }

    @Test
    fun inertKeywordModeRulesAreNotReportedInTagsMode() {
        // A rule authored while the filter was in Regex mode is preserved but inert in Tags mode
        // (MessageRule.mode's own doc). It is not rendered by the panel and does not affect the
        // current result, so the bar must not surface it either.
        val filter = Filter(
            mode = FilterMode.TAGS,
            messageRules = listOf(MessageRule(id = "r1", include = true, pattern = "x", mode = FilterMode.KEYWORD)),
        )

        assertEquals("", filterBarResidualSummary(filter))
    }

    @Test
    fun residualSummaryNeverMentionsTagsPrefixesOrRulePatterns() {
        // Negative test: this is what must fail if filterBarResidualSummary is ever "simplified"
        // back to reusing describeFilter, which reports exactly these fields (already shown by
        // the bar itself as pills/fields).
        val filter = Filter(
            activeTags = setOf("SecretTag"),
            excludeTags = setOf("OtherSecretTag"),
            pkgPrefixes = setOf("com.confidential"),
            kwText = "topSecretPattern",
            kwInTag = "anotherSecretPattern",
            messageRules = listOf(MessageRule(id = "r1", include = true, pattern = "secretRulePattern", mode = FilterMode.TAGS)),
            // Also exercise a genuine residual field alongside the above.
            levels = setOf(LogLevel.E),
        )

        val summary = filterBarResidualSummary(filter)
        assertTrue(summary.isNotEmpty())
        for (forbidden in listOf("SecretTag", "OtherSecretTag", "com.confidential", "topSecretPattern", "anotherSecretPattern", "secretRulePattern")) {
            assertFalse(summary.contains(forbidden), "residual summary must never mention '$forbidden': $summary")
        }
    }

    // ── filterBarPillCount ────────────────────────────────────────────────

    @Test
    fun pillCountSumsPrefixesTagsAndCurrentModeMessageRulesOnly() {
        val filter = Filter(
            mode = FilterMode.TAGS,
            pkgPrefixes = setOf("com.a", "com.b"),
            excludePkgPrefixes = setOf("com.c"),
            activeTags = setOf("App"),
            excludeTags = setOf("Noisy1", "Noisy2"),
            messageRules = listOf(
                MessageRule(id = "r1", include = true, pattern = "x", mode = FilterMode.TAGS),
                // A KEYWORD-mode rule must not count while the filter is in TAGS mode.
                MessageRule(id = "r2", include = true, pattern = "y", mode = FilterMode.KEYWORD),
            ),
        )

        // 2 pkg + 1 excl-pkg + 1 tag + 2 excl-tag + 1 tags-mode rule = 7
        assertEquals(7, filterBarPillCount(filter))
    }

    @Test
    fun pillCountIsZeroForADefaultFilter() {
        assertEquals(0, filterBarPillCount(Filter()))
    }
}
