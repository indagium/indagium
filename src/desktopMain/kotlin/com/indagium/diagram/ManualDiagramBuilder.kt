@file:Suppress("CyclomaticComplexMethod")

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
    if (spec.manualDocument.messages.isNotEmpty()) {
        return buildCanonicalManualSequenceDiagram(spec, entries, participants, coverage, warnings)
    }
    val entryById = entries.associateBy { it.id }
    val candidateInteractions = spec.manualDocument.interactions.withIndex()
        .filter { it.value.enabled }
        .sortedWith(compareBy<IndexedValue<ManualDiagramInteraction>> { it.value.order }.thenBy { it.index })
    val resolvedInteractions = resolveManualRenderItems(candidateInteractions, entries, entryById, warnings)

    val activeParticipantIds = resolvedInteractions.flatMap { item ->
        listOfNotNull(item.interaction.fromParticipantId, item.interaction.toParticipantId)
    }.toSet()
    val orderedParticipants = orderParticipants(
        participants.filter { it.id in activeParticipantIds },
        spec.lifelineOrder,
    )
    val indexById = orderedParticipants.mapIndexed { index, participant -> participant.id to index }.toMap()
    val (messages, indexByInteractionId) = buildManualMessages(resolvedInteractions, indexById, spec, warnings)

    return SeqDiagram(
        spec = spec,
        participants = orderedParticipants,
        messages = messages,
        frames = manualFrames(spec, indexByInteractionId, warnings),
        notes = manualNotes(spec, indexById, indexByInteractionId, warnings),
        activationSpans = manualActivations(spec, indexById, indexByInteractionId, warnings),
        truncated = false,
        scannedEntries = entries.size,
        coverage = coverage,
        warnings = warnings,
        traceMode = SourceTraceMode.DISABLED,
    )
}

/** Renders the version-5 message aggregate. Legacy documents deliberately continue through the
 * compatibility path below until they are explicitly normalized/saved. */
