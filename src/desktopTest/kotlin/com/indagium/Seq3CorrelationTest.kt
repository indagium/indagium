package com.indagium

import com.indagium.diagram3.extractCorrelationTokens
import com.indagium.diagram3.hasSharedCorrelationToken
import com.indagium.diagram3.isCallbackRegistration
import com.indagium.diagram3.isThreadHandoff
import com.indagium.diagram3.looksInboundCallback
import com.indagium.diagram3.sharedCorrelationToken
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors `CorrelationTokenTest`'s own expectations for the ported extract/shared functions,
 *  plus new coverage for the adapted isThreadHandoff/hasSharedCorrelationToken (previously private
 *  to `diagram.SeqDiagramBuilder`, now public API of this package). */
class Seq3CorrelationTest {
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
        named.forEach { message -> assertTrue(extractCorrelationTokens(message).contains(compact), message) }
    }

    @Test
    fun rejectsGenericNumericPlainIdShortAndUnmatchedValues() {
        assertEquals(emptyList(), extractCorrelationTokens("id=123456789 requestId=1234567 status=success traceId=short generic-word"))
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

    private fun entry(id: Int, ts: String, tag: String, msg: String, pid: Int = 0, tid: Int = 0) =
        LogEntry(id, ts, LogLevel.I, tag, msg, pid, tid)

    @Test
    fun threadHandoffRequiresRealNonZeroMatchingPidTidWithinTheGapBound() {
        val previous = entry(1, "10:00:00.000", "A", "start", pid = 7, tid = 11)
        val sameThreadClose = entry(2, "10:00:00.100", "B", "continue", pid = 7, tid = 11)
        val sameThreadFar = entry(3, "10:00:01.000", "B", "continue", pid = 7, tid = 11)
        val zeroPidTid = entry(4, "10:00:00.100", "B", "continue")
        val differentThread = entry(5, "10:00:00.100", "B", "continue", pid = 7, tid = 12)

        assertTrue(isThreadHandoff(sameThreadClose, previous))
        assertFalse(isThreadHandoff(sameThreadFar, previous), "beyond the gap bound must not correlate")
        assertFalse(isThreadHandoff(zeroPidTid, previous), "zero pid/tid must never correlate (brief/RAW logs)")
        assertFalse(isThreadHandoff(differentThread, previous), "a different tid must not correlate")
        assertFalse(isThreadHandoff(sameThreadClose, null), "no previous entry means no handoff")
    }

    @Test
    fun sharedCorrelationTokenRequiresAGapBoundAndAnActualSharedToken() {
        val token = "0123456789abcdef"
        val previous = entry(1, "10:00:00.000", "A", "requestId=$token start")
        val closeMatch = entry(2, "10:00:00.100", "B", "requestId=$token finish")
        val farMatch = entry(3, "10:00:01.000", "B", "requestId=$token finish")
        val noToken = entry(4, "10:00:00.100", "B", "unrelated")

        assertTrue(hasSharedCorrelationToken(closeMatch, previous))
        assertFalse(hasSharedCorrelationToken(farMatch, previous), "beyond the gap bound must not correlate")
        assertFalse(hasSharedCorrelationToken(noToken, previous), "no shared token means no correlation")
        assertFalse(hasSharedCorrelationToken(closeMatch, null), "no previous entry means no correlation")
    }

    // ── WP5: isCallbackRegistration / looksInboundCallback ──────────────────────────────────────

    @Test
    fun callbackRegistrationRecognisesRegisterAddSubscribeAndSetOnListenerShapes() {
        assertEquals("locationlistener", isCallbackRegistration(entry(1, "10:00:00.000", "A", "registerLocationListener(listener)")))
        assertEquals("click", isCallbackRegistration(entry(2, "10:00:00.000", "A", "MyView: addClickListener(handler)")))
        assertEquals("updates", isCallbackRegistration(entry(3, "10:00:00.000", "A", "EventBus: subscribeToUpdates(this)")))
        assertEquals("click", isCallbackRegistration(entry(4, "10:00:00.000", "A", "Button: setOnClickListener(this)")))
        // A registration call that names nothing descriptive still counts as a registration — the
        // TAG registering *something* is the signal inferTarget needs, not the identifier text.
        assertEquals("callback", isCallbackRegistration(entry(5, "10:00:00.000", "A", "Foo: addListener(cb)")))
    }

    @Test
    fun callbackRegistrationRejectsUnregisterAndOrdinaryLogLines() {
        assertNull(
            isCallbackRegistration(entry(1, "10:00:00.000", "A", "unregisterListener(listener)")),
            "un-registering must never be read as registering",
        )
        assertNull(isCallbackRegistration(entry(2, "10:00:00.000", "A", "Downloading update from server")))
        assertNull(isCallbackRegistration(entry(3, "10:00:00.000", "A", "onClick(view)")), "a firing callback is not itself a registration")
    }

    @Test
    fun looksInboundCallbackRecognisesOnXHandleXCallbackAndListenerShapes() {
        assertTrue(looksInboundCallback(entry(1, "10:00:00.000", "A", "onClick(view)")))
        assertTrue(looksInboundCallback(entry(2, "10:00:00.000", "A", "handleClickEvent(event)")))
        assertTrue(looksInboundCallback(entry(3, "10:00:00.000", "A", "invoking registered callback now")))
        assertTrue(looksInboundCallback(entry(4, "10:00:00.000", "A", "notifying scrollListener of change")))
    }

    @Test
    fun looksInboundCallbackRejectsOrdinaryLogLines() {
        assertFalse(looksInboundCallback(entry(1, "10:00:00.000", "A", "Downloading update from server")))
        assertFalse(looksInboundCallback(entry(2, "10:00:00.000", "A", "Connected to 192.168.0.1 on port 8080")))
        assertFalse(looksInboundCallback(entry(3, "10:00:00.000", "A", "Constructor initialized fields")))
    }
}
