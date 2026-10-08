@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.indagium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.indagium.ai.normalizeAiProviderProfiles
import com.indagium.model.WorkflowAiSelection
import com.indagium.model.summaryLabel
import com.indagium.testing.authoring.RecordedTestStep
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.TestStepRecordingSnapshot
import com.indagium.testing.authoring.contextHint
import com.indagium.testing.model.TestCase
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// "Rewrite with AI" in the recording review: pick a profile (and optionally a model and reasoning effort), say what the test is
// about, and the raw taps and swipes become readable steps with expected results. Removed inputs remain recoverable; Undo rewrite
// restores only kept inputs, while Restore removed inputs resets the preview to the full raw recording.

private val NOTE_MIN_HEIGHT = 44.dp
private val NOTE_MAX_HEIGHT = 110.dp
private val PROFILE_MENU_WIDTH = 280.dp

@Composable
internal fun RecordingRewriteSection(session: TestStepRecordingSession, case: TestCase, snapshot: TestStepRecordingSnapshot, locked: Boolean) {
    val ui = LocalTestsUi.current
    val profiles = normalizeAiProviderProfiles(ui.state.settings.aiProviderProfiles)
    val savedSelection = ui.state.settings.workflowAiSelections[AiWorkflow.RECORDING_REWRITE]
    var selection by remember(profiles, savedSelection) {
        mutableStateOf(resolveAiWorkflowSelection(ui.state.settings, AiWorkflow.RECORDING_REWRITE, profiles))
    }
    val profile = profiles.firstOrNull { it.id == selection.profileId }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var rewriting by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val catalog = remember { ModelCatalog() }
    val busy = rewriting || locked || ui.state.isTestStepRecordingRewriting(session.id)
    val blocked = snapshot.pendingSnapshots > 0
    DisposableEffect(session.id) { onDispose { job?.cancel() } }

    TestsSectionTitle("Rewrite with AI")
    TestsHint(
        "Turns the raw taps and swipes into readable steps with expected results. The chosen provider receives the recorded inputs, " +
            "screen text, typed text (passwords are hidden) and screenshots from the device. Nothing is added to the case until you apply.",
        maxLines = 4,
    )
    if (snapshot.excludedSourceInputIds.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        TestsHint("${snapshot.excludedSourceInputIds.size} recorded input(s) excluded from rewrite and apply.")
    }
    Spacer(Modifier.height(6.dp))
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
                ui.state.rememberAiWorkflowSelection(AiWorkflow.RECORDING_REWRITE, selection)
            },
            enabled = !busy,
            isSelected = { it.id == profile?.id },
            emptyText = "Configure a provider profile in Settings first",
            menuWidth = PROFILE_MENU_WIDTH,
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
                    ui.state.rememberAiWorkflowSelection(AiWorkflow.RECORDING_REWRITE, selection)
                },
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    TestsLabeled("What was this test about? (optional)") {
        ScrollableTextArea(
            note, { note = it }, placeholder = case.name, modifier = Modifier.fillMaxWidth(), minHeight = NOTE_MIN_HEIGHT, maxHeight = NOTE_MAX_HEIGHT,
            enabled = !busy,
        )
    }
    error?.let { TestsErrorText(it) }
    snapshot.rewriteUsage?.let { usage ->
        TestsHint("Latest rewrite attempt · ${usage.summaryLabel()}")
    }
    snapshot.rewriteUsageTotal?.let { usage ->
        if (snapshot.rewriteAttemptCount > 1) TestsHint("Rewrite session total (${snapshot.rewriteAttemptCount} attempts) · ${usage.summaryLabel()}")
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        if (snapshot.rewriteApplied) {
            AppButton(
                "Undo rewrite",
                onClick = { error = ui.state.restoreTestStepRecordingRaw(session.id).takeIf { it !is StoreResult.Ok }?.userMessage() },
                enabled = !busy && snapshot.pendingSnapshots == 0,
                variant = ButtonVariant.Ghost,
            )
        }
        if (snapshot.excludedSourceInputIds.isNotEmpty()) {
            AppButton(
                "Restore removed inputs",
                onClick = {
                    error = ui.state.restoreRemovedTestRecordingInputs(session.id).takeIf { it !is StoreResult.Ok }?.userMessage()
                },
                enabled = !busy && snapshot.pendingSnapshots == 0,
                variant = ButtonVariant.Ghost,
            )
        }
        if (rewriting) AppButton("Cancel", onClick = { job?.cancel() }, variant = ButtonVariant.Ghost)
        HintedButton(
            when {
                rewriting -> "Rewriting…"
                snapshot.rewriteApplied -> "Rewrite again"
                else -> "Rewrite"
            },
            onClick = {
                val chosen = profile ?: return@HintedButton
                rewriting = true
                error = null
                job = ui.scope.launch {
                    try {
                        val result = ui.state.rewriteTestStepRecording(
                            session.id, chosen.id, selection.modelId, selection.reasoningEffort, note,
                        )
                        if (result !is StoreResult.Ok) error = result.userMessage()
                    } finally {
                        rewriting = false
                    }
                }
            },
            enabled = !busy && !blocked && snapshot.steps.isNotEmpty() && profile != null,
            disabledHint = when {
                snapshot.steps.isEmpty() -> "Restore removed inputs or keep at least one step before rewriting."
                profile == null -> "Configure a provider profile in Settings first."
                blocked -> "Waiting for ${snapshot.pendingSnapshots} screen snapshot(s) to finish."
                else -> "A rewrite or an apply is already running."
            },
            variant = ButtonVariant.Primary,
        )
    }
    if (snapshot.rewriteApplied && snapshot.rewriteNotes.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        TestsHint("AI notes: ${snapshot.rewriteNotes}", maxLines = 6)
    }
    Spacer(Modifier.height(8.dp))
}

/** The "From N recorded inputs" list of a rewritten step: collapsed by default, it names the raw inputs the step stands for. */
@Composable
internal fun RecordedInputsList(row: RecordedTestStep, raw: List<RecordedTestStep>) {
    var open by remember(row.id) { mutableStateOf(false) }
    val sources = row.sourceInputIds.mapNotNull { id -> raw.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { it + 1 to raw[it] } }
    AppButton(
        "From ${sources.size} recorded input${if (sources.size == 1) "" else "s"} ${if (open) "▾" else "▸"}",
        onClick = { open = !open },
        variant = ButtonVariant.Ghost,
        modifier = Modifier.padding(top = 2.dp),
    )
    if (open) {
        sources.forEach { (number, source) ->
            TestsHint("$number. ${source.action}${source.contextHint()?.let { " ($it)" }.orEmpty()}", maxLines = 2)
        }
    }
}
