package com.indagium.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.indagium.diagram.DiagramAttachmentMetadata
import com.indagium.diagram.DiagramAttachmentMode
import com.indagium.diagram.DiagramExportMode
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
import com.indagium.utils.computeLogFingerprint
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
    /** Set for a workspace opened from the durable library.  The existing note-editing contract
     * stays untouched; this only lets Save/Attach update the matching library record. */
    val libraryItemId: String? = null,
)

/** Whether regenerating a cached library diagram against an open tab is safe without an explicit
 * warning.  Filename equality is deliberately insufficient: entry IDs are per-log. */
enum class DiagramRegenerationCheck { MATCH, SOURCE_MISMATCH, NO_OPEN_TAB }

/** Read-only workspace payload for a cached diagram whose original log is not open.  It is kept
 * separate from [SeqDiagramRequest]: that type promises a real AppState LogTab for preview
 * rebuilding, while this type explicitly promises callers they must render cached data only. */
data class OfflineDiagramLibraryRequest(
    val item: DiagramLibraryItem,
    val spec: SeqDiagramSpec,
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
class SeqDiagramCoordinator(
    private val appState: AppState,
    private val libraryStore: DiagramLibraryStore = DiagramLibraryStore(),
) {

    /** Non-null exactly while the dialog is open. */
    var request by mutableStateOf<SeqDiagramRequest?>(null)

    var preview by mutableStateOf<DiagramPreviewState>(DiagramPreviewState.NotComputed)
        private set

    /** The library item currently presented in the workspace.  It remains available, with its
     * carried model, even when no source log is open; in that state the UI must treat it as
     * read-only until a matching log is selected for regeneration. */
    var openedLibraryItem by mutableStateOf<DiagramLibraryItem?>(null)
        private set

    var libraryOpenReadOnly by mutableStateOf(false)
        private set

    var offlineLibraryRequest by mutableStateOf<OfflineDiagramLibraryRequest?>(null)
        private set

    // DiagramLibraryStore is intentionally a plain disk-backed store.  This revision bridges its
    // mutations into Compose so a visible Notes-panel section immediately reflects save/delete.
    private var libraryRevision by mutableStateOf(0)

    /** Global, searchable summaries for the library panel. */
    fun searchLibrary(query: String = "", source: DiagramSourceIdentity? = null): List<DiagramLibrarySummary> =
        libraryStore.search(query, source)

    fun recentLibrary(limit: Int = 20): List<DiagramLibrarySummary> = libraryStore.recent(limit)

    fun libraryItem(id: String): DiagramLibraryItem? = libraryStore.get(id)

    fun libraryForSource(source: DiagramSourceIdentity): List<DiagramLibraryItem> = libraryStore.forSource(source)

    /**
     * Returns only diagrams created from this exact log identity.  The fingerprint makes this
     * deliberately stricter than a filename/path check: two revisions of the same log path must
     * not share a Notes-panel library section.
     */
    fun libraryForTab(tab: LogTab): List<DiagramLibraryItem> {
        @Suppress("UNUSED_VARIABLE")
        val observedRevision = libraryRevision
        return libraryForSource(sourceIdentity(tab))
    }

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
        openedLibraryItem = null
        libraryOpenReadOnly = false
        offlineLibraryRequest = null
        requestPreview(tabId, spec)
    }

    /** Opens the dialog on an existing diagram note, repopulated from the spec its header carries.
     *  Returns false when [blockId] isn't a diagram note (so the caller can leave it as text). */
    fun beginEdit(tabId: String, blockId: String): Boolean {
        val tab = appState.tab(tabId) ?: return false
        val block = tab.annotations.blocks.firstOrNull { it.id == blockId } as? com.indagium.model.AnnBlock.Note ?: return false
        val parsed = parseDiagramNote(block.text) ?: return false
        request = SeqDiagramRequest(tabId, parsed.spec, editingBlockId = blockId)
        openedLibraryItem = null
        libraryOpenReadOnly = false
        offlineLibraryRequest = null
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
        openedLibraryItem = null
        libraryOpenReadOnly = false
        offlineLibraryRequest = null
    }

    fun updateSpec(spec: SeqDiagramSpec) {
        val current = request ?: return
        request = current.copy(spec = spec)
        requestPreview(current.tabId, spec)
    }

    // ── Diagram library ─────────────────────────────────────────────────────────────────────

    /**
     * Opens a saved diagram from its cached codec snapshot.  Supplying a currently open [tabId]
     * makes it editable/rebuildable (subject to [regenerationCheck]); omitting it intentionally
     * gives callers a fully viewable read-only cached diagram when the original log is unavailable.
     */
    fun openLibraryItem(id: String, tabId: String? = null): Boolean {
        val item = libraryStore.markOpened(id) ?: return false
        libraryRevision++
        val parsed = item.parsed ?: return false
        val usableTabId = tabId?.takeIf { appState.tab(it) != null }
        openedLibraryItem = item
        request = usableTabId?.let { SeqDiagramRequest(it, parsed.spec, libraryItemId = item.id) }
        libraryOpenReadOnly = usableTabId == null
        offlineLibraryRequest = if (usableTabId == null) OfflineDiagramLibraryRequest(item, parsed.spec) else null
        preview = parsed.model?.let { DiagramPreviewState.Computed(it) } ?: DiagramPreviewState.NotComputed
        return true
    }

    /** Returns the provenance gate the workspace should show before calling [requestPreview] to
     * regenerate a saved diagram.  A caller may still let the user explicitly accept a mismatch. */
    fun regenerationCheck(id: String, tabId: String): DiagramRegenerationCheck {
        val item = libraryStore.get(id) ?: return DiagramRegenerationCheck.NO_OPEN_TAB
        val tab = appState.tab(tabId) ?: return DiagramRegenerationCheck.NO_OPEN_TAB
        return if (sourceIdentity(tab) == item.source) DiagramRegenerationCheck.MATCH else DiagramRegenerationCheck.SOURCE_MISMATCH
    }

    /** Saves the currently rendered workspace as a draft, or updates its originating library
     * record.  It never attaches a Note block; use [attachLibrarySnapshot] or [attachLibraryLink]
     * for that explicit second step. */
    fun saveDraft(title: String? = null, description: String? = null): DiagramLibraryItem? {
        val req = request ?: return null
        val diagram = preview.diagramOrNull ?: return null
        val tab = appState.tab(req.tabId) ?: return null
        val source = diagram.toSource(req.spec.dialect)
        val snapshot = DiagramLibrarySnapshot.create(req.spec, source, diagram)
        val now = System.currentTimeMillis()
        val resolvedTitle = title ?: req.spec.title.ifBlank { "Untitled diagram" }
        val saved = if (req.libraryItemId == null) {
            libraryStore.create(resolvedTitle, description.orEmpty(), sourceIdentity(tab), snapshot, now)
        } else {
            libraryStore.update(req.libraryItemId) { existing ->
                existing.copy(
                    title = title ?: existing.title,
                    description = description ?: existing.description,
                    source = sourceIdentity(tab), snapshot = snapshot, updatedAt = now,
                )
            } ?: return null
        }
        request = req.copy(libraryItemId = saved.id)
        openedLibraryItem = saved
        libraryOpenReadOnly = false
        libraryRevision++
        return saved
    }

    /** Lower-level save API for callers that already have a codec-produced snapshot (for example
     * metadata-aware DiagramSpecCodec flows).  The snapshot is stored verbatim, not rebuilt. */
    fun saveLibraryItem(
        title: String,
        description: String,
        source: DiagramSourceIdentity,
        snapshot: DiagramLibrarySnapshot,
        id: String? = null,
    ): DiagramLibraryItem? = if (id == null) {
        libraryStore.create(title, description, source, snapshot).also { libraryRevision++ }
    } else {
        libraryStore.update(id) { existing ->
            existing.copy(title = title, description = description, source = source, snapshot = snapshot, updatedAt = System.currentTimeMillis())
        }?.also { libraryRevision++ }
    }

    fun deleteLibraryItem(id: String): Boolean {
        if (openedLibraryItem?.id == id) {
            request = null
            preview = DiagramPreviewState.NotComputed
            openedLibraryItem = null
            libraryOpenReadOnly = false
            offlineLibraryRequest = null
        }
        return libraryStore.delete(id).also { deleted -> if (deleted) libraryRevision++ }
    }

    /** Adds the exact saved codec artifact as a normal Note.  The snapshot owns its model/source
     * and is consequently reproducible even if the library item is changed or deleted later. */
    fun attachLibrarySnapshot(tabId: String, libraryItemId: String, afterBlockId: String? = null): String? {
        val item = libraryStore.get(libraryItemId) ?: return null
        val attachedAt = System.currentTimeMillis()
        val blockId = appState.addNoteBlock(
            tabId,
            attachmentNote(item, DiagramAttachmentMode.SNAPSHOT, attachedAt, appState.settings.diagramDefaultExportMode),
            afterBlockId,
        ) ?: return null
        libraryStore.addAttachment(
            libraryItemId,
            DiagramLibraryAttachment(tabId, blockId, DiagramAttachmentKind.SNAPSHOT, attachedAt),
        )
        libraryRevision++
        return blockId
    }

    /** Adds a small, durable reference to the working diagram.  Rendering code can resolve it
     * through [resolveLibraryAttachment]; unlike a snapshot it follows later saves to [libraryItemId]. */
    fun attachLibraryLink(tabId: String, libraryItemId: String, afterBlockId: String? = null): String? {
        val item = libraryStore.get(libraryItemId) ?: return null
        val attachedAt = System.currentTimeMillis()
        val blockId = appState.addNoteBlock(
            tabId,
            attachmentNote(item, DiagramAttachmentMode.LINKED, attachedAt, appState.settings.diagramDefaultExportMode),
            afterBlockId,
        ) ?: return null
        libraryStore.addAttachment(
            libraryItemId,
            DiagramLibraryAttachment(tabId, blockId, DiagramAttachmentKind.LINK, attachedAt),
        )
        libraryRevision++
        return blockId
    }

    /** Resolves either a live-link Note or an inline snapshot Note.  This is intentionally a
     * read-only operation so annotations opened without a source log can still display diagrams. */
    fun resolveLibraryAttachment(noteText: String): DiagramLibraryItem? {
        val snapshot = DiagramLibrarySnapshot.fromDiagramNote(noteText) ?: return null
        val parsed = snapshot.parsed() ?: return null
        if (parsed.attachment?.mode == DiagramAttachmentMode.LINKED) {
            parsed.attachment.diagramId?.let(libraryStore::get)?.let { return it }
        }
        return DiagramLibraryItem(
            id = "snapshot", title = parsed.spec.title.ifBlank { "Diagram snapshot" }, description = "",
            source = DiagramSourceIdentity(parsed.spec.sourceFile.orEmpty(), ""), snapshot = snapshot,
            createdAt = 0L, updatedAt = 0L,
        )
    }

    private fun sourceIdentity(tab: LogTab): DiagramSourceIdentity = DiagramSourceIdentity(
        sourcePath = tab.sourcePath ?: tab.filename,
        contentFingerprint = computeLogFingerprint(tab.logData),
    )

    /** Converts a saved document into a typed DiagramSpecCodec attachment.  This is the one
     * boundary that understands codec metadata: the library's on-disk snapshot remains opaque,
     * while Notes get a v2 attachment header that the panel can identify without parsing an
     * ad-hoc link comment. */
    private fun attachmentNote(
        item: DiagramLibraryItem,
        mode: DiagramAttachmentMode,
        attachedAt: Long,
        exportMode: DiagramExportMode,
    ): String {
        val parsed = requireNotNull(item.parsed) { "Stored diagram snapshot must remain parseable" }
        return encodeDiagramNote(
            spec = parsed.spec,
            source = parsed.source,
            model = parsed.model,
            attachment = DiagramAttachmentMetadata(
                diagramId = item.id,
                mode = mode,
                revision = item.updatedAt,
                attachedAtEpochMs = attachedAt,
                exportMode = exportMode,
            ),
        )
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
        // A new note captures today's preference as note-owned metadata. Editing must preserve
        // the existing note's metadata (or its legacy image default), rather than retroactively
        // applying the current preference to an already attached artifact.
        val attachment = if (req.editingBlockId == null) {
            DiagramAttachmentMetadata(exportMode = appState.settings.diagramDefaultExportMode)
        } else {
            (appState.tab(req.tabId)?.annotations?.blocks
                ?.firstOrNull { it.id == req.editingBlockId } as? com.indagium.model.AnnBlock.Note)
                ?.let { parseDiagramNote(it.text)?.attachment }
        }
        val text = encodeDiagramNote(req.spec, source, diagram, attachment = attachment)
        lastSpecByTab[req.tabId] = req.spec
        val blockId = if (req.editingBlockId != null) {
            appState.updateBlock(req.tabId, req.editingBlockId, text)
            req.editingBlockId
        } else {
            appState.addNoteBlock(req.tabId, text)
        }
        // Existing UI callers still use confirm() as their one-shot “Add to notes” action.  When
        // that workspace came from a draft, retain the attachment relationship as well; a null
        // block id means AppState deferred the write behind its overwrite gate, so do not record a
        // reference to a block that is not observable yet.
        if (blockId != null && req.libraryItemId != null) {
            libraryStore.addAttachment(
                req.libraryItemId,
                DiagramLibraryAttachment(req.tabId, blockId, DiagramAttachmentKind.SNAPSHOT, System.currentTimeMillis()),
            )
            libraryRevision++
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
