@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

// The buttons of the log toolbar between the leading and trailing parts, in the shape [LogToolbarVariant] asks for. FULL is the legacy
// toolbar, moved here verbatim from LogViewer.kt so that file only decides *which* variant to draw. The pure rules are in
// LogToolbarLayout.kt.

private const val TOOLBAR_HEIGHT_DP = 34
private const val OVERFLOW_MENU_WIDTH_DP = 210

/** Test tag of the toolbar button for [id] (a [LogToolbarItem.id]): `log-toolbar-expand-all`, ... */
internal fun logToolbarTag(id: String): String = "log-toolbar-$id"

/** Test tag of the "⋯" button that opens the overflow menu. */
internal const val LOG_TOOLBAR_OVERFLOW_TAG = "log-toolbar-overflow"

/** Test tag of the overflow menu row for [id] (a [LogToolbarItem.id]). */
internal fun logToolbarOverflowItemTag(id: String): String = "log-toolbar-overflow-item-$id"

/** What the toolbar controls show. */
internal class LogToolbarView(
    val tiled: Boolean,
    val showTimeDelta: Boolean,
    val showUnfiltered: Boolean,
    val searchActive: Boolean,
    /** Null when the host has no filter bar toggle. */
    val filterBarVisible: Boolean?,
    val canExpandAll: Boolean,
    val canCollapseAll: Boolean,
    val countLabel: String,
    /** The [LogToolbarItem.id] under the keyboard cursor, if any. */
    val focusedId: String?,
    val exportMenuOpen: Boolean,
)

/** What the toolbar controls do; the callbacks are `LogViewer`'s own. */
internal class LogToolbarActions(
    /** Null when the host has no filter bar toggle. */
    val onToggleFilterBar: (() -> Unit)?,
    val onExportMenuOpenChange: (Boolean) -> Unit,
    val onExportTxt: () -> Unit,
    val onExportCsv: () -> Unit,
    val onExportCaptureRange: (() -> Unit)?,
    val onToggleTimeDelta: () -> Unit,
    val onOpenSearch: () -> Unit,
    val onExpandAll: () -> Unit,
    val onCollapseAll: () -> Unit,
    val onToggleUnfiltered: () -> Unit,
    /** Gives the log list the keyboard focus back (a dismissed popup or a clicked button takes it). */
    val onReclaimFocus: () -> Unit,
)

internal class LogToolbarModel(val view: LogToolbarView, val actions: LogToolbarActions) {
    val hasFilterBar: Boolean get() = view.filterBarVisible != null && actions.onToggleFilterBar != null
}

