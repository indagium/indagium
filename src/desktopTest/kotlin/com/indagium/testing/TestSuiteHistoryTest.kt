package com.indagium.testing

import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.deriveTestSuiteHistory
import com.indagium.testing.model.metrics
import com.indagium.testing.run.mergeRunSnapshots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestSuiteHistoryTest {
    @Test
    fun partialCancelledLatestRunDoesNotBorrowOlderCaseResults() {
        val first = com.indagium.testing.model.TestCase("case-a", "A")
        val second = com.indagium.testing.model.TestCase("case-b", "B")
        val suite = TestSuite("suite-a", "Suite", cases = listOf(first, second))
        val lane = LaneConfig(kind = LaneKind.EXTERNAL, deviceSerial = "device")
        val oldRun = run(suite, "run-old", 10, RunStatus.PASSED, listOf(
            LaneResult(lane.id, lane, RunStatus.PASSED, listOf(
                CaseResult(first.id, first.name, status = CaseStatus.PASS),
                CaseResult(second.id, second.name, status = CaseStatus.PASS),
            )),
        ))
        val latest = run(suite, "run-latest", 20, RunStatus.CANCELLED, listOf(
            LaneResult(lane.id, lane, RunStatus.CANCELLED, listOf(CaseResult(first.id, first.name, status = CaseStatus.CANCELLED))),
        ))

        val history = deriveTestSuiteHistory(suite, listOf(oldRun, latest))

        assertEquals("run-latest", history.latestTerminalRun?.id)
        assertEquals(CaseStatus.CANCELLED, history.cases.getValue(first.id).lanes.single().status)
        assertNull(history.cases.getValue(second.id).lanes.single().status, "an absent case must read Not run")
        assertEquals(0, history.cases.getValue(second.id).lanes.single().repeatCount)
    }

    @Test
    fun repeatedResultsAreGroupedPerLaneWithDurationDisagreementAndIssues() {
        val case = com.indagium.testing.model.TestCase("case-a", "A")
        val suite = TestSuite("suite-a", "Suite", cases = listOf(case))
        val profileLane = LaneConfig(id = "agent", kind = LaneKind.AGENT_PROFILE, profileId = "profile-a", deviceSerial = "device-a")
        val externalLane = LaneConfig(id = "external", kind = LaneKind.EXTERNAL, deviceSerial = "device-b")
        val step = StepResult(
            stepId = "step-a", stepNumber = 1, action = "tap", status = StepStatus.BLOCKED,
            durationMs = 45, issueId = "issue-a", judgeInconclusive = true,
            checks = listOf(CheckResult("check-a", "screenJudge", CheckStatus.NOT_EVALUATED)),
        )
        val latest = run(suite, "run-latest", 20, RunStatus.FAILED, listOf(
            LaneResult(profileLane.id, profileLane, RunStatus.FAILED, listOf(
                CaseResult(
                    case.id,
                    case.name,
                    1,
                    CaseStatus.PASS,
                    listOf(step.copy(status = StepStatus.PASS, durationMs = 40, issueId = null, judgeInconclusive = false, checks = emptyList())),
                ),
                CaseResult(case.id, case.name, 2, CaseStatus.FAIL, listOf(step)),
            )),
            LaneResult(externalLane.id, externalLane, RunStatus.PASSED, listOf(
                CaseResult(
                    case.id,
                    case.name,
                    1,
                    CaseStatus.PASS,
                    listOf(step.copy(status = StepStatus.PASS, durationMs = 80, issueId = null, judgeInconclusive = false, checks = emptyList())),
                ),
                CaseResult(
                    case.id,
                    case.name,
                    2,
                    CaseStatus.PASS,
                    listOf(step.copy(status = StepStatus.PASS, durationMs = 60, issueId = null, judgeInconclusive = false, checks = emptyList())),
                ),
            )),
        ))

        val summary = deriveTestSuiteHistory(suite, listOf(latest)).cases.getValue(case.id)

        assertTrue(summary.disagreement)
        assertEquals(CaseStatus.FAIL, summary.lanes[0].status, "the profile row summarizes all repeat iterations")
        assertEquals(2, summary.lanes[0].repeatCount)
        assertEquals(85, summary.lanes[0].durationMs)
        assertEquals(listOf("issue-a"), summary.issueIds)
        assertEquals(1, summary.lanes[0].unresolvedJudgeCount)
        assertFalse(summary.lanes[1].issueIds.isNotEmpty())
        assertEquals(1, latest.metrics().disagreements)
        assertEquals(1, latest.metrics().unresolvedJudging)
    }

    @Test
    fun laneDurationUsesElapsedCaseTimeForEveryRepeatAndFallsBackForLegacyResults() {
        val case = com.indagium.testing.model.TestCase("case-a", "A")
        val suite = TestSuite("suite-a", "Suite", cases = listOf(case))
        val lane = LaneConfig(kind = LaneKind.EXTERNAL, deviceSerial = "device")
        val result = run(suite, "run-duration", 20, RunStatus.PASSED, listOf(
            LaneResult(lane.id, lane, RunStatus.PASSED, listOf(
                CaseResult(
                    case.id,
                    case.name,
                    iteration = 1,
                    status = CaseStatus.PASS,
                    steps = listOf(StepResult("step-a", 1, "tap", setup = true, status = StepStatus.PASS, durationMs = 400)),
                    startedAt = 1_000,
                    finishedAt = 6_000,
                ),
                CaseResult(
                    case.id,
                    case.name,
                    iteration = 2,
                    status = CaseStatus.PASS,
                    steps = listOf(StepResult("step-a", 1, "tap", status = StepStatus.PASS, durationMs = 30)),
                    startedAt = 8_000,
                    finishedAt = 15_000,
                ),
                CaseResult(
                    case.id,
                    case.name,
                    iteration = 3,
                    status = CaseStatus.PASS,
                    steps = listOf(StepResult("step-legacy", 1, "tap", status = StepStatus.PASS, durationMs = 25)),
                ),
                CaseResult(
                    case.id,
                    case.name,
                    iteration = 4,
                    status = CaseStatus.PASS,
                    steps = listOf(StepResult("step-zero", 1, "tap", status = StepStatus.PASS, durationMs = 99)),
                    startedAt = 20_000,
                    finishedAt = 20_000,
                ),
                CaseResult(
                    case.id,
                    case.name,
                    iteration = 5,
                    status = CaseStatus.PASS,
                    steps = listOf(StepResult("step-missing-start", 1, "tap", status = StepStatus.PASS, durationMs = 10)),
                    finishedAt = 30_000,
                ),
            )),
        ))

        val history = deriveTestSuiteHistory(suite, listOf(result))

        assertEquals(12_035, history.cases.getValue(case.id).lanes.single().durationMs)
    }

    @Test
    fun disagreementComparesEachIterationInsteadOfWorstAggregatePerLane() {
        val case = com.indagium.testing.model.TestCase("case-a", "A")
        val suite = TestSuite("suite-a", "Suite", cases = listOf(case))
        val laneA = LaneConfig(id = "a", kind = LaneKind.EXTERNAL, deviceSerial = "device-a")
        val laneB = LaneConfig(id = "b", kind = LaneKind.EXTERNAL, deviceSerial = "device-b")
        val latest = run(suite, "run-latest", 20, RunStatus.FAILED, listOf(
            LaneResult(laneA.id, laneA, RunStatus.FAILED, listOf(
                CaseResult(case.id, case.name, 1, CaseStatus.PASS),
                CaseResult(case.id, case.name, 2, CaseStatus.FAIL),
            )),
            LaneResult(laneB.id, laneB, RunStatus.FAILED, listOf(
                CaseResult(case.id, case.name, 1, CaseStatus.FAIL),
                CaseResult(case.id, case.name, 2, CaseStatus.PASS),
            )),
        ))

        assertTrue(deriveTestSuiteHistory(suite, listOf(latest)).cases.getValue(case.id).disagreement)
        assertEquals(2, latest.metrics().disagreements)
    }

    @Test
    fun liveSnapshotsWinWhenPersistedListingFinishesAfterThem() {
        val suite = TestSuite("suite-a", "Suite")
        val stored = run(suite, "run-a", 10, RunStatus.RUNNING, emptyList())
        val live = stored.copy(status = RunStatus.PASSED, finishedAt = 20)

        val merged = mergeRunSnapshots(listOf(live), listOf(stored))

        assertEquals(listOf(live), merged)
    }

    @Test
    fun runningRunIsNotTheLatestTerminalRun() {
        val suite = TestSuite("suite-a", "Suite")
        val old = run(suite, "run-old", 10, RunStatus.PASSED, emptyList())
        val active = run(suite, "run-active", 20, RunStatus.RUNNING, emptyList())
        val history = deriveTestSuiteHistory(suite, listOf(active, old))

        assertEquals("run-old", history.latestTerminalRun?.id)
        assertEquals("run-active", history.recentRuns.first().id)
    }

    private fun run(suite: TestSuite, id: String, createdAt: Long, status: RunStatus, lanes: List<LaneResult>) = TestRun(
        id = id,
        suite = suite,
        scripts = emptyList(),
        sharedSteps = emptyList(),
        config = RunConfig(suite.id, lanes = lanes.map { it.config }),
        lanes = lanes,
        status = status,
        createdAt = createdAt,
        finishedAt = createdAt.takeIf { status != RunStatus.RUNNING },
    )
}
