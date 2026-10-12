@file:Suppress("MagicNumber", "ktlint:standard:max-line-length", "MaxLineLength")

package com.indagium.testing

import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.JudgeTiming
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.VerificationState
import com.indagium.testing.model.agentJudgeDisagrees
import com.indagium.testing.run.StartRunResult
import com.indagium.testing.run.failedCaseIds
import com.indagium.testing.run.interruptedCaseIds
import com.indagium.testing.store.decodeRunFile
import com.indagium.testing.store.encodeRunFile
import com.indagium.ui.runVerdict
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val AWAIT_MS = 20_000L
private const val POLL_MS = 10L

/**
 * What a run reports when it did not simply pass: a step that timed out although the action worked, a run the user cancelled, an
 * agent and a judge that disagree. Every run here is synthetic (a scripted agent and judge on a fake adb).
 */
class RunOutcomeHonestyTest {
    private var harness: RunHarness? = null

    @AfterTest
    fun tearDown() {
        harness?.close()
    }

    private fun harnessWith(suite: com.indagium.testing.model.TestSuite, agent: TurnProvider, judge: ScriptedJudgeProvider?): RunHarness =
        RunHarness(
            libraryOf(suite),
            profile = profileOf(LANE_A_PROFILE_ID),
            extraProfiles = listOf(judgeProfile),
            agentFactory = agentsByProfile(buildMap { put(LANE_A_PROFILE_ID, agent); if (judge != null) put(JUDGE_PROFILE_ID, judge) }),
        ).also { harness = it }

    private fun RunHarness.runToEnd(suite: com.indagium.testing.model.TestSuite, timing: JudgeTiming?, retries: Int = 0): TestRun {
        val base = config(suite, agentLane(LANE_A_PROFILE_ID)).copy(caseRetries = retries)
        val config = if (timing == null) {
            base
        } else {
            base.copy(judgeProfileId = JUDGE_PROFILE_ID, judgeMode = JudgeMode.EVERY_STEP.wire, judgeTiming = timing)
        }
        val started = assertIs<StartRunResult.Started>(runBlocking { coordinator.start(config) })
        return runBlocking { withTimeout(AWAIT_MS) { assertNotNull(coordinator.awaitFinished(started.runId)) } }
    }

    // ── A step that times out is still judged, as advice ────────────

    private fun timeoutSuite() = suiteOf(caseOf("Slow video", step("Tap the video", "Playback starts", timeoutMs = 500)))

    private fun hangingAgent() = TurnProvider(listOf(toolTurn("press_key", """{"key":"HOME"}"""), hangingTurn, textTurn))

    private fun assertAdvisory(run: TestRun, verdict: JudgeVerdict, state: VerificationState) {
        val result = run.caseResult("Slow video").steps.single()
        assertEquals(StepStatus.TIMEOUT, result.status, "the verdict is advice: the status stays a timeout")
        assertEquals(StepStatus.TIMEOUT, result.executionStatus)
        assertEquals(verdict, result.judge?.verdict)
        assertEquals(state, result.verificationState)
        assertFalse(result.judgeInconclusive)
        assertEquals(CaseStatus.FAIL, run.caseResult("Slow video").status, "a timeout still fails the case")
        assertEquals(RunStatus.FAILED, run.status)
        val note = result.note.orEmpty()
        assertTrue("did not finish the step within 500 ms" in note, note)
        assertTrue("last tool calls: press_key" in note, note)
        assertTrue("The action may have succeeded" in note && "judge's advice" in note, note)
        val screenshot = assertNotNull(result.screenshotPath, "the end screenshot was taken at the timeout")
        assertTrue(File(harness!!.store.runDir(run.id), screenshot).isFile)
    }

