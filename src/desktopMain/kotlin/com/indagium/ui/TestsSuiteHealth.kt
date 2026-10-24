@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.indagium.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.testing.model.CaseRunCell
import com.indagium.testing.model.CaseRunMatrix
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueStatus
import com.indagium.testing.model.RunCaseOutcomes
import com.indagium.testing.model.SuiteHealthSummary
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.deriveSuiteHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The suite screen's Health tab (design E): summary cards over the suite's newest finished runs, a cases x runs grid with a legend,
// and the latest failure with a way into its run log or an issue draft. Everything is derived from SuiteScreenData's matrix (compact
// outcomes, no full runs); only the latest failed run is loaded in full, and only for the card.

/** The judge's reasoning or the step's observation on the latest-failure card is cut to this many characters. */
internal const val LATEST_FAILURE_DETAIL_CHARS = 280

private const val STEP_TITLE_CHARS = 80
private const val FLAKY_BADGE_ALPHA = .18f
private const val CELL_BORDER_ALPHA = .8f
private const val DIMMED_ROW_ALPHA = .5f
private const val CARD_VALUE_SIZE = 20
private const val DASH = "—"
private val CARD_MIN_WIDTH = 170.dp

// Fits one 11sp line (text or the "Review →" link); reserved in every summary card so they all share one height.
private val CARD_DETAIL_HEIGHT = 16.dp
private val CARD_SHAPE = RoundedCornerShape(8.dp)
private val GRID_INDEX_WIDTH = 24.dp
private val GRID_CASE_MIN_WIDTH = 180.dp
private val GRID_CELL_COLUMN = 26.dp
private val GRID_PASS_WIDTH = 44.dp
private val GRID_ACTION_WIDTH = 96.dp
private val GRID_ROW_HEIGHT = 30.dp
private val GRID_PADDING = 8.dp
private val GRID_SQUARE = 14.dp
private val GRID_SQUARE_SHAPE = RoundedCornerShape(3.dp)
private val LEGEND_SWATCH = 10.dp

// ── Pure helpers ─────────────────────────────────────────────────────

/** "83%", or "—" when no run in the window passed or failed. */
internal fun healthPassRateValue(health: SuiteHealthSummary): String = health.passRatePercent?.let { "$it%" } ?: DASH

/** "10 passed · 2 failed", or a quiet note when no run was judged. */
internal fun healthPassRateDetail(health: SuiteHealthSummary): String =
    if (health.passRatePercent == null) "No passed or failed runs" else "${health.passedRuns} passed · ${health.failedRuns} failed"

/** The stored issues of [suiteId] that are still open (drafts and saved ones; a sent issue is closed from here). */
internal fun openSuiteIssueCount(issues: List<IssueRecord>, suiteId: String): Int =
    issues.count { it.source.suiteId == suiteId && (it.status == IssueStatus.DRAFT || it.status == IssueStatus.SAVED) }

/** [text] on one line, cut with an ellipsis so that it is at most [limit] characters. */
internal fun trimmedFailureDetail(text: String, limit: Int = LATEST_FAILURE_DETAIL_CHARS): String {
    val flat = text.trim().replace(Regex("\\s+"), " ")
    return if (flat.length > limit) flat.take(limit - 1).trimEnd() + "…" else flat
}

/** The tooltip of a run column: "yesterday 19:27 · Failed". */
internal fun healthColumnTooltip(column: RunCaseOutcomes, nowMs: Long): String =
    "${formatRunMoment(column.finishedAt ?: column.createdAt, nowMs)} · ${column.status.label()}"

/**
 * Where a run first went wrong, for the Health tab's card. [stepId] is null when no step explains it (a case that did not pass without
 * a failing step); then there is nothing to draft an issue for. [detail] is the judge's reasoning, else the step's observation, trimmed.
 */
internal data class LatestFailure(
    val caseName: String,
    val caseId: String?,
    val iteration: Int,
    val stepId: String?,
    val stepNumber: Int?,
    val stepTitle: String,
    val laneId: String?,
    val detail: String,
)

/** The first failure of [run] (see [firstFailure]), or null when nothing failed in it. */
internal fun latestFailureOf(run: TestRun): LatestFailure? {
    val failure = firstFailure(buildMatrix(run)) ?: return null
    val step = failure.step
    val result = step?.cells?.firstOrNull { it.laneId == failure.laneId }?.result
    val reasoning = result?.judge?.reasoning.orEmpty().takeIf { it.isNotBlank() }
    val detail = trimmedFailureDetail(reasoning ?: result?.observation.orEmpty())
    return LatestFailure(
        caseName = failure.caseName,
        caseId = step?.caseId,
        iteration = step?.iteration ?: 1,
        stepId = step?.stepId,
        stepNumber = step?.number,
        stepTitle = step?.action.orEmpty().trim().take(STEP_TITLE_CHARS),
        laneId = failure.laneId,
        detail = detail,
    )
}

