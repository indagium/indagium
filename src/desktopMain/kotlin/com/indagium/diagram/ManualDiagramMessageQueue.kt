@file:Suppress("CyclomaticComplexMethod", "MaxLineLength")

package com.indagium.diagram

import com.indagium.model.LogEntry

/**
 * UI-free state and operations for the manual message queue.
 *
 * The queue groups only by the durable [ManualDiagramInteraction.groupKey]. An interaction without
 * a group key remains an individual row, so the queue never collapses two unrelated pieces of log
 * evidence merely because their labels happen to match.
 */
enum class ManualMessageFilter {
    ALL,
    NEEDS_TARGET,
    EDITED,
    HIDDEN,
}

enum class ManualMessageSort {
    LOG_ORDER,
    LIFELINE,
    OCCURRENCES,
    STATE,
}

enum class ManualMessageState {
    NEEDS_TARGET,
    EDITED,
    AUTO,
    HIDDEN,
}

data class ManualMessageQueueRow(
    val id: String,
    val groupKey: String?,
    val interactions: List<ManualDiagramInteraction>,
    val label: String,
    val occurrenceCount: Int,
    val sourceEntryIds: Set<Int>,
    val state: ManualMessageState,
    val hidden: Boolean,
    val firstOrder: Long,
    /** Non-null for a version-5 canonical row; legacy rows retain a null compatibility value. */
    val message: ManualDiagramMessageDefinition? = null,
) {
    val interactionIds: List<String>
        get() = interactions.map { it.id }

    val representative: ManualDiagramInteraction
        get() = interactions.first()

    val fromParticipantId: String
        get() = message?.fromParticipantId ?: representative.fromParticipantId

    val toParticipantId: String?
        get() = message?.toParticipantId ?: representative.toParticipantId
}

data class ManualMessageQueue(
    val rows: List<ManualMessageQueueRow>,
) {
    val needsTargetCount: Int
        get() = rows.count { row -> row.interactions.any { it.toParticipantId == null } }

    val editedCount: Int
        get() = rows.count { row -> row.interactions.any { it.authoring == ManualInteractionAuthoring.EDITED } }
}

/**
 * Resolves the ids carried by the queue selection to occurrence ids. Version 5 selections contain
 * durable message ids; legacy selections contain the compatibility bucket ids emitted by
 * [manualMessageBucketId]. Keeping this adapter here prevents UI code from knowing which document
 * version it is editing.
 */
fun manualInteractionIdsForSelectedMessages(
    document: ManualDiagramDocument,
    selectedMessageIds: Set<String>,
): Set<String> = if (document.messages.isNotEmpty()) {
    document.messages
        .filter { it.id in selectedMessageIds }
        .flatMapTo(linkedSetOf(), ManualDiagramMessageDefinition::occurrenceIds)
} else {
    document.interactions
        .filter { it.id in selectedMessageIds || manualMessageBucketId(it) in selectedMessageIds }
        .mapTo(linkedSetOf(), ManualDiagramInteraction::id)
}

/** Applies a guided target choice to one occurrence or to the complete durable message. When a
 * repeated message is only partially resolved, the selected occurrences are split into a new
 * first-class definition so the unresolved remainder remains editable and visible. */
fun setManualMessageTargetForOccurrences(
    document: ManualDiagramDocument,
    messageId: String,
    occurrenceIds: Set<String>,
    targetParticipantId: String,
    kind: MessageKind,
): ManualDiagramDocument {
    val selectedIds = occurrenceIds.intersect(document.interactions.map { it.id }.toSet())
    if (selectedIds.isEmpty()) return document
    val interactions = document.interactions.map { interaction ->
        if (interaction.id in selectedIds) interaction.copy(
            toParticipantId = targetParticipantId,
            kind = kind,
            authoring = ManualInteractionAuthoring.EDITED,
        ) else interaction
    }
    if (document.messages.isEmpty()) return document.copy(interactions = interactions)
    val definition = document.messages.firstOrNull { it.id == messageId } ?: return document.copy(interactions = interactions)
    val selected = definition.occurrenceIds.filter { it in selectedIds }
    if (selected.isEmpty()) return document.copy(interactions = interactions)
    val remaining = definition.occurrenceIds.filterNot { it in selectedIds }
    val resolved = definition.copy(
        id = if (remaining.isEmpty()) definition.id else "${definition.id}:resolved:${selected.first()}",
        occurrenceIds = selected,
        toParticipantId = targetParticipantId,
        kind = kind,
        state = ManualMessageStateKind.EDITED,
        authoring = ManualInteractionAuthoring.EDITED,
    )
    val replacements = if (remaining.isEmpty()) listOf(resolved) else listOf(definition.copy(occurrenceIds = remaining), resolved)
    return document.copy(
        interactions = interactions,
        messages = document.messages.flatMap { current -> if (current.id == definition.id) replacements else listOf(current) },
    )
}

/**
 * Pure queue selection semantics. A plain click selects one message, Cmd/Ctrl toggles one, and
 * Shift selects the visible range from the stable anchor. The caller owns the anchor so filtering
 * and sorting can be changed without changing durable document order.
 */
