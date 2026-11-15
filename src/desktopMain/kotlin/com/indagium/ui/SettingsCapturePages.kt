package com.indagium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.indagium.capture.CaptureBufferMode
import com.indagium.capture.CaptureMirrorMode
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureVideoContainer
import com.indagium.capture.MICROPHONE_OFF_ID
import com.indagium.capture.captureFilenameTemplateError
import com.indagium.capture.captureSizeEstimate
import com.indagium.capture.effectiveMirrorMode
import com.indagium.capture.formatCaptureSizeEstimate
import com.indagium.capture.withMirrorMode

// Settings › Capture & video and its four drill-in pages (Tools, Limits & snapshots, Mark issue, Video playback). The main page holds the
// defaults the New tab's Before start panel starts from; the rest sits one click down. The controls themselves are shared with that panel
// (ui/CaptureSettingControls.kt), so both screens behave the same.

// The row descriptions the search index shares (SettingsSearchIndex.kt), so a result reads exactly like the row it points at.
internal const val CAPTURE_RECORD_SCREEN_HINT = "Save the device screen with the session so you can replay it beside the log."
internal const val CAPTURE_AUDIO_HINT = "Device audio goes into the recorded file and the scrcpy window; the in-app mirror is silent."
internal const val CAPTURE_KEEP_DEVICE_AUDIO_HINT = "Needs Android 13 or later. Keeps the phone's speaker on while its audio is captured."
internal const val CAPTURE_MICROPHONE_HINT = "Records audio from this computer's microphone with the screen capture."
internal const val CAPTURE_DEVICE_DISPLAY_HINT = "Where the live screen appears while capturing."
internal const val CAPTURE_CUSTOM_BUFFERS_HINT = "The logcat buffers that Custom mode captures."
internal const val CAPTURE_ADB_HINT = "Path to adb. Leave it blank to auto-detect."
internal const val CAPTURE_SCRCPY_HINT = "Optional. Only for the scrcpy window; recording and the in-app mirror work without it."
internal const val CAPTURE_SCRCPY_DETAIL = "Optional. Only used when Device display is set to a scrcpy window; recording and the in-app mirror work without it."
internal const val CAPTURE_HARDWARE_MIRROR_HINT = "Experimental. Decodes the in-app mirror on the GPU (D3D11 on Windows, VAAPI on Linux). " +
    "Applies only while the device is shown in the in-app mirror."
internal const val CAPTURE_TOOLS_CHECK_HINT = "Run it again after changing a path. Live-session diagnostics appear on the capture strip."
internal const val CAPTURE_MAX_SIZE_HINT = "Longest side in pixels; larger screens are scaled down. 0 keeps the full device resolution."
internal const val CAPTURE_MAX_FPS_HINT = "Frame-rate cap for the screen recording."
internal const val CAPTURE_BITRATE_HINT = "Video bitrate of the screen recording. Typing numbers here sets the quality preset to Custom."
internal const val CAPTURE_SAVED_FORMAT_HINT = "Used by Save snapshot and Save ZIP, not the live recording. MP4 opens more widely."
internal const val CAPTURE_SAVED_FORMAT_DETAIL = "Applies to Save snapshot and Save ZIP, not the live recording. MP4 opens more widely; " +
    "capturing with audio still exports as MKV."
internal const val CAPTURE_SESSION_LIMIT_HINT = "Largest size a single capture session may grow to."
internal const val CAPTURE_RESERVE_HINT = "Free disk space left untouched while capturing."
internal const val CAPTURE_FILENAME_TEMPLATE_HINT = "Placeholders: {device}, {start}, {range}, {counter}."
internal const val CAPTURE_LABEL_HINT = "Stored in the capture archive."
internal const val CAPTURE_MARKER_BEFORE_HINT = "Log window kept before pressing Mark issue."
internal const val CAPTURE_MARKER_AFTER_HINT = "Log window kept after pressing Mark issue."
internal const val CAPTURE_MARKER_SCREENSHOT_HINT = "Adds a device screenshot to the marker note."
internal const val CAPTURE_FOLLOW_READOUT_HINT = "Shows a line under the video transport bar that says what Follow is doing."
internal const val CAPTURE_FOLLOW_READOUT_DETAIL = "Shows the “video → log → holding at …” diagnostic line under the video transport bar, explaining exactly " +
    "what Follow is doing at the current playhead position."
