package com.openlog.utils

/**
 * Filename for the Nth exported annotation image, 1-based — the SAME name buildMd() embeds in a
 * `!frame-0N.jpg!` Jira wiki anchor (see Filter.kt's `imageOrdinal` counter) and
 * AppState.exportAnnotationFrames() writes the image bytes to. Both call sites must go through
 * this one function so an exported file and the markup referencing it can never drift apart.
 *
 * [imageOrdinal] is the 1-based count of [com.openlog.model.AnnBlock.Image] blocks in document
 * order — independent of buildMd's cross-block `blockNumber`, which also counts Note/LogRef blocks
 * and is gated on `settings.numberAnnotationBlocks`. Zero-padded to width 2 ("frame-01" ..
 * "frame-99"). Images are always JPEG (AnnotationManager.addImageBlock is the sole producer of
 * AnnBlock.Image, always with `format == "jpeg"`), so [format] "jpeg" maps to the conventional
 * ".jpg" file extension; any other value passes through unchanged rather than guessing.
 */
fun annotationImageFileName(imageOrdinal: Int, format: String): String {
    val ext = if (format.equals("jpeg", ignoreCase = true)) "jpg" else format
    return "frame-%02d.%s".format(imageOrdinal, ext)
}