fun selectManualQueueMessageIds(
    visibleMessageIds: List<String>,
    selectedMessageIds: Set<String>,
    anchorMessageId: String?,
    clickedMessageId: String,
    additive: Boolean = false,
    range: Boolean = false,
): Pair<Set<String>, String?> {
    if (clickedMessageId !in visibleMessageIds) return selectedMessageIds to anchorMessageId
    val next = when {
        range && anchorMessageId in visibleMessageIds -> {
            val start = visibleMessageIds.indexOf(anchorMessageId)
            val end = visibleMessageIds.indexOf(clickedMessageId)
            val rangeIds = visibleMessageIds.subList(minOf(start, end), maxOf(start, end) + 1).toSet()
            if (additive) selectedMessageIds + rangeIds else rangeIds
        }
        additive -> if (clickedMessageId in selectedMessageIds) selectedMessageIds - clickedMessageId
        else selectedMessageIds + clickedMessageId
        else -> setOf(clickedMessageId)
    }
    return next to if (range && anchorMessageId in visibleMessageIds) anchorMessageId else clickedMessageId
}

fun manualMessageTemplate(interaction: ManualDiagramInteraction): String {
    interaction.label?.trim()?.takeUnless { it.isNullOrEmpty() }?.let { return it }
    val operation = interaction.operation.trim()
    val parameters = interaction.parameters
        .map { parameter ->
            parameter.name.trim().takeUnless { it.isEmpty() }?.let { name ->
                name + "=" + parameter.value
            } ?: parameter.value
        }
        .filter { it.isNotBlank() }
        .joinToString(", ")
    val call = buildString {
        append(operation)
        if (parameters.isNotEmpty()) append("(").append(parameters).append(")")
    }.trim()
    return interaction.result?.trim()?.takeUnless { it.isEmpty() }?.let { result ->
        if (call.isEmpty()) result else call + " → " + result
    } ?: call.ifEmpty { "(untitled message)" }
}

/**
 * Renders [row]'s label as a token template: a parameter name whose value is identical across
 * every occurrence in the row stays a literal value, while a name whose value varies is shown as
 * `{name}` instead — e.g. a ×12 group whose only difference is a device id renders as
 * `push(deviceKey={deviceKey})` rather than one arbitrary occurrence's literal value.
 *
 * Falls back to [row]'s own [ManualMessageQueueRow.label] when there is only one occurrence, when
 * the representative interaction carries an explicit literal [ManualDiagramInteraction.label]
 * override (which is shown verbatim, never reparsed), or when no parameter name actually varies.
 *
 * Parameter names/values are read from each interaction's own [ManualDiagramInteraction.parameters]
 * when present, falling back to [extractManualParameters] over that occurrence's rendered
 * [manualMessageTemplate] text otherwise — the same fallback [ManualDiagramSeedService] itself uses
 * for a non-source-call interaction, so template detection works whether or not the interaction was
 * seeded with structured parameters.
 */
fun manualMessageDisplayTemplate(row: ManualMessageQueueRow): String {
    row.message?.let { return it.labelTemplate }
    return manualMessageDisplayTemplate(row.interactions)
}

/**
 * The canonical generalized label used by both the queue and the canvas.  Keeping this projection
 * independent of a queue row prevents an arbitrary representative occurrence from leaking into a
 * collapsed canvas arrow.
 */
fun manualMessageDisplayTemplate(interactions: List<ManualDiagramInteraction>): String {
    if (interactions.isEmpty()) return "(untitled message)"
    val ordered = interactions.sortedBy { it.order }
    val rowLabel = manualMessageTemplate(ordered.first())
    if (ordered.size <= 1) return rowLabel
    val representative = ordered.first()
    if (!representative.label.isNullOrBlank()) return rowLabel

    fun paramsOf(interaction: ManualDiagramInteraction): List<DiagramParameter> =
        interaction.parameters.ifEmpty { extractManualParameters(manualMessageTemplate(interaction)) }

    val perOccurrenceParams = ordered.map(::paramsOf)
    val namesInAll = perOccurrenceParams
        .map { params -> params.mapNotNull { it.name.trim().takeUnless(String::isEmpty) }.toSet() }
        .reduceOrNull { a, b -> a intersect b }
        .orEmpty()
    val varyingNames = namesInAll.filterTo(linkedSetOf()) { name ->
        perOccurrenceParams.map { params -> params.first { it.name.trim() == name }.value }.toSet().size > 1
    }
    if (varyingNames.isEmpty()) return rowLabel

    val operation = representative.operation.trim()
        .ifEmpty { manualOperationLabel(manualMessageTemplate(representative)) }
    val templatedParameters = paramsOf(representative).joinToString(", ") { parameter ->
        val name = parameter.name.trim()
        val display = if (name.isNotEmpty() && name in varyingNames) "{$name}" else parameter.value
        if (name.isEmpty()) display else "$name=$display"
    }
    val call = buildString {
        append(operation)
        if (templatedParameters.isNotBlank()) append("(").append(templatedParameters).append(")")
    }.trim()
    return representative.result?.trim()?.takeUnless { it.isEmpty() }?.let { result ->
        if (call.isEmpty()) result else "$call → $result"
    } ?: call.ifEmpty { rowLabel }
}

