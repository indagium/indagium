@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)
@file:Suppress("MaxLineLength")

package com.indagium.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.*
import com.indagium.ui.holiday.*
import kotlin.math.roundToInt

// ── Settings dialog ───────────────────────────────────────────────────
// Left-hand nav lists every page in labelled groups; only the selected page's content renders on the
// right, so growing any one page (e.g. Providers) no longer pushes every other page down a shared
// scroll. The page model, nav list and footer live in SettingsPages.kt; this file owns the dialog's
// state and the page bodies. Version, author and the project links sit on the About page.

private const val ANCHOR_WAIT_FRAMES = 30

private val ANCHOR_SCROLL_MARGIN = 16.dp
private const val FLASH_MS = 1200

/** Keeps the content composed (so its state survives) but neither placed, drawn, hit-testable nor
 *  exposed to accessibility. */
private fun Modifier.composedButHidden(): Modifier = this
    .clearAndSetSemantics {}
    .layout { measurable, constraints ->
        measurable.measure(constraints)
        layout(0, 0) {}
    }

/** Published by [AiProviderSettingsSection] each recomposition so [SettingsDialog] can gate
 *  page switches and closing the dialog behind an unsaved-changes prompt without hoisting the
 *  section's entire edit-draft state up to the dialog. */
internal class AiProviderGuard(val isDirty: Boolean, val profileName: String, val save: () -> String?)

