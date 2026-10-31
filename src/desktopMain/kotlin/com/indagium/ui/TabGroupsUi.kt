@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.model.TabStripStyle
import kotlin.math.roundToInt

/** Opens the group editor: the group id, and whether the group was just created (the dialog then reads
 * "New tab group" and its Cancel removes the group again). */
internal val LocalOpenTabGroupEditor = staticCompositionLocalOf<(String, Boolean) -> Unit> { { _, _ -> } }

private const val TAB_GROUP_HEADER_MAX_WIDTH_DP = 200f
private const val TAB_GROUP_HEADER_MIN_WIDTH_DP = 64f
private const val TAB_GROUP_CHIP_ICON_DP = 14f
private const val TAB_GROUP_CHIP_ICON_GAP_DP = 4f
private const val TAB_GROUP_CHIP_DOT_DP = 8f
private const val TAB_GROUP_CHIP_WIDTH_SLACK_DP = 2f
private const val TAB_GROUP_ICONS_PER_ROW = 9

/** The member tab tint on a light panel / a dark panel, and while a dragged tab hovers the group (any panel). */
internal const val GROUP_TINT_ALPHA_LIGHT = 0.10f
internal const val GROUP_TINT_ALPHA_DARK = 0.16f
internal const val GROUP_TINT_ALPHA_DRAG = 0.22f
private const val LIGHT_PANEL_LUMINANCE = 0.5f

/** How strongly a group colours its member tabs: lighter on a light panel, stronger on a dark one, and strongest while a dragged tab hovers the group. */
internal fun tabGroupTintAlpha(panel: Color, dragHover: Boolean): Float = when {
    dragHover -> GROUP_TINT_ALPHA_DRAG
    panel.luminance() > LIGHT_PANEL_LUMINANCE -> GROUP_TINT_ALPHA_LIGHT
    else -> GROUP_TINT_ALPHA_DARK
}

/** A group's colour as a Compose colour. */
internal val TabGroup.color: Color get() = Color(colorArgb)

/** What a group's chip reads: the chevron, the name and the number of members ("▾ name · 3"). */
internal fun tabGroupChipLabel(group: TabGroup): String =
    "${if (group.collapsed) "▸" else "▾"} ${group.name} · ${group.members.size}"

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

/**
 * Group members appear together at their first persisted order position; all other refs keep their order. A collapsed group
 * keeps its [active] member visible after the chip (so the tab being viewed never disappears); once another tab outside the group
 * is active, the collapsed group folds fully.
 */
