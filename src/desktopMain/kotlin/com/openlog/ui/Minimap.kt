package com.openlog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.openlog.model.Highlighter
import com.openlog.model.LogAnalysis
import com.openlog.model.LogItem
import com.openlog.model.LogLevel
import com.openlog.model.entry
import com.openlog.utils.RegexEvaluationContext
import com.openlog.utils.visibleLogLineText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.BitSet
import kotlin.math.roundToInt

// ── Pure core (no @Composable / composition-dependent APIs — Color/ThemeColors/Highlighter are
// plain data with no UI-thread coupling, so this stays unit-testable without a harness, same split
// as utils/SeqComputer.kt / utils/StackTraceComputer.kt use for their own algorithms). ──

// Distinct hue from every LogLevel color (all of which sit in the red/orange/green/blue family —
// see Model.kt's LogLevel.defaultColor) so a crash row can never be mistaken for a plain ERROR row
// at a glance. Reused from the app's own HL_COLORS palette (Theme.kt), not invented. internal (not
// private) so MinimapTest can assert against it directly instead of duplicating the literal.
internal val CRASH_COLOR = Color(0xFFd946ef)

/** One word/token block within a minimap row's text, as character OFFSETS into that row's own
 *  rendered line (not yet scaled to pixels — see Minimap()'s draw loop for that). Preserving real
 *  offsets rather than repacking them means the blocks drawn from this list reproduce the row's
 *  actual word spacing (including runs of multiple spaces) for free — nothing is drawn in a gap,
 *  so the gap IS the whitespace, no separate "gap width" parameter needed. */
data class MinimapWord(val startChar: Int, val lengthChars: Int)

// A pathological multi-thousand-character line (a huge JSON dump, say) must not cost more to draw
// than the rest of the strip combined — cap how many word blocks any single row contributes.
// internal (not private) so MinimapTest can assert the cap without duplicating the literal.
internal const val MAX_WORDS_PER_ROW = 40

/** Splits [text] into its whitespace-separated word tokens, in order, capped at [maxWords]. */
fun splitIntoWordBlocks(text: String, maxWords: Int = MAX_WORDS_PER_ROW): List<MinimapWord> {
    val words = mutableListOf<MinimapWord>()
    var i = 0
    val n = text.length
    while (i < n && words.size < maxWords) {
        while (i < n && text[i].isWhitespace()) i++
        if (i >= n) break
        val start = i
        while (i < n && !text[i].isWhitespace()) i++
        words.add(MinimapWord(start, i - start))
    }
    return words
}

/** One drawable row of the Sublime-style minimap strip — word-shape blocks ([words], scaled to
 *  pixels by the caller) plus [indent] plus a single dominant [color], ALL taken from one
 *  REPRESENTATIVE item: the FIRST item the row spans, never the most severe one. At real
 *  compression (a row can span ~100 lines) picking the most-severe item made nearly every row
 *  contain a warning or error, so every row painted as "the worst thing nearby" and the texture —
 *  the actual point of a minimap — vanished into a wall of color. */
data class MinimapBar(
    val words: List<MinimapWord>,
    val indent: Int,
    val color: Color,
)

// LogItem.ManualHeader is the one variant with no indent field at all (see model/Model.kt) —
// unlike Row/SeqHeader/StackTraceHeader, manual collapse blocks aren't nesting-aware, so 0 is the
// only sensible value, not a missing case.
private fun LogItem.indentOrZero(): Int = when (this) {
    is LogItem.Row -> indent
    is LogItem.SeqHeader -> indent
    is LogItem.ManualHeader -> 0
    is LogItem.StackTraceHeader -> indent
}

private fun isCrashItem(item: LogItem, crashIds: BitSet): Boolean =
    item is LogItem.StackTraceHeader || crashIds.get(item.entry.id)

