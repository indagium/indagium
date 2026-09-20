package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureRange
import com.indagium.capture.CaptureSession
import com.indagium.capture.RecorderSnapshot
import com.indagium.capture.RecorderState
import com.indagium.capture.renderCaptureFilename
import com.indagium.model.LogTab
import java.io.File
import java.util.Locale

internal const val CAPTURE_STRIP_HEIGHT_DP = 46

// Fits the snapshot dialog's three DialogActionButtons (132dp each, from Dialogs.kt) plus its own
// 20dp side padding and the 8dp gaps the centered action row uses between them.
private val CAPTURE_SNAPSHOT_POPOVER_WIDTH = 480.dp
private const val CAPTURE_DIAGNOSTICS_ROW_LIMIT = 6
private const val CAPTURE_BYTES_PER_KIB = 1024L
private const val CAPTURE_BYTES_PER_MIB = CAPTURE_BYTES_PER_KIB * 1024L
private const val CAPTURE_BYTES_PER_GIB = CAPTURE_BYTES_PER_MIB * 1024L
private const val CAPTURE_MILLIS_PER_SECOND = 1_000L
private const val CAPTURE_SECONDS_PER_MINUTE = 60L
private const val CAPTURE_SECONDS_PER_HOUR = 3_600L

/** Pure display formatting kept separate from the Compose surface for focused regression tests. */
internal fun formatCaptureElapsed(elapsedMs: Long): String {
    val bounded = elapsedMs.coerceAtLeast(0L)
    val totalSeconds = bounded / CAPTURE_MILLIS_PER_SECOND
    val hours = totalSeconds / CAPTURE_SECONDS_PER_HOUR
    val minutes = (totalSeconds % CAPTURE_SECONDS_PER_HOUR) / CAPTURE_SECONDS_PER_MINUTE
    val seconds = totalSeconds % CAPTURE_SECONDS_PER_MINUTE
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/** Compact byte formatting used by both the strip and status card. */
internal fun formatCaptureBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe >= CAPTURE_BYTES_PER_GIB -> String.format(Locale.US, "%.1f GiB", safe.toDouble() / CAPTURE_BYTES_PER_GIB)
        safe >= CAPTURE_BYTES_PER_MIB -> String.format(Locale.US, "%.1f MiB", safe.toDouble() / CAPTURE_BYTES_PER_MIB)
        safe >= CAPTURE_BYTES_PER_KIB -> String.format(Locale.US, "%.1f KiB", safe.toDouble() / CAPTURE_BYTES_PER_KIB)
        else -> "$safe B"
    }
}

internal fun captureVideoStatus(snapshot: RecorderSnapshot): String = when {
    snapshot.videoRecording -> "Video REC"
    snapshot.session?.settings?.recordVideo == true -> "Video idle"
    else -> "Video off"
}

/** Maps the current tab selection to source capture ordinals without assuming row ids start at 1. */
internal fun selectedCaptureOrdinals(tab: LogTab): IntRange? {
    if (tab.selected.isEmpty() || tab.logData.isEmpty()) return null
    val positions = tab.logData.mapIndexedNotNull { index, entry ->
        if (entry.id in tab.selected) index + 1 else null
    }
    if (positions.isEmpty()) return null
    return positions.minOrNull()!!..positions.maxOrNull()!!
}

/** Validates and normalizes the editable snapshot basename. */
internal fun captureArchiveName(input: String): String? {
    val stem = input.trim().removeSuffix(".zip")
    if (stem.isBlank() || stem == "." || stem == "..") return null
    if (stem.contains('/') || stem.contains('\\')) return null
    return "$stem.zip"
}

private fun recorderIsActive(snapshot: RecorderSnapshot): Boolean = when (snapshot.state) {
    RecorderState.RECORDING, RecorderState.STOPPING -> true
    RecorderState.IDLE, RecorderState.STOPPED, RecorderState.INTERRUPTED -> false
}