@Composable
internal fun SettingsDialog(
    state: AppState,
    onDismiss: () -> Unit,
    onRequestCloseChanged: (() -> Unit) -> Unit = {},
    // The main window's size in pixels. The dialog is a layer of its own, so it cannot ask its own window; null falls back to
    // LocalWindowInfo, which is the main window's in tests and where the layer shares the window.
    windowSize: IntSize? = null,
) {
    val tc = tc()
    val shape = RoundedCornerShape(8.dp)
    var selectedPage by remember {
        mutableStateOf(state.requestedSettingsPage ?: SettingsPage.General)
    }
    val voiceInputSupported = true
    LaunchedEffect(Unit) {
        state.requestedSettingsPage?.let {
            selectedPage = it
            state.requestedSettingsPage = null
        }
        state.refreshArchiveCacheInfo()
    }
    // Guards leaving the Providers page (switching to another page, or closing the dialog entirely)
    // with unsaved profile edits. Only consulted while that page is actually selected, so a stale
    // guard value left over from an earlier visit is harmless.
    var aiProviderGuard by remember { mutableStateOf<AiProviderGuard?>(null) }
    var pendingPageSwitch by remember { mutableStateOf<SettingsPage?>(null) }
    var pendingClose by remember { mutableStateOf(false) }
    var guardSaveError by remember { mutableStateOf<String?>(null) }

    // Returns false when the switch was deferred behind the unsaved-changes prompt.
    fun requestPageSwitch(target: SettingsPage): Boolean {
        if (selectedPage == SettingsPage.AiProviders && aiProviderGuard?.isDirty == true) {
            guardSaveError = null
            pendingPageSwitch = target
            return false
        }
        selectedPage = target
        return true
    }

    // ── Settings search (ui/SettingsSearchIndex.kt, ui/SettingsSearchUi.kt) ──────────────────
    val visiblePages = remember { SettingsPage.entries.filter { it != SettingsPage.VoiceInput || voiceInputSupported }.toSet() }
    var query by remember { mutableStateOf("") }
    val searching = query.isNotBlank()
    val results = remember(query) { searchSettings(query, visiblePages) }
    val anchors = remember { SettingsAnchors() }
    var pendingAnchor by remember { mutableStateOf<String?>(null) }
    val searchFocus = remember { FocusRequester() }
    // Keeps the dialog's own key handling (Cmd/Ctrl+F, Esc) alive: a clicked nav item, result row
    // or dismissed popup would otherwise take keyboard focus with it and leave nothing focused.
    val rootFocus = remember { FocusRequester() }

    fun reclaimFocus() { runCatching { rootFocus.requestFocus() } }
    LaunchedEffect(Unit) { reclaimFocus() }

    fun openResult(entry: SettingsSearchEntry) {
        val reached = entry.page == selectedPage || requestPageSwitch(entry.page)
        // A switch deferred behind the AI-provider prompt keeps the results on screen instead, so
        // the user can simply pick again once they've answered it.
        if (!reached) return
        query = ""
        pendingAnchor = entry.anchor
        reclaimFocus()
    }

    fun requestClose() {
        if (selectedPage == SettingsPage.AiProviders && aiProviderGuard?.isDirty == true) {
            guardSaveError = null
            pendingClose = true
        } else {
            onDismiss()
        }
    }
    // Escape still reaches the outer Dialog's onDismissRequest (only click-outside is disabled
    // there), so it must go through the same unsaved-changes guard rather than closing unconditionally.
    SideEffect { onRequestCloseChanged(::requestClose) }

    SideEffect { anchors.pendingKey = pendingAnchor }
    val contentScroll = rememberScrollState()
    val density = LocalDensity.current
    // A page opens at its top, so a drill-in page or the page behind a back link is not left scrolled to the previous page's offset. Declared
    // before the anchor effect below, which scrolls to its row once the page has laid out.
    LaunchedEffect(selectedPage) { contentScroll.scrollTo(0) }
    // After a result click the page is composed again (and, for another page, for the first
    // time), so give it two frames to lay out before reading the anchor, then scroll and flash.
    LaunchedEffect(pendingAnchor) {
        val key = pendingAnchor ?: return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        var y: Float? = null
        var waited = 0
        while (y == null && waited < ANCHOR_WAIT_FRAMES) {
            y = anchors.yInContent(key)
            if (y == null) {
                withFrameNanos { }
                waited++
            }
        }
        // Cleared only at the end: the key of this very effect, so clearing it earlier would
        // cancel the scroll below. A newer result click changes the key and restarts it instead.
        val target = y
        if (target != null) {
            contentScroll.animateScrollTo((target - with(density) { ANCHOR_SCROLL_MARGIN.toPx() }).toInt().coerceAtLeast(0))
            anchors.flashKey = key
            anchors.flash.snapTo(1f)
            anchors.flash.animateTo(0f, tween(FLASH_MS, easing = LinearEasing))
        }
        pendingAnchor = null
    }

    val windowPx = windowSize ?: LocalWindowInfo.current.containerSize
    val dialogSize = with(density) { settingsDialogSize(windowPx.width.toDp(), windowPx.height.toDp()) }
    Box(
        // 216 (nav) + 1 (divider) + 743 (content). 743 less the 24dp and 28dp padding leaves 691dp,
        // tuned against ThemeGallery's FlowRow math (118dp cards, 8dp gaps: 5 cards = 622dp, a 6th
        // would need 748dp) so five fit per row and the five workspace-profile cards split the width evenly.
        Modifier.size(dialogSize.width, dialogSize.height)
            .clip(shape)
            .background(tc.p)
            .border(1.dp, tc.br, shape)
            .onPreviewKeyEvent { ev ->
                when {
                    ev.type != KeyEventType.KeyDown -> false
                    ev.isActionKey && !ev.isShiftPressed && ev.key == Key.F -> {
                        runCatching { searchFocus.requestFocus() }
                        true
                    }
                    // Esc first clears an active search; with no query it falls through to the
                    // dialog's own dismiss handling (and its unsaved-changes guard) unchanged.
                    ev.key == Key.Escape && query.isNotEmpty() -> {
                        query = ""
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(start = 20.dp, end = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("Settings", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                CloseButton(onClick = ::requestClose)
            }
            Divider()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.width(216.dp).fillMaxHeight().background(tc.p).padding(vertical = 12.dp, horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SettingsSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        focusRequester = searchFocus,
                        onSubmit = { results.firstOrNull()?.let(::openResult) },
                    )
                    // Scrolls only if the page list ever outgrows the space under the search field.
                    SettingsNavList(
                        selected = selectedPage,
                        visiblePages = visiblePages,
                        searching = searching,
                        // A sub-page's results count on its parent's nav item, the only one the nav shows for it.
                        resultCounts = results.groupingBy { it.page.navPage }.eachCount(),
                        totalResults = results.size,
                        onAllResults = { runCatching { searchFocus.requestFocus() } },
                        onPick = { page ->
                            // Leaving the results for a page is a plain page switch.
                            query = ""
                            if (page != selectedPage) requestPageSwitch(page)
                            reclaimFocus()
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(tc.br))
                Box(Modifier.weight(1f).fillMaxHeight().background(tc.bg)) {
                    if (searching) SettingsSearchResults(query, results, ::openResult, Modifier.fillMaxSize())
                    CompositionLocalProvider(LocalSettingsAnchors provides anchors) {
                        Column(
                            Modifier.fillMaxSize()
                                // While results are showing, the selected page stays composed but is
                                // neither placed nor drawn: leaving it out of the tree would drop its
                                // unsaved AI-provider edits, which the page-switch guard relies on.
                                .then(if (searching) Modifier.composedButHidden() else Modifier)
                                .verticalScroll(contentScroll)
                                .onGloballyPositioned { anchors.content = it }
                                .padding(start = 24.dp, top = 20.dp, end = 28.dp, bottom = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            selectedPage.parent?.let { parent ->
                                AccentLink("‹ ${parent.title}", onClick = {
                                    requestPageSwitch(parent)
                                    reclaimFocus()
                                })
                            }
                            SettingsPageHeader(selectedPage.group.label, selectedPage.title, selectedPage.description)
                            SettingsPageBody(
                                state,
                                selectedPage,
                                ::reclaimFocus,
                                // A drill-in row opens its sub-page the way a nav click opens a page.
                                onOpenPage = { target -> requestPageSwitch(target) },
                            ) { aiProviderGuard = it }
                        }
                    }
                    if (!searching) {
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(contentScroll),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                            style = appScrollbarStyle(tc),
                        )
                    }
                }
            }
            SettingsFooter(
                onShortcuts = { state.shortcutsOpen = true },
                // Closes Settings first (unlike the shortcuts popup): the assistant is a dialog of
                // its own. Unsaved AI-provider edits get the usual close prompt instead.
                onSetupAssistant = {
                    if (selectedPage == SettingsPage.AiProviders && aiProviderGuard?.isDirty == true) {
                        requestClose()
                    } else {
                        state.rerunSetupAssistant()
                    }
                },
                onVersion = {
                    query = ""
                    if (selectedPage != SettingsPage.About) requestPageSwitch(SettingsPage.About)
                    reclaimFocus()
                },
                onDone = ::requestClose,
            )
        }
    }

    pendingPageSwitch?.let { target ->
        SettingsConfirmDialog(
            title = "Save changes to ${aiProviderGuard?.profileName.orEmpty()}?",
            message = "You changed this provider's settings without saving. Save them before switching pages?",
            error = guardSaveError,
            onDismissRequest = { pendingPageSwitch = null; guardSaveError = null },
        ) {
            DialogActionButton("Save", active = true) {
                val err = aiProviderGuard?.save?.invoke()
                if (err == null) {
                    pendingPageSwitch = null
                    guardSaveError = null
                    selectedPage = target
                } else {
                    guardSaveError = err
                }
            }
            DialogActionButton("Discard", active = false, danger = true) {
                pendingPageSwitch = null
                guardSaveError = null
                selectedPage = target
            }
            DialogActionButton("Cancel", active = false) { pendingPageSwitch = null; guardSaveError = null }
        }
    }

    if (pendingClose) {
        SettingsConfirmDialog(
            title = "Save changes to ${aiProviderGuard?.profileName.orEmpty()}?",
            message = "You changed this provider's settings without saving. Save them before closing Settings?",
            error = guardSaveError,
            onDismissRequest = { pendingClose = false; guardSaveError = null },
        ) {
            DialogActionButton("Save", active = true) {
                val err = aiProviderGuard?.save?.invoke()
                if (err == null) {
                    pendingClose = false
                    guardSaveError = null
                    onDismiss()
                } else {
                    guardSaveError = err
                }
            }
            DialogActionButton("Discard", active = false, danger = true) {
                pendingClose = false
                guardSaveError = null
                onDismiss()
            }
            DialogActionButton("Cancel", active = false) { pendingClose = false; guardSaveError = null }
        }
    }
}

/** What [page] shows, in the content column under its header. The interim bodies reuse each setting's existing control. */
@Composable
private fun SettingsPageBody(
    state: AppState,
    page: SettingsPage,
    reclaimFocus: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
    onProviderGuard: (AiProviderGuard) -> Unit,
) {
    when (page) {
        SettingsPage.General -> GeneralSettingsSection(state, reclaimFocus)
        SettingsPage.Appearance -> AppearanceSettingsSection(state, reclaimFocus)
        SettingsPage.TabsToolbar -> TabsToolbarSettingsSection(state, reclaimFocus)
        SettingsPage.LogView -> LogViewSettingsSection(state, reclaimFocus)
        SettingsPage.SearchFilters -> SearchFiltersSettingsSection(state, reclaimFocus)
        SettingsPage.IssueCategories -> IssueCategoriesSettingsSection(state, reclaimFocus)
        SettingsPage.SourceCode -> SourceCodeSettingsSection(state, reclaimFocus)
        SettingsPage.NotesWriting -> NotesWritingSettingsSection(state, reclaimFocus)
        SettingsPage.CopyExport -> CopyExportSettingsSection(state, reclaimFocus)
        SettingsPage.CaptureVideo -> CaptureVideoSettingsSection(state, reclaimFocus, onOpenPage)
        SettingsPage.CaptureTools -> CaptureToolsSettingsSection(state, reclaimFocus)
        SettingsPage.CaptureLimits -> CaptureLimitsSettingsSection(state, reclaimFocus)
        SettingsPage.CaptureMarkIssue -> CaptureMarkIssueSettingsSection(state, reclaimFocus)
        SettingsPage.CaptureVideoPlayback -> CaptureVideoPlaybackSettingsSection(state, reclaimFocus)
        SettingsPage.AiProviders -> AiProviderSettingsSection(state, onProviderGuard)
        SettingsPage.AiCommands -> CustomAiCommandsSettingsSection(state)
        SettingsPage.VoiceInput -> VoiceInputSettingsSection(state, reclaimFocus)
        SettingsPage.TestRuns -> TestRunsSettingsSection(state, reclaimFocus)
        SettingsPage.IssueTracker -> IssueTrackerSettingsSection(state, reclaimFocus)
        SettingsPage.Storage -> StorageSettingsSection(state, reclaimFocus)
        SettingsPage.Integrations -> IntegrationsSettingsSection(state, reclaimFocus)
        SettingsPage.About -> AboutSettingsSection(state)
    }
}

/** Shared modal shape for the confirm/discard prompts in this file - title, message, an optional
 *  inline error (surfaced when a Save attempt from within the dialog fails validation), and a
 *  caller-supplied row of [DialogActionButton]s. */
@Composable
internal fun SettingsConfirmDialog(
    title: String,
    message: String,
    onDismissRequest: () -> Unit,
    error: String? = null,
    buttons: @Composable RowScope.() -> Unit,
) {
    val tc = tc()
    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            Modifier.width(460.dp).background(tc.p, RoundedCornerShape(8.dp))
                .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(20.dp),
        ) {
            AppText(title, color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            AppText(message, color = tc.td, fontSize = 11.sp, maxLines = 3)
            error?.let {
                Spacer(Modifier.height(6.dp))
                AppText(it, color = DANGER_RED, fontSize = 11.sp, maxLines = 3)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                content = buttons,
            )
        }
    }
}

/** A bounded, scrollable collection used for Settings sections that can grow without bound.
 *
 * Keeping the add/register action outside this viewport makes it available even when a long list
 * is scrolled to the bottom, matching the Theme gallery's compact scrollbar treatment. */
@Composable
internal fun SettingsScrollableRows(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tc = tc()
    val scrollState = rememberScrollState()
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.fillMaxWidth().height(148.dp)
            .clip(shape)
            .background(tc.p2)
            .border(1.dp, tc.br, shape)
            .padding(8.dp),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
            style = appScrollbarStyle(tc),
        )
    }
}

