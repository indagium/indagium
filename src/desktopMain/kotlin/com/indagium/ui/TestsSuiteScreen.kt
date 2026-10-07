@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.RunSummary
import com.indagium.testing.model.SuiteCaseHistory
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.deriveTestSuiteHistory
import com.indagium.testing.model.metrics
import com.indagium.testing.model.summary
import com.indagium.testing.run.mergeRunSnapshots
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.StoredSuiteRunHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// A compact suite workspace: case execution is the default tab, while less-used suite metadata stays behind focused tabs.
// Persisted and live run snapshots are merged with live state winning so a late disk read cannot roll the view backwards.

private val CASE_ROW_HEIGHT = 56.dp
private val INDEX_COLUMN = 24.dp
private val STEPS_COLUMN = 48.dp
private val RESULT_COLUMN = 270.dp
private val SUITE_RAIL_WIDTH = 220.dp
private val SUITE_TAB_SHAPE = CORNER_SM

@Composable
internal fun TestsSuiteScreen(suiteId: String) {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val library = ui.library
    val suite = library.suite(suiteId) ?: return
    val access = suiteEditAccess(library, suite, limits)
    val (history, issues) = rememberSuiteHistory(suite)
    var confirmDelete by remember { mutableStateOf(false) }
    TestsScreenScaffold {
        SuiteHeader(suite, access, onDelete = { confirmDelete = true })
        Spacer(Modifier.height(8.dp))
        SuiteTabs(ui.view.selectedSuiteTab) { ui.view.selectedSuiteTab = it }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 720.dp) {
                val compact = maxWidth < 560.dp
                Column(Modifier.fillMaxWidth()) {
                    SuiteTabContent(suite, access.editable, history, compact)
                    SuiteHistoryRail(suite, history, issues, Modifier.fillMaxWidth())
                }
            } else {
                val tabContentWidth = maxWidth - SUITE_RAIL_WIDTH - 14.dp
                val compact = tabContentWidth < 700.dp
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f)) { SuiteTabContent(suite, access.editable, history, compact) }
                    SuiteHistoryRail(suite, history, issues, Modifier.width(SUITE_RAIL_WIDTH))
                }
            }
        }
    }
    if (confirmDelete) {
        TestsConfirmDialog(
            title = "Delete suite?",
            message = "\"${suite.name}\" and its ${suite.cases.size} case(s) will be deleted. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = { deleteSuiteAndSelectNext(ui, suite) },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun rememberSuiteHistory(suite: TestSuite): Pair<com.indagium.testing.model.TestSuiteHistory, List<IssueRecord>> {
    val ui = LocalTestsUi.current
    val liveRuns = ui.state.testRuns.filter { it.config.suiteId == suite.id }
    val suiteRunHistory by produceState(
        initialValue = StoredSuiteRunHistory(liveRuns, liveRuns.map { it.summary() }),
        key1 = suite.id,
        key2 = ui.state.testRuns,
    ) {
        value = ui.state.testRunCoordinator.loadSuiteRunHistory(suite.id)
    }
    val runs = remember(liveRuns, suiteRunHistory) { mergeRunSnapshots(liveRuns, suiteRunHistory.recentRecords) }
    val history = remember(suite, runs, suiteRunHistory.summaries) {
        deriveTestSuiteHistory(suite, runs, allRunSummaries = suiteRunHistory.summaries)
    }
    val issueRevision by ui.state.issueStore.revision.collectAsState()
    val issues by produceState<List<IssueRecord>>(emptyList(), issueRevision) {
        value = withContext(Dispatchers.IO) { ui.state.issueStore.list() }
    }
    return history to issues
}

private fun deleteSuiteAndSelectNext(ui: TestsUi, suite: TestSuite) {
    val next = ui.library.suites.firstOrNull { it.id != suite.id }?.id
    if (ui.report(ui.state.deleteTestSuite(suite.id)) is StoreResult.Ok) {
        ui.view.selectedSuiteId = next
        ui.view.selectedCaseId = null
    }
}

@Composable
private fun SuiteHeader(suite: TestSuite, access: TestsEditAccess, onDelete: () -> Unit) {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 620.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuiteNameField(suite, access.editable, Modifier.weight(1f))
                SuiteHeaderActions(suite, limits, onDelete)
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SuiteNameField(suite, access.editable, Modifier.fillMaxWidth())
                SuiteHeaderActions(suite, limits, onDelete)
            }
        }
    }
    if (access.reason != null) {
        Spacer(Modifier.height(6.dp))
        TestsLockedNotice(access.reason)
    }
}