private fun screenshotButtonEnabled(
    snapshot: RecorderSnapshot,
    capability: CaptureScreenshotCapability,
): Boolean = snapshot.state == RecorderState.RECORDING &&
    capability.availability == CaptureScreenshotAvailability.ENABLED

private fun captureSessionDeviceLabel(session: CaptureSession?, fallback: String): String {
    val device = session?.device ?: return fallback
    return buildString {
        append(device.model.ifBlank { device.serial })
        if (device.serial.isNotBlank() && device.serial != device.model) append(" · ").append(device.serial)
    }
}

/** Model/serial split for [CaptureDeviceChip], which renders the two independently instead of one
 * fixed-width string (that was the truncation bug fixed in the strip's restyle). */
private fun captureSessionDeviceParts(session: CaptureSession?, fallback: String): Pair<String, String> {
    val device = session?.device ?: return fallback to ""
    val model = device.model.ifBlank { device.serial }
    val serial = if (device.serial.isNotBlank() && device.serial != device.model) device.serial else ""
    return model to serial
}

private fun captureSnapshotState(state: AppState, tabId: String): RecorderSnapshot {
    // This helper is used only when the controller is not available during the tiny startup/stop
    // transition. Once a controller exists, the composable below collects its StateFlow directly.
    return state.captureControllerFor(tabId)?.snapshot?.value ?: RecorderSnapshot()
}

@Composable
private fun rememberCaptureSnapshot(state: AppState, tab: LogTab): RecorderSnapshot {
    val controller = state.captureControllerFor(tab.id)
    val snapshotState = if (controller != null) {
        controller.snapshot.collectAsState()
    } else {
        remember(tab.id) { mutableStateOf(captureSnapshotState(state, tab.id)) }
    }
    val snapshot by snapshotState
    return snapshot
}

