@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.indagium.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Schema
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indagium.model.*
import com.indagium.source.SourceCodeView
import com.indagium.utils.ArchiveFormat
import com.indagium.utils.detectArchiveFormat
import com.indagium.utils.tidMapProcessLabel
import com.indagium.video.formatVideoTimeShort
import kotlinx.coroutines.delay
import java.awt.Toolkit
import java.io.File
import java.net.URI
import kotlin.math.roundToInt

/** The popup is bounded visually, but every retained path stays reachable by scrolling. */
internal fun recentFilesForMenu(recentFiles: List<String>): List<String> = recentFiles

/**
 * Files dropped by Linux file managers are not consistently exposed through AWT's
 * [DragData.FilesList].  In particular, some Wayland/X11 combinations offer the standard
 * `text/uri-list` flavour instead.  Keep this conversion local-file-only: a remote URI or an
 * arbitrary text selection must never be mistaken for a file-open request.
 */
internal fun localFilesFromUriList(uriList: String): List<File> =
    uriList.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .mapNotNull(::localFileFromDropUri)
        .toList()

private fun localFileFromDropUri(value: String): File? = runCatching {
    URI.create(value)
        .takeIf { it.scheme.equals("file", ignoreCase = true) }
        ?.let(::File)
}.getOrNull()

internal fun isFileDropData(data: DragData): Boolean = when (data) {
    is DragData.FilesList -> true
    is DragData.Text -> data.bestMimeType.substringBefore(';').trim()
        .equals("text/uri-list", ignoreCase = true)
    else -> false
}

internal fun localFilesFromDropData(data: DragData): List<File> = when (data) {
    is DragData.FilesList -> runCatching { data.readFiles().mapNotNull(::localFileFromDropUri) }
        .getOrDefault(emptyList())
    is DragData.Text -> if (isFileDropData(data)) {
        runCatching { localFilesFromUriList(data.readText()) }.getOrDefault(emptyList())
    } else {
        emptyList()
    }
    else -> emptyList()
}

/**
 * Candidate mitigation for a reported Linux/XFCE symptom: a drag from an external file manager
 * dropped onto this window sometimes does nothing at all — no onDrop, no visible rejection —
 * apparently depending on whether the window already had input focus at drop time. That lines up
 * with a long-documented category of X11/XDND quirks in AWT's Motif/X11 drop-target
 * implementation, where some window managers only deliver the XdndDrop message to a window that's
 * already focused/raised. Same mechanism as [raiseWindow] in Main.kt (used there for GNOME/Mutter
 * ignoring a bare toFront() under focus-stealing prevention): a brief isAlwaysOnTop toggle forces
 * the compositor to actually raise+focus the window, run here on [DragAndDropTarget.onStarted] —
 * the earliest point a drag is recognized as eligible for this target — so the window is focused
 * before the drop message would need to be delivered. Harmless no-op-ish on platforms/WMs that
 * already deliver drops correctly (macOS/Windows never pass a non-null window here at all — see
 * App's `window` parameter).
 *
 * Unverified against a real X11/XFCE session (no Linux desktop available to reproduce this on) —
 * a reasonable, low-risk thing to try given the reported symptom, not a confirmed fix.
 */
internal fun raiseWindowForIncomingDrag(window: java.awt.Window?) {
    if (window == null || !isLinuxOs) return
    if (window is java.awt.Frame && window.extendedState and java.awt.Frame.ICONIFIED != 0) {
        window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
    }
    window.toFront()
    window.requestFocus()
    window.isAlwaysOnTop = true
    window.isAlwaysOnTop = false
}

