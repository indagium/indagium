package com.indagium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.AppSettings
import com.indagium.model.ChromeEdge
import com.indagium.model.TabStripPosition
import com.indagium.model.ToolbarAlign
import com.indagium.model.ToolbarPlacement

// Settings > Tabs & toolbar, the toolbar and placement half: which window edge the tab strip is on, where the toolbar sits relative to it (and,
// docked to an edge of its own, which edge), and whether the grips that drag them are shown. The same values the grips write
// (ui/ChromeLayout.kt applyChromeDock), so the dialog and a drag always agree. Beside tabs is offered only with a vertical tab strip.

internal fun tabPositionLabel(position: TabStripPosition): String = when (position) {
    TabStripPosition.TOP -> "Top"
    TabStripPosition.LEFT -> "Left"
    TabStripPosition.RIGHT -> "Right"
    TabStripPosition.BOTTOM -> "Bottom"
}

internal fun chromeEdgeLabel(edge: ChromeEdge): String = edge.name.lowercase().replaceFirstChar { it.uppercase() }

internal fun toolbarPlacementLabel(placement: ToolbarPlacement): String = when (placement) {
    ToolbarPlacement.WITH_TABS_START -> "With tabs · start"
    ToolbarPlacement.WITH_TABS_END -> "With tabs · end (default)"
    ToolbarPlacement.BESIDE_TABS -> "Beside tabs"
    ToolbarPlacement.OWN_EDGE -> "Own edge"
    ToolbarPlacement.IN_LAYOUT -> "In the log view layout"
}

internal fun toolbarAlignLabel(align: ToolbarAlign): String = when (align) {
    ToolbarAlign.START -> "Start"
    ToolbarAlign.CENTER -> "Center"
    ToolbarAlign.END -> "End"
}

/** The radio that shows as chosen for [settings]: the stored placement, Beside tabs reading as the end of the strip's bar with a horizontal strip. */
internal fun selectedToolbarPlacement(settings: AppSettings): ToolbarPlacement =
    if (settings.toolbarPlacement == ToolbarPlacement.BESIDE_TABS && !settings.tabStripPosition.toEdge().isVertical) {
        ToolbarPlacement.WITH_TABS_END
    } else {
        settings.toolbarPlacement
    }

/** True when [placement] can be chosen with the tab strip on [position]: only Beside tabs depends on it (it needs a vertical strip). */
internal fun toolbarPlacementAvailable(placement: ToolbarPlacement, position: TabStripPosition): Boolean =
    placement != ToolbarPlacement.BESIDE_TABS || position.toEdge().isVertical

/**
 * The Toolbar group of Settings › Tabs & toolbar: where the toolbar sits (with the Edge, Alignment and Order rows only the placements
 * that need them show), whether its buttons carry text, and the lock that hides the grips. The tab strip's own position is a row of the
 * Tabs group.
 */
@Composable
internal fun ChromePlacementSettings(state: AppState) {
    val tc = tc()
    val settings = state.settings
    val arrangement = chromeArrangement(settings)
    SettingsGroup("Toolbar") {
        SettingsStackedRow(
            "Placement",
            "Where the toolbar sits. Edge and alignment rows appear below for the placements that need them.",
            anchor = "Toolbar placement",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                ToolbarPlacement.entries.forEach { placement ->
                    val available = toolbarPlacementAvailable(placement, settings.tabStripPosition)
                    RadioRow(
                        selected = selectedToolbarPlacement(settings) == placement,
                        onSelect = { state.updateSettings { it.withToolbarPlacement(placement) } },
                        modifier = Modifier.testTag("toolbar-placement-${placement.name}"),
                        enabled = available,
                    ) {
                        AppText(toolbarPlacementLabel(placement), color = if (available) tc.tx else tc.td, fontSize = 11.sp)
                        if (!available) AppText("(needs left or right tabs)", color = tc.td, fontSize = 10.sp)
                        if (placement == ToolbarPlacement.IN_LAYOUT) AppText("(a bar where there are no tiles)", color = tc.td, fontSize = 10.sp)
                    }
                }
            }
        }
        if (arrangement.placement == ToolbarPlacement.OWN_EDGE) {
            SettingsRow("Edge", "The window edge the toolbar is docked to.", anchor = "Toolbar edge", indent = true) {
                val edges = ChromeEdge.entries
                SegmentedControl(
                    options = edges.map(::chromeEdgeLabel),
                    selectedIndices = setOf(edges.indexOf(settings.toolbarEdge)),
                    onToggle = { idx -> state.updateSettings { it.copy(toolbarEdge = edges[idx]) } },
                    modifier = Modifier.testTag("toolbar-edge"),
                )
            }
        }
        val ownBar = settings.toolbarPlacement == ToolbarPlacement.OWN_EDGE || settings.toolbarPlacement == ToolbarPlacement.BESIDE_TABS
        if (ownBar && arrangement.toolbarBarEdge != null) {
            SettingsRow("Alignment", "Where the buttons sit along a toolbar that has a bar of its own.", anchor = "Toolbar alignment", indent = true) {
                val aligns = ToolbarAlign.entries
                SegmentedControl(
                    options = aligns.map(::toolbarAlignLabel),
                    selectedIndices = setOf(aligns.indexOf(settings.toolbarAlign)),
                    onToggle = { idx -> state.updateSettings { it.copy(toolbarAlign = aligns[idx]) } },
                    modifier = Modifier.testTag("toolbar-align"),
                )
            }
        }
        if (ownBar && arrangement.toolbarSharesTabEdge && arrangement.placement == ToolbarPlacement.OWN_EDGE) {
            SettingsRow(
                "Order on the tab edge",
                "Whether the toolbar comes before or after the tabs on the edge they share.",
                anchor = "Toolbar order on the tab edge",
                indent = true,
            ) {
                SegmentedControl(
                    options = listOf("Before tabs", "After tabs"),
                    selectedIndices = setOf(if (settings.toolbarOuter) 0 else 1),
                    onToggle = { idx -> state.updateSettings { it.copy(toolbarOuter = idx == 0) } },
                    modifier = Modifier.testTag("toolbar-order"),
                )
            }
        }
        SettingsRow("Button labels", "Hides text on the main toolbar buttons, leaving only their icons.", anchor = "Toolbar labels") {
            SegmentedControl(
                options = listOf("Show", "Icons only"),
                selectedIndices = setOf(if (settings.toolbarIconOnlyButtons) 1 else 0),
                onToggle = { idx -> state.updateSettings { it.copy(toolbarIconOnlyButtons = idx == 1) } },
            )
        }
        SettingsRow("Lock tabs and toolbar", "Hides the grips that move them, so they can't be dragged by accident.") {
            SettingsSwitch(
                checked = settings.chromeLocked,
                onCheckedChange = { next -> state.updateSettings { it.copy(chromeLocked = next) } },
                label = "Lock tabs and toolbar",
                testTag = "chrome-lock",
            )
        }
    }
}
