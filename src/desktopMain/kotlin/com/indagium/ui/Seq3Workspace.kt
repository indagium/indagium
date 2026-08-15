@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indagium.diagram3.Seq3AttachmentMode
import com.indagium.diagram3.Seq3BulkAction
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Dialect
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

// Item 14 — drag bounds for the queue-panel divider. The panel width stays the seed value a
// freshly-opened workspace starts at; the height bounds keep the two stacked sections usable.
private const val PANEL_WIDTH_MIN_DP = 280f
private const val PANEL_WIDTH_MAX_DP = 560f
internal const val SEQ3_INSPECTOR_HEIGHT_MIN_DP = 140f
internal const val SEQ3_INSPECTOR_HEIGHT_MAX_DP = 520f

@Composable
fun Seq3Workspace(state: AppState, sessionId: String) {
    val session = state.seq3Sessions.sessions.firstOrNull { it.id == sessionId } ?: return
    val tc = tc()
    // Session-scoped, ephemeral VIEW state — see [Seq3ViewState]'s own doc for why this is never
    // part of [Seq3WorkspaceSession]. `remember(sessionId)` gives every open v3 workspace its own
    // instance and resets it when a session closes and a different one is opened in its place.
    // [Seq3ViewState.focusRequester] lives ON the view (not a separately remembered local) so every
    // composable already holding `view` — the row press/checkbox handlers in Seq3QueuePanel.kt,
    // [Seq3DropdownButton] via [LocalSeq3FocusRequester] below — can reclaim keyboard focus after a
    // click without a new parameter threaded through every call site.
    val view = remember(sessionId) { Seq3ViewState() }
    // The workspace root owns the §09 keyboard map. `onPreviewKeyEvent` (not onKeyEvent) so a key
    // is seen before a child consumes it, and the handler itself no-ops whenever a text field has
    // focus — see Seq3ViewState.textFieldFocused.
    LaunchedEffect(sessionId) { runCatching { view.focusRequester.requestFocus() } }
    // Item 4's Esc-reliability fix: `Modifier.clickable` (checkboxes, dropdown pills, action-bar
    // buttons — used throughout this surface) is focusable by default and can steal keyboard focus
    // from the workspace root, and a dismissed [Seq3DropdownButton] popup doesn't hand focus back
    // either — so `handleSeq3Key`'s Esc handling would silently stop firing after the very first
    // click on either. [LocalSeq3FocusRequester] makes [view.focusRequester] reachable from
    // [Seq3DropdownButton] (defined below) without adding a parameter to its 9 call sites; providing
    // it here, wrapping BOTH the root Column and the regenerate sheet (which renders as a sibling,
    // not a child, of the Column — see the `if` below), covers every dropdown/checkbox this surface
    // draws, sheet included.
    CompositionLocalProvider(LocalSeq3FocusRequester provides view.focusRequester) {
        Column(
            Modifier.fillMaxSize().background(tc.bg)
                .focusRequester(view.focusRequester).focusable()
                .onPreviewKeyEvent { event -> handleSeq3Key(state, session, view, event) },
        ) {
            Seq3TitleBar(state, session, view)
            // Keep the title identity block visually separate from the Messages/Canvas body.
            Divider()
            if (view.guidedPass != null) {
                Seq3GuidedPass(state, session, view, Modifier.fillMaxSize())
            } else {
                Row(Modifier.fillMaxSize()) {
                    if (view.sidebarOpen) {
                        Seq3QueuePanel(state, session, view, Modifier.width(view.panelWidthDp.dp).fillMaxHeight())
                        HDivider { delta ->
                            view.panelWidthDp = seq3ClampDividerWidth(view.panelWidthDp, delta, PANEL_WIDTH_MIN_DP, PANEL_WIDTH_MAX_DP)
                        }
                    }
                    Seq3Canvas(state, session, view, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        if (view.regenerateSheetOpen) Seq3RegenerateSheet(state, session, view)
    }
}

@Composable
private fun Seq3TitleBar(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState) {
    val tc = tc()
    val tabName = session.sourceTabId?.let(state::tab)?.filename?.substringAfterLast('/') ?: "log closed"
    val scanned = state.seq3Sessions.scannedEntryCount(session.id)
    // Keep the title bar focused on identity and size. The exact timestamp range belongs to the
    // log view, not this diagram header, and made the title area noisy without changing the
    // diagram's scope. Scope changes still happen through the regenerate sheet.
    val subtitle = "$tabName · $scanned rows"
    Row(
        Modifier.fillMaxWidth().background(tc.p)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Seq3TitleField(state, session)
            AppText(subtitle, color = tc.ts, fontSize = 11.sp)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ToolbarBtn(
                label = "≡",
                tooltip = if (view.sidebarOpen) "Hide Messages and Inspector" else "Show Messages and Inspector",
                active = view.sidebarOpen,
                modifier = Modifier.size(28.dp),
                shape = CORNER_SM,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                onClick = { view.sidebarOpen = !view.sidebarOpen },
            )
            Seq3AttachmentAction(state, session)
            SegmentedControl(
                options = listOf("PlantUML", "Mermaid"),
                selectedIndices = setOf(if (session.dialect == Seq3Dialect.PLANTUML) 0 else 1),
                onToggle = { index ->
                    state.seq3Sessions.setDialect(
                        session.id,
                        if (index == 0) Seq3Dialect.PLANTUML else Seq3Dialect.MERMAID,
                    )
                },
            )
            Seq3CanvasZoomToolbarControls(view)
            if (session.generating) AppText("Generating…", color = tc.ts, fontSize = 11.sp)
        }
    }
}

/** Compact explicit note attachment action. The button itself stays the same 28dp footprint as the
 * zoom stepper; the two attachment modes live in the popup so neither choice is hidden behind an
 * update-only state. */
@Composable
private fun Seq3AttachmentAction(state: AppState, session: Seq3WorkspaceSession) {
    val density = LocalDensity.current
    val focusRequester = LocalSeq3FocusRequester.current
    var open by remember(session.id) { mutableStateOf(false) }
    val primary = if (state.settings.diagramLinkedNotePrimary) Seq3AttachmentMode.LINKED else Seq3AttachmentMode.SNAPSHOT
    val secondary = if (primary == Seq3AttachmentMode.LINKED) Seq3AttachmentMode.SNAPSHOT else Seq3AttachmentMode.LINKED

    fun close() {
        open = false
        focusRequester?.let { runCatching { it.requestFocus() } }
    }

    Box {
        ToolbarBtn(
            label = "+",
            tooltip = "Attach diagram to note",
            modifier = Modifier.size(28.dp),
            shape = CORNER_SM,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            onClick = { open = !open },
        )
        if (open) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, with(density) { 30.dp.roundToPx() }),
                onDismissRequest = ::close,
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(170.dp)
                        .shadow(8.dp, RoundedCornerShape(8.dp))
                        .background(tc().p, RoundedCornerShape(8.dp))
                        .border(1.dp, tc().br, RoundedCornerShape(8.dp))
                        .padding(4.dp),
                ) {
                    Seq3DropdownMenuItem(
                        label = attachmentActionLabel(primary),
                        onClick = {
                            attach(state, session, primary)
                            close()
                        },
                    )
                    Seq3DropdownMenuItem(
                        label = attachmentActionLabel(secondary),
                        onClick = {
                            attach(state, session, secondary)
                            close()
                        },
                    )
                }
            }
        }
    }
}

