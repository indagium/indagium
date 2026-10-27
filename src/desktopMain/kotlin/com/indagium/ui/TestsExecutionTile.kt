package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.capture.CaptureDevice
import com.indagium.testing.authoring.RecordedTestStep
import com.indagium.testing.authoring.ReplacementPlan
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.isExternalEvent
import com.indagium.testing.authoring.recordingApplyBlockedReason
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val OPERATION_CARD_SHAPE = RoundedCornerShape(8.dp)

/** The one Tests tile for runs and authoring recordings. Its targets come from the current selection only while idle. */
@Composable
internal fun TestsExecutionTile() {
    val ui = LocalTestsUi.current
    val tc = tc()
    val suite = if (ui.view.nav == TestsNav.Suites) {
        ui.view.selectedSuiteId?.let(ui.library::suite) ?: ui.library.selectedSuiteOrFirst(ui.view.selectedSuiteId)
    } else {
        null
    }
    val suiteCaseIds = suite?.cases?.mapTo(HashSet()) { it.id }.orEmpty()
    val selectedCaseId = suite?.let { activeCaseId(ui.view.selectedCaseId, ui.view.previewCaseId, suiteCaseIds) }
    val browsingTarget = selectedTestsOperationTarget(suite?.id, selectedCaseId, suiteCaseIds)
    val session = ui.state.testStepRecordingSession
    val liveRuns = ui.state.testRuns.filterNot { it.isFinished }.sortedByDescending { it.createdAt }

    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (session != null) {
            val snapshot by session.snapshot.collectAsState()
            val targetPair = ui.state.testStepRecordingTarget
            val target = targetPair?.let { TestsOperationTarget(it.first, it.second) }
            val targetCase = targetPair?.second?.let(ui.library::findCase)?.case
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    AppText(
                        if (snapshot.active) "Recording · ${snapshot.steps.size} input(s)" else "Recording draft · ${snapshot.steps.size} step(s)",
                        color = if (snapshot.active) tc.warn else tc.tx,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    AppText(
                        target?.let(ui.library::operationTargetLabel) ?: "Original target unavailable",
                        color = tc.ts,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (snapshot.active) {
                    AppButton(
                        "Stop & review",
                        onClick = {
                            ui.state.stopTestStepRecording()
                            ui.view.recordingReviewOpen = true
                        },
                        variant = ButtonVariant.Primary,
                    )
                } else {
                    AppButton("Review draft", onClick = {
                        ui.view.recordingSelectedRowId = ui.view.recordingSelectedRowId?.takeIf { id -> snapshot.steps.any { it.id == id } }
                            ?: snapshot.steps.firstOrNull()?.id
                        ui.view.recordingReviewOpen = true
                    }, variant = ButtonVariant.Primary)
                }
                AppButton(
                    "Discard",
                    onClick = {
                        ui.state.clearTestStepRecording(session.id)
                        ui.view.recordingReviewOpen = false
                        ui.view.recordingSelectedRowId = null
                        ui.view.recordingReplayRowIds = emptySet()
                    },
                    enabled = !ui.state.isTestStepRecordingApplying(session.id) && !ui.state.isTestStepRecordingRewriting(session.id),
                    variant = ButtonVariant.Ghost,
                )
            }
            if (targetCase == null) TestsErrorText("The case this recording belongs to is no longer available. The draft cannot be applied to another case.")
            if (browsingTarget != target) {
                AppText(
                    "Browsing ${browsingTarget?.let(ui.library::operationTargetLabel) ?: "another Tests area"}; this recording stays with its original target.",
                    color = tc.td, fontSize = 10.sp,
                )
            }
            if (!snapshot.active && ui.view.recordingReviewOpen && targetCase != null) {
                RecordingDraftReviewTile(session, targetCase)
            }
        } else {
            if (ui.view.recordingReviewOpen) {
                LaunchedEffect(Unit) {
                    ui.view.recordingReviewOpen = false
                    ui.view.recordingSelectedRowId = null
                    ui.view.recordingReplayRowIds = emptySet()
                }
            }
            val targetLabel = browsingTarget?.let(ui.library::operationTargetLabel)
            if (targetLabel == null) {
                TestsHint("Select a suite or case to prepare a run or recording.")
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        AppText("Ready", color = tc.ac, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        AppText(targetLabel, color = tc.tx, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    AppText(
                        if (browsingTarget.caseId == null) "Suite run · all unlocked cases" else "Case run · selected case",
                        color = tc.ts, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            suite?.let { selectedSuite ->
                val selectedCase = selectedCaseId?.let { id -> selectedSuite.cases.firstOrNull { it.id == id } }
                val caseAccess = selectedCase?.let { caseEditAccess(ui.library, selectedSuite, it.id, LocalTestsLimits.current) }
                val storedRecordings by produceState(emptyList<String>(), selectedCase?.id, selectedCase?.steps?.map { it.recordingRef }) {
                    value = selectedCase?.let { withContext(Dispatchers.IO) { ui.state.storedRecordingIds(it.id) } }.orEmpty()
                }
                if (storedRecordings.isNotEmpty() && selectedCase != null) {
                    var reopening by remember(selectedCase.id) { mutableStateOf(false) }
                    var reopenError by remember(selectedCase.id) { mutableStateOf<String?>(null) }
                    TestsSectionTitle("Saved recording evidence", modifier = Modifier.padding(top = 0.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        storedRecordings.forEachIndexed { index, recordingId ->
                            AppButton(
                                if (storedRecordings.size == 1) "Rewrite from saved recording…" else "Rewrite from saved recording ${index + 1}…",
                                onClick = {
                                    if (!reopening) {
                                        reopening = true
                                        ui.scope.launch {
                                            try {
                                                when (val opened = ui.state.reopenTestStepRecording(selectedCase.id, recordingId)) {
                                                    is StoreResult.Ok -> {
                                                        reopenError = null
                                                        ui.view.recordingReviewOpen = true
                                                    }
                                                    else -> reopenError = opened.userMessage()
                                                }
                                            } finally {
                                                reopening = false
                                            }
                                        }
                                    }
                                },
                                enabled = caseAccess?.editable == true && !reopening,
                                variant = ButtonVariant.Ghost,
                            )
                        }
                        reopenError?.let { TestsErrorText(it) }
                    }
                }
            }
        }

        if (liveRuns.isNotEmpty()) {
            TestsSectionTitle("Active runs", modifier = Modifier.padding(top = 0.dp))
            liveRuns.forEach { run -> ActiveRunCard(run, browsingTarget) }
        }
    }
}

/** Run and recording dialogs stay composed at the workspace root, even if their tile is hidden or collapsed. */
@Composable
internal fun TestsOperationDialogs() {
    val ui = LocalTestsUi.current
    ui.view.runDialog?.let { target ->
        TestRunDialog(target) {
            if (ui.view.runDialog == target) ui.view.runDialog = null
        }
    }
    ui.view.improveDialog?.let { target ->
        TestStepImproveDialog(target) {
            if (ui.view.improveDialog == target) ui.view.improveDialog = null
        }
    }
    ui.view.recordDialogTarget?.let { target -> TestRecordingSetupDialog(target) }
}

@Composable
private fun ActiveRunCard(run: TestRun, browsingTarget: TestsOperationTarget?) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val coordinator = ui.state.testRunCoordinator
    var paused by remember(run.id) { mutableStateOf(coordinator.isPaused(run.id)) }
    LaunchedEffect(run.id, run.status) { paused = coordinator.isPaused(run.id) }
    val activeLane = run.lanes.firstOrNull { it.status == RunStatus.RUNNING }
    Column(
        Modifier.fillMaxWidth().background(tc.p, OPERATION_CARD_SHAPE).border(1.dp, tc.br, OPERATION_CARD_SHAPE).padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            AppText(run.status.label(), color = if (paused) tc.warn else tc.ac, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            AppText(run.operationTargetLabel(), color = tc.tx, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (run.status == RunStatus.RUNNING) {
                AppButton(if (paused) "Resume" else "Pause", onClick = {
                    paused = !paused
                    coordinator.setPaused(run.id, paused)
                    ui.reclaimFocus()
                }, variant = ButtonVariant.Ghost)
                AppButton("Stop", onClick = { coordinator.cancel(run.id); ui.reclaimFocus() }, isDanger = true)
            }
        }
        activeLane?.let { lane ->
            lane.currentCase?.let { AppText(it, color = tc.ts, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            lane.currentStepAction?.let { AppText(it, color = tc.td, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (browsingTarget != null && run.config.suiteId != browsingTarget.suiteId) {
            AppText("This run keeps its original target while you browse ${ui.library.operationTargetLabel(browsingTarget)}.", color = tc.td, fontSize = 9.sp)
        }
        AppButton("Open run report", onClick = { openSuiteRun(ui, run.id); ui.reclaimFocus() }, variant = ButtonVariant.Ghost)
    }
}

/** Compact draft list and apply controls stay in Execution; the selected row's editor and images use Details/Evidence. */
@Composable
private fun RecordingDraftReviewTile(session: TestStepRecordingSession, case: TestCase) {
    val ui = LocalTestsUi.current
    val tc = tc()
    val snapshot by session.snapshot.collectAsState()
    val pinnedSuite = ui.state.testStepRecordingTarget?.first?.let(ui.library::suite)
    val targetLocked = pinnedSuite == null || !caseEditAccess(ui.library, pinnedSuite, case.id, LocalTestsLimits.current).editable
    val rewriting = ui.state.isTestStepRecordingRewriting(session.id)
    val applying = ui.state.isTestStepRecordingApplying(session.id)
    val editable = !snapshot.active && snapshot.pendingSnapshots == 0 && !rewriting && !applying && !targetLocked
    val replacement = remember(case.steps.map { it.id }, session.id) { ui.state.reopenedReplacementPlan(session.id) }
    val blockedReason = recordingApplyBlockedReason(snapshot)
    val replayable = remember(snapshot.steps, snapshot.active) { if (snapshot.active) emptySet() else session.replayableRowIds() }
    val replayRows = ui.view.recordingReplayRowIds.intersect(replayable)
    var localApply by remember(session.id) { mutableStateOf(false) }
    var error by remember(session.id) { mutableStateOf<String?>(null) }
    val insertInstead = snapshot.reopened != null && replacement is ReplacementPlan.Ambiguous
    val applyLabel = when {
        localApply || applying -> "Applying…"
        insertInstead -> "Insert as new steps"
        snapshot.reopened != null -> "Apply and replace"
        else -> "Apply to original case"
    }
    LaunchedEffect(replayable, ui.view.recordingReplayRowIds) {
        val retained = ui.view.recordingReplayRowIds.intersect(replayable)
        if (retained != ui.view.recordingReplayRowIds) ui.view.recordingReplayRowIds = retained
    }
    LaunchedEffect(session.id, snapshot.steps.map { it.id }, ui.view.recordingSelectedRowId) {
        val selectedExists = snapshot.steps.any { it.id == ui.view.recordingSelectedRowId }
        if (!selectedExists) ui.view.recordingSelectedRowId = snapshot.steps.firstOrNull()?.id
    }

    fun addStep(at: Int) {
        val row = RecordedTestStep(action = "")
        when (val inserted = ui.state.insertTestRecordingRow(session.id, at, row)) {
            is StoreResult.Ok -> {
                ui.view.recordingSelectedRowId = row.id
                ui.view.recordingReviewOpen = true
                error = null
            }
            else -> error = inserted.userMessage()
        }
        ui.reclaimFocus()
    }

    fun apply(insertAsNew: Boolean) {
        if (localApply || blockedReason != null) return
        localApply = true
        error = null
        ui.scope.launch {
            try {
                val reviewed = session.snapshot.value.steps
                when (val result = ui.state.applyTestStepRecording(
                    session.id,
                    reviewed.map { it.action to it.expected },
                    case.steps.indexOfFirst { it.id == ui.view.selectedStepId }.takeIf { it >= 0 }?.plus(1),
                    reviewed.filter { it.useScreenshotAsExpected }.mapTo(HashSet()) { it.id },
                    reviewed.map { it.id },
                    reviewed.associate { it.id to (it.optional to it.condition) },
                    insertAsNew,
                    replayRowIds = replayRows,
                )) {
                    is StoreResult.Ok -> {
                        ui.report(result)
                        ui.view.recordingReviewOpen = false
                        ui.view.recordingSelectedRowId = null
                        ui.view.recordingReplayRowIds = emptySet()
                    }
                    else -> error = result.userMessage()
                }
            } finally {
                localApply = false
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        TestsSectionTitle("Draft steps (${snapshot.steps.size})", modifier = Modifier.padding(top = 0.dp)) {
            AppButton("+ Step", onClick = { addStep(snapshot.steps.size) }, enabled = editable, variant = ButtonVariant.Ghost)
        }
        if (snapshot.excludedSourceInputIds.isNotEmpty()) {
            AppButton(
                "Restore removed inputs",
                onClick = {
                    error = ui.state.restoreRemovedTestRecordingInputs(session.id)
                        .takeIf { it !is StoreResult.Ok }
                        ?.userMessage()
                },
                enabled = !snapshot.active && snapshot.pendingSnapshots == 0 && !rewriting && !applying && !localApply,
                variant = ButtonVariant.Ghost,
            )
        }
        if (snapshot.steps.isNotEmpty()) {
            HintedButton(
                applyLabel,
                onClick = { apply(insertInstead) },
                enabled = editable && !localApply && blockedReason == null,
                disabledHint = blockedReason ?: if (rewriting) "The recording is being rewritten." else "Review the draft before applying it.",
                variant = ButtonVariant.Primary,
            )
        }
        if (snapshot.steps.isEmpty()) {
            TestsHint(if (snapshot.excludedSourceInputIds.isNotEmpty()) "Restore removed inputs or add a step." else "No inputs were recorded.")
        } else {
            ReorderableColumn(
                items = snapshot.steps,
                idOf = { it.id },
                onMove = { id, to -> error = ui.state.moveTestRecordingRow(session.id, id, to).takeIf { it !is StoreResult.Ok }?.userMessage() },
                reorderEnabled = editable,
                rowGap = 3.dp,
            ) { row, reorder ->
                TestsListRow(
                    selected = ui.view.recordingSelectedRowId == row.id,
                    onClick = {
                        ui.view.recordingSelectedRowId = row.id
                        ui.view.recordingReviewOpen = true
                        ui.reclaimFocus()
                    },
                    minHeight = 38.dp,
                ) {
                    ReorderGrip(reorder)
                    AppText("${reorder.index + 1}", color = tc.ac, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Column(Modifier.weight(1f)) {
                        AppText(
                            row.action.ifBlank { "Untitled recorded step" },
                            color = tc.tx, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        AppText(
                            when {
                                row.isExternalEvent -> "Detected device event"
                                row.expected.isBlank() -> "Expected result needed"
                                row.optional -> "Optional step"
                                else -> row.expected
                            },
                            color = if (row.expected.isBlank() && row.stepKind == StepKind.AGENT) tc.warn else tc.td,
                            fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ReorderMoveButtons(reorder)
                    AppButton("Remove", onClick = {
                        error = ui.state.removeTestRecordingRows(session.id, setOf(row.id)).takeIf { it !is StoreResult.Ok }?.userMessage()
                    }, enabled = editable, variant = ButtonVariant.Ghost)
                }
            }
        }
        ui.state.testStepRecordingTarget?.let { target ->
            val suite = ui.library.suite(target.first)
            val pinnedCase = suite?.cases?.firstOrNull { it.id == target.second }
            if (pinnedCase != null) RecordingRewriteSection(session, pinnedCase, snapshot, locked = applying || localApply)
        }
        if (snapshot.steps.isNotEmpty()) {
            CheckRow(
                checked = replayable.isNotEmpty() && replayRows.size == replayable.size,
                onToggle = {
                    ui.view.recordingReplayRowIds = if (replayRows.size == replayable.size) emptySet() else replayable
                },
                enabled = editable && replayable.isNotEmpty(),
            ) { AppText(APPLY_AS_REPLAY_LABEL, color = tc.ts, fontSize = 10.sp) }
            TestsHint(replayOptionHint(replayable.size, snapshot.steps.size, replayRows.size))
        }
        snapshot.warnings.forEach { TestsErrorText(it) }
        error?.let { TestsErrorText(it) }
        blockedReason?.let { TestsHint(it) }
        if (targetLocked) TestsErrorText("The original case is read-only or no longer available. This draft cannot be applied elsewhere.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            AppButton("Close review", onClick = { ui.view.recordingReviewOpen = false; ui.reclaimFocus() }, variant = ButtonVariant.Ghost)
            AppButton("Discard draft", onClick = {
                ui.state.clearTestStepRecording(session.id)
                ui.view.recordingReviewOpen = false
                ui.view.recordingSelectedRowId = null
                ui.view.recordingReplayRowIds = emptySet()
            }, enabled = !applying && !rewriting, variant = ButtonVariant.Ghost)
        }
    }
}

private sealed interface RecordingDevicesState {
    data object Loading : RecordingDevicesState

    data class Ready(val devices: List<CaptureDevice>) : RecordingDevicesState

    data class Failed(val message: String) : RecordingDevicesState
}

@Composable
@Suppress("TooGenericExceptionCaught") // adb/scrcpy and the capture adapter can surface platform-specific exception types.
private fun TestRecordingSetupDialog(initialTarget: TestsOperationTarget) {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val tc = tc()
    val currentSuite = ui.library.suite(initialTarget.suiteId)
    val targetCase = initialTarget.caseId?.let { id -> currentSuite?.cases?.firstOrNull { it.id == id } }
    var destination by remember(initialTarget.suiteId) { mutableStateOf<RecordingCaseDestination>(RecordingCaseDestination.NewCase) }
    var newCaseName by remember(initialTarget.suiteId) { mutableStateOf(uniqueName("Recorded case", currentSuite?.cases?.map { it.name }.orEmpty())) }
    var selectedSerial by remember(initialTarget.suiteId) { mutableStateOf<String?>(null) }
    var retryDevices by remember(initialTarget.suiteId) { mutableStateOf(0) }
    var starting by remember(initialTarget.suiteId) { mutableStateOf(false) }
    var error by remember(initialTarget.suiteId) { mutableStateOf<String?>(null) }
    val deviceState by produceState<RecordingDevicesState>(RecordingDevicesState.Loading, initialTarget.suiteId, retryDevices) {
        value = try {
            RecordingDevicesState.Ready(withContext(Dispatchers.IO) { ui.state.discoverTestStepRecordingDevices() })
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            RecordingDevicesState.Failed(failure.message ?: "Could not list the connected devices.")
        }
    }
    val devices = (deviceState as? RecordingDevicesState.Ready)?.devices.orEmpty()
    val selectedDestinationCase = when (val chosen = destination) {
        RecordingCaseDestination.NewCase -> null
        is RecordingCaseDestination.ExistingCase -> currentSuite?.cases?.firstOrNull { it.id == chosen.caseId }
    }
    val canCreateDestination = currentSuite != null && !ui.library.readOnly && !currentSuite.readOnly && limits.canCreateCase(currentSuite.id)
    val canStartForTarget = if (initialTarget.caseId != null) {
        targetCase != null && currentSuite != null && caseEditAccess(ui.library, currentSuite, targetCase.id, limits).editable
    } else if (destination == RecordingCaseDestination.NewCase) {
        canCreateDestination
    } else {
        selectedDestinationCase != null && currentSuite != null && caseEditAccess(ui.library, currentSuite, selectedDestinationCase.id, limits).editable
    }
    LaunchedEffect(deviceState) {
        val available = (deviceState as? RecordingDevicesState.Ready)?.devices.orEmpty()
        val preferred = ui.state.liveCaptureSerial()
        selectedSerial = selectedSerial?.takeIf { serial -> available.any { it.serial == serial } }
            ?: available.firstOrNull { it.serial == preferred }?.serial
            ?: available.singleOrNull()?.serial
    }

    fun close() {
        ui.view.recordDialogTarget = null
        ui.reclaimFocus()
    }

    fun targetForCurrentChoice(): TestsOperationTarget? {
        val suite = ui.state.testLibrary.suite(initialTarget.suiteId) ?: return null
        val currentIds = suite.cases.mapTo(HashSet()) { it.id }
        if (initialTarget.caseId != null) {
            return selectedTestsOperationTarget(suite.id, initialTarget.caseId, currentIds)
        }
        var createdCaseId: String? = null
        val chosen = destination
        if (chosen == RecordingCaseDestination.NewCase) {
            if (!limits.canCreateCase(suite.id) || ui.library.readOnly || suite.readOnly) {
                error = limits.hint
                return null
            }
            val name = newCaseName.trim().ifBlank { "Recorded case" }
            when (val created = ui.report(ui.state.createTestCase(suite.id, TestCase("", uniqueName(name, suite.cases.map { it.name }))))) {
                is StoreResult.Ok -> createdCaseId = created.value.id
                else -> {
                    error = created.userMessage()
                    return null
                }
            }
        }
        val updatedIds = if (createdCaseId == null) currentIds else currentIds + createdCaseId
        val resolved = recordingTestsOperationTarget(suite.id, chosen, createdCaseId, updatedIds)
        if (resolved?.caseId != null) ui.view.recordDialogTarget = resolved
        return resolved
    }

    fun start() {
        if (starting) return
        val serial = selectedSerial?.takeIf { selected -> devices.any { it.serial == selected } }
        if (serial == null) {
            error = when (deviceState) {
                is RecordingDevicesState.Loading -> "Waiting for the device list."
                is RecordingDevicesState.Failed -> "Refresh the device list and try again."
                is RecordingDevicesState.Ready -> "Choose a connected device."
            }
            return
        }
        val target = targetForCurrentChoice()
        if (target == null) {
            if (error == null) error = "Choose a current case in this suite."
            return
        }
        val suite = ui.state.testLibrary.suite(target.suiteId)
        val case = suite?.cases?.firstOrNull { it.id == target.caseId }
        if (suite == null || case == null) {
            error = "The recording destination is no longer available. Choose another case."
            return
        }
        if (!caseEditAccess(ui.library, suite, case.id, limits).editable) {
            error = caseEditAccess(ui.library, suite, case.id, limits).reason ?: "This case is read-only."
            return
        }
        val caseId = case.id
        starting = true
        error = null
        ui.scope.launch {
            try {
                when (val prepared = ui.state.prepareTestStepRecording(serial, target.suiteId, caseId)) {
                    is StoreResult.Ok -> {
                        ui.view.recordDialogTarget = null
                        ui.view.recordingReviewOpen = false
                    }
                    else -> error = prepared.userMessage()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "Could not prepare recording: ${failure.message ?: failure::class.simpleName}"
            } finally {
                starting = false
            }
        }
    }

    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TestsDialogFrame(
            title = "Record from device",
            width = 560.dp,
            maxHeight = 650.dp,
            status = { error?.let { TestsErrorText(it, Modifier.padding(top = 8.dp)) } },
            body = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val suite = currentSuite
                    TestsDialogRow("Target") {
                        AppText(
                            when {
                                suite == null -> "Deleted suite"
                                targetCase != null -> "${suite.name} / ${targetCase.name}"
                                initialTarget.caseId != null -> "${suite.name} / deleted case"
                                else -> suite.name
                            },
                            color = tc.tx,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (initialTarget.caseId != null && targetCase == null) TestsErrorText("This recording target was deleted.")
                    }
                    if (suite != null && initialTarget.caseId == null) {
                        val editableCases = suite.cases.filter { case -> caseEditAccess(ui.library, suite, case.id, limits).editable }
                        val destinations = buildList<RecordingCaseDestination> {
                            add(RecordingCaseDestination.NewCase)
                            editableCases.forEach { add(RecordingCaseDestination.ExistingCase(it.id)) }
                        }
                        val destinationLabel = when (val selected = destination) {
                            RecordingCaseDestination.NewCase -> "New case in ${suite.name}"
                            is RecordingCaseDestination.ExistingCase -> editableCases.firstOrNull { it.id == selected.caseId }?.name
                                ?: "Choose an editable case"
                        }
                        TestsDialogRow("Record into") {
                            TestsDropdown(
                                selectedLabel = destinationLabel,
                                options = destinations,
                                optionLabel = { option ->
                                    when (option) {
                                        RecordingCaseDestination.NewCase -> "New case in ${suite.name}"
                                        is RecordingCaseDestination.ExistingCase -> editableCases.firstOrNull { it.id == option.caseId }?.name ?: "Deleted case"
                                    }
                                },
                                onSelect = { destination = it; error = null },
                                isSelected = { it == destination },
                                menuWidth = 300.dp,
                            )
                        }
                        if (destination == RecordingCaseDestination.NewCase) {
                            TestsDialogRow("New case name") {
                                CommitTextField(
                                    newCaseName,
                                    { value -> newCaseName = value; StoreResult.Ok(Unit) },
                                    placeholder = "Recorded case",
                                    proseTargetId = "recording-new-case:${suite.id}:name",
                                )
                            }
                        }
                    }
                    TestsDialogRow("Device") {
                        when (val loading = deviceState) {
                            RecordingDevicesState.Loading -> TestsHint("Finding connected Android devices…")
                            is RecordingDevicesState.Failed -> {
                                TestsErrorText(loading.message)
                                AppButton("Refresh devices", onClick = { retryDevices++ }, variant = ButtonVariant.Ghost)
                            }
                            is RecordingDevicesState.Ready -> when {
                                loading.devices.isEmpty() -> {
                                    TestsHint("No connected Android devices are available. Connect or authorize a device, then refresh.")
                                    AppButton("Refresh devices", onClick = { retryDevices++ }, variant = ButtonVariant.Ghost)
                                }
                                loading.devices.size == 1 -> AppText(recordingDeviceLabel(loading.devices.single()), color = tc.tx, fontSize = 11.sp)
                                else -> TestsDropdown(
                                    selectedLabel = loading.devices.firstOrNull { it.serial == selectedSerial }?.let(::recordingDeviceLabel) ?: "Choose device",
                                    options = loading.devices,
                                    optionLabel = ::recordingDeviceLabel,
                                    onSelect = { selectedSerial = it.serial },
                                    isSelected = { it.serial == selectedSerial },
                                    menuWidth = 300.dp,
                                )
                            }
                        }
                    }
                }
            },
            footer = {
                DialogActionButton(
                    "Start recording",
                    active = true,
                    enabled = !starting && canStartForTarget && selectedSerial?.let { serial -> devices.any { it.serial == serial } } == true,
                ) { start() }
                DialogActionButton("Cancel", active = false) { close() }
            },
        )
    }
}

private fun activeCaseId(selectedCaseId: String?, previewCaseId: String?, validCaseIds: Set<String>): String? =
    selectedCaseId?.takeIf { it in validCaseIds } ?: previewCaseId?.takeIf { it in validCaseIds }

private fun recordingDeviceLabel(device: CaptureDevice): String =
    if (device.model == device.serial) device.serial else "${device.model} (${device.serial})"