internal const val CAPTURE_DOUBLE_CLICK_HINT = "The default for a newly linked log and video anchor. Each link can still be switched from its header."
internal const val CAPTURE_DOUBLE_CLICK_DETAIL = "The default for a newly linked log and video anchor. Each video link can then be toggled " +
    "independently from its header; this does not affect existing links or Follow."
internal const val CAPTURE_LIVE_VOLUME_HINT = "Volume of the in-app mirror's speaker toggle: device audio played live on this computer."

/** Test tags of the Capture & video pages' controls that have no tag of their own. */
internal object CapturePageTags {
    const val RECORD_SCREEN = "capture-record-screen"
    const val VIDEO_QUALITY = "capture-video-quality"
    const val VIDEO_CODEC = "capture-video-codec"
    const val CAPTURE_AUDIO = "capture-audio"
    const val KEEP_DEVICE_AUDIO = "capture-keep-device-audio"
    const val MICROPHONE = "capture-microphone"
    const val DEVICE_DISPLAY = "capture-device-display"
    const val BUFFER_MODE = "capture-buffer-mode"
    const val EARLIER_LOGS = "capture-earlier-logs"
    const val AVAILABILITY_MARKERS = "capture-availability-markers"
    const val UNAVAILABLE_FRAMES = "capture-unavailable-frames"
    const val HARDWARE_MIRROR = "capture-hardware-mirror"
    const val SAVED_VIDEO_FORMAT = "capture-saved-video-format"
    const val MARKER_SCREENSHOT = "capture-marker-screenshot"
    const val FOLLOW_READOUT = "capture-follow-readout"
    const val DOUBLE_CLICK_SEEKS = "capture-double-click-seeks"

    /** The "More" row of the main page that opens [page]. */
    fun drillRow(page: SettingsPage): String = "capture-drill-${page.name}"
}

private val DRILL_SUMMARIES = mapOf(
    SettingsPage.CaptureLimits to "Session size, free-space reserve, file names",
    SettingsPage.CaptureMarkIssue to "Log window before and after, screenshot",
    SettingsPage.CaptureVideoPlayback to "Follow readout, double-click seeking, live audio",
)

/** The invalid-settings keys ([invalidCaptureSettings]) each drill-in page shows, so the main page can flag a page that needs a fix. */
private val DRILL_VALIDATION_KEYS = mapOf(
    SettingsPage.CaptureTools to setOf("adbPath", "scrcpyPath"),
    SettingsPage.CaptureLimits to setOf("maxSize", "maxFps", "bitrateMbps", "sessionLimit", "freeSpaceReserve", "filenameTemplate"),
    SettingsPage.CaptureMarkIssue to setOf("markerPreMs", "markerPostMs"),
    SettingsPage.CaptureVideoPlayback to setOf("liveAudioVolume"),
)

private const val NEEDS_ATTENTION = "Needs attention"
private const val AUTO_DETECTED_PLACEHOLDER = "Auto-detected"

private fun captureEdit(state: AppState): CaptureSettingsEdit = { transform ->
    state.updateSettings { it.copy(captureSettings = transform(it.captureSettings)) }
}

/**
 * Settings › Capture & video: the defaults of the New tab's Before start panel (recording, logcat), the device-availability switches, and
 * four rows that open the drill-in pages. [onOpenPage] switches the dialog to one of them.
 */
