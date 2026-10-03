package com.indagium.utils

import com.indagium.model.LogEntry
import kotlinx.coroutines.CancellationException

private const val MIN_CAPACITY = 1024

// Keep below the JVM's practical max array length (Integer.MAX_VALUE - 8 on HotSpot).
private const val MAX_CAPACITY = Int.MAX_VALUE - 8

/**
 * The log rows of a tab: an [AppendOnlyList] of [LogEntry]. Kept as an alias because AppState's
 * pin/release bookkeeping and [appendLogEntries] are about the rows specifically.
 */
typealias AppendOnlyLogList = AppendOnlyList<LogEntry>

/**
 * An immutable [List] view over a growable backing array that successive views share, so that
 * appending a batch to a long list costs O(batch) instead of copying every existing element.
 *
 * Why: a live capture appends a ~1 s batch to a tab's `logData` (and to the computed item list the
 * viewer renders from it) thousands of times. `list + batch` copies the whole reference array each
 * time; at 1.3M rows that is a 5 MB humongous allocation per second, which made G1 grow the
 * committed heap to ~6 GB for ~330 MB of live data.
 *
 * A view is `(store, size)`. The store holds the slots plus a `committed` count of how many slots
 * have ever been written. [appendLogEntries] / [appendMapped] extend in place only when the base
 * view is the newest one (`size == committed`) and the store has room; otherwise they copy into a
 * fresh store. An older view therefore never observes elements appended after it was created (it
 * never reads an index >= its own `size`), and two views that diverge (a "branch" append from an
 * older view) end up on different stores.
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
 * is referenced. [release] drops the slots explicitly for the one case where that is not good enough:
 * a closed capture tab whose old `tabs` value can stay reachable from an invalid Compose snapshot
 * record that nothing reuses until much later (see [release]). ONLY the rows store (`logData`) is
 * ever released: an item list built with [appendMapped] must never be, because its exception is a
 * [CancellationException] and would be thrown inside composition.
 *
 * Extends [AbstractList], so `equals`/`hashCode`/`subList` keep standard list semantics (`equals`
 * compares sizes first, so differently-sized views are unequal in O(1), and two views of the same
 * store and size are equal in O(1) without touching an element). [iterator] reads the slot array
 * once instead of per element.
 */
