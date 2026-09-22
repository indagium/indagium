package com.indagium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.capture.CaptureBufferMode
import com.indagium.capture.CaptureSettings
import com.indagium.capture.captureFilenameTemplateError
import java.io.File

internal const val CAPTURE_GIB = 1024L * 1024L * 1024L
internal const val MIN_CAPTURE_SIZE = 0
internal const val MAX_CAPTURE_SIZE = 8_192
internal const val MIN_CAPTURE_FPS = 1
internal const val MAX_CAPTURE_FPS = 240
internal const val MIN_CAPTURE_BITRATE_MBPS = 1
internal const val MAX_CAPTURE_BITRATE_MBPS = 500

/** Returns the settings fields that would prevent starting a capture. */
internal fun invalidCaptureSettings(settings: CaptureSettings): Set<String> = buildSet {
    if (settings.adbPath.isNotBlank() && !File(settings.adbPath).isFile) add("adbPath")
    if (settings.scrcpyPath.isNotBlank() && !File(settings.scrcpyPath).isFile) add("scrcpyPath")
    if (settings.maxSize !in MIN_CAPTURE_SIZE..MAX_CAPTURE_SIZE) add("maxSize")
    if (settings.maxFps !in MIN_CAPTURE_FPS..MAX_CAPTURE_FPS) add("maxFps")
    if (settings.bitrateMbps !in MIN_CAPTURE_BITRATE_MBPS..MAX_CAPTURE_BITRATE_MBPS) add("bitrateMbps")
    if (settings.sessionLimitBytes <= 0L) add("sessionLimit")
    if (settings.freeSpaceReserveBytes < 0L) add("freeSpaceReserve")
    if (captureFilenameTemplateError(settings.filenameTemplate) != null) add("filenameTemplate")
    if (settings.bufferMode == CaptureBufferMode.CUSTOM && settings.buffers.isEmpty()) add("buffers")
}

/** Pure presentation rule shared by the settings UI and its focused tests. */
internal fun visibleCaptureBuffers(settings: CaptureSettings): List<String> =
    settings.buffers.takeIf { settings.bufferMode == CaptureBufferMode.CUSTOM }.orEmpty()

private val CAPTURE_BUFFER_NAMES = listOf("main", "system", "crash", "kernel", "events", "radio")

// Layout follows the design: Tools full width at the top (it's the one group whose fields —
// paths, the recheck/install actions, tool status — genuinely want the full row), then two
// side-by-side pairs below it (Log buffers | Screen recording, Storage | Snapshot files) so the
// section reads like the rest of Settings (AppearanceSettingsSection, EditorBehaviorSettingsSection,
// AutomationSettingsSection in SettingsDialog.kt) instead of a stack of bordered cards found nowhere
// else in this dialog. The "Diagnostics" panel this replaces had no fields of its own beyond a
// second "Recheck tools" button and a blurb — that blurb now lives as trailing help text under
// Tools' own recheck/install row instead of repeating the group.
@Composable
internal fun CaptureSettingsSection(state: AppState) {
    val tc = tc()
    val settings = state.settings.captureSettings
    val toolResolution = state.captureToolResolution
    val invalid = invalidCaptureSettings(settings)
    val update: (CaptureSettings) -> Unit = { next -> state.updateSettings { it.copy(captureSettings = next) } }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("Capture", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            AppText(
                "Capture Android logs and optional video into a retained session. Changes apply immediately.",
                color = tc.td,
                fontSize = 11.sp,
                maxLines = 3,
            )
        }

        CaptureToolsGroup(state, settings, toolResolution, invalid, update)

        Divider()
        CapturePairedGroupRow(
            first = { CaptureLogBuffersGroup(settings, update) },
            second = { CaptureScreenRecordingGroup(settings, update) },
        )

        Divider()
        CapturePairedGroupRow(
            first = { CaptureStorageGroup(settings, update) },
            second = { CaptureSnapshotFilesGroup(settings, update) },
        )
    }
}

/** Two equal-width tracks, top-aligned so a taller left group (e.g. Custom buffer checkboxes)
 * doesn't shove the right group's content down — matches the top alignment EditorBehaviorGridRow
 * uses for its own multi-column rows in SettingsDialog.kt. */
@Composable
private fun CapturePairedGroupRow(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { first() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { second() }
    }
}

/** Matches the group-label idiom every reference section uses directly (e.g. AutomationSettingsSection's
 * "Connection info" in SettingsDialog.kt) rather than a bordered panel title. */
