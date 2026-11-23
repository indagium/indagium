package com.indagium

import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogLevel
import com.indagium.model.LogRecordMetadata
import com.indagium.model.LogTab
import com.indagium.model.TabContentState
import com.indagium.ui.AppState
import com.indagium.ui.GoToRowsResult
import com.indagium.ui.createSharedNote
import com.indagium.ui.goToLogRefRows
import com.indagium.ui.goToLogRefRowsAfterReopen
import com.indagium.ui.mkTab
import com.indagium.ui.resolveLogRefRowIds
import com.indagium.ui.setNotesView
import com.indagium.ui.stopSharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GO_TO_TIMEOUT_MS = 30_000L

/**
 * Phase 3 of shared notes, part 2: "Go to rows" from a row reference (a shared note's, or a private note's) to its rows in the
 * source tab: the tab and section are shown, saved ids are checked against the frozen rows and looked up by content when they
 * changed, a closed source gives the reopen offer, and an unloaded source is loaded first.
 */
class SharedNoteGoToRowsTest {
    private fun entry(id: Int, msg: String = "line $id", tag: String = "App", ts: String = "10:00:%02d.000".format(id)) =
        LogEntry(id, ts, LogLevel.I, tag, msg)

    private fun waitUntil(message: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), message)
    }

    private fun <T> go(block: suspend () -> T): T = runBlocking { withTimeout(GO_TO_TIMEOUT_MS) { block() } }

    private fun ref(
        id: String = "r1",
        source: String?,
        ids: List<Int>,
        frozen: List<LogEntry>? = ids.map { entry(it) },
        section: String? = null,
        filename: String? = source?.let { "$it.log" },
    ) = AnnBlock.LogRef(id, ids, "rows", source, filename, frozen, sourceSectionId = section)

    private fun AppState.putIntoNote(noteId: String, block: AnnBlock) =
        upAnn(noteId) { it.copy(annotations = it.annotations.copy(blocks = it.annotations.blocks + block)) }

    private fun twoTabs(dir: File, aRows: List<LogEntry> = (1..6).map { entry(it) }): AppState =
        AppState(File(dir, "state.cache"), autoExportNotes = false, notesDir = File(dir, "notes")).also { s ->
            s.tabs = listOf(
                mkTab("a", "a.log", aRows).copy(sourcePath = File(dir, "a.log").absolutePath),
                mkTab("b", "b.log", (1..3).map { entry(it, "b says $it") }).copy(sourcePath = File(dir, "b.log").absolutePath),
            )
            s.activeTabId = "b"
        }

    // ── Same tab, other tab ──────────────────────────────────────────────────────────────────────

    @Test
    fun aPrivateNotesReferenceSelectsItsRowsInTheViewingTab() {
        val dir = createTempDirectory("goto-same").toFile()
        val app = twoTabs(dir)
        try {
            val block = ref(source = null, ids = listOf(2, 3))

            val result = go { app.goToLogRefRows(block, "a") }

            assertEquals(GoToRowsResult.Navigated("a", listOf(2, 3), missing = 0), result)
            assertEquals("a", app.activeTabId)
            assertEquals(setOf(2, 3), app.tab("a")!!.selected)
            val request = assertNotNull(app.pendingAnnotationNavigation)
            assertEquals("a", request.tabId)
            assertEquals(listOf(2, 3), request.logIds)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aSharedNotesReferenceActivatesItsSourceTabAndSelectsTheRows() {
        val dir = createTempDirectory("goto-other").toFile()
        val app = twoTabs(dir)
        try {
            val host = assertNotNull(app.createSharedNote("S", listOf("a", "b")))
            val block = ref(source = "a", ids = listOf(4, 5))
            app.putIntoNote(host, block)
            assertEquals("b", app.activeTabId)

            val result = go { app.goToLogRefRows(block, "b") }

            assertEquals(GoToRowsResult.Navigated("a", listOf(4, 5)), result)
            assertEquals("a", app.activeTabId)
            assertEquals(setOf(4, 5), app.tab("a")!!.selected)
            assertEquals("a", app.pendingAnnotationNavigation?.tabId)
            assertTrue(app.tab("b")!!.selected.isEmpty())
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aSharedNotesReferenceWithoutASourceTabHasNoSource() {
        val dir = createTempDirectory("goto-nosource").toFile()
        val app = twoTabs(dir)
        try {
            val host = assertNotNull(app.createSharedNote("S", listOf("a", "b")))
            val orphan = ref(source = null, ids = listOf(1))
            app.putIntoNote(host, orphan)

            assertEquals(GoToRowsResult.NoSource, go { app.goToLogRefRows(orphan, "b") })
            // A source that is neither open nor a closed member of any note.
            assertEquals(GoToRowsResult.NoSource, go { app.goToLogRefRows(ref(source = "zz", ids = listOf(1)), "b") })
            assertNull(app.pendingAnnotationNavigation)
            assertTrue(app.tab("a")!!.selected.isEmpty())
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    // ── Row identity ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun idsThatChangedAfterAReparseAreFoundByContent() {
        val dir = createTempDirectory("goto-reparsed").toFile()
        // The note was written when the rows were 10, 11 (and a repeated message at 20 and 30); the tab now numbers them 2, 3.
        val rows = listOf(entry(1, "other")) + listOf(
            entry(2, "wanted one", ts = "11:00:00.000"),
            entry(3, "wanted two", ts = "11:00:01.000"),
        ) + (4..7).map { entry(it, "filler $it") }
        val app = twoTabs(dir, rows)
        try {
            val frozen = listOf(
                entry(10, "wanted one", ts = "11:00:00.000"),
                entry(11, "wanted two", ts = "11:00:01.000"),
            )
            val block = ref(source = "a", ids = listOf(10, 11), frozen = frozen)
            val host = assertNotNull(app.createSharedNote("S", listOf("a", "b")))
            app.putIntoNote(host, block)

            val result = go { app.goToLogRefRows(block, "b") }

            assertEquals(GoToRowsResult.Navigated("a", listOf(2, 3)), result)
            assertEquals(setOf(2, 3), app.tab("a")!!.selected)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun rowsThatAreGoneAreNotFoundAndNothingIsSelected() {
        val dir = createTempDirectory("goto-gone").toFile()
        val app = twoTabs(dir)
        try {
            val block = ref(source = "a", ids = listOf(2, 3), frozen = listOf(entry(2, "vanished"), entry(3, "also vanished")))
            val host = assertNotNull(app.createSharedNote("S", listOf("a", "b")))
            app.putIntoNote(host, block)
            app.setSelectedRows("a", listOf(1))

            assertEquals(GoToRowsResult.RowsNotFound, go { app.goToLogRefRows(block, "b") })

            assertEquals(setOf(1), app.tab("a")!!.selected, "the selection is left as it was")
            assertNull(app.pendingAnnotationNavigation)
            assertEquals("b", app.activeTabId, "the viewer stays where it is")
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun partlyFoundRowsNavigateToWhatWasFound() {
        val dir = createTempDirectory("goto-partial").toFile()
        val app = twoTabs(dir)
        try {
            val block = ref(source = "a", ids = listOf(2, 3), frozen = listOf(entry(2), entry(3, "vanished")))
            val host = assertNotNull(app.createSharedNote("S", listOf("a", "b")))
            app.putIntoNote(host, block)

            val result = go { app.goToLogRefRows(block, "b") }

            assertEquals(GoToRowsResult.Navigated("a", listOf(2), missing = 1), result)
            assertEquals(setOf(2), app.tab("a")!!.selected)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun resolveLogRefRowIdsKeepsMatchingIdsAndPicksTheNearestRowForRepeatedContent() {
        val rows = (1..40).map { entry(it, if (it % 10 == 0) "repeat" else "row $it", ts = if (it % 10 == 0) "09:00:00.000" else "10:00:%02d.000".format(it)) }
        val rmap = rows.associateBy { it.id }
        // Row 1 is unchanged; the second row was id 33 and its content now repeats at 10, 20, 30 and 40; id 99 has nothing.
        val block = ref(
            source = "a", ids = listOf(1, 33, 99),
            frozen = listOf(entry(1, "row 1", ts = "10:00:01.000"), entry(33, "repeat", ts = "09:00:00.000")),
        )

        assertEquals(listOf(1, 30), resolveLogRefRowIds(block, rows, rmap), "99 has no frozen row and no row under it")

        val legacy = AnnBlock.LogRef("l", listOf(2, 500), "legacy")
        assertEquals(listOf(2), resolveLogRefRowIds(legacy, rows, rmap), "without frozen rows the ids that exist are kept")
    }

    @Test
    fun identicalCitationsThatMovedKeepOneRowEach() {
        // Two identical lines were cited at 10 and 11; two lines were inserted before them, so they now sit at 12 and 13.
        val twin = entry(0, "twin", ts = "09:00:00.000")
        val rows = (1..20).map { if (it == 12 || it == 13) twin.copy(id = it) else entry(it, "row $it", ts = "10:00:%02d.000".format(it)) }
        val block = ref(source = "a", ids = listOf(10, 11), frozen = listOf(twin.copy(id = 10), twin.copy(id = 11)))

        assertEquals(listOf(12, 13), resolveLogRefRowIds(block, rows, rows.associateBy { it.id }))
    }

    @Test
    fun aReusedRowIdMustStillMatchTheSavedProcessThreadAndSeverity() {
        val saved = entry(2, "same message").copy(pid = 123, tid = 456)
        val block = ref(source = "a", ids = listOf(saved.id), frozen = listOf(saved))
        for (other in listOf(saved.copy(pid = 321), saved.copy(tid = 654), saved.copy(level = LogLevel.E))) {
            val rows = listOf(other, saved.copy(id = 3))
            assertEquals(listOf(3), resolveLogRefRowIds(block, rows, rows.associateBy { it.id }))
        }
    }

    @Test
    fun shiftedCitationsWithDifferentThreadIdentitiesAreGroupedSeparately() {
        val first = entry(2, "same message").copy(pid = 123, tid = 456)
        val second = first.copy(id = 3, tid = 654)
        val block = ref(source = "a", ids = listOf(first.id, second.id), frozen = listOf(first, second))
        val rows = listOf(first.copy(id = 5), second.copy(id = 6))

        assertEquals(listOf(5, 6), resolveLogRefRowIds(block, rows, rows.associateBy { it.id }))
    }

    @Test
    fun aRemappedCitationNeverTakesARowAnotherCitationIsAlreadyMatchedOn() {
        val twin = entry(0, "twin", ts = "09:00:00.000")
        // Id 5 still holds the twin; the citation that was at 6 is lost, and the closest twin to it is the reserved row 5.
        val rows = (1..12).map { if (it == 5 || it == 9) twin.copy(id = it) else entry(it, "row $it", ts = "10:00:%02d.000".format(it)) }
        val block = ref(source = "a", ids = listOf(5, 6), frozen = listOf(twin.copy(id = 5), twin.copy(id = 6)))

        assertEquals(listOf(5, 9), resolveLogRefRowIds(block, rows, rows.associateBy { it.id }))
    }

    @Test
    fun moreIdenticalCitationsThanRowsTakeEveryRowOnceAndKeepTheirOrder() {
        val twin = entry(0, "twin", ts = "09:00:00.000")
        val rows = (1..12).map { if (it == 3 || it == 11) twin.copy(id = it) else entry(it, "row $it", ts = "10:00:%02d.000".format(it)) }
        val block = ref(source = "a", ids = listOf(6, 7, 8), frozen = listOf(6, 7, 8).map { twin.copy(id = it) })

        assertEquals(listOf(3, 11), resolveLogRefRowIds(block, rows, rows.associateBy { it.id }))

        // With room for an ordered choice, the second citation takes a row after the first one's, not the nearer row before it.
        val spread = (1..30).map { if (it in setOf(5, 8, 30)) twin.copy(id = it) else entry(it, "row $it", ts = "10:00:%02d.000".format(it)) }
        val two = ref(source = "a", ids = listOf(9, 10), frozen = listOf(9, 10).map { twin.copy(id = it) })
        assertEquals(listOf(8, 30), resolveLogRefRowIds(two, spread, spread.associateBy { it.id }))
    }

    @Test
    fun structuredRowsWithIdenticalSummariesAreMatchedByTheirOriginalJson() {
        val saved = entry(1, "packet").copy(recordMetadata = LogRecordMetadata(rawRecord = """{"payload":17}"""))
        val current = listOf(saved.copy(recordMetadata = LogRecordMetadata(rawRecord = """{"payload":21}""")), saved.copy(id = 2))
        val block = ref(source = "a", ids = listOf(1), frozen = listOf(saved))

        assertEquals(listOf(2), resolveLogRefRowIds(block, current, current.associateBy { it.id }))
        assertEquals(emptyList(), resolveLogRefRowIds(block, current.take(1), mapOf(1 to current.first())))
    }

    // ── Sections ─────────────────────────────────────────────────────────────────────────────────

    private fun metadataJson(dir: File): File = File(dir, "capture.json").apply {
        fun packet(identity: String, title: String) =
            """{"metadata":{"identity":"$identity","source":"Demo","system_time":1790000000000000000},"Summary":{"Note":"$title"}}"""
        writeText(
            """{"Tethered Bluetooth":{"HCI":[""" + packet("Bluetooth.HCI.Note", "HCI note") + "," + packet("Bluetooth.HCI.Note", "HCI note 2") +
                "],\"L2CAP\":[" + packet("Bluetooth.L2CAP", "L2CAP frame") + "," + packet("Bluetooth.L2CAP", "L2CAP frame 2") + "]}}",
        )
    }

    private fun openJson(app: AppState, file: File): String {
        val id = assertNotNull(app.openFile(file))
        waitUntil("the file loads in its first section") {
            app.tab(id)?.let { it.contentState == TabContentState.LOADED && it.selectedLogSectionId == "Tethered Bluetooth / HCI" } == true
        }
        return id
    }

    @Test
    fun aReferenceInAnotherSectionSwitchesToItWaitsForTheSwitchAndThenSelects() {
        val dir = createTempDirectory("goto-section").toFile()
        val app = AppState(File(dir, "state.cache"), autoExportNotes = false, notesDir = File(dir, "notes"))
        try {
            app.tabs = listOf(mkTab("log", "app.log", (1..3).map { entry(it) }))
            val json = openJson(app, metadataJson(dir))
            val l2capId = "Tethered Bluetooth / L2CAP"
            // The L2CAP rows as they parse: freeze them the way "Add annotation" would have.
            assertTrue(go { app.selectLogSectionAndAwait(json, l2capId) })
            val l2cap = app.tab(json)!!.logData
            assertEquals(2, l2cap.size)
            val block = ref(source = json, ids = l2cap.map { it.id }, frozen = l2cap, section = l2capId, filename = "capture.json")
            val host = assertNotNull(app.createSharedNote("S", listOf("log", json)))
            app.putIntoNote(host, block)
            assertTrue(go { app.selectLogSectionAndAwait(json, "Tethered Bluetooth / HCI") })
            assertEquals("HCI note", app.tab(json)!!.logData.first().msg)
            app.activateTab("log")

            // Hold the section load on the IO thread: the navigation must be waiting for it, not racing ahead.
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            app.onLogSectionLoadStart = { _, _ ->
                started.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
            val outcome = runBlocking {
                withTimeout(GO_TO_TIMEOUT_MS) {
                    val pending = async(Dispatchers.Default) { app.goToLogRefRows(block, "log") }
                    assertTrue(started.await(10, TimeUnit.SECONDS), "the section switch started")
                    delay(200)
                    assertFalse(pending.isCompleted, "Go to rows waits for the switch")
                    assertEquals("Tethered Bluetooth / HCI", app.tab(json)!!.selectedLogSectionId, "the old section is still shown")
                    assertNull(app.pendingAnnotationNavigation)
                    release.countDown()
                    pending.await()
                }
            }

            assertEquals(GoToRowsResult.Navigated(json, l2cap.map { it.id }), outcome)
            val shown = app.tab(json)!!
            assertEquals(l2capId, shown.selectedLogSectionId)
            assertEquals("L2CAP frame", shown.logData.first().msg)
            assertEquals(l2cap.map { it.id }.toSet(), shown.selected)
            assertEquals(json, app.activeTabId)
            assertEquals(json, app.pendingAnnotationNavigation?.tabId)
        } finally {
            app.onLogSectionLoadStart = null
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aSectionThatNoLongerExistsLeavesTheRowsToBeLookedUpInTheShownOne() {
        val dir = createTempDirectory("goto-section-gone").toFile()
        val app = AppState(File(dir, "state.cache"), autoExportNotes = false, notesDir = File(dir, "notes"))
        try {
            app.tabs = listOf(mkTab("log", "app.log", (1..3).map { entry(it) }))
            val json = openJson(app, metadataJson(dir))
            val shownRows = app.tab(json)!!.logData
            val block = ref(source = json, ids = shownRows.map { it.id }, frozen = shownRows, section = "Tethered Bluetooth / Gone", filename = "capture.json")
            val host = assertNotNull(app.createSharedNote("S", listOf("log", json)))
            app.putIntoNote(host, block)

            val result = go { app.goToLogRefRows(block, "log") }

            assertIs<GoToRowsResult.Navigated>(result, "the rows are still where they were; the missing section cannot be selected")
            assertEquals("Tethered Bluetooth / HCI", app.tab(json)!!.selectedLogSectionId)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    // ── Closed sources and unloaded tabs ─────────────────────────────────────────────────────────

    private fun logcatFile(dir: File, name: String) = File(dir, name).apply {
        writeText("06-26 10:00:00.000  123  456 I App: hello from $name\n06-26 10:00:01.000  123  456 I App: second from $name\n")
    }

    @Test
    fun aClosedMembersReferenceOffersToReopenAndThenGoesToTheRows() {
        val dir = createTempDirectory("goto-reopen").toFile()
        val app = AppState(File(dir, "state.cache"), autoExportNotes = false, notesDir = File(dir, "notes"))
        try {
            val file = logcatFile(dir, "a.log")
            app.tabs = listOf(
                mkTab("a", "a.log", listOf(entry(1, "hello from a.log", ts = "10:00:00.000"), entry(2, "second from a.log", ts = "10:00:01.000")))
                    .copy(sourcePath = file.absolutePath),
                mkTab("b", "b.log", (1..3).map { entry(it, "b says $it") }).copy(sourcePath = File(dir, "b.log").absolutePath),
            )
            app.activeTabId = "b"
            val host = assertNotNull(app.createSharedNote("S", listOf("a", "b")))
            val block = ref(source = "a", ids = listOf(1, 2), frozen = app.tab("a")!!.logData)
            app.putIntoNote(host, block)
            app.closeTab("a")
            assertNull(app.tab("a"))

            val offer = go { app.goToLogRefRows(block, "b") }

            assertEquals(GoToRowsResult.NeedsReopen(host, "a"), offer)
            assertNull(app.tab("a"), "nothing is opened without the caller's say")
            assertNull(app.pendingAnnotationNavigation)

            val result = go { app.goToLogRefRowsAfterReopen(host, "a", block, "b") }

            val navigated = assertIs<GoToRowsResult.Navigated>(result)
            val reopened = assertNotNull(app.tab(navigated.tabId))
            assertEquals(file.absolutePath, reopened.sourcePath)
            assertEquals(listOf(1, 2), navigated.rowIds)
            assertEquals(setOf(1, 2), reopened.selected)
            assertEquals(reopened.id, app.activeTabId)
            assertEquals(reopened.id, (app.sharedNoteHost(host)!!.annotations.blocks.single() as AnnBlock.LogRef).sourceTabId)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun reopeningAFileThatIsGoneIsNoSource() {
        val dir = createTempDirectory("goto-reopen-missing").toFile()
        val app = AppState(File(dir, "state.cache"), autoExportNotes = false, notesDir = File(dir, "notes"))
        try {
            app.tabs = listOf(
                mkTab("a", "a.log", listOf(entry(1))).copy(sourcePath = File(dir, "a.log").absolutePath),
                mkTab("b", "b.log", listOf(entry(1))),
            )
            val host = assertNotNull(app.createSharedNote("S", listOf("a", "b")))
            val block = ref(source = "a", ids = listOf(1))
            app.putIntoNote(host, block)
            app.closeTab("a")

            assertEquals(GoToRowsResult.NoSource, go { app.goToLogRefRowsAfterReopen(host, "a", block, "b") })
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aNonMemberCompareSourceReopensWithItsProfileAndRemainsOutsideTheNote() {
        val dir = createTempDirectory("goto-compare-source").toFile()
        val app = twoTabs(dir)
        try {
            val file = File(dir, "source.log").apply { writeText("Worker: request timed out\n") }
            val profile = LogFormatProfile(
                id = "compare-source-profile",
                name = "Compare source profile",
                engine = LogFormatEngine.REGEX,
                regexPattern = "^(\\w+): (.*)$",
                fieldMappings = mapOf(LogFieldRole.COMPONENT to "1", LogFieldRole.MESSAGE to "2"),
            )
            val sourceId = assertNotNull(app.openFileWithLogFormatProfile(file, profile))
            waitUntil("the source loads") { app.tab(sourceId)?.contentState == TabContentState.LOADED }
            val originalSnapshot = assertNotNull(app.tab(sourceId)?.logFormatProfileSnapshot)
            app.activeTabId = "a"
            val host = assertNotNull(app.createSharedNote("Compare review", listOf("a")))
            app.compareTabId = sourceId
            app.compareMode = true
            app.requestAddAnn(sourceId, listOf(1))
            val request = assertNotNull(app.addAnnRequest)
            assertEquals(host, request.targetTabId)
            app.confirmAddAnn(request, "Compare excerpt", AnnBlock.LogRefPresentationOptions())
            val block = assertNotNull(app.sharedNoteHost(host)).annotations.blocks.single() as AnnBlock.LogRef

            app.closeTab(sourceId)
            val closed = assertNotNull(app.sharedNoteHost(host)?.sharedNote)
            assertTrue(closed.closedMembers.isEmpty())
            assertEquals(originalSnapshot, closed.closedReferenceSources.single().logFormatProfileSnapshot)
            assertEquals(GoToRowsResult.NeedsReopen(host, sourceId), go { app.goToLogRefRows(block, "a") })

            // An open tab on a different profile for the same file cannot satisfy the recorded source.
            val other = assertNotNull(app.openFileWithLogFormatProfile(file, "plain-text"))
            waitUntil("the alternate view loads") { app.tab(other)?.contentState == TabContentState.LOADED }
            assertEquals(GoToRowsResult.NeedsReopen(host, sourceId), go { app.goToLogRefRows(block, "a") })

            val navigated = assertIs<GoToRowsResult.Navigated>(go { app.goToLogRefRowsAfterReopen(host, sourceId, block, "a") })
            val reopened = assertNotNull(app.tab(navigated.tabId))
            assertEquals(originalSnapshot, reopened.logFormatProfileSnapshot)
            assertEquals(listOf(1), navigated.rowIds)
            assertEquals(setOf(1), reopened.selected)
            assertNull(reopened.notesView)
            val updated = assertNotNull(app.sharedNoteHost(host))
            assertEquals(listOf("a"), updated.sharedNote?.memberTabIds)
            assertTrue(updated.sharedNote?.closedReferenceSources.orEmpty().isEmpty())
            assertEquals(reopened.id, (updated.annotations.blocks.single() as AnnBlock.LogRef).sourceTabId)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aTransferredNoteReloadsItsCitedFilesFromTheSidecar() {
        val dir = createTempDirectory("goto-transferred-sidecar").toFile()
        val notes = File(dir, "notes")
        val app = AppState(File(dir, "state.cache"), restoreOnCreate = false, autoExportNotes = true, notesDir = notes)
        try {
            app.updateSettings { it.copy(autoExportNotes = true) }
            val source = File(dir, "source.log").apply { writeText("Worker: request timed out\n") }
            val recipient = File(dir, "recipient.log").apply { writeText("10:00:00.000 I/Tag: recipient row\n") }
            val profile = LogFormatProfile(
                id = "citation-source-profile",
                name = "Citation source profile",
                engine = LogFormatEngine.REGEX,
                regexPattern = "^(\\w+): (.*)$",
                fieldMappings = mapOf(LogFieldRole.COMPONENT to "1", LogFieldRole.MESSAGE to "2"),
            )
            val sourceId = assertNotNull(app.openFileWithLogFormatProfile(source, profile))
            val recipientId = assertNotNull(app.openFile(recipient))
            waitUntil("both logs have loaded") { listOf(sourceId, recipientId).all { app.tab(it)?.contentState == TabContentState.LOADED } }
            val sourceTab = assertNotNull(app.tab(sourceId))
            val originalProfile = assertNotNull(sourceTab.logFormatProfileSnapshot)
            val host = assertNotNull(app.createSharedNote("Investigation", listOf(sourceId, recipientId)))
            val block = ref(source = sourceId, ids = listOf(1), frozen = sourceTab.logData.toList(), filename = source.name)
            app.putIntoNote(host, block)
            assertTrue(app.stopSharing(host, recipientId))
            val sidecar = File(notes, "recipient_analysis.ann")
            waitUntil("the transferred note is exported") { sidecar.isFile && sidecar.length() > 0 }
            app.closeTab(sourceId)
            app.closeTab(recipientId)

            val restoredId = assertNotNull(app.openFile(recipient))
            waitUntil("the note autoloads") { app.tab(restoredId)?.annotations?.blocks?.isNotEmpty() == true }
            val restored = assertIs<AnnBlock.LogRef>(app.tab(restoredId)!!.annotations.blocks.single())
            assertEquals(GoToRowsResult.NeedsReopen(restoredId, sourceId), go { app.goToLogRefRows(restored, restoredId) })
            val navigated = assertIs<GoToRowsResult.Navigated>(go { app.goToLogRefRowsAfterReopen(restoredId, sourceId, restored, restoredId) })
            assertEquals(originalProfile, app.tab(navigated.tabId)?.logFormatProfileSnapshot)
            assertEquals(listOf(1), navigated.rowIds)

            app.closeTab(restoredId)
            val againId = assertNotNull(app.openFile(recipient))
            waitUntil("the note autoloads with its source already open") { app.tab(againId)?.annotations?.blocks?.isNotEmpty() == true }
            val rebound = assertIs<AnnBlock.LogRef>(app.tab(againId)!!.annotations.blocks.single())
            assertEquals(navigated.tabId, rebound.sourceTabId)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun anUnloadedSourceTabIsLoadedBeforeTheRowsAreSelected() {
        val dir = createTempDirectory("goto-unloaded").toFile()
        var now = 0L
        val app = AppState(autosaveFile = File(dir, "state.cache"), autoExportNotes = false, monotonicNowNanos = { now })
        try {
            val file = File(dir, "hidden.log").apply {
                writeText((1..4).joinToString("") { "10:00:00.000 I/Tag: row $it\n" })
            }
            val rows = (1..4).map { LogEntry(it, "10:00:00.000", LogLevel.I, "Tag", "row $it") }
            app.tabs = listOf(
                mkTab("hidden", "hidden.log", rows).copy(sourcePath = file.absolutePath),
                mkTab("visible", "visible.log", rows.take(1)),
            )
            app.settings = app.settings.copy(inactiveTabUnloadMinutes = 30)
            val host = assertNotNull(app.createSharedNote("S", listOf("hidden", "visible")))
            val block = ref(source = "hidden", ids = listOf(2, 3), frozen = rows.subList(1, 3))
            app.putIntoNote(host, block)
            app.activateTab("hidden")
            app.activateTab("visible")
            app.sweepInactiveTabs()
            now = TimeUnit.MINUTES.toNanos(31)
            waitUntil("the hidden log unloads") {
                app.sweepInactiveTabs()
                app.tab("hidden")?.contentState == TabContentState.UNLOADED
            }

            val result = go { app.goToLogRefRows(block, "visible") }

            assertEquals(GoToRowsResult.Navigated("hidden", listOf(2, 3)), result)
            val loaded = app.tab("hidden")!!
            assertEquals(TabContentState.LOADED, loaded.contentState)
            assertEquals(setOf(2, 3), loaded.selected)
            assertEquals("hidden", app.activeTabId)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aTabThatViewsTheSharedNoteIsTheViewerNotTheSource() {
        val dir = createTempDirectory("goto-viewer").toFile()
        val app = twoTabs(dir)
        try {
            val host = assertNotNull(app.createSharedNote("S", listOf("a")))
            app.setNotesView("b", host)
            // b views the shared note; a reference without a source tab must not be taken to name b.
            assertEquals(GoToRowsResult.NoSource, go { app.goToLogRefRows(ref(source = null, ids = listOf(1)), "b") })
            // While a tab that shows its own note owns a reference without a source.
            app.setNotesView("b", null)
            assertIs<GoToRowsResult.Navigated>(go { app.goToLogRefRows(ref(source = null, ids = listOf(1), frozen = listOf(entry(1, "b says 1"))), "b") })
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    // ── Rows that change between resolving and applying ──────────────────────────────────────────

    @Test
    fun aSourceThatChangesAfterTheIdsWereResolvedIsResolvedAgainAndThenNavigatedOnce() {
        val dir = createTempDirectory("goto-race-retry").toFile()
        val state = twoTabs(dir)
        try {
            val block = ref(source = "a", ids = listOf(2, 3))
            var swaps = 0
            // The rows are read again once, as a reload does (equal rows, new objects): the first resolve no longer applies.
            val result = go {
                state.goToLogRefRows(block, "b") {
                    if (swaps++ == 0) state.upTab("a") { it.reread() }
                }
            }

            assertEquals(GoToRowsResult.Navigated("a", listOf(2, 3)), result)
            assertEquals(2, swaps, "the second attempt resolved against the new rows")
            assertEquals(setOf(2, 3), state.tab("a")!!.selected.toSet())
        } finally {
            state.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun rowsATailAppendsMeanwhileDoNotCostTheNavigation() {
        val dir = createTempDirectory("goto-race-tail").toFile()
        val state = twoTabs(dir)
        try {
            val block = ref(source = "a", ids = listOf(2, 3))
            var resolves = 0
            // A live tail or capture publishes a new list every batch but keeps the rows it already had.
            val result = go {
                state.goToLogRefRows(block, "b") {
                    resolves++
                    state.upTab("a") { t ->
                        val rows = t.logData + entry(t.logData.size + 1)
                        t.copy(logData = rows, rmap = rows.associateBy { it.id })
                    }
                }
            }

            assertEquals(GoToRowsResult.Navigated("a", listOf(2, 3)), result)
            assertEquals(1, resolves, "an append is not a change of the rows the ids name")
            assertEquals(setOf(2, 3), state.tab("a")!!.selected.toSet())
        } finally {
            state.close()
            dir.deleteRecursively()
        }
    }

    // A reload: the same rows read again into new objects.
    private fun LogTab.reread(): LogTab {
        val rows = logData.map { it.copy() }
        return copy(logData = rows, rmap = rows.associateBy { it.id })
    }

    @Test
    fun aSourceThatKeepsChangingOrSwitchesSectionNeverReportsASelectionItDidNotMake() {
        val dir = createTempDirectory("goto-race-gone").toFile()
        val state = twoTabs(dir)
        try {
            val block = ref(source = "a", ids = listOf(2, 3))

            val churning = go {
                state.goToLogRefRows(block, "b") { state.upTab("a") { it.reread() } }
            }
            assertEquals(GoToRowsResult.RowsNotFound, churning)
            assertTrue(state.tab("a")!!.selected.isEmpty(), "nothing was selected")

            // The rows are dropped altogether (an unload): the old code still answered Navigated with nothing selected.
            val dropped = go {
                state.goToLogRefRows(block, "b") { state.upTab("a") { it.copy(logData = emptyList(), rmap = emptyMap()) } }
            }
            assertEquals(GoToRowsResult.RowsNotFound, dropped)
            assertTrue(state.tab("a")!!.selected.isEmpty())
            state.upTab("a") { it.copy(logData = (1..6).map { id -> entry(id) }, rmap = (1..6).associateWith { id -> entry(id) }) }

            // Another section is shown by the time the rows are applied: they belong to the section that was resolved.
            var switches = 0
            val switched = go {
                state.goToLogRefRows(block, "b") { state.upTab("a") { it.copy(selectedLogSectionId = "other-${switches++}") } }
            }
            assertEquals(GoToRowsResult.RowsNotFound, switched)
            assertTrue(state.tab("a")!!.selected.isEmpty())
            assertEquals("b", state.activeTabId, "the source tab was not activated either")
        } finally {
            state.close()
            dir.deleteRecursively()
        }
    }
}
