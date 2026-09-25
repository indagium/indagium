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

/** Exactly one presentation action selected when a capture session starts. */
enum class CaptureMirrorStartRoute { EMBEDDED, EXTERNAL, NONE }

/** Capture preferences are persisted as keyed JSON, never in legacy positional settings. */
data class CaptureSettings(
    val adbPath: String = "",
    val scrcpyPath: String = "",
    val buffers: List<String> = listOf("main", "system", "crash"),
    val includeBufferedLogs: Boolean = false,
    val recordVideo: Boolean = false,
    /**
     * Legacy enablement bit retained for old settings/session JSON. New callers select
     * [mirrorMode]; [effectiveMirrorMode] keeps a legacy false value authoritative so settings
     * written by older builds still disable every display route.
     */
    val mirror: Boolean = true,
    val audio: Boolean = false,
    val maxSize: Int = 1080,
    val maxFps: Int = 30,
    val bitrateMbps: Int = 8,
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
)

/** The active display choice after applying the pre-choice `mirror` compatibility switch. */
val CaptureSettings.effectiveMirrorMode: CaptureMirrorMode
    get() = if (mirror) mirrorMode else CaptureMirrorMode.DISABLED

/** Keeps the legacy `mirror` bit and the current display choice in sync for UI edits. */
fun CaptureSettings.withMirrorMode(mode: CaptureMirrorMode): CaptureSettings =
    copy(mirror = mode != CaptureMirrorMode.DISABLED, mirrorMode = mode)

/** Prevents a start path from accidentally opening both embedded and external scrcpy streams. */
fun CaptureSettings.mirrorStartRoute(): CaptureMirrorStartRoute = when (effectiveMirrorMode) {
    CaptureMirrorMode.EMBEDDED -> CaptureMirrorStartRoute.EMBEDDED
    CaptureMirrorMode.EXTERNAL -> CaptureMirrorStartRoute.EXTERNAL
    CaptureMirrorMode.DISABLED -> CaptureMirrorStartRoute.NONE
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
