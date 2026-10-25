package com.indagium.ui

import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import kotlin.math.max
import kotlin.math.min

// How the tiles of a scrolling column are sized, pure (TileLayout.kt's geometry and resize call it). Two ways, chosen per column:
//
// - Legacy (no tile of the column has a TileSizing): a child's sizeDp is its height (at least scrollMinDp), weighted children share what the
//   pinned ones leave exactly as a fitting column does (minTileDp each at the least), and when everything is pinned and shorter than the
//   viewport the last open tile takes the rest.
// - Sized (at least one leaf has a TileSizing, i.e. its body reported what its content needs): a tile is as tall as its content wants.
//   A stored sizeDp is clamped to [min, max] at layout time (never rewritten); an unpinned tile with a default takes it; an unpinned "fill"
//   tile (default null: Notes, AI) and any child without a TileSizing share what is left by weight, each within its bounds. A column that
//   ends up shorter than its viewport leaves the rest empty, except that the last open tile may grow into it up to its max.

/** Least height (dp) of a tile's flex part that a resize leaves, about one row (never more than the part's natural height). */
const val TILE_FLEX_MIN_DP: Float = 40f

/** Height (dp) a flex part's natural content is capped at for a tile's default height when its body names no cap of its own. */
const val TILE_FLEX_DEFAULT_CAP_DP: Float = 240f

private const val TILE_SIZING_EPSILON_DP: Float = 0.5f

/**
 * What a tile's content asks of its OUTER height (header included) in a scrolling column: at least [minDp] and at most [maxDp] (equal for a
 * tile with no flex part: it cannot be resized), [defaultDp] while it has no stored size, or null for a "fill" tile that shares what the
 * column leaves by weight.
 */
data class TileSizing(val minDp: Float, val maxDp: Float, val defaultDp: Float?) {
    /** [maxDp], never below [minDp]. */
    val hiDp: Float get() = max(minDp, maxDp)

    /** False when the tile has no room to grow or shrink (nothing to drag). */
    val resizable: Boolean get() = hiDp - minDp > TILE_SIZING_EPSILON_DP

    fun clamp(dp: Float): Float = dp.coerceIn(minDp, hiDp)
}

/**
 * The heights [sizes] of a scrolling column's children and what the plan knows about each child: [collapsed] (header only), [pinned]
 * (a stored sizeDp is honoured), [sizing] (the leaf's TileSizing, null when unknown) and [dependent] (its height depends on its siblings, so
 * the first resize pins it at the height it has).
 */
internal class TileScrollPlan(
    val sizes: List<Float>,
    val collapsed: List<Boolean>,
    val pinned: List<Boolean>,
    val sizing: List<TileSizing?>,
    val dependent: List<Boolean>,
)

// The pinned size, or null when the node is weighted (absent or not a positive finite number).
internal fun <K> TileNode<K>.pinnedDp(): Float? = sizeDp?.takeIf { it.isFinite() && it > 0f }

internal fun <K> scrollPlan(
    split: TileSplit<K>,
    extent: Float,
    minTileDp: Float,
    scrollMinDp: Float,
    collapsedExtentDp: Float,
    collapsedSet: Set<K>,
    sizingOf: (K) -> TileSizing? = { null },
): TileScrollPlan {
    val children = split.children
    val isCollapsed = children.map { it is TileLeaf && it.section in collapsedSet }
    val isPinned = children.mapIndexed { i, c -> !isCollapsed[i] && c.pinnedDp() != null }
    val sizing = children.mapIndexed { i, c -> if (isCollapsed[i]) null else (c as? TileLeaf<K>)?.let { sizingOf(it.section) } }
    return if (sizing.any { it != null }) {
        sizedPlan(split, extent, minTileDp, scrollMinDp, collapsedExtentDp, isCollapsed, isPinned, sizing)
    } else {
        legacyPlan(split, extent, minTileDp, scrollMinDp, collapsedExtentDp, isCollapsed, isPinned)
    }
}

private fun <K> legacyPlan(
    split: TileSplit<K>,
    extent: Float,
    minTileDp: Float,
    scrollMinDp: Float,
    collapsedExtentDp: Float,
    isCollapsed: List<Boolean>,
    isPinned: List<Boolean>,
): TileScrollPlan {
    val children = split.children

    fun fixedSize(i: Int): Float = if (isCollapsed[i]) collapsedExtentDp else max(children[i].pinnedDp()!!, scrollMinDp)
    val fixed = children.indices.filter { isCollapsed[it] || isPinned[it] }.sumOf { fixedSize(it).toDouble() }.toFloat()
    val weighted = children.indices.filter { !isCollapsed[it] && !isPinned[it] }
    val weightSum = weighted.sumOf { children[it].weight.toDouble() }.toFloat()
    // The weighted tiles share what is left exactly as a fitting column shares it, unless the fixed ones leave less than minTileDp each:
    // then they get that much between them and the column is taller than its viewport (and scrolls) instead of squeezing the fixed ones.
    val weightedExtent = max(max(0f, extent - fixed), weighted.size * minTileDp)
    val sizes = children.indices.map { i ->
        when {
            isCollapsed[i] || isPinned[i] -> fixedSize(i)
            weightSum > 0f -> weightedExtent * children[i].weight / weightSum
            else -> weightedExtent / weighted.size
        }
    }.toMutableList()
    if (weighted.isEmpty()) {
        // Everything is pinned or collapsed: when that is shorter than the viewport the last open tile takes the rest.
        val slack = extent - sizes.sumOf { it.toDouble() }.toFloat()
        val last = children.indices.lastOrNull { !isCollapsed[it] }
        if (slack > 0f && last != null) sizes[last] = sizes[last] + slack
    }
    return TileScrollPlan(sizes, isCollapsed, isPinned, children.map { null }, children.indices.map { !isCollapsed[it] && !isPinned[it] })
}

