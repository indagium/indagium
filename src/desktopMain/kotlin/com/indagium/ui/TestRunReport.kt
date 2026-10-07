@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.RunSummary
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.run.ComparedStepPresence
import com.indagium.testing.run.TestRunReportExportProgress
import com.indagium.testing.run.TestRunReportFormat
import com.indagium.testing.run.availableRunArtifactPaths
import com.indagium.testing.run.compareTestRuns
import com.indagium.testing.run.failedCaseIds
import com.indagium.testing.run.previousTerminalRunSummaryOfSameSuite
import com.indagium.testing.run.rerunFailedCasesConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// The Runs screen of the Tests workspace: the list of runs (this launch and stored ones), and one run's report — a
// cases x steps x lanes matrix (one column per lane, a single one for now), the step detail (what the agent claimed and
// saw, the check results, screenshot, log excerpt, transcript) and, while a run is live, its progress with minimal
// Allow/Deny and pause controls. Text that came from an agent, the device or a script is labelled untrusted: it is shown,
// never acted on.

private val STATUS_COLUMN = 110.dp
private val CONSENSUS_COLUMN = 170.dp
private val ROW_HEIGHT = 26.dp
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
internal fun runColor(status: RunStatus): Color = when (status) {
    RunStatus.PASSED -> tc().ok
    RunStatus.FAILED, RunStatus.ERROR -> DANGER_RED
    RunStatus.RUNNING, RunStatus.QUEUED -> tc().ac
    RunStatus.CANCELLED -> tc().warn
}

@Composable
internal fun stepColor(status: StepStatus): Color = when (status) {
    StepStatus.PASS -> tc().ok
    StepStatus.FAIL, StepStatus.ERROR, StepStatus.TIMEOUT -> DANGER_RED
    StepStatus.BLOCKED -> tc().warn
    StepStatus.SKIPPED -> tc().td
}

@Composable
internal fun caseColor(status: CaseStatus?): Color = when (status) {
    CaseStatus.PASS -> tc().ok
    CaseStatus.FAIL, CaseStatus.ERROR -> DANGER_RED
    CaseStatus.BLOCKED, CaseStatus.CANCELLED -> tc().warn
    CaseStatus.SKIPPED -> tc().td
    null -> tc().ac
}

