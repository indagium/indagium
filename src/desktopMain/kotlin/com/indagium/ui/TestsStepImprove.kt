package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.ai.normalizeAiProviderProfiles
import com.indagium.model.AiUsageStats
import com.indagium.model.WorkflowAiSelection
import com.indagium.model.summaryLabel
import com.indagium.testing.authoring.ImproveEvidenceSummary
import com.indagium.testing.authoring.MAX_IMPROVE_NOTE_CHARS
import com.indagium.testing.authoring.TestCaseImproveProposal
import com.indagium.testing.model.StepKind
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// The "Improve steps with AI" dialog: pick a profile (and model / effort), say what to improve, see which evidence will be sent, then
// review the proposal step by step (old against new, the reason, a warning for a result that may flap), accept or edit each step
// and apply the selected ones. The state behind it is in TestsStepImproveState.kt; the work is TestCaseImproveService's.

private val DIALOG_WIDTH = 720.dp
private val DIALOG_MAX_HEIGHT = 780.dp
private val PROFILE_MENU_WIDTH = 320.dp
private val NOTE_MIN_HEIGHT = 60.dp
private val NOTE_MAX_HEIGHT = 140.dp

@Suppress("LongMethod", "CyclomaticComplexMethod") // One dialog with two phases (ask, review) that share its state and cleanup.
@Composable
internal fun TestStepImproveDialog(target: ImproveStepsTarget, onDismiss: () -> Unit) {
    val ui = LocalTestsUi.current
    val tc = tc()
    val service = ui.state.testCaseImproveService
    val case = ui.library.findCase(target.caseId)?.case
    val profiles = normalizeAiProviderProfiles(ui.state.settings.aiProviderProfiles)
    val savedSelection = ui.state.settings.workflowAiSelections[AiWorkflow.TEST_CASE_IMPROVE]
    var selection by remember(profiles, savedSelection) {
        mutableStateOf(resolveAiWorkflowSelection(ui.state.settings, AiWorkflow.TEST_CASE_IMPROVE, profiles))
    }
    val profile = profiles.firstOrNull { it.id == selection.profileId }
    val catalog = remember { ModelCatalog() }
    var note by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<ImproveReview?>(null) }
    var attemptUsage by remember { mutableStateOf<AiUsageStats?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val editable = case != null && !ui.library.readOnly && ui.library.suite(target.suiteId)?.readOnly != true
    val blockedReason = improveBlockedReason(case, editable)
    val evidence by produceState<StoreResult<ImproveEvidenceSummary>?>(null, target.caseId, target.runId, case?.steps) {
        value = if (case == null) null else service.describeEvidence(target.caseId, target.runId)
    }

    fun discardPreview() {
        job?.cancel()
        review?.proposal?.let { service.discard(it.id) }
        onDismiss()
    }
    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            review?.proposal?.let { service.discard(it.id) }
        }
    }

    Dialog(onDismissRequest = ::discardPreview, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(DIALOG_WIDTH).background(tc.p, CORNER_MD).border(1.dp, tc.br, CORNER_MD).padding(18.dp)
                .heightIn(max = DIALOG_MAX_HEIGHT).verticalScroll(rememberScrollState()),
        ) {
            AppText("Improve steps with AI", color = tc.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            AppText(case?.name.orEmpty(), color = tc.ts, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            val current = review
            if (current == null) {
                ImproveAskPhase(
                    profileName = profile?.displayName,
                    evidenceLine = evidence?.let { result ->
                        (result as? StoreResult.Ok)?.let { improveEvidenceLabel(it.value, explicitRun = target.runId != null) }
                            ?: result.userMessage()
                    },
                    blockedReason = blockedReason,
                    profilePicker = {
                        TestsLabeled("Provider profile") {
                            TestsDropdown(
                                selectedLabel = profile?.displayName ?: "Choose a configured profile",
                                options = profiles,
                                optionLabel = { it.displayName },
                                onSelect = {
                                    selection = WorkflowAiSelection(profileId = it.id)
                                    ui.state.rememberAiWorkflowSelection(AiWorkflow.TEST_CASE_IMPROVE, selection)
                                },
                                enabled = !working,
                                isSelected = { it.id == profile?.id },
                                emptyText = "Configure a provider profile in Settings first",
                                menuWidth = PROFILE_MENU_WIDTH,
                            )
                        }
                        profile?.let { chosen ->
                            FlowRow(
                                Modifier.padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                ModelAndEffortPickers(
                                    profile = chosen,
                                    catalog = catalog,
                                    model = selection.modelId,
                                    effort = selection.reasoningEffort,
                                    modelWasDiscovered = selection.modelWasDiscovered,
                                    onChange = { pickedModel, pickedEffort, discovered ->
                                        selection = WorkflowAiSelection(chosen.id, pickedModel, pickedEffort, discovered)
                                        ui.state.rememberAiWorkflowSelection(AiWorkflow.TEST_CASE_IMPROVE, selection)
                                    },
                                )
                            }
                        }
                    },
                    note = note,
                    onNote = { note = it.take(MAX_IMPROVE_NOTE_CHARS) },
                    working = working,
                    usageLine = attemptUsage?.let { "Latest attempt · ${it.summaryLabel()}" },
                    error = error,
                    canGenerate = !working && profile != null && blockedReason == null,
                    onCancel = ::discardPreview,
                    onGenerate = {
                        val selected = profile ?: return@ImproveAskPhase
                        working = true
                        error = null
                        job = ui.scope.launch {
                            try {
                                when (val result = service.create(
                                    target.caseId, selected.id, note, selection.modelId, selection.reasoningEffort, target.runId,
                                    onUsage = { attemptUsage = it },
                                )) {
                                    is StoreResult.Ok -> review = ImproveReview(result.value, ui.library.scripts)
                                    else -> error = result.userMessage()
                                }
                            } finally {
                                working = false
                            }
                        }
                    },
                )
            } else {
                ImproveReviewPhase(
                    review = current,
                    working = working,
                    error = error,
                    onBack = {
                        job?.cancel()
                        service.discard(current.proposal.id)
                        review = null
                        error = null
                    },
                    onCancel = ::discardPreview,
                    onApply = {
                        working = true
                        error = null
                        job = ui.scope.launch {
                            try {
                                when (val result = service.apply(current.proposal.id, current.edits(), current.acceptedIds())) {
                                    is StoreResult.Ok -> {
                                        ui.report(result)
                                        review = null
                                        onDismiss()
                                    }
                                    else -> error = result.userMessage()
                                }
                            } finally {
                                working = false
                            }
                        }
                    },
                )
            }
        }
    }
}

