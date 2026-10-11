package com.indagium.testing

import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.ManualCompletion
import com.indagium.testing.model.ManualStepsOption
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.ScriptCall
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepCompletion
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.newCaseId
import com.indagium.testing.model.newHookId
import com.indagium.testing.model.newSharedStepId
import com.indagium.testing.model.newStepId
import com.indagium.testing.model.performedByLine
import com.indagium.testing.run.ManualWait
import com.indagium.testing.run.WaitingLaneInfo
import com.indagium.testing.run.lanePrompt
import com.indagium.testing.run.toMarkdown
import com.indagium.testing.store.decodeRunFile
import com.indagium.testing.store.encodeRunFile
import com.indagium.ui.LaneChoice
import com.indagium.ui.RunDialogModel
import com.indagium.ui.SuiteTab
import com.indagium.ui.TestsNav
import com.indagium.ui.TestsViewState
import com.indagium.ui.liveColumns
import com.indagium.ui.manualWaitClock
import com.indagium.ui.manualWaitRemaining
import com.indagium.ui.openScriptEditorFor
import com.indagium.ui.returnFromScriptEditor
import com.indagium.ui.scriptUsageCount
import com.indagium.ui.stepKindBadge
import com.indagium.ui.suiteHasManualSteps
import com.indagium.ui.toConfig
import com.indagium.ui.withRunConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How manual and script steps show up in the run file, the Markdown report, the live view, the run dialog and the step editor's state. */
class StepKindReportTest {
    private val lane = LaneConfig("lane-1", LaneKind.AGENT_PROFILE, "p1", "SER-1")

    private fun manualResult(status: StepStatus, completedBy: StepCompletion?, classification: JudgeClassification? = null) = StepResult(
        stepId = "step-m", stepNumber = 2, action = "Plug the USB stick into the phone", status = status, kind = StepKind.MANUAL,
        completedBy = completedBy, manualCompletion = ManualCompletion.TESTER_OR_CHECKS, classification = classification,
        observation = "Done, confirmed by the tester.",
    )

    private fun runOf(config: RunConfig, vararg steps: StepResult): TestRun {
        val case = CaseResult("case-1", "Hub", status = CaseStatus.PASS, steps = steps.toList())
        return TestRun(
            "run-1", suiteOf(caseOf("Hub", plainStep())), emptyList(), emptyList(), config,
            listOf(LaneResult("lane-1", lane, RunStatus.PASSED, listOf(case))), RunStatus.PASSED,
        )
    }

    // ── Run file ────────────────────────────────────────────────────

    @Test
    fun theRunFileKeepsTheKindHowItWasCompletedAndTheManualStepsOption() {
        val config = RunConfig("suite-1", null, listOf(lane), manualSteps = ManualStepsOption.WAIT_FOR_TESTER)
        val unconfirmed = manualResult(StepStatus.TIMEOUT, null, JudgeClassification.AGENT_OR_STEP_PROBLEM)
        val run = runOf(config, unconfirmed, manualResult(StepStatus.PASS, StepCompletion.CHECKS))

        val decoded = decodeRunFile(encodeRunFile(run)).getOrThrow()

        assertEquals(ManualStepsOption.WAIT_FOR_TESTER, decoded.config.manualSteps)
        val steps = decoded.lanes.single().cases.single().steps
        assertEquals(listOf(StepKind.MANUAL, StepKind.MANUAL), steps.map { it.kind })
        assertEquals(listOf(null, StepCompletion.CHECKS), steps.map { it.completedBy })
        assertEquals(JudgeClassification.AGENT_OR_STEP_PROBLEM, steps[0].classification)
        assertEquals(ManualCompletion.TESTER_OR_CHECKS, steps[0].manualCompletion)
    }

    @Test
    fun anAiStepIsWrittenWithoutTheNewFieldsAndAnOlderRunFileReadsAsOne() {
        val aiStep = StepResult("step-a", 1, "Tap", status = StepStatus.PASS)
        val text = encodeRunFile(runOf(RunConfig("suite-1", null, listOf(lane)), aiStep))

        listOf("completedBy", "classification", "manualCompletion").forEach { assertFalse(text.contains(it), "$it is not written for an AI step") }
        assertFalse(Regex("\"kind\"\\s*:\\s*\"(AGENT|MANUAL|SCRIPT)\"").containsMatchIn(text), "a step kind is not written for an AI step")
        val decoded = decodeRunFile(text.replace("\"manualSteps\": \"USE_SCRIPT_THEN_WAIT\",", "")).getOrThrow()
        assertEquals(ManualStepsOption.USE_SCRIPT_THEN_WAIT, decoded.config.manualSteps)
        assertEquals(StepKind.AGENT, decoded.lanes.single().cases.single().steps.single().kind)
        assertNull(decoded.lanes.single().cases.single().steps.single().completedBy)
    }

