package com.indagium.ui

import com.indagium.capture.CaptureMarker
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.captureLogEntriesForOrdinals
import com.indagium.capture.markerHeader
import com.indagium.capture.markerHeadingLine
import com.indagium.capture.ordinalRangeForElapsedWindow
import com.indagium.capture.parseMarkerHeader
import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.LogEntry
import com.indagium.model.VideoFrameReference
import com.indagium.utils.downscaleAndEncodeJpeg
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

// The one implementation of a "Mark issue" press: an ordinary Note whose first line is an `indagium:marker` header (see
// capture/CaptureMarkerCodec.kt) covering the log window already on disk, an optional screenshot under it, and, once the
// trailing window has elapsed, a LogRef to the rows of [t - preMs, t + postMs]. The button, the AI/MCP `mark_device_issue` tool
// and an AI test lane that marks a failed step all go through [CaptureMarkerWriter]; they differ only in WHERE the blocks are
// written ([MarkerNotesTarget]: a capture tab's Notes, or the notes a tabless lane holds), what text the note carries and where
// the screenshot comes from.

private const val MARKER_MS_PER_SECOND = 1_000.0

/** Everything the screenshot and trailing-window steps need that [CaptureMarkerWriter.begin] already resolved. */
internal data class MarkerPressContext(
    val noteId: String,
    val ordinal: Int,
    val marker: CaptureMarker,
    val session: CaptureSession,
    val settings: CaptureSettings,
)

/** A screenshot to attach to a marker: the encoded bytes, the file in the capture session when the recorder wrote one, and its video link. */
internal class MarkerShot(
    val bytes: ByteArray,
    val file: File? = null,
    val videoFrame: VideoFrameReference? = null,
    /** The provenance line under the image when it has no [videoFrame]; null: "From <where the notes live>". */
    val provenance: String? = null,
)

/** Where a marker's blocks are written. */
internal interface MarkerNotesTarget {
    /** The capture tab whose Notes these are, or null for the notes a tabless lane holds. */
    val tabId: String?

    /** False once the notes are gone (the tab was closed): the writer stops quietly. */
    fun isOpen(): Boolean

    fun blocks(): List<AnnBlock>

    fun updateAnnotations(transform: (Annotations) -> Annotations)

    /** Adds an image block right after [afterId]; the new block's id, or null when the image could not be encoded. */
    fun addImage(bytes: ByteArray, provenance: String, afterId: String?, videoFrame: VideoFrameReference?): String?

    /** Adds a log reference after [afterId]; null when nothing could be referenced. [sourceEntries], when given, are the rows to show. */
    fun addLogRef(logIds: List<Int>, afterId: String?, sourceEntries: List<LogEntry>?): String?

    /** The rows the viewer holds by their ordinal id (a tab's `rmap`); empty for notes without a viewer. */
    fun heldRows(): Map<Int, LogEntry>

    /** The "From ..." line for an image that has no video link. */
    fun sourceLabel(): String
}

/** The Notes of a capture tab. */
internal class TabMarkerNotes(private val state: AppState, override val tabId: String) : MarkerNotesTarget {
    override fun isOpen(): Boolean = state.tab(tabId) != null

    override fun blocks(): List<AnnBlock> = state.tab(tabId)?.annotations?.blocks.orEmpty()

    override fun updateAnnotations(transform: (Annotations) -> Annotations) {
        state.upAnn(tabId) { it.copy(annotations = transform(it.annotations)) }
    }

    override fun addImage(bytes: ByteArray, provenance: String, afterId: String?, videoFrame: VideoFrameReference?): String? =
        state.addImageBlock(tabId, bytes, provenance, afterId = afterId, videoFrame = videoFrame)

    override fun addLogRef(logIds: List<Int>, afterId: String?, sourceEntries: List<LogEntry>?): String? =
        state.addLogRefBlock(tabId, logIds, caption = "", afterId = afterId, sourceEntries = sourceEntries)

    override fun heldRows(): Map<Int, LogEntry> = state.tab(tabId)?.rmap.orEmpty()

    override fun sourceLabel(): String = "From ${state.tab(tabId)?.filename ?: "capture"}"
}

/**
 * The notes a lane without a tab holds in memory. Safe to use from several coroutines; the lane passes [snapshot] to the capture
 * export, which is how a tabless lane's archive carries the same markers a tab's would.
 */
internal class LaneNotes(initial: Annotations = Annotations()) : MarkerNotesTarget {
    private val notes = AtomicReference(initial)

    override val tabId: String? = null

    fun snapshot(): Annotations = notes.get()

    override fun isOpen(): Boolean = true

    override fun blocks(): List<AnnBlock> = notes.get().blocks

    override fun updateAnnotations(transform: (Annotations) -> Annotations) {
        while (true) {
            val current = notes.get()
            if (notes.compareAndSet(current, transform(current))) return
        }
    }

