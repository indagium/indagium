@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.model.AiUsageStats
import com.indagium.model.sumAiUsage
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.RunSummary
import com.indagium.testing.model.SUITE_SETUP_CASE_ID
import com.indagium.testing.model.SUITE_TEARDOWN_CASE_ID
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.agentJudgeDisagrees
import com.indagium.testing.model.aiUsage
import com.indagium.testing.model.driverLabel
import com.indagium.testing.model.summary
import com.indagium.testing.run.TestRunReportExportProgress
import com.indagium.testing.run.TestRunReportFormat
import com.indagium.testing.run.availableRunArtifactPaths
import com.indagium.testing.run.compareTestRuns
import com.indagium.testing.run.failedCaseIds
import com.indagium.testing.run.interruptedCaseIds
import com.indagium.testing.run.previousTerminalRunSummaryOfSameSuite
import com.indagium.testing.run.rerunFailedCasesConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

// The Runs screen of the Tests workspace: the list of runs (this launch and stored ones), and one run's report — a
// cases x steps x lanes matrix (one column per lane, a single one for now), the step detail (what the agent claimed and
// saw, the check results, screenshot, log excerpt, transcript) and, while a run is live, its progress with minimal
// Allow/Deny and pause controls. Text that came from an agent, the device or a script is labelled untrusted: it is shown,
// never acted on.

private val RUN_STATUS_COLUMN = 72.dp
private val RUN_BAR_WIDTH = 72.dp
private val RUN_STEPS_LABEL_WIDTH = 56.dp
private val RUN_META_COLUMN = 150.dp
private val RUN_CLOCK_COLUMN = 40.dp
private val RUN_MENU_SLOT = 26.dp
private val RUN_SUITE_MENU_WIDTH = 240.dp
private val LANE_COLUMN = 92.dp
private val JUDGE_COLUMN = 104.dp
private val ATTEMPT_MENU_WIDTH = 190.dp
private val ROW_HEIGHT = 30.dp
private val STEP_ROW_START_PADDING = 20.dp
private val REPORT_TABS_MAX_WIDTH = 420.dp
private val PICKER_WIDTH = 560.dp
private val COMPARE_RESULT_COLUMN = 86.dp
private const val USAGE_LABEL_WEIGHT = .38f
private const val VERDICT_ALPHA = .10f
private const val ROW_SEPARATOR_ALPHA = .6f
private const val LIVE_POLL_MS = 500L
private const val CHIP_ALPHA = .16f
private const val BAR_TRACK_ALPHA = .85f

@Composable
internal fun TestsRunsScreen() {
    val ui = LocalTestsUi.current
    val runId = ui.view.selectedRunId
    if (runId == null) TestRunList() else TestRunReportScreen(runId)
}

// ── List ─────────────────────────────────────────────────────────────

/** The Runs screen: "Runs" with a suite filter, the status pills with counts, then the runs grouped by day. */
@Composable
private fun TestRunList() {
    val ui = LocalTestsUi.current
    val view = ui.view
    val runs = ui.state.testRuns
    val revision = runs.map { "${it.id}:${it.status}" }
    // Null until the first read finished: "No runs yet" must not flash while the stored runs are still loading.
    val loaded by produceState<List<RunSummary>?>(null, revision, ui.state.testRunsFolderEpoch) { value = ui.state.testRunCoordinator.listRuns() }
    val suites = ui.library.suites
    val suiteId = view.runsSuiteId?.takeIf { id -> suites.any { it.id == id } }
    val summaries = loaded
    val inSuite = remember(summaries, suiteId) { summaries.orEmpty().let { all -> if (suiteId == null) all else all.filter { it.suiteId == suiteId } } }
    val counts = remember(inSuite) { runFilterCounts(inSuite) }
    val shown = remember(inSuite, view.runsFilter) { filterRuns(inSuite, null, view.runsFilter) }
    val sizes by rememberRunSizes()
    var deleteRequest by remember { mutableStateOf<RunDeleteRequest?>(null) }
    val activeRunIds = ui.state.testRuns.filter { !it.isFinished }.mapTo(HashSet()) { it.id }
    val deletable = remember(shown, activeRunIds) { deletableRuns(shown, activeRunIds) }
    TestsScreenScaffold {
        TestsPageHeader(
            title = "Runs",
            overflowDescription = "More runs actions",
            overflowItems = listOf(
                TestsMenuItem(
                    "Delete ${deletable.size} shown ${if (deletable.size == 1) "run" else "runs"}…",
                    onClick = { deleteRequest = RunDeleteRequest(deletable) },
                    danger = true,
                    enabled = deletable.isNotEmpty(),
                    disabledHint = "No finished runs match the current filter.",
                ),
            ),
            primary = {
                TestsDropdown(
                    selectedLabel = suites.firstOrNull { it.id == suiteId }?.name?.ifBlank { "Untitled suite" } ?: "All suites",
                    options = listOf<String?>(null) + suites.map { it.id },
                    optionLabel = { id -> id?.let { wanted -> suites.firstOrNull { it.id == wanted }?.name?.ifBlank { "Untitled suite" } } ?: "All suites" },
                    onSelect = { view.runsSuiteId = it },
                    isSelected = { it == suiteId },
                    menuWidth = RUN_SUITE_MENU_WIDTH,
                )
            },
        )
        TestsHint("Start a run with “Run suite” on a suite or “Run case” on a case. A run keeps its evidence in the save folder.")
        if (!summaries.isNullOrEmpty()) {
            TestsHint(runsUsageLine(summaries.size, sizes, summaries.map { it.id }), Modifier.semantics { contentDescription = "Runs disk usage" })
        }
        Spacer(Modifier.height(10.dp))
        RunFilterPills(counts, view.runsFilter) { view.runsFilter = it }
        Spacer(Modifier.height(4.dp))
        when {
            summaries == null -> TestsHint("Loading runs…", Modifier.padding(top = 8.dp))
            summaries.isEmpty() -> TestsHint("No runs yet. Start one with Run suite.", Modifier.padding(top = 8.dp))
            shown.isEmpty() -> TestsHint("No runs match this filter.", Modifier.padding(top = 8.dp))
            else -> RunHistoryList(
                shown, showSuite = true, onOpen = { view.selectedRunId = it },
                onDelete = { deleteRequest = RunDeleteRequest(listOf(it)) },
            )
        }
    }
    deleteRequest?.let { request -> RunDeleteDialog(request, onDismiss = { deleteRequest = null }) }
}

