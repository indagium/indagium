package com.indagium.testing.authoring

import java.util.Locale

// The rewrite's visual evidence uses eligible timestamped screenshots first, then recorded-video frames for missing or uncertain
// moments. Per input it seeks a frame just before the input starts and a settled look after it, avoiding a transition screen as the
// expected result. Contact sheets cover each input within the image budget, and captions/provenance describe the images actually used.

/** The "before" frame is the one held this long before the input starts. */
internal const val REWRITE_VIDEO_BEFORE_MS = 100L

private const val MILLIS_PER_SECOND = 1_000.0

/** One sheet and how many of its inputs have a settled look at their result. */
private class VideoSheet(val image: RewriteImage, val shownInputs: List<Int>, val settledInputs: Int, val videoSettledInputs: List<Int>)

/**
 * The evidence for [considered] from [video], or null when [video] is null or shows nothing. [tally] receives the number of inputs
 * with a settled "after" frame or screenshot.
 */
internal fun videoEvidence(
    considered: List<ShownInput>,
    video: RewriteVideoSheets?,
    screenText: Map<Int, String>,
    withheld: Map<Int, String>,
    cautions: Map<Int, String>,
    tally: EvidenceTally,
): RewriteEvidence? {
    if (video == null) return null
    val pairs = (considered.size + REWRITE_SHEET_PAIR_INPUTS - 1) / REWRITE_SHEET_PAIR_INPUTS <= REWRITE_MAX_ATTACHED_IMAGES
    val perSheet = if (pairs) REWRITE_SHEET_PAIR_INPUTS else REWRITE_SHEET_SINGLE_INPUTS
    val images = ArrayList<RewriteImage>()
    val refs = LinkedHashMap<Int, String>()
    val notAttached = ArrayList<Int>()
    var bytes = 0L
    var settled = 0
    val videoSettled = HashSet<Int>()
    considered.chunked(perSheet).forEachIndexed { index, group ->
        val sheet = if (index >= REWRITE_MAX_ATTACHED_IMAGES) null else sheetOf(group, pairs, video)
        when {
            index >= REWRITE_MAX_ATTACHED_IMAGES -> notAttached += group.map { it.input.number }
            sheet == null -> Unit // Nothing of these inputs can be shown at all.
            bytes + sheet.image.bytes.size > REWRITE_MAX_ATTACHED_IMAGE_BYTES -> notAttached += sheet.shownInputs
            else -> {
                bytes += sheet.image.bytes.size
                images += sheet.image
                settled += sheet.settledInputs
                videoSettled += sheet.videoSettledInputs
                sheet.shownInputs.forEach { refs[it] = "Image ${images.size} (contact sheet)" }
            }
        }
    }
    if (images.none { it.videoFrames > 0 }) return null
    tally.settledAfter = settled
    // A caution is about a screenshot taken too soon; an input whose settled look came from the video needs none.
    val remaining = cautions.filterKeys { it !in videoSettled }
    return RewriteEvidence(RewriteEvidenceKind.SHEETS, images, notAttached, refs, screenText, withheld, remaining, tally.considered, settled)
}

/** The cells of one sheet and where they came from. */
private class SheetCells {
    val cells = ArrayList<ContactSheetCell>()
    val shown = ArrayList<Int>()
    var videoCells = 0
    var screenCells = 0
    var settled = 0
    val videoSettled = ArrayList<Int>()

    fun add(number: Int, itemCells: List<Pair<ContactSheetCell, Boolean>>, settledAfter: Boolean, settledFromVideo: Boolean) {
        if (itemCells.isEmpty()) return
        itemCells.forEach { (cell, fromVideo) ->
            cells += cell
            if (fromVideo) videoCells++ else screenCells++
        }
        shown += number
        if (settledAfter) settled++
        if (settledFromVideo) videoSettled += number
    }
}

