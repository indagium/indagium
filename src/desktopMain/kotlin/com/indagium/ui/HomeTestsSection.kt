package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueStatus
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.RunSummary
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.StoredLatestRuns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The New tab's Tests section (design A2): the suites with their last result and a Run button, the newest finished run, the
// open-issue counts and, on a limited edition, the usage line. It reads the stored runs through AppState.latestTestRunSummaries
// (never starting the run coordinator) and opens the Tests tab through AppState.openTestsSuite. TestsOverflowMenu / LocalTestsUi
// belong to the Tests workspace and are not available here, so everything below is home-local.

/** The card width (dp) from which the suites and the latest-activity column sit side by side. */
internal const val HOME_TESTS_WIDE_FROM_DP: Int = 640

private const val HOME_TESTS_ROW_MIN_HEIGHT_DP = 40
private const val HOME_TESTS_DOT_DP = 8
private const val HOME_TESTS_ICON_DP = 16
private const val HOME_TESTS_COLUMN_GAP_DP = 20
private const val HOME_TESTS_SUITES_WEIGHT = 1.4f
private const val HOME_TESTS_SIDE_WEIGHT = 1f
private const val HOME_TESTS_LOADING_TEXT = "…"

/** What the section knows about one suite's last finished run. */
internal sealed interface HomeLastRun {
    /** The stored runs are still being read: never claim "Not run yet" at this point. */
    data object Loading : HomeLastRun

    data object NeverRun : HomeLastRun

    data class Ran(val status: RunStatus, val momentMs: Long) : HomeLastRun
}

/**
 * The line under a suite's name: "5 cases · Passed Oct 13, 19:11", "3 cases · Not run yet", "3 cases · …" while loading, or
 * "5 cases · read-only on Free" for a suite the edition locks ([editionLabel] names the edition). Pure.
 */
internal fun homeSuiteSubLine(caseCount: Int, locked: Boolean, editionLabel: String, last: HomeLastRun, nowMs: Long): String {
    val tail = when {
        locked -> "read-only on $editionLabel"
        last is HomeLastRun.NeverRun -> "Not run yet"
        last is HomeLastRun.Ran -> "${last.status.label()} ${formatRunMoment(last.momentMs, nowMs)}"
        else -> HOME_TESTS_LOADING_TEXT
    }
    return "${countNoun(caseCount, "case")} · $tail"
}

/** "Run passed · 8 of 8 steps · 2m 34s": the headline of the newest finished run (the duration is left out when unknown). Pure. */
internal fun homeLatestRunLine(run: RunSummary): String {
    val outcome = when (run.status) {
        RunStatus.PASSED -> "passed"
        RunStatus.FAILED -> "failed"
        RunStatus.CANCELLED -> "was cancelled"
        RunStatus.ERROR -> "ended with an error"
        RunStatus.QUEUED, RunStatus.RUNNING -> run.status.label().lowercase()
    }
    val steps = "${run.passedSteps} of ${run.totalSteps} ${if (run.totalSteps == 1) "step" else "steps"}"
    val duration = run.finishedAt?.let { formatDuration(it - run.createdAt) }
    return listOfNotNull("Run $outcome", steps, duration).joinToString(" · ")
}

/** "1 open issue · 1 draft", "3 open issues · 2 drafts · 1 saved"; null when no issue is open (the section hides the line). Pure. */
internal fun homeIssuesLine(issues: List<IssueRecord>): String? {
    val drafts = issues.count { it.status == IssueStatus.DRAFT }
    val saved = issues.count { it.status == IssueStatus.SAVED }
    val open = drafts + saved
    if (open == 0) return null
    val parts = listOfNotNull(
        countNoun(open, "open issue"),
        countNoun(drafts, "draft").takeIf { drafts > 0 },
        "$saved saved".takeIf { saved > 0 },
    )
    return parts.joinToString(" · ")
}

/** The stored latest runs, tagged with the suites they were read for, so a suite created since shows as loading, not "Not run yet". */
private class LoadedLatestRuns(val suiteIds: Set<String>, val runs: StoredLatestRuns)

/**
 * The body of the New tab's Tests card. [onReclaimFocus] gives root keyboard focus back after any click (see CLAUDE.md's
 * clickable-steals-focus gotcha). Side by side (suites | latest activity, issues, usage) from [HOME_TESTS_WIDE_FROM_DP], stacked below.
 */
