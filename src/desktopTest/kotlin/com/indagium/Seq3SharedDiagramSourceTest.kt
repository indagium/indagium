package com.indagium

import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.diagram3.parseSeq3Note
import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.mkTab
import com.indagium.ui.shareTabNote
import com.indagium.ui.stopSharing
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A diagram opened from a shared note, or from a private note it was moved into by Stop sharing, finds the log it was made from:
 * among several open tabs of the same file by the content, and never by the tab whose note it merely sits in.
 */
class Seq3SharedDiagramSourceTest {
    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val WAIT_MS = 4_000L
        const val PID = 7
        const val TID = 11
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_MS * NANOS_PER_MILLI
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition did not become true within ${WAIT_MS}ms")
    }

    private fun rows(): List<LogEntry> = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "Producer", "start op", PID, TID),
        LogEntry(2, "10:00:00.050", LogLevel.I, "Consumer", "handle op", PID, TID),
    )

    @Test
    fun theTabWithTheDiagramsOwnContentWinsOverAnotherTabOfTheSameFile() {
        val root = createTempDirectory("indagium-seq3-shared-source").toFile()
        val path = File(root, "sample.log").absolutePath
        val state = AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        ).also {
            // The same file read twice, as reparsing does: the copy listed first holds different rows.
            val reparsed = mkTab("reparsed", "sample.log · JSON", rows() + LogEntry(3, "10:00:00.100", LogLevel.W, "Other", "extra"))
                .copy(sourcePath = path)
            it.tabs = listOf(reparsed, mkTab("log", "sample.log", rows()).copy(sourcePath = path))
            it.activateTab("log")
        }
        try {
            val coordinator = state.seq3Sessions
            val original = assertNotNull(coordinator.begin("log", setOf(1, 2)))
            await { coordinator.sessions.firstOrNull { it.id == original }?.generating == false }
            val blockId = assertNotNull(coordinator.confirm(original))
            val hostId = assertNotNull(state.shareTabNote("log", "Investigation", listOf("log")))
            coordinator.close(original)

            // The library record keeps the log's path and fingerprint; two tabs share the path, and the fingerprint picks "log".
            assertNotNull(coordinator.beginEdit(hostId, blockId))
            assertEquals("log", coordinator.sessions.single().sourceTabId)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    private fun twoLogsState(root: File): AppState = AppState(
        File(root, "state.cache"),
        notesDir = File(root, "notes"),
        diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
    ).also {
        val other = listOf(LogEntry(1, "11:00:00.000", LogLevel.I, "Other", "unrelated"), LogEntry(2, "11:00:00.010", LogLevel.I, "More", "rows"))
        it.tabs = listOf(
            mkTab("a", "a.log", rows()).copy(sourcePath = File(root, "a.log").absolutePath),
            mkTab("b", "b.log", other).copy(sourcePath = File(root, "b.log").absolutePath),
        )
        it.activateTab("a")
    }

    @Test
    fun aDiagramMovedByStopSharingIntoAnotherLogsNoteStillUsesTheLogItWasMadeFrom() {
        val root = createTempDirectory("indagium-seq3-stop-sharing-source").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            val original = assertNotNull(coordinator.begin("a", setOf(1, 2)))
            await { coordinator.sessions.firstOrNull { it.id == original }?.generating == false }
            val blockId = assertNotNull(coordinator.confirm(original))
            // The note also cites a's rows, which is how the note is "about" a once it sits in b's private note.
            state.upAnn("a") { it.copy(annotations = it.annotations.copy(blocks = it.annotations.blocks + AnnBlock.LogRef("r1", listOf(1), "rows"))) }
            val hostId = assertNotNull(state.shareTabNote("a", "Investigation", listOf("a", "b")))
            coordinator.close(original)
            assertTrue(state.stopSharing(hostId, "b"))

            // The diagram now sits in b's private note, but its rows are a's.
            val reopened = assertNotNull(coordinator.beginEdit("b", blockId))
            assertEquals("a", coordinator.sessions.single { it.id == reopened }.sourceTabId)
            coordinator.close(reopened)

            // With a closed, nothing else may stand in for it: b's own rows are not the diagram's.
            state.closeTab("a")
            val without = assertNotNull(coordinator.beginEdit("b", blockId))
            assertNull(coordinator.sessions.single { it.id == without }.sourceTabId)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aDiagramMadeFromALogInThatLogsOwnNoteStillUsesIt() {
        val root = createTempDirectory("indagium-seq3-own-note-source").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            state.activateTab("b")
            val original = assertNotNull(coordinator.begin("b", setOf(1, 2)))
            await { coordinator.sessions.firstOrNull { it.id == original }?.generating == false }
            val blockId = assertNotNull(coordinator.confirm(original))
            coordinator.close(original)

            val reopened = assertNotNull(coordinator.beginEdit("b", blockId))
            assertEquals("b", coordinator.sessions.single { it.id == reopened }.sourceTabId)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aDiagramInItsOwnLogsNoteStillUsesItAfterTheTabIsKnownByAnotherName() {
        val root = createTempDirectory("indagium-seq3-renamed-source").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            val original = assertNotNull(coordinator.begin("a", setOf(1, 2)))
            await { coordinator.sessions.firstOrNull { it.id == original }?.generating == false }
            val blockId = assertNotNull(coordinator.confirm(original))
            coordinator.close(original)
            // The same log under another name: a capture tab titled by its device, a log relinked after the file was renamed.
            state.upTab("a") { it.copy(filename = "Pixel 7 · 10:42", sourcePath = File(root, "renamed.log").absolutePath) }

            assertNotNull(coordinator.beginEdit("a", blockId))
            assertEquals("a", coordinator.sessions.single().sourceTabId, "nothing says the diagram is about another log")
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    /** A diagram made from "a", confirmed into a's note, then moved with a's whole note into a shared note over a and b. */
    private fun sharedDiagramFromA(state: AppState): Pair<String, String> {
        val coordinator = state.seq3Sessions
        val original = assertNotNull(coordinator.begin("a", setOf(1, 2)))
        await { coordinator.sessions.firstOrNull { it.id == original }?.generating == false }
        val blockId = assertNotNull(coordinator.confirm(original))
        coordinator.close(original)
        val hostId = assertNotNull(state.shareTabNote("a", "Investigation", listOf("a", "b")))
        return hostId to blockId
    }

    private fun noteDocument(state: AppState, tabId: String, blockId: String): Seq3Document {
        val block = state.tab(tabId)!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note
        return assertNotNull(parseSeq3Note(block.text)).document
    }

    @Test
    fun confirmingAMovedDiagramUpdatesItsBlockInTheNoteItSitsInAndTheSessionIsFoundAgain() {
        val root = createTempDirectory("indagium-seq3-moved-confirm").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            val (hostId, blockId) = sharedDiagramFromA(state)
            assertTrue(state.stopSharing(hostId, "b"))
            val notesOfA = state.tab("a")!!.annotations

            val editing = assertNotNull(coordinator.beginEdit("b", blockId))
            assertEquals("a", coordinator.sessions.single().sourceTabId)
            assertEquals("b", coordinator.sessions.single().noteTargetTabId, "the diagram is written back to the note it sits in")
            coordinator.updateTitle(editing, "Renamed in b")
            val written = assertNotNull(coordinator.confirm(editing))

            assertEquals(blockId, written, "the same block is updated in place")
            assertEquals("Renamed in b", noteDocument(state, "b", blockId).title)
            assertEquals(1, state.tab("b")!!.annotations.blocks.size)
            assertEquals(notesOfA, state.tab("a")!!.annotations, "a's note is untouched")
            assertEquals(editing, coordinator.beginEdit("b", blockId), "reopening finds the same session")
            assertEquals(1, coordinator.sessions.size)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aMovedDiagramWhoseLogIsClosedStillConfirmsIntoTheNoteItSitsIn() {
        val root = createTempDirectory("indagium-seq3-moved-offline-confirm").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            val (hostId, blockId) = sharedDiagramFromA(state)
            assertTrue(state.stopSharing(hostId, "b"))
            state.closeTab("a")

            val editing = assertNotNull(coordinator.beginEdit("b", blockId))
            assertNull(coordinator.sessions.single().sourceTabId)
            assertEquals("b", coordinator.sessions.single().noteTargetTabId)
            coordinator.updateTitle(editing, "Offline edit")
            assertEquals(blockId, coordinator.confirm(editing))
            assertEquals("Offline edit", noteDocument(state, "b", blockId).title)
            assertEquals(editing, coordinator.beginEdit("b", blockId))
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aDiagramAloneInASharedNoteKeepsItsOpenLogAfterStopSharingEvenWhenNothingCitesIt() {
        val root = createTempDirectory("indagium-seq3-stamp-open").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            val (hostId, blockId) = sharedDiagramFromA(state)
            assertTrue(state.stopSharing(hostId, "b"))

            assertEquals(File(root, "a.log").absolutePath, noteDocument(state, "b", blockId).sourcePath)
            val reopened = assertNotNull(coordinator.beginEdit("b", blockId))
            assertEquals("a", coordinator.sessions.single { it.id == reopened }.sourceTabId)
            coordinator.close(reopened)

            // The log closed after Stop sharing: nothing may stand in for it, b's rows least of all.
            state.closeTab("a")
            val without = assertNotNull(coordinator.beginEdit("b", blockId))
            assertNull(coordinator.sessions.single { it.id == without }.sourceTabId)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aDiagramAloneInASharedNoteWhoseLogClosedBeforeStopSharingIsUnavailableNotTheRecipients() {
        val root = createTempDirectory("indagium-seq3-stamp-closed-member").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            val (hostId, blockId) = sharedDiagramFromA(state)
            state.closeTab("a")
            assertTrue(state.stopSharing(hostId, "b"))

            assertEquals(File(root, "a.log").absolutePath, noteDocument(state, "b", blockId).sourcePath)
            val reopened = assertNotNull(coordinator.beginEdit("b", blockId))
            assertNull(coordinator.sessions.single { it.id == reopened }.sourceTabId)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aDiagramMadeFromTheRecipientItselfIsNotStamped() {
        val root = createTempDirectory("indagium-seq3-stamp-none").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            state.activateTab("b")
            val original = assertNotNull(coordinator.begin("b", setOf(1, 2)))
            await { coordinator.sessions.firstOrNull { it.id == original }?.generating == false }
            val blockId = assertNotNull(coordinator.confirm(original))
            coordinator.close(original)
            val hostId = assertNotNull(state.shareTabNote("b", "Investigation", listOf("a", "b")))
            assertTrue(state.stopSharing(hostId, "b"))

            assertNull(noteDocument(state, "b", blockId).sourcePath, "its log is the recipient itself")
            val reopened = assertNotNull(coordinator.beginEdit("b", blockId))
            assertEquals("b", coordinator.sessions.single { it.id == reopened }.sourceTabId)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aStampedDiagramKeepsItsLogThroughAnEditAndConfirmInTheRecipientsNote() {
        val root = createTempDirectory("indagium-seq3-stamp-confirm").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            val (hostId, blockId) = sharedDiagramFromA(state)
            assertTrue(state.stopSharing(hostId, "b"))
            val editing = assertNotNull(coordinator.beginEdit("b", blockId))
            coordinator.updateTitle(editing, "Edited")
            assertEquals(blockId, coordinator.confirm(editing))
            coordinator.close(editing)

            assertEquals(File(root, "a.log").absolutePath, noteDocument(state, "b", blockId).sourcePath, "the stamp survives the confirm")
            state.closeTab("a")
            val reopened = assertNotNull(coordinator.beginEdit("b", blockId))
            assertNull(coordinator.sessions.single { it.id == reopened }.sourceTabId, "a's log is closed: b's rows are not the diagram's")
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun anOpenSessionOnTheMovedDiagramCarriesTheStampSoItsNextConfirmKeepsIt() {
        val root = createTempDirectory("indagium-seq3-stamp-open-session").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            val (hostId, blockId) = sharedDiagramFromA(state)
            val editing = assertNotNull(coordinator.beginEdit(hostId, blockId))
            assertTrue(state.stopSharing(hostId, "b"))
            assertEquals(File(root, "a.log").absolutePath, coordinator.sessions.single { it.id == editing }.document.sourcePath)
            coordinator.updateTitle(editing, "Edited while open")
            assertEquals(blockId, coordinator.confirm(editing))
            assertEquals(File(root, "a.log").absolutePath, noteDocument(state, "b", blockId).sourcePath)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    /** a and b as in [twoLogsState], plus another log "c" that is also called "a.log" but is another file. */
    private fun threeLogsState(root: File): AppState = twoLogsState(root).also {
        val third = listOf(LogEntry(1, "12:00:00.000", LogLevel.I, "Third", "same name, other file"))
        it.tabs = it.tabs + mkTab("c", "a.log", third).copy(sourcePath = File(root, "elsewhere/a.log").absolutePath)
    }

    /** A diagram made from a, moved into b's private note by Stop sharing (stamped with a's path), then shared again with c. */
    private fun stampedDiagramSharedAgain(state: AppState): Pair<String, String> {
        val (hostId, blockId) = sharedDiagramFromA(state)
        assertTrue(state.stopSharing(hostId, "b"))
        assertEquals(File(state.tab("a")!!.sourcePath!!).absolutePath, noteDocument(state, "b", blockId).sourcePath, "fixture: stamped")
        val again = assertNotNull(state.shareTabNote("b", "Second investigation", listOf("b", "c")))
        return again to blockId
    }

    @Test
    fun aStampedDiagramSharedAgainStillFindsItsOpenLogNotASameNamedOne() {
        val root = createTempDirectory("indagium-seq3-stamp-shared-again").toFile()
        val state = threeLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            val (hostId, blockId) = stampedDiagramSharedAgain(state)

            // a is not a member of the new shared note; its stamp, not the file name "a.log" (which c also has), names the log.
            assertEquals("a", coordinator.sourceTabIdForNote(hostId, blockId))
            val reopened = assertNotNull(coordinator.beginEdit(hostId, blockId))
            assertEquals("a", coordinator.sessions.single { it.id == reopened }.sourceTabId)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aStampedDiagramSharedAgainHasNoLogWhenItsLogIsClosedNeverASameNamedOne() {
        val root = createTempDirectory("indagium-seq3-stamp-shared-again-closed").toFile()
        val state = threeLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            val (hostId, blockId) = stampedDiagramSharedAgain(state)
            state.closeTab("a")

            assertNull(coordinator.sourceTabIdForNote(hostId, blockId), "c is called a.log too, but is not the diagram's log")
            val reopened = assertNotNull(coordinator.beginEdit(hostId, blockId))
            assertNull(coordinator.sessions.single { it.id == reopened }.sourceTabId)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun stoppingTheSecondSharingKeepsTheExistingStampInsteadOfStampingAnotherLog() {
        val root = createTempDirectory("indagium-seq3-stamp-kept").toFile()
        val state = threeLogsState(root)
        try {
            val (hostId, blockId) = stampedDiagramSharedAgain(state)
            assertTrue(state.stopSharing(hostId, "b"))

            assertEquals(File(root, "a.log").absolutePath, noteDocument(state, "b", blockId).sourcePath)
            assertEquals("a", state.seq3Sessions.sourceTabIdForNote("b", blockId))
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun undoAfterStopSharingKeepsTheStampSoTheNextConfirmStillNamesTheLog() {
        val root = createTempDirectory("indagium-seq3-stamp-undo").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            val (hostId, blockId) = sharedDiagramFromA(state)
            val editing = assertNotNull(coordinator.beginEdit(hostId, blockId))
            // An edit before the stamp: its undo snapshot holds the unstamped document.
            coordinator.updateTitle(editing, "Edited while shared")
            assertTrue(coordinator.canUndo(editing))
            assertTrue(state.stopSharing(hostId, "b"))
            assertEquals(File(root, "a.log").absolutePath, coordinator.sessions.single { it.id == editing }.document.sourcePath)

            assertTrue(coordinator.undo(editing))
            val afterUndo = coordinator.sessions.single { it.id == editing }.document
            assertEquals(File(root, "a.log").absolutePath, afterUndo.sourcePath, "undo restores the title, not the source")
            assertEquals(blockId, coordinator.confirm(editing))

            assertEquals(File(root, "a.log").absolutePath, noteDocument(state, "b", blockId).sourcePath, "the stamp survives undo and confirm")
            assertEquals("a", coordinator.sourceTabIdForNote("b", blockId))
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun theSourcePathRoundTripsAndAnUnstampedDocumentEncodesWithoutTheKey() {
        val plain = Seq3Document(title = "t", sourceFile = "a.log")
        val stampedText = encodeSeq3Note(plain.copy(sourcePath = "/logs/a.log"))
        assertEquals("/logs/a.log", parseSeq3Note(stampedText)?.document?.sourcePath)
        val plainText = encodeSeq3Note(plain)
        assertNull(parseSeq3Note(plainText)?.document?.sourcePath)
        assertTrue("sourcePath" !in plainText, "an unstamped document must encode exactly as before")
        assertEquals(plainText, encodeSeq3Note(parseSeq3Note(plainText)!!.document))
        // A header written by this build for an older reader: the key sits inside the document map, which readers look up by name.
        assertTrue(stampedText.contains("\"sourcePath\":\"/logs/a.log\""))
    }
}