@Composable
internal fun RowScope.LogToolbarControls(variant: LogToolbarVariant, model: LogToolbarModel) {
    val tc = tc()
    val overflow = variant.overflow.toSet()

    fun ring(item: LogToolbarItem): Modifier =
        Modifier.testTag(logToolbarTag(item.id)).border(1.dp, if (model.view.focusedId == item.id) tc.ac else Color.Transparent, CORNER_MD)

    Box {
        AppButton(
            "Export ▾",
            onClick = { model.actions.onExportMenuOpenChange(true) },
            modifier = ring(LogToolbarItem.EXPORT),
        )
        if (model.view.exportMenuOpen) {
            ExportMenuPopup(
                onExportTxt = { model.actions.onExportMenuOpenChange(false); model.actions.onExportTxt() },
                onExportCsv = { model.actions.onExportMenuOpenChange(false); model.actions.onExportCsv() },
                onExportCaptureRange = model.actions.onExportCaptureRange?.let { callback ->
                    { model.actions.onExportMenuOpenChange(false); callback() }
                },
                onDismiss = { model.actions.onExportMenuOpenChange(false) },
                tc = tc,
            )
        }
    }
    if (model.hasFilterBar && LogToolbarItem.FILTER_BAR !in overflow) {
        Spacer(Modifier.width(LOG_TOOLBAR_GAP_DP.dp))
        FilterBarToolbarButton(
            visible = model.view.filterBarVisible == true,
            onToggle = { model.actions.onToggleFilterBar?.invoke() },
            modifier = Modifier
                .testTag("filter-bar-toolbar-toggle")
                .border(1.dp, if (model.view.focusedId == LogToolbarItem.FILTER_BAR.id) tc.ac else Color.Transparent, CORNER_MD),
        )
    }
    if (LogToolbarItem.TIME_DELTA !in overflow) {
        Spacer(Modifier.width(LOG_TOOLBAR_GAP_DP.dp))
        // Primary variant when on is this toolbar's only "active" state affordance (Unfiltered,
        // the other stateful toggle here, signals its state via label text instead — "Δt on/off"
        // has no comparably natural alternate label, so a filled/tinted button is the toggle cue).
        AppButton(
            "Δt",
            onClick = model.actions.onToggleTimeDelta,
            variant = if (model.view.showTimeDelta) ButtonVariant.Primary else ButtonVariant.Secondary,
            modifier = ring(LogToolbarItem.TIME_DELTA),
        )
    }
    Spacer(Modifier.width(LOG_TOOLBAR_GAP_DP.dp))
    AppButton(
        "",
        onClick = model.actions.onOpenSearch,
        variant = if (model.view.searchActive) ButtonVariant.Primary else ButtonVariant.Secondary,
        leadingIcon = Icons.Outlined.Search,
        horizontalPadding = 10.dp,
        modifier = ring(LogToolbarItem.SEARCH),
    )
    Spacer(Modifier.width(LOG_TOOLBAR_GAP_DP.dp))
    if (variant.showCount) {
        AppText(
            model.view.countLabel,
            color = tc.td,
            fontSize = 11.sp,
            fontFamily = MONO,
            overflow = if (model.view.tiled) TextOverflow.Ellipsis else TextOverflow.Clip,
            modifier = Modifier.weight(1f),
        )
    } else {
        // The count has no room: hovering the empty space shows it (the overflow menu repeats it).
        TooltipArea(tooltip = { ToolbarTooltip(model.view.countLabel) }, modifier = Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.fillMaxWidth().fillMaxHeight().testTag(LOG_TOOLBAR_COUNT_TAG))
        }
    }
    var emitted = false

    fun gap(): Boolean {
        val needed = emitted
        emitted = true
        return needed
    }
    if (LogToolbarItem.EXPAND_ALL !in overflow) {
        if (gap()) Spacer(Modifier.width(LOG_TOOLBAR_GAP_DP.dp))
        if (variant.iconLabels) {
            LogToolbarIconButton(
                Icons.Outlined.UnfoldMore, "Expand all", model.view.canExpandAll, false,
                ring(LogToolbarItem.EXPAND_ALL), model.actions.onExpandAll,
            )
        } else {
            AppButton("Expand all", onClick = model.actions.onExpandAll, enabled = model.view.canExpandAll, modifier = ring(LogToolbarItem.EXPAND_ALL))
        }
    }
    if (LogToolbarItem.COLLAPSE_ALL !in overflow) {
        if (gap()) Spacer(Modifier.width(LOG_TOOLBAR_GAP_DP.dp))
        if (variant.iconLabels) {
            LogToolbarIconButton(
                Icons.Outlined.UnfoldLess, "Collapse all", model.view.canCollapseAll, false,
                ring(LogToolbarItem.COLLAPSE_ALL), model.actions.onCollapseAll,
            )
        } else {
            AppButton("Collapse all", onClick = model.actions.onCollapseAll, enabled = model.view.canCollapseAll, modifier = ring(LogToolbarItem.COLLAPSE_ALL))
        }
    }
    if (LogToolbarItem.UNFILTERED !in overflow) {
        if (gap()) Spacer(Modifier.width(LOG_TOOLBAR_GAP_DP.dp))
        if (variant.iconLabels) {
            LogToolbarIconButton(
                Icons.Outlined.Description, unfilteredLabel(model.view.showUnfiltered), true, model.view.showUnfiltered,
                ring(LogToolbarItem.UNFILTERED), model.actions.onToggleUnfiltered,
            )
        } else {
            AppButton(
                if (model.view.showUnfiltered) "Hide original" else "Unfiltered",
                onClick = model.actions.onToggleUnfiltered,
                modifier = ring(LogToolbarItem.UNFILTERED),
            )
        }
    }
    if (variant.hasOverflow) {
        if (gap()) Spacer(Modifier.width(LOG_TOOLBAR_GAP_DP.dp))
        LogToolbarOverflow(variant.overflow, model)
    }
}

/** Test tag of the empty toolbar space that carries the count tooltip when the count has no room. */
internal const val LOG_TOOLBAR_COUNT_TAG = "log-toolbar-count-space"

