package com.indagium.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.indagium.diagram3.DiagramExportMode
import com.indagium.diagram3.ParsedSeq3
import com.indagium.diagram3.Seq3AttachmentMetadata
import com.indagium.diagram3.Seq3AttachmentMode
import com.indagium.diagram3.Seq3Authoring
import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3GenerateOptions
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3NoteSeed
import com.indagium.diagram3.Seq3Range
import com.indagium.diagram3.Seq3RegenReview
import com.indagium.diagram3.Seq3UndoEntry
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.applySeq3Command
import com.indagium.diagram3.applySeq3NoteSeeds
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
    /** Wall-clock millis of the last time [dirty] settled back to false. Null before the first
     *  settle; retained for save bookkeeping even though the canvas no longer renders a save
     *  status label. */
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
    /** Dialect used by the explicit note action. Changing it never mutates the document or writes
     *  an annotation by itself. */
    val dialect: Seq3Dialect = Seq3Dialect.MERMAID,
    /** Notes-panel prose to re-anchor onto this session's document every time [Seq3Session.
     *  publishGenerated] runs — set once by [Seq3Session.beginFromNotes], empty for every other
     *  session (an ordinary [Seq3Session.begin] or [Seq3Session.beginEdit]). Two production paths
     *  reach [publishGenerated] with a non-empty [noteSeeds]: the initial generate
     *  [Seq3Session.beginFromNotes] itself kicks off, and a later range-change regenerate
     *  ([Seq3Session.updateRangeAndRegenerate]) on that same session. The workspace's own
     *  "Regenerate" button ([Seq3Session.requestRegenReview]/[Seq3Session.applyRegenReview]) is a
     *  DIFFERENT path — it never calls [publishGenerated] — so it never reads [noteSeeds] at all;
     *  these notes simply ride along on [Seq3WorkspaceSession.document] itself there, the same way
     *  any other note or fragment survives that reviewed-diff regeneration. See
     *  [com.indagium.diagram3.applySeq3NoteSeeds]'s own doc for the anchoring/idempotency rules.
     *  Deliberately runtime-only (never encoded by [com.indagium.diagram3.Seq3Codec], never part of
     *  [Seq3WorkspaceSession.document] itself): a session restored after a restart (via
     *  [Seq3Session.openLibraryItem]) keeps its notes in the saved document but loses the ability to
     *  re-anchor them to a fresh generate — exactly like any hand-added note today. */
    val noteSeeds: List<Seq3NoteSeed> = emptyList(),
)

private const val BASE_UNTITLED_TITLE = "Untitled diagram"

/** Restores generated authoring only when every user-editable value of a message is back at the
 *  runtime baseline. Visibility is intentionally ignored because it is an orthogonal display flag:
 *  hiding/showing a message must not make a previously restored row look edited. */
