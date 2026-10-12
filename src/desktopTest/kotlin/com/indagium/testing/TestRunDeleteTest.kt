@file:Suppress("MagicNumber", "ktlint:standard:max-line-length", "MaxLineLength")

package com.indagium.testing

import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.RunSummary
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.newRunId
import com.indagium.testing.model.summary
import com.indagium.testing.run.StartRunResult
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.TEST_RUN_FILE_NAME
import com.indagium.testing.store.TestRunStore
import com.indagium.ui.deletableRuns
import com.indagium.ui.formatBytes
import com.indagium.ui.runsUsageLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeFalse
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Deleting stored runs: the store removes exactly one run folder and refuses anything that is not one; the coordinator refuses an active run. */
class TestRunDeleteTest {
    private val dir: File = createTempDirectory("indagium-run-delete").toFile()
    private val runsDir = File(dir, "test-runs")
    private val store = TestRunStore { runsDir }
    private var harness: RunHarness? = null

    @AfterTest
    fun tearDown() {
        harness?.close()
        dir.deleteRecursively()
    }

    private fun savedRun(status: RunStatus = RunStatus.PASSED, createdAt: Long = 1_000L, sink: TestRunStore = store): TestRun {
        val suite = suiteOf(caseOf("Login", step("Open the app")))
        val run = TestRun(
            id = newRunId(), suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
            config = RunConfig(suite.id, null, listOf(externalLane())), lanes = emptyList(),
            status = status, createdAt = createdAt, finishedAt = createdAt + 10,
        )
        sink.save(run).getOrThrow()
        return run
    }

    @Test
    fun deleteRemovesOnlyThatRunsFolderWithEverythingInIt() {
        val doomed = savedRun()
        val kept = savedRun(createdAt = 2_000L)
        File(store.runDir(doomed.id), "lanes/lane-a/screens").mkdirs()
        File(store.runDir(doomed.id), "lanes/lane-a/screens/shot.png").writeBytes(ByteArray(2048))

        assertEquals(StoreResult.Ok(Unit), store.delete(doomed.id))

        assertTrue(!store.runDir(doomed.id).exists(), "the whole folder is gone")
        assertEquals(listOf(kept.id), store.list().map { it.id }, "the other run is untouched")
        assertTrue(File(store.runDir(kept.id), TEST_RUN_FILE_NAME).isFile)
    }

    @Test
    fun anUnknownRunIsNotFoundAndAnUnsafeIdIsRefusedWithoutTouchingAnything() {
        val kept = savedRun()
        val outside = File(dir, "precious").also { it.mkdirs() }
        File(outside, TEST_RUN_FILE_NAME).writeText("{}")

        assertIs<StoreResult.NotFound>(store.delete("run-that-never-existed"))
        assertIs<StoreResult.Invalid>(store.delete("../precious"))
        assertIs<StoreResult.Invalid>(store.delete("a/b"))
        assertIs<StoreResult.Invalid>(store.delete(""))

        assertTrue(File(outside, TEST_RUN_FILE_NAME).isFile, "a folder next to the runs folder is never deleted")
        assertTrue(File(store.runDir(kept.id), TEST_RUN_FILE_NAME).isFile)
    }

    @Test
    fun aFolderWithoutRunJsonIsNotTreatedAsARun() {
        savedRun()
        val stray = File(runsDir, "my-notes").also { it.mkdirs() }
        File(stray, "todo.txt").writeText("keep me")

        assertIs<StoreResult.Invalid>(store.delete("my-notes"))

        assertEquals("keep me", File(stray, "todo.txt").readText())
    }

    @Test
    fun aSymbolicLinkInTheRunsFolderIsRefusedAndItsTargetKept() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        savedRun()
        val target = File(dir, "elsewhere").also { it.mkdirs() }
        File(target, TEST_RUN_FILE_NAME).writeText("{}")
        File(target, "data.bin").writeBytes(ByteArray(10))
        Files.createSymbolicLink(File(runsDir, "linked-run").toPath(), target.toPath())

        assertIs<StoreResult.Invalid>(store.delete("linked-run"))

