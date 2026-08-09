package com.indagium

import com.indagium.diagram.DiagramRange
import com.indagium.diagram.DiagramExportMode
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.parseDiagramNote
import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
import com.indagium.ui.DiagramAttachmentKind
import com.indagium.ui.DiagramLibraryAttachment
import com.indagium.ui.DiagramLibrarySnapshot
import com.indagium.ui.DiagramLibraryStatus
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.DiagramSourceIdentity
import com.indagium.ui.SeqDiagramCoordinator
import com.indagium.ui.mkTab
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiagramLibraryStoreTest {
    private val source = DiagramSourceIdentity("/captures/bugreport.log", "4:deadbeef")

    private fun snapshot(title: String) = DiagramLibrarySnapshot.create(
        SeqDiagramSpec(title = title, range = DiagramRange.Ids(10, 20), sourceFile = "bugreport.log"),
        "sequenceDiagram\n  A->>B: work",
        model = null,
    )

    @Test
    fun savedDiagramRoundTripsAsItsOriginalCodecArtifact() {
        val file = File(createTempDirectory("diagram-library").toFile(), "diagram-library-v1")
        val store = DiagramLibraryStore(file)
        val saved = store.create("Boot flow", "Saved before reproduction", source, snapshot("Boot flow"), now = 100L)

        val restored = assertNotNull(DiagramLibraryStore(file).get(saved.id))

        assertEquals("Boot flow", restored.title)
        assertEquals(source, restored.source)
        assertEquals(saved.snapshot.encodedDiagramNote, restored.snapshot.encodedDiagramNote)
        assertEquals("Boot flow", restored.parsed?.spec?.title)
        assertEquals(DiagramRange.Ids(10, 20), restored.parsed?.spec?.range)
    }

    @Test
    fun summariesAreGlobalSearchableAndReflectDraftAttachedAndRecentStates() {
        val file = File(createTempDirectory("diagram-library").toFile(), "diagram-library-v1")
        val store = DiagramLibraryStore(file)
        val draft = store.create("Bluetooth startup", "adapter handoff", source, snapshot("Bluetooth startup"), now = 100L)
        val attached = store.create("Network retry", "socket", source.copy(contentFingerprint = "5:cafe"), snapshot("Network retry"), now = 200L)

        store.addAttachment(attached.id, DiagramLibraryAttachment("tab-1", "note-1", DiagramAttachmentKind.LINK, 300L))
        store.markOpened(draft.id, now = 400L)

        val bluetooth = store.search("adapter")
        assertEquals(listOf(draft.id), bluetooth.map { it.id })
        assertEquals(DiagramLibraryStatus.DRAFT, bluetooth.single().status)
        assertEquals(DiagramLibraryStatus.ATTACHED, store.search("Network").single().status)
        assertEquals(listOf(draft.id), store.recent().map { it.id })
    }

    @Test
    fun malformedLinesDoNotDiscardOtherLibraryRecords() {
        val file = File(createTempDirectory("diagram-library").toFile(), "diagram-library-v1")
        val store = DiagramLibraryStore(file)
        val first = store.create("First", "", source, snapshot("First"), now = 100L)
        val second = store.create("Second", "", source, snapshot("Second"), now = 200L)
        // A torn/corrupt record is expected after a hand edit or a bad older build. The store
        // must preserve valid records around it instead of treating the entire library as lost.
        file.appendText("item\tnot-valid-base64\nattachment\tbad\n")

        val restored = DiagramLibraryStore(file)

        assertTrue(restored.get(first.id) != null)
        assertTrue(restored.get(second.id) != null)
        assertFalse(restored.all().isEmpty())
    }

    @Test
    fun coordinatorOpensCachedLibraryDiagramWithoutRequiringALogTab() {
        val dir = createTempDirectory("diagram-library").toFile()
        val store = DiagramLibraryStore(File(dir, "diagram-library-v1"))
        val saved = store.create("Offline", "", source, snapshot("Offline"), now = 100L)
        val state = AppState(autosaveFile = File(dir, "autosave.cache"), restoreOnCreate = false, autoExportNotes = false)
        try {
            val coordinator = SeqDiagramCoordinator(state, store)

            assertTrue(coordinator.openLibraryItem(saved.id))
            assertEquals(null, coordinator.request, "an offline item must not invent a rebuildable LogTab request")
            assertEquals(saved.id, coordinator.offlineLibraryRequest?.item?.id)
            assertTrue(coordinator.libraryOpenReadOnly)
        } finally {
            state.close()
        }
    }

    @Test
    fun sourceDefaultIsCapturedByNewOneShotSnapshotAndLinkedNotes() {
        val dir = createTempDirectory("diagram-export-default").toFile()
        val store = DiagramLibraryStore(File(dir, "diagram-library-v1"))
        val state = AppState(autosaveFile = File(dir, "autosave.cache"), restoreOnCreate = false, autoExportNotes = false)
        try {
            state.updateSettings { it.copy(diagramDefaultExportMode = DiagramExportMode.SOURCE) }
            state.tabs = listOf(
                mkTab(
                    "log",
                    "sample.log",
                    listOf(
                        LogEntry(1, "10:00:00.000", LogLevel.I, "Client", "request"),
                        LogEntry(2, "10:00:00.010", LogLevel.I, "Server", "response"),
                    ),
                ),
            )
            val coordinator = SeqDiagramCoordinator(state, store)

            coordinator.begin("log")
            waitUntil { coordinator.preview.diagramOrNull != null }
            val oneShotId = assertNotNull(coordinator.confirm())
            assertEquals(
                DiagramExportMode.SOURCE,
                (state.tab("log")!!.annotations.blocks.single() as AnnBlock.Note)
                    .let { parseDiagramNote(it.text)!!.exportMode },
            )

            // The preference only seeds new notes: revisiting this existing source-mode note
            // while Image is selected must preserve the note's metadata.
            state.updateSettings { it.copy(diagramDefaultExportMode = DiagramExportMode.IMAGE) }
            assertTrue(coordinator.beginEdit("log", oneShotId))
            assertNotNull(coordinator.confirm())
            assertEquals(
                DiagramExportMode.SOURCE,
                ((state.tab("log")!!.annotations.blocks.single() as AnnBlock.Note)
                    .let { parseDiagramNote(it.text)!!.exportMode }),
            )
            state.updateSettings { it.copy(diagramDefaultExportMode = DiagramExportMode.SOURCE) }

            val saved = store.create("Saved", "", source, snapshot("Saved"))
            assertNotNull(coordinator.attachLibrarySnapshot("log", saved.id))
            assertNotNull(coordinator.attachLibraryLink("log", saved.id))
            val exportModes = state.tab("log")!!.annotations.blocks
                .filterIsInstance<AnnBlock.Note>()
                .drop(1)
                .map { parseDiagramNote(it.text)!!.exportMode }
            assertEquals(listOf(DiagramExportMode.SOURCE, DiagramExportMode.SOURCE), exportModes)
        } finally {
            state.close()
        }
    }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), "condition did not become true within ${timeoutMs}ms")
    }
}
