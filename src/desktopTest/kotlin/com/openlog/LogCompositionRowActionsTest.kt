package com.openlog

import com.openlog.model.FilterMode
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.model.MessageTemplate
import com.openlog.ui.AppState
import com.openlog.ui.mkTab
import com.openlog.utils.computeMessageTemplates
import com.openlog.utils.matchingHighlighter
import com.openlog.utils.matchingMessageRule
import com.openlog.utils.messageRuleSpecForTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 2c: the Log composition panel's Hide/Show only/Highlight buttons now toggle instead of
 * firing once — see AppState.toggleMessageRuleForTemplate/toggleHighlightForTemplate and
 * utils/MessageTemplates.matchingMessageRule/matchingHighlighter, the ONE place "is this row's
 * action already applied" is decided (model.messageRulesSameShape, also reused by
 * AppState.addMessageRule's own opposite-polarity replacement — see that function's doc).
 *
 * Every assertion here reads the tab's real `filter.messageRules` / `filter.highlighters`, not a
 * UI-only flag, per this feature's own test-writing note.
 */
class LogCompositionRowActionsTest {
    private fun entry(id: Int, tag: String, msg: String) = LogEntry(id, "10:00:00.$id", LogLevel.I, tag, msg)

    private fun openTabWithTemplate(state: AppState, tag: String, msg: String, repeats: Int = 3): MessageTemplate {
        val entries = (1..repeats).map { entry(it, tag, msg) }
        state.tabs = listOf(mkTab("t1", "a.log", entries))
        return computeMessageTemplates(entries).templates.single()
    }

    private fun tabId(state: AppState) = state.tabs.single().id

    // ── Hide toggles ─────────────────────────────────────────────────────────────────────────

    @Test
    fun hidingATemplateCreatesAnExcludeRuleAndPressingHideAgainRemovesIt() {
        val state = AppState()
        val template = openTabWithTemplate(state, "Net", "heartbeat")
        val id = tabId(state)

        state.toggleMessageRuleForTemplate(id, template, include = false)
        val afterHide = state.tabs.single().filter.messageRules
        assertEquals(1, afterHide.size)
        assertEquals(false, afterHide.single().include)
        assertTrue(
            matchingMessageRule(afterHide, template, include = false, FilterMode.TAGS) != null,
            "the row must report Hide as applied once the rule exists",
        )

        state.toggleMessageRuleForTemplate(id, template, include = false)
        val afterSecondPress = state.tabs.single().filter.messageRules
        assertTrue(afterSecondPress.isEmpty(), "a second Hide press must remove the rule it created, not add a duplicate")
        assertNull(matchingMessageRule(afterSecondPress, template, include = false, FilterMode.TAGS))
    }

    // ── Show only toggles ────────────────────────────────────────────────────────────────────

    @Test
    fun showingOnlyATemplateCreatesAnIncludeRuleAndPressingItAgainRemovesIt() {
        val state = AppState()
        val template = openTabWithTemplate(state, "Net", "heartbeat")
        val id = tabId(state)

        state.toggleMessageRuleForTemplate(id, template, include = true)
        val afterShowOnly = state.tabs.single().filter.messageRules
        assertEquals(1, afterShowOnly.size)
        assertEquals(true, afterShowOnly.single().include)
        assertTrue(matchingMessageRule(afterShowOnly, template, include = true, FilterMode.TAGS) != null)

        state.toggleMessageRuleForTemplate(id, template, include = true)
        assertTrue(state.tabs.single().filter.messageRules.isEmpty())
    }

    // ── Highlight toggles ────────────────────────────────────────────────────────────────────

    @Test
    fun highlightingATemplateCreatesAHighlighterAndPressingItAgainRemovesIt() {
        val state = AppState()
        val template = openTabWithTemplate(state, "Net", "heartbeat")
        val id = tabId(state)

        state.toggleHighlightForTemplate(id, template)
        val afterHighlight = state.tabs.single().filter.highlighters
        assertEquals(1, afterHighlight.size)
        assertTrue(matchingHighlighter(afterHighlight, template) != null)

        state.toggleHighlightForTemplate(id, template)
        val afterSecondPress = state.tabs.single().filter.highlighters
        assertTrue(afterSecondPress.isEmpty())
        assertNull(matchingHighlighter(afterSecondPress, template))
    }

    // ── A hand-authored rule counts as applied too ──────────────────────────────────────────

    @Test
    fun aMessageRuleCreatedByHandThatHappensToMatchATemplateMakesThatRowReportApplied() {
        val state = AppState()
        val template = openTabWithTemplate(state, "Net", "heartbeat")
        val id = tabId(state)
        val spec = messageRuleSpecForTemplate(template)

        // Built directly via addMessageRule (the right-click flyout's own path), never through the
        // Log composition panel's toggle — this is the "user made it by hand" scenario.
        state.addMessageRule(id, include = false, pattern = spec.pattern, regex = spec.regex, tag = template.tag, packagePrefix = null)

        val rules = state.tabs.single().filter.messageRules
        assertEquals(1, rules.size, "sanity: exactly the hand-authored rule exists")
        assertTrue(
            matchingMessageRule(rules, template, include = false, FilterMode.TAGS) != null,
            "a hand-authored rule that matches the template's own pattern is genuinely applied, not a false positive",
        )

        // And pressing Hide now must recognize it and remove it, not add a second contradictory one.
        state.toggleMessageRuleForTemplate(id, template, include = false)
        assertTrue(state.tabs.single().filter.messageRules.isEmpty())
    }

    // ── Hide then Show only: exactly one rule survives ──────────────────────────────────────
    // Decision (documented here, matches AppState.addMessageRule's existing opposite-polarity
    // replacement — see addingOppositeTagAndMessageRuleReplacesTheExistingPolarity in
    // AppStateBehaviorTest for the same rule reached the other way): the LATEST action wins. Hide
    // then Show-only leaves only the Show-only (include) rule — it does not keep both, and it does
    // not leave the view unfiltered. This falls out of addMessageRule's own same-shape/opposite-
    // polarity handling with no extra code in the toggle functions.

    @Test
    fun hidingThenShowingOnlyTheSameRowLeavesExactlyOneRuleNotTwoContradictoryOnes() {
        val state = AppState()
        val template = openTabWithTemplate(state, "Net", "heartbeat")
        val id = tabId(state)

        state.toggleMessageRuleForTemplate(id, template, include = false) // Hide
        state.toggleMessageRuleForTemplate(id, template, include = true) // Show only

        val rules = state.tabs.single().filter.messageRules
        assertEquals(1, rules.size, "Hide then Show-only must leave exactly one rule")
        assertEquals(true, rules.single().include, "the later action (Show-only) wins")
        assertNull(matchingMessageRule(rules, template, include = false, FilterMode.TAGS), "the Hide rule must be gone")
        assertTrue(matchingMessageRule(rules, template, include = true, FilterMode.TAGS) != null)
    }
}
