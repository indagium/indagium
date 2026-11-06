package com.indagium.storage

import com.indagium.model.LogEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.UUID

/** A bounded immutable page of source ordinals associated with one snapshot version. */
internal class StorageOrdinalPage(
    val version: SourceVersion,
    val start: Long,
    values: LongArray,
    val endReached: Boolean,
) {
    private val values = values.copyOf()
    val size: Int get() = values.size

    operator fun get(index: Int): RowOrdinal = RowOrdinal(values[index])
}

/**
 * Experimental query over an owned snapshot. [scan] must complete before paging.
 * Positions are row ordinals, not entry IDs, and are associated with [version] for this lease.
 * The predicate is supplied by the caller. Scans and reads run on Dispatchers.Default; callers
 * must fence publication when a query is replaced. Closing this query also closes the snapshot.
 *
 * [scanPageRows] must be accepted by the snapshot. The result index is proportional to the
 * number of matches; result pages and source reads are bounded separately.
 */
internal class StorageQuery(
    private val snapshot: ReadSnapshot,
    private val scanPageRows: Int,
    private val maxResultPageRows: Int,
) : AutoCloseable {
    val version: SourceVersion = snapshot.version
    val queryId: String = UUID.randomUUID().toString()
    private val lock = Any()
    private var closed = false
    private var scanning = false
    private var positions: LongArray? = null
    private var publishedBy: Any? = null

    init {
        if (scanPageRows <= 0 || maxResultPageRows <= 0) {
            throw StorageFailure.InvalidRequest("Page limits must be positive")
        }
    }

    suspend fun scan(predicate: (LogEntry) -> Boolean): Long {
        val operation = Any()
        return try {
            withContext(Dispatchers.Default) { StorageScanLimiter.withScan { scanOnWorker(operation, predicate) } }
        } catch (cancelled: CancellationException) {
            // Cancellation can occur while dispatching a completed result back to its caller.
            synchronized(lock) {
                if (publishedBy === operation) {
                    positions = null
                    publishedBy = null
                }
            }
            throw cancelled
        }
    }

    private suspend fun scanOnWorker(operation: Any, predicate: (LogEntry) -> Boolean): Long {
        val context = currentCoroutineContext()
        context.ensureActive()
        synchronized(lock) {
            ensureOpen()
            if (scanning || positions != null) throw StorageFailure.InvalidRequest("Query already scanned or scanning")
            scanning = true
        }
        try {
            var found = LongArray(16)
            var count = 0
            var start = 0L
            while (start < snapshot.rowCount) {
                context.ensureActive()
                synchronized(lock) { ensureOpen() }
                val page = snapshot.rows(RowOrdinal(start), scanPageRows)
                check(page.version == version && page.start == start && page.items.isNotEmpty()) {
                    "Inconsistent snapshot page"
                }
                check(
                    page.items.size <= scanPageRows && page.items.size.toLong() <= snapshot.rowCount - start &&
                        page.endReached == (start + page.items.size == snapshot.rowCount),
                ) { "Inconsistent snapshot page" }
                for ((offset, row) in page.items.withIndex()) {
                    context.ensureActive()
                    if (predicate(row.entry)) {
                        if (count == Int.MAX_VALUE) throw StorageFailure.InvalidRequest("Too many query matches")
                        if (count == found.size) {
                            val next = minOf(Int.MAX_VALUE.toLong(), found.size.toLong() * 2).toInt()
                            found = found.copyOf(next)
                        }
                        found[count++] = start + offset
                    }
                }
                start += page.items.size
            }
            context.ensureActive()
            val completed = found.copyOf(count)
            synchronized(lock) {
                ensureOpen()
                context.ensureActive()
                positions = completed
                publishedBy = operation
            }
            return count.toLong()
        } finally {
            synchronized(lock) { scanning = false }
        }
    }

    /** Returns the result ordinal for a matching entry ID, or null if it is absent or filtered out. */
    suspend fun positionOf(id: EntryId): Long? = withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        context.ensureActive()
        val index = synchronized(lock) {
            ensureOpen()
            positions ?: throw StorageFailure.InvalidRequest("Query scan has not completed")
        }
        val sourcePosition = snapshot.resolve(id)
        context.ensureActive()
        synchronized(lock) {
            ensureOpen()
            context.ensureActive()
            if (positions !== index) throw StorageFailure.InvalidRequest("Query result changed during lookup")
            sourcePosition?.let {
                val result = index.binarySearch(it.value)
                if (result >= 0) result.toLong() else null
            }
        }
    }

    /** Validate the query and underlying snapshot after a multi-page operation. */
    suspend fun validate() = withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        context.ensureActive()
        val index = synchronized(lock) {
            ensureOpen()
            positions ?: throw StorageFailure.InvalidRequest("Query scan has not completed")
        }
        // File-backed snapshots validate metadata on resolve. The ID is only a probe; its result
        // is ignored and it may legitimately exist in a legacy snapshot.
        snapshot.resolve(EntryId(0))
        context.ensureActive()
        synchronized(lock) {
            ensureOpen()
            context.ensureActive()
            if (positions !== index) throw StorageFailure.InvalidRequest("Query result changed during validation")
        }
    }

    /** Copies a bounded page of source ordinals without decoding the rows again. */
    suspend fun ordinals(start: Long, limit: Int): StorageOrdinalPage = withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        context.ensureActive()
        synchronized(lock) {
            ensureOpen()
            if (start < 0 || limit !in 1..maxResultPageRows) {
                throw StorageFailure.InvalidRequest("Invalid result page")
            }
            val all = positions ?: throw StorageFailure.InvalidRequest("Query scan has not completed")
            val first = minOf(start, all.size.toLong()).toInt()
            val end = first + minOf(limit, all.size - first)
            val copy = all.copyOfRange(first, end)
            context.ensureActive()
            StorageOrdinalPage(version, start, copy, end == all.size)
        }
    }

    suspend fun rows(start: Long, limit: Int): StoragePage<LegacyDecodedRow> = withContext(Dispatchers.Default) {
        rowsOnWorker(start, limit)
    }

    private suspend fun rowsOnWorker(start: Long, limit: Int): StoragePage<LegacyDecodedRow> {
        val context = currentCoroutineContext()
        context.ensureActive()
        val selected = synchronized(lock) {
            ensureOpen()
            if (start < 0 || limit !in 1..maxResultPageRows) {
                throw StorageFailure.InvalidRequest("Invalid result page")
            }
            val all = positions ?: throw StorageFailure.InvalidRequest("Query scan has not completed")
            if (start >= all.size.toLong()) {
                LongArray(0) to true
            } else {
                val first = start.toInt()
                val end = first + minOf(limit, all.size - first)
                all.copyOfRange(first, end) to (end == all.size)
            }
        }
        val result = ArrayList<LegacyDecodedRow>(selected.first.size)
        var index = 0
        while (index < selected.first.size) {
            context.ensureActive()
            val position = selected.first[index]
            var count = 1
            while (
                index + count < selected.first.size && count < scanPageRows &&
                selected.first[index + count] == position + count
            ) {
                count++
            }
            val page = snapshot.rows(RowOrdinal(position), count)
            check(page.version == version && page.start == position && page.items.size == count) {
                "Inconsistent snapshot page"
            }
            result.addAll(page.items)
            index += count
        }
        context.ensureActive()
        return synchronized(lock) {
            ensureOpen()
            context.ensureActive()
            StoragePage(version, start, Collections.unmodifiableList(result), selected.second)
        }
    }

    // Call only while holding lock.
    private fun ensureOpen() {
        if (closed) throw StorageFailure.Closed()
    }

    override fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                positions = null
                publishedBy = null
                snapshot.close()
            }
        }
    }
}
