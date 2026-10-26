package com.indagium.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// What a tile's body tells the tiling surface about its height, so a side section is exactly as tall as its content wants (a scrolling column,
// TileLayout.kt) instead of being handed a share of the window with the rest left empty.
//
// A body is a FIXED part (always shown in full: inputs, segmented controls, editors) and at most one FLEX part, a scrollable list marked with
// [FlexRegion]. A slot declares which it is with [TileBodySizing]; the card then measures the body at its natural height and reports the
// fixed part and the flex part's natural height ([TileBodyMeasure]), from which the surface derives the tile's [TileSizing] (bounds and
// default height). The surface hands the height it decided on back to the region ([TileFlexHost.allottedDp]), which is how resizing a tile
// changes only its flex part. Outside a scrolling column nothing of this is active: a region is then the bounded box it always was.

/** How a tile's body takes part in the height of a scrolling column. */
internal enum class TileBodySizing {
    /** Not measured: the tile gets a stored or weighted height (the log, Video, the toolbar, the New tab's sections). */
    FILL,

    /** The body is fixed content only: the tile is exactly header + body and cannot be resized. */
    CONTENT,

    /** The body is a fixed part plus (at most) one [FlexRegion]; resizing the tile changes the region's height only. */
    FLEX,
}

/**
 * What a card measured of its body at the card's width, in dp: the [fixedDp] part (everything but the flex region, always shown), the flex
 * part's natural height [flexNaturalDp] (0 without one) and its default cap [flexCapDp] (null = no cap: the tile shares the column by weight).
 * [chromeDp] is the header and its divider.
 */
internal data class TileBodyMeasure(
    val mode: TileBodySizing,
    val chromeDp: Float,
    val fixedDp: Float = 0f,
    val flexNaturalDp: Float = 0f,
    val flexCapDp: Float? = null,
) {
    /**
     * The bounds and default of the tile's outer height, [maxDp] being the most a tile may ever be (null for a [TileBodySizing.FILL] body). A flex
     * tile resizes between its least (the fixed part and about a row of the list, or all of a shorter list) and [maxDp], whatever its list holds
     * now: the list may be empty today and fill later, and extra room the user chose shows as empty list area. Only the height it has before any
     * resize stays content based: the fixed part plus the list at its natural height (to the cap), which is also as far as leftover room stretches it.
     */
    fun toSizing(maxDp: Float): TileSizing? = when (mode) {
        TileBodySizing.FILL -> null
        TileBodySizing.CONTENT -> (chromeDp + fixedDp).let { TileSizing(it, it, it) }
        TileBodySizing.FLEX -> {
            val base = chromeDp + fixedDp
            val low = base + min(flexNaturalDp, TILE_FLEX_MIN_DP)
            val fit = min(base + flexNaturalDp, maxDp).coerceAtLeast(low)
            TileSizing(low, max(low, maxDp), flexCapDp?.let { (base + min(flexNaturalDp, it)).coerceIn(low, fit) }, fit)
        }
    }
}

private const val MEASURE_EPSILON_DP = 0.5f

/** The latest [TileBodyMeasure] of each tile of one surface (snapshot state: the surface re-lays out when one changes). */
internal class TileMeasureStore<K> {
    private val measures = mutableStateMapOf<K, TileBodyMeasure>()

    operator fun get(id: K): TileBodyMeasure? = measures[id]

    /** Applies [change] to the measure of [id] (starting from [initial]) and keeps the result only when it differs by more than half a dp. */
    fun edit(id: K, initial: TileBodyMeasure, change: (TileBodyMeasure) -> TileBodyMeasure) {
        // Read without observation: this runs inside layout, which must not be re-run by the changes of other tiles.
        val before = Snapshot.withoutReadObservation { measures[id] }
        val after = change(before ?: initial)
        if (before == null || !before.closeTo(after)) measures[id] = after
    }

    fun sizingOf(id: K, maxDp: Float): TileSizing? = measures[id]?.toSizing(maxDp)
}

private fun TileBodyMeasure.closeTo(other: TileBodyMeasure): Boolean =
    mode == other.mode && flexCapDp == other.flexCapDp &&
        abs(chromeDp - other.chromeDp) < MEASURE_EPSILON_DP && abs(fixedDp - other.fixedDp) < MEASURE_EPSILON_DP &&
        abs(flexNaturalDp - other.flexNaturalDp) < MEASURE_EPSILON_DP

/**
 * The link between a card and the [FlexRegion] in its body. The card sets [allottedDp] (the height it wants the region to show) and reads
 * what the region measured; [onFlex] receives the region's natural height and cap.
 */
internal class TileFlexHost {
    var allottedDp by mutableStateOf<Float?>(null)

    /** The region's height as last laid out, px (0 without a region); the card subtracts it from the body's height to get the fixed part. */
    var shownPx: Int = 0
    var onFlex: (naturalDp: Float, capDp: Float?) -> Unit = { _, _ -> }

    fun regionGone() {
        shownPx = 0
        onFlex(0f, null)
    }
}

/** Set by a card whose body is measured by its content (a scrolling column), null elsewhere. */
internal val LocalTileFlex = compositionLocalOf<TileFlexHost?> { null }