private fun attachmentActionLabel(mode: Seq3AttachmentMode): String = when (mode) {
    Seq3AttachmentMode.SNAPSHOT -> "Attach snapshot"
    Seq3AttachmentMode.LINKED -> "Attach live link"
}

private fun attach(state: AppState, session: Seq3WorkspaceSession, mode: Seq3AttachmentMode) {
    when (mode) {
        Seq3AttachmentMode.SNAPSHOT -> state.seq3Sessions.attachSnapshot(session.id)
        Seq3AttachmentMode.LINKED -> state.seq3Sessions.attachLiveLink(session.id)
    }
}

private val TITLE_FIELD_MAX_WIDTH = 220.dp
private val TITLE_FIELD_MIN_WIDTH = 96.dp
private val TITLE_FIELD_HEIGHT = 24.dp
private const val TITLE_MAX_VISIBLE_CHARS = 28
private const val TITLE_CHARACTER_WIDTH_DP = 7.2f
private const val TITLE_HORIZONTAL_PADDING_DP = 16f

private fun seq3TitleWidth(title: String): androidx.compose.ui.unit.Dp {
    // Keep display and edit modes on the same box so toggling the pencil never changes the
    // title strip's height or causes the controls to jump. The width follows short names closely,
    // but is capped so long names get a stable ellipsis/tooltip affordance.
    val estimated = (title.length * TITLE_CHARACTER_WIDTH_DP + TITLE_HORIZONTAL_PADDING_DP)
        .coerceIn(TITLE_FIELD_MIN_WIDTH.value, TITLE_FIELD_MAX_WIDTH.value)
    return estimated.dp
}

/** The title bar's editable title (item 6c) — double-click to rename, same convention as the
 *  canvas's own inline editors ([Seq3InlineLabelEditor]'s double-click-to-edit label,
 *  [Seq3LifelineChip]'s double-click-to-rename chip, both in `Seq3Canvas.kt`) — PLUS a small pencil
 *  icon button next to the title text (item 1's own "discoverable rename affordance": double-click
 *  alone isn't discoverable without already knowing the convention). Both open the exact same
 *  [Seq3TitleEditor]; the icon is simply a second, visible entry point into it. Commits through the
 *  already-existing [Seq3Session.updateTitle] — which already marks the session dirty and now
 *  note action — never a new session method. */
