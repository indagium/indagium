package com.indagium.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

internal val LocalOpenTabGroupEditor = staticCompositionLocalOf<(String) -> Unit> { {} }

private const val TAB_GROUP_HEADER_CHEVRON_FONT_SIZE_SP = 11f
private const val TAB_GROUP_HEADER_HORIZONTAL_PADDING_DP = 26f
private const val TAB_GROUP_HEADER_MAX_WIDTH_DP = 180f

internal sealed interface TabStripItem {
    val key: String

    data class Tab(val ref: TabRef) : TabStripItem {
        override val key: String get() = ref.rawId()
    }

    data class GroupHeader(val group: TabGroup, val supporting: Boolean = false) : TabStripItem {
        override val key: String get() = groupHeaderKey(group.id) + if (supporting) "-support" else ""
    }
}

internal fun groupHeaderKey(id: String): String = "__tab-group__:$id"

internal fun tabGroupDropBefore(
    target: TabStripItem?,
    order: List<TabRef>,
    after: Boolean,
): TabRef? = when (target) {
    is TabStripItem.Tab -> {
        if (!after) {
            target.ref
        } else {
            order.dropWhile { it != target.ref }.drop(1).firstOrNull()
        }
    }
    is TabStripItem.GroupHeader -> {
        if (!after) {
            target.group.members.firstOrNull()
        } else {
            val lastMemberIndex = order.indexOfLast { it in target.group.members }
            order.getOrNull(lastMemberIndex + 1)
        }
    }
    null -> null
}

/** Group members appear together at their first persisted order position; all other refs keep their order. */
internal fun projectTabStripItems(order: List<TabRef>, groups: List<TabGroup>): List<TabStripItem> {
    val liveRefs = order.toSet()
    val byMember = groups.flatMap { group -> group.members.filter { it in liveRefs }.map { it to group } }.toMap()
    val result = mutableListOf<TabStripItem>()
    val emittedGroups = mutableSetOf<String>()
    order.forEach { ref ->
        val group = byMember[ref]
        if (group == null) {
            result += TabStripItem.Tab(ref)
        } else if (emittedGroups.add(group.id)) {
            result += TabStripItem.GroupHeader(group)
            if (!group.collapsed) {
                order.filter { it in group.members }.forEach { member -> result += TabStripItem.Tab(member) }
            }
        }
    }
    return result
}

/** Adds a group header at each side of overflow when only that side has group members. */
internal fun associateGroupHeaders(items: List<TabStripItem>, groups: List<TabGroup>): List<TabStripItem> {
    val headers = groups.associateBy { it.id }
    val memberRefs = items.filterIsInstance<TabStripItem.Tab>().mapTo(mutableSetOf()) { it.ref }
    val normalizedItems = items.filterNot { item ->
        item is TabStripItem.GroupHeader && item.supporting && item.group.members.none { it in memberRefs }
    }
    val presentHeaderIds = normalizedItems.filterIsInstance<TabStripItem.GroupHeader>().mapTo(mutableSetOf()) { it.group.id }
    val result = mutableListOf<TabStripItem>()
    val inserted = mutableSetOf<String>()
    normalizedItems.forEach { item ->
        val group = (item as? TabStripItem.Tab)?.let { tab -> headers.values.firstOrNull { tab.ref in it.members } }
        if (group != null && group.id !in presentHeaderIds && inserted.add(group.id)) {
            result += TabStripItem.GroupHeader(group, supporting = true)
        }
        result += item
    }
    return result
}

