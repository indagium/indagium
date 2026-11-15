@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.TooltipArea
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
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
private const val DESCRIPTION_MAX_LINES = 2
private const val DETAIL_TOOLTIP_MAX_LINES = 12
private val CHECKBOX_SIZE = 16.dp
private val CHECKBOX_SHAPE = RoundedCornerShape(3.dp)
private val DETAIL_ICON_SIZE = 12.dp
private const val BADGE_FILL_ALPHA = 0.13f
private const val BADGE_BORDER_ALPHA = 0.27f
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

/**
 * The height of every control in a settings row: dropdowns, text and number fields, segmented controls, steppers and row buttons. Outlines
 * are 1dp ([SETTINGS_CONTROL_BORDER]); fields use [CORNER_SM], segmented controls and steppers 6dp.
 */
internal val SETTINGS_CONTROL_HEIGHT = 26.dp
internal val SETTINGS_CONTROL_BORDER = 1.dp

/** The two standard field widths: short values and dropdowns, and URLs, names and model ids. In one card they all share one width. */
internal val SETTINGS_FIELD_SHORT = 200.dp
internal val SETTINGS_FIELD_LONG = 300.dp

/** The width of a [SettingsTextField] whose caller sets none. */
internal val SETTINGS_TEXT_FIELD_WIDTH = SETTINGS_FIELD_SHORT

/**
 * True while the [SettingsRow] or [SettingsStackedRow] drawing the control is disabled. The row already dims itself to half, so the
 * Settings controls inside it only stop taking input and add no dim of their own: one 50% dim, never two stacked.
 */
internal val LocalSettingsRowDimmed = compositionLocalOf { false }

/** The dim a control adds for its own `enabled = false`: half, unless the row around it is already dimmed. */
@Composable
internal fun Modifier.settingsDimmed(enabled: Boolean): Modifier = if (!enabled && !LocalSettingsRowDimmed.current) alpha(DISABLED_ALPHA) else this

/** Whether a Settings control takes input: its own [enabled] and the row around it being enabled. */
@Composable
internal fun settingsEnabled(enabled: Boolean): Boolean = enabled && !LocalSettingsRowDimmed.current

/** The fixed width of the number in a [SettingsNumberField]. */
internal val SETTINGS_NUMBER_FIELD_WIDTH = 64.dp
private const val SETTINGS_NUMBER_MAX_DIGITS = 12

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
            .settingsDimmed(enabled)
            .size(SWITCH_TRACK_WIDTH, SWITCH_TRACK_HEIGHT)
            .clip(shape)
            .background(if (checked) tc.ac else tc.p2, shape)
            .then(if (checked) Modifier else Modifier.border(1.dp, tc.td, shape))
            .toggleable(value = checked, enabled = settingsEnabled(enabled), role = Role.Switch, onValueChange = onCheckedChange)
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
 * One setting: [label] with an optional [description] (at most two lines) on the left and [control] right-aligned. It carries
 * [settingsAnchor] for [anchor] (the search index key, default the label) so a search result can scroll to and flash it.
 * [indent] marks a row that depends on the one above. [enabled] = false dims the row to half and disables the Settings controls in it
 * ([LocalSettingsRowDimmed]); a custom control must stop its own input. [danger] draws the label in the danger colour (a destructive
 * action).
 *
 * A description that does not fit two lines is never cut silently: the whole text, or [detail] when given, shows as a hover hint on the
 * label and description, marked by a small info glyph after the label.
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
    danger: Boolean = false,
    detail: String? = null,
    control: @Composable () -> Unit,
) {
    val tc = tc()
    var cut by remember(description) { mutableStateOf(false) }
    val hint = detail?.takeIf { it.isNotBlank() } ?: description?.takeIf { cut }
    Box(modifier.fillMaxWidth().settingsAnchor(anchor)) {
        CompositionLocalProvider(LocalSettingsRowDimmed provides !enabled) {
            Row(
                Modifier.fillMaxWidth()
                    .alpha(if (enabled) 1f else DISABLED_ALPHA)
                    .padding(start = 12.dp + if (indent) SETTINGS_ROW_INDENT else 0.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HintArea(hint, Modifier.weight(1f)) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppText(
                                label,
                                color = if (danger) DANGER_RED else tc.tx,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (hint != null) Icon(Icons.Outlined.Info, contentDescription = null, tint = tc.ts, modifier = Modifier.size(DETAIL_ICON_SIZE))
                        }
                        if (!description.isNullOrBlank()) {
                            AppText(
                                description,
                                color = tc.ts,
                                fontSize = 11.sp,
                                maxLines = DESCRIPTION_MAX_LINES,
                                overflow = TextOverflow.Ellipsis,
                                onTextLayout = { if (it.hasVisualOverflow != cut) cut = it.hasVisualOverflow },
                                modifier = if (descriptionTag != null) Modifier.testTag(descriptionTag) else Modifier,
                            )
                        }
                    }
                }
                control()
            }
        }
    }
}

