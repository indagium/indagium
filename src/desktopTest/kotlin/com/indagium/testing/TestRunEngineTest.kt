package com.indagium.testing

import com.indagium.ai.LlmRole
import com.indagium.edition.EditionLimits
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.newHookId
import com.indagium.testing.model.newSharedStepId
import com.indagium.testing.run.PauseDecision
import com.indagium.testing.run.StartRunResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeFalse
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val AWAIT_MS = 15_000L
private const val POLL_MS = 10L

class TestRunEngineTest {
    private var harness: RunHarness? = null

    @AfterTest
    fun tearDown() {
        harness?.close()
    }

    private fun harness(
        suite: com.indagium.testing.model.TestSuite,
        turns: List<Turn>,
        scripts: List<com.indagium.testing.model.TestScript> = emptyList(),
        limits: EditionLimits = EditionLimits.UNLIMITED,
    ): RunHarness = RunHarness(TestLibrary(suites = listOf(suite), scripts = scripts), TurnProvider(turns), limits).also { harness = it }

    private fun RunHarness.runToEnd(suite: com.indagium.testing.model.TestSuite, repeat: Int = 1): TestRun = runBlocking {
        val started = coordinator.start(config(suite, repeat = repeat))
        val ok = assertIs<StartRunResult.Started>(started, started.toString())
        withTimeout(AWAIT_MS) { assertNotNull(coordinator.awaitFinished(ok.runId)) }
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) = runBlocking {
        withTimeout(AWAIT_MS) { while (!condition()) delay(POLL_MS) }
        Unit
    }.also { check(condition()) { what } }

    // ── Pass path ───────────────────────────────────────────────────

    @Test
    fun aCaseThatPassesEveryStepPassesTheRunAndLeavesEvidence() {
        val suite = suiteOf(caseOf("Login", step("Open the app", checks = listOf(logAppears("Login ok"))), step("Look at the home screen")))
        val h = harness(
            suite,
            listOf(
                toolTurn("take_screenshot"),
                finishTurn("pass", "The login screen opened", before = { h().emitLog("Login ok") }),
                finishTurn("pass", "Home screen is visible"),
                textTurn,
            ),
        )
        val run = h.runToEnd(suite)

        assertEquals(RunStatus.PASSED, run.status, run.toString())
        assertEquals(listOf(StepStatus.PASS, StepStatus.PASS), run.caseResult("Login").steps.map { it.status })
        assertEquals(CaseStatus.PASS, run.caseResult("Login").status)
        val first = run.caseResult("Login").steps.first()
        assertEquals("pass", first.agentClaim)
        assertEquals(CheckStatus.PASS, first.checks.single().status)
        assertEquals(1, first.attempts)
        val runDir = h.store.runDir(run.id)
        val shot = assertNotNull(first.screenshotPath)
        assertTrue(File(runDir, shot).length() > 0, "the screenshot was saved")
        assertTrue(assertNotNull(first.logEndOffset) >= assertNotNull(first.logStartOffset))
        val transcript = File(runDir, assertNotNull(run.lanes.single().transcriptPath)).readText()
        assertTrue(transcript.contains("finish_step"), transcript)
        assertFalse(h.adb.logcat.isAlive, "the lane's recorder was stopped")
        // run.json on disk is the final state, loadable by a later launch.
        val stored = assertNotNull(h.store.load(run.id))
        assertEquals(RunStatus.PASSED, stored.status)
        assertEquals(run.suite, stored.suite)
    }

    private fun h(): RunHarness = checkNotNull(harness)

