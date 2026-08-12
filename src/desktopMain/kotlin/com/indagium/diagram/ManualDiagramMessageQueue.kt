package com.indagium.diagram

import com.indagium.model.LogEntry
import kotlin.math.abs

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
) {
    val interactionIds: List<String>
        get() = interactions.map { it.id }

    val representative: ManualDiagramInteraction
        get() = interactions.first()

    val fromParticipantId: String
        get() = representative.fromParticipantId

    val toParticipantId: String?
        get() = representative.toParticipantId
}

data class ManualMessageQueue(
    val rows: List<ManualMessageQueueRow>,
) {
    val needsTargetCount: Int
        get() = rows.count { row -> row.interactions.any { it.toParticipantId == null } }

    val editedCount: Int
        get() = rows.count { row -> row.interactions.any { it.authoring == ManualInteractionAuthoring.EDITED } }
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

fun groupManualMessageQueueRows(document: ManualDiagramDocument): List<ManualMessageQueueRow> {
    val buckets = linkedMapOf<String, MutableList<ManualDiagramInteraction>>()
    document.interactions
        .sortedWith(compareBy<ManualDiagramInteraction> { it.order }.thenBy { it.id })
        .forEach { interaction ->
            val bucketId = interaction.groupKey?.trim()?.takeUnless { it.isEmpty() }
                ?.let { "group:" + it }
                ?: "individual:" + interaction.id
            buckets.getOrPut(bucketId) { mutableListOf() }.add(interaction)
        }
    return buckets.map { (id, interactions) ->
        val ordered = interactions.sortedWith(compareBy<ManualDiagramInteraction> { it.order }.thenBy { it.id })
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
            sourceEntryIds = ordered.flatMap { it.sourceEntryIds }.toSet(),
            state = state,
            hidden = hidden,
            firstOrder = ordered.first().order,
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
                        interaction.sourceEntryIds.any { it.toString() == normalizedQuery }
                }
        }
        .sortedWith(
            when (sort) {
                ManualMessageSort.LOG_ORDER -> compareBy<ManualMessageQueueRow> { it.firstOrder }.thenBy { it.id }
                ManualMessageSort.LIFELINE -> compareBy<ManualMessageQueueRow> {
                    it.fromParticipantId
                }.thenBy { it.toParticipantId ?: "\uFFFF" }.thenBy { it.firstOrder }
                ManualMessageSort.OCCURRENCES -> compareByDescending<ManualMessageQueueRow> {
                    it.occurrenceCount
                }.thenBy { it.firstOrder }.thenBy { it.id }
                ManualMessageSort.STATE -> compareBy<ManualMessageQueueRow> {
                    when (it.state) {
                        ManualMessageState.NEEDS_TARGET -> 0
                        ManualMessageState.EDITED -> 1
                        ManualMessageState.HIDDEN -> 2
                        ManualMessageState.AUTO -> 3
                    }
                }.thenBy { it.firstOrder }.thenBy { it.id }
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
    val sourceEntry = interaction.sourceEntryIds.asSequence()
        .mapNotNull { id -> entries.firstOrNull { entry -> entry.id == id } }
        .minByOrNull { it.id }
        ?: return null
    val sourceIndex = entries.indexOfFirst { it.id == sourceEntry.id }
    if (sourceIndex < 0) return null
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
    val candidates = (1..maxDistance).flatMap { distance ->
        listOfNotNull(
            (sourceIndex + distance).takeIf { it in entries.indices }?.let { it to distance },
            (sourceIndex - distance).takeIf { it in entries.indices }?.let { it to -distance },
        )
    }.asSequence()
        .map { (index, distance) -> entries[index] to distance }
        .filter { (entry, _) ->
            entry.pid == sourceEntry.pid &&
                entry.tid == sourceEntry.tid &&
                entry.tag != sourceEntry.tag
        }
        .mapNotNull { (entry, distance) ->
            val participant = participantByTag[entry.tag].orEmpty()
                .distinctBy { it.id }
                .singleOrNull()
                ?: return@mapNotNull null
            if (participant.id == sourceParticipant?.id || participant.id == interaction.fromParticipantId) {
                return@mapNotNull null
            }
            Triple(entry, distance, participant)
        }
        .sortedWith(compareBy<Triple<LogEntry, Int, DiagramParticipant>> { abs(it.second) }.thenByDescending {
            it.second > 0
        })
        .firstOrNull()
        ?: return null
    return ManualTargetSuggestion(
        participantId = candidates.third.id,
        reason = "Nearby mapped tag on the same PID/TID",
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
    val selected = selectedInteractionIds.toList()
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
        ManualMessageBulkAction.Ungroup ->
            updateSelectedManualInteractions(document, selectedSet) { interaction -> editedManualInteraction(interaction) { it.copy(groupKey = null) } }
        is ManualMessageBulkAction.GroupAsFragment -> applyManualGroupAsFragment(document, selectedSet, selected, action)
        is ManualMessageBulkAction.AddNote -> applyManualAddNote(document, selectedSet, action)
    }
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