/** Shows [hint] in a hover tooltip over [content]; with no hint it is just [content]. */
@Composable
private fun HintArea(hint: String?, modifier: Modifier, content: @Composable () -> Unit) {
    if (hint == null) {
        Box(modifier) { content() }
    } else {
        TooltipArea(tooltip = { ToolbarTooltip(hint, maxLines = DETAIL_TOOLTIP_MAX_LINES) }, modifier = modifier) { content() }
    }
}

/**
 * A [SettingsRow] whose control needs the full width: [label] and the optional [description] on top, [content] (a list of choices, a
 * picker) under them. Same padding, anchor, indent, dimming and [detail] hint as [SettingsRow]; [enabled] = false disables the
 * Settings controls in [content] too, a custom one must stop its own input.
 */
@Composable
internal fun SettingsStackedRow(
    label: String,
    description: String? = null,
    anchor: String = label,
    indent: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    detail: String? = null,
    content: @Composable () -> Unit,
) {
    val tc = tc()
    var cut by remember(description) { mutableStateOf(false) }
    val hint = detail?.takeIf { it.isNotBlank() } ?: description?.takeIf { cut }
    Box(modifier.fillMaxWidth().settingsAnchor(anchor)) {
        CompositionLocalProvider(LocalSettingsRowDimmed provides !enabled) {
            Column(
                Modifier.fillMaxWidth()
                    .alpha(if (enabled) 1f else DISABLED_ALPHA)
                    .padding(start = 12.dp + if (indent) SETTINGS_ROW_INDENT else 0.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HintArea(hint, Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppText(label, color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (hint != null) Icon(Icons.Outlined.Info, contentDescription = null, tint = tc.ts, modifier = Modifier.size(DETAIL_ICON_SIZE))
                        }
                        if (!description.isNullOrBlank()) {
                            AppText(
                                description,
                                color = tc.ts,
                                fontSize = 11.sp,
                                maxLines = DESCRIPTION_MAX_LINES,
                                overflow = TextOverflow.Ellipsis,
                                onTextLayout = { if (it.hasVisualOverflow != cut) cut = it.hasVisualOverflow },
                            )
                        }
                    }
                }
                content()
            }
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
        if (description.isNotBlank()) AppText(description, color = tc.ts, fontSize = 11.sp, maxLines = Int.MAX_VALUE)
    }
}

/**
 * A 26dp field showing [label] of the [selected] option and an expand icon; a click opens a themed list of [options] below it, sized
 * to at least the field. Picking one calls [onSelect] and closes. The field is at least [minWidth] wide; pass `Modifier.fillMaxWidth()`
 * (and a small [minWidth]) to stretch it instead.
 *
 * [testTag] goes on the field and each option gets `optionTag(option)`, or `"$testTag-option-<index>"` when [optionTag] is null.
 * An option for which [isEnabled] is false is dimmed, ignores clicks and exposes the disabled semantics; [optionNote] adds a small
 * dim note after an option's label (why it is unavailable, or what it means). A click on the field right after a dismiss is ignored
 * for 200 ms, because the popup's outside-click dismiss and the field's own click would otherwise cancel out. [onInteraction] runs
 * after every toggle, dismiss and pick so the host can reclaim keyboard focus that the clicked field or the popup took.
 */
@Composable
internal fun <T> SettingsDropdown(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    minWidth: Dp = SETTINGS_FIELD_SHORT,
    optionTag: ((T) -> String)? = null,
    isEnabled: (T) -> Boolean = { true },
    optionNote: (T) -> String? = { null },
    onInteraction: () -> Unit = {},
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    placeholder: String? = null,
) {
    val tc = tc()
    var open by remember { mutableStateOf(false) }
    var suppressToggleUntilMs by remember { mutableStateOf(0L) }
    SettingsDropdownField(
        text = placeholder ?: label(selected),
        open = open && settingsEnabled(enabled),
        onClick = {
            if (System.currentTimeMillis() >= suppressToggleUntilMs) {
                open = !open
                onInteraction()
            }
        },
        modifier = modifier,
        minWidth = minWidth,
        testTag = testTag,
        textColor = if (placeholder != null) tc.td else null,
        enabled = enabled,
        leading = leading,
    ) { fieldWidth, popupOffset ->
        Popup(
            // Right-aligned to the field, like the control it sits beside: a popup wider than the field grows to the left.
            alignment = Alignment.TopEnd,
            offset = popupOffset,
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
                    val optionEnabled = isEnabled(option)
                    val note = optionNote(option)
                    HoverBox(
                        modifier = Modifier.fillMaxWidth()
                            .testTag(optionTag?.invoke(option) ?: "$testTag-option-$index")
                            .clip(DROPDOWN_OPTION_SHAPE)
                            .then(if (optionEnabled) Modifier else Modifier.semantics { disabled() }),
                        baseBg = if (active) tc.abg else Color.Transparent,
                        hoverEnabled = optionEnabled,
                        onClick = if (optionEnabled) {
                            {
                                open = false
                                onSelect(option)
                                onInteraction()
                            }
                        } else {
                            null
                        },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppText(
                                label(option),
                                color = if (active) tc.ac else tc.tx,
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.alpha(if (optionEnabled) 1f else DISABLED_ALPHA),
                            )
                            if (note != null) {
                                AppText("·", color = tc.ts, fontSize = 11.sp, modifier = Modifier.alpha(if (optionEnabled) 1f else DISABLED_ALPHA))
                                AppText(note, color = tc.ts, fontSize = 10.sp, modifier = Modifier.alpha(if (optionEnabled) 1f else DISABLED_ALPHA))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The closed field every settings dropdown shares: 26dp, the page background with a 1dp border, [text] in 12sp (in [textFontFamily]
 * when set, so a font picker can show the selected family; in [textColor] when set, e.g. a placeholder) and an expand icon that flips
 * with [open]; a hover tint.
 * [onClick] toggles the menu. The field is at least [minWidth] wide, or pass `Modifier.fillMaxWidth()` and a small [minWidth] to
 * stretch it. [testTag], when set, goes on the field.
 *
 * While [open] it calls [popup] inside the field, with the field's current width and the offset that puts a popup just below it, so
 * the caller adds its own `Popup` (its own dismiss handling and focus mode) and content.
 */
@Composable
internal fun SettingsDropdownField(
    text: String,
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = SETTINGS_FIELD_SHORT,
    testTag: String? = null,
    textFontFamily: FontFamily? = null,
    textColor: Color? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    popup: @Composable (fieldWidth: Dp, popupOffset: IntOffset) -> Unit = { _, _ -> },
) {
    val tc = tc()
    val active = settingsEnabled(enabled)
    val density = LocalDensity.current
    var fieldWidth by remember { mutableStateOf(0.dp) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // propagateMinConstraints hands the field's minimum width on to the content row, so the icon stays at the right edge whether the
    // field is stretched by the caller or only held at [minWidth].
    Box(
        modifier.widthIn(min = minWidth).height(DROPDOWN_FIELD_HEIGHT)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .settingsDimmed(enabled)
            .onGloballyPositioned { fieldWidth = with(density) { it.size.width.toDp() } }
            .clip(CORNER_SM)
            .background(tc.bg, CORNER_SM)
            .background(if (hovered && active) tc.hv else Color.Transparent, CORNER_SM)
            .border(1.dp, tc.br, CORNER_SM)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = active, role = Role.DropdownList, onClick = onClick)
            .padding(start = 8.dp, end = 6.dp),
        propagateMinConstraints = true,
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(8.dp))
                }
                AppText(
                    text,
                    color = textColor ?: tc.tx,
                    fontSize = 12.sp,
                    fontFamily = textFontFamily ?: LocalUiFontFamily.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                )
            }
            Icon(
                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = tc.ts,
                modifier = Modifier.size(16.dp),
            )
        }
        if (open && active) popup(fieldWidth, IntOffset(0, with(density) { DROPDOWN_POPUP_OFFSET.roundToPx() }))
    }
}

/**
 * The one single-line text field of the Settings pages, drawn like [SettingsDropdownField]: 26dp tall, the page background with a 1dp
 * border (the danger colour while [invalid]) and [CORNER_SM] corners, 12sp text with an accent caret, [placeholder] in the dim colour.
 * [enabled] = false ignores input and dims it to half. [monospace] and [textAlign] serve paths and numbers; [onClear] adds a small x
 * while there is text; [onSubmit] runs on Enter. The field is [SETTINGS_TEXT_FIELD_WIDTH] wide unless [modifier] says otherwise
 * (`Modifier.width(..)`, `Modifier.fillMaxWidth()`, `Modifier.weight(..)`). [testTag] goes on the field itself, the node a test types into.
 */
@Composable
internal fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier.width(SETTINGS_TEXT_FIELD_WIDTH),
    placeholder: String = "",
    enabled: Boolean = true,
    invalid: Boolean = false,
    monospace: Boolean = false,
    textAlign: TextAlign = TextAlign.Start,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onSubmit: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val tc = tc()
    val focusReporter = textInputFocusReporter()
    val fontFamily = if (monospace) MONO else LocalUiFontFamily.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = settingsEnabled(enabled),
        textStyle = TextStyle(color = tc.tx, fontSize = 12.sp, fontFamily = fontFamily, textAlign = textAlign),
        cursorBrush = SolidColor(tc.ac),
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
        modifier = modifier
            .then(focusReporter)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .settingsDimmed(enabled)
            .height(DROPDOWN_FIELD_HEIGHT)
            .background(tc.bg, CORNER_SM)
            .border(1.dp, if (invalid) DANGER_RED else tc.br, CORNER_SM),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxSize().padding(start = 8.dp, end = if (onClear != null) 3.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = if (textAlign == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        AppText(placeholder, color = tc.td, fontSize = 12.sp, fontFamily = fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
                if (onClear != null && value.isNotEmpty() && settingsEnabled(enabled)) {
                    SquareIconButton("×", fontSize = 12.sp, onClick = onClear, modifier = Modifier.padding(start = 4.dp), size = 18.dp)
                }
            }
        },
    )
}

/**
 * A [SettingsTextField] for a whole number: digits only (at most [maxDigits]), right-aligned in a fixed [SETTINGS_NUMBER_FIELD_WIDTH]
 * and followed by [unit] in 11sp secondary text. [unitWidth] holds the unit's column to a fixed width so the fields of several rows
 * line up whatever their unit. [value] is the text as typed, so a caller can show a number it rejects and flag it with [invalid];
 * [modifier] applies to the field and unit together, [testTag] to the field.
 */
@Composable
internal fun SettingsNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    unit: String? = null,
    unitWidth: Dp? = null,
    placeholder: String = "",
    maxDigits: Int = SETTINGS_NUMBER_MAX_DIGITS,
    enabled: Boolean = true,
    invalid: Boolean = false,
    testTag: String? = null,
) {
    val tc = tc()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingsTextField(
            value = value,
            onValueChange = { typed -> onValueChange(typed.filter(Char::isDigit).take(maxDigits)) },
            modifier = Modifier.width(SETTINGS_NUMBER_FIELD_WIDTH),
            placeholder = placeholder,
            enabled = enabled,
            invalid = invalid,
            monospace = true,
            textAlign = TextAlign.End,
            keyboardType = KeyboardType.Number,
            testTag = testTag,
        )
        if (unit != null) {
            AppText(
                unit,
                color = tc.ts,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.settingsDimmed(enabled).then(if (unitWidth != null) Modifier.width(unitWidth) else Modifier),
            )
        }
    }
}

