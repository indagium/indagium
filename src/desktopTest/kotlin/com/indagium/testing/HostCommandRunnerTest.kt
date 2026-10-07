package com.indagium.testing

import com.indagium.testing.script.BACKGROUND_PROCESS_WARNING
import com.indagium.testing.script.HostCommandSpec
import com.indagium.testing.script.ProcessBuilderHostCommandRunner
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeFalse
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SH = "/bin/sh"
private const val POLL_MS = 25L
private const val WAIT_LIMIT_MS = 5_000L
private const val NANOS_PER_MS = 1_000_000L
private const val DEFAULT_TIMEOUT_MS = 10_000L
private const val DEFAULT_CAP_BYTES = 64 * 1024

class HostCommandRunnerTest {
    private val runner = ProcessBuilderHostCommandRunner()
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        dir = createTempDirectory("indagium-host-cmd").toFile()
    }

    @AfterTest
    fun tearDown() {
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun sh(
        script: String,
        workingDir: File? = null,
        environment: Map<String, String> = emptyMap(),
        stdin: ByteArray? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        cap: Int = DEFAULT_CAP_BYTES,
    ) = HostCommandSpec(listOf(SH, "-c", script), workingDir, environment, stdin, timeoutMs, cap)

    private fun run(spec: HostCommandSpec) = runBlocking { runner.run(spec) }

    private fun isAlive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private fun awaitPidFile(file: File): Long {
        val deadline = System.nanoTime() + WAIT_LIMIT_MS * NANOS_PER_MS
        while (System.nanoTime() < deadline) {
            file.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        error("no pid written to $file")
    }

    private fun awaitDead(pid: Long) {
        val deadline = System.nanoTime() + WAIT_LIMIT_MS * NANOS_PER_MS
        while (isAlive(pid) && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        assertFalse(isAlive(pid), "process $pid is still running")
    }

    @Test
    fun runsInTheGivenWorkingDirectory() {
        val result = run(sh("pwd -P", workingDir = dir))
        assertEquals(dir.canonicalPath, result.stdoutText().trim())
        assertEquals(0, result.exitCode)
    }

    @Test
    fun explicitEnvironmentIsMergedOntoTheInheritedOne() {
        val result = run(sh("printf '%s|%s' \"\$ONLY_FOR_TEST\" \"\${PATH:+has-path}\"", environment = mapOf("ONLY_FOR_TEST" to "v a l")))
        assertEquals("v a l|has-path", result.stdoutText())
    }

    @Test
    fun stdinBytesAreDeliveredAndEndOfInputIsSignalled() {
        val bytes = "line one\nline two\n".toByteArray()
        assertEquals("line one\nline two\n", run(sh("cat", stdin = bytes)).stdoutText())
    }

    @Test
    fun withoutStdinACommandThatReadsItSeesEndOfFileAtOnce() {
        val result = run(sh("cat; printf done"))
        assertEquals("done", result.stdoutText())
        assertFalse(result.timedOut)
    }

    @Test
    fun aChildThatNeverReadsStdinDoesNotBlockTheCaller() {
        val big = ByteArray(2 * 1024 * 1024) { 'x'.code.toByte() }
        val result = run(sh("printf ok", stdin = big))
        assertEquals("ok", result.stdoutText())
    }

    @Test
    fun exitCodeStdoutAndStderrAreSeparate() {
        val result = run(sh("printf out; printf err >&2; exit 3"))
        assertEquals(3, result.exitCode)
        assertEquals("out", result.stdoutText())
        assertEquals("err", result.stderrText())
        assertFalse(result.truncated)
        assertFalse(result.timedOut)
    }

    @Test
    fun outputIsBoundedPerStreamAndFlagged() {
        val result = run(sh("head -c 10000 /dev/zero | tr '\\0' o; head -c 10000 /dev/zero | tr '\\0' e >&2", cap = 256))
        assertEquals(256, result.stdout.size)
        assertEquals(256, result.stderr.size)
        assertTrue(result.truncated)
        assertEquals(0, result.exitCode, "the child was drained, not blocked on a full pipe")
    }

    @Test
    fun aTimeoutKillsTheCommandAndItsChildProcesses() {
        val pidFile = File(dir, "child.pid")
        val result = run(sh("sleep 60 & echo \$! > '${pidFile.absolutePath}'; wait", timeoutMs = 600))
        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
        awaitDead(awaitPidFile(pidFile))
    }

    @Test
    fun cancellingTheCallerKillsTheProcessTree() = runBlocking {
        val pidFile = File(dir, "child.pid")
        val job = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            runner.run(sh("sleep 60 & echo \$! > '${pidFile.absolutePath}'; wait"))
        }
        val pid = awaitPidFile(pidFile)
        assertTrue(isAlive(pid))
        job.cancelAndJoin()
        awaitDead(pid)
    }

    @Test
    fun durationIsMeasured() {
        val result = run(sh("sleep 0.2"))
        assertTrue(result.durationMs >= 150, "was ${result.durationMs}")
    }

    @Test
    fun aCommandThatCannotStartThrows() {
        assertFailsWith<IOException> { run(HostCommandSpec(listOf(File(dir, "no-such-binary").absolutePath))) }
        assertFailsWith<IOException> { run(sh("true", workingDir = File(dir, "missing"))) }
    }

    @Test
    fun theSpecRejectsNonsense() {
        assertFailsWith<IllegalArgumentException> { HostCommandSpec(emptyList()) }
        assertFailsWith<IllegalArgumentException> { HostCommandSpec(listOf(SH), timeoutMs = 0) }
        assertFailsWith<IllegalArgumentException> { HostCommandSpec(listOf(SH), outputCapBytes = 0) }
    }

    @Test
    fun legitimate126And127ExitCodesAreReportedAsTheCommandsOwn() {
        for (exitCode in listOf(0, 17, 126, 127)) {
            val result = run(sh("exit $exitCode"))
            assertEquals(exitCode, result.exitCode)
            assertFalse(result.timedOut)
        }
    }

    @Test
    fun unicodeAndQuotesInTheEnvironmentSurviveUntouched() {
        val value = "space quote \" backslash\\tail Unicode-\u0457 \uD83D\uDE80"
        val result = run(sh("printf '%s' \"\$INDAGIUM_HOST_TEST\"", environment = mapOf("INDAGIUM_HOST_TEST" to value)))
        assertEquals(value, result.stdoutText())
    }

    @Test
    fun aCleanCommandHasNoWarnings() {
        val result = run(sh("(sleep 0.1; true) & wait; printf ok"))
        assertEquals("ok", result.stdoutText())
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
    }

    @Test
    fun aCommandThatLeavesABackgroundProcessRunningIsReportedAndTheLeftoverIsNotStopped() {
        val pidFile = File(dir, "leftover.pid")
        val result = run(sh("sleep 30 & echo \$! > '${pidFile.absolutePath}'; sleep 0.3", timeoutMs = DEFAULT_TIMEOUT_MS))
        val pid = awaitPidFile(pidFile)
        try {
            assertEquals(0, result.exitCode)
            assertFalse(result.timedOut)
            assertEquals(listOf(BACKGROUND_PROCESS_WARNING), result.warnings)
            assertTrue(isAlive(pid), "a command that ended on its own is only reported on, its leftover is not killed")
        } finally {
            ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
        }
    }

    @Test
    fun aTimedOutCommandWhoseChildrenAreKilledHasNoWarnings() {
        val pidFile = File(dir, "child.pid")
        val result = run(sh("sleep 60 & echo \$! > '${pidFile.absolutePath}'; wait", timeoutMs = 600))
        assertTrue(result.timedOut)
        awaitDead(awaitPidFile(pidFile))
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
    }
}
