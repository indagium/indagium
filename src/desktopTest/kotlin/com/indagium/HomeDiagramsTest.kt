package com.indagium

import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Range
import com.indagium.model.HomeDiagramSegment
import com.indagium.ui.DiagramLibrarySnapshot
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.DiagramLibrarySummary
import com.indagium.ui.DiagramSourceIdentity
import com.indagium.ui.DiagramTicketFilter
import com.indagium.ui.HomeDiagramLogFile
import com.indagium.ui.HomeDiagramLogProblem
import com.indagium.ui.homeDiagramLogProblemNotice
import com.indagium.ui.homeDiagramMatches
import com.indagium.ui.homeDiagramParticipantsLabel
import com.indagium.ui.homeDiagramRows
import com.indagium.ui.homeDiagramSegmentLabel
import com.indagium.ui.homeDiagramsEmpty
import com.indagium.ui.resolveDiagramLogFile
import com.indagium.ui.ticketFilter
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure half of the New tab's Diagrams section: what a segment lists, how a search matches, what a row says. */
class HomeDiagramsTest {
    private val now = 10_000_000_000L
    private val logTicket = DiagramSourceIdentity("/captures/checkout.log", "9:abc")
    private val zipTicket = DiagramSourceIdentity("/home/me/BUG-42.zip!FS/logs/logcat.log", "5:def")

    private fun summaries(): List<DiagramLibrarySummary> {
        val store = DiagramLibraryStore(File(createTempDirectory("home-diagrams").toFile(), "library.cache"))
        fun add(title: String, source: DiagramSourceIdentity, vararg participants: String, at: Long) {
            val document = Seq3Document(
                title = title,
                range = Seq3Range.VisibleView,
                lifelines = participants.mapIndexed { index, name -> Seq3Lifeline("l$index", name, emptySet(), index) },
            )
            store.create(title, "", source, DiagramLibrarySnapshot.create(document), now = at)
        }
        add("Login flow", DiagramSourceIdentity.NONE, "Client", "Auth server", at = now - 1_000)
        add("Payment retry", logTicket, "Checkout", "Gateway", "Bank", "Ledger", "Audit", at = now - 2 * 3_600_000L)
        add("Crash trace", zipTicket, "App", at = now - 3 * 86_400_000L)
        add("Empty sketch", DiagramSourceIdentity.NONE, at = now - 60_000L)
        return store.list(DiagramTicketFilter.ALL)
    }

    @Test
    fun segmentsMapToTheLibraryFiltersAndHaveTheirLabels() {
        assertEquals(DiagramTicketFilter.NO_TICKET, HomeDiagramSegment.NO_TICKET.ticketFilter())
        assertEquals(DiagramTicketFilter.TICKET, HomeDiagramSegment.TICKET.ticketFilter())
        assertEquals(DiagramTicketFilter.ALL, HomeDiagramSegment.ALL.ticketFilter())
        assertEquals(listOf("No ticket", "Ticket", "All"), HomeDiagramSegment.entries.map(::homeDiagramSegmentLabel))
    }

    @Test
    fun eachSegmentListsItsDiagramsNewestFirst() {
        val all = summaries()
        fun titles(segment: HomeDiagramSegment, query: String = "") = homeDiagramRows(all, segment, query, now).map { it.title }

        assertEquals(listOf("Login flow", "Empty sketch"), titles(HomeDiagramSegment.NO_TICKET))
        assertEquals(listOf("Payment retry", "Crash trace"), titles(HomeDiagramSegment.TICKET))
        assertEquals(listOf("Login flow", "Empty sketch", "Payment retry", "Crash trace"), titles(HomeDiagramSegment.ALL))
    }

