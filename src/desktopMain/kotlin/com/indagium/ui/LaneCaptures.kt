package com.indagium.ui

import com.indagium.capture.CAPTURE_SESSION_FILE_NAME
import com.indagium.capture.CaptureArchiveExporter
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureExportResult
import com.indagium.capture.CaptureRange
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSessionBoundary
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureStatus
import com.indagium.capture.DEFAULT_VIDEO_COVERAGE_WAIT_MS
import com.indagium.capture.RecorderState
import com.indagium.capture.readCaptureSessionDirectory
import com.indagium.capture.sessionJson
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    val laneSession: CaptureSession = session,
    /** The manual source tab when this lane borrows its active capture; the lane itself has no UI tab. */
    val borrowedSourceTabId: String? = null,
) {
    val isBorrowed: Boolean get() = borrowedSourceTabId != null

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
    private val borrowedEvidenceLock = Any()
    private val borrowedCopyDiagnostics = CopyOnWriteArrayList<String>()

    @Volatile
    private var borrowedEvidenceFrozen = false

    override val lost: Deferred<String> get() = lostSignal
    override val logFile: File = handle.laneSession.logFile
    override val readLogFile: File
        get() = if (handle.isBorrowed && !stopRequested.get() && handle.session.logFile.isFile) handle.session.logFile else logFile
    override val sessionDirectory: File = handle.laneSession.directory
    override val tabId: String? get() = handle.tabId
    override val isRecording: Boolean
        get() = !stopRequested.get() && handle.controller.snapshot.value.state == RecorderState.RECORDING
    override val diagnostics: List<String>
        get() = handle.controller.snapshot.value.diagnostics + borrowedCopyDiagnostics

    init {
        // The recorder leaving RECORDING without our asking (the user pressed Stop on the lane's tab, closed it, or the device went
        // away) ends the lane: nobody is recording any more, so its evidence would silently stop.
        scope.launch {
            handle.controller.snapshot.first { it.state != RecorderState.RECORDING && it.state != RecorderState.IDLE }
            if (!stopRequested.get()) lostSignal.complete(LANE_CAPTURE_LOST_REASON)
        }
        if (handle.isBorrowed) {
            initializeBorrowedEvidence()
            startBorrowedEvidenceRefresh()
        }
    }

    /** Constructor cleanup must cover every failure while the source's capture remains live. */
    @Suppress("TooGenericExceptionCaught")
    private fun initializeBorrowedEvidence() {
        try {
            snapshotBorrowedEvidence(final = false)
        } catch (failure: Throwable) {
            scope.cancel()
            throw failure
        }
    }

    /** A failed required snapshot means the lane can no longer promise independent evidence. */
    @Suppress("TooGenericExceptionCaught")
    private fun startBorrowedEvidenceRefresh() {
        scope.launch {
            while (!stopRequested.get()) {
                try {
                    snapshotBorrowedEvidence(final = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    recordBorrowedRefreshFailure(failure)
                    break
                }
                delay(BORROWED_EVIDENCE_POLL_MS)
            }
        }
    }

    private fun recordBorrowedRefreshFailure(failure: Throwable) {
        val detail = failure.message ?: failure::class.simpleName.orEmpty()
        borrowedCopyDiagnostics += "Run-local capture evidence could not be refreshed: $detail"
        lostSignal.complete("The borrowed capture evidence could not be preserved: $detail")
    }

    override fun flushedLogLength(): Long {
        if (handle.isBorrowed && stopRequested.get()) return logFile.length()
        return runCatching { handle.controller.snapshotForExport().logLength }.getOrElse { readLogFile.length() }
    }

    override fun readScreen(): ByteArray = handle.controller.readScreen()

    private fun markerWriter(): CaptureMarkerWriter {
        val target: MarkerNotesTarget = handle.tabId?.let { TabMarkerNotes(app, it) } ?: checkNotNull(handle.notes)
        return CaptureMarkerWriter(
            target = target,
            boundary = ::markerBoundary,
            liveBoundary = { if (stopRequested.get()) null else runCatching { handle.controller.snapshotForExport() }.getOrNull() },
            // Borrowed lanes stop at their frozen local boundary while the user's source capture may continue for hours.
            stoppedSession = { _ -> stoppedMarkerSession() },
        )
    }

    private fun markerBoundary(): CaptureSessionBoundary =
        if (handle.isBorrowed && stopRequested.get()) localBoundary() else handle.controller.snapshotForExport()

    private fun stoppedMarkerSession(): CaptureSession? =
        if (handle.isBorrowed && stopRequested.get()) {
            readCaptureSessionDirectory(handle.laneSession.directory) ?: handle.laneSession
        } else {
            readCaptureSessionDirectory(handle.session.directory)
        }

    private fun localBoundary(): CaptureSessionBoundary {
        val local = readCaptureSessionDirectory(handle.laneSession.directory) ?: handle.laneSession
        return CaptureSessionBoundary(local, local.logFile.length(), local.indexFile.length(), local.elapsedMs)
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

    @Suppress("TooGenericExceptionCaught") // Any copy failure must still freeze evidence and release the per-device claim.
    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        stopRequested.set(true)
        var borrowedFailure: Throwable? = null
        try {
            if (handle.isBorrowed) {
                try {
                    synchronized(borrowedEvidenceLock) {
                        try {
                            snapshotBorrowedEvidenceLocked(final = true)
                        } finally {
                            // A queued poll must never extend or replace the lane boundary after stop, even if final copying fails.
                            borrowedEvidenceFrozen = true
                        }
                    }
                } catch (failure: Throwable) {
                    borrowedFailure = failure
                } finally {
                    app.releaseBorrowedLaneCapture(handle)
                }
            } else {
                app.finishLaneCapture(handle, LANE_STOP_WAIT_MS)
            }
        } finally {
            persistNotes()
            scope.cancel()
        }
        borrowedFailure?.let { throw IllegalStateException("Could not freeze the lane's borrowed capture evidence", it) }
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
        if (handle.isBorrowed) {
            val boundary = synchronized(borrowedEvidenceLock) {
                if (stopRequested.get()) {
                    localBoundary()
                } else {
                    snapshotBorrowedEvidenceLocked(final = false)
                }
            }
            val request = CaptureExportRequest(
                destination = destination,
                range = CaptureRange.ALL,
                includeVideo = boundary.session.settings.recordVideo,
                cutoffElapsedMs = boundary.elapsedMs,
            )
            // The lane exports its own bounded copy, never the source tab. Its checkpoint, notes and files remain independent after
            // Stop and after the user later deletes the manual source capture.
            return CaptureArchiveExporter(videoCoverageWaitMs = DEFAULT_VIDEO_COVERAGE_WAIT_MS).export(boundary.session, request, notes = notes)
        }
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

    private fun snapshotBorrowedEvidence(final: Boolean): CaptureSessionBoundary = synchronized(borrowedEvidenceLock) {
        snapshotBorrowedEvidenceLocked(final)
    }

    private fun snapshotBorrowedEvidenceLocked(final: Boolean): CaptureSessionBoundary {
        check(handle.isBorrowed) { "Only borrowed captures have run-local borrowed evidence." }
        if (borrowedEvidenceFrozen) return localBoundary()
        if (!final && stopRequested.get()) return localBoundary()
        val boundary = runCatching { handle.controller.snapshotForExport() }.getOrElse {
            val disk = readCaptureSessionDirectory(handle.session.directory) ?: throw IOException("The borrowed source capture is no longer available", it)
            CaptureSessionBoundary(disk, disk.logFile.length(), disk.indexFile.length(), disk.elapsedMs)
        }
        val source = boundary.session
        // Capture every source length at the same point-in-time boundary before doing any potentially slow file I/O.
        val videoLength = source.videoFile.length()
        val localRoot = handle.laneSession.directory.canonicalFile
        require(localRoot.toPath().startsWith(laneDir.canonicalFile.toPath())) { "Lane evidence escaped its run directory" }
        copyBorrowedPrefix(source.logFile, File(localRoot, "logs/logcat.log"), boundary.logLength, localRoot, required = true)
        copyBorrowedPrefix(source.indexFile, File(localRoot, "mapping/capture-index.jsonl"), boundary.indexLength, localRoot, required = true)
        copyBorrowedPrefix(source.videoFile, File(localRoot, "video/screen.mkv"), videoLength, localRoot)
        val sourceStopped = handle.controller.snapshot.value.state !in setOf(RecorderState.RECORDING, RecorderState.STOPPING)
        val stoppedWhileBorrowing = !sourceStopped && final
        val snapshotSession = source.copy(
            id = handle.laneSession.id,
            directory = localRoot,
            elapsedMs = maxOf(source.elapsedMs, boundary.elapsedMs),
            status = when {
                sourceStopped -> if (source.status == CaptureStatus.STOPPED) CaptureStatus.STOPPED else CaptureStatus.INTERRUPTED
                stoppedWhileBorrowing -> CaptureStatus.STOPPED
                else -> CaptureStatus.RECORDING
            },
            interruptions = if (stoppedWhileBorrowing) {
                (
                    source.interruptions +
                        "The completed test lane's evidence was frozen while its source capture continued; copied media ends at the lane boundary."
                ).distinct()
            } else {
                source.interruptions
            },
        )
        val destination = File(localRoot, CAPTURE_SESSION_FILE_NAME)
        val temporary = File(localRoot, "$CAPTURE_SESSION_FILE_NAME.tmp")
        temporary.writeText(sessionJson(snapshotSession), Charsets.UTF_8)
        runCatching {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        if (final) borrowedEvidenceFrozen = true
        return localBoundary()
    }

    private fun copyBorrowedPrefix(source: File, target: File, limit: Long, localRoot: File, required: Boolean = false) {
        if (!source.isFile || limit < 0L) {
            if (required) throw IOException("Required borrowed capture file is unavailable: $source")
            return
        }
        val safeTarget = target.canonicalFile
        require(safeTarget.toPath().startsWith(localRoot.toPath())) { "Lane evidence path escaped its run directory" }
        safeTarget.parentFile?.mkdirs()
        val frozenLength = minOf(limit, source.length())
        RandomAccessFile(source, "r").use { input ->
            RandomAccessFile(safeTarget, "rw").use { output ->
                if (output.length() > frozenLength) output.setLength(frozenLength)
                var position = output.length()
                input.seek(position)
                output.seek(position)
                val buffer = ByteArray(BORROWED_EVIDENCE_COPY_BUFFER_BYTES)
                while (position < frozenLength) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), frozenLength - position).toInt())
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    position += count
                }
                if (position != frozenLength) throw IOException("Borrowed capture file changed while it was copied: $source")
                output.fd.sync()
            }
        }
    }
}

private const val BORROWED_EVIDENCE_POLL_MS = 1_000L
private const val BORROWED_EVIDENCE_COPY_BUFFER_BYTES = 64 * 1024

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
    @Suppress("TooGenericExceptionCaught") // Release the device claim if borrowed session construction fails for any reason.
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
        try {
            TestDeviceSession.forCapture(request.serial, request.laneDir, tools, AppLaneCapture(app, handle, request.laneDir))
        } catch (failure: Throwable) {
            if (handle.isBorrowed) app.releaseBorrowedLaneCapture(handle)
            throw failure
        }
    }
}
