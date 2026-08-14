package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indagium.diagram3.Seq3BulkAction
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Filter
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3GuidedPassState
import com.indagium.diagram3.Seq3Selection
import com.indagium.diagram3.Seq3Sort
import com.indagium.diagram3.Seq3Visibility
import java.util.UUID

// ── v3 workspace shell (phase 4) ────────────────────────────────────────────────────────────
//
// Phase 3 delivered a skeleton (title bar, panel/canvas split, footer) with placeholder rows and
// a rasterized-bitmap canvas so generation was visible end to end. This phase replaces both
// placeholders with the real spec §04/§06/§07 queue panel and a NATIVE Compose canvas (no more
// bitmap image — see Seq3Canvas.kt's own header for why), and adds the spec §03 Inspector as a
// third, conditionally-shown pane.
//
// All colors still come from [ThemeColors] (via [tc]) — including warn/warnBg/ok through
// [toSeq3RasterTheme] where the raster theme is still needed (Seq3RenderCache.layout, the PNG
// export path) — never a hardcoded hex, so this reads correctly in every preset, dark ones
// included (see the v3 rewrite plan's palette decision).

private val PANEL_WIDTH = 392.dp
private val INSPECTOR_WIDTH = 320.dp

@Composable
fun Seq3Workspace(state: AppState, sessionId: String) {
    val session = state.seq3Sessions.sessions.firstOrNull { it.id == sessionId } ?: return
    val tc = tc()
    // Session-scoped, ephemeral VIEW state — see [Seq3ViewState]'s own doc for why this is never
    // part of [Seq3WorkspaceSession]. `remember(sessionId)` gives every open v3 workspace its own
    // instance and resets it when a session closes and a different one is opened in its place.
    val view = remember(sessionId) { Seq3ViewState() }
    val focusRequester = remember(sessionId) { FocusRequester() }
    // The workspace root owns the §09 keyboard map. `onPreviewKeyEvent` (not onKeyEvent) so a key
    // is seen before a child consumes it, and the handler itself no-ops whenever a text field has
    // focus — see Seq3ViewState.textFieldFocused.
    LaunchedEffect(sessionId) { runCatching { focusRequester.requestFocus() } }
    Column(
        Modifier.fillMaxSize().background(tc.bg)
            .focusRequester(focusRequester).focusable()
            .onPreviewKeyEvent { event -> handleSeq3Key(state, session, view, event) },
    ) {
        Seq3TitleBar(state, session)
        if (view.guidedPass != null) {
            Seq3GuidedPass(state, session, view, Modifier.fillMaxSize())
        } else {
            Row(Modifier.fillMaxSize()) {
                Seq3QueuePanel(state, session, view, Modifier.width(PANEL_WIDTH).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(tc.br))
                Seq3Canvas(state, session, view, Modifier.weight(1f).fillMaxHeight())
                if (view.inspectorMessageId != null) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(tc.br))
                    Seq3Inspector(state, session, view, Modifier.width(INSPECTOR_WIDTH).fillMaxHeight())
                }
            }
        }
    }
    if (view.regenerateSheetOpen) Seq3RegenerateSheet(state, session, view)
}

@Composable
private fun Seq3TitleBar(state: AppState, session: Seq3WorkspaceSession) {
    val tc = tc()
    val tabName = session.sourceTabId?.let(state::tab)?.filename?.substringAfterLast('/') ?: "log closed"
    val scopeLabel = when (session.range) {
        is com.indagium.diagram3.Seq3Range.VisibleView -> "whole log range"
        is com.indagium.diagram3.Seq3Range.Ids -> "selected range"
        is com.indagium.diagram3.Seq3Range.Time -> "time range"
    }
    val scanned = state.seq3Sessions.scannedEntryCount(session.id)
    Row(
        Modifier.fillMaxWidth().background(tc.p)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            AppText(
                session.document.title.ifBlank { "Sequence diagram v3" },
                color = tc.tx, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
            AppText("$tabName · $scopeLabel · $scanned rows", color = tc.ts, fontSize = 11.sp)
        }
        // Non-functional placeholder per phase 3's brief — the range picker stays out of scope
        // here too; phase 4's brief is the panel/canvas/inspector, not the scope picker.
        Box(
            Modifier.background(tc.p2, CORNER_SM).border(1.dp, tc.br, CORNER_SM)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) { AppText("Scope: whole view ▾", color = tc.ts, fontSize = 11.sp) }
        if (session.generating) AppText("Generating…", color = tc.ts, fontSize = 11.sp)
        CloseButton(onClick = { state.seq3Sessions.close(session.id) })
    }
}

