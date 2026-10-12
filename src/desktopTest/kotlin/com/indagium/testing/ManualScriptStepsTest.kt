package com.indagium.testing

import com.indagium.ai.LlmProvider
import com.indagium.ai.LlmRequest
import com.indagium.ai.LlmRole
import com.indagium.ai.LlmStreamEvent
import com.indagium.ai.LlmToolCall
import com.indagium.ai.ModelDiscoveryResult
import com.indagium.ai.ProviderCapabilities
import com.indagium.edition.EditionLimits
import com.indagium.testing.device.DeviceStateReader
import com.indagium.testing.device.DeviceStateReading
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeInconclusivePolicy
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.ManualCompletion
import com.indagium.testing.model.ManualStepsOption
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.RecordedInput
import com.indagium.testing.model.RecordedInputEvent
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.ScriptCall
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepCompletion
import com.indagium.testing.model.StepIntent
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.newCheckId
import com.indagium.testing.model.newStepId
import com.indagium.testing.run.LaneAgentFactory
import com.indagium.testing.run.LaneDeviceOpener
import com.indagium.testing.run.ManualDecision
import com.indagium.testing.run.ManualResolveResult
import com.indagium.testing.run.ProviderLaneAgent
import com.indagium.testing.run.StartRunResult
import com.indagium.testing.run.validateRun
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeFalse
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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
private const val SECOND_SERIAL = "SER-2"

/** A model that answers by what it is asked, not by how many requests came before: a handed-over step restarts the agent at a new place. */
private class StepAwareProvider : LlmProvider {
    val prompts = CopyOnWriteArrayList<String>()
    val toolResults = CopyOnWriteArrayList<String>()
    private var callNumber = 0

    override val capabilities = ProviderCapabilities(streaming = true, toolCalls = true, modelDiscovery = false)

    override suspend fun listModels(): ModelDiscoveryResult = ModelDiscoveryResult.Unavailable("not used")

    override fun streamChat(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val last = request.messages.lastOrNull { it.role == LlmRole.TOOL }?.content.orEmpty()
        if (last.isEmpty()) prompts += request.messages.first { it.role == LlmRole.USER }.content.orEmpty() else toolResults += last
        when {
            last.contains("case_finished") -> {
                emit(LlmStreamEvent.TextDelta("The case is over."))
                emit(LlmStreamEvent.Completed)
            }
            // The app took over the next step: this run is closed from outside, so wait for that.
            last.contains("handoff") -> delay(AWAIT_MS * 6)
            else -> {
                emit(LlmStreamEvent.ToolCall(LlmToolCall("call-${++callNumber}", "finish_step", """{"status":"pass","observation":"done by the agent"}""")))
                emit(LlmStreamEvent.Completed)
            }
        }
    }
}

/** SCRIPT and MANUAL steps in a run: performed by the run itself, between the agent's steps, with no agent started for them. */
class ManualScriptStepsTest {
    private var harness: RunHarness? = null
    private val adb = ScriptedAdbRunner()

    @AfterTest
    fun tearDown() {
        harness?.close()
    }

    private fun scriptStep(
        script: TestScript,
        exitCode: Int? = 0,
        stdoutContains: String? = null,
        checks: List<StepCheck> = emptyList(),
        onFailure: OnFailure = OnFailure.STOP_CASE,
    ) = TestStep(
        newStepId(), "Run ${script.toolName}", checks = checks, timeoutMs = 20_000L, retries = 0, onFailure = onFailure,
        kind = StepKind.SCRIPT, script = ScriptCall(script.id, exitCode = exitCode, stdoutContains = stdoutContains),
    )

    private fun manualStep(
        action: String = "Plug the USB stick into the phone",
        completion: ManualCompletion = ManualCompletion.TESTER_OR_CHECKS,
        checks: List<StepCheck> = emptyList(),
        script: TestScript? = null,
        timeoutMs: Long = 20_000L,
        onFailure: OnFailure = OnFailure.STOP_CASE,
    ) = TestStep(
        newStepId(), action, timeoutMs = timeoutMs, retries = 0, onFailure = onFailure, checks = checks, kind = StepKind.MANUAL,
        manualCompletion = completion, script = script?.let { ScriptCall(it.id) },
    )

    private fun usbCheck(withinMs: Long = 100L) = StepCheck.DeviceState(newCheckId(), "usb.devices", "0781:5581", withinMs)

