package com.indagium.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.indagium.diagram.ArrowMode
import com.indagium.diagram.DiagramAttachmentMetadata
import com.indagium.diagram.DiagramAttachmentMode
import com.indagium.diagram.DiagramDialect
import com.indagium.diagram.DiagramExportMode
import com.indagium.diagram.DiagramParticipantCandidate
import com.indagium.diagram.DiagramResolvedTrace
import com.indagium.diagram.DiagramSourceInteraction
import com.indagium.diagram.DiagramTheme
import com.indagium.diagram.ManualDiagramDocument
import com.indagium.diagram.ManualDiagramSeedConfiguration
import com.indagium.diagram.ManualDiagramSeedStrategy
import com.indagium.diagram.ManualRegenerationReview
import com.indagium.diagram.SeqDiagram
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.applyReviewedManualRegeneration
import com.indagium.diagram.buildSequenceDiagram
import com.indagium.diagram.diagramParticipantCandidates
import com.indagium.diagram.encodeDiagramNote
import com.indagium.diagram.manualDocumentFromDiagram
import com.indagium.diagram.parseDiagramNote
import com.indagium.diagram.reviewManualRegeneration
import com.indagium.diagram.toSource
import com.indagium.model.LogEntry
import com.indagium.model.LogTab
import com.indagium.source.SOURCE_INDEX_VERSION
import com.indagium.source.SourceEnrichmentResolver
import com.indagium.source.SourceTraceInferenceEngine
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

// A parsed "at Owner.method(location)" stack-trace line, used only by sourceInteractionResolver's
// stack-frame branch to pair each callee frame with the caller frame logged right before it.
private data class SourceStackFrame(val ownerType: String, val methodName: String, val location: String)

private const val DOLLAR = '$'
private val SOURCE_STACK_FRAME_PATTERN =
    Regex("^\\s*at\\s+([A-Za-z0-9_.$DOLLAR]+)\\.([A-Za-z0-9_$DOLLAR<>]+)\\(([^)]*)\\)\\s*\\z")

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

/** The main content currently shown by the application.  Diagram workspaces deliberately live
 * outside [AppState.tabs]: a log tab remains a log tab, including for autosave and compare mode. */
sealed interface ActiveSurface {
    data class Log(val tabId: String) : ActiveSurface

    data class Diagram(val workspaceId: String) : ActiveSurface
}

/** How [DiagramWorkspaceSession.zoom] is currently being driven. FIT and FIT_WIDTH are "sticky"
 *  modes: the canvas keeps recomputing and reapplying that fit on every rebuild (a spec edit, a
 *  window resize) instead of the one-shot behaviour the three `SegmentedControl` entries used to
 *  have. MANUAL means the user (stepper, ctrl/cmd-wheel) owns the zoom value and nothing should
 *  touch it until they pick a mode again. */
enum class DiagramZoomMode { MANUAL, FIT, FIT_WIDTH }

data class ManualSeedUndoSnapshot(
    val document: ManualDiagramDocument,
    val lifelineOrder: List<String>,
)

/** A diagram is an independent working document.  Keeping the last rendered model here is what
 * makes it possible to close a source log yet continue inspecting/copying its diagram. */
data class DiagramWorkspaceSession(
    val id: String,
    val sourceTabId: String?,
    /** Kept when [request] is cleared after closing the source log. */
    val spec: SeqDiagramSpec,
    val request: SeqDiagramRequest?,
    val preview: DiagramPreviewState = DiagramPreviewState.NotComputed,
    val candidates: DiagramCandidateState = DiagramCandidateState.NotComputed,
    val openedLibraryItem: DiagramLibraryItem? = null,
    val offlineRequest: OfflineDiagramLibraryRequest? = null,
    val dirty: Boolean = false,
    val inspectorOpen: Boolean = true,
    val inspectorWidth: Float = 330f,
    /** Canvas viewport. FIT is the default so a freshly opened workspace auto-fits exactly once,
     *  the same first-render behaviour the old always-refit LaunchedEffect gave every workspace —
     *  see [SeqDiagramWorkspace]'s DiagramPreviewPane for how the mode is applied. */
    val zoom: Float = 1f,
    val zoomMode: DiagramZoomMode = DiagramZoomMode.FIT,
    /** Only new workspaces use this one-shot asynchronous inferred-to-manual seed. */
    val initialManualSeedPending: Boolean = false,
    val manualSeedUndo: ManualSeedUndoSnapshot? = null,
    /** True when a user changed manual content or order after the last seed/reset. */
    val manualSeedEditsSinceApply: Boolean = false,
    val manualSeedBusy: Boolean = false,
    val manualSeedStatus: String? = null,
    val manualSeedReview: ManualRegenerationReview? = null,
    /** Transient row/canvas association; keyed by stable manual identity and never persisted. */
    val focusedManualInteractionId: String? = null,
    val hoveredManualInteractionId: String? = null,
)

sealed class DiagramCandidateState {
    data object NotComputed : DiagramCandidateState()

    data class Computing(val previous: List<DiagramParticipantCandidate> = emptyList()) : DiagramCandidateState()

    data class Computed(val values: List<DiagramParticipantCandidate>) : DiagramCandidateState()

    data class Failed(val message: String) : DiagramCandidateState()

