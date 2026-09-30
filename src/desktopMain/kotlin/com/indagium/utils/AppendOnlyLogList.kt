package com.indagium.utils

import com.indagium.model.LogEntry

private const val MIN_CAPACITY = 1024

// Keep below the JVM's practical max array length (Integer.MAX_VALUE - 8 on HotSpot).
private const val MAX_CAPACITY = Int.MAX_VALUE - 8

/**
 * An immutable [List] view over a growable backing array that successive views share, so that
 * appending a batch to a long list costs O(batch) instead of copying every existing element.
 *
 * Why: a live capture appends a ~1 s batch to a tab's `logData` thousands of times. `list + batch`
 * copies the whole reference array each time; at 1.3M rows that is a 5 MB humongous allocation per
 * second, which made G1 grow the committed heap to ~6 GB for ~330 MB of live data.
 *
 * A view is `(store, size)`. The store holds the slots plus a `committed` count of how many slots
 * have ever been written. [appendLogEntries] extends in place only when the base view is the newest
 * one (`size == committed`) and the store has room; otherwise it copies into a fresh store. An
 * older view therefore never observes elements appended after it was created (it never reads an
 * index >= its own `size`), and two views that diverge (a "branch" append from an older view) end
 * up on different stores.
 *
 * Memory model: writers hold the store's monitor, write the elements into the slots, and only then
 * construct the new view, whose fields are `final`. By the JLS final-field rule, any thread that
 * obtains the view reference (even through a data race) sees the store and every slot written
 * before the constructor returned. Readers take no lock: a slot below a view's `size` is never
 * rewritten (slots are written exactly once, at index `committed`), and slots at or above `size`
 * are never read through that view. Callers additionally serialize appends under
 * `AppState.stateLock`; the store monitor makes concurrent appends safe on their own too.
 *
 * The view keeps the whole store alive (including slots newer than its own size) for as long as it
 * is referenced. Extends [AbstractList], so `equals`/`hashCode`/`iterator`/`subList` keep standard
 * list semantics (`equals` compares sizes first, so differently-sized views are unequal in O(1)).
 */
class AppendOnlyLogList private constructor(
    private val store: Store,
    override val size: Int,
) : AbstractList<LogEntry>(), RandomAccess {
    private class Store(val slots: Array<LogEntry?>) {
        /** Number of leading slots written so far. Guarded by `synchronized(this)`. */
        var committed: Int = 0
    }

    /** Test hook: two views return the same object iff they share a backing store. */
    internal val storeIdentity: Any get() = store

    /** Test hook: slot count of the backing store. */
    internal val capacity: Int get() = store.slots.size

    override fun get(index: Int): LogEntry {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("index: $index, size: $size")
        return store.slots[index] ?: error("unwritten slot $index below size $size")
    }

    // Two views over the same store and size are identical in content by construction, so the
    // common "is this the same snapshot" question never degrades to an element-by-element compare.
    override fun equals(other: Any?): Boolean =
        if (other is AppendOnlyLogList && other.store === store && other.size == size) true else super.equals(other)

    override fun hashCode(): Int = super.hashCode()

    /** In-place append, or null when this view is not the newest of its store or it has no room. */
    private fun tryAppend(batch: List<LogEntry>): AppendOnlyLogList? = synchronized(store) {
        val slots = store.slots
        if (size != store.committed || batch.size > slots.size - size) return null
        var i = size
        for (entry in batch) slots[i++] = entry
        store.committed = i
        AppendOnlyLogList(store, i)
    }

    internal companion object {
        fun copyOf(base: List<LogEntry>, batch: List<LogEntry>): AppendOnlyLogList {
            val total = base.size.toLong() + batch.size
            check(total <= MAX_CAPACITY) { "log list too large: $total entries" }
            val capacity = maxOf(MIN_CAPACITY.toLong(), total + total / 2).coerceAtMost(MAX_CAPACITY.toLong()).toInt()
            val slots = arrayOfNulls<LogEntry>(capacity)
            var i = 0
            if (base is AppendOnlyLogList) {
                System.arraycopy(base.store.slots, 0, slots, 0, base.size)
                i = base.size
            } else {
                for (entry in base) slots[i++] = entry
            }
            for (entry in batch) slots[i++] = entry
            val store = Store(slots)
            store.committed = i
            return AppendOnlyLogList(store, i)
        }

        fun tryAppendTo(base: AppendOnlyLogList, batch: List<LogEntry>): AppendOnlyLogList? = base.tryAppend(batch)
    }
}

/**
 * Returns [base] followed by [batch] as an immutable list, sharing [base]'s backing array when
 * [base] is the newest view of an [AppendOnlyLogList] store with spare capacity (O(batch)), and
 * copying into a new, larger store otherwise (plain lists, branch appends from an older view,
 * capacity exhausted). [base] is never modified and keeps its own contents. An empty [batch]
 * returns [base] itself.
 */
fun appendLogEntries(base: List<LogEntry>, batch: List<LogEntry>): List<LogEntry> {
    if (batch.isEmpty()) return base
    if (base is AppendOnlyLogList) {
        AppendOnlyLogList.tryAppendTo(base, batch)?.let { return it }
    }
    return AppendOnlyLogList.copyOf(base, batch)
}