@Composable
private fun RunFilterPills(counts: Map<RunFilter, Int>, selected: RunFilter, onSelect: (RunFilter) -> Unit) {
    val tc = tc()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RunFilter.entries.forEach { filter ->
            TestsFilterPill(
                label = filter.label,
                count = counts[filter] ?: 0,
                selected = filter == selected,
                onClick = { onSelect(filter) },
                countColor = when (filter) {
                    RunFilter.ALL -> tc.tx
                    RunFilter.FAILED -> DANGER_RED
                    RunFilter.PASSED -> tc.success
                    RunFilter.CANCELLED -> tc.warn
                },
            )
        }
    }
}

/**
 * Runs grouped by day (Today, Yesterday, "Friday, Oct 9"), newest first, one 34 dp row each: status chip (fixed width, never cut) ·
 * the suite name — or "N cases" when [showSuite] is false because the surrounding screen is already one suite — · a pass bar with
 * "passed/total" steps · "N cases · N lanes" (plus the judge / flaky notes) · the clock time. [onOpen] gets the run id. Used by the
 * Runs screen and the suite's Runs tab.
 */
@Composable
internal fun RunHistoryList(
    runs: List<RunSummary>,
    showSuite: Boolean,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    /** Gives each row a hover ⋯ with "Delete run…" that hands the run here; null shows no menu. */
    onDelete: ((RunSummary) -> Unit)? = null,
) {
    val tc = tc()
    val groups = remember(runs, nowMs, zone) { groupRunsByDay(runs, nowMs, zone) }
    Column(modifier.fillMaxWidth()) {
        groups.forEach { group ->
            AppText(
                group.label, color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 4.dp),
            )
            group.runs.forEach { run -> RunHistoryRow(run, showSuite, zone, onDelete) { onOpen(run.id) } }
        }
    }
}

@Composable
private fun RunHistoryRow(run: RunSummary, showSuite: Boolean, zone: ZoneId, onDelete: ((RunSummary) -> Unit)?, onClick: () -> Unit) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val color = runColor(run.status)
    var hovered by remember { mutableStateOf(false) }
    TestsListRow(
        selected = false,
        onClick = onClick,
        modifier = Modifier.onPointerEvent(PointerEventType.Enter) { hovered = true }.onPointerEvent(PointerEventType.Exit) { hovered = false },
        startPadding = 6.dp,
        spacing = 10.dp,
    ) {
        Box(Modifier.width(RUN_STATUS_COLUMN)) { StatusChip(run.status.label(), color) }
        AppText(
            if (showSuite) run.suiteName.ifBlank { "Untitled suite" } else countNoun(run.caseCount, "case"),
            color = tc.tx, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        RunPassBar(run, color)
        // A fixed slot, so the bar and the clock sit at the same x on every row whatever the note says.
        Box(Modifier.width(RUN_META_COLUMN)) {
            AppText(runMetaText(run, showSuite), color = tc.td, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.width(RUN_CLOCK_COLUMN), contentAlignment = Alignment.CenterEnd) {
            AppText(formatClock(run.createdAt, zone), color = tc.td, fontSize = 10.sp, fontFamily = MONO)
        }
        if (onDelete != null) {
            val active = run.status.isActiveRun() || ui.state.testRuns.any { it.id == run.id && !it.isFinished }
            Box(Modifier.size(RUN_MENU_SLOT).alpha(if (hovered) 1f else 0f)) {
                TestsOverflowMenu(
                    listOf(
                        TestsMenuItem(
                            "Delete run…", onClick = { onDelete(run) }, danger = true,
                            enabled = !active, disabledHint = RUN_DELETE_ACTIVE_HINT,
                        ),
                    ),
                    contentDescription = "Run actions",
                )
            }
        }
    }
}

private const val RUN_DELETE_ACTIVE_HINT = "Stop the run before deleting it."

/**
 * A 6 dp rounded track filled with the share of passed steps, and "passed/total" after it. Both live in fixed-width slots (the bar
 * [RUN_BAR_WIDTH], the label [RUN_STEPS_LABEL_WIDTH] right-aligned in a monospace face) so every row's bar starts and ends at the same x.
 * A live run fills in the accent colour.
 */
@Composable
private fun RunPassBar(run: RunSummary, statusColor: Color) {
    val tc = tc()
    val live = run.status == RunStatus.QUEUED || run.status == RunStatus.RUNNING
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.testTag("run-pass-bar").width(RUN_BAR_WIDTH).height(6.dp).clip(CORNER_SM).background(tc.br.copy(alpha = BAR_TRACK_ALPHA))) {
            val fraction = run.passFraction()
            if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(if (live) statusColor else tc.success))
        }
        Box(Modifier.testTag("run-pass-steps").width(RUN_STEPS_LABEL_WIDTH), contentAlignment = Alignment.CenterEnd) {
            AppText("${run.passedSteps}/${run.totalSteps}", color = tc.ts, fontSize = 11.sp, fontFamily = MONO, maxLines = 1)
        }
    }
}

