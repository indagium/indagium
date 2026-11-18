@file:Suppress("MagicNumber")
@file:OptIn(ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
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
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlin.math.abs
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

/**
 * A text selection from inside the row [fromTag] to inside [toTag]: a drag within the first row, then a Shift-click in the second (a drag that
 * leaves its row picks the rows instead of selecting text).
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeContentTestRule.selectTextAcross(fromTag: String, toTag: String, toInset: Float = KEY_INSET + 12f) {
    dragSelect(fromTag, KEY_INSET, fromTag, KEY_INSET + 30f)
    modifiedClick(toTag, shift = true, inset = toInset)
}

/** A point [inset] px into the field row [tag], at its middle. */
internal fun ComposeContentTestRule.rowPoint(tag: String, inset: Float = KEY_INSET): Offset {
    val bounds = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    return Offset(bounds.left + inset, bounds.center.y)
}

/** A mouse drag over field rows in steps, with the pointer pressed between [press] and [release] (Ctrl held for the whole drag when [ctrl]). */
@OptIn(ExperimentalTestApi::class)
internal class RowDragger(private val rule: ComposeContentTestRule, private val ctrl: Boolean = false) {
    private val root get() = rule.onAllNodes(isRoot())[0]

    fun press(at: Offset) {
        if (ctrl) root.performKeyInput { keyDown(Key.CtrlLeft) }
        root.performMouseInput {
            moveTo(at)
            press()
        }
        rule.waitForIdle()
    }

    fun moveTo(at: Offset) {
        root.performMouseInput { moveTo(at) }
        rule.waitForIdle()
    }

    fun release() {
        root.performMouseInput { release() }
        if (ctrl) root.performKeyInput { keyUp(Key.CtrlLeft) }
        rule.waitForIdle()
    }
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

    private fun drag(from: String, to: String) = rule.selectTextAcross(from, to)

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
        rule.dragSelect(hexLine, 4f, hexLine, 40f)
        rule.modifiedClick(row("note"), shift = true, inset = KEY_INSET + 12f)
        rightClick(row("note"))

        rule.onNodeWithText("Add 2 selected fields to notes").assertExists()
        chooseAndAdd("Add 2 selected fields to notes")
        assertEquals(listOf(listOf(listOf("blob"), listOf("note"))), recorder.added)
    }

    @Test
    fun theMenuOfAHexDumpInsideTheSelectionOffersTheSelectionToo() {
        show(RECORD_BLOB)

        rule.selectTextAcross(row("id"), "structured-record-hex-line-1-1-root-blob-0", 40f)
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
    fun aPlainClickOnARowPicksItAndOpensNoMenu() {
        show()

        rule.modifiedClick(row("metadata", "identity"))

        assertEquals(setOf(IDENTITY), recorder.picked)
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

        rule.selectTextAcross(details("metadata", "identity"), details("metadata", "status"))
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

        val drag = RowDragger(rule)
        drag.press(rule.rowPoint(under("f1")))
        drag.moveTo(rule.rowPoint(under("f9")))
        drag.release()
        rightClickRow(under("f5"))
        rule.onNodeWithText("Add 9 fields to notes").performClick()
        rule.waitForIdle()

        assertNull(state.addAnnRequest)
        assertEquals("Too much to add", state.pendingDiagramNotice?.title)
    }

    @Test
    fun theTreeUnderALogRowDoesTheSame() {
        setupJson()
        openRecordUnderRow()

        // A drag across rows picks them (a text selection cannot run across rows under a log row: a press there gives the log view focus).
        val drag = RowDragger(rule)
        drag.press(rule.rowPoint(under("metadata", "identity")))
        drag.moveTo(rule.rowPoint(under("metadata", "status")))
        drag.release()
        rightClickRow(under("metadata", "source"))
        assertNull(state.ctx, "the log row's own menu does not open under the field menu")

        rule.onNodeWithText("Add 3 fields to notes").performClick()
        rule.waitForIdle()
        assertEquals(listOf(IDENTITY, SOURCE, STATUS), addedPaths())
    }

    @Test
    fun theLogRowsMenuOffersTheSelectedFieldsOfItsRecord() {
        setupJson()
        openRecordUnderRow()
        rule.dragSelect(under("metadata", "identity"), KEY_INSET, under("metadata", "identity"), KEY_INSET + 40f)

        // The row's own right-click (at its start, above the tree) takes focus, which drops the tree's selection; the menu has it anyway.
        rule.onNodeWithTag("log-row-a:main-1").performMouseInput { rightClick(Offset(120f, 8f)) }
        rule.waitForIdle()
        assertNotNull(state.ctx, "the log row's menu opened")

        val entry = assertNotNull(state.recordFieldsMenuEntry("a", 1))
        assertEquals("Add selected field to notes", entry.label)
        entry.onClick()
        rule.waitForIdle()

        assertNull(state.ctx)
        assertEquals(listOf(IDENTITY), addedPaths())
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

        rule.dragSelect(under("metadata", "identity"), KEY_INSET, under("metadata", "identity"), KEY_INSET + 40f)
        rule.onNodeWithTag("log-row-a:main-1").performMouseInput { rightClick(Offset(120f, 8f)) }
        rule.waitForIdle()
        assertNotNull(state.recordFieldsMenuEntry("a", 1))
        assertNull(state.recordFieldsMenuEntry("a", 2), "the selection belongs to the first record")
    }

    @Test
    fun aPlainClickForgetsTheSelectionAThePreviousRightPressHeld() {
        setupJson()
        openRecordUnderRow()
        rule.dragSelect(under("metadata", "identity"), KEY_INSET, under("metadata", "identity"), KEY_INSET + 40f)
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

// ── Field rows behave like log rows ──────────────────────────────────────────────────────────────────

/** What a recording stand-in for the log view's field actions saw; its pick and anchor behave like the app's. */
internal class FieldActionsRecorder : RecordFieldActions {
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

/** A key press (with Shift and/or the platform's copy modifier held) on whatever holds keyboard focus. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeContentTestRule.pressKey(key: Key, shift: Boolean = false, action: Boolean = false) {
    val modifiers = listOfNotNull(Key.ShiftLeft.takeIf { shift }, (if (isMacOs) Key.MetaLeft else Key.CtrlLeft).takeIf { action })
    onAllNodes(isRoot())[0].performKeyInput {
        modifiers.forEach { keyDown(it) }
        keyDown(key)
        keyUp(key)
        modifiers.reversed().forEach { keyUp(it) }
    }
    waitForIdle()
}

/** A plain click on the field row [tag], at its key text. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeContentTestRule.clickRow(tag: String) = modifiedClick(tag)

/** The pixel at the left edge of the field row [tag], inside its own background, to tell a tinted row from a plain one. */
internal fun ComposeContentTestRule.rowTint(tag: String) =
    onNodeWithTag(tag).captureToImage().toPixelMap().let { it[2, it.height / 2] }

/** Whether the colour is [other] to within the rounding of a screen capture. */
internal fun Color.isCloseTo(other: Color): Boolean =
    abs(red - other.red) < 0.03f && abs(green - other.green) < 0.03f && abs(blue - other.blue) < 0.03f

/** Takes the pointer off every row (a hovered row shows the hover tint, which would otherwise look like a mark). */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeContentTestRule.pointerAway() {
    onAllNodes(isRoot())[0].performMouseInput { moveTo(Offset(2f, 2f)) }
    waitForIdle()
}

/** Clicking a field row picks it, Shift/Ctrl-click extend it, the arrow keys, Left/Right and Copy work on it, on the field-actions stand-in. */
@OptIn(ExperimentalTestApi::class)
class RecordFieldRowSelectionUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val recorder = FieldActionsRecorder()

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

    /** Another click that is not part of the first one's double-click. */
    private fun later() = rule.mainClock.advanceTimeBy(2_000)

    @Test
    fun aPlainClickPicksThatRowAndTintsItAndAnotherClickReplacesIt() {
        show()

        rule.clickRow(row("metadata", "identity"))
        assertEquals(setOf(IDENTITY), recorder.picked)
        assertEquals(IDENTITY, recorder.anchor)
        rule.pointerAway()
        assertTrue(rule.rowTint(row("metadata", "identity")) != rule.rowTint(row("metadata", "source")), "the picked row is tinted")

        later()
        rule.clickRow(row("metadata", "source"))
        assertEquals(setOf(SOURCE), recorder.picked, "the earlier pick is gone")
        assertEquals(SOURCE, recorder.anchor)
        rule.pointerAway()
        assertTrue(rule.rowTint(row("metadata", "identity")) == rule.rowTint(row("metadata", "owner")), "the first row is plain again")
        assertTrue(rule.rowTint(row("metadata", "source")) != rule.rowTint(row("metadata", "owner")))
    }

    @Test
    fun aPickedRowHasTheSelectionFillAndAHoveredRowTheHoverTint() {
        show()
        val plain = rule.rowTint(row("metadata", "owner"))

        rule.onNodeWithTag(row("metadata", "owner")).performMouseInput { moveTo(Offset(KEY_INSET, center.y)) }
        rule.waitForIdle()
        val hovered = rule.rowTint(row("metadata", "owner"))
        assertTrue(hovered != plain, "hover tints the row")
        assertTrue(hovered.isCloseTo(LIGHT_THEME.hv.compositeOver(plain)), "with the app's hover tint: $hovered")

        rule.clickRow(row("metadata", "owner"))
        rule.pointerAway()
        val picked = rule.rowTint(row("metadata", "owner"))
        assertTrue(picked.isCloseTo(LIGHT_THEME.sl.compositeOver(plain)), "a picked row has the selection fill: $picked")
        assertTrue(picked != hovered, "and it is not the hover tint")
    }

    @Test
    fun clickingAPickedRowAgainKeepsItPicked() {
        show()

        rule.clickRow(row("metadata", "identity"))
        later()
        rule.clickRow(row("metadata", "identity"))

        assertEquals(setOf(IDENTITY), recorder.picked)
    }

    @Test
    fun aPlainClickOnOneOfSeveralPickedRowsPicksJustThatRow() {
        show()
        rule.modifiedClick(row("metadata", "identity"), ctrl = true)
        rule.modifiedClick(row("metadata", "source"), ctrl = true)
        assertEquals(setOf(IDENTITY, SOURCE), recorder.picked)

        later()
        rule.clickRow(row("metadata", "source"))

        assertEquals(setOf(SOURCE), recorder.picked)
    }

    @Test
    fun aClickInTheValueAreaOfAHexDumpOrAMultiLineStringPicksItsField() {
        show(RECORD_BLOB)

        rule.onNodeWithTag("structured-record-hex-line-1-1-root-blob-0").performMouseInput { click(Offset(width * 0.3f, center.y)) }
        rule.waitForIdle()
        assertEquals(setOf(listOf("blob")), recorder.picked)

        later()
        rule.onNodeWithTag("structured-record-string-line-1-1-root-note-1").performMouseInput { click(Offset(width * 0.3f, center.y)) }
        rule.waitForIdle()
        assertEquals(setOf(listOf("note")), recorder.picked)
    }

    @Test
    fun aDoubleClickPicksAnExpandableRowAndOpensOrClosesIt() {
        show()
        rule.onNodeWithTag(row("metadata", "identity")).assertExists()

        rule.onNodeWithTag(row("metadata")).performMouseInput { doubleClick(Offset(KEY_INSET, center.y)) }
        rule.waitForIdle()

        assertEquals(setOf(METADATA), recorder.picked, "the first click picked it")
        rule.onNodeWithTag(row("metadata", "identity")).assertDoesNotExist()

        later()
        rule.onNodeWithTag(row("metadata")).performMouseInput { doubleClick(Offset(KEY_INSET, center.y)) }
        rule.waitForIdle()
        rule.onNodeWithTag(row("metadata", "identity")).assertExists()
    }

    @Test
    fun aDragWithinOneRowStillSelectsTextDropsThePickAndCopyCopiesTheText() {
        show()
        rule.clickRow(row("metadata", "identity"))
        assertEquals(setOf(IDENTITY), recorder.picked)

        rule.dragSelect(row("metadata", "source"), KEY_INSET, row("metadata", "source"), KEY_INSET + 40f)

        assertTrue(recorder.picked.isEmpty(), "a drag inside a row is text selection, not a row click")
        rightClick(row("metadata", "source"))
        rule.onNodeWithText("Copy").performClick()
        rule.waitForIdle()
        assertTrue(recorder.copied.single().isNotEmpty(), "the selected text is what Copy copies")
    }

    @Test
    fun aClickLeavesNoStrayTextSelectionBehind() {
        show()
        rule.selectTextAcross(row("metadata", "identity"), row("metadata", "source"))

        rule.clickRow(row("metadata", "status"))
        rightClick(row("metadata", "status"))

        rule.onNodeWithText("Copy").assertDoesNotExist()
        rule.onNodeWithText("Copy value").assertExists()
    }

    // ── Keyboard ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun upAndDownMovePickToThePreviousAndNextVisibleRow() {
        show()
        rule.clickRow(row("metadata", "source"))

        rule.pressKey(Key.DirectionDown)
        assertEquals(setOf(STATUS), recorder.picked)
        rule.pressKey(Key.DirectionUp)
        rule.pressKey(Key.DirectionUp)
        assertEquals(setOf(IDENTITY), recorder.picked)
        rule.pressKey(Key.DirectionUp)
        assertEquals(setOf(METADATA), recorder.picked, "into the row of the container above")
        rule.pressKey(Key.DirectionUp)
        rule.pressKey(Key.DirectionUp)
        assertEquals(setOf(listOf("id")), recorder.picked, "and stays on the first row")
    }

    @Test
    fun shiftUpAndDownExtendThePickFromTheAnchor() {
        show()
        rule.clickRow(row("metadata", "source"))

        rule.pressKey(Key.DirectionDown, shift = true)
        assertEquals(setOf(SOURCE, STATUS), recorder.picked)
        rule.pressKey(Key.DirectionDown, shift = true)
        assertEquals(setOf(SOURCE, STATUS, OWNER), recorder.picked)
        rule.pressKey(Key.DirectionUp, shift = true)
        assertEquals(setOf(SOURCE, STATUS), recorder.picked)
        rule.pressKey(Key.DirectionUp, shift = true)
        rule.pressKey(Key.DirectionUp, shift = true)
        assertEquals(setOf(IDENTITY, SOURCE), recorder.picked, "past the anchor, upwards")
        assertEquals(SOURCE, recorder.anchor)
    }

    @Test
    fun leftClosesAnOpenRowAndRightOpensItThenStepsIntoItsFirstField() {
        show()
        rule.clickRow(row("metadata"))

        rule.pressKey(Key.DirectionLeft)
        rule.onNodeWithTag(row("metadata", "identity")).assertDoesNotExist()
        assertEquals(setOf(METADATA), recorder.picked, "closing keeps the row picked")
        rule.pressKey(Key.DirectionRight)
        rule.onNodeWithTag(row("metadata", "identity")).assertExists()
        assertEquals(setOf(METADATA), recorder.picked)
        rule.pressKey(Key.DirectionRight)
        assertEquals(setOf(IDENTITY), recorder.picked, "on an open container Right goes to its first field")
    }

    @Test
    fun leftOnAFieldStepsToItsParentAndOnTopLevelOrLeafRowsRightDoesNothing() {
        show()
        rule.clickRow(row("metadata", "status"))

        rule.pressKey(Key.DirectionRight)
        assertEquals(setOf(STATUS), recorder.picked, "a plain field does not open")
        rule.pressKey(Key.DirectionLeft)
        assertEquals(setOf(METADATA), recorder.picked, "Left on a child goes to its parent")
        rule.pressKey(Key.DirectionUp)
        rule.pressKey(Key.DirectionLeft)
        assertEquals(setOf(listOf("id")), recorder.picked, "a top-level field has no parent to go to")
    }

    @Test
    fun copyCopiesThePickedRowsAsPathAndValueLinesInDocumentOrder() {
        show()
        rule.clickRow(row("metadata", "source"))
        rule.pressKey(Key.DirectionDown, shift = true)
        rule.modifiedClick(row("tail"), ctrl = true)

        rule.pressKey(Key.C, action = true)

        assertEquals(listOf("metadata.source: phone\nmetadata.status: ok\ntail: true"), recorder.copied)
    }

    @Test
    fun copyWithTextSelectedCopiesTheTextEvenWithARowPicked() {
        show()
        rule.onNodeWithTag(row("metadata", "source")).performMouseInput { doubleClick(Offset(KEY_INSET + 6f, center.y)) }
        rule.waitForIdle()
        assertEquals(setOf(SOURCE), recorder.picked, "the double-click's first click picked the row")

        rule.pressKey(Key.C, action = true)

        assertEquals(listOf("source"), recorder.copied, "the selected word, not a path: value line")
    }

    @Test
    fun theKeysDoNothingWithoutAPick() {
        show()
        rule.clickRow(row("metadata", "source"))
        recorder.picked = emptySet()

        rule.pressKey(Key.DirectionDown)
        rule.pressKey(Key.C, action = true)

        assertTrue(recorder.picked.isEmpty())
        assertTrue(recorder.copied.isEmpty())
    }

    // ── Right-click ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun aRightClickOnAnUnpickedRowPicksItFirstAndOffersTheSingleFieldMenu() {
        show()
        rule.clickRow(row("tail"))

        rightClick(row("metadata", "identity"))

        assertEquals(setOf(IDENTITY), recorder.picked)
        rule.onNodeWithText("Add field to notes").performClick()
        rule.waitForIdle()
        assertEquals(listOf(listOf(IDENTITY)), recorder.added)
    }

    @Test
    fun aRightClickOnAPickedRowActsOnTheWholePick() {
        show()
        rule.clickRow(row("metadata", "identity"))
        rule.modifiedClick(row("metadata", "source"), ctrl = true)
        rule.modifiedClick(row("tail"), ctrl = true)

        rightClick(row("metadata", "source"))

        assertEquals(setOf(IDENTITY, SOURCE, listOf("tail")), recorder.picked)
        rule.onNodeWithText("Add 3 fields to notes").performClick()
        rule.waitForIdle()
        assertEquals(setOf(IDENTITY, SOURCE, listOf("tail")), recorder.added.single().toSet())
    }
}

/** Pressing on a field row and dragging across others picks the rows between, live, on the field-actions stand-in. */
@OptIn(ExperimentalTestApi::class)
class RecordFieldRowDragUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val recorder = FieldActionsRecorder()

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

    private fun at(vararg path: String, inset: Float = KEY_INSET) = rule.rowPoint(row(*path), inset)

    private fun rightClick(tag: String) {
        rule.onNodeWithTag(tag).performMouseInput { rightClick(Offset(width - 8f, center.y)) }
        rule.waitForIdle()
    }

    @Test
    fun draggingDownFromOneRowPicksTheRowsBetweenLiveAndBackUpPastTheStartFollows() {
        show()
        val drag = RowDragger(rule)

        drag.press(at("metadata", "identity"))
        drag.moveTo(at("metadata", "source"))
        assertEquals(setOf(IDENTITY, SOURCE), recorder.picked, "live, while the button is down")
        drag.moveTo(at("metadata", "status"))
        assertEquals(listOf(IDENTITY, SOURCE, STATUS), recorder.picked.toList())
        drag.moveTo(at("metadata", "identity"))
        assertEquals(setOf(IDENTITY), recorder.picked, "back on the pressed row it is just that row")
        drag.moveTo(at("id"))
        assertEquals(setOf(listOf("id"), IDENTITY), recorder.picked, "past the start, upwards (the container above is only partly covered)")
        drag.moveTo(at("metadata", "status"))
        assertEquals(setOf(IDENTITY, SOURCE, STATUS), recorder.picked)
        drag.release()

        assertEquals(setOf(IDENTITY, SOURCE, STATUS), recorder.picked)
        assertEquals(IDENTITY, recorder.anchor, "the pressed row is the anchor")
    }

    @Test
    fun shiftDownAfterADragExtendsFromTheAnchorAndTheTreeHasKeyboardFocus() {
        show()
        val drag = RowDragger(rule)
        drag.press(at("metadata", "identity"))
        drag.moveTo(at("metadata", "status"))
        drag.release()

        rule.pressKey(Key.DirectionDown, shift = true)

        assertEquals(setOf(IDENTITY, SOURCE, STATUS, OWNER), recorder.picked)
        rule.pressKey(Key.DirectionUp, shift = true)
        rule.pressKey(Key.DirectionUp, shift = true)
        rule.pressKey(Key.DirectionUp, shift = true)
        assertEquals(setOf(IDENTITY), recorder.picked, "Shift+Up shrinks back to the anchor")
    }

    @Test
    fun aShiftClickAfterADragRangesFromThePressedRow() {
        show()
        val drag = RowDragger(rule)
        drag.press(at("metadata", "source"))
        drag.moveTo(at("metadata", "status"))
        drag.release()

        rule.modifiedClick(row("metadata", "rank"), shift = true)

        assertEquals(setOf(SOURCE, STATUS, OWNER, RANK), recorder.picked)
    }

    @Test
    fun aDragWithinOneRowIsTextSelectionAndPicksNothing() {
        show()
        rule.clickRow(row("tail"))
        assertEquals(setOf(listOf("tail")), recorder.picked)

        val drag = RowDragger(rule)
        drag.press(at("metadata", "identity"))
        drag.moveTo(at("metadata", "identity", inset = KEY_INSET + 24f))
        drag.moveTo(at("metadata", "identity", inset = KEY_INSET + 60f))
        assertTrue(recorder.picked.isEmpty(), "the earlier pick makes way for the text")
        drag.release()

        assertTrue(recorder.picked.isEmpty())
        rightClick(row("metadata", "identity"))
        rule.onNodeWithText("Copy").performClick()
        rule.waitForIdle()
        val copied = recorder.copied.single()
        assertTrue(copied.isNotEmpty() && copied.length < "identity: abc".length, "just the part that was dragged over: '$copied'")
    }

    @Test
    fun enteringAnotherRowDropsTheTextTheDragHadStartedToSelect() {
        show()
        val drag = RowDragger(rule)
        drag.press(at("metadata", "identity"))
        drag.moveTo(at("metadata", "identity", inset = KEY_INSET + 40f))
        drag.moveTo(at("metadata", "source"))
        drag.release()

        assertEquals(setOf(IDENTITY, SOURCE), recorder.picked)
        rightClick(row("metadata", "owner"))
        rule.onNodeWithText("Copy").assertDoesNotExist()
        rule.onNodeWithText("Copy value").assertExists()
    }

    @Test
    fun aDragFromAHexDumpLineToTheNextFieldPicksBothFields() {
        show(RECORD_BLOB)
        val drag = RowDragger(rule)

        drag.press(rule.rowPoint("structured-record-hex-line-1-1-root-blob-0", inset = 4f))
        drag.moveTo(rule.rowPoint("structured-record-hex-line-1-1-root-blob-1", inset = 40f))
        assertTrue(recorder.picked.isEmpty(), "still the dump's own field: text selection")
        drag.moveTo(at("note"))
        drag.release()

        assertEquals(setOf(listOf("blob"), listOf("note")), recorder.picked)
        assertEquals(listOf("blob"), recorder.anchor)
    }

    @Test
    fun aDragFromTheNextFieldUpIntoAHexDumpAlsoPicksBoth() {
        show(RECORD_BLOB)
        val drag = RowDragger(rule)

        drag.press(at("note"))
        drag.moveTo(rule.rowPoint("structured-record-hex-line-1-1-root-blob-0", inset = 40f))
        drag.release()

        assertEquals(setOf(listOf("blob"), listOf("note")), recorder.picked)
    }

    @Test
    fun aCtrlDragAddsTheRangeToTheExistingPickAndAPlainOneReplacesIt() {
        show()
        rule.modifiedClick(row("tail"), ctrl = true)
        assertEquals(setOf(listOf("tail")), recorder.picked)

        val ctrlDrag = RowDragger(rule, ctrl = true)
        ctrlDrag.press(at("metadata", "identity"))
        ctrlDrag.moveTo(at("metadata", "status"))
        assertEquals(setOf(listOf("tail"), IDENTITY, SOURCE, STATUS), recorder.picked)
        ctrlDrag.moveTo(at("metadata", "source"))
        assertEquals(setOf(listOf("tail"), IDENTITY, SOURCE), recorder.picked, "the range follows the pointer, the old pick stays")
        ctrlDrag.release()
        assertEquals(setOf(listOf("tail"), IDENTITY, SOURCE), recorder.picked)

        val plain = RowDragger(rule)
        plain.press(at("metadata", "owner"))
        plain.moveTo(at("metadata", "rank"))
        plain.release()
        assertEquals(setOf(OWNER, RANK), recorder.picked, "a plain drag starts a new pick")
    }

    @Test
    fun aDragThatStartsOnTheChevronIsNoRowDrag() {
        show()
        val drag = RowDragger(rule)

        drag.press(at("metadata", "identity", inset = 6f))
        drag.moveTo(at("metadata", "status"))
        drag.release()

        assertTrue(recorder.picked.isEmpty(), "picked: ${recorder.picked}")
    }

    @Test
    fun rowsHiddenInAClosedContainerAreNotPicked() {
        show()
        rule.onNodeWithTag(nodeTag("1.root", "metadata", toggle = true)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(row("metadata", "identity")).assertDoesNotExist()

        val drag = RowDragger(rule)
        drag.press(at("id"))
        drag.moveTo(at("tail"))
        drag.release()

        assertEquals(listOf(listOf("id"), METADATA, listOf("tail")), recorder.picked.toList())
    }

    @Test
    fun aDragEndingOutsideTheTreePicksUpToItsLastRow() {
        show()
        val drag = RowDragger(rule)
        drag.press(at("metadata", "owner"))
        drag.moveTo(Offset(100f, 340f))
        drag.release()

        assertEquals(setOf(OWNER, RANK, listOf("tail")), recorder.picked)
    }
}

/** The same in the log view: the tree under a row and Message details, with the log view's own keys and selection around them. */
@OptIn(ExperimentalTestApi::class)
class RecordFieldRowSelectionLogViewUiTest : SharedNoteUiTestBase() {
    private fun jsonRow(id: Int, json: String) = LogEntry(
        id, "10:00:%02d.000".format(id), LogLevel.I, "HCI", "message $id",
        recordMetadata = LogRecordMetadata(rawRecord = json, usesProfilePresentation = true),
    )

    private fun setupJson() = setup(1100, 760) { s ->
        s.tabs = s.tabs.map { tab ->
            if (tab.id == "a") mkTab("a", "a.log", listOf(jsonRow(1, RECORD_SELECT), jsonRow(2, RECORD_ONE))).copy(sourcePath = tab.sourcePath) else tab
        }
    }

    private fun under(vararg path: String) = nodeTag("1.root", *path)

    private fun details(vararg path: String) = nodeTag("1.details", *path)

    /** Opens the record under row 1 and its `metadata` (the first level is closed under a row). */
    private fun openRecordUnderRow() {
        rule.onNodeWithTag("structured-record-disclosure-1").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(nodeTag("1.root", "metadata", toggle = true)).performClick()
        rule.waitForIdle()
    }

    private fun picked() = state.recordFieldSelection?.paths

    private fun clickField(tag: String) {
        rule.onNodeWithTag(tag).performMouseInput { click(Offset(KEY_INSET, center.y)) }
        rule.waitForIdle()
    }

    private fun rightClick(tag: String) {
        rule.onNodeWithTag(tag).performMouseInput { rightClick(Offset(width - 8f, center.y)) }
        rule.waitForIdle()
    }

    private fun addedPaths() = assertNotNull(state.addAnnRequest).recordFields!!.map { it.path }

    private fun later() = rule.mainClock.advanceTimeBy(2_000)

    @Test
    fun aClickOnAFieldUnderARowPicksItAndLeavesTheLogRowSelectionAndTheOpenRecordAlone() {
        setupJson()
        rule.onNodeWithTag("log-row-a:main-2").performClick()
        rule.waitForIdle()
        openRecordUnderRow()
        val selectedBefore = state.tab("a")!!.selected

        clickField(under("metadata", "identity"))

        assertEquals(setOf(IDENTITY), picked())
        assertEquals(selectedBefore, state.tab("a")!!.selected, "the log row selection is untouched")
        assertTrue(state.recordExpansion("a").isExpanded(1), "the record stays open")
        later()
        clickField(under("metadata", "source"))
        assertEquals(setOf(SOURCE), picked(), "another click replaces the pick")
        assertEquals(selectedBefore, state.tab("a")!!.selected)
    }

    @Test
    fun aClickOnALogRowClearsThePickedFields() {
        setupJson()
        openRecordUnderRow()
        clickField(under("metadata", "identity"))
        assertNotNull(picked())

        rule.onNodeWithTag("log-row-a:main-2").performClick()
        rule.waitForIdle()

        assertNull(picked())
    }

    @Test
    fun shiftAndCtrlClickStillExtendAndToggleFromAClickedField() {
        setupJson()
        openRecordUnderRow()

        clickField(under("metadata", "identity"))
        rule.modifiedClick(under("metadata", "status"), shift = true)
        assertEquals(setOf(IDENTITY, SOURCE, STATUS), picked())
        rule.modifiedClick(under("tail"), ctrl = true)
        assertEquals(setOf(IDENTITY, SOURCE, STATUS, listOf("tail")), picked())
    }

    @Test
    fun arrowKeysMoveTheFieldPickInsteadOfTheLogRowsAndEscapeHandsThemBack() {
        setupJson()
        rule.onNodeWithTag("log-row-a:main-2").performClick()
        rule.waitForIdle()
        openRecordUnderRow()
        clickField(under("metadata", "identity"))
        val selectedBefore = state.tab("a")!!.selected

        rule.pressKey(Key.DirectionDown)
        assertEquals(setOf(SOURCE), picked())
        rule.pressKey(Key.DirectionDown, shift = true)
        assertEquals(setOf(SOURCE, STATUS), picked())
        rule.pressKey(Key.DirectionUp)
        assertEquals(setOf(SOURCE), picked())
        assertEquals(selectedBefore, state.tab("a")!!.selected, "the log rows did not move")

        rule.pressKey(Key.Escape)
        assertNull(picked())
        rule.pressKey(Key.DirectionUp)
        assertTrue(state.tab("a")!!.selected != selectedBefore, "with nothing picked the arrow keys are the log view's again")
    }

    @Test
    fun leftAndRightFoldTheContainerUnderARow() {
        setupJson()
        openRecordUnderRow()
        clickField(under("metadata"))

        rule.pressKey(Key.DirectionLeft)
        rule.onNodeWithTag(under("metadata", "identity")).assertDoesNotExist()
        rule.pressKey(Key.DirectionRight)
        rule.onNodeWithTag(under("metadata", "identity")).assertExists()
        rule.pressKey(Key.DirectionRight)
        assertEquals(setOf(IDENTITY), picked())
        rule.pressKey(Key.DirectionLeft)
        assertEquals(setOf(METADATA), picked())
    }

    @Test
    fun theKeysAreNotTakenByATreeThatDoesNotHoldFocus() {
        setupJson()
        rule.onNodeWithTag("log-row-a:main-2").performClick()
        rule.waitForIdle()
        val selectedBefore = state.tab("a")!!.selected

        // "Go to rows" marks fields without moving keyboard focus into the tree.
        state.openRecordFields("a", 1, setOf(IDENTITY))
        rule.waitForIdle()
        assertEquals(setOf(IDENTITY), picked())

        rule.pressKey(Key.DirectionUp)

        assertEquals(setOf(IDENTITY), picked(), "the marked fields did not move")
        assertTrue(state.tab("a")!!.selected != selectedBefore, "the log view took the key")
    }

    @Test
    fun copyCopiesThePickedFieldsAsPathAndValueLines() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val previous = runCatching { clipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull()
        try {
            setupJson()
            openRecordUnderRow()
            clickField(under("metadata", "identity"))
            rule.pressKey(Key.DirectionDown, shift = true)
            rule.pressKey(Key.C, action = true)

            assertEquals("metadata.identity: abc\nmetadata.source: phone", clipboard.getData(DataFlavor.stringFlavor))
        } finally {
            previous?.let { clipboard.setContents(StringSelection(it), null) }
        }
    }

    @Test
    fun aRightClickOnAnUnpickedFieldPicksItAndAddsJustThatField() {
        setupJson()
        openRecordUnderRow()
        clickField(under("tail"))

        rightClick(under("metadata", "identity"))
        assertEquals(setOf(IDENTITY), picked())
        assertNull(state.ctx, "the log row's own menu does not open under the field menu")
        rule.onNodeWithText("Add field to notes").performClick()
        rule.waitForIdle()

        assertEquals(listOf(IDENTITY), addedPaths())
    }

    @Test
    fun aRightClickOnAPickedFieldAddsTheWholePick() {
        setupJson()
        openRecordUnderRow()
        clickField(under("metadata", "identity"))
        rule.modifiedClick(under("metadata", "status"), shift = true)

        rightClick(under("metadata", "source"))
        rule.onNodeWithText("Add 3 fields to notes").performClick()
        rule.waitForIdle()

        assertEquals(listOf(IDENTITY, SOURCE, STATUS), addedPaths())
    }

    // ── Message details ──────────────────────────────────────────────────────────────────────────

    @Test
    fun messageDetailsRowsBehaveTheSame() {
        setupJson()
        rule.onNodeWithTag("log-row-a:main-1").performClick()
        rule.waitForIdle()
        val selectedBefore = state.tab("a")!!.selected

        clickField(details("metadata", "identity"))
        assertEquals(setOf(IDENTITY), picked())
        later()
        clickField(details("metadata", "source"))
        assertEquals(setOf(SOURCE), picked())
        rule.modifiedClick(details("metadata", "owner"), shift = true)
        assertEquals(setOf(SOURCE, STATUS, OWNER), picked())

        rule.pressKey(Key.DirectionUp)
        assertEquals(setOf(STATUS), picked(), "Up moves from the cursor, which the Shift-click left on the last row")
        rule.pressKey(Key.DirectionLeft)
        assertEquals(setOf(METADATA), picked())
        rule.pressKey(Key.DirectionLeft)
        rule.onNodeWithTag(details("metadata", "identity")).assertDoesNotExist()
        rule.pressKey(Key.DirectionRight)
        rule.onNodeWithTag(details("metadata", "identity")).assertExists()
        assertEquals(selectedBefore, state.tab("a")!!.selected, "the log row selection never changed")

        rightClick(details("metadata", "owner"))
        assertEquals(setOf(OWNER), picked(), "a right-click on an unpicked row picks it")
        rule.onNodeWithText("Add field to notes").performClick()
        rule.waitForIdle()
        assertEquals(listOf(OWNER), addedPaths())
    }

    // ── Dragging across field rows ───────────────────────────────────────────────────────────────

    private fun drag(from: String, to: String, ctrl: Boolean = false, release: Boolean = true): RowDragger {
        val dragger = RowDragger(rule, ctrl)
        dragger.press(rule.rowPoint(from))
        dragger.moveTo(rule.rowPoint(to))
        if (release) dragger.release()
        return dragger
    }

    @Test
    fun aDragAcrossFieldRowsUnderARowPicksThemAndLeavesTheLogRowSelectionAlone() {
        setupJson()
        rule.onNodeWithTag("log-row-a:main-2").performClick()
        rule.waitForIdle()
        openRecordUnderRow()
        val selectedBefore = state.tab("a")!!.selected

        drag(under("metadata", "identity"), under("metadata", "status"))

        assertEquals(setOf(IDENTITY, SOURCE, STATUS), picked())
        assertEquals(selectedBefore, state.tab("a")!!.selected, "the log row selection is untouched")
        rule.pressKey(Key.DirectionDown, shift = true)
        assertEquals(setOf(IDENTITY, SOURCE, STATUS, OWNER), picked(), "Shift+Down extends from the pressed row")
        assertEquals(selectedBefore, state.tab("a")!!.selected)
    }

    @Test
    fun aDragFromAFieldPastTheEndOfItsRecordOntoTheNextLogRowSelectsNoLogRows() {
        setupJson()
        rule.onNodeWithTag("log-row-a:main-1").performClick()
        rule.waitForIdle()
        openRecordUnderRow()
        val selectedBefore = state.tab("a")!!.selected

        drag(under("metadata", "rank"), "log-row-a:main-2")

        assertEquals(setOf(RANK, listOf("tail")), picked(), "the range runs to the record's last row")
        assertEquals(selectedBefore, state.tab("a")!!.selected, "the log list did not read the drag as a row selection")
    }

    @Test
    fun aDragWithinOneValueUnderARowIsTextSelectionAndPicksNothing() {
        setupJson()
        openRecordUnderRow()
        clickField(under("tail"))
        later()

        rule.dragSelect(under("metadata", "identity"), KEY_INSET, under("metadata", "identity"), KEY_INSET + 40f)

        assertNull(picked())
    }

    @Test
    fun aCtrlDragUnderARowAddsToThePick() {
        setupJson()
        openRecordUnderRow()
        rule.modifiedClick(under("tail"), ctrl = true)

        drag(under("metadata", "identity"), under("metadata", "source"), ctrl = true)

        assertEquals(setOf(listOf("tail"), IDENTITY, SOURCE), picked())
    }

    @Test
    fun aDragAcrossFieldRowsInMessageDetailsPicksThemAndShiftDownExtends() {
        setupJson()
        rule.onNodeWithTag("log-row-a:main-1").performClick()
        rule.waitForIdle()
        val selectedBefore = state.tab("a")!!.selected

        val dragger = drag(details("metadata", "identity"), details("metadata", "status"), release = false)
        assertEquals(setOf(IDENTITY, SOURCE, STATUS), picked(), "live")
        dragger.moveTo(rule.rowPoint(details("metadata", "source")))
        assertEquals(setOf(IDENTITY, SOURCE), picked())
        dragger.moveTo(rule.rowPoint(details("metadata", "status")))
        dragger.release()

        assertEquals(setOf(IDENTITY, SOURCE, STATUS), picked())
        rule.pressKey(Key.DirectionDown, shift = true)
        assertEquals(setOf(IDENTITY, SOURCE, STATUS, OWNER), picked())
        assertEquals(selectedBefore, state.tab("a")!!.selected)
    }

    @Test
    fun aDragFromAHexDumpLineToTheNextFieldInMessageDetailsPicksBoth() {
        setup(1100, 760) { s ->
            s.tabs = s.tabs.map { tab ->
                if (tab.id == "a") mkTab("a", "a.log", listOf(jsonRow(1, RECORD_BLOB), jsonRow(2, RECORD_ONE))).copy(sourcePath = tab.sourcePath) else tab
            }
        }
        rule.onNodeWithTag("log-row-a:main-1").performClick()
        rule.waitForIdle()

        val dragger = RowDragger(rule)
        dragger.press(rule.rowPoint("structured-record-hex-line-1-1-details-blob-0", inset = 4f))
        dragger.moveTo(rule.rowPoint(details("note")))
        dragger.release()

        assertEquals(setOf(listOf("blob"), listOf("note")), picked())
    }

    /** A record of [count] short fields, so tall that only a part of it shows in a tile or in the log list. */
    private fun tallRecord(count: Int) = (1..count).joinToString(",", "{", "}") { """"f$it":$it""" }

    private fun field(index: Int) = listOf("f$index")

    private fun setupTall(count: Int) = setup(1100, 760) { s ->
        s.tabs = s.tabs.map { tab ->
            if (tab.id == "a") mkTab("a", "a.log", listOf(jsonRow(1, tallRecord(count)), jsonRow(2, RECORD_ONE))).copy(sourcePath = tab.sourcePath) else tab
        }
    }

    @Test
    fun draggingBelowTheVisibleAreaOfMessageDetailsScrollsItAndExtendsThePick() {
        setupTall(120)
        rule.onNodeWithTag("log-row-a:main-1").performClick()
        rule.waitForIdle()
        // The scrollbar spans the tile's visible part (the content column is as tall as the whole record).
        val viewport = rule.onNodeWithTag("message-details-scrollbar-1").fetchSemanticsNode().boundsInRoot
        val content = rule.onNodeWithTag("message-details-content-1").fetchSemanticsNode().boundsInRoot
        val first = details("f3")
        // Rows scrolled out of the tile report empty bounds.
        val lastVisible = (1..120).last { !rule.onNodeWithTag(details("f$it")).fetchSemanticsNode().boundsInRoot.isEmpty }
        assertTrue(lastVisible in 5..100, "only a part of the record shows: $lastVisible")

        rule.mainClock.autoAdvance = false
        val dragger = RowDragger(rule)
        dragger.press(rule.rowPoint(first))
        dragger.moveTo(Offset(content.left + 60f, minOf(viewport.bottom + 24f, 755f)))
        val before = picked()!!
        assertTrue(field(lastVisible) in before, "the visible rows are picked at once")
        rule.mainClock.advanceTimeBy(1_500)
        val after = picked()!!
        dragger.release()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        assertTrue(field(lastVisible + 6) in after, "rows scrolled in below were picked too: ${after.size} vs ${before.size}")
        assertTrue(after.size > before.size + 6)
        assertEquals(field(3), state.recordFieldAnchor!!.paths.single(), "the pressed row stays the anchor")
    }

    @Test
    fun draggingAboveTheVisibleAreaOfMessageDetailsScrollsBackUpAndExtendsThePick() {
        setupTall(120)
        rule.onNodeWithTag("log-row-a:main-1").performClick()
        rule.waitForIdle()
        val viewport = rule.onNodeWithTag("message-details-scrollbar-1").fetchSemanticsNode().boundsInRoot
        rule.onAllNodes(isRoot())[0].performMouseInput {
            moveTo(Offset(viewport.left - 100f, viewport.top + 100f))
            scroll(600f)
        }
        rule.waitForIdle()
        val visible = (1..120).filter { !rule.onNodeWithTag(details("f$it")).fetchSemanticsNode().boundsInRoot.isEmpty }
        assertTrue(visible.first() > 10, "scrolled down: ${visible.first()}")
        val pressed = visible.first() + 4

        rule.mainClock.autoAdvance = false
        val dragger = RowDragger(rule)
        dragger.press(rule.rowPoint(details("f$pressed")))
        dragger.moveTo(Offset(viewport.left - 300f, viewport.top - 6f))
        val before = picked()!!
        rule.mainClock.advanceTimeBy(1_500)
        val after = picked()!!
        dragger.release()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        assertTrue(field(pressed) in after)
        assertTrue(field(visible.first() - 6) in after, "rows scrolled in above were picked: ${before.size} -> ${after.size}")
    }

    @Test
    fun draggingBelowTheListUnderARowScrollsTheListAndExtendsThePick() {
        setupTall(120)
        rule.onNodeWithTag("structured-record-disclosure-1").performClick()
        rule.waitForIdle()
        val rowBounds = rule.onNodeWithTag("log-row-a:main-1").fetchSemanticsNode().boundsInRoot
        val root = rule.onAllNodes(isRoot())[0].fetchSemanticsNode().boundsInRoot
        val list = rule.onNodeWithTag(under("f2")).fetchSemanticsNode().boundsInRoot

        rule.mainClock.autoAdvance = false
        val dragger = RowDragger(rule)
        dragger.press(rule.rowPoint(under("f2")))
        dragger.moveTo(Offset(list.left + 60f, root.bottom - 2f))
        val before = picked()!!
        rule.mainClock.advanceTimeBy(1_500)
        val after = picked()!!
        dragger.release()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        assertTrue(after.size > before.size + 6, "the list scrolled and the range grew: ${before.size} -> ${after.size} (row at ${rowBounds.top})")
    }

    @Test
    fun aClickInMessageDetailsAndTheTreeUnderTheRowShareTheOnePick() {
        setupJson()
        rule.onNodeWithTag("log-row-a:main-1").performClick()
        rule.waitForIdle()
        openRecordUnderRow()

        clickField(under("metadata", "source"))

        assertEquals(setOf(SOURCE), picked())
        rule.pointerAway()
        assertTrue(rule.rowTint(details("metadata", "source")) != rule.rowTint(details("metadata", "owner")), "Message details marks it too")
        assertTrue(rule.rowTint(under("metadata", "source")) != rule.rowTint(under("metadata", "owner")), "and so does the tree under the row")
    }
}

/** Writes the selected field rows to build/shared-notes-qa (11-field-rows-selected-light/dark.png, 12-field-rows-drag.png) so they can be looked at. */
@OptIn(ExperimentalTestApi::class)
class RecordFieldRowsQaScreenshotsUiTest : SharedNoteUiTestBase() {
    private fun jsonRow(id: Int, json: String) = LogEntry(
        id, "10:00:%02d.000".format(id), LogLevel.I, "HCI", "message $id",
        recordMetadata = LogRecordMetadata(rawRecord = json, usesProfilePresentation = true),
    )

    private fun setupShot(theme: ThemeColors) {
        dir = kotlin.io.path.createTempDirectory("record-field-rows-qa").toFile()
        state = AppState(java.io.File(dir, "state.cache"), notesDir = java.io.File(dir, "notes"), autoExportNotes = false)
        state.tabs = listOf(
            mkTab("a", "Untitled.json", listOf(jsonRow(1, RECORD_SELECT), jsonRow(2, RECORD_ONE)))
                .copy(sourcePath = logFile("a.log").absolutePath),
        )
        state.activeTabId = "a"
        rule.setContent {
            CompositionLocalProvider(
                LocalTheme provides theme,
                LocalContextMenuRepresentation provides IndagiumContextMenuRepresentation,
                LocalTextContextMenu provides IndagiumTextContextMenu,
            ) {
                Box(Modifier.size(1000.dp, 520.dp).background(theme.bg)) { state.tab("a")?.let { FileView(state, it) } }
            }
        }
        rule.waitForIdle()
    }

    private fun shotWithThreeRowsSelected(name: String, theme: ThemeColors) {
        setupShot(theme)
        rule.onNodeWithTag("log-row-a:main-1").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("structured-record-disclosure-1").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(nodeTag("1.root", "metadata", toggle = true)).performClick()
        rule.waitForIdle()

        rule.clickRow(nodeTag("1.details", "metadata", "identity"))
        rule.modifiedClick(nodeTag("1.details", "metadata", "status"), shift = true)
        // The pointer rests on another row to show the hover tint beside the three selected ones.
        rule.onNodeWithTag(nodeTag("1.details", "metadata", "owner")).performMouseInput { moveTo(Offset(KEY_INSET + 40f, center.y)) }
        rule.waitForIdle()
        assertEquals(setOf(IDENTITY, SOURCE, STATUS), state.recordFieldSelection!!.paths)

        val roots = rule.onAllNodes(isRoot())
        for (i in 0 until roots.fetchSemanticsNodes().size) saveScreenshot(roots[i], if (i == 0) name else "$name-layer$i")
    }

    @Test
    fun aDragOverFourFieldRowsInTheLightTheme() {
        setupShot(LIGHT_THEME)
        rule.onNodeWithTag("log-row-a:main-1").performClick()
        rule.waitForIdle()

        // The button is still down: the range follows the pointer, as it does on screen while the user drags.
        val drag = RowDragger(rule)
        drag.press(rule.rowPoint(nodeTag("1.details", "metadata", "identity")))
        drag.moveTo(rule.rowPoint(nodeTag("1.details", "metadata", "status")))
        drag.moveTo(rule.rowPoint(nodeTag("1.details", "metadata", "owner"), inset = KEY_INSET + 50f))
        assertEquals(setOf(IDENTITY, SOURCE, STATUS, OWNER), state.recordFieldSelection!!.paths)

        val roots = rule.onAllNodes(isRoot())
        for (i in 0 until roots.fetchSemanticsNodes().size) saveScreenshot(roots[i], if (i == 0) "12-field-rows-drag" else "12-field-rows-drag-layer$i")
        drag.release()
    }

    @Test
    fun threeSelectedFieldRowsInTheLightTheme() = shotWithThreeRowsSelected("11-field-rows-selected-light", LIGHT_THEME)

    @Test
    fun threeSelectedFieldRowsInTheDarkTheme() = shotWithThreeRowsSelected("11-field-rows-selected-dark", DARK_GITHUB)
}
