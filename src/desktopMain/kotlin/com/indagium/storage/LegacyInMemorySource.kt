package com.indagium.storage

import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Collections
import java.util.UUID

@JvmInline internal value class SourceId(val value: String)

@JvmInline internal value class EntryId(val value: Int)

@JvmInline internal value class RowOrdinal(val value: Long)

internal data class SourceVersion(val lifetime: String, val revision: Long)

internal data class RowRef(val source: SourceId, val id: EntryId)

internal data class LegacyDecodedRow(val ref: RowRef, val entry: LogEntry)

internal data class StoragePage<T>(val version: SourceVersion, val start: Long, val items: List<T>, val endReached: Boolean)

/** Expected boundary failures. Coroutine cancellation is propagated separately. */
internal abstract class StorageFailure(message: String) : IllegalStateException(message) {
    class Closed : StorageFailure("Storage handle or snapshot is closed")

    class InvalidRequest(message: String) : StorageFailure(message)
}

internal interface SourceHandle : AutoCloseable {
    val id: SourceId
    val format: LogFormat

    suspend fun snapshot(): ReadSnapshot

    override fun close()
}

internal interface ReadSnapshot : AutoCloseable {
    val version: SourceVersion
    val rowCount: Long

    suspend fun rows(start: RowOrdinal, limit: Int): StoragePage<LegacyDecodedRow>

    /** Returns null only when the ID is absent; IDs are not row ordinals. */
    suspend fun resolve(id: EntryId): RowOrdinal?

    override fun close()
}

/**
 * Reference adapter for an already decoded legacy list. Construction copies the list and builds
 * an ID index, so total memory grows with the source. It performs no file I/O and is not a file
 * store, query engine, or viewer integration. Callers establish logical [id] reuse themselves.
 *
 * Each instance has a fresh lifetime, revision zero, and immutable contents. Acquired snapshots
 * own independent references and remain readable after this handle closes. Closing a snapshot
 * prevents further operations, while returned pages retain their own immutable list of rows.
 */
internal class LegacyInMemorySource(
    override val id: SourceId,
    override val format: LogFormat,
    entries: List<LogEntry>,
    private val maxPageRows: Int = DEFAULT_MAX_PAGE_ROWS,
) : SourceHandle {
    companion object {
        const val DEFAULT_MAX_PAGE_ROWS = 1024
    }

    private class Data(val entries: List<LogEntry>, val positions: Map<Int, Int>)

    private val lock = Any()
    private val sourceVersion = SourceVersion(UUID.randomUUID().toString(), 0)
    private var data: Data?

    init {
        if (maxPageRows <= 0) throw StorageFailure.InvalidRequest("Maximum page size must be positive")
        val copy = ArrayList(entries)
        val positions = HashMap<Int, Int>()
        copy.forEachIndexed { index, entry ->
            if (positions.put(entry.id, index) != null) {
                throw StorageFailure.InvalidRequest("Duplicate entry ID: ${entry.id}")
            }
        }
        data = Data(Collections.unmodifiableList(copy), positions)
    }

    override suspend fun snapshot(): ReadSnapshot {
        currentCoroutineContext().ensureActive()
        return synchronized(lock) {
            Snapshot(id, sourceVersion, maxPageRows, data ?: throw StorageFailure.Closed())
        }
    }

    override fun close() {
        synchronized(lock) { data = null }
    }

    private class Snapshot(
        private val source: SourceId,
        override val version: SourceVersion,
        private val maxPageRows: Int,
        data: Data,
    ) : ReadSnapshot {
        private val lock = Any()
        private var data: Data? = data
        override val rowCount: Long = data.entries.size.toLong()

        override suspend fun rows(start: RowOrdinal, limit: Int): StoragePage<LegacyDecodedRow> {
            currentCoroutineContext().ensureActive()
            val page = synchronized(lock) {
                val current = data ?: throw StorageFailure.Closed()
                if (start.value < 0 || limit !in 1..maxPageRows) {
                    throw StorageFailure.InvalidRequest("Page requires a nonnegative start and limit in 1..$maxPageRows")
                }
                if (start.value >= current.entries.size.toLong()) {
                    StoragePage(version, start.value, emptyList(), true)
                } else {
                    val first = start.value.toInt() // proven within the backing list's Int range
                    val count = minOf(limit, current.entries.size - first)
                    val rows = ArrayList<LegacyDecodedRow>(count)
                    for (index in first until first + count) {
                        val entry = current.entries[index]
                        rows.add(LegacyDecodedRow(RowRef(source, EntryId(entry.id)), entry))
                    }
                    StoragePage(version, start.value, Collections.unmodifiableList(rows), first + count == current.entries.size)
                }
            }
            currentCoroutineContext().ensureActive()
            return page
        }

        override suspend fun resolve(id: EntryId): RowOrdinal? {
            currentCoroutineContext().ensureActive()
            val result = synchronized(lock) {
                val current = data ?: throw StorageFailure.Closed()
                current.positions[id.value]?.let { RowOrdinal(it.toLong()) }
            }
            currentCoroutineContext().ensureActive()
            return result
        }

        override fun close() {
            synchronized(lock) { data = null }
        }
    }
}
