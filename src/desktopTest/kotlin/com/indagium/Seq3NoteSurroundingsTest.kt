package com.indagium

import com.indagium.diagram3.DiagramExportMode
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.adoptSeq3NoteSource
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.diagram3.parseSeq3Note
import com.indagium.diagram3.seq3NoteHasHandEdit
import com.indagium.diagram3.updateSeq3NoteCaption
import com.indagium.diagram3.updateSeq3NoteExportMode
import com.indagium.diagram3.updateSeq3NoteSourcePath
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [parseSeq3Note] accepts prose after the closing fence (and whitespace before the header). Every metadata rewrite and every session
 * path that replaces a diagram note's text keeps it, instead of deleting what the user wrote beside the diagram.
 */
class Seq3NoteSurroundingsTest {
    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val WAIT_MS = 4_000L
        const val PID = 7
        const val TID = 11
        const val PROSE = "Some prose after.\n"
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_MS * NANOS_PER_MILLI
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition did not become true within ${WAIT_MS}ms")
    }

    private fun noteWithProse(leading: String = ""): String =
        leading + encodeSeq3Note(Seq3Document(title = "t", sourceFile = "a.log"), Seq3Dialect.PLANTUML) + PROSE

    private fun assertSurroundingsKept(original: String, rewritten: String, leading: String = "") {
        assertTrue(rewritten.startsWith(leading), "the whitespace before the header is kept")
        assertTrue(rewritten.endsWith(PROSE), "the prose after the fence is kept: <$rewritten>")
        val before = assertNotNull(parseSeq3Note(original))
        val after = assertNotNull(parseSeq3Note(rewritten))
        assertEquals(before.sourceHashMatches, after.sourceHashMatches, "the hash state does not change")
        assertEquals(before.source, after.source, "the fence is carried verbatim")
    }

    @Test
    fun aSourcePathRewriteKeepsTheProseAfterTheFence() {
        val note = noteWithProse()
        val rewritten = assertNotNull(updateSeq3NoteSourcePath(note, "/logs/a.log"))
        assertSurroundingsKept(note, rewritten)
        assertEquals("/logs/a.log", parseSeq3Note(rewritten)?.document?.sourcePath)
    }

    @Test
    fun aCaptionRewriteKeepsTheProseAndTheLeadingWhitespace() {
        val note = noteWithProse(leading = "\n  ")
        val rewritten = assertNotNull(updateSeq3NoteCaption(note, "a caption"))
        assertSurroundingsKept(note, rewritten, leading = "\n  ")
        assertEquals("a caption", parseSeq3Note(rewritten)?.caption)
    }

    @Test
    fun anExportModeRewriteKeepsTheProseAfterTheFence() {
        val note = noteWithProse()
        val rewritten = assertNotNull(updateSeq3NoteExportMode(note, DiagramExportMode.SOURCE))
        assertSurroundingsKept(note, rewritten)
        assertEquals(DiagramExportMode.SOURCE, parseSeq3Note(rewritten)?.exportMode)
    }

    @Test
    fun adoptingTheSourceKeepsTheProseAndClearsTheDrift() {
        val plain = noteWithProse()
        val drifted = plain.replaceFirst("@startuml\n", "@startuml\nnote over A: hand-edited\n")
        check(drifted != plain)
        assertTrue(seq3NoteHasHandEdit(drifted), "fixture precondition")
        val adopted = assertNotNull(adoptSeq3NoteSource(drifted))
        assertTrue(adopted.endsWith(PROSE), "the prose after the fence is kept: <$adopted>")
        assertFalse(seq3NoteHasHandEdit(adopted))
        assertTrue(assertNotNull(parseSeq3Note(adopted)).source.contains("hand-edited"))
    }

    @Test
    fun aDriftedNoteWithProseStaysDriftedThroughAMetadataRewrite() {
        val plain = noteWithProse()
        val drifted = plain.replaceFirst("@startuml\n", "@startuml\nnote over A: hand-edited\n")
        val rewritten = assertNotNull(updateSeq3NoteCaption(drifted, "c"))
        assertTrue(seq3NoteHasHandEdit(rewritten))
        assertTrue(rewritten.endsWith(PROSE))
    }

    // ── The session paths ───────────────────────────────────────────────────────────────────────

    private fun rows(): List<LogEntry> = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "Producer", "start op", PID, TID),
        LogEntry(2, "10:00:00.050", LogLevel.I, "Consumer", "handle op", PID, TID),
    )

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

    private fun noteText(state: AppState, tabId: String, blockId: String): String =
        (state.noteTab(tabId)!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note).text

    /** A diagram of a confirmed into a's note, with prose typed after it. Returns the session id and the block id. */
    private fun diagramWithProse(state: AppState): Pair<String, String> {
        val coordinator = state.seq3Sessions
        val session = assertNotNull(coordinator.begin("a", setOf(1, 2)))
        await { coordinator.sessions.firstOrNull { it.id == session }?.generating == false }
        val blockId = assertNotNull(coordinator.confirm(session))
        state.updateBlock("a", blockId, noteText(state, "a", blockId) + PROSE)
        return session to blockId
    }

    @Test
    fun shareAndStopSharingKeepTheProseAfterTheFence() {
        val root = createTempDirectory("indagium-seq3-surroundings-share").toFile()
        val state = twoLogsState(root)
        try {
            val (session, blockId) = diagramWithProse(state)
            state.seq3Sessions.close(session)

            val hostId = assertNotNull(state.shareTabNote("a", "Investigation", listOf("a", "b")))
            val shared = noteText(state, hostId, blockId)
            assertTrue(shared.endsWith(PROSE), "Share stamps the source path and keeps the prose: <$shared>")
            assertEquals(File(root, "a.log").absolutePath, parseSeq3Note(shared)?.document?.sourcePath, "fixture: the rewrite ran")

            assertTrue(state.stopSharing(hostId, "b"))
            val returned = noteText(state, "b", blockId)
            assertTrue(returned.endsWith(PROSE), "Stop sharing keeps the prose: <$returned>")
            assertEquals(false, seq3NoteHasHandEdit(returned))
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun confirmKeepsTheProseAfterTheFence() {
        val root = createTempDirectory("indagium-seq3-surroundings-confirm").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            val (session, blockId) = diagramWithProse(state)
            coordinator.updateTitle(session, "Renamed")
            assertEquals(blockId, coordinator.confirm(session))

            val text = noteText(state, "a", blockId)
            assertEquals("Renamed", assertNotNull(parseSeq3Note(text)).document.title, "fixture: the confirm rewrote the note")
            assertTrue(text.endsWith(PROSE), "Confirm keeps the prose: <$text>")
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aLiveLinkSyncKeepsTheProseAfterTheFence() {
        val root = createTempDirectory("indagium-seq3-surroundings-link").toFile()
        val state = twoLogsState(root)
        try {
            val coordinator = state.seq3Sessions
            val session = assertNotNull(coordinator.begin("a", setOf(1, 2)))
            await { coordinator.sessions.firstOrNull { it.id == session }?.generating == false }
            val blockId = assertNotNull(coordinator.attachLiveLink(session))
            state.updateBlock("a", blockId, noteText(state, "a", blockId) + PROSE)

            coordinator.updateTitle(session, "Renamed live")
            assertEquals(blockId, coordinator.confirm(session))

            val text = noteText(state, "a", blockId)
            assertEquals("Renamed live", assertNotNull(parseSeq3Note(text)).document.title, "fixture: the link followed the session")
            assertTrue(text.endsWith(PROSE), "the live-link sync keeps the prose: <$text>")
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }
}
