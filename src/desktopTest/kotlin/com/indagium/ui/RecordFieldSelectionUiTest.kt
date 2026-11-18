@file:Suppress("MagicNumber")
@file:OptIn(ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogRecordMetadata
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A record whose `metadata` holds five fields; every row is open in Message details. */
internal const val RECORD_SELECT = """{"id":7,"metadata":{"identity":"abc","source":"phone","status":"ok","owner":"me","rank":3},"tail":true}"""

private val IDENTITY = listOf("metadata", "identity")
private val SOURCE = listOf("metadata", "source")
private val STATUS = listOf("metadata", "status")
private val OWNER = listOf("metadata", "owner")
private val RANK = listOf("metadata", "rank")
private val METADATA = listOf("metadata")

/** How far into a field row (whose bounds start after its indent) its key text is hit: past the 20 px chevron slot. */
internal const val KEY_INSET = 28f

/** Drags the mouse from [fromTag] to [toTag], pressing at [fromInset] px into the first row and releasing [toInset] px into the second. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeContentTestRule.dragSelect(fromTag: String, fromInset: Float, toTag: String, toInset: Float) {
    val from = onNodeWithTag(fromTag).fetchSemanticsNode().boundsInRoot
    val to = onNodeWithTag(toTag).fetchSemanticsNode().boundsInRoot
    onAllNodes(isRoot())[0].performMouseInput {
        moveTo(Offset(from.left + fromInset, from.top + 6f))
        press()
        moveTo(Offset(to.left + toInset, to.top + 6f))
        release()
    }
    waitForIdle()
}

/** A click on the field row [tag] (at its key text) with [modifiers] held. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeContentTestRule.modifiedClick(tag: String, ctrl: Boolean = false, shift: Boolean = false, inset: Float = KEY_INSET) {
    val bounds = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    val root = onAllNodes(isRoot())[0]
    val modifiers = listOfNotNull(Key.CtrlLeft.takeIf { ctrl }, Key.ShiftLeft.takeIf { shift })
    modifiers.forEach { key -> root.performKeyInput { keyDown(key) } }
    root.performMouseInput { click(Offset(bounds.left + inset, bounds.center.y)) }
    modifiers.forEach { key -> root.performKeyInput { keyUp(key) } }
    waitForIdle()
}

/** The pure part: which of a tree's covered rows a selection adds. */
class RecordFieldNormalizationTest {
    private val order = listOf(
        listOf("id"),
        METADATA,
        IDENTITY,
        SOURCE,
        STATUS,
        listOf("tail"),
    )

    @Test
    fun separateScalarsAreKeptInDocumentOrder() {
        assertEquals(listOf(IDENTITY, SOURCE, STATUS), normalizeFieldPaths(order, setOf(STATUS, IDENTITY, SOURCE)))
    }

    @Test
    fun aContainerWhoseWholeVisibleSubtreeIsCoveredStandsForIt() {
        assertEquals(listOf(listOf("id"), METADATA), normalizeFieldPaths(order, setOf(listOf("id"), METADATA, IDENTITY, SOURCE, STATUS)))
    }

    @Test
    fun aPartlyCoveredContainerIsLeftOutAndItsCoveredFieldsCountOneByOne() {
        assertEquals(listOf(listOf("id"), IDENTITY, SOURCE), normalizeFieldPaths(order, setOf(listOf("id"), METADATA, IDENTITY, SOURCE)))
    }

    @Test
    fun theFieldsInsideAContainerWithoutItsOwnRowAreNotTheContainer() {
        assertEquals(listOf(IDENTITY, SOURCE, STATUS), normalizeFieldPaths(order, setOf(IDENTITY, SOURCE, STATUS)))
    }

    @Test
    fun aCoveredContainerWithNothingOfItsContentsCoveredIsKept() {
        assertEquals(listOf(METADATA), normalizeFieldPaths(order, setOf(METADATA)))
    }

    @Test
    fun aClosedContainerIsOneRowAndSoCoveredAsAWhole() {
        assertEquals(listOf(METADATA), normalizeFieldPaths(listOf(listOf("id"), METADATA, listOf("tail")), setOf(METADATA)))
    }

    @Test
    fun nothingCoveredAddsNothing() {
        assertTrue(normalizeFieldPaths(order, emptySet()).isEmpty())
    }

    @Test
    fun theMenuActsOnThePickWhenThePointerIsOnAPickedRowElseOnTheSelectionWhenItIsInsideIt() {
        val selection = TextSelectionFields(setOf(IDENTITY, SOURCE, STATUS), listOf(IDENTITY, SOURCE, STATUS))
        val pick = setOf(SOURCE, OWNER)

        assertEquals(FieldMenuTargets.Pick(listOf(SOURCE, OWNER)), fieldMenuTargets(pick, SOURCE, selection), "on a picked row the pick decides")
        assertEquals(FieldMenuTargets.Pick(listOf(SOURCE, OWNER)), fieldMenuTargets(pick, OWNER, null))
        assertEquals(FieldMenuTargets.Selection(listOf(IDENTITY, SOURCE, STATUS)), fieldMenuTargets(pick, IDENTITY, selection))
        assertEquals(FieldMenuTargets.One(RANK), fieldMenuTargets(pick, RANK, selection), "outside both, just the row")
        assertEquals(FieldMenuTargets.One(IDENTITY), fieldMenuTargets(emptySet(), IDENTITY, TextSelectionFields(setOf(IDENTITY), listOf(IDENTITY))))
        assertEquals(FieldMenuTargets.One(SOURCE), fieldMenuTargets(setOf(SOURCE), SOURCE, null), "a pick of one is no pick")
    }
}

/** The field menu with a text selection over several fields, and Shift-click picking, on a tree of the field-actions stand-in. */
@OptIn(ExperimentalTestApi::class)
class RecordFieldTextSelectionUiTest {
    @get:Rule
    val rule = createComposeRule()

    private class Recorder : RecordFieldActions {
        var picked by mutableStateOf(emptySet<List<String>>())
        var anchor: List<String>? by mutableStateOf(null)
        val added = mutableListOf<List<List<String>>>()
        val copied = mutableListOf<String>()

        override fun selectedPaths(tabId: String, entryId: Int) = picked

        override fun toggleSelected(tabId: String, entryId: Int, path: List<String>) {
            picked = if (path in picked) picked.minusElement(path) else picked.plusElement(path)
            anchor = path
        }

        override fun addToNotes(tabId: String, entryId: Int, paths: List<List<String>>) {
            added += paths
        }

        override fun copyText(text: String) {
            copied += text
        }

        override fun openRequest(tabId: String, entryId: Int): RecordFieldOpenRequest? = null

        override fun reclaimFocus() = Unit

        override fun rangeAnchor(tabId: String, entryId: Int) = anchor

        override fun setRangeAnchor(tabId: String, entryId: Int, path: List<String>) {
            anchor = path
        }

        override fun pickFields(tabId: String, entryId: Int, paths: List<List<String>>, additive: Boolean) {
            picked = if (additive) picked + paths else paths.toSet()
        }
    }

    private val recorder = Recorder()

    private fun show(record: String = RECORD_SELECT) {
        rule.setContent {
            CompositionLocalProvider(
                LocalTheme provides LIGHT_THEME,
                LocalRecordFieldActions provides recorder,
                LocalContextMenuRepresentation provides IndagiumContextMenuRepresentation,
                LocalTextContextMenu provides IndagiumTextContextMenu,
            ) {
                Box(Modifier.size(620.dp, 360.dp)) {
                    StructuredRecordBody(1, record, FontFamily.Monospace, startIndent = 4.dp, initiallyExpanded = true, recordTabId = "t")
                }
            }
        }
        rule.waitForIdle()
    }

    private fun row(vararg path: String) = nodeTag("1.root", *path)

    private fun rightClick(tag: String) {
        rule.onNodeWithTag(tag).performMouseInput { rightClick(Offset(width - 8f, center.y)) }
        rule.waitForIdle()
    }

    private fun drag(from: String, to: String) = rule.dragSelect(from, KEY_INSET, to, KEY_INSET + 12f)

    private fun chooseAndAdd(label: String) {
        rule.onNodeWithText(label).performClick()
        rule.waitForIdle()
    }

    @Test
    fun draggingOverThreeSiblingFieldsOffersToAddThoseThree() {
        show()

        drag(row("metadata", "identity"), row("metadata", "status"))
        rightClick(row("metadata", "source"))

        rule.onNodeWithText("Copy").assertExists()
        rule.onNodeWithText("Add 3 selected fields to notes").assertExists()
        rule.onNodeWithText("Add field to notes").assertDoesNotExist()
        rule.onNodeWithText("Copy value").assertDoesNotExist()
        chooseAndAdd("Add 3 selected fields to notes")
        assertEquals(listOf(listOf(IDENTITY, SOURCE, STATUS)), recorder.added)
    }

    @Test
    fun theSelectionAlsoDecidesWhenDraggedUpwardsAndMenuOpenedOnItsFirstRow() {
        show()

        drag(row("metadata", "status"), row("metadata", "identity"))
        rightClick(row("metadata", "identity"))
        chooseAndAdd("Add 3 selected fields to notes")

        assertEquals(listOf(listOf(IDENTITY, SOURCE, STATUS)), recorder.added)
    }

    @Test
    fun aSelectionOverAContainersWholeSubtreeAddsTheContainerOnce() {
        show()

        drag(row("metadata"), row("metadata", "rank"))
        rightClick(row("metadata", "owner"))

        rule.onNodeWithText("Add selected field to notes").assertExists()
        rule.onNodeWithText("Copy value").assertDoesNotExist()
        chooseAndAdd("Add selected field to notes")
        assertEquals(listOf(listOf(METADATA)), recorder.added)
    }

    @Test
    fun aSelectionOverPartOfAContainerAddsTheFieldsItCoversNotTheContainer() {
        show()

        drag(row("id"), row("metadata", "owner"))
        rightClick(row("metadata", "source"))

        rule.onNodeWithText("Add 5 selected fields to notes").assertExists()
        chooseAndAdd("Add 5 selected fields to notes")
        assertEquals(listOf(listOf(listOf("id"), IDENTITY, SOURCE, STATUS, OWNER)), recorder.added)
    }

    @Test
    fun aSelectionFromInsideAHexDumpToTheNextFieldAddsTheDumpsFieldAndThatField() {
        show(RECORD_BLOB)

        val hexLine = "structured-record-hex-line-1-1-root-blob-0"
        rule.dragSelect(hexLine, 4f, row("note"), KEY_INSET + 12f)
        rightClick(row("note"))

        rule.onNodeWithText("Add 2 selected fields to notes").assertExists()
        chooseAndAdd("Add 2 selected fields to notes")
        assertEquals(listOf(listOf(listOf("blob"), listOf("note"))), recorder.added)
    }

    @Test
    fun theMenuOfAHexDumpInsideTheSelectionOffersTheSelectionToo() {
        show(RECORD_BLOB)

        rule.dragSelect(row("id"), KEY_INSET, "structured-record-hex-line-1-1-root-blob-0", 40f)
        rule.onNodeWithTag("structured-record-hex-line-1-1-root-blob-0").performMouseInput { rightClick(Offset(width - 6f, center.y)) }
        rule.waitForIdle()

        rule.onNodeWithText("Add 2 selected fields to notes").assertExists()
        chooseAndAdd("Add 2 selected fields to notes")
        assertEquals(listOf(listOf(listOf("id"), listOf("blob"))), recorder.added)
    }

    @Test
    fun aRowOutsideTheSelectionKeepsTheOrdinaryMenuAndCopyStaysFirst() {
        show()

        drag(row("metadata", "identity"), row("metadata", "status"))
        rightClick(row("metadata", "rank"))

        rule.onNodeWithText("Copy").assertExists()
        rule.onNodeWithText("Add field to notes").assertExists()
        rule.onNodeWithText("Copy value").assertExists()
        rule.onNodeWithText("Copy path").assertExists()
        rule.onNodeWithText("Add 3 selected fields to notes").assertDoesNotExist()
        chooseAndAdd("Add field to notes")
        assertEquals(listOf(listOf(RANK)), recorder.added)
    }

    @Test
    fun aSelectionInsideTheClickedFieldAloneKeepsTheOrdinaryMenu() {
        show()

        rule.dragSelect(row("metadata", "identity"), KEY_INSET, row("metadata", "identity"), KEY_INSET + 30f)
        rightClick(row("metadata", "identity"))

        rule.onNodeWithText("Copy").assertExists()
        rule.onNodeWithText("Add field to notes").assertExists()
        rule.onNodeWithText("Copy value").assertExists()
    }

    @Test
    fun copyOfASelectionOverSeveralFieldsCopiesItsText() {
        show()

        drag(row("metadata", "identity"), row("metadata", "status"))
        rightClick(row("metadata", "source"))
        chooseAndAdd("Copy")

        val text = recorder.copied.single()
        assertTrue("phone" in text && "abc" in text, "copied: $text")
        assertTrue(recorder.added.isEmpty())
    }

    @Test
    fun doubleClickingAWordSelectsItsFieldOnlyAndAddsNothingExtra() {
        show()

        rule.onNodeWithTag(row("metadata", "source")).performMouseInput { doubleClick(Offset(KEY_INSET + 4f, center.y)) }
        rule.waitForIdle()
        rightClick(row("metadata", "source"))

        rule.onNodeWithText("Add field to notes").assertExists()
        rule.onNodeWithText("Add 1 selected fields to notes").assertDoesNotExist()
    }

    // A click inside a tree clears its text selection (Compose), so in use a pick and a selection rarely meet; they are set up directly here.

    @Test
    fun aPickOnTheRowUnderThePointerBeatsASelection() {
        show()
        drag(row("metadata", "identity"), row("metadata", "status"))
        recorder.picked = setOf(SOURCE, RANK)

        // On a picked row, inside the selection too: the pick.
        rightClick(row("metadata", "source"))
        rule.onNodeWithText("Add 2 fields to notes").assertExists()
        rule.onNodeWithText("Add 3 selected fields to notes").assertDoesNotExist()
        chooseAndAdd("Add 2 fields to notes")
        assertEquals(listOf(listOf(SOURCE, RANK)), recorder.added)
    }

    @Test
    fun onARowOfTheSelectionThatIsNotPickedTheSelectionDecides() {
        show()
        drag(row("metadata", "identity"), row("metadata", "status"))
        recorder.picked = setOf(RANK, listOf("tail"))

        rightClick(row("metadata", "identity"))
        rule.onNodeWithText("Add 3 selected fields to notes").assertExists()
        rule.onNodeWithText("Add 2 fields to notes").assertDoesNotExist()
        chooseAndAdd("Add 3 selected fields to notes")
        assertEquals(listOf(listOf(IDENTITY, SOURCE, STATUS)), recorder.added)
    }

    // ── Shift-click ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun shiftClickAfterAPlainClickPicksTheRowsBetween() {
        show()

        rule.modifiedClick(row("metadata", "identity"))
        rule.modifiedClick(row("metadata", "status"), shift = true)

        assertEquals(setOf(IDENTITY, SOURCE, STATUS), recorder.picked)
        assertEquals(listOf(IDENTITY, SOURCE, STATUS), recorder.picked.toList(), "in document order")
    }

    @Test
    fun aShiftRangeOverAContainersWholeSubtreePicksTheContainer() {
        show()

        rule.modifiedClick(row("metadata"))
        rule.modifiedClick(row("metadata", "rank"), shift = true)

        assertEquals(setOf(METADATA), recorder.picked)
    }

    @Test
    fun aShiftRangeOverPartOfAContainerPicksTheFieldsItCovers() {
        show()

        rule.modifiedClick(row("id"))
        rule.modifiedClick(row("metadata", "owner"), shift = true)

        assertEquals(setOf(listOf("id"), IDENTITY, SOURCE, STATUS, OWNER), recorder.picked)
    }

    @Test
    fun shiftClickUpwardsFromTheAnchorPicksTheSameRange() {
        show()

        rule.modifiedClick(row("metadata", "status"))
        rule.modifiedClick(row("metadata", "identity"), shift = true)

        assertEquals(setOf(IDENTITY, SOURCE, STATUS), recorder.picked)
    }

    @Test
    fun aShiftClickReplacesTheOldPickButCtrlShiftAddsToIt() {
        show()
        rule.modifiedClick(row("tail"), ctrl = true)
        assertEquals(setOf(listOf("tail")), recorder.picked)
        recorder.anchor = IDENTITY

        rule.modifiedClick(row("metadata", "source"), ctrl = true, shift = true)
        assertEquals(setOf(listOf("tail"), IDENTITY, SOURCE), recorder.picked, "Ctrl+Shift adds the range")

        recorder.anchor = IDENTITY
        rule.modifiedClick(row("metadata", "status"), shift = true)
        assertEquals(setOf(IDENTITY, SOURCE, STATUS), recorder.picked, "a plain Shift replaces the pick")
    }

    @Test
    fun ctrlClickStillTogglesAndMovesTheAnchor() {
        show()

        rule.modifiedClick(row("metadata", "identity"), ctrl = true)
        rule.modifiedClick(row("metadata", "owner"), ctrl = true)
        assertEquals(setOf(IDENTITY, OWNER), recorder.picked)
        assertEquals(OWNER, recorder.anchor)
        rule.modifiedClick(row("metadata", "identity"), ctrl = true)
        assertEquals(setOf(OWNER), recorder.picked)
    }

    @Test
    fun shiftClickWithoutAnAnchorDoesNotPickAnything() {
        show()

        rule.modifiedClick(row("metadata", "status"), shift = true)

        assertTrue(recorder.picked.isEmpty())
        assertNull(recorder.anchor)
    }

    @Test
    fun shiftClickWithTextSelectedExtendsTheSelectionInsteadOfPicking() {
        show()
        drag(row("metadata", "identity"), row("metadata", "source"))

        rule.modifiedClick(row("metadata", "status"), shift = true, inset = KEY_INSET + 12f)
        assertTrue(recorder.picked.isEmpty(), "no pick while text is selected")
        rightClick(row("metadata", "source"))

        rule.onNodeWithText("Add 3 selected fields to notes").assertExists()
    }

    @Test
    fun aPlainClickOnARowDoesNotOpenAMenuOrPick() {
        show()

        rule.modifiedClick(row("metadata", "identity"))

        assertTrue(recorder.picked.isEmpty())
        assertEquals(IDENTITY, assertNotNull(recorder.anchor))
        rule.onNodeWithText("Add field to notes").assertDoesNotExist()
    }
}

