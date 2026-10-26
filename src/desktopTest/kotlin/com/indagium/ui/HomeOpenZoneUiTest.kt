package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.indagium.model.DEFAULT_HOME_OPEN_ACTIONS_HEIGHT_DP
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.assertTrue

class HomeOpenZoneUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val tempDir = Files.createTempDirectory("home-open-zone-ui-").toFile()
    private val state = AppState(
        autosaveFile = File(tempDir, "state.cache"),
        restoreOnCreate = false,
        notesDir = File(tempDir, "notes"),
        archiveCacheDir = File(tempDir, "archive-cache"),
        customCommandsDir = File(tempDir, "commands"),
        filterBackupsDir = File(tempDir, "filter-backups"),
    )

    @After
    fun tearDown() {
        state.close()
        tempDir.deleteRecursively()
    }

    private fun render(widthDp: Int = 1000, heightDp: Int = 700, fontScale: Float = 1f) {
        state.openHomeTab()
        val tab = requireNotNull(state.activeTab())
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(rule.density.density, fontScale)) {
                Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                    HomeOpenZone(state, tab, onReclaimFocus = {}, modifier = Modifier.fillMaxSize().testTag("home-open-zone-test-host"))
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).getBoundsInRoot()

    private fun saveEvidenceScreenshot(name: String, image: ImageBitmap) {
        val directory = File("build/home-layout-qa").apply { mkdirs() }
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) rendered.setRGB(x, y, pixels[x, y].toArgb())
        }
        check(ImageIO.write(rendered, "png", File(directory, name)))
    }

    @Test
    fun draggingTheActionSplitterShrinksCardsAndGivesTheSpaceToRecentFiles() {
        render()
        saveEvidenceScreenshot("home-open-actions-wide.png", rule.onNodeWithTag("home-open-zone-test-host").captureToImage())
        val actionsBefore = bounds("home-open-action-area")
        val recentBefore = bounds("home-recent-section")
        val splitter = rule.onNodeWithTag("home-open-actions-splitter")

        splitter.performTouchInput {
            down(center)
            moveBy(Offset(0f, -48f * rule.density.density))
            up()
        }
        rule.waitForIdle()

        val actionsAfter = bounds("home-open-action-area")
        val recentAfter = bounds("home-recent-section")
        assertTrue(actionsAfter.height < actionsBefore.height, "actions shrank from ${actionsBefore.height} to ${actionsAfter.height}")
        assertTrue(recentAfter.top < recentBefore.top, "Recent files moved up from ${recentBefore.top} to ${recentAfter.top}")
        assertTrue(recentAfter.height > recentBefore.height, "Recent files grew from ${recentBefore.height} to ${recentAfter.height}")
        assertTrue(state.settings.homeOpenActionsHeightDp < DEFAULT_HOME_OPEN_ACTIONS_HEIGHT_DP)
    }

    @Test
    fun aShorterCardClampsTheSavedActionHeightToTheAvailableSpace() {
        state.openHomeTab()
        state.updateSettings { it.withHomeOpenActionsHeightDp(500) }
        render(heightDp = 300)
        val actionHeight = bounds("home-open-action-area").height.value
        val recentHeight = bounds("home-recent-section").height.value
        assertTrue(actionHeight < 500f, "stored action area was clamped to the short card: ${actionHeight}dp")
        assertTrue(recentHeight >= HOME_OPEN_RECENTS_MIN_HEIGHT_DP, "Recent files keeps its minimum viewport: ${recentHeight}dp")
    }

    @Test
    fun midWidthAndLargeTextKeepActionsInsideTheResizableArea() {
        render(widthDp = 420, heightDp = 700, fontScale = 1.8f)
        val actions = bounds("home-open-action-area")
        val recent = bounds("home-recent-section")
        for (index in 0..3) {
            val tile = bounds("home-open-tile-$index")
            assertTrue(tile.bottom <= actions.bottom, "action $index stays inside the action area")
        }
        assertTrue(recent.top >= actions.bottom + 8.dp, "Recent files starts below the action cards and splitter")
    }

    @Test
    fun narrowCardKeepsEveryCompactActionVisibleAndLeavesRecentSpace() {
        render(widthDp = 220, heightDp = 700)
        val actions = bounds("home-open-action-area")
        val recent = bounds("home-recent-section")
        for (index in 0..3) {
            assertTrue(bounds("home-open-button-$index").bottom <= actions.bottom, "action $index stays reachable")
        }
        assertTrue(recent.height.value >= HOME_OPEN_RECENTS_MIN_HEIGHT_DP)
    }
}
