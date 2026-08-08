package com.indagium.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.indagium.diagram.DiagramTheme
import com.indagium.diagram.SeqDiagram
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.buildSequenceDiagram
import com.indagium.diagram.encodeDiagramNote
import com.indagium.diagram.parseDiagramNote
import com.indagium.diagram.toSource
import com.indagium.model.LogEntry
import com.indagium.model.LogTab
import com.indagium.utils.CancellationCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.coroutineContext

/** An open request to build a diagram — what the dialog is currently editing. [editingBlockId] is
 *  set when the dialog was opened via "Edit diagram…" on an existing note, so confirming replaces
 *  that block's text instead of appending a new one. */
data class SeqDiagramRequest(
    val tabId: String,
    val spec: SeqDiagramSpec,
    val editingBlockId: String? = null,
)

/**
 * The dialog's preview state.
 *
 * Modeled as a sealed hierarchy rather than "nullable result + isLoading boolean" for the reason
 * [com.indagium.model.MessageCompositionState] spells out: the two-field encoding makes
 * "computing for the first time" and "recomputing while showing the previous result"
 * indistinguishable, and every consumer then has to re-derive which it is.
 */
sealed class DiagramPreviewState {
    data object NotComputed : DiagramPreviewState()

    /** [previous] keeps the last good render on screen while a new one computes, so tweaking an
     *  option doesn't flash the preview area empty on every keystroke. */
    data class Computing(val previous: SeqDiagram?) : DiagramPreviewState()

    data class Computed(val diagram: SeqDiagram) : DiagramPreviewState()

    data class Failed(val message: String) : DiagramPreviewState()

    val diagramOrNull: SeqDiagram?
        get() = when (this) {
            is Computed -> diagram
            is Computing -> previous
            else -> null
        }
}

/**
 * Owns everything behind the "build a sequence diagram" dialog: the open request, the debounced
 * background build that feeds its live preview, and writing the result into the notes.
 *
 * Split out of [AppState] the same way [AnnotationManager] is — AppState is already ~6k lines, and
 * this is a self-contained feature with its own job lifecycle.
 */
class SeqDiagramCoordinator(private val appState: AppState) {

    /** Non-null exactly while the dialog is open. */
    var request by mutableStateOf<SeqDiagramRequest?>(null)

    var preview by mutableStateOf<DiagramPreviewState>(DiagramPreviewState.NotComputed)
        private set

    // Its own scope rather than reaching into AppState's private ioScope: this keeps AppState's
    // surface unchanged, and lets a preview job be cancelled without touching file loading.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var previewJob: Job? = null

    // ── Opening ──────────────────────────────────────────────────────────────────────────────

    /**
     * Opens the dialog for [tabId]. [seedIds] is the current row selection, if any: a selection is
     * by far the most common way a user says "this part of the log", so it becomes the default
     * range (min..max, matching how AppState.collapseRange treats an inclusive id span).
     */
    fun begin(tabId: String, seedIds: Set<Int> = emptySet()) {
        val tab = appState.tab(tabId) ?: return
        val range = if (seedIds.size >= 2) {
            com.indagium.diagram.DiagramRange.Ids(seedIds.min(), seedIds.max())
        } else {
            com.indagium.diagram.DiagramRange.VisibleView
        }
        // Reuse this tab's last spec (participants, options, dialect) so a second diagram from the
        // same log doesn't start from scratch — only the range is re-seeded from the new selection.
        val base = lastSpecByTab[tabId] ?: SeqDiagramSpec()
        val spec = base.copy(range = range, sourceFile = File(tab.filename).name)
        request = SeqDiagramRequest(tabId, spec)
        requestPreview(tabId, spec)
    }

    /** Opens the dialog on an existing diagram note, repopulated from the spec its header carries.
     *  Returns false when [blockId] isn't a diagram note (so the caller can leave it as text). */
    fun beginEdit(tabId: String, blockId: String): Boolean {
        val tab = appState.tab(tabId) ?: return false
        val block = tab.annotations.blocks.firstOrNull { it.id == blockId } as? com.indagium.model.AnnBlock.Note ?: return false
        val parsed = parseDiagramNote(block.text) ?: return false
        request = SeqDiagramRequest(tabId, parsed.spec, editingBlockId = blockId)
        // Seed the preview with the note's OWN carried model rather than immediately rebuilding:
        // the note may have been opened without its log (Case Library "notes only"), where a
        // rebuild would produce nothing. Regenerating is an explicit action from here.
        preview = parsed.model?.let { DiagramPreviewState.Computed(it) } ?: DiagramPreviewState.NotComputed
        return true
    }

    fun cancel() {
        previewJob?.cancel()
        previewJob = null
        request = null
        preview = DiagramPreviewState.NotComputed
    }

    fun updateSpec(spec: SeqDiagramSpec) {
        val current = request ?: return
        request = current.copy(spec = spec)
        requestPreview(current.tabId, spec)
    }

    // ── Preview ──────────────────────────────────────────────────────────────────────────────

    private val lastSpecByTab = mutableMapOf<String, SeqDiagramSpec>()