@Composable
private fun CaptureGroupLabel(text: String) {
    AppText(text, color = tc().td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun CaptureToolsGroup(
    state: AppState,
    settings: CaptureSettings,
    toolResolution: CaptureToolResolution?,
    invalid: Set<String>,
    update: (CaptureSettings) -> Unit,
) {
    val tc = tc()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CaptureGroupLabel("Tools")
        CapturePathField(
            label = "ADB path",
            value = settings.adbPath,
            onValue = { update(settings.copy(adbPath = it)) },
            onBrowse = state::browseCaptureAdb,
            invalid = "adbPath" in invalid,
            invalidMessage = "path does not point to a file",
            effectivePath = settings.adbPath.takeIf(String::isBlank)?.let { toolResolution?.adbPath },
        )
        // Recording and the embedded (in-app) mirror both stream the device over adb directly —
        // via a bundled scrcpy *server* jar pushed and run with `app_process`, not this host
        // scrcpy executable — so neither needs this path configured at all. It's only read by the
        // separate "Open scrcpy mirror" action, which opens a real, visible scrcpy window.
        CapturePathField(
            label = "scrcpy path (optional — native mirror window only)",
            value = settings.scrcpyPath,
            onValue = { update(settings.copy(scrcpyPath = it)) },
            onBrowse = state::browseCaptureScrcpy,
            invalid = "scrcpyPath" in invalid,
            invalidMessage = "path does not point to a file",
            effectivePath = settings.scrcpyPath.takeIf(String::isBlank)?.let { toolResolution?.scrcpyPath },
            effectiveMessage = if (settings.scrcpyPath.isBlank() && toolResolution != null && toolResolution.scrcpyPath == null) {
                "No scrcpy executable detected. Recording and the embedded mirror still work without it; " +
                    "only the separate native \"Open scrcpy mirror\" window needs it installed."
            } else {
                null
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppButton("Recheck tools", state::recheckCaptureTools)
            AppButton("Install guidance", state::openCaptureInstallGuidance, ButtonVariant.Secondary)
        }
        state.captureToolStatus?.let {
            AppText(it, color = tc.ts, fontSize = 10.sp, fontFamily = MONO, maxLines = 4)
        }
        AppText(
            "Recheck tools after changing a path. Live-session diagnostics appear on the capture strip.",
            color = tc.td,
            fontSize = 10.sp,
            maxLines = 2,
        )
    }
}

@Composable
private fun CaptureLogBuffersGroup(settings: CaptureSettings, update: (CaptureSettings) -> Unit) {
    val tc = tc()
    CaptureGroupLabel("Log buffers")
    val modes = CaptureBufferMode.entries
    CompactSetting("Mode", Modifier.fillMaxWidth()) {
        SegmentedControl(
            options = listOf("Default", "All", "Custom…"),
            selectedIndices = setOf(modes.indexOf(settings.bufferMode)),
            onToggle = { index -> update(settings.copy(bufferMode = modes[index])) },
            modifier = Modifier.fillMaxWidth(),
            fillWidth = true,
        )
    }
    AppText(
        when (settings.bufferMode) {
            CaptureBufferMode.DEFAULT -> "Use adb's own default buffers. No -b flag is sent."
            CaptureBufferMode.ALL -> "Capture every buffer the device exposes (-b all)."
            CaptureBufferMode.CUSTOM -> "Choose the buffers explicitly; this is useful for excluding noisy radio logs."
        },
        color = tc.td,
        fontSize = 10.sp,
    )
    if (settings.bufferMode == CaptureBufferMode.CUSTOM) {
        CAPTURE_BUFFER_NAMES.forEach { name ->
            val checked = name in settings.buffers
            CheckRow(checked, {
                update(
                    settings.copy(
                        buffers = if (checked) settings.buffers - name else (settings.buffers + name).distinct(),
                    ),
                )
            }) {
                AppText(name, fontSize = 11.sp)
            }
        }
        if (settings.buffers.isEmpty()) {
            AppText("Choose at least one buffer for Custom mode.", color = DANGER_RED, fontSize = 10.sp)
        }
    }
    CheckRow(settings.includeBufferedLogs, { update(settings.copy(includeBufferedLogs = !settings.includeBufferedLogs)) }) {
        AppText("Include buffered logs before capture starts", fontSize = 11.sp)
    }
}

@Composable
private fun CaptureScreenRecordingGroup(settings: CaptureSettings, update: (CaptureSettings) -> Unit) {
    CaptureGroupLabel("Screen recording")
    CheckRow(settings.recordVideo, { update(settings.copy(recordVideo = !settings.recordVideo)) }) {
        AppText("Record video", fontSize = 11.sp)
    }
    CheckRow(settings.mirror, { update(settings.copy(mirror = !settings.mirror)) }) {
        AppText("Mirror device", fontSize = 11.sp)
    }
    CheckRow(settings.audio, { update(settings.copy(audio = !settings.audio)) }) {
        AppText("Capture audio", fontSize = 11.sp)
    }
    CaptureNumericField("Max size", settings.maxSize.toString(), MIN_CAPTURE_SIZE..MAX_CAPTURE_SIZE) {
        update(settings.copy(maxSize = it))
    }
    CaptureNumericField("Max FPS", settings.maxFps.toString(), MIN_CAPTURE_FPS..MAX_CAPTURE_FPS) {
        update(settings.copy(maxFps = it))
    }
    CaptureNumericField("Bitrate Mbps", settings.bitrateMbps.toString(), MIN_CAPTURE_BITRATE_MBPS..MAX_CAPTURE_BITRATE_MBPS) {
        update(settings.copy(bitrateMbps = it))
    }
}

@Composable
private fun CaptureStorageGroup(settings: CaptureSettings, update: (CaptureSettings) -> Unit) {
    CaptureGroupLabel("Storage")
    CaptureLongField("Session limit GiB", settings.sessionLimitBytes / CAPTURE_GIB, 1L..Long.MAX_VALUE / CAPTURE_GIB) {
        update(settings.copy(sessionLimitBytes = it * CAPTURE_GIB))
    }
    CaptureLongField("Reserve GiB", settings.freeSpaceReserveBytes / CAPTURE_GIB, 0L..Long.MAX_VALUE / CAPTURE_GIB) {
        update(settings.copy(freeSpaceReserveBytes = it * CAPTURE_GIB))
    }
}

@Composable
private fun CaptureSnapshotFilesGroup(settings: CaptureSettings, update: (CaptureSettings) -> Unit) {
    val tc = tc()
    CaptureGroupLabel("Snapshot files")
    CapturePathField(
        label = "Filename template",
        value = settings.filenameTemplate,
        onValue = { update(settings.copy(filenameTemplate = it)) },
        invalid = captureFilenameTemplateError(settings.filenameTemplate) != null,
        invalidMessage = captureFilenameTemplateError(settings.filenameTemplate) ?: "template is invalid",
    )
    CapturePathField("Label", settings.label, { update(settings.copy(label = it)) })
    AppText(
        "Placeholders: {device}, {start}, {range}, {counter}. The label is stored in the capture archive.",
        color = tc.td,
        fontSize = 10.sp,
    )
}

/** Label + inline text field + optional Browse, the same shape as AppearanceSettingsSection's
 * "Default save folder" row in SettingsDialog.kt — a group label above a field row, not a bordered
 * box. Validation surfaces as red label text (as CompactSetting's own callers do for range/format
 * errors elsewhere in Settings), not a bespoke field border. */
@Composable
private fun CapturePathField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    onBrowse: (() -> Unit)? = null,
    invalid: Boolean = false,
    invalidMessage: String = "value is invalid",
    effectivePath: String? = null,
    effectiveMessage: String? = null,
) {
    val tc = tc()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        AppText(
            label + if (invalid) " — $invalidMessage" else "",
            color = if (invalid) DANGER_RED else tc.td,
            fontSize = 10.sp,
            fontFamily = UI,
            fontWeight = FontWeight.SemiBold,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InlineField(value, onValue, modifier = Modifier.weight(1f))
            onBrowse?.let { AppButton("Browse…", it, ButtonVariant.Secondary) }
        }
        effectivePath?.let {
            AppText("Auto-detected: $it", color = tc.ts, fontSize = 10.sp, fontFamily = MONO)
        }
        effectiveMessage?.let { AppText(it, color = DANGER_RED, fontSize = 10.sp) }
    }
}

@Composable
private fun CaptureNumericField(label: String, value: String, range: IntRange, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in range
    CapturePathField(label, text, { next ->
        text = next.filter(Char::isDigit)
        text.toIntOrNull()?.let(onValue)
    }, invalid = !valid, invalidMessage = "out of range")
}

@Composable
private fun CaptureLongField(label: String, value: Long, range: LongRange, onValue: (Long) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toLongOrNull()
    val valid = parsed != null && parsed in range
    CapturePathField(label, text, { next ->
        text = next.filter(Char::isDigit)
        text.toLongOrNull()?.let(onValue)
    }, invalid = !valid, invalidMessage = "out of range")
}