/** A text link in the accent colour with the hover highlight the footer's "GitHub repository" link uses. */
@Composable
internal fun AccentLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tc = tc()
    HoverBox(modifier = modifier.clip(RoundedCornerShape(4.dp)), onClick = onClick) {
        Box(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            AppText(label, color = tc.ac, fontSize = 11.sp, fontFamily = UI)
        }
    }
}

@Composable
private fun TabsToolbarSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    TabsAppearanceSettings(state, reclaimFocus)
}

/**
 * A row of compact settings spread across the full content width: cells keep their natural width,
 * the first starts on the left edge, the last ends on the right edge, the space between is even,
 * and the row wraps onto a second line if the cells cannot fit. For several rows that should line
 * up as columns use [SettingsGrid] instead.
 */
@Composable
internal fun SettingsControlRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

/**
 * Compact settings on a grid with [columns] columns; the children fill it row by row. Each column is
 * exactly as wide as its widest cell (label or control) and the width left over is split into equal
 * gaps between columns, so the first column starts on the left edge, the last column's widest cell
 * ends on the right edge, and every column reads as one clean left edge. Cells are top-aligned
 * and start-aligned in their column.
 */
@Composable
internal fun SettingsGrid(
    columns: Int,
    modifier: Modifier = Modifier,
    rowSpacing: Dp = 10.dp,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints(maxWidth = width)) }
        val rows = placeables.chunked(columns)
        val columnWidths = List(columns) { column -> rows.maxOfOrNull { it.getOrNull(column)?.width ?: 0 } ?: 0 }
        val gap = if (columns > 1) ((width - columnWidths.sum()) / (columns - 1).toFloat()).coerceAtLeast(0f) else 0f
        val spacing = rowSpacing.roundToPx()
        val rowHeights = rows.map { row -> row.maxOfOrNull { it.height } ?: 0 }
        val height = rowHeights.sum() + spacing * (rows.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            rows.forEachIndexed { rowIndex, row ->
                var x = 0f
                row.forEachIndexed { column, placeable ->
                    placeable.placeRelative(x.roundToInt(), y)
                    x += columnWidths[column] + gap
                }
                y += rowHeights[rowIndex] + spacing
            }
        }
    }
}