@Suppress("LongParameterList") // The ask phase is a pure view of the dialog's state; each argument is one piece of it.
@Composable
private fun ImproveAskPhase(
    profileName: String?,
    evidenceLine: String?,
    blockedReason: String?,
    profilePicker: @Composable () -> Unit,
    note: String,
    onNote: (String) -> Unit,
    working: Boolean,
    usageLine: String?,
    error: String?,
    canGenerate: Boolean,
    onCancel: () -> Unit,
    onGenerate: () -> Unit,
) {
    val tc = tc()
    TestsHint(
        "The provider receives the case's steps, the suite and case text, the golden screenshots and, when the case has run, each " +
            "step's screenshots and the judge's reasoning and suggested fixes (never what the agent claimed). Nothing is changed until " +
            "you apply. Steps are never added, removed or reordered.",
        maxLines = 5,
    )
    Spacer(Modifier.height(8.dp))
    AppText("EVIDENCE TO SEND", color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    AppText(evidenceLine ?: "Reading the case's latest run…", color = tc.tx, fontSize = 11.sp)
    usageLine?.let { TestsHint(it) }
    Spacer(Modifier.height(10.dp))
    profilePicker()
    Spacer(Modifier.height(8.dp))
    TestsLabeled("What should be improved? (optional)") {
        ScrollableTextArea(
            note, onNote, placeholder = "For example: make the expected results independent of what the feed shows",
            modifier = Modifier.fillMaxWidth(), minHeight = NOTE_MIN_HEIGHT, maxHeight = NOTE_MAX_HEIGHT, enabled = !working,
        )
    }
    blockedReason?.let { TestsErrorText(it) }
    error?.let { TestsErrorText(it) }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        AppButton("Cancel", onClick = onCancel, variant = ButtonVariant.Ghost)
        HintedButton(
            if (working) "Improving…" else "Generate preview",
            onClick = onGenerate,
            enabled = canGenerate,
            disabledHint = blockedReason ?: if (profileName == null) "Choose a provider profile first." else "Working…",
            variant = ButtonVariant.Primary,
        )
    }
}

