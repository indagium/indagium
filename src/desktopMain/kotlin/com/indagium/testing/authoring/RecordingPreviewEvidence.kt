package com.indagium.testing.authoring

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** A before/after image shown in recording review. Image provenance stays separate from the screenshot-as-oracle choice. */
internal data class RecordingPreviewImage(
    val jpeg: ByteArray,
    val source: String,
    val moment: String,
    val timestampMs: Long?,
    val alignmentConfidence: String,
    val eligibleAsExpectedScreenshot: Boolean = false,
)

/** Image availability and UI-hierarchy availability have separate explanations. */
internal data class RecordingPreviewEvidence(
    val before: RecordingPreviewImage?,
    val after: RecordingPreviewImage?,
    val uiContextMessage: String,
    val imageAvailabilityMessage: String?,
    val rawInput: RecordingPreviewImage? = null,
    val reviewFingerprint: Int? = null,
)

/** The selected after image tied to the exact reviewed row/group that supplied it. */
internal data class RecordingExpectedScreenshotSelection(val image: RecordingPreviewImage, val reviewFingerprint: Int)

/**
 * Shared before/after selection for rewrite evidence and the authoring preview. A timestamped screenshot wins when it is eligible;
 * the same settled-after policy then falls back to an aligned video still. A too-early or uncertain screenshot can still be previewed,
 * but is never marked as an expected screenshot.
 */
internal fun selectRecordingPreviewEvidence(
    input: RewriteInput,
    next: RewriteInput?,
    sensitivity: RewriteSensitivity,
    beforeFrame: RewriteVideoFrame? = null,
    afterFrame: RewriteVideoFrame? = null,
): RecordingPreviewEvidence {
    val sensitiveInput = sensitivity.inputWithheld(input)
    val rawShot = input.screenshot("input")?.takeUnless { sensitivity.screenshotWithheld(it, input.row.inputAtMs) }
    val beforeShot = input.screenshot("before")?.takeUnless { sensitivity.screenshotWithheld(it, input.row.inputStartMs) }
    val settled = settledAfterOf(input, next, sensitivity)
    val afterShot = settled.shot?.takeUnless { sensitivity.screenshotWithheld(it, input.row.inputAtMs) }
    val screenshotDecodeFailed = listOfNotNull(rawShot, beforeShot, afterShot).any { decodeJpeg(it.jpeg) == null }
    val rawInput = if (sensitiveInput) null else rawShot?.let { screenshotPreview(it, "input", eligible = false) }

    val before = if (sensitiveInput) null else selectBeforeRecordingPreview(beforeShot, beforeFrame)
    val after = if (sensitiveInput) null else selectAfterRecordingPreview(afterShot, settled.settled, input.row.inputAtMs, afterFrame)
    val context = when {
        sensitiveInput -> "UI context withheld because this input was in a password-sensitive window."
        input.before?.nodes?.isNotEmpty() == true -> "UI hierarchy was captured for this input."
        else -> "UI hierarchy unavailable for this input."
    }
    val shotNotes = buildList {
        if (listOfNotNull(rawShot, beforeShot, afterShot).any { it.timingUncertain }) add("Screenshot timing overlapped or was uncertain during a gesture.")
        if (listOfNotNull(rawShot, beforeShot, afterShot).isEmpty()) add("No screenshot was captured for one or both moments.")
    }
    val imageMessage = when {
        sensitiveInput -> "Images withheld for this password-sensitive input."
        screenshotDecodeFailed -> "A captured screenshot could not be decoded; a video frame is shown where one was available."
        before != null && after != null -> null
        before == null && after == null -> (shotNotes + "No suitable video frame was available for either moment.").joinToString(" ")
        before == null -> (shotNotes + "Before image unavailable; after evidence is shown.").joinToString(" ")
        else -> (shotNotes + "After image unavailable; before evidence is shown.").joinToString(" ")
    }
    return RecordingPreviewEvidence(before, after, context, imageMessage, rawInput)
}