/** Pick a viewport around an overflow-selected tab without changing tab order or group membership. */
internal fun splitTabStripItems(
    items: List<TabStripItem>,
    widthsPx: List<Float>,
    containerPx: Int,
    overflowButtonPx: Int,
    visibleItemLimit: Int,
    minimumItemPx: Int,
    groups: List<TabGroup>,
    anchorRef: TabRef?,
    forceOverflow: Boolean = false,
    preserveItemSize: Boolean = false,
): Pair<List<TabStripItem>, List<TabStripItem>> {
    if (items.isEmpty()) return emptyList<TabStripItem>() to emptyList()
    if (containerPx <= 0) return items to emptyList()
    val limit = visibleItemLimit.coerceIn(1, items.size)
    val anchorIndex = anchorRef?.let { ref -> items.indexOfFirst { it is TabStripItem.Tab && it.ref == ref } }
        ?.takeIf { it >= 0 }
    val budget = viewportBudget(items, widthsPx, containerPx, overflowButtonPx, limit, minimumItemPx, anchorIndex, forceOverflow)
    val groupHeaderIndex = anchorHeaderIndex(anchorRef, items, groups)
    val chosen = selectViewportIndexes(
        items, widthsPx, limit, budget, minimumItemPx, anchorIndex, groupHeaderIndex, preserveItemSize,
    )
    val overflowIndexes = items.indices.filterNot { it in chosen }.toMutableSet()
    val visibleSource = items.filterIndexed { index, _ -> index in chosen }
    val visible = associateGroupHeaders(visibleSource, groups).toMutableList()
    val widthByKey = items.mapIndexed { index, item -> item.key to widthAt(widthsPx, index, minimumItemPx) }.toMap()
    trimViewportToBudget(visible, overflowIndexes, items, groups, limit, budget, anchorRef, widthByKey)
    val overflowSource = items.filterIndexed { index, _ -> index in overflowIndexes }
    val overflow = associateGroupHeaders(overflowSource, groups)
    return visible to overflow
}

private fun viewportBudget(
    items: List<TabStripItem>,
    widthsPx: List<Float>,
    containerPx: Int,
    overflowButtonPx: Int,
    limit: Int,
    minimumItemPx: Int,
    anchorIndex: Int?,
    forceOverflow: Boolean,
): Float {
    val overflows = items.size > limit || anchorIndex != null || forceOverflow || widthsPx.sum() > containerPx
    val overflowWidth = if (overflows) overflowButtonPx else 0
    return (containerPx - overflowWidth).coerceAtLeast(minimumItemPx).toFloat()
}

private fun anchorHeaderIndex(anchorRef: TabRef?, items: List<TabStripItem>, groups: List<TabGroup>): Int? {
    val groupId = anchorRef?.let { ref -> groups.firstOrNull { ref in it.members }?.id } ?: return null
    return items.indexOfFirst { it is TabStripItem.GroupHeader && it.group.id == groupId }.takeIf { it >= 0 }
}

private fun selectViewportIndexes(
    items: List<TabStripItem>,
    widthsPx: List<Float>,
    limit: Int,
    budget: Float,
    minimumItemPx: Int,
    anchorIndex: Int?,
    groupHeaderIndex: Int?,
    preserveItemSize: Boolean,
): MutableSet<Int> = if (anchorIndex == null) {
    selectTrailingIndexes(items, widthsPx, limit, budget, minimumItemPx)
} else {
    selectAnchoredIndexes(
        items.size, widthsPx, limit, budget, minimumItemPx, anchorIndex, groupHeaderIndex, preserveItemSize,
    )
}

private fun selectTrailingIndexes(
    items: List<TabStripItem>,
    widthsPx: List<Float>,
    limit: Int,
    budget: Float,
    minimumItemPx: Int,
): MutableSet<Int> {
    val selected = mutableSetOf<Int>()
    var used = 0f
    for (index in items.indices.reversed()) {
        if (selected.size >= limit) break
        val width = widthAt(widthsPx, index, minimumItemPx)
        if (selected.isNotEmpty() && used + width > budget) break
        selected += index
        used += width
    }
    // Keep one tab reachable in a tiny window; FIT shrinks it to the available width.
    if (selected.isEmpty()) selected += items.lastIndex
    return selected
}

private fun selectAnchoredIndexes(
    itemCount: Int,
    widthsPx: List<Float>,
    limit: Int,
    budget: Float,
    minimumItemPx: Int,
    anchorIndex: Int,
    groupHeaderIndex: Int?,
    preserveItemSize: Boolean,
): MutableSet<Int> {
    val anchorWidth = widthAt(widthsPx, anchorIndex, minimumItemPx)
    val headerWidth = groupHeaderIndex?.let { widthAt(widthsPx, it, minimumItemPx) } ?: 0f
    if (preserveItemSize && groupHeaderIndex != null && anchorWidth + headerWidth > budget) {
        // Keep the header and overflow menu visible when a vertical strip cannot fit both rows.
        return mutableSetOf(groupHeaderIndex)
    }
    val selected = mutableSetOf(anchorIndex)
    var used = anchorWidth
    if (groupHeaderIndex != null) {
        selected += groupHeaderIndex
        used += headerWidth
    }
    val leftResult = addAdjacentIndexes(
        selected, (anchorIndex - 1 downTo 0), widthsPx, limit, used, budget, minimumItemPx,
    )
    addAdjacentIndexes(selected, (anchorIndex + 1 until itemCount), widthsPx, limit, leftResult, budget, minimumItemPx)
    return selected
}