// Reuses MS_PER_MINUTE/MS_PER_SECOND from McpInfoDialog.kt (same package); only the hour/day/
// absolute-date tiers below are new, since agoLabel() there only ever needs seconds/minutes for
// "last seen" client freshness.
internal const val MS_PER_HOUR = 60 * MS_PER_MINUTE
internal const val MS_PER_DAY = 24 * MS_PER_HOUR

internal fun sourceIndexAgeLabel(builtAt: Long): String {
    val delta = System.currentTimeMillis() - builtAt
    return when {
        delta < AGO_JUST_NOW_MS -> "just now"
        delta < MS_PER_MINUTE -> "${delta / MS_PER_SECOND}s ago"
        delta < MS_PER_HOUR -> "${delta / MS_PER_MINUTE} min ago"
        delta < MS_PER_DAY -> "${delta / MS_PER_HOUR} h ago"
        else -> java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(java.util.Date(builtAt))
    }
}

// The repo's own .mcp.json can get away with a relative "mcp-server/src/index.ts" because tools
// that auto-discover it (Claude Code) spawn the server with cwd already at the project root.
// This copy-for-other-tools snippet can't assume that — a client like LM Studio spawns MCP
// servers from ITS OWN working directory, so a relative path there resolves to nothing and the
// server fails to start.
//
// user.dir is only a reliable stand-in for "the project root" during an unpackaged dev run
// (./gradlew desktopRun sets the JVM's working directory there). The control server can also be
// turned on from Settings in a normal installed .dmg/.deb/.msi — that's the common case, not a
// dev-only path — and there user.dir is whatever the OS handed the launched app (often "/" for a
// Indagium serves MCP natively over Streamable HTTP at /mcp — any MCP client (LM Studio, Claude
// Code, Codex) connects with just this URL, no Node bridge / npm / repo checkout to install. The
// snippet is the standard mcpServers-with-url form those clients accept. `token` is required on
// every request (see debug/ControlServer.kt's start()); it rides along as a `headers` block the
// same way any bearer-token MCP server config does, so "paste this JSON" keeps working end to end.
internal fun mcpConfigSnippet(port: Int, token: String): String =
    """
    {
      "mcpServers": {
        "indagium-control": {
          "url": "${mcpUrl(port)}",
          "headers": {
            "Authorization": "Bearer $token"
          }
        }
      }
    }
    """.trimIndent()