@Composable
internal fun StatusChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.background(color.copy(alpha = CHIP_ALPHA), CORNER_SM).padding(horizontal = 6.dp, vertical = 2.dp)) {
        AppText(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Report ───────────────────────────────────────────────────────────

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

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun RunReport(run: TestRun, runDir: File) {
    val tc = tc()
    val ui = LocalTestsUi.current
    var selection by remember(run.id) { mutableStateOf<StepSelection?>(null) }
    var tick by remember(run.id) { mutableIntStateOf(0) }
    var filters by remember(run.id) { mutableStateOf(emptySet<TestRunReportFilter>()) }
    var showExport by remember(run.id) { mutableStateOf(false) }
    var showComparePicker by remember(run.id) { mutableStateOf(false) }
    var compareRunId by remember(run.id) { mutableStateOf<String?>(null) }
    val summaries by produceState(emptyList<RunSummary>(), run.id, run.suite.id) {
        value = ui.state.testRunCoordinator.listRunSummariesForSuite(run.suite.id)
    }
    val previousRun = remember(summaries, run.id, run.suite.id, run.createdAt) { previousTerminalRunSummaryOfSameSuite(summaries, run) }
    LaunchedEffect(run.id, previousRun?.id) { if (compareRunId == null) compareRunId = previousRun?.id }
    val comparedRun by produceState<TestRun?>(null, compareRunId) {
        value = compareRunId?.let { ui.state.testRunCoordinator.loadRun(it) }
    }
    val active = run.status == RunStatus.QUEUED || run.status == RunStatus.RUNNING
    val failedIds = remember(run) { failedCaseIds(run) }
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
    }
    AppText("Started ${formatRunTime(run.startedAt ?: run.createdAt)} · finished ${formatRunTime(run.finishedAt)}", color = tc.td, fontSize = 10.sp)
    run.warnings.forEach { TestsLockedNotice(it, Modifier.padding(top = 6.dp)) }
    run.error?.let { TestsErrorText(it) }
    ReportMetrics(run)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppButton("Re-run failed (${failedIds.size})", onClick = {
            rerunFailedCasesConfig(run).onSuccess { config ->
                ui.view.runDialog = RunDialogTarget(run.suite.id, initialCaseIds = config.caseIds.orEmpty().toSet(), initialConfig = config)
            }.onFailure { ui.info(it.message ?: "Failed cases cannot be re-run.") }
        }, enabled = failedIds.isNotEmpty() && !active)
        AppButton("Export report…", onClick = { showExport = true }, enabled = !active)
        AppButton(if (comparedRun == null) "Compare with previous…" else "Compare with ${comparedRun?.suite?.name ?: "run"}…", onClick = { showComparePicker = true }, enabled = !active)
    }
    ReportFilterRow(filters, onToggle = { filter -> filters = if (filter in filters) filters - filter else filters + filter }, onClear = { filters = emptySet() })
    comparedRun?.let { RunComparisonPanel(it, run) }
    if (active) TestRunLiveView(run, runDir, tick) else run.lanes.forEach { lane -> LaneProgress(run, lane, runDir) }
    TestsSectionTitle("Results")
    ReportMatrix(run, selection, filters) { selection = it }
    selection?.let { chosen -> StepDetailFor(run, runDir, chosen) }
    if (showComparePicker) {
        RunComparisonPicker(
            summaries.filter { it.id != run.id && it.suiteId == run.suite.id && it.status !in setOf(RunStatus.QUEUED, RunStatus.RUNNING) },
            compareRunId,
            onChoose = { compareRunId = it; showComparePicker = false },
            onDismiss = { showComparePicker = false },
        )
    }
    if (showExport) ReportExportDialog(run, runDir, onDismiss = { showExport = false })
}