// Mirrors LogRow's own color precedence exactly, so the strip's colors correspond to what's
// actually on screen instead of a separate, invented palette:
//   1. Crash / StackTraceHeader — unmissable, overrides everything else.
//   2. Level E/A (error) / W (warn) — the same colors the row's own level badge uses.
//   3. The first enabled, matching highlighter from `highlighters` (tab.filter.highlighters) —
//      reuses hlRanges (LogViewer.kt), the SAME matcher LogRow itself calls from
//      buildFullLineAnnotation, rather than a second one that could drift from it.
//   4. A group/collapse HEADER's own color — SeqHeader.color / ManualHeader.color (StackTraceHeader
//      has none, but it's already handled by crash precedence above). Deliberately NOT
//      LogItem.Row.groupColor: that field is set on every MEMBER row of an expanded sequence/
//      manual-collapse block too, and painting every member the group color made the whole block
//      read as one solid slab on the strip. Only the block's HEADER gets the color; a member row
//      falls through to its own level color or the muted default like any other line.
//   5. Otherwise, [mutedColor] — the same treatment V/D/plain-Info rows get, so the noise floor
//      stays quiet and an actually-colored row means something.
private fun resolveMinimapColor(
    item: LogItem,
    isCrash: Boolean,
    highlighters: List<Highlighter>,
    regexContext: RegexEvaluationContext,
    mutedColor: Color,
): Color {
    if (isCrash) return CRASH_COLOR
    when (item.entry.level) {
        LogLevel.E, LogLevel.A -> return LogLevel.E.defaultColor
        LogLevel.W -> return LogLevel.W.defaultColor
        else -> {}
    }
    if (highlighters.isNotEmpty()) {
        val lineText = visibleLogLineText(item.entry)
        for (hl in highlighters) {
            if (!hl.on || hl.pattern.isBlank()) continue
            if (hlRanges(lineText, hl, regexContext).isNotEmpty()) return hl.color
        }
    }
    val headerColor = when (item) {
        is LogItem.Row -> null
        is LogItem.SeqHeader -> item.color
        is LogItem.ManualHeader -> item.color
        is LogItem.StackTraceHeader -> null
    }
    return headerColor ?: mutedColor
}

/** Maps a display-order item index to the `[0, rowCount)` drawable row it falls in — the forward
 *  half of the pair with [minimapItemIndexOf]. Both floor-divide the same way so a row's own start
 *  index always maps back to that same row (see MinimapTest's round-trip case). */
fun minimapBucketOf(itemIndex: Int, itemCount: Int, rowCount: Int): Int {
    if (itemCount <= 0 || rowCount <= 0) return 0
    return ((itemIndex.toLong() * rowCount) / itemCount).toInt().coerceIn(0, rowCount - 1)
}

/** Inverse direction, for click-to-jump: which item index a click on drawable `row` should scroll
 *  to. Resolves to the FIRST item that row covers (its floor-divide boundary), matching how a
 *  scrollbar jump lands at the top of the destination rather than centering on it. */
fun minimapItemIndexOf(row: Int, itemCount: Int, rowCount: Int): Int {
    if (itemCount <= 0 || rowCount <= 0) return 0
    return ((row.toLong() * itemCount) / rowCount).toInt().coerceIn(0, itemCount - 1)
}

/** Buckets [items] into at most [rowCount] drawable rows and resolves each row's word-shape +
 *  color from its representative (FIRST) item — see [MinimapBar]'s doc. [highlighters] should be
 *  the tab's `filter.highlighters`; matching runs here (via [resolveMinimapColor]) so it happens
 *  exactly once per drawable row, off the UI thread (see Minimap()'s LaunchedEffect) — never in
 *  the draw loop or during composition. */
internal fun computeMinimapBars(
    items: List<LogItem>,
    crashIds: BitSet,
    rowCount: Int,
    highlighters: List<Highlighter>,
    mutedColor: Color,
    regexContext: RegexEvaluationContext = RegexEvaluationContext(),
): List<MinimapBar> {
    if (items.isEmpty() || rowCount <= 0) return emptyList()
    val effectiveRowCount = rowCount.coerceAtMost(items.size)
    val representativeIdx = IntArray(effectiveRowCount) { -1 }
    for (i in items.indices) {
        val b = minimapBucketOf(i, items.size, effectiveRowCount)
        if (representativeIdx[b] < 0) representativeIdx[b] = i
    }
    return List(effectiveRowCount) { b ->
        val item = items[representativeIdx[b]]
        MinimapBar(
            words = splitIntoWordBlocks(visibleLogLineText(item.entry)),
            indent = item.indentOrZero(),
            color = resolveMinimapColor(item, isCrashItem(item, crashIds), highlighters, regexContext, mutedColor),
        )
    }
}

