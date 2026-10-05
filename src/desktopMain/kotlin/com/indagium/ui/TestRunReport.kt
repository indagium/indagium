package com.indagium.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.RunSummary
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.run.PauseDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

// The Runs screen of the Tests workspace: the list of runs (this launch and stored ones), and one run's report — a
// cases x steps x lanes matrix (one column per lane, a single one for now), the step detail (what the agent claimed and
// saw, the check results, screenshot, log excerpt, transcript) and, while a run is live, its progress with minimal
// Allow/Deny and pause controls. Text that came from an agent, the device or a script is labelled untrusted: it is shown,
// never acted on.

private val STATUS_COLUMN = 110.dp
private val ROW_HEIGHT = 26.dp
private val SCREENSHOT_MAX_HEIGHT = 360.dp
private val EXCERPT_MAX_HEIGHT = 200.dp
private const val LIVE_POLL_MS = 500L
private const val CHIP_ALPHA = .16f

@Composable
internal fun TestsRunsScreen() {
    val ui = LocalTestsUi.current
    val runId = ui.view.selectedRunId
    if (runId == null) TestRunList() else TestRunReportScreen(runId)
}

// ── List ─────────────────────────────────────────────────────────────

@Composable
private fun TestRunList() {
    val tc = tc()
    val ui = LocalTestsUi.current
    val runs = ui.state.testRuns
    val revision = runs.map { "${it.id}:${it.status}" }
    val summaries by produceState<List<RunSummary>>(emptyList(), revision) { value = ui.state.testRunCoordinator.listRuns() }
    TestsScreenScaffold {
        AppText("Runs", color = tc.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        TestsHint("Start a run from a suite's “Run suite…” or a case's “Run this case…”. A run keeps its evidence in the save folder.")
        Spacer(Modifier.height(10.dp))
        if (summaries.isEmpty()) TestsHint("No runs yet.")
        summaries.forEach { summary -> RunListRow(summary) { ui.view.selectedRunId = summary.id } }
    }
}

@Composable
private fun RunListRow(summary: RunSummary, onClick: () -> Unit) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusChip(summary.status.label(), runColor(summary.status), Modifier.width(STATUS_COLUMN))
        AppText(
            summary.suiteName.ifBlank { "Untitled suite" },
            color = tc.tx, fontSize = 12.sp, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        AppText(summary.line(), color = tc.td, fontSize = 10.sp)
        AppText(formatRunTime(summary.createdAt), color = tc.td, fontSize = 10.sp, fontFamily = MONO)
    }
}

// ── Colours and chips ────────────────────────────────────────────────

@Composable
private fun runColor(status: RunStatus): Color = when (status) {
    RunStatus.PASSED -> tc().ok
    RunStatus.FAILED, RunStatus.ERROR -> DANGER_RED
    RunStatus.RUNNING, RunStatus.QUEUED -> tc().ac
    RunStatus.CANCELLED -> tc().warn
}

@Composable
private fun stepColor(status: StepStatus): Color = when (status) {
    StepStatus.PASS -> tc().ok
    StepStatus.FAIL, StepStatus.ERROR, StepStatus.TIMEOUT -> DANGER_RED
    StepStatus.BLOCKED -> tc().warn
    StepStatus.SKIPPED -> tc().td
}

@Composable
private fun caseColor(status: CaseStatus?): Color = when (status) {
    CaseStatus.PASS -> tc().ok
    CaseStatus.FAIL, CaseStatus.ERROR -> DANGER_RED
    CaseStatus.BLOCKED, CaseStatus.CANCELLED -> tc().warn
    CaseStatus.SKIPPED -> tc().td
    null -> tc().ac
}

