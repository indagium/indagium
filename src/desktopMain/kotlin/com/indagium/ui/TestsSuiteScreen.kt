@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.indagium.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
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
import com.indagium.testing.model.CaseRunCell
import com.indagium.testing.model.CaseRunMatrix
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.RunSummary
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.SuiteCaseHistory
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.TestSuiteHistory
import com.indagium.testing.model.deriveCaseRunMatrix
import com.indagium.testing.model.deriveTestSuiteHistory
import com.indagium.testing.model.summary
import com.indagium.testing.run.mergeFinishedOutcomes
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
private val LAST_RUNS_COLUMN = 72.dp
private val LATEST_COLUMN = 150.dp
private val ISSUES_COLUMN = 52.dp
private val GRIP_COLUMN = 18.dp
private val MENU_COLUMN = 26.dp
private val ROW_MIN_HEIGHT = 34.dp
private val ROW_MIN_HEIGHT_WITH_GOAL = 44.dp
private val ROW_START_PADDING = 6.dp
private val SUITE_TABS_MAX_WIDTH = 330.dp
private val DOT_SIZE = 8.dp
private val RUN_SQUARE_SIZE = 8.dp
private val RUN_SQUARE_GAP = 3.dp
private val RUN_SQUARE_SHAPE = RoundedCornerShape(2.dp)
private const val LAST_RUNS_SHOWN = 6
private const val RUN_SQUARE_OTHER_ALPHA = .45f
private const val NOTICE_BORDER_ALPHA = .5f
private val STEP_ICON_SIZE = 14.dp
private val STEP_NUMBER_WIDTH = 16.dp
private const val ROW_MENU_IDLE_ALPHA = .35f
private const val ROW_SEPARATOR_ALPHA = .6f
private const val ISSUE_PILL_ALPHA = .18f

/** What the suite screen derived from the run history. [loaded] is false until the stored runs were read. */
private class SuiteScreenData(
    val history: TestSuiteHistory,
    val issues: List<IssueRecord>,
    val loaded: Boolean,
    /** The suite's cases against its newest finished runs (stored outcomes plus this launch's finished runs). */
    val matrix: CaseRunMatrix,
    /** "#N" numbers of the suite's runs by run id, oldest = 1. */
    val ordinals: Map<String, Int>,
)

