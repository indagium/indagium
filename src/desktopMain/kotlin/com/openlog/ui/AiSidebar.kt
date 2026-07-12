@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.openlog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
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
import kotlinx.coroutines.launch

/** A session-only choice for the existing right sidebar. It is intentionally absent from autosave. */
internal enum class RightSidebarTab { NOTES, AI }

/**
 * Keeps the existing Notes panel intact while making AI a sibling view in the same resizable
 * sidebar. Both modes use the same focus target, so F6/Shift+F6 panel traversal remains stable.
 */
@Composable
internal fun RightSidebarPanel(
    state: AppState,
    tab: LogTab,
    width: Float,
    focusRequester: FocusRequester,
    onPanelFocusChanged: (Boolean) -> Unit,
    notesContent: @Composable () -> Unit,
) {
    Column(Modifier.width(width.dp).fillMaxHeight().background(tc().p)) {
        RightSidebarSelector(
            selected = state.rightSidebarTab,
            onSelect = { state.rightSidebarTab = it },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.rightSidebarTab == RightSidebarTab.NOTES) {
                notesContent()
            } else {
                AiSidebarPanel(
                    state = state,
                    tab = tab,
                    focusRequester = focusRequester,
                    onPanelFocusChanged = onPanelFocusChanged,
                )
            }
        }
    }
}

