package com.indagium.testing.model

// Per-case results across a suite's recent runs: the compact shape the Tests tab's "last runs" dots and Health grid read.
// UI-free. A run's heavy data (steps, evidence) is dropped as early as possible: [RunCaseOutcomes] keeps one small entry per
// case so a store scan can retain a dozen of them without holding extra full runs (a run file can be up to 64 MB).

/** How many of a suite's newest finished runs the case matrix and the health summary look at. */
const val SUITE_HEALTH_RUN_LIMIT = 12

private const val PERCENT = 100
private const val HALF = 2

/**
 * One case in one run: the worst [status] across lanes and iterations (null when no result carried a status), the case's [durationMs]
 * (the slowest lane; null when no start/finish was recorded) and whether any lane marked it [flaky].
 */
data class CaseOutcome(val status: CaseStatus?, val durationMs: Long?, val flaky: Boolean)

/** A run reduced to what the case matrix needs. [cases] is keyed by case id; the suite-level setup/teardown pseudo cases are left out. */
data class RunCaseOutcomes(
    val runId: String,
    val suiteId: String,
    val status: RunStatus,
    val createdAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val cases: Map<String, CaseOutcome>,
) {
    val isFinished: Boolean
        get() = status == RunStatus.PASSED || status == RunStatus.FAILED || status == RunStatus.CANCELLED || status == RunStatus.ERROR

    /** Wall-clock length of the run, or null when it did not record both ends. */
    val durationMs: Long?
        get() {
            val start = startedAt
            val end = finishedAt
            return if (start != null && end != null && end >= start) end - start else null
        }
}

/** The compact outcomes of this run: per case the worst status over every lane and iteration. Suite setup/teardown hooks are not cases. */
fun TestRun.caseOutcomes(): RunCaseOutcomes {
    val results = lanes.flatMap { lane -> lane.cases.map { lane.laneId to it } }
        .filter { (_, result) -> result.caseId != SUITE_SETUP_CASE_ID && result.caseId != SUITE_TEARDOWN_CASE_ID }
    val byCase = LinkedHashMap<String, CaseOutcome>()
    for ((caseId, group) in results.groupBy { (_, result) -> result.caseId }) {
        val status = group.mapNotNull { (_, result) -> result.status }.reduceOrNull(::worseCaseStatus)
        // Iterations of one lane run one after another, so they add up; lanes run side by side, so the slowest one is the case's time.
        val laneDurations = group.groupBy { (laneId, _) -> laneId }.values.mapNotNull { laneGroup ->
            val timed = laneGroup.map { (_, result) -> result }.filter { it.hasRecordedSpan() }
            if (timed.isEmpty()) null else timed.sumOf { (it.finishedAt ?: it.startedAt) - it.startedAt }
        }
        byCase[caseId] = CaseOutcome(status, laneDurations.maxOrNull(), group.any { (_, result) -> result.flaky })
    }
    return RunCaseOutcomes(id, config.suiteId, status, createdAt, startedAt, finishedAt, byCase)
}

private fun CaseResult.hasRecordedSpan(): Boolean {
    val completedAt = finishedAt
    return startedAt > 0 && completedAt != null && completedAt >= startedAt
}

/** One square of the matrix: how a case ended in one run, or that the run did not include it. */
enum class CaseRunCell {
    PASSED,
    FAILED,
    BLOCKED,
    ERROR,
    NOT_IN_RUN,

    /** The case was in the run but ended skipped, cancelled or without a verdict. */
    OTHER,
    ;

    /** The case ran and reached a verdict about the app (as opposed to being absent, skipped or cancelled). */
    val ran: Boolean get() = this == PASSED || this == FAILED || this == BLOCKED || this == ERROR
}

/** [CaseStatus.SKIPPED], [CaseStatus.CANCELLED] and a missing status are [CaseRunCell.OTHER]. */
fun CaseStatus?.toCaseRunCell(): CaseRunCell = when (this) {
    CaseStatus.PASS -> CaseRunCell.PASSED
    CaseStatus.FAIL -> CaseRunCell.FAILED
    CaseStatus.BLOCKED -> CaseRunCell.BLOCKED
    CaseStatus.ERROR -> CaseRunCell.ERROR
    CaseStatus.SKIPPED, CaseStatus.CANCELLED, null -> CaseRunCell.OTHER
}

