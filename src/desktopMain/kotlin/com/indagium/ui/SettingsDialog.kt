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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.ai.CustomAiCommand
import com.indagium.ai.ModelDiscoveryResult
import com.indagium.ai.OpenAiCompatibleProvider
import com.indagium.ai.normalizeAiProviderProfiles
import com.indagium.model.*
import com.indagium.ui.holiday.*
import com.indagium.voice.VoiceLanguageCatalog
import com.indagium.voice.VoiceModelCatalog
import com.indagium.voice.VoiceModelCheckState
import com.indagium.voice.VoiceModelInstallResult
import com.indagium.voice.VoiceModelInstaller
import com.indagium.voice.VoiceRecognitionEngines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
private class AiProviderGuard(val isDirty: Boolean, val profileName: String, val save: () -> String?)

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
                        resultCounts = results.groupingBy { it.page }.eachCount(),
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
        SettingsPage.CaptureVideo -> CaptureVideoSettingsSection(state)
        SettingsPage.AiProviders -> AiProviderSettingsSection(state, onProviderGuard)
        SettingsPage.AiCommands -> CustomAiCommandsSettingsSection(state)
        SettingsPage.VoiceInput -> VoiceInputSettingsSection(state)
        SettingsPage.TestRuns -> TestRunsSettingsSection(state)
        SettingsPage.IssueTracker -> IssueTrackerSettingsSection(state)
        SettingsPage.Storage -> StorageSettingsSection(state)
        SettingsPage.Integrations -> IntegrationsSettingsSection(state)
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

/** The three AI test folders: Settings › Test runs shows them, Storage & files shows every other save folder. */
private val TEST_FOLDER_KINDS = listOf(SaveFolderKind.TEST_SUITES, SaveFolderKind.TEST_RUNS, SaveFolderKind.TEST_ISSUES)

@Composable
private fun StorageSettingsSection(state: AppState) {
    val tc = tc()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SaveFolderKind.entries.filter { it !in TEST_FOLDER_KINDS }.forEach { kind -> SaveFolderSetting(state, kind) }
        state.testStorageStatus?.let { message -> AppText(message, color = DANGER_RED, fontSize = 11.sp, maxLines = 3) }
    }
    SettingsControlRow {
        AutoSaveSetting(state)
        FilterBackupsSetting(state)
    }
    Column(Modifier.settingsAnchor("Storage"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText(
            "Storage",
            color = tc.td,
            fontSize = 10.sp,
            fontFamily = UI,
            fontWeight = FontWeight.SemiBold,
        )
        val appDataPath = state.appCachePath
        TooltipArea(
            tooltip = {
                Box(
                    Modifier
                        .background(tc.p2, RoundedCornerShape(4.dp))
                        .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    AppText(appDataPath, color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
                }
            },
        ) {
            AppText(
                truncatePathForDisplay(appDataPath),
                color = tc.ts,
                fontSize = 11.sp,
                fontFamily = MONO,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            Modifier.settingsAnchor("Temporary data"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TooltipArea(
                tooltip = {
                    StorageInfoTooltip(
                        "Downloaded archive cache and app-managed notes. Clear temporary data removes these items.",
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                AppText(
                    "Temporary data · ${formatByteSize(state.temporaryDataSizeBytes)}  ⓘ",
                    color = tc.ts,
                    fontSize = 11.sp,
                    fontFamily = MONO,
                )
            }
            AppButton("Clear temporary data", onClick = { state.requestClearCache() }, variant = ButtonVariant.Secondary)
        }
        Row(
            Modifier.settingsAnchor("App data"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TooltipArea(
                tooltip = {
                    StorageInfoTooltip(
                        "Everything Indagium stores in this folder: temporary data, settings, current session/autosave, " +
                            "saved filters, source and case indexes, diagnostics, and integration data.",
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                AppText(
                    "App data · ${formatByteSize(state.appDataSizeBytes)}  ⓘ",
                    color = tc.ts,
                    fontSize = 11.sp,
                    fontFamily = MONO,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AppButton("Reset app data…", onClick = { state.requestResetAppData() }, variant = ButtonVariant.Secondary, isDanger = true)
        }
    }
    state.autosaveError?.let { message ->
        AppText(message, color = DANGER_RED, fontSize = 11.sp, maxLines = 2)
    }
}

/** Test runs: what a new run starts from, then the three AI test folders (kept with the lock hint while a run is active). */
@Composable
private fun TestRunsSettingsSection(state: AppState) {
    TestingSettingsSection(state)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TEST_FOLDER_KINDS.forEach { kind -> SaveFolderSetting(state, kind) }
        state.testStorageStatus?.let { message -> AppText(message, color = DANGER_RED, fontSize = 11.sp, maxLines = 3) }
    }
}

/** The row for one of the save folders, wired exactly as Settings → General shows it; the setup
 *  assistant reuses it for the folders it offers. */
@Composable
internal fun SaveFolderSetting(state: AppState, kind: SaveFolderKind) {
    val locked = kind.affectsTestStorage && state.testRunInProgress
    when (kind) {
        SaveFolderKind.ROOT -> SaveFolderRow(
            label = "Default save folder",
            tooltip = "Parent folder every other save location below defaults under when it isn't " +
                "set on its own. Defaults to your Documents folder.",
            explicitValue = state.settings.saveRootDir,
            effectivePath = state.effectiveSaveRootDir.absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.ROOT) },
            onReset = { state.resetSaveFolder(SaveFolderKind.ROOT) },
            enabled = !locked,
            disabledHint = TEST_RUN_LOCK_HINT,
        )
        SaveFolderKind.ANALYSIS -> SaveFolderRow(
            label = "Analysis artifacts folder",
            tooltip = "Where analysis notes, filtered exports and split logs are saved. Auto-saved " +
                "notes are written here, created on first save.",
            explicitValue = state.settings.defaultSaveDir,
            effectivePath = state.effectiveAnalysisDirForDisplay().absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.ANALYSIS) },
            onReset = { state.resetSaveFolder(SaveFolderKind.ANALYSIS) },
        )
        SaveFolderKind.SESSIONS -> SaveFolderRow(
            label = "Capture sessions folder",
            tooltip = "Where new device captures are recorded. A change applies from the next " +
                "Start; sessions already recorded stay exactly where they are.",
            explicitValue = state.settings.captureSessionsDir,
            effectivePath = state.effectiveCaptureSessionsDir().absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.SESSIONS) },
            onReset = { state.resetSaveFolder(SaveFolderKind.SESSIONS) },
        )
        SaveFolderKind.SNAPSHOTS -> SaveFolderRow(
            label = "Snapshots folder",
            tooltip = "Destination for \"Save snapshot\" while a capture is recording.",
            explicitValue = state.settings.captureSnapshotsDir,
            effectivePath = state.effectiveCaptureSnapshotsDir().absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.SNAPSHOTS) },
            onReset = { state.resetSaveFolder(SaveFolderKind.SNAPSHOTS) },
        )
        SaveFolderKind.ZIP -> SaveFolderRow(
            label = "Saved captures folder (Save ZIP)",
            tooltip = "Destination for \"Save ZIP\" on a stopped or retained capture.",
            explicitValue = state.settings.captureZipDir,
            effectivePath = state.effectiveCaptureZipDirForDisplay().absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.ZIP) },
            onReset = { state.resetSaveFolder(SaveFolderKind.ZIP) },
        )
        SaveFolderKind.TEST_SUITES -> SaveFolderRow(
            label = "Test suites folder",
            tooltip = "Where your AI test suites are kept (library.json, suites/ and golden-screenshot assets/). " +
                "Changing it loads the suites found there; the old folder is left as it is.",
            explicitValue = state.settings.testSuitesDir,
            effectivePath = state.effectiveTestSuitesDirForDisplay().absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.TEST_SUITES) },
            onReset = { state.resetSaveFolder(SaveFolderKind.TEST_SUITES) },
            enabled = !locked,
            disabledHint = TEST_RUN_LOCK_HINT,
        )
        SaveFolderKind.TEST_RUNS -> SaveFolderRow(
            label = "Test runs folder",
            tooltip = "Where each AI test run keeps its evidence (run.json, lane recordings, screenshots, transcripts). " +
                "Changing it lists the runs found there; the old folder is left as it is.",
            explicitValue = state.settings.testRunsDir,
            effectivePath = state.effectiveTestRunsDirForDisplay().absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.TEST_RUNS) },
            onReset = { state.resetSaveFolder(SaveFolderKind.TEST_RUNS) },
            enabled = !locked,
            disabledHint = TEST_RUN_LOCK_HINT,
        )
        SaveFolderKind.TEST_ISSUES -> SaveFolderRow(
            label = "Issues folder",
            tooltip = "Where the issues made from failed test steps are kept, one folder each with its evidence. " +
                "Changing it lists the issues found there; the old folder is left as it is.",
            explicitValue = state.settings.testIssuesDir,
            effectivePath = state.effectiveTestIssuesDirForDisplay().absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.TEST_ISSUES) },
            onReset = { state.resetSaveFolder(SaveFolderKind.TEST_ISSUES) },
            enabled = !locked,
            disabledHint = TEST_RUN_LOCK_HINT,
        )
    }
}