@Composable
internal fun TestsSuiteScreen(suiteId: String) {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val library = ui.library
    val suite = library.suite(suiteId) ?: return
    val access = suiteEditAccess(library, suite, limits)
    val data = rememberSuiteScreenData(suite)
    var confirmDelete by remember { mutableStateOf(false) }
    // A selected row is only honoured for a case of this suite on the Cases tab (a stale id of another suite or a deleted case shows nothing).
    LaunchedEffect(suite.id) {
        if (ui.view.previewCaseId != null && suite.cases.none { it.id == ui.view.previewCaseId }) ui.view.previewCaseId = null
    }
    val previewCase = suite.cases.firstOrNull { it.id == ui.view.previewCaseId }.takeIf { ui.view.selectedSuiteTab == SuiteTab.Cases }
    val detailPanel: (@Composable () -> Unit)? = previewCase?.let { case -> { CaseDetailPanel(suite, case, data) } }
    TestsScreenScaffold(inspector = detailPanel) {
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
    // The disk is read again only when something it holds can have changed: another suite, a changed or pruned folder, or one of this
    // suite's live runs finishing (it was just saved). Progress updates of a running run change `testRuns` many times a second and
    // must not rescan every run file; they are merged below, in composition.
    val finishedLiveIds = liveRuns.filter { it.isFinished }.map { it.id }
    val loadedRuns by produceState<LoadedSuiteRuns?>(
        initialValue = null,
        key1 = suite.id,
        key2 = ui.state.testRunsFolderEpoch,
        key3 = finishedLiveIds,
    ) {
        value = LoadedSuiteRuns(suite.id, ui.state.testRunCoordinator.loadSuiteRunHistory(suite.id))
    }
    // A result read for another suite (the screen was reused) does not count: the new suite is loading again.
    val loaded = loadedRuns?.suiteId == suite.id
    val stored = loadedRuns?.takeIf { loaded }?.stored ?: StoredSuiteRunHistory(liveRuns, liveRuns.map { it.summary() })
    val runs = remember(liveRuns, stored) { mergeRunSnapshots(liveRuns, stored.recentRecords) }
    // The stored summaries are as old as the last read, so a run started or progressed since then comes from the live snapshots.
    val summaries = remember(liveRuns, stored.summaries) {
        val liveSummaries = liveRuns.map { it.summary() }
        val liveIds = liveSummaries.mapTo(HashSet()) { it.id }
        (liveSummaries + stored.summaries.filter { it.id !in liveIds })
            .sortedWith(compareByDescending<RunSummary> { it.createdAt }.thenByDescending { it.id })
    }
    val history = remember(suite, runs, summaries) { deriveTestSuiteHistory(suite, runs, allRunSummaries = summaries) }
    val matrix = remember(suite, liveRuns, stored.finishedOutcomes) {
        deriveCaseRunMatrix(suite, mergeFinishedOutcomes(stored.finishedOutcomes, liveRuns))
    }
    val ordinals = remember(summaries) { runOrdinals(summaries) }
    val issueRevision by ui.state.issueStore.revision.collectAsState()
    val issues by produceState<List<IssueRecord>>(emptyList(), issueRevision) {
        value = withContext(Dispatchers.IO) { ui.state.issueStore.list() }
    }
    return SuiteScreenData(history, issues, loaded, matrix, ordinals)
}

private fun deleteSuiteAndSelectNext(ui: TestsUi, suite: TestSuite) {
    val next = ui.library.suites.firstOrNull { it.id != suite.id }?.id
    if (ui.report(ui.state.deleteTestSuite(suite.id)) is StoreResult.Ok) {
        ui.view.selectedSuiteId = next
        ui.view.selectedCaseId = null
    }
}

// ── Header ───────────────────────────────────────────────────────────

/** The suite name, + New case, ▶ Run suite and the ⋯ menu (Duplicate, Export…, Delete…), with the read-only notice under it. */
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
        proseTargetId = "test-suite:${suite.id}:title",
        titlePlaceholder = "Suite name",
        primary = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NewCaseButton(suite)
                HintedButton(
                    "▶ Run suite",
                    onClick = { ui.view.runDialog = RunDialogTarget(suite.id) },
                    enabled = !limits.isSuiteLocked(suite.id) && suite.cases.any { !limits.isCaseLocked(it.id) },
                    disabledHint = limits.hint,
                    variant = ButtonVariant.Primary,
                )
            }
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
        Box(Modifier.fillMaxWidth().align(Alignment.BottomStart)) { Divider() }
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

/** "+ New case": the header's Secondary button, and the empty state's ghost one. Disabled (with the reason) when the suite cannot take a case. */
@Composable
private fun NewCaseButton(suite: TestSuite, variant: ButtonVariant = ButtonVariant.Secondary) {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    HintedButton(
        "+ New case",
        onClick = { createCaseAndOpen(ui, suite) },
        enabled = !ui.library.readOnly && !suite.readOnly && limits.canCreateCase(suite.id),
        disabledHint = limits.hint,
        variant = variant,
    )
}