internal fun projectTabStripItems(order: List<TabRef>, groups: List<TabGroup>, active: TabRef? = null): List<TabStripItem> {
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
            order.filter { it in group.members && (!group.collapsed || it == active) }
                .forEach { member -> result += TabStripItem.Tab(member) }
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
    var lastRemoved: TabRef? = null
    while (viewportExceedsBudget(visible, limit, budget, widthByKey) && visible.hasRemovableTab(anchorRef)) {
        val removed = visible.removeAt(visible.indexOfFirst { it is TabStripItem.Tab && it.ref != anchorRef }) as TabStripItem.Tab
        lastRemoved = removed.ref
        items.indexOfFirst { it is TabStripItem.Tab && it.ref == removed.ref }
            .takeIf { it >= 0 }?.let(overflowIndexes::add)
        val associated = associateGroupHeaders(visible, groups)
        visible.clear()
        visible.addAll(associated)
    }
    // A strip too short for a collapsed group's chip and its active member must not end up empty: the chip stays.
    if (visible.isEmpty()) {
        val headerIndex = lastRemoved?.let { ref ->
            items.indexOfFirst { it is TabStripItem.GroupHeader && ref in it.group.members }
        }?.takeIf { it >= 0 }
        if (headerIndex != null) {
            visible += items[headerIndex]
            overflowIndexes -= headerIndex
        }
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
    TabGroupIcon.WARNING -> Icons.Outlined.Warning
    TabGroupIcon.SCHEDULE -> Icons.Outlined.Schedule
    TabGroupIcon.CLOUD -> Icons.Outlined.Cloud
    TabGroupIcon.WIFI -> Icons.Outlined.Wifi
    TabGroupIcon.BATTERY -> Icons.Outlined.BatteryFull
    TabGroupIcon.MEMORY -> Icons.Outlined.Memory
    TabGroupIcon.LOCK -> Icons.Outlined.Lock
    TabGroupIcon.SCIENCE -> Icons.Outlined.Science
}

/** Width a chip's icon adds (icon plus its gap); none when the group has no icon. */
internal fun tabGroupChipIconWidthDp(group: TabGroup): Float =
    if (group.icon == TabGroupIcon.NONE) 0f else TAB_GROUP_CHIP_ICON_DP + TAB_GROUP_CHIP_ICON_GAP_DP

internal fun tabGroupHeaderWidthPx(
    group: TabGroup,
    textMeasurer: TextMeasurer,
    uiFont: androidx.compose.ui.text.font.FontFamily,
    density: Density,
    style: TabStripStyle = TabStripStyle.CURRENT,
): Float {
    val labelWidth = textMeasurer.measure(
        AnnotatedString(tabGroupChipLabel(group)),
        style = tabLabelMeasureStyle(style, uiFont),
    ).size.width.toFloat()
    val chromeDp = tabChromeWidthDp(style) + TAB_GROUP_CHIP_DOT_DP + tabGroupChipIconWidthDp(group) + TAB_GROUP_CHIP_WIDTH_SLACK_DP
    return (labelWidth + with(density) { chromeDp.dp.toPx() })
        .coerceIn(
            with(density) { TAB_GROUP_HEADER_MIN_WIDTH_DP.dp.toPx() },
            with(density) { TAB_GROUP_HEADER_MAX_WIDTH_DP.dp.toPx() },
        )
}

/**
 * A group's chip: a normal full-height tab cell drawn by [TabShell] in the current strip style ([appearance] carries the style and the
 * strip edge, so height, outline and radius match every tab), reading "▾ name · N" after an 8dp dot in the group colour and the group's
 * icon when it has one. The whole cell is the click (collapse), right-click ([onContextMenu] gets the cell's bounds) and drag target.
 */
@Composable
internal fun TabGroupHeader(
    group: TabGroup,
    vertical: Boolean,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    appearance: TabAppearance,
    supporting: Boolean = false,
    dragging: Boolean = false,
    onClick: () -> Unit,
    onContextMenu: (IntRect) -> Unit,
) {
    var headerBounds by remember(group.id) { mutableStateOf(IntRect.Zero) }
    val sizeModifier = if (vertical) Modifier.fillMaxWidth().height(height) else Modifier.width(width).height(height)
    val suffix = if (supporting) "-support" else ""
    Box(
        sizeModifier
            .testTag("tab-group-header-${group.id}$suffix")
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                headerBounds = IntRect(
                    bounds.left.roundToInt(),
                    bounds.top.roundToInt(),
                    bounds.right.roundToInt(),
                    bounds.bottom.roundToInt(),
                )
            },
    ) {
        // The cell's own tag is "tab-group-chip-<id>" (TabShell prefixes "tab-").
        TabShell(
            pointerKey = "group-chip-${group.id}$suffix",
            label = tabGroupChipLabel(group),
            tooltip = "${group.name} · ${group.members.size} tab${if (group.members.size == 1) "" else "s"}",
            isActive = false,
            showClose = false,
            dragging = dragging,
            leading = { TabGroupChipLeading(group) },
            onClick = onClick,
            onClose = {},
            onCtxMenu = { _, _ -> onContextMenu(headerBounds) },
            appearance = appearance.copy(
                showBadge = false,
                showStripe = false,
                secondLine = null,
                tailing = false,
                groupColor = null,
                groupTintAlpha = 0f,
            ),
        )
    }
}

