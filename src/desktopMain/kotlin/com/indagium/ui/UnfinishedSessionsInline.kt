package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.indagium.capture.CaptureSession

/** Fallback cap for the inline list (dp); a tall Capture card can pass a larger measured viewport. */
internal const val HOME_UNFINISHED_LIST_MAX_HEIGHT_DP = 320
internal const val HOME_UNFINISHED_PANEL_CHROME_HEIGHT_DP = 84

/** List height that fills [panelHeightDp] after the panel's header, padding, and selection controls. */
internal fun homeUnfinishedListMinHeightDp(panelHeightDp: Int): Int =
    (panelHeightDp - HOME_UNFINISHED_PANEL_CHROME_HEIGHT_DP).coerceAtLeast(0)

private const val WINDOW_DIALOG_WIDTH_DP = 640
private const val WINDOW_DIALOG_MAX_HEIGHT_DP = 560

/**
 * The Capture card's "Unfinished sessions · N" block: today's full [UnfinishedSessionsSection] (select all, delete selected, and per
 * row Open / Save ZIP / Save Compact / Open folder / Discard) inline, with the list filling its available viewport and scrolling when
 * needed. The "Open in window…" header link shows the same panel in a dialog as an additional place to manage them. The count and
 * rows read the same [retained] snapshot ([UnfinishedCaptureSessionsPanel]).
 */
@Composable
internal fun UnfinishedSessionsInline(
    state: AppState,
    retained: List<CaptureSession>,
    onReclaimFocus: () -> Unit,
    minPanelHeightDp: Int = 0,
    listMinHeightDp: Int = 0,
    listMaxHeightDp: Int = HOME_UNFINISHED_LIST_MAX_HEIGHT_DP,
) {
    var windowOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("unfinished-inline")) {
        UnfinishedCaptureSessionsPanel(
            retained = retained,
            modifier = Modifier.heightIn(min = minPanelHeightDp.coerceAtLeast(0).dp),
            headerAction = if (retained.isEmpty()) {
                null
            } else {
                { AccentLink("Open in window…", { windowOpen = true }, Modifier.testTag("unfinished-open-window")) }
            },
        ) { sessions ->
            UnfinishedSessionsSection(
                state = state,
                retained = sessions,
                listMaxHeightDp = maxOf(listMaxHeightDp, listMinHeightDp),
                listMinHeightDp = listMinHeightDp,
            )
        }
    }
    if (windowOpen) {
        UnfinishedSessionsWindowDialog(
            state = state,
            retained = retained,
            onDismiss = {
                windowOpen = false
                onReclaimFocus()
            },
        )
    }
}

/** "Open in window…": the same panel in a dialog (wider than the platform default, see CLAUDE.md's Dialog width gotcha). */
@Composable
private fun UnfinishedSessionsWindowDialog(state: AppState, retained: List<CaptureSession>, onDismiss: () -> Unit) {
    val colors = tc()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(WINDOW_DIALOG_WIDTH_DP.dp).heightIn(max = WINDOW_DIALOG_MAX_HEIGHT_DP.dp)
                .background(colors.p, RoundedCornerShape(8.dp))
                .border(1.dp, colors.br, RoundedCornerShape(8.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScrollColumnWithBar(Modifier.weight(1f, fill = false), endGutter = 0.dp) {
                UnfinishedCaptureSessionsPanel(retained) { sessions ->
                    UnfinishedSessionsSection(state = state, retained = sessions)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogActionButton("Done", active = true, onClick = onDismiss)
            }
        }
    }
}