private fun buildCanonicalManualSequenceDiagram(
    spec: SeqDiagramSpec,
    entries: List<LogEntry>,
    participants: List<DiagramParticipant>,
    coverage: DiagramCoverage,
    warnings: MutableList<String>,
): SeqDiagram {
    val canonical = canonicalizeManualMessages(spec.manualDocument)
    canonical.diagnostics.filter { it.isError }.forEach { warnings += it.message }
    val interactions = spec.manualDocument.interactions.associateBy { it.id }
    val entryById = entries.associateBy { it.id }
    val events = canonical.messages.flatMap { message ->
        if (message.definition.visibility == ManualMessageVisibility.HIDDEN) return@flatMap emptyList()
        message.occurrences.mapNotNull { occurrence ->
            val interaction = interactions[occurrence.interactionId] ?: return@mapNotNull null
            if (!interaction.enabled) return@mapNotNull null
            val durableEntryIds = occurrence.evidence.map { it.entryId }.toSet() + interaction.sourceEntryIds
            val entry = durableEntryIds.asSequence().mapNotNull(entryById::get).firstOrNull()
            val evidence = occurrence.evidence.firstOrNull()
            val timestamp = entry?.ts ?: evidence?.timestamp ?: interaction.renderAnchorTs
            val level = entry?.level ?: evidence?.level ?: interaction.renderAnchorLevel
            if (timestamp.isNullOrBlank() || level == null) {
                warnings += "Message '${message.definition.id}' has no renderable evidence anchor."
                return@mapNotNull null
            }
            CanonicalManualRenderItem(
                message = message,
                interaction = interaction,
                occurrence = occurrence,
                representedEntryIds = durableEntryIds,
                entryId = entry?.id ?: durableEntryIds.minOrNull() ?: 0,
                ts = timestamp,
                level = level,
            )
        }
    }.sortedWith(compareBy<CanonicalManualRenderItem> {
        it.occurrence.derivedOrder.timestampMillis ?: Long.MAX_VALUE
    }.thenBy {
        it.message.definition.orderOverride?.tieRank ?: Int.MAX_VALUE
    }.thenBy { it.occurrence.derivedOrder.sourceOrdinal }.thenBy { it.message.definition.id })

    val activeParticipantIds = events.flatMap { event ->
        listOfNotNull(event.message.definition.fromParticipantId, event.message.definition.toParticipantId)
    }.toSet()
    val orderedParticipants = orderParticipants(participants.filter { it.id in activeParticipantIds }, spec.lifelineOrder)
    val indexById = orderedParticipants.mapIndexed { index, participant -> participant.id to index }.toMap()
    val indexByInteractionId = linkedMapOf<String, Int>()
    val messages = mutableListOf<DiagramMessage>()
    var cursor = 0
    while (cursor < events.size) {
        val head = events[cursor]
        var end = cursor + 1
        while (end < events.size && events[end].message.definition.id == head.message.definition.id) end++
        val run = events.subList(cursor, end)
        val policy = head.message.definition.repeatPolicy
        val visibleRuns = when (policy.mode) {
            ManualMessageRepeatMode.EVERY_OCCURRENCE -> run.map { listOf(it) }
            ManualMessageRepeatMode.FIRST_AND_LAST -> if (run.size <= 2) run.map { listOf(it) }
            else listOf(listOf(run.first()), listOf(run.last()))
            ManualMessageRepeatMode.COLLAPSE_CONSECUTIVE -> if (run.size >= policy.collapseThreshold) {
                listOf(run)
            } else {
                run.map { listOf(it) }
            }
        }
        visibleRuns.forEach { visible ->
            val definition = head.message.definition
            val from = indexById[definition.fromParticipantId]
            val targetless = definition.toParticipantId == null
            val to = definition.toParticipantId?.let(indexById::get)
            if (from == null || (!targetless && to == null)) {
                warnings += "Message '${definition.id}' references an unavailable lifeline."
                return@forEach
            }
            val renderTo = to ?: from
            val kind = when {
                targetless -> MessageKind.CALL
                from == renderTo && definition.kind != MessageKind.RETURN -> MessageKind.SELF
                else -> definition.kind
            }
            val messageIndex = messages.size
            visible.forEach { item -> indexByInteractionId[item.interaction.id] = messageIndex }
            messages += DiagramMessage(
                fromIdx = from,
                toIdx = renderTo,
                label = truncateManualLabel(definition.labelTemplate, spec.options.labelMaxChars),
                entryId = visible.first().entryId,
                ts = visible.first().ts,
                level = visible.first().level,
                kind = kind,
                repeatCount = visible.size,
                evidence = MessageEvidence.MANUAL_OVERRIDE,
                primary = true,
                representedEntryIds = visible.flatMapTo(linkedSetOf()) { it.representedEntryIds },
                originKeys = visible.mapTo(linkedSetOf()) {
                    MessageOriginKey(it.entryId, manualInteractionId = it.interaction.id)
                },
                targetless = targetless,
                manualGroupKey = definition.id,
            )
        }
        cursor = end
    }
    return SeqDiagram(
        spec = spec,
        participants = orderedParticipants,
        messages = messages,
        frames = manualFrames(spec, indexByInteractionId, warnings),
        notes = manualNotes(spec, indexById, indexByInteractionId, warnings),
        activationSpans = manualActivations(spec, indexById, indexByInteractionId, warnings),
        truncated = false,
        scannedEntries = entries.size,
        coverage = coverage,
        warnings = warnings,
        traceMode = SourceTraceMode.DISABLED,
    )
}

// The helpers below carry buildManualSequenceDiagram's own resolution/message/frame/note/
// activation phases — pulled out purely to keep the caller's own complexity down; each does
// exactly what its inline block used to.

