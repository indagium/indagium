@file:Suppress("ReturnCount", "MagicNumber")

package com.indagium.diagram

import com.indagium.model.LogLevel

/** A canonical message plus the evidence occurrences it owns. */
data class CanonicalManualMessage(
    val definition: ManualDiagramMessageDefinition,
    val occurrences: List<ManualMessageOccurrence>,
) {
    val firstOccurrence: ManualMessageOccurrence get() = occurrences.first()
}

data class ManualCanonicalDiagnostic(
    val messageId: String?,
    val message: String,
    val isError: Boolean = false,
)

data class ManualCanonicalization(
    val messages: List<CanonicalManualMessage>,
    val diagnostics: List<ManualCanonicalDiagnostic>,
)

enum class ManualDocumentIssueCode {
    DUPLICATE_INTERACTION_ID,
    DUPLICATE_MESSAGE_ID,
    DUPLICATE_OCCURRENCE_OWNERSHIP,
    DANGLING_OCCURRENCE,
    UNOWNED_OCCURRENCE,
    DUPLICATE_CAPTURE_NAME,
    CAPTURE_TOKEN_MISMATCH,
    CAPTURE_VALUE_MISMATCH,
    LABEL_CAPTURE_MISMATCH,
    INVALID_ENDPOINT,
    INVALID_TARGETLESS_STATE,
    INVALID_REPEAT_POLICY,
    INVALID_ORDER_OVERRIDE,
}

data class ManualDocumentValidationIssue(
    val code: ManualDocumentIssueCode,
    val messageId: String? = null,
    val occurrenceId: String? = null,
    val message: String,
)

data class ManualDocumentValidation(
    val issues: List<ManualDocumentValidationIssue>,
) {
    val isValid: Boolean get() = issues.isEmpty()
    val errors: List<ManualDocumentValidationIssue> get() = issues
}

/**
 * Validates the durable message projection without consulting UI state or a source index.  An
 * empty [ManualDiagramDocument.messages] list is the explicit legacy marker, so legacy
 * occurrence-only documents are checked for duplicate interaction ids but are not rejected for
 * lacking message ownership.  Once v5 messages exist, every occurrence must be owned exactly once.
 */
fun validateManualDocument(document: ManualDiagramDocument): ManualDocumentValidation {
    val issues = mutableListOf<ManualDocumentValidationIssue>()
    val interactionsById = linkedMapOf<String, ManualDiagramInteraction>()
    document.interactions.forEach { interaction ->
        if (interaction.id.isBlank()) {
            issues += ManualDocumentValidationIssue(
                ManualDocumentIssueCode.DUPLICATE_INTERACTION_ID,
                occurrenceId = interaction.id,
                message = "An occurrence id must not be blank",
            )
        } else if (interactionsById.put(interaction.id, interaction) != null) {
            issues += ManualDocumentValidationIssue(
                ManualDocumentIssueCode.DUPLICATE_INTERACTION_ID,
                occurrenceId = interaction.id,
                message = "Occurrence '${interaction.id}' is declared more than once",
            )
        }
    }
    if (document.messages.isEmpty()) return ManualDocumentValidation(issues)

    val messageIds = mutableSetOf<String>()
    val ownerByOccurrence = linkedMapOf<String, String>()
    document.messages.forEach { definition ->
        if (definition.id.isBlank()) {
            issues += ManualDocumentValidationIssue(
                ManualDocumentIssueCode.DUPLICATE_MESSAGE_ID,
                message = "A message definition id must not be blank",
            )
        } else if (!messageIds.add(definition.id)) {
            issues += ManualDocumentValidationIssue(
                ManualDocumentIssueCode.DUPLICATE_MESSAGE_ID,
                messageId = definition.id,
                message = "Message '${definition.id}' is declared more than once",
            )
        }
        val occurrenceIds = definition.occurrenceIds
        if (occurrenceIds.isEmpty()) {
            issues += ManualDocumentValidationIssue(
                ManualDocumentIssueCode.DANGLING_OCCURRENCE,
                messageId = definition.id,
                message = "Message '${definition.id}' must own at least one occurrence",
            )
        }
        occurrenceIds.forEach { occurrenceId ->
            val interaction = interactionsById[occurrenceId]
            if (interaction == null) {
                issues += ManualDocumentValidationIssue(
                    ManualDocumentIssueCode.DANGLING_OCCURRENCE,
                    messageId = definition.id,
                    occurrenceId = occurrenceId,
                    message = "Message '${definition.id}' references missing occurrence '$occurrenceId'",
                )
            }
            val previousOwner = ownerByOccurrence.putIfAbsent(occurrenceId, definition.id)
            if (previousOwner != null) {
                issues += ManualDocumentValidationIssue(
                    ManualDocumentIssueCode.DUPLICATE_OCCURRENCE_OWNERSHIP,
                    messageId = definition.id,
                    occurrenceId = occurrenceId,
                    message = "Occurrence '$occurrenceId' is owned by both '$previousOwner' and '${definition.id}'",
                )
            }
        }
        validateMessageDefinition(definition, occurrenceIds.mapNotNull(interactionsById::get), issues)
    }
    interactionsById.keys.filterNot(ownerByOccurrence::containsKey).forEach { occurrenceId ->
        issues += ManualDocumentValidationIssue(
            ManualDocumentIssueCode.UNOWNED_OCCURRENCE,
            occurrenceId = occurrenceId,
            message = "Occurrence '$occurrenceId' is not owned by a message definition",
        )
    }
    validateOrderOverrides(document.messages, interactionsById, issues)
    return ManualDocumentValidation(issues)
}

