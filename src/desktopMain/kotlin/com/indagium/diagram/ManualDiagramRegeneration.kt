@file:Suppress("CyclomaticComplexMethod")

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

/**
 * A row's chosen resolution. [PENDING] is only ever the constructed default for [EDITED_KEPT]
 * rows, which are always kept regardless of decision and so need no meaningful choice. For every
 * other kind, [defaultManualRegenerationRowDecision] gives each row the decision that reproduces
 * today's fixed per-kind policy, so an unreviewed regeneration applies identically to before this
 * type existed: [ACCEPT] adds a [ManualRegenerationChangeKind.NEW] row and takes the candidate's
 * data for a [ManualRegenerationChangeKind.CHANGED_AUTO] row; [REJECT] is the default for
 * [ManualRegenerationChangeKind.NO_LONGER_IN_SOURCE] and drops that row, matching the old
 * unconditional removal.
 */
enum class ManualRegenerationRowDecision { PENDING, ACCEPT, REJECT }

private fun defaultManualRegenerationRowDecision(kind: ManualRegenerationChangeKind): ManualRegenerationRowDecision =
    when (kind) {
        ManualRegenerationChangeKind.NEW, ManualRegenerationChangeKind.CHANGED_AUTO -> ManualRegenerationRowDecision.ACCEPT
        ManualRegenerationChangeKind.NO_LONGER_IN_SOURCE -> ManualRegenerationRowDecision.REJECT
        ManualRegenerationChangeKind.EDITED_KEPT -> ManualRegenerationRowDecision.PENDING
    }

data class ManualRegenerationReviewRow(
    val kind: ManualRegenerationChangeKind,
    val existing: ManualDiagramInteraction? = null,
    val candidate: ManualDiagramInteraction? = null,
    val decision: ManualRegenerationRowDecision = defaultManualRegenerationRowDecision(kind),
    /** True when semantic matching was intentionally withheld because it was non-unique. */
    val matchAmbiguous: Boolean = false,
    val ambiguityReason: String? = null,
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

private data class ManualDurableIdentity(
    val entryIds: Set<Int>,
    val sourceMethodId: String?,
    val sourceLogSiteId: String?,
    val sourceOwnerType: String?,
)

private fun durableIdentity(interaction: ManualDiagramInteraction): ManualDurableIdentity? {
    val entryIds = interaction.sourceEntryIds + interaction.evidence.map { it.entryId }
    val hasProvenance = interaction.sourceMethodId != null || interaction.sourceLogSiteId != null || interaction.sourceOwnerType != null
    return if (entryIds.isEmpty() && !hasProvenance) null else ManualDurableIdentity(
        entryIds, interaction.sourceMethodId, interaction.sourceLogSiteId, interaction.sourceOwnerType,
    )
}

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
    val result = linkedMapOf<String, ManualDiagramInteraction>()
    val unmatchedExisting = existing.toMutableSet()
    val unmatchedCandidates = candidates.toMutableSet()
    // Evidence/provenance is authoritative but only if it identifies exactly one candidate on
    // each side. Duplicate durable identities are surfaced as separate review rows, never paired
    // arbitrarily by document order.
    existing.forEach { old ->
        val identity = durableIdentity(old) ?: return@forEach
        val matches = unmatchedCandidates.filter { durableIdentity(it) == identity }
        if (matches.size == 1 && existing.count { durableIdentity(it) == identity } == 1) {
            result[old.id] = matches.single()
            unmatchedExisting.remove(old)
            unmatchedCandidates.remove(matches.single())
        }
    }
    // Semantic fallback is deliberately available only to entirely evidence-free interactions and
    // only where it is a unique one-to-one match.
    unmatchedExisting.toList().forEach { old ->
        if (durableIdentity(old) != null) return@forEach
        val identity = fallbackIdentity(old)
        val oldMatches = unmatchedExisting.filter { durableIdentity(it) == null && fallbackIdentity(it) == identity }
        val candidateMatches = unmatchedCandidates.filter { durableIdentity(it) == null && fallbackIdentity(it) == identity }
        if (oldMatches.size == 1 && candidateMatches.size == 1) {
            result[old.id] = candidateMatches.single()
            unmatchedExisting.remove(old)
            unmatchedCandidates.remove(candidateMatches.single())
        }
    }
    return result
}

