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
import androidx.compose.foundation.layout.widthIn
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
import com.indagium.diagram3.toMermaid
import com.indagium.diagram3.toPlantUml
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
// internal (not private): AutosaveCodec's seq3ViewToken()/restoreSeq3ViewToken() re-clamp a
// restored panelWidthDp through these same bounds rather than trusting the stored value.
internal const val PANEL_WIDTH_MIN_DP = 280f
internal const val PANEL_WIDTH_MAX_DP = 560f

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
                    if (seq3PanelVisible(view)) {
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
        // Design variant 1a: no more fixed 240dp box. A fixed width either clipped an ordinary
        // title early (forcing the ellipsis+tooltip path far more often than the name actually
        // needed it) or wasted space next to a short one; the identity block now takes exactly the
        // width seq3TitleWidth() computes for THIS title, capped generously — see that function.
        // The cap still has to be enforced on the COLUMN, not just on the title field: the
        // subtitle below it is a second, independently-sized child, and a long source filename
        // ("bugreport-2026-08-21-113355-…​.log · 128400 rows") would otherwise stretch the whole
        // identity block past the title's own cap and squeeze the right cluster.
        Column(Modifier.widthIn(max = TITLE_FIELD_MAX_WIDTH)) {
            Seq3TitleField(state, session)
            AppText(subtitle, color = tc.ts, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
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
        // Design variant 1a's right cluster: three NAMED groups — panes, diagram presentation,
        // output — each closed off by a hairline ([Seq3HeaderHairline]) instead of the old flat
        // run of individually-tooltipped icon buttons. Grouping by function is the whole point: a
        // control now reads as "this belongs with diagram presentation" or "this is an output
        // action" before the user even reads its own label.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Seq3PanesGroup(view)
            Seq3HeaderHairline()
            Seq3DiagramPresentationGroup(state, session)
            // Placed right before the OUTPUT group's leading hairline (not the diagram-
            // presentation group's) so it reads as "still generating — the actions on the right
            // are about to have something to act on", not as a stray note mid-cluster.
            if (session.generating) AppText("Generating…", color = tc.ts, fontSize = 11.sp)
            Seq3HeaderHairline()
            Seq3OutputGroup(state, session)
        }
    }
}

/** Design variant 1a's separator between the header's right-cluster groups — a plain 1dp rule
 *  (not a wider gap) so the three functionally distinct groups it sits between read as separate
 *  clusters even though [Seq3TitleBar] draws them in one continuous Row. */
@Composable
private fun Seq3HeaderHairline() {
    Box(Modifier.width(1.dp).height(20.dp).background(tc().br))
}

/** Design variant 1a's Panes group — replaces three solid-accent `≡`/`⇅`/`▦` [ToolbarBtn]s (whose
 *  accent fill made an "open" pane read as one of three heavy blocks, and whose glyphs meant
 *  nothing without their tooltip) with ONE multi-select [SegmentedControl] whose labels
 *  ("Messages"/"Lifelines"/"Artifacts") say outright what each toggles — the accent tint now
 *  reads as "this pane is open" rather than "this button is pressed", and the old glyph tooltips
 *  stop being load-bearing. [seq3PaneSegments]/[seq3TogglePaneSegment] hold the actual mapping to
 *  [Seq3ViewState]'s three section-visibility flags as plain functions so [Seq3WorkspaceTest] can
 *  assert it without composing this row. */
@Composable
private fun Seq3PanesGroup(view: Seq3ViewState) {
    SegmentedControl(
        options = listOf("Messages", "Lifelines", "Artifacts"),
        selectedIndices = seq3PaneSegments(view),
        onToggle = { index -> seq3TogglePaneSegment(view, index) },
    )
}

/** Which segments of [Seq3PanesGroup]'s multi-select read as "on" — index 0 Messages/
 *  [Seq3ViewState.messagesSectionOpen], 1 Lifelines/[Seq3ViewState.lifelinesSectionOpen],
 *  2 Artifacts/[Seq3ViewState.artifactsSectionOpen]. All three are peers: each hides only its own
 *  panel section, and the panel itself follows from them ([seq3PanelVisible]). */
internal fun seq3PaneSegments(view: Seq3ViewState): Set<Int> = buildSet {
    if (view.messagesSectionOpen) add(0)
    if (view.lifelinesSectionOpen) add(1)
    if (view.artifactsSectionOpen) add(2)
}

/** [seq3PaneSegments]'s toggle half — flips the one [Seq3ViewState] flag the clicked index maps
 *  to. Kept as a plain function (not inlined into the `onToggle` lambda) so the index<->flag
 *  mapping and its test sit next to [seq3PaneSegments] rather than only inside a composable. */
internal fun seq3TogglePaneSegment(view: Seq3ViewState, index: Int) {
    when (index) {
        0 -> view.messagesSectionOpen = !view.messagesSectionOpen
        1 -> view.lifelinesSectionOpen = !view.lifelinesSectionOpen
        2 -> view.artifactsSectionOpen = !view.artifactsSectionOpen
    }
}