private fun validateMessageDefinition(
    definition: ManualDiagramMessageDefinition,
    interactions: List<ManualDiagramInteraction>,
    issues: MutableList<ManualDocumentValidationIssue>,
) {
    val captureNames = definition.match.captures.map { it.name }
    if (captureNames.distinct().size != captureNames.size) {
        issues += ManualDocumentValidationIssue(
            ManualDocumentIssueCode.DUPLICATE_CAPTURE_NAME,
            messageId = definition.id,
            message = "Message '${definition.id}' declares a capture name more than once",
        )
    }
    val matchTokens = manualCaptureTokenNames(definition.match.textPattern)
    if (matchTokens.distinct().size != matchTokens.size || matchTokens.toSet() != captureNames.toSet()) {
        issues += ManualDocumentValidationIssue(
            ManualDocumentIssueCode.CAPTURE_TOKEN_MISMATCH,
            messageId = definition.id,
            message = "Message '${definition.id}' match tokens and capture declarations disagree",
        )
    }
    val labelTokens = manualCaptureTokenNames(definition.labelTemplate)
    if (!labelTokens.all { it in captureNames }) {
        issues += ManualDocumentValidationIssue(
            ManualDocumentIssueCode.LABEL_CAPTURE_MISMATCH,
            messageId = definition.id,
            message = "Message '${definition.id}' label refers to an undeclared capture",
        )
    }
    if (definition.fromParticipantId.isBlank() || definition.toParticipantId?.isBlank() == true) {
        issues += ManualDocumentValidationIssue(
            ManualDocumentIssueCode.INVALID_ENDPOINT,
            messageId = definition.id,
            message = "Message '${definition.id}' has a blank endpoint",
        )
    }
    val targetless = definition.toParticipantId == null
    val targetlessStateIsValid = if (targetless) {
        definition.state == ManualMessageStateKind.NEEDS_TARGET || definition.state == ManualMessageStateKind.HIDDEN
    } else {
        definition.state != ManualMessageStateKind.NEEDS_TARGET
    }
    if (!targetlessStateIsValid || (definition.visibility == ManualMessageVisibility.HIDDEN && definition.state != ManualMessageStateKind.HIDDEN)) {
        issues += ManualDocumentValidationIssue(
            ManualDocumentIssueCode.INVALID_TARGETLESS_STATE,
            messageId = definition.id,
            message = "Message '${definition.id}' has an endpoint/state combination that is not durable",
        )
    }
    if (definition.repeatPolicy.collapseThreshold < 1) {
        issues += ManualDocumentValidationIssue(
            ManualDocumentIssueCode.INVALID_REPEAT_POLICY,
            messageId = definition.id,
            message = "Message '${definition.id}' has a non-positive repeat threshold",
        )
    }
    interactions.forEach { interaction ->
        val actualCaptures = matchManualMessageText(definition.match, manualInteractionMatchText(interaction))
        if (actualCaptures == null) {
            issues += ManualDocumentValidationIssue(
                ManualDocumentIssueCode.CAPTURE_TOKEN_MISMATCH,
                messageId = definition.id,
                occurrenceId = interaction.id,
                message = "Occurrence '${interaction.id}' does not match message '${definition.id}'",
            )
        } else if (interaction.captureValues != actualCaptures) {
            issues += ManualDocumentValidationIssue(
                ManualDocumentIssueCode.CAPTURE_VALUE_MISMATCH,
                messageId = definition.id,
                occurrenceId = interaction.id,
                message = "Occurrence '${interaction.id}' has capture values inconsistent with message '${definition.id}'",
            )
        }
    }
}

