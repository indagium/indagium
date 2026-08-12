package com.indagium

import com.indagium.diagram.extractCorrelationTokens
import com.indagium.diagram.sharedCorrelationToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorrelationTokenTest {
    @Test
    fun extractsUuidCompactHexAndAllNamedSpellings() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val compact = "0123456789abcdef0123456789abcdef"
        val named = listOf(
            "requestId=$compact",
            "request_id: '$compact'",
            "sessionId=\"$compact\"",
            "session_id=$compact",
            "traceId: $compact",
            "trace_id='$compact'",
            "correlationId=$compact",
            "correlation_id: \"$compact\"",
            "spanId=$compact",
        )

        assertTrue(extractCorrelationTokens("requestId=$uuid").contains(uuid))
        named.forEach { message ->
            assertTrue(extractCorrelationTokens(message).contains(compact), message)
        }
    }

    @Test
    fun rejectsGenericNumericPlainIdShortAndUnmatchedValues() {
        val message = "id=123456789 requestId=1234567 status=success traceId=short generic-word"

        assertEquals(emptyList(), extractCorrelationTokens(message))
        assertEquals(emptyList(), extractCorrelationTokens("requestId=123456789"))
        assertEquals(emptyList(), extractCorrelationTokens("requestId=abcdefg"))
        assertEquals(emptyList(), extractCorrelationTokens("id=550e8400-e29b-41d4-a716-446655440000"))
        assertEquals(emptyList(), extractCorrelationTokens("status=0123456789abcdef"))
        assertEquals(emptyList(), extractCorrelationTokens("requestId=first"))
    }

    @Test
    fun sharedTokenChoosesLongestThenLexicalAndRequiresBothMessages() {
        val shorter = "requestId=abcdefgh"
        val longer = "traceId=0123456789abcdef"

        assertEquals("0123456789abcdef", sharedCorrelationToken("$shorter $longer", longer))
        assertEquals(null, sharedCorrelationToken("requestId=abcdefgh", "requestId=ijklmnop"))
    }
}
