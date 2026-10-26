package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.indagium.model.AppSettings
import com.indagium.model.LogViewSectionId
import com.indagium.model.TileNode
import com.indagium.model.ToolbarPlacement

// The main toolbar as a tile of the log view's layout (ToolbarPlacement.IN_LAYOUT): a slim, movable, resizable tile with no header, hide,
// collapse or maximize (the toolbar must always be reachable). This file holds the tile, the settings changes that move the toolbar
// between the window edges and the layout, and the bridge through which the window chrome (ChromeScaffold) and the log view's tiling
// surface (LogViewTiling.kt) let a drag of the toolbar cross from one to the other.

/** Height (dp) of the toolbar's card in the narrow, stacked log view (a wrapping row of buttons: two lines). */
internal const val TOOLBAR_STACKED_CARD_DP: Float = 72f

// A vertical toolbar tile shows labels only when it is at least this wide.
private const val TOOLBAR_TILE_LABELS_MIN_DP = 100f
private const val TOOLBAR_TILE_PAD_DP = 3
private const val TOOLBAR_TILE_GRIP_DP = 22

/**
 * What lets a drag of the toolbar cross between the window chrome and the log view's tiles. Session-only state, created by the scaffold
 * and read through [LocalToolbarDock]. [external] is the tile that the chrome's toolbar bar is dragging over the tiles; [tileDragPointerRoot]
 * the pointer (window root px) of a drag of the toolbar TILE, which the chrome shows its dock strips for.
 */
internal class ToolbarDockBridge {
    val external = TileExternalDrag(LogViewSectionId.TOOLBAR)
    var tileDragPointerRoot by mutableStateOf<Offset?>(null)

    /** Set by the scaffold: the dock target under a window root px point while the toolbar is dragged, or null. */
    var zoneAt: ((Offset) -> ChromeDockTarget?)? = null

    /** Set by the scaffold: called when the toolbar tile is released at a root px point; true when it docked the toolbar to an edge. */
    var tileRelease: ((Offset) -> Boolean)? = null

    /** Set by the log view's tiling host: drops the toolbar into the layout at [target]. */
    var dropInLayout: ((TileDropTarget<LogViewSectionId>) -> Unit)? = null

    /** True when [rootPx] is over one of the chrome's dock strips (so the tiles show no drop target of their own there). */
    fun overChromeZone(rootPx: Offset): Boolean = zoneAt?.invoke(rootPx) != null
}

internal val LocalToolbarDock = staticCompositionLocalOf<ToolbarDockBridge?> { null }

/** Where the Recent files button is on screen, for the menu that opens next to it; provided by the scaffold for the toolbar tile too. */
internal val LocalChromeAnchors = staticCompositionLocalOf<ChromeAnchors?> { null }

/**
 * True when the toolbar is a tile in the layout of the view on screen: it is stored IN_LAYOUT and the active surface is a single log view
 * with nothing maximized (a maximized tile covers the toolbar tile, so the chrome shows the fallback bar meanwhile). Everywhere else
 * (the New tab, compare, diagrams, no tabs) the scaffold shows the toolbar as a bar at the fallback placement.
 */
internal fun AppState.toolbarHostedInLayout(): Boolean =
    settings.toolbarPlacement == ToolbarPlacement.IN_LAYOUT && !compareMode && logViewMaximized == null && layoutMenuTab(this) != null

/**
 * The toolbar placement chosen in Settings (or by code): IN_LAYOUT remembers the placement it replaces as the fallback and puts the tile
 * into the stored layout; any other placement takes the tile out of it again. Choosing the current placement changes nothing.
 */
internal fun AppSettings.withToolbarPlacement(placement: ToolbarPlacement): AppSettings {
    if (placement == toolbarPlacement) return this
    val next = if (placement == ToolbarPlacement.IN_LAYOUT) {
        copy(toolbarPlacement = placement, toolbarFallbackPlacement = toolbarPlacement)
    } else {
        copy(toolbarPlacement = placement)
    }
    return next.logViewLayout?.let { next.withLogViewLayout(it) } ?: next
}

/**
 * The toolbar dropped into the layout: [tree] is the layout with the tile placed (by dropTile); the toolbar becomes IN_LAYOUT (the
 * placement it had is the fallback), the tile is pinned slim and the layout stored sanitized.
 */
internal fun AppSettings.withToolbarDroppedInLayout(tree: TileNode<LogViewSectionId>): AppSettings {
    val base = if (toolbarPlacement == ToolbarPlacement.IN_LAYOUT) {
        this
    } else {
        copy(toolbarPlacement = ToolbarPlacement.IN_LAYOUT, toolbarFallbackPlacement = toolbarPlacement)
    }
    return base.withLogViewLayout(pinToolbarTile(tree))
}

/** The toolbar tile: emitted first by the log view (it is a no-op unless the tile is visible). */
@Composable
internal fun TileScope<LogViewSectionId>.ToolbarTile(state: AppState, onReclaimFocus: () -> Unit) {
    val anchors = LocalChromeAnchors.current
    val scope = this
    Tile(
        LogViewSectionId.TOOLBAR,
        TileSlot(
            icon = Icons.Outlined.Dashboard,
            title = logViewTileTitle(LogViewSectionId.TOOLBAR),
            headerless = true,
            hideable = false,
            // In a scrolling column (the filter column, say) the tile is exactly as tall as its wrapped rows of buttons, with no handle.
            sizing = TileBodySizing.CONTENT,
            body = { info -> ToolbarTileBody(state, info, scope, onReclaimFocus, anchors) },
        ),
    )
}

// A tile wider than tall is a wrapping row with the grip before it; a tile narrower than tall is a column with the grip above it. A tile sized
// by its content (in a scrolling column) has no height to compare with its width: it is always the wrapping row, as tall as its rows.
@Composable
private fun ToolbarTileBody(
    state: AppState,
    info: TileBodyInfo,
    scope: TileScope<LogViewSectionId>,
    onReclaimFocus: () -> Unit,
    anchors: ChromeAnchors?,
) {
    val vertical = !info.contentSized && info.heightDp > info.widthDp
    val onRecentAnchor: (TileRect) -> Unit = { anchors?.recentButton = it }
    if (vertical) {
        Column(Modifier.fillMaxSize().padding(TOOLBAR_TILE_PAD_DP.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(TOOLBAR_TILE_GRIP_DP.dp)) { scope.Grip(LogViewSectionId.TOOLBAR) }
            MainToolbar(
                state = state,
                orientation = ChromeOrientation.VERTICAL,
                onReclaimFocus = onReclaimFocus,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onRecentAnchor = onRecentAnchor,
                popupsBeside = true,
                showLabels = !state.settings.toolbarIconOnlyButtons && info.widthDp >= TOOLBAR_TILE_LABELS_MIN_DP,
            )
        }
    } else {
        Row(Modifier.then(if (info.contentSized) Modifier.fillMaxWidth() else Modifier.fillMaxSize()).padding(TOOLBAR_TILE_PAD_DP.dp)) {
            scope.Grip(LogViewSectionId.TOOLBAR)
            MainToolbar(
                state = state,
                orientation = ChromeOrientation.HORIZONTAL,
                onReclaimFocus = onReclaimFocus,
                modifier = Modifier.weight(1f).then(if (info.contentSized) Modifier else Modifier.fillMaxHeight()),
                onRecentAnchor = onRecentAnchor,
                wrap = true,
                popupsBeside = false,
                scrolls = !info.contentSized,
            )
        }
    }
}
