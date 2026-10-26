package com.indagium.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.max

// The wrapping main toolbar (MainToolbar.kt's FLOW shape): a row of button chunks that wraps between groups, never inside one. A chunk is a
// short run of buttons that stands together (the panel toggles, Compare and Cases, Open with its arrow); Layout, Settings and New are chunks
// of one button. The bar draws the toggles, Compare / Cases and Open as ONE joined group, so while that group fits a line it is placed
// without gaps and its buttons take the group's end corners; when it does not, each chunk is a group of its own with gaps between and its own
// end corners. A chunk wider than the room (labels on, a very narrow tile) is split into its buttons, each standing alone, so nothing is ever
// clipped. The shapes are the buttons' business (they read [ToolbarFlowState], which the layout writes), the placing is the layout's.

/**
 * What the flow layout tells the buttons: [joined] is true while the toolbar's joined group (its chunks 1..3) is laid out as one run, [split]
 * holds the chunks too wide for a line, whose buttons are then laid out one by one.
 */
internal class ToolbarFlowState {
    var joined by mutableStateOf(true)
    var split by mutableStateOf(emptySet<Int>())
}

/** The layout id of a chunk of the wrapping toolbar (a cell of a split chunk carries its [key]). */
private data class ToolbarChunkId(val index: Int, val key: String)

// Set while a split chunk is composed: its cells then place themselves as layout children of their own instead of inside the chunk's row.
private val LocalSplitChunk = compositionLocalOf<Int?> { null }

/** One chunk of the wrapping toolbar: its buttons in a row tagged with [index] for [ToolbarGroupFlow], or, when [split], the buttons by themselves. */
@Composable
internal fun ToolbarChunk(index: Int, split: Boolean, content: @Composable () -> Unit) {
    if (split) {
        CompositionLocalProvider(LocalSplitChunk provides index) { content() }
    } else {
        Row(Modifier.layoutId(ToolbarChunkId(index, ""))) { content() }
    }
}

/** One button of a chunk: a layout child of its own while the chunk is split ([key] tells it from its neighbours), otherwise just its content. */
@Composable
internal fun ToolbarCell(key: String, content: @Composable () -> Unit) {
    val chunk = LocalSplitChunk.current
    if (chunk != null) Row(Modifier.layoutId(ToolbarChunkId(chunk, key))) { content() } else content()
}

// The chunks that make up the bar's one joined group.
private val JOINED_CHUNKS = 1..3

/** The [gap] between the units of a line and between lines. */
@Composable
internal fun ToolbarGroupFlow(state: ToolbarFlowState, modifier: Modifier, gap: Dp, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val limit = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        // Natural widths: a chunk is measured without a limit, so one that does not fit shows as wider than the room.
        val items = measurables.mapNotNull { m ->
            val id = m.layoutId as? ToolbarChunkId ?: return@mapNotNull null
            val placeable = m.measure(Constraints())
            (id.index to placeable).takeIf { placeable.width > 0 }
        }
        val byChunk = LinkedHashMap<Int, MutableList<Placeable>>()
        items.forEach { (index, placeable) -> byChunk.getOrPut(index) { mutableListOf() } += placeable }
        val tooWide = byChunk.filter { (_, cells) -> cells.sumOf { it.width } + gapPx * (cells.size - 1) > limit }.keys
        if (tooWide != state.split) state.split = tooWide
        val groupWidth = byChunk.filterKeys { it in JOINED_CHUNKS }.values.sumOf { cells -> cells.sumOf { it.width } }
        val joined = groupWidth <= limit && byChunk.keys.none { it in JOINED_CHUNKS && it in tooWide }
        if (state.joined != joined) state.joined = joined
        // The units that wrap as a whole: the joined group in one piece, a split chunk's buttons one by one, every other chunk alone.
        val units = mutableListOf<List<Placeable>>()
        var group: MutableList<Placeable>? = null
        byChunk.forEach { (index, cells) ->
            when {
                index in tooWide -> cells.forEach { units += listOf(it) }
                joined && index in JOINED_CHUNKS -> {
                    val run = group ?: mutableListOf<Placeable>().also { group = it; units += it }
                    run += cells
                }
                else -> units += cells
            }
        }
        val lines = mutableListOf<MutableList<List<Placeable>>>()
        var lineWidth = 0
        units.forEach { unit ->
            val width = unit.sumOf { it.width }
            val current = lines.lastOrNull()
            if (current == null || lineWidth + gapPx + width > limit) {
                lines += mutableListOf(unit)
                lineWidth = width
            } else {
                current += unit
                lineWidth += gapPx + width
            }
        }
        val lineHeights = lines.map { line -> line.maxOf { unit -> unit.maxOf { it.height } } }
        val height = lineHeights.sum() + gapPx * max(0, lines.size - 1)
        // As wide as its widest line (like a FlowRow), not the whole room.
        val widest = lines.maxOfOrNull { l -> l.sumOf { u -> u.sumOf { it.width } } + gapPx * (l.size - 1) } ?: 0
        layout(widest.coerceIn(constraints.minWidth, limit), height.coerceAtLeast(constraints.minHeight)) {
            var y = 0
            lines.forEachIndexed { row, line ->
                var x = 0
                line.forEach { unit ->
                    unit.forEach { placeable ->
                        placeable.place(x, y + (lineHeights[row] - placeable.height) / 2)
                        x += placeable.width
                    }
                    x += gapPx
                }
                y += lineHeights[row] + gapPx
            }
        }
    }
}
