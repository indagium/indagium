@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.openlog.model.*
import com.openlog.utils.RegexEvaluationContext
import com.openlog.utils.computeItems
import com.openlog.utils.deltaAnchorId
import com.openlog.utils.deltaMillis
import com.openlog.utils.formatDelta
import com.openlog.utils.formatSignedDelta
import com.openlog.utils.passesFilter
import com.openlog.utils.regexRanges
import com.openlog.utils.visibleLogLineText
import com.openlog.utils.widestAnchorDeltaMagnitudeMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.coroutines.coroutineContext
import java.awt.Cursor as AwtCursor

private const val PAGE_JUMP_ROWS = 15
private const val CTX_MENU_KEYBOARD_X_DP = 60f
private const val LOADING_GRACE_MS = 250L
private const val SPLICE_SUMMARY_GUARD_MIN_ITEMS = 4096
private const val DOUBLE_CLICK_WINDOW_MS = 500L

// Δt gutter cell (LogRow's deltaMs param) tints itself this color when the gap since the previous
// visible row is at least this long — a fixed v1 threshold; a user-configurable one is out of scope
// (see the plan's "Out of scope" list).
private const val DELTA_WARN_THRESHOLD_MS = 1000L

// DANGER_RED is only ever assigned as a LogItem.Row's groupColor for expanded crash/stack-trace
// group members (see Filter.kt's computeItems — sequence/manual-collapse groupColors always come
// from a different palette). By default those rows only get a thin left-edge stripe, while the
// group's own header gets a full red background+text tint; the highlightEntireCrashGroup setting
// extends that full tint to every row in the group, not just the header.
internal fun isCrashGroupRow(groupColor: Color?, highlightEntireCrashGroup: Boolean): Boolean =
    highlightEntireCrashGroup && groupColor == DANGER_RED

// internal (not private): reused by ui/Minimap.kt's off-thread color resolution so the minimap's
// "does this row match a highlighter" check is the exact same logic LogRow itself uses, not a
// second matcher that could silently drift from it.
internal fun hlRanges(
    msg: String,
    hl: Highlighter,
    regexContext: RegexEvaluationContext,
): List<Pair<Int, Int>> =
    if (hl.regex) {
        regexRanges(msg, hl.pattern, regexContext = regexContext)
    } else {
        buildList {
            var i = 0
            while (true) {
                val idx = msg.indexOf(hl.pattern, i, ignoreCase = true)
                if (idx < 0) break
                add(idx to idx + hl.pattern.length); i = idx + 1
            }
        }
    }

internal fun keywordRegexHighlightRanges(
    lineText: String,
    filter: Filter,
    regexContext: RegexEvaluationContext = RegexEvaluationContext(),
): List<Pair<Int, Int>> =
    if (
        filter.mode == FilterMode.KEYWORD &&
        filter.kwRegex &&
        filter.kwText.isNotBlank() &&
        filter.kwHighlightEnabled
    ) {
        regexRanges(lineText, filter.kwText, regexContext = regexContext)
    } else {
        emptyList()
    }

// Derived once per item-list computation (off the UI thread for large tabs) so recompositions
// never pay an O(n) pass over millions of items: entry ids in display order (drag-select and
// navigation), row-only ids (select-all), a BitSet for pruning stale row bounds, and the
// expand/collapse counts the toolbar buttons need.
class ItemsSummary(
    val allIds: IntArray,
    val rowIds: IntArray,
    val idBits: java.util.BitSet,
    val collapsedGroupCount: Int,
    val expandedGroupCount: Int,
) {
    val rowCount: Int get() = rowIds.size

    // Every header (Seq/Manual/StackTrace) is itself one real log entry rendered in header style,
    // whether its group is collapsed or expanded — rowCount alone (Row items only, kept row-only
    // for select-all) undercounts the "entries currently shown" label by exactly the header count.
    val visibleEntryCount: Int get() = rowCount + collapsedGroupCount + expandedGroupCount
}

// O(1) dense-guess fast path / O(log n) binary-search fallback for an entry id's position within
// a strictly-ascending-by-id IntArray (P-05) — the same technique utils/EntryIdMap.kt already
// uses for id->LogEntry over the raw, unfiltered logData. Valid here for the same reason:
// allIds/rowIds are built by walking `items` in display order (summarizeItems/spliceSummarize
// above), which preserves the original file's ascending-id order even after filtering/folding —
// it just isn't necessarily dense (filtering/folding can remove ids), so the dense guess is a
// cheap opportunistic check, not a guarantee; binary search is what actually does the work.
// Keyboard navigation and drag-selection used to re-scan the full array with .indexOf/
// .indexOfFirst on every keypress/pointer-move instead of using this.
internal fun IntArray.indexOfId(id: Int): Int {
    if (isEmpty()) return -1
    val guess = id - this[0]
    if (guess in indices && this[guess] == id) return guess
    var lo = 0
    var hi = lastIndex
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        when {
            this[mid] < id -> lo = mid + 1
            this[mid] > id -> hi = mid - 1
            else -> return mid
        }
    }
    return -1
}

internal fun summarizeItems(items: List<LogItem>): ItemsSummary {
    val allIds = IntArray(items.size)
    val idBits = java.util.BitSet()
    var rows = 0
    var collapsed = 0
    var expanded = 0
    items.forEachIndexed { i, item ->
        val id = logItemEntryId(item)
        allIds[i] = id
        idBits.set(id)
        when (item) {
            is LogItem.Row -> rows++
            is LogItem.SeqHeader -> if (item.expanded) expanded++ else collapsed++
            is LogItem.ManualHeader -> if (item.expanded) expanded++ else collapsed++
            is LogItem.StackTraceHeader -> if (item.expanded) expanded++ else collapsed++
        }
    }
    val rowIds = IntArray(rows)
    var r = 0
    items.forEach { item -> if (item is LogItem.Row) rowIds[r++] = item.entry.id }
    return ItemsSummary(allIds, rowIds, idBits, collapsed, expanded)
}

// Splice-aware summary update. computeItems' stack-toggle fast path returns a list sharing
// object identity with the previous one outside a single contiguous window; when that holds,
// the summary arrays rebuild via arraycopy of the unchanged regions plus a walk of just the
// window — instead of the full O(n) object walk of summarizeItems, which had become the
// dominant per-toggle cost (~300ms at 10M items) once computeItems itself was spliced.
// Returns null (caller does the full summarize) whenever the shape doesn't hold: first compute,
// full rebuilds (fresh objects everywhere), or a window too large to be worth it.
// Branchy by nature: head/tail identity scans plus per-array splicing in one place IS the
// optimization — factoring it apart would re-walk the lists it exists to avoid walking.
@Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
internal fun spliceSummarize(
    oldItems: List<LogItem>,
    oldSummary: ItemsSummary,
    newItems: List<LogItem>,
): ItemsSummary? {
    val oldN = oldItems.size
    val newN = newItems.size
    if (oldN == 0 || newN == 0 || oldSummary.allIds.size != oldN) return null
    var head = 0
    var headRows = 0
    val maxHead = minOf(oldN, newN)
    while (head < maxHead && oldItems[head] === newItems[head]) {
        if (oldItems[head] is LogItem.Row) headRows++
        head++
    }
    if (head == oldN && head == newN) return oldSummary
    var tail = 0
    var tailRows = 0
    val maxTail = minOf(oldN, newN) - head
    while (tail < maxTail && oldItems[oldN - 1 - tail] === newItems[newN - 1 - tail]) {
        if (oldItems[oldN - 1 - tail] is LogItem.Row) tailRows++
        tail++
    }
    // Large changed window on a large list: the clone+clear/set overhead beats a clean full
    // summarize, so bail. Small lists always splice — either way is microseconds there, and it
    // keeps the equivalence tests exercising this path.
    if (newN > SPLICE_SUMMARY_GUARD_MIN_ITEMS && newN - head - tail > newN / 4) return null

    val allIds = IntArray(newN)
    System.arraycopy(oldSummary.allIds, 0, allIds, 0, head)
    System.arraycopy(oldSummary.allIds, oldN - tail, allIds, newN - tail, tail)
    val idBits = oldSummary.idBits.clone() as java.util.BitSet
    var collapsed = oldSummary.collapsedGroupCount
    var expanded = oldSummary.expandedGroupCount

    fun headerDelta(item: LogItem, sign: Int) {
        when (item) {
            is LogItem.Row -> {} // row counts are derived from the window row-id list below
            is LogItem.SeqHeader -> if (item.expanded) expanded += sign else collapsed += sign
            is LogItem.ManualHeader -> if (item.expanded) expanded += sign else collapsed += sign
            is LogItem.StackTraceHeader -> if (item.expanded) expanded += sign else collapsed += sign
        }
    }

    for (i in head until oldN - tail) {
        val item = oldItems[i]
        idBits.clear(logItemEntryId(item))
        headerDelta(item, -1)
    }
    val windowRowIds = ArrayList<Int>(newN - head - tail)
    for (i in head until newN - tail) {
        val item = newItems[i]
        val id = logItemEntryId(item)
        allIds[i] = id
        idBits.set(id)
        headerDelta(item, +1)
        if (item is LogItem.Row) windowRowIds.add(id)
    }

    val oldWindowRows = oldSummary.rowCount - headRows - tailRows
    val rowCount = oldSummary.rowCount - oldWindowRows + windowRowIds.size
    val rowIds = IntArray(rowCount)
    System.arraycopy(oldSummary.rowIds, 0, rowIds, 0, headRows)
    windowRowIds.forEachIndexed { k, id -> rowIds[headRows + k] = id }
    System.arraycopy(oldSummary.rowIds, oldSummary.rowCount - tailRows, rowIds, rowCount - tailRows, tailRows)
    return ItemsSummary(allIds, rowIds, idBits, collapsed, expanded)
}

// Plain class on purpose: instances serve as remember/LaunchedEffect keys, where identity
// comparison is O(1) but a data-class equals would deep-compare millions of items.
internal class ComputedLogItems(
    val items: List<LogItem>,
    val summary: ItemsSummary,
    val loading: Boolean,
)

private val EMPTY_SUMMARY = summarizeItems(emptyList())

@Composable
private fun rememberComputedLogItems(tab: LogTab, applyFilter: Boolean): ComputedLogItems {
    val dataSize = tab.logData.size
    val lastId = tab.logData.lastOrNull()?.id
    val filter = tab.filter
    val expanded = tab.expanded
    val manualBlocks = tab.manualBlocks
    // analysis is a key so folding appears once the deferred background analysis lands
    // (tab.analysis flips from pending to full after a large-file load).
    val analysis = tab.analysis
    if (!tab.largeFileMode) {
        return remember(tab.id, dataSize, lastId, filter, expanded, manualBlocks, analysis, applyFilter) {
            val items = computeItems(tab, applyFilter)
            ComputedLogItems(items, summarizeItems(items), loading = false)
        }
    }

    var computed by remember(tab.id, applyFilter) {
        mutableStateOf(ComputedLogItems(emptyList(), EMPTY_SUMMARY, loading = true))
    }
    LaunchedEffect(tab.id, dataSize, lastId, filter, expanded, manualBlocks, analysis, applyFilter) {
        val snapshot = tab.copy(selected = emptySet())
        val previous = computed
        coroutineScope {
            val deferred = async(Dispatchers.Default) {
                // P-01: without this, a superseded computation (this LaunchedEffect's own
                // coroutineScope already gets cancelled the instant a newer filter/expand/etc.
                // change lands — see the LaunchedEffect keys above) keeps running to full
                // completion on its thread instead of actually stopping, wasting CPU under rapid
                // filter edits even though the result was always going to be thrown away.
                val items = computeItems(snapshot, applyFilter, cancellationCheck = { ensureActive() })
                val summary = spliceSummarize(previous.items, previous.summary, items)
                    ?: summarizeItems(items)
                ComputedLogItems(items, summary, loading = false)
            }
            // Grace period before flagging as loading: sub-quarter-second recomputes (the common
            // expand/collapse case, now that filter and sequence results are memoized across
            // expanded-only changes) swap in without ever flashing the loading line; only
            // genuinely slow recomputes show it.
            val quick = withTimeoutOrNull(LOADING_GRACE_MS) { deferred.await() }
            computed = quick ?: run {
                computed = ComputedLogItems(computed.items, computed.summary, loading = true)
                deferred.await()
            }
        }
    }
    return computed
}

// Seed for the gap-mode branch of rememberTimeDeltaChars below, used for the first paint while its
// off-thread scan is still running. A plain sub-minute gap ("+0.000"-shaped, 6 chars) is the
// overwhelmingly common case — consecutive rows in a real logcat are almost always milliseconds
// apart — so seeding with it means the very first frame already looks like the eventual answer
// instead of flashing some other width and then resizing once the scan lands.
private const val GAP_MODE_SEED_CHARS = 6

// Character budget for the Δt gutter (Theme.kt's timeDeltaColumnWidth), sized from the widest
// delta string actually rendered for this tab/mode instead of a fixed worst-case width.
// `selected` is passed in separately from `tab` (rather than reading tab.selected directly)
// because split view's Original panel anchors off its own local selection, not the tab's — see
// this function's call sites below.
//
// The two modes are NOT computed the same way — see widestAnchorDeltaMagnitudeMs/
// widestAdjacentGapMagnitudeMs's own docs for why collapsing them back into one shortcut is
// exactly the bug this split fixes:
//   - Anchor mode (a row selected): O(1), bounded by the two endpoints — cheap enough to compute
//     synchronously inside `remember`.
//   - Gap mode (no selection): the real bound is the single largest ADJACENT-row gap anywhere in
//     tab.logData, which needs an O(n) scan — tab.logData can be millions of rows, so this runs
//     off the UI thread via LaunchedEffect + Dispatchers.Default, the same pattern ui/Minimap.kt
//     already uses for its own O(n) bucket pass, and is seeded (GAP_MODE_SEED_CHARS) so the first
//     frame isn't visibly wrong while the scan is in flight.
//
// Both branches key on the tab id/size (so a tailed-in append or a tab switch recomputes) and the
// anchor id (so selecting/deselecting a line recomputes). Gap mode also keys on the actual visible
// item list: sizing from the full unfiltered file left a large blank gutter when a hidden outlier
// gap was the only long delta, which made otherwise identical files appear to have arbitrary spacing.
@Composable
private fun rememberTimeDeltaChars(tab: LogTab, selected: Set<Int>, visibleItems: List<LogItem>): Int {
    if (!tab.showTimeDelta) return 1
    val anchorId = deltaAnchorId(selected)
    if (anchorId != null) {
        return remember(tab.id, tab.logData.size, anchorId) {
            val firstTs = tab.logData.firstOrNull()?.ts
            val lastTs = tab.logData.lastOrNull()?.ts
            val anchorTs = tab.rmap[anchorId]?.ts
            if (firstTs == null || lastTs == null || anchorTs == null) {
                1
            } else {
                formatDelta(widestAnchorDeltaMagnitudeMs(firstTs, lastTs, anchorTs)).length
            }
        }
    }

    var gapChars by remember(tab.id, tab.logData.size, visibleItems) { mutableStateOf(GAP_MODE_SEED_CHARS) }
    LaunchedEffect(tab.id, tab.logData.size, visibleItems) {
        gapChars = withContext(Dispatchers.Default) {
            formatDelta(widestVisibleGapMagnitudeMs(visibleItems)).length
        }
    }
    return gapChars
}

