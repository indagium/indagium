package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.ai.normalizeAiProviderProfiles
import com.indagium.model.AiUsageStats
import com.indagium.model.WorkflowAiSelection
import com.indagium.model.summaryLabel
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.TestScript
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.validateScript
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val SCRIPT_DRAFT_DIALOG_WIDTH = 660.dp
private val SCRIPT_DRAFT_DIALOG_MAX_HEIGHT = 760.dp
private val SCRIPT_DRAFT_FIELD_WIDTH = 200.dp
private val SCRIPT_DRAFT_COMMAND_MIN_HEIGHT = 110.dp
private val SCRIPT_DRAFT_COMMAND_MAX_HEIGHT = 260.dp

private const val TARGET_AUTO_LABEL = "Auto (let the AI choose)"

/** The target choices of the dialog: null lets the model pick. */
private fun scriptTargetHintLabel(hint: ScriptTarget?): String = when (hint) {
    null -> TARGET_AUTO_LABEL
    ScriptTarget.HOST_SHELL -> "Computer shell"
    ScriptTarget.ADB_SHELL -> "Device shell (adb)"
}

/**
 * "Generate with AI…" for the Scripts screen: the person describes the script, the AI drafts it, the person reviews and edits the
 * preview and only then it enters the library. With [existing] (a regeneration) the description starts as that script's and the
 * confirm button replaces it, keeping its id. A generated script always asks before an agent may run it.
 */
