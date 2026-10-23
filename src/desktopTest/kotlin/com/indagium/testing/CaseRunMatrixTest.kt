package com.indagium.testing

import com.indagium.testing.model.CaseOutcome
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseRunCell
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunCaseOutcomes
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.SUITE_HEALTH_RUN_LIMIT
import com.indagium.testing.model.SUITE_SETUP_CASE_ID
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.caseOutcomes
import com.indagium.testing.model.deriveCaseRunMatrix
import com.indagium.testing.model.deriveSuiteHealth
import com.indagium.testing.model.toCaseRunCell
import com.indagium.testing.run.mergeFinishedOutcomes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("MagicNumber") // Synthetic ids, timestamps and percentages.
class CaseRunMatrixTest {
    private val caseA = caseOf("A")
    private val caseB = caseOf("B")
    private val suite: TestSuite = suiteOf(caseA, caseB)
    private val laneOne = LaneConfig("lane-1", LaneKind.EXTERNAL, deviceSerial = "d1")
    private val laneTwo = LaneConfig("lane-2", LaneKind.EXTERNAL, deviceSerial = "d2")

    private fun outcomes(
        id: String,
        createdAt: Long,
        status: RunStatus = RunStatus.PASSED,
        cases: Map<String, CaseOutcome> = mapOf(caseA.id to pass(), caseB.id to pass()),
        suiteId: String = suite.id,
        startedAt: Long? = createdAt,
        finishedAt: Long? = createdAt + 100,
    ) = RunCaseOutcomes(id, suiteId, status, createdAt, startedAt, finishedAt, cases)

    private fun pass(flaky: Boolean = false) = CaseOutcome(CaseStatus.PASS, 10L, flaky)

    private fun outcome(status: CaseStatus?) = CaseOutcome(status, null, false)

    private fun case(caseId: String, status: CaseStatus?, iteration: Int = 1, start: Long = 0L, end: Long? = null, flaky: Boolean = false) =
        CaseResult(caseId, caseId, iteration, status, startedAt = start, finishedAt = end, flaky = flaky)

    private fun run(vararg lanes: Pair<LaneConfig, List<CaseResult>>) = TestRun(
        id = "run-x", suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
        config = RunConfig(suite.id, null, lanes.map { it.first }),
        lanes = lanes.map { (config, cases) -> LaneResult(config.id, config, RunStatus.PASSED, cases) },
        status = RunStatus.PASSED, createdAt = 1_000L, startedAt = 1_100L, finishedAt = 2_100L,
    )

    // ── caseOutcomes ────────────────────────────────────────────────

    @Test
    fun caseOutcomesTakeTheWorstStatusAcrossLanesAndIterations() {
        val result = run(
            laneOne to listOf(
                case(caseA.id, CaseStatus.PASS),
                case(caseB.id, CaseStatus.PASS, iteration = 1),
                case(caseB.id, CaseStatus.FAIL, iteration = 2),
            ),
            laneTwo to listOf(case(caseA.id, CaseStatus.ERROR), case(caseB.id, CaseStatus.PASS)),
        ).caseOutcomes()

        assertEquals(CaseStatus.ERROR, result.cases.getValue(caseA.id).status)
        assertEquals(CaseStatus.FAIL, result.cases.getValue(caseB.id).status)
        assertEquals(RunStatus.PASSED, result.status)
        assertEquals(suite.id, result.suiteId)
    }

    @Test
    fun caseOutcomesLeaveOutSuiteHooksAndTakeTheSlowestLaneAsDuration() {
        val result = run(
            laneOne to listOf(
                case(SUITE_SETUP_CASE_ID, CaseStatus.PASS),
                case(caseA.id, CaseStatus.PASS, 1, 100L, 400L),
                case(caseA.id, CaseStatus.PASS, 2, 500L, 700L),
            ),
            laneTwo to listOf(case(caseA.id, CaseStatus.PASS, start = 100L, end = 300L, flaky = true), case(caseB.id, CaseStatus.PASS)),
        ).caseOutcomes()

        assertEquals(setOf(caseA.id, caseB.id), result.cases.keys)
        assertEquals(500L, result.cases.getValue(caseA.id).durationMs, "lane one ran two iterations: 300 + 200 beats lane two's 200")
        assertTrue(result.cases.getValue(caseA.id).flaky)
        assertNull(result.cases.getValue(caseB.id).durationMs, "no start/finish recorded")
        assertFalse(result.cases.getValue(caseB.id).flaky)
    }

    // ── cells ───────────────────────────────────────────────────────