/** 46dp live-capture chrome rendered only above an active streaming log tab. */
@Composable
internal fun CaptureStrip(
    state: AppState,
    tab: LogTab,
    onReturnFocus: () -> Unit,
) {
    if (tab.captureSessionId == null) {
        if (tab.isCaptureLauncher) {
            CaptureIdleStrip()
            return
        }
        state.captureFinalizationStatus(tab.id)?.let { status ->
            CaptureFinalizationBanner(status)
        }
        // Export becoming unreachable the instant a capture stops was the worst of the capture
        // regressions: captureSessionId is cleared on Stop (recording really is over), but the
        // tab's rows and its recorder session on disk are still there. captureSourceSessionId
        // (session-only, set by attachFinalizedCapture / stopCaptureTab's failure paths) is what
        // lets this strip keep offering Save ZIP / Open folder instead of disappearing along with
        // the live controller — see the field's doc in Model.kt.
        if (tab.captureSourceSessionId != null) {
            CaptureStoppedStrip(state = state, tab = tab, onReturnFocus = onReturnFocus)
        }
        return
    }

    val snapshot = rememberCaptureSnapshot(state, tab)
    LaunchedEffect(tab.id) { state.ensureScreenshotCapability(tab.id) }
    var diagnosticsOpen by remember(tab.id) { mutableStateOf(false) }
    val session = snapshot.session
    val active = recorderIsActive(snapshot)
    val screenshotCapability = state.screenshotCapability(tab.id)
    val colors = tc()
    val (deviceModel, deviceSerial) = captureSessionDeviceParts(session, tab.filename.removePrefix("Capture — "))
    var snapshotOpen by remember(tab.id) { mutableStateOf(false) }
    val storageLabel = session?.let {
        "${formatCaptureBytes(snapshot.logBytes)} / ${formatCaptureBytes(it.settings.sessionLimitBytes)}"
    } ?: formatCaptureBytes(snapshot.logBytes)
    val finalizing = state.captureFinalizationStatus(tab.id) == CAPTURE_FINALIZING_STATUS

    Column(Modifier.fillMaxWidth().background(colors.p2)) {
        // Status cluster (device chip + rec/state + storage/video) sits on the left; the action
        // buttons are right-aligned and never shrink — narrow windows are handled by letting the
        // status text truncate instead of the strip scrolling sideways (see Problem 1 in the
        // restyle plan this strip follows).
        Row(
            Modifier.fillMaxWidth().height(CAPTURE_STRIP_HEIGHT_DP.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CaptureDeviceChip(model = deviceModel, serial = deviceSerial, live = active)
            when {
                finalizing -> AppText(
                    CAPTURE_FINALIZING_STATUS, color = colors.ts, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                active -> CaptureRecordingIndicator(elapsedMs = sessionElapsed(snapshot))
                else -> AppText(
                    snapshot.state.name, color = colors.td, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppText(
                    "Storage $storageLabel", color = colors.td, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                AppText(
                    captureVideoStatus(snapshot),
                    color = if (snapshot.videoRecording) colors.ac else colors.td,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            ToolbarBtn(
                label = "Screenshot",
                icon = Icons.Outlined.AddAPhoto,
                tooltip = "Capture a device screenshot",
                enabled = screenshotButtonEnabled(snapshot, screenshotCapability),
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                onClick = {
                    state.screenshotCapture(tab.id)
                    onReturnFocus()
                },
            )
            ToolbarBtn(
                label = "Stop",
                icon = Icons.Outlined.Stop,
                tooltip = "Stop capture",
                enabled = active,
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                onClick = {
                    state.stopCaptureTab(tab.id)
                    onReturnFocus()
                },
            )
            Box {
                ToolbarBtn(
                    label = "Save snapshot",
                    icon = Icons.Outlined.Save,
                    active = true,
                    tooltip = "Export a capture snapshot",
                    enabled = active && !state.captureExportBusy,
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                    onClick = {
                        snapshotOpen = true
                        state.clearCaptureExportStatus()
                    },
                )
                if (snapshotOpen) {
                    CaptureSnapshotPopover(
                        state = state,
                        tab = tab,
                        snapshot = snapshot,
                        onDismiss = {
                            if (state.captureExportBusy) state.cancelCaptureSnapshot()
                            snapshotOpen = false
                            state.clearCaptureExportStatus()
                            onReturnFocus()
                        },
                        onReturnFocus = onReturnFocus,
                    )
                }
            }
            ToolbarBtn(
                label = "Settings",
                icon = Icons.Outlined.Tune,
                showLabel = false,
                tooltip = "Open Capture settings",
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                onClick = {
                    state.requestedSettingsSection = SettingsSection.Capture
                    state.settingsOpen = true
                    onReturnFocus()
                },
            )
            if (snapshot.diagnostics.isNotEmpty()) {
                PillBtn(
                    label = snapshot.diagnostics.size.toString(),
                    active = diagnosticsOpen,
                    onClick = {
                        diagnosticsOpen = !diagnosticsOpen
                        onReturnFocus()
                    },
                )
            }
        }
        if (diagnosticsOpen) {
            CaptureDiagnosticsDrawer(
                state = state,
                tab = tab,
                snapshot = snapshot,
                onDismiss = {
                    diagnosticsOpen = false
                    onReturnFocus()
                },
            )
        }
    }
}

/** Pill-shaped device identity chip (Problem 2 of the restyle plan): a state dot, the model
 * (truncated with an ellipsis if it must, via [widthIn]) and the full serial in monospace, which
 * never truncates — the serial is the identifying part a fixed-width column used to cut off. */
@Composable
private fun CaptureDeviceChip(model: String, serial: String, live: Boolean, modifier: Modifier = Modifier) {
    val colors = tc()
    Row(
        modifier
            .background(colors.p, RoundedCornerShape(50))
            .border(1.dp, colors.br, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).background(if (live) colors.ok else colors.td, RoundedCornerShape(50)))
        AppText(
            model,
            color = colors.tx,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 130.dp),
        )
        if (serial.isNotBlank()) {
            AppText(serial, color = colors.td, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}

/** Recording indicator shown next to (not inside) the device chip while a capture is active. */
@Composable
private fun CaptureRecordingIndicator(elapsedMs: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).background(DANGER_RED, RoundedCornerShape(50)))
        AppText(
            "REC ${formatCaptureElapsed(elapsedMs)}",
            color = DANGER_RED,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun CaptureIdleStrip() {
    val colors = tc()
    Row(
        Modifier.fillMaxWidth().height(CAPTURE_STRIP_HEIGHT_DP.dp).background(colors.p2).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(colors.td, RoundedCornerShape(50)))
        AppText("New capture", color = colors.tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        AppText(
            "Select a device to begin; capture settings are available in Settings.",
            color = colors.td, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Chrome for a capture tab whose recording has stopped: no live controller/recorder exists any
 * more, so this reads the retained [CaptureSession] straight off disk (via [CaptureService],
 * already kept fresh by [AppState.stopCaptureTab]'s `updateSessions()` call) instead of collecting
 * a [RecorderSnapshot]. Save ZIP and Open folder deliberately reuse the exact same
 * [AppState.saveRetainedCapture] / [AppState.openFolder] calls the "Retained sessions" list in
 * [CaptureLauncher] already uses for a session with no open tab — the underlying export has never
 * needed a live recorder (CaptureArchiveExporter.export takes a CaptureSession, not a controller),
 * only the previous wiring did.
 */
@Composable
private fun CaptureStoppedStrip(
    state: AppState,
    tab: LogTab,
    onReturnFocus: () -> Unit,
) {
    val sessionId = tab.captureSourceSessionId ?: return
    val session = state.captureService.sessions.firstOrNull { it.id == sessionId }
    val colors = tc()
    val (deviceModel, deviceSerial) = captureSessionDeviceParts(session, tab.filename.removePrefix("Capture — "))
    Column(Modifier.fillMaxWidth().background(colors.p2)) {
        Row(
            Modifier.fillMaxWidth().height(CAPTURE_STRIP_HEIGHT_DP.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CaptureDeviceChip(model = deviceModel, serial = deviceSerial, live = false)
            AppText(
                "Stopped · ${formatCaptureElapsed(session?.elapsedMs ?: 0L)}",
                color = colors.td, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            AppText(
                "${tab.logData.size} rows · ${formatCaptureBytes(session?.logFile?.length() ?: 0L)}",
                color = colors.td, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            ToolbarBtn(
                label = "Save ZIP",
                icon = Icons.Outlined.Save,
                active = true,
                tooltip = "Export this capture as a ZIP",
                enabled = session != null && !state.captureExportBusy,
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                onClick = {
                    state.saveRetainedCapture(sessionId)
                    onReturnFocus()
                },
            )
            ToolbarBtn(
                label = "Open folder",
                icon = Icons.Outlined.FolderOpen,
                tooltip = "Open the capture's session folder",
                enabled = session != null,
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                onClick = {
                    session?.directory?.let(state::openFolder)
                    onReturnFocus()
                },
            )
        }
        if (session == null || state.captureExportError != null || state.captureExportResult != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (session == null) {
                    AppText("Session no longer on disk", color = DANGER_RED, fontSize = 10.sp)
                }
                state.captureExportError?.let {
                    AppText("Save failed: $it", color = DANGER_RED, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                state.captureExportResult?.let { AppText("Saved ${it.file.name}", color = colors.ac, fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun CaptureFinalizationBanner(status: String) {
    val colors = tc()
    Row(
        Modifier.fillMaxWidth().background(colors.p2).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            status,
            color = if (status == CAPTURE_FINALIZING_STATUS) colors.ts else DANGER_RED,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private enum class SnapshotRangeChoice(val label: String) {
    ALL("All"),
    LAST_MINUTES("Last N minutes"),
    SINCE_SAVE("Since last save"),
    SELECTION("Current selection"),
}

@Composable
private fun CaptureSnapshotPopover(
    state: AppState,
    tab: LogTab,
    snapshot: RecorderSnapshot,
    onDismiss: () -> Unit,
    onReturnFocus: () -> Unit,
) {
    val colors = tc()
    val session = snapshot.session
    val selection = remember(tab.id, tab.selected, tab.logData) { selectedCaptureOrdinals(tab) }
    var rangeChoice by remember(tab.id) { mutableStateOf(SnapshotRangeChoice.ALL) }
    var customMinutesText by remember(tab.id) { mutableStateOf("5") }
    var includeVideo by remember(tab.id) { mutableStateOf(session?.settings?.recordVideo == true) }
    var overwriteConfirmed by remember(tab.id) { mutableStateOf(false) }
    var saveAndOpen by remember(tab.id) { mutableStateOf(false) }
    val customMinutes = customMinutesText.toIntOrNull()?.coerceAtLeast(1) ?: 0
    val range = when (rangeChoice) {
        SnapshotRangeChoice.ALL -> CaptureRange.ALL
        SnapshotRangeChoice.LAST_MINUTES -> when (customMinutes) {
            5 -> CaptureRange.LAST_FIVE
            10 -> CaptureRange.LAST_TEN
            else -> CaptureRange.CUSTOM
        }
        SnapshotRangeChoice.SINCE_SAVE -> CaptureRange.SINCE_SAVE
        SnapshotRangeChoice.SELECTION -> CaptureRange.SELECTION
    }
    val defaultFilename = session?.let {
        renderCaptureFilename(
            template = it.settings.filenameTemplate,
            device = it.device,
            startEpochMs = it.startedEpochMs,
            range = range,
            counter = it.exportCounter,
            label = it.settings.label,
        )
    } ?: "capture.zip"
    var basename by remember(tab.id, defaultFilename) {
        mutableStateOf(defaultFilename.removeSuffix(".zip"))
    }
    val destinationDirectory = state.settings.defaultSaveDir?.let(::File) ?: File(".")
    val filename = captureArchiveName(basename)
    val destination = filename?.let { File(destinationDirectory, it) } ?: File(destinationDirectory, "capture.zip")
    val enabled = session != null && !state.captureExportBusy &&
        (rangeChoice != SnapshotRangeChoice.LAST_MINUTES || customMinutes > 0) &&
        (rangeChoice != SnapshotRangeChoice.SELECTION || selection != null) && filename != null
    val request = session?.let {
        CaptureExportRequest(
            destination = destination,
            range = range,
            customMinutes = customMinutes,
            includeVideo = includeVideo,
            cutoffElapsedMs = it.elapsedMs,
            selectedFirstRowOrdinal = selection?.first,
            selectedLastRowOrdinal = selection?.last,
            overwriteExisting = overwriteConfirmed,
        )
    }
    LaunchedEffect(rangeChoice, customMinutes, includeVideo, selection, basename, session?.elapsedMs) {
        if (enabled && request != null) state.previewCaptureSnapshot(tab.id, request)
    }

    // Dialog chrome matches the app's other dialogs (see SplitPromptDialog in Dialogs.kt, the
    // reference this was copied from): rounded 8dp corners, 20dp padding, 12dp section rhythm, a
    // 14sp SemiBold title, 10sp SemiBold section labels, and DialogActionButton for the terminal
    // action row instead of a plain AppButton row. Width is sized to fit that row's three
    // DialogActionButtons (132dp each) plus the dialog's own side padding.
    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(CAPTURE_SNAPSHOT_POPOVER_WIDTH)
                .background(colors.p, RoundedCornerShape(8.dp))
                .border(1.dp, colors.br, RoundedCornerShape(8.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppText("Capture snapshot", color = colors.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                SquareIconButton("×", fontSize = 14.sp, enabled = !state.captureExportBusy, onClick = {
                    onDismiss()
                    onReturnFocus()
                })
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AppText("Range", color = colors.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                // Range is mutually exclusive (exactly one of All/Last N minutes/Since last
                // save/Current selection applies to the export), so this is a radio group — see
                // RadioRow in Components.kt — not a checkbox list. A checkbox implies "choose any",
                // which was the semantic bug here even though it happened to render one selection
                // at a time.
                SnapshotRangeChoice.entries.forEach { choice ->
                    val choiceEnabled = choice != SnapshotRangeChoice.SELECTION || selection != null
                    RadioRow(
                        selected = rangeChoice == choice,
                        onSelect = {
                            rangeChoice = choice
                            onReturnFocus()
                        },
                        enabled = choiceEnabled,
                    ) {
                        AppText(choice.label, color = if (choiceEnabled) colors.ts else colors.td, fontSize = 11.sp)
                    }
                }
            }
            if (rangeChoice == SnapshotRangeChoice.LAST_MINUTES) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppText("Minutes", color = colors.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    InlineField(
                        value = customMinutesText,
                        onValue = { value -> customMinutesText = value.filter(Char::isDigit).take(4) },
                        modifier = Modifier.width(64.dp),
                    )
                    if (customMinutes == 0) AppText("Enter a positive number", color = DANGER_RED, fontSize = 10.sp)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppText("Archive name", color = colors.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                InlineField(
                    value = basename,
                    onValue = { basename = it.take(180) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filename == null) AppText("Enter a valid archive basename", color = DANGER_RED, fontSize = 10.sp)
            }
            CheckRow(
                includeVideo,
                {
                    if (session?.settings?.recordVideo == true) includeVideo = !includeVideo
                    onReturnFocus()
                },
            ) {
                AppText("Include video", color = if (session?.settings?.recordVideo == true) colors.ts else colors.td, fontSize = 11.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppText("Destination", color = colors.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                AppText(
                    destination.absolutePath,
                    color = colors.ts,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                AppButton(
                    "Choose folder…",
                    { state.pickSaveFolder(); onReturnFocus() },
                    ButtonVariant.Secondary,
                    enabled = !state.captureExportBusy,
                )
            }
            if (rangeChoice == SnapshotRangeChoice.SELECTION && selection != null) {
                AppText("Rows ${selection.first}–${selection.last}; separators between those rows are included.", color = colors.td, fontSize = 10.sp)
            }
            state.captureExportPreview?.let { preview ->
                val logCoverage = preview.logStartMs?.let { start ->
                    "Log ${formatCaptureElapsed(start)}–${formatCaptureElapsed(preview.logEndMs ?: start)}"
                } ?: "No complete log rows in this range"
                AppText(logCoverage, color = colors.ts, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                if (preview.includeVideo && preview.videoShortfallMs != null && preview.videoShortfallMs > 0L) {
                    AppText(
                        if (preview.videoCoveredEndMs != null) {
                            "Video coverage ends at ${formatCaptureElapsed(preview.videoCoveredEndMs)}; " +
                                "shortfall ${formatCaptureElapsed(preview.videoShortfallMs)}."
                        } else {
                            "Video coverage unavailable; shortfall ${formatCaptureElapsed(preview.videoShortfallMs)}."
                        },
                        color = colors.ts,
                        fontSize = 10.sp,
                    )
                }
            }
            if (destination.exists() && !overwriteConfirmed) {
                AppText(
                    "An archive with this name already exists. Confirm overwrite to continue.",
                    color = DANGER_RED,
                    fontSize = 10.sp,
                )
                AppButton("Confirm overwrite", { overwriteConfirmed = true; onReturnFocus() }, ButtonVariant.Secondary)
            }
            state.captureExportError?.let { error ->
                AppText("Snapshot failed: $error", color = DANGER_RED, fontSize = 10.sp, maxLines = 3)
            }
            state.captureExportResult?.let { result ->
                AppText("Saved ${result.file.name}: ${result.message}", color = colors.ac, fontSize = 10.sp, maxLines = 2)
                if (includeVideo && result.videoCoveredEndMs != null && result.videoCoveredEndMs < result.logCoveredEndMs) {
                    AppText(
                        "Video coverage ends at ${formatCaptureElapsed(result.videoCoveredEndMs)}; " +
                            "log coverage ends at ${formatCaptureElapsed(result.logCoveredEndMs)}.",
                        color = colors.ts,
                        fontSize = 10.sp,
                        maxLines = 2,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.captureExportBusy) {
                    DialogActionButton("Cancel export", active = true, danger = true) {
                        state.cancelCaptureSnapshot()
                        onDismiss()
                        onReturnFocus()
                    }
                } else {
                    DialogActionButton("Cancel", active = false) {
                        onDismiss()
                        onReturnFocus()
                    }
                    // "Save ZIP" is the primary action (active = true, the accent-filled style);
                    // "Save + open" is the secondary variant of the same action, matching
                    // DialogActionButton's active/inactive convention elsewhere (e.g.
                    // SplitPromptDialog's "Split"/"Do Not Split").
                    DialogActionButton("Save ZIP", active = true, enabled = enabled) {
                        saveAndOpen = false
                        request?.let { state.exportCaptureSnapshot(tab.id, it) }
                        onReturnFocus()
                    }
                    DialogActionButton("Save + open", active = false, enabled = enabled) {
                        saveAndOpen = true
                        request?.let { state.exportCaptureSnapshot(tab.id, it) }
                        onReturnFocus()
                    }
                }
            }
        }
    }
    LaunchedEffect(state.captureExportResult) {
        if (saveAndOpen) {
            state.captureExportResult?.file?.let { file ->
                // The export is always our own capture ZIP (a portable archive with a
                // capture.indagium.json descriptor), never a plain text log — routing it through
                // plain openFile() parsed the ZIP's raw bytes as a text log and produced a tab full
                // of "RAW: PK ..." mojibake, with no video attached. openCaptureFile is the designed
                // entry point: it validates the descriptor, parses the log inside the zip, and
                // attaches the video. isCaptureArchive still gates it so a hand-edited/corrupted
                // export that genuinely isn't a capture archive falls back to plain openFile instead
                // of throwing deep inside openCaptureFile's coroutine.
                if (com.indagium.capture.CaptureArchiveReader.isCaptureArchive(file)) {
                    state.openCaptureFile(file)
                } else {
                    state.openFile(file)
                }
                saveAndOpen = false
                onDismiss()
            }
        }
    }
}

private fun sessionElapsed(snapshot: RecorderSnapshot): Long =
    snapshot.session?.elapsedMs?.coerceAtLeast(0L) ?: 0L

@Composable
private fun CaptureDiagnosticsDrawer(
    state: AppState,
    tab: LogTab,
    snapshot: RecorderSnapshot,
    onDismiss: () -> Unit,
) {
    val colors = tc()
    Column(
        Modifier.fillMaxWidth().border(1.dp, colors.br).background(colors.p).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppText("Capture diagnostics", color = colors.tx, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            AppButton("Close", onDismiss, ButtonVariant.Ghost)
        }
        AppText("Recorder: ${snapshot.state.name} · rows: ${snapshot.indexedRows} · log: ${formatCaptureBytes(snapshot.logBytes)}",
            color = colors.td, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        state.captureToolStatus?.let {
            AppText(it, color = colors.td, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 4)
        }
        state.captureScreenshotStatus?.let { AppText(it, color = colors.ts, fontSize = 10.sp) }
        state.screenshotCapability(tab.id).reason?.let {
            AppText("Screenshot unavailable: $it", color = DANGER_RED, fontSize = 10.sp, maxLines = 2)
        }
        val diagnostics = snapshot.diagnostics.takeLast(CAPTURE_DIAGNOSTICS_ROW_LIMIT)
        if (diagnostics.isEmpty()) {
            AppText("No recorder diagnostics", color = colors.td, fontSize = 10.sp)
        } else {
            diagnostics.forEach { line ->
                AppText(line, color = colors.ts, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Status-only right-sidebar card for a live capture; live video remains in scrcpy's window.
 * Structured per Problem 5 of the restyle plan: an uppercase "DEVICE" section label, a bordered
 * mirror placeholder (no embedded video — see [CaptureMirrorPlaceholder]), a labelled value grid,
 * then a subtle diagnostics footer. Scrolls so it never clips at the panel's ~360–500dp width
 * ([ANNOTATION_PANEL_MAX_WIDTH]) when the host window is short. */
@Composable
internal fun CaptureCard(state: AppState, tab: LogTab) {
    if (tab.captureSessionId == null) return
    val snapshot = rememberCaptureSnapshot(state, tab)
    val colors = tc()
    val session = snapshot.session
    val deviceLabel = captureSessionDeviceLabel(session, tab.filename.removePrefix("Capture — "))
    val storageLabel = session?.let {
        "${formatCaptureBytes(snapshot.logBytes)} / ${formatCaptureBytes(it.settings.sessionLimitBytes)}"
    } ?: formatCaptureBytes(snapshot.logBytes)
    Column(
        Modifier.fillMaxSize().background(colors.p).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionHeader("DEVICE")
        CaptureMirrorPlaceholder(onOpenMirror = { state.openCaptureMirror(tab.id) })
        CaptureCardValueGrid(
            deviceLabel = deviceLabel,
            elapsedLabel = formatCaptureElapsed(sessionElapsed(snapshot)),
            storageLabel = storageLabel,
            videoLabel = captureVideoStatus(snapshot),
        )
        state.captureScreenshotStatus?.let {
            AppText(it, color = colors.ts, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (snapshot.diagnostics.isNotEmpty()) {
            AppText("${snapshot.diagnostics.size} diagnostic message(s)", color = colors.td, fontSize = 10.sp)
        }
    }
}

/** Bordered, rounded placeholder for where the design showed a live mirror preview. We
 * deliberately do not embed video here — the player cannot follow a growing capture file — so
 * this explains that the mirror is a separate scrcpy window and hosts the button that opens it. */
@Composable
private fun CaptureMirrorPlaceholder(onOpenMirror: () -> Unit) {
    val colors = tc()
    Column(
        Modifier.fillMaxWidth().height(132.dp)
            .border(1.dp, colors.br, RoundedCornerShape(8.dp))
            .background(colors.p2, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, tint = colors.td, modifier = Modifier.size(22.dp))
        AppText(
            "Live mirror opens in a separate scrcpy window",
            color = colors.td, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        AppButton("Open mirror", onOpenMirror, ButtonVariant.Secondary)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun CaptureCardValueGrid(deviceLabel: String, elapsedLabel: String, storageLabel: String, videoLabel: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CaptureCardValueRow("Device", deviceLabel)
        CaptureCardValueRow("Elapsed", elapsedLabel)
        CaptureCardValueRow("Storage", storageLabel)
        CaptureCardValueRow("Video", videoLabel)
    }
}

@Composable
private fun CaptureCardValueRow(label: String, value: String) {
    val colors = tc()
    Row(Modifier.fillMaxWidth()) {
        AppText(label, color = colors.td, fontSize = 11.sp, modifier = Modifier.width(64.dp))
        AppText(
            value, color = colors.tx, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun CaptureIdleCard(state: AppState) {
    val colors = tc()
    Column(
        Modifier.fillMaxSize().background(colors.p).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppText("Capture", color = colors.tx, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        AppText(
            "Choose an Android device in the capture tab. Global capture settings apply immediately; this draft is session-only.",
            color = colors.td,
            fontSize = 10.sp,
            maxLines = 4,
        )
        state.captureService.toolStatus?.let { AppText(it, color = colors.ts, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
        if (state.captureService.devices.isEmpty()) {
            AppText("No devices discovered", color = colors.td, fontSize = 10.sp)
        } else {
            state.captureService.devices.forEach { device ->
                AppText("${device.model} · ${device.state}", color = if (device.available) colors.ts else colors.td, fontSize = 10.sp)
            }
        }
    }
}
