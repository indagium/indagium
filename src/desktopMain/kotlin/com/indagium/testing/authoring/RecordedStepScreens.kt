package com.indagium.testing.authoring

import com.indagium.testing.store.MAX_RECORDING_FILE_BYTES
import com.indagium.testing.store.RECORDING_FILE_NAME
import com.indagium.testing.store.RECORDING_SCREENS_DIR_NAME
import com.indagium.testing.store.RECORDING_SCREEN_EXTENSION
import com.indagium.testing.store.RecordingEvidenceFiles
import com.indagium.testing.store.readBoundedTestAsset
import java.io.File

// A light read of one step's stored screenshots, for the step inspector's "Recorded screens". Reopening a recording
// (loadStoredRecording) reads every image of it into memory; this reads recording.json once with a store that hands back the image
// reference instead of the bytes, finds the row the step was made from, and reads only that row's before / after image files.

/** The recorded screens of one step: the moment before and after its input, and the single raw input frame when neither was kept. */
internal class RecordedStepScreens(val before: ByteArray?, val after: ByteArray?, val input: ByteArray?) {
    val isEmpty: Boolean get() = before == null && after == null && input == null
}

/** Hands back the reference as the "bytes", so decoding the file is cheap and the caller can read just the images it wants. */
private object ReferenceOnlyImages : RecordingImageStore {
    override fun put(bytes: ByteArray): String = error("read only")

    override fun get(ref: String): ByteArray = ref.toByteArray(Charsets.US_ASCII)
}

/**
 * The stored screens of [stepId] in recording [recordingId] of [suiteId], or null when the recording or the step's row is not there or
 * has no image. Blocking disk work (one JSON file and at most three images): call on an IO thread. Never throws.
 */
internal fun loadRecordedStepScreens(files: RecordingEvidenceFiles, suiteId: String, recordingId: String, stepId: String): RecordedStepScreens? {
    val dir = files.recordingDir(suiteId, recordingId) ?: return null
    val file = File(dir, RECORDING_FILE_NAME)
    if (!file.isFile || file.length() > MAX_RECORDING_FILE_BYTES) return null
    val recording = runCatching { decodeStoredRecording(file.readText(), ReferenceOnlyImages).getOrNull() }.getOrNull() ?: return null
    val position = recording.stepIds.indexOf(stepId)
    // The rows were applied one step per row; a stored recording whose lists disagree is not trusted to map a step to a row.
    if (position < 0 || recording.rows.size != recording.stepIds.size) return null
    val row = recording.rows[position]
    val screensDir = File(dir, RECORDING_SCREENS_DIR_NAME)

    fun image(reference: ByteArray?): ByteArray? =
        reference?.toString(Charsets.US_ASCII)?.let { readBoundedTestAsset(File(screensDir, it + RECORDING_SCREEN_EXTENSION)) }

    // A rewritten row carries the screenshots it selected; a recorded one points at the screen readings taken before and after it.
    fun stateImage(seq: Int?): ByteArray? = seq?.let { wanted -> recording.screenStates.firstOrNull { it.seq == wanted }?.screenshotJpeg }
    val before = row.beforeScreenshot?.jpeg ?: stateImage(row.before?.seq)
    val after = row.afterScreenshot?.jpeg ?: stateImage(row.after?.seq)
    val screens = RecordedStepScreens(image(before), image(after), image(row.screenshotJpeg))
    return screens.takeUnless { it.isEmpty }
}
