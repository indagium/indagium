package com.indagium.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.indagium.model.HomeLayoutLeaf
import com.indagium.model.HomeLayoutSplit
import com.indagium.model.HomeSectionId
import com.indagium.model.HomeSplitAxis
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// Drives HomeTilingHost with fake slot bodies (plain Text), so no adb, file or network access runs.
class HomeTilingUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val tempDir = Files.createTempDirectory("home-tiling-ui-").toFile()
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

    private fun fakeSlots(): Map<HomeSectionId, HomeSectionSlot> = mapOf(
        HomeSectionId.OPEN to HomeSectionSlot(Icons.Outlined.Description, homeSectionTitle(HomeSectionId.OPEN)) {
            Text("open body")
        },
        HomeSectionId.CAPTURE to HomeSectionSlot(Icons.Outlined.PhoneAndroid, homeSectionTitle(HomeSectionId.CAPTURE)) {
            // Local remembered state: it must survive the section being moved to another place in the layout.
            var clicks by remember { mutableStateOf(0) }
            Box(Modifier.testTag("capture-clicker").clickable { clicks += 1 }) { Text("capture clicks $clicks") }
        },
        HomeSectionId.TESTS to HomeSectionSlot(Icons.Outlined.Science, homeSectionTitle(HomeSectionId.TESTS)) {
            Text("tests body")
        },
    )

    private fun setHost(width: Int = 1000, height: Int = 700) {
        state.openHomeTab()
        rule.setContent {
            Column(Modifier.size(width.dp, height.dp).testTag("home-test-root")) {
                Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.End) {
                    LayoutMenuButton(state, onReclaimFocus = {}, wrap = true, menuBeside = false)
                }
                HomeTilingHost(
                    state = state,
                    slots = fakeSlots(),
                    onReclaimFocus = {},
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag).getBoundsInRoot()

    private fun density(): Float = rule.density.density

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
    fun defaultLayoutShowsOpenLeftOfCaptureAndBothAboveTests() {
        setHost()
        saveEvidenceScreenshot("home-wide-open.png", rule.onNodeWithTag("home-test-root").captureToImage())
        val open = bounds("home-card-open")
        val capture = bounds("home-card-capture")
        val tests = bounds("home-card-tests")
        assertTrue(open.right <= capture.left, "open $open should be left of capture $capture")
        assertTrue(open.bottom <= tests.top && capture.bottom <= tests.top, "open and capture should sit above tests")
        assertTrue(tests.width > open.width, "tests spans the full width")
    }

    @Test
    fun sectionGapsMatchTheLogViewTilingSpec() {
        setHost()
        val open = bounds("home-card-open")
        val capture = bounds("home-card-capture")
        val tests = bounds("home-card-tests")
        assertEquals(LOG_VIEW_GAP_ROW_DP, capture.left.value - open.right.value)
        assertEquals(LOG_VIEW_GAP_COLUMN_DP, tests.top.value - maxOf(open.bottom.value, capture.bottom.value))
        assertEquals(LOG_VIEW_HOST_PAD_DP.toFloat(), open.left.value)
    }

    @Test
    fun hidingAndRestoringASectionWorksThroughTheToolbarLayoutMenu() {
        setHost()
        rule.onNodeWithTag("home-hide-tests").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("home-card-tests").assertDoesNotExist()
        assertEquals(listOf(HomeSectionId.TESTS), state.settings.homeHiddenSections)
        rule.onNodeWithTag("layout-menu-button").performClick()
        rule.onNodeWithTag("home-layout-menu-panel-tests").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("home-card-tests").assertExists()
        assertTrue(state.settings.homeHiddenSections.isEmpty())
    }

    @Test
    fun theNewTabHasNoStartHeadingOrDragTipAndLayoutControlsLiveInTheToolbarMenu() {
        setHost()
        rule.onNodeWithText("Start", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Tip: drag", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("home-preset-DEFAULT").assertDoesNotExist()
        rule.onNodeWithTag("layout-menu-button").performClick()
        rule.onNodeWithTag("layout-menu-popup").assertExists()
        rule.onNodeWithTag("home-preset-DEFAULT").assertExists()
        rule.onNodeWithTag("home-layout-menu-panel-tests").assertExists()
        saveEvidenceScreenshot("home-layout-menu.png", rule.onNodeWithTag("layout-menu-popup").captureToImage())
    }

    @Test
    fun layoutMenuIsHiddenOnTheTestsSurface() {
        setHost()
        rule.runOnIdle { state.activeSurface = ActiveSurface.Tests }
        rule.waitForIdle()
        rule.onNodeWithTag("layout-menu-button").assertDoesNotExist()
    }

    @Test
    fun splitterLinesStopWhereTheCardCornersStart() {
        setHost()
        val corner = 8.dp
        // Default layout: splitter 0 is the vertical gap between Open and Capture, splitter 1 the horizontal gap above Tests.
        val vertical = bounds("home-splitter-0")
        val verticalLine = bounds("home-splitter-line-0")
        assertEquals(vertical.top + corner, verticalLine.top)
        assertEquals(vertical.bottom - corner, verticalLine.bottom)
        assertEquals(vertical.left, verticalLine.left)
        val horizontal = bounds("home-splitter-1")
        val horizontalLine = bounds("home-splitter-line-1")
        assertEquals(horizontal.left + corner, horizontalLine.left)
        assertEquals(horizontal.right - corner, horizontalLine.right)
        assertEquals(horizontal.top, horizontalLine.top)
    }

    @Test
    fun theLastVisibleSectionCannotBeHidden() {
        setHost()
        rule.runOnIdle { state.updateSettings { it.withHomeLayoutPreset(HomeLayoutPreset.LOGS_ONLY) } }
        rule.waitForIdle()
        rule.onNodeWithTag("home-card-capture").assertDoesNotExist()
        rule.onNodeWithTag("home-hide-open").assertIsNotEnabled()
        rule.onNodeWithTag("layout-menu-button").performClick()
        rule.onNodeWithTag("home-layout-menu-panel-capture").assertExists()
        rule.onNodeWithTag("home-layout-menu-panel-tests").assertExists()
        rule.onNodeWithTag("home-hide-open").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("home-card-open").assertExists()
    }

    @Test
    fun clickingAPresetChangesTheStoredLayout() {
        setHost()
        val before = state.settings.homeLayout
        rule.onNodeWithTag("layout-menu-button").performClick()
        rule.onNodeWithTag("home-preset-THREE_COLUMNS").performClick()
        rule.waitForIdle()
        assertNotEquals(before, state.settings.homeLayout)
        assertEquals(HomeLayoutPreset.THREE_COLUMNS.layout, state.settings.homeLayout)
        val open = bounds("home-card-open")
        val capture = bounds("home-card-capture")
        val tests = bounds("home-card-tests")
        assertTrue(open.right <= capture.left && capture.right <= tests.left, "three columns, left to right")
    }

    @Test
    fun gripDragCommitsOnceOnReleaseAndKeepsSectionState() {
        setHost()
        rule.onNodeWithTag("capture-clicker").performClick()
        rule.onNodeWithTag("capture-clicker").assertExists()
        val before = state.settings
        val grip = rule.onNodeWithTag("home-grip-tests")
        val gripBounds = bounds("home-grip-tests")
        val capture = bounds("home-card-capture")
        val d = density()
        // The right part of the capture card, vertically centred: well clear of the page bands.
        val targetX = (capture.left.value + capture.width.value * 0.85f) * d
        val targetY = (capture.top.value + capture.height.value / 2f) * d
        val local = Offset(targetX - gripBounds.left.value * d, targetY - gripBounds.top.value * d)
        grip.performTouchInput {
            down(center)
            moveBy(Offset(40f, -40f))
            moveTo(local)
        }
        rule.waitForIdle()
        assertEquals(before, state.settings, "nothing is written while dragging")
        rule.onNodeWithTag("home-drop-preview").assertExists()
        grip.performTouchInput { up() }
        rule.waitForIdle()
        rule.onNodeWithTag("home-drop-preview").assertDoesNotExist()
        assertNotEquals(before.homeLayout, state.settings.homeLayout)
        val root = state.settings.homeLayout
        assertTrue(root is HomeLayoutSplit && root.axis == HomeSplitAxis.ROW && root.children.size == 3, "tests joined the top row: $root")
        assertEquals(
            listOf(HomeSectionId.OPEN, HomeSectionId.CAPTURE, HomeSectionId.TESTS),
            root.children.map { (it as HomeLayoutLeaf).section },
        )
        // The capture body kept its remembered click although its card moved.
        rule.onNodeWithTag("capture-clicker").assertExists()
        assertTrue(bounds("home-card-tests").left >= bounds("home-card-capture").right)
    }

    @Test
    fun gripDragReleasedOverNothingChangesNothing() {
        setHost()
        val before = state.settings
        val grip = rule.onNodeWithTag("home-grip-tests")
        val own = bounds("home-card-tests")
        val gripBounds = bounds("home-grip-tests")
        val d = density()
        val local = Offset(
            (own.left.value + own.width.value / 2f - gripBounds.left.value) * d,
            (own.top.value + own.height.value / 2f - gripBounds.top.value) * d,
        )
        grip.performTouchInput {
            down(center)
            moveBy(Offset(30f, 30f))
            moveTo(local)
            up()
        }
        rule.waitForIdle()
        assertEquals(before, state.settings)
    }

    @Test
    fun splitterDragCommitsOnlyOnRelease() {
        setHost()
        val before = state.settings.homeLayout
        val openWidthBefore = bounds("home-card-open").width
        val splitter = rule.onNodeWithTag("home-splitter-0")
        splitter.performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
            moveBy(Offset(60f, 0f))
        }
        rule.waitForIdle()
        assertEquals(before, state.settings.homeLayout, "settings untouched mid-drag")
        assertTrue(bounds("home-card-open").width > openWidthBefore, "the draft already resizes the card")
        splitter.performTouchInput { up() }
        rule.waitForIdle()
        assertNotEquals(before, state.settings.homeLayout)
        val top = ((state.settings.homeLayout as HomeLayoutSplit).children[0] as HomeLayoutSplit)
        assertTrue(top.children[0].weight > top.children[1].weight, "open grew against capture: ${top.children.map { it.weight }}")
    }

    @Test
    fun arrowKeysOnAFocusedSplitterResizeImmediately() {
        setHost()
        val before = state.settings.homeLayout
        val splitter = rule.onNodeWithTag("home-splitter-0")
        splitter.performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        splitter.performKeyInput { keyDown(Key.DirectionRight) }
        rule.waitForIdle()
        assertNotEquals(before, state.settings.homeLayout)
        val top = ((state.settings.homeLayout as HomeLayoutSplit).children[0] as HomeLayoutSplit)
        assertTrue(top.children[0].weight > top.children[1].weight)
    }

    @Test
    fun narrowHostStacksTheCardsWithoutGripsOrSplitters() {
        setHost(width = 600, height = 800)
        val open = bounds("home-card-open")
        val capture = bounds("home-card-capture")
        assertTrue(open.bottom <= capture.top, "open above capture")
        // 75% of the viewport clamped to 320..560dp (the later cards are partly or fully scrolled out, so only the first is exact).
        assertTrue(open.height.value in 320f..560f, "stacked card height ${open.height}")
        rule.onNodeWithTag("home-card-tests").assertExists()
        rule.onNodeWithTag("home-grip-open").assertDoesNotExist()
        rule.onNodeWithTag("home-splitter-0").assertDoesNotExist()
        rule.onNodeWithTag("home-hide-open").assertExists()
        rule.onNodeWithTag("layout-menu-button").assertExists()
    }
}
