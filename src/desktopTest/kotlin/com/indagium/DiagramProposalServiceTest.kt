package com.indagium

import com.indagium.diagram.DiagramMessage
import com.indagium.diagram.DiagramMessageRule
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.DiagramProposalIssueKind
import com.indagium.diagram.DiagramProposalService
import com.indagium.diagram.DiagramRuleCaptureBinding
import com.indagium.diagram.DiagramRuleEndpoint
import com.indagium.diagram.ManualDiagramInteraction
import com.indagium.diagram.MessageEvidence
import com.indagium.diagram.MessageKind
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagramProposalServiceTest {
    private val app = DiagramParticipant("app", "App", ParticipantKind.TAG, tag = "App")
    private val network = DiagramParticipant("network", "Network", ParticipantKind.TAG, tag = "Network")

    private fun entry(id: Int, tag: String = "App", message: String) =
        LogEntry(id, "10:00:00.000", LogLevel.I, tag, message)

    @Test
    fun typedCaptureBindingsResolveMatchesAndExposeRepresentativeSamples() {
        val rule = DiagramMessageRule(
            id = "http",
            pattern = "GET (?<peer>\\w+) (?<path>/\\S+)",
            fromTemplate = "",
            toTemplate = "",
            labelTemplate = "GET ${'$'}{path}",
            fromEndpoint = DiagramRuleEndpoint.CurrentEntry,
            toEndpoint = DiagramRuleEndpoint.CapturedValue("peer", listOf(DiagramRuleCaptureBinding("api", "network"))),
        )

        val result = DiagramProposalService.evaluate(
            listOf(entry(1, message = "GET api /pets"), entry(2, message = "GET api /owners")),
            SeqDiagramSpec(rules = listOf(rule)),
            listOf(app, network),
        )

        val proposal = result.candidates.single()
        assertEquals(setOf(1, 2), proposal.matchedEntryIds)
        assertEquals(setOf("app", "network"), proposal.resolvedEndpointIds)
        assertEquals(2, proposal.samples.size)
        assertEquals("GET /pets", proposal.samples.first().label)
        assertFalse(result.applyBlocked)
    }

    @Test
    fun unboundAndAmbiguousCaptureBlockApplicationWithoutCreatingActors() {
        val unbound = DiagramMessageRule(
            id = "unbound",
            pattern = "to (?<peer>\\w+)",
            fromTemplate = "",
            toTemplate = "",
            labelTemplate = "${'$'}{msg}",
            fromEndpoint = DiagramRuleEndpoint.ExistingParticipant("app"),
            toEndpoint = DiagramRuleEndpoint.CapturedValue("peer", emptyList()),
        )
        val ambiguous = unbound.copy(
            id = "ambiguous",
            toEndpoint = DiagramRuleEndpoint.CapturedValue(
                "peer",
                listOf(DiagramRuleCaptureBinding("api", "network"), DiagramRuleCaptureBinding("api", "other")),
            ),
        )

        val result = DiagramProposalService.evaluateRules(
            listOf(entry(7, message = "to api")),
            listOf(unbound, ambiguous),
            listOf(app, network),
        )

        assertTrue(result.applyBlocked)
        assertEquals(DiagramProposalIssueKind.UNRESOLVED_CAPTURE, result.candidates[0].issues.single().kind)
        assertEquals(DiagramProposalIssueKind.AMBIGUOUS_CAPTURE, result.candidates[1].issues.single().kind)
        assertTrue(result.resolvedEndpointIds.contains("app"))
        assertFalse(result.resolvedEndpointIds.contains("api"), "captured text must not become an implicit actor")
    }

    @Test
    fun explicitActorIsReturnedAsAnExplicitDeclarationAndDoesNotBlock() {
        val rule = DiagramMessageRule(
            id = "device",
            pattern = "request",
            fromTemplate = "",
            toTemplate = "",
            labelTemplate = "request",
            fromEndpoint = DiagramRuleEndpoint.ExplicitActor("device", "Mobile device"),
            toEndpoint = DiagramRuleEndpoint.ExistingParticipant("app"),
        )

        val proposal = DiagramProposalService.evaluateRules(listOf(entry(1, message = "request")), listOf(rule), listOf(app))
            .candidates.single()

        assertFalse(proposal.applyBlocked)
        assertEquals(setOf("device", "app"), proposal.resolvedEndpointIds)
        assertEquals(listOf("device"), proposal.explicitActors.map { it.id })
        assertEquals(ParticipantKind.ACTOR, proposal.explicitActors.single().kind)
    }

    @Test
    fun legacyTemplateDoesNotCreateAnUnknownActorInProposalEvaluation() {
        val rule = DiagramMessageRule(
            id = "legacy",
            pattern = "sending to (?<peer>\\w+)",
            fromTemplate = "app",
            toTemplate = "${'$'}{peer}",
            labelTemplate = "${'$'}{msg}",
        )

        val result = DiagramProposalService.evaluateRules(listOf(entry(1, message = "sending to remote")), listOf(rule), listOf(app))

        assertTrue(result.applyBlocked)
        assertEquals(setOf("app"), result.resolvedEndpointIds)
        assertTrue(result.issues.single().detail.contains("never creates actors implicitly"))
    }

    @Test
    fun sourceProvenMessageCanBecomeManualSeedOnlyWhenEndpointsExist() {
        val sourceMessage = DiagramMessage(
            fromIdx = 0,
            toIdx = 1,
            label = "load pets",
            entryId = 4,
            ts = "10:00:00.000",
            level = LogLevel.I,
            kind = MessageKind.CALL,
            evidence = MessageEvidence.SOURCE_INFERRED,
            sourceOperationId = "call:load",
            representedEntryIds = setOf(4, 5),
        )

        val seed = DiagramProposalService.manualSeedFromVerifiedSourceMessage(sourceMessage, listOf(app, network))

        assertEquals("source:call:load:0", seed?.id)
        assertEquals(setOf(4, 5), seed?.sourceEntryIds)
        assertEquals("app", seed?.fromParticipantId)
        assertEquals("network", seed?.toParticipantId)
        assertNull(DiagramProposalService.manualSeedFromVerifiedSourceMessage(sourceMessage, listOf(app)))
        assertNull(DiagramProposalService.manualSeedFromVerifiedSourceMessage(sourceMessage.copy(evidence = MessageEvidence.LOG), listOf(app, network)))
    }

    @Test
    fun manualSeedEvaluationReportsMissingDeclaredParticipants() {
        val seed = ManualDiagramInteraction("manual", setOf(1), "app", "missing")

        val result = DiagramProposalService.evaluateManualSeed(seed, listOf(app))

        assertTrue(result.applyBlocked)
        assertEquals(listOf("missing"), result.unresolvedParticipantIds)
    }
}
