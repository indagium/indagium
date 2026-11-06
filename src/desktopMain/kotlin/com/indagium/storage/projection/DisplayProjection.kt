package com.indagium.storage.projection

import androidx.compose.ui.graphics.Color
import com.indagium.model.ManualCollapseDirection
import com.indagium.storage.EntryId
import com.indagium.storage.SourceVersion
import com.indagium.storage.StorageFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Collections

private const val OCCURRENCE_CANCELLATION_INTERVAL = 1024

internal data class RowStyle(val indent: Int = 0, val groupColor: Color? = null, val scopedSeqColor: Color? = null)

/** Explicit output of a grouping algorithm; this module does not implement legacy grouping. */
internal sealed interface ProjectionEvent {
    data class Row(val ordinal: Long, val id: EntryId, val style: RowStyle = RowStyle()) : ProjectionEvent

    data class Header(val descriptor: HeaderDescriptor) : ProjectionEvent
}

/**
 * Membership contains source row ordinals, including sparse or noncontiguous members.
 * Extents in renderer-generated descriptors are exclusive filtered positions; -1 means absent.
 * Manual membership is represented by its range, not enumerated here.
 */
@Suppress("LongParameterList") // Mirrors the supported grouped-header fields.
internal class HeaderDescriptor(
    val groupId: String,
    val kind: String,
    val anchorOrdinal: Long,
    val declaredEndFilteredPosition: Long,
    val hostedEndFilteredPosition: Long,
    val depth: Int,
    val expanded: Boolean,
    membership: LongArray,
    val anchorId: EntryId? = null,
    val count: Int = 0,
    val color: Color? = null,
    val scopeTid: Int? = null,
    val direction: ManualCollapseDirection? = null,
) {
    private val members = membership.copyOf()

    fun membership(): LongArray = members.copyOf()
}

internal data class ProjectionGeneration(
    val sourceVersion: SourceVersion,
    val queryId: String,
    val projectionId: String,
    val requestToken: Long,
)

internal sealed interface DisplayEvent {
    data class Row(val ordinal: Long, val id: EntryId, val style: RowStyle = RowStyle()) : DisplayEvent

    data class Header(val descriptor: HeaderDescriptor) : DisplayEvent
}

internal data class DisplayPage(
    val generation: ProjectionGeneration,
    val start: Long,
    val items: List<DisplayEvent>,
    val endReached: Boolean,
)

/**
 * Immutable event projection. Row runs use a starting source ordinal and primitive IDs;
 * headers and run boundaries are sparse. Reordered/non-adjacent rows start new runs. Memory
 * remains O(rows + runs + membership), and adversarial ordering can create O(rows) runs.
 * No decoded entries or source leases are held. Publication fencing belongs to the caller.
 */
