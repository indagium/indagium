package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties

// Building blocks of the redesigned Settings pages: a switch, a row (name + visible description + control), a bordered group
// card, a page header, a dropdown and a drill-in row. Colours come from tc() only; the Light hex values in the design canvas are
// just that theme's values of the same ThemeColors fields.

private val SWITCH_TRACK_WIDTH = 30.dp
private val SWITCH_TRACK_HEIGHT = 18.dp
private val SWITCH_KNOB_ON = 14.dp
private val SWITCH_KNOB_OFF = 12.dp
private const val DISABLED_ALPHA = 0.5f
private val SETTINGS_ROW_INDENT = 28.dp
private val SETTINGS_CARD_SHAPE = RoundedCornerShape(8.dp)
private val DROPDOWN_POPUP_SHAPE = RoundedCornerShape(8.dp)
private val DROPDOWN_OPTION_SHAPE = RoundedCornerShape(5.dp)
private val DROPDOWN_FIELD_HEIGHT = 26.dp
private val DROPDOWN_POPUP_OFFSET = 30.dp
private val DROPDOWN_POPUP_MAX_HEIGHT = 320.dp
private val DRILL_SUMMARY_MAX_WIDTH = 320.dp
private val OVERFLOW_TARGET = 22.dp
private val OVERFLOW_MENU_MIN_WIDTH = 190.dp

/** How long after a dismiss a click on the field is ignored: the popup's outside-click dismiss and the field's own click fire for the same press. */
private const val DROPDOWN_REOPEN_GUARD_MS = 200L

/**
 * An on/off switch: a 30x18 track, accent fill with a white knob when on, a panel fill with a dim border and a secondary-colour knob when
 * off. One node with [Role.Switch] and [label] as its content description. [enabled] = false ignores clicks and dims it to half.
 *
 * Like every clickable it takes keyboard focus on click, so a host with its own root key handler should call its focus reclaim from
 * [onCheckedChange].
 */
@Composable
internal fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val tc = tc()
    val shape = RoundedCornerShape(SWITCH_TRACK_HEIGHT / 2)
    Box(
        modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .size(SWITCH_TRACK_WIDTH, SWITCH_TRACK_HEIGHT)
            .clip(shape)
            .background(if (checked) tc.ac else tc.p2, shape)
            .then(if (checked) Modifier else Modifier.border(1.dp, tc.td, shape))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { contentDescription = label }
            .padding(if (checked) 2.dp else 3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier.size(if (checked) SWITCH_KNOB_ON else SWITCH_KNOB_OFF)
                .background(if (checked) Color.White else tc.ts, CircleShape),
        )
    }
}

/**
 * One setting: [label] with an optional one-line [description] on the left and [control] right-aligned. It carries
 * [settingsAnchor] for [anchor] (the search index key, default the label) so a search result can scroll to and flash it.
 * [indent] marks a row that depends on the one above. [enabled] = false dims the label and control to half; the control must
 * disable its own interaction as well, the row only draws.
 */
@Composable
internal fun SettingsRow(
    label: String,
    description: String? = null,
    anchor: String = label,
    indent: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    descriptionTag: String? = null,
    control: @Composable () -> Unit,
) {
    val tc = tc()
    Box(modifier.fillMaxWidth().settingsAnchor(anchor)) {
        Row(
            Modifier.fillMaxWidth()
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .padding(start = 12.dp + if (indent) SETTINGS_ROW_INDENT else 0.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AppText(label, color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!description.isNullOrBlank()) {
                    AppText(
                        description,
                        color = tc.ts,
                        fontSize = 11.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (descriptionTag != null) Modifier.testTag(descriptionTag) else Modifier,
                    )
                }
            }
            control()
        }
    }
}

/**
 * A [SettingsRow] whose control needs the full width: [label] and the optional [description] on top, [content] (a list of choices, a
 * picker) under them. Same padding, anchor, indent and dimming as [SettingsRow]; [content] must disable its own interaction when
 * [enabled] is false.
 */
