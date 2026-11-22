package com.indagium

import com.indagium.model.TabContentState
import com.indagium.ui.AppState
import com.indagium.utils.IncrementalProfileParser
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val WAIT_SECONDS = 15L
private const val PROMPT_MS = 2_000L
private const val BLOCK_SECONDS = 6L
private const val TAILER_BLOCKS_MS = 1_300L

/**
 * The scan that rebuilds a root-array JSON tail's state must never be waited for by a stop or a rotation: both run under stateLock,
 * and the scan reads the whole file. A hook blocks the scan mid-way, as a slow disk on a huge file would.
 */
class TailRootArrayScanCancellationTest {
    private var dir: File? = null
    private var state: AppState? = null
    private val release = CountDownLatch(1)

    @AfterTest
    fun tearDown() {
        IncrementalProfileParser.seedScanHookForTesting = null
        release.countDown()
        state?.close()
        dir?.deleteRecursively()
    }

    private fun newState(): AppState {
        val directory = createTempDirectory("tail-scan-cancel").toFile().also { dir = it }
        return AppState(
            autosaveFile = File(directory, "state.cache"),
            restoreOnCreate = false,
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archive-cache"),
            customCommandsDir = File(directory, "commands"),
            filterBackupsDir = File(directory, "filter-backups"),
            autoExportNotes = false,
            controlTokenFile = File(directory, "control-token"),
            sourceIndexFile = File(directory, "source-index"),
            testingDir = File(directory, "testing"),
        ).also { state = it }
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "$what not reached within ${WAIT_SECONDS}s" }
            Thread.sleep(5)
        }
    }

    /** Opens [lines] as a JSON-lines tab and starts its tail with the root-array scan blocked mid-way. Returns the tab id and file. */
    private fun tailWithBlockedScan(app: AppState, lines: List<String>): Pair<String, File> {
        val file = File(requireNotNull(dir), "open-array.json").apply { writeText(lines.joinToString("\n", postfix = "\n")) }
        val entered = CountDownLatch(1)
        IncrementalProfileParser.seedScanHookForTesting = { scanned, _ ->
            if (scanned.absolutePath == file.absolutePath) {
                entered.countDown()
                // Bounded, so a scan that something waits for fails the test by time instead of hanging it.
                release.await(BLOCK_SECONDS, TimeUnit.SECONDS)
            }
        }
        val tabId = requireNotNull(app.openFileWithLogFormatProfile(file, "json-lines"))
        waitUntil("tab loaded") { app.tab(tabId)?.contentState == TabContentState.LOADED }
        app.startTailing(tabId)
        check(app.tab(tabId)!!.tailing) { "did not start tailing" }
        check(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the scan never started" }
        return tabId to file
    }

    private val openArray = listOf("[", """  {"message":"a"},""", """  {"message":"b",""")

    @Test
    fun stoppingWithTheDrainReturnsPromptlyWhileTheScanIsBlockedAndKeepsTheRowsRight() {
        val app = newState()
        val (tabId, file) = tailWithBlockedScan(app, openArray)
        file.appendText("   \"v\":1},\n  {\"message\":\"c\"}\n]\n")

        val started = System.nanoTime()
        app.drainAndStopTailing(tabId)
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue(tookMs < PROMPT_MS, "the drain waited ${tookMs}ms for a scan that is blocked")
        assertFalse(app.tab(tabId)!!.tailing)
        // The few lines drained without the scan's state are seeded from the last row: still one row per record, none doubled.
        assertEquals(listOf("a", "b", "c"), app.tab(tabId)!!.logData.map { it.msg })

        release.countDown()
        Thread.sleep(200)
        assertEquals(listOf("a", "b", "c"), app.tab(tabId)!!.logData.map { it.msg }, "a released scan must not touch the finished tab")
    }

    @Test
    fun aRotationRestartDoesNotWaitForTheBlockedScan() {
        val app = newState()
        val (tabId, file) = tailWithBlockedScan(app, openArray)
        // The file is rewritten shorter: the tailer restarts it from byte 0 (flushing the old parser under stateLock).
        file.writeText("{\"message\":\"x\"}\n")

        waitUntil("the rewritten file's row, while the scan is still blocked") { app.tab(tabId)!!.logData.any { it.msg == "x" } }
        assertTrue(app.tab(tabId)!!.tailing)
        assertEquals("a", app.tab(tabId)!!.logData.first().msg, "the rows shown before the rotation stay")

        release.countDown()
        Thread.sleep(200)
        assertTrue(app.tab(tabId)!!.logData.any { it.msg == "x" })
        app.stopTailing(tabId)
    }

    /** Appends lines and gives the tailer (polling every 500 ms) time to pick them up and block on the scan inside its append. */
    private fun appendAndLetTheTailerBlock(file: File, text: String) {
        file.appendText(text)
        Thread.sleep(TAILER_BLOCKS_MS)
    }

    private fun AppState.messages(tabId: String): List<String> = tab(tabId)!!.logData.map { it.msg }

    @Test
    fun stopThenStartAgainDoesNotWaitForTheScanTheOldTailerIsBlockedOn() {
        val app = newState()
        val (tabId, file) = tailWithBlockedScan(app, openArray)
        appendAndLetTheTailerBlock(file, "   \"v\":1},\n")

        app.stopTailing(tabId)
        assertFalse(app.tab(tabId)!!.tailing)

        // Before the fix the old tailer waited for the scan (6 s here), and a restart is refused until it has ended.
        val started = System.nanoTime()
        waitUntil("a restarted tail") {
            app.startTailing(tabId)
            app.tab(tabId)!!.tailing
        }
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(tookMs < PROMPT_MS, "the restart waited ${tookMs}ms for a scan that is blocked")

        // The old tail's batch landed once, before the new tail's: nothing is doubled or reordered afterwards.
        release.countDown()
        file.appendText("  {\"message\":\"c\"}\n]\n")
        waitUntil("the last row") { app.messages(tabId).contains("c") }
        app.drainAndStopTailing(tabId)
        assertEquals(listOf("a", "b", "c"), app.messages(tabId))
    }

    @Test
    fun pausingReturnsPromptlyWhileTheScanIsBlockedAndResumeContinuesFromTheLastRow() {
        val app = newState()
        val (tabId, file) = tailWithBlockedScan(app, openArray)
        appendAndLetTheTailerBlock(file, "   \"v\":1},\n")

        val started = System.nanoTime()
        assertTrue(app.pauseTailing(tabId))
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(tookMs < PROMPT_MS, "the pause waited ${tookMs}ms for a scan that is blocked")
        assertFalse(app.tab(tabId)!!.tailing)

        release.countDown()
        file.appendText("  {\"message\":\"c\"}\n]\n")
        assertTrue(app.resumeTailing(tabId))
        waitUntil("the last row after resume") { app.messages(tabId).contains("c") }
        app.drainAndStopTailing(tabId)
        // One row per record: the cancelled scan left the parser to be seeded from the last row, not scanned again or left unseeded.
        assertEquals(listOf("a", "b", "c"), app.messages(tabId))
    }
}
