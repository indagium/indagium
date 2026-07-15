@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.openlog.model.*
import com.openlog.source.SourceCodeView
import kotlinx.coroutines.delay
import java.io.File
import java.net.URI
import kotlin.math.roundToInt

/** The popup is bounded visually, but every retained path stays reachable by scrolling. */
internal fun recentFilesForMenu(recentFiles: List<String>): List<String> = recentFiles

@Composable
fun App(state: AppState = remember { AppState(restoreOnCreate = true, filterBackupsDir = DesktopStorage.filterBackupsDir()) }) {
    val theme = themeColors(state.settings.theme)
    val rootFocusRequester = remember { FocusRequester() }
    var pendingPanelFocus by remember { mutableStateOf<KeyboardPanel?>(null) }
    var filterSearchFocusRequest by remember { mutableStateOf(0) }

    CompositionLocalProvider(
        LocalTheme provides theme,
        LocalFontBase provides state.settings.fontSize,
        LocalUseMono provides state.settings.fontMono,
    ) {
        val tc = tc()
        LaunchedEffect(
            state.tabs,
            state.savedFilters,
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
            if (state.tabs.none { it.tailing }) state.autosaveNow()
        }
        LaunchedEffect(Unit) {
            runCatching { rootFocusRequester.requestFocus() }
        }
        LaunchedEffect(state) {
            state.startPendingRestoredTabLoads()
        }
        val dropTarget = remember {
            object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val files = runCatching {
                        (event.dragData() as DragData.FilesList).readFiles()
                    }.getOrElse { return false }
                    state.openPaths(files.mapNotNull { uri -> runCatching { File(URI.create(uri)) }.getOrNull() })
                    return true
                }
            }
        }
        val dc by dragCursorOverride
        Box(
            Modifier.fillMaxSize().background(tc.bg)
                .dragAndDropTarget(
                    shouldStartDragAndDrop = { event ->
                        runCatching { event.dragData() is DragData.FilesList }.getOrElse { false }
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
                .onPreviewKeyEvent { ev ->
                    handleGlobalKey(
                        ev = ev,
                        state = state,
                        onFocusPanel = { panel -> state.keyboardFocusVisible = true; pendingPanelFocus = panel },
                        onFocusFilterSearch = {
                            state.keyboardFocusVisible = true
                            state.updateFilterVisible(true)
                            pendingPanelFocus = KeyboardPanel.FILTERS
                            filterSearchFocusRequest += 1
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
                        filterSearchFocusRequest = filterSearchFocusRequest,
                        onPanelFocusConsumed = { pendingPanelFocus = null },
                    )
                    activeTab != null -> key(activeTab.id) {
                        FileView(
                            state = state,
                            tab = activeTab,
                            requestedPanelFocus = pendingPanelFocus,
                            filterSearchFocusRequest = filterSearchFocusRequest,
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
                        val canCollapseToStart = ctxTab.let {
                            manualCollapseAvailability(it, ctx.entryId, ManualCollapseDirection.TO_START) ==
                                ManualCollapseAvailability.AVAILABLE
                        }
                        val canCollapseToEnd = ctxTab.let {
                            manualCollapseAvailability(it, ctx.entryId, ManualCollapseDirection.TO_END) ==
                                ManualCollapseAvailability.AVAILABLE
                        }
                        val canCollapseSelection = ctxTab.let { tab ->
                            selectedIds.size > 1 &&
                                manualCollapseAvailability(
                                    tab,
                                    selectedIds.minOrNull() ?: ctx.entryId,
                                    ManualCollapseDirection.RANGE,
                                    selectedIds.maxOrNull(),
                                ) == ManualCollapseAvailability.AVAILABLE
                        }
                        // Keep the grouped action rows wide enough for three equal-width
                        // buttons, including the longest Highlight/Selected labels.
                        val menuWidth = 276.dp
                        // Estimate full menu height from items that will actually render:
                        //   header(37) + divider(9) + preview(63) + 1 item(32) + divider(9)
                        //   + 2 items(64) [sequence] + divider(9) + 2 items(64) [collapse-to-start/end]
                        //   + divider(9) + 2 items(64) [hide/show] + divider(9) + 1 row(32) [tags]
                        //   + divider(9) + 2 items(64) = 538
                        // Selection text adds a preview extension line(15) on top of that.
                        val estimatedMenuHeight = (458 +
                            (if (ctx.selText.isNotBlank()) 15 else 0) +
                            (if (state.pendingSequenceStart != null) 32 else 0) +
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
                                                val pid = if (entry.pid > 0) "  ${entry.pid.toString().padStart(5)} ${
                                                    entry.tid.toString().padStart(5)
                                                }" else ""
                                                state.copyToClipboard("${entry.ts}$pid  ${entry.level.key}  ${entry.tag}: ${entry.msg}")
                                            }
                                            state.ctx = null
                                        },
                                        onHighlight = { state.addHlFromCtx() },
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
                                            )
                                        is CtxMenuEntry.CollapseActions ->
                                            CtxCollapseActions(
                                                highlighted = e === selectedEntry,
                                                onToStart = e.onToStart,
                                                onToEnd = e.onToEnd,
                                                onSelected = e.onSelected,
                                            )
                                        is CtxMenuEntry.SelectionActions ->
                                            CtxSelectionActions(
                                                highlighted = e === selectedEntry,
                                                onAskAi = e.onAskAi,
                                                onCopy = e.onCopy,
                                                onHighlight = e.onHighlight,
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
                        // real file to watch) or a merged tab (sourcePath is null).
                        val canTail = remember(ttab.sourcePath) {
                            val p = ttab.sourcePath
                            p != null && '!' !in p && File(p).isFile
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
                Dialog(onDismissRequest = { state.sfDialog = false }) {
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
                            if (tid != null && state.sfName.isNotBlank()) state.saveFilter(tid, state.sfName)
                        }
                        InlineField(
                            state.sfName,
                            { state.sfName = it },
                            "Preset name…",
                            Modifier.fillMaxWidth(),
                            fontSize = 13.sp,
                            onSubmit = trySaveFilter,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                            DialogActionButton("Save", active = state.sfName.isNotBlank()) { trySaveFilter() }
                            DialogActionButton("Cancel", active = false) { state.sfDialog = false }
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
                        Spacer(Modifier.height(10.dp))
                        Column(
                            Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            review.rows.forEach { row ->
                                Column(
                                    Modifier.fillMaxWidth().background(tc2.bg, CORNER_MD)
                                        .border(1.dp, tc2.br, CORNER_MD).padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
                                            row.skippedReason?.let { "Skipped: $it" } ?: "Will import as \"${row.resolvedName}\".",
                                            color = tc2.td,
                                            fontSize = 10.sp,
                                            maxLines = 2,
                                        )
                                    }
                                    if (row.targetId != null && row.skippedReason == null) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            AppButton("Rename", onClick = {
                                                state.setImportFilterAction(row.rowId, ImportFilterAction.RENAME)
                                            }, variant = if (row.action == ImportFilterAction.RENAME) ButtonVariant.Primary else ButtonVariant.Secondary)
                                            AppButton("Replace", onClick = {
                                                state.setImportFilterAction(row.rowId, ImportFilterAction.REPLACE)
                                            }, variant = if (row.action == ImportFilterAction.REPLACE) ButtonVariant.Primary else ButtonVariant.Secondary)
                                            AppButton("Skip", onClick = {
                                                state.setImportFilterAction(row.rowId, ImportFilterAction.SKIP)
                                            }, variant = if (row.action == ImportFilterAction.SKIP) ButtonVariant.Primary else ButtonVariant.Secondary)
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
                var selected by remember(pending.zipFile) { mutableStateOf(emptySet<String>()) }
                Dialog(
                    onDismissRequest = { state.cancelZipPicker() },
                    properties = DialogProperties(dismissOnClickOutside = false),
                ) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(420.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText("Multiple log files found", color = tc2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            "\"${pending.zipFile.name}\" contains ${pending.candidates.size} candidate log files. " +
                                "Choose which to open — each opens as its own tab.",
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(10.dp))
                        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                            pending.candidates.forEach { candidate ->
                                val displayEntryPath = zipEntryPathForDisplay(candidate.entryPath)
                                CheckRow(
                                    checked = candidate.entryPath in selected,
                                    onToggle = {
                                        selected = if (candidate.entryPath in selected) {
                                            selected - candidate.entryPath
                                        } else {
                                            selected + candidate.entryPath
                                        }
                                    },
                                ) {
                                    TooltipArea(
                                        tooltip = {
                                            Box(
                                                Modifier
                                                    .widthIn(max = 560.dp)
                                                    .background(tc2.p2, RoundedCornerShape(4.dp))
                                                    .border(0.5.dp, tc2.br, RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                            ) {
                                                AppText(
                                                    candidate.entryPath,
                                                    color = tc2.tx,
                                                    fontSize = 11.sp,
                                                    fontFamily = MONO,
                                                    maxLines = 4,
                                                )
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        AppText(displayEntryPath, color = tc2.tx, fontSize = 11.sp, fontFamily = MONO)
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
                            DialogActionButton(
                                "Open selected",
                                active = selected.isNotEmpty(),
                                enabled = selected.isNotEmpty(),
                            ) {
                                state.openZipEntries(pending.zipFile, pending.candidates.filter { it.entryPath in selected })
                            }
                            DialogActionButton("Cancel", active = false) { state.cancelZipPicker() }
                        }
                    }
                }
            }

            state.pendingSplitPrompt?.let { pending ->
                SplitPromptDialog(
                    state = state,
                    pending = pending,
                    onDismiss = { state.cancelSplitPrompt() },
                )
            }

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
                        AppText("Clear cache?", color = tc2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            "Deletes cached archive data and app-managed auto-saved notes in " +
                                "\"${state.appCachePath}\". Notes in the Default save folder are kept.",
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 4,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DialogActionButton(
                                "Clear cache",
                                active = true,
                                danger = true,
                            ) { state.confirmClearCache() }
                            DialogActionButton("Cancel", active = false) { state.cancelClearCache() }
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