private fun validateOrderOverrides(
    definitions: List<ManualDiagramMessageDefinition>,
    interactionsById: Map<String, ManualDiagramInteraction>,
    issues: MutableList<ManualDocumentValidationIssue>,
) {
    val timestampsByMessage = definitions.associate { definition ->
        definition.id to definition.occurrenceIds.mapNotNull { occurrenceId ->
            val interaction = interactionsById[occurrenceId] ?: return@mapNotNull null
            durableEvidence(interaction).firstOrNull()?.timestamp?.let(::manualTimestampMillis)
        }.toSet()
    }
    definitions.mapNotNull { definition -> definition.orderOverride?.let { definition to it } }
        .forEach { (definition, override) ->
            val timestamps = timestampsByMessage[definition.id].orEmpty()
            val tied = definitions.filter { other ->
                other.id != definition.id && override.tiedTimestampMillis in timestampsByMessage[other.id].orEmpty()
            }
            if (override.tiedTimestampMillis !in timestamps || tied.isEmpty() || override.tieRank < 0 || override.tieRank >= tied.size + 1) {
                issues += ManualDocumentValidationIssue(
                    ManualDocumentIssueCode.INVALID_ORDER_OVERRIDE,
                    messageId = definition.id,
                    message = "Message '${definition.id}' order override is not inside a real same-timestamp bucket",
                )
            }
        }
    val usedRanks = definitions.mapNotNull { definition ->
        definition.orderOverride?.let { override -> override.tiedTimestampMillis to override.tieRank }
    }.groupBy({ it.first }, { it.second })
    usedRanks.forEach { (timestamp, ranks) ->
        if (ranks.size != ranks.toSet().size) {
            issues += ManualDocumentValidationIssue(
                ManualDocumentIssueCode.INVALID_ORDER_OVERRIDE,
                message = "Timestamp $timestamp has duplicate message tie ranks",
            )
        }
    }
}

/**
 * One adapter for legacy and version-5 manual documents. Every consumer uses this projection so
 * queue grouping, rendering, regeneration, and command validation cannot invent different rows.
 */
fun canonicalManualMessages(document: ManualDiagramDocument): List<CanonicalManualMessage> =
    canonicalizeManualMessages(document).messages

fun canonicalizeManualMessages(document: ManualDiagramDocument): ManualCanonicalization {
    if (document.messages.isNotEmpty()) return canonicalizeExplicitMessages(document)
    return canonicalizeLegacyInteractions(document)
}

/** Normalizes a legacy document in memory. It does not write a note, so merely opening a v1-v4
 * diagram never silently changes the user's file. */
fun normalizeManualDocument(document: ManualDiagramDocument): ManualDiagramDocument {
    if (document.messages.isNotEmpty()) return document
    val canonical = canonicalizeLegacyInteractions(document)
    val capturesByInteraction = canonical.messages
        .flatMap { message -> message.occurrences.map { it.interactionId to it.captureValues } }
        .toMap()
    return document.copy(
        interactions = document.interactions.map { interaction ->
            interaction.copy(
                captureValues = capturesByInteraction[interaction.id].orEmpty(),
                matchText = interaction.matchText ?: manualInteractionMatchText(interaction),
            )
        },
        messages = canonical.messages.map { it.definition },
    )
}

data class ManualMessageOrderOverrideResult(
    val document: ManualDiagramDocument,
    val applied: Boolean,
    val reason: String? = null,
)