@Composable
internal fun CaptureVideoSettingsSection(state: AppState, reclaimFocus: () -> Unit, onOpenPage: (SettingsPage) -> Unit) {
    val settings = state.settings.captureSettings
    val edit = captureEdit(state)
    val nativeMediaSupport = state.captureNativeMediaSupport
    val scrcpyAvailable = state.captureToolResolution?.scrcpyPath != null
    val supported = nativeMediaSupport.available

    // The group anchor is the "Before start" search entry: this group is what the New tab's panel starts from.
    SettingsGroup("Recording", modifier = Modifier.settingsAnchor("Before start")) {
        SettingsRow(
            "Record the screen",
            if (supported) CAPTURE_RECORD_SCREEN_HINT else nativeMediaUnsupportedText(nativeMediaSupport),
            anchor = "Record video to file",
            enabled = supported,
        ) {
            SettingsToggle(
                checked = settings.recordVideo && supported,
                label = "Record the screen",
                onChange = { next -> edit { it.copy(recordVideo = next) } },
                reclaimFocus = reclaimFocus,
                enabled = supported,
                testTag = CapturePageTags.RECORD_SCREEN,
            )
        }
        // Only while video is recorded: with Record the screen off none of these numbers apply.
        if (settings.recordVideo) {
            SettingsRow(
                "Video quality",
                captureVideoQualityCaption(settings) + "\n" + formatCaptureSizeEstimate(settings.captureSizeEstimate()),
                indent = true,
            ) {
                CaptureVideoQualitySegments(settings, edit, reclaimFocus, Modifier.testTag(CapturePageTags.VIDEO_QUALITY), settingsStyle = true)
            }
            SettingsRow(
                "Recording codec",
                captureVideoCodecSupportText(settings.videoCodec, null, null),
                indent = true,
            ) {
                CaptureVideoCodecSegments(settings, edit, reclaimFocus, Modifier.testTag(CapturePageTags.VIDEO_CODEC), settingsStyle = true)
            }
        }
        val shownMode = effectiveDeviceDisplayMode(settings, nativeMediaSupport, scrcpyAvailable)
        SettingsRow(
            "Show the device",
            CAPTURE_DEVICE_DISPLAY_HINT + deviceDisplayNote(settings, nativeMediaSupport, scrcpyAvailable)?.let { "\n$it" }.orEmpty(),
            anchor = "Device display",
        ) {
            SettingsDropdown(
                options = CaptureMirrorMode.entries,
                selected = shownMode,
                label = ::deviceDisplayLabel,
                onSelect = { mode -> edit { it.withMirrorMode(mode) } },
                testTag = CapturePageTags.DEVICE_DISPLAY,
                onInteraction = reclaimFocus,
            )
        }
    }

    // Sound and the microphone are not part of the screen recording (the screen can be off while sound is kept), so they are a group of
    // their own rather than rows indented under "Record the screen".
    SettingsGroup("Audio") {
        SettingsRow("Capture audio", CAPTURE_AUDIO_HINT) {
            SettingsToggle(
                checked = settings.audio,
                label = "Capture audio",
                onChange = { next -> edit { it.copy(audio = next) } },
                reclaimFocus = reclaimFocus,
                testTag = CapturePageTags.CAPTURE_AUDIO,
            )
        }
        // Dependent on Capture audio: nothing to keep on the device while the sound is not captured. Dimmed and locked, the choice is kept.
        SettingsRow(
            "Keep sound on the device",
            CAPTURE_KEEP_DEVICE_AUDIO_HINT,
            indent = true,
            enabled = settings.audio,
            detail = CAPTURE_KEEP_DEVICE_AUDIO_HINT + "\n" + DEVICE_AUDIO_MUTING_TEXT,
        ) {
            SettingsToggle(
                checked = settings.keepDeviceAudio,
                label = "Keep sound on the device",
                onChange = { next -> edit { it.copy(keepDeviceAudio = next) } },
                reclaimFocus = reclaimFocus,
                enabled = settings.audio,
                testTag = CapturePageTags.KEEP_DEVICE_AUDIO,
            )
        }
        SettingsRow(
            "Microphone",
            CAPTURE_MICROPHONE_HINT + if (settings.microphoneDeviceId != MICROPHONE_OFF_ID) "\n$MICROPHONE_PICKUP_NOTE" else "",
        ) {
            CaptureMicrophonePicker(
                settings,
                edit,
                reclaimFocus,
                Modifier.width(SETTINGS_FIELD_SHORT).testTag(CapturePageTags.MICROPHONE),
                settingsStyle = true,
            )
        }
    }

    SettingsGroup("Logcat") {
        SettingsRow("Buffers", captureBufferModeHint(settings), anchor = "Buffer mode") {
            CaptureBufferModeSegments(settings, edit, Modifier.testTag(CapturePageTags.BUFFER_MODE), settingsStyle = true)
        }
        if (settings.bufferMode == CaptureBufferMode.CUSTOM) {
            SettingsStackedRow("Buffers to capture", CAPTURE_CUSTOM_BUFFERS_HINT, indent = true) {
                CaptureBufferChecks(settings, edit, settingsStyle = true)
            }
        }
        SettingsRow("Include earlier device logs", EARLIER_DEVICE_LOGS_TEXT) {
            SettingsToggle(
                checked = settings.includeBufferedLogs,
                label = "Include earlier device logs",
                onChange = { next -> edit { it.copy(includeBufferedLogs = next) } },
                reclaimFocus = reclaimFocus,
                testTag = CapturePageTags.EARLIER_LOGS,
            )
        }
    }

    // Not in the design's drill-in buckets, so it stays here with the recording choices it belongs to.
    SettingsGroup("Device availability") {
        SettingsRow(
            "Write device availability markers to the log",
            "Offline duration is always kept in the capture timeline.",
            anchor = "Write device availability markers",
        ) {
            SettingsToggle(
                checked = settings.writeDeviceAvailabilityMarkers,
                label = "Write device availability markers to the log",
                onChange = { next -> edit { it.copy(writeDeviceAvailabilityMarkers = next) } },
                reclaimFocus = reclaimFocus,
                testTag = CapturePageTags.AVAILABILITY_MARKERS,
            )
        }
        SettingsRow(
            "Show unavailable device periods in playback and exported video",
            "Markers and playback cards can be hidden independently.",
            anchor = "Show unavailable frames",
        ) {
            SettingsToggle(
                checked = settings.showUnavailableFrames,
                label = "Show unavailable device periods in playback and exported video",
                onChange = { next -> edit { it.copy(showUnavailableFrames = next) } },
                reclaimFocus = reclaimFocus,
                testTag = CapturePageTags.UNAVAILABLE_FRAMES,
            )
        }
    }

    val invalid = invalidCaptureSettings(settings)
    SettingsGroup("More") {
        listOf(SettingsPage.CaptureTools, SettingsPage.CaptureLimits, SettingsPage.CaptureMarkIssue, SettingsPage.CaptureVideoPlayback)
            .forEach { page ->
                val needsAttention = DRILL_VALIDATION_KEYS[page].orEmpty().any { it in invalid }
                SettingsDrillRow(
                    icon = page.icon,
                    label = page.title,
                    summary = if (needsAttention) NEEDS_ATTENTION else drillSummary(page, settings),
                    onClick = {
                        onOpenPage(page)
                        reclaimFocus()
                    },
                    anchor = CapturePageTags.drillRow(page),
                    modifier = Modifier.testTag(CapturePageTags.drillRow(page)),
                )
            }
    }
}