/** Fraction (`[0, 1]`) of the way through the document the LazyColumn has scrolled, used to derive
 *  the miniature's own Sublime-style scroll offset (see [minimapScrollOffsetPx]). 0 when the whole
 *  document already fits in the viewport (nothing to scroll) or [itemCount] is 0. */
fun minimapScrollFraction(firstVisibleItemIndex: Int, visibleItemCount: Int, itemCount: Int): Float {
    if (itemCount <= 0 || itemCount <= visibleItemCount) return 0f
    val maxFirst = (itemCount - visibleItemCount).coerceAtLeast(1)
    return (firstVisibleItemIndex.toFloat() / maxFirst).coerceIn(0f, 1f)
}

/** Sublime-style scroll offset (px) for a miniature taller than the strip: as the document scrolls
 *  from top to bottom, the miniature scrolls from top to bottom too, at [scrollFraction] of its own
 *  scrollable range (`miniatureHeightPx - stripHeightPx`). Zero whenever the miniature already fits
 *  (`miniatureHeightPx <= stripHeightPx`) — which is exactly the "draw it all, no scrolling" case,
 *  so callers don't need a separate branch for it; this returns 0 for it automatically. */
fun minimapScrollOffsetPx(scrollFraction: Float, miniatureHeightPx: Float, stripHeightPx: Float): Float {
    val scrollableRangePx = miniatureHeightPx - stripHeightPx
    if (scrollableRangePx <= 0f) return 0f
    return scrollFraction.coerceIn(0f, 1f) * scrollableRangePx
}

/** On-screen bounds of the minimap's highlighted viewport. Keeping this mapping separate from the
 * Canvas makes the press target and the drawn indicator use exactly the same geometry. */
internal fun minimapViewportBounds(
    firstVisibleItemIndex: Int,
    visibleItemCount: Int,
    itemCount: Int,
    miniatureHeightPx: Float,
    stripHeightPx: Float,
    minViewportHeightPx: Float,
): Pair<Float, Float>? {
    if (itemCount <= 0 || visibleItemCount <= 0 || stripHeightPx <= 0f) return null
    val scrollFraction = minimapScrollFraction(firstVisibleItemIndex, visibleItemCount, itemCount)
    val offsetPx = minimapScrollOffsetPx(scrollFraction, miniatureHeightPx, stripHeightPx)
    val viewportTopInMiniature = (firstVisibleItemIndex.toFloat() / itemCount) * miniatureHeightPx
    val viewportHeightInMiniature = ((visibleItemCount.toFloat() / itemCount) * miniatureHeightPx)
        .coerceAtLeast(minViewportHeightPx)
    val top = (viewportTopInMiniature - offsetPx).coerceIn(0f, stripHeightPx)
    val bottom = (viewportTopInMiniature + viewportHeightInMiniature - offsetPx)
        .coerceIn(0f, stripHeightPx)
    return top to bottom
}

/** Maps a drag distance that began inside the highlighted viewport to the corresponding first
 * visible item. The original index is used for every move, so grabbing the viewport's middle does
 * not make it jump to put that point at its top edge. */
internal fun minimapFirstVisibleIndexForViewportDrag(
    dragStartIndex: Int,
    dragDeltaPx: Float,
    visibleItemCount: Int,
    itemCount: Int,
    miniatureHeightPx: Float,
    stripHeightPx: Float,
    minViewportHeightPx: Float,
): Int {
    val maxFirst = (itemCount - visibleItemCount).coerceAtLeast(0)
    if (maxFirst == 0 || miniatureHeightPx <= 0f || stripHeightPx <= 0f) return 0
    val startBounds = minimapViewportBounds(
        firstVisibleItemIndex = dragStartIndex,
        visibleItemCount = visibleItemCount,
        itemCount = itemCount,
        miniatureHeightPx = miniatureHeightPx,
        stripHeightPx = stripHeightPx,
        minViewportHeightPx = minViewportHeightPx,
    ) ?: return dragStartIndex.coerceIn(0, maxFirst)
    return minimapFirstVisibleIndexForViewportCenter(
        pointerY = (startBounds.first + startBounds.second) / 2f + dragDeltaPx,
        visibleItemCount = visibleItemCount,
        itemCount = itemCount,
        miniatureHeightPx = miniatureHeightPx,
        stripHeightPx = stripHeightPx,
        minViewportHeightPx = minViewportHeightPx,
    )
}