/** Pins a message only inside the bucket of messages sharing its real derived timestamp. */
fun setManualMessageOrderOverride(
    document: ManualDiagramDocument,
    messageId: String,
    tiedTimestampMillis: Long,
    tieRank: Int,
): ManualMessageOrderOverrideResult {
    if (tieRank < 0) return ManualMessageOrderOverrideResult(document, false, "Tie rank must be non-negative")
    val canonical = canonicalManualMessages(document)
    val target = canonical.firstOrNull { it.definition.id == messageId }
        ?: return ManualMessageOrderOverrideResult(document, false, "Unknown message")
    val timestamp = target.occurrences.mapNotNull { it.derivedOrder.timestampMillis }.minOrNull()
        ?: return ManualMessageOrderOverrideResult(document, false, "Only timestamped messages can be pinned")
    if (timestamp != tiedTimestampMillis) {
        return ManualMessageOrderOverrideResult(document, false, "A pin may only change order within the message timestamp")
    }
    val tiedMessages = canonical.filter { message ->
        message.occurrences.any { it.derivedOrder.timestampMillis == timestamp }
    }
    if (tiedMessages.size < 2) {
        return ManualMessageOrderOverrideResult(document, false, "There are no same-timestamp neighbors to pin")
    }
    if (tieRank >= tiedMessages.size) {
        return ManualMessageOrderOverrideResult(document, false, "Tie rank is outside the same-timestamp bucket")
    }
    val collision = tiedMessages.firstOrNull { it.definition.id != messageId && it.definition.orderOverride?.let { override ->
        override.tiedTimestampMillis == timestamp && override.tieRank == tieRank
    } == true }
    if (collision != null) {
        return ManualMessageOrderOverrideResult(document, false, "That tie rank is already pinned by ${collision.definition.id}")
    }
    return ManualMessageOrderOverrideResult(
        document.copy(messages = document.messages.map { definition ->
            if (definition.id == messageId) definition.copy(
                orderOverride = ManualMessageOrderOverride(timestamp, tieRank),
                state = ManualMessageStateKind.EDITED,
                authoring = ManualInteractionAuthoring.EDITED,
            ) else definition
        }),
        true,
    )
}

/** Canonical message order derived from evidence, not from message-list position. */
fun derivedManualMessageOrder(document: ManualDiagramDocument): List<CanonicalManualMessage> =
    canonicalManualMessages(document).sortedWith(
        compareBy<CanonicalManualMessage> { message ->
            message.occurrences.mapNotNull { it.derivedOrder.timestampMillis }.minOrNull() ?: Long.MAX_VALUE
        }.thenBy { message ->
            val override = message.definition.orderOverride
            override?.tiedTimestampMillis?.let { timestamp ->
                if (timestamp == message.occurrences.firstOrNull()?.derivedOrder?.timestampMillis) {
                    override.tieRank
                } else {
                    null
                }
            } ?: Int.MAX_VALUE
        }.thenBy { message -> message.occurrences.minOfOrNull { it.derivedOrder.sourceOrdinal } ?: Long.MAX_VALUE }
            .thenBy { it.definition.id },
    )

data class ManualMessageCommandResult(
    val document: ManualDiagramDocument,
    val applied: Boolean,
    val reason: String? = null,
)

/**
 * Conservatively merges message definitions. Endpoints and kind must agree, and the compiler must
 * be able to prove one durable match against every selected occurrence. The legacy occurrence
 * projection is normalized first, so the operation never creates a second grouping authority.
 */
