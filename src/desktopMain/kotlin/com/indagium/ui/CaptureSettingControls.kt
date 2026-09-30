package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indagium.capture.CaptureBufferMode
import com.indagium.capture.CaptureMirrorMode
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureVideoPreset
import com.indagium.capture.DesktopMicrophone
import com.indagium.capture.MICROPHONE_DEFAULT_ID
import com.indagium.capture.MICROPHONE_OFF_ID
import com.indagium.capture.NativeMediaSupport
import com.indagium.capture.captureSizeEstimate
import com.indagium.capture.effectiveMirrorMode
import com.indagium.capture.enumerateDesktopMicrophones
import com.indagium.capture.formatCaptureSizeEstimate
import com.indagium.capture.videoPreset
import com.indagium.capture.withMirrorMode
import com.indagium.capture.withVideoPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
internal fun CaptureStartOptions(
    settings: CaptureSettings,
    onReclaimFocus: () -> Unit = {},
    // Old-glibc Linux (see NativeMediaSupport.kt): available on every other system, and until the
    // background probe in CaptureService's init publishes a real answer — see that field's own doc
    // for why "available" is the right default to assume meanwhile.
    nativeMediaSupport: NativeMediaSupport = NativeMediaSupport(available = true),
    scrcpyAvailable: Boolean = true,
    edit: CaptureSettingsEdit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { RecordVideoToFileCheck(settings, edit, nativeMediaSupport) }
            Box(Modifier.weight(1f)) { CaptureAudioCheck(settings, edit) }
            Box(Modifier.weight(1f)) { IncludeEarlierDeviceLogsCheck(settings, edit) }
        }
        if (isMacOs) {
            KeepDeviceAudioCheck(settings, edit)
        } else {
            // Same three-column grid as the row above, so the opt-in sits under "Capture audio".
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { KeepDeviceAudioCheck(settings, edit) }
                Box(Modifier.weight(1f)) { HardwareMirrorCheck(settings, edit, nativeMediaSupport) }
                Spacer(Modifier.weight(1f))
            }
        }
        if (settings.audio) DeviceAudioMutingNotice()
        CaptureMicrophoneControl(settings, edit, onReclaimFocus)
        EarlierDeviceLogsHint()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CaptureDeviceDisplayControl(settings, edit, nativeMediaSupport, scrcpyAvailable)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CaptureBufferModeControl(settings, edit)
            }
        }
        // Full width (the four segment labels plus the hint/estimate lines want the room) and only
        // while video is recorded — with Record video off none of these numbers apply.
        if (settings.recordVideo) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CaptureVideoQualityControl(settings, edit, onReclaimFocus)
            }
        }
    }
}

