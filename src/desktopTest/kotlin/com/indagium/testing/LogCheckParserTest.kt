package com.indagium.testing

import com.indagium.testing.model.DEFAULT_LOG_WITHIN_MS
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.previewLogChecks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogCheckParserTest {
    @Test
    fun androidLogcatTagsAndLiteralMessagesAreParsedAndRegexEscaped() {
        val preview = previewLogChecks(
            "10-06 12:15:30.123  123  456 E Checkout: total is $12.50 (paid)\n" +
                "D/MyTag( 1234): retry #2?\n" +
                "2026-10-06T12:15:31.123+0300 123 456 I LongFormat: open [item]\n" +
                "literal text with [brackets] and * stars",
        )

        assertEquals(4, preview.checks.size)
        assertEquals("Checkout", preview.checks[0].tag)
        assertEquals("total is $12.50 (paid)", preview.checks[0].message)
        assertEquals("\\Qtotal is $12.50 (paid)\\E", preview.checks[0].check.regex)
        assertEquals("MyTag", preview.checks[1].tag)
        assertEquals("\\Qretry #2?\\E", preview.checks[1].check.regex)
        assertEquals("LongFormat", preview.checks[2].tag)
        assertEquals("\\Qopen [item]\\E", preview.checks[2].check.regex)
        assertNull(preview.checks[3].tag)
        assertEquals(DEFAULT_LOG_WITHIN_MS, preview.checks[0].check.withinMs)
        assertEquals(emptyList(), preview.warnings)
    }

    @Test
    fun emptyTagPrefixedLineAndExcessLinesAreReported() {
        val preview = previewLogChecks("E/Tag:\nkeep", maxChecks = 1)
        assertEquals(1, preview.checks.size)
        assertEquals(listOf("Skipped a line with no message: E/Tag:"), preview.warnings)
        assertEquals("keep", (preview.checks.single().check as StepCheck.LogAppears).let { it.regex.removePrefix("\\Q").removeSuffix("\\E") })
    }
}