private const val TEST_RUN_LOCK_HINT = "Unavailable while a test run is in progress. Change it after the run has finished."

/**
 * One row of the Save folders group (GeneralSettingsSection above): a labeled
 * tooltip, the effective path (dimmer with a "(default)" suffix when nothing is explicitly set —
 * [explicitValue] is null), a Browse button, and a Reset button that only shows once the folder
 * has actually been set to something other than its computed default.
 */
@Composable
internal fun SaveFolderRow(
    label: String,
    tooltip: String,
    explicitValue: String?,
    effectivePath: String,
    onBrowse: () -> Unit,
    onReset: () -> Unit,
    // False greys Browse and Reset out (the folder cannot change right now); [disabledHint] is the tooltip that says why.
    enabled: Boolean = true,
    disabledHint: String? = null,
) {
    val tc = tc()
    Column(Modifier.settingsAnchor(label), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TooltipArea(
            tooltip = {
                Box(
                    Modifier
                        .background(tc.p2, RoundedCornerShape(4.dp))
                        .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    AppText(tooltip, color = tc.tx, fontSize = 11.sp, maxLines = 3)
                }
            },
        ) {
            AppText(label, color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val displayText = truncatePathForDisplay(effectivePath).let {
                if (explicitValue != null) it else "$it  (default)"
            }
            val pathText: @Composable () -> Unit = {
                AppText(
                    displayText,
                    color = if (explicitValue != null) tc.ts else tc.td,
                    fontSize = 11.sp,
                    fontFamily = MONO,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TooltipArea(
                tooltip = {
                    Box(
                        Modifier
                            .background(tc.p2, RoundedCornerShape(4.dp))
                            .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        AppText(effectivePath, color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
                    }
                },
                modifier = Modifier.weight(1f),
            ) { pathText() }
            val buttons: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppButton("Browse", onClick = onBrowse, enabled = enabled)
                    if (explicitValue != null) AppButton("Reset", onClick = onReset, enabled = enabled)
                }
            }
            if (!enabled && disabledHint != null) {
                TooltipArea(
                    tooltip = {
                        Box(
                            Modifier
                                .background(tc.p2, RoundedCornerShape(4.dp))
                                .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            AppText(disabledHint, color = tc.tx, fontSize = 11.sp, maxLines = 3)
                        }
                    },
                ) { buttons() }
            } else {
                buttons()
            }
        }
    }
}

@Composable
private fun StorageInfoTooltip(text: String) {
    val tc = tc()
    Box(
        Modifier
            .width(330.dp)
            .background(tc.p2, RoundedCornerShape(4.dp))
            .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        AppText(text, color = tc.tx, fontSize = 11.sp, maxLines = 4)
    }
}

/** Settings › Capture & video: the capture settings, then the two video-playback settings that used to sit under Editor behavior. */
@Composable
private fun CaptureVideoSettingsSection(state: AppState) {
    val finalRowLabelAreaHeight = 28.dp
    CaptureSettingsSection(state)
    SettingsGrid(columns = 4, rowSpacing = 16.dp) {
        CompactSettingWithTooltip(
            label = "Video follow readout",
            tooltip = "Shows the \"video → log → holding at ...\" diagnostic line under the video transport " +
                "bar, explaining exactly what Follow is doing at the current playhead position.",
            labelMaxLines = 2,
            labelAreaHeight = finalRowLabelAreaHeight,
        ) {
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.showVideoFollowReadout) 0 else 1),
                onToggle = { idx -> state.updateSettings { it.copy(showVideoFollowReadout = idx == 0) } },
            )
        }
        CompactSettingWithTooltip(
            label = "New video links:\ndouble-click seeks",
            tooltip = "The default for a newly linked log/video anchor. Each video link can then be toggled " +
                "independently from its header; this does not affect existing links or Follow.",
            labelMaxLines = 2,
            labelAreaHeight = finalRowLabelAreaHeight,
        ) {
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.enableDoubleClickVideoSeekOnLink) 0 else 1),
                onToggle = { idx -> state.updateSettings { it.copy(enableDoubleClickVideoSeekOnLink = idx == 0) } },
            )
        }
    }
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