@Composable
private fun Seq3TitleField(state: AppState, session: Seq3WorkspaceSession) {
    val tc = tc()
    var editing by remember(session.id) { mutableStateOf(false) }
    val fullTitle = session.document.title.ifBlank { "Sequence diagram v3" }
    val titleWidth = seq3TitleWidth(fullTitle)
    if (editing) {
        Seq3TitleEditor(state, session, titleWidth) { editing = false }
    } else {
        val titleBox: @Composable () -> Unit = {
            Box(
                Modifier.width(titleWidth).height(TITLE_FIELD_HEIGHT)
                    .pointerInput(session.id) { detectTapGestures(onDoubleTap = { editing = true }) },
                contentAlignment = Alignment.CenterStart,
            ) {
                DisableSelection {
                    AppText(
                        fullTitle,
                        color = tc.tx,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.height(TITLE_FIELD_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (fullTitle.length > TITLE_MAX_VISIBLE_CHARS) {
                TooltipArea(tooltip = { ToolbarTooltip(fullTitle) }) {
                    titleBox()
                }
            } else {
                titleBox()
            }
            SquareIconButton("✎", fontSize = 10.sp, onClick = { editing = true }, size = 16.dp)
        }
    }
}

@Composable
private fun Seq3TitleEditor(
    state: AppState,
    session: Seq3WorkspaceSession,
    titleWidth: androidx.compose.ui.unit.Dp,
    onDone: () -> Unit,
) {
    var text by remember(session.id) { mutableStateOf(session.document.title) }

    // A blank commit keeps the PREVIOUS value rather than saving an empty title — enforced by
    // [Seq3Session.updateTitle] itself (a blank-after-trim title is silently ignored there), so
    // this editor doesn't duplicate that check.
    fun commit() {
        state.seq3Sessions.updateTitle(session.id, text)
        onDone()
    }
    Row(
        modifier = Modifier.height(TITLE_FIELD_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        InlineField(
            value = text,
            onValue = { text = it },
            fontSize = 13.sp,
            modifier = Modifier.width(titleWidth).height(TITLE_FIELD_HEIGHT),
            centerTextVertically = true,
            onSubmit = ::commit,
        )
        // Reuse the same small, unbordered note-action controls for save/cancel so the title
        // editor does not introduce a second button language in the title strip.
        SquareIconButton("✓", fontSize = 12.sp, onClick = ::commit, size = 18.dp)
        SquareIconButton("×", fontSize = 14.sp, onClick = onDone, size = 18.dp)
    }
}

/** Pure bounds-clamping behind the queue-panel/inspector divider drags (item 14) — same
 *  split-out-for-testability rationale as [seq3KeyAction]/[seq3ScopeMenuState], so
 *  [Seq3KeyActionTest] can assert it directly without a composition. [current] and [delta] are
 *  both dp-equivalent Floats, matching what [HDivider]'s own `onDelta` callback already hands
 *  back (it divides the raw pixel drag by density before calling out). */
internal fun seq3ClampDividerWidth(current: Float, delta: Float, min: Float, max: Float): Float =
    (current + delta).coerceIn(min, max)

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

    /** Queue-panel width (dp), drag-resized via the [HDivider] in [Seq3Workspace]'s main `Row`.
     *  The Inspector now lives inside that same panel and owns a vertical height preference. */
    var panelWidthDp by mutableStateOf(PANEL_WIDTH.value)

    /** Whether the Messages + Inspector sidebar is visible. The diagram remains usable full-width
     *  when this is collapsed; it is a view preference and is never persisted into the document. */
    var sidebarOpen by mutableStateOf(true)

    /** Whether the stacked Messages and Inspector sections are expanded. These are view-only
     *  preferences, so collapsing either section never changes the document or its undo history. */
    var messagesExpanded by mutableStateOf(true)
    var inspectorExpanded by mutableStateOf(true)

    /** Inspector body height in dp. [VDivider] changes this with the same cursor-driven resize
     *  affordance used by the app's other split panels. */
    var inspectorHeightDp by mutableStateOf(320f)

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

    /** Item 4's Esc-reliability fix: the workspace root's own [androidx.compose.ui.focus.
     *  FocusRequester], kept here (rather than a separately `remember`ed local in [Seq3Workspace])
     *  so every composable already holding a `view: Seq3ViewState` — the queue-row press/checkbox
     *  handlers, [Seq3DropdownButton] via [LocalSeq3FocusRequester] — can reclaim keyboard focus
     *  after a click without a new parameter threaded through every call site. Plain `val`, not
     *  `mutableStateOf`: a [androidx.compose.ui.focus.FocusRequester] is itself a mutable handle,
     *  not a value Compose needs to observe for recomposition. */
    val focusRequester = FocusRequester()
}

internal enum class Seq3ZoomMode { FIT, FIT_WIDTH, MANUAL }

/** Item 4's Esc-reliability fix (see [Seq3ViewState.focusRequester]'s own doc): makes the
 *  workspace's focus requester reachable from [Seq3DropdownButton] without adding a parameter to
 *  its 9 call sites across this file, `Seq3QueuePanel.kt`, `Seq3Canvas.kt` and
 *  `Seq3RegenerateSheet.kt`. Null outside a v3 workspace (there is no requester to reclaim). */
internal val LocalSeq3FocusRequester = compositionLocalOf<FocusRequester?> { null }

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
    // Item 1 (phase-5 round 2): almost every call site should read as an ordinary at-rest control —
    // transparent until opened, tinted on hover via HoverBox's own hover layer, `fillColor` only
    // while the menu is `open` (mirrors PillBtn's `active` tint). The one exception is a genuine
    // semantic warning highlight riding on this same component — Seq3QueuePanel.kt's "set target"
    // chip — which must stay filled regardless of open/hover state; that call site passes
    // `alwaysFilled = true` instead of relying on `open`.
    alwaysFilled: Boolean = false,
    menuWidth: androidx.compose.ui.unit.Dp = 160.dp,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    val tc = tc()
    val density = LocalDensity.current
    val focusRequester = LocalSeq3FocusRequester.current
    var open by remember { mutableStateOf(false) }
    var suppressUntilMs by remember { mutableStateOf(0L) }

    // Item 4's Esc-reliability fix: a dismissed Popup doesn't hand keyboard focus back to the
    // workspace root on its own — without this, Esc (and every other §09 key) would silently stop
    // firing after the first dropdown open/close. Shared by both ways this dropdown closes (picking
    // a menu item below, or dismissing via an outside click/Popup's own onDismissRequest).
    fun closeAndReclaimFocus() {
        open = false
        suppressUntilMs = System.currentTimeMillis() + 200
        focusRequester?.let { runCatching { it.requestFocus() } }
    }
    Box(modifier) {
        HoverBox(
            // Keep the padding inside the clickable Box. `HoverBox` appends its clickable
            // modifier after the supplied modifier, so padding on the supplied chain would
            // leave only the label text as the effective hit target (the same bug users see
            // in the Log order control). The log-view pills put their padding inside the
            // clickable surface; keep this shared dropdown consistent with that behavior.
            modifier = Modifier.clip(CORNER_MD).background(if (open || alwaysFilled) fillColor else Color.Transparent, CORNER_MD)
                .border(1.dp, tc.br, CORNER_MD),
            onClick = { if (System.currentTimeMillis() >= suppressUntilMs) open = !open },
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                DisableSelection {
                    AppText(label, color = labelColor, fontSize = 11.sp)
                    AppText("▾", color = labelColor.copy(alpha = .7f), fontSize = 9.sp)
                }
            }
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { 26.dp.roundToPx() }),
                onDismissRequest = ::closeAndReclaimFocus,
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    Modifier.width(menuWidth)
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .shadow(8.dp, RoundedCornerShape(8.dp))
                        .background(tc.p, RoundedCornerShape(8.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(8.dp))
                        .padding(4.dp),
                ) {
                    menu(::closeAndReclaimFocus)
                }
            }
        }
    }
}

