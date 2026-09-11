package com.indagium

import com.indagium.debug.Json
import com.indagium.diagram3.DiagramExportMode
import com.indagium.diagram3.Seq3AttachmentMetadata
import com.indagium.diagram3.Seq3AttachmentMode
import com.indagium.diagram3.Seq3Authoring
import com.indagium.diagram3.Seq3Capture
import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.Seq3Delay
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3LifelineKind
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Note
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Operand
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.adoptSeq3NoteSource
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.diagram3.parseSeq3Note
import com.indagium.diagram3.seq3NoteHasHandEdit
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

    // ── WP12: adoptSeq3NoteSource — the user's way out of the drift warning above ───────────────
    // These are the natural counterpart of sourceHashMismatchSetsAWarningButStillReturnsTheDocument:
    // that test tampers a fence and checks the warning appears; these tamper a fence the same way
    // and check that adopting it clears the warning while keeping the hand-edited text verbatim.

    @Test
    fun adoptingADriftedNoteClearsTheSourceHashWarningOnReparse() {
        val text = encodeSeq3Note(fixedDocument())
        val tampered = text.replaceFirst("sequenceDiagram\n", "sequenceDiagram\n    Note over A: tampered\n")
        val parsedBeforeAdopt = parseSeq3Note(tampered)
        assertNotNull(parsedBeforeAdopt)
        assertFalse(parsedBeforeAdopt.sourceHashMatches, "test setup sanity: the tamper must actually trip the warning")

        val adopted = adoptSeq3NoteSource(tampered)
        assertNotNull(adopted)
        val reparsed = parseSeq3Note(adopted)
        assertNotNull(reparsed)
        assertTrue(reparsed.sourceHashMatches, "adopting must make the declared hash match the fence it was adopted from")
        assertNull(reparsed.warning)
    }

    @Test
    fun adoptingPreservesTheHandEditedFenceBodyByteForByte() {
        val text = encodeSeq3Note(fixedDocument())
        // A deliberately odd body — mixed indentation, a trailing-space line, a blank line in the
        // middle — so a byte-for-byte comparison actually exercises more than "still has words in
        // it". This is the whole point of adoptSeq3NoteSource: the text the user wrote must survive
        // completely untouched, not just "close enough to re-render".
        val tamperedFence = "sequenceDiagram\n    Note over A: tampered  \n\n  A->>B: hi\n"
        val tampered = text.replaceFirst(Regex("sequenceDiagram\\n(.|\\n)*?(?=```\\n)"), tamperedFence)
        val parsedBeforeAdopt = parseSeq3Note(tampered)
        assertNotNull(parsedBeforeAdopt)
        assertFalse(parsedBeforeAdopt.sourceHashMatches, "test setup sanity: the tamper must actually trip the warning")
        // encodeSeq3Note trims trailing newlines off the fenced body before embedding it (see its
        // own doc) — parsedBeforeAdopt.source already reflects that normalization, so compare
        // against IT rather than tamperedFence's own raw trailing newline.
        val expectedBody = parsedBeforeAdopt.source

        val adopted = adoptSeq3NoteSource(tampered)
        assertNotNull(adopted)
        val reparsed = parseSeq3Note(adopted)
        assertNotNull(reparsed)
        assertEquals(expectedBody, reparsed.source, "the hand-edited fence body must survive adoption byte for byte")
    }

    @Test
    fun adoptingSwitchesTheNoteToSourceExportMode() {
        val text = encodeSeq3Note(fixedDocument(), exportMode = DiagramExportMode.IMAGE)
        val tampered = text.replaceFirst("sequenceDiagram\n", "sequenceDiagram\n    Note over A: tampered\n")

        val adopted = adoptSeq3NoteSource(tampered)
        assertNotNull(adopted)
        val reparsed = parseSeq3Note(adopted)
        assertNotNull(reparsed)
        // The rendered picture still reflects the untouched document, so leaving exportMode at
        // IMAGE would keep showing a picture that disagrees with the adopted text — see
        // adoptSeq3NoteSource's own doc for why SOURCE is the only self-consistent outcome.
        assertEquals(DiagramExportMode.SOURCE, reparsed.exportMode)
    }

    @Test
    fun adoptingPreservesDocumentDialectCaptionAndAttachmentUnchanged() {
        val attachment = Seq3AttachmentMetadata(
            diagramId = "diagram-42",
            mode = Seq3AttachmentMode.LINKED,
            revision = 17L,
            attachedAtEpochMs = 1234L,
        )
        val text = encodeSeq3Note(
            fixedDocument(),
            dialect = Seq3Dialect.PLANTUML,
            caption = "kept caption",
            attachment = attachment,
        )
        val tampered = text.replaceFirst("@startuml\n", "@startuml\nnote over A: tampered\n")

        val adopted = adoptSeq3NoteSource(tampered)
        assertNotNull(adopted)
        val reparsed = parseSeq3Note(adopted)
        assertNotNull(reparsed)
        assertEquals(fixedDocument(), reparsed.document)
        assertEquals(Seq3Dialect.PLANTUML, reparsed.dialect)
        assertEquals("kept caption", reparsed.caption)
        assertEquals(attachment, reparsed.attachment)
    }

    @Test
    fun adoptSeq3NoteSourceReturnsNullForAnUnparseableNote() {
        assertNull(adoptSeq3NoteSource("just a plain text note, not a diagram note at all"))
    }

    // ── WP14: seq3NoteHasHandEdit — the one predicate confirm()/syncLiveLinkedNote/the MCP route
    // now all share to decide "has this fence drifted". Exercises exactly the three cases its own
    // KDoc calls out: a genuinely drifted note, an intact one, and something that isn't a diagram
    // note at all (where the deliberate `?. + == false` shape, not `!= true`, matters).

    @Test
    fun seq3NoteHasHandEditIsTrueForATamperedFence() {
        val text = encodeSeq3Note(fixedDocument())
        val tampered = text.replaceFirst("sequenceDiagram\n", "sequenceDiagram\n    Note over A: tampered\n")

        assertTrue(seq3NoteHasHandEdit(tampered))
    }

    @Test
    fun seq3NoteHasHandEditIsFalseForAnUntamperedNote() {
        val text = encodeSeq3Note(fixedDocument())

        assertFalse(seq3NoteHasHandEdit(text))
    }

    @Test
    fun seq3NoteHasHandEditIsFalseForTextThatIsNotADiagramNoteAtAll() {
        // The deliberate shape this function's own KDoc calls out: an unparseable note has no
        // fence/hash pair to have drifted, so it must read as "not a hand edit" — `?. + == false`,
        // not `!= true` (which would make `null != true` evaluate to true here and wrongly flag
        // every ordinary Note).
        assertFalse(seq3NoteHasHandEdit("just a plain text note, not a diagram note at all"))
        assertFalse(seq3NoteHasHandEdit(""))
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

    // ── WP1: lifeline identity / fragment-note visibility / diagram-theme fields ────────────────

    @Test
    fun everyNewWp1FieldSurvivesAnEncodeDecodeRoundTrip() {
        val original = fixedDocument().copy(
            lifelines = listOf(
                Seq3Lifeline("A", "Alpha", setOf("A"), 0, kind = Seq3LifelineKind.ACTOR, displaySegments = 2),
                Seq3Lifeline("B", "Beta", setOf("B"), 1, kind = Seq3LifelineKind.PARTICIPANT, displaySegments = null),
            ),
            fragments = listOf(Seq3Fragment("f1", Seq3FragmentKind.LOOP, "retry", listOf("m1"), visibility = Seq3Visibility.HIDDEN)),
            notes = listOf(Seq3Note("n1", "watch this", listOf("m1"), visibility = Seq3Visibility.HIDDEN)),
            lifelineDisplaySegments = 3,
            themePresetName = "DRACULA",
        )

        val parsed = parseSeq3Note(encodeSeq3Note(original))

        assertNotNull(parsed)
        assertEquals(original, parsed.document)
        val alpha = parsed.document.lifelines.single { it.id == "A" }
        assertEquals(Seq3LifelineKind.ACTOR, alpha.kind)
        assertEquals(2, alpha.displaySegments)
        val beta = parsed.document.lifelines.single { it.id == "B" }
        assertEquals(Seq3LifelineKind.PARTICIPANT, beta.kind)
        assertNull(beta.displaySegments)
        assertEquals(Seq3Visibility.HIDDEN, parsed.document.fragments.single().visibility)
        assertEquals(Seq3Visibility.HIDDEN, parsed.document.notes.single().visibility)
        assertEquals(3, parsed.document.lifelineDisplaySegments)
        assertEquals("DRACULA", parsed.document.themePresetName)
    }

    // ── WP10 (item 7): inline call numbering / timestamps ───────────────────────────────────────

    @Test
    fun showSequenceNumbersAndShowTimestampsRoundTripThroughEncodeAndParse() {
        val original = fixedDocument().copy(showSequenceNumbers = true, showTimestamps = true)

        val parsed = parseSeq3Note(encodeSeq3Note(original))

        assertNotNull(parsed)
        assertEquals(original, parsed.document)
        assertTrue(parsed.document.showSequenceNumbers)
        assertTrue(parsed.document.showTimestamps)
    }

    @Test
    fun aDocumentMissingTheWp10TogglesDecodesToBothFalse() {
        // A note saved by a build predating WP10 has neither key at all.
        val legacyMap = mapOf(
            "lifelines" to listOf(mapOf("id" to "A", "name" to "A", "tagIds" to listOf("A"), "ordinal" to 0)),
            "messages" to emptyList<Any?>(),
            "fragments" to emptyList<Any?>(),
            "notes" to emptyList<Any?>(),
        )
        val source = "sequenceDiagram\n"
        val header = mapOf("dialect" to "mermaid", "sourceHash" to seq3SourceHash(source), "document" to legacyMap)
        val legacyText = "<!-- indagium:diagram3 v1 ${Json.encode(header)} -->\n```mermaid\n$source```\n"

        val parsed = parseSeq3Note(legacyText)

        assertNotNull(parsed)
        assertFalse(parsed.document.showSequenceNumbers)
        assertFalse(parsed.document.showTimestamps)
    }

    // ── WP1: activation-bars document toggle ────────────────────────────────────────────────────

    @Test
    fun showActivationsRoundTripsThroughEncodeAndParse() {
        val original = fixedDocument().copy(showActivations = true)

        val parsed = parseSeq3Note(encodeSeq3Note(original))

        assertNotNull(parsed)
        assertEquals(original, parsed.document)
        assertTrue(parsed.document.showActivations)
    }

    @Test
    fun aDocumentMissingTheWp1ActivationsKeyDecodesToFalse() {
        // A note saved by a build predating WP1 has no "showActivations" key at all.
        val legacyMap = mapOf(
            "lifelines" to listOf(mapOf("id" to "A", "name" to "A", "tagIds" to listOf("A"), "ordinal" to 0)),
            "messages" to emptyList<Any?>(),
            "fragments" to emptyList<Any?>(),
            "notes" to emptyList<Any?>(),
        )
        val source = "sequenceDiagram\n"
        val header = mapOf("dialect" to "mermaid", "sourceHash" to seq3SourceHash(source), "document" to legacyMap)
        val legacyText = "<!-- indagium:diagram3 v1 ${Json.encode(header)} -->\n```mermaid\n$source```\n"

        val parsed = parseSeq3Note(legacyText)

        assertNotNull(parsed)
        assertFalse(parsed.document.showActivations)
    }

    // ── WP15: elapsed-tag document toggle ───────────────────────────────────────────────────────

    @Test
    fun showElapsedRoundTripsThroughEncodeAndParse() {
        val original = fixedDocument().copy(showElapsed = true)

        val parsed = parseSeq3Note(encodeSeq3Note(original))

        assertNotNull(parsed)
        assertEquals(original, parsed.document)
        assertTrue(parsed.document.showElapsed)
    }

    @Test
    fun aDocumentMissingTheWp15ElapsedKeyDecodesToFalse() {
        // A note saved by a build predating WP15 has no "showElapsed" key at all.
        val legacyMap = mapOf(
            "lifelines" to listOf(mapOf("id" to "A", "name" to "A", "tagIds" to listOf("A"), "ordinal" to 0)),
            "messages" to emptyList<Any?>(),
            "fragments" to emptyList<Any?>(),
            "notes" to emptyList<Any?>(),
        )
        val source = "sequenceDiagram\n"
        val header = mapOf("dialect" to "mermaid", "sourceHash" to seq3SourceHash(source), "document" to legacyMap)
        val legacyText = "<!-- indagium:diagram3 v1 ${Json.encode(header)} -->\n```mermaid\n$source```\n"

        val parsed = parseSeq3Note(legacyText)

        assertNotNull(parsed)
        assertFalse(parsed.document.showElapsed)
    }

    // ── Delay (WP11) ─────────────────────────────────────────────────────────────────────────

    @Test
    fun delaysRoundTripThroughEncodeAndParse() {
        val original = fixedDocument().copy(
            delays = listOf(
                Seq3Delay("d1", afterMessageId = "m1", label = "5 minutes later", afterOccurrenceEntryId = 7),
                Seq3Delay("d2", afterMessageId = "m1", label = "hidden gap", visibility = Seq3Visibility.HIDDEN),
            ),
        )

        val parsed = parseSeq3Note(encodeSeq3Note(original))

        assertNotNull(parsed)
        assertEquals(original, parsed.document)
        assertEquals(original.delays, parsed.document.delays)
        assertEquals(Seq3Visibility.HIDDEN, parsed.document.delays.single { it.id == "d2" }.visibility)
        assertEquals(7, parsed.document.delays.single { it.id == "d1" }.afterOccurrenceEntryId)
        assertNull(parsed.document.delays.single { it.id == "d2" }.afterOccurrenceEntryId)
    }

    @Test
    fun aDelayMissingTheAfterOccurrenceEntryIdKeyDecodesToNull() {
        // A delay saved by a build predating this field has no "afterOccurrenceEntryId" key at
        // all — must fall back to null ("after the last occurrence"), the field's own pre-existing
        // default, rather than throwing. Same "hand-built legacy map" shape as
        // aDocumentMissingTheDelaysKeyDecodesToAnEmptyList above.
        val legacyDelayMap = mapOf("id" to "d1", "afterMessageId" to "m1", "label" to "gap", "visibility" to "VISIBLE")
        val legacyMap = mapOf(
            "lifelines" to listOf(mapOf("id" to "A", "name" to "A", "tagIds" to listOf("A"), "ordinal" to 0)),
            "messages" to emptyList<Any?>(),
            "fragments" to emptyList<Any?>(),
            "notes" to emptyList<Any?>(),
            "delays" to listOf(legacyDelayMap),
        )
        val source = "sequenceDiagram\n"
        val header = mapOf("dialect" to "mermaid", "sourceHash" to seq3SourceHash(source), "document" to legacyMap)
        val legacyText = "<!-- indagium:diagram3 v1 ${Json.encode(header)} -->\n```mermaid\n$source```\n"

        val parsed = parseSeq3Note(legacyText)

        assertNotNull(parsed)
        assertNull(parsed.document.delays.single().afterOccurrenceEntryId)
    }

    @Test
    fun aDocumentMissingTheDelaysKeyDecodesToAnEmptyList() {
        // A note saved by a build predating WP11 has no "delays" key at all.
        val legacyMap = mapOf(
            "lifelines" to listOf(mapOf("id" to "A", "name" to "A", "tagIds" to listOf("A"), "ordinal" to 0)),
            "messages" to emptyList<Any?>(),
            "fragments" to emptyList<Any?>(),
            "notes" to emptyList<Any?>(),
        )
        val source = "sequenceDiagram\n"
        val header = mapOf("dialect" to "mermaid", "sourceHash" to seq3SourceHash(source), "document" to legacyMap)
        val legacyText = "<!-- indagium:diagram3 v1 ${Json.encode(header)} -->\n```mermaid\n$source```\n"

        val parsed = parseSeq3Note(legacyText)

        assertNotNull(parsed)
        assertTrue(parsed.document.delays.isEmpty())
    }

    @Test
    fun aDocumentMissingEveryNewWp1KeyDecodesToTheDocumentedDefaults() {
        // Hand-built map with every WP1-added key entirely absent — the shape a note saved by a
        // build predating this phase would have. Every new field must fall back to the default
        // that preserves that old document's original, unshortened, unthemed rendering.
        val legacyMap = mapOf(
            "lifelines" to listOf(mapOf("id" to "A", "name" to "A", "tagIds" to listOf("A"), "ordinal" to 0)),
            "messages" to emptyList<Any?>(),
            "fragments" to listOf(mapOf("id" to "f1", "kind" to "LOOP", "label" to "retry", "messageIds" to listOf<String>())),
            "notes" to listOf(mapOf("id" to "n1", "text" to "hi", "messageIds" to listOf<String>())),
        )
        val source = "sequenceDiagram\n"
        val header = mapOf("dialect" to "mermaid", "sourceHash" to seq3SourceHash(source), "document" to legacyMap)
        val legacyText = "<!-- indagium:diagram3 v1 ${Json.encode(header)} -->\n```mermaid\n$source```\n"

        val parsed = parseSeq3Note(legacyText)

        assertNotNull(parsed)
        val lifeline = parsed.document.lifelines.single()
        assertEquals(Seq3LifelineKind.PARTICIPANT, lifeline.kind)
        assertNull(lifeline.displaySegments)
        assertEquals(Seq3Visibility.VISIBLE, parsed.document.fragments.single().visibility)
        assertEquals(Seq3Visibility.VISIBLE, parsed.document.notes.single().visibility)
        assertEquals(0, parsed.document.lifelineDisplaySegments)
        assertNull(parsed.document.themePresetName)
    }

    @Test
    fun aLifelineWithADeclaredButEmptyTagIdsListBackfillsToItsOwnName() {
        // Exactly the shape a manual lifeline created before the item-8 merge fix left on disk:
        // the key is present, but decodes to an empty set. An absent key still falls back to the
        // lifeline's id (unchanged legacy behaviour) — only a genuinely empty DECODED set heals to
        // the name, per lifelineFromMap's own doc.
        val legacyMap = mapOf(
            "lifelines" to listOf(
                mapOf("id" to "manual-1", "name" to "Manual Actor", "tagIds" to emptyList<String>(), "ordinal" to 0),
                mapOf("id" to "no-key", "name" to "No Key", "ordinal" to 1),
            ),
            "messages" to emptyList<Any?>(),
            "fragments" to emptyList<Any?>(),
            "notes" to emptyList<Any?>(),
        )
        val source = "sequenceDiagram\n"
        val header = mapOf("dialect" to "mermaid", "sourceHash" to seq3SourceHash(source), "document" to legacyMap)
        val legacyText = "<!-- indagium:diagram3 v1 ${Json.encode(header)} -->\n```mermaid\n$source```\n"

        val parsed = parseSeq3Note(legacyText)

        assertNotNull(parsed)
        assertEquals(setOf("Manual Actor"), parsed.document.lifelines.single { it.id == "manual-1" }.tagIds)
        assertEquals(setOf("no-key"), parsed.document.lifelines.single { it.id == "no-key" }.tagIds)
    }

    // ── Fragment kinds (WP12) ────────────────────────────────────────────────────────────────

    @Test
    fun everyNewWp12FragmentKindRoundTripsThroughEncodeAndParse() {
        listOf(Seq3FragmentKind.CRITICAL, Seq3FragmentKind.BREAK, Seq3FragmentKind.GROUP).forEach { kind ->
            val original = fixedDocument().copy(
                fragments = listOf(Seq3Fragment("f1", kind, "label", listOf("m1"))),
            )
            val parsed = parseSeq3Note(encodeSeq3Note(original))
            assertNotNull(parsed)
            assertEquals(original, parsed.document)
            assertEquals(kind, parsed.document.fragments.single().kind)
        }
    }

    @Test
    fun everyNewWp11FragmentKindRoundTripsThroughEncodeAndParse() {
        // Same proof as everyNewWp12FragmentKindRoundTripsThroughEncodeAndParse just above — no
        // codec change was needed for WP11 either, since Seq3Codec's fragmentFromMap/fragmentToMap
        // already decode/encode `kind` generically by name (enumFromName) for every
        // Seq3FragmentKind, not just the ones that existed when that code was written.
        listOf(Seq3FragmentKind.NEG, Seq3FragmentKind.STRICT, Seq3FragmentKind.CONSIDER, Seq3FragmentKind.IGNORE).forEach { kind ->
            val original = fixedDocument().copy(
                fragments = listOf(Seq3Fragment("f1", kind, "label", listOf("m1"))),
            )
            val parsed = parseSeq3Note(encodeSeq3Note(original))
            assertNotNull(parsed)
            assertEquals(original, parsed.document)
            assertEquals(kind, parsed.document.fragments.single().kind)
        }
    }

    @Test
    fun lostAndFoundKindsRoundTripByName() {
        // No codec change needed for WP9 (Seq3Codec decodes `Seq3Kind` generically by name — see
        // this file's own `enumFromName` usage) — this test exists to PROVE that, not to exercise
        // new code. `toLifelineId` is forced to null on the way in: it's meaningless for LOST/
        // FOUND (Seq3Kind's own doc), so a realistic fixture for either kind never has one.
        listOf(Seq3Kind.LOST, Seq3Kind.FOUND).forEach { kind ->
            val original = fixedDocument().let { doc ->
                doc.copy(messages = doc.messages.map { it.copy(kind = kind, toLifelineId = null) })
            }
            val parsed = parseSeq3Note(encodeSeq3Note(original))
            assertNotNull(parsed)
            assertEquals(original, parsed.document)
            assertEquals(kind, parsed.document.messages.single().kind)
        }
    }

    @Test
    fun createAndDestroyKindsRoundTripByName() {
        // WP10: same proof as lostAndFoundKindsRoundTripByName just above — Seq3Codec decodes
        // Seq3Kind generically by name, so no codec change was needed for this work package
        // either. Unlike LOST/FOUND, CREATE/DESTROY keep fixedDocument()'s own real
        // `toLifelineId` UNCHANGED rather than forcing it null: it is NOT meaningless for these two
        // kinds (Seq3Kind's own doc) — the whole point of the feature is a genuine target lifeline
        // to construct/destroy, so a realistic fixture for either kind always has one.
        listOf(Seq3Kind.CREATE, Seq3Kind.DESTROY).forEach { kind ->
            val original = fixedDocument().let { doc ->
                doc.copy(messages = doc.messages.map { it.copy(kind = kind) })
            }
            val parsed = parseSeq3Note(encodeSeq3Note(original))
            assertNotNull(parsed)
            assertEquals(original, parsed.document)
            assertEquals(kind, parsed.document.messages.single().kind)
        }
    }

    @Test
    fun hideKindLabelRoundTripsThroughEncodeAndParse() {
        val original = fixedDocument().copy(
            fragments = listOf(
                Seq3Fragment("f1", Seq3FragmentKind.GROUP, "billing flow", listOf("m1"), hideKindLabel = true),
                Seq3Fragment("f2", Seq3FragmentKind.LOOP, "retry", listOf("m1"), hideKindLabel = false),
            ),
        )

        val parsed = parseSeq3Note(encodeSeq3Note(original))

        assertNotNull(parsed)
        assertEquals(original, parsed.document)
        assertTrue(parsed.document.fragments.single { it.id == "f1" }.hideKindLabel)
        assertFalse(parsed.document.fragments.single { it.id == "f2" }.hideKindLabel)
    }

    @Test
    fun aDocumentWithAnUnknownFragmentKindCoercesToLoopRatherThanFailingToParse() {
        // Exactly what an OLDER build sees if it opens a document a newer build saved with a kind
        // it doesn't know about yet (e.g. a hypothetical future addition, or corrupted text) —
        // Seq3FragmentKind's own enumFromName default keeps the whole document loadable.
        val legacyMap = mapOf(
            "lifelines" to listOf(mapOf("id" to "A", "name" to "A", "tagIds" to listOf("A"), "ordinal" to 0)),
            "messages" to emptyList<Any?>(),
            "fragments" to listOf(mapOf("id" to "f1", "kind" to "SOMETHING_FUTURE", "label" to "retry", "messageIds" to listOf<String>())),
            "notes" to emptyList<Any?>(),
        )
        val source = "sequenceDiagram\n"
        val header = mapOf("dialect" to "mermaid", "sourceHash" to seq3SourceHash(source), "document" to legacyMap)
        val legacyText = "<!-- indagium:diagram3 v1 ${Json.encode(header)} -->\n```mermaid\n$source```\n"

        val parsed = parseSeq3Note(legacyText)

        assertNotNull(parsed)
        assertEquals(Seq3FragmentKind.LOOP, parsed.document.fragments.single().kind)
    }

    @Test
    fun aDocumentMissingTheHideKindLabelKeyDecodesToFalse() {
        // A fragment saved by a build predating WP12 has no "hideKindLabel" key at all.
        val legacyMap = mapOf(
            "lifelines" to listOf(mapOf("id" to "A", "name" to "A", "tagIds" to listOf("A"), "ordinal" to 0)),
            "messages" to emptyList<Any?>(),
            "fragments" to listOf(mapOf("id" to "f1", "kind" to "LOOP", "label" to "retry", "messageIds" to listOf<String>())),
            "notes" to emptyList<Any?>(),
        )
        val source = "sequenceDiagram\n"
        val header = mapOf("dialect" to "mermaid", "sourceHash" to seq3SourceHash(source), "document" to legacyMap)
        val legacyText = "<!-- indagium:diagram3 v1 ${Json.encode(header)} -->\n```mermaid\n$source```\n"

        val parsed = parseSeq3Note(legacyText)

        assertNotNull(parsed)
        assertFalse(parsed.document.fragments.single().hideKindLabel)
    }

    // ── Fragment operands (WP4) ─────────────────────────────────────────────────────────────────

    @Test
    fun aFragmentWithTwoElseOperandsRoundTripsThroughEncodeAndParse() {
        val original = fixedDocument().copy(
            fragments = listOf(
                Seq3Fragment(
                    "f1",
                    Seq3FragmentKind.ALT,
                    "x > 0", // operand zero's guard — see Seq3Fragment.elseOperands' own doc
                    listOf("m1"),
                    elseOperands = listOf(
                        Seq3Operand("op1", "x == 0", startsAtMessageId = "m1", startsAtOccurrenceEntryId = 42),
                        Seq3Operand("op2", "x < 0", startsAtMessageId = "m1"),
                    ),
                ),
            ),
        )

        val parsed = parseSeq3Note(encodeSeq3Note(original))

        assertNotNull(parsed)
        assertEquals(original, parsed.document)
        val operands = parsed.document.fragments.single().elseOperands
        assertEquals(listOf("op1", "op2"), operands.map { it.id })
        assertEquals(42, operands.single { it.id == "op1" }.startsAtOccurrenceEntryId)
        assertNull(operands.single { it.id == "op2" }.startsAtOccurrenceEntryId)
    }

    @Test
    fun aFragmentMapWithNoElseOperandsKeyDecodesToAnEmptyListAndReEncodesIdentically() {
        // The most important test in this section: a fragment saved by any build before WP4 has
        // no "elseOperands" key at all. It must decode to emptyList() — one implicit operand whose
        // guard is the existing "label" — and re-encoding that parsed document must be byte-stable
        // (no spurious key materializes), matching Seq3Fragment.elseOperands' own "not a migration
        // case" doc.
        val legacyMap = mapOf(
            "lifelines" to listOf(mapOf("id" to "A", "name" to "A", "tagIds" to listOf("A"), "ordinal" to 0)),
            "messages" to emptyList<Any?>(),
            "fragments" to listOf(mapOf("id" to "f1", "kind" to "ALT", "label" to "x > 0", "messageIds" to listOf<String>())),
            "notes" to emptyList<Any?>(),
        )
        val source = "sequenceDiagram\n"
        val header = mapOf("dialect" to "mermaid", "sourceHash" to seq3SourceHash(source), "document" to legacyMap)
        val legacyText = "<!-- indagium:diagram3 v1 ${Json.encode(header)} -->\n```mermaid\n$source```\n"

        val parsed = parseSeq3Note(legacyText)

        assertNotNull(parsed)
        assertTrue(parsed.document.fragments.single().elseOperands.isEmpty())

        val reEncoded = encodeSeq3Note(parsed.document, Seq3Dialect.MERMAID)
        val reParsed = parseSeq3Note(reEncoded)
        assertNotNull(reParsed)
        assertEquals(parsed.document, reParsed.document, "re-encoding a legacy (no-elseOperands) document must not change what it decodes to")
        assertTrue(reParsed.document.fragments.single().elseOperands.isEmpty())
    }

    @Test
    fun anOperandMapMissingIdOrStartsAtMessageIdDropsThatOperandWithoutFailingTheDocument() {
        // Deliberate counterpart to aDocumentWithAnUnknownFragmentKindCoercesToLoopRatherThanFailingToParse
        // above: fragmentFromMap coerces an unrecognised "kind" to LOOP rather than dropping the
        // fragment (a fragment with a wrong kind is still a fragment worth drawing), but
        // operandFromMap drops a malformed OPERAND outright — an anchor-less operand is nothing at
        // all, there is no position at which to draw its divider.
        val legacyMap = mapOf(
            "lifelines" to listOf(mapOf("id" to "A", "name" to "A", "tagIds" to listOf("A"), "ordinal" to 0)),
            "messages" to emptyList<Any?>(),
            "fragments" to listOf(
                mapOf(
                    "id" to "f1",
                    "kind" to "ALT",
                    "label" to "x > 0",
                    "messageIds" to listOf<String>(),
                    "elseOperands" to listOf(
                        mapOf("id" to "op-missing-anchor", "guard" to "x == 0"), // no startsAtMessageId
                        mapOf("guard" to "x < 0", "startsAtMessageId" to "m1"), // no id
                        mapOf("id" to "op-valid", "guard" to "x < 0", "startsAtMessageId" to "m1"),
                    ),
                ),
            ),
            "notes" to emptyList<Any?>(),
        )
        val source = "sequenceDiagram\n"
        val header = mapOf("dialect" to "mermaid", "sourceHash" to seq3SourceHash(source), "document" to legacyMap)
        val legacyText = "<!-- indagium:diagram3 v1 ${Json.encode(header)} -->\n```mermaid\n$source```\n"

        val parsed = parseSeq3Note(legacyText)

        assertNotNull(parsed, "two malformed operands must not fail the whole document")
        val operands = parsed.document.fragments.single().elseOperands
        assertEquals(listOf("op-valid"), operands.map { it.id }, "only the well-formed operand survives")
    }

    @Test
    fun exceedingTheMaxOperandsPerFragmentIsRejectedTheSameWayTheExistingPerFragmentBoundIs() {
        // Mirrors tooManyMessagesIsRejectedAtDecode's shape: 33 minimal operand maps, one over
        // MAX_SEQ3_OPERANDS_PER_FRAGMENT (32), well under MAX_SEQ3_HEADER_CHARS so only the
        // count bound is exercised.
        val operands = (0 until 33).map { i -> mapOf("id" to "op$i", "guard" to "g$i", "startsAtMessageId" to "m1") }
        val legacyMap = mapOf(
            "lifelines" to listOf(mapOf("id" to "A", "name" to "A", "tagIds" to listOf("A"), "ordinal" to 0)),
            "messages" to emptyList<Any?>(),
            "fragments" to listOf(
                mapOf("id" to "f1", "kind" to "ALT", "label" to "x > 0", "messageIds" to listOf<String>(), "elseOperands" to operands),
            ),
            "notes" to emptyList<Any?>(),
        )
        val source = "sequenceDiagram\n"
        val header = mapOf("dialect" to "mermaid", "sourceHash" to seq3SourceHash(source), "document" to legacyMap)
        val text = "<!-- indagium:diagram3 v1 ${Json.encode(header)} -->\n```mermaid\n$source```\n"
        assertTrue(Json.encode(header).length < 512 * 1024, "test setup sanity: header must stay under the size bound so only the count bound is exercised")

        assertNull(parseSeq3Note(text), "a fragment declaring more than the per-fragment operand cap must be rejected, not silently truncated")
    }

    @Test
    fun aFreeFloatingNoteWithNoMessageIdsRoundTrips() {
        // WP7 item 3: "Add note here" on empty canvas creates a note with an EMPTY messageIds and
        // its own explicit geometry — must round-trip exactly like an anchored one, not get
        // silently dropped or have its geometry lost.
        val original = fixedDocument().copy(
            notes = listOf(Seq3Note("floating1", "just a thought", messageIds = emptyList(), x = 120.0, y = 340.0, width = 220.0, height = 72.0)),
        )
        val parsed = parseSeq3Note(encodeSeq3Note(original))

        assertNotNull(parsed)
        assertEquals(original, parsed.document)
        val note = parsed.document.notes.single()
        assertTrue(note.messageIds.isEmpty())
        assertEquals(120.0, note.x)
        assertEquals(340.0, note.y)
    }
}
