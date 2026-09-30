package com.indagium.capture

import java.io.File

private const val BYTES_PER_GIBIBYTE = 1024L * 1024L * 1024L
private const val DEFAULT_SESSION_LIMIT_BYTES = 10L * BYTES_PER_GIBIBYTE
private const val DEFAULT_FREE_SPACE_RESERVE_BYTES = BYTES_PER_GIBIBYTE
private const val DEFAULT_MARKER_WINDOW_MS = 5_000L

/**
 * `-b` buffer selection for `adb logcat`. Three modes:
 * - [DEFAULT] — pass no `-b` at all, i.e. whatever plain `adb logcat` does on its own. This is
 *   deliberately NOT re-encoded as `main,system,crash` (today's hardcoded trio): platform-tools
 *   35.0.2's own `--help` states the real default also includes `kernel`, and a device missing one
 *   of the named buffers makes adb error out where omitting `-b` entirely cannot. Emits nothing.
 * - [ALL] — `-b all`, every buffer the device exposes.
 * - [CUSTOM] — the user's explicit [CaptureSettings.buffers] list, one `-b` pair per buffer.
 */
enum class CaptureBufferMode { DEFAULT, ALL, CUSTOM }

/** Where a live device display is shown while capture is running. */
enum class CaptureMirrorMode { EMBEDDED, EXTERNAL, DISABLED }

/**
 * The container an exported/saved video is remuxed into (recording itself always stays MKV — see
 * StreamingMkvWriter's own doc for why). [extension] is also the archive's video entry name stem
 * ("screen.$extension") — see CaptureArchive.kt's export().
 */
enum class CaptureVideoContainer(val extension: String) { MP4("mp4"), MKV("mkv") }

/** Exactly one presentation action selected when a capture session starts. */
enum class CaptureMirrorStartRoute { EMBEDDED, EXTERNAL, NONE }

/** Capture preferences are persisted as keyed JSON, never in legacy positional settings. */
data class CaptureSettings(
    val adbPath: String = "",
    val scrcpyPath: String = "",
    val buffers: List<String> = listOf("main", "system", "crash"),
    // Off by default: with it on, logcat dumps everything the device's ring buffers hold before
    // streaming (often hours, sometimes a previous day), and logcat's date-less timestamps then
    // make the log<->video sync ambiguous — see estimateCaptureSyncAnchor. A user who wants the
    // pre-Start history opts in; stored settings keep whatever the user chose, and settings that
    // never saved this key pick up this default.
    val includeBufferedLogs: Boolean = false,
    // On by default: a capture is most useful with the screen recording beside the log. Stored
    // settings keep whatever the user chose; only settings that never saved this key pick it up.
    val recordVideo: Boolean = true,
    /**
     * Legacy enablement bit retained for old settings/session JSON. New callers select
     * [mirrorMode]; [effectiveMirrorMode] keeps a legacy false value authoritative so settings
     * written by older builds still disable every display route.
     */
    val mirror: Boolean = true,
    val audio: Boolean = false,
    // Balanced preset ([CaptureVideoPreset.BALANCED]); a stored value always wins over these.
    val maxSize: Int = 1280,
    val maxFps: Int = 30,
    val bitrateMbps: Int = 3,
    val sessionLimitBytes: Long = DEFAULT_SESSION_LIMIT_BYTES,
    val freeSpaceReserveBytes: Long = DEFAULT_FREE_SPACE_RESERVE_BYTES,
    val filenameTemplate: String = "{device}_{start}_{range}_{counter}.zip",
    val label: String = "",
    // Appended last per CLAUDE.md's positional-token rule (this field is JSON, not a positional
    // token, but the convention is kept for consistency and to minimize diff churn on this data
    // class). `buffers` keeps its old shape/default untouched so persisted settings and archive
    // descriptors round-trip unchanged; this field only selects how `buffers` is interpreted.
    val bufferMode: CaptureBufferMode = CaptureBufferMode.DEFAULT,
    val mirrorMode: CaptureMirrorMode = CaptureMirrorMode.EMBEDDED,
    // Mark issue (restyle plan Phase 3), appended last per this class's own convention comment
    // above. markerPreMs/markerPostMs bound the log window a press captures around itself;
    // markerScreenshot gates the extra adb screencap that press also takes. markerNotesInSnapshot
    // is read only by Phase 4 (snapshot archive export) — that phase isn't implemented yet, so
    // nothing consumes this value today, but the setting and its UI row exist now so Phase 4 has
    // nothing left to add to the settings surface itself.
    val markerPreMs: Long = DEFAULT_MARKER_WINDOW_MS,
    val markerPostMs: Long = DEFAULT_MARKER_WINDOW_MS,
    val markerScreenshot: Boolean = true,
    val markerNotesInSnapshot: Boolean = true,
    // Archive v3 / MP4 export, appended last per this class's own convention comment above. Only
    // the EXPORTED video's container (Save snapshot / Save ZIP remux) — the live recording always
    // stays MKV regardless of this setting. See CaptureArchive.kt's export() and
    // FfmpegCaptureVideoExporter for where this is actually consumed.
    val videoContainer: CaptureVideoContainer = CaptureVideoContainer.MP4,
    /** "Keep sound on the device" (appended last per this class's own convention comment above).
     *  Off by default: it only ever matters when [audio] is also on, and the plain "device muted
     *  while its audio is captured" behavior is the long-standing default nobody should be opted
     *  into silently. See [captureAudioPlan] for the Android-13+ gate and the exact scrcpy
     *  arguments this selects. */
    val keepDeviceAudio: Boolean = false,
    /** Live audio playback on this computer for the embedded (in-app) mirror, appended last per this
     *  class's own convention comment above. Off by default — muted until the user opts in via the
     *  mirror panel's speaker toggle (ui/EmbeddedMirrorPanel.kt). Only ever matters when [audio] is
     *  also on and the mirror is EMBEDDED; irrelevant for EXTERNAL (the scrcpy window plays its own
     *  audio) or DISABLED. See capture/mirror/LiveAudioPlayer.kt. */
    val playAudioLive: Boolean = false,
    /** 0-100 volume applied to the live audio player above, appended last matching [playAudioLive].
     *  Default 80 rather than 100 so turning this on for the first time isn't jarringly loud. */
    val liveAudioVolume: Int = 80,
    /** Optional host microphone input. [MICROPHONE_OFF_ID] disables it, [MICROPHONE_DEFAULT_ID]
     * uses the operating system default, and any other value is a stable mixer identity. */
    val microphoneDeviceId: String = MICROPHONE_OFF_ID,
    /** Opt-in for the Windows D3D11 / Linux VAAPI+EGL direct-decode in-app mirror, appended last
     *  matching this class's own convention comment above. Off by default: neither path has proven
     *  itself on real hardware yet (see EmbeddedMirrorPanel.kt's `shouldUseDesktopGpuMirror`), so
     *  Windows/Linux default to the Compose/JavaCV mirror until a user explicitly turns this on in
     *  Settings → Capture. Irrelevant on macOS, which always uses its own VideoToolbox/Metal path. */
    val hardwareMirror: Boolean = false,
)

