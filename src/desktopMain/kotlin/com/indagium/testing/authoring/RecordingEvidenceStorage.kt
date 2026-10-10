package com.indagium.testing.authoring

import com.indagium.testing.device.UiNode
import com.indagium.testing.store.MAX_RECORDING_FILE_BYTES
import com.indagium.testing.store.RECORDING_FILE_NAME
import com.indagium.testing.store.RECORDING_SCREENS_DIR_NAME
import com.indagium.testing.store.RECORDING_SCREEN_EXTENSION
import com.indagium.testing.store.RECORDING_VIDEO_DIR_NAME
import com.indagium.testing.store.RecordingEvidenceFiles
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.readBoundedTestAsset
import com.indagium.utils.writeFileAtomically
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

// Saving and reading the evidence of an applied recording. Saving builds the whole recording folder in a staging folder next to the final
// ones and publishes it with one rename, so a crash or a full disk never leaves half a recording where a reopen would find it. Privacy
// and the size cap are decided here, before anything is written: see [sanitizeEvidence] and [RecordingEvidenceSaver.saveNew].

/** The most one stored recording may take: its file, its images and its video together. Over it the video goes first, then the images. */
const val MAX_RECORDING_EVIDENCE_BYTES = 200L * 1024L * 1024L

private const val IMAGE_REF_CHARS = 32
private const val BYTES_PER_MB = 1024L * 1024L
private const val SHA_256 = "SHA-256"

/** Resource id of the one node a screen reading kept inside a password window: it says a password field was possibly on screen. */
internal const val REDACTED_NODE_RESOURCE_ID = "indagium:redacted-password-window"

/** Everything of a stopped recording that is saved: its review state, its screen readings and screenshots, its video and its device. */
internal class SessionEvidence(
    val snapshot: TestStepRecordingSnapshot,
    val screenStates: List<RecordingScreenState>,
    val probeScreenshots: List<RecordingScreenshotEvidence>,
    val video: RecordedVideoTimeline?,
    val deviceSerial: String,
)

/** [SessionEvidence] after the privacy rules: what may be written to disk. */
internal class SanitizedEvidence(
    val rows: List<RecordedTestStep>,
    val rawRows: List<RecordedTestStep>?,
    val screenStates: List<RecordingScreenState>,
    val probeScreenshots: List<RecordingScreenshotEvidence>,
    val privacy: StoredPrivacy,
)

/**
 * Applies the rewrite's own password rule ([RewriteSensitivity]) to what is about to be stored. When no input touched a password field the
 * evidence is stored as it is. Otherwise, inside the sensitive windows: an input that typed a password, or happened in a window, keeps no
 * screenshot; a screenshot taken in a window is dropped; a screen reading in a window keeps only its time span and app (and one marker node
 * saying a password field may have been on screen, so the windows are found again when the recording is reopened) - no text, no image.
 */
