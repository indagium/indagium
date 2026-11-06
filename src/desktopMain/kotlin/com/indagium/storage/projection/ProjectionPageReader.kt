package com.indagium.storage.projection

import com.indagium.storage.LegacyDecodedRow
import com.indagium.storage.ReadSnapshot
import com.indagium.storage.RowOrdinal
import com.indagium.storage.StorageFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Collections

internal sealed interface DecodedDisplayEvent {
    data class Row(val event: DisplayEvent.Row, val row: LegacyDecodedRow) : DecodedDisplayEvent

    data class Header(val descriptor: HeaderDescriptor, val anchor: LegacyDecodedRow) : DecodedDisplayEvent
}

internal data class DecodedDisplayPage(
    val generation: ProjectionGeneration,
    val start: Long,
    val items: List<DecodedDisplayEvent>,
    val endReached: Boolean,
)

/** Owns the snapshot lease. Publication of a completed page must still be fenced by the caller. */
internal class ProjectionPageReader(
    private val projection: DisplayProjection,
    private val snapshot: ReadSnapshot,
    private val sourcePageSize: Int = 1,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    init {
        if (sourcePageSize <= 0 || snapshot.version != projection.generation.sourceVersion) {
            snapshot.close()
            throw StorageFailure.InvalidRequest("Invalid source page size or projection source version")
        }
    }

    @Suppress("CyclomaticComplexMethod") // Validates and reconstructs one bounded, reordered page.
    suspend fun page(start: Long, limit: Int): DecodedDisplayPage = withContext(Dispatchers.IO) {
        checkOpen()
        val display = projection.page(start, limit)
        val ordinals = sortedSetOf<Long>()
        for (event in display.items) {
            currentCoroutineContext().ensureActive()
            val ordinal = when (event) {
                is DisplayEvent.Row -> event.ordinal
                is DisplayEvent.Header -> event.descriptor.anchorOrdinal
            }
            if (ordinal < 0 || ordinal >= snapshot.rowCount) throw StorageFailure.InvalidRequest("Source ordinal outside snapshot")
            ordinals.add(ordinal)
        }
        val requested = ordinals.toList()
        val decoded = HashMap<Long, LegacyDecodedRow>()
        var index = 0
        while (index < requested.size) {
            checkOpen()
            val first = requested[index]
            var end = index + 1
            while (end < requested.size && end - index < sourcePageSize && requested[end] - first == (end - index).toLong()) end++
            val count = end - index
            val page = snapshot.rows(RowOrdinal(first), count)
            checkOpen()
            if (page.version != projection.generation.sourceVersion || page.start != first || page.items.size != count ||
                page.endReached != (first + count == snapshot.rowCount)) {
                throw StorageFailure.InvalidRequest("Inconsistent source page")
            }
            for (i in 0 until count) decoded[requested[index + i]] = page.items[i]
            index = end
        }
        val result = ArrayList<DecodedDisplayEvent>(display.items.size)
        for (event in display.items) {
            currentCoroutineContext().ensureActive()
            when (event) {
                is DisplayEvent.Row -> {
                    val row = decoded.getValue(event.ordinal)
                    if (row.ref.id != event.id || row.entry.id != event.id.value) throw StorageFailure.InvalidRequest("Projection row ID mismatch")
                    result.add(DecodedDisplayEvent.Row(event, row))
                }
                is DisplayEvent.Header -> {
                    val row = decoded.getValue(event.descriptor.anchorOrdinal)
                    if (row.ref.id.value != row.entry.id || (event.descriptor.anchorId != null && row.ref.id != event.descriptor.anchorId)) {
                        throw StorageFailure.InvalidRequest("Projection anchor ID mismatch")
                    }
                    result.add(DecodedDisplayEvent.Header(event.descriptor, row))
                }
            }
        }
        checkOpen()
        val probeStart = snapshot.rowCount
        val probe = snapshot.rows(RowOrdinal(probeStart), 1)
        checkOpen()
        if (probe.version != display.generation.sourceVersion || probe.start != probeStart || probe.items.isNotEmpty() || !probe.endReached) {
            throw StorageFailure.InvalidRequest("Inconsistent source validity probe")
        }
        if (snapshot.version != display.generation.sourceVersion) throw StorageFailure.InvalidRequest("Source version changed")
        DecodedDisplayPage(display.generation, display.start, Collections.unmodifiableList(result), display.endReached)
    }

    private suspend fun checkOpen() {
        currentCoroutineContext().ensureActive()
        synchronized(lock) { if (closed) throw StorageFailure.Closed() }
    }

    override fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                snapshot.close()
            }
        }
    }
}
