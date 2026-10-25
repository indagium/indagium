// The thumbnail drawing is a few dozen literal geometry numbers (dp-ish units of a 84 x 34 sketch), not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.indagium.model.TabLabelMode
import com.indagium.model.TabSizeMode
import com.indagium.model.TabStripStyle

// Settings > Appearance > Tabs: the strip's style as a grid of thumbnails, tab size, label and the optional details.
//
// Details rule: the file-type badge and the source colour stripe are two independent details any style can add. The TYPED style
// always draws the badge and the SOURCE_STRIPE style always draws the stripe (that is what they are), so while one of those styles is
// selected its checkbox is shown checked and locked; every other style shows the checkbox as stored.

/** The thumbnail caption of a style, with the letter of the design it came from. */
internal fun tabStyleLabel(style: TabStripStyle): String = when (style) {
    TabStripStyle.CURRENT -> "A Current"
    TabStripStyle.RAISED -> "B Raised"
    TabStripStyle.SEGMENTED -> "C Segmented"
    TabStripStyle.TYPED -> "D Typed"
    TabStripStyle.COMPACT -> "E Compact"
    TabStripStyle.SOURCE_STRIPE -> "F Source colour"
    TabStripStyle.TWO_LINE -> "G Two-line"
    TabStripStyle.QUIET -> "H Quiet"
}

internal fun tabLabelModeLabel(mode: TabLabelMode): String = when (mode) {
    TabLabelMode.FILE_SESSION -> "File (+ folder if same name)"
    TabLabelMode.SESSION_ONLY -> "Folder"
    TabLabelMode.FILE_ONLY -> "File only"
    TabLabelMode.FILE_FOLDER_ALWAYS -> "File — folder"
}

/** The hint under the Tab label control: what the default does, which is the one choice whose name does not say it all. */
internal const val TAB_LABEL_HINT: String = "The folder is added when two open tabs have the same file name."

/** Test tags of the Tab label control and its hint. */
internal const val TAB_LABEL_CONTROL_TAG = "tab-label-mode"
internal const val TAB_LABEL_HINT_TAG = "tab-label-hint"

private val THUMB_WIDTH = 84.dp
private val THUMB_HEIGHT = 34.dp

