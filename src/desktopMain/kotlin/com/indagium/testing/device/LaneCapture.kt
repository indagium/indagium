package com.indagium.testing.device

import com.indagium.capture.CaptureArchiveExporter
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureExportResult
import com.indagium.capture.CaptureRange
import com.indagium.capture.CaptureRecorder
import com.indagium.capture.CaptureSession
import com.indagium.capture.RecorderState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.io.File

// What a test lane needs from the recording of its device, as a seam. Production lanes record through a real capture controller
// (ui/LaneCaptures.kt: the same controller, recorder and archive code a manual live capture uses, with or without a tab);
// StandaloneLaneCapture is the headless recorder the fast engine tests use. `testing` never imports `ui`, so the owner hands the
// engine an implementation.

/** The notes of a lane (its markers and anything added in its tab), written at the end of the lane next to its other files. */
const val LANE_NOTES_FILE_NAME = "lane-notes.ann"

/** What a lane asks the capture to write as a marker: the label, the note under it and, when there is one, the step's screenshot (PNG). */
internal class LaneMarkerRequest(val label: String, val noteText: String, val screenshotPng: ByteArray?)

internal sealed interface LaneMarkerOutcome {
    /** The marker's note is in the lane's notes. Its log window and screenshot may still be completing in the background. */
    data class Added(val markerId: String, val noteId: String, val screenshotAttached: Boolean) : LaneMarkerOutcome

    data class Skipped(val reason: String) : LaneMarkerOutcome
}

internal interface LaneCapture {
    /** The recorded raw logcat of the session. */
    val logFile: File

    /** The folder of the capture session (`logs/`, `video/`, `session.json`, ...). */
    val sessionDirectory: File

    /** False once the recording ended (device unplugged, storage limit, the tab was closed): input still works, new log rows stop. */
    val isRecording: Boolean

    /** The recorder's own diagnostics (video start failures, adb stderr lines), for a run report. */
    val diagnostics: List<String>

    /** The live capture tab of this lane, or null when it records without one. */
    val tabId: String?

    /** Completes with a reason when the recording ended without the lane asking for it (the user closed or stopped the lane's tab). */
    val lost: Deferred<String>

    /** Flushes the recorder's buffers and returns the log's size: rows written after this call have offsets at or beyond it. Blocking. */
    fun flushedLogLength(): Long

    /** The current screen as PNG bytes. Blocking. */
    fun readScreen(): ByteArray

    /** Writes a marker like the Mark issue button does, in the lane's notes. */
    suspend fun addMarker(request: LaneMarkerRequest): LaneMarkerOutcome

    /** Waits (bounded) for the trailing log windows of markers added so far. */
    suspend fun awaitMarkers(timeoutMs: Long)

    /** Stops recording and finalizes the session; a lane's tab stays open as a stopped capture. Idempotent. Blocking: call on IO. */
    fun stop()

    /** Exports the whole session (log, video, audio, the lane's notes with every marker) as the capture ZIP [destination]. Blocking. */
    fun exportArchive(destination: File): CaptureExportResult
}

/** Why a lane without notes writes no marker. */
internal const val NO_LANE_NOTES_REASON = "This lane records without a notes surface."

/**
 * The headless recorder of a lane in the engine tests: a standalone [CaptureRecorder] in the lane's folder, no tab, no notes. Its
 * archive is exported by the same exporter as Save ZIP, but without markers.
 */
internal class StandaloneLaneCapture(
    private val recorder: CaptureRecorder,
    private val session: CaptureSession,
) : LaneCapture {
    override val logFile: File get() = session.logFile
    override val sessionDirectory: File get() = session.directory
    override val isRecording: Boolean get() = recorder.snapshot.value.state == RecorderState.RECORDING
    override val diagnostics: List<String> get() = recorder.snapshot.value.diagnostics
    override val tabId: String? = null
    override val lost: Deferred<String> = CompletableDeferred()

    override fun flushedLogLength(): Long = recorder.snapshotForExport(session.id).logLength

    override fun readScreen(): ByteArray = recorder.readScreen()

    override suspend fun addMarker(request: LaneMarkerRequest): LaneMarkerOutcome = LaneMarkerOutcome.Skipped(NO_LANE_NOTES_REASON)

    override suspend fun awaitMarkers(timeoutMs: Long) = Unit

    override fun stop() {
        runCatching { recorder.stop() }
        runCatching { recorder.close() }
    }

    override fun exportArchive(destination: File): CaptureExportResult {
        val boundary = recorder.snapshotForExport(session.id)
        val request = CaptureExportRequest(
            destination = destination,
            range = CaptureRange.ALL,
            includeVideo = boundary.session.settings.recordVideo,
            cutoffElapsedMs = boundary.elapsedMs,
        )
        return CaptureArchiveExporter().export(boundary.session, request)
    }
}
