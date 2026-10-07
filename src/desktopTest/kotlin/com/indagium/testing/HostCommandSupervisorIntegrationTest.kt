package com.indagium.testing

import com.indagium.testing.model.ScriptParam
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.TestScript
import com.indagium.testing.script.HostCommandSpec
import com.indagium.testing.script.ProcessBuilderHostCommandRunner
import com.indagium.testing.script.ScriptRunContext
import com.indagium.testing.script.ScriptRunOutcome
import com.indagium.testing.script.TestScriptRunner
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val PROBE_PROPERTY = "indagium.commandSupervisorProbe"
private const val AWAIT_MS = 5_000L
private const val POLL_MS = 10L
private const val DEFAULT_OUTPUT_CAP_BYTES = 64 * 1024

/** Host-native runner regressions, executed on every supported CI operating system. */
class HostCommandSupervisorIntegrationTest {
    private val runner = ProcessBuilderHostCommandRunner()
    private lateinit var probe: File
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        assumeNotNull(System.getProperty(PROBE_PROPERTY))
        probe = File(System.getProperty(PROBE_PROPERTY)!!)
        assertTrue(probe.isFile, "the native integration-test probe was compiled")
        dir = createTempDirectory("indagium-command-supervisor-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun spec(vararg args: String, timeoutMs: Long = 10_000, cap: Int = DEFAULT_OUTPUT_CAP_BYTES, stdin: ByteArray? = null) =
        spec(args.toList(), timeoutMs, cap, stdin)

    private fun spec(args: List<String>, timeoutMs: Long = 10_000, cap: Int = DEFAULT_OUTPUT_CAP_BYTES, stdin: ByteArray? = null) =
        HostCommandSpec(listOf(probe.absolutePath) + args, timeoutMs = timeoutMs, outputCapBytes = cap, stdin = stdin)

    private fun run(spec: HostCommandSpec) = runBlocking { runner.run(spec) }

    private fun awaitPid(file: File): Long = runBlocking {
        withTimeout(AWAIT_MS) {
            while (!file.isFile) kotlinx.coroutines.delay(POLL_MS)
            file.readText().trim().toLong()
        }
    }

    private fun awaitDead(pid: Long) = runBlocking {
        withTimeout(AWAIT_MS) {
            while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) kotlinx.coroutines.delay(POLL_MS)
        }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "process $pid survived the supervisor")
    }

    @Test
    fun preservesArgumentBoundariesEnvironmentWorkingDirectoryAndUnicode() {
        val args = listOf("space value", "embedded\"quote", "backslash\\tail", "unicode-ї-🚀", "")
        val argv = run(spec(listOf("--argv") + args))
        assertEquals(0, argv.exitCode)
        assertEquals(args, argv.stdoutText().split('\n').dropLast(1))

        val environment = run(
            HostCommandSpec(
                command = listOf(probe.absolutePath, "--environment"),
                workingDir = dir,
                environment = mapOf("INDAGIUM_SUPERVISOR_TEST" to "space quote \" Unicode 🚀"),
            ),
        )
        assertEquals("${dir.canonicalPath}|space quote \" Unicode 🚀\n", environment.stdoutText())
    }

    @Test
    fun preservesStdinOutputCapsAndLegitimate126And127ExitCodes() {
        val input = "stdin with spaces and Unicode: тест 🚀\n".repeat(100).toByteArray()
        val echoed = run(spec("--stdin", stdin = input))
        assertEquals(input.toList(), echoed.stdout.toList())

        val capped = run(spec("--emit", "12000", cap = 256))
        assertEquals(256, capped.stdout.size)
        assertEquals(256, capped.stderr.size)
        assertTrue(capped.truncated)
        for (exitCode in listOf(0, 17, 126, 127)) {
            assertEquals(exitCode, run(spec("--exit", exitCode.toString())).exitCode)
        }
    }

    @Test
    fun missingExecutableIsStillALaunchFailure() {
        val missing = File(dir, "does-not-exist").absolutePath
        assertFailsWith<IOException> { run(HostCommandSpec(listOf(missing))) }
    }

    @Test
    fun anImmediateCommandExitStillTerminatesAResistantChild() {
        val pidFile = File(dir, "child.pid")
        val sentinel = File(dir, "sentinel")
        val result = run(spec("--spawn-resistant-child", pidFile.absolutePath, sentinel.absolutePath))
        assertEquals(0, result.exitCode)
        awaitDead(awaitPid(pidFile))
        assertFalse(sentinel.exists(), "the child did not outlive its command parent")
    }

    @Test
    fun timeoutKillsTheLongRunningCommandAndItsChild() {
        val pidFile = File(dir, "timeout-child.pid")
        val sentinel = File(dir, "timeout-sentinel")
        val result = run(spec("--spawn-and-wait", pidFile.absolutePath, sentinel.absolutePath, timeoutMs = 450))
        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
        awaitDead(awaitPid(pidFile))
        assertFalse(sentinel.exists())
    }

    @Test
    fun cancellationKillsTheLongRunningCommandAndItsChild() = runBlocking {
        val pidFile = File(dir, "cancel-child.pid")
        val sentinel = File(dir, "cancel-sentinel")
        val job = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            runner.run(spec("--spawn-and-wait", pidFile.absolutePath, sentinel.absolutePath))
        }
        val pid = awaitPid(pidFile)
        job.cancelAndJoin()
        awaitDead(pid)
        assertFalse(sentinel.exists())
    }

    @Test
    fun startupCancellationNeverLeavesAChildOutsideTheSupervisedProcessTree() = runBlocking {
        for (delayMs in listOf(1L, 3L, 5L, 10L, 20L, 50L)) {
            val pidFile = File(dir, "startup-$delayMs.pid")
            val sentinel = File(dir, "startup-$delayMs.sentinel")
            val job = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                runner.run(spec("--spawn-and-wait", pidFile.absolutePath, sentinel.absolutePath))
            }
            kotlinx.coroutines.delay(delayMs)
            job.cancelAndJoin()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_200)
            while (System.nanoTime() < deadline && !pidFile.exists() && !sentinel.exists()) kotlinx.coroutines.delay(10)
            if (pidFile.isFile) awaitDead(pidFile.readText().trim().toLong())
            assertFalse(sentinel.exists(), "startup cancellation at ${delayMs}ms must not leave a child running")
        }
    }

    @Test
    fun windowsCmdScriptPayloadSurvivesTheNativeSupervisorCommandLine() {
        assumeTrue("cmd.exe quoting is Windows-specific", System.getProperty("os.name").lowercase().contains("win"))
        val payload = "space \"quoted\" backslash\\tail Unicode-ї"
        val commandTemplate = "chcp 65001 >nul & echo \"quoted template\" %payload%"
        val script = TestScript(
            id = "script-supervisor-cmd",
            toolName = "cmd_echo",
            params = listOf(ScriptParam("payload")),
            commandTemplate = commandTemplate,
            permission = ScriptPermission.AUTO,
        )
        val scriptRunner = TestScriptRunner(commandRunner = runner, hostShell = listOf("cmd.exe", "/d", "/s", "/c"))
        val direct = ProcessBuilder("cmd.exe", "/d", "/s", "/c", commandTemplate).directory(dir).apply {
            environment()["payload"] = payload
        }.start()
        val directOutput = direct.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(direct.waitFor(5, TimeUnit.SECONDS), "the direct cmd.exe oracle completed")
        assertEquals(0, direct.exitValue(), direct.errorStream.readBytes().toString(Charsets.UTF_8))
        val result = runBlocking {
            scriptRunner.run(script, mapOf("payload" to payload), ScriptRunContext(runDir = dir))
        }

        val finished = assertIs<ScriptRunOutcome.Finished>(result, result.toString())
        assertEquals(0, finished.result.exitCode, finished.result.stderr)
        assertEquals(directOutput, finished.result.stdout, "supervision preserves cmd.exe /d /s /c command-line behavior")
        assertEquals("\"quoted template\" $payload", directOutput.trimEnd('\r', '\n'))
    }
}
