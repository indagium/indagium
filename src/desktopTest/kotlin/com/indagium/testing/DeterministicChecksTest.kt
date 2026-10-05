package com.indagium.testing

import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.ScriptParam
import com.indagium.testing.model.ScriptParamType
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.newCheckId
import com.indagium.testing.model.newScriptId
import com.indagium.testing.run.DeterministicChecks
import com.indagium.testing.script.ScriptRunContext
import com.indagium.testing.script.TestScriptRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeFalse
import java.io.File
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SETTLE_MS = 5_000L
private const val NANOS_PER_MS = 1_000_000L

/** LogAppears, LogAbsent and ScriptResult against a fake adb: timing, tag filter, the regex budget and script results. */
class DeterministicChecksTest {
    private val adb = ScriptedAdbRunner()
    private val laneDir: File = createTempDirectory("indagium-checks").toFile()
    private var session: TestDeviceSession? = null
    private var emittedBytes = 0L

    @AfterTest
    fun tearDown() {
        session?.closeBlocking()
        laneDir.deleteRecursively()
    }

    private fun checks(scripts: List<TestScript> = emptyList()): DeterministicChecks {
        val lane = runBlocking { openFixtureSession(adb, laneDir) }.also { session = it }
        return DeterministicChecks(
            lane,
            TestScriptRunner(hostShell = listOf("/bin/sh", "-c")),
            { id -> scripts.firstOrNull { it.id == id } },
            { ScriptRunContext(packageName = "com.example", runDir = laneDir, caseId = "case-1", stepId = "step-1") },
        )
    }

    private fun emit(text: String) {
        emittedBytes += text.toByteArray().size
        adb.logcat.emit(text)
    }

    private fun settle() {
        val lane = checkNotNull(session)
        val deadline = System.nanoTime() + SETTLE_MS * NANOS_PER_MS
        while (runBlocking { lane.logMarker() } < emittedBytes) {
            check(System.nanoTime() < deadline) { "log never reached $emittedBytes bytes" }
            Thread.sleep(10)
        }
    }

    private fun DeterministicChecks.run(check: StepCheck, offset: Long = 0L): CheckResult = runBlocking { evaluate(check, offset) }

    private fun appears(regex: String, tag: String? = null, withinMs: Long = 200L) = StepCheck.LogAppears(newCheckId(), tag, regex, withinMs)

    private fun absent(regex: String, tag: String? = null, forMs: Long = 200L) = StepCheck.LogAbsent(newCheckId(), tag, regex, forMs)

    // ── LogAppears ──────────────────────────────────────────────────

    @Test
    fun aRowAlreadyInTheLogSinceTheStepBeganPassesAtOnce() {
        val c = checks()
        emit(logRow("Login ok"))
        settle()
        val started = System.nanoTime()
        val result = c.run(appears("Login ok", withinMs = 5_000L))
        assertEquals(CheckStatus.PASS, result.status, result.detail)
        assertTrue(result.detail.contains("Login ok"), result.detail)
        assertTrue((System.nanoTime() - started) / NANOS_PER_MS < 2_000L, "an existing row needs no waiting")
    }

    @Test
    fun aRowThatArrivesWhileWaitingPassesAndRowsBeforeTheStepOffsetDoNotCount() {
        val c = checks()
        emit(logRow("Login ok"))
        settle()
        val offset = runBlocking { checkNotNull(session).logMarker() }
        assertEquals(CheckStatus.FAIL, c.run(appears("Login ok", withinMs = 150L), offset).status, "an older row is outside the step")

        thread {
            Thread.sleep(120)
            emit(logRow("Home shown"))
        }
        val late = c.run(appears("Home shown", withinMs = 5_000L), offset)
        assertEquals(CheckStatus.PASS, late.status, late.detail)
    }

    @Test
    fun aMissingRowFailsOnlyAfterTheWholeWindow() {
        val c = checks()
        val started = System.nanoTime()
        val result = c.run(appears("never happens", withinMs = 300L))
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MS
        assertEquals(CheckStatus.FAIL, result.status)
        assertTrue(result.detail.contains("within 300 ms"), result.detail)
        assertTrue(elapsedMs >= 250, "it waited for the window: $elapsedMs ms")
    }

    @Test
    fun theTagFilterIgnoresRowsOfOtherTags() {
        val c = checks()
        emit(logRow("Displayed Settings", tag = "OtherTag"))
        settle()
        assertEquals(CheckStatus.FAIL, c.run(appears("Displayed", tag = "ActivityManager", withinMs = 100L)).status)
        emit(logRow("Displayed Settings", tag = "ActivityManager"))
        settle()
        assertEquals(CheckStatus.PASS, c.run(appears("Displayed", tag = "ActivityManager", withinMs = 2_000L)).status)
    }

    @Test
    fun anInvalidRegexIsAnErrorResultNotAnException() {
        val c = checks()
        val result = c.run(appears("([unclosed", withinMs = 50L))
        assertEquals(CheckStatus.ERROR, result.status)
        assertTrue(result.detail.contains("Invalid regular expression"), result.detail)
    }

