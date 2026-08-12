package com.indagium.diagram

import com.indagium.model.LogEntry

/**
 * Workspace-local navigation state for the unresolved-target pass. The ids are stable queue row
 * ids, not list positions, so filtering, sorting, and preview rebuilds cannot retarget the pass.
 */
data class GuidedTargetPassState(
    val groupIds: List<String>,
    val currentIndex: Int = 0,
) {
    val currentGroupId: String?
        get() = groupIds.getOrNull(currentIndex)

    val completedCount: Int
        get() = currentIndex.coerceIn(0, groupIds.size)
}

fun beginGuidedTargetPass(document: ManualDiagramDocument): GuidedTargetPassState? {
    val unresolved = groupManualMessageQueueRows(document)
        .filter { row -> row.interactions.any { it.toParticipantId == null } }
        .map { it.id }
    return unresolved.takeIf { it.isNotEmpty() }?.let(::GuidedTargetPassState)
}

/**
 * Refreshes the unresolved snapshot after a user action and advances past the current group. A
 * skipped group remains in the refreshed list but is not revisited until the pass wraps or restarts.
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
    val nextId = afterCurrent.firstOrNull() ?: unresolved.first()
    return GuidedTargetPassState(unresolved, unresolved.indexOf(nextId))
}

fun guidedTargetRow(
    document: ManualDiagramDocument,
    state: GuidedTargetPassState,
): ManualMessageQueueRow? {
    val id = state.currentGroupId ?: return null
    return groupManualMessageQueueRows(document).firstOrNull { it.id == id }
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