    @Test
    fun statusesMapToCells() {
        assertEquals(CaseRunCell.PASSED, CaseStatus.PASS.toCaseRunCell())
        assertEquals(CaseRunCell.FAILED, CaseStatus.FAIL.toCaseRunCell())
        assertEquals(CaseRunCell.BLOCKED, CaseStatus.BLOCKED.toCaseRunCell())
        assertEquals(CaseRunCell.ERROR, CaseStatus.ERROR.toCaseRunCell())
        assertEquals(CaseRunCell.OTHER, CaseStatus.SKIPPED.toCaseRunCell())
        assertEquals(CaseRunCell.OTHER, CaseStatus.CANCELLED.toCaseRunCell())
        assertEquals(CaseRunCell.OTHER, (null as CaseStatus?).toCaseRunCell())
    }

    @Test
    fun aCaseAbsentFromARunIsNotInRunAndEverySuiteCaseHasARow() {
        val matrix = deriveCaseRunMatrix(
            suite,
            listOf(
                outcomes("r1", 1_000L, cases = mapOf(caseA.id to outcome(CaseStatus.FAIL))),
                outcomes("r2", 2_000L, cases = mapOf(caseA.id to outcome(CaseStatus.SKIPPED), caseB.id to outcome(CaseStatus.BLOCKED))),
            ),
        )

        assertEquals(listOf(caseA.id, caseB.id), matrix.rows.keys.toList())
        assertEquals(listOf(CaseRunCell.FAILED, CaseRunCell.OTHER), matrix.rows.getValue(caseA.id).cells)
        assertEquals(listOf(CaseRunCell.NOT_IN_RUN, CaseRunCell.BLOCKED), matrix.rows.getValue(caseB.id).cells)
    }

    @Test
    fun aSuiteWithNoRunsHasEmptyRowsAndNoPassRate() {
        val matrix = deriveCaseRunMatrix(suite, emptyList())

        assertTrue(matrix.columns.isEmpty())
        assertEquals(emptyList(), matrix.rows.getValue(caseA.id).cells)
        assertNull(matrix.rows.getValue(caseA.id).passRatePercent)
        assertFalse(matrix.rows.getValue(caseA.id).flaky)
        val health = deriveSuiteHealth(matrix)
        assertEquals(0, health.runCount)
        assertNull(health.passRatePercent)
        assertNull(health.medianDurationMs)
        assertNull(health.latestFailedRunId)
    }

    // ── window ──────────────────────────────────────────────────────

    @Test
    fun theWindowKeepsTheNewestTwelveFinishedRunsOfTheSuiteOldestFirst() {
        val all = (1..15).map { outcomes("r$it", it * 1_000L) } +
            outcomes("other", 99_000L, suiteId = "another-suite") +
            outcomes("running", 98_000L, status = RunStatus.RUNNING) +
            outcomes("queued", 97_000L, status = RunStatus.QUEUED)

        val matrix = deriveCaseRunMatrix(suite, all.shuffled(java.util.Random(7)))

        assertEquals(12, SUITE_HEALTH_RUN_LIMIT)
        assertEquals((4..15).map { "r$it" }, matrix.columns.map { it.runId })
        assertEquals(12, matrix.rows.getValue(caseA.id).cells.size)
        assertEquals(3, deriveCaseRunMatrix(suite, all, limit = 3).columns.size)
    }

    // ── rows ────────────────────────────────────────────────────────

    @Test
    fun passRateCountsOnlyRunsWhereTheCaseRan() {
        val matrix = deriveCaseRunMatrix(
            suite,
            listOf(
                outcomes("r1", 1_000L, cases = mapOf(caseA.id to outcome(CaseStatus.PASS))),
                outcomes("r2", 2_000L, cases = mapOf(caseA.id to outcome(CaseStatus.PASS))),
                outcomes("r3", 3_000L, cases = mapOf(caseA.id to outcome(CaseStatus.FAIL))),
                outcomes("r4", 4_000L, cases = mapOf(caseA.id to outcome(CaseStatus.SKIPPED))),
                outcomes("r5", 5_000L, cases = emptyMap()),
            ),
        )

        val row = matrix.rows.getValue(caseA.id)
        assertEquals(67, row.passRatePercent, "2 of the 3 runs where it ran")
        assertEquals(2, row.passed)
        assertEquals(1, row.failed)
        assertNull(matrix.rows.getValue(caseB.id).passRatePercent, "never ran")
    }

    @Test
    fun aCaseIsFlakyWhenItBothPassedAndFailedOrARunMarkedItFlaky() {
        val matrix = deriveCaseRunMatrix(
            suite,
            listOf(
                outcomes("r1", 1_000L, cases = mapOf(caseA.id to outcome(CaseStatus.PASS), caseB.id to outcome(CaseStatus.PASS))),
                outcomes("r2", 2_000L, cases = mapOf(caseA.id to outcome(CaseStatus.ERROR), caseB.id to pass(flaky = true))),
            ),
        )

        assertTrue(matrix.rows.getValue(caseA.id).flaky, "passed once, errored once")
        assertTrue(matrix.rows.getValue(caseB.id).flaky, "always passed but a run marked it flaky")
        val steady = deriveCaseRunMatrix(suite, listOf(outcomes("r1", 1_000L), outcomes("r2", 2_000L)))
        assertFalse(steady.rows.getValue(caseA.id).flaky)
        val alwaysFailing = deriveCaseRunMatrix(
            suite,
            listOf(1_000L, 2_000L).map { outcomes("r$it", it, cases = mapOf(caseA.id to outcome(CaseStatus.FAIL))) },
        )
        assertFalse(alwaysFailing.rows.getValue(caseA.id).flaky, "failing every time is broken, not flaky")
    }