    // ── Report ──────────────────────────────────────────────────────

    @Test
    fun theReportNamesTheKindHowTheStepWasCompletedAndWhoIsBlamedForAnUnconfirmedStep() {
        val mode = "completion: tester or checks"
        assertEquals("Manual step · confirmed by the tester · $mode", manualResult(StepStatus.PASS, StepCompletion.TESTER).performedByLine())
        assertEquals("Manual step · detected by its checks · $mode", manualResult(StepStatus.PASS, StepCompletion.CHECKS).performedByLine())
        assertEquals("Manual step · skipped · $mode", manualResult(StepStatus.SKIPPED, StepCompletion.SKIPPED).performedByLine())
        assertEquals("Manual step · not confirmed · $mode", manualResult(StepStatus.TIMEOUT, null).performedByLine())
        val script = StepResult("s", 1, "Hub", status = StepStatus.PASS, kind = StepKind.SCRIPT, completedBy = StepCompletion.SCRIPT)
        assertEquals("Script step · done by a script", script.performedByLine())
        assertNull(StepResult("a", 1, "Tap", status = StepStatus.PASS).performedByLine())

        val markdown = runOf(
            RunConfig("suite-1", null, listOf(lane)),
            manualResult(StepStatus.TIMEOUT, null, JudgeClassification.AGENT_OR_STEP_PROBLEM),
            script,
        ).toMarkdown()

        assertTrue(markdown.contains("  - Manual step · not confirmed · completion: tester or checks"), markdown)
        assertTrue(markdown.contains("  - Classification: agent or step problem"), markdown)
        assertTrue(markdown.contains("  - Script step · done by a script"), markdown)
    }

    // ── Live view ───────────────────────────────────────────────────

    @Test
    fun theLiveColumnOfAWaitingLaneCarriesItsCardAndTheBarAndClockCountDown() {
        val run = runOf(RunConfig("suite-1", null, listOf(lane)))
        val wait = ManualWait(
            "Hub", 2, 3, "Plug it in", "", "SER-1", startedAtMs = 1_000L, deadlineAtMs = 11_000L,
            completion = ManualCompletion.TESTER_ONLY, testerCanComplete = true,
        )
        val other = WaitingLaneInfo("run-other", "lane-1", wait)
        val mine = WaitingLaneInfo("run-1", "lane-1", wait)

        val columns = liveColumns(run, emptyList(), { emptyList() }, emptyList(), emptyList(), listOf(other, mine))

        assertEquals(mine, columns.single().waiting, "only this run's card")
        assertEquals(1f, manualWaitRemaining(1_000L, 11_000L, 1_000L))
        assertEquals(.5f, manualWaitRemaining(1_000L, 11_000L, 6_000L))
        assertEquals(0f, manualWaitRemaining(1_000L, 11_000L, 20_000L))
        assertEquals("0:10", manualWaitClock(11_000L, 1_000L))
        assertEquals("1:05", manualWaitClock(66_000L, 1_000L))
        assertEquals("0:00", manualWaitClock(11_000L, 99_000L))
    }

    // ── Run dialog ──────────────────────────────────────────────────

    @Test
    fun theRunDialogCarriesTheManualStepsOptionIntoTheConfigAndBackIntoARerun() {
        val choices = listOf(LaneChoice("p1", "Claude"))
        val model = RunDialogModel("suite", setOf("c1"), choices[0], "SER-A", manualSteps = ManualStepsOption.SKIP)

        val config = model.toConfig(listOf("c1")).getOrThrow()
        assertEquals(ManualStepsOption.SKIP, config.manualSteps)
        val untouched = RunDialogModel("suite", setOf("c1"), choices[0], "SER-A").toConfig(listOf("c1")).getOrThrow()
        assertEquals(ManualStepsOption.USE_SCRIPT_THEN_WAIT, untouched.manualSteps)

        val prefilled = RunDialogModel("suite", setOf("c1"), choices[0], "SER-Z").withRunConfig(config, choices)
        assertEquals(ManualStepsOption.SKIP, prefilled.manualSteps)
    }

