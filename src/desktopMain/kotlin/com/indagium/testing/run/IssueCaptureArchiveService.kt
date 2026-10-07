package com.indagium.testing.run

import com.indagium.capture.CaptureArchiveExporter
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureExportResult
import com.indagium.capture.CaptureRange
import com.indagium.capture.CaptureSession
import com.indagium.capture.readCaptureSessionDirectory
import com.indagium.model.Annotations
import com.indagium.testing.device.LANE_CAPTURE_DIRECTORY
import com.indagium.testing.model.IssueAttachment
import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.isPendingCaptureArchive
import com.indagium.testing.store.IssueStore
import com.indagium.testing.store.MAX_ISSUE_CAPTURE_ARCHIVE_BYTES
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.util.UUID

// The capture archive of an issue: the WHOLE recording of the lane that saw the failure (all of the log, including the device's
// earlier logs when they were recorded, the whole video, audio tracks and the lane's notes with every AI marker) as the same
// ZIP the capture tab's Save ZIP writes, so Indagium opens it with "Bug report / archive" and shows the markers at the failure.
// It is far bigger than the other evidence, so it has its own handling: the draft only carries a pending wish for it, it is
// exported (with progress and a disk-space check) when the issue is created or sent, it is moved, not copied, into the issue
// folder, it has its own, much higher size cap, and a tracker agent is only ever given its path, never its bytes.

private const val ARCHIVE_FILE_NAME = "capture-archive.zip"
private const val BYTES_PER_MEGABYTE = 1024L * 1024L
private const val PENDING_NOTE = "Exported from the whole lane recording when the issue is created or sent. Untick it to leave it out."
const val CAPTURE_ARCHIVE_LABEL = "Capture archive (log + video + audio + notes) .zip"

/** How a lane that is still recording exports its archive (like a live Save ZIP); null for a lane whose recording is over. */
internal fun interface LiveLaneArchiveExporter {
    fun export(destination: File): CaptureExportResult
}

internal data class IssueCaptureArchiveExport(val issue: IssueRecord, val bytes: Long, val includedVideo: Boolean)

/** The session folder a lane recorded into (`lanes/<laneId>/capture/<sessionId>`), or null when the run folder has none. */
internal fun laneCaptureSessionDirectory(runDir: File, lane: LaneResult): File? = runCatching {
    val root = runDir.canonicalFile
    val captureRoot = File(root, "lanes/${lane.laneId}/$LANE_CAPTURE_DIRECTORY").canonicalFile
    require(captureRoot.path.startsWith(root.path + File.separator))
    val logged = lane.logPath?.let { resolveRunArtifact(runDir, it) }?.parentFile?.parentFile?.canonicalFile
        ?.takeIf { it.parentFile == captureRoot && File(it, "logs/logcat.log").isFile }
    logged ?: captureRoot.listFiles { file -> file.isDirectory }.orEmpty()
        .map { it.canonicalFile }
        .filter { it.parentFile == captureRoot && readCaptureSessionDirectory(it)?.device?.serial == lane.config.deviceSerial }
        .maxByOrNull { it.lastModified() }
}.getOrNull()

/** What the archive of [session] will roughly weigh: its raw log and video (screenshots and the notes are small beside them). */
internal fun estimateCaptureArchiveBytes(session: CaptureSession): Long =
    session.logFile.length() + session.videoFile.takeIf { it.isFile }?.length().let { it ?: 0L }

/** The pending capture-archive wish a draft built for [lane] carries, or null when the lane left no recording in the run folder. */
internal fun pendingCaptureArchiveAttachment(runDir: File, lane: LaneResult): IssueAttachment? {
    val directory = laneCaptureSessionDirectory(runDir, lane) ?: return null
    val session = readCaptureSessionDirectory(directory) ?: return null
    return IssueAttachment(
        kind = IssueAttachmentKind.CAPTURE_ARCHIVE,
        label = CAPTURE_ARCHIVE_LABEL,
        fileName = ARCHIVE_FILE_NAME,
        sizeBytes = estimateCaptureArchiveBytes(session),
        include = true,
        note = PENDING_NOTE,
    )
}

private fun mb(bytes: Long) = "%.1f MB".format(java.util.Locale.ROOT, bytes.toDouble() / BYTES_PER_MEGABYTE)