/** The same in the log view: the tree under a row, Message details, and the log row's own menu. */
@OptIn(ExperimentalTestApi::class)
class RecordFieldTextSelectionLogViewUiTest : SharedNoteUiTestBase() {
    private fun jsonRow(id: Int, json: String) = LogEntry(
        id, "10:00:%02d.000".format(id), LogLevel.I, "HCI", "message $id",
        recordMetadata = LogRecordMetadata(rawRecord = json, usesProfilePresentation = true),
    )

    private fun setupJson() = setup(1100, 760) { s ->
        s.tabs = s.tabs.map { tab ->
            if (tab.id == "a") mkTab("a", "a.log", listOf(jsonRow(1, RECORD_SELECT), jsonRow(2, RECORD_ONE))).copy(sourcePath = tab.sourcePath) else tab
        }
    }

    private fun details(vararg path: String) = nodeTag("1.details", *path)

    /** Opens the record under row 1 and its `metadata` (the first level is closed under a row). */
    private fun openRecordUnderRow() {
        rule.onNodeWithTag("structured-record-disclosure-1").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(nodeTag("1.root", "metadata", toggle = true)).performClick()
        rule.waitForIdle()
    }

    private fun under(vararg path: String) = nodeTag("1.root", *path)

    private fun rightClickRow(tag: String) {
        rule.onNodeWithTag(tag).performMouseInput { rightClick(Offset(width - 8f, center.y)) }
        rule.waitForIdle()
    }

