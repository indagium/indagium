package com.indagium.diagram3

// ── CREATE / DESTROY lifecycle conformance ───────────────────────────────────────────────────
//
// This is deliberately a read-only view of a document.  Mutation helpers remain small and local;
// Seq3Commands applies this validator once to the whole candidate document so a multi-row command
// is accepted or rejected atomically.  The validator follows what can actually be drawn: hidden
// messages/occurrences and messages whose endpoint lifelines are hidden do not participate.

/** A lifecycle problem in the visible chronological sequence for one lifeline. */
enum class Seq3LifecycleViolationKind {
    DUPLICATE_CREATE,
    DUPLICATE_DESTROY,
    CREATE_NOT_FIRST_INTERACTION,
    DESTROY_NOT_LAST_INTERACTION,
    CREATE_AFTER_DESTROY,
}

/**
 * A semantic (rather than diagnostic-position) lifecycle violation.  Keeping this value keyed to
 * target and rule lets a legacy document retain its known defect while unrelated commands proceed;
 * a candidate is rejected only when it adds a rule/target combination that was not already broken.
 * [count] distinguishes a third CREATE from an already-invalid pair of CREATEs.
 */
data class Seq3LifecycleViolation(
    val targetLifelineId: String,
    val kind: Seq3LifecycleViolationKind,
    val count: Int = 1,
)

/** All violations in the document's visible rendered chronology. */
fun seq3LifecycleViolations(document: Seq3Document): Set<Seq3LifecycleViolation> {
    val visibleLifelineIds = document.lifelines
        .filter { it.visibility == Seq3Visibility.VISIBLE }
        .mapTo(hashSetOf()) { it.id }
    val emissions = document.messages.flatMap { message ->
        visibleLifecycleEmissions(message, visibleLifelineIds)
    }
    val ordered = seq3ChronologicalOrder(
        document = document,
        items = emissions,
        messageIdOf = { it.message.id },
        timestampMillisOf = { emission ->
            // Match Seq3Layout/Seq3Emitters: old occurrences can lack an unrolled elapsed value,
            // in which case their wall-clock timestamp remains the chronological fallback.
            seq3EmissionElapsed(emission.message, emission.occurrence?.elapsedMillis)
                ?: seq3EmissionTimestamp(emission.message, emission.occurrence?.timestampMillis)
        },
        entryIdOf = { it.occurrence?.entryId },
    )

    val interactionsByLifeline = linkedMapOf<String, MutableList<Seq3LifecycleEmission>>()
    ordered.forEach { emission ->
        // A SELF message appears only once for its lifeline; all other arrow kinds can involve both
        // endpoints.  NOTE is excluded in visibleLifecycleEmissions because it is an annotation,
        // not a lifecycle interaction.
        emission.participantIds.forEach { lifelineId ->
            interactionsByLifeline.getOrPut(lifelineId, ::mutableListOf).add(emission)
        }
    }

    return buildSet {
        interactionsByLifeline.forEach { (lifelineId, interactions) ->
            val creates = interactions.filter { it.message.kind == Seq3Kind.CREATE && it.message.toLifelineId == lifelineId }
            val destroys = interactions.filter { it.message.kind == Seq3Kind.DESTROY && it.message.toLifelineId == lifelineId }
            if (creates.size > 1) add(Seq3LifecycleViolation(lifelineId, Seq3LifecycleViolationKind.DUPLICATE_CREATE, creates.size))
            if (destroys.size > 1) add(Seq3LifecycleViolation(lifelineId, Seq3LifecycleViolationKind.DUPLICATE_DESTROY, destroys.size))
            if (creates.isNotEmpty() && interactions.first() !in creates) {
                add(Seq3LifecycleViolation(lifelineId, Seq3LifecycleViolationKind.CREATE_NOT_FIRST_INTERACTION))
            }
            if (destroys.isNotEmpty() && interactions.last() !in destroys) {
                add(Seq3LifecycleViolation(lifelineId, Seq3LifecycleViolationKind.DESTROY_NOT_LAST_INTERACTION))
            }
            if (creates.isNotEmpty() && destroys.isNotEmpty() && ordered.indexOf(creates.first()) > ordered.indexOf(destroys.last())) {
                add(Seq3LifecycleViolation(lifelineId, Seq3LifecycleViolationKind.CREATE_AFTER_DESTROY))
            }
        }
    }
}