/** "5 cases · 1 lane" (the cases part is dropped when the screen already shows them), plus the judge and flaky notes the old list had. */
internal fun runMetaText(run: RunSummary, showSuite: Boolean): String = listOfNotNull(
    countNoun(run.caseCount, "case").takeIf { showSuite },
    countNoun(run.laneCount, "lane"),
    "${run.pendingVerificationSteps} awaiting judge".takeIf { run.pendingVerificationSteps > 0 },
    "flaky recovery".takeIf { run.flaky },
).joinToString(" · ")

// ── Colours and chips ────────────────────────────────────────────────

@Composable
internal fun runColor(status: RunStatus): Color = when (status) {
    RunStatus.PASSED -> tc().success
    RunStatus.FAILED, RunStatus.ERROR -> DANGER_RED
    RunStatus.RUNNING, RunStatus.QUEUED -> tc().ac
    RunStatus.CANCELLED -> tc().warn
}

@Composable
internal fun stepColor(status: StepStatus): Color = when (status) {
    StepStatus.PASS -> tc().success
    StepStatus.FAIL, StepStatus.ERROR, StepStatus.TIMEOUT -> DANGER_RED
    StepStatus.BLOCKED -> tc().warn
    StepStatus.SKIPPED -> tc().td
}

/** A step result's colour: neutral for a step a cancel interrupted (it did not fail), else its status' colour. */
@Composable
internal fun stepResultColor(result: StepResult): Color = if (result.interrupted) tc().warn else stepColor(result.status)

@Composable
internal fun caseColor(status: CaseStatus?): Color = when (status) {
    CaseStatus.PASS -> tc().success
    CaseStatus.FAIL, CaseStatus.ERROR -> DANGER_RED
    CaseStatus.BLOCKED, CaseStatus.CANCELLED -> tc().warn
    CaseStatus.SKIPPED -> tc().td
    null -> tc().ac
}