@Composable
private fun RightSidebarSelector(selected: RightSidebarTab, onSelect: (RightSidebarTab) -> Unit) {
    val colors = tc()
    Row(
        Modifier.fillMaxWidth().height(32.dp).background(colors.p2)
            .border(BorderStroke(1.dp, colors.br)).padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppButton(
            "Notes",
            onClick = { onSelect(RightSidebarTab.NOTES) },
            variant = if (selected == RightSidebarTab.NOTES) ButtonVariant.Primary else ButtonVariant.Secondary,
            modifier = Modifier.height(24.dp),
        )
        AppButton(
            "AI",
            onClick = { onSelect(RightSidebarTab.AI) },
            variant = if (selected == RightSidebarTab.AI) ButtonVariant.Primary else ButtonVariant.Secondary,
            modifier = Modifier.height(24.dp),
        )
        AppText(
            if (selected == RightSidebarTab.AI) "AI assistant" else "Log notes",
            color = colors.td,
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 3.dp),
        )
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

    Column(
        Modifier.fillMaxSize().background(colors.p)
            .border(BorderStroke(1.dp, if (panelFocused && state.keyboardFocusVisible) colors.ac else colors.br))
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
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AiProviderControls(
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
                onPickModel = { modelDraft = it },
                onOpenSettings = { state.settingsOpen = true },
            )
            AiQuickActions(
                tab = tab,
                onAction = { action -> state.requestAiInvestigation(tab.id, action) },
            )
            val lastEvent = session.runs.lastOrNull()?.history?.lastOrNull()
            val connectionState = when {
                session.activeRun != null -> "Generating with ${profile.displayName}…"
                lastEvent is AiRunEvent.Error -> "Last provider request failed"
                lastEvent == AiRunEvent.Cancelled -> "Last request was stopped"
                else -> "Ready"
            }
            AppText("Connection: $connectionState", color = colors.td, fontSize = 10.sp)
            error?.let { AppText(it, color = DANGER_RED, fontSize = 11.sp) }
            if (session.runs.isEmpty()) {
                AppText(
                    "Ask about the active log tab. The assistant can use the same log, filter, source, and notes tools as MCP.",
                    color = colors.td,
                    fontSize = 11.sp,
                    maxLines = 4,
                )
            }
            session.runs.forEach { run -> AiRunCard(run, runtime::resolveConfirmation, state::navigateAiEvidence) }
        }
        AiPromptComposer(
            prompt = prompt,
            running = session.activeRun,
            retryAvailable = session.activeRun == null && session.lastPrompt != null,
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

@Composable
private fun AiQuickActions(tab: LogTab, onAction: (AiQuickAction) -> Unit) {
    val colors = tc()
    val hasSelectedLine = tab.selected.any { it in tab.rmap }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppText("Quick investigations", color = colors.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(AiQuickAction.SELECTED_ERROR, AiQuickAction.ROOT_CAUSE, AiQuickAction.TIMELINE).forEach { action ->
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
            listOf(AiQuickAction.FILTERED_RESULT, AiQuickAction.MAPPED_SOURCE).forEach { action ->
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
    onPickModel: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = tc()
    val profiles = normalizeAiProviderProfiles(state.settings.aiProviderProfiles)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        AppText("Provider", color = colors.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            profiles.forEach { item ->
                AppButton(
                    item.displayName,
                    onClick = { state.selectAiProviderProfile(item.id) },
                    variant = if (item.id == profile.id) ButtonVariant.Primary else ButtonVariant.Secondary,
                    modifier = Modifier.heightIn(min = 26.dp).widthIn(max = 150.dp),
                )
            }
            AppButton("Settings", onClick = onOpenSettings, variant = ButtonVariant.Ghost, modifier = Modifier.height(26.dp))
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
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            InlineField(
                value = model,
                onValue = onModelChange,
                placeholder = "Enter a model id",
                modifier = Modifier.weight(1f),
                fontSize = 11.sp,
            )
            AppButton("Find", onClick = onDiscoverModels, modifier = Modifier.height(27.dp))
        }
        when (discovery) {
            is ModelDiscoveryResult.Available -> if (discovery.models.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    discovery.models.take(4).forEach { item ->
                        AppButton(item.displayName, onClick = { onPickModel(item.id) }, variant = ButtonVariant.Ghost)
                    }
                }
            } else {
                AppText("No models were returned. You can enter a model id manually.", color = colors.td, fontSize = 10.sp)
            }
            is ModelDiscoveryResult.Unavailable -> AppText(discovery.message, color = colors.td, fontSize = 10.sp, maxLines = 2)
            null -> Unit
        }
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

@Composable
private fun AiRunCard(
    run: AiRun,
    onResolveConfirmation: (AiRun, AiToolConfirmation, Boolean) -> Boolean,
    onNavigateEvidence: (AiEvidence) -> Unit,
) {
    val colors = tc()
    val events = run.history
    val assistantText = events.filterIsInstance<AiRunEvent.AssistantDelta>().joinToString(separator = "") { it.text }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (run.userPrompt.isNotBlank()) {
            AiBubble("You", colors.ac.copy(.13f)) { AppText(run.userPrompt, color = colors.tx, fontSize = 12.sp) }
        }
        if (assistantText.isNotBlank()) {
            AiBubble("Assistant", colors.p2) {
                // The renderer parses asynchronously. AiSidebarRuntime batches updates, so its
                // content only changes at a modest cadence while a provider is streaming.
                Markdown(assistantText, modifier = Modifier.fillMaxWidth())
            }
        }
        events.filterNot { it is AiRunEvent.AssistantDelta }.forEach { event ->
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

@Composable
private fun AiBubble(label: String, background: Color, content: @Composable () -> Unit) {
    val colors = tc()
    Column(
        Modifier.fillMaxWidth().background(background, CORNER_MD).border(0.5.dp, colors.br, CORNER_MD).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppText(label, color = colors.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
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
    onPromptChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = tc()
    Column(
        Modifier.fillMaxWidth().background(colors.p2).border(BorderStroke(1.dp, colors.br)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = prompt,
            onValueChange = onPromptChange,
            textStyle = TextStyle(color = colors.tx, fontSize = 12.sp),
            cursorBrush = SolidColor(colors.ac),
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp, max = 120.dp)
                .background(colors.bg, CORNER_SM).border(1.dp, colors.br, CORNER_SM).padding(7.dp),
            decorationBox = { inner ->
                if (prompt.isBlank()) AppText("Ask about this log tab…", color = colors.td, fontSize = 12.sp)
                inner()
            },
        )
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