    val valuesOrEmpty: List<DiagramParticipantCandidate>
        get() = when (this) {
            is Computed -> values
            is Computing -> previous
            else -> emptyList()
        }
}

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
    /** Open dedicated diagram surfaces.  They are session-only: drafts/notes remain the durable
     * representation, while an open workspace is comparable to an editor tab. */
    var workspaces by mutableStateOf<List<DiagramWorkspaceSession>>(emptyList())
        private set
    var activeWorkspaceId by mutableStateOf<String?>(null)
        private set
    var pendingCloseWorkspaceId by mutableStateOf<String?>(null)
        private set
    var candidatePreview by mutableStateOf<DiagramCandidateState>(DiagramCandidateState.NotComputed)
        private set

    /** Test-visible instrumentation for the metadata-edit performance contract. */
    internal val candidateScanCount = java.util.concurrent.atomic.AtomicLong()
    internal val previewBuildCount = java.util.concurrent.atomic.AtomicLong()

    private fun activeWorkspace(): DiagramWorkspaceSession? =
        activeWorkspaceId?.let { id -> workspaces.firstOrNull { it.id == id } }

    val activeSession: DiagramWorkspaceSession? get() = activeWorkspace()

    private fun replaceWorkspace(id: String, transform: (DiagramWorkspaceSession) -> DiagramWorkspaceSession) {
        workspaces = workspaces.map { if (it.id == id) transform(it) else it }
    }

    /** Rebuilds [workspaces] into [orderedIds]' order — the diagram-tab-strip counterpart of
     *  [AppState.reorderTabs]. Unknown ids in [orderedIds] are ignored (a stale drag computed
     *  against an order that has since changed must never invent a workspace), and any live
     *  workspace whose id was left out of [orderedIds] is appended rather than dropped, so a
     *  partial/stale order can never silently close a tab. Neither [activeWorkspaceId] nor
     *  [appState.activeSurface] is touched — reordering never changes what's selected. */
    fun reorderWorkspaces(orderedIds: List<String>) {
        val byId = workspaces.associateBy { it.id }
        val reordered = orderedIds.mapNotNull(byId::get)
        val omitted = workspaces.filter { it.id !in orderedIds }
        workspaces = reordered + omitted
    }

    /** Selects an existing diagram surface without changing AppState.tabs. */
    fun activateWorkspace(id: String): Boolean {
        val workspace = workspaces.firstOrNull { it.id == id } ?: return false
        activeWorkspaceId = id
        request = workspace.request
        preview = workspace.preview
        candidatePreview = workspace.candidates
        openedLibraryItem = workspace.openedLibraryItem
        libraryOpenReadOnly = workspace.sourceTabId == null || appState.tab(workspace.sourceTabId) == null
        offlineLibraryRequest = workspace.offlineRequest
        appState.activeSurface = ActiveSurface.Diagram(id)
        return true
    }

    private fun persistActiveWorkspace(dirty: Boolean? = null) {
        val id = activeWorkspaceId ?: return
        replaceWorkspace(id) { current ->
            current.copy(
                sourceTabId = request?.tabId ?: current.sourceTabId,
                spec = request?.spec ?: current.spec,
                request = request,
                preview = preview,
                candidates = candidatePreview,
                openedLibraryItem = openedLibraryItem,
                offlineRequest = offlineLibraryRequest,
                dirty = dirty ?: current.dirty,
            )
        }
    }

    fun updateInspector(open: Boolean? = null, width: Float? = null) {
        val id = activeWorkspaceId ?: return
        replaceWorkspace(id) { current ->
            current.copy(
                inspectorOpen = open ?: current.inspectorOpen,
                inspectorWidth = (width ?: current.inspectorWidth).coerceIn(
                    INSPECTOR_MIN_WIDTH,
                    INSPECTOR_MAX_WIDTH,
                ),
            )
        }
    }

    /** Applies one drag delta to the current inspector width.
     *
     * The caller can outlive a recomposition while a pointer drag is in progress, so the current
     * workspace must be read here rather than captured by the composable that owns the divider.
     */
    fun resizeInspectorBy(delta: Float) {
        val id = activeWorkspaceId ?: return
        replaceWorkspace(id) { current ->
            current.copy(
                inspectorWidth = (current.inspectorWidth + delta).coerceIn(
                    INSPECTOR_MIN_WIDTH,
                    INSPECTOR_MAX_WIDTH,
                ),
            )
        }
    }

    /** Canvas viewport counterpart of [updateInspector] — same shape (resolve the active
     *  workspace, [replaceWorkspace], coerce inputs), stored per-workspace so switching diagram
     *  tabs never leaks one workspace's zoom/mode into another's. */
    fun updateViewport(zoom: Float? = null, mode: DiagramZoomMode? = null) {
        val id = activeWorkspaceId ?: return
        replaceWorkspace(id) { current ->
            current.copy(
                zoom = (zoom ?: current.zoom).coerceIn(VIEWPORT_MIN_ZOOM, VIEWPORT_MAX_ZOOM),
                zoomMode = mode ?: current.zoomMode,
            )
        }
    }

    fun focusManualInteraction(interactionId: String?) {
        val id = activeWorkspaceId ?: return
        replaceWorkspace(id) { it.copy(focusedManualInteractionId = interactionId) }
    }

    fun hoverManualInteraction(interactionId: String?) {
        val id = activeWorkspaceId ?: return
        replaceWorkspace(id) { it.copy(hoveredManualInteractionId = interactionId) }
    }

    private fun openWorkspace(
        sourceTabId: String?, request: SeqDiagramRequest?, preview: DiagramPreviewState,
        item: DiagramLibraryItem? = null, offline: OfflineDiagramLibraryRequest? = null,
        initialManualSeedPending: Boolean = false,
    ) {
        persistActiveWorkspace()
        val id = "diagram-${java.util.UUID.randomUUID()}"
        val workspace = DiagramWorkspaceSession(
            id = id, sourceTabId = sourceTabId, spec = request?.spec ?: offline?.spec ?: SeqDiagramSpec(),
            request = request, preview = preview, openedLibraryItem = item, offlineRequest = offline,
            initialManualSeedPending = initialManualSeedPending,
        )
        workspaces = workspaces + workspace
        activeWorkspaceId = id
        this.request = request
        this.preview = preview
        candidatePreview = DiagramCandidateState.NotComputed
        openedLibraryItem = item
        libraryOpenReadOnly = sourceTabId == null || appState.tab(sourceTabId) == null
        offlineLibraryRequest = offline
        appState.activeSurface = ActiveSurface.Diagram(id)
    }

    /** Closing is intentionally a two-step API so the host can offer Save / Discard / Cancel. */
    fun workspaceNeedsSave(id: String): Boolean = workspaces.firstOrNull { it.id == id }?.dirty == true

    fun requestCloseWorkspace(id: String) {
        if (workspaceNeedsSave(id)) {
            pendingCloseWorkspaceId = id
            activateWorkspace(id)
        } else {
            closeWorkspace(id)
        }
    }

    fun cancelWorkspaceClose() {
        pendingCloseWorkspaceId = null
    }

    fun closeWorkspace(id: String, save: Boolean = false): Boolean {
        val workspace = workspaces.firstOrNull { it.id == id } ?: return false
        if (save) {
            activateWorkspace(id)
            if (saveDraft() == null) return false
        }
        if (pendingCloseWorkspaceId == id) pendingCloseWorkspaceId = null
        previewJobs.remove(id)?.cancel()
        candidateJobs.remove(id)?.cancel()
        seedJobs.remove(id)?.cancel()
        previewGenerations.remove(id)
        candidateGenerations.remove(id)
        workspaces = workspaces.filterNot { it.id == id }
        if (activeWorkspaceId == id) {
            val next = workspaces.lastOrNull()
            if (next != null) {
                activateWorkspace(next.id)
            } else {
                activeWorkspaceId = null
                request = null
                preview = DiagramPreviewState.NotComputed
                candidatePreview = DiagramCandidateState.NotComputed
                openedLibraryItem = null
                offlineLibraryRequest = null
                appState.activeSurface = appState.activeTab()?.id?.let(ActiveSurface::Log)
            }
        }
        return true
    }

    /** Source logs are allowed to close independently.  No cached model is discarded; requests
     * to regenerate simply report that a source must be relinked. */
    fun sourceTabClosed(tabId: String) {
        workspaces.filter { it.sourceTabId == tabId }.forEach { workspace ->
            replaceWorkspace(workspace.id) { it.copy(request = null, dirty = it.dirty) }
        }
        if (request?.tabId == tabId) {
            request = null
            libraryOpenReadOnly = true
            offlineLibraryRequest = activeWorkspace()?.let { workspace ->
                workspace.openedLibraryItem?.let { OfflineDiagramLibraryRequest(it, workspace.spec) }
            }
            persistActiveWorkspace()
        }
    }

    /** Relinks an offline workspace to an already open log.  The cached preview remains visible
     * until the caller explicitly regenerates it. */
    fun relinkWorkspace(id: String, tabId: String): Boolean {
        val workspace = workspaces.firstOrNull { it.id == id } ?: return false
        val tab = appState.tab(tabId) ?: return false
        val spec = workspace.spec.copy(sourceFile = File(tab.filename).name)
        replaceWorkspace(id) {
            it.copy(
                sourceTabId = tabId,
                request = SeqDiagramRequest(tabId, spec, libraryItemId = workspace.request?.libraryItemId),
                offlineRequest = null,
            )
        }
        activateWorkspace(id)
        return true
    }

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
    private val previewJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val candidateJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val seedJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val previewGenerations = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val candidateGenerations = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    // ── Opening ──────────────────────────────────────────────────────────────────────────────

    /**
     * Opens the dialog for [tabId]. [seedIds] is the current row selection, if any: a selection is
     * by far the most common way a user says "this part of the log", so it becomes the default
     * range (min..max, matching how AppState.collapseRange treats an inclusive id span).
     */
    fun begin(tabId: String, seedIds: Set<Int> = emptySet()) {
        val tab = appState.tab(tabId) ?: return
        // "Select a collapsed block" must mean what uncollapsing it and selecting the same lines by
        // hand would mean (expandSelectionThroughCollapsedBlocks, utils/Filter.kt) — widened BEFORE
        // deriving either the range or the seeded components below, so one expansion fixes both: the
        // range spans the whole fold, and the interior's own tags become enabled components instead
        // of falling into unmappedTagPolicy's HIDE default. Only the seed path does this — updateSpec
        // and requestCandidates never re-derive from a raw selection, so a user-typed range survives
        // verbatim.
        val effective = com.indagium.utils.expandSelectionThroughCollapsedBlocks(tab, seedIds)
        val range = if (effective.isNotEmpty()) {
            com.indagium.diagram.DiagramRange.Ids(effective.min(), effective.max(), effective)
        } else {
            com.indagium.diagram.DiagramRange.VisibleView
        }
        // Reuse this tab's last spec (participants, options, dialect) so a second diagram from the
        // same log doesn't start from scratch — only the range is re-seeded from the new selection.
        val base = lastSpecByTab[tabId] ?: SeqDiagramSpec()
        // A non-empty selection, including exactly one row, is an inclusive range.  Its exact
        // tags start enabled; tags discovered elsewhere in the filtered view stay opt-in.
        val selectedTags = effective.mapNotNull(tab.rmap::get).map { it.tag }.distinct()
        val seededComponents = if (effective.isNotEmpty()) selectedTags.map { tag ->
            com.indagium.diagram.DiagramComponent(tag, tag, setOf(tag), enabled = true)
        } else base.components.ifEmpty {
            // Keeps a brand-new no-selection workspace in explicit component mode from its first
            // build. Candidate tags discovered asynchronously remain disabled until chosen.
            listOf(com.indagium.diagram.DiagramComponent(EMPTY_COMPONENT_ID, "", emptySet(), enabled = false))
        }
        val spec = base.copy(
            range = range,
            components = seededComponents,
            sourceFile = File(tab.filename).name,
            authoringMode = com.indagium.diagram.DiagramAuthoringMode.MANUAL,
            manualDocument = ManualDiagramDocument(),
        )
        openWorkspace(
            tabId,
            SeqDiagramRequest(tabId, spec),
            DiagramPreviewState.NotComputed,
            initialManualSeedPending = true,
        )
        requestCandidates(tabId, spec)
        requestPreview(tabId, spec)
    }

    /** The exact [com.indagium.diagram.DiagramRange.Ids] that `begin(tabId, tab.selected)` would
     *  seed right now — expanded through any collapsed fold the same way `begin` itself does, so the
     *  Selection pill (SeqDiagramDialog.kt's RangeSection) and the initial seed can never disagree
     *  about what clicking the pill will produce. Null when there's nothing selected, matching the
     *  pill's own guard. */
    fun selectionRange(tabId: String): com.indagium.diagram.DiagramRange.Ids? {
        val tab = appState.tab(tabId) ?: return null
        val effective = com.indagium.utils.expandSelectionThroughCollapsedBlocks(tab, tab.selected)
        if (effective.isEmpty()) return null
        return com.indagium.diagram.DiagramRange.Ids(effective.min(), effective.max(), effective)
    }

    /** Opens the dialog on an existing diagram note, repopulated from the spec its header carries.
     *  Returns false when [blockId] isn't a diagram note (so the caller can leave it as text). */
    fun beginEdit(tabId: String, blockId: String): Boolean {
        val tab = appState.tab(tabId) ?: return false
        val block = tab.annotations.blocks.firstOrNull { it.id == blockId } as? com.indagium.model.AnnBlock.Note ?: return false
        val parsed = parseDiagramNote(block.text) ?: return false
        // Seed the preview with the note's OWN carried model rather than immediately rebuilding:
        // the note may have been opened without its log (Case Library "notes only"), where a
        // rebuild would produce nothing. Regenerating is an explicit action from here.
        val editableSpec = editableManualSpec(parsed.spec, parsed.model)
        openWorkspace(
            tabId, SeqDiagramRequest(tabId, editableSpec, editingBlockId = blockId),
            parsed.model?.let { DiagramPreviewState.Computed(it.copy(spec = editableSpec)) } ?: DiagramPreviewState.NotComputed,
        )
        return true
    }

    fun cancel() {
        activeWorkspaceId?.let { closeWorkspace(it) }
    }

    fun updateSpec(spec: SeqDiagramSpec) {
        val current = request ?: return
        val manualChanged = current.spec.manualDocument != spec.manualDocument ||
            current.spec.lifelineOrder != spec.lifelineOrder
        request = current.copy(spec = spec)
        activeWorkspaceId?.let { workspaceId ->
            replaceWorkspace(workspaceId) { workspace ->
                workspace.copy(
                    // Metadata and inferred-build options may change while the first automatic
                    // seed is running. Keep that seed eligible until the user edits manual rows;
                    // otherwise the option change cancels the seed and renders an empty manual
                    // document instead.
                    initialManualSeedPending = workspace.initialManualSeedPending && !manualChanged,
                    manualSeedStatus = null,
                    manualSeedReview = if (manualChanged) null else workspace.manualSeedReview,
                    manualSeedEditsSinceApply = workspace.manualSeedEditsSinceApply || manualChanged,
                )
            }
        }
        persistActiveWorkspace(dirty = true)
        // Text metadata and export dialect have no bearing on source rows or the diagram model.
        // Keeping them off this path prevents a title keystroke from starting an O(n) scan.
        if (requiresRebuild(current.spec, spec)) requestPreview(current.tabId, spec)
        if (current.spec.range != spec.range) requestCandidates(current.tabId, spec)
    }

    val canRevertManualSeed: Boolean get() = activeWorkspace()?.manualSeedUndo != null
    val manualSeedNeedsConfirmation: Boolean get() = activeWorkspace()?.manualSeedEditsSinceApply == true
    val manualSeedBusy: Boolean get() = activeWorkspace()?.manualSeedBusy == true
    val manualSeedStatus: String? get() = activeWorkspace()?.manualSeedStatus
    val manualSeedReview: ManualRegenerationReview? get() = activeWorkspace()?.manualSeedReview

    /**
     * Replaces the current manual document with one inferred using independently selected evidence.
     *
     * `force` is threaded through from the UI (SeqDiagramInspector.kt's onApplySeed) but not yet
     * consumed here — flagged during a detekt cleanup pass rather than guessed at and implemented;
     * worth a follow-up to confirm whether it should bypass reviewManualRegeneration's conflict
     * review below.
     */
    @Suppress("UnusedParameter")
    fun applyManualSeed(configuration: ManualDiagramSeedConfiguration, force: Boolean = false) {
        val workspaceId = activeWorkspaceId ?: return
        val currentRequest = request ?: return
        val tab = appState.tab(currentRequest.tabId) ?: return
        activeWorkspace() ?: return
        if (!configuration.enabled) {
            replaceWorkspace(workspaceId) { it.copy(manualSeedStatus = "Choose at least one source option.") }
            return
        }
        val expectedSpec = currentRequest.spec
        val undo = ManualSeedUndoSnapshot(expectedSpec.manualDocument, expectedSpec.lifelineOrder)
        replaceWorkspace(workspaceId) {
            it.copy(manualSeedBusy = true, manualSeedStatus = "Building ${configuration.label}…")
        }
        seedJobs.remove(workspaceId)?.cancel()
        val job = scope.launch {
            val cancellation = CancellationCheck { if (!isActive) throw kotlinx.coroutines.CancellationException() }
            val inferredSpec = expectedSpec.forManualInference().copy(
                authoringMode = com.indagium.diagram.DiagramAuthoringMode.INFERRED,
                manualDocument = ManualDiagramDocument(),
                mode = ArrowMode.EVIDENCE_FLOW,
                sourceEnrichment = expectedSpec.sourceEnrichment.copy(enabled = configuration.reconstructSourceTrace),
                options = expectedSpec.options.copy(threadHandoffArrows = configuration.inferThreadHandoffs),
            )
            val result = runCatching {
                buildPreviewDiagram(tab, inferredSpec, cancellation).let { diagram ->
                    diagram to manualDocumentFromDiagram(diagram)
                }
            }
            coroutineContext.ensureActive()
            if (activeWorkspaceId != workspaceId || request?.spec != expectedSpec) {
                replaceWorkspace(workspaceId) { it.copy(manualSeedBusy = false, manualSeedStatus = null) }
                return@launch
            }
            result.fold(
                onSuccess = { (inferredDiagram, document) ->
                    if (document.interactions.isEmpty()) {
                        replaceWorkspace(workspaceId) {
                            it.copy(manualSeedBusy = false, manualSeedStatus = "No interactions were found; existing content was preserved.")
                        }
                    } else {
                        val inferredParticipants = inferredDiagram.participants.map { it.id }
                        val next = expectedSpec.withSeededParticipants(inferredDiagram.participants).copy(
                            authoringMode = com.indagium.diagram.DiagramAuthoringMode.MANUAL,
                            manualDocument = document,
                            lifelineOrder = (expectedSpec.lifelineOrder + inferredParticipants).distinct(),
                        )
                        val review = reviewManualRegeneration(expectedSpec.manualDocument, document)
                            .copy(candidateSpec = next)
                        replaceWorkspace(workspaceId) {
                            it.copy(
                                manualSeedUndo = undo,
                                manualSeedBusy = false,
                                manualSeedReview = review,
                                manualSeedStatus = "Review " + configuration.label + ": " +
                                    review.newCount + " new, " + review.changedAutoCount + " changed auto, " +
                                    review.editedKeptCount + " edits kept.",
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    replaceWorkspace(workspaceId) {
                        it.copy(
                            manualSeedBusy = false,
                            manualSeedStatus = "Could not build ${configuration.label}: ${error.message ?: "source analysis failed"}",
                        )
                    }
                },
            )
        }
        seedJobs[workspaceId] = job
    }

    fun cancelManualSeedReview() {
        val id = activeWorkspaceId ?: return
        replaceWorkspace(id) {
            it.copy(manualSeedReview = null, manualSeedBusy = false, manualSeedStatus = "Regeneration canceled.")
        }
    }

    fun acceptManualSeedReview() {
        val id = activeWorkspaceId ?: return
        val workspace = activeWorkspace() ?: return
        val review = workspace.manualSeedReview ?: return
        val current = request ?: return
        val safeDocument = applyReviewedManualRegeneration(current.spec.manualDocument, review)
        val next = (review.candidateSpec ?: current.spec).copy(
            authoringMode = com.indagium.diagram.DiagramAuthoringMode.MANUAL,
            manualDocument = safeDocument,
        )
        replaceWorkspace(id) {
            it.copy(
                spec = next,
                request = it.request?.copy(spec = next),
                manualSeedReview = null,
                manualSeedEditsSinceApply = false,
                manualSeedStatus = "Reviewed regeneration applied; your edits were kept.",
            )
        }
        request = current.copy(spec = next)
        persistActiveWorkspace(dirty = true)
        requestPreview(current.tabId, next)
    }

    /** Compatibility adapter for callers and persisted-session tests using the old single-choice API. */
    fun applyManualSeed(strategy: ManualDiagramSeedStrategy) = applyManualSeed(
        when (strategy) {
            ManualDiagramSeedStrategy.SOURCE_TRACE -> ManualDiagramSeedConfiguration(reconstructSourceTrace = true)
            ManualDiagramSeedStrategy.THREAD_HANDOFFS -> ManualDiagramSeedConfiguration(
                reconstructSourceTrace = false,
                inferThreadHandoffs = true,
            )
        },
    )

    fun revertManualSeed() {
        val workspaceId = activeWorkspaceId ?: return
        val workspace = activeWorkspace() ?: return
        if (workspace.manualSeedBusy) return
        if (workspace.manualSeedReview != null) {
            replaceWorkspace(workspaceId) {
                it.copy(manualSeedReview = null, manualSeedUndo = null, manualSeedStatus = "Regeneration review canceled.")
            }
            return
        }
        val undo = workspace.manualSeedUndo ?: return
        val current = request ?: return
        val restored = current.spec.copy(
            authoringMode = com.indagium.diagram.DiagramAuthoringMode.MANUAL,
            manualDocument = undo.document,
            lifelineOrder = undo.lifelineOrder,
        )
        replaceWorkspace(workspaceId) {
            it.copy(
                spec = restored,
                request = it.request?.copy(spec = restored),
                manualSeedUndo = null,
                manualSeedEditsSinceApply = false,
                manualSeedStatus = "Restored the previous interactions.",
            )
        }
        request = current.copy(spec = restored)
        persistActiveWorkspace(dirty = true)
        requestPreview(current.tabId, restored)
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
        openWorkspace(
            usableTabId,
            usableTabId?.let { SeqDiagramRequest(it, parsed.spec, libraryItemId = item.id) },
            parsed.model?.let { DiagramPreviewState.Computed(it) } ?: DiagramPreviewState.NotComputed,
            item = item,
            offline = if (usableTabId == null) OfflineDiagramLibraryRequest(item, parsed.spec) else null,
        )
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
        val persistedSpec = persistableSpec(req.spec) ?: return null
        val persistedDiagram = diagram.copy(spec = persistedSpec)
        val source = persistedDiagram.toSource(persistedSpec.dialect)
        val snapshot = DiagramLibrarySnapshot.create(persistedSpec, source, persistedDiagram)
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
        request = req.copy(spec = persistedSpec, libraryItemId = saved.id)
        openedLibraryItem = saved
        libraryOpenReadOnly = false
        persistActiveWorkspace(dirty = false)
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
            activeWorkspaceId?.let { closeWorkspace(it) }
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

    /** Candidate tiers are another full range scan, so they have their own latest-only lane.
     * Components are deliberately removed for the scan: counts/tiering depend only on the source
     * view and range; enabled/merged ownership is overlaid synchronously by the inspector. */
    fun requestCandidates(tabId: String, spec: SeqDiagramSpec) {
        val workspaceId = activeWorkspaceId ?: return
        val generation = candidateGenerations.computeIfAbsent(workspaceId) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        val tab = appState.tab(tabId) ?: return
        candidateJobs.remove(workspaceId)?.cancel()
        publishCandidates(workspaceId, DiagramCandidateState.Computing(candidatePreview.valuesOrEmpty))
        // Candidate search is intentionally independent of the main log filter. Selecting one of
        // these candidates is the explicit opt-in that makes its rows part of the diagram; the
        // persisted option remains only as a compatibility field for older callers.
        val sourceSpec = spec.copy(
            components = emptyList(), participants = emptyList(),
            options = spec.options.copy(includeRowsHiddenByFilter = true),
        )
        val logRef = System.identityHashCode(tab.logData)
        val filterRef = System.identityHashCode(tab.filter)
        val job = scope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            coroutineContext.ensureActive()
            val latest = appState.tab(tabId) ?: return@launch
            // A changed log/filter makes this worker stale; the Composable effect schedules the
            // replacement using the new key rather than publishing a mismatched candidate set.
            if (System.identityHashCode(latest.logData) != logRef || System.identityHashCode(latest.filter) != filterRef) return@launch
            val result = runCatching {
                candidateScanCount.incrementAndGet()
                diagramParticipantCandidates(latest, sourceSpec, CancellationCheck {
                    if (!isActive) throw kotlinx.coroutines.CancellationException()
                })
            }
            coroutineContext.ensureActive()
            result.fold(
                onSuccess = {
                    if (candidateGenerations[workspaceId]?.get() == generation) {
                        publishCandidates(workspaceId, DiagramCandidateState.Computed(it))
                    }
                },
                onFailure = { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    if (candidateGenerations[workspaceId]?.get() == generation) {
                        publishCandidates(
                            workspaceId,
                            DiagramCandidateState.Failed(error.message ?: "Could not inspect tags."),
                        )
                    }
                },
            )
        }
        candidateJobs[workspaceId] = job
    }

    private fun publishCandidates(workspaceId: String, state: DiagramCandidateState) {
        replaceWorkspace(workspaceId) { it.copy(candidates = state) }
        if (activeWorkspaceId == workspaceId) candidatePreview = state
    }

    /**
     * Rebuilds the preview off the composition thread, cancelling any in-flight build first.
     *
     * Follows [AppState.requestMessageComposition]'s shape deliberately: a full-file scan behind an
     * interactive control must be cancellable and must supersede rather than queue, or dragging a
     * slider on a 10M-line tab enqueues dozens of multi-second scans that all still have to run.
     */
    fun requestPreview(tabId: String, spec: SeqDiagramSpec) {
        val workspaceId = activeWorkspaceId ?: return
        val generation = previewGenerations.computeIfAbsent(workspaceId) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        previewJobs.remove(workspaceId)?.cancel()
        val previous = preview.diagramOrNull
        val initialSeedPending = workspaces.firstOrNull { it.id == workspaceId }?.initialManualSeedPending == true &&
            spec.authoringMode == com.indagium.diagram.DiagramAuthoringMode.MANUAL &&
            spec.manualDocument.interactions.isEmpty()
        if (initialSeedPending) {
            replaceWorkspace(workspaceId) { it.copy(manualSeedStatus = "Preparing interactions…") }
        }
        publishPreview(workspaceId, DiagramPreviewState.Computing(previous))
        val job = scope.launch {
            // Debounce: the dialog calls this on every keystroke/toggle, and a build is far more
            // expensive than the 180 ms a user spends finishing a click.
            delay(PREVIEW_DEBOUNCE_MS)
            coroutineContext.ensureActive()
            val tab = appState.tab(tabId)
            if (tab == null) {
                if (previewGenerations[workspaceId]?.get() == generation) {
                    publishPreview(
                        workspaceId,
                        previous?.let(DiagramPreviewState::Computed)
                            ?: DiagramPreviewState.Failed("This log is closed. Relink it to rebuild the preview."),
                    )
                }
                return@launch
            }
            val built = runCatching {
                val cancellation = CancellationCheck { if (!isActive) throw kotlinx.coroutines.CancellationException() }
                val effectiveSpec = if (initialSeedPending) {
                    seedInitialManualDocument(workspaceId, tabId, tab, spec, generation, cancellation)
                } else {
                    spec
                }
                buildPreviewDiagram(tab, effectiveSpec, cancellation)
            }
            coroutineContext.ensureActive()
            built.fold(
                onSuccess = {
                    if (previewGenerations[workspaceId]?.get() == generation) {
                        if (initialSeedPending) replaceWorkspace(workspaceId) {
                            it.copy(initialManualSeedPending = false, manualSeedStatus = null)
                        }
                        publishPreview(workspaceId, DiagramPreviewState.Computed(it))
                    }
                },
                onFailure = { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (previewGenerations[workspaceId]?.get() == generation) {
                        if (initialSeedPending) replaceWorkspace(workspaceId) {
                            it.copy(initialManualSeedPending = false, manualSeedStatus = null)
                        }
                        publishPreview(
                            workspaceId,
                            DiagramPreviewState.Failed(e.message ?: "Could not build this diagram."),
                        )
                    }
                },
            )
        }
        previewJobs[workspaceId] = job
    }

    // Extracted from requestPreview's own "initial seed" branch to keep that function's complexity
    // down; behavior (including exactly when publishPreview/replaceWorkspace fire, guarded by the
    // same generation check) is unchanged.
    private fun seedInitialManualDocument(
        workspaceId: String,
        tabId: String,
        tab: LogTab,
        spec: SeqDiagramSpec,
        generation: Long,
        cancellation: CancellationCheck,
    ): SeqDiagramSpec {
        val inferredSpec = spec.forManualInference().copy(
            authoringMode = com.indagium.diagram.DiagramAuthoringMode.INFERRED,
            manualDocument = ManualDiagramDocument(),
        )
        val inferred = buildPreviewDiagram(tab, inferredSpec, cancellation)
        // The inferred model is useful immediately. Keep it on the canvas while the second pass
        // converts it into the durable manual document; otherwise the renderer shows its empty
        // placeholder for the whole seed duration.
        if (previewGenerations[workspaceId]?.get() == generation && inferred.messages.isNotEmpty()) {
            publishPreview(workspaceId, DiagramPreviewState.Computing(inferred))
        }
        val seeded = manualDocumentFromDiagram(inferred)
        if (seeded.interactions.isEmpty()) return spec
        val seededSpec = spec.withSeededParticipants(inferred.participants).copy(
            manualDocument = seeded,
            lifelineOrder = (spec.lifelineOrder + inferred.participants.map { it.id }).distinct(),
        )
        if (previewGenerations[workspaceId]?.get() == generation) {
            replaceWorkspace(workspaceId) { current ->
                current.copy(
                    spec = seededSpec,
                    request = current.request?.copy(spec = seededSpec),
                    manualSeedEditsSinceApply = false,
                    manualSeedStatus = null,
                )
            }
            if (activeWorkspaceId == workspaceId && request?.tabId == tabId) {
                request = request?.copy(spec = seededSpec)
            }
        }
        return seededSpec
    }

    private fun buildPreviewDiagram(tab: LogTab, spec: SeqDiagramSpec, cancellation: CancellationCheck): SeqDiagram {
        previewBuildCount.incrementAndGet()
        return buildSequenceDiagram(
            tab = tab,
            spec = spec,
            resolveLabel = sourceLabelResolver(spec),
            cancellationCheck = cancellation,
            resolveTrace = sourceTraceResolver(spec),
            resolveSourceInteractions = sourceInteractionResolver(spec),
        )
    }

    private fun publishPreview(workspaceId: String, state: DiagramPreviewState) {
        replaceWorkspace(workspaceId) { it.copy(preview = state) }
        if (activeWorkspaceId == workspaceId) preview = state
    }

    private fun requiresRebuild(old: SeqDiagramSpec, new: SeqDiagramSpec): Boolean =
        old.copy(title = "", dialect = DiagramDialect.MERMAID) !=
            new.copy(title = "", dialect = DiagramDialect.MERMAID)

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
                val owner = match.site.owningType?.substringAfterLast('.')
                    ?: File(match.site.filePath).name.substringBeforeLast('.')
                "$owner#${match.site.methodName}()"
            }
        }
    }

    /** Shared range-level trace adapter used by the UI builder. The resolver owns no UI state and
     * therefore stays identical to the MCP path; cancellation is still enforced by the builder's
     * range callback boundary. */
    private fun sourceTraceResolver(spec: SeqDiagramSpec): ((List<LogEntry>) -> DiagramResolvedTrace)? {
        if (!spec.sourceEnrichment.enabled) return null
        val index = appState.sourceIndex ?: return null
        if (index.version != SOURCE_INDEX_VERSION) return null
        val engine = SourceTraceInferenceEngine(index)
        return { entries -> engine.resolve(entries, sourceSiteOverrides = spec.sourceSiteOverrides) }
    }

    /** Legacy explicit interaction adapter used only when callers do not provide a source-trace
     * resolver. Source-enabled UI builds use [sourceTraceResolver] as the single semantic owner. */
    private fun sourceInteractionResolver(spec: SeqDiagramSpec): (LogEntry) -> List<DiagramSourceInteraction> {
        if (!spec.sourceEnrichment.enabled || spec.components.isEmpty()) return { emptyList() }
        val index = appState.sourceIndex ?: return { emptyList() }
        val resolver = SourceEnrichmentResolver(index)
        val cache = BoundedSourceInteractionCache(SOURCE_CACHE_MAX_ENTRIES)
        var previousStackFrame: SourceStackFrame? = null
        return { entry ->
            val stackFrame = SOURCE_STACK_FRAME_PATTERN.matchEntire(entry.msg)?.let { match ->
                SourceStackFrame(match.groupValues[1], match.groupValues[2], match.groupValues[3])
            }
            if (stackFrame != null) {
                val callee = previousStackFrame
                previousStackFrame = stackFrame
                stackFrameInteraction(spec, callee, stackFrame)
            } else {
                previousStackFrame = null
                cache.getOrPut(sourceCacheKey(entry)) { oneHopSourceInteractions(spec, resolver, entry) }
            }
        }
    }

    private fun stackFrameInteraction(spec: SeqDiagramSpec, callee: SourceStackFrame?, caller: SourceStackFrame): List<DiagramSourceInteraction> {
        if (callee == null) return emptyList()
        val from = sourceComponentId(spec, caller.ownerType)
        val to = sourceComponentId(spec, callee.ownerType)
        if (from == null || to == null) return emptyList()
        return listOf(
            DiagramSourceInteraction(
                fromComponentId = from,
                toComponentId = to,
                label = "${callee.ownerType}.${callee.methodName} (${callee.location})",
                allowSelfCall = from == to,
            ),
        )
    }

    private fun oneHopSourceInteractions(
        spec: SeqDiagramSpec,
        resolver: SourceEnrichmentResolver,
        entry: LogEntry,
    ): List<DiagramSourceInteraction> = resolver.resolveOneHop(entry).mapNotNull { call ->
        if (call.confidence < MIN_SOURCE_CONFIDENCE) return@mapNotNull null
        val from = sourceComponentId(spec, call.sourceOwnerType)
        val to = sourceComponentId(spec, call.targetOwnerType)
        if (from == null || to == null) null else DiagramSourceInteraction(
            fromComponentId = from,
            toComponentId = to,
            label = "${call.targetOwnerType}.${call.targetMethodSignature}",
            returnLabel = (call.observedReturnLabel ?: call.declaredReturnType)?.takeIf { spec.sourceEnrichment.addReturnArrows },
        )
    }.distinct()

    private fun sourceComponentId(spec: SeqDiagramSpec, owner: String?): String? {
        val cleanOwner = owner?.trim().orEmpty()
        if (cleanOwner.isEmpty()) return null
        val normalized = cleanOwner.substringBefore('$')
        val explicit = spec.components.filter {
            it.enabled && (cleanOwner in it.sourceOwnerTypes || normalized in it.sourceOwnerTypes)
        }
        if (explicit.size == 1) return explicit.single().id
        if (explicit.size > 1) return null
        val simple = normalized.substringAfterLast('.')
        val heuristic = spec.components.filter { component ->
            component.enabled && (
                component.id == cleanOwner || component.id == normalized ||
                    component.displayName == cleanOwner || component.displayName == normalized ||
                    component.displayName.substringAfterLast('.') == simple ||
                    component.tagIds.any { tag ->
                        tag == cleanOwner || tag == normalized || tag.substringAfterLast('.') == simple
                    }
            )
        }.distinctBy { it.id }
        return heuristic.singleOrNull()?.id
    }

    private fun sourceCacheKey(entry: LogEntry): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(entry.tag.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(entry.msg.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
    }

    private fun persistableSpec(spec: SeqDiagramSpec): SeqDiagramSpec? {
        val realComponents = spec.components.filter { it.tagIds.isNotEmpty() }
        if (realComponents.isNotEmpty()) return spec.copy(components = realComponents)
        val disabled = candidatePreview.valuesOrEmpty.map { candidate ->
            com.indagium.diagram.DiagramComponent(
                id = candidate.tag,
                displayName = candidate.tag,
                tagIds = setOf(candidate.tag),
                enabled = false,
            )
        }
        return disabled.takeIf { it.isNotEmpty() }?.let { spec.copy(components = it) }
    }

    /** Old saved inferred documents remain readable, but opening one for editing creates the
     * single manual contract from its own carried model. This never regenerates against a possibly
     * different log, and a source-only legacy note remains view-only until explicit regeneration. */
    private fun editableManualSpec(spec: SeqDiagramSpec, model: SeqDiagram?): SeqDiagramSpec {
        if (spec.manualDocument.interactions.isNotEmpty()) {
            return spec.copy(authoringMode = com.indagium.diagram.DiagramAuthoringMode.MANUAL)
        }
        val carried = model ?: return spec.copy(authoringMode = com.indagium.diagram.DiagramAuthoringMode.MANUAL)
        return spec.withSeededParticipants(carried.participants).copy(
            authoringMode = com.indagium.diagram.DiagramAuthoringMode.MANUAL,
            manualDocument = manualDocumentFromDiagram(carried),
            lifelineOrder = (spec.lifelineOrder + carried.participants.map { it.id }).distinct(),
        )
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
        val persistedSpec = persistableSpec(req.spec) ?: return null
        val persistedDiagram = diagram.copy(spec = persistedSpec)
        val source = persistedDiagram.toSource(persistedSpec.dialect)
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
        val text = encodeDiagramNote(persistedSpec, source, persistedDiagram, attachment = attachment)
        lastSpecByTab[req.tabId] = persistedSpec
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
        const val EMPTY_COMPONENT_ID = "__indagium_empty_component__"
        const val INSPECTOR_MIN_WIDTH = 220f
        const val INSPECTOR_MAX_WIDTH = 520f
        const val VIEWPORT_MIN_ZOOM = .15f
        const val VIEWPORT_MAX_ZOOM = 2.5f
        const val MIN_SOURCE_CONFIDENCE = 0.7
        const val SOURCE_CACHE_MAX_ENTRIES = 256
    }
}

