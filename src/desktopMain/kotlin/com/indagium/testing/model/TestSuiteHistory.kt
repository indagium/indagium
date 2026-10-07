package com.indagium.testing.model

/** Per-lane result for one case in the suite's latest terminal run. Null status means the case was absent. */
data class SuiteCaseLaneHistory(
    val lane: LaneConfig,
    val status: CaseStatus?,
    val durationMs: Long,
    val repeatCount: Int,
    val issueIds: List<String>,
    val unresolvedJudgeCount: Int,
)

/** The row-level result shown beside a current library case. */
data class SuiteCaseHistory(
    val caseId: String,
    val lanes: List<SuiteCaseLaneHistory>,
    val disagreement: Boolean,
) {
    val issueIds: List<String> get() = lanes.flatMap { it.issueIds }.distinct()
}

/** Latest terminal run, five most recent runs, and results derived only from the latest terminal run. */
data class TestSuiteHistory(
    val latestTerminalRun: TestRun?,
    val runs: List<RunSummary>,
    val recentRuns: List<TestRun>,
    val cases: Map<String, SuiteCaseHistory>,
)

data class TestRunMetrics(
    val passed: Int,
    val failed: Int,
    val blocked: Int,
    val errors: Int,
    val disagreements: Int,
    val unresolvedJudging: Int,
)

/** Shared metrics for report cards and the suite's latest-run summary. */
fun TestRun.metrics(): TestRunMetrics {
    val cases = lanes.flatMap { it.cases }.filter { it.caseId != SUITE_SETUP_CASE_ID && it.caseId != SUITE_TEARDOWN_CASE_ID }
    val statuses = cases.mapNotNull { it.status }
    val grouped = lanes.flatMap { lane ->
        lane.cases.filter { it.caseId != SUITE_SETUP_CASE_ID && it.caseId != SUITE_TEARDOWN_CASE_ID }.map { lane to it }
    }.groupBy { (_, result) -> result.caseId to result.iteration }
    val disagreements = grouped.values.count { group -> group.mapNotNull { it.second.status }.distinct().size > 1 }
    val steps = lanes.flatMap { it.cases }.flatMap { it.steps }
    val unresolved = steps.count { it.hasUnresolvedJudgeOutcome() }
    return TestRunMetrics(
        passed = statuses.count { it == CaseStatus.PASS },
        failed = statuses.count { it == CaseStatus.FAIL },
        blocked = statuses.count { it == CaseStatus.BLOCKED },
        errors = statuses.count { it == CaseStatus.ERROR },
        disagreements = disagreements,
        unresolvedJudging = unresolved,
    )
}

/** Counts only judge verdicts left unresolved; timeout may also mark deterministic checks NOT_EVALUATED. */
internal fun StepResult.hasUnresolvedJudgeOutcome(): Boolean =
    judgeInconclusive || checks.any { it.status == CheckStatus.NOT_EVALUATED && it.kind in EXPLICIT_JUDGE_CHECK_KINDS }

/**
 * Derives current-library case rows from a suite's latest terminal run. In particular, cases absent from a partial latest
 * run stay unrun; an older run is never used to fill those gaps.
 */
fun deriveTestSuiteHistory(
    suite: TestSuite,
    runs: List<TestRun>,
    recentLimit: Int = 5,
    allRunSummaries: List<RunSummary> = runs.map { it.summary() },
): TestSuiteHistory {
    val suiteRuns = runs.asSequence().filter { it.config.suiteId == suite.id }
        .sortedWith(compareByDescending<TestRun> { it.createdAt }.thenByDescending { it.finishedAt ?: 0L })
        .toList()
    val latest = suiteRuns.firstOrNull { it.isFinished }
    val byCase = suite.cases.associate { case ->
        val laneRows = latest?.lanes.orEmpty().map { lane ->
            val results = lane.cases.filter { it.caseId == case.id && it.status != null }.sortedBy { it.iteration }
            val steps = results.flatMap { it.steps }.filterNot { it.setup }
            SuiteCaseLaneHistory(
                lane = lane.config,
                status = results.mapNotNull { it.status }.reduceOrNull(::worseCaseStatus),
                durationMs = results.sumOf { it.elapsedDurationMs() },
                repeatCount = results.map { it.iteration }.distinct().size,
                issueIds = steps.mapNotNull { it.issueId }.distinct(),
                unresolvedJudgeCount = steps.count { step ->
                    step.judgeInconclusive || step.checks.any {
                        it.status == CheckStatus.NOT_EVALUATED && it.kind in EXPLICIT_JUDGE_CHECK_KINDS
                    }
                },
            )
        }
        // Compare lanes within each repeat iteration. Comparing the aggregate worst status loses real disagreement
        // when two lanes trade outcomes across repeats (for example PASS/FAIL versus FAIL/PASS).
        val disagreement: Boolean = latest?.lanes.orEmpty().flatMap { lane ->
            lane.cases.filter { it.caseId == case.id && it.status != null }
                .map { lane.laneId to it }
        }.groupBy { pair -> pair.second.iteration }
            .values.any { iterationRows -> iterationRows.mapNotNull { it.second.status }.distinct().size > 1 }
        case.id to SuiteCaseHistory(case.id, laneRows, disagreement)
    }
    val summaries = allRunSummaries.asSequence().filter { it.suiteId.isBlank() || it.suiteId == suite.id }.distinctBy { it.id }
        .toList()
        .sortedWith(compareByDescending<RunSummary> { it.createdAt }.thenByDescending { it.id })
    return TestSuiteHistory(latest, summaries, suiteRuns.take(recentLimit.coerceAtLeast(0)), byCase)
}

private fun CaseResult.elapsedDurationMs(): Long {
    val completedAt = finishedAt
    if (startedAt > 0 && completedAt != null && completedAt >= startedAt) return completedAt - startedAt
    return steps.filterNot { it.setup }.sumOf { it.durationMs.coerceAtLeast(0L) }
}

private fun worseCaseStatus(left: CaseStatus, right: CaseStatus): CaseStatus {
    fun rank(status: CaseStatus): Int = when (status) {
        CaseStatus.ERROR -> 6
        CaseStatus.FAIL -> 5
        CaseStatus.BLOCKED -> 4
        CaseStatus.CANCELLED -> 3
        CaseStatus.SKIPPED -> 2
        CaseStatus.PASS -> 1
    }
    return if (rank(left) >= rank(right)) left else right
}

internal val EXPLICIT_JUDGE_CHECK_KINDS = setOf("screenJudge", "askJudge", "screen_judge", "ask_judge")