private fun drillSummary(page: SettingsPage, settings: CaptureSettings): String? = when (page) {
    SettingsPage.CaptureTools ->
        if (settings.adbPath.isBlank() && settings.scrcpyPath.isBlank()) "adb and scrcpy · auto-detected" else "adb and scrcpy · custom paths"
    else -> DRILL_SUMMARIES[page]
}

private fun deviceDisplayLabel(mode: CaptureMirrorMode): String = when (mode) {
    CaptureMirrorMode.EMBEDDED -> "In-app mirror"
    CaptureMirrorMode.EXTERNAL -> "scrcpy window"
    CaptureMirrorMode.DISABLED -> "Off"
}

/** Settings › Capture & video › Tools: the adb and scrcpy executables, and (Windows and Linux) the hardware-accelerated in-app mirror. */
@Composable
internal fun CaptureToolsSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    val settings = state.settings.captureSettings
    val edit = captureEdit(state)
    val toolResolution = state.captureToolResolution
    val invalid = invalidCaptureSettings(settings)
    SettingsGroup("Executables", modifier = Modifier.settingsAnchor("Tools")) {
        CaptureTextRow(
            label = "ADB path",
            value = settings.adbPath,
            onValue = { next -> edit { it.copy(adbPath = next) } },
            description = CAPTURE_ADB_HINT,
            placeholder = AUTO_DETECTED_PLACEHOLDER,
            onBrowse = state::browseCaptureAdb,
            invalid = "adbPath" in invalid,
            invalidMessage = "path does not point to a file",
            effectivePath = settings.adbPath.takeIf(String::isBlank)?.let { toolResolution?.adbPath },
        )
        // Recording and the embedded (in-app) mirror both stream the device over adb directly - via a bundled scrcpy *server* jar pushed and
        // run with `app_process`, not this host scrcpy executable - so neither needs this path configured at all. It is only used when
        // Device display is set to "scrcpy window" for a capture.
        CaptureTextRow(
            label = "scrcpy path",
            value = settings.scrcpyPath,
            onValue = { next -> edit { it.copy(scrcpyPath = next) } },
            description = CAPTURE_SCRCPY_HINT,
            detail = CAPTURE_SCRCPY_DETAIL,
            placeholder = AUTO_DETECTED_PLACEHOLDER,
            onBrowse = state::browseCaptureScrcpy,
            invalid = "scrcpyPath" in invalid,
            invalidMessage = "path does not point to a file",
            effectivePath = settings.scrcpyPath.takeIf(String::isBlank)?.let { toolResolution?.scrcpyPath },
            effectiveMessage = if (settings.scrcpyPath.isBlank() && toolResolution != null && toolResolution.scrcpyPath == null) {
                "No scrcpy executable detected. Recording and the in-app mirror still work without it; " +
                    "choose a scrcpy window only after installing scrcpy."
            } else {
                null
            },
        )
        // The buttons sit on the right like every action row; the tool status the last check printed stays under them.
        Column(Modifier.fillMaxWidth()) {
            SettingsRow("Tool detection", CAPTURE_TOOLS_CHECK_HINT) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsButton("Recheck tools", state::recheckCaptureTools)
                    SettingsButton("Install guidance", state::openCaptureInstallGuidance, ButtonVariant.Secondary)
                }
            }
            state.captureToolStatus?.let { status -> SettingsNoteRow(status) }
        }
    }
    // The macOS in-app mirror is always the native one, so there is nothing to opt into there.
    if (!isMacOs) {
        val enabled = state.captureNativeMediaSupport.available && settings.effectiveMirrorMode == CaptureMirrorMode.EMBEDDED
        SettingsGroup("In-app mirror") {
            SettingsRow("Hardware-accelerated mirror", CAPTURE_HARDWARE_MIRROR_HINT, enabled = enabled) {
                SettingsToggle(
                    checked = settings.hardwareMirror && enabled,
                    label = "Hardware-accelerated mirror",
                    onChange = { next -> edit { it.copy(hardwareMirror = next) } },
                    reclaimFocus = reclaimFocus,
                    enabled = enabled,
                    testTag = CapturePageTags.HARDWARE_MIRROR,
                )
            }
        }
    }
}

