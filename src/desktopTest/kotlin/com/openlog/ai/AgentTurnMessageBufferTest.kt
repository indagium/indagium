package com.openlog.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentTurnMessageBufferTest {
    @Test
    fun earlierAgentMessageBecomesProgressAndOnlyTheLastMessageIsFinal() {
        val buffer = AgentTurnMessageBuffer()

        assertNull(buffer.append("plan", "I will inspect the log."))
        assertNull(buffer.append("plan", " Then I will narrow the tags."))
        assertEquals(
            "I will inspect the log. Then I will narrow the tags.",
            buffer.append("answer", "The crash originates in Parser."),
        )
        assertEquals("The crash originates in Parser.", buffer.finalText())
    }
}
