package com.indagium

import com.indagium.diagram3.DiagramExportMode
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Range
import com.indagium.diagram3.parseSeq3Note
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
import com.indagium.ui.MAX_DIAGRAM_LIBRARY_ATTACHMENTS_PER_ITEM
import com.indagium.ui.MAX_DIAGRAM_LIBRARY_DESCRIPTION_CHARS
import com.indagium.ui.MAX_DIAGRAM_LIBRARY_FILE_BYTES
import com.indagium.ui.MAX_DIAGRAM_LIBRARY_FINGERPRINT_CHARS
import com.indagium.ui.MAX_DIAGRAM_LIBRARY_ID_CHARS
import com.indagium.ui.MAX_DIAGRAM_LIBRARY_ITEMS
import com.indagium.ui.MAX_DIAGRAM_LIBRARY_LINE_CHARS
import com.indagium.ui.MAX_DIAGRAM_LIBRARY_SOURCE_PATH_CHARS
import com.indagium.ui.MAX_DIAGRAM_LIBRARY_TITLE_CHARS
import com.indagium.ui.Seq3Session
import com.indagium.ui.mkTab
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * v3 port of the deleted `DiagramLibraryStoreTest`. `ui/DiagramLibraryStore.kt` itself is
 * format-agnostic and unchanged by the v3 cutover (it stores the codec's encoded text verbatim —
 * see docs/SAAD.md §13.7) — only [DiagramLibrarySnapshot]'s wrapper (now `Seq3Document`/
 * `parseSeq3Note`) changed, so the store's own persistence engine (round-trip, search, corruption
 * tolerance, size caps) is real, unchanged coverage that was otherwise about to be lost outright.
 * The last two tests port the `SeqDiagramCoordinator`-specific cases onto `ui.Seq3Session`, which
 * owns the same "offline open" and "export-mode capture on confirm" behaviour for v3 (see
 * `Seq3Session.openLibraryItem`/`confirm`).
 */
class Seq3LibraryStoreTest {
    private val source = DiagramSourceIdentity("/captures/bugreport.log", "4:deadbeef")

    private fun snapshot(title: String) = DiagramLibrarySnapshot.create(
        Seq3Document(title = title, range = Seq3Range.Ids(10, 20), sourceFile = "bugreport.log"),
    )

    @Test
    fun savedDiagramRoundTripsAsItsOriginalCodecArtifact() {
        val file = File(createTempDirectory("seq3-library").toFile(), "diagram-library-v1")
        val store = DiagramLibraryStore(file)
        val saved = store.create("Boot flow", "Saved before reproduction", source, snapshot("Boot flow"), now = 100L)

        val restored = assertNotNull(DiagramLibraryStore(file).get(saved.id))

        assertEquals("Boot flow", restored.title)
        assertEquals(source, restored.source)
        assertEquals(saved.snapshot.encodedDiagramNote, restored.snapshot.encodedDiagramNote)
        assertEquals("Boot flow", restored.parsed?.document?.title)
        assertEquals(Seq3Range.Ids(10, 20), restored.parsed?.document?.range)
    }