internal fun codexMcpConfigSnippet(port: Int, token: String): String =
    """
    [mcp_servers.indagium]
    url = "${mcpUrl(port)}"
    http_headers = { Authorization = "Bearer $token" }

    [mcp_servers.indagium.tools.list_tabs]
    approval_mode = "approve"
    """.trimIndent()

internal fun mcpUrl(port: Int): String = "http://127.0.0.1:$port/mcp"

@Composable
internal fun RowWrapControl(auto: Boolean, wrapChars: Int, onToggleAuto: () -> Unit, onWrapCharsChange: (Int) -> Unit) {
    val tc = tc()
    var wrapLimitText by remember(wrapChars) { mutableStateOf(wrapChars.toString()) }
    Row(
        Modifier
            .border(0.5.dp, tc.br, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .height(28.dp)
                .background(if (auto) tc.ac.copy(alpha = .2f) else Color.Transparent)
                .clickable(onClick = onToggleAuto)
                .padding(horizontal = 10.dp),
        ) {
            AppText(
                "Auto",
                color = if (auto) tc.ac else tc.ts,
                fontSize = 12.sp,
                fontWeight = if (auto) FontWeight.Medium else FontWeight.Normal,
            )
        }
        Box(Modifier.width(0.5.dp).height(28.dp).background(tc.br))
        if (auto) {
            Box(Modifier.width(60.dp).height(28.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                AppText(wrapLimitText, color = tc.td.copy(alpha = 0.5f), fontSize = 12.sp, fontFamily = MONO)
            }
        } else {
            BasicTextField(
                value = wrapLimitText,
                onValueChange = { value ->
                    val digits = value.filter { it.isDigit() }.take(5)
                    wrapLimitText = digits
                    digits.toIntOrNull()?.let { onWrapCharsChange(it.coerceIn(80, 20_000)) }
                },
                textStyle = TextStyle(color = tc.tx, fontSize = 12.sp, fontFamily = MONO),
                cursorBrush = SolidColor(tc.ac),
                singleLine = true,
                modifier = Modifier.width(60.dp).height(28.dp).padding(horizontal = 8.dp),
                decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { inner() } },
            )
        }
    }
}

