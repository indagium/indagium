package com.indagium.testing

import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.LaneToolCall
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.newCheckId
import com.indagium.testing.run.PauseDecision
import com.indagium.testing.run.PausedStep
import com.indagium.testing.run.SequenceEnv
import com.indagium.testing.run.SequenceListener
import com.indagium.testing.run.SequenceSpec
import com.indagium.testing.run.StepSequence
import com.indagium.testing.script.TestScriptRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val PROTOCOL_WAIT_MS = 5_000L
private const val STEP_TIMEOUT_MS = 100L

class StepSequenceProtocolIdentityTest {
    @Test
    fun staleExternalFinishCannotFinishSuccessorOrRunItsChecks() = runBlocking {
        val checkScript = hostScript("successor_check", "printf successor").copy(permission = ScriptPermission.AUTO)
        val steps = listOf(
            step("First", timeoutMs = STEP_TIMEOUT_MS, onFailure = OnFailure.CONTINUE),
            step("Second", checks = listOf(StepCheck.ScriptResult(newCheckId(), checkScript.id)), timeoutMs = 1_000),
        )
        val listener = HoldingSequenceListener("finish_step")
        val clock = AtomicLong()
        val fixture = sequenceFixture(steps, listener, clock, scripts = listOf(checkScript))
        try {
            fixture.sequence.begin(0)
            val oldGateway = fixture.sequence.gateway(runEpoch = null) // An external lane has no managed-agent epoch.
            val lateCall = async(Dispatchers.Default) {
                oldGateway.executeSuspending("finish_step", mapOf("status" to "pass", "observation" to "stale result"))
            }
            listener.awaitAdmission()

            clock.set((STEP_TIMEOUT_MS + 1) * NANOS_PER_MILLI)
            assertTrue(fixture.sequence.checkTimeout())
            assertEquals(1, fixture.sequence.currentIndex)
            assertEquals(listOf(StepStatus.TIMEOUT), fixture.sequence.results().map { it.status })

            listener.release()
            val stale = assertIs<Map<*, *>>(withTimeout(PROTOCOL_WAIT_MS) { lateCall.await() })
            assertTrue(stale["error"].toString().contains("attempt ended"), stale.toString())
            assertEquals(1, fixture.sequence.results().size, "the stale report cannot evaluate the successor's check")

            fixture.sequence.gateway(runEpoch = null).executeSuspending(
                "finish_step", mapOf("status" to "pass", "observation" to "successor completed"),
            )
            assertEquals(listOf(StepStatus.TIMEOUT, StepStatus.PASS), fixture.sequence.results().map { it.status })
            assertEquals("successor completed", fixture.sequence.results().last().observation)
            assertEquals("PASS", fixture.sequence.results().last().checks.single().status.name)
        } finally {
            listener.release()
            fixture.close()
        }
    }

    @Test
    fun staleManagedFinishCannotCompleteRetriedAttempt() = runBlocking {
        val retryStep = step("Retry me", timeoutMs = STEP_TIMEOUT_MS, onFailure = OnFailure.STOP_CASE)
        val listener = HoldingSequenceListener("finish_step")
        val clock = AtomicLong()
        val fixture = sequenceFixture(
            listOf(retryStep),
            listener,
            clock,
            stepRetriesOverride = 1,
        )
        try {
            fixture.sequence.begin(0)
            val oldGateway = fixture.sequence.gateway(fixture.sequence.newEpoch())
            val lateCall = async(Dispatchers.Default) {
                oldGateway.executeSuspending("finish_step", mapOf("status" to "pass", "observation" to "old attempt"))
            }
            listener.awaitAdmission()

            clock.set((STEP_TIMEOUT_MS + 1) * NANOS_PER_MILLI)
            assertTrue(fixture.sequence.checkTimeout())
            assertEquals(retryStep.id to 2, fixture.sequence.currentAttemptIdentity())

            listener.release()
            val stale = assertIs<Map<*, *>>(withTimeout(PROTOCOL_WAIT_MS) { lateCall.await() })
            assertTrue(stale["error"].toString().contains("attempt ended"), stale.toString())
            assertTrue(fixture.sequence.results().isEmpty(), "the timed-out attempt remains retry evidence only")

            fixture.sequence.gateway(fixture.sequence.newEpoch()).executeSuspending(
                "finish_step", mapOf("status" to "pass", "observation" to "new attempt"),
            )
            val result = fixture.sequence.results().single()
            assertEquals(StepStatus.PASS, result.status)
            assertEquals(2, result.attempts)
            assertEquals("new attempt", result.observation)
        } finally {
            listener.release()
            fixture.close()
        }
    }