    @Test
    fun theDialogOffersTheChoiceOnlyWhenTheSuiteCanReachAManualStep() {
        val manual = TestStep(newStepId(), "Plug it in", kind = StepKind.MANUAL)
        val plainSuite = suiteOf(caseOf("A", plainStep()))
        val manualSuite = suiteOf(caseOf("B", plainStep(), manual))
        val shared = SharedStep(newSharedStepId(), "Hub", steps = listOf(manual))
        val viaHook = suiteOf(caseOf("C", plainStep(), setup = listOf(HookItem.Shared(newHookId(), shared.id))))

        assertFalse(suiteHasManualSteps(plainSuite, TestLibrary()))
        assertTrue(suiteHasManualSteps(manualSuite, TestLibrary()))
        assertFalse(suiteHasManualSteps(viaHook, TestLibrary()))
        assertTrue(suiteHasManualSteps(viaHook, TestLibrary(sharedSteps = listOf(shared))))
    }

    // ── Step editor state ───────────────────────────────────────────

    @Test
    fun aScriptEditorOpenedFromAStepLeadsBackToThatStepExpanded() {
        val view = TestsViewState()
        view.nav = TestsNav.Suites
        view.selectedSuiteId = "suite-1"
        view.selectedSuiteTab = SuiteTab.Cases
        view.selectedCaseId = "case-1"

        view.openScriptEditorFor("script-new", "step-9")
        assertEquals(TestsNav.Scripts, view.nav)
        assertEquals("script-new", view.selectedScriptId)

        view.nav = TestsNav.Runs
        view.selectedCaseId = null
        view.returnFromScriptEditor()
        assertEquals(TestsNav.Suites, view.nav)
        assertEquals("suite-1", view.selectedSuiteId)
        assertEquals("case-1", view.selectedCaseId)
        assertEquals("step-9", view.selectedStepId)
        assertNull(view.scriptReturn)

        view.returnFromScriptEditor()
        assertEquals(TestsNav.Suites, view.nav, "nothing to return to: nothing changes")
    }

    @Test
    fun theKindBadgeAndTheScriptUsageCountSeeStepScripts() {
        val script = sampleScript("hub_on")
        val scripted = TestStep(newStepId(), "Hub on", kind = StepKind.SCRIPT, script = ScriptCall(script.id))
        val manual = TestStep(newStepId(), "Plug it in", kind = StepKind.MANUAL, script = ScriptCall(script.id))
        val library = TestLibrary(suites = listOf(suiteOf(caseOf("A", scripted, manual, plainStep()))), scripts = listOf(script))

        assertEquals("Script", stepKindBadge(scripted))
        assertEquals("Manual", stepKindBadge(manual))
        assertNull(stepKindBadge(plainStep()))
        assertEquals(2, scriptUsageCount(library, script.id))
    }

    // ── Prompts ─────────────────────────────────────────────────────

    @Test
    fun theAgentIsToldWhenSomeStepsAreNotItsOwnAndSeesWhatThePersonDid() {
        val manual = TestStep(newStepId(), "Plug in the stick", kind = StepKind.MANUAL)
        val steps = listOf(plainStep("Open the app"), manual, plainStep("Check the files"))
        val suite = suiteOf(TestCase(newCaseId(), "Mixed", steps = steps))
        val previous = listOf(manualResult(StepStatus.PASS, StepCompletion.TESTER).copy(stepNumber = 2))

        val prompt = lanePrompt(suite, suite.cases.single(), "Mixed", steps, 2, 1, previous, setup = false)

        assertTrue(prompt.contains("Current step 3 of 3 (some steps of this case are performed by a person or by a script"), prompt)
        assertTrue(prompt.contains("Step 2 [manual step, done by tester]"), prompt)
        val agentOnly = lanePrompt(suite, suite.cases.single(), "Mixed", listOf(plainStep("Only")), 0, 1, emptyList(), setup = false)
        assertFalse(agentOnly.contains("performed by a person"))
    }
}
