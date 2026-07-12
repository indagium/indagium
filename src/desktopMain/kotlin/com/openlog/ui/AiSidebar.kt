@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.openlog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.openlog.ai.AiEvidence
import com.openlog.ai.AiInvestigationContext
import com.openlog.ai.AiQuickAction
import com.openlog.ai.AiRun
import com.openlog.ai.AiRunEvent
import com.openlog.ai.AiStartResult
import com.openlog.ai.AiToolConfirmation
import com.openlog.ai.ModelDiscoveryResult
import com.openlog.ai.isLoopbackHost
import com.openlog.ai.normalizeAiProviderProfiles
import com.openlog.model.LogTab
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Provider and Actions share one row as an accordion: opening one closes the other. */
internal enum class AiSidebarSection { PROVIDER, ACTIONS }

private fun AiSidebarSection?.toggled(section: AiSidebarSection): AiSidebarSection? =
    if (this == section) null else section

/**
 * Notes and the AI panel are independent visibility toggles (AppState.annotationVisible /
 * aiPanelVisible, both driven from the main toolbar) sharing this one resizable sidebar slot.
 * With both on, it splits vertically - Notes above AI - at a user-draggable ratio
 * (rightSidebarSplit); with only one on, that one fills the whole slot.
 */
@Composable
internal fun RightSidebarPanel(
    state: AppState,
    tab: LogTab,
    width: Float,
    aiFocusRequester: FocusRequester,
    onAiPanelFocusChanged: (Boolean) -> Unit,
    notesContent: @Composable () -> Unit,
) {
    val notesOn = state.annotationVisible
    val aiOn = state.aiPanelVisible
    val density = LocalDensity.current
    var totalHeightPx by remember { mutableStateOf(0) }
    Column(Modifier.width(width.dp).fillMaxHeight().background(tc().p)) {
        // notesContent() and AiSidebarPanel() each appear at exactly one call site below,
        // regardless of notesOn/aiOn - only their weight changes. Branching into separate `when`
        // arms that each called them (as this used to) puts them in structurally different
        // composition groups, so Compose tears down and rebuilds the whole subtree - and every
        // `remember`ed UI state (collapsed sections, scroll position) - the moment the other panel
        // is toggled, rather than just resizing in place.
        //
        // Height comes from onSizeChanged rather than BoxWithConstraints: the latter is backed by
        // SubcomposeLayout, and nesting a Popup-based dropdown (the model/provider pickers below)
        // several levels inside one has been observed to throw a Compose Desktop
        // "layouts are not part of the same hierarchy" IllegalArgumentException when the popup
        // tries to position itself relative to its anchor. Plain onSizeChanged avoids the extra
        // subcomposition boundary entirely.
        Column(Modifier.weight(1f).fillMaxWidth().onSizeChanged { totalHeightPx = it.height }) {
            val totalHeightDp = with(density) { totalHeightPx.toDp().value }
            if (notesOn) {
                Box(Modifier.weight(if (aiOn) state.rightSidebarSplit else 1f).fillMaxWidth()) { notesContent() }
            }
            if (notesOn && aiOn) {
                VDivider { delta ->
                    val newFrac = (state.rightSidebarSplit * totalHeightDp + delta) / totalHeightDp
                    state.updateRightSidebarSplit(newFrac)
                }
            }
            if (aiOn) {
                Box(Modifier.weight(if (notesOn) 1f - state.rightSidebarSplit else 1f).fillMaxWidth()) {
                    AiSidebarPanel(
                        state = state,
                        tab = tab,
                        focusRequester = aiFocusRequester,
                        onPanelFocusChanged = onAiPanelFocusChanged,
                    )
                }
            }
        }
    }
}

