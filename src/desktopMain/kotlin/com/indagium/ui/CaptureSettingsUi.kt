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
import androidx.compose.ui.platform.testTag
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
internal const val MIN_MARKER_WINDOW_SECONDS = 0
internal const val MAX_MARKER_WINDOW_SECONDS = 120
internal const val MIN_LIVE_AUDIO_VOLUME = 0
internal const val MAX_LIVE_AUDIO_VOLUME = 100
internal const val CAPTURE_MARKER_MS_PER_SECOND = 1_000L

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
    val markerWindowRangeMs = MIN_MARKER_WINDOW_SECONDS * CAPTURE_MARKER_MS_PER_SECOND..MAX_MARKER_WINDOW_SECONDS * CAPTURE_MARKER_MS_PER_SECOND
    if (settings.markerPreMs !in markerWindowRangeMs) add("markerPreMs")
    if (settings.markerPostMs !in markerWindowRangeMs) add("markerPostMs")
    if (settings.liveAudioVolume !in MIN_LIVE_AUDIO_VOLUME..MAX_LIVE_AUDIO_VOLUME) add("liveAudioVolume")
}

/** Pure presentation rule shared by the settings UI and its focused tests. */
internal fun visibleCaptureBuffers(settings: CaptureSettings): List<String> =
    settings.buffers.takeIf { settings.bufferMode == CaptureBufferMode.CUSTOM }.orEmpty()

// What the Settings › Capture & video pages (ui/SettingsCapturePages.kt) are built from: the limits and the validation that decides which
// fields are flagged, and the row shapes for numeric and path fields. A field the user has typed out of range stays visible as typed, with a
// red note, and is reported by [invalidCaptureSettings] so a capture refuses to start with it.

private const val NUMBER_FIELD_MAX_DIGITS = 12

/** Test tag of the text field of the capture row with search anchor [anchor]. */
internal fun captureFieldTag(anchor: String): String =
    "capture-field-" + anchor.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

/** The note a numeric row shows beside its unit while the typed value is out of range. */
private const val OUT_OF_RANGE_NOTE = "out of range"

/**
 * One numeric setting: [label] with an optional [description], and a right-aligned digits-only field with its [unit]. Every typed value that
 * parses is passed to [onValue], in range or not, so [invalidCaptureSettings] sees it; while it is out of [range] or empty the row says so in
 * red. [anchor] is the search anchor (default [label]).
 */
@Composable
internal fun CaptureNumberRow(
    label: String,
    value: Int,
    range: IntRange,
    unit: String,
    description: String? = null,
    anchor: String = label,
    detail: String? = null,
    onValue: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    CaptureNumberRowCore(label, description, anchor, unit, text, valid = parsed != null && parsed in range, detail = detail) { next ->
        text = next
        next.toIntOrNull()?.let(onValue)
    }
}

/** [CaptureNumberRow] for a [Long] setting. */
@Composable
internal fun CaptureLongRow(
    label: String,
    value: Long,
    range: LongRange,
    unit: String,
    description: String? = null,
    anchor: String = label,
    detail: String? = null,
    onValue: (Long) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toLongOrNull()
    CaptureNumberRowCore(label, description, anchor, unit, text, valid = parsed != null && parsed in range, detail = detail) { next ->
        text = next
        next.toLongOrNull()?.let(onValue)
    }
}

@Composable
private fun CaptureNumberRowCore(
    label: String,
    description: String?,
    anchor: String,
    unit: String,
    text: String,
    valid: Boolean,
    detail: String? = null,
    onText: (String) -> Unit,
) {
    SettingsRow(label, description, anchor = anchor, detail = detail) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!valid) AppText(OUT_OF_RANGE_NOTE, color = DANGER_RED, fontSize = 11.sp)
            SettingsNumberField(
                value = text,
                onValueChange = onText,
                unit = unit,
                unitWidth = UNIT_COLUMN_WIDTH,
                maxDigits = NUMBER_FIELD_MAX_DIGITS,
                invalid = !valid,
                testTag = captureFieldTag(anchor),
            )
        }
    }
}

private val UNIT_COLUMN_WIDTH = 34.dp

/**
 * One text setting on a row of its own, the field spanning the width: [label] with an optional [description] above, the field (with a
 * [placeholder] while empty) and an optional Browse button, then [invalidMessage] in red while [invalid], the auto-detected [effectivePath]
 * and a red [effectiveMessage].
 */
@Composable
internal fun CaptureTextRow(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    description: String? = null,
    anchor: String = label,
    onBrowse: (() -> Unit)? = null,
    invalid: Boolean = false,
    invalidMessage: String = "value is invalid",
    effectivePath: String? = null,
    effectiveMessage: String? = null,
    placeholder: String = "",
    detail: String? = null,
) {
    val tc = tc()
    SettingsStackedRow(label, description, anchor = anchor, detail = detail) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsTextField(
                    value,
                    onValue,
                    modifier = Modifier.weight(1f),
                    placeholder = placeholder,
                    invalid = invalid,
                    testTag = captureFieldTag(anchor),
                )
                onBrowse?.let { SettingsButton("Browse", it, ButtonVariant.Secondary) }
            }
            if (invalid) AppText(invalidMessage, color = DANGER_RED, fontSize = 11.sp)
            effectivePath?.let { AppText("Auto-detected: $it", color = tc.ts, fontSize = 11.sp, fontFamily = MONO) }
            effectiveMessage?.let { AppText(it, color = DANGER_RED, fontSize = 11.sp) }
        }
    }
}
