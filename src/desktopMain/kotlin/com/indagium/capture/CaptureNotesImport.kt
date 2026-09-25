package com.indagium.capture

import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.LogEntry
import com.indagium.model.VideoSource

// Phase 4 (snapshot archive + import) — the import-side counterpart to CaptureArchive.kt's
// stageNotes/clipMarkersForExport. Both functions here are pure (Annotations/AnnBlock/LogEntry are
// plain `model` types, no UI/AppState dependency), which is what makes them safely callable from
// AppState.openCaptureFile's ioScope coroutine without pulling any capture-package logic into `ui`.

/** How AppState.resolveCaptureNotesImport applies a pending re-import decision — see
 *  AppState.pendingCaptureNotesImport's own doc for when this dialog appears at all (only when
 *  reopening/redropping an archive whose tab is already open AND already has its own notes;
 *  dropping a zip always opens a brand-new tab, which never has existing notes to protect). */
enum class CaptureNotesImportAction { APPEND, REPLACE, SKIP }

/** Merges already-re-anchored [incoming] notes (see [reanchorImportedCaptureNotes]) into [existing]
 *  per the user's choice. */
fun mergeCaptureNotesImport(existing: Annotations, incoming: Annotations, action: CaptureNotesImportAction): Annotations =
    when (action) {
        CaptureNotesImportAction.SKIP -> existing
        CaptureNotesImportAction.REPLACE -> incoming
        CaptureNotesImportAction.APPEND -> existing.copy(blocks = existing.blocks + incoming.blocks)
    }

/**
 * Rebuilds every marker's [AnnBlock.LogRef] against THIS archive's own freshly parsed [logData],
 * instead of the ids [notes] was originally saved with. Those original ids are [LogEntry.id]s from
 * the LIVE capture tab that wrote them; [com.indagium.utils.LogParser] restarts ids at 1 per file,
 * so they only happen to still be correct when [logData] is the exact same, complete log (an
 * unfiltered/ALL export) — anything else (a time-windowed or selection snapshot) silently points at
 * the wrong rows, or rows that don't exist at all, without this step.
 *
 * The translation comes from [markers] (export-local ordinal bounds, 1:1 with [logData] by
 * construction — see [CaptureArchiveMarker]'s own doc), not by re-reading each marker header's
 * `rows` directly: a marker whose window was only partially covered by the export was already
 * clipped there, and a marker entirely outside the export range has no entry in [markers] at all.
 * That absence is handled here as "keep the note and its screenshot, drop only the LogRef" — never
 * a silent full drop of the marker.
 *
 * A LogRef doesn't itself record which marker "owns" it; [markers] is instead matched to a LogRef by
 * an EXACT match against that marker's original header window — [com.indagium.ui.AppState.markIssue]
 * always creates a marker's LogRef with `logIds == (firstOrdinal..lastOrdinal).toList()` exactly
 * (see `finishMarkerWindow` -> `addLogRefBlock`), so this is reliable as long as two markers don't
 * happen to share an identical window (an accepted, extremely unlikely edge case — each candidate
 * LogRef is claimed at most once, in block order, so it can't double-match).
 */
