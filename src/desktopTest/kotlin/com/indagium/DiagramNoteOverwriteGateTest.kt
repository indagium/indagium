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
 * The note-overwrite-prompt fix (§1/§5 of the "notes commit before the overwrite prompt is
 * answered" fix), exercised through the sequence-diagram "Add to notes" flow rather than the plain
 * addNoteBlock/confirmAddAnn paths AppStateBehaviorTest's NoteOverwrite group covers — a diagram
 * note is still just an ordinary [AnnBlock.Note] by the time it reaches upAnn (see
 * SeqDiagramCoordinator.confirm()), but it arrives through a different call path
 * (AppState.seqDiagrams.confirm(), not addNoteBlock/confirmAddAnn directly), and that path used to
 * be the one where this bug was first noticed and reported.
 */
class DiagramNoteOverwriteGateTest {

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

    // Builds the preview and returns once it's ready to confirm — every test below starts from
    // here, since begin()'s build runs on SeqDiagramCoordinator's own background scope.
    private fun AppState.beginAndAwaitPreview(tabId: String) {
        seqDiagrams.begin(tabId)
        waitUntil { seqDiagrams.preview.diagramOrNull != null }
    }

    @Test
    fun addingADiagramNoteIsNotObservableInTabsWhileTheOverwritePromptIsPending() {
        val dir = createTempDirectory("openlog-diagram-overwrite-invisible").toFile()
        val (state, existingMd, _) = stateWithExistingNote(dir)
        val originalBytes = existingMd.readBytes()

        state.beginAndAwaitPreview("log")
        val blockId = state.seqDiagrams.confirm()

        // Deferred, not failed: the membership-check contract (SeqDiagramCoordinator.confirm()'s
        // own comment) means a null return here is expected and benign, not an error.
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
        val dir = createTempDirectory("openlog-diagram-overwrite-confirm").toFile()
        val (state, existingMd, _) = stateWithExistingNote(dir)

        state.beginAndAwaitPreview("log")
        state.seqDiagrams.confirm()
        assertNotNull(state.pendingNoteOverwrite)

        state.confirmNoteOverwrite()

        assertEquals(null, state.pendingNoteOverwrite)
        assertEquals("sample_analysis.md", state.tab("log")?.noteTargetName)
        assertEquals(1, state.tab("log")?.annotations?.blocks?.size, "the diagram note must have landed, exactly once")
        assertTrue(
            (state.tab("log")?.annotations?.blocks?.single() as AnnBlock.Note).text.contains("indagium:diagram"),
            "the committed block must be the diagram note, not something else",
        )
        // "sequenceDiagram", not the literal word "mermaid" — buildMd's appendDiagramNote renders
        // the exported .md differently depending on settings.annotationLogBlockStyle (default
        // JIRA_JAVA wraps the source in a "{code}" block with a "!diagram-0N.png!" anchor, no
        // "```mermaid" fence at all; only INDENTED keeps that literal fence). "sequenceDiagram" is
        // the Mermaid dialect's own header keyword, present in diagram.source under EITHER style.
        waitUntil { existingMd.readText().contains("sequenceDiagram") }
    }

    @Test
    fun openExistingNoteInsteadOfOverwriteMergesTheDiagramAfterTheEarlierNote() {
        val dir = createTempDirectory("openlog-diagram-overwrite-open-existing").toFile()
        val (state, existingMd, _) = stateWithExistingNote(dir)

        state.beginAndAwaitPreview("log")
        state.seqDiagrams.confirm()
        assertNotNull(state.pendingNoteOverwrite)

        state.openExistingNoteInsteadOfOverwrite()

        assertEquals(null, state.pendingNoteOverwrite)
        assertEquals("sample_analysis.md", state.tab("log")?.noteTargetName)
        val blocks = state.tab("log")?.annotations?.blocks.orEmpty()
        // "earlier note" (restored from the .ann) first, the diagram note (the deferred add)
        // appended after — merged, not replaced, exactly like the plain-LogRef equivalent.
        assertEquals(2, blocks.size)
        assertEquals("earlier note", (blocks[0] as AnnBlock.Note).text)
        assertTrue((blocks[1] as AnnBlock.Note).text.contains("indagium:diagram"))
        waitUntil { existingMd.readText().contains("sequenceDiagram") }
    }

    @Test
    fun saveNotesToNewNoteFileWritesTheDiagramToASuffixedFile() {
        val dir = createTempDirectory("openlog-diagram-overwrite-save-new").toFile()
        val (state, existingMd, _) = stateWithExistingNote(dir)
        val originalBytes = existingMd.readBytes()

        state.beginAndAwaitPreview("log")
        state.seqDiagrams.confirm()
        assertNotNull(state.pendingNoteOverwrite)

        state.saveNotesToNewNoteFile()

        assertEquals(null, state.pendingNoteOverwrite)
        assertEquals("sample_analysis_2.md", state.tab("log")?.noteTargetName)
        val newFile = File(existingMd.parentFile, "sample_analysis_2.md")
        waitUntil { newFile.exists() && newFile.readText().contains("sequenceDiagram") }
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
}