internal class DisplayProjection private constructor(
    val generation: ProjectionGeneration,
    private val segments: List<Segment>,
    private val ends: LongArray,
    private val maxPageRows: Int,
) {
    private sealed interface Segment {
        val size: Int

        class Run(val start: Long, val ids: IntArray, val style: RowStyle) : Segment { override val size get() = ids.size }

        class Header(val value: HeaderDescriptor) : Segment { override val size = 1 }
    }

    val size: Long get() = ends.lastOrNull() ?: 0L

    suspend fun page(start: Long, limit: Int): DisplayPage {
        val context = currentCoroutineContext()
        context.ensureActive()
        if (start < 0 || limit !in 1..maxPageRows) throw StorageFailure.InvalidRequest("Invalid display page")
        if (start >= size) return DisplayPage(generation, start, emptyList(), true)
        val result = ArrayList<DisplayEvent>(minOf(limit.toLong(), size - start).toInt())
        var index = findSegment(start)
        var position = start
        while (position < size && result.size < limit) {
            context.ensureActive()
            val before = if (index == 0) 0L else ends[index - 1]
            val offset = (position - before).toInt()
            when (val segment = segments[index]) {
                is Segment.Header -> {
                    result.add(DisplayEvent.Header(segment.value))
                    position++
                }
                is Segment.Run -> {
                    var i = offset
                    while (i < segment.size && result.size < limit) {
                        context.ensureActive()
                        result.add(DisplayEvent.Row(segment.start + i, EntryId(segment.ids[i]), segment.style))
                        i++
                        position++
                    }
                }
            }
            index++
        }
        return DisplayPage(generation, start, Collections.unmodifiableList(result), position == size)
    }

    /** Returns the requested zero-based displayed occurrence; headers are not searched. */
    suspend fun locate(id: EntryId, occurrence: Long): Long? = withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        context.ensureActive()
        if (occurrence < 0) throw StorageFailure.InvalidRequest("Negative occurrence")
        var seen = 0L
        var base = 0L
        for (segment in segments) {
            context.ensureActive()
            if (segment is Segment.Run) {
                for (i in segment.ids.indices) {
                    context.ensureActive()
                    if (segment.ids[i] == id.value) {
                        if (seen == occurrence) return@withContext base + i
                        seen++
                    }
                }
            }
            base += segment.size
        }
        null
    }

    /** Counts matching displayed rows and headers before a position and validates its identity. */
    suspend fun occurrenceAt(position: Long, id: EntryId): Long = withContext(Dispatchers.Default) {
        if (position !in 0 until size) throw StorageFailure.InvalidRequest("Display position is outside the projection")
        val context = currentCoroutineContext()
        var base = 0L
        var seen = 0L
        for (segment in segments) {
            context.ensureActive()
            when (segment) {
                is Segment.Header -> {
                    val anchor = segment.value.anchorId ?: throw StorageFailure.InvalidRequest("Header identity is unavailable")
                    if (base == position) {
                        if (anchor != id) throw StorageFailure.InvalidRequest("Display identity changed")
                        return@withContext seen
                    }
                    if (anchor == id) seen++
                }
                is Segment.Run -> {
                    val count = minOf(segment.size.toLong(), position - base + 1).coerceAtLeast(0).toInt()
                    for (index in 0 until count) {
                        if (index % OCCURRENCE_CANCELLATION_INTERVAL == 0) context.ensureActive()
                        if (base + index == position) {
                            if (segment.ids[index] != id.value) throw StorageFailure.InvalidRequest("Display identity changed")
                            return@withContext seen
                        }
                        if (segment.ids[index] == id.value) seen++
                    }
                }
            }
            base += segment.size
        }
        throw StorageFailure.InvalidRequest("Display position is unavailable")
    }

    /** Finds the first displayed occurrence, including headers. */
    suspend fun locateItem(id: EntryId): Long? = withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        var base = 0L
        for (segment in segments) {
            context.ensureActive()
            when (segment) {
                is Segment.Header -> if (segment.value.anchorId == id) return@withContext base
                is Segment.Run -> for (index in segment.ids.indices) {
                    if (index % OCCURRENCE_CANCELLATION_INTERVAL == 0) context.ensureActive()
                    if (segment.ids[index] == id.value) return@withContext base + index
                }
            }
            base += segment.size
        }
        null
    }

    private fun findSegment(position: Long): Int {
        var low = 0
        var high = ends.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (ends[middle] <= position) low = middle + 1 else high = middle
        }
        return low
    }

    /**
     * Single-owner, non-thread-safe accumulator. Append calls run on the caller's dispatcher;
     * producers should run their traversal off the UI thread. Finish consumes the builder even
     * if cancelled, and never exposes a partial projection.
     */
    class Builder(
        private val generation: ProjectionGeneration,
        private val maxPageRows: Int,
    ) {
        private val segments = ArrayList<Segment>()
        private var runStart = 0L
        private var ids = IntArray(16)
        private var count = 0
        private var runStyle = RowStyle()
        private var finished = false

        init {
            if (maxPageRows <= 0) throw StorageFailure.InvalidRequest("Maximum page size must be positive")
        }

        suspend fun appendRow(ordinal: Long, id: EntryId, style: RowStyle = RowStyle()) {
            currentCoroutineContext().ensureActive()
            appendRowUnchecked(ordinal, id, style)
        }

        internal fun appendRowUnchecked(ordinal: Long, id: EntryId, style: RowStyle) {
            ensureBuilding()
            if (ordinal < 0) throw StorageFailure.InvalidRequest("Negative row ordinal")
            if (count != 0 && (runStyle != style || runStart > Long.MAX_VALUE - count || ordinal != runStart + count)) flush()
            if (count == 0) {
                runStart = ordinal
                runStyle = style
            }
            if (count == ids.size) {
                if (ids.size >= Int.MAX_VALUE / 2) flush() else ids = ids.copyOf(ids.size * 2)
                if (count == 0) {
                    runStart = ordinal
                    runStyle = style
                }
            }
            ids[count++] = id.value
        }

        suspend fun appendHeader(descriptor: HeaderDescriptor) {
            currentCoroutineContext().ensureActive()
            appendHeaderUnchecked(descriptor)
        }

        internal fun appendHeaderUnchecked(descriptor: HeaderDescriptor) {
            ensureBuilding()
            flush()
            segments.add(Segment.Header(descriptor))
        }

        suspend fun finish(): DisplayProjection {
            ensureBuilding()
            finished = true
            currentCoroutineContext().ensureActive()
            return withContext(Dispatchers.Default) {
                flush()
                val ends = LongArray(segments.size)
                var total = 0L
                for (i in segments.indices) {
                    currentCoroutineContext().ensureActive()
                    if (total > Long.MAX_VALUE - segments[i].size) throw StorageFailure.InvalidRequest("Display count overflow")
                    total += segments[i].size
                    ends[i] = total
                }
                currentCoroutineContext().ensureActive()
                DisplayProjection(generation, segments.toList(), ends, maxPageRows)
            }
        }

        private fun ensureBuilding() {
            if (finished) throw StorageFailure.InvalidRequest("Projection builder already finished")
        }

        private fun flush() {
            if (count != 0) {
                segments.add(Segment.Run(runStart, ids.copyOf(count), runStyle))
                count = 0
            }
        }
    }

    companion object {
        /** Consumes an explicit event stream once. Cancellation never returns a partial projection. */
        suspend fun build(
            generation: ProjectionGeneration,
            events: Iterable<ProjectionEvent>,
            maxPageRows: Int,
        ): DisplayProjection = withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            val builder = Builder(generation, maxPageRows)
            for (event in events) {
                currentCoroutineContext().ensureActive()
                when (event) {
                    is ProjectionEvent.Header -> builder.appendHeader(event.descriptor)
                    is ProjectionEvent.Row -> builder.appendRow(event.ordinal, event.id, event.style)
                }
            }
            builder.finish()
        }
    }
}