/** Target first visible item for a press outside the viewport. Sublime centers the viewport on
 * that press instead of putting the pointer on its top edge, which keeps click-to-jump predictable
 * regardless of the viewport's height. */
internal fun minimapFirstVisibleIndexForViewportCenter(
    pointerY: Float,
    visibleItemCount: Int,
    itemCount: Int,
    miniatureHeightPx: Float,
    stripHeightPx: Float,
    minViewportHeightPx: Float,
): Int {
    val maxFirst = (itemCount - visibleItemCount).coerceAtLeast(0)
    if (maxFirst == 0 || miniatureHeightPx <= 0f || stripHeightPx <= 0f) return 0
    // This deliberately inverts minimapViewportBounds rather than relying on a simplified ratio.
    // The miniature can scroll and the viewport can be height-clamped, so direct arithmetic drifts
    // away from the rectangle the Canvas actually draws. A binary search is only O(log n) and makes
    // both outside jumps and internal drags use the same exact on-screen coordinate system.
    val targetY = pointerY.coerceIn(0f, stripHeightPx)
    var low = 0
    var high = maxFirst
    var closest = 0
    var closestDistance = Float.POSITIVE_INFINITY
    while (low <= high) {
        val candidate = (low + high) ushr 1
        val bounds = minimapViewportBounds(
            firstVisibleItemIndex = candidate,
            visibleItemCount = visibleItemCount,
            itemCount = itemCount,
            miniatureHeightPx = miniatureHeightPx,
            stripHeightPx = stripHeightPx,
            minViewportHeightPx = minViewportHeightPx,
        ) ?: break
        val center = (bounds.first + bounds.second) / 2f
        val distance = kotlin.math.abs(center - targetY)
        if (distance < closestDistance) {
            closest = candidate
            closestDistance = distance
        }
        if (center < targetY) low = candidate + 1 else high = candidate - 1
    }
    return closest
}

// ── Compose wrapper ──────────────────────────────────────────────────

// Strip width. Rendered beside VerticalScrollbar (see LogViewer.kt's BoxWithConstraints wiring —
// both are shown together, Sublime-style; verticalScrollbarGutterPx there is sized against
// 16.dp + MINIMAP_WIDTH + MINIMAP_CONTENT_GAP, additive, not a max, since both bars are present at
// once). Wide enough (64dp) that the per-line word-shape texture actually reads.
val MINIMAP_WIDTH = 64.dp

// Visual gap between the log content and the minimap+scrollbar cluster — in Sublime the minimap is
// visually separated from the text it summarizes, not butted directly against it. Applied as
// leading space on the Row that holds Minimap+VerticalScrollbar (see LogViewer.kt), only when the
// minimap itself is shown (a bare VerticalScrollbar was never the thing users found cramped).
// internal (not private) so LogViewer.kt can reuse the exact same value for
// verticalScrollbarGutterPx — re-deriving that constant by hand a third time is exactly how it kept
// going stale.
internal val MINIMAP_CONTENT_GAP = 6.dp

// Height of one drawn row. Small and fixed, like Sublime's own minimap rows — this is what lets
// the strip show a full multi-million-line file's texture at a readable per-row scale.
private val BAR_ROW_HEIGHT = 2.dp

// Bounded representation cap for the drawable row count: a multi-million-line file still only ever
// computes/draws at most this many rows (bucketing beyond this point, same as before), keeping the
// off-thread computeMinimapBars pass and the draw loop both bounded regardless of file size. NO
// LONGER tied to strip height (a prior version capped rows at `stripHeightPx / rowHeightPx` so the
// whole file always fit on screen, compressed) — Sublime's own minimap doesn't do that: past this
// many rows the miniature is simply taller than the strip and SCROLLS (see minimapScrollOffsetPx),
// rather than being squeezed to fit. internal (not private) so MinimapTest can assert the cap.
internal const val MINIMAP_MAX_BUCKETS = 2000

// A word block's pixel width is scaled against this many characters = the strip's own fill width
// (see LINE_FILL_FRACTION), NOT against the file's own longest line — one 4000-char dump would
// otherwise squash every other line's words down to invisible slivers. 160 chars is a generous
// single logcat line in practice.
private const val CHAR_REFERENCE_COUNT = 160

// A typical (~160-char) line fills this fraction of the strip width, not the full 100% — leaves a
// hair of margin so word blocks don't visually collide with the strip's own right edge.
private const val LINE_FILL_FRACTION = 0.9f