    // ── health ──────────────────────────────────────────────────────

    @Test
    fun healthCountsPassedAndFailedRunsAndLeavesCancelledOutOfTheRate() {
        val matrix = deriveCaseRunMatrix(
            suite,
            listOf(
                outcomes("r1", 1_000L, status = RunStatus.PASSED),
                outcomes("r2", 2_000L, status = RunStatus.FAILED),
                outcomes("r3", 3_000L, status = RunStatus.CANCELLED),
                outcomes("r4", 4_000L, status = RunStatus.PASSED),
                outcomes("r5", 5_000L, status = RunStatus.ERROR),
                outcomes("r6", 6_000L, status = RunStatus.PASSED),
            ),
        )

        val health = deriveSuiteHealth(matrix)

        assertEquals(6, health.runCount)
        assertEquals(3, health.passedRuns)
        assertEquals(2, health.failedRuns)
        assertEquals(60, health.passRatePercent, "3 of 5: the cancelled run is not counted")
        assertEquals("r5", health.latestFailedRunId)
    }

    @Test
    fun healthMedianDurationUsesRunsThatRecordedBothEnds() {
        fun timed(id: String, created: Long, duration: Long?) = outcomes(
            id, created, startedAt = created, finishedAt = duration?.let { created + it },
        )
        val odd = deriveSuiteHealth(
            deriveCaseRunMatrix(suite, listOf(timed("a", 1_000L, 30L), timed("b", 2_000L, 10L), timed("c", 3_000L, 20L), timed("d", 4_000L, null))),
        )
        val even = deriveSuiteHealth(deriveCaseRunMatrix(suite, listOf(timed("a", 1_000L, 10L), timed("b", 2_000L, 40L))))

        assertEquals(20L, odd.medianDurationMs)
        assertEquals(25L, even.medianDurationMs)
        assertNull(deriveSuiteHealth(deriveCaseRunMatrix(suite, listOf(timed("a", 1_000L, null)))).medianDurationMs)
    }

    @Test
    fun healthMedianDurationLeavesCancelledRunsOut() {
        fun timed(id: String, created: Long, duration: Long, status: RunStatus) = outcomes(
            id, created, status = status, startedAt = created, finishedAt = created + duration,
        )
        val health = deriveSuiteHealth(
            deriveCaseRunMatrix(
                suite,
                listOf(
                    timed("a", 1_000L, 30L, RunStatus.PASSED),
                    timed("b", 2_000L, 1L, RunStatus.CANCELLED),
                    timed("c", 3_000L, 50L, RunStatus.FAILED),
                ),
            ),
        )

        assertEquals(40L, health.medianDurationMs, "a run stopped early does not pull the typical duration down")
    }

    @Test
    fun healthListsFlakyCasesInSuiteOrderAndNoFailedRunWhenNoneFailed() {
        val matrix = deriveCaseRunMatrix(
            suite,
            listOf(
                outcomes("r1", 1_000L, cases = mapOf(caseA.id to pass(), caseB.id to outcome(CaseStatus.FAIL))),
                outcomes("r2", 2_000L, cases = mapOf(caseA.id to pass(), caseB.id to pass())),
            ),
        )

        val health = deriveSuiteHealth(matrix)

        assertEquals(listOf(caseB.id), health.flakyCaseIds)
        assertNull(health.latestFailedRunId)
        assertEquals(100, health.passRatePercent)
    }

    @Test
    fun liveFinishedRunsWinOverStoredOutcomesAndTheMergeIsNewestFirstAndCapped() {
        val stored = (1..12).map { outcomes("r$it", it * 1_000L, status = RunStatus.FAILED) }
        val liveSameId = run(laneOne to listOf(case(caseA.id, CaseStatus.PASS))).copy(id = "r3", createdAt = 3_000L)
        val liveNewer = liveSameId.copy(id = "r13", createdAt = 13_000L)
        val liveRunning = liveSameId.copy(id = "r14", createdAt = 14_000L, status = RunStatus.RUNNING)

        val merged = mergeFinishedOutcomes(stored, listOf(liveSameId, liveNewer, liveRunning))

        assertEquals(12, merged.size)
        assertEquals("r13", merged.first().runId)
        assertEquals(listOf("r13") + (12 downTo 2).map { "r$it" }, merged.map { it.runId })
        assertEquals(RunStatus.PASSED, merged.first { it.runId == "r3" }.status, "the live copy replaced the stored one")
    }
}
