@file:Suppress("MagicNumber")

package com.indagium.ui

import com.indagium.model.LogAnalysis
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.utils.AppendOnlyLogList
import com.indagium.utils.appendLogEntries
import com.indagium.utils.listArchiveLogCandidates
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Closing a tab releases its row store after a grace period, including stale Compose snapshots. */
class ClosedTabRowsReleaseTest {
    private fun entries(range: IntRange) = range.map { LogEntry(it, "10:00:00.000", LogLevel.I, "Tag", "msg $it") }

    private fun appendOnly(range: IntRange) = appendLogEntries(entries(1..3), entries(4..range.last)) as AppendOnlyLogList

    private fun newState(): AppState {
        val app = AppState(autosaveFile = Files.createTempFile("closed-rows-release-autosave", "").toFile(), autoExportNotes = false)
        app.closedTabRowsReleaseDelayMs = 150
        return app
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), message)
    }

    // captureSessionId marks the tab as a capture so closing it asks for a trim whatever its size.
    private fun captureTab(id: String, rows: List<LogEntry>) = mkTab(id, "$id.log", rows).copy(captureSessionId = "session-$id")

    @Test
    fun closingATabReleasesItsRowsAfterTheGraceDelayThenRequestsTheTrim() {
        val app = newState()
        val rows = appendOnly(1..10)
        val events = mutableListOf<Pair<String, Boolean>>()
        val trimmed = CountDownLatch(1)
        app.heapTrimRequester = { reason ->
            synchronized(events) { events += reason to rows.isReleased }
            trimmed.countDown()
        }
        app.tabs = listOf(captureTab("t1", rows), mkTab("t2", "t2.log", entries(1..3)))

        app.closeTab("t1")

        assertFalse(rows.isReleased, "the rows must outlive the close by the grace delay")
        assertTrue(synchronized(events) { events.isEmpty() }, "no trim before the release: it would free nothing")
        assertTrue(trimmed.await(10, TimeUnit.SECONDS), "the release job must request the trim")
        assertTrue(rows.isReleased)
        // One trim only (the close-time request is suppressed), issued after the release.
        assertEquals(listOf("released closed capture rows" to true), synchronized(events) { events.toList() })
    }

    @Test
    fun aStoreStillUsedByARemainingTabIsNotReleased() {
        val app = newState()
        val shared = appendOnly(1..10)
        val trimReasons = mutableListOf<String>()
        val trimmed = CountDownLatch(1)
        app.heapTrimRequester = { reason ->
            synchronized(trimReasons) { trimReasons += reason }
            trimmed.countDown()
        }
        // t2 views the very same store (e.g. a duplicate sharing logData), so closing t1 must not drop it.
        app.tabs = listOf(captureTab("t1", shared), mkTab("t2", "t2.log", shared))

        app.closeTab("t1")

        assertTrue(trimmed.await(10, TimeUnit.SECONDS))
        assertFalse(shared.isReleased)
        assertEquals(1, shared[0].id, "a surviving tab keeps reading the rows")
        // Nothing was released, so the trim the close skipped is still issued, with its original reason.
        assertEquals(listOf("closed large or capture tab"), synchronized(trimReasons) { trimReasons.toList() })
    }

    @Test
    fun aPlainListTabClosesExactlyAsBefore() {
        val app = newState()
        val rows = entries(1..5)
        val trimReasons = mutableListOf<String>()
        app.heapTrimRequester = { reason -> trimReasons += reason }
        val plainTab = captureTab("t1", rows).copy(logData = rows, rmap = mkRmap(rows))
        app.tabs = listOf(plainTab, mkTab("t2", "t2.log", entries(1..3)))

        app.closeTab("t1")

        // Synchronous, on the closing thread, with the original reason; nothing to release.
        assertEquals(listOf("closed large or capture tab"), trimReasons)
        assertEquals(entries(1..5), rows, "a plain list is never touched")
    }

    @Test
    fun aSmallNonCaptureTabIsReleasedButDoesNotRequestATrim() {
        val app = newState()
        val rows = appendOnly(1..10)
        val trimReasons = mutableListOf<String>()
        app.heapTrimRequester = { reason -> synchronized(trimReasons) { trimReasons += reason } }
        app.tabs = listOf(mkTab("t1", "t1.log", rows), mkTab("t2", "t2.log", entries(1..3)))

        app.closeTab("t1")

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!rows.isReleased && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(rows.isReleased)
        assertTrue(synchronized(trimReasons) { trimReasons.isEmpty() }, "close semantics for small tabs stay trim-free")
    }

    @Test
    fun ordinaryAndZipTabsReleaseStaleRowsAndIndexesAfterClose() {
        val dir = Files.createTempDirectory("closed-source-rows").toFile()
        val contents = "10:00:00.000 I/Tag: synthetic row one\n10:00:00.100 I/Tag: synthetic row two\n"
        val file = File(dir, "sample.log").also { it.writeText(contents) }
        val zip = File(dir, "sample.zip").also { archive ->
            ZipOutputStream(archive.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("logs/sample.log"))
                output.write(contents.toByteArray())
                output.closeEntry()
            }
        }
        val app = newState()
        try {
            val candidate = listArchiveLogCandidates(zip).single()
            val opens = listOf<() -> String?>(
                { app.openFile(file) },
                { app.openZipEntryAsIs(zip, candidate) },
                { app.openZipEntryAsIs(zip, candidate) },
                { app.openZipEntryAsIs(zip, candidate) },
            )
            val closedSnapshots = mutableListOf<LogTab>()
            for (open in opens) {
                val id = assertNotNull(open())
                waitUntil("synthetic file should load") { app.tab(id)?.logData?.size == 2 }
                val stale = assertNotNull(app.tab(id))
                assertEquals(2, stale.rmap.size)
                app.closeTab(id)
                assertEquals(2, stale.logData.size, "the grace period protects outgoing readers")
                closedSnapshots += stale
                waitUntil("closed source rows and index should be released") {
                    closedSnapshots.all { it.logData.isEmpty() && it.rmap.isEmpty() }
                }
            }
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun duplicateTabKeepsSharedRowsAndIndexUntilLastOwnerCloses() {
        val app = newState()
        try {
            val original = captureTab("original", entries(1..10))
            val duplicate = mkTab("duplicate", "duplicate.log", original.logData)
            val delayCompleted = CountDownLatch(1)
            app.heapTrimRequester = { delayCompleted.countDown() }
            app.tabs = listOf(original, duplicate)
            app.closeTab(original.id)
            assertTrue(delayCompleted.await(10, TimeUnit.SECONDS))
            assertEquals(10, duplicate.logData.size)
            assertEquals(10, duplicate.rmap.size)
            assertEquals(1, duplicate.rmap[1]?.id)
            app.closeTab(duplicate.id)
            waitUntil("last owner releases shared rows and both index facades") {
                original.logData.isEmpty() && original.rmap.isEmpty() &&
                    duplicate.logData.isEmpty() && duplicate.rmap.isEmpty()
            }
        } finally {
            app.close()
        }
    }

    @Test
    fun closingOrdinaryTabDefersReleaseUntilTheLastReaderUnpins() {
        val app = newState()
        try {
            val tab = captureTab("pinned-ordinary", entries(1..10))
            app.tabs = listOf(tab)
            val first = app.pinRows(tab)
            val second = app.pinRows(tab)
            app.closeTab(tab.id)
            Thread.sleep(600)
            assertEquals(10, tab.logData.size)
            first.close()
            assertEquals(10, tab.rmap.size)
            second.close()
            waitUntil("last reader releases ordinary rows") { tab.logData.isEmpty() && tab.rmap.isEmpty() }
        } finally {
            app.close()
        }
    }

    @Test
    fun largeOrdinaryTabRequestsTrimOnlyAfterItsRowsAreReleased() {
        val app = newState()
        try {
            val tab = mkTab("large", "synthetic.log", entries(1..50_000), analysis = LogAnalysis())
            val trimmed = CountDownLatch(1)
            val events = mutableListOf<Pair<String, Boolean>>()
            app.heapTrimRequester = { reason ->
                synchronized(events) { events += reason to tab.logData.isEmpty() }
                trimmed.countDown()
            }
            app.tabs = listOf(tab)
            app.closeTab(tab.id)
            assertTrue(trimmed.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("released closed capture rows" to true), synchronized(events) { events.toList() })
        } finally {
            app.close()
        }
    }
}
