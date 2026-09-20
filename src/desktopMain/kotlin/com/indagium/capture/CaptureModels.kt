package com.indagium.capture

import java.io.File

private const val BYTES_PER_GIBIBYTE = 1024L * 1024L * 1024L
private const val DEFAULT_SESSION_LIMIT_BYTES = 10L * BYTES_PER_GIBIBYTE
private const val DEFAULT_FREE_SPACE_RESERVE_BYTES = BYTES_PER_GIBIBYTE

/** Capture preferences are persisted as keyed JSON, never in legacy positional settings. */
data class CaptureSettings(
    val adbPath: String = "",
    val scrcpyPath: String = "",
    val buffers: List<String> = listOf("main", "system", "crash"),
    val includeBufferedLogs: Boolean = false,
    val recordVideo: Boolean = false,
    val mirror: Boolean = true,
    val audio: Boolean = false,
    val maxSize: Int = 1080,
    val maxFps: Int = 30,
    val bitrateMbps: Int = 8,
    val sessionLimitBytes: Long = DEFAULT_SESSION_LIMIT_BYTES,
    val freeSpaceReserveBytes: Long = DEFAULT_FREE_SPACE_RESERVE_BYTES,
    val filenameTemplate: String = "{device}_{start}_{range}_{counter}.zip",
    val label: String = "",
)

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
    val logCheckpointMs: Long = -1,
    val videoCheckpointMs: Long = -1,
    val exportCounter: Int = 0,
    val interruptions: List<String> = emptyList(),
    val manualOffsetMs: Long = 0,
) {
    val logFile: File get() = File(directory, "logs/logcat.log")
    val indexFile: File get() = File(directory, "mapping/capture-index.jsonl")
    val videoFile: File get() = File(directory, "video/screen.mkv")
}

/** One complete raw line; separators have no parsed ordinal. Elapsed time uses a monotonic clock. */
data class CaptureLogIndexRecord(val byteOffset: Long, val byteLength: Int, val elapsedMs: Long, val rowOrdinal: Int?)

enum class CaptureRange { ALL, LAST_FIVE, LAST_TEN, CUSTOM, SINCE_SAVE }

data class CaptureExportRequest(
    val destination: File,
    val range: CaptureRange = CaptureRange.ALL,
    val customMinutes: Int = 5,
    val includeVideo: Boolean = true,
    val cutoffElapsedMs: Long,
)

data class CaptureExportResult(
    val file: File,
    val logCoveredEndMs: Long,
    val videoCoveredEndMs: Long?,
    val videoActualStartMs: Long?,
    val message: String,
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
