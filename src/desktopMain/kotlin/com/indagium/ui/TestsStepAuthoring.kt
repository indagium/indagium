package com.indagium.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.ai.normalizeAiProviderProfiles
import com.indagium.model.AiUsageStats
import com.indagium.model.WorkflowAiSelection
import com.indagium.model.summaryLabel
import com.indagium.testing.authoring.RecordedTestStep
import com.indagium.testing.authoring.RecordingPreviewEvidence
import com.indagium.testing.authoring.RecordingPreviewImage
import com.indagium.testing.authoring.ReopenedRecordingInfo
import com.indagium.testing.authoring.ReplacementPlan
import com.indagium.testing.authoring.TestStepDraft
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.contextHint
import com.indagium.testing.authoring.isExternalEvent
import com.indagium.testing.authoring.isSoftReviewWarning
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.previewLogChecks
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private data class StepInsertionPoint(val label: String, val index: Int)

private val DRAFT_DIALOG_WIDTH = 660.dp
private val DRAFT_DIALOG_MAX_HEIGHT = 760.dp
private val LOG_DIALOG_WIDTH = 620.dp
private val LOG_DIALOG_MAX_HEIGHT = 760.dp
private val AI_FIELD_WIDTH = 200.dp
private val AUTHORING_BUTTON_PADDING = 8.dp

/** Fixed authoring actions for the case's step list. Run and record setup live in the stable execution tile. */
@Composable
internal fun CaseStepsHeader(suiteId: String, case: TestCase, editable: Boolean, onAddStep: () -> Unit) {
    val ui = LocalTestsUi.current
    val step = case.steps.firstOrNull { it.id == ui.view.selectedStepId } ?: case.steps.firstOrNull()
    var draftOpen by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    var insertSharedOpen by remember { mutableStateOf(false) }
    val readOnlyHint = "This case is read-only."
    val addActions = buildList {
        add(TestsMenuItem("Draft steps with AI…", onClick = { draftOpen = true }, enabled = editable, disabledHint = readOnlyHint))
        add(
            TestsMenuItem(
                "Paste log lines as checks…", onClick = { logOpen = true },
                enabled = editable && step != null, disabledHint = if (!editable) readOnlyHint else "Add a step first.",
            ),
        )
        if (ui.library.sharedSteps.isNotEmpty()) {
            add(
                TestsMenuItem(
                    "Insert shared sequence…", onClick = { insertSharedOpen = true },
                    enabled = editable, disabledHint = readOnlyHint, dividerBefore = true,
                ),
            )
        }
    }
    val moreActions = listOf(
        TestsMenuItem(
            "Improve steps with AI…", onClick = { ui.view.improveDialog = ImproveStepsTarget(suiteId, case.id) },
            enabled = improveBlockedReason(case, editable) == null, disabledHint = improveBlockedReason(case, editable),
        ),
    )

    TestsSectionTitle("Steps (${case.steps.size})")
    // Every authoring action is a visible compact button; the row wraps at narrow widths. A disabled one keeps its reason as a tooltip.
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HintedButton(
            "+ Add step",
            onClick = {
                onAddStep()
                ui.reclaimFocus()
            },
            enabled = editable,
            disabledHint = readOnlyHint,
            horizontalPadding = AUTHORING_BUTTON_PADDING,
        )
        (addActions + moreActions).forEach { item ->
            HintedButton(
                item.label,
                onClick = {
                    item.onClick()
                    ui.reclaimFocus()
                },
                enabled = item.enabled,
                disabledHint = item.disabledHint,
                horizontalPadding = AUTHORING_BUTTON_PADDING,
            )
        }
    }
    if (draftOpen) TestStepDraftDialog(suiteId, case, onDismiss = { draftOpen = false; ui.reclaimFocus() })
    if (logOpen && step != null) LogChecksDialog(step.id, onDismiss = { logOpen = false; ui.reclaimFocus() })
    if (insertSharedOpen) InsertSharedDialog(case, step, onDismiss = { insertSharedOpen = false })
}