    private fun start(
        suite: TestSuite,
        vararg scripts: TestScript,
        provider: LlmProvider = TurnProvider(emptyList()),
        openDevice: LaneDeviceOpener? = null,
        lanes: List<LaneConfig> = listOf(agentLane()),
        configure: (RunConfig) -> RunConfig = { it },
    ): Pair<RunHarness, StartRunResult.Started> {
        val h = RunHarness(
            libraryOf(suite, *scripts), (provider as? TurnProvider) ?: TurnProvider(emptyList()), tuning = FAST_TUNING.copy(manualPollMs = 30L),
            agentFactory = (provider as? StepAwareProvider)?.let { aware -> LaneAgentFactory { chosen, _ -> ProviderLaneAgent(aware, chosen) } },
            openDevice = openDevice,
        ).also { harness = it }
        val started = runBlocking { h.coordinator.start(configure(h.config(suite).copy(lanes = lanes))) }
        return h to assertIs<StartRunResult.Started>(started, started.toString())
    }

    private fun RunHarness.finished(started: StartRunResult.Started): TestRun =
        runBlocking { withTimeout(AWAIT_MS) { assertNotNull(coordinator.awaitFinished(started.runId)) } }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        runBlocking { withTimeout(AWAIT_MS) { while (!condition()) delay(POLL_MS) } }
        check(condition()) { what }
    }

    private fun RunHarness.awaitWaiting(count: Int = 1) = awaitTrue("$count lane(s) waiting for a tester") { coordinator.waitingSteps().size >= count }

    /** Lanes whose device answers device-state checks with [reader]. */
    private fun deviceWith(reader: DeviceStateReader) = LaneDeviceOpener { _, laneDir, _ ->
        openFixtureSession(adb, laneDir).also { it.deviceStateReader = reader }
    }

    private fun noWindows() = assumeFalse(System.getProperty("os.name").lowercase().contains("win"))

    // ── Script steps ────────────────────────────────────────────────

    @Test
    fun aScriptStepRunsItsScriptWithoutStartingAnAgentAndRecordsWhoDidIt() {
        noWindows()
        val marker = File.createTempFile("script-step", ".txt").also { it.delete() }
        val script = hostScript("hub_on", "printf ran > '${marker.absolutePath}'; echo hub is on", ScriptPermission.AUTO)
        val suite = suiteOf(caseOf("Hub", scriptStep(script)))
        val provider = TurnProvider(emptyList())
        val (h, started) = start(suite, script, provider = provider)

        val run = h.finished(started)

        assertEquals(RunStatus.PASSED, run.status, run.toString())
        val result = run.caseResult("Hub").steps.single()
        assertEquals(StepStatus.PASS, result.status)
        assertEquals(StepKind.SCRIPT, result.kind)
        assertEquals(StepCompletion.SCRIPT, result.completedBy)
        assertNull(result.agentClaim, "no agent said anything about this step")
        assertTrue(result.observation.contains("exit code 0") && result.observation.contains("hub is on"), result.observation)
        assertEquals("ran", marker.readText())
        assertTrue(provider.requests.isEmpty(), "no AI agent is started for a script step")
        marker.delete()
    }

    @Test
    fun aScriptStepFailsOnANonZeroExitAnUnmetExitCodeOrMissingOutput() {
        noWindows()
        val failing = hostScript("fails", "echo hay; exit 3", ScriptPermission.AUTO)
        val suite = suiteOf(
            caseOf("Default exit", scriptStep(failing)),
            caseOf("Expected exit", scriptStep(failing, exitCode = 3)),
            caseOf("Any exit", scriptStep(failing, exitCode = null)),
            caseOf("Output", scriptStep(failing, exitCode = 3, stdoutContains = "needle")),
            caseOf("Output found", scriptStep(failing, exitCode = 3, stdoutContains = "hay")),
        )
        val (h, started) = start(suite, failing)

        val run = h.finished(started)

        assertEquals(StepStatus.FAIL, run.caseResult("Default exit").steps.single().status)
        assertTrue(run.caseResult("Default exit").steps.single().observation.contains("expected exit code 0"))
        assertEquals(StepStatus.PASS, run.caseResult("Expected exit").steps.single().status)
        assertEquals(StepStatus.PASS, run.caseResult("Any exit").steps.single().status)
        assertEquals(StepStatus.FAIL, run.caseResult("Output").steps.single().status)
        assertTrue(run.caseResult("Output").steps.single().observation.contains("needle"))
        assertEquals(StepStatus.PASS, run.caseResult("Output found").steps.single().status)
        assertEquals(RunStatus.FAILED, run.status)
    }

    @Test
    fun aScriptStepsOtherChecksRunAfterTheScript() {
        noWindows()
        val script = hostScript("fine", "true", ScriptPermission.AUTO)
        val suite = suiteOf(caseOf("Then checks", scriptStep(script, checks = listOf(logAppears("never logged", withinMs = 100L)))))
        val (h, started) = start(suite, script)

        val result = h.finished(started).caseResult("Then checks").steps.single()

        assertEquals(StepStatus.FAIL, result.status, "the script was fine but the log line never came")
        assertEquals(StepCompletion.SCRIPT, result.completedBy)
        assertEquals(listOf("logAppears"), result.checks.map { it.kind })
    }

    @Test
    fun anAskScriptStepWaitsForTheUsersAnswerAndADeniedOneIsBlockedAndNeverRuns() {
        noWindows()
        val marker = File.createTempFile("ask-step", ".txt").also { it.delete() }
        val script = hostScript("ask_hub", "printf ran > '${marker.absolutePath}'", ScriptPermission.ASK)
        val suite = suiteOf(caseOf("Denied", scriptStep(script)), caseOf("Allowed", scriptStep(script)))
        val (h, started) = start(suite, script)

        awaitTrue("a confirmation card") { h.coordinator.pendingConfirmations().isNotEmpty() }
        val card = h.coordinator.pendingConfirmations().single()
        assertEquals("ask_hub", card.toolName)
        assertTrue(card.description.contains("Run script 'ask_hub'"), card.description)
        assertTrue(h.coordinator.resolveConfirmation(started.runId, card.confirmationId, false))
        assertFalse(marker.exists(), "a denied script is never run")

        awaitTrue("the second card") { h.coordinator.pendingConfirmations().isNotEmpty() }
        assertTrue(h.coordinator.resolveConfirmation(started.runId, h.coordinator.pendingConfirmations().single().confirmationId, true))
        val run = h.finished(started)

        assertEquals(StepStatus.BLOCKED, run.caseResult("Denied").steps.single().status)
        assertEquals(StepStatus.PASS, run.caseResult("Allowed").steps.single().status)
        assertEquals("ran", marker.readText())
        marker.delete()
    }

    @Test
    fun aDeclinedScriptPermissionIsNotAskedAgainByAutomaticCaseRetry() {
        noWindows()
        val marker = File.createTempFile("ask-no-retry", ".txt").also { it.delete() }
        val script = hostScript("ask_once", "printf ran > '${marker.absolutePath}'", ScriptPermission.ASK)
        val suite = suiteOf(caseOf("Declined", scriptStep(script)))
        val (h, started) = start(suite, script) { it.copy(caseRetries = 1) }

        awaitTrue("the permission card") { h.coordinator.pendingConfirmations().isNotEmpty() }
        val card = h.coordinator.pendingConfirmations().single()
        assertTrue(h.coordinator.resolveConfirmation(started.runId, card.confirmationId, false))
        val run = h.finished(started)
        val result = run.caseResult("Declined")

        assertEquals(CaseStatus.BLOCKED, result.status)
        assertEquals(1, result.caseAttempt)
        assertTrue(result.priorAttempts.isEmpty())
        assertTrue(result.steps.single().automaticRetryExcluded)
        assertTrue(result.steps.single().observation.contains("not allowed"))
        assertTrue(h.coordinator.pendingConfirmations().isEmpty())
        assertFalse(marker.exists(), "the declined script is never run")
        marker.delete()
    }

    @Test
    fun aMissingScriptLibraryEntryIsNotRetriedAsAnEnvironmentalBlocker() {
        noWindows()
        val declared = hostScript("missing_library_entry", "printf should-not-run")
        val suite = suiteOf(caseOf("Missing", scriptStep(declared)))
        val h = RunHarness(libraryOf(suite)).also { harness = it }

        val rejected = assertIs<StartRunResult.Rejected>(runBlocking { h.coordinator.start(h.config(suite).copy(caseRetries = 1)) })
        assertTrue(rejected.errors.any { "is not in the library" in it }, rejected.toString())
        assertTrue(runBlocking { h.coordinator.listRuns() }.isEmpty(), "invalid library state is rejected before any case attempt")
    }

    @Test
    fun aSetupOnlyScriptUsedAsAStepIsNotRetriedAsAnEnvironmentalBlocker() {
        noWindows()
        val script = hostScript("setup_only_step", "printf should-not-run", ScriptPermission.SETUP_TEARDOWN_ONLY)
        val suite = suiteOf(caseOf("Hook only", scriptStep(script)))
        val h = RunHarness(libraryOf(suite, script)).also { harness = it }

        val rejected = assertIs<StartRunResult.Rejected>(runBlocking { h.coordinator.start(h.config(suite).copy(caseRetries = 1)) })
        assertTrue(rejected.errors.any { "limited to setup and teardown" in it }, rejected.toString())
        assertTrue(runBlocking { h.coordinator.listRuns() }.isEmpty(), "invalid use is rejected before any case attempt")
    }

    // ── Manual steps ────────────────────────────────────────────────

    @Test
    fun aManualStepWaitsForTheTesterAndCompletesWhenTheyAnswerDone() {
        val suite = suiteOf(caseOf("Manual", manualStep("Plug the USB stick into the phone")))
        val provider = TurnProvider(emptyList())
        val (h, started) = start(suite, provider = provider)

        h.awaitWaiting()
        val waiting = h.coordinator.waitingSteps().single()
        assertEquals("Plug the USB stick into the phone", waiting.wait.instructions)
        assertEquals(FIXTURE_SERIAL, waiting.wait.deviceSerial)
        assertEquals(RunStatus.RUNNING, h.coordinator.run(started.runId)?.status, "the run waits for the tester")
        assertEquals(ManualResolveResult.RESOLVED, h.coordinator.resolveManualStep(started.runId, waiting.laneId, ManualDecision.DONE, "plugged in"))
        val run = h.finished(started)

        val result = run.caseResult("Manual").steps.single()
        assertEquals(StepStatus.PASS, result.status)
        assertEquals(StepKind.MANUAL, result.kind)
        assertEquals(StepCompletion.TESTER, result.completedBy)
        assertEquals(ManualCompletion.TESTER_OR_CHECKS, result.manualCompletion)
        assertTrue(result.observation.contains("plugged in"), result.observation)
        assertNotNull(result.screenshotPath, "evidence: a screenshot of the device when it was done")
        assertNotNull(result.logEndOffset)
        assertTrue(h.coordinator.waitingSteps().isEmpty())
        assertTrue(provider.requests.isEmpty(), "no AI agent is started for a manual step")
        assertEquals(ManualResolveResult.NOT_WAITING, h.coordinator.resolveManualStep(started.runId, waiting.laneId, ManualDecision.DONE))
    }

    @Test
    fun aManualStepIsDetectedByItsChecksWhenTheyBecomeTrue() {
        val reads = AtomicInteger()
        val reader = DeviceStateReader {
            if (reads.incrementAndGet() >= 4) DeviceStateReading.Value("0781:5581 Ultra") else DeviceStateReading.Value("")
        }
        val suite = suiteOf(caseOf("By checks", manualStep(completion = ManualCompletion.CHECKS_ONLY, checks = listOf(usbCheck()))))
        val (h, started) = start(suite, openDevice = deviceWith(reader))

        val run = h.finished(started)

        val result = run.caseResult("By checks").steps.single()
        assertEquals(StepStatus.PASS, result.status, result.toString())
        assertEquals(StepCompletion.CHECKS, result.completedBy)
        assertEquals(listOf(CheckStatus.PASS), result.checks.map { it.status })
        assertTrue(reads.get() >= 4, "the state was read until it matched")
    }

    @Test
    fun aStepCompletedByChecksOnlyCannotBeAnsweredDoneButCanBeFailed() {
        val reader = DeviceStateReader { DeviceStateReading.Value("") }
        val suite = suiteOf(caseOf("Checks only", manualStep(completion = ManualCompletion.CHECKS_ONLY, checks = listOf(usbCheck()))))
        val (h, started) = start(suite, openDevice = deviceWith(reader))

        h.awaitWaiting()
        val info = h.coordinator.waitingSteps().single()
        assertFalse(info.wait.testerCanComplete)
        assertEquals(ManualResolveResult.CHECKS_ONLY, h.coordinator.resolveManualStep(started.runId, info.laneId, ManualDecision.DONE))
        assertEquals(ManualResolveResult.RESOLVED, h.coordinator.resolveManualStep(started.runId, info.laneId, ManualDecision.FAILED, "it never mounted"))

        val result = h.finished(started).caseResult("Checks only").steps.single()
        assertEquals(StepStatus.FAIL, result.status)
        assertEquals(StepCompletion.TESTER, result.completedBy)
        assertTrue(result.observation.contains("it never mounted"))
    }

    @Test
    fun aTesterWhoSaysDoneOverridesTheCompletionChecksButATesterOnlyStepStillVerifiesThem() {
        val reader = DeviceStateReader { DeviceStateReading.Value("") }
        val open = deviceWith(reader)
        val suite = suiteOf(
            caseOf("Either", manualStep("Either way", completion = ManualCompletion.TESTER_OR_CHECKS, checks = listOf(usbCheck()))),
            caseOf("Tester only", manualStep("Tester only", completion = ManualCompletion.TESTER_ONLY, checks = listOf(usbCheck()))),
        )
        val (h, started) = start(suite, openDevice = open)

        h.awaitWaiting()
        h.coordinator.resolveManualStep(started.runId, h.coordinator.waitingSteps().single().laneId, ManualDecision.DONE)
        awaitTrue("the second step waits") { h.coordinator.waitingSteps().singleOrNull()?.wait?.instructions == "Tester only" }
        h.coordinator.resolveManualStep(started.runId, h.coordinator.waitingSteps().single().laneId, ManualDecision.DONE)
        val run = h.finished(started)

        assertEquals(StepStatus.PASS, run.caseResult("Either").steps.single().status, "the tester's word replaces the completion checks")
        val verified = run.caseResult("Tester only").steps.single()
        assertEquals(StepStatus.FAIL, verified.status, "a tester-only step still asserts its checks after Done")
        assertEquals(StepCompletion.TESTER, verified.completedBy)
    }

    @Test
    fun aManualStepNobodyCompletesIsNotConfirmedAndBlamedOnTheStepNotTheApp() {
        val suite = suiteOf(caseOf("Timeout", manualStep(timeoutMs = 250L, onFailure = OnFailure.CONTINUE), manualStep("Second", timeoutMs = 250L)))
        val (h, started) = start(suite)

        val run = h.finished(started)

        val steps = run.caseResult("Timeout").steps
        assertEquals(listOf(StepStatus.TIMEOUT, StepStatus.TIMEOUT), steps.map { it.status })
        assertNull(steps[0].completedBy)
        assertEquals(JudgeClassification.AGENT_OR_STEP_PROBLEM, steps[0].classification)
        assertTrue(steps[0].observation.startsWith("Not confirmed"), steps[0].observation)
        assertEquals(1, steps[0].attempts, "a manual step is never retried")
        assertEquals(CaseStatus.FAIL, run.caseResult("Timeout").status)
        assertEquals(RunStatus.FAILED, run.status)
    }

    @Test
    fun theTesterCanSkipOrFailAManualStepAndTheCaseGoesOnAfterASkip() {
        val suite = suiteOf(caseOf("Skip", manualStep("First one"), manualStep("Second one")), caseOf("Fail", manualStep("Fails")))
        val (h, started) = start(suite)

        h.awaitWaiting()
        h.coordinator.resolveManualStep(started.runId, h.coordinator.waitingSteps().single().laneId, ManualDecision.SKIP)
        awaitTrue("the next manual step") { h.coordinator.waitingSteps().singleOrNull()?.wait?.instructions == "Second one" }
        h.coordinator.resolveManualStep(started.runId, h.coordinator.waitingSteps().single().laneId, ManualDecision.DONE)
        awaitTrue("the next case") { h.coordinator.waitingSteps().singleOrNull()?.wait?.instructions == "Fails" }
        h.coordinator.resolveManualStep(started.runId, h.coordinator.waitingSteps().single().laneId, ManualDecision.FAILED)
        val run = h.finished(started)

        val skip = run.caseResult("Skip")
        assertEquals(listOf(StepStatus.SKIPPED, StepStatus.PASS), skip.steps.map { it.status })
        assertEquals(StepCompletion.SKIPPED, skip.steps[0].completedBy)
        assertEquals(CaseStatus.PASS, skip.status, "a skipped step does not fail the case")
        assertEquals(StepStatus.FAIL, run.caseResult("Fail").steps.single().status)
    }

    @Test
    fun theSkipOptionReportsManualStepsAsSkippedWithoutWaiting() {
        val suite = suiteOf(caseOf("Skipped", manualStep("A"), manualStep("B")))
        val (h, started) = start(suite) { it.copy(manualSteps = ManualStepsOption.SKIP) }

        val run = h.finished(started)

        assertEquals(listOf(StepStatus.SKIPPED, StepStatus.SKIPPED), run.caseResult("Skipped").steps.map { it.status })
        assertEquals(CaseStatus.PASS, run.caseResult("Skipped").status)
        assertEquals(RunStatus.PASSED, run.status)
        assertEquals(ManualStepsOption.SKIP, run.config.manualSteps)
    }

    @Test
    fun anAutomationScriptReplacesWaitingUnlessTheRunInsistsOnATester() {
        noWindows()
        val marker = File.createTempFile("manual-script", ".txt").also { it.delete() }
        val script = hostScript("hub_cycle", "printf ran >> '${marker.absolutePath}'", ScriptPermission.AUTO)
        val suite = suiteOf(caseOf("Substituted", manualStep("Power the hub", script = script)))

        val (h, started) = start(suite, script)
        val run = h.finished(started)
        val result = run.caseResult("Substituted").steps.single()
        assertEquals(StepStatus.PASS, result.status)
        assertEquals(StepKind.MANUAL, result.kind)
        assertEquals(StepCompletion.SCRIPT, result.completedBy)
        assertEquals("ran", marker.readText())
        h.close()

        val (waiting, startedWaiting) = start(suite, script) { it.copy(manualSteps = ManualStepsOption.WAIT_FOR_TESTER) }
        waiting.awaitWaiting()
        assertEquals("ran", marker.readText(), "the script is not used when the run waits for a tester")
        waiting.coordinator.resolveManualStep(startedWaiting.runId, waiting.coordinator.waitingSteps().single().laneId, ManualDecision.DONE)
        assertEquals(StepCompletion.TESTER, waiting.finished(startedWaiting).caseResult("Substituted").steps.single().completedBy)
        marker.delete()
    }

    @Test
    fun aFailingAutomationScriptLeavesTheStepToTheTester() {
        noWindows()
        val script = hostScript("broken_hub", "exit 5", ScriptPermission.AUTO)
        val suite = suiteOf(caseOf("Fallback", manualStep("Power the hub", script = script)))
        val (h, started) = start(suite, script)

        h.awaitWaiting()
        val wait = h.coordinator.waitingSteps().single().wait
        assertTrue(wait.hint.contains("automation script did not do it"), wait.hint)
        h.coordinator.resolveManualStep(started.runId, h.coordinator.waitingSteps().single().laneId, ManualDecision.DONE)

        val result = h.finished(started).caseResult("Fallback").steps.single()
        assertEquals(StepStatus.PASS, result.status)
        assertEquals(StepCompletion.TESTER, result.completedBy)
    }

    @Test
    fun everyLaneWaitsOnItsOwnDevice() {
        val adbTwo = ScriptedAdbRunner()
        val opener = LaneDeviceOpener { serial, laneDir, _ ->
            if (serial == SECOND_SERIAL) TestDeviceSession.open(SECOND_SERIAL, laneDir, fixtureTools(adbTwo), adbTwo) else openFixtureSession(adb, laneDir)
        }
        val suite = suiteOf(caseOf("Two phones", manualStep("Plug it in")))
        val (h, started) = start(suite, openDevice = opener, lanes = listOf(agentLane(), agentLane(serial = SECOND_SERIAL)))

        h.awaitWaiting(count = 2)
        val waits = h.coordinator.waitingSteps()
        assertEquals(setOf(FIXTURE_SERIAL, SECOND_SERIAL), waits.map { it.wait.deviceSerial }.toSet())
        val first = waits.first { it.wait.deviceSerial == FIXTURE_SERIAL }

        h.coordinator.resolveManualStep(started.runId, first.laneId, ManualDecision.DONE)
        awaitTrue("only the second lane still waits") { h.coordinator.waitingSteps().map { it.wait.deviceSerial } == listOf(SECOND_SERIAL) }
        assertEquals(RunStatus.RUNNING, h.coordinator.run(started.runId)?.status)
        h.coordinator.resolveManualStep(started.runId, h.coordinator.waitingSteps().single().laneId, ManualDecision.DONE)
        val run = h.finished(started)

        assertEquals(RunStatus.PASSED, run.status)
        assertEquals(listOf(StepStatus.PASS, StepStatus.PASS), run.lanes.map { it.cases.single().steps.single().status })
    }

    @Test
    fun anExternalLaneWaitsForManualStepsToo() {
        val suite = suiteOf(caseOf("External", manualStep("Plug it in")))
        val (h, started) = start(suite, lanes = listOf(externalLane()))

        h.awaitWaiting()
        h.coordinator.resolveManualStep(started.runId, started.laneIds.single(), ManualDecision.DONE)

        assertEquals(RunStatus.PASSED, h.finished(started).status)
    }

    @Test
    fun aCaseThatMixesKindsHandsOverToTheTesterAndThenStartsTheAgentAtTheNextAiStep() {
        val provider = StepAwareProvider()
        val suite = suiteOf(caseOf("Mixed", step("Open the app"), manualStep("Plug in the stick"), step("Look at the files")))
        val (h, started) = start(suite, provider = provider)

        awaitTrue("the handover to the tester") { h.coordinator.waitingSteps().isNotEmpty() }
        assertEquals("Plug in the stick", h.coordinator.waitingSteps().single().wait.instructions)
        assertTrue(provider.toolResults.any { it.contains("handoff") }, "the agent was told the next step is not its own: ${provider.toolResults}")
        h.coordinator.resolveManualStep(started.runId, h.coordinator.waitingSteps().single().laneId, ManualDecision.DONE)
        val run = h.finished(started)

        assertEquals(RunStatus.PASSED, run.status, run.toString())
        val steps = run.caseResult("Mixed").steps
        assertEquals(listOf(StepKind.AGENT, StepKind.MANUAL, StepKind.AGENT), steps.map { it.kind })
        assertEquals(listOf(StepStatus.PASS, StepStatus.PASS, StepStatus.PASS), steps.map { it.status })
        assertEquals(2, provider.prompts.size, "one agent run before the manual step and one after")
        assertTrue(provider.prompts[0].contains("Current step 1 of 3") && provider.prompts[0].contains("Open the app"))
        assertTrue(provider.prompts[1].contains("Current step 3 of 3") && provider.prompts[1].contains("Look at the files"))
        assertTrue(provider.prompts[1].contains("manual step, done by tester"), "the new run is told what the tester did: ${provider.prompts[1]}")
        val shownAsItsOwn = provider.prompts.any { it.contains("Plug in the stick") && it.contains("Current step 2") }
        assertFalse(shownAsItsOwn, "the agent is never shown the manual step as its own")
    }

    @Test
    fun aScriptStepBetweenAgentStepsRunsAndTheAgentCarriesOn() {
        noWindows()
        val script = hostScript("between", "true", ScriptPermission.AUTO)
        val provider = StepAwareProvider()
        val suite = suiteOf(caseOf("Sandwich", scriptStep(script), step("Open the app"), scriptStep(script), step("Close the app")))
        val (h, started) = start(suite, script, provider = provider)

        val run = h.finished(started)

        assertEquals(RunStatus.PASSED, run.status, run.toString())
        assertEquals(listOf(StepKind.SCRIPT, StepKind.AGENT, StepKind.SCRIPT, StepKind.AGENT), run.caseResult("Sandwich").steps.map { it.kind })
        assertEquals(2, provider.prompts.size)
        assertTrue(provider.prompts[0].contains("Current step 2 of 4"))
        assertTrue(provider.prompts[1].contains("Current step 4 of 4"))
    }

    private fun keyReplay(retries: Int = 0): TestStep = replayStep(
        action = "Press Back", expected = "The prior screen is shown",
        input = RecordedInput(listOf(RecordedInputEvent.Key("BACK")), settleMs = 500), retries = retries,
    )

    @Test
    fun aReplayBetweenAgentStepsInjectsThenChecksAndHandsBackToTheAgent() {
        val provider = StepAwareProvider()
        val check = StepCheck.DeviceState(newCheckId(), "wifi.enabled", "true", 100L)
        val replay = keyReplay().copy(checks = listOf(check))
        val reader = DeviceStateReader {
            DeviceStateReading.Value((adb.shellCommands.any { it == listOf("input", "keyevent", "KEYCODE_BACK") }).toString())
        }
        val suite = suiteOf(caseOf("Replay sandwich", step("Open the app"), replay, step("Inspect the result")))
        val (h, started) = start(suite, provider = provider, openDevice = deviceWith(reader))

        val run = h.finished(started)
        val results = run.caseResult("Replay sandwich").steps
        assertEquals(listOf(StepStatus.PASS, StepStatus.PASS, StepStatus.PASS), results.map { it.status })
        assertEquals(StepCompletion.REPLAY, results[1].completedBy)
        assertEquals(CheckStatus.PASS, results[1].checks.single().status, "the deterministic check ran after injection")
        assertTrue(results[1].note.orEmpty().contains("not independently judged"), results[1].note)
        assertFalse(results[1].note.orEmpty().contains("Passed by the agent"))
        assertEquals(1, adb.shellCommands.count { it == listOf("input", "keyevent", "KEYCODE_BACK") })
        assertEquals(2, provider.prompts.size, "the agent runs before and after Replay, but never for Replay itself")
        assertTrue(provider.prompts[1].contains("Current step 3 of 3"))
    }

    @Test
    fun aFailedReplayIsNotRetriedAndEvenAJudgePassCannotMakeTheInjectionPass() {
        adb.shellExitCode = 1
        val replay = keyReplay(retries = 2).copy(checks = listOf(StepCheck.AskJudge(newCheckId(), "Is the prior screen shown?")))
        val suite = suiteOf(caseOf("Failed replay", replay))
        val judge = ScriptedJudgeProvider { JudgeAnswer("pass", "unknown", "The picture looks right.") }
        val h = RunHarness(
            libraryOf(suite), profile = profileOf(LANE_A_PROFILE_ID), extraProfiles = listOf(judgeProfile),
            agentFactory = agentsByProfile(mapOf(
                LANE_A_PROFILE_ID to AutoAgentProvider(emptyList()), JUDGE_PROFILE_ID to judge,
            )),
            openDevice = LaneDeviceOpener { _, laneDir, _ -> openFixtureSession(adb, laneDir) },
        ).also { harness = it }
        val config = h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(judgeProfileId = JUDGE_PROFILE_ID, judgeMode = JudgeMode.EVERY_STEP.wire)
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        val result = h.finished(started).caseResult("Failed replay").steps.single()

        assertEquals(StepStatus.FAIL, result.status)
        assertEquals(1, result.attempts, "a partially injected action must not be retried automatically")
        assertEquals(JudgeVerdict.PASS, result.judge?.verdict)
        assertEquals(1, adb.shellCommands.count { it == listOf("input", "keyevent", "KEYCODE_BACK") })
        assertFalse(result.note.orEmpty().contains("agent"), "the report never attributes replay to an agent: ${result.note}")
    }

    @Test
    fun aJudgeFailureAfterSuccessfulReplayDoesNotReportAnAgentDisagreement() {
        val replay = keyReplay().copy(checks = listOf(StepCheck.AskJudge(newCheckId(), "Is the prior screen shown?")))
        val suite = suiteOf(caseOf("Judged replay", replay))
        val judge = ScriptedJudgeProvider { JudgeAnswer("fail", "app_defect", "The prior screen is absent.") }
        val h = RunHarness(
            libraryOf(suite), profile = profileOf(LANE_A_PROFILE_ID), extraProfiles = listOf(judgeProfile),
            agentFactory = agentsByProfile(mapOf(
                LANE_A_PROFILE_ID to AutoAgentProvider(emptyList()), JUDGE_PROFILE_ID to judge,
            )),
            openDevice = LaneDeviceOpener { _, laneDir, _ -> openFixtureSession(adb, laneDir) },
        ).also { harness = it }
        val config = h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(judgeProfileId = JUDGE_PROFILE_ID, judgeMode = JudgeMode.EVERY_STEP.wire)
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        val result = h.finished(started).caseResult("Judged replay").steps.single()

        assertEquals(StepStatus.FAIL, result.status)
        assertEquals(JudgeVerdict.FAIL, result.judge?.verdict)
        assertEquals(StepCompletion.REPLAY, result.completedBy)
        assertFalse(result.note.orEmpty().contains("agent", ignoreCase = true), result.note)
    }

    @Test
    fun anInconclusiveJudgeKeepsReplayPassWithoutAttributingItToAnAgent() {
        val replay = keyReplay()
        val suite = suiteOf(caseOf("Inconclusive replay", replay))
        val judge = ScriptedJudgeProvider { JudgeAnswer("inconclusive", "unknown", "The prior screen is unclear.") }
        val h = RunHarness(
            libraryOf(suite), profile = profileOf(LANE_A_PROFILE_ID), extraProfiles = listOf(judgeProfile),
            agentFactory = agentsByProfile(mapOf(
                LANE_A_PROFILE_ID to AutoAgentProvider(emptyList()), JUDGE_PROFILE_ID to judge,
            )),
            openDevice = LaneDeviceOpener { _, laneDir, _ -> openFixtureSession(adb, laneDir) },
        ).also { harness = it }
        val config = h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(
            judgeProfileId = JUDGE_PROFILE_ID,
            judgeMode = JudgeMode.EVERY_STEP.wire,
            judgeInconclusivePolicy = JudgeInconclusivePolicy.KEEP_AGENT_PASS,
        )
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        val result = h.finished(started).caseResult("Inconclusive replay").steps.single()

        assertEquals(StepStatus.PASS, result.status)
        assertEquals(JudgeVerdict.INCONCLUSIVE, result.judge?.verdict)
        assertTrue(result.note.orEmpty().contains("execution pass was retained"), result.note)
        assertFalse(result.note.orEmpty().contains("agent", ignoreCase = true), result.note)
    }

    @Test
    fun aLoadedReplayWithEnsureIntentOrPreconditionIsRefusedBeforeRunning() {
        listOf(keyReplay().copy(intent = StepIntent.ENSURE_STATE), keyReplay().copy(precondition = "The tile is off")).forEach { invalid ->
            val suite = suiteOf(caseOf("Invalid replay", invalid))
            val config = RunConfig(suite.id, null, listOf(agentLane()))
            val validation = validateRun(config, libraryOf(suite), EditionLimits.UNLIMITED, listOf(testProfile)) { "" }
            assertTrue(validation.errors.any { it.contains("replay step", ignoreCase = true) }, validation.errors.toString())
        }
    }

    @Test
    fun theManualStepsOptionIsFrozenIntoTheRunAndCountedInWarnings() {
        val suite = suiteOf(caseOf("Warn", manualStep("A")))
        val (h, started) = start(suite) { it.copy(manualSteps = ManualStepsOption.SKIP) }

        h.finished(started)

        assertTrue(started.warnings.any { it.contains("will be skipped") }, started.warnings.toString())
        assertEquals(ManualStepsOption.SKIP, h.coordinator.run(started.runId)?.config?.manualSteps)
    }
}