/**
 * The first lifecycle violation made worse by [candidate].  Rule/target combinations are compared
 * rather than raw data-class equality so reducing a legacy duplicate count (three CREATEs to two)
 * remains a permitted repair even though its diagnostic count necessarily changes.
 */
fun seq3NewLifecycleViolation(before: Seq3Document, candidate: Seq3Document): Seq3LifecycleViolation? {
    val previousCounts = seq3LifecycleViolations(before).associateBy(
        keySelector = { it.targetLifelineId to it.kind },
        valueTransform = { it.count },
    )
    return seq3LifecycleViolations(candidate)
        .filter { violation -> violation.count > (previousCounts[violation.targetLifelineId to violation.kind] ?: 0) }
        .sortedWith(compareBy<Seq3LifecycleViolation>({ it.targetLifelineId }, { it.kind.ordinal }, { it.count }))
        .firstOrNull()
}

/** Specific, user-facing rejection text used by the command boundary. */
fun Seq3LifecycleViolation.rejectionReason(): String = when (kind) {
    Seq3LifecycleViolationKind.DUPLICATE_CREATE ->
        "Lifecycle violation: $targetLifelineId has $count visible CREATE messages"
    Seq3LifecycleViolationKind.DUPLICATE_DESTROY ->
        "Lifecycle violation: $targetLifelineId has $count visible DESTROY messages"
    Seq3LifecycleViolationKind.CREATE_NOT_FIRST_INTERACTION ->
        "Lifecycle violation: CREATE for $targetLifelineId must be its first visible interaction"
    Seq3LifecycleViolationKind.DESTROY_NOT_LAST_INTERACTION ->
        "Lifecycle violation: DESTROY for $targetLifelineId must be its last visible interaction"
    Seq3LifecycleViolationKind.CREATE_AFTER_DESTROY ->
        "Lifecycle violation: CREATE for $targetLifelineId must occur before DESTROY"
}

private data class Seq3LifecycleEmission(
    val message: Seq3Message,
    val occurrence: Seq3Occurrence?,
    val participantIds: Set<String>,
)

private fun visibleLifecycleEmissions(message: Seq3Message, visibleLifelineIds: Set<String>): List<Seq3LifecycleEmission> {
    if (message.visibility != Seq3Visibility.VISIBLE || message.kind == Seq3Kind.NOTE) return emptyList()
    if (message.fromLifelineId !in visibleLifelineIds) return emptyList()
    // This matches the canvas/text emitters: a targeted message is not an interaction with an
    // invisible/missing target.  An unresolved arrow still visibly involves its source.
    val target = message.toLifelineId
    if (target != null && target !in visibleLifelineIds) return emptyList()
    val participants = buildSet {
        add(message.fromLifelineId)
        target?.let(::add)
    }
    val visibleOccurrences = message.occurrences.filter { it.visibility == Seq3Visibility.VISIBLE }
    if (message.occurrences.isNotEmpty() && visibleOccurrences.isEmpty()) return emptyList()
    val emittedOccurrences: List<Seq3Occurrence?> = when {
        message.occurrences.isEmpty() -> listOf(null)
        message.repeat == Seq3Repeat.EVERY -> visibleOccurrences
        message.repeat == Seq3Repeat.FIRST_LAST -> when (visibleOccurrences.size) {
            0 -> emptyList()
            1 -> listOf(visibleOccurrences.first())
            else -> listOf(visibleOccurrences.first(), visibleOccurrences.last())
        }
        // COLLAPSE_ABOVE emits every row through its threshold and only then becomes one collapsed
        // arrow, matching expandMessage/expandForLayout rather than treating queue grouping as a
        // visual collapse.
        else -> if (visibleOccurrences.size <= message.repeatThreshold) visibleOccurrences else listOf(visibleOccurrences.first())
    }
    return emittedOccurrences.map { occurrence -> Seq3LifecycleEmission(message, occurrence, participants) }
}