fun manualMessageRepeatPresentation(
    definition: ManualDiagramMessageDefinition?,
    fallback: ManualDiagramRepeatPresentation,
): ManualDiagramRepeatPresentation = when (definition?.repeatPolicy?.mode) {
    ManualMessageRepeatMode.EVERY_OCCURRENCE -> ManualDiagramRepeatPresentation.EVERY_OCCURRENCE
    ManualMessageRepeatMode.FIRST_AND_LAST -> ManualDiagramRepeatPresentation.FIRST_AND_LAST
    ManualMessageRepeatMode.COLLAPSE_CONSECUTIVE -> ManualDiagramRepeatPresentation.CONSECUTIVE
    null -> fallback
}

fun manualMessageRepeatPolicy(presentation: ManualDiagramRepeatPresentation): ManualMessageRepeatPolicy =
    ManualMessageRepeatPolicy(
        mode = when (presentation) {
            ManualDiagramRepeatPresentation.CONSECUTIVE -> ManualMessageRepeatMode.COLLAPSE_CONSECUTIVE
            ManualDiagramRepeatPresentation.EVERY_OCCURRENCE -> ManualMessageRepeatMode.EVERY_OCCURRENCE
            ManualDiagramRepeatPresentation.FIRST_AND_LAST -> ManualMessageRepeatMode.FIRST_AND_LAST
        },
    )

/**
 * Reverses [ManualInteractionAuthoring.EDITED] back to [ManualInteractionAuthoring.AUTO], letting a
 * later regeneration treat the interaction as ordinary auto-seeded content again instead of
 * protecting it as an edit. Deliberately NOT routed through the bulk-action helpers' own
 * `editedManualInteraction` wrapper (every other mutation in this file marks EDITED) — this is the
 * one intentional EDITED → AUTO transition in the app, driven only by an explicit "Unlock" action.
 */
fun unlockManualInteraction(interaction: ManualDiagramInteraction): ManualDiagramInteraction =
    interaction.copy(authoring = ManualInteractionAuthoring.AUTO)

/**
 * Durable bucket identity for one manual interaction: every interaction sharing a non-blank
 * [ManualDiagramInteraction.groupKey] shares this id, and an ungrouped interaction gets its own.
 * Shared by [groupManualMessageQueueRows] (which computes [ManualMessageQueueRow.id] from it) and
 * [buildManualMessages] (which computes [DiagramMessage.manualGroupKey] from it), so canvas
 * arrow-click and row selection always agree on the same identity for the same group.
 */
fun manualMessageBucketId(interaction: ManualDiagramInteraction): String =
    interaction.groupKey?.trim()?.takeUnless { it.isEmpty() }
        ?.let { "group:" + it }
        ?: "individual:" + interaction.id

/** Union of legacy IDs and append-only retained evidence IDs, for source lookup and queue display. */
fun manualEvidenceEntryIds(interaction: ManualDiagramInteraction): Set<Int> =
    interaction.sourceEntryIds + interaction.evidence.map { it.entryId }

/** Result exposed to the editor before it offers a destructive group-wide merge. */
data class ManualMergeCompatibility(
    val compatible: Boolean,
    val reason: String? = null,
)

/**
 * A queue group is an editing convenience, never a license to treat unlike source evidence as one
 * arrow.  Values in structured parameters may vary (and are generalized with `{name}`), but all
 * endpoints, kind, editable shape, and source provenance must agree.
 */
fun manualMergeCompatibility(interactions: Collection<ManualDiagramInteraction>): ManualMergeCompatibility {
    val selected = interactions.toList()
    if (selected.size < 2) return ManualMergeCompatibility(false, "Select at least two messages to merge")
    val first = selected.first()
    if (selected.any { it.fromParticipantId != first.fromParticipantId || it.toParticipantId != first.toParticipantId }) {
        return ManualMergeCompatibility(false, "Merged messages must have identical From and To lifelines")
    }
    if (selected.any { it.kind != first.kind }) {
        return ManualMergeCompatibility(false, "Merged messages must have the same message kind")
    }
    if (selected.any { manualEditableLabelShape(it) != manualEditableLabelShape(first) }) {
        return ManualMergeCompatibility(false, "Merged messages must have the same editable label shape")
    }
    if (selected.any { manualSourceProvenance(it) != manualSourceProvenance(first) }) {
        return ManualMergeCompatibility(false, "Merged messages must have compatible source provenance")
    }
    return ManualMergeCompatibility(true)
}

private fun manualEditableLabelShape(interaction: ManualDiagramInteraction): String = listOf(
    interaction.label?.trim().orEmpty(),
    interaction.operation.trim(),
    interaction.parameters.joinToString("|") { it.name.trim() },
    interaction.result?.let(::normalizeManualMessage).orEmpty(),
    interaction.visibility.name,
).joinToString("\u0000")

private fun manualSourceProvenance(interaction: ManualDiagramInteraction): String = listOf(
    interaction.sourceMethodId.orEmpty(),
    interaction.sourceLogSiteId.orEmpty(),
    interaction.sourceOwnerType.orEmpty(),
).joinToString("\u0000")