private fun addAdjacentIndexes(
    selected: MutableSet<Int>,
    candidates: IntProgression,
    widthsPx: List<Float>,
    limit: Int,
    initialUsed: Float,
    budget: Float,
    minimumItemPx: Int,
): Float {
    var used = initialUsed
    var canAdd = true
    for (index in candidates) {
        if (canAdd && selected.size < limit && index !in selected) {
            val width = widthAt(widthsPx, index, minimumItemPx)
            if (used + width > budget) {
                canAdd = false
            } else {
                selected += index
                used += width
            }
        }
    }
    return used
}

private fun widthAt(widthsPx: List<Float>, index: Int, minimumItemPx: Int): Float =
    widthsPx.getOrElse(index) { minimumItemPx.toFloat() }

private fun trimViewportToBudget(
    visible: MutableList<TabStripItem>,
    overflowIndexes: MutableSet<Int>,
    items: List<TabStripItem>,
    groups: List<TabGroup>,
    limit: Int,
    budget: Float,
    anchorRef: TabRef?,
    widthByKey: Map<String, Float>,
) {
    while (viewportExceedsBudget(visible, limit, budget, widthByKey) && visible.hasRemovableTab(anchorRef)) {
        val removed = visible.removeAt(visible.indexOfFirst { it is TabStripItem.Tab && it.ref != anchorRef }) as TabStripItem.Tab
        items.indexOfFirst { it is TabStripItem.Tab && it.ref == removed.ref }
            .takeIf { it >= 0 }?.let(overflowIndexes::add)
        val associated = associateGroupHeaders(visible, groups)
        visible.clear()
        visible.addAll(associated)
    }
}

private fun viewportExceedsBudget(
    visible: List<TabStripItem>,
    limit: Int,
    budget: Float,
    widthByKey: Map<String, Float>,
): Boolean = visible.size > limit || visible.sumOf { itemWidth(it, widthByKey).toDouble() } > budget

private fun itemWidth(item: TabStripItem, widthByKey: Map<String, Float>): Float =
    widthByKey[item.key] ?: when (item) {
        is TabStripItem.GroupHeader -> widthByKey[groupHeaderKey(item.group.id)] ?: 0f
        is TabStripItem.Tab -> 0f
    }

private fun List<TabStripItem>.hasRemovableTab(anchorRef: TabRef?): Boolean =
    any { it is TabStripItem.Tab && it.ref != anchorRef }

@Suppress("MagicNumber")
internal fun TabGroupColor.uiColor(): Color = when (this) {
    TabGroupColor.BLUE -> Color(0xFF3B82F6)
    TabGroupColor.GREEN -> Color(0xFF22A06B)
    TabGroupColor.YELLOW -> Color(0xFFE3A008)
    TabGroupColor.ORANGE -> Color(0xFFF97316)
    TabGroupColor.RED -> Color(0xFFEF4444)
    TabGroupColor.PURPLE -> Color(0xFF8B5CF6)
    TabGroupColor.PINK -> Color(0xFFEC4899)
    TabGroupColor.GRAY -> Color(0xFF8B949E)
}

internal fun TabGroupTextSize.fontSize(): TextUnit = when (this) {
    TabGroupTextSize.SMALL -> 10.sp
    TabGroupTextSize.MEDIUM -> 12.sp
    TabGroupTextSize.LARGE -> 14.sp
}

internal fun TabGroupIcon.imageVector(): ImageVector? = when (this) {
    TabGroupIcon.NONE -> null
    TabGroupIcon.FOLDER -> Icons.Outlined.FolderOpen
    TabGroupIcon.BOOKMARK -> Icons.Outlined.Bookmark
    TabGroupIcon.BUG -> Icons.Outlined.BugReport
    TabGroupIcon.CODE -> Icons.Outlined.Code
    TabGroupIcon.DEVICE -> Icons.Outlined.PhoneAndroid
    TabGroupIcon.FLAG -> Icons.Outlined.Flag
    TabGroupIcon.STAR -> Icons.Outlined.StarBorder
    TabGroupIcon.SEARCH -> Icons.Outlined.Search
}