    @Test
    fun theAgentIsShownOnlyTheCurrentStepAndTheSuiteContext() {
        val suite = suiteOf(caseOf("Login", step("Tap the SECRET-FIRST button"), step("Tap the SECOND button")))
        val h = harness(suite, listOf(finishTurn("pass"), finishTurn("pass"), textTurn))
        h.runToEnd(suite)

        val first = h.provider.requests.first().messages.filter { it.role == LlmRole.USER }.joinToString("\n") { it.content.orEmpty() }
        assertTrue(first.contains("SECRET-FIRST"), first)
        assertTrue(first.contains("com.example.app"), first)
        assertFalse(first.contains("SECOND button"), "later steps stay hidden until finish_step reveals them")
        val toolResult = h.provider.requests[1].messages.last { it.role == LlmRole.TOOL }.content.orEmpty()
        assertTrue(toolResult.contains("SECOND button"), toolResult)
    }

    // ── Retries and failure policies ────────────────────────────────

    @Test
    fun aFailedCheckRetriesTheStepAndThePassCountsTwoAttempts() {
        val suite = suiteOf(caseOf("Retry", step("Open it", retries = 1, checks = listOf(logAppears("Opened OK", withinMs = 800)))))
        val h = harness(
            suite,
            listOf(
                finishTurn("pass", "I think it opened"),
                finishTurn("pass", "Now it opened", before = { h().emitLog("Opened OK") }),
                textTurn,
            ),
        )
        val run = h.runToEnd(suite)

        val result = run.caseResult("Retry").steps.single()
        assertEquals(StepStatus.PASS, result.status)
        assertEquals(2, result.attempts)
        assertEquals(RunStatus.PASSED, run.status)
        val redo = h.provider.requests[1].messages.last { it.role == LlmRole.TOOL }.content.orEmpty()
        assertTrue(redo.contains("redo"), redo)
        assertTrue(redo.contains("attempt 2"), redo)
    }

    @Test
    fun stopCaseEndsTheCaseSkipsTheRestAndFailsTheRun() {
        val suite = suiteOf(caseOf("Stop", step("One"), step("Two"), step("Three")))
        val h = harness(suite, listOf(finishTurn("fail", "It crashed"), textTurn))
        val run = h.runToEnd(suite)

        val case = run.caseResult("Stop")
        assertEquals(listOf(StepStatus.FAIL, StepStatus.SKIPPED, StepStatus.SKIPPED), case.steps.map { it.status })
        assertEquals(CaseStatus.FAIL, case.status)
        assertEquals(RunStatus.FAILED, run.status)
        val answer = h.provider.requests[1].messages.last { it.role == LlmRole.TOOL }.content.orEmpty()
        assertTrue(answer.contains("case_finished"), answer)
    }

    @Test
    fun continueAndCreateIssueAndContinueMoveOnAndFlagTheIssue() {
        val suite = suiteOf(
            caseOf(
                "Continue",
                step("One", onFailure = OnFailure.CONTINUE),
                step("Two", onFailure = OnFailure.CREATE_ISSUE_AND_CONTINUE),
                step("Three"),
            ),
        )
        val h = harness(suite, listOf(finishTurn("fail", "bad one"), finishTurn("blocked", "no button"), finishTurn("pass"), textTurn))
        val run = h.runToEnd(suite)

        val steps = run.caseResult("Continue").steps
        assertEquals(listOf(StepStatus.FAIL, StepStatus.BLOCKED, StepStatus.PASS), steps.map { it.status })
        assertEquals(listOf(false, true, false), steps.map { it.issueRequested })
        assertEquals(CaseStatus.FAIL, run.caseResult("Continue").status)
        assertEquals(RunStatus.FAILED, run.status)
    }

