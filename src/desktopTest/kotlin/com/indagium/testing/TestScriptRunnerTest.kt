package com.indagium.testing

import com.indagium.capture.FakeCaptureRunner
import com.indagium.testing.model.ScriptParam
import com.indagium.testing.model.ScriptParamType
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.newScriptId
import com.indagium.testing.script.AdbScriptTarget
import com.indagium.testing.script.HostCommandResult
import com.indagium.testing.script.HostCommandRunner
import com.indagium.testing.script.HostCommandSpec
import com.indagium.testing.script.ProcessBuilderHostCommandRunner
import com.indagium.testing.script.ScriptArgsResult
import com.indagium.testing.script.ScriptRunContext
import com.indagium.testing.script.ScriptRunOutcome
import com.indagium.testing.script.TestScriptRunner
import com.indagium.testing.script.buildAdbRemoteCommand
import com.indagium.testing.script.posixSingleQuote
import com.indagium.testing.script.scriptArgsFromToolValues
import com.indagium.testing.script.validateScriptArgs
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeFalse
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val POSIX_SHELL = listOf("/bin/sh", "-c")
private const val SHORT_TIMEOUT_MS = 400L
private const val LONG_SLEEP_SECONDS = 20
private const val DEFAULT_CAP_BYTES = 64 * 1024

class TestScriptRunnerTest {
    private val runDir: File = createTempDirectory("indagium-script-run").toFile()

    @AfterTest
    fun cleanUp() {
        runDir.deleteRecursively()
    }

    private fun script(
        template: String,
        params: List<ScriptParam> = emptyList(),
        target: ScriptTarget = ScriptTarget.HOST_SHELL,
        timeoutMs: Long = 10_000,
        cap: Int = DEFAULT_CAP_BYTES,
        workingDir: String? = null,
    ) = TestScript(
        id = newScriptId(), toolName = "demo", params = params, commandTemplate = template, target = target,
        timeoutMs = timeoutMs, outputCapBytes = cap, workingDir = workingDir,
    )

    private fun context(deviceSerial: String = "", packageName: String = "") = ScriptRunContext(deviceSerial, packageName, runDir, "case-1", "step-1")

    private fun finished(outcome: ScriptRunOutcome) = assertIs<ScriptRunOutcome.Finished>(outcome).result

    private fun run(
        script: TestScript,
        args: Map<String, String> = emptyMap(),
        context: ScriptRunContext = context(),
        adb: AdbScriptTarget? = null,
        shell: List<String> = POSIX_SHELL,
    ): ScriptRunOutcome = runBlocking { TestScriptRunner(hostShell = shell).run(script, args, context, adb) }

    private fun skipOnWindows() = assumeFalse(System.getProperty("os.name").lowercase().contains("win"))

    // ── The injection rule: parameter values are data, never command text ──

    @Test
    fun shellMetacharactersInAParameterStayLiteralOnTheHost() {
        skipOnWindows()
        val hostile = listOf(
            "; rm -rf ~", "\$(whoami)", "`id`", "\$HOME", "a && touch $runDir/pwned", "x\ny", "'; echo hacked; '", "\"quoted\"", "back\\slash",
        )
        val demo = script("printf %s \"\$name\"", listOf(ScriptParam("name")))
        hostile.forEach { value ->
            val result = finished(run(demo, mapOf("name" to value)))
            assertEquals(0, result.exitCode, value)
            assertEquals(value, result.stdout, "value must reach the script unchanged")
        }
        assertFalse(File(runDir, "pwned").exists(), "nothing in a value may be executed")
    }

    @Test
    fun theRunVariablesAreProvidedAsEnvironment() {
        skipOnWindows()
        val demo = script("printf '%s|%s|%s|%s|%s' \"\$DEVICE\" \"\$PACKAGE\" \"\$CASE_ID\" \"\$STEP_ID\" \"\$RUN_DIR\"")
        val result = finished(run(demo, context = context("SER-9", "com.example.app")))
        assertEquals("SER-9|com.example.app|case-1|step-1|${runDir.absolutePath}", result.stdout)
    }

