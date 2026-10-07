package com.indagium.testing.run

import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureStatus
import com.indagium.capture.CaptureVideoClip
import com.indagium.capture.CaptureVideoCoverageProbe
import com.indagium.capture.CaptureVideoExporter
import com.indagium.capture.FfmpegCaptureVideoExporter
import com.indagium.capture.readCaptureSessionDirectory
import com.indagium.testing.model.IssueAttachment
import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueSource
import com.indagium.testing.model.TestRun
import com.indagium.testing.store.IssueStore
import com.indagium.testing.store.MAX_ISSUE_ATTACHMENT_BYTES
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max

const val ISSUE_CLIP_PADDING_MS = 5_000L
private const val CLIP_BYTES_PER_MEGABYTE = 1024 * 1024

data class IssueStepClipRequest(val startMs: Long, val endMs: Long)

data class IssueStepClipExport(val issue: IssueRecord, val actualStartMs: Long, val actualEndMs: Long, val fileBytes: Long)

/** Shared on-demand clip writer for UI and MCP. It never modifies the source recording. */
@Suppress("CyclomaticComplexMethod", "ThrowsCount", "TooGenericExceptionCaught")
internal suspend fun exportIssueStepClip(
    store: IssueStore,
    issueId: String,
    run: TestRun,
    runDir: File,
    requestedStartMs: Long? = null,
    requestedEndMs: Long? = null,
    exporter: CaptureVideoExporter = FfmpegCaptureVideoExporter(),
    progress: (String) -> Unit = {},
    cancellationCheck: () -> Unit = {},
): Result<IssueStepClipExport> {
    var staging: File? = null
    return try {
        val callerContext = currentCoroutineContext()
        val issue = store.load(issueId) ?: return Result.failure(IllegalArgumentException("Issue '$issueId' was not found."))
        require(!issue.readOnly) { "This issue was saved by a newer version and is read-only here." }
        require(issue.source.runId == run.id) { "The issue does not belong to run '${run.id}'." }
        val step = run.stepResult(issue.source.laneId, issue.source.caseId, issue.source.iteration, issue.source.stepId)
            ?: throw IllegalArgumentException("The issue's step has no saved run result.")
        val lane = run.lane(issue.source.laneId) ?: throw IllegalArgumentException("The issue's source lane is missing from the run.")
        val capture = resolveLaneCaptureSession(runDir, lane)
            ?: throw IllegalArgumentException("The lane recording metadata is missing or unreadable.")
        val videoStart = capture.videoStartElapsedMs
            ?: throw IllegalArgumentException("This lane has no recorded video for the failed step.")
        val safeVideo = resolveCaptureVideo(runDir, capture)
            ?: throw IllegalArgumentException("The lane video is missing or outside the run folder.")
        require(safeVideo.isFile && safeVideo.length() > 0) { "The lane video is empty." }

        val default = if (requestedStartMs == null || requestedEndMs == null) {
            defaultIssueStepClipRequest(capture.startedEpochMs, videoStart, capture.elapsedMs, step.startedAt, step.durationMs)
        } else {
            null
        }
        val start = requestedStartMs ?: requireNotNull(default).startMs
        val requestedEnd = requestedEndMs ?: requireNotNull(default).endMs
        require(start >= 0 && requestedEnd > start) { "Clip end must be after clip start, and times must be non-negative milliseconds." }
        val isRecording = capture.status == CaptureStatus.RECORDING
        val coverageEnd = if (isRecording) {
            requestedEnd
        } else {
            (exporter as? CaptureVideoCoverageProbe)?.let { probe ->
                runInterruptible(Dispatchers.IO) { probe.coverageEndMs(safeVideo, start, requestedEnd) }
            } ?: requestedEnd
        }
        require(coverageEnd > start) { "The recording does not cover the requested failed-step interval." }
        val end = minOf(requestedEnd, coverageEnd)
        cancellationCheck()
        val tempDir = File(store.issueDir(issueId), ".clip-${UUID.randomUUID()}")
        require(tempDir.mkdirs()) { "Could not create an issue-owned temporary folder for the clip." }
        staging = tempDir
        val output = File(tempDir, "step-clip.mkv")
        progress("Exporting the failed step with five-second context…")
        val actual: CaptureVideoClip = runInterruptible(Dispatchers.IO) {
            if (isRecording) exporter.export(safeVideo, output, start, end) else exporter.exportFinal(safeVideo, output, start, end)
        }
        currentCoroutineContext().ensureActive()
        cancellationCheck()
        require(output.isFile && output.length() in 1..MAX_ISSUE_ATTACHMENT_BYTES) {
            "The step clip could not be saved within the ${MAX_ISSUE_ATTACHMENT_BYTES / CLIP_BYTES_PER_MEGABYTE} MB attachment limit."
        }
        require(actual.coveredEndMs > actual.actualStartMs) { "The available recording does not cover the requested step interval." }
        val attachmentName = "step-${issue.source.stepNumber}-${UUID.randomUUID()}.mkv"
        val attachment = IssueAttachment(
            kind = IssueAttachmentKind.VIDEO_CLIP,
            label = "Step ${issue.source.stepNumber} video clip",
            fileName = attachmentName,
            sizeBytes = output.length(),
            include = true,
            sourcePath = output.absolutePath,
            note = "Exported recording bounds: ${actual.actualStartMs}–${actual.coveredEndMs} ms (source recording retained).",
        )
        currentCoroutineContext().ensureActive()
        cancellationCheck()
        when (val stored = store.appendAttachmentStrict(issueId, attachment) { cancellationCheck(); callerContext.ensureActive() }) {
            is StoreResult.Ok -> {
                val saved = stored.value.draft.attachments.lastOrNull { it.kind == IssueAttachmentKind.VIDEO_CLIP && it.fileName == attachmentName }
                require(saved?.storedPath != null && store.attachmentFile(stored.value, saved)?.isFile == true) {
                    "The step clip could not be verified in the issue folder."
                }
                Result.success(IssueStepClipExport(stored.value, actual.actualStartMs, actual.coveredEndMs, saved.sizeBytes))
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

@Suppress("TooGenericExceptionCaught") // The injectable exporter/probe boundary reports failures as a typed Result.
internal suspend fun defaultIssueStepClipWindow(
    run: TestRun,
    runDir: File,
    source: IssueSource,
    probe: CaptureVideoCoverageProbe = FfmpegCaptureVideoExporter(),
): Result<IssueStepClipRequest> = try {
    val lane = run.lane(source.laneId) ?: error("The issue's source lane is missing from the run.")
    val step = run.stepResult(source.laneId, source.caseId, source.iteration, source.stepId)
        ?: error("The issue's step has no saved run result.")
    val capture = resolveLaneCaptureSession(runDir, lane) ?: error("The lane recording metadata is missing or unreadable.")
    val videoStart = capture.videoStartElapsedMs ?: error("This lane has no recorded video for the failed step.")
    val base = defaultIssueStepClipRequest(capture.startedEpochMs, videoStart, capture.elapsedMs, step.startedAt, step.durationMs)
    val video = resolveCaptureVideo(runDir, capture) ?: error("The lane video is missing or outside the run folder.")
    val coverage = if (capture.status == CaptureStatus.RECORDING) {
        base.endMs
    } else {
        runInterruptible(Dispatchers.IO) { probe.coverageEndMs(video, base.startMs, base.endMs) }
    }
    require(coverage > base.startMs) { "The recording does not cover the failed step." }
    Result.success(base.copy(endMs = minOf(base.endMs, coverage)))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}

private fun resolveCaptureVideo(runDir: File, capture: CaptureSession): File? = runCatching {
    val relativePath = capture.videoFile.relativeTo(runDir.canonicalFile).invariantSeparatorsPath
    resolveRunArtifact(runDir, relativePath)?.takeIf { it.isFile && it.length() > 0 }
}.getOrNull()

private fun resolveLaneCaptureSession(runDir: File, lane: com.indagium.testing.model.LaneResult): CaptureSession? = runCatching {
    val root = runDir.canonicalFile
    val laneRoot = File(root, "lanes/${lane.laneId}").canonicalFile
    require(laneRoot.path.startsWith(root.path + File.separator))
    val captureRoot = File(laneRoot, "capture").canonicalFile
    require(captureRoot.path.startsWith(laneRoot.path + File.separator))
    val loggedSession = lane.logPath?.let { resolveRunArtifact(runDir, it)?.parentFile?.parentFile }
        ?.takeIf { it.canonicalFile.parentFile == captureRoot }
    val candidates = sequenceOf(loggedSession).filterNotNull() + captureRoot.listFiles { file -> file.isDirectory }
        .orEmpty().asSequence().filter { it.canonicalFile.parentFile == captureRoot }
    candidates.distinctBy { it.canonicalPath }
        .mapNotNull(::readCaptureSessionDirectory)
        .firstOrNull { it.device.serial == lane.config.deviceSerial && it.settings.recordVideo && it.videoFile.isFile && it.videoFile.length() > 0 }
}.getOrNull()

/** Converts the failed step's wall-clock interval to recording PTS, pads by five seconds and clamps to known coverage. */
fun defaultIssueStepClipRequest(
    captureStartedEpochMs: Long,
    videoStartElapsedMs: Long,
    captureElapsedMs: Long,
    stepStartedEpochMs: Long,
    stepDurationMs: Long,
): IssueStepClipRequest {
    val stepStartPts = stepStartedEpochMs - captureStartedEpochMs - videoStartElapsedMs
    val coverageEnd = (captureElapsedMs - videoStartElapsedMs).coerceAtLeast(0L)
    val start = max(0L, stepStartPts - ISSUE_CLIP_PADDING_MS)
    val requestedEnd = stepStartPts + stepDurationMs.coerceAtLeast(0L) + ISSUE_CLIP_PADDING_MS
    require(requestedEnd > start) { "The failed step does not overlap the available recording." }
    require(coverageEnd > start) { "The recording does not cover the failed step." }
    val end = minOf(requestedEnd, coverageEnd)
    return IssueStepClipRequest(start, end)
}