@Composable
private fun StatusChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.background(color.copy(alpha = CHIP_ALPHA), CORNER_SM).padding(horizontal = 6.dp, vertical = 2.dp)) {
        AppText(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Report ───────────────────────────────────────────────────────────

private data class StepSelection(val rowKey: String, val laneId: String)

@Composable
private fun TestRunReportScreen(runId: String) {
    val ui = LocalTestsUi.current
    val coordinator = ui.state.testRunCoordinator
    val live = ui.state.testRuns.firstOrNull { it.id == runId }
    val loaded by produceState<TestRun?>(null, runId, live == null) { if (live == null) value = coordinator.loadRun(runId) }
    val run = live ?: loaded
    TestsScreenScaffold {
        AppButton("‹ Runs", onClick = { ui.view.selectedRunId = null }, variant = ButtonVariant.Ghost)
        Spacer(Modifier.height(6.dp))
        if (run == null) {
            TestsHint("Loading the run…")
        } else {
            RunReport(run, coordinator.runDir(runId))
        }
    }
}

@Composable
private fun RunReport(run: TestRun, runDir: File) {
    val tc = tc()
    val ui = LocalTestsUi.current
    var selection by remember(run.id) { mutableStateOf<StepSelection?>(null) }
    var tick by remember(run.id) { mutableIntStateOf(0) }
    val active = run.status == RunStatus.QUEUED || run.status == RunStatus.RUNNING
    LaunchedEffect(run.id, active) {
        while (active) {
            delay(LIVE_POLL_MS)
            tick++
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppText(
            run.suite.name.ifBlank { "Untitled suite" },
            color = tc.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false),
        )
        StatusChip(run.status.label(), runColor(run.status))
        if (active) AppButton("Cancel run", onClick = { ui.state.testRunCoordinator.cancel(run.id) }, isDanger = true)
    }
    AppText("Started ${formatRunTime(run.startedAt ?: run.createdAt)} · finished ${formatRunTime(run.finishedAt)}", color = tc.td, fontSize = 10.sp)
    run.warnings.forEach { TestsLockedNotice(it, Modifier.padding(top = 6.dp)) }
    run.error?.let { TestsErrorText(it) }
    run.lanes.forEach { lane -> LaneProgress(run, lane) }
    if (active) LivePanel(run, tick)
    TestsSectionTitle("Results")
    ReportMatrix(run, selection) { selection = it }
    selection?.let { chosen -> StepDetailFor(run, runDir, chosen) }
}

@Composable
private fun LaneProgress(run: TestRun, lane: LaneResult) {
    val tc = tc()
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TestsBadge(if (lane.config.kind == com.indagium.testing.model.LaneKind.EXTERNAL) "External" else "Agent")
        AppText(run.progressLine(lane.laneId), color = tc.ts, fontSize = 11.sp, maxLines = 2)
    }
    lane.error?.let { TestsErrorText(it) }
}

// ── Live: confirmations and pauses ───────────────────────────────────

@Composable
private fun LivePanel(run: TestRun, tick: Int) {
    val tc = tc()
    val coordinator = LocalTestsUi.current.state.testRunCoordinator
    val confirmations = remember(tick, run) { coordinator.pendingConfirmations().filter { it.runId == run.id } }
    val pauses = remember(tick, run) { coordinator.pausedSteps().filter { it.runId == run.id } }
    confirmations.forEach { card ->
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(tc.warnBg, CORNER_MD).padding(10.dp)) {
            AppText("The agent wants to: ${card.description}", color = tc.tx, fontSize = 12.sp, maxLines = 3)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton("Allow", onClick = { coordinator.resolveConfirmation(run.id, card.confirmationId, true) }, variant = ButtonVariant.Primary)
                AppButton("Deny", onClick = { coordinator.resolveConfirmation(run.id, card.confirmationId, false) })
            }
        }
    }
    pauses.forEach { paused ->
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(tc.warnBg, CORNER_MD).padding(10.dp)) {
            AppText("Paused at step ${paused.step.stepNumber}: ${paused.step.action}", color = tc.tx, fontSize = 12.sp, maxLines = 2)
            AppText("${paused.step.status.label()} — ${paused.step.observation.take(PAUSE_OBSERVATION_CHARS)}", color = tc.ts, fontSize = 11.sp, maxLines = 3)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton("Retry", onClick = { coordinator.resumePausedStep(run.id, paused.laneId, PauseDecision.RETRY) })
                AppButton(
                    "Continue",
                    onClick = { coordinator.resumePausedStep(run.id, paused.laneId, PauseDecision.CONTINUE) },
                    variant = ButtonVariant.Primary,
                )
                AppButton("Stop case", onClick = { coordinator.resumePausedStep(run.id, paused.laneId, PauseDecision.STOP) }, isDanger = true)
            }
        }
    }
}

private const val PAUSE_OBSERVATION_CHARS = 300

// ── Matrix ───────────────────────────────────────────────────────────

