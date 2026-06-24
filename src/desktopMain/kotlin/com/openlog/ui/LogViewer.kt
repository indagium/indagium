@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlog.model.*
import com.openlog.utils.computeItems

private fun hlRanges(msg: String, hl: Highlighter): List<Pair<Int, Int>> =
    if (hl.regex) {
        runCatching { Regex(hl.pattern, RegexOption.IGNORE_CASE).findAll(msg) }.getOrNull()
            ?.map { it.range.first to it.range.last + 1 }?.toList() ?: emptyList()
    } else buildList {
        var i = 0
        while (true) {
            val idx = msg.indexOf(hl.pattern, i, ignoreCase = true)
            if (idx < 0) break
            add(idx to idx + hl.pattern.length); i = idx + 1
        }
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
    val msgOff = length
    withStyle(SpanStyle(color = msgColor)) { append(entry.msg) }
    for (hl in highlighters.filter { it.on && it.pattern.isNotBlank() }) {
        hlRanges(entry.msg, hl).forEach { (s, e) ->
            if (s < e && e <= entry.msg.length)
                addStyle(SpanStyle(background = hl.color.copy(alpha = 0.6f), fontWeight = FontWeight.SemiBold), msgOff + s, msgOff + e)
        }
    }
}

@Composable
fun LogViewer(
    tab: LogTab,
    sequences: List<SequenceDef>,
    modifier: Modifier = Modifier,
    onSelRow: (Int, Boolean, Boolean) -> Unit,
    onSelRowRange: (Int, Int) -> Unit = { _, _ -> },
    onCtxMenu: (Int, Float, Float, String) -> Unit,
    onToggleGroup: (String) -> Unit,
    onClearFilter: () -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    onToggleUnfiltered: () -> Unit,
) {
    val tc        = tc()
    val mono      = monoFont()
    val items     = remember(tab.id, tab.filter, tab.expanded, sequences) { computeItems(tab, sequences, true) }
    val visCnt    = items.count { it is LogItem.Row }
    val totalCnt  = tab.logData.size
    val hasPidTid = remember(tab.id) { tab.logData.any { it.pid > 0 } }

    // Row bounds for global drag-select (plain HashMap avoids recomposition on scroll updates)
    val rowBoundsAbs = remember { HashMap<Int, Pair<Float, Float>>() }
    val boxPosY      = remember { floatArrayOf(0f) }

    // Clear stale bounds from previous tab so drag-select uses correct positions
    LaunchedEffect(tab.id) { rowBoundsAbs.clear() }

    Column(modifier.fillMaxSize().background(tc.bg)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).background(tc.p).border(BorderStroke(1.dp, tc.br)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(12.dp))
            AppText("$visCnt / $totalCnt entries", color = tc.td, fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.weight(1f))
            PillBtn("Expand all",   active = false, onClick = onExpandAll)
            Spacer(Modifier.width(4.dp))
            PillBtn("Collapse all", active = false, onClick = onCollapseAll)
            Spacer(Modifier.width(4.dp))
            PillBtn(if (tab.showUnfiltered) "⊘ Hide original" else "⊙ Unfiltered", active = tab.showUnfiltered, onClick = onToggleUnfiltered)
            Spacer(Modifier.width(8.dp))
        }

        @Composable
        fun ItemList(listItems: List<LogItem>) {
            if (listItems.isEmpty()) { EmptyState(tc, onClearFilter); return }
            val lazyState = rememberLazyListState()
            val hScroll   = rememberScrollState()
            Box(
                Modifier.fillMaxSize()
                    .onGloballyPositioned { boxPosY[0] = it.positionInRoot().y }
                    .pointerInput("drag") {
                        awaitPointerEventScope {
                            var startId: Int? = null; var lastId: Int? = null
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = ev.changes.firstOrNull() ?: continue
                                when (ev.type) {
                                    PointerEventType.Press -> if (ev.buttons.isPrimaryPressed) {
                                        val absY = boxPosY[0] + ch.position.y
                                        startId = rowBoundsAbs.entries.firstOrNull { (_, b) -> absY >= b.first && absY < b.second }?.key
                                        lastId  = startId
                                    }
                                    PointerEventType.Move -> if (ev.buttons.isPrimaryPressed && startId != null) {
                                        val absY = boxPosY[0] + ch.position.y
                                        val id   = rowBoundsAbs.entries.firstOrNull { (_, b) -> absY >= b.first && absY < b.second }?.key
                                        if (id != null && id != lastId) { lastId = id; onSelRowRange(startId!!, id) }
                                    }
                                    PointerEventType.Release -> { startId = null; lastId = null }
                                    else -> {}
                                }
                            }
                        }
                    }
            ) {
                Column(Modifier.fillMaxSize()) {
                    // Content area: horizontal scroll wraps LazyColumn
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
                            LazyColumn(
                                state = lazyState,
                                modifier = Modifier.fillMaxHeight().widthIn(min = 2000.dp),
                            ) {
                                items(
                                    items = listItems,
                                    key = { item -> when (item) {
                                        is LogItem.Row       -> "r${item.entry.id}"
                                        is LogItem.SeqHeader -> "h${item.gid}"
                                    }}
                                ) { item ->
                                    when (item) {
                                        is LogItem.Row       -> LogRow(item, tab, mono, tc, onSelRow, onCtxMenu, rowBoundsAbs)
                                        is LogItem.SeqHeader -> SeqHeaderRow(item, mono, tc, onToggleGroup)
                                    }
                                }
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(lazyState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )
                    }
                    HorizontalScrollbar(
                        adapter = rememberScrollbarAdapter(hScroll),
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    )
                }
            }
        }

        if (tab.showUnfiltered) {
            val allItems = remember(tab.id, tab.expanded, sequences) { computeItems(tab, sequences, false) }
            Column(Modifier.fillMaxWidth().weight(0.45f)) {
                SectionBanner("Original — $totalCnt lines", tc.seq1, tc)
                ColHeader(hasPidTid)
                ItemList(allItems)
            }
            SectionBanner("Filtered — $visCnt lines", tc.ac, tc)
            Column(Modifier.fillMaxWidth().weight(0.45f)) {
                ColHeader(hasPidTid); ItemList(items)
            }
        } else {
            ColHeader(hasPidTid); ItemList(items)
        }
    }
}