/**
 * Exports the capture archive of the lane behind issue [issueId] and makes it the issue's attachment (replacing the pending one).
 * [liveExport] is how a lane that is still recording exports; [notes] reads the lane's notes (its markers) for a lane whose recording
 * is over. A no-op that succeeds when the issue already holds the archive or does not want one. Runs the export on IO, interruptible.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod", "ThrowsCount", "TooGenericExceptionCaught")
internal suspend fun exportIssueCaptureArchive(
    store: IssueStore,
    issueId: String,
    run: TestRun,
    runDir: File,
    liveExport: LiveLaneArchiveExporter?,
    notes: () -> Annotations?,
    exporter: CaptureArchiveExporter = CaptureArchiveExporter(),
    freeSpace: (File) -> Long = File::getUsableSpace,
    progress: (String) -> Unit = {},
    cancellationCheck: () -> Unit = {},
): Result<IssueCaptureArchiveExport> {
    var staging: File? = null
    return try {
        val callerContext = currentCoroutineContext()
        val issue = store.load(issueId) ?: return Result.failure(IllegalArgumentException("Issue '$issueId' was not found."))
        require(!issue.readOnly) { "This issue was saved by a newer version and is read-only here." }
        require(issue.source.runId == run.id) { "The issue does not belong to run '${run.id}'." }
        val pending = issue.draft.attachments.firstOrNull { it.isPendingCaptureArchive }
        val held = issue.draft.attachments.firstOrNull { it.kind == IssueAttachmentKind.CAPTURE_ARCHIVE && it.storedPath != null }
        if (pending == null || !pending.include) {
            return Result.success(IssueCaptureArchiveExport(issue, held?.sizeBytes ?: 0L, includedVideo = false))
        }
        val lane = run.lane(issue.source.laneId) ?: throw IllegalArgumentException("The issue's source lane is missing from the run.")
        val directory = laneCaptureSessionDirectory(runDir, lane)
            ?: throw IllegalArgumentException("The lane's recording is not in the run folder any more (the run was cleaned up or never recorded).")
        val session = readCaptureSessionDirectory(directory)
            ?: throw IllegalArgumentException("The lane's recording metadata is missing or unreadable.")
        val estimate = estimateCaptureArchiveBytes(session)
        require(estimate <= MAX_ISSUE_CAPTURE_ARCHIVE_BYTES) {
            "The lane's recording is about ${mb(estimate)}, more than the ${mb(MAX_ISSUE_CAPTURE_ARCHIVE_BYTES)} an issue can hold. " +
                "Untick the capture archive and export the lane's capture from its tab instead."
        }
        val tempDir = File(store.issueDir(issueId), ".capture-archive-${UUID.randomUUID()}")
        require(tempDir.mkdirs()) { "Could not create an issue-owned temporary folder for the capture archive." }
        staging = tempDir
        val needed = estimate + session.settings.freeSpaceReserveBytes
        val free = freeSpace(tempDir)
        require(free >= needed) {
            "Not enough disk space for the capture archive: about ${mb(estimate)} are needed (plus the ${mb(session.settings.freeSpaceReserveBytes)} " +
                "reserve for other programs) but only ${mb(free)} are free where the issues are kept."
        }
        cancellationCheck()
        progress("Exporting the lane's whole recording (about ${mb(estimate)}) as a capture archive…")
        val destination = File(tempDir, ARCHIVE_FILE_NAME)
        val result = runInterruptible(Dispatchers.IO) {
            if (liveExport != null) {
                liveExport.export(destination)
            } else {
                val request = CaptureExportRequest(
                    destination = destination,
                    range = CaptureRange.ALL,
                    includeVideo = session.settings.recordVideo,
                    cutoffElapsedMs = session.elapsedMs,
                )
                exporter.export(session, request, notes = notes())
            }
        }
        currentCoroutineContext().ensureActive()
        cancellationCheck()
        val archive = result.file
        require(archive.isFile && archive.length() in 1..MAX_ISSUE_CAPTURE_ARCHIVE_BYTES) {
            "The capture archive could not be saved within the ${mb(MAX_ISSUE_CAPTURE_ARCHIVE_BYTES)} limit."
        }
        progress("Saving the capture archive (${mb(archive.length())}) with the issue…")
        val name = "capture-archive-${UUID.randomUUID()}.zip"
        val attachment = IssueAttachment(
            kind = IssueAttachmentKind.CAPTURE_ARCHIVE,
            label = CAPTURE_ARCHIVE_LABEL,
            fileName = name,
            sizeBytes = archive.length(),
            include = true,
            sourcePath = archive.absolutePath,
            note = "The whole lane recording" + (if (result.videoCoveredEndMs != null) " with video" else "") +
                ". Open it in Indagium with Bug report / archive; the AI markers are in its notes.",
        )
        currentCoroutineContext().ensureActive()
        when (val stored = store.appendAttachmentStrict(issueId, attachment, replacePending = true) { cancellationCheck(); callerContext.ensureActive() }) {
            is StoreResult.Ok -> {
                val saved = stored.value.draft.attachments.lastOrNull { it.kind == IssueAttachmentKind.CAPTURE_ARCHIVE && it.fileName == name }
                require(saved?.storedPath != null && store.attachmentFile(stored.value, saved)?.isFile == true) {
                    "The capture archive could not be verified in the issue folder."
                }
                Result.success(IssueCaptureArchiveExport(stored.value, saved.sizeBytes, includedVideo = result.videoCoveredEndMs != null))
            }
            is StoreResult.Invalid -> Result.failure(IOException(stored.reason))
            is StoreResult.NotFound -> Result.failure(IOException(stored.message))
            is StoreResult.LimitReached -> Result.failure(IOException(stored.decision.message))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    } finally {
        staging?.deleteRecursively()
    }
}