fun reanchorImportedCaptureNotes(notes: Annotations, markers: List<CaptureArchiveMarker>, logData: List<LogEntry>): Annotations {
    val markersByNoteId = notes.blocks.filterIsInstance<AnnBlock.Note>()
        .mapNotNull { note -> parseMarkerHeader(note.text)?.let { note.id to it } }
        .toMap()
    if (markersByNoteId.isEmpty()) return notes

    val clipByNoteId = markers.associateBy { it.noteBlockId }
    val validIds = logData.mapTo(HashSet()) { it.id }
    val claimed = HashSet<String>()

    // Which marker (by its Note's own id) [ref] belongs to, or null when it isn't a marker's LogRef
    // at all (a plain, user-added log reference — left untouched, see this function's own doc).
    fun ownerNoteIdOf(ref: AnnBlock.LogRef): String? {
        val owner = markersByNoteId.entries.firstOrNull { (noteId, marker) ->
            noteId !in claimed && marker.firstOrdinal != null && marker.lastOrdinal != null &&
                ref.logIds == (marker.firstOrdinal..marker.lastOrdinal).toList()
        }
        if (owner != null) claimed += owner.key
        return owner?.key
    }

    fun reanchoredNote(block: AnnBlock.Note): AnnBlock.Note {
        val marker = markersByNoteId.getValue(block.id)
        val clip = clipByNoteId[block.id]
        val rewritten = marker.copy(firstOrdinal = clip?.firstOrdinal, lastOrdinal = clip?.lastOrdinal)
        val body = block.text.substringAfter("\n", missingDelimiterValue = "")
        return block.copy(text = markerHeader(rewritten) + "\n" + body)
    }

    // Null return means "drop this block" (mapNotNull below) — either it belonged to a marker whose
    // window has no export-local clip (out of range) or clipping left no valid rows to point at.
    fun reanchoredLogRef(block: AnnBlock.LogRef): AnnBlock.LogRef? {
        val ownerNoteId = ownerNoteIdOf(block)
        if (ownerNoteId == null) return block
        val clip = clipByNoteId[ownerNoteId] ?: return null
        val ids = (clip.firstOrdinal..clip.lastOrdinal).filter { it in validIds }
        return if (ids.isEmpty()) null else block.copy(logIds = ids, sourceEntries = null)
    }

    val blocks = notes.blocks.mapNotNull { block ->
        when {
            block is AnnBlock.Note && block.id in markersByNoteId -> reanchoredNote(block)
            block is AnnBlock.LogRef -> reanchoredLogRef(block)
            else -> block
        }
    }
    return notes.copy(blocks = blocks)
}

/**
 * Import-side counterpart to CaptureArchive.kt's `rewriteExportedVideoFrames` (see its own doc):
 * re-points every [AnnBlock.Image] whose [com.indagium.model.VideoFrameReference.source] is that
 * function's portable export-time marker — `VideoSource.ArchiveEntry("", entryPath, displayName)`,
 * empty `archivePath` meaning "this same archive" — at [actualSource], the durable identity the
 * REOPENED tab's own attached video actually resolves to (an `ArchiveEntry(zipPath, …)` for a
 * `.zip`, a `LocalFile` for an extracted directory). Without this, [AppState.navigateToVideoFrame]'s
 * exact-source-identity check rejects every marker screenshot's seek link after Save + reopen — the
 * durable identity a fresh import builds is never byte-for-byte the portable placeholder a past
 * export shipped.
 *
 * [actualSource] is null when this import attached no video at all (the export had no video, or
 * this open path chose not to attach one) — every portable frame is then detached (`videoFrame =
 * null`, kept as a plain image) instead of left pointing at a source that will never resolve.
 * Called from [com.indagium.ui.AppState.openCaptureFile] (a brand-new tab) and
 * [com.indagium.ui.AppState.offerCaptureNotesReimportIfNeeded] (an already-open tab), both AFTER
 * [reanchorImportedCaptureNotes] — the two rewrites touch disjoint fields (LogRef/marker-header
 * ordinals vs. Image video frames) so their order doesn't matter, but reanchoring first keeps every
 * caller's pipeline in one consistent order.
 */
fun repointPortableVideoFrames(notes: Annotations, actualSource: VideoSource?): Annotations {
    var changed = false
    val rewritten = notes.blocks.map { block ->
        if (block !is AnnBlock.Image) return@map block
        val frame = block.videoFrame ?: return@map block
        val portable = frame.source as? VideoSource.ArchiveEntry ?: return@map block
        if (portable.archivePath.isNotEmpty()) return@map block
        changed = true
        if (actualSource == null) block.copy(videoFrame = null) else block.copy(videoFrame = frame.copy(source = actualSource))
    }
    return if (changed) notes.copy(blocks = rewritten) else notes
}
