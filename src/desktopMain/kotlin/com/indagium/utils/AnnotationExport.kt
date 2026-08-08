package com.indagium.utils

/**
 * Filename for the Nth exported annotation image, 1-based — the SAME name buildMd() embeds in a
 * `!frame-0N.jpg!` Jira wiki anchor (see Filter.kt's `imageOrdinal` counter), and both
 * AppState.writeAnnotationFrameImages() and AppState.exportAnnotationFrames() write the image
 * bytes to. All three call sites must go through this one function so an exported file and the
 * markup referencing it can never drift apart.
 *
 * [imageOrdinal] is the 1-based count of [com.indagium.model.AnnBlock.Image] blocks in document
 * order — independent of buildMd's cross-block `blockNumber`, which also counts Note/LogRef blocks
 * and is gated on `settings.numberAnnotationBlocks`. Zero-padded to width 2 ("frame-01" ..
 * "frame-99"). Images are always JPEG (AnnotationManager.addImageBlock is the sole producer of
 * AnnBlock.Image, always with `format == "jpeg"`), so [format] "jpeg" maps to the conventional
 * ".jpg" file extension; any other value passes through unchanged rather than guessing.
 *
 * [frameStamp] is [com.indagium.model.Annotations.frameStamp] — a "yyyyMMdd-HHmmss" moment captured
 * once per analysis (the first time it ever gains an image block) and persisted from then on, so
 * every subsequent export names frames "frame-<stamp>-0N.jpg" regardless of how many times the
 * analysis is re-exported. This is what keeps two different people's analyses of two different
 * logs from both producing an identical "frame-01.jpg" that collides as a Jira attachment. Null
 * (an analysis that never had an image, or a note saved before this field existed) reproduces the
 * exact legacy name "frame-01.jpg" unchanged, so pre-existing analyses keep their current names.
 */
fun annotationImageFileName(imageOrdinal: Int, format: String, frameStamp: String? = null): String {
    val ext = if (format.equals("jpeg", ignoreCase = true)) "jpg" else format
    val pattern = if (frameStamp != null) "frame-$frameStamp-%02d.%s" else "frame-%02d.%s"
    return pattern.format(imageOrdinal, ext)
}

/**
 * Filename for the Nth exported sequence diagram, 1-based — the exact counterpart of
 * [annotationImageFileName], and held to the same contract: this one function is the only place the
 * name is formed, so buildMd()'s `!diagram-0N.png!` Jira anchor and the bytes
 * AppState.writeAnnotationFrameImages() writes beside the .md can never drift apart.
 *
 * [diagramOrdinal] counts diagram notes in document order, INDEPENDENTLY of
 * [annotationImageFileName]'s image ordinal — a document with two screenshots and two diagrams
 * produces frame-01/frame-02 and diagram-01/diagram-02, not a shared 1..4 sequence. They share
 * [frameStamp] (see its doc there) so one analysis's whole set of attachments carries one moment,
 * and two people's exports can't collide as Jira attachments.
 *
 * Always PNG: a sequence diagram is line art, and the JPEG the image path uses would fringe every
 * arrow and label. This is also why diagrams do NOT go through AnnotationManager.addImageBlock —
 * see utils/ImageDownscale.kt's 1280px/400KB JPEG cap.
 */
fun annotationDiagramFileName(diagramOrdinal: Int, frameStamp: String? = null): String {
    val pattern = if (frameStamp != null) "diagram-$frameStamp-%02d.png" else "diagram-%02d.png"
    return pattern.format(diagramOrdinal)
}
