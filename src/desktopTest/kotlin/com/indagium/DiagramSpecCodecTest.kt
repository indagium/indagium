package com.indagium

import com.indagium.diagram.ArrowMode
import com.indagium.diagram.DiagramDialect
import com.indagium.diagram.DiagramFrame
import com.indagium.diagram.DiagramMessage
import com.indagium.diagram.DiagramMessageRule
import com.indagium.diagram.DiagramNoteMark
import com.indagium.diagram.DiagramOptions
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.DiagramRange
import com.indagium.diagram.LabelSource
import com.indagium.diagram.MessageKind
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagram
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.encodeDiagramNote
import com.indagium.diagram.parseDiagramNote
import com.indagium.diagram.stripDiagramSpecHeader
import com.indagium.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagramSpecCodecTest {
    private val fullSpec = SeqDiagramSpec(
        dialect = DiagramDialect.MERMAID,
        title = "Boot flow",
        participants = listOf(
            DiagramParticipant("user", "User", ParticipantKind.ACTOR, isEntryPoint = true),
            DiagramParticipant("App", "App", ParticipantKind.TAG, tag = "App"),
        ),
        range = DiagramRange.Ids(10, 42),
        mode = ArrowMode.RULES,
        rules = listOf(DiagramMessageRule(id = "r1", pattern = "sending to (?<to>\\w+)", fromTemplate = "self", toTemplate = "\${to}", labelTemplate = "\${msg}")),
        options = DiagramOptions(
            collapseRepeats = false,
            maxMessages = 42,
            labelMaxChars = 30,
            labelSource = LabelSource.BOTH,
            showTimestamps = true,
            showElapsed = false,
            seqGroupFrames = false,
            notesForErrors = false,
        ),
        sourceFile = "app.log",
    )
    private val source = "sequenceDiagram\n    participant App as App\n    App->>App: hi\n"

    // ── Round-trip ────────────────────────────────────────────────────────────────────────────

    @Test
    fun encodeThenParseRoundTripsTheFullSpecAndTheSourceExactly() {
        val note = encodeDiagramNote(fullSpec, source)

        val parsed = parseDiagramNote(note)

        assertTrue(parsed != null, "a note this codec itself produced must always parse back")
        assertEquals(fullSpec, parsed.spec)
        assertEquals(DiagramDialect.MERMAID, parsed.dialect)
        assertEquals(source.trimEnd('\n'), parsed.source.trimEnd('\n'))
    }

    @Test
    fun encodeThenParseRoundTripsARangeOfEachDiagramRangeVariant() {
        val variants = listOf(
            DiagramRange.VisibleView,
            DiagramRange.Ids(1, 5),
            DiagramRange.Time("10:00:00.000", "10:00:05.000"),
            DiagramRange.SeqGroupRef("sg_x_1"),
        )
        for (range in variants) {
            val spec = SeqDiagramSpec(range = range)
            val parsed = parseDiagramNote(encodeDiagramNote(spec, source))
            assertEquals(range, parsed?.spec?.range, "range variant $range did not round-trip")
        }
    }

    @Test
    fun plantUmlDialectRoundTripsWithTheMatchingFenceLanguage() {
        val spec = SeqDiagramSpec(dialect = DiagramDialect.PLANTUML)
        val plantUmlSource = "@startuml\nA -> B: hi\n@enduml\n"

        val note = encodeDiagramNote(spec, plantUmlSource)

        assertTrue(note.contains("```plantuml"), "the fence language must match the spec's dialect; got:\n$note")
        val parsed = parseDiagramNote(note)
        assertEquals(DiagramDialect.PLANTUML, parsed?.dialect)
        assertEquals(plantUmlSource.trimEnd('\n'), parsed?.source?.trimEnd('\n'))
    }

    // ── Forward compatibility: unknown fields ignored, missing fields default ─────────────────

    @Test
    fun unknownJsonFieldsAreIgnoredAndMissingFieldsFallBackToDefaults() {
        val note = "<!-- indagium:diagram v1 {\"dialect\":\"mermaid\",\"totallyUnknownField\":{\"nested\":true}} -->\n" +
            "```mermaid\nsequenceDiagram\n```\n"

        val parsed = parseDiagramNote(note)

        assertTrue(parsed != null)
        assertEquals(DiagramDialect.MERMAID, parsed.spec.dialect)
        assertEquals(SeqDiagramSpec().title, parsed.spec.title, "a missing field must fall back to SeqDiagramSpec()'s own default")
        assertEquals(SeqDiagramSpec().options, parsed.spec.options)
        assertEquals(SeqDiagramSpec().range, parsed.spec.range)
    }

    // ── stripDiagramSpecHeader ────────────────────────────────────────────────────────────────

    @Test
    fun stripDiagramSpecHeaderRemovesOnlyTheHtmlCommentLeavingTheFence() {
        val note = encodeDiagramNote(fullSpec, source)

        val stripped = stripDiagramSpecHeader(note)

        assertFalse(stripped.contains("indagium:diagram"), "the header comment must be gone")
        assertTrue(stripped.startsWith("```mermaid"), "the fenced block itself must survive untouched")
    }

    @Test
    fun stripDiagramSpecHeaderIsANoOpOnAnOrdinaryNote() {
        val plain = "Just a regular note with nothing special about it."

        assertEquals(plain, stripDiagramSpecHeader(plain))
    }

    // ── Malformed input: every case returns null, never throws ─────────────────────────────────

    @Test
    fun parseDiagramNoteReturnsNullForAPlainUserWrittenNote() {
        assertNull(parseDiagramNote("Just some notes about the crash, nothing structured here."))
    }

    @Test
    fun parseDiagramNoteReturnsNullForAnEmptyString() {
        assertNull(parseDiagramNote(""))
    }

    @Test
    fun parseDiagramNoteReturnsNullWhenTheHeaderPayloadIsNotAJsonObject() {
        // The hand-rolled Json parser (debug/Json.kt) is deliberately lenient about malformed
        // object bodies — it degrades to weird-but-non-throwing partial maps rather than raising —
        // so the one case that reliably fails cleanly is the payload not being a JSON OBJECT at
        // all (here: a bare JSON string). The `as? Map<String, Any?>` cast fails without an
        // exception, and that's what parseDiagramNote must turn into null.
        val notAnObject = "<!-- indagium:diagram v1 \"just a string, not an object\" -->\n```mermaid\nsequenceDiagram\n```\n"
        assertNull(parseDiagramNote(notAnObject))
    }

    @Test
    fun parseDiagramNoteReturnsNullWhenTheHeaderIsNeverClosed() {
        val truncated = "<!-- indagium:diagram v1 {\"dialect\":\"mermaid\"} \n```mermaid\nsequenceDiagram\n```\n"
        assertNull(parseDiagramNote(truncated))
    }

    @Test
    fun parseDiagramNoteReturnsNullWhenTheFenceIsMissingEntirely() {
        val noFence = "<!-- indagium:diagram v1 {\"dialect\":\"mermaid\"} -->\nJust some text, no code fence at all."
        assertNull(parseDiagramNote(noFence))
    }

    @Test
    fun parseDiagramNoteReturnsNullWhenTheFenceIsNeverClosed() {
        val unclosedFence = "<!-- indagium:diagram v1 {\"dialect\":\"mermaid\"} -->\n```mermaid\nsequenceDiagram\n  A->>B: hi\n"
        assertNull(parseDiagramNote(unclosedFence))
    }

    @Test
    fun parseDiagramNoteReturnsNullWhenTheFenceLanguageDoesNotMatchTheDeclaredDialect() {
        // dialect says mermaid, but the fence is a plantuml block — not a well-formed pairing.
        val mismatched = "<!-- indagium:diagram v1 {\"dialect\":\"mermaid\"} -->\n```plantuml\n@startuml\n@enduml\n```\n"
        assertNull(parseDiagramNote(mismatched))
    }

    @Test
    fun parseDiagramNoteReturnsNullForAnUnsupportedFutureVersion() {
        val futureVersion = "<!-- indagium:diagram v2 {\"dialect\":\"mermaid\"} -->\n```mermaid\nsequenceDiagram\n```\n"
        assertNull(parseDiagramNote(futureVersion))
    }

    @Test
    fun parseDiagramNoteHandlesAnEmptyFencedBlockWithoutThrowing() {
        // A degenerate "immediately closed" fence — this encoder never produces one (source is
        // always non-empty rendered text) but a hand-edited/corrupted note could. Must not throw;
        // null (treated as an ordinary text note) is the only acceptable outcome either way.
        val emptyFence = "<!-- indagium:diagram v1 {\"dialect\":\"mermaid\"} -->\n```mermaid\n```\n"
        assertNull(parseDiagramNote(emptyFence), "no content line between the fences reads as 'no closing fence found', not a crash")
    }

    // ── The carried model ────────────────────────────────────────────────────────────────────

    @Test
    fun theBuiltModelRoundTripsThroughTheHeaderSoAReopenedNoteCanStillBeDrawnAndClicked() {
        // entryId is the field that matters most: no dialect's syntax can express it, so losing it
        // on a round trip would silently break click-an-arrow-to-jump for every reopened note.
        val participants = listOf(
            DiagramParticipant("User", "User", ParticipantKind.ACTOR, isEntryPoint = true),
            DiagramParticipant("BT", "BluetoothAdapter", ParticipantKind.TAG, tag = "BT"),
        )
        val model = SeqDiagram(
            spec = fullSpec.copy(participants = participants),
            participants = participants,
            messages = listOf(
                DiagramMessage(0, 1, "enable() requested", entryId = 12040, ts = "10:00:01.500", level = LogLevel.I, kind = MessageKind.CALL),
                DiagramMessage(1, 1, "STATE_TURNING_ON", entryId = 12041, ts = "10:00:01.700", level = LogLevel.E, kind = MessageKind.SELF, repeatCount = 3),
            ),
            frames = listOf(DiagramFrame("enable", 0x33AABBCC, 0, 1, 0)),
            notes = listOf(DiagramNoteMark(1, 1, "adapter refused", isError = true)),
            truncated = true,
            scannedEntries = 831,
        )

        val parsed = assertNotNull(parseDiagramNote(encodeDiagramNote(model.spec, "sequenceDiagram\n  User->>BT: go", model)))
        val back = assertNotNull(parsed.model, "the header must carry the model back")

        assertEquals(model.participants, back.participants)
        assertEquals(model.messages, back.messages)
        assertEquals(listOf(12040, 12041), back.messages.map { it.entryId })
        assertEquals(model.frames, back.frames)
        assertEquals(model.notes, back.notes)
        assertEquals(true, back.truncated)
        assertEquals(831, back.scannedEntries)
        assertEquals(parsed.spec, back.spec, "the model's spec is threaded back from the header, not stored twice")
    }

    @Test
    fun aDiagramNoteWithNoCarriedModelStillParsesAsADiagramNote() {
        // Notes written before the model was carried (and hand-authored ones) must keep working:
        // they show their fenced source and export correctly, they just can't be drawn until
        // regenerated. A null model must never demote the note to plain text.
        val encoded = encodeDiagramNote(fullSpec, "sequenceDiagram\n  A->>B: hi", model = null)

        val parsed = assertNotNull(parseDiagramNote(encoded))

        assertNull(parsed.model)
        assertEquals("sequenceDiagram\n  A->>B: hi", parsed.source)
        assertEquals(fullSpec.title, parsed.spec.title)
    }

    @Test
    fun aMalformedModelRecordDegradesToNoModelRatherThanFailingTheWholeParse() {
        // Same posture the rest of this codec holds: one bad sub-record must not cost the user the
        // spec (and therefore the ability to regenerate).
        val note = "<!-- indagium:diagram v1 {\"dialect\":\"mermaid\",\"title\":\"T\",\"model\":{\"participants\":[]}} -->\n" +
            "```mermaid\nsequenceDiagram\n```\n"

        val parsed = assertNotNull(parseDiagramNote(note))

        assertNull(parsed.model, "a model with no participants is not renderable and must read as absent")
        assertEquals("T", parsed.spec.title, "the spec must survive a bad model record")
    }
}
