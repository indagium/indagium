package com.indagium.ui

import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Lifeline
import org.junit.After
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Renaming a saved diagram from the library: the title lives in the diagram itself, so a closed item's snapshot is re-encoded and an
 *  open workspace takes the ordinary SetTitle command. Nothing else about the record changes. */
class Seq3RenameLibraryItemTest {
    private val root = createTempDirectory("seq3-rename").toFile()
    private val libraryFile = File(root, "library.cache")
    private val store = DiagramLibraryStore(libraryFile)
    private val state = AppState(
        File(root, "state.cache"),
        notesDir = File(root, "notes"),
        diagramLibraryStore = store,
        restoreOnCreate = false,
    )

    @After
    fun tearDown() {
        state.close()
        root.deleteRecursively()
    }

    private fun newClosedDiagram(): String {
        val sessionId = assertNotNull(state.seq3Sessions.beginStandalone())
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddLifeline(Seq3Lifeline("a", "Client", emptySet(), 0)))
        val itemId = assertNotNull(state.seq3Sessions.sessions.single().libraryItemId)
        state.seq3Sessions.close(sessionId)
        return itemId
    }

    @Test
    fun renamingAClosedDiagramChangesTitleSnapshotAndUpdateTimeOnly() {
        val itemId = newClosedDiagram()
        val before = assertNotNull(store.get(itemId))
        assertTrue(before.parsed?.document?.lifelines?.map { it.name } == listOf("Client"))

        assertTrue(state.seq3Sessions.renameLibraryItem(itemId, "  Login flow  "))

        val after = assertNotNull(DiagramLibraryStore(libraryFile).get(itemId), "persisted to disk")
        assertEquals("Login flow", after.title)
        assertEquals("Login flow", after.parsed?.document?.title, "the diagram carries the title, not only the record")
        assertEquals(listOf("Client"), after.parsed?.document?.lifelines?.map { it.name }, "the rest of the diagram is untouched")
        assertEquals(before.source, after.source)
        assertEquals(before.attachments, after.attachments)
        assertEquals(before.createdAt, after.createdAt)
        assertTrue(after.updatedAt >= before.updatedAt)
        assertEquals(listOf(itemId), state.seq3Sessions.listLibrary().map { it.id })
        assertEquals("Login flow", state.seq3Sessions.listLibrary().single().title)
    }

    @Test
    fun aRenamedDiagramReopensWithTheNewTitle() {
        val itemId = newClosedDiagram()
        assertTrue(state.seq3Sessions.renameLibraryItem(itemId, "Checkout"))

        val sessionId = assertNotNull(state.seq3Sessions.openLibraryItemWithoutLog(itemId))

        assertEquals("Checkout", state.seq3Sessions.sessions.single { it.id == sessionId }.document.title)
    }

    @Test
    fun renamingAnOpenDiagramGoesThroughTheWorkspaceAndSavesAtOnce() {
        val sessionId = assertNotNull(state.seq3Sessions.beginStandalone())
        val itemId = assertNotNull(state.seq3Sessions.sessions.single().libraryItemId)

        assertTrue(state.seq3Sessions.renameLibraryItem(itemId, "Open and renamed"))

        assertEquals("Open and renamed", state.seq3Sessions.sessions.single { it.id == sessionId }.document.title)
        assertEquals("Open and renamed", DiagramLibraryStore(libraryFile).get(itemId)?.title, "saved without waiting for the debounce")
        // It is one undo step like any other edit of the open diagram.
        assertTrue(state.seq3Sessions.sessions.single().undoStack.isNotEmpty())
    }

    @Test
    fun renamingToTheCurrentTitleIsANoOpThatSucceeds() {
        val itemId = newClosedDiagram()
        val title = assertNotNull(store.get(itemId)).title
        val updatedAt = assertNotNull(store.get(itemId)).updatedAt

        assertTrue(state.seq3Sessions.renameLibraryItem(itemId, title))

        assertEquals(updatedAt, store.get(itemId)?.updatedAt)
    }

    @Test
    fun aBlankTooLongOrUnknownRenameIsRefusedAndChangesNothing() {
        val itemId = newClosedDiagram()
        val before = assertNotNull(store.get(itemId))

        assertFalse(state.seq3Sessions.renameLibraryItem(itemId, "   "))
        assertFalse(state.seq3Sessions.renameLibraryItem(itemId, "x".repeat(MAX_DIAGRAM_LIBRARY_TITLE_CHARS + 1)))
        assertFalse(state.seq3Sessions.renameLibraryItem("no-such-id", "Name"))

        assertEquals(before, store.get(itemId))
        assertTrue(state.seq3Sessions.renameLibraryItem(itemId, "y".repeat(MAX_DIAGRAM_LIBRARY_TITLE_CHARS)), "the limit itself is allowed")
    }

    @Test
    fun renamingKeepsTheTicketOfATicketDiagram() {
        val tab = mkTab("log", "app.log", emptyList()).copy(sourcePath = File(root, "app.log").absolutePath)
        state.tabs = listOf(tab)
        state.activateTab(tab.id)
        val sessionId = assertNotNull(state.seq3Sessions.beginEmpty(tab.id))
        val itemId = assertNotNull(state.seq3Sessions.sessions.single().libraryItemId)
        state.seq3Sessions.close(sessionId)
        val source = assertNotNull(store.get(itemId)).source
        assertFalse(source.isNone)

        assertTrue(state.seq3Sessions.renameLibraryItem(itemId, "Renamed ticket diagram"))

        assertEquals(source, store.get(itemId)?.source)
        assertTrue(state.seq3Sessions.listLibrary().single().hasTicket)
    }
}