private fun ambiguousSemanticIdentities(
    existing: List<ManualDiagramInteraction>,
    candidates: List<ManualDiagramInteraction>,
    matches: Map<String, ManualDiagramInteraction>,
): Set<String> {
    val matchedExisting = matches.keys
    val matchedCandidates = matches.values.toSet()
    val unresolvedExisting = existing.filter { it.id !in matchedExisting && durableIdentity(it) == null }
    val unresolvedCandidates = candidates.filter { it !in matchedCandidates && durableIdentity(it) == null }
    return (unresolvedExisting + unresolvedCandidates)
        .groupBy(::fallbackIdentity)
        .filter { (_, occurrences) -> occurrences.size > 1 }
        .keys
}

fun reviewManualRegeneration(
    existing: ManualDiagramDocument,
    candidate: ManualDiagramDocument,
): ManualRegenerationReview {
    val matches = matchRegenerationInteractions(existing.interactions, candidate.interactions)
    val matchedCandidateIds = matches.values.map { it.id }.toSet()
    val ambiguousFallbacks = ambiguousSemanticIdentities(existing.interactions, candidate.interactions, matches)
    val rows = buildList {
        existing.interactions.forEach { old ->
            val next = matches[old.id]
            if (old.authoring == ManualInteractionAuthoring.EDITED) {
                add(ManualRegenerationReviewRow(ManualRegenerationChangeKind.EDITED_KEPT, old, next))
            } else if (next == null) {
                add(ManualRegenerationReviewRow(
                    ManualRegenerationChangeKind.NO_LONGER_IN_SOURCE, old,
                    matchAmbiguous = durableIdentity(old) == null && fallbackIdentity(old) in ambiguousFallbacks,
                    ambiguityReason = "Multiple evidence-free interactions have the same semantic identity",
                ))
            } else if (old.copy(id = next.id, authoring = ManualInteractionAuthoring.AUTO) != next.copy(authoring = ManualInteractionAuthoring.AUTO)) {
                add(ManualRegenerationReviewRow(ManualRegenerationChangeKind.CHANGED_AUTO, old, next))
            }
        }
        candidate.interactions
            .filterNot { it.id in matchedCandidateIds }
            .forEach { next ->
                add(ManualRegenerationReviewRow(
                    ManualRegenerationChangeKind.NEW, candidate = next,
                    matchAmbiguous = durableIdentity(next) == null && fallbackIdentity(next) in ambiguousFallbacks,
                    ambiguityReason = "Multiple evidence-free interactions have the same semantic identity",
                ))
            }
    }
    return ManualRegenerationReview(candidate, rows)
}

/** Returns a copy of [review] with the row at [rowIndex] set to [decision]. Out-of-range indices
 *  are a no-op, matching this file's other bulk helpers' safe-on-invalid-input contract. */
fun withRowDecision(
    review: ManualRegenerationReview,
    rowIndex: Int,
    decision: ManualRegenerationRowDecision,
): ManualRegenerationReview {
    if (rowIndex !in review.rows.indices) return review
    return review.copy(rows = review.rows.mapIndexed { index, row -> if (index == rowIndex) row.copy(decision = decision) else row })
}

/** Sets every non-[ManualRegenerationChangeKind.EDITED_KEPT] row to [ManualRegenerationRowDecision.ACCEPT].
 *  An edited row is always kept regardless of decision, so it is left untouched rather than given
 *  a decision that would never be consulted. */
fun acceptAllRegenerationRows(review: ManualRegenerationReview): ManualRegenerationReview = review.copy(
    rows = review.rows.map { row ->
        if (row.kind == ManualRegenerationChangeKind.EDITED_KEPT) row else row.copy(decision = ManualRegenerationRowDecision.ACCEPT)
    },
)

