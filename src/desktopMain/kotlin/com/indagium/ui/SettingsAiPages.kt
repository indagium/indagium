package com.indagium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.ai.AccountCliCheck
import com.indagium.ai.AnthropicMessagesProvider
import com.indagium.ai.CustomAiCommand
import com.indagium.ai.LocalAccountCli
import com.indagium.ai.ModelDiscoveryResult
import com.indagium.ai.OpenAiCompatibleProvider
import com.indagium.ai.checkAccountCli
import com.indagium.ai.isLoopbackHost
import com.indagium.ai.normalizeAiProviderProfiles
import com.indagium.model.AiProviderKind
import com.indagium.model.VoiceRecognitionEngine
import com.indagium.model.defaultAiProviderProfile
import com.indagium.voice.VoiceLanguageCatalog
import com.indagium.voice.VoiceLanguageOption
import com.indagium.voice.VoiceModelCatalog
import com.indagium.voice.VoiceModelCheckState
import com.indagium.voice.VoiceModelDescriptor
import com.indagium.voice.VoiceModelInstallResult
import com.indagium.voice.VoiceModelInstaller
import com.indagium.voice.VoiceRecognitionEngines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Settings › AI: Providers, Commands and Voice input, as groups of rows. Providers keeps its own unsaved-changes guard exactly as it was:
// the draft, Save profile, the prompt that stops a switch, add or remove of a provider while the draft is dirty, the SettingsDraftGuard it
// publishes for the dialog (which stops a page switch or Done), and the API key that is stored per keystroke for this session only.

// The row descriptions the search index shares (SettingsSearchIndex.kt), so a result reads exactly like the row it points at.
internal const val AI_PROFILE_HINT = "Pick the profile to edit. The AI panel uses the profile that is selected."
internal const val AI_PROVIDER_TYPE_HINT = "An API provider (OpenAI, Anthropic or OpenAI-compatible) or a local Codex or Claude Code account."
internal const val AI_PROFILE_NAME_HINT = "How this profile is called in the AI panel's provider picker."
internal const val AI_ENDPOINT_HINT = "Base URL of an HTTP AI provider."
internal const val AI_CLI_EXECUTABLE_HINT = "Optional. The Codex or Claude Code CLI of an account that is already signed in on this computer."
internal const val AI_MODEL_HINT = "Optional. The model this profile asks for. Open the list to detect what the provider offers."
internal const val AI_REASONING_EFFORT_HINT = "How much the model thinks before it answers."
internal const val AI_API_KEY_HINT = "For this session only; it is never saved."
internal const val AI_MAX_TOOL_CALLS_HINT = "How many tool calls one request may use before it stops. Notes reads and writes are unlimited."
internal const val AI_MAX_TOOL_CALLS_DETAIL = "A multi-step investigation (filtering, reading lines, then writing a note) can take many tool calls, " +
    "especially with a smaller local model. Raise this if a request stops with “MCP tool-call budget” before it finishes. " +
    "Notes reads and writes are unlimited; only analysis and operational calls consume this budget."
internal const val VOICE_TRANSLATE_HINT = "Writes what you dictate as English text, whatever language you speak. You can edit it before sending."
internal const val VOICE_LANGUAGE_HINT =
    "Automatic detects the spoken language. Choose Ukrainian for short Ukrainian phrases; it avoids an English-recognition bias."
internal const val VOICE_LANGUAGE_WINDOWS_HINT =
    "Windows Speech needs a matching installed legacy recogniser. For Ukrainian or any unavailable language, choose Local Whisper."
internal const val VOICE_MODEL_HINT = "Size of the local Whisper model. Downloads start only when you choose Download; speech stays local."

private val PROVIDER_FIELD_WIDTH = SETTINGS_FIELD_LONG
private val LANGUAGE_CODE_FIELD_WIDTH = SETTINGS_FIELD_SHORT
private const val VOICE_MODEL_POLL_MS = 5_000L
private const val LANGUAGE_CODE_MAX = 3
private const val ADD_LANGUAGE_OPTION = "add-language"