fun groupManualMessageQueueRows(document: ManualDiagramDocument): List<ManualMessageQueueRow> {
    if (document.messages.isNotEmpty()) return canonicalQueueRows(document)
    val buckets = linkedMapOf<String, MutableList<ManualDiagramInteraction>>()
    // Preserve document position as the tie-break for equal evidence order.  A synthetic id
    // tie-break can make interleaved repeat members appear adjacent, which falsely changes both
    // the queue and canvas's evidence narrative.
    document.interactions.withIndex()
        .sortedWith(compareBy<IndexedValue<ManualDiagramInteraction>> { it.value.order }.thenBy { it.index })
        .forEach { indexed ->
            val interaction = indexed.value
            buckets.getOrPut(manualMessageBucketId(interaction)) { mutableListOf() }.add(interaction)
        }
    return buckets.map { (id, interactions) ->
        val ordered = interactions.sortedBy { it.order }
        val hidden = ordered.any { !it.enabled }
        val state = when {
            ordered.any { it.toParticipantId == null } -> ManualMessageState.NEEDS_TARGET
            ordered.any { it.authoring == ManualInteractionAuthoring.EDITED } -> ManualMessageState.EDITED
            hidden -> ManualMessageState.HIDDEN
            else -> ManualMessageState.AUTO
        }
        ManualMessageQueueRow(
            id = id,
            groupKey = ordered.first().groupKey?.trim()?.takeUnless { it.isEmpty() },
            interactions = ordered,
            label = manualMessageTemplate(ordered.first()),
            occurrenceCount = ordered.size,
            sourceEntryIds = ordered.flatMap { manualEvidenceEntryIds(it) }.toSet(),
            state = state,
            hidden = hidden,
            firstOrder = ordered.first().order,
        )
    }
}

private fun canonicalQueueRows(document: ManualDiagramDocument): List<ManualMessageQueueRow> {
    val interactionsById = document.interactions.associateBy { it.id }
    return canonicalManualMessages(document).mapNotNull { canonical ->
        val definition = canonical.definition
        val interactions = definition.occurrenceIds.mapNotNull(interactionsById::get)
        if (interactions.isEmpty()) return@mapNotNull null
        val state = when {
            definition.visibility == ManualMessageVisibility.HIDDEN -> ManualMessageState.HIDDEN
            definition.toParticipantId == null -> ManualMessageState.NEEDS_TARGET
            definition.state == ManualMessageStateKind.EDITED ||
                definition.authoring == ManualInteractionAuthoring.EDITED -> ManualMessageState.EDITED
            else -> ManualMessageState.AUTO
        }
        ManualMessageQueueRow(
            id = definition.id,
            groupKey = definition.id,
            interactions = interactions,
            label = definition.labelTemplate,
            occurrenceCount = definition.occurrenceIds.size,
            sourceEntryIds = canonical.occurrences.flatMapTo(linkedSetOf()) { occurrence ->
                occurrence.evidence.map { it.entryId }
            },
            state = state,
            hidden = definition.visibility == ManualMessageVisibility.HIDDEN,
            firstOrder = canonical.occurrences.minOf { it.derivedOrder.sourceOrdinal },
            message = definition,
        )
    }
}

fun buildManualMessageQueue(
    document: ManualDiagramDocument,
    filter: ManualMessageFilter = ManualMessageFilter.ALL,
    query: String = "",
    sort: ManualMessageSort = ManualMessageSort.LOG_ORDER,
): ManualMessageQueue {
    val normalizedQuery = query.trim().lowercase()
    val rows = groupManualMessageQueueRows(document)
        .asSequence()
        .filter { row ->
            when (filter) {
                ManualMessageFilter.ALL -> true
                ManualMessageFilter.NEEDS_TARGET -> row.interactions.any { it.toParticipantId == null }
                ManualMessageFilter.EDITED -> row.interactions.any {
                    it.authoring == ManualInteractionAuthoring.EDITED
                }
                ManualMessageFilter.HIDDEN -> row.hidden
            }
        }
        .filter { row ->
            normalizedQuery.isEmpty() ||
                row.label.lowercase().contains(normalizedQuery) ||
                row.interactions.any { interaction ->
                    interaction.fromParticipantId.lowercase().contains(normalizedQuery) ||
                        interaction.toParticipantId?.lowercase()?.contains(normalizedQuery) == true ||
                        manualEvidenceEntryIds(interaction).any { it.toString() == normalizedQuery }
                }
        }
        .sortedWith(
            when (sort) {
                ManualMessageSort.LOG_ORDER -> compareBy<ManualMessageQueueRow> { it.firstOrder }
                ManualMessageSort.LIFELINE -> compareBy<ManualMessageQueueRow> {
                    it.fromParticipantId
                }.thenBy { it.toParticipantId ?: "\uFFFF" }.thenBy { it.firstOrder }
                ManualMessageSort.OCCURRENCES -> compareByDescending<ManualMessageQueueRow> {
                    it.occurrenceCount
                }.thenBy { it.firstOrder }
                ManualMessageSort.STATE -> compareBy<ManualMessageQueueRow> {
                    when (it.state) {
                        ManualMessageState.NEEDS_TARGET -> 0
                        ManualMessageState.EDITED -> 1
                        ManualMessageState.HIDDEN -> 2
                        ManualMessageState.AUTO -> 3
                    }
                }.thenBy { it.firstOrder }
            },
        )
        .toList()
    return ManualMessageQueue(rows)
}

