@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.openlog.model.*
import com.openlog.utils.computeItems
import com.openlog.utils.regexRanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.awt.Cursor as AwtCursor

private const val PAGE_JUMP_ROWS = 15
private const val CTX_MENU_KEYBOARD_X_DP = 60f

private fun hlRanges(msg: String, hl: Highlighter): List<Pair<Int, Int>> =
    if (hl.regex) {
        regexRanges(msg, hl.pattern)
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

internal fun nextOriginalSelectionAfterFilteredSelection(filteredSelection: Set<Int>): Set<Int> = filteredSelection

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
    if (!tab.largeFileMode) {
        return remember(tab.id, dataSize, lastId, filter, expanded, manualBlocks, applyFilter) {
            val items = computeItems(tab, applyFilter)
            ComputedLogItems(items, summarizeItems(items), loading = false)
        }
    }

    var computed by remember(tab.id, applyFilter) {
        mutableStateOf(ComputedLogItems(emptyList(), EMPTY_SUMMARY, loading = true))
    }
    LaunchedEffect(tab.id, dataSize, lastId, filter, expanded, manualBlocks, applyFilter) {
        computed = ComputedLogItems(computed.items, computed.summary, loading = true)
        val snapshot = tab.copy(selected = emptySet())
        computed = withContext(Dispatchers.Default) {
            val items = computeItems(snapshot, applyFilter)
            ComputedLogItems(items, summarizeItems(items), loading = false)
        }
    }
    return computed
}