/**
 * WP8: generalised so both the Settings "Theme" control and the per-document diagram picker in
 * `Seq3Workspace.kt` share one card gallery instead of Settings having the only visual chooser and
 * everything else falling back to a text dropdown. [settings] is only
 * needed to resolve the *colors* of the leading "Follow app theme" tile (via
 * [resolveSeq3ThemeColors]) when [followAppTheme] is true — selection itself is driven entirely by
 * [selected]/[onSelect] so this has no idea whether it's picking the app theme, a per-document
 * theme, or a default-for-new-documents.
 */
@Composable
internal fun ThemeGallery(
    settings: AppSettings,
    selected: ThemePreset?,
    onSelect: (ThemePreset?) -> Unit,
    // Null shows every theme at natural height with no inner scroll (Settings, setup assistant);
    // a fixed height keeps the compact scrolling gallery of the diagram theme picker.
    height: Dp? = 148.dp,
    followAppTheme: Boolean = false,
) {
    val tc = tc()
    val themeScroll = rememberScrollState()
    Box(Modifier.fillMaxWidth().then(if (height != null) Modifier.height(height) else Modifier)) {
        FlowRow(
            Modifier.fillMaxWidth().then(if (height != null) Modifier.verticalScroll(themeScroll).padding(end = 12.dp) else Modifier),
            // Natural height: no scrollbar to leave room for, and the cards spread edge to edge so
            // the controls below line up with the gallery's right edge.
            horizontalArrangement = if (height != null) Arrangement.spacedBy(8.dp) else Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (followAppTheme) {
                ThemeWindowCard(
                    label = "Follow app theme",
                    colors = resolveSeq3ThemeColors(null, settings),
                    selected = selected == null,
                    onClick = { onSelect(null) },
                )
            }
            ThemePreset.entries.forEach { preset ->
                ThemeWindowCard(
                    label = preset.label,
                    colors = themeColors(preset),
                    selected = preset == selected,
                    onClick = { onSelect(preset) },
                )
            }
        }
        if (height != null) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(themeScroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
                style = appScrollbarStyle(tc),
            )
        }
    }
}