data class ManualTargetSuggestion(
    val participantId: String,
    val reason: String,
    val sourceEntryId: Int,
)

/**
 * Suggests only a declared TAG participant represented by a nearby log row on the same pid/tid.
 * No actor or new lifeline is inferred, and an absent/ambiguous source row yields no suggestion.
 */
fun suggestManualTarget(
    interaction: ManualDiagramInteraction,
    entries: List<LogEntry>,
    participants: List<DiagramParticipant>,
    maxDistance: Int = 8,
): ManualTargetSuggestion? {
    if (interaction.toParticipantId != null || entries.isEmpty() || maxDistance < 1) return null
    val sourceEntry = manualEvidenceEntryIds(interaction).asSequence()
        .mapNotNull { id -> entries.firstOrNull { entry -> entry.id == id } }
        .minByOrNull { it.id }
        ?: return null
    val sourceIndex = entries.indexOfFirst { it.id == sourceEntry.id }
    if (sourceIndex < 0 || sourceEntry.pid == 0 || sourceEntry.tid == 0) return null
    val participantByTag = participants
        .filter { it.kind == ParticipantKind.TAG }
        .flatMap { participant ->
            listOfNotNull(participant.tag, participant.id).map { tag -> tag to participant }
        }
        .groupBy({ it.first }, { it.second })
    val sourceParticipant = participants.firstOrNull { participant ->
        participant.id == interaction.fromParticipantId ||
            participant.tag == sourceEntry.tag
    }
    val candidates = (1..maxDistance).asSequence()
        .mapNotNull { distance -> (sourceIndex + distance).takeIf { it in entries.indices } }
        .map { index -> entries[index] }
        .filter { entry ->
            entry.pid != 0 &&
                entry.tid != 0 &&
            entry.pid == sourceEntry.pid &&
                entry.tid == sourceEntry.tid &&
                entry.tag != sourceEntry.tag
        }
        .mapNotNull { entry ->
            val participant = participantByTag[entry.tag].orEmpty()
                .distinctBy { it.id }
                .singleOrNull()
                ?: return@mapNotNull null
            if (participant.id == sourceParticipant?.id || participant.id == interaction.fromParticipantId) {
                return@mapNotNull null
            }
            entry to participant
        }
        .firstOrNull()
        ?: return null
    return ManualTargetSuggestion(
        participantId = candidates.second.id,
        reason = "Next mapped tag on the same PID/TID",
        sourceEntryId = candidates.first.id,
    )
}

sealed interface ManualMessageBulkAction {
    data class SetSource(val participantId: String) : ManualMessageBulkAction

    data class SetTarget(val participantId: String?) : ManualMessageBulkAction

    data object Hide : ManualMessageBulkAction

    data object Show : ManualMessageBulkAction

    data class Merge(val groupKey: String) : ManualMessageBulkAction

    data object Ungroup : ManualMessageBulkAction

    /** Alias used by the message editor; unlike arbitrary row deletion this restores the
     * occurrence-backed messages that were folded by Merge. */
    data object Unmerge : ManualMessageBulkAction

    data class GroupAsFragment(val group: ManualDiagramGroup) : ManualMessageBulkAction

    data class AddNote(val note: ManualDiagramNote) : ManualMessageBulkAction
}

data class ManualMessageBulkActionResult(
    val document: ManualDiagramDocument,
    val applied: Boolean,
    val reason: String? = null,
)

/**
 * Applies an explicit action only when every selected id and action payload is valid. Invalid
 * selections return the original document object unchanged, which keeps destructive bulk actions
 * safe for callers that want to offer a confirmation/undo step.
 */
fun applyManualMessageBulkAction(
    document: ManualDiagramDocument,
    selectedInteractionIds: Set<String>,
    action: ManualMessageBulkAction,
): ManualMessageBulkActionResult {
    if (document.messages.isNotEmpty()) {
        return applyCanonicalMessageBulkAction(document, selectedInteractionIds, action)
    }
    // Keep structural membership in durable evidence order, never Set iteration order. This makes
    // regenerated/encoded fragments deterministic and preserves the same order shown by the queue.
    val selected = document.interactions
        .filter { it.id in selectedInteractionIds || manualMessageBucketId(it) in selectedInteractionIds }
        .map { it.id }
    if (selected.isEmpty()) {
        return ManualMessageBulkActionResult(document, applied = false, reason = "Select at least one message")
    }
    val selectedSet = selected.toSet()
    val selectedInteractions = document.interactions.filter { it.id in selectedSet }
    if (selectedInteractions.size != selectedSet.size) {
        return ManualMessageBulkActionResult(document, applied = false, reason = "Selection contains an unknown message")
    }
    return when (action) {
        is ManualMessageBulkAction.SetSource -> applyManualSetSource(document, selectedSet, action)
        is ManualMessageBulkAction.SetTarget -> applyManualSetTarget(document, selectedSet, action)
        ManualMessageBulkAction.Hide ->
            updateSelectedManualInteractions(document, selectedSet) { editedManualInteraction(it) { value -> value.copy(enabled = false) } }
        ManualMessageBulkAction.Show ->
            updateSelectedManualInteractions(document, selectedSet) { editedManualInteraction(it) { value -> value.copy(enabled = true) } }
        is ManualMessageBulkAction.Merge -> applyManualMerge(document, selectedSet, action)
        ManualMessageBulkAction.Ungroup, ManualMessageBulkAction.Unmerge ->
            updateSelectedManualInteractions(document, selectedSet) { interaction -> editedManualInteraction(interaction) { it.copy(groupKey = null) } }
        is ManualMessageBulkAction.GroupAsFragment -> applyManualGroupAsFragment(document, selectedSet, selected, action)
        is ManualMessageBulkAction.AddNote -> applyManualAddNote(document, selectedSet, action)
    }
}