    @Test
    fun rowsSayTitleParticipantsAndRelativeUpdateTime() {
        val rows = homeDiagramRows(summaries(), HomeDiagramSegment.ALL, "", now).associateBy { it.title }

        val login = rows.getValue("Login flow")
        assertEquals("Client, Auth server", login.participants)
        assertEquals("Updated just now", login.updated)
        assertEquals("Updated 1 min ago", rows.getValue("Empty sketch").updated)
        assertEquals("Updated 2 hr ago", rows.getValue("Payment retry").updated)
        assertEquals("Updated 3 d ago", rows.getValue("Crash trace").updated)
        assertEquals("No participants yet", rows.getValue("Empty sketch").participants)
    }

    @Test
    fun participantsAreShortenedToTheFirstThreeWithACount() {
        assertEquals("A, B, C +2", homeDiagramParticipantsLabel(listOf("A", "B", "C", "D", "E")))
        assertEquals("A, B, C", homeDiagramParticipantsLabel(listOf("A", "B", "C")))
        assertEquals("A", homeDiagramParticipantsLabel(listOf(" A ", " ", "")))
        assertEquals("No participants yet", homeDiagramParticipantsLabel(emptyList()))
    }

    @Test
    fun theTicketLabelAndTheNoTicketBadgeFollowTheSegment() {
        val all = summaries()
        val noTicket = homeDiagramRows(all, HomeDiagramSegment.NO_TICKET, "", now)
        assertTrue(noTicket.all { it.ticketLabel.isEmpty() && !it.noTicketBadge }, "the No ticket list needs neither")

        val ticket = homeDiagramRows(all, HomeDiagramSegment.TICKET, "", now).associateBy { it.title }
        assertEquals("checkout.log", ticket.getValue("Payment retry").ticketLabel)
        assertEquals("BUG-42.zip › logcat.log", ticket.getValue("Crash trace").ticketLabel)
        assertTrue(ticket.values.none { it.noTicketBadge })

        val everything = homeDiagramRows(all, HomeDiagramSegment.ALL, "", now).associateBy { it.title }
        assertEquals("checkout.log", everything.getValue("Payment retry").ticketLabel)
        assertTrue(everything.getValue("Login flow").noTicketBadge, "a log-free row is marked in All")
        assertEquals("", everything.getValue("Login flow").ticketLabel)
        assertFalse(everything.getValue("Payment retry").noTicketBadge)
    }

    @Test
    fun searchMatchesTitleParticipantsAndTicketLabelCaseInsensitively() {
        val all = summaries().associateBy { it.title }
        val payment = all.getValue("Payment retry")
        val login = all.getValue("Login flow")
        val crash = all.getValue("Crash trace")

        assertTrue(homeDiagramMatches(payment, ""))
        assertTrue(homeDiagramMatches(payment, "   "))
        assertTrue(homeDiagramMatches(payment, "PAYMENT"))
        assertTrue(homeDiagramMatches(login, "auth"), "a participant")
        assertTrue(homeDiagramMatches(payment, "checkout.log"), "the ticket label")
        assertTrue(homeDiagramMatches(crash, "bug-42"), "an archive ticket")
        assertTrue(homeDiagramMatches(crash, "logcat"), "the entry of an archive ticket")
        assertFalse(homeDiagramMatches(login, "checkout"), "a log-free diagram has no ticket label to match")
        assertFalse(homeDiagramMatches(payment, "login"))
    }

    @Test
    fun everyWordOfAMultiWordSearchMustMatchSomewhere() {
        val payment = summaries().first { it.title == "Payment retry" }
        assertTrue(homeDiagramMatches(payment, "payment bank"))
        assertTrue(homeDiagramMatches(payment, "  gateway   checkout.log "))
        assertFalse(homeDiagramMatches(payment, "payment auth"))
    }

    @Test
    fun searchNarrowsWithinTheSegmentOnly() {
        val all = summaries()
        assertEquals(listOf("Login flow"), homeDiagramRows(all, HomeDiagramSegment.NO_TICKET, "client", now).map { it.title })
        assertTrue(homeDiagramRows(all, HomeDiagramSegment.TICKET, "client", now).isEmpty(), "Client belongs to a log-free diagram")
        assertEquals(listOf("Crash trace"), homeDiagramRows(all, HomeDiagramSegment.ALL, "app", now).map { it.title })
    }

