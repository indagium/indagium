@file:Suppress("MagicNumber")
@file:OptIn(ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogRecordMetadata
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val RECORD_ONE = """{"timestamp":"2026-11-16T10:00:00Z",""" +
    """"metadata":{"bluetooth":{"accessory_address":"AA:BB:CC:DD","rssi":-61},"tags":["a","b"]},"id":7}"""
private const val RECORD_TWO = """{"timestamp":"2026-11-16T10:00:03Z",""" +
    """"metadata":{"bluetooth":{"accessory_address":"11:22:33:44","rssi":-70}},"id":8}"""

internal const val RECORD_BLOB = """{"id":7,"blob":"00 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff 01 02",""" +
    """"note":"first line of text\nsecond line of text\nthird line"}"""

private val ADDRESS = listOf("metadata", "bluetooth", "accessory_address")
private val RSSI = listOf("metadata", "bluetooth", "rssi")

/** A click on the node with [modifier] held: Ctrl, or Cmd on a Mac. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeContentTestRule.ctrlClickNode(tag: String, modifier: Key) {
    onNodeWithTag(tag).performKeyInput { keyDown(modifier) }
    onNodeWithTag(tag).performMouseInput { click(center) }
    onNodeWithTag(tag).performKeyInput { keyUp(modifier) }
    waitForIdle()
}

/** The tag of a field node of a tree rooted at [root] (StructuredLogRecord.kt's pathTag of "root/segment/segment"). */
internal fun nodeTag(root: String, vararg path: String, toggle: Boolean = false): String {
    val tag = (listOf(root) + path).joinToString("/").map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("").replace(Regex("-+"), "-").trim('-')
    return (if (toggle) "structured-record-node-toggle-" else "structured-record-node-") + tag
}

/**
 * Fields of a JSON record in notes and from the field tree: a whole-record reference shows the tree, a fields reference shows the chosen
 * fields under their paths, Ctrl/Cmd-click picks fields, the right-click menu adds them to the tab's note (or the shared note it shows),
 * and Go to rows opens the record at them.
 */
@OptIn(ExperimentalTestApi::class)
class RecordFieldsUiTest : SharedNoteUiTestBase() {
    private fun jsonRow(id: Int, json: String) = LogEntry(
        id, "10:00:%02d.000".format(id), LogLevel.I, "HCI", "message $id",
        recordMetadata = LogRecordMetadata(rawRecord = json, usesProfilePresentation = true),
    )

    /** The base log view with tab a's rows replaced by two JSON records (b and c keep their plain rows). */
    private fun setupJson(width: Int = 1100, height: Int = 760, configure: (AppState) -> Unit = {}) = setup(width, height) { s ->
        s.tabs = s.tabs.map { tab ->
            if (tab.id == "a") mkTab("a", "a.log", listOf(jsonRow(1, RECORD_ONE), jsonRow(2, RECORD_TWO))).copy(sourcePath = tab.sourcePath) else tab
        }
        configure(s)
    }

    private fun AppState.addWholeRecord(tabId: String = "a", id: Int = 1): String {
        requestAddAnn(tabId, listOf(id))
        val request = assertNotNull(addAnnRequest)
        confirmAddAnn(request, "Pairing record", request.capture?.presentation?.options ?: AnnBlock.LogRefPresentationOptions())
        return assertNotNull(noteTab(notesTargetFor(tabId))).annotations.blocks.last().id
    }

    private fun AppState.addFields(tabId: String, id: Int, vararg paths: List<String>): String {
        requestAddRecordFields(tabId, id, paths.toList())
        val request = assertNotNull(addAnnRequest)
        confirmAddAnn(request, "Accessory", request.capture?.presentation?.options ?: AnnBlock.LogRefPresentationOptions())
        return assertNotNull(noteTab(notesTargetFor(tabId))).annotations.blocks.last().id
    }

    private fun selectRow(id: Int) {
        rule.onNodeWithTag("log-row-a:main-$id").performClick()
        rule.waitForIdle()
    }

    private fun ctrlClick(tag: String) = rule.ctrlClickNode(tag, Key.CtrlLeft)

    private fun rightClickNode(tag: String) {
        rule.onNodeWithTag(tag).performMouseInput { rightClick(center) }
        rule.waitForIdle()
    }

    private fun detailsNode(vararg path: String) = nodeTag("1.details", *path)

    // ── A whole-record reference keeps its structure ─────────────────────────────────────────────

    @Test
    fun aWholeRecordReferenceShowsTheFieldTreeTopLevelOpenAndNestedFolded() {
        setupJson()
        val id = state.addWholeRecord()
        rule.waitForIdle()
        val root = "1.n$id"

        assertTrue(exists(logRefRecordTag(id, 1)), "the record's tree sits under its row")
        rule.onNodeWithTag(nodeTag(root, "timestamp")).assertTextContains("2026-11-16", substring = true)
        rule.onNodeWithTag(nodeTag(root, "metadata")).assertTextContains("{2 fields}", substring = true)
        // The first level of containers is open, what they hold is folded.
        rule.onNodeWithTag(nodeTag(root, "metadata", "bluetooth")).assertTextContains("{2 fields}", substring = true)
        rule.onNodeWithTag(nodeTag(root, "metadata", "tags")).assertTextContains("[2 items]", substring = true)
        rule.onNodeWithTag(nodeTag(root, "metadata", "bluetooth", "accessory_address")).assertDoesNotExist()

        rule.onNodeWithTag(nodeTag(root, "metadata", "bluetooth", toggle = true)).performClick()
        rule.onNodeWithTag(nodeTag(root, "metadata", "bluetooth", "accessory_address")).assertTextContains("AA:BB:CC:DD", substring = true)
        // Double-click closes it again, as in the log view.
        rule.onNodeWithTag(nodeTag(root, "metadata", "bluetooth")).performMouseInput { doubleClick() }
        rule.onNodeWithTag(nodeTag(root, "metadata", "bluetooth", "accessory_address")).assertDoesNotExist()
    }

    @Test
    fun aReferenceToAPlainRowShowsNoTree() {
        setup(tabs = listOf("b", "c"))
        state.requestAddAnn("b", listOf(1))
        val request = assertNotNull(state.addAnnRequest)
        state.confirmAddAnn(request, "plain", request.capture!!.presentation.options)
        rule.waitForIdle()

        assertTrue(rule.onAllNodesWithText("1 from b", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(!exists(logRefRecordTag(state.tab("b")!!.annotations.blocks.single().id, 1)))
    }

    // ── Adding chosen fields ─────────────────────────────────────────────────────────────────────

    @Test
    fun chosenFieldsAreFrozenIntoTheTabsOwnNoteAsAFieldsReference() {
        setupJson()

        val id = state.addFields("a", 1, ADDRESS, listOf("id"))
        rule.waitForIdle()

        val block = state.tab("a")!!.annotations.blocks.single() as AnnBlock.LogRef
        assertEquals(id, block.id)
        assertEquals(listOf(1), block.logIds)
        val fields = assertNotNull(block.recordFields)
        assertEquals(listOf(ADDRESS, listOf("id")), fields.map { it.path })
        assertEquals(listOf("\"AA:BB:CC:DD\"", "7"), fields.map { it.json })
        assertNull(block.sourceTabId, "the tab's own note keeps no source tab")
        assertNotNull(block.sourceRecordFingerprint)
        assertNull(block.sourceEntries!!.single().rawRecord)

        // The note shows each chosen field under its path (parents in secondary text), the last segment as the field.
        assertTrue(exists(logRefFieldsTag(id)))
        rule.onNodeWithTag(logRefFieldsTimeTag(id)).assertTextContains("10:00:01.000", substring = true)
        rule.onNodeWithTag(logRefFieldPathTag(id, 0)).assertTextContains("metadata › bluetooth ›")
        assertTrue(!exists(logRefFieldPathTag(id, 1)), "a field at the top has no path above it")
        rule.onNodeWithTag(nodeTag("1.f$id-0", "accessory_address")).assertTextContains("AA:BB:CC:DD", substring = true)
        rule.onNodeWithTag(nodeTag("1.f$id-1", "id")).assertTextContains("7", substring = true)
    }

    @Test
    fun aChosenContainerOpensToItsFieldsAndItsSubtreeCanBeToggled() {
        setupJson()
        val id = state.addFields("a", 1, listOf("metadata", "bluetooth"))
        rule.waitForIdle()
        val root = "1.f$id-0"

        rule.onNodeWithTag(nodeTag(root, "bluetooth")).assertTextContains("{2 fields}", substring = true)
        rule.onNodeWithTag(nodeTag(root, "bluetooth", "rssi")).assertTextContains("-61", substring = true)
        rule.onNodeWithTag(nodeTag(root, "bluetooth", toggle = true)).performClick()
        rule.onNodeWithTag(nodeTag(root, "bluetooth", "rssi")).assertDoesNotExist()
    }

    @Test
    fun chosenFieldsOfATabThatViewsASharedNoteGoToTheSharedNoteAsACrossFileReference() {
        setupJson()
        val host = assertNotNull(state.createSharedNote("Pairing", listOf("a", "b")))
        rule.waitForIdle()

        state.requestAddRecordFields("a", 1, listOf(ADDRESS))
        val request = assertNotNull(state.addAnnRequest)
        assertEquals(host, request.targetTabId)
        state.confirmAddAnn(request, "From the shared view", request.capture!!.presentation.options)
        rule.waitForIdle()

        val block = assertIs<AnnBlock.LogRef>(state.noteTab(host)!!.annotations.blocks.single())
        assertEquals("a", block.sourceTabId)
        assertEquals("a.log", block.sourceFilename)
        assertEquals(listOf(ADDRESS), block.recordFields!!.map { it.path })
        assertEquals(1, block.sourceEntries!!.size)
        assertTrue(exists(logRefSourceBadgeTag(block.id)), "the shared note names the file the fields come from")
        assertTrue(exists(logRefGoToRowsTag(block.id)))
        assertTrue(state.tab("a")!!.annotations.blocks.isEmpty(), "the tab's own note is untouched")
    }

    @Test
    fun aSelectionOverTheSizeCapShowsANoticeAndAddsNothing() {
        val big = "x".repeat(70 * 1024)
        setupJson { s ->
            s.tabs = s.tabs.map { tab ->
                if (tab.id == "a") tab.copy(logData = listOf(jsonRow(1, """{"a":"$big"}""")), rmap = mapOf(1 to jsonRow(1, """{"a":"$big"}"""))) else tab
            }
        }

        state.requestAddRecordFields("a", 1, listOf(listOf("a")))

        assertNull(state.addAnnRequest)
        assertEquals("Too much to add", state.pendingDiagramNotice?.title)
    }

    // ── Picking fields and the menu ──────────────────────────────────────────────────────────────

    @Test
    fun rightClickingAFieldInMessageDetailsOffersAddFieldToNotes() {
        setupJson()
        selectRow(1)

        rightClickNode(detailsNode("metadata", "bluetooth", "accessory_address"))

        rule.onNodeWithText("Add field to notes").assertExists()
        rule.onNodeWithText("Copy value").assertExists()
        rule.onNodeWithText("Copy path").assertExists()
        rule.onNodeWithText("Add field to notes").performClick()
        rule.waitForIdle()

        val request = assertNotNull(state.addAnnRequest)
        assertEquals(listOf(listOf("metadata", "bluetooth", "accessory_address")), request.recordFields!!.map { it.path })
        assertEquals("a", request.targetTabId)
    }

    @Test
    fun addingAParsedTextFieldToNotesUsesTheVisibleFieldSource() {
        val entry = LogEntry(
            1, "12:00:00.000", LogLevel.I, "daemon", "ready",
            recordMetadata = LogRecordMetadata(
                profileFields = mapOf("HOST" to "host1", "MESSAGE" to "ready", "sd.state" to "ready"),
                rawRecord = "<134>1 2026-11-16T12:00:00.000Z host1 daemon 123 ID47 [example state=\"ready\"] ready",
                usesProfilePresentation = true,
            ),
        )
        setup { s ->
            s.tabs = s.tabs.map { tab ->
                if (tab.id == "a") mkTab("a", "a.log", listOf(entry)).copy(sourcePath = tab.sourcePath) else tab
            }
        }
        selectRow(1)
        rightClickNode(detailsNode("host"))
        rule.onNodeWithText("Add field to notes").performClick()
        val request = assertNotNull(state.addAnnRequest)
        assertEquals(listOf("host"), request.recordFields!!.single().path)
        assertEquals("\"host1\"", request.recordFields!!.single().json)
    }

    @Test
    fun theMenuOfAFieldUnderALogRowAddsItToo() {
        setupJson()
        rule.onNodeWithTag("structured-record-disclosure-1").performClick()
        rule.waitForIdle()

        rightClickNode(nodeTag("1.root", "id"))
        assertNull(state.ctx, "the log row's own context menu does not open under the field menu")
        rule.onNodeWithText("Add field to notes").performClick()
        rule.waitForIdle()

        assertEquals(listOf(listOf("id")), assertNotNull(state.addAnnRequest).recordFields!!.map { it.path })
    }

    @Test
    fun ctrlClickPicksFieldsOfOneRecordAndTheMenuAddsThemAll() {
        setupJson()
        selectRow(1)

        ctrlClick(detailsNode("metadata", "bluetooth", "accessory_address"))
        ctrlClick(detailsNode("metadata", "bluetooth", "rssi"))
        assertEquals(setOf(ADDRESS, RSSI), state.recordFieldSelection!!.paths)

        // A third pick toggles off again.
        ctrlClick(detailsNode("metadata", "bluetooth", "rssi"))
        assertEquals(setOf(ADDRESS), state.recordFieldSelection!!.paths)
        ctrlClick(detailsNode("metadata", "bluetooth", "rssi"))

        rightClickNode(detailsNode("metadata", "bluetooth", "rssi"))
        rule.onNodeWithText("Add 2 fields to notes").assertExists()
        rule.onNodeWithText("Copy value").assertDoesNotExist()
        rule.onNodeWithText("Add 2 fields to notes").performClick()
        rule.waitForIdle()

        assertEquals(setOf(ADDRESS, RSSI), assertNotNull(state.addAnnRequest).recordFields!!.map { it.path }.toSet())
        assertNull(state.recordFieldSelection, "the pick is spent once it is being added")
    }

    @Test
    fun aPickInAnotherRecordReplacesTheFirst() {
        setupJson()
        selectRow(1)
        ctrlClick(detailsNode("id"))
        assertEquals(1, state.recordFieldSelection!!.entryId)

        selectRow(2)
        ctrlClick(nodeTag("2.details", "id"))

        assertEquals(2, state.recordFieldSelection!!.entryId)
        assertEquals(setOf(listOf("id")), state.recordFieldSelection!!.paths)
    }

    @Test
    fun aPlainClickOnALogRowClearsThePick() {
        setupJson()
        selectRow(1)
        ctrlClick(detailsNode("id"))
        assertNotNull(state.recordFieldSelection)

        selectRow(2)

        assertNull(state.recordFieldSelection)
    }

    @Test
    fun aPlainClickOnAFieldMakesThatFieldTheWholePick() {
        setupJson()
        selectRow(1)
        ctrlClick(detailsNode("id"))
        ctrlClick(detailsNode("timestamp"))
        assertEquals(setOf(listOf("id"), listOf("timestamp")), state.recordFieldSelection!!.paths)

        rule.onNodeWithTag(detailsNode("timestamp")).performClick()
        rule.waitForIdle()

        assertEquals(setOf(listOf("timestamp")), state.recordFieldSelection!!.paths)
    }

    @Test
    fun escapeClearsThePick() {
        setupJson()
        selectRow(1)
        ctrlClick(detailsNode("id"))
        assertNotNull(state.recordFieldSelection)

        rule.onNodeWithTag(detailsNode("id")).performKeyInput {
            keyDown(Key.Escape)
            keyUp(Key.Escape)
        }
        rule.waitForIdle()

        assertNull(state.recordFieldSelection)
    }

    // ── Go to rows ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun goToRowsOnAFieldsReferenceOpensTheRecordAtTheChosenFields() {
        setupJson()
        val id = state.addFields("a", 1, ADDRESS)
        val block = state.tab("a")!!.annotations.blocks.single { it.id == id } as AnnBlock.LogRef
        rule.waitForIdle()
        assertTrue(!state.recordExpansion("a").isExpanded(1))

        val result = runBlocking { withTimeout(20_000) { state.goToLogRefRows(block, "a") } }
        rule.waitForIdle()

        assertIs<GoToRowsResult.Navigated>(result)
        assertTrue(state.recordExpansion("a").isExpanded(1), "the record under its row is open")
        // The chosen field is open and shown; the way down to it is open, a sibling container is not.
        rule.onNodeWithTag(nodeTag("1.root", "metadata", "bluetooth", "accessory_address")).assertTextContains("AA:BB:CC:DD", substring = true)
        rule.onNodeWithTag(nodeTag("1.root", "metadata", "tags")).assertTextContains("[2 items]", substring = true)
        rule.onNodeWithTag(nodeTag("1.root", "metadata", "tags", "[0]")).assertDoesNotExist()
    }

    /** The colour at the left edge of the row [tag], inside its own background (past no text), to tell a tinted row from a plain one. */
    private fun rowEdgeColor(tag: String) = rule.onNodeWithTag(tag).captureToImage().toPixelMap().let { it[2, it.height / 2] }

    @Test
    fun goToRowsMarksTheReferencedFieldsLikeAPickAndEscClearsThem() {
        setupJson()
        val id = state.addFields("a", 1, ADDRESS, RSSI)
        val block = state.tab("a")!!.annotations.blocks.single { it.id == id } as AnnBlock.LogRef
        rule.waitForIdle()

        runBlocking { withTimeout(20_000) { state.goToLogRefRows(block, "a") } }
        rule.waitForIdle()

        assertEquals(setOf(ADDRESS, RSSI), state.recordFieldSelection!!.paths, "the fields are the pick")
        val address = nodeTag("1.root", "metadata", "bluetooth", "accessory_address")
        val rssi = nodeTag("1.root", "metadata", "bluetooth", "rssi")
        val sibling = nodeTag("1.root", "metadata", "tags")
        assertTrue(rowEdgeColor(address) == rowEdgeColor(rssi), "both marked rows share the pick tint")
        assertTrue(rowEdgeColor(address) != rowEdgeColor(sibling), "an unchosen row is not marked")

        // Picking a third field and unpicking it again gives the view keyboard focus (as using the marks would) and leaves the two.
        ctrlClick(sibling)
        ctrlClick(sibling)
        assertEquals(setOf(ADDRESS, RSSI), state.recordFieldSelection!!.paths)
        rule.onNodeWithTag(address).performKeyInput {
            keyDown(Key.Escape)
            keyUp(Key.Escape)
        }
        // The pointer is still over the last clicked row, which shows the hover tint: take it away before comparing colours.
        rule.onAllNodes(isRoot())[0].performMouseInput { moveTo(Offset(2f, 2f)) }
        rule.waitForIdle()
        assertNull(state.recordFieldSelection)
        assertTrue(rowEdgeColor(address) == rowEdgeColor(sibling), "Esc clears the marks")
    }

    @Test
    fun goToRowsOnARecordShownInMessageDetailsMarksItsFieldsThereToo() {
        setupJson()
        val id = state.addFields("a", 1, ADDRESS)
        val block = state.tab("a")!!.annotations.blocks.single { it.id == id } as AnnBlock.LogRef
        rule.waitForIdle()

        runBlocking { withTimeout(20_000) { state.goToLogRefRows(block, "a") } }
        rule.waitForIdle()

        assertEquals(setOf(ADDRESS), state.recordFieldSelection!!.paths)
        val address = detailsNode("metadata", "bluetooth", "accessory_address")
        assertTrue(rowEdgeColor(address) != rowEdgeColor(detailsNode("id")), "the chosen field is marked in Message details")
        // A plain click on another field picks that one instead, and the marks move.
        rule.onNodeWithTag(detailsNode("id")).performClick()
        rule.onAllNodes(isRoot())[0].performMouseInput { moveTo(Offset(2f, 2f)) }
        rule.waitForIdle()
        assertEquals(setOf(listOf("id")), state.recordFieldSelection!!.paths)
        assertTrue(rowEdgeColor(address) == rowEdgeColor(detailsNode("timestamp")), "the old marks are gone")
        assertTrue(rowEdgeColor(detailsNode("id")) != rowEdgeColor(detailsNode("timestamp")), "the clicked field is marked")
    }

    @Test
    fun goToRowsScrollsTheFirstChosenFieldIntoViewInATallRecord() {
        val filler = (1..60).joinToString(",") { """"f$it":$it""" }
        val tall = """{$filler,"zz":{"deep":{"target":"found"}}}"""
        setupJson(height = 420) { s ->
            s.tabs = s.tabs.map { tab ->
                if (tab.id == "a") mkTab("a", "a.log", listOf(jsonRow(1, tall), jsonRow(2, RECORD_TWO))).copy(sourcePath = tab.sourcePath) else tab
            }
        }
        val id = state.addFields("a", 1, listOf("zz", "deep", "target"))
        val block = state.tab("a")!!.annotations.blocks.single { it.id == id } as AnnBlock.LogRef
        rule.waitForIdle()

        runBlocking { withTimeout(20_000) { state.goToLogRefRows(block, "a") } }
        rule.waitForIdle()

        rule.onNodeWithTag(nodeTag("1.root", "zz", "deep", "target")).assertIsDisplayed()
    }

    @Test
    fun aGoToRowsWithoutFieldsPicksNothing() {
        setupJson()
        val id = state.addWholeRecord()
        val block = state.tab("a")!!.annotations.blocks.single { it.id == id } as AnnBlock.LogRef

        runBlocking { withTimeout(20_000) { state.goToLogRefRows(block, "a") } }

        assertNull(state.recordFieldSelection)
    }

    @Test
    fun aWholeRecordReferenceOnlyNavigatesToTheRow() {
        setupJson()
        val id = state.addWholeRecord()
        val block = state.tab("a")!!.annotations.blocks.single { it.id == id } as AnnBlock.LogRef

        runBlocking { withTimeout(20_000) { state.goToLogRefRows(block, "a") } }

        assertNull(state.recordFieldOpenRequest)
    }
}

/** What a recording stand-in for the log view's field actions saw. */
@OptIn(ExperimentalTestApi::class)
class RecordFieldMenuUiTest {
    @get:Rule
    val rule = createComposeRule()

    private class Recorder : RecordFieldActions {
        var picked by mutableStateOf(emptySet<List<String>>())
        val added = mutableListOf<List<List<String>>>()
        val copied = mutableListOf<String>()
        var reclaimed = 0

        override fun selectedPaths(tabId: String, entryId: Int) = picked

        override fun toggleSelected(tabId: String, entryId: Int, path: List<String>) {
            picked = if (path in picked) picked.minusElement(path) else picked.plusElement(path)
        }

        override fun addToNotes(tabId: String, entryId: Int, paths: List<List<String>>) {
            added += paths
        }

        override fun copyText(text: String) {
            copied += text
        }

        override fun openRequest(tabId: String, entryId: Int): RecordFieldOpenRequest? = null

        override fun reclaimFocus() {
            reclaimed++
        }
    }

    private fun show(recorder: Recorder) {
        rule.setContent {
            CompositionLocalProvider(
                LocalTheme provides LIGHT_THEME,
                LocalRecordFieldActions provides recorder,
                LocalContextMenuRepresentation provides IndagiumContextMenuRepresentation,
            ) {
                Box(Modifier.size(520.dp, 300.dp)) {
                    StructuredRecordBody(1, RECORD_ONE, FontFamily.Monospace, startIndent = 4.dp, initiallyExpanded = true, recordTabId = "t")
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun theMenuCopiesTheValueAndThePathAndGivesFocusBackWhenItCloses() {
        val recorder = Recorder()
        show(recorder)
        val address = nodeTag("1.root", "metadata", "bluetooth", "accessory_address")

        rule.onNodeWithTag(address).performMouseInput { rightClick(center) }
        rule.waitForIdle()
        rule.onNodeWithText("Copy value").performClick()
        rule.waitForIdle()
        assertEquals(listOf("AA:BB:CC:DD"), recorder.copied, "a string copies as its text, not as JSON")
        assertEquals(1, recorder.reclaimed)

        rule.onNodeWithTag(address).performMouseInput { rightClick(center) }
        rule.waitForIdle()
        rule.onNodeWithText("Copy path").performClick()
        rule.waitForIdle()
        assertEquals("metadata.bluetooth.accessory_address", recorder.copied.last())
        assertEquals(2, recorder.reclaimed)

        rule.onNodeWithTag(nodeTag("1.root", "metadata", "bluetooth")).performMouseInput { rightClick(center) }
        rule.waitForIdle()
        rule.onNodeWithText("Copy value").performClick()
        rule.waitForIdle()
        assertEquals("""{"accessory_address":"AA:BB:CC:DD","rssi":-61}""", recorder.copied.last(), "a container copies as compact JSON")
    }

    @Test
    fun dismissingTheMenuWithoutChoosingStillGivesFocusBack() {
        val recorder = Recorder()
        show(recorder)

        rule.onNodeWithTag(nodeTag("1.root", "id")).performMouseInput { rightClick(center) }
        rule.waitForIdle()
        rule.onNodeWithText("Add field to notes").assertExists()
        // A click outside the menu closes it.
        rule.onAllNodes(isRoot())[0].performMouseInput { click(Offset(500f, 280f)) }
        rule.waitForIdle()

        assertTrue(recorder.added.isEmpty())
        rule.onNodeWithText("Add field to notes").assertDoesNotExist()
        assertEquals(1, recorder.reclaimed)
    }

    @Test
    fun aPickedRowStaysPickedAndTheMenuOnAnUnpickedRowActsOnThatRowOnly() {
        val recorder = Recorder()
        show(recorder)
        val id = nodeTag("1.root", "id")
        val stamp = nodeTag("1.root", "timestamp")
        rule.ctrlClickNode(id, Key.MetaLeft)
        rule.ctrlClickNode(stamp, Key.CtrlLeft)
        assertEquals(setOf(listOf("id"), listOf("timestamp")), recorder.picked)

        rule.onNodeWithTag(nodeTag("1.root", "metadata")).performMouseInput { rightClick(center) }
        rule.waitForIdle()
        rule.onNodeWithText("Add field to notes").performClick()
        rule.waitForIdle()

        assertEquals(listOf(listOf(listOf("metadata"))), recorder.added)
    }
}

/** The field menu from the expanded value areas of a field: a hex dump and the numbered lines of a multi-line string. */
@OptIn(ExperimentalTestApi::class)
class RecordFieldValueAreaMenuUiTest {
    @get:Rule
    val rule = createComposeRule()

    private class Recorder : RecordFieldActions {
        var picked by mutableStateOf(emptySet<List<String>>())
        val added = mutableListOf<List<List<String>>>()
        val copied = mutableListOf<String>()

        override fun selectedPaths(tabId: String, entryId: Int) = picked

        override fun toggleSelected(tabId: String, entryId: Int, path: List<String>) {
            picked = if (path in picked) picked.minusElement(path) else picked.plusElement(path)
        }

        override fun addToNotes(tabId: String, entryId: Int, paths: List<List<String>>) {
            added += paths
        }

        override fun copyText(text: String) {
            copied += text
        }

        override fun openRequest(tabId: String, entryId: Int): RecordFieldOpenRequest? = null

        override fun reclaimFocus() = Unit
    }

    private val hexLine = "structured-record-hex-line-1-1-root-blob-0"
    private val stringLine = "structured-record-string-line-1-1-root-note-1"

    private fun show(recorder: Recorder) {
        rule.setContent {
            CompositionLocalProvider(
                LocalTheme provides LIGHT_THEME,
                LocalRecordFieldActions provides recorder,
                LocalContextMenuRepresentation provides IndagiumContextMenuRepresentation,
                androidx.compose.foundation.text.LocalTextContextMenu provides IndagiumTextContextMenu,
            ) {
                Box(Modifier.size(620.dp, 360.dp)) {
                    StructuredRecordBody(1, RECORD_BLOB, FontFamily.Monospace, startIndent = 4.dp, initiallyExpanded = true, recordTabId = "t")
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun rightClickingAHexDumpOffersTheFieldsMenuAndNotJustCopy() {
        val recorder = Recorder()
        show(recorder)

        rule.onNodeWithTag(hexLine).performMouseInput { rightClick(center) }
        rule.waitForIdle()

        rule.onNodeWithText("Add field to notes").assertExists()
        rule.onNodeWithText("Copy value").assertExists()
        rule.onNodeWithText("Copy path").assertExists()
        rule.onNodeWithText("Copy").assertDoesNotExist()
        rule.onNodeWithText("Add field to notes").performClick()
        rule.waitForIdle()
        assertEquals(listOf(listOf(listOf("blob"))), recorder.added)
    }

    @Test
    fun rightClickingTheLinesOfAMultiLineStringOffersItsFieldsMenu() {
        val recorder = Recorder()
        show(recorder)

        rule.onNodeWithTag(stringLine).performMouseInput { rightClick(center) }
        rule.waitForIdle()
        rule.onNodeWithText("Copy path").performClick()
        rule.waitForIdle()

        assertEquals("note", recorder.copied.single())
        rule.onNodeWithTag(stringLine).performMouseInput { rightClick(center) }
        rule.waitForIdle()
        rule.onNodeWithText("Copy value").performClick()
        rule.waitForIdle()
        assertEquals("first line of text\nsecond line of text\nthird line", recorder.copied.last())
    }

    @Test
    fun ctrlClickingTheValueAreaTogglesTheField() {
        val recorder = Recorder()
        show(recorder)

        rule.ctrlClickNode(hexLine, Key.CtrlLeft)
        rule.ctrlClickNode(stringLine, Key.MetaLeft)
        assertEquals(setOf(listOf("blob"), listOf("note")), recorder.picked)
        rule.ctrlClickNode(hexLine, Key.CtrlLeft)
        assertEquals(setOf(listOf("note")), recorder.picked)
        rule.ctrlClickNode(hexLine, Key.CtrlLeft)

        // The field is part of the pick, so its value area's menu adds the whole pick.
        rule.onNodeWithTag(hexLine).performMouseInput { rightClick(center) }
        rule.waitForIdle()
        rule.onNodeWithText("Add 2 fields to notes").performClick()
        rule.waitForIdle()
        assertEquals(setOf(listOf("blob"), listOf("note")), recorder.added.single().toSet())
    }

    @Test
    fun withTextSelectedTheMenuAlsoOffersCopyForIt() {
        val recorder = Recorder()
        show(recorder)

        // Drag over the second line of the string to select text in the tree.
        rule.onNodeWithTag(stringLine).performMouseInput {
            moveTo(Offset(2f, center.y))
            press()
            moveTo(Offset(width - 2f, center.y))
            release()
        }
        rule.waitForIdle()
        rule.onNodeWithTag(stringLine).performMouseInput { rightClick(Offset(10f, center.y)) }
        rule.waitForIdle()

        rule.onNodeWithText("Copy").assertExists()
        rule.onNodeWithText("Add field to notes").assertExists()
        rule.onNodeWithText("Copy").performClick()
        rule.waitForIdle()
        assertTrue(recorder.copied.single().contains("second line"), "Copy copies the selected text: ${recorder.copied}")
    }

    @Test
    fun aDragInTheValueAreaStillSelectsAndOpensNoMenu() {
        val recorder = Recorder()
        show(recorder)

        rule.onNodeWithTag(hexLine).performMouseInput {
            moveTo(Offset(2f, center.y))
            press()
            moveTo(Offset(width - 2f, center.y))
            release()
        }
        rule.waitForIdle()

        rule.onNodeWithText("Add field to notes").assertDoesNotExist()
        assertTrue(recorder.picked.isEmpty())
    }

    @Test
    fun theChosenFieldIsBroughtIntoViewInATallRecord() {
        val recorder = object : RecordFieldActions by Recorder() {
            override fun selectedPaths(tabId: String, entryId: Int) = setOf(listOf("zz", "deep", "target"))

            override fun openRequest(tabId: String, entryId: Int) =
                RecordFieldOpenRequest(1, tabId, null, entryId, setOf(listOf("zz", "deep", "target")))
        }
        val filler = (1..40).joinToString(",") { """"f$it":$it""" }
        val record = "{$filler,\"zz\":{\"deep\":{\"target\":\"found\"}}}"
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME, LocalRecordFieldActions provides recorder) {
                Box(Modifier.size(500.dp, 160.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        StructuredRecordBody(1, record, FontFamily.Monospace, startIndent = 4.dp, recordTabId = "t")
                    }
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag(nodeTag("1.root", "zz", "deep", "target")).assertIsDisplayed()
    }
}

/** Writes the record-field surfaces to build/shared-notes-qa (8-structured-*.png) so they can be looked at. */
@OptIn(ExperimentalTestApi::class)
class RecordFieldsQaScreenshotsUiTest : SharedNoteUiTestBase() {
    private fun jsonRow(id: Int, json: String) = LogEntry(
        id, "10:00:%02d.000".format(id), LogLevel.I, "HCI", "message $id",
        recordMetadata = LogRecordMetadata(rawRecord = json, usesProfilePresentation = true),
    )

    /** The log view of tab a with two JSON records, drawn as the app draws it (its own context-menu look). */
    private fun setupShot(width: Int = 1000, height: Int = 760, records: List<String> = listOf(RECORD_ONE, RECORD_TWO)) {
        dir = createTempDirectory("record-fields-qa").toFile()
        state = AppState(File(dir, "state.cache"), notesDir = File(dir, "notes"), autoExportNotes = false)
        state.tabs = listOf(
            mkTab("a", "Untitled.json", records.mapIndexed { index, json -> jsonRow(index + 1, json) })
                .copy(sourcePath = logFile("a.log").absolutePath),
        )
        state.activeTabId = "a"
        rule.setContent {
            CompositionLocalProvider(
                LocalTheme provides LIGHT_THEME,
                LocalContextMenuRepresentation provides IndagiumContextMenuRepresentation,
                androidx.compose.foundation.text.LocalTextContextMenu provides IndagiumTextContextMenu,
            ) {
                Box(Modifier.size(width.dp, height.dp)) { state.tab("a")?.let { FileView(state, it) } }
            }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        val roots = rule.onAllNodes(isRoot())
        val count = roots.fetchSemanticsNodes().size
        for (i in 0 until count) saveScreenshot(roots[i], if (i == 0) name else "$name-layer$i")
    }

    private fun AppState.add(request: () -> Unit) {
        request()
        val pending = assertNotNull(addAnnRequest)
        confirmAddAnn(pending, "Accessory pairing", pending.capture?.presentation?.options ?: AnnBlock.LogRefPresentationOptions())
    }

    @Test
    fun aNoteWithAWholeRecordAndAFieldsReference() {
        setupShot()
        state.add { state.requestAddAnn("a", listOf(1)) }
        state.add { state.requestAddRecordFields("a", 1, listOf(ADDRESS, listOf("id"))) }
        rule.waitForIdle()
        rule.onNodeWithTag("tile-maximize-notes").performClick()
        rule.waitForIdle()

        shot("8-structured-note-whole-record-and-fields")
    }

    @Test
    fun aFieldsReferenceWithAContainerAndAScalar() {
        setupShot()
        state.add { state.requestAddRecordFields("a", 1, listOf(listOf("metadata", "bluetooth"), listOf("timestamp"), listOf("metadata", "tags", "[1]"))) }
        rule.waitForIdle()
        rule.onNodeWithTag("tile-maximize-notes").performClick()
        rule.waitForIdle()

        shot("8-structured-note-fields-with-container")
    }

    @Test
    fun theFieldMenuAndAMultiSelection() {
        setupShot()
        rule.onNodeWithTag("log-row-a:main-1").performClick()
        rule.waitForIdle()
        val address = nodeTag("1.details", "metadata", "bluetooth", "accessory_address")

        rule.onNodeWithTag(address).performMouseInput { rightClick(center) }
        rule.waitForIdle()
        shot("8-structured-field-menu")
        rule.onAllNodes(isRoot())[0].performMouseInput { click(Offset(2f, 2f)) }
        rule.waitForIdle()

        rule.ctrlClickNode(address, Key.CtrlLeft)
        rule.ctrlClickNode(nodeTag("1.details", "id"), Key.CtrlLeft)
        shot("8-structured-multiselect")
        rule.onNodeWithTag(nodeTag("1.details", "id")).performMouseInput { rightClick(center) }
        rule.waitForIdle()
        shot("8-structured-multiselect-menu")
    }

    @Test
    fun theFieldMenuOverAHexDumpWithTextSelected() {
        setupShot(records = listOf(RECORD_BLOB, RECORD_TWO))
        rule.onNodeWithTag("log-row-a:main-1").performClick()
        rule.waitForIdle()
        val line = rule.onNodeWithTag("structured-record-hex-line-1-1-details-blob-0")

        line.performMouseInput {
            moveTo(Offset(2f, center.y))
            press()
            moveTo(Offset(width * 0.6f, center.y))
            release()
        }
        rule.waitForIdle()
        line.performMouseInput { rightClick(Offset(width * 0.3f, center.y)) }
        rule.waitForIdle()

        // Two "Copy": the Notes bar's button and the menu's item for the selected text.
        assertEquals(2, rule.onAllNodesWithText("Copy").fetchSemanticsNodes().size)
        rule.onNodeWithText("Add field to notes").assertExists()
        shot("9-hex-dump-field-menu-with-selection")
    }

    @Test
    fun aTextSelectionOverThreeFieldsWithItsAddMenuOpen() {
        setupShot(records = listOf(RECORD_SELECT, RECORD_TWO))
        rule.onNodeWithTag("log-row-a:main-1").performClick()
        rule.waitForIdle()

        rule.selectTextAcross(nodeTag("1.details", "metadata", "identity"), nodeTag("1.details", "metadata", "status"), KEY_INSET + 24f)
        rule.onNodeWithTag(nodeTag("1.details", "metadata", "source")).performMouseInput { rightClick(Offset(width - 8f, center.y)) }
        rule.waitForIdle()

        rule.onNodeWithText("Add 3 selected fields to notes").assertExists()
        shot("10-selection-add-menu")
    }

    @Test
    fun theFieldsMarkedAfterGoToRows() {
        setupShot()
        state.add { state.requestAddRecordFields("a", 1, listOf(ADDRESS, RSSI)) }
        val block = state.tab("a")!!.annotations.blocks.single() as AnnBlock.LogRef
        rule.waitForIdle()

        runBlocking { withTimeout(20_000) { state.goToLogRefRows(block, "a") } }
        rule.waitForIdle()

        shot("9-go-to-rows-marked-fields")
    }
}