// ── Shared ephemeral view state (queue<->canvas<->inspector) ───────────────────────────────────

/**
 * Session-scoped VIEW state shared between [Seq3QueuePanel], [Seq3Canvas] and [Seq3Inspector] —
 * filter/sort/selection/hover/zoom/which-message-the-inspector-shows. Deliberately never part of
 * [Seq3WorkspaceSession]: spec §07's "sort is a view, never an edit" reasoning extends to every
 * field here — none of it is undo-tracked, none of it is written to a note, and losing it when a
 * session is closed and reopened is correct (the same way a browser tab's own scroll position
 * isn't persisted either). Every EDIT that reads this class still only ever reaches the document
 * through [Seq3Session.applyCommand] — this class itself never holds a [com.indagium.diagram3.
 * Seq3Document].
 */
internal class Seq3ViewState {
    var selection by mutableStateOf(Seq3Selection())
    var filter by mutableStateOf(Seq3Filter.ALL)
    var textFilter by mutableStateOf("")
    var sort by mutableStateOf(Seq3Sort.LOG_ORDER)

    /** Two-way row<->arrow hover (spec §04): set by whichever surface the pointer is over, read by
     *  the other. */
    var hoveredMessageId by mutableStateOf<String?>(null)

    /** Set by [Seq3Canvas] when an arrow is clicked; [Seq3QueuePanel] consumes it to scroll that
     *  row into view "even when the current filter hides it" (spec §04) — the canvas clears the
     *  filter itself before setting this, so the panel only ever needs to scroll. */
    var scrollRequestId by mutableStateOf<String?>(null)

    /** Which message [Seq3Inspector] shows; null hides the inspector pane entirely. Set on a
     *  single-row queue click or a canvas row click; NOT touched by ⇧/⌘ multi-select (the
     *  inspector is a one-message-at-a-time surface, spec §03). */
    var inspectorMessageId by mutableStateOf<String?>(null)

    /** Canvas double-click inline label editor target (spec §04). */
    var editingLabelMessageId by mutableStateOf<String?>(null)

    /** The lifeline header chip drawn in the accent color (spec §04's "the selected one in
     *  accent"). Purely a highlight — never gates which lifelines a dropdown/drag can target. */
    var selectedLifelineId by mutableStateOf<String?>(null)

    var zoom by mutableStateOf(1f)
    var zoomMode by mutableStateOf(Seq3ZoomMode.FIT)

    /** Non-null while the guided pass MODE is on screen (spec §05). A mode, not a dialog, so it
     *  lives here beside the other view state rather than in a dialog-visibility flag on the
     *  session — exiting it must never touch the document. */
    var guidedPass by mutableStateOf<Seq3GuidedPassState?>(null)

    /** Spec §08's review sheet visibility. The REVIEW itself lives on the session
     *  ([Seq3WorkspaceSession.pendingRegenReview]) because building it is async work that must
     *  survive a recomposition; only whether the sheet is shown is view state. */
    var regenerateSheetOpen by mutableStateOf(false)

    /** True while a text field inside the workspace holds focus. The §09 keyboard map is
     *  single-letter (`h` hides, `m` merges, `g` groups…), so without this guard typing "h" into
     *  the filter box or the inline label editor would fire a destructive command — the classic
     *  bug in keyboard-driven panels. Every text field in the v3 surface sets this. */
    var textFieldFocused by mutableStateOf(false)
}

internal enum class Seq3ZoomMode { FIT, FIT_WIDTH, MANUAL }

// ── Shared small dropdown-button (Set from ▾ / Set to ▾ / Group ▾ / sort / target lifeline) ───
//
// Same Popup(alignment=TopStart, offset=below-anchor, onDismissRequest, focusable=false) shape as
// SettingsDialog.kt's own inline dropdowns — reused here as a named composable since three new
// files each need one or more of these.