@Composable
private fun AiSidebarPanel(
    state: AppState,
    tab: LogTab,
    focusRequester: FocusRequester,
    onPanelFocusChanged: (Boolean) -> Unit,
) {
    val colors = tc()
    val runtime = state.aiSidebarRuntime
    // Reading this value subscribes composition to a batched update stream: assistant Markdown
    // is not recomposed for every provider token.
    val revision by runtime.revision.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val observedRevision = revision
    val profiles = normalizeAiProviderProfiles(state.settings.aiProviderProfiles)
    val profile = profiles.first { it.selected }
    val session = runtime.sessionFor(tab.id)
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var panelFocused by remember { mutableStateOf(false) }
    var prompt by remember(tab.id) { mutableStateOf("") }
    var modelDraft by remember(profile.id, profile.model) { mutableStateOf(profile.model) }
    var keyDraft by remember(profile.id) { mutableStateOf(state.aiProviderApiKey(profile.id)) }
    var error by remember(tab.id, profile.id) { mutableStateOf<String?>(null) }
    var modelDiscovery by remember(profile.id) { mutableStateOf<ModelDiscoveryResult?>(null) }
    var panelHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    fun profileForRun(): com.openlog.model.AiProviderProfile? {
        val draft = profile.copy(model = modelDraft.trim())
        if (draft != profile) {
            val saveError = state.updateAiProviderProfile(draft)
            if (saveError != null) {
                error = saveError
                return null
            }
        }
        return state.settings.aiProviderProfiles.firstOrNull { it.id == draft.id } ?: draft
    }

    fun start(promptToSend: String, context: AiInvestigationContext = AiInvestigationContext(tab.id)) {
        val currentProfile = profileForRun() ?: return
        state.setAiProviderApiKey(currentProfile.id, keyDraft)
        when (val result = runtime.start(tab.id, currentProfile, keyDraft, promptToSend, context)) {
            is AiStartResult.Started -> {
                prompt = ""
                error = null
            }
            is AiStartResult.Rejected -> error = result.message
        }
    }

    // Context-menu requests are stored on AppState only long enough to reach the correct tab's
    // sidebar. The request itself carries the tab/line, so switching tabs before this effect runs
    // cannot make the model inspect whichever tab happened to become active.
    val pendingRequest = state.pendingAiPromptRequest
    LaunchedEffect(pendingRequest?.id, tab.id) {
        val request = pendingRequest ?: return@LaunchedEffect
        if (request.context.tabId != tab.id) return@LaunchedEffect
        start(request.prompt, request.context)
        state.consumeAiPromptRequest(request.id)
    }

    // Pins to the bottom while a request is in flight (streamed tokens, a new tool-trace row)
    // rather than on any content-size change: gating on activeRun, not a bare maxValue watch,
    // means expanding/collapsing an already-finished run's investigation block - which also
    // changes maxValue - no longer yanks the view back to the bottom.
    LaunchedEffect(scroll, session.activeRun) {
        if (session.activeRun == null) return@LaunchedEffect
        snapshotFlow { scroll.maxValue }.collect { max -> scroll.scrollTo(max) }
    }

    Column(
        Modifier.fillMaxSize().background(colors.p)
            .border(BorderStroke(1.dp, if (panelFocused && state.keyboardFocusVisible) colors.ac else colors.br))
            .onSizeChanged { panelHeightPx = it.height }
            .focusRequester(focusRequester).focusGroup().focusable()
            .onFocusChanged { panelFocused = it.hasFocus; onPanelFocusChanged(it.hasFocus) }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val actionPressed = event.isCtrlPressed || event.isMetaPressed
                when {
                    actionPressed && event.key == Key.Enter -> {
                        if (session.activeRun == null) start(prompt) else runtime.cancel(session.activeRun!!)
                        true
                    }
                    event.key == Key.Escape && session.activeRun != null -> {
                        runtime.cancel(session.activeRun!!); true
                    }
                    else -> false
                }
            },
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(scroll)
                        .padding(start = 10.dp, top = 8.dp, end = 18.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Actions and Provider share one row as an accordion (opening one closes the
                    // other) rather than two independently-collapsible sections stacked on top of
                    // each other - besides being more compact, this keeps both tabs the same height
                    // regardless of what either section's content contains. The expanded section is
                    // tracked on AppState, not a local `remember`, so it survives the AI panel being
                    // toggled off and back on (see aiSidebarExpandedSection).
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AiSectionTab(
                            "Actions",
                            expanded = state.aiSidebarExpandedSection == AiSidebarSection.ACTIONS,
                            onToggle = {
                                state.aiSidebarExpandedSection = state.aiSidebarExpandedSection
                                    .toggled(AiSidebarSection.ACTIONS)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        AiSectionTab(
                            "Provider",
                            expanded = state.aiSidebarExpandedSection == AiSidebarSection.PROVIDER,
                            onToggle = {
                                state.aiSidebarExpandedSection = state.aiSidebarExpandedSection
                                    .toggled(AiSidebarSection.PROVIDER)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    when (state.aiSidebarExpandedSection) {
                        AiSidebarSection.ACTIONS -> AiQuickActionsContent(
                            tab = tab,
                            onAction = { action -> state.requestAiInvestigation(tab.id, action) },
                        )
                        AiSidebarSection.PROVIDER -> AiProviderControls(
                            state = state,
                            profile = profile,
                            model = modelDraft,
                            onModelChange = { modelDraft = it },
                            apiKey = keyDraft,
                            onApiKeyChange = { keyDraft = it; state.setAiProviderApiKey(profile.id, it) },
                            discovery = modelDiscovery,
                            onDiscoverModels = {
                                scope.launch {
                                    modelDiscovery = runtime.discoverModels(profile.copy(model = modelDraft.trim()), keyDraft)
                                }
                            },
                            onAddProvider = {
                                state.addAiProviderProfile()
                                state.settingsOpen = true
                            },
                        )
                        null -> Unit
                    }
                    val lastEvent = session.runs.lastOrNull()?.history?.lastOrNull()
                    val connectionState = when {
                        session.activeRun != null -> "Generating with ${profile.displayName}…"
                        lastEvent is AiRunEvent.Error -> "Last provider request failed"
                        lastEvent == AiRunEvent.Cancelled -> "Last request was stopped"
                        else -> "Ready"
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppText("Connection: $connectionState", color = colors.td, fontSize = 10.sp)
                        if (session.runs.isNotEmpty()) {
                            AppButton(
                                "Reset",
                                onClick = {
                                    runtime.resetSession(tab.id)
                                    prompt = ""
                                    error = null
                                },
                                variant = ButtonVariant.Ghost,
                                modifier = Modifier.height(22.dp),
                            )
                        }
                    }
                    error?.let { AppText(it, color = DANGER_RED, fontSize = 11.sp) }
                    if (session.runs.isEmpty()) {
                        AppText(
                            "Ask about the active log tab. The assistant can use the same log, filter, source, and notes tools as MCP.",
                            color = colors.td,
                            fontSize = 11.sp,
                            maxLines = 4,
                        )
                    }
                    // run.history is read here, in the scope that actually observes `revision`, and
                    // passed down as a plain List so Compose's skip check sees real structural
                    // change. AiRun itself mutates its history in place behind a synchronized list,
                    // so a composable that only takes `run` as a parameter can be skipped by Compose
                    // (same object reference) and never pick up new events until something else
                    // forces the whole subtree to recompose.
                    session.runs.forEach { run ->
                        AiRunCard(run, run.history, runtime::resolveConfirmation, state::navigateAiEvidence, state::copyToClipboard)
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                style = appScrollbarStyle(colors),
            )
        }
        val maxPromptHeightDp = with(density) { (panelHeightPx / 2).toDp() }.coerceAtLeast(54.dp)
        AiPromptComposer(
            prompt = prompt,
            running = session.activeRun,
            retryAvailable = session.activeRun == null && session.lastPrompt != null,
            maxHeightDp = maxPromptHeightDp,
            onPromptChange = { prompt = it },
            onSend = { start(prompt) },
            onStop = { session.activeRun?.let(runtime::cancel) },
            onRetry = {
                val currentProfile = profileForRun() ?: return@AiPromptComposer
                state.setAiProviderApiKey(currentProfile.id, keyDraft)
                when (val result = runtime.retry(tab.id, currentProfile, keyDraft)) {
                    is AiStartResult.Started -> error = null
                    is AiStartResult.Rejected -> error = result.message
                }
            },
        )
    }
}

// Shared look for every collapsible section in this sidebar (Provider, Actions, and each run's
// Investigation trace) - a rounded, padded hover target rather than bare clickable text, so the
// toggle reads consistently and has a comfortable click area everywhere it appears.
@Composable
private fun AiCollapsibleHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth().clip(CORNER_SM),
        onClick = onToggle,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(if (expanded) "▾" else "▸", color = colors.td, fontSize = 11.sp)
            AppText(
                title,
                color = colors.td,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke(this)
        }
    }
}

// One segment of the Actions/Provider accordion row - same visual language as
// AiCollapsibleHeader (rounded hover target) but half-width and highlighted while selected, so
// both segments stay the same height regardless of what either section's content holds.
@Composable
private fun AiSectionTab(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = tc()
    HoverBox(
        modifier = modifier.clip(CORNER_SM)
            .background(if (expanded) colors.abg else Color.Transparent, CORNER_SM)
            .border(1.dp, if (expanded) colors.ac.copy(.4f) else colors.br, CORNER_SM),
        onClick = onToggle,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(if (expanded) "▾" else "▸", color = if (expanded) colors.ac else colors.td, fontSize = 11.sp)
            AppText(
                title,
                color = if (expanded) colors.ac else colors.td,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun AiQuickActionsContent(tab: LogTab, onAction: (AiQuickAction) -> Unit) {
    val colors = tc()
    val hasSelectedLine = tab.selected.any { it in tab.rmap }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(AiQuickAction.SELECTED_ERROR, AiQuickAction.ROOT_CAUSE).forEach { action ->
                AppButton(
                    action.label,
                    onClick = { onAction(action) },
                    enabled = hasSelectedLine,
                    variant = ButtonVariant.Ghost,
                    modifier = Modifier.height(25.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(AiQuickAction.TIMELINE, AiQuickAction.ISSUE_INVESTIGATION).forEach { action ->
                AppButton(
                    action.label,
                    onClick = { onAction(action) },
                    enabled = !action.requiresLine || hasSelectedLine,
                    variant = ButtonVariant.Ghost,
                    modifier = Modifier.height(25.dp),
                )
            }
        }
        if (!hasSelectedLine) {
            AppText("Select a log line to enable line-based investigations.", color = colors.td, fontSize = 10.sp)
        }
    }
}

@Composable
private fun AiProviderControls(
    state: AppState,
    profile: com.openlog.model.AiProviderProfile,
    model: String,
    onModelChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    discovery: ModelDiscoveryResult?,
    onDiscoverModels: () -> Unit,
    onAddProvider: () -> Unit,
) {
    val colors = tc()
    val profiles = normalizeAiProviderProfiles(state.settings.aiProviderProfiles)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            AiProviderDropdown(
                profiles = profiles,
                selected = profile,
                onSelect = { id -> state.selectAiProviderProfile(id) },
                modifier = Modifier.weight(1f),
            )
            // AppButton's fixed horizontal/vertical padding is sized for pill-shaped text labels,
            // not a single glyph in a 24dp square - it squeezed the "+" off-center. HoverBox with
            // no padding centers it exactly like CloseButton's "×" does.
            HoverBox(
                modifier = Modifier.size(24.dp).clip(CORNER_SM).border(1.dp, colors.br, CORNER_SM),
                onClick = onAddProvider,
            ) {
                AppText("+", color = colors.td, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
        AppText(profile.baseUrl, color = colors.td, fontSize = 10.sp, maxLines = 1)
        val endpointHost = runCatching { java.net.URI(profile.baseUrl).host.orEmpty() }.getOrDefault("")
        if (!isLoopbackHost(endpointHost) && !profile.remoteDisclosureAcknowledged) {
            Box(Modifier.fillMaxWidth().background(DANGER_RED.copy(.10f), CORNER_SM).padding(7.dp)) {
                AppText(
                    "Remote provider is blocked until you acknowledge the data disclosure in Settings.",
                    color = DANGER_RED,
                    fontSize = 10.sp,
                    maxLines = 3,
                )
            }
        }
        AppText("Model", color = colors.td, fontSize = 10.sp)
        AiModelDropdown(
            model = model,
            discovery = discovery,
            onDiscoverModels = onDiscoverModels,
            onPickModel = onModelChange,
        )
        AppText("API key (this launch only)", color = colors.td, fontSize = 10.sp)
        InlineField(
            value = apiKey,
            onValue = onApiKeyChange,
            placeholder = "Optional for LM Studio",
            modifier = Modifier.fillMaxWidth(),
            fontSize = 11.sp,
            visualTransformation = PasswordVisualTransformation(),
        )
    }
}

// Same dropdown treatment as AiModelDropdown/CrashCategoryDropdown, for choosing which provider
// profile is active - consistent with "model choosing" now that both use the same interaction.
@Composable
private fun AiProviderDropdown(
    profiles: List<com.openlog.model.AiProviderProfile>,
    selected: com.openlog.model.AiProviderProfile,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    var fieldWidth by remember { mutableStateOf(0.dp) }
    var suppressToggleUntilMs by remember { mutableStateOf(0L) }
    Box(
        modifier.onGloballyPositioned { coords ->
            fieldWidth = with(density) { coords.size.width.toDp() }
        },
    ) {
        HoverBox(
            modifier = Modifier.fillMaxWidth().height(28.dp)
                .clip(CORNER_SM)
                .background(tc.p2, CORNER_SM)
                .border(1.dp, tc.br, CORNER_SM),
            onClick = { if (System.currentTimeMillis() >= suppressToggleUntilMs) open = !open },
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AppText(
                    selected.displayName,
                    color = tc.tx,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AppText(if (open) "▲" else "▼", color = tc.td, fontSize = 9.sp)
            }
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { 32.dp.roundToPx() }),
                onDismissRequest = {
                    open = false
                    suppressToggleUntilMs = System.currentTimeMillis() + 200
                },
                properties = PopupProperties(focusable = false),
            ) {
                // A Popup's content still registers with the ambient SelectionContainer (Popup
                // isn't a selection boundary), but its LayoutCoordinates live in a separate root -
                // starting a text-selection drag anywhere in the panel then throws Compose
                // Desktop's "layouts are not part of the same hierarchy" when it tries to compare
                // this popup's selectable text against the panel's. DisableSelection opts this
                // subtree out of that registrar entirely.
                DisableSelection {
                    Column(
                        Modifier.width(fieldWidth)
                            .background(tc.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc.br, RoundedCornerShape(8.dp))
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        profiles.forEach { item ->
                            val active = item.id == selected.id
                            HoverBox(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)),
                                baseBg = if (active) tc.abg else Color.Transparent,
                                onClick = { open = false; onSelect(item.id) },
                            ) {
                                AppText(
                                    item.displayName,
                                    color = if (active) tc.ac else tc.tx,
                                    fontSize = 11.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Mirrors FilterPanel's CrashCategoryDropdown: a clickable field showing the current value that
// opens a themed option list on click, rather than the app's earlier free-text-field-plus-chips
// layout. Manual entry remains possible (the profile's manually entered model must stay usable
// even when discovery is unavailable) via the field at the bottom of the popup.
@Composable
private fun AiModelDropdown(
    model: String,
    discovery: ModelDiscoveryResult?,
    onDiscoverModels: () -> Unit,
    onPickModel: (String) -> Unit,
) {
    val tc = tc()
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    var fieldWidth by remember { mutableStateOf(0.dp) }
    // See CrashCategoryDropdown for why this guard is needed: the Popup's dismissOnClickOutside
    // also fires for a click back on the field itself, which would otherwise race the field's own
    // toggle and net out to "stayed open" instead of closing.
    var suppressToggleUntilMs by remember { mutableStateOf(0L) }
    var manualEntry by remember(open) { mutableStateOf(model) }
    Box(
        Modifier.fillMaxWidth().onGloballyPositioned { coords ->
            fieldWidth = with(density) { coords.size.width.toDp() }
        },
    ) {
        HoverBox(
            modifier = Modifier.fillMaxWidth().height(28.dp)
                .clip(CORNER_SM)
                .background(tc.p2, CORNER_SM)
                .border(1.dp, tc.br, CORNER_SM),
            onClick = {
                if (System.currentTimeMillis() >= suppressToggleUntilMs) {
                    val opening = !open
                    open = opening
                    if (opening && discovery == null) onDiscoverModels()
                }
            },
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AppText(
                    model.ifBlank { "Choose a model" },
                    color = if (model.isBlank()) tc.td else tc.tx,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AppText(if (open) "▲" else "▼", color = tc.td, fontSize = 9.sp)
            }
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { 32.dp.roundToPx() }),
                onDismissRequest = {
                    open = false
                    suppressToggleUntilMs = System.currentTimeMillis() + 200
                },
                properties = PopupProperties(focusable = false),
            ) {
                // See the matching comment in AiProviderDropdown: Popup content stays registered
                // with the ambient SelectionContainer unless explicitly opted out, which crashes a
                // text-selection drag anywhere in the panel once this popup is open.
                DisableSelection {
                    Column(
                        Modifier.width(fieldWidth)
                            .background(tc.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc.br, RoundedCornerShape(8.dp))
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        HoverBox(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)),
                            onClick = onDiscoverModels,
                        ) {
                            AppText(
                                "↻ Find models",
                                color = tc.ac,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                        when (discovery) {
                            is ModelDiscoveryResult.Available -> if (discovery.models.isNotEmpty()) {
                                discovery.models.forEach { item ->
                                    val active = item.id == model
                                    HoverBox(
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)),
                                        baseBg = if (active) tc.abg else Color.Transparent,
                                        onClick = { open = false; onPickModel(item.id) },
                                    ) {
                                        AppText(
                                            item.displayName,
                                            color = if (active) tc.ac else tc.tx,
                                            fontSize = 11.sp,
                                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            } else {
                                AppText(
                                    "No models were returned.",
                                    color = tc.td,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(8.dp),
                                )
                            }
                            is ModelDiscoveryResult.Unavailable -> AppText(
                                discovery.message,
                                color = tc.td,
                                fontSize = 10.sp,
                                maxLines = 3,
                                modifier = Modifier.padding(8.dp),
                            )
                            null -> AppText("Finding models…", color = tc.td, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
                        }
                        Divider()
                        Row(
                            Modifier.fillMaxWidth().padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            InlineField(manualEntry, { manualEntry = it }, "Enter a model id", Modifier.weight(1f), fontSize = 11.sp)
                            AppButton("Use", onClick = { open = false; onPickModel(manualEntry.trim()) }, modifier = Modifier.height(26.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiRunCard(
    run: AiRun,
    events: List<AiRunEvent>,
    onResolveConfirmation: (AiRun, AiToolConfirmation, Boolean) -> Boolean,
    onNavigateEvidence: (AiEvidence) -> Unit,
    onCopyText: (String) -> Unit,
) {
    val colors = tc()
    val assistantText = events.filterIsInstance<AiRunEvent.AssistantDelta>().joinToString(separator = "") { it.text }
    val traceEvents = events.filter { it !is AiRunEvent.AssistantDelta && it !is AiRunEvent.Usage }
    val usage = events.filterIsInstance<AiRunEvent.Usage>().lastOrNull()
    val isTerminal = events.any { it is AiRunEvent.Done || it is AiRunEvent.Error || it == AiRunEvent.Cancelled }

    // Closed by default - the trace is available on demand, but doesn't compete with the final
    // answer for attention while it's still visible.
    var expanded by remember(run.id) { mutableStateOf(false) }

    val traceScroll = rememberScrollState()
    // Same reasoning as the outer transcript scroll: only auto-follow while this run is still
    // adding steps, so reopening a finished run's trace later doesn't jump it to the bottom.
    LaunchedEffect(traceScroll, isTerminal) {
        if (isTerminal) return@LaunchedEffect
        snapshotFlow { traceScroll.maxValue }.collect { max -> traceScroll.scrollTo(max) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (run.userPrompt.isNotBlank()) {
            AiBubble("You", colors.ac.copy(.13f)) {
                AppText(run.userPrompt, color = colors.tx, fontSize = 12.sp, maxLines = Int.MAX_VALUE)
                AppText(clockTimeLabel(run.sentAt), color = colors.td, fontSize = 9.sp)
            }
        }
        if (traceEvents.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                AiCollapsibleHeader(
                    "Investigation (${traceEvents.size} step${if (traceEvents.size == 1) "" else "s"})",
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                )
                if (expanded) {
                    Box(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                        Column(
                            Modifier.verticalScroll(traceScroll).padding(end = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            traceEvents.forEach { event ->
                                when (event) {
                                    is AiRunEvent.ConfirmationRequired -> AiConfirmationCard(run, event.confirmation, onResolveConfirmation)
                                    is AiRunEvent.ToolCompleted -> {
                                        AiTraceRow(event)
                                        event.evidence.forEach { evidence -> AiEvidenceCard(evidence, onNavigateEvidence) }
                                    }
                                    else -> AiTraceRow(event)
                                }
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(traceScroll),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            style = appScrollbarStyle(colors),
                        )
                    }
                }
            }
        }
        if (assistantText.isNotBlank()) {
            AiBubble("Assistant", colors.p2, onCopy = { onCopyText(assistantText) }) {
                // The renderer parses asynchronously. AiSidebarRuntime batches updates, so its
                // content only changes at a modest cadence while a provider is streaming.
                // retainState keeps the previously rendered Markdown visible while a reparse is in
                // flight - without it, each update briefly shows the loading placeholder, which
                // reads as the whole card flickering right as the final answer is written.
                val markdownState = rememberMarkdownState(content = assistantText, retainState = true)
                Markdown(
                    markdownState,
                    colors = aiMarkdownColors(colors),
                    typography = aiMarkdownTypography(colors),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        AiRunTimingRow(run, isTerminal, usage)
    }
}

@Composable
private fun AiRunTimingRow(run: AiRun, isTerminal: Boolean, usage: AiRunEvent.Usage?) {
    val colors = tc()
    // Ticks once a second while active so "running Xs" advances live; freezes once terminal since
    // run.completedAt is then fixed and no further ticks are scheduled.
    var liveNow by remember(run.id) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(run.id, isTerminal) {
        while (!isTerminal) {
            delay(1000)
            liveNow = System.currentTimeMillis()
        }
    }
    val firstResponseAt = run.firstResponseAt
    val elapsedEnd = run.completedAt ?: liveNow
    val parts = buildList {
        add("Sent ${clockTimeLabel(run.sentAt)}")
        firstResponseAt?.let { add("first reply after ${durationLabel(it - run.sentAt)}") }
        val elapsed = durationLabel(elapsedEnd - run.sentAt)
        add(if (run.completedAt != null) "took $elapsed" else "running $elapsed")
    }
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        AppText(parts.joinToString(" · "), color = colors.td, fontSize = 9.sp, maxLines = 2)
        usage?.let {
            AppText(
                "Tokens: ${it.promptTokens} prompt + ${it.completionTokens} completion = ${it.totalTokens} total",
                color = colors.td,
                fontSize = 9.sp,
            )
        }
    }
}

private fun clockTimeLabel(epochMs: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(epochMs))

private fun durationLabel(ms: Long): String = when {
    ms < 1000 -> "${ms.coerceAtLeast(0)}ms"
    ms < 60_000 -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}

@Composable
private fun AiEvidenceCard(evidence: AiEvidence, onNavigate: (AiEvidence) -> Unit) {
    val colors = tc()
    val label = when (evidence) {
        is AiEvidence.LogRows -> {
            val preview = evidence.lineIds.take(3).joinToString(", ")
            "Evidence: log line${if (evidence.lineIds.size == 1) "" else "s"} $preview"
        }
        is AiEvidence.Source -> "Evidence: ${evidence.methodName} (${evidence.filePath.substringAfterLast('/')}:${evidence.callLine})"
        is AiEvidence.Note -> "Evidence: note ${evidence.blockId}"
    }
    Box(
        Modifier.fillMaxWidth().background(colors.ac.copy(.08f), CORNER_SM)
            .border(1.dp, colors.ac.copy(.35f), CORNER_SM).padding(5.dp),
    ) {
        AppButton(label, onClick = { onNavigate(evidence) }, variant = ButtonVariant.Ghost, modifier = Modifier.fillMaxWidth().height(25.dp))
    }
}

// The renderer's own defaults pull from MaterialTheme.colorScheme/typography, which resolve to
// Material3's stock light baseline (57sp display headings) since this app never installs a
// MaterialTheme - it has its own ThemeColors/tc() system instead. Map assistant Markdown onto
// that same compact scale so headings and body text read like the rest of the sidebar.
@Composable
private fun aiMarkdownColors(colors: ThemeColors) = markdownColor(
    text = colors.tx,
    codeBackground = colors.p2,
    inlineCodeBackground = colors.p2,
    dividerColor = colors.br,
    tableBackground = colors.p2,
)

@Composable
private fun aiMarkdownTypography(colors: ThemeColors): MarkdownTypography {
    val body = TextStyle(color = colors.tx, fontSize = 12.sp, fontFamily = UI)
    val code = TextStyle(color = colors.tx, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    val heading = body.copy(fontWeight = FontWeight.SemiBold)
    return markdownTypography(
        h1 = heading.copy(fontSize = 15.sp),
        h2 = heading.copy(fontSize = 14.sp),
        h3 = heading.copy(fontSize = 13.sp),
        h4 = heading.copy(fontSize = 12.sp),
        h5 = heading.copy(fontSize = 12.sp),
        h6 = heading.copy(fontSize = 12.sp),
        text = body,
        code = code,
        inlineCode = code,
        quote = body.copy(fontStyle = FontStyle.Italic),
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        table = body,
    )
}

@Composable
private fun AiBubble(
    label: String,
    background: Color,
    onCopy: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = tc()
    Column(
        Modifier.fillMaxWidth().background(background, CORNER_MD).border(0.5.dp, colors.br, CORNER_MD).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(label, color = colors.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            onCopy?.let { copy ->
                HoverBox(modifier = Modifier.size(20.dp).clip(CORNER_SM), onClick = copy) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "Copy",
                        tint = colors.td,
                        modifier = Modifier.size(13.dp).align(Alignment.Center),
                    )
                }
            }
        }
        content()
    }
}

@Composable
private fun AiTraceRow(event: AiRunEvent) {
    val colors = tc()
    val text = when (event) {
        is AiRunEvent.Status -> event.text
        is AiRunEvent.ToolRequested -> "Using tool: ${event.call.name}"
        is AiRunEvent.ToolCompleted -> buildString {
            append("Tool finished: ${event.call.name}")
            if (event.resultTruncated) append(" (result truncated)")
        }
        is AiRunEvent.Error -> event.message
        AiRunEvent.Cancelled -> "Request stopped."
        AiRunEvent.Done -> "Done"
        else -> return
    }
    val color = if (event is AiRunEvent.Error) DANGER_RED else colors.td
    AppText(text, color = color, fontSize = 10.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 3.dp))
}

@Composable
private fun AiConfirmationCard(
    run: AiRun,
    confirmation: AiToolConfirmation,
    onResolve: (AiRun, AiToolConfirmation, Boolean) -> Boolean,
) {
    val colors = tc()
    Column(
        Modifier.fillMaxWidth().background(DANGER_RED.copy(.10f), CORNER_SM)
            .border(1.dp, DANGER_RED.copy(.45f), CORNER_SM).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        AppText("Confirmation required", color = colors.tx, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        AppText(confirmation.description, color = colors.td, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppButton("Allow", onClick = { onResolve(run, confirmation, true) }, variant = ButtonVariant.Primary)
            AppButton("Deny", onClick = { onResolve(run, confirmation, false) }, variant = ButtonVariant.Secondary)
        }
    }
}

@Composable
private fun AiPromptComposer(
    prompt: String,
    running: AiRun?,
    retryAvailable: Boolean,
    maxHeightDp: Dp,
    onPromptChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = tc()
    val density = LocalDensity.current
    val promptScroll = rememberScrollState()
    // Measuring the *Box's own* resolved height (as a previous version of this did) and feeding
    // that back into the scrollbar's height is a self-sustaining loop: once the scrollbar has any
    // height, it becomes one of the Box's children too, so the Box's own size includes it - and
    // next frame the (now-including-scrollbar) size gets fed back in again, permanently pinning
    // the box at whatever size it first happened to settle on, regardless of how little text
    // there is. Measuring the text field's own size instead has no such loop: a Box's non-
    // matchParentSize children are measured independently of each other, so the text field's
    // resolved size never depends on what the scrollbar was in a previous frame.
    var textFieldHeightPx by remember { mutableStateOf(0) }
    Column(
        Modifier.fillMaxWidth().background(colors.p2).border(BorderStroke(1.dp, colors.br)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 54.dp, max = maxHeightDp)) {
            androidx.compose.foundation.text.BasicTextField(
                value = prompt,
                onValueChange = onPromptChange,
                textStyle = TextStyle(color = colors.tx, fontSize = 12.sp),
                cursorBrush = SolidColor(colors.ac),
                modifier = Modifier.fillMaxWidth()
                    .onSizeChanged { textFieldHeightPx = it.height }
                    .background(colors.bg, CORNER_SM).border(1.dp, colors.br, CORNER_SM)
                    .verticalScroll(promptScroll)
                    .padding(start = 7.dp, top = 7.dp, bottom = 7.dp, end = 14.dp),
                decorationBox = { inner ->
                    if (prompt.isBlank()) AppText("Ask about this log tab…", color = colors.td, fontSize = 12.sp)
                    inner()
                },
            )
            if (textFieldHeightPx > 0) {
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(promptScroll),
                    modifier = Modifier.align(Alignment.CenterEnd)
                        .height(with(density) { textFieldHeightPx.toDp() })
                        .padding(vertical = 2.dp),
                    style = appScrollbarStyle(colors),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (running == null) {
                AppButton("Send", onClick = onSend, variant = ButtonVariant.Primary, enabled = prompt.isNotBlank())
                if (retryAvailable) AppButton("Retry", onClick = onRetry, variant = ButtonVariant.Secondary)
                AppText("Ctrl/Cmd + Enter to send", color = colors.td, fontSize = 10.sp)
            } else {
                AppButton("Stop", onClick = onStop, variant = ButtonVariant.Secondary, isDanger = true)
                AppText("Esc to stop", color = colors.td, fontSize = 10.sp)
            }
        }
    }
}