/** The colour dot and the optional icon before a chip's label. */
@Composable
private fun TabGroupChipLeading(group: TabGroup) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TAB_GROUP_CHIP_ICON_GAP_DP.dp)) {
        Box(
            Modifier.size(TAB_GROUP_CHIP_DOT_DP.dp).background(group.color, CircleShape)
                .testTag("tab-group-dot-${group.id}"),
        )
        TabGroupHeaderIcon(group, group.color, "tab-group-header-icon-${group.id}")
    }
}

/** The group's icon in [color]; nothing when the group has none (a chip never shows a stand-in dot). */
@Composable
internal fun TabGroupHeaderIcon(group: TabGroup, color: Color = group.color, testTagValue: String? = null) {
    val icon = group.icon.imageVector() ?: return
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(TAB_GROUP_CHIP_ICON_DP.dp).then(if (testTagValue == null) Modifier else Modifier.testTag(testTagValue)),
    )
}

/**
 * The shared highlighter picker (palette, saved custom colours, "Custom color") wired to the app settings the way every other
 * colour row is. A group's colour is always opaque, so the picker's alpha is dropped.
 */
@Composable
internal fun SettingsColorPicker(
    state: AppState,
    color: Color,
    onColorChange: (Color) -> Unit,
    pickerOpen: Boolean,
    onPickerOpenChange: (Boolean) -> Unit,
    testTagPrefix: String,
) {
    val tc = tc()
    val customColors = remember(state.settings.highlighterCustomColors) {
        customHighlightColors(state.settings.highlighterCustomColors)
    }
    HighlighterColorPicker(
        color = color,
        onColorChange = { onColorChange(it.copy(alpha = 1f)) },
        customColors = customColors,
        onSaveCustomColor = { saved ->
            val hex = highlightColorHex(saved)
            state.updateSettings { settings ->
                val colors = customHighlightColors(settings.highlighterCustomColors)
                if (saved !in colors && colors.size < MAX_CUSTOM_HIGHLIGHT_COLORS) {
                    settings.copy(
                        highlighterCustomColors = (colors.map(::highlightColorHex) + hex).take(MAX_CUSTOM_HIGHLIGHT_COLORS),
                    )
                } else {
                    settings
                }
            }
        },
        onDeleteCustomColor = { removed ->
            state.updateSettings { settings ->
                settings.copy(highlighterCustomColors = settings.highlighterCustomColors.filterNot { parseHighlightHex(it) == removed })
            }
        },
        paletteColumns = state.settings.highlighterPaletteColumns,
        onPaletteColumnsChange = { columns ->
            state.updateSettings { it.copy(highlighterPaletteColumns = if (columns >= 10) 10 else 5) }
        },
        pickerOpen = pickerOpen,
        onPickerOpenChange = onPickerOpenChange,
        testTagPrefix = testTagPrefix,
        customColorEditorExpanded = state.settings.highlighterCustomColorEditorExpanded,
        onCustomColorEditorExpandedChange = { expanded ->
            state.updateSettings { it.copy(highlighterCustomColorEditorExpanded = expanded) }
        },
        swatchBackdrop = tc.p,
        swatchOutline = tc.br,
    )
}

@Composable
private fun DialogField(label: String, content: @Composable () -> Unit) {
    val tc = tc()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        AppText(label, color = tc.ts, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        content()
    }
}

private val GROUP_ICON_BUTTON_SIZE = 26.dp