/** Sets every non-[ManualRegenerationChangeKind.EDITED_KEPT] row to [ManualRegenerationRowDecision.REJECT]. */
fun rejectAllRegenerationRows(review: ManualRegenerationReview): ManualRegenerationReview = review.copy(
    rows = review.rows.map { row ->
        if (row.kind == ManualRegenerationChangeKind.EDITED_KEPT) row else row.copy(decision = ManualRegenerationRowDecision.REJECT)
    },
)

/**
 * Applies each row's own [ManualRegenerationReviewRow.decision]. Edited interactions are retained
 * byte-for-byte, including their ids, endpoint choices, labels, visibility, and source evidence,
 * regardless of decision. When every row keeps the constructed default from
 * [defaultManualRegenerationRowDecision] this produces IDENTICAL output to the old fixed per-kind
 * policy: a [ManualRegenerationChangeKind.NEW] row defaults to [ManualRegenerationRowDecision.ACCEPT]
 * (added, as before), a [ManualRegenerationChangeKind.CHANGED_AUTO] row defaults to
 * [ManualRegenerationRowDecision.ACCEPT] (candidate's data replaces the old row's, as before), and a
 * [ManualRegenerationChangeKind.NO_LONGER_IN_SOURCE] row defaults to [ManualRegenerationRowDecision.REJECT]
 * (dropped, as before) — see [ManualRegenerationRowDecision]'s own doc.
 */
fun applyReviewedManualRegeneration(
    existing: ManualDiagramDocument,
    review: ManualRegenerationReview,
): ManualDiagramDocument {
    if (review.rows.isEmpty()) return existing
    val candidate = review.candidateDocument
    val rowByExistingId = review.rows.mapNotNull { row -> row.existing?.let { it.id to row } }.toMap()
    val newRowByCandidateId = review.rows
        .filter { it.kind == ManualRegenerationChangeKind.NEW }
        .mapNotNull { row -> row.candidate?.let { it.id to row } }
        .toMap()
    val interactions = existing.interactions.mapNotNull { old ->
        val row = rowByExistingId[old.id] ?: return@mapNotNull old
        when (row.kind) {
            ManualRegenerationChangeKind.EDITED_KEPT -> old
            ManualRegenerationChangeKind.NO_LONGER_IN_SOURCE ->
                if (row.decision == ManualRegenerationRowDecision.REJECT) null else old
            ManualRegenerationChangeKind.CHANGED_AUTO ->
                if (row.decision == ManualRegenerationRowDecision.REJECT) {
                    old
                } else {
                    row.candidate?.copy(id = old.id, authoring = ManualInteractionAuthoring.AUTO) ?: old
                }
            ManualRegenerationChangeKind.NEW -> old // unreachable: a NEW row never carries `existing`.
        }
    } + candidate.interactions.filter { next ->
        val row = newRowByCandidateId[next.id] ?: return@filter false
        row.decision != ManualRegenerationRowDecision.REJECT
    }
    val ordered = interactions.sortedBy { it.order }
    val validIds = ordered.map { it.id }.toSet()
    val candidateToFinalId = buildMap {
        review.rows.forEach { row ->
            val candidateId = row.candidate?.id ?: return@forEach
            val finalId = when {
                row.kind == ManualRegenerationChangeKind.NEW && row.decision != ManualRegenerationRowDecision.REJECT -> candidateId
                row.kind == ManualRegenerationChangeKind.CHANGED_AUTO && row.decision != ManualRegenerationRowDecision.REJECT -> row.existing?.id
                else -> null
            }
            if (finalId != null && finalId in validIds) put(candidateId, finalId)
        }
    }
    fun remapMessage(message: ManualDiagramMessageDefinition): ManualDiagramMessageDefinition? {
        val occurrenceIds = message.occurrenceIds.map { candidateToFinalId[it] ?: it }.distinct()
        return message.copy(occurrenceIds = occurrenceIds.filter { it in validIds })
            .takeIf { it.occurrenceIds.isNotEmpty() }
    }
    val normalizedExisting = normalizeManualDocument(existing)
    val normalizedCandidate = normalizeManualDocument(candidate)
    val protectedMessages = normalizedExisting.messages
        .filter { it.state == ManualMessageStateKind.EDITED || it.authoring == ManualInteractionAuthoring.EDITED }
        .mapNotNull(::remapMessage)
    val protectedOccurrenceIds = protectedMessages.flatMapTo(linkedSetOf()) { it.occurrenceIds }
    val candidateMessages = normalizedCandidate.messages
        .mapNotNull(::remapMessage)
        .filter { message -> message.occurrenceIds.none { it in protectedOccurrenceIds } }
    val messages = (protectedMessages + candidateMessages)
        .distinctBy { it.id }
        .ifEmpty { normalizeManualDocument(ManualDiagramDocument(interactions = ordered)).messages }
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
    return ManualDiagramDocument(
        interactions = ordered,
        groups = groups,
        notes = notes,
        activations = activations,
        repeatPresentation = existing.repeatPresentation,
        messages = messages,
        defaultRepeatPolicy = existing.defaultRepeatPolicy,
    )
}

