package com.indagium.diagram

import com.indagium.model.LogEntry
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.firstRegexMatchResult

/**
 * Pure, side-effect-free preview for authoring interaction rules.  It deliberately has no
 * dependency on the UI, MCP, or builder: both adapters can show exactly the same proposed
 * interactions before they decide whether applying a rule is safe.
 *
 * In contrast to the legacy builder path, this service never creates a participant as a side
 * effect.  A typed [DiagramRuleEndpoint.ExplicitActor] is the one explicit exception: its id is
 * returned as a declared actor endpoint for the caller to add deliberately when applying.
 */
object DiagramProposalService {
    const val DEFAULT_SAMPLE_LIMIT: Int = 3

    fun evaluate(
        entries: List<LogEntry>,
        spec: SeqDiagramSpec,
        availableParticipants: List<DiagramParticipant> = spec.participants,
        sampleLimit: Int = DEFAULT_SAMPLE_LIMIT,
    ): DiagramProposalEvaluation = evaluateRules(entries, spec.rules, availableParticipants, sampleLimit)

    fun evaluateRules(
        entries: List<LogEntry>,
        rules: List<DiagramMessageRule>,
        availableParticipants: List<DiagramParticipant>,
        sampleLimit: Int = DEFAULT_SAMPLE_LIMIT,
    ): DiagramProposalEvaluation {
        val participantIds = availableParticipants.map { it.id }.toSet()
        val participantsByTag = availableParticipants
            .filter { it.kind == ParticipantKind.TAG && !it.tag.isNullOrBlank() }
            .groupBy { it.tag!! }
        val proposals = rules.map { rule ->
            evaluateRule(rule, entries, participantIds, participantsByTag, sampleLimit.coerceAtLeast(0))
        }
        return DiagramProposalEvaluation(
            candidates = proposals,
            matchedEntryIds = proposals.flatMapTo(linkedSetOf()) { it.matchedEntryIds },
            resolvedEndpointIds = proposals.flatMapTo(linkedSetOf()) { it.resolvedEndpointIds },
            samples = proposals.flatMap { it.samples },
            issues = proposals.flatMap { it.issues },
            // A disabled, invalid, or unmatched candidate should not prevent applying another
            // clean rule.  Any matched candidate with an unresolved endpoint must be explicit.
            applyBlocked = proposals.any { it.applyBlocked },
        )
    }

    /**
     * Creates an editable manual seed only for a source-proven message with valid predeclared
     * endpoints.  The returned interaction does not mutate the supplied participant list.
     */
    fun manualSeedFromVerifiedSourceMessage(
        message: DiagramMessage,
        participants: List<DiagramParticipant>,
    ): ManualDiagramInteraction? {
        if (message.evidence != MessageEvidence.SOURCE_INFERRED) return null
        if (message.sourceOperationId.isNullOrBlank() && message.sourceLogSiteId.isNullOrBlank()) return null
        val from = participants.getOrNull(message.fromIdx)?.id ?: return null
        val to = participants.getOrNull(message.toIdx)?.id ?: return null
        val sourceParticipant = participants.getOrNull(message.fromIdx)
        val origin = message.originKeys.singleOrNull()
            ?: MessageOriginKey(
                entryId = message.entryId,
                sourceOperationId = message.sourceOperationId,
                sourceLogSiteId = message.sourceLogSiteId,
                invocationId = message.invocationId,
                generatedOrdinal = message.edgeOrdinal,
            )
        val id = origin.manualInteractionId ?: buildString {
            append("source:")
            append(origin.sourceOperationId ?: origin.sourceLogSiteId ?: origin.entryId)
            append(':')
            append(origin.generatedOrdinal)
        }
        return ManualDiagramInteraction(
            id = id,
            sourceEntryIds = message.representedEntryIds.ifEmpty { setOf(message.entryId) },
            fromParticipantId = from,
            toParticipantId = to,
            operation = message.label,
            label = message.label,
            kind = message.kind,
            order = message.entryId.toLong(),
            groupKey = manualInteractionGroupKey(
                message.sourceOperationId, message.sourceLogSiteId, from, to, message.kind, message.label,
            ),
            sourceMethodId = message.sourceOperationId,
            sourceLogSiteId = message.sourceLogSiteId,
            sourceOwnerType = sourceParticipant?.sourceOwnerType,
        )
    }

    /** Evaluates an existing manual seed against the same strict participant contract as rules. */
    fun evaluateManualSeed(
        seed: ManualDiagramInteraction,
        availableParticipants: List<DiagramParticipant>,
    ): DiagramManualSeedProposal {
        val ids = availableParticipants.mapTo(hashSetOf()) { it.id }
        val missing = listOfNotNull(seed.fromParticipantId, seed.toParticipantId).filter { it !in ids }.distinct()
        return DiagramManualSeedProposal(seed, missing, applyBlocked = !seed.enabled || missing.isNotEmpty())
    }

