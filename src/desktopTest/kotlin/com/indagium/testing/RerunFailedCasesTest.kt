package com.indagium.testing

import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.run.rerunFailedCasesConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RerunFailedCasesTest {
    @Test
    fun selectsUnionAcrossLanesAndPreservesOriginalExecutionConfiguration() {
        val suite = suiteOf(caseOf("Passed", step("Pass")), caseOf("Blocked", step("Block")), caseOf("Error", step("Error")))
        val passed = suite.cases[0]
        val blocked = suite.cases[1]
        val errored = suite.cases[2]
        val agent = LaneConfig("lane-agent", LaneKind.AGENT_PROFILE, "profile-a", "device-a")
        val external = LaneConfig("lane-external", LaneKind.EXTERNAL, null, "device-b")
        val config = RunConfig(
            suite.id, null, listOf(agent, external), repeat = 3, caseToolCallLimit = 123,
            evidence = EvidenceFlags(video = true, screenshots = false, logcat = true, transcript = false),
            judgeProfileId = "judge", judgeMode = "every_step", stopAfterStepId = "old-stop",
        )
        val run = TestRun(
            id = "run-source", suite = suite, scripts = emptyList(), sharedSteps = emptyList(), config = config,
            lanes = listOf(
                LaneResult(agent.id, agent, RunStatus.FAILED, listOf(
                    CaseResult(passed.id, passed.name, 1, CaseStatus.PASS),
                    CaseResult(blocked.id, blocked.name, 1, CaseStatus.BLOCKED),
                )),
                LaneResult(external.id, external, RunStatus.ERROR, listOf(
                    CaseResult(errored.id, errored.name, 1, null, steps = listOf(StepResult(errored.steps.single().id, 1, "Error", status = StepStatus.ERROR))),
                    CaseResult(blocked.id, blocked.name, 2, CaseStatus.FAIL),
                )),
            ), status = RunStatus.ERROR,
        )

        val rerun = rerunFailedCasesConfig(run).getOrThrow()

        assertEquals(listOf(blocked.id, errored.id), rerun.caseIds)
        assertEquals(listOf(LaneKind.AGENT_PROFILE, LaneKind.EXTERNAL), rerun.lanes.map { it.kind })
        assertEquals(listOf("device-a", "device-b"), rerun.lanes.map { it.deviceSerial })
        assertEquals(listOf("profile-a", null), rerun.lanes.map { it.profileId })
        assertNotEquals(config.lanes.map { it.id }, rerun.lanes.map { it.id })
        assertEquals(3, rerun.repeat)
        assertEquals(123, rerun.caseToolCallLimit)
        assertEquals(config.evidence, rerun.evidence)
        assertEquals("judge", rerun.judgeProfileId)
        assertEquals("every_step", rerun.judgeMode)
        assertEquals("run-source", rerun.rerunOf)
        assertEquals(null, rerun.stopAfterStepId)
    }
}
