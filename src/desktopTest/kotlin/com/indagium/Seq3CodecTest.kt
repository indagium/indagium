package com.indagium

import com.indagium.debug.Json
import com.indagium.diagram3.Seq3AttachmentMetadata
import com.indagium.diagram3.Seq3AttachmentMode
import com.indagium.diagram3.Seq3Authoring
import com.indagium.diagram3.Seq3Capture
import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Note
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.diagram3.parseSeq3Note
import com.indagium.diagram3.seq3SourceHash
import com.indagium.diagram3.stripSeq3NoteHeader
import com.indagium.diagram3.toMermaid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Seq3CodecTest {
    private fun fixedDocument(): Seq3Document {
        val a = Seq3Lifeline("A", "Alpha", setOf("A"), 0)
        val b = Seq3Lifeline("B", "Beta", setOf("B"), 1)
        val occurrence = Seq3Occurrence(
            entryId = 42,
            timestampMillis = 12_345L,
            rawTimestamp = "10:00:00.000",
            pid = 7,
            tid = 11,
            level = 'E',
            text = "push deviceKey=abc123",
            captureValues = mapOf("deviceKey" to "abc123"),
            visibility = Seq3Visibility.HIDDEN,
        )
        val message = Seq3Message(
            id = "m1",
            match = Seq3Match("A", "push deviceKey={deviceKey}", listOf(Seq3Capture("deviceKey", Seq3CaptureSource.NAMED_VALUE))),
            fromLifelineId = "A",
            toLifelineId = "B",
            labelTemplate = "push deviceKey={deviceKey}",
            kind = Seq3Kind.CALL,
            repeat = Seq3Repeat.COLLAPSE_ABOVE,
            repeatThreshold = 3,
            visibility = Seq3Visibility.VISIBLE,
            authoring = Seq3Authoring.EDITED,
            movedOutFromMessageId = "origin",
            occurrences = listOf(occurrence),
        )
        return Seq3Document(
            title = "My diagram",
            sourceFile = "app.log",
            lifelines = listOf(a, b),
            messages = listOf(message),
            fragments = listOf(Seq3Fragment("f1", Seq3FragmentKind.LOOP, "retry", listOf("m1"))),
            notes = listOf(Seq3Note("n1", "watch this", listOf("m1"))),
        )
    }

    @Test
    fun roundTripsACompleteDocumentThroughEncodeAndParse() {
        val original = fixedDocument()
        val text = encodeSeq3Note(original, Seq3Dialect.MERMAID)

        val parsed = parseSeq3Note(text)
        assertNotNull(parsed)
        assertEquals(original, parsed.document)
        assertTrue(parsed.sourceHashMatches)
        assertNull(parsed.warning)
        assertEquals(Seq3Dialect.MERMAID, parsed.dialect)
    }

    @Test
    fun lifelineVisibilityRoundTripsAndOldDefaultRemainsVisible() {
        val original = fixedDocument().copy(
            lifelines = fixedDocument().lifelines.mapIndexed { index, lifeline ->
                if (index == 1) lifeline.copy(visibility = Seq3Visibility.HIDDEN) else lifeline
            },
        )
        val parsed = parseSeq3Note(encodeSeq3Note(original))
        assertNotNull(parsed)
        assertEquals(Seq3Visibility.HIDDEN, parsed.document.lifelines.single { it.id == "B" }.visibility)

        val legacyMap = mapOf(
            "lifelines" to listOf(mapOf("id" to "A", "name" to "A", "tagIds" to listOf("A"), "ordinal" to 0)),
            "messages" to emptyList<Any?>(),
            "fragments" to emptyList<Any?>(),
            "notes" to emptyList<Any?>(),
        )
        val source = "sequenceDiagram\n"
        val header = mapOf("dialect" to "mermaid", "sourceHash" to seq3SourceHash(source), "document" to legacyMap)
        val legacyText = "<!-- indagium:diagram3 v1 ${Json.encode(header)} -->\n```mermaid\n$source```\n"
        val legacy = parseSeq3Note(legacyText)
        assertNotNull(legacy)
        assertEquals(Seq3Visibility.VISIBLE, legacy.document.lifelines.single().visibility)
    }

    @Test
    fun stripSeq3NoteHeaderLeavesOnlyTheFencedBody() {
        val text = encodeSeq3Note(fixedDocument())
        val stripped = stripSeq3NoteHeader(text)

        assertFalse(stripped.contains("indagium:diagram3"))
        assertTrue(stripped.startsWith("```mermaid"))
    }

    @Test
    fun aPlainTextNoteIsNotADiagramNote() {
        assertNull(parseSeq3Note("Just a regular note about something I saw in the log."))
    }

    @Test
    fun aTruncatedHeaderIsRejectedRatherThanThrowing() {
        val text = encodeSeq3Note(fixedDocument())
        val cutMidHeader = text.substring(0, text.length / 3) // well before the closing "-->"
        assertNull(parseSeq3Note(cutMidHeader))
    }

    @Test
    fun aHeaderWithNoFenceAfterItIsRejected() {
        val text = encodeSeq3Note(fixedDocument())
        val headerOnly = text.substringBefore("```")
        assertNull(parseSeq3Note(headerOnly))
    }

    @Test
    fun sourceHashMismatchSetsAWarningButStillReturnsTheDocument() {
        val text = encodeSeq3Note(fixedDocument())
        // Splice an extra line into the fenced body WITHOUT touching the header's declared hash,
        // exactly the "hand-edited fence" scenario this warning exists for.
        val tampered = text.replaceFirst("sequenceDiagram\n", "sequenceDiagram\n    Note over A: tampered\n")

        val parsed = parseSeq3Note(tampered)
        assertNotNull(parsed)
        assertFalse(parsed.sourceHashMatches)
        assertNotNull(parsed.warning)
        assertEquals(fixedDocument(), parsed.document) // the header's model is untouched by the tamper
    }

    @Test
    fun tooManyMessagesIsRejectedAtDecode() {
        // Deliberately minimal per-message maps (just the fields messageFromMap strictly requires)
        // so this isolates the MESSAGE-COUNT bound from the separate header-size bound — 5,001 of
        // these still lands well under MAX_SEQ3_HEADER_CHARS, so a size-only implementation
        // wouldn't catch this the way an actual count check must.
        val messages = (0 until 5_001).map { i -> mapOf("id" to "m$i", "match" to mapOf("template" to "x"), "fromLifelineId" to "A") }
        val documentMap = mapOf("lifelines" to emptyList<Any?>(), "messages" to messages, "fragments" to emptyList<Any?>(), "notes" to emptyList<Any?>())
        val source = "sequenceDiagram\n"
        val header = mapOf("dialect" to "mermaid", "sourceHash" to seq3SourceHash(source), "document" to documentMap)
        val text = "<!-- indagium:diagram3 v1 ${Json.encode(header)} -->\n```mermaid\n$source```\n"
        assertTrue(Json.encode(header).length < 512 * 1024, "test setup sanity: header must stay under the size bound so only the count bound is exercised")

        assertNull(parseSeq3Note(text), "a document declaring more than the message cap must be rejected, not silently truncated")
    }

    @Test
    fun anOverlongTitleIsBoundedRatherThanAcceptedVerbatim() {
        val hugeTitle = "x".repeat(20_000) // over MAX_SEQ3_STRING_CHARS (16 KiB)
        val documentMap = mapOf(
            "title" to hugeTitle,
            "lifelines" to emptyList<Any?>(),
            "messages" to emptyList<Any?>(),
            "fragments" to emptyList<Any?>(),
            "notes" to emptyList<Any?>(),
        )
        val source = "sequenceDiagram\n"
        val header = mapOf("dialect" to "mermaid", "sourceHash" to seq3SourceHash(source), "document" to documentMap)
        val text = "<!-- indagium:diagram3 v1 ${Json.encode(header)} -->\n```mermaid\n$source```\n"

        val parsed = parseSeq3Note(text)
        assertNotNull(parsed)
        assertTrue(parsed.document.title.length < hugeTitle.length, "an over-bound string must never pass through verbatim")
    }

    @Test
    fun aFutureVersionHeaderIsRejected() {
        val text = encodeSeq3Note(fixedDocument()).replaceFirst("indagium:diagram3 v1", "indagium:diagram3 v2")
        assertNull(parseSeq3Note(text))
    }

    @Test
    fun encodedSourceMatchesTheDocumentsOwnMermaidRendering() {
        val doc = fixedDocument()
        val text = encodeSeq3Note(doc, Seq3Dialect.MERMAID)
        val parsed = parseSeq3Note(text)
        assertNotNull(parsed)
        assertEquals(doc.toMermaid().trimEnd('\n'), parsed.source) // encode normalizes the trailing newline — see encodeSeq3Note's own doc
    }

    @Test
    fun attachmentMetadataRoundTripsAndSurvivesNoteMetadataEdits() {
        val attachment = Seq3AttachmentMetadata(
            diagramId = "diagram-42",
            mode = Seq3AttachmentMode.LINKED,
            revision = 17L,
            attachedAtEpochMs = 1234L,
        )
        val text = encodeSeq3Note(fixedDocument(), attachment = attachment)

        val parsed = parseSeq3Note(text)
        assertNotNull(parsed)
        assertEquals(attachment, parsed.attachment)

        val captionUpdated = com.indagium.diagram3.updateSeq3NoteCaption(text, "kept link")
        val reparsed = parseSeq3Note(captionUpdated!!)
        assertNotNull(reparsed)
        assertEquals(attachment, reparsed.attachment)
        assertEquals("kept link", reparsed.caption)
    }
}
