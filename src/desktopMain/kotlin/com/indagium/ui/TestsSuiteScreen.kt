@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.indagium.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.SuiteCaseHistory
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.TestSuiteHistory
import com.indagium.testing.model.deriveTestSuiteHistory
import com.indagium.testing.model.summary
import com.indagium.testing.run.mergeRunSnapshots
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.StoredSuiteRunHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The suite screen (wireframe B): the name with ▶ Run suite and a ⋯ menu, a one-line summary of the last run, and underline tabs
// Cases · Runs · Settings. Cases is a compact table (# · case and goal · steps · latest result · issues · ⋯); Settings holds the
// less-used suite metadata (hooks, agent instructions, variables). Persisted and live run snapshots are merged with live state
// winning so a late disk read cannot roll the view backwards; until that read completes the screen says "Loading runs…" rather
// than claiming nothing ran.

private val INDEX_COLUMN = 24.dp
private val STEPS_COLUMN = 44.dp
private val LATEST_COLUMN = 150.dp
private val ISSUES_COLUMN = 52.dp
private val GRIP_COLUMN = 18.dp
private val MENU_COLUMN = 26.dp
private val ROW_MIN_HEIGHT = 34.dp
private val ROW_MIN_HEIGHT_WITH_GOAL = 44.dp
private val ROW_START_PADDING = 6.dp
private val SUITE_TABS_MAX_WIDTH = 330.dp
private val DOT_SIZE = 8.dp
private const val ROW_MENU_IDLE_ALPHA = .35f
private const val ROW_SEPARATOR_ALPHA = .6f
private const val ISSUE_PILL_ALPHA = .18f

/** What the suite screen derived from the run history. [loaded] is false until the stored runs were read. */
private class SuiteScreenData(val history: TestSuiteHistory, val issues: List<IssueRecord>, val loaded: Boolean)