/** [enabled] (item 4a's "only enabled/selectable when that selection is non-empty") disables both
 *  the click and the hover highlight, dimming the label the same way a disabled row does everywhere
 *  else in this app — added for the title-bar scope dropdown's "Selection" item, defaults to `true`
 *  so every pre-existing call site (Seq3RegenScopeControls' own "Whole view"/"Selection") is
 *  unaffected. */
@Composable
internal fun Seq3DropdownMenuItem(label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)),
        baseBg = if (active) tc.abg else Color.Transparent,
        onClick = if (enabled) onClick else null,
    ) {
        DisableSelection {
            AppText(
                label,
                color = when {
                    !enabled -> tc.td
                    active -> tc.ac
                    else -> tc.tx
                },
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
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

/** "Draft saved 2 min ago" after the explicit note action / "Unsaved changes" while edits are
 *  pending / blank before the first save. Not `@Composable` — evaluated fresh on every recomposition. */
internal fun draftStatusLabel(session: Seq3WorkspaceSession): String = when {
    session.dirty -> "Unsaved changes"
    session.draftSavedAtMillis == null -> ""
    else -> {
        val ageMs = (System.currentTimeMillis() - session.draftSavedAtMillis).coerceAtLeast(0)
        val minutes = ageMs / MILLIS_PER_MINUTE
        if (minutes <= 0) "Draft saved just now" else "Draft saved ${minutes}m ago"
    }
}