/**
 * A new no-selection workspace starts with an intentionally disabled placeholder component so
 * candidate discovery does not opt every tag into the diagram. Once the first inferred model is
 * available, the manual document still needs real component-backed lifelines or the authoritative
 * manual builder will discard every interaction as unmapped. Replace only that placeholder (or an
 * otherwise empty component palette) with the participants proved by the inferred model.
 */
private fun SeqDiagramSpec.withSeededParticipants(
    participants: List<com.indagium.diagram.DiagramParticipant>,
): SeqDiagramSpec {
    val retainedComponents = components.filter { it.tagIds.isNotEmpty() || it.sourceOwnerTypes.isNotEmpty() }
    val coveredTags = retainedComponents.flatMap { it.tagIds }.toSet()
    val inferredTagComponents = participants
        .filter { it.kind == com.indagium.diagram.ParticipantKind.TAG }
        .filter { participant -> (participant.tag ?: participant.id) !in coveredTags }
        .map { participant ->
            com.indagium.diagram.DiagramComponent(
                id = participant.id,
                displayName = participant.label,
                tagIds = setOf(participant.tag ?: participant.id),
                enabled = false,
            )
        }
        .distinctBy { it.id }
    return copy(components = (retainedComponents + inferredTagComponents).distinctBy { it.id })
}

/** Do not let the new-workspace opt-out placeholder hide every row from the initial inference. */
private fun SeqDiagramSpec.forManualInference(): SeqDiagramSpec =
    if (components.any { it.enabled && it.tagIds.isNotEmpty() }) this else copy(components = emptyList())

private class BoundedSourceInteractionCache(private val maxEntries: Int) {
    private val values = object : LinkedHashMap<String, List<DiagramSourceInteraction>>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<DiagramSourceInteraction>>?,
        ): Boolean = size > maxEntries
    }

    @Synchronized
    fun getOrPut(key: String, producer: () -> List<DiagramSourceInteraction>): List<DiagramSourceInteraction> =
        values[key] ?: producer().also { values[key] = it }
}

/** Every diagram note in [tab], paired with its block id — the one place that decides "is this
 *  Note actually a diagram", so the panel, the export and the MCP layer can never disagree. */
fun LogTab.diagramNotes(): List<Pair<String, com.indagium.diagram.ParsedDiagram>> =
    annotations.blocks.mapNotNull { block ->
        (block as? com.indagium.model.AnnBlock.Note)?.let { note ->
            parseDiagramNote(note.text)?.let { note.id to it }
        }
    }
