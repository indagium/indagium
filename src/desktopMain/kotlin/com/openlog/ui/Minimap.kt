package com.openlog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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
//   4. A group/collapse color — LogItem.Row.groupColor, or the header variants' own `color` field
//      (StackTraceHeader has none, but it's already handled by crash precedence above).
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
    val groupColor = when (item) {
        is LogItem.Row -> item.groupColor
        is LogItem.SeqHeader -> item.color
        is LogItem.ManualHeader -> item.color
        is LogItem.StackTraceHeader -> null
    }
    return groupColor ?: mutedColor
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

// ── Compose wrapper ──────────────────────────────────────────────────

// Strip width. Rendered beside VerticalScrollbar (see LogViewer.kt's BoxWithConstraints wiring —
// both are shown together, Sublime-style; verticalScrollbarGutterPx there is sized against
// 16.dp + MINIMAP_WIDTH, additive, not a max, since both bars are present at once). Wide enough
// (64dp) that the per-line word-shape texture actually reads.
val MINIMAP_WIDTH = 64.dp

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
    modifier: Modifier = Modifier,
) {
    var heightPx by remember { mutableStateOf(0) }
    val rowHeightPx = with(LocalDensity.current) { BAR_ROW_HEIGHT.toPx() }.coerceAtLeast(1f)
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
        val scrollFraction = minimapScrollFraction(
            lazyState.firstVisibleItemIndex,
            lazyState.layoutInfo.visibleItemsInfo.size,
            items.size,
        )
        val offsetPx = minimapScrollOffsetPx(scrollFraction, miniatureHeightPx, h.toFloat())
        // The click lands in ON-SCREEN strip coordinates; adding back the scroll offset converts
        // it into a position within the (possibly taller-than-the-strip) miniature before dividing
        // into a row — see minimapScrollOffsetPx's doc and MinimapTest's dedicated coverage of this
        // exact mapping (getting the sign/order wrong here sends jumps to the wrong place).
        val row = ((y + offsetPx) / rowHeightPx).toInt().coerceIn(0, rowCount - 1)
        val targetIndex = minimapItemIndexOf(row, items.size, rowCount)
        // Immediate (non-animated) jump, same idiom as scrollForCursor/centerOnItem elsewhere in
        // LogViewer.kt: animateScrollToItem takes several frames to settle, and a minimap click is
        // a "land exactly here now" gesture, not something that benefits from an eased scroll.
        scope.launch { lazyState.scrollToItem(targetIndex) }
    }

    Canvas(
        modifier
            .width(MINIMAP_WIDTH)
            .fillMaxHeight()
            .onSizeChanged { heightPx = it.height }
            // Click-or-drag-to-jump: a bare Press already jumps (a "click"), and continuing to hold
            // while moving keeps jumping to wherever the pointer now is (a "drag"). Deliberately a
            // raw awaitPointerEventScope loop rather than detectDragGestures — detectDragGestures
            // only fires after touch-slop movement, which would silently swallow a plain
            // click-without-moving.
            .pointerInput(items.size, rowCount) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: continue
                        if (
                            ev.buttons.isPrimaryPressed &&
                            (ev.type == PointerEventType.Press || ev.type == PointerEventType.Move)
                        ) {
                            ch.consume()
                            jumpTo(ch.position.y)
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
            val viewportTopInMiniature = (first.toFloat() / items.size) * miniatureHeightPx
            val viewportHeightInMiniature = ((visibleCount.toFloat() / items.size) * miniatureHeightPx)
                .coerceAtLeast(rowHeightPx)
            val viewportTop = (viewportTopInMiniature - offsetPx).coerceIn(0f, size.height)
            val viewportBottom = (viewportTopInMiniature + viewportHeightInMiniature - offsetPx)
                .coerceIn(0f, size.height)
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
}
