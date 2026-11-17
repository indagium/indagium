package com.indagium

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.indagium.model.TabContentState
import com.indagium.ui.AppState
import com.indagium.ui.DARK_GITHUB
import com.indagium.ui.FileView
import com.indagium.ui.LocalTheme
import com.indagium.ui.RecordExpansion
import com.indagium.ui.logToolbarTag
import com.indagium.ui.messageDetailsCopyText
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val LOAD_TIMEOUT_MS = 15000L

/** The metadata JSON view's polish: Sections tile, Tags tile note, Message details header, Expand all reaching records. */
@OptIn(ExperimentalTestApi::class)
class JsonViewPolishUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun packet(identity: String, title: String): String =
        """{"metadata":{"identity":"$identity","source":"Demo","system_time":1790000000000000000},"Summary":{"Note":"$title"}}"""

    private fun sectionsFile(directory: File): File = File(directory, "capture.json").apply {
        writeText(
            """{"Tethered Bluetooth":{"HCI":[""" + packet("Bluetooth.HCI.Note", "HCI note") + "," + packet("Bluetooth.HCI.Note", "HCI second") +
                "],\"L2CAP\":[" + packet("Bluetooth.L2CAP", "L2CAP frame") + "],\"RFCOMM\":[]}}",
        )
    }

    private fun recordsFile(directory: File): File = File(directory, "records.json").apply {
        writeText("[" + packet("Service.Event", "first") + "," + packet("Service.Event", "second") + "," + packet("Service.Event", "third") + "]")
    }

    private fun open(app: AppState, file: File, filters: Boolean = true): String {
        val id = assertNotNull(app.openFile(file))
        rule.waitUntil(LOAD_TIMEOUT_MS) { app.tab(id)?.contentState == TabContentState.LOADED }
        app.filterVisible = filters
        app.annotationVisible = false
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                Box(Modifier.size(1500.dp, 900.dp).testTag("json-polish-root")) { app.tab(id)?.let { FileView(app, it) } }
            }
        }
        rule.waitForIdle()
        return id
    }

    private inline fun withApp(body: (AppState, File) -> Unit) {
        val directory = Files.createTempDirectory("json-polish-ui-").toFile()
        val app = AppState(autosaveFile = File(directory, "state.cache"), autoExportNotes = false)
        try {
            body(app, directory)
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    private fun textColor(rowTag: String, text: String): Color {
        val node = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(rowTag)), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().layoutInput.style.color
    }

    // J6 ─────────────────────────────────────────────────────────────────

    @Test
    fun sectionsTileUsesTheSidebarPatternWithShowEmptyInItsHeader() {
        withApp { app, directory ->
            val id = open(app, sectionsFile(directory))
            val colors = DARK_GITHUB
            val hci = "json-section-Tethered Bluetooth / HCI"
            val l2cap = "json-section-Tethered Bluetooth / L2CAP"
            rule.onNodeWithTag(hci).assertExists()
            assertEquals(colors.tx, textColor(hci, "HCI"), "the selected title is plain text, not the accent")
            assertEquals(colors.tx, textColor(l2cap, "L2CAP"))
            assertNotEquals(colors.ac, textColor(hci, "HCI"))

            val list = hasTestTag("json-section-list-$id")
            val showEmpty = hasTestTag("json-sections-show-empty-$id")
            rule.onNode(showEmpty).assertTextEquals("Show 1 empty")
            assertTrue(
                rule.onAllNodes(showEmpty and hasAnyAncestor(list)).fetchSemanticsNodes().isEmpty(),
                "the control is not an item of the list",
            )
            assertTrue(
                rule.onAllNodes(showEmpty and hasAnyAncestor(hasTestTag("tile-header-sections"))).fetchSemanticsNodes().isNotEmpty(),
                "it sits in the Sections tile header",
            )
            rule.onNodeWithTag("json-section-Tethered Bluetooth / RFCOMM").assertDoesNotExist()
            rule.onNode(showEmpty).performClick()
            rule.onNode(showEmpty).assertTextEquals("Hide empty")
            rule.onNodeWithTag("json-section-Tethered Bluetooth / RFCOMM").assertExists()
            rule.onNode(showEmpty).performClick()
            rule.onNodeWithTag("json-section-Tethered Bluetooth / RFCOMM").assertDoesNotExist()

            // The group label's row carries no fill of its own: the body is the tile's panel colour, not the old p2 well.
            val image = rule.onNodeWithTag("log-sections-$id").captureToImage()
            val sample = image.toPixelMap()[6, 4]
            assertNotEquals(colors.p2, sample, "no p2 fill behind the list")
            assertEquals(colors.p, sample, "the tile's own panel colour")
        }
    }

    // J7 ─────────────────────────────────────────────────────────────────

    @Test
    fun tagsTileSaysTheTagColumnIsHiddenAndCanShowIt() {
        withApp { app, directory ->
            val id = open(app, recordsFile(directory))
            val card = hasTestTag("tile-card-tags")
            assertEquals(false, app.metadataJsonTagVisible(id))
            rule.onNode(hasText("TAG column hidden") and hasAnyAncestor(card)).assertExists()
            rule.onNodeWithTag("tags-tag-column-show").performClick()
            assertEquals(true, app.metadataJsonTagVisible(id))
            rule.waitForIdle()
            rule.onAllNodes(hasText("TAG column hidden")).assertCountEquals(0)
            rule.onAllNodes(hasTestTag("tags-tag-column-show")).assertCountEquals(0)
            app.toggleMetadataJsonTag(id)
            rule.waitForIdle()
            rule.onNode(hasText("TAG column hidden") and hasAnyAncestor(card)).assertExists()
        }
    }

    @Test
    fun tagsTileHasNoNoteInALogcatTab() {
        withApp { app, directory ->
            val file = File(directory, "plain.log").apply {
                writeText("01-01 10:00:00.000  100  200 I Tag: hello\n01-01 10:00:01.000  100  200 W Tag: again\n")
            }
            val id = open(app, file)
            assertEquals(null, app.metadataJsonTagVisible(id))
            rule.onNodeWithTag("tile-card-tags").assertExists()
            rule.onAllNodes(hasText("TAG column hidden")).assertCountEquals(0)
            rule.onAllNodes(hasTestTag("tags-tag-column-show")).assertCountEquals(0)
        }
    }

    // J8 ─────────────────────────────────────────────────────────────────

    @Test
    fun messageDetailsHeaderIsTimestampAndTagPillAndCopiesTheRawRecord() {
        withApp { app, directory ->
            val id = open(app, recordsFile(directory))
            rule.onNodeWithTag("log-row-$id:main-1").performClick()
            rule.waitForIdle()
            val entry = app.tab(id)!!.logData.first()
            rule.onNodeWithTag("message-details-header-1").assertExists()
            rule.onNodeWithTag("message-details-ts-1").assertTextEquals(entry.ts)
            rule.onNodeWithTag("message-details-tag-1").assertExists()
            rule.onNode(hasText("Service.Event") and hasAnyAncestor(hasTestTag("message-details-tag-1")), useUnmergedTree = true).assertExists()
            val content = hasTestTag("message-details-content-1")
            // The message is on the row and inside the tree; the old separate message line is gone.
            val message = hasText(entry.msg) and hasAnyAncestor(content)
            assertTrue(rule.onAllNodes(message, useUnmergedTree = true).fetchSemanticsNodes().size <= 1, "the message is not repeated above the tree")
            rule.onNodeWithTag("structured-record-body-1").assertDoesNotExist()
            rule.onNodeWithTag("message-details-record-1").assertExists()
            rule.onNodeWithTag("message-details-copy-1").assertExists()
            assertEquals(entry.rawRecord, messageDetailsCopyText(entry))
            assertEquals("plain text", messageDetailsCopyText(entry.copy(recordMetadata = null, msg = "plain text")))

            assumeFalse(GraphicsEnvironment.isHeadless())
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val previous = runCatching { clipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull()
            try {
                rule.onNodeWithTag("message-details-copy-1").performClick()
                assertEquals(entry.rawRecord, clipboard.getData(DataFlavor.stringFlavor))
            } finally {
                previous?.let { clipboard.setContents(StringSelection(it), null) }
            }
        }
    }

    // J9 ─────────────────────────────────────────────────────────────────

    @Test
    fun expandAllAndCollapseAllReachRecordsInATabWithoutGroups() {
        withApp { app, directory ->
            val id = open(app, recordsFile(directory))
            val expandAll = logToolbarTag("expand-all")
            val collapseAll = logToolbarTag("collapse-all")

            fun body(row: Int) = rule.onAllNodes(hasTestTag("structured-record-body-$row"))

            body(1).assertCountEquals(0)
            rule.onNodeWithTag(expandAll).assertIsEnabled()
            rule.onNodeWithTag(collapseAll).assertIsNotEnabled()

            rule.onNodeWithTag(expandAll).performClick()
            rule.waitForIdle()
            listOf(1, 2, 3).forEach { body(it).assertCountEquals(1) }
            rule.onNodeWithTag(expandAll).assertIsNotEnabled()
            rule.onNodeWithTag(collapseAll).assertIsEnabled()

            // One row toggled after Expand all closes only that row.
            rule.onNodeWithTag("structured-record-disclosure-2").performClick()
            rule.waitForIdle()
            body(2).assertCountEquals(0)
            body(1).assertCountEquals(1)
            body(3).assertCountEquals(1)
            rule.onNodeWithTag(expandAll).assertIsEnabled()
            rule.onNodeWithTag(collapseAll).assertIsEnabled()

            rule.onNodeWithTag(collapseAll).performClick()
            rule.waitForIdle()
            listOf(1, 2, 3).forEach { body(it).assertCountEquals(0) }
            rule.onNodeWithTag(collapseAll).assertIsNotEnabled()
            assertTrue(app.recordExpansion(id).isInitial)

            rule.onNodeWithTag("structured-record-disclosure-1").performClick()
            rule.waitForIdle()
            body(1).assertCountEquals(1)
            body(2).assertCountEquals(0)
            rule.onNodeWithTag(collapseAll).assertIsEnabled()
            rule.onNodeWithTag(expandAll).assertIsEnabled()
        }
    }

    @Test
    fun recordExpansionResetsWhenTheSectionChangesAndIsNotPersisted() {
        withApp { app, directory ->
            val id = open(app, sectionsFile(directory), filters = false)
            rule.onNodeWithTag(logToolbarTag("expand-all")).performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("structured-record-body-1").assertExists()
            assertEquals(RecordExpansion.ALL_EXPANDED, app.recordExpansion(id))
            rule.onNodeWithTag("json-section-Tethered Bluetooth / L2CAP").performClick()
            rule.waitUntil(LOAD_TIMEOUT_MS) {
                val tab = app.tab(id)
                tab?.contentState == TabContentState.LOADED && tab.selectedLogSectionId == "Tethered Bluetooth / L2CAP"
            }
            rule.waitForIdle()
            assertTrue(app.recordExpansion(id).isInitial, "a section change clears the expansion")
            rule.onAllNodes(hasTestTag("structured-record-body-1")).assertCountEquals(0)
            app.closeTab(id)
            assertTrue(app.recordExpansion(id).isInitial)
        }
    }

    @Test
    fun recordExpansionKeepsOnlyTheRowsThatDifferFromTheDefault() {
        val none = RecordExpansion()
        assertFalse(none.isExpanded(7))
        val one = none.toggled(7)
        assertTrue(one.isExpanded(7))
        assertFalse(one.isExpanded(8))
        assertEquals(none, one.toggled(7))
        assertTrue(one.anyExpanded)
        assertFalse(one.allExpanded)
        val all = RecordExpansion.ALL_EXPANDED
        assertTrue(all.isExpanded(1) && all.allExpanded)
        val allButOne = all.toggled(7)
        assertFalse(allButOne.isExpanded(7))
        assertTrue(allButOne.isExpanded(8))
        assertFalse(allButOne.allExpanded)
        assertTrue(allButOne.anyExpanded)
    }

    @Test
    fun savesAScreenshotOfTheJsonView() {
        withApp { app, directory ->
            val hex = "Software Version 4.2 build 17".toByteArray().joinToString(" ") { "%02x".format(it) }
            val blob = (0 until 40).joinToString("") { "%02x".format((it * 7 + 0x80) and 0xff) }

            fun withPayload(title: String) =
                """{"metadata":{"identity":"Bluetooth.HCI.Note","source":"Demo","system_time":1790000000000000000},""" +
                    """"Summary":{"Note":"$title","Payload":"$hex","Raw":"$blob","Handle":"1794860790349975040"}}"""
            val file = File(directory, "capture.json").apply {
                writeText(
                    """{"Tethered Bluetooth":{"HCI":[""" + withPayload("HCI note") + "," + packet("Bluetooth.HCI.Note", "HCI second") +
                        "],\"L2CAP\":[" + packet("Bluetooth.L2CAP", "L2CAP frame") + "],\"RFCOMM\":[]}}",
                )
            }
            val id = open(app, file)
            rule.onNodeWithTag("log-row-$id:main-1").performClick()
            app.expandAll(id)
            rule.waitForIdle()
            val image = rule.onNodeWithTag("json-polish-root").captureToImage()
            val pixels = image.toPixelMap()
            val rendered = java.awt.image.BufferedImage(image.width, image.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) for (x in 0 until image.width) rendered.setRGB(x, y, pixels[x, y].toArgb())
            val dir = File("build/log-format-qa").apply { mkdirs() }
            assertTrue(javax.imageio.ImageIO.write(rendered, "png", File(dir, "json-view.png")))
        }
    }

    /** A hex dump scrolls sideways inside a log row, which itself sits in the log's sideways scroll: both wrap modes must lay out. */
    @Test
    fun aHexFieldOpensInsideALogRowInBothWrapModes() {
        for (wrap in listOf(true, false)) {
            withApp { app, directory ->
                app.settings = app.settings.copy(autoLogRowWrap = wrap)
                val hex = "Software Version 4.2 build 17".toByteArray().joinToString(" ") { "%02x".format(it) }
                val file = File(directory, "hex.json").apply {
                    writeText(
                        "[" + """{"metadata":{"identity":"Hex.Note","source":"Demo","system_time":1790000000000000000},""" +
                            """"Summary":{"Payload":"$hex"}}""" + "]",
                    )
                }
                val id = open(app, file, filters = false)
                app.expandAll(id)
                rule.waitForIdle()
                rule.onNodeWithTag("structured-record-node-toggle-1-root-Summary", useUnmergedTree = true).let { summary ->
                    if (rule.onAllNodes(hasTestTag("structured-record-node-toggle-1-root-Summary-Payload"), useUnmergedTree = true)
                            .fetchSemanticsNodes().isEmpty()
                    ) {
                        summary.performClick()
                        rule.waitForIdle()
                    }
                }
                rule.onNodeWithTag("structured-record-node-toggle-1-root-Summary-Payload", useUnmergedTree = true).performClick()
                rule.waitForIdle()
                rule.onNodeWithTag("structured-record-hex-1-root-Summary-Payload", useUnmergedTree = true).assertExists()
            }
        }
    }
}
