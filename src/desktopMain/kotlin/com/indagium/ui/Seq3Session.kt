package com.indagium.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.indagium.diagram3.DiagramExportMode
import com.indagium.diagram3.ParsedSeq3
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3GenerateOptions
import com.indagium.diagram3.Seq3Range
import com.indagium.diagram3.Seq3RegenReview
import com.indagium.diagram3.Seq3UndoEntry
import com.indagium.diagram3.applySeq3Command
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.diagram3.generateSeq3
import com.indagium.diagram3.matchOneMessage
import com.indagium.diagram3.parseSeq3Note
import com.indagium.diagram3.reviewSeq3Regeneration
import com.indagium.model.AnnBlock
import com.indagium.model.LogTab
import com.indagium.utils.computeLogFingerprint
import com.indagium.utils.expandSelectionThroughCollapsedBlocks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** The main content currently shown by the application. A diagram workspace deliberately lives
 *  outside [AppState.tabs]: a log tab remains a log tab, including for autosave and compare mode.
 *  Moved here from `ui/SeqDiagramCoordinator.kt` in the v3 cutover (phase 6 of docs/plans/use-the-
 *  claude-design-mcp-compiled-lighthouse.md) — the v1/v2 [ActiveSurface.Diagram] variant that used
 *  to sit alongside [Diagram3] is gone with the rest of that file. */
sealed interface ActiveSurface {
    data class Log(val tabId: String) : ActiveSurface

    data class Diagram3(val sessionId: String) : ActiveSurface
}

/** Every diagram note in [tab], paired with its block id — the one place that decides "is this
 *  Note actually a diagram", so the panel, the export and the MCP layer can never disagree.
 *  v3 counterpart of the deleted `ui.diagramNotes()` (`SeqDiagramCoordinator.kt`). */
fun LogTab.seq3DiagramNotes(): List<Pair<String, ParsedSeq3>> =
    annotations.blocks.mapNotNull { block ->
        (block as? AnnBlock.Note)?.let { note -> parseSeq3Note(note.text)?.let { note.id to it } }
    }

// ── v3 workspace state owner ────────────────────────────────────────────────────────────────
//
// Replaces SeqDiagramCoordinator for the v3 surface (docs/plans/use-the-claude-design-mcp-
// compiled-lighthouse.md) without deleting it — v1/v2 keep using that file untouched until phase 6.
// Split out of AppState the same way SeqDiagramCoordinator/AnnotationManager/TailCoordinator are
// (docs/SAAD.md §11.3): a self-contained feature with its own job lifecycle doesn't belong inlined
// into an already ~6k-line class.
//
// Three responsibilities, one per section below: (1) session lifecycle keyed off a source log tab,
// independent of that tab's own lifetime (SAAD §11.6, mirroring SeqDiagramCoordinator.
// sourceTabClosed at ui/SeqDiagramCoordinator.kt:385); (2) a debounced, conflated, cancellable
// generate+layout pipeline (SAAD §10.2's "conflated mapLatest job" — modeled on
// SeqDiagramCoordinator.requestPreview's generation-counter idiom, not literal kotlinx-coroutines-
// flow mapLatest, since neither this file nor that one is Flow-based); (3) one undo stack per
// session wrapping diagram3's applySeq3Command/undoSeq3Command, so every mutation phases 4-5 add —
// including a whole regeneration apply — is exactly one ⌘Z step (Seq3Commands.kt's own header).

/**
 * One open v3 diagram workspace. Deliberately NOT part of [AppState.tabs] — a diagram, like a v1/v2
 * [DiagramWorkspaceSession], is an independent working document that must outlive its source log
 * tab closing (SAAD §11.6).
 *
 * [confirmedBlockId] is set the first time [Seq3Session.confirm] writes a note; every later confirm
 * updates that same block instead of appending a second one, mirroring [SeqDiagramRequest.
 * editingBlockId]'s role in the v1/v2 coordinator.
 */