@Composable
internal fun Seq3DropdownButton(
    label: String,
    modifier: Modifier = Modifier,
    labelColor: Color = tc().tx,
    fillColor: Color = tc().p2,
    menuWidth: androidx.compose.ui.unit.Dp = 160.dp,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    val tc = tc()
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    var suppressUntilMs by remember { mutableStateOf(0L) }
    Box(modifier) {
        HoverBox(
            modifier = Modifier.clip(CORNER_SM).background(fillColor, CORNER_SM).border(1.dp, tc.br, CORNER_SM)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            onClick = { if (System.currentTimeMillis() >= suppressUntilMs) open = !open },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                AppText(label, color = labelColor, fontSize = 11.sp)
                AppText("▾", color = labelColor.copy(alpha = .7f), fontSize = 9.sp)
            }
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { 26.dp.roundToPx() }),
                onDismissRequest = { open = false; suppressUntilMs = System.currentTimeMillis() + 200 },
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    Modifier.width(menuWidth)
                        .shadow(8.dp, RoundedCornerShape(8.dp))
                        .background(tc.p, RoundedCornerShape(8.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(8.dp))
                        .padding(4.dp),
                ) {
                    menu { open = false; suppressUntilMs = System.currentTimeMillis() + 200 }
                }
            }
        }
    }
}

