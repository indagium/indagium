package com.indagium.ui

import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureExportResult
import com.indagium.capture.CaptureRange
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.RecorderState
import com.indagium.capture.readCaptureSessionDirectory
import com.indagium.model.Annotations
import com.indagium.model.TestLaneTabRef
import com.indagium.testing.device.LANE_CAPTURE_DIRECTORY
import com.indagium.testing.device.LANE_NOTES_FILE_NAME
import com.indagium.testing.device.LaneCapture
import com.indagium.testing.device.LaneMarkerOutcome
import com.indagium.testing.device.LaneMarkerRequest
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.run.LANE_STOP_WAIT_MS
import com.indagium.testing.run.LaneDeviceOpener
import com.indagium.testing.run.LaneOpenRequest
import com.indagium.testing.run.headlessCaptureSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

// How an AI test lane records its device: through the SAME controller, recorder, mirror and archive code as a manual live capture.
// AppState owns the start/stop primitives (beginLaneCapture / finishLaneCapture: the per-device claim, the shared starter, the lane
// tab); this file adapts them to the engine's LaneCapture seam. A lane either has a real capture tab (stays open as a stopped
// capture after the lane) or records without one and holds its markers in [LaneNotes]; both write the same markers and export the
// same archive.

/** Why a lane whose recording ended on its own stops. */
private const val LANE_CAPTURE_LOST_REASON = "The lane's capture ended (its tab was closed or stopped, or the device went away)."

/** What a lane asks AppState for when it starts recording. */
internal class LaneCaptureStart(
    val lane: TestLaneTabRef,
    val device: CaptureDevice,
    /** The folder that holds the lane's capture sessions (`<run>/lanes/<laneId>/capture`). */
    val captureRoot: File,
    val settings: CaptureSettings,
    /** True: a real capture tab is opened for the lane (without taking focus). */
    val openTab: Boolean,
    val title: String,
)

/** A lane's running capture: its identity, tab (if any), controller and the session it started. */
internal class LaneCaptureHandle(
    val lane: TestLaneTabRef,
    val tabId: String?,
    val controller: TabCaptureController,
    val session: CaptureSession,
    val device: CaptureDevice,
) {
    /** The notes of a lane without a tab; null when the notes live in its tab. */
    val notes: LaneNotes? = if (tabId == null) LaneNotes() else null

    private var tablessStop: Job? = null

    /** Starts the stop of a tabless lane's controller once and returns that one job, however often this is called. */
    @Synchronized
    fun stopTablessOnce(start: () -> Job): Job = tablessStop ?: start().also { tablessStop = it }
}

