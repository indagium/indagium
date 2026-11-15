// The thumbnail drawing is a few dozen literal geometry numbers (dp-ish units of a 84 x 34 sketch), not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.TabCloseButtonPosition
import com.indagium.model.TabLabelMode
import com.indagium.model.TabSizeMode
import com.indagium.model.TabStripPosition
import com.indagium.model.TabStripStyle

// Settings > Tabs & toolbar, the tab half: the strip's style as a grid of thumbnails, the optional details, width, label and close button.
//
// Details rule: the file-type badge and the source colour stripe are two independent details any style can add. The TYPED style
// always draws the badge and the SOURCE_STRIPE style always draws the stripe (that is what they are), so while one of those styles is
// selected its switch is shown on and locked (the row dimmed); every other style shows the switch as stored.

/** The thumbnail caption of a style. */
internal fun tabStyleLabel(style: TabStripStyle): String = when (style) {
    TabStripStyle.CURRENT -> "Current"
    TabStripStyle.RAISED -> "Raised"
    TabStripStyle.SEGMENTED -> "Segmented"
    TabStripStyle.TYPED -> "Typed"
    TabStripStyle.COMPACT -> "Compact"
    TabStripStyle.SOURCE_STRIPE -> "Source stripe"
    TabStripStyle.TWO_LINE -> "Two-line"
    TabStripStyle.QUIET -> "Quiet"
    TabStripStyle.OUTLINED -> "Outlined"
    TabStripStyle.PILL -> "Pill"
}

internal fun tabLabelModeLabel(mode: TabLabelMode): String = when (mode) {
    TabLabelMode.FILE_SESSION -> "File, plus folder if same name"
    TabLabelMode.SESSION_ONLY -> "Folder"
    TabLabelMode.FILE_ONLY -> "File only"
    TabLabelMode.FILE_FOLDER_ALWAYS -> "File — folder"
}

/** The hint under the Tab label control: what the default does, which is the one choice whose name does not say it all. */
internal const val TAB_LABEL_HINT: String = "The folder is added when two open tabs have the same file name."

/** Test tags of the Tab label control and its hint. */
internal const val TAB_LABEL_CONTROL_TAG = "tab-label-mode"
internal const val TAB_LABEL_HINT_TAG = "tab-label-hint"
internal const val TAB_CLOSE_BUTTON_POSITION_CONTROL_TAG = "tab-close-button-position"
internal const val TAB_STYLE_GRID_TAG = "tab-style-grid"

/** The descriptions of the Tabs rows, shared with their search entries. */
internal const val TAB_SIZE_HINT = "Share the strip gives every tab an equal share of the strip; Fit the label sizes each tab to its name."
internal const val TAB_BADGE_HINT = "Adds a LOG, ZIP or IND badge before the tab name."
internal const val TAB_STRIPE_HINT = "Adds a coloured stripe on top of a tab by where it came from: file, live capture, bug report or capture."
internal const val TAB_CLOSE_EVERY_HINT = "Off shows a tab's close button only on the active tab and under the pointer."
internal const val TAB_POSITION_HINT = "Which window edge the tab strip sits on. You can also drag it by its grip."
internal const val TAB_CLOSE_POSITION_HINT = "Which end of a tab its close button sits on."
internal const val VISIBLE_TABS_HINT = "How many tabs show before the rest collapse into a menu."
private const val LOCKED_DETAIL_HINT = "Always on with this tab style."

/** The options of the Width control. */
internal val TAB_SIZE_OPTIONS = listOf("Share the strip", "Fit the label")
private val VISIBLE_TAB_LIMITS = listOf(4, 6, 8, 10, 12, 16)
private val THUMB_MIN_WIDTH = 84.dp
private val THUMB_HEIGHT = 34.dp
private val TAB_STYLE_GRID_GAP = 8.dp
private const val TAB_STYLE_COLUMNS = 5

/**
 * Settings › Tabs & toolbar: the Tab style group, the Tabs group (position, width, label, close button, visible tabs) and the Toolbar
 * group ([ChromePlacementSettings]). [onInteraction] runs after a dropdown or menu closes so the dialog can reclaim keyboard focus.
 */