    override fun addImage(bytes: ByteArray, provenance: String, afterId: String?, videoFrame: VideoFrameReference?): String? {
        val encoded = downscaleAndEncodeJpeg(bytes) ?: return null
        val id = "i${System.nanoTime()}"
        val block = AnnBlock.Image(id = id, caption = "", provenance = provenance, format = "jpeg", bytes = encoded, videoFrame = videoFrame)
        updateAnnotations { insertAfter(it, block, afterId) }
        return id
    }

    override fun addLogRef(logIds: List<Int>, afterId: String?, sourceEntries: List<LogEntry>?): String? {
        if (logIds.isEmpty() && sourceEntries.isNullOrEmpty()) return null
        val id = "r${System.nanoTime()}"
        val block = AnnBlock.LogRef(id = id, logIds = logIds.distinct().sorted(), caption = "", sourceEntries = sourceEntries)
        updateAnnotations { insertAfter(it, block, afterId) }
        return id
    }

    override fun heldRows(): Map<Int, LogEntry> = emptyMap()

    override fun sourceLabel(): String = "From the lane's capture"

    private fun insertAfter(annotations: Annotations, block: AnnBlock, afterId: String?): Annotations {
        val blocks = annotations.blocks.toMutableList()
        val at = afterId?.let { anchor -> blocks.indexOfFirst { it.id == anchor }.takeIf { it >= 0 }?.plus(1) } ?: blocks.size
        blocks.add(at, block)
        return annotations.copy(blocks = blocks)
    }
}

/** What a tab-bound press registers for its Undo affordance; a lane's marker has none. */
internal interface MarkerUndoSink {
    fun noteAdded(noteId: String)

    fun imageAdded(noteId: String, imageBlockId: String, screenshotFile: File?)

    fun logRefAdded(noteId: String, logRefId: String)
}

/** How a press reports a problem or a shortfall (the capture strip's one status line, or nothing for a lane). */
internal fun interface MarkerStatusSink {
    fun report(message: String)
}