/** "#12 · Login · step 3 “Tap Sign in”": the card's headline ([ordinal] is the run's "#N", when it has one). */
internal fun latestFailureHeadline(ordinal: Int?, failure: LatestFailure): String {
    val step = failure.stepNumber?.let { number ->
        "step $number" + failure.stepTitle.takeIf { it.isNotEmpty() }?.let { " “$it”" }.orEmpty()
    }
    return listOfNotNull(ordinal?.let { "#$it" }, failure.caseName, step).joinToString(" · ")
}

private sealed interface FailureLoad {
    data object Loading : FailureLoad

    data object Unavailable : FailureLoad

    class Ready(val failure: LatestFailure) : FailureLoad
}

// ── Tab ──────────────────────────────────────────────────────────────

@Composable
internal fun SuiteHealthTab(suite: TestSuite, data: SuiteScreenData) {
    val matrix = data.matrix
    if (matrix.columns.isEmpty()) {
        HealthEmptyState(suite, data.loaded)
        return
    }
    val health = remember(matrix) { deriveSuiteHealth(matrix) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HealthSummaryCards(suite, data, health)
        HealthGrid(suite, data)
        HealthLegend()
        health.latestFailedRunId?.let { LatestFailureCard(it, data.ordinals[it]) }
    }
}

@Composable
private fun HealthEmptyState(suite: TestSuite, loaded: Boolean) {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    if (!loaded) {
        TestsHint("Loading runs…")
        return
    }
    TestsHint("No finished runs yet. Run the suite to see its health.")
    Spacer(Modifier.height(8.dp))
    Row {
        HintedButton(
            "Run suite",
            onClick = { ui.view.runDialog = RunDialogTarget(suite.id) },
            enabled = !limits.isSuiteLocked(suite.id) && suite.cases.any { !limits.isCaseLocked(it.id) },
            disabledHint = limits.hint,
            variant = ButtonVariant.Primary,
            leadingIcon = Icons.Outlined.PlayArrow,
        )
    }
}

// ── Summary cards ────────────────────────────────────────────────────