/**
 * One case across the matrix's runs. [cells] runs oldest to newest, one per column. [passed] counts PASSED cells and [failed] counts
 * FAILED and ERROR cells; [passRatePercent] is passed over every cell where the case ran (passed, failed, blocked, error), rounded,
 * or null when it never ran. [flaky]: it both passed and failed/errored inside the window, or a run marked it flaky.
 */
data class CaseRunRow(val cells: List<CaseRunCell>, val passed: Int, val failed: Int, val passRatePercent: Int?, val flaky: Boolean)

/** The suite's cases against its newest finished runs: [columns] oldest to newest, [rows] by case id in the suite's case order. */
data class CaseRunMatrix(val columns: List<RunCaseOutcomes>, val rows: Map<String, CaseRunRow>)

/**
 * Builds the matrix from [outcomes] (any mix of suites and states): only finished runs of [suite] count, the newest [limit] of them
 * (by creation time, then id), shown oldest to newest. Every case of [suite] has a row; a case a run did not include is
 * [CaseRunCell.NOT_IN_RUN] there. Duplicate run ids collapse to the first one given.
 */
fun deriveCaseRunMatrix(suite: TestSuite, outcomes: List<RunCaseOutcomes>, limit: Int = SUITE_HEALTH_RUN_LIMIT): CaseRunMatrix {
    val newestFirst = outcomes.asSequence()
        .filter { it.suiteId == suite.id && it.isFinished }
        .distinctBy { it.runId }
        .sortedWith(compareByDescending<RunCaseOutcomes> { it.createdAt }.thenByDescending { it.runId })
        .take(limit.coerceAtLeast(0))
        .toList()
    val columns = newestFirst.asReversed()
    val rows = LinkedHashMap<String, CaseRunRow>()
    for (case in suite.cases) {
        val outcomesInOrder = columns.map { it.cases[case.id] }
        val cells = outcomesInOrder.map { if (it == null) CaseRunCell.NOT_IN_RUN else it.status.toCaseRunCell() }
        rows[case.id] = caseRunRow(cells, outcomesInOrder.any { it?.flaky == true })
    }
    return CaseRunMatrix(columns, rows)
}

private fun caseRunRow(cells: List<CaseRunCell>, markedFlaky: Boolean): CaseRunRow {
    val passed = cells.count { it == CaseRunCell.PASSED }
    val failed = cells.count { it == CaseRunCell.FAILED || it == CaseRunCell.ERROR }
    val ran = cells.count { it.ran }
    val rate = if (ran == 0) null else Math.round(passed * PERCENT.toDouble() / ran).toInt()
    return CaseRunRow(cells, passed, failed, rate, markedFlaky || (passed > 0 && failed > 0))
}

/**
 * The Health tab's headline numbers over the matrix's runs. A run passed when its status is PASSED and failed when it is FAILED or
 * ERROR; cancelled runs count in [runCount] but not in [passRatePercent] or [medianDurationMs] (a run stopped early would drag the
 * typical duration down). [medianDurationMs] is over the remaining runs that recorded both ends.
 * [latestFailedRunId] is the newest failed or errored run, or null.
 */
data class SuiteHealthSummary(
    val runCount: Int,
    val passedRuns: Int,
    val failedRuns: Int,
    val passRatePercent: Int?,
    val medianDurationMs: Long?,
    val flakyCaseIds: List<String>,
    val latestFailedRunId: String?,
)

fun deriveSuiteHealth(matrix: CaseRunMatrix): SuiteHealthSummary {
    val passedRuns = matrix.columns.count { it.status == RunStatus.PASSED }
    val failedRuns = matrix.columns.count { it.status == RunStatus.FAILED || it.status == RunStatus.ERROR }
    val judged = passedRuns + failedRuns
    return SuiteHealthSummary(
        runCount = matrix.columns.size,
        passedRuns = passedRuns,
        failedRuns = failedRuns,
        passRatePercent = if (judged == 0) null else Math.round(passedRuns * PERCENT.toDouble() / judged).toInt(),
        medianDurationMs = median(matrix.columns.filter { it.status != RunStatus.CANCELLED }.mapNotNull { it.durationMs }),
        flakyCaseIds = matrix.rows.filterValues { it.flaky }.keys.toList(),
        latestFailedRunId = matrix.columns.lastOrNull { it.status == RunStatus.FAILED || it.status == RunStatus.ERROR }?.runId,
    )
}

private fun median(values: List<Long>): Long? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val middle = sorted.size / HALF
    return if (sorted.size % HALF == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / HALF
}