const val MICROPHONE_OFF_ID = "off"
const val MICROPHONE_DEFAULT_ID = "system-default"

/** The active display choice after applying the pre-choice `mirror` compatibility switch. */
val CaptureSettings.effectiveMirrorMode: CaptureMirrorMode
    get() = if (mirror) mirrorMode else CaptureMirrorMode.DISABLED

/**
 * Screen-recording quality presets: a named (longest side, frame rate, bitrate) triple. Not
 * persisted on its own — [videoPreset] derives it from the three stored numbers, so settings written
 * before presets existed simply show as [videoPreset] `null` (Custom) when they match none.
 */
enum class CaptureVideoPreset(val label: String, val maxSize: Int, val maxFps: Int, val bitrateMbps: Int) {
    COMPACT("Compact", 1080, 15, 1),
    BALANCED("Balanced", 1280, 30, 3),
    DETAILED("Detailed", 1600, 30, 6),
    SMOOTH("Smooth", 1920, 60, 12),
}

/** The preset whose triple equals this settings' recording numbers, or `null` (= Custom). */
fun CaptureSettings.videoPreset(): CaptureVideoPreset? =
    CaptureVideoPreset.entries.firstOrNull { it.maxSize == maxSize && it.maxFps == maxFps && it.bitrateMbps == bitrateMbps }

fun CaptureSettings.withVideoPreset(preset: CaptureVideoPreset): CaptureSettings =
    copy(maxSize = preset.maxSize, maxFps = preset.maxFps, bitrateMbps = preset.bitrateMbps)

/** Approximate recording size per minute, in MB (10^6 bytes). [typicalMbPerMin] is what a mixed
 * screen produces; [capMbPerMin] is the ceiling the encoder bitrate allows. */
data class CaptureSizeEstimate(val typicalMbPerMin: Double, val capMbPerMin: Double)

private const val MB_PER_MIN_PER_MBPS = 7.5 // 1 Mbps * 60 s / 8 bits per byte