@Composable
internal fun HomeTestsSection(state: AppState, onReclaimFocus: () -> Unit, modifier: Modifier = Modifier) {
    val library = state.testLibrary
    val edition by state.editionService.current.collectAsState()
    val limits = remember(library, edition) { testsLimitsUiState(library, edition.limits) }
    val suiteIds = library.suites.map { it.id }
    val finishedRunIds = state.testRuns.filter { it.isFinished }.map { it.id }
    // The produced state survives key changes, so a reload keeps showing the previous result instead of flashing "…".
    val loaded by produceState<LoadedLatestRuns?>(null, suiteIds, finishedRunIds, state.testRunsFolderEpoch) {
        value = LoadedLatestRuns(suiteIds.toSet(), state.latestTestRunSummaries(suiteIds.toSet()))
    }
    val issueRevision by state.issueStore.revision.collectAsState()
    val issues by produceState<List<IssueRecord>>(emptyList(), issueRevision) {
        value = withContext(Dispatchers.IO) { runCatching { state.issueStore.list() }.getOrDefault(emptyList()) }
    }
    val usageLine = remember(library, edition) { testsEditionUsage(library, edition.limits)?.let { editionUsageLine(edition.label, it) } }
    val suitesColumn: @Composable () -> Unit = { HomeTestsSuites(state, limits, edition.label, loaded, onReclaimFocus) }
    val sideColumn: @Composable () -> Unit = {
        HomeTestsSide(state, loaded?.runs?.latestOverall, homeIssuesLine(issues), usageLine, onReclaimFocus)
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth.value >= HOME_TESTS_WIDE_FROM_DP
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(HOME_SECTION_BODY_PADDING)) {
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HOME_TESTS_COLUMN_GAP_DP.dp)) {
                    Box(Modifier.weight(HOME_TESTS_SUITES_WEIGHT)) { suitesColumn() }
                    Box(Modifier.weight(HOME_TESTS_SIDE_WEIGHT)) { sideColumn() }
                }
            } else {
                suitesColumn()
                Box(Modifier.padding(top = HOME_TESTS_COLUMN_GAP_DP.dp)) { sideColumn() }
            }
        }
    }
}

// ── Suites ───────────────────────────────────────────────────────────

@Composable
private fun HomeTestsSuites(
    state: AppState,
    limits: TestsLimitsUiState,
    editionLabel: String,
    loaded: LoadedLatestRuns?,
    onReclaimFocus: () -> Unit,
) {
    val tc = tc()
    val library = state.testLibrary
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HomeTestsHeading("Suites · ${library.suites.size}", Modifier.weight(1f))
            HintedButton(
                "+ New suite",
                onClick = {
                    createSuiteAndOpen(state)
                    onReclaimFocus()
                },
                enabled = !library.readOnly && limits.canCreateSuite,
                disabledHint = if (library.readOnly) LIBRARY_READ_ONLY_MESSAGE else limits.hint,
                modifier = Modifier.testTag("home-tests-new-suite"),
            )
        }
        if (library.suites.isEmpty()) {
            AppText("No test suites yet.", color = tc.td, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp).testTag("home-tests-empty"))
        }
        val nowMs = System.currentTimeMillis()
        library.suites.forEach { suite ->
            val locked = limits.isSuiteLocked(suite.id)
            val last = lastRunOf(suite.id, loaded)
            HomeSuiteRow(
                state = state,
                suiteId = suite.id,
                name = suite.name,
                subLine = homeSuiteSubLine(suite.cases.size, locked, editionLabel, last, nowMs),
                last = last,
                lockHint = limits.hint.takeIf { locked },
                canRun = !locked && suite.cases.any { !limits.isCaseLocked(it.id) },
                runHint = limits.hint,
                onReclaimFocus = onReclaimFocus,
            )
        }
    }
}

/** The suite's last finished run, or [HomeLastRun.Loading] until stored runs read for a set of suites that includes it arrived. */
private fun lastRunOf(suiteId: String, loaded: LoadedLatestRuns?): HomeLastRun {
    if (loaded == null || suiteId !in loaded.suiteIds) return HomeLastRun.Loading
    val summary = loaded.runs.bySuite[suiteId] ?: return HomeLastRun.NeverRun
    return HomeLastRun.Ran(summary.status, summary.finishedAt ?: summary.createdAt)
}

/** Creates a suite and opens the Tests tab on it; a refusal (the button is disabled in that case) leaves everything as it was. */
private fun createSuiteAndOpen(state: AppState) {
    val result = state.createNewTestSuite()
    if (result is StoreResult.Ok) state.openTestsSuite(result.value.id, openRunDialog = false)
}

