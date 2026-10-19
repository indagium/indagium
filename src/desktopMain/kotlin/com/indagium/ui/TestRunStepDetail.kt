@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.indagium.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.model.summaryLabel
import com.indagium.testing.model.CaseAttemptRecord
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.JudgeComparison
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepFix
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.agentJudgeDisagrees
import com.indagium.testing.model.performedByLine
import com.indagium.testing.run.StartRunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// The detail of one step of one lane in the report: what was asked, what the agent said, the automatic checks, the judge's
// verdict with its reasoning, classification and suggested fix, the expected (golden) screenshot beside the actual one, the
// log and the transcript - and the actions: apply the suggested fix to the library step (after a before/after confirm),
// mark the result as the agent's mistake, run the step again. Text from an agent, a model, the device or a script is
// labelled and shown, never acted on. Images are decoded on IO.

private val SCREENSHOT_MAX_HEIGHT = 360.dp
private val EXCERPT_MAX_HEIGHT = 200.dp
private val FIX_DIALOG_WIDTH = 520.dp
private const val DETAIL_MAX_LINES = 12
private const val PANEL_ALPHA = .6f
private const val HEADER_ACTION_CHARS = 80

/** Below this width the expected and actual screenshots stack instead of sitting side by side. */
private val SIDE_BY_SIDE_MIN_WIDTH = 440.dp

/** A step of a lane picked in the matrix: [rowKey] is the matrix row's key. */
internal data class StepSelection(val rowKey: String, val laneId: String)

/**
 * The inspector content for one selected step: its header (step number and case, the action) and the evidence below. Everything
 * wraps or stacks so it fits the narrowest inspector (300dp).
 */
@Composable
internal fun StepDetailFor(run: TestRun, runDir: File, selection: StepSelection) {
    val row = remember(run, selection.rowKey) { buildMatrix(run).filterIsInstance<MatrixRow.Step>().firstOrNull { it.key == selection.rowKey } }
    val result = row?.cells?.firstOrNull { it.laneId == selection.laneId }?.result
    val lane = run.lane(selection.laneId)
    if (row == null || result == null || lane == null) {
        TestsHint("This step has no result yet.")
        return
    }
    val laneIndex = run.lanes.indexOfFirst { it.laneId == lane.laneId }
    val eyebrow = listOfNotNull(
        (if (row.setup) "Setup step ${result.stepNumber}" else "Step ${result.stepNumber}") +
            " · " + matrixCaseName(run, row.caseId) + if (row.iteration > 1) " (run ${row.iteration})" else "",
        "Lane ${laneIndex + 1}".takeIf { run.lanes.size > 1 },
    ).joinToString(" · ")
    TestsInspectorHeader(eyebrow, result.action.ifBlank { row.action })
    StepDetail(run, row, result, lane, runDir)
}