@Composable
internal fun Seq3DropdownMenuItem(label: String, active: Boolean = false, onClick: () -> Unit) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)),
        baseBg = if (active) tc.abg else Color.Transparent,
        onClick = onClick,
    ) {
        AppText(
            label,
            color = if (active) tc.ac else tc.tx,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

// ── The §09 keyboard map ──────────────────────────────────────────────────────────────────────
//
// "At 40-150 messages the mouse is the bottleneck. Every action in the panel has a key, and the
// keys are the same in the guided pass." (spec §09). ⌘Z is deliberately NOT handled here — it is
// already wired globally for a v3 surface in App.kt; duplicating it would undo twice per press.

/** What a key press means, resolved before any state is touched so [Seq3KeyActionTest] can assert
 *  the mapping — including the text-field guard — without a composition. */
internal sealed class Seq3KeyAction {
    data object PrevMessage : Seq3KeyAction()

    data object NextMessage : Seq3KeyAction()

    data class SetTarget(val oneBasedKey: Int) : Seq3KeyAction()

    data class SetSource(val oneBasedKey: Int) : Seq3KeyAction()

    data object StartGuidedPass : Seq3KeyAction()

    data object EditLabel : Seq3KeyAction()

    data object ToggleHide : Seq3KeyAction()

    data object MergeSelection : Seq3KeyAction()

    data object GroupSelection : Seq3KeyAction()

    data object JumpToLog : Seq3KeyAction()

    data object FocusFilter : Seq3KeyAction()

    data object SkipGuided : Seq3KeyAction()

    data object ConfirmGuided : Seq3KeyAction()

    data object Escape : Seq3KeyAction()
}

private const val DIGIT_KEY_MIN = 1
private const val DIGIT_KEY_MAX = 9

/**
 * Maps one key press to a [Seq3KeyAction], or null for "not ours — let it through".
 *
 * [textFieldFocused] is the critical guard: the map is single-letter, so without it typing `h` in
 * the filter box would hide a message. Only [Seq3KeyAction.Escape] survives a focused text field
 * (it blurs/cancels), matching how every other panel in this app treats Esc.
 */
@Suppress("CyclomaticComplexMethod")
internal fun seq3KeyAction(
    keyLabel: String,
    shift: Boolean,
    textFieldFocused: Boolean,
    guidedActive: Boolean,
): Seq3KeyAction? {
    if (keyLabel == "Escape") return Seq3KeyAction.Escape
    if (textFieldFocused) return null
    keyLabel.toIntOrNull()?.takeIf { it in DIGIT_KEY_MIN..DIGIT_KEY_MAX }?.let { digit ->
        return if (shift) Seq3KeyAction.SetSource(digit) else Seq3KeyAction.SetTarget(digit)
    }
    if (guidedActive && keyLabel == "Enter") return Seq3KeyAction.ConfirmGuided
    return when (keyLabel.lowercase()) {
        "j" -> Seq3KeyAction.PrevMessage
        "k" -> Seq3KeyAction.NextMessage
        "f" -> Seq3KeyAction.StartGuidedPass
        "e" -> Seq3KeyAction.EditLabel
        "h" -> Seq3KeyAction.ToggleHide
        "m" -> Seq3KeyAction.MergeSelection
        "g" -> Seq3KeyAction.GroupSelection
        "l" -> Seq3KeyAction.JumpToLog
        "s" -> if (guidedActive) Seq3KeyAction.SkipGuided else null
        "/" -> Seq3KeyAction.FocusFilter
        else -> null
    }
}

private fun keyLabelOf(event: KeyEvent): String = when (event.key) {
    Key.Escape -> "Escape"
    Key.Enter, Key.NumPadEnter -> "Enter"
    else -> {
        val code = event.utf16CodePoint
        if (code in 1..Char.MAX_VALUE.code) code.toChar().toString() else ""
    }
}

private fun handleSeq3Key(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    // ⌘/Ctrl chords belong to the global handler in App.kt (⌘Z undo) — never claim them here.
    if (event.isMetaPressed || event.isCtrlPressed) return false
    val action = seq3KeyAction(keyLabelOf(event), event.isShiftPressed, view.textFieldFocused, view.guidedPass != null)
        ?: return false
    return applySeq3KeyAction(state, session, view, action)
}

// Split one branch per action (rather than one big `when` block with a `return false`/implicit-
// true per case) purely to keep this dispatcher itself under detekt's ReturnCount budget — each
// action keeps its own early-return guard-clause style, just inside its own small function.
private fun applySeq3KeyAction(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    action: Seq3KeyAction,
): Boolean {
    val document = session.document
    return when (action) {
        is Seq3KeyAction.Escape -> applySeq3Escape(state, session, view)
        is Seq3KeyAction.StartGuidedPass -> applySeq3StartGuidedPass(document, view)
        is Seq3KeyAction.SkipGuided -> { skipSeq3Guided(session, view); true }
        is Seq3KeyAction.ConfirmGuided -> false // the footer button owns the explicit apply
        is Seq3KeyAction.PrevMessage -> { view.inspectorMessageId = seq3NeighbourMessageId(document, view.inspectorMessageId, -1); true }
        is Seq3KeyAction.NextMessage -> { view.inspectorMessageId = seq3NeighbourMessageId(document, view.inspectorMessageId, +1); true }
        is Seq3KeyAction.SetTarget -> applySeq3SetTarget(state, session, view, document, action)
        is Seq3KeyAction.SetSource -> applySeq3SetSource(state, session, view, document, action)
        is Seq3KeyAction.ToggleHide -> applySeq3ToggleHide(state, session, view, document)
        is Seq3KeyAction.MergeSelection -> applySeq3MergeSelection(state, session, view)
        is Seq3KeyAction.GroupSelection -> applySeq3GroupSelection(state, session, view)
        is Seq3KeyAction.EditLabel -> applySeq3EditLabel(view)
        is Seq3KeyAction.JumpToLog -> applySeq3JumpToLog(state, session, view, document)
        is Seq3KeyAction.FocusFilter -> { view.textFieldFocused = true; true }
    }
}

private fun applySeq3Escape(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState): Boolean = when {
    view.textFieldFocused -> { view.textFieldFocused = false; true }
    view.regenerateSheetOpen -> { closeSeq3RegenerateSheet(state, session, view); true }
    view.guidedPass != null -> { view.guidedPass = null; true }
    view.selection.selectedIds.isNotEmpty() -> { view.selection = Seq3Selection(); true }
    else -> false
}

private fun applySeq3StartGuidedPass(document: Seq3Document, view: Seq3ViewState): Boolean {
    val pass = startSeq3GuidedPass(document) ?: return false
    view.guidedPass = pass
    return true
}

private fun applySeq3SetTarget(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    document: Seq3Document,
    action: Seq3KeyAction.SetTarget,
): Boolean {
    val lifeline = seq3GuidedLifelineForKey(document, action.oneBasedKey) ?: return false
    val messageId = view.guidedPass?.currentMessageId ?: view.inspectorMessageId ?: return false
    if (view.guidedPass != null) {
        applySeq3GuidedChoice(state, session, view, Seq3Command.GuidedTarget(messageId, lifeline.id))
    } else {
        state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(setOf(messageId), Seq3BulkAction.SetTo(lifeline.id)))
    }
    return true
}

private fun applySeq3SetSource(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    document: Seq3Document,
    action: Seq3KeyAction.SetSource,
): Boolean {
    val lifeline = seq3GuidedLifelineForKey(document, action.oneBasedKey) ?: return false
    val messageId = view.guidedPass?.currentMessageId ?: view.inspectorMessageId ?: return false
    state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(setOf(messageId), Seq3BulkAction.SetFrom(lifeline.id)))
    return true
}

