package com.indagium

import com.indagium.model.LogEntry
import com.indagium.model.TabContentState
import com.indagium.ui.AppState
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PROOF_WAIT_SECONDS = 15L

/**
 * Tailing a profile file whose last line is unfinished: the tailer re-reads that line whole and replaces the row, but only when the
 * file is proven unchanged since the tab was parsed, and a rewrite of the file is a rotation even when the new content is longer
 * than the offset the tail restarted from.
 */
class TailUnfinishedLineProofTest {
    private var dir: File? = null
    private var state: AppState? = null

    @AfterTest
    fun tearDown() {
        state?.close()
        dir?.deleteRecursively()
    }

    private fun newState(): AppState {
        val directory = createTempDirectory("tail-unfinished-proof").toFile().also { dir = it }
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
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROOF_WAIT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "$what not reached within ${PROOF_WAIT_SECONDS}s" }
            Thread.sleep(5)
        }
    }

    private fun AppState.openPlain(file: File): String {
        val tabId = requireNotNull(openFileWithLogFormatProfile(file, "plain-text"))
        waitUntil("loaded") { tab(tabId)?.contentState == TabContentState.LOADED }
        return tabId
    }

    private fun rows(app: AppState, tabId: String): List<LogEntry> = app.tab(tabId)!!.logData

    // ── rotation hidden by the replay offset ─────────────────────────────────────────────────

    @Test
    fun aRewriteShorterThanTheFileWasIsARotationNotAContinuationOfTheReplayedLine() {
        val app = newState()
        val file = File(dir!!, "one-line.log").apply { writeText("the one unfinished line of the old file") }
        val tabId = app.openPlain(file)
        val old = rows(app, tabId).single()
        app.startTailing(tabId)
        assertTrue(app.tab(tabId)!!.tailing)

        // Shorter than before (39 bytes), longer than the offset the tail started from (0).
        file.writeText("new a\nnew b\n")
        waitUntil("the new file's rows") { rows(app, tabId).size >= 3 }
        app.drainAndStopTailing(tabId)

        val after = rows(app, tabId)
        assertEquals(listOf(old.msg, "new a", "new b"), after.map { it.msg })
        assertEquals(old, after.first(), "the old row is not overwritten as the continuation of a line it was not part of")
    }

    // ── the unfinished line must be the tab's own ────────────────────────────────────────────

    @Test
    fun aLineAppendedAfterTheParseThatMerelyContainsTheLastRowIsNotItsReplacement() {
        val app = newState()
        val file = File(dir!!, "grown.log").apply { writeText("alpha\nfoo\n") }
        val tabId = app.openPlain(file)
        val before = rows(app, tabId)
        assertEquals(listOf("alpha", "foo"), before.map { it.msg })

        // A new, unfinished line the tab has not seen; its text begins with the tab's last row.
        file.appendText("foo bar")
        app.startTailing(tabId)
        assertTrue(app.tab(tabId)!!.tailing)
        file.appendText(" and more\nnext\n")
        waitUntil("the appended lines") { rows(app, tabId).last().msg == "next" }
        app.drainAndStopTailing(tabId)

        val after = rows(app, tabId)
        assertEquals(before, after.take(2), "row 2 is untouched")
        assertEquals("next", after.last().msg)
    }

    @Test
    fun aRepeatedIdenticalLineAppendedAfterTheParseDoesNotReplaceTheRowItEquals() {
        val app = newState()
        val file = File(dir!!, "repeated.log").apply { writeText("x\nrep\n") }
        val tabId = app.openPlain(file)
        val before = rows(app, tabId)

        file.appendText("rep")
        app.startTailing(tabId)
        file.appendText(" two\nz\n")
        waitUntil("the appended lines") { rows(app, tabId).last().msg == "z" }
        app.drainAndStopTailing(tabId)

        val after = rows(app, tabId)
        assertEquals(before, after.take(2), "the second row is still the line it was parsed from")
    }

    @Test
    fun anUnfinishedLastLineOfTheUnchangedFileIsStillReplacedByItsWholeLine() {
        val app = newState()
        val file = File(dir!!, "unfinished.log").apply { writeText("x\nrep") }
        val tabId = app.openPlain(file)
        assertEquals(listOf("x", "rep"), rows(app, tabId).map { it.msg })

        app.startTailing(tabId)
        file.appendText(" two\nz\n")
        waitUntil("the whole line") { rows(app, tabId).last().msg == "z" }
        app.drainAndStopTailing(tabId)

        assertEquals(listOf("x", "rep two", "z"), rows(app, tabId).map { it.msg })
    }

    // ── where the length comes from ──────────────────────────────────────────────────────────

    @Test
    fun everyTabParsedFromAFileRecordsTheLengthMeasuredBeforeTheParse() {
        val app = newState()
        val file = File(dir!!, "recorded.log").apply { writeText("a\nb\nc") }
        val tabId = app.openPlain(file)
        assertEquals(file.length(), app.tab(tabId)!!.profileSourceByteLength, "open")

        val reparsedId = assertNotNull(
            kotlinx.coroutines.runBlocking { app.reparseTabWithLogFormatProfileAwait(tabId, "logfmt") },
        )
        assertEquals(file.length(), app.tab(reparsedId)!!.profileSourceByteLength, "reparse as another profile")

        // Tailing reads past the parse: the recorded length no longer describes the tab.
        app.startTailing(tabId)
        file.appendText("\nd\n")
        waitUntil("tailed") { rows(app, tabId).size >= 4 }
        app.drainAndStopTailing(tabId)
        assertNull(app.tab(tabId)!!.profileSourceByteLength)
    }

    @Test
    fun aRestoredTabRecordsTheLengthAgainWhenItsRowsLoad() {
        val app = newState()
        val directory = dir!!
        val file = File(directory, "restore.log").apply { writeText("a\nb") }
        val tabId = app.openPlain(file)
        app.autosaveNow()
        app.close()

        val restored = AppState(
            autosaveFile = File(directory, "state.cache"),
            restoreOnCreate = true,
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archive-cache"),
            customCommandsDir = File(directory, "commands"),
            filterBackupsDir = File(directory, "filter-backups"),
            autoExportNotes = false,
            controlTokenFile = File(directory, "control-token"),
            sourceIndexFile = File(directory, "source-index"),
            testingDir = File(directory, "testing"),
        )
        state = restored
        val restoredId = restored.tabs.first { it.sourcePath == file.absolutePath }.id
        assertNull(restored.tab(restoredId)!!.profileSourceByteLength, "an unloaded shell has not parsed anything yet")
        restored.startPendingRestoredTabLoads()
        restored.activateTab(restoredId)
        waitUntil("restored rows") { restored.tab(restoredId)?.contentState == TabContentState.LOADED }
        assertEquals(file.length(), restored.tab(restoredId)!!.profileSourceByteLength)
        assertTrue(tabId.isNotEmpty())
    }
}