/** The recording of one lane for the engine. */
internal class AppLaneCapture(
    private val app: AppState,
    private val handle: LaneCaptureHandle,
    private val laneDir: File,
) : LaneCapture {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopRequested = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val windowJobs = CopyOnWriteArrayList<Job>()
    private val lostSignal = CompletableDeferred<String>()

    override val lost: Deferred<String> get() = lostSignal
    override val logFile: File = handle.session.logFile
    override val sessionDirectory: File = handle.session.directory
    override val tabId: String? get() = handle.tabId
    override val isRecording: Boolean get() = handle.controller.snapshot.value.state == RecorderState.RECORDING
    override val diagnostics: List<String> get() = handle.controller.snapshot.value.diagnostics

    init {
        // The recorder leaving RECORDING without our asking (the user pressed Stop on the lane's tab, closed it, or the device went
        // away) ends the lane: nobody is recording any more, so its evidence would silently stop.
        scope.launch {
            handle.controller.snapshot.first { it.state != RecorderState.RECORDING && it.state != RecorderState.IDLE }
            if (!stopRequested.get()) lostSignal.complete(LANE_CAPTURE_LOST_REASON)
        }
    }

    override fun flushedLogLength(): Long =
        runCatching { handle.controller.snapshotForExport().logLength }.getOrElse { logFile.length() }

    override fun readScreen(): ByteArray = handle.controller.readScreen()

    private fun markerWriter(): CaptureMarkerWriter {
        val target: MarkerNotesTarget = handle.tabId?.let { TabMarkerNotes(app, it) } ?: checkNotNull(handle.notes)
        return CaptureMarkerWriter(
            target = target,
            boundary = handle.controller::snapshotForExport,
            liveBoundary = { if (stopRequested.get()) null else runCatching { handle.controller.snapshotForExport() }.getOrNull() },
            // The stopped session as the recorder left it on disk: the registered object is the start-time one, with no elapsed time.
            stoppedSession = { _ -> readCaptureSessionDirectory(handle.session.directory) },
        )
    }

    override suspend fun addMarker(request: LaneMarkerRequest): LaneMarkerOutcome = withContext(Dispatchers.IO) {
        if (stopRequested.get()) return@withContext LaneMarkerOutcome.Skipped("The lane's recording already stopped.")
        val writer = markerWriter()
        val context = writer.begin(request.label, request.noteText)
            ?: return@withContext LaneMarkerOutcome.Skipped("The capture could not give a position for the marker.")
        val afterId = writer.attachScreenshot(context) {
            request.screenshotPng?.let { MarkerShot(bytes = it, provenance = "Screenshot at the end of the step (AI test lane)") }
        }
        windowJobs += scope.launch {
            @Suppress("TooGenericExceptionCaught") // The trailing window is a bonus: the note and screenshot are already in place.
            try {
                writer.finishWindow(context, afterId)
            } catch (stop: CancellationException) {
                throw stop
            } catch (_: Exception) {
                Unit
            }
        }
        LaneMarkerOutcome.Added(context.marker.id, context.noteId, screenshotAttached = afterId != context.noteId)
    }

    override suspend fun awaitMarkers(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { windowJobs.toList().joinAll() }
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        stopRequested.set(true)
        try {
            app.finishLaneCapture(handle, LANE_STOP_WAIT_MS)
        } finally {
            persistNotes()
            scope.cancel()
        }
    }

    /** The lane's current notes: its tab's while the tab is open, else what the lane held or wrote at the end. */
    private fun currentNotes(): Annotations? {
        handle.notes?.let { return it.snapshot() }
        handle.tabId?.let { id -> app.laneTabNotes(id)?.let { return it } }
        return readLaneNotesFile(laneDir)
    }

    private fun persistNotes() {
        val notes = runCatching { currentNotes() }.getOrNull()
        val file = File(laneDir, LANE_NOTES_FILE_NAME)
        if (notes == null || notes.blocks.isEmpty()) {
            runCatching { file.delete() }
        } else {
            runCatching { file.writeText(notes.annotationsToken()) }
        }
    }

    override fun exportArchive(destination: File): CaptureExportResult {
        val notes = currentNotes()
        val live = !stopRequested.get() && handle.controller.snapshot.value.state == RecorderState.RECORDING
        return if (live) {
            // Like a live Save ZIP: a flushed point-in-time archive of everything so far while the recorder keeps running.
            val request = CaptureExportRequest(
                destination = destination,
                range = CaptureRange.ALL,
                includeVideo = handle.session.settings.recordVideo,
                cutoffElapsedMs = 0L,
            )
            handle.controller.export(request, notes = notes)
        } else {
            app.captureService.exportRetainedSession(handle.session.id, destination, notes)
        }
    }
}

/** The notes a lane wrote at its end (see [AppLaneCapture.stop]), or null when it has none. */
internal fun readLaneNotesFile(laneDir: File): Annotations? =
    File(laneDir, LANE_NOTES_FILE_NAME).takeIf { it.isFile }?.let { file -> runCatching { file.readText().annotationsFromToken() }.getOrNull() }

/** Opens the lanes of a run in production: a real capture per lane, through AppState. */
internal class ProductionLaneOpener(private val app: AppState) : LaneDeviceOpener {
    override suspend fun open(serial: String, laneDir: File, recordVideo: Boolean): TestDeviceSession =
        open(LaneOpenRequest("", "", serial, laneDir, "agent", headlessCaptureSettings(recordVideo), openTab = false))

    // NonCancellable: starting a capture is a blocking sequence (adb, recorder, tab) that cannot be interrupted half way. A run that is
    // cancelled meanwhile still gets the opened capture back, and the lane's own cleanup (which runs even when cancelled) stops it;
    // dropping the result instead would leave a recording nobody owns and a device nobody can release.
    override suspend fun open(request: LaneOpenRequest): TestDeviceSession = withContext(Dispatchers.IO + NonCancellable) {
        val tools = app.captureToolsProvider(request.capture)
        val device = runCatching { tools.listDevices().map { it.device }.firstOrNull { it.serial == request.serial } }.getOrNull()
            ?: CaptureDevice(request.serial, state = "device")
        val start = LaneCaptureStart(
            lane = TestLaneTabRef(request.runId, request.laneId),
            device = device,
            captureRoot = File(request.laneDir, LANE_CAPTURE_DIRECTORY),
            settings = request.capture,
            openTab = request.openTab,
            title = "Test lane — ${request.agentLabel} · ${device.model}",
        )
        val handle = app.beginLaneCapture(start)
        TestDeviceSession.forCapture(request.serial, request.laneDir, tools, AppLaneCapture(app, handle, request.laneDir))
    }
}
