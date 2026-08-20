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
import com.indagium.diagram3.Seq3Box
import com.indagium.diagram3.Seq3BulkAction
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Delay
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Filter
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3GuidedPassState
import com.indagium.diagram3.Seq3Note
import com.indagium.diagram3.Seq3OccurrenceRef
import com.indagium.diagram3.Seq3Selection
import com.indagium.diagram3.Seq3Sort
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.seq3MessageIdsAreContiguous
import com.indagium.model.ThemePreset
import java.util.UUID

// ── v3 workspace shell (phase 4) ────────────────────────────────────────────────────────────
//
// Phase 3 delivered a skeleton (title bar, panel/canvas split, footer) with placeholder rows and
// a rasterized-bitmap canvas so generation was visible end to end. This phase replaces both
// placeholders with the real spec §04/§06/§07 queue panel and a NATIVE Compose canvas (no more
// bitmap image — see Seq3Canvas.kt's own header for why), and keeps message details inside the
// queue rows so the workspace has no separate details pane.
//
// All colors still come from [ThemeColors] (via [tc]) — including warn/warnBg/ok through
// [toSeq3RasterTheme] where the raster theme is still needed (Seq3RenderCache.layout, the PNG
// export path) — never a hardcoded hex, so this reads correctly in every preset, dark ones
// included (see the v3 rewrite plan's palette decision).

private val PANEL_WIDTH = 392.dp

// Item 14 — drag bounds for the queue-panel divider. The panel width stays the seed value a
// freshly-opened workspace starts at.
private const val PANEL_WIDTH_MIN_DP = 280f
private const val PANEL_WIDTH_MAX_DP = 560f

@Composable
fun Seq3Workspace(state: AppState, sessionId: String) {
    val session = state.seq3Sessions.sessions.firstOrNull { it.id == sessionId } ?: return
    val tc = tc()
    // Session-scoped, ephemeral VIEW state — see [Seq3ViewState]'s own doc for why this is never
    // part of [Seq3WorkspaceSession]. Seq3Session owns the instance so it survives this surface
    // leaving composition when the user switches to another tab.
    // [Seq3ViewState.focusRequester] lives ON the view (not a separately remembered local) so every
    // composable already holding `view` — the row press/checkbox handlers in Seq3QueuePanel.kt,
    // [Seq3DropdownButton] via [LocalSeq3FocusRequester] below — can reclaim keyboard focus after a
    // click without a new parameter threaded through every call site.
    val view = state.seq3Sessions.viewState(sessionId) ?: return
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
        Column(Modifier.width(240.dp)) {
            Seq3TitleField(state, session)
            AppText(subtitle, color = tc.ts, fontSize = 11.sp)
        }
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val selectedIds = seq3SelectedMessageIds(session.document, view)
            if (selectedIds.isNotEmpty()) {
                Seq3ContextualSelectionActions(state, session, view, selectedIds)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ToolbarBtn(
                label = "≡",
                tooltip = if (view.sidebarOpen) "Hide Messages" else "Show Messages",
                active = view.sidebarOpen,
                modifier = Modifier.size(28.dp),
                shape = CORNER_SM,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                onClick = { view.sidebarOpen = !view.sidebarOpen },
            )
            ToolbarBtn(
                label = "⇅",
                tooltip = if (view.lifelinesSectionOpen) "Hide Lifelines" else "Show Lifelines",
                active = view.lifelinesSectionOpen,
                modifier = Modifier.size(28.dp),
                shape = CORNER_SM,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                onClick = { view.lifelinesSectionOpen = !view.lifelinesSectionOpen },
            )
            ToolbarBtn(
                label = "▦",
                tooltip = if (view.artifactsSectionOpen) "Hide Fragments & notes" else "Show Fragments & notes",
                active = view.artifactsSectionOpen,
                modifier = Modifier.size(28.dp),
                shape = CORNER_SM,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                onClick = { view.artifactsSectionOpen = !view.artifactsSectionOpen },
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
            Seq3InlinePrefixToggles(state, session)
            Seq3DocumentThemeDropdown(state, session)
            if (session.generating) AppText("Generating…", color = tc.ts, fontSize = 11.sp)
        }
    }
}

/**
 * WP10 (item 7): two independent document-level toggles, beside the dialect control they visually
 * pair with — "how does this diagram present itself" toolbar controls, same slot as
 * [Seq3DocumentThemeDropdown]. Each dispatches its own [Seq3Command] so `⌘Z` undoes them
 * independently, and both are document fields (not view state) precisely because the user wants
 * the canvas, the PNG export, and the exported text to always agree on whether a call's `[#n]`/
 * `[ts]` prefix is showing.
 */