    private fun addedPaths() = assertNotNull(state.addAnnRequest).recordFields!!.map { it.path }

    @Test
    fun aSelectionOverThreeFieldsInMessageDetailsAddsThemToTheNote() {
        setupJson()
        rule.onNodeWithTag("log-row-a:main-1").performClick()
        rule.waitForIdle()

        rule.dragSelect(details("metadata", "identity"), KEY_INSET, details("metadata", "status"), KEY_INSET + 12f)
        rightClickRow(details("metadata", "source"))

        rule.onNodeWithText("Add 3 selected fields to notes").assertExists()
        rule.onNodeWithText("Add 3 selected fields to notes").performClick()
        rule.waitForIdle()

        assertEquals(listOf(IDENTITY, SOURCE, STATUS), addedPaths())
        assertEquals("a", state.addAnnRequest!!.targetTabId)
    }

    @Test
    fun aSelectionOverTheSizeCapShowsTheNoticeAndAddsNothing() {
        // Nine strings of 7,500 characters (the tree takes at most 8 KiB a string): over the 64 KiB a note keeps.
        val big = "x".repeat(7_500)
        val record = (1..9).joinToString(",", "{", "}") { """"f$it":"$big"""" }
        setup(1100, 760) { s ->
            s.tabs = s.tabs.map { tab ->
                if (tab.id == "a") mkTab("a", "a.log", listOf(jsonRow(1, record))).copy(sourcePath = tab.sourcePath) else tab
            }
        }
        // Under the row a long string shows as a two-line summary, so nine of them fit on screen.
        rule.onNodeWithTag("structured-record-disclosure-1").performClick()
        rule.waitForIdle()

        rule.dragSelect(under("f1"), KEY_INSET, under("f9"), KEY_INSET + 12f)
        rightClickRow(under("f5"))
        rule.onNodeWithText("Add 9 selected fields to notes").performClick()
        rule.waitForIdle()

        assertNull(state.addAnnRequest)
        assertEquals("Too much to add", state.pendingDiagramNotice?.title)
    }

    @Test
    fun theTreeUnderALogRowDoesTheSame() {
        setupJson()
        openRecordUnderRow()

        rule.dragSelect(under("metadata", "identity"), KEY_INSET, under("metadata", "status"), KEY_INSET + 12f)
        rightClickRow(under("metadata", "source"))
        assertNull(state.ctx, "the log row's own menu does not open under the field menu")

        rule.onNodeWithText("Add 3 selected fields to notes").performClick()
        rule.waitForIdle()
        assertEquals(listOf(IDENTITY, SOURCE, STATUS), addedPaths())
    }

    @Test
    fun theLogRowsMenuOffersTheSelectedFieldsOfItsRecord() {
        setupJson()
        openRecordUnderRow()
        rule.dragSelect(under("metadata", "identity"), KEY_INSET, under("metadata", "status"), KEY_INSET + 12f)

        // The row's own right-click (at its start, above the tree) takes focus, which drops the tree's selection; the menu has it anyway.
        rule.onNodeWithTag("log-row-a:main-1").performMouseInput { rightClick(Offset(120f, 8f)) }
        rule.waitForIdle()
        assertNotNull(state.ctx, "the log row's menu opened")

        val entry = assertNotNull(state.recordFieldsMenuEntry("a", 1))
        assertEquals("Add 3 selected fields to notes", entry.label)
        entry.onClick()
        rule.waitForIdle()

        assertNull(state.ctx)
        assertEquals(listOf(IDENTITY, SOURCE, STATUS), addedPaths())
    }

    @Test
    fun theLogRowsMenuOffersThePickToo() {
        setupJson()
        openRecordUnderRow()
        rule.modifiedClick(under("metadata", "identity"), ctrl = true)
        rule.modifiedClick(under("tail"), ctrl = true)

        rule.onNodeWithTag("log-row-a:main-1").performMouseInput { rightClick(Offset(120f, 8f)) }
        rule.waitForIdle()

        val entry = assertNotNull(state.recordFieldsMenuEntry("a", 1))
        assertEquals("Add 2 selected fields to notes", entry.label)
        entry.onClick()
        rule.waitForIdle()
        assertEquals(listOf(IDENTITY, listOf("tail")), addedPaths())
    }

    @Test
    fun theLogRowsMenuOffersNothingWithoutASelectionOrPickAndForAnotherRecord() {
        setupJson()
        openRecordUnderRow()
        rule.onNodeWithTag("log-row-a:main-1").performMouseInput { rightClick(Offset(120f, 8f)) }
        rule.waitForIdle()
        assertNull(state.recordFieldsMenuEntry("a", 1))

        rule.dragSelect(under("metadata", "identity"), KEY_INSET, under("metadata", "status"), KEY_INSET + 12f)
        rule.onNodeWithTag("log-row-a:main-1").performMouseInput { rightClick(Offset(120f, 8f)) }
        rule.waitForIdle()
        assertNotNull(state.recordFieldsMenuEntry("a", 1))
        assertNull(state.recordFieldsMenuEntry("a", 2), "the selection belongs to the first record")
    }

    @Test
    fun aPlainClickForgetsTheSelectionAThePreviousRightPressHeld() {
        setupJson()
        openRecordUnderRow()
        rule.dragSelect(under("metadata", "identity"), KEY_INSET, under("metadata", "status"), KEY_INSET + 12f)
        rule.onNodeWithTag("log-row-a:main-1").performMouseInput { rightClick(Offset(120f, 8f)) }
        rule.waitForIdle()
        assertNotNull(state.recordFieldsMenuEntry("a", 1))

        rule.onNodeWithTag("log-row-a:main-2").performClick()
        rule.waitForIdle()

        assertNull(state.recordFieldsMenuEntry("a", 1))
    }

    @Test
    fun shiftClickRangeInTheLogViewPicksTheFieldsAndTheyAddThroughTheRowMenu() {
        setupJson()
        rule.onNodeWithTag("log-row-a:main-1").performClick()
        rule.waitForIdle()

        rule.modifiedClick(details("metadata", "identity"))
        rule.modifiedClick(details("metadata", "status"), shift = true)

        assertEquals(setOf(IDENTITY, SOURCE, STATUS), state.recordFieldSelection!!.paths)
        rightClickRow(details("metadata", "source"))
        rule.onNodeWithText("Add 3 fields to notes").assertExists()
    }
}