@Composable
private fun StepDetail(run: TestRun, row: MatrixRow.Step, step: StepResult, lane: LaneResult, runDir: File) {
    val tc = tc()
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lane.cases.firstOrNull { it.caseId == row.caseId && it.iteration == row.iteration }
            ?.priorAttempts?.takeIf { it.isNotEmpty() }?.let { attempts -> EarlierCaseAttempts(attempts, lane, runDir) }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(step.detailLabel(), stepResultColor(step))
            val attempts = if (step.attempts == 1) "1 attempt" else "${step.attempts} attempts"
            AppText("$attempts · ${formatDuration(step.durationMs)}", color = tc.td, fontSize = 10.sp)
            if (step.flaky) TestsBadge("flaky")
            if (step.verificationState == com.indagium.testing.model.VerificationState.PENDING) {
                // A run that is over will never judge it: a cancel stopped the judge before it ran.
                val over = run.status != RunStatus.QUEUED && run.status != RunStatus.RUNNING
                TestsBadge(if (over) "not judged · the run ended first" else "provisional · judge pending")
            }
            if (step.agentJudgeDisagrees()) TestsBadge("agent and judge disagree")
            if (step.issueId != null) TestsBadge("issue draft created") else if (step.issueRequested) TestsBadge("issue requested")
            if (step.judgeInconclusive) TestsBadge("judge inconclusive")
            if (step.automaticRetryExcluded) TestsBadge("automatic retry unavailable")
            if (step.agentError != null) TestsBadge("marked as agent error")
        }
        // The header shows the action in two lines; a longer one is repeated here in full.
        if (step.action.length > HEADER_ACTION_CHARS) DetailLine("Action", step.action)
        step.performedByLine()?.let { DetailLine("Performed by", it) }
        DetailLine("Expected", step.expected)
        run.suite.cases.firstOrNull { it.id == row.caseId }?.steps?.firstOrNull { it.id == row.stepId }?.let { definition ->
            DetailLine("Intent", if (definition.intent == com.indagium.testing.model.StepIntent.ENSURE_STATE) {
                "Ensure state: accepts the expected state when it is already true."
            } else {
                "Perform action: verifies the transition caused by the action."
            })
            definition.precondition?.let { DetailLine("Required starting state", it) }
            if (definition.optional) DetailLine("Run only when", definition.condition.orEmpty())
        }
        step.executionStatus?.let { if (it != step.status) DetailLine("Execution outcome", it.name.lowercase()) }
        if (step.verificationState != com.indagium.testing.model.VerificationState.NOT_REQUESTED) {
            DetailLine("Verification", step.verificationState.name.lowercase().replace('_', ' '))
        }
        step.judgeQueueWaitMs?.let { DetailLine("Judge queue wait", formatDuration(it)) }
        step.judgmentDurationMs?.let { DetailLine("Judge duration", formatDuration(it)) }
        step.classification?.let { DetailLine("Classification", it.label()) }
        step.agentClaim?.let { DetailLine("Agent claimed", it) }
        if (step.observation.isNotBlank()) DetailLine("Observation (untrusted)", step.observation, mono = true)
        step.agentError?.let { DetailLine("Marked as an agent error", it) }
        step.note?.let { DetailLine("Note", it) }
        step.checks.forEach { check ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                StatusChip(check.status.name.lowercase(), checkColor(check.status))
                AppText("${check.kind}: ${check.detail}", color = tc.ts, fontSize = 11.sp, maxLines = 4, modifier = Modifier.weight(1f))
            }
        }
        step.judge?.let { JudgeSection(run, row, it) }
        step.attemptHistory.forEach { attempt ->
            Column(Modifier.fillMaxWidth().background(tc.p2, CORNER_MD).padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                AppText(
                    "Attempt ${attempt.attempt} · ${attempt.status.name.lowercase()} · " +
                        "${attempt.retryReason.replace('_', ' ')} · ${formatDuration(attempt.durationMs)}",
                    color = tc.ts,
                    fontSize = 10.sp,
                )
                attempt.executionStatus?.let { AppText("Execution: ${it.name.lowercase()}", color = tc.td, fontSize = 10.sp) }
                if (attempt.verificationState != com.indagium.testing.model.VerificationState.NOT_REQUESTED) {
                    AppText("Verification: ${attempt.verificationState.name.lowercase().replace('_', ' ')}", color = tc.td, fontSize = 10.sp)
                }
                attempt.judgeQueueWaitMs?.let { AppText("Judge queue wait: ${formatDuration(it)}", color = tc.td, fontSize = 10.sp) }
                attempt.judgmentDurationMs?.let { AppText("Judge duration: ${formatDuration(it)}", color = tc.td, fontSize = 10.sp) }
                attempt.agentClaim?.let { AppText("Claim (untrusted): $it", color = tc.td, fontSize = 10.sp) }
                if (attempt.observation.isNotBlank()) DetailLine("Attempt observation (untrusted)", attempt.observation, mono = true)
                attempt.judge?.let { judge ->
                    TestsBadge("judge ${judge.verdict.name.lowercase()} · ${judge.usage?.let(::formatUsage) ?: "usage unavailable"}")
                }
            }
        }
        run.comparisons.filter { it.stepId == row.stepId && it.iteration == row.iteration && it.caseId == row.caseId }
            .forEach { ComparisonSection(run, row, it) }
        ExpectedVersusActual(run, row, step, runDir)
        StepEvidence(step, lane, runDir)
        StepActions(run, row, lane, step)
    }
}