@Composable
internal fun TabsAppearanceSettings(state: AppState, onInteraction: () -> Unit = {}) {
    TabStyleGroup(state)
    TabsGroup(state, onInteraction, includePlacement = true)
    ChromePlacementSettings(state, onInteraction)
}

/**
 * The tab look shared with the setup assistant's Look step: the Tab style group and the Tabs group without the position and visible-tabs
 * rows (those are Settings-only). Changes go straight through AppState in both places.
 */
@Composable
internal fun TabAppearanceControls(state: AppState, onInteraction: () -> Unit = {}) {
    TabStyleGroup(state)
    TabsGroup(state, onInteraction, includePlacement = false)
}

// The style thumbnails, then the two details a style can add (the badge and the source stripe) as switch rows.
@Composable
private fun TabStyleGroup(state: AppState) {
    val settings = state.settings
    SettingsGroup("Tab style") {
        Box(Modifier.fillMaxWidth().settingsAnchor("Tab style").padding(12.dp)) {
            TabStyleGrid(settings.tabStripStyle) { style -> state.updateSettings { it.copy(tabStripStyle = style) } }
        }
        val typed = settings.tabStripStyle == TabStripStyle.TYPED
        val striped = settings.tabStripStyle == TabStripStyle.SOURCE_STRIPE
        DetailSwitchRow("File-type badge", if (typed) LOCKED_DETAIL_HINT else TAB_BADGE_HINT, tabBadgeShown(settings), locked = typed) {
            state.updateSettings { it.copy(tabShowTypeBadge = !it.tabShowTypeBadge) }
        }
        DetailSwitchRow("Source colour stripe", if (striped) LOCKED_DETAIL_HINT else TAB_STRIPE_HINT, tabStripeShown(settings), locked = striped) {
            state.updateSettings { it.copy(tabShowSourceStripe = !it.tabShowSourceStripe) }
        }
    }
}

// Rows of tab behaviour. [includePlacement] adds the strip position (first) and the visible-tab count (last), which the setup assistant omits.
@Composable
private fun TabsGroup(state: AppState, onInteraction: () -> Unit, includePlacement: Boolean) {
    val settings = state.settings
    SettingsGroup("Tabs") {
        if (includePlacement) {
            SettingsRow("Position", TAB_POSITION_HINT, anchor = "Tab position") {
                val positions = TabStripPosition.entries
                SettingsSegmented(
                    options = positions.map(::tabPositionLabel),
                    selectedIndices = setOf(positions.indexOf(settings.tabStripPosition)),
                    onToggle = { idx -> state.updateSettings { it.copy(tabStripPosition = positions[idx]) } },
                    modifier = Modifier.testTag("tab-position"),
                )
            }
        }
        SettingsRow("Width", TAB_SIZE_HINT, anchor = "Tab size") {
            val modes = TabSizeMode.entries
            SettingsSegmented(
                options = TAB_SIZE_OPTIONS,
                selectedIndices = setOf(modes.indexOf(settings.tabSizeMode)),
                onToggle = { idx -> state.updateSettings { it.copy(tabSizeMode = modes[idx]) } },
            )
        }
        SettingsRow("Label", TAB_LABEL_HINT, anchor = "Tab label", descriptionTag = TAB_LABEL_HINT_TAG) {
            SettingsDropdown(
                options = TabLabelMode.entries,
                selected = settings.tabLabelMode,
                label = ::tabLabelModeLabel,
                onSelect = { mode -> state.updateSettings { it.copy(tabLabelMode = mode) } },
                testTag = TAB_LABEL_CONTROL_TAG,
                optionTag = { mode -> "$TAB_LABEL_CONTROL_TAG-option-${mode.name}" },
                onInteraction = onInteraction,
            )
        }
        SettingsRow("Close button position", TAB_CLOSE_POSITION_HINT) {
            val positions = TabCloseButtonPosition.entries
            SettingsSegmented(
                options = listOf("Left", "Right"),
                selectedIndices = setOf(positions.indexOf(settings.tabCloseButtonPosition)),
                onToggle = { idx -> state.updateSettings { it.copy(tabCloseButtonPosition = positions[idx]) } },
                modifier = Modifier.testTag(TAB_CLOSE_BUTTON_POSITION_CONTROL_TAG),
            )
        }
        DetailSwitchRow("Close button on every tab", TAB_CLOSE_EVERY_HINT, settings.tabCloseOnEvery, locked = false) {
            state.updateSettings { it.copy(tabCloseOnEvery = !it.tabCloseOnEvery) }
        }
        if (includePlacement) {
            SettingsRow("Visible tabs", VISIBLE_TABS_HINT) {
                SettingsStepper(
                    options = VISIBLE_TAB_LIMITS,
                    value = settings.visibleTabLimit,
                    onChange = { v -> state.updateSettings { it.copy(visibleTabLimit = v) } },
                )
            }
        }
    }
}