    private fun evaluateRule(
        rule: DiagramMessageRule,
        entries: List<LogEntry>,
        participantIds: Set<String>,
        participantsByTag: Map<String, List<DiagramParticipant>>,
        sampleLimit: Int,
    ): DiagramRuleProposal {
        if (!rule.enabled) return DiagramRuleProposal(rule.id, enabled = false)
        if (rule.pattern.isBlank()) {
            return DiagramRuleProposal(
                ruleId = rule.id,
                issues = listOf(DiagramProposalIssue(rule.id, null, DiagramProposalIssueKind.INVALID_PATTERN, "Rule pattern is blank.")),
                applyBlocked = true,
            )
        }
        if (runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE) }.isFailure) {
            return DiagramRuleProposal(
                ruleId = rule.id,
                issues = listOf(DiagramProposalIssue(rule.id, null, DiagramProposalIssueKind.INVALID_PATTERN, "Rule pattern is invalid.")),
                applyBlocked = true,
            )
        }

        val matched = linkedSetOf<Int>()
        val endpointIds = linkedSetOf<String>()
        val explicitActors = linkedMapOf<String, DiagramParticipant>()
        val issues = mutableListOf<DiagramProposalIssue>()
        val samples = mutableListOf<DiagramProposalSample>()
        val regexContext = RegexEvaluationContext()
        for (entry in entries) {
            val match = firstRegexMatchResult(entry.msg, rule.pattern, regexContext = regexContext) ?: continue
            matched += entry.id
            val from = resolveEndpoint(rule.fromEndpoint, rule.fromTemplate, "from", entry, match, participantIds, participantsByTag)
            val to = resolveEndpoint(rule.toEndpoint, rule.toTemplate, "to", entry, match, participantIds, participantsByTag)
            endpointIds += listOfNotNull(from.participantId, to.participantId)
            from.explicitActor?.let { explicitActors.putIfAbsent(it.id, it) }
            to.explicitActor?.let { explicitActors.putIfAbsent(it.id, it) }
            issues += from.issues.map { it.copy(ruleId = rule.id, entryId = entry.id) }
            issues += to.issues.map { it.copy(ruleId = rule.id, entryId = entry.id) }
            if (samples.size < sampleLimit) {
                samples += DiagramProposalSample(
                    ruleId = rule.id,
                    entryId = entry.id,
                    message = entry.msg,
                    fromParticipantId = from.participantId,
                    toParticipantId = to.participantId,
                    label = substitute(rule.labelTemplate, match, entry),
                )
            }
        }
        val distinctIssues = issues.distinct()
        return DiagramRuleProposal(
            ruleId = rule.id,
            matchedEntryIds = matched,
            resolvedEndpointIds = endpointIds,
            samples = samples,
            explicitActors = explicitActors.values.toList(),
            issues = distinctIssues,
            applyBlocked = matched.isNotEmpty() && distinctIssues.any { it.kind.blocksApply },
        )
    }

    private fun resolveEndpoint(
        endpoint: DiagramRuleEndpoint?,
        legacyTemplate: String,
        role: String,
        entry: LogEntry,
        match: MatchResult,
        participantIds: Set<String>,
        participantsByTag: Map<String, List<DiagramParticipant>>,
    ): EndpointResolution = when (endpoint) {
        is DiagramRuleEndpoint.ExistingParticipant -> existingParticipant(endpoint.participantId, role, participantIds)
        DiagramRuleEndpoint.CurrentEntry -> {
            val candidates = participantsByTag[entry.tag].orEmpty().map { it.id }.distinct()
            when (candidates.size) {
                1 -> EndpointResolution(participantId = candidates.single())
                0 -> unresolved(role, "No available participant represents entry tag '${entry.tag}'.")
                else -> ambiguous(role, entry.tag, candidates)
            }
        }
        is DiagramRuleEndpoint.CapturedValue -> {
            val captured = capture(match, endpoint.captureName)
            if (captured.isBlank()) {
                unresolved(role, "Capture '${endpoint.captureName}' is empty or absent.")
            } else {
                val ids = endpoint.bindings.filter { it.capturedValue == captured }.map { it.participantId }.distinct()
                when (ids.size) {
                    1 -> existingParticipant(ids.single(), role, participantIds)
                    0 -> unresolved(role, "Capture '${endpoint.captureName}' value '$captured' has no participant binding.", endpoint.captureName, captured)
                    else -> ambiguous(role, captured, ids, endpoint.captureName)
                }
            }
        }
        is DiagramRuleEndpoint.ExplicitActor -> {
            if (endpoint.id.isBlank()) unresolved(role, "Explicit actor id is blank.")
            else EndpointResolution(participantId = endpoint.id, explicitActor = DiagramParticipant(endpoint.id, endpoint.label, ParticipantKind.ACTOR))
        }
        null -> {
            val value = substitute(legacyTemplate, match, entry)
            existingParticipant(value, role, participantIds, "Legacy template resolved '$value' but proposal evaluation never creates actors implicitly.")
        }
    }

    private fun existingParticipant(
        id: String,
        role: String,
        participantIds: Set<String>,
        missingDetail: String? = null,
    ): EndpointResolution = if (id.isNotBlank() && id in participantIds) {
        EndpointResolution(participantId = id)
    } else {
        unresolved(role, missingDetail ?: "Participant '$id' is not available.")
    }

    private fun unresolved(role: String, detail: String, captureName: String? = null, capturedValue: String? = null) = EndpointResolution(
        issues = listOf(DiagramProposalIssue("", null, DiagramProposalIssueKind.UNRESOLVED_CAPTURE, "$role endpoint: $detail", captureName, capturedValue)),
    )

    private fun ambiguous(role: String, captured: String, candidateIds: List<String>, captureName: String? = null) = EndpointResolution(
        issues = listOf(
            DiagramProposalIssue(
                "", null, DiagramProposalIssueKind.AMBIGUOUS_CAPTURE,
                "$role endpoint '$captured' resolves to ${candidateIds.joinToString()}.", captureName, captured, candidateIds,
            ),
        ),
    )

    private fun capture(match: MatchResult, name: String): String =
        runCatching { match.groups[name]?.value }.getOrNull().orEmpty()

    private fun substitute(template: String, match: MatchResult, entry: LogEntry): String =
        TEMPLATE_TOKEN.replace(template) { token ->
            val name = token.groupValues[1]
            if (name == "msg") entry.msg else capture(match, name)
        }

    private data class EndpointResolution(
        val participantId: String? = null,
        val explicitActor: DiagramParticipant? = null,
        val issues: List<DiagramProposalIssue> = emptyList(),
    )
}

