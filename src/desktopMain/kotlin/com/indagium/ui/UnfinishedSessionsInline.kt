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

/** Tallest the inline sessions list grows (dp) in the New tab's Capture card before it scrolls on its own. */
internal const val HOME_UNFINISHED_LIST_MAX_HEIGHT_DP = 320

private const val WINDOW_DIALOG_WIDTH_DP = 640
private const val WINDOW_DIALOG_MAX_HEIGHT_DP = 560

/**
 * The Capture card's "Unfinished sessions · N" block: today's full [UnfinishedSessionsSection] (select all, delete selected, and per
 * row Open / Save ZIP / Save Compact / Open folder / Discard) inline, its rows scrolling inside [HOME_UNFINISHED_LIST_MAX_HEIGHT_DP],
 * plus an "Open in window…" link in the header that shows the same panel in a dialog as an additional place to manage them. The count
 * and the rows read the same [retained] snapshot ([UnfinishedCaptureSessionsPanel]).
 */
@Composable
internal fun UnfinishedSessionsInline(
    state: AppState,
    retained: List<CaptureSession>,
    onReclaimFocus: () -> Unit,
) {
    var windowOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("unfinished-inline")) {
        UnfinishedCaptureSessionsPanel(
            retained = retained,
            headerAction = if (retained.isEmpty()) {
                null
            } else {
                { AccentLink("Open in window…", { windowOpen = true }, Modifier.testTag("unfinished-open-window")) }
            },
        ) { sessions ->
            UnfinishedSessionsSection(state = state, retained = sessions, listMaxHeightDp = HOME_UNFINISHED_LIST_MAX_HEIGHT_DP)
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
