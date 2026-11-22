package com.indagium

import com.indagium.model.AnnBlock
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.SharedNoteClosedSource
import com.indagium.model.TabContentState
import com.indagium.ui.AppState
import com.indagium.ui.GoToRowsResult
import com.indagium.ui.createSharedNote
import com.indagium.ui.goToLogRefRows
import com.indagium.ui.goToLogRefRowsAfterReopen
import com.indagium.ui.setSharedNoteMembers
import com.indagium.ui.shareTabNote
import com.indagium.ui.stopSharing
import com.indagium.ui.tabShellFromToken
import com.indagium.ui.tabToken
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val STOPPED_TIMEOUT_MS = 30_000L

/**
 * A shared note that stops sharing hands its closed-log reopen information to the recipient's private note, so a citation of a log
 * that was already closed (or closes later) can still be reopened, and rebinds when the log opens again.
 */
class StoppedSharedNoteClosedSourcesTest {
    private val profile = LogFormatProfile(
        id = "stopped-note-profile",
        name = "Stopped note profile",
        engine = LogFormatEngine.REGEX,
        regexPattern = "^(\\w+): (.*)$",
        fieldMappings = mapOf(LogFieldRole.COMPONENT to "1", LogFieldRole.MESSAGE to "2"),
    )

    private fun newState(dir: File, restore: Boolean = false) =
        AppState(File(dir, "state.cache"), restoreOnCreate = restore, autoExportNotes = false, notesDir = File(dir, "notes"))

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), message)
    }

    private fun <T> go(block: suspend () -> T): T = runBlocking { withTimeout(STOPPED_TIMEOUT_MS) { block() } }

    private class Fixture(val app: AppState, val dir: File, val aId: String, val bId: String, val bFile: File, val hostId: String)

    /** Tab A (an ordinary log), standalone log B (read with [profile]), and a shared note of A that cites B's first row. */
    private fun fixture(dir: File): Fixture {
        val app = newState(dir)
        val aFile = File(dir, "a.log").apply { writeText("06-26 10:00:00.000  123  456 I App: hello from a\n") }
        val bFile = File(dir, "b.log").apply { writeText("Worker: request timed out\nWorker: second line\n") }
        assertTrue(app.saveLogFormatProfile(profile).isSuccess)
        val aId = assertNotNull(app.openFile(aFile))
        waitUntil("A loads") { app.tab(aId)?.contentState == TabContentState.LOADED }
        val bId = assertNotNull(app.openFileWithLogFormatProfile(bFile, profile.id))
        waitUntil("B loads") { app.tab(bId)?.contentState == TabContentState.LOADED }
        val hostId = assertNotNull(app.createSharedNote("Review", listOf(aId)))
        app.activeTabId = aId
        app.compareTabId = bId
        app.compareMode = true
        app.requestAddAnn(bId, listOf(1))
        val request = assertNotNull(app.addAnnRequest)
        assertEquals(hostId, request.targetTabId)
        app.confirmAddAnn(request, "B excerpt", AnnBlock.LogRefPresentationOptions())
        app.compareMode = false
        return Fixture(app, dir, aId, bId, bFile, hostId)
    }

    private fun AppState.citation(tabId: String): AnnBlock.LogRef =
        assertNotNull(tab(tabId)).annotations.blocks.filterIsInstance<AnnBlock.LogRef>().single()

    @Test
    fun aClosedLogCitedByAStoppedSharedNoteStillOffersReopenAfterARestartAndRebindsOnReopen() {
        val dir = createTempDirectory("stopped-note-closed").toFile()
        val f = fixture(dir)
        var restored: AppState? = null
        try {
            val app = f.app
            val originalSnapshot = assertNotNull(app.tab(f.bId)?.logFormatProfileSnapshot)
            app.closeTab(f.bId)
            assertNull(app.tab(f.bId))
            assertTrue(app.stopSharing(f.hostId, f.aId))
            assertNull(app.sharedNoteHost(f.hostId), "the shared note is gone, and with it the record that used to reopen B")

            val carried = app.tab(f.aId)!!.noteClosedSources
            assertEquals(listOf(f.bId), carried.map { it.tabId })
            assertEquals(f.bFile.absolutePath, carried.single().sourcePath)
            assertEquals(originalSnapshot, carried.single().logFormatProfileSnapshot)
            assertEquals(GoToRowsResult.NeedsReopen(f.aId, f.bId), go { app.goToLogRefRows(app.citation(f.aId), f.aId) })

            // A restart over the same autosave keeps the offer.
            app.autosaveNow()
            app.close()
            val again = newState(dir, restore = true).also { restored = it }
            assertEquals(carried, again.tab(f.aId)!!.noteClosedSources)
            val citation = again.citation(f.aId)
            assertEquals(GoToRowsResult.NeedsReopen(f.aId, f.bId), go { again.goToLogRefRows(citation, f.aId) })

            // Reopen: B comes back with its saved profile, the citation follows it, and the cited row is selected.
            val navigated = assertIs<GoToRowsResult.Navigated>(go { again.goToLogRefRowsAfterReopen(f.aId, f.bId, citation, f.aId) })
            val reopened = assertNotNull(again.tab(navigated.tabId))
            assertEquals(originalSnapshot, reopened.logFormatProfileSnapshot)
            assertEquals(listOf(1), navigated.rowIds)
            assertEquals(setOf(1), reopened.selected)
            assertEquals(reopened.id, again.citation(f.aId).sourceTabId)
            assertTrue(again.tab(f.aId)!!.noteClosedSources.isEmpty(), "the entry is dropped once the citation is rebound")
        } finally {
            restored?.close()
            runCatching { f.app.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun sharingThePrivateNoteAgainKeepsItsClosedSourcesWithTheNote() {
        val dir = createTempDirectory("stopped-note-reshare").toFile()
        val f = fixture(dir)
        try {
            val app = f.app
            app.closeTab(f.bId)
            assertTrue(app.stopSharing(f.hostId, f.aId))
            assertEquals(listOf(f.bId), app.tab(f.aId)!!.noteClosedSources.map { it.tabId })

            // Sharing A's note again moves the reopen record into the new shared note, where its citation can still use it.
            val again = assertNotNull(app.shareTabNote(f.aId, "Review again", listOf(f.aId)))
            assertTrue(app.tab(f.aId)!!.noteClosedSources.isEmpty())
            assertEquals(listOf(f.bId), app.sharedNoteHost(again)!!.sharedNote!!.closedReferenceSources.map { it.tabId })
            val citation = app.sharedNoteHost(again)!!.annotations.blocks.filterIsInstance<AnnBlock.LogRef>().single()
            assertEquals(GoToRowsResult.NeedsReopen(again, f.bId), go { app.goToLogRefRows(citation, f.aId) })
        } finally {
            runCatching { f.app.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun openingTheClosedLogByHandRebindsTheCitationToo() {
        val dir = createTempDirectory("stopped-note-manual").toFile()
        val f = fixture(dir)
        try {
            val app = f.app
            app.closeTab(f.bId)
            assertTrue(app.stopSharing(f.hostId, f.aId))
            assertEquals(f.bId, app.citation(f.aId).sourceTabId)

            val manual = assertNotNull(app.openFileWithLogFormatProfile(f.bFile, profile.id))
            waitUntil("B loads again") { app.tab(manual)?.contentState == TabContentState.LOADED }

            assertEquals(manual, app.citation(f.aId).sourceTabId)
            assertTrue(app.tab(f.aId)!!.noteClosedSources.isEmpty())
            val navigated = assertIs<GoToRowsResult.Navigated>(go { app.goToLogRefRows(app.citation(f.aId), f.aId) })
            assertEquals(manual, navigated.tabId)
            assertEquals(setOf(1), app.tab(manual)!!.selected)
        } finally {
            f.app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aLogClosedAfterTheNoteStoppedBeingSharedIsRecordedToo() {
        val dir = createTempDirectory("stopped-note-later").toFile()
        val f = fixture(dir)
        try {
            val app = f.app
            assertTrue(app.stopSharing(f.hostId, f.aId))
            assertTrue(app.tab(f.aId)!!.noteClosedSources.isEmpty(), "B is open, nothing to remember yet")

            app.closeTab(f.bId)

            assertEquals(listOf(f.bId), app.tab(f.aId)!!.noteClosedSources.map { it.tabId })
            assertEquals(GoToRowsResult.NeedsReopen(f.aId, f.bId), go { app.goToLogRefRows(app.citation(f.aId), f.aId) })
        } finally {
            f.app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun closedLogsThatTheTransferredNoteDoesNotCiteAreLeftBehind() {
        val dir = createTempDirectory("stopped-note-uncited").toFile()
        val f = fixture(dir)
        try {
            val app = f.app
            // A second closed member that no reference of the note cites.
            val cFile = File(dir, "c.log").apply { writeText("06-26 10:00:00.000  123  456 I App: c\n") }
            val cId = assertNotNull(app.openFile(cFile))
            waitUntil("C loads") { app.tab(cId)?.contentState == TabContentState.LOADED }
            app.setSharedNoteMembers(f.hostId, listOf(f.aId, cId))
            app.closeTab(cId)
            app.closeTab(f.bId)

            assertTrue(app.stopSharing(f.hostId, f.aId))

            assertEquals(listOf(f.bId), app.tab(f.aId)!!.noteClosedSources.map { it.tabId })
        } finally {
            f.app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun theClosedSourcesRoundTripThroughTheTabTokenAndOlderTokensDecodeWithNone() {
        val source = SharedNoteClosedSource("old-b", "b.log", "/tmp/b.log", """{"id":"p"}""")
        val tab = mkTabForToken().copy(noteClosedSources = listOf(source, SharedNoteClosedSource("old-c", "c.log", null)))

        val token = tab.tabToken()
        assertEquals(tab.noteClosedSources, token.tabShellFromToken()?.tab?.noteClosedSources)

        val withoutField = token.substringBeforeLast('|')
        assertTrue(withoutField.tabShellFromToken()?.tab?.noteClosedSources.orEmpty().isEmpty(), "a token from before the field has none")
        assertTrue(mkTabForToken().tabToken().tabShellFromToken()?.tab?.noteClosedSources.orEmpty().isEmpty())
    }

    private fun mkTabForToken() = com.indagium.ui.mkTab("tok", "t.log", emptyList()).copy(sourcePath = File("t.log").absolutePath)
}
