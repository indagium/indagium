package com.indagium.diagram

import com.indagium.model.LogEntry

/**
 * Deterministic projection of a durable [ManualDiagramDocument].  It deliberately has no source
 * index dependency: source inference may seed a document, but accepted manual interactions are
 * authoritative and remain renderable when the index is absent or stale.
 */
internal fun buildManualSequenceDiagram(
    spec: SeqDiagramSpec,
    entries: List<LogEntry>,
    participants: List<DiagramParticipant>,
    coverage: DiagramCoverage,
    warnings: MutableList<String>,
): SeqDiagram {
    val entryById = entries.associateBy { it.id }
    val candidateInteractions = spec.manualDocument.interactions.withIndex()
        .filter { it.value.enabled }
        .sortedWith(compareBy<IndexedValue<ManualDiagramInteraction>> { it.value.order }.thenBy { it.index })
    // A manual document is the authoritative user-authored model. In particular, its initial
    // draft must retain every selected log event; the inferred builder's structural-arrow cap
    // must not silently delete manual rows.
    val resolvedInteractions = candidateInteractions.mapNotNull { indexed ->
        val interaction = indexed.value
        if (interaction.id.isBlank()) {
            warnings += "Interaction with an empty id was ignored."
            return@mapNotNull null
        }
        val representedInRange = interaction.sourceEntryIds.filterTo(linkedSetOf()) { it in entryById }
        if (representedInRange.size != interaction.sourceEntryIds.size && representedInRange.isNotEmpty()) {
            warnings += "Interaction '${interaction.id}' references log rows outside the current selection."
        }
        val entry = entries.firstOrNull { it.id in representedInRange }
        if (entry != null) {
            return@mapNotNull ManualRenderItem(
                interaction = interaction,
                representedEntryIds = representedInRange,
                entryId = entry.id,
                ts = entry.ts,
                level = entry.level,
            )
        }
        val anchorTs = interaction.renderAnchorTs
        val anchorLevel = interaction.renderAnchorLevel
        if (anchorTs.isNullOrBlank() || anchorLevel == null) {
            warnings += "Interaction '${interaction.id}' has no selected log evidence."
            return@mapNotNull null
        }
        // A durable manual interaction is authoritative. Its persisted anchor keeps it renderable
        // after the current range/filter no longer contains its original log rows.
        ManualRenderItem(
            interaction = interaction,
            representedEntryIds = interaction.sourceEntryIds,
            entryId = interaction.sourceEntryIds.minOrNull() ?: 0,
            ts = anchorTs,
            level = anchorLevel,
        )
    }
    val activeParticipantIds = resolvedInteractions.flatMap { item ->
        listOf(item.interaction.fromParticipantId, item.interaction.toParticipantId)
    }.toSet()
    val orderedParticipants = orderParticipants(
        participants.filter { it.id in activeParticipantIds },
        spec.lifelineOrder,
    )
    val indexById = orderedParticipants.mapIndexed { index, participant -> participant.id to index }.toMap()
    val messages = mutableListOf<DiagramMessage>()
    val indexByInteractionId = linkedMapOf<String, Int>()

    resolvedInteractions.forEach { item ->
            val interaction = item.interaction
            if (interaction.id in indexByInteractionId) {
                warnings += "Duplicate interaction '${interaction.id}' was ignored."
                return@forEach
            }
            val from = indexById[interaction.fromParticipantId]
            val to = indexById[interaction.toParticipantId]
            if (from == null || to == null) {
                warnings += "Interaction '${interaction.id}' references an unavailable lifeline."
                return@forEach
            }
            val kind = if (from == to && interaction.kind != MessageKind.RETURN) MessageKind.SELF else interaction.kind
            val origin = MessageOriginKey(
                entryId = item.entryId,
                manualInteractionId = interaction.id,
            )
            indexByInteractionId[interaction.id] = messages.size
            messages += DiagramMessage(
                fromIdx = from,
                toIdx = to,
                label = manualLabel(interaction, spec.options.labelMaxChars),
                entryId = item.entryId,
                ts = item.ts,
                level = item.level,
                kind = kind,
                evidence = MessageEvidence.MANUAL_OVERRIDE,
                primary = true,
                representedEntryIds = item.representedEntryIds,
                originKeys = setOf(origin),
            )
    }

    val frames = spec.manualDocument.groups.filter { it.enabled }.mapNotNull { group ->
        val indices = group.interactionIds.mapNotNull(indexByInteractionId::get)
        if (indices.isEmpty()) {
            warnings += "Group '${group.id}' has no enabled interactions."
            null
        } else {
            DiagramFrame(group.label, null, indices.min(), indices.max(), depth = 0)
        }
    }
    val notes = spec.manualDocument.notes.filter { it.enabled }.mapNotNull { note ->
        val participant = indexById[note.participantId]
        val after = indexByInteractionId[note.afterInteractionId]
        if (participant == null || after == null) {
            warnings += "Note '${note.id}' has no available anchor."
            null
        } else {
            DiagramNoteMark(participant, after, note.text, note.isError)
        }
    }
    val activations = if (spec.options.activationPolicy == ActivationPolicy.NONE) emptyList() else {
        spec.manualDocument.activations.filter { it.enabled }.mapNotNull { activation ->
            val participant = indexById[activation.participantId]
            val start = indexByInteractionId[activation.startInteractionId]
            val end = indexByInteractionId[activation.endInteractionId]
            if (participant == null || start == null || end == null) {
                warnings += "Activation '${activation.id}' has no available boundary."
                null
            } else {
                DiagramActivationSpan(participant, minOf(start, end), maxOf(start, end), MessageEvidence.MANUAL_OVERRIDE)
            }
        }
    }
    return SeqDiagram(
        spec = spec,
        participants = orderedParticipants,
        messages = messages,
        frames = frames,
        notes = notes,
        activationSpans = activations,
        truncated = false,
        scannedEntries = entries.size,
        coverage = coverage,
        warnings = warnings,
        traceMode = SourceTraceMode.DISABLED,
    )
}