/** Whether the queue panel (and its drag divider) renders at all — DERIVED from the three section
 *  toggles rather than stored, because the panel is nothing but its sections: turning the last one
 *  off should give the canvas the full width, and turning any one back on should bring the panel
 *  back with exactly that section in it. Before the 1a header rework the Messages toggle *was* the
 *  panel's own visibility flag, so switching Messages off took Lifelines and Artifacts down with
 *  it even when the user had them open — the labelled segmented control made that mismatch
 *  obvious, since three peer-looking labels have to behave like peers. */
internal fun seq3PanelVisible(view: Seq3ViewState): Boolean =
    view.messagesSectionOpen || view.lifelinesSectionOpen || view.artifactsSectionOpen

/** Design variant 1a's diagram-presentation group — "how does this diagram present itself": the
 *  PlantUML/Mermaid dialect control, the `#n`/`⏱ Time` prefix toggles, then the theme swatch.
 *  Unchanged in behaviour from before the header rework — only grouped and hairline-bounded now
 *  instead of running directly into the panes toggles on one side and the output actions on the
 *  other. */
@Composable
private fun Seq3DiagramPresentationGroup(state: AppState, session: Seq3WorkspaceSession) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
    }
}

/** Design variant 1a's output group — the header's two actions on the PRODUCED diagram, as
 *  opposed to anything that edits it: `Copy ▾` (read-only, never touches the document or its undo
 *  history) and `Attach snapshot ▾` (the header's one accent-filled primary control, replacing the
 *  old bare "+" icon button). Both go dim/disabled the same way when there is nothing to copy or
 *  attach yet ([Seq3Document.lifelines] empty). */
@Composable
private fun Seq3OutputGroup(state: AppState, session: Seq3WorkspaceSession) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Seq3CopyDropdown(state, session)
        Seq3AttachmentAction(state, session)
    }
}

/** Design variant 1a's real functional addition: puts the diagram straight on the clipboard in
 *  whatever form the destination needs, without going through a note first. Reuses
 *  [Seq3DropdownButton] — never a hand-rolled [Popup], see this file's own header comment on
 *  [LocalSeq3FocusRequester] for why that would silently break this surface's Esc handling —
 *  rather than [Seq3AttachmentAction]'s split-button shape, because every item here is a plain
 *  action with no checkmark state to show, so three menu items don't need their own
 *  primary/secondary split. [seq3CopyTargetLabel]/[seq3CopyTargetText] hold the label<->action
 *  mapping as plain functions the whole way except for the PNG item, which still renders through
 *  [Seq3RenderCache] directly in its own click handler (the same shape [AppState.copyRichPreview]
 *  already uses) since a raster needs a real [Seq3Document] and a resolved theme, not just its own
 *  text. The download item hands those exact same branded bytes to [AppState.downloadSeq3Png],
 *  which owns the native save picker and asynchronous file write. */
@Composable
private fun Seq3CopyDropdown(state: AppState, session: Seq3WorkspaceSession) {
    val document = session.document
    val hasContent = document.lifelines.isNotEmpty()
    Seq3DropdownButton(
        label = "Copy",
        labelColor = tc().tx,
        fillColor = tc().p2,
        menuWidth = 170.dp,
        // Without this the trigger sizes to its own 11sp label plus 4dp padding (~24dp) and sits
        // visibly shorter than every 28dp control beside it in the header.
        fixedHeight = 28.dp,
        // Matches [Seq3AttachmentAction]'s own Popup offset so both header menus drop to the same
        // line — see [Seq3DropdownButton]'s `menuOffsetY`.
        menuOffsetY = SEQ3_HEADER_MENU_OFFSET_Y,
    ) { close ->
        Seq3CopyTarget.entries.forEach { target ->
            Seq3DropdownMenuItem(label = seq3CopyTargetLabel(target), enabled = hasContent) {
                when (target) {
                    // The only item here that can throw (rasterizing the layout) — wrapped the
                    // same way copyRichPreview's own PNG render is, so a bad document can't crash
                    // the workspace out from under an otherwise plain clipboard action.
                    Seq3CopyTarget.PNG_IMAGE,
                    Seq3CopyTarget.DOWNLOAD_PNG -> runCatching {
                        val png = Seq3RenderCache.brandedPngBytes(
                            Seq3RenderCache.layout(document),
                            resolveSeq3ThemeColors(document, state.settings).toSeq3RasterTheme(),
                        )
                        when (target) {
                            Seq3CopyTarget.PNG_IMAGE -> state.copyImageToClipboard(
                                png,
                                document.title.ifBlank { "Sequence diagram v3" },
                            )
                            Seq3CopyTarget.DOWNLOAD_PNG -> state.downloadSeq3Png(png, document.title)
                        }
                    }
                    else -> seq3CopyTargetText(target, document)?.let(state::copyToClipboard)
                }
                close()
            }
        }
    }
}