    /**
     * Rebuilds the preview off the composition thread, cancelling any in-flight build first.
     *
     * Follows [AppState.requestMessageComposition]'s shape deliberately: a full-file scan behind an
     * interactive control must be cancellable and must supersede rather than queue, or dragging a
     * slider on a 10M-line tab enqueues dozens of multi-second scans that all still have to run.
     */
    fun requestPreview(tabId: String, spec: SeqDiagramSpec) {
        previewJob?.cancel()
        preview = DiagramPreviewState.Computing(preview.diagramOrNull)
        previewJob = scope.launch {
            // Debounce: the dialog calls this on every keystroke/toggle, and a build is far more
            // expensive than the 180 ms a user spends finishing a click.
            delay(PREVIEW_DEBOUNCE_MS)
            coroutineContext.ensureActive()
            val tab = appState.tab(tabId)
            if (tab == null) {
                preview = DiagramPreviewState.Failed("This tab is no longer open.")
                return@launch
            }
            val built = runCatching {
                buildSequenceDiagram(
                    tab = tab,
                    spec = spec,
                    resolveLabel = sourceLabelResolver(spec),
                    cancellationCheck = CancellationCheck { if (!isActive) throw kotlinx.coroutines.CancellationException() },
                )
            }
            coroutineContext.ensureActive()
            built.fold(
                onSuccess = { preview = DiagramPreviewState.Computed(it) },
                onFailure = { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    preview = DiagramPreviewState.Failed(e.message ?: "Could not build this diagram.")
                },
            )
        }
    }

    /**
     * Backs `DiagramOptions.labelSource`'s SOURCE_METHOD/BOTH modes.
     *
     * The source index resolves a log line to a file and a bare method name — it persists no class
     * name and has no call graph (see source/SourceModel.kt), so the class here is derived from the
     * file name. That is a LABEL only; arrow direction always comes from the log's own ordering.
     * Returns a no-op resolver when labels don't need it, so a plain diagram never pays for it.
     */
    private fun sourceLabelResolver(spec: SeqDiagramSpec): (LogEntry) -> String? {
        if (spec.options.labelSource == com.indagium.diagram.LabelSource.MESSAGE) return { null }
        return { entry ->
            appState.resolveLogSource(entry.tag, entry.msg, limit = 1).firstOrNull()?.let { match ->
                val cls = File(match.site.filePath).name.substringBeforeLast('.')
                "$cls#${match.site.methodName}()"
            }
        }
    }

    // ── Writing the result into the notes ────────────────────────────────────────────────────

    /**
     * Appends (or, when editing, replaces) the diagram note and closes the dialog. Returns the
     * block id, or null when there was nothing to write.
     *
     * The note text is the fenced dialect source plus the spec/model header — see
     * diagram/DiagramSpecCodec.kt. Nothing about the .ann format changes: this is an ordinary
     * [com.indagium.model.AnnBlock.Note].
     *
     * A null return from the addNoteBlock() branch below is not necessarily a failure: upAnn's
     * overwrite-conflict gate (AppState.upAnn/PendingNoteOverwrite) can stash a first-time-conflict
     * mutation on pendingNoteOverwrite instead of committing it, in which case addNoteBlock's own
     * membership check correctly reports "not observable yet" as null — the add is pending a
     * decision on the "Existing notes found" prompt, not lost. Closing the dialog unconditionally
     * (cancel(), below) stays correct either way: the diagram itself was already fully built and
     * previewed, so there's nothing left for this dialog to do once the note text has been handed
     * off, whether that landed immediately or is waiting on the user's next click elsewhere.
     */
    fun confirm(): String? {
        val req = request ?: return null
        val diagram = preview.diagramOrNull ?: return null
        val source = diagram.toSource(req.spec.dialect)
        val text = encodeDiagramNote(req.spec, source, diagram)
        lastSpecByTab[req.tabId] = req.spec
        val blockId = if (req.editingBlockId != null) {
            appState.updateBlock(req.tabId, req.editingBlockId, text)
            req.editingBlockId
        } else {
            appState.addNoteBlock(req.tabId, text)
        }
        cancel()
        return blockId
    }

    /** The dialect source for the current preview, for the dialog's "Copy source" button. */
    fun currentSource(): String? {
        val req = request ?: return null
        return preview.diagramOrNull?.toSource(req.spec.dialect)
    }

    /** The current preview rendered to PNG bytes, for "Copy image". */
    fun currentPng(theme: DiagramTheme): ByteArray? =
        preview.diagramOrNull?.let { DiagramRenderCache.pngBytes(it, theme) }

    private companion object {
        const val PREVIEW_DEBOUNCE_MS = 180L
    }
}

/** Every diagram note in [tab], paired with its block id — the one place that decides "is this
 *  Note actually a diagram", so the panel, the export and the MCP layer can never disagree. */
fun LogTab.diagramNotes(): List<Pair<String, com.indagium.diagram.ParsedDiagram>> =
    annotations.blocks.mapNotNull { block ->
        (block as? com.indagium.model.AnnBlock.Note)?.let { note ->
            parseDiagramNote(note.text)?.let { note.id to it }
        }
    }
