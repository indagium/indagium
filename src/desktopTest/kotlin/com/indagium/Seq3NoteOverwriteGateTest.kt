package com.indagium

import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
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
        val state = AppState(File(dir, "state.cache"), notesDir = notesDir)
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