@Composable
private fun EarlierCaseAttempts(attempts: List<CaseAttemptRecord>, lane: LaneResult, runDir: File) {
    val tc = tc()
    Column(Modifier.fillMaxWidth().background(tc.p2, CORNER_MD).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppText("Other case attempts", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        attempts.forEach { attempt ->
            Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusChip("attempt ${attempt.attempt} · ${attempt.status.name.lowercase()}", caseColor(attempt.status))
                    attempt.note?.takeIf { it.isNotBlank() }?.let { AppText(it, color = tc.td, fontSize = 10.sp, maxLines = 2) }
                }
                attempt.steps.filter { it.status != StepStatus.PASS && it.status != StepStatus.SKIPPED }.forEach { failed ->
                    AppText("Step ${failed.stepNumber}: ${failed.status.name.lowercase()} · ${failed.action}", color = tc.ts, fontSize = 10.sp, maxLines = 2)
                    failed.agentClaim?.let { AppText("Claim (untrusted): $it", color = tc.td, fontSize = 10.sp, maxLines = 2) }
                    if (failed.observation.isNotBlank()) {
                        AppText("Observation (untrusted): ${failed.observation}", color = tc.td, fontSize = 10.sp, maxLines = 3)
                    }
                    failed.checks.filter { it.status == CheckStatus.FAIL || it.status == CheckStatus.ERROR }.forEach { check ->
                        AppText("${check.status.name.lowercase()} ${check.kind}: ${check.detail}", color = tc.td, fontSize = 10.sp, maxLines = 2)
                    }
                    failed.judge?.let { judge ->
                        AppText(
                            "Judge ${judge.verdict.name.lowercase()}: ${judge.reasoning.orEmpty().ifBlank { judge.error.orEmpty() }}",
                            color = tc.td,
                            fontSize = 10.sp,
                            maxLines = 3,
                        )
                    }
                    val evidencePaths = listOfNotNull(failed.startScreenshotPath, failed.screenshotPath) + failed.sampleScreenshotPaths
                    if (evidencePaths.isNotEmpty()) {
                        val testsUi = LocalTestsUi.current
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            evidencePaths.distinct().forEachIndexed { index, path ->
                                val file = File(runDir, path)
                                if (file.isFile) {
                                    AppButton("Open evidence ${index + 1}", onClick = { testsUi.openFileReportingFailure(file, "the evidence screenshot") })
                                }
                            }
                        }
                    }
                }
            }
        }
        lane.transcriptPath?.let { path ->
            val transcript = File(runDir, path)
            if (transcript.isFile) {
                val testsUi = LocalTestsUi.current
                AppButton("Open lane transcript", onClick = { testsUi.openFileReportingFailure(transcript, "the lane transcript") })
            }
        }
    }
}

@Composable
internal fun checkColor(status: CheckStatus): Color = when (status) {
    CheckStatus.PASS -> tc().success
    CheckStatus.FAIL, CheckStatus.ERROR -> DANGER_RED
    CheckStatus.NOT_EVALUATED -> tc().td
}