// Calibrated from a real capture: 30 MB/min at an 8 Mbps cap (Android encoders spend well under
// the target bitrate on mostly static screens).
private const val TYPICAL_FRACTION_OF_CAP = 0.5
private const val AUDIO_MB_PER_MIN = 1.0 // Opus at ~128 kbps
private const val ESTIMATE_MINUTES = 30
private const val ESTIMATE_TOTAL_STEP_MB = 10
private const val MB_PER_GB = 1000
private const val ONE_DECIMAL_BELOW_MB = 10.0

fun captureSizeEstimate(bitrateMbps: Int, withAudio: Boolean): CaptureSizeEstimate {
    val cap = bitrateMbps * MB_PER_MIN_PER_MBPS
    val audio = if (withAudio) AUDIO_MB_PER_MIN else 0.0
    return CaptureSizeEstimate(cap * TYPICAL_FRACTION_OF_CAP + audio, cap + audio)
}

/** Device audio or the host microphone adds an audio track to the recording. */
fun CaptureSettings.captureSizeEstimate(): CaptureSizeEstimate =
    captureSizeEstimate(bitrateMbps, audio || microphoneDeviceId != MICROPHONE_OFF_ID)

/** e.g. `≈ 11 MB/min (up to 23) · 30 min ≈ 340 MB`. */
fun formatCaptureSizeEstimate(estimate: CaptureSizeEstimate): String {
    val total = Math.round(estimate.typicalMbPerMin * ESTIMATE_MINUTES / ESTIMATE_TOTAL_STEP_MB) * ESTIMATE_TOTAL_STEP_MB
    val totalText = if (total >= MB_PER_GB) {
        String.format(java.util.Locale.ROOT, "%.1f GB", total / MB_PER_GB.toDouble())
    } else {
        "$total MB"
    }
    return "≈ ${formatMbPerMin(estimate.typicalMbPerMin)} MB/min (up to ${formatMbPerMin(estimate.capMbPerMin)}) · " +
        "$ESTIMATE_MINUTES min ≈ $totalText"
}

private fun formatMbPerMin(value: Double): String =
    if (value < ONE_DECIMAL_BELOW_MB) String.format(java.util.Locale.ROOT, "%.1f", value) else Math.round(value).toString()

/** Keeps the legacy `mirror` bit and the current display choice in sync for UI edits. */
fun CaptureSettings.withMirrorMode(mode: CaptureMirrorMode): CaptureSettings =
    copy(mirror = mode != CaptureMirrorMode.DISABLED, mirrorMode = mode)

/** Prevents a start path from accidentally opening both embedded and external scrcpy streams. */
fun CaptureSettings.mirrorStartRoute(): CaptureMirrorStartRoute = when (effectiveMirrorMode) {
    CaptureMirrorMode.EMBEDDED -> CaptureMirrorStartRoute.EMBEDDED
    CaptureMirrorMode.EXTERNAL -> CaptureMirrorStartRoute.EXTERNAL
    CaptureMirrorMode.DISABLED -> CaptureMirrorStartRoute.NONE
}

/**
 * Adapts one launch's settings to this system's native media capability (see NativeMediaSupport.kt
 * — Linux with a too-old glibc). Never touches saved settings; a caller applies this once, right
 * before a single capture actually starts (see AppState.startCaptureTab), to the settings that one
 * launch uses. Available -> unchanged. Unavailable -> video recording is forced off (it needs the
 * same bundled natives), and an in-app mirror request falls back to the external scrcpy window when
 * one is installed, or to no display at all when it isn't; a request for the external window or no
 * display was never going to touch the bundled natives, so it stays exactly as chosen.
 */
fun CaptureSettings.adaptedToNativeMedia(support: NativeMediaSupport, scrcpyAvailable: Boolean): CaptureSettings {
    if (support.available) return this
    val videoOff = copy(recordVideo = false)
    return if (videoOff.effectiveMirrorMode == CaptureMirrorMode.EMBEDDED) {
        videoOff.withMirrorMode(if (scrcpyAvailable) CaptureMirrorMode.EXTERNAL else CaptureMirrorMode.DISABLED)
    } else {
        videoOff
    }
}

/**
 * One plain, non-fatal notice for a single capture whose settings [adaptedToNativeMedia] actually
 * changed — null when nothing changed (an available system, or a launch that was never going to
 * touch the bundled natives anyway). Meant for a live-session diagnostic (CaptureRecorder's
 * `addDiagnostic`/snapshot.diagnostics — see the capture strip's Diagnostics drawer), not an error:
 * the capture still works, just without native video.
 */
