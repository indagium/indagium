package com.indagium.ui

import androidx.compose.foundation.Image
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.ai.normalizeAiProviderProfiles
import com.indagium.capture.CaptureDevice
import com.indagium.model.WorkflowAiSelection
import com.indagium.testing.authoring.RecordedTestStep
import com.indagium.testing.authoring.TestStepDraft
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.contextHint
import com.indagium.testing.authoring.recordingApplyBlockedReason
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.previewLogChecks
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class StepInsertionPoint(val label: String, val index: Int)

@Composable
internal fun CaseAuthoringActions(suiteId: String, case: TestCase, editable: Boolean) {
    val ui = LocalTestsUi.current
    val tc = tc()
    val step = case.steps.firstOrNull { it.id == ui.view.expandedStepId } ?: case.steps.firstOrNull()
    val selectedIndex = case.steps.indexOfFirst { it.id == step?.id }.coerceAtLeast(0)
    val insertionPoints = buildList {
        add(StepInsertionPoint("At end", case.steps.size))
        case.steps.forEachIndexed { index, row ->
            add(StepInsertionPoint("Before ${index + 1}: ${row.action.take(32)}", index))
            add(StepInsertionPoint("After ${index + 1}: ${row.action.take(32)}", index + 1))
        }
    }.distinctBy { it.index to it.label }
    val shared = ui.library.sharedSteps
    var selectedShared by remember(shared.map { it.id }) { mutableStateOf(shared.firstOrNull()) }
    var sharedPosition by remember(case.id, case.steps.map { it.id }) {
        mutableStateOf(StepInsertionPoint("After selected step", (selectedIndex + 1).coerceAtMost(case.steps.size)))
    }
    var draftOpen by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    var recordError by remember(suiteId, case.id) { mutableStateOf<String?>(null) }
    var authoringDevices by remember(suiteId, case.id) { mutableStateOf<List<CaptureDevice>>(emptyList()) }
    var selectedAuthoringSerial by remember(suiteId, case.id) { mutableStateOf<String?>(null) }
    var preparingRecording by remember(suiteId, case.id) { mutableStateOf(false) }
    var recordingPreparationJob by remember(suiteId, case.id) { mutableStateOf<Job?>(null) }

    DisposableEffect(suiteId, case.id) {
        onDispose { recordingPreparationJob?.cancel() }
    }

    TestsSectionTitle("Step authoring")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HintedButton("Draft steps with AI", onClick = { draftOpen = true }, enabled = editable, disabledHint = "This case is read-only.")
        if (authoringDevices.size > 1) {
            TestsDropdown(
                selectedLabel = authoringDevices.firstOrNull { it.serial == selectedAuthoringSerial }?.let(::recordingDeviceLabel)
                    ?: "Choose device",
                options = authoringDevices,
                optionLabel = ::recordingDeviceLabel,
                onSelect = { selectedAuthoringSerial = it.serial },
                enabled = editable && !preparingRecording,
                isSelected = { it.serial == selectedAuthoringSerial },
                menuWidth = 240.dp,
            )
        }
        HintedButton(
            if (preparingRecording) "Connecting…" else "Record from device",
            onClick = {
                if (preparingRecording) return@HintedButton
                recordingPreparationJob = ui.scope.launch {
                    preparingRecording = true
                    try {
                        handleAuthoringPreparationFailure({ recordError = it }) {
                            authoringDevices = withContext(Dispatchers.IO) { ui.state.discoverTestStepRecordingDevices() }
                            val preferredSerial = ui.state.liveCaptureSerial()
                            val serial = selectedAuthoringSerial?.takeIf { selected -> authoringDevices.any { it.serial == selected } }
                                ?: authoringDevices.firstOrNull { it.serial == preferredSerial }?.serial
                                ?: authoringDevices.singleOrNull()?.serial
                            selectedAuthoringSerial = serial
                            if (authoringDevices.isEmpty()) {
                                recordError = "No connected Android devices are available. Connect or authorize a device, then try again."
                            } else if (serial == null) {
                                recordError = "Choose the device to record. The Tests workspace will stay open while its mirror connects."
                            } else {
                                startAuthoringRecording(serial, ui, suiteId, case.id) { recordError = it }
                            }
                        }
                    } finally {
                        preparingRecording = false
                        recordingPreparationJob = null
                    }
                }
            },
            enabled = editable && !preparingRecording,
            disabledHint = if (!editable) "This case is read-only." else "Preparing the device mirror.",
        )
        if (preparingRecording) {
            AppButton("Cancel", onClick = { recordingPreparationJob?.cancel() }, variant = ButtonVariant.Ghost)
        }
        if (shared.isNotEmpty()) {
            TestsDropdown(
                selectedLabel = selectedShared?.name ?: "Shared sequence",
                options = shared,
                optionLabel = SharedStep::name,
                onSelect = { selectedShared = it },
                enabled = editable,
                isSelected = { it.id == selectedShared?.id },
                menuWidth = 260.dp,
            )
            TestsDropdown(
                selectedLabel = sharedPosition.label,
                options = insertionPoints,
                optionLabel = StepInsertionPoint::label,
                onSelect = { sharedPosition = it },
                enabled = editable,
                isSelected = { it == sharedPosition },
                menuWidth = 260.dp,
            )
            HintedButton(
                "Insert shared",
                onClick = {
                    selectedShared?.let { chosen ->
                        ui.report(ui.state.insertSharedSteps(case.id, chosen.id, sharedPosition.index))
                    }
                },
                enabled = editable && selectedShared != null,
                disabledHint = if (selectedShared == null) "Choose a shared sequence first." else "This case is read-only.",
            )
        }
        HintedButton(
            "Paste log lines as checks",
            onClick = { logOpen = true },
            enabled = editable && step != null,
            disabledHint = if (!editable) "This case is read-only." else "Add a step first.",
        )
    }
    recordError?.let { TestsErrorText(it) }
    Spacer(Modifier.height(8.dp))
    if (draftOpen) TestStepDraftDialog(suiteId, case, onDismiss = { draftOpen = false })
    if (logOpen && step != null) LogChecksDialog(step.id, onDismiss = { logOpen = false })
    val recordingTarget = ui.state.testStepRecordingTarget
    if (recordingTarget == (suiteId to case.id)) {
        ui.state.testStepRecordingSession?.let { session ->
            TestStepRecordingPanel(session, case, onDismiss = { ui.state.clearTestStepRecording(session.id) })
        }
    }
}