        assertTrue(File(target, "data.bin").isFile, "the link's target is untouched")
    }

    @Test
    fun sizesReportEachRunFolderAndTheTotalIsWhatTheHeaderQuotes() {
        val small = savedRun()
        val big = savedRun(createdAt = 2_000L)
        File(store.runDir(big.id), "payload.bin").writeBytes(ByteArray(5000))

        val sizes = store.sizes()

        assertEquals(setOf(small.id, big.id), sizes.keys)
        assertTrue(sizes.getValue(big.id) >= sizes.getValue(small.id) + 5000)
        assertEquals(sizes.getValue(big.id), store.sizeOf(big.id))
        assertEquals(0L, store.sizeOf("missing"))
        assertEquals("2 runs · ${formatBytes(sizes.values.sum())}", runsUsageLine(2, sizes, listOf(small.id, big.id)))
        assertEquals("1 run", runsUsageLine(1, null, listOf(small.id)))
    }

    @Test
    fun formatBytesUsesBinaryUnits() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("340 KB", formatBytes(340L * 1024))
        assertEquals("12.4 MB", formatBytes((12.4 * 1024 * 1024).toLong()))
        assertEquals("1.2 GB", formatBytes((1.2 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun theBulkSelectionNeverIncludesQueuedOrRunningRuns() {
        val shown: List<RunSummary> = listOf(
            savedRun(RunStatus.PASSED).summary(),
            savedRun(RunStatus.RUNNING).summary(),
            savedRun(RunStatus.QUEUED).summary(),
            savedRun(RunStatus.FAILED).summary(),
            savedRun(RunStatus.CANCELLED).summary(),
        )
        val live = shown[3].id

        assertEquals(
            listOf(RunStatus.PASSED, RunStatus.FAILED, RunStatus.CANCELLED),
            deletableRuns(shown).map { it.status },
            "queued and running are excluded by their status",
        )
        assertEquals(
            listOf(RunStatus.PASSED, RunStatus.CANCELLED),
            deletableRuns(shown, activeRunIds = setOf(live)).map { it.status },
            "a run of this launch that has not finished is excluded by its id",
        )
        assertTrue(deletableRuns(emptyList()).isEmpty())
    }

    @Test
    fun theCoordinatorRefusesARunThatIsStillRunningAndDeletesItOnceItEnded() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        val marker = File.createTempFile("ask-me", ".txt").also { it.delete() }
        val script = hostScript("ask_me", "printf ran > '${marker.absolutePath}'", ScriptPermission.ASK)
        val suite = suiteOf(caseOf("Needs a script", step("Run the script, then report")))
        val provider = TurnProvider(listOf(toolTurn("ask_me"), finishTurn("fail", "denied"), textTurn))
        val h = RunHarness(TestLibrary(suites = listOf(suite), scripts = listOf(script)), provider).also { harness = it }

        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(h.config(suite)) })
        runBlocking {
            // The run waits for the confirmation card: it is running.
            withTimeout(WAIT_MS) { while (h.coordinator.pendingConfirmations().isEmpty()) delay(POLL_MS) }
            assertEquals(RunStatus.RUNNING, h.coordinator.run(started.runId)?.status)
            val refused = h.coordinator.deleteRun(started.runId)
            assertIs<StoreResult.Invalid>(refused)
            assertTrue(h.coordinator.run(started.runId) != null, "a refused delete forgets nothing")

            h.coordinator.cancel(started.runId)
            withTimeout(WAIT_MS) { h.coordinator.awaitFinished(started.runId) }
        }
        val dirOfRun = h.store.runDir(started.runId)
        assertTrue(dirOfRun.exists())

        assertEquals(StoreResult.Ok(Unit), runBlocking { h.coordinator.deleteRun(started.runId) })

        assertTrue(!dirOfRun.exists())
        assertTrue(runBlocking { h.coordinator.listRuns() }.none { it.id == started.runId }, "the run left the list")
        marker.delete()
    }

    private companion object {
        const val WAIT_MS = 15_000L
        const val POLL_MS = 10L
    }
}