    @Test
    fun pauseForUserWaitsForTheDecisionAndContinueMovesOn() {
        val suite = suiteOf(caseOf("Pause", step("One", onFailure = OnFailure.PAUSE_FOR_USER), step("Two")))
        val h = harness(suite, listOf(finishTurn("fail", "needs a look"), finishTurn("pass"), textTurn))
        val run = runBlocking {
            val started = assertIs<StartRunResult.Started>(h.coordinator.start(h.config(suite)))
            awaitTrue("lane paused") { h.coordinator.pausedSteps().isNotEmpty() }
            val paused = h.coordinator.pausedSteps().single()
            assertEquals(1, paused.step.stepNumber)
            assertEquals("needs a look", paused.step.observation)
            assertEquals(RunStatus.RUNNING, h.coordinator.run(started.runId)?.status, "the run waits while paused")
            assertTrue(h.coordinator.resumePausedStep(started.runId, paused.laneId, PauseDecision.CONTINUE))
            withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) }
        }
        assertEquals(listOf(StepStatus.FAIL, StepStatus.PASS), run.caseResult("Pause").steps.map { it.status })
        assertTrue(h.coordinator.pausedSteps().isEmpty())
    }

    @Test
    fun pauseForUserRetryGivesTheStepAnotherAttemptAndStopEndsTheCase() {
        val suite = suiteOf(caseOf("Retry on pause", step("One", onFailure = OnFailure.PAUSE_FOR_USER), step("Two")))
        val h = harness(suite, listOf(finishTurn("fail", "first"), finishTurn("fail", "second"), textTurn))
        val run = runBlocking {
            val started = assertIs<StartRunResult.Started>(h.coordinator.start(h.config(suite)))
            awaitTrue("first pause") { h.coordinator.pausedSteps().isNotEmpty() }
            h.coordinator.resumePausedStep(started.runId, started.laneIds.single(), PauseDecision.RETRY)
            awaitTrue("pause resolved") { h.coordinator.pausedSteps().isEmpty() }
            awaitTrue("second pause") { h.coordinator.pausedSteps().isNotEmpty() }
            assertEquals("second", h.coordinator.pausedSteps().single().step.observation)
            h.coordinator.resumePausedStep(started.runId, started.laneIds.single(), PauseDecision.STOP)
            withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) }
        }
        val steps = run.caseResult("Retry on pause").steps
        assertEquals(StepStatus.FAIL, steps.first().status)
        assertEquals(2, steps.first().attempts)
        assertEquals(StepStatus.SKIPPED, steps.last().status)
    }

    // ── Timeouts, restarts, caps, cancel ────────────────────────────

    @Test
    fun aStepTimeoutIsMarkedAndAFreshAgentResumesAtTheNextStepWithASummary() {
        val suite = suiteOf(caseOf("Timeout", step("Slow step", timeoutMs = 250, onFailure = OnFailure.CONTINUE), step("Fast step")))
        val h = harness(suite, listOf(hangingTurn, finishTurn("pass", "all good"), textTurn))
        val run = h.runToEnd(suite)

        val steps = run.caseResult("Timeout").steps
        assertEquals(listOf(StepStatus.TIMEOUT, StepStatus.PASS), steps.map { it.status })
        assertEquals(CaseStatus.FAIL, run.caseResult("Timeout").status)
        val resumed = h.provider.requests[1].messages.filter { it.role == LlmRole.USER }.joinToString("\n") { it.content.orEmpty() }
        assertTrue(resumed.contains("Current step 2 of 2"), resumed)
        assertTrue(resumed.contains("<untrusted_data"), "the summary of earlier steps is fenced:\n$resumed")
        assertTrue(resumed.contains("Step 1") && resumed.contains("TIMEOUT"), resumed)
        assertFalse(resumed.contains("Slow step\n") && resumed.contains("Current step 1"), resumed)
    }

    @Test
    fun anAgentThatStopsWithoutFinishingIsRestartedAtTheSameStepThenGivenUp() {
        val suite = suiteOf(caseOf("Stops", step("Only step")))
        // maxAgentRestarts = 1: the first plain-text answer restarts the agent, the second one gives up on the step.
        val h = harness(suite, listOf(textTurn, textTurn))
        val run = h.runToEnd(suite)

        val result = run.caseResult("Stops").steps.single()
        assertEquals(StepStatus.ERROR, result.status)
        assertTrue(result.note.orEmpty().contains("without finishing"), result.note)
        assertEquals(CaseStatus.ERROR, run.caseResult("Stops").status)
        assertEquals(RunStatus.ERROR, run.status)
    }

    @Test
    fun cancellingMidStepStopsTheAgentRunsTeardownAndReleasesTheDevice() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        val marker = File.createTempFile("teardown", ".txt").also { it.delete() }
        val teardown = hostScript("tidy_up", "printf done > '${marker.absolutePath}'")
        val suite = suiteOf(caseOf("Cancel me", step("Never finishes")), teardown = listOf(scriptHook(teardown)))
        val h = harness(suite, listOf(hangingTurn), scripts = listOf(teardown))
        val run = runBlocking {
            val started = assertIs<StartRunResult.Started>(h.coordinator.start(h.config(suite)))
            awaitTrue("step started") { h.coordinator.run(started.runId)?.lanes?.single()?.currentStepNumber == 1 }
            assertTrue(h.coordinator.cancel(started.runId))
            withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) }
        }
        assertEquals(RunStatus.CANCELLED, run.status)
        assertEquals(CaseStatus.CANCELLED, run.caseResult("Cancel me").status)
        assertEquals("done", marker.readText(), "the teardown script ran although the run was cancelled")
        assertFalse(h.adb.logcat.isAlive, "the recorder was stopped")
        marker.delete()
        // The device is free again.
        assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(h.config(suite)) })
    }

    @Test
    fun aFailingSetupHookBlocksTheCasesButTheTeardownStillRuns() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        val marker = File.createTempFile("teardown", ".txt").also { it.delete() }
        val broken = hostScript("broken_setup", "exit 3")
        val teardown = hostScript("tidy_up", "printf done > '${marker.absolutePath}'")
        val suite = suiteOf(
            caseOf("Blocked one", step("A"), step("B")),
            setup = listOf(scriptHook(broken)),
            teardown = listOf(scriptHook(teardown)),
        )
        val h = harness(suite, emptyList(), scripts = listOf(broken, teardown))
        val run = h.runToEnd(suite)

        val case = run.caseResult("Blocked one")
        assertEquals(CaseStatus.BLOCKED, case.status)
        assertEquals(listOf(StepStatus.SKIPPED, StepStatus.SKIPPED), case.steps.map { it.status })
        assertEquals(RunStatus.FAILED, run.status)
        assertTrue(h.provider.requests.isEmpty(), "the agent was never started")
        assertEquals("done", marker.readText())
        val setup = run.lanes.single().cases.first { it.caseName == "Suite setup" }
        assertEquals(StepStatus.FAIL, setup.steps.single().status)
        assertTrue(setup.steps.single().setup)
        marker.delete()
    }

    @Test
    fun aCaseSetupHookFailureBlocksOnlyThatCase() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        val broken = hostScript("broken_setup", "exit 1")
        val suite = suiteOf(
            caseOf("Needs setup", step("A"), setup = listOf(scriptHook(broken))),
            caseOf("Fine", step("B")),
        )
        val h = harness(suite, listOf(finishTurn("pass"), textTurn), scripts = listOf(broken))
        val run = h.runToEnd(suite)

        assertEquals(CaseStatus.BLOCKED, run.caseResult("Needs setup").status)
        assertEquals(CaseStatus.PASS, run.caseResult("Fine").status)
        assertEquals(RunStatus.FAILED, run.status)
    }

    @Test
    fun lockedCasesAreSkippedWithAWarningAndNeverRun() {
        val suite = suiteOf(caseOf("Active", step("A")), caseOf("Locked", step("B")))
        val h = harness(suite, listOf(finishTurn("pass"), textTurn), limits = EditionLimits(maxSuites = 1, maxCasesPerSuite = 1))
        val started = runBlocking { h.coordinator.start(h.config(suite)) }
        val ok = assertIs<StartRunResult.Started>(started)
        assertTrue(ok.warnings.any { it.contains("Locked") }, ok.warnings.toString())
        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(ok.runId)) } }

        assertEquals(CaseStatus.PASS, run.caseResult("Active").status)
        assertEquals(CaseStatus.SKIPPED, run.caseResult("Locked").status)
        assertTrue(run.caseResult("Locked").steps.isEmpty())
        assertEquals(RunStatus.PASSED, run.status)
        assertEquals(1, h.provider.requests.map { it.messages.count { m -> m.role == LlmRole.USER } }.distinct().single())
    }

    @Test
    fun aRunThatCannotStartIsRefusedAsDataAndNothingStarts() {
        val suite = suiteOf(caseOf("Case", step("A")))
        val h = RunHarness(libraryOf(suite), deviceProblem = { "Device $it is not connected." }).also { harness = it }
        val refused = runBlocking { h.coordinator.start(h.config(suite)) }
        assertIs<StartRunResult.Rejected>(refused)
        assertTrue(refused.errors.single().contains("not connected"), refused.errors.toString())
        assertTrue(h.coordinator.runsFlow.value.isEmpty())

        val unknown = runBlocking { h.coordinator.start(h.config(suite).copy(suiteId = "suite-nope")) }
        assertTrue(assertIs<StartRunResult.Rejected>(unknown).errors.any { it.contains("not found") })
        val badRepeat = runBlocking { h.coordinator.start(h.config(suite).copy(repeat = 2)) }
        assertTrue(assertIs<StartRunResult.Rejected>(badRepeat).errors.any { it.contains("repeat") })
        val noProfile = runBlocking { h.coordinator.start(h.config(suite, agentLane().copy(profileId = "nope"))) }
        assertTrue(assertIs<StartRunResult.Rejected>(noProfile).errors.any { it.contains("does not exist") })
        assertNull(h.coordinator.run("run-none"))
    }

    @Test
    fun repeatRunsEveryCaseThatManyTimes() {
        val suite = suiteOf(caseOf("Again", step("A")))
        val h = harness(suite, listOf(finishTurn("pass"), textTurn, finishTurn("pass"), textTurn, finishTurn("pass"), textTurn))
        val run = h.runToEnd(suite, repeat = 3)

        assertEquals(listOf(1, 2, 3), run.lanes.single().cases.map { it.iteration })
        assertTrue(run.lanes.single().cases.all { it.status == CaseStatus.PASS })
    }

    @Test
    fun overThePerStepToolCapEveryToolButFinishStepAnswersThatTheBudgetIsExhausted() {
        val suite = suiteOf(caseOf("Capped", step("Press things", maxToolCalls = 2)))
        val h = harness(
            suite,
            listOf(
                toolTurn("press_key", """{"key":"HOME"}"""),
                toolTurn("press_key", """{"key":"BACK"}"""),
                toolTurn("press_key", """{"key":"HOME"}"""),
                toolTurn("get_current_step"),
                finishTurn("pass", "done anyway"),
                textTurn,
            ),
        )
        val run = h.runToEnd(suite)

        fun resultOf(requestIndex: Int) = h.provider.requests[requestIndex].messages.last { it.role == LlmRole.TOOL }.content.orEmpty()
        assertTrue(resultOf(1).contains("\"ok\"") || resultOf(1).contains("ok=true"), resultOf(1))
        assertTrue(resultOf(2).contains("ok=true"), "the second call is still inside the cap: ${resultOf(2)}")
        assertTrue(resultOf(3).contains("step budget exhausted — call finish_step"), resultOf(3))
        assertTrue(resultOf(4).contains("step budget exhausted"), "even get_current_step is refused once the cap is used up: ${resultOf(4)}")
        assertEquals(2, h.adb.shellCommands.count { it.firstOrNull() == "input" }, "refused calls never reached adb")
        assertEquals(StepStatus.PASS, run.caseResult("Capped").steps.single().status, "finish_step still works")
    }

    @Test
    fun anExternalLaneNobodyDrivesTimesOutStepByStepAndTheCaseFails() {
        val suite = suiteOf(
            caseOf(
                "Unattended",
                step("First", timeoutMs = 150, onFailure = OnFailure.CONTINUE),
                step("Second", timeoutMs = 150, onFailure = OnFailure.CONTINUE),
            ),
        )
        val h = RunHarness(libraryOf(suite)).also { harness = it }
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(h.config(suite, externalLane())) })
        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        assertEquals(listOf(StepStatus.TIMEOUT, StepStatus.TIMEOUT), run.caseResult("Unattended").steps.map { it.status })
        assertEquals(RunStatus.FAILED, run.status)
        assertTrue(run.caseResult("Unattended").steps.first().observation.contains("timed out"))
    }

    @Test
    fun theCaseToolCallLimitIsSpentAcrossAgentRestarts() {
        val suite = suiteOf(caseOf("Limited", step("Only step", timeoutMs = 200, onFailure = OnFailure.CONTINUE), step("Second")))
        val h = harness(suite, listOf(toolTurn("press_key", """{"key":"HOME"}"""), hangingTurn, finishTurn("pass"), textTurn))
        val limited = runBlocking {
            val started = assertIs<StartRunResult.Started>(h.coordinator.start(h.config(suite, toolLimit = 5)))
            withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) }
        }
        assertEquals(listOf(StepStatus.TIMEOUT, StepStatus.PASS), limited.caseResult("Limited").steps.map { it.status })
        val resumedGuidance = h.provider.requests.last().messages.filter { it.role == LlmRole.SYSTEM }.joinToString("\n") { it.content.orEmpty() }
        assertTrue(resumedGuidance.contains("strict 4-call budget"), "one of the 5 calls was already spent before the restart:\n$resumedGuidance")
    }

    @Test
    fun aSharedStepHookRunsItsStepsThroughTheAgentFlaggedAsSetup() {
        val shared = SharedStep(newSharedStepId(), "Log in", steps = listOf(step("Open login"), step("Submit credentials")))
        val case = caseOf("Uses login", step("Check the home screen"), setup = listOf(HookItem.Shared(newHookId(), shared.id)))
        val suite = suiteOf(case)
        val library = TestLibrary(suites = listOf(suite), sharedSteps = listOf(shared))
        val provider = TurnProvider(listOf(finishTurn("pass"), finishTurn("pass"), textTurn, finishTurn("pass", "home"), textTurn))
        val h = RunHarness(library, provider).also { harness = it }
        val run = h.runToEnd(suite)

        assertEquals(RunStatus.PASSED, run.status, run.toString())
        val steps = run.caseResult("Uses login").steps
        assertEquals(listOf(true, true, false), steps.map { it.setup })
        assertEquals(listOf("Open login", "Submit credentials", "Check the home screen"), steps.map { it.action })
        assertTrue(steps.all { it.status == StepStatus.PASS })
        assertEquals(listOf(shared), run.sharedSteps, "the run froze the shared step it used")
        val setupPrompt = h.provider.requests.first().messages.filter { it.role == LlmRole.USER }.joinToString("\n") { it.content.orEmpty() }
        assertTrue(setupPrompt.contains("Open login") && !setupPrompt.contains("Check the home screen"), setupPrompt)
    }

    @Test
    fun aFailingSharedStepSetupBlocksTheCase() {
        val shared = SharedStep(newSharedStepId(), "Log in", steps = listOf(step("Open login")))
        val case = caseOf("Uses login", step("Never reached"), setup = listOf(HookItem.Shared(newHookId(), shared.id)))
        val suite = suiteOf(case)
        val provider = TurnProvider(listOf(finishTurn("fail", "no login screen"), textTurn))
        val h = RunHarness(TestLibrary(suites = listOf(suite), sharedSteps = listOf(shared)), provider).also { harness = it }
        val run = h.runToEnd(suite)

        val result = run.caseResult("Uses login")
        assertEquals(CaseStatus.BLOCKED, result.status)
        assertEquals(listOf(StepStatus.FAIL, StepStatus.SKIPPED), result.steps.map { it.status })
        assertEquals(RunStatus.FAILED, run.status)
    }
}
