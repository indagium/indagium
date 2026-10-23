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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

private const val OPTIONS_DIALOG_WIDTH_DP = 640
private const val OPTIONS_DIALOG_MAX_HEIGHT_DP = 620

/** "Recording options": the pre-start capture controls of the New tab's Capture card in a dialog. Wider than the platform
 *  default dialog width (see CLAUDE.md's `Dialog` width gotcha), so `usePlatformDefaultWidth = false`. */
@Composable
internal fun CaptureOptionsDialog(
    state: AppState,
    launcherTabId: String,
    deviceSerial: String?,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CaptureOptionsDialogContent(state, launcherTabId, deviceSerial, onDismiss)
    }
}

/** The dialog's card, split from the [Dialog] window so UI tests can render it directly. Hosts [CaptureStartOptions] with
 *  the same arguments the old inline "Before start" row passed; edits go to the saved capture settings like before. */
@Composable
internal fun CaptureOptionsDialogContent(
    state: AppState,
    launcherTabId: String,
    deviceSerial: String?,
    onDismiss: () -> Unit,
) {
    val colors = tc()
    val draft = state.captureLaunchSettings(launcherTabId)
    val edit: CaptureSettingsEdit = { transform -> state.updateCaptureLaunchSettings(launcherTabId, transform) }
    Column(
        Modifier.width(OPTIONS_DIALOG_WIDTH_DP.dp).heightIn(max = OPTIONS_DIALOG_MAX_HEIGHT_DP.dp)
            .background(colors.p, RoundedCornerShape(8.dp))
            .border(1.dp, colors.br, RoundedCornerShape(8.dp))
            .padding(20.dp)
            .testTag("capture-options-dialog"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppText("Recording options", color = colors.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        ScrollColumnWithBar(Modifier.weight(1f, fill = false), endGutter = 0.dp) {
            CaptureStartOptions(
                settings = draft,
                onReclaimFocus = {},
                nativeMediaSupport = state.captureNativeMediaSupport,
                scrcpyAvailable = state.captureToolResolution?.scrcpyPath != null,
                videoEncoderProbeState = deviceSerial?.let { state.captureService.videoEncoderProbeStates[it] },
                videoEncoderDeviceSerial = deviceSerial,
                onRefreshVideoEncoderSupport = deviceSerial?.let { serial ->
                    { state.captureService.refreshVideoEncoderCapabilities(serial, force = true) }
                },
                edit = edit,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AccentLink(
                "All capture settings…",
                {
                    onDismiss()
                    state.openCaptureSettings()
                },
                Modifier.weight(1f, fill = false).testTag("capture-all-settings"),
            )
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                DialogActionButton("Done", active = true, onClick = onDismiss)
            }
        }
    }
}