/** Message-level regeneration row. Occurrence rows remain available above for v1-v4 callers. */
data class ManualMessageRegenerationReviewRow(
    val kind: ManualRegenerationChangeKind,
    val existing: ManualDiagramMessageDefinition? = null,
    val candidate: ManualDiagramMessageDefinition? = null,
    val decision: ManualRegenerationRowDecision = defaultManualRegenerationRowDecision(kind),
    val matchAmbiguous: Boolean = false,
    val ambiguityReason: String? = null,
)

data class ManualMessageRegenerationReview(
    val existingDocument: ManualDiagramDocument,
    val candidateDocument: ManualDiagramDocument,
    val rows: List<ManualMessageRegenerationReviewRow>,
) {
    val newCount: Int get() = rows.count { it.kind == ManualRegenerationChangeKind.NEW }
    val changedAutoCount: Int get() = rows.count { it.kind == ManualRegenerationChangeKind.CHANGED_AUTO }
    val noLongerInSourceCount: Int get() = rows.count { it.kind == ManualRegenerationChangeKind.NO_LONGER_IN_SOURCE }
    val editedKeptCount: Int get() = rows.count { it.kind == ManualRegenerationChangeKind.EDITED_KEPT }
}

private fun messageSemanticIdentity(message: ManualDiagramMessageDefinition): String = listOf(
    message.fromParticipantId,
    message.toParticipantId.orEmpty(),
    message.kind.name,
    message.match.textPattern,
    message.labelTemplate,
).joinToString("\u0000")

private fun matchManualMessageDefinitions(
    existing: List<ManualDiagramMessageDefinition>,
    candidates: List<ManualDiagramMessageDefinition>,
): Map<String, ManualDiagramMessageDefinition> {
    val result = linkedMapOf<String, ManualDiagramMessageDefinition>()
    val unmatchedExisting = existing.toMutableList()
    val unmatchedCandidates = candidates.toMutableList()
    existing.forEach { old ->
        val matches = unmatchedCandidates.filter { it.id == old.id }
        if (matches.size == 1) {
            result[old.id] = matches.single()
            unmatchedExisting.remove(old)
            unmatchedCandidates.remove(matches.single())
        }
    }
    unmatchedExisting.toList().forEach { old ->
        val matches = unmatchedCandidates.filter { candidate ->
            old.occurrenceIds.toSet().intersect(candidate.occurrenceIds.toSet()).isNotEmpty()
        }
        if (matches.size == 1 && unmatchedExisting.count {
                it.occurrenceIds.toSet().intersect(matches.single().occurrenceIds.toSet()).isNotEmpty()
            } == 1) {
            result[old.id] = matches.single()
            unmatchedExisting.remove(old)
            unmatchedCandidates.remove(matches.single())
        }
    }
    unmatchedExisting.toList().forEach { old ->
        val matches = unmatchedCandidates.filter { messageSemanticIdentity(it) == messageSemanticIdentity(old) }
        if (matches.size == 1 && unmatchedExisting.count { messageSemanticIdentity(it) == messageSemanticIdentity(old) } == 1) {
            result[old.id] = matches.single()
            unmatchedExisting.remove(old)
            unmatchedCandidates.remove(matches.single())
        }
    }
    return result
}

