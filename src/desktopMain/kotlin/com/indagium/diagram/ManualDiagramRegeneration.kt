package com.indagium.diagram

/**
 * Reviewable result of building a new auto-seeded manual document. The current document is never
 * mutated while this value is being calculated or displayed.
 */
enum class ManualRegenerationChangeKind {
    NEW,
    CHANGED_AUTO,
    NO_LONGER_IN_SOURCE,
    EDITED_KEPT,
}

data class ManualRegenerationReviewRow(
    val kind: ManualRegenerationChangeKind,
    val existing: ManualDiagramInteraction? = null,
    val candidate: ManualDiagramInteraction? = null,
)

data class ManualRegenerationReview(
    val candidateDocument: ManualDiagramDocument,
    val rows: List<ManualRegenerationReviewRow>,
    val candidateSpec: SeqDiagramSpec? = null,
) {
    val newCount: Int get() = rows.count { it.kind == ManualRegenerationChangeKind.NEW }
    val changedAutoCount: Int get() = rows.count { it.kind == ManualRegenerationChangeKind.CHANGED_AUTO }
    val noLongerInSourceCount: Int get() = rows.count { it.kind == ManualRegenerationChangeKind.NO_LONGER_IN_SOURCE }
    val editedKeptCount: Int get() = rows.count { it.kind == ManualRegenerationChangeKind.EDITED_KEPT }
}

private fun sameStableSource(a: ManualDiagramInteraction, b: ManualDiagramInteraction): Boolean =
    a.sourceEntryIds.isNotEmpty() &&
        a.sourceEntryIds == b.sourceEntryIds &&
        (a.sourceMethodId == null || b.sourceMethodId == null || a.sourceMethodId == b.sourceMethodId) &&
        (a.sourceLogSiteId == null || b.sourceLogSiteId == null || a.sourceLogSiteId == b.sourceLogSiteId)

private fun fallbackIdentity(interaction: ManualDiagramInteraction): String = listOf(
    interaction.fromParticipantId,
    interaction.toParticipantId.orEmpty(),
    interaction.kind.name,
    manualMessageTemplate(interaction).lowercase(),
    interaction.sourceMethodId.orEmpty(),
    interaction.sourceLogSiteId.orEmpty(),
).joinToString("|")

private fun matchRegenerationInteractions(
    existing: List<ManualDiagramInteraction>,
    candidates: List<ManualDiagramInteraction>,
): Map<String, ManualDiagramInteraction> {
    val unmatched = candidates.toMutableList()
    val result = linkedMapOf<String, ManualDiagramInteraction>()
    existing.forEach { old ->
        val match = unmatched.firstOrNull { sameStableSource(old, it) }
            ?: unmatched.firstOrNull { fallbackIdentity(old) == fallbackIdentity(it) }
        if (match != null) {
            result[old.id] = match
            unmatched.remove(match)
        }
    }
    return result
}

fun reviewManualRegeneration(
    existing: ManualDiagramDocument,
    candidate: ManualDiagramDocument,
): ManualRegenerationReview {
    val matches = matchRegenerationInteractions(existing.interactions, candidate.interactions)
    val matchedCandidateIds = matches.values.map { it.id }.toSet()
    val rows = buildList {
        existing.interactions.forEach { old ->
            val next = matches[old.id]
            if (old.authoring == ManualInteractionAuthoring.EDITED) {
                add(ManualRegenerationReviewRow(ManualRegenerationChangeKind.EDITED_KEPT, old, next))
            } else if (next == null) {
                add(ManualRegenerationReviewRow(ManualRegenerationChangeKind.NO_LONGER_IN_SOURCE, old))
            } else if (old.copy(id = next.id, authoring = ManualInteractionAuthoring.AUTO) != next.copy(authoring = ManualInteractionAuthoring.AUTO)) {
                add(ManualRegenerationReviewRow(ManualRegenerationChangeKind.CHANGED_AUTO, old, next))
            }
        }
        candidate.interactions
            .filterNot { it.id in matchedCandidateIds }
            .forEach { add(ManualRegenerationReviewRow(ManualRegenerationChangeKind.NEW, candidate = it)) }
    }
    return ManualRegenerationReview(candidate, rows)
}

/**
 * Applies only safe candidate changes. Edited interactions are retained byte-for-byte, including
 * their ids, endpoint choices, labels, visibility, and source evidence.
 */
fun applyReviewedManualRegeneration(
    existing: ManualDiagramDocument,
    review: ManualRegenerationReview,
): ManualDiagramDocument {
    val candidate = review.candidateDocument
    val matches = matchRegenerationInteractions(existing.interactions, candidate.interactions)
    val matchedCandidateIds = matches.values.map { it.id }.toSet()
    val interactions = existing.interactions.mapNotNull { old ->
        val next = matches[old.id]
        when {
            old.authoring == ManualInteractionAuthoring.EDITED -> old
            next == null -> null
            else -> next.copy(id = old.id, authoring = ManualInteractionAuthoring.AUTO)
        }
    } + candidate.interactions.filterNot { it.id in matchedCandidateIds }
    val ordered = interactions.sortedWith(compareBy<ManualDiagramInteraction> { it.order }.thenBy { it.id })
    val validIds = ordered.map { it.id }.toSet()
    val groups = (existing.groups + candidate.groups)
        .distinctBy { it.id }
        .mapNotNull { group ->
            group.copy(interactionIds = group.interactionIds.filter { it in validIds })
                .takeIf { it.interactionIds.isNotEmpty() }
        }
    val notes = (existing.notes + candidate.notes)
        .distinctBy { it.id }
        .filter { it.afterInteractionId in validIds }
    val activations = (existing.activations + candidate.activations)
        .distinctBy { it.id }
        .filter { it.startInteractionId in validIds && it.endInteractionId in validIds }
    return ManualDiagramDocument(ordered, groups, notes, activations)
}