private fun unfilteredLabel(showUnfiltered: Boolean): String = if (showUnfiltered) "Hide original" else "Show unfiltered original"

@Composable
private fun LogToolbarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    AppButton(
        "",
        onClick = onClick,
        enabled = enabled,
        variant = if (active) ButtonVariant.Primary else ButtonVariant.Secondary,
        leadingIcon = icon,
        horizontalPadding = 10.dp,
        tooltip = label,
        modifier = modifier.semantics { contentDescription = label },
    )
}

/** The "⋯" button and its menu: the [items] that did not fit the bar, in toolbar order, behind the same Popup treatment as Export. */
@Composable
private fun LogToolbarOverflow(items: List<LogToolbarItem>, model: LogToolbarModel) {
    val tc = tc()
    var open by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density
    val focusInside = model.view.focusedId != null && items.any { it.id == model.view.focusedId }
    Box {
        AppButton(
            "",
            onClick = { open = !open },
            variant = if (open) ButtonVariant.Primary else ButtonVariant.Secondary,
            leadingIcon = Icons.Outlined.MoreHoriz,
            horizontalPadding = 10.dp,
            tooltip = "More actions",
            modifier = Modifier
                .testTag(LOG_TOOLBAR_OVERFLOW_TAG)
                .semantics { contentDescription = "More actions" }
                .border(1.dp, if (focusInside) tc.ac else Color.Transparent, CORNER_MD),
        )
        if (open) {
            val dismiss = { open = false; model.actions.onReclaimFocus() }
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, (TOOLBAR_HEIGHT_DP * density).roundToInt()),
                onDismissRequest = dismiss,
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(OVERFLOW_MENU_WIDTH_DP.dp)
                        .background(tc.p, RoundedCornerShape(7.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                        .padding(vertical = 4.dp),
                ) {
                    // The menu only exists for the overflow variants, which never have room for the count.
                    AppText(
                        model.view.countLabel,
                        color = tc.td,
                        fontSize = 11.sp,
                        fontFamily = MONO,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag(LOG_TOOLBAR_OVERFLOW_COUNT_TAG),
                    )
                    Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
                    items.forEach { item -> OverflowRow(item, model, dismiss) }
                }
            }
        }
    }
}

/** Test tag of the entries count line at the top of the overflow menu. */
internal const val LOG_TOOLBAR_OVERFLOW_COUNT_TAG = "log-toolbar-overflow-count"

@Composable
private fun OverflowRow(item: LogToolbarItem, model: LogToolbarModel, dismiss: () -> Unit) {
    val tc = tc()
    val label: String
    val enabled: Boolean
    val on: Boolean
    val action: () -> Unit
    when (item) {
        LogToolbarItem.EXPORT -> { label = "Export"; enabled = true; on = false; action = { model.actions.onExportMenuOpenChange(true) } }
        LogToolbarItem.FILTER_BAR -> {
            label = if (model.view.filterBarVisible == true) "Hide filter bar" else "Show filter bar"
            enabled = true; on = model.view.filterBarVisible == true; action = { model.actions.onToggleFilterBar?.invoke() }
        }
        LogToolbarItem.TIME_DELTA -> { label = "Time delta (Δt)"; enabled = true; on = model.view.showTimeDelta; action = model.actions.onToggleTimeDelta }
        LogToolbarItem.SEARCH -> { label = "Search"; enabled = true; on = model.view.searchActive; action = model.actions.onOpenSearch }
        LogToolbarItem.EXPAND_ALL -> { label = "Expand all"; enabled = model.view.canExpandAll; on = false; action = model.actions.onExpandAll }
        LogToolbarItem.COLLAPSE_ALL -> { label = "Collapse all"; enabled = model.view.canCollapseAll; on = false; action = model.actions.onCollapseAll }
        LogToolbarItem.UNFILTERED -> {
            label = unfilteredLabel(model.view.showUnfiltered)
            enabled = true; on = model.view.showUnfiltered; action = model.actions.onToggleUnfiltered
        }
    }
    HoverBox(
        modifier = Modifier.fillMaxWidth().testTag(logToolbarOverflowItemTag(item.id)),
        hoverEnabled = enabled,
        onClick = if (enabled) ({ dismiss(); action() }) else null,
    ) {
        AppText(
            label,
            color = when {
                !enabled -> tc.td.copy(.5f)
                on -> tc.ac
                else -> tc.tx
            },
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