/** Settings › Capture & video › Limits & snapshots: the recording's numbers and saved format, how big a session may grow, snapshot file names. */
@Composable
internal fun CaptureLimitsSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    val settings = state.settings.captureSettings
    val edit = captureEdit(state)
    SettingsGroup("Screen recording", modifier = Modifier.settingsAnchor("Screen recording")) {
        // Applies to the recorded file, the in-app mirror and the scrcpy window alike (scrcpy max_size).
        CaptureNumberRow(
            "Max size", settings.maxSize, MIN_CAPTURE_SIZE..MAX_CAPTURE_SIZE, "px",
            description = CAPTURE_MAX_SIZE_HINT,
        ) { next -> edit { it.copy(maxSize = next) } }
        CaptureNumberRow(
            "Max FPS", settings.maxFps, MIN_CAPTURE_FPS..MAX_CAPTURE_FPS, "fps",
            description = CAPTURE_MAX_FPS_HINT,
        ) { next -> edit { it.copy(maxFps = next) } }
        // The preset picker is on the main page; typing numbers here simply turns it to Custom. The size those numbers imply sits beside them.
        CaptureNumberRow(
            "Bitrate", settings.bitrateMbps, MIN_CAPTURE_BITRATE_MBPS..MAX_CAPTURE_BITRATE_MBPS, "Mbps",
            description = CAPTURE_BITRATE_HINT + "\n" + formatCaptureSizeEstimate(settings.captureSizeEstimate()),
            anchor = "Bitrate Mbps",
        ) { next -> edit { it.copy(bitrateMbps = next) } }
        // Recording itself always stays Matroska (crash-safe while it's still growing - see FfmpegCaptureVideoExporter's own module doc); this
        // only picks the container Save snapshot/Save ZIP remux into. MP4 plays natively in more places (QuickTime, phones, browsers) but audio
        // capture isn't supported there yet, hence the second sentence.
        SettingsRow("Saved video format", CAPTURE_SAVED_FORMAT_HINT, detail = CAPTURE_SAVED_FORMAT_DETAIL) {
            SettingsSegmented(
                options = listOf("MP4", "MKV"),
                selectedIndices = setOf(CaptureVideoContainer.entries.indexOf(settings.videoContainer)),
                onToggle = { index ->
                    edit { it.copy(videoContainer = CaptureVideoContainer.entries[index]) }
                    reclaimFocus()
                },
                modifier = Modifier.testTag(CapturePageTags.SAVED_VIDEO_FORMAT),
            )
        }
    }
    SettingsGroup("Storage", modifier = Modifier.settingsAnchor("Storage")) {
        CaptureLongRow(
            "Session limit", settings.sessionLimitBytes / CAPTURE_GIB, 1L..Long.MAX_VALUE / CAPTURE_GIB, "GiB",
            description = CAPTURE_SESSION_LIMIT_HINT,
            anchor = "Session limit GiB",
        ) { next -> edit { it.copy(sessionLimitBytes = next * CAPTURE_GIB) } }
        CaptureLongRow(
            "Reserve", settings.freeSpaceReserveBytes / CAPTURE_GIB, 0L..Long.MAX_VALUE / CAPTURE_GIB, "GiB",
            description = CAPTURE_RESERVE_HINT,
            anchor = "Reserve GiB",
        ) { next -> edit { it.copy(freeSpaceReserveBytes = next * CAPTURE_GIB) } }
    }
    SettingsGroup("Snapshot files", modifier = Modifier.settingsAnchor("Snapshot files")) {
        val templateError = captureFilenameTemplateError(settings.filenameTemplate)
        CaptureTextRow(
            label = "Filename template",
            value = settings.filenameTemplate,
            onValue = { next -> edit { it.copy(filenameTemplate = next) } },
            description = CAPTURE_FILENAME_TEMPLATE_HINT,
            invalid = templateError != null,
            invalidMessage = templateError ?: "template is invalid",
        )
        CaptureTextRow(
            label = "Label",
            value = settings.label,
            onValue = { next -> edit { it.copy(label = next) } },
            description = CAPTURE_LABEL_HINT,
        )
    }
}