// Floor so a 1-2 character word is still a visible mark, not a sub-pixel nothing.
private val MIN_WORD_WIDTH = 1.dp

// Per-indent-level left offset, scaled down from LogRow's own INDENT_STEP (18dp — far too coarse
// for a 64dp-wide strip) so nested sequence/fold levels still read as a staircase without eating
// the whole width after a handful of levels. Capped at MAX_INDENT_WIDTH_FRACTION of the strip so a
// pathologically deep nesting level can't collapse a row's drawable width to zero.
private val MINIMAP_INDENT_STEP = 4.dp
private const val MAX_INDENT_WIDTH_FRACTION = 0.7f

private const val VIEWPORT_FILL_ALPHA = 0.08f
private val VIEWPORT_LINE_HEIGHT = 1.5.dp

// Width of the right-click context menu. The strip itself (MINIMAP_WIDTH, 64dp) is far narrower
// than this, so the menu is deliberately positioned opening LEFTWARD from the click (see the
// pointerInput's contextMenuOffset below) rather than rightward off the strip's own edge — there's
// no room to the right of the minimap (it already sits at the window's trailing edge), but plenty
// of room to the left, over the log content.
private val MINIMAP_CONTEXT_MENU_WIDTH = 170.dp

/** Clickable Sublime-style text minimap, rendered BESIDE VerticalScrollbar (not instead of it —
 *  see LogViewer.kt's BoxWithConstraints wiring) when settings.showMinimap is on. [highlighters]
 *  should be the tab's own `filter.highlighters`, so the strip's colors track the same highlighter
 *  rules the log rows themselves are tinted by. */