@Composable
private fun GroupIconOption(icon: TabGroupIcon, selected: Boolean, onClick: () -> Unit) {
    val tc = tc()
    val shape = CORNER_MD
    TooltipArea(tooltip = { ToolbarTooltip(icon.name.lowercase().replaceFirstChar(Char::uppercase)) }) {
        Box(
            Modifier.size(GROUP_ICON_BUTTON_SIZE)
                .background(if (selected) tc.abg else Color.Transparent, shape)
                .border(1.dp, if (selected) tc.ac else tc.br, shape)
                .clip(shape)
                .clickable(onClick = onClick)
                .testTag("tab-group-icon-${icon.name.lowercase()}"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon.imageVector() ?: Icons.Outlined.Block,
                contentDescription = icon.name.lowercase().replaceFirstChar(Char::uppercase),
                tint = if (selected) tc.ac else tc.ts,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Creates (isNew) or edits a group: name, colour from the shared picker, optional icon. A new group reads "New tab group" with a
 * "Create group" button, and cancelling it (Cancel or Esc) removes the group again; an existing one reads "Edit tab group" / "Save".
 */
@Composable
internal fun TabGroupEditorDialog(state: AppState, groupId: String, isNew: Boolean = false, onDismiss: () -> Unit) {
    val group = state.tabGroups.firstOrNull { it.id == groupId }
    if (group == null) {
        LaunchedEffect(groupId) { onDismiss() }
        return
    }
    var name by remember(group.id) { mutableStateOf(TextFieldValue(group.name, selection = TextRange(0, group.name.length))) }
    var selectedColor by remember(group.id) { mutableStateOf(group.color) }
    var selectedIcon by remember(group.id) { mutableStateOf(group.icon) }
    var pickerOpen by remember(group.id) { mutableStateOf(false) }
    val nameFocus = remember(group.id) { FocusRequester() }
    LaunchedEffect(group.id) { runCatching { nameFocus.requestFocus() } }
    val tc = tc()

    fun cancel() {
        if (isNew) state.ungroupTabs(groupId)
        onDismiss()
    }

    fun save() {
        state.renameTabGroup(groupId, name.text.trim().ifBlank { group.name })
        state.setTabGroupColor(groupId, selectedColor.toArgb())
        state.setTabGroupIcon(groupId, selectedIcon)
        onDismiss()
    }

    Dialog(
        onDismissRequest = ::cancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier.widthIn(min = 320.dp, max = 380.dp).background(tc.p, RoundedCornerShape(10.dp))
                .border(1.dp, tc.br, RoundedCornerShape(10.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppText(
                if (isNew) "New tab group" else "Edit tab group",
                color = tc.tx,
                fontSize = 16.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier.testTag("tab-group-title"),
            )
            DialogField("Name") {
                InlineField(
                    value = name,
                    onValue = { name = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocus).testTag("tab-group-name"),
                    onSubmit = ::save,
                    onCancel = ::cancel,
                )
            }
            DialogField("Colour") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsColorPicker(
                        state = state,
                        color = selectedColor,
                        onColorChange = { selectedColor = it },
                        pickerOpen = pickerOpen,
                        onPickerOpenChange = { pickerOpen = it },
                        testTagPrefix = "tab-group-color",
                    )
                    AppText(
                        seq3ArtifactHexLabel(selectedColor),
                        color = tc.td,
                        fontSize = 11.sp,
                        fontFamily = MONO,
                        modifier = Modifier.testTag("tab-group-color-readout"),
                    )
                }
            }
            DialogField("Icon (optional)") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Two lines: every icon shows at once, none sits behind a scroll.
                    TabGroupIcon.entries.chunked(TAB_GROUP_ICONS_PER_ROW).forEachIndexed { index, rowIcons ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("tab-group-icon-row-$index")) {
                            rowIcons.forEach { icon ->
                                GroupIconOption(icon, selected = selectedIcon == icon) { selectedIcon = icon }
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.testTag("tab-group-save")) {
                    DialogActionButton(if (isNew) "Create group" else "Save", active = true, onClick = ::save)
                }
                Box(Modifier.testTag("tab-group-cancel")) {
                    DialogActionButton("Cancel", active = false, onClick = ::cancel)
                }
            }
        }
    }
}

/** The "Colour ▸" row of the chip menu: a swatch that opens the shared picker and recolours the group as you pick. */
@Composable
private fun TabGroupMenuColorRow(state: AppState, group: TabGroup, onDone: () -> Unit) {
    val tc = tc()
    var pickerOpen by remember(group.id) { mutableStateOf(false) }
    HoverBox(
        modifier = Modifier.fillMaxWidth().testTag("tab-group-menu-colour"),
        onClick = { pickerOpen = true },
    ) {
        Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Palette, contentDescription = null, modifier = Modifier.size(16.dp), tint = tc.td.copy(alpha = 0.65f))
            }
            Spacer(Modifier.width(8.dp))
            AppText("Colour", color = tc.tx, fontSize = 12.sp, modifier = Modifier.weight(1f))
            SettingsColorPicker(
                state = state,
                color = group.color,
                onColorChange = { state.setTabGroupColor(group.id, it.toArgb()) },
                pickerOpen = pickerOpen,
                onPickerOpenChange = { open ->
                    pickerOpen = open
                    if (!open) onDone()
                },
                testTagPrefix = "tab-group-menu-color",
            )
            AppText("▸", color = tc.td.copy(alpha = 0.65f), fontSize = 10.sp)
        }
    }
}

