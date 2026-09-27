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

private const val CHECK_LABEL_MAX_LINES = 2

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

/** The whole "Before start" block, laid out once: Record video, Capture audio and Include earlier
 * device logs as three equal columns on one row (item 1 of the "make it compact" pass — labels
 * wrap to two lines via [CHECK_LABEL_MAX_LINES] rather than ellipsize when a column is narrow),
 * then the dependent "Keep sound on the device" checkbox and its muted notice, the earlier-logs
 * hint, then Device display and Buffer mode side by side in two columns. The launcher shows it in
 * its panel, Settings as a full-width group, so both screens look the same rather than merely
 * sharing the individual controls. */
@Composable
internal fun CaptureStartOptions(settings: CaptureSettings, edit: CaptureSettingsEdit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { RecordVideoToFileCheck(settings, edit) }
            Box(Modifier.weight(1f)) { CaptureAudioCheck(settings, edit) }
            Box(Modifier.weight(1f)) { IncludeEarlierDeviceLogsCheck(settings, edit) }
        }
        KeepDeviceAudioCheck(settings, edit)
        if (settings.audio) DeviceAudioMutingNotice()
        EarlierDeviceLogsHint()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CaptureDeviceDisplayControl(settings, edit)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CaptureBufferModeControl(settings, edit)
            }
        }
    }
}

@Composable
internal fun RecordVideoToFileCheck(settings: CaptureSettings, edit: CaptureSettingsEdit) {
    CheckRow(settings.recordVideo, { edit { it.copy(recordVideo = !it.recordVideo) } }) {
        AppText(
            "Record video to file", color = tc().tx, fontSize = 11.sp,
            maxLines = CHECK_LABEL_MAX_LINES, modifier = Modifier.weight(1f),
        )
    }
}

/** Device audio goes into the recorded file and the scrcpy window; the in-app mirror is silent.
 *  Whenever this is on, the device's own speaker is muted for the duration unless
 *  [KeepDeviceAudioCheck] is also on and the device qualifies — see [DeviceAudioMutingNotice]. */
@Composable
internal fun CaptureAudioCheck(settings: CaptureSettings, edit: CaptureSettingsEdit) {
    CheckRow(settings.audio, { edit { it.copy(audio = !it.audio) } }) {
        AppText(
            "Capture audio", color = tc().tx, fontSize = 11.sp,
            maxLines = CHECK_LABEL_MAX_LINES, modifier = Modifier.weight(1f),
        )
    }
}

/** Dependent on [CaptureSettings.audio] — greyed out and inert while audio capture is off, since
 *  there is nothing to keep sound on the device FOR otherwise. Asks the embedded scrcpy server (or
 *  the external scrcpy window) for `audio_source=playback` + `audio_dup`, which only Android 13+
 *  accepts; a device below that still gets the plain "device muted" behavior plus a diagnostic —
 *  see [captureAudioPlan][com.indagium.capture.captureAudioPlan]. */
@Composable
internal fun KeepDeviceAudioCheck(settings: CaptureSettings, edit: CaptureSettingsEdit) {
    CheckRow(
        settings.keepDeviceAudio,
        { edit { it.copy(keepDeviceAudio = !it.keepDeviceAudio) } },
        enabled = settings.audio,
    ) {
        AppText(
            "Keep sound on the device (Android 13+)",
            color = if (settings.audio) tc().tx else tc().td,
            fontSize = 11.sp,
            maxLines = CHECK_LABEL_MAX_LINES,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun DeviceAudioMutingNotice() {
    AppText(
        "While audio is captured the phone's speaker is muted (Android 11–12 always; Android 13+ " +
            "unless \"Keep sound on the device\" is on). Apps can block capture of their sound.",
        color = tc().td,
        fontSize = 10.sp,
        maxLines = CAPTURE_HINT_MAX_LINES,
    )
}

/** Off passes `-T 1` to logcat (start at the newest line, CaptureRecorder); on lets logcat dump
 * what the device's ring buffers already hold before streaming — see [EarlierDeviceLogsHint]. */
@Composable
internal fun IncludeEarlierDeviceLogsCheck(settings: CaptureSettings, edit: CaptureSettingsEdit) {
    CheckRow(settings.includeBufferedLogs, { edit { it.copy(includeBufferedLogs = !it.includeBufferedLogs) } }) {
        AppText(
            "Include earlier device logs", color = tc().tx, fontSize = 11.sp,
            maxLines = CHECK_LABEL_MAX_LINES, modifier = Modifier.weight(1f),
        )
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