@Composable
private fun SuiteCasesTab(suite: TestSuite, data: SuiteScreenData) {
    val ui = LocalTestsUi.current
    var caseToDelete by remember(suite.id) { mutableStateOf<TestCase?>(null) }
    val edition by ui.state.editionService.current.collectAsState()
    val limits = LocalTestsLimits.current
    TestsKeyTargetEffect { event -> handleCasesKey(ui, suite, event) }
    Column(Modifier.fillMaxWidth()) {
        if (suite.cases.isEmpty()) {
            TestsHint("No cases yet. Add a case to define a workflow for the agent.")
            Spacer(Modifier.height(8.dp))
            Row { NewCaseButton(suite, ButtonVariant.Ghost) }
        } else {
            val notice = lockedCasesNoticeText(suite, limits, edition.label)
            if (notice != null && !ui.view.lockedCasesNoticeDismissed) {
                LockedCasesNotice(notice) {
                    ui.view.lockedCasesNoticeDismissed = true
                    ui.reclaimFocus()
                }
                Spacer(Modifier.height(8.dp))
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = suiteCasesCompact(maxWidth.value)
                Column(Modifier.fillMaxWidth()) {
                    if (!compact) CasesTableHeader()
                    Divider()
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
        TableHeading("Case", Modifier.weight(1f))
        TableHeading("Steps", Modifier.width(STEPS_COLUMN), Alignment.CenterEnd)
        TableHeading("Last 6 runs", Modifier.width(LAST_RUNS_COLUMN))
        TableHeading("Latest", Modifier.width(LATEST_COLUMN))
        TableHeading("Issues", Modifier.width(ISSUES_COLUMN), Alignment.CenterEnd)
        Spacer(Modifier.width(MENU_COLUMN))
    }
}

@Composable
private fun TableHeading(text: String, modifier: Modifier, alignment: Alignment = Alignment.CenterStart) {
    Box(modifier, contentAlignment = alignment) { AppText(text, color = tc().td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold) }
}

/** One case: the grip, its number, name with a goal line, step count, latest result, issues and the ⋯ menu. */
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
    val selected = case.id == ui.view.previewCaseId
    val revealer = remember { BringIntoViewRequester() }
    // Keyboard selection can move off screen; scrolling the row into view is a no-op for one that is already visible.
    LaunchedEffect(selected) { if (selected) revealer.bringIntoView() }
    Column(Modifier.fillMaxWidth()) {
        TestsListRow(
            selected = selected,
            onClick = {
                // A click selects the row (its detail panel shows); a click on the row that is already selected opens the case.
                if (ui.view.previewCaseId == case.id) ui.view.selectedCaseId = case.id else ui.view.previewCaseId = case.id
                ui.reclaimFocus()
            },
            modifier = Modifier.bringIntoViewRequester(revealer)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }.onPointerEvent(PointerEventType.Exit) { hovered = false },
            minHeight = if (goal != null || compact) ROW_MIN_HEIGHT_WITH_GOAL else ROW_MIN_HEIGHT,
            startPadding = ROW_START_PADDING,
        ) {
            ReorderGrip(row)
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
                LastRunsCell(data, case.id, Modifier.width(LAST_RUNS_COLUMN))
                CaseLatestCell(history, data.loaded, Modifier.width(LATEST_COLUMN))
                Box(Modifier.width(ISSUES_COLUMN), contentAlignment = Alignment.CenterEnd) { IssuesCount(issueIds) }
            }
            CaseRowMenu(case, row, hovered, onDelete)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br.copy(alpha = ROW_SEPARATOR_ALPHA)))
    }
}

/** Up to the newest [LAST_RUNS_SHOWN] runs of a case as small squares, oldest first; "—" when the suite has no finished run yet. */
@Composable
private fun LastRunsCell(data: SuiteScreenData, caseId: String, modifier: Modifier) {
    val tc = tc()
    val cells = data.matrix.rows[caseId]?.cells.orEmpty().takeLast(LAST_RUNS_SHOWN)
    if (cells.isEmpty()) {
        AppText("—", color = tc.td, fontSize = 10.sp, modifier = modifier)
        return
    }
    val runIds = data.matrix.columns.takeLast(cells.size).map { it.runId }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RUN_SQUARE_GAP)) {
        cells.forEachIndexed { index, cell ->
            val description = lastRunCellDescription(data.ordinals[runIds.getOrNull(index)], cell)
            val base = Modifier.size(RUN_SQUARE_SIZE).clip(RUN_SQUARE_SHAPE).semantics { contentDescription = description }
            val fill = when (cell) {
                CaseRunCell.PASSED -> tc.success
                CaseRunCell.FAILED, CaseRunCell.ERROR -> DANGER_RED
                CaseRunCell.BLOCKED -> tc.warn
                CaseRunCell.OTHER -> tc.td.copy(alpha = RUN_SQUARE_OTHER_ALPHA)
                CaseRunCell.NOT_IN_RUN -> null
            }
            Box(if (fill != null) base.background(fill) else base.border(1.dp, tc.br, RUN_SQUARE_SHAPE))
        }
    }
}

/** The thin warn-tinted notice above the table: some cases are read-only on this edition. × hides it for the session. */
@Composable
private fun LockedCasesNotice(text: String, onDismiss: () -> Unit) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().border(1.dp, tc.warn.copy(alpha = NOTICE_BORDER_ALPHA), CORNER_MD).padding(start = 8.dp, top = 3.dp, bottom = 3.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = tc.warn, modifier = Modifier.size(12.dp))
        AppText(text, color = tc.ts, fontSize = 10.sp, maxLines = 2, modifier = Modifier.weight(1f))
        SquareIconButton("×", fontSize = 14.sp, onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "Dismiss locked cases notice" })
    }
}