    @Test
    fun aTimedOutStepIsJudgedInlineAsAdviceAndKeepsItsTimeoutStatus() {
        val judge = ScriptedJudgeProvider { JudgeAnswer(verdict = "pass", reasoning = "Playback is visible.") }
        val suite = timeoutSuite()
        val run = harnessWith(suite, hangingAgent(), judge).runToEnd(suite, JudgeTiming.INLINE)

        assertAdvisory(run, JudgeVerdict.PASS, VerificationState.VERIFIED)
        assertEquals(1, judge.requests.map { it.messages.first().content }.distinct().size.coerceAtLeast(1))
        assertTrue("Advisory judge: the expected result looks met" in run.caseResult("Slow video").steps.single().note.orEmpty())
    }

    @Test
    fun aTimedOutStepIsJudgedAfterTheCaseWhenThatIsTheRunsTiming() {
        val judge = ScriptedJudgeProvider { JudgeAnswer(verdict = "pass") }
        val suite = timeoutSuite()
        val run = harnessWith(suite, hangingAgent(), judge).runToEnd(suite, JudgeTiming.AFTER_CASE)

        assertAdvisory(run, JudgeVerdict.PASS, VerificationState.VERIFIED)
        assertFalse("Independent verification is pending" in run.caseResult("Slow video").steps.single().note.orEmpty())
    }

    @Test
    fun aBackgroundAdviceThatSaysNotMetStillLeavesTheStatusAlone() {
        val judge = ScriptedJudgeProvider { JudgeAnswer(verdict = "fail", reasoning = "The video never started.") }
        val suite = timeoutSuite()
        val run = harnessWith(suite, hangingAgent(), judge).runToEnd(suite, JudgeTiming.BACKGROUND)

        assertAdvisory(run, JudgeVerdict.FAIL, VerificationState.FAILED)
        assertTrue("expected result was not met" in run.caseResult("Slow video").steps.single().note.orEmpty())
    }

    @Test
    fun withoutAJudgeATimeoutIsNotAdvisedAndTheNoteDoesNotPromiseAdvice() {
        val suite = timeoutSuite()
        val run = harnessWith(suite, hangingAgent(), null).runToEnd(suite, null)

        val result = run.caseResult("Slow video").steps.single()
        assertEquals(StepStatus.TIMEOUT, result.status)
        assertNull(result.judge)
        assertEquals(VerificationState.NOT_REQUESTED, result.verificationState)
        val note = result.note.orEmpty()
        assertTrue("did not finish the step within 500 ms" in note && "The action may have succeeded" in note, note)
        assertFalse("judge" in note, note)
    }

    // ── A cancelled run is not a failed one ─────────────────────────