/**
 * The one scrollable part of a [TileBodySizing.FLEX] body. [scrollState] is the state of the vertical scroll the content holds (its
 * `maxValue` gives the natural height: the content must scroll with exactly this state, in a column that is NOT `fillMaxSize`, and keep any
 * scrollbar in a `matchParentSize()` box so it does not claim the whole height). In a tile of a scrolling column the region is as tall as the
 * surface allots, whatever its content: the list scrolls when it is longer, and when it is shorter (a tile the user made taller than its list) the rest of
 * the region is empty; elsewhere it is capped at [capDp], or, with `capDp == null`, takes [fill] (e.g.
 * `Modifier.weight(1f)`), which is what Notes and the AI conversation have always done. [capDp] is also the region's share of the tile's
 * default height; null means the tile shares the column by weight. [modifier] applies in every case.
 */
@Composable
internal fun FlexRegion(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    capDp: Int? = TILE_FLEX_DEFAULT_CAP_DP.roundToInt(),
    fill: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val host = LocalTileFlex.current
    DisposableEffect(host) { onDispose { host?.regionGone() } }
    val sizing = when {
        host != null -> Modifier.fillMaxWidth().flexHeight(host, scrollState, capDp)
        capDp != null -> Modifier.fillMaxWidth().heightIn(max = capDp.dp)
        else -> fill
    }
    // The region is the tile's one scrolling list: a list inside it shows all its rows instead of scrolling (and drawing a scrollbar) of its own.
    CompositionLocalProvider(LocalFlatLists provides LocalInTile.current) {
        Box(modifier.then(sizing), content = content)
    }
}

// The region's height: what the surface allots, else its cap, and never more than its content (the box wraps it) until the surface allots more:
// a tile the user made taller than its list leaves the list at the top and the rest of the region empty (so a composer under an empty
// conversation stays at the bottom of the tile). Reports the natural height, which is the content's, not the allotment's.
private fun Modifier.flexHeight(host: TileFlexHost, scroll: ScrollState, capDp: Int?): Modifier = layout { measurable, constraints ->
    val allotted = host.allottedDp
    val limitPx = min(constraints.maxHeight, ((allotted ?: capDp?.toFloat() ?: TILE_FLEX_DEFAULT_CAP_DP) * density).roundToInt())
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = limitPx))
    val hidden = scroll.maxValue.takeIf { it != Int.MAX_VALUE } ?: 0
    val height = if (allotted != null) max(placeable.height, limitPx) else placeable.height
    host.shownPx = height
    host.onFlex((placeable.height + hidden) / density, capDp?.toFloat())
    layout(placeable.width, height) { placeable.place(0, 0) }
}

/**
 * A [FlexRegion] around a plain scrolling column with the app scrollbar: the bounded list of the filter sections (what `BoundedScrollBoxDp`
 * was). The region is at most [capDp] tall by default and as tall as its content when that is less.
 */
@Composable
internal fun FlexScrollBox(
    capDp: Int,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    FlexRegion(scrollState, modifier, capDp) {
        Column(Modifier.fillMaxWidth().verticalScroll(scrollState), content = content)
        // In a matchParentSize box so the scrollbar's fillMaxHeight does not pin the region to its cap.
        Box(Modifier.matchParentSize()) {
            ThinVerticalScrollbar(scrollState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/**
 * A [FlexRegion] with nothing in it: the flex part of a body whose list is empty at the moment. In a tile of a scrolling column it takes
 * whatever height the surface allots, so a tile made taller than its content leaves the room here and the fixed parts after the list (an
 * input, a form) sit at the bottom of the tile, exactly as they do once the list has rows; elsewhere it is no height at all.
 */
@Composable
internal fun EmptyFlexRegion(modifier: Modifier = Modifier) {
    FlexRegion(rememberScrollState(), modifier, capDp = 0) {}
}

/**
 * A tile body's column: scrolls (with the app scrollbar) while the tile gives it a fixed height, and is just a column that wraps its content
 * when the tile is sized by its content ([TileBodyInfo.contentSized]), so the card measures it at its natural height. One call site for both,
 * so a tile dragged between a scrolling column and another place keeps its composition.
 */
@Composable
internal fun TileBodyColumn(info: TileBodyInfo, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scrolling = !info.contentSized
    val state = rememberScrollState()
    // While the body scrolls as a whole, its scrollbar gets a strip of its own (only while it overflows: a body that fits keeps its full width),
    // so it never lies over the scrollbar of a flex list inside the body. ScrollState.maxValue is Int.MAX_VALUE until the first layout.
    val overflowing = scrolling && state.maxValue in 1 until Int.MAX_VALUE
    Box((if (scrolling) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).then(modifier)) {
        Column(
            Modifier.fillMaxWidth().then(if (scrolling) Modifier.verticalScroll(state) else Modifier)
                .then(if (overflowing) Modifier.padding(end = TILE_SCROLL_GUTTER_DP.dp) else Modifier),
            content = content,
        )
        if (scrolling) {
            Box(Modifier.matchParentSize()) {
                ThinVerticalScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}