@Composable
fun Minimap(
    items: List<LogItem>,
    analysis: LogAnalysis,
    highlighters: List<Highlighter>,
    lazyState: LazyListState,
    tc: ThemeColors,
    onHideMinimap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var heightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { BAR_ROW_HEIGHT.toPx() }.coerceAtLeast(1f)
    var contextMenuOpen by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(IntOffset.Zero) }
    // Purely item-count-driven now (not heightPx-driven) — see MINIMAP_MAX_BUCKETS's own doc for
    // why: past this many items the row count stays capped and the miniature scrolls instead of
    // compressing further to fit whatever height happens to be available.
    val rowCount = items.size.coerceAtMost(MINIMAP_MAX_BUCKETS)
    var bars by remember { mutableStateOf<List<MinimapBar>>(emptyList()) }
    val scope = rememberCoroutineScope()

    // Recompute off the UI thread — items can be millions of entries on a large-file tab, the same
    // reason rememberComputedLogItems's largeFileMode path (LogViewer.kt) never walks the full item
    // list synchronously during composition. Highlighter matching (resolveMinimapColor) happens in
    // here too, for the same reason — a few hundred rows' worth of regex/substring matching is fine
    // off-thread, never in the draw loop below. Re-keyed on analysis too: crashSites only appears
    // once the background stack-trace/crash analysis finishes (LogAnalysis.pending), so the strip's
    // crash rows appear the moment that lands, same as row folding already does.
    LaunchedEffect(items, rowCount, analysis, highlighters, tc) {
        bars = if (items.isEmpty() || rowCount <= 0) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                val crashIds = BitSet()
                analysis.crashSites.forEach { crashIds.set(it.entry.id) }
                computeMinimapBars(items, crashIds, rowCount, highlighters, tc.td)
            }
        }
    }

    fun jumpTo(y: Float) {
        val h = heightPx
        if (items.isEmpty() || h <= 0 || rowCount <= 0) return
        val miniatureHeightPx = rowCount * rowHeightPx
        val targetIndex = minimapFirstVisibleIndexForViewportCenter(
            pointerY = y,
            visibleItemCount = lazyState.layoutInfo.visibleItemsInfo.size,
            itemCount = items.size,
            miniatureHeightPx = miniatureHeightPx,
            stripHeightPx = h.toFloat(),
            minViewportHeightPx = rowHeightPx,
        )
        // Immediate (non-animated) jump, so the centered viewport lands precisely under the press.
        scope.launch { lazyState.scrollToItem(targetIndex) }
    }

    // Box wraps the Canvas (rather than the Canvas taking modifier directly) so the context-menu
    // Popup below is anchored at exactly the same origin the pointerInput's own click coordinates
    // are measured against — both are children of this same Box.
    Box(modifier.width(MINIMAP_WIDTH).fillMaxHeight()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .onSizeChanged { heightPx = it.height }
                // Sublime-style viewport behavior: a press inside the highlighted viewport grabs it and
                // only moves it as the pointer moves; a press outside it jumps immediately. A raw
                // pointer loop is intentional here: detectDragGestures waits for touch slop and would
                // make an outside click appear unresponsive.
                .pointerInput(items.size, rowCount) {
                    awaitPointerEventScope {
                        var viewportDragStartY: Float? = null
                        var viewportDragStartIndex = 0
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: continue
                            if (ev.type == PointerEventType.Press && ev.buttons.isSecondaryPressed) {
                                ch.consume()
                                // Opens leftward from the click (see MINIMAP_CONTEXT_MENU_WIDTH's own
                                // doc) — there's no room to the right of this strip, which already sits
                                // at the window's trailing edge.
                                val menuWidthPx = with(density) { MINIMAP_CONTEXT_MENU_WIDTH.toPx() }
                                contextMenuOffset = IntOffset(
                                    (ch.position.x - menuWidthPx).roundToInt(),
                                    ch.position.y.roundToInt(),
                                )
                                contextMenuOpen = true
                            } else if (ev.type == PointerEventType.Press && ev.buttons.isPrimaryPressed) {
                                ch.consume()
                                val miniatureHeightPx = rowCount * rowHeightPx
                                val bounds = minimapViewportBounds(
                                    firstVisibleItemIndex = lazyState.firstVisibleItemIndex,
                                    visibleItemCount = lazyState.layoutInfo.visibleItemsInfo.size,
                                    itemCount = items.size,
                                    miniatureHeightPx = miniatureHeightPx,
                                    stripHeightPx = heightPx.toFloat(),
                                    minViewportHeightPx = rowHeightPx,
                                )
                                if (bounds != null && ch.position.y in bounds.first..bounds.second) {
                                    viewportDragStartY = ch.position.y
                                    viewportDragStartIndex = lazyState.firstVisibleItemIndex
                                } else {
                                    viewportDragStartY = null
                                    jumpTo(ch.position.y)
                                }
                            } else if (ev.type == PointerEventType.Move && ev.buttons.isPrimaryPressed) {
                                ch.consume()
                                val dragStartY = viewportDragStartY
                                if (dragStartY == null) {
                                    jumpTo(ch.position.y)
                                } else {
                                    val targetIndex = minimapFirstVisibleIndexForViewportDrag(
                                        dragStartIndex = viewportDragStartIndex,
                                        dragDeltaPx = ch.position.y - dragStartY,
                                        visibleItemCount = lazyState.layoutInfo.visibleItemsInfo.size,
                                        itemCount = items.size,
                                        miniatureHeightPx = rowCount * rowHeightPx,
                                        stripHeightPx = heightPx.toFloat(),
                                        minViewportHeightPx = rowHeightPx,
                                    )
                                    scope.launch { lazyState.scrollToItem(targetIndex) }
                                }
                            } else if (!ev.buttons.isPrimaryPressed) {
                                viewportDragStartY = null
                            }
                        }
                    }
                },
        ) {
            if (bars.isEmpty()) return@Canvas
            val miniatureHeightPx = bars.size * rowHeightPx
            // Sublime-style scroll: 0 automatically whenever the miniature already fits (see
            // minimapScrollOffsetPx) — no separate "fits" branch needed anywhere below.
            val scrollFraction = minimapScrollFraction(
                lazyState.firstVisibleItemIndex,
                lazyState.layoutInfo.visibleItemsInfo.size,
                items.size,
            )
            val offsetPx = minimapScrollOffsetPx(scrollFraction, miniatureHeightPx, size.height)

            val indentStepPx = MINIMAP_INDENT_STEP.toPx()
            val maxIndentPx = size.width * MAX_INDENT_WIDTH_FRACTION
            val minWordWidthPx = MIN_WORD_WIDTH.toPx()
            val charScalePx = (size.width * LINE_FILL_FRACTION) / CHAR_REFERENCE_COUNT

            for (i in bars.indices) {
                val bar = bars[i]
                val top = i * rowHeightPx - offsetPx
                // Cheap cull: with up to MINIMAP_MAX_BUCKETS (2000) rows this is a minor optimization
                // rather than a necessity, but it's free and avoids issuing draw calls for rows that
                // are entirely scrolled out of the strip's own visible bounds.
                if (top + rowHeightPx < 0f || top > size.height) continue
                val indentPx = (bar.indent * indentStepPx).coerceAtMost(maxIndentPx)
                for (word in bar.words) {
                    val wordStartPx = indentPx + word.startChar * charScalePx
                    // Words are in ascending position order — once one starts past the strip's own
                    // right edge, every later word in this row would too, so stop early.
                    if (wordStartPx >= size.width) break
                    val wordWidthPx = (word.lengthChars * charScalePx)
                        .coerceAtLeast(minWordWidthPx)
                        .coerceAtMost(size.width - wordStartPx)
                    if (wordWidthPx <= 0f) continue
                    drawRect(bar.color, topLeft = Offset(wordStartPx, top), size = Size(wordWidthPx, rowHeightPx))
                }
            }

            // Viewport indicator: outline (top/bottom edge lines + a barely-there fill) rather than a
            // wash, so it marks the current scroll window without hiding the words underneath it. Its
            // position is computed the same way the row offset above is — as a fraction of the
            // MINIATURE's own height, then shifted by the same offsetPx — so the indicator and the
            // content it's pointing at always move together.
            val visibleCount = lazyState.layoutInfo.visibleItemsInfo.size
            if (items.isNotEmpty() && visibleCount > 0) {
                val first = lazyState.firstVisibleItemIndex.coerceIn(0, items.size - 1)
                val viewportBounds = minimapViewportBounds(
                    firstVisibleItemIndex = first,
                    visibleItemCount = visibleCount,
                    itemCount = items.size,
                    miniatureHeightPx = miniatureHeightPx,
                    stripHeightPx = size.height,
                    minViewportHeightPx = rowHeightPx,
                )
                val viewportTop = viewportBounds?.first ?: return@Canvas
                val viewportBottom = viewportBounds.second
                val viewportHeightPx = (viewportBottom - viewportTop).coerceAtLeast(0f)
                if (viewportHeightPx > 0f) {
                    drawRect(
                        tc.ac.copy(alpha = VIEWPORT_FILL_ALPHA),
                        topLeft = Offset(0f, viewportTop),
                        size = Size(size.width, viewportHeightPx),
                    )
                    // DrawScope itself implements Density, so Dp.toPx() resolves directly here — no
                    // separate LocalDensity needed inside the draw block.
                    val lineHeightPx = VIEWPORT_LINE_HEIGHT.toPx().coerceAtMost(viewportHeightPx)
                    drawRect(tc.ac, topLeft = Offset(0f, viewportTop), size = Size(size.width, lineHeightPx))
                    drawRect(
                        tc.ac,
                        topLeft = Offset(0f, (viewportBottom - lineHeightPx).coerceAtLeast(viewportTop)),
                        size = Size(size.width, lineHeightPx),
                    )
                }
            }
        }
        if (contextMenuOpen) {
            MinimapContextMenuPopup(
                onHideMinimap = { contextMenuOpen = false; onHideMinimap() },
                onDismiss = { contextMenuOpen = false },
                offset = contextMenuOffset,
                tc = tc,
            )
        }
    }
}

/** Right-click context menu for the minimap strip, opened at the cursor (see the Canvas's
 *  pointerInput above for where [offset] comes from). Only ever shown while the minimap itself is
 *  visible, so "Hide minimap" is the sole, unconditional action — unlike ToolbarOptionsPopup's
 *  toggle (LogViewer.kt), there's no "Show minimap" state to also represent here. */
@Composable
private fun MinimapContextMenuPopup(
    onHideMinimap: () -> Unit,
    onDismiss: () -> Unit,
    offset: IntOffset,
    tc: ThemeColors,
) {
    Popup(
        alignment = Alignment.TopStart,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(MINIMAP_CONTEXT_MENU_WIDTH)
                .background(tc.p, RoundedCornerShape(7.dp))
                .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                .padding(vertical = 4.dp),
        ) {
            HoverBox(modifier = Modifier.fillMaxWidth(), onClick = onHideMinimap) {
                AppText(
                    "Hide minimap",
                    color = tc.tx,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}