private fun recordingDeviceLabel(device: CaptureDevice): String =
    if (device.model == device.serial) device.serial else "${device.model} (${device.serial})"

/** ADB/scrcpy setup failures have several platform-specific wrappers; cancellation still propagates to the UI job. */
@Suppress("TooGenericExceptionCaught")
private suspend fun handleAuthoringPreparationFailure(onFailure: (String) -> Unit, action: suspend () -> Unit) {
    try {
        action()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onFailure("Could not prepare recording: ${failure.message ?: failure::class.simpleName}")
    }
}

private suspend fun startAuthoringRecording(
    serial: String?,
    ui: TestsUi,
    suiteId: String,
    caseId: String,
    onError: (String?) -> Unit,
) {
    if (serial == null) {
        onError("Choose the device to record.")
        return
    }
    when (val started = ui.state.prepareTestStepRecording(serial, suiteId, caseId)) {
        is StoreResult.Ok -> onError(null)
        else -> onError(started.userMessage())
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun TestStepDraftDialog(suiteId: String, case: TestCase, onDismiss: () -> Unit) {
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
        onDismiss()
    }

    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            draft?.let { ui.state.testStepDraftService.discard(it.id) }
        }
    }

    Dialog(onDismissRequest = ::discardPreview, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(660.dp).background(tc.p, CORNER_MD).border(1.dp, tc.br, CORNER_MD).padding(18.dp)
                .heightIn(max = 760.dp).verticalScroll(rememberScrollState()),
        ) {
            AppText("Draft steps with AI", color = tc.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            TestsHint("Generation only creates this preview. It has no device controls and does not change the library until you apply it.")
            Spacer(Modifier.height(12.dp))
            if (draft == null) {
                TestsLabeled("Provider profile") {
                    TestsDropdown(
                        selectedLabel = profile?.displayName ?: "Choose a configured profile",
                        options = profiles,
                        optionLabel = { it.displayName },
                        onSelect = {
                            selection = WorkflowAiSelection(profileId = it.id)
                            ui.state.rememberAiWorkflowSelection(AiWorkflow.TEST_STEP_DRAFT, selection)
                        },
                        isSelected = { it.id == profile?.id },
                        emptyText = "Configure a provider profile in Settings first",
                        menuWidth = 320.dp,
                    )
                }
                profile?.let { chosen ->
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Spacer(Modifier.height(8.dp))
                TestsLabeled("What should the added steps do?") {
                    ScrollableTextArea(prompt, { prompt = it }, modifier = Modifier.fillMaxWidth(), minHeight = 90.dp, maxHeight = 180.dp)
                }
                error?.let { TestsErrorText(it) }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    AppButton("Cancel", onClick = ::discardPreview, variant = ButtonVariant.Ghost)
                    AppButton(
                        if (working) "Drafting…" else "Generate preview",
                        onClick = {
                            val selected = profile ?: return@AppButton
                            working = true
                            error = null
                            job = ui.scope.launch {
                                try {
                                    when (val result = ui.state.testStepDraftService.create(
                                        suiteId, case.id, selected.id, prompt, selection.modelId, selection.reasoningEffort,
                                    )) {
                                        is StoreResult.Ok -> draft = result.value
                                        else -> error = result.userMessage()
                                    }
                                } finally {
                                    working = false
                                }
                            }
                        },
                        enabled = !working && profile != null && prompt.isNotBlank(),
                        variant = ButtonVariant.Primary,
                    )
                }
            } else {
                AppText("Review and edit ${editableSteps.size} proposed step(s).", color = tc.ts, fontSize = 11.sp)
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
                            AppText("STEP ${index + 1}", color = tc.ac, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            AppButton("Remove", onClick = { editableSteps.removeAt(index) }, variant = ButtonVariant.Ghost)
                        }
                        TestsLabeled("Action") {
                            CommitTextField(step.action, { value -> editableSteps[index] = editableSteps[index].copy(action = value); StoreResult.Ok(Unit) }, placeholder = "What the tester does")
                        }
                        TestsLabeled("Expected result") {
                            CommitTextField(step.expected, { value -> editableSteps[index] = editableSteps[index].copy(expected = value); StoreResult.Ok(Unit) }, multiline = true, placeholder = "What should happen")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                error?.let { TestsErrorText(it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    AppButton("Back", onClick = { job?.cancel(); draft?.let { ui.state.testStepDraftService.discard(it.id) }; draft = null; error = null }, variant = ButtonVariant.Ghost)
                    AppButton("Cancel", onClick = ::discardPreview, variant = ButtonVariant.Ghost)
                    AppButton(
                        if (working) "Applying…" else "Apply to case",
                        onClick = {
                            val activeDraft = draft ?: return@AppButton
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
                        },
                        enabled = !working && editableSteps.isNotEmpty() && editableSteps.all { it.action.isNotBlank() },
                        variant = ButtonVariant.Primary,
                    )
                }
            }
        }
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
        Column(Modifier.width(620.dp).background(tc.p, CORNER_MD).border(1.dp, tc.br, CORNER_MD).padding(18.dp)) {
            AppText("Paste log lines as checks", color = tc.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            TestsHint("Android log tags are detected when available. Messages are regex-escaped literally; each check uses the default wait duration.")
            Spacer(Modifier.height(8.dp))
            ScrollableTextArea(text, { text = it }, modifier = Modifier.fillMaxWidth(), minHeight = 120.dp, maxHeight = 250.dp)
            Spacer(Modifier.height(8.dp))
            AppText("PREVIEW · ${preview.checks.size} checks", color = tc.ac, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Column(Modifier.fillMaxWidth().heightIn(max = 210.dp).verticalScroll(rememberScrollState())) {
                editableChecks.forEachIndexed { index, check ->
                    Column(Modifier.fillMaxWidth().background(tc.p2, CORNER_SM).padding(6.dp)) {
                        TestsLabeled("Tag (optional)") { CommitTextField(check.tag.orEmpty(), { value -> editableChecks[index] = check.copy(tag = value.trim().ifEmpty { null }); StoreResult.Ok(Unit) }, placeholder = "Any tag") }
                        TestsLabeled("Literal message regex") { CommitTextField(check.regex, { value -> editableChecks[index] = check.copy(regex = value); StoreResult.Ok(Unit) }, mono = true) }
                        TestsLabeled("Wait (ms)") { CommitNumberField(check.withinMs, 1L, 300_000L, { value -> editableChecks[index] = check.copy(withinMs = value); StoreResult.Ok(Unit) }) }
                    }
                }
                preview.warnings.forEach { warning -> AppText(warning, color = tc.warn, fontSize = 9.sp) }
            }
            error?.let { TestsErrorText(it) }
            TestsDropdown(
                selectedLabel = insertion.label,
                options = checkPositions,
                optionLabel = StepInsertionPoint::label,
                onSelect = { insertion = it },
                isSelected = { it.index == insertion.index },
                menuWidth = 300.dp,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                AppButton("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost)
                AppButton("Insert checks", onClick = {
                    when (val result = ui.state.insertTestChecks(stepId, editableChecks.toList(), insertion.index.takeIf { it != Int.MAX_VALUE })) {
                        is StoreResult.Ok -> { ui.report(result); onDismiss() }
                        else -> error = result.userMessage()
                    }
                }, enabled = preview.checks.isNotEmpty(), variant = ButtonVariant.Primary)
            }
        }
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun TestStepRecordingPanel(session: TestStepRecordingSession, case: TestCase, onDismiss: () -> Unit) {
    val ui = LocalTestsUi.current
    val tc = tc()
    val snapshot by session.snapshot.collectAsState()
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val rewriting = ui.state.isTestStepRecordingRewriting(session.id)
    val applying = working || ui.state.isTestStepRecordingApplying(session.id)
    val applyBlockedReason = recordingApplyBlockedReason(snapshot)
    Column(Modifier.fillMaxWidth().background(tc.p, CORNER_MD).border(1.dp, tc.br, CORNER_MD).padding(14.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
        AppText(if (snapshot.active) "Recording device input…" else "Review recorded steps", color = tc.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        TestsHint("Device ${session.deviceSerial}. Tap, swipe, key and text input are observed through the existing mirror. No device actions are sent by this recorder.")
        if (snapshot.active) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                AppButton("Cancel recording", onClick = { session.stop(); onDismiss() }, variant = ButtonVariant.Ghost)
                AppButton("Stop and review", onClick = { session.stop() }, variant = ButtonVariant.Primary)
            }
        }
        if (snapshot.steps.isEmpty()) TestsHint("No accepted mirror input recorded yet.")
        if (!snapshot.active && snapshot.steps.isNotEmpty()) RecordingRewriteSection(session, case, snapshot, locked = applying)
        snapshot.steps.forEachIndexed { index, row ->
            Column(Modifier.fillMaxWidth().background(tc.p2, CORNER_MD).padding(8.dp)) {
                AppText("STEP ${index + 1}", color = tc.ac, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                TestsLabeled("Action") {
                    CommitTextField(row.action, { text -> updateRecorded(session, index) { it.copy(action = text) }; StoreResult.Ok(Unit) }, enabled = !applying && !rewriting, placeholder = "Recorded action")
                }
                TestsLabeled("Expected result") {
                    CommitTextField(
                        row.expected,
                        { text ->
                            updateRecorded(session, index) { it.copy(expected = text) }
                            StoreResult.Ok(Unit)
                        },
                        enabled = !applying && !rewriting,
                        multiline = true,
                        placeholder = if (row.optional) "May be blank for an optional action" else "Required before applying",
                    )
                }
                CheckRow(checked = row.optional, onToggle = {
                    updateRecorded(session, index) {
                        if (it.optional) it.copy(optional = false, condition = null)
                        else it.copy(optional = true)
                    }
                }, enabled = !applying && !rewriting) {
                    AppText("Optional: skip when its condition is absent", color = tc.ts, fontSize = 9.sp)
                }
                if (row.optional) {
                    TestsLabeled("Perform only when") {
                        CommitTextField(
                            row.condition.orEmpty(),
                            { text ->
                                updateRecorded(session, index) { it.copy(condition = text) }
                                StoreResult.Ok(Unit)
                            },
                            enabled = !applying && !rewriting,
                            multiline = true,
                            placeholder = "Describe the visible condition, such as an ad is playing",
                        )
                    }
                }
                if (row.sourceInputIds.isNotEmpty()) {
                    RecordedInputsList(row, snapshot.rawSteps.orEmpty())
                    if (row.checks.isNotEmpty()) TestsHint("${row.checks.size} check(s) proposed by the AI will be added to this step.")
                }
                row.contextHint()?.let { TestsHint("Screen context: $it") }
                row.screenContext?.let { TestsHint(it) }
                row.reviewReason?.let {
                    if (row.expected.isBlank()) TestsErrorText("Review required: $it") else TestsHint("Recording note: $it")
                }
                row.screenshotJpeg?.let { bytes ->
                    val source = row.screenshotSource?.name?.lowercase()?.replace('_', ' ') ?: "unknown source"
                    val caption = when {
                        row.screenshotVerifiedMoment != null -> "Image: verified ${row.screenshotVerifiedMoment} evidence from $source."
                        row.screenshotTimingUncertain -> "Image: raw input preview from $source; timing uncertain."
                        else -> "Image: raw input preview from $source; acquisition interval recorded."
                    }
                    TestsHint(caption)
                    RecordedScreenshotPreview(bytes)
                    CheckRow(checked = row.useScreenshotAsExpected, onToggle = {
                        updateRecorded(session, index) { it.copy(useScreenshotAsExpected = !it.useScreenshotAsExpected) }
                    }, enabled = !applying && !rewriting) {
                        val label = if (row.screenshotVerifiedMoment == "after") {
                            "Use this verified after screenshot as an expected screenshot (review first)"
                        } else {
                            "Use this raw input preview as an expected screenshot (review first)"
                        }
                        AppText(label, color = tc.ts, fontSize = 9.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        snapshot.warnings.forEach { TestsErrorText(it) }
        error?.let { TestsErrorText(it) }
        if (!snapshot.active) {
            if (snapshot.pendingSnapshots > 0) TestsHint("Finishing ${snapshot.pendingSnapshots} bounded screen snapshot(s)…")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                AppButton("Discard", onClick = onDismiss, enabled = !ui.state.isTestStepRecordingApplying(session.id) && !rewriting, variant = ButtonVariant.Ghost)
                HintedButton(
                    if (working) "Applying…" else "Apply recorded steps",
                    onClick = {
                        working = true
                        ui.scope.launch {
                            try {
                                val reviewed = session.snapshot.value.steps
                                when (val result = ui.state.applyTestStepRecording(
                                    session.id,
                                    reviewed.map { it.action to it.expected },
                                    case.steps.indexOfFirst { it.id == ui.view.expandedStepId }.takeIf { it >= 0 }?.plus(1),
                                    reviewed.filter { it.useScreenshotAsExpected }.map { it.id }.toSet(),
                                    reviewed.map { it.id },
                                    reviewed.associate { it.id to (it.optional to it.condition) },
                                )) {
                                    is StoreResult.Ok -> { ui.report(result); onDismiss() }
                                    else -> error = result.userMessage()
                                }
                            } finally {
                                working = false
                            }
                        }
                    },
                    enabled = !working && !ui.state.isTestStepRecordingApplying(session.id) && !rewriting && applyBlockedReason == null,
                    disabledHint = applyBlockedReason ?: if (rewriting) "The recording is being rewritten." else "This recording is being applied.",
                    variant = ButtonVariant.Primary,
                )
            }
        }
    }
}

@Composable
private fun RecordedScreenshotPreview(bytes: ByteArray) {
    val bitmap by produceState<ImageBitmap?>(null, bytes) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                decodeBoundedPreviewImage(bytes, 4_000_000L)
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it, contentDescription = "Input-time screenshot context", contentScale = ContentScale.Fit, modifier = Modifier.heightIn(max = 180.dp)) }
}

private fun updateRecorded(session: TestStepRecordingSession, index: Int, transform: (RecordedTestStep) -> RecordedTestStep) {
    val current = session.snapshot.value.steps.toMutableList()
    current.getOrNull(index)?.let { current[index] = transform(it); session.replaceSteps(current) }
}