/** Settings › Capture & video › Mark issue: the log window the Mark issue button keeps, and whether it takes a screenshot. */
@Composable
internal fun CaptureMarkIssueSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    val settings = state.settings.captureSettings
    val edit = captureEdit(state)
    SettingsGroup("Log window", modifier = Modifier.settingsAnchor("Mark issue")) {
        CaptureLongRow(
            "Before the press", settings.markerPreMs / CAPTURE_MARKER_MS_PER_SECOND,
            MIN_MARKER_WINDOW_SECONDS.toLong()..MAX_MARKER_WINDOW_SECONDS.toLong(), "s",
            description = CAPTURE_MARKER_BEFORE_HINT,
            anchor = "Before, seconds",
        ) { next -> edit { it.copy(markerPreMs = next * CAPTURE_MARKER_MS_PER_SECOND) } }
        CaptureLongRow(
            "After the press", settings.markerPostMs / CAPTURE_MARKER_MS_PER_SECOND,
            MIN_MARKER_WINDOW_SECONDS.toLong()..MAX_MARKER_WINDOW_SECONDS.toLong(), "s",
            description = CAPTURE_MARKER_AFTER_HINT,
            anchor = "After, seconds",
        ) { next -> edit { it.copy(markerPostMs = next * CAPTURE_MARKER_MS_PER_SECOND) } }
    }
    SettingsGroup("Note") {
        SettingsRow("Take a screenshot on Mark issue", CAPTURE_MARKER_SCREENSHOT_HINT) {
            SettingsToggle(
                checked = settings.markerScreenshot,
                label = "Take a screenshot on Mark issue",
                onChange = { next -> edit { it.copy(markerScreenshot = next) } },
                reclaimFocus = reclaimFocus,
                testTag = CapturePageTags.MARKER_SCREENSHOT,
            )
        }
        // markerNotesInSnapshot has no reader yet (Phase 4, the snapshot export, is its consumer), so it has no row: a switch that does nothing
        // would only mislead. The stored value is kept, and the row and its search entry come back with the export that reads it.
    }
}

