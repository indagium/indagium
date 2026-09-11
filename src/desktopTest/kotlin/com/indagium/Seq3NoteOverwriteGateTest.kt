package com.indagium

import com.indagium.diagram3.parseSeq3Note
import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.annotationsToken
import com.indagium.ui.mkTab
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * v3 port of the deleted `DiagramNoteOverwriteGateTest`. The note-overwrite-prompt fix (§1/§5 of
 * the "notes commit before the overwrite prompt is answered" fix) is exercised through the v3
 * sequence-diagram confirm flow (`AppState.seq3Sessions.confirm`) rather than the plain
 * addNoteBlock/confirmAddAnn paths AppStateBehaviorTest's NoteOverwrite group covers — a v3
 * diagram note is still just an ordinary [AnnBlock.Note] by the time it reaches upAnn (see
 * Seq3Session.confirm()), but it arrives through a different call path, and that path used to be
 * the one where this bug was first noticed and reported.
 */
class Seq3NoteOverwriteGateTest {
    private val entries = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "BluetoothAdapter", "enable() called"),
        LogEntry(2, "10:00:00.120", LogLevel.I, "BluetoothManagerService", "handleEnable"),
        LogEntry(3, "10:00:00.300", LogLevel.E, "BluetoothManagerService", "bind failed"),
        LogEntry(4, "10:00:00.480", LogLevel.I, "BluetoothAdapter", "STATE_OFF"),
    )

    private fun stateWithExistingNote(dir: File): Triple<AppState, File, String> {
        val notesDir = File(dir, "notes").apply { mkdirs() }
        val sourcePath = File(dir, "sample.log").absolutePath
        val existingMd = File(notesDir, "sample_analysis.md").apply { writeText("## earlier analysis\n\nkeep this") }
        File(notesDir, "sample_analysis.ann").writeText(
            Annotations(blocks = listOf(AnnBlock.Note(id = "n1", text = "earlier note"))).annotationsToken(sourcePath),
        )
        val state = AppState(
            File(dir, "state.cache"),
            notesDir = notesDir,
            diagramLibraryStore = DiagramLibraryStore(File(dir, "library.cache")),
        )
        state.tabs = listOf(mkTab("log", "sample.log", entries).copy(sourcePath = sourcePath))
        return Triple(state, existingMd, sourcePath)
    }

    // Opens a v3 workspace and returns once its generate pass has produced at least one message —
    // every test below starts from here, since begin()'s generate runs on Seq3Session's own
    // debounced background scope.
    private fun AppState.beginAndAwaitGenerate(tabId: String): String {
        val id = requireNotNull(seq3Sessions.begin(tabId, entries.mapTo(linkedSetOf()) { it.id }))
        waitUntil { seq3Sessions.sessions.firstOrNull { it.id == id }?.document?.messages?.isNotEmpty() == true }
        return id
    }

    @Test
    fun addingADiagramNoteIsNotObservableInTabsWhileTheOverwritePromptIsPending() {
        val dir = createTempDirectory("openlog-seq3-overwrite-invisible").toFile()
        val (state, existingMd, _) = stateWithExistingNote(dir)
        val originalBytes = existingMd.readBytes()

        val id = state.beginAndAwaitGenerate("log")
        val blockId = state.seq3Sessions.confirm(id)

        // Deferred, not failed: the membership-check contract (Seq3Session.confirm's own upAnn
        // path) means a null return here is expected and benign, not an error.
        assertEquals(null, blockId)
        val pending = assertNotNull(state.pendingNoteOverwrite, "expected the overwrite prompt to be up")
        assertEquals("log", pending.tabId)

        // The reported bug: the tab must show ZERO new blocks while the prompt is open, not just
        // "the disk file happens to be unchanged."
        assertTrue(
            state.tab("log")?.annotations?.blocks.orEmpty().isEmpty(),
            "the tab must carry zero blocks until the prompt is resolved",
        )
        Thread.sleep(150)
        assertTrue(originalBytes.contentEquals(existingMd.readBytes()), "existing notes file must not change before the prompt is resolved")
    }

    @Test
    fun confirmNoteOverwriteCommitsTheDiagramBlockAndWritesIt() {
        val dir = createTempDirectory("openlog-seq3-overwrite-confirm").toFile()
        val (state, existingMd, _) = stateWithExistingNote(dir)

        val id = state.beginAndAwaitGenerate("log")
        state.seq3Sessions.confirm(id)
        assertNotNull(state.pendingNoteOverwrite)

        state.confirmNoteOverwrite()

        assertEquals(null, state.pendingNoteOverwrite)
        assertEquals("sample_analysis.md", state.tab("log")?.noteTargetName)
        assertEquals(1, state.tab("log")?.annotations?.blocks?.size, "the diagram note must have landed, exactly once")
        assertTrue(
            (state.tab("log")?.annotations?.blocks?.single() as AnnBlock.Note).text.contains("indagium:diagram3"),
            "the committed block must be the v3 diagram note, not something else",
        )
        assertDefaultDiagramImageExport(existingMd)
    }

    @Test
    fun openExistingNoteInsteadOfOverwriteMergesTheDiagramAfterTheEarlierNote() {
        val dir = createTempDirectory("openlog-seq3-overwrite-open-existing").toFile()
        val (state, existingMd, _) = stateWithExistingNote(dir)

        val id = state.beginAndAwaitGenerate("log")
        state.seq3Sessions.confirm(id)
        assertNotNull(state.pendingNoteOverwrite)

        state.openExistingNoteInsteadOfOverwrite()

        assertEquals(null, state.pendingNoteOverwrite)
        assertEquals("sample_analysis.md", state.tab("log")?.noteTargetName)
        val blocks = state.tab("log")?.annotations?.blocks.orEmpty()
        // "earlier note" (restored from the .ann) first, the diagram note (the deferred add)
        // appended after — merged, not replaced, exactly like the plain-LogRef equivalent.
        assertEquals(2, blocks.size)
        assertEquals("earlier note", (blocks[0] as AnnBlock.Note).text)
        assertTrue((blocks[1] as AnnBlock.Note).text.contains("indagium:diagram3"))
        assertDefaultDiagramImageExport(existingMd)
    }

    @Test
    fun saveNotesToNewNoteFileWritesTheDiagramToASuffixedFile() {
        val dir = createTempDirectory("openlog-seq3-overwrite-save-new").toFile()
        val (state, existingMd, _) = stateWithExistingNote(dir)
        val originalBytes = existingMd.readBytes()

        val id = state.beginAndAwaitGenerate("log")
        state.seq3Sessions.confirm(id)
        assertNotNull(state.pendingNoteOverwrite)

        state.saveNotesToNewNoteFile()

        assertEquals(null, state.pendingNoteOverwrite)
        assertEquals("sample_analysis_2.md", state.tab("log")?.noteTargetName)
        val newFile = File(existingMd.parentFile, "sample_analysis_2.md")
        waitUntil { newFile.exists() }
        assertDefaultDiagramImageExport(newFile)
        // The original file must survive untouched.
        assertTrue(originalBytes.contentEquals(existingMd.readBytes()))
    }

    // ── WP14: confirm() vs. a hand-edited fence ─────────────────────────────────────────────────
    //
    // Same overwrite-prompt shape as the file-conflict tests above — one field (pendingNoteOverwrite),
    // "the tab must show zero new/changed blocks while a prompt is pending" — but a DIFFERENT origin:
    // confirm() found the ALREADY-confirmed note's fence hand-edited since it was generated
    // (Seq3Codec.seq3NoteHasHandEdit) and deferred to PendingNoteOverwrite.handEdit instead of
    // silently re-deriving the fence from the current document. See Seq3Session.confirm's own WP14
    // comment. Deliberately a FRESH temp dir per test (not stateWithExistingNote, which seeds an
    // unrelated pre-existing .md purely to trip the OTHER, file-conflict prompt) — these tests must
    // only ever see the hand-edit prompt, never that one.

    private fun freshState(dir: File): AppState {
        val notesDir = File(dir, "notes").apply { mkdirs() }
        val sourcePath = File(dir, "sample.log").absolutePath
        val state = AppState(
            File(dir, "state.cache"),
            notesDir = notesDir,
            diagramLibraryStore = DiagramLibraryStore(File(dir, "library.cache")),
        )
        state.tabs = listOf(mkTab("log", "sample.log", entries).copy(sourcePath = sourcePath))
        return state
    }

    // Splices an extra line into the fenced body WITHOUT touching the header's declared hash — the
    // same "hand-edited fence" shape Seq3CodecTest's own tamper helper uses, applied here to a REAL
    // generated note (default MERMAID dialect) rather than a hand-built fixture document.
    private fun handEdited(noteText: String): String {
        val tampered = noteText.replaceFirst("sequenceDiagram\n", "sequenceDiagram\n    Note over X: hand-edited\n")
        check(tampered != noteText) { "fixture note text did not contain the expected mermaid header to tamper" }
        return tampered
    }

    // Shared setup for all four tests below: a confirmed (non-drifted) note, then hand-edit it in
    // place — the exact sequence a real drift needs (there is nothing to have drifted FROM before a
    // first confirm ever lands). Returns the block id and the tampered text now sitting on disk/in
    // `tabs`, ready for a SECOND confirm() to trip the gate.
    private fun AppState.confirmThenHandEdit(id: String): Pair<String, String> {
        val blockId = requireNotNull(seq3Sessions.confirm(id)) { "test setup: the first confirm must succeed" }
        val original = (tab("log")!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note).text
        val drifted = handEdited(original)
        updateBlock("log", blockId, drifted)
        return blockId to drifted
    }

    @Test
    fun confirmOnADriftedNoteDefersToTheHandEditPromptInsteadOfWriting() {
        val dir = createTempDirectory("openlog-seq3-handedit-defer").toFile()
        val state = freshState(dir)
        val id = state.beginAndAwaitGenerate("log")
        val (blockId, drifted) = state.confirmThenHandEdit(id)

        val result = state.seq3Sessions.confirm(id)

        assertEquals(null, result, "confirm() must report nothing written while the hand-edit prompt is pending")
        val pending = assertNotNull(state.pendingNoteOverwrite, "expected the hand-edit overwrite prompt to be up")
        assertNotNull(pending.handEdit, "this prompt must be the WP14 hand-edit origin, not the file-conflict one")
        assertEquals(blockId, pending.handEdit.blockId)
        assertEquals(1, state.tab("log")?.annotations?.blocks.orEmpty().size, "no new block may appear while the prompt is pending")
        assertEquals(
            drifted,
            (state.tab("log")!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note).text,
            "the hand-edited text must survive byte for byte until the prompt is resolved",
        )
    }

    @Test
    fun overwritingTheHandEditPromptWritesTheSessionsCurrentDocument() {
        val dir = createTempDirectory("openlog-seq3-handedit-overwrite").toFile()
        val state = freshState(dir)
        val id = state.beginAndAwaitGenerate("log")
        val (blockId, _) = state.confirmThenHandEdit(id)
        state.seq3Sessions.confirm(id)
        assertNotNull(state.pendingNoteOverwrite)

        state.confirmSeq3NoteOverwrite()

        assertEquals(null, state.pendingNoteOverwrite)
        val written = (state.tab("log")!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note).text
        val reparsed = assertNotNull(parseSeq3Note(written), "Overwrite must write a well-formed diagram note")
        assertEquals(
            state.seq3Sessions.sessions.single { it.id == id }.document,
            reparsed.document,
            "Overwrite must write the session's CURRENT model, discarding the hand edit",
        )
        assertTrue(reparsed.sourceHashMatches, "a freshly re-derived fence must match its own declared hash")
    }

    @Test
    fun keepingMyTextOnTheHandEditPromptLeavesTheFenceIntact() {
        val dir = createTempDirectory("openlog-seq3-handedit-keep").toFile()
        val state = freshState(dir)
        val id = state.beginAndAwaitGenerate("log")
        val (blockId, drifted) = state.confirmThenHandEdit(id)
        state.seq3Sessions.confirm(id)
        assertNotNull(state.pendingNoteOverwrite)

        state.keepSeq3NoteText()

        assertEquals(null, state.pendingNoteOverwrite)
        assertEquals(
            drifted,
            (state.tab("log")!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note).text,
            "\"Keep my text\" must leave the hand-edited fence exactly as the user wrote it",
        )
    }

    @Test
    fun cancellingTheHandEditPromptWritesNothing() {
        val dir = createTempDirectory("openlog-seq3-handedit-cancel").toFile()
        val state = freshState(dir)
        val id = state.beginAndAwaitGenerate("log")
        val (blockId, drifted) = state.confirmThenHandEdit(id)
        state.seq3Sessions.confirm(id)
        assertNotNull(state.pendingNoteOverwrite)

        state.cancelNoteOverwrite()

        assertEquals(null, state.pendingNoteOverwrite)
        assertEquals(1, state.tab("log")?.annotations?.blocks.orEmpty().size, "cancel must not add or remove any block")
        assertEquals(
            drifted,
            (state.tab("log")!!.annotations.blocks.single { it.id == blockId } as AnnBlock.Note).text,
            "cancel writes nothing — the drifted text must stay exactly as it was",
        )
    }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    /** IMAGE is the default export mode: the Markdown points at a durable PNG, rather than
     * carrying Mermaid source. Verify both halves of that contract here so a stale overwrite gate
     * cannot accidentally keep asserting the SOURCE behaviour. */
    private fun assertDefaultDiagramImageExport(markdown: File) {
        val framesDir = File(markdown.parentFile, "${markdown.nameWithoutExtension}_frames")
        val png = File(framesDir, "diagram-01.png")
        waitUntil {
            markdown.readText().contains("!diagram-01.png!") && png.isFile && png.length() > 0L
        }
    }
}