fun mergeManualMessageDefinitions(
    document: ManualDiagramDocument,
    messageIds: Set<String>,
    mergedMessageId: String,
): ManualMessageCommandResult {
    val normalized = normalizeManualDocument(document)
    val selected = normalized.messages.filter { it.id in messageIds }
    if (selected.size < 2) return ManualMessageCommandResult(document, false, "Select at least two messages")
    val requestedId = mergedMessageId.trim()
    if (requestedId.isEmpty()) return ManualMessageCommandResult(document, false, "Merged message id is required")
    val finalId = if (requestedId.startsWith("manual-message:")) requestedId else "manual-message:$requestedId"
    if (normalized.messages.any { it.id == finalId && it !in selected }) {
        return ManualMessageCommandResult(document, false, "Merged message id already exists")
    }
    val first = selected.first()
    if (selected.any {
            it.fromParticipantId != first.fromParticipantId ||
                it.toParticipantId != first.toParticipantId ||
                it.kind != first.kind
        }) {
        return ManualMessageCommandResult(document, false, "Messages must have identical endpoints and kind")
    }
    val interactionById = normalized.interactions.associateBy { it.id }
    val occurrenceIds = normalized.interactions
        .map { it.id }
        .filter { occurrenceId -> selected.any { occurrenceId in it.occurrenceIds } }
    val compilation = compileManualMessageMatch(
        occurrenceIds.mapNotNull { occurrenceId ->
            interactionById[occurrenceId]?.let { interaction ->
                ManualMessageMatchInput(occurrenceId, manualInteractionMatchText(interaction))
            }
        },
    )
    val match = compilation.match ?: return ManualMessageCommandResult(
        document,
        false,
        compilation.error ?: "The selected occurrences cannot be merged safely",
    )
    val merged = first.copy(
        id = finalId,
        occurrenceIds = occurrenceIds,
        match = match,
        labelTemplate = if (selected.map { it.labelTemplate }.distinct().size == 1) first.labelTemplate else match.textPattern,
        repeatPolicy = first.repeatPolicy,
        state = ManualMessageStateKind.EDITED,
        authoring = ManualInteractionAuthoring.EDITED,
        orderOverride = null,
    )
    val updatedInteractions = normalized.interactions.map { interaction ->
        if (interaction.id !in occurrenceIds) interaction else interaction.copy(
            groupKey = finalId,
            captureValues = compilation.captureValuesByOccurrence[interaction.id].orEmpty(),
            authoring = ManualInteractionAuthoring.EDITED,
        )
    }
    val firstIndex = normalized.messages.indexOfFirst { it.id in selected.map(ManualDiagramMessageDefinition::id).toSet() }
    val selectedIds = selected.map(ManualDiagramMessageDefinition::id).toSet()
    val messages = buildList {
        normalized.messages.forEachIndexed { index, definition ->
            if (index == firstIndex) add(merged)
            if (definition.id !in selectedIds) add(definition)
        }
    }
    val result = normalized.copy(interactions = updatedInteractions, messages = messages)
    return if (validateManualDocument(result).isValid) {
        ManualMessageCommandResult(result, true)
    } else {
        ManualMessageCommandResult(document, false, "The merged message would violate durable ownership or capture invariants")
    }
}

/** Splits one multi-occurrence message into independently editable occurrence messages. */
fun unmergeManualMessageDefinition(
    document: ManualDiagramDocument,
    messageId: String,
): ManualMessageCommandResult {
    val normalized = normalizeManualDocument(document)
    val definition = normalized.messages.firstOrNull { it.id == messageId }
        ?: return ManualMessageCommandResult(document, false, "Unknown message")
    if (definition.occurrenceIds.size < 2) return ManualMessageCommandResult(document, false, "The message has one occurrence")
    val interactionsById = normalized.interactions.associateBy { it.id }
    val replacements = definition.occurrenceIds.mapIndexedNotNull { index, occurrenceId ->
        val interaction = interactionsById[occurrenceId] ?: return@mapIndexedNotNull null
        val text = manualInteractionMatchText(interaction)
        ManualDiagramMessageDefinition(
            id = "${definition.id}:$index",
            occurrenceIds = listOf(occurrenceId),
            match = ManualMessageMatch(textPattern = text),
            fromParticipantId = interaction.fromParticipantId,
            toParticipantId = interaction.toParticipantId,
            labelTemplate = interaction.label ?: text,
            kind = interaction.kind,
            repeatPolicy = definition.repeatPolicy.copy(mode = ManualMessageRepeatMode.EVERY_OCCURRENCE),
            visibility = if (interaction.enabled) ManualMessageVisibility.VISIBLE else ManualMessageVisibility.HIDDEN,
            state = if (interaction.toParticipantId == null) ManualMessageStateKind.NEEDS_TARGET
            else if (interaction.enabled) ManualMessageStateKind.EDITED else ManualMessageStateKind.HIDDEN,
            authoring = ManualInteractionAuthoring.EDITED,
        )
    }
    val messages = normalized.messages.flatMap { current ->
        if (current.id == messageId) replacements else listOf(current)
    }
    val result = normalized.copy(
        interactions = normalized.interactions.map { interaction ->
            if (interaction.id in definition.occurrenceIds) interaction.copy(
                groupKey = null,
                captureValues = emptyMap(),
                authoring = ManualInteractionAuthoring.EDITED,
            ) else interaction
        },
        messages = messages,
    )
    return if (validateManualDocument(result).isValid) ManualMessageCommandResult(result, true)
    else ManualMessageCommandResult(document, false, "The unmerge would leave invalid durable ownership")
}