/** Settings › Capture & video › Video playback: the Follow readout and link defaults (app settings, not capture settings), and the live mirror's volume. */
@Composable
internal fun CaptureVideoPlaybackSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    val settings = state.settings
    val edit = captureEdit(state)
    SettingsGroup("Playback") {
        SettingsRow("Video follow readout", CAPTURE_FOLLOW_READOUT_HINT, detail = CAPTURE_FOLLOW_READOUT_DETAIL) {
            SettingsToggle(
                checked = settings.showVideoFollowReadout,
                label = "Video follow readout",
                onChange = { next -> state.updateSettings { it.copy(showVideoFollowReadout = next) } },
                reclaimFocus = reclaimFocus,
                testTag = CapturePageTags.FOLLOW_READOUT,
            )
        }
        SettingsRow(
            "Double-click seeks on new links",
            CAPTURE_DOUBLE_CLICK_HINT,
            anchor = "New video links:\ndouble-click seeks",
            detail = CAPTURE_DOUBLE_CLICK_DETAIL,
        ) {
            SettingsToggle(
                checked = settings.enableDoubleClickVideoSeekOnLink,
                label = "Double-click seeks on new links",
                onChange = { next -> state.updateSettings { it.copy(enableDoubleClickVideoSeekOnLink = next) } },
                reclaimFocus = reclaimFocus,
                testTag = CapturePageTags.DOUBLE_CLICK_SEEKS,
            )
        }
    }
    SettingsGroup("Live mirror") {
        CaptureNumberRow(
            "Live audio volume", settings.captureSettings.liveAudioVolume, MIN_LIVE_AUDIO_VOLUME..MAX_LIVE_AUDIO_VOLUME, "%",
            description = CAPTURE_LIVE_VOLUME_HINT,
            anchor = "Live audio volume %",
        ) { next -> edit { it.copy(liveAudioVolume = next) } }
    }
}
