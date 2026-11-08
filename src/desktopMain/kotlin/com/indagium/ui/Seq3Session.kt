package com.indagium.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.indagium.debug.AppLogger
import com.indagium.diagram3.DiagramExportMode
import com.indagium.diagram3.ParsedSeq3
import com.indagium.diagram3.Seq3AttachmentMetadata
import com.indagium.diagram3.Seq3AttachmentMode
import com.indagium.diagram3.Seq3Authoring
import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.SEQ3_SOURCE_EDIT_LABEL
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3GenerateOptions
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3NoteSeed
import com.indagium.diagram3.Seq3Range
import com.indagium.diagram3.Seq3RegenReview
import com.indagium.diagram3.Seq3RowRef
import com.indagium.diagram3.Seq3SourceDiagnostic
import com.indagium.diagram3.Seq3SourceImportResult
import com.indagium.diagram3.Seq3UndoEntry
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.applySeq3Command
import com.indagium.diagram3.applySeq3NoteSeeds
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.diagram3.generateSeq3
import com.indagium.diagram3.importSeq3Source
import com.indagium.diagram3.matchOneMessage
import com.indagium.diagram3.normalizeSeq3FragmentSpans
import com.indagium.diagram3.parseSeq3Note
import com.indagium.diagram3.reviewSeq3Regeneration
import com.indagium.diagram3.seq3NoteHasHandEdit
import com.indagium.diagram3.toSource
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

    /** The AI test-suites workspace (ui/TestsWorkspace.kt): one workspace tab, owned by [AppState.testsTabOpen]. */
    data object Tests : ActiveSurface
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
     *  an annotation by itself. A NEW diagram starts in PlantUML; opening a saved diagram or note
     *  always passes the dialect it was stored with, so Mermaid ones stay Mermaid. */
    val dialect: Seq3Dialect = Seq3Dialect.PLANTUML,
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
    /**
     * WP14: true while at least one of this session's LINKED notes (`Seq3AttachmentMode.LINKED`)
     * has drifted — hand-edited since it was generated, [com.indagium.diagram3.seq3NoteHasHandEdit]
     * — and so [Seq3Session.syncLiveLinkedNote] is SKIPPING it rather than overwriting it. A live
     * link the user hand-edited has stopped being a live link in any meaningful sense; unlike
     * [Seq3Session.confirm]'s prompt (an explicit action, gated with a real choice), this runs from
     * [Seq3Session.publishGenerated]/[Seq3Session.markDirty] on every edit, so a prompt per sync is
     * unusable — this flag is the "tell the user once" alternative: [ui.Seq3CanvasStatusBar] renders
     * an extra clause while it's true, no popup, no dedupe map of its own needed (unlike
     * [Seq3Session.reportSaveRejection]'s [Seq3Session.lastReportedRejectionKey], there is exactly
     * ONE boolean here — the log call beside where this is set only fires on a false->true
     * transition, checked against the session's own previous value, which is dedupe enough).
     * Recomputed from scratch on every [Seq3Session.syncLiveLinkedNote] pass, so it also clears
     * itself automatically once nothing is drifted any more (e.g. the user adopts the hand-edited
     * source, or reverts it) — no separate "clear" call needed.
     */
    val linkedNoteDrifted: Boolean = false,
    /**
     * False for a diagram that has no ticket: it was made without a source log
     * ([Seq3Session.beginStandalone]) or opened from a library item that has none. Together with
     * [sourceTabId] this tells the two "no log" states apart: `sourceTabId == null && hasTicket` is a
     * ticket diagram whose log is closed or missing (it can be relinked, see [Seq3Session.relink]);
     * `sourceTabId == null && !hasTicket` is a diagram that never had a log. Every action that scans
     * log rows asks [logUnavailableReason] instead of testing either field.
     */
    val hasTicket: Boolean = true,
) {
    /** Null while a live source log is linked; otherwise the short reason an action that needs the
     *  log (regenerate, evidence, scroll to log, From notes, attach to notes) is unavailable. */
    /** True for a diagram that never had a log: nothing log-backed (regenerate, evidence, row
     *  insertion from the log, the guided pass) is offered at all, rather than offered and disabled. */
    val logFree: Boolean get() = sourceTabId == null && !hasTicket

    fun logUnavailableReason(): String? = when {
        sourceTabId != null -> null
        hasTicket -> "The source log is not open"
        else -> "This diagram has no log"
    }
}

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
 * A unique default title for a brand-new session, seeded in [Seq3Session.begin] or
 * [Seq3Session.beginEmpty] against that exact
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

    // Read immutable Compose snapshots lock-free. In particular autosave serialization runs while
    // AutosaveScheduler owns its writer lock and must never wait for stateLock.
    val activeSession: Seq3WorkspaceSession? get() = activeSessionId?.let(::session)

    /**
     * View state is kept with the open workspace rather than with its Compose composition. The
     * active-surface switch removes the workspace from composition, so a `remember(sessionId)`
     * inside [Seq3Workspace] would lose zoom and the other view preferences every time the user
     * visited another tab. Most of the state here is still ephemeral: closing the workspace drops
     * it from this map. The exception is the diagram's layout preferences — the tiled tree,
     * hidden/collapsed tiles, lock and last preset, alongside the legacy section flags and sizes —
     * which survive a full app restart via `seq3ViewToken()`/`restoreSeq3ViewToken()` (DiagramWorkspaceViewCodec.kt).
     * Those functions read/write the preferences into whichever [Seq3ViewState] this map hands out
     * for a session's id; see the EXCEPTION note on [Seq3ViewState] itself. Closing a tab also remembers
     * a layout the user changed in [layoutMemory], per library item, for the next time the item is opened.
     */
    private val workspaceViewStates = ConcurrentHashMap<String, Seq3ViewState>()

    /** Runtime-only generated snapshot used to clear a stale EDITED badge when a row is changed
     *  and later restored to its original generated values. It is deliberately not persisted as a
     *  second copy of the diagram note. */
    private val generatedBaselines = ConcurrentHashMap<String, Seq3Document>()

    /** W1c anti-nag: the dedupe key ([DiagramSaveRejection.dedupeKey]) of the last rejection
     *  [reportSaveRejection] actually surfaced for this session, if any. Without this, an
     *  over-budget session re-triggers [saveToLibrary]/[syncLiveLinkedNote] on every subsequent
     *  edit (`markDirty`'s own debounce-free autosave), each one rejected again, each one
     *  reopening the SAME popup the user just dismissed. Cleared on a later successful save (the
     *  problem this session had is no longer true) and on [close] (no session, nothing to
     *  remember). Keyed by category rather than the rejection's exact payload — two `TooLarge`
     *  reports in a row for the same session almost never carry byte-for-byte identical counts
     *  (the user's still-oversized document keeps changing size as they edit it), so exact value
     *  equality would defeat the dedupe almost every time. */
    private val lastReportedRejectionKey = ConcurrentHashMap<String, String>()

    /** When the last live source edit of a typing burst was applied, per session, so the next one
     *  can join its diagram undo entry ([seq3SourceUndoMerges]). Read and written under
     *  `appState.stateLock`; any other command or an undo ends the burst. */
    private val liveSourceBurstAtMs = HashMap<String, Long>()

    internal fun viewState(id: String): Seq3ViewState? {
        val session = session(id) ?: return null
        return workspaceViewStates.computeIfAbsent(id) { newViewState(session.libraryItemId) }
    }

    /** What the diagram workspaces remember of their layouts across a closed tab and a restart; see [DiagramLayoutMemory]. */
    internal val layoutMemory = DiagramLayoutMemory()

    /** Forgets the remembered layouts of items the library no longer holds (a restored token may name one deleted since). */
    internal fun dropLayoutsOfMissingItems() = layoutMemory.retainWhere { libraryStore.get(it) != null }

    /** A new view for the item [libraryItemId] (null for a diagram with no library record): it starts from the layout the
     *  item was left with, else from the last layout the user set on any diagram, else from the built-in default. */
    private fun newViewState(libraryItemId: String?): Seq3ViewState {
        val view = Seq3ViewState()
        (libraryItemId?.let(layoutMemory::layoutFor) ?: layoutMemory.lastSet())?.let(view::applySavedLayout)
        view.diagramLayoutBaseline = view.savedLayout()
        return view
    }

    private fun session(id: String): Seq3WorkspaceSession? = sessions.firstOrNull { it.id == id }

    /** The source the editor shows and compares its draft against: [toSource] with nested content
     *  indented, unless the app-wide Auto-indent setting is off (then the generated text is shown as
     *  it is). Exports and the importer keep using the plain [toSource]. */
    private fun Seq3Document.toEditorSource(dialect: Seq3Dialect): String =
        if (appState.settings.diagramSourceAutoIndent) seq3IndentSource(toSource(dialect), dialect) else toSource(dialect)

    /** Switches the app-wide Auto-indent setting and brings every open source draft to the new form
     *  of the diagram's source: a clean draft follows it as one undoable editor edit; a draft with
     *  typed edits keeps its text and only takes the new base, because the diagram did not change. */
    fun setSourceAutoIndent(enabled: Boolean) {
        if (appState.settings.diagramSourceAutoIndent == enabled) return
        appState.updateSettings { it.copy(diagramSourceAutoIndent = enabled) }
        workspaceViewStates.forEach { (id, view) ->
            if (!view.sourceDraftInitialized) return@forEach
            val document = session(id)?.document ?: return@forEach
            val canonical = document.toEditorSource(view.sourceEditorDialect)
            if (canonical == view.sourceDraftCanonical) return@forEach
            if (view.sourceDraft == view.sourceDraftCanonical) {
                refreshSourceDraft(view, document)
            } else if (!view.sourceDraftStale) {
                view.sourceDraftCanonical = canonical
                view.sourceDraftDirty = view.sourceDraft != canonical
            }
        }
    }

    /** [fn] must be a pure in-memory copy; persistence, autosave scheduling and other side effects
     *  belong after this list update so AppState.stateLock never covers IO or scheduler locks. */
    private fun replace(id: String, fn: (Seq3WorkspaceSession) -> Seq3WorkspaceSession): Boolean = synchronized(appState.stateLock) {
        if (sessions.none { it.id == id }) return@synchronized false
        sessions = sessions.map { if (it.id == id) fn(it) else it }
        true
    }

    /** Publishes one freshly-built workspace and its mixed-strip membership as one state change.
     *  Caller performs library IO and generation only after this critical section returns. */
    private fun publishSession(session: Seq3WorkspaceSession, originGroupId: String?, baseline: Seq3Document? = null) {
        synchronized(appState.stateLock) {
            sessions = sessions + session
            appState.inheritTabGroupForOpenedRef(originGroupId, TabRef.Diagram(session.id))
            appState.revealTabGroupForActivation(TabRef.Diagram(session.id))
            activeSessionId = session.id
            appState.activeSurface = ActiveSurface.Diagram3(session.id)
            if (baseline != null) generatedBaselines[session.id] = baseline
        }
    }

    private fun activateLocked(id: String): Boolean {
        if (sessions.none { it.id == id }) return false
        appState.revealTabGroupForActivation(TabRef.Diagram(id))
        activeSessionId = id
        appState.activeSurface = ActiveSurface.Diagram3(id)
        return true
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
    fun begin(tabId: String, selectedIds: Set<Int> = emptySet()): String? =
        begin(tabId, selectedIds, TabRef.Log(tabId))

    internal fun begin(tabId: String, selectedIds: Set<Int>, origin: TabRef?): String? {
        val originGroupId = appState.tabGroupIdForOrigin(origin)
        val tab = appState.tab(tabId) ?: return null
        return begin(tab, rangeFor(tab, selectedIds), emptyList(), originGroupId)
    }

    /** Opens a blank diagram for [tabId]. Unlike [begin], this does not scan the log or derive
     *  participants, messages, notes, or other artifacts from it. The source tab is retained only
     *  for the diagram library's normal per-log organization and autosave behavior; the blank
     *  document itself carries no source filename. Its first library snapshot is written before
     *  returning, so closing the workspace before the first edit still leaves a reopenable draft. */
    fun beginEmpty(tabId: String): String? = beginEmpty(tabId, TabRef.Log(tabId))

    internal fun beginEmpty(tabId: String, origin: TabRef?): String? {
        val originGroupId = appState.tabGroupIdForOrigin(origin)
        val tab = appState.tab(tabId) ?: return null
        val source = sourceIdentity(tab)
        val title = defaultSeq3Title(libraryStore.forSource(source).map { it.title }.toSet())
        val document = Seq3Document(title = title)
        val id = "seq3-${UUID.randomUUID()}"
        val session = Seq3WorkspaceSession(
            id = id,
            sourceTabId = tab.id,
            document = document,
            range = document.range,
            // Keep the blank document source-free, but remember which log an explicit future
            // regeneration should use. The filename becomes document metadata only after that
            // generation actually runs.
            generateOptions = Seq3GenerateOptions(sourceFile = File(tab.filename).name),
        )
        publishSession(session, originGroupId, document)

        val exportMode = appState.settings.diagramDefaultExportMode
        val encoded = encodeSeq3Note(document, session.dialect, exportMode = exportMode)
        val item = saveToLibrary(session, tab, encoded)
        if (item != null) {
            replace(id) { it.copy(libraryItemId = item.id, exportMode = exportMode) }
        }
        return id
    }

    /** Creates a diagram with no log (no ticket) and opens it as its own Diagram tab, in no tab
     *  group. It starts blank with a fresh default title that no other ticket-less diagram uses,
     *  and its first library snapshot is written before returning, so closing the tab before the
     *  first edit still leaves a reopenable draft. The session has no [Seq3WorkspaceSession.
     *  sourceTabId]; log-needing actions are unavailable ([Seq3WorkspaceSession.
     *  logUnavailableReason]) but a log can still be linked later with [relink]. Returns the
     *  session id. */
    fun beginStandalone(): String? = beginStandalone(origin = null)

    internal fun beginStandalone(origin: TabRef?): String? {
        val originGroupId = appState.tabGroupIdForOrigin(origin)
        val title = defaultSeq3Title(libraryStore.forSource(DiagramSourceIdentity.NONE).map { it.title }.toSet())
        val document = Seq3Document(title = title)
        val id = "seq3-${UUID.randomUUID()}"
        val session = Seq3WorkspaceSession(
            id = id,
            sourceTabId = null,
            document = document,
            range = document.range,
            hasTicket = false,
        )
        publishSession(session, originGroupId, document)
        val exportMode = appState.settings.diagramDefaultExportMode
        val item = saveToLibrary(session, null, encodeSeq3Note(document, session.dialect, exportMode = exportMode))
        if (item != null) {
            replace(id) { it.copy(libraryItemId = item.id, exportMode = exportMode) }
        }
        return id
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
    fun beginFromNotes(tabId: String): String? = beginFromNotes(tabId, TabRef.Log(tabId))

    internal fun beginFromNotes(tabId: String, origin: TabRef?): String? {
        val originGroupId = appState.tabGroupIdForOrigin(origin)
        val tab = appState.tab(tabId) ?: return null
        val selection = seq3NotesSelection(tab)
        if (selection.entryIds.isEmpty()) return null
        val range = Seq3Range.Ids(selection.entryIds.min(), selection.entryIds.max(), selection.entryIds)
        return begin(tab, range, selection.seeds, originGroupId)
    }

    // Shared body for [begin] and [beginFromNotes] — everything about opening a fresh workspace
    // (title, library scoping, activeSurface, kicking off the first generate) is identical between
    // the two; only the range and the notes-derived seeds differ.
    private fun begin(tab: LogTab, range: Seq3Range, seeds: List<Seq3NoteSeed>, originGroupId: String?): String {
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
        publishSession(session, originGroupId)
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
    fun beginEdit(tabId: String, blockId: String): String? = beginEdit(tabId, blockId, TabRef.Log(tabId))

    internal fun beginEdit(tabId: String, blockId: String, origin: TabRef?): String? {
        val originGroupId = appState.tabGroupIdForOrigin(origin)
        val tab = appState.tab(tabId) ?: return null
        val block = tab.annotations.blocks.firstOrNull { it.id == blockId } as? AnnBlock.Note ?: return null
        val parsed = parseSeq3Note(block.text) ?: return null
        // A document saved by an older build can hold fragment membership that disagrees with the box
        // the canvas draws; it is made to agree on open (see normalizeSeq3FragmentSpans).
        val openedDocument = normalizeSeq3FragmentSpans(parsed.document)
        val attachmentLibraryId = parsed.attachment?.diagramId
        val isSnapshot = parsed.attachment?.mode == Seq3AttachmentMode.SNAPSHOT
        val existing = synchronized(appState.stateLock) { sessions.firstOrNull { existing ->
            existing.sourceTabId == tabId &&
                (!isSnapshot || existing.document == openedDocument) &&
                (existing.confirmedBlockId == blockId ||
                    (attachmentLibraryId != null && existing.libraryItemId == attachmentLibraryId))
        } }
        existing?.let {
            activate(it.id)
            return it.id
        }
        val id = "seq3-${UUID.randomUUID()}"
        val session = Seq3WorkspaceSession(
            id = id,
            sourceTabId = tabId,
            document = openedDocument,
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
        publishSession(session, originGroupId, openedDocument)
        return id
    }

    /** Selects an existing workspace without touching [AppState.tabs] — the v3 counterpart of
     *  [SeqDiagramCoordinator.activateWorkspace]. */
    fun activate(id: String): Boolean {
        return synchronized(appState.stateLock) { activateLocked(id) }
    }

    /** Closes [id] outright. Phase 3 has no dirty-close confirmation UI yet (that's a phase 4/5
     *  concern, mirroring [SeqDiagramCoordinator.requestCloseWorkspace]'s three-way prompt) — a
     *  caller that wants to warn on [Seq3WorkspaceSession.dirty] checks it before calling this. */
    fun close(id: String) {
        // W3: flush BEFORE tearing anything down — a pending debounced draft save is the only
        // persistence an unconfirmed edit has, so closing (workspace tab close, or app quit) within
        // DRAFT_SAVE_DEBOUNCE_MS of the last edit must not silently drop it.
        flush(id)
        generateJobs.remove(id)?.cancel()
        revertJobs.remove(id)?.cancel()
        draftSaveJobs.remove(id)?.cancel()
        draftSaveGenerations.remove(id)
        generations.remove(id)
        val libraryItemId = session(id)?.libraryItemId
        workspaceViewStates.remove(id)?.let { view ->
            // A layout the user changed outlives the tab: reopening the item shows it again.
            val layout = view.savedLayout()
            if (libraryItemId != null && layout != view.diagramLayoutBaseline) layoutMemory.remember(libraryItemId, layout)
        }
        synchronized(appState.stateLock) { liveSourceBurstAtMs.remove(id) }
        generatedBaselines.remove(id)
        lastReportedRejectionKey.remove(id)
        synchronized(appState.stateLock) {
            sessions = sessions.filterNot { it.id == id }
            appState.stripTabClosed(TabRef.Diagram(id))
            if (activeSessionId == id) {
                val next = sessions.lastOrNull()?.id
                activeSessionId = next
                // The Tests workspace may be the surface on screen while this (background) session closes.
                if (appState.activeSurface !is ActiveSurface.Tests) {
                    appState.activeSurface = next?.let(ActiveSurface::Diagram3) ?: appState.activeTab()?.id?.let(ActiveSurface::Log)
                }
            }
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
        synchronized(appState.stateLock) {
            val byId = sessions.associateBy { it.id }
            val reordered = orderedIds.mapNotNull(byId::get)
            val orderedSet = orderedIds.toHashSet()
            val omitted = sessions.filter { it.id !in orderedSet }
            sessions = reordered + omitted
        }
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
        val affected = synchronized(appState.stateLock) { sessions.filter { it.sourceTabId == tabId } }
        affected.forEach { s ->
            generateJobs.remove(s.id)?.cancel()
            // Regen-review and revert jobs also read the source tab's rows (they looked the tab up
            // once, at launch). Cancel them too so none outlives the tab into the release of its rows;
            // regenBuilding is cleared here because a cancelled job never reaches its own reset.
            regenJobs.remove(s.id)?.cancel()
            revertJobs.remove(s.id)?.cancel()
            replace(s.id) { it.copy(sourceTabId = null, generating = false, regenBuilding = false) }
        }
    }

    /** Relinks an offline session (its source tab closed, see [sourceTabClosed]) to an already-open
     *  tab. Regenerating remains an explicit follow-up call — this alone does not rebuild. */
    fun relink(id: String, tabId: String): Boolean {
        if (appState.tab(tabId) == null) return false
        if (session(id) == null) return false
        replace(id) { it.copy(sourceTabId = tabId, hasTicket = true) }
        return true
    }

    private fun rangeFor(tab: LogTab, selectedIds: Set<Int>): Seq3Range {
        val expansion = expandSelectionThroughCollapsedBlocks(tab, selectedIds)
        val effective = expansion.ids
        if (effective.isEmpty()) return Seq3Range.VisibleView
        val from = effective.min()
        val to = effective.max()
        // W4: a manual block too large to enumerate (Filter.kt's SELECTION_EXPANSION_MAX_IDS)
        // reports only its own boundary ids in `effective`, not its full interior — passing that
        // as `selectedIds` below would curate the diagram down to just those two lines instead of
        // the whole fold. Fall back to the plain span exactly like the contiguous case does; a
        // range this wide that still can't be diagrammed gets caught downstream by the codec's own
        // header-size gate (W1c), which pops up an explanatory notice instead of hanging.
        if (expansion.boundExceeded) return Seq3Range.Ids(from, to)
        // W1b: a CONTIGUOUS selection (every id in [from, to] is present) is exactly what the plain
        // inclusive span already means — Seq3Range.Ids's own doc: "empty [selectedIds] preserves the
        // plain inclusive-span behaviour" — so persist an EMPTY set instead of every id individually.
        // This is the common case (a drag selection, "select all", a whole-tab generate) and removes
        // the note's second-biggest unbounded contributor after occurrence text: on a 10M-line tab, an
        // all-visible-lines selection would otherwise persist ~10M ints into every saved note. A
        // SPARSE selection (the set's size doesn't match the span) still needs the explicit ids to
        // reproduce exactly what the user picked; if that's large enough to matter, the codec's own
        // header-size gate (W1c) catches it.
        return if (effective.size == to - from + 1) Seq3Range.Ids(from, to) else Seq3Range.Ids(from, to, effective)
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
            val tab = appState.awaitTabLoaded(tabId)
            if (tab == null) {
                if (generations[id]?.get() == generation) replace(id) { it.copy(generating = false) }
                return@launch
            }
            val cancellationCheck: () -> Unit = { if (!isActive) throw CancellationException("seq3 generate superseded") }
            generateRunCount.incrementAndGet()
            val result = runCatching {
                appState.withPinnedRows(tab) {
                    generateSeq3(tab.logData, current.range, current.generateOptions, cancellationCheck, appState.sourceIndex)
                }
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
        val current = session(id) ?: return
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
        // Deliberately called BEFORE the replace() below, not nested inside its lambda: `replace`
        // is `sessions = sessions.map { ... }` over a snapshot taken when the outer call starts, so
        // a REJECTED saveToLibrary calling its own reportSaveRejection -> replace(id) { ... } from
        // INSIDE this function's own replace lambda would have its effect silently overwritten the
        // instant the outer map's result gets assigned back to `sessions` — regenBuilding=false in
        // particular would never stick. Calling it here, then reading the session fresh via `latest`
        // in the one replace() call below, means whichever one runs last (this call's own
        // saveToLibrary, or a concurrently superseded generation) is what sticks — never a lost
        // write. See this phase's PR review for the exact hazard.
        val libraryItemId = tab?.let {
            saveToLibrary(current, it, encodeSeq3Note(document, current.dialect, exportMode = exportMode))?.id
        }
        replace(id) { latest ->
            latest.copy(
                document = document,
                generating = false,
                libraryItemId = libraryItemId ?: latest.libraryItemId,
                exportMode = exportMode,
            )
        }
        updateSourceDraftForDocument(id, document)
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

    /** Loads canonical PlantUML into the detached draft on first open, or follows the current
     *  diagram while the draft is clean. Dirty and stale drafts are preserved. */
    fun prepareSourceDraft(id: String): Boolean {
        val current = session(id) ?: return false
        val view = viewState(id) ?: return false
        if (!view.sourceDraftInitialized) {
            // The editor opens in the diagram's own dialect, the one the header control shows.
            view.sourceEditorDialect = current.dialect
            refreshSourceDraft(view, current.document)
            view.sourceFocusRequestGeneration++
        } else if (!view.sourceDraftDirty && !view.sourceDraftStale) {
            refreshSourceDraft(view, current.document)
        }
        return true
    }

    /** Switches the detached source draft to another text dialect after the UI has resolved any
     *  dirty-draft confirmation. A dialect switch always starts from the latest document source;
     *  the caller must not invoke this while an unconfirmed draft should be kept. The diagram's own
     *  dialect (the header control, saved with the diagram) changes with it: there is one dialect. */
    fun switchSourceDraftDialect(id: String, dialect: Seq3Dialect): Boolean {
        val current = session(id) ?: return false
        val view = viewState(id) ?: return false
        setDialect(id, dialect)
        if (view.sourceEditorDialect == dialect && view.sourceDraftInitialized) return true
        view.sourceEditorDialect = dialect
        view.sourceDraftInitialized = false
        refreshSourceDraft(view, current.document, force = true)
        return true
    }

    /** Explicitly discards the draft and reloads the latest diagram source. An open editor takes
     *  the text as one undoable edit, so Reload and Discard can be undone with a single Ctrl+Z. */
    fun reloadSourceDraft(id: String): Boolean {
        val current = session(id) ?: return false
        val view = viewState(id) ?: return false
        refreshSourceDraft(view, current.document, force = true, viaEditor = true)
        return true
    }

    /** Replaces the draft with the latest diagram source and restarts the editor, dropping its
     *  history: used after Apply and New diagram, where the old text is no longer meaningful. */
    private fun resetSourceDraft(id: String): Boolean {
        val current = session(id) ?: return false
        val view = viewState(id) ?: return false
        refreshSourceDraft(view, current.document, force = true)
        return true
    }

    /** Records field edits and keeps dirty/stale status derived from the current document. */
    fun updateSourceDraftText(id: String, source: String) {
        val current = session(id) ?: return
        val view = viewState(id) ?: return
        view.sourceDraft = source
        view.sourceDraftDirty = source != view.sourceDraftCanonical
        val currentCanonical = current.document.toEditorSource(view.sourceEditorDialect)
        if (view.sourceDraftDirty) {
            view.sourceDraftStale = currentCanonical != view.sourceDraftCanonical
            if (view.sourceDraftStale) view.sourceEvidenceLossPending = false
        } else if (currentCanonical != view.sourceDraftCanonical) {
            // The user reverted every source edit. The clean draft can now follow the diagram.
            refreshSourceDraft(view, current.document)
        } else {
            view.sourceDraftStale = false
        }
    }

    private fun refreshSourceDraft(
        view: Seq3ViewState,
        document: Seq3Document,
        force: Boolean = false,
        viaEditor: Boolean = false,
    ) {
        val canonical = document.toEditorSource(view.sourceEditorDialect)
        if (force || !view.sourceDraftInitialized || view.sourceDraftCanonical != canonical) {
            // An initialised editor takes a follow-the-diagram refresh (and an explicit reload, see
            // [viaEditor]) as one undoable edit through the external-text channel, keeping its
            // history and caret. Anything else restarts the editor with a new generation.
            val inEditor = view.sourceDraftInitialized && (!force || viaEditor)
            val textChanges = view.sourceDraft != canonical
            view.sourceDraft = canonical
            view.sourceDraftCanonical = canonical
            if (!inEditor) {
                view.sourceEditorGeneration++
            } else if (textChanges) {
                view.sourceExternalText = canonical
                view.sourceExternalRevision++
            }
        }
        view.sourceDraftInitialized = true
        view.sourceDraftDirty = false
        view.sourceDraftStale = false
        view.sourceLiveLastAppliedText = view.sourceDraft
        view.sourceDiagnostics = emptyList()
        view.sourceEvidenceLossPending = false
        view.sourceLiveImportBaseline = document
        view.sourceLiveLastAppliedDocument = document
    }

    private fun updateSourceDraftForDocument(id: String, document: Seq3Document) {
        val view = workspaceViewStates[id] ?: return
        if (!view.sourceDraftInitialized) return
        val currentCanonical = document.toEditorSource(view.sourceEditorDialect)
        val hasDraft = view.sourceDraft != view.sourceDraftCanonical
        view.sourceDraftDirty = hasDraft
        // The draft is exactly what the diagram last accepted (live import sets lastApplied to the
        // text as typed, which differs from the canonical form only by identity markers), with
        // nothing waiting on the user: there is no pending edit to protect, so a change to the
        // diagram's source form is adopted rather than locking the editor.
        val noPendingEdits = view.sourceDraft == view.sourceLiveLastAppliedText &&
            view.sourceDiagnostics.isEmpty() && !view.sourceEvidenceLossPending
        if (hasDraft && noPendingEdits && currentCanonical != view.sourceDraftCanonical) {
            refreshSourceDraft(view, document)
        } else if (hasDraft) {
            view.sourceDraftStale = currentCanonical != view.sourceDraftCanonical
            if (view.sourceDraftStale) view.sourceEvidenceLossPending = false
        } else {
            // The draft now matches its base exactly, so there is no user-authored source left to
            // preserve. Follow the latest document even if the tile was previously stale.
            refreshSourceDraft(view, document)
        }
    }

    /** Imports a debounced editor revision without taking ownership of the editor buffer. A valid
     *  revision becomes one ordinary document undo step; an invalid partial edit only updates its
     *  diagnostic, and a revision that removes evidence waits for the existing explicit
     *  confirmation flow. The import baseline stays fixed until reload/dialect change so source
     *  markers keep referring to the same generated rows while the user types. [previewSource] is
     *  the text that is imported when it differs from the draft [source]: the editor leaves out the
     *  half-typed word an open suggestion list belongs to, so the preview keeps following the
     *  rest of the draft without flagging that word. */
    fun applyLiveSourceDraft(
        id: String,
        source: String,
        dialect: Seq3Dialect,
        previewSource: String = source,
    ): Seq3SourceImportResult {
        val current = session(id) ?: return Seq3SourceImportResult.Failure(emptyList())
        val view = viewState(id) ?: return Seq3SourceImportResult.Failure(emptyList())
        if (view.sourceEditorDialect != dialect) {
            val failure = Seq3SourceImportResult.Failure(
                listOf(Seq3SourceDiagnostic(1, "Source dialect changed. Reload source to continue.")),
            )
            view.sourceDiagnostics = failure.diagnostics
            return failure
        }

        // The editor calls updateSourceDraftText for every text change. Calling it here too keeps
        // the API safe for non-UI callers and test harnesses, without changing the buffer when it
        // already contains this exact revision.
        if (view.sourceDraft != source) updateSourceDraftText(id, source)
        val latest = session(id) ?: return Seq3SourceImportResult.Failure(emptyList())
        val currentCanonical = latest.document.toEditorSource(dialect)
        val stale = view.sourceDraftStale ||
            (view.sourceDraft != view.sourceDraftCanonical && view.sourceDraftCanonical != currentCanonical)
        if (stale) {
            view.sourceDraftStale = true
            view.sourceEvidenceLossPending = false
            view.sourceDiagnostics = emptyList()
            return Seq3SourceImportResult.Failure(
                listOf(Seq3SourceDiagnostic(1, "Diagram changed. Reload source to continue.")),
            )
        }

        if (view.sourceLiveImportBaseline == null) {
            view.sourceLiveImportBaseline = latest.document
            view.sourceLiveLastAppliedDocument = latest.document
        }
        val baseline = requireNotNull(view.sourceLiveImportBaseline)
        // A blank editor clears structure while keeping current session metadata, including a
        // title entered since the source baseline was established.
        val importBase = if (previewSource.isBlank()) latest.document else baseline
        val imported = importSourceText(previewSource, dialect, importBase, sourceOwnsVisibleLifelines = true)
        val success = imported as? Seq3SourceImportResult.Success
        if (success == null) {
            view.sourceDiagnostics = (imported as Seq3SourceImportResult.Failure).diagnostics
            view.sourceEvidenceLossPending = false
            return imported
        }
        if (success.evidenceLoss) {
            view.sourceDiagnostics = emptyList()
            view.sourceEvidenceLossPending = true
            return success
        }

        val stableDocument = retainLatestLiveSourcePresentation(
            latest = latest.document,
            imported = retainLiveSourceIdentities(
                baseline = baseline,
                previous = view.sourceLiveLastAppliedDocument ?: baseline,
                imported = success.document,
            ),
        )
        if (stableDocument != latest.document &&
            !applyCommand(id, Seq3Command.ReplaceDocument(stableDocument, SEQ3_SOURCE_EDIT_LABEL), mergeBurst = true)
        ) {
            return Seq3SourceImportResult.Failure(emptyList())
        }
        val appliedDocument = session(id)?.document ?: stableDocument
        view.sourceLiveLastAppliedDocument = appliedDocument
        // What was imported: a trimmed preview leaves the draft with a pending, unapplied word.
        view.sourceLiveLastAppliedText = previewSource
        // Keep the text as typed. This baseline is only the current diagram's canonical form for
        // stale protection; advancing it must not increment the editor generation or move focus.
        view.sourceDraftCanonical = appliedDocument.toEditorSource(dialect)
        view.sourceDraftDirty = source != view.sourceDraftCanonical
        view.sourceDraftStale = false
        view.sourceDiagnostics = emptyList()
        view.sourceEvidenceLossPending = false
        return success.copy(document = appliedDocument, canonicalSource = view.sourceDraftCanonical)
    }

    /** Starts a new source-authored diagram after the UI's explicit replace confirmation. Unlike
     *  an ordinary source import, this parses against a structurally empty document so omitted
     *  old participants and rows cannot survive the new source. Existing evidence is a separate
     *  confirmation gate: the first call returns the prospective result without touching the
     *  diagram, and only a repeat with [confirmEvidenceLoss] commits it. */
    fun startNewSourceDiagram(
        id: String,
        source: String,
        dialect: Seq3Dialect,
        confirmEvidenceLoss: Boolean = false,
    ): Seq3SourceImportResult {
        val current = session(id) ?: return Seq3SourceImportResult.Failure(emptyList())
        val view = viewState(id) ?: return Seq3SourceImportResult.Failure(emptyList())
        val seed = current.document.copy(
            title = "",
            sourceFile = null,
            lifelines = emptyList(),
            messages = emptyList(),
            fragments = emptyList(),
            notes = emptyList(),
            delays = emptyList(),
            stateInvariants = emptyList(),
            manualActivations = emptyList(),
            rowOrder = emptyList(),
            elidedMessageCount = null,
        )
        val imported = importSourceText(source, dialect, seed)
        val success = imported as? Seq3SourceImportResult.Success ?: run {
            view.sourceDiagnostics = (imported as Seq3SourceImportResult.Failure).diagnostics
            view.sourceEvidenceLossPending = false
            return imported
        }
        val evidenceLoss = current.document.messages.any { it.occurrences.isNotEmpty() }
        val result = success.copy(evidenceLoss = evidenceLoss)
        if (evidenceLoss && !confirmEvidenceLoss) {
            view.sourceDiagnostics = emptyList()
            view.sourceEvidenceLossPending = true
            return result
        }

        if (result.document != current.document &&
            !applyCommand(id, Seq3Command.ReplaceDocument(result.document, SEQ3_SOURCE_EDIT_LABEL))
        ) {
            return Seq3SourceImportResult.Failure(emptyList())
        }
        view.sourceEditorDialect = dialect
        seq3ClearSelection(view, clearFocus = true)
        resetSourceDraft(id)
        return result.copy(canonicalSource = result.document.toEditorSource(dialect).trimEnd('\n'))
    }

    private fun importSourceText(
        source: String,
        dialect: Seq3Dialect,
        existing: Seq3Document,
        sourceOwnsVisibleLifelines: Boolean = false,
    ): Seq3SourceImportResult {
        if (source.length > 2 * 1024 * 1024) {
            return Seq3SourceImportResult.Failure(
                listOf(Seq3SourceDiagnostic(1, "Source exceeds the import limit.")),
            )
        }
        if (!source.isBlank()) {
            val imported = importSeq3Source(source, dialect, existing, sourceOwnsVisibleLifelines)
            val success = imported as? Seq3SourceImportResult.Success ?: return imported
            // The text source owns its title: removing its title statement removes the document
            // title too. Whitespace-only input is handled below as a structural clear that keeps
            // session metadata, and therefore remains distinct from a new wrapper with no title.
            if (source.lineSequence().none { it.trim().startsWith("title ") }) {
                val untitled = success.document.copy(title = "")
                return success.copy(
                    document = untitled,
                    canonicalSource = untitled.toEditorSource(dialect).trimEnd('\n'),
                )
            }
            return success
        }
        val empty = existing.copy(
            lifelines = emptyList(),
            messages = emptyList(),
            fragments = emptyList(),
            notes = emptyList(),
            delays = emptyList(),
            stateInvariants = emptyList(),
            manualActivations = emptyList(),
            rowOrder = emptyList(),
            elidedMessageCount = null,
        )
        val evidenceLoss = existing.messages.any { it.occurrences.isNotEmpty() }
        return Seq3SourceImportResult.Success(empty, "", evidenceLoss)
    }

    /** Newly typed, unmarked rows receive temporary importer ids. Reconcile them against the last
     *  accepted source result before publishing so changing a label (or another field) retains
     *  selection, references, and identity instead of manufacturing a fresh row every keystroke. */
    private fun retainLiveSourceIdentities(
        baseline: Seq3Document,
        previous: Seq3Document,
        imported: Seq3Document,
    ): Seq3Document {
        val baselineLifelineIds = baseline.lifelines.mapTo(hashSetOf()) { it.id }
        val addedLifelines = imported.lifelines.filter { it.id !in baselineLifelineIds }
        val previousLifelines = previous.lifelines.filter { it.id !in baselineLifelineIds }
        val lifelineIds = stableIdsForSourceRows(addedLifelines, previousLifelines, { it.id }) { new, old ->
            new.id == old.id
        }.toMutableMap()
        val usedLifelineIds = lifelineIds.values.toMutableSet()
        // Match unchanged names before positions so moving or deleting declarations keeps the
        // remaining columns attached to their own identities. Position resolves an edit to the
        // sole remaining declaration, including a partially typed alias or display name.
        addedLifelines.forEach { fresh ->
            if (fresh.id !in lifelineIds) {
                val match = previousLifelines.firstOrNull { it.id !in usedLifelineIds && it.name == fresh.name && it.kind == fresh.kind }
                if (match != null) {
                    lifelineIds[fresh.id] = match.id
                    usedLifelineIds += match.id
                }
            }
        }
        addedLifelines.forEachIndexed { index, fresh ->
            if (fresh.id !in lifelineIds) {
                val match = previousLifelines.getOrNull(index)?.takeIf { it.id !in usedLifelineIds }
                if (match != null) {
                    lifelineIds[fresh.id] = match.id
                    usedLifelineIds += match.id
                }
            }
        }
        fun lifelineId(id: String): String = lifelineIds[id] ?: id
        val normalized = imported.copy(
            lifelines = imported.lifelines.map { it.copy(id = lifelineId(it.id)) },
            messages = imported.messages.map { message ->
                message.copy(
                    fromLifelineId = lifelineId(message.fromLifelineId),
                    toLifelineId = message.toLifelineId?.let(::lifelineId),
                    match = if (message.occurrences.isEmpty() && message.match.tag == message.fromLifelineId) {
                        message.match.copy(tag = lifelineId(message.match.tag))
                    } else message.match,
                )
            },
            manualActivations = imported.manualActivations.map { it.copy(lifelineId = lifelineId(it.lifelineId)) },
        )
        val baselineMessageIds = baseline.messages.mapTo(hashSetOf()) { it.id }
        val previousMessages = previous.messages.filter { it.id !in baselineMessageIds && it.occurrences.isEmpty() }
        val addedMessages = normalized.messages.filter { it.id !in baselineMessageIds && it.occurrences.isEmpty() }
        val messageIds = stableIdsForSourceRows(
            addedMessages,
            previousMessages,
            { it.id },
        ) { new, old ->
            new.fromLifelineId == old.fromLifelineId && new.toLifelineId == old.toLifelineId &&
                new.kind == old.kind && new.labelTemplate == old.labelTemplate
        }.toMutableMap()
        val usedMessageIds = messageIds.values.toMutableSet()
        addedMessages.forEachIndexed { index, message ->
            if (message.id in messageIds) return@forEachIndexed
            val fallback = previousMessages.firstOrNull {
                it.id !in usedMessageIds && it.fromLifelineId == message.fromLifelineId &&
                    it.toLifelineId == message.toLifelineId && it.kind == message.kind
            } ?: previousMessages.getOrNull(index)?.takeIf { it.id !in usedMessageIds }
            if (fallback != null) {
                messageIds[message.id] = fallback.id
                usedMessageIds += fallback.id
            }
        }

        fun messageId(id: String): String = messageIds[id] ?: id
        val baseFragmentIds = baseline.fragments.mapTo(hashSetOf()) { it.id }
        val fragmentIds = stableIdsForSourceRows(
            imported.fragments.filter { it.id !in baseFragmentIds },
            previous.fragments.filter { it.id !in baseFragmentIds },
            { it.id },
        ) { new, old -> new.kind == old.kind && new.label == old.label }.toMutableMap()
        val usedFragmentIds = fragmentIds.values.toMutableSet()
        imported.fragments.filter { it.id !in baseFragmentIds }.forEachIndexed { index, fragment ->
            if (fragment.id in fragmentIds) return@forEachIndexed
            val fallback = previous.fragments.filter { it.id !in baseFragmentIds }.getOrNull(index)
                ?.takeIf { it.id !in usedFragmentIds }
            if (fallback != null) {
                fragmentIds[fragment.id] = fallback.id
                usedFragmentIds += fallback.id
            }
        }
        val baseNoteIds = baseline.notes.mapTo(hashSetOf()) { it.id }
        val noteIds = stableIdsForSourceRows(
            imported.notes.filter { it.id !in baseNoteIds },
            previous.notes.filter { it.id !in baseNoteIds },
            { it.id },
        ) { new, old -> new.text == old.text }.toMutableMap()
        val usedNoteIds = noteIds.values.toMutableSet()
        imported.notes.filter { it.id !in baseNoteIds }.forEachIndexed { index, note ->
            if (note.id in noteIds) return@forEachIndexed
            val fallback = previous.notes.filter { it.id !in baseNoteIds }.getOrNull(index)
                ?.takeIf { it.id !in usedNoteIds }
            if (fallback != null) {
                noteIds[note.id] = fallback.id
                usedNoteIds += fallback.id
            }
        }

        val baseDelayIds = baseline.delays.mapTo(hashSetOf()) { it.id }
        val delayIds = stableIdsForSourceRows(
            imported.delays.filter { it.id !in baseDelayIds },
            previous.delays.filter { it.id !in baseDelayIds },
            { it.id },
        ) { new, old -> new.label == old.label }.toMutableMap()
        val baseStateIds = baseline.stateInvariants.mapTo(hashSetOf()) { it.id }
        val stateIds = stableIdsForSourceRows(
            imported.stateInvariants.filter { it.id !in baseStateIds },
            previous.stateInvariants.filter { it.id !in baseStateIds },
            { it.id },
        ) { new, old -> new.captureName == old.captureName }.toMutableMap()

        val stableMessages = normalized.messages.map { message ->
            message.copy(
                id = messageId(message.id),
                fromLifelineId = message.fromLifelineId,
                toLifelineId = message.toLifelineId,
                movedOutFromMessageId = message.movedOutFromMessageId?.let(::messageId),
            )
        }
        val baselineOperandIds = baseline.fragments.flatMapTo(hashSetOf()) { fragment -> fragment.elseOperands.map { it.id } }
        val stableFragments = imported.fragments.map { fragment ->
            val stableId = fragmentIds[fragment.id] ?: fragment.id
            // Typed dividers get a fresh importer id on every parse.  Hand them the id of the same
            // position in the previous applied fragment so it does not churn per keystroke.
            val previousNewOperands = previous.fragments.firstOrNull { it.id == stableId }
                ?.elseOperands.orEmpty().filter { it.id !in baselineOperandIds }
            var newOperandIndex = 0
            fragment.copy(
                id = stableId,
                messageIds = fragment.messageIds.map(::messageId),
                occurrenceRefs = fragment.occurrenceRefs.map { it.copy(messageId = messageId(it.messageId)) },
                elseOperands = fragment.elseOperands.map { operand ->
                    val stableOperandId = if (operand.id in baselineOperandIds) {
                        operand.id
                    } else {
                        previousNewOperands.getOrNull(newOperandIndex++)?.id ?: operand.id
                    }
                    operand.copy(id = stableOperandId, startsAtMessageId = messageId(operand.startsAtMessageId))
                },
            )
        }
        return normalized.copy(
            messages = stableMessages,
            fragments = stableFragments,
            notes = imported.notes.map { note ->
                note.copy(id = noteIds[note.id] ?: note.id, messageIds = note.messageIds.map(::messageId))
            },
            delays = imported.delays.map { delay ->
                delay.copy(id = delayIds[delay.id] ?: delay.id, afterMessageId = messageId(delay.afterMessageId))
            },
            stateInvariants = imported.stateInvariants.map { state ->
                state.copy(id = stateIds[state.id] ?: state.id, messageId = messageId(state.messageId))
            },
            manualActivations = normalized.manualActivations.map { activation ->
                activation.copy(
                    startMessageId = messageId(activation.startMessageId),
                    endMessageId = activation.endMessageId?.let(::messageId),
                )
            },
            rowOrder = imported.rowOrder.map { row -> Seq3RowRef(messageId(row.messageId), row.occurrenceEntryId) },
        )
    }

    /** The source parser must keep reading its fixed import baseline so source markers and evidence
     * stay anchored while the user types. Canvas-only edits made after the draft began are a
     * separate layer: once the imported entities have their stable ids, carry those edits forward
     * from the latest document. Looking up only ids in [imported] keeps source-owned deletions
     * authoritative; structural/source-authored fields continue to come from the import result. */
    private fun retainLatestLiveSourcePresentation(latest: Seq3Document, imported: Seq3Document): Seq3Document {
        val lifelines = latest.lifelines.associateBy { it.id }
        val messages = latest.messages.associateBy { it.id }
        val fragments = latest.fragments.associateBy { it.id }
        val notes = latest.notes.associateBy { it.id }
        val delays = latest.delays.associateBy { it.id }
        val states = latest.stateInvariants.associateBy { it.id }

        return imported.copy(
            lifelines = imported.lifelines.map { fresh ->
                lifelines[fresh.id]?.let { old ->
                    fresh.copy(
                        visibility = old.visibility,
                        displaySegments = old.displaySegments,
                        headerWidthDp = old.headerWidthDp,
                    )
                } ?: fresh
            },
            messages = imported.messages.map { fresh ->
                messages[fresh.id]?.let { old -> fresh.copy(visibility = old.visibility, noteStyle = old.noteStyle) } ?: fresh
            },
            fragments = imported.fragments.map { fresh ->
                fragments[fresh.id]?.let { old ->
                    fresh.copy(visibility = old.visibility, hideKindLabel = old.hideKindLabel, style = old.style)
                } ?: fresh
            },
            notes = imported.notes.map { fresh ->
                notes[fresh.id]?.let { old ->
                    fresh.copy(
                        x = old.x,
                        y = old.y,
                        width = old.width,
                        height = old.height,
                        visibility = old.visibility,
                        style = old.style,
                    )
                } ?: fresh
            },
            delays = imported.delays.map { fresh ->
                delays[fresh.id]?.let { fresh.copy(visibility = it.visibility) } ?: fresh
            },
            stateInvariants = imported.stateInvariants.map { fresh ->
                states[fresh.id]?.let { fresh.copy(visibility = it.visibility) } ?: fresh
            },
            // Source does not explicitly encode these defaults. Preserve their values; if their
            // rendered effect changes source text, the canonical-source stale guard above rejects
            // the draft. Explicit source fields such as title and activation directives remain
            // importer-owned.
            defaultRepeat = latest.defaultRepeat,
            lifelineDisplaySegments = latest.lifelineDisplaySegments,
            themePresetName = latest.themePresetName,
        )
    }

    private fun <T> stableIdsForSourceRows(
        fresh: List<T>,
        previous: List<T>,
        id: (T) -> String,
        sameShape: (T, T) -> Boolean,
    ): Map<String, String> {
        val used = hashSetOf<String>()
        val result = linkedMapOf<String, String>()
        fresh.forEach { item ->
            val match = previous.firstOrNull { candidate -> id(candidate) !in used && sameShape(item, candidate) }
            if (match != null) {
                result[id(item)] = id(match)
                used += id(match)
            }
        }
        return result
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
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        applyCommand(id, Seq3Command.SetTitle(trimmed))
    }

    /** Changes the diagram's dialect: the one the note action writes and the source editor opens
     *  in. It is saved with the diagram's library draft. Callers that also show the source editor
     *  go through [switchSourceDraftDialect] so the editor follows. */
    fun setDialect(id: String, dialect: Seq3Dialect) {
        if (session(id)?.dialect == dialect) return
        replace(id) { it.copy(dialect = dialect, dirty = true) }
        scheduleDraftSave(id)
        syncLiveLinkedNote(id)
    }

    /** The title a New diagram keeps: the diagram's own, or, when it has none, a fresh default
     *  that no other diagram of the same log already uses. A diagram without a title would show
     *  as "Sequence diagram v3" in the header and as plain "Diagram" in the tab. */
    fun newSourceDiagramTitle(id: String): String {
        val current = session(id) ?: return BASE_UNTITLED_TITLE
        current.document.title.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val others = sourceIdentityOf(current)?.let(libraryStore::forSource).orEmpty()
            .filter { it.id != current.libraryItemId }
            .map { it.title }
        return defaultSeq3Title(others.toSet())
    }

    // ── Undo stack: every mutation routes through applySeq3Command ──────────────────────────────
    //
    // Seq3Commands.kt's own header explains why a whole-document snapshot per step makes "undo is
    // one step, including a whole regeneration apply" trivially true — this class only has to keep
    // a stack of those snapshots per session and never has to know what any individual command did.

    /** Applies [command] and, only if it actually changed anything, pushes the resulting
     *  [Seq3UndoEntry] and marks the session dirty. Phases 4-5's every editing verb (set target,
     *  merge, group, hide, regenerate-apply, …) must route through this, never mutate
     *  [Seq3WorkspaceSession.document] directly, or ⌘Z silently stops covering that verb.
     *  [mergeBurst] is set only by the live source editor: within one typing burst its edits
     *  share the first one's undo entry. */
    fun applyCommand(id: String, command: Seq3Command, mergeBurst: Boolean = false): Boolean {
        val document = synchronized(appState.stateLock) {
            val current = sessions.firstOrNull { it.id == id } ?: return@synchronized null
            val result = applySeq3Command(current.document, command)
            val undo = result.undo
            if (!result.applied || undo == null) return@synchronized null
            val updatedDocument = restoreGeneratedAuthoring(result.document, generatedBaselines[id])
            // Tolerant command paths may report success even when the requested value is already
            // present. Once authoring normalization is applied, that is a true no-op and must not
            // create a misleading undo entry.
            if (updatedDocument == current.document) return@synchronized null
            val now = clock()
            val merges = mergeBurst && seq3SourceUndoMerges(current.undoStack.lastOrNull()?.label, liveSourceBurstAtMs[id], now)
            if (mergeBurst) liveSourceBurstAtMs[id] = now else liveSourceBurstAtMs.remove(id)
            sessions = sessions.map {
                if (it.id == id) it.copy(
                    document = updatedDocument,
                    undoStack = if (merges) it.undoStack else (it.undoStack + undo).takeLast(MAX_UNDO_DEPTH),
                ) else it
            }
            updatedDocument
        } ?: return false
        updateSourceDraftForDocument(id, document)
        markDirty(id)
        return true
    }

    /**
     * Applies a source import as one ordinary ReplaceDocument undo step. Parsing finishes before
     * this method reaches mutable session state; therefore malformed source is an atomic no-op.
     * Whitespace-only input denotes an empty diagram. Evidence deletion is intentionally a two-step UI flow: callers inspect a
     * successful result with [Seq3SourceImportResult.Success.evidenceLoss] and repeat with
     * [confirmEvidenceLoss] only after the user explicitly agrees.
     */
    fun importSource(id: String, source: String, dialect: Seq3Dialect, confirmEvidenceLoss: Boolean = false): Seq3SourceImportResult {
        val current = session(id) ?: return Seq3SourceImportResult.Failure(emptyList())
        val view = viewState(id)
        if (view != null && view.sourceDraftInitialized && view.sourceEditorDialect != dialect) {
            return Seq3SourceImportResult.Failure(
                listOf(Seq3SourceDiagnostic(1, "Source dialect changed. Reload source to continue.")),
            )
        }
        val currentCanonical = current.document.toEditorSource(dialect)
        val stale = view?.sourceDraftInitialized == true && view.sourceDraft != view.sourceDraftCanonical &&
            view.sourceDraftCanonical != currentCanonical
        if (stale) {
            return Seq3SourceImportResult.Failure(listOf(Seq3SourceDiagnostic(1, "Diagram changed. Reload source to continue.")))
        }
        val liveBaseline = view?.sourceLiveImportBaseline?.takeIf { view.sourceEvidenceLossPending && source.isNotBlank() }
        val result = importSourceText(source, dialect, liveBaseline ?: current.document, sourceOwnsVisibleLifelines = liveBaseline != null)
        val imported = result as? Seq3SourceImportResult.Success ?: return result
        if (imported.evidenceLoss && !confirmEvidenceLoss) return imported
        val baseline = view?.sourceLiveImportBaseline ?: current.document
        val success = imported.copy(
            document = retainLatestLiveSourcePresentation(
                latest = current.document,
                imported = retainLiveSourceIdentities(
                    baseline = baseline,
                    previous = view?.sourceLiveLastAppliedDocument ?: current.document,
                    imported = imported.document,
                ),
            ),
        ).let { stable -> stable.copy(canonicalSource = stable.document.toEditorSource(dialect).trimEnd('\n')) }
        if (success.document != current.document) {
            if (!applyCommand(id, Seq3Command.ReplaceDocument(success.document, SEQ3_SOURCE_EDIT_LABEL))) {
                return Seq3SourceImportResult.Failure(emptyList())
            }
        }
        canonicalizeImportedLinkedNote(id, success)
        // A same-document import is still an explicit reconciliation: canonicalize all matching
        // linked notes/library snapshots now, without manufacturing a no-op ReplaceDocument undo.
        syncLiveLinkedNote(id)
        resetSourceDraft(id)
        return success
    }

    private fun canonicalizeImportedLinkedNote(id: String, success: Seq3SourceImportResult.Success) {
        // A linked fence is necessarily drifted at the point it is imported, and the ordinary
        // live-sync safeguard correctly refuses to overwrite drifted text.  This is the explicit
        // user-requested import path, so rewrite THIS confirmed block canonically before the
        // debounced sync runs; the hash now matches and subsequent ordinary syncs remain guarded.
        val after = session(id)
        val blockId = after?.confirmedBlockId
        val tabId = after?.sourceTabId
        if (blockId != null && tabId != null) {
            val note = appState.tab(tabId)?.annotations?.blocks?.filterIsInstance<AnnBlock.Note>()?.firstOrNull { it.id == blockId }
            val parsed = note?.text?.let(::parseSeq3Note)
            if (parsed?.attachment?.mode == Seq3AttachmentMode.LINKED) {
                appState.updateBlock(
                    tabId,
                    blockId,
                    encodeSeq3Note(success.document, parsed.dialect, parsed.caption, parsed.exportMode, attachment = parsed.attachment),
                )
            }
        }
    }

    fun canUndo(id: String): Boolean = session(id)?.undoStack?.isNotEmpty() == true

    /** Pops and restores the most recent [Seq3UndoEntry]. Silently does nothing on an empty stack —
     *  matches [SeqDiagramCoordinator.revertManualSeed]'s own "already safely no-ops" contract. */
    fun undo(id: String): Boolean {
        val document = synchronized(appState.stateLock) {
            val current = sessions.firstOrNull { it.id == id } ?: return@synchronized null
            val entry = current.undoStack.lastOrNull() ?: return@synchronized null
            liveSourceBurstAtMs.remove(id)
            sessions = sessions.map {
                if (it.id == id) it.copy(document = entry.before, undoStack = it.undoStack.dropLast(1)) else it
            }
            entry.before
        } ?: return false
        updateSourceDraftForDocument(id, document)
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
        if (replace(id) { it.copy(dirty = true) }) scheduleDraftSave(id)
    }

    // W3: autoSaveDraftToLibrary (encode + validate + rewrite the WHOLE library file) plus
    // syncLiveLinkedNote (parse every note block in the tab, encode AGAIN, rewrite the WHOLE
    // library file a second time) used to run synchronously inside markDirty — which applyCommand
    // calls from a pointer/keyboard handler on the Compose UI thread. Measured ~15ms encode+parse
    // at 1,400 occurrences, doubled, plus two full-file rewrites: a 40-80ms hitch per canvas edit
    // that grows with LIBRARY size, not diagram size. Debounced the same way [requestGenerate]
    // debounces a full regenerate — cancel-and-relaunch on [scope], a per-session Job in
    // [draftSaveJobs] — so a burst of edits (dragging an endpoint) collapses into one disk write.
    //
    // [draftSaveGenerations] is the conflation guard: cancelling a Job that has already moved past
    // its `delay()` suspension point (into the synchronous save work) does not interrupt it, so a
    // pathological back-to-back edit could otherwise let two dispatches both reach [runDraftSave].
    // Each dispatch captures its OWN generation number when scheduled; [runDraftSave] only proceeds
    // if it is still the latest, so at most one of the two actually does the work — never zero
    // (whichever loses the race is redundant, not lost: it always reads the CURRENT session state,
    // never a stale snapshot, so any dispatch that DOES run is always up to date).
    private val draftSaveJobs = ConcurrentHashMap<String, Job>()
    private val draftSaveGenerations = ConcurrentHashMap<String, AtomicLong>()

    /** Test-visible instrumentation, mirrors [generateRunCount]'s identical role: counts actual
     *  [autoSaveDraftToLibrary]/[syncLiveLinkedNote] executions (post-debounce or via [flush]), so a
     *  test can assert a burst of edits coalesces into ONE run instead of one per edit. */
    internal val draftSaveRunCount = AtomicLong()

    private fun scheduleDraftSave(id: String) {
        val generation = draftSaveGenerations.computeIfAbsent(id) { AtomicLong() }.incrementAndGet()
        draftSaveJobs.remove(id)?.cancel()
        val job = scope.launch {
            delay(DRAFT_SAVE_DEBOUNCE_MS)
            coroutineContext.ensureActive()
            runDraftSave(id, generation)
        }
        draftSaveJobs[id] = job
        // Self-cleans once done so a later flush() can tell "nothing pending" (map has no entry)
        // apart from "the debounced job already ran" (same state) — `remove(id, job)` only clears
        // the entry if it STILL points at this exact job, so a newer dispatch's entry is never
        // clobbered by an older one finishing late.
        job.invokeOnCompletion { draftSaveJobs.remove(id, job) }
    }

    private fun runDraftSave(id: String, generation: Long) {
        if (draftSaveGenerations[id]?.get() != generation) return
        draftSaveRunCount.incrementAndGet()
        autoSaveDraftToLibrary(id)
        syncLiveLinkedNote(id)
    }

    /** Runs any pending debounced draft save immediately instead of waiting out
     *  [DRAFT_SAVE_DEBOUNCE_MS] — called from [confirm], [attach] and [close] so an edit made in the
     *  last [DRAFT_SAVE_DEBOUNCE_MS] before any of those is never silently dropped. A no-op when
     *  nothing is pending (the debounced job already ran, or no edit has happened yet).
     *  `internal`, not `private`, purely so [Seq3SessionTest] can settle a pending draft save
     *  deterministically instead of a real-time sleep — mirrors [generateRunCount]'s identical
     *  test-visibility rationale. */
    internal fun flush(id: String) {
        val pending = draftSaveJobs.remove(id) ?: return
        pending.cancel()
        val generation = draftSaveGenerations.computeIfAbsent(id) { AtomicLong() }.incrementAndGet()
        runDraftSave(id, generation)
    }

    /** Keeps the diagram library useful as a real draft library. A generated workspace gets a
     * record as soon as its first generation lands; a blank workspace gets its first record in
     * [beginEmpty] before returning. Every later document/title edit refreshes that same record.
     * This is deliberately independent from [confirm], which is still the explicit note
     * action and may or may not be used for a given diagram.
     *
     * WP14: deliberately UNCHANGED by the hand-edit gate added to [confirm]/[syncLiveLinkedNote].
     * [saveToLibrary] writes the LIBRARY record — [DiagramLibraryStore]'s own draft snapshot — never
     * a note block in any tab's annotations, so there is no fence for a user to have hand-edited
     * here in the first place; `seq3NoteHasHandEdit` has nothing to check against. Do not "fix" this
     * into calling it anyway for consistency with the other two — that would be checking a property
     * this function's write can't violate. */
    private fun autoSaveDraftToLibrary(id: String) {
        val current = session(id) ?: return
        val tab = current.sourceTabId?.let(appState::tab)
        // A linked log that is not loaded yet (a restore in progress) must not be saved over. A
        // session with no log at all still saves: a ticket-less one has no source to keep, and one
        // whose log is closed keeps its existing record's source (see sourceIdentityOf).
        if (tab == null && (current.sourceTabId != null || sourceIdentityOf(current) == null)) return
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
        // W3: run any still-pending debounced draft save now rather than leaving it to fire later
        // against whatever session state exists AFTER this confirm — avoids a redundant write
        // chasing this one a moment later, and guarantees the library draft never lags behind an
        // explicit note-writing action.
        flush(id)
        val existingBlock = current.confirmedBlockId
            ?.let { blockId -> appState.tab(tabId)?.annotations?.blocks?.firstOrNull { it.id == blockId } as? AnnBlock.Note }
        // WP14: confirm() is an explicit user action (the note-strip "confirm" press), so it's the
        // one write path in this class that can afford to STOP and ask instead of silently
        // re-deriving the fence from `current.document` — see seq3NoteHasHandEdit's own doc for the
        // exact "drifted, not merely unparseable" check. Routes through AppState.
        // pendingNoteOverwrite (PendingNoteOverwrite.handEdit) rather than a second pending field:
        // that's the existing single-flight "at most one deferred note decision on screen" channel,
        // and a second field would let a hand-edit prompt and a file-conflict prompt both try to be
        // on screen at once. syncLiveLinkedNote below is the OTHER write path that can hit this same
        // drift, but it runs unattended off publishGenerated/markDirty — a prompt per debounced sync
        // would be unusable there, so it skips instead of asking; see that function's own doc.
        if (existingBlock != null && seq3NoteHasHandEdit(existingBlock.text)) {
            appState.pendingNoteOverwrite = PendingNoteOverwrite(
                tabId = tabId,
                targetPath = "",
                targetName = current.document.title.ifBlank { "Untitled diagram" },
                pendingTab = appState.tab(tabId) ?: return null,
                handEdit = PendingHandEditOverwrite(sessionId = id, blockId = existingBlock.id),
            )
            return null
        }
        return writeConfirmedNote(id, current, tabId, existingBlock)
    }

    /**
     * The actual note write + bookkeeping — everything [confirm] itself did unconditionally before
     * WP14 inserted the hand-edit gate above it. Split out so [resolveHandEditOverwrite] (that
     * gate's own "Overwrite" resolution) can run exactly this without going back through [confirm],
     * which would immediately re-run the very drift check that raised the prompt, against the text
     * THIS call is about to replace, and never terminate.
     */
    private fun writeConfirmedNote(id: String, current: Seq3WorkspaceSession, tabId: String, existingBlock: AnnBlock.Note?): String? {
        // Sticky per-session: seeded from the global default only the FIRST time (a brand-new
        // session's exportMode starts null); every later confirm on this session — including one
        // reached via beginEdit/openLibraryItem, which seed it from the note/item's own already-
        // written choice — reuses this value rather than re-reading a since-changed setting.
        val exportMode = current.exportMode ?: appState.settings.diagramDefaultExportMode
        val existingAttachment = existingBlock?.let { parseSeq3Note(it.text)?.attachment }
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

    /**
     * WP14: resolves the hand-edit prompt's "Overwrite" arm — [AppState.confirmSeq3NoteOverwrite]'s
     * only caller. Re-reads the session and its [Seq3WorkspaceSession.confirmedBlockId] fresh
     * rather than trusting anything captured when [confirm] first raised the prompt: the session
     * may have kept generating or being edited in the background while the modal sat on screen.
     * `internal`, not `private`: [AppState] — not this class — owns [AppState.pendingNoteOverwrite]
     * and must be the one to null it out before delegating here (never after, or a second call
     * arriving while this one runs could see the prompt as still "up"). A no-op (returns null) if
     * the session has since closed.
     */
    internal fun resolveHandEditOverwrite(id: String): String? {
        val current = session(id) ?: return null
        val tabId = current.sourceTabId ?: return null
        val existingBlock = current.confirmedBlockId
            ?.let { blockId -> appState.tab(tabId)?.annotations?.blocks?.firstOrNull { it.id == blockId } as? AnnBlock.Note }
        return writeConfirmedNote(id, current, tabId, existingBlock)
    }

    /**
     * W1c: routed through [DiagramLibraryStore.tryCreate]/[DiagramLibraryStore.tryUpdate] instead of
     * the throwing [DiagramLibraryStore.create]/[DiagramLibraryStore.update] — every one of this
     * function's four callers (generation just landed, an edit's autosave, an explicit confirm, an
     * explicit attach) runs on either a background coroutine or a UI-thread command handler, and an
     * escaping `IllegalArgumentException` from either is exactly the original blocker bug (a
     * permanently latched "Generating…", or a crashed window). A rejection reports through
     * [reportSaveRejection] and this returns null, same as the pre-existing "nothing to return"
     * contract every caller already handles with `?.`/`?:`.
     */
    private fun saveToLibrary(current: Seq3WorkspaceSession, tab: LogTab?, encodedNote: String): DiagramLibraryItem? {
        val title = current.document.title.ifBlank { "Untitled diagram" }
        val snapshot = DiagramLibrarySnapshot(encodedNote)
        // With a live log the record takes that log's identity; without one it keeps the source it
        // already has (a ticket diagram whose log is closed) or has none (a ticket-less diagram).
        val source = tab?.let(::sourceIdentity) ?: sourceIdentityOf(current) ?: DiagramSourceIdentity.NONE
        val result = if (current.libraryItemId == null || libraryStore.get(current.libraryItemId) == null) {
            libraryStore.tryCreate(title, "", source, snapshot)
        } else {
            libraryStore.tryUpdate(current.libraryItemId) { item ->
                item.copy(title = title, source = source, snapshot = snapshot, updatedAt = clock())
            }
        }
        val rejection = result.toDiagramSaveRejection()
        if (rejection != null) {
            reportSaveRejection(current.id, rejection)
            return null
        }
        // A save that succeeds after a previously reported rejection means whatever was wrong
        // (an over-wide range, a full library) is no longer true — forget it, so a LATER
        // rejection of the same category (the user grows the range again) surfaces fresh instead
        // of staying suppressed by the anti-nag dedupe below.
        lastReportedRejectionKey.remove(current.id)
        val saved = result?.getOrNull()
        libraryRevision++
        return saved ?: current.libraryItemId?.let(libraryStore::get)
    }

    /** Unwraps a `try*` [DiagramLibraryStore] call's [Result] into the [DiagramSaveRejection] it
     *  failed with, or null on success/no-op — the one place [saveToLibrary] and [syncLiveLinkedNote]
     *  turn a caught exception back into a typed reason instead of duplicating the same `as?` cast.
     *  Almost always a [DiagramSaveRejectedException] ([DiagramLibraryStore.rejectionFor] already
     *  screened the save before `trySave` attempted it); the generic [DiagramSaveRejection.Invalid]
     *  fallback exists only in case the underlying `save()` throws for a reason `rejectionFor`'s
     *  mirrored checks didn't anticipate, so an unknown failure still gets reported/logged/unlatched
     *  instead of silently swallowed. */
    private fun Result<DiagramLibraryItem>?.toDiagramSaveRejection(): DiagramSaveRejection? {
        val exception = this?.exceptionOrNull() ?: return null
        return (exception as? DiagramSaveRejectedException)?.rejection
            ?: DiagramSaveRejection.Invalid(exception.message ?: "Diagram could not be saved")
    }

    /** [syncLiveLinkedNote]'s own `try*` unwrap (kept out of that function's body purely to keep its
     *  own return-statement count under detekt's limit): a rejection routes through
     *  [reportSaveRejection] and both "nothing to return" cases — a genuine rejection, or a null
     *  [Result] meaning [DiagramLibraryStore.tryUpdate]'s id no longer exists — collapse to a single
     *  null, so the caller's own `?: return` is the one early exit for either. */
    private fun resolveLibraryWrite(id: String, result: Result<DiagramLibraryItem>?): DiagramLibraryItem? {
        val rejection = result.toDiagramSaveRejection()
        if (rejection != null) {
            reportSaveRejection(id, rejection)
            return null
        }
        // Same "the problem is gone, forget it" reasoning as saveToLibrary's own success path.
        lastReportedRejectionKey.remove(id)
        return result?.getOrNull()
    }

    /** Stable category for [lastReportedRejectionKey] — see that field's own doc for why this is
     *  keyed by CATEGORY rather than the rejection's exact payload. */
    private fun DiagramSaveRejection.dedupeKey(): String = when (this) {
        is DiagramSaveRejection.TooLarge -> "TooLarge"
        DiagramSaveRejection.LibraryFull -> "LibraryFull"
        is DiagramSaveRejection.Invalid -> "Invalid:$reason"
    }

    /**
     * W0/W1c's central error path. Every chain that can hit a [DiagramSaveRejection] — [saveToLibrary]
     * (backing [publishGenerated], [autoSaveDraftToLibrary], [confirm], [attach]) and
     * [syncLiveLinkedNote]'s own direct [DiagramLibraryStore.tryUpdate] call — routes here instead of
     * letting the rejection propagate as a thrown exception. Does all three things the W0/W1 brief
     * requires of every failure path: sets [AppState.pendingDiagramNotice] with a plain-English,
     * actionable reason, logs via [AppLogger.error] (an operational fact only — never log content,
     * matching [AppLogger]'s own "never user log rows" contract), and clears `generating`/
     * `regenBuilding` so the canvas can never stay latched on "Generating…" — the exact blocker bug.
     *
     * The notice/log are skipped — but `generating`/`regenBuilding` are ALWAYS still cleared,
     * unconditionally — when this exact category was the last thing reported for [id]
     * ([lastReportedRejectionKey]): a still-oversized document keeps re-hitting `saveToLibrary` on
     * every subsequent edit (`markDirty` has no debounce of its own yet — W3), and re-showing the
     * identical popup on every keystroke is worse than showing it once and letting the user act.
     */
    private fun reportSaveRejection(id: String, rejection: DiagramSaveRejection) {
        val key = rejection.dedupeKey()
        if (lastReportedRejectionKey.put(id, key) != key) {
            val notice = when (rejection) {
                is DiagramSaveRejection.TooLarge -> {
                    val scanned = scannedEntryCount(id)
                    DiagramNotice(
                        title = "Diagram too large to save",
                        body = "This range is too large to diagram. It scanned $scanned lines and the saved " +
                            "diagram would be ${formatByteSize(rejection.encodedChars.toLong())}, over the " +
                            "${formatByteSize(rejection.limitChars.toLong())} limit. Select a smaller range and try again.",
                    )
                }
                DiagramSaveRejection.LibraryFull -> DiagramNotice(
                    title = "Diagram library is full",
                    body = "The diagram library is full ($MAX_DIAGRAM_LIBRARY_ITEMS of $MAX_DIAGRAM_LIBRARY_ITEMS). " +
                        "Delete diagrams you no longer need from the library panel, then try again.",
                )
                is DiagramSaveRejection.Invalid -> DiagramNotice(title = "Couldn't save diagram", body = rejection.reason)
            }
            appState.pendingDiagramNotice = notice
            AppLogger.error("diagram3", "Diagram save rejected for session $id: $rejection")
        }
        replace(id) { it.copy(generating = false, regenBuilding = false) }
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
        flush(id) // W3: see confirm()'s identical call for why.
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
        // W2: DiagramLibraryStore.addAttachment is now non-throwing (routes through tryUpdate) — a
        // full per-item/library attachment cap, or a snapshot that has drifted out of bounds since
        // it was saved, quietly skips the library back-reference rather than crashing; the note
        // itself (already written above) is unaffected either way. No session is in scope here to
        // hang a W0 popup off of (this helper backs the standalone attachLibrarySnapshot/
        // attachLibraryLink entry points as well as attach()), so this only logs for observability.
        if (libraryStore.addAttachment(libraryItemId, DiagramLibraryAttachment(tabId, blockId, kind, attachedAt)) == null) {
            AppLogger.error("diagram3", "Diagram attachment not recorded in library for item $libraryItemId (cap or invalid snapshot)")
        }
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
        // WP14: a linked note whose fence was hand-edited since it was generated has stopped being
        // a "live" link in any meaningful sense — re-deriving it from `current.document` here (the
        // pre-WP14 behaviour) would throw the hand edit away on the very next debounced sync, with
        // no explicit user action in between to blame it on. Unlike confirm() (a real press, gated
        // with a real prompt), this runs unattended from publishGenerated/markDirty, so the policy
        // is SKIP + tell once (Seq3WorkspaceSession.linkedNoteDrifted), never ask. `!parsed.
        // sourceHashMatches` is exactly seq3NoteHasHandEdit's own check, already applied to a
        // ParsedSeq3 this function parsed a few lines up — routing it back through that function
        // from the raw text would just reparse the same note for free.
        val (drifted, syncable) = linkedNotes.partition { (_, parsed, _) -> !parsed.sourceHashMatches }
        if (drifted.isNotEmpty() && !current.linkedNoteDrifted) {
            // Logged on the false->true transition only (checked against the session's own PREVIOUS
            // flag, not a parallel dedupe map) — mirrors reportSaveRejection's "the user was already
            // told, don't tell them again on every keystroke" reasoning, but there is exactly one
            // boolean to debounce here, not a handful of rejection categories to distinguish.
            AppLogger.error(
                "diagram3",
                "Live-linked diagram note(s) skipped for session $id: hand-edited since generation, no longer syncing",
            )
        }
        replace(id) { it.copy(linkedNoteDrifted = drifted.isNotEmpty()) }
        // `if (syncable.isNotEmpty()) { ... }` rather than an early `if (syncable.isEmpty()) return`
        // — detekt's ReturnCount budget for this function is already spent on the tab/session lookups
        // above; this block simply falls through to the end when there is nothing left to sync
        // (every linked note drifted), same effective behaviour as the early return it replaces.
        if (syncable.isNotEmpty()) {
            val exportMode = current.exportMode ?: appState.settings.diagramDefaultExportMode
            val plainText = encodeSeq3Note(current.document, current.dialect, exportMode = exportMode)
            // W1c: tryUpdate, not update — this write can hit the same size/library-full rejections as
            // saveToLibrary (see that function's own doc), and it runs from publishGenerated/markDirty,
            // exactly the chains W1c requires never to let an IllegalArgumentException escape.
            val result = libraryStore.tryUpdate(libraryItemId) { item ->
                item.copy(
                    title = current.document.title.ifBlank { item.title },
                    source = sourceIdentity(tab),
                    snapshot = DiagramLibrarySnapshot(plainText),
                    updatedAt = clock(),
                )
            }
            resolveLibraryWrite(id, result)?.let { updated ->
                syncable.forEach { (blockId, parsed, attachment) ->
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
        }
    }

    // ── Diagram library (ui.DiagramLibraryStore) ────────────────────────────────────────────────

    // DiagramLibraryStore is a plain disk-backed store; this bridges its mutations into Compose so
    // AnnotationPanel's "Diagram library" section immediately reflects a confirm/delete — same
    // rationale as SeqDiagramCoordinator's identically-named field for v1/v2.
    private var libraryRevision by mutableStateOf(0)

    private fun sourceIdentity(tab: LogTab): DiagramSourceIdentity =
        DiagramSourceIdentity(sourcePath = tab.sourcePath ?: tab.filename, contentFingerprint = computeLogFingerprint(tab.logData))

    /** The source identity a library record of [current] has or should get: that of its live log,
     *  else the one its record already carries, else none for a diagram that has no ticket. Null
     *  for a ticket diagram whose log is gone and that has no record yet: its identity is unknown,
     *  so nothing may be saved for it. */
    private fun sourceIdentityOf(current: Seq3WorkspaceSession): DiagramSourceIdentity? =
        current.sourceTabId?.let(appState::tab)?.let(::sourceIdentity)
            ?: current.libraryItemId?.let(libraryStore::get)?.source
            ?: DiagramSourceIdentity.NONE.takeIf { !current.hasTicket }

    /** Returns only diagrams created from this exact log identity — the v3 counterpart of
     *  [SeqDiagramCoordinator.libraryForTab]. The fingerprint makes this deliberately stricter than
     *  a filename/path check: two revisions of the same log path must not share a library section. */
    fun libraryForTab(tab: LogTab): List<DiagramLibraryItem> {
        @Suppress("UNUSED_VARIABLE")
        val observedRevision = libraryRevision
        return libraryStore.forSource(sourceIdentity(tab))
    }

    /** WP17: the `ref` fragment picker's data source — [libraryForTab] above is deliberately NOT
     *  it. A `ref` box legitimately points at a diagram built from a DIFFERENT log than the one the
     *  current workspace was generated from (that's the whole point of a cross-reference), so the
     *  picker needs the same source-agnostic global search [DiagramLibraryStore.search] already
     *  offers — it had no production call site before this. `@Suppress("UNUSED_VARIABLE")` mirrors
     *  [libraryForTab]'s own `libraryRevision` read: this bridges the plain disk-backed store's
     *  mutations into Compose recomposition, same rationale as that function's own doc. */
    fun searchLibrary(
        query: String = "",
        source: DiagramSourceIdentity? = null,
        tickets: DiagramTicketFilter = DiagramTicketFilter.ALL,
    ): List<DiagramLibrarySummary> {
        @Suppress("UNUSED_VARIABLE")
        val observedRevision = libraryRevision
        return libraryStore.search(query, source, tickets)
    }

    /** The library as a "No ticket | Ticket | All" list, most recently updated first, optionally
     *  narrowed by [query]. Recomposes on every library change like [searchLibrary]. */
    fun listLibrary(tickets: DiagramTicketFilter = DiagramTicketFilter.ALL, query: String = ""): List<DiagramLibrarySummary> {
        @Suppress("UNUSED_VARIABLE")
        val observedRevision = libraryRevision
        return libraryStore.list(tickets, query)
    }

    /** WP17: the picker's default (no query typed yet) listing — [DiagramLibraryStore.recent] had
     *  no production call site before this either. Same source-agnostic reasoning as
     *  [searchLibrary] just above. */
    fun recentLibrary(limit: Int = 20): List<DiagramLibrarySummary> {
        @Suppress("UNUSED_VARIABLE")
        val observedRevision = libraryRevision
        return libraryStore.recent(limit)
    }

    /** WP17: a non-mutating lookup for one library item by id — unlike [openLibraryItem] below,
     *  this never calls `markOpened` and never opens a workspace session; it exists purely so the
     *  canvas can resolve a [Seq3Fragment.refDiagramId] to a title (or discover it no longer
     *  resolves) without any side effect, e.g. to decide what to draw for a `ref` box or whether a
     *  click should do anything. Returns null for an unknown/deleted id — the canvas's own contract
     *  ("a dangling ref still draws, never crashes") is met by this being a plain nullable read,
     *  never a throw. */
    fun libraryItem(id: String): DiagramLibraryItem? {
        @Suppress("UNUSED_VARIABLE")
        val observedRevision = libraryRevision
        return libraryStore.get(id)
    }

    /**
     * Opens a saved diagram from its cached codec snapshot into a fresh workspace — the v3
     * counterpart of [SeqDiagramCoordinator.openLibraryItem]. Supplying a currently open [tabId]
     * makes the session regenerable; omitting it (or naming a tab that isn't open) still opens a
     * fully viewable session from the cached document, same as v1/v2's offline contract.
     */
    fun openLibraryItem(id: String, tabId: String? = null): Boolean =
        openLibraryItem(id, tabId, tabId?.let { TabRef.Log(it) })

    internal fun openLibraryItem(id: String, origin: TabRef?): Boolean =
        openLibraryItem(id, tabId = null, origin = origin)

    internal fun openLibraryItem(id: String, tabId: String?, origin: TabRef?): Boolean =
        openLibraryItemSession(id, tabId, origin) != null

    /** Opens the library item [id] as its own Diagram tab with no log, in no tab group, and
     *  returns the session id (null for an unknown or unreadable item). This is how a ticket-less
     *  diagram is opened, and the fallback for a ticket diagram whose log no longer exists: the
     *  item's self-contained snapshot is shown and edited, and its record keeps its ticket. An
     *  already open session of the item is activated instead of duplicated. */
    fun openLibraryItemWithoutLog(id: String): String? = openLibraryItemWithoutLog(id, origin = null)

    /** As above, and the new Diagram tab joins the tab group of [origin] (the New tab it was opened from). */
    internal fun openLibraryItemWithoutLog(id: String, origin: TabRef?): String? =
        openLibraryItemSession(id, tabId = null, origin = origin)

    private fun openLibraryItemSession(id: String, tabId: String?, origin: TabRef?): String? {
        // Capture this before any store work or publication. `origin` is independent of tabId:
        // reference-diagram links pass their containing diagram here while leaving tabId null, so
        // the opened workspace remains offline-capable and does not inherit the referenced item's
        // unrelated source log.
        val originGroupId = appState.tabGroupIdForOrigin(origin)
        val existing = synchronized(appState.stateLock) { sessions.firstOrNull { existing ->
            existing.libraryItemId == id && (tabId == null || existing.sourceTabId == tabId)
        } }
        existing?.let {
            libraryStore.markOpened(id)
            libraryRevision++
            activate(it.id)
            return it.id
        }
        val item = libraryStore.markOpened(id) ?: return null
        libraryRevision++
        val parsed = item.parsed ?: return null
        val openedDocument = normalizeSeq3FragmentSpans(parsed.document)
        val usableTabId = tabId?.takeIf { appState.tab(it) != null }
        val sessionId = "seq3-${UUID.randomUUID()}"
        val session = Seq3WorkspaceSession(
            id = sessionId,
            sourceTabId = usableTabId,
            document = openedDocument,
            range = parsed.document.range,
            generateOptions = Seq3GenerateOptions(
                sourceFile = parsed.document.sourceFile
                    ?: usableTabId?.let(appState::tab)?.let { File(it.filename).name },
            ),
            libraryItemId = item.id,
            exportMode = parsed.exportMode,
            dialect = parsed.dialect,
            hasTicket = !item.source.isNone,
        )
        return synchronized(appState.stateLock) {
            val duplicate = sessions.firstOrNull { existing ->
                existing.libraryItemId == id && (usableTabId == null || existing.sourceTabId == usableTabId)
            }
            if (duplicate != null) {
                activateLocked(duplicate.id)
                duplicate.id
            } else {
                sessions = sessions + session
                appState.inheritTabGroupForOpenedRef(originGroupId, TabRef.Diagram(sessionId))
                appState.revealTabGroupForActivation(TabRef.Diagram(sessionId))
                generatedBaselines[sessionId] = openedDocument
                activeSessionId = sessionId
                appState.activeSurface = ActiveSurface.Diagram3(sessionId)
                sessionId
            }
        }
    }

    /** Deletes a saved diagram, closing any open session it backs (matches
     *  [SeqDiagramCoordinator.deleteLibraryItem]'s "the workspace can't outlive its own record"). */
    fun deleteLibraryItem(id: String): Boolean {
        synchronized(appState.stateLock) { sessions.filter { it.libraryItemId == id } }.forEach { close(it.id) }
        // After the closes: closing the item's open tab remembers its layout.
        layoutMemory.forget(id)
        return libraryStore.delete(id).also { deleted -> if (deleted) libraryRevision++ }
    }

    /**
     * Gives the saved diagram [id] the title [title] (trimmed) and returns whether it was renamed. The title lives in the diagram
     * itself, so an open workspace of the item renames through the ordinary [Seq3Command.SetTitle] (one undo step, and the same
     * save that every edit takes, flushed here so the record is current when this returns); an item nobody has open has its
     * stored snapshot re-encoded with the new title. Either way only the library record and its snapshot change: the item's
     * ticket, its attachments and the notes that hold snapshots of it are untouched. False for an unknown id, a blank title,
     * one over [MAX_DIAGRAM_LIBRARY_TITLE_CHARS] and a save the library rejects. Renaming to the title it has is a no-op that
     * returns true.
     */
    fun renameLibraryItem(id: String, title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_DIAGRAM_LIBRARY_TITLE_CHARS) return false
        val item = libraryStore.get(id) ?: return false
        val open = synchronized(appState.stateLock) { sessions.firstOrNull { it.libraryItemId == id } }
        if (open != null) {
            if (open.document.title != trimmed) {
                updateTitle(open.id, trimmed)
                flush(open.id)
            }
            return libraryStore.get(id)?.title == trimmed
        }
        val parsed = item.parsed ?: return false
        if (item.title == trimmed && parsed.document.title == trimmed) return true
        val encoded = encodeSeq3Note(
            parsed.document.copy(title = trimmed),
            parsed.dialect,
            parsed.caption,
            parsed.exportMode,
            parsed.attachment,
        )
        val result = libraryStore.tryUpdate(id) { it.copy(title = trimmed, snapshot = DiagramLibrarySnapshot(encoded), updatedAt = clock()) }
        val saved = result?.isSuccess == true
        if (saved) libraryRevision++
        return saved
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
            val tab = appState.awaitTabLoaded(tabId)
            if (tab == null) {
                replace(id) { it.copy(regenBuilding = false) }
                return@launch
            }
            val cancellationCheck: () -> Unit = { if (!isActive) throw CancellationException("seq3 regen review superseded") }
            generateRunCount.incrementAndGet()
            val result = runCatching {
                appState.withPinnedRows(tab) {
                    generateSeq3(tab.logData, range, options, cancellationCheck, appState.sourceIndex)
                }
            }
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
            val tab = appState.awaitTabLoaded(tabId) ?: return@launch
            val cancellationCheck: () -> Unit = { if (!isActive) throw CancellationException("seq3 revert superseded") }
            generateRunCount.incrementAndGet()
            val result = runCatching {
                appState.withPinnedRows(tab) {
                    generateSeq3(tab.logData, current.range, current.generateOptions, cancellationCheck, appState.sourceIndex)
                }
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
        // A manually authored diagram has no source filename because it has never been generated.
        // Its live source tab is only where it is organized and saved; counting those rows would
        // claim work the session never did. Generated diagrams keep their source filename even
        // when generation legitimately found no messages or the user later removes them all.
        if (current.document.sourceFile.isNullOrBlank()) return emptyList()
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
        val current = session(id) ?: return null
        if (current.document.sourceFile.isNullOrBlank()) return null
        val range = current.range
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

        // W3: SAAD §12.5's debounce inventory table ("Diagram draft save") documents this figure —
        // update it there too if this ever changes.
        const val DRAFT_SAVE_DEBOUNCE_MS = 400L
        const val MAX_UNDO_DEPTH = 50
    }
}