/**
 * The Cases tab's bare keys: ↑/↓ select the previous/next case (the first or last with none selected), Enter opens the selected case,
 * Esc clears the selection. Anything else, and a key with a modifier, is left alone.
 */
private fun handleCasesKey(ui: TestsUi, suite: TestSuite, event: KeyEvent): Boolean {
    if (event.isAltPressed || event.isCtrlPressed || event.isMetaPressed || event.isShiftPressed) return false
    val view = ui.view
    val ids = suite.cases.map { it.id }
    val current = view.previewCaseId?.takeIf { it in ids }
    val handled = when (event.key) {
        Key.DirectionUp, Key.DirectionDown -> {
            val next = caseSelectionMovedBy(ids, current, if (event.key == Key.DirectionUp) -1 else 1)
            next?.also { view.previewCaseId = it } != null
        }
        Key.Enter, Key.NumPadEnter -> current?.also { view.selectedCaseId = it } != null
        Key.Escape -> current?.also { view.previewCaseId = null } != null
        else -> false
    }
    // A clicked control keeps keyboard focus; hand it back so the keys keep reaching the workspace root.
    if (handled) ui.reclaimFocus()
    return handled
}

/** The right-hand panel of the selected case row: goal, chips, its steps with their last result, the last run and the actions. */
@Composable
private fun CaseDetailPanel(suite: TestSuite, case: TestCase, data: SuiteScreenData) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val writable = !ui.library.readOnly
    val locked = limits.isCaseLocked(case.id)
    val lastRun = remember(data.matrix, case.id) { caseLastRun(data.matrix, case.id) }
    val lastRunRecord = lastRun?.let { last -> data.history.recentRuns.firstOrNull { it.id == last.runId } }
    val stepStatuses = remember(lastRunRecord, case.id) { latestStepStatuses(lastRunRecord, case.id) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            TestsInspectorHeader("Case ${suite.cases.indexOfFirst { it.id == case.id } + 1}", case.name.ifBlank { "Untitled case" }, Modifier.weight(1f))
            if (locked) LockBadge(limits.hint)
        }
        case.description.trim().takeIf { it.isNotEmpty() }?.let { AppText(it, color = tc.ts, fontSize = 11.sp, maxLines = 6, overflow = TextOverflow.Ellipsis) }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            suite.targetPackage.takeIf { it.isNotBlank() }?.let { TestsChip(it) }
            TestsChip(countNoun(case.steps.size, "step"))
        }
        if (case.steps.isNotEmpty()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                case.steps.forEachIndexed { index, step ->
                    DetailStepRow(index + 1, step, if (lastRun == null) StepMark.NOT_RUN else stepMark(lastRunRecord != null, stepStatuses[step.id]))
                }
            }
        }
        DetailLastRun(data, lastRun)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HintedButton(
                "Run case",
                onClick = {
                    ui.view.runDialog = RunDialogTarget(suite.id, case.id)
                    ui.reclaimFocus()
                },
                enabled = !locked && !limits.isSuiteLocked(suite.id),
                disabledHint = limits.hint,
                variant = ButtonVariant.Primary,
                leadingIcon = Icons.Outlined.PlayArrow,
            )
            HintedButton(
                "Edit steps",
                onClick = {
                    ui.view.selectedCaseId = case.id
                    ui.reclaimFocus()
                },
            )
            HintedButton(
                "Duplicate",
                onClick = {
                    ui.report(ui.state.duplicateTestCase(case.id))
                    ui.reclaimFocus()
                },
                enabled = writable && limits.canDuplicateCase(case.id),
                disabledHint = if (!writable) LIBRARY_READ_ONLY_MESSAGE else limits.hint,
            )
        }
    }
}

/** How a step of the detail panel was left by the case's last run. [UNKNOWN]: that run's steps are not loaded, so nothing is claimed. */
private enum class StepMark { PASS, FAIL, BLOCKED, NOT_RUN, UNKNOWN }

private fun stepMark(runAvailable: Boolean, status: StepStatus?): StepMark = when {
    !runAvailable -> StepMark.UNKNOWN
    status == null -> StepMark.NOT_RUN
    status == StepStatus.PASS -> StepMark.PASS
    status == StepStatus.BLOCKED || status == StepStatus.SKIPPED -> StepMark.BLOCKED
    else -> StepMark.FAIL
}

