package com.indagium

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import com.indagium.model.TabContentState
import com.indagium.ui.AppState
import com.indagium.ui.DARK_GITHUB
import com.indagium.ui.FileView
import com.indagium.ui.LocalTheme
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MessageDetailsTileUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun selectingMessagesUpdatesAnExpandedInspectorAndDoubleClickTogglesInlineDetails() {
        val directory = Files.createTempDirectory("message-details-ui-").toFile()
        val file = File(directory, "records.json").apply {
            writeText(
                """[{"message":"First packet","context":{"device":{"serial":"A-1"}}},""" +
                    """{"message":"Second packet","context":{"device":{"serial":"B-2"}}}]""",
            )
        }
        val app = AppState(autosaveFile = File(directory, "state.cache"), autoExportNotes = false)
        try {
            val id = requireNotNull(app.openFile(file))
            rule.waitUntil(15000L) { app.tab(id)?.contentState == TabContentState.LOADED }
            app.filterVisible = false
            app.annotationVisible = false
            rule.setContent {
                CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                    Box(Modifier.size(1200.dp, 760.dp).testTag("message-tile-qa")) { app.tab(id)?.let { FileView(app, it) } }
                }
            }
            rule.onNodeWithTag("tab-format-details-$id").assertDoesNotExist()
            rule.onNodeWithTag("tile-grip-message_details").assertExists()
            rule.onNodeWithTag("log-row-$id:main-1").performClick()
            rule.onNodeWithTag("structured-record-node-1-details-context-device-serial").assertTextContains("A-1", substring = true)
            rule.onNodeWithTag("structured-record-node-toggle-1-details-context-device").performClick()
            rule.onNodeWithTag("structured-record-node-1-details-context-device-serial").assertDoesNotExist()
            rule.onNodeWithTag("log-row-$id:main-2").performClick()
            rule.onNodeWithTag("structured-record-node-2-details-context-device-serial").assertTextContains("B-2", substring = true)
            assertEquals(setOf(2), app.tab(id)!!.selected)
            saveScreenshot("message-inspector-expanded")
            val row = rule.onNodeWithTag("log-row-$id:main-2")
            row.performMouseInput { doubleClick() }
            rule.onNodeWithTag("structured-record-body-2").assertExists()
            row.performMouseInput { doubleClick() }
            rule.onNodeWithTag("structured-record-body-2").assertDoesNotExist()
            rule.onNodeWithTag("tile-hide-message_details").performClick()
            assertTrue(com.indagium.model.LogViewSectionId.MESSAGE_DETAILS in app.settings.logViewHidden)
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun detailsScrollbarCanDragToTheLastFieldOfALongMessage() {
        val directory = Files.createTempDirectory("message-details-scroll-").toFile()
        val file = File(directory, "long.json").apply {
            writeText("[{\"message\":\"Long packet\"," + (0..69).joinToString(",") { "\"field$it\":\"value $it\"" } + "}]")
        }
        val app = AppState(autosaveFile = File(directory, "state.cache"), autoExportNotes = false)
        try {
            val id = requireNotNull(app.openFile(file))
            rule.waitUntil(15000L) { app.tab(id)?.contentState == TabContentState.LOADED }
            app.filterVisible = false
            app.annotationVisible = false
            rule.setContent {
                CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                    Box(Modifier.size(1200.dp, 760.dp).testTag("message-tile-qa")) { app.tab(id)?.let { FileView(app, it) } }
                }
            }
            rule.onNodeWithTag("log-row-$id:main-1").performClick()
            val content = rule.onNodeWithTag("message-details-content-1")

            fun scrollPosition() = content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

            assertEquals(0f, scrollPosition())
            rule.onNodeWithTag("message-details-scrollbar-1").assertIsDisplayed().performMouseInput {
                moveTo(topCenter + Offset(0f, 4f))
                press()
                moveTo(bottomCenter - Offset(0f, 4f), delayMillis = 400)
                release()
            }
            rule.waitUntil { scrollPosition() > 0f }
            rule.onNodeWithText("value 69", substring = true).assertIsDisplayed()
            saveScreenshot("message-inspector-scrollbar")
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    private fun saveScreenshot(name: String) {
        val image = rule.onNodeWithTag("message-tile-qa").captureToImage()
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) rendered.setRGB(x, y, pixels[x, y].toArgb())
        val dir = File("build/message-tiles-qa").apply { mkdirs() }
        assertTrue(ImageIO.write(rendered, "png", File(dir, "$name.png")))
    }
}
