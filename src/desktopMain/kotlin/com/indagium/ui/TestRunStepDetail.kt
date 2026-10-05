package com.indagium.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.JudgeComparison
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.StepFix
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import com.indagium.testing.run.StartRunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

// The detail of one step of one lane in the report: what was asked, what the agent said, the automatic checks, the judge's
// verdict with its reasoning, classification and suggested fix, the expected (golden) screenshot beside the actual one, the
// log and the transcript - and the actions: apply the suggested fix to the library step (after a before/after confirm),
// mark the result as the agent's mistake, run the step again. Text from an agent, a model, the device or a script is
// labelled and shown, never acted on. Images are decoded on IO.

private val SCREENSHOT_MAX_HEIGHT = 360.dp
private val EXCERPT_MAX_HEIGHT = 200.dp
private val FIX_DIALOG_WIDTH = 520.dp
private val FIX_DIALOG_SHAPE = RoundedCornerShape(8.dp)
private const val MILLIS_PER_SECOND = 1_000L
private const val DETAIL_MAX_LINES = 12
private const val PANEL_ALPHA = .6f

/** A step of a lane picked in the matrix: [rowKey] is the matrix row's key. */
internal data class StepSelection(val rowKey: String, val laneId: String)

@Composable
internal fun StepDetailFor(run: TestRun, runDir: File, selection: StepSelection) {
    val row = remember(run, selection.rowKey) { buildMatrix(run).filterIsInstance<MatrixRow.Step>().firstOrNull { it.key == selection.rowKey } }
    val result = row?.cells?.firstOrNull { it.laneId == selection.laneId }?.result ?: return
    val lane = run.lane(selection.laneId) ?: return
    TestsSectionTitle("Step ${result.stepNumber}")
    StepDetail(run, row, result, lane, runDir)
}

@Composable
private fun StepDetail(run: TestRun, row: MatrixRow.Step, step: StepResult, lane: LaneResult, runDir: File) {
    val tc = tc()
    Column(
        Modifier.fillMaxWidth().background(tc.p, CORNER_MD).border(1.dp, tc.br, CORNER_MD).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(step.status.label(), stepColor(step.status))
            AppText("${step.attempts} attempt(s) · ${step.durationMs / MILLIS_PER_SECOND}s", color = tc.td, fontSize = 10.sp)
            if (step.issueRequested) TestsBadge("issue requested")
            if (step.judgeInconclusive) TestsBadge("judge inconclusive")
            if (step.agentError != null) TestsBadge("marked as agent error")
        }
        DetailLine("Action", step.action)
        DetailLine("Expected", step.expected)
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
        run.comparisons.filter { it.stepId == row.stepId && it.iteration == row.iteration && it.caseId == row.caseId }
            .forEach { ComparisonSection(run, row, it) }
        ExpectedVersusActual(run, row, step, runDir)
        StepEvidence(step, lane, runDir)
        StepActions(run, row, lane)
    }
}

@Composable
internal fun checkColor(status: CheckStatus): Color = when (status) {
    CheckStatus.PASS -> tc().ok
    CheckStatus.FAIL, CheckStatus.ERROR -> DANGER_RED
    CheckStatus.NOT_EVALUATED -> tc().td
}

