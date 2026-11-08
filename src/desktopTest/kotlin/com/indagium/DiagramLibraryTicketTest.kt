package com.indagium

import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Range
import com.indagium.ui.DiagramLibrarySnapshot
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.DiagramSourceIdentity
import com.indagium.ui.DiagramTicketFilter
import com.indagium.ui.diagramTicketLabel
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The "ticket" (source log) dimension of the diagram library: a diagram without a log is stored with
 *  [DiagramSourceIdentity.NONE], and listings split into no-ticket / ticket / all. */
class DiagramLibraryTicketTest {
    private val ticket = DiagramSourceIdentity("/captures/bugreport.log", "4:deadbeef")

    private fun snapshot(title: String) = DiagramLibrarySnapshot.create(
        Seq3Document(title = title, range = Seq3Range.VisibleView),
    )

    private fun newFile() = File(createTempDirectory("diagram-ticket").toFile(), "diagram-library-v1")

    @Test
    fun noneIdentityIsEmptyAndRoundTripsThroughTheFileFormat() {
        val file = newFile()
        val store = DiagramLibraryStore(file)
        val saved = store.create("Standalone", "", DiagramSourceIdentity.NONE, snapshot("Standalone"), now = 100L)

        val restored = assertNotNull(DiagramLibraryStore(file).get(saved.id))

        assertEquals(DiagramSourceIdentity.NONE, restored.source)
        assertTrue(restored.source.isNone)
        assertEquals("Standalone", restored.title)
        // No new field or version: the empty values are the existing "~" token.
        val line = file.readLines().first { it.startsWith("item\t") }.split('\t')
        assertEquals("~", line[4])
        assertEquals("~", line[5])
        assertEquals("diagram-library-v1", file.readLines().first())
        assertEquals("version\t1", file.readLines()[1])
    }

    @Test
    fun filtersSplitTicketAndTicketlessDiagramsNewestUpdatedFirst() {
        val store = DiagramLibraryStore(newFile())
        val a = store.create("No ticket old", "", DiagramSourceIdentity.NONE, snapshot("No ticket old"), now = 100L)
        val b = store.create("Ticket", "", ticket, snapshot("Ticket"), now = 200L)
        val c = store.create("No ticket new", "", DiagramSourceIdentity.NONE, snapshot("No ticket new"), now = 300L)
        // Opening an item must not reorder the "most recently updated" list.
        store.markOpened(a.id, now = 900L)

        assertEquals(listOf(c.id, a.id), store.list(DiagramTicketFilter.NO_TICKET).map { it.id })
        assertEquals(listOf(b.id), store.list(DiagramTicketFilter.TICKET).map { it.id })
        assertEquals(listOf(c.id, b.id, a.id), store.list(DiagramTicketFilter.ALL).map { it.id })
        assertEquals(listOf(c.id), store.list(DiagramTicketFilter.NO_TICKET, query = "new").map { it.id })
        assertEquals(listOf(b.id), store.search("", tickets = DiagramTicketFilter.TICKET).map { it.id })
        assertEquals(3, store.search("").size, "the default stays \"all\", as before")
        assertEquals(listOf(a.id, c.id).toSet(), store.forSource(DiagramSourceIdentity.NONE).map { it.id }.toSet())
    }

    @Test
    fun summariesCarryHasTicketAndTicketLabel() {
        val store = DiagramLibraryStore(newFile())
        store.create("Free", "", DiagramSourceIdentity.NONE, snapshot("Free"), now = 1L)
        store.create("Plain", "", ticket, snapshot("Plain"), now = 2L)
        store.create(
            "Zip",
            "",
            DiagramSourceIdentity("/home/me/tickets/BUG-42.zip!FS/logs/logcat.log", "9:abc"),
            snapshot("Zip"),
            now = 3L,
        )

        val byTitle = store.list().associateBy { it.title }

        assertFalse(byTitle.getValue("Free").hasTicket)
        assertEquals("", byTitle.getValue("Free").ticketLabel)
        assertTrue(byTitle.getValue("Plain").hasTicket)
        assertEquals("bugreport.log", byTitle.getValue("Plain").ticketLabel)
        assertEquals("BUG-42.zip › logcat.log", byTitle.getValue("Zip").ticketLabel)
        assertEquals("C.log", diagramTicketLabel(DiagramSourceIdentity("C:\\logs\\C.log", "x")))
    }

    @Test
    fun existingItemsWrittenBeforeTicketlessDiagramsStillLoadAsTicketDiagrams() {
        val file = newFile()
        DiagramLibraryStore(file).create("Old", "", ticket, snapshot("Old"), now = 5L)

        val reloaded = DiagramLibraryStore(file)

        val summary = reloaded.list().single()
        assertTrue(summary.hasTicket)
        assertEquals(ticket, summary.source)
        assertTrue(reloaded.list(DiagramTicketFilter.NO_TICKET).isEmpty())
        assertEquals(1, reloaded.list(DiagramTicketFilter.TICKET).size)
    }
}
