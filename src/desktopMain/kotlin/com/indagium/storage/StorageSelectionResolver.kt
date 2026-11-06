package com.indagium.storage

import com.indagium.storage.projection.DecodedDisplayEvent
import com.indagium.storage.projection.DisplayProjection
import com.indagium.storage.projection.ProjectionPageReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.BitSet

/**
 * Resolve an expression to numerically sorted unique IDs for copy-like actions. This allocates
 * primitive storage proportional to selected IDs; it does not expand folded groups. The caller
 * owns both leases and must fence its own generation again before publishing or copying output.
 * Primitive sorting is not interruptible; cancellation is checked immediately before and after it.
 */
internal suspend fun resolveSelectionForCopy(
    selection: StorageSelection,
    current: SelectionGeneration,
    projection: DisplayProjection,
    reader: ProjectionPageReader,
    source: ReadSnapshot,
    pageSize: Int,
    maxSourceRows: Int = 10_000_000,
    maxSelectedIds: Int = 10_000_000,
): IntArray = withContext(Dispatchers.Default) {
    val generation = projection.generation
    val matchingView = selection.generationStatus(current) == SelectionGenerationStatus.CURRENT &&
        generation.sourceVersion == current.sourceVersion && generation.queryId == current.queryId &&
        generation.projectionId == current.projectionId && source.version == current.sourceVersion
    if (!matchingView) throw StorageFailure.InvalidRequest("Selection belongs to a different view generation")
    if (pageSize <= 0 || maxSourceRows < 0 || maxSelectedIds < 0 || source.rowCount !in 0..maxSourceRows.toLong()) {
        throw StorageFailure.InvalidRequest("Invalid selection resolution limits")
    }
    val range = selection.base as? SelectionBase.DisplayRange
    if (range != null && range.lastPosition >= projection.size) {
        throw StorageFailure.InvalidRequest("Selection range is outside the view")
    }
    val resolver = SelectionResolver(selection, current, projection, reader, source, pageSize, maxSelectedIds)
    resolver.resolve()
}

/** Single-operation state: no decoded page or lease escapes this resolver. */
@Suppress("LongParameterList")
private class SelectionResolver(
    private val selection: StorageSelection,
    private val current: SelectionGeneration,
    private val projection: DisplayProjection,
    private val reader: ProjectionPageReader,
    private val source: ReadSnapshot,
    private val pageSize: Int,
    private val maxSelectedIds: Int,
) {
    private val seen = BitSet(source.rowCount.toInt())
    private var result = IntArray(minOf(maxSelectedIds, 16))
    private var size = 0
    private val range = selection.base as? SelectionBase.DisplayRange
    private val anchors = range?.let { listOf(it.anchor, it.endpoint).distinct() }.orEmpty()
    private val occurrenceCounts = LongArray(anchors.size)
    private val verifiedAnchors = BooleanArray(anchors.size)

    suspend fun resolve(): IntArray {
        if (selection.base != SelectionBase.Explicit) scanDisplayed()
        if (verifiedAnchors.any { !it }) throw StorageFailure.InvalidRequest("Selection anchor no longer matches the view")
        addInclusions()
        currentCoroutineContext().ensureActive()
        val sorted = result.copyOf(size).also { it.sort() }
        currentCoroutineContext().ensureActive()
        val end = source.rows(RowOrdinal(source.rowCount), 1)
        check(end.version == current.sourceVersion && end.start == source.rowCount && end.items.isEmpty() && end.endReached) {
            "Inconsistent source end"
        }
        currentCoroutineContext().ensureActive()
        return sorted
    }

    private suspend fun scanDisplayed() {
        var start = 0L
        while (start < projection.size) {
            currentCoroutineContext().ensureActive()
            val page = reader.page(start, pageSize)
            check(page.generation == projection.generation && page.start == start && page.items.isNotEmpty()) {
                "Inconsistent display page"
            }
            check(page.items.size <= pageSize && page.items.size.toLong() <= projection.size - start &&
                page.endReached == (start + page.items.size == projection.size)) { "Inconsistent display page" }
            for ((offset, event) in page.items.withIndex()) process(event, start + offset)
            start += page.items.size
            if (page.endReached) break
        }
    }

    private fun process(event: DecodedDisplayEvent, position: Long) {
        val row = when (event) {
            is DecodedDisplayEvent.Row -> event.row
            is DecodedDisplayEvent.Header -> event.anchor
        }
        val ordinal = when (event) {
            is DecodedDisplayEvent.Row -> event.event.ordinal
            is DecodedDisplayEvent.Header -> event.descriptor.anchorOrdinal
        }
        if (row.ref.source != current.sourceId) throw StorageFailure.InvalidRequest("Selection source identity mismatch")
        for (index in anchors.indices) {
            val anchor = anchors[index]
            if (row.ref.id == anchor.id) {
                if (position == anchor.displayPosition && occurrenceCounts[index] == anchor.occurrence) verifiedAnchors[index] = true
                occurrenceCounts[index]++
            }
        }
        val selected = when (val base = selection.base) {
            SelectionBase.Explicit -> false
            SelectionBase.AllDisplayedRows -> event is DecodedDisplayEvent.Row
            SelectionBase.AllDisplayedItems -> true
            is SelectionBase.DisplayRange -> position in base.firstPosition..base.lastPosition
        }
        if (selected) add(ordinal, row.ref.id)
    }

    private suspend fun addInclusions() {
        for (id in selection.inclusions) {
            currentCoroutineContext().ensureActive()
            val ordinal = source.resolve(id) ?: continue
            val resolved = source.rows(ordinal, 1)
            check(resolved.version == current.sourceVersion && resolved.start == ordinal.value && resolved.items.size == 1) {
                "Inconsistent selected row"
            }
            val row = resolved.items.single()
            if (row.ref.source != current.sourceId || row.ref.id != id || row.entry.id != id.value) {
                throw StorageFailure.InvalidRequest("Selection source identity mismatch")
            }
            add(ordinal.value, id)
        }
    }

    private fun add(ordinal: Long, id: EntryId) {
        if (ordinal < 0 || ordinal >= source.rowCount) throw StorageFailure.InvalidRequest("Selection source ordinal is invalid")
        if (id in selection.exclusions || seen[ordinal.toInt()]) return
        if (size >= maxSelectedIds) throw StorageFailure.InvalidRequest("Selection exceeds the ID limit")
        if (size == result.size) {
            val capacity = minOf(maxSelectedIds.toLong(), maxOf(1L, result.size.toLong() * 2)).toInt()
            result = result.copyOf(capacity)
        }
        seen.set(ordinal.toInt())
        result[size++] = id.value
    }
}