fun nativeMediaAdaptationNotice(
    original: CaptureSettings,
    adapted: CaptureSettings,
    support: NativeMediaSupport,
    scrcpyAvailable: Boolean,
): String? {
    if (original == adapted) return null
    val glibcClause = support.detectedGlibc?.let { "glibc $it" } ?: "an old glibc"
    // Describe the display this launch will actually use: a launch whose display was already
    // Off (or already the scrcpy window) only lost video recording, so don't claim a fallback.
    val displayTail = when {
        adapted.effectiveMirrorMode == CaptureMirrorMode.EXTERNAL -> "the device is shown in a scrcpy window."
        original.effectiveMirrorMode == CaptureMirrorMode.EMBEDDED && !scrcpyAvailable ->
            "no device display is shown (scrcpy isn't installed)."
        else -> "the device display is off."
    }
    return "Video recording and the in-app mirror aren't available on this system " +
        "($glibcClause; needs ${REQUIRED_GLIBC.first}.${REQUIRED_GLIBC.second}+, Ubuntu 22.04 or newer). " +
        "Capturing logs only; $displayTail"
}

/** The `-b` arguments for this configuration. Empty for DEFAULT — passing no -b at all is what
 *  plain `adb logcat` does, and tracks whatever adb's own default is rather than re-encoding
 *  today's list (which silently omitted `kernel`). */
fun CaptureSettings.logcatBufferArgs(): List<String> = when (bufferMode) {
    CaptureBufferMode.DEFAULT -> emptyList()
    CaptureBufferMode.ALL -> listOf("-b", "all")
    CaptureBufferMode.CUSTOM -> buffers.distinct().flatMap { listOf("-b", it) }
}

data class CaptureDevice(val serial: String, val state: String, val model: String = serial, val emulator: Boolean = serial.startsWith("emulator-")) {
    val available: Boolean get() = state == "device"

    /** Attached over Wi-Fi (Wireless debugging) rather than USB. Computed from the serial, so
     *  nothing about it is persisted. */
    val wireless: Boolean get() = isWirelessSerial(serial)
}

enum class CaptureStatus { RECORDING, STOPPED, INTERRUPTED }

data class CaptureSession(
    val id: String,
    val directory: File,
    val device: CaptureDevice,
    val settings: CaptureSettings,
    val startedEpochMs: Long,
    val elapsedMs: Long = 0,
    val status: CaptureStatus = CaptureStatus.RECORDING,
    val videoStartElapsedMs: Long? = null,
    /**
     * The cursor used for the *log* half of the Since last save range: the elapsed-ms end of the
     * log coverage from the last successful snapshot. Always advances on every successful export
     * (video or not) to the export's log-covered end, so a log-only snapshot never repeats rows.
     */
    val snapshotCheckpointMs: Long = -1,
    @Deprecated("Use snapshotCheckpointMs")
    val logCheckpointMs: Long = -1,
    /**
     * The cursor used for the *video* half of the Since last save range: the elapsed-ms end of the
     * video actually covered by the last successful export that included video. Deliberately a
     * separate cursor from [snapshotCheckpointMs] — the growing scrcpy MKV's muxer/AVIO buffering
     * lag means video coverage routinely falls behind the log at Save time (see
     * FfmpegCaptureVideoExporter's wait-for-coverage step), and a shared cursor either re-exported
     * the whole recording from its first keyframe every time (video checkpoint stuck at the far
     * past) or silently dropped the untranscoded tail between saves (video checkpoint advanced past
     * what was actually exported). Left at -1 (unset) when no export has ever included video, in
     * which case the video range start falls back to [snapshotCheckpointMs] — see
     * [effectiveVideoCheckpointMs].
     */
    val videoCheckpointMs: Long = -1,
    val exportCounter: Int = 0,
    val interruptions: List<String> = emptyList(),
    val manualOffsetMs: Long = 0,
) {
    val logFile: File get() = File(directory, "logs/logcat.log")
    val indexFile: File get() = File(directory, "mapping/capture-index.jsonl")
    val videoFile: File get() = File(directory, "video/screen.mkv")

    /** Effective cursor, including sessions loaded from the legacy logCheckpointMs format. */
    val effectiveSnapshotCheckpointMs: Long
        get() = snapshotCheckpointMs.takeIf { it >= 0 } ?: logCheckpointMs

    /**
     * The elapsed-ms start of the *video* half of a Since last save export. `min()` rather than
     * using [videoCheckpointMs] alone: video coverage can only ever lag the log (never lead it —
     * see the field doc above), so in the steady state this simply evaluates to
     * [videoCheckpointMs], picking up exactly the tail the previous export's muxer lag left behind.
     * Falls back to the log cursor when no export has ever produced video, matching the pre-split
     * behaviour instead of reaching back to the very start of the recording.
     */
    val effectiveVideoCheckpointMs: Long
        get() = if (videoCheckpointMs >= 0) minOf(effectiveSnapshotCheckpointMs, videoCheckpointMs) else effectiveSnapshotCheckpointMs

    val hasSnapshotCheckpoint: Boolean get() = effectiveSnapshotCheckpointMs >= 0

    /** Age of the last successful save at the current capture elapsed position. */
    fun snapshotCheckpointAgeMs(atElapsedMs: Long): Long? =
        effectiveSnapshotCheckpointMs.takeIf { it >= 0 }?.let { (atElapsedMs - it).coerceAtLeast(0L) }
}