private fun applyCanonicalMessageBulkAction(
    document: ManualDiagramDocument,
    selectedMessageIds: Set<String>,
    action: ManualMessageBulkAction,
): ManualMessageBulkActionResult {
    val selected = document.messages.filter { definition ->
        definition.id in selectedMessageIds || definition.occurrenceIds.any { it in selectedMessageIds }
    }
    if (selected.isEmpty()) return ManualMessageBulkActionResult(document, false, "Select at least one message")
    val selectedIds = selected.map { it.id }.toSet()
    val occurrenceIds = selected.flatMapTo(linkedSetOf()) { it.occurrenceIds }
    val interactions = document.interactions
    fun updateDefinitions(transform: (ManualDiagramMessageDefinition) -> ManualDiagramMessageDefinition): ManualDiagramDocument {
        return document.copy(messages = document.messages.map { definition ->
            if (definition.id in selectedIds) transform(definition) else definition
        })
    }
    fun editDefinition(definition: ManualDiagramMessageDefinition, transform: (ManualDiagramMessageDefinition) -> ManualDiagramMessageDefinition) =
        transform(definition).copy(state = ManualMessageStateKind.EDITED, authoring = ManualInteractionAuthoring.EDITED)
    return when (action) {
        is ManualMessageBulkAction.SetSource -> {
            val source = action.participantId.trim()
            if (source.isEmpty()) return ManualMessageBulkActionResult(document, false, "Source lifeline is required")
            val updated = updateDefinitions { definition ->
                editDefinition(definition) { it.copy(
                    fromParticipantId = source,
                    kind = endpointKind(source, it.toParticipantId, it.kind),
                ) }
            }
            ManualMessageBulkActionResult(updated.copy(interactions = updateInteractionEndpoints(updated.interactions, occurrenceIds, source, null)), true)
        }
        is ManualMessageBulkAction.SetTarget -> {
            val target = action.participantId?.trim()?.takeUnless { it.isEmpty() }
            val updated = updateDefinitions { definition ->
                editDefinition(definition) { it.copy(
                    toParticipantId = target,
                    kind = endpointKind(it.fromParticipantId, target, it.kind),
                    state = if (target == null) ManualMessageStateKind.NEEDS_TARGET else ManualMessageStateKind.EDITED,
                ) }
            }
            ManualMessageBulkActionResult(updated.copy(interactions = updateInteractionEndpoints(updated.interactions, occurrenceIds, null, target, replaceTarget = true)), true)
        }
        ManualMessageBulkAction.Hide -> {
            val updated = updateDefinitions { definition ->
                editDefinition(definition) { it.copy(visibility = ManualMessageVisibility.HIDDEN, state = ManualMessageStateKind.HIDDEN) }
            }
            ManualMessageBulkActionResult(updated.copy(interactions = updateInteractionEnabled(updated.interactions, occurrenceIds, false)), true)
        }
        ManualMessageBulkAction.Show -> {
            val updated = updateDefinitions { definition ->
                editDefinition(definition) { it.copy(
                    visibility = ManualMessageVisibility.VISIBLE,
                    state = if (it.toParticipantId == null) ManualMessageStateKind.NEEDS_TARGET else ManualMessageStateKind.EDITED,
                ) }
            }
            ManualMessageBulkActionResult(updated.copy(interactions = updateInteractionEnabled(updated.interactions, occurrenceIds, true)), true)
        }
        is ManualMessageBulkAction.Merge -> mergeCanonicalMessages(document, selected, action.groupKey)
        ManualMessageBulkAction.Ungroup, ManualMessageBulkAction.Unmerge -> unmergeCanonicalMessages(document, selected)
        is ManualMessageBulkAction.GroupAsFragment -> {
            val group = action.group
            val expandedIds = selected.flatMap { it.occurrenceIds }
            if (group.id.isBlank() || group.label.isBlank()) {
                ManualMessageBulkActionResult(document, false, "Fragment id and label are required")
            } else if (document.groups.any { it.id == group.id }) {
                ManualMessageBulkActionResult(document, false, "Fragment id already exists")
            } else {
                ManualMessageBulkActionResult(
                    document.copy(groups = document.groups + group.copy(interactionIds = expandedIds)),
                    true,
                )
            }
        }
        is ManualMessageBulkAction.AddNote -> {
            val anchor = selected.last().occurrenceIds.lastOrNull()
            if (anchor == null || action.note.id.isBlank() || action.note.text.isBlank()) {
                ManualMessageBulkActionResult(document, false, "Note id, anchor, and text are required")
            } else if (document.notes.any { it.id == action.note.id }) {
                ManualMessageBulkActionResult(document, false, "Note id already exists")
            } else {
                ManualMessageBulkActionResult(document.copy(notes = document.notes + action.note.copy(afterInteractionId = anchor)), true)
            }
        }
    }
}