private fun widestVisibleGapMagnitudeMs(items: List<LogItem>): Long {
    var previousTs: String? = null
    var widest = 0L
    items.forEach { item ->
        val ts = when (item) {
            is LogItem.Row -> item.entry.ts
            is LogItem.SeqHeader -> item.entry.ts
            is LogItem.ManualHeader -> item.entry.ts
            is LogItem.StackTraceHeader -> item.entry.ts
        }
        previousTs?.let { previous ->
            deltaMillis(previous, ts)?.let { widest = maxOf(widest, kotlin.math.abs(it)) }
        }
        previousTs = ts
    }
    return widest
}

internal data class AnnotationNavigationTarget(
    val filteredEntryId: Int?,
    val originalEntryId: Int?,
)

internal data class ExpansionAndIndexTarget(
    val expanded: Set<String>,
    val index: Int,
)

internal fun annotationNavigationTarget(
    referencedIds: List<Int>,
    filteredVisibleIds: List<Int>,
    originalOpen: Boolean,
): AnnotationNavigationTarget? {
    val filteredId = referencedIds.firstOrNull { it in filteredVisibleIds }
    val originalId = referencedIds.firstOrNull()?.takeIf { originalOpen }
    if (filteredId == null && originalId == null) return null
    return AnnotationNavigationTarget(filteredEntryId = filteredId, originalEntryId = originalId)
}

internal fun annotationNavigationTarget(
    referencedIds: List<Int>,
    filteredVisibleIds: IntArray,
    originalOpen: Boolean,
): AnnotationNavigationTarget? {
    val filteredId = referencedIds.firstOrNull { filteredVisibleIds.contains(it) }
    val originalId = referencedIds.firstOrNull()?.takeIf { originalOpen }
    if (filteredId == null && originalId == null) return null
    return AnnotationNavigationTarget(filteredEntryId = filteredId, originalEntryId = originalId)
}

internal fun visibleRowRangeIds(fromId: Int, toId: Int, visibleIds: List<Int>): List<Int> {
    val a = visibleIds.indexOf(fromId)
    val b = visibleIds.indexOf(toId)
    return if (a >= 0 && b >= 0) visibleIds.subList(minOf(a, b), maxOf(a, b) + 1) else emptyList()
}

// IntArray twin of the above for the drag-select hot path — no per-element boxing, and (P-05)
// an O(log n) indexOfId lookup instead of an O(n) .indexOf scan on every drag pointer-move event.
internal fun visibleRowRangeIds(fromId: Int, toId: Int, visibleIds: IntArray): List<Int> {
    val a = visibleIds.indexOfId(fromId)
    val b = visibleIds.indexOfId(toId)
    return if (a >= 0 && b >= 0) (minOf(a, b)..maxOf(a, b)).map { visibleIds[it] } else emptyList()
}

internal fun logItemEntryId(item: LogItem): Int = when (item) {
    is LogItem.Row -> item.entry.id
    is LogItem.SeqHeader -> item.entry.id
    is LogItem.ManualHeader -> item.entry.id
    is LogItem.StackTraceHeader -> item.entry.id
}

internal fun logItemStableKey(tabId: String, item: LogItem): String = when (item) {
    is LogItem.Row -> "$tabId:r${item.entry.id}"
    is LogItem.SeqHeader -> "$tabId:h${item.gid}"
    is LogItem.ManualHeader -> "$tabId:m${item.gid}"
    is LogItem.StackTraceHeader -> "$tabId:st${item.gid}"
}

internal fun expansionAndIndexForEntry(
    tab: LogTab,
    applyFilter: Boolean,
    entryId: Int,
    currentItems: List<LogItem>? = null,
): ExpansionAndIndexTarget? {
    val regexContext = RegexEvaluationContext()
    // An entry excluded by the filter itself (not merely folded inside a collapsed group) can
    // never be surfaced by expanding groups — bail before the loop below instead of burning up to
    // 24 rounds of full computeItems() recomputation trying every collapsed header in the file,
    // which on a large log made a bulk exclude/hide action feel like a hang.
    if (applyFilter) {
        val entry = tab.rmap[entryId] ?: return null
        if (!passesFilter(entry, tab.filter, regexContext)) return null
    }
    var expanded = tab.expanded
    var candidateItems = currentItems ?: computeItems(tab.copy(expanded = expanded), applyFilter, regexContext)
    repeat(24) {
        val visibleIdx = candidateItems.indexOfEntry(entryId)
        if (visibleIdx >= 0) return ExpansionAndIndexTarget(expanded, visibleIdx)
        if (tab.largeFileMode) return null
        val collapsedHeaders = candidateItems.mapNotNull { item ->
            when (item) {
                is LogItem.SeqHeader -> item.gid.takeIf { !item.expanded }?.let { it to item.entry.id }
                is LogItem.ManualHeader -> item.gid.takeIf { !item.expanded }?.let { it to item.entry.id }
                is LogItem.StackTraceHeader -> item.gid.takeIf { !item.expanded }?.let { it to item.entry.id }
                is LogItem.Row -> null
            }
        }
        val ranked = rankCollapsedHeadersByProximity(collapsedHeaders, entryId)
        val groupToOpen = ranked.firstOrNull { gid ->
            computeItems(tab.copy(expanded = expanded + gid), applyFilter, regexContext).anyEntry(entryId)
        } ?: ranked.firstOrNull() ?: return null
        expanded = expanded + groupToOpen
        candidateItems = computeItems(tab.copy(expanded = expanded), applyFilter, regexContext)
    }
    return null
}

private fun List<LogItem>.indexOfEntry(entryId: Int): Int = indexOfFirst { it.hasEntryId(entryId) }

private fun List<LogItem>.anyEntry(entryId: Int): Boolean = any { it.hasEntryId(entryId) }

private fun LogItem.hasEntryId(entryId: Int): Boolean = when (this) {
    is LogItem.Row -> entry.id == entryId
    is LogItem.SeqHeader -> entry.id == entryId
    is LogItem.ManualHeader -> entry.id == entryId
    is LogItem.StackTraceHeader -> entry.id == entryId
}

class LogViewerScrollStateStore {
    private val lazyStates = mutableMapOf<String, LazyListState>()
    private val horizontalStates = mutableMapOf<String, ScrollState>()

    fun lazyState(panelKey: String): LazyListState =
        lazyStates.getOrPut(panelKey) { LazyListState() }

    // ScrollState itself is axis-agnostic — this backs the log rows' horizontal scroll, but is
    // reused as-is (same store, same "$tabId:" prefix cleanup) for other single-axis scroll
    // positions that need to survive a tab switch, e.g. the Notes panel's vertical scroll.
    fun scrollState(panelKey: String): ScrollState =
        horizontalStates.getOrPut(panelKey) { ScrollState(0) }

    fun removeTab(tabId: String) {
        val prefix = "$tabId:"
        lazyStates.keys.removeAll { it.startsWith(prefix) }
        horizontalStates.keys.removeAll { it.startsWith(prefix) }
    }
}

// In-view "Find" bar match info for one line (ui/SearchBar.kt, AppState.LogSearchState) —
// isCurrentRow picks which of the two theme-derived backgrounds (ThemeColors.searchMatchBg /
// searchCurrentBg, passed in from the call site's tc) buildFullLineAnnotation paints every match
// span in this line with.
internal data class SearchHighlight(
    val query: String,
    val caseSensitive: Boolean,
    val isCurrentRow: Boolean,
    val matchBg: Color,
    val currentBg: Color,
)

// Full selectable line matching raw logcat threadtime layout:
//   ts  pid  tid  L  tag: msg
// Level key sits at its natural position (after pid/tid) and is coloured by level.
fun buildFullLineAnnotation(
    entry: LogEntry,
    highlighters: List<Highlighter>,
    tsColor: Color,
    pidColor: Color,
    tagColor: Color,
    msgColor: Color,
    keywordRegexFilter: Filter? = null,
): AnnotatedString = buildFullLineAnnotation(
    entry,
    highlighters,
    tsColor,
    pidColor,
    tagColor,
    msgColor,
    keywordRegexFilter,
    RegexEvaluationContext(),
)

internal fun buildFullLineAnnotation(
    entry: LogEntry,
    highlighters: List<Highlighter>,
    tsColor: Color,
    pidColor: Color,
    tagColor: Color,
    msgColor: Color,
    keywordRegexFilter: Filter?,
    regexContext: RegexEvaluationContext,
    searchHighlight: SearchHighlight? = null,
): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = tsColor)) { append(entry.ts) }
    if (entry.pid > 0) {
        append("  ")
        withStyle(SpanStyle(color = pidColor)) {
            append(entry.pid.toString().padStart(5))
            append(" ")
            append(entry.tid.toString().padStart(5))
        }
    }
    append("  ")
    withStyle(SpanStyle(color = entry.level.defaultColor, fontWeight = FontWeight.Bold)) {
        append(entry.level.key.toString())
    }
    append("  ")
    withStyle(SpanStyle(color = tagColor)) { append(entry.tag); append(":") }
    append(" ")
    withStyle(SpanStyle(color = msgColor)) { append(entry.msg) }
    val lineText = visibleLogLineText(entry)
    for (hl in highlighters.filter { it.on && it.pattern.isNotBlank() }) {
        hlRanges(lineText, hl, regexContext).forEach { (s, e) ->
            if (s < e && e <= lineText.length)
                addStyle(SpanStyle(background = hl.color.copy(alpha = 0.6f), fontWeight = FontWeight.SemiBold), s, e)
        }
    }
    keywordRegexFilter?.let { filter ->
        keywordRegexHighlightRanges(lineText, filter, regexContext).forEach { (s, e) ->
            if (s < e && e <= lineText.length) {
                addStyle(
                    SpanStyle(background = filter.kwHighlightColor.copy(alpha = 0.6f), fontWeight = FontWeight.SemiBold),
                    s,
                    e,
                )
            }
        }
    }
    // Appended last (after highlighter + keyword-regex spans above) so a Find match always wins
    // visually — addStyle layers are painted in the order added, later spans on top.
    searchHighlight?.let { sh ->
        if (sh.query.isNotEmpty()) {
            val bg = if (sh.isCurrentRow) sh.currentBg else sh.matchBg
            regexRanges(lineText, sh.query, ignoreCase = !sh.caseSensitive, regexContext = regexContext).forEach { (s, e) ->
                if (s < e && e <= lineText.length) {
                    addStyle(SpanStyle(background = bg, fontWeight = FontWeight.SemiBold), s, e)
                }
            }
        }
    }
}

// Start offset of each wrapped visual line (always begins with 0; count == number of lines).
// Breaks after the last space within budget so ordinary words are never split mid-word — only
// falls back to a hard break at the budget boundary when a single unbroken token (a long URI,
// base64 blob, ...) exceeds the whole limit by itself, which still guarantees bounded overflow.
// Never removes or adds a character — the break is always a choice of *where* to insert '\n'
// into the existing sequence — so stripVisualWrapBreaks always reconstructs the original exactly.
private fun wrapBreakStarts(text: CharSequence, limit: Int): List<Int> {
    val starts = mutableListOf(0)
    var start = 0
    while (start < text.length) {
        val maxEnd = (start + limit).coerceAtMost(text.length)
        if (maxEnd == text.length) break
        var breakAt = maxEnd
        var i = maxEnd - 1
        while (i > start) {
            if (text[i] == ' ') {
                breakAt = i + 1
                break
            }
            i--
        }
        starts += breakAt
        start = breakAt
    }
    return starts
}

fun visualLogLineForWrapLimit(text: String, limitChars: Int): String {
    val limit = limitChars.coerceAtLeast(1)
    if (text.length <= limit) return text
    val starts = wrapBreakStarts(text, limit)
    return starts.indices.joinToString("\n") { idx ->
        text.substring(starts[idx], starts.getOrNull(idx + 1) ?: text.length)
    }
}

fun stripVisualWrapBreaks(text: String): String = text.replace("\n", "")

fun keyboardCopyTextForLogPanel(selectedText: String?, selectedRowsText: () -> String): String =
    selectedText?.takeIf { it.isNotBlank() } ?: selectedRowsText()

private fun visualLogLineForWrapLimit(line: AnnotatedString, limitChars: Int): AnnotatedString {
    val limit = limitChars.coerceAtLeast(1)
    if (line.length <= limit) return line
    val starts = wrapBreakStarts(line.text, limit)
    val builder = AnnotatedString.Builder()
    starts.forEachIndexed { idx, from ->
        if (idx > 0) builder.append('\n')
        builder.append(line.subSequence(from, starts.getOrNull(idx + 1) ?: line.length))
    }
    return builder.toAnnotatedString()
}

private const val MIN_WRAP_LIMIT_CHARS = 80
private const val MAX_WRAP_LIMIT_CHARS = 20_000
private const val ROW_HORIZONTAL_CHROME_DP = 24f
private const val MIN_CHAR_WIDTH_DP = 1f
private const val CONTENT_WIDTH_PADDING_DP = 80f

private const val MIN_LOG_CONTENT_WIDTH_DP = 2000

fun effectiveLogWrapLimitChars(
    auto: Boolean,
    configuredLimitChars: Int,
    visibleWidthDp: Float,
    charWidthDp: Float,
): Int {
    if (!auto) return configuredLimitChars.coerceIn(MIN_WRAP_LIMIT_CHARS, MAX_WRAP_LIMIT_CHARS)
    val usableWidthDp = (visibleWidthDp - ROW_HORIZONTAL_CHROME_DP).coerceAtLeast(0f)
    return (usableWidthDp / charWidthDp.coerceAtLeast(MIN_CHAR_WIDTH_DP)).roundToInt()
        .coerceIn(MIN_WRAP_LIMIT_CHARS, MAX_WRAP_LIMIT_CHARS)
}

private fun logContentWidthDp(wrapLimitChars: Int, charWidthDp: Float): Dp {
    return (wrapLimitChars.coerceAtLeast(MIN_WRAP_LIMIT_CHARS) * charWidthDp + CONTENT_WIDTH_PADDING_DP).dp
        .coerceAtLeast(MIN_LOG_CONTENT_WIDTH_DP.dp)
}