@Composable
internal fun StatusChip(label: String, color: Color, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Box(modifier.background(color.copy(alpha = CHIP_ALPHA), CORNER_SM).padding(horizontal = 6.dp, vertical = 2.dp)) {
        AppText(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
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
    if (run == null) {
        TestsScreenScaffold {
            TestsBreadcrumbLink("‹ Runs", onClick = { ui.view.selectedRunId = null }, contentDescription = "Back to runs")
            Spacer(Modifier.height(6.dp))
            TestsHint("Loading the run…")
        }
        return
    }
    RunReport(run, coordinator.runDir(runId))
}

/**
 * One run's report (wireframe C): "‹ Runs", the suite name with Re-run failed and a ⋯ menu, the verdict box, then the tabs
 * Results · Compare · Activity · Usage. The selected step's evidence is the inspector. The selection, the filters and the
 * comparison choice are remembered per run; the previous run is only read while the Compare tab (or its picker) is open.
 */
@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun RunReport(run: TestRun, runDir: File) {
    val ui = LocalTestsUi.current
    val view = ui.view
    var selection by remember(run.id) { mutableStateOf(firstNonPassingSelection(run)) }
    // Which attempt of a retried case the matrix shows; the default is the latest one. The report's verdict and actions use `run`.
    var attemptViews by remember(run.id) { mutableStateOf(emptyMap<String, Int>()) }
    val shown = remember(run, attemptViews) { run.viewingAttempts(attemptViews) }
    var tick by remember(run.id) { mutableIntStateOf(0) }
    var filters by remember(run.id) { mutableStateOf(emptySet<TestRunReportFilter>()) }
    var showExport by remember(run.id) { mutableStateOf(false) }
    var showComparePicker by remember(run.id) { mutableStateOf(false) }
    var compareRunId by remember(run.id) { mutableStateOf<String?>(null) }
    DisposableEffect(run.id) { onDispose { view.reportTab = RunReportTab.Results } }
    val tab = view.reportTab
    val active = run.status == RunStatus.QUEUED || run.status == RunStatus.RUNNING
    val rows = remember(shown, filters) { filterReportRows(shown, filters) }
    LaunchedEffect(run.id, active) {
        while (active) {
            delay(LIVE_POLL_MS)
            tick++
        }
    }
    TestsKeyTargetEffect { event ->
        val delta = when (event.key) {
            Key.DirectionUp -> -1
            Key.DirectionDown -> 1
            else -> null
        }
        val plain = !event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed && !event.isShiftPressed
        val next = if (delta != null && plain && tab == RunReportTab.Results) reportSelectionMovedBy(rows, selection, delta) else null
        if (next != null) {
            selection = next
            ui.reclaimFocus()
        }
        next != null
    }
    // The previous run is read only while the Compare tab or the picker needs it, never to decorate the results.
    val needCompare = tab == RunReportTab.Compare || showComparePicker
    val summaries by produceState<List<RunSummary>?>(null, run.id, run.suite.id, needCompare) {
        if (needCompare) value = ui.state.testRunCoordinator.listRunSummariesForSuite(run.suite.id)
    }
    val previousRun = remember(summaries, run.id, run.suite.id, run.createdAt) { summaries?.let { previousTerminalRunSummaryOfSameSuite(it, run) } }
    val wantedCompareId = compareRunId ?: previousRun?.id
    val comparedRun by produceState<TestRun?>(null, wantedCompareId, needCompare) {
        if (needCompare) {
            value = null
            value = wantedCompareId?.let { ui.state.testRunCoordinator.loadRun(it) }
        }
    }
    val profiles = ui.state.settings.aiProviderProfiles
    val verdict = remember(run) { runVerdict(run) }
    val meta = remember(run, profiles) { runMetaLine(run, System.currentTimeMillis(), profileName = { id -> profiles.firstOrNull { it.id == id }?.displayName }) }
    val failedIds = remember(run) { failedCaseIds(run) }
    val interruptedIds = remember(run) { interruptedCaseIds(run) }
    val rerunLabel = rerunButtonLabel(failedIds.size, interruptedIds.size)
    var deleteRequest by remember(run.id) { mutableStateOf<RunDeleteRequest?>(null) }
    TestsScreenScaffold(inspector = { ReportInspector(shown, runDir, selection) }) {
        TestsPageHeader(
            title = run.suite.name.ifBlank { "Untitled suite" },
            breadcrumb = { TestsBreadcrumbLink("‹ Runs", onClick = { view.selectedRunId = null }, contentDescription = "Back to runs") },
            primary = if (rerunLabel != null && !active) {
                {
                    AppButton(rerunLabel, onClick = {
                        rerunFailedCasesConfig(run).onSuccess { config ->
                            view.runDialog = RunDialogTarget(run.suite.id, initialCaseIds = config.caseIds.orEmpty().toSet(), initialConfig = config)
                        }.onFailure { ui.info(it.message ?: "Failed cases cannot be re-run.") }
                    }, variant = ButtonVariant.Primary)
                }
            } else {
                null
            },
            overflowDescription = "More report actions",
            overflowItems = listOf(
                TestsMenuItem("Export report…", onClick = { showExport = true }, enabled = !active, disabledHint = RUN_ACTIVE_HINT),
                TestsMenuItem("Compare with…", onClick = { showComparePicker = true }, enabled = !active, disabledHint = RUN_ACTIVE_HINT),
                TestsMenuItem(
                    "Delete run…",
                    onClick = { deleteRequest = RunDeleteRequest(listOf(run.summary())) { view.selectedRunId = null } },
                    danger = true, enabled = !active, disabledHint = RUN_DELETE_ACTIVE_HINT, dividerBefore = true,
                ),
            ),
        )
        Spacer(Modifier.height(8.dp))
        RunVerdictBox(verdict, meta)
        run.warnings.forEach { TestsLockedNotice(it, Modifier.padding(top = 6.dp)) }
        run.error?.takeIf { it.isNotBlank() && !verdict.headline.contains(it.trim()) }?.let { TestsErrorText(it) }
        Spacer(Modifier.height(10.dp))
        ReportTabs(run)
        Spacer(Modifier.height(10.dp))
        when (tab) {
            RunReportTab.Results -> ResultsTab(
                shown, runDir, tick, active, rows, filters, selection,
                onFilters = { filters = it },
                onSelect = { selection = it },
                onAttempt = { key, attempt -> attemptViews = attemptViews + (key to attempt) },
            )
            RunReportTab.Compare -> CompareTab(run, active, summaries, comparedRun, wantedCompareId, onPick = { showComparePicker = true })
            RunReportTab.Activity -> ActivityTab(run, runDir)
            RunReportTab.Usage -> UsageTab(run, profiles.associate { it.id to it.displayName })
        }
    }
    if (showComparePicker) {
        RunComparisonPicker(
            summaries?.filter { it.id != run.id && it.suiteId == run.suite.id && it.status !in setOf(RunStatus.QUEUED, RunStatus.RUNNING) },
            wantedCompareId,
            onChoose = {
                compareRunId = it
                showComparePicker = false
                view.reportTab = RunReportTab.Compare
            },
            onDismiss = { showComparePicker = false },
        )
    }
    if (showExport) ReportExportDialog(run, runDir, onDismiss = { showExport = false; ui.reclaimFocus() })
    deleteRequest?.let { request -> RunDeleteDialog(request, onDismiss = { deleteRequest = null }) }
}

private const val RUN_ACTIVE_HINT = "Available once the run has finished."

/** The tinted box under the header: a status glyph, the verdict sentence and the meta line (when, cases, lanes, judge). */
@Composable
private fun RunVerdictBox(verdict: RunVerdict, meta: String) {
    val tc = tc()
    val color = runColor(verdict.status)
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = VERDICT_ALPHA), CORNER_MD).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AppText(verdictGlyph(verdict.status), color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AppText(verdict.headline, color = tc.tx, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (meta.isNotBlank()) AppText(meta, color = tc.ts, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ReportTabs(run: TestRun) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val tabs = RunReportTab.entries
    val activityCount = run.lanes.sumOf { it.toolCalls.size }
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().align(Alignment.BottomStart)) { Divider() }
        UnderlineTabs(
            labels = tabs.map { runReportTabLabel(it, activityCount) },
            selectedIndex = tabs.indexOf(ui.view.reportTab),
            onSelect = {
                ui.view.reportTab = tabs[it]
                ui.reclaimFocus()
            },
            modifier = Modifier.widthIn(max = REPORT_TABS_MAX_WIDTH),
        )
    }
}

/** The inspector of the report: the selected step's evidence and actions, or the prompt to select one. */
@Composable
private fun ReportInspector(run: TestRun, runDir: File, selection: StepSelection?) {
    if (selection == null) {
        TestsHint("Select a step to see its evidence.")
    } else {
        StepDetailFor(run, runDir, selection)
    }
}

// ── Results ──────────────────────────────────────────────────────────

@Composable
private fun ResultsTab(
    run: TestRun,
    runDir: File,
    tick: Int,
    active: Boolean,
    rows: List<MatrixRow>,
    filters: Set<TestRunReportFilter>,
    selection: StepSelection?,
    onFilters: (Set<TestRunReportFilter>) -> Unit,
    onSelect: (StepSelection) -> Unit,
    onAttempt: (key: String, attempt: Int) -> Unit,
) {
    if (active) {
        TestRunLiveView(run, runDir, tick)
    } else {
        run.lanes.forEachIndexed { index, lane ->
            lane.error?.let { TestsErrorText("Lane ${index + 1} · ${lane.config.deviceSerial}: $it") }
        }
    }
    ReportFilterPills(run, filters, onFilters)
    ReportMatrix(run, rows, filtered = filters.isNotEmpty(), selection, onSelect, onAttempt = if (active) null else onAttempt)
}

@Composable
private fun filterCountColor(filter: TestRunReportFilter): Color = when (filter) {
    TestRunReportFilter.FAILURES -> DANGER_RED
    TestRunReportFilter.BLOCKED_OR_ERROR, TestRunReportFilter.DISAGREEMENTS -> tc().warn
    TestRunReportFilter.UNRESOLVED_JUDGING -> tc().ac
}

/** One pill per filter with the number of step rows it matches; several can be on (their matches are combined). */
@Composable
private fun ReportFilterPills(run: TestRun, filters: Set<TestRunReportFilter>, onChange: (Set<TestRunReportFilter>) -> Unit) {
    val ui = LocalTestsUi.current
    val counts = remember(run) { reportFilterCounts(run) }
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        TestRunReportFilter.entries.forEach { filter ->
            TestsFilterPill(
                label = filter.label,
                count = counts[filter] ?: 0,
                selected = filter in filters,
                onClick = {
                    onChange(if (filter in filters) filters - filter else filters + filter)
                    ui.reclaimFocus()
                },
                countColor = filterCountColor(filter),
            )
        }
        if (filters.isNotEmpty()) {
            TestsFilterPill(label = "Clear", count = null, selected = false, onClick = { onChange(emptySet()); ui.reclaimFocus() })
        }
    }
}