class AppendOnlyList<T : Any> private constructor(
    private val store: Store,
    override val size: Int,
) : AbstractList<T>(), RandomAccess {
    private class Store(slots: Array<Any?>) {
        // Volatile: [release] swaps in an empty array while lock-free readers are mid-iteration.
        @Volatile
        var slots: Array<Any?> = slots

        /** Number of leading slots written so far. Guarded by `synchronized(this)`. */
        var committed: Int = 0

        // Written under `synchronized(this)` (before `slots` is swapped, see [release]) but volatile
        // because [get] reads it without the lock: a reader that sees the emptied array must also see
        // this flag, so it reports a release rather than a corrupt "unwritten slot".
        @Volatile
        var released: Boolean = false
    }

    /** Test hook: two views return the same object iff they share a backing store. */
    internal val storeIdentity: Any get() = store

    /** Test hook: slot count of the backing store (0 once released). */
    internal val capacity: Int get() = store.slots.size

    /** Test hook: whether [release] has been called on this view's store. */
    internal val isReleased: Boolean get() = store.released

    /**
     * Drops the backing array of this view's store, and with it every element only it referenced.
     * Idempotent. EVERY view of the store (this one and any older or branched-from-here sibling)
     * becomes unreadable: [get] and anything built on it (iteration, `equals` against a different
     * list, `hashCode`) throws [ReleasedLogListException] from then on, and appends no longer share
     * the store. [size] is a plain `val` and keeps reporting the pre-release size.
     *
     * Why this exists: once a capture tab closes nothing of ours should reach its rows, but Compose
     * can keep the pre-close `tabs` list alive in an invalid snapshot record until a later write
     * happens to reuse that record, and an idle app never does. Releasing the store makes such a
     * record pin a small `LogTab` instead of the whole capture.
     *
     * Callers must only release a store that no live tab (and no reader that cannot tolerate the
     * exception) uses; the AppState close path checks that under `stateLock`. NEVER call this on a
     * computed item list ([appendMapped]): the viewer reads those during composition.
     */
    fun release() {
        synchronized(store) {
            // Flag first: see Store.released for why a reader of the empty array must see it.
            store.released = true
            store.slots = arrayOfNulls(0)
            store.committed = 0
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun get(index: Int): T {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("index: $index, size: $size")
        val slots = store.slots
        // `index < size <= slots.size` for a live store; a released one swapped in an empty array.
        val element = if (index < slots.size) slots[index] else null
        if (element != null) return element as T
        throw unreadableSlot(index)
    }

    // Reads the slot array once (one volatile read) instead of per element: the hot full-list loops
    // (summary, id sets, ...) iterate millions of items. A store released mid-iteration keeps
    // serving the array captured here, which is still fully populated; one released before the
    // iterator was created reports the release on the first element, exactly like [get].
    override fun iterator(): Iterator<T> = object : Iterator<T> {
        private val slots = store.slots
        private var next = 0

        override fun hasNext(): Boolean = next < size

        @Suppress("UNCHECKED_CAST")
        override fun next(): T {
            val i = next
            if (i >= size) throw NoSuchElementException()
            val element = if (i < slots.size) slots[i] else null
            if (element == null) throw unreadableSlot(i)
            next = i + 1
            return element as T
        }
    }

    private fun unreadableSlot(index: Int): Throwable =
        if (store.released) ReleasedLogListException() else IllegalStateException("unwritten slot $index below size $size")

    // Two views over the same store and size are identical in content by construction, so the
    // common "is this the same snapshot" question (Compose key comparison between consecutive
    // lists) never degrades to an element-by-element compare. Anything else is the standard List
    // contract: equal to any List with the same elements in the same order (an ArrayList too).
    override fun equals(other: Any?): Boolean =
        if (other is AppendOnlyList<*> && other.store === store && other.size == size) true else super.equals(other)

    override fun hashCode(): Int = super.hashCode()

    /**
     * In-place append of [count] elements written by [fill] (called with the slot array and the
     * index of the first free slot, under the store's monitor), or null when this view is not the
     * newest of its store or it has no room. [fill] must be a cheap, non-reentrant element writer:
     * it runs while the store is locked.
     */
    private fun tryAppend(count: Int, fill: (Array<Any?>, Int) -> Unit): AppendOnlyList<T>? = synchronized(store) {
        if (store.released) return null
        val slots = store.slots
        if (size != store.committed || count > slots.size - size) return null
        fill(slots, size)
        val newSize = size + count
        store.committed = newSize
        AppendOnlyList(store, newSize)
    }

    internal companion object {
        /**
         * [base] followed by [count] elements written by [fill]: in place when [base] is the newest
         * view of an [AppendOnlyList] store with room (O(count)), a copy into a new store otherwise.
         */
        fun <T : Any> appendFilled(base: List<T>, count: Int, fill: (Array<Any?>, Int) -> Unit): AppendOnlyList<T> {
            if (base is AppendOnlyList<T>) base.tryAppend(count, fill)?.let { return it }
            return copyFilled(base, count, fill)
        }

        private fun <T : Any> copyFilled(base: List<T>, count: Int, fill: (Array<Any?>, Int) -> Unit): AppendOnlyList<T> {
            val total = base.size.toLong() + count
            check(total <= MAX_CAPACITY) { "list too large: $total entries" }
            val capacity = maxOf(MIN_CAPACITY.toLong(), total + total / 2).coerceAtMost(MAX_CAPACITY.toLong()).toInt()
            val slots = arrayOfNulls<Any>(capacity)
            var i = 0
            if (base is AppendOnlyList<T>) {
                // Read the array BEFORE the flag: a released store always shows the flag once its
                // empty array is visible, so an empty `source` can never reach arraycopy below.
                val source = base.store.slots
                if (base.store.released) throw ReleasedLogListException()
                System.arraycopy(source, 0, slots, 0, base.size)
                i = base.size
            } else {
                for (element in base) slots[i++] = element
            }
            fill(slots, i)
            val store = Store(slots)
            store.committed = i + count
            return AppendOnlyList(store, i + count)
        }
    }
}

/**
 * Returns [base] followed by [batch] as an immutable list, sharing [base]'s backing array when
 * [base] is the newest view of an [AppendOnlyList] store with spare capacity (O(batch)), and
 * copying into a new, larger store otherwise (plain lists, branch appends from an older view,
 * capacity exhausted). [base] is never modified and keeps its own contents. An empty [batch]
 * returns [base] itself.
 */
fun appendLogEntries(base: List<LogEntry>, batch: List<LogEntry>): List<LogEntry> {
    if (batch.isEmpty()) return base
    return AppendOnlyList.appendFilled(base, batch.size) { slots, start ->
        var i = start
        for (entry in batch) slots[i++] = entry
    }
}

/**
 * [base] followed by `map(e)` for every `e` in [src], with the same sharing rules as
 * [appendLogEntries]: written straight into [base]'s store in O(src) when [base] is the newest
 * view of one with room, copied into a fresh store otherwise (a plain-list [base] is converted
 * once). Unlike [appendLogEntries] the result is always an [AppendOnlyList], even for an empty
 * [src] with a plain [base]; an empty [src] with an [AppendOnlyList] [base] returns [base] itself.
 * [map] runs under the store's lock on the in-place path, so it must be cheap and must not touch
 * the list being built.
 */
fun <S : Any, T : Any> appendMapped(base: List<S>, src: List<T>, map: (T) -> S): AppendOnlyList<S> {
    if (src.isEmpty() && base is AppendOnlyList<S>) return base
    return AppendOnlyList.appendFilled(base, src.size) { slots, start ->
        var i = start
        for (element in src) slots[i++] = map(element)
    }
}

/**
 * Thrown when an element of an [AppendOnlyList] whose store was [released][AppendOnlyList.release]
 * is read. It is a [CancellationException] on purpose: the only readers still running after a tab
 * closes are background jobs that were (or should have been) cancelled with it, and a coroutine that
 * ends with a CancellationException finishes quietly. `AppState.ioScope` has no exception handler,
 * so any other type escaping such a job would reach the uncaught-exception handler as a crash-style
 * error for what is just a late reader of a closed tab.
 */
class ReleasedLogListException : CancellationException("log rows were released after their tab closed")

/**
 * True when [current] is [snapshot] with rows appended (identical, or a strictly longer append-only
 * continuation), in O(1). Tailing only ever appends and entry ids strictly increase, so comparing
 * the snapshot's last row by identity with the same index of [current] is sufficient; it also works
 * across backing-store regrowth, where the two lists no longer share a store. An empty [snapshot]
 * is never considered extended.
 */
internal fun extendsSnapshot(current: List<LogEntry>, snapshot: List<LogEntry>): Boolean =
    snapshot.isNotEmpty() && current.size >= snapshot.size &&
        current[snapshot.size - 1] === snapshot[snapshot.size - 1]