@Composable
private fun Seq3InlinePrefixToggles(state: AppState, session: Seq3WorkspaceSession) {
    val document = session.document
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToolbarBtn(
            label = "#",
            tooltip = if (document.showSequenceNumbers) "Hide call numbers" else "Show call numbers",
            active = document.showSequenceNumbers,
            modifier = Modifier.size(28.dp),
            shape = CORNER_SM,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            onClick = {
                state.seq3Sessions.applyCommand(session.id, Seq3Command.SetShowSequenceNumbers(!document.showSequenceNumbers))
            },
        )
        ToolbarBtn(
            label = "⏱",
            tooltip = if (document.showTimestamps) "Hide timestamps" else "Show timestamps",
            active = document.showTimestamps,
            modifier = Modifier.size(28.dp),
            shape = CORNER_SM,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            onClick = {
                state.seq3Sessions.applyCommand(session.id, Seq3Command.SetShowTimestamps(!document.showTimestamps))
            },
        )
    }
}

/**
 * WP4/WP8: per-diagram theme picker, beside the PlantUML/Mermaid dialect control it visually
 * pairs with — both are "how does this diagram present itself" toolbar controls. *Follow app
 * theme* (`null`) plus all 20 [ThemePreset.entries], as the same [ThemeGallery] card grid Settings
 * uses — the user wants to *see* what they're picking, not read a 21-row text menu — dispatching
 * [Seq3Command.SetDocumentTheme]. Reuses [Seq3DropdownButton] (not a hand-rolled Popup) precisely
 * for its [closeAndReclaimFocus] handling — see this file's own header comment on
 * [LocalSeq3FocusRequester] for why a hand-rolled popup here would silently kill the workspace's
 * root key handler (Esc included) after the first click. `menuWidth` is widened to fit ~3 cards
 * per row (118dp cards + 8dp gaps ≈ 370dp, plus the gallery's own scrollbar gutter and the popup's
 * padding).
 *
 * WP-theme-badge, round 5 (user-observed correction): the trigger is the [Seq3ThemeSwatch]
 * mini-card alone — round 3's stacked name label is gone, since the swatch already shows the
 * theme and the label made this control roughly three times wider than every other toolbar
 * button. The name is not surfaced at rest at all; a plain "Choose diagram theme" [TooltipArea]
 * explains the control instead. The swatch keeps its own landscape shape, padded out to the same
 * 28dp height as its `≡`/`⇅`/`▦`/`#`/`⏱` siblings rather than forced into their square.
 */
@Composable
private fun Seq3DocumentThemeDropdown(state: AppState, session: Seq3WorkspaceSession) {
    val themePresetName = session.document.themePresetName
    val selected = themePresetName?.let { name -> runCatching { ThemePreset.valueOf(name) }.getOrNull() }
    val swatchColors = resolveSeq3ThemeColors(session.document, state.settings)
    TooltipArea(tooltip = { ToolbarTooltip("Choose diagram theme") }) {
        Seq3DropdownButton(
            label = "theme",
            fillColor = tc().p2,
            menuWidth = 400.dp,
            // Round 6: only a 1dp inset, just enough to keep the chrome's own 1dp border
            // visible — the swatch is meant to *be* the button face, not a small icon floating
            // inside it, so its 42x26dp fills the 44x28dp footprint its toolbar siblings occupy.
            anchorContent = {
                Box(Modifier.padding(1.dp)) { Seq3ThemeSwatch(swatchColors) }
            },
        ) { close ->
            ThemeGallery(
                settings = state.settings,
                selected = selected,
                onSelect = { preset ->
                    state.seq3Sessions.applyCommand(session.id, Seq3Command.SetDocumentTheme(preset?.name))
                    close()
                },
                followAppTheme = true,
            )
        }
    }
}

/** WP-theme-badge polish, round 6 (user-observed correction): rounds 2–3 built this as a 22dp
 *  *square*, which squeezed [ThemeWindowCard]'s 118×66 landscape composition into an aspect ratio
 *  the card itself never has, and round 4's 36×22 still sat inside a 4dp/3dp inset that left a
 *  visible dead margin between the swatch and the button border. This is now the button face:
 *  42×26dp inside a 44×28dp trigger (its `≡`/`⇅`/`▦`/`#`/`⏱` siblings' footprint), leaving only
 *  the 1dp the chrome's border needs. Every element is scaled independently rather than
 *  pixel-scaled uniformly — a literal ~35% scale of the card's 4dp rail and 8dp swatches would
 *  still read as mush — while the card's own proportions are kept: an outer [ThemeColors.bg]
 *  frame, a `p` title strip at the card's ≈21% height ratio with its ac/seq1/seq2 dot trio, and
 *  a `p2` body pane with the left accent rail and bottom-right swatch pair. No text: the theme's
 *  name lives in [Seq3DocumentThemeDropdown]'s tooltip. */
