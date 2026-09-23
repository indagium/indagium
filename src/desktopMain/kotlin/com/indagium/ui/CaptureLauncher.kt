package com.indagium.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.capture.CaptureBufferMode
import com.indagium.capture.CaptureMirrorMode
import com.indagium.capture.effectiveMirrorMode
import com.indagium.capture.withMirrorMode
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureStatus
import com.indagium.capture.deviceStateGuidance
import kotlinx.coroutines.delay

private const val DEVICE_REFRESH_INTERVAL_MS = 3_000L
private val CAPTURE_LAUNCH_BUFFER_NAMES = listOf("main", "system", "crash", "kernel", "events", "radio")
private val CAPTURE_LAUNCHER_MAX_WIDTH = 640.dp

/** Session-only empty state used by the Capture toolbar action: a centered, ~640dp-wide column
 * (Problem 6 of the restyle plan) rather than a form stretched edge to edge. Kept as its own
 * launcher tab, not folded into an in-place idle→recording transition — that decision is settled
 * separately from this styling pass. */
@Composable
internal fun CaptureLauncher(state: AppState, modifier: Modifier = Modifier) {
    val service = state.captureService
    val launcherTabId = state.activeTab()?.takeIf { it.isCaptureLauncher }?.id
    val draft = launcherTabId?.let(state::captureLaunchSettings)
    var discardId by remember { mutableStateOf<String?>(null) }
    // Local, presentation-only selection of which discovered device "Start capture" acts on — not
    // persisted, not part of AppState, and re-resolved against the live device list every
    // recomposition so a 3s background refresh (below) never silently resets it.
    var selectedSerial by remember { mutableStateOf<String?>(null) }
    val selectedDevice = service.devices.firstOrNull { it.serial == selectedSerial } ?: service.devices.firstOrNull()
    LaunchedEffect(service) {
        service.refreshDevices(force = true)
        while (true) {
            delay(DEVICE_REFRESH_INTERVAL_MS)
            service.refreshDevices()
        }
    }
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = CAPTURE_LAUNCHER_MAX_WIDTH).fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppText("Capture from a device", fontSize = 20.sp, color = tc().tx)
                AppText(
                    "Choose an Android device. The capture opens as a normal streaming log tab.",
                    color = tc().td,
                    fontSize = 12.sp,
                )
            }
            service.toolStatus?.let { status ->
                LauncherPanel("Capture tools") {
                    AppText(status, color = tc().ts, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    service.error?.let { AppText(it, color = DANGER_RED, fontSize = 11.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton("Recheck tools", { service.recheckToolsFromSettings() })
                        AppButton("Install guidance", { service.openInstallGuidanceFromSettings() }, ButtonVariant.Ghost)
                    }
                }
            }
            LauncherPanel("Devices") {
                if (service.devices.isEmpty()) {
                    AppText("No devices discovered", color = tc().td)
                    AppButton("Refresh devices", { service.refreshDevices(force = true) }, ButtonVariant.Ghost)
                } else {
                    service.devices.forEach { device ->
                        CaptureDeviceRow(
                            device = device,
                            selected = device.serial == selectedDevice?.serial,
                            onSelect = { selectedSerial = device.serial },
                        )
                    }
                }
            }
            if (launcherTabId != null && draft != null) {
                CaptureBeforeStartRow(state = state, launcherTabId = launcherTabId, draft = draft)
            }
            AppButton(
                "Start capture",
                { selectedDevice?.let { state.startCaptureTab(it) } },
                ButtonVariant.Primary,
                enabled = selectedDevice?.available == true && state.liveCaptureTabId == null && !state.captureStartInProgress,
                modifier = Modifier.fillMaxWidth(),
            )
            LauncherPanel("Unfinished sessions") {
                state.captureExportError?.let { AppText("Save failed: $it", color = DANGER_RED, fontSize = 10.sp) }
                val retained = service.sessions.filter { it.status != CaptureStatus.RECORDING }
                if (retained.isEmpty()) {
                    AppText("No interrupted sessions", color = tc().td)
                } else {
                    retained.forEach { session ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                AppText("${session.device.model} · ${session.status}")
                                AppText(session.directory.name, color = tc().td, fontSize = 10.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AppButton("Open", { state.openRetainedCapture(session.id) }, ButtonVariant.Secondary)
                                AppButton("Save ZIP", { state.saveRetainedCapture(session.id) }, ButtonVariant.Secondary)
                                AppButton("Open folder", { state.openFolder(session.directory) }, ButtonVariant.Ghost)
                                AppButton("Discard", { discardId = session.id }, ButtonVariant.Ghost)
                            }
                        }
                        if (discardId == session.id) {
                            AppText("Discard this retained capture? The raw session directory will be removed.", color = DANGER_RED, fontSize = 10.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AppButton("Confirm discard", {
                                    state.discardRetainedCapture(session.id)
                                    discardId = null
                                }, ButtonVariant.Primary)
                                AppButton("Cancel", { discardId = null }, ButtonVariant.Ghost)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One selectable row in the Devices card: model (SemiBold), serial (mono, dim) and a readiness
 * pill on the right. `adb` doesn't expose a transport field on [CaptureDevice], so unlike the
 * design's "serial + transport" copy, only the serial is shown here. An actionable hint (from the
 * same [deviceStateGuidance] the recorder itself uses to reject a start) appears under a row that
 * isn't ready. */
@Composable
private fun CaptureDeviceRow(device: CaptureDevice, selected: Boolean, onSelect: () -> Unit) {
    val colors = tc()
    val shape = CORNER_MD
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.hv else Color.Transparent, shape)
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                AppText(device.model, color = colors.tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                AppText(device.serial, color = colors.td, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CaptureDeviceStatePill(device)
        }
        deviceStateGuidance(device.state)?.let { hint ->
            AppText(
                hint, color = DANGER_RED, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** "ready" in a calm success tone, or the raw adb state (unauthorized/offline/…) in [DANGER_RED].
 * Reuses [tc]'s `ok` role — the app's existing derived "confirmation" color (see its doc in
 * Theme.kt) — rather than a fixed green, since no literal-green token exists in [ThemeColors] and
 * every other role here already tracks the active preset instead of a fixed hue. */
@Composable
private fun CaptureDeviceStatePill(device: CaptureDevice) {
    val colors = tc()
    val ready = device.available
    val tone = if (ready) colors.ok else DANGER_RED
    val label = if (ready) "ready" else device.state
    Box(
        Modifier.background(tone.copy(alpha = 0.15f), RoundedCornerShape(50))
            .border(1.dp, tone.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        AppText(label, color = tone, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

/** The pre-start toggles as one compact row instead of a stacked list — each [CheckRow] gets an
 * equal share of the row's width via [Modifier.weight] so three fixed-width-hungry rows sit side
 * by side instead of each claiming the full row (which is what [CheckRow]'s own `fillMaxWidth`
 * would otherwise do if placed directly in a [Row]). Buffer mode and its custom-buffer picker stay
 * stacked below, since they don't fit the same one-line treatment. */
@Composable
private fun CaptureBeforeStartRow(state: AppState, launcherTabId: String, draft: CaptureSettings) {
    LauncherPanel("Before start") {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                CheckRow(draft.recordVideo, {
                    state.updateCaptureLaunchSettings(launcherTabId) { it.copy(recordVideo = !it.recordVideo) }
                }) { AppText("Record video", color = tc().tx, fontSize = 11.sp) }
            }
            Box(Modifier.weight(1f)) {
                CheckRow(draft.includeBufferedLogs, {
                    state.updateCaptureLaunchSettings(launcherTabId) { it.copy(includeBufferedLogs = !it.includeBufferedLogs) }
                }) { AppText("Include buffered logs", color = tc().tx, fontSize = 11.sp) }
            }
        }
        AppText("Device display", color = tc().td, fontSize = 10.sp)
        SegmentedControl(
            options = listOf("In-app mirror", "scrcpy window", "Off"),
            selectedIndices = setOf(
                when (draft.effectiveMirrorMode) {
                    CaptureMirrorMode.EMBEDDED -> 0
                    CaptureMirrorMode.EXTERNAL -> 1
                    CaptureMirrorMode.DISABLED -> 2
                },
            ),
            onToggle = { index ->
                state.updateCaptureLaunchSettings(launcherTabId) {
                    it.withMirrorMode(CaptureMirrorMode.entries[index])
                }
            },
            modifier = Modifier.fillMaxWidth(),
            fillWidth = true,
        )
        AppText("Buffer mode", color = tc().td, fontSize = 10.sp)
        SegmentedControl(
            options = listOf("Default", "All", "Custom"),
            selectedIndices = setOf(CaptureBufferMode.entries.indexOf(draft.bufferMode)),
            onToggle = { index ->
                state.updateCaptureLaunchSettings(launcherTabId) {
                    it.copy(bufferMode = CaptureBufferMode.entries[index])
                }
            },
            modifier = Modifier.fillMaxWidth(),
            fillWidth = true,
        )
        if (draft.bufferMode == CaptureBufferMode.CUSTOM) {
            AppText("Buffers used when Include buffered logs is enabled", color = tc().td, fontSize = 10.sp)
            CAPTURE_LAUNCH_BUFFER_NAMES.forEach { name ->
                val checked = name in draft.buffers
                CheckRow(checked, {
                    state.updateCaptureLaunchSettings(launcherTabId) {
                        it.copy(buffers = if (checked) (it.buffers + name).distinct() else it.buffers - name)
                    }
                }) { AppText(name, color = tc().tx, fontSize = 11.sp) }
            }
            if (draft.buffers.isEmpty()) {
                AppText("Choose at least one buffer for Custom mode.", color = DANGER_RED, fontSize = 10.sp)
            }
        }
        AppText(
            when (draft.bufferMode) {
                CaptureBufferMode.DEFAULT -> "Default follows adb's own buffer selection."
                CaptureBufferMode.ALL -> "All captures every buffer exposed by the device."
                CaptureBufferMode.CUSTOM -> "Custom passes only the selected buffers to adb."
            },
            color = tc().td,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun LauncherPanel(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(BorderStroke(1.dp, tc().br), CORNER_MD).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppText(title, color = tc().ts, fontSize = 11.sp)
        content()
    }
}