@Composable
fun LogViewer(
    tab: LogTab,
    modifier: Modifier = Modifier,
    settings: AppSettings = AppSettings(),
    onSelRow: (Int, Boolean, Boolean) -> Unit,
    onSelRowRange: (List<Int>) -> Unit = { _ -> },
    onCtxMenu: (Int, Float, Float, String, Set<Int>) -> Unit,
    onToggleGroup: (String) -> Unit,
    onClearFilter: () -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    onToggleUnfiltered: () -> Unit,
    // Per-tab Δt-column toggle (LogTab.showTimeDelta, AppState.toggleTimeDelta) — a toolbar button
    // beside Export, not a Settings entry (see LogTab.showTimeDelta's doc comment for why). Default
    // no-op keeps preview/test call sites that don't wire it unaffected, same as the other optional
    // callbacks below.
    onToggleTimeDelta: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onToggleRowNumbers: () -> Unit = {},
    onToggleMinimap: () -> Unit = {},
    onExportTxt: () -> Unit,
    onExportCsv: () -> Unit,
    scrollStateStore: LogViewerScrollStateStore? = null,
    annotationNavigationRequest: AnnotationNavigationRequest? = null,
    onConsumeAnnotationNavigation: (Long) -> Unit = {},
    // Find bar's own navigation channel — deliberately separate from annotationNavigationRequest
    // above (see SearchNavigationRequest's doc comment in AppState.kt): it drives a minimal-scroll
    // LaunchedEffect instead of always centering, so Enter/Next/Prev doesn't visibly flash when the
    // match is already on screen.
    searchNavigationRequest: SearchNavigationRequest? = null,
    onConsumeSearchNavigation: (Long) -> Unit = {},
    onSelectAll: (() -> Unit)? = null,
    onClearSelection: (() -> Unit)? = null,
    onCopySelection: ((Set<Int>?) -> Unit)? = null,
    onCopyText: (String) -> Unit = {},
    navScrollMargin: Int = 5,
    focusRequester: FocusRequester? = null,
    onPanelFocusChanged: (Boolean) -> Unit = {},
    keyboardFocusVisible: Boolean = false,
    // Pushes each freshly computed filtered item summary up to AppState so selection ops
    // (shift-click range, select-all) can reuse it instead of recomputing on the UI thread.
    onVisibleItems: ((ItemsSummary) -> Unit)? = null,
    // Tracks AppState.hoveredLogPanelKey (by the panel's panelKey, e.g. "<tabId>:main") for the
    // Linux X11 horizontal-scroll AWT bridge in Main.kt, which has no Compose pointer position of
    // its own to resolve which panel a button-6/7 press should scroll. Default no-op keeps every
    // other LogViewer call site (previews, other tests) unaffected.
    onHoverPanelKey: (String?) -> Unit = {},
    // In-view "Find" bar (ui/SearchBar.kt, Settings.ctrlFTarget == FIND_BAR) — wired to
    // AppState.setSearchQuery/toggleSearchCase/searchNext/searchPrev/closeSearch by FileView.kt and
    // CompareView.kt. Defaults keep every other call site (previews, tests) unaffected:
    // tab.search.active stays false unless AppState.openSearch was actually called, so SearchBar
    // simply never renders for them.
    onSearchQueryChange: (String) -> Unit = {},
    onSearchToggleCase: () -> Unit = {},
    onSearchNext: () -> Unit = {},
    onSearchPrev: () -> Unit = {},
    onSearchClose: () -> Unit = {},
) {
    val tc        = tc()
    val mono      = monoFont()
    val toolbarDensity = LocalDensity.current
    val scrollStates = scrollStateStore ?: remember { LogViewerScrollStateStore() }
    val computedItems = rememberComputedLogItems(tab, true)
    val items = computedItems.items
    val itemsVersion = items.size to items.lastOrNull()?.let(::logItemEntryId)
    val visCnt = computedItems.summary.visibleEntryCount
    val totalCnt  = tab.logData.size
    // P-07: keyed on tab.id alone, this never recomputed once tailing appended lines that
    // introduced pid/tid data to a file that initially had none — the PID/TID column headers
    // stayed hidden until the tab was closed and reopened. totalCnt (already tracked here) lets
    // this recompute whenever new data arrives, same as the other remember/LaunchedEffect calls
    // in this file that key on the tailed-growth size. any{} short-circuits on the first match,
    // so this only re-pays a full scan repeatedly for tabs that genuinely have zero pid/tid data.
    val hasPidTid = remember(tab.id, totalCnt) { tab.logData.any { it.pid > 0 } }
    LaunchedEffect(computedItems) {
        if (!computedItems.loading) onVisibleItems?.invoke(computedItems.summary)
    }
    val canExpandAll = computedItems.summary.collapsedGroupCount > 0
    val canCollapseAll = computedItems.summary.expandedGroupCount > 0
    var toolbarIndex by remember(tab.id) { mutableStateOf<Int?>(null) }
    var exportMenuOpen by remember(tab.id) { mutableStateOf(false) }
    var toolbarContextMenuOpen by remember(tab.id) { mutableStateOf(false) }
    var toolbarContextMenuOffset by remember(tab.id) { mutableStateOf(IntOffset.Zero) }
    var toolbarWidthPx by remember(tab.id) { mutableStateOf(0) }

    // Row bounds for global drag-select (plain HashMap avoids recomposition on scroll updates)
    val rowBoundsAbs = remember { HashMap<Int, Pair<Float, Float>>() }
    val boxPosY      = remember { floatArrayOf(0f) }

    // Clear stale bounds from previous tab so drag-select uses correct positions
    LaunchedEffect(tab.id) { rowBoundsAbs.clear() }

    // Order here MUST match the toolbar's actual left-to-right button order below — toolbarIndex
    // (roving keyboard nav) indexes into this same list, and the per-button border highlight is
    // literally `toolbarIndex == <that button's position in this list>`. Adding/reordering a
    // toolbar button means updating BOTH this list and every `toolbarIndex == N` check below.
    fun toolbarActions(): List<Pair<Boolean, () -> Unit>> = listOf(
        true to { exportMenuOpen = true },
        true to onToggleTimeDelta,
        true to onOpenSearch,
        canExpandAll to onExpandAll,
        canCollapseAll to onCollapseAll,
        true to onToggleUnfiltered,
    )

    fun toolbarRovingItems(): List<RovingItem> =
        toolbarActions().mapIndexed { idx, action -> RovingItem(idx.toString(), action.first) }

    Column(modifier.fillMaxSize().background(tc.bg)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp)
                .background(tc.p)
                .border(BorderStroke(1.dp, tc.br))
                .onSizeChanged { toolbarWidthPx = it.width }
                .pointerInput("toolbar-context", tab.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                event.changes.forEach { it.consume() }
                                val position = event.changes.firstOrNull()?.position ?: Offset.Zero
                                val menuWidthPx = with(toolbarDensity) { 190.dp.toPx() }
                                toolbarContextMenuOffset = IntOffset(
                                    position.x.roundToInt().coerceIn(
                                        0,
                                        (toolbarWidthPx - menuWidthPx).roundToInt().coerceAtLeast(0),
                                    ),
                                    position.y.roundToInt().coerceAtLeast(0),
                                )
                                toolbarContextMenuOpen = true
                            }
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(12.dp))
            Box {
                AppButton(
                    "Export ▾",
                    onClick = { exportMenuOpen = true },
                    modifier = Modifier.border(1.dp, if (toolbarIndex == 0) tc.ac else Color.Transparent, CORNER_MD),
                )
                if (exportMenuOpen) {
                    ExportMenuPopup(
                        onExportTxt = { exportMenuOpen = false; onExportTxt() },
                        onExportCsv = { exportMenuOpen = false; onExportCsv() },
                        onDismiss = { exportMenuOpen = false },
                        tc = tc,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // Primary variant when on is this toolbar's only "active" state affordance (Unfiltered,
            // the other stateful toggle here, signals its state via label text instead — "Δt on/off"
            // has no comparably natural alternate label, so a filled/tinted button is the toggle cue).
            AppButton(
                "Δt",
                onClick = onToggleTimeDelta,
                variant = if (tab.showTimeDelta) ButtonVariant.Primary else ButtonVariant.Secondary,
                modifier = Modifier.border(1.dp, if (toolbarIndex == 1) tc.ac else Color.Transparent, CORNER_MD),
            )
            Spacer(Modifier.width(8.dp))
            AppButton(
                "",
                onClick = onOpenSearch,
                leadingIcon = Icons.Outlined.Search,
                horizontalPadding = 8.dp,
                modifier = Modifier.border(1.dp, if (toolbarIndex == 2) tc.ac else Color.Transparent, CORNER_MD),
            )
            Spacer(Modifier.width(8.dp))
            val countLabel = if (tab.largeFileMode) "$visCnt / $totalCnt entries - large file mode" else "$visCnt / $totalCnt entries"
            AppText(countLabel, color = tc.td, fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.weight(1f))
            AppButton(
                "Expand all",
                onClick = onExpandAll,
                enabled = canExpandAll,
                modifier = Modifier.border(1.dp, if (toolbarIndex == 3) tc.ac else Color.Transparent, CORNER_MD),
            )
            Spacer(Modifier.width(4.dp))
            AppButton(
                "Collapse all",
                onClick = onCollapseAll,
                enabled = canCollapseAll,
                modifier = Modifier.border(1.dp, if (toolbarIndex == 4) tc.ac else Color.Transparent, CORNER_MD),
            )
            Spacer(Modifier.width(4.dp))
            AppButton(
                if (tab.showUnfiltered) "Hide original" else "Unfiltered",
                onClick = onToggleUnfiltered,
                modifier = Modifier.border(1.dp, if (toolbarIndex == 5) tc.ac else Color.Transparent, CORNER_MD),
            )
            Spacer(Modifier.width(8.dp))
            if (toolbarContextMenuOpen) {
                ToolbarOptionsPopup(
                    showRowNumbers = settings.showRowNumbers,
                    showMinimap = settings.showMinimap,
                    onToggleRowNumbers = { toolbarContextMenuOpen = false; onToggleRowNumbers() },
                    onToggleMinimap = { toolbarContextMenuOpen = false; onToggleMinimap() },
                    onDismiss = { toolbarContextMenuOpen = false },
                    offset = toolbarContextMenuOffset,
                    tc = tc,
                )
            }
        }

        @Composable
        fun ItemList(
            listItems: List<LogItem>,
            listSummary: ItemsSummary,
            boundsMap: HashMap<Int, Pair<Float, Float>>,
            posY: FloatArray,
            // Allows each panel to own its selection/context independently when showUnfiltered is active.
            effectiveTab: LogTab = tab,
            itemOnSelRow: (Int, Boolean, Boolean) -> Unit = onSelRow,
            itemOnSelRowRange: (List<Int>) -> Unit = onSelRowRange,
            itemOnSelectAll: (() -> Unit)? = onSelectAll,
            itemOnClearSelection: (() -> Unit)? = onClearSelection,
            itemOnCopySelection: ((Set<Int>?) -> Unit)? = onCopySelection,
            itemsLoading: Boolean = computedItems.loading,
            // Wraps the outer 5-arg onCtxMenu; callers may inject a different selectedIds set.
            itemOnCtxMenu: (Int, Float, Float, String) -> Unit = { id, x, y, sel -> onCtxMenu(id, x, y, sel, emptySet()) },
            panelKey: String = effectiveTab.id,
            listState: LazyListState? = null,
            externalFr: FocusRequester? = null,
            onFocusChangedExternal: (Boolean) -> Unit = {},
            // Δt column width in characters, precomputed once for this panel by the caller
            // (rememberTimeDeltaChars) and passed straight through to every LogRow — see that
            // function's doc for why this must NOT be recomputed per row.
            timeDeltaChars: Int = 1,
        ) {
            if (listItems.isEmpty()) {
                if (itemsLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        IndeterminateLoadingLine(Modifier.width(180.dp))
                    }
                } else {
                    EmptyState(tc, totalCnt, onClearFilter)
                }
                return
            }
            val highlightRegexContext = remember(panelKey, effectiveTab.filter, listSummary) {
                RegexEvaluationContext()
            }
            val lazyState = listState ?: scrollStates.lazyState(panelKey)
            val hScroll   = scrollStates.scrollState(panelKey)
            val scrollbarStyle = appScrollbarStyle(tc)
            val density = LocalDensity.current
            // Must cover EVERYTHING drawn in the CenterEnd column beside the log rows — the
            // Minimap strip AND VerticalScrollbar now render side by side there when
            // settings.showMinimap is on (see the BoxWithConstraints wiring below), so this is
            // additive, not a max. Re-derived from MINIMAP_WIDTH/MINIMAP_CONTENT_GAP (Minimap.kt)
            // rather than hand-copied, since this exact constant has gone stale twice already —
            // once when the minimap's width changed and once when the content gap was added.
            // Getting it wrong is the #1 trap for this feature: a drag starting on either bar (or
            // now, on the gap between the content and the bars) would fall inside the region this
            // pointerInput treats as "on a row" and begin a row range-selection underneath it,
            // instead of being ignored the way a scrollbar-area drag already is.
            val verticalScrollbarGutterPx = with(density) {
                (16.dp + if (settings.showMinimap) MINIMAP_WIDTH + MINIMAP_CONTENT_GAP else 0.dp).toPx()
            }
            val horizontalScrollbarGutterPx = with(density) { 18.dp.toPx() }
            val visibleIds = listSummary.allIds
            val currentOnSelRowRange by rememberUpdatedState(itemOnSelRowRange)
            var selectedTextForCopy by remember(panelKey) { mutableStateOf("") }
            // Prune bounds of rows that no longer exist — once per new item list, NOT once per
            // recomposition: the old SideEffect built a boxed HashSet of every visible id on
            // every recomposition, a multi-hundred-ms UI stall on multi-million-row tabs.
            LaunchedEffect(listSummary) {
                boundsMap.keys.removeAll { !listSummary.idBits.get(it) }
            }
            val fr = externalFr ?: remember { FocusRequester() }
            var isFocused by remember { mutableStateOf(false) }
            val navScope = rememberCoroutineScope()
            var anchorId by remember(effectiveTab.id) { mutableStateOf<Int?>(null) }
            var cursorId by remember(effectiveTab.id) { mutableStateOf<Int?>(null) }
            Box(
                Modifier.fillMaxSize()
                    .onGloballyPositioned { posY[0] = it.positionInRoot().y }
                    .pointerInput("drag", effectiveTab.id, visibleIds) {
                        awaitPointerEventScope {
                            var startId: Int? = null
                            var lastId: Int? = null
                            var startPos = Offset.Zero
                            var dragSelecting = false
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = ev.changes.firstOrNull() ?: continue
                                when (ev.type) {
                                    PointerEventType.Press -> if (ev.buttons.isPrimaryPressed) {
                                        startPos = ch.position
                                        dragSelecting = false
                                        fr.requestFocus()
                                        if (
                                            ch.position.x > size.width - verticalScrollbarGutterPx ||
                                            ch.position.y > size.height - horizontalScrollbarGutterPx
                                        ) {
                                            startId = null
                                            lastId = null
                                            continue
                                        }
                                        val absY = posY[0] + ch.position.y
                                        startId = boundsMap.entries.firstOrNull { (_, b) -> absY >= b.first && absY < b.second }?.key
                                        lastId  = startId
                                    }
                                    PointerEventType.Move -> if (ev.buttons.isPrimaryPressed && startId != null) {
                                        val delta = ch.position - startPos
                                        if (!dragSelecting && kotlin.math.abs(delta.y) > 4f && kotlin.math.abs(delta.y) > kotlin.math.abs(delta.x)) {
                                            dragSelecting = true
                                        }
                                        if (dragSelecting) ch.consume()
                                        val absY = posY[0] + ch.position.y
                                        val id   = boundsMap.entries.firstOrNull { (_, b) -> absY >= b.first && absY < b.second }?.key
                                        if (id != null && id != lastId) {
                                            val rangeIds = visibleRowRangeIds(startId, id, visibleIds)
                                            if (rangeIds.isNotEmpty()) {
                                                lastId = id
                                                currentOnSelRowRange(rangeIds)
                                            }
                                        }
                                    }
                                    PointerEventType.Release -> {
                                        startId = null
                                        lastId = null
                                        dragSelecting = false
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                    .onFocusChanged { isFocused = it.isFocused; onFocusChangedExternal(it.isFocused) }
                    .focusRequester(fr)
                    .focusable()
                    .onPreviewKeyEvent { ev ->
                        val selCursor = SelectionCursor(
                            anchorId, cursorId,
                            onAnchorChange = { anchorId = it },
                            onCursorChange = { cursorId = it },
                        )
                        if (ev.type == KeyEventType.KeyDown && toolbarIndex != null) {
                            val actions = toolbarActions()
                            when (ev.key) {
                                Key.DirectionLeft -> {
                                    toolbarIndex = rovingMove(
                                        toolbarRovingItems(),
                                        toolbarIndex ?: 0,
                                        -1,
                                        wrap = true,
                                    )
                                    return@onPreviewKeyEvent true
                                }
                                Key.DirectionRight -> {
                                    toolbarIndex = rovingMove(
                                        toolbarRovingItems(),
                                        toolbarIndex ?: 0,
                                        +1,
                                        wrap = true,
                                    )
                                    return@onPreviewKeyEvent true
                                }
                                Key.DirectionDown, Key.Escape -> {
                                    toolbarIndex = null
                                    return@onPreviewKeyEvent true
                                }
                                Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                                    val idx = toolbarIndex ?: 0
                                    actions.getOrNull(idx)?.takeIf { it.first }?.second?.invoke()
                                    return@onPreviewKeyEvent true
                                }
                            }
                        }
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp && !ev.isShiftPressed) {
                            val firstRow = listSummary.rowIds.firstOrNull()
                            val current = selCursor.effectiveCursorId(effectiveTab)
                            if (firstRow != null && current == firstRow) {
                                toolbarIndex = rovingMove(toolbarRovingItems(), 0, +1, wrap = true)
                                return@onPreviewKeyEvent true
                            }
                        }
                        if (ev.type == KeyEventType.KeyDown && ev.isShiftPressed && ev.key == Key.F10) {
                            val id = selCursor.effectiveCursorId(effectiveTab)
                            val bounds = id?.let { boundsMap[it] }
                            if (id != null && bounds != null) {
                                val yDp = with(density) { bounds.first.toDp() }
                                itemOnCtxMenu(id, CTX_MENU_KEYBOARD_X_DP, yDp.value, "")
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.NumPadEnter)) {
                            val id = selCursor.effectiveCursorId(effectiveTab)
                            val bounds = id?.let { boundsMap[it] }
                            if (id != null && bounds != null) {
                                val yDp = with(density) { bounds.first.toDp() }
                                itemOnCtxMenu(id, CTX_MENU_KEYBOARD_X_DP, yDp.value, "")
                                return@onPreviewKeyEvent true
                            }
                        }
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.Spacebar) {
                            val id = selCursor.effectiveCursorId(effectiveTab)
                            if (id != null) {
                                itemOnSelRow(id, true, false)
                                return@onPreviewKeyEvent true
                            }
                        }
                        val actionPressed = if (isMacOs) ev.isMetaPressed else ev.isCtrlPressed
                        if (ev.type == KeyEventType.KeyDown && actionPressed && ev.key == Key.C && selectedTextForCopy.isNotBlank()) {
                            onCopyText(keyboardCopyTextForLogPanel(selectedTextForCopy, selectedRowsText = { "" }))
                            return@onPreviewKeyEvent true
                        }
                        if (handleNavKey(ev, listItems, effectiveTab, lazyState, navScope, navScrollMargin,
                                selCursor, listSummary, onSelectRow = { id -> itemOnSelRowRange(listOf(id)) }))
                            return@onPreviewKeyEvent true
                        handleSelKey(ev, listItems, effectiveTab, lazyState, navScope, navScrollMargin,
                            itemOnSelRowRange, selCursor, listSummary,
                            actions = SelKeyActions(
                                itemOnSelectAll,
                                itemOnClearSelection,
                                itemOnCopySelection,
                            ))
                    }
                    .border(1.dp, if (isFocused && keyboardFocusVisible) tc.ac else Color.Transparent)
            ) {
                Column(Modifier.fillMaxSize()) {
                    // Content area: horizontal scroll wraps LazyColumn
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val tailSpaceHeight = maxHeight * 0.5f
                        val fontSizeSp = baseSp().value
                        // Only Manual mode's fixed-chars-per-line sizing (effectiveWrapLimitChars/
                        // logContentWidthDp below) still needs this estimate — Auto mode relies on
                        // BasicTextField's own softWrap for the actual row text (see LogRow's
                        // autoWrap param), which uses the real font metrics with zero estimation
                        // error, so rowContentWidth there is just the real viewport width directly.
                        val density = LocalDensity.current.density
                        val textMeasurer = rememberTextMeasurer()
                        val charWidthDp = remember(textMeasurer, fontSizeSp, density) {
                            val sampleLen = 64
                            val measured = textMeasurer.measure(
                                AnnotatedString("M".repeat(sampleLen)),
                                TextStyle(fontFamily = MONO, fontSize = fontSizeSp.sp),
                            )
                            (measured.size.width / sampleLen) / density
                        }
                        // The minimap and vertical scrollbar are drawn in a right-aligned overlay
                        // row. Keep the log viewport out from underneath that row so long lines and
                        // the log's right border remain readable when the minimap is enabled.
                        val logViewportWidth = (maxWidth - if (settings.showMinimap) {
                            16.dp + MINIMAP_CONTENT_GAP + MINIMAP_WIDTH
                        } else {
                            0.dp
                        }).coerceAtLeast(0.dp)
                        val effectiveWrapLimitChars = effectiveLogWrapLimitChars(
                            auto = settings.autoLogRowWrap,
                            configuredLimitChars = settings.logRowWrapLimitChars,
                            visibleWidthDp = logViewportWidth.value,
                            charWidthDp = charWidthDp,
                        )
                        val rowContentWidth = if (settings.autoLogRowWrap) {
                            logViewportWidth
                        } else {
                            logContentWidthDp(effectiveWrapLimitChars, charWidthDp)
                        }
                        val contentModifier = if (settings.autoLogRowWrap) {
                            Modifier.width(logViewportWidth).fillMaxHeight()
                        } else {
                            // horizontalScroll(hScroll) already reacts to PointerEventType.Scroll
                            // itself (that's how mouse-wheel scrolling works for any Compose
                            // Desktop scrollable, no extra code needed) — including Shift+wheel,
                            // which arrives here pre-converted to a horizontal Offset.x by
                            // Compose's own AWT bridge (see the Row wrapping tooltip below). A
                            // formerly-present onPointerEvent(Scroll) handler here duplicated that
                            // exact dispatch on the same unconsumed event, doubling Shift+wheel
                            // scroll speed; removed rather than left as dead/harmful code. Native
                            // Linux touchpad horizontal-swipe deltas never reach this handler at
                            // all (AWT has no horizontal wheel axis to deliver them on) — that
                            // path is bridged separately in ui/LinuxHorizontalScroll.kt via
                            // hoveredLogPanelKey below, not through Compose pointer events.
                            Modifier.width(logViewportWidth).fillMaxHeight()
                                .horizontalScroll(hScroll)
                                .onPointerEvent(PointerEventType.Enter) { onHoverPanelKey(panelKey) }
                                .onPointerEvent(PointerEventType.Exit) { onHoverPanelKey(null) }
                        }
                        LaunchedEffect(settings.autoLogRowWrap, hScroll) {
                            if (settings.autoLogRowWrap && hScroll.value != 0) hScroll.scrollTo(0)
                        }
                        Box(contentModifier) {
                            LazyColumn(
                                state = lazyState,
                                modifier = Modifier.width(rowContentWidth).fillMaxHeight(),
                            ) {
                                itemsIndexed(
                                    items = listItems,
                                    key = { _, item -> logItemStableKey(effectiveTab.id, item) }
                                ) { index, item ->
                                    // O(log n) per row via the same ascending-id IntArray lookup
                                    // ItemsSummary.rowIds uses (indexOfId) — matchIds is built in
                                    // display order by computeSearchMatches, so it's sorted too.
                                    // Only rows that ARE matches pay for a SearchHighlight/regex
                                    // pass at all; everything else passes null and skips it.
                                    val search = effectiveTab.search
                                    val matchIdx = if (search.active && search.matchIds.isNotEmpty()) {
                                        search.matchIds.indexOfId(logItemEntryId(item))
                                    } else {
                                        -1
                                    }
                                    val isSearchMatch = matchIdx >= 0
                                    val isCurrentSearchMatch = isSearchMatch && matchIdx == search.currentIdx
                                    // Δt baseline: a selection anchors every row's delta to the
                                    // SIGNED offset from the selected line (deltaAnchorId picks the
                                    // lowest selected id — see its own doc comment for why); with no
                                    // selection this falls back to the plain gap-to-previous-VISIBLE-
                                    // row behavior. Computed once per row here (not inside LogRow)
                                    // because it needs listItems[index - 1], which only this lambda
                                    // (via itemsIndexed) has.
                                    val deltaAnchorEntryId = if (effectiveTab.showTimeDelta) deltaAnchorId(effectiveTab.selected) else null
                                    val deltaMs = when {
                                        !effectiveTab.showTimeDelta -> null
                                        deltaAnchorEntryId != null ->
                                            effectiveTab.rmap[deltaAnchorEntryId]?.ts?.let { anchorTs -> deltaMillis(anchorTs, item.entry.ts) }
                                        index > 0 -> deltaMillis(listItems[index - 1].entry.ts, item.entry.ts)
                                        else -> null
                                    }
                                    when (item) {
                                        is LogItem.Row -> LogRow(
                                            item = item,
                                            tab = effectiveTab,
                                            mono = mono,
                                            tc = tc,
                                            wrapLimitChars = effectiveWrapLimitChars,
                                            onSelRow = itemOnSelRow,
                                            onCtxMenu = itemOnCtxMenu,
                                            onSelectedTextChange = { selectedTextForCopy = it },
                                            rowBoundsAbs = boundsMap,
                                            regexContext = highlightRegexContext,
                                            highlightEntireCrashGroup = settings.highlightEntireCrashGroup,
                                            autoWrap = settings.autoLogRowWrap,
                                            showRowNumbers = settings.showRowNumbers,
                                            showTimeDelta = effectiveTab.showTimeDelta,
                                            deltaMs = deltaMs,
                                            deltaSelectionAnchored = deltaAnchorEntryId != null,
                                            timeDeltaChars = timeDeltaChars,
                                            searchHighlight = if (isSearchMatch) {
                                                SearchHighlight(
                                                    search.query, search.caseSensitive, isCurrentSearchMatch,
                                                    matchBg = tc.searchMatchBg, currentBg = tc.searchCurrentBg,
                                                )
                                            } else {
                                                null
                                            },
                                        )
                                        is LogItem.SeqHeader ->
                                            SeqHeaderRow(
                                                item, effectiveTab, mono, tc, itemOnSelRow, itemOnCtxMenu, onToggleGroup, boundsMap,
                                                isSearchMatch = isSearchMatch, isCurrentSearchMatch = isCurrentSearchMatch,
                                                showTimeDelta = effectiveTab.showTimeDelta,
                                                deltaMs = deltaMs,
                                                deltaSelectionAnchored = deltaAnchorEntryId != null,
                                                timeDeltaChars = timeDeltaChars,
                                            )
                                        is LogItem.ManualHeader ->
                                            ManualHeaderRow(
                                                item, effectiveTab, mono, tc, itemOnSelRow, itemOnCtxMenu, onToggleGroup, boundsMap,
                                                isSearchMatch = isSearchMatch, isCurrentSearchMatch = isCurrentSearchMatch,
                                                showTimeDelta = effectiveTab.showTimeDelta,
                                                deltaMs = deltaMs,
                                                deltaSelectionAnchored = deltaAnchorEntryId != null,
                                                timeDeltaChars = timeDeltaChars,
                                            )
                                        is LogItem.StackTraceHeader ->
                                            StackTraceHeaderRow(
                                                item, effectiveTab, mono, tc, itemOnSelRow, itemOnCtxMenu, onToggleGroup, boundsMap,
                                                isSearchMatch = isSearchMatch, isCurrentSearchMatch = isCurrentSearchMatch,
                                                showTimeDelta = effectiveTab.showTimeDelta,
                                                deltaMs = deltaMs,
                                                deltaSelectionAnchored = deltaAnchorEntryId != null,
                                                timeDeltaChars = timeDeltaChars,
                                            )
                                    }
                                }
                                item(key = "tail-space") {
                                    Spacer(Modifier.height(tailSpaceHeight))
                                }
                            }
                        }
                        // Minimap sits beside VerticalScrollbar (outside it, i.e. further from the
                        // log content), not instead of it — Sublime shows both, and so does this.
                        // Both live in one right-aligned Row so they share the CenterEnd slot
                        // cleanly; see verticalScrollbarGutterPx above for the matching (additive)
                        // drag-select sizing this requires. Leading start-padding (only when the
                        // minimap itself is shown — see MINIMAP_CONTENT_GAP's own doc) is what
                        // visually separates the strip from the log content behind it, the same way
                        // Sublime's own minimap never butts directly against the text; nothing is
                        // drawn in that padding, so the content's own background shows through it.
                        Row(
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                                .then(if (settings.showMinimap) Modifier.padding(start = MINIMAP_CONTENT_GAP) else Modifier),
                        ) {
                            if (settings.showMinimap) {
                                Minimap(
                                    items = listItems,
                                    analysis = effectiveTab.analysis,
                                    highlighters = effectiveTab.filter.highlighters,
                                    lazyState = lazyState,
                                    tc = tc,
                                )
                            }
                            VerticalScrollbar(
                                adapter = rememberScrollbarAdapter(lazyState),
                                modifier = Modifier.fillMaxHeight(),
                                style = scrollbarStyle,
                            )
                        }
                    }
                    if (!settings.autoLogRowWrap) {
                        HorizontalScrollbar(
                            adapter = rememberScrollbarAdapter(hScroll),
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                            style = scrollbarStyle,
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(3.dp)) {
                        if (itemsLoading) IndeterminateLoadingLine(Modifier.fillMaxWidth())
                    }
                }
            }
        }

        if (tab.showUnfiltered) {
            val computedAllItems = rememberComputedLogItems(tab, false)
            val allItems = computedAllItems.items
            val allItemsVersion = allItems.size to allItems.lastOrNull()?.let(::logItemEntryId)
            // Each panel needs its own bounds map so row IDs from "Original" and "Filtered"
            // panels don't overwrite each other (both show some of the same entries).
            val allBoundsAbs = remember(tab.id) { HashMap<Int, Pair<Float, Float>>() }
            val allBoxPosY   = remember { floatArrayOf(0f) }
            // Split stored in dp so drag delta adds directly → border tracks cursor 1:1.
            // -1 means "not yet set; use 50% of containerH once measured."
            var splitDp by remember(tab.id) { mutableStateOf(-1f) }
            var containerH by remember { mutableStateOf(0f) }
            val density = LocalDensity.current.density
            val effectiveSplitDp = if (splitDp < 0f) maxOf(50f, (containerH - 10f) / 2f) else splitDp

            // Hoisted lazy state for the Original panel so the Filtered panel can scroll it.
            val allLazyState = scrollStates.lazyState("${tab.id}:original")
            // Same key as the single-view panel below (":main") — both render the exact same
            // `items` list, just in different layouts, so they must share one scroll state.
            // Using a distinct ":filtered" key here used to reset to the top every time
            // Unfiltered was toggled on, and lose whatever was scrolled in split view when
            // toggled back off.
            val filteredLazyState = scrollStates.lazyState("${tab.id}:main")
            val syncScope    = rememberCoroutineScope()

            // Independent selection for the "Original" panel so clicks there don't
            // highlight rows in the "Filtered" panel and vice-versa.
            var localAllSelected by remember(tab.id) { mutableStateOf(emptySet<Int>()) }
            val allOnSelRow: (Int, Boolean, Boolean) -> Unit = { id, multi, range ->
                val visIds = computedAllItems.summary.allIds
                localAllSelected = when {
                    multi -> if (id in localAllSelected) localAllSelected - id else localAllSelected + id
                    range -> {
                        val last = localAllSelected.lastOrNull { visIds.contains(it) }
                            ?: localAllSelected.maxOrNull()
                        if (last == null) {
                            setOf(id)
                        } else {
                            val a = visIds.indexOfId(last); val b = visIds.indexOfId(id)
                            if (a >= 0 && b >= 0) (minOf(a, b)..maxOf(a, b)).map { visIds[it] }.toSet()
                            else localAllSelected + id
                        }
                    }
                    else -> if (localAllSelected == setOf(id)) emptySet() else setOf(id)
                }
            }
            val allOnSelRowRange: (List<Int>) -> Unit = { ids -> localAllSelected = ids.toSet() }
            val allOnSelectAll: () -> Unit = { localAllSelected = computedAllItems.summary.allIds.toSet() }
            val allOnClearSelection: () -> Unit = { localAllSelected = emptySet() }

            LaunchedEffect(annotationNavigationRequest?.id, itemsVersion, allItemsVersion, tab.expanded) {
                val request = annotationNavigationRequest?.takeIf { it.tabId == tab.id } ?: return@LaunchedEffect
                val filteredTarget = request.logIds.firstNotNullOfOrNull { entryId ->
                    expansionAndIndexForEntry(tab, applyFilter = true, entryId = entryId, currentItems = items)
                }
                val originalTarget = request.logIds.firstNotNullOfOrNull { entryId ->
                    expansionAndIndexForEntry(tab, applyFilter = false, entryId = entryId, currentItems = allItems)
                }
                if (filteredTarget != null || originalTarget != null) {
                    localAllSelected = request.logIds.toSet()
                    var opened = tab.expanded
                    filteredTarget?.let { target ->
                        (target.expanded - opened).forEach { gid -> onToggleGroup(gid) }
                        if (target.expanded != opened) kotlinx.coroutines.delay(80)
                        opened = opened + target.expanded
                        filteredLazyState.centerOnItem(target.index)
                    }
                    originalTarget?.let { target ->
                        (target.expanded - opened).forEach { gid -> onToggleGroup(gid) }
                        if (target.expanded != opened) kotlinx.coroutines.delay(80)
                        opened = opened + target.expanded
                        allLazyState.centerOnItem(target.index)
                    }
                }
                onConsumeAnnotationNavigation(request.id)
            }

            // Find bar's own navigation channel (see SearchNavigationRequest's doc comment). Unlike
            // the single-view version below, split view also has to sync the Original panel to the
            // same entry — mirroring the Filtered ItemList's own itemOnSelRow row-click sync
            // further down (find `target = expansionAndIndexForEntry(tab, applyFilter = false, ...)`)
            // and the annotation-nav effect above — search matches are only ever computed against
            // the filtered item list (utils/LogSearch.kt), but the entry a match belongs to always
            // exists in the unfiltered one too, so the Original panel always has a target to follow
            // to. `var opened` accumulates across both panels within one invocation, same shape as
            // the annotation-nav effect's dual-target handling above, so a group toggled for one
            // panel isn't redundantly re-toggled resolving the other. allItemsVersion/tab.expanded
            // as keys (matching the annotation-nav effect, unlike the single-view search-nav effect
            // below, which has no Original panel to key on) is what makes the expand-then-scroll
            // sequence converge: toggling a group changes tab.expanded, restarting this effect —
            // the restart's own expansionAndIndexForEntry calls then find their targets already
            // visible with no further expansion needed, falling straight to centering instead of
            // looping.
            LaunchedEffect(searchNavigationRequest?.id, itemsVersion, allItemsVersion, tab.expanded) {
                val request = searchNavigationRequest?.takeIf { it.tabId == tab.id } ?: return@LaunchedEffect
                var opened = tab.expanded
                val filteredTarget = expansionAndIndexForEntry(tab, applyFilter = true, entryId = request.entryId, currentItems = items)
                if (filteredTarget != null) {
                    if (filteredTarget.expanded != opened) {
                        // A collapsed group had to open to reveal the match at all — a real
                        // enough change that centering (like any other reveal-and-jump) reads
                        // right, unlike the minimal-scroll branch below.
                        (filteredTarget.expanded - opened).forEach { gid -> onToggleGroup(gid) }
                        kotlinx.coroutines.delay(80)
                        opened = opened + filteredTarget.expanded
                        filteredLazyState.centerOnItem(filteredTarget.index)
                    } else {
                        // Already expanded: scroll only the minimum needed to keep navScrollMargin
                        // rows of context around the target (scrollForCursor), a no-op if it's
                        // already comfortably on screen — no top-then-recenter flash.
                        scrollForCursor(filteredLazyState, syncScope, filteredTarget.index, navScrollMargin)
                    }
                }
                val originalTarget = expansionAndIndexForEntry(tab, applyFilter = false, entryId = request.entryId, currentItems = allItems)
                if (originalTarget != null) {
                    // Original panel has its own independent selection (localAllSelected) — keep it
                    // in sync with the match too, same as a Filtered-panel row click does.
                    localAllSelected = setOf(request.entryId)
                    if (originalTarget.expanded != opened) {
                        (originalTarget.expanded - opened).forEach { gid -> onToggleGroup(gid) }
                        kotlinx.coroutines.delay(80)
                        opened = opened + originalTarget.expanded
                        allLazyState.centerOnItem(originalTarget.index)
                    } else {
                        scrollForCursor(allLazyState, syncScope, originalTarget.index, navScrollMargin)
                    }
                }
                onConsumeSearchNavigation(request.id)
            }

            // Fires once each time the split view is freshly opened (Compose disposes/recreates
            // this whole branch on every showUnfiltered toggle, so LaunchedEffect(Unit) re-runs
            // on every open) — centers both panels on whatever was already selected before
            // Unfiltered was pressed, instead of leaving the selection wherever it happened to
            // land in the preserved scroll position.
            LaunchedEffect(Unit) {
                val targetId = tab.selected.minOrNull() ?: return@LaunchedEffect
                // containerH starts at 0 and only gets its real value from the split Column's
                // own onGloballyPositioned, one or more frames after this branch first composes.
                // Until then, effectiveSplitDp/weight(1f) size both panels off that placeholder
                // 0 — a centerOnItem call landing before this settles computes its correction
                // against that transient, too-small viewport and never gets a chance to redo it
                // once the real (larger) size arrives, so it doesn't end up actually centered.
                snapshotFlow { containerH }.first { it > 0f }
                var opened = tab.expanded
                val originalTarget = expansionAndIndexForEntry(tab, applyFilter = false, entryId = targetId, currentItems = allItems)
                if (originalTarget != null) {
                    (originalTarget.expanded - opened).forEach { gid -> onToggleGroup(gid) }
                    if (originalTarget.expanded != opened) kotlinx.coroutines.delay(80)
                    opened = opened + originalTarget.expanded
                    allLazyState.centerOnItem(originalTarget.index)
                }
                val filteredTarget = expansionAndIndexForEntry(tab, applyFilter = true, entryId = targetId, currentItems = items)
                if (filteredTarget != null) {
                    (filteredTarget.expanded - opened).forEach { gid -> onToggleGroup(gid) }
                    if (filteredTarget.expanded != opened) kotlinx.coroutines.delay(80)
                    filteredLazyState.centerOnItem(filteredTarget.index)
                }
            }

            Column(
                Modifier.fillMaxWidth().weight(1f)
                    .onGloballyPositioned { containerH = it.size.height / density }
            ) {
                // Fixed height for Panel1 → cursor drag adds directly to splitDp → 1:1 tracking.
                Column(Modifier.fillMaxWidth().height(effectiveSplitDp.dp)) {
                    SectionBanner("Original — $totalCnt lines", tc.seq1, tc)
                    // Original panel anchors its own Δt width off localAllSelected — its OWN
                    // selection, independent of the Filtered panel's tab.selected below.
                    val originalTimeDeltaChars = rememberTimeDeltaChars(tab, localAllSelected, allItems)
                    ColHeader(
                        hasPidTid,
                        showRowNumbers = settings.showRowNumbers,
                        rowNumDigits = tab.logData.size.toString().length,
                        showTimeDelta = tab.showTimeDelta,
                        timeDeltaChars = originalTimeDeltaChars,
                    )
                    ItemList(
                        listItems = allItems,
                        listSummary = computedAllItems.summary,
                        boundsMap = allBoundsAbs,
                        posY = allBoxPosY,
                        effectiveTab = tab.copy(selected = localAllSelected),
                        itemOnSelRow = allOnSelRow,
                        itemOnSelRowRange = allOnSelRowRange,
                        itemOnSelectAll = allOnSelectAll,
                        itemOnClearSelection = allOnClearSelection,
                        itemOnCopySelection = { selectedIds -> onCopySelection?.invoke(selectedIds) },
                        itemsLoading = computedAllItems.loading,
                        itemOnCtxMenu = { id, x, y, sel -> onCtxMenu(id, x, y, sel, localAllSelected) },
                        panelKey = "${tab.id}:original",
                        listState = allLazyState,
                        timeDeltaChars = originalTimeDeltaChars,
                    )
                }
                VDivider { delta ->
                    val cur = if (splitDp < 0f) maxOf(50f, (containerH - 10f) / 2f) else splitDp
                    splitDp = (cur + delta).coerceIn(50f, (containerH - 60f).coerceAtLeast(100f))
                }
                // Panel2 fills the rest with weight(1f).
                // Clicking a row here scrolls the Original panel to the same entry.
                Column(Modifier.fillMaxWidth().weight(1f)) {
                    SectionBanner("Filtered — $visCnt lines", tc.ac, tc)
                    // Split view shows the Find bar over the Filtered panel only (not Original) —
                    // one search state per tab (see LogTab.search), no independent per-panel state
                    // in v1 (plan's explicit scope note in AppState.openSearch's doc comment).
                    if (tab.search.active) {
                        SearchBar(
                            search = tab.search,
                            onQueryChange = onSearchQueryChange,
                            onToggleCase = onSearchToggleCase,
                            onNext = onSearchNext,
                            onPrev = onSearchPrev,
                            // Same fix as the single-view branch below: Escape removes the
                            // focused find field entirely, so without an explicit refocus here
                            // keyboard focus falls off the tree and App.kt's root
                            // onPreviewKeyEvent/handleGlobalKey stops receiving Ctrl+F until a
                            // click restores focus somewhere — reusing this panel's own
                            // externalFr (wired to the Filtered ItemList below) fixes that.
                            onClose = {
                                onSearchClose()
                                runCatching { focusRequester?.requestFocus() }
                            },
                        )
                    }
                    val filteredTimeDeltaChars = rememberTimeDeltaChars(tab, tab.selected, items)
                    ColHeader(
                        hasPidTid,
                        showRowNumbers = settings.showRowNumbers,
                        rowNumDigits = tab.logData.size.toString().length,
                        showTimeDelta = tab.showTimeDelta,
                        timeDeltaChars = filteredTimeDeltaChars,
                    )
                    ItemList(
                        listItems = items,
                        listSummary = computedItems.summary,
                        boundsMap = rowBoundsAbs,
                        posY = boxPosY,
                        itemOnSelRow = { id, multi, range ->
                            onSelRow(id, multi, range)
                            if (!multi && !range) {
                                localAllSelected = setOf(id)
                                val target = expansionAndIndexForEntry(tab, applyFilter = false, entryId = id, currentItems = allItems)
                                if (target != null) syncScope.launch {
                                    (target.expanded - tab.expanded).forEach { gid -> onToggleGroup(gid) }
                                    if (target.expanded != tab.expanded) kotlinx.coroutines.delay(80)
                                    allLazyState.centerOnItem(target.index)
                                }
                            }
                        },
                        itemOnSelRowRange = { ids ->
                            onSelRowRange(ids)
                            localAllSelected = ids.toSet()
                        },
                        panelKey = "${tab.id}:main",
                        listState = filteredLazyState,
                        externalFr = focusRequester,
                        onFocusChangedExternal = onPanelFocusChanged,
                        timeDeltaChars = filteredTimeDeltaChars,
                    )
                }
            }
        } else {
            val mainLazyState = scrollStates.lazyState("${tab.id}:main")
            val searchNavScope = rememberCoroutineScope()
            LaunchedEffect(annotationNavigationRequest?.id, itemsVersion) {
                val request = annotationNavigationRequest?.takeIf { it.tabId == tab.id } ?: return@LaunchedEffect
                val target = request.logIds.firstNotNullOfOrNull { entryId ->
                    expansionAndIndexForEntry(tab, applyFilter = true, entryId = entryId, currentItems = items)
                }
                if (target != null) {
                    (target.expanded - tab.expanded).forEach { gid -> onToggleGroup(gid) }
                    if (target.expanded != tab.expanded) kotlinx.coroutines.delay(80)
                    mainLazyState.centerOnItem(target.index)
                }
                onConsumeAnnotationNavigation(request.id)
            }
            // Find bar's own navigation channel — see SearchNavigationRequest's doc comment and
            // the split-view LaunchedEffect above for why this stays separate from
            // annotationNavigationRequest and scrolls minimally instead of always centering.
            LaunchedEffect(searchNavigationRequest?.id, itemsVersion) {
                val request = searchNavigationRequest?.takeIf { it.tabId == tab.id } ?: return@LaunchedEffect
                val target = expansionAndIndexForEntry(tab, applyFilter = true, entryId = request.entryId, currentItems = items)
                if (target != null) {
                    if (target.expanded != tab.expanded) {
                        (target.expanded - tab.expanded).forEach { gid -> onToggleGroup(gid) }
                        kotlinx.coroutines.delay(80)
                        mainLazyState.centerOnItem(target.index)
                    } else {
                        scrollForCursor(mainLazyState, searchNavScope, target.index, navScrollMargin)
                    }
                }
                onConsumeSearchNavigation(request.id)
            }
            if (tab.search.active) {
                SearchBar(
                    search = tab.search,
                    onQueryChange = onSearchQueryChange,
                    onToggleCase = onSearchToggleCase,
                    onNext = onSearchNext,
                    onPrev = onSearchPrev,
                    // Escape (SearchBar's own key handler) closes and returns focus to the log
                    // row list — reusing this view's own externalFr rather than a second
                    // FocusRequester the caller would otherwise need to hoist and manage.
                    onClose = {
                        onSearchClose()
                        runCatching { focusRequester?.requestFocus() }
                    },
                )
            }
            val mainTimeDeltaChars = rememberTimeDeltaChars(tab, tab.selected, items)
            ColHeader(
                hasPidTid,
                showRowNumbers = settings.showRowNumbers,
                rowNumDigits = tab.logData.size.toString().length,
                showTimeDelta = tab.showTimeDelta,
                timeDeltaChars = mainTimeDeltaChars,
            )
            ItemList(
                items, computedItems.summary, rowBoundsAbs, boxPosY,
                panelKey = "${tab.id}:main", listState = mainLazyState,
                externalFr = focusRequester, onFocusChangedExternal = onPanelFocusChanged,
                timeDeltaChars = mainTimeDeltaChars,
            )
        }
    }
}