@Composable
private fun Seq3ThemeSwatch(colors: ThemeColors) {
    Column(
        Modifier.width(42.dp).height(26.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(colors.bg)
            .padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(1.5.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(6.dp)
                .background(colors.p, RoundedCornerShape(2.dp))
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(colors.ac, colors.seq1, colors.seq2).forEach { color ->
                Box(Modifier.size(2.5.dp).background(color, RoundedCornerShape(50)))
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(colors.p2, RoundedCornerShape(2.dp))) {
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxHeight().width(3.dp)
                    .background(colors.ac, RoundedCornerShape(1.dp)),
            )
            Row(
                Modifier.align(Alignment.BottomEnd).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            ) {
                Box(Modifier.size(4.dp).background(colors.seq1, RoundedCornerShape(1.dp)))
                Box(Modifier.size(4.dp).background(colors.seq2, RoundedCornerShape(1.dp)))
            }
        }
    }
}

@Composable
private fun Seq3ContextualSelectionActions(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    selectedIds: Set<String>,
) {
    val tc = tc()
    val document = session.document
    val selectedRowCount = if (view.selectedCanvasRows.isNotEmpty()) {
        view.selectedCanvasRows.size
    } else {
        selectedIds.size
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selectedRowCount == 1 && selectedIds.size == 1) {
            Seq3TitleActionButton("Rename", "Rename selected line") {
                seq3BeginLabelRename(view, document, selectedIds.single())
            }
            Seq3TitleActionButton("Note", "Add note for selected line") {
                seq3AddNote(state, session, view, document, selectedIds)
            }
        } else if (seq3CanGroupSelection(document, view, selectedIds)) {
            Seq3DropdownButton(
                label = "Group",
                labelColor = tc.tx,
                fillColor = tc.p2,
                menuWidth = 130.dp,
            ) { close ->
                Seq3FragmentKind.entries.forEach { kind ->
                    Seq3DropdownMenuItem(kind.name.lowercase()) {
                        seq3GroupMessages(state, session, view, selectedIds, kind)
                        close()
                    }
                }
            }
        }
        Seq3TitleActionButton("Clear", "Clear selection") {
            seq3ClearSelection(view)
        }
    }
}

