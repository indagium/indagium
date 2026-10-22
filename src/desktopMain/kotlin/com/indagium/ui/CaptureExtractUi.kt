package com.indagium.ui

import com.indagium.capture.CAPTURE_DESCRIPTOR_NAME
import com.indagium.capture.CaptureExportFormat
import com.indagium.capture.CaptureExtractKind
import com.indagium.capture.CaptureExtractPlan
import com.indagium.capture.CaptureExtractSource
import java.io.File

/**
 * What the snapshot popover's Format section chooses: an archive written by the exporter, or a plain-file extraction out of
 * the archive that is open. UI-only on purpose; [CaptureExportFormat] is part of the exporter request and the AI tool and
 * stays archive formats.
 */
internal sealed interface SnapshotOutput {
    data class Archive(val format: CaptureExportFormat) : SnapshotOutput

    data class Extract(val kind: CaptureExtractKind) : SnapshotOutput
}

/** Shown under Range in Extract mode, where the range controls are disabled. */
internal const val CAPTURE_EXTRACT_RANGE_HINT = "Extract always copies the whole archive."

/** Shown under the Video only option when the opened archive holds none. */
internal const val CAPTURE_EXTRACT_NO_VIDEO_HINT = "This archive has no video"

/** Shown under the All files option: the folder's notes are the archive's, not the recipient's edits in this tab. */
internal const val CAPTURE_EXTRACT_NOTES_HINT =
    "Notes are copied as shipped in the archive, not your edits in this tab. Use Save ZIP to share edited notes."

internal const val CAPTURE_EXTRACT_AV1_NOTE = "AV1 video: plays in VLC and browsers; QuickTime may not."

/** Whether the Video only option can be chosen. */
internal fun captureExtractVideoAvailable(source: CaptureExtractSource?): Boolean = source?.descriptor?.video != null

/** The video's real container extension (`mkv`, `mp4`), as the archive stores it. */
internal fun captureExtractVideoExtension(source: CaptureExtractSource?): String? =
    source?.descriptor?.video?.path?.substringAfterLast('/')?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }

/** `""` for a folder, `.log` for the log, the video's own extension for the video. */
internal fun captureExtractSuffix(kind: CaptureExtractKind, videoExtension: String?): String = when (kind) {
    CaptureExtractKind.ALL_FILES -> ""
    CaptureExtractKind.LOG_ONLY -> ".log"
    CaptureExtractKind.VIDEO_ONLY -> "." + (videoExtension ?: "mkv")
}

internal fun captureExtractNameLabel(kind: CaptureExtractKind): String =
    if (kind == CaptureExtractKind.ALL_FILES) "Folder name" else "File name"

/** The opened archive's name without its extension: a ZIP/`.ind`, or the folder (or the folder holding the descriptor). */
internal fun captureExtractDefaultName(archive: File): String {
    val named = when {
        archive.isDirectory -> archive
        archive.name == CAPTURE_DESCRIPTOR_NAME -> archive.absoluteFile.parentFile ?: archive
        else -> return captureArchiveTitle(archive)
    }
    return named.name.ifBlank { "capture" }
}

/** "14 files · 223.0 MiB (log 12.3 MiB, video 210.0 MiB)", from the files on disk. */
internal fun captureExtractSizeLine(plan: CaptureExtractPlan): String {
    val files = "${plan.fileCount} file${if (plan.fileCount == 1) "" else "s"}"
    val parts = buildList {
        if (plan.logBytes > 0L && plan.kind == CaptureExtractKind.ALL_FILES) add("log ${formatCaptureBytes(plan.logBytes)}")
        if (plan.videoBytes > 0L && plan.kind == CaptureExtractKind.ALL_FILES) add("video ${formatCaptureBytes(plan.videoBytes)}")
    }
    val detail = if (parts.isEmpty()) "" else " (${parts.joinToString(", ")})"
    return "$files · ${formatCaptureBytes(plan.totalBytes)}$detail"
}

/**
 * AV1 note for a copy that includes the video. [compactSource] (a `.ind`) assumes AV1 until the probe of the real video
 * ([probedAv1], null while it runs) says otherwise; any other archive shows it only when the probe finds AV1.
 */
internal fun captureExtractShowsAv1Note(
    kind: CaptureExtractKind,
    hasVideo: Boolean,
    compactSource: Boolean,
    probedAv1: Boolean?,
): Boolean = hasVideo && kind != CaptureExtractKind.LOG_ONLY && (probedAv1 ?: compactSource)

/** `.ind` (and the legacy `.ind.zip`) is Indagium Compact. */
internal fun captureExtractIsCompactArchive(archive: File): Boolean =
    archive.name.endsWith(".ind", ignoreCase = true) || archive.name.endsWith(".ind.zip", ignoreCase = true)

/** Whether the primary Extract button can be pressed. A folder is never replaced or merged, so an existing one blocks it. */
internal fun captureExtractEnabled(
    kind: CaptureExtractKind,
    targetNameValid: Boolean,
    videoAvailable: Boolean,
    destinationExists: Boolean,
    overwriteConfirmed: Boolean,
    busy: Boolean,
): Boolean = !busy && targetNameValid &&
    (kind != CaptureExtractKind.VIDEO_ONLY || videoAvailable) &&
    when (kind) {
        CaptureExtractKind.ALL_FILES -> !destinationExists
        else -> !destinationExists || overwriteConfirmed
    }