// A manual document is the authoritative user-authored model. In particular, its initial
// draft must retain every selected log event; the inferred builder's structural-arrow cap
// must not silently delete manual rows.
private fun resolveManualRenderItems(
    candidateInteractions: List<IndexedValue<ManualDiagramInteraction>>,
    entries: List<LogEntry>,
    entryById: Map<Int, LogEntry>,
    warnings: MutableList<String>,
): List<ManualRenderItem> = candidateInteractions.mapNotNull { indexed ->
    val interaction = indexed.value
    if (interaction.id.isBlank()) {
        warnings += "Interaction with an empty id was ignored."
        return@mapNotNull null
    }
    val durableEntryIds = interaction.sourceEntryIds + interaction.evidence.map { it.entryId }
    val representedInRange = durableEntryIds.filterTo(linkedSetOf()) { it in entryById }
    if (representedInRange.size != durableEntryIds.size && representedInRange.isNotEmpty()) {
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
    val retainedEvidence = interaction.evidence.minByOrNull { it.entryId }
    val anchorTs = retainedEvidence?.timestamp ?: interaction.renderAnchorTs
    val anchorLevel = retainedEvidence?.level ?: interaction.renderAnchorLevel
    if (anchorTs.isNullOrBlank() || anchorLevel == null) {
        warnings += "Interaction '${interaction.id}' has no selected log evidence."
        return@mapNotNull null
    }
    // A durable manual interaction is authoritative. Its persisted anchor keeps it renderable
    // after the current range/filter no longer contains its original log rows.
    ManualRenderItem(
        interaction = interaction,
        representedEntryIds = durableEntryIds,
        entryId = durableEntryIds.minOrNull() ?: 0,
        ts = anchorTs,
        level = anchorLevel,
    )
}

// Groups remain logical editing groups.  Rendering must retain evidence order: only an adjacent
// run of equivalent group members may collapse, so an interleaved log occurrence can never vanish
// behind a misleading ×N arrow.
private fun buildManualMessages(
    resolvedInteractions: List<ManualRenderItem>,
    indexById: Map<String, Int>,
    spec: SeqDiagramSpec,
    warnings: MutableList<String>,
): Pair<List<DiagramMessage>, Map<String, Int>> {
    val seenInteractionIds = mutableSetOf<String>()
    // resolveManualRenderItems receives interactions already stably ordered by durable `order`
    // and their document index. Do not add an id tie-break here: equal-order evidence must retain
    // that original order or an interleaved event could become falsely adjacent and collapse.
    val ordered = resolvedInteractions
    val runs = mutableListOf<MutableList<ManualRenderItem>>()
    ordered.forEach { item ->
        val interaction = item.interaction
        if (!seenInteractionIds.add(interaction.id)) {
            warnings += "Duplicate interaction '${interaction.id}' was ignored."
            return@forEach
        }
        val previous = runs.lastOrNull()?.lastOrNull()
        if (previous != null && canCollapseManualOccurrences(previous.interaction, interaction)) {
            runs.last().add(item)
        } else {
            runs += mutableListOf(item)
        }
    }
    val messages = mutableListOf<DiagramMessage>()
    val indexByInteractionId = linkedMapOf<String, Int>()
    fun emit(rendered: List<ManualRenderItem>, repeatCount: Int) {
        val head = rendered.first()
        val interaction = head.interaction
        val from = indexById[interaction.fromParticipantId]
        val targetless = interaction.toParticipantId == null
        val requestedTo = interaction.toParticipantId?.let(indexById::get)
        val source = from ?: run {
            warnings += "Interaction '${interaction.id}' references an unavailable lifeline."
            return
        }
        if (!targetless && requestedTo == null) {
            warnings += "Interaction '${interaction.id}' references an unavailable lifeline."
            return
        }
        val to = requestedTo ?: source
        val kind = when {
            targetless -> MessageKind.CALL
            source == to && interaction.kind != MessageKind.RETURN -> MessageKind.SELF
            else -> interaction.kind
        }
        val origins = rendered.mapTo(linkedSetOf()) { member ->
            MessageOriginKey(entryId = member.entryId, manualInteractionId = member.interaction.id)
        }
        val representedEntryIds = rendered.flatMapTo(linkedSetOf()) { it.representedEntryIds }
        val messageIndex = messages.size
        rendered.forEach { member -> indexByInteractionId[member.interaction.id] = messageIndex }
        messages += DiagramMessage(
            fromIdx = source,
            toIdx = to,
            label = manualDisplayLabel(rendered.map { it.interaction }, spec.options.labelMaxChars),
            entryId = head.entryId,
            ts = head.ts,
            level = head.level,
            kind = kind,
            repeatCount = repeatCount,
            evidence = MessageEvidence.MANUAL_OVERRIDE,
            primary = true,
            representedEntryIds = representedEntryIds,
            originKeys = origins,
            targetless = targetless,
            manualGroupKey = manualMessageBucketId(interaction),
        )
    }
    runs.forEach { run ->
        when (spec.manualDocument.repeatPresentation) {
            ManualDiagramRepeatPresentation.EVERY_OCCURRENCE -> run.forEach { emit(listOf(it), 1) }
            ManualDiagramRepeatPresentation.CONSECUTIVE -> emit(run, run.size)
            ManualDiagramRepeatPresentation.FIRST_AND_LAST -> when (run.size) {
                1 -> emit(run, 1)
                2 -> run.forEach { emit(listOf(it), 1) }
                else -> {
                    emit(listOf(run.first()), 1)
                    val lastIndex = messages.lastIndex
                    emit(listOf(run.last()), 1)
                    // Frames/notes which refer to a collapsed middle occurrence remain bounded
                    // by the first visible member instead of becoming orphaned.
                    run.drop(1).dropLast(1).forEach { indexByInteractionId[it.interaction.id] = lastIndex }
                }
            }
        }
    }
    return messages to indexByInteractionId
}

private fun canCollapseManualOccurrences(
    previous: ManualDiagramInteraction,
    next: ManualDiagramInteraction,
): Boolean = manualMessageBucketId(previous) == manualMessageBucketId(next) &&
    manualMergeCompatibility(listOf(previous, next)).compatible

private fun manualDisplayLabel(interactions: List<ManualDiagramInteraction>, maxChars: Int): String {
    val representative = interactions.first()
    val visibility = when (representative.visibility) {
        ManualOperationVisibility.PUBLIC -> "+"
        ManualOperationVisibility.PROTECTED -> "#"
        ManualOperationVisibility.PACKAGE -> "~"
        ManualOperationVisibility.PRIVATE -> "-"
        ManualOperationVisibility.UNSPECIFIED -> ""
    }
    val label = if (representative.label.isNullOrBlank()) {
        manualMessageDisplayTemplate(interactions)
    } else {
        representative.label.orEmpty()
    }
    val structuredDefault = representative.label.isNullOrBlank() && representative.parameters.isEmpty()
    val formatted = if (structuredDefault && !label.contains('(')) "$label()" else label
    return visibility + truncateManualLabel(formatted, maxChars)
}

private fun manualFrames(
    spec: SeqDiagramSpec,
    indexByInteractionId: Map<String, Int>,
    warnings: MutableList<String>,
): List<DiagramFrame> = spec.manualDocument.groups.filter { it.enabled }.mapNotNull { group ->
    val indices = group.interactionIds.mapNotNull(indexByInteractionId::get)
    if (indices.isEmpty()) {
        warnings += "Group '${group.id}' has no enabled interactions."
        null
    } else {
        DiagramFrame(manualFragmentLabel(group), null, indices.min(), indices.max(), depth = 0)
    }
}

/** CUSTOM keeps the group's own free-text label verbatim; a typed kind prefixes the conventional
 *  UML fragment keyword (mockup "Group ▾ ... loop, alt, opt, par") ahead of it. */
private fun manualFragmentLabel(group: ManualDiagramGroup): String {
    val prefix = when (group.kind) {
        ManualFragmentKind.LOOP -> "loop "
        ManualFragmentKind.ALT -> "alt "
        ManualFragmentKind.OPT -> "opt "
        ManualFragmentKind.PAR -> "par "
        ManualFragmentKind.CUSTOM -> return group.label
    }
    return prefix + group.label
}

private fun manualNotes(
    spec: SeqDiagramSpec,
    indexById: Map<String, Int>,
    indexByInteractionId: Map<String, Int>,
    warnings: MutableList<String>,
): List<DiagramNoteMark> = spec.manualDocument.notes.filter { it.enabled }.mapNotNull { note ->
    val participant = indexById[note.participantId]
    val after = indexByInteractionId[note.afterInteractionId]
    if (participant == null || after == null) {
        warnings += "Note '${note.id}' has no available anchor."
        null
    } else {
        DiagramNoteMark(participant, after, note.text, note.isError)
    }
}

private fun manualActivations(
    spec: SeqDiagramSpec,
    indexById: Map<String, Int>,
    indexByInteractionId: Map<String, Int>,
    warnings: MutableList<String>,
): List<DiagramActivationSpan> {
    if (spec.options.activationPolicy == ActivationPolicy.NONE) return emptyList()
    return spec.manualDocument.activations.filter { it.enabled }.mapNotNull { activation ->
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

private data class CanonicalManualRenderItem(
    val message: CanonicalManualMessage,
    val interaction: ManualDiagramInteraction,
    val occurrence: ManualMessageOccurrence,
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