@Composable
private fun ReportMetrics(run: TestRun) {
    val metrics = remember(run) { reportMetrics(run) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricCard("Passed · final cases", metrics.passedCases, tc().ok, Modifier.weight(1f))
            MetricCard("Failed · final cases", metrics.failedCases, DANGER_RED, Modifier.weight(1f))
            MetricCard("Blocked / error", metrics.blockedOrErrorCases, tc().warn, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricCard("Disagreements", metrics.disagreements, DANGER_RED, Modifier.weight(1f))
            MetricCard("Unresolved judging", metrics.unresolvedJudging, tc().warn, Modifier.weight(1f))
            MetricCard("Cases / repeats", "${metrics.caseIterations} / ${run.config.repeat}", tc().ac, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCard(label: String, value: Any, color: Color, modifier: Modifier = Modifier) {
    val tc = tc()
    Column(modifier.background(tc.p, CORNER_SM).border(1.dp, tc.br, CORNER_SM).padding(horizontal = 10.dp, vertical = 7.dp)) {
        AppText(value.toString(), color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        AppText(label, color = tc.td, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun ReportFilterRow(
    filters: Set<TestRunReportFilter>,
    onToggle: (TestRunReportFilter) -> Unit,
    onClear: () -> Unit,
) {
    FlowRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppText("Filter", color = tc().td, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp))
        TestRunReportFilter.entries.forEach { filter ->
            AppButton(filter.label, onClick = { onToggle(filter) }, variant = if (filter in filters) ButtonVariant.Primary else ButtonVariant.Ghost, horizontalPadding = 7.dp)
        }
        if (filters.isNotEmpty()) AppButton("Clear", onClick = onClear, variant = ButtonVariant.Ghost)
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun RunComparisonPicker(
    runs: List<RunSummary>,
    selectedId: String?,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tc = tc()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(560.dp).heightIn(max = 620.dp).background(tc.p, CORNER_MD).border(1.dp, tc.br, CORNER_MD).padding(16.dp)) {
            AppText("Compare runs", color = tc.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                runs.forEach { candidate ->
                    Row(Modifier.fillMaxWidth().clickable { onChoose(candidate.id) }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(candidate.status.label(), runColor(candidate.status))
                        AppText(candidate.suiteName, color = tc.tx, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        AppText("${candidate.laneCount} lanes · ×${candidate.repeat} · ${candidate.id.take(8)} · ${formatRunTime(candidate.createdAt)}", color = tc.td, fontSize = 9.sp, fontFamily = MONO)
                        if (candidate.id == selectedId) TestsBadge("selected")
                    }
                }
                if (runs.isEmpty()) TestsHint("No other completed runs of this suite are available.")
            }
            AppButton("Close", onClick = onDismiss, modifier = Modifier.align(Alignment.End))
        }
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun RunComparisonPanel(previous: TestRun, current: TestRun) {
    val tc = tc()
    val rows = remember(previous, current) { compareTestRuns(previous, current) }
    Column(Modifier.fillMaxWidth().background(tc.p, CORNER_MD).border(1.dp, tc.br, CORNER_MD).padding(10.dp)) {
        AppText("Compared with ${previous.suite.name} · ${formatRunTime(previous.finishedAt)}", color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (rows.isEmpty()) TestsHint("Stable case and step IDs, definitions, and statuses match.")
        Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState())) {
            rows.forEach { row ->
                val label = when {
                    row.suiteDefinitionChanged -> "Suite context changed"
                    row.casePresence.name == "ADDED" -> "Case added"
                    row.casePresence.name == "REMOVED" -> "Case removed"
                    row.caseDefinitionChanged -> "Case context changed"
                    row.presence == ComparedStepPresence.ADDED -> "Step added"
                    row.presence == ComparedStepPresence.REMOVED -> "Step removed"
                    row.definitionChanged -> "Definition changed"
                    else -> "Status changed"
                }
                AppText("$label · ${row.caseName}${row.stepId.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}", color = tc.ts, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                AppText("${row.previousStatuses} → ${row.currentStatuses}${row.previousAction?.let { "\n$it" }.orEmpty()}${row.currentAction?.let { "\n→ $it" }.orEmpty()}", color = tc.td, fontSize = 9.sp, maxLines = 4)
            }
        }
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun ReportExportDialog(run: TestRun, runDir: File, onDismiss: () -> Unit) {
    val tc = tc()
    val ui = LocalTestsUi.current
    var format by remember(run.id) { mutableStateOf(TestRunReportFormat.JSON) }
    var destination by remember(run.id) { mutableStateOf("") }
    val evidencePaths by produceState(emptyList<String>(), run.id) {
        value = withContext(Dispatchers.IO) { availableRunArtifactPaths(run, runDir) }
    }
    var selectedEvidence by remember(run.id) { mutableStateOf(emptySet<String>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<TestRunReportExportProgress?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun export() {
        if (destination.isBlank() || running) return
        running = true
        message = null
        job = ui.scope.launch {
            try {
                val result = ui.state.exportTestRunReport(run.id, destination, format, selectedEvidence.toList(), overwrite = false) { progress = it }
                result.fold(
                    onSuccess = { message = "Saved ${it.file.absolutePath} (${it.evidenceFiles} evidence files)." },
                    onFailure = { message = it.message ?: "Could not export the report." },
                )
            } catch (_: CancellationException) {
                message = "Export cancelled; the destination was left unchanged."
            } finally {
                running = false
                job = null
            }
        }
    }
    Dialog(onDismissRequest = { if (running) job?.cancel() else onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(620.dp).heightIn(max = 700.dp).background(tc.p, CORNER_MD).border(1.dp, tc.br, CORNER_MD).padding(16.dp)) {
            AppText("Export run report", color = tc.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            TestsHint("Choose a local JSON, Markdown, or evidence ZIP destination. Existing files are never replaced by this dialog.")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TestRunReportFormat.entries.forEach { option ->
                    AppButton(option.name.replace('_', ' '), onClick = { format = option }, variant = if (format == option) ButtonVariant.Primary else ButtonVariant.Secondary)
                }
            }
            AppText("Destination path", color = tc.ts, fontSize = 10.sp)
            BasicTextField(
                value = destination,
                onValueChange = { destination = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = tc.tx, fontSize = 11.sp, fontFamily = MONO),
                modifier = Modifier.fillMaxWidth().background(tc.bg, CORNER_SM).border(1.dp, tc.br, CORNER_SM).padding(8.dp),
                decorationBox = { inner ->
                    Box {
                        if (destination.isBlank()) {
                            AppText("/path/to/report.${format.name.lowercase()}", color = tc.td, fontSize = 10.sp)
                        }
                        inner()
                    }
                },
            )
            if (format == TestRunReportFormat.EVIDENCE_ZIP) {
                TestsSectionTitle("Saved evidence")
                Column(Modifier.weight(1f, fill = false).heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    evidencePaths.forEach { path ->
                        CheckRow(path in selectedEvidence, { selectedEvidence = if (path in selectedEvidence) selectedEvidence - path else selectedEvidence + path }) {
                            AppText(path, color = tc.ts, fontSize = 9.sp, fontFamily = MONO, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                    if (evidencePaths.isEmpty()) TestsHint("No saved screenshots, logs, or tool activity paths are available.")
                }
            }
            progress?.let { TestsHint("${it.percent}% · ${it.current}") }
            message?.let { TestsHint(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                AppButton(if (running) "Cancel export" else "Export", onClick = { if (running) job?.cancel() else export() }, enabled = running || destination.isNotBlank(), variant = ButtonVariant.Primary)
                AppButton("Close", onClick = onDismiss, enabled = !running)
            }
        }
    }
}

private const val MAX_REPORT_ACTIVITY_BYTES = 20 * 1024 * 1024
private const val ACTIVITY_PAGE_SIZE = 60

private fun readActivityLines(runDir: File, relativePath: String?): List<String> {
    if (relativePath == null) return emptyList()
    val bytes = com.indagium.testing.run.readBoundedRunArtifact(runDir, relativePath, MAX_REPORT_ACTIVITY_BYTES) ?: return emptyList()
    return bytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList()
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun LaneProgress(run: TestRun, lane: LaneResult, runDir: File) {
    val tc = tc()
    var showActivity by remember(run.id, lane.laneId) { mutableStateOf(false) }
    var visibleActivityCount by remember(run.id, lane.laneId) { mutableIntStateOf(ACTIVITY_PAGE_SIZE) }
    val activityKey = "${run.id}:${lane.laneId}:${lane.toolActivityPath}:$showActivity"
    val savedActivity by produceState(emptyList<String>(), activityKey) {
        value = if (showActivity) withContext(Dispatchers.IO) { readActivityLines(runDir, lane.toolActivityPath) } else emptyList()
    }
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TestsBadge(if (lane.config.kind == LaneKind.EXTERNAL) "External" else "Agent")
        AppText(run.progressLine(lane.laneId), color = tc.ts, fontSize = 11.sp, maxLines = 2)
    }
    lane.error?.let { TestsErrorText(it) }
    if (lane.toolCalls.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppText("${lane.toolCalls.size} recent tool activities", color = tc.td, fontSize = 10.sp, modifier = Modifier.weight(1f))
            AppButton(if (showActivity) "Hide" else "Activity", onClick = { showActivity = !showActivity }, variant = ButtonVariant.Ghost)
        }
        if (showActivity) {
            lane.toolActivityPath?.let { path ->
                val from = (savedActivity.size - visibleActivityCount).coerceAtLeast(0)
                if (from > 0) {
                    AppButton("Load earlier activity", onClick = { visibleActivityCount += ACTIVITY_PAGE_SIZE }, variant = ButtonVariant.Ghost)
                }
                savedActivity.drop(from).forEach { line ->
                    val decoded = runCatching { com.indagium.debug.Json.decode(line) as? Map<*, *> }.getOrNull()
                    if (decoded?.get("phase") == "truncated") {
                        TestsHint(decoded["reason"]?.toString() ?: "Older activity was truncated by the evidence limit.")
                    } else {
                        val tool = decoded?.get("toolName")?.toString() ?: "Tool activity"
                        val status = decoded?.get("status")?.toString()?.lowercase() ?: "unknown"
                        val context = listOf("caseId", "stepId", "iteration", "attempt").mapNotNull { key -> decoded?.get(key)?.let { "$key=$it" } }.joinToString(" · ")
                        val args = decoded?.get("argumentsPreview")?.toString().orEmpty()
                        val result = decoded?.get("resultPreview")?.toString().orEmpty()
                        AppText("$tool · $status${if (context.isBlank()) "" else " · $context"}\n${listOf(args, result).filter(String::isNotBlank).joinToString(" → ")}", color = tc.ts, fontSize = 9.sp, fontFamily = MONO, maxLines = 4)
                    }
                }
                if (savedActivity.isEmpty()) TestsHint("Full activity history is unavailable or exceeds its safe read limit.")
            }
            if (lane.toolActivityPath == null) lane.toolCalls.takeLast(12).forEach { call ->
                AppText(
                    "${call.toolName} · ${call.status.name.lowercase()} · ${call.caseId}/${call.stepId} · iteration ${call.iteration}, attempt ${call.attempt}\n" +
                        listOf(call.argumentsPreview, call.resultPreview).filter(String::isNotBlank).joinToString(" → "),
                    color = tc.ts, fontSize = 9.sp, fontFamily = MONO, maxLines = 3,
                )
            }
        }
    }
}

@Composable
private fun consensusColor(tone: ConsensusTone): Color = when (tone) {
    ConsensusTone.PASS -> tc().ok
    ConsensusTone.FAIL, ConsensusTone.DISAGREE -> DANGER_RED
    ConsensusTone.UNSURE -> tc().warn
    ConsensusTone.NONE -> tc().td
}

// ── Matrix ───────────────────────────────────────────────────────────

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun ReportMatrix(run: TestRun, selection: StepSelection?, filters: Set<TestRunReportFilter>, onSelect: (StepSelection?) -> Unit) {
    val tc = tc()
    val rows = remember(run, filters) { filterReportRows(run, filters) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText("CASE / STEP", color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        run.lanes.forEachIndexed { index, lane ->
            AppText(
                "LANE ${index + 1} · ${lane.config.deviceSerial}",
                color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(STATUS_COLUMN),
            )
        }
        AppText(
            "JUDGE / CONSENSUS",
            color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(CONSENSUS_COLUMN),
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
    if (rows.isEmpty()) TestsHint("Nothing to show yet.")
    rows.forEach { row ->
        when (row) {
            is MatrixRow.Case -> CaseMatrixRow(row)
            is MatrixRow.Step -> StepMatrixRow(row, consensusFor(run, row), selection, onSelect)
        }
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
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
        Spacer(Modifier.width(CONSENSUS_COLUMN))
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun StepMatrixRow(row: MatrixRow.Step, consensus: ConsensusCell, selection: StepSelection?, onSelect: (StepSelection?) -> Unit) {
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
        Box(Modifier.width(CONSENSUS_COLUMN)) {
            if (consensus.tone == ConsensusTone.NONE) {
                AppText(consensus.label, color = tc.td, fontSize = 11.sp)
            } else {
                StatusChip(consensus.label, consensusColor(consensus.tone))
            }
        }
    }
}