/** Test tag of every [SettingsEmptyState] unless the caller gives it another. */
internal const val SETTINGS_EMPTY_STATE_TAG = "settings-empty-state"

/**
 * What a list shows while it has nothing in it: one compact, centred line of 11sp secondary [text] in a 1dp bordered box with small
 * corners. [asRow] pads it as a card row of its own (a direct child of a [SettingsGroup]); otherwise it fills the width it is given
 * (the content of a stacked row, where the list would have been).
 */
@Composable
internal fun SettingsEmptyState(
    text: String,
    modifier: Modifier = Modifier,
    asRow: Boolean = false,
    testTag: String = SETTINGS_EMPTY_STATE_TAG,
) {
    val tc = tc()
    val line = @Composable {
        Box(
            Modifier.fillMaxWidth()
                .testTag(testTag)
                .background(tc.bg, CORNER_SM)
                .border(1.dp, tc.br, CORNER_SM)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            AppText(text, color = tc.ts, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
    if (asRow) {
        Box(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) { line() }
    } else {
        Box(modifier.fillMaxWidth()) { line() }
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

/**
 * A card row that is only a sentence: a note under the rows above it or a status line (an error in the danger colour when [error]).
 * [anchor], when set, makes it a search target like any row.
 */
@Composable
internal fun SettingsNoteRow(text: String, error: Boolean = false, modifier: Modifier = Modifier, anchor: String? = null) {
    val tc = tc()
    Box(modifier.fillMaxWidth().then(if (anchor != null) Modifier.settingsAnchor(anchor) else Modifier)) {
        AppText(
            text,
            color = if (error) DANGER_RED else tc.ts,
            fontSize = 11.sp,
            maxLines = Int.MAX_VALUE,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}

/** A small accent pill with a [text] (a status such as "Running" or an edition name); [dot] adds a leading status dot. */
@Composable
internal fun SettingsBadge(text: String, modifier: Modifier = Modifier, dot: Boolean = false) {
    val tc = tc()
    val shape = RoundedCornerShape(3.dp)
    Row(
        modifier.background(tc.ac.copy(alpha = BADGE_FILL_ALPHA), shape)
            .border(1.dp, tc.ac.copy(alpha = BADGE_BORDER_ALPHA), shape)
            .padding(horizontal = 8.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) Box(Modifier.size(6.dp).background(tc.ac, CircleShape))
        AppText(text, color = tc.ac, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * A [SegmentedControl] at the Settings control height ([SETTINGS_CONTROL_HEIGHT], a 1dp outline): 2 to 4 short choices. The chosen
 * segment is the accent wash with accent text; a disabled control (or one in a dimmed row) keeps its colours, takes no input and is dimmed
 * once. [segmentModifier] and [segmentLeading] put a test tag or a small picture on one segment.
 */
@Composable
internal fun SettingsSegmented(
    options: List<String>,
    selectedIndices: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
    selectedColors: List<Color>? = null,
    weightByLabel: Boolean = false,
    segmentModifier: (index: Int) -> Modifier = { Modifier },
    segmentLeading: (@Composable (index: Int, color: Color) -> Unit)? = null,
    segmentHorizontalPadding: Dp = 10.dp,
) {
    SegmentedControl(
        options = options,
        selectedIndices = selectedIndices,
        onToggle = onToggle,
        modifier = modifier.settingsDimmed(enabled),
        selectedColors = selectedColors,
        fillWidth = fillWidth,
        enabled = settingsEnabled(enabled),
        segmentHeight = SETTINGS_CONTROL_HEIGHT,
        weightByLabel = weightByLabel,
        borderWidth = SETTINGS_CONTROL_BORDER,
        recolourWhenDisabled = false,
        segmentModifier = segmentModifier,
        segmentLeading = segmentLeading,
        segmentHorizontalPadding = segmentHorizontalPadding,
    )
}

/** A [ListStepper] at the Settings control height, with a 1dp outline. */
@Composable
internal fun SettingsStepper(
    options: List<Int>,
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    format: (Int) -> String = Int::toString,
    enabled: Boolean = true,
) {
    ListStepper(
        options = options,
        value = value,
        onChange = onChange,
        modifier = modifier.settingsDimmed(enabled),
        format = format,
        height = SETTINGS_CONTROL_HEIGHT,
        borderWidth = SETTINGS_CONTROL_BORDER,
        enabled = settingsEnabled(enabled),
    )
}

/**
 * A button in a settings row, at the Settings control height. Secondary for a row action, [ButtonVariant.Primary] only for the commit of
 * an explicit-save form, [isDanger] for a destructive action (its label then ends in "…" when it confirms). Disabled (or in a dimmed row)
 * it is dimmed once and takes no input.
 */
@Composable
internal fun SettingsButton(
    label: String,
    onClick: () -> Unit,
    variant: ButtonVariant = ButtonVariant.Secondary,
    isDanger: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    tooltip: String? = null,
    horizontalPadding: Dp = 10.dp,
) {
    val tc = tc()
    val active = settingsEnabled(enabled)
    val sized = modifier.height(SETTINGS_CONTROL_HEIGHT).settingsDimmed(enabled)
    if (active) {
        AppButton(label, onClick, variant, isDanger, modifier = sized, leadingIcon = leadingIcon, horizontalPadding = horizontalPadding, tooltip = tooltip)
    } else if (variant == ButtonVariant.Primary) {
        // A disabled primary keeps its fill (AppButton draws none for a disabled one), so it only goes dim and inert.
        AppButton(label, {}, variant, isDanger, modifier = sized.semantics { disabled() }, leadingIcon = leadingIcon, horizontalPadding = horizontalPadding)
    } else {
        val textColor = when {
            isDanger -> DANGER_RED
            variant == ButtonVariant.Ghost -> tc.ts
            else -> tc.tx
        }
        AppButton(
            label,
            onClick,
            variant,
            isDanger,
            enabled = false,
            modifier = sized,
            leadingIcon = leadingIcon,
            horizontalPadding = horizontalPadding,
            textColor = textColor,
        )
    }
}

/**
 * The one checkbox of the Settings pages, for picking several items from a set or for a consent. It carries no inset of its own, so it
 * sits on the text edge of the row it is in: 16dp box, accent fill with a check when on. One toggleable node; [enabled] = false dims it
 * once and ignores clicks. [anchor] makes it a search target.
 */
@Composable
internal fun SettingsCheckbox(
    checked: Boolean,
    label: String,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    testTag: String? = null,
    modifier: Modifier = Modifier,
    anchor: String? = null,
    maxLines: Int = 1,
    monospace: Boolean = false,
    labelColor: Color? = null,
    fontSize: TextUnit = 12.sp,
) {
    val tc = tc()
    DisableSelection {
        Row(
            modifier
                .then(if (anchor != null) Modifier.settingsAnchor(anchor) else Modifier)
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .settingsDimmed(enabled)
                .toggleable(value = checked, enabled = settingsEnabled(enabled), role = Role.Checkbox, onValueChange = onChange)
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(CHECKBOX_SIZE)
                    .background(if (checked) tc.ac else Color.Transparent, CHECKBOX_SHAPE)
                    .border(1.5.dp, if (checked) tc.ac else tc.td, CHECKBOX_SHAPE),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) Icon(Icons.Outlined.Check, contentDescription = null, tint = tc.onAc, modifier = Modifier.size(12.dp))
            }
            AppText(
                label,
                color = labelColor ?: tc.tx,
                fontSize = fontSize,
                fontFamily = if (monospace) MONO else LocalUiFontFamily.current,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val PICKER_SELECTED_BORDER_DP = 1.5f

/**
 * The surface of a picture picker's card (a theme, a tab style, a pack, a profile): the accent wash with a 1.5dp accent border when
 * [selected], else the page fill with a 1dp outline. [hovered] adds only the hover tint, never an accent border. Draw its caption in
 * [settingsPickerCaptionColor]. [colors] are the ones to draw with (a pack card draws in its own palette).
 */
internal fun Modifier.settingsPickerSurface(selected: Boolean, hovered: Boolean, shape: Shape, colors: ThemeColors): Modifier =
    this.clip(shape)
        .background(if (selected) colors.abg else colors.bg, shape)
        .background(if (hovered) colors.hv else Color.Transparent, shape)
        .border(if (selected) PICKER_SELECTED_BORDER_DP.dp else 1.dp, if (selected) colors.ac else colors.br, shape)

/** The caption colour of a picture picker's card: accent when [selected], else secondary. */
internal fun settingsPickerCaptionColor(selected: Boolean, colors: ThemeColors): Color = if (selected) colors.ac else colors.ts