@Composable
fun App(
    state: AppState = remember { AppState(restoreOnCreate = true, filterBackupsDir = DesktopStorage.filterBackupsDir()) },
    onLicenseDeclined: () -> Unit = {},
    onResetAppData: () -> Unit = {},
    // Null in tests (no real AWT window there) and unused on macOS/Windows — see
    // raiseWindowForIncomingDrag's own KDoc for why this is Linux-only.
    window: java.awt.Window? = null,
) {
    val theme = themeColors(state.settings.theme)
    val platformDensity = LocalDensity.current
    val interfaceScale = state.settings.interfaceScalePercent / 100f
    val scaledDensity = remember(platformDensity, interfaceScale) {
        Density(
            density = platformDensity.density * interfaceScale,
            // `sp` converts through both density and fontScale. Scaling density once makes text
            // and dp-based UI geometry grow by the same percentage while preserving the platform
            // accessibility font preference; multiplying fontScale too would scale text twice.
            fontScale = platformDensity.fontScale,
        )
    }
    val rootFocusRequester = remember { FocusRequester() }
    var pendingPanelFocus by remember { mutableStateOf<KeyboardPanel?>(null) }
    var nextFilterSearchRequestNonce by remember { mutableStateOf(0L) }
    var pendingFilterSearchRequest by remember { mutableStateOf<FilterSearchRequest?>(null) }

    CompositionLocalProvider(
        LocalTheme provides theme,
        LocalFontBase provides state.settings.fontSize,
        LocalUseMono provides state.settings.fontMono,
        LocalDensity provides scaledDensity,
    ) {
        val tc = tc()
        // PERF-4: keyed on each tab's persistedSnapshot() (id/filename/sourcePath/filter/
        // annotations/showAnnMd/showUnfiltered/expanded/manualBlocks/archiveCandidate — exactly
        // what tabToken() serializes), not on state.tabs itself. state.tabs changes identity on
        // ANY tab field write, including session-only ones like `selected`/`analysis` that
        // tabToken() never persists — keying on the raw list meant every row click re-armed this
        // effect and, 400ms later, did a synchronous full-session serialize+write on the UI
        // thread. The .map { } preserves tab order, which is itself part of the persisted state
        // (tabs serialize in list order).
        LaunchedEffect(
            state.tabs.map { it.persistedSnapshot() },
            state.savedFilters,
            state.savedFilterFolders,
            state.settings,
            state.activeSavedFilterIds,
            state.recentFiles,
            state.recentNotes,
        ) {
            kotlinx.coroutines.delay(400)
            // Suppressed while any tab is actively tailing — a fast-growing logData would
            // otherwise keep rewriting the whole autosave.cache every ~400ms for the tailing
            // session's entire duration. stopTailing() explicitly autosaveNow()s once the tab
            // settles, so nothing is lost, just deferred.
            // autosaveInBackground (not autosaveNow): nothing here needs the write to complete
            // synchronously — Main.kt's onCloseRequest already flushes synchronously on exit, so
            // this path can debounce and run entirely off the UI thread instead.
            if (state.tabs.none { it.tailing }) state.autosaveInBackground()
        }
        LaunchedEffect(Unit) {
            runCatching { rootFocusRequester.requestFocus() }
        }
        LaunchedEffect(state) {
            state.startPendingRestoredTabLoads()
        }
        val dropTarget = remember(state, window) {
            object : DragAndDropTarget {
                override fun onStarted(event: DragAndDropEvent) {
                    raiseWindowForIncomingDrag(window)
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val dropped = runCatching { localFilesFromDropData(event.dragData()) }
                        .getOrDefault(emptyList())
                    if (dropped.isEmpty()) return false
                    // Keep the pairing decision in AppState: opening a log publishes its tab
                    // asynchronously, so attaching here would race it and bind the video to the
                    // previously active tab instead.
                    state.openDroppedFiles(dropped)
                    return true
                }
            }
        }
        val dc by dragCursorOverride
        Box(
            Modifier.fillMaxSize().background(tc.bg)
                .dragAndDropTarget(
                    shouldStartDragAndDrop = { event ->
                        runCatching { isFileDropData(event.dragData()) }.getOrDefault(false)
                    },
                    target = dropTarget,
                )
                .then(
                    if (dc != null) Modifier.pointerHoverIcon(
                        PointerIcon(dc!!),
                        overrideDescendants = true
                    ) else Modifier
                )
                .focusRequester(rootFocusRequester)
                .focusable()
                // Any mouse click anywhere hides the keyboard focus-visible outline; panel
                // focus shortcuts below turn it back on. Runs on the Initial pass so it fires
                // before the click's own focus side effects.
                .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) {
                    state.keyboardFocusVisible = false
                }
                // Bubble phase — see handleGlobalImagePaste's doc comment for why this one must
                // NOT join the onPreviewKeyEvent chain below.
                .onKeyEvent { ev -> handleGlobalImagePaste(ev, state) }
                .onPreviewKeyEvent { ev ->
                    handleGlobalKey(
                        ev = ev,
                        state = state,
                        onFocusPanel = { panel -> state.keyboardFocusVisible = true; pendingPanelFocus = panel },
                        onFocusFilterSearch = {
                            state.keyboardFocusVisible = true
                            // Settings.ctrlFTarget (FIND_BAR by default) routes Ctrl/Cmd+F to the
                            // non-destructive in-view Find bar instead of focusing a filter input —
                            // see AppState.openSearch and ui/SearchBar.kt. TAGS/KEYWORD_REGEX (and
                            // the still-routable-but-not-selectable MESSAGE_RULE) fall through to
                            // exactly the pre-existing filter-focus path below. openUnfilteredOnCtrlF
                            // applies to BOTH branches now — it means "Ctrl+F reveals the Original
                            // split", independent of which of the two things Ctrl+F then does with
                            // that revealed panel.
                            if (state.settings.ctrlFTarget == CtrlFTarget.FIND_BAR) {
                                // Single-tab mode only: ensureActiveTabUnfiltered operates on
                                // activeTabId, and compare mode has no Original/Filtered split to
                                // reveal in the first place (its left/right panels are two whole
                                // separate tabs, not one tab's showUnfiltered flag) — the Find bar
                                // still opens fine there via searchFocusTabId-aware targetTabId
                                // below, just without this step.
                                if (state.settings.openUnfilteredOnCtrlF && !state.compareMode) {
                                    state.ensureActiveTabUnfiltered()
                                }
                                // No pendingPanelFocus here (unlike the filter-focus branch below):
                                // the Find bar isn't one of FileView's F6-roving panel targets, and
                                // ui/SearchBar.kt's own LaunchedEffect(focusNonce) already requests
                                // keyboard focus on the find field itself once AppState.openSearch
                                // flips tab.search.active — routing through KeyboardPanel here too
                                // would just race that effect for no benefit.
                                //
                                // In compare mode, activeTabId always names the left panel only —
                                // searchFocusTabId (last panel to actually hold keyboard focus,
                                // updated by both CompareView panels) is what lets this target
                                // whichever of the two the user was actually in. Guarded to only
                                // the two tabs compare mode currently shows so a stale value from a
                                // previous compare session (or one whose tab has since closed) can
                                // never silently open Find on some other, no-longer-visible tab;
                                // single-tab mode always uses activeTab() regardless, since a plain
                                // tab switch there doesn't necessarily refocus the log panel.
                                val targetTabId = if (state.compareMode) {
                                    state.searchFocusTabId
                                        ?.takeIf { it == state.activeTabId || it == state.compareTabId }
                                        ?: state.activeTab()?.id
                                } else {
                                    state.activeTab()?.id
                                }
                                targetTabId?.let { tabId -> state.openSearch(tabId) }
                            } else {
                                if (state.settings.openUnfilteredOnCtrlF) state.ensureActiveTabUnfiltered()
                                state.updateFilterVisible(true)
                                pendingPanelFocus = KeyboardPanel.FILTERS
                                state.activeTab()?.id?.let { tabId ->
                                    nextFilterSearchRequestNonce += 1
                                    pendingFilterSearchRequest = FilterSearchRequest(
                                        nonce = nextFilterSearchRequestNonce,
                                        tabId = tabId,
                                        target = state.settings.ctrlFTarget,
                                    )
                                }
                            }
                        },
                    )
                }
        ) {
            Column(Modifier.fillMaxSize()) {
                TabBar(state)
                val activeTab = state.activeTab()
                when {
                    state.tabs.isEmpty() ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            AppText("No files open — click Open to add a log", color = tc.ts, fontSize = 14.sp)
                        }

                    state.compareMode -> CompareView(
                        state = state,
                        requestedPanelFocus = pendingPanelFocus,
                        filterSearchRequest = pendingFilterSearchRequest,
                        onFilterSearchRequestConsumed = { request ->
                            pendingFilterSearchRequest = consumeFilterSearchRequest(pendingFilterSearchRequest, request)
                        },
                        onPanelFocusConsumed = { pendingPanelFocus = null },
                    )
                    activeTab != null -> key(activeTab.id) {
                        FileView(
                            state = state,
                            tab = activeTab,
                            requestedPanelFocus = pendingPanelFocus,
                            filterSearchRequest = pendingFilterSearchRequest,
                            onFilterSearchRequestConsumed = { request ->
                                pendingFilterSearchRequest = consumeFilterSearchRequest(pendingFilterSearchRequest, request)
                            },
                            onPanelFocusConsumed = { pendingPanelFocus = null },
                        )
                    }
                }
            }

            // ── Loading overlay ───────────────────────────────────────
            if (state.isLoading) {
                Box(
                    Modifier.fillMaxSize().background(loadingOverlayBackground(tc)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AppText("Loading file…", color = tc.ts, fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                        IndeterminateLoadingLine(Modifier.width(180.dp))
                    }
                }
            }

            // ── Stuck-loading watchdog ─────────────────────────────────
            // A load that never finishes (a genuine hang, not just a big file) otherwise leaves
            // no way back into the app short of force-quitting — this surfaces after a long
            // stretch of continuous isLoading and offers real escape hatches instead. This can
            // only catch hangs in BACKGROUND work that's still reporting isLoading=true but never
            // completing; it can't do anything for the UI thread itself being blocked (nothing
            // running on that same thread could), which is a separate class of bug fixed instead
            // by making sure the UI thread is never handed blocking work in the first place (see
            // AppState.applyControlServerState's ioScope move).
            var stuckPromptSnoozeCount by remember { mutableStateOf(0) }
            var showStuckPrompt by remember { mutableStateOf(false) }
            LaunchedEffect(state.isLoading, stuckPromptSnoozeCount) {
                showStuckPrompt = false
                if (state.isLoading) {
                    kotlinx.coroutines.delay(STUCK_LOADING_PROMPT_DELAY_MS)
                    if (state.isLoading) showStuckPrompt = true
                }
            }
            if (showStuckPrompt) {
                StuckLoadingDialog(
                    status = state.loadingStatus,
                    onCancelLoading = { state.cancelAllLoads(); showStuckPrompt = false },
                    onCloseAllTabs = { state.closeAllTabs(); showStuckPrompt = false },
                    onClearCache = { state.requestClearCache(); showStuckPrompt = false },
                    onKeepWaiting = { showStuckPrompt = false; stuckPromptSnoozeCount++ },
                )
            }

            // ── Context menu ──────────────────────────────────────────
            state.ctx?.let { ctx ->
                val ctxTab = state.tab(ctx.tabId)
                val entry = ctxTab?.rmap?.get(ctx.entryId)
                // Transparent backdrop — no indication (shadow) added
                BoxWithConstraints(
                    Modifier.fillMaxSize().clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { state.ctx = null },
                    )
                ) {
                    if (entry != null) {
                        // panelSelectedIds is non-empty when the right-click came from a panel
                        // with its own local selection (e.g. the "Original" unfiltered panel).
                        val selectedIds = ctx.panelSelectedIds.ifEmpty { ctxTab.selected }
                        val selCount = selectedIds.size
                        // manualCollapseAvailability re-derives a range (and re-scans every existing
                        // manual block) each call — remember it per menu-open so unrelated
                        // recompositions of this scope (e.g. AI sidebar state ticking) don't re-pay
                        // that cost. Keyed on ctxTab + entryId + selectedIds so a genuine change to
                        // any of those (new manual block, selection edited from another panel while
                        // the menu is open) still recomputes.
                        val canCollapseToStart = remember(ctxTab, ctx.entryId) {
                            manualCollapseAvailability(ctxTab, ctx.entryId, ManualCollapseDirection.TO_START) ==
                                ManualCollapseAvailability.AVAILABLE
                        }
                        val canCollapseToEnd = remember(ctxTab, ctx.entryId) {
                            manualCollapseAvailability(ctxTab, ctx.entryId, ManualCollapseDirection.TO_END) ==
                                ManualCollapseAvailability.AVAILABLE
                        }
                        val canCollapseSelection = remember(ctxTab, ctx.entryId, selectedIds) {
                            selectedIds.size > 1 &&
                                manualCollapseAvailability(
                                    ctxTab,
                                    selectedIds.minOrNull() ?: ctx.entryId,
                                    ManualCollapseDirection.RANGE,
                                    selectedIds.maxOrNull(),
                                ) == ManualCollapseAvailability.AVAILABLE
                        }
                        // Keep the grouped action rows wide enough for three equal-width
                        // buttons, including the longest Highlight/Selected labels.
                        val menuWidth = 276.dp
                        // Tid map — resolved once here (not inline in the buildList below) since
                        // both the height estimate and the entries themselves need to agree on
                        // exactly the same "is a map active, does it match this row" facts.
                        val activeTidMap = ctxTab.tidMap
                        val tidMapTargetHere = TidMapTarget(entry.pid, entry.tid)
                        // Same availability rules the merged "Process" block itself computes below
                        // (run{} block, before the CtxMenuEntry.ProcessActions add()) — duplicated
                        // here, like every other conditional block's height cost, so the height
                        // estimate and the entries agree on exactly the same facts.
                        val hasShowMapAction = entry.pid > 0 && activeTidMap?.target?.pid != tidMapTargetHere.pid
                        val hasHideMapAction = activeTidMap != null
                        val hasNameAction = ctxTab.analysis.processNames[entry.pid] != null
                        // Estimate full menu height from items that will actually render:
                        //   header(37) + divider(9) + preview(63) + 1 item(32) + divider(9)
                        //   + 2 items(64) [sequence] + divider(9) + 2 items(64) [collapse-to-start/end]
                        //   + divider(9) + 2 items(64) [hide/show] + divider(9) + 1 row(32) [tags] = 458
                        // Selection text adds a preview extension line(15) on top of that. The merged
                        // "Process" block (CtxProcessActions — tid map + process name, formerly two
                        // separate blocks) is a fixed-shape header+one-row+divider costing 73, same as
                        // every other single-row block below, for 1 or 2 buttons (CtxActionSlot's
                        // fixed-width slots hold at most 2 per row — see that composable's own doc).
                        // The only way to reach a 3rd button is both map actions AND the name action
                        // all being available at once, which wraps that 3rd button onto its own
                        // second row, costing +28 on top — mirrors this same block's own pre-merge
                        // two-row shape.
                        val hasProcessBlock = hasShowMapAction || hasHideMapAction || hasNameAction
                        val hasProcessSecondRow = hasShowMapAction && hasHideMapAction && hasNameAction
                        // 490, not 458: the always-present "Sequence diagram…" Action row in the
                        // sequence block below adds one more 32dp entry. This estimate only decides
                        // where the menu is anchored, but an under-estimate lets it open off-screen.
                        val estimatedMenuHeight = (490 +
                            (if (ctx.selText.isNotBlank()) 15 else 0) +
                            (if (state.pendingSequenceStart != null) 32 else 0) +
                            (if (hasProcessBlock) 73 else 0) +
                            (if (hasProcessSecondRow) 28 else 0) +
                            // Video block: 2 Action rows (32 each) + a trailing divider (9).
                            (if (ctxTab.attachedVideo != null) 73 else 0) +
                            (if (state.settings.sourceFolders.isNotEmpty()) 44 else 0)).dp
                        val menuScroll = rememberScrollState()
                        val x = ctx.x.dp.coerceIn(8.dp, (maxWidth - menuWidth - 8.dp).coerceAtLeast(8.dp))
                        val y = ctx.y.dp.coerceIn(8.dp, (maxHeight - estimatedMenuHeight - 8.dp).coerceAtLeast(8.dp))
                        // Not enough room for the submenu to the right of the whole menu — open it
                        // to the left instead (see CtxItemWithSubmenu's preferLeft).
                        val submenuOpensLeft = (x + menuWidth + CTX_SUBMENU_WIDTH) > maxWidth

                        val ruleVariants = state.messageRuleVariantsFromCtx()
                        // Resolved once per menu open (cheap — indexed lookup) rather than per
                        // item, so both the enabled/disabled source actions below and their
                        // onClick agree on the same match list.
                        val srcMatches = if (state.settings.sourceFolders.isEmpty()) {
                            emptyList()
                        } else {
                            state.resolveForLine(ctx.tabId, ctx.entryId)
                        }
                        val menuEntries = buildList {
                            add(
                                CtxMenuEntry.ActionHeader(
                                    if (selCount > 1) "Add annotation for $selCount lines" else "Add annotation",
                                ) {
                                    val ids = if (selCount > 1) selectedIds.toSortedSet().toList() else listOf(ctx.entryId)
                                    state.requestAddAnn(ctx.tabId, ids)
                                },
                            )
                            add(CtxMenuEntry.Divider)
                            add(CtxMenuEntry.Preview)
                            // Block order: selection, hide/show, tags, sequence, collapse.
                            run {
                                val selectionIds = if (selCount > 1) selectedIds.toSortedSet().toList() else listOf(ctx.entryId)
                                add(
                                    CtxMenuEntry.SelectionActions(
                                        onAskAi = { state.requestAiContext(ctx.tabId, selectionIds) },
                                        onCopy = {
                                            if (selCount > 1) {
                                                state.copySelectedLines(ctx.tabId, selectionIds.toSet())
                                            } else {
                                                state.copySelectedLines(ctx.tabId, setOf(entry.id))
                                            }
                                            state.ctx = null
                                        },
                                        onHighlight = { state.addHlFromCtx() },
                                        onHighlightColor = { color -> state.addHlFromCtx(color) },
                                        highlightAutoColor = state.nextAvailableHighlighterColor(ctx.tabId),
                                        preferPickerLeft = submenuOpensLeft,
                                    ),
                                )
                                add(CtxMenuEntry.Divider)
                            }
                            add(
                                CtxMenuEntry.ActionWithSubmenu(
                                    Icons.Outlined.VisibilityOff,
                                    "Hide messages like this",
                                    onClick = { state.hideMessagesLikeCtx() },
                                    submenu = ruleVariants.map { v -> v.label to { state.hideMessagesLikeVariant(v) } },
                                ),
                            )
                            add(
                                CtxMenuEntry.ActionWithSubmenu(
                                    Icons.Outlined.Visibility,
                                    "Show messages like this",
                                    onClick = { state.showOnlyMessagesLikeCtx() },
                                    submenu = ruleVariants.map { v -> v.label to { state.showOnlyMessagesLikeVariant(v) } },
                                ),
                            )
                            add(CtxMenuEntry.Divider)
                            add(
                                CtxMenuEntry.TagActions(
                                    onInclude = { state.addTagFilterFromCtx() },
                                    onExclude = { state.addExcludeTagFromCtx() },
                                    onHighlight = { state.addHlTagFromCtx() },
                                    onHighlightColor = { color -> state.addHlTagFromCtx(color) },
                                    highlightAutoColor = state.nextAvailableHighlighterColor(ctx.tabId),
                                    preferPickerLeft = submenuOpensLeft,
                                ),
                            )
                            add(CtxMenuEntry.Divider)
                            // Sequence actions — own block, "Add as sequence" pulled out of the
                            // highlight block above to sit next to the rest of the sequence
                            // workflow instead of next to an unrelated highlight toggle.
                            run {
                                add(
                                    CtxMenuEntry.ActionWithSubmenu(
                                        Icons.Outlined.Layers,
                                        "Add as sequence",
                                        onClick = { state.addSeqFromCtx() },
                                        submenu = ruleVariants.map { v -> v.label to { state.addSequenceVariant(v) } },
                                    ),
                                )
                                if (state.pendingSequenceStart != null) {
                                    add(CtxMenuEntry.Action(Icons.Outlined.Flag, "Complete sequence end") { state.completeSequenceEndFromCtx() })
                                }
                                add(CtxMenuEntry.Action(Icons.Outlined.PlayArrow, "Set sequence start") { state.setSequenceStartFromCtx() })
                                add(
                                    CtxMenuEntry.Action(Icons.Outlined.Schema, "Sequence diagram…") {
                                        state.seqDiagrams.begin(ctx.tabId, selectedIds.toSet())
                                    },
                                )
                                add(CtxMenuEntry.Divider)
                            }
                            // Collapse actions — own block. Every entry is conditional on
                            // availability, so the divider after is guarded on at least one
                            // having rendered (an empty block would otherwise leave two dividers
                            // back to back with nothing between them, here right before Copy).
                            run {
                                val hasCollapseAction = canCollapseSelection || canCollapseToStart || canCollapseToEnd
                                if (hasCollapseAction) {
                                    add(
                                        CtxMenuEntry.CollapseActions(
                                            onToStart = canCollapseToStart.takeIf { it }?.let { { state.collapseToStartFromCtx() } },
                                            onToEnd = canCollapseToEnd.takeIf { it }?.let { { state.collapseToEndFromCtx() } },
                                            onSelected = canCollapseSelection.takeIf { it }?.let {
                                                { state.collapseSelectedLinesFromCtx(ctx.tabId, selectedIds) }
                                            },
                                        ),
                                    )
                                }
                                if (hasCollapseAction) add(CtxMenuEntry.Divider)
                            }
                            // Process (tid map + process name, merged) — one Collapse-shaped block
                            // ("Process" header + up to two rows of Ghost buttons: Show/Hide map,
                            // Show/Hide name), inserted here (after Collapse, before Source) per the
                            // approved design. These used to be two adjacent blocks — "Threads" and
                            // "Process name" — for the same process; merged into one section since a
                            // user right-clicking one row has no reason to read two headers naming
                            // the same process back to back (see CtxProcessActions' own doc).
                            //
                            // Map: Hide is offered whenever a map is active, from ANY row — not just
                            // the row it was originally opened for — since requiring the user to find
                            // their way back to that exact row before they could close it was a real
                            // dead end (the row can easily have scrolled out of view). Show is
                            // offered whenever the right-clicked row's pid differs from the currently
                            // active target's (including when nothing is active), so switching to a
                            // new process's map never requires closing the old one first —
                            // RAW-fallback rows carry no real pid at all (LogParser.kt's RAW-fallback
                            // branch never populates one, defaulting to 0), so a "map" of a fake
                            // shared pid=0 across unrelated lines would be actively misleading, and
                            // Show is omitted rather than disabled (same null-to-omit convention
                            // CollapseActions itself uses).
                            //
                            // Name: offered only when this row's OWN pid resolves to a known name;
                            // Show/Hide always switch THIS TAB's processNameMode to MANUAL (see
                            // AppState.showProcessNameForPid/hideProcessNameForPid's own doc) — this
                            // control is fundamentally a MANUAL-mode picker, so invoking it from OFF
                            // or ALL must switch into the mode where the pick actually matters.
                            //
                            // The whole block (and its header) is omitted when NONE of the four
                            // actions apply — same "an empty header with no buttons under it must
                            // not render" rule the pre-merge Threads block already followed for a
                            // pid-less row with no map active.
                            run {
                                val onShowMap = (activeTidMap?.target?.pid != tidMapTargetHere.pid)
                                    .takeIf { it && entry.pid > 0 }
                                    ?.let { { state.toggleTidMap(ctx.tabId, entry.pid, entry.tid); state.ctx = null } }
                                val onHideMap = activeTidMap?.let { { state.closeTidMap(ctx.tabId); state.ctx = null } }
                                val processName = ctxTab.analysis.processNames[entry.pid]
                                val currentlyShown = when (ctxTab.processNameMode) {
                                    ProcessNameMode.OFF -> false
                                    ProcessNameMode.ALL -> true
                                    ProcessNameMode.MANUAL -> entry.pid in ctxTab.manualProcessNamePicks
                                }
                                val onShowName = (processName != null && !currentlyShown)
                                    .takeIf { it }
                                    ?.let { { state.showProcessNameForPid(ctxTab.id, entry.pid); state.ctx = null } }
                                val onHideName = (processName != null && currentlyShown)
                                    .takeIf { it }
                                    ?.let { { state.hideProcessNameForPid(ctxTab.id, entry.pid); state.ctx = null } }
                                if (onShowMap != null || onHideMap != null || onShowName != null || onHideName != null) {
                                    // The header names THIS row's own resolved process when known —
                                    // matching the Show/Hide name buttons exactly, and matching the
                                    // map buttons too in the common case where they act on the same
                                    // process. Only when this row's own name is unknown does it fall
                                    // back to whichever process the map buttons actually act on
                                    // (labelTarget: the ACTIVE map's own target when one is open,
                                    // since Hide always closes THAT map even from an unrelated row —
                                    // same fallback the pre-merge Threads block already used).
                                    val labelTarget = activeTidMap?.target ?: tidMapTargetHere
                                    val processLabel = processName ?: tidMapProcessLabel(labelTarget, ctxTab.analysis.processNames)
                                    add(
                                        CtxMenuEntry.ProcessActions(
                                            onShowMap = onShowMap, onHideMap = onHideMap,
                                            onShowName = onShowName, onHideName = onHideName,
                                            processLabel = processLabel,
                                        ),
                                    )
                                    add(CtxMenuEntry.Divider)
                                }
                            }
                            // Video anchoring/nav (plan doc's Task B) — only offered when this tab
                            // has a video attached at all, grouped under one "Video" header the same
                            // way Threads groups its show/hide-map pair. "Link to <time>" always
                            // overwrites whatever anchor already existed (one anchor per tab — see
                            // VideoAttachment.anchor's own doc) with the CURRENT playhead position;
                            // there is deliberately no separate "link to 0:00" action — anyone wanting
                            // that anchor seeks the video to 0:00 first, then links. "Show" stays
                            // visible but disabled (matching Source's own enabled-not-hidden
                            // convention below) until BOTH an anchor exists AND this row's `ts`
                            // actually maps to a video-time (logIdToVideoMs is null for TS_UNKNOWN
                            // rows — brief/RAW-format lines with no timestamp).
                            run {
                                val attachedVideo = ctxTab.attachedVideo
                                if (attachedVideo != null) {
                                    val videoController = state.videoController(ctxTab.id)
                                    val mappedMs = state.logIdToVideoMs(ctxTab, entry.id)
                                    val hasValidMappedPosition = mappedMs?.let { state.isVideoPositionValid(ctxTab, it) } == true
                                    add(
                                        CtxMenuEntry.VideoActions(
                                            linkLabel = "Link to ${formatVideoTimeShort(videoController?.positionMs ?: 0L)}",
                                            onLink = {
                                                videoController?.let { vc -> state.setVideoAnchor(ctxTab.id, vc.positionMs, entry.id) }
                                                state.ctx = null
                                            },
                                            showEnabled = attachedVideo.anchor != null && hasValidMappedPosition,
                                            onShow = {
                                                mappedMs?.takeIf { state.isVideoPositionValid(ctxTab, it) }?.let { ms -> videoController?.seek(ms) }
                                                state.ctx = null
                                            },
                                        ),
                                    )
                                    add(CtxMenuEntry.Divider)
                                }
                            }
                            // Only offered once source folders are configured; if they are but this
                            // particular line has no resolved call site, the actions still render
                            // disabled rather than disappearing.
                            if (state.settings.sourceFolders.isNotEmpty()) {
                                add(
                                    CtxMenuEntry.SourceActions(
                                        // Both actions resolve a single line (ctx.entryId) regardless of
                                        // selection — with multiple lines selected there's no single call
                                        // site to show/open, so disable rather than silently acting on
                                        // just the right-clicked row.
                                        enabled = srcMatches.isNotEmpty() && selCount <= 1,
                                        onShowCode = {
                                            if (srcMatches.isNotEmpty()) {
                                                state.sourceCodeView = SourceCodeView(srcMatches)
                                                state.ctx = null
                                            }
                                        },
                                        onOpenFile = {
                                            srcMatches.firstOrNull()?.let { match ->
                                                state.openInEditor(match.site.filePath, match.site.callLine)
                                                state.ctx = null
                                            }
                                        },
                                    ),
                                )
                            }
                        }
                        val selectableEntries = menuEntries.filter {
                            it is CtxMenuEntry.ActionHeader || it is CtxMenuEntry.Action ||
                                it is CtxMenuEntry.TagActions || it is CtxMenuEntry.CollapseActions ||
                                it is CtxMenuEntry.ProcessActions ||
                                it is CtxMenuEntry.VideoActions ||
                                it is CtxMenuEntry.SelectionActions || it is CtxMenuEntry.SourceActions ||
                                it is CtxMenuEntry.ActionWithSubmenu
                        }
                        var selectedIdx by remember(ctx) { mutableStateOf(0) }
                        val selectedEntry = selectableEntries.getOrNull(selectedIdx)
                        val menuFr = remember(ctx) { FocusRequester() }
                        LaunchedEffect(ctx) { runCatching { menuFr.requestFocus() } }

                        // Position is already in dp (converted from px in LogRow)
                        Box(
                            Modifier
                                .offset(x = x, y = y)
                                .shadow(8.dp, RoundedCornerShape(7.dp))
                                .background(tc.p, RoundedCornerShape(7.dp))
                                .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                                .width(menuWidth)
                                .heightIn(max = (maxHeight - y - 8.dp).coerceAtLeast(160.dp))
                                .verticalScroll(menuScroll)
                                .focusRequester(menuFr)
                                .focusable()
                                .onPreviewKeyEvent { ev ->
                                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (ev.key) {
                                        Key.DirectionDown -> {
                                            selectedIdx = (selectedIdx + 1).mod(selectableEntries.size.coerceAtLeast(1))
                                            true
                                        }
                                        Key.DirectionUp -> {
                                            selectedIdx = (selectedIdx - 1).mod(selectableEntries.size.coerceAtLeast(1))
                                            true
                                        }
                                        Key.Enter, Key.NumPadEnter -> {
                                            selectedEntry?.let {
                                                when (it) {
                                                    is CtxMenuEntry.ActionHeader -> it.onClick()
                                                    is CtxMenuEntry.Action -> it.onClick()
                                                    is CtxMenuEntry.TagActions -> it.onInclude()
                                                    is CtxMenuEntry.CollapseActions -> it.onToStart?.invoke()
                                                    is CtxMenuEntry.ProcessActions ->
                                                        it.onShowMap?.invoke() ?: it.onHideMap?.invoke()
                                                            ?: it.onShowName?.invoke() ?: it.onHideName?.invoke()
                                                    is CtxMenuEntry.VideoActions -> it.onLink()
                                                    is CtxMenuEntry.SelectionActions -> it.onAskAi()
                                                    is CtxMenuEntry.SourceActions -> it.onShowCode()
                                                    is CtxMenuEntry.ActionWithSubmenu -> it.onClick()
                                                    else -> {}
                                                }
                                            }
                                            true
                                        }
                                        Key.Escape -> { state.ctx = null; true }
                                        else -> false
                                    }
                                },
                        ) {
                            Column {
                                menuEntries.forEach { e ->
                                    when (e) {
                                        is CtxMenuEntry.ActionHeader -> {
                                            HoverBox(
                                                modifier = Modifier.fillMaxWidth(),
                                                baseBg = tc.p2,
                                                forceHover = e === selectedEntry,
                                                onClick = e.onClick,
                                            ) {
                                                Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                                    AppText(e.label, color = tc.ac, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }
                                        is CtxMenuEntry.Action ->
                                            CtxItem(e.icon, e.label, highlighted = e === selectedEntry, enabled = e.enabled, onClick = e.onClick)
                                        is CtxMenuEntry.TagActions ->
                                            CtxTagActions(
                                                highlighted = e === selectedEntry,
                                                onInclude = e.onInclude,
                                                onExclude = e.onExclude,
                                                onHighlight = e.onHighlight,
                                                onHighlightColor = e.onHighlightColor,
                                                highlightAutoColor = e.highlightAutoColor,
                                                preferPickerLeft = e.preferPickerLeft,
                                            )
                                        is CtxMenuEntry.CollapseActions ->
                                            CtxCollapseActions(
                                                highlighted = e === selectedEntry,
                                                onToStart = e.onToStart,
                                                onToEnd = e.onToEnd,
                                                onSelected = e.onSelected,
                                            )
                                        is CtxMenuEntry.ProcessActions ->
                                            CtxProcessActions(
                                                highlighted = e === selectedEntry,
                                                onShowMap = e.onShowMap,
                                                onHideMap = e.onHideMap,
                                                onShowName = e.onShowName,
                                                onHideName = e.onHideName,
                                                processLabel = e.processLabel,
                                            )
                                        is CtxMenuEntry.VideoActions ->
                                            CtxVideoActions(
                                                highlighted = e === selectedEntry,
                                                linkLabel = e.linkLabel,
                                                onLink = e.onLink,
                                                showEnabled = e.showEnabled,
                                                onShow = e.onShow,
                                            )
                                        is CtxMenuEntry.SelectionActions ->
                                            CtxSelectionActions(
                                                highlighted = e === selectedEntry,
                                                onAskAi = e.onAskAi,
                                                onCopy = e.onCopy,
                                                onHighlight = e.onHighlight,
                                                onHighlightColor = e.onHighlightColor,
                                                highlightAutoColor = e.highlightAutoColor,
                                                preferPickerLeft = e.preferPickerLeft,
                                            )
                                        is CtxMenuEntry.SourceActions ->
                                            CtxSourceActions(
                                                highlighted = e === selectedEntry,
                                                enabled = e.enabled,
                                                onShowCode = e.onShowCode,
                                                onOpenFile = e.onOpenFile,
                                            )
                                        is CtxMenuEntry.ActionWithSubmenu ->
                                            CtxItemWithSubmenu(
                                                e.icon, e.label, e.submenu,
                                                highlighted = e === selectedEntry,
                                                preferLeft = submenuOpensLeft,
                                                onClick = e.onClick,
                                            )
                                        CtxMenuEntry.Divider -> CtxDivider()
                                        CtxMenuEntry.Preview -> Column(
                                            Modifier.fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                                .background(tc.p2, CORNER_MD)
                                                .border(BorderStroke(0.5.dp, tc.br.copy(alpha = 0.5f)), CORNER_MD)
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                LevelBadge(entry.level)
                                                AppText(
                                                    entry.tag, color = tc.td, fontSize = 10.sp, fontFamily = MONO,
                                                    modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Spacer(Modifier.height(2.dp))
                                            AppText(
                                                entry.msg,
                                                color = tc.ts,
                                                fontSize = 10.sp,
                                                fontFamily = MONO,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (ctx.selText.isNotBlank()) {
                                                Spacer(Modifier.height(2.dp))
                                                AppText("Selected: \"${ctx.selText}\"", color = tc.ac, fontSize = 9.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Tab context menu ───────────────────────────────────────
            state.tabCtx?.let { tctx ->
                val ttab = state.tab(tctx.tabId)
                if (ttab != null) {
                    BoxWithConstraints(
                        Modifier.fillMaxSize().clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = { state.tabCtx = null },
                        ),
                    ) {
                        // Only a tab backed by a real, currently-existing file can be tailed —
                        // not a zip-extracted tab (sourcePath is a "zip!entry" pseudo-path, no
                        // real file to watch), a merged tab (sourcePath is null), or a bare
                        // compressed log (sourcePath is real, but TailCoordinator.startTailing
                        // refuses it too — see its doc comment for why appending raw gzip bytes
                        // makes no sense). Mirrors that same guard so the menu item doesn't even
                        // offer an action startTailing would silently no-op on.
                        val canTail = remember(ttab.sourcePath) {
                            val p = ttab.sourcePath
                            p != null && '!' !in p && File(p).isFile && detectArchiveFormat(File(p)) == ArchiveFormat.None
                        }
                        val canSplit = remember(ttab.sourcePath) {
                            ttab.sourcePath?.let { state.splitSourceForPath(it) } != null
                        }
                        val menuWidth = 200.dp
                        val estimatedMenuHeight = (268 + (if (canTail) 44 else 0) + (if (canSplit) 44 else 0)).dp
                        val x = tctx.x.dp.coerceIn(8.dp, (maxWidth - menuWidth - 8.dp).coerceAtLeast(8.dp))
                        val y = tctx.y.dp.coerceIn(8.dp, (maxHeight - estimatedMenuHeight - 8.dp).coerceAtLeast(8.dp))
                        Column(
                            Modifier.offset(x = x, y = y).width(menuWidth)
                                .background(tc.p, RoundedCornerShape(7.dp))
                                .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                ) {}
                                .padding(vertical = 4.dp),
                        ) {
                            CtxItem(
                                icon = Icons.Outlined.ContentCopy,
                                label = "Copy tab name",
                                onClick = {
                                    state.copyToClipboard(ttab.filename)
                                    state.tabCtx = null
                                },
                            )
                            CtxItem(
                                icon = Icons.Outlined.ContentCopy,
                                label = "Copy full path",
                                onClick = {
                                    // Two tabs can share a filename (same file opened twice, or two
                                    // files with the same name from different folders) — the full
                                    // path is what actually disambiguates them, e.g. for an MCP
                                    // client asked to "analyze the tab named X".
                                    state.copyToClipboard(ttab.sourcePath ?: ttab.filename)
                                    state.tabCtx = null
                                },
                            )
                            CtxDivider()
                            if (canTail) {
                                CtxItem(
                                    icon = Icons.Outlined.PlayArrow,
                                    label = if (ttab.tailing) "Stop Live Watching" else "Start Live Watching",
                                    onClick = {
                                        if (ttab.tailing) state.stopTailing(ttab.id) else state.startTailing(ttab.id)
                                        state.tabCtx = null
                                    },
                                )
                            }
                            if (canSplit) {
                                CtxItem(
                                    icon = Icons.Outlined.Layers,
                                    label = "Split…",
                                    onClick = {
                                        state.requestSplitForTab(ttab.id)
                                        state.tabCtx = null
                                    },
                                )
                            }
                            CtxItem(
                                icon = Icons.AutoMirrored.Outlined.CallMerge,
                                label = "Merge…",
                                onClick = {
                                    state.mergeTabsPreselectedId = ttab.id
                                    state.mergeTabsDialogOpen = true
                                    state.tabCtx = null
                                },
                            )
                            CtxDivider()
                            CtxItem(
                                icon = Icons.Outlined.Block,
                                label = "Close other tabs",
                                onClick = {
                                    state.closeOtherTabs(ttab.id)
                                    state.tabCtx = null
                                },
                            )
                            CtxItem(
                                icon = Icons.Outlined.Block,
                                label = "Close to right",
                                onClick = {
                                    state.closeTabsToRight(ttab.id)
                                    state.tabCtx = null
                                },
                            )
                            CtxItem(
                                icon = Icons.Outlined.Block,
                                label = "Close to left",
                                onClick = {
                                    state.closeTabsToLeft(ttab.id)
                                    state.tabCtx = null
                                },
                            )
                            CtxItem(
                                icon = Icons.Outlined.Block,
                                label = "Close all",
                                onClick = {
                                    state.closeAllTabs()
                                    state.tabCtx = null
                                },
                            )
                        }
                    }
                }
            }

            // ── Add annotation dialog ─────────────────────────────────
            state.addAnnRequest?.let { req ->
                val rows = req.logIds.mapNotNull { state.tab(req.sourceTabId)?.rmap?.get(it) }
                Dialog(onDismissRequest = { state.addAnnRequest = null }) {
                    AddAnnDialog(
                        rows = rows,
                        sourceFilename = req.sourceFilename,
                        onConfirm = { caption ->
                            state.confirmAddAnn(
                                req.targetTabId,
                                req.sourceTabId,
                                req.logIds,
                                caption,
                                req.sourceFilename
                            )
                        },
                        onDismiss = { state.addAnnRequest = null },
                    )
                }
            }

            // ── Save filter dialog ────────────────────────────────────
            if (state.sfDialog) {
                Dialog(onDismissRequest = { state.sfDialog = false; state.sfFolderId = null }) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(340.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText(
                            "Save filter preset",
                            color = tc2.tx,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(5.dp))
                        AppText(
                            "Saves: levels · tags · keyword · highlighters · sequences",
                            color = tc2.td, fontSize = 11.sp, maxLines = 2
                        )
                        Spacer(Modifier.height(14.dp))
                        val trySaveFilter = {
                            val tid = state.sfTabId
                            if (tid != null && state.sfName.isNotBlank()) {
                                state.saveFilter(tid, state.sfName, state.sfFolderId)
                            }
                        }
                        InlineField(
                            state.sfName,
                            { state.sfName = it },
                            "Preset name…",
                            Modifier.fillMaxWidth(),
                            fontSize = 13.sp,
                            onSubmit = trySaveFilter,
                        )
                        Spacer(Modifier.height(10.dp))
                        AppText("Folder", color = tc2.td, fontSize = 10.sp)
                        Spacer(Modifier.height(4.dp))
                        SavedFilterFolderPicker(
                            folders = state.savedFilterFolders,
                            selectedFolderId = state.sfFolderId,
                            onSelect = { state.sfFolderId = it },
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                            DialogActionButton("Save", active = state.sfName.isNotBlank()) { trySaveFilter() }
                            DialogActionButton("Cancel", active = false) {
                                state.sfDialog = false
                                state.sfFolderId = null
                            }
                        }
                    }
                }
            }

            state.pendingDuplicateFilterSave?.let { pending ->
                Dialog(onDismissRequest = { state.cancelDuplicateFilterSave() }) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(380.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText("Replace saved filter?", color = tc2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            "\"${pending.existingName}\" already exists. Replace it with the current filter settings, or cancel and save with another name.",
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DialogActionButton("Replace", active = true, danger = true) { state.confirmReplaceDuplicateFilter() }
                            DialogActionButton("Cancel", active = false) { state.cancelDuplicateFilterSave() }
                        }
                    }
                }
            }

            state.pendingFilterLoad?.takeIf { !state.sfDialog && !state.updateExistingPickerOpen }?.let { pending ->
                val current = pending.currentFilterId?.let { id -> state.savedFilters.find { it.id == id } }
                val target = state.savedFilters.find { it.id == pending.targetFilterId }
                Dialog(onDismissRequest = { state.cancelPendingFilterLoad() }) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(380.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText(
                            "Save current filter changes?",
                            color = tc2.tx,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            "Before loading \"${target?.name ?: "another filter"}\", save the changes to \"${current?.name ?: "current filter"}\".",
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(14.dp))
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                DialogActionButton("Save as new", active = true) { state.beginSavePendingFilterAsNew() }
                                DialogActionButton(
                                    "Update existing",
                                    active = true,
                                ) { state.beginUpdateExistingPick() }
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                DialogActionButton(
                                    "Do not save",
                                    active = true,
                                    danger = true
                                ) { state.discardPendingFilterChangesAndLoad() }
                                DialogActionButton("Cancel", active = false) { state.cancelPendingFilterLoad() }
                            }
                        }
                    }
                }
            }

            // See PendingNoteOverwrite / AppState.autoExportAnnotations: this fires when the very
            // first annotation edit on a tab resolves to a "<base>_analysis.md" that already exists
            // on disk from an unrelated earlier session. dismissOnClickOutside = false, same as the
            // zip-picker and other consequential dialogs below, so an accidental outside click can't
            // silently pick "Cancel" for the user.
            state.pendingNoteOverwrite?.let { pending ->
                Dialog(
                    onDismissRequest = { state.cancelNoteOverwrite() },
                    properties = DialogProperties(dismissOnClickOutside = false),
                ) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(380.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText(
                            "Existing notes found",
                            color = tc2.tx,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            "\"${pending.targetName}\" already has saved notes for this log, from a session that " +
                                "never opened this file. Nothing is being written to disk while this is open — " +
                                "choose how to proceed.",
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 5,
                        )
                        Spacer(Modifier.height(14.dp))
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DialogActionButton(
                                    "Open existing notes",
                                    active = true,
                                ) { state.openExistingNoteInsteadOfOverwrite() }
                                DialogActionButton(
                                    "Save to a new file",
                                    active = true,
                                ) { state.saveNotesToNewNoteFile() }
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DialogActionButton(
                                    "Overwrite",
                                    active = true,
                                    danger = true,
                                ) { state.confirmNoteOverwrite() }
                                DialogActionButton("Cancel", active = false) { state.cancelNoteOverwrite() }
                            }
                        }
                    }
                }
            }

            // Change 2c's mismatch gate — see AppState.beginLogRelink/PendingLogRelink. The log
            // named here is already open as its own plain tab; this only decides whether the
            // note's blocks get attached to it. dismissOnClickOutside = false, same reasoning as
            // pendingNoteOverwrite above: an accidental outside click must not silently pick
            // "Cancel" for a decision this consequential.
            state.pendingLogRelink?.let { pending ->
                Dialog(
                    onDismissRequest = { state.cancelLogRelink() },
                    properties = DialogProperties(dismissOnClickOutside = false),
                ) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(420.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText(
                            "This might be a different capture",
                            color = tc2.tx,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            "\"${pending.fileName}\" doesn't match the log these notes were saved against — its " +
                                "content looks different. The notes will still show their stored lines, but " +
                                "clicking one may jump to an unrelated row in this file.",
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 5,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DialogActionButton("Open anyway", active = true, danger = true) { state.confirmLogRelink() }
                            DialogActionButton("Cancel", active = false) { state.cancelLogRelink() }
                        }
                    }
                }
            }

            state.pendingFilterLoad?.takeIf { state.updateExistingPickerOpen }?.let { pending ->
                val target = state.savedFilters.find { it.id == pending.targetFilterId }
                Dialog(onDismissRequest = { state.cancelUpdateExistingPick() }) {
                    val tc2 = tc()
                    var pickerExpanded by remember { mutableStateOf(false) }
                    val pickerDensity = LocalDensity.current.density
                    Column(
                        Modifier.width(320.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText(
                            "Update which filter?",
                            color = tc2.tx,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            "Overwrite the chosen preset with your current changes, then load \"${target?.name ?: "another filter"}\".",
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth()) {
                            HoverBox(
                                modifier = Modifier.fillMaxWidth()
                                    .border(1.dp, tc2.br, RoundedCornerShape(5.dp))
                                    .clip(RoundedCornerShape(5.dp)),
                                onClick = { pickerExpanded = true },
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AppText("Choose a filter…", color = tc2.td, fontSize = 12.sp)
                                    AppText("▾", color = tc2.td, fontSize = 12.sp)
                                }
                            }
                            if (pickerExpanded) {
                                Popup(
                                    alignment = Alignment.TopStart,
                                    offset = IntOffset(0, (36 * pickerDensity).roundToInt()),
                                    onDismissRequest = { pickerExpanded = false },
                                    properties = PopupProperties(focusable = true),
                                ) {
                                    Column(
                                        Modifier.width(280.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                                            .background(tc2.p, RoundedCornerShape(7.dp))
                                            .border(1.dp, tc2.br, RoundedCornerShape(7.dp))
                                            .padding(vertical = 4.dp),
                                    ) {
                                        state.savedFilters.forEach { sf ->
                                            HoverBox(
                                                modifier = Modifier.fillMaxWidth(),
                                                onClick = { pickerExpanded = false; state.confirmUpdateExisting(sf.id) },
                                            ) {
                                                AppText(
                                                    sf.name,
                                                    color = tc2.tx,
                                                    fontSize = 12.sp,
                                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            DialogActionButton("Cancel", active = false) { state.cancelUpdateExistingPick() }
                        }
                    }
                }
            }

            state.pendingClearFilterTabId?.let { tabId ->
                Dialog(onDismissRequest = { state.cancelClearFilter() }) {
                    val tc2 = tc()
                    val tabName = state.tab(tabId)?.filename ?: "current file"
                    Column(
                        Modifier.width(380.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText("Clear filters?", color = tc2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            "Reset levels, tags, keyword rules, highlighters, message rules, and the active preset for \"$tabName\".",
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DialogActionButton(
                                "Clear filters",
                                active = true,
                                danger = true
                            ) { state.confirmClearFilter() }
                            DialogActionButton("Cancel", active = false) { state.cancelClearFilter() }
                        }
                    }
                }
            }

            state.pendingDeleteFilterId?.let { filterId ->
                val draftName = state.filterDraftsByTab.values.find { it.id == filterId }?.name
                val filterName = state.savedFilters.find { it.id == filterId }?.name
                    ?: draftName
                    ?: "this filter"
                Dialog(onDismissRequest = { state.cancelDeleteSF() }) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(360.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText(
                            if (draftName != null) "Delete filter draft?" else "Delete saved filter?",
                            color = tc2.tx,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            if (draftName != null) {
                                "Remove \"$filterName\" from this tab's filter list. Current filter values stay applied."
                            } else {
                                "Delete \"$filterName\" from saved filters."
                            },
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DialogActionButton("Delete", active = true, danger = true) { state.confirmDeleteSF() }
                            DialogActionButton("Cancel", active = false) { state.cancelDeleteSF() }
                        }
                    }
                }
            }

            state.pendingDeleteSavedFilterFolderId?.let { folderId ->
                val folderName = state.savedFilterFolders.firstOrNull { it.id == folderId }?.name ?: "this folder"
                Dialog(onDismissRequest = { state.cancelDeleteSavedFilterFolder() }) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(380.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText("Delete filter folder?", color = tc2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            "Delete “$folderName”? Its saved filters will move to Ungrouped; no filters will be deleted.",
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DialogActionButton("Delete folder", active = true, danger = true) {
                                state.confirmDeleteSavedFilterFolder()
                            }
                            DialogActionButton("Cancel", active = false) { state.cancelDeleteSavedFilterFolder() }
                        }
                    }
                }
            }

            state.pendingFilterRename?.let { pending ->
                Dialog(onDismissRequest = { state.cancelRenameFilter() }) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(380.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText(
                            if (pending.isDraft) "Save draft filter" else "Rename saved filter",
                            color = tc2.tx,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            if (pending.isDraft) {
                                "Renaming this draft saves it as a normal filter preset."
                            } else {
                                "Choose a unique name for \"${pending.currentName}\"."
                            },
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(12.dp))
                        InlineField(
                            state.filterRenameName,
                            {
                                state.filterRenameName = it
                                state.filterRenameError = null
                            },
                            "Filter name…",
                            Modifier.fillMaxWidth(),
                            fontSize = 13.sp,
                        )
                        state.filterRenameError?.let {
                            Spacer(Modifier.height(6.dp))
                            AppText(it, color = DANGER_RED, fontSize = 11.sp, maxLines = 2)
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DialogActionButton("Save", active = state.filterRenameName.isNotBlank()) {
                                state.confirmRenameFilter()
                            }
                            DialogActionButton("Cancel", active = false) { state.cancelRenameFilter() }
                        }
                    }
                }
            }

            if (state.filterExportDialogOpen) {
                Dialog(onDismissRequest = { state.cancelExportFilters() }) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(440.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText("Export saved filters", color = tc2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        AppText("Choose which normal saved filters to export.", color = tc2.td, fontSize = 11.sp, maxLines = 2)
                        Spacer(Modifier.height(10.dp))
                        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                            state.savedFilters.forEach { sf ->
                                CheckRow(
                                    checked = sf.id in state.filterExportSelectedIds,
                                    onToggle = { state.toggleExportFilterSelection(sf.id) },
                                ) {
                                    TooltipArea(
                                        tooltip = {
                                            Box(
                                                Modifier.background(tc2.p2, RoundedCornerShape(4.dp))
                                                    .border(0.5.dp, tc2.br, RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                            ) {
                                                AppText(sf.name, color = tc2.tx, fontSize = 11.sp)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        AppText(sf.name, color = tc2.tx, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DialogActionButton("Export all", active = true) { state.exportFiltersToFile() }
                            DialogActionButton(
                                "Export selected",
                                active = state.filterExportSelectedIds.isNotEmpty(),
                                enabled = state.filterExportSelectedIds.isNotEmpty(),
                            ) { state.exportFiltersToFile(state.filterExportSelectedIds) }
                            DialogActionButton("Cancel", active = false) { state.cancelExportFilters() }
                        }
                    }
                }
            }

            state.pendingImportReview?.let { review ->
                Dialog(onDismissRequest = { state.cancelImportFilters() }, properties = DialogProperties(dismissOnClickOutside = false)) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(560.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText("Import saved filters", color = tc2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            review.sourceName?.let { "Review filters from \"$it\" before importing." }
                                ?: "Review filters before importing.",
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(8.dp))
                        val folderNameFor: (String?) -> String = { folderId ->
                            folderId?.let { id ->
                                state.savedFilterFolders.firstOrNull { it.id == id }?.name
                                    ?: review.stagedFolders.firstOrNull { it.id == id }?.name
                            } ?: "Ungrouped"
                        }
                        val toggleableIds: (List<ImportFilterReviewRow>) -> Set<String> = { rows ->
                            rows.filter { it.skippedReason == null }.map { it.rowId }.toSet()
                        }
                        val grouped = review.rows.groupBy { folderNameFor(it.incoming.folderId) }.toList()
                            .sortedWith(compareBy { (name, _) -> name == "Ungrouped" })
                        val allToggleableIds = toggleableIds(review.rows)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AppText(
                                "Select all",
                                color = tc2.ac,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { state.setImportRowsChecked(allToggleableIds, true) },
                            )
                            AppText(
                                "Select none",
                                color = tc2.ac,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { state.setImportRowsChecked(allToggleableIds, false) },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Column(
                            Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            grouped.forEach { (folderName, rowsInFolder) ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val groupToggleableIds = toggleableIds(rowsInFolder)
                                    val allChecked = groupToggleableIds.isNotEmpty() &&
                                        rowsInFolder.filter { it.rowId in groupToggleableIds }.all { it.action != ImportFilterAction.SKIP }
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        if (groupToggleableIds.isNotEmpty()) {
                                            Checkbox(
                                                checked = allChecked,
                                                onCheckedChange = { state.setImportRowsChecked(groupToggleableIds, it) },
                                                colors = CheckboxDefaults.colors(checkedColor = tc2.ac, uncheckedColor = tc2.td, checkmarkColor = tc2.bg),
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                        AppText(folderName, color = tc2.tx, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    rowsInFolder.forEach { row ->
                                        Column(
                                            Modifier.fillMaxWidth().background(tc2.bg, CORNER_MD)
                                                .border(1.dp, tc2.br, CORNER_MD).padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                if (row.skippedReason == null) {
                                                    Checkbox(
                                                        checked = row.action != ImportFilterAction.SKIP,
                                                        onCheckedChange = { state.setImportRowsChecked(setOf(row.rowId), it) },
                                                        colors = CheckboxDefaults.colors(
                                                            checkedColor = tc2.ac,
                                                            uncheckedColor = tc2.td,
                                                            checkmarkColor = tc2.bg,
                                                        ),
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                                AppText(
                                                    row.incoming.name,
                                                    color = tc2.tx,
                                                    fontSize = 11.sp,
                                                    modifier = Modifier.weight(1f),
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                AppText(
                                                    when (row.action) {
                                                        ImportFilterAction.ADD -> "add"
                                                        ImportFilterAction.RENAME -> "rename"
                                                        ImportFilterAction.REPLACE -> "replace"
                                                        ImportFilterAction.SKIP -> "skip"
                                                    },
                                                    color = if (row.action == ImportFilterAction.SKIP) tc2.td else tc2.ac,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                            }
                                            if (row.action == ImportFilterAction.RENAME) {
                                                InlineField(
                                                    row.resolvedName,
                                                    { state.setImportFilterRename(row.rowId, it) },
                                                    "Imported name…",
                                                    Modifier.fillMaxWidth(),
                                                    fontSize = 12.sp,
                                                )
                                            } else {
                                                AppText(
                                                    if (row.action == ImportFilterAction.SKIP) {
                                                        row.skippedReason?.let { "Skipped: $it" } ?: "Skipped."
                                                    } else {
                                                        "Will import as \"${row.resolvedName}\"."
                                                    },
                                                    color = tc2.td,
                                                    fontSize = 10.sp,
                                                    maxLines = 2,
                                                )
                                            }
                                            if (row.targetId != null && row.skippedReason == null) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    AppButton("Rename", onClick = {
                                                        state.setImportFilterAction(row.rowId, ImportFilterAction.RENAME)
                                                    }, variant = if (row.action == ImportFilterAction.RENAME) {
                                                        ButtonVariant.Primary
                                                    } else {
                                                        ButtonVariant.Secondary
                                                    })
                                                    AppButton("Replace", onClick = {
                                                        state.setImportFilterAction(row.rowId, ImportFilterAction.REPLACE)
                                                    }, variant = if (row.action == ImportFilterAction.REPLACE) {
                                                        ButtonVariant.Primary
                                                    } else {
                                                        ButtonVariant.Secondary
                                                    })
                                                    AppButton("Skip", onClick = {
                                                        state.setImportFilterAction(row.rowId, ImportFilterAction.SKIP)
                                                    }, variant = if (row.action == ImportFilterAction.SKIP) ButtonVariant.Primary else ButtonVariant.Secondary)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DialogActionButton("Import", active = true) { state.confirmImportFilters() }
                            DialogActionButton("Cancel", active = false) { state.cancelImportFilters() }
                        }
                    }
                }
            }

            state.importError?.let { message ->
                Dialog(onDismissRequest = { state.importError = null }) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(360.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText("Could not import filters", color = tc2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        AppText(message, color = tc2.td, fontSize = 11.sp, maxLines = 3)
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            DialogActionButton("OK", active = true) { state.importError = null }
                        }
                    }
                }
            }

            state.pendingZipPicker?.let { pending ->
                EntryPickerDialog(
                    sourceLabel = pending.zipFile.name,
                    candidates = pending.candidates,
                    videoCandidates = pending.videoCandidates,
                    onCancel = { state.cancelZipPicker() },
                    onConfirm = { selected, video -> state.openZipEntries(pending.zipFile, selected, video) },
                )
            }

            state.pendingFolderPicker?.let { pending ->
                EntryPickerDialog(
                    sourceLabel = pending.folder.name,
                    candidates = pending.candidates,
                    videoCandidates = pending.videoCandidates,
                    truncatedNotice = if (pending.truncated) {
                        "This folder has more entries than could be scanned — some logs may be missing from this list."
                    } else {
                        null
                    },
                    onCancel = { state.cancelFolderPicker() },
                    onConfirm = { selected, video -> state.openFolderEntries(pending.folder, selected, video) },
                )
            }

            state.pendingSplitPrompt?.let { pending ->
                SplitPromptDialog(
                    state = state,
                    pending = pending,
                    onDismiss = { state.cancelSplitPrompt() },
                )
            }

            // Renders itself off state.seqDiagrams.request (null = closed), so there's no separate
            // boolean flag to keep in sync with the request payload.
            SeqDiagramDialog(state)

            if (state.mergeTabsDialogOpen) {
                var selected by remember {
                    mutableStateOf(state.mergeTabsPreselectedId?.let { setOf(it) } ?: emptySet())
                }
                var mergedName by remember { mutableStateOf("Merged") }

                fun close() {
                    state.mergeTabsDialogOpen = false
                    state.mergeTabsPreselectedId = null
                    selected = emptySet()
                }
                Dialog(
                    onDismissRequest = { close() },
                    properties = DialogProperties(dismissOnClickOutside = false),
                ) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(420.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText("Merge tabs", color = tc2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            "Pick 2 or more open tabs to merge into one, interleaved by time-of-day.",
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(10.dp))
                        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                            state.tabs.forEach { candidateTab ->
                                CheckRow(
                                    checked = candidateTab.id in selected,
                                    onToggle = {
                                        selected = if (candidateTab.id in selected) {
                                            selected - candidateTab.id
                                        } else {
                                            selected + candidateTab.id
                                        }
                                    },
                                ) {
                                    AppText(candidateTab.filename, color = tc2.tx, fontSize = 11.sp, fontFamily = MONO,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        InlineField(mergedName, { mergedName = it }, "Merged tab name…", Modifier.fillMaxWidth(), fontSize = 13.sp)
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DialogActionButton(
                                "Merge",
                                active = selected.size >= 2,
                                enabled = selected.size >= 2,
                            ) {
                                state.mergeTabs(selected.toList(), mergedName.ifBlank { "Merged" })
                                close()
                            }
                            DialogActionButton("Cancel", active = false) { close() }
                        }
                    }
                }
            }

            // ── Recent files popup ────────────────────────────────────
            if (state.recentMenuOpen && state.recentFiles.isNotEmpty()) {
                BoxWithConstraints(
                    Modifier.fillMaxSize().clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { state.recentMenuOpen = false },
                    )
                ) {
                    val menuWidth = 320.dp
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-8).dp, y = 44.dp)
                            .width(menuWidth)
                            .background(tc.p, RoundedCornerShape(7.dp))
                            .border(1.dp, tc.br, RoundedCornerShape(7.dp)),
                    ) {
                        Column {
                            Box(
                                Modifier.fillMaxWidth().background(tc.p2)
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                AppText(
                                    "Recent Files (${state.recentFiles.size})",
                                    color = tc.ts,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
                            // All retained entries are reachable; keep the popup bounded and
                            // scroll the full list rather than showing an unusable "N more" hint.
                            val displayFiles = recentFilesForMenu(state.recentFiles)
                            val listH = (displayFiles.size * 46).coerceAtMost(460).dp
                            val recentScroll = rememberScrollState()
                            Box(Modifier.fillMaxWidth().height(listH)) {
                                Column(
                                    Modifier.fillMaxSize().verticalScroll(recentScroll).padding(end = 10.dp),
                                ) {
                                    displayFiles.forEach { path ->
                                        val file = File(path)
                                        val exists = file.exists()
                                        HoverBox(
                                            modifier = Modifier.fillMaxWidth(),
                                            onClick = { state.openPath(file) },
                                        ) {
                                            Column(
                                                Modifier.fillMaxWidth()
                                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                                            ) {
                                                AppText(
                                                    file.name,
                                                    color = if (exists) tc.tx else tc.td,
                                                    fontSize = 12.sp,
                                                    fontFamily = MONO,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                AppText(
                                                    file.parent ?: path,
                                                    color = tc.td,
                                                    fontSize = 10.sp,
                                                    fontFamily = MONO,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                                VerticalScrollbar(
                                    adapter = rememberScrollbarAdapter(recentScroll),
                                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                                    style = appScrollbarStyle(tc),
                                )
                            }
                        }
                    }
                }
            }

            state.openError?.let { error ->
                Dialog(onDismissRequest = { state.dismissOpenError() }) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(420.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText(error.title, color = tc2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        AppText(error.message, color = tc2.td, fontSize = 11.sp, maxLines = 4)
                        error.path?.let { path ->
                            Spacer(Modifier.height(10.dp))
                            Box(
                                Modifier.fillMaxWidth().background(tc2.bg, CORNER_SM)
                                    .border(1.dp, tc2.br, CORNER_SM).padding(10.dp),
                            ) {
                                AppText(
                                    path,
                                    color = tc2.ts,
                                    fontSize = 11.sp,
                                    fontFamily = MONO,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DialogActionButton("Close", active = true) { state.dismissOpenError() }
                        }
                    }
                }
            }

            // ── Settings dialog ───────────────────────────────────────
            if (state.settingsOpen) {
                // Clicking outside used to close the dialog unconditionally, bypassing the AI
                // providers section's unsaved-changes guard entirely - disabled so the only way
                // out is through a control SettingsDialog itself gates (X, Done, Escape).
                var settingsRequestClose by remember { mutableStateOf<() -> Unit>({ state.settingsOpen = false }) }
                // usePlatformDefaultWidth defaults to true, which silently clamps this dialog's
                // content to Android's ported "preferred dialog width" (580dp on a window this
                // size) no matter what width SettingsDialog's own Box requests — disabled so the
                // sidebar + content layout actually gets the width it asks for.
                Dialog(
                    onDismissRequest = { settingsRequestClose() },
                    properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
                ) {
                    SettingsDialog(
                        state,
                        onDismiss = { state.settingsOpen = false },
                        onRequestCloseChanged = { settingsRequestClose = it },
                    )
                }
            }

            if (state.licenseAgreementOpen && !state.needsLicenseAcceptance) {
                LicenseAgreementDialog(mandatory = false, onDismiss = { state.licenseAgreementOpen = false })
            }

            if (state.needsLicenseAcceptance) {
                LicenseAgreementDialog(
                    mandatory = true,
                    onAccept = state::acceptLicenseAgreement,
                    onDecline = onLicenseDeclined,
                )
            }

            if (state.updateDialogVisible) {
                UpdateDialog(state)
            }

            // ── Source code popup ─────────────────────────────────────
            state.sourceCodeView?.let { view ->
                SourceCodeDialog(state, view, onDismiss = { state.sourceCodeView = null })
            }

            if (state.cacheClearConfirmOpen) {
                Dialog(onDismissRequest = { state.cancelClearCache() }) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(400.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText("Clear temporary data?", color = tc2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            "Deletes cached archive data and app-managed notes. Keeps settings, the current " +
                                "session/autosave, saved filters, source and case indexes, diagnostics, and " +
                                "integration data. It does not reset Indagium.",
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 6,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DialogActionButton(
                                "Clear temporary data",
                                active = true,
                                danger = true,
                            ) { state.confirmClearCache() }
                            DialogActionButton("Cancel", active = false) { state.cancelClearCache() }
                        }
                    }
                }
            }

            if (state.resetAppDataConfirmOpen) {
                Dialog(onDismissRequest = { state.cancelResetAppData() }) {
                    val tc2 = tc()
                    var confirmation by remember { mutableStateOf("") }
                    Column(
                        Modifier.width(440.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText("Reset app data?", color = tc2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            "Permanently deletes all data stored by Indagium in \"${state.appCachePath}\" and then closes " +
                                "the app. Your logs, source folders, exports, and Default save folder are kept.",
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 5,
                        )
                        Spacer(Modifier.height(12.dp))
                        AppText("Type RESET to continue", color = tc2.td, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            value = confirmation,
                            onValueChange = { confirmation = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = tc2.tx, fontSize = 12.sp),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(tc2.ac),
                            modifier = Modifier.fillMaxWidth().border(1.dp, tc2.br, RoundedCornerShape(5.dp)).padding(8.dp),
                        )
                        state.resetAppDataError?.let {
                            Spacer(Modifier.height(8.dp))
                            AppText(it, color = DANGER_RED, fontSize = 11.sp, maxLines = 2)
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DialogActionButton(
                                "Reset app data",
                                active = true,
                                enabled = confirmation == "RESET",
                                danger = true,
                            ) {
                                if (state.deleteAllAppData()) onResetAppData()
                            }
                            DialogActionButton("Cancel", active = false) { state.cancelResetAppData() }
                        }
                    }
                }
            }

            // ── Keyboard shortcuts dialog ─────────────────────────────
            if (state.shortcutsOpen) {
                // usePlatformDefaultWidth defaults to true and silently caps dialog content to
                // ~580dp regardless of any width modifier inside — must disable it for the
                // 3-column layout to actually get the width it asks for.
                Dialog(
                    onDismissRequest = { state.shortcutsOpen = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    KeyboardShortcutsDialog { state.shortcutsOpen = false }
                }
            }

            // ── MCP connection info dialog ────────────────────────────
            // Uses the persisted connection token when the server is off or still binding, so a
            // click opens immediately instead of waiting for an unrelated recomposition.
            if (state.mcpInfoOpen) {
                val token = state.connectionInfoToken()
                Dialog(onDismissRequest = { state.mcpInfoOpen = false }) {
                    McpInfoDialog(state = state, port = state.settings.mcpControlPort, token = token) { state.mcpInfoOpen = false }
                }
            }

            // ── Case Library dialog ───────────────────────────────────
            if (state.caseLibraryTabId != null) {
                // usePlatformDefaultWidth defaults to true and silently caps dialog content to
                // ~580dp regardless of any width modifier inside — must disable it for the
                // 900dp-wide layout to actually get the width it asks for.
                Dialog(
                    onDismissRequest = { state.closeCaseLibrary() },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    CaseLibraryDialog(state = state, onDismiss = { state.closeCaseLibrary() })
                }
            }

            // ── Custom AI command editor dialog ───────────────────────
            state.customCommandEditorTarget?.let { target ->
                Dialog(onDismissRequest = { state.customCommandEditorTarget = null }) {
                    CustomAiCommandEditorDialog(state = state, target = target) { state.customCommandEditorTarget = null }
                }
            }

            // ── Source folder project info dialog ─────────────────────
            state.sourceFolderInfoEditorTarget?.let { path ->
                Dialog(onDismissRequest = { state.sourceFolderInfoEditorTarget = null }) {
                    SourceFolderInfoDialog(state = state, path = path) { state.sourceFolderInfoEditorTarget = null }
                }
            }
        }
    }
}

@Composable
private fun SavedFilterFolderPicker(
    folders: List<SavedFilterFolder>,
    selectedFolderId: String?,
    onSelect: (String?) -> Unit,
) {
    val tc = tc()
    var open by remember { mutableStateOf(false) }
    val selectedName = folders.firstOrNull { it.id == selectedFolderId }?.name ?: "Ungrouped"
    Box(Modifier.fillMaxWidth()) {
        HoverBox(
            modifier = Modifier.fillMaxWidth().height(28.dp)
                .clip(CORNER_SM)
                .background(tc.bg, CORNER_SM)
                .border(1.dp, tc.br, CORNER_SM),
            onClick = { open = !open },
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AppText(selectedName, color = tc.tx, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
                AppText(if (open) "▲" else "▼", color = tc.td, fontSize = 8.sp)
            }
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, 30),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(300.dp).heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .shadow(8.dp, RoundedCornerShape(8.dp))
                        .background(tc.p, RoundedCornerShape(8.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(8.dp))
                        .padding(4.dp),
                ) {
                    (listOf<SavedFilterFolder?>(null) + folders).forEach { folder ->
                        val selected = folder?.id == selectedFolderId
                        HoverBox(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)),
                            baseBg = if (selected) tc.abg else Color.Transparent,
                            onClick = {
                                onSelect(folder?.id)
                                open = false
                            },
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                AppText(if (selected) "✓" else "", color = tc.ac, fontSize = 10.sp, modifier = Modifier.width(10.dp))
                                AppText(folder?.name ?: "Ungrouped", color = tc.tx, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Cmd/Ctrl+V anywhere in the window drops a clipboard image straight into Notes, so a screenshot
 * can be pasted without first clicking into the Notes panel.
 *
 * Deliberately wired on the root Box's BUBBLE phase (`onKeyEvent`), never the preview phase the
 * neighbouring [handleGlobalKey] uses. Preview runs root→leaf and would beat AnnotationPanel's own
 * paste handler, losing that panel's smarter insertion point (imageInsertionAfterId() puts the
 * image beside the block being edited). Bubbling runs leaf→root: the panel keeps winning while it
 * has focus, and this only fires from everywhere else.
 *
 * Returns false whenever the clipboard holds no image, so an ordinary text paste is untouched.
 */
private fun handleGlobalImagePaste(ev: KeyEvent, state: AppState): Boolean {
    if (ev.type != KeyEventType.KeyDown || !ev.isActionKey || ev.key != Key.V) return false
    val tabId = state.activeTab()?.id ?: return false
    val bytes = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }
        .getOrNull()
        ?.let(::imageBytesFromTransferable) ?: return false
    // Appended at the end: with focus outside Notes there is no "block being edited" to anchor to.
    state.addImageBlock(tabId, bytes, "pasted from clipboard", null) ?: return false
    if (!state.annotationVisible) state.updateAnnotationVisible(true)
    return true
}

private fun handleGlobalKey(
    ev: KeyEvent,
    state: AppState,
    onFocusPanel: (KeyboardPanel) -> Unit,
    onFocusFilterSearch: () -> Unit,
): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    if (ev.isCtrlPressed && ev.key == Key.Tab) {
        navigateTab(state, if (ev.isShiftPressed) -1 else +1)
        return true
    }
    if (!ev.isActionKey) return false
    return when {
        ev.isShiftPressed && ev.key == Key.F -> { state.updateFilterVisible(!state.filterVisible); true }
        ev.isShiftPressed && ev.key == Key.A -> { state.updateAnnotationVisible(!state.annotationVisible); true }
        ev.isShiftPressed && ev.key == Key.D && state.canCompare -> { state.updateCompareMode(!state.compareMode); true }
        // Corpus-wide, not tab-scoped like AnnotationPanel's own plain ⌘O ("Open Note") — a
        // distinct chord so the two never collide (checked against Shortcuts.kt's whole catalogue).
        ev.isShiftPressed && ev.key == Key.O  -> { state.activeTab()?.id?.let(state::openCaseLibrary); true }
        ev.key == Key.F                      -> { onFocusFilterSearch(); true }
        ev.key == Key.One                    -> { state.updateFilterVisible(true); onFocusPanel(KeyboardPanel.FILTERS); true }
        ev.key == Key.Two                    -> { onFocusPanel(KeyboardPanel.LOG_VIEW); true }
        ev.key == Key.Three                  -> { state.updateAnnotationVisible(true); onFocusPanel(KeyboardPanel.NOTES); true }
        ev.key == Key.RightBracket           -> { navigateTab(state, +1); true }
        ev.key == Key.LeftBracket            -> { navigateTab(state, -1); true }
        ev.key == Key.W                      -> { state.activeTab()?.id?.let(state::closeTab); true }
        ev.key == Key.Slash                  -> { state.shortcutsOpen = true; true }
        else -> false
    }
}

private fun navigateTab(state: AppState, delta: Int) {
    val ids = state.tabs.map { it.id }
    val cur = ids.indexOf(state.activeTabId)
    if (cur < 0 || ids.size < 2) return
    state.activateTab(ids[(cur + delta).mod(ids.size)])
}
