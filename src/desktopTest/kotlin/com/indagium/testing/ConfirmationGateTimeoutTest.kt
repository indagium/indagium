package com.indagium.testing

import com.indagium.ai.LlmRole
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestLibrary
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
import kotlin.test.assertTrue

private const val AWAIT_MS = 15_000L
private const val POLL_MS = 10L
private const val SHORT_TIMEOUT_MS = 300L

/** A confirmation card of a lane's agent that nobody answers within the run's timeout is denied, and the run goes on. */
class ConfirmationGateTimeoutTest {
    private var harness: RunHarness? = null

    @AfterTest
    fun tearDown() {
        harness?.close()
    }

    @Test
    fun anUnansweredAskScriptCardTimesOutAsDeniedTheScriptNeverRunsAndTheRunContinues() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        val marker = File.createTempFile("ask-me", ".txt").also { it.delete() }
        val script = hostScript("ask_me", "printf ran > '${marker.absolutePath}'", ScriptPermission.ASK)
        val suite = suiteOf(caseOf("Needs a script", step("Run the script, then report"), step("Look around")))
        val provider = TurnProvider(listOf(toolTurn("ask_me"), finishTurn("fail", "the script was not allowed"), finishTurn("pass", "ok"), textTurn))
        val h = RunHarness(TestLibrary(suites = listOf(suite), scripts = listOf(script)), provider).also { harness = it }
        // CONTINUE would hide a stuck run; the default STOP_CASE ends the case at the failed first step, which is what the agent reports.
        val config = h.config(suite).copy(confirmationTimeoutMs = SHORT_TIMEOUT_MS)

        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        runBlocking {
            withTimeout(AWAIT_MS) { while (h.coordinator.pendingConfirmations().isEmpty()) delay(POLL_MS) }
            val card = h.coordinator.pendingConfirmations().single()
            assertEquals("ask_me", card.toolName)
            assertEquals(RunStatus.RUNNING, h.coordinator.run(started.runId)?.status, "the run waits for the card")
        }
        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        assertTrue(h.coordinator.pendingConfirmations().isEmpty(), "the card is gone")
        assertFalse(marker.exists(), "a denied script is never run")
        val denial = provider.requests[1].messages.last { it.role == LlmRole.TOOL }.content.orEmpty()
        assertTrue(denial.contains("denied"), "the agent is told the action was denied: $denial")
        val steps = run.lanes.single().cases.single { it.caseName == "Needs a script" }
        assertEquals(StepStatus.FAIL, steps.steps.first().status, "the run went on: the agent reported, the engine recorded the step")
        assertEquals(CaseStatus.FAIL, steps.status)
        assertEquals(RunStatus.FAILED, run.status)
    }

    @Test
    fun anAnsweredCardStillRunsTheScript() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        val marker = File.createTempFile("ask-me", ".txt").also { it.delete() }
        val script = hostScript("ask_me", "printf ran > '${marker.absolutePath}'", ScriptPermission.ASK)
        val suite = suiteOf(caseOf("Needs a script", step("Run the script, then report")))
        val provider = TurnProvider(listOf(toolTurn("ask_me"), finishTurn("pass", "ok"), textTurn))
        val h = RunHarness(TestLibrary(suites = listOf(suite), scripts = listOf(script)), provider).also { harness = it }

        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(h.config(suite)) })
        runBlocking {
            withTimeout(AWAIT_MS) { while (h.coordinator.pendingConfirmations().isEmpty()) delay(POLL_MS) }
            val card = h.coordinator.pendingConfirmations().single()
            assertTrue(h.coordinator.resolveConfirmation(started.runId, card.confirmationId, true))
        }
        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }
        assertEquals(RunStatus.PASSED, run.status, run.toString())
        assertEquals("ran", marker.readText())
        marker.delete()
    }

    @Test
    fun aDeniedAskDoesNotSpendThePaidCaseDispatchAllowance() {
        val marker = File.createTempFile("ask-me", ".txt").also { it.delete() }
        val script = hostScript("ask_me", "printf ran > '${marker.absolutePath}'", ScriptPermission.ASK)
        val suite = suiteOf(caseOf("Denied ask", step("Try the script, press once, then report", maxToolCalls = 8)))
        val provider = TurnProvider(
            listOf(
                toolTurn("ask_me"),
                toolTurn("press_key", """{"key":"HOME"}"""),
                finishTurn("pass", "the button was pressed"),
                textTurn,
            ),
        )
        val h = RunHarness(TestLibrary(suites = listOf(suite), scripts = listOf(script)), provider).also { harness = it }
        val config = h.config(suite, toolLimit = 1).copy(confirmationTimeoutMs = SHORT_TIMEOUT_MS)
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        assertFalse(marker.exists(), "the timed-out confirmation does not execute the script")
        assertEquals(1, h.adb.shellCommands.count { it.firstOrNull() == "input" }, "the one actual paid dispatch is admitted")
        assertEquals(CaseStatus.PASS, run.lanes.single().cases.single().status, run.toString())
        assertEquals(RunStatus.PASSED, run.status, run.toString())
    }
}