/** The sheet of one run of inputs: their video frames (screenshots where the video has none) in one picture; null when there is nothing. */
private fun sheetOf(group: List<ShownInput>, pairs: Boolean, video: RewriteVideoSheets): VideoSheet? {
    val wishes = group.flatMap { item ->
        listOfNotNull((item.input.row.inputStartMs - REWRITE_VIDEO_BEFORE_MS).takeIf { pairs }, settledAtMs(item.input, item.nextStartMs))
    }
    val frames = video.frames(wishes)
    var cursor = 0
    val sheet = SheetCells()
    val videoMoments = LinkedHashMap<Int, Set<String>>()
    val visualMoments = LinkedHashMap<Int, Set<String>>()
    for (item in group) {
        val beforeFrame = if (pairs) frames.getOrNull(cursor++) else null
        val afterFrame = frames.getOrNull(cursor++)
        val before = if (pairs) beforeCell(item, beforeFrame) else null
        val after = afterCell(item, afterFrame, pairs)
        videoMoments[item.input.number] = buildSet {
            if (before?.second == true) add("before")
            if (after?.fromVideo == true) add("after")
        }
        val itemCells = listOfNotNull(before, after?.let { it.cell to it.fromVideo })
        visualMoments[item.input.number] = itemCells.map { it.first.relation }.toSet()
        sheet.add(item.input.number, itemCells, after?.settled == true, after?.settled == true && after.fromVideo)
    }
    if (sheet.cells.isEmpty()) return null
    val what = if (pairs) "for each input the screen just before it, then its settled screen after it" else "the settled screen after each input"
    val label = "contact sheet of input(s) ${rewriteNumberRanges(sheet.shown)} from the recorded video: $what, left to right then top to " +
        "bottom, captioned with the input number and the video time (a caption saying screenshot is a stored screenshot where the video " +
        "had no frame)"
    val image = encodeSheet(sheet.cells)?.let {
        RewriteImage(
            label, sheet.shown, it, JPEG_MIME, videoFrames = sheet.videoCells, storedScreens = sheet.screenCells,
            videoMoments = videoMoments, visualMoments = visualMoments,
        )
    }
    return image?.let { VideoSheet(it, sheet.shown, sheet.settled, sheet.videoSettled) }
}

private fun beforeCell(item: ShownInput, frame: RewriteVideoFrame?): Pair<ContactSheetCell, Boolean>? {
    val number = item.input.number
    val selected = selectBeforeRecordingPreview(item.before, frame) ?: return null
    val image = decodeJpeg(selected.jpeg) ?: return null
    val fromVideo = selected.source == "recorded video frame"
    val caption = if (fromVideo) "input $number before • ${seconds(frame?.sessionMs ?: 0L)}" else "input $number before (screenshot)"
    return ContactSheetCell(image, "before", caption) to fromVideo
}

private class AfterCell(val cell: ContactSheetCell, val fromVideo: Boolean, val settled: Boolean)

@Suppress("ReturnCount") // Each early return is a distinct unavailable/decode/fallback branch for one evidence cell.
private fun afterCell(item: ShownInput, frame: RewriteVideoFrame?, pairs: Boolean): AfterCell? {
    val number = item.input.number
    val selected = selectAfterRecordingPreview(item.after, item.afterSettled, item.input.row.inputAtMs, frame)
    if (selected?.source == "recorded video frame") {
        val selectedFrame = frame ?: return null
        val unchanged = selectedFrame.frameWallMs <= item.input.row.inputAtMs
        val what = if (unchanged) "after (unchanged since ${seconds(selectedFrame.sessionMs)})" else "after (settled) • ${seconds(selectedFrame.sessionMs)}"
        val image = decodeJpeg(selected.jpeg) ?: return null
        return AfterCell(ContactSheetCell(image, "after", "input $number $what"), fromVideo = true, settled = true)
    }
    val image = selected?.jpeg?.let(::decodeJpeg)
    if (image != null) {
        val caption = "input $number after (screenshot${if (item.afterSettled) "" else ", early"})"
        return AfterCell(ContactSheetCell(image, "after", caption), fromVideo = false, settled = item.afterSettled)
    }
    // A sheet of one screen per input shows the screen before it when nothing of the result was kept.
    if (pairs) return null
    val before = selectBeforeRecordingPreview(item.before, null)?.jpeg?.let(::decodeJpeg) ?: return null
    return AfterCell(ContactSheetCell(before, "before", "input $number before (screenshot)"), fromVideo = false, settled = false)
}

private fun seconds(sessionMs: Long): String = String.format(Locale.ROOT, "%.1fs", sessionMs / MILLIS_PER_SECOND)