@Composable
private fun DetailLine(label: String, value: String, mono: Boolean = false) {
    if (value.isBlank()) return
    val tc = tc()
    Column {
        AppText(label.uppercase(), color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        AppText(value, color = tc.tx, fontSize = 11.sp, fontFamily = if (mono) MONO else LocalUiFontFamily.current, maxLines = DETAIL_MAX_LINES)
    }
}

// ── The judge ────────────────────────────────────────────────────────

@Composable
private fun JudgeSection(run: TestRun, row: MatrixRow.Step, judge: StepJudgement) {
    val tc = tc()
    Column(Modifier.fillMaxWidth().background(tc.p2, CORNER_MD).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText("JUDGE", color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            StatusChip(judge.verdict.label(), verdictColor(judge.verdict))
            TestsBadge(judge.classification.label())
        }
        judge.error?.let { TestsErrorText(it) }
        DetailLine("Reasoning (written by the judge model)", judge.reasoning)
        judge.suggestedFix?.let { FixLine(run, row, it, judge.id, judge.fixApplied) }
    }
}

@Composable
private fun ComparisonSection(run: TestRun, row: MatrixRow.Step, comparison: JudgeComparison) {
    val tc = tc()
    Column(Modifier.fillMaxWidth().background(tc.p2, CORNER_MD).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText("LANES COMPARED", color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            TestsBadge(comparison.classification.label())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
    AppText("SUGGESTED STEP FIX", color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
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
        Column(Modifier.width(FIX_DIALOG_WIDTH).background(tc.p, FIX_DIALOG_SHAPE).border(1.dp, tc.br, FIX_DIALOG_SHAPE).padding(20.dp)) {
            AppText("Apply the suggested fix?", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            TestsHint("This changes the step in your library. The run keeps its own copy.")
            if (changes.isEmpty()) TestsHint("The fix would not change anything: the step already says this.")
            changes.forEach { change ->
                AppText(change.field.uppercase(), color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                AppText("− ${change.before.ifBlank { "(empty)" }}", color = DANGER_RED, fontSize = 11.sp, maxLines = DETAIL_MAX_LINES)
                AppText("+ ${change.after}", color = tc.ok, fontSize = 11.sp, maxLines = DETAIL_MAX_LINES)
            }
            problem?.let { TestsErrorText(it, Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.padding(top = 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
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
            }
        }
    }
}

// ── Expected versus actual ───────────────────────────────────────────

@Composable
private fun rememberBitmap(file: File?): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = file?.let { source ->
            withContext(Dispatchers.IO) { runCatching { org.jetbrains.skia.Image.makeFromEncoded(source.readBytes()).toComposeImageBitmap() }.getOrNull() }
        }
    }
    return bitmap
}

@Composable
private fun ScreenshotPane(title: String, bitmap: ImageBitmap?, missing: String, modifier: Modifier) {
    val tc = tc()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppText(title, color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
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
        ScreenshotPane("SCREENSHOT AT THE END OF THE STEP", actual, "No screenshot was kept.", Modifier.fillMaxWidth())
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenshotPane("EXPECTED (${golden.caption.ifBlank { golden.assetPath }})", expected, "The reference image is missing.", Modifier.weight(1f))
        ScreenshotPane("ACTUAL", actual, "No screenshot was kept.", Modifier.weight(1f))
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
        AppText("LOG DURING THE STEP (untrusted)", color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.fillMaxWidth().heightIn(max = EXCERPT_MAX_HEIGHT).background(tc.bg, CORNER_SM).verticalScroll(rememberScrollState()).padding(6.dp)) {
            AppText(text, color = tc.ts, fontSize = 10.sp, fontFamily = MONO, maxLines = Int.MAX_VALUE)
        }
    }
    val transcript = lane.transcriptPath?.let { File(runDir, it) }?.takeIf { it.isFile }
    if (transcript != null) {
        val scope = LocalTestsUi.current.scope
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText("Transcript bytes ${step.transcriptStartOffset ?: 0}–${step.transcriptEndOffset ?: 0}", color = tc.td, fontSize = 10.sp)
            AppButton("Open transcript", onClick = { scope.launch(Dispatchers.IO) { openFile(transcript) } })
        }
    }
}

private fun openFile(file: File) {
    runCatching { if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) Desktop.getDesktop().open(file) }
}

// ── Actions ──────────────────────────────────────────────────────────

@Composable
private fun StepActions(run: TestRun, row: MatrixRow.Step, lane: LaneResult) {
    if (row.setup) return
    val ui = LocalTestsUi.current
    var marking by remember(row.key, lane.laneId) { mutableStateOf(false) }
    var starting by remember(row.key, lane.laneId) { mutableStateOf(false) }
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppButton("Mark as agent error…", onClick = { marking = true })
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
        Column(Modifier.width(FIX_DIALOG_WIDTH).background(tc.p, FIX_DIALOG_SHAPE).border(1.dp, tc.br, FIX_DIALOG_SHAPE).padding(20.dp)) {
            AppText("Mark as an agent error", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            TestsHint("Say what the agent got wrong (the app is fine). The note is kept with the step's result.")
            InlineField(
                value = note,
                onValue = { note = it },
                placeholder = "What did the agent get wrong?",
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                fontSize = 12.sp,
            )
            problem?.let { TestsErrorText(it, Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.padding(top = 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                DialogActionButton("Save note", active = true, enabled = note.isNotBlank()) {
                    ui.scope.launch {
                        when (val result = ui.state.markAgentError(runId, laneId, row.caseId, row.iteration, row.stepId, note)) {
                            is ReportActionResult.Done -> close()
                            is ReportActionResult.Failed -> problem = result.message
                        }
                    }
                }
                DialogActionButton("Cancel", active = false) { close() }
            }
        }
    }
}