@Composable
private fun AutoSaveSetting(state: AppState) {
    CompactSettingWithTooltip(
        label = "Auto-save",
        tooltip = "Saves note Markdown and its .ann sidecar after note changes.",
    ) {
        SegmentedControl(
            options = listOf("On", "Off"),
            selectedIndices = setOf(if (state.settings.autoExportNotes) 0 else 1),
            onToggle = { idx -> state.updateSettings { it.copy(autoExportNotes = idx == 0) } },
        )
    }
}

@Composable
private fun FilterBackupsSetting(state: AppState) {
    CompactSettingWithTooltip(
        label = "Filter backups",
        tooltip = "Writes timestamped saved-filter backups after saved-filter changes.",
    ) {
        SegmentedControl(
            options = listOf("On", "Off"),
            selectedIndices = setOf(if (state.settings.autoSaveFilters) 0 else 1),
            onToggle = { idx -> state.updateSettings { it.copy(autoSaveFilters = idx == 0) } },
        )
    }
}

@Composable
private fun IntegrationsSettingsSection(state: AppState) {
    val tc = tc()
    SettingsControlRow {
        CompactSetting("MCP control server") {
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.mcpControlEnabled) 0 else 1),
                onToggle = { idx -> state.setMcpControlEnabled(idx == 0, state.settings.mcpControlPort) },
            )
        }
        // (SEC-1) Off by default: CORS lets any origin a browser has open issue cross-origin requests
        // to this loopback server. Bearer-token auth still gates every request either way — this only
        // controls whether a browser is additionally allowed to do that at all. Opt-in for the
        // uncommon case of a browser-based MCP inspector.
        CompactSetting("Allow browser-based MCP clients (CORS)") {
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.mcpAllowBrowserClients) 0 else 1),
                onToggle = { idx -> state.setMcpAllowBrowserClients(idx == 0) },
            )
        }
        CompactSetting("Port") {
            var portText by remember(state.settings.mcpControlPort) {
                mutableStateOf(state.settings.mcpControlPort.toString())
            }
            InlineField(
                portText,
                { v ->
                    val digits = v.filter { it.isDigit() }.take(5)
                    portText = digits
                    digits.toIntOrNull()?.coerceIn(MIN_PORT, MAX_PORT)?.let { p ->
                        if (state.settings.mcpControlEnabled) state.setMcpControlEnabled(true, p)
                        else state.updateSettings { it.copy(mcpControlPort = p) }
                    }
                },
                "8991",
                Modifier.width(72.dp),
                fontSize = 12.sp,
            )
        }
    }
    state.mcpControlError?.let { message ->
        AppText(message, color = DANGER_RED, fontSize = 11.sp, maxLines = 2)
    }
    CompactSetting("Connection info") {
        // Deliberately doesn't close Settings first — stacks on top instead, so closing
        // this popup returns you to Settings rather than to the main window.
        AppButton("Connection info…", onClick = { state.mcpInfoOpen = true }, variant = ButtonVariant.Secondary)
    }
    CompactSetting("Debug logging") {
        SegmentedControl(
            options = listOf("On", "Off"),
            selectedIndices = setOf(if (state.settings.debugLoggingEnabled) 0 else 1),
            onToggle = { idx -> state.setDebugLoggingEnabled(idx == 0) },
        )
    }
    Row(
        Modifier.settingsAnchor("Debug log file"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fullPath = state.settings.debugLogFilePath
        val pathText: @Composable () -> Unit = {
            AppText(
                fullPath?.let { truncatePathForDisplay(it) } ?: "(not set)",
                color = tc.ts, fontSize = 11.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis,
            )
        }
        if (fullPath != null) {
            TooltipArea(
                tooltip = {
                    Box(
                        Modifier
                            .background(tc.p2, RoundedCornerShape(4.dp))
                            .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        AppText(fullPath, color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
                    }
                },
                modifier = Modifier.weight(1f, fill = false),
            ) { pathText() }
        } else {
            Box(Modifier.weight(1f, fill = false)) { pathText() }
        }
        AppButton("Browse", onClick = { state.pickDebugLogFile() })
        AppButton(
            "Open current log",
            onClick = { state.openCurrentDebugLog() },
            variant = ButtonVariant.Secondary,
            enabled = fullPath?.let { File(it).isFile } == true,
        )
    }
    state.debugLoggingError?.let { message ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText("Diagnostic logging unavailable: $message", color = DANGER_RED, fontSize = 11.sp, maxLines = 2)
            AppButton("Retry", onClick = { state.retryDebugLoggingConfiguration() }, variant = ButtonVariant.Secondary)
        }
    }
}