@Composable
internal fun ThemeWindowCard(label: String, colors: ThemeColors, selected: Boolean, onClick: () -> Unit) {
    val tc = tc()
    val shape = RoundedCornerShape(8.dp)
    var hovered by remember { mutableStateOf(false) }
    Column(
        Modifier.width(118.dp).height(66.dp)
            .clip(shape)
            .background(if (hovered && !selected) colors.ac.copy(.08f) else colors.bg)
            .border(1.dp, if (selected || hovered) colors.ac else tc.br, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(14.dp).background(colors.p, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(colors.ac, colors.seq1, colors.seq2).forEach { color ->
                Box(Modifier.size(4.dp).background(color, RoundedCornerShape(50)))
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(colors.p2, RoundedCornerShape(4.dp))) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(4.dp).background(colors.ac, CORNER_SM))
            Row(
                Modifier.align(Alignment.BottomEnd).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(Modifier.size(8.dp).background(colors.seq1, CORNER_SM))
                Box(Modifier.size(8.dp).background(colors.seq2, CORNER_SM))
            }
        }
        AppText(
            text = label,
            color = if (selected) colors.ac else tc.ts,
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    }
}

@Composable
internal fun CompactSettingWithTooltip(
    label: String,
    tooltip: String,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    labelMaxLines: Int = 1,
    labelAreaHeight: Dp? = null,
    content: @Composable () -> Unit,
) {
    val tc = tc()
    Column(
        modifier.settingsAnchor(label),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val labelContent: @Composable () -> Unit = {
            TooltipArea(
                tooltip = {
                    Box(
                        Modifier
                            .background(tc.p2, RoundedCornerShape(4.dp))
                            .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        AppText(tooltip, color = tc.tx, fontSize = 11.sp, maxLines = 2)
                    }
                },
            ) {
                AppText(
                    label,
                    color = tc.td,
                    fontSize = 10.sp,
                    fontFamily = UI,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = labelMaxLines,
                )
            }
        }
        if (labelAreaHeight == null) {
            labelContent()
        } else {
            Box(Modifier.height(labelAreaHeight)) { labelContent() }
        }
        content()
    }
}

@Composable
internal fun CompactSetting(
    label: String,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable () -> Unit,
) {
    val tc = tc()
    Column(
        modifier.settingsAnchor(label),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = horizontalAlignment,
    ) {
        AppText(label, color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        content()
    }
}