internal fun tabGroupIndicatorWidthDp(group: TabGroup): Float =
    if (group.icon == TabGroupIcon.NONE) 7f else 14f

internal fun tabGroupHeaderWidthPx(
    group: TabGroup,
    textMeasurer: TextMeasurer,
    uiFont: androidx.compose.ui.text.font.FontFamily,
    density: Density,
): Float {
    val label = "${group.name} (${group.members.size})"
    val labelWidth = textMeasurer.measure(
        AnnotatedString(label),
        style = TextStyle(fontSize = group.textSize.fontSize(), fontFamily = uiFont, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
    ).size.width.toFloat()
    val chevron = if (group.collapsed) "▸" else "▾"
    val chevronWidth = textMeasurer.measure(
        AnnotatedString(chevron),
        style = TextStyle(fontSize = TAB_GROUP_HEADER_CHEVRON_FONT_SIZE_SP.sp, fontFamily = MONO),
    ).size.width.toFloat()
    val contentPadding = with(density) { TAB_GROUP_HEADER_HORIZONTAL_PADDING_DP.dp.toPx() }
    val iconWidth = with(density) { tabGroupIndicatorWidthDp(group).dp.toPx() }
    return (labelWidth + chevronWidth + contentPadding + iconWidth)
        .coerceIn(with(density) { 64.dp.toPx() }, with(density) { TAB_GROUP_HEADER_MAX_WIDTH_DP.dp.toPx() })
}

@Composable
internal fun TabGroupHeader(
    group: TabGroup,
    vertical: Boolean,
    activeMember: Boolean,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    supporting: Boolean = false,
    onClick: () -> Unit,
    onContextMenu: (IntRect) -> Unit,
) {
    val tc = tc()
    val color = group.color.uiColor()
    var headerBounds by remember(group.id) { mutableStateOf(IntRect.Zero) }
    val sizeModifier = if (vertical) Modifier.fillMaxWidth().height(height) else Modifier.width(width).height(height)
    Row(
        sizeModifier.padding(horizontal = 2.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (activeMember) color.copy(alpha = if (tc.p.luminance() > 0.5f) 0.16f else 0.25f) else tc.p2)
            .border(if (activeMember) 1.dp else 0.5.dp, if (activeMember) color else tc.br, RoundedCornerShape(5.dp))
            .testTag("tab-group-header-${group.id}${if (supporting) "-support" else ""}")
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                headerBounds = IntRect(
                    bounds.left.roundToInt(),
                    bounds.top.roundToInt(),
                    bounds.right.roundToInt(),
                    bounds.bottom.roundToInt(),
                )
            }
            .pointerInput(group.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            event.changes.firstOrNull()?.consume()
                            onContextMenu(headerBounds)
                        }
                    }
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TabGroupHeaderIcon(group, color, "tab-group-header-icon-${group.id}")
        AppText(if (group.collapsed) "▸" else "▾", color = color, fontSize = 11.sp, fontFamily = MONO)
        AppText(
            "${group.name} (${group.members.size})",
            color = if (activeMember) tc.tx else tc.ts,
            fontSize = group.textSize.fontSize(),
            fontWeight = if (activeMember) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun TabGroupHeaderIcon(group: TabGroup, color: Color = group.color.uiColor(), testTagValue: String? = null) {
    val tagModifier = if (testTagValue == null) Modifier else Modifier.testTag(testTagValue)
    val icon = group.icon.imageVector()
    if (icon == null) {
        Box(
            Modifier.size(7.dp).clip(CircleShape).background(color).then(tagModifier),
        )
    } else {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp).then(tagModifier),
        )
    }
}