    @Test
    fun aCatastrophicRegexIsStoppedByTheBacktrackingBudgetInsteadOfHanging() {
        val c = checks()
        emit(logRow("a".repeat(40) + "!"))
        settle()
        val started = System.nanoTime()
        val result = c.run(appears("^(a+)+\$", withinMs = 100L))
        assertEquals(CheckStatus.FAIL, result.status, "the budget cut the match off, so nothing matched")
        assertTrue((System.nanoTime() - started) / NANOS_PER_MS < 20_000L, "the check returned")
    }

    // ── LogAbsent ───────────────────────────────────────────────────

    @Test
    fun aRowThatNeverAppearsPassesAfterTheHoldTime() {
        val c = checks()
        val started = System.nanoTime()
        val result = c.run(absent("FATAL EXCEPTION", forMs = 300L))
        assertEquals(CheckStatus.PASS, result.status, result.detail)
        assertTrue((System.nanoTime() - started) / NANOS_PER_MS >= 250, "it held for the whole window")
    }

    @Test
    fun aForbiddenRowEndsTheHoldEarlyAsAFailure() {
        val c = checks()
        emit(logRow("FATAL EXCEPTION: main", level = 'E', tag = "AndroidRuntime"))
        settle()
        val started = System.nanoTime()
        val result = c.run(absent("FATAL EXCEPTION", forMs = 5_000L))
        assertEquals(CheckStatus.FAIL, result.status)
        assertTrue(result.detail.contains("FATAL EXCEPTION"), result.detail)
        assertTrue((System.nanoTime() - started) / NANOS_PER_MS < 3_000L, "it did not wait out the window")
    }

    @Test
    fun theAbsentCheckHonoursTagAndStepOffset() {
        val c = checks()
        emit(logRow("boom", tag = "Noise"))
        settle()
        assertEquals(CheckStatus.PASS, c.run(absent("boom", tag = "Quiet", forMs = 100L)).status)
        val offset = runBlocking { checkNotNull(session).logMarker() }
        assertEquals(CheckStatus.PASS, c.run(absent("boom", forMs = 100L), offset).status, "the row is from before the step")
        assertEquals(CheckStatus.FAIL, c.run(absent("boom", forMs = 100L)).status)
    }

    // ── ScriptResult ────────────────────────────────────────────────

    private fun script(template: String, params: List<ScriptParam> = emptyList(), timeoutMs: Long = 10_000L) =
        TestScript(newScriptId(), "check_script", params = params, commandTemplate = template, timeoutMs = timeoutMs, permission = ScriptPermission.AUTO)

    private fun result(
        script: TestScript,
        exit: Int? = 0,
        contains: String? = null,
        args: Map<String, String> = emptyMap(),
        scripts: List<TestScript> = listOf(script),
    ) =
        checks(scripts).run(StepCheck.ScriptResult(newCheckId(), script.id, args, exit, contains))

    @Test
    fun aScriptWithTheExpectedExitCodeAndOutputPasses() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        val s = script("printf 'all %s fine' \"\$what\"", listOf(ScriptParam("what", ScriptParamType.STRING)))
        val passed = result(s, contains = "all good fine", args = mapOf("what" to "good"))
        assertEquals(CheckStatus.PASS, passed.status, passed.detail)
    }

    @Test
    fun aWrongExitCodeOrMissingOutputFailsAndNullExitCodeAcceptsAny() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        val failing = script("printf nope; exit 4")
        assertEquals(CheckStatus.FAIL, result(failing).status)
        assertTrue(result(failing).detail.contains("exit code 4"))
        assertEquals(CheckStatus.PASS, result(failing, exit = 4).status)
        assertEquals(CheckStatus.PASS, result(failing, exit = null).status)
        assertEquals(CheckStatus.FAIL, result(failing, exit = null, contains = "yes").status)
    }

    @Test
    fun aScriptThatTimesOutFails() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        val slow = script("sleep 5", timeoutMs = 200L)
        val timedOut = result(slow, exit = null)
        assertEquals(CheckStatus.FAIL, timedOut.status)
        assertTrue(timedOut.detail.contains("timed out"), timedOut.detail)
    }

    @Test
    fun anUnknownScriptOrBadArgumentsAreErrorResults() {
        val s = script("printf hi", listOf(ScriptParam("n", ScriptParamType.INT)))
        val missing = checks().run(StepCheck.ScriptResult(newCheckId(), "script-nope", emptyMap(), 0, null))
        assertEquals(CheckStatus.ERROR, missing.status)
        assertTrue(missing.detail.contains("not in the library"))
        val bad = result(s, args = mapOf("n" to "seven"))
        assertEquals(CheckStatus.ERROR, bad.status)
        assertTrue(bad.detail.contains("whole number"), bad.detail)
    }

    // ── Judge checks ────────────────────────────────────────────────

    @Test
    fun judgeChecksAreNotEvaluatedInThisVersion() {
        val c = checks()
        assertEquals(CheckStatus.NOT_EVALUATED, c.run(StepCheck.ScreenJudge(newCheckId(), "The title is visible")).status)
        assertEquals(CheckStatus.NOT_EVALUATED, c.run(StepCheck.AskJudge(newCheckId(), "Does it look right?")).status)
    }
}