@Composable
private fun VoiceInputSettingsSection(state: AppState) {
    val tc = tc()
    val scope = rememberCoroutineScope()
    val voiceSettings = state.settings.voiceInput
    val selectedModel = VoiceModelCatalog.byId(voiceSettings.modelId)
    val installer = remember(selectedModel.id) { VoiceModelInstaller(DesktopStorage.voiceModelsDir(), selectedModel) }
    val modelCheckState by installer.checkState.collectAsState()
    val installed = modelCheckState == VoiceModelCheckState.INSTALLED
    val checkingModel = modelCheckState == VoiceModelCheckState.CHECKING
    var installing by remember { mutableStateOf(false) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var languageToAdd by remember { mutableStateOf("") }

    LaunchedEffect(installer) {
        while (true) {
            installer.requestVerification(scope)
            kotlinx.coroutines.delay(5_000)
        }
    }

    fun installModel() {
        installing = true
        statusMessage = null
        scope.launch {
            when (val result = withContext(Dispatchers.IO) {
                installer.install { downloadedBytes = it }
            }) {
                is VoiceModelInstallResult.Installed,
                is VoiceModelInstallResult.AlreadyInstalled -> {
                    installing = false
                    statusMessage = "Local voice model is ready."
                }
                is VoiceModelInstallResult.Failure -> {
                    installing = false
                    statusMessage = result.message
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppText("Voice input", color = tc.td, fontSize = 10.sp, fontFamily = UI)
        AppText(
            "Dictate into the AI composer using the default microphone. Audio stays local; AI providers receive only the text you choose to send.",
            color = tc.tx,
            fontSize = 12.sp,
            maxLines = 4,
        )
        AppText(
            "Recognition engine", color = tc.td, fontSize = 10.sp, fontFamily = UI,
            modifier = Modifier.settingsAnchor("Recognition engine"),
        )
        val engineChoices = VoiceRecognitionEngines.availableChoices()
        SegmentedControl(
            options = engineChoices.map { it.label },
            selectedIndices = setOf(engineChoices.indexOf(voiceSettings.recognitionEngine).coerceAtLeast(0)),
            onToggle = { index ->
                state.updateSettings { settings ->
                    settings.copy(voiceInput = settings.voiceInput.copy(recognitionEngine = engineChoices[index]))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            fillWidth = engineChoices.size <= 2,
        )
        AppText(VoiceRecognitionEngines.description(voiceSettings.recognitionEngine), color = tc.td, fontSize = 10.sp, maxLines = 3)
        if (VoiceRecognitionEngines.supportsTranslation(voiceSettings.recognitionEngine, selectedModel)) {
            CheckRow(
                modifier = Modifier.settingsAnchor("Translate dictated speech to English"),
                checked = voiceSettings.translateToEnglish,
                onToggle = {
                    state.updateSettings { settings ->
                        settings.copy(voiceInput = settings.voiceInput.copy(translateToEnglish = !settings.voiceInput.translateToEnglish))
                    }
                },
            ) {
                AppText("Translate dictated speech to English", color = tc.tx, fontSize = 12.sp)
            }
            AppText(
                if (voiceSettings.translateToEnglish) {
                    "Output mode: English translation after " +
                        "${VoiceLanguageCatalog.label(voiceSettings.selectedRecognitionLanguageCode)} recognition. " +
                        "You can edit the result before sending."
                } else {
                    "Output mode: ${VoiceLanguageCatalog.label(voiceSettings.selectedRecognitionLanguageCode)} " +
                        "transcript. You can edit the result before sending."
                },
                color = tc.td,
                fontSize = 10.sp,
                maxLines = 3,
            )
        } else {
            AppText(
                if (voiceSettings.recognitionEngine == VoiceRecognitionEngine.WHISPER) {
                    "Large v3 Turbo supports transcription only. Your saved English translation preference " +
                        "will apply again when you select a model that supports translation."
                } else {
                    "This OS engine returns the recognized spoken language only; English translation remains available with Whisper."
                },
                color = tc.td,
                fontSize = 10.sp,
                maxLines = 3,
            )
        }
        Divider()
        AppText(
            "Recognition language", color = tc.td, fontSize = 10.sp, fontFamily = UI,
            modifier = Modifier.settingsAnchor("Recognition language"),
        )
        AppText(
            if (voiceSettings.recognitionEngine == VoiceRecognitionEngine.WINDOWS_SPEECH) {
                "Windows Speech needs a matching installed legacy recognizer. For Ukrainian or any unavailable language, choose Local Whisper."
            } else {
                "Automatic detects the spoken language. Choose Ukrainian for short Ukrainian phrases; it avoids an English-recognition bias."
            },
            color = tc.tx,
            fontSize = 12.sp,
            maxLines = 3,
        )
        val selectedLanguageIndex = voiceSettings.recognitionLanguageCodes
            .indexOf(voiceSettings.selectedRecognitionLanguageCode).coerceAtLeast(0)
        SegmentedControl(
            options = voiceSettings.recognitionLanguageCodes.map(VoiceLanguageCatalog::label),
            selectedIndices = setOf(selectedLanguageIndex),
            onToggle = { index ->
                val code = voiceSettings.recognitionLanguageCodes[index]
                state.updateSettings { settings ->
                    settings.copy(voiceInput = settings.voiceInput.copy(selectedRecognitionLanguageCode = code))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            fillWidth = voiceSettings.recognitionLanguageCodes.size <= 3,
        )
        val addableLanguages = VoiceLanguageCatalog.additional.filter { it.code !in voiceSettings.recognitionLanguageCodes }
        if (addableLanguages.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                InlineField(
                    value = languageToAdd,
                    onValue = { languageToAdd = it.lowercase().filter(Char::isLetter).take(3) },
                    placeholder = "Language code, e.g. ru",
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                    onSubmit = {
                        val code = languageToAdd
                        if (addableLanguages.any { it.code == code }) {
                            state.updateSettings { settings ->
                                settings.copy(voiceInput = settings.voiceInput.copy(
                                    recognitionLanguageCodes = settings.voiceInput.recognitionLanguageCodes + code,
                                ))
                            }
                            languageToAdd = ""
                        }
                    },
                )
                AppButton(
                    "Add language",
                    onClick = {
                        val code = languageToAdd
                        if (addableLanguages.any { it.code == code }) {
                            state.updateSettings { settings ->
                                settings.copy(voiceInput = settings.voiceInput.copy(
                                    recognitionLanguageCodes = settings.voiceInput.recognitionLanguageCodes + code,
                                ))
                            }
                            languageToAdd = ""
                        }
                    },
                    enabled = addableLanguages.any { it.code == languageToAdd },
                )
            }
            AppText(
                "Additional supported codes: ${addableLanguages.joinToString { "${it.label} (${it.code})" }}",
                color = tc.td,
                fontSize = 10.sp,
                maxLines = 3,
            )
        }
        val removableLanguages = voiceSettings.recognitionLanguageCodes.filter { code ->
            VoiceLanguageCatalog.defaults.none { it.code == code }
        }
        if (removableLanguages.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                removableLanguages.forEach { code ->
                    AppButton(
                        "Remove ${VoiceLanguageCatalog.label(code)}",
                        onClick = {
                            state.updateSettings { settings ->
                                val languages = settings.voiceInput.recognitionLanguageCodes - code
                                settings.copy(voiceInput = settings.voiceInput.copy(
                                    recognitionLanguageCodes = languages,
                                    selectedRecognitionLanguageCode = settings.voiceInput.selectedRecognitionLanguageCode
                                        .takeIf { it in languages } ?: "auto",
                                ))
                            }
                        },
                        variant = ButtonVariant.Ghost,
                        horizontalPadding = 7.dp,
                    )
                }
            }
        }
        Divider()
        if (voiceSettings.recognitionEngine == VoiceRecognitionEngine.WHISPER) {
            AppText(
                "Local model", color = tc.td, fontSize = 10.sp, fontFamily = UI,
                modifier = Modifier.settingsAnchor("Local model"),
            )
            Seq3DropdownButton(
                label = "${selectedModel.displayName} (${formatByteSize(selectedModel.sizeBytes)})",
                menuWidth = 280.dp,
                fixedHeight = 32.dp,
            ) { close ->
                VoiceModelCatalog.all.forEach { model ->
                    Seq3DropdownMenuItem(
                        label = "${model.displayName} (${formatByteSize(model.sizeBytes)})",
                        active = model.id == selectedModel.id,
                    ) {
                        state.updateSettings { settings ->
                            settings.copy(voiceInput = settings.voiceInput.copy(modelId = model.id))
                        }
                        close()
                    }
                }
            }
            AppText(
                selectedModel.description + " Downloads start only when you choose Download; speech stays local.",
                color = tc.tx,
                fontSize = 12.sp,
                maxLines = 4,
            )
            AppText(
                when {
                    checkingModel -> "Checking local model…"
                    installed -> "Status: installed at Indagium application data/voice-models."
                    else -> "Status: not installed."
                },
                color = if (installed) tc.ac else tc.td,
                fontSize = 11.sp,
            )
            if (installing) AppText("Downloading ${formatByteSize(downloadedBytes)}…", color = tc.ac, fontSize = 11.sp)
            statusMessage?.let { message ->
                AppText(message, color = if (installed) tc.ac else DANGER_RED, fontSize = 10.sp, maxLines = 3)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (installed) {
                    AppButton(
                        "Remove model",
                        onClick = {
                            if (installer.remove()) {
                                statusMessage = "Local voice model removed."
                            } else {
                                statusMessage = "Could not remove the local voice model."
                            }
                        },
                        variant = ButtonVariant.Secondary,
                        isDanger = true,
                        enabled = !installing,
                    )
                    AppButton(
                        "Reinstall",
                        onClick = {
                            installer.remove()
                            installModel()
                        },
                        variant = ButtonVariant.Secondary,
                        enabled = !installing,
                    )
                } else {
                    AppButton(
                        if (installing) "Downloading…" else "Download local model",
                        onClick = ::installModel,
                        variant = ButtonVariant.Primary,
                        enabled = !installing && !checkingModel,
                    )
                }
            }
            AppText("Model license: ${selectedModel.licenseUrl}", color = tc.td, fontSize = 10.sp, maxLines = 2)
        } else {
            AppText("Native engine", color = tc.td, fontSize = 10.sp, fontFamily = UI)
            AppText(
                if (voiceSettings.recognitionEngine == VoiceRecognitionEngine.APPLE_SPEECH) {
                    "At first use macOS asks for Speech Recognition permission. If the selected " +
                        "language has no on-device Apple model, Indagium refuses to send audio and " +
                        "you can switch back to Whisper."
                } else {
                    "Windows Speech uses a matching installed legacy recognizer. Language availability is narrower than Whisper; " +
                        "if there is no recognizer for the selected language, Indagium shows the reason and you can switch back to Whisper."
                },
                color = tc.tx,
                fontSize = 12.sp,
                maxLines = 4,
            )
        }
    }
}

@Composable
private fun AiProviderSettingsSection(state: AppState, onGuardChange: (AiProviderGuard) -> Unit = {}) {
    val tc = tc()
    // Settings migration normally guarantees this invariant, but keeping the editor safe during
    // a transient empty state avoids a compose-time crash while a profile list is being replaced.
    val profiles = normalizeAiProviderProfiles(state.settings.aiProviderProfiles)
    var editingProfileId by remember { mutableStateOf(profiles.firstOrNull { it.selected }?.id.orEmpty()) }
    val profile = profiles.firstOrNull { it.id == editingProfileId }
        ?: profiles.firstOrNull { it.selected }
        ?: profiles.first()
    var name by remember(profile.id, profile.displayName) { mutableStateOf(profile.displayName) }
    var endpoint by remember(profile.id, profile.baseUrl) { mutableStateOf(profile.baseUrl) }
    var model by remember(profile.id, profile.model) { mutableStateOf(profile.model) }
    var kind by remember(profile.id, profile.kind) { mutableStateOf(profile.kind) }
    var executablePath by remember(profile.id, profile.executablePath) { mutableStateOf(profile.executablePath) }
    var reasoningEffort by remember(profile.id, profile.reasoningEffort) { mutableStateOf(profile.reasoningEffort) }
    // Tracks (endpoint text, acknowledged) as a pair rather than a bare boolean so the checkbox
    // reflects the *live* endpoint field: editing the endpoint away from what was last acknowledged
    // shows unchecked again, without a Save round-trip. `acknowledged` below is a plain derived val
    // (never written to directly), so there's no write-triggers-recompute-triggers-write loop risk.
    var ackState by remember(profile.id, profile.remoteDisclosureAcknowledged) {
        mutableStateOf(profile.baseUrl.trim() to profile.remoteDisclosureAcknowledged)
    }
    var apiKey by remember(profile.id) { mutableStateOf(state.aiProviderApiKey(profile.id)) }
    var validationError by remember(profile.id) { mutableStateOf<String?>(null) }
    var connectionTest by remember(profile.id, kind) { mutableStateOf<ModelDiscoveryResult?>(null) }
    var accountCliCheck by remember(profile.id, kind) { mutableStateOf<com.indagium.ai.AccountCliCheck?>(null) }
    var modelDiscovery by remember(profile.id, kind) { mutableStateOf<ModelDiscoveryResult?>(null) }
    var testingConnection by remember(profile.id, kind) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val acknowledged = if (endpoint.trim() == ackState.first) ackState.second else false
    val draft = profile.copy(
        displayName = name.trim().ifBlank { "OpenAI-compatible" },
        baseUrl = endpoint.trim(),
        model = model.trim(),
        remoteDisclosureAcknowledged = acknowledged,
        kind = kind,
        executablePath = executablePath.trim(),
        reasoningEffort = reasoningEffort,
    )
    // Discovery used to only run when the user opened the model dropdown, so switching back to a
    // profile that already had a model+reasoning effort saved showed the model but silently hid
    // the reasoning dropdown (modelDiscovery resets to null on every profile/kind switch) until
    // the model dropdown was clicked again. Running it eagerly keeps the two in sync.
    LaunchedEffect(profile.id, kind) {
        modelDiscovery = state.aiSidebarRuntime.discoverModels(draft, apiKey)
    }
    val isDirty = draft != profile
    SideEffect { onGuardChange(AiProviderGuard(isDirty, profile.displayName) { state.updateAiProviderProfile(draft) }) }

    // Any navigation away from a dirty draft (switching provider, adding a new one, or removing a
    // *different* profile) goes through this instead of acting immediately, so edits are never
    // silently lost. Switching settings section or closing the dialog is guarded the same way, one
    // level up in SettingsDialog, via the published AiProviderGuard above.
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var navigationSaveError by remember { mutableStateOf<String?>(null) }
    var pendingDeleteProfileId by remember { mutableStateOf<String?>(null) }

    fun navigateOrConfirm(action: () -> Unit) {
        if (isDirty) {
            navigationSaveError = null
            pendingNavigation = action
        } else {
            action()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppText("Providers", color = tc.td, fontSize = 10.sp, fontFamily = UI, modifier = Modifier.settingsAnchor("Providers"))
        // Same dropdown-plus-"+" treatment as the AI panel's own provider switcher
        // (AiProviderControls in AiSidebar.kt), for a consistent look and so both places share one
        // battle-tested selection path instead of a second, subtly different one.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AiProviderDropdown(
                profiles = profiles,
                selected = profile,
                onSelect = { id ->
                    navigateOrConfirm {
                        state.selectAiProviderProfile(id)
                        editingProfileId = id
                    }
                },
                modifier = Modifier.weight(1f),
            )
            HoverBox(
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, tc.br, RoundedCornerShape(6.dp)),
                onClick = { navigateOrConfirm { editingProfileId = state.addAiProviderProfile().id } },
            ) {
                AppText("+", color = tc.td, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText(
                "Provider type", color = tc.td, fontSize = 10.sp, fontFamily = UI,
                modifier = Modifier.settingsAnchor("Provider type"),
            )
            SegmentedControl(
                options = com.indagium.model.AiProviderKind.entries.map { it.label },
                selectedIndices = setOf(com.indagium.model.AiProviderKind.entries.indexOf(kind)),
                onToggle = { index ->
                    val selectedKind = com.indagium.model.AiProviderKind.entries[index]
                    if (selectedKind != kind) {
                        val preset = com.indagium.model.defaultAiProviderProfile(selectedKind)
                        kind = selectedKind
                        name = preset.displayName
                        endpoint = preset.baseUrl
                        model = preset.model
                        executablePath = preset.executablePath
                        reasoningEffort = preset.reasoningEffort
                        ackState = endpoint.trim() to false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                fillWidth = true,
                weightByLabel = true,
            )
            AppText(
                "Profile name", color = tc.td, fontSize = 10.sp, fontFamily = UI,
                modifier = Modifier.settingsAnchor("Profile name"),
            )
            InlineField(name, { name = it }, "LM Studio (local)", Modifier.fillMaxWidth(), fontSize = 12.sp)
            if (kind.usesHttpEndpoint) {
                AppText(
                    "Endpoint", color = tc.td, fontSize = 10.sp, fontFamily = UI,
                    modifier = Modifier.settingsAnchor("Endpoint"),
                )
                InlineField(endpoint, { endpoint = it }, "https://api.example.com/v1", Modifier.fillMaxWidth(), fontSize = 12.sp)
            } else {
                AppText(
                    "Uses a local CLI account already signed in on this computer.",
                    color = tc.td,
                    fontSize = 10.sp,
                )
                AppText(
                    "CLI executable (optional)", color = tc.td, fontSize = 10.sp, fontFamily = UI,
                    modifier = Modifier.settingsAnchor("CLI executable (optional)"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    InlineField(
                        executablePath,
                        { executablePath = it },
                        if (kind == AiProviderKind.CODEX_ACCOUNT) "codex" else "claude",
                        Modifier.weight(1f),
                        fontSize = 12.sp,
                    )
                    AppButton(
                        "Detect",
                        onClick = {
                            val detected = com.indagium.ai.LocalAccountCli.detectExecutable(kind)
                            if (detected != null) {
                                executablePath = detected
                                accountCliCheck = com.indagium.ai.AccountCliCheck(true, "Found local CLI: $detected")
                            } else {
                                val appPath = com.indagium.ai.LocalAccountCli.detectedDesktopApp(kind)
                                accountCliCheck = com.indagium.ai.AccountCliCheck(
                                    false,
                                    if (kind == AiProviderKind.CLAUDE_CODE_ACCOUNT && appPath != null) {
                                        "Claude desktop app was found at $appPath, but Claude Code CLI is not installed."
                                    } else {
                                        "No ${if (kind == AiProviderKind.CODEX_ACCOUNT) "Codex" else "Claude Code"} CLI was detected."
                                    },
                                )
                            }
                        },
                        variant = ButtonVariant.Secondary,
                    )
                    AppButton(
                        "Browse",
                        onClick = { state.pickAccountCliExecutable()?.let { executablePath = it } },
                        variant = ButtonVariant.Secondary,
                    )
                }
                AppText(
                    if (kind == AiProviderKind.CODEX_ACCOUNT) {
                        "Choose a CLI executable, not an app bundle. On macOS Detect can use ChatGPT's bundled Codex CLI."
                    } else {
                        "Choose the Claude Code CLI executable. Claude desktop app bundles cannot run managed panel requests."
                    },
                    color = tc.td,
                    fontSize = 10.sp,
                    maxLines = 2,
                )
            }
            // Not gated by provider kind: any provider whose discovered model reports reasoning
            // efforts gets the dropdown, so Codex, Anthropic, and OpenAI-compatible endpoints
            // (including reasoning-capable local models served via LM Studio) all work the same way.
            val discoveredModel = (modelDiscovery as? ModelDiscoveryResult.Available)
                ?.models
                ?.firstOrNull { it.id == model }
            val reasoningEfforts = discoveredModel?.reasoningEfforts.orEmpty()
            val showReasoningDropdown = discoveredModel != null && reasoningEfforts.isNotEmpty()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppText(
                    if (kind.usesHttpEndpoint) "Model (optional)" else "Model (optional override)",
                    color = tc.td, fontSize = 10.sp, fontFamily = UI,
                    modifier = Modifier.weight(1f).settingsAnchor("Model"),
                )
                if (showReasoningDropdown) {
                    AppText(
                        "Reasoning effort", color = tc.td, fontSize = 10.sp, fontFamily = UI,
                        modifier = Modifier.weight(1f).settingsAnchor("Reasoning effort"),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiModelDropdown(
                    model = model,
                    discovery = modelDiscovery,
                    onDiscoverModels = {
                        coroutineScope.launch(Dispatchers.IO) {
                            modelDiscovery = state.aiSidebarRuntime.discoverModels(draft, apiKey)
                        }
                    },
                    onPickModel = { selectedModel ->
                        model = selectedModel
                        val supportedEfforts = (modelDiscovery as? ModelDiscoveryResult.Available)
                            ?.models
                            ?.firstOrNull { it.id == selectedModel }
                            ?.reasoningEfforts
                            .orEmpty()
                        if (reasoningEffort !in supportedEfforts) reasoningEffort = ""
                    },
                    modifier = Modifier.weight(1f),
                )
                if (showReasoningDropdown) {
                    AiReasoningEffortDropdown(
                        efforts = reasoningEfforts,
                        selected = reasoningEffort,
                        onPick = { reasoningEffort = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (discoveredModel != null && reasoningEfforts.isEmpty()) {
                AppText(
                    "This model does not expose configurable reasoning effort.",
                    color = tc.td,
                    fontSize = 10.sp,
                    maxLines = 2,
                )
            }
            if (kind == AiProviderKind.CLAUDE_CODE_ACCOUNT) {
                AppText(
                    "Claude Code cannot list models enabled for this account. Sonnet, Opus, and Haiku are " +
                        "documented aliases; blank uses your account default, and you can enter a full model id.",
                    color = tc.td,
                    fontSize = 10.sp,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (kind.usesApiKey) {
                AppText(
                    "API key — this session only; it is never saved", color = tc.td, fontSize = 10.sp, fontFamily = UI,
                    modifier = Modifier.settingsAnchor("API key"),
                )
                InlineField(
                    apiKey,
                    { value -> apiKey = value; state.setAiProviderApiKey(profile.id, value) },
                    "Required for cloud API providers",
                    Modifier.fillMaxWidth(),
                    fontSize = 12.sp,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        }
        val endpointHost = runCatching { java.net.URI(endpoint.trim()).host.orEmpty() }.getOrDefault("")
        if (kind.usesHttpEndpoint && endpoint.isNotBlank() && !com.indagium.ai.isLoopbackHost(endpointHost)) {
            CheckRow(acknowledged, { ackState = endpoint.trim() to !acknowledged }) {
                // Screen images from device capture tools are sent to this provider when used.
                AppText(
                    "I understand logs, source code, paths, device screen images, and tool results may leave this " +
                        "device, and that a plain HTTP (non-HTTPS) endpoint also sends my API key unencrypted.",
                    color = tc.td,
                    fontSize = 10.sp,
                    maxLines = 3,
                )
            }
        }
        validationError?.let { AppText(it, color = DANGER_RED, fontSize = 10.sp) }
        connectionTest?.let { result ->
            when (result) {
                is ModelDiscoveryResult.Available -> AppText(
                    "Reachable — ${result.models.size} model(s) found.",
                    color = tc.ac,
                    fontSize = 10.sp,
                )
                is ModelDiscoveryResult.Unavailable -> AppText(result.message, color = DANGER_RED, fontSize = 10.sp)
            }
        }
        accountCliCheck?.let { result ->
            AppText(
                result.message,
                color = if (result.isReady) tc.ac else DANGER_RED,
                fontSize = 10.sp,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Secondary, not Primary — elsewhere in Settings, Primary is reserved for the
            // dialog's own "Done" action, not per-section actions like this one.
            AppButton(
                "Save profile",
                onClick = { validationError = state.updateAiProviderProfile(draft) },
            )
            // Reachability is a pure network probe against the current form fields: it never
            // saves, validates, or otherwise touches the stored profile, so it works whether or
            // not the endpoint would currently pass Save (e.g. an unacknowledged remote endpoint).
            AppButton(
                if (testingConnection) "Checking…" else if (kind.usesHttpEndpoint) "Test connection" else "Check local CLI",
                onClick = {
                    val testProfile = draft
                    val testKey = apiKey
                    testingConnection = true
                    connectionTest = null
                    accountCliCheck = null
                    coroutineScope.launch(Dispatchers.IO) {
                        if (!testProfile.kind.usesHttpEndpoint) {
                            accountCliCheck = com.indagium.ai.checkAccountCli(testProfile)
                        } else {
                            val provider = when (testProfile.kind) {
                                com.indagium.model.AiProviderKind.ANTHROPIC_API ->
                                    com.indagium.ai.AnthropicMessagesProvider(testProfile.baseUrl, testKey)
                                else -> OpenAiCompatibleProvider(testProfile, testKey)
                            }
                            connectionTest = try {
                                provider.listModels()
                            } finally {
                                provider.close()
                            }
                        }
                        testingConnection = false
                    }
                },
                variant = ButtonVariant.Secondary,
                enabled = !testingConnection && (!kind.usesHttpEndpoint || endpoint.isNotBlank()),
            )
            if (profiles.size > 1) {
                AppButton(
                    "Remove",
                    onClick = { pendingDeleteProfileId = profile.id },
                    variant = ButtonVariant.Secondary,
                    isDanger = true,
                )
            }
        }
        CompactSetting("Max MCP tool calls per request") {
            val roundLimits = listOf(12, 25, 50, 100, 200, 500)
            ListStepper(
                options = roundLimits,
                value = state.settings.aiMaxToolRounds,
                onChange = { v -> state.updateSettings { it.copy(aiMaxToolRounds = v) } },
            )
        }
        AppText(
            "A multi-step investigation (filtering, reading lines, then writing a note) can take " +
                "many tool calls, especially with a smaller local model. Raise this if a request stops " +
                "with \"MCP tool-call budget\" before it finishes. Notes reads and writes are unlimited; " +
                "only analysis and operational calls consume this budget.",
            color = tc.td,
            fontSize = 10.sp,
            maxLines = 3,
        )
    }

    pendingDeleteProfileId?.let { id ->
        val targetName = profiles.firstOrNull { it.id == id }?.displayName ?: "this provider"
        SettingsConfirmDialog(
            title = "Remove provider profile?",
            message = "Delete \"$targetName\" from AI providers. This can't be undone.",
            onDismissRequest = { pendingDeleteProfileId = null },
        ) {
            DialogActionButton("Delete", active = true, danger = true) {
                state.removeAiProviderProfile(id)
                editingProfileId = state.settings.aiProviderProfiles.first { it.selected }.id
                pendingDeleteProfileId = null
            }
            DialogActionButton("Cancel", active = false) { pendingDeleteProfileId = null }
        }
    }

    pendingNavigation?.let { action ->
        SettingsConfirmDialog(
            title = "Save changes to ${profile.displayName}?",
            message = "You changed this provider's settings without saving. Save them before continuing?",
            error = navigationSaveError,
            onDismissRequest = { pendingNavigation = null; navigationSaveError = null },
        ) {
            DialogActionButton("Save", active = true) {
                val err = state.updateAiProviderProfile(draft)
                if (err == null) {
                    pendingNavigation = null
                    navigationSaveError = null
                    action()
                } else {
                    navigationSaveError = err
                }
            }
            DialogActionButton("Discard", active = false, danger = true) {
                pendingNavigation = null
                navigationSaveError = null
                action()
            }
            DialogActionButton("Cancel", active = false) { pendingNavigation = null; navigationSaveError = null }
        }
    }
}

@Composable
private fun CustomAiCommandsSettingsSection(state: AppState) {
    val tc = tc()
    var pendingDeleteCommand by remember { mutableStateOf<CustomAiCommand?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppButton(
            "Add command",
            onClick = { state.customCommandEditorTarget = CustomAiCommand("", "") },
            variant = ButtonVariant.Secondary,
            modifier = Modifier.settingsAnchor("Add command"),
        )
        SettingsScrollableRows {
            if (state.customAiCommands.isEmpty()) {
                AppText(
                    "(none yet — add one to invoke it as a button in Actions or by typing /name in the chat box)",
                    color = tc.td,
                    fontSize = 11.sp,
                )
            } else {
                state.customAiCommands.forEach { command ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            AppText("/${command.name}", color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
                            val preview = command.promptTemplate.lineSequence().firstOrNull().orEmpty()
                            if (preview.isNotBlank()) {
                                AppText(preview, color = tc.td, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        AppButton("Edit", onClick = { state.customCommandEditorTarget = command }, variant = ButtonVariant.Secondary)
                        AppButton(
                            "Delete",
                            onClick = { pendingDeleteCommand = command },
                            variant = ButtonVariant.Secondary,
                            isDanger = true,
                        )
                    }
                }
            }
        }
    }
    pendingDeleteCommand?.let { command ->
        SettingsConfirmDialog(
            title = "Delete AI command?",
            message = "Delete /${command.name} from AI commands. This can't be undone.",
            onDismissRequest = { pendingDeleteCommand = null },
        ) {
            DialogActionButton("Delete", active = true, danger = true) {
                state.deleteCustomAiCommand(command.name)
                pendingDeleteCommand = null
            }
            DialogActionButton("Cancel", active = false) { pendingDeleteCommand = null }
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