@Suppress("CyclomaticComplexMethod") // One pass over rows, screen readings and screenshots that all share the same window checks.
internal fun sanitizeEvidence(evidence: SessionEvidence): SanitizedEvidence {
    val snapshot = evidence.snapshot
    val statesBySeq = evidence.screenStates.associateBy { it.seq }
    val recorded = (snapshot.rawSteps ?: snapshot.steps).filterNot { it.authorAdded }
    val inputs = rewriteInputsOf(recorded, statesBySeq::get)
    val sensitivity = RewriteSensitivity(inputs)
    if (sensitivity.windows.isEmpty()) {
        return SanitizedEvidence(snapshot.steps, snapshot.rawSteps, evidence.screenStates, evidence.probeScreenshots, StoredPrivacy())
    }
    val withheldInputIds = inputs.filter(sensitivity::inputWithheld).mapTo(HashSet()) { it.row.id }
    var droppedShots = 0

    fun scrub(row: RecordedTestStep): RecordedTestStep {
        if (row.authorAdded) return row
        val sources = row.sourceInputIds.ifEmpty { listOf(row.id) }
        val rowWithheld = sources.any { it in withheldInputIds } || sensitivity.spanWithheld(row.inputStartMs, row.inputAtMs)

        fun keep(shot: RecordingScreenshotEvidence?): RecordingScreenshotEvidence? {
            if (shot == null) return null
            val withheld = rowWithheld || sensitivity.screenshotWithheld(shot, row.inputAtMs)
            if (withheld) droppedShots++
            return shot.takeUnless { withheld }
        }
        val own = row.screenshotJpeg?.let {
            RecordingScreenshotEvidence(
                it, row.screenshotSource ?: RecordingScreenshotSource.MIRROR_INPUT, row.screenshotAcquiredAtMs,
                row.screenshotAcquisitionFinishedAtMs, row.screenshotTimingUncertain,
            )
        }
        val ownKept = keep(own)
        return row.copy(
            screenshotJpeg = ownKept?.jpeg,
            useScreenshotAsExpected = row.useScreenshotAsExpected && ownKept != null,
            beforeScreenshot = keep(row.beforeScreenshot),
            afterScreenshot = keep(row.afterScreenshot),
        )
    }

    var redactedStates = 0
    val states = evidence.screenStates.map { state ->
        val shotStart = state.screenshotStartedAt
        val inWindow = sensitivity.spanWithheld(state.startedAt, state.finishedAt) ||
            (shotStart != null && sensitivity.spanWithheld(shotStart, state.screenshotFinishedAt ?: shotStart))
        if (!inWindow) return@map state
        if (state.nodes.isNotEmpty() || state.screenshotJpeg != null) redactedStates++
        if (state.screenshotJpeg != null) droppedShots++
        state.redacted()
    }
    val probes = evidence.probeScreenshots.filter { shot ->
        val withheld = sensitivity.screenshotWithheld(shot, shot.acquiredAtMs ?: 0L)
        if (withheld) droppedShots++
        !withheld
    }
    return SanitizedEvidence(
        rows = snapshot.steps.map(::scrub),
        rawRows = snapshot.rawSteps?.map(::scrub),
        screenStates = states,
        probeScreenshots = probes,
        privacy = StoredPrivacy(
            passwordSensitive = true,
            windows = sensitivity.windows,
            droppedScreenStates = redactedStates,
            droppedScreenshots = droppedShots,
        ),
    )
}

/** The reading reduced to its time span, app and one blank password-field marker. */
private fun RecordingScreenState.redacted(): RecordingScreenState = copy(
    nodes = listOf(
        UiNode(0, 0, 0, 0, "", "", REDACTED_NODE_RESOURCE_ID, "", false, false, false, packageName.orEmpty(), password = true, focused = true),
    ),
    screenshotJpeg = null,
)

// ── Images on disk ───────────────────────────────────────────────────

/** Images by content: the reference is the first [IMAGE_REF_CHARS] hex digits of the SHA-256, the file `<ref>.jpg`. Writes never overwrite. */
internal class DiskRecordingImages(private val dir: File) : RecordingImageStore {
    private val sizes = LinkedHashMap<String, Long>()

    /** The bytes of every image put so far, by reference. */
    val totalBytes: Long get() = sizes.values.sum()

    override fun put(bytes: ByteArray): String {
        val ref = referenceOf(bytes)
        sizes[ref] = bytes.size.toLong()
        val file = File(dir, ref + RECORDING_SCREEN_EXTENSION)
        if (!file.exists()) {
            dir.mkdirs()
            writeBytesAtomically(file, bytes)
        }
        return ref
    }

    override fun get(ref: String): ByteArray? = readBoundedTestAsset(File(dir, ref + RECORDING_SCREEN_EXTENSION))
}

/** Collects images in memory to measure them (and write them later); nothing touches the disk. */
internal class CollectingRecordingImages : RecordingImageStore {
    val images = LinkedHashMap<String, ByteArray>()

    val totalBytes: Long get() = images.values.sumOf { it.size.toLong() }

    override fun put(bytes: ByteArray): String = referenceOf(bytes).also { images.putIfAbsent(it, bytes) }

    override fun get(ref: String): ByteArray? = images[ref]
}

private fun referenceOf(bytes: ByteArray): String =
    MessageDigest.getInstance(SHA_256).digest(bytes).joinToString("") { "%02x".format(it) }.take(IMAGE_REF_CHARS)