@Composable
private fun HealthSummaryCards(suite: TestSuite, data: SuiteScreenData, health: SuiteHealthSummary) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val newest = data.matrix.columns.last()
    val flakyNames = health.flakyCaseIds.mapNotNull { id -> suite.cases.firstOrNull { it.id == id }?.name?.ifBlank { "Untitled case" } }
    val openIssues = remember(data.issues, suite.id) { openSuiteIssueCount(data.issues, suite.id) }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val moment = formatRunMoment(newest.finishedAt ?: newest.createdAt, System.currentTimeMillis())
        HealthCard(
            "Last run",
            value = { AppText(newest.status.label(), color = runColor(newest.status), fontSize = CARD_VALUE_SIZE.sp, fontWeight = FontWeight.SemiBold) },
            detail = {
                AppText(listOfNotNull(data.ordinals[newest.runId]?.let { "#$it" }, moment).joinToString(" · "), color = tc.ts, fontSize = 11.sp)
            },
        )
        HealthCard(
            "Pass rate, last ${health.runCount} ${if (health.runCount == 1) "run" else "runs"}",
            value = { HealthCardValue(healthPassRateValue(health)) },
            detail = { AppText(healthPassRateDetail(health), color = tc.ts, fontSize = 11.sp) },
        )
        HealthCard("Median duration", value = { HealthCardValue(health.medianDurationMs?.let { formatDuration(it) } ?: DASH) })
        HealthCard(
            "Flaky cases",
            value = { HealthCardValue("${flakyNames.size}", color = if (flakyNames.isEmpty()) tc.tx else tc.warn) },
            detail = flakyNames.firstOrNull()?.let { first ->
                { AppText(first, color = tc.ts, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
        )
        HealthCard(
            "Open issues",
            value = { HealthCardValue("$openIssues") },
            detail = if (openIssues > 0) {
                {
                    TestsBreadcrumbLink(
                        "Review →",
                        onClick = {
                            ui.view.nav = TestsNav.Issues
                            ui.view.selectedIssueId = null
                        },
                        contentDescription = "Review open issues",
                    )
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun HealthCardValue(text: String, color: Color = tc().tx) {
    AppText(text, color = color, fontSize = CARD_VALUE_SIZE.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

/**
 * One summary card: a one-line [title], the [value] and a [detail] line. The detail line is always reserved (empty when there is
 * nothing to say) and the title never wraps, so every card in the row has the same height.
 */
@Composable
private fun HealthCard(title: String, value: @Composable () -> Unit, detail: (@Composable () -> Unit)? = null) {
    val tc = tc()
    Column(
        Modifier.widthIn(min = CARD_MIN_WIDTH).background(tc.p, CARD_SHAPE).border(1.dp, tc.br, CARD_SHAPE).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AppText(title, color = tc.td, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        value()
        Box(Modifier.height(CARD_DETAIL_HEIGHT), contentAlignment = Alignment.CenterStart) { detail?.invoke() }
    }
}

// ── Grid ─────────────────────────────────────────────────────────────

@Composable
private fun HealthGrid(suite: TestSuite, data: SuiteScreenData) {
    val tc = tc()
    val matrix = data.matrix
    BoxWithConstraints(Modifier.fillMaxWidth().border(1.dp, tc.br, CARD_SHAPE).clip(CARD_SHAPE)) {
        val cellsWidth = GRID_CELL_COLUMN * matrix.columns.size
        val fixed = GRID_PADDING * 2 + GRID_INDEX_WIDTH + cellsWidth + GRID_PASS_WIDTH + GRID_ACTION_WIDTH
        val caseWidth = (maxWidth - fixed).coerceAtLeast(GRID_CASE_MIN_WIDTH)
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(fixed + caseWidth)) {
                HealthGridHeader(matrix, data.ordinals, caseWidth)
                suite.cases.forEachIndexed { index, case ->
                    Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br.copy(alpha = ROW_DIVIDER_ALPHA)))
                    HealthGridRow(suite, index, case.id, case.name, data, caseWidth)
                }
            }
        }
    }
}

private const val ROW_DIVIDER_ALPHA = .6f

@Composable
private fun GridRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(GRID_ROW_HEIGHT).padding(horizontal = GRID_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun HealthGridHeader(matrix: CaseRunMatrix, ordinals: Map<String, Int>, caseWidth: Dp) {
    val tc = tc()
    val now = remember(matrix) { System.currentTimeMillis() }
    GridRow {
        HeaderCell("#", Modifier.width(GRID_INDEX_WIDTH))
        HeaderCell("Case", Modifier.width(caseWidth))
        matrix.columns.forEach { column ->
            val label = ordinals[column.runId]?.let { "#$it" } ?: DASH
            TooltipArea(
                tooltip = { ToolbarTooltip(healthColumnTooltip(column, now)) },
                modifier = Modifier.width(GRID_CELL_COLUMN),
            ) {
                Box(Modifier.width(GRID_CELL_COLUMN), contentAlignment = Alignment.Center) {
                    AppText(label, color = tc.td, fontSize = 9.sp, fontFamily = MONO, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        HeaderCell("Pass", Modifier.width(GRID_PASS_WIDTH), Alignment.CenterEnd)
        Spacer(Modifier.width(GRID_ACTION_WIDTH))
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier, alignment: Alignment = Alignment.CenterStart) {
    Box(modifier, contentAlignment = alignment) { AppText(text, color = tc().td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun HealthGridRow(suite: TestSuite, index: Int, caseId: String, caseName: String, data: SuiteScreenData, caseWidth: Dp) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val row = data.matrix.rows[caseId]
    val locked = limits.isCaseLocked(caseId)
    val runnable = !locked && !limits.isSuiteLocked(suite.id)
    val name = caseName.ifBlank { "Untitled case" }
    Box(Modifier.alpha(if (locked) DIMMED_ROW_ALPHA else 1f)) {
        GridRow {
            AppText("${index + 1}", color = tc.td, fontSize = 10.sp, fontFamily = MONO, modifier = Modifier.width(GRID_INDEX_WIDTH))
            Row(
                Modifier.width(caseWidth).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppText(
                    name, color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (locked) LockBadge(limits.hint)
                if (row?.flaky == true) FlakyBadge()
            }
            val cells = row?.cells.orEmpty()
            data.matrix.columns.forEachIndexed { columnIndex, column ->
                val cell = cells.getOrNull(columnIndex) ?: CaseRunCell.NOT_IN_RUN
                Box(Modifier.width(GRID_CELL_COLUMN), contentAlignment = Alignment.Center) {
                    GridSquare(cell, lastRunCellDescription(data.ordinals[column.runId], cell))
                }
            }
            Box(Modifier.width(GRID_PASS_WIDTH), contentAlignment = Alignment.CenterEnd) {
                AppText(row?.passRatePercent?.let { "$it%" } ?: DASH, color = tc.ts, fontSize = 11.sp, fontFamily = MONO)
            }
            Box(Modifier.width(GRID_ACTION_WIDTH), contentAlignment = Alignment.CenterEnd) {
                if (runnable) {
                    TestsBreadcrumbLink(
                        "▶ Run case",
                        onClick = { ui.view.runDialog = RunDialogTarget(suite.id, caseId) },
                        contentDescription = "Run case $name",
                    )
                }
            }
        }
    }
}

@Composable
private fun GridSquare(cell: CaseRunCell, description: String) {
    val tc = tc()
    val base = Modifier.size(GRID_SQUARE).clip(GRID_SQUARE_SHAPE).semantics { contentDescription = description }
    val fill = caseRunCellFill(cell)
    Box(if (fill != null) base.background(fill) else base.border(1.dp, tc.br.copy(alpha = CELL_BORDER_ALPHA), GRID_SQUARE_SHAPE))
}

@Composable
private fun FlakyBadge() {
    val tc = tc()
    Box(Modifier.background(tc.warn.copy(alpha = FLAKY_BADGE_ALPHA), CORNER_SM).padding(horizontal = 6.dp, vertical = 1.dp)) {
        AppText("Flaky", color = tc.warn, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HealthLegend() {
    val tc = tc()
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            "Passed" to CaseRunCell.PASSED, "Failed" to CaseRunCell.FAILED, "Blocked" to CaseRunCell.BLOCKED,
            "Other" to CaseRunCell.OTHER, "Not in run" to CaseRunCell.NOT_IN_RUN,
        ).forEach { (label, cell) -> LegendItem(label) { LegendSwatch(caseRunCellFill(cell)) } }
        LegendItem("read-only") { Icon(Icons.Outlined.Lock, contentDescription = null, tint = tc.warn, modifier = Modifier.size(LEGEND_SWATCH + 2.dp)) }
    }
}

@Composable
private fun LegendItem(label: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        swatch()
        AppText(label, color = tc().td, fontSize = 10.sp)
    }
}

@Composable
private fun LegendSwatch(fill: Color?) {
    val tc = tc()
    val base = Modifier.size(LEGEND_SWATCH).clip(GRID_SQUARE_SHAPE)
    Box(if (fill != null) base.background(fill) else base.border(1.dp, tc.br, GRID_SQUARE_SHAPE))
}

// ── Latest failure ───────────────────────────────────────────────────

@Composable
private fun LatestFailureCard(runId: String, ordinal: Int?) {
    val tc = tc()
    val ui = LocalTestsUi.current
    // Only this one run is read in full; the grid above comes from compact outcomes.
    val load by produceState<FailureLoad>(FailureLoad.Loading, runId) {
        value = FailureLoad.Loading
        val run = runCatching { ui.state.testRunCoordinator.loadRun(runId) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        val failure = run?.let { withContext(Dispatchers.Default) { latestFailureOf(it) } }
        value = if (failure != null) FailureLoad.Ready(failure) else FailureLoad.Unavailable
    }
    when (val state = load) {
        FailureLoad.Unavailable -> Unit
        FailureLoad.Loading -> AppText("Loading latest failure…", color = tc.td, fontSize = 11.sp)
        is FailureLoad.Ready -> {
            val failure = state.failure
            Column(
                Modifier.fillMaxWidth().background(tc.p, CARD_SHAPE).border(1.dp, tc.br, CARD_SHAPE).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppText("Latest failure", color = DANGER_RED, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                AppText(
                    latestFailureHeadline(ordinal, failure), color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (failure.detail.isNotEmpty()) AppText(failure.detail, color = tc.ts, fontSize = 11.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppButton(
                        "Open run log",
                        onClick = {
                            openSuiteRun(ui, runId)
                            ui.reclaimFocus()
                        },
                    )
                    val stepId = failure.stepId
                    val caseId = failure.caseId
                    val laneId = failure.laneId
                    if (stepId != null && caseId != null && laneId != null) {
                        AppButton(
                            "Draft issue…",
                            onClick = {
                                ui.view.issueDialog = IssueDialogTarget.FromStep(runId, laneId, caseId, failure.iteration, stepId)
                                ui.reclaimFocus()
                            },
                        )
                    }
                }
            }
        }
    }
}
