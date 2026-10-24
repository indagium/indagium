package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.indagium.model.LogViewSectionId

// What LogViewer needs to emit its two tiles (Original and Log) into a TilingSurface instead of laying itself out as one Column;
// LogViewer(tiles = null) keeps that Column. The tiles' header content and the PID/TID rule live here so LogViewer.kt (already at
// its size limit) only has to call them.

/**
 * The tiling surface to emit into ([scope]) and which tile ids stand for this log: [logId] and [originalId] (LOG / ORIGINAL in the
 * single view; LOG_A / ORIGINAL_A and LOG_B / ORIGINAL_B in compare). [logHeaderLeading] adds content to the Log tile's toolbar
 * right after the move grip (compare's A/B badge and file name).
 */
internal class LogViewerTiles(
    val scope: TileScope<LogViewSectionId>,
    val logId: LogViewSectionId,
    val originalId: LogViewSectionId,
    val logHeaderLeading: (@Composable RowScope.() -> Unit)? = null,
) {
    /** Before the toolbar buttons: the Log tile's move grip (nothing while tiles are locked), then [logHeaderLeading]. */
    val toolbarLeading: @Composable RowScope.() -> Unit = {
        scope.Grip(logId)
        logHeaderLeading?.invoke(this)
    }

    /** After the toolbar buttons: the Log tile's maximize button. */
    val toolbarTrailing: @Composable RowScope.() -> Unit = {
        scope.MaximizeButton(logId)
        Spacer(Modifier.width(TOOLBAR_TRAILING_GAP_DP.dp))
    }

    /** The Log tile: headerless, because the log toolbar is its header ([toolbarLeading] / [toolbarTrailing] fill that bar). */
    fun logSlot(body: @Composable (TileBodyInfo) -> Unit): TileSlot =
        TileSlot(
            icon = Icons.Outlined.Description,
            title = logViewTileTitle(logId),
            hideable = false,
            headerless = true,
            body = body,
        )

    /**
     * The Original tile in the standard tile header: grip, a purple icon, "Original", its line count, then "Follow selection",
     * maximize and close. [followSelection] / [onToggleFollow] are the Settings > "link Original and Filtered selection and
     * scroll" switch; [onClose] closes Original (the toolbar's "Hide original"); [onReclaimFocus] gives the log list back the
     * keyboard focus a click here would take.
     */
    @Composable
    fun originalSlot(
        totalLines: Int,
        followSelection: Boolean,
        onToggleFollow: () -> Unit,
        onClose: () -> Unit,
        onReclaimFocus: () -> Unit,
        body: @Composable (TileBodyInfo) -> Unit,
    ): TileSlot {
        val tc = tc()
        return TileSlot(
            icon = Icons.Outlined.Description,
            title = logViewTileTitle(originalId),
            summary = originalSummary(totalLines),
            iconTint = tc.seq1,
            headerAction = {
                PillBtn(
                    "Follow selection",
                    active = followSelection,
                    modifier = Modifier.testTag(ORIGINAL_FOLLOW_TAG),
                    onClick = {
                        onToggleFollow()
                        onReclaimFocus()
                    },
                )
                scope.MaximizeButton(originalId)
                TileIconButton(Icons.Outlined.Close, "Close Original", "Hide original", ORIGINAL_CLOSE_TAG) {
                    onClose()
                    onReclaimFocus()
                }
            },
            hideable = false,
            body = body,
        )
    }
}

private const val TOOLBAR_TRAILING_GAP_DP = 4f

/** Test tag of the Original tile's "Follow selection" pill. */
internal const val ORIGINAL_FOLLOW_TAG = "original-follow-toggle"

/** Test tag of the Original tile's close button. */
internal const val ORIGINAL_CLOSE_TAG = "original-tile-close"

/**
 * Whether the PID/TID column headers show in a log pane [paneWidthDp] wide (tiled mode only; the legacy view always shows them
 * when the log has pids). Narrow panes keep their width for the message; a pane exactly [LOG_TILE_NARROW_DP] wide still shows them.
 */
internal fun pidTidColumnsVisible(hasPidTid: Boolean, paneWidthDp: Float): Boolean = hasPidTid && paneWidthDp >= LOG_TILE_NARROW_DP

/**
 * The legacy (non-tiled) shell of [LogViewer]: one Column holding the [toolbar] and then [content], which gets the modifier that
 * makes it fill what is left (`weight(1f)`); tiled, there is no shell (each tile brings its own toolbar) and [content] is emitted
 * straight into the tiling surface with a plain Modifier. Exists so LogViewer's body is the same lambda in both modes.
 */
@Composable
internal fun LogViewerFrame(
    tiled: Boolean,
    modifier: Modifier,
    background: Color,
    toolbar: @Composable () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    if (tiled) {
        content(Modifier)
    } else {
        Column(modifier.fillMaxSize().background(background)) {
            toolbar()
            content(Modifier.weight(1f))
        }
    }
}
