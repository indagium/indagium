package com.indagium.ui

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How the New tab's Diagrams section opens a diagram, against a real [AppState]: log-free rows, ticket rows with the log open, on
 *  disk (a file and an archive entry) and gone, and the notices of the fallbacks. */
class HomeDiagramOpenerTest {
    private val root = createTempDirectory("home-diagram-opener").toFile()
    private val libraryFile = File(root, "library.cache")
    private val states = mutableListOf<AppState>()

    @After
    fun tearDown() {
        states.forEach { runCatching { it.close() } }
        root.deleteRecursively()
    }

    private fun newState(): AppState = AppState(
        File(root, "state-${states.size}.cache"),
        notesDir = File(root, "notes"),
        diagramLibraryStore = DiagramLibraryStore(libraryFile),
        restoreOnCreate = false,
        archiveCacheDir = File(root, "archive-cache"),
    ).also { states += it }

    private fun logFile(name: String = "app.log", lines: Int = 3): File = File(root, name).apply {
        writeText((1..lines).joinToString("") { "06-26 10:00:0$it.000  123  456 I App: step $it\n" })
    }

    private fun await(timeoutMs: Long = 15_000, what: String = "condition", condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "$what did not become true within ${timeoutMs}ms")
    }

    private fun AppState.openAndAwait(file: File): String {
        val tabId = assertNotNull(openPath(file))
        await(what = "the log tab") { tab(tabId) != null && !isLoadInFlight(tabId) }
        return tabId
    }

    /** Draws an empty diagram against [tabId] (so it gets that log as its ticket), then closes the diagram tab and the log tab. */
    private fun AppState.ticketDiagramThenCloseLog(tabId: String): DiagramLibrarySummary {
        val sessionId = assertNotNull(seq3Sessions.beginEmpty(tabId))
        val itemId = assertNotNull(sessionOf(sessionId)?.libraryItemId)
        seq3Sessions.close(sessionId)
        closeTab(tabId)
        return seq3Sessions.listLibrary().single { it.id == itemId }.also { assertTrue(it.hasTicket) }
    }

    private fun AppState.opener() = homeDiagramOpener

    private fun AppState.sessionOf(id: String) = seq3Sessions.sessions.firstOrNull { it.id == id }

    private fun AppState.openTicket(summary: DiagramLibrarySummary, launcherTabId: String? = null) =
        runBlocking { withTimeout(30_000) { opener().openTicket(summary, launcherTabId) } }

    @Test
    fun aDiagramWithoutATicketOpensInItsOwnTabAndConsumesTheNewTab() {
        val state = newState()
        val id = assertNotNull(state.seq3Sessions.beginStandalone())
        state.seq3Sessions.close(id)
        state.openHomeTab()
        val launcher = state.tabs.single { it.isCaptureLauncher }.id
        val summary = state.seq3Sessions.listLibrary(DiagramTicketFilter.NO_TICKET).single()

        state.opener().open(summary, launcher)

        val session = state.seq3Sessions.sessions.single()
        assertEquals(summary.id, session.libraryItemId)
        assertNull(session.sourceTabId)
        assertEquals(ActiveSurface.Diagram3(session.id), state.activeSurface)
        assertTrue(state.tabs.none { it.id == launcher }, "the New tab it was opened from is gone")
        assertNull(state.pendingDiagramNotice)
    }

    @Test
    fun aTicketDiagramOpensAgainstTheOpenTabOfItsLog() {
        val state = newState()
        val file = logFile()
        val tabId = state.openAndAwait(file)
        val sessionId = assertNotNull(state.seq3Sessions.beginEmpty(tabId))
        val itemId = assertNotNull(state.sessionOf(sessionId)?.libraryItemId)
        state.seq3Sessions.close(sessionId)
        state.openHomeTab()
        val launcher = state.tabs.single { it.isCaptureLauncher }.id
        val summary = state.seq3Sessions.listLibrary().single()

        state.openTicket(summary, launcher)

        val session = state.seq3Sessions.sessions.single()
        assertEquals(itemId, session.libraryItemId)
        assertEquals(tabId, session.sourceTabId, "opened against the log that is already open")
        assertTrue(session.hasTicket)
        assertEquals(ActiveSurface.Diagram3(session.id), state.activeSurface)
        assertEquals(1, state.tabs.count { it.sourcePath == file.absolutePath }, "the log was not opened a second time")
        assertTrue(state.tabs.none { it.id == launcher })
        assertNull(state.pendingDiagramNotice)
    }

    @Test
    fun aTicketDiagramWhoseLogIsClosedOpensTheFileFromDiskAndThenTheDiagram() {
        val state = newState()
        val file = logFile()
        val summary = state.ticketDiagramThenCloseLog(state.openAndAwait(file))
        assertTrue(state.tabs.isEmpty())

        state.openTicket(summary)

        val tab = state.tabs.single()
        assertEquals(file.absolutePath, tab.sourcePath)
        val session = state.seq3Sessions.sessions.single()
        assertEquals(summary.id, session.libraryItemId)
        assertEquals(tab.id, session.sourceTabId)
        assertEquals(summary.source, state.seq3Sessions.libraryItem(summary.id)?.source, "its ticket is unchanged")
        assertNull(state.pendingDiagramNotice)
    }

    @Test
    fun aTicketDiagramWhoseLogIsAnArchiveEntryOpensThatEntry() {
        val state = newState()
        val zip = File(root, "BUG-7.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("FS/logs/logcat.log"))
            out.write(logFile("inner.log", lines = 4).readBytes())
            out.closeEntry()
        }
        // A single-log archive opens straight away, without the picker.
        state.openPath(zip)
        await(what = "the archive entry's tab") { state.tabs.any { it.sourcePath?.startsWith(zip.absolutePath + "!") == true } && !state.isLoading }
        val tabId = state.tabs.single().id
        assertEquals("${zip.absolutePath}!FS/logs/logcat.log", state.tab(tabId)?.sourcePath)
        val summary = state.ticketDiagramThenCloseLog(tabId)
        assertEquals("BUG-7.zip › logcat.log", summary.ticketLabel)

        state.openTicket(summary)

        val tab = state.tabs.single()
        assertEquals("${zip.absolutePath}!FS/logs/logcat.log", tab.sourcePath)
        assertEquals(tab.id, state.seq3Sessions.sessions.single().sourceTabId)
        assertNull(state.pendingDiagramNotice)
    }

    @Test
    fun aTicketDiagramWhoseLogIsGoneOpensWithoutItAndSaysSo() {
        val state = newState()
        val file = logFile()
        val summary = state.ticketDiagramThenCloseLog(state.openAndAwait(file))
        assertTrue(file.delete())
        state.openHomeTab()
        val launcher = state.tabs.single { it.isCaptureLauncher }.id

        state.openTicket(summary, launcher)

        val session = state.seq3Sessions.sessions.single()
        assertEquals(summary.id, session.libraryItemId)
        assertNull(session.sourceTabId)
        assertEquals(ActiveSurface.Diagram3(session.id), state.activeSurface)
        val notice = assertNotNull(state.pendingDiagramNotice)
        assertEquals("Source log not found", notice.title)
        assertTrue("app.log" in notice.body, notice.body)
        assertEquals(summary.source, state.seq3Sessions.libraryItem(summary.id)?.source, "the saved diagram keeps its ticket")
        assertTrue(state.tabs.none { it.id == launcher }, "the diagram took over the screen, so the New tab closed")
    }

    @Test
    fun aLogThatChangedSinceTheDiagramWasSavedIsNotTreatedAsItsLog() {
        val state = newState()
        val file = logFile(lines = 3)
        val summary = state.ticketDiagramThenCloseLog(state.openAndAwait(file))
        logFile(lines = 5)

        state.openTicket(summary)

        val session = state.seq3Sessions.sessions.single()
        assertNull(session.sourceTabId, "a different revision of the log is not linked to the diagram")
        assertEquals("Source log has changed", assertNotNull(state.pendingDiagramNotice).title)
        assertEquals(summary.source, state.seq3Sessions.libraryItem(summary.id)?.source)
    }

    @Test
    fun aDiagramAlreadyOpenIsBroughtToTheFrontNotOpenedTwice() {
        val state = newState()
        val summary = state.ticketDiagramThenCloseLog(state.openAndAwait(logFile()))
        val first = assertNotNull(state.seq3Sessions.openLibraryItemWithoutLog(summary.id))
        val other = assertNotNull(state.seq3Sessions.beginStandalone())
        assertEquals(ActiveSurface.Diagram3(other), state.activeSurface)

        state.openTicket(summary)

        assertEquals(2, state.seq3Sessions.sessions.size)
        assertEquals(ActiveSurface.Diagram3(first), state.activeSurface)
    }

    @Test
    fun anOfflineSessionOfATicketDiagramRelinksWhenItsLogIsOpenAgain() {
        val state = newState()
        val file = logFile()
        val summary = state.ticketDiagramThenCloseLog(state.openAndAwait(file))
        val offline = assertNotNull(state.seq3Sessions.openLibraryItemWithoutLog(summary.id))
        assertNull(state.sessionOf(offline)?.sourceTabId)
        val reopened = state.openAndAwait(file)

        state.openTicket(summary)

        val session = state.seq3Sessions.sessions.single()
        assertEquals(offline, session.id)
        assertEquals(reopened, session.sourceTabId, "the session is linked to the log again")
        assertEquals(ActiveSurface.Diagram3(offline), state.activeSurface)
    }

    @Test
    fun anUnreadableLibraryItemShowsANoticeInsteadOfOpeningNothing() {
        val state = newState()
        val gone = DiagramLibrarySummary(
            id = "missing", title = "Gone", description = "", source = DiagramSourceIdentity.NONE, status = DiagramLibraryStatus.DRAFT,
            updatedAt = 0L, lastOpenedAt = 0L, attachmentCount = 0, participantLabels = emptyList(), rangeSummary = "",
        )

        state.opener().open(gone, launcherTabId = null)

        assertTrue(state.seq3Sessions.sessions.isEmpty())
        assertEquals("Couldn't open diagram", assertNotNull(state.pendingDiagramNotice).title)
    }
}
