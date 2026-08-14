package com.indagium

import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.Seq3TokenizeInput
import com.indagium.diagram3.matchesText
import com.indagium.diagram3.tokenizeSeq3Messages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Seq3TokenizerTest {
    private fun input(id: Int, text: String) = Seq3TokenizeInput(id.toString(), text)

    @Test
    fun nearIdenticalNamedValueTextsCollapseToOneMatchWithATokenNamedFromTheKey() {
        val result = tokenizeSeq3Messages(
            "UsbEventMonitor",
            listOf(
                input(1, "attach deviceKey=usb-dev-016"),
                input(2, "attach deviceKey=usb-dev-017"),
                input(3, "attach deviceKey=usb-dev-018"),
            ),
        )

        assertTrue(result.compiled, result.error)
        val match = result.match!!
        assertEquals("attach deviceKey={deviceKey}", match.template)
        assertEquals(listOf(Seq3CaptureSource.NAMED_VALUE), match.captures.map { it.source })
        assertEquals("usb-dev-016", result.captureValuesByOccurrence.getValue("1").getValue("deviceKey"))
        assertEquals("usb-dev-018", result.captureValuesByOccurrence.getValue("3").getValue("deviceKey"))
    }

    @Test
    fun aSingleOccurrenceYieldsALiteralMatchWithNoCaptures() {
        val result = tokenizeSeq3Messages("A", listOf(input(1, "device connected id=42")))

        assertTrue(result.compiled, result.error)
        val match = result.match!!
        assertEquals("device connected id=42", match.template)
        assertTrue(match.captures.isEmpty())
        assertEquals(mapOf("1" to emptyMap<String, String>()), result.captureValuesByOccurrence)
    }

    @Test
    fun tokenNamesAreStableAndDeterministicAcrossRepeatedRuns() {
        val occurrences = listOf(input(1, "push seq 4821"), input(2, "push seq 91"), input(3, "push seq 500217"))

        val first = tokenizeSeq3Messages("Pusher", occurrences)
        val second = tokenizeSeq3Messages("Pusher", occurrences)

        assertTrue(first.compiled, first.error)
        assertEquals(first.match, second.match, "the same input set must tokenize to the exact same template every time")
        assertEquals("push seq {n}", first.match!!.template, "an all-digit varying run gets the stable generated name 'n'")
    }

    @Test
    fun aHexIshVaryingRunGetsTheStableGeneratedNameId() {
        // No '=' here on purpose: a key=value run is captured under its OWN key name (see the
        // deviceKey test above) — this exercises the ANONYMOUS positional-run path instead, where
        // the generated name must come from the captured content's own shape.
        val result = tokenizeSeq3Messages(
            "A",
            listOf(input(1, "resolved token deadbeef01"), input(2, "resolved token cafebabe99")),
        )

        assertTrue(result.compiled, result.error)
        assertEquals("resolved token {id}", result.match!!.template)
    }

    @Test
    fun theDenylistKeepsAGenericNamedValueFromBecomingItsOwnNamedCapture() {
        // 'connected' only ever varies between denylisted generic words (true/false) — the
        // generator must not merge these into a noisy, mistaken {connected} token. It may still
        // fall back to capturing the differing text anonymously (see the positional-run path
        // above); what matters is that the named key itself never becomes the capture's name.
        val result = tokenizeSeq3Messages("A", listOf(input(1, "connected=true"), input(2, "connected=false")))

        assertTrue(result.compiled, result.error)
        assertFalse("connected" in result.match!!.captures.map { it.name }, "a generic true/false split must not become a {connected} token")
    }

    @Test
    fun matchesTextRoundTripsCapturedValuesAndRejectsANonMatchingText() {
        val result = tokenizeSeq3Messages("A", listOf(input(1, "fetch id=1"), input(2, "fetch id=2")))
        val match = result.match!!

        assertEquals(mapOf("id" to "3"), matchesText(match, "fetch id=3"))
        assertNull(matchesText(match, "totally different text"))
    }

    @Test
    fun structurallyIncompatibleOccurrencesFailToCompile() {
        val result = tokenizeSeq3Messages(
            "A",
            listOf(input(1, "alpha 1 beta 2"), input(2, "gamma 3 delta 4")),
        )

        assertFalse(result.compiled)
        assertEquals(null, result.match)
    }
}