    private fun awaitCancelled(h: RunHarness, ready: (TestRun) -> Boolean): TestRun = runBlocking {
        val started = assertIs<StartRunResult.Started>(h.coordinator.start(h.config(suiteHolder!!, agentLane(LANE_A_PROFILE_ID)).copy(caseRetries = 1)))
        withTimeout(AWAIT_MS) { while (h.coordinator.run(started.runId)?.let(ready) != true) delay(POLL_MS) }
        assertTrue(h.coordinator.cancel(started.runId))
        withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) }
    }

    private var suiteHolder: com.indagium.testing.model.TestSuite? = null

    @Test
    fun aStepInterruptedByACancelIsNotAFailureAndTheCaseIsOfferedForReRunAsInterrupted() {
        val suite = suiteOf(caseOf("Watch", step("Open the app"), step("Tap the video")))
        suiteHolder = suite
        val h = harnessWith(suite, TurnProvider(listOf(finishTurn("pass", "opened"), hangingTurn)), null)
        val run = awaitCancelled(h) { it.lanes.single().currentStepNumber == 2 }

        assertEquals(RunStatus.CANCELLED, run.status)
        val case = run.caseResult("Watch")
        assertEquals(CaseStatus.CANCELLED, case.status)
        val interrupted = case.steps.single { it.interrupted }
        assertEquals(StepStatus.ERROR, interrupted.status, "an older app reads the same status")
        assertEquals(2, interrupted.stepNumber)
        assertEquals(setOf(case.caseId), interruptedCaseIds(run))
        assertTrue(failedCaseIds(run).isEmpty(), "a cancel is not a failure")

        val reloaded = decodeRunFile(encodeRunFile(run)).getOrThrow()
        assertTrue(reloaded.caseResult("Watch").steps.single { it.stepNumber == 2 }.interrupted, "the marker survives the stored run")
    }

    @Test
    fun aCancelDuringTheSecondCaseAttemptKeepsTheFirstAttemptVisibleAndSaysWhatItGotWrong() {
        val suite = suiteOf(caseOf("Watch", step("Open the app"), step("Tap the video"), step("Check the feed")))
        suiteHolder = suite
        // The first three judgements (attempt 1) fail; whatever the judge says later passes, so only the cancel is unfinished.
        val judged = java.util.concurrent.atomic.AtomicInteger()
        val judge = ScriptedJudgeProvider { JudgeAnswer(verdict = if (judged.getAndIncrement() < 3) "fail" else "pass", reasoning = "Not what is expected.") }
        // Attempt 1: the agent reports three passes and the background judge fails them; attempt 2 starts, passes step 1 and hangs on step 2.
        val agent = TurnProvider(
            listOf(finishTurn("pass", "first"), finishTurn("pass", "second"), finishTurn("pass", "third"), textTurn, finishTurn("pass", "again"), hangingTurn),
        )
        val h = harnessWith(suite, agent, judge)
        val config = h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(
            caseRetries = 1, judgeProfileId = JUDGE_PROFILE_ID, judgeMode = JudgeMode.EVERY_STEP.wire, judgeTiming = JudgeTiming.BACKGROUND,
        )
        val run = runBlocking {
            val started = assertIs<StartRunResult.Started>(h.coordinator.start(config))
            withTimeout(AWAIT_MS) {
                while (h.coordinator.run(started.runId)?.lanes?.single()?.let { it.currentCase.orEmpty().contains("attempt 2") && it.currentStepNumber == 2 } != true) delay(POLL_MS)
            }
            assertTrue(h.coordinator.cancel(started.runId))
            withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) }
        }

        val case = run.caseResult("Watch")
        assertEquals(CaseStatus.CANCELLED, case.status)
        assertEquals(2, case.caseAttempt)
        val first = case.priorAttempts.single()
        assertEquals(CaseStatus.FAIL, first.status)
        assertTrue(first.steps.all { it.judge?.verdict == JudgeVerdict.FAIL && it.status == StepStatus.FAIL }, "attempt 1 was judged: ${first.steps.map { listOf(it.status, it.judge?.verdict, it.verificationState) }}")
        assertTrue(failedCaseIds(run).isEmpty())
        assertEquals(setOf(case.caseId), interruptedCaseIds(run))
        assertEquals(
            "Cancelled — stopped during attempt 2 at step 2; attempt 1 failed at steps 1, 2 and 3 (judge)",
            runVerdict(run).headline,
        )
    }

    @Test
    fun aJudgementThatNeverRanBecauseOfTheCancelSaysSoInsteadOfPromisingAVerdict() {
        val suite = suiteOf(caseOf("Watch", step("Open the app"), step("Tap the video")))
        suiteHolder = suite
        val judge = ScriptedJudgeProvider(responseDelayMs = 60_000L)
        val h = harnessWith(suite, TurnProvider(listOf(finishTurn("pass", "opened"), hangingTurn)), judge)
        val config = h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(
            judgeProfileId = JUDGE_PROFILE_ID, judgeMode = JudgeMode.EVERY_STEP.wire, judgeTiming = JudgeTiming.BACKGROUND,
        )
        val run = runBlocking {
            val started = assertIs<StartRunResult.Started>(h.coordinator.start(config))
            withTimeout(AWAIT_MS) { while (h.coordinator.run(started.runId)?.lanes?.single()?.currentStepNumber != 2) delay(POLL_MS) }
            assertTrue(h.coordinator.cancel(started.runId))
            withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) }
        }

        val first = run.caseResult("Watch").steps.first { it.stepNumber == 1 }
        assertEquals(StepStatus.PASS, first.status)
        val note = first.note.orEmpty()
        assertTrue("Not judged: the run was cancelled before the judge ran." in note, note)
        assertFalse("Independent verification is pending." in note, note)
    }

    // ── Agent and judge disagree ────────────────────────────────────

    @Test
    fun anAgentFailureTheJudgeFoundMetIsNamedAsADisagreementInTheNote() {
        for (timing in listOf(JudgeTiming.INLINE, JudgeTiming.AFTER_CASE, JudgeTiming.BACKGROUND)) {
            harness?.close()
            val judge = ScriptedJudgeProvider { JudgeAnswer(verdict = "pass", reasoning = "The next video is on screen.") }
            val suite = suiteOf(caseOf("Navigate", step("Tap the video area to navigate to different content", "Different content is shown")))
            val h = harnessWith(suite, TurnProvider(listOf(finishTurn("fail", "Nothing changed"), textTurn)), judge)
            val run = h.runToEnd(suite, timing)

            val result = run.caseResult("Navigate").steps.single()
            assertEquals(StepStatus.FAIL, result.status, "$timing: the agent's failure stays authoritative")
            assertEquals(JudgeVerdict.PASS, result.judge?.verdict)
            assertTrue(result.agentJudgeDisagrees(), timing.name)
            val note = result.note.orEmpty()
            assertTrue("The agent reported a failure, but the judge found the expected result met. Review this step." in note, "$timing: $note")
            assertFalse("Independent judge confirmed the expected result." in note, "$timing: $note")
        }
    }

    @Test
    fun anAgentPassTheJudgeFailedIsNamedInTheNoteOfADeferredVerdictToo() {
        val judge = ScriptedJudgeProvider { JudgeAnswer(verdict = "fail", reasoning = "The dialog is still open.") }
        val suite = suiteOf(caseOf("Dialog", step("Close the dialog", "The dialog is gone")))
        val h = harnessWith(suite, TurnProvider(listOf(finishTurn("pass", "closed"), textTurn)), judge)
        val run = h.runToEnd(suite, JudgeTiming.AFTER_CASE)

        val result = run.caseResult("Dialog").steps.single()
        assertEquals(StepStatus.FAIL, result.status)
        assertEquals(StepStatus.PASS, result.executionStatus)
        assertTrue(result.agentJudgeDisagrees())
        assertTrue("The judge failed a step the agent reported as passed." in result.note.orEmpty(), result.note)
    }

    @Test
    fun aPendingJudgementIsNotDescribedAsNotRequested() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val judge = ScriptedJudgeProvider(beforeSubmit = { gate.await() })
        val suite = suiteOf(caseOf("Pending", step("Open the app"), step("Second step")))
        val h = harnessWith(suite, TurnProvider(listOf(finishTurn("pass", "one"), finishTurn("pass", "two"), textTurn)), judge)
        val config = h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(
            judgeProfileId = JUDGE_PROFILE_ID, judgeMode = JudgeMode.EVERY_STEP.wire, judgeTiming = JudgeTiming.BACKGROUND,
        )
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        try {
            val pending = runBlocking {
                withTimeout(AWAIT_MS) {
                    while (h.coordinator.run(started.runId)?.lanes?.single()?.cases?.firstOrNull()?.steps?.firstOrNull() == null) delay(POLL_MS)
                }
                h.coordinator.run(started.runId)!!.caseResult("Pending").steps.first()
            }
            assertEquals(VerificationState.PENDING, pending.verificationState)
            val note = pending.note.orEmpty()
            assertTrue("Independent verification is pending." in note, note)
            assertFalse("no independent judge was requested" in note, note)
        } finally {
            gate.complete(Unit)
        }
        runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }
    }
}