@Composable
private fun SuiteNameField(suite: TestSuite, editable: Boolean, modifier: Modifier) {
    val ui = LocalTestsUi.current
    Column(modifier) {
        AppText("Suite name", color = tc().td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        CommitTextField(
            value = suite.name,
            onCommit = { text -> ui.state.updateTestSuite(suite.id) { it.copy(name = text.trim()) } },
            enabled = editable,
            placeholder = "Suite name",
        )
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun SuiteHeaderActions(suite: TestSuite, limits: TestsLimitsUiState, onDelete: () -> Unit) {
    val ui = LocalTestsUi.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HintedButton("Duplicate", onClick = { duplicateSuiteAndSelect(ui, suite) }, enabled = !ui.library.readOnly && limits.canDuplicateSuite(suite.id), disabledHint = limits.hint)
        HintedButton("Export…", onClick = { exportSuiteToChosenFile(ui, suite) })
        HintedButton("Run suite…", onClick = { ui.view.runDialog = RunDialogTarget(suite.id) }, enabled = !limits.isSuiteLocked(suite.id) && suite.cases.any { !limits.isCaseLocked(it.id) }, disabledHint = limits.hint, variant = ButtonVariant.Primary)
        HintedButton("Delete", onClick = onDelete, enabled = !ui.library.readOnly, disabledHint = LIBRARY_READ_ONLY_MESSAGE, isDanger = true)
    }
}

/*
 * The narrower layout wraps tab chips rather than compressing five labels into unreadable slivers.
 */
@Composable
private fun SuiteTabs(selected: SuiteTab, onSelect: (SuiteTab) -> Unit) {
    val tc = tc()
    BoxWithConstraints(Modifier.fillMaxWidth().background(tc.p2, SUITE_TAB_SHAPE).padding(3.dp)) {
        if (maxWidth >= 560.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                SuiteTab.entries.forEach { tab -> SuiteTabChip(tab, selected, onSelect, Modifier.weight(1f)) }
            }
        } else {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                SuiteTab.entries.forEach { tab -> SuiteTabChip(tab, selected, onSelect, Modifier.widthIn(min = 72.dp)) }
            }
        }
    }
}