@Composable
private fun Seq3TitleActionButton(label: String, tooltip: String, onClick: () -> Unit) {
    ToolbarBtn(
        label = label,
        tooltip = tooltip,
        shape = CORNER_SM,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        onClick = onClick,
    )
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
// Compose's density is already applied to this estimate. The old 7.2dp/character value made a
// short title reserve roughly twice its rendered width on Retina displays, leaving the pencil
// visibly detached from the name. Keep a little trailing room for the text caret/ellipsis while
// letting the icon sit immediately after ordinary titles.
private const val TITLE_CHARACTER_WIDTH_DP = 4.8f
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

/** Pure bounds-clamping behind the queue-panel divider drags (item 14) — same
 *  split-out-for-testability rationale as [seq3KeyAction]/[seq3ScopeMenuState], so
 *  [Seq3KeyActionTest] can assert it directly without a composition. [current] and [delta] are
 *  both dp-equivalent Floats, matching what [HDivider]'s own `onDelta` callback already hands
 *  back (it divides the raw pixel drag by density before calling out). */
internal fun seq3ClampDividerWidth(current: Float, delta: Float, min: Float, max: Float): Float =
    (current + delta).coerceIn(min, max)

// ── Shared ephemeral view state (queue<->canvas) ──────────────────────────────────────────────

/**
 * Session-scoped VIEW state shared between [Seq3QueuePanel] and [Seq3Canvas] —
 * filter/sort/selection/hover/zoom/focused message. Deliberately never part of
 * [Seq3WorkspaceSession]: spec §07's "sort is a view, never an edit" reasoning extends to every
 * field here — none of it is undo-tracked, none of it is written to a note, and losing it when a
 * session is closed and reopened is correct (the same way a browser tab's own scroll position
 * isn't persisted either). Every EDIT that reads this class still only ever reaches the document
 * through [Seq3Session.applyCommand] — this class itself never holds a [com.indagium.diagram3.
 * Seq3Document].
 */
/** One exact row drawn by the canvas. A null occurrence id identifies a standalone/authored row;
 *  a non-null id distinguishes one occurrence from the other rows of the same repeated message. */
internal data class Seq3CanvasRowRef(val messageId: String, val occurrenceEntryId: Int?)

internal class Seq3ViewState {
    var selection by mutableStateOf(Seq3Selection())
    /** Expanded repeated-message rows in the queue. This is view state only; grouping remains part
     *  of the durable message and the extracted occurrence command is the only document edit. */
    var expandedOccurrenceMessageIds by mutableStateOf<Set<String>>(emptySet())
    /** Expanded per-message Pattern/Label details. This is independent from occurrence expansion
     *  so both disclosures can remain open at once, with details rendered first. */
    var expandedInfoMessageIds by mutableStateOf<Set<String>>(emptySet())
    /** Independent checkbox state for the evidence rows shown inside an expanded group. */
    var selectedOccurrenceIds by mutableStateOf<Set<String>>(emptySet())
    /** The queue row selected by its message body. When set, the canvas emphasizes this exact
     *  occurrence instead of every drawn occurrence owned by the message. */
    var selectedOccurrenceMessageId by mutableStateOf<String?>(null)
    var selectedOccurrenceEntryId by mutableStateOf<Int?>(null)
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

    /** The message currently focused by a queue/canvas click. This drives the cross-surface
     *  highlight and keyboard actions; it is not a selection and does not affect checkboxes. */
    var focusedMessageId by mutableStateOf<String?>(null)

    /** Canvas double-click inline label editor target (spec §04). */
    var editingLabelMessageId by mutableStateOf<String?>(null)
    /** The exact repeated occurrence whose label editor is open; prevents one editor per drawn
     *  occurrence when a message is expanded on the canvas. */
    var editingLabelOccurrenceEntryId by mutableStateOf<Int?>(null)

    /** The lifeline header chip drawn in the accent color (spec §04's "the selected one in
     *  accent"). Purely a highlight — never gates which lifelines a dropdown/drag can target.
     *  WP3 reuses this as the lifeline PANEL row's "focused" state (Seq3QueueRow's own
     *  `focusedMessageId` counterpart) — set by a plain click on the row body, independent from
     *  the checkbox-driven [selectedLifelineIds] below. */
    var selectedLifelineId by mutableStateOf<String?>(null)
    /** Lifelines checked in the panel for a multi-lifeline operation such as Merge. */
    var selectedLifelineIds by mutableStateOf<Set<String>>(emptySet())
    /** Two-way row<->column hover for the Lifelines panel section, mirroring
     *  [hoveredMessageId] for the Messages queue. */
    var hoveredLifelineId by mutableStateOf<String?>(null)

    /** Rectangle currently being dragged on the canvas to select multiple message rows. */
    var canvasSelectionRect by mutableStateOf<Seq3Box?>(null)
    /** Once enabled by the toolbar toggle, primary-button drags pan the view. */
    var canvasPanMode by mutableStateOf(false)
    /** True when the current message selection came from a marquee, which may intentionally span
     * hidden/non-rendered rows while still representing one visible rectangle on the canvas. */
    var selectionFromMarquee by mutableStateOf(false)
    /** Exact rows selected by the canvas rectangle. Kept separate from queue checkbox selection:
     *  a repeated queue message can contribute one selected arrow without selecting all of its
     *  other occurrences. */
    var selectedCanvasRows by mutableStateOf<Set<Seq3CanvasRowRef>>(emptySet())

    /** WP11 auto-suggest: `afterMessageId`s of a [com.indagium.diagram3.Seq3DelaySuggestion] the
     *  user dismissed this session. View-only, never persisted — the round-2 corrections plan's
     *  "manual insert plus auto-suggest, explicitly not silent automatic insertion" only requires
     *  a dismissible OFFER, not a durable "never ask about this gap again" preference; re-showing
     *  a dismissed suggestion after a reload is an acceptable, much simpler trade-off. */
    var dismissedDelaySuggestionAfterIds by mutableStateOf<Set<String>>(emptySet())

    /** Right-click menu state for a canvas message row. */
    var canvasContextMenuMessageId by mutableStateOf<String?>(null)
    /** Exact repeated occurrence under the right-clicked canvas arrow, when there is one. */
    var canvasContextMenuOccurrenceEntryId by mutableStateOf<Int?>(null)
    var canvasContextMenuOffset by mutableStateOf(IntOffset.Zero)
    var canvasContextMenuCanvasPoint by mutableStateOf(Seq3Box(0.0, 0.0, 0.0, 0.0))
    /** WP7 item 5 (canvas half): the empty-canvas right-click menu ("Add note here") — open when
     *  the click hit no message row. Mutually exclusive with [canvasContextMenuMessageId]; shares
     *  [canvasContextMenuOffset]/[canvasContextMenuCanvasPoint] for position since only one of the
     *  two context menus is ever open at once. */
    var canvasEmptyContextMenuOpen by mutableStateOf(false)

    var zoom by mutableStateOf(1f)
    var zoomMode by mutableStateOf(Seq3ZoomMode.FIT_WIDTH)

    /** Queue-panel width (dp), drag-resized via the [HDivider] in [Seq3Workspace]'s main `Row`. */
    var panelWidthDp by mutableStateOf(PANEL_WIDTH.value)

    /** Whether the Messages sidebar is visible. The diagram remains usable full-width when this is
     *  collapsed; it is a view preference and is never persisted into the document. */
    var sidebarOpen by mutableStateOf(true)

    /** Whether the Messages section is expanded. This is a view-only preference, so collapsing it
     *  never changes the document or its undo history. */
    var messagesExpanded by mutableStateOf(true)

    /** Whether the Lifelines section is expanded. This is a panel-only preference and never enters
     *  the saved diagram or undo history. */
    var lifelinesExpanded by mutableStateOf(true)

    /** Whether the Lifelines section is visible in the queue panel. */
    var lifelinesSectionOpen by mutableStateOf(true)

    /** Height of the expanded Lifelines section, adjusted by the horizontal panel divider. */
    var lifelinesSectionHeightDp by mutableStateOf(220f)

    /** Whether the Fragments & notes section (WP3 item 9 — promoted out of the Messages area into
     *  its own top-level panel section) is expanded. Panel-only, never enters the saved diagram or
     *  undo history — same contract as [lifelinesExpanded]. */
    var artifactsExpanded by mutableStateOf(false)

    /** Height of the expanded Fragments & notes section, adjusted by its own horizontal panel
     *  divider — the [artifactsSectionHeightDp] counterpart of [lifelinesSectionHeightDp]. */
    var artifactsSectionHeightDp by mutableStateOf(200f)

    /** WP8: whether the Fragments & notes section is visible in the queue panel at all — the
     *  [artifactsSectionOpen] counterpart of [lifelinesSectionOpen] (toggled the same way, via a
     *  title-bar [ToolbarBtn]). Independent from [artifactsExpanded], which only squishes an
     *  already-visible section down to its header row. Panel-only; never enters the saved diagram
     *  or undo history. */
    var artifactsSectionOpen by mutableStateOf(true)

    /** WP8: the fragment selected by clicking a Fragments & notes panel row's body. The row used
     *  to carry a checkbox that drove a bare local `remember` — no shared selection, no bulk
     *  action, no canvas link, and it wasn't clear what it did — so it was removed in favor of
     *  making the row body itself clickable. Panel-only; never enters the saved diagram or undo
     *  history. WP7: `Seq3Canvas`'s `drawSeq3Diagram` reads this (via `seq3FragmentIsEmphasized`)
     *  to draw that fragment's bracket with the same accent-stroke emphasis a selected message row
     *  already gets. */
    var selectedFragmentId by mutableStateOf<String?>(null)
    /** The note counterpart of [selectedFragmentId] above — same contract. */
    var selectedNoteId by mutableStateOf<String?>(null)
    /** Two-way row<->canvas hover for a Fragments & notes panel row, mirroring
     *  [hoveredMessageId]/[hoveredLifelineId]. Panel-only; never enters the saved diagram or undo
     *  history. WP7: consumed by `Seq3Canvas` the same way [selectedFragmentId] is. */
    var hoveredFragmentId by mutableStateOf<String?>(null)
    /** The note counterpart of [hoveredFragmentId] above — same contract. */
    var hoveredNoteId by mutableStateOf<String?>(null)

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

/** Message ids selected across the queue, canvas, and occurrence checkboxes. */
internal fun seq3SelectedMessageIds(document: Seq3Document, view: Seq3ViewState): Set<String> {
    val occurrenceMessageIds = document.messages.flatMap { message ->
        message.occurrences.mapNotNull { occurrence ->
            val key = "${message.id}::${occurrence.entryId}"
            message.id.takeIf { key in view.selectedOccurrenceIds }
        }
    }
    val canvasMessageIds = view.selectedCanvasRows.map { it.messageId }
    return (view.selection.selectedIds + occurrenceMessageIds + canvasMessageIds)
        .filterTo(linkedSetOf()) { id -> document.messages.any { it.id == id } }
}

/** Exact occurrence rows selected by a canvas marquee/Cmd-click or by submessage checkboxes. */
internal fun seq3SelectedOccurrenceRefs(document: Seq3Document, view: Seq3ViewState): List<Seq3OccurrenceRef> {
    val canvasRefs = view.selectedCanvasRows.mapNotNull { row ->
        row.occurrenceEntryId?.let { Seq3OccurrenceRef(row.messageId, it) }
    }
    val checkedRefs = document.messages.flatMap { message ->
        message.occurrences.mapNotNull { occurrence ->
            val key = "${message.id}::${occurrence.entryId}"
            occurrence.entryId.takeIf { key in view.selectedOccurrenceIds }
                ?.let { Seq3OccurrenceRef(message.id, it) }
        }
    }
    return (canvasRefs + checkedRefs).distinct()
}

/** All selection surfaces expose the same "at least two rows" grouping affordance. */
internal fun seq3CanGroupSelection(
    document: Seq3Document,
    view: Seq3ViewState,
    selectedIds: Set<String>,
): Boolean {
    val exactRowCount = if (view.selectedCanvasRows.isNotEmpty()) {
        view.selectedCanvasRows.size
    } else {
        seq3SelectedOccurrenceRefs(document, view).size
    }
    return exactRowCount >= 2 || (exactRowCount == 0 && seq3MessageIdsAreContiguous(document, selectedIds))
}

internal fun seq3ClearSelection(view: Seq3ViewState, clearFocus: Boolean = false) {
    view.selection = Seq3Selection()
    view.selectionFromMarquee = false
    view.selectedCanvasRows = emptySet()
    view.selectedOccurrenceIds = emptySet()
    view.selectedOccurrenceMessageId = null
    view.selectedOccurrenceEntryId = null
    view.hoveredMessageId = null
    if (clearFocus) {
        view.focusedMessageId = null
        // Item 4 (WP-panel-toggle): every "clicked empty canvas background" site in Seq3Canvas.kt
        // already calls this with clearFocus=true to drop the message focus/highlight — fold the
        // panel-only fragment/note/lifeline selections into that same "clicked away" gesture so a
        // click on empty canvas space deselects a panel-selected row the same way it already
        // deselects a message. Seq3LifelineChip's own onPress calls `seq3ClearSelection(view,
        // clearFocus = true)` immediately before re-setting `view.selectedLifelineId =
        // column.lifelineId` on the SAME press — clearing it here and re-setting it on the very
        // next line is fine (see that call site).
        view.selectedFragmentId = null
        view.selectedNoteId = null
        view.selectedLifelineId = null
    }
}

/** Toggle-to-deselect (item 4, WP-panel-toggle): clicking the already-selected fragment/note/
 *  lifeline row a second time clears it, matching how message selection already behaves. Each
 *  row's own `onSelect`/click handler in `Seq3QueuePanel.kt` calls the matching one of these
 *  three instead of unconditionally assigning the id. */
internal fun seq3ToggleFragmentSelection(view: Seq3ViewState, fragmentId: String) {
    view.selectedFragmentId = if (view.selectedFragmentId == fragmentId) null else fragmentId
}

/** The note counterpart of [seq3ToggleFragmentSelection] above — same contract. */
internal fun seq3ToggleNoteSelection(view: Seq3ViewState, noteId: String) {
    view.selectedNoteId = if (view.selectedNoteId == noteId) null else noteId
}

/** The lifeline counterpart of [seq3ToggleFragmentSelection] above — same contract. This is the
 *  panel row's plain-click FOCUS toggle ([Seq3ViewState.selectedLifelineId]), independent from the
 *  checkbox-driven [Seq3ViewState.selectedLifelineIds] multi-select. */
internal fun seq3ToggleLifelineSelection(view: Seq3ViewState, lifelineId: String) {
    view.selectedLifelineId = if (view.selectedLifelineId == lifelineId) null else lifelineId
}

internal fun seq3BeginLabelRename(view: Seq3ViewState, document: Seq3Document, messageId: String): Boolean {
    val message = document.messages.firstOrNull { it.id == messageId } ?: return false
    view.focusedMessageId = messageId
    view.editingLabelMessageId = messageId
    view.editingLabelOccurrenceEntryId = message.occurrences.firstOrNull()?.entryId
    view.canvasContextMenuMessageId = null
    view.canvasContextMenuOccurrenceEntryId = null
    return true
}

internal fun seq3AddNote(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    document: Seq3Document,
    selectedIds: Set<String>,
    placement: Seq3Box? = null,
): Boolean {
    val ids = document.messages.map { it.id }.filter { it in selectedIds }
    // WP7 item 3: the empty-canvas "Add note here" menu has no message selection to span at all —
    // a FREE-FLOATING note (empty messageIds, explicit x/y from the click) is the only way to add
    // one there. An anchored note (>=1 message) still requires a selection, same as before.
    if (ids.isEmpty() && placement == null) return false
    val note = Seq3Note(
        id = "note-${UUID.randomUUID()}",
        text = "Note",
        messageIds = ids,
        x = placement?.x,
        y = placement?.y,
        width = placement?.let { 220.0 },
        height = placement?.let { 72.0 },
    )
    val applied = state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(ids.toSet(), Seq3BulkAction.Note(note)))
    if (applied) {
        view.canvasContextMenuMessageId = null
        view.canvasContextMenuOccurrenceEntryId = null
        view.canvasEmptyContextMenuOpen = false
        view.canvasContextMenuCanvasPoint = Seq3Box(0.0, 0.0, 0.0, 0.0)
    }
    return applied
}

/** Default free-floating placement for the Fragments & notes panel header's "+ note" button
 *  (`Seq3QueuePanel.kt`'s `Seq3FragmentsAndNotesSection`) when nothing is selected. The canvas's
 *  own empty-context-menu "Add note here" anchors a free-floating note to the exact right-click
 *  point (see `Seq3Canvas.kt`'s `Seq3CanvasEmptyContextMenu`); a panel button press has no click
 *  point to anchor to, so this derives a position from the diagram's own overall size
 *  ([Seq3RenderCache.layout]) instead — horizontally centered, a small margin down from the top —
 *  so the note lands somewhere on the visible diagram rather than off it, for a diagram of any
 *  size (including an empty one, where `layout.width` is 0 and the `coerceAtLeast(0.0)` keeps `x`
 *  from going negative). */
internal fun seq3DefaultNotePlacement(document: Seq3Document): Seq3Box {
    val layout = Seq3RenderCache.layout(document)
    val noteWidth = 220.0
    val x = ((layout.width - noteWidth) / 2.0).coerceAtLeast(0.0)
    val y = 24.0
    return Seq3Box(x, y, 0.0, 0.0)
}

/** Manual insert (WP11, "Insert delay after this" — canvas context menu and the `+ message`
 *  menu both call this): anchors a new [Seq3Delay] right after [afterMessageId]'s own last drawn
 *  row. [label] defaults to a generic placeholder, editable in place afterward via
 *  [Seq3BulkAction.SetDelayLabel] (the canvas overlay's double-click-to-rename, same pattern as
 *  a fragment/note label) — this function's own job is only to create it, matching
 *  [seq3AddNote]'s "caller mints the id, this fires the bulk action" shape. */
internal fun seq3InsertDelayAfter(
    state: AppState,
    session: Seq3WorkspaceSession,
    afterMessageId: String,
    label: String = "delay",
): Boolean {
    val delay = Seq3Delay(id = "seq3-delay-${UUID.randomUUID()}", afterMessageId = afterMessageId, label = label)
    return state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(emptySet(), Seq3BulkAction.AddDelay(delay)))
}