/** One complete raw line; separators have no parsed ordinal. Elapsed time uses a monotonic clock. */
data class CaptureLogIndexRecord(val byteOffset: Long, val byteLength: Int, val elapsedMs: Long, val rowOrdinal: Int?)

enum class CaptureRange { ALL, LAST_FIVE, LAST_TEN, CUSTOM, SINCE_SAVE, SELECTION }

data class CaptureExportRequest(
    val destination: File,
    val range: CaptureRange = CaptureRange.ALL,
    val customMinutes: Int = 5,
    val includeVideo: Boolean = true,
    val cutoffElapsedMs: Long,
    /** Inclusive source capture-row bounds for [CaptureRange.SELECTION]. */
    val selectedFirstRowOrdinal: Int? = null,
    val selectedLastRowOrdinal: Int? = null,
    val overwriteExisting: Boolean = false,
)

data class CaptureExportResult(
    val file: File,
    val logCoveredEndMs: Long,
    val videoCoveredEndMs: Long?,
    val videoActualStartMs: Long?,
    val message: String,
)

/** Read-only coverage and overwrite information shown before a live snapshot is saved. */
data class CaptureExportPreview(
    val logStartMs: Long?,
    val logEndMs: Long?,
    val videoCoveredEndMs: Long?,
    val videoShortfallMs: Long?,
    val selectedFirstRowOrdinal: Int?,
    val selectedLastRowOrdinal: Int?,
    val destinationExists: Boolean,
    val includeVideo: Boolean,
)

/** Null video positions deliberately represent rows outside the readable recording. */
data class CaptureMappingRow(val ordinal: Int, val elapsedMs: Long, val videoMs: Long?)

data class CaptureTimeline(
    val rows: List<CaptureMappingRow>,
    val quality: String = "estimated",
    val uncertaintyMs: Long? = null,
    val manualOffsetMs: Long = 0,
)

data class CaptureVideoClip(val actualStartMs: Long, val coveredEndMs: Long, val durationMs: Long)

fun interface CaptureVideoExporter {
    /** Snapshot a growing MKV and remux without modifying the source. Times are source video PTS. */
    fun export(source: File, destination: File, requestedStartMs: Long, requestedEndMs: Long): CaptureVideoClip

    /**
     * Like [export], but [source] is final: the session is stopped or interrupted, so the file will
     * never grow again and implementations may read it directly instead of snapshotting a prefix
     * first (a multi-GB copy for a long capture). Defaults to [export] so simple implementations
     * and SAM-lambda test fakes need not care.
     */
    fun exportFinal(source: File, destination: File, requestedStartMs: Long, requestedEndMs: Long): CaptureVideoClip =
        export(source, destination, requestedStartMs, requestedEndMs)
}

/**
 * Cheap, read-only companion to [CaptureVideoExporter]: reports how far the requested interval is
 * currently covered without writing any destination file. [CaptureArchiveExporter.preview] polls
 * on a debounce while the popover is open and a live capture keeps growing its MKV; routing that
 * through the full [CaptureVideoExporter.export] (copy the prefix, scan it, then remux and write a
 * whole second MKV) did that write on every tick for no reason — nothing reads the written bytes,
 * only [CaptureVideoClip.coveredEndMs]. Implementations still need to copy the current prefix
 * before scanning it (the same live-file race [CaptureVideoExporter.export] avoids), so this saves
 * the remux/write half of the work, not the read half.
 */
fun interface CaptureVideoCoverageProbe {
    fun coverageEndMs(source: File, requestedStartMs: Long, requestedEndMs: Long): Long
}