private fun writeBytesAtomically(target: File, bytes: ByteArray) {
    val temporary = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
    try {
        temporary.writeBytes(bytes)
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temporary.delete()
    }
}

// ── Saving ───────────────────────────────────────────────────────────

/** What a save wrote: the recording's reference, its size, whether the video made it, and notes for the person. */
internal class SavedRecording(val recordingId: String, val bytes: Long, val videoStored: Boolean, val privacy: StoredPrivacy, val notes: List<String>)

internal class RecordingEvidenceSaver(
    private val files: RecordingEvidenceFiles,
    private val clock: () -> Long = System::currentTimeMillis,
    private val capBytes: Long = MAX_RECORDING_EVIDENCE_BYTES,
) {
    /**
     * Writes a new recording [recordingId] for [caseId] in [suiteId]: the reviewed rows of [evidence] (the steps [stepIds] were made from
     * them), their screenshots and screen readings and, unless a password was typed or the size cap would be passed, the recorded video.
     * Blocking disk work: call on an IO thread, with no lock held. Fails with [StoreResult.Invalid] and leaves nothing behind.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught") // Each failure has its own message; the staging folder is removed on every path.
    fun saveNew(suiteId: String, caseId: String, recordingId: String, evidence: SessionEvidence, stepIds: List<String>): StoreResult<SavedRecording> {
        val sanitized = sanitizeEvidence(evidence)
        val exported = evidence.video?.exportForStorage()
        val capMegabytes = capBytes / BYTES_PER_MB
        val notes = ArrayList<String>()
        var video = videoPlanFor(sanitized.privacy.passwordSensitive, evidence.video != null, exported)
        val measured = CollectingRecordingImages()
        val jsonBytes = encodeStoredRecording(recordingOf(recordingId, caseId, evidence, stepIds, sanitized, video.info(exported)), measured)
            .toByteArray(Charsets.UTF_8).size.toLong()
        var total = jsonBytes + measured.totalBytes + (exported?.takeIf { video.stored }?.bytes ?: 0L)
        if (total > capBytes && video.stored) {
            notes += "The recorded video (${(exported?.bytes ?: 0L) / BYTES_PER_MB} MB) was not stored: this recording would use more than $capMegabytes MB."
            video = VideoPlan.none("The recording would use more than $capMegabytes MB with its video.")
            total = jsonBytes + measured.totalBytes
        }
        val keepImages = total <= capBytes
        if (!keepImages) notes += "The screenshots were not stored either: they alone would use more than $capMegabytes MB."
        val staging = try {
            files.newStagingDir(suiteId) ?: return StoreResult.Invalid("The recording folder for suite '$suiteId' cannot be used.")
        } catch (failure: IOException) {
            return StoreResult.Invalid("Could not create the recording folder: ${failure.message}")
        }
        var published = false
        try {
            val source = if (keepImages) sanitized else sanitized.withoutImages()
            val info = video.copyInto(File(staging, RECORDING_VIDEO_DIR_NAME), exported)
            val recording = recordingOf(recordingId, caseId, evidence, stepIds, source, info).copy(notices = notes.toList())
            val text = encodeStoredRecording(recording, DiskRecordingImages(File(staging, RECORDING_SCREENS_DIR_NAME)))
            writeFileAtomically(File(staging, RECORDING_FILE_NAME)) { it.write(text) }
            published = files.publish(suiteId, recordingId, staging)
            if (!published) return StoreResult.Invalid("The recording folder for suite '$suiteId' cannot be used.")
            val saved = SavedRecording(recordingId, files.sizeOf(suiteId, recordingId), info.stored, source.privacy, notes + videoNotes(source.privacy, info))
            return StoreResult.Ok(saved)
        } catch (failure: IOException) {
            return StoreResult.Invalid("Could not save the recording evidence: ${failure.message ?: failure::class.simpleName}")
        } catch (failure: RuntimeException) {
            return StoreResult.Invalid("Could not save the recording evidence: ${failure.message ?: failure::class.simpleName}")
        } finally {
            if (!published) runCatching { staging.deleteRecursively() }
        }
    }

    /**
     * Writes the reviewed rows of a reopened recording back to its stored `recording.json` (atomically) and records [stepIds] as the steps
     * it now produced. Its images, screen readings and video are not touched.
     */
    @Suppress("TooGenericExceptionCaught") // Reported as a message; the stored recording stays as it was on any failure.
    fun updateRows(suiteId: String, original: StoredRecording, evidence: SessionEvidence, stepIds: List<String>): StoreResult<SavedRecording> {
        val dir = files.recordingDir(suiteId, original.recordingId)?.takeIf { File(it, RECORDING_FILE_NAME).isFile }
            ?: return StoreResult.NotFound("recording", original.recordingId)
        return try {
            val snapshot = evidence.snapshot
            val updated = original.copy(
                updatedAtMs = clock(),
                stepIds = stepIds,
                rows = snapshot.steps,
                rawRows = snapshot.rawSteps,
                rewriteApplied = snapshot.rewriteApplied,
                rewriteNotes = snapshot.rewriteNotes,
                excludedSourceInputIds = snapshot.excludedSourceInputIds,
            )
            val text = encodeStoredRecording(updated, DiskRecordingImages(File(dir, RECORDING_SCREENS_DIR_NAME)))
            writeFileAtomically(File(dir, RECORDING_FILE_NAME)) { it.write(text) }
            val bytes = files.sizeOf(suiteId, original.recordingId)
            StoreResult.Ok(SavedRecording(original.recordingId, bytes, original.video.stored, original.privacy, emptyList()))
        } catch (failure: IOException) {
            StoreResult.Invalid("Could not update the stored recording: ${failure.message ?: failure::class.simpleName}")
        } catch (failure: RuntimeException) {
            StoreResult.Invalid("Could not update the stored recording: ${failure.message ?: failure::class.simpleName}")
        }
    }

    private fun recordingOf(
        recordingId: String,
        caseId: String,
        evidence: SessionEvidence,
        stepIds: List<String>,
        sanitized: SanitizedEvidence,
        video: StoredVideoInfo,
    ): StoredRecording {
        val now = clock()
        val snapshot = evidence.snapshot
        return StoredRecording(
            recordingId = recordingId,
            caseId = caseId,
            deviceSerial = evidence.deviceSerial,
            createdAtMs = now,
            updatedAtMs = now,
            stepIds = stepIds,
            rows = sanitized.rows,
            rawRows = sanitized.rawRows,
            rewriteApplied = snapshot.rewriteApplied,
            rewriteNotes = snapshot.rewriteNotes,
            excludedSourceInputIds = snapshot.excludedSourceInputIds,
            screenStates = sanitized.screenStates,
            probeScreenshots = sanitized.probeScreenshots,
            warnings = snapshot.warnings,
            privacy = sanitized.privacy,
            video = video,
        )
    }

    private fun videoPlanFor(passwordSensitive: Boolean, hadVideo: Boolean, exported: VideoStorageExport?): VideoPlan = when {
        passwordSensitive -> VideoPlan.none(PASSWORD_VIDEO_REASON)
        !hadVideo -> VideoPlan.none("The recording had no video: the Indagium mirror was not connected.")
        exported == null -> VideoPlan.none("The recorded video was not usable.")
        else -> VideoPlan.store()
    }

    private fun videoNotes(privacy: StoredPrivacy, video: StoredVideoInfo): List<String> = buildList {
        if (privacy.passwordSensitive) add(RECORDING_PRIVACY_STATEMENT)
        if (!video.stored && !privacy.passwordSensitive && video.reason != null) add("No video was stored: ${video.reason}")
    }

    private fun SanitizedEvidence.withoutImages() = SanitizedEvidence(
        rows = rows.map { it.withoutImages() },
        rawRows = rawRows?.map { it.withoutImages() },
        screenStates = screenStates.map { it.copy(screenshotJpeg = null) },
        probeScreenshots = emptyList(),
        privacy = privacy,
    )

    private fun RecordedTestStep.withoutImages() =
        copy(screenshotJpeg = null, beforeScreenshot = null, afterScreenshot = null, useScreenshotAsExpected = false)

    private companion object {
        const val PASSWORD_VIDEO_REASON = "A password was typed while recording; the video of such a recording is never stored."
    }
}

