package com.indagium.testing

import com.indagium.ai.LlmRole
import com.indagium.edition.EditionLimits
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.newHookId
import com.indagium.testing.model.newSharedStepId
import com.indagium.testing.run.PauseDecision
import com.indagium.testing.run.StartRunResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeFalse
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    private fun RunHarness.runToEnd(suite: com.indagium.testing.model.TestSuite, repeat: Int = 1, toolLimit: Int = 40): TestRun = runBlocking {
        val started = coordinator.start(config(suite, repeat = repeat, toolLimit = toolLimit))
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

    @Test
    fun finishReplyCarriesTheNextStepsExamplesAndOnlyItsAllowedExampleTools() {
        val second = step("Check receipt").copy(
            examples = listOf(StepExample.ReferenceLog("receipt-log", "I/Checkout: receipt saved", "Receipt event")),
        )
        val case = caseOf("Receipt", step("Open checkout"), second).copy(allowedTools = setOf("get_step_example"))
        val suite = suiteOf(case)
        val h = harness(suite, listOf(finishTurn("pass"), finishTurn("pass"), textTurn))
        h.runToEnd(suite)

        val nextStepReply = h.provider.requests[1].messages.last { it.role == LlmRole.TOOL }.content.orEmpty()
        assertTrue(nextStepReply.contains("receipt-log"), nextStepReply)
        assertTrue(nextStepReply.contains("Receipt event"), nextStepReply)
        assertTrue(nextStepReply.contains("get_step_example"), nextStepReply)
        assertFalse(nextStepReply.contains("list_step_examples"), nextStepReply)
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
    fun externalToolResultCompletingAfterTimeoutKeepsItsAdmissionStepIdentity() {
        assumeFalse("the fixture host shell uses POSIX sleep/printf", System.getProperty("os.name").lowercase().contains("win"))
        val marker = File.createTempFile("late-tool-start", ".txt").also { it.delete() }
        val release = File.createTempFile("late-tool-release", ".txt").also { it.delete() }
        marker.deleteOnExit()
        release.deleteOnExit()
        val held = hostScript(
            "hold_for_late_result",
            "printf started > '${marker.absolutePath}'; while [ ! -f '${release.absolutePath}' ]; do sleep 0.01; done",
            com.indagium.testing.model.ScriptPermission.AUTO,
        )
        val first = step("Held action", timeoutMs = 180, onFailure = OnFailure.CONTINUE)
        val suite = suiteOf(caseOf("Late result", first, step("Next action", timeoutMs = 3_000)))
        val h = RunHarness(TestLibrary(suites = listOf(suite), scripts = listOf(held))).also { harness = it }
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(h.config(suite, externalLane())) })
        val laneId = h.coordinator.run(started.runId)!!.lanes.single().laneId
        var lateResult: Any? = null
        runBlocking {
            coroutineScope {
                withTimeout(AWAIT_MS) {
                    while (h.coordinator.run(started.runId)?.lanes?.single()?.currentStepAction == null) delay(POLL_MS)
                }
                val dispatch = async { h.coordinator.laneToolCall(started.runId, laneId, held.toolName, emptyMap()) }
                withTimeout(AWAIT_MS) { while (!marker.exists()) delay(POLL_MS) }
                withTimeout(AWAIT_MS) {
                    while (h.coordinator.run(started.runId)?.lanes?.single()?.currentStepNumber != 2) delay(POLL_MS)
                }
                val refusedFinish = h.coordinator.laneToolCall(
                    started.runId, laneId, "finish_step", mapOf("status" to "pass", "observation" to "too early"),
                )
                assertTrue(refusedFinish.toString().contains("still running"), refusedFinish.toString())
                release.writeText("release")
                lateResult = dispatch.await()
                h.coordinator.laneToolCall(started.runId, laneId, "finish_step", mapOf("status" to "pass", "observation" to "next action completed"))
            }
        }
        val terminal = runBlocking { h.coordinator.awaitFinished(started.runId) } ?: error("run not found")
        val activity = terminal.lanes.single().toolCalls.first { it.toolName == held.toolName }
        assertTrue(lateResult.toString().contains("exitCode") || lateResult.toString().contains("exit_code"), lateResult.toString())
        assertEquals(first.id, activity.stepId)
        assertEquals(suite.cases.single().id, activity.caseId)
        assertEquals(1, activity.iteration)
        assertEquals(1, activity.attempt)
        assertEquals(com.indagium.testing.model.LaneToolCallStatus.SUCCEEDED, activity.status)
        assertFalse(activity.resultPreview.contains("base64", ignoreCase = true), activity.resultPreview)
        val finishActivities = terminal.lanes.single().toolCalls.filter { it.toolName == "finish_step" }
        assertEquals(2, finishActivities.size)
        assertEquals(terminal.caseResult("Late result").steps.last().stepId, finishActivities.first().stepId)
        assertEquals(com.indagium.testing.model.LaneToolCallStatus.FAILED, finishActivities.first().status)
        assertEquals(com.indagium.testing.model.LaneToolCallStatus.SUCCEEDED, finishActivities.last().status)
        marker.delete()
        release.delete()
    }

    @Test
    fun deadlineDuringChecksRecordsOneTimeoutAndKeepsCompletedResultsAndScreenshot() {
        val script = hostScript("check_output", "printf ready")
        val firstCheck = StepCheck.ScriptResult(com.indagium.testing.model.newCheckId(), script.id, exitCode = 0, stdoutContains = "ready")
        val slowCheck = StepCheck.LogAppears(com.indagium.testing.model.newCheckId(), regex = "never-arrives", withinMs = 10_000)
        val first = step("Check output", onFailure = OnFailure.CONTINUE, timeoutMs = 700, checks = listOf(firstCheck, slowCheck))
        val suite = suiteOf(caseOf("Deadline", first, step("Continue after timeout", timeoutMs = 3_000)))
        // A timed-out finish is followed by a scripted stale-agent response, then a fresh response
        // for the next step's replacement segment.
        val h = harness(suite, listOf(finishTurn("pass"), finishTurn("pass"), finishTurn("pass"), textTurn), scripts = listOf(script))
        val run = h.runToEnd(suite)
        val result = run.caseResult("Deadline").steps

        assertEquals(listOf(StepStatus.TIMEOUT, StepStatus.PASS), result.map { it.status }, run.toString())
        assertEquals(1, result.count { it.stepId == first.id }, "the watchdog and finish path cannot record the timeout twice")
        assertEquals(com.indagium.testing.model.CheckStatus.PASS, result.first().checks.first().status)
        assertEquals(com.indagium.testing.model.CheckStatus.NOT_EVALUATED, result.first().checks.last().status)
        val screenshot = assertNotNull(result.first().screenshotPath)
        assertTrue(File(h.store.runDir(run.id), screenshot).isFile, "screenshot evidence completed before the deadline")
        assertEquals(RunStatus.FAILED, run.status, run.toString())
    }

    @Test
    fun deadlineInterruptsBlockingScreenshotCapture() {
        val startedCapture = CountDownLatch(1)
        val interruptedCapture = CountDownLatch(1)
        val releaseCapture = CountDownLatch(1)
        val adb = ScriptedAdbRunner().also { runner ->
            runner.beforeScreenshot = {
                startedCapture.countDown()
                try {
                    releaseCapture.await(5, TimeUnit.SECONDS)
                } catch (interrupted: InterruptedException) {
                    interruptedCapture.countDown()
                    throw interrupted
                }
            }
        }
        val suite = suiteOf(caseOf("Slow screenshot", step("Wait for the screen", timeoutMs = 300)))
        val h = RunHarness(
            TestLibrary(suites = listOf(suite)),
            TurnProvider(listOf(finishTurn("pass"), textTurn)),
            openDevice = com.indagium.testing.run.LaneDeviceOpener { _, laneDir, _ -> openFixtureSession(adb, laneDir) },
        ).also { harness = it }

        val run = h.runToEnd(suite)

        assertTrue(startedCapture.await(1, TimeUnit.SECONDS), "finish_step entered blocking screencap")
        assertTrue(interruptedCapture.await(2, TimeUnit.SECONDS), "step deadline interrupts the blocking capture thread")
        assertEquals(listOf(StepStatus.TIMEOUT), run.caseResult("Slow screenshot").steps.map { it.status }, run.toString())
        assertNull(run.caseResult("Slow screenshot").steps.single().screenshotPath, "an incomplete capture is not reported as evidence")
        releaseCapture.countDown()
    }

    @Test
    fun anAgentRestartDoesNotResetTheCurrentAttemptDeadline() {
        val suite = suiteOf(caseOf("Restart deadline", step("One step", timeoutMs = 500, onFailure = OnFailure.CONTINUE)))
        val stopped: Turn = {
            delay(300)
            emit(com.indagium.ai.LlmStreamEvent.TextDelta("I need another turn."))
            emit(com.indagium.ai.LlmStreamEvent.Completed)
        }
        val delayedFinish: Turn = { request ->
            delay(300)
            finishTurn("pass").invoke(this, request)
        }
        val h = harness(suite, listOf(stopped, delayedFinish, textTurn))
        val run = h.runToEnd(suite)

        assertEquals(listOf(StepStatus.TIMEOUT), run.caseResult("Restart deadline").steps.map { it.status }, run.toString())
        assertEquals(RunStatus.FAILED, run.status, run.toString())
        assertEquals(2, h.provider.requests.size, "the second segment was cancelled at the original attempt deadline")
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
        assertTrue(resumedGuidance.contains("strict 5-call budget"), "the case limit stays stable across a restart:\n$resumedGuidance")
        assertTrue(resumedGuidance.contains("4 paid dispatches remain"), "the agent gets the remaining case allowance:\n$resumedGuidance")
    }

    @Test
    fun agentCanUseFreeProtocolToolsAfterExactlySpendingTheCaseBudget() {
        val suite = suiteOf(caseOf("Exact budget", step("Press once", maxToolCalls = 5)))
        val h = harness(suite, listOf(toolTurn("press_key", """{"key":"HOME"}"""), textTurn, finishTurn("pass"), textTurn))
        val run = h.runToEnd(suite, toolLimit = 1)

        assertEquals(RunStatus.PASSED, run.status, run.toString())
        assertEquals(CaseStatus.PASS, run.caseResult("Exact budget").status)
        assertEquals(1, h.adb.shellCommands.count { it.firstOrNull() == "input" })
        val restartPrompt = h.provider.requests[2].messages.filter { it.role == LlmRole.SYSTEM }.joinToString("\n") { it.content.orEmpty() }
        assertTrue(restartPrompt.contains("strict 1-call budget"), restartPrompt)
        assertTrue(restartPrompt.contains("0 paid dispatches remain"), restartPrompt)
    }

    @Test
    fun overBudgetAgentDispatchCannotBeFollowedByAFalsePass() {
        val suite = suiteOf(caseOf("Over budget", step("Press once", maxToolCalls = 8)))
        val h = harness(
            suite,
            listOf(
                toolTurn("press_key", """{"key":"HOME"}"""),
                toolTurn("press_key", """{"key":"BACK"}"""),
                finishTurn("pass", "pretend it worked"),
                textTurn,
            ),
        )
        val run = h.runToEnd(suite, toolLimit = 1)

        assertEquals(RunStatus.ERROR, run.status, run.toString())
        assertEquals(CaseStatus.ERROR, run.caseResult("Over budget").status)
        assertEquals(1, h.adb.shellCommands.count { it.firstOrNull() == "input" }, "the refused call never reached adb")
        assertTrue(run.caseResult("Over budget").note.orEmpty().contains("case tool-call budget"), run.caseResult("Over budget").toString())
    }

    @Test
    fun exhaustedBudgetMakesCaseErrorEvenAfterAnEarlierContinuedFailure() {
        val suite = suiteOf(
            caseOf(
                "Failure before exhaustion",
                step("Earlier failure", onFailure = OnFailure.CONTINUE),
                step("Spend budget", maxToolCalls = 8),
            ),
        )
        val h = harness(
            suite,
            listOf(
                finishTurn("fail", "first check failed"),
                toolTurn("press_key", """{"key":"HOME"}"""),
                toolTurn("press_key", """{"key":"BACK"}"""),
                finishTurn("pass", "ignored"),
                textTurn,
            ),
        )

        val run = h.runToEnd(suite, toolLimit = 1)

        val result = run.caseResult("Failure before exhaustion")
        assertEquals(listOf(StepStatus.FAIL, StepStatus.ERROR), result.steps.map { it.status }, run.toString())
        assertEquals(CaseStatus.ERROR, result.status, "budget exhaustion takes precedence over a continued deterministic failure")
        assertTrue(result.note.orEmpty().contains("budget was exhausted"), result.toString())
    }

    @Test
    fun exhaustedBudgetDuringSharedCaseSetupProducesActionableCaseError() {
        val shared = SharedStep(
            newSharedStepId(),
            "Paid setup",
            steps = listOf(step("First setup action", maxToolCalls = 8), step("Second setup action", maxToolCalls = 8)),
        )
        val case = caseOf("Setup budget", step("Never reached"), setup = listOf(HookItem.Shared(newHookId(), shared.id)))
        val suite = suiteOf(case)
        val library = TestLibrary(suites = listOf(suite), sharedSteps = listOf(shared))
        val h = RunHarness(
            library,
            TurnProvider(listOf(
                toolTurn("press_key", """{"key":"HOME"}"""),
                finishTurn("pass"),
                toolTurn("press_key", """{"key":"BACK"}"""),
                textTurn,
            )),
        ).also { harness = it }

        val run = h.runToEnd(suite, toolLimit = 1)

        val result = run.caseResult("Setup budget")
        assertEquals(CaseStatus.ERROR, result.status, run.toString())
        assertTrue(result.note.orEmpty().contains("budget was exhausted"), result.toString())
        assertEquals(StepStatus.SKIPPED, result.steps.last().status)
    }

    @Test
    fun retryDoesNotRestoreCaseBudget() {
        val suite = suiteOf(caseOf("Retry budget", step("Retry after failure", retries = 1, maxToolCalls = 8)))
        val h = harness(
            suite,
            listOf(
                toolTurn("press_key", """{"key":"HOME"}"""),
                finishTurn("fail", "retry this attempt"),
                toolTurn("press_key", """{"key":"BACK"}"""),
                finishTurn("pass", "ignored after budget exhaustion"),
                textTurn,
            ),
        )

        val run = h.runToEnd(suite, toolLimit = 1)

        val result = run.caseResult("Retry budget")
        assertEquals(CaseStatus.ERROR, result.status, run.toString())
        assertEquals(StepStatus.ERROR, result.steps.single().status)
        assertEquals(1, h.adb.shellCommands.count { it.firstOrNull() == "input" }, "the retry's paid dispatch exceeds the original case budget")
    }

    @Test
    fun externalOverBudgetDispatchSettlesBeforeImmediateFinishStep() {
        val suite = suiteOf(caseOf("External over budget", step("Use one paid tool", maxToolCalls = 8)))
        val h = RunHarness(TestLibrary(suites = listOf(suite))).also { harness = it }
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(h.config(suite, externalLane(), toolLimit = 1)) })
        val laneId = h.coordinator.run(started.runId)!!.lanes.single().laneId
        runBlocking { withTimeout(AWAIT_MS) { while (h.coordinator.run(started.runId)?.lanes?.single()?.currentStepAction == null) delay(POLL_MS) } }

        val first = runBlocking { h.coordinator.laneToolCall(started.runId, laneId, "press_key", mapOf("key" to "HOME")) }
        val refused = runBlocking { h.coordinator.laneToolCall(started.runId, laneId, "press_key", mapOf("key" to "BACK")) }
        val finish = runBlocking { h.coordinator.laneToolCall(started.runId, laneId, "finish_step", mapOf("status" to "pass", "observation" to "pretend")) }
        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        assertTrue(first.toString().contains("ok=true") || first.toString().contains("{ok=true"), first.toString())
        assertTrue(refused.toString().contains("limit is exhausted"), refused.toString())
        assertTrue(
            finish.toString().contains("finished") || finish.toString().contains("budget") || finish.toString().contains("No case is active"),
            finish.toString(),
        )
        assertEquals(1, h.adb.shellCommands.count { it.firstOrNull() == "input" })
        assertEquals(CaseStatus.ERROR, run.caseResult("External over budget").status, run.toString())
    }

    @Test
    fun concurrentExternalPaidCallsCannotRaceFinishIntoASecondSettlement() {
        assumeFalse("the fixture host shell uses POSIX sleep/printf", System.getProperty("os.name").lowercase().contains("win"))
        val marker = File.createTempFile("dispatch-started", ".txt").also { it.delete() }
        val release = File.createTempFile("dispatch-release", ".txt").also { it.delete() }
        marker.deleteOnExit()
        release.deleteOnExit()
        val hold = hostScript(
            "hold_action",
            "printf started > '${marker.absolutePath}'; while [ ! -f '${release.absolutePath}' ]; do sleep 0.01; done",
            com.indagium.testing.model.ScriptPermission.AUTO,
        )
        val suite = suiteOf(caseOf("Concurrent", step("Use one paid tool", maxToolCalls = 8, timeoutMs = 5_000)))
        val h = RunHarness(TestLibrary(suites = listOf(suite), scripts = listOf(hold))).also { harness = it }
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(h.config(suite, externalLane(), toolLimit = 1)) })
        val laneId = h.coordinator.run(started.runId)!!.lanes.single().laneId
        runBlocking { withTimeout(AWAIT_MS) { while (h.coordinator.run(started.runId)?.lanes?.single()?.currentStepAction == null) delay(POLL_MS) } }

        var rejected: Any? = null
        var finishing: Any? = null
        val run = runBlocking {
            coroutineScope {
                val action = async { h.coordinator.laneToolCall(started.runId, laneId, "hold_action", emptyMap()) }
                withTimeout(AWAIT_MS) { while (!marker.exists()) delay(POLL_MS) }
                val denied = async { h.coordinator.laneToolCall(started.runId, laneId, "press_key", mapOf("key" to "HOME")) }
                val finish = async { h.coordinator.laneToolCall(started.runId, laneId, "finish_step", mapOf("status" to "pass", "observation" to "done")) }
                rejected = denied.await()
                finishing = finish.await()
                release.writeText("release")
                action.await()
            }
        }

        assertTrue(
            rejected.toString().contains("limit is exhausted") || rejected.toString().contains("step is finishing or paused"),
            rejected.toString(),
        )
        assertTrue(
            finishing.toString().contains("finished") || finishing.toString().contains("budget") ||
                finishing.toString().contains("still running") || finishing.toString().contains("stopping"),
            finishing.toString(),
        )
        runBlocking {
            h.coordinator.laneToolCall(started.runId, laneId, "press_key", mapOf("key" to "BACK"))
            h.coordinator.laneToolCall(started.runId, laneId, "finish_step", mapOf("status" to "pass", "observation" to "pretend"))
        }
        val terminal = runBlocking { h.coordinator.awaitFinished(started.runId) } ?: error("run not found")
        assertEquals(CaseStatus.ERROR, terminal.caseResult("Concurrent").status, terminal.toString())
        assertTrue(terminal.caseResult("Concurrent").steps.size <= 1, "a race cannot settle a stale attempt twice")
        marker.delete()
        release.delete()
    }

    @Test
    fun cancellingRunCancelsExternalScriptTreeAndPersistsCancelledActivity() {
        assumeFalse("the fixture host shell uses POSIX signals", System.getProperty("os.name").lowercase().contains("win"))
        val pidFile = File.createTempFile("external-cancel-pid", ".txt").also { it.delete() }
        val sentinel = File.createTempFile("external-cancel-sentinel", ".txt").also { it.delete() }
        pidFile.deleteOnExit()
        sentinel.deleteOnExit()
        val script = hostScript(
            "wait_for_cancel",
            """
                (trap '' TERM; while :; do sleep 0.05; done) & child=${'$'}!
                printf '%s' "${'$'}child" > '${pidFile.absolutePath}'
                wait "${'$'}child"
                sleep 2
                printf late > '${sentinel.absolutePath}'
            """.trimIndent(),
            com.indagium.testing.model.ScriptPermission.AUTO,
        )
        val suite = suiteOf(caseOf("Cancel external", step("Wait for cancellation", timeoutMs = 15_000, maxToolCalls = 5)))
        val h = RunHarness(TestLibrary(suites = listOf(suite), scripts = listOf(script))).also { harness = it }
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(h.config(suite, externalLane())) })
        val laneId = h.coordinator.run(started.runId)!!.lanes.single().laneId

        runBlocking {
            withTimeout(AWAIT_MS) {
                while (h.coordinator.run(started.runId)?.lanes?.single()?.currentStepAction == null) delay(POLL_MS)
            }
            val dispatch = async { runCatching { h.coordinator.laneToolCall(started.runId, laneId, script.toolName, emptyMap()) } }
            withTimeout(AWAIT_MS) { while (!pidFile.exists()) delay(POLL_MS) }
            val childPid = pidFile.readText().toLong()
            assertTrue(h.coordinator.cancel(started.runId), "the active run can be cancelled")
            withTimeout(AWAIT_MS) { while (h.coordinator.run(started.runId)?.status != RunStatus.CANCELLED) delay(POLL_MS) }
            val dispatchResult = dispatch.await()
            assertTrue(dispatchResult.isFailure, "the external request is cancelled with its run: $dispatchResult")
            delay(650)
            assertFalse(sentinel.exists(), "the supervised child cannot write after the run is cancelled")
            assertFalse(ProcessHandle.of(childPid).map { it.isAlive }.orElse(false), "the resistant descendant is gone")
        }
        val finished = runBlocking { h.coordinator.awaitFinished(started.runId) } ?: error("run not found")
        assertEquals(RunStatus.CANCELLED, finished.status)
        assertEquals(com.indagium.testing.model.LaneToolCallStatus.CANCELLED, finished.lanes.single().toolCalls.single().status)
        val activityPath = assertNotNull(finished.lanes.single().toolActivityPath)
        assertTrue(File(h.store.runDir(finished.id), activityPath).readText().contains("CANCELLED"))
        pidFile.delete()
        sentinel.delete()
    }

    @Test
    fun naturalRunCompletionStopsConcurrentPaidScriptButReturnsTerminalFinishAndSavesActivity() {
        assumeFalse("the fixture host shell uses POSIX signals", System.getProperty("os.name").lowercase().contains("win"))
        val pidFile = File.createTempFile("external-finish-pid", ".txt").also { it.delete() }
        val sentinel = File.createTempFile("external-finish-sentinel", ".txt").also { it.delete() }
        pidFile.deleteOnExit()
        sentinel.deleteOnExit()
        val script = hostScript(
            "wait_for_natural_finish",
            """
                (trap '' TERM; while :; do sleep 0.05; done) & child=${'$'}!
                printf '%s' "${'$'}child" > '${pidFile.absolutePath}'
                wait "${'$'}child"
                sleep 2
                printf late > '${sentinel.absolutePath}'
            """.trimIndent(),
            com.indagium.testing.model.ScriptPermission.AUTO,
        )
        val suite = suiteOf(caseOf("Finish external", step("Wait for completion", timeoutMs = 15_000, maxToolCalls = 5)))
        val h = RunHarness(TestLibrary(suites = listOf(suite), scripts = listOf(script))).also { harness = it }
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(h.config(suite, externalLane())) })
        val laneId = h.coordinator.run(started.runId)!!.lanes.single().laneId

        val (dispatchResult, finishReply) = runBlocking {
            coroutineScope {
                withTimeout(AWAIT_MS) {
                    while (h.coordinator.run(started.runId)?.lanes?.single()?.currentStepAction == null) delay(POLL_MS)
                }
                val dispatch = async { runCatching { h.coordinator.laneToolCall(started.runId, laneId, script.toolName, emptyMap()) } }
                withTimeout(AWAIT_MS) { while (!pidFile.exists()) delay(POLL_MS) }
                val reply = h.coordinator.laneToolCall(
                    started.runId, laneId, "finish_step", mapOf("status" to "pass", "observation" to "completed"),
                )
                reply to dispatch.await()
            }.let { (reply, dispatched) -> dispatched to reply }
        }
        assertTrue(finishReply.toString().contains("case_finished"), "terminal protocol reply is preserved: $finishReply")
        assertTrue(
            dispatchResult.isFailure || (dispatchResult.getOrNull() as? Map<*, *>)?.get("error") != null,
            "the still-running paid action is cancelled: $dispatchResult",
        )
        val finished = runBlocking { h.coordinator.awaitFinished(started.runId) } ?: error("run not found")
        assertEquals(RunStatus.PASSED, finished.status, finished.toString())
        val childPid = pidFile.readText().toLong()
        Thread.sleep(650)
        assertFalse(sentinel.exists(), "a paid command cannot outlive a naturally completed run")
        assertFalse(ProcessHandle.of(childPid).map { it.isAlive }.orElse(false), "the resistant child is reaped")
        val lane = finished.lanes.single()
        assertEquals(com.indagium.testing.model.LaneToolCallStatus.CANCELLED, lane.toolCalls.single { it.toolName == script.toolName }.status)
        assertEquals(com.indagium.testing.model.LaneToolCallStatus.SUCCEEDED, lane.toolCalls.single { it.toolName == "finish_step" }.status)
        val activity = File(h.store.runDir(finished.id), assertNotNull(lane.toolActivityPath)).readText()
        assertTrue(activity.contains("${script.toolName}") && activity.contains("CANCELLED"), activity)
        assertTrue(activity.contains("finish_step") && activity.contains("SUCCEEDED"), activity)
        pidFile.delete()
        sentinel.delete()
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