data class DiagramProposalEvaluation(
    val candidates: List<DiagramRuleProposal> = emptyList(),
    val matchedEntryIds: Set<Int> = emptySet(),
    val resolvedEndpointIds: Set<String> = emptySet(),
    val samples: List<DiagramProposalSample> = emptyList(),
    val issues: List<DiagramProposalIssue> = emptyList(),
    val applyBlocked: Boolean = false,
) {
    /** Named views let adapters report the two actionable capture states without reimplementing filters. */
    val representativeSamples: List<DiagramProposalSample> get() = samples
    val unresolvedCaptures: List<DiagramProposalIssue>
        get() = issues.filter { it.kind == DiagramProposalIssueKind.UNRESOLVED_CAPTURE }
    val ambiguousCaptures: List<DiagramProposalIssue>
        get() = issues.filter { it.kind == DiagramProposalIssueKind.AMBIGUOUS_CAPTURE }
}

data class DiagramRuleProposal(
    val ruleId: String,
    val enabled: Boolean = true,
    val matchedEntryIds: Set<Int> = emptySet(),
    val resolvedEndpointIds: Set<String> = emptySet(),
    val samples: List<DiagramProposalSample> = emptyList(),
    /** Explicit actor declarations are safe candidates, never implicit actor creation. */
    val explicitActors: List<DiagramParticipant> = emptyList(),
    val issues: List<DiagramProposalIssue> = emptyList(),
    val applyBlocked: Boolean = false,
) {
    val representativeSamples: List<DiagramProposalSample> get() = samples
    val unresolvedCaptures: List<DiagramProposalIssue>
        get() = issues.filter { it.kind == DiagramProposalIssueKind.UNRESOLVED_CAPTURE }
    val ambiguousCaptures: List<DiagramProposalIssue>
        get() = issues.filter { it.kind == DiagramProposalIssueKind.AMBIGUOUS_CAPTURE }
}

data class DiagramProposalSample(
    val ruleId: String,
    val entryId: Int,
    val message: String,
    val fromParticipantId: String?,
    val toParticipantId: String?,
    val label: String,
)

enum class DiagramProposalIssueKind(val blocksApply: Boolean) {
    INVALID_PATTERN(true),
    UNRESOLVED_CAPTURE(true),
    AMBIGUOUS_CAPTURE(true),
}

data class DiagramProposalIssue(
    val ruleId: String,
    val entryId: Int?,
    val kind: DiagramProposalIssueKind,
    val detail: String,
    val captureName: String? = null,
    val capturedValue: String? = null,
    val candidateParticipantIds: List<String> = emptyList(),
)

data class DiagramManualSeedProposal(
    val seed: ManualDiagramInteraction,
    val unresolvedParticipantIds: List<String>,
    val applyBlocked: Boolean,
)

private val TEMPLATE_TOKEN = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")