/**
 * The chip's right-click menu: Rename…, Colour ▸, Collapse / Expand, then Edit group…, Ungroup and Close group.
 * [onEdit] opens the editor for the group (never as a new one); every action ends in [onDismiss].
 */
@Composable
internal fun TabGroupChipMenu(state: AppState, group: TabGroup, onDismiss: () -> Unit, onEdit: (String, Boolean) -> Unit) {
    val tc = tc()
    Column(
        Modifier.width(200.dp).background(tc.p, RoundedCornerShape(7.dp))
            .border(1.dp, tc.br, RoundedCornerShape(7.dp)).testTag("tab-group-context-menu")
            .padding(vertical = 4.dp),
    ) {
        CtxItem(icon = Icons.Outlined.DriveFileRenameOutline, label = "Rename…", tag = "tab-group-menu-rename") {
            onEdit(group.id, false)
            onDismiss()
        }
        TabGroupMenuColorRow(state, group, onDone = onDismiss)
        CtxItem(
            icon = if (group.collapsed) Icons.Outlined.UnfoldMore else Icons.Outlined.UnfoldLess,
            label = if (group.collapsed) "Expand" else "Collapse",
            tag = "tab-group-menu-toggle",
        ) {
            state.toggleTabGroupCollapsed(group.id)
            onDismiss()
        }
        CtxDivider()
        CtxItem(icon = Icons.Outlined.Edit, label = "Edit group…", tag = "tab-group-menu-edit") {
            onEdit(group.id, false)
            onDismiss()
        }
        CtxItem(icon = Icons.Outlined.Block, label = "Ungroup", tag = "tab-group-menu-ungroup") {
            state.ungroupTabs(group.id)
            onDismiss()
        }
        CtxItem(icon = Icons.Outlined.Close, label = "Close group", tag = "tab-group-menu-close") {
            state.closeTabGroup(group.id)
            onDismiss()
        }
    }
}

/**
 * The group entries of a tab's context menu: "Add to new group", "Add to group ▸" / "Move to group ▸" (the other groups, each with
 * its colour, ending in "New group…") and "Remove from group". A new group opens the editor with [onEdit]'s isNew set.
 */
@Composable
internal fun TabGroupContextActions(
    state: AppState,
    ref: TabRef,
    onDismiss: () -> Unit,
    onEdit: (String, Boolean) -> Unit,
) {
    val groups = state.tabGroups
    val current = state.tabGroupOf(ref)
    if (ref is TabRef.Log && state.tab(ref.tabId)?.isCaptureLauncher == true) return

    fun newGroup() {
        state.createTabGroup(ref)?.let { onEdit(it, true) }
        onDismiss()
    }
    CtxItem(icon = Icons.Outlined.CreateNewFolder, label = "Add to new group") { newGroup() }
    if (groups.isNotEmpty()) {
        CtxItemWithSubmenu(
            icon = Icons.Outlined.FolderOpen,
            label = if (current == null) "Add to group" else "Move to group",
            submenu = groups.filterNot { it.id == current?.id }.map { group ->
                CtxSubmenuOption("${group.name} (${group.members.size})", swatch = group.color) {
                    state.assignTabToGroup(ref, group.id)
                    onDismiss()
                }
            } + CtxSubmenuOption("New group…") { newGroup() },
            triggerTag = "tab-group-move-trigger",
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