// Centers `index` in the viewport instead of just scrolling it into view. Scrolling to a fixed
// "-N rows" margin above the target only approximates centering and drifts whenever rows have
// different heights (a SeqHeader vs. a plain Row) or the viewport is resized.
//
// Deliberately avoids scrollBy() for the correction: scrollToItem(index) has one unambiguous,
// documented effect — the given index lands at the very top of the viewport — but a follow-up
// scrollBy(delta) computed from that turned out to move in the wrong direction in practice
// (verified against screenshots: the target consistently ended up pinned at the *bottom* edge
// instead of centered, and got worse the more it retried). Rather than re-guess scrollBy's sign
// convention, this only ever calls scrollToItem: first on the real target to measure an actual
// row height from whatever's now visible, then again on an *earlier* index offset back by roughly
// half a viewport's worth of rows — since that earlier index lands at the top, the real target
// naturally ends up near the middle. Approximate (row heights vary) but always in the right
// direction, which a screen-relative correction was not.
internal fun centerAnchorIndex(index: Int, viewportHeight: Int, visibleItemSizes: List<Int>): Int {
    if (visibleItemSizes.isEmpty()) return index
    val avgRowHeight = visibleItemSizes.sum() / visibleItemSizes.size
    if (avgRowHeight <= 0) return index
    val rowsToHalfViewport = (viewportHeight / 2) / avgRowHeight
    return (index - rowsToHalfViewport).coerceAtLeast(0)
}