internal fun seq3GroupMessages(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    selectedIds: Set<String>,
    kind: Seq3FragmentKind,
): Boolean {
    val orderedIds = session.document.messages.map { it.id }.filter { it in selectedIds }
    if (!seq3CanGroupSelection(session.document, view, selectedIds)) return false
    val hasExactRows = view.selectedCanvasRows.isNotEmpty() || view.selectedOccurrenceIds.isNotEmpty()
    val exactOccurrenceRefs = seq3SelectedOccurrenceRefs(session.document, view)
    val exactMessageIds = exactOccurrenceRefs.mapTo(hashSetOf()) { it.messageId }
    val fallbackMessageIds = if (!hasExactRows) {
        orderedIds
    } else {
        orderedIds.filterNot { it in exactMessageIds }
    }
    val fragment = Seq3Fragment(
        id = "seq3-fragment-${UUID.randomUUID()}",
        kind = kind,
        label = kind.name.lowercase(),
        messageIds = fallbackMessageIds,
        occurrenceRefs = exactOccurrenceRefs,
    )
    val applied = state.seq3Sessions.applyCommand(
        session.id,
        Seq3Command.Bulk(selectedIds, Seq3BulkAction.Group(fragment)),
    )
    if (applied) seq3ClearSelection(view)
    return applied
}

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
    fixedHeight: androidx.compose.ui.unit.Dp? = null,
    // WP-theme-badge: lets a call site substitute its own trigger surface (e.g. the compact theme
    // swatch below) for the default "label ▾" Row. Everything else about the dropdown — the click
    // handling, the open/fillColor background, the Popup, closeAndReclaimFocus — stays identical;
    // only the visible content of the trigger changes. `null` (the default) keeps every pre-existing
    // call site rendering exactly as before.
    anchorContent: (@Composable () -> Unit)? = null,
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
    val fixedHeightModifier = fixedHeight?.let { Modifier.height(it) } ?: Modifier
    // WP-theme-badge sizing fix: this only ever filled HEIGHT, so a compact `anchorContent`
    // trigger (the theme badge) hugged its own intrinsic content width (the 22dp swatch) instead
    // of the full square the outer Box(modifier.then(fixedHeightModifier)) reserves — the badge
    // rendered narrower than its 28dp `≡`/`⇅`/`▦`/`#`/`⏱` toolbar siblings. Gated on
    // `anchorContent != null`: the default label+▾ Row call sites (Seq3QueuePanel.kt's lifeline
    // kind/display-segments/fragment-kind pickers, Seq3MessageKindPicker) sit inside a
    // `Row(Modifier.fillMaxWidth())` of several controls and rely on their own intrinsic
    // (text-driven) width — adding fillMaxWidth() there would make each dropdown claim the
    // entire row and push its neighbors out, so they must keep the height-only behavior exactly
    // as before.
    val fixedSurfaceModifier = when {
        fixedHeight != null && anchorContent != null -> Modifier.fillMaxHeight().fillMaxWidth()
        fixedHeight != null -> Modifier.fillMaxHeight()
        else -> Modifier
    }
    Box(modifier.then(fixedHeightModifier)) {
        HoverBox(
            // Keep the padding inside the clickable Box. `HoverBox` appends its clickable
            // modifier after the supplied modifier, so padding on the supplied chain would
            // leave only the label text as the effective hit target (the same bug users see
            // in the Log order control). The log-view pills put their padding inside the
            // clickable surface; keep this shared dropdown consistent with that behavior.
            modifier = fixedSurfaceModifier
                .clip(CORNER_MD).background(if (open || alwaysFilled) fillColor else Color.Transparent, CORNER_MD)
                .border(1.dp, tc.br, CORNER_MD),
            onClick = { if (System.currentTimeMillis() >= suppressUntilMs) open = !open },
        ) {
            if (anchorContent != null) {
                // No horizontal/vertical padding here — a compact anchor (e.g. a 28dp badge) is
                // meant to fill its whole `fixedSurfaceModifier` box; the old text-anchor's
                // 8dp/4dp Row padding would otherwise squeeze it down well below its intended size.
                Box(fixedSurfaceModifier, contentAlignment = Alignment.Center) {
                    anchorContent()
                }
            } else {
                Row(
                    fixedSurfaceModifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    DisableSelection {
                        AppText(label, color = labelColor, fontSize = 11.sp)
                        AppText("▾", color = labelColor.copy(alpha = .7f), fontSize = 9.sp)
                    }
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
        is Seq3KeyAction.PrevMessage -> { view.focusedMessageId = seq3NeighbourMessageId(document, view.focusedMessageId, -1); true }
        is Seq3KeyAction.NextMessage -> { view.focusedMessageId = seq3NeighbourMessageId(document, view.focusedMessageId, +1); true }
        is Seq3KeyAction.SetTarget -> applySeq3SetTarget(state, session, view, document, action)
        is Seq3KeyAction.SetSource -> applySeq3SetSource(state, session, view, document, action)
        is Seq3KeyAction.ToggleHide -> applySeq3ToggleHide(state, session, view, document)
        is Seq3KeyAction.MergeSelection -> applySeq3MergeSelection(state, session, view)
        is Seq3KeyAction.GroupSelection -> applySeq3GroupSelection(state, session, view)
        is Seq3KeyAction.EditLabel -> applySeq3EditLabel(view, document)
        is Seq3KeyAction.JumpToLog -> applySeq3JumpToLog(state, session, view, document)
        is Seq3KeyAction.FocusFilter -> { view.textFieldFocused = true; true }
    }
}

// internal (not private): WP-panel-toggle's own Seq3KeyActionTest-style coverage of the
// fragment/note/lifeline Esc branch needs to invoke this directly, the same reason
// seq3ClearSelection/seq3AddNote/seq3BeginLabelRename above are already internal rather than
// private.
internal fun applySeq3Escape(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState): Boolean = when {
    // WP7 item 6: this root handler sits ABOVE every canvas/panel inline editor in the focus tree,
    // and Compose dispatches onPreviewKeyEvent top-down (root first) — so if this branch claimed
    // (returned true for) the event the way it used to, an editor's own InlineField.onCancel would
    // NEVER see the Escape key press at all, no matter what it's wired to do. The blur side effect
    // still always runs (harmless for a field with no cancel concept, e.g. the queue filter box,
    // which has nothing deeper to hand the event to), but `false` lets the event keep propagating
    // down to whatever field is actually focused, so its own onPreviewKeyEvent (InlineField's new
    // onCancel hook) gets the chance to revert/close itself instead of just losing focus.
    view.textFieldFocused -> { view.textFieldFocused = false; false }
    view.canvasEmptyContextMenuOpen -> { view.canvasEmptyContextMenuOpen = false; true }
    view.canvasContextMenuMessageId != null -> {
        view.canvasContextMenuMessageId = null
        view.canvasContextMenuOccurrenceEntryId = null
        true
    }
    view.canvasSelectionRect != null -> { view.canvasSelectionRect = null; true }
    view.regenerateSheetOpen -> { closeSeq3RegenerateSheet(state, session, view); true }
    view.guidedPass != null -> { view.guidedPass = null; true }
    // Item 4 (WP-panel-toggle): the three panel-only selections (fragment/note/lifeline) can't
    // conflict with each other — they're different rows in different sections — so Esc clears
    // whichever of them is set in one press, rather than picking a priority order among the three.
    // This branch sits ahead of the message-selection branch below: a panel row is the more
    // "local"/recent selection layer, so Esc peels it off first, same as it already peels off a
    // context menu or a marquee rect before falling through to the broader message selection.
    view.selectedFragmentId != null || view.selectedNoteId != null || view.selectedLifelineId != null -> {
        view.selectedFragmentId = null
        view.selectedNoteId = null
        view.selectedLifelineId = null
        true
    }
    view.selection.selectedIds.isNotEmpty() || view.selectedOccurrenceIds.isNotEmpty() -> {
        seq3ClearSelection(view)
        true
    }
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
    val messageId = view.guidedPass?.currentMessageId ?: view.focusedMessageId ?: return false
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
    val messageId = view.guidedPass?.currentMessageId ?: view.focusedMessageId ?: return false
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
    return seq3GroupMessages(state, session, view, ids, Seq3FragmentKind.LOOP)
}

private fun applySeq3EditLabel(view: Seq3ViewState, document: Seq3Document): Boolean {
    val messageId = seq3SelectedMessageIds(document, view).singleOrNull() ?: view.focusedMessageId ?: return false
    return seq3BeginLabelRename(view, document, messageId)
}

private fun applySeq3JumpToLog(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, document: Seq3Document): Boolean {
    val messageId = view.focusedMessageId ?: return false
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
 *  message currently focused by the queue or canvas. */
private fun seq3TargetIds(view: Seq3ViewState): Set<String>? =
    view.selection.selectedIds.takeIf { it.isNotEmpty() } ?: view.focusedMessageId?.let(::setOf)
