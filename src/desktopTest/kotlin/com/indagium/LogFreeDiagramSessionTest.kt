package com.indagium

import com.indagium.debug.IndagiumToolOperations
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3SourceImportResult
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.ActiveSurface
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.DiagramSourceIdentity
import com.indagium.ui.DiagramTicketFilter
import com.indagium.ui.TabRef
import com.indagium.ui.mkTab
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Diagrams that have no log (no "ticket"): created standalone, edited, saved, reopened, restored, and
 *  kept apart from the log tabs that come and go around them. */
class LogFreeDiagramSessionTest {
    private val root = createTempDirectory("indagium-log-free").toFile()
    private val libraryFile = File(root, "library.cache")

    private fun entries(): List<LogEntry> = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "Producer", "start op", 7, 11),
        LogEntry(2, "10:00:00.050", LogLevel.I, "Consumer", "handle op", 7, 11),
    )

    private fun newState(cache: String = "state.cache", restoreOnCreate: Boolean = false): AppState =
        AppState(
            File(root, cache),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(libraryFile),
            restoreOnCreate = restoreOnCreate,
        )

    private fun AppState.withLog(): AppState = also {
        val logFile = File(root, "app.log").apply { writeText("fixture") }
        it.tabs = listOf(mkTab("log", "app.log", entries()).copy(sourcePath = logFile.absolutePath))
        it.activateTab("log")
    }

    private fun await(timeoutMs: Long = 4_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition did not become true within ${timeoutMs}ms")
    }

    @Test
    fun beginStandaloneOpensABlankLogFreeDiagramTabAndSavesItsFirstSnapshot() {
        val state = newState()
        val id = assertNotNull(state.seq3Sessions.beginStandalone())
        val session = state.seq3Sessions.sessions.single()

        assertEquals(id, session.id)
        assertNull(session.sourceTabId)
        assertFalse(session.hasTicket)
        assertTrue(session.logFree)
        assertEquals("This diagram has no log", session.logUnavailableReason())
        assertEquals("Untitled diagram", session.document.title)
        assertTrue(session.document.lifelines.isEmpty())
        assertEquals(ActiveSurface.Diagram3(id), state.activeSurface)
        assertEquals(0L, state.seq3Sessions.generateRunCount.get())
        assertTrue(state.tabGroups.none { TabRef.Diagram(id) in it.members }, "no origin log, so no tab group")

        val item = assertNotNull(DiagramLibraryStore(libraryFile).get(assertNotNull(session.libraryItemId)))
        assertEquals(DiagramSourceIdentity.NONE, item.source)
        assertEquals(session.document, item.parsed?.document, "the blank draft is on disk before any edit")
        assertEquals(Seq3Dialect.PLANTUML, item.parsed?.dialect)
        assertEquals(1, state.seq3Sessions.listLibrary(DiagramTicketFilter.NO_TICKET).size)
        assertTrue(state.seq3Sessions.listLibrary(DiagramTicketFilter.TICKET).isEmpty())
    }

    @Test
    fun defaultTitlesAreNumberedAmongTicketlessDiagramsOnly() {
        val state = newState().withLog()
        state.seq3Sessions.beginEmpty("log")!! // a ticket diagram titled "Untitled diagram"

        val first = state.seq3Sessions.beginStandalone()!!
        val second = state.seq3Sessions.beginStandalone()!!

        val titles = state.seq3Sessions.sessions.associate { it.id to it.document.title }
        assertEquals("Untitled diagram", titles[first])
        assertEquals("Untitled diagram 2", titles[second])
    }

    @Test
    fun canvasAndSourceEditsSaveAndTheDiagramReopensAfterClosingItsTab() {
        val state = newState()
        val seq3 = state.seq3Sessions
        val id = seq3.beginStandalone()!!
        val itemId = seq3.sessions.single().libraryItemId!!

        assertTrue(seq3.prepareSourceDraft(id))
        val edited = "@startuml\nparticipant Client\nparticipant Server\nClient -> Server : ping\n@enduml\n"
        val imported = seq3.importSource(id, edited, Seq3Dialect.PLANTUML)
        assertIs<Seq3SourceImportResult.Success>(imported, imported.toString())
        assertTrue(seq3.applyCommand(id, Seq3Command.AddLifeline(Seq3Lifeline("gw", "Gateway", emptySet(), 5))))
        seq3.updateTitle(id, "Handshake")
        assertTrue(seq3.canUndo(id))
        seq3.flush(id)
        val documentBeforeClose = seq3.sessions.single().document
        assertEquals(listOf("Client", "Server", "Gateway"), documentBeforeClose.lifelines.map { it.name })
        assertEquals("ping", documentBeforeClose.messages.single().labelTemplate)

        seq3.close(id)

        assertTrue(seq3.sessions.isEmpty())
        val saved = assertNotNull(DiagramLibraryStore(libraryFile).get(itemId))
        assertEquals(documentBeforeClose, saved.parsed?.document, "closing the tab keeps the library draft")
        assertTrue(saved.source.isNone)

        val reopened = assertNotNull(seq3.openLibraryItemWithoutLog(itemId))
        val session = seq3.sessions.single { it.id == reopened }
        assertEquals(documentBeforeClose, session.document)
        assertNull(session.sourceTabId)
        assertFalse(session.hasTicket)
        assertEquals(ActiveSurface.Diagram3(reopened), state.activeSurface)
        assertEquals(reopened, seq3.openLibraryItemWithoutLog(itemId), "an open diagram is activated, not duplicated")
        assertEquals(1, seq3.sessions.size)
        assertNull(seq3.openLibraryItemWithoutLog("missing"))
    }

    @Test
    fun actionsThatNeedALogDoNothingAndNeverThrow() {
        val state = newState()
        val seq3 = state.seq3Sessions
        val id = seq3.beginStandalone()!!
        seq3.applyCommand(id, Seq3Command.AddLifeline(Seq3Lifeline("a", "Client", emptySet(), 0)))

        seq3.requestGenerate(id)
        seq3.updateRangeAndRegenerate(id, com.indagium.diagram3.Seq3Range.VisibleView)
        seq3.requestRegenReview(id, com.indagium.diagram3.Seq3Range.VisibleView, seq3.sessions.single().generateOptions)
        seq3.revertMessage(id, "none")
        Thread.sleep(400)

        assertEquals(0L, seq3.generateRunCount.get(), "nothing scans a log")
        assertFalse(seq3.sessions.single().generating)
        assertFalse(seq3.sessions.single().regenBuilding)
        assertNull(seq3.confirm(id), "no log, no note to write")
        assertNull(seq3.attachSnapshot(id))
        assertNull(seq3.attachLiveLink(id))
        assertEquals(0, seq3.scannedEntryCount(id))
        assertNull(seq3.scannedTimeRange(id))
        assertNull(seq3.scannedIdRange(id))
    }

    @Test
    fun closingAnUnrelatedLogTabLeavesTheLogFreeDiagramAlone() {
        val state = newState().withLog()
        val ticketId = state.seq3Sessions.begin("log", setOf(1, 2))!!
        await { state.seq3Sessions.sessions.first { it.id == ticketId }.generating == false }
        val freeId = state.seq3Sessions.beginStandalone()!!
        state.seq3Sessions.applyCommand(freeId, Seq3Command.AddLifeline(Seq3Lifeline("a", "Client", emptySet(), 0)))
        val before = state.seq3Sessions.sessions.single { it.id == freeId }

        state.closeTab("log")

        val after = state.seq3Sessions.sessions.single { it.id == freeId }
        assertEquals(before.document, after.document)
        assertEquals(before.libraryItemId, after.libraryItemId)
        assertNull(after.sourceTabId)
        assertFalse(after.hasTicket, "closing a log never turns a log-free diagram into a ticket diagram")
        assertNull(state.seq3Sessions.sessions.single { it.id == ticketId }.sourceTabId, "the ticket diagram did go offline")
        assertTrue(state.seq3Sessions.sessions.single { it.id == ticketId }.hasTicket)
        assertEquals("The source log is not open", state.seq3Sessions.sessions.single { it.id == ticketId }.logUnavailableReason())
    }

    @Test
    fun aTicketDiagramWhoseLogIsGoneOpensItsSnapshotWithoutALogAndKeepsItsTicket() {
        val state = newState().withLog()
        val seq3 = state.seq3Sessions
        val sessionId = seq3.begin("log", setOf(1, 2))!!
        await { seq3.sessions.first { it.id == sessionId }.generating == false }
        val itemId = seq3.sessions.single().libraryItemId!!
        val ticketSource = DiagramLibraryStore(libraryFile).get(itemId)!!.source
        assertFalse(ticketSource.isNone)
        seq3.close(sessionId)
        state.closeTab("log")

        val reopened = assertNotNull(seq3.openLibraryItemWithoutLog(itemId))
        val session = seq3.sessions.single { it.id == reopened }
        assertNull(session.sourceTabId)
        assertTrue(session.hasTicket, "the item still has its ticket")
        assertFalse(session.logFree)
        assertTrue(session.document.lifelines.isNotEmpty(), "the self-contained snapshot is shown")

        seq3.updateTitle(reopened, "Renamed offline")
        seq3.flush(reopened)

        val saved = DiagramLibraryStore(libraryFile).get(itemId)!!
        assertEquals("Renamed offline", saved.title)
        assertEquals(ticketSource, saved.source, "an offline edit keeps the ticket")
    }

    @Test
    fun aLogCanStillBeLinkedLater() {
        val state = newState().withLog()
        val id = state.seq3Sessions.beginStandalone()!!

        assertTrue(state.seq3Sessions.relink(id, "log"))
        val session = state.seq3Sessions.sessions.single()
        assertEquals("log", session.sourceTabId)
        assertTrue(session.hasTicket)
        assertNull(session.logUnavailableReason())
        state.seq3Sessions.updateTitle(id, "Linked now")
        state.seq3Sessions.flush(id)
        assertFalse(DiagramLibraryStore(libraryFile).get(session.libraryItemId!!)!!.source.isNone)
    }

    @Test
    fun aLogFreeDiagramTabComesBackAfterARestartWithNoLogTabsOpen() {
        val state = newState()
        val id = state.seq3Sessions.beginStandalone()!!
        state.seq3Sessions.applyCommand(id, Seq3Command.AddLifeline(Seq3Lifeline("a", "Client", emptySet(), 0)))
        state.seq3Sessions.flush(id)
        val itemId = state.seq3Sessions.sessions.single().libraryItemId!!
        state.autosaveNow()

        val restored = newState(restoreOnCreate = true)

        val session = restored.seq3Sessions.sessions.single()
        assertEquals(itemId, session.libraryItemId)
        assertNull(session.sourceTabId)
        assertFalse(session.hasTicket)
        assertEquals(listOf("Client"), session.document.lifelines.map { it.name })
        assertEquals(ActiveSurface.Diagram3(session.id), restored.activeSurface)
        assertTrue(restored.tabs.isEmpty())
    }

    @Test
    fun aLogFreeAndATicketDiagramBothRestoreAlongsideALogTab() {
        val state = newState().withLog()
        val ticketId = state.seq3Sessions.begin("log", setOf(1, 2))!!
        await { state.seq3Sessions.sessions.first { it.id == ticketId }.generating == false }
        val freeId = state.seq3Sessions.beginStandalone()!!
        state.seq3Sessions.flush(freeId)
        state.autosaveNow()

        val restored = newState(restoreOnCreate = true)

        assertEquals(2, restored.seq3Sessions.sessions.size)
        assertEquals(
            setOf<String?>("log", null),
            restored.seq3Sessions.sessions.map { it.sourceTabId }.toSet(),
        )
        assertEquals(1, restored.seq3Sessions.sessions.count { !it.hasTicket })
    }

    @Test
    fun mcpToolsThatTakeALogBehaveWhileOnlyALogFreeDiagramIsOpen() {
        val state = newState()
        state.seq3Sessions.beginStandalone()!!
        val operations = IndagiumToolOperations(state)

        val result = operations.toolGateway.execute("build_sequence_diagram", mapOf("tabId" to "none")) as Map<*, *>
        assertNotNull(result["error"], "a log-less call fails with a clear error instead of crashing")

        assertNotNull(operations.toolGateway.execute("list_tabs", emptyMap()))
        assertEquals(1, state.seq3Sessions.sessions.size, "the diagram session is untouched")
    }
}