/**
 * Moves one occurrence out of a repeated message. This is intentionally a separate command from
 * unmerge: the remaining message keeps its match/capture contract while the moved occurrence gets
 * a literal one-occurrence definition.
 */
fun moveManualMessageOccurrenceOut(
    document: ManualDiagramDocument,
    messageId: String,
    occurrenceId: String,
    movedMessageId: String,
): ManualMessageCommandResult {
    val normalized = normalizeManualDocument(document)
    val definition = normalized.messages.firstOrNull { it.id == messageId }
        ?: return ManualMessageCommandResult(document, false, "Unknown message")
    if (definition.occurrenceIds.size < 2 || occurrenceId !in definition.occurrenceIds) {
        return ManualMessageCommandResult(document, false, "Only a repeated message occurrence can be moved out")
    }
    val newId = movedMessageId.trim()
    if (newId.isEmpty() || normalized.messages.any { it.id == newId }) {
        return ManualMessageCommandResult(document, false, "Moved message id is missing or already exists")
    }
    val interaction = normalized.interactions.firstOrNull { it.id == occurrenceId }
        ?: return ManualMessageCommandResult(document, false, "Unknown occurrence")
    val text = manualInteractionMatchText(interaction)
    val moved = ManualDiagramMessageDefinition(
        id = newId,
        occurrenceIds = listOf(occurrenceId),
        match = ManualMessageMatch(textPattern = text),
        fromParticipantId = interaction.fromParticipantId,
        toParticipantId = interaction.toParticipantId,
        labelTemplate = interaction.label ?: text,
        kind = interaction.kind,
        repeatPolicy = definition.repeatPolicy.copy(mode = ManualMessageRepeatMode.EVERY_OCCURRENCE),
        visibility = if (interaction.enabled) ManualMessageVisibility.VISIBLE else ManualMessageVisibility.HIDDEN,
        state = if (interaction.toParticipantId == null) ManualMessageStateKind.NEEDS_TARGET
        else if (interaction.enabled) ManualMessageStateKind.EDITED else ManualMessageStateKind.HIDDEN,
        authoring = ManualInteractionAuthoring.EDITED,
    )
    val remaining = definition.copy(
        occurrenceIds = definition.occurrenceIds.filterNot { it == occurrenceId },
        state = if (definition.toParticipantId == null) ManualMessageStateKind.NEEDS_TARGET else definition.state,
    )
    val messages = normalized.messages.flatMap { current ->
        when {
            current.id != messageId -> listOf(current)
            else -> listOf(remaining, moved)
        }
    }
    val result = normalized.copy(
        interactions = normalized.interactions.map { current ->
            if (current.id == occurrenceId) current.copy(
                groupKey = newId,
                captureValues = emptyMap(),
                authoring = ManualInteractionAuthoring.EDITED,
            )
            else current
        },
        messages = messages,
    )
    return if (validateManualDocument(result).isValid) ManualMessageCommandResult(result, true)
    else ManualMessageCommandResult(document, false, "The move would leave invalid durable ownership")
}

