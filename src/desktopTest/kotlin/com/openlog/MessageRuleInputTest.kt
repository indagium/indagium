package com.openlog

import com.openlog.model.RuleTarget
import com.openlog.ui.messageRuleInputSpec
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
}