@Composable
internal fun TabGroupEditorDialog(state: AppState, groupId: String, onDismiss: () -> Unit) {
    val group = state.tabGroups.firstOrNull { it.id == groupId }
    if (group == null) {
        LaunchedEffect(groupId) { onDismiss() }
        return
    }
    var name by remember(group.id) { mutableStateOf(TextFieldValue(group.name, selection = TextRange(0, group.name.length))) }
    var selectedColor by remember(group.id) { mutableStateOf(group.color) }
    var selectedTextSize by remember(group.id) { mutableStateOf(group.textSize) }
    var selectedIcon by remember(group.id) { mutableStateOf(group.icon) }
    val nameFocus = remember(group.id) { FocusRequester() }
    LaunchedEffect(group.id) { runCatching { nameFocus.requestFocus() } }
    val tc = tc()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier.widthIn(min = 300.dp, max = 380.dp).background(tc.p, RoundedCornerShape(10.dp))
                .border(1.dp, tc.br, RoundedCornerShape(10.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppText("Edit tab group", color = tc.tx, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { AppText("Group name", color = tc.ts, fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(nameFocus).testTag("tab-group-name"),
            )
            AppText("Color", color = tc.ts, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TabGroupColor.entries.forEach { color ->
                    val selected = selectedColor == color
                    Box(
                        Modifier.size(29.dp).clip(CircleShape).background(color.uiColor())
                            .then(if (selected) Modifier.border(3.dp, tc.tx, CircleShape) else Modifier)
                            .testTag("tab-group-color-${color.name.lowercase()}")
                            .clickable { selectedColor = color },
                    )
                }
            }
            AppText("Text size", color = tc.ts, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TabGroupTextSize.entries.forEach { size ->
                    val selected = selectedTextSize == size
                    Box(
                        Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (selected) selectedColor.uiColor().copy(alpha = 0.16f) else tc.p2)
                            .border(1.dp, if (selected) selectedColor.uiColor() else tc.br, RoundedCornerShape(6.dp))
                            .testTag("tab-group-text-size-${size.name.lowercase()}")
                            .clickable { selectedTextSize = size },
                        contentAlignment = Alignment.Center,
                    ) {
                        AppText(
                            "${size.name.lowercase().replaceFirstChar(Char::uppercase)} (${size.fontSize().value.toInt()})",
                            color = tc.tx,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
            AppText("Icon", color = tc.ts, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TabGroupIcon.entries.forEach { icon ->
                    val selected = selectedIcon == icon
                    Box(
                        Modifier.size(28.dp).clip(CircleShape)
                            .background(if (selected) selectedColor.uiColor().copy(alpha = 0.16f) else tc.p2)
                            .border(1.dp, if (selected) selectedColor.uiColor() else tc.br, CircleShape)
                            .testTag("tab-group-icon-${icon.name.lowercase()}")
                            .clickable { selectedIcon = icon },
                        contentAlignment = Alignment.Center,
                    ) {
                        TabGroupHeaderIcon(group.copy(icon = icon), selectedColor.uiColor())
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                ToolbarBtn("Cancel", active = false) { onDismiss() }
                Spacer(Modifier.width(8.dp))
                ToolbarBtn("Save", active = true, modifier = Modifier.testTag("tab-group-save")) {
                    state.renameTabGroup(groupId, name.text.trim().ifBlank { group.name })
                    state.setTabGroupColor(groupId, selectedColor)
                    state.setTabGroupTextSize(groupId, selectedTextSize)
                    state.setTabGroupIcon(groupId, selectedIcon)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
internal fun TabGroupContextActions(
    state: AppState,
    ref: TabRef,
    onDismiss: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val groups = state.tabGroups
    val current = state.tabGroupOf(ref)
    if (ref is TabRef.Log && state.tab(ref.tabId)?.isCaptureLauncher == true) return
    CtxItem(icon = Icons.Outlined.CreateNewFolder, label = "Add to new group") {
        state.createTabGroup(ref)?.let(onEdit)
        onDismiss()
    }
    if (groups.isNotEmpty()) {
        CtxItemWithSubmenu(
            icon = Icons.Outlined.FolderOpen,
            label = "Move to group",
            submenu = groups.filterNot { it.id == current?.id }.map { group ->
                CtxSubmenuOption("${group.name} (${group.members.size})") {
                    state.assignTabToGroup(ref, group.id)
                    onDismiss()
                }
            }.ifEmpty { listOf(CtxSubmenuOption("No other groups", enabled = false) {}) },
            onClick = {},
        )
    }
    if (current != null) {
        CtxItem(icon = Icons.Outlined.RemoveCircleOutline, label = "Remove from group") {
            state.removeTabFromGroup(ref)
            onDismiss()
        }
    }
}
