@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
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
import com.indagium.model.*
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.cachedSeqGroupsFor
import com.indagium.utils.computeItems
import com.indagium.utils.deltaAnchorId
import com.indagium.utils.deltaMillis
import com.indagium.utils.formatDelta
import com.indagium.utils.formatSignedDelta
import com.indagium.utils.passesFilter
import com.indagium.utils.regexRanges
import com.indagium.utils.resolveProcessDisplayName
import com.indagium.utils.visibleLogLineText
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
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import java.awt.Cursor as AwtCursor

private const val PAGE_JUMP_ROWS = 15
private const val CTX_MENU_KEYBOARD_X_DP = 60f
private const val LOADING_GRACE_MS = 250L
private const val SPLICE_SUMMARY_GUARD_MIN_ITEMS = 4096
private const val DOUBLE_CLICK_WINDOW_MS = 500L

// Upper bound for awaitExpandedAt below — the recompute it waits on has no fixed budget of its own
// (LOADING_GRACE_MS only governs when the LOADING line starts showing, not how long the compute is
// allowed to take), so this exists purely as a last-resort escape hatch against hanging forever on
// a target that — through some bug elsewhere — never actually lands, not as a "typical" duration.
private const val EXPANSION_AWAIT_TIMEOUT_MS = 5000L

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
//
// CHANGE B: [expandedAt] records the `tab.expanded` set this item list was actually BUILT from.
// largeFileMode's async path below (see the LaunchedEffect) can return a PREVIOUS, stale
// ComputedLogItems for up to LOADING_GRACE_MS with `loading == false` — a nav effect that resolves
// against that window's item list would scroll to a position computed under the OLD fold state, then
// mark its own request satisfied and never retry. Nav effects compare this against the live
// `tab.expanded` to detect that case ("stale") and treat it exactly like `loading`: don't consume the
// request yet. `null` means "not computed yet / unknown" and must always count as stale.
internal class ComputedLogItems(
    val items: List<LogItem>,
    val summary: ItemsSummary,
    val loading: Boolean,
    val expandedAt: Set<String>?,
)

private val EMPTY_SUMMARY = summarizeItems(emptyList())

// Returns the backing State object itself (read with `by`, not `=`, at call sites — see those
// call sites' comments) rather than unwrapping it, specifically so a LaunchedEffect elsewhere in
// this file can `snapshotFlow { computedItems.expandedAt }` and wait on the REAL recompute landing
// instead of guessing a fixed delay — see centerOnItem's callers and CHANGE 4/1.3(b)'s doc.
@Composable
private fun rememberComputedLogItems(tab: LogTab, applyFilter: Boolean): State<ComputedLogItems> {
    val dataSize = tab.logData.size
    val lastId = tab.logData.lastOrNull()?.id
    val filter = tab.filter
    val expanded = tab.expanded
    val manualBlocks = tab.manualBlocks
    val analysis = tab.analysis
    // Only folding-relevant analysis belongs in these keys. Custom Issues anchors must not
    // recompute rows or alter folding when their Settings rule list changes.
    val stackTraceGroups = analysis.stackTraceGroups
    val analysisPending = analysis.pending

    // Async + cancellable for every tab, not only largeFileMode ones (P-01): a keyword/tag filter
    // edit on a perfectly ordinary log still re-runs computeItems over the whole file, and doing
    // that synchronously inside composition pins the UI thread for however long the regex chain
    // takes — the same freeze class as FilterPanel's unifiedCandidates, just triggered by
    // committing a filter change instead of typing a search. The LOADING_GRACE_MS window below
    // means a fast recompute (the overwhelming majority on a normal-sized file) still swaps in
    // within the same frame or two, with no visible loading flash — see the largeFileMode
    // behavior this now applies to every tab, unchanged in shape.
    val computedState = remember(tab.id, applyFilter) {
        mutableStateOf(ComputedLogItems(emptyList(), EMPTY_SUMMARY, loading = true, expandedAt = null))
    }
    LaunchedEffect(tab.id, dataSize, lastId, filter, expanded, manualBlocks, stackTraceGroups, analysisPending, applyFilter) {
        val snapshot = tab.copy(selected = emptySet())
        val previous = computedState.value
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
                ComputedLogItems(items, summary, loading = false, expandedAt = snapshot.expanded)
            }
            // Grace period before flagging as loading: sub-quarter-second recomputes (the common
            // expand/collapse case, now that filter and sequence results are memoized across
            // expanded-only changes) swap in without ever flashing the loading line; only
            // genuinely slow recomputes show it.
            val quick = withTimeoutOrNull(LOADING_GRACE_MS) { deferred.await() }
            computedState.value = quick ?: run {
                // Preserve expandedAt from the previous (now-stale) result rather than resetting it —
                // this placeholder still carries the OLD list, so its staleness must still be
                // computed against whatever `expanded` it actually reflects, not wiped to null (which
                // would make an already-known-stale list look "unknown" instead).
                computedState.value = ComputedLogItems(
                    computedState.value.items, computedState.value.summary,
                    loading = true, expandedAt = computedState.value.expandedAt,
                )
                deferred.await()
            }
        }
    }
    return computedState
}

// Replaces the old blind `delay(80)` after an onToggleGroup burst (1.3(b)): that guessed a fixed
// 80ms while rememberComputedLogItems' own async recompute is allowed up to LOADING_GRACE_MS
// (250ms) before even showing a loading state, and unboundedly longer than that on a genuinely
// slow file — a caller that resumed after 80ms and immediately scrolled/centered was frequently
// racing a computation that hadn't landed yet, resolving its target index against the OLD item
// list. Waits on the real signal instead: `expandedAtProvider` reads a `by`-delegated
// computedItems/computedAllItems.expandedAt (see rememberComputedLogItems's doc), which
// snapshotFlow can observe change to exactly `target` once the fresh list is in. Falls back after
// EXPANSION_AWAIT_TIMEOUT_MS so a caller can never hang forever if the recompute never converges
// (defensive only — see that constant's doc).
private suspend fun awaitExpandedAt(target: Set<String>, expandedAtProvider: () -> Set<String>?) {
    if (expandedAtProvider() == target) return
    withTimeoutOrNull(EXPANSION_AWAIT_TIMEOUT_MS) {
        snapshotFlow(expandedAtProvider).first { it == target }
    }
}

// The first frame while the off-thread width calculation runs. This is the usual short
// "+0.000"-shaped delta and avoids initially reserving the full-day worst case.
private const val TIME_DELTA_SEED_CHARS = 6

// Keep the Δt gutter fixed while its meaning changes between "gap to previous row" and
// "distance from selected row". The budget covers both the largest visible adjacent gap and the
// visible range's endpoints (the largest possible selected-anchor distance), but never depends on
// the current selection. That keeps timestamps still during double-click word selection without
// leaving a full-day-sized blank gutter for short log files.
@Composable
private fun rememberTimeDeltaChars(tab: LogTab, visibleItems: List<LogItem>): Int {
    if (!tab.showTimeDelta) return 1

    var chars by remember(tab.id, tab.logData.size, visibleItems) {
        mutableStateOf(TIME_DELTA_SEED_CHARS)
    }
    LaunchedEffect(tab.id, tab.logData.size, visibleItems) {
        chars = withContext(Dispatchers.Default) {
            formatDelta(widestVisibleTimeDeltaMagnitudeMs(visibleItems)).length
        }
    }
    return chars
}

