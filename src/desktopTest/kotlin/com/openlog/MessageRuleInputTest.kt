package com.openlog

import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.model.MessageRule
import com.openlog.model.RuleTarget
import com.openlog.ui.contextualMessageRuleCandidates
import com.openlog.ui.messageRuleInputSpec
import com.openlog.ui.messageRulePillLabel
import com.openlog.ui.messageRuleScopeOptions
import com.openlog.ui.messageRuleScopePrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageRuleInputTest {
    @Test
    fun regexModeCreatesMessageRegexRule() {
        val spec = messageRuleInputSpec("""timeout\s+\d+""", regexMode = true)

        assertEquals("""timeout\s+\d+""", spec.pattern)
        assertTrue(spec.regex)
        assertEquals(RuleTarget.MESSAGE, spec.target)
    }

    @Test
    fun slashWrappedInputCreatesRegexRuleWithoutToggle() {
        val spec = messageRuleInputSpec("""/timeout\s+\d+/i""", regexMode = false)

        assertEquals("""timeout\s+\d+""", spec.pattern)
        assertTrue(spec.regex)
        assertEquals(RuleTarget.MESSAGE, spec.target)
    }

    @Test
    fun numericInputRemainsPidTidRuleEvenWhenRegexModeIsOn() {
        val spec = messageRuleInputSpec("1234", regexMode = true)

        assertEquals("1234", spec.pattern)
        assertFalse(spec.regex)
        assertEquals(RuleTarget.PID_TID, spec.target)
    }

    @Test
    fun scopedMessageRulePillLabelsShowWhereRuleApplies() {
        val exact = MessageRule(id = "r1", include = true, pattern = "timeout", tag = "com.app.Network")
        val prefix = MessageRule(id = "r2", include = false, pattern = "heartbeat", packagePrefix = "com.app", regex = true)
        val all = MessageRule(id = "r3", include = true, pattern = "1234", target = RuleTarget.PID_TID)

        assertEquals("com.app.Network → timeout", messageRulePillLabel(exact))
        assertEquals("com.app.* → /heartbeat/", messageRulePillLabel(prefix))
        assertEquals("pid:1234", messageRulePillLabel(all))
    }

    @Test
    fun pendingScopePromptShowsSelectedRuleAction() {
        assertEquals("Add + rule to", messageRuleScopePrompt(include = true))
        assertEquals("Add - rule to", messageRuleScopePrompt(include = false))
    }

    @Test
    fun scopeOptionsKeepAllBeforeSearchResults() {
        val options = messageRuleScopeOptions(
            sortedTags = listOf("com.app.Network", "com.app.Auth", "org.other.Ui"),
            search = "app",
        )

        assertEquals("All", options[0].label)
        assertEquals("com.app.*", options[1].label)
        assertTrue(options.drop(1).any { it.label == "com.app.Network" })
    }

    @Test
    fun contextualSuggestionsMatchBothTagSeparatorFormsAndMirrorFlyoutVariants() {
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "com.my.app", "method call: id=42")

        val spaced = contextualMessageRuleCandidates(listOf(entry), "com.my.app : method", regex = false)
        val compact = contextualMessageRuleCandidates(listOf(entry), "com.my.app: method", regex = false)

        val expected = listOf(
            Triple("com.my.app: method call", "method call", "com.my.app"),
            Triple("com.my.app: method call: id", "method call: id", "com.my.app"),
            Triple("method call", "method call", null),
            Triple("method call: id", "method call: id", null),
        )
        assertEquals(expected, spaced.map { Triple(it.label, it.pattern, it.tag) })
        assertEquals(expected, compact.map { Triple(it.label, it.pattern, it.tag) })
    }

    @Test
    fun contextualSuggestionsHonorRegexAndDoNotReplaceMessageOnlySuggestions() {
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "com.my.app", "method call")

        val regexMatches = contextualMessageRuleCandidates(listOf(entry), "com\\.my\\.app.*method", regex = true)

        assertEquals(
            listOf(
                Triple("com.my.app: method call", "method call", "com.my.app"),
                Triple("method call", "method call", null),
            ),
            regexMatches.map { Triple(it.label, it.pattern, it.tag) },
        )
        assertTrue(contextualMessageRuleCandidates(listOf(entry), "method", regex = false).isEmpty())
    }

    @Test
    fun contextualSuggestionsDeDuplicateRepeatedLogRows() {
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "com.my.app", "method call")

        val candidates = contextualMessageRuleCandidates(listOf(entry, entry.copy(id = 2)), "com.my.app.*method", regex = true)

        assertEquals(2, candidates.size)
    }
}