@Composable
private fun SuiteTabChip(tab: SuiteTab, selected: SuiteTab, onSelect: (SuiteTab) -> Unit, modifier: Modifier) {
    val tc = tc()
    Box(
        modifier.clickable { onSelect(tab) }
            .background(if (tab == selected) tc.ac.copy(alpha = .18f) else Color.Transparent, SUITE_TAB_SHAPE)
            .padding(vertical = 7.dp, horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) { AppText(tab.label, color = if (tab == selected) tc.ac else tc.ts, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun SuiteTabContent(suite: TestSuite, editable: Boolean, history: com.indagium.testing.model.TestSuiteHistory, compact: Boolean) {
    when (LocalTestsUi.current.view.selectedSuiteTab) {
        SuiteTab.Cases -> SuiteCasesTab(suite, history, compact)
        SuiteTab.Runs -> SuiteRunsTab(history)
        SuiteTab.SetupTeardown -> SuiteHooksTab(suite, editable)
        SuiteTab.AgentInstructions -> SuiteInstructionsTab(suite, editable)
        SuiteTab.Variables -> SuiteVariablesTab(suite, editable)
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun SuiteCasesTab(suite: TestSuite, history: com.indagium.testing.model.TestSuiteHistory, compact: Boolean) {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    var caseToDelete by remember(suite.id) { mutableStateOf<com.indagium.testing.model.TestCase?>(null) }
    TestsSectionTitle("Cases (${suite.cases.size})") {
        HintedButton("New case", onClick = { createCaseAndOpen(ui, suite) }, enabled = !ui.library.readOnly && !suite.readOnly && limits.canCreateCase(suite.id), disabledHint = limits.hint)
    }
    if (suite.cases.isEmpty()) {
        TestsHint("No cases yet. Add a case to define a workflow for the agent.")
        return
    }
    if (!compact) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Spacer(Modifier.width(18.dp))
            TableHeader("#", Modifier.width(INDEX_COLUMN))
            TableHeader("CASE", Modifier.weight(1f))
            TableHeader("STEPS", Modifier.width(STEPS_COLUMN))
            TableHeader("LATEST RUN", Modifier.width(RESULT_COLUMN))
            Spacer(Modifier.width(CASE_ACTIONS_WIDTH))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(tc().br))
    ReorderableColumn(
        items = suite.cases,
        idOf = { it.id },
        onMove = { id, to -> ui.report(ui.state.moveTestCase(id, to)) },
        fixedRowHeight = caseRowHeight(history.latestTerminalRun?.lanes?.size ?: 1, compact),
        reorderEnabled = !ui.library.readOnly && !suite.readOnly,
    ) { case, row ->
        CaseRow(case, row, limits.isCaseLocked(case.id), history.cases[case.id], compact, onDelete = { caseToDelete = case })
    }
    caseToDelete?.let { case ->
        TestsConfirmDialog(
            title = "Delete case?",
            message = "\"${case.name}\" and its ${case.steps.size} step(s) will be deleted. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = { ui.report(ui.state.deleteTestCase(case.id)) },
            onDismiss = { caseToDelete = null },
        )
    }
}

private val CASE_ACTIONS_WIDTH = 18.dp * 3 + 8.dp

private fun caseRowHeight(laneCount: Int, compact: Boolean) = if (compact) {
    // The compact row has a case header, one line per lane, and vertical padding.
    maxOf(CASE_ROW_HEIGHT, (40 + 18 * laneCount.coerceAtLeast(1)).dp)
} else {
    maxOf(CASE_ROW_HEIGHT, (48 + 18 * laneCount.coerceAtLeast(1)).dp)
}

@Composable
private fun TableHeader(text: String, modifier: Modifier) {
    AppText(text, color = tc().td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(vertical = 4.dp))
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun CaseRow(case: com.indagium.testing.model.TestCase, row: ReorderRowScope, locked: Boolean, history: SuiteCaseHistory?, compact: Boolean, onDelete: () -> Unit) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val canDuplicate = !ui.library.readOnly && limits.canDuplicateCase(case.id)
    if (compact) {
        Column(Modifier.fillMaxSize().padding(horizontal = 4.dp).background(if (row.index % 2 == 1) tc.hv else Color.Transparent).padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ReorderGrip(row)
                AppText("${row.index + 1}", color = tc.td, fontSize = 10.sp, fontFamily = MONO, modifier = Modifier.width(INDEX_COLUMN))
                AppText(case.name.ifBlank { "Untitled case" }, color = tc.tx, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clickable { ui.view.selectedCaseId = case.id })
                if (locked) LockBadge(limits.hint)
                ReorderMoveButtons(row)
                HintWhen(!canDuplicate, limits.hint) { SquareIconButton("⧉", fontSize = 12.sp, enabled = canDuplicate, onClick = { ui.report(ui.state.duplicateTestCase(case.id)) }) }
                SquareIconButton("×", fontSize = 14.sp, enabled = !ui.library.readOnly, onClick = onDelete)
            }
            Row(Modifier.fillMaxWidth().padding(start = 42.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText("${case.steps.size} steps", color = tc.ts, fontSize = 9.sp)
                CaseLatestResults(history, Modifier.weight(1f))
            }
        }
    } else {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 4.dp).background(if (row.index % 2 == 1) tc.hv else Color.Transparent),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ReorderGrip(row)
            AppText("${row.index + 1}", color = tc.td, fontSize = 10.sp, fontFamily = MONO, modifier = Modifier.width(INDEX_COLUMN))
            Column(Modifier.weight(1f).fillMaxSize().clickable { ui.view.selectedCaseId = case.id }, verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppText(case.name.ifBlank { "Untitled case" }, color = tc.tx, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (locked) LockBadge(limits.hint)
                }
                AppText(case.description.ifBlank { case.steps.firstOrNull()?.action.orEmpty() }, color = tc.td, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AppText("${case.steps.size}", color = tc.ts, fontSize = 11.sp, modifier = Modifier.width(STEPS_COLUMN))
            CaseLatestResults(history, Modifier.width(RESULT_COLUMN))
            ReorderMoveButtons(row)
            HintWhen(!canDuplicate, limits.hint) { SquareIconButton("⧉", fontSize = 12.sp, enabled = canDuplicate, onClick = { ui.report(ui.state.duplicateTestCase(case.id)) }) }
            SquareIconButton("×", fontSize = 14.sp, enabled = !ui.library.readOnly, onClick = onDelete)
        }
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun CaseLatestResults(history: SuiteCaseHistory?, modifier: Modifier) {
    val tc = tc()
    val ui = LocalTestsUi.current
    if (history == null || history.lanes.none { it.status != null }) {
        AppText("Not run", color = tc.td, fontSize = 10.sp, modifier = modifier)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.Center) {
        history.lanes.forEach { lane ->
            val label = if (lane.lane.kind == LaneKind.EXTERNAL) {
                "External · ${lane.lane.deviceSerial}"
            } else {
                ui.state.settings.aiProviderProfiles.firstOrNull { it.id == lane.lane.profileId }?.displayName ?: lane.lane.profileId.orEmpty()
            }.take(24)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusChip(lane.status?.label() ?: "Not run", if (lane.status == null) tc.td else caseColor(lane.status), Modifier.width(56.dp))
                AppText(label, color = tc.ts, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (lane.status != null) {
                    AppText("${lane.durationMs / 1000.0}s", color = tc.td, fontSize = 9.sp, fontFamily = MONO)
                    if (lane.repeatCount > 1) AppText("×${lane.repeatCount}", color = tc.td, fontSize = 9.sp)
                }
                if (history.disagreement) AppText("≠", color = DANGER_RED, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (lane.issueIds.isNotEmpty()) {
                    AppText("Issue ×${lane.issueIds.size}", color = tc.warn, fontSize = 9.sp, modifier = Modifier.clickable {
                        ui.view.nav = TestsNav.Issues
                        ui.view.selectedIssueId = lane.issueIds.first()
                    })
                }
            }
        }
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun SuiteRunsTab(history: com.indagium.testing.model.TestSuiteHistory) {
    val ui = LocalTestsUi.current
    TestsSectionTitle("Suite run history · ${history.runs.size}")
    if (history.runs.isEmpty()) TestsHint("No runs have been saved for this suite yet.")
    history.runs.forEach { run -> SuiteRunRow(run) { openSuiteRun(ui, run.id) } }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun SuiteHooksTab(suite: TestSuite, editable: Boolean) {
    val ui = LocalTestsUi.current
    val update: ((TestSuite) -> TestSuite) -> StoreResult<*> = { transform -> ui.state.updateTestSuite(suite.id, transform) }
    TestsSectionTitle("Setup & teardown")
    TestsHint("Suite hooks run once around the selected cases. Case hooks are edited inside each case.")
    HooksEditor("Setup hooks", suite.setup, editable) { hooks -> update { it.copy(setup = hooks) } }
    HooksEditor("Teardown hooks", suite.teardown, editable) { hooks -> update { it.copy(teardown = hooks) } }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun SuiteInstructionsTab(suite: TestSuite, editable: Boolean) {
    val ui = LocalTestsUi.current
    val update: ((TestSuite) -> TestSuite) -> StoreResult<*> = { transform -> ui.state.updateTestSuite(suite.id, transform) }
    TestsSectionTitle("Agent instructions")
    TestsLabeled("Suite goal") { CommitTextField(suite.description, { t -> update { it.copy(description = t) } }, enabled = editable, multiline = true, placeholder = "What this suite covers") }
    Spacer(Modifier.height(8.dp))
    TestsLabeled("Instructions shown to every testing agent") { CommitTextField(suite.instructions, { t -> update { it.copy(instructions = t) } }, enabled = editable, multiline = true, placeholder = "Context every case shares") }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TestsLabeled("Target package", Modifier.weight(1f)) { CommitTextField(suite.targetPackage, { t -> update { it.copy(targetPackage = t.trim()) } }, enabled = editable, placeholder = "com.example.app", mono = true) }
        TestsLabeled("Device hint", Modifier.weight(1f)) { CommitTextField(suite.deviceProfileHint, { t -> update { it.copy(deviceProfileHint = t) } }, enabled = editable, placeholder = "Pixel, Android version…") }
    }
    Spacer(Modifier.height(8.dp))
    TestsLabeled("Tags") { TagsEditor(suite.tags, editable) { tags -> update { it.copy(tags = tags) } } }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun SuiteVariablesTab(suite: TestSuite, editable: Boolean) {
    val ui = LocalTestsUi.current
    TestsHint("Values in this list are available to suite instructions and script parameters.")
    VariablesEditor(suite.variables, editable) { variables -> ui.report(ui.state.updateTestSuite(suite.id) { it.copy(variables = variables) }) }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun SuiteRunRow(run: RunSummary, onClick: () -> Unit) {
    val tc = tc()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp, horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip(run.status.label(), runColor(run.status), Modifier.width(75.dp))
        Column(Modifier.weight(1f)) {
            AppText(formatRunTime(run.createdAt), color = tc.tx, fontSize = 10.sp, fontFamily = MONO)
            AppText("${run.laneCount} lane(s) · ${run.repeat} repeat(s)", color = tc.td, fontSize = 9.sp)
        }
        AppText("Open →", color = tc.ac, fontSize = 10.sp)
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun SuiteHistoryRail(suite: TestSuite, history: com.indagium.testing.model.TestSuiteHistory, issues: List<IssueRecord>, modifier: Modifier = Modifier) {
    val tc = tc()
    val ui = LocalTestsUi.current
    Column(modifier.padding(top = 14.dp)) {
        TestsSectionTitle("Latest run")
        val latest = history.latestTerminalRun
        if (latest == null) {
            TestsHint("No completed run yet.")
        } else {
            val metrics = latest.metrics()
            Column(Modifier.fillMaxWidth().background(tc.p2, CORNER_MD).clickable { openSuiteRun(ui, latest.id) }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                StatusChip(latest.status.label(), runColor(latest.status))
                AppText(formatRunTime(latest.finishedAt ?: latest.createdAt), color = tc.ts, fontSize = 10.sp, fontFamily = MONO)
                AppText("${latest.lanes.size} lane(s) · ${latest.config.repeat} repeat(s)", color = tc.td, fontSize = 9.sp)
                AppText("${metrics.passed} passed · ${metrics.failed} failed · ${metrics.blocked} blocked · ${metrics.errors} errors", color = tc.ts, fontSize = 9.sp)
                AppText("${metrics.disagreements} disagreement(s) · ${metrics.unresolvedJudging} unresolved judge check(s)", color = tc.ts, fontSize = 9.sp)
                latest.lanes.take(MAX_RAIL_LANES).forEach { lane ->
                    val cases = lane.cases.filter { it.caseId != com.indagium.testing.model.SUITE_SETUP_CASE_ID && it.caseId != com.indagium.testing.model.SUITE_TEARDOWN_CASE_ID }
                    val counts = cases.mapNotNull { it.status }.groupingBy { it }.eachCount()
                    val label = if (lane.config.kind == LaneKind.EXTERNAL) {
                        "External · ${lane.config.deviceSerial}"
                    } else {
                        ui.state.settings.aiProviderProfiles.firstOrNull { it.id == lane.config.profileId }?.displayName ?: "Agent profile"
                    }
                    val outcomes = listOfNotNull(
                        counts[com.indagium.testing.model.CaseStatus.PASS]?.let { "$it pass" },
                        counts[com.indagium.testing.model.CaseStatus.FAIL]?.let { "$it fail" },
                        counts[com.indagium.testing.model.CaseStatus.BLOCKED]?.let { "$it block" },
                        counts[com.indagium.testing.model.CaseStatus.ERROR]?.let { "$it error" },
                    ).ifEmpty { listOf("no case results") }.joinToString(" · ")
                    AppText(
                        "$label · $outcomes${if (latest.config.repeat > 1) " · ×${latest.config.repeat} repeats" else ""}",
                        color = tc.ts,
                        fontSize = 9.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (latest.lanes.size > MAX_RAIL_LANES) AppText("+${latest.lanes.size - MAX_RAIL_LANES} more lanes", color = tc.td, fontSize = 9.sp)
                AppText("Open report →", color = tc.ac, fontSize = 10.sp)
            }
        }
        TestsSectionTitle("Recent runs · ${history.recentRuns.size}")
        if (history.recentRuns.isEmpty()) TestsHint("Runs appear here after the first suite run.")
        history.recentRuns.take(5).forEach { run ->
            Row(Modifier.fillMaxWidth().clickable { openSuiteRun(ui, run.id) }.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(run.status.label(), runColor(run.status), Modifier.width(66.dp))
                AppText(formatRunTime(run.createdAt), color = tc.td, fontSize = 9.sp, fontFamily = MONO, modifier = Modifier.weight(1f))
            }
        }
        TestsSectionTitle("Linked issues")
        val linked = issues.filter { it.source.suiteId == suite.id }
        if (linked.isEmpty()) {
            TestsHint("No issues are linked to this suite yet.")
        } else {
            linked.forEach { issue ->
                Column(Modifier.fillMaxWidth().clickable {
                    ui.view.nav = TestsNav.Issues
                    ui.view.selectedIssueId = issue.id
                }.padding(vertical = 4.dp)) {
                    AppText(issue.draft.title, color = tc.tx, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    AppText("${issue.status.label()} · ${issue.source.caseName}", color = tc.td, fontSize = 9.sp)
                }
            }
        }
    }
}

private const val MAX_RAIL_LANES = 4

private fun openSuiteRun(ui: TestsUi, runId: String) {
    ui.view.nav = TestsNav.Runs
    ui.view.selectedRunId = runId
}

private fun createCaseAndOpen(ui: TestsUi, suite: TestSuite) {
    val name = uniqueName("New case", suite.cases.map { it.name })
    val result = ui.report(ui.state.createTestCase(suite.id, com.indagium.testing.model.TestCase("", name)))
    if (result is StoreResult.Ok) ui.view.selectedCaseId = result.value.id
}

private fun duplicateSuiteAndSelect(ui: TestsUi, suite: TestSuite) {
    val result = ui.report(ui.state.duplicateTestSuite(suite.id))
    if (result is StoreResult.Ok) {
        ui.view.selectedSuiteId = result.value.id
        ui.view.selectedCaseId = null
        ui.view.selectedSuiteTab = SuiteTab.Cases
    }
}

private fun exportSuiteToChosenFile(ui: TestsUi, suite: TestSuite) {
    val file = pickSaveFile("Export test suite", suiteExportFileName(suite.name), null) ?: return
    ui.reclaimFocus()
    val result = ui.report(ui.state.exportTestSuiteToFile(suite.id, file))
    if (result is StoreResult.Ok) {
        val assetNote = if (ui.state.testSuiteRequiresExternalAssets(suite.id)) {
            " External golden screenshot assets are required and were not included in the JSON file."
        } else {
            ""
        }
        ui.info("Exported \"${suite.name}\" to ${file.name}.$assetNote")
    }
}
