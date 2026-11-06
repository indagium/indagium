package com.indagium.utils

/**
 * A read-only `Set<Int>` that keeps its elements in the order given and answers [contains] from a
 * bit set, with no per-element boxing: a 10M-row Select All costs a 40 MB IntArray plus ~1.2 MB of
 * bits instead of ~500 MB of boxed Integers and LinkedHashSet nodes. Use [of] to build one.
 *
 * Behaves like the `LinkedHashSet` that `list.toSet()` returns: duplicates are dropped (first
 * occurrence wins), iteration follows the given order, and equals/hashCode follow the `Set`
 * contract so it compares equal to any other set with the same elements.
 */
internal class OrderedIntSet private constructor(
    private val ids: IntArray,
    private val base: Int,
    private val bits: java.util.BitSet,
) : AbstractSet<Int>() {
    override val size: Int get() = ids.size

    override fun contains(element: Int): Boolean {
        val offset = element.toLong() - base
        return offset >= 0 && offset <= Int.MAX_VALUE && bits.get(offset.toInt())
    }

    override fun iterator(): Iterator<Int> = object : Iterator<Int> {
        private var next = 0

        override fun hasNext(): Boolean = next < ids.size

        override fun next(): Int {
            if (next >= ids.size) throw NoSuchElementException()
            return ids[next++]
        }
    }

    companion object {
        // Bits are stored relative to the smallest id, so a short range deep into a big file stays
        // small. A set whose ids are too spread out for that falls back to a plain LinkedHashSet.
        private const val SPARSE_SLACK_BITS = 4096L
        private const val SPARSE_BITS_PER_ID = 64L

        /** The distinct ids of `source[from until toExclusive]` in that order, as a `Set<Int>`. */
        fun of(source: IntArray, from: Int = 0, toExclusive: Int = source.size): Set<Int> {
            require(from in 0..toExclusive && toExclusive <= source.size) { "bad range $from..$toExclusive of ${source.size}" }
            val count = toExclusive - from
            if (count == 0) return emptySet()
            var min = source[from]
            var max = min
            for (i in from + 1 until toExclusive) {
                val id = source[i]
                if (id < min) min = id
                if (id > max) max = id
            }
            val span = max.toLong() - min
            if (span > count * SPARSE_BITS_PER_ID + SPARSE_SLACK_BITS || span >= Int.MAX_VALUE) {
                return LinkedHashSet<Int>(count * 2).also { set -> for (i in from until toExclusive) set.add(source[i]) }
            }
            val bits = java.util.BitSet(span.toInt() + 1)
            val distinct = IntArray(count)
            var n = 0
            for (i in from until toExclusive) {
                val id = source[i]
                val offset = id - min
                if (!bits.get(offset)) {
                    bits.set(offset)
                    distinct[n++] = id
                }
            }
            return OrderedIntSet(if (n == count) distinct else distinct.copyOf(n), min, bits)
        }
    }
}