private suspend fun LazyListState.centerOnItem(index: Int) {
    scrollToItem(index)
    withFrameNanos { }
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return
    val viewportHeight = info.viewportEndOffset - info.viewportStartOffset
    val anchorIndex = centerAnchorIndex(index, viewportHeight, visible.map { it.size })
    if (anchorIndex != index) scrollToItem(anchorIndex)
}

// Ranks collapsed-header candidates (gid to the header's own log entry id) by how likely each is
// to actually contain `entryId`, cheapest test first. Sequences, nested sub-sequences, stack-trace
// groups, and manual TO_END blocks all cover lines *after* their own header, so the nearest
// preceding header is the most likely match; manual TO_START blocks are the one backward-covering
// exception, so the nearest *following* header is tried next. This is a pure ordering hint, not a
// filter — the caller still verifies each candidate and falls through the full list if the
// top-ranked guess is wrong. On a real bug-report log with hundreds of collapsed groups, blindly
// testing them in arbitrary order (the original approach) could mean thousands of expensive
// recomputes to reveal one deeply-buried target — this typically finds the right one on the first
// or second try instead.
internal fun rankCollapsedHeadersByProximity(headers: List<Pair<String, Int>>, entryId: Int): List<String> {
    val (preceding, following) = headers.partition { (_, headerId) -> headerId <= entryId }
    return preceding.sortedByDescending { it.second }.map { it.first } + following.sortedBy { it.second }.map { it.first }
}