@Composable
private fun DetailStepRow(number: Int, step: TestStep, mark: StepMark) {
    val tc = tc()
    val (label, color) = when (mark) {
        StepMark.PASS -> "passed" to tc.success
        StepMark.FAIL -> "failed" to DANGER_RED
        StepMark.BLOCKED -> "blocked" to tc.warn
        StepMark.NOT_RUN -> "not run" to tc.td
        StepMark.UNKNOWN -> "" to tc.td
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        AppText("$number", color = tc.td, fontSize = 10.sp, fontFamily = MONO, modifier = Modifier.width(STEP_NUMBER_WIDTH))
        Column(Modifier.weight(1f)) {
            AppText(step.action.ifBlank { "Untitled step" }, color = tc.tx, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (step.expected.isNotBlank()) AppText(step.expected, color = tc.td, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val iconModifier = Modifier.size(STEP_ICON_SIZE).semantics { if (label.isNotEmpty()) contentDescription = "Step $number $label" }
        when (mark) {
            StepMark.PASS -> Icon(Icons.Outlined.Check, contentDescription = null, tint = color, modifier = iconModifier)
            StepMark.FAIL -> Icon(Icons.Outlined.Close, contentDescription = null, tint = color, modifier = iconModifier)
            StepMark.BLOCKED -> Icon(Icons.Outlined.Block, contentDescription = null, tint = color, modifier = iconModifier)
            StepMark.NOT_RUN -> Box(iconModifier.padding(3.dp).border(1.dp, color, CircleShape))
            StepMark.UNKNOWN -> Spacer(iconModifier)
        }
    }
}

/** "#21 · yesterday 19:27 · 48s · Open report →", "Not run yet", or "Loading runs…" until the stored runs were read. */
@Composable
private fun DetailLastRun(data: SuiteScreenData, lastRun: CaseLastRun?) {
    val tc = tc()
    val ui = LocalTestsUi.current
    if (lastRun == null) {
        AppText(if (data.loaded) "Not run yet" else "Loading runs…", color = tc.td, fontSize = 11.sp)
        return
    }
    val parts = listOfNotNull(
        data.ordinals[lastRun.runId]?.let { "#$it" },
        formatRunMoment(lastRun.momentMs, System.currentTimeMillis()),
        lastRun.durationMs?.let { formatDuration(it) },
    )
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(parts.joinToString(" · "), color = tc.ts, fontSize = 11.sp)
        TestsBreadcrumbLink("Open report →", onClick = { openSuiteRun(ui, lastRun.runId) })
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
    var deleteRequest by remember { mutableStateOf<RunDeleteRequest?>(null) }
    when {
        runs.isNotEmpty() -> RunHistoryList(
            runs, showSuite = false, onOpen = { openSuiteRun(ui, it) },
            onDelete = { deleteRequest = RunDeleteRequest(listOf(it)) },
        )
        !data.loaded -> TestsHint("Loading runs…")
        else -> TestsHint("No runs yet. Start one with Run suite.")
    }
    deleteRequest?.let { request -> RunDeleteDialog(request, onDismiss = { deleteRequest = null }) }
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
        TestsLabeled("Suite goal") { CommitTextField(suite.description, { t -> update { it.copy(description = t) } }, enabled = editable, multiline = true, placeholder = "What this suite covers", proseTargetId = "test-suite:${suite.id}:goal") }
        Spacer(Modifier.height(8.dp))
        TestsLabeled("Instructions shown to every testing agent") { CommitTextField(suite.instructions, { t -> update { it.copy(instructions = t) } }, enabled = editable, multiline = true, placeholder = "Context every case shares", proseTargetId = "test-suite:${suite.id}:instructions") }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TestsLabeled("Target package", Modifier.weight(1f)) { CommitTextField(suite.targetPackage, { t -> update { it.copy(targetPackage = t.trim()) } }, enabled = editable, placeholder = "com.example.app", mono = true) }
            TestsLabeled("Device hint", Modifier.weight(1f)) { CommitTextField(suite.deviceProfileHint, { t -> update { it.copy(deviceProfileHint = t) } }, enabled = editable, placeholder = "Pixel, Android version…", proseTargetId = "test-suite:${suite.id}:device-hint") }
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