private fun updateInteractionEndpoints(
    interactions: List<ManualDiagramInteraction>,
    selectedIds: Set<String>,
    source: String?,
    target: String?,
    replaceTarget: Boolean = false,
): List<ManualDiagramInteraction> = interactions.map { interaction ->
    if (interaction.id !in selectedIds) interaction else interaction.copy(
        fromParticipantId = source ?: interaction.fromParticipantId,
        toParticipantId = if (replaceTarget) target else target ?: interaction.toParticipantId,
        authoring = ManualInteractionAuthoring.EDITED,
        kind = endpointKind(
            source ?: interaction.fromParticipantId,
            if (replaceTarget) target else target ?: interaction.toParticipantId,
            interaction.kind,
        ),
    )
}

private fun updateInteractionEnabled(
    interactions: List<ManualDiagramInteraction>,
    selectedIds: Set<String>,
    enabled: Boolean,
): List<ManualDiagramInteraction> = interactions.map { interaction ->
    if (interaction.id in selectedIds) interaction.copy(enabled = enabled, authoring = ManualInteractionAuthoring.EDITED)
    else interaction
}

private fun mergeCanonicalMessages(
    document: ManualDiagramDocument,
    selected: List<ManualDiagramMessageDefinition>,
    requestedId: String,
): ManualMessageBulkActionResult {
    val id = requestedId.trim().ifEmpty { return ManualMessageBulkActionResult(document, false, "Message id is required") }
    val first = selected.first()
    if (selected.any { it.fromParticipantId != first.fromParticipantId || it.toParticipantId != first.toParticipantId || it.kind != first.kind }) {
        return ManualMessageBulkActionResult(document, false, "Merged messages must have identical endpoints and kind")
    }
    val interactionsById = document.interactions.associateBy { it.id }
    val occurrenceIds = selected.flatMap { it.occurrenceIds }.distinct()
    val compilation = compileManualMessageMatch(occurrenceIds.mapNotNull { occurrenceId ->
        interactionsById[occurrenceId]?.let { ManualMessageMatchInput(occurrenceId, it.label ?: manualLabel(it)) }
    })
    val match = compilation.match ?: return ManualMessageBulkActionResult(document, false, compilation.error ?: "Merge pattern is invalid")
    val merged = first.copy(
        id = "manual-message:$id",
        occurrenceIds = occurrenceIds,
        match = match,
        labelTemplate = match.textPattern,
        state = ManualMessageStateKind.EDITED,
        authoring = ManualInteractionAuthoring.EDITED,
    )
    val selectedIds = selected.map { it.id }.toSet()
    val updatedInteractions = document.interactions.map { interaction ->
        if (interaction.id !in occurrenceIds) interaction else interaction.copy(
            groupKey = id,
            captureValues = compilation.captureValuesByOccurrence[interaction.id].orEmpty(),
            authoring = ManualInteractionAuthoring.EDITED,
        )
    }
    return ManualMessageBulkActionResult(
        document.copy(
            interactions = updatedInteractions,
            messages = document.messages.filterNot { it.id in selectedIds } + merged,
        ),
        true,
    )
}

private fun unmergeCanonicalMessages(
    document: ManualDiagramDocument,
    selected: List<ManualDiagramMessageDefinition>,
): ManualMessageBulkActionResult {
    val selectedIds = selected.map { it.id }.toSet()
    val interactionsById = document.interactions.associateBy { it.id }
    val replacements = selected.flatMap { definition ->
        if (definition.occurrenceIds.size <= 1) listOf(definition) else definition.occurrenceIds.mapIndexed { index, occurrenceId ->
            val interaction = interactionsById[occurrenceId]
            definition.copy(
                id = "${definition.id}:$index",
                occurrenceIds = listOf(occurrenceId),
                match = ManualMessageMatch(textPattern = interaction?.label ?: definition.match.textPattern),
                labelTemplate = interaction?.label ?: definition.labelTemplate,
                repeatPolicy = definition.repeatPolicy.copy(mode = ManualMessageRepeatMode.EVERY_OCCURRENCE),
            )
        }
    }
    return ManualMessageBulkActionResult(document.copy(messages = document.messages.filterNot { it.id in selectedIds } + replacements), true)
}