    @Test
    fun aBlankTitleReadsAsUntitled() {
        val store = DiagramLibraryStore(File(createTempDirectory("home-diagrams-blank").toFile(), "library.cache"))
        store.create("", "", DiagramSourceIdentity.NONE, DiagramLibrarySnapshot.create(Seq3Document()), now = now)
        assertEquals("Untitled diagram", homeDiagramRows(store.list(), HomeDiagramSegment.NO_TICKET, "", now).single().title)
    }

    @Test
    fun emptyStatesNameTheSegmentOrTheSearch() {
        assertEquals("No diagrams without a ticket yet", homeDiagramsEmpty(HomeDiagramSegment.NO_TICKET, "").message)
        assertTrue(homeDiagramsEmpty(HomeDiagramSegment.TICKET, "").message.startsWith("No diagrams with a ticket yet"))
        assertEquals("No diagrams yet", homeDiagramsEmpty(HomeDiagramSegment.ALL, "").message)
        assertTrue(homeDiagramsEmpty(HomeDiagramSegment.ALL, "").offersNew)

        val searched = homeDiagramsEmpty(HomeDiagramSegment.TICKET, "  zzz ")
        assertEquals("Nothing matches \"zzz\"", searched.message)
        assertFalse(searched.offersNew, "a search that finds nothing is not a reason to create one")
    }

    @Test
    fun aSourcePathReadsAsAPlainLogOrAnArchiveEntry() {
        val dir = createTempDirectory("home-diagrams-paths").toFile()
        val log = File(dir, "app.log").apply { writeText("x") }
        val zip = File(dir, "BUG-42.zip").apply { writeText("x") }
        val bangLog = File(dir, "Hello!.log").apply { writeText("x") }

        assertEquals(HomeDiagramLogFile.Plain(log), resolveDiagramLogFile(log.absolutePath))
        assertEquals(
            HomeDiagramLogFile.ArchiveEntry(zip, "FS/logs/logcat.log"),
            resolveDiagramLogFile(zip.absolutePath + "!FS/logs/logcat.log"),
        )
        assertEquals(HomeDiagramLogFile.Plain(bangLog), resolveDiagramLogFile(bangLog.absolutePath), "a file name with a ! stays a plain file")
        assertEquals(
            HomeDiagramLogFile.ArchiveEntry(zip, "a!b/c.log"),
            resolveDiagramLogFile(zip.absolutePath + "!a!b/c.log"),
            "only the first ! that leaves an existing archive splits",
        )
        assertNull(resolveDiagramLogFile(File(dir, "gone.log").absolutePath))
        assertNull(resolveDiagramLogFile(File(dir, "gone.zip").absolutePath + "!x.log"))
        assertNull(resolveDiagramLogFile(zip.absolutePath + "!"), "an empty entry is nothing")
        assertNull(resolveDiagramLogFile(""))
        assertIs<HomeDiagramLogFile.Plain>(resolveDiagramLogFile("virtual.log") { it.path == "virtual.log" })
        dir.deleteRecursively()
    }

    @Test
    fun theFallbackNoticeSaysWhyTheLogDidNotOpen() {
        val missing = homeDiagramLogProblemNotice(HomeDiagramLogProblem.MISSING, "checkout.log")
        assertEquals("Source log not found", missing.title)
        assertTrue("checkout.log" in missing.body && "without it" in missing.body)

        assertEquals("Source log has changed", homeDiagramLogProblemNotice(HomeDiagramLogProblem.CHANGED, "x.log").title)
        assertEquals("Source log not opened", homeDiagramLogProblemNotice(HomeDiagramLogProblem.NOT_OPENED, "x.log").title)
        assertTrue("the source log" in homeDiagramLogProblemNotice(HomeDiagramLogProblem.MISSING, "").body)
    }
}