/** Whether the video is stored and, if so, the copy of its files. */
private class VideoPlan private constructor(val stored: Boolean, private val reason: String?) {
    fun info(export: VideoStorageExport?): StoredVideoInfo =
        if (!stored || export == null) StoredVideoInfo(stored = false, reason = reason) else exportInfo(export)

    /** Copies the segments and the index into [target] and returns what the stored recording says about them. */
    fun copyInto(target: File, export: VideoStorageExport?): StoredVideoInfo {
        if (!stored || export == null) return StoredVideoInfo(stored = false, reason = reason)
        Files.createDirectories(target.toPath())
        export.segmentFiles.filter { isStoredSegmentFileName(it.name) }.forEach { Files.copy(it.toPath(), File(target, it.name).toPath()) }
        export.indexFile?.let { Files.copy(it.toPath(), File(target, RecordedVideoTimeline.INDEX_FILE_NAME).toPath()) }
        return exportInfo(export)
    }

    private fun exportInfo(export: VideoStorageExport) = StoredVideoInfo(
        stored = true,
        bytes = export.bytes,
        sessionStartedAtMs = export.sessionStartedAtMs,
        stoppedDurationMs = export.stoppedDurationMs,
        stoppedWallClockMs = export.stoppedWallClockMs,
        gapCount = export.gapCount,
        capReached = export.capReached,
        warning = export.warning,
        packetCount = export.packetCount,
    )

