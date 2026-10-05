package com.indagium.testing

import com.indagium.ai.LlmRole
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestSuite
import com.indagium.testing.run.StartRunResult
import com.indagium.testing.run.comparisonTargets
import com.indagium.testing.store.TEST_RUN_JUDGE_FILE_NAME
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val AWAIT_MS = 15_000L
private const val LANE_A_SECRET = "LANE-A-SECRET-OBSERVATION"
private const val LANE_B_SECRET = "LANE-B-SECRET-OBSERVATION"

/** Lanes that ended a step differently get a comparison judge with per-lane, blind evidence; lanes that agree do not. */
class JudgeComparisonTest {
    private var harness: RunHarness? = null

    @AfterTest
    fun tearDown() {
        harness?.close()
    }

    private fun suite(): TestSuite = suiteOf(
        caseOf(
            "Login",
            step("Open the app", onFailure = OnFailure.CONTINUE),
            step("Look at the home screen"),
        ),
    )

    private fun run(
        suite: TestSuite,
        laneA: List<Pair<String, String>>,
        laneB: List<Pair<String, String>>,
        judge: ScriptedJudgeProvider,
    ): Pair<RunHarness, TestRun> {
        val farm = DeviceFarm()
        val h = RunHarness(
            libraryOf(suite),
            profile = profileOf(LANE_A_PROFILE_ID),
            extraProfiles = listOf(profileOf(LANE_B_PROFILE_ID), judgeProfile),
            agentFactory = agentsByProfile(
                mapOf(LANE_A_PROFILE_ID to AutoAgentProvider(laneA), LANE_B_PROFILE_ID to AutoAgentProvider(laneB), JUDGE_PROFILE_ID to judge),
            ),
            openDevice = farm.opener,
        ).also { harness = it }
        val config = h.config(suite, agentLane(LANE_A_PROFILE_ID, "SER-A"), agentLane(LANE_B_PROFILE_ID, "SER-B"))
            .copy(judgeProfileId = JUDGE_PROFILE_ID, judgeMode = JudgeMode.EVERY_STEP.wire)
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        return h to runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }
    }

    private fun comparisonJudge(fix: kotlinx.serialization.json.JsonObject? = null) = ScriptedJudgeProvider { ask ->
        if (ask.comparison) {
            JudgeAnswer(
                classification = "agent_or_step_problem", reasoning = "Lane 2 took another path.", fix = fix,
                laneVerdicts = mapOf("Lane 1" to "pass", "Lane 2" to "fail"),
            )
        } else {
            JudgeAnswer(verdict = "pass")
        }
    }

    @Test
    fun aStepTheLanesDisagreedOnGetsAComparisonWithEveryLanesEvidenceAndNoAgentWords() {
        val suite = suite()
        val fix = buildJsonObject { put("action", "Open the app from the launcher") }
        val judge = comparisonJudge(fix)
        val (h, run) = run(
            suite,
            laneA = listOf("pass" to LANE_A_SECRET, "pass" to "ok"),
            laneB = listOf("fail" to LANE_B_SECRET, "pass" to "ok"),
            judge = judge,
        )

        assertEquals(RunStatus.FAILED, run.status, run.toString())
        val comparison = run.comparisons.single()
        val step = suite.cases.single().steps.first()
        assertEquals(step.id, comparison.stepId)
        assertEquals(1, comparison.stepNumber)
        assertEquals(mapOf(run.lanes[0].laneId to JudgeVerdict.PASS, run.lanes[1].laneId to JudgeVerdict.FAIL), comparison.verdicts)
        assertEquals(JudgeClassification.AGENT_OR_STEP_PROBLEM, comparison.classification)
        assertEquals("Lane 2 took another path.", comparison.explanation)
        assertEquals("Open the app from the launcher", comparison.suggestedFix?.action)
        assertFalse(comparison.fixApplied)

        val comparisonRequests = judge.requests.filter { r -> r.messages.first { it.role == LlmRole.USER }.content.orEmpty().startsWith("Compare") }
        assertTrue(comparisonRequests.isNotEmpty(), "a comparison judge ran")
        val brief = comparisonRequests.flatMap { it.messages }.first { it.role == LlmRole.TOOL }.content.orEmpty()
        assertTrue(brief.contains("Lane 1") && brief.contains("Lane 2"), "the brief holds evidence of both lanes: $brief")
        assertTrue(
            comparisonRequests.any { r -> r.messages.any { it.images.isNotEmpty() } },
            "the judge looked at a lane's screenshot, loaded from the run folder",
        )
        val everything = judge.everythingSeen()
        assertFalse(everything.contains(LANE_A_SECRET) || everything.contains(LANE_B_SECRET), "no lane's observation reaches any judge:\n$everything")
        // Both lanes were judged one by one too, and the second step (they agreed on) has no comparison.
        assertEquals(1, run.comparisons.size)
        assertEquals(RunStatus.FAILED, h.store.load(run.id)!!.status)
        assertEquals(run.comparisons, h.store.load(run.id)!!.comparisons, "the comparison is stored in run.json")
        val transcript = File(h.store.runDir(run.id), TEST_RUN_JUDGE_FILE_NAME).readText()
        assertTrue(transcript.contains("compare:") && transcript.contains("submit_comparison"), transcript)
        assertFalse(transcript.contains(LANE_A_SECRET) || transcript.contains(LANE_B_SECRET))
    }

    @Test
    fun lanesThatAgreeGetNoComparison() {
        val judge = comparisonJudge()
        val (_, run) = run(suite(), laneA = listOf("pass" to "ok", "pass" to "ok"), laneB = listOf("pass" to "ok", "pass" to "ok"), judge = judge)

        assertEquals(RunStatus.PASSED, run.status, run.toString())
        assertTrue(run.comparisons.isEmpty())
        assertTrue(
            judge.requests.none { r -> r.messages.first { it.role == LlmRole.USER }.content.orEmpty().startsWith("Compare") },
            "no comparison judge was started",
        )
        assertEquals(CaseStatus.PASS, run.lanes[0].cases.single().status)
    }

    // ── Who disagrees (pure) ────────────────────────────────────────

    private fun result(stepId: String, number: Int, status: StepStatus, verdict: JudgeVerdict? = null) =
        StepResult(stepId, number, "action $number", status = status, judge = verdict?.let { StepJudgement(verdict = it) })

    private fun twoLaneRun(suite: TestSuite, first: List<StepResult>, second: List<StepResult>): TestRun {
        val case = suite.cases.single()
        val laneA = agentLane(LANE_A_PROFILE_ID, "SER-A")
        val laneB = agentLane(LANE_B_PROFILE_ID, "SER-B")
        return TestRun(
            id = "run-1", suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
            config = com.indagium.testing.model.RunConfig(suite.id, null, listOf(laneA, laneB)),
            lanes = listOf(
                LaneResult(laneA.id, laneA, RunStatus.PASSED, listOf(CaseResult(case.id, case.name, 1, CaseStatus.PASS, first))),
                LaneResult(laneB.id, laneB, RunStatus.PASSED, listOf(CaseResult(case.id, case.name, 1, CaseStatus.PASS, second))),
            ),
        )
    }

    @Test
    fun aDifferentStatusOrJudgeVerdictIsADisagreementAndSkippedOrErroredLanesDoNotCount() {
        val suite = suite()
        val (one, two) = suite.cases.single().steps

        val statusDiffers = twoLaneRun(
            suite,
            listOf(result(one.id, 1, StepStatus.PASS), result(two.id, 2, StepStatus.PASS)),
            listOf(result(one.id, 1, StepStatus.FAIL), result(two.id, 2, StepStatus.PASS)),
        )
        assertEquals(listOf(one.id), comparisonTargets(statusDiffers).map { it.step.id })
        assertEquals(listOf(1, 2), comparisonTargets(statusDiffers).single().results.map { it.laneNumber })

        val verdictDiffers = twoLaneRun(
            suite,
            listOf(result(one.id, 1, StepStatus.PASS, JudgeVerdict.PASS)),
            listOf(result(one.id, 1, StepStatus.PASS, JudgeVerdict.INCONCLUSIVE)),
        )
        assertEquals(listOf(one.id), comparisonTargets(verdictDiffers).map { it.step.id })

        val same = twoLaneRun(
            suite,
            listOf(result(one.id, 1, StepStatus.PASS, JudgeVerdict.PASS)),
            listOf(result(one.id, 1, StepStatus.PASS, JudgeVerdict.PASS)),
        )
        assertTrue(comparisonTargets(same).isEmpty())

        val oneSkipped = twoLaneRun(
            suite,
            listOf(result(one.id, 1, StepStatus.PASS)),
            listOf(result(one.id, 1, StepStatus.SKIPPED)),
        )
        assertTrue(comparisonTargets(oneSkipped).isEmpty(), "a skipped step has no evidence")
        val oneErrored = twoLaneRun(
            suite,
            listOf(result(one.id, 1, StepStatus.PASS)),
            listOf(result(one.id, 1, StepStatus.ERROR)),
        )
        assertTrue(comparisonTargets(oneErrored).isEmpty(), "an infrastructure error has no evidence")

        val single = statusDiffers.copy(lanes = statusDiffers.lanes.take(1))
        assertTrue(comparisonTargets(single).isEmpty(), "one lane has nothing to compare with")
    }
}