/** Formats parameters structurally instead of trying to parse a user-edited display label. */
internal fun manualLabel(interaction: ManualDiagramInteraction, maxChars: Int = Int.MAX_VALUE): String {
    val visibility = when (interaction.visibility) {
        ManualOperationVisibility.PUBLIC -> "+"
        ManualOperationVisibility.PROTECTED -> "#"
        ManualOperationVisibility.PACKAGE -> "~"
        ManualOperationVisibility.PRIVATE -> "-"
        ManualOperationVisibility.UNSPECIFIED -> ""
    }
    interaction.label?.takeIf { it.isNotBlank() }?.let { return visibility + it }
    val arguments = interaction.parameters.joinToString(", ") { parameter ->
        if (parameter.name.isBlank()) parameter.value else "${parameter.name}=${parameter.value}"
    }
    val call = interaction.operation.ifBlank { "event" } + "($arguments)"
    val label = interaction.result?.takeIf { it.isNotBlank() }?.let { "$call: $it" } ?: call
    return visibility + truncateManualLabel(label, maxChars)
}

private fun truncateManualLabel(value: String, maxChars: Int): String {
    val limit = maxChars.coerceAtLeast(1)
    if (value.length <= limit) return value
    if (limit == 1) return "…"
    return value.take(limit - 1) + "…"
}

private data class ManualRenderItem(
    val interaction: ManualDiagramInteraction,
    val representedEntryIds: Set<Int>,
    val entryId: Int,
    val ts: String,
    val level: com.indagium.model.LogLevel,
)

/** Order every configured lifeline exactly once; unknown persisted ids are intentionally ignored. */
internal fun orderParticipants(participants: List<DiagramParticipant>, lifelineOrder: List<String>): List<DiagramParticipant> {
    if (lifelineOrder.isEmpty()) return participants
    val byId = participants.associateBy { it.id }
    val requested = lifelineOrder.distinct().mapNotNull(byId::get)
    return requested + participants.filter { it.id !in lifelineOrder }
}

/** Remaps every participant-indexed render construct after a user changes lifeline order. */
internal fun reorderDiagramLifelines(diagram: SeqDiagram, lifelineOrder: List<String>): SeqDiagram {
    val ordered = orderParticipants(diagram.participants, lifelineOrder)
    if (ordered == diagram.participants) return diagram
    val newIndexById = ordered.mapIndexed { index, participant -> participant.id to index }.toMap()
    fun remap(index: Int): Int = diagram.participants.getOrNull(index)?.id?.let(newIndexById::get) ?: index
    return diagram.copy(
        participants = ordered,
        messages = diagram.messages.map { message -> message.copy(fromIdx = remap(message.fromIdx), toIdx = remap(message.toIdx)) },
        notes = diagram.notes.map { note -> note.copy(participantIdx = remap(note.participantIdx)) },
        activationSpans = diagram.activationSpans.map { span -> span.copy(participantIdx = remap(span.participantIdx)) },
    )
}
