package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.indagium.capture.CaptureExportFormat
import com.indagium.capture.CaptureSession
import kotlin.math.roundToInt

/** Retained sessions listed inline in the New tab's Capture card; the rest sit behind "Manage all…". */
internal const val HOME_UNFINISHED_ROWS = 3

private const val MANAGE_DIALOG_WIDTH_DP = 640
private const val MANAGE_DIALOG_MAX_HEIGHT_DP = 560
private const val SESSION_MENU_WIDTH_DP = 150
private const val MORE_BUTTON_SIZE_DP = 28

/** The Capture card's compact "Unfinished sessions · N" block: the first [HOME_UNFINISHED_ROWS] rows (Open + a ⋯ menu)
 *  and a "Manage all…" link opening today's full list — [UnfinishedCaptureSessionsPanel] + [UnfinishedSessionsSection]
 *  (select all, bulk delete) — in a dialog. The count and the rows read the same [retained] snapshot. */
@Composable
internal fun UnfinishedSessionsCompact(
    state: AppState,
    retained: List<CaptureSession>,
    onReclaimFocus: () -> Unit,
) {
    val colors = tc()
    var manageOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("unfinished-compact"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppText("Unfinished sessions · ${retained.size}", color = colors.ts, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (retained.isNotEmpty()) {
                AccentLink("Manage all…", { manageOpen = true }, Modifier.testTag("unfinished-manage-all"))
            }
        }
        UnfinishedOrphanExportStatus(state, retained.map { it.id }.toSet())
        if (retained.isEmpty()) {
            AppText("No interrupted sessions", color = colors.td)
        } else {
            retained.take(HOME_UNFINISHED_ROWS).forEach { session ->
                UnfinishedCompactRow(state, session, onReclaimFocus)
            }
        }
    }
    if (manageOpen) {
        UnfinishedSessionsManageDialog(
            state = state,
            retained = retained,
            onDismiss = {
                manageOpen = false
                onReclaimFocus()
            },
        )
    }
}

/** "Manage all…": the unchanged full list in a dialog (wider than the platform default, see CLAUDE.md's Dialog width gotcha). */
@Composable
private fun UnfinishedSessionsManageDialog(state: AppState, retained: List<CaptureSession>, onDismiss: () -> Unit) {
    val colors = tc()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(MANAGE_DIALOG_WIDTH_DP.dp).heightIn(max = MANAGE_DIALOG_MAX_HEIGHT_DP.dp)
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

/** One compact retained-session row: model · status, directory name (mono, dim), Open and a ⋯ menu (Save ZIP, Save
 *  Compact, Open folder, Discard…) with the export notice for this session under it. Discard asks first. */
@Composable
private fun UnfinishedCompactRow(state: AppState, session: CaptureSession, onReclaimFocus: () -> Unit) {
    val colors = tc()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val exportStatus = captureExportStatusFor(
        session.id, state.captureExportOwner, state.captureExportBusy, state.captureExportResult, state.captureExportError,
        state.captureExportWarning, state.captureExportCancelled,
    )
    Column(Modifier.fillMaxWidth().testTag("unfinished-row-${session.id}")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                AppText("${session.device.model} · ${session.status}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                AppText(
                    session.directory.name, color = colors.td, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            AppButton("Open", { state.openRetainedCapture(session.id); onReclaimFocus() }, ButtonVariant.Secondary)
            Box {
                HoverBox(
                    modifier = Modifier.size(MORE_BUTTON_SIZE_DP.dp).clip(CORNER_MD).border(1.dp, colors.br, CORNER_MD)
                        .testTag("unfinished-more-${session.id}"),
                    onClick = { menuOpen = !menuOpen },
                ) {
                    Box(Modifier.size(MORE_BUTTON_SIZE_DP.dp), contentAlignment = Alignment.Center) {
                        AppText("⋯", color = colors.ts, fontSize = 14.sp)
                    }
                }
                if (menuOpen) {
                    UnfinishedSessionMenu(
                        state = state,
                        session = session,
                        saving = exportStatus.busy,
                        onDiscard = { confirmDiscard = true },
                        onClose = {
                            menuOpen = false
                            onReclaimFocus()
                        },
                    )
                }
            }
        }
        CaptureExportNotice(
            result = exportStatus.result,
            error = exportStatus.error,
            onClose = { state.clearCaptureExportStatus() },
            onReclaimFocus = onReclaimFocus,
            warning = exportStatus.warning,
            cancelled = exportStatus.cancelled,
        )
    }
    if (confirmDiscard) {
        ConfirmDeleteSessionsDialog(
            selectedCount = 1,
            requiresTyped = false,
            onConfirm = {
                state.discardRetainedCapture(session.id)
                confirmDiscard = false
                onReclaimFocus()
            },
            onDismiss = {
                confirmDiscard = false
                onReclaimFocus()
            },
        )
    }
}

@Composable
private fun UnfinishedSessionMenu(
    state: AppState,
    session: CaptureSession,
    saving: Boolean,
    onDiscard: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = tc()
    val density = LocalDensity.current.density
    val canSave = !state.captureExportBusy
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, (MORE_BUTTON_SIZE_DP * density).roundToInt()),
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(SESSION_MENU_WIDTH_DP.dp)
                .background(colors.p, RoundedCornerShape(7.dp))
                .border(1.dp, colors.br, RoundedCornerShape(7.dp))
                .padding(vertical = 4.dp),
        ) {
            SessionMenuItem(if (saving) "Saving…" else "Save ZIP", enabled = canSave) {
                state.saveRetainedCapture(session.id, format = CaptureExportFormat.ZIP)
                onClose()
            }
            SessionMenuItem(if (saving) "Saving…" else "Save Compact", enabled = canSave) {
                state.saveRetainedCapture(session.id, format = CaptureExportFormat.COMPACT)
                onClose()
            }
            SessionMenuItem("Open folder") {
                state.openRetainedCaptureFolder(session.id)
                onClose()
            }
            SessionMenuItem("Discard…", danger = true) {
                onDiscard()
                onClose()
            }
        }
    }
}

@Composable
private fun SessionMenuItem(label: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val colors = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth(),
        hoverEnabled = enabled,
        onClick = if (enabled) onClick else null,
    ) {
        AppText(
            label,
            color = when {
                !enabled -> colors.td
                danger -> DANGER_RED
                else -> colors.tx
            },
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