@Composable
private fun DetailLine(label: String, value: String, mono: Boolean = false) {
    if (value.isBlank()) return
    val tc = tc()
    Column {
        AppText(label, color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        AppText(value, color = tc.tx, fontSize = 11.sp, fontFamily = if (mono) MONO else LocalUiFontFamily.current, maxLines = DETAIL_MAX_LINES)
    }
}

// ── The judge ────────────────────────────────────────────────────────

@Composable
private fun JudgeSection(run: TestRun, row: MatrixRow.Step, judge: StepJudgement) {
    val tc = tc()
    Column(Modifier.fillMaxWidth().background(tc.p2, CORNER_MD).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText("Judge", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            StatusChip(judge.verdict.label(), verdictColor(judge.verdict))
            TestsBadge(judge.classification.label())
        }
        judge.error?.let { TestsErrorText(it) }
        judge.timing?.let { AppText("Judge: ${it.summaryLabel()}", color = tc.td, fontSize = 11.sp) }
        DetailLine("Reasoning (written by the judge model)", judge.reasoning)
        judge.suggestedFix?.let { FixLine(run, row, it, judge.id, judge.fixApplied) }
    }
}

@Composable
private fun ComparisonSection(run: TestRun, row: MatrixRow.Step, comparison: JudgeComparison) {
    val tc = tc()
    Column(Modifier.fillMaxWidth().background(tc.p2, CORNER_MD).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText("Lanes compared", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            TestsBadge(comparison.classification.label())
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            comparison.verdicts.forEach { (laneId, verdict) ->
                val index = run.lanes.indexOfFirst { it.laneId == laneId }
                StatusChip("Lane ${index + 1}: ${verdict.label()}", verdictColor(verdict))
            }
        }
        comparison.error?.let { TestsErrorText(it) }
        DetailLine("Why the lanes differ (written by the judge model)", comparison.explanation)
        comparison.suggestedFix?.let { FixLine(run, row, it, comparison.id, comparison.fixApplied) }
    }
}

/** A suggested fix with its before/after and the button that opens the confirmation. */
@Composable
private fun FixLine(run: TestRun, row: MatrixRow.Step, fix: StepFix, fixRef: String, applied: Boolean) {
    val tc = tc()
    val ui = LocalTestsUi.current
    var confirming by remember(fixRef) { mutableStateOf(false) }
    val limits = LocalTestsLimits.current
    val location = ui.library.findStep(row.stepId)
    val libraryStep = location?.step
    val locked = location != null && (limits.isSuiteLocked(location.suite.id) || limits.isCaseLocked(location.case.id))
    AppText("Suggested step fix", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    fix.action?.let { DetailLine("New action", it) }
    fix.expected?.let { DetailLine("New expected result", it) }
    fix.note?.let { DetailLine("Note", it) }
    if (applied) {
        TestsBadge("applied to the library")
    } else if (fix.isApplicable) {
        HintWhen(locked, limits.hint) {
            AppButton("Apply fix…", onClick = { confirming = true }, enabled = libraryStep != null && !locked)
        }
        if (libraryStep == null) TestsHint("The step is no longer in the library.")
    }
    if (confirming && libraryStep != null) StepFixDialog(run.id, row.stepId, fixRef, libraryStep.action, libraryStep.expected, fix) { confirming = false }
}

@Composable
private fun StepFixDialog(runId: String, stepId: String, fixRef: String, action: String, expected: String, fix: StepFix, onClose: () -> Unit) {
    val tc = tc()
    val ui = LocalTestsUi.current
    var problem by remember { mutableStateOf<String?>(null) }
    val changes = remember(action, expected, fix) { fixChanges(TestStep(stepId, action, expected), fix) }

    fun close() {
        onClose()
        ui.reclaimFocus()
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TestsDialogFrame(
            title = "Apply the suggested fix?",
            width = FIX_DIALOG_WIDTH,
            status = { problem?.let { TestsErrorText(it, Modifier.padding(top = 8.dp)) } },
            body = {
                TestsHint("This changes the step in your library. The run keeps its own copy.")
                if (changes.isEmpty()) TestsHint("The fix would not change anything: the step already says this.")
                changes.forEach { change ->
                    AppText(change.field, color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    AppText("− ${change.before.ifBlank { "(empty)" }}", color = DANGER_RED, fontSize = 11.sp, maxLines = DETAIL_MAX_LINES)
                    AppText("+ ${change.after}", color = tc.success, fontSize = 11.sp, maxLines = DETAIL_MAX_LINES)
                }
            },
            footer = {
                DialogActionButton("Apply fix", active = true, enabled = changes.isNotEmpty()) {
                    ui.scope.launch {
                        when (val result = ui.state.applyStepFix(runId, stepId, fixRef)) {
                            is ReportActionResult.Done -> {
                                ui.info(result.message)
                                close()
                            }
                            is ReportActionResult.Failed -> problem = result.message
                        }
                    }
                }
                DialogActionButton("Cancel", active = false) { close() }
            },
        )
    }
}

// ── Expected versus actual ───────────────────────────────────────────

@Composable
private fun rememberBitmap(file: File?): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = file?.let { source ->
            withContext(Dispatchers.IO) { decodeBoundedPreviewImage(source) }
        }
    }
    return bitmap
}

@Composable
private fun ScreenshotPane(title: String, bitmap: ImageBitmap?, missing: String, modifier: Modifier) {
    val tc = tc()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppText(title, color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        if (bitmap != null) {
            Image(bitmap, contentDescription = title, contentScale = ContentScale.Fit, modifier = Modifier.heightIn(max = SCREENSHOT_MAX_HEIGHT))
        } else {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).background(tc.bg.copy(alpha = PANEL_ALPHA), CORNER_SM).padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                AppText(missing, color = tc.td, fontSize = 10.sp)
            }
        }
    }
}

