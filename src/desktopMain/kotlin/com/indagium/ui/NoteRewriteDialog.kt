@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.ai.ModelDiscoveryResult
import com.indagium.ai.normalizeAiProviderProfiles
import com.indagium.model.AiUsageStats
import com.indagium.model.WorkflowAiSelection
import com.indagium.model.summaryLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Provider transports surface varied auth/network failures; cancellation is rethrown above and
// other provider-boundary exceptions become a recoverable message in this dialog.
@Suppress("LongMethod", "LongParameterList", "TooGenericExceptionCaught")
@Composable
internal fun NoteRewriteDialog(
    state: AppState,
    snapshot: NoteRewriteSnapshot,
    isCurrent: () -> Boolean,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tc = tc()
    val profiles = remember(state.settings.aiProviderProfiles) { normalizeAiProviderProfiles(state.settings.aiProviderProfiles) }
    var selection by remember(snapshot, state) {
        mutableStateOf(resolveAiWorkflowSelection(state.settings, AiWorkflow.NOTE_REWRITE, profiles))
    }
    val profile = profiles.firstOrNull { it.id == selection.profileId }
    val scope = rememberCoroutineScope()
    val catalog = remember { ModelCatalog() }
    val discovery = profile?.let { catalog.result(it.id) }
    var instructions by remember(snapshot) { mutableStateOf("") }
    var working by remember(snapshot) { mutableStateOf(false) }
    val instructionsTargetId = remember(snapshot) { "note-rewrite:${snapshot.sessionKey}:instructions" }
    val prose = rememberProseDictation(
        targetId = instructionsTargetId,
        value = instructions,
        onValue = { instructions = it },
        enabled = !working,
    )
    val voiceUi = LocalVoiceInputUi.current
    val dictationActive = prose.control?.phase == DictationPhase.Recording || prose.control?.phase == DictationPhase.Transcribing
    var proposal by remember(snapshot) { mutableStateOf<String?>(null) }
    var usage by remember(snapshot) { mutableStateOf<AiUsageStats?>(null) }
    var error by remember(snapshot) { mutableStateOf<String?>(null) }
    var requestJob by remember(snapshot) { mutableStateOf<Job?>(null) }

    DisposableEffect(snapshot) { onDispose { requestJob?.cancel() } }
    LaunchedEffect(profile?.id, selection.modelId, selection.reasoningEffort, selection.modelWasDiscovered, discovery) {
        val currentProfile = profile ?: return@LaunchedEffect
        val normalized = normalizeAiWorkflowSelectionForCatalog(selection, currentProfile, discovery)
        if (normalized != selection) {
            selection = normalized
            state.rememberAiWorkflowSelection(AiWorkflow.NOTE_REWRITE, normalized)
        }
    }

    fun saveSelection(updated: WorkflowAiSelection) {
        selection = updated
        state.rememberAiWorkflowSelection(AiWorkflow.NOTE_REWRITE, updated)
    }

    fun close() {
        voiceUi?.coordinator?.cancel(instructionsTargetId)
        requestJob?.cancel()
        requestJob = null
        onDismiss()
    }

    fun rewrite() {
        val selectedProfile = profile ?: run {
            error = "Choose an AI profile in Settings first."
            return
        }
        if (working || !isCurrent()) {
            if (!isCurrent()) error = "The note changed. Close this dialog and start again."
            return
        }
        voiceUi?.coordinator?.cancel(instructionsTargetId)
        error = null
        proposal = null
        usage = null
        working = true
        requestJob = scope.launch {
            try {
                val result = state.generateNoteRewrite(
                    profileId = selectedProfile.id,
                    model = selection.modelId,
                    reasoningEffort = selection.reasoningEffort,
                    targetText = snapshot.original,
                    instructions = instructions,
                    onUsage = { usage = it },
                )
                if (isCurrent()) proposal = result else error = "The note changed while rewriting. Nothing was applied."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "The provider could not rewrite this text."
            } finally {
                working = false
                requestJob = null
            }
        }
    }

    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(10.dp)
        Column(
            Modifier.width(680.dp).heightIn(max = 760.dp).background(tc.p, shape)
                .border(1.dp, tc.br, shape).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    AppText(
                        if (proposal == null) "Rewrite with AI" else "Review rewrite",
                        color = tc.tx,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    AppText("Only the captured note text and your instructions are sent.", color = tc.td, fontSize = 11.sp)
                }
                CloseButton(onClick = ::close)
            }

            if (proposal == null) {
                if (profiles.isEmpty() || profile == null) {
                    AppText("Add an AI provider profile in Settings to rewrite this note.", color = tc.td, fontSize = 12.sp)
                } else if (!working) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AiProviderDropdown(
                            profiles = profiles,
                            selected = profile,
                            onSelect = { id -> saveSelection(WorkflowAiSelection(profileId = id)) },
                            modifier = Modifier.weight(1f),
                        )
                        AiModelDropdown(
                            model = selection.modelId ?: profile.model,
                            discovery = discovery,
                            onDiscoverModels = { catalog.find(state, scope, profile) },
                            onPickModel = { picked ->
                                val override = picked.trim().takeIf { it.isNotEmpty() && it != profile.model }
                                val catalogued = override != null &&
                                    (discovery as? ModelDiscoveryResult.Available)?.models?.any { it.id == override } == true
                                saveSelection(
                                    selection.copy(
                                        modelId = override,
                                        reasoningEffort = effortOverrideForModel(profile, override, selection.reasoningEffort, discovery),
                                        modelWasDiscovered = catalogued,
                                    ),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val efforts = effortChoicesFor(profile, selection.modelId ?: profile.model, discovery)
                    if (efforts.isNotEmpty()) {
                        AiReasoningEffortDropdown(
                            efforts = efforts,
                            selected = selection.reasoningEffort ?: profile.reasoningEffort,
                            onPick = { picked ->
                                saveSelection(selection.copy(reasoningEffort = picked.takeIf { it != profile.reasoningEffort }))
                            },
                            modifier = Modifier.width(200.dp),
                        )
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppText("Profile", color = tc.td, fontSize = 10.sp)
                        AppText(profile.displayName, color = tc.tx, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        AppText("Model", color = tc.td, fontSize = 10.sp)
                        AppText(selection.modelId ?: profile.model, color = tc.tx, fontSize = 11.sp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    AppText("Optional instructions", color = tc.ts, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    val instructionsShape = RoundedCornerShape(6.dp)
                    Box(
                        Modifier.fillMaxWidth().height(72.dp).background(tc.bg, instructionsShape)
                            .border(1.dp, if (prose.control?.phase == DictationPhase.Recording) DANGER_RED else tc.br, instructionsShape)
                            .focusGroup(),
                    ) {
                        BasicTextField(
                            value = prose.state.value,
                            onValueChange = { updated: TextFieldValue ->
                                prose.accept(updated)
                                instructions = updated.text
                            },
                            enabled = !working,
                            textStyle = TextStyle(color = tc.tx, fontSize = 12.sp),
                            cursorBrush = SolidColor(tc.ac),
                            modifier = Modifier.fillMaxWidth().height(72.dp)
                                .padding(start = 9.dp, top = 9.dp, bottom = 9.dp, end = if (prose.control == null) 9.dp else 36.dp)
                                .then(prose.fieldModifier)
                                .testTag("note-rewrite-instructions"),
                            decorationBox = { inner ->
                                Box {
                                    if (instructions.isEmpty()) {
                                        AppText(
                                            "For example: keep the wording concise.",
                                            color = tc.td,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        prose.control?.let { control ->
                            DictationFieldOverlay(control, hasText = instructions.isNotEmpty(), singleLine = false, shape = instructionsShape)
                        }
                    }
                }
                error?.let {
                    AppText(
                        it,
                        color = DANGER_RED,
                        fontSize = 11.sp,
                        modifier = Modifier.testTag("note-rewrite-error"),
                    )
                }
                if (working) {
                    AppText(
                        "Rewriting…  ${usage?.summaryLabel().orEmpty()}",
                        color = tc.td,
                        fontSize = 11.sp,
                    )
                } else if (usage != null) {
                    AppText("Usage: ${usage?.summaryLabel().orEmpty()}", color = tc.td, fontSize = 11.sp)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppButton(
                        "Cancel",
                        onClick = ::close,
                        variant = ButtonVariant.Ghost,
                        modifier = Modifier.testTag("note-rewrite-cancel"),
                    )
                    Spacer(Modifier.width(8.dp))
                    AppButton(
                        "Generate preview",
                        onClick = ::rewrite,
                        variant = ButtonVariant.Primary,
                        enabled = !working && !dictationActive && isCurrent(),
                        modifier = Modifier.testTag("note-rewrite-submit"),
                    )
                }
            } else {
                Row(
                    Modifier.weight(1f, fill = false).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        Modifier.weight(1f).heightIn(max = 430.dp)
                            .background(tc.bg, RoundedCornerShape(6.dp)).padding(10.dp),
                    ) {
                        AppText("Original", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        SelectionContainer {
                            Text(
                                snapshot.original,
                                color = tc.tx,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp).verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                    Column(
                        Modifier.weight(1f).heightIn(max = 430.dp)
                            .background(tc.bg, RoundedCornerShape(6.dp)).padding(10.dp),
                    ) {
                        AppText("Proposed", color = tc.ac, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        SelectionContainer {
                            Text(
                                proposal.orEmpty(),
                                color = tc.tx,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp).verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
                error?.let {
                    AppText(it, color = DANGER_RED, fontSize = 11.sp)
                }
                usage?.let {
                    AppText("Usage: ${it.summaryLabel()}", color = tc.td, fontSize = 11.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    AppButton(
                        "Cancel",
                        onClick = ::close,
                        variant = ButtonVariant.Ghost,
                        modifier = Modifier.testTag("note-rewrite-cancel"),
                    )
                    Spacer(Modifier.width(8.dp))
                    AppButton("Back", onClick = { proposal = null; error = null }, variant = ButtonVariant.Secondary, enabled = !working)
                    Spacer(Modifier.width(8.dp))
                    AppButton(
                        "Apply",
                        onClick = { proposal?.let(onApply) },
                        variant = ButtonVariant.Primary,
                        enabled = !working && isCurrent(),
                        modifier = Modifier.testTag("note-rewrite-apply"),
                    )
                }
            }
        }
    }
}