private fun ambiguousMessageSemanticIdentities(
    existing: List<ManualDiagramMessageDefinition>,
    candidates: List<ManualDiagramMessageDefinition>,
    matches: Map<String, ManualDiagramMessageDefinition>,
): Set<String> {
    val matchedExisting = matches.keys
    val matchedCandidates = matches.values.toSet()
    return (existing.filter { it.id !in matchedExisting } + candidates.filter { it !in matchedCandidates })
        .groupBy(::messageSemanticIdentity)
        .filterValues { it.size > 1 }
        .keys
}

/** Compares regenerated durable messages without treating occurrence-list identity as a queue row. */
fun reviewManualMessageRegeneration(
    existing: ManualDiagramDocument,
    candidate: ManualDiagramDocument,
): ManualMessageRegenerationReview {
    val oldDocument = normalizeManualDocument(existing)
    val candidateDocument = normalizeManualDocument(candidate)
    val matches = matchManualMessageDefinitions(oldDocument.messages, candidateDocument.messages)
    val matchedCandidateIds = matches.values.map { it.id }.toSet()
    val ambiguous = ambiguousMessageSemanticIdentities(oldDocument.messages, candidateDocument.messages, matches)
    val rows = buildList {
        oldDocument.messages.forEach { old ->
            val next = matches[old.id]
            when {
                old.authoring == ManualInteractionAuthoring.EDITED || old.state == ManualMessageStateKind.EDITED ->
                    add(ManualMessageRegenerationReviewRow(ManualRegenerationChangeKind.EDITED_KEPT, old, next))
                next == null -> add(
                    ManualMessageRegenerationReviewRow(
                        ManualRegenerationChangeKind.NO_LONGER_IN_SOURCE,
                        existing = old,
                        matchAmbiguous = messageSemanticIdentity(old) in ambiguous,
                        ambiguityReason = "Multiple messages have the same durable semantic identity",
                    ),
                )
                next.copy(id = old.id) != old ->
                    add(ManualMessageRegenerationReviewRow(ManualRegenerationChangeKind.CHANGED_AUTO, old, next))
            }
        }
        candidateDocument.messages.filterNot { it.id in matchedCandidateIds }.forEach { next ->
            add(
                ManualMessageRegenerationReviewRow(
                    ManualRegenerationChangeKind.NEW,
                    candidate = next,
                    matchAmbiguous = messageSemanticIdentity(next) in ambiguous,
                    ambiguityReason = "Multiple messages have the same durable semantic identity",
                ),
            )
        }
    }
    return ManualMessageRegenerationReview(oldDocument, candidateDocument, rows)
}

fun withMessageRegenerationRowDecision(
    review: ManualMessageRegenerationReview,
    rowIndex: Int,
    decision: ManualRegenerationRowDecision,
): ManualMessageRegenerationReview = if (rowIndex !in review.rows.indices) review else review.copy(
    rows = review.rows.mapIndexed { index, row ->
        if (index == rowIndex) row.copy(decision = decision) else row
    },
)

fun acceptAllMessageRegenerationRows(review: ManualMessageRegenerationReview): ManualMessageRegenerationReview = review.copy(
    rows = review.rows.map { row ->
        if (row.kind == ManualRegenerationChangeKind.EDITED_KEPT) row else row.copy(decision = ManualRegenerationRowDecision.ACCEPT)
    },
)