@Suppress("LongParameterList")
private fun <K> sizedPlan(
    split: TileSplit<K>,
    extent: Float,
    minTileDp: Float,
    scrollMinDp: Float,
    collapsedExtentDp: Float,
    isCollapsed: List<Boolean>,
    isPinned: List<Boolean>,
    sizing: List<TileSizing?>,
): TileScrollPlan {
    val children = split.children
    val sizes = MutableList(children.size) { 0f }
    val sharing = mutableListOf<Int>()
    children.indices.forEach { i ->
        val settled = settledHeight(children[i], sizing[i], isCollapsed[i], isPinned[i], collapsedExtentDp, scrollMinDp)
        if (settled != null) sizes[i] = settled else sharing += i
    }
    val settledSum = children.indices.filter { it !in sharing }.sumOf { sizes[it].toDouble() }.toFloat()
    val shared = shareByWeight(
        sharing,
        max(0f, extent - settledSum),
        weight = { children[it].weight },
        lo = { sizing[it]?.minDp ?: minTileDp },
        hi = { sizing[it]?.hiDp ?: Float.MAX_VALUE },
    )
    shared.forEach { (i, dp) -> sizes[i] = dp }
    // The last open tile may take what is left, up to its max, when it has a default of its own (a stored size is respected as it is).
    val receiver = children.indices.lastOrNull { !isCollapsed[it] }?.takeIf { !isPinned[it] && canGrowBeyondDefault(sizing[it]) }
    if (receiver != null) {
        val slack = extent - sizes.sumOf { it.toDouble() }.toFloat()
        if (slack > 0f) sizes[receiver] = min(sizes[receiver] + slack, sizing[receiver]!!.hiDp)
    }
    val dependent = children.indices.map { i -> !isCollapsed[i] && !isPinned[i] && (i in sharing || i == receiver) }
    return TileScrollPlan(sizes, isCollapsed, isPinned, sizing, dependent)
}

// A tile's height when it does not depend on its siblings, or null when it shares what is left (a "fill" tile, or one that was not measured).
@Suppress("LongParameterList")
private fun <K> settledHeight(
    child: TileNode<K>,
    sizing: TileSizing?,
    collapsed: Boolean,
    pinned: Boolean,
    collapsedExtentDp: Float,
    scrollMinDp: Float,
): Float? {
    val stored = child.pinnedDp()
    return when {
        collapsed -> collapsedExtentDp
        sizing != null && pinned -> sizing.clamp(stored!!)
        sizing != null -> sizing.defaultDp?.let { sizing.clamp(it) }
        pinned -> max(stored!!, scrollMinDp)
        else -> null
    }
}

private fun canGrowBeyondDefault(sizing: TileSizing?): Boolean {
    val default = sizing?.defaultDp ?: return false
    return sizing.hiDp - default > TILE_SIZING_EPSILON_DP
}

// Shares [total] among [items] by weight, each within [lo, hi]: a tile that would get less than its minimum is held at it, then (once none
// is below) a tile that would get more than its maximum is held at that, and the rest share what remains. May return more than [total]
// when the minimums do not fit, which makes the column scroll.
private fun shareByWeight(items: List<Int>, total: Float, weight: (Int) -> Float, lo: (Int) -> Float, hi: (Int) -> Float): Map<Int, Float> {
    val out = HashMap<Int, Float>()
    var active = items
    var left = total
    while (active.isNotEmpty()) {
        val sum = active.sumOf { weight(it).toDouble() }.toFloat()

        fun share(i: Int): Float = if (sum > 0f) left * weight(i) / sum else left / active.size
        val held = active.filter { share(it) < lo(it) }.associateWith { lo(it) }
            .ifEmpty { active.filter { share(it) > hi(it) }.associateWith { hi(it) } }
        if (held.isEmpty()) {
            active.forEach { out[it] = share(it) }
            break
        }
        out += held
        left = max(0f, left - held.values.sum())
        active = active.filter { it !in held }
    }
    return out
}
