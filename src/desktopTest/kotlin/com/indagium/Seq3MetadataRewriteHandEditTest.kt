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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A rewrite of a diagram note's metadata (path, caption, export mode) must not make a hand-edited fence look untouched. */
class Seq3MetadataRewriteHandEditTest {
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

    private fun handEditedNote(): String {
        val text = encodeSeq3Note(Seq3Document(title = "t", sourceFile = "a.log"), Seq3Dialect.MERMAID)
        val tampered = text.replaceFirst("sequenceDiagram\n", "sequenceDiagram\n    Note over A: hand-edited\n")
        check(tampered != text) { "the tamper must change the note" }
        assertTrue(seq3NoteHasHandEdit(tampered), "fixture precondition")
        return tampered
    }

    @Test
    fun aSourcePathRewriteKeepsADriftedFenceDrifted() {
        val rewritten = assertNotNull(updateSeq3NoteSourcePath(handEditedNote(), "/logs/a.log"))
        assertTrue(seq3NoteHasHandEdit(rewritten))
        val parsed = assertNotNull(parseSeq3Note(rewritten))
        assertEquals("/logs/a.log", parsed.document.sourcePath)
        assertTrue(parsed.source.contains("hand-edited"), "the fence itself is carried verbatim")
    }

    @Test
    fun aCaptionRewriteKeepsADriftedFenceDrifted() {
        val rewritten = assertNotNull(updateSeq3NoteCaption(handEditedNote(), "a caption"))
        assertTrue(seq3NoteHasHandEdit(rewritten))
        assertEquals("a caption", parseSeq3Note(rewritten)?.caption)
    }

    @Test
    fun anExportModeRewriteKeepsADriftedFenceDrifted() {
        val rewritten = assertNotNull(updateSeq3NoteExportMode(handEditedNote(), DiagramExportMode.SOURCE))
        assertTrue(seq3NoteHasHandEdit(rewritten))
        assertEquals(DiagramExportMode.SOURCE, parseSeq3Note(rewritten)?.exportMode)
    }

    @Test
    fun rewritingAnUntouchedNoteStillKeepsItUntouched() {
        val text = encodeSeq3Note(Seq3Document(title = "t"), Seq3Dialect.MERMAID)
        for (rewritten in listOf(
            updateSeq3NoteSourcePath(text, "/logs/a.log"),
            updateSeq3NoteCaption(text, "c"),
            updateSeq3NoteExportMode(text, DiagramExportMode.SOURCE),
        )) {
            assertFalse(seq3NoteHasHandEdit(assertNotNull(rewritten)))
        }
    }

    @Test
    fun adoptingStillClearsTheDriftAfterAMetadataRewrite() {
        val rewritten = assertNotNull(updateSeq3NoteSourcePath(handEditedNote(), "/logs/a.log"))
        assertFalse(seq3NoteHasHandEdit(assertNotNull(adoptSeq3NoteSource(rewritten))))
    }

    @Test
    fun aHandEditedDiagramInASharedNoteIsStillGuardedAfterStopSharing() {
        val root = createTempDirectory("indagium-seq3-stop-sharing-hand-edit").toFile()
        val rows = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Producer", "start op", PID, TID),
            LogEntry(2, "10:00:00.050", LogLevel.I, "Consumer", "handle op", PID, TID),
        )
        val other = listOf(LogEntry(1, "11:00:00.000", LogLevel.I, "Other", "unrelated"))
        val state = AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        ).also {
            it.tabs = listOf(
                mkTab("a", "a.log", rows).copy(sourcePath = File(root, "a.log").absolutePath),
                mkTab("b", "b.log", other).copy(sourcePath = File(root, "b.log").absolutePath),
            )
            it.activateTab("a")
        }
        try {
            val coordinator = state.seq3Sessions
            val original = assertNotNull(coordinator.begin("a", setOf(1, 2)))
            await { coordinator.sessions.firstOrNull { it.id == original }?.generating == false }
            val blockId = assertNotNull(coordinator.confirm(original))
            coordinator.close(original)
            val hostId = assertNotNull(state.shareTabNote("a", "Investigation", listOf("a", "b")))

            // A recipient hand-edits the fence in the shared note, then the note stops being shared.
            val hostNote = state.noteTab(hostId)!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note
            val fence = Regex("```[a-z]+\n").find(hostNote.text)!!.value
            val tampered = hostNote.text.replaceFirst(fence, fence + "' hand-edited\n")
            assertTrue(tampered != hostNote.text, "the tamper must change the fence")
            state.updateBlock(hostId, blockId, tampered)
            assertTrue(state.stopSharing(hostId, "b"))

            val moved = (state.tab("b")!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note).text
            assertNotNull(parseSeq3Note(moved)?.document?.sourcePath, "fixture precondition: stop sharing stamped the diagram")
            assertTrue(seq3NoteHasHandEdit(moved), "stamping the source must not make the hand edit look untouched")

            // Confirm from the recipient goes through the hand-edit prompt instead of overwriting.
            val editing = assertNotNull(coordinator.beginEdit("b", blockId))
            assertNull(coordinator.confirm(editing))
            assertNotNull(state.pendingNoteOverwrite?.handEdit, "the hand-edit overwrite prompt is up")
            assertEquals(moved, (state.tab("b")!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note).text)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }
}
