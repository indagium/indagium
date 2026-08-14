package com.indagium.diagram

import com.indagium.model.LogEntry

/**
 * Workspace-local navigation state for the unresolved-target pass. The ids are stable queue row
 * ids, not list positions, so filtering, sorting, and preview rebuilds cannot retarget the pass.
 */
data class GuidedTargetPassState(
    val groupIds: List<String>,
    val currentIndex: Int = 0,
    /** Snapshot used to distinguish partial resolution of a repeated row from an explicit skip. */
    val unresolvedOccurrenceIdsByGroup: Map<String, Set<String>> = emptyMap(),
) {
    val currentGroupId: String?
        get() = groupIds.getOrNull(currentIndex)

    val completedCount: Int
        get() = currentIndex.coerceIn(0, groupIds.size)
}

fun beginGuidedTargetPass(document: ManualDiagramDocument): GuidedTargetPassState? {
    val rows = groupManualMessageQueueRows(document)
        .filter { row -> row.interactions.any { it.toParticipantId == null } }
    val unresolved = rows.map { it.id }
    return unresolved.takeIf { it.isNotEmpty() }?.let {
        GuidedTargetPassState(it, unresolvedOccurrenceIdsByGroup = rows.associate { row ->
            row.id to row.interactions.filter { interaction -> interaction.toParticipantId == null }.map { it.id }.toSet()
        })
    }
}

/**
 * Refreshes the unresolved snapshot after a user action and advances past the current group. A
 * A skipped group remains in the refreshed list but is never revisited during this pass. In
 * particular, skipping the final unresolved group completes the pass instead of wrapping back to
 * the first row.
 */
fun advanceGuidedTargetPass(
    document: ManualDiagramDocument,
    state: GuidedTargetPassState,
): GuidedTargetPassState? {
    val unresolved = groupManualMessageQueueRows(document)
        .filter { row -> row.interactions.any { it.toParticipantId == null } }
        .map { it.id }
    if (unresolved.isEmpty()) return null
    val current = state.currentGroupId
    val afterCurrent = current?.let { id ->
        val currentIndex = unresolved.indexOf(id)
        if (currentIndex >= 0) unresolved.drop(currentIndex + 1) else unresolved
    }.orEmpty()
    val currentStillUnresolved = current != null && current in unresolved
    if (afterCurrent.isEmpty() && currentStillUnresolved) {
        val currentIds = groupManualMessageQueueRows(document)
            .firstOrNull { it.id == current }
            ?.interactions
            ?.filter { it.toParticipantId == null }
            ?.map { it.id }
            ?.toSet()
            .orEmpty()
        val previousIds = state.unresolvedOccurrenceIdsByGroup[current].orEmpty()
        // A smaller set means the user resolved one occurrence and must stay on the same
        // logical message. An unchanged set means Skip, so the pass completes at the end.
        if (previousIds.isNotEmpty() && currentIds.size < previousIds.size) {
            return GuidedTargetPassState(
                unresolved,
                unresolved.indexOf(current),
                state.unresolvedOccurrenceIdsByGroup + (current to currentIds),
            )
        }
        return null
    }
    val nextId = afterCurrent.firstOrNull() ?: unresolved.first()
    val currentRows = groupManualMessageQueueRows(document)
    return GuidedTargetPassState(
        unresolved,
        unresolved.indexOf(nextId),
        currentRows.associate { row ->
            row.id to row.interactions.filter { it.toParticipantId == null }.map { it.id }.toSet()
        },
    )
}

fun guidedTargetRow(
    document: ManualDiagramDocument,
    state: GuidedTargetPassState,
): ManualMessageQueueRow? {
    val id = state.currentGroupId ?: return null
    val row = groupManualMessageQueueRows(document).firstOrNull { it.id == id } ?: return null
    // A repeat group can be resolved one occurrence at a time when the user clears “Apply to
    // all”. Present only its still-unresolved members here; otherwise the representative stays
    // on the already fixed first member and the pass appears unable to advance.
    val unresolved = row.interactions.filter { it.toParticipantId == null }
    if (unresolved.isEmpty()) return null
    return row.copy(
        interactions = unresolved,
        occurrenceCount = unresolved.size,
        sourceEntryIds = unresolved.flatMapTo(linkedSetOf(), ::manualEvidenceEntryIds),
        firstOrder = unresolved.first().order,
    )
}

fun guidedTargetContext(
    row: ManualMessageQueueRow,
    entries: List<LogEntry>,
    radius: Int = 1,
): List<LogEntry> {
    val sourceId = row.sourceEntryIds.minOrNull() ?: return emptyList()
    val index = entries.indexOfFirst { it.id == sourceId }
    if (index < 0) return emptyList()
    return entries.subList(
        (index - radius).coerceAtLeast(0),
        (index + radius + 1).coerceAtMost(entries.size),
    )
}