private fun restoreGeneratedAuthoring(document: Seq3Document, baseline: Seq3Document?): Seq3Document {
    if (baseline == null) return document
    val baselineById = baseline.messages.associateBy { it.id }
    return document.copy(
        messages = document.messages.map { message ->
            val generated = baselineById[message.id] ?: return@map message

            fun comparable(value: Seq3Message) = value.copy(
                authoring = Seq3Authoring.AUTO,
                visibility = Seq3Visibility.VISIBLE,
                // The row editor recreates captures as AUTHOR captures. Compare the stable
                // capture names, not their provenance, when deciding whether the typed pattern
                // is back at the generated value.
                match = value.match.copy(
                    captures = value.match.captures.map { it.copy(source = Seq3CaptureSource.AUTHOR) },
                ),
            )
            val comparable = comparable(message)
            val generatedComparable = comparable(generated)
            if (comparable == generatedComparable) message.copy(authoring = generated.authoring) else message
        },
    )
}

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

    /**
     * View state is kept with the open workspace rather than with its Compose composition. The
     * active-surface switch removes the workspace from composition, so a `remember(sessionId)`
     * inside [Seq3Workspace] would lose zoom and the other view preferences every time the user
     * visited another tab. Most of the state here is still ephemeral: closing the workspace drops
     * it from this map. The exception (Wave 2.5) is the queue panel's own section open/expanded
     * flags and heights plus the panel width — those survive a full app restart too, via
     * `seq3ViewToken()`/`restoreSeq3ViewToken()` (AutosaveCodec.kt), which read/write them back
     * into whichever [Seq3ViewState] this map hands out for a session's id — see the EXCEPTION
     * note on [Seq3ViewState] itself for which fields and why.
     */
    private val workspaceViewStates = ConcurrentHashMap<String, Seq3ViewState>()

    /** Runtime-only generated snapshot used to clear a stale EDITED badge when a row is changed
     *  and later restored to its original generated values. It is deliberately not persisted as a
     *  second copy of the diagram note. */
    private val generatedBaselines = ConcurrentHashMap<String, Seq3Document>()

    internal fun viewState(id: String): Seq3ViewState? {
        if (session(id) == null) return null
        return workspaceViewStates.computeIfAbsent(id) { Seq3ViewState() }
    }

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
        return begin(tab, rangeFor(tab, selectedIds), emptyList())
    }

    /**
     * Opens a new workspace over exactly the log lines curated into [tabId]'s Notes document —
     * the Diagram library's "From notes" action ([AnnotationPanel]'s `+ diagram` menu). Range is
     * the EXACT noted lines ([Seq3Range.Ids.selectedIds]), never the span between them: a curated
     * set can legitimately skip long stretches the user never annotated, and drawing those in would
     * defeat the point of generating from curation instead of a fresh selection. Each block's prose
     * rides along as a [Seq3WorkspaceSession.noteSeeds] entry, applied to the generated document by
     * [publishGenerated]. Returns null when [tabId] isn't live tab, or when [seq3NotesSelection]
     * finds no usable noted lines at all (nothing to draw) — the panel is expected to have already
     * disabled the menu row in that case, but this stays the authoritative guard.
     */
    fun beginFromNotes(tabId: String): String? {
        val tab = appState.tab(tabId) ?: return null
        val selection = seq3NotesSelection(tab)
        if (selection.entryIds.isEmpty()) return null
        val range = Seq3Range.Ids(selection.entryIds.min(), selection.entryIds.max(), selection.entryIds)
        return begin(tab, range, selection.seeds)
    }

    // Shared body for [begin] and [beginFromNotes] — everything about opening a fresh workspace
    // (title, library scoping, activeSurface, kicking off the first generate) is identical between
    // the two; only the range and the notes-derived seeds differ.
    private fun begin(tab: LogTab, range: Seq3Range, seeds: List<Seq3NoteSeed>): String {
        val id = "seq3-${UUID.randomUUID()}"
        // Unique against THIS exact log's own library entries (not the whole library) — two
        // different logs are free to each have their own "Untitled diagram".
        val title = defaultSeq3Title(libraryStore.forSource(sourceIdentity(tab)).map { it.title }.toSet())
        val session = Seq3WorkspaceSession(
            id = id,
            sourceTabId = tab.id,
            // A new document starts with no theme override (null = follow the app theme); the
            // theme is chosen per diagram from the workspace title bar's theme picker. There is
            // no Settings-level default for this — an existing document opened by
            // beginEdit/openLibraryItem below likewise keeps whatever theme it was saved with.
            document = Seq3Document(
                title = title,
                sourceFile = File(tab.filename).name,
                range = range,
            ),
            range = range,
            generateOptions = Seq3GenerateOptions(sourceFile = File(tab.filename).name),
            noteSeeds = seeds,
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
        val attachmentLibraryId = parsed.attachment?.diagramId
        val isSnapshot = parsed.attachment?.mode == Seq3AttachmentMode.SNAPSHOT
        sessions.firstOrNull { existing ->
            existing.sourceTabId == tabId &&
                (!isSnapshot || existing.document == parsed.document) &&
                (existing.confirmedBlockId == blockId ||
                    (attachmentLibraryId != null && existing.libraryItemId == attachmentLibraryId))
        }?.let { existing ->
            activate(existing.id)
            return existing.id
        }
        val id = "seq3-${UUID.randomUUID()}"
        val session = Seq3WorkspaceSession(
            id = id,
            sourceTabId = tabId,
            document = parsed.document,
            range = parsed.document.range,
            generateOptions = Seq3GenerateOptions(sourceFile = parsed.document.sourceFile ?: File(tab.filename).name),
            confirmedBlockId = blockId,
            // Only live links retain the library id. Snapshot notes remain independent from the
            // library item that produced them; assigning their id here would make later edits of
            // the snapshot update the library record and turn a static attachment into a live one.
            libraryItemId = parsed.attachment
                ?.takeIf { it.mode == Seq3AttachmentMode.LINKED }
                ?.diagramId,
            exportMode = parsed.exportMode,
            dialect = parsed.dialect,
        )
        sessions = sessions + session
        generatedBaselines[id] = parsed.document
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
        revertJobs.remove(id)?.cancel()
        generations.remove(id)
        workspaceViewStates.remove(id)
        generatedBaselines.remove(id)
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
            // whatever the user already typed rather than blanking it on every regenerate. Notes-
            // panel seeds (beginFromNotes) are applied here, before the baseline snapshot below and
            // everything downstream of `document` — the library encode, the live-linked note sync,
            // a later confirm — so they all already see the seeded notes with no further change.
            // `fresh` itself never carries notes (`generateSeq3` doesn't produce any), so hand
            // `applySeq3NoteSeeds` the PREVIOUSLY seeded notes from `current.document` to update in
            // place — this is what lets its idempotent branch (user edits on the canvas win) engage
            // at all; without it every regenerate would look like the very first seed application.
            // Deliberately scoped to only the notes this session's own `noteSeeds` minted (matched
            // by id) — a hand-added canvas note is not resurrected here, matching every session's
            // existing behaviour of a raw regenerate otherwise dropping fragments/notes wholesale.
            // A no-op (returns `fresh` unchanged) for the overwhelming majority of sessions, whose
            // `noteSeeds` is the empty-list default.
            val seededNoteIds = current.noteSeeds.mapTo(HashSet()) { it.id }
            val previouslySeededNotes = current.document.notes.filter { it.id in seededNoteIds }
            val document = applySeq3NoteSeeds(fresh.copy(notes = previouslySeededNotes), current.noteSeeds)
                .copy(title = current.document.title.ifBlank { fresh.title })
            generatedBaselines[id] = document
            val tab = current.sourceTabId?.let(appState::tab)
            val exportMode = current.exportMode ?: appState.settings.diagramDefaultExportMode
            val libraryItemId = tab?.let {
                saveToLibrary(current, it, encodeSeq3Note(document, current.dialect, exportMode = exportMode))?.id
            }
            current.copy(
                document = document,
                generating = false,
                libraryItemId = libraryItemId ?: current.libraryItemId,
                exportMode = exportMode,
            )
        }
        syncLiveLinkedNote(id)
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
     * workspace dirty or push an undo step.
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
     * title-strip note action makes the pending change visible to the user.
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

    /** Changes only the source dialect used by the explicit note action. */
    fun setDialect(id: String, dialect: Seq3Dialect) {
        if (session(id)?.dialect == dialect) return
        replace(id) { it.copy(dialect = dialect, dirty = true) }
        syncLiveLinkedNote(id)
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
        val document = restoreGeneratedAuthoring(result.document, generatedBaselines[id])
        // Tolerant command paths may report success even when the requested value is already
        // present. Once authoring normalization is applied, that is a true no-op and must not
        // create a misleading undo entry.
        if (document == current.document) return false
        replace(id) { it.copy(document = document, undoStack = (it.undoStack + undo).takeLast(MAX_UNDO_DEPTH)) }
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

    // ── Dirty flag / explicit note save ─────────────────────────────────────────────────────────
    //
    // Diagram edits remain outside AppState's annotation autosave: opening or editing a diagram
    // never creates or rewrites a note until the user explicitly presses the title-strip note
    // action. The separate diagram-library draft is synchronized by [autoSaveDraftToLibrary].
    private fun markDirty(id: String) {
        replace(id) { it.copy(dirty = true) }
        autoSaveDraftToLibrary(id)
        syncLiveLinkedNote(id)
    }

    /** Keeps the diagram library useful as a real draft library. A workspace gets a record as soon
     * as its first generation lands, and every later document/title edit refreshes that same
     * record. This is deliberately independent from [confirm], which is still the explicit note
     * action and may or may not be used for a given diagram. */
    private fun autoSaveDraftToLibrary(id: String) {
        val current = session(id) ?: return
        val tab = current.sourceTabId?.let(appState::tab) ?: return
        if (current.document.lifelines.isEmpty()) return
        val exportMode = current.exportMode ?: appState.settings.diagramDefaultExportMode
        val encoded = encodeSeq3Note(current.document, current.dialect, exportMode = exportMode)
        val item = saveToLibrary(current, tab, encoded) ?: return
        replace(id) { it.copy(libraryItemId = item.id, exportMode = exportMode) }
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
     * Also refreshes the library record ([Seq3WorkspaceSession.libraryItemId]). Library drafts are
     * already created by generation and updated by edits; confirm additionally writes the note and
     * preserves the existing explicit attachment semantics.
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
        val existingAttachment = current.confirmedBlockId
            ?.let { blockId ->
                (appState.tab(tabId)?.annotations?.blocks?.firstOrNull { it.id == blockId } as? AnnBlock.Note)
                    ?.let { parseSeq3Note(it.text)?.attachment }
            }
        val text = encodeSeq3Note(
            current.document,
            current.dialect,
            exportMode = exportMode,
            attachment = existingAttachment,
        )
        val blockId = current.confirmedBlockId?.also { appState.updateBlock(tabId, it, text) }
            ?: appState.addNoteBlock(tabId, text)
        if (blockId != null) {
            val plainText = encodeSeq3Note(current.document, current.dialect, exportMode = exportMode)
            val libraryItemId = appState.tab(tabId)?.let { tab -> saveToLibrary(current, tab, plainText)?.id }
            replace(id) {
                it.copy(
                    confirmedBlockId = blockId,
                    dirty = false,
                    draftSavedAtMillis = clock(),
                    libraryItemId = libraryItemId ?: it.libraryItemId,
                    exportMode = exportMode,
                )
            }
            syncLiveLinkedNote(id)
        }
        return blockId
    }

    private fun saveToLibrary(current: Seq3WorkspaceSession, tab: LogTab, encodedNote: String): DiagramLibraryItem? {
        val title = current.document.title.ifBlank { "Untitled diagram" }
        val snapshot = DiagramLibrarySnapshot(encodedNote)
        val saved = if (current.libraryItemId == null || libraryStore.get(current.libraryItemId) == null) {
            libraryStore.create(title, "", sourceIdentity(tab), snapshot)
        } else {
            libraryStore.update(current.libraryItemId) { item ->
                item.copy(title = title, source = sourceIdentity(tab), snapshot = snapshot, updatedAt = clock())
            }
        }
        libraryRevision++
        return saved ?: current.libraryItemId?.let(libraryStore::get)
    }

    /** Explicitly attaches the current workspace as an immutable note snapshot. */
    fun attachSnapshot(id: String): String? = attach(id, Seq3AttachmentMode.SNAPSHOT)

    /** Explicitly attaches the current workspace as a durable live-link note. */
    fun attachLiveLink(id: String): String? = attach(id, Seq3AttachmentMode.LINKED)

    private fun attach(id: String, mode: Seq3AttachmentMode): String? {
        val current = session(id)
        val tabId = current?.sourceTabId
        val tab = current?.sourceTabId?.let(appState::tab)
        if (current == null || tabId == null || tab == null || current.document.lifelines.isEmpty()) return null
        val exportMode = current.exportMode ?: appState.settings.diagramDefaultExportMode
        val plainText = encodeSeq3Note(current.document, current.dialect, exportMode = exportMode)
        val item = saveToLibrary(current, tab, plainText) ?: return null
        val blockId = when (mode) {
            Seq3AttachmentMode.SNAPSHOT -> attachLibrarySnapshot(tabId, item.id)
            Seq3AttachmentMode.LINKED -> attachLibraryLink(tabId, item.id)
        } ?: return null
        replace(id) {
            it.copy(
                libraryItemId = item.id,
                confirmedBlockId = if (mode == Seq3AttachmentMode.LINKED) blockId else it.confirmedBlockId,
                dirty = false,
                draftSavedAtMillis = clock(),
                exportMode = exportMode,
            )
        }
        return blockId
    }

    /** Adds an immutable copy of a saved library artifact to [tabId]. */
    fun attachLibrarySnapshot(tabId: String, libraryItemId: String, afterBlockId: String? = null): String? =
        attachLibraryItem(tabId, libraryItemId, DiagramAttachmentKind.SNAPSHOT, afterBlockId)

    /** Adds a durable live-link note that follows later workspace/library updates. */
    fun attachLibraryLink(tabId: String, libraryItemId: String, afterBlockId: String? = null): String? =
        attachLibraryItem(tabId, libraryItemId, DiagramAttachmentKind.LINK, afterBlockId)

    private fun attachLibraryItem(
        tabId: String,
        libraryItemId: String,
        kind: DiagramAttachmentKind,
        afterBlockId: String?,
    ): String? {
        val item = libraryStore.get(libraryItemId) ?: return null
        val parsed = item.parsed ?: return null
        val attachedAt = clock()
        val mode = if (kind == DiagramAttachmentKind.LINK) Seq3AttachmentMode.LINKED else Seq3AttachmentMode.SNAPSHOT
        val text = encodeSeq3Note(
            parsed.document,
            parsed.dialect,
            parsed.caption,
            parsed.exportMode,
            attachment = Seq3AttachmentMetadata(item.id, mode, item.updatedAt, attachedAt),
            sourceOverride = parsed.source,
        )
        val blockId = appState.addNoteBlock(tabId, text, afterBlockId) ?: return null
        libraryStore.addAttachment(
            libraryItemId,
            DiagramLibraryAttachment(tabId, blockId, kind, attachedAt),
        )
        libraryRevision++
        return blockId
    }

    /** Refreshes all live-link notes owned by this session after a document/library mutation. */
    private fun syncLiveLinkedNote(id: String) {
        val current = session(id) ?: return
        val tabId = current.sourceTabId ?: return
        val libraryItemId = current.libraryItemId ?: return
        val tab = appState.tab(tabId) ?: return
        val linkedNotes = tab.annotations.blocks.mapNotNull { block ->
            val note = block as? AnnBlock.Note ?: return@mapNotNull null
            val parsed = parseSeq3Note(note.text) ?: return@mapNotNull null
            val attachment = parsed.attachment ?: return@mapNotNull null
            if (attachment.mode == Seq3AttachmentMode.LINKED && attachment.diagramId == libraryItemId) {
                Triple(note.id, parsed, attachment)
            } else {
                null
            }
        }
        if (linkedNotes.isEmpty()) return
        val exportMode = current.exportMode ?: appState.settings.diagramDefaultExportMode
        val plainText = encodeSeq3Note(current.document, current.dialect, exportMode = exportMode)
        val updated = libraryStore.update(libraryItemId) { item ->
            item.copy(
                title = current.document.title.ifBlank { item.title },
                source = sourceIdentity(tab),
                snapshot = DiagramLibrarySnapshot(plainText),
                updatedAt = clock(),
            )
        } ?: return
        linkedNotes.forEach { (blockId, parsed, attachment) ->
            val linkedText = encodeSeq3Note(
                current.document,
                current.dialect,
                parsed.caption,
                exportMode,
                attachment = attachment.copy(revision = updated.updatedAt),
            )
            appState.updateBlock(tabId, blockId, linkedText)
        }
        libraryRevision++
        replace(id) {
            it.copy(
                exportMode = exportMode,
                dirty = false,
                draftSavedAtMillis = clock(),
            )
        }
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
        sessions.firstOrNull { existing ->
            existing.libraryItemId == id && (tabId == null || existing.sourceTabId == tabId)
        }?.let { existing ->
            libraryStore.markOpened(id)
            libraryRevision++
            activate(existing.id)
            return true
        }
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
            dialect = parsed.dialect,
        )
        sessions = sessions + session
        generatedBaselines[sessionId] = parsed.document
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

    /** Removes the library's durable back-reference when a Note attachment is deleted. */
    fun removeLibraryAttachment(tabId: String, blockId: String) {
        var changed = false
        libraryStore.all().forEach { item ->
            if (item.attachments.any { it.tabId == tabId && it.blockId == blockId }) {
                libraryStore.removeAttachment(item.id, tabId, blockId)
                changed = true
            }
        }
        if (changed) libraryRevision++
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
                val latestDocument = session(id)?.document ?: return@onSuccess
                val latestMessage = latestDocument.messages.firstOrNull { it.id == messageId } ?: return@onSuccess
                val matched = matchOneMessage(latestMessage, fresh.messages) ?: return@onSuccess
                // Move out splits one generated group into multiple edited rows. Reverting one
                // split row must restore the fresh group and remove its sibling split rows in the
                // same undoable command; otherwise reverting both halves duplicates every piece of
                // evidence (the exact failure reproduced in the screen recording).
                val matchedEvidenceIds = matched.occurrences.map { it.entryId }.toSet()
                val splitSiblingIds = if (matchedEvidenceIds.isEmpty()) {
                    emptySet()
                } else {
                    latestDocument.messages
                        .filter { it.id != messageId && it.occurrences.any { occurrence -> occurrence.entryId in matchedEvidenceIds } }
                        .mapTo(linkedSetOf(), Seq3Message::id)
                }
                applyCommand(
                    id,
                    Seq3Command.ReplaceMessage(messageId, matched, removeMessageIds = splitSiblingIds),
                )
            }.onFailure { e -> if (e is CancellationException) throw e }
        }
        revertJobs[id] = job
    }

    // ── Status-bar support ──────────────────────────────────────────────────────────────────────

    /**
     * The entries a status-bar figure is counted/spanned over. Deliberately not `Seq3Generator`'s
     * own (private) range resolver — a status line only ever needs a COUNT or a first/last
     * timestamp, and [Seq3Range.Time]'s exact carry-forward-timestamp rule isn't worth duplicating
     * for an estimate, so that one case falls back to the tab's full list rather than
     * re-implementing the generator's parsing. Shared by [scannedEntryCount] and [scannedTimeRange]
     * so the two can never disagree about which entries "scanned" means.
     */
    private fun scannedEntries(id: String): List<com.indagium.model.LogEntry> {
        val current = session(id) ?: return emptyList()
        val tab = current.sourceTabId?.let(appState::tab) ?: return emptyList()
        return when (val range = current.range) {
            is Seq3Range.VisibleView -> tab.logData
            is Seq3Range.Ids -> {
                if (range.selectedIds.isNotEmpty()) {
                    // Generation honors the exact selection when it is present. Keep the status
                    // counts aligned with that behavior instead of reporting the whole inclusive
                    // ID span around a sparse or collapsed-block selection.
                    tab.logData.filter { it.id in range.selectedIds }
                } else {
                    val lo = minOf(range.from, range.to)
                    val hi = maxOf(range.from, range.to)
                    tab.logData.filter { it.id in lo..hi }
                }
            }
            is Seq3Range.Time -> tab.logData
        }
    }

    /** Rough "N scanned" figure for the canvas status bar (design spec §04: "45 shown · 748 scanned
     *  · 4 lifelines · 31 hidden"). */
    fun scannedEntryCount(id: String): Int = scannedEntries(id).size

    /**
     * The scanned range's actual `"HH:MM:SS.mmm"` span — the title bar's subtitle (item 1 of the
     * phase-5 post-ship plan: a bare row count reads as if it WERE the scope; the real value is the
     * time span). A [Seq3Range.Time] scope already carries its own textual bounds
     * ([Seq3Range.Time.fromTs]/[toTs]), so those are returned directly rather than filtering
     * [scannedEntries] and re-deriving what the user already typed. [Seq3Range.Ids]/[Seq3Range.
     * VisibleView] read the first/last [scannedEntries]' own [com.indagium.model.LogEntry.ts] —
     * already a raw `"HH:MM:SS.mmm"` string, exactly the format this subtitle wants, no reformatting
     * needed. Null when there is nothing to show (no source tab, or the resolved range is empty).
     */
    fun scannedTimeRange(id: String): Pair<String, String>? {
        val range = session(id)?.range
        if (range is Seq3Range.Time) return range.fromTs to range.toTs
        val entries = scannedEntries(id)
        val first = entries.firstOrNull() ?: return null
        val last = entries.lastOrNull() ?: return null
        return first.ts to last.ts
    }

    /**
     * The scanned range's actual row-id span — the regenerate sheet's Rows scope fields (item 4 of
     * the phase-5 round-2 post-ship plan) seed from this instead of starting blank. Mirrors
     * [scannedTimeRange] exactly: a [Seq3Range.Ids] scope already carries its own bounds
     * ([Seq3Range.Ids.from]/[to]), so those are returned directly; [Seq3Range.Time]/[Seq3Range.
     * VisibleView] read the first/last [scannedEntries]' own [com.indagium.model.LogEntry.id]. Null
     * when there is nothing to show (no source tab, or the resolved range is empty).
     */
    fun scannedIdRange(id: String): Pair<Int, Int>? {
        val range = session(id)?.range
        if (range is Seq3Range.Ids) return range.from to range.to
        val entries = scannedEntries(id)
        val first = entries.firstOrNull() ?: return null
        val last = entries.lastOrNull() ?: return null
        return first.id to last.id
    }

    private companion object {
        const val GENERATE_DEBOUNCE_MS = 180L
        const val MAX_UNDO_DEPTH = 50
    }
}
