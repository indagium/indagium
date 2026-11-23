package com.indagium

import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.parseSeq3Note
import com.indagium.diagram3.updateSeq3NoteSourcePath
import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibrarySnapshot
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.DiagramSourceIdentity
import com.indagium.ui.DiagramTicketFilter
import com.indagium.ui.createSharedNote
import com.indagium.ui.diagramSourceKey
import com.indagium.ui.mkTab
import com.indagium.ui.setSharedNoteMembers
import com.indagium.ui.shareTabNote
import com.indagium.ui.stopSharing
import com.indagium.utils.computeLogFingerprint
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A merged log keeps its tab identity across note ownership changes and title collisions.
 */
class Seq3MergedLogDiagramSourceTest {
    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val WAIT_MS = 4_000L
        const val PID = 7
        const val TID = 11
        const val MERGED = "Merged"
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_MS * NANOS_PER_MILLI
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition did not become true within ${WAIT_MS}ms")
    }

    private fun state(root: File, library: DiagramLibraryStore = DiagramLibraryStore(File(root, "library.cache"))): AppState = AppState(
        File(root, "state.cache"),
        notesDir = File(root, "notes"),
        diagramLibraryStore = library,
    ).also {
        val merged = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Producer", "start op", PID, TID),
            LogEntry(2, "10:00:00.050", LogLevel.I, "Consumer", "handle op", PID, TID),
        )
        val other = listOf(LogEntry(1, "11:00:00.000", LogLevel.I, "Other", "unrelated"), LogEntry(2, "11:00:00.010", LogLevel.I, "More", "rows"))
        it.tabs = listOf(
            mkTab("m", MERGED, merged),
            mkTab("b", "b.log", other).copy(sourcePath = File(root, "b.log").absolutePath),
        )
        assertNull(it.tab("m")!!.sourcePath, "a merged tab has no file path")
        it.activateTab("m")
    }

    private fun confirmed(state: AppState, tabId: String): String {
        val coordinator = state.seq3Sessions
        val id = assertNotNull(coordinator.begin(tabId, setOf(1, 2)))
        await { coordinator.sessions.firstOrNull { it.id == id }?.generating == false }
        val blockId = assertNotNull(coordinator.confirm(id))
        coordinator.close(id)
        return blockId
    }

    private fun stampOf(state: AppState, noteTabId: String, blockId: String): String? {
        val block = state.noteTab(noteTabId)!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note
        return parseSeq3Note(block.text)?.document?.sourcePath
    }

    private fun navigate(state: AppState, noteTabId: String, blockId: String, entryId: Int): Boolean =
        runBlocking { state.seq3Sessions.navigateToOccurrence(noteTabId, blockId, entryId) }

    @Test
    fun sharingStampsAMergedLogsDiagramAndItStillResolvesOnceThatLogLeavesTheMembers() {
        val root = createTempDirectory("indagium-seq3-merged-share").toFile()
        val state = state(root)
        try {
            val blockId = confirmed(state, "m")
            val hostId = assertNotNull(state.shareTabNote("m", "Investigation", listOf("m", "b")))
            assertEquals(state.tab("m")!!.diagramSourceKey, stampOf(state, hostId, blockId))

            assertTrue(state.setSharedNoteMembers(hostId, listOf("b")))
            state.activateTab("b")

            assertTrue(navigate(state, hostId, blockId, 2), "the diagram still names its open log")
            assertEquals(setOf(2), state.tab("m")!!.selected)
            assertTrue(state.tab("b")!!.selected.isEmpty())
            assertNull(state.pendingDiagramNotice)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun confirmingAMergedLogsDiagramInASharedNoteStampsItAndItResolvesAfterTheLogLeavesTheMembers() {
        val root = createTempDirectory("indagium-seq3-merged-confirm").toFile()
        val state = state(root)
        try {
            val firstBlock = confirmed(state, "m")
            val hostId = assertNotNull(state.shareTabNote("m", "Investigation", listOf("m", "b")))
            // The shared block is made to carry no stamp, as a diagram written by an older build does.
            val shared = state.sharedNoteHost(hostId)!!.annotations.blocks.single { it.id == firstBlock } as AnnBlock.Note
            state.updateBlock(hostId, firstBlock, assertNotNull(updateSeq3NoteSourcePath(shared.text, null)))
            assertNull(stampOf(state, hostId, firstBlock))

            val editing = assertNotNull(state.seq3Sessions.beginEdit(hostId, firstBlock))
            assertEquals("m", state.seq3Sessions.sessions.single { it.id == editing }.sourceTabId)
            state.seq3Sessions.updateTitle(editing, "Edited in the shared note")
            assertEquals(firstBlock, state.seq3Sessions.confirm(editing))
            assertEquals(state.tab("m")!!.diagramSourceKey, stampOf(state, hostId, firstBlock))
            state.seq3Sessions.close(editing)

            assertTrue(state.setSharedNoteMembers(hostId, listOf("b")))
            state.activateTab("b")

            assertTrue(navigate(state, hostId, firstBlock, 2))
            assertEquals(setOf(2), state.tab("m")!!.selected)
            assertNull(state.pendingDiagramNotice)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun stopSharingKeepsTheMergedLogsKeyAndItStillResolves() {
        val root = createTempDirectory("indagium-seq3-merged-stop").toFile()
        val state = state(root)
        try {
            val blockId = confirmed(state, "m")
            val hostId = assertNotNull(state.shareTabNote("m", "Investigation", listOf("m", "b")))
            assertTrue(state.stopSharing(hostId, "b", replaceExisting = true))

            assertEquals(state.tab("m")!!.diagramSourceKey, stampOf(state, "b", blockId))
            state.activateTab("b")
            assertTrue(navigate(state, "b", blockId, 2))
            assertEquals(setOf(2), state.tab("m")!!.selected)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun closingAMergedSourceNeverBindsItsDiagramToAnotherMergeWithTheSameTitleAndRows() {
        val root = createTempDirectory("indagium-seq3-merged-duplicate").toFile()
        val state = state(root)
        try {
            val sharedRows = state.tab("m")!!.logData.toList()
            state.tabs = state.tabs + mkTab("other-merge", MERGED, sharedRows + LogEntry(3, "11:00:00.000", LogLevel.I, "Other", "different"))
            val blockId = confirmed(state, "m")
            val hostId = assertNotNull(state.shareTabNote("m", "Investigation", listOf("m", "b")))

            state.upTab("m") { it.copy(filename = "Renamed merge") }
            assertTrue(navigate(state, hostId, blockId, 2))
            state.closeTab("m")

            assertNull(state.seq3Sessions.sourceTabIdForNote(hostId, blockId))
            assertTrue(state.stopSharing(hostId, "b"))
            assertNull(state.seq3Sessions.sourceTabIdForNote("b", blockId))
            assertTrue(state.tab("other-merge")!!.selected.isEmpty())
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aStampedManualDiagramKeepsItsTicketWhenReopenedAfterTheSourceCloses() {
        val root = createTempDirectory("indagium-seq3-merged-closed-ticket").toFile()
        val state = state(root)
        try {
            val coordinator = state.seq3Sessions
            val editor = assertNotNull(coordinator.beginEmpty("m"))
            assertTrue(coordinator.applyCommand(editor, Seq3Command.AddLifeline(Seq3Lifeline("manual", "Manual", emptySet(), 0))))
            val blockId = assertNotNull(coordinator.confirm(editor))
            val hostId = assertNotNull(state.shareTabNote("m", "Review", listOf("b")))
            val block = state.noteTab(hostId)!!.annotations.blocks.single() as AnnBlock.Note
            val saved = assertNotNull(parseSeq3Note(block.text)).document
            assertNull(saved.sourceFile)
            assertEquals(state.tab("m")!!.diagramSourceKey, saved.sourcePath)
            coordinator.close(editor)
            state.closeTab("m")

            val reopened = assertNotNull(coordinator.beginEdit(hostId, blockId))
            val session = coordinator.sessions.single { it.id == reopened }
            assertNull(session.sourceTabId)
            assertTrue(session.hasTicket)
            assertEquals("The source log is not open", session.logUnavailableReason())
            coordinator.updateTitle(reopened, "Edited without the log")
            assertEquals(blockId, coordinator.confirm(reopened))
            assertTrue(coordinator.listLibrary(DiagramTicketFilter.NO_TICKET).isEmpty())
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aLegacyManualDiagramOnlyUsesTheMergeThatMatchesItsSavedFingerprint() {
        val root = createTempDirectory("indagium-seq3-legacy-merge-fingerprint").toFile()
        val store = DiagramLibraryStore(File(root, "library.cache"))
        val state = state(root, store)
        try {
            val original = state.tab("m")!!
            val replacement = mkTab("other", MERGED, state.tab("b")!!.logData.toList())
            state.tabs = state.tabs + replacement
            val snapshot = DiagramLibrarySnapshot.create(Seq3Document(lifelines = listOf(Seq3Lifeline("manual", "Manual", emptySet(), 0))))
            val source = DiagramSourceIdentity(MERGED, computeLogFingerprint(original.logData))
            val item = store.create("Legacy diagram", "", source, snapshot)
            val host = assertNotNull(state.createSharedNote("Review", listOf(original.id, replacement.id, "b")))
            val coordinator = state.seq3Sessions
            val blockId = assertNotNull(coordinator.attachLibraryLink(host, item.id))
            val editor = assertNotNull(coordinator.beginEdit(host, blockId))
            assertEquals(original.id, coordinator.sessions.single { it.id == editor }.sourceTabId)
            coordinator.close(editor)
            state.closeTab(original.id)

            val reopened = assertNotNull(coordinator.beginEdit(host, blockId))
            assertNull(coordinator.sessions.single { it.id == reopened }.sourceTabId)
            coordinator.close(reopened)
            val privateBlock = assertNotNull(coordinator.attachLibraryLink(replacement.id, item.id))
            assertNull(coordinator.sourceTabIdForNote(replacement.id, privateBlock))
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aMergeCreatedAfterRestartCannotInheritTheClosedMergesDiagram() {
        val root = createTempDirectory("indagium-seq3-merged-restart").toFile()
        val cache = File(root, "state.cache")

        fun app(restore: Boolean) = AppState(
            cache,
            restoreOnCreate = restore,
            autoExportNotes = false,
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        )
        try {
            val original = app(restore = false)
            val fixtures = try {
                val sourceTabs = (1..3).map { index ->
                    val file = File(root, "source-$index.log").apply {
                        writeText("10:00:00.000 I/Tag: row $index\n")
                    }
                    assertNotNull(original.openFile(file))
                }
                await { sourceTabs.all { original.tab(it)?.logData?.isNotEmpty() == true } }
                original.mergeTabs(sourceTabs.take(2), "Original merge")
                await { original.tabs.any { it.filename == "Original merge" } }
                val merged = original.tabs.single { it.filename == "Original merge" }
                val recipient = original.tab(sourceTabs[1])!!

                val coordinator = original.seq3Sessions
                val editor = assertNotNull(coordinator.beginEmpty(merged.id))
                assertTrue(coordinator.applyCommand(editor, Seq3Command.AddLifeline(Seq3Lifeline("manual", "Manual", emptySet(), 0))))
                val block = assertNotNull(coordinator.confirm(editor))
                val host = assertNotNull(original.shareTabNote(merged.id, "Review", listOf(recipient.id)))
                assertTrue(original.stopSharing(host, recipient.id))
                assertEquals(merged.diagramSourceKey, stampOf(original, recipient.id, block))
                coordinator.close(editor)
                original.closeTab(merged.id)
                original.autosaveNow()
                MergeRestartFixture(sourceTabs, recipient, merged, block)
            } finally {
                original.close()
            }

            val restored = app(restore = true)
            try {
                restored.startPendingRestoredTabLoads()
                runBlocking {
                    fixtures.sourceTabs.forEach { assertTrue(assertNotNull(restored.awaitTabLoaded(it)).logData.isNotEmpty()) }
                }
                val owner = assertNotNull(restored.tab(fixtures.recipient.id))
                assertEquals(fixtures.recipient.sourceInstanceId, owner.sourceInstanceId)
                restored.mergeTabs(listOf(fixtures.sourceTabs[0], fixtures.sourceTabs[2]), "Replacement merge")
                await { restored.tabs.any { it.filename == "Replacement merge" } }
                val replacement = restored.tabs.single { it.filename == "Replacement merge" }
                assertEquals(fixtures.merged.id, replacement.id, "the numeric ID is reused in this fixture")
                assertNotEquals(fixtures.merged.diagramSourceKey, replacement.diagramSourceKey)
                assertNull(restored.seq3Sessions.sourceTabIdForNote(owner.id, fixtures.blockId))
            } finally {
                restored.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private data class MergeRestartFixture(
        val sourceTabs: List<String>,
        val recipient: LogTab,
        val merged: LogTab,
        val blockId: String,
    )
}