/** What `Copy ▾` ([Seq3CopyDropdown]) can put on the clipboard or save to disk. */
internal enum class Seq3CopyTarget { PNG_IMAGE, DOWNLOAD_PNG, PLANTUML_SOURCE, MERMAID_SOURCE }

/** [Seq3CopyDropdown]'s menu-item label for each [Seq3CopyTarget]. */
internal fun seq3CopyTargetLabel(target: Seq3CopyTarget): String = when (target) {
    Seq3CopyTarget.PNG_IMAGE -> "PNG image"
    Seq3CopyTarget.DOWNLOAD_PNG -> "Download PNG"
    Seq3CopyTarget.PLANTUML_SOURCE -> "PlantUML source"
    Seq3CopyTarget.MERMAID_SOURCE -> "Mermaid source"
}

/**
 * Default filename for [Seq3CopyTarget.DOWNLOAD_PNG]. Titles are user-editable, so collapse every
 * run of characters that is unsafe in a native save dialog (including path separators, control
 * characters, and Windows-reserved punctuation) to one underscore. Keeping dots and hyphens makes
 * normal titles readable while the final extension is always the lowercase `.png` expected by the
 * download action. Pure and filesystem-free so the filename contract stays directly testable.
 */
internal fun seq3PngFileName(title: String): String {
    val safeTitle = title.trim().ifBlank { "Sequence diagram v3" }
        .replace(Regex("[^a-zA-Z0-9._-]+"), "_")
        .trim('.', '-', '_')
        .ifBlank { "sequence_diagram" }
    val withoutPng = safeTitle.replace(Regex("\\.png$", RegexOption.IGNORE_CASE), "")
        .ifBlank { "sequence_diagram" }
    return "$withoutPng.png"
}

/** Pure "what text does this target copy" half of [Seq3CopyDropdown] — split out so
 *  [Seq3WorkspaceTest] can assert the PlantUML/Mermaid selection without a composition. `null` for
 *  [Seq3CopyTarget.PNG_IMAGE] and [Seq3CopyTarget.DOWNLOAD_PNG]: those items operate on bytes, not
 *  text, and are rendered directly by the click handler instead (see [Seq3CopyDropdown]'s own doc
 *  comment for why). */
internal fun seq3CopyTargetText(target: Seq3CopyTarget, document: Seq3Document): String? = when (target) {
    Seq3CopyTarget.PNG_IMAGE, Seq3CopyTarget.DOWNLOAD_PNG -> null
    Seq3CopyTarget.PLANTUML_SOURCE -> document.toPlantUml()
    Seq3CopyTarget.MERMAID_SOURCE -> document.toMermaid()
}

/**
 * Design variant 1a: two independent document-level toggles, now ONE multi-select
 * [SegmentedControl] inside [Seq3DiagramPresentationGroup] — beside the dialect control and
 * [Seq3DocumentThemeDropdown] it visually pairs with ("how does this diagram present itself").
 * Used to be two glyph `#`/`⏱` [ToolbarBtn]s explained only by a tooltip; the segments are now
 * self-describing sample text (`"#n"`/`"⏱ Time"`) so the label itself carries the affordance.
 * [SegmentedControl] renders every option in one font, so the design mock's monospace `#n` is out
 * of reach here without forking the control — not worth it for two labels, so both render in the
 * control's default font. Each segment still dispatches its OWN [Seq3Command]
 * ([Seq3Command.SetShowSequenceNumbers]/[Seq3Command.SetShowTimestamps], via
 * [seq3TogglePrefixSegment]) so `⌘Z` still undoes them independently, and both stay document
 * fields (not view state) for the same reason as before: the canvas, the PNG export, and the
 * exported text must always agree on whether a call's `[#n]`/`[ts]` prefix is showing. Per-segment
 * tooltips are dropped for the same reason as the font: [SegmentedControl] draws its options as
 * one internal Row, so wrapping either sample in its own [TooltipArea] would mean forking the
 * control just for this one call site — the labels are the affordance now.
 */
@Composable
private fun Seq3InlinePrefixToggles(state: AppState, session: Seq3WorkspaceSession) {
    SegmentedControl(
        options = listOf("#n", "⏱ Time"),
        selectedIndices = seq3PrefixToggleSegments(session.document),
        onToggle = { index -> seq3TogglePrefixSegment(state, session, index) },
    )
}

/** Which segments of [Seq3InlinePrefixToggles] read as "on". */
internal fun seq3PrefixToggleSegments(document: Seq3Document): Set<Int> = buildSet {
    if (document.showSequenceNumbers) add(0)
    if (document.showTimestamps) add(1)
}

/** [seq3PrefixToggleSegments]'s toggle half — dispatches the [Seq3Command] the clicked index maps
 *  to. Kept as a plain function so the index<->command mapping is testable without composing
 *  [Seq3InlinePrefixToggles]. */