    @Test
    fun theTemplateRunsInTheRunFolderUnlessAWorkingDirIsSet() {
        skipOnWindows()
        val other = createTempDirectory("indagium-script-cwd").toFile()
        try {
            val here = finished(run(script("pwd -P")))
            assertEquals(runDir.canonicalPath, here.stdout.trim())
            val there = finished(run(script("pwd -P", workingDir = other.absolutePath)))
            assertEquals(other.canonicalPath, there.stdout.trim())
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun aMissingWorkingDirIsRejectedWithoutRunning() {
        val outcome = run(script("echo hi", workingDir = File(runDir, "nope").absolutePath))
        assertTrue(assertIs<ScriptRunOutcome.Rejected>(outcome).message.contains("not an existing folder"))
    }

    // ── Validation ─────────────────────────────────────────────────

    private val typed = script(
        "true",
        listOf(
            ScriptParam("text", ScriptParamType.STRING, required = true),
            ScriptParam("count", ScriptParamType.INT, required = false, defaultValue = "3"),
            ScriptParam("flag", ScriptParamType.BOOL, required = false),
        ),
    )

    private fun valid(args: Map<String, String>) = assertIs<ScriptArgsResult.Valid>(validateScriptArgs(typed, args)).values

    private fun invalid(args: Map<String, String>) = assertIs<ScriptArgsResult.Invalid>(validateScriptArgs(typed, args)).message

    @Test
    fun defaultsAreAppliedAndAnAbsentOptionalParameterIsEmpty() {
        assertEquals(mapOf("text" to "hi", "count" to "3", "flag" to ""), valid(mapOf("text" to "hi")))
        assertEquals(mapOf("text" to "hi", "count" to "-7", "flag" to "true"), valid(mapOf("text" to "hi", "count" to "-7", "flag" to "true")))
    }

    @Test
    fun requiredAndUnknownArgumentsAreRejected() {
        assertTrue(invalid(emptyMap()).contains("Missing required argument 'text'"))
        assertTrue(invalid(mapOf("text" to "x", "extra" to "1")).contains("Unknown argument"))
    }

    @Test
    fun intAndBoolAreStrict() {
        listOf("1.5", "abc", "", "+3", "1e3", "12345678901234567890", " 3").forEach {
            assertTrue(invalid(mapOf("text" to "x", "count" to it)).contains("whole number"), "'$it'")
        }
        listOf("0", "-1", "999999999999999999").forEach { valid(mapOf("text" to "x", "count" to it)) }
        listOf("True", "1", "yes", "").forEach {
            assertTrue(invalid(mapOf("text" to "x", "flag" to it)).contains("true or false"), "'$it'")
        }
    }

    @Test
    fun stringsAreBoundedAndHaveNoNul() {
        assertTrue(invalid(mapOf("text" to "a\u0000b")).contains("NUL"))
        assertTrue(invalid(mapOf("text" to "x".repeat(4 * 1024 + 1))).contains("longer than"))
        valid(mapOf("text" to "x".repeat(4 * 1024)))
        assertTrue(invalid(mapOf("text" to "é".repeat(2049))).contains("longer than"), "the limit is in bytes")
    }

    @Test
    fun aScriptWithAReservedParameterNameCannotRun() {
        val bad = script("true", listOf(ScriptParam("path")))
        assertTrue(assertIs<ScriptArgsResult.Invalid>(validateScriptArgs(bad, mapOf("path" to "/x"))).message.contains("reserved"))
    }

    @Test
    fun toolCallValuesBecomeStrings() {
        assertEquals(
            mapOf("a" to "x", "b" to "true", "c" to "42", "d" to "-5"),
            scriptArgsFromToolValues(mapOf("a" to "x", "b" to true, "c" to 42, "d" to -5.0, "skipped" to null)),
        )
        assertFailsWith<IllegalArgumentException> { scriptArgsFromToolValues(mapOf("a" to 1.5)) }
        assertFailsWith<IllegalArgumentException> { scriptArgsFromToolValues(mapOf("a" to listOf(1))) }
    }

    // ── adb ────────────────────────────────────────────────────────

    @Test
    fun singleQuotesInAValueAreEscapedForThePosixShell() {
        assertEquals("'it'\\''s'", posixSingleQuote("it's"))
        assertEquals("'plain'", posixSingleQuote("plain"))
        assertEquals("''", posixSingleQuote(""))
        assertEquals(
            "export name='it'\\''s'; echo \"\$name\"",
            buildAdbRemoteCommand("echo \"\$name\"", mapOf("name" to "it's")),
        )
        assertEquals("echo hi", buildAdbRemoteCommand("echo hi", emptyMap()))
    }

    @Test
    fun theAdbRemoteCommandKeepsHostileValuesLiteralWhenAShellEvaluatesIt() {
        skipOnWindows()
        val value = "it's \$(echo pwned); `echo pwned` \"x\" \\n"
        val remote = buildAdbRemoteCommand("printf %s \"\$name\"", mapOf("name" to value))
        val result = runBlocking { ProcessBuilderHostCommandRunner().run(HostCommandSpec(listOf("/bin/sh", "-c", remote))) }
        assertEquals(value, result.stdoutText())
    }

    @Test
    fun anAdbScriptSendsOneRemoteCommandAfterShellWithTheDeviceSerial() {
        val captured = mutableListOf<HostCommandSpec>()
        val runner = object : HostCommandRunner {
            override suspend fun run(spec: HostCommandSpec): HostCommandResult {
                captured += spec
                return HostCommandResult(0, "ok".toByteArray(), ByteArray(0), timedOut = false, truncated = false, durationMs = 1)
            }
        }
        val tools = fixtureTools(FakeCaptureRunner())
        val adbScript = script("pm clear \"\$pkg\"", listOf(ScriptParam("pkg")), target = ScriptTarget.ADB_SHELL)
        val outcome = runBlocking {
            TestScriptRunner(runner).run(adbScript, mapOf("pkg" to "it's"), context("SER-1", "com.example"), AdbScriptTarget(tools, "SER-1"))
        }
        assertEquals("ok", finished(outcome).stdout)
        val command = captured.single().command
        assertEquals(listOf("adb", "-s", "SER-1", "shell"), command.take(4))
        assertEquals(5, command.size, "the remote command is ONE argument")
        assertEquals(
            "export pkg='it'\\''s' DEVICE='SER-1' PACKAGE='com.example' CASE_ID='case-1' STEP_ID='step-1'; pm clear \"\$pkg\"",
            command[4],
        )
        assertFalse(command[4].contains(runDir.absolutePath), "the host run folder is not sent to the device")
    }

    @Test
    fun anAdbScriptWithoutADeviceIsRejected() {
        val outcome = run(script("true", target = ScriptTarget.ADB_SHELL))
        assertTrue(assertIs<ScriptRunOutcome.Rejected>(outcome).message.contains("choose a device"))
    }

    // ── Timeout, cap, exit codes ───────────────────────────────────

    @Test
    fun aScriptThatOutlivesItsTimeoutIsKilled() {
        skipOnWindows()
        val started = System.nanoTime()
        val result = finished(run(script("sleep $LONG_SLEEP_SECONDS", timeoutMs = SHORT_TIMEOUT_MS)))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(result.timedOut)
        assertTrue(tookMs < LONG_SLEEP_SECONDS * 1000L / 2, "returned after $tookMs ms")
    }

    @Test
    fun outputBeyondTheCapIsCutAndFlagged() {
        skipOnWindows()
        val result = finished(run(script("head -c 5000 /dev/zero | tr '\\0' a", cap = 100)))
        assertEquals(100, result.stdout.length)
        assertTrue(result.truncated)
        assertFalse(finished(run(script("printf small", cap = 100))).truncated)
    }

    @Test
    fun theExitCodeAndStderrAreReported() {
        skipOnWindows()
        val result = finished(run(script("echo oops >&2; exit 7")))
        assertEquals(7, result.exitCode)
        assertEquals("oops", result.stderr.trim())
        assertFalse(result.timedOut)
    }
}
