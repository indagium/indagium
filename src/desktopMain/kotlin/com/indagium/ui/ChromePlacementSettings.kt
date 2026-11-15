package com.indagium.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    ToolbarPlacement.WITH_TABS_END -> "With tabs · end · default"
    ToolbarPlacement.BESIDE_TABS -> "Beside tabs"
    ToolbarPlacement.OWN_EDGE -> "Own edge"
    ToolbarPlacement.IN_LAYOUT -> "In the log view layout"
}

internal fun toolbarAlignLabel(align: ToolbarAlign): String = when (align) {
    ToolbarAlign.START -> "Start"
    ToolbarAlign.CENTER -> "Centre"
    ToolbarAlign.END -> "End"
}

/** The descriptions of the Toolbar rows, shared with their search entries. */
internal const val TOOLBAR_PLACEMENT_HINT = "Where the toolbar sits. Own edge adds edge and alignment rows."
internal const val TOOLBAR_EDGE_HINT = "The window edge the toolbar is docked to."
internal const val TOOLBAR_ALIGN_HINT = "Where the buttons sit along a toolbar that has a bar of its own."
internal const val TOOLBAR_ORDER_HINT = "Whether the toolbar comes before or after the tabs on the edge they share."
internal const val TOOLBAR_LABELS_HINT = "Hides text on the main toolbar buttons, leaving only their icons."
internal const val TOOLBAR_LOCK_HINT = "Hides the grips that move them, so they can't be dragged by accident."

/** The two options of the Button labels control (the Toolbar group here, the setup assistant's Look step). */
internal val TOOLBAR_LABEL_OPTIONS = listOf("Icon and text", "Icons only")

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
 * The note after a placement's name in the picker (shown after a "·"): why it can't be chosen now, or what the In layout placement means;
 * null for the rest.
 */
internal fun toolbarPlacementNote(placement: ToolbarPlacement, position: TabStripPosition): String? = when {
    !toolbarPlacementAvailable(placement, position) -> "needs left or right tabs"
    placement == ToolbarPlacement.IN_LAYOUT -> "a bar where there are no tiles"
    else -> null
}

/** Test tag of the Placement dropdown's field; its options keep their `toolbar-placement-<PLACEMENT>` tags. */
internal const val TOOLBAR_PLACEMENT_TAG = "toolbar-placement"

/**
 * The Toolbar group of Settings › Tabs & toolbar: where the toolbar sits (with the Edge, Alignment and Order rows only the placements
 * that need them show), whether its buttons carry text, and the lock that hides the grips. The tab strip's own position is a row of the
 * Tabs group.
 */
@Composable
internal fun ChromePlacementSettings(state: AppState, onInteraction: () -> Unit = {}) {
    val settings = state.settings
    val arrangement = chromeArrangement(settings)
    SettingsGroup("Toolbar") {
        SettingsRow(
            "Placement",
            TOOLBAR_PLACEMENT_HINT,
            anchor = "Toolbar placement",
        ) {
            SettingsDropdown(
                options = ToolbarPlacement.entries,
                selected = selectedToolbarPlacement(settings),
                label = ::toolbarPlacementLabel,
                onSelect = { placement -> state.updateSettings { it.withToolbarPlacement(placement) } },
                testTag = TOOLBAR_PLACEMENT_TAG,
                minWidth = SETTINGS_FIELD_SHORT,
                optionTag = { placement -> "toolbar-placement-${placement.name}" },
                isEnabled = { placement -> toolbarPlacementAvailable(placement, settings.tabStripPosition) },
                optionNote = { placement -> toolbarPlacementNote(placement, settings.tabStripPosition) },
                onInteraction = onInteraction,
            )
        }
        if (arrangement.placement == ToolbarPlacement.OWN_EDGE) {
            SettingsRow("Edge", TOOLBAR_EDGE_HINT, anchor = "Toolbar edge", indent = true) {
                val edges = ChromeEdge.entries
                SettingsSegmented(
                    options = edges.map(::chromeEdgeLabel),
                    selectedIndices = setOf(edges.indexOf(settings.toolbarEdge)),
                    onToggle = { idx -> state.updateSettings { it.copy(toolbarEdge = edges[idx]) } },
                    modifier = Modifier.testTag("toolbar-edge"),
                )
            }
        }
        val ownBar = settings.toolbarPlacement == ToolbarPlacement.OWN_EDGE || settings.toolbarPlacement == ToolbarPlacement.BESIDE_TABS
        if (ownBar && arrangement.toolbarBarEdge != null) {
            SettingsRow("Alignment", TOOLBAR_ALIGN_HINT, anchor = "Toolbar alignment", indent = true) {
                val aligns = ToolbarAlign.entries
                SettingsSegmented(
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
                TOOLBAR_ORDER_HINT,
                anchor = "Toolbar order on the tab edge",
                indent = true,
            ) {
                SettingsSegmented(
                    options = listOf("Before tabs", "After tabs"),
                    selectedIndices = setOf(if (settings.toolbarOuter) 0 else 1),
                    onToggle = { idx -> state.updateSettings { it.copy(toolbarOuter = idx == 0) } },
                    modifier = Modifier.testTag("toolbar-order"),
                )
            }
        }
        SettingsRow("Button labels", TOOLBAR_LABELS_HINT, anchor = "Toolbar labels") {
            SettingsSegmented(
                options = TOOLBAR_LABEL_OPTIONS,
                selectedIndices = setOf(if (settings.toolbarIconOnlyButtons) 1 else 0),
                onToggle = { idx -> state.updateSettings { it.copy(toolbarIconOnlyButtons = idx == 1) } },
            )
        }
        SettingsRow("Lock tabs and toolbar", TOOLBAR_LOCK_HINT) {
            SettingsSwitch(
                checked = settings.chromeLocked,
                onCheckedChange = { next -> state.updateSettings { it.copy(chromeLocked = next) } },
                label = "Lock tabs and toolbar",
                testTag = "chrome-lock",
            )
        }
    }
}
