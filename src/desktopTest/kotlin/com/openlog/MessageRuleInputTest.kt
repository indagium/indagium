package com.openlog

import com.openlog.model.MessageRule
import com.openlog.model.RuleTarget
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
}