@Composable
internal fun SettingsStackedRow(
    label: String,
    description: String? = null,
    anchor: String = label,
    indent: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tc = tc()
    Box(modifier.fillMaxWidth().settingsAnchor(anchor)) {
        Column(
            Modifier.fillMaxWidth()
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .padding(start = 12.dp + if (indent) SETTINGS_ROW_INDENT else 0.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AppText(label, color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!description.isNullOrBlank()) {
                    AppText(description, color = tc.ts, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            content()
        }
    }
}

/** One entry of a [SettingsOverflowMenu]: [tag] is its test tag, [danger] draws it in the danger colour. */
internal data class SettingsMenuItem(val label: String, val tag: String? = null, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * A small "⋯" button (22dp target, [testTag]) that opens a menu of [items], for a group's rare actions (it sits in
 * [SettingsGroup]'s `trailing`). Picking an item closes the menu and then runs it. [onInteraction] runs after every toggle, dismiss and
 * pick, before the item's own action, so the host can reclaim the keyboard focus that the dismissed popup took with it.
 */
@Composable
internal fun SettingsOverflowMenu(
    items: List<SettingsMenuItem>,
    contentDescription: String,
    testTag: String,
    modifier: Modifier = Modifier,
    onInteraction: () -> Unit = {},
) {
    val tc = tc()
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(modifier) {
        Box(
            Modifier.size(OVERFLOW_TARGET)
                .testTag(testTag)
                .clip(CORNER_SM)
                .background(if (hovered || open) tc.hv else Color.Transparent, CORNER_SM)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                    open = !open
                    onInteraction()
                }
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) {
            AppText("⋯", color = tc.ts, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        if (open) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, with(density) { (OVERFLOW_TARGET + 4.dp).roundToPx() }),
                onDismissRequest = {
                    open = false
                    onInteraction()
                },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(IntrinsicSize.Max).widthIn(min = OVERFLOW_MENU_MIN_WIDTH)
                        .shadow(8.dp, DROPDOWN_POPUP_SHAPE)
                        .background(tc.p, DROPDOWN_POPUP_SHAPE)
                        .border(1.dp, tc.br, DROPDOWN_POPUP_SHAPE)
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items.forEach { item ->
                        HoverBox(
                            modifier = Modifier.fillMaxWidth()
                                .then(if (item.tag != null) Modifier.testTag(item.tag) else Modifier)
                                .clip(DROPDOWN_OPTION_SHAPE),
                            onClick = {
                                open = false
                                onInteraction()
                                item.onClick()
                            },
                        ) {
                            AppText(
                                item.label,
                                color = if (item.danger) DANGER_RED else tc.tx,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A titled card of rows. The title is 11sp semibold secondary text above a panel-filled card with a 1dp border and 8dp corners;
 * [trailing] sits at the title's right edge (a "⋯" menu, a count). Every child the [content] emits is one row of the card and a 1dp
 * divider is drawn between neighbours, so conditional rows need no divider bookkeeping. An empty [title] with no [trailing] drops
 * the header line.
 */
@Composable
internal fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val tc = tc()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title.isNotEmpty() || trailing != null) {
            Row(Modifier.fillMaxWidth().padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                AppText(title, color = tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
        }
        DividedColumn(
            Modifier.fillMaxWidth()
                .clip(SETTINGS_CARD_SHAPE)
                .background(tc.p, SETTINGS_CARD_SHAPE)
                .border(1.dp, tc.br, SETTINGS_CARD_SHAPE),
            dividerColor = tc.br,
            content = content,
        )
    }
}

private enum class DividedSlot { ROWS, DIVIDERS }

/** Stacks whatever [content] emits top to bottom with a 1dp [dividerColor] line between each pair. */
@Composable
private fun DividedColumn(modifier: Modifier, dividerColor: Color, content: @Composable () -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val childConstraints = constraints.copy(
            minWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth,
            minHeight = 0,
            maxHeight = Constraints.Infinity,
        )
        val rows = subcompose(DividedSlot.ROWS, content).map { it.measure(childConstraints) }
        val dividers = subcompose(DividedSlot.DIVIDERS) {
            repeat((rows.size - 1).coerceAtLeast(0)) { Box(Modifier.fillMaxWidth().height(1.dp).background(dividerColor)) }
        }.map { it.measure(childConstraints) }
        val width = constraints.constrainWidth(rows.maxOfOrNull { it.width } ?: 0)
        val height = constraints.constrainHeight(rows.sumOf { it.height } + dividers.sumOf { it.height })
        layout(width, height) {
            var y = 0
            rows.forEachIndexed { index, row ->
                row.placeRelative(0, y)
                y += row.height
                dividers.getOrNull(index)?.let {
                    it.placeRelative(0, y)
                    y += it.height
                }
            }
        }
    }
}

/**
 * The top of a settings page: the nav group's name ([groupLabel]), the page [title] and a one-line [description], in 11sp / 14sp
 * semibold / 11sp.
 */
@Composable
internal fun SettingsPageHeader(groupLabel: String, title: String, description: String, modifier: Modifier = Modifier) {
    val tc = tc()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        AppText(groupLabel, color = tc.ts, fontSize = 11.sp)
        AppText(title, color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (description.isNotBlank()) AppText(description, color = tc.ts, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A 26dp field showing [label] of the [selected] option and an expand icon; a click opens a themed list of [options] below it, sized
 * to at least the field. Picking one calls [onSelect] and closes. The field is at least [minWidth] wide; pass `Modifier.fillMaxWidth()`
 * (and a small [minWidth]) to stretch it instead.
 *
 * [testTag] goes on the field and each option gets `optionTag(option)`, or `"$testTag-option-<index>"` when [optionTag] is null.
 * A click on the field right after a dismiss is ignored for 200 ms, because the popup's outside-click dismiss and the field's own
 * click would otherwise cancel out. [onInteraction] runs after every toggle, dismiss and pick so the host can reclaim keyboard
 * focus that the clicked field or the popup took.
 */
@Composable
internal fun <T> SettingsDropdown(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    minWidth: Dp = 168.dp,
    optionTag: ((T) -> String)? = null,
    onInteraction: () -> Unit = {},
) {
    val tc = tc()
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    var fieldWidth by remember { mutableStateOf(0.dp) }
    var suppressToggleUntilMs by remember { mutableStateOf(0L) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // propagateMinConstraints hands the field's minimum width on to the content row, so the icon stays at the right edge whether the
    // field is stretched by the caller or only held at [minWidth].
    Box(
        modifier.widthIn(min = minWidth).height(DROPDOWN_FIELD_HEIGHT)
            .testTag(testTag)
            .onGloballyPositioned { fieldWidth = with(density) { it.size.width.toDp() } }
            .clip(CORNER_SM)
            .background(tc.bg, CORNER_SM)
            .background(if (hovered) tc.hv else Color.Transparent, CORNER_SM)
            .border(1.dp, tc.br, CORNER_SM)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.DropdownList) {
                if (System.currentTimeMillis() >= suppressToggleUntilMs) {
                    open = !open
                    onInteraction()
                }
            }
            .padding(start = 8.dp, end = 6.dp),
        propagateMinConstraints = true,
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            AppText(
                label(selected),
                color = tc.tx,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
            )
            Icon(
                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = tc.ts,
                modifier = Modifier.size(16.dp),
            )
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { DROPDOWN_POPUP_OFFSET.roundToPx() }),
                onDismissRequest = {
                    open = false
                    suppressToggleUntilMs = System.currentTimeMillis() + DROPDOWN_REOPEN_GUARD_MS
                    onInteraction()
                },
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    Modifier.widthIn(min = fieldWidth).width(IntrinsicSize.Max)
                        .heightIn(max = DROPDOWN_POPUP_MAX_HEIGHT)
                        .shadow(8.dp, DROPDOWN_POPUP_SHAPE)
                        .background(tc.p, DROPDOWN_POPUP_SHAPE)
                        .border(1.dp, tc.br, DROPDOWN_POPUP_SHAPE)
                        .verticalScroll(rememberScrollState())
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    options.forEachIndexed { index, option ->
                        val active = option == selected
                        HoverBox(
                            modifier = Modifier.fillMaxWidth()
                                .testTag(optionTag?.invoke(option) ?: "$testTag-option-$index")
                                .clip(DROPDOWN_OPTION_SHAPE),
                            baseBg = if (active) tc.abg else Color.Transparent,
                            onClick = {
                                open = false
                                onSelect(option)
                                onInteraction()
                            },
                        ) {
                            AppText(
                                label(option),
                                color = if (active) tc.ac else tc.tx,
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A row that opens a sub-page: a leading [icon], the [label], a secondary [summary] and a chevron. [anchor] is the search index
 * key (default the label), as for [SettingsRow].
 */
@Composable
internal fun SettingsDrillRow(
    icon: ImageVector,
    label: String,
    summary: String?,
    onClick: () -> Unit,
    anchor: String = label,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    HoverBox(modifier.fillMaxWidth().settingsAnchor(anchor), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tc.ts, modifier = Modifier.size(16.dp))
            AppText(label, color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (!summary.isNullOrBlank()) {
                AppText(
                    summary,
                    color = tc.ts,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = DRILL_SUMMARY_MAX_WIDTH),
                )
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = tc.ts, modifier = Modifier.size(16.dp))
        }
    }
}