private fun applySeq3ToggleHide(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, document: Seq3Document): Boolean {
    val ids = seq3TargetIds(view) ?: return false
    val allHidden = document.messages.filter { it.id in ids }.all { it.visibility == Seq3Visibility.HIDDEN }
    val bulk = if (allHidden) Seq3BulkAction.Show else Seq3BulkAction.Hide
    state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(ids, bulk))
    return true
}

private fun applySeq3MergeSelection(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState): Boolean {
    val ids = view.selection.selectedIds.takeIf { it.size > 1 } ?: return false
    state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(ids, Seq3BulkAction.Merge("seq3-merge-${UUID.randomUUID()}")))
    return true
}

private fun applySeq3GroupSelection(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState): Boolean {
    val ids = view.selection.selectedIds.takeIf { it.isNotEmpty() } ?: return false
    val fragment = Seq3Fragment("seq3-fragment-${UUID.randomUUID()}", Seq3FragmentKind.LOOP, "loop", ids.toList())
    state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(ids, Seq3BulkAction.Group(fragment)))
    return true
}

private fun applySeq3EditLabel(view: Seq3ViewState): Boolean {
    val messageId = view.inspectorMessageId ?: return false
    view.editingLabelMessageId = messageId
    return true
}

private fun applySeq3JumpToLog(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, document: Seq3Document): Boolean {
    val messageId = view.inspectorMessageId ?: return false
    val entryId = document.messages.firstOrNull { it.id == messageId }?.occurrences?.firstOrNull()?.entryId ?: return false
    val tabId = session.sourceTabId ?: return false
    state.navigateToLogLine(tabId, entryId)
    return true
}

/** `J`/`K` step through the document's own (log-clock) order, not the current view sort — the
 *  spec's own "sort is a view" rule means a keyboard walk must not change meaning with the sort. */
private fun seq3NeighbourMessageId(document: Seq3Document, currentId: String?, delta: Int): String? {
    val ids = document.messages.map { it.id }
    if (ids.isEmpty()) return null
    val index = ids.indexOf(currentId)
    if (index < 0) return ids.first()
    return ids.getOrNull(index + delta) ?: currentId
}

/** `H` (hide/show) applies to the whole selection when there is one, otherwise to the single
 *  message the inspector is showing. */
private fun seq3TargetIds(view: Seq3ViewState): Set<String>? =
    view.selection.selectedIds.takeIf { it.isNotEmpty() } ?: view.inspectorMessageId?.let(::setOf)

// ── Draft-saved status label — shared by Seq3Canvas's status bar ───────────────────────────────

private const val MILLIS_PER_MINUTE = 60_000L

/** "Draft saved 2 min ago" (design spec §04) / "Saving…" while a debounced edit is still settling
 *  / blank before the first edit. Not `@Composable` — evaluated fresh on every recomposition. */
internal fun draftStatusLabel(session: Seq3WorkspaceSession): String = when {
    session.dirty -> "Saving…"
    session.draftSavedAtMillis == null -> ""
    else -> {
        val ageMs = (System.currentTimeMillis() - session.draftSavedAtMillis).coerceAtLeast(0)
        val minutes = ageMs / MILLIS_PER_MINUTE
        if (minutes <= 0) "Draft saved just now" else "Draft saved ${minutes}m ago"
    }
}
