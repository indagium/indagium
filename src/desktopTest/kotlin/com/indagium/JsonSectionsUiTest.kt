package com.indagium

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import com.indagium.model.TabContentState
import com.indagium.ui.AppState
import com.indagium.ui.DARK_GITHUB
import com.indagium.ui.FileView
import com.indagium.ui.LOG_VIEW_FILTER_TILES
import com.indagium.ui.LocalTheme
import com.indagium.ui.isLogViewTileCollapsed
import com.indagium.ui.toggleLogViewTileCollapsed
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val SECTION_LOAD_TIMEOUT_MS = 15000L
private const val TILE_GAP_DP = 6f
private const val TILE_GEOMETRY_TOLERANCE_DP = 0.6f

@OptIn(ExperimentalTestApi::class)
class JsonSectionsUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun sidePanelOpensSeparateProtocolLogsAndRestoresTheSelectedSection() {
        val directory = Files.createTempDirectory("json-sections-ui-").toFile()
        val file = File(directory, "capture.json").apply {
            writeText(
                """{"Tethered Bluetooth":{"HCI":[""" +
                    packet("Bluetooth.HCI.Note", "HCI note") + "],\"L2CAP\":[" +
                    packet("Bluetooth.L2CAP", "L2CAP frame") + "],\"RFCOMM\":[]}}",
            )
        }
        val autosave = File(directory, "state.cache")
        val app = AppState(autosaveFile = autosave, autoExportNotes = false)
        var tabId = ""
        try {
            tabId = assertNotNull(app.openFile(file))
            waitForSection(app, tabId, "HCI")
            assertEquals(listOf("HCI note"), app.tab(tabId)!!.logData.map { it.msg })
            app.filterVisible = true
            app.annotationVisible = false
            LOG_VIEW_FILTER_TILES.forEach { if (!app.isLogViewTileCollapsed(it)) app.toggleLogViewTileCollapsed(it) }
            rule.setContent {
                CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                    Box(Modifier.fillMaxSize()) { app.tab(tabId)?.let { FileView(app, it) } }
                }
            }
            rule.onNodeWithTag("json-section-Tethered Bluetooth / HCI").assertIsSelected()
            assertNoLevelTileOrGap()
            assertEquals(false, app.metadataJsonTagVisible(tabId))
            openToolbarOptions(tabId)
            rule.onNodeWithText("Show TAG").performClick()
            assertEquals(true, app.metadataJsonTagVisible(tabId))
            app.setKw(tabId, "HCI note")
            rule.onNodeWithTag("json-section-Tethered Bluetooth / L2CAP").performClick()
            waitForSection(app, tabId, "L2CAP")
            assertEquals(listOf("L2CAP frame"), app.tab(tabId)!!.logData.map { it.msg })
            assertTrue(app.tab(tabId)!!.logData.single().recordMetadata!!.displayColumns.any { it.id == "component" })
            rule.onNodeWithText("L2CAP frame", substring = true, useUnmergedTree = true).assertExists()
            assertEquals("", app.tab(tabId)!!.filter.kwText)
            rule.onNodeWithTag("json-section-Tethered Bluetooth / L2CAP").assertIsSelected()
            assertNoLevelTileOrGap()
            rule.onNodeWithTag("json-section-Tethered Bluetooth / RFCOMM").assertDoesNotExist()
            rule.onNodeWithTag("json-sections-show-empty-$tabId").performClick()
            rule.onNodeWithTag("json-section-Tethered Bluetooth / RFCOMM").performClick()
            waitForSection(app, tabId, "RFCOMM")
            assertTrue(app.tab(tabId)!!.logData.isEmpty(), "an empty protocol section is a valid empty log")
            rule.onNodeWithTag("json-section-Tethered Bluetooth / HCI").performClick()
            waitForSection(app, tabId, "HCI")
            assertEquals("HCI note", app.tab(tabId)!!.filter.kwText, "filters belong to their protocol log")
            rule.onNodeWithTag("json-section-Tethered Bluetooth / L2CAP").performClick()
            waitForSection(app, tabId, "L2CAP")
            app.autosaveNow()
        } finally {
            app.close()
        }
        val restored = AppState(autosaveFile = autosave, restoreOnCreate = true, autoExportNotes = false)
        try {
            restored.startPendingRestoredTabLoads()
            waitForSection(restored, tabId, "L2CAP")
            assertEquals(listOf("L2CAP frame"), restored.tab(tabId)!!.logData.map { it.msg })
            assertEquals(3, restored.tab(tabId)!!.logSections.size)
            assertEquals(true, restored.metadataJsonTagVisible(tabId))
            assertTrue(restored.tab(tabId)!!.logData.single().recordMetadata!!.displayColumns.any { it.id == "component" })
        } finally {
            restored.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun aSeparateRecordFileUsesTheWholeLogViewWithoutASectionsTile() {
        val directory = Files.createTempDirectory("json-single-section-ui-").toFile()
        val file = File(directory, "records.json").apply {
            writeText("[" + packet("Service.Event", "Single message") + "]")
        }
        val app = AppState(autosaveFile = File(directory, "state.cache"), autoExportNotes = false)
        try {
            val id = assertNotNull(app.openFile(file))
            rule.waitUntil(SECTION_LOAD_TIMEOUT_MS) { app.tab(id)?.contentState == TabContentState.LOADED }
            assertEquals(1, app.tab(id)!!.logSections.size)
            app.filterVisible = true
            rule.setContent {
                CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                    Box(Modifier.fillMaxSize()) { app.tab(id)?.let { FileView(app, it) } }
                }
            }
            rule.onNodeWithTag("tile-card-sections").assertDoesNotExist()
            rule.onNodeWithTag("log-sections-$id").assertDoesNotExist()
            rule.onNodeWithText("Single message", substring = true, useUnmergedTree = true).assertExists()
            val original = app.tab(id)!!.logData.single()
            val rowTag = hasTestTag("log-row-$id:main-${original.id}")
            val displayedTag = hasText("Service.Event", substring = true) and (rowTag or hasAnyAncestor(rowTag))
            rule.onNode(displayedTag, useUnmergedTree = true).assertDoesNotExist()
            openToolbarOptions(id)
            rule.onNodeWithText("Show TAG").performClick()
            assertEquals(true, app.metadataJsonTagVisible(id))
            assertEquals("Service.Event", app.tab(id)!!.logData.single().recordMetadata!!.displayColumns.single { it.id == "component" }.value)
            rule.waitUntil(SECTION_LOAD_TIMEOUT_MS) {
                rule.onAllNodes(displayedTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(original.rawRecord, app.tab(id)!!.logData.single().rawRecord)
            assertEquals(original.id, app.tab(id)!!.logData.single().id)
            openToolbarOptions(id)
            rule.onNodeWithText("Hide TAG").performClick()
            rule.waitUntil(SECTION_LOAD_TIMEOUT_MS) {
                rule.onAllNodes(displayedTag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            rule.onNode(displayedTag, useUnmergedTree = true).assertDoesNotExist()
            assertEquals("Service.Event", app.tab(id)!!.logData.single().tag)
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun anEmptyArrayFileDoesNotCreateEmptySectionNavigation() {
        val directory = Files.createTempDirectory("json-empty-section-ui-").toFile()
        val file = File(directory, "empty.json").apply { writeText("[]") }
        val app = AppState(autosaveFile = File(directory, "state.cache"), autoExportNotes = false)
        try {
            val id = assertNotNull(app.openFile(file))
            rule.waitUntil(SECTION_LOAD_TIMEOUT_MS) { app.tab(id)?.contentState == TabContentState.LOADED }
            assertTrue(app.tab(id)!!.logData.isEmpty())
            assertTrue(app.tab(id)!!.logSections.isEmpty())
            rule.setContent {
                CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                    Box(Modifier.fillMaxSize()) { app.tab(id)?.let { FileView(app, it) } }
                }
            }
            rule.onNodeWithTag("tile-card-sections").assertDoesNotExist()
            rule.onNodeWithText("This file contains no records").assertExists()
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun ordinaryJsonRetainsTagAndHasNoFormatSpecificMenuItem() {
        val directory = Files.createTempDirectory("json-ordinary-tag-ui-").toFile()
        val file = File(directory, "ordinary.json").apply { writeText("""{"tag":"Service","message":"ordinary"}""") }
        val app = AppState(autosaveFile = File(directory, "state.cache"), autoExportNotes = false)
        try {
            val id = assertNotNull(app.openFile(file))
            rule.waitUntil(SECTION_LOAD_TIMEOUT_MS) { app.tab(id)?.contentState == TabContentState.LOADED }
            assertEquals(null, app.metadataJsonTagVisible(id))
            val original = app.tab(id)!!.logData
            app.toggleMetadataJsonTag(id)
            assertTrue(app.tab(id)!!.logData === original)
            assertTrue(original.single().recordMetadata!!.displayColumns.any { it.id == "component" })
            rule.setContent {
                CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                    Box(Modifier.fillMaxSize()) { app.tab(id)?.let { FileView(app, it) } }
                }
            }
            openToolbarOptions(id)
            rule.onNodeWithText("Show row numbers").assertExists()
            rule.onNodeWithTag("metadata-json-tag-toggle").assertDoesNotExist()
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    private fun openToolbarOptions(tabId: String) {
        rule.onNodeWithTag("log-view-options-toolbar-$tabId").performMouseInput { rightClick() }
    }

    private fun assertNoLevelTileOrGap() {
        rule.onNodeWithTag("tile-card-log_level").assertDoesNotExist()
        val above = rule.onNodeWithTag("tile-card-highlighters").getBoundsInRoot()
        val below = rule.onNodeWithTag("tile-card-sequences").getBoundsInRoot()
        assertEquals(above.bottom.value + TILE_GAP_DP, below.top.value, TILE_GEOMETRY_TOLERANCE_DP, "unavailable tiles must not reserve space")
    }

    private fun waitForSection(app: AppState, tabId: String, title: String) {
        rule.waitUntil(SECTION_LOAD_TIMEOUT_MS) {
            val tab = app.tab(tabId)
            tab?.contentState == TabContentState.LOADED && tab.selectedLogSectionId == "Tethered Bluetooth / $title"
        }
        rule.waitForIdle()
    }

    private fun packet(identity: String, title: String): String =
        """{"metadata":{"identity":"$identity","source":"Demo","system_time":1790000000000000000},"Summary":{"Note":"$title"}}"""
}