/** Screenshot-first visual choice used both by rewrite contact sheets and the recording review preview. */
internal fun selectBeforeRecordingPreview(
    screenshot: RecordingScreenshotEvidence?,
    videoFrame: RewriteVideoFrame?,
): RecordingPreviewImage? = screenshot?.takeIf {
    hasEligibleBeforeScreenshot(it)
}?.let { screenshotPreview(it, "before", eligible = false) }
    ?: videoPreview(videoFrame, "before", eligible = false)
    ?: screenshot?.let { screenshotPreview(it, "before", eligible = false) }

/** Settled screenshot first; a video still is the fallback, and an early/uncertain screenshot remains preview-only. */
internal fun selectAfterRecordingPreview(
    screenshot: RecordingScreenshotEvidence?,
    settled: Boolean,
    inputEndedAtMs: Long,
    videoFrame: RewriteVideoFrame?,
): RecordingPreviewImage? {
    val screenshotEligible = screenshot != null && settled && hasEligibleAfterScreenshot(screenshot)
    return if (screenshotEligible) {
        screenshotPreview(screenshot, "after", eligible = true)
            ?: videoPreview(videoFrame, "after", eligible = videoFrame?.frameWallMs?.let { it > inputEndedAtMs } == true)
    } else {
        videoPreview(videoFrame, "after", eligible = videoFrame?.frameWallMs?.let { it > inputEndedAtMs } == true)
            ?: screenshot?.let { screenshotPreview(it, "after", eligible = false) }
    }
}

internal fun hasEligibleBeforeScreenshot(screenshot: RecordingScreenshotEvidence): Boolean =
    !screenshot.timingUncertain && screenshot.acquiredAtMs != null && screenshot.acquisitionFinishedAtMs != null && decodeJpeg(screenshot.jpeg) != null

internal fun hasEligibleAfterScreenshot(screenshot: RecordingScreenshotEvidence): Boolean =
    !screenshot.timingUncertain && screenshot.acquiredAtMs != null && decodeJpeg(screenshot.jpeg) != null

private fun screenshotPreview(shot: RecordingScreenshotEvidence, moment: String, eligible: Boolean): RecordingPreviewImage? =
    decodeJpeg(shot.jpeg)?.let {
        RecordingPreviewImage(
            jpeg = shot.jpeg,
            source = when (shot.source) {
                RecordingScreenshotSource.SCREEN_PROBE -> "screen probe screenshot"
                RecordingScreenshotSource.ADB_INPUT -> "ADB screenshot"
                RecordingScreenshotSource.MIRROR_INPUT -> "mirror input frame"
            },
            moment = moment,
            timestampMs = shot.acquiredAtMs,
            alignmentConfidence = when {
                shot.timingUncertain -> "uncertain alignment"
                shot.acquiredAtMs != null && shot.acquisitionFinishedAtMs != null -> "captured interval aligned to input"
                else -> "timestamp unavailable"
            },
            eligibleAsExpectedScreenshot = eligible,
        )
    }

private fun videoPreview(frame: RewriteVideoFrame?, moment: String, eligible: Boolean): RecordingPreviewImage? {
    frame ?: return null
    val jpeg = encodePreviewJpeg(frame.image) ?: return null
    return RecordingPreviewImage(
        jpeg = jpeg,
        source = "recorded video frame",
        moment = moment,
        timestampMs = frame.frameWallMs,
        alignmentConfidence = "approximate host packet-receipt alignment (${frame.sessionMs} ms into recording)",
        eligibleAsExpectedScreenshot = eligible,
    )
}

private const val PREVIEW_MAX_IMAGE_DIMENSION = 1_280
private const val PREVIEW_MAX_JPEG_BYTES = 2 * 1024 * 1024

private fun encodePreviewJpeg(image: BufferedImage): ByteArray? = runCatching {
    val scale = minOf(1.0, PREVIEW_MAX_IMAGE_DIMENSION.toDouble() / maxOf(image.width, image.height))
    val width = (image.width * scale).toInt().coerceAtLeast(1)
    val height = (image.height * scale).toInt().coerceAtLeast(1)
    val bounded = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = bounded.createGraphics()
    try {
        graphics.drawImage(image, 0, 0, width, height, null)
    } finally {
        graphics.dispose()
    }
    ByteArrayOutputStream().use { output ->
        if (!ImageIO.write(bounded, "jpg", output)) null else output.toByteArray().takeIf { it.size <= PREVIEW_MAX_JPEG_BYTES }
    }
}.getOrNull()