// Keeps `margin` rows of context visible around the cursor (like vim's scrolloff): the
// highlight moves freely within that window with no scrolling, and only once it would land
// within `margin` rows of the top/bottom edge does the viewport scroll to restore the margin.
// Uses an immediate (non-animated) scrollToItem: animateScrollToItem takes several frames to
// settle, but the selection highlight switches to the new row instantly, so the multi-frame
// animation produced a visible gap where the old row had already lost its highlight but the
// new row hadn't scrolled into view yet. An immediate jump keeps the highlight continuously
// visible across the scroll.
private fun scrollForCursor(lazyState: LazyListState, scope: CoroutineScope, targetItemsIdx: Int, margin: Int) {
    val visible = lazyState.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) {
        scope.launch { lazyState.scrollToItem(maxOf(0, targetItemsIdx - margin)) }
        return
    }
    val firstVisible = visible.first().index
    val lastVisible = visible.last().index
    val target = when {
        targetItemsIdx < firstVisible + margin -> (targetItemsIdx - margin).coerceAtLeast(0)
        targetItemsIdx > lastVisible - margin -> (targetItemsIdx + margin - visible.size + 1).coerceAtLeast(0)
        else -> return
    }
    if (target == firstVisible) return
    scope.launch { lazyState.scrollToItem(target) }
}

// Bundles the keyboard selection's anchor (fixed end of a shift-extend range) and cursor
// (moving end). The cursor is tracked explicitly rather than re-derived from the selection set
// on every keystroke: tab.selected.maxOrNull() only identifies the moving end while extending
// downward — extend upward (past the anchor) and it locks onto the anchor instead, which gets
// the selection stuck or makes it jump when the direction reverses. The explicit cursorId is
// trusted as long as it's still part of the current selection; otherwise (a plain mouse click,
// a tab switch, Select All, ...) it falls back to the largest selected id.
private class SelectionCursor(
    val anchorId: Int?,
    val cursorId: Int?,
    val onAnchorChange: (Int?) -> Unit,
    val onCursorChange: (Int?) -> Unit,
) {
    fun effectiveCursorId(tab: LogTab): Int? =
        cursorId?.takeIf { it in tab.selected } ?: tab.selected.maxOrNull()

    fun reset() {
        onAnchorChange(null)
        onCursorChange(null)
    }
}

// P-05: id->position via ItemsSummary's sorted id arrays (O(log n)) instead of re-scanning
// rows/items on every keypress (previously O(n), O(n^2) in the no-prior-cursor fallback).
// Extracted as a pure, internal function — shared by handleNavKey/handleSelKey (previously two
// near-identical copies of this same logic) and directly unit-testable without needing to
// construct a Compose KeyEvent/LazyListState/CoroutineScope.
internal fun cursorRowIndex(cursorEntryId: Int?, firstVisibleItemIndex: Int, items: List<LogItem>, summary: ItemsSummary): Int {
    if (cursorEntryId != null) return summary.rowIds.indexOfId(cursorEntryId).coerceAtLeast(0)
    val firstRowId = (firstVisibleItemIndex until items.size).asSequence()
        .map { items[it] }.filterIsInstance<LogItem.Row>().firstOrNull()?.entry?.id
    return firstRowId?.let { summary.rowIds.indexOfId(it) }?.coerceAtLeast(0) ?: 0
}

private fun handleNavKey(
    ev: KeyEvent,
    items: List<LogItem>,
    tab: LogTab,
    lazyState: LazyListState,
    scope: CoroutineScope,
    scrollMargin: Int,
    cursor: SelectionCursor,
    summary: ItemsSummary,
    onSelectRow: (Int) -> Unit,
): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    if (summary.rowCount == 0) return false

    fun cursorIdx(): Int = cursorRowIndex(cursor.effectiveCursorId(tab), lazyState.firstVisibleItemIndex, items, summary)

    // summary.rowIds[i] is the i-th LogItem.Row's entry id in display order — summarizeItems
    // builds it by walking `items` picking out just the Row entries, and spliceSummarize
    // preserves that shape — so row-index math runs on the IntArray directly instead of
    // materializing a Row-only list of (potentially) millions of items on every keypress (P-02).
    fun moveTo(rowIdx: Int) {
        val i = rowIdx.coerceIn(0, summary.rowCount - 1)
        cursor.onAnchorChange(null)
        val id = summary.rowIds[i]
        // Always replace the selection outright (never toggle): keyboard nav must stay
        // idempotent even if the same target row is selected again by a duplicate key event.
        onSelectRow(id)
        cursor.onCursorChange(id)
        scrollForCursor(lazyState, scope, summary.allIds.indexOfId(id), scrollMargin)
    }

    return when {
        (ev.isMetaPressed || ev.isCtrlPressed) && ev.key == Key.DirectionUp   -> { moveTo(0); true }
        (ev.isMetaPressed || ev.isCtrlPressed) && ev.key == Key.DirectionDown -> { moveTo(summary.rowCount - 1); true }
        ev.key == Key.MoveHome   -> { moveTo(0); true }
        ev.key == Key.MoveEnd    -> { moveTo(summary.rowCount - 1); true }
        ev.key == Key.DirectionUp   && !ev.isShiftPressed -> { moveTo(cursorIdx() - 1); true }
        ev.key == Key.DirectionDown && !ev.isShiftPressed -> { moveTo(cursorIdx() + 1); true }
        ev.key == Key.PageUp     && !ev.isShiftPressed -> { moveTo(cursorIdx() - PAGE_JUMP_ROWS); true }
        ev.key == Key.PageDown   && !ev.isShiftPressed -> { moveTo(cursorIdx() + PAGE_JUMP_ROWS); true }
        else -> false
    }
}

private data class SelKeyActions(
    val onSelectAll: (() -> Unit)?,
    val onClearSelection: (() -> Unit)?,
    val onCopySelection: ((Set<Int>?) -> Unit)?,
)

internal fun panelCopySelectionIds(tab: LogTab): Set<Int> = tab.selected

private fun handleSelKey(
    ev: KeyEvent,
    items: List<LogItem>,
    tab: LogTab,
    lazyState: LazyListState,
    scope: CoroutineScope,
    scrollMargin: Int,
    onSelRowRange: (List<Int>) -> Unit,
    cursor: SelectionCursor,
    summary: ItemsSummary,
    actions: SelKeyActions,
): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    val isAction = if (isMacOs) ev.isMetaPressed else ev.isCtrlPressed

    fun cursorIdx(): Int = cursorRowIndex(cursor.effectiveCursorId(tab), lazyState.firstVisibleItemIndex, items, summary)

    // See handleNavKey's moveTo — row-index math runs on summary.rowIds directly, never
    // materializing a Row-only list per keypress (P-02).
    fun extendTo(newRowIdx: Int) {
        if (summary.rowCount == 0) return
        val clamped = newRowIdx.coerceIn(0, summary.rowCount - 1)
        val target = summary.rowIds[clamped]
        val anchor = cursor.anchorId ?: tab.selected.minOrNull() ?: target
        cursor.onAnchorChange(anchor)
        cursor.onCursorChange(target)
        val anchorIdx = summary.rowIds.indexOfId(anchor).coerceAtLeast(0)
        val lo = minOf(anchorIdx, clamped)
        val hi = maxOf(anchorIdx, clamped)
        onSelRowRange((lo..hi).map { summary.rowIds[it] })
        scrollForCursor(lazyState, scope, summary.allIds.indexOfId(target), scrollMargin)
    }

    return when {
        ev.isShiftPressed && ev.key == Key.DirectionUp   -> { extendTo(cursorIdx() - 1); true }
        ev.isShiftPressed && ev.key == Key.DirectionDown -> { extendTo(cursorIdx() + 1); true }
        ev.isShiftPressed && ev.key == Key.PageUp        -> { extendTo(cursorIdx() - PAGE_JUMP_ROWS); true }
        ev.isShiftPressed && ev.key == Key.PageDown      -> { extendTo(cursorIdx() + PAGE_JUMP_ROWS); true }
        isAction && ev.key == Key.A -> { cursor.reset(); actions.onSelectAll?.invoke(); true }
        isAction && ev.key == Key.C -> { actions.onCopySelection?.invoke(panelCopySelectionIds(tab)); true }
        ev.key == Key.Escape        -> { cursor.reset(); actions.onClearSelection?.invoke(); true }
        else -> false
    }
}

