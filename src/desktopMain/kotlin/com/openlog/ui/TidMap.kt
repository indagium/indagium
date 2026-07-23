package com.openlog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openlog.model.LogItem
import com.openlog.model.TidMapState
import com.openlog.utils.TidMapBranch
import com.openlog.utils.computeTidMapBranches
import com.openlog.utils.findTidMapSpan
import com.openlog.utils.tidMapHighlightedEntryRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Sharp (90°) corners, not rounded — an earlier rounded-elbow version (quarter-circle arcs, a
// mirrored first-row cap) turned out not to render as visibly rounded at the row heights this
// actually draws at, so the geometry was simplified to plain straight lines: the spine (a
// continuous vertical drawLine, below) and each row's own horizontal branch (also a plain
// drawLine) simply cross at a right angle. No arc math, no mirroring, no separate path-building
// function needed — two straight lines crossing at a point already IS a sharp corner.
private val TID_MAP_BRANCH_LENGTH = 14.dp
private val TID_MAP_STROKE_WIDTH = 1.6.dp
private val TID_MAP_STROKE_WIDTH_HIGHLIGHTED = 2.6.dp

// A click-a-branch highlight dims everything NOT sharing the clicked color, rather than hiding it —
// same "full opacity for the matching group, faded for the rest" idiom used elsewhere in this app
// for "make one thing stand out without losing the others from view" (the entire point of a tid map
// over filtering in the first place).
private const val DIMMED_ALPHA = 0.25f

// Total width the gutter's own drawing + click target occupies. NOT private: LogRow, the header row
// variants, and ColHeader all reserve exactly this much leading width for the gutter (see
// LogViewer.kt's tidMapSpineX and Components.kt's ColHeader) — one shared constant so the row's own
// content, the header's column labels, and this overlay's drawing/click-region can never drift out
// of alignment with each other the way the old measured-offset approach could.
val TID_MAP_HIT_WIDTH = TID_MAP_BRANCH_LENGTH + 4.dp

// Off-thread computation result — see computeTidMap's own doc for why this is never built
// synchronously in composition. firstEntryId/lastEntryId are the TRUE span boundaries (may or may
// not be among the rows currently laid out/visible; see TidMapOverlay's draw loop for how that's
// handled) — kept separately from branchesByEntryId's keys so "is this the true first/last row"
// stays an O(1) check instead of a linear scan.
//
// Deliberately does NOT carry a colors map of its own — colors are computed exactly once, from the
// tab's full logData, by AppState.toggleTidMap, and stored in TidMapState.colors (see its own doc
// for why: two panels computing "first appearance order" from two different filtered item lists
// used to assign the SAME pid two DIFFERENT colors). This per-panel computation only ever produces
// span/branch geometry, which genuinely is panel-specific (see TidMapOverlay's own doc).
private class TidMapComputed(
    val firstEntryId: Int,
    val lastEntryId: Int,
    val branchesByEntryId: Map<Int, TidMapBranch>,
    // First/last entryId per colorKey (thread), across the FULL span — not just whatever's
    // currently laid out. Lets the click-a-branch highlight overlay extend to the canvas edge the
    // same way the base spine does (see TidMapOverlay's own doc on that split) instead of always
    // stopping dead at whatever two branches of that color happen to be on screen right now.
    val firstLastEntryIdByColorKey: Map<Int, Pair<Int, Int>>,
)

private fun computeTidMap(items: List<LogItem>, tidMap: TidMapState): TidMapComputed? {
    val span = findTidMapSpan(items, tidMap.target) ?: return null
    val branches = computeTidMapBranches(items, span, tidMap.target)
    if (branches.isEmpty()) return null
    return TidMapComputed(
        firstEntryId = branches.first().entryId,
        lastEntryId = branches.last().entryId,
        branchesByEntryId = branches.associateBy { it.entryId },
        firstLastEntryIdByColorKey = branches.groupBy { it.colorKey }
            .mapValues { (_, group) -> group.first().entryId to group.last().entryId },
    )
}

/** The tid-map gutter overlay — a vertical spine plus one sharp-cornered branch per row belonging
 *  to the target process, drawn in a fixed-width LEADING column before the timestamp (see
 *  LogViewer.kt's tidMapSpineX, a plain reserved-width offset — no text measurement involved, see
 *  Theme.kt's own note on why that approach was dropped). One instance per panel (Original/Filtered
 *  get their own — see LogViewer.kt's two call sites), so [items]/[rowBoundsAbs] are always that
 *  panel's own, and the span this draws is naturally independent per panel.
 *
 *  [rowBoundsAbs] and [contentTopY] are the SAME absolute-Y bookkeeping LogViewer.kt already
 *  maintains for row hit-testing (drag-select) — reused here rather than a second position-tracking
 *  mechanism. [contentTopY] converts an absolute row Y (rowBoundsAbs's own coordinate space) into
 *  this Canvas's local coordinate space (which starts at the content Box's own top-left, the same
 *  Box the LazyColumn lives in).
 *
 *  Only rows currently present in [rowBoundsAbs] (i.e. currently composed/laid out by the
 *  LazyColumn, a window bounded by the viewport regardless of how large the true span is) are ever
 *  drawn — same reasoning as ui/Minimap.kt's own bucket cap: a span of thousands of rows must not
 *  mean thousands of draw calls at once.
 *
 *  Colors are read straight from [tidMap]`.colors` — NOT computed here. This overlay only computes
 *  panel-local geometry (which rows are in the span, which are on screen, where each branch's
 *  crossing point lands); the pid→Color assignment itself is computed once, from the tab's full
 *  logData, by AppState.toggleTidMap, so it's identical between this panel and the other one (see
 *  TidMapState.colors's own doc for the bug that shared assignment fixes). */
@Composable
fun TidMapOverlay(
    tidMap: TidMapState,
    items: List<LogItem>,
    rowBoundsAbs: HashMap<Int, Pair<Float, Float>>,
    contentTopY: Float,
    spineOffsetX: Dp,
    tc: ThemeColors,
    onHighlightChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var computed by remember(tidMap.target) { mutableStateOf<TidMapComputed?>(null) }

    // Off-thread, same pattern ui/Minimap.kt established (LaunchedEffect + Dispatchers.Default into
    // a mutableStateOf) — a span can cover hundreds or thousands of rows on a busy thread in a huge
    // file, so this must never run synchronously during composition. Re-keyed on `items` (this
    // panel's own current filtered/folded list — a filter edit can change what "first/last visible"
    // means) and `tidMap.target` (a new map replaces the old one wholesale).
    LaunchedEffect(items, tidMap.target) {
        computed = withContext(Dispatchers.Default) { computeTidMap(items, tidMap) }
    }

    val snapshot = computed ?: return
    val highlighted = tidMap.highlightedColorKey

    Canvas(
        modifier
            .offset(x = spineOffsetX)
            .width(TID_MAP_HIT_WIDTH)
            .fillMaxHeight()
            .pointerInput(rowBoundsAbs, snapshot, contentTopY) {
                awaitPointerEventScope {
                    // Single `if`, no `continue`/`break` — detekt's LoopWithTooManyJumpStatements
                    // flags loops with multiple jump statements as hard to follow; folding every
                    // "not a real click on a branch" case into one guarded block (rather than a
                    // chain of early `continue`s) says the same thing without tripping it.
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull()
                        val isPrimaryClick = ev.type == PointerEventType.Press && ev.buttons.isPrimaryPressed
                        if (ch != null && isPrimaryClick) {
                            val absY = contentTopY + ch.position.y
                            val hitEntryId = rowBoundsAbs.entries
                                .firstOrNull { (_, bounds) -> absY >= bounds.first && absY < bounds.second }
                                ?.key
                            val branch = hitEntryId?.let { snapshot.branchesByEntryId[it] }
                            if (branch != null) {
                                ch.consume()
                                onHighlightChange(if (highlighted == branch.colorKey) null else branch.colorKey)
                            }
                        }
                    }
                }
            },
    ) {
        // spineX is intentionally NOT 0f: the Canvas's own origin (this composable's `.offset(x =
        // spineOffsetX)`) already lands on a whole physical pixel (Modifier.offset rounds dp→px at
        // placement), so a plain vertical line drawn AT local x=0 sits exactly ON a pixel-column
        // boundary — a straight stroke centered on a boundary anti-aliases as a 50/50 split across
        // the two neighbouring columns, reading visibly thinner/fainter than a stroke that isn't
        // sitting on that seam. Nudging by half a pixel re-centers the spine WITHIN one column
        // instead of ON its edge, the standard fix for this exact anti-aliasing artifact — each
        // branch starts at this same spineX, so spine and branch still cross at exactly one point.
        val spineX = 0.5f
        // The spine represents the SPECIFIC thread that was right-clicked to open this map — read
        // straight from tidMap.target.tid, not "whichever branch happens to be first" (usually the
        // same row, but not guaranteed to be: the target's own first VISIBLE occurrence and the
        // first-appearance-order the color assignment walked are two different orderings).
        val spineColor = tidMap.colors[tidMap.target.tid] ?: tc.td
        val branchLengthPx = TID_MAP_BRANCH_LENGTH.toPx()
        val strokeWidthPx = TID_MAP_STROKE_WIDTH.toPx()
        val strokeWidthHighlightedPx = TID_MAP_STROKE_WIDTH_HIGHLIGHTED.toPx()

        // Only rows currently laid out (present in rowBoundsAbs) AND within the span are drawable —
        // sorted by their actual on-screen Y so the spine's own extent (below) is computed from
        // whatever's topmost/bottommost right now, not span order (which could differ if a filter
        // ever reordered rows — it doesn't today, but this doesn't assume it never will).
        val visible = rowBoundsAbs.entries
            .mapNotNull { (entryId, bounds) -> snapshot.branchesByEntryId[entryId]?.let { Triple(entryId, it, bounds) } }
            .sortedBy { it.third.first }
        if (visible.isEmpty()) return@Canvas

        fun colorFor(branch: TidMapBranch): Color {
            val base = tidMap.colors[branch.colorKey] ?: tc.td
            return if (highlighted != null && highlighted != branch.colorKey) base.copy(alpha = DIMMED_ALPHA) else base
        }

        // Every Y this function draws at is clamped into [0, size.height] — LazyColumn composes a
        // small overscan buffer just beyond the actual viewport (for smooth-scroll prefetch), so a
        // row can be present in `visible` with a centerY that's a few px negative or past
        // size.height. Canvas draw calls are NOT auto-clipped to the composable's own bounds, so an
        // unclamped line/tick at one of those positions bleeds into whatever sits above/below this
        // panel (the column header, the section banner, the next split-view panel) instead of
        // stopping at this gutter's own edge — this was the reported "thread line overlaps some UI
        // elements" bug.
        fun clampY(y: Float) = y.coerceIn(0f, size.height)

        // Spine's drawn vertical extent: at the TRUE first/last row (when currently visible), it
        // stops EXACTLY at that row's own centerY — a sharp corner needs no radius to reserve room
        // for, unlike the earlier rounded version — "no overshoot" per the shape spec. When the true
        // boundary row isn't currently laid out (the span extends further than what's on screen
        // right now), the spine simply runs to this canvas's own edge instead, implying continuation
        // off-screen rather than falsely terminating mid-span.
        val topEntry = visible.first()
        val bottomEntry = visible.last()
        val topCenterY = (topEntry.third.first + topEntry.third.second) / 2f - contentTopY
        val bottomCenterY = (bottomEntry.third.first + bottomEntry.third.second) / 2f - contentTopY
        val spineTopY = clampY(if (topEntry.first == snapshot.firstEntryId) topCenterY else 0f)
        val spineBottomY = clampY(if (bottomEntry.first == snapshot.lastEntryId) bottomCenterY else size.height)
        if (spineBottomY > spineTopY) {
            drawLine(
                spineColor,
                start = Offset(spineX, spineTopY),
                end = Offset(spineX, spineBottomY),
                strokeWidth = strokeWidthPx,
            )
        }

        // Ticks only ever drawn for rows that actually land ON screen (within the clamped range) —
        // a row sitting in the overscan buffer gets no tick of its own; it still counts toward the
        // spine's own top/bottom-entry detection above via the un-filtered `visible` list, but
        // drawing ITS tick would be the same off-canvas bleed clampY guards the spine against.
        for ((_, branch, bounds) in visible) {
            val centerY = (bounds.first + bounds.second) / 2f - contentTopY
            if (centerY < 0f || centerY > size.height) continue
            val color = colorFor(branch)
            val isHighlighted = highlighted != null && highlighted == branch.colorKey
            drawLine(
                color,
                start = Offset(spineX, centerY),
                end = Offset(spineX + branchLengthPx, centerY),
                strokeWidth = if (isHighlighted) strokeWidthHighlightedPx else strokeWidthPx,
            )
        }

        // Feature: while a color group is highlighted, the spine ALSO switches to that color, but
        // only across the sub-range from the first to the last branch sharing it — same "true
        // boundary vs. currently-laid-out edge" split the base spine above uses (via
        // firstLastEntryIdByColorKey, this thread's own analogue of firstEntryId/lastEntryId), not
        // just whatever two same-colored branches happen to be on screen right now. An earlier
        // version always stopped at the visible pair, which read as a floating block detached from
        // the panel's top/bottom edge instead of a segment of the one continuous spine. Drawn LAST
        // (i.e. on top of the base spine above) so it visibly overrides just that portion rather
        // than being occluded by it.
        if (highlighted != null) {
            val range = tidMapHighlightedEntryRange(visible.map { it.second }, highlighted)
            if (range != null) {
                val (firstId, lastId) = range
                val trueRange = snapshot.firstLastEntryIdByColorKey[highlighted]
                val firstBounds = visible.first { it.first == firstId }.third
                val lastBounds = visible.first { it.first == lastId }.third
                val firstCenterY = (firstBounds.first + firstBounds.second) / 2f - contentTopY
                val lastCenterY = (lastBounds.first + lastBounds.second) / 2f - contentTopY
                val firstY = clampY(if (trueRange != null && firstId == trueRange.first) firstCenterY else 0f)
                val lastY = clampY(if (trueRange != null && lastId == trueRange.second) lastCenterY else size.height)
                if (lastY > firstY) {
                    drawLine(
                        tidMap.colors[highlighted] ?: tc.td,
                        start = Offset(spineX, firstY),
                        end = Offset(spineX, lastY),
                        strokeWidth = strokeWidthHighlightedPx,
                    )
                }
            }
        }
    }
}