/** Applies the reviewed message transaction while retaining edited definitions and evidence. */
fun applyReviewedManualMessageRegeneration(review: ManualMessageRegenerationReview): ManualDiagramDocument {
    val existing = review.existingDocument
    val candidate = review.candidateDocument
    val finalDefinitions = buildList {
        review.rows.forEach { row ->
            when (row.kind) {
                ManualRegenerationChangeKind.EDITED_KEPT -> row.existing?.let(::add)
                ManualRegenerationChangeKind.NO_LONGER_IN_SOURCE ->
                    if (row.decision != ManualRegenerationRowDecision.REJECT) row.existing?.let(::add)
                ManualRegenerationChangeKind.CHANGED_AUTO ->
                    if (row.decision == ManualRegenerationRowDecision.REJECT) row.existing?.let(::add)
                    else row.candidate?.let { add(it.copy(id = row.existing?.id ?: it.id)) }
                ManualRegenerationChangeKind.NEW ->
                    if (row.decision != ManualRegenerationRowDecision.REJECT) row.candidate?.let(::add)
            }
        }
    }.distinctBy { it.id }
    val replacedExistingOccurrences = review.rows
        .filter { it.kind == ManualRegenerationChangeKind.CHANGED_AUTO && it.decision != ManualRegenerationRowDecision.REJECT }
        .flatMapTo(linkedSetOf()) { it.existing?.occurrenceIds.orEmpty() }
    val acceptedCandidateOccurrences = finalDefinitions.flatMapTo(linkedSetOf()) { it.occurrenceIds }
    val interactions = buildList {
        existing.interactions.forEach { interaction ->
            if (interaction.id !in replacedExistingOccurrences && interaction.id !in acceptedCandidateOccurrences) add(interaction)
        }
        candidate.interactions.filter { it.id in acceptedCandidateOccurrences }.forEach(::add)
    }.distinctBy { it.id }
    val validIds = interactions.map { it.id }.toSet()
    val messages = finalDefinitions.mapNotNull { definition ->
        definition.copy(occurrenceIds = definition.occurrenceIds.filter { it in validIds })
            .takeIf { it.occurrenceIds.isNotEmpty() }
    }
    return existing.copy(
        interactions = interactions,
        messages = messages,
        groups = (existing.groups + candidate.groups).distinctBy { it.id }
            .mapNotNull { it.copy(interactionIds = it.interactionIds.filter(validIds::contains)).takeIf { group -> group.interactionIds.isNotEmpty() } },
        notes = (existing.notes + candidate.notes).distinctBy { it.id }
            .filter { it.afterInteractionId in validIds },
        activations = (existing.activations + candidate.activations).distinctBy { it.id }
            .filter { it.startInteractionId in validIds && it.endInteractionId in validIds },
    )
}

/** Full immutable spec snapshots make regeneration undo restore every affected collection. */
data class ManualRegenerationSpecSnapshot(
    val before: SeqDiagramSpec,
    val candidate: SeqDiagramSpec,
)

data class ManualRegenerationSpecReview(
    val snapshot: ManualRegenerationSpecSnapshot,
    val messages: ManualMessageRegenerationReview,
)

fun reviewManualRegenerationSpec(
    existing: SeqDiagramSpec,
    candidate: SeqDiagramSpec,
): ManualRegenerationSpecReview = ManualRegenerationSpecReview(
    snapshot = ManualRegenerationSpecSnapshot(existing, candidate),
    messages = reviewManualMessageRegeneration(existing.manualDocument, candidate.manualDocument),
)

fun applyReviewedManualRegenerationSpec(review: ManualRegenerationSpecReview): SeqDiagramSpec =
    review.snapshot.candidate.copy(
        authoringMode = DiagramAuthoringMode.MANUAL,
        manualDocument = applyReviewedManualMessageRegeneration(review.messages),
    )

fun restoreManualRegenerationSpec(snapshot: ManualRegenerationSpecSnapshot): SeqDiagramSpec = snapshot.before
