package com.indagium.utils

import com.indagium.model.LogEntry
import com.indagium.model.LogItem
import java.util.RandomAccess

/** A snapshot of selected source positions. Stores only primitive indices, never boxed entries. */
internal class MatchingEntries private constructor(
    internal val source: List<LogEntry>,
    private val positions: PositionStore,
    override val size: Int,
) : AbstractList<LogEntry>(), RandomAccess {
    private class PositionStore(var values: IntArray, var used: Int)

    override fun get(index: Int): LogEntry {
        if (index !in 0 until size) throw IndexOutOfBoundsException("index: $index, size: $size")
        return source[positions.values[index]]
    }

    fun append(source: List<LogEntry>, tailPositions: IntArray, count: Int): MatchingEntries {
        val store = synchronized(positions) {
            val needed = size + count
            if (positions.used == size && positions.values.size >= needed) {
                tailPositions.copyInto(positions.values, size, 0, count)
                positions.used = needed
                positions
            } else {
                val values = positions.values.copyOf(maxOf(needed, size + size / 2 + 16))
                tailPositions.copyInto(values, size, 0, count)
                PositionStore(values, needed)
            }
        }
        return MatchingEntries(source, store, size + count)
    }

    companion object {
        fun create(source: List<LogEntry>, values: IntArray, count: Int): MatchingEntries =
            MatchingEntries(source, PositionStore(values, count), count)
    }
}

/**
 * A list-compatible flat rendering: Row wrappers exist only while accessed. Readers of a
 * releasable source must hold its row pin for the lifetime of this view (as the viewer does).
 */
internal class FlatRows private constructor(
    internal val entries: List<LogEntry>,
    private val token: Any,
    private val parentToken: Any?,
) : AbstractList<LogItem>(), RandomAccess {
    constructor(entries: List<LogEntry>) : this(entries, Any(), null)

    internal val sourceRows: List<LogEntry> get() = (entries as? MatchingEntries)?.source ?: entries
    override val size: Int get() = entries.size

    override fun get(index: Int): LogItem = LogItem.Row(entries[index], 0)

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other is FlatRows && size != other.size -> false
        else -> super.equals(other)
    }

    override fun hashCode(): Int = super.hashCode()

    fun extend(entries: List<LogEntry>): FlatRows = FlatRows(entries, Any(), token)

    // Only the immediate parent is proven to be a prefix. Tokens do not retain old row stores.
    fun isExtensionOf(old: List<*>): Boolean = this === old ||
        (old is FlatRows && parentToken === old.token && size >= old.size)
}