// ── Matrix ───────────────────────────────────────────────────────────

@Composable
private fun consensusColor(tone: ConsensusTone): Color = when (tone) {
    ConsensusTone.PASS -> tc().success
    ConsensusTone.FAIL, ConsensusTone.DISAGREE -> DANGER_RED
    ConsensusTone.UNSURE -> tc().warn
    ConsensusTone.NONE -> tc().td
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun ReportMatrix(
    run: TestRun,
    rows: List<MatrixRow>,
    filtered: Boolean,
    selection: StepSelection?,
    onSelect: (StepSelection) -> Unit,
    onAttempt: ((key: String, attempt: Int) -> Unit)? = null,
) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val profiles = ui.state.settings.aiProviderProfiles
    val improvable = remember(run, ui.library) {
        val live = run.status == RunStatus.QUEUED || run.status == RunStatus.RUNNING
        if (live) emptySet() else improvableCaseIds(run).filter { ui.library.findCase(it) != null }.toSet()
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("Case / step", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        run.lanes.forEachIndexed { index, lane ->
            Column(Modifier.width(LANE_COLUMN)) {
                AppText("Lane ${index + 1}", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                AppText(lane.config.deviceSerial, color = tc.td, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (lane.config.kind != LaneKind.EXTERNAL) {
                    val name = profiles.firstOrNull { it.id == lane.config.profileId }?.displayName
                    AppText(lane.config.driverLabel(name), color = tc.td, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        AppText(
            if (run.lanes.size > 1) "Consensus" else "Judge",
            color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.width(JUDGE_COLUMN),
        )
    }
    Divider()
    if (rows.isEmpty()) {
        TestsHint(if (filtered) "No steps match these filters." else "Nothing to show yet.", Modifier.padding(horizontal = 8.dp, vertical = 10.dp))
    }
    rows.forEach { row ->
        when (row) {
            is MatrixRow.Case -> CaseMatrixRow(run, row, improvable = row.caseId in improvable, onAttempt = onAttempt)
            is MatrixRow.Step -> StepMatrixRow(row, consensusFor(run, row), run.lanes.size > 1, selection, onSelect)
        }
    }
}

/** A case header: its name, its result per lane, and a ⋯ (Improve these steps with AI…, Open case) for real library cases. */
@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun CaseMatrixRow(run: TestRun, row: MatrixRow.Case, improvable: Boolean, onAttempt: ((key: String, attempt: Int) -> Unit)? = null) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val location = ui.library.findCase(row.caseId)
    val isHook = row.caseId == SUITE_SETUP_CASE_ID || row.caseId == SUITE_TEARDOWN_CASE_ID
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = ROW_HEIGHT).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText(
                row.name + if (row.iteration > 1) " (run ${row.iteration})" else "",
                color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (row.attemptCount > 1 && onAttempt != null) {
                TestsDropdown(
                    selectedLabel = "Attempt ${row.viewingAttempt} of ${row.attemptCount}",
                    options = (1..row.attemptCount).toList(),
                    optionLabel = { attempt -> "Attempt $attempt" + (row.attemptStatuses[attempt]?.let { " · ${it.label()}" } ?: "") },
                    onSelect = { attempt -> onAttempt(caseAttemptKey(row.caseId, row.iteration), attempt) },
                    isSelected = { it == row.viewingAttempt },
                    menuWidth = ATTEMPT_MENU_WIDTH,
                )
            }
        }
        row.cells.forEach { cell ->
            Box(Modifier.width(LANE_COLUMN)) {
                if (cell.present) {
                    StatusChip(
                        cell.status.label() + (if (cell.flaky) " · flaky" else "") + if (cell.attempts > 1) " ×${cell.attempts}" else "",
                        caseColor(cell.status),
                        maxLines = 1,
                    )
                } else {
                    AppText("—", color = tc.td, fontSize = 11.sp)
                }
            }
        }
        Box(Modifier.width(JUDGE_COLUMN), contentAlignment = Alignment.CenterEnd) {
            if (!isHook) {
                val live = run.status == RunStatus.QUEUED || run.status == RunStatus.RUNNING
                TestsOverflowMenu(
                    contentDescription = "Actions for ${row.name}",
                    items = listOf(
                        TestsMenuItem(
                            "Improve these steps with AI…",
                            onClick = { ui.view.improveDialog = ImproveStepsTarget(run.suite.id, row.caseId, run.id) },
                            enabled = improvable && row.iteration == 1,
                            disabledHint = when {
                                live -> RUN_ACTIVE_HINT
                                row.iteration > 1 -> "Improve the first run of this case."
                                location == null -> "This case is no longer in the library."
                                else -> "No step of this case needs improving."
                            },
                        ),
                        TestsMenuItem(
                            "Open case",
                            onClick = {
                                location?.let {
                                    ui.view.nav = TestsNav.Suites
                                    ui.view.selectedSuiteId = it.suite.id
                                    ui.view.selectedCaseId = row.caseId
                                }
                            },
                            enabled = location != null,
                            disabledHint = "This case is no longer in the library.",
                        ),
                    ),
                )
            }
        }
    }
}

/** A step row: the action, one result chip per lane (click a chip to pick that lane) and the judge / lane consensus. */
@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun StepMatrixRow(row: MatrixRow.Step, consensus: ConsensusCell, multiLane: Boolean, selection: StepSelection?, onSelect: (StepSelection) -> Unit) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val selected = selection?.rowKey == row.key
    val firstWithResult = row.cells.firstOrNull { it.result != null }
    Column(Modifier.fillMaxWidth()) {
        TestsListRow(
            selected = selected,
            onClick = {
                val lane = row.cells.firstOrNull { selected && it.laneId == selection.laneId && it.result != null } ?: firstWithResult
                lane?.let { onSelect(StepSelection(row.key, it.laneId)) }
                ui.reclaimFocus()
            },
            minHeight = ROW_HEIGHT,
            startPadding = STEP_ROW_START_PADDING,
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                AppText(
                    (if (row.setup) "setup · " else "") + "${row.number}. ${row.action}",
                    color = tc.ts, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (row.cells.any { it.result?.agentJudgeDisagrees() == true }) {
                    HintWhen(true, "The agent and the judge disagree about this step.") {
                        AppText("≠", color = tc.warn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            row.cells.forEach { cell ->
                val chosen = multiLane && selected && selection.laneId == cell.laneId
                val result = cell.result
                Box(
                    Modifier.width(LANE_COLUMN)
                        .then(if (chosen) Modifier.border(1.dp, tc.ac, CORNER_SM) else Modifier)
                        .then(
                            if (result != null && multiLane) {
                                Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    onSelect(StepSelection(row.key, cell.laneId))
                                    ui.reclaimFocus()
                                }
                            } else {
                                Modifier
                            },
                        )
                        .padding(2.dp),
                ) {
                    if (result == null) {
                        AppText("·", color = tc.td, fontSize = 11.sp)
                    } else {
                        StatusChip(
                            result.matrixLabel() + (if (result.flaky) " · flaky" else "") + if (result.attempts > 1) " ×${result.attempts}" else "",
                            stepResultColor(result),
                            maxLines = 1,
                        )
                    }
                }
            }
            Box(Modifier.width(JUDGE_COLUMN)) {
                if (consensus.tone == ConsensusTone.NONE) {
                    AppText(consensus.label, color = tc.td, fontSize = 11.sp)
                } else {
                    StatusChip(consensus.label, consensusColor(consensus.tone), maxLines = 1)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br.copy(alpha = ROW_SEPARATOR_ALPHA)))
    }
}

// ── Compare ──────────────────────────────────────────────────────────

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun CompareTab(
    run: TestRun,
    active: Boolean,
    summaries: List<RunSummary>?,
    compared: TestRun?,
    wantedId: String?,
    onPick: () -> Unit,
) {
    val tc = tc()
    if (active) {
        TestsHint("Comparing is available once the run has finished.")
        return
    }
    val ready = compared?.takeIf { it.id == wantedId }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val note = when {
            summaries == null -> "Loading runs…"
            wantedId == null -> "There is no earlier finished run of this suite to compare with."
            ready == null -> "Loading the run to compare with…"
            else -> "Compared with the run from ${formatRunMoment(ready.finishedAt ?: ready.createdAt, System.currentTimeMillis())}."
        }
        TestsHint(note, Modifier.weight(1f))
        AppButton("Compare with…", onClick = onPick, enabled = summaries != null)
    }
    if (ready == null) return
    val lines = remember(ready, run) { compareLines(compareTestRuns(ready, run)) }
    if (lines.isEmpty()) {
        TestsHint("No differences: the definitions and results match.", Modifier.padding(top = 8.dp))
        return
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppText("Change", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        AppText("Before", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(COMPARE_RESULT_COLUMN))
        AppText("Now", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(COMPARE_RESULT_COLUMN))
    }
    Divider()
    lines.forEach { line ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                AppText(line.change, color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                if (line.subject.isNotBlank()) AppText(line.subject, color = tc.tx, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                line.detail?.let { AppText(it, color = tc.td, fontSize = 10.sp, maxLines = COMPARE_DETAIL_LINES, overflow = TextOverflow.Ellipsis) }
            }
            AppText(line.before, color = tc.td, fontSize = 11.sp, maxLines = 2, modifier = Modifier.width(COMPARE_RESULT_COLUMN))
            AppText(line.now, color = tc.tx, fontSize = 11.sp, maxLines = 2, modifier = Modifier.width(COMPARE_RESULT_COLUMN))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br.copy(alpha = ROW_SEPARATOR_ALPHA)))
    }
}

private const val COMPARE_DETAIL_LINES = 6

/** Picks the run to compare with. Choosing closes it; a closed dialog hands keyboard focus back to the workspace. */
@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun RunComparisonPicker(
    runs: List<RunSummary>?,
    selectedId: String?,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val now = System.currentTimeMillis()

    fun dismiss() {
        onDismiss()
        ui.reclaimFocus()
    }
    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TestsDialogFrame(
            title = "Compare runs",
            width = PICKER_WIDTH,
            body = {
                when {
                    runs == null -> TestsHint("Loading runs…")
                    runs.isEmpty() -> TestsHint("No other completed runs of this suite are available.")
                    else -> runs.forEach { candidate ->
                        TestsListRow(selected = candidate.id == selectedId, onClick = { onChoose(candidate.id); ui.reclaimFocus() }) {
                            Box(Modifier.width(RUN_STATUS_WIDTH)) { StatusChip(candidate.status.label(), runColor(candidate.status)) }
                            AppText(formatRunMoment(candidate.createdAt, now), color = tc.tx, fontSize = 11.sp, modifier = Modifier.widthIn(min = RUN_MOMENT_WIDTH))
                            AppText(candidate.line(), color = tc.td, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (candidate.id == selectedId) TestsBadge("selected")
                        }
                    }
                }
            },
            footer = { DialogActionButton("Close", active = false) { dismiss() } },
        )
    }
}

private val RUN_STATUS_WIDTH = 76.dp
private val RUN_MOMENT_WIDTH = 110.dp

// ── Activity ─────────────────────────────────────────────────────────

/** Every lane's progress line and its tool activity, expanded: what the agent and the judge did, newest last. */
@Composable
private fun ActivityTab(run: TestRun, runDir: File) {
    if (run.lanes.isEmpty()) TestsHint("This run has no lanes.")
    run.lanes.forEachIndexed { index, lane -> LaneActivity(run, index, lane, runDir) }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun LaneActivity(run: TestRun, index: Int, lane: LaneResult, runDir: File) {
    val tc = tc()
    var visibleActivityCount by remember(run.id, lane.laneId) { mutableIntStateOf(ACTIVITY_PAGE_SIZE) }
    val activityKey = "${run.id}:${lane.laneId}:${lane.toolActivityPath}:${lane.toolCalls.size}"
    val savedActivity by produceState(emptyList<String>(), activityKey) {
        value = withContext(Dispatchers.IO) { readActivityLines(runDir, lane.toolActivityPath) }
    }
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TestsBadge(if (lane.config.kind == LaneKind.EXTERNAL) "External" else "Agent")
        AppText("Lane ${index + 1} · ${run.progressLine(lane.laneId)}", color = tc.ts, fontSize = 11.sp, maxLines = 2)
    }
    lane.error?.let { TestsErrorText(it) }
    if (lane.toolCalls.isEmpty() && lane.toolActivityPath == null) {
        TestsHint("No tool activity was recorded for this lane.", Modifier.padding(vertical = 6.dp))
        return
    }
    TestsHint("${lane.toolCalls.size} recent tool activities", Modifier.padding(vertical = 4.dp))
    lane.toolActivityPath?.let {
        val from = (savedActivity.size - visibleActivityCount).coerceAtLeast(0)
        if (from > 0) {
            AppButton("Load earlier activity", onClick = { visibleActivityCount += ACTIVITY_PAGE_SIZE }, variant = ButtonVariant.Ghost)
        }
        savedActivity.drop(from).forEach { line ->
            val decoded = runCatching { com.indagium.debug.Json.decode(line) as? Map<*, *> }.getOrNull()
            if (decoded?.get("phase") == "truncated") {
                TestsHint(decoded["reason"]?.toString() ?: "Older activity was truncated by the evidence limit.")
            } else {
                val context = activityContext(
                    run,
                    decoded?.get("caseId")?.toString().orEmpty(),
                    decoded?.get("stepId")?.toString().orEmpty(),
                    (decoded?.get("iteration") as? Number)?.toInt() ?: 1,
                    (decoded?.get("attempt") as? Number)?.toInt() ?: 1,
                )
                ActivityRow(
                    time = (decoded?.get("startedAt") as? Number)?.toLong()?.let { formatClock(it) },
                    tool = decoded?.get("toolName")?.toString() ?: "Tool activity",
                    status = decoded?.get("status")?.toString()?.lowercase() ?: "unknown",
                    context = context,
                    detail = listOf(decoded?.get("argumentsPreview")?.toString().orEmpty(), decoded?.get("resultPreview")?.toString().orEmpty())
                        .filter(String::isNotBlank).joinToString(" → "),
                )
            }
        }
        if (savedActivity.isEmpty()) TestsHint("Full activity history is unavailable or exceeds its safe read limit.")
    }
    if (lane.toolActivityPath == null) {
        lane.toolCalls.takeLast(RECENT_ACTIVITY_LINES).forEach { call ->
            ActivityRow(
                time = formatClock(call.startedAt),
                tool = call.toolName,
                status = call.status.name.lowercase(),
                context = activityContext(run, call.caseId, call.stepId, call.iteration, call.attempt),
                detail = listOf(call.argumentsPreview, call.resultPreview).filter(String::isNotBlank).joinToString(" → "),
            )
        }
    }
}

private const val RECENT_ACTIVITY_LINES = 12

/** One tool call: the clock time, the tool (accent, mono), its status, where it happened and its arguments → result. */
@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun ActivityRow(time: String?, tool: String, status: String, context: String, detail: String) {
    val tc = tc()
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            time?.let { AppText(it, color = tc.td, fontSize = 10.sp, fontFamily = MONO) }
            AppText(tool, color = tc.ac, fontSize = 11.sp, fontFamily = MONO, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            AppText(status, color = tc.td, fontSize = 10.sp)
        }
        if (context.isNotBlank()) AppText(context, color = tc.ts, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail.isNotBlank()) AppText(detail, color = tc.ts, fontSize = 10.sp, fontFamily = MONO, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br.copy(alpha = ROW_SEPARATOR_ALPHA)))
}

// ── Usage ────────────────────────────────────────────────────────────

/** AI usage: the run total, then each lane's total and what its cases used (agent, judge, external), in short number forms. */
@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun UsageTab(run: TestRun, profileNames: Map<String, String>) {
    val comparisonUsage = remember(run) { sumAiUsage(run.comparisons.map { it.usage }) }
    UsageRow("Run total", formatUsage(run.aiUsage()), emphasis = true)
    val start = run.startedAt ?: run.createdAt.takeIf { it > 0L }
    val finish = run.finishedAt
    if (start != null && finish != null) UsageRow("Duration", formatDuration(finish - start))
    comparisonUsage?.let { UsageRow("Lane comparison judge", formatUsage(it)) }
    run.lanes.forEachIndexed { index, lane ->
        val driver = if (lane.config.kind == LaneKind.EXTERNAL) EXTERNAL_LANE_LABEL else lane.config.driverLabel(profileNames[lane.config.profileId])
        TestsSectionTitle("Lane ${index + 1} · " + listOf(lane.config.deviceSerial, driver).filter(String::isNotBlank).joinToString(" · "))
        UsageRow("Lane total", formatUsage(lane.aiUsage()), emphasis = true)
        lane.cases.forEach { case ->
            val parts = listOfNotNull(
                case.agentUsage?.let { "agent: ${formatUsage(it)}" },
                case.judgeUsage?.let { "judge: ${formatUsage(it)}" },
                case.externalToolCalls?.let { calls -> "external: ${formatUsage(AiUsageStats(toolCalls = calls, partial = calls > 0L))}" },
            )
            if (parts.isNotEmpty()) UsageRow(case.caseName.ifBlank { "Untitled case" } + if (case.iteration > 1) " (run ${case.iteration})" else "", parts.joinToString("\n"))
        }
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun UsageRow(label: String, value: String, emphasis: Boolean = false) {
    val tc = tc()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        AppText(label, color = tc.td, fontSize = 12.sp, modifier = Modifier.weight(USAGE_LABEL_WEIGHT))
        AppText(value, color = tc.tx, fontSize = 12.sp, fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f - USAGE_LABEL_WEIGHT))
    }
}

/**
 * Exports a run's report: the format as a segmented control, the destination as a path field with a native "Choose…" save prompt
 * ([chooseFile], prefilled with a file name made of the suite, the run's time and the format's extension) and, for the evidence ZIP, the
 * saved evidence to include. Closing is refused while an export runs; its button then cancels it.
 */
@Suppress("ktlint:standard:max-line-length", "MaxLineLength", "LongMethod")
@Composable
internal fun ReportExportDialog(
    run: TestRun,
    runDir: File,
    onDismiss: () -> Unit,
    chooseFile: (title: String, suggestedName: String, initialDirectory: File?) -> File? = ::pickSaveFile,
) {
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
    val suggestedName = reportExportFileName(run.suite.name, run.createdAt, format)

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

    fun choose() {
        val folder = destination.takeIf(String::isNotBlank)?.let { File(it).absoluteFile.parentFile }?.takeIf(File::isDirectory)
        chooseFile("Export run report", suggestedName, folder ?: ui.state.initialSaveDialogDir())?.let { destination = it.absolutePath }
        ui.reclaimFocus()
    }
    Dialog(onDismissRequest = { if (running) job?.cancel() else onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TestsDialogFrame(
            title = "Export run report",
            width = EXPORT_DIALOG_WIDTH,
            maxHeight = EXPORT_DIALOG_MAX_HEIGHT,
            status = {
                progress?.let { TestsHint("${it.percent}% · ${it.current}", Modifier.padding(top = 8.dp)) }
                message?.let { TestsHint(it, Modifier.padding(top = 4.dp)) }
            },
            body = {
                TestsHint("Choose a local JSON, Markdown, or evidence ZIP destination. Existing files are never replaced by this dialog.")
                Spacer(Modifier.height(12.dp))
                TestsFieldLabel("Format")
                SegmentedControl(
                    options = TestRunReportFormat.entries.map { it.label() },
                    selectedIndices = setOf(TestRunReportFormat.entries.indexOf(format)),
                    onToggle = { index ->
                        format = TestRunReportFormat.entries[index]
                        destination = withReportExtension(destination, format)
                    },
                    enabled = !running,
                )
                Spacer(Modifier.height(10.dp))
                TestsFieldLabel("Destination path")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CompositionLocalProvider(LocalUiFontFamily provides MONO) {
                        InlineField(
                            value = destination,
                            onValue = { destination = it },
                            placeholder = "/path/to/$suggestedName",
                            modifier = Modifier.weight(1f),
                            fontSize = 11.sp,
                        )
                    }
                    AppButton("Choose…", onClick = ::choose, enabled = !running)
                }
                if (format == TestRunReportFormat.EVIDENCE_ZIP) {
                    TestsSectionTitle("Saved evidence")
                    BoundedScrollBoxDp(EVIDENCE_LIST_MAX_HEIGHT) {
                        evidencePaths.forEach { path ->
                            CheckRow(path in selectedEvidence, { selectedEvidence = if (path in selectedEvidence) selectedEvidence - path else selectedEvidence + path }) {
                                AppText(path, color = tc.ts, fontSize = 10.sp, fontFamily = MONO, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                        }
                        if (evidencePaths.isEmpty()) TestsHint("No saved screenshots, logs, or tool activity paths are available.")
                    }
                }
            },
            footer = {
                DialogActionButton(if (running) "Cancel export" else "Export", active = true, enabled = running || destination.isNotBlank()) {
                    if (running) job?.cancel() else export()
                }
                DialogActionButton("Close", active = false, enabled = !running) { onDismiss() }
            },
        )
    }
}

private val EXPORT_DIALOG_WIDTH = 620.dp
private val EXPORT_DIALOG_MAX_HEIGHT = 700.dp
private const val EVIDENCE_LIST_MAX_HEIGHT = 240

private const val MAX_REPORT_ACTIVITY_BYTES = 20 * 1024 * 1024
private const val ACTIVITY_PAGE_SIZE = 60

private fun readActivityLines(runDir: File, relativePath: String?): List<String> {
    if (relativePath == null) return emptyList()
    val bytes = com.indagium.testing.run.readBoundedRunArtifact(runDir, relativePath, MAX_REPORT_ACTIVITY_BYTES) ?: return emptyList()
    return bytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList()
}