@Composable
@Suppress("LongParameterList")
private fun HomeSuiteRow(
    state: AppState,
    suiteId: String,
    name: String,
    subLine: String,
    last: HomeLastRun,
    lockHint: String?,
    canRun: Boolean,
    runHint: String,
    onReclaimFocus: () -> Unit,
) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().heightIn(min = HOME_TESTS_ROW_MIN_HEIGHT_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(HOME_TESTS_ICON_DP.dp), contentAlignment = Alignment.Center) {
            if (lockHint != null) {
                LockBadge(lockHint)
            } else {
                val color = if (last is HomeLastRun.Ran) runColor(last.status) else tc.td
                Box(Modifier.size(HOME_TESTS_DOT_DP.dp).background(color, CircleShape))
            }
        }
        HoverBox(
            Modifier.weight(1f).testTag("home-tests-suite-$suiteId"),
            onClick = {
                state.openTestsSuite(suiteId, openRunDialog = false)
                onReclaimFocus()
            },
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                AppText(name, color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.Medium, overflow = TextOverflow.Ellipsis)
                AppText(subLine, color = tc.td, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
            }
        }
        if (lockHint != null) {
            AppButton(
                "View",
                onClick = {
                    state.openTestsSuite(suiteId, openRunDialog = false)
                    onReclaimFocus()
                },
                variant = ButtonVariant.Secondary,
                modifier = Modifier.testTag("home-tests-view-$suiteId"),
            )
        } else {
            HintedButton(
                "Run",
                onClick = {
                    state.openTestsSuite(suiteId, openRunDialog = true)
                    onReclaimFocus()
                },
                enabled = canRun,
                disabledHint = runHint,
                variant = ButtonVariant.Secondary,
                modifier = Modifier.testTag("home-tests-run-$suiteId"),
            )
        }
    }
}

// ── Latest activity, issues, usage ───────────────────────────────────

@Composable
private fun HomeTestsSide(
    state: AppState,
    latest: RunSummary?,
    issuesLine: String?,
    usageLine: String?,
    onReclaimFocus: () -> Unit,
) {
    val tc = tc()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (latest != null) {
            HomeTestsHeading("Latest activity")
            HomeActivityRow(
                icon = latestRunIcon(latest.status),
                tint = runColor(latest.status),
                title = homeLatestRunLine(latest),
                detail = "${latest.suiteName} · ${formatRunMoment(latest.finishedAt ?: latest.createdAt, System.currentTimeMillis())}",
                tag = "home-tests-latest",
            ) {
                openRunReport(state, latest)
                onReclaimFocus()
            }
        }
        if (issuesLine != null) {
            HomeTestsHeading("Issues")
            HomeActivityRow(icon = Icons.Outlined.ErrorOutline, tint = tc.warn, title = issuesLine, detail = null, tag = "home-tests-issues") {
                state.testsView.nav = TestsNav.Issues
                state.testsView.selectedIssueId = null
                state.openTestsTab()
                onReclaimFocus()
            }
        }
        if (usageLine != null) {
            AppText(usageLine, color = tc.td, fontSize = 11.sp, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("home-tests-usage"))
        }
    }
}

/** Opens [run]'s report in the Tests tab (Runs screen with the run selected). */
private fun openRunReport(state: AppState, run: RunSummary) {
    state.testsView.nav = TestsNav.Runs
    state.testsView.selectedRunId = run.id
    state.openTestsTab()
}

private fun latestRunIcon(status: RunStatus): ImageVector = when (status) {
    RunStatus.PASSED -> Icons.Outlined.CheckCircle
    RunStatus.FAILED -> Icons.Outlined.Cancel
    RunStatus.ERROR -> Icons.Outlined.ErrorOutline
    RunStatus.CANCELLED, RunStatus.QUEUED, RunStatus.RUNNING -> Icons.Outlined.Block
}

@Composable
private fun HomeActivityRow(icon: ImageVector, tint: Color, title: String, detail: String?, tag: String, onClick: () -> Unit) {
    val tc = tc()
    HoverBox(Modifier.fillMaxWidth().testTag(tag), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(HOME_TESTS_ICON_DP.dp))
            Column(Modifier.weight(1f)) {
                AppText(title, color = tc.tx, fontSize = 12.sp, overflow = TextOverflow.Ellipsis)
                if (detail != null) AppText(detail, color = tc.td, fontSize = 11.sp, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HomeTestsHeading(text: String, modifier: Modifier = Modifier) {
    AppText(text, color = tc().ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}
