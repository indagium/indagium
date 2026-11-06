package com.indagium.storage

import java.util.Collections

/** Identity of the exact displayed result on which a selection was made. */
internal data class SelectionGeneration(
    val sourceId: SourceId,
    val sourceVersion: SourceVersion,
    val queryId: String,
    val projectionId: String,
    val foldGeneration: Long,
    val paneGeneration: Long,
)

/** A clicked occurrence is distinct from ID membership: the same ID may appear more than once. */
internal data class SelectionOccurrence(val id: EntryId, val displayPosition: Long, val occurrence: Long) {
    init {
        if (displayPosition < 0 || occurrence < 0) throw StorageFailure.InvalidRequest("Negative selection occurrence")
    }
}

/** These bases describe displayed items, not all rows matching the underlying query. */
internal sealed interface SelectionBase {
    data object Explicit : SelectionBase

    /** Both endpoints are inclusive; traversal is in display order regardless of click direction. */
    data class DisplayRange(val anchor: SelectionOccurrence, val endpoint: SelectionOccurrence) : SelectionBase {
        val firstPosition: Long get() = minOf(anchor.displayPosition, endpoint.displayPosition)
        val lastPosition: Long get() = maxOf(anchor.displayPosition, endpoint.displayPosition)
    }

    /** Excludes headers and folded members. */
    data object AllDisplayedRows : SelectionBase

    /** Includes displayed headers, but not folded members. */
    data object AllDisplayedItems : SelectionBase
}

internal enum class SelectionGenerationStatus { CURRENT, MISMATCH }

internal enum class SelectionOverride { INCLUDED, EXCLUDED, NONE }

/**
 * Experimental expression only: a consumer must resolve the base under the exact generation and
 * apply the ID overrides after deduplicating displayed occurrences. Range and all-items bases include
 * header IDs; all-rows excludes headers. Explicit inclusions may add any ID. This type does not
 * define action-specific copy, export, annotation or group-expansion behavior and owns no lease.
 * A generation mismatch requires an explicit application decision; this value is never auto-rebased.
 * Sets preserve insertion order, and contain only explicit edits rather than materialized select-all.
 */
internal class StorageSelection(
    val generation: SelectionGeneration,
    val base: SelectionBase,
    val anchor: SelectionOccurrence? = (base as? SelectionBase.DisplayRange)?.anchor,
    inclusions: Set<EntryId> = emptySet(),
    exclusions: Set<EntryId> = emptySet(),
) {
    val inclusions: Set<EntryId> = Collections.unmodifiableSet(LinkedHashSet(inclusions))
    val exclusions: Set<EntryId> = Collections.unmodifiableSet(LinkedHashSet(exclusions))

    init {
        if (this.inclusions.any { it in this.exclusions }) {
            throw StorageFailure.InvalidRequest("Selection inclusions and exclusions overlap")
        }
        if (base is SelectionBase.DisplayRange && anchor != base.anchor) {
            throw StorageFailure.InvalidRequest("Range anchor must match selection anchor")
        }
    }

    fun generationStatus(current: SelectionGeneration): SelectionGenerationStatus =
        if (generation == current) SelectionGenerationStatus.CURRENT else SelectionGenerationStatus.MISMATCH

    fun overrideFor(id: EntryId): SelectionOverride = when (id) {
        in inclusions -> SelectionOverride.INCLUDED
        in exclusions -> SelectionOverride.EXCLUDED
        else -> SelectionOverride.NONE
    }
}
