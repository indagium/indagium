package com.indagium.debug

import com.indagium.capture.CaptureBufferMode
import com.indagium.capture.CaptureMirrorMode
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureVideoPreset
import com.indagium.capture.MICROPHONE_DEFAULT_ID
import com.indagium.capture.MICROPHONE_OFF_ID
import com.indagium.capture.effectiveMirrorMode
import com.indagium.capture.videoPreset
import com.indagium.capture.withMirrorMode
import com.indagium.capture.withVideoPreset
import com.indagium.ui.CAPTURE_BUFFER_NAMES

// The `capture` object of run_test_suite: the same "Before start" knobs a manual live capture has, applied on top of the user's
// saved capture settings for this run only (nothing is written back). Parsing is strict: an unknown key or a value of the wrong
// type is refused, so a typo never silently records less than the caller meant.

private const val MIC_OFF_WORD = "off"
private const val MIC_DEFAULT_WORD = "default"
private const val MAX_MICROPHONE_ID_CHARS = 300

private val DISPLAY_WORDS = mapOf(
    "in_app_mirror" to CaptureMirrorMode.EMBEDDED,
    "scrcpy_window" to CaptureMirrorMode.EXTERNAL,
    "off" to CaptureMirrorMode.DISABLED,
)
private val CAPTURE_KEYS = setOf(
    "recordVideo", "audio", "includeEarlierDeviceLogs", "keepDeviceAudio", "microphone", "deviceDisplay", "bufferMode", "buffers",
    "videoQuality",
)

/** The words `capture.deviceDisplay` accepts. */
internal val CAPTURE_DISPLAY_WORDS: List<String> = DISPLAY_WORDS.keys.toList()

/** The words `capture.videoQuality` accepts. */
internal val CAPTURE_QUALITY_WORDS: List<String> = CaptureVideoPreset.entries.map { it.name.lowercase() }

/**
 * The saved settings [saved] with the options of [raw] (the `capture` argument) applied; [raw] null leaves them as they are.
 * [videoHint] is the older `evidence.video` choice: when given it decides whether the screen is recorded unless `capture` says so itself.
 */
internal fun parseRunCapture(raw: Any?, saved: CaptureSettings, videoHint: Boolean? = null): CaptureSettings {
    val base = if (videoHint == null) saved else saved.copy(recordVideo = videoHint)
    if (raw == null) return base
    val given = raw.asObject("capture")
    val unknown = given.keys - CAPTURE_KEYS
    if (unknown.isNotEmpty()) {
        toolArgError("capture has no option ${unknown.sorted().joinToString(", ")}; the options are ${CAPTURE_KEYS.sorted().joinToString(", ")}.")
    }
    val args = ToolArgs(given)
    var settings = base
    args.bool("recordVideo")?.let { settings = settings.copy(recordVideo = it) }
    args.bool("audio")?.let { settings = settings.copy(audio = it) }
    args.bool("includeEarlierDeviceLogs")?.let { settings = settings.copy(includeBufferedLogs = it) }
    args.bool("keepDeviceAudio")?.let { settings = settings.copy(keepDeviceAudio = it) }
    args.string("microphone")?.let { settings = settings.copy(microphoneDeviceId = microphoneId(it)) }
    args.string("deviceDisplay")?.let { word ->
        val mode = DISPLAY_WORDS[word.trim().lowercase()] ?: toolArgError("capture.deviceDisplay must be one of ${CAPTURE_DISPLAY_WORDS.joinToString(", ")}.")
        settings = settings.withMirrorMode(mode)
    }
    args.string("videoQuality")?.let { word ->
        val preset = CaptureVideoPreset.entries.firstOrNull { it.name.equals(word.trim(), ignoreCase = true) }
            ?: toolArgError("capture.videoQuality must be one of ${CAPTURE_QUALITY_WORDS.joinToString(", ")}.")
        settings = settings.withVideoPreset(preset)
    }
    return withBuffers(settings, args)
}

private fun microphoneId(word: String): String {
    val trimmed = word.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_MICROPHONE_ID_CHARS || trimmed.any { it.isISOControl() }) {
        toolArgError("capture.microphone must be \"$MIC_OFF_WORD\", \"$MIC_DEFAULT_WORD\" or the id of a microphone.")
    }
    return when (trimmed.lowercase()) {
        MIC_OFF_WORD -> MICROPHONE_OFF_ID
        MIC_DEFAULT_WORD -> MICROPHONE_DEFAULT_ID
        else -> trimmed
    }
}

private fun withBuffers(settings: CaptureSettings, args: ToolArgs): CaptureSettings {
    val buffers = args.strings("buffers")?.map { it.trim().lowercase() }
    val modeWord = args.string("bufferMode")?.trim()?.lowercase()
    val mode = when {
        modeWord != null -> CaptureBufferMode.entries.firstOrNull { it.name.equals(modeWord, ignoreCase = true) }
            ?: toolArgError("capture.bufferMode must be one of ${CaptureBufferMode.entries.joinToString(", ") { it.name.lowercase() }}.")
        buffers != null -> CaptureBufferMode.CUSTOM
        else -> return settings
    }
    if (buffers != null) {
        val bad = buffers.filter { it !in CAPTURE_BUFFER_NAMES }
        if (bad.isNotEmpty()) toolArgError("capture.buffers has unknown buffer(s) ${bad.joinToString(", ")}; use ${CAPTURE_BUFFER_NAMES.joinToString(", ")}.")
    }
    val chosen = buffers ?: settings.buffers
    if (mode == CaptureBufferMode.CUSTOM && chosen.isEmpty()) toolArgError("capture.buffers must name at least one buffer for bufferMode custom.")
    return settings.copy(bufferMode = mode, buffers = chosen.distinct())
}

/** One line for an approval card or a report: what a run records, in plain words. */
internal fun CaptureSettings.recordingSummary(openLaneTabs: Boolean): String {
    val parts = ArrayList<String>()
    parts += if (recordVideo) "screen video (${videoPreset()?.label ?: "custom quality"})" else "no screen video"
    if (audio) parts += "device audio" + if (keepDeviceAudio) " kept audible on the device" else ""
    if (microphoneDeviceId != MICROPHONE_OFF_ID) {
        parts += "microphone (${if (microphoneDeviceId == MICROPHONE_DEFAULT_ID) "system default" else "selected input"})"
    }
    if (includeBufferedLogs) parts += "earlier device logs"
    parts += "buffers: " + when (bufferMode) {
        CaptureBufferMode.DEFAULT -> "default"
        CaptureBufferMode.ALL -> "all"
        CaptureBufferMode.CUSTOM -> buffers.joinToString(",")
    }
    parts += "device display: " + when (effectiveMirrorMode) {
        CaptureMirrorMode.EMBEDDED -> "in-app mirror"
        CaptureMirrorMode.EXTERNAL -> "scrcpy window"
        CaptureMirrorMode.DISABLED -> "off"
    }
    parts += if (openLaneTabs) "a live capture tab per lane" else "no lane tabs"
    return parts.joinToString("; ")
}