data class Seq3WorkspaceSession(
    val id: String,
    /** Null exactly when the source tab has been closed — see [Seq3Session.sourceTabClosed]. The
     *  cached [document] and [undoStack] are unaffected; only [Seq3Session.requestGenerate] and
     *  [Seq3Session.confirm] require this to be non-null. */
    val sourceTabId: String?,
    val document: Seq3Document,
    val range: Seq3Range = Seq3Range.VisibleView,
    val generateOptions: Seq3GenerateOptions = Seq3GenerateOptions(),
    val undoStack: List<Seq3UndoEntry> = emptyList(),
    /** True while a [Seq3Session.requestGenerate] job is in flight (post-debounce, actually
     *  scanning) for this session. */
    val generating: Boolean = false,
    val dirty: Boolean = false,
    /** Wall-clock millis of the last time [dirty] settled back to false — the canvas status bar's
     *  "Draft saved 2 min ago" (design spec §04). Null before the first settle. */
    val draftSavedAtMillis: Long? = null,
    val confirmedBlockId: String? = null,
    /** Spec §08's review sheet — non-null while a "Build review" result is on screen awaiting
     *  per-row decisions. Built by [Seq3Session.requestRegenReview] from a FRESH `generateSeq3`
     *  pass; [Seq3Document]/[range]/[generateOptions] above are never touched until
     *  [Seq3Session.applyRegenReview] routes the user's decisions through the normal
     *  [Seq3Session.applyCommand] pipeline as one undo step. */
    val pendingRegenReview: Seq3RegenReview? = null,
    /** True while a [Seq3Session.requestRegenReview] job is in flight — the sheet's "Building
     *  review…" status line. */
    val regenBuilding: Boolean = false,
    /** Set the first time [Seq3Session.confirm] saves this session into [DiagramLibraryStore] —
     *  every later confirm updates that same record instead of creating a second one. Mirrors
     *  [confirmedBlockId]'s identical role for the note itself. */
    val libraryItemId: String? = null,
    /** Null until the first [Seq3Session.confirm], which seeds it from [AppState.settings]'
     *  `diagramDefaultExportMode` (the setting only seeds a NEW note — see that setting's own
     *  tooltip, "Existing notes keep their own choice"); [Seq3Session.beginEdit] and
     *  [Seq3Session.openLibraryItem] instead seed it from the note/item's own already-written
     *  choice. Every later confirm on this session reuses this value rather than re-reading the
     *  (possibly since-changed) global setting. */
    val exportMode: DiagramExportMode? = null,
)

private const val BASE_UNTITLED_TITLE = "Untitled diagram"

/**
 * A unique default title for a brand-new session, seeded in [Seq3Session.begin] against that exact
 * log's own already-saved diagrams ([DiagramLibraryStore.forSource]) so two diagrams for the SAME
 * log never start out sharing a name — a different log is free to also start at "Untitled diagram",
 * since [existingTitles] is scoped per-source by the caller. `internal`, not `private`, purely so
 * [Seq3SessionTest] can assert the numbering directly without going through [Seq3Session.begin].
 */
internal fun defaultSeq3Title(existingTitles: Set<String>): String {
    if (BASE_UNTITLED_TITLE !in existingTitles) return BASE_UNTITLED_TITLE
    var suffix = 2
    while ("$BASE_UNTITLED_TITLE $suffix" in existingTitles) suffix++
    return "$BASE_UNTITLED_TITLE $suffix"
}

/**
 * Owns every open [Seq3WorkspaceSession]. One instance lives on [AppState] (`AppState.seq3Sessions`),
 * exactly like `AppState.seqDiagrams` owns [SeqDiagramCoordinator].
 *
 * [clock] is a constructor seam (SAAD §11.1's "every external dependency is a constructor parameter
 * with a production default"), not a hidden `System.currentTimeMillis()` call, so
 * [Seq3SessionTest] can assert draft-saved timestamps without a real 400 ms sleep per case.
 */