/** The golden screenshot of the step (when it has one) beside the one the lane ended on; just the latter otherwise. */
@Composable
private fun ExpectedVersusActual(run: TestRun, row: MatrixRow.Step, step: StepResult, runDir: File) {
    val ui = LocalTestsUi.current
    val libraryStep = run.suite.cases.firstOrNull { it.id == row.caseId }?.steps?.firstOrNull { it.id == row.stepId }
    val golden = goldenExampleFor(libraryStep)
    val goldenFile = golden?.let { ui.state.testGoldenImageFile(run.suite.id, it.assetPath) }?.takeIf { it.isFile }
    val expected = rememberBitmap(goldenFile)
    val actual = rememberBitmap(step.screenshotFile(runDir))
    if (golden == null && actual == null) return
    if (golden == null) {
        ScreenshotPane("Screenshot at the end of the step", actual, "No screenshot was kept.", Modifier.fillMaxWidth())
        return
    }
    val expectedTitle = "Expected (${golden.caption.ifBlank { golden.assetPath }})"
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= SIDE_BY_SIDE_MIN_WIDTH) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ScreenshotPane(expectedTitle, expected, "The reference image is missing.", Modifier.weight(1f))
                ScreenshotPane("Actual", actual, "No screenshot was kept.", Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ScreenshotPane(expectedTitle, expected, "The reference image is missing.", Modifier.fillMaxWidth())
                ScreenshotPane("Actual", actual, "No screenshot was kept.", Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun StepEvidence(step: StepResult, lane: LaneResult, runDir: File) {
    val tc = tc()
    val log = lane.logPath?.let { File(runDir, it) }
    val excerpt by produceState<String?>(null, log, step.logStartOffset, step.logEndOffset) {
        value = log?.let { withContext(Dispatchers.IO) { runCatching { readLogExcerpt(it, step.logStartOffset, step.logEndOffset) }.getOrNull() } }
    }
    excerpt?.takeIf { it.isNotBlank() }?.let { text ->
        AppText("Log during the step (untrusted)", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        TestsScrollColumn(Modifier.fillMaxWidth().heightIn(max = EXCERPT_MAX_HEIGHT).background(tc.bg, CORNER_SM), contentModifier = Modifier.padding(6.dp)) {
            AppText(text, color = tc.ts, fontSize = 10.sp, fontFamily = MONO, maxLines = Int.MAX_VALUE)
        }
    }
    val transcript = lane.transcriptPath?.let { File(runDir, it) }?.takeIf { it.isFile }
    if (transcript != null) {
        val testsUi = LocalTestsUi.current
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            AppText("Transcript bytes ${step.transcriptStartOffset ?: 0}–${step.transcriptEndOffset ?: 0}", color = tc.td, fontSize = 10.sp)
            AppButton("Open transcript", onClick = { testsUi.openFileReportingFailure(transcript, "the transcript") })
        }
    }
}

// ── Actions ──────────────────────────────────────────────────────────

@Composable
private fun StepActions(run: TestRun, row: MatrixRow.Step, lane: LaneResult, step: StepResult) {
    if (row.setup) return
    val ui = LocalTestsUi.current
    var marking by remember(row.key, lane.laneId) { mutableStateOf(false) }
    var starting by remember(row.key, lane.laneId) { mutableStateOf(false) }
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppButton("Mark as agent error…", onClick = { marking = true })
        if (step.status != StepStatus.PASS && step.status != StepStatus.SKIPPED) {
            AppButton(
                if (step.issueId != null) "Open issue…" else "Create issue…",
                onClick = { ui.view.issueDialog = IssueDialogTarget.FromStep(run.id, lane.laneId, row.caseId, row.iteration, row.stepId) },
            )
        }
        AppButton(
            if (starting) "Starting…" else "Run again up to this step",
            enabled = !starting && run.isFinished,
            onClick = {
                starting = true
                ui.scope.launch {
                    when (val started = ui.state.rerunStep(run.id, lane.laneId, row.caseId, row.stepId)) {
                        is StartRunResult.Started -> ui.view.selectedRunId = started.runId
                        is StartRunResult.Rejected -> {
                            ui.banner = TestsBanner(started.errors.joinToString(" "), isError = true)
                            starting = false
                        }
                    }
                }
            },
        )
    }
    if (marking) AgentErrorDialog(run.id, lane.laneId, row) { marking = false }
}

@Composable
private fun AgentErrorDialog(runId: String, laneId: String, row: MatrixRow.Step, onClose: () -> Unit) {
    val tc = tc()
    val ui = LocalTestsUi.current
    var note by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    fun close() {
        onClose()
        ui.reclaimFocus()
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TestsDialogFrame(
            title = "Mark as an agent error",
            width = FIX_DIALOG_WIDTH,
            status = { problem?.let { TestsErrorText(it, Modifier.padding(top = 8.dp)) } },
            body = {
                TestsHint("Say what the agent got wrong (the app is fine). The note is kept with the step's result.")
                DictatedInlineField(
                    value = note,
                    onValue = { note = it },
                    proseTargetId = "agent-error:$runId:$laneId:${row.stepId}:note",
                    placeholder = "What did the agent get wrong?",
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    fontSize = 12.sp,
                )
            },
            footer = {
                DialogActionButton("Save note", active = true, enabled = note.isNotBlank()) {
                    ui.scope.launch {
                        when (val result = ui.state.markAgentError(runId, laneId, row.caseId, row.iteration, row.stepId, note)) {
                            is ReportActionResult.Done -> close()
                            is ReportActionResult.Failed -> problem = result.message
                        }
                    }
                }
                DialogActionButton("Cancel", active = false) { close() }
            },
        )
    }
}