@Composable
private fun CaptureMicrophoneControl(
    settings: CaptureSettings,
    edit: CaptureSettingsEdit,
    onReclaimFocus: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var microphones by remember { mutableStateOf<List<DesktopMicrophone>?>(null) }
    var enumerationFailure by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refreshMicrophones() {
        if (scanning) return
        scope.launch {
            scanning = true
            try {
                val enumeration = withContext(Dispatchers.IO) { enumerateDesktopMicrophones() }
                microphones = enumeration.devices
                enumerationFailure = enumeration.failure
            } finally {
                scanning = false
            }
        }
    }
    LaunchedEffect(settings.microphoneDeviceId) {
        if (settings.microphoneDeviceId != MICROPHONE_OFF_ID &&
            settings.microphoneDeviceId != MICROPHONE_DEFAULT_ID && microphones == null
        ) {
            refreshMicrophones()
        }
    }
    val selected = when (settings.microphoneDeviceId) {
        MICROPHONE_OFF_ID -> "Off"
        MICROPHONE_DEFAULT_ID -> "System default"
        else -> when {
            scanning -> "Loading microphone…"
            enumerationFailure != null -> "Microphone list unavailable"
            microphones == null -> "Selected microphone"
            else -> microphones?.firstOrNull { it.id == settings.microphoneDeviceId }?.label
                ?: "Selected microphone unavailable"
        }
    }
    val colors = tc()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.settingsAnchor("Microphone"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText("Microphone", color = colors.td, fontSize = 10.sp)
            Box(Modifier.widthIn(max = 250.dp).fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(CORNER_MD)
                        .border(1.dp, colors.br, CORNER_MD)
                        .clickable {
                            menuExpanded = !menuExpanded
                            if (menuExpanded) refreshMicrophones()
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    AppText(
                        if (scanning) "Loading microphones…" else selected,
                        color = colors.tx,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    AppText(if (menuExpanded) "▾" else "▸", color = colors.ts, fontSize = 9.sp)
                }
                if (menuExpanded) {
                    val density = androidx.compose.ui.platform.LocalDensity.current.density
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(0, (32 * density).roundToInt()),
                        onDismissRequest = { menuExpanded = false; onReclaimFocus() },
                        properties = PopupProperties(focusable = true),
                    ) {
                        Column(
                            Modifier.widthIn(min = 190.dp, max = 250.dp)
                                .background(colors.p, RoundedCornerShape(7.dp))
                                .border(1.dp, colors.br, RoundedCornerShape(7.dp))
                                .padding(vertical = 4.dp),
                        ) {
                            MicrophoneOption("Off", settings.microphoneDeviceId == MICROPHONE_OFF_ID) {
                                edit { it.copy(microphoneDeviceId = MICROPHONE_OFF_ID) }
                                menuExpanded = false
                                onReclaimFocus()
                            }
                            MicrophoneOption("System default", settings.microphoneDeviceId == MICROPHONE_DEFAULT_ID) {
                                edit { it.copy(microphoneDeviceId = MICROPHONE_DEFAULT_ID) }
                                menuExpanded = false
                                onReclaimFocus()
                            }
                            microphones.orEmpty().forEach { microphone ->
                                MicrophoneOption(microphone.label, settings.microphoneDeviceId == microphone.id) {
                                    edit { it.copy(microphoneDeviceId = microphone.id) }
                                    menuExpanded = false
                                    onReclaimFocus()
                                }
                            }
                            when {
                                scanning -> MicrophoneOption("Loading microphones…", active = false, enabled = false)
                                enumerationFailure != null -> MicrophoneOption(
                                    enumerationFailure ?: "Microphone list unavailable",
                                    active = false,
                                    enabled = false,
                                )
                                microphones.isNullOrEmpty() -> MicrophoneOption("No microphones found", active = false, enabled = false)
                            }
                        }
                    }
                }
            }
        }
        AppText(
            "Records audio from this computer microphone with the screen capture.",
            color = colors.td,
            fontSize = 10.sp,
        )
    }
    if (settings.microphoneDeviceId != MICROPHONE_OFF_ID) {
        AppText(
            "Nearby speaker audio may be picked up; microphone monitoring is off.",
            color = colors.td,
            fontSize = 10.sp,
            maxLines = 2,
        )
    }
}