// One on/off detail of a tab as a switch row; [locked] dims the row and keeps the switch as the style forces it.
@Composable
private fun DetailSwitchRow(label: String, description: String, checked: Boolean, locked: Boolean, onToggle: () -> Unit) {
    SettingsRow(label, description, enabled = !locked) {
        SettingsSwitch(
            checked = checked,
            onCheckedChange = { onToggle() },
            label = label,
            testTag = "tab-detail-${label.lowercase().replace(Regex("[^a-z]+"), "-")}",
        )
    }
}

@Composable
private fun TabStyleGrid(selected: TabStripStyle, onSelect: (TabStripStyle) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag(TAB_STYLE_GRID_TAG)) {
        val minimumCellWidth = minOf(THUMB_MIN_WIDTH, maxWidth)
        val columnCount = ((maxWidth + TAB_STYLE_GRID_GAP) / (minimumCellWidth + TAB_STYLE_GRID_GAP))
            .toInt()
            .coerceIn(1, TAB_STYLE_COLUMNS)
        Column(verticalArrangement = Arrangement.spacedBy(TAB_STYLE_GRID_GAP)) {
            TabStripStyle.entries.chunked(columnCount).forEach { rowStyles ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TAB_STYLE_GRID_GAP)) {
                    rowStyles.forEach { style ->
                        TabStyleThumb(
                            style = style,
                            active = style == selected,
                            modifier = Modifier.weight(1f).widthIn(min = minimumCellWidth),
                        ) { onSelect(style) }
                    }
                    repeat(columnCount - rowStyles.size) {
                        Spacer(Modifier.weight(1f).widthIn(min = minimumCellWidth))
                    }
                }
            }
        }
    }
}

@Composable
private fun TabStyleThumb(style: TabStripStyle, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tc = tc()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(THUMB_HEIGHT)
                .settingsPickerSurface(active, hovered, CORNER_MD, tc)
                .testTag("tab-style-${style.name}")
                .semantics {
                    contentDescription = tabStyleLabel(style)
                    selected = active
                }
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                .padding(4.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) { drawTabThumb(style, tc, active) }
        }
        AppText(
            tabStyleLabel(style),
            color = settingsPickerCaptionColor(active, tc),
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().testTag("tab-style-label-${style.name}"),
        )
    }
}

private val THUMB_STRIPES = listOf(Color(0xFF60a5fa), Color(0xFFfb923c), Color(0xFFa78bfa))

private data class TabThumbMetrics(
    val tc: ThemeColors,
    val tabColor: Color,
    val dimColor: Color,
    val unit: Float,
    val tabWidth: Float,
    val fullHeight: Float,
    val height: Float,
    val top: Float,
    val accent: Color,
)