@Composable
internal fun TabsAppearanceSettings(state: AppState) {
    val tc = tc()
    val settings = state.settings
    Column(Modifier.settingsAnchor("Tab style"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText("Tabs", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        TabStyleGrid(settings.tabStripStyle) { style -> state.updateSettings { it.copy(tabStripStyle = style) } }
    }
    SettingsControlRow {
        CompactSetting("Tab size") {
            val modes = TabSizeMode.entries
            SegmentedControl(
                options = listOf("Fixed", "Fit label"),
                selectedIndices = setOf(modes.indexOf(settings.tabSizeMode)),
                onToggle = { idx -> state.updateSettings { it.copy(tabSizeMode = modes[idx]) } },
            )
        }
        CompactSetting("Tab label") {
            val modes = TabLabelMode.entries
            SegmentedControl(
                options = modes.map(::tabLabelModeLabel),
                selectedIndices = setOf(modes.indexOf(settings.tabLabelMode)),
                onToggle = { idx -> state.updateSettings { it.copy(tabLabelMode = modes[idx]) } },
                modifier = Modifier.testTag(TAB_LABEL_CONTROL_TAG),
                segmentFontSize = 11.sp,
                segmentHorizontalPadding = 8.dp,
            )
            AppText(TAB_LABEL_HINT, color = tc.td, fontSize = 10.sp, modifier = Modifier.testTag(TAB_LABEL_HINT_TAG))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        val typed = settings.tabStripStyle == TabStripStyle.TYPED
        val striped = settings.tabStripStyle == TabStripStyle.SOURCE_STRIPE
        DetailCheck("File-type badge", tabBadgeShown(settings), locked = typed) {
            state.updateSettings { it.copy(tabShowTypeBadge = !it.tabShowTypeBadge) }
        }
        DetailCheck("Source colour stripe", tabStripeShown(settings), locked = striped) {
            state.updateSettings { it.copy(tabShowSourceStripe = !it.tabShowSourceStripe) }
        }
        DetailCheck("Close button on every tab", settings.tabCloseOnEvery, locked = false) {
            state.updateSettings { it.copy(tabCloseOnEvery = !it.tabCloseOnEvery) }
        }
    }
    ChromePlacementSettings(state)
}

@Composable
private fun DetailCheck(label: String, checked: Boolean, locked: Boolean, onToggle: () -> Unit) {
    val tc = tc()
    CheckRow(
        modifier = Modifier.settingsAnchor(label).testTag("tab-detail-${label.lowercase().replace(Regex("[^a-z]+"), "-")}"),
        checked = checked,
        onToggle = onToggle,
        enabled = !locked,
    ) {
        AppText(label, color = tc.tx, fontSize = 11.sp)
        if (locked) AppText("(part of this style)", color = tc.td, fontSize = 10.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TabStyleGrid(selected: TabStripStyle, onSelect: (TabStripStyle) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TabStripStyle.entries.forEach { style -> TabStyleThumb(style, style == selected) { onSelect(style) } }
    }
}

@Composable
private fun TabStyleThumb(style: TabStripStyle, active: Boolean, onClick: () -> Unit) {
    val tc = tc()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            Modifier
                .size(THUMB_WIDTH, THUMB_HEIGHT)
                .clip(CORNER_MD)
                .background(if (active) tc.ac.copy(alpha = 0.12f) else Color.Transparent)
                .border(1.dp, if (active) tc.ac else tc.br, CORNER_MD)
                .testTag("tab-style-${style.name}")
                .semantics {
                    contentDescription = tabStyleLabel(style)
                    selected = active
                }
                .clickable(role = Role.Button, onClick = onClick)
                .padding(4.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) { drawTabThumb(style, tc, active) }
        }
        AppText(
            tabStyleLabel(style),
            color = if (active) tc.ac else tc.ts,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(THUMB_WIDTH),
        )
    }
}

private val THUMB_STRIPES = listOf(Color(0xFF60a5fa), Color(0xFFfb923c), Color(0xFFa78bfa))

// Three miniature tabs (the middle one active) in the look of [style].
private fun DrawScope.drawTabThumb(style: TabStripStyle, tc: ThemeColors, selected: Boolean) {
    val tab = tc.td.copy(alpha = if (selected) 0.9f else 0.7f)
    val dim = tc.td.copy(alpha = 0.45f)
    val unit = 1.dp.toPx()
    val tabWidth = size.width / 3f
    val full = size.height
    val height = if (style == TabStripStyle.COMPACT) full * 0.72f else full
    val top = full - height
    val accent = tc.ac
    repeat(3) { i ->
        val x = i * tabWidth
        val isActive = i == 1
        val inner = Offset(x + 4 * unit, top + height / 2f - unit)
        when (style) {
            TabStripStyle.RAISED -> {
                if (isActive) {
                    drawRoundRect(tc.br, Offset(x + unit, top + 2 * unit), Size(tabWidth - 2 * unit, height), CornerRadius(4 * unit))
                    drawRoundRect(tc.bg, Offset(x + 1.5f * unit, top + 3 * unit), Size(tabWidth - 3 * unit, height), CornerRadius(3.5f * unit))
                }
                drawBar(inner.copy(y = top + height / 2f + unit), tabWidth - 10 * unit, if (isActive) tab else dim)
            }

            TabStripStyle.SEGMENTED -> {
                if (isActive) {
                    drawRoundRect(tc.br, Offset(x + unit, top + 3 * unit), Size(tabWidth - 2 * unit, height - 6 * unit), CornerRadius(3 * unit))
                    drawRoundRect(tc.p, Offset(x + 1.5f * unit, top + 3.5f * unit), Size(tabWidth - 3 * unit, height - 7 * unit), CornerRadius(2.5f * unit))
                    drawCircle(accent, 1.5f * unit, Offset(x + 5 * unit, top + height / 2f))
                }
                drawBar(inner.copy(x = x + (if (isActive) 8 else 4) * unit), tabWidth - 14 * unit, if (isActive) tab else dim)
            }

            TabStripStyle.QUIET -> {
                drawBar(inner.copy(y = top + height / 2f - 3 * unit), tabWidth - 10 * unit, if (isActive) accent else dim)
                drawRect(if (isActive) accent else tc.br, Offset(x, full - 2 * unit), Size(tabWidth, 2 * unit))
            }

            else -> {
                drawRect(if (isActive) tc.bg else tc.p2, Offset(x, top), Size(tabWidth, height))
                drawRect(tc.br, Offset(x, top), Size(tabWidth, height), style = androidx.compose.ui.graphics.drawscope.Stroke(unit))
                if (isActive) drawRect(accent, Offset(x, full - 2 * unit), Size(tabWidth, 2 * unit))
                drawClassicDetails(style, tc, x, top, tabWidth, height, i, if (isActive) tab else dim)
            }
        }
    }
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