@Composable
private fun LogRow(
    item: LogItem.Row,
    tab: LogTab,
    mono: FontFamily,
    tc: ThemeColors,
    onSelRow: (Int, Boolean, Boolean) -> Unit,
    onCtxMenu: (Int, Float, Float, String) -> Unit,
    rowBoundsAbs: HashMap<Int, Pair<Float, Float>>,
) {
    val density  = LocalDensity.current.density
    val entry    = item.entry
    val isSel    = entry.id in tab.selected
    var hov      by remember { mutableStateOf(false) }
    var rowRoot  by remember { mutableStateOf(Offset.Zero) }
    var sel      by remember(entry.id) { mutableStateOf(TextRange.Zero) }

    val annoLine = remember(entry.id, tab.filter.highlighters, tc.td, tc.ts, tc.tx) {
        buildFullLineAnnotation(entry, tab.filter.highlighters, tc.td, tc.td.copy(0.5f), tc.ts, tc.tx)
    }

    val levelColor = entry.level.defaultColor
    val bg = when { isSel -> tc.sl; hov -> tc.hv; else -> Color.Transparent }

    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                rowBoundsAbs[entry.id] = pos.y to (pos.y + coords.size.height)
                rowRoot = pos
            }
            // Keys include tab.id so coroutines restart when the same entry ID appears in a different tab
            .pointerInput("rc", tab.id, entry.id) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.type == PointerEventType.Press) {
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
                            } else if (ev.buttons.isPrimaryPressed && (mods.isShiftPressed || mods.isCtrlPressed)) {
                                ev.changes.forEach { it.consume() }
                                onSelRow(entry.id, mods.isShiftPressed, mods.isCtrlPressed)
                            }
                        }
                    }
                }
            }
            .pointerInput("hd", tab.id, entry.id) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        when (ev.type) {
                            PointerEventType.Enter -> hov = true
                            PointerEventType.Exit  -> hov = false
                            PointerEventType.Press -> if (ev.buttons.isPrimaryPressed) onSelRow(entry.id, false, false)
                            else -> {}
                        }
                    }
                }
            }
            // Level-coloured left edge stripe
            .drawBehind { drawRect(levelColor.copy(alpha = if (isSel) 0.7f else 0.35f), topLeft = Offset.Zero, size = Size(3f, size.height)) }
            .padding(start = (11 + item.indent * 18).dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value         = TextFieldValue(annotatedString = annoLine, selection = sel),
            onValueChange = { new -> sel = new.selection },
            readOnly      = true,
            singleLine    = true,
            textStyle     = TextStyle(fontFamily = mono, fontSize = baseSp()),
            cursorBrush   = SolidColor(Color.Transparent),
            modifier      = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SeqHeaderRow(
    item: LogItem.SeqHeader,
    mono: FontFamily,
    tc: ThemeColors,
    onToggleGroup: (String) -> Unit,
) {
    val sc  = item.color
    var hov by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (hov) sc.copy(.15f) else sc.copy(.07f))
            .drawBehind { drawRect(sc, topLeft = Offset.Zero, size = Size(4f, size.height)) }
            .pointerInput("hd", item.gid) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        when (ev.type) {
                            PointerEventType.Enter -> hov = true
                            PointerEventType.Exit  -> hov = false
                            else -> {}
                        }
                    }
                }
            }
            .clickable { onToggleGroup(item.gid) }
            .padding(start = (11 + item.indent * 18).dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppText(if (item.expanded) "▼" else "▶", color = sc, fontSize = 9.sp, fontFamily = mono)
        AppText("${item.entry.ts}  ${item.entry.level.key}", color = sc.copy(.7f), fontSize = 11.sp, fontFamily = mono)
        AppText("${item.entry.tag}:", color = sc, fontSize = 11.sp, fontFamily = mono,
            modifier = Modifier.widthIn(max = 240.dp), overflow = TextOverflow.Ellipsis)
        AppText(item.entry.msg, color = sc, fontSize = 12.sp, fontFamily = mono, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
        if (!item.expanded) AppText("${item.count} entries", color = sc.copy(.6f), fontSize = 11.sp)
    }
}

@Composable
private fun SectionBanner(label: String, color: Color, tc: ThemeColors) {
    Box(Modifier.fillMaxWidth().background(color.copy(.05f)).border(BorderStroke(1.dp, tc.br)).padding(horizontal = 12.dp, vertical = 3.dp)) {
        AppText(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ColumnScope.EmptyState(tc: ThemeColors, onClear: () -> Unit) {
    Column(
        Modifier.fillMaxSize().weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        AppText("No entries match current filters", color = tc.ts, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        PillBtn("Clear filters", active = true, onClick = onClear)
    }
}