// Three miniature tabs (the middle one active) in the look of [style].
private fun DrawScope.drawTabThumb(style: TabStripStyle, tc: ThemeColors, selected: Boolean) {
    val unit = 1.dp.toPx()
    val tabWidth = size.width / 3f
    val full = size.height
    val height = if (style == TabStripStyle.COMPACT) full * 0.72f else full
    val top = full - height
    val metrics = TabThumbMetrics(
        tc = tc,
        tabColor = tc.td.copy(alpha = if (selected) 0.9f else 0.7f),
        dimColor = tc.td.copy(alpha = 0.45f),
        unit = unit,
        tabWidth = tabWidth,
        fullHeight = full,
        height = height,
        top = top,
        accent = tc.ac,
    )
    repeat(3) { i ->
        val x = i * tabWidth
        val isActive = i == 1
        val inner = Offset(x + 4 * unit, top + height / 2f - unit)
        when (style) {
            TabStripStyle.RAISED -> drawRaisedThumb(metrics, x, isActive, inner)
            TabStripStyle.SEGMENTED -> drawSegmentedThumb(metrics, x, isActive, inner)
            TabStripStyle.QUIET -> drawQuietThumb(metrics, x, isActive, inner)
            TabStripStyle.OUTLINED -> drawOutlinedThumb(metrics, x, isActive, inner)
            TabStripStyle.PILL -> drawPillThumb(metrics, x, isActive, inner)
            else -> drawClassicThumb(style, metrics, x, i, isActive)
        }
    }
}

private fun DrawScope.drawRaisedThumb(metrics: TabThumbMetrics, x: Float, active: Boolean, inner: Offset) {
    val unit = metrics.unit
    if (active) {
        drawRoundRect(metrics.tc.br, Offset(x + unit, metrics.top + 2 * unit), Size(metrics.tabWidth - 2 * unit, metrics.height), CornerRadius(4 * unit))
        drawRoundRect(
            metrics.tc.bg,
            Offset(x + 1.5f * unit, metrics.top + 3 * unit),
            Size(metrics.tabWidth - 3 * unit, metrics.height),
            CornerRadius(3.5f * unit),
        )
    }
    drawBar(inner.copy(y = metrics.top + metrics.height / 2f + unit), metrics.tabWidth - 10 * unit, if (active) metrics.tabColor else metrics.dimColor)
}

private fun DrawScope.drawSegmentedThumb(metrics: TabThumbMetrics, x: Float, active: Boolean, inner: Offset) {
    val unit = metrics.unit
    if (active) {
        drawRoundRect(
            metrics.tc.br,
            Offset(x + unit, metrics.top + 3 * unit),
            Size(metrics.tabWidth - 2 * unit, metrics.height - 6 * unit),
            CornerRadius(3 * unit),
        )
        drawRoundRect(
            metrics.tc.p,
            Offset(x + 1.5f * unit, metrics.top + 3.5f * unit),
            Size(metrics.tabWidth - 3 * unit, metrics.height - 7 * unit),
            CornerRadius(2.5f * unit),
        )
        drawCircle(metrics.accent, 1.5f * unit, Offset(x + 5 * unit, metrics.top + metrics.height / 2f))
    }
    drawBar(inner.copy(x = x + (if (active) 8 else 4) * unit), metrics.tabWidth - 14 * unit, if (active) metrics.tabColor else metrics.dimColor)
}

private fun DrawScope.drawQuietThumb(metrics: TabThumbMetrics, x: Float, active: Boolean, inner: Offset) {
    val unit = metrics.unit
    val color = if (active) metrics.accent else metrics.dimColor
    drawBar(inner.copy(y = metrics.top + metrics.height / 2f - 3 * unit), metrics.tabWidth - 10 * unit, color)
    drawRect(if (active) metrics.accent else metrics.tc.br, Offset(x, metrics.fullHeight - 2 * unit), Size(metrics.tabWidth, 2 * unit))
}