internal fun seq3TogglePrefixSegment(state: AppState, session: Seq3WorkspaceSession, index: Int) {
    val document = session.document
    when (index) {
        0 -> state.seq3Sessions.applyCommand(session.id, Seq3Command.SetShowSequenceNumbers(!document.showSequenceNumbers))
        1 -> state.seq3Sessions.applyCommand(session.id, Seq3Command.SetShowTimestamps(!document.showTimestamps))
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
 * explains the control instead. Design variant 1a: the swatch's "toolbar siblings" it matches the
 * height of are now the two [SegmentedControl]s it shares [Seq3DiagramPresentationGroup] with
 * (dialect, then `#n`/`⏱ Time`), not the old individual glyph buttons — the swatch keeps its own
 * landscape shape rather than being forced into their square either way.
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
            // inside it, so its 42x26dp fills the 44x28dp footprint its [Seq3DiagramPresentationGroup]
            // siblings occupy.
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
 *  42×26dp inside a 44×28dp trigger (its [Seq3DiagramPresentationGroup] siblings' 28dp-tall
 *  footprint), leaving only the 1dp the chrome's border needs. Every element is scaled
 *  independently rather than pixel-scaled uniformly — a literal ~35% scale of the card's 4dp rail
 *  and 8dp swatches would still read as mush — while the card's own proportions are kept: an outer
 *  [ThemeColors.bg] frame, a `p` title strip at the card's ≈21% height ratio with its ac/seq1/seq2
 *  dot trio, and a `p2` body pane with the left accent rail and bottom-right swatch pair. No text:
 *  the theme's name lives in [Seq3DocumentThemeDropdown]'s tooltip. */
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selectedRowCount == 1 && selectedIds.size == 1) {
            Seq3TitleActionButton("Rename", "Rename selected line") {
                seq3BeginLabelRename(view, document, selectedIds.single())
            }
            Seq3TitleActionButton("Note", "Add note for selected line") {
                seq3AddNote(state, session, view, document, selectedIds)
            }
        }
        // Offered alongside Rename/Note rather than instead of them: a fragment around a single
        // selected message is as legitimate as one around ten, exactly as it would be if the user
        // wrote the bracket by hand in PlantUML.
        if (seq3CanGroupSelection(document, view, selectedIds)) {
            // Same geometry as `Copy ▾` ([Seq3CopyDropdown]) — 28dp fixed height, CORNER_MD (not
            // a bespoke smaller radius) — so the two dropdown triggers in this header read as one
            // family rather than "Group" looking like a heavier, separate kind of control.
            Seq3DropdownButton(
                label = "Group",
                labelColor = tc.tx,
                menuWidth = 130.dp,
                fixedHeight = 28.dp,
            ) { close ->
                Seq3FragmentKind.entries.forEach { kind ->
                    Seq3DropdownMenuItem(kind.name.lowercase()) {
                        seq3GroupMessages(state, session, view, selectedIds, kind)
                        close()
                    }
                }
            }
        }
        // A hairline before "Clear" — same device [Seq3TitleBar] uses to separate its right
        // cluster's groups — so the destructive verb reads apart from the constructive
        // rename/note/group actions ahead of it, in both the single- and multi-selection branches.
        Seq3HeaderHairline()
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
        modifier = Modifier.height(28.dp),
        onClick = onClick,
    )
}

// Where both header dropdown menus open, measured from their trigger's top: the controls in that
// row are 28dp tall, so this is that height plus a 2dp gap. Shared by [Seq3AttachmentAction]'s own
// Popup and (via `menuOffsetY`) [Seq3CopyDropdown]'s [Seq3DropdownButton], since two menus hanging
// off the same toolbar row have to line up with each other.
private val SEQ3_HEADER_MENU_OFFSET_Y = 30.dp

// Asymmetric corners for [Seq3AttachmentAction]'s split button: the two halves share the same
// 4dp radius [CORNER_MD] uses everywhere else, but only on their OUTER edge, so the pair still
// reads as one rounded pill with a seam in the middle rather than two separate buttons glued
// together.
private val SEQ3_ATTACH_LEADING_SHAPE = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
private val SEQ3_ATTACH_TRAILING_SHAPE = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)

/** Design variant 1a: the header's single accent-filled PRIMARY control, replacing the old bare
 *  "+" icon-only [ToolbarBtn]. Reworked from a single button-that-opens-a-menu into a genuine
 *  split button because clicking "attach" is by far the more common action and no longer deserves
 *  to cost an extra click just to reach the mode picker: the left half performs
 *  `attachmentActionLabel(primary)` directly, one click, exactly like a plain button; only the
 *  ~22dp caret half opens the popup, which now ALSO surfaces the "which mode is primary"
 *  preference itself ([AppSettings.diagramLinkedNotePrimary] — the same field `SettingsDialog.kt`'s
 *  "Diagram note action" row already exposes) so the user can retarget the left half's click
 *  without leaving the header. Both halves reuse [ToolbarBtn]'s existing `active = true` solid-fill
 *  treatment (the same white-on-[ThemeColors.ac] look every other active toolbar toggle already
 *  has) rather than hand-rolling a new filled surface, sliced into two segments by
 *  [SEQ3_ATTACH_LEADING_SHAPE]/[SEQ3_ATTACH_TRAILING_SHAPE] so the pair still reads as one pill.
 *  Disabled together when [Seq3Document.lifelines] is empty, since [attach] already no-ops on an
 *  empty document — this only makes that dead end visible instead of silently swallowing the
 *  click. Keeps the hand-rolled [Popup] (not [Seq3DropdownButton] — see [Seq3CopyDropdown]'s own
 *  doc comment for why that one DOES reuse it) because the split-button anchor shape has no
 *  equivalent in [Seq3DropdownButton], but still goes through the same `closeAndReclaimFocus`
 *  pattern that component uses, since this surface's Esc handling depends on it just the same —
 *  see this file's own header comment on [LocalSeq3FocusRequester]. */
