package com.indagium.testing

import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.summary
import com.indagium.testing.run.ComparedCasePresence
import com.indagium.testing.run.ComparedStepPresence
import com.indagium.testing.run.compareTestRuns
import com.indagium.testing.run.previousTerminalRunOfSameSuite
import com.indagium.testing.run.previousTerminalRunSummaryOfSameSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestRunComparisonTest {
    @Test
    fun matchesStableIdsAndLabelsStatusDefinitionAndAddedRemovedSteps() {
        val base = suiteOf(caseOf("Checkout", step("Open"), step("Remove me"))).copy(id = "suite-same")
        val caseId = base.cases.single().id
        val open = base.cases.single().steps[0].copy(id = "step-open")
        val removed = base.cases.single().steps[1].copy(id = "step-removed")
        val newer = base.copy(
            cases = listOf(
                base.cases.single().copy(
                    steps = listOf(open.copy(action = "Open revised", expected = "New page"), step("New row").copy(id = "step-added")),
                ),
            ),
        )
        val oldRun = run(
            "run-old",
            base.copy(cases = listOf(base.cases.single().copy(steps = listOf(open, removed)))),
            caseId,
            mapOf("step-open" to StepStatus.FAIL),
        )
        val newRun = run("run-new", newer, caseId, mapOf("step-open" to StepStatus.PASS, "step-added" to StepStatus.PASS))

        val rows = compareTestRuns(oldRun, newRun).associateBy { it.stepId }
        assertEquals(3, rows.size)
        assertTrue(rows.getValue("step-open").definitionChanged)
        assertEquals("Open", rows.getValue("step-open").previousAction)
        assertEquals("Open revised", rows.getValue("step-open").currentAction)
        assertTrue(rows.getValue("step-open").previousStatuses.endsWith("FAIL"))
        assertTrue(rows.getValue("step-open").currentStatuses.endsWith("PASS"))
        assertEquals(ComparedStepPresence.REMOVED, rows.getValue("step-removed").presence)
        assertEquals(ComparedStepPresence.ADDED, rows.getValue("step-added").presence)
    }

    @Test
    fun reportsSuiteInstructionsAndResolvedSharedHookDefinitionChanges() {
        val baseCase = caseOf("Checkout", step("Open")).copy(id = "case-stable")
        val oldSuite = suiteOf(baseCase).copy(
            id = "suite-same",
            instructions = "Use the seeded account",
            setup = listOf(HookItem.Shared("hook", "shared-stable")),
        )
        val newSuite = oldSuite.copy(instructions = "Use the refreshed account")
        val oldShared = SharedStep("shared-stable", "Seed account", steps = listOf(step("Create the account")))
        val newShared = oldShared.copy(steps = listOf(oldShared.steps.single().copy(action = "Refresh the account")))
        val oldRun = run("old", oldSuite, baseCase.id, emptyMap()).copy(sharedSteps = listOf(oldShared))
        val newRun = run("new", newSuite, baseCase.id, emptyMap()).copy(sharedSteps = listOf(newShared))

        val suiteRow = compareTestRuns(oldRun, newRun).single()
        assertTrue(suiteRow.suiteDefinitionChanged)
        assertTrue(suiteRow.previousAction.orEmpty().contains("Use the seeded account"))
        assertTrue(suiteRow.previousAction.orEmpty().contains("Create the account"))
        assertTrue(suiteRow.currentAction.orEmpty().contains("Refresh the account"))
    }

    @Test
    fun laneStatusComparisonRetainsProfileIdentityWhenResultsSwapOnOneDevice() {
        val testCase = caseOf("Checkout", step("Open")).copy(id = "case-stable")
        val suite = suiteOf(testCase).copy(id = "suite-stable")
        val step = testCase.steps.single().copy(id = "step-stable")
        val stableCase = testCase.copy(steps = listOf(step))

        fun lane(id: String, profile: String, status: StepStatus): LaneResult {
            val config = LaneConfig(id, LaneKind.AGENT_PROFILE, profile, "SERIAL-1")
            val stepResult = StepResult(step.id, 1, step.action, status = status)
            val caseResult = CaseResult(
                stableCase.id,
                stableCase.name,
                status = if (status == StepStatus.PASS) CaseStatus.PASS else CaseStatus.FAIL,
                steps = listOf(stepResult),
            )
            return LaneResult(id, config, if (status == StepStatus.PASS) RunStatus.PASSED else RunStatus.FAILED, listOf(caseResult))
        }

        fun run(id: String, a: StepStatus, b: StepStatus): TestRun {
            val laneA = lane("lane-a-$id", "profile-a", a)
            val laneB = lane("lane-b-$id", "profile-b", b)
            return TestRun(
                id, suite.copy(cases = listOf(stableCase)), emptyList(), emptyList(),
                RunConfig(suite.id, listOf(stableCase.id), listOf(laneA.config, laneB.config)),
                listOf(laneA, laneB), RunStatus.FAILED,
            )
        }
        val comparison = compareTestRuns(run("old", StepStatus.PASS, StepStatus.FAIL), run("new", StepStatus.FAIL, StepStatus.PASS))
            .single { it.stepId == step.id }
        assertTrue(comparison.previousStatuses != comparison.currentStatuses)
        assertTrue(comparison.previousStatuses.contains("profile-a/SERIAL-1/i1:PASS"))
        assertTrue(comparison.currentStatuses.contains("profile-a/SERIAL-1/i1:FAIL"))
    }

    @Test
    fun emptyCasesAddedOrRemovedAreStillReported() {
        val stable = caseOf("Stable", step("Open")).copy(id = "case-stable")
        val removed = TestCase("case-removed", "Removed empty case")
        val added = TestCase("case-added", "Added empty case")
        val previousSuite = suiteOf(stable, removed).copy(id = "suite-cases")
        val currentSuite = suiteOf(stable, added).copy(id = "suite-cases")
        val previous = run("old", previousSuite, stable.id, emptyMap())
        val current = run("new", currentSuite, stable.id, emptyMap())
        val rows = compareTestRuns(previous, current)
        assertEquals(ComparedCasePresence.REMOVED, rows.single { it.caseId == removed.id }.casePresence)
        assertEquals(ComparedCasePresence.ADDED, rows.single { it.caseId == added.id }.casePresence)
    }

    @Test
    fun previousComparisonRunUsesSuiteIdentityTerminalStatusAndTimeNotName() {
        val currentCase = caseOf("Checkout", step("Open")).copy(id = "case-stable")
        val suite = suiteOf(currentCase).copy(id = "suite-stable", name = "Current name")
        val current = run("current", suite, currentCase.id, emptyMap()).copy(createdAt = 300)
        val renamedPrevious = run("previous", suite.copy(name = "Earlier name"), currentCase.id, emptyMap()).copy(createdAt = 250)
        val older = run("older", suite, currentCase.id, emptyMap()).copy(createdAt = 200)
        val otherSuite = run("other-suite", suite.copy(id = "other-id"), currentCase.id, emptyMap()).copy(createdAt = 290)
        val newer = run("newer", suite, currentCase.id, emptyMap()).copy(createdAt = 400)
        val running = run("running", suite, currentCase.id, emptyMap()).copy(createdAt = 295, status = RunStatus.RUNNING)

        assertEquals(renamedPrevious.id, previousTerminalRunOfSameSuite(listOf(older, renamedPrevious, otherSuite, newer, running), current)?.id)
    }

    @Test
    fun summaryHistorySelectsPriorTerminalForSameStableSuiteWhenViewingAnOlderRun() {
        val case = caseOf("Case", step("Step")).copy(id = "case-stable")
        val suite = suiteOf(case).copy(id = "stable-suite")
        val current = run("current", suite, case.id, emptyMap()).copy(createdAt = 300, finishedAt = 300)
        val previous = run("previous", suite.copy(name = "Renamed"), case.id, emptyMap()).copy(createdAt = 200, finishedAt = 200)
        val newer = run("newer", suite, case.id, emptyMap()).copy(createdAt = 400, finishedAt = 400)
        val unrelated = run("unrelated", suite.copy(id = "other-suite"), case.id, emptyMap()).copy(createdAt = 250, finishedAt = 250)

        val selected = previousTerminalRunSummaryOfSameSuite(
            listOf(newer.summary(), unrelated.summary(), previous.summary(), current.summary()),
            current,
        )

        assertEquals(previous.id, selected?.id)
    }

    @Test
    fun caseContextResolvedCaseHooksAndScriptResultDefinitionsAreCompared() {
        val originalStep = step("Run check").copy(id = "step-check", checks = listOf(StepCheck.ScriptResult("script-check", "script-stable")))
        val originalCase = caseOf("Checkout", originalStep).copy(
            id = "case-stable",
            description = "Confirm the seeded account",
            allowedTools = setOf("tap"),
            setup = listOf(HookItem.Shared("case-hook", "shared-case")),
        )
        val suite = suiteOf(originalCase).copy(id = "suite-case-hook")
        val originalShared = SharedStep("shared-case", "Seed account", steps = listOf(step("Create account")))
        val changedShared = originalShared.copy(steps = listOf(originalShared.steps.single().copy(action = "Refresh account")))
        val script = TestScript("script-stable", "verify_account", commandTemplate = "echo ready")
        val changedScript = script.copy(commandTemplate = "echo refreshed")
        val before = run("before", suite, originalCase.id, emptyMap()).copy(sharedSteps = listOf(originalShared), scripts = listOf(script))
        val updatedCase = originalCase.copy(description = "Confirm the refreshed account", allowedTools = setOf("tap", "swipe"))
        val after = run("after", suite.copy(cases = listOf(updatedCase)), originalCase.id, emptyMap())
            .copy(sharedSteps = listOf(changedShared), scripts = listOf(changedScript))

        val rows = compareTestRuns(before, after)
        val caseContext = rows.single { it.caseId == originalCase.id && it.stepId.isBlank() }
        assertTrue(caseContext.caseDefinitionChanged)
        assertTrue(caseContext.previousAction.orEmpty().contains("seeded account"))
        assertTrue(caseContext.previousAction.orEmpty().contains("Create account"))
        assertTrue(caseContext.currentAction.orEmpty().contains("Refresh account"))
        val checkStep = rows.single { it.caseId == originalCase.id && it.stepId == originalStep.id }
        assertTrue(checkStep.definitionChanged, "a changed frozen script referenced by ScriptResult changes step semantics")

        val movedHook = originalCase.copy(setup = emptyList(), teardown = originalCase.setup)
        val moved = run("moved-hook", suite.copy(cases = listOf(movedHook)), originalCase.id, emptyMap()).copy(sharedSteps = listOf(originalShared))
        assertTrue(compareTestRuns(before.copy(scripts = emptyList()), moved).any { it.caseId == originalCase.id && it.caseDefinitionChanged },
            "moving a case hook from setup to teardown changes execution semantics")
    }

    @Test
    fun caseOutcomeTransitionsIncludeEmptyAndPartialRepeatedResults() {
        val emptyCase = TestCase("case-empty", "Empty setup-only case")
        val suite = suiteOf(emptyCase).copy(id = "suite-case-outcomes")
        val laneConfig = LaneConfig("any-id", LaneKind.EXTERNAL, null, "fixture")

        fun run(id: String, caseResults: List<CaseResult>): TestRun = TestRun(
            id = id,
            suite = suite,
            scripts = emptyList(),
            sharedSteps = emptyList(),
            config = RunConfig(suite.id, listOf(emptyCase.id), listOf(laneConfig), repeat = 2),
            lanes = listOf(LaneResult("lane-$id", laneConfig.copy(id = "lane-$id"), RunStatus.PASSED, caseResults)),
            status = RunStatus.PASSED,
        )
        val previous = run(
            "previous",
            listOf(CaseResult(emptyCase.id, emptyCase.name, iteration = 1, status = CaseStatus.ERROR)),
        )
        val current = run(
            "current",
            listOf(
                CaseResult(emptyCase.id, emptyCase.name, iteration = 1, status = CaseStatus.PASS),
                CaseResult(emptyCase.id, emptyCase.name, iteration = 2, status = CaseStatus.BLOCKED),
            ),
        )
        val outcome = compareTestRuns(previous, current).single { it.caseId == emptyCase.id && it.stepId.isBlank() }
        assertEquals("EXTERNAL//fixture/i1:ERROR", outcome.previousStatuses)
        assertEquals("EXTERNAL//fixture/i1:PASS, EXTERNAL//fixture/i2:BLOCKED", outcome.currentStatuses)

        val partial = run("partial", emptyList())
        val absent = compareTestRuns(previous, partial).single { it.caseId == emptyCase.id && it.stepId.isBlank() }
        assertEquals("Not run", absent.currentStatuses)
    }

    private fun run(runId: String, suite: TestSuite, caseId: String, statuses: Map<String, StepStatus>): TestRun {
        val lane = LaneConfig("lane-$runId", LaneKind.EXTERNAL, null, "fixture")
        val results = statuses.map { (id, status) -> StepResult(id, 1, id, status = status) }
        return TestRun(
            id = runId, suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
            config = RunConfig(suite.id, listOf(caseId), listOf(lane)),
            lanes = listOf(LaneResult(lane.id, lane, RunStatus.PASSED, listOf(CaseResult(caseId, "Checkout", status = CaseStatus.PASS, steps = results)))),
            status = RunStatus.PASSED,
        )
    }
}