private fun canonicalizeExplicitMessages(document: ManualDiagramDocument): ManualCanonicalization {
    val interactionById = document.interactions.associateBy { it.id }
    val diagnostics = mutableListOf<ManualCanonicalDiagnostic>()
    val validation = validateManualDocument(document)
    validation.issues.forEach { issue ->
        diagnostics += ManualCanonicalDiagnostic(issue.messageId, issue.message, isError = true)
    }
    val invalidMessageIds = validation.issues.mapNotNull { it.messageId }.toSet()
    val referenced = mutableSetOf<String>()
    val messages = document.messages.mapNotNull { definition ->
        if (definition.id in invalidMessageIds) return@mapNotNull null
        if (definition.id.isBlank()) {
            diagnostics += ManualCanonicalDiagnostic(null, "A message definition has an empty id", isError = true)
            return@mapNotNull null
        }
        val interactions = definition.occurrenceIds.mapNotNull { occurrenceId ->
            val interaction = interactionById[occurrenceId]
            if (interaction == null) {
                diagnostics += ManualCanonicalDiagnostic(
                    definition.id,
                    "Message references missing occurrence '$occurrenceId'",
                    isError = true,
                )
            } else {
                referenced += occurrenceId
            }
            interaction
        }
        if (interactions.isEmpty()) {
            diagnostics += ManualCanonicalDiagnostic(definition.id, "Message has no evidence occurrences", isError = true)
            return@mapNotNull null
        }
        val occurrences = interactions.map { interaction ->
            val evidence = durableEvidence(interaction)
            val derived = ManualDerivedOrder(
                timestampMillis = evidence.firstOrNull()?.timestamp?.let(::manualTimestampMillis),
                sourceOrdinal = evidence.firstOrNull()?.entryId?.toLong() ?: interaction.order,
            )
            val matchedCaptures = matchManualMessageText(definition.match, manualInteractionMatchText(interaction))
            val captured = matchedCaptures ?: emptyMap()
            if (matchedCaptures == null) {
                diagnostics += ManualCanonicalDiagnostic(
                    definition.id,
                    "Occurrence '${interaction.id}' does not match the authored pattern",
                    isError = true,
                )
            }
            ManualMessageOccurrence(interaction.id, captured, evidence, derived)
        }
        CanonicalManualMessage(definition, occurrences)
    }
    document.interactions.filter { it.id !in referenced }.forEach { interaction ->
        diagnostics += ManualCanonicalDiagnostic(
            null,
            "Unreferenced occurrence '${interaction.id}' was retained as a legacy message",
        )
    }
    return ManualCanonicalization(messages, diagnostics)
}

private fun canonicalizeLegacyInteractions(document: ManualDiagramDocument): ManualCanonicalization {
    val diagnostics = mutableListOf<ManualCanonicalDiagnostic>()
    val ordered = document.interactions.withIndex()
        .sortedWith(compareBy<IndexedValue<ManualDiagramInteraction>> { it.value.order }.thenBy { it.index })
    val buckets = linkedMapOf<String, MutableList<IndexedValue<ManualDiagramInteraction>>>()
    ordered.forEach { indexed ->
        buckets.getOrPut(legacyBucketKey(indexed.value)) { mutableListOf() } += indexed
    }
    val messages = mutableListOf<CanonicalManualMessage>()
    buckets.forEach { (bucketKey, members) ->
        // A legacy group may have been authored with inconsistent endpoints or state. Split it
        // conservatively; stable members retain the group identity and no evidence is discarded.
        val partitions = members.groupBy { legacyCompatibilityKey(it.value) }
        partitions.entries.forEachIndexed { partitionIndex, (_, partition) ->
            val interactions = partition.map { it.value }.sortedBy { it.order }
            val baseId = legacyMessageId(bucketKey, partitionIndex, partitions.size)
            val inputs = interactions.map { interaction ->
                ManualMessageMatchInput(interaction.id, manualInteractionMatchText(interaction))
            }
            val compilation = compileManualMessageMatch(inputs)
            val representative = interactions.first()
            val match = compilation.match ?: ManualMessageMatch(textPattern = manualInteractionMatchText(representative))
            if (compilation.error != null && interactions.size > 1) {
                diagnostics += ManualCanonicalDiagnostic(baseId, compilation.error)
            }
            val captureByInteraction = compilation.captureValuesByOccurrence
            val occurrences = interactions.map { interaction ->
                val evidence = durableEvidence(interaction)
                ManualMessageOccurrence(
                    interactionId = interaction.id,
                    captureValues = captureByInteraction[interaction.id].orEmpty().ifEmpty { interaction.captureValues },
                    evidence = evidence,
                    derivedOrder = ManualDerivedOrder(
                        timestampMillis = evidence.firstOrNull()?.timestamp?.let(::manualTimestampMillis),
                        sourceOrdinal = evidence.firstOrNull()?.entryId?.toLong() ?: interaction.order,
                    ),
                )
            }
            val policy = legacyRepeatPolicy(document)
            val definition = ManualDiagramMessageDefinition(
                id = baseId,
                occurrenceIds = occurrences.map { it.interactionId },
                match = match,
                fromParticipantId = representative.fromParticipantId,
                toParticipantId = representative.toParticipantId,
                labelTemplate = compilation.match?.textPattern?.let { displayCaptureTokens(it) }
                    ?: manualMessageTemplate(representative),
                kind = representative.kind,
                repeatPolicy = policy,
                visibility = if (interactions.all { !it.enabled }) ManualMessageVisibility.HIDDEN
                else ManualMessageVisibility.VISIBLE,
                state = legacyState(interactions),
                authoring = if (interactions.any { it.authoring == ManualInteractionAuthoring.EDITED }) {
                    ManualInteractionAuthoring.EDITED
                } else {
                    ManualInteractionAuthoring.AUTO
                },
            )
            messages += CanonicalManualMessage(definition, occurrences)
        }
    }
    return ManualCanonicalization(messages, diagnostics)
}