class Seq3Session(
    private val appState: AppState,
    private val clock: () -> Long = System::currentTimeMillis,
    // Constructor seam (SAAD §11.1: "every external dependency is a constructor parameter with a
    // production default") — mirrors SeqDiagramCoordinator's identical (tabId, libraryStore)
    // parameter for v1/v2, so a test can point the library at a temp file instead of the real
    // appDataDir() store.
    private val libraryStore: DiagramLibraryStore = DiagramLibraryStore(),
) {
    var sessions by mutableStateOf<List<Seq3WorkspaceSession>>(emptyList())
        private set
    var activeSessionId by mutableStateOf<String?>(null)
        private set

    val activeSession: Seq3WorkspaceSession? get() = activeSessionId?.let(::session)

    private fun session(id: String): Seq3WorkspaceSession? = sessions.firstOrNull { it.id == id }

    private fun replace(id: String, fn: (Seq3WorkspaceSession) -> Seq3WorkspaceSession) {
        sessions = sessions.map { if (it.id == id) fn(it) else it }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────────────────

    /**
     * Opens a new workspace for [tabId], seeded from [selectedIds] the same way
     * [SeqDiagramCoordinator.begin] seeds a v1/v2 range: a non-empty selection becomes an inclusive
     * [Seq3Range.Ids] span (widened through any collapsed fold first, so "select a collapsed block"
     * means what expanding it and selecting the same lines by hand would mean); an empty selection
     * falls back to [Seq3Range.VisibleView]. Returns null (opening nothing) when [tabId] isn't a
     * live tab.
     */
    fun begin(tabId: String, selectedIds: Set<Int> = emptySet()): String? {
        val tab = appState.tab(tabId) ?: return null
        val range = rangeFor(tab, selectedIds)
        val id = "seq3-${UUID.randomUUID()}"
        // Unique against THIS exact log's own library entries (not the whole library) — two
        // different logs are free to each have their own "Untitled diagram".
        val title = defaultSeq3Title(libraryStore.forSource(sourceIdentity(tab)).map { it.title }.toSet())
        val session = Seq3WorkspaceSession(
            id = id,
            sourceTabId = tabId,
            document = Seq3Document(title = title, sourceFile = File(tab.filename).name, range = range),
            range = range,
            generateOptions = Seq3GenerateOptions(sourceFile = File(tab.filename).name),
        )
        sessions = sessions + session
        activeSessionId = id
        appState.activeSurface = ActiveSurface.Diagram3(id)
        requestGenerate(id)
        return id
    }

    /**
     * Opens the workspace on an existing diagram note, repopulated from the document its header
     * carries — the v3 counterpart of [SeqDiagramCoordinator.beginEdit]. Regenerating remains an
     * explicit follow-up ([requestGenerate]), never automatic here, so a note opened without its
     * source log (e.g. Case Library "notes only") still shows its saved document rather than
     * nothing. Returns null when [blockId] isn't a v3 diagram note.
     */
    fun beginEdit(tabId: String, blockId: String): String? {
        val tab = appState.tab(tabId) ?: return null
        val block = tab.annotations.blocks.firstOrNull { it.id == blockId } as? AnnBlock.Note ?: return null
        val parsed = parseSeq3Note(block.text) ?: return null
        val id = "seq3-${UUID.randomUUID()}"
        val session = Seq3WorkspaceSession(
            id = id,
            sourceTabId = tabId,
            document = parsed.document,
            range = parsed.document.range,
            generateOptions = Seq3GenerateOptions(sourceFile = parsed.document.sourceFile ?: File(tab.filename).name),
            confirmedBlockId = blockId,
            exportMode = parsed.exportMode,
        )
        sessions = sessions + session
        activeSessionId = id
        appState.activeSurface = ActiveSurface.Diagram3(id)
        return id
    }

    /** Selects an existing workspace without touching [AppState.tabs] — the v3 counterpart of
     *  [SeqDiagramCoordinator.activateWorkspace]. */
    fun activate(id: String): Boolean {
        if (session(id) == null) return false
        activeSessionId = id
        appState.activeSurface = ActiveSurface.Diagram3(id)
        return true
    }

    /** Closes [id] outright. Phase 3 has no dirty-close confirmation UI yet (that's a phase 4/5
     *  concern, mirroring [SeqDiagramCoordinator.requestCloseWorkspace]'s three-way prompt) — a
     *  caller that wants to warn on [Seq3WorkspaceSession.dirty] checks it before calling this. */
    fun close(id: String) {
        generateJobs.remove(id)?.cancel()
        draftJobs.remove(id)?.cancel()
        revertJobs.remove(id)?.cancel()
        generations.remove(id)
        sessions = sessions.filterNot { it.id == id }
        if (activeSessionId == id) {
            val next = sessions.lastOrNull()?.id
            activeSessionId = next
            appState.activeSurface = next?.let(ActiveSurface::Diagram3) ?: appState.activeTab()?.id?.let(ActiveSurface::Log)
        }
    }

    /** Rebuilds [sessions] into [orderedIds]' order — the v3 counterpart of
     *  [SeqDiagramCoordinator.reorderWorkspaces], for `ui.TabBar`'s diagram tab strip. Unknown ids
     *  in [orderedIds] are ignored (a stale drag computed against an order that has since changed
     *  must never invent a session), and any live session left out of [orderedIds] is appended
     *  rather than dropped, so a partial/stale order can never silently close a tab. Neither
     *  [activeSessionId] nor [AppState.activeSurface] is touched — reordering never changes what's
     *  selected. */
    fun reorderSessions(orderedIds: List<String>) {
        val byId = sessions.associateBy { it.id }
        val reordered = orderedIds.mapNotNull(byId::get)
        val omitted = sessions.filter { it.id !in orderedIds }
        sessions = reordered + omitted
    }

    /**
     * Source logs are allowed to close independently of an open v3 workspace — SAAD §11.6 ("closing
     * a source log converts dependent workspaces to offline/view-only state rather than discarding
     * their cached evidence"), the exact contract [SeqDiagramCoordinator.sourceTabClosed]
     * (`ui/SeqDiagramCoordinator.kt:385`) already implements for v1/v2. The generated [Seq3Document]
     * and the whole [Seq3WorkspaceSession.undoStack] are left untouched; only [sourceTabId] clears,
     * which in turn makes [requestGenerate] and [confirm] no-ops until [relink].
     */
    fun sourceTabClosed(tabId: String) {
        sessions.filter { it.sourceTabId == tabId }.forEach { s ->
            generateJobs.remove(s.id)?.cancel()
            replace(s.id) { it.copy(sourceTabId = null, generating = false) }
        }
    }

    /** Relinks an offline session (its source tab closed, see [sourceTabClosed]) to an already-open
     *  tab. Regenerating remains an explicit follow-up call — this alone does not rebuild. */
    fun relink(id: String, tabId: String): Boolean {
        if (appState.tab(tabId) == null) return false
        if (session(id) == null) return false
        replace(id) { it.copy(sourceTabId = tabId) }
        return true
    }

    private fun rangeFor(tab: LogTab, selectedIds: Set<Int>): Seq3Range {
        val effective = expandSelectionThroughCollapsedBlocks(tab, selectedIds)
        return if (effective.isNotEmpty()) Seq3Range.Ids(effective.min(), effective.max(), effective) else Seq3Range.VisibleView
    }

    // ── Generate pipeline: debounced, conflated, cancellable (SAAD §10.2) ──────────────────────
    //
    // Follows SeqDiagramCoordinator.requestPreview's own shape: a generation counter per session
    // supersedes rather than queues, so dragging a range boundary on a huge log doesn't enqueue a
    // dozen multi-second scans that all still have to run to completion. Debounced separately per
    // session (own Job in generateJobs), so regenerating workspace A never cancels workspace B's
    // in-flight build.

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generateJobs = ConcurrentHashMap<String, Job>()
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    /** Test-visible instrumentation for the metadata-edit performance contract — mirrors
     *  [SeqDiagramCoordinator.previewBuildCount]'s identical role for v1/v2. Counts actual calls
     *  into `generateSeq3`, across every session, so [Seq3SessionTest] can assert a metadata-only
     *  edit ([updateTitle]) never advances it. */
    internal val generateRunCount = AtomicLong()

    /**
     * (Re)builds [id]'s [Seq3WorkspaceSession.document] from its current [Seq3WorkspaceSession.
     * range]/[Seq3WorkspaceSession.generateOptions]. The ONLY entry point into diagram3's
     * `generateSeq3` — [updateTitle] and every [applyCommand] mutation deliberately never call this,
     * which is what makes "a metadata-only edit must never trigger a rebuild" true by construction
     * rather than by a special case here.
     */
    fun requestGenerate(id: String) {
        val current = session(id) ?: return
        val tabId = current.sourceTabId ?: return
        val generation = generations.computeIfAbsent(id) { AtomicLong() }.incrementAndGet()
        generateJobs.remove(id)?.cancel()
        replace(id) { it.copy(generating = true) }
        val job = scope.launch {
            // Debounce: a range drag or a rapid sequence of edits fires this repeatedly, and a full
            // generate+layout pass is far more expensive than the ~180ms a user spends finishing one.
            delay(GENERATE_DEBOUNCE_MS)
            coroutineContext.ensureActive()
            val tab = appState.tab(tabId)
            if (tab == null) {
                if (generations[id]?.get() == generation) replace(id) { it.copy(generating = false) }
                return@launch
            }
            val cancellationCheck: () -> Unit = { if (!isActive) throw CancellationException("seq3 generate superseded") }
            generateRunCount.incrementAndGet()
            val result = runCatching {
                generateSeq3(tab.logData, current.range, current.generateOptions, cancellationCheck, appState.sourceIndex)
            }
            coroutineContext.ensureActive()
            result.onSuccess { fresh -> publishGenerated(id, generation, fresh) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (generations[id]?.get() == generation) replace(id) { it.copy(generating = false) }
                }
        }
        generateJobs[id] = job
    }

    // A stale (superseded) generation must never publish — see requestGenerate's own comment.
    // Split out so requestGenerate itself stays under detekt's function-length/complexity budget.
    private fun publishGenerated(id: String, generation: Long, fresh: Seq3Document) {
        if (generations[id]?.get() != generation) return
        replace(id) { current ->
            // A freshly generated document's title is empty (Seq3Generator never invents one); keep
            // whatever the user already typed rather than blanking it on every regenerate.
            current.copy(document = fresh.copy(title = current.document.title.ifBlank { fresh.title }), generating = false)
        }
    }

    /** Changes [Seq3WorkspaceSession.range] and re-triggers [requestGenerate] in one call — the
     *  range picker's (phase 4) only way to reach the generator. */
    fun updateRangeAndRegenerate(id: String, range: Seq3Range) {
        if (session(id) == null) return
        replace(id) { it.copy(range = range) }
        requestGenerate(id)
    }

    /**
     * Sets the scope WITHOUT regenerating — the regenerate sheet's own scope picker (spec §08: the
     * scope controls "are inputs to THIS action", i.e. to the explicit "Build review" press that
     * follows, not a trigger of their own). Deliberately not [updateRangeAndRegenerate], and
     * deliberately not [markDirty]: choosing a scope is not a document edit and must not start the
     * draft-saved debounce or push an undo step.
     */
    fun updateScope(id: String, range: Seq3Range) {
        if (session(id) == null) return
        replace(id) { it.copy(range = range) }
    }

    /** Same contract as [updateScope] for the sheet's `Seq3GenerateOptions` toggles (same-thread
     *  handoffs, correlation tokens): an input to the next "Build review", never a rebuild itself. */
    fun updateGenerateOptions(id: String, transform: (Seq3GenerateOptions) -> Seq3GenerateOptions) {
        if (session(id) == null) return
        replace(id) { it.copy(generateOptions = transform(it.generateOptions)) }
    }

    /**
     * Title is pure metadata — [Seq3GenerateOptions.title] only ever seeds a BRAND NEW document
     * (see [publishGenerated]'s `ifBlank` above), it is never read back out of one — so this
     * deliberately never calls [requestGenerate]. Still marks the session dirty and starts the
     * draft-saved (now auto-save, see [markDirty]) debounce like every other edit.
     *
     * A [title] that's blank after trimming is silently ignored, keeping whatever the session
     * already had — the title bar always needs something to show (item 6c), so this is the one
     * place that guarantee is enforced, rather than duplicating the check in every caller
     * ([Seq3TitleField]'s commit included).
     */
    fun updateTitle(id: String, title: String) {
        if (session(id) == null) return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        replace(id) { it.copy(document = it.document.copy(title = trimmed)) }
        markDirty(id)
    }

    // ── Undo stack: every mutation routes through applySeq3Command ──────────────────────────────
    //
    // Seq3Commands.kt's own header explains why a whole-document snapshot per step makes "undo is
    // one step, including a whole regeneration apply" trivially true — this class only has to keep
    // a stack of those snapshots per session and never has to know what any individual command did.

    /** Applies [command] and, only if it actually changed anything, pushes the resulting
     *  [Seq3UndoEntry] and marks the session dirty. Phases 4-5's every editing verb (set target,
     *  merge, group, hide, regenerate-apply, …) must route through this, never mutate
     *  [Seq3WorkspaceSession.document] directly, or ⌘Z silently stops covering that verb. */
    fun applyCommand(id: String, command: Seq3Command): Boolean {
        val current = session(id) ?: return false
        val result = applySeq3Command(current.document, command)
        val undo = result.undo
        if (!result.applied || undo == null) return false
        replace(id) { it.copy(document = result.document, undoStack = (it.undoStack + undo).takeLast(MAX_UNDO_DEPTH)) }
        markDirty(id)
        return true
    }

    fun canUndo(id: String): Boolean = session(id)?.undoStack?.isNotEmpty() == true

    /** Pops and restores the most recent [Seq3UndoEntry]. Silently does nothing on an empty stack —
     *  matches [SeqDiagramCoordinator.revertManualSeed]'s own "already safely no-ops" contract. */
    fun undo(id: String): Boolean {
        val current = session(id) ?: return false
        val entry = current.undoStack.lastOrNull() ?: return false
        replace(id) { it.copy(document = entry.before, undoStack = it.undoStack.dropLast(1)) }
        markDirty(id)
        return true
    }

    /** ⌘Z while a v3 surface is active — `ui/App.kt`'s global key handler calls this directly,
     *  mirroring the v1/v2 `state.seqDiagrams.revertManualSeed()` call site at `ui/App.kt:2294`. */
    fun undoActive(): Boolean = activeSessionId?.let(::undo) ?: false

    // ── Dirty flag / draft-saved timestamp / auto-save ──────────────────────────────────────────
    //
    // This debounce IS the auto-save (design spec's "fully automatic — no Save button" decision):
    // 400ms after an edit settles, [markDirty] calls [confirm], which writes the document into a
    // note (going through AppState's normal upAnn autosave path for that note block, same as any
    // other annotation edit) and updates DiagramLibraryStore. [markDirty] itself is called ONLY from
    // [applyCommand], [undo] and [updateTitle] — never from generation completing — so opening or
    // regenerating a diagram nobody has touched writes nothing; only a real edit ever reaches
    // [confirm] this way. [confirm] already no-ops harmlessly (returns null, dirty stays true) when
    // `current.sourceTabId == null` or the document has no lifelines yet, so the `ensureActive()`
    // check below is the only guard this debounce needs. Same 400ms debounce constant AppState's own
    // autosave scheduler uses, reused here so a v3 workspace settles on the same cadence the rest of
    // the app already trains a user to expect.

    private val draftJobs = ConcurrentHashMap<String, Job>()

    /** Marks [id] dirty and (re)starts the auto-save debounce — see this section's own header for
     *  why calling [confirm] here on settle is a real save, not merely a cosmetic timestamp flip. A
     *  second edit before the debounce fires cancels and restarts it, same conflation idiom
     *  [requestGenerate] uses for its own debounce. */
    private fun markDirty(id: String) {
        replace(id) { it.copy(dirty = true) }
        draftJobs.remove(id)?.cancel()
        draftJobs[id] = scope.launch {
            delay(DRAFT_SAVE_DEBOUNCE_MS)
            coroutineContext.ensureActive()
            confirm(id)
        }
    }

    // ── Confirm: write the document into a note ─────────────────────────────────────────────────

    /**
     * Writes [id]'s current document into its source tab's notes as an ordinary [com.indagium.
     * model.AnnBlock.Note] — [encodeSeq3Note] plus [AppState.addNoteBlock], the v3 equivalent of
     * [SeqDiagramCoordinator.confirm] (`ui/SeqDiagramCoordinator.kt:1304`). A later confirm on the
     * SAME session updates that same block ([Seq3WorkspaceSession.confirmedBlockId]) instead of
     * appending a second note. Returns null (writing nothing) when the source tab is closed
     * (matches v1/v2: confirming requires a live tab) or the document has no lifelines yet.
     *
     * Also keeps [DiagramLibraryStore] in sync ([Seq3WorkspaceSession.libraryItemId]): unlike v1/v2,
     * v3's own workspace has no separate "save draft"/"attach" affordances (the design spec never
     * mentions a library), so this is the one place a confirmed diagram becomes reachable from
     * AnnotationPanel's "Diagram library" Notes-column section for quick re-opening.
     */
    fun confirm(id: String): String? {
        val current = session(id) ?: return null
        val tabId = current.sourceTabId ?: return null
        if (current.document.lifelines.isEmpty()) return null
        // Sticky per-session: seeded from the global default only the FIRST time (a brand-new
        // session's exportMode starts null); every later confirm on this session — including one
        // reached via beginEdit/openLibraryItem, which seed it from the note/item's own already-
        // written choice — reuses this value rather than re-reading a since-changed setting.
        val exportMode = current.exportMode ?: appState.settings.diagramDefaultExportMode
        val text = encodeSeq3Note(current.document, Seq3Dialect.MERMAID, exportMode = exportMode)
        val blockId = current.confirmedBlockId?.also { appState.updateBlock(tabId, it, text) }
            ?: appState.addNoteBlock(tabId, text)
        if (blockId != null) {
            draftJobs.remove(id)?.cancel()
            val libraryItemId = appState.tab(tabId)?.let { tab -> saveToLibrary(current, tab, text) }
            replace(id) {
                it.copy(
                    confirmedBlockId = blockId,
                    dirty = false,
                    draftSavedAtMillis = clock(),
                    libraryItemId = libraryItemId ?: it.libraryItemId,
                    exportMode = exportMode,
                )
            }
        }
        return blockId
    }

    private fun saveToLibrary(current: Seq3WorkspaceSession, tab: LogTab, encodedNote: String): String? {
        val title = current.document.title.ifBlank { "Untitled diagram" }
        val snapshot = DiagramLibrarySnapshot(encodedNote)
        val saved = if (current.libraryItemId == null) {
            libraryStore.create(title, "", sourceIdentity(tab), snapshot)
        } else {
            libraryStore.update(current.libraryItemId) { item ->
                item.copy(title = title, source = sourceIdentity(tab), snapshot = snapshot, updatedAt = System.currentTimeMillis())
            }
        }
        libraryRevision++
        return saved?.id ?: current.libraryItemId
    }

    // ── Diagram library (ui.DiagramLibraryStore) ────────────────────────────────────────────────

    // DiagramLibraryStore is a plain disk-backed store; this bridges its mutations into Compose so
    // AnnotationPanel's "Diagram library" section immediately reflects a confirm/delete — same
    // rationale as SeqDiagramCoordinator's identically-named field for v1/v2.
    private var libraryRevision by mutableStateOf(0)

    private fun sourceIdentity(tab: LogTab): DiagramSourceIdentity =
        DiagramSourceIdentity(sourcePath = tab.sourcePath ?: tab.filename, contentFingerprint = computeLogFingerprint(tab.logData))

    /** Returns only diagrams created from this exact log identity — the v3 counterpart of
     *  [SeqDiagramCoordinator.libraryForTab]. The fingerprint makes this deliberately stricter than
     *  a filename/path check: two revisions of the same log path must not share a library section. */
    fun libraryForTab(tab: LogTab): List<DiagramLibraryItem> {
        @Suppress("UNUSED_VARIABLE")
        val observedRevision = libraryRevision
        return libraryStore.forSource(sourceIdentity(tab))
    }

    /**
     * Opens a saved diagram from its cached codec snapshot into a fresh workspace — the v3
     * counterpart of [SeqDiagramCoordinator.openLibraryItem]. Supplying a currently open [tabId]
     * makes the session regenerable; omitting it (or naming a tab that isn't open) still opens a
     * fully viewable session from the cached document, same as v1/v2's offline contract.
     */
    fun openLibraryItem(id: String, tabId: String? = null): Boolean {
        val item = libraryStore.markOpened(id) ?: return false
        libraryRevision++
        val parsed = item.parsed ?: return false
        val usableTabId = tabId?.takeIf { appState.tab(it) != null }
        val sessionId = "seq3-${UUID.randomUUID()}"
        val session = Seq3WorkspaceSession(
            id = sessionId,
            sourceTabId = usableTabId,
            document = parsed.document,
            range = parsed.document.range,
            generateOptions = Seq3GenerateOptions(sourceFile = parsed.document.sourceFile),
            libraryItemId = item.id,
            exportMode = parsed.exportMode,
        )
        sessions = sessions + session
        activeSessionId = sessionId
        appState.activeSurface = ActiveSurface.Diagram3(sessionId)
        return true
    }

    /** Deletes a saved diagram, closing any open session it backs (matches
     *  [SeqDiagramCoordinator.deleteLibraryItem]'s "the workspace can't outlive its own record"). */
    fun deleteLibraryItem(id: String): Boolean {
        sessions.filter { it.libraryItemId == id }.forEach { close(it.id) }
        return libraryStore.delete(id).also { deleted -> if (deleted) libraryRevision++ }
    }

    // ── Regenerate: a reviewed proposal, never a wholesale replace (spec §08) ──────────────────
    //
    // The scope (selection/whole view/time range) and the trace/handoff toggles the sheet exposes
    // are "inputs to this action, not permanent panel furniture" (spec's own words) — [range] and
    // [Seq3WorkspaceSession.generateOptions] above are deliberately never overwritten by any
    // function here. [applyRegenReview] is the one path back into [applyCommand], which is what
    // makes "Apply is one undoable transaction, not 15" (spec §08) true for this feature exactly
    // the same way it is for every other v3 mutation (Seq3Commands.kt's own header).

    private val regenJobs = ConcurrentHashMap<String, Job>()

    /**
     * Builds [id]'s [Seq3WorkspaceSession.pendingRegenReview] from a FRESH `generateSeq3` pass over
     * [range]/[options] diffed against the session's CURRENT document (`ui.Seq3RegenerateSheet`'s
     * own "Build review" button). Explicit and one-shot — unlike [requestGenerate] this has no
     * debounce; a second call (a second click) simply supersedes the first, same conflation idiom.
     */
    fun requestRegenReview(id: String, range: Seq3Range, options: Seq3GenerateOptions) {
        val current = session(id) ?: return
        val tabId = current.sourceTabId ?: return
        regenJobs.remove(id)?.cancel()
        replace(id) { it.copy(regenBuilding = true) }
        val job = scope.launch {
            val tab = appState.tab(tabId)
            if (tab == null) {
                replace(id) { it.copy(regenBuilding = false) }
                return@launch
            }
            val cancellationCheck: () -> Unit = { if (!isActive) throw CancellationException("seq3 regen review superseded") }
            generateRunCount.incrementAndGet()
            val result = runCatching { generateSeq3(tab.logData, range, options, cancellationCheck, appState.sourceIndex) }
            coroutineContext.ensureActive()
            result.onSuccess { fresh ->
                val latest = session(id) ?: return@onSuccess
                replace(id) { it.copy(pendingRegenReview = reviewSeq3Regeneration(latest.document, fresh), regenBuilding = false) }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                replace(id) { it.copy(regenBuilding = false) }
            }
        }
        regenJobs[id] = job
    }

    /** Every per-row decision / accept-all / reject-all / unlock (spec §08) routes through here so
     *  `ui.Seq3RegenerateSheet` never mutates [Seq3WorkspaceSession.pendingRegenReview] directly —
     *  [transform] is one of `diagram3.Seq3Regeneration`'s own pure row-transition functions
     *  (`withSeq3RegenDecision`, `acceptAllSeq3Regen`, …). A no-op when there is no pending review. */
    fun updateRegenReview(id: String, transform: (Seq3RegenReview) -> Seq3RegenReview) {
        val review = session(id)?.pendingRegenReview ?: return
        replace(id) { it.copy(pendingRegenReview = transform(review)) }
    }

    /** The sheet's "Cancel" (spec §08): discards the pending review — and any still-building
     *  request — without touching [Seq3WorkspaceSession.document]. */
    fun cancelRegenReview(id: String) {
        regenJobs.remove(id)?.cancel()
        replace(id) { it.copy(pendingRegenReview = null, regenBuilding = false) }
    }

    /**
     * "Apply N changes" (spec §08). Routes through [applyCommand] — the SAME one-undo-step path
     * every other v3 mutation uses — via [Seq3Command.ApplyRegeneration], then clears the now-
     * consumed review. Returns false, leaving [Seq3WorkspaceSession.pendingRegenReview] in place,
     * when there is nothing pending or [applyCommand] itself reports no change (matching
     * [applyCommand]'s own "unapplied is always a safe no-op" contract).
     */
    fun applyRegenReview(id: String): Boolean {
        val review = session(id)?.pendingRegenReview ?: return false
        val applied = applyCommand(id, Seq3Command.ApplyRegeneration(review))
        if (applied) replace(id) { it.copy(pendingRegenReview = null) }
        return applied
    }

    // ── Revert one message to its freshly regenerated counterpart (item 15) ────────────────────
    //
    // No existing primitive is message-scoped — [Seq3RegenReview]'s own per-row decisions all act
    // across a WHOLE pending review built by [requestRegenReview]. This runs its own one-shot,
    // fire-and-forget `generateSeq3` pass (same shape as [requestRegenReview]) over the session's
    // CURRENT [Seq3WorkspaceSession.range]/[Seq3WorkspaceSession.generateOptions], finds [messageId]'s
    // regenerated counterpart via [matchOneMessage] — the promoted, single-message sibling of the
    // whole-document matching [requestRegenReview] uses, so a one-message revert and a full
    // regeneration review can never silently disagree on what "the same message" means — and applies
    // it through [Seq3Command.ReplaceMessage]: one [applyCommand] call, one undo step, same discipline
    // as every other v3 mutation.

    private val revertJobs = ConcurrentHashMap<String, Job>()

    /**
     * "Revert to generated" (spec: undo a hand-edit back to what the engine would produce today) for
     * the ONE row [messageId] inside session [id]. A safe no-op — nothing errors, nothing changes —
     * when the source tab is closed, [messageId] no longer exists, or nothing in the fresh pass
     * matches it (no shared evidence and no unique template match, [matchOneMessage]'s own contract).
     */
    fun revertMessage(id: String, messageId: String) {
        val current = session(id) ?: return
        val tabId = current.sourceTabId ?: return
        if (current.document.messages.none { it.id == messageId }) return
        revertJobs.remove(id)?.cancel()
        val job = scope.launch {
            val tab = appState.tab(tabId) ?: return@launch
            val cancellationCheck: () -> Unit = { if (!isActive) throw CancellationException("seq3 revert superseded") }
            generateRunCount.incrementAndGet()
            val result = runCatching {
                generateSeq3(tab.logData, current.range, current.generateOptions, cancellationCheck, appState.sourceIndex)
            }
            coroutineContext.ensureActive()
            result.onSuccess { fresh ->
                val latestMessage = session(id)?.document?.messages?.firstOrNull { it.id == messageId } ?: return@onSuccess
                val matched = matchOneMessage(latestMessage, fresh.messages) ?: return@onSuccess
                applyCommand(id, Seq3Command.ReplaceMessage(messageId, matched))
            }.onFailure { e -> if (e is CancellationException) throw e }
        }
        revertJobs[id] = job
    }

    // ── Status-bar support ──────────────────────────────────────────────────────────────────────

    /**
     * Rough "N scanned" figure for the canvas status bar (design spec §04: "45 shown · 748 scanned
     * · 4 lifelines · 31 hidden"). Deliberately not `Seq3Generator`'s own (private) range resolver —
     * this only ever needs a COUNT, and [Seq3Range.Time]'s exact carry-forward-timestamp rule isn't
     * worth duplicating for a status-bar estimate, so that one case falls back to the tab's full
     * size rather than re-implementing the generator's parsing.
     */
    fun scannedEntryCount(id: String): Int {
        val current = session(id) ?: return 0
        val tab = current.sourceTabId?.let(appState::tab) ?: return 0
        return when (val range = current.range) {
            is Seq3Range.VisibleView -> tab.logData.size
            is Seq3Range.Ids -> {
                val lo = minOf(range.from, range.to)
                val hi = maxOf(range.from, range.to)
                tab.logData.count { it.id in lo..hi }
            }
            is Seq3Range.Time -> tab.logData.size
        }
    }

    private companion object {
        const val GENERATE_DEBOUNCE_MS = 180L
        const val DRAFT_SAVE_DEBOUNCE_MS = 400L
        const val MAX_UNDO_DEPTH = 50
    }
}
