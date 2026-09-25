package com.indagium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.capture.CaptureBufferMode
import com.indagium.capture.CaptureMirrorMode
import com.indagium.capture.CaptureSettings
import com.indagium.capture.effectiveMirrorMode
import com.indagium.capture.withMirrorMode

/**
 * The capture controls that appear both on the New tab's "Before start" panel (a per-launch draft)
 * and in Settings → Capture (the saved defaults), so the two always read and behave the same.
 * Each takes [edit], a settings transform: the launcher applies it to its draft
 * (`updateCaptureLaunchSettings`), Settings to the saved settings.
 */
internal typealias CaptureSettingsEdit = ((CaptureSettings) -> CaptureSettings) -> Unit

internal val CAPTURE_BUFFER_NAMES = listOf("main", "system", "crash", "kernel", "events", "radio")
private const val CAPTURE_BUFFER_GRID_COLUMNS = 3
private const val CAPTURE_HINT_MAX_LINES = 3

/** The whole "Before start" block, laid out once: the two checkboxes side by side, the hint,
 * Device display, then Buffer mode. The launcher shows it in its panel, Settings as a full-width
 * group, so both screens look the same rather than merely sharing the individual controls. */
@Composable
internal fun CaptureStartOptions(settings: CaptureSettings, edit: CaptureSettingsEdit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { RecordVideoToFileCheck(settings, edit) }
            Box(Modifier.weight(1f)) { IncludeEarlierDeviceLogsCheck(settings, edit) }
        }
        EarlierDeviceLogsHint()
        CaptureDeviceDisplayControl(settings, edit)
        CaptureBufferModeControl(settings, edit)
    }
}

@Composable
internal fun RecordVideoToFileCheck(settings: CaptureSettings, edit: CaptureSettingsEdit) {
    CheckRow(settings.recordVideo, { edit { it.copy(recordVideo = !it.recordVideo) } }) {
        AppText("Record video to file", color = tc().tx, fontSize = 11.sp)
    }
}

/** Off passes `-T 1` to logcat (start at the newest line, CaptureRecorder); on lets logcat dump
 * what the device's ring buffers already hold before streaming — see [EarlierDeviceLogsHint]. */
@Composable
internal fun IncludeEarlierDeviceLogsCheck(settings: CaptureSettings, edit: CaptureSettingsEdit) {
    CheckRow(settings.includeBufferedLogs, { edit { it.copy(includeBufferedLogs = !it.includeBufferedLogs) } }) {
        AppText("Include earlier device logs", color = tc().tx, fontSize = 11.sp)
    }
}

@Composable
internal fun EarlierDeviceLogsHint() {
    AppText(
        "Earlier device logs: also copies what the device already holds from before Start " +
            "(often minutes to hours), not only new lines.",
        color = tc().td,
        fontSize = 10.sp,
        maxLines = CAPTURE_HINT_MAX_LINES,
    )
}

@Composable
internal fun CaptureDeviceDisplayControl(settings: CaptureSettings, edit: CaptureSettingsEdit) {
    AppText("Device display", color = tc().td, fontSize = 10.sp)
    SegmentedControl(
        options = listOf("In-app mirror", "scrcpy window", "Off"),
        selectedIndices = setOf(
            when (settings.effectiveMirrorMode) {
                CaptureMirrorMode.EMBEDDED -> 0
                CaptureMirrorMode.EXTERNAL -> 1
                CaptureMirrorMode.DISABLED -> 2
            },
        ),
        onToggle = { index -> edit { it.withMirrorMode(CaptureMirrorMode.entries[index]) } },
        modifier = Modifier.fillMaxWidth(),
        fillWidth = true,
    )
}

/** Mode selector, the Custom picker (three buffers per row) and one line saying what the mode does. */
@Composable
internal fun CaptureBufferModeControl(settings: CaptureSettings, edit: CaptureSettingsEdit) {
    val colors = tc()
    AppText("Buffer mode", color = colors.td, fontSize = 10.sp)
    SegmentedControl(
        options = listOf("Default", "All", "Custom"),
        selectedIndices = setOf(CaptureBufferMode.entries.indexOf(settings.bufferMode)),
        onToggle = { index -> edit { it.copy(bufferMode = CaptureBufferMode.entries[index]) } },
        modifier = Modifier.fillMaxWidth(),
        fillWidth = true,
    )
    AppText(
        when (settings.bufferMode) {
            CaptureBufferMode.DEFAULT -> "Uses adb's own default buffers (no -b flag)."
            CaptureBufferMode.ALL -> "Captures every buffer the device exposes (-b all)."
            CaptureBufferMode.CUSTOM -> "Captures only the buffers ticked below."
        },
        color = colors.td,
        fontSize = 10.sp,
        maxLines = CAPTURE_HINT_MAX_LINES,
    )
    if (settings.bufferMode == CaptureBufferMode.CUSTOM) CaptureCustomBufferPicker(settings, edit)
}

/** Buffers picked here always apply in Custom mode — `logcatBufferArgs()` ignores
 * `includeBufferedLogs` — hence "Buffers to capture" rather than tying them to that toggle. */
@Composable
private fun CaptureCustomBufferPicker(settings: CaptureSettings, edit: CaptureSettingsEdit) {
    AppText("Buffers to capture", color = tc().td, fontSize = 10.sp)
    CAPTURE_BUFFER_NAMES.chunked(CAPTURE_BUFFER_GRID_COLUMNS).forEach { rowNames ->
        Row(Modifier.fillMaxWidth()) {
            rowNames.forEach { name ->
                Box(Modifier.weight(1f)) {
                    CheckRow(name in settings.buffers, { edit { it.copy(buffers = toggleCaptureBuffer(it.buffers, name)) } }) {
                        AppText(name, color = tc().tx, fontSize = 11.sp)
                    }
                }
            }
        }
    }
    if (settings.buffers.isEmpty()) {
        AppText("Choose at least one buffer for Custom mode.", color = DANGER_RED, fontSize = 10.sp)
    }
}