@Composable
private fun Seq3AttachmentAction(state: AppState, session: Seq3WorkspaceSession) {
    val tc = tc()
    val density = LocalDensity.current
    val focusRequester = LocalSeq3FocusRequester.current
    var open by remember(session.id) { mutableStateOf(false) }
    val primary = if (state.settings.diagramLinkedNotePrimary) Seq3AttachmentMode.LINKED else Seq3AttachmentMode.SNAPSHOT
    val hasContent = session.document.lifelines.isNotEmpty()

    fun closeAndReclaimFocus() {
        open = false
        focusRequester?.let { runCatching { it.requestFocus() } }
    }

    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolbarBtn(
                label = attachmentActionLabel(primary),
                active = true,
                enabled = hasContent,
                modifier = Modifier.height(28.dp),
                shape = SEQ3_ATTACH_LEADING_SHAPE,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                onClick = { attach(state, session, primary) },
            )
            // The seam between the two halves — white-on-accent like the ToolbarBtn text either
            // side of it, not a themed tc.br rule, since it sits ON TOP of the accent fill rather
            // than at the button's outer edge.
            Box(Modifier.width(1.dp).height(28.dp).background(Color.White.copy(alpha = .28f)))
            ToolbarBtn(
                label = "▾",
                tooltip = "More attach options",
                active = true,
                enabled = hasContent,
                modifier = Modifier.width(22.dp).height(28.dp),
                shape = SEQ3_ATTACH_TRAILING_SHAPE,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                onClick = { open = !open },
            )
        }
        if (open) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, with(density) { SEQ3_HEADER_MENU_OFFSET_Y.roundToPx() }),
                onDismissRequest = ::closeAndReclaimFocus,
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(216.dp)
                        .shadow(8.dp, RoundedCornerShape(8.dp))
                        .background(tc.p, RoundedCornerShape(8.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(8.dp))
                        .padding(4.dp),
                ) {
                    Seq3DropdownMenuItem(
                        label = attachmentActionLabel(Seq3AttachmentMode.SNAPSHOT),
                        onClick = {
                            attach(state, session, Seq3AttachmentMode.SNAPSHOT)
                            closeAndReclaimFocus()
                        },
                    )
                    Seq3DropdownMenuItem(
                        label = attachmentActionLabel(Seq3AttachmentMode.LINKED),
                        onClick = {
                            attach(state, session, Seq3AttachmentMode.LINKED)
                            closeAndReclaimFocus()
                        },
                    )
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
                    }
                    // "CLICK ATTACHES" + the Snapshot/Live link picker below it change which mode
                    // `primary` resolves to WITHOUT closing the menu — only the two action rows
                    // above and the Popup's own dismiss do that — so the user can flip the
                    // preference and immediately see the left half's label update before deciding
                    // whether to also use one of the explicit actions this same click.
                    AppText(
                        "CLICK ATTACHES",
                        color = tc.td,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    )
                    SegmentedControl(
                        options = listOf("Snapshot", "Live link"),
                        selectedIndices = setOf(if (state.settings.diagramLinkedNotePrimary) 1 else 0),
                        onToggle = { index -> state.updateSettings { it.copy(diagramLinkedNotePrimary = index == 1) } },
                        fillWidth = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
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

// Design variant 1a: the identity block no longer sits in a fixed 240dp Column (see
// Seq3TitleBar's own comment), so this cap no longer has to leave headroom for the right cluster
// squeezed in beside it — raised 220 -> 420dp (and TITLE_MAX_VISIBLE_CHARS 28 -> 56 to match) so
// an ordinary title stops truncating well before it would ever crowd the now-independently-sized
// right cluster.
private val TITLE_FIELD_MAX_WIDTH = 420.dp
private val TITLE_FIELD_MIN_WIDTH = 96.dp
private val TITLE_FIELD_HEIGHT = 24.dp
private const val TITLE_MAX_VISIBLE_CHARS = 56

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
                // Display mode measures the real text and only caps it. The estimate below is a
                // guess (4.8dp/char) that under-measures 13sp SemiBold badly enough that even
                // "Untitled diagram" hit the 96dp floor and rendered as "Untitled diag…" — the
                // exact truncation raising TITLE_FIELD_MAX_WIDTH was meant to end. The estimate
                // survives only for the EDITOR, which needs a concrete width to lay out a field.
                Modifier.widthIn(max = TITLE_FIELD_MAX_WIDTH).height(TITLE_FIELD_HEIGHT)
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

/** One exact row drawn by the canvas. A null occurrence id identifies a standalone/authored row;
 *  a non-null id distinguishes one occurrence from the other rows of the same repeated message. */
internal data class Seq3CanvasRowRef(val messageId: String, val occurrenceEntryId: Int?)

/**
 * Session-scoped VIEW state shared between [Seq3QueuePanel] and [Seq3Canvas] —
 * filter/sort/selection/hover/zoom/focused message. Deliberately never part of
 * [Seq3WorkspaceSession]: spec §07's "sort is a view, never an edit" reasoning extends to every
 * field here — none of it is undo-tracked, none of it is written to a note, and losing it when a
 * session is closed and reopened is correct (the same way a browser tab's own scroll position
 * isn't persisted either). Every EDIT that reads this class still only ever reaches the document
 * through [Seq3Session.applyCommand] — this class itself never holds a [com.indagium.diagram3.
 * Seq3Document].
 *
 * EXCEPTION (Wave 2.5): the queue panel's own layout preferences — [messagesSectionOpen]/
 * [messagesExpanded], [lifelinesSectionOpen]/[lifelinesExpanded]/[lifelinesSectionHeightDp],
 * [artifactsSectionOpen]/[artifactsExpanded]/[artifactsSectionHeightDp], and [panelWidthDp] — ARE
 * persisted across a restart, keyed by the session's `libraryItemId` (stable) rather than its
 * `sessionId` (regenerated every reopen — see [activeDiagramToken] for the identical
 * substitution). See `seq3ViewToken()`/`restoreSeq3ViewToken()` in AutosaveCodec.kt. A user who
 * spends time collapsing Lifelines and stretching Artifacts on one diagram found that layout gone
 * on every restart, which reads as the app not remembering a preference rather than as the
 * deliberate ephemerality every other field in this class still has. Nothing else here changes:
 * selection/hover/zoom/filter/sort still reset to defaults on reopen, same as before.
 */
internal class Seq3ViewState {
    var selection by mutableStateOf(Seq3Selection())

    /** Expanded repeated-message rows in the queue. This is view state only; grouping remains part
     *  of the durable message and the extracted occurrence command is the only document edit. */
    var expandedOccurrenceMessageIds by mutableStateOf<Set<String>>(emptySet())

    /** Which of [expandedOccurrenceMessageIds] was opened BY a canvas click rather than by the user
     *  reaching for the queue's own toggle — see [seq3AutoExpandOccurrences]. At most one, and only
     *  while it is still the group the canvas is pointing at. */
    var autoExpandedOccurrenceMessageId by mutableStateOf<String?>(null)

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

    /** Whether the Messages section is visible in the queue panel — the peer of
     *  [lifelinesSectionOpen]/[artifactsSectionOpen], and distinct from [messagesExpanded], which
     *  only collapses a *visible* Messages section down to its own header row. A view preference,
     *  never persisted into the document. Whether the panel itself renders is derived from all
     *  three section flags — see [seq3PanelVisible]. */
    var messagesSectionOpen by mutableStateOf(true)

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

    /** The delay counterpart of [selectedFragmentId] above — same contract, backing the Artifacts
     *  panel's own delay row (delays moved there from being canvas/context-menu-only). */
    var selectedDelayId by mutableStateOf<String?>(null)

    /** The delay counterpart of [hoveredFragmentId] above — same contract. */
    var hoveredDelayId by mutableStateOf<String?>(null)

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

/** Whether the current selection names anything a fragment can be drawn around — see
 *  [seq3FragmentSpanFor] for what counts.
 *
 *  Deliberately no contiguity requirement and no "at least two rows" minimum. Both used to be
 *  checked here and both silently removed the `Group` control, with nothing on screen saying why.
 *  A bracket drawn around a selection the user made is not a state the app needs to protect them
 *  from — hand-writing the same fragment in PlantUML puts it wherever the author wants it. */
internal fun seq3CanGroupSelection(document: Seq3Document, view: Seq3ViewState, selectedIds: Set<String>): Boolean =
    seq3FragmentSpanFor(document, view, selectedIds).isNotEmpty()

/** Opens [messageId]'s occurrence rows in the queue because a canvas click landed on one of them —
 *  the diagram knows exactly which occurrence was hit, so the matching submessage has to be visible
 *  and actionable in the panel.
 *
 *  The expansion is also remembered as automatic, so [seq3ReleaseAutoExpand] can undo it when the
 *  canvas moves on or the selection is dropped. Without that, this only ever ADDED: clicking five
 *  repeated messages left five groups open, all still open long after the selection was gone.
 *
 *  A group the user opened themselves is never claimed — if it was already expanded before this
 *  click, it stays theirs and nothing will collapse it later. Re-clicking the group this function
 *  already owns keeps that ownership rather than quietly handing it over. */
internal fun seq3AutoExpandOccurrences(view: Seq3ViewState, messageId: String) {
    val alreadyExpanded = messageId in view.expandedOccurrenceMessageIds
    val previous = view.autoExpandedOccurrenceMessageId
    if (previous != null && previous != messageId) {
        view.expandedOccurrenceMessageIds = view.expandedOccurrenceMessageIds - previous
    }
    view.expandedOccurrenceMessageIds = view.expandedOccurrenceMessageIds + messageId
    view.autoExpandedOccurrenceMessageId = if (alreadyExpanded && previous != messageId) null else messageId
}

/** Collapses whatever [seq3AutoExpandOccurrences] opened, if anything. A no-op for a group the user
 *  opened themselves, which is the whole point of tracking ownership. */
internal fun seq3ReleaseAutoExpand(view: Seq3ViewState) {
    val auto = view.autoExpandedOccurrenceMessageId ?: return
    view.expandedOccurrenceMessageIds = view.expandedOccurrenceMessageIds - auto
    view.autoExpandedOccurrenceMessageId = null
}

/** The queue's own expand/collapse toggle taking [messageId] over: once the user has reached for it
 *  themselves the row is theirs, open or closed, and the canvas must not collapse it out from under
 *  them later. */
internal fun seq3DisownAutoExpand(view: Seq3ViewState, messageId: String) {
    if (view.autoExpandedOccurrenceMessageId == messageId) view.autoExpandedOccurrenceMessageId = null
}

internal fun seq3ClearSelection(view: Seq3ViewState, clearFocus: Boolean = false) {
    view.selection = Seq3Selection()
    view.selectionFromMarquee = false
    view.selectedCanvasRows = emptySet()
    view.selectedOccurrenceIds = emptySet()
    view.selectedOccurrenceMessageId = null
    view.selectedOccurrenceEntryId = null
    view.hoveredMessageId = null
    // The canvas opened that group only to show what was selected; with the selection gone it has
    // no reason to stay open. A group the user opened themselves is untouched.
    seq3ReleaseAutoExpand(view)
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
        view.selectedDelayId = null
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

/** The delay counterpart of [seq3ToggleFragmentSelection] above — same contract. */
internal fun seq3ToggleDelaySelection(view: Seq3ViewState, delayId: String) {
    view.selectedDelayId = if (view.selectedDelayId == delayId) null else delayId
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

// Default size for a free-floating note placed via a click (canvas empty-context-menu or the
// panel's "+ note" button) — a fixed starting box the user is free to resize afterward.
private const val SEQ3_DEFAULT_NOTE_WIDTH_DP = 220.0
private const val SEQ3_DEFAULT_NOTE_HEIGHT_DP = 72.0

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
        width = placement?.let { SEQ3_DEFAULT_NOTE_WIDTH_DP },
        height = placement?.let { SEQ3_DEFAULT_NOTE_HEIGHT_DP },
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

private const val SEQ3_DEFAULT_NOTE_PLACEMENT_TOP_MARGIN_DP = 24.0

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
    val x = ((layout.width - SEQ3_DEFAULT_NOTE_WIDTH_DP) / 2.0).coerceAtLeast(0.0)
    val y = SEQ3_DEFAULT_NOTE_PLACEMENT_TOP_MARGIN_DP
    return Seq3Box(x, y, 0.0, 0.0)
}

/** Manual insert (WP11, "Insert delay after this" — the canvas context menu and the Artifacts
 *  panel's "+ delay" button both call this): anchors a new [Seq3Delay] right after
 *  [afterMessageId]'s own last drawn row. [label] defaults to a generic placeholder, editable in
 *  place afterward via [Seq3BulkAction.SetDelayLabel] (the canvas overlay's or the Artifacts
 *  row's double-click-to-rename, same pattern as a fragment/note label) — this function's own job
 *  is only to create it, matching [seq3AddNote]'s "caller mints the id, this fires the bulk
 *  action" shape. */
internal fun seq3InsertDelayAfter(
    state: AppState,
    session: Seq3WorkspaceSession,
    afterMessageId: String,
    label: String = "delay",
    // User-observed correction: omitting this (the pre-existing default) anchors after the
    // message's LAST occurrence, same as before this parameter existed — right for a caller that
    // only knows the messageId (the Artifacts panel's own anchor logic already picks the right
    // message this way). The canvas context menu instead already knows exactly which occurrence's
    // row was right-clicked (`view.canvasContextMenuOccurrenceEntryId`) and passes it through so a
    // delay lands after THAT row even when the same message repeats later in the timeline.
    afterOccurrenceEntryId: Int? = null,
): Boolean {
    val delay = Seq3Delay(
        id = "seq3-delay-${UUID.randomUUID()}",
        afterMessageId = afterMessageId,
        label = label,
        afterOccurrenceEntryId = afterOccurrenceEntryId,
    )
    return state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(emptySet(), Seq3BulkAction.AddDelay(delay)))
}

/** The individual messages a fragment built from the current selection has to reference. Split out
 *  of [seq3GroupMessages] so the selection → fragment step can be asserted directly.
 *
 *  The unit of grouping is one message — one arrow on the canvas, one checkable row in the queue.
 *  A repeated queue row (`×9`) is NOT one of those: it is a container holding that many independent
 *  messages, drawn as a single row purely so the group can be edited from one place. That is why it
 *  renders an expand toggle where a single message renders a checkbox, and it is why selecting it
 *  contributes nothing here on its own — the bracket follows the children the user actually ticked,
 *  never all of them because their container happened to be highlighted. Sweeping in every
 *  occurrence is what stretched a `loop` from the top of a diagram to the bottom.
 *
 *  So a fragment references exactly:
 *   - occurrence rows ticked in the queue and arrows picked on the canvas ([seq3SelectedOccurrenceRefs]);
 *   - a selected message that holds at most one occurrence, which is a single message in its own
 *     right rather than a container — as its one occurrence when it has evidence, or as a plain
 *     message id when it is authored and has none. */
internal data class Seq3FragmentSpan(val messageIds: List<String>, val occurrenceRefs: List<Seq3OccurrenceRef>) {
    fun isNotEmpty(): Boolean = messageIds.isNotEmpty() || occurrenceRefs.isNotEmpty()

    /** The message ids this span speaks for — what [Seq3BulkAction.Group]'s own "must contain
     *  exactly the selected messages" check has to be given, since a selected container drops out. */
    fun referencedMessageIds(): Set<String> = (messageIds + occurrenceRefs.map { it.messageId }).toSet()
}

internal fun seq3FragmentSpanFor(
    document: Seq3Document,
    view: Seq3ViewState,
    selectedIds: Set<String>,
): Seq3FragmentSpan {
    val exactRefs = seq3SelectedOccurrenceRefs(document, view)
    val exactIds = exactRefs.mapTo(hashSetOf()) { it.messageId }
    val singles = document.messages.filter { it.id in selectedIds && it.id !in exactIds && it.occurrences.size <= 1 }
    val singleRefs = singles.mapNotNull { message ->
        message.occurrences.firstOrNull()?.let { Seq3OccurrenceRef(message.id, it.entryId) }
    }
    return Seq3FragmentSpan(
        messageIds = singles.filter { it.occurrences.isEmpty() }.map { it.id },
        occurrenceRefs = exactRefs + singleRefs,
    )
}

internal fun seq3GroupMessages(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    selectedIds: Set<String>,
    kind: Seq3FragmentKind,
): Boolean {
    val span = seq3FragmentSpanFor(session.document, view, selectedIds)
    if (!span.isNotEmpty()) return false
    val fragment = Seq3Fragment(
        id = "seq3-fragment-${UUID.randomUUID()}",
        kind = kind,
        label = kind.name.lowercase(),
        messageIds = span.messageIds,
        occurrenceRefs = span.occurrenceRefs,
    )
    // The span's own ids, not the raw selection: a highlighted `×N` container contributes no
    // messages, and applyGroup rejects a fragment whose references don't match what it is told was
    // selected.
    val applied = state.seq3Sessions.applyCommand(
        session.id,
        Seq3Command.Bulk(span.referencedMessageIds(), Seq3BulkAction.Group(fragment)),
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
    // How far below the trigger's TOP the menu opens. The 26dp default suits this component's
    // original call sites (Seq3QueuePanel.kt's SEQ3_ACTION_BADGE_SIZE chips and friends, all
    // shorter than the header's controls); a taller trigger has to push its menu down by its own
    // height instead, or the menu rides up over the button. The header's `Copy ▾` passes 30dp so
    // it opens level with `Attach snapshot ▾`'s own popup right beside it — two menus dropping
    // from one toolbar row must share an edge.
    menuOffsetY: androidx.compose.ui.unit.Dp = 26.dp,
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
    // rendered narrower than its 28dp toolbar siblings. Gated on
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
                offset = IntOffset(0, with(density) { menuOffsetY.roundToPx() }),
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
    // Item 4 (WP-panel-toggle): the panel-only selections (fragment/note/delay/lifeline) can't
    // conflict with each other — they're different rows in different sections — so Esc clears
    // whichever of them is set in one press, rather than picking a priority order among them.
    // This branch sits ahead of the message-selection branch below: a panel row is the more
    // "local"/recent selection layer, so Esc peels it off first, same as it already peels off a
    // context menu or a marquee rect before falling through to the broader message selection.
    view.selectedFragmentId != null || view.selectedNoteId != null || view.selectedDelayId != null || view.selectedLifelineId != null -> {
        view.selectedFragmentId = null
        view.selectedNoteId = null
        view.selectedDelayId = null
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