private fun legacyBucketKey(interaction: ManualDiagramInteraction): String =
    interaction.groupKey?.trim()?.takeUnless { it.isEmpty() } ?: "individual:${interaction.id}"

private fun legacyCompatibilityKey(interaction: ManualDiagramInteraction): String = listOf(
    interaction.fromParticipantId,
    interaction.toParticipantId.orEmpty(),
    interaction.kind.name,
    interaction.enabled,
    interaction.authoring.name,
).joinToString("\u0000")

private fun legacyMessageId(bucketKey: String, partitionIndex: Int, partitionCount: Int): String =
    if (partitionCount == 1) "manual-message:$bucketKey" else "manual-message:$bucketKey#$partitionIndex"

private fun legacyState(interactions: List<ManualDiagramInteraction>): ManualMessageStateKind = when {
    interactions.all { !it.enabled } -> ManualMessageStateKind.HIDDEN
    interactions.any { it.toParticipantId == null } -> ManualMessageStateKind.NEEDS_TARGET
    interactions.any { it.authoring == ManualInteractionAuthoring.EDITED } -> ManualMessageStateKind.EDITED
    else -> ManualMessageStateKind.AUTO
}

private fun legacyRepeatPolicy(document: ManualDiagramDocument): ManualMessageRepeatPolicy = when (
    document.repeatPresentation
) {
    ManualDiagramRepeatPresentation.CONSECUTIVE -> document.defaultRepeatPolicy
    ManualDiagramRepeatPresentation.EVERY_OCCURRENCE ->
        document.defaultRepeatPolicy.copy(mode = ManualMessageRepeatMode.EVERY_OCCURRENCE)
    ManualDiagramRepeatPresentation.FIRST_AND_LAST ->
        document.defaultRepeatPolicy.copy(mode = ManualMessageRepeatMode.FIRST_AND_LAST)
}

private fun durableEvidence(interaction: ManualDiagramInteraction): List<ManualDiagramEvidence> {
    if (interaction.evidence.isNotEmpty()) {
        return interaction.evidence.sortedWith(
            compareBy<ManualDiagramEvidence> { manualTimestampMillis(it.timestamp) ?: Long.MAX_VALUE }
                .thenBy { it.entryId },
        )
    }
    return interaction.sourceEntryIds.sorted().map { entryId ->
        ManualDiagramEvidence(entryId, interaction.renderAnchorTs.orEmpty(), interaction.renderAnchorLevel ?: LogLevel.I)
    }
}

private fun manualInteractionMatchText(interaction: ManualDiagramInteraction): String =
    interaction.matchText?.takeUnless { it.isBlank() } ?: interaction.label?.takeUnless { it.isBlank() }
    ?: manualLabel(interaction)

private fun displayCaptureTokens(value: String): String = value

/** Parses the logcat time-of-day representation without introducing a dependency on the log UI. */
internal fun manualTimestampMillis(value: String): Long? {
    val match = Regex("^(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,3}))?").find(value.trim()) ?: return null
    val hours = match.groupValues[1].toLongOrNull() ?: return null
    val minutes = match.groupValues[2].toLongOrNull() ?: return null
    val seconds = match.groupValues[3].toLongOrNull() ?: return null
    val millis = match.groupValues[4].padEnd(3, '0').toLongOrNull() ?: 0L
    if (hours >= 24 || minutes >= 60 || seconds >= 60) return null
    return ((hours * 60 + minutes) * 60 + seconds) * 1_000 + millis
}
