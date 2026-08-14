package com.indagium

import com.indagium.diagram.ArrowMode
import com.indagium.diagram.DiagramActivationSpan
import com.indagium.diagram.DiagramActor
import com.indagium.diagram.DiagramAttachmentMetadata
import com.indagium.diagram.DiagramAttachmentMode
import com.indagium.diagram.DiagramAuthoringMode
import com.indagium.diagram.DiagramCallOverride
import com.indagium.diagram.DiagramComponent
import com.indagium.diagram.DiagramDialect
import com.indagium.diagram.DiagramExportMode
import com.indagium.diagram.DiagramFrame
import com.indagium.diagram.DiagramMessage
import com.indagium.diagram.DiagramMessageOverride
import com.indagium.diagram.DiagramMessageRule
import com.indagium.diagram.DiagramNoteMark
import com.indagium.diagram.DiagramOptions
import com.indagium.diagram.DiagramParameter
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.DiagramRange
import com.indagium.diagram.DiagramResolvedTrace
import com.indagium.diagram.DiagramRuleCaptureBinding
import com.indagium.diagram.DiagramRuleEndpoint
import com.indagium.diagram.DiagramTraceCall
import com.indagium.diagram.DiagramTraceDiagnostic
import com.indagium.diagram.DiagramTraceDiagnostics
import com.indagium.diagram.DiagramTraceEvent
import com.indagium.diagram.DiagramTraceEvidence
import com.indagium.diagram.DiagramTraceOperation
import com.indagium.diagram.LabelSource
import com.indagium.diagram.ManualDiagramActivation
import com.indagium.diagram.ManualDiagramDocument
import com.indagium.diagram.ManualDiagramEvidence
import com.indagium.diagram.ManualDiagramGroup
import com.indagium.diagram.ManualDiagramInteraction
import com.indagium.diagram.ManualDiagramNote
import com.indagium.diagram.ManualDiagramRepeatPresentation
import com.indagium.diagram.ManualFragmentKind
import com.indagium.diagram.ManualInteractionAuthoring
import com.indagium.diagram.ManualOperationVisibility
import com.indagium.diagram.MessageEvidence
import com.indagium.diagram.MessageKind
import com.indagium.diagram.MessageOriginKey
import com.indagium.diagram.MirrorDirection
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagram
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.SourceTraceMode
import com.indagium.diagram.TraceCallStatus
import com.indagium.diagram.TraceDiagnosticReason
import com.indagium.diagram.TraceInvocationKind
import com.indagium.diagram.TraceOperationKind
import com.indagium.diagram.UnmappedTagPolicy
import com.indagium.diagram.encodeDiagramNote
import com.indagium.diagram.normalizeManualDocument
import com.indagium.diagram.parseDiagramNote
import com.indagium.diagram.stripDiagramSpecHeader
import com.indagium.diagram.updateDiagramNoteCaption
import com.indagium.diagram.updateDiagramNoteExportMode
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
        rules = listOf(
            DiagramMessageRule(
                id = "r1", pattern = "sending to (?<to>\\w+)",
                fromTemplate = "self", toTemplate = "\${to}", labelTemplate = "\${msg}",
            ),
        ),
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
    fun sourceOnlyLegacyNotesRemainViewableWhileNewWritesOmitRetiredFields() {
        val note = encodeDiagramNote(fullSpec, source)

        val parsed = parseDiagramNote(note)

        assertTrue(parsed != null, "a note this codec itself produced must always parse back")
        assertEquals(fullSpec.copy(mode = ArrowMode.EVIDENCE_FLOW, rules = emptyList()), parsed.spec)
        assertFalse(note.contains("\"mode\":\"EVIDENCE_FLOW\""))
        assertFalse(note.contains("\"rules\""))
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
        val futureVersion = "<!-- indagium:diagram v6 {\"dialect\":\"mermaid\"} -->\n```mermaid\nsequenceDiagram\n```\n"
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
    fun transientCallerIsNotPersistedInCarriedModels() {
        val durable = listOf(DiagramParticipant("A", "A", ParticipantKind.TAG, tag = "A"))
        val caller = DiagramParticipant("Caller", "Caller", ParticipantKind.ACTOR, isEntryPoint = true, inferred = true)
        val model = SeqDiagram(
            spec = fullSpec.copy(participants = durable),
            participants = listOf(caller, durable.single()),
            messages = listOf(
                DiagramMessage(0, 1, "start", 7, "10:00:00.000", LogLevel.I, MessageKind.CALL),
                DiagramMessage(1, 1, "work", 8, "10:00:00.010", LogLevel.I, MessageKind.SELF),
            ),
        )

        val parsed = assertNotNull(parseDiagramNote(encodeDiagramNote(model.spec, source, model)))
        val carried = assertNotNull(parsed.model)

        assertTrue(carried.participants.none { it.label == "Caller" })
        assertEquals(listOf(0, 0), carried.messages.map { it.fromIdx })
        assertEquals(listOf(0, 0), carried.messages.map { it.toIdx })
        assertTrue(parsed.spec.participants.none { it.label == "Caller" })
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

    @Test
    fun v2GuardsTheCarriedModelAgainstSourceEditsAndRetainsAttachmentSnapshotMetadata() {
        val participants = listOf(DiagramParticipant("App", "App", ParticipantKind.TAG, tag = "App"))
        val model = SeqDiagram(
            spec = fullSpec.copy(participants = participants), participants = participants,
            messages = listOf(DiagramMessage(0, 0, "original", 7, "10:00:00.000", LogLevel.I, MessageKind.SELF)),
        )
        val attachment = DiagramAttachmentMetadata("draft-42", DiagramAttachmentMode.LINKED, revision = 8, attachedAtEpochMs = 1234)
        val encoded = encodeDiagramNote(model.spec, source, model, attachment)
        val trusted = assertNotNull(parseDiagramNote(encoded))
        assertTrue(trusted.sourceHashMatches == true)
        assertNotNull(trusted.model)
        assertEquals(attachment, trusted.attachment)

        val edited = encoded.replace("App->>App: hi", "App->>App: manually edited")
        val parsed = assertNotNull(parseDiagramNote(edited))
        assertEquals(false, parsed.sourceHashMatches)
        assertNull(parsed.model, "a stale carried model must never render after source editing")
        assertNotNull(parsed.snapshot, "the original attachment snapshot remains available for explicit recovery")
        assertNotNull(
            parsed.snapshotPreviewModel,
            "read-only Markdown Preview can still show the retained v2 attachment as a snapshot",
        )
        assertNotNull(parsed.warning)
        assertEquals(attachment, parsed.attachment)
    }

    @Test
    fun v1CarriedModelsRemainReadableWithoutASourceHash() {
        val note = "<!-- indagium:diagram v1 {\"dialect\":\"mermaid\",\"model\":{\"participants\":[" +
            "{\"id\":\"A\",\"label\":\"A\",\"kind\":\"TAG\",\"tag\":\"A\"}],\"messages\":[]}} -->\n" +
            "```mermaid\nsequenceDiagram\n```\n"
        val parsed = assertNotNull(parseDiagramNote(note))
        assertNotNull(parsed.model)
        assertNotNull(parsed.snapshotPreviewModel, "v1's carried model remains previewable")
        assertNull(parsed.sourceHashMatches)
    }

    @Test
    fun attachmentCaptionAndExportModeRoundTripAndLegacyV1DefaultsToImage() {
        val attachment = DiagramAttachmentMetadata(caption = "Bluetooth startup", exportMode = DiagramExportMode.SOURCE)
        val parsed = assertNotNull(parseDiagramNote(encodeDiagramNote(fullSpec, source, attachment = attachment)))

        assertEquals("Bluetooth startup", parsed.caption)
        assertEquals(DiagramExportMode.SOURCE, parsed.exportMode)

        val v1 = assertNotNull(parseDiagramNote(
            "<!-- indagium:diagram v1 {\"dialect\":\"mermaid\"} -->\n```mermaid\nsequenceDiagram\n```\n",
        ))
        assertEquals("", v1.caption)
        assertEquals(DiagramExportMode.IMAGE, v1.exportMode)
    }

    @Test
    fun attachmentMetadataRewriteApisUpgradeLegacyNotesWithoutChangingTheirSource() {
        val v1 = "<!-- indagium:diagram v1 {\"dialect\":\"mermaid\"} -->\n```mermaid\nsequenceDiagram\n```\n"

        val captioned = assertNotNull(updateDiagramNoteCaption(v1, "Startup sequence"))
        val sourceMode = assertNotNull(updateDiagramNoteExportMode(captioned, DiagramExportMode.SOURCE))
        val parsed = assertNotNull(parseDiagramNote(sourceMode))

        assertTrue(sourceMode.startsWith("<!-- indagium:diagram v5 "))
        assertEquals("Startup sequence", parsed.caption)
        assertEquals(DiagramExportMode.SOURCE, parsed.exportMode)
        assertEquals("sequenceDiagram", parsed.source)
        assertNull(updateDiagramNoteCaption("ordinary note", "nope"))
    }

    @Test
    fun newModelBackedNotesNormalizeToManualAndRetireInferredFields() {
        val spec = SeqDiagramSpec(
            components = listOf(DiagramComponent("app", "App", setOf("A", "B"), sourceOwnerTypes = setOf("com.example.App"))),
            actors = listOf(DiagramActor("client", "Client", "app", MirrorDirection.OUTBOUND, mirrorComponentIds = setOf("app"))),
            unmappedTagPolicy = UnmappedTagPolicy.GROUP_AS_OTHER,
            callOverrides = listOf(DiagramCallOverride(9, 0, "app", "client")),
        )
        val participants = listOf(
            DiagramParticipant("app", "App", ParticipantKind.TAG),
            DiagramParticipant("client", "Client", ParticipantKind.ACTOR),
        )
        val model = SeqDiagram(
            spec, participants,
            listOf(DiagramMessage(0, 1, "call", 9, "10:00:00", LogLevel.I, MessageKind.CALL, evidence = MessageEvidence.SOURCE_INFERRED, edgeOrdinal = 0)),
            activationSpans = listOf(DiagramActivationSpan(1, 0, 0, MessageEvidence.SOURCE_INFERRED)),
        )
        val encoded = encodeDiagramNote(spec, source, model)
        val parsed = assertNotNull(parseDiagramNote(encoded))
        assertEquals(DiagramAuthoringMode.MANUAL, parsed.spec.authoringMode)
        assertTrue(parsed.spec.manualDocument.interactions.isNotEmpty())
        assertEquals(setOf("com.example.App"), parsed.spec.components.single().sourceOwnerTypes)
        assertTrue(parsed.spec.actors.single().mirrorComponentIds.isEmpty())
        assertTrue(parsed.spec.callOverrides.isEmpty())
        assertFalse(encoded.contains("\"authoringMode\""))
        assertFalse(encoded.contains("\"callOverrides\""))
        assertFalse(encoded.contains("\"mirrorComponentId\""))
        assertEquals(MessageEvidence.SOURCE_INFERRED, parsed.model?.messages?.single()?.evidence)
        assertEquals(model.activationSpans, parsed.model?.activationSpans)
    }

    @Test
    fun v4RoundTripsManualEditsTypedRulesAndBoundedTraceProvenance() {
        val participants = listOf(
            DiagramParticipant("client", "Client", ParticipantKind.ACTOR),
            DiagramParticipant("service", "Service", ParticipantKind.TAG, tag = "Service"),
        )
        val origin = MessageOriginKey(7, ruleId = "route", sourceOperationId = "op-7", sourceLogSiteId = "site-7", invocationId = "i-7")
        val document = ManualDiagramDocument(
            interactions = listOf(ManualDiagramInteraction(
                "manual-1", setOf(7), "client", "service", "fetch", listOf(DiagramParameter("id", "7"),),
                kind = MessageKind.ASYNC, groupKey = "source:op-7|site-7", sourceMethodId = "op-7",
                sourceLogSiteId = "site-7", sourceOwnerType = "Client", visibility = ManualOperationVisibility.PRIVATE,
            )),
            groups = listOf(ManualDiagramGroup("g-1", "request", listOf("manual-1"), kind = ManualFragmentKind.LOOP)),
            notes = listOf(ManualDiagramNote("n-1", "service", "manual-1", "queued")),
            activations = listOf(ManualDiagramActivation("a-1", "service", "manual-1", "manual-1")),
        )
        val spec = SeqDiagramSpec(
            participants = participants,
            rules = listOf(DiagramMessageRule(
                "route", "to (?<peer>\\w+)", fromTemplate = "", toTemplate = "", labelTemplate = "",
                fromEndpoint = DiagramRuleEndpoint.ExistingParticipant("client"),
                toEndpoint = DiagramRuleEndpoint.CapturedValue("peer", listOf(DiagramRuleCaptureBinding("service", "service"))),
            )),
            authoringMode = DiagramAuthoringMode.MANUAL,
            lifelineOrder = listOf("client", "service"),
            messageOverrides = listOf(DiagramMessageOverride(origin, label = "fetch async", kind = MessageKind.ASYNC)),
            manualDocument = document,
        )
        val trace = DiagramResolvedTrace(
            events = listOf(
                DiagramTraceEvent(7, "site-7", methodId = "m-7", laneId = "p1:t1", confidence = 0.9, evidence = setOf(DiagramTraceEvidence.EXACT_SOURCE_SITE)),
            ),
            calls = listOf(
                DiagramTraceCall(
                    "i-7", "Client", "Service", callEntryId = 7, status = TraceCallStatus.RETURNED,
                    invocationKind = TraceInvocationKind.EXECUTOR_DISPATCH, callLabel = "fetch", confidence = 0.9,
                ),
            ),
            operations = listOf(DiagramTraceOperation("op-7", TraceOperationKind.SOURCE_CALL, 7, "i-7", "op-7", "site-7")),
            diagnostics = DiagramTraceDiagnostics(diagnostics = listOf(DiagramTraceDiagnostic(TraceDiagnosticReason.ASYNC_BOUNDARY, 7, "dispatch"))),
        )
        val model = SeqDiagram(spec, participants, listOf(
            DiagramMessage(
                0, 1, "fetch", 7, "10:00:00", LogLevel.I, MessageKind.ASYNC,
                sourceOperationId = "op-7", sourceLogSiteId = "site-7", originKeys = setOf(origin),
            ),
        ), resolvedTrace = trace, traceMode = SourceTraceMode.SOURCE_TRACE)

        val encoded = encodeDiagramNote(spec, source, model)
        val parsed = assertNotNull(parseDiagramNote(encoded))

        assertTrue(encoded.startsWith("<!-- indagium:diagram v5 "))
        assertEquals(normalizeManualDocument(document), parsed.spec.manualDocument)
        assertEquals(DiagramAuthoringMode.MANUAL, parsed.spec.authoringMode)
        assertTrue(parsed.spec.rules.isEmpty())
        assertTrue(parsed.spec.messageOverrides.isEmpty())
        assertFalse(encoded.contains("\"rules\""))
        assertFalse(encoded.contains("\"messageOverrides\""))
        assertEquals(model.messages.single().originKeys, parsed.model?.messages?.single()?.originKeys)
        assertEquals(trace, parsed.model?.resolvedTrace)
        assertEquals(SourceTraceMode.SOURCE_TRACE, parsed.model?.traceMode)
        assertEquals("source:op-7|site-7", parsed.spec.manualDocument.interactions.single().groupKey)
        assertEquals(ManualOperationVisibility.PRIVATE, parsed.spec.manualDocument.interactions.single().visibility)
        assertEquals(ManualFragmentKind.LOOP, parsed.spec.manualDocument.groups.single().kind)
    }

    @Test
    fun oldManualGroupRecordsWithNoPersistedKindDecodeAsCustom() {
        val note = "<!-- indagium:diagram v4 {\"dialect\":\"mermaid\",\"participants\":[" +
            "{\"id\":\"a\",\"label\":\"A\",\"kind\":\"TAG\",\"tag\":\"A\"}]," +
            "\"authoringMode\":\"MANUAL\",\"manualDocument\":{\"interactions\":[" +
            "{\"id\":\"m\",\"sourceEntryIds\":[1],\"fromParticipantId\":\"a\",\"toParticipantId\":\"a\"," +
            "\"operation\":\"event\",\"parameters\":[],\"result\":null,\"label\":null," +
            "\"kind\":\"SELF\",\"enabled\":true,\"order\":0}]," +
            "\"groups\":[{\"id\":\"g\",\"label\":\"Frame\",\"interactionIds\":[\"m\"],\"enabled\":true}]," +
            "\"notes\":[],\"activations\":[]}} -->\n" +
            "```mermaid\nsequenceDiagram\n```\n"
        val parsed = assertNotNull(parseDiagramNote(note))
        assertEquals(ManualFragmentKind.CUSTOM, parsed.spec.manualDocument.groups.single().kind)
    }

    @Test
    fun unknownPersistedFragmentKindDecodesAsCustomRatherThanFailingTheWholeNote() {
        val note = "<!-- indagium:diagram v4 {\"dialect\":\"mermaid\",\"participants\":[" +
            "{\"id\":\"a\",\"label\":\"A\",\"kind\":\"TAG\",\"tag\":\"A\"}]," +
            "\"authoringMode\":\"MANUAL\",\"manualDocument\":{\"interactions\":[" +
            "{\"id\":\"m\",\"sourceEntryIds\":[1],\"fromParticipantId\":\"a\",\"toParticipantId\":\"a\"," +
            "\"operation\":\"event\",\"parameters\":[],\"result\":null,\"label\":null," +
            "\"kind\":\"SELF\",\"enabled\":true,\"order\":0}]," +
            "\"groups\":[{\"id\":\"g\",\"label\":\"Frame\",\"interactionIds\":[\"m\"],\"enabled\":true,\"kind\":\"FUTURE_KIND\"}]," +
            "\"notes\":[],\"activations\":[]}} -->\n" +
            "```mermaid\nsequenceDiagram\n```\n"
        val parsed = assertNotNull(parseDiagramNote(note))
        assertEquals(ManualFragmentKind.CUSTOM, parsed.spec.manualDocument.groups.single().kind)
    }

    @Test
    fun oldManualInteractionRecordsUseGroupingProvenanceAndVisibilityDefaults() {
        val note = "<!-- indagium:diagram v4 {\"dialect\":\"mermaid\",\"participants\":[" +
            "{\"id\":\"a\",\"label\":\"A\",\"kind\":\"TAG\",\"tag\":\"A\"}]," +
            "\"authoringMode\":\"MANUAL\",\"manualDocument\":{\"interactions\":[" +
            "{\"id\":\"m\",\"sourceEntryIds\":[1],\"fromParticipantId\":\"a\",\"toParticipantId\":\"a\"," +
            "\"operation\":\"event\",\"parameters\":[],\"result\":null,\"label\":null," +
            "\"kind\":\"SELF\",\"enabled\":true,\"order\":0}],\"groups\":[],\"notes\":[],\"activations\":[]}} -->\n" +
            "```mermaid\nsequenceDiagram\n```\n"
        val parsed = assertNotNull(parseDiagramNote(note))
        val interaction = parsed.spec.manualDocument.interactions.single()
        assertNull(interaction.groupKey)
        assertNull(interaction.sourceMethodId)
        assertEquals(ManualOperationVisibility.UNSPECIFIED, interaction.visibility)
        assertEquals(ManualInteractionAuthoring.AUTO, interaction.authoring)
    }

    @Test
    fun targetlessManualInteractionsRoundTripWithStableModelIdentity() {
        val participants = listOf(DiagramParticipant("a", "A", ParticipantKind.TAG, tag = "A"))
        val interaction = ManualDiagramInteraction(
            id = "targetless", sourceEntryIds = setOf(7), fromParticipantId = "a", toParticipantId = null,
            label = "queued", groupKey = "queue", authoring = ManualInteractionAuthoring.EDITED,
        )
        val spec = SeqDiagramSpec(
            participants = participants,
            authoringMode = DiagramAuthoringMode.MANUAL,
            manualDocument = ManualDiagramDocument(interactions = listOf(interaction)),
        )
        val model = SeqDiagram(
            spec, participants,
            listOf(DiagramMessage(
                0, 0, "queued", 7, "10:00:00", LogLevel.W, MessageKind.CALL,
                originKeys = setOf(MessageOriginKey(7, manualInteractionId = "targetless")),
                targetless = true, manualGroupKey = "queue",
            )),
        )

        val parsed = assertNotNull(parseDiagramNote(encodeDiagramNote(spec, source, model)))
        assertEquals(null, parsed.spec.manualDocument.interactions.single().toParticipantId)
        assertEquals(ManualInteractionAuthoring.EDITED, parsed.spec.manualDocument.interactions.single().authoring)
        assertTrue(parsed.model?.messages?.single()?.targetless == true)
        assertEquals("targetless", parsed.model?.messages?.single()?.originKeys?.single()?.manualInteractionId)
        assertEquals("queue", parsed.model?.messages?.single()?.manualGroupKey)
    }

    @Test
    fun codecRejectsDuplicateAndOversizedUntrustedSpecIdentifiers() {
        val duplicate = "<!-- indagium:diagram v1 {\"dialect\":\"mermaid\",\"participants\":[" +
            "{\"id\":\"A\",\"label\":\"A\",\"kind\":\"TAG\",\"tag\":\"A\"}," +
            "{\"id\":\"A\",\"label\":\"Again\",\"kind\":\"TAG\",\"tag\":\"B\"}]} -->\n" +
            "```mermaid\nsequenceDiagram\n```\n"
        assertNull(parseDiagramNote(duplicate))

        val tooLongTitle = "x".repeat(16 * 1024 + 1)
        val oversized = "<!-- indagium:diagram v1 {\"dialect\":\"mermaid\",\"title\":\"$tooLongTitle\"} -->\n```mermaid\nsequenceDiagram\n```\n"
        assertNull(parseDiagramNote(oversized))
    }

    @Test
    fun codecDropsUntrustedModelsWithOutOfRangeMessageAndActivationIndices() {
        val participants = listOf(DiagramParticipant("A", "A", ParticipantKind.TAG, tag = "A"))
        val model = SeqDiagram(
            SeqDiagramSpec(participants = participants), participants,
            listOf(DiagramMessage(0, 0, "ok", 1, "10:00:00", LogLevel.I, MessageKind.SELF)),
            activationSpans = listOf(DiagramActivationSpan(0, 0, 0, MessageEvidence.LOG)),
        )
        val encoded = encodeDiagramNote(model.spec, source, model)
        val badMessage = assertNotNull(parseDiagramNote(encoded.replace("\"f\":0", "\"f\":99")))
        assertNull(badMessage.model)

        val badActivation = assertNotNull(parseDiagramNote(encoded.replace("\"s\":0", "\"s\":99")))
        assertNull(badActivation.model)
    }

    // ── Components UI constraints (SeqDiagramDialog.kt's ParticipantsSection owns these — the
    // codec only checks them on *decode*, never on save, so a UI bug here would silently produce
    // a note that fails to reopen) ───────────────────────────────────────────────────────────────

    @Test
    fun aliasAndMultiTagComponentsRoundTripUnchanged() {
        val spec = SeqDiagramSpec(
            components = listOf(
                DiagramComponent(id = "BT", displayName = "Bluetooth radio", tagIds = setOf("BT")),
                DiagramComponent(id = "cmp-1", displayName = "Networking", tagIds = setOf("Wifi", "Nsd"), enabled = false),
            ),
        )

        val parsed = assertNotNull(parseDiagramNote(encodeDiagramNote(spec, source)))

        assertEquals(spec.components, parsed.spec.components)
    }

    @Test
    fun aSpecWithATagInTwoComponentsIsRejected() {
        val spec = SeqDiagramSpec(
            components = listOf(
                DiagramComponent(id = "A", displayName = "A", tagIds = setOf("Shared")),
                DiagramComponent(id = "cmp-1", displayName = "B", tagIds = setOf("Shared", "Other")),
            ),
        )

        assertNull(parseDiagramNote(encodeDiagramNote(spec, source)), "the same tag owned by two components must never survive a reopen")
    }

    @Test
    fun aSpecOverTheComponentCapIsRejected() {
        val spec = SeqDiagramSpec(
            components = (1..129).map { DiagramComponent(id = "t$it", displayName = "t$it", tagIds = setOf("tag$it")) },
        )

        assertNull(parseDiagramNote(encodeDiagramNote(spec, source)), "129 components is one over MAX_CODEC_COMPONENTS")
    }

    @Test
    fun anEmptyTagIdComponentIsRejected() {
        val spec = SeqDiagramSpec(
            components = listOf(DiagramComponent(id = "empty", displayName = "Nothing here", tagIds = emptySet())),
        )

        assertNull(parseDiagramNote(encodeDiagramNote(spec, source)), "the EMPTY_COMPONENT_ID-shaped sentinel must never reach the wire")
    }

    // ── ArrowMode rename: the one back-compat boundary ──────────────────────────────────────

    @Test
    fun aV1NoteWithTheLegacyTagTransitionModeTokenDecodesToEvidenceFlow() {
        val note = "<!-- indagium:diagram v1 {\"dialect\":\"mermaid\",\"mode\":\"TAG_TRANSITION\"} -->\n" +
            "```mermaid\nsequenceDiagram\n```\n"

        val parsed = assertNotNull(parseDiagramNote(note))

        assertEquals(ArrowMode.EVIDENCE_FLOW, parsed.spec.mode)
    }

    @Test
    fun aCurrentEvidenceFlowModeTokenRoundTripsUnchanged() {
        val spec = SeqDiagramSpec(mode = ArrowMode.EVIDENCE_FLOW)
        val parsed = assertNotNull(parseDiagramNote(encodeDiagramNote(spec, source)))
        assertEquals(ArrowMode.EVIDENCE_FLOW, parsed.spec.mode)
    }

    // ── New DiagramOptions fields (Part 4) ───────────────────────────────────────────────────

    @Test
    fun theFourNewOptionsFieldsRoundTrip() {
        val spec = SeqDiagramSpec(
            options = DiagramOptions(
                labelMaxLines = 3,
                threadHandoffArrows = true,
                showSelfMessages = false,
                showSourceInferred = false,
            ),
        )

        val parsed = assertNotNull(parseDiagramNote(encodeDiagramNote(spec, source)))

        assertEquals(3, parsed.spec.options.labelMaxLines)
        assertTrue(parsed.spec.options.threadHandoffArrows)
        assertFalse(parsed.spec.options.showSelfMessages)
        assertFalse(parsed.spec.options.showSourceInferred)
    }

    @Test
    fun aV3NoteWrittenWithoutTheNewOptionKeysDecodesToTheirSafeDefaults() {
        // Simulates a v3 note written by a build that predates these four fields entirely — no
        // "labelMaxLines"/"threadHandoffArrows"/"showSelfMessages"/"showSourceInferred" keys at all.
        val note = "<!-- indagium:diagram v3 {\"dialect\":\"mermaid\",\"options\":{\"maxMessages\":42}} -->\n" +
            "```mermaid\nsequenceDiagram\n```\n"

        val parsed = assertNotNull(parseDiagramNote(note))

        val defaults = DiagramOptions()
        assertEquals(42, parsed.spec.options.maxMessages)
        assertFalse(defaults.showElapsed)
        assertEquals(defaults.labelMaxLines, parsed.spec.options.labelMaxLines)
        assertEquals(defaults.threadHandoffArrows, parsed.spec.options.threadHandoffArrows)
        assertEquals(defaults.showSelfMessages, parsed.spec.options.showSelfMessages)
        assertEquals(defaults.showSourceInferred, parsed.spec.options.showSourceInferred)
    }

    @Test
    fun labelMaxLinesOutsideTheValidRangeIsRejectedByValidSpec() {
        val tooFew = SeqDiagramSpec(options = DiagramOptions(labelMaxLines = 0))
        assertNull(parseDiagramNote(encodeDiagramNote(tooFew, source)), "0 lines is below the 1..MAX_LABEL_LINES range")

        val tooMany = SeqDiagramSpec(options = DiagramOptions(labelMaxLines = 9))
        assertNull(parseDiagramNote(encodeDiagramNote(tooMany, source)), "9 lines is one over MAX_LABEL_LINES (8)")

        val atTheEdge = SeqDiagramSpec(options = DiagramOptions(labelMaxLines = 8))
        assertNotNull(parseDiagramNote(encodeDiagramNote(atTheEdge, source)), "8 is the inclusive upper bound")
    }

    @Test
    fun manualEvidenceAndRepeatPresentationRoundTripAndLegacyDocumentsDefaultSafely() {
        val interaction = ManualDiagramInteraction(
            id = "m", sourceEntryIds = setOf(7), fromParticipantId = "a", toParticipantId = "a",
            label = "event", evidence = listOf(ManualDiagramEvidence(7, "10:00:00.000", LogLevel.W)),
        )
        val spec = SeqDiagramSpec(
            participants = listOf(DiagramParticipant("a", "A", ParticipantKind.TAG, tag = "A")),
            manualDocument = ManualDiagramDocument(
                interactions = listOf(interaction), repeatPresentation = ManualDiagramRepeatPresentation.FIRST_AND_LAST,
            ),
        )

        val parsed = assertNotNull(parseDiagramNote(encodeDiagramNote(spec, source)))

        assertEquals(interaction.evidence, parsed.spec.manualDocument.interactions.single().evidence)
        assertEquals(ManualDiagramRepeatPresentation.FIRST_AND_LAST, parsed.spec.manualDocument.repeatPresentation)
    }
}