internal class CaptureMarkerWriter(
    private val target: MarkerNotesTarget,
    /** The live recorder boundary, or a failure when the capture has no session (any more). */
    private val boundary: () -> com.indagium.capture.CaptureSessionBoundary,
    /** The live boundary again after the trailing wait, or null when the capture has stopped meanwhile. */
    private val liveBoundary: () -> com.indagium.capture.CaptureSessionBoundary?,
    /** The stopped session with this id (its files are final), or null when it is not known any more. */
    private val stoppedSession: (String) -> CaptureSession?,
    private val status: MarkerStatusSink = MarkerStatusSink { },
    private val undo: MarkerUndoSink? = null,
) {
    /**
     * Step 1: resolve the press-time boundary, compute the leading (already-on-disk) half of the window and commit the Note.
     * [noteText] goes under the heading. Null (after reporting) when the boundary cannot be read.
     */
    fun begin(label: String, noteText: String?): MarkerPressContext? {
        val boundary = runCatching(boundary).getOrElse {
            status.report("Mark issue failed: ${it.message ?: it::class.simpleName}")
            return null
        }
        val session = boundary.session
        val settings = session.settings
        // Highest existing number + 1, not the count: after a marker in the middle is deleted the count would hand out a number
        // (id "mN", heading "Marker N") that is still in use.
        val ordinal = target.blocks().filterIsInstance<AnnBlock.Note>()
            .mapNotNull { parseMarkerHeader(it.text)?.id?.removePrefix("m")?.toIntOrNull() }
            .maxOrNull().let { (it ?: 0) + 1 }
        val videoStart = session.videoStartElapsedMs
        val videoMs = videoStart?.let { start -> (boundary.elapsedMs - start + session.manualOffsetMs).takeIf { it >= 0L } }
        // The leading half [t-preMs, t] is already flushed (boundary.indexLength is exactly the bound this scan must not read past),
        // so it is resolved synchronously here rather than after the trailing wait.
        val leadingRange = ordinalRangeForElapsedWindow(
            session.indexFile,
            boundary.indexLength,
            (boundary.elapsedMs - settings.markerPreMs).coerceAtLeast(0L),
            boundary.elapsedMs,
        )
        val marker = CaptureMarker(
            id = "m$ordinal",
            elapsedMs = boundary.elapsedMs,
            firstOrdinal = leadingRange?.first,
            lastOrdinal = leadingRange?.last,
            videoMs = videoMs,
            label = label,
            preMs = settings.markerPreMs,
            postMs = settings.markerPostMs,
            screenshotPath = if (settings.markerScreenshot) "screenshots/marker-$ordinal.png" else null,
        )
        val noteId = "n${System.nanoTime()}"
        target.updateAnnotations { annotations ->
            val text = markerHeader(marker) + "\n" + markerHeadingLine(ordinal, marker.label) + "\n" + (noteText?.let { "$it\n" } ?: "")
            annotations.copy(blocks = annotations.blocks + AnnBlock.Note(noteId, text))
        }
        undo?.noteAdded(noteId)
        return MarkerPressContext(noteId = noteId, ordinal = ordinal, marker = marker, session = session, settings = settings)
    }

    /**
     * Step 2: attaches the screenshot [shot] produced (null: none could be taken, which is not a failed press: the Note and its log
     * window are worth keeping either way). Returns the block id the trailing LogRef should insert after: the screenshot's if one was
     * added, otherwise the Note's own id.
     */
    suspend fun attachScreenshot(context: MarkerPressContext, shot: suspend () -> MarkerShot?): String {
        val taken = shot() ?: return context.noteId
        if (!markerNoteExists(context.noteId)) {
            // Deleted while the screenshot was being taken: don't attach it to whatever is now last.
            taken.file?.let { runCatching { it.delete() } }
            return context.noteId
        }
        val provenance = taken.videoFrame?.provenanceLabel ?: taken.provenance ?: target.sourceLabel()
        val blockId = target.addImage(taken.bytes, provenance, context.noteId, taken.videoFrame) ?: return context.noteId
        // The header's path so far is only a hint; record the file the recorder actually wrote, so deleting the marker can remove it
        // and later snapshots stop shipping it. A screenshot that was not written into the session has no path to record.
        updateMarkerHeader(context.noteId) { it.copy(screenshotPath = taken.file?.let { file -> "screenshots/${file.name}" }) }
        undo?.imageAdded(context.noteId, blockId, taken.file)
        return blockId
    }

    /**
     * Step 3: waits out `markerPostMs`, rescans for the full [t-preMs, t+postMs] window against whatever is flushed by then, and
     * appends the LogRef under [afterId]. If the capture stopped partway through the wait, the window is clipped to what was actually
     * recorded and the status line reports the shortfall.
     */
    suspend fun finishWindow(context: MarkerPressContext, afterId: String) {
        delay(context.settings.markerPostMs)
        val live = liveBoundary()
        val indexFile: File
        val logFile: File
        val indexBytes: Long
        val actualEndMs: Long
        if (live != null) {
            indexFile = live.session.indexFile
            logFile = live.session.logFile
            indexBytes = live.indexLength
            actualEndMs = minOf(context.marker.elapsedMs + context.settings.markerPostMs, live.elapsedMs)
        } else {
            // The controller is gone (Stop won the race): the session's own files are already fully flushed by the finalization.
            val stopped = stoppedSession(context.session.id) ?: context.session
            indexFile = stopped.indexFile
            logFile = stopped.logFile
            indexBytes = stopped.indexFile.length()
            actualEndMs = minOf(context.marker.elapsedMs + context.settings.markerPostMs, stopped.elapsedMs)
        }
        val requestedEndMs = context.marker.elapsedMs + context.settings.markerPostMs
        if (actualEndMs < requestedEndMs) {
            val collectedSeconds = (actualEndMs - context.marker.elapsedMs).coerceAtLeast(0L) / MARKER_MS_PER_SECOND
            val requestedSeconds = context.settings.markerPostMs / MARKER_MS_PER_SECOND
            status.report("+%.1f s of %.0f s — capture ended".format(Locale.US, collectedSeconds, requestedSeconds))
        }
        val fullRange = ordinalRangeForElapsedWindow(
            indexFile,
            indexBytes,
            (context.marker.elapsedMs - context.settings.markerPreMs).coerceAtLeast(0L),
            actualEndMs,
        ) ?: return
        if (!target.isOpen()) return
        // Deleted during the trailing wait: its LogRef would otherwise be appended at the end.
        if (!markerNoteExists(context.noteId)) return
        val held = target.heldRows()
        val missing = fullRange.filterNot { it in held }.toSet()
        val sourceEntries = if (missing.isEmpty()) {
            null
        } else {
            // Tail lag (or no viewer at all): the notes hold no row for some of the window. Read those straight from the capture log by
            // byte offset and merge with what is held, so the LogRef shows the whole window regardless of tailing progress.
            (fullRange.mapNotNull { held[it] } + captureLogEntriesForOrdinals(indexFile, indexBytes, logFile, missing)).sortedBy { it.id }
        }
        val logRefId = target.addLogRef(fullRange.toList(), afterId, sourceEntries)
        if (logRefId != null) undo?.logRefAdded(context.noteId, logRefId)
        // Re-stamp the header with the window that was ACTUALLY collected: it is the durable record the archive import rebuilds from.
        updateMarkerHeader(context.noteId) { it.copy(firstOrdinal = fullRange.first, lastOrdinal = fullRange.last) }
    }

    /** Rewrites one marker Note's header line from its CURRENT header, so separate updates never overwrite each other with a stale copy. */
    private fun updateMarkerHeader(noteId: String, transform: (CaptureMarker) -> CaptureMarker) {
        target.updateAnnotations { annotations ->
            val blocks = annotations.blocks.map { block ->
                if (block !is AnnBlock.Note || block.id != noteId) return@map block
                val current = parseMarkerHeader(block.text) ?: return@map block
                val updated = transform(current)
                if (updated == current) return@map block
                val body = block.text.substringAfter("\n", missingDelimiterValue = "")
                block.copy(text = markerHeader(updated) + "\n" + body)
            }
            annotations.copy(blocks = blocks)
        }
    }

    private fun markerNoteExists(noteId: String): Boolean = target.blocks().any { it.id == noteId }
}