@Composable
private fun MicrophoneOption(label: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit = {}) {
    val colors = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth(),
        hoverEnabled = enabled,
        onClick = onClick.takeIf { enabled },
    ) {
        AppText(
            label,
            color = when {
                !enabled -> colors.td
                active -> colors.ac
                else -> colors.tx
            },
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Unchecked and disabled (same greyed pattern as [KeepDeviceAudioCheck]) when this system's
 *  glibc is too old for the bundled FFmpeg natives ([NativeMediaSupport.available] false) — the
 *  actual start-time adaptation lives in [com.indagium.capture.adaptedToNativeMedia], this only
 *  keeps the checkbox from promising something the launch can't deliver. The stored value is left
 *  untouched, matching every other settings control here (see [CaptureStartOptions]'s own doc). */
@Composable
internal fun RecordVideoToFileCheck(
    settings: CaptureSettings,
    edit: CaptureSettingsEdit,
    nativeMediaSupport: NativeMediaSupport = NativeMediaSupport(available = true),
) {
    val supported = nativeMediaSupport.available
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CheckRow(
            settings.recordVideo && supported,
            { edit { it.copy(recordVideo = !it.recordVideo) } },
            modifier = Modifier.settingsAnchor("Record video to file"),
            enabled = supported,
        ) {
            AppText(
                "Record video to file",
                color = if (supported) tc().tx else tc().td,
                fontSize = 11.sp,
                maxLines = CHECK_LABEL_MAX_LINES,
                modifier = Modifier.weight(1f),
            )
        }
        if (!supported) NativeMediaUnsupportedHint(nativeMediaSupport)
    }
}

@Composable
private fun NativeMediaUnsupportedHint(nativeMediaSupport: NativeMediaSupport) {
    AppText(
        nativeMediaSupport.reason ?: "Video recording isn't available on this system.",
        color = tc().td,
        fontSize = 10.sp,
        maxLines = CAPTURE_HINT_MAX_LINES,
    )
}

/** Windows/Linux opt-in for the D3D11 / VAAPI+EGL hardware in-app mirror (see
 *  `CaptureSettings.hardwareMirror`). Greyed out when Device display isn't the in-app mirror, which
 *  is the only thing it affects, or when this system can't load the bundled media libraries at all
 *  (old-glibc Linux, see NativeMediaSupport.kt). Not shown on macOS, whose native mirror is always on. */
@Composable
internal fun HardwareMirrorCheck(
    settings: CaptureSettings,
    edit: CaptureSettingsEdit,
    nativeMediaSupport: NativeMediaSupport = NativeMediaSupport(available = true),
) {
    val enabled = nativeMediaSupport.available && settings.effectiveMirrorMode == CaptureMirrorMode.EMBEDDED
    CheckRow(
        settings.hardwareMirror && enabled,
        { edit { it.copy(hardwareMirror = !it.hardwareMirror) } },
        modifier = Modifier.settingsAnchor("Hardware-accelerated mirror (experimental)"),
        enabled = enabled,
    ) {
        AppText(
            "Hardware-accelerated mirror (experimental)",
            color = if (enabled) tc().tx else tc().td,
            fontSize = 11.sp,
            maxLines = CHECK_LABEL_MAX_LINES,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Device audio goes into the recorded file and the scrcpy window; the in-app mirror is silent.
 *  Whenever this is on, the device's own speaker is muted for the duration unless
 *  [KeepDeviceAudioCheck] is also on and the device qualifies — see [DeviceAudioMutingNotice]. */
@Composable
internal fun CaptureAudioCheck(settings: CaptureSettings, edit: CaptureSettingsEdit) {
    CheckRow(settings.audio, { edit { it.copy(audio = !it.audio) } }, modifier = Modifier.settingsAnchor("Capture audio")) {
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
        modifier = Modifier.settingsAnchor("Keep sound on the device (Android 13+)"),
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
    CheckRow(
        settings.includeBufferedLogs,
        { edit { it.copy(includeBufferedLogs = !it.includeBufferedLogs) } },
        modifier = Modifier.settingsAnchor("Include earlier device logs"),
    ) {
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

/**
 * Keeps the same three segments regardless of [nativeMediaSupport] — clicking "In-app mirror"
 * still stores that preference (so it takes effect again on a newer system, or once the user
 * fixes their glibc) — but when this system can't actually run it, the *selected* segment shows
 * what a launch will really use instead (see [com.indagium.capture.adaptedToNativeMedia]): EXTERNAL
 * when a scrcpy is available, Off when it isn't. Showing EMBEDDED as selected while every capture
 * silently opened a scrcpy window instead would be a control that lies about its own state; this
 * is the simplest fix that doesn't restructure the three-segment control itself.
 */
@Composable
internal fun CaptureDeviceDisplayControl(
    settings: CaptureSettings,
    edit: CaptureSettingsEdit,
    nativeMediaSupport: NativeMediaSupport = NativeMediaSupport(available = true),
    scrcpyAvailable: Boolean = true,
) {
    val storedMode = settings.effectiveMirrorMode
    val embeddedUnsupported = !nativeMediaSupport.available && storedMode == CaptureMirrorMode.EMBEDDED
    val effectiveMode = if (embeddedUnsupported) {
        if (scrcpyAvailable) CaptureMirrorMode.EXTERNAL else CaptureMirrorMode.DISABLED
    } else {
        storedMode
    }
    AppText("Device display", color = tc().td, fontSize = 10.sp, modifier = Modifier.settingsAnchor("Device display"))
    SegmentedControl(
        options = listOf("In-app mirror", "scrcpy window", "Off"),
        selectedIndices = setOf(
            when (effectiveMode) {
                CaptureMirrorMode.EMBEDDED -> 0
                CaptureMirrorMode.EXTERNAL -> 1
                CaptureMirrorMode.DISABLED -> 2
            },
        ),
        onToggle = { index -> edit { it.withMirrorMode(CaptureMirrorMode.entries[index]) } },
        modifier = Modifier.fillMaxWidth(),
        fillWidth = true,
    )
    if (embeddedUnsupported) {
        AppText(
            if (scrcpyAvailable) {
                "In-app mirror isn't available on this system; the scrcpy window is used instead."
            } else {
                "In-app mirror isn't available on this system, and scrcpy isn't installed; no device display is shown."
            },
            color = tc().td,
            fontSize = 10.sp,
            maxLines = CAPTURE_HINT_MAX_LINES,
        )
    }
}

/**
 * Quality preset picker for the screen recording plus the size it implies. The preset is derived
 * from the three stored numbers ([videoPreset]), so when the user has typed values in Settings that
 * match no preset ("Custom") no segment is selected and the caption spells the numbers out.
 * [onReclaimFocus] gives keyboard focus back to the screen's root key handler after a click — see
 * CLAUDE.md's note on `Modifier.clickable` stealing focus.
 */
@Composable
internal fun CaptureVideoQualityControl(
    settings: CaptureSettings,
    edit: CaptureSettingsEdit,
    onReclaimFocus: () -> Unit = {},
) {
    val colors = tc()
    val preset = settings.videoPreset()
    AppText("Video quality", color = colors.td, fontSize = 10.sp, modifier = Modifier.settingsAnchor("Video quality"))
    SegmentedControl(
        options = CaptureVideoPreset.entries.map { it.label },
        selectedIndices = setOfNotNull(preset?.let { CaptureVideoPreset.entries.indexOf(it) }),
        onToggle = { index ->
            edit { it.withVideoPreset(CaptureVideoPreset.entries[index]) }
            onReclaimFocus()
        },
        modifier = Modifier.fillMaxWidth(),
        fillWidth = true,
    )
    AppText(
        if (preset != null) {
            "${preset.maxSize} px · ${preset.maxFps} fps · ${preset.bitrateMbps} Mbps"
        } else {
            "Custom — ${settings.maxSize} px · ${settings.maxFps} fps · ${settings.bitrateMbps} Mbps"
        },
        color = colors.td,
        fontSize = 10.sp,
        maxLines = 1,
    )
    CaptureSizeEstimateLine(settings)
}

/** One-line "≈ 11 MB/min (up to 23) · 30 min ≈ 340 MB" for the current bitrate and audio choice. */
@Composable
internal fun CaptureSizeEstimateLine(settings: CaptureSettings) {
    AppText(
        formatCaptureSizeEstimate(settings.captureSizeEstimate()),
        color = tc().ts,
        fontSize = 10.sp,
        maxLines = 2,
    )
}

/** Mode selector, the Custom picker (three buffers per row) and one line saying what the mode does. */
@Composable
internal fun CaptureBufferModeControl(settings: CaptureSettings, edit: CaptureSettingsEdit) {
    val colors = tc()
    AppText("Buffer mode", color = colors.td, fontSize = 10.sp, modifier = Modifier.settingsAnchor("Buffer mode"))
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
    AppText("Buffers to capture", color = tc().td, fontSize = 10.sp, modifier = Modifier.settingsAnchor("Buffers to capture"))
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