@Composable
private fun ReportMatrix(run: TestRun, selection: StepSelection?, onSelect: (StepSelection?) -> Unit) {
    val tc = tc()
    val rows = remember(run) { buildMatrix(run) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText("CASE / STEP", color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        run.lanes.forEach { lane ->
            AppText(
                lane.config.deviceSerial,
                color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(STATUS_COLUMN),
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
    if (rows.isEmpty()) TestsHint("Nothing to show yet.")
    rows.forEach { row ->
        when (row) {
            is MatrixRow.Case -> CaseMatrixRow(row)
            is MatrixRow.Step -> StepMatrixRow(row, selection, onSelect)
        }
    }
}

@Composable
private fun CaseMatrixRow(row: MatrixRow.Case) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppText(
            row.name + if (row.iteration > 1) " (run ${row.iteration})" else "",
            color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        row.cells.forEach { cell ->
            Box(Modifier.width(STATUS_COLUMN)) {
                if (cell.present) StatusChip(cell.status.label(), caseColor(cell.status)) else AppText("—", color = tc.td, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun StepMatrixRow(row: MatrixRow.Step, selection: StepSelection?, onSelect: (StepSelection?) -> Unit) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp).heightIn(min = ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppText(
            (if (row.setup) "setup · " else "") + "${row.number}. ${row.action}",
            color = tc.ts, fontSize = 11.sp, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        row.cells.forEach { cell ->
            val chosen = selection?.rowKey == row.key && selection.laneId == cell.laneId
            Box(
                Modifier.width(STATUS_COLUMN).background(if (chosen) tc.abg else Color.Transparent, CORNER_SM)
                    .clickable(enabled = cell.result != null) { onSelect(if (chosen) null else StepSelection(row.key, cell.laneId)) }
                    .padding(2.dp),
            ) {
                val result = cell.result
                if (result == null) {
                    AppText("·", color = tc.td, fontSize = 11.sp)
                } else {
                    StatusChip(result.status.label() + if (result.attempts > 1) " ×${result.attempts}" else "", stepColor(result.status))
                }
            }
        }
    }
}

// ── Step detail ──────────────────────────────────────────────────────

@Composable
private fun StepDetailFor(run: TestRun, runDir: File, selection: StepSelection) {
    val row = remember(run, selection.rowKey) { buildMatrix(run).filterIsInstance<MatrixRow.Step>().firstOrNull { it.key == selection.rowKey } }
    val cell = row?.cells?.firstOrNull { it.laneId == selection.laneId }?.result ?: return
    val lane = run.lane(selection.laneId) ?: return
    TestsSectionTitle("Step ${cell.stepNumber}")
    StepDetail(cell, lane, runDir)
}

@Composable
private fun StepDetail(step: StepResult, lane: LaneResult, runDir: File) {
    val tc = tc()
    Column(
        Modifier.fillMaxWidth().background(tc.p, CORNER_MD).border(1.dp, tc.br, CORNER_MD).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(step.status.label(), stepColor(step.status))
            AppText("${step.attempts} attempt(s) · ${step.durationMs / MILLIS_PER_SECOND}s", color = tc.td, fontSize = 10.sp)
            if (step.issueRequested) TestsBadge("issue requested")
        }
        DetailLine("Action", step.action)
        DetailLine("Expected", step.expected)
        step.agentClaim?.let { DetailLine("Agent claimed", it) }
        if (step.observation.isNotBlank()) DetailLine("Observation (untrusted)", step.observation, mono = true)
        step.note?.let { DetailLine("Note", it) }
        step.checks.forEach { check ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                StatusChip(check.status.name.lowercase(), checkColor(check.status))
                AppText("${check.kind}: ${check.detail}", color = tc.ts, fontSize = 11.sp, maxLines = 4, modifier = Modifier.weight(1f))
            }
        }
        StepEvidence(step, lane, runDir)
    }
}

private const val MILLIS_PER_SECOND = 1_000L

@Composable
private fun checkColor(status: CheckStatus): Color = when (status) {
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

private const val DETAIL_MAX_LINES = 12

@Composable
private fun StepEvidence(step: StepResult, lane: LaneResult, runDir: File) {
    val tc = tc()
    val screenshot = step.screenshotFile(runDir)
    if (screenshot != null) {
        val bitmap by produceState<ImageBitmap?>(null, screenshot) {
            value = withContext(Dispatchers.IO) {
                runCatching { org.jetbrains.skia.Image.makeFromEncoded(screenshot.readBytes()).toComposeImageBitmap() }.getOrNull()
            }
        }
        bitmap?.let {
            Image(
                it,
                contentDescription = "Screenshot at the end of the step",
                contentScale = ContentScale.Fit,
                modifier = Modifier.heightIn(max = SCREENSHOT_MAX_HEIGHT),
            )
        }
    }
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