    @Test
    fun staleExternalObservationIsRejectedAndCannotLeakIntoSuccessor() = runBlocking {
        val listener = HoldingSequenceListener("report_observation")
        val clock = AtomicLong()
        val fixture = sequenceFixture(
            listOf(
                step("First", timeoutMs = STEP_TIMEOUT_MS, onFailure = OnFailure.CONTINUE),
                step("Second", timeoutMs = 1_000),
            ),
            listener,
            clock,
        )
        try {
            fixture.sequence.begin(0)
            val oldGateway = fixture.sequence.gateway(runEpoch = null)
            val lateCall = async(Dispatchers.Default) {
                oldGateway.executeSuspending("report_observation", mapOf("text" to "observation from old step"))
            }
            listener.awaitAdmission()

            clock.set((STEP_TIMEOUT_MS + 1) * NANOS_PER_MILLI)
            assertTrue(fixture.sequence.checkTimeout())
            listener.release()
            val stale = assertIs<Map<*, *>>(withTimeout(PROTOCOL_WAIT_MS) { lateCall.await() })
            assertTrue(stale["error"].toString().contains("attempt ended"), stale.toString())

            fixture.sequence.gateway(runEpoch = null).executeSuspending(
                "finish_step", mapOf("status" to "pass", "observation" to "current step only"),
            )
            val second = fixture.sequence.results().last()
            assertEquals("current step only", second.observation)
            assertTrue("old step" !in second.observation)
        } finally {
            listener.release()
            fixture.close()
        }
    }

    private suspend fun sequenceFixture(
        steps: List<com.indagium.testing.model.TestStep>,
        listener: HoldingSequenceListener,
        clock: AtomicLong,
        scripts: List<com.indagium.testing.model.TestScript> = emptyList(),
        stepRetriesOverride: Int? = null,
    ): SequenceFixture {
        val directory = createTempDirectory("step-sequence-protocol").toFile()
        val adb = ScriptedAdbRunner()
        val session = openFixtureSession(adb, directory)
        val sequence = StepSequence(
            SequenceEnv(
                session = session,
                scriptRunner = TestScriptRunner(hostShell = listOf("/bin/sh", "-c")),
                scripts = scripts,
                runDir = directory,
                laneDir = directory,
                packageName = "com.example.app",
                evidence = EvidenceFlags(screenshots = false, logcat = false, transcript = false),
                transcript = null,
                listener = listener,
                nanoTime = clock::get,
                wallClock = { clock.get() / NANOS_PER_MILLI },
                stepRetriesOverride = stepRetriesOverride,
            ),
            SequenceSpec(
                caseId = "case-protocol",
                caseName = "Protocol identity",
                steps = steps,
                setup = false,
                allowedTools = null,
                evidencePrefix = "protocol",
            ),
        )
        return SequenceFixture(sequence, session, directory)
    }

    private class SequenceFixture(
        val sequence: StepSequence,
        private val session: com.indagium.testing.device.TestDeviceSession,
        private val directory: File,
    ) {
        suspend fun close() {
            sequence.cancelDeferredJudgements()
            session.close()
            directory.deleteRecursively()
        }
    }

    private class HoldingSequenceListener(private val heldTool: String) : SequenceListener {
        private val held = AtomicBoolean()
        private val admitted = CountDownLatch(1)
        private val release = CountDownLatch(1)

        override fun stepStarted(stepNumber: Int, action: String, attempt: Int) = Unit

        override fun stepRecorded(result: StepResult) = Unit

        override fun toolCallStarted(call: LaneToolCall) {
            if (call.toolName == heldTool && held.compareAndSet(false, true)) {
                admitted.countDown()
                check(release.await(PROTOCOL_WAIT_MS, TimeUnit.MILLISECONDS)) { "test did not release the held protocol call" }
            }
        }

        override suspend fun awaitUser(paused: PausedStep): PauseDecision = PauseDecision.CONTINUE

        suspend fun awaitAdmission() {
            assertTrue(
                withContext(Dispatchers.IO) { admitted.await(PROTOCOL_WAIT_MS, TimeUnit.MILLISECONDS) },
                "the protocol call reached dispatch admission",
            )
        }

        fun release() {
            release.countDown()
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