private fun widestVisibleTimeDeltaMagnitudeMs(items: List<LogItem>): Long {
    var firstTs: String? = null
    var previousTs: String? = null
    var widest = 0L
    items.forEach { item ->
        val ts = when (item) {
            is LogItem.Row -> item.entry.ts
            is LogItem.SeqHeader -> item.entry.ts
            is LogItem.ManualHeader -> item.entry.ts
            is LogItem.StackTraceHeader -> item.entry.ts
        }
        if (firstTs == null) firstTs = ts
        previousTs?.let { previous ->
            deltaMillis(previous, ts)?.let { widest = maxOf(widest, kotlin.math.abs(it)) }
        }
        previousTs = ts
    }
    firstTs?.let { first ->
        previousTs?.let { last ->
            deltaMillis(first, last)?.let { widest = maxOf(widest, kotlin.math.abs(it)) }
        }
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

// The branches model distinct fold kinds and large-file safety paths. Keeping them together is
// intentional: each loop iteration must choose at most one expansion against the same snapshot.
@Suppress("CyclomaticComplexMethod")
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
        if (!passesFilter(entry, tab.filter, tab.analysis.processNames, regexContext)) return null
    }
    var expanded = tab.expanded
    var candidateItems = currentItems ?: computeItems(tab.copy(expanded = expanded), applyFilter, regexContext, storeInCache = false)
    repeat(24) {
        // CORE RULE: expand only folds that HIDE the target. Never expand the header that DISPLAYS
        // it. A header shows the SAME entry id whether it's folded or open (SeqHeader/StackTraceHeader
        // show their root line, ManualHeader TO_START shows its anchor — see Filter.kt), so ANY item
        // displaying entryId — a Row, or a header whether collapsed or expanded — is already a valid
        // landing spot: clicking a crash in the Issues panel must scroll to that crash's stack-trace
        // header and leave it collapsed (the user opens it themselves), while an OUTER fold that hides
        // that header — e.g. a manual "Collapse -> To start" block — still needs expanding so the
        // header becomes reachable at all (handled by the collapsedHeaders probing below).
        val visibleIdx = candidateItems.indexOfEntry(entryId)
        if (visibleIdx >= 0) return ExpansionAndIndexTarget(expanded, visibleIdx)
        // Stack-trace membership is the one containment question answerable WITHOUT a computeItems
        // probe — analysis.stackTraceGroups already lists each group's member ids outright. Worth a
        // direct lookup on a huge file, where the probing path below is off the table: a Find-bar
        // match is computed over the fully-expanded list (utils/SearchComputeResult.kt), so it
        // frequently lands on a frame INSIDE a collapsed trace, which is never a direct hit on the
        // header. Without this, Next/Prev onto such a match would silently do nothing.
        if (tab.largeFileMode) {
            val owningStackGid = tab.analysis.stackTraceGroups
                .firstOrNull { it.gid !in expanded && entryId in it.memberIds }?.gid
            if (owningStackGid != null) {
                expanded = expanded + owningStackGid
                candidateItems = computeItems(tab.copy(expanded = expanded), applyFilter, regexContext, storeInCache = false)
                return@repeat
            }
            // Sequence containment gets the same cheap treatment as stack traces just above, via
            // cachedSeqGroupsFor's memoized read of the seqGroups computeItems already built on the
            // last full pass for this (tab, applyFilter) — a Find-bar match is computed over the
            // fully-expanded list (utils/SearchComputeResult.kt) so it frequently lands on a line
            // INSIDE a collapsed sequence group, which — unlike a stack trace — had no escape hatch
            // at all here before: on a huge file collapsedHeaders below deliberately excludes
            // SeqHeader candidates (probing one costs a full computeItems), so the jump silently
            // resolved to null and the group never opened. This is O(total swallowed ids) of plain
            // int scanning per round — a sequence def with no end pattern can swallow most of the
            // file, so it isn't free — but it is orders of magnitude cheaper than the
            // one-full-computeItems-per-guess probing the largeFileMode guard exists to avoid.
            // cachedSeqGroupsFor returning null means only "no cheap answer available" (cache
            // empty/stale/sequences off) — never "no group contains entryId" — so that case simply
            // falls through unchanged, same as finding no owning group below.
            val seqGroups = cachedSeqGroupsFor(tab, applyFilter)
            if (seqGroups != null) {
                var gidToOpen: String? = null
                for (sg in seqGroups) {
                    val inPlain = entryId in sg.plain
                    val nested = sg.nested.firstOrNull { entryId == it.rid || entryId in it.ch }
                    if (!inPlain && nested == null) continue
                    // Outermost first: opening a nested sub-sequence is useless while its parent is
                    // still folded — the parent's own header (which the CORE RULE would then match on
                    // the very next round) isn't even reachable yet.
                    gidToOpen = when {
                        sg.gid !in expanded -> sg.gid
                        nested != null && nested.gid !in expanded -> nested.gid
                        else -> null
                    }
                    if (gidToOpen != null) break
                }
                if (gidToOpen != null) {
                    expanded = expanded + gidToOpen
                    candidateItems = computeItems(tab.copy(expanded = expanded), applyFilter, regexContext, storeInCache = false)
                    return@repeat
                }
            }
        }
        // By this point neither cheap branch above resolved anything: entryId isn't displayed by any
        // row or header, so it's buried strictly inside a fold. Reaching it means probing candidates
        // by actually expanding them — a full computeItems PER GUESS, which is what largeFileMode
        // must not do across the thousands of collapsed sequence/stack-trace headers a big log has.
        //
        // Manual blocks are the one exception, and they're deliberately still probed on huge files:
        // they're user-created (a handful at most, not thousands), and they're the only fold kind
        // whose header can never be a direct hit for the lines it hides — a TO_START header displays
        // its ANCHOR, i.e. the line at the far END of the range it folds. Without this, "Collapse →
        // To start" on a large file would swallow every jump into it, which is exactly the case this
        // whole fix exists for. Cost is bounded by the number of manual blocks, not by file size.
        val collapsedHeaders = candidateItems.mapNotNull { item ->
            when (item) {
                is LogItem.ManualHeader -> item.gid.takeIf { !item.expanded }?.let { it to item.entry.id }
                is LogItem.SeqHeader ->
                    item.gid.takeIf { !item.expanded && !tab.largeFileMode }?.let { it to item.entry.id }
                is LogItem.StackTraceHeader ->
                    item.gid.takeIf { !item.expanded && !tab.largeFileMode }?.let { it to item.entry.id }
                is LogItem.Row -> null
            }
        }
        // On a huge file with no manual block in the way there is nothing cheap left to try, so stop
        // rather than fall through to the blind fallback below and open an arbitrary group.
        if (collapsedHeaders.isEmpty()) return null
        val ranked = rankCollapsedHeadersByProximity(collapsedHeaders, entryId)
        // anyEntry deliberately stays loose (matches collapsed headers too, not just Rows/expanded
        // headers): with nested folds — e.g. a collapsed sequence inside a collapsed manual block —
        // expanding just the outer block never turns entryId into a Row in one step, so a stricter
        // check would make every candidate fail to verify. The blind `?: ranked.firstOrNull()`
        // fallback is likewise load-bearing for that same nested case: verification can legitimately
        // fail for every ranked candidate on a single round, and we still need to make progress.
        val groupToOpen = ranked.firstOrNull { gid ->
            computeItems(tab.copy(expanded = expanded + gid), applyFilter, regexContext, storeInCache = false).anyEntry(entryId)
        } ?: ranked.firstOrNull() ?: return null
        expanded = expanded + groupToOpen
        candidateItems = computeItems(tab.copy(expanded = expanded), applyFilter, regexContext, storeInCache = false)
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

    // Whether a panel should keep auto-scrolling to the newest row while its tab is live-watching.
    // Lives here rather than a remember{} for the same reason lazyState does: it must survive tab
    // switches and the Unfiltered split toggle, keyed by the same "$tabId:$panel" panelKey so the
    // ":original" and ":main" panels of a split follow independently. Being store-backed (not
    // AppSettings-backed) is also exactly what makes it session-only — the scroll-up pause never
    // outlives the tab, and never gets written back to the standing autoScrollWhileTailing setting.
    private val followTailStates = mutableMapOf<String, MutableState<Boolean>>()

    fun lazyState(panelKey: String): LazyListState =
        lazyStates.getOrPut(panelKey) { LazyListState() }

    // ScrollState itself is axis-agnostic — this backs the log rows' horizontal scroll, but is
    // reused as-is (same store, same "$tabId:" prefix cleanup) for other single-axis scroll
    // positions that need to survive a tab switch, e.g. the Notes panel's vertical scroll.
    fun scrollState(panelKey: String): ScrollState =
        horizontalStates.getOrPut(panelKey) { ScrollState(0) }

    fun followTailState(panelKey: String): MutableState<Boolean> =
        followTailStates.getOrPut(panelKey) { mutableStateOf(true) }

    fun removeTab(tabId: String) {
        val prefix = "$tabId:"
        lazyStates.keys.removeAll { it.startsWith(prefix) }
        horizontalStates.keys.removeAll { it.startsWith(prefix) }
        followTailStates.keys.removeAll { it.startsWith(prefix) }
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

// Split out of buildFullLineAnnotation below purely to keep that function's own cyclomatic
// complexity under detekt's threshold.
//
// Change 3 (process-names rework): the pid field renders either a process name (when
// [processDisplay] is non-null) or the bare pid number, but EITHER WAY it's padded to the same
// [pidFieldWidth] — the uniform per-tab width LogViewer computes via pidFieldCharWidth — so every
// row's TID (and everything after it) lands at the same x regardless of whether that particular
// row happens to show a name. A name is middle-ellipsised to fit, then padded on the right
// (padEnd); a bare number keeps the original right-aligned convention (padStart) — the two read
// naturally in opposite directions, but since both always occupy exactly [pidFieldWidth]
// characters, TID starts at the same offset either way. [pidFieldWidth] is always exactly 5 (the
// original padStart(5) width) in mode OFF and in any tab where the longest known name is no wider
// than 5 chars, so this reproduces the pre-feature output byte-for-byte in both cases.
// [cellBg] — null for every pre-existing caller, so this reproduces the pre-feature render
// byte-for-byte otherwise — paints a background wash behind the ts span and, separately, behind
// the whole pid/tid span (LogRow passes item.scopedSeqColor.copy(alpha = tc.seqCellBgAlpha) for a
// thread-scoped/"async" sequence row; see that field's own doc). Two SEPARATE SpanStyle
// backgrounds, one per field, rather than one background spanning the "  " gap between them — same
// two-cell shape the foreground tint (tsColor/pidColor, already computed per-field by the caller)
// already implies. Applied first, before the caller's own highlighter/keyword/search addStyle
// passes below run — later-added spans paint on top (see this function's own doc), so a highlighter
// hit, keyword-regex hit, or Find match landing on the ts/pid text still visually wins over this
// wash exactly as before this feature existed.
private fun AnnotatedString.Builder.appendTsPidTid(
    entry: LogEntry,
    tsColor: Color,
    pidColor: Color,
    processDisplay: String?,
    pidFieldWidth: Int,
    cellBg: Color? = null,
) {
    withStyle(SpanStyle(color = tsColor, background = cellBg ?: Color.Unspecified)) { append(entry.ts) }
    if (entry.pid > 0) {
        append("  ")
        withStyle(SpanStyle(color = pidColor, background = cellBg ?: Color.Unspecified)) {
            if (processDisplay != null) {
                append(middleEllipsis(processDisplay, pidFieldWidth).padEnd(pidFieldWidth))
            } else {
                append(entry.pid.toString().padStart(pidFieldWidth))
            }
            append(" ")
            append(entry.tid.toString().padStart(5))
        }
    }
}

// Change 3 (process-names rework): remaps a [start, end) offset pair computed against
// visibleLogLineText(entry) (utils/TextMatch.kt:125-133, which always writes the pid as a fixed
// 5-char padStart field — see that function's own doc for why it's never changed by this feature)
// onto the text buildFullLineAnnotation actually renders below, where the pid field can be a
// different — but constant for this one row — width (appendTsPidTid's pidFieldWidth; [delta] here
// is that width minus 5, i.e. how much every offset AFTER the field has shifted).
//
// - A range entirely BEFORE the field (both offsets <= [pidFieldStart]) is unaffected.
// - A range entirely AT-OR-AFTER the field (both offsets >= [pidFieldEndVisible]) keeps its exact
//   width, just shifted right by [delta].
// - A range that OVERLAPS the field in either direction is widened to cover the field's full
//   RENDERED span for the overlapping side, rather than trying to reproduce a position inside
//   content that may no longer exist there (a highlighter matching part of the numeric pid has no
//   meaningful analog once that pid is replaced by a name). This is a deliberate choice: it
//   over-highlights the whole field instead of silently collapsing to an empty range (start ==
//   end), which every caller below drops via its own `s < e` check — losing the highlight
//   entirely would be a worse surprise than highlighting a couple of extra characters.
internal fun remapPidFieldRange(
    range: Pair<Int, Int>,
    pidFieldStart: Int,
    pidFieldEndVisible: Int,
    delta: Int,
): Pair<Int, Int> {
    val pidFieldEndRendered = pidFieldEndVisible + delta

    fun remap(offset: Int, clampTo: Int): Int = when {
        offset <= pidFieldStart -> offset
        offset < pidFieldEndVisible -> clampTo
        else -> offset + delta
    }
    return remap(range.first, pidFieldStart) to remap(range.second, pidFieldEndRendered)
}

// Change 3 (process-names rework): the uniform pid-FIELD character width for this tab, shared by
// every row (LogRow) and ColHeader's own "PID" box — see appendTsPidTid's doc for why a uniform
// width (rather than each row sizing to its own content) is what makes TID/LVL/TAG/MESSAGE line up
// on every row once the feature is on.
//
// OFF always returns 5 (the original padStart(5) width) — LogRow/buildFullLineAnnotation then
// render byte-identical to before this feature existed, per ProcessNameMode's own doc.
//
// ALL derives the width from the WIDEST name actually known for this tab ([processNames], every
// name this tab has learned) — every known name is a candidate because ALL can show any of them at
// any time. MANUAL, unlike ALL, only ever RENDERS a name for a pid in [manualPicks] (see
// resolveProcessDisplayName), so it sizes to the widest name among just those picked pids —
// anything wider that was merely learned but never picked would reserve column space for a name no
// row is actually showing, which is exactly the "wide, mostly-empty PID column" bug this width was
// rewritten to stop (MANUAL with nothing picked — the state every restart lands in, since picks are
// session-only — now returns 5, the same as OFF). A column that resizes when the user explicitly
// picks or hides a pid is an acceptable, expected trade for that; a column that never reflects what
// it's actually rendering is not. Floored at 5 (a numeric pid must never render NARROWER than
// before) and capped at PROCESS_NAME_MAX_CHARS (Theme.kt) — the same middle-ellipsis budget
// appendTsPidTid already truncates an individual long name to, so one outlier name can't blow the
// column out for the whole file.
internal fun pidFieldCharWidth(mode: ProcessNameMode, processNames: Map<Int, String>, manualPicks: Set<Int>): Int {
    if (mode == ProcessNameMode.OFF) return 5
    val candidateNames = if (mode == ProcessNameMode.MANUAL) {
        processNames.filterKeys { it in manualPicks }.values
    } else {
        processNames.values
    }
    val longestName = candidateNames.maxOfOrNull { it.length } ?: return 5
    return longestName.coerceIn(5, PROCESS_NAME_MAX_CHARS)
}

// Whether this tab is actually rendering any process name right now — which is NOT the same as
// "the mode isn't OFF". MANUAL with an empty pick set displays nothing at all: reachable by showing
// one process from a row menu and then hiding it again, and it is also where every restored tab
// lands, since the picks are session-only (see LogTab.manualProcessNamePicks). Keying the toolbar
// entry off the bare mode made it offer "Hide process names" in exactly that state, with no name on
// screen to hide.
internal fun processNamesVisible(mode: ProcessNameMode, manualPicks: Set<Int>): Boolean = when (mode) {
    ProcessNameMode.OFF -> false
    ProcessNameMode.ALL -> true
    ProcessNameMode.MANUAL -> manualPicks.isNotEmpty()
}

// The toolbar options popup's process-name entry is a plain two-state toggle (unlike Settings' own
// three-way control): showing anything means the action is "hide" (-> OFF), showing nothing means
// it is "show" (-> ALL). MANUAL is never entered from here, only from a row's context menu.
// Pulled out as a pure function, like pidFieldCharWidth/resolveProcessDisplayName above, so this
// decision is unit-testable without a Compose harness (this codebase has none).
internal fun toggledProcessNameMode(current: ProcessNameMode, manualPicks: Set<Int>): ProcessNameMode =
    if (processNamesVisible(current, manualPicks)) ProcessNameMode.OFF else ProcessNameMode.ALL

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
    // Change 3 (process-names rework): the resolved name shown in place of this row's bare pid
    // (null for every pre-existing caller, including the whole prior test suite — see
    // resolveProcessDisplayName for when LogRow ever passes non-null), and the uniform per-tab
    // pid-FIELD character width every row pads to (5 — the original padStart width — for every
    // pre-existing caller too). Both defaults reproduce the exact pre-feature single-field render
    // byte-for-byte; see appendTsPidTid's own doc for how they combine.
    processDisplay: String? = null,
    pidFieldWidth: Int = 5,
    // See appendTsPidTid's own doc — null (every pre-existing caller) reproduces the pre-feature
    // render byte-for-byte.
    cellBg: Color? = null,
): AnnotatedString = buildAnnotatedString {
    appendTsPidTid(entry, tsColor, pidColor, processDisplay, pidFieldWidth, cellBg)
    append("  ")
    withStyle(SpanStyle(color = entry.level.defaultColor, fontWeight = FontWeight.Bold)) {
        append(entry.level.key.toString())
    }
    append("  ")
    withStyle(SpanStyle(color = tagColor)) { append(entry.tag); append(":") }
    append(" ")
    withStyle(SpanStyle(color = msgColor)) { append(entry.msg) }
    // Filters/highlighters/Find always match against visibleLogLineText(entry) — the single
    // source of truth (utils/TextMatch.kt) — never against what's actually rendered above, which
    // (once a process name, or a numeric pid padded to a wider uniform column, replaces
    // visibleLogLineText's own fixed 5-char pid field) can differ from it — in LENGTH whenever
    // pidFieldWidth != 5, but potentially in CONTENT even when pidFieldWidth == 5 (a resolved name
    // no wider than 5 chars still replaces the digits at that same width). Every offset pair
    // hlRanges/keywordRegexHighlightRanges/regexRanges hand back is therefore always passed through
    // remapPidFieldRange (whenever this row even HAS a pid field — entry.pid <= 0 rows never do, on
    // either side, so those skip straight to identity) before being applied to the text actually
    // built above — see that function's own doc for the exact rule, including how it widens a range
    // overlapping the pid field to the field's full rendered span regardless of whether delta is
    // zero, precisely because content (not just width) can differ there. Mode OFF never resolves a
    // name at all (LogRow's resolveProcessDisplayName), so this is a no-op there in practice, not
    // just in the common case — see the OFF-byte-identical test coverage in
    // ProcessNameRenderingTest.
    val lineText = visibleLogLineText(entry)
    val pidFieldDelta = pidFieldWidth - 5
    val pidFieldStart = entry.ts.length + 2
    val pidFieldEndVisible = pidFieldStart + 5

    fun remap(range: Pair<Int, Int>): Pair<Int, Int> =
        if (entry.pid <= 0) range else remapPidFieldRange(range, pidFieldStart, pidFieldEndVisible, pidFieldDelta)
    val renderedLength = length
    for (hl in highlighters.filter { it.on && it.pattern.isNotBlank() }) {
        hlRanges(lineText, hl, regexContext).forEach { rawRange ->
            val (s, e) = remap(rawRange)
            if (s < e && e <= renderedLength)
                addStyle(SpanStyle(background = hl.color.copy(alpha = 0.6f), fontWeight = FontWeight.SemiBold), s, e)
        }
    }
    keywordRegexFilter?.let { filter ->
        keywordRegexHighlightRanges(lineText, filter, regexContext).forEach { rawRange ->
            val (s, e) = remap(rawRange)
            if (s < e && e <= renderedLength) {
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
            regexRanges(lineText, sh.query, ignoreCase = !sh.caseSensitive, regexContext = regexContext).forEach { rawRange ->
                val (s, e) = remap(rawRange)
                if (s < e && e <= renderedLength) {
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
    // The process-name display mode (Problem 4's second entry point — the same options popup that
    // toggles row numbers/minimap). Default no-op keeps preview/test call sites that don't wire it
    // unaffected, same convention as onToggleRowNumbers/onToggleMinimap above.
    onSetProcessNameMode: (ProcessNameMode) -> Unit = {},
    // Click-a-branch-to-highlight for the tid-map gutter overlay (ui/TidMap.kt,
    // AppState.setTidMapHighlight) — bound to this tab's id by the caller already, same convention
    // as onToggleTimeDelta/onToggleUnfiltered above. Default no-op keeps preview/test call sites
    // that don't wire it unaffected.
    onSetTidMapHighlight: (Int?) -> Unit = {},
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
    // A one-shot, non-selection action for an unmodified primary-button double-click on a plain
    // row. The caller owns the tab binding and any video mapping; nullable keeps previews/tests
    // and normal LogViewer consumers free of video coupling.
    onLogRowDoubleClick: ((Int) -> Unit)? = null,
    // Follow needs to know about the first press too: it can otherwise re-select its current row
    // before the second press turns this into a log-to-video double-click seek. The expiry callback
    // restores normal Follow after a plain single click.
    onLogRowDoubleClickGestureStarted: (() -> Unit)? = null,
    onLogRowDoubleClickGestureExpired: (() -> Unit)? = null,
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
    // `by`, not `=`: keeps this a live-readable delegated property so the LaunchedEffects further
    // down can snapshotFlow { computedItems.expandedAt } and wait on the real recompute landing —
    // see rememberComputedLogItems's doc.
    val computedItems by rememberComputedLogItems(tab, true)
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
    // Change 3 (process-names rework): the uniform pid-FIELD character width every row's pid cell
    // (LogRow) — and ColHeader's own "PID" box — pads to, so TID/LVL/TAG/MESSAGE line up on every
    // row once the feature is on. Computed once per tab here (not per row: pidFieldCharWidth scans
    // every known name's length, an O(known-pids) cost that must not repeat for every visible row)
    // and threaded down to both ColHeader and LogRow below. Keyed on the processNames map itself
    // (not just tab.id) so a name learned mid-tail (TailCoordinator's merge) updates the width, same
    // rationale as hasPidTid's own totalCnt key above — and on manualProcessNamePicks too, since
    // MANUAL now sizes to only the picked pids (pidFieldCharWidth's own doc), so picking or hiding
    // one must recompute this the same way learning a new name does.
    val pidFieldChars = remember(tab.id, tab.analysis.processNames, tab.manualProcessNamePicks, tab.processNameMode) {
        pidFieldCharWidth(tab.processNameMode, tab.analysis.processNames, tab.manualProcessNamePicks)
    }
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
                variant = if (tab.search.active) ButtonVariant.Primary else ButtonVariant.Secondary,
                leadingIcon = Icons.Outlined.Search,
                horizontalPadding = 10.dp,
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
                    processNameMode = tab.processNameMode,
                    manualProcessNamePicks = tab.manualProcessNamePicks,
                    onToggleRowNumbers = { toolbarContextMenuOpen = false; onToggleRowNumbers() },
                    onToggleMinimap = { toolbarContextMenuOpen = false; onToggleMinimap() },
                    onSetProcessNameMode = { mode -> toolbarContextMenuOpen = false; onSetProcessNameMode(mode) },
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
            // Fixed Δt column width in characters, passed straight through to every LogRow so selecting
            // an anchor never moves the rest of the log content.
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
            // ---- Tail-follow (settings.autoScrollWhileTailing) ----------------------------------
            // The runtime half of the setting. `followTail` is derived FROM LAYOUT on every viewport
            // change rather than tracked as a scroll delta, and that is the whole reason this is
            // race-free: our own scroll lands at the bottom, where isAtLastRow is true anyway, so the
            // derived value agrees with itself and there is never a "who scrolled?" question in the
            // steady state. Suspending and resuming here is transient viewport state — it must never
            // write back to settings.autoScrollWhileTailing (see AppSettings' comment on that field).
            val followTail = scrollStates.followTailState(panelKey)
            // The one window where the derivation IS wrong is mid-flight during our own scroll, when
            // firstVisibleItem* has moved but layout hasn't settled. This masks exactly that window.
            var selfScrolling by remember(panelKey) { mutableStateOf(false) }
            val currentLastRowIndex by rememberUpdatedState(listItems.lastIndex)
            // Re-arm on every transition into live-watching: choosing to watch a file means wanting
            // to see the newest line, even if this panel had been scrolled away earlier.
            LaunchedEffect(panelKey, effectiveTab.tailing) {
                if (effectiveTab.tailing) followTail.value = true
            }
            // User-intent sampler. Keyed on the viewport position, NOT on the item count: appending
            // rows below the fold never changes firstVisibleItem*, so a tail batch cannot flip
            // following off, while any real viewport move (wheel, drag, scrollbar, minimap, keyboard
            // nav, or a note/Find/video jump — see the note beside the annotation-nav effects above)
            // does. snapshotFlow conflates, so a burst of scroll frames collapses to one reading.
            LaunchedEffect(panelKey, lazyState) {
                snapshotFlow { lazyState.firstVisibleItemIndex to lazyState.firstVisibleItemScrollOffset }
                    .collect {
                        if (!selfScrolling) {
                            followTail.value = isAtLastRow(
                                lazyState.layoutInfo.visibleItemsInfo.lastOrNull()?.index,
                                currentLastRowIndex,
                            )
                        }
                    }
            }
            // The follow itself. listItems.size is the append signal; it deliberately does not fire
            // when the active filter hides every newly tailed line, which is the correct behaviour —
            // nothing new is visible, so nothing should move. Instant, never animated: at
            // FileTailer's ~500ms poll the next batch would land mid-animation (the same reasoning
            // scrollForCursor records below). The spacer index is listItems.size, one past the last
            // real row — see newestRowScrollOffset for why that lands the newest row flush at the
            // bottom edge with no row-height estimate.
            LaunchedEffect(panelKey, settings.autoScrollWhileTailing, effectiveTab.tailing, listItems.size) {
                if (!settings.autoScrollWhileTailing || !effectiveTab.tailing || !followTail.value) return@LaunchedEffect
                selfScrolling = true
                try {
                    val viewportHeight = lazyState.layoutInfo.let { it.viewportEndOffset - it.viewportStartOffset }
                    lazyState.scrollToItem(listItems.size, newestRowScrollOffset(viewportHeight))
                } finally {
                    // Release only after layout settles, so the sampler above reads the SETTLED
                    // position rather than a mid-scroll one — the same one-frame trick centerOnItem
                    // uses. Too narrow a mask un-follows the panel on its own first batch; too wide
                    // swallows a genuine user scroll that raced the batch.
                    withFrameNanos { }
                    selfScrolling = false
                }
            }
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
                        // Absolute Y of this content Box's own top-left — the same
                        // positionInRoot()-based bookkeeping rowBoundsAbs already uses for row hit-
                        // testing (see the "drag" pointerInput above), captured here too so
                        // TidMapOverlay can convert rowBoundsAbs's absolute row bounds into its own
                        // LOCAL (canvas-relative) coordinates.
                        var contentTopY by remember { mutableStateOf(0f) }
                        // Positioned as its OWN leading gutter — BEFORE the timestamp, row-number,
                        // and Δt gutters — exactly like those two gutters are: a real reserved-width
                        // Box that pushes the row's own content right (see LogRow's/ColHeader's
                        // leading TID_MAP_HIT_WIDTH spacer), not a measured offset into a gap that
                        // already has other text in it.
                        //
                        // An earlier version tried to sit BETWEEN the timestamp and PID columns by
                        // measuring the rendered width of the timestamp text and offsetting into the
                        // 2-space gap buildFullLineAnnotation leaves there. That was fragile in two
                        // ways at once: the 2-space gap is far narrower than the branch geometry
                        // needs (near-zero margin for error even with a perfect measurement), and the
                        // measurement itself could drift from the row's actual rendered position
                        // (theme/font-scale-dependent) — the combination produced a spine that
                        // visibly overlaid live timestamp digits. A fixed leading reservation has
                        // neither problem: Compose's own layout system guarantees the row's real
                        // content starts exactly TID_MAP_HIT_WIDTH later, the same guarantee the
                        // row-number and Δt gutters already rely on.
                        val tidMapSpineX = ROW_START_PAD
                        Box(contentModifier.onGloballyPositioned { contentTopY = it.positionInRoot().y }) {
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
                                            // AppState owns the link-local gate. Keep the handler
                                            // installed so an anchor/link choice changed elsewhere
                                            // is reflected without coupling LogViewer to settings.
                                            onLogRowDoubleClick = onLogRowDoubleClick,
                                            onLogRowDoubleClickGestureStarted = onLogRowDoubleClickGestureStarted,
                                            onLogRowDoubleClickGestureExpired = onLogRowDoubleClickGestureExpired,
                                            rowBoundsAbs = boundsMap,
                                            regexContext = highlightRegexContext,
                                            highlightEntireCrashGroup = settings.highlightEntireCrashGroup,
                                            autoWrap = settings.autoLogRowWrap,
                                            showRowNumbers = settings.showRowNumbers,
                                            showTimeDelta = effectiveTab.showTimeDelta,
                                            deltaMs = deltaMs,
                                            deltaSelectionAnchored = deltaAnchorEntryId != null,
                                            timeDeltaChars = timeDeltaChars,
                                            hasTidMap = effectiveTab.tidMap != null,
                                            processNameMode = tab.processNameMode,
                                            pidFieldChars = pidFieldChars,
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
                                                showRowNumbers = settings.showRowNumbers,
                                                showTimeDelta = effectiveTab.showTimeDelta,
                                                deltaMs = deltaMs,
                                                deltaSelectionAnchored = deltaAnchorEntryId != null,
                                                timeDeltaChars = timeDeltaChars,
                                                hasTidMap = effectiveTab.tidMap != null,
                                                autoWrap = settings.autoLogRowWrap,
                                                wrapLimitChars = effectiveWrapLimitChars,
                                                pidFieldChars = pidFieldChars,
                                            )
                                        is LogItem.ManualHeader ->
                                            ManualHeaderRow(
                                                item, effectiveTab, mono, tc, itemOnSelRow, itemOnCtxMenu, onToggleGroup, boundsMap,
                                                isSearchMatch = isSearchMatch, isCurrentSearchMatch = isCurrentSearchMatch,
                                                showRowNumbers = settings.showRowNumbers,
                                                showTimeDelta = effectiveTab.showTimeDelta,
                                                deltaMs = deltaMs,
                                                deltaSelectionAnchored = deltaAnchorEntryId != null,
                                                timeDeltaChars = timeDeltaChars,
                                                hasTidMap = effectiveTab.tidMap != null,
                                                autoWrap = settings.autoLogRowWrap,
                                                wrapLimitChars = effectiveWrapLimitChars,
                                                pidFieldChars = pidFieldChars,
                                            )
                                        is LogItem.StackTraceHeader ->
                                            StackTraceHeaderRow(
                                                item, effectiveTab, mono, tc, itemOnSelRow, itemOnCtxMenu, onToggleGroup, boundsMap,
                                                isSearchMatch = isSearchMatch, isCurrentSearchMatch = isCurrentSearchMatch,
                                                showRowNumbers = settings.showRowNumbers,
                                                showTimeDelta = effectiveTab.showTimeDelta,
                                                deltaMs = deltaMs,
                                                deltaSelectionAnchored = deltaAnchorEntryId != null,
                                                timeDeltaChars = timeDeltaChars,
                                                hasTidMap = effectiveTab.tidMap != null,
                                                autoWrap = settings.autoLogRowWrap,
                                                wrapLimitChars = effectiveWrapLimitChars,
                                                pidFieldChars = pidFieldChars,
                                            )
                                    }
                                }
                                item(key = "tail-space") {
                                    Spacer(Modifier.height(tailSpaceHeight))
                                }
                            }
                            // Drawn AFTER (on top of) the LazyColumn, as a second child of the same
                            // Box — an overlay, not a participating layout row, same relationship
                            // Minimap has to the log content below. Only renders when this tab has
                            // an active map (effectiveTab.tidMap, not tab.tidMap — split view's
                            // Original/Filtered panels share the one tab-level map, but each
                            // instance here still only ever sees ITS OWN listItems/boundsMap, which
                            // is what keeps the computed span independent per panel).
                            effectiveTab.tidMap?.let { tidMap ->
                                TidMapOverlay(
                                    tidMap = tidMap,
                                    items = listItems,
                                    rowBoundsAbs = boundsMap,
                                    contentTopY = contentTopY,
                                    spineOffsetX = tidMapSpineX,
                                    tc = tc,
                                    onHighlightChange = onSetTidMapHighlight,
                                )
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
                                    onHideMinimap = onToggleMinimap,
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
            // `by`, not `=` — same reason as computedItems above.
            val computedAllItems by rememberComputedLogItems(tab, false)
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

            // Backs the Filtered panel's row-click sync further down (1.3(a)): a click sets this,
            // and the LaunchedEffect below does the actual resolve+toggle+center, keyed on both the
            // click and computedAllItems.expandedAt so it correctly RETRIES once a fresh Original
            // list lands instead of resolving once against a possibly-stale one with no staleness
            // guard (the bug this replaces — see the itemOnSelRow call site's comment).
            var rowClickSyncId by remember(tab.id) { mutableStateOf<Int?>(null) }
            LaunchedEffect(rowClickSyncId, computedAllItems.expandedAt, computedAllItems.loading) {
                val id = rowClickSyncId ?: return@LaunchedEffect
                if (computedAllItems.expandedAt != tab.expanded || computedAllItems.loading) return@LaunchedEffect
                val target = withContext(Dispatchers.Default) {
                    expansionAndIndexForEntry(tab, applyFilter = false, entryId = id, currentItems = computedAllItems.items)
                }
                if (target != null) {
                    (target.expanded - tab.expanded).forEach { gid -> onToggleGroup(gid) }
                    awaitExpandedAt(target.expanded) { computedAllItems.expandedAt }
                    allLazyState.centerOnItem(target.index)
                }
                rowClickSyncId = null
            }

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

            // NOTE for whoever touches tail-follow next: every LaunchedEffect below that calls
            // followItem/centerOnItem/scrollForCursor on filteredLazyState or allLazyState (annotation
            // nav, video-follow — which reuses the same FOLLOW branch below via AppState.followVisibleLog
            // — Find-bar search nav, and the one-shot restore-selection-on-split-open effect further
            // down) moves the viewport away from the last row as an unavoidable side effect. That
            // suspends this panel's tail-following (see ItemList's own follow-tail sampler, which derives
            // "following" purely from whether the last row is on screen). This is intentional and
            // load-bearing, not a bug: the user (or a video anchor) explicitly asked to look at a
            // specific row, and that request must win over a live tail's pull toward the newest line.
            // Don't "fix" this into two scroll owners fighting — there is deliberately only one, and
            // whichever effect scrolled last owns the viewport until the user scrolls back to the tail.
            // Survives this LaunchedEffect being cancelled and restarted (by its own tab.expanded
            // key, which its own onToggleGroup calls below flip) partway through resolving a
            // request — BEFORE reaching onConsumeAnnotationNavigation. Without this, that restart
            // would re-run expansionAndIndexForEntry against a tab.expanded the USER may have since
            // hand-collapsed (the request itself never got a chance to actually consume), and
            // re-expand exactly what they just closed. See CHANGE 4 in the task write-up (bug B).
            var satisfiedAnnotationNavId by remember(tab.id) { mutableStateOf(-1L) }
            LaunchedEffect(
                annotationNavigationRequest?.id, itemsVersion, allItemsVersion, tab.expanded,
                computedItems.loading, computedAllItems.loading, computedItems.expandedAt, computedAllItems.expandedAt,
            ) {
                val request = annotationNavigationRequest?.takeIf { it.tabId == tab.id } ?: return@LaunchedEffect
                if (request.id == satisfiedAnnotationNavId) return@LaunchedEffect
                // CHANGE B: largeFileMode's async recompute (rememberComputedLogItems) can keep
                // serving the PREVIOUS item list — built from an older `expanded` set — for up to
                // LOADING_GRACE_MS with `loading == false`. Resolving against that stale list would
                // scroll to an index computed under the wrong fold state and still mark the request
                // satisfied, wasting the jump (the user would have to press again). Bail before doing
                // any resolution work; expandedAt is in this effect's keys, so it re-runs the instant
                // a fresh list lands — this is what makes a single click work instead of two.
                val itemsStale = computedItems.expandedAt != tab.expanded
                val allItemsStale = computedAllItems.expandedAt != tab.expanded
                if (itemsStale || allItemsStale) return@LaunchedEffect
                // Staleness is already ruled out by the guard above, so this is purely "a fresh list is
                // still being computed".
                val stillLoading = computedItems.loading || computedAllItems.loading
                if (request.scrollMode == NavigationScrollMode.FOLLOW) {
                    var filteredIdx = request.logIds.firstNotNullOfOrNull { entryId ->
                        items.indexOfEntry(entryId).takeIf { it >= 0 }
                    }
                    var allIdx = request.logIds.firstNotNullOfOrNull { entryId ->
                        allItems.indexOfEntry(entryId).takeIf { it >= 0 }
                    }
                    // A direct miss only gets the expand treatment when AppState explicitly asked
                    // for it (a collapsed group is genuinely hiding the row) — a plain FOLLOW with
                    // no un-clamped target never opens anything, matching the historical clamp
                    // behavior for filter-hidden rows (see AppState.followRevealTarget).
                    if ((filteredIdx == null || allIdx == null) && request.expandCollapsedGroups) {
                        var opened = tab.expanded
                        if (filteredIdx == null) {
                            val target = withContext(Dispatchers.Default) {
                                request.logIds.firstNotNullOfOrNull { entryId ->
                                    expansionAndIndexForEntry(tab, applyFilter = true, entryId = entryId, currentItems = items)
                                }
                            }
                            if (target != null) {
                                (target.expanded - opened).forEach { gid -> onToggleGroup(gid) }
                                awaitExpandedAt(target.expanded) { computedItems.expandedAt }
                                opened = opened + target.expanded
                                filteredIdx = target.index
                            }
                        }
                        if (allIdx == null) {
                            // tab.copy(expanded = opened): the filtered branch above may already
                            // have toggled a gid this same effect run — reflect that instead of
                            // resolving against the pre-toggle expanded set (1.3(c)).
                            val target = withContext(Dispatchers.Default) {
                                request.logIds.firstNotNullOfOrNull { entryId ->
                                    expansionAndIndexForEntry(
                                        tab.copy(expanded = opened), applyFilter = false,
                                        entryId = entryId, currentItems = computedAllItems.items,
                                    )
                                }
                            }
                            if (target != null) {
                                (target.expanded - opened).forEach { gid -> onToggleGroup(gid) }
                                awaitExpandedAt(target.expanded) { computedAllItems.expandedAt }
                                opened = opened + target.expanded
                                allIdx = target.index
                            }
                        }
                    }
                    if (filteredIdx == null && allIdx == null && stillLoading) {
                        // Items are still being computed (largeFileMode's async path) — don't drop
                        // the request; computedItems.loading/computedAllItems.loading are in this
                        // effect's keys, so it re-runs once they settle.
                        return@LaunchedEffect
                    }
                    if (filteredIdx != null || allIdx != null) satisfiedAnnotationNavId = request.id
                    filteredIdx?.let { filteredLazyState.followItem(it) }
                    allIdx?.let { allLazyState.followItem(it) }
                    onConsumeAnnotationNavigation(request.id)
                    return@LaunchedEffect
                }
                val filteredTarget = withContext(Dispatchers.Default) {
                    request.logIds.firstNotNullOfOrNull { entryId ->
                        expansionAndIndexForEntry(tab, applyFilter = true, entryId = entryId, currentItems = items)
                    }
                }
                // Existence probe only (1.3(c)) — its .index/.expanded are resolved against the
                // pre-filteredTarget-toggle expanded set and must never be used directly; see the
                // real originalTarget recompute further down, after that toggle has landed.
                val originalTargetProbe = withContext(Dispatchers.Default) {
                    request.logIds.firstNotNullOfOrNull { entryId ->
                        expansionAndIndexForEntry(tab, applyFilter = false, entryId = entryId, currentItems = allItems)
                    }
                }
                if (filteredTarget == null && originalTargetProbe == null && stillLoading) {
                    return@LaunchedEffect
                }
                if (filteredTarget != null || originalTargetProbe != null) {
                    localAllSelected = request.logIds.toSet()
                    var opened = tab.expanded
                    filteredTarget?.let { target ->
                        (target.expanded - opened).forEach { gid -> onToggleGroup(gid) }
                        awaitExpandedAt(target.expanded) { computedItems.expandedAt }
                        opened = opened + target.expanded
                        filteredLazyState.centerOnItem(target.index)
                    }
                    var originalTarget: ExpansionAndIndexTarget? = null
                    if (originalTargetProbe != null) {
                        // Recomputed AFTER filteredTarget's own toggle has landed (1.3(c)): both
                        // panels share tab.expanded, so a fold opened above this entry's position in
                        // the Original list shifts its true index — resolving it up front (like
                        // originalTargetProbe above) would use a soon-to-be-wrong row count whenever
                        // that toggle actually inserts rows ahead of it.
                        originalTarget = withContext(Dispatchers.Default) {
                            request.logIds.firstNotNullOfOrNull { entryId ->
                                expansionAndIndexForEntry(
                                    tab.copy(expanded = opened), applyFilter = false,
                                    entryId = entryId, currentItems = computedAllItems.items,
                                )
                            }
                        }
                    }
                    originalTarget?.let { target ->
                        (target.expanded - opened).forEach { gid -> onToggleGroup(gid) }
                        awaitExpandedAt(target.expanded) { computedAllItems.expandedAt }
                        opened = opened + target.expanded
                        allLazyState.centerOnItem(target.index)
                    }
                    satisfiedAnnotationNavId = request.id
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
            // See satisfiedAnnotationNavId above for why this is needed (CHANGE 4 / bug B): it
            // guards this effect's own search-nav request against being re-applied by a restart
            // triggered mid-flight by its own onToggleGroup calls (keyed on tab.expanded), which
            // would otherwise re-expand a group the user had since manually collapsed.
            var satisfiedSearchNavId by remember(tab.id) { mutableStateOf(-1L) }
            LaunchedEffect(
                searchNavigationRequest?.id, itemsVersion, allItemsVersion, tab.expanded,
                computedItems.loading, computedAllItems.loading, computedItems.expandedAt, computedAllItems.expandedAt,
            ) {
                val request = searchNavigationRequest?.takeIf { it.tabId == tab.id } ?: return@LaunchedEffect
                if (request.id == satisfiedSearchNavId) return@LaunchedEffect
                // CHANGE B: see the split-view annotation-nav effect above for why this must run
                // before any resolution — a stale item list would otherwise center on the wrong
                // index and still consume the request, so the user's click would need a second press.
                val itemsStale = computedItems.expandedAt != tab.expanded
                val allItemsStale = computedAllItems.expandedAt != tab.expanded
                if (itemsStale || allItemsStale) return@LaunchedEffect
                var opened = tab.expanded
                val filteredTarget = withContext(Dispatchers.Default) {
                    expansionAndIndexForEntry(tab, applyFilter = true, entryId = request.entryId, currentItems = items)
                }
                if (filteredTarget != null) {
                    if (filteredTarget.expanded != opened) {
                        // A collapsed group had to open to reveal the match at all — a real
                        // enough change that centering (like any other reveal-and-jump) reads
                        // right, unlike the minimal-scroll branch below.
                        (filteredTarget.expanded - opened).forEach { gid -> onToggleGroup(gid) }
                        awaitExpandedAt(filteredTarget.expanded) { computedItems.expandedAt }
                        opened = opened + filteredTarget.expanded
                        filteredLazyState.centerOnItem(filteredTarget.index)
                    } else {
                        // Already expanded: scroll only the minimum needed to keep navScrollMargin
                        // rows of context around the target (scrollForCursor), a no-op if it's
                        // already comfortably on screen — no top-then-recenter flash.
                        scrollForCursor(filteredLazyState, syncScope, filteredTarget.index, navScrollMargin)
                    }
                }
                // tab.copy(expanded = opened) / computedAllItems.items: resolved AFTER
                // filteredTarget's own toggle (if any) has landed, not against the pre-toggle
                // expanded set — see 1.3(c).
                val originalTarget = withContext(Dispatchers.Default) {
                    expansionAndIndexForEntry(
                        tab.copy(expanded = opened), applyFilter = false,
                        entryId = request.entryId, currentItems = computedAllItems.items,
                    )
                }
                if (originalTarget != null) {
                    // Original panel has its own independent selection (localAllSelected) — keep it
                    // in sync with the match too, same as a Filtered-panel row click does.
                    localAllSelected = setOf(request.entryId)
                    if (originalTarget.expanded != opened) {
                        (originalTarget.expanded - opened).forEach { gid -> onToggleGroup(gid) }
                        awaitExpandedAt(originalTarget.expanded) { computedAllItems.expandedAt }
                        opened = opened + originalTarget.expanded
                        allLazyState.centerOnItem(originalTarget.index)
                    } else {
                        scrollForCursor(allLazyState, syncScope, originalTarget.index, navScrollMargin)
                    }
                }
                if (filteredTarget == null && originalTarget == null &&
                    (computedItems.loading || computedAllItems.loading)
                ) {
                    // Items are still being computed or stale — don't give up on this request yet;
                    // the loading/expandedAt flags above are in this effect's keys, so it re-runs
                    // once they settle.
                    return@LaunchedEffect
                }
                if (filteredTarget != null || originalTarget != null) satisfiedSearchNavId = request.id
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
                val originalTarget = withContext(Dispatchers.Default) {
                    expansionAndIndexForEntry(tab, applyFilter = false, entryId = targetId, currentItems = allItems)
                }
                if (originalTarget != null) {
                    (originalTarget.expanded - opened).forEach { gid -> onToggleGroup(gid) }
                    awaitExpandedAt(originalTarget.expanded) { computedAllItems.expandedAt }
                    opened = opened + originalTarget.expanded
                    allLazyState.centerOnItem(originalTarget.index)
                }
                // tab.copy(expanded = opened) / computedItems.items: resolved AFTER originalTarget's
                // own toggle (if any) has landed — see 1.3(c).
                val filteredTarget = withContext(Dispatchers.Default) {
                    expansionAndIndexForEntry(
                        tab.copy(expanded = opened), applyFilter = true,
                        entryId = targetId, currentItems = computedItems.items,
                    )
                }
                if (filteredTarget != null) {
                    (filteredTarget.expanded - opened).forEach { gid -> onToggleGroup(gid) }
                    awaitExpandedAt(filteredTarget.expanded) { computedItems.expandedAt }
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
                    // Original panel's Δt values use its local selection, independent of the
                    // Filtered panel's tab.selected below; the gutter width itself stays fixed.
                    val originalTimeDeltaChars = rememberTimeDeltaChars(tab, allItems)
                    ColHeader(
                        hasPidTid,
                        showRowNumbers = settings.showRowNumbers,
                        rowNumDigits = tab.logData.size.toString().length,
                        showTimeDelta = tab.showTimeDelta,
                        timeDeltaChars = originalTimeDeltaChars,
                        hasTidMap = tab.tidMap != null,
                        pidFieldChars = pidFieldChars,
                        contentFontSizeSp = settings.fontSize,
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
                    val filteredTimeDeltaChars = rememberTimeDeltaChars(tab, items)
                    ColHeader(
                        hasPidTid,
                        showRowNumbers = settings.showRowNumbers,
                        rowNumDigits = tab.logData.size.toString().length,
                        showTimeDelta = tab.showTimeDelta,
                        timeDeltaChars = filteredTimeDeltaChars,
                        hasTidMap = tab.tidMap != null,
                        pidFieldChars = pidFieldChars,
                        contentFontSizeSp = settings.fontSize,
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
                                // Resolved by the rowClickSync LaunchedEffect below, not inline in
                                // this callback (1.3(a)) — that used to run on syncScope with no
                                // staleness guard, so a click landing inside the 250ms
                                // LOADING_GRACE_MS window right after ANY expand (exactly what
                                // pressing an Issues-panel entry does) would resolve against a
                                // stale allItems and scroll to the wrong row with no retry.
                                rowClickSyncId = id
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
            // Same interaction with tail-follow as the split-view cluster above: any scroll these
            // two effects (or the keyboard nav in handleNavKey/handleSelKey below) perform on
            // mainLazyState suspends this panel's follow, on purpose — see that comment for why.
            // See the split-view annotation-nav effect above for why this is needed (CHANGE 4 /
            // bug B): guards against this effect re-applying an already-handled request when its
            // own onToggleGroup calls (via the tab.expanded key) cancel and restart it before it
            // reaches onConsumeAnnotationNavigation.
            var satisfiedAnnotationNavId by remember(tab.id) { mutableStateOf(-1L) }
            LaunchedEffect(
                annotationNavigationRequest?.id, itemsVersion, tab.expanded,
                computedItems.loading, computedItems.expandedAt,
            ) {
                val request = annotationNavigationRequest?.takeIf { it.tabId == tab.id } ?: return@LaunchedEffect
                if (request.id == satisfiedAnnotationNavId) return@LaunchedEffect
                // CHANGE B: see the split-view annotation-nav effect above for the full rationale —
                // largeFileMode can still be serving a stale (older-`expanded`) item list here with
                // loading == false; resolving against it would scroll to a wrong index and still
                // consume the request. Bail before doing any resolution work; expandedAt is in this
                // effect's keys, so it re-runs once the fresh list lands — this is what makes a
                // single click work instead of needing two.
                val itemsStale = computedItems.expandedAt != tab.expanded
                if (itemsStale) return@LaunchedEffect
                if (request.scrollMode == NavigationScrollMode.FOLLOW) {
                    val directIdx = request.logIds.firstNotNullOfOrNull { entryId ->
                        items.indexOfEntry(entryId).takeIf { it >= 0 }
                    }
                    var followedIdx = directIdx
                    // A direct miss only gets the expand treatment when AppState explicitly asked
                    // for it — see the split-view FOLLOW branch above for the full rationale.
                    if (directIdx == null && request.expandCollapsedGroups) {
                        val target = withContext(Dispatchers.Default) {
                            request.logIds.firstNotNullOfOrNull { entryId ->
                                expansionAndIndexForEntry(tab, applyFilter = true, entryId = entryId, currentItems = items)
                            }
                        }
                        if (target != null) {
                            (target.expanded - tab.expanded).forEach { gid -> onToggleGroup(gid) }
                            awaitExpandedAt(target.expanded) { computedItems.expandedAt }
                            mainLazyState.followItem(target.index)
                            followedIdx = target.index
                        }
                    } else {
                        directIdx?.let { mainLazyState.followItem(it) }
                    }
                    if (followedIdx == null && computedItems.loading) {
                        // Items still being computed or stale (largeFileMode) — don't drop the
                        // request; loading/expandedAt are in this effect's keys, so it re-runs once
                        // items settle.
                        return@LaunchedEffect
                    }
                    if (followedIdx != null) satisfiedAnnotationNavId = request.id
                    onConsumeAnnotationNavigation(request.id)
                    return@LaunchedEffect
                }
                val target = withContext(Dispatchers.Default) {
                    request.logIds.firstNotNullOfOrNull { entryId ->
                        expansionAndIndexForEntry(tab, applyFilter = true, entryId = entryId, currentItems = items)
                    }
                }
                if (target == null && computedItems.loading) {
                    return@LaunchedEffect
                }
                if (target != null) {
                    (target.expanded - tab.expanded).forEach { gid -> onToggleGroup(gid) }
                    awaitExpandedAt(target.expanded) { computedItems.expandedAt }
                    mainLazyState.centerOnItem(target.index)
                    satisfiedAnnotationNavId = request.id
                }
                onConsumeAnnotationNavigation(request.id)
            }
            // Find bar's own navigation channel — see SearchNavigationRequest's doc comment and
            // the split-view LaunchedEffect above for why this stays separate from
            // annotationNavigationRequest and scrolls minimally instead of always centering.
            // See the split-view search-nav effect above for why this is needed (CHANGE 4 / bug B):
            // this effect's own onToggleGroup call changes tab.expanded, which — via itemsVersion,
            // since the item list itself shifts when a fold opens or closes — can cancel and
            // restart this effect before it reaches onConsumeSearchNavigation. Without this guard a
            // restart landing right after the user manually collapsed the very group this request
            // just opened would see it collapsed again and re-expand it, exactly bug (B).
            var satisfiedSearchNavId by remember(tab.id) { mutableStateOf(-1L) }
            LaunchedEffect(searchNavigationRequest?.id, itemsVersion, computedItems.loading, computedItems.expandedAt) {
                val request = searchNavigationRequest?.takeIf { it.tabId == tab.id } ?: return@LaunchedEffect
                if (request.id == satisfiedSearchNavId) return@LaunchedEffect
                // CHANGE B: see the split-view annotation-nav effect above for the full rationale —
                // bail before resolving against a possibly-stale list so a single click can't resolve
                // against the old fold state and silently waste the jump.
                val itemsStale = computedItems.expandedAt != tab.expanded
                if (itemsStale) return@LaunchedEffect
                val target = withContext(Dispatchers.Default) {
                    expansionAndIndexForEntry(tab, applyFilter = true, entryId = request.entryId, currentItems = items)
                }
                if (target == null && computedItems.loading) {
                    // Items still being computed or stale (largeFileMode) — don't give up yet;
                    // loading/expandedAt are in this effect's keys, so it re-runs once settled.
                    return@LaunchedEffect
                }
                if (target != null) {
                    if (target.expanded != tab.expanded) {
                        (target.expanded - tab.expanded).forEach { gid -> onToggleGroup(gid) }
                        awaitExpandedAt(target.expanded) { computedItems.expandedAt }
                        mainLazyState.centerOnItem(target.index)
                    } else {
                        scrollForCursor(mainLazyState, searchNavScope, target.index, navScrollMargin)
                    }
                    satisfiedSearchNavId = request.id
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
            val mainTimeDeltaChars = rememberTimeDeltaChars(tab, items)
            ColHeader(
                hasPidTid,
                showRowNumbers = settings.showRowNumbers,
                rowNumDigits = tab.logData.size.toString().length,
                showTimeDelta = tab.showTimeDelta,
                timeDeltaChars = mainTimeDeltaChars,
                hasTidMap = tab.tidMap != null,
                pidFieldChars = pidFieldChars,
                contentFontSizeSp = settings.fontSize,
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

// Row-count-based centering fallback, used only when centerOnItem's own pixel-offset approach
// (below) fails to converge within CENTER_ON_ITEM_MAX_ROUNDS — see that function's doc for why the
// pixel-offset approach is preferred: this one drifts whenever the rows above the target are a
// different average height than the ones the estimate was based on (a SeqHeader vs. a plain Row,
// wrapped multi-line rows vs. single-line ones). Averages whatever's visible AT AND BELOW the
// target after a scrollToItem(index) and walks back by roughly that many rows' worth of pixels —
// approximate (row heights vary), but was the primary mechanism before 1.3(d); kept only as a
// last-resort fallback now.
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

// Pure geometry for tail-follow (Part 5), extracted the same way centerAnchorIndex above is: the
// surrounding Compose scroll machinery can't be exercised headlessly, but these two decisions can.
//
// "At the newest line" means the last REAL row is on screen — not that the trailing "tail-space"
// spacer (a real lazy item at index listItems.size, see the LazyColumn below) is fully exposed.
// Requiring the latter would force the user to scroll an extra half-viewport past the last line
// before following resumes, which reads as broken. lastVisibleIndex landing ON the spacer still
// counts: it can only get there by first passing over the last row, and clamping there (rather
// than treating it as "not yet at the bottom") is what lets scrollToItem overshoot slightly
// without spuriously un-following. A null lastVisibleIndex (no layout yet, or a genuinely empty
// list) defaults to true — nothing to disagree with, and defaulting to true is what lets the
// follow effect below scroll on its very first composition instead of waiting for a real
// "not following" reading first.
internal fun isAtLastRow(lastVisibleIndex: Int?, lastRowIndex: Int): Boolean =
    lastVisibleIndex == null || lastVisibleIndex >= lastRowIndex

// Placing the tail-space SPACER's top at the viewport bottom puts the last row's bottom edge
// exactly there, with no row-height estimate needed — LazyListState's scrollOffset convention is
// "this many px below the viewport top" (the same convention followItem above already relies on),
// so a negative offset of exactly one viewport height pulls the spacer's top up to the viewport's
// bottom edge. A log shorter than one viewport clamps to 0 automatically (LazyListState coerces an
// over-large scroll), which is also the correct picture: everything is already on screen.
// viewportHeight <= 0 (layout not measured yet) returns 0 rather than a meaningless positive
// offset — scrollToItem(spacerIndex, 0) is a harmless no-op until real layout info exists.
internal fun newestRowScrollOffset(viewportHeight: Int): Int = if (viewportHeight <= 0) 0 else -viewportHeight

// 1.3(d): centerAnchorIndex's row-count-based backward walk (averaging the height of whatever
// happened to be visible AT AND BELOW the target after a first plain scrollToItem(index)) drifts
// badly whenever the rows above the target are a different shape than the rows below it — wrapped
// multi-line rows above, single-line rows below made avgRowHeight too small, so the walk-back
// landed far too early and the target ended up entirely below the viewport. Prefer the exact form
// followItem (below) already uses successfully instead: scrollToItem(index, scrollOffset =
// -(viewportHeight / 2)) works in real pixels, not an estimated row count, so it isn't fooled by
// non-uniform row heights. One call is usually enough, but the first jump can land into
// previously-unmeasured rows whose estimated height Compose's lazy layout guessed at — a follow-up
// round re-issues the same call with the now-measured, more accurate layout, iterated up to
// CENTER_ON_ITEM_MAX_ROUNDS and stopping the moment the target is verified converged (see
// isItemPlacementConverged). The old average-height walk is kept only as a last-resort fallback
// for the rare case this never converges.
private const val CENTER_ON_ITEM_MAX_ROUNDS = 3

// A small pixel slack for the "pinned to the top" tolerance below — scrollToItem(index,
// scrollOffset = 0) should land the target's top exactly at 0, but a sub-pixel rounding or a
// layout pass that hasn't fully settled yet could leave it off by a hair; a few px is still
// visually indistinguishable from pinned.
private const val TALL_ROW_TOP_TOLERANCE_PX = 4

// Whether `offset`/`size` (a LazyListItemInfo's own offset and size, relative to the viewport
// start) represents a placement worth stopping the centerOnItem correction loop at. Extracted as a
// pure function (mirroring centerAnchorIndex above) so it's unit-testable without the surrounding
// Compose scroll machinery.
//
// A row that fits entirely within the viewport converges once it actually does (offset >= 0 and
// its bottom edge at/before viewportHeight) — the normal case. But a single log row CAN be taller
// than the viewport: with wrap-on-overflow enabled, a very long line (a raw stack trace, a JSON
// dump, a base64 blob — item 3 of the original bug report is literally about long lines, so these
// are exactly the tabs where navigation gets used) wraps to more visual lines than the window is
// tall. For such a row, "fits entirely" can never be satisfied — checking for it anyway burned
// every round on a no-op scrollToItem(index, -(viewportHeight/2)) call (the same placement each
// time, since nothing about the measurement changes) and then fell through to the average-height
// fallback, which 1.3(d) established is the LESS accurate placement, actively making the already-
// centered-as-well-as-possible row worse. Once a row is known taller than the viewport there is no
// placement that shows all of it, so the best available one is its top edge pinned at the viewport
// top (offset ~= 0) — that's the part of a long line a reader wants first, and centering it would
// instead push the beginning off-screen above.
internal fun isItemPlacementConverged(offset: Int, size: Int, viewportHeight: Int): Boolean {
    if (viewportHeight <= 0) return true // nothing meaningful to converge against
    return if (size >= viewportHeight) {
        kotlin.math.abs(offset) <= TALL_ROW_TOP_TOLERANCE_PX
    } else {
        offset >= 0 && offset + size <= viewportHeight
    }
}

private suspend fun LazyListState.centerOnItem(index: Int) {
    repeat(CENTER_ON_ITEM_MAX_ROUNDS) {
        val info = layoutInfo
        val viewportHeight = info.viewportEndOffset - info.viewportStartOffset
        if (viewportHeight <= 0) {
            scrollToItem(index)
            return
        }
        // Once a previous round has actually measured the target as taller than the viewport,
        // stop trying to CENTER it — pin its top edge to the viewport top instead (see
        // isItemPlacementConverged's doc for why centering it would be strictly worse).
        val knownTooTall = info.visibleItemsInfo.firstOrNull { it.index == index }
            ?.let { it.size >= viewportHeight } == true
        scrollToItem(index, scrollOffset = if (knownTooTall) 0 else -(viewportHeight / 2))
        withFrameNanos { }
        val after = layoutInfo
        val afterViewportHeight = after.viewportEndOffset - after.viewportStartOffset
        val target = after.visibleItemsInfo.firstOrNull { it.index == index } ?: return@repeat
        if (isItemPlacementConverged(target.offset, target.size, afterViewportHeight)) return
    }
    // Fallback: the iterative pixel-offset approach above never converged — fall back to the
    // original average-row-height backward walk rather than leaving the target wherever the last
    // round's scrollToItem happened to land.
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return
    val viewportHeight = info.viewportEndOffset - info.viewportStartOffset
    val anchorIndex = centerAnchorIndex(index, viewportHeight, visible.map { it.size })
    if (anchorIndex != index) scrollToItem(anchorIndex)
}

// A wide center band supplies the hysteresis needed for a continuously-moving playhead: adjacent
// rows can update selection without issuing another scroll, while a target that leaves the middle
// third is brought back near centre in one operation. Unlike centerOnItem this never first jumps
// to the target at the viewport edge, so there is no visible bounce.
private suspend fun LazyListState.followItem(index: Int) {
    val info = layoutInfo
    val viewportHeight = info.viewportEndOffset - info.viewportStartOffset
    if (viewportHeight <= 0) return
    val visibleTarget = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (visibleTarget != null) {
        val targetCenter = visibleTarget.offset + visibleTarget.size / 2
        val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
        // Stay put while the target occupies the middle third. This avoids needless tiny
        // corrections as the playhead progresses row-by-row.
        if (kotlin.math.abs(targetCenter - viewportCenter) <= viewportHeight / 3) return
    }
    // LazyListState's offset is relative to the viewport start. A negative half-viewport offset
    // places the item near the middle in one scroll, including when it was previously offscreen.
    scrollToItem(index, scrollOffset = -(viewportHeight / 2))
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
//
// Also the vehicle for keyboard nav (handleNavKey/handleSelKey) to move the viewport, so — like
// the other scroll effects above — pressing an arrow/page key away from the last row suspends
// tail-follow. Deliberate: the keyboard is the most explicit possible "I'm looking at this row"
// signal there is.
// 1.3(d): the `+ margin - visible.size + 1` arithmetic assumes the rows about to be scrolled INTO
// view are the same height as the ones already on screen — wrong whenever they aren't (e.g. the
// cursor moving from a run of single-line rows into a run of wrapped multi-line ones), landing
// short of or past the intended margin. Re-verify against the REAL post-scroll layout and correct
// again if needed, same iterate-and-verify shape as centerOnItem above, capped the same way.
private const val SCROLL_FOR_CURSOR_MAX_ROUNDS = 3

private fun scrollForCursor(lazyState: LazyListState, scope: CoroutineScope, targetItemsIdx: Int, margin: Int) {
    val visible = lazyState.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) {
        scope.launch { lazyState.scrollToItem(maxOf(0, targetItemsIdx - margin)) }
        return
    }
    val firstVisible = visible.first().index
    val lastVisible = visible.last().index
    // Already comfortably within the margin band: the pre-existing no-scroll contract (see the
    // class doc above) — a cursor move inside the middle never triggers a scroll at all.
    if (targetItemsIdx in (firstVisible + margin)..(lastVisible - margin)) return
    val firstGuess = when {
        targetItemsIdx < firstVisible + margin -> (targetItemsIdx - margin).coerceAtLeast(0)
        else -> (targetItemsIdx + margin - visible.size + 1).coerceAtLeast(0)
    }
    if (firstGuess == firstVisible) return
    scope.launch {
        var next = firstGuess
        repeat(SCROLL_FOR_CURSOR_MAX_ROUNDS) {
            lazyState.scrollToItem(next)
            withFrameNanos { }
            val after = lazyState.layoutInfo.visibleItemsInfo
            if (after.isEmpty()) return@launch
            val afterFirst = after.first().index
            val afterLast = after.last().index
            if (targetItemsIdx in (afterFirst + margin)..(afterLast - margin)) return@launch
            next = when {
                targetItemsIdx < afterFirst + margin -> (targetItemsIdx - margin).coerceAtLeast(0)
                else -> (targetItemsIdx + margin - after.size + 1).coerceAtLeast(0)
            }
            if (next == afterFirst) return@launch
        }
    }
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

// Truncates in the MIDDLE rather than at the end, for the process-name badge (LogRow, below).
// Android package names are dominated by a shared, low-information prefix (com.google.android.*,
// com.example.*), while the tail often carries what actually distinguishes one from another (a
// `:process` suffix, or the final segment) — keeping both ends visible reads better than an
// end-truncated "com.example.reall…" that hides exactly the part that would tell two similarly-
// named processes apart. A no-op when [text] already fits.
internal fun middleEllipsis(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val keep = (maxChars - 1).coerceAtLeast(2)
    val head = (keep + 1) / 2
    val tail = keep - head
    return text.take(head) + "…" + text.takeLast(tail)
}

// Whether LogRow's hover popup has anything to offer for this row. Only worth showing when a name
// is actually being rendered ([processDisplay] non-null — OFF mode and pid<=0 rows never resolve
// one) AND middleEllipsis (above) actually shortened it to fit [pidFieldWidth]: a name that already
// renders in full has nothing left for the popup to reveal. Pulled out as a pure function (like
// pidFieldCharWidth/middleEllipsis above) so it's unit-testable without the Compose harness this
// codebase doesn't have — LogRow (below) uses it to skip the hover-tracking/layout-capture work
// entirely on the common case of a short or absent name.
internal fun shouldShowProcessNamePopup(processDisplay: String?, pidFieldWidth: Int): Boolean =
    processDisplay != null && processDisplay.length > pidFieldWidth

// Whether [pointerX] — a pointer position captured by LogRow's own hover pointerInput block, in the
// same px coordinate space the row's BasicTextField measures in — falls inside [fieldStartX,
// fieldEndX], the pid field's horizontal span for THIS row's actual rendered text layout.
//
// That span is deliberately supplied as already-measured pixel bounds rather than computed here:
// ui/Theme.kt:269-275's NOTE records two earlier attempts to locate a position between the
// timestamp and PID columns arithmetically (assuming "HH:MM:SS.mmm" is always 12 characters, then
// measuring the row's own rendered text) that were both reverted as fragile, since timestamps are
// absent entirely on some rows (brief format, or an empty LogEntry.ts). LogRow instead reads
// TextLayoutResult.getBoundingBox for the field's first/last rendered character — the exact pixel
// Rect Skia actually laid the text out at — and passes the resulting [fieldStartX, fieldEndX] in
// here untouched.
internal fun pointerInsidePidFieldX(pointerX: Float, fieldStartX: Float, fieldEndX: Float): Boolean =
    pointerX in fieldStartX..fieldEndX

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
    onLogRowDoubleClick: ((Int) -> Unit)? = null,
    onLogRowDoubleClickGestureStarted: (() -> Unit)? = null,
    onLogRowDoubleClickGestureExpired: (() -> Unit)? = null,
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
    // Fixed Δt column width in characters. Its constant value mirrors rowNumDigits' role for the
    // row-number gutter: every row and the header consume the same budget.
    timeDeltaChars: Int = 1,
    // Whether this tab has an active tid map — reserves TID_MAP_HIT_WIDTH as a leading, EMPTY
    // spacer (the actual spine/branch graphics are drawn by the separate TidMapOverlay Canvas that
    // sits over the whole panel, not by this row itself) so the row's own content — and every other
    // gutter after this one — starts exactly where the overlay expects it to. See LogViewer.kt's
    // tidMapSpineX for the other half of this contract.
    hasTidMap: Boolean = false,
    // The standing preference (Settings → Appearance, the log toolbar's options popup) —
    // OFF (the default) never reads tab.analysis.processNames/manualProcessNamePicks at all below,
    // which is what guarantees this row renders pixel-identical to before this feature existed.
    processNameMode: ProcessNameMode = ProcessNameMode.OFF,
    // The uniform per-tab pid-FIELD character width (LogViewer's pidFieldCharWidth, computed once
    // per tab — see its own doc for why every row shares one value instead of sizing to its own
    // content). Always 5 in mode OFF, which is what keeps this row byte-identical to before this
    // feature existed even before processDisplay is resolved below.
    pidFieldChars: Int = 5,
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

    // Null whenever OFF, whenever this pid has no learned name, or (MANUAL) whenever this pid
    // hasn't been picked — in every one of those cases the row below takes the untouched original
    // rendering path, byte-for-byte, so OFF (and any row a non-OFF mode doesn't apply to) stays
    // pixel-identical to before this feature existed. entry.pid <= 0 rows (RAW-fallback lines,
    // LogParser's own convention) are never in processNames to begin with, so they fall out of this
    // the same way they already fall out of the pid/tid segment entirely (see buildFullLineAnnotation).
    val processDisplay = resolveProcessDisplayName(
        processNameMode, tab.analysis.processNames, tab.manualProcessNamePicks, entry.pid,
    )

    // Whether this row's name is truncated enough that a hover popup has something to reveal (see
    // shouldShowProcessNamePopup's own doc). False for OFF, for every row without a name, and for
    // the common case of a name that already fits — which is what keeps the pointer-tracking/layout
    // capture below from doing any work on the vast majority of rows even when a mode is on.
    val showsElidedName = shouldShowProcessNamePopup(processDisplay, pidFieldChars)
    val latestShowsElidedName by rememberUpdatedState(showsElidedName)
    // The pid field's exact horizontal pixel span in THIS row's own rendered text layout — written
    // once by the BasicTextField's onTextLayout below via TextLayoutResult.getBoundingBox (see
    // pointerInsidePidFieldX's own doc for why that, and not arithmetic, is what locates it). Only
    // ever written when showsElidedName is true, so it stays null (and unread) on every other row.
    var pidFieldXRange by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    // Last pointer x seen by the "hd" pointerInput block below, only tracked while showsElidedName
    // is true.
    var hoverPointerX by remember { mutableStateOf(0f) }
    var popupAnchorHeightPx by remember { mutableStateOf(0) }
    val showProcessNamePopup = hov && showsElidedName &&
        pidFieldXRange?.let { (start, end) -> pointerInsidePidFieldX(hoverPointerX, start, end) } == true

    val isCrashGroupRow = isCrashGroupRow(item.groupColor, highlightEntireCrashGroup)
    // Toned exactly like the sequence header's own ts/pid-tid cells (HeaderPidTidCell's call site
    // uses sc.copy(.7f) — see that composable's doc) so a row belonging to a THREAD-SCOPED
    // sequence's run is visibly distinguishable at a glance from the interleaved foreign-thread
    // lines around it, which stay in the ordinary muted tc.td. null (the ordinary/unscoped case,
    // and every foreign-thread row) falls straight through to tc.td unchanged. Left at 0.7 even now
    // that cellBg below adds a background wash of the SAME colour underneath: the wash sits at
    // tc.seqCellBgAlpha (0.16-0.22, tuned per light/dark — see that field's own doc), a big enough
    // alpha gap under this 0.7 foreground that the text stays legible rather than reading as one
    // muddy same-hue-on-itself block, in both light and dark themes.
    val tsColor = item.scopedSeqColor?.copy(alpha = 0.7f) ?: tc.td
    val pidColor = item.scopedSeqColor?.copy(alpha = 0.7f) ?: tc.td.copy(0.5f)
    // Background companion to tsColor/pidColor above — same null-for-unscoped fallthrough, but
    // Color.Unspecified (SpanStyle's own "no paint" value) rather than a theme colour, since an
    // unscoped/ordinary row must not get any cell fill at all (pixel-identical to before this was
    // added). See appendTsPidTid's own doc for why this is two separate per-field washes rather
    // than one spanning the "  " gap between them.
    val cellBg = item.scopedSeqColor?.copy(alpha = tc.seqCellBgAlpha)
    val annoLine = remember(
        tab.id, entry, tab.filter, tsColor, pidColor, cellBg, tc.ts, tc.tx, wrapLimitChars, isCrashGroupRow, autoWrap,
        searchHighlight, processDisplay, pidFieldChars,
    ) {
        val tagColor = if (isCrashGroupRow) DANGER_RED else tc.ts
        val msgColor = if (isCrashGroupRow) DANGER_RED else tc.tx
        val built = buildFullLineAnnotation(
            entry,
            tab.filter.highlighters,
            tsColor,
            pidColor,
            tagColor,
            msgColor,
            tab.filter,
            regexContext,
            searchHighlight,
            // Change 3: the pid field renders inline now (name-or-number, padded to pidFieldChars),
            // so this is a single BasicTextField again end to end — see this function's own doc for
            // why that matters for drag-selection.
            processDisplay = processDisplay,
            pidFieldWidth = pidFieldChars,
            cellBg = cellBg,
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
                    var pressUnmodified = false
                    var pressDragged = false
                    var pendingSelectedRowToggle: Job? = null
                    var pendingDoubleClickGestureExpiry: Job? = null
                    var lastPrimaryPressMs = 0L
                    var lastPrimaryPressPos = Offset.Unspecified
                    var doubleClickInProgress = false
                    try {
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
                                        pressUnmodified = !mods.isShiftPressed && !mods.isCtrlPressed &&
                                            !mods.isMetaPressed && !mods.isAltPressed
                                        pressDragged = false
                                        if (pressUnmodified) {
                                            if (doubleClickInProgress) {
                                                pendingDoubleClickGestureExpiry?.cancel()
                                            } else {
                                                onLogRowDoubleClickGestureStarted?.invoke()
                                                pendingDoubleClickGestureExpiry?.cancel()
                                                pendingDoubleClickGestureExpiry = clickScope.launch {
                                                    kotlinx.coroutines.delay(DOUBLE_CLICK_WINDOW_MS)
                                                    onLogRowDoubleClickGestureExpired?.invoke()
                                                }
                                            }
                                        }
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
                                    // Run alongside (rather than instead of) BasicTextField's own
                                    // second-click handling. It therefore preserves desktop word
                                    // selection while adding only the independent video seek action.
                                    if (doubleClickInProgress && !pressDragged && pressUnmodified) {
                                        onLogRowDoubleClick?.invoke(entry.id)
                                    } else if (doubleClickInProgress && pressDragged) {
                                        // The second press was a drag rather than a real double-click;
                                        // do not leave Follow held while the user continues selecting.
                                        onLogRowDoubleClickGestureExpired?.invoke()
                                    }
                                    pressPos = null
                                    pressShift = false
                                    pressMulti = false
                                    pressUnmodified = false
                                    pressDragged = false
                                    doubleClickInProgress = false
                                }
                                else -> {}
                            }
                        }
                    } finally {
                        pendingDoubleClickGestureExpiry?.cancel()
                        onLogRowDoubleClickGestureExpired?.invoke()
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
                            // Only recorded when this row can actually show the popup — every other
                            // row's Move events fall through to the else branch below, no state
                            // write, no recomposition triggered by hovering it.
                            PointerEventType.Move -> if (latestShowsElidedName) {
                                ev.changes.firstOrNull()?.let { hoverPointerX = it.position.x }
                            }
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
        // Empty reserved space for the tid-map gutter (see hasTidMap's own doc above) — leading,
        // before row-number/Δt/text, matching where TidMapOverlay's own Canvas is offset to.
        if (hasTidMap) {
            Spacer(Modifier.width(TID_MAP_HIT_WIDTH))
        }
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
        // since timeDeltaChars is fixed, that width remains stable while selecting a row, and the
        // small difference (ROW_NUM_GAP) becomes the trailing gap before whatever comes next — the same
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
        // Video anchor badge (plan doc's Task B) — this row is the one and only VideoAnchor.logId
        // for tab.attachedVideo, if any. A plain icon (no tooltip): rows are the hottest path in
        // this composable (every visible row, every recomposition), and the match is rare enough
        // (at most one row per tab) that the extra affordance isn't worth the per-row cost of
        // wiring up TooltipArea's own pointer-event tracking on every row just to support it.
        if (tab.attachedVideo?.anchor?.logId == entry.id) {
            Icon(
                Icons.Outlined.Movie, contentDescription = "Linked to the attached video",
                tint = tc.ac, modifier = Modifier.size(12.dp).padding(end = 4.dp),
            )
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
        // Change 3 (process-names rework): the process name (when shown) now renders INSIDE
        // annoLine's single BasicTextField below, padded inline to a uniform per-tab pid-field
        // width (appendTsPidTid/pidFieldCharWidth) — no separate composable, so drag-selection
        // spans the whole row again even on a row showing a name. This used to be two extra
        // composables here (a plain ts AppText plus a TooltipArea-wrapped name AppText) specifically
        // to hang a hover tooltip off the name; splitting the field out that way is exactly what
        // broke whole-row drag-selection on named rows.
        //
        // The hover popup below (Change: process-name hover popup) restores a way to see the full
        // name WITHOUT re-splitting the field: it reads this SAME BasicTextField's own
        // TextLayoutResult (onTextLayout) to find the pid field's exact rendered pixel span via
        // getBoundingBox, rather than computing an x-offset arithmetically (see
        // pointerInsidePidFieldX's own doc, and ui/Theme.kt:269-275's NOTE, for why two earlier
        // attempts at the latter were reverted). The wrapping Box below only exists so the Popup can
        // sit alongside the field as a sibling — Popup has no content slot of its own on
        // BasicTextField — and does not affect drag-selection, which is entirely a property of the
        // single BasicTextField inside it.
        Box(Modifier.weight(1f)) {
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 18.dp)
                    .onSizeChanged { if (showsElidedName) popupAnchorHeightPx = it.height },
                // Measuring bounding boxes only runs for a row that can actually show the popup —
                // every other row (OFF, no name, or a name that already fits) skips this entirely,
                // same as the pointer-tracking guard above. pidFieldStart mirrors
                // buildFullLineAnnotation's own "entry.ts.length + 2" — the same formula against the
                // same rendered text, since the field starts at the same offset in both.
                onTextLayout = { layout ->
                    if (showsElidedName) {
                        val pidFieldStart = entry.ts.length + 2
                        val pidFieldEnd = (pidFieldStart + pidFieldChars).coerceAtMost(layout.layoutInput.text.length)
                        if (pidFieldEnd > pidFieldStart) {
                            val left = layout.getBoundingBox(pidFieldStart).left
                            val right = layout.getBoundingBox(pidFieldEnd - 1).right
                            pidFieldXRange = left to right
                        }
                    }
                },
            )
            val fieldRange = pidFieldXRange
            if (showProcessNamePopup && fieldRange != null && processDisplay != null) {
                // Same visual treatment and TopStart+measured-offset positioning as FilterPanel.kt's
                // FullTextHint (the house precedent for a hover popup driven by layout information)
                // — see its own doc for why a guessed constant offset flickers. x is the field's own
                // measured left edge (fieldRange.first); y drops the popup below the row using this
                // field's own measured height, exactly mirroring FullTextHint's anchorHeightPx.
                val gapPx = (4f * density).roundToInt()
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(fieldRange.first.roundToInt(), popupAnchorHeightPx + gapPx),
                    properties = PopupProperties(focusable = false),
                ) {
                    Box(
                        Modifier
                            .background(tc.p, CORNER_SM)
                            .border(1.dp, tc.br, CORNER_SM)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        AppText(
                            processDisplay, color = tc.tx, fontSize = 11.sp, fontFamily = mono,
                            maxLines = 1, overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
        }
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

// Shared, empty tid-map gutter reservation for the three group/collapse header row types below —
// mirrors LogRow's own leading `hasTidMap` spacer (see its doc comment), first in the Row so the
// header's row-number/Δt gutters and text line up with the body rows below it exactly the way they
// already do for showRowNumbers/showTimeDelta.
@Composable
private fun HeaderTidMapGutterCell(hasTidMap: Boolean) {
    if (hasTidMap) Spacer(Modifier.width(TID_MAP_HIT_WIDTH))
}

// Shared row-number gutter for the three group/collapse header row types below. The header entry is
// still a real log entry, so it uses its stable parse-order ID just like LogRow.
@Composable
private fun HeaderRowNumberCell(
    showRowNumbers: Boolean,
    entryId: Int,
    tab: LogTab,
    mono: FontFamily,
    tc: ThemeColors,
) {
    if (!showRowNumbers) return
    val fontSize = baseSp()
    val numColWidth = rowNumberColumnWidth(fontSize.value, tab.logData.size.toString().length)
    Box(Modifier.width(numColWidth + ROW_NUM_GAP).padding(end = ROW_NUM_GAP)) {
        AppText(
            entryId.toString(),
            color = tc.td, fontSize = fontSize, fontFamily = mono, maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}

// Shared Δt gutter cell for the three group/collapse header row types below (SeqHeaderRow/
// ManualHeaderRow/StackTraceHeaderRow) — rendered exactly as a plain row does: same width
// formula (timeDeltaColumnWidth), same left alignment and warn-tint rule, same "—" no-baseline
// placeholder, using the header's OWN entry (its ts is what deltaMs was already computed against
// upstream, same as any other item — see the itemsIndexed lambda's shared deltaMs block).
//
// Positioned immediately after HeaderRowNumberCell, so enabling both settings keeps the two
// gutters in the same order as a normal log row.
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

// Shared PID/TID cell for the three group/collapse header row types below. Body rows render their
// PID/TID inside LogRow's single BasicTextField (appendTsPidTid), so this is a separate composable
// rather than a shared helper called from both places — but it deliberately mirrors that same
// "pid tid" text shape (pid padStart to the tab's uniform pidFieldChars, tid always padStart(5))
// and sits in the same left-to-right slot (right after the timestamp+level text, before the tag),
// so a header's PID/TID lands in the same column body rows use. Unlike LogRow, a header never
// resolves a process display name here — headers already carry enough distinct text (chevron,
// tag, message, entry/frame count) that adding name resolution's own width churn wasn't worth it
// for a single anchor entry; the bare numbers are enough to show which thread/process this group's
// anchor line belongs to. `entry.pid <= 0` (RAW-fallback lines) omits the cell entirely, matching
// LogRow's own `if (entry.pid > 0)` guard in appendTsPidTid.
@Composable
private fun HeaderPidTidCell(entry: LogEntry, color: Color, mono: FontFamily, pidFieldChars: Int) {
    if (entry.pid <= 0) return
    AppText(
        "${entry.pid.toString().padStart(pidFieldChars)} ${entry.tid.toString().padStart(5)}",
        color = color, fontSize = 11.sp, fontFamily = mono, maxLines = 1, overflow = TextOverflow.Clip,
    )
}

// Compact "this run is pinned to one thread" marker for a thread-scoped (Wave 2.1 "async")
// SequenceDef's header — the ONLY visible cue that distinguishes it from an ordinary sequence
// before this, a user had no way to tell from the log view alone why two interleaved runs of the
// same flow rendered as separate groups. item.scopeTid is always the header entry's own tid when
// set (the start pattern only matches on that thread to begin with — see SeqComputer's
// matchesSeqText), so this doesn't need its own tid lookup, just item.scopeTid itself. Rendered in
// the sequence's own color (sc) with a filled pill background so it reads as a distinct badge next
// to the plain-text PID/TID cell rather than more of the same muted metadata.
@Composable
private fun ScopeTidBadge(scopeTid: Int?, color: Color, mono: FontFamily) {
    if (scopeTid == null) return
    Box(
        Modifier.background(color.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        AppText("tid $scopeTid", color = color, fontSize = 10.sp, fontFamily = mono, fontWeight = FontWeight.Bold)
    }
}

// Shared message cell for the three group/collapse header row types below. Unlike LogRow's message
// (an AnnotatedString rendered through a real BasicTextField — see the comment above LogRow's
// autoWrap param), headers render msg as plain AppText, so it never picked up either of LogRow's
// two wrap behaviors: Auto mode's reliance on real font-measured width, or Manual mode's fixed-
// chars-per-line break. This mirrors both: in Auto mode msg is left untouched and AppText's own
// softWrap does the wrapping against the Row's real available width, same mechanism LogRow's Auto
// path uses (minus the zero-estimation-error nuance that only matters for BasicTextField's caret/
// selection math, which a header — not editable, not selectable text — doesn't need); in Manual
// mode msg is pre-broken with visualLogLineForWrapLimit at wrapLimitChars, the SAME helper LogRow's
// Manual path (AnnotatedString overload) uses, so a header wraps at the same fixed column the body
// rows below it do, rather than at whatever width happens to remain after the tag/timestamp
// columns eat into the Row. Capped at 3 lines with an ellipsis so one very long header can't
// dominate the visible row budget, and the full, un-wrapped, un-truncated line is always one hover
// away via the tooltip — same TooltipArea shape as VideoPanel.kt's fullPath tooltip.
@Composable
private fun RowScope.HeaderMessageCell(
    msg: String,
    color: Color,
    mono: FontFamily,
    tc: ThemeColors,
    autoWrap: Boolean,
    wrapLimitChars: Int,
) {
    val displayMsg = remember(msg, autoWrap, wrapLimitChars) {
        if (autoWrap) msg else visualLogLineForWrapLimit(msg, wrapLimitChars)
    }
    TooltipArea(
        tooltip = {
            Box(
                Modifier.background(tc.p2, CORNER_SM)
                    .border(0.5.dp, tc.br, CORNER_SM)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .widthIn(max = 480.dp),
            ) {
                AppText(msg, color = tc.tx, fontSize = 11.sp, maxLines = 16, overflow = TextOverflow.Ellipsis)
            }
        },
        modifier = Modifier.weight(1f),
    ) {
        AppText(
            displayMsg, color = color, fontSize = 12.sp, fontFamily = mono, fontWeight = FontWeight.Medium,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
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
    showRowNumbers: Boolean = false,
    showTimeDelta: Boolean = false,
    deltaMs: Long? = null,
    deltaSelectionAnchored: Boolean = false,
    timeDeltaChars: Int = 1,
    hasTidMap: Boolean = false,
    // Threaded in from the same settings.autoLogRowWrap / effectiveWrapLimitChars the LazyColumn's
    // itemsIndexed lambda already computes for LogRow (see the call site above) — see
    // HeaderMessageCell's own doc for why the header message needs both to wrap "the same way body
    // rows do" instead of the plain-AppText single-line clip it used to get.
    autoWrap: Boolean = false,
    wrapLimitChars: Int = MIN_WRAP_LIMIT_CHARS,
    // Same per-tab uniform width LogRow's own PID cell uses (LogViewer's pidFieldCharWidth) — see
    // HeaderPidTidCell's own doc for why this stays a bare-number cell rather than also resolving
    // a process display name.
    pidFieldChars: Int = 5,
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
            .padding(start = ROW_START_PAD, end = 8.dp, top = ROW_V_PAD, bottom = ROW_V_PAD),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HeaderTidMapGutterCell(hasTidMap)
        HeaderRowNumberCell(showRowNumbers, item.entry.id, tab, mono, tc)
        HeaderTimeDeltaCell(showTimeDelta, deltaMs, deltaSelectionAnchored, timeDeltaChars, mono, tc)
        if (item.indent > 0) Spacer(Modifier.width(INDENT_STEP * item.indent))
        CollapseChevron(expanded = item.expanded, color = sc, mono = mono, onClick = { onToggleGroup(item.gid) })
        AppText("${item.entry.ts}  ${item.entry.level.key}", color = sc.copy(.7f), fontSize = 11.sp, fontFamily = mono)
        HeaderPidTidCell(item.entry, sc.copy(.7f), mono, pidFieldChars)
        ScopeTidBadge(item.scopeTid, sc, mono)
        AppText("${item.entry.tag}:", color = sc, fontSize = 11.sp, fontFamily = mono,
            modifier = Modifier.widthIn(min = 120.dp, max = 520.dp), overflow = TextOverflow.Clip)
        HeaderMessageCell(item.entry.msg, sc, mono, tc, autoWrap, wrapLimitChars)
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
    showRowNumbers: Boolean = false,
    showTimeDelta: Boolean = false,
    deltaMs: Long? = null,
    deltaSelectionAnchored: Boolean = false,
    timeDeltaChars: Int = 1,
    hasTidMap: Boolean = false,
    // See SeqHeaderRow's identical params / HeaderMessageCell's doc.
    autoWrap: Boolean = false,
    wrapLimitChars: Int = MIN_WRAP_LIMIT_CHARS,
    pidFieldChars: Int = 5,
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
        HeaderTidMapGutterCell(hasTidMap)
        HeaderRowNumberCell(showRowNumbers, item.entry.id, tab, mono, tc)
        HeaderTimeDeltaCell(showTimeDelta, deltaMs, deltaSelectionAnchored, timeDeltaChars, mono, tc)
        CollapseChevron(expanded = item.expanded, color = sc, mono = mono, onClick = { onToggleGroup(item.gid) })
        val label = when (item.direction) {
            ManualCollapseDirection.TO_START -> "Collapsed to file start"
            ManualCollapseDirection.TO_END -> "Collapsed to file end"
            ManualCollapseDirection.RANGE -> "Collapsed selection"
        }
        AppText(label, color = sc, fontSize = 11.sp, fontFamily = mono, fontWeight = FontWeight.SemiBold)
        AppText("${item.entry.ts}  ${item.entry.level.key}", color = sc.copy(.7f), fontSize = 11.sp, fontFamily = mono)
        HeaderPidTidCell(item.entry, sc.copy(.7f), mono, pidFieldChars)
        AppText("${item.entry.tag}:", color = sc, fontSize = 11.sp, fontFamily = mono,
            modifier = Modifier.widthIn(min = 120.dp, max = 520.dp), overflow = TextOverflow.Clip)
        HeaderMessageCell(item.entry.msg, sc, mono, tc, autoWrap, wrapLimitChars)
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
    showRowNumbers: Boolean = false,
    showTimeDelta: Boolean = false,
    deltaMs: Long? = null,
    deltaSelectionAnchored: Boolean = false,
    timeDeltaChars: Int = 1,
    hasTidMap: Boolean = false,
    // See SeqHeaderRow's identical params / HeaderMessageCell's doc.
    autoWrap: Boolean = false,
    wrapLimitChars: Int = MIN_WRAP_LIMIT_CHARS,
    pidFieldChars: Int = 5,
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
            .padding(start = ROW_START_PAD, end = 8.dp, top = ROW_V_PAD, bottom = ROW_V_PAD),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HeaderTidMapGutterCell(hasTidMap)
        HeaderRowNumberCell(showRowNumbers, item.entry.id, tab, mono, tc)
        HeaderTimeDeltaCell(showTimeDelta, deltaMs, deltaSelectionAnchored, timeDeltaChars, mono, tc)
        if (item.indent > 0) Spacer(Modifier.width(INDENT_STEP * item.indent))
        CollapseChevron(expanded = item.expanded, color = sc, mono = mono, onClick = { onToggleGroup(item.gid) })
        AppText("${item.entry.ts}  ${item.entry.level.key}", color = sc.copy(.7f), fontSize = 11.sp, fontFamily = mono)
        HeaderPidTidCell(item.entry, sc.copy(.7f), mono, pidFieldChars)
        AppText("${item.entry.tag}:", color = sc, fontSize = 11.sp, fontFamily = mono,
            modifier = Modifier.widthIn(min = 120.dp, max = 520.dp), overflow = TextOverflow.Clip)
        HeaderMessageCell(item.entry.msg, sc, mono, tc, autoWrap, wrapLimitChars)
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
    processNameMode: ProcessNameMode,
    manualProcessNamePicks: Set<Int>,
    onToggleRowNumbers: () -> Unit,
    onToggleMinimap: () -> Unit,
    onSetProcessNameMode: (ProcessNameMode) -> Unit,
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
            // Second entry point for the mode (Settings → Appearance carries the full three-way
            // choice — see SettingsDialog.kt's EditorBehaviorSettingsSection). This one is a plain
            // two-state toggle, styled exactly like Show/Hide row numbers and Show/Hide minimap
            // above: OFF shows names (sets ALL), and ALL or MANUAL hides them (sets OFF). MANUAL is
            // never entered from here — it's only ever entered by picking an individual process
            // from a row's context menu (see Change 2/CtxProcessActions) — so this toggle only ever
            // needs to represent "off" vs "some names showing," not the full three-way state.
            HoverBox(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSetProcessNameMode(toggledProcessNameMode(processNameMode, manualProcessNamePicks)) },
            ) {
                AppText(
                    if (processNamesVisible(processNameMode, manualProcessNamePicks)) "Hide process names" else "Show process names",
                    color = tc.tx,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}
