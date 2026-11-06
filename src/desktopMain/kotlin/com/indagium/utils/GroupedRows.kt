package com.indagium.utils

import androidx.compose.ui.graphics.Color
import com.indagium.model.LogEntry
import com.indagium.model.LogItem
import java.util.RandomAccess

/** In-memory grouped rendering without retaining a separate Row object for every entry. */
internal class GroupedRows private constructor(
    private val items: Array<Any>,
    private val starts: IntArray,
    private val styles: Array<Style>,
) : AbstractList<LogItem>(), RandomAccess {
    private data class Style(val indent: Int, val color: Color?, val scopedColor: Color?)

    override val size: Int get() = items.size

    override fun get(index: Int): LogItem {
        val item = items[index]
        if (item is LogItem) return item
        val pos = starts.binarySearch(index).let { if (it >= 0) it else -it - 2 }
        val style = styles[pos]
        return LogItem.Row(item as LogEntry, style.indent, style.color, style.scopedColor)
    }

    internal class Builder(capacity: Int = 0) {
        private val items = ArrayList<Any>(capacity)
        private val starts = ArrayList<Int>()
        private val styles = ArrayList<Style>()

        fun row(entry: LogEntry, indent: Int, color: Color?, scopedColor: Color?) {
            val previous = styles.lastOrNull()
            if (previous == null || previous.indent != indent || previous.color != color || previous.scopedColor != scopedColor) {
                starts.add(items.size)
                styles.add(Style(indent, color, scopedColor))
            }
            items.add(entry)
        }

        fun header(item: LogItem) {
            require(item !is LogItem.Row)
            items.add(item)
        }

        fun add(item: LogItem) {
            if (item is LogItem.Row) row(item.entry, item.indent, item.groupColor, item.scopedSeqColor) else header(item)
        }

        fun finish(): GroupedRows = GroupedRows(items.toTypedArray(), starts.toIntArray(), styles.toTypedArray())
    }
}