@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
internal fun TestScriptDraftDialog(existing: TestScript?, onApplied: (TestScript) -> Unit, onDismiss: () -> Unit) {
    val ui = LocalTestsUi.current
    val tc = tc()
    val profiles = normalizeAiProviderProfiles(ui.state.settings.aiProviderProfiles)
    val savedSelection = ui.state.settings.workflowAiSelections[AiWorkflow.TEST_SCRIPT_DRAFT]
    var selection by remember(profiles, savedSelection) {
        mutableStateOf(resolveAiWorkflowSelection(ui.state.settings, AiWorkflow.TEST_SCRIPT_DRAFT, profiles))
    }
    val profile = profiles.firstOrNull { it.id == selection.profileId }
    val catalog = remember { ModelCatalog() }
    var request by remember { mutableStateOf(existing?.description.orEmpty()) }
    var targetHint by remember { mutableStateOf<ScriptTarget?>(null) }
    var draft by remember { mutableStateOf<TestScript?>(null) }
    var usage by remember { mutableStateOf<AiUsageStats?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val others = ui.library.scripts.filterNot { it.id == existing?.id }
    val problem = draft?.let { validateScript(it, others) }

    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    fun close() {
        job?.cancel()
        onDismiss()
    }

    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TestsDialogFrame(
            title = if (existing == null) "Generate script with AI" else "Regenerate script with AI",
            width = SCRIPT_DRAFT_DIALOG_WIDTH,
            maxHeight = SCRIPT_DRAFT_DIALOG_MAX_HEIGHT,
            status = {
                error?.let { TestsErrorText(it, Modifier.padding(top = 8.dp)) }
                if (error == null) problem?.let { TestsErrorText(it, Modifier.padding(top = 8.dp)) }
            },
            body = {
                TestsHint(
                    "Generation only creates this preview. Nothing runs, and the library does not change until you confirm. " +
                        "A generated script always asks you before an agent may run it.",
                )
                usage?.let {
                    Spacer(Modifier.height(5.dp))
                    AppText("Latest attempt · ${it.summaryLabel()}", color = tc.td, fontSize = 10.sp)
                }
                Spacer(Modifier.height(12.dp))
                val shown = draft
                if (shown == null) {
                    TestsLabeled("Provider profile") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            TestsDropdown(
                                selectedLabel = profile?.displayName ?: "Choose a configured profile",
                                options = profiles,
                                optionLabel = { it.displayName },
                                onSelect = {
                                    selection = WorkflowAiSelection(profileId = it.id)
                                    ui.state.rememberAiWorkflowSelection(AiWorkflow.TEST_SCRIPT_DRAFT, selection)
                                },
                                modifier = Modifier.width(SCRIPT_DRAFT_FIELD_WIDTH),
                                isSelected = { it.id == profile?.id },
                                emptyText = "Configure a provider profile in Settings first",
                                menuWidth = 320.dp,
                                filled = true,
                            )
                            profile?.let { chosen ->
                                ModelAndEffortPickers(
                                    profile = chosen,
                                    catalog = catalog,
                                    model = selection.modelId,
                                    effort = selection.reasoningEffort,
                                    modelWasDiscovered = selection.modelWasDiscovered,
                                    onChange = { pickedModel, pickedEffort, discovered ->
                                        selection = WorkflowAiSelection(chosen.id, pickedModel, pickedEffort, discovered)
                                        ui.state.rememberAiWorkflowSelection(AiWorkflow.TEST_SCRIPT_DRAFT, selection)
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TestsLabeled("Runs on") {
                        TestsDropdown(
                            selectedLabel = scriptTargetHintLabel(targetHint),
                            options = listOf<ScriptTarget?>(null) + ScriptTarget.entries,
                            optionLabel = ::scriptTargetHintLabel,
                            onSelect = { targetHint = it },
                            modifier = Modifier.width(SCRIPT_DRAFT_FIELD_WIDTH),
                            isSelected = { it == targetHint },
                            menuWidth = 260.dp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TestsLabeled("What should the script do?") {
                        ScrollableTextArea(request, { request = it }, modifier = Modifier.fillMaxWidth(), minHeight = 90.dp, maxHeight = 180.dp)
                    }
                    if (working) {
                        Spacer(Modifier.height(10.dp))
                        AiWorkProgress("Drafting script", profile?.displayName, selection.modelId ?: profile?.model, stage = null)
                    }
                } else {
                    AppText("Review and edit the proposed script.", color = tc.ts, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    TestsLabeled("Tool name") {
                        CommitTextField(
                            shown.toolName, { value -> draft = shown.copy(toolName = value.trim()); StoreResult.Ok(Unit) },
                            mono = true, placeholder = "reset_app",
                        )
                    }
                    TestsLabeled("Description (shown to the agent)") {
                        CommitTextField(
                            shown.description, { value -> draft = shown.copy(description = value); StoreResult.Ok(Unit) },
                            multiline = true, placeholder = "What the script does",
                        )
                    }
                    TestsLabeled("Runs on") {
                        TestsDropdown(
                            selectedLabel = scriptTargetHintLabel(shown.target),
                            options = ScriptTarget.entries,
                            optionLabel = ::scriptTargetHintLabel,
                            onSelect = { draft = shown.copy(target = it) },
                            modifier = Modifier.width(SCRIPT_DRAFT_FIELD_WIDTH),
                            isSelected = { it == shown.target },
                        )
                    }
                    TestsLabeled("Command") {
                        CommitTextField(
                            shown.commandTemplate, { value -> draft = shown.copy(commandTemplate = value); StoreResult.Ok(Unit) },
                            multiline = true, mono = true, placeholder = "echo hello",
                            minHeight = SCRIPT_DRAFT_COMMAND_MIN_HEIGHT, maxHeight = SCRIPT_DRAFT_COMMAND_MAX_HEIGHT,
                        )
                    }
                    TestsHint("Parameters are passed as environment variables; they are never pasted into the command text.", Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(8.dp))
                    TestsSectionTitle("Parameters (${shown.params.size})")
                    if (shown.params.isEmpty()) {
                        TestsHint("No parameters. You can add or change them in the script editor after creating it.")
                    } else {
                        Column(Modifier.fillMaxWidth().background(tc.p2, CORNER_MD).padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            shown.params.forEach { param ->
                                val detail = buildString {
                                    append(param.type.name.lowercase())
                                    append(if (param.required) ", required" else ", optional")
                                    param.defaultValue?.let { append(", default \"$it\"") }
                                }
                                AppText("${param.name} ($detail)", color = tc.ac, fontSize = 10.sp, fontFamily = MONO)
                                if (param.description.isNotBlank()) AppText(param.description, color = tc.ts, fontSize = 10.sp)
                            }
                        }
                    }
                }
            },
            footer = {
                if (draft == null) {
                    DialogActionButton(
                        if (working) "Drafting…" else "Generate preview",
                        active = true,
                        enabled = !working && profile != null && request.isNotBlank(),
                    ) {
                        val selected = profile ?: return@DialogActionButton
                        working = true
                        error = null
                        job = ui.scope.launch {
                            try {
                                val result = ui.state.testScriptDraftService.generate(
                                    profileId = selected.id,
                                    description = request,
                                    existing = ui.library.scripts,
                                    model = selection.modelId,
                                    effort = selection.reasoningEffort,
                                    targetHint = targetHint,
                                    current = existing,
                                    onUsage = { usage = it },
                                )
                                when (result) {
                                    is StoreResult.Ok -> draft = result.value
                                    else -> error = result.userMessage()
                                }
                            } finally {
                                working = false
                            }
                        }
                    }
                    if (working) DialogActionButton("Stop", active = false) { job?.cancel() }
                    DialogActionButton("Cancel", active = false) { close() }
                } else {
                    DialogActionButton(
                        if (existing == null) "Create script" else "Replace script",
                        active = true,
                        enabled = problem == null,
                    ) {
                        val edited = draft ?: return@DialogActionButton
                        val result: StoreResult<TestScript> = if (existing == null) {
                            ui.state.createTestScript(edited)
                        } else {
                            ui.state.updateTestScript(existing.id) { edited.copy(id = it.id) }
                        }
                        when (result) {
                            is StoreResult.Ok -> {
                                onApplied(result.value)
                                onDismiss()
                            }
                            else -> error = result.userMessage()
                        }
                    }
                    DialogActionButton("Back", active = false) {
                        draft = null
                        error = null
                    }
                    DialogActionButton("Cancel", active = false) { close() }
                }
            },
        )
    }
}