    @Test
    fun summariesAreGlobalSearchableAndReflectDraftAttachedAndRecentStates() {
        val file = File(createTempDirectory("seq3-library").toFile(), "diagram-library-v1")
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
        val file = File(createTempDirectory("seq3-library").toFile(), "diagram-library-v1")
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
    fun oversizedLibraryFileIsRejectedBeforeStartupRead() {
        val file = File(createTempDirectory("seq3-library-oversized").toFile(), "diagram-library-v1")
        RandomAccessFile(file, "rw").use { it.setLength(MAX_DIAGRAM_LIBRARY_FILE_BYTES + 1L) }

        assertTrue(DiagramLibraryStore(file).all().isEmpty())
    }

    @Test
    fun oversizedLineIsDiscardedWithoutLosingEarlierValidRecords() {
        val file = File(createTempDirectory("seq3-library-long-line").toFile(), "diagram-library-v1")
        val store = DiagramLibraryStore(file)
        val saved = store.create("Valid", "", source, snapshot("Valid"), now = 100L)
        file.appendText("x".repeat(MAX_DIAGRAM_LIBRARY_LINE_CHARS + 1) + "\n")

        val restored = DiagramLibraryStore(file)

        assertNotNull(restored.get(saved.id))
        assertEquals(1, restored.all().size)
    }

    @Test
    fun loadCapsItemCountAndRejectsOversizedDecodedMetadataToken() {
        val file = File(createTempDirectory("seq3-library-bounded-records").toFile(), "diagram-library-v1")
        val store = DiagramLibraryStore(file)
        store.create("Seed", "", source, snapshot("Seed"), now = 100L)
        val lines = file.readLines()
        val itemFields = lines.single { it.startsWith("item\t") }.split('\t').toMutableList()
        val records = buildList {
            repeat(MAX_DIAGRAM_LIBRARY_ITEMS + 20) { index ->
                add(itemFields.toMutableList().also { it[1] = token("item-$index") }.joinToString("\t"))
            }
            add(
                itemFields.toMutableList().also {
                    it[1] = token("oversized-title")
                    it[2] = token("x".repeat(MAX_DIAGRAM_LIBRARY_TITLE_CHARS + 1))
                }.joinToString("\t"),
            )
        }
        file.writeText((lines.take(2) + records).joinToString("\n", postfix = "\n"))

        val restored = DiagramLibraryStore(file)

        assertEquals(MAX_DIAGRAM_LIBRARY_ITEMS, restored.all().size)
        assertEquals(null, restored.get("oversized-title"))
    }

    @Test
    fun saveRejectsOversizedMetadataAndAttachmentCollections() {
        val file = File(createTempDirectory("seq3-library-save-bounds").toFile(), "diagram-library-v1")
        val store = DiagramLibraryStore(file)
        val valid = store.create("Valid", "", source, snapshot("Valid"), now = 100L)

        assertFailsWith<IllegalArgumentException> {
            store.save(valid.copy(title = "x".repeat(MAX_DIAGRAM_LIBRARY_TITLE_CHARS + 1)))
        }
        assertFailsWith<IllegalArgumentException> {
            store.save(valid.copy(description = "x".repeat(MAX_DIAGRAM_LIBRARY_DESCRIPTION_CHARS + 1)))
        }
        assertFailsWith<IllegalArgumentException> {
            store.save(valid.copy(source = source.copy(sourcePath = "x".repeat(MAX_DIAGRAM_LIBRARY_SOURCE_PATH_CHARS + 1))))
        }
        assertFailsWith<IllegalArgumentException> {
            store.save(valid.copy(source = source.copy(contentFingerprint = "x".repeat(MAX_DIAGRAM_LIBRARY_FINGERPRINT_CHARS + 1))))
        }
        assertFailsWith<IllegalArgumentException> {
            store.save(valid.copy(id = "x".repeat(MAX_DIAGRAM_LIBRARY_ID_CHARS + 1)))
        }
        val attachments = List(MAX_DIAGRAM_LIBRARY_ATTACHMENTS_PER_ITEM + 1) { index ->
            DiagramLibraryAttachment("tab", "block-$index", DiagramAttachmentKind.LINK, index.toLong())
        }
        assertFailsWith<IllegalArgumentException> { store.save(valid.copy(attachments = attachments)) }
        assertEquals(listOf(valid.id), store.all().map { it.id })
    }

    @Test
    fun sessionOpensCachedLibraryDiagramWithoutRequiringALogTab() {
        val dir = createTempDirectory("seq3-library-offline").toFile()
        val store = DiagramLibraryStore(File(dir, "diagram-library-v1"))
        val saved = store.create("Offline", "", source, snapshot("Offline"), now = 100L)
        val state = AppState(autosaveFile = File(dir, "autosave.cache"), restoreOnCreate = false, autoExportNotes = false)
        try {
            // A standalone Seq3Session pointed at the temp-file store — AppState.seq3Sessions
            // itself always uses the real appDataDir() store (Seq3Session's production default).
            val sessions = Seq3Session(state, libraryStore = store)
            // No tabId argument: mirrors opening a saved diagram whose original log isn't open.
            assertTrue(sessions.openLibraryItem(saved.id))
            val session = assertNotNull(sessions.activeSession)
            assertEquals(null, session.sourceTabId, "an offline item must not invent a rebuildable source tab")
            assertEquals("Offline", session.document.title)
            assertEquals(saved.id, session.libraryItemId)
        } finally {
            state.close()
        }
    }

    @Test
    fun confirmCapturesTheDefaultExportModeForANewNoteAndPreservesAnExistingOnesChoice() {
        val dir = createTempDirectory("seq3-export-default").toFile()
        val state = AppState(autosaveFile = File(dir, "autosave.cache"), restoreOnCreate = false, autoExportNotes = false)
        try {
            state.updateSettings { it.copy(diagramDefaultExportMode = DiagramExportMode.SOURCE) }
            state.tabs = listOf(
                mkTab(
                    "log", "sample.log",
                    listOf(
                        LogEntry(1, "10:00:00.000", LogLevel.I, "Client", "request"),
                        LogEntry(2, "10:00:00.010", LogLevel.I, "Server", "response"),
                    ),
                ),
            )

            val id = requireNotNull(state.seq3Sessions.begin("log", setOf(1, 2)))
            waitUntil { state.seq3Sessions.sessions.firstOrNull { it.id == id }?.document?.messages?.isNotEmpty() == true }
            val blockId = assertNotNull(state.seq3Sessions.confirm(id))
            assertEquals(
                DiagramExportMode.SOURCE,
                parseSeq3Note((state.tab("log")!!.annotations.blocks.single() as AnnBlock.Note).text)!!.exportMode,
            )

            // The preference only seeds a NEW note; revisiting this existing SOURCE-mode note
            // while IMAGE is now the default must preserve the note's own already-written choice.
            state.updateSettings { it.copy(diagramDefaultExportMode = DiagramExportMode.IMAGE) }
            val editId = requireNotNull(state.seq3Sessions.beginEdit("log", blockId))
            assertNotNull(state.seq3Sessions.confirm(editId))
            assertEquals(
                DiagramExportMode.SOURCE,
                parseSeq3Note((state.tab("log")!!.annotations.blocks.single() as AnnBlock.Note).text)!!.exportMode,
            )
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

    private fun token(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
}