    companion object {
        fun store() = VideoPlan(true, null)

        fun none(reason: String) = VideoPlan(false, reason)
    }
}

// ── Reading ──────────────────────────────────────────────────────────

/** A stored recording read back, with its video opened read-only when it has one. */
internal class LoadedRecording(val recording: StoredRecording, val video: StoredVideoTimeline?, val notes: List<String>)

/** Reads the stored recording [recordingId] of [suiteId]. Never throws; nothing on disk is changed. */
@Suppress("ReturnCount") // Each unreadable state has its own message.
internal fun loadStoredRecording(files: RecordingEvidenceFiles, suiteId: String, recordingId: String): StoreResult<LoadedRecording> {
    val dir = files.recordingDir(suiteId, recordingId) ?: return StoreResult.NotFound("recording", recordingId)
    val file = File(dir, RECORDING_FILE_NAME)
    if (!file.isFile) return StoreResult.NotFound("recording", recordingId)
    if (file.length() > MAX_RECORDING_FILE_BYTES) return StoreResult.Invalid("The stored recording is too large to read.")
    val text = try {
        file.readText()
    } catch (failure: IOException) {
        return StoreResult.Invalid("Could not read the stored recording: ${failure.message}")
    }
    val recording = decodeStoredRecording(text, DiskRecordingImages(File(dir, RECORDING_SCREENS_DIR_NAME))).getOrElse {
        return StoreResult.Invalid(it.message ?: "The stored recording could not be read.")
    }
    if (recording.recordingId != recordingId) return StoreResult.Invalid("The stored recording belongs to a different id; it was not opened.")
    val notes = ArrayList<String>()
    val video = if (recording.video.stored) {
        val videoDir = File(dir, RECORDING_VIDEO_DIR_NAME)
        val segments = readStoredVideoSegments(videoDir)
        if (segments.isEmpty()) {
            notes += "The stored video is missing or unreadable, so the rewrite works from the stored screenshots only."
            null
        } else {
            StoredVideoTimeline(
                directory = videoDir,
                sessionStartedAtMs = recording.video.sessionStartedAtMs,
                stoppedDurationMs = recording.video.stoppedDurationMs,
                stoppedWallClockMs = recording.video.stoppedWallClockMs,
                gapCount = recording.video.gapCount,
                capReached = recording.video.capReached,
                warning = recording.video.warning,
                packetCount = recording.video.packetCount,
                segments = segments,
            )
        }
    } else {
        null
    }
    return StoreResult.Ok(LoadedRecording(recording, video, notes))
}