@Composable
internal fun TestsSuiteScreen(suiteId: String) {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val library = ui.library
    val suite = library.suite(suiteId) ?: return
    val access = suiteEditAccess(library, suite, limits)
    val data = rememberSuiteScreenData(suite)
    var confirmDelete by remember { mutableStateOf(false) }
    TestsScreenScaffold {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SuiteHeader(suite, access, onDelete = { confirmDelete = true })
            SuiteLastRunLine(suiteLastRun(data.loaded, data.history.latestTerminalRun))
            SuiteTabs(suite, if (data.loaded) data.history.runs.size else null)
            when (ui.view.selectedSuiteTab) {
                SuiteTab.Cases -> SuiteCasesTab(suite, data)
                SuiteTab.Runs -> SuiteRunsTab(data)
                SuiteTab.Settings -> SuiteSettingsTab(suite, access.editable)
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

/** The stored runs of [StoredSuiteRunHistory], tagged with the suite they were read for. */
private class LoadedSuiteRuns(val suiteId: String, val stored: StoredSuiteRunHistory)

@Composable
private fun rememberSuiteScreenData(suite: TestSuite): SuiteScreenData {
    val ui = LocalTestsUi.current
    val liveRuns = ui.state.testRuns.filter { it.config.suiteId == suite.id }
    val loadedRuns by produceState<LoadedSuiteRuns?>(
        initialValue = null,
        key1 = suite.id,
        key2 = ui.state.testRuns,
        key3 = ui.state.testRunsFolderEpoch,
    ) {
        value = LoadedSuiteRuns(suite.id, ui.state.testRunCoordinator.loadSuiteRunHistory(suite.id))
    }
    // A result read for another suite (the screen was reused) does not count: the new suite is loading again.
    val loaded = loadedRuns?.suiteId == suite.id
    val stored = loadedRuns?.takeIf { loaded }?.stored ?: StoredSuiteRunHistory(liveRuns, liveRuns.map { it.summary() })
    val runs = remember(liveRuns, stored) { mergeRunSnapshots(liveRuns, stored.recentRecords) }
    val history = remember(suite, runs, stored.summaries) { deriveTestSuiteHistory(suite, runs, allRunSummaries = stored.summaries) }
    val issueRevision by ui.state.issueStore.revision.collectAsState()
    val issues by produceState<List<IssueRecord>>(emptyList(), issueRevision) {
        value = withContext(Dispatchers.IO) { ui.state.issueStore.list() }
    }
    return SuiteScreenData(history, issues, loaded)
}

private fun deleteSuiteAndSelectNext(ui: TestsUi, suite: TestSuite) {
    val next = ui.library.suites.firstOrNull { it.id != suite.id }?.id
    if (ui.report(ui.state.deleteTestSuite(suite.id)) is StoreResult.Ok) {
        ui.view.selectedSuiteId = next
        ui.view.selectedCaseId = null
    }
}

// ── Header ───────────────────────────────────────────────────────────

/** The suite name, ▶ Run suite and the ⋯ menu (Duplicate, Export…, Delete…), with the read-only notice under it. */
@Composable
private fun SuiteHeader(suite: TestSuite, access: TestsEditAccess, onDelete: () -> Unit) {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val writable = !ui.library.readOnly
    TestsPageHeader(
        title = suite.name,
        onTitleCommit = { t -> ui.state.updateTestSuite(suite.id) { it.copy(name = t.trim()) } },
        titleEditable = access.editable,
        titleDescription = "Suite name",
        titlePlaceholder = "Suite name",
        primary = {
            HintedButton(
                "▶ Run suite",
                onClick = { ui.view.runDialog = RunDialogTarget(suite.id) },
                enabled = !limits.isSuiteLocked(suite.id) && suite.cases.any { !limits.isCaseLocked(it.id) },
                disabledHint = limits.hint,
                variant = ButtonVariant.Primary,
            )
        },
        overflowDescription = "Suite actions",
        overflowItems = listOf(
            TestsMenuItem(
                "Duplicate",
                onClick = { duplicateSuiteAndSelect(ui, suite) },
                enabled = writable && limits.canDuplicateSuite(suite.id),
                disabledHint = if (!writable) LIBRARY_READ_ONLY_MESSAGE else limits.hint,
            ),
            TestsMenuItem("Export…", onClick = { exportSuiteToChosenFile(ui, suite) }),
            TestsMenuItem(
                "Delete…", onClick = onDelete, danger = true,
                enabled = writable, disabledHint = LIBRARY_READ_ONLY_MESSAGE, dividerBefore = true,
            ),
        ),
    )
    if (access.reason != null) {
        Spacer(Modifier.height(6.dp))
        TestsLockedNotice(access.reason)
    }
}

/** "Last run  ● Passed  yesterday 19:27 · 3 cases · 1 lane · 12 of 12 steps passed  Open report →", or the loading / never-run text. */
@Composable
private fun SuiteLastRunLine(line: SuiteLastRun) {
    val tc = tc()
    val ui = LocalTestsUi.current
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        when (line) {
            SuiteLastRun.Loading -> AppText("Loading runs…", color = tc.td, fontSize = 11.sp)
            SuiteLastRun.NeverRun -> AppText("Not run yet", color = tc.td, fontSize = 11.sp)
            is SuiteLastRun.Ran -> {
                val color = runColor(line.status)
                AppText("Last run", color = tc.td, fontSize = 11.sp)
                StatusDot(color)
                AppText(line.status.label(), color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                AppText(
                    "${formatRunMoment(line.momentMs, System.currentTimeMillis())} · ${line.detail()}",
                    color = tc.ts, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                TestsBreadcrumbLink("Open report →", onClick = { openSuiteRun(ui, line.runId) })
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(DOT_SIZE).background(color, CircleShape))
}

// ── Tabs ─────────────────────────────────────────────────────────────

@Composable
private fun SuiteTabs(suite: TestSuite, runCount: Int?) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val tabs = SuiteTab.entries
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br).align(Alignment.BottomStart))
        UnderlineTabs(
            labels = tabs.map { suiteTabLabel(it, suite.cases.size, runCount) },
            selectedIndex = tabs.indexOf(ui.view.selectedSuiteTab),
            onSelect = {
                ui.view.selectedSuiteTab = tabs[it]
                ui.reclaimFocus()
            },
            modifier = Modifier.widthIn(max = SUITE_TABS_MAX_WIDTH),
        )
    }
}

// ── Cases ────────────────────────────────────────────────────────────

@Composable
private fun SuiteCasesTab(suite: TestSuite, data: SuiteScreenData) {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    var caseToDelete by remember(suite.id) { mutableStateOf<TestCase?>(null) }
    Column(Modifier.fillMaxWidth()) {
        if (suite.cases.isEmpty()) {
            TestsHint("No cases yet. Add a case to define a workflow for the agent.")
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = suiteCasesCompact(maxWidth.value)
                Column(Modifier.fillMaxWidth()) {
                    if (!compact) CasesTableHeader()
                    Box(Modifier.fillMaxWidth().height(1.dp).background(tc().br))
                    ReorderableColumn(
                        items = suite.cases,
                        idOf = { it.id },
                        onMove = { id, to -> ui.report(ui.state.moveTestCase(id, to)) },
                        reorderEnabled = !ui.library.readOnly && !suite.readOnly,
                    ) { case, row ->
                        CaseRow(suite, case, row, data, compact, onDelete = { caseToDelete = case })
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row {
            HintedButton(
                "+ New case",
                onClick = { createCaseAndOpen(ui, suite) },
                enabled = !ui.library.readOnly && !suite.readOnly && limits.canCreateCase(suite.id),
                disabledHint = limits.hint,
                variant = ButtonVariant.Ghost,
            )
        }
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

/** The column headings, laid out with the same paddings and widths as a [CaseRow] so they line up. */
@Composable
private fun CasesTableHeader() {
    Row(
        Modifier.fillMaxWidth().height(28.dp).padding(start = ROW_START_PADDING, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.width(GRIP_COLUMN))
        TableHeading("#", Modifier.width(INDEX_COLUMN))
        TableHeading("CASE", Modifier.weight(1f))
        TableHeading("STEPS", Modifier.width(STEPS_COLUMN), Alignment.CenterEnd)
        TableHeading("LATEST", Modifier.width(LATEST_COLUMN))
        TableHeading("ISSUES", Modifier.width(ISSUES_COLUMN), Alignment.CenterEnd)
        Spacer(Modifier.width(MENU_COLUMN))
    }
}

@Composable
private fun TableHeading(text: String, modifier: Modifier, alignment: Alignment = Alignment.CenterStart) {
    Box(modifier, contentAlignment = alignment) { AppText(text, color = tc().td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold) }
}

/** One case: the grip (hover), its number, name with a goal line, step count, latest result, issues and the ⋯ menu. */
@Composable
private fun CaseRow(suite: TestSuite, case: TestCase, row: ReorderRowScope, data: SuiteScreenData, compact: Boolean, onDelete: () -> Unit) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    var hovered by remember { mutableStateOf(false) }
    val history = data.history.cases[case.id]
    val issueIds = caseIssueIds(history, case.id, suite.id, data.issues)
    val goal = case.description.replace('\n', ' ').trim().takeIf { it.isNotBlank() && it != case.name.trim() }
    val locked = limits.isCaseLocked(case.id)
    Column(Modifier.fillMaxWidth()) {
        TestsListRow(
            selected = false,
            onClick = { ui.view.selectedCaseId = case.id },
            modifier = Modifier.onPointerEvent(PointerEventType.Enter) { hovered = true }.onPointerEvent(PointerEventType.Exit) { hovered = false },
            minHeight = if (goal != null || compact) ROW_MIN_HEIGHT_WITH_GOAL else ROW_MIN_HEIGHT,
            startPadding = ROW_START_PADDING,
        ) {
            ReorderGrip(row, Modifier.alpha(if (hovered || row.dragging) 1f else 0f))
            AppText("${row.index + 1}", color = tc.td, fontSize = 10.sp, fontFamily = MONO, modifier = Modifier.width(INDEX_COLUMN))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppText(
                        case.name.ifBlank { "Untitled case" }, color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (locked) LockBadge(limits.hint)
                }
                if (compact) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppText("${case.steps.size} steps ·", color = tc.ts, fontSize = 10.sp)
                        CaseLatestCell(history, data.loaded, Modifier.weight(1f, fill = false))
                        IssuesCount(issueIds)
                    }
                } else if (goal != null) {
                    AppText(goal, color = tc.td, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!compact) {
                Box(Modifier.width(STEPS_COLUMN), contentAlignment = Alignment.CenterEnd) {
                    AppText("${case.steps.size}", color = tc.ts, fontSize = 11.sp)
                }
                CaseLatestCell(history, data.loaded, Modifier.width(LATEST_COLUMN))
                Box(Modifier.width(ISSUES_COLUMN), contentAlignment = Alignment.CenterEnd) { IssuesCount(issueIds) }
            }
            CaseRowMenu(case, row, hovered, onDelete)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br.copy(alpha = ROW_SEPARATOR_ALPHA)))
    }
}

/** The row's ⋯ (Move up, Move down, Duplicate, Delete…): faint until the row is hovered. */
@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun CaseRowMenu(case: TestCase, row: ReorderRowScope, hovered: Boolean, onDelete: () -> Unit) {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val writable = !ui.library.readOnly
    Box(Modifier.alpha(if (hovered) 1f else ROW_MENU_IDLE_ALPHA)) {
        TestsOverflowMenu(
            contentDescription = "Actions for ${case.name.ifBlank { "Untitled case" }}",
            items = listOf(
                TestsMenuItem("Move up", onClick = row.moveUp, enabled = row.canMoveUp, disabledHint = if (writable && row.enabled) null else LIBRARY_READ_ONLY_MESSAGE),
                TestsMenuItem("Move down", onClick = row.moveDown, enabled = row.canMoveDown, disabledHint = if (writable && row.enabled) null else LIBRARY_READ_ONLY_MESSAGE),
                TestsMenuItem(
                    "Duplicate",
                    onClick = { ui.report(ui.state.duplicateTestCase(case.id)) },
                    enabled = writable && limits.canDuplicateCase(case.id),
                    disabledHint = if (!writable) LIBRARY_READ_ONLY_MESSAGE else limits.hint,
                ),
                TestsMenuItem("Delete…", onClick = onDelete, danger = true, enabled = writable, disabledHint = LIBRARY_READ_ONLY_MESSAGE, dividerBefore = true),
            ),
        )
    }
}

/** The number of issues linked to a case; clicking it opens the Issues screen on the first one. Nothing is drawn for none. */
@Composable
private fun IssuesCount(issueIds: List<String>) {
    if (issueIds.isEmpty()) return
    val tc = tc()
    val ui = LocalTestsUi.current
    Box(
        Modifier.background(tc.warn.copy(alpha = ISSUE_PILL_ALPHA), CORNER_SM)
            .clickable {
                ui.view.nav = TestsNav.Issues
                ui.view.selectedIssueId = issueIds.first()
                ui.reclaimFocus()
            }
            .semantics(mergeDescendants = true) {
                contentDescription = "Issues: ${issueIds.size}"
                role = Role.Button
            }
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) { AppText("${issueIds.size}", color = tc.warn, fontSize = 10.sp, fontWeight = FontWeight.Medium) }
}

private fun laneLabel(ui: TestsUi, lane: LaneConfig): String =
    if (lane.kind == LaneKind.EXTERNAL) {
        "External · ${lane.deviceSerial}"
    } else {
        ui.state.settings.aiProviderProfiles.firstOrNull { it.id == lane.profileId }?.displayName ?: lane.profileId.orEmpty()
    }

/**
 * A case's latest result in one fixed-height line. One lane: dot, status, duration and "×repeats". Several lanes: one dot per
 * lane (the row never grows) with a tooltip listing each lane's name, status and duration. "Not run" only once the history loaded.
 */
@Composable
private fun CaseLatestCell(history: SuiteCaseHistory?, loaded: Boolean, modifier: Modifier) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val lanes = history.ranLanes()
    if (lanes.isEmpty()) {
        AppText(if (loaded) "Not run" else "—", color = tc.td, fontSize = 10.sp, modifier = modifier)
        return
    }
    val single = lanes.singleOrNull()
    if (single != null) {
        val color = caseColor(single.status)
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            StatusDot(color)
            AppText(single.status.label(), color = color, fontSize = 11.sp)
            AppText(formatDuration(single.durationMs), color = tc.td, fontSize = 10.sp, fontFamily = MONO, maxLines = 1)
            if (single.repeatCount > 1) AppText("×${single.repeatCount}", color = tc.td, fontSize = 10.sp)
        }
        return
    }
    val lines = lanes.map { laneResultLine(laneLabel(ui, it.lane), it.status, it.durationMs, it.repeatCount) }
    TooltipArea(tooltip = { ToolbarTooltip(lines.joinToString("\n"), maxLines = lines.size) }, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            lanes.forEachIndexed { index, lane ->
                StatusDot(caseColor(lane.status), Modifier.semantics { contentDescription = lines[index] })
            }
            if (history?.disagreement == true) AppText("≠", color = DANGER_RED, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Runs ─────────────────────────────────────────────────────────────

@Composable
private fun SuiteRunsTab(data: SuiteScreenData) {
    val ui = LocalTestsUi.current
    val runs = data.history.runs
    when {
        runs.isNotEmpty() -> RunHistoryList(runs, showSuite = false, onOpen = { openSuiteRun(ui, it) })
        !data.loaded -> TestsHint("Loading runs…")
        else -> TestsHint("No runs yet. Start one with Run suite.")
    }
}

// ── Settings ─────────────────────────────────────────────────────────

/** Setup & teardown hooks, agent instructions and variables: the suite metadata that is edited rarely. */
@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun SuiteSettingsTab(suite: TestSuite, editable: Boolean) {
    val ui = LocalTestsUi.current
    val update: ((TestSuite) -> TestSuite) -> StoreResult<*> = { transform -> ui.state.updateTestSuite(suite.id, transform) }
    Column(Modifier.fillMaxWidth()) {
        TestsSectionTitle("Setup & teardown")
        TestsHint("Suite hooks run once around the selected cases. Case hooks are edited inside each case.")
        HooksEditor("Setup hooks", suite.setup, editable) { hooks -> update { it.copy(setup = hooks) } }
        HooksEditor("Teardown hooks", suite.teardown, editable) { hooks -> update { it.copy(teardown = hooks) } }
        Spacer(Modifier.height(10.dp))
        Divider()
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
        Spacer(Modifier.height(10.dp))
        Divider()
        // VariablesEditor draws its own "Variables" heading and + Add button.
        TestsHint("Values in this list are available to suite instructions and script parameters.")
        VariablesEditor(suite.variables, editable) { variables -> ui.report(ui.state.updateTestSuite(suite.id) { it.copy(variables = variables) }) }
    }
}

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
        val evidenceNote = if (ui.state.testSuiteHasRecordingEvidence(suite.id)) {
            " Stored recording evidence (video, screenshots) is not included."
        } else {
            ""
        }
        ui.info("Exported \"${suite.name}\" to ${file.name}.$assetNote$evidenceNote")
    }
}