/** Inserts a library shared sequence into [case]: which sequence, and where (after the selected step by default). */
@Composable
private fun InsertSharedDialog(case: TestCase, selectedStep: TestStep?, onDismiss: () -> Unit) {
    val ui = LocalTestsUi.current
    val shared = ui.library.sharedSteps
    val selectedIndex = case.steps.indexOfFirst { it.id == selectedStep?.id }.coerceAtLeast(0)
    val insertionPoints = buildList {
        add(StepInsertionPoint("At end", case.steps.size))
        case.steps.forEachIndexed { index, row ->
            add(StepInsertionPoint("Before ${index + 1}: ${row.action.take(32)}", index))
            add(StepInsertionPoint("After ${index + 1}: ${row.action.take(32)}", index + 1))
        }
    }.distinctBy { it.index to it.label }
    var chosen by remember { mutableStateOf(shared.firstOrNull()) }
    var position by remember { mutableStateOf(StepInsertionPoint("After selected step", (selectedIndex + 1).coerceAtMost(case.steps.size))) }

    fun dismiss() {
        onDismiss()
        ui.reclaimFocus()
    }
    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TestsDialogFrame(
            title = "Insert shared sequence",
            body = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TestsLabeled("Shared sequence") {
                        TestsDropdown(
                            selectedLabel = chosen?.name ?: "Choose a shared sequence",
                            options = shared,
                            optionLabel = SharedStep::name,
                            onSelect = { chosen = it },
                            isSelected = { it.id == chosen?.id },
                            menuWidth = 260.dp,
                        )
                    }
                    TestsLabeled("Position") {
                        TestsDropdown(
                            selectedLabel = position.label,
                            options = insertionPoints,
                            optionLabel = StepInsertionPoint::label,
                            onSelect = { position = it },
                            isSelected = { it == position },
                            menuWidth = 260.dp,
                        )
                    }
                }
            },
            footer = {
                DialogActionButton("Insert", active = true, enabled = chosen != null) {
                    chosen?.let { ui.report(ui.state.insertSharedSteps(case.id, it.id, position.index)) }
                    dismiss()
                }
                DialogActionButton("Cancel", active = false) { dismiss() }
            },
        )
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
internal fun TestStepDraftDialog(suiteId: String, case: TestCase, onDismiss: () -> Unit) {
    val ui = LocalTestsUi.current
    val tc = tc()
    val profiles = normalizeAiProviderProfiles(ui.state.settings.aiProviderProfiles)
    val savedSelection = ui.state.settings.workflowAiSelections[AiWorkflow.TEST_STEP_DRAFT]
    var selection by remember(profiles, savedSelection) {
        mutableStateOf(resolveAiWorkflowSelection(ui.state.settings, AiWorkflow.TEST_STEP_DRAFT, profiles))
    }
    val profile = profiles.firstOrNull { it.id == selection.profileId }
    val catalog = remember { ModelCatalog() }
    var prompt by remember { mutableStateOf(case.description.takeIf(String::isNotBlank) ?: "") }
    var draft by remember { mutableStateOf<TestStepDraft?>(null) }
    var attemptUsage by remember { mutableStateOf<AiUsageStats?>(null) }
    var sessionUsage by remember { mutableStateOf<AiUsageStats?>(null) }
    val authoringSessionId = remember(case.id) { UUID.randomUUID().toString() }
    val editableSteps = remember(draft?.id) { mutableStateListOf<TestStep>().apply { draft?.steps?.let(::addAll) } }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var insertion by remember(case.id) { mutableStateOf(StepInsertionPoint("At end", case.steps.size)) }
    val insertionPoints = buildList {
        add(StepInsertionPoint("At end", case.steps.size))
        case.steps.forEachIndexed { index, step ->
            add(StepInsertionPoint("Before ${index + 1}: ${step.action.take(40)}", index))
            add(StepInsertionPoint("After ${index + 1}: ${step.action.take(40)}", index + 1))
        }
    }.distinctBy { it.index to it.label }

    fun discardPreview() {
        job?.cancel()
        draft?.let { ui.state.testStepDraftService.discard(it.id) }
        ui.state.testStepDraftService.discardAuthoringSession(authoringSessionId)
        onDismiss()
    }

    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            draft?.let { ui.state.testStepDraftService.discard(it.id) }
            ui.state.testStepDraftService.discardAuthoringSession(authoringSessionId)
        }
    }

    Dialog(onDismissRequest = ::discardPreview, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TestsDialogFrame(
            title = "Draft steps with AI",
            width = DRAFT_DIALOG_WIDTH,
            maxHeight = DRAFT_DIALOG_MAX_HEIGHT,
            status = { error?.let { TestsErrorText(it, Modifier.padding(top = 8.dp)) } },
            body = {
                TestsHint("Generation only creates this preview. It has no device controls and does not change the library until you apply it.")
                if (attemptUsage != null) {
                    Spacer(Modifier.height(5.dp))
                    AppText("Latest attempt · ${attemptUsage?.summaryLabel()}", color = tc.td, fontSize = 10.sp)
                }
                sessionUsage?.let { AppText("Creation session total · ${it.summaryLabel()}", color = tc.td, fontSize = 10.sp) }
                Spacer(Modifier.height(12.dp))
                if (draft == null) {
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
                                    ui.state.rememberAiWorkflowSelection(AiWorkflow.TEST_STEP_DRAFT, selection)
                                },
                                modifier = Modifier.width(AI_FIELD_WIDTH),
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
                                        ui.state.rememberAiWorkflowSelection(AiWorkflow.TEST_STEP_DRAFT, selection)
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TestsLabeled("What should the added steps do?") {
                        DictatedTextArea(prompt, { prompt = it }, proseTargetId = "test-case:${case.id}:draft-steps-prompt", modifier = Modifier.fillMaxWidth(), minHeight = 90.dp, maxHeight = 180.dp)
                    }
                    if (working) {
                        Spacer(Modifier.height(10.dp))
                        AiWorkProgress("Drafting steps", profile?.displayName, selection.modelId ?: profile?.model, stage = null)
                    }
                } else {
                    AppText("Review and edit ${editableSteps.size} proposed step(s).", color = tc.ts, fontSize = 11.sp)
                    draft?.creationUsage?.let { usage ->
                        Spacer(Modifier.height(4.dp))
                        AppText("Creation session total · ${usage.summaryLabel()}", color = tc.td, fontSize = 10.sp)
                    }
                    draft?.latestUsage?.let { usage -> AppText("Latest draft attempt · ${usage.summaryLabel()}", color = tc.td, fontSize = 10.sp) }
                    TestsDropdown(
                        selectedLabel = insertion.label,
                        options = insertionPoints,
                        optionLabel = StepInsertionPoint::label,
                        onSelect = { insertion = it },
                        isSelected = { it == insertion },
                        menuWidth = 380.dp,
                    )
                    Spacer(Modifier.height(8.dp))
                    editableSteps.forEachIndexed { index, step ->
                        Column(Modifier.fillMaxWidth().background(tc.p2, CORNER_MD).padding(9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppText("Step ${index + 1}", color = tc.ac, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                AppButton("Remove", onClick = { editableSteps.removeAt(index) }, variant = ButtonVariant.Ghost)
                            }
                            TestsLabeled("Action") {
                                CommitTextField(step.action, { value -> editableSteps[index] = editableSteps[index].copy(action = value); StoreResult.Ok(Unit) }, placeholder = "What the tester does", proseTargetId = "test-step:${step.id}:action")
                            }
                            TestsLabeled("Expected result") {
                                CommitTextField(step.expected, { value -> editableSteps[index] = editableSteps[index].copy(expected = value); StoreResult.Ok(Unit) }, multiline = true, placeholder = "What should happen", proseTargetId = "test-step:${step.id}:expected")
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            },
            footer = {
                if (draft == null) {
                    DialogActionButton(
                        if (working) "Drafting…" else "Generate preview",
                        active = true,
                        enabled = !working && profile != null && prompt.isNotBlank(),
                    ) {
                        val selected = profile ?: return@DialogActionButton
                        working = true
                        error = null
                        job = ui.scope.launch {
                            try {
                                when (val result = ui.state.testStepDraftService.create(
                                    suiteId, case.id, selected.id, prompt, selection.modelId, selection.reasoningEffort,
                                    authoringSessionId = authoringSessionId,
                                    onUsage = { latest, total -> attemptUsage = latest; sessionUsage = total },
                                )) {
                                    is StoreResult.Ok -> draft = result.value
                                    else -> error = result.userMessage()
                                }
                            } finally {
                                working = false
                            }
                        }
                    }
                    DialogActionButton("Cancel", active = false) { discardPreview() }
                } else {
                    DialogActionButton(
                        if (working) "Applying…" else "Apply to case",
                        active = true,
                        enabled = !working && editableSteps.isNotEmpty() && editableSteps.all { it.action.isNotBlank() },
                    ) {
                        val activeDraft = draft ?: return@DialogActionButton
                        working = true
                        job = ui.scope.launch {
                            try {
                                when (val result = ui.state.testStepDraftService.apply(activeDraft.id, editableSteps.toList(), insertion.index)) {
                                    is StoreResult.Ok -> {
                                        ui.report(result)
                                        onDismiss()
                                    }
                                    else -> error = result.userMessage()
                                }
                            } finally {
                                working = false
                            }
                        }
                    }
                    DialogActionButton("Back", active = false) {
                        job?.cancel()
                        draft?.let { ui.state.testStepDraftService.discard(it.id) }
                        draft = null
                        error = null
                    }
                    DialogActionButton("Cancel", active = false) { discardPreview() }
                }
            },
        )
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun LogChecksDialog(stepId: String, onDismiss: () -> Unit) {
    val ui = LocalTestsUi.current
    val tc = tc()
    var text by remember { mutableStateOf("") }
    val preview = remember(text) { previewLogChecks(text) }
    val editableChecks = remember(preview) { mutableStateListOf<StepCheck.LogAppears>().apply { addAll(preview.checks.map { it.check }) } }
    var insertion by remember(stepId) { mutableStateOf(StepInsertionPoint("At end", Int.MAX_VALUE)) }
    val found = ui.library.findStep(stepId)?.step
    val checkPositions = buildList {
        add(StepInsertionPoint("At end", found?.checks?.size ?: 0))
        found?.checks?.forEachIndexed { index, check ->
            add(StepInsertionPoint("Before ${index + 1}: ${check::class.simpleName}", index))
            add(StepInsertionPoint("After ${index + 1}: ${check::class.simpleName}", index + 1))
        }
    }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TestsDialogFrame(
            title = "Paste log lines as checks",
            width = LOG_DIALOG_WIDTH,
            maxHeight = LOG_DIALOG_MAX_HEIGHT,
            status = { error?.let { TestsErrorText(it, Modifier.padding(top = 8.dp)) } },
            body = {
                TestsHint("Android log tags are detected when available. Messages are regex-escaped literally; each check uses the default wait duration.")
                Spacer(Modifier.height(8.dp))
                ScrollableTextArea(text, { text = it }, modifier = Modifier.fillMaxWidth(), minHeight = 120.dp, maxHeight = 250.dp)
                Spacer(Modifier.height(8.dp))
                AppText("Preview · ${preview.checks.size} checks", color = tc.ac, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                TestsScrollColumn(Modifier.fillMaxWidth().heightIn(max = 210.dp)) {
                    editableChecks.forEachIndexed { index, check ->
                        Column(Modifier.fillMaxWidth().background(tc.p2, CORNER_SM).padding(6.dp)) {
                            TestsLabeled("Tag (optional)") { CommitTextField(check.tag.orEmpty(), { value -> editableChecks[index] = check.copy(tag = value.trim().ifEmpty { null }); StoreResult.Ok(Unit) }, placeholder = "Any tag") }
                            TestsLabeled("Literal message regex") { CommitTextField(check.regex, { value -> editableChecks[index] = check.copy(regex = value); StoreResult.Ok(Unit) }, mono = true) }
                            TestsLabeled("Wait (ms)") { CommitNumberField(check.withinMs, 1L, 300_000L, { value -> editableChecks[index] = check.copy(withinMs = value); StoreResult.Ok(Unit) }) }
                        }
                    }
                    preview.warnings.forEach { warning -> AppText(warning, color = tc.warn, fontSize = 10.sp) }
                }
                Spacer(Modifier.height(8.dp))
                TestsDropdown(
                    selectedLabel = insertion.label,
                    options = checkPositions,
                    optionLabel = StepInsertionPoint::label,
                    onSelect = { insertion = it },
                    isSelected = { it.index == insertion.index },
                    menuWidth = 300.dp,
                )
            },
            footer = {
                DialogActionButton("Insert checks", active = true, enabled = preview.checks.isNotEmpty()) {
                    when (val result = ui.state.insertTestChecks(stepId, editableChecks.toList(), insertion.index.takeIf { it != Int.MAX_VALUE })) {
                        is StoreResult.Ok -> { ui.report(result); onDismiss() }
                        else -> error = result.userMessage()
                    }
                }
                DialogActionButton("Cancel", active = false) { onDismiss() }
            },
        )
    }
}

internal const val APPLY_AS_REPLAY_LABEL = "Replay all eligible steps"

/** What the replay option does for this recording, in one sentence. */
internal fun replayOptionHint(replayable: Int, steps: Int, selected: Int = 0): String =
    if (replayable > 0) {
        "$selected selected for replay; $replayable of $steps step(s) are eligible. Choose Replay this step on individual rows, " +
            "or select all here. Replay scales recorded coordinates but does not adapt to a changed layout. Other recorded steps use AI."
    } else {
        "No step can be replayed (typed text, rotation, an optional or precondition step, or input withheld for a password); all stay AI steps."
    }

/** What a reopened recording says about itself: where it came from, what was left out, and which steps applying it replaces. */
@Composable
private fun ReopenedRecordingNotes(info: ReopenedRecordingInfo, replacement: ReplacementPlan?) {
    val tc = tc()
    TestsHint(
        "Reopened from the evidence saved on ${java.time.Instant.ofEpochMilli(info.createdAtMs)} " +
            "(${if (info.videoStored) "with video" else "without video"}). No device is needed. Rewrite with AI, edit and reorder work as " +
            "for a new recording.",
    )
    info.notes.forEach { AppText(it, color = tc.warn, fontSize = 10.sp) }
    when (replacement) {
        is ReplacementPlan.Replace ->
            TestsHint("Applying replaces the ${replacement.stepIds.size} step(s) this recording made, in place. Discarding changes nothing.")
        is ReplacementPlan.Ambiguous -> TestsErrorText("${replacement.reason} You can insert the reviewed steps as new steps instead; the old ones stay.")
        null -> Unit
    }
}

@Composable
internal fun RecordedEvidencePreview(session: TestStepRecordingSession, row: RecordedTestStep, editable: Boolean) {
    val tc = tc()
    val selected by produceState<RecordingPreviewEvidence?>(null, session.id, row) {
        value = withContext(Dispatchers.IO) { session.previewEvidenceFor(row.id) }
    }
    val evidence = selected ?: run {
        TestsHint("Preparing recording evidence preview…")
        return
    }
    TestsHint(evidence.uiContextMessage)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        evidence.before?.let { PreviewFrameColumn("Before", it) }
        evidence.after?.let { PreviewFrameColumn("After", it) }
        evidence.rawInput?.let { PreviewFrameColumn("Input preview", it) }
    }
    evidence.imageAvailabilityMessage?.let { TestsHint(it, maxLines = 3) }
    evidence.after?.takeIf { it.eligibleAsExpectedScreenshot }?.let { after ->
        CheckRow(checked = row.useScreenshotAsExpected, onToggle = {
            val use = !row.useScreenshotAsExpected
            updateRecorded(session, row.id) {
                it.copy(
                    useScreenshotAsExpected = use,
                    expectedScreenshotJpeg = after.jpeg.takeIf { use },
                    expectedScreenshotSource = after.source.takeIf { use },
                    expectedScreenshotTimestampMs = after.timestampMs.takeIf { use },
                    expectedScreenshotAlignment = after.alignmentConfidence.takeIf { use },
                )
            }
        }, enabled = editable) {
            AppText("Use this eligible after image as an expected screenshot (review first)", color = tc.ts, fontSize = 10.sp)
        }
    }
}

/** The selected in-memory recording row shown in the Details tile while recording review is explicitly active. */
@Composable
internal fun TestsRecordingReviewDetails() {
    val ui = LocalTestsUi.current
    val session = ui.state.testStepRecordingSession
    TestsScrollColumn(Modifier.fillMaxSize(), contentModifier = Modifier.padding(12.dp)) {
        if (!ui.view.recordingReviewOpen || session == null) {
            TestsHint("Select a recorded step to review its action and expected result.")
        } else {
            RecordingReviewDetailsContent(session)
        }
    }
}

@Composable
private fun RecordingReviewDetailsContent(session: TestStepRecordingSession) {
    val ui = LocalTestsUi.current
    val tc = tc()
    val snapshot by session.snapshot.collectAsState()
    val rowIndex = snapshot.steps.indexOfFirst { it.id == ui.view.recordingSelectedRowId }.takeIf { it >= 0 }
        ?: snapshot.steps.indices.firstOrNull()
    val row = rowIndex?.let(snapshot.steps::getOrNull)
    LaunchedEffect(session.id, snapshot.steps.map { it.id }, row?.id) {
        if (row?.id != ui.view.recordingSelectedRowId) ui.view.recordingSelectedRowId = row?.id
    }
    if (row == null) {
        TestsInspectorHeader("Recording draft", "No steps to review", Modifier.fillMaxWidth())
        TestsHint(if (snapshot.excludedSourceInputIds.isNotEmpty()) "Restore removed inputs from the Execution tile." else "No input has been recorded yet.")
        return
    }
    val target = ui.state.testStepRecordingTarget
    val case = target?.second?.let(ui.library::findCase)?.case
    val editable = !snapshot.active && snapshot.pendingSnapshots == 0 &&
        !ui.state.isTestStepRecordingApplying(session.id) && !ui.state.isTestStepRecordingRewriting(session.id)
    val replacement = remember(case?.steps?.map { it.id }, session.id) { ui.state.reopenedReplacementPlan(session.id) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TestsInspectorHeader("Recorded draft", "Step ${rowIndex + 1}", Modifier.weight(1f))
            AppButton("Close review", onClick = { ui.view.recordingReviewOpen = false; ui.reclaimFocus() }, variant = ButtonVariant.Ghost)
        }
        if (target != null) {
            AppText(
                ui.library.operationTargetLabel(TestsOperationTarget(target.first, target.second)),
                color = tc.td, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (snapshot.active) TestsHint("Stop the recording before editing the draft.")
        if (snapshot.pendingSnapshots > 0) TestsHint("Wait for the recorded screen snapshots to finish before editing the draft.")
        case?.let { currentCase ->
            snapshot.reopened?.let { ReopenedRecordingNotes(it, replacement) }
            val replayable = remember(snapshot.steps, snapshot.active) { if (snapshot.active) emptySet() else session.replayableRowIds() }
            if (row.authorAdded) RecordedRowKindEditor(session, row, editable)
            if (row.isExternalEvent) {
                TestsBadge("Detected on the device")
                ExternalEventRowEditor(session, row, editable)
            }
            if (row.stepKind == StepKind.AGENT && !row.authorAdded) {
                val canReplay = row.id in replayable
                CheckRow(
                    checked = row.id in ui.view.recordingReplayRowIds,
                    onToggle = {
                        ui.view.recordingReplayRowIds = if (row.id in ui.view.recordingReplayRowIds) {
                            ui.view.recordingReplayRowIds - row.id
                        } else {
                            ui.view.recordingReplayRowIds + row.id
                        }
                    },
                    enabled = editable && canReplay,
                ) { AppText("Replay this recorded step", color = tc.ts, fontSize = 10.sp) }
                TestsHint(
                    if (canReplay) {
                        "Replay uses the recorded gesture on a similar layout; turn it off to let AI adapt."
                    } else {
                        "This input cannot be replayed; AI will adapt it to the current screen."
                    },
                )
            }
            TestsLabeled(if (row.stepKind == StepKind.MANUAL) "Instructions for the tester" else "Action") {
                CommitTextField(
                    row.action,
                    { text -> updateRecorded(session, row.id) { it.copy(action = text) }; StoreResult.Ok(Unit) },
                    enabled = editable,
                    placeholder = if (row.stepKind == StepKind.MANUAL) "What the tester should do" else "What the agent should do",
                    proseTargetId = "recorded-step:${row.id}:action",
                )
            }
            TestsLabeled("Expected result") {
                CommitTextField(
                    row.expected,
                    { text -> updateRecorded(session, row.id) { it.copy(expected = text) }; StoreResult.Ok(Unit) },
                    enabled = editable,
                    multiline = true,
                    placeholder = if (row.optional) "May be blank for an optional action" else "What should happen",
                    proseTargetId = "recorded-step:${row.id}:expected",
                )
            }
            if (row.stepKind == StepKind.AGENT) {
                CheckRow(checked = row.optional, onToggle = {
                    updateRecorded(session, row.id) { if (it.optional) it.copy(optional = false, condition = null) else it.copy(optional = true) }
                }, enabled = editable) {
                    AppText("Optional: skip when its condition is absent", color = tc.ts, fontSize = 10.sp)
                }
            }
            if (row.optional) {
                TestsLabeled("Perform only when") {
                    CommitTextField(
                        row.condition.orEmpty(),
                        { text -> updateRecorded(session, row.id) { it.copy(condition = text) }; StoreResult.Ok(Unit) },
                        enabled = editable,
                        multiline = true,
                        placeholder = "Describe the visible condition",
                        proseTargetId = "recorded-step:${row.id}:condition",
                    )
                }
            }
            if (row.sourceInputIds.isNotEmpty()) {
                RecordedInputsList(row, snapshot.rawSteps.orEmpty())
                if (row.checks.isNotEmpty()) TestsHint("${row.checks.size} check(s) proposed by AI will be added to this step.")
            }
            if (row.stateChanges.isNotEmpty() && row.sourceInputIds.isEmpty()) RecordedStateChangesHint(session, row, editable)
            row.contextHint()?.let { TestsHint("UI/activity context: $it") }
            row.screenContext?.let { TestsHint(it) }
            row.reviewReason?.let {
                when {
                    row.expected.isBlank() -> TestsErrorText("Review required: $it")
                    isSoftReviewWarning(it) -> AppText(it, color = tc.warn, fontSize = 10.sp)
                    else -> TestsHint("Recording note: $it")
                }
            }
            if (currentCase.steps.isEmpty()) TestsHint("This draft will add steps to ${currentCase.name} when you apply it.")
        } ?: TestsErrorText("The case this recording belongs to was deleted. This draft remains pinned to that target.")
        if (snapshot.warnings.isNotEmpty()) snapshot.warnings.forEach { TestsErrorText(it) }
    }
}

/** The selected draft row's bounded images and expected-screenshot choice, shown in the Evidence tile during review. */
@Composable
internal fun TestsRecordingReviewEvidence() {
    val ui = LocalTestsUi.current
    val session = ui.state.testStepRecordingSession
    TestsScrollColumn(Modifier.fillMaxSize(), contentModifier = Modifier.padding(12.dp)) {
        if (!ui.view.recordingReviewOpen || session == null) {
            TestsHint("Recorded evidence appears here while a draft step is selected.")
        } else {
            val snapshot by session.snapshot.collectAsState()
            val row = snapshot.steps.firstOrNull { it.id == ui.view.recordingSelectedRowId } ?: snapshot.steps.firstOrNull()
            if (row == null) {
                TestsHint("No recorded evidence is available for this draft yet.")
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TestsInspectorHeader("Recorded evidence", row.action.ifBlank { "Step ${snapshot.steps.indexOf(row) + 1}" }, Modifier.fillMaxWidth())
                    RecordedEvidencePreview(
                        session,
                        row,
                        editable = !snapshot.active && snapshot.pendingSnapshots == 0 &&
                            !ui.state.isTestStepRecordingApplying(session.id) && !ui.state.isTestStepRecordingRewriting(session.id),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewFrameColumn(title: String, evidence: RecordingPreviewImage) {
    Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        AppText(title, color = tc().tx, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        RecordedPreviewImageThumb(evidence.jpeg, "$title recording evidence")
        AppText(
            buildString {
                append(evidence.source)
                evidence.timestampMs?.let { append(" · t=").append(it).append(" ms") }
                append(" · ").append(evidence.alignmentConfidence)
            },
            color = tc().td,
            fontSize = 10.sp,
            maxLines = 3,
        )
    }
}

@Composable
private fun RecordedPreviewImageThumb(bytes: ByteArray, description: String) {
    val bitmap by produceState<ImageBitmap?>(null, bytes) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                decodeBoundedPreviewImage(bytes, 4_000_000L)
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it, contentDescription = description, contentScale = ContentScale.Fit, modifier = Modifier.width(150.dp).heightIn(max = 180.dp)) }
}

internal fun updateRecorded(session: TestStepRecordingSession, rowId: String, transform: (RecordedTestStep) -> RecordedTestStep) {
    val current = session.snapshot.value.steps.toMutableList()
    val index = current.indexOfFirst { it.id == rowId }
    if (index >= 0) {
        current[index] = transform(current[index])
        session.replaceSteps(current)
    }
}