@Suppress("LongMethod") // The review is one list of step cards; splitting it would only move the parameters around.
@Composable
private fun ImproveReviewPhase(
    review: ImproveReview,
    working: Boolean,
    error: String?,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    val tc = tc()
    val proposal: TestCaseImproveProposal = review.proposal
    AppText(
        "${review.proposedChanges} of ${review.rows.size} step(s) would change. Untick a step to keep it as it is; edit the new text if needed.",
        color = tc.ts, fontSize = 11.sp,
    )
    TestsHint("Evidence sent · ${improveEvidenceLabel(proposal.evidence, explicitRun = proposal.runId != null)}")
    proposal.usage?.let { TestsHint("Usage · ${it.summaryLabel()}") }
    Spacer(Modifier.height(8.dp))
    review.rows.forEach { row ->
        val step = row.step
        Column(Modifier.fillMaxWidth().background(tc.p2, CORNER_MD).padding(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText("STEP ${step.number}", color = tc.ac, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                if (step.previous.kind != StepKind.AGENT) TestsBadge(step.previous.kind.name)
                if (row.changed) TestsBadge("CHANGED") else TestsBadge("UNCHANGED")
            }
            CheckRow(checked = row.accepted && row.changed, onToggle = { row.accepted = !row.accepted }, enabled = row.changed && !working) {
                AppText("Apply this step", color = tc.ts, fontSize = 10.sp)
            }
            if (row.changed) {
                ImproveWas("Was · action", step.previous.action)
                ImproveWas("Was · expected", step.previous.expected.ifBlank { "(blank)" })
            }
            TestsLabeled(if (step.previous.kind == StepKind.MANUAL) "Instructions for the tester" else "Action") {
                CommitTextField(row.action, { text -> row.action = text; StoreResult.Ok(Unit) }, enabled = !working)
            }
            TestsLabeled("Expected result") {
                CommitTextField(row.expected, { text -> row.expected = text; StoreResult.Ok(Unit) }, enabled = !working, multiline = true)
            }
            if (row.canBeOptional) {
                CheckRow(checked = row.optional, onToggle = { row.optional = !row.optional }, enabled = !working) {
                    AppText("Optional: skip when its condition is absent", color = tc.ts, fontSize = 9.sp)
                }
                if (row.optional) {
                    TestsLabeled("Perform only when") {
                        CommitTextField(row.condition, { text -> row.condition = text; StoreResult.Ok(Unit) }, enabled = !working, multiline = true)
                    }
                }
            }
            if (step.checks != step.previous.checks) {
                TestsHint("Its checks change too: ${checkSummary(step.previous.checks.size, step.checks.size)}.")
            }
            TestsHint("Why: ${step.reason}", maxLines = 4)
            row.warning?.let { AppText(it, color = tc.warn, fontSize = 9.sp) }
            row.problem?.let { TestsErrorText(it) }
        }
        Spacer(Modifier.height(6.dp))
    }
    error?.let { TestsErrorText(it) }
    review.blockedReason?.let { TestsHint(it) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        AppButton("Back", onClick = onBack, enabled = !working, variant = ButtonVariant.Ghost)
        AppButton("Cancel", onClick = onCancel, variant = ButtonVariant.Ghost)
        HintedButton(
            if (working) "Applying…" else review.applyLabel,
            onClick = onApply,
            enabled = !working && review.blockedReason == null,
            disabledHint = review.blockedReason,
            variant = ButtonVariant.Primary,
        )
    }
}

@Composable
private fun ImproveWas(label: String, text: String) {
    val tc = tc()
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        AppText(label, color = tc.td, fontSize = 9.sp)
        AppText(text, color = tc.td, fontSize = 11.sp)
    }
}

private fun checkSummary(before: Int, after: Int): String = "$before check(s) before, $after after"