@Composable
private fun LogRow(
    item: LogItem.Row,
    tab: LogTab,
    mono: FontFamily,
    tc: ThemeColors,
    wrapLimitChars: Int,
    onSelRow: (Int, Boolean, Boolean) -> Unit,
    onCtxMenu: (Int, Float, Float, String) -> Unit,
    onSelectedTextChange: (String) -> Unit,
    rowBoundsAbs: HashMap<Int, Pair<Float, Float>>,
    regexContext: RegexEvaluationContext,
    highlightEntireCrashGroup: Boolean = false,
    // Auto mode's whole point is "use the real available width" — BasicTextField's own softWrap
    // already does that with zero estimation error, using the real font metrics Skia will render
    // with. Pre-wrapping with an estimated wrapLimitChars (as Manual mode deliberately does, for
    // its fixed-chars-per-line preference) instead introduced compounding estimation error here:
    // wherever the estimate was even slightly off, the manually-inserted break landed before the
    // real available width was used up, so the line wrapped one row earlier than necessary.
    autoWrap: Boolean = false,
    showRowNumbers: Boolean = false,
    showTimeDelta: Boolean = false,
    // Precomputed by the caller (LazyColumn's itemsIndexed row lambda), which is the one place
    // that has both listItems[index - 1] AND the selected-line anchor logic (deltaAnchorId) —
    // null when unavailable (showTimeDelta off, no baseline/anchor to compare against, or either
    // side's ts didn't parse; see utils/LogTime.kt.deltaMillis). Deliberately NOT folded into
    // buildFullLineAnnotation: that AnnotatedString is what gets copied to the clipboard, what Find
    // matching/highlighters read via visibleLogLineText, and what flows into Markdown exports — Δt
    // is derived UI-only data and must stay out of all three, so it's rendered as this separate
    // gutter cell instead.
    deltaMs: Long? = null,
    // true when deltaMs is a SIGNED offset from the selected line (a row is selected in this tab)
    // rather than the ordinary gap to the previous visible row. Changes both the formatting
    // (formatSignedDelta, so the selected row itself reads as bare "0.000" rather than "+0.000")
    // and suppresses the stall-warning tint below — a row simply being far from the selection
    // isn't a "stall," so lighting up half the column in warning color would be noise, not signal.
    deltaSelectionAnchored: Boolean = false,
    // Δt column width in characters — precomputed ONCE per tab/mode by the caller
    // (LogViewer.kt's rememberTimeDeltaChars) from the widest delta string this tab/mode will
    // actually render, not recomputed per row. Mirrors rowNumDigits' role for the row-number
    // gutter above: same shape, same "caller derives the digit/char count once, every row and the
    // header both consume it" split.
    timeDeltaChars: Int = 1,
    searchHighlight: SearchHighlight? = null,
) {
    val density  = LocalDensity.current.density
    val entry    = item.entry
    val isSel    = entry.id in tab.selected
    var hov      by remember { mutableStateOf(false) }
    var rowRoot  by remember { mutableStateOf(Offset.Zero) }
    var sel      by remember(tab.id, entry.id) { mutableStateOf(TextRange.Zero) }
    val latestIsSelected by rememberUpdatedState(isSel)
    val latestSelection by rememberUpdatedState(sel)
    val fontSize = baseSp()
    DisposableEffect(entry.id, rowBoundsAbs) {
        onDispose { rowBoundsAbs.remove(entry.id) }
    }

    val isCrashGroupRow = isCrashGroupRow(item.groupColor, highlightEntireCrashGroup)
    val annoLine = remember(
        tab.id, entry, tab.filter, tc.td, tc.ts, tc.tx, wrapLimitChars, isCrashGroupRow, autoWrap, searchHighlight,
    ) {
        val tagColor = if (isCrashGroupRow) DANGER_RED else tc.ts
        val msgColor = if (isCrashGroupRow) DANGER_RED else tc.tx
        val built = buildFullLineAnnotation(
            entry,
            tab.filter.highlighters,
            tc.td,
            tc.td.copy(0.5f),
            tagColor,
            msgColor,
            tab.filter,
            regexContext,
            searchHighlight,
        )
        if (autoWrap) built else visualLogLineForWrapLimit(built, wrapLimitChars)
    }

    val levelColor = entry.level.defaultColor
    val bg = when {
        isSel -> tc.sl
        isCrashGroupRow -> DANGER_RED.copy(alpha = if (hov) 0.15f else 0.07f)
        hov -> tc.hv
        else -> Color.Transparent
    }
    val groupColor = item.groupColor

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 22.dp)
            .pointerHoverIcon(PointerIcon(AwtCursor.getDefaultCursor()), overrideDescendants = true)
            .background(bg)
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                rowBoundsAbs[entry.id] = pos.y to (pos.y + coords.size.height)
                rowRoot = pos
            }
            // Keys include tab.id so coroutines restart when the same entry ID appears in a different tab
            .pointerInput("rc", tab.id, entry.id) {
                val clickScope = CoroutineScope(coroutineContext)
                awaitPointerEventScope {
                    var pressPos: Offset? = null
                    var pressShift = false
                    var pressMulti = false
                    var pressDragged = false
                    var pendingSelectedRowToggle: Job? = null
                    var lastPrimaryPressMs = 0L
                    var lastPrimaryPressPos = Offset.Unspecified
                    var doubleClickInProgress = false
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        when (ev.type) {
                            PointerEventType.Press -> {
                                val mods = ev.keyboardModifiers
                                if (ev.buttons.isSecondaryPressed) {
                                    pendingSelectedRowToggle?.cancel()
                                    ev.changes.forEach { it.consume() }
                                    val selText = if (!sel.collapsed)
                                        runCatching {
                                            stripVisualWrapBreaks(annoLine.text.substring(sel.min, sel.max))
                                        }.getOrElse { "" }
                                    else ""
                                    val ch = ev.changes.firstOrNull() ?: continue
                                    onCtxMenu(
                                        entry.id,
                                        (rowRoot.x + ch.position.x) / density,
                                        (rowRoot.y + ch.position.y) / density,
                                        selText,
                                    )
                                } else if (ev.buttons.isPrimaryPressed) {
                                    // A selected row's first click in a double-click sequence would
                                    // otherwise deselect it before BasicTextField can select the
                                    // word. Delay only that plain selected-row toggle; a second
                                    // press cancels it, while an ordinary single click still
                                    // deselects after the normal desktop double-click interval.
                                    pendingSelectedRowToggle?.cancel()
                                    val position = ev.changes.firstOrNull()?.position
                                    val now = System.currentTimeMillis()
                                    doubleClickInProgress = position != null &&
                                        lastPrimaryPressPos != Offset.Unspecified &&
                                        now - lastPrimaryPressMs <= DOUBLE_CLICK_WINDOW_MS &&
                                        (position - lastPrimaryPressPos).getDistance() <= 10f
                                    lastPrimaryPressMs = now
                                    if (position != null) lastPrimaryPressPos = position
                                    pressPos = position
                                    pressShift = mods.isShiftPressed
                                    pressMulti = mods.isCtrlPressed || mods.isMetaPressed
                                    pressDragged = false
                                }
                            }
                            PointerEventType.Move -> {
                                val start = pressPos
                                val current = ev.changes.firstOrNull()?.position
                                if (start != null && current != null && (current - start).getDistance() > 4f) {
                                    pressDragged = true
                                }
                            }
                            PointerEventType.Release -> {
                                // sel reflects whatever BasicTextField's own gesture handling did
                                // with THIS click by the time its Release arrives here — text
                                // selection (double-click-to-select-word, in particular) happens on
                                // the PRESS half of a click, not the release, so by Release time
                                // `sel` already carries the new selection Press produced. A plain
                                // click (cursor placement, no drag) always ends up COLLAPSED, so
                                // this doesn't change ordinary click-to-select-row behavior at all.
                                //
                                // Without the sel.collapsed check: a double-click to select a word
                                // is, from this handler's point of view, two ordinary clicks in
                                // quick succession. AppState.selRow TOGGLES an already-selected row
                                // off on a repeat plain click — so click 1 selected the row, click 2
                                // (the second half of the double-click) immediately deselected it
                                // again, and the Δt column's anchor mode flipped on then off inside
                                // one user gesture — visible as the flicker this guards against.
                                if (!doubleClickInProgress && !pressDragged && pressPos != null && latestSelection.collapsed) {
                                    if (latestIsSelected && !pressMulti && !pressShift) {
                                        pendingSelectedRowToggle = clickScope.launch {
                                            kotlinx.coroutines.delay(DOUBLE_CLICK_WINDOW_MS)
                                            onSelRow(entry.id, false, false)
                                        }
                                    } else {
                                        onSelRow(entry.id, pressMulti, pressShift)
                                    }
                                }
                                pressPos = null
                                pressShift = false
                                pressMulti = false
                                pressDragged = false
                                doubleClickInProgress = false
                            }
                            else -> {}
                        }
                    }
                }
            }
            .pointerInput("hd", tab.id, entry.id) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Final)
                        when (ev.type) {
                            PointerEventType.Enter -> hov = true
                            PointerEventType.Exit  -> hov = false
                            else -> {}
                        }
                    }
                }
            }
            // Level-coloured left edge stripe
            .drawBehind {
                drawRect(levelColor.copy(alpha = if (isSel) 0.7f else 0.35f), topLeft = Offset.Zero, size = Size(3f, size.height))
                if (groupColor != null && item.indent > 0) {
                    val x = 6.dp.toPx() + ((item.indent - 1).coerceAtLeast(0) * INDENT_STEP.toPx())
                    drawRect(groupColor.copy(alpha = 0.85f), topLeft = Offset(x, 0f), size = Size(2f, size.height))
                }
            }
            // Keep the optional gutters in a stable, file-wide column. Nesting indentation belongs
            // to the log line itself, not to the row-number/Δt gutter; otherwise the same settings
            // appear to create different gaps in different files depending on the visible group.
            .padding(start = ROW_START_PAD, end = 8.dp, top = ROW_V_PAD, bottom = ROW_V_PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Optional left gutter showing the row's original (parse-order) row number — entry.id,
        // which is stable under filtering/folding so it always points at the same spot in the full
        // file. Left-aligned at the stable gutter origin; group/fold headers deliberately omit it
        // and span the gutter (like an IDE's fold-region header).
        if (showRowNumbers) {
            // Size the number column to the widest row number in this tab (see ColHeader's "#" cell
            // for the matching header-side width), so a small-/mid-size log gets a tight gutter that
            // hugs the left edge instead of a fixed-width cell the number floats inside.
            val numColWidth = rowNumberColumnWidth(fontSize.value, tab.logData.size.toString().length)
            Box(Modifier.width(numColWidth + ROW_NUM_GAP).padding(end = ROW_NUM_GAP)) {
                AppText(
                    entry.id.toString(),
                    color = tc.td, fontSize = fontSize, fontFamily = mono, maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        }
        // Optional left gutter (immediately after the row-number gutter, when both are on) showing
        // either the signed offset from the selected line (deltaSelectionAnchored) or the gap to
        // the previous VISIBLE row — see LogRow's deltaMs/deltaSelectionAnchored param docs. "—"
        // marks no baseline (first visible row with no selection, or either side's ts unparseable
        // — never invented as a zero). A gap over DELTA_WARN_THRESHOLD_MS is tinted the same
        // warning color as a W-level row, but only in gap mode — see deltaSelectionAnchored's doc
        // for why that tint is suppressed once the column means "distance from selection" instead.
        //
        // LEFT-aligned, flush with this Box's own start (no leading padding) — deliberately, so the
        // Δt VALUE's own left edge lands exactly where row content starts when the column is
        // hidden (enabling Δt must not invent a new left margin). The box's own width still
        // reserves deltaColWidth + ROW_NUM_GAP, same total footprint as the row-number gutter uses;
        // since timeDeltaChars already sizes deltaColWidth to the widest string this tab/mode
        // actually renders, that width is mostly filled by the text itself, and the small
        // difference (ROW_NUM_GAP) becomes the trailing gap before whatever comes next — the same
        // gap the row-number gutter gets, just achieved by leaving the end open instead of an
        // explicit end-padding, because THIS text is left- not right-aligned.
        if (showTimeDelta) {
            val deltaColWidth = timeDeltaColumnWidth(fontSize.value, timeDeltaChars)
            Box(Modifier.width(deltaColWidth + ROW_NUM_GAP)) {
                AppText(
                    deltaMs?.let { if (deltaSelectionAnchored) formatSignedDelta(it) else formatDelta(it) } ?: "—",
                    color = if (!deltaSelectionAnchored && deltaMs != null && deltaMs >= DELTA_WARN_THRESHOLD_MS) {
                        LogLevel.W.defaultColor
                    } else {
                        tc.td
                    },
                    fontSize = fontSize, fontFamily = mono, maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        }
        if (item.indent > 0) {
            Spacer(Modifier.width(INDENT_STEP * item.indent))
        }
        // Only merged tabs (utils/LogMerge.kt) ever set sourceTag — a small pinned (non-scrolling)
        // badge naming which original file a row came from, since a merged tab otherwise gives no
        // visual way to tell which buffer (main/system/crash/...) a given line was in.
        entry.sourceTag?.let { tag ->
            AppText(
                tag, color = tc.td, fontSize = 9.sp, fontFamily = mono, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 70.dp).padding(end = 4.dp),
            )
        }
        BasicTextField(
            value = TextFieldValue(annotatedString = annoLine, selection = sel),
            onValueChange = { new ->
                sel = new.selection
                val selectedText = if (!new.selection.collapsed) {
                    runCatching {
                        stripVisualWrapBreaks(annoLine.text.substring(new.selection.min, new.selection.max))
                    }.getOrElse { "" }
                } else {
                    ""
                }
                onSelectedTextChange(selectedText)
            },
            readOnly = true,
            singleLine = false,
            textStyle = TextStyle(color = tc.tx, fontFamily = mono, fontSize = fontSize, lineHeight = (fontSize.value + 4).sp),
            cursorBrush = SolidColor(Color.Transparent),
            modifier = Modifier.weight(1f).heightIn(min = 18.dp),
        )
    }
}

// Shared expand/collapse toggle for SeqHeaderRow/ManualHeaderRow/StackTraceHeaderRow — a rounded
// hover background gives it the same "clickable chip" affordance as other icon buttons in the app
// (e.g. HoverBox usages elsewhere), instead of a bare glyph with no feedback until the click lands.
@Composable
private fun CollapseChevron(expanded: Boolean, color: Color, mono: FontFamily, onClick: () -> Unit) {
    HoverBox(
        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)),
        hoverBg = color.copy(alpha = 0.18f),
        onClick = onClick,
    ) {
        AppText(
            if (expanded) "▼" else "▶",
            color = color,
            fontSize = 14.sp,
            fontFamily = mono,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

// Shared Δt gutter cell for the three group/collapse header row types below (SeqHeaderRow/
// ManualHeaderRow/StackTraceHeaderRow) — rendered "exactly as a plain row does": same width
// formula (timeDeltaColumnWidth), same left alignment and warn-tint rule, same "—" no-baseline
// placeholder, using the header's OWN entry (its ts is what deltaMs was already computed against
// upstream, same as any other item — see the itemsIndexed lambda's shared deltaMs block).
//
// Deliberately positioned as the FIRST element in each header's Row, before even the collapse
// chevron — that's what makes its left edge land at the same x as LogRow's own Δt cell (which is
// also the first thing after the row's own start-pad, when showRowNumbers is off).
//
// This is a real, if narrow, divergence from the row-number gutter's treatment of headers:
// row-number gutter stays entirely OMITTED/spanned here (unchanged — headers never show row
// numbers, regardless of settings.showRowNumbers), but the Δt cell is NOT spanned — it renders a
// real value. One consequence: if a user has BOTH showRowNumbers AND showTimeDelta on, a plain
// row's Δt cell sits to the right of its row-number gutter, while a header's Δt cell (having no
// row-number gutter to sit after) stays flush left — the two Δt columns won't perfectly x-align in
// that specific combined-settings case. Accepted rather than also reserving a phantom row-number-
// width spacer here, which would have meant reintroducing the row-number gutter's own layout for
// headers just to keep two settings that are usually used one-at-a-time in perfect lockstep.
@Composable
private fun HeaderTimeDeltaCell(
    showTimeDelta: Boolean,
    deltaMs: Long?,
    deltaSelectionAnchored: Boolean,
    timeDeltaChars: Int,
    mono: FontFamily,
    tc: ThemeColors,
) {
    if (!showTimeDelta) return
    val fontSize = baseSp()
    val deltaColWidth = timeDeltaColumnWidth(fontSize.value, timeDeltaChars)
    Box(Modifier.width(deltaColWidth + ROW_NUM_GAP)) {
        AppText(
            deltaMs?.let { if (deltaSelectionAnchored) formatSignedDelta(it) else formatDelta(it) } ?: "—",
            color = if (!deltaSelectionAnchored && deltaMs != null && deltaMs >= DELTA_WARN_THRESHOLD_MS) {
                LogLevel.W.defaultColor
            } else {
                tc.td
            },
            fontSize = fontSize, fontFamily = mono, maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}

@Composable
private fun SeqHeaderRow(
    item: LogItem.SeqHeader,
    tab: LogTab,
    mono: FontFamily,
    tc: ThemeColors,
    onSelRow: (Int, Boolean, Boolean) -> Unit,
    onCtxMenu: (Int, Float, Float, String) -> Unit,
    onToggleGroup: (String) -> Unit,
    rowBoundsAbs: HashMap<Int, Pair<Float, Float>>,
    // Header rows render tag/msg as plain AppText, not an AnnotatedString built by
    // buildFullLineAnnotation, so a Find match here gets a whole-row background tint instead of
    // the per-substring span a LogRow match gets — a coarser but much simpler way to still surface
    // "this header's line matched" (computeSearchMatches walks header entries too, see
    // utils/LogSearch.kt) without reworking every header composable onto AnnotatedString rendering.
    isSearchMatch: Boolean = false,
    isCurrentSearchMatch: Boolean = false,
    showTimeDelta: Boolean = false,
    deltaMs: Long? = null,
    deltaSelectionAnchored: Boolean = false,
    timeDeltaChars: Int = 1,
) {
    val density = LocalDensity.current.density
    val sc  = item.color
    val isSel = item.entry.id in tab.selected
    var hov by remember { mutableStateOf(false) }
    var rowRoot by remember { mutableStateOf(Offset.Zero) }
    var lastClickMs by remember { mutableStateOf(0L) }
    DisposableEffect(item.entry.id, rowBoundsAbs) {
        onDispose { rowBoundsAbs.remove(item.entry.id) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(when {
                isSel -> tc.sl
                isCurrentSearchMatch -> tc.searchCurrentBg
                isSearchMatch -> tc.searchMatchBg
                hov -> sc.copy(.15f)
                else -> sc.copy(.07f)
            })
            .drawBehind {
                val guideX = item.indent * INDENT_STEP.toPx()
                drawRect(sc, topLeft = Offset(guideX, 0f), size = Size(4f, size.height))
            }
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                rowBoundsAbs[item.entry.id] = pos.y to (pos.y + coords.size.height)
                rowRoot = pos
            }
            .pointerInput("hd", tab.id, item.gid) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        when (ev.type) {
                            PointerEventType.Enter -> hov = true
                            PointerEventType.Exit  -> hov = false
                            PointerEventType.Press -> {
                                val mods = ev.keyboardModifiers
                                when {
                                    ev.buttons.isSecondaryPressed -> {
                                        ev.changes.forEach { it.consume() }
                                        val ch = ev.changes.firstOrNull() ?: continue
                                        onCtxMenu(
                                            item.entry.id,
                                            (rowRoot.x + ch.position.x) / density,
                                            (rowRoot.y + ch.position.y) / density,
                                            "",
                                        )
                                    }
                                    ev.buttons.isPrimaryPressed && (mods.isShiftPressed || mods.isCtrlPressed || mods.isMetaPressed) -> {
                                        ev.changes.forEach { it.consume() }
                                        onSelRow(item.entry.id, mods.isCtrlPressed || mods.isMetaPressed, mods.isShiftPressed)
                                    }
                                    ev.buttons.isPrimaryPressed -> {
                                        val now = System.currentTimeMillis()
                                        if (now - lastClickMs < 350) onToggleGroup(item.gid)
                                        else onSelRow(item.entry.id, false, false)
                                        lastClickMs = now
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
            .padding(start = ROW_START_PAD + INDENT_STEP * item.indent, end = 8.dp, top = ROW_V_PAD, bottom = ROW_V_PAD),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HeaderTimeDeltaCell(showTimeDelta, deltaMs, deltaSelectionAnchored, timeDeltaChars, mono, tc)
        CollapseChevron(expanded = item.expanded, color = sc, mono = mono, onClick = { onToggleGroup(item.gid) })
        AppText("${item.entry.ts}  ${item.entry.level.key}", color = sc.copy(.7f), fontSize = 11.sp, fontFamily = mono)
        AppText("${item.entry.tag}:", color = sc, fontSize = 11.sp, fontFamily = mono,
            modifier = Modifier.widthIn(min = 120.dp, max = 520.dp), overflow = TextOverflow.Clip)
        AppText(item.entry.msg, color = sc, fontSize = 12.sp, fontFamily = mono, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f), overflow = TextOverflow.Clip)
        if (!item.expanded) AppText("${item.count} entries", color = sc.copy(.6f), fontSize = 11.sp)
    }
}

@Composable
private fun ManualHeaderRow(
    item: LogItem.ManualHeader,
    tab: LogTab,
    mono: FontFamily,
    tc: ThemeColors,
    onSelRow: (Int, Boolean, Boolean) -> Unit,
    onCtxMenu: (Int, Float, Float, String) -> Unit,
    onToggleGroup: (String) -> Unit,
    rowBoundsAbs: HashMap<Int, Pair<Float, Float>>,
    isSearchMatch: Boolean = false,
    isCurrentSearchMatch: Boolean = false,
    showTimeDelta: Boolean = false,
    deltaMs: Long? = null,
    deltaSelectionAnchored: Boolean = false,
    timeDeltaChars: Int = 1,
) {
    val density = LocalDensity.current.density
    val sc = item.color
    val isSel = item.entry.id in tab.selected
    var hov by remember { mutableStateOf(false) }
    var rowRoot by remember { mutableStateOf(Offset.Zero) }
    var lastClickMs by remember { mutableStateOf(0L) }
    DisposableEffect(item.entry.id, rowBoundsAbs) {
        onDispose { rowBoundsAbs.remove(item.entry.id) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(when {
                isSel -> tc.sl
                isCurrentSearchMatch -> tc.searchCurrentBg
                isSearchMatch -> tc.searchMatchBg
                hov -> sc.copy(.13f)
                else -> sc.copy(.06f)
            })
            .drawBehind { drawRect(sc, topLeft = Offset.Zero, size = Size(4f, size.height)) }
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                rowBoundsAbs[item.entry.id] = pos.y to (pos.y + coords.size.height)
                rowRoot = pos
            }
            .pointerInput("manual", tab.id, item.gid) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        when (ev.type) {
                            PointerEventType.Enter -> hov = true
                            PointerEventType.Exit -> hov = false
                            PointerEventType.Press -> {
                                val mods = ev.keyboardModifiers
                                when {
                                    ev.buttons.isSecondaryPressed -> {
                                        ev.changes.forEach { it.consume() }
                                        val ch = ev.changes.firstOrNull() ?: continue
                                        onCtxMenu(item.entry.id, (rowRoot.x + ch.position.x) / density, (rowRoot.y + ch.position.y) / density, "")
                                    }
                                    ev.buttons.isPrimaryPressed && (mods.isShiftPressed || mods.isCtrlPressed || mods.isMetaPressed) -> {
                                        ev.changes.forEach { it.consume() }
                                        onSelRow(item.entry.id, mods.isCtrlPressed || mods.isMetaPressed, mods.isShiftPressed)
                                    }
                                    ev.buttons.isPrimaryPressed -> {
                                        val now = System.currentTimeMillis()
                                        if (now - lastClickMs < 350) onToggleGroup(item.gid)
                                        else onSelRow(item.entry.id, false, false)
                                        lastClickMs = now
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
            .padding(start = ROW_START_PAD, end = 8.dp, top = ROW_V_PAD, bottom = ROW_V_PAD),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HeaderTimeDeltaCell(showTimeDelta, deltaMs, deltaSelectionAnchored, timeDeltaChars, mono, tc)
        CollapseChevron(expanded = item.expanded, color = sc, mono = mono, onClick = { onToggleGroup(item.gid) })
        val label = when (item.direction) {
            ManualCollapseDirection.TO_START -> "Collapsed to file start"
            ManualCollapseDirection.TO_END -> "Collapsed to file end"
            ManualCollapseDirection.RANGE -> "Collapsed selection"
        }
        AppText(label, color = sc, fontSize = 11.sp, fontFamily = mono, fontWeight = FontWeight.SemiBold)
        AppText("${item.entry.ts}  ${item.entry.level.key}", color = sc.copy(.7f), fontSize = 11.sp, fontFamily = mono)
        AppText("${item.entry.tag}:", color = sc, fontSize = 11.sp, fontFamily = mono,
            modifier = Modifier.widthIn(min = 120.dp, max = 520.dp), overflow = TextOverflow.Clip)
        AppText(item.entry.msg, color = sc, fontSize = 12.sp, fontFamily = mono, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f), overflow = TextOverflow.Clip)
        if (!item.expanded) AppText("${item.count} entries", color = sc.copy(.6f), fontSize = 11.sp)
    }
}

// Mirrors SeqHeaderRow — always-on, no backing SequenceDef/color to look up, so sc is fixed to
// DANGER_RED (crash/exception semantics) rather than read from the item.
@Composable
private fun StackTraceHeaderRow(
    item: LogItem.StackTraceHeader,
    tab: LogTab,
    mono: FontFamily,
    tc: ThemeColors,
    onSelRow: (Int, Boolean, Boolean) -> Unit,
    onCtxMenu: (Int, Float, Float, String) -> Unit,
    onToggleGroup: (String) -> Unit,
    rowBoundsAbs: HashMap<Int, Pair<Float, Float>>,
    isSearchMatch: Boolean = false,
    isCurrentSearchMatch: Boolean = false,
    showTimeDelta: Boolean = false,
    deltaMs: Long? = null,
    deltaSelectionAnchored: Boolean = false,
    timeDeltaChars: Int = 1,
) {
    val density = LocalDensity.current.density
    val sc = DANGER_RED
    val isSel = item.entry.id in tab.selected
    var hov by remember { mutableStateOf(false) }
    var rowRoot by remember { mutableStateOf(Offset.Zero) }
    var lastClickMs by remember { mutableStateOf(0L) }
    DisposableEffect(item.entry.id, rowBoundsAbs) {
        onDispose { rowBoundsAbs.remove(item.entry.id) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(when {
                isSel -> tc.sl
                isCurrentSearchMatch -> tc.searchCurrentBg
                isSearchMatch -> tc.searchMatchBg
                hov -> sc.copy(.15f)
                else -> sc.copy(.07f)
            })
            .drawBehind {
                val guideX = item.indent * INDENT_STEP.toPx()
                drawRect(sc, topLeft = Offset(guideX, 0f), size = Size(4f, size.height))
            }
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                rowBoundsAbs[item.entry.id] = pos.y to (pos.y + coords.size.height)
                rowRoot = pos
            }
            .pointerInput("st", tab.id, item.gid) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        when (ev.type) {
                            PointerEventType.Enter -> hov = true
                            PointerEventType.Exit -> hov = false
                            PointerEventType.Press -> {
                                val mods = ev.keyboardModifiers
                                when {
                                    ev.buttons.isSecondaryPressed -> {
                                        ev.changes.forEach { it.consume() }
                                        val ch = ev.changes.firstOrNull() ?: continue
                                        onCtxMenu(
                                            item.entry.id,
                                            (rowRoot.x + ch.position.x) / density,
                                            (rowRoot.y + ch.position.y) / density,
                                            "",
                                        )
                                    }
                                    ev.buttons.isPrimaryPressed && (mods.isShiftPressed || mods.isCtrlPressed || mods.isMetaPressed) -> {
                                        ev.changes.forEach { it.consume() }
                                        onSelRow(item.entry.id, mods.isCtrlPressed || mods.isMetaPressed, mods.isShiftPressed)
                                    }
                                    ev.buttons.isPrimaryPressed -> {
                                        val now = System.currentTimeMillis()
                                        if (now - lastClickMs < 350) onToggleGroup(item.gid)
                                        else onSelRow(item.entry.id, false, false)
                                        lastClickMs = now
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
            .padding(start = ROW_START_PAD + INDENT_STEP * item.indent, end = 8.dp, top = ROW_V_PAD, bottom = ROW_V_PAD),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HeaderTimeDeltaCell(showTimeDelta, deltaMs, deltaSelectionAnchored, timeDeltaChars, mono, tc)
        CollapseChevron(expanded = item.expanded, color = sc, mono = mono, onClick = { onToggleGroup(item.gid) })
        AppText("${item.entry.ts}  ${item.entry.level.key}", color = sc.copy(.7f), fontSize = 11.sp, fontFamily = mono)
        AppText("${item.entry.tag}:", color = sc, fontSize = 11.sp, fontFamily = mono,
            modifier = Modifier.widthIn(min = 120.dp, max = 520.dp), overflow = TextOverflow.Clip)
        AppText(item.entry.msg, color = sc, fontSize = 12.sp, fontFamily = mono, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f), overflow = TextOverflow.Clip)
        if (!item.expanded) AppText("${item.count} frames", color = sc.copy(.6f), fontSize = 11.sp)
    }
}

@Composable
private fun SectionBanner(label: String, color: Color, tc: ThemeColors) {
    Box(Modifier.fillMaxWidth().background(color.copy(.05f)).border(BorderStroke(1.dp, tc.br)).padding(horizontal = 12.dp, vertical = 3.dp)) {
        AppText(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ColumnScope.EmptyState(tc: ThemeColors, totalCount: Int, onClear: () -> Unit) {
    Column(
        Modifier.fillMaxSize().weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (totalCount == 0) {
            AppText("Open a log file to begin", color = tc.ts, fontSize = 13.sp)
        } else {
            AppText("No entries match current filters", color = tc.ts, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            PillBtn("Clear filters", active = true, onClick = onClear)
        }
    }
}

@Composable
private fun ExportMenuPopup(
    onExportTxt: () -> Unit,
    onExportCsv: () -> Unit,
    onDismiss: () -> Unit,
    tc: ThemeColors,
) {
    val density = LocalDensity.current.density
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(0, (34 * density).roundToInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(160.dp)
                .background(tc.p, RoundedCornerShape(7.dp))
                .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                .padding(vertical = 4.dp),
        ) {
            HoverBox(modifier = Modifier.fillMaxWidth(), onClick = onExportTxt) {
                AppText(
                    "Filtered log as .txt", color = tc.tx, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            HoverBox(modifier = Modifier.fillMaxWidth(), onClick = onExportCsv) {
                AppText(
                    "Filtered log as .csv", color = tc.tx, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolbarOptionsPopup(
    showRowNumbers: Boolean,
    showMinimap: Boolean,
    onToggleRowNumbers: () -> Unit,
    onToggleMinimap: () -> Unit,
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
            Modifier.width(190.dp)
                .background(tc.p, RoundedCornerShape(7.dp))
                .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                .padding(vertical = 4.dp),
        ) {
            HoverBox(modifier = Modifier.fillMaxWidth(), onClick = onToggleRowNumbers) {
                AppText(
                    if (showRowNumbers) "Hide row numbers" else "Show row numbers",
                    color = tc.tx,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
            HoverBox(modifier = Modifier.fillMaxWidth(), onClick = onToggleMinimap) {
                AppText(
                    if (showMinimap) "Hide minimap" else "Show minimap",
                    color = tc.tx,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}