internal data class AnnotationNavigationTarget(
    val filteredEntryId: Int?,
    val originalEntryId: Int?,
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

// IntArray twin of the above for the drag-select hot path — no per-element boxing.
internal fun visibleRowRangeIds(fromId: Int, toId: Int, visibleIds: IntArray): List<Int> {
    val a = visibleIds.indexOf(fromId)
    val b = visibleIds.indexOf(toId)
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

class LogViewerScrollStateStore {
    private val lazyStates = mutableMapOf<String, LazyListState>()
    private val horizontalStates = mutableMapOf<String, ScrollState>()

    fun lazyState(panelKey: String): LazyListState =
        lazyStates.getOrPut(panelKey) { LazyListState() }

    fun horizontalState(panelKey: String): ScrollState =
        horizontalStates.getOrPut(panelKey) { ScrollState(0) }

    fun removeTab(tabId: String) {
        val prefix = "$tabId:"
        lazyStates.keys.removeAll { it.startsWith(prefix) }
        horizontalStates.keys.removeAll { it.startsWith(prefix) }
    }
}

private fun fullLineText(entry: LogEntry): String = buildString {
    append(entry.ts)
    if (entry.pid > 0) {
        append("  ")
        append(entry.pid.toString().padStart(5))
        append(" ")
        append(entry.tid.toString().padStart(5))
    }
    append("  ")
    append(entry.level.key)
    append("  ")
    append(entry.tag)
    append(": ")
    append(entry.msg)
}

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
    val lineText = fullLineText(entry)
    for (hl in highlighters.filter { it.on && it.pattern.isNotBlank() }) {
        hlRanges(lineText, hl).forEach { (s, e) ->
            if (s < e && e <= lineText.length)
                addStyle(SpanStyle(background = hl.color.copy(alpha = 0.6f), fontWeight = FontWeight.SemiBold), s, e)
        }
    }
}

@Composable
fun LogViewer(
    tab: LogTab,
    modifier: Modifier = Modifier,
    onSelRow: (Int, Boolean, Boolean) -> Unit,
    onSelRowRange: (List<Int>) -> Unit = { _ -> },
    onCtxMenu: (Int, Float, Float, String, Set<Int>) -> Unit,
    onToggleGroup: (String) -> Unit,
    onClearFilter: () -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    onToggleUnfiltered: () -> Unit,
    onExportTxt: () -> Unit,
    onExportCsv: () -> Unit,
    scrollStateStore: LogViewerScrollStateStore? = null,
    annotationNavigationRequest: AnnotationNavigationRequest? = null,
    onConsumeAnnotationNavigation: (Long) -> Unit = {},
    onSelectAll: (() -> Unit)? = null,
    onClearSelection: (() -> Unit)? = null,
    onCopySelection: ((Set<Int>?) -> Unit)? = null,
    navScrollMargin: Int = 5,
    focusRequester: FocusRequester? = null,
    onPanelFocusChanged: (Boolean) -> Unit = {},
    keyboardFocusVisible: Boolean = false,
    // Pushes each freshly computed filtered item summary up to AppState so selection ops
    // (shift-click range, select-all) can reuse it instead of recomputing on the UI thread.
    onVisibleItems: ((ItemsSummary) -> Unit)? = null,
) {
    val tc        = tc()
    val mono      = monoFont()
    val scrollStates = scrollStateStore ?: remember { LogViewerScrollStateStore() }
    val computedItems = rememberComputedLogItems(tab, true)
    val items = computedItems.items
    val itemsVersion = items.size to items.lastOrNull()?.let(::logItemEntryId)
    val visCnt = computedItems.summary.rowCount
    val totalCnt  = tab.logData.size
    val hasPidTid = remember(tab.id) { tab.logData.any { it.pid > 0 } }
    LaunchedEffect(computedItems) {
        if (!computedItems.loading) onVisibleItems?.invoke(computedItems.summary)
    }
    val canExpandAll = computedItems.summary.collapsedGroupCount > 0
    val canCollapseAll = computedItems.summary.expandedGroupCount > 0
    var toolbarIndex by remember(tab.id) { mutableStateOf<Int?>(null) }
    var exportMenuOpen by remember(tab.id) { mutableStateOf(false) }

    // Row bounds for global drag-select (plain HashMap avoids recomposition on scroll updates)
    val rowBoundsAbs = remember { HashMap<Int, Pair<Float, Float>>() }
    val boxPosY      = remember { floatArrayOf(0f) }

    // Clear stale bounds from previous tab so drag-select uses correct positions
    LaunchedEffect(tab.id) { rowBoundsAbs.clear() }

    fun toolbarActions(): List<Pair<Boolean, () -> Unit>> = listOf(
        true to { exportMenuOpen = true },
        canExpandAll to onExpandAll,
        canCollapseAll to onCollapseAll,
        true to onToggleUnfiltered,
    )

    fun toolbarRovingItems(): List<RovingItem> =
        toolbarActions().mapIndexed { idx, action -> RovingItem(idx.toString(), action.first) }

    Column(modifier.fillMaxSize().background(tc.bg)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).background(tc.p).border(BorderStroke(1.dp, tc.br)),
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
            val countLabel = if (tab.largeFileMode) "$visCnt / $totalCnt entries - large file mode" else "$visCnt / $totalCnt entries"
            AppText(countLabel, color = tc.td, fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.weight(1f))
            AppButton(
                "Expand all",
                onClick = onExpandAll,
                enabled = canExpandAll,
                modifier = Modifier.border(1.dp, if (toolbarIndex == 1) tc.ac else Color.Transparent, CORNER_MD),
            )
            Spacer(Modifier.width(4.dp))
            AppButton(
                "Collapse all",
                onClick = onCollapseAll,
                enabled = canCollapseAll,
                modifier = Modifier.border(1.dp, if (toolbarIndex == 2) tc.ac else Color.Transparent, CORNER_MD),
            )
            Spacer(Modifier.width(4.dp))
            AppButton(
                if (tab.showUnfiltered) "Hide original" else "Unfiltered",
                onClick = onToggleUnfiltered,
                modifier = Modifier.border(1.dp, if (toolbarIndex == 3) tc.ac else Color.Transparent, CORNER_MD),
            )
            Spacer(Modifier.width(8.dp))
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
            val lazyState = listState ?: scrollStates.lazyState(panelKey)
            val hScroll   = scrollStates.horizontalState(panelKey)
            val scrollbarStyle = appScrollbarStyle(tc)
            val density = LocalDensity.current
            val verticalScrollbarGutterPx = with(density) { 16.dp.toPx() }
            val horizontalScrollbarGutterPx = with(density) { 18.dp.toPx() }
            val visibleIds = listSummary.allIds
            val currentOnSelRowRange by rememberUpdatedState(itemOnSelRowRange)
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
                            val firstRow = listItems.filterIsInstance<LogItem.Row>().firstOrNull()?.entry?.id
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
                        if (handleNavKey(ev, listItems, effectiveTab, lazyState, navScope, navScrollMargin,
                                selCursor, onSelectRow = { id -> itemOnSelRowRange(listOf(id)) }))
                            return@onPreviewKeyEvent true
                        handleSelKey(ev, listItems, effectiveTab, lazyState, navScope, navScrollMargin,
                            itemOnSelRowRange, selCursor,
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
                        LazyColumn(
                            state = lazyState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(
                                items = listItems,
                                key = { item -> logItemStableKey(effectiveTab.id, item) }
                            ) { item ->
                                when (item) {
                                    is LogItem.Row       -> LogRow(item, effectiveTab, mono, tc, hScroll, itemOnSelRow, itemOnCtxMenu, boundsMap)
                                    is LogItem.SeqHeader ->
                                        SeqHeaderRow(item, effectiveTab, mono, tc, hScroll, itemOnSelRow, itemOnCtxMenu, onToggleGroup, boundsMap)
                                    is LogItem.ManualHeader ->
                                        ManualHeaderRow(item, effectiveTab, mono, tc, hScroll, itemOnSelRow, itemOnCtxMenu, onToggleGroup, boundsMap)
                                    is LogItem.StackTraceHeader ->
                                        StackTraceHeaderRow(item, effectiveTab, mono, tc, hScroll, itemOnSelRow, itemOnCtxMenu, onToggleGroup, boundsMap)
                                }
                            }
                            item(key = "tail-space") {
                                Spacer(Modifier.height(maxHeight * 0.5f))
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(lazyState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            style = scrollbarStyle,
                        )
                    }
                    HorizontalScrollbar(
                        adapter = rememberScrollbarAdapter(hScroll),
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                        style = scrollbarStyle,
                    )
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

            fun originalExpansionAndIndexFor(entryId: Int): Pair<Set<String>, Int>? {
                val currentIdx = computedAllItems.summary.allIds.indexOf(entryId)
                if (currentIdx >= 0) return tab.expanded to currentIdx
                if (tab.largeFileMode) return null
                var expanded = tab.expanded
                repeat(24) {
                    val candidateItems = computeItems(tab.copy(expanded = expanded), false)
                    val visibleIdx = candidateItems.indexOfFirst { item ->
                        when (item) {
                            is LogItem.Row -> item.entry.id == entryId
                            is LogItem.SeqHeader -> item.entry.id == entryId
                            is LogItem.ManualHeader -> item.entry.id == entryId
                            is LogItem.StackTraceHeader -> item.entry.id == entryId
                        }
                    }
                    if (visibleIdx >= 0) return expanded to visibleIdx
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
                        computeItems(tab.copy(expanded = expanded + gid), false).any { item ->
                            when (item) {
                                is LogItem.Row -> item.entry.id == entryId
                                is LogItem.SeqHeader -> item.entry.id == entryId
                                is LogItem.ManualHeader -> item.entry.id == entryId
                                is LogItem.StackTraceHeader -> item.entry.id == entryId
                            }
                        }
                    } ?: ranked.firstOrNull() ?: return null
                    expanded = expanded + groupToOpen
                }
                return null
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
                            val a = visIds.indexOf(last); val b = visIds.indexOf(id)
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
                val target = annotationNavigationTarget(request.logIds, computedItems.summary.allIds, originalOpen = true)
                if (target != null) {
                    localAllSelected = request.logIds.toSet()
                    target.filteredEntryId?.let { filteredEntryId ->
                        val idx = computedItems.summary.allIds.indexOf(filteredEntryId)
                        if (idx >= 0) filteredLazyState.centerOnItem(idx)
                    }
                    target.originalEntryId?.let { originalEntryId ->
                        val originalTarget = originalExpansionAndIndexFor(originalEntryId)
                        if (originalTarget != null) {
                            val (expanded, idx) = originalTarget
                            (expanded - tab.expanded).forEach { gid -> onToggleGroup(gid) }
                            if (expanded != tab.expanded) kotlinx.coroutines.delay(80)
                            allLazyState.centerOnItem(idx)
                        }
                    }
                }
                onConsumeAnnotationNavigation(request.id)
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
                val originalTarget = originalExpansionAndIndexFor(targetId)
                if (originalTarget != null) {
                    val (expanded, idx) = originalTarget
                    (expanded - tab.expanded).forEach { gid -> onToggleGroup(gid) }
                    if (expanded != tab.expanded) kotlinx.coroutines.delay(80)
                    allLazyState.centerOnItem(idx)
                }
                val filteredIdx = computedItems.summary.allIds.indexOf(targetId)
                if (filteredIdx >= 0) filteredLazyState.centerOnItem(filteredIdx)
            }

            Column(
                Modifier.fillMaxWidth().weight(1f)
                    .onGloballyPositioned { containerH = it.size.height / density }
            ) {
                // Fixed height for Panel1 → cursor drag adds directly to splitDp → 1:1 tracking.
                Column(Modifier.fillMaxWidth().height(effectiveSplitDp.dp)) {
                    SectionBanner("Original — $totalCnt lines", tc.seq1, tc)
                    ColHeader(hasPidTid)
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
                    ColHeader(hasPidTid)
                    ItemList(
                        listItems = items,
                        listSummary = computedItems.summary,
                        boundsMap = rowBoundsAbs,
                        posY = boxPosY,
                        itemOnSelRow = { id, multi, range ->
                            onSelRow(id, multi, range)
                            if (!multi && !range) {
                                localAllSelected = nextOriginalSelectionAfterFilteredSelection(
                                    filteredSelection = setOf(id),
                                )
                                val target = originalExpansionAndIndexFor(id)
                                if (target != null) syncScope.launch {
                                    val (expanded, idx) = target
                                    (expanded - tab.expanded).forEach { gid -> onToggleGroup(gid) }
                                    if (expanded != tab.expanded) kotlinx.coroutines.delay(80)
                                    allLazyState.centerOnItem(idx)
                                }
                            }
                        },
                        itemOnSelRowRange = { ids ->
                            onSelRowRange(ids)
                            localAllSelected = nextOriginalSelectionAfterFilteredSelection(
                                filteredSelection = ids.toSet(),
                            )
                        },
                        panelKey = "${tab.id}:main",
                        listState = filteredLazyState,
                    )
                }
            }
        } else {
            val mainLazyState = scrollStates.lazyState("${tab.id}:main")
            LaunchedEffect(annotationNavigationRequest?.id, itemsVersion) {
                val request = annotationNavigationRequest?.takeIf { it.tabId == tab.id } ?: return@LaunchedEffect
                val target = annotationNavigationTarget(request.logIds, computedItems.summary.allIds, originalOpen = false)
                if (target != null) {
                    target.filteredEntryId?.let { filteredEntryId ->
                        val idx = computedItems.summary.allIds.indexOf(filteredEntryId)
                        if (idx >= 0) mainLazyState.centerOnItem(idx)
                    }
                }
                onConsumeAnnotationNavigation(request.id)
            }
            ColHeader(hasPidTid)
            ItemList(
                items, computedItems.summary, rowBoundsAbs, boxPosY,
                panelKey = "${tab.id}:main", listState = mainLazyState,
                externalFr = focusRequester, onFocusChangedExternal = onPanelFocusChanged,
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
private suspend fun LazyListState.centerOnItem(index: Int) {
    scrollToItem(index)
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return
    val avgRowHeight = visible.sumOf { it.size } / visible.size
    if (avgRowHeight <= 0) return
    val viewportHeight = info.viewportEndOffset - info.viewportStartOffset
    val rowsToHalfViewport = (viewportHeight / 2) / avgRowHeight
    val anchorIndex = (index - rowsToHalfViewport).coerceAtLeast(0)
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

private fun handleNavKey(
    ev: KeyEvent,
    items: List<LogItem>,
    tab: LogTab,
    lazyState: LazyListState,
    scope: CoroutineScope,
    scrollMargin: Int,
    cursor: SelectionCursor,
    onSelectRow: (Int) -> Unit,
): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    val rows = items.filterIsInstance<LogItem.Row>()
    if (rows.isEmpty()) return false

    fun cursorIdx(): Int {
        val cur = cursor.effectiveCursorId(tab)
        return if (cur != null) {
            rows.indexOfFirst { it.entry.id == cur }.coerceAtLeast(0)
        } else {
            val vis = lazyState.firstVisibleItemIndex
            rows.indexOfFirst { r -> items.indexOf(r) >= vis }.coerceAtLeast(0)
        }
    }

    fun moveTo(rowIdx: Int) {
        val i = rowIdx.coerceIn(0, rows.lastIndex)
        cursor.onAnchorChange(null)
        // Always replace the selection outright (never toggle): keyboard nav must stay
        // idempotent even if the same target row is selected again by a duplicate key event.
        onSelectRow(rows[i].entry.id)
        cursor.onCursorChange(rows[i].entry.id)
        scrollForCursor(lazyState, scope, items.indexOf(rows[i]), scrollMargin)
    }

    return when {
        (ev.isMetaPressed || ev.isCtrlPressed) && ev.key == Key.DirectionUp   -> { moveTo(0); true }
        (ev.isMetaPressed || ev.isCtrlPressed) && ev.key == Key.DirectionDown -> { moveTo(rows.lastIndex); true }
        ev.key == Key.MoveHome   -> { moveTo(0); true }
        ev.key == Key.MoveEnd    -> { moveTo(rows.lastIndex); true }
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
    actions: SelKeyActions,
): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    val rows = items.filterIsInstance<LogItem.Row>()
    val isAction = if (isMacOs) ev.isMetaPressed else ev.isCtrlPressed

    fun cursorIdx(): Int {
        val cur = cursor.effectiveCursorId(tab)
        return if (cur != null) {
            rows.indexOfFirst { it.entry.id == cur }.coerceAtLeast(0)
        } else {
            val vis = lazyState.firstVisibleItemIndex
            rows.indexOfFirst { r -> items.indexOf(r) >= vis }.coerceAtLeast(0)
        }
    }

    fun extendTo(newRowIdx: Int) {
        if (rows.isEmpty()) return
        val clamped = newRowIdx.coerceIn(0, rows.lastIndex)
        val target = rows[clamped].entry.id
        val anchor = cursor.anchorId ?: tab.selected.minOrNull() ?: target
        cursor.onAnchorChange(anchor)
        cursor.onCursorChange(target)
        val anchorIdx = rows.indexOfFirst { it.entry.id == anchor }.coerceAtLeast(0)
        val lo = minOf(anchorIdx, clamped)
        val hi = maxOf(anchorIdx, clamped)
        onSelRowRange(rows.subList(lo, hi + 1).map { it.entry.id })
        scrollForCursor(lazyState, scope, items.indexOf(rows[clamped]), scrollMargin)
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
    hScroll: ScrollState,
    onSelRow: (Int, Boolean, Boolean) -> Unit,
    onCtxMenu: (Int, Float, Float, String) -> Unit,
    rowBoundsAbs: HashMap<Int, Pair<Float, Float>>,
) {
    val density  = LocalDensity.current.density
    val entry    = item.entry
    val isSel    = entry.id in tab.selected
    var hov      by remember { mutableStateOf(false) }
    var rowRoot  by remember { mutableStateOf(Offset.Zero) }
    var sel      by remember(tab.id, entry.id) { mutableStateOf(TextRange.Zero) }
    val fontSize = baseSp()
    DisposableEffect(entry.id, rowBoundsAbs) {
        onDispose { rowBoundsAbs.remove(entry.id) }
    }

    val annoLine = remember(tab.id, entry, tab.filter.highlighters, tc.td, tc.ts, tc.tx) {
        buildFullLineAnnotation(entry, tab.filter.highlighters, tc.td, tc.td.copy(0.5f), tc.ts, tc.tx)
    }

    val levelColor = entry.level.defaultColor
    val bg = when { isSel -> tc.sl; hov -> tc.hv; else -> Color.Transparent }
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
                awaitPointerEventScope {
                    var pressPos: Offset? = null
                    var pressShift = false
                    var pressMulti = false
                    var pressDragged = false
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        when (ev.type) {
                            PointerEventType.Press -> {
                                val mods = ev.keyboardModifiers
                                if (ev.buttons.isSecondaryPressed) {
                                    ev.changes.forEach { it.consume() }
                                    val selText = if (!sel.collapsed)
                                        runCatching { annoLine.text.substring(sel.min, sel.max) }.getOrElse { "" }
                                    else ""
                                    val ch = ev.changes.firstOrNull() ?: continue
                                    onCtxMenu(
                                        entry.id,
                                        (rowRoot.x + ch.position.x) / density,
                                        (rowRoot.y + ch.position.y) / density,
                                        selText,
                                    )
                                } else if (ev.buttons.isPrimaryPressed) {
                                    pressPos = ev.changes.firstOrNull()?.position
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
                                if (!pressDragged && pressPos != null) {
                                    onSelRow(entry.id, pressMulti, pressShift)
                                }
                                pressPos = null
                                pressShift = false
                                pressMulti = false
                                pressDragged = false
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
            .padding(start = ROW_START_PAD + INDENT_STEP * item.indent, end = 8.dp, top = ROW_V_PAD, bottom = ROW_V_PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        Box(Modifier.fillMaxWidth().horizontalScroll(hScroll)) {
            BasicTextField(
                value         = TextFieldValue(annotatedString = annoLine, selection = sel),
                onValueChange = { new -> sel = new.selection },
                readOnly      = true,
                singleLine    = true,
                textStyle     = TextStyle(color = tc.tx, fontFamily = mono, fontSize = fontSize, lineHeight = (fontSize.value + 4).sp),
                cursorBrush   = SolidColor(Color.Transparent),
                modifier      = Modifier.widthIn(min = 2000.dp).heightIn(min = 18.dp),
            )
        }
    }
}

@Composable
private fun SeqHeaderRow(
    item: LogItem.SeqHeader,
    tab: LogTab,
    mono: FontFamily,
    tc: ThemeColors,
    hScroll: ScrollState,
    onSelRow: (Int, Boolean, Boolean) -> Unit,
    onCtxMenu: (Int, Float, Float, String) -> Unit,
    onToggleGroup: (String) -> Unit,
    rowBoundsAbs: HashMap<Int, Pair<Float, Float>>,
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
            .horizontalScroll(hScroll)
            .widthIn(min = 2000.dp)
            .padding(start = ROW_START_PAD + INDENT_STEP * item.indent, end = 8.dp, top = ROW_V_PAD, bottom = ROW_V_PAD),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(24.dp).clickable { onToggleGroup(item.gid) },
            contentAlignment = Alignment.Center,
        ) {
            AppText(if (item.expanded) "▼" else "▶", color = sc, fontSize = 14.sp, fontFamily = mono)
        }
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
    hScroll: ScrollState,
    onSelRow: (Int, Boolean, Boolean) -> Unit,
    onCtxMenu: (Int, Float, Float, String) -> Unit,
    onToggleGroup: (String) -> Unit,
    rowBoundsAbs: HashMap<Int, Pair<Float, Float>>,
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
            .horizontalScroll(hScroll)
            .widthIn(min = 2000.dp)
            .padding(start = ROW_START_PAD, end = 8.dp, top = ROW_V_PAD, bottom = ROW_V_PAD),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(24.dp).clickable { onToggleGroup(item.gid) },
            contentAlignment = Alignment.Center,
        ) {
            AppText(if (item.expanded) "▼" else "▶", color = sc, fontSize = 14.sp, fontFamily = mono)
        }
        val direction = if (item.direction == ManualCollapseDirection.TO_START) "file start" else "file end"
        AppText("Collapsed to $direction", color = sc, fontSize = 11.sp, fontFamily = mono, fontWeight = FontWeight.SemiBold)
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
    hScroll: ScrollState,
    onSelRow: (Int, Boolean, Boolean) -> Unit,
    onCtxMenu: (Int, Float, Float, String) -> Unit,
    onToggleGroup: (String) -> Unit,
    rowBoundsAbs: HashMap<Int, Pair<Float, Float>>,
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
            .horizontalScroll(hScroll)
            .widthIn(min = 2000.dp)
            .padding(start = ROW_START_PAD + INDENT_STEP * item.indent, end = 8.dp, top = ROW_V_PAD, bottom = ROW_V_PAD),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(24.dp).clickable { onToggleGroup(item.gid) },
            contentAlignment = Alignment.Center,
        ) {
            AppText(if (item.expanded) "▼" else "▶", color = sc, fontSize = 14.sp, fontFamily = mono)
        }
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