/** Test tags of the AI pages' controls that have no tag of their own. */
internal object AiPageTags {
    const val PROVIDER_PICKER = "ai-provider-picker"
    const val ADD_PROVIDER = "ai-provider-add"
    const val PROVIDER_TYPE = "ai-provider-type"
    const val PROFILE_NAME = "ai-profile-name"
    const val ENDPOINT = "ai-provider-endpoint"
    const val API_KEY = "ai-provider-api-key"
    const val SAVE_PROFILE = "ai-provider-save"
    const val REMOVE_PROVIDER = "ai-provider-remove"
    const val MAX_TOOL_CALLS = "ai-max-tool-calls"
    const val ADD_COMMAND = "ai-command-add"
    const val VOICE_ENGINE = "voice-engine"
    const val VOICE_TRANSLATE = "voice-translate"
    const val VOICE_LANGUAGE = "voice-language"
    const val VOICE_MODEL = "voice-model"

    fun commandEdit(name: String): String = "ai-command-edit-$name"

    fun commandDelete(name: String): String = "ai-command-delete-$name"
}

// ── Providers ─────────────────────────────────────────────────────────

@Composable
internal fun AiProviderSettingsSection(state: AppState, onGuardChange: (SettingsDraftGuard) -> Unit = {}, reclaimFocus: () -> Unit = {}) {
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
    var accountCliCheck by remember(profile.id, kind) { mutableStateOf<AccountCliCheck?>(null) }
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
    SideEffect { onGuardChange(SettingsDraftGuard(isDirty, profile.displayName) { state.updateAiProviderProfile(draft) }) }

    // Any navigation away from a dirty draft (switching provider, adding a new one, or removing a
    // *different* profile) goes through this instead of acting immediately, so edits are never
    // silently lost. Switching settings section or closing the dialog is guarded the same way, one
    // level up in SettingsDialog, via the published SettingsDraftGuard above.
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

    // Not gated by provider kind: any provider whose discovered model reports reasoning
    // efforts gets the dropdown, so Codex, Anthropic, and OpenAI-compatible endpoints
    // (including reasoning-capable local models served via LM Studio) all work the same way.
    val discoveredModel = (modelDiscovery as? ModelDiscoveryResult.Available)
        ?.models
        ?.firstOrNull { it.id == model }
    val reasoningEfforts = discoveredModel?.reasoningEfforts.orEmpty()
    val showReasoningDropdown = discoveredModel != null && reasoningEfforts.isNotEmpty()

    SettingsGroup(
        "Provider profile",
        trailing = {
            SettingsButton(
                "Add profile",
                onClick = { navigateOrConfirm { editingProfileId = state.addAiProviderProfile().id } },
                modifier = Modifier.testTag(AiPageTags.ADD_PROVIDER),
            )
        },
    ) {
        // Same dropdown treatment as the AI panel's own provider switcher (AiProviderControls in AiSidebar.kt), for a consistent look and so
        // both places share one battle-tested selection path instead of a second, subtly different one. Adding a profile is the group's
        // header button, like every list.
        SettingsRow("Profile", AI_PROFILE_HINT, anchor = "Providers") {
            AiProviderDropdown(
                profiles = profiles,
                selected = profile,
                onSelect = { id ->
                    navigateOrConfirm {
                        state.selectAiProviderProfile(id)
                        editingProfileId = id
                    }
                },
                modifier = Modifier.width(settingsFieldWidth(PROVIDER_FIELD_WIDTH)).testTag(AiPageTags.PROVIDER_PICKER),
                settingsStyle = true,
                onInteraction = reclaimFocus,
            )
        }
        SettingsRow("Provider type", AI_PROVIDER_TYPE_HINT) {
            SettingsDropdown(
                options = AiProviderKind.entries,
                selected = kind,
                label = { it.label },
                onSelect = { selectedKind ->
                    if (selectedKind != kind) {
                        val preset = defaultAiProviderProfile(selectedKind)
                        kind = selectedKind
                        name = preset.displayName
                        endpoint = preset.baseUrl
                        model = preset.model
                        executablePath = preset.executablePath
                        reasoningEffort = preset.reasoningEffort
                        ackState = endpoint.trim() to false
                    }
                },
                testTag = AiPageTags.PROVIDER_TYPE,
                minWidth = PROVIDER_FIELD_WIDTH,
                optionTag = { "${AiPageTags.PROVIDER_TYPE}-${it.name}" },
                onInteraction = reclaimFocus,
            )
        }
        SettingsRow("Profile name", AI_PROFILE_NAME_HINT) {
            SettingsTextField(
                name,
                { name = it },
                Modifier.width(settingsFieldWidth(PROVIDER_FIELD_WIDTH)),
                placeholder = "LM Studio (local)",
                testTag = AiPageTags.PROFILE_NAME,
            )
        }
        if (kind.usesHttpEndpoint) {
            SettingsRow("Endpoint", AI_ENDPOINT_HINT) {
                SettingsTextField(
                    endpoint,
                    { endpoint = it },
                    Modifier.width(settingsFieldWidth(PROVIDER_FIELD_WIDTH)),
                    placeholder = "https://api.example.com/v1",
                    testTag = AiPageTags.ENDPOINT,
                )
            }
        } else {
            SettingsStackedRow(
                "CLI executable",
                AI_CLI_EXECUTABLE_HINT,
                detail = AI_CLI_EXECUTABLE_HINT + "\n" + if (kind == AiProviderKind.CODEX_ACCOUNT) {
                    "Choose a CLI executable, not an app bundle. On macOS Detect can use ChatGPT's bundled Codex CLI."
                } else {
                    "Choose the Claude Code CLI executable. Claude desktop app bundles cannot run managed panel requests."
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SettingsTextField(
                            executablePath,
                            { executablePath = it },
                            Modifier.weight(1f),
                            placeholder = if (kind == AiProviderKind.CODEX_ACCOUNT) "codex" else "claude",
                        )
                        SettingsButton(
                            "Detect",
                            onClick = {
                                val detected = LocalAccountCli.detectExecutable(kind)
                                if (detected != null) {
                                    executablePath = detected
                                    accountCliCheck = AccountCliCheck(true, "Found local CLI: $detected")
                                } else {
                                    val appPath = LocalAccountCli.detectedDesktopApp(kind)
                                    accountCliCheck = AccountCliCheck(
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
                        SettingsButton(
                            "Browse",
                            onClick = { state.pickAccountCliExecutable()?.let { executablePath = it } },
                            variant = ButtonVariant.Secondary,
                        )
                    }
                }
            }
        }
        SettingsRow(
            "Model",
            modelDescription(kind, discoveredModel != null && reasoningEfforts.isEmpty()),
        ) {
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
                modifier = Modifier.width(settingsFieldWidth(PROVIDER_FIELD_WIDTH)),
                settingsStyle = true,
                onInteraction = reclaimFocus,
            )
        }
        if (showReasoningDropdown) {
            SettingsRow("Reasoning effort", AI_REASONING_EFFORT_HINT, indent = true) {
                AiReasoningEffortDropdown(
                    efforts = reasoningEfforts,
                    selected = reasoningEffort,
                    onPick = { reasoningEffort = it },
                    modifier = Modifier.width(settingsFieldWidth(PROVIDER_FIELD_WIDTH)),
                    settingsStyle = true,
                    onInteraction = reclaimFocus,
                )
            }
        }
        if (kind.usesApiKey) {
            SettingsRow("API key", AI_API_KEY_HINT) {
                SettingsTextField(
                    apiKey,
                    { value -> apiKey = value; state.setAiProviderApiKey(profile.id, value) },
                    Modifier.width(settingsFieldWidth(PROVIDER_FIELD_WIDTH)),
                    placeholder = "Required for cloud API providers",
                    visualTransformation = PasswordVisualTransformation(),
                    testTag = AiPageTags.API_KEY,
                )
            }
        }
        val endpointHost = runCatching { java.net.URI(endpoint.trim()).host.orEmpty() }.getOrDefault("")
        if (kind.usesHttpEndpoint && endpoint.isNotBlank() && !isLoopbackHost(endpointHost)) {
            // Screen images from device capture tools are sent to this provider when used.
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                SettingsCheckbox(
                    checked = acknowledged,
                    label = "I understand logs, source code, paths, device screen images, and tool results may leave this " +
                        "device, and that a plain HTTP (non-HTTPS) endpoint also sends my API key unencrypted.",
                    onChange = { ackState = endpoint.trim() to !acknowledged },
                    maxLines = 3,
                    labelColor = tc.ts,
                    fontSize = 11.sp,
                )
            }
        }
        validationError?.let { SettingsNoteRow(it, error = true) }
        connectionTest?.let { result ->
            when (result) {
                is ModelDiscoveryResult.Available -> SettingsNoteRow("Reachable — ${result.models.size} model(s) found.")
                is ModelDiscoveryResult.Unavailable -> SettingsNoteRow(result.message, error = true)
            }
        }
        accountCliCheck?.let { result -> SettingsNoteRow(result.message, error = !result.isReady) }
        // Right-aligned like every action row. Save profile is the explicit-save form's commit: Primary, and only enabled while the draft
        // differs from the stored profile. Test and Remove stay secondary.
        SettingsActionRow {
            if (profiles.size > 1) {
                SettingsButton(
                    "Remove…",
                    onClick = { pendingDeleteProfileId = profile.id },
                    variant = ButtonVariant.Secondary,
                    isDanger = true,
                    modifier = Modifier.testTag(AiPageTags.REMOVE_PROVIDER),
                )
            }
            // Reachability is a pure network probe against the current form fields: it never
            // saves, validates, or otherwise touches the stored profile, so it works whether or
            // not the endpoint would currently pass Save (e.g. an unacknowledged remote endpoint).
            SettingsButton(
                if (testingConnection) "Checking…" else if (kind.usesHttpEndpoint) "Test connection" else "Check local CLI",
                onClick = {
                    val testProfile = draft
                    val testKey = apiKey
                    testingConnection = true
                    connectionTest = null
                    accountCliCheck = null
                    coroutineScope.launch(Dispatchers.IO) {
                        if (!testProfile.kind.usesHttpEndpoint) {
                            accountCliCheck = checkAccountCli(testProfile)
                        } else {
                            val provider = when (testProfile.kind) {
                                AiProviderKind.ANTHROPIC_API -> AnthropicMessagesProvider(testProfile.baseUrl, testKey)
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
            SettingsButton(
                "Save profile",
                onClick = { validationError = state.updateAiProviderProfile(draft) },
                variant = ButtonVariant.Primary,
                enabled = isDirty,
                modifier = Modifier.testTag(AiPageTags.SAVE_PROFILE),
            )
        }
    }
    SettingsGroup("AI requests") {
        SettingsRow("Max MCP tool calls per request", AI_MAX_TOOL_CALLS_HINT, detail = AI_MAX_TOOL_CALLS_DETAIL) {
            val roundLimits = listOf(12, 25, 50, 100, 200, 500)
            SettingsStepper(
                options = roundLimits,
                value = state.settings.aiMaxToolRounds,
                onChange = { v -> state.updateSettings { it.copy(aiMaxToolRounds = v) } },
                modifier = Modifier.testTag(AiPageTags.MAX_TOOL_CALLS),
            )
        }
    }

    pendingDeleteProfileId?.let { id ->
        val targetName = profiles.firstOrNull { it.id == id }?.displayName ?: "this provider"
        SettingsConfirmDialog(
            title = "Remove provider profile?",
            message = "Delete “$targetName” from AI providers. This can't be undone.",
            onDismissRequest = { pendingDeleteProfileId = null },
        ) {
            SettingsDialogButton("Delete", primary = true, danger = true) {
                state.removeAiProviderProfile(id)
                editingProfileId = state.settings.aiProviderProfiles.first { it.selected }.id
                pendingDeleteProfileId = null
            }
            SettingsDialogButton("Cancel") { pendingDeleteProfileId = null }
        }
    }

    pendingNavigation?.let { action ->
        SettingsConfirmDialog(
            title = "Save changes to ${profile.displayName}?",
            message = "You changed this provider's settings without saving. Save them before continuing?",
            error = navigationSaveError,
            onDismissRequest = { pendingNavigation = null; navigationSaveError = null },
        ) {
            SettingsDialogButton("Save", primary = true) {
                val err = state.updateAiProviderProfile(draft)
                if (err == null) {
                    pendingNavigation = null
                    navigationSaveError = null
                    action()
                } else {
                    navigationSaveError = err
                }
            }
            SettingsDialogButton("Discard", danger = true) {
                pendingNavigation = null
                navigationSaveError = null
                action()
            }
            SettingsDialogButton("Cancel") { pendingNavigation = null; navigationSaveError = null }
        }
    }
}

/** The line under the Model row: what the field is for, plus the notes that depend on the provider and on the models it reported. */
private fun modelDescription(kind: AiProviderKind, noConfigurableEffort: Boolean): String = buildString {
    append(
        when {
            kind.usesHttpEndpoint -> AI_MODEL_HINT
            kind == AiProviderKind.CLAUDE_CODE_ACCOUNT ->
                "Optional. Blank uses your account's default. Sonnet, Opus and Haiku aliases work, or enter a full model id."
            else -> "Optional. Blank uses your account's default model."
        },
    )
    if (noConfigurableEffort) append("\nThis model does not expose configurable reasoning effort.")
}

// ── Commands ──────────────────────────────────────────────────────────

@Composable
internal fun CustomAiCommandsSettingsSection(state: AppState) {
    var pendingDeleteCommand by remember { mutableStateOf<CustomAiCommand?>(null) }
    // The group is the list: adding is its header button, and the page description above already says what a command is.
    SettingsGroup(
        "Commands",
        modifier = Modifier.settingsAnchor("Add command"),
        trailing = {
            SettingsButton(
                "Add command",
                onClick = { state.customCommandEditorTarget = CustomAiCommand("", "") },
                variant = ButtonVariant.Secondary,
                modifier = Modifier.testTag(AiPageTags.ADD_COMMAND),
            )
        },
    ) {
        if (state.customAiCommands.isEmpty()) {
            SettingsEmptyState("No commands yet.", asRow = true)
        } else {
            state.customAiCommands.forEach { command ->
                SettingsRow("/${command.name}", command.promptTemplate.lineSequence().firstOrNull().orEmpty().ifBlank { null }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SettingsButton(
                            "Edit",
                            onClick = { state.customCommandEditorTarget = command },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.testTag(AiPageTags.commandEdit(command.name)),
                        )
                        SettingsButton(
                            "Delete…",
                            onClick = { pendingDeleteCommand = command },
                            variant = ButtonVariant.Secondary,
                            isDanger = true,
                            modifier = Modifier.testTag(AiPageTags.commandDelete(command.name)),
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
            SettingsDialogButton("Delete", primary = true, danger = true) {
                state.deleteCustomAiCommand(command.name)
                pendingDeleteCommand = null
            }
            SettingsDialogButton("Cancel") { pendingDeleteCommand = null }
        }
    }
}

// ── Voice input ───────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoiceInputSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
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
    var addingLanguage by remember { mutableStateOf(false) }

    LaunchedEffect(installer) {
        while (true) {
            installer.requestVerification(scope)
            delay(VOICE_MODEL_POLL_MS)
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

    fun addLanguage(addable: List<VoiceLanguageOption>) {
        val code = languageToAdd
        if (addable.any { it.code == code }) {
            state.updateSettings { settings ->
                settings.copy(voiceInput = settings.voiceInput.copy(recognitionLanguageCodes = settings.voiceInput.recognitionLanguageCodes + code))
            }
            languageToAdd = ""
            addingLanguage = false
        }
    }

    val engineChoices = VoiceRecognitionEngines.availableChoices()
    val engine = voiceSettings.recognitionEngine
    SettingsGroup("Dictation") {
        // An OS engine's own notes (the permission it asks for, what it cannot do) are folded into this row's hover hint rather than shown
        // as a sentence row of their own.
        // A stored engine this computer does not offer (settings carried over from another OS) shows no chosen segment, and the description says so.
        val engineAvailable = engine in engineChoices
        SettingsRow(
            "Recognition engine",
            if (engineAvailable) {
                VoiceRecognitionEngines.description(engine)
            } else {
                "${engine.label} isn't available on this computer. Choose one of these engines."
            },
            detail = voiceEngineDetail(engine).takeIf { engineAvailable },
        ) {
            SettingsSegmented(
                options = engineChoices.map { it.label },
                selectedIndices = setOfNotNull(engineChoices.indexOf(engine).takeIf { it >= 0 }),
                onToggle = { index ->
                    state.updateSettings { settings ->
                        settings.copy(voiceInput = settings.voiceInput.copy(recognitionEngine = engineChoices[index]))
                    }
                },
                modifier = Modifier.testTag(AiPageTags.VOICE_ENGINE),
            )
        }
        if (VoiceRecognitionEngines.supportsTranslation(engine, selectedModel)) {
            SettingsRow("Translate dictated speech to English", VOICE_TRANSLATE_HINT) {
                SettingsToggle(
                    checked = voiceSettings.translateToEnglish,
                    label = "Translate dictated speech to English",
                    onChange = { next ->
                        state.updateSettings { settings -> settings.copy(voiceInput = settings.voiceInput.copy(translateToEnglish = next)) }
                    },
                    reclaimFocus = reclaimFocus,
                    testTag = AiPageTags.VOICE_TRANSLATE,
                )
            }
        } else if (engine == VoiceRecognitionEngine.WHISPER) {
            SettingsNoteRow(
                "Large v3 Turbo supports transcription only. Your saved English translation preference " +
                    "will apply again when you select a model that supports translation.",
            )
        }
    }

    SettingsGroup("Language") {
        val addableLanguages = VoiceLanguageCatalog.additional.filter { it.code !in voiceSettings.recognitionLanguageCodes }
        val removableLanguages = voiceSettings.recognitionLanguageCodes.filter { code -> VoiceLanguageCatalog.defaults.none { it.code == code } }
        // A dropdown, because the list grows with every code added: the languages in use, then "Add language…", which opens the row below.
        SettingsRow(
            "Recognition language",
            if (engine == VoiceRecognitionEngine.WINDOWS_SPEECH) VOICE_LANGUAGE_WINDOWS_HINT else VOICE_LANGUAGE_HINT,
        ) {
            SettingsDropdown(
                options = voiceSettings.recognitionLanguageCodes + if (addableLanguages.isNotEmpty()) listOf(ADD_LANGUAGE_OPTION) else emptyList(),
                selected = voiceSettings.selectedRecognitionLanguageCode,
                label = { code -> if (code == ADD_LANGUAGE_OPTION) "Add language…" else VoiceLanguageCatalog.label(code) },
                onSelect = { code ->
                    if (code == ADD_LANGUAGE_OPTION) {
                        addingLanguage = true
                    } else {
                        state.updateSettings { settings ->
                            settings.copy(voiceInput = settings.voiceInput.copy(selectedRecognitionLanguageCode = code))
                        }
                    }
                },
                testTag = AiPageTags.VOICE_LANGUAGE,
                optionTag = { code -> "${AiPageTags.VOICE_LANGUAGE}-option-$code" },
                onInteraction = reclaimFocus,
            )
        }
        if ((addingLanguage && addableLanguages.isNotEmpty()) || removableLanguages.isNotEmpty()) {
            SettingsStackedRow(
                "More languages",
                if (addableLanguages.isNotEmpty()) {
                    "Supported codes: ${addableLanguages.joinToString { "${it.label} (${it.code})" }}"
                } else {
                    null
                },
                indent = true,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (addingLanguage && addableLanguages.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            SettingsTextField(
                                value = languageToAdd,
                                onValueChange = { languageToAdd = it.lowercase().filter(Char::isLetter).take(LANGUAGE_CODE_MAX) },
                                modifier = Modifier.width(LANGUAGE_CODE_FIELD_WIDTH),
                                placeholder = "Language code, e.g. ru",
                                onSubmit = { addLanguage(addableLanguages) },
                            )
                            SettingsButton(
                                "Add",
                                onClick = { addLanguage(addableLanguages) },
                                enabled = addableLanguages.any { it.code == languageToAdd },
                            )
                        }
                    }
                    if (removableLanguages.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            removableLanguages.forEach { code ->
                                SettingsButton(
                                    "Remove ${VoiceLanguageCatalog.label(code)}",
                                    onClick = {
                                        state.updateSettings { settings ->
                                            val languages = settings.voiceInput.recognitionLanguageCodes - code
                                            settings.copy(
                                                voiceInput = settings.voiceInput.copy(
                                                    recognitionLanguageCodes = languages,
                                                    selectedRecognitionLanguageCode = settings.voiceInput.selectedRecognitionLanguageCode
                                                        .takeIf { it in languages } ?: "auto",
                                                ),
                                            )
                                        }
                                    },
                                    variant = ButtonVariant.Ghost,
                                    horizontalPadding = 7.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (engine == VoiceRecognitionEngine.WHISPER) {
        SettingsGroup("Local model") {
            SettingsRow(
                "Recognition model",
                selectedModel.description,
                detail = selectedModel.description + " Downloads start only when you choose Download; speech stays local.",
            ) {
                SettingsDropdown(
                    options = VoiceModelCatalog.all,
                    selected = selectedModel,
                    label = { model: VoiceModelDescriptor -> "${model.displayName} · ${formatByteSize(model.sizeBytes)}" },
                    onSelect = { model ->
                        state.updateSettings { settings -> settings.copy(voiceInput = settings.voiceInput.copy(modelId = model.id)) }
                    },
                    testTag = AiPageTags.VOICE_MODEL,
                    minWidth = SETTINGS_FIELD_LONG,
                    onInteraction = reclaimFocus,
                )
            }
            SettingsRow(
                "Model status",
                buildString {
                    append(
                        when {
                            checkingModel -> "Checking local model…"
                            installed -> "Installed in Indagium's application data, in voice-models."
                            else -> "Not installed."
                        },
                    )
                    if (installing) append("\nDownloading ${formatByteSize(downloadedBytes)}…")
                    statusMessage?.let { append("\n$it") }
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AccentLink("Model license", onClick = { openExternalUrl(selectedModel.licenseUrl) })
                    if (installed) {
                        SettingsButton(
                            "Remove model",
                            onClick = {
                                statusMessage = if (installer.remove()) "Local voice model removed." else "Could not remove the local voice model."
                            },
                            variant = ButtonVariant.Secondary,
                            isDanger = true,
                            enabled = !installing,
                        )
                        SettingsButton(
                            "Reinstall",
                            onClick = {
                                installer.remove()
                                installModel()
                            },
                            variant = ButtonVariant.Secondary,
                            enabled = !installing,
                        )
                    } else {
                        // A secondary action like every other row's button: Primary is only for the commit of a form and the dialog's Done.
                        SettingsButton(
                            if (installing) "Downloading…" else "Download local model",
                            onClick = ::installModel,
                            variant = ButtonVariant.Secondary,
                            enabled = !installing && !checkingModel,
                        )
                    }
                }
            }
        }
    }
}

/** The hover hint of the engine row: the engine's own description and, for an OS engine, what to expect of it. */
private fun voiceEngineDetail(engine: VoiceRecognitionEngine): String? {
    val description = VoiceRecognitionEngines.description(engine)
    return when (engine) {
        VoiceRecognitionEngine.WHISPER -> null
        VoiceRecognitionEngine.APPLE_SPEECH ->
            "$description At first use macOS asks for Speech Recognition permission. If the selected language has no on-device Apple model, " +
                "Indagium refuses to send audio and you can switch back to Whisper. English translation is available only with Whisper."
        VoiceRecognitionEngine.WINDOWS_SPEECH ->
            "$description Language availability is narrower than Whisper; if there is no recogniser for the selected language, Indagium " +
                "shows the reason and you can switch back to Whisper. English translation is available only with Whisper."
    }
}