// The helpers below carry applyManualMessageBulkAction's own per-action-kind logic — pulled out
// purely to keep the caller's own complexity down; each does exactly what its inline `when`
// branch used to, over the same pre-validated (document, selectedSet) pair.
private fun endpointKind(from: String, to: String?, current: MessageKind): MessageKind = when {
    to == null -> MessageKind.CALL
    from == to && current != MessageKind.RETURN -> MessageKind.SELF
    from != to && current == MessageKind.SELF -> MessageKind.CALL
    else -> current
}

private fun editedManualInteraction(
    interaction: ManualDiagramInteraction,
    transform: (ManualDiagramInteraction) -> ManualDiagramInteraction,
): ManualDiagramInteraction = transform(interaction).copy(authoring = ManualInteractionAuthoring.EDITED)

private fun updateSelectedManualInteractions(
    document: ManualDiagramDocument,
    selectedSet: Set<String>,
    transform: (ManualDiagramInteraction) -> ManualDiagramInteraction,
): ManualMessageBulkActionResult = ManualMessageBulkActionResult(
    document = document.copy(interactions = document.interactions.map { interaction ->
        if (interaction.id in selectedSet) transform(interaction) else interaction
    }),
    applied = true,
)

private fun applyManualSetSource(
    document: ManualDiagramDocument,
    selectedSet: Set<String>,
    action: ManualMessageBulkAction.SetSource,
): ManualMessageBulkActionResult {
    val source = action.participantId.trim()
    if (source.isEmpty()) return ManualMessageBulkActionResult(document, false, "Source lifeline is required")
    return updateSelectedManualInteractions(document, selectedSet) { interaction ->
        editedManualInteraction(interaction) {
            it.copy(fromParticipantId = source, kind = endpointKind(source, it.toParticipantId, it.kind))
        }
    }
}

private fun applyManualSetTarget(
    document: ManualDiagramDocument,
    selectedSet: Set<String>,
    action: ManualMessageBulkAction.SetTarget,
): ManualMessageBulkActionResult {
    val target = action.participantId?.trim()?.takeUnless { it.isEmpty() }
    return updateSelectedManualInteractions(document, selectedSet) { interaction ->
        editedManualInteraction(interaction) {
            it.copy(toParticipantId = target, kind = endpointKind(it.fromParticipantId, target, it.kind))
        }
    }
}

private fun applyManualMerge(
    document: ManualDiagramDocument,
    selectedSet: Set<String>,
    action: ManualMessageBulkAction.Merge,
): ManualMessageBulkActionResult {
    val groupKey = action.groupKey.trim()
    if (groupKey.isEmpty()) return ManualMessageBulkActionResult(document, false, "Group name is required")
    manualMergeCompatibility(document.interactions.filter { it.id in selectedSet }).let { compatibility ->
        if (!compatibility.compatible) return ManualMessageBulkActionResult(document, false, compatibility.reason)
    }
    return updateSelectedManualInteractions(document, selectedSet) { interaction ->
        editedManualInteraction(interaction) { it.copy(groupKey = groupKey) }
    }
}

private fun applyManualGroupAsFragment(
    document: ManualDiagramDocument,
    selectedSet: Set<String>,
    selected: List<String>,
    action: ManualMessageBulkAction.GroupAsFragment,
): ManualMessageBulkActionResult {
    val group = action.group
    return when {
        group.id.trim().isEmpty() || group.label.trim().isEmpty() ->
            ManualMessageBulkActionResult(document, false, "Fragment id and label are required")
        group.interactionIds.toSet() != selectedSet ->
            ManualMessageBulkActionResult(document, false, "Fragment must contain exactly the selected messages")
        document.groups.any { it.id == group.id } ->
            ManualMessageBulkActionResult(document, false, "Fragment id already exists")
        else -> ManualMessageBulkActionResult(
            document.copy(
                interactions = document.interactions.map { interaction ->
                    if (interaction.id in selectedSet) editedManualInteraction(interaction) { it } else interaction
                },
                groups = document.groups + group.copy(
                    id = group.id.trim(),
                    label = group.label.trim(),
                    interactionIds = selected,
                ),
            ),
            applied = true,
        )
    }
}

private fun applyManualAddNote(
    document: ManualDiagramDocument,
    selectedSet: Set<String>,
    action: ManualMessageBulkAction.AddNote,
): ManualMessageBulkActionResult {
    val note = action.note
    return when {
        note.id.trim().isEmpty() || note.text.trim().isEmpty() || note.participantId.trim().isEmpty() ->
            ManualMessageBulkActionResult(document, false, "Note id, participant, and text are required")
        note.afterInteractionId !in selectedSet ->
            ManualMessageBulkActionResult(document, false, "Note anchor must be selected")
        document.notes.any { it.id == note.id } ->
            ManualMessageBulkActionResult(document, false, "Note id already exists")
        else -> ManualMessageBulkActionResult(
            document.copy(
                interactions = document.interactions.map { interaction ->
                    if (interaction.id == note.afterInteractionId) editedManualInteraction(interaction) { it } else interaction
                },
                notes = document.notes + note.copy(
                    id = note.id.trim(),
                    participantId = note.participantId.trim(),
                    text = note.text.trim(),
                ),
            ),
            applied = true,
        )
    }
}