private fun DrawScope.drawOutlinedThumb(metrics: TabThumbMetrics, x: Float, active: Boolean, inner: Offset) {
    val unit = metrics.unit
    val left = x + 2 * unit
    val top = metrics.top + 2 * unit
    val width = metrics.tabWidth - 4 * unit
    val height = metrics.height - 4 * unit
    drawRoundRect(if (active) metrics.tc.p else Color.Transparent, Offset(left, top), Size(width, height), CornerRadius(5 * unit))
    drawRoundRect(
        if (active) metrics.accent else metrics.tc.br,
        Offset(left, top), Size(width, height), CornerRadius(5 * unit),
        style = androidx.compose.ui.graphics.drawscope.Stroke(unit),
    )
    drawBar(inner.copy(x = x + 5 * unit), metrics.tabWidth - 12 * unit, if (active) metrics.tabColor else metrics.dimColor)
}

private fun DrawScope.drawPillThumb(metrics: TabThumbMetrics, x: Float, active: Boolean, inner: Offset) {
    val unit = metrics.unit
    val left = x + 2 * unit
    val top = metrics.top + 5 * unit
    val width = metrics.tabWidth - 4 * unit
    val height = metrics.height - 10 * unit
    drawRoundRect(
        if (active) metrics.accent.copy(alpha = 0.2f) else metrics.tc.p,
        Offset(left, top), Size(width, height), CornerRadius(height / 2f),
    )
    drawRoundRect(
        if (active) metrics.accent else metrics.tc.br,
        Offset(left, top), Size(width, height), CornerRadius(height / 2f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(unit),
    )
    drawBar(inner.copy(x = x + 5 * unit), metrics.tabWidth - 12 * unit, if (active) metrics.accent else metrics.dimColor)
}

private fun DrawScope.drawClassicThumb(style: TabStripStyle, metrics: TabThumbMetrics, x: Float, index: Int, active: Boolean) {
    val unit = metrics.unit
    drawRect(if (active) metrics.tc.bg else metrics.tc.p2, Offset(x, metrics.top), Size(metrics.tabWidth, metrics.height))
    drawRect(metrics.tc.br, Offset(x, metrics.top), Size(metrics.tabWidth, metrics.height), style = androidx.compose.ui.graphics.drawscope.Stroke(unit))
    if (active) drawRect(metrics.accent, Offset(x, metrics.fullHeight - 2 * unit), Size(metrics.tabWidth, 2 * unit))
    val textColor = if (active) metrics.tabColor else metrics.dimColor
    drawClassicDetails(style, metrics.tc, x, metrics.top, metrics.tabWidth, metrics.height, index, textColor)
}

private fun DrawScope.drawBar(topLeft: Offset, width: Float, color: Color) {
    drawRoundRect(color, topLeft, Size(width.coerceAtLeast(2f), 2.dp.toPx()), CornerRadius(1.dp.toPx()))
}

// The label bar of the classic-family styles, with their own detail: the badge pill, the source stripe, the second line.
private fun DrawScope.drawClassicDetails(
    style: TabStripStyle,
    tc: ThemeColors,
    x: Float,
    top: Float,
    tabWidth: Float,
    height: Float,
    index: Int,
    color: Color,
) {
    val unit = 1.dp.toPx()
    val midY = top + height / 2f - unit
    when (style) {
        TabStripStyle.TYPED -> {
            drawRoundRect(tc.hv.copy(alpha = 0.5f), Offset(x + 3 * unit, midY - unit), Size(7 * unit, 4 * unit), CornerRadius(2 * unit))
            drawBar(Offset(x + 12 * unit, midY), tabWidth - 18 * unit, color)
        }

        TabStripStyle.SOURCE_STRIPE -> {
            drawRect(THUMB_STRIPES[index % THUMB_STRIPES.size], Offset(x, top), Size(tabWidth, 3 * unit))
            drawBar(Offset(x + 4 * unit, midY + unit), tabWidth - 10 * unit, color)
        }

        TabStripStyle.TWO_LINE -> {
            drawBar(Offset(x + 4 * unit, midY - 3 * unit), tabWidth - 10 * unit, color)
            drawBar(Offset(x + 4 * unit, midY + 3 * unit), (tabWidth - 10 * unit) * 0.6f, color.copy(alpha = 0.5f))
        }

        else -> drawBar(Offset(x + 4 * unit, midY), tabWidth - 10 * unit, color)
    }
}
