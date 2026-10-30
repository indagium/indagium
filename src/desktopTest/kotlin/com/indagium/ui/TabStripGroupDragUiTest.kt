package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.indagium.model.LogTab
import com.indagium.model.TabStripStyle
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Real mouse drags of tabs into, within and out of a group. The state outcome of each drag is the long-standing behaviour; what these
 * pin is how it looks mid-drag: the dragged tab is opaque over its neighbours, a 2dp accent bar marks the gap it will land in, and
 * hovering a group's run shows that group's rail solid.
 */
@OptIn(ExperimentalTestApi::class)
class TabStripGroupDragUiTest {
    private companion object {
        // Past the strip's 8px drag threshold, so the next move is a drag.
        const val PAST_SLOP_PX = 14f
        const val SLOP_STEP_MS = 50L
        const val DRAG_STEP_MS = 100L
        const val RAIL_MIDDLE_DP = 1.5f
    }

    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private fun newState(ids: List<String>) {
        directory = createTempDirectory("tab-strip-drag").toFile()
        state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
        val tabs: List<LogTab> = ids.map { mkTab(it, "$it.log", emptyList()) }
        state.tabs = tabs
        state.activateTab(ids.first())
    }

    private fun setStrip() {
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.width(1000.dp).height(44.dp)) {
                    TabStrip(state, ChromeOrientation.HORIZONTAL, Modifier.fillMaxSize().testTag("drag-test-strip"))
                }
            }
        }
        rule.waitForIdle()
    }

    private fun exists(tag: String): Boolean =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Presses the mouse on [fromTag] and drags to [fraction] of the way across [toTag], leaving the button down. */
    private fun pressAndDrag(fromTag: String, toTag: String, fraction: Float = 0.5f) {
        rule.waitForIdle()
        val from = rule.onNodeWithTag(fromTag).getBoundsInRoot()
        val to = rule.onNodeWithTag(toTag).getBoundsInRoot()
        val density = rule.density.density
        val target = Offset(
            x = (to.left + to.width * fraction - from.left).value * density,
            y = ((to.top + to.bottom) / 2f - from.top).value * density,
        )
        rule.onNodeWithTag(fromTag).performMouseInput {
            moveTo(center)
            press(MouseButton.Primary)
            moveTo(Offset(center.x + (if (target.x > center.x) PAST_SLOP_PX else -PAST_SLOP_PX), center.y), SLOP_STEP_MS)
            moveTo(target, DRAG_STEP_MS)
            moveTo(target, DRAG_STEP_MS)
        }
        rule.waitForIdle()
    }

    private fun releaseMouse() {
        rule.onNodeWithTag("drag-test-strip").performMouseInput { release(MouseButton.Primary) }
        rule.waitForIdle()
    }

    private fun railPixel(tabKey: String): Int {
        val image = rule.onNodeWithTag("tab-$tabKey").captureToImage().toPixelMap()
        // The rail runs along the outer (top) edge of a top strip's tab; sample the middle of its 3dp band.
        return image[image.width / 2, (RAIL_MIDDLE_DP * rule.density.density).toInt()].toArgb()
    }

    private fun groupOfBandC(): String {
        val id = requireNotNull(state.createTabGroup(TabRef.Log("b")))
        state.assignTabToGroup(TabRef.Log("c"), id)
        return id
    }

    @Test
    fun draggingATabOntoAGroupShowsTheBarAnOpaqueTabAndASolidRailThenJoinsTheGroup() {
        newState(listOf("a", "b", "c", "d"))
        val groupId = groupOfBandC()
        setStrip()
        val groupColor = Color(state.tabGroups.single().colorArgb).toArgb()
        assertNotEquals(groupColor, railPixel("c"), "an idle member's rail is softer than the group colour")
        assertFalse(exists("tab-drop-bar"))

        pressAndDrag("tab-d", "tab-group-header-$groupId", fraction = 0.3f)

        assertTrue(exists("tab-drag-ghost-d"), "the dragged tab sits on an opaque backdrop")
        assertTrue(exists("tab-drop-bar"), "a bar marks the gap the tab will land in")
        assertEquals(groupColor, railPixel("c"), "hovering the group's run shows its rail solid")

        releaseMouse()
        assertEquals(groupId, state.tabGroupOf(TabRef.Log("d"))?.id)
        assertEquals(listOf(TabRef.Log("b"), TabRef.Log("c"), TabRef.Log("d")), state.tabGroups.single().members)
        assertFalse(exists("tab-drop-bar"), "the bar goes with the drag")
        assertFalse(exists("tab-drag-ghost-d"))
    }

    @Test
    fun draggingAMemberWithinItsGroupReordersItAndStillMarksTheGap() {
        newState(listOf("a", "b", "c", "d"))
        val groupId = groupOfBandC()
        setStrip()

        pressAndDrag("tab-c", "tab-b", fraction = 0.2f)
        assertTrue(exists("tab-drop-bar"))
        assertTrue(exists("tab-drag-ghost-c"))

        releaseMouse()
        assertEquals(groupId, state.tabGroupOf(TabRef.Log("c"))?.id)
        assertEquals(listOf(TabRef.Log("c"), TabRef.Log("b")), state.tabGroups.single().members)
    }

    @Test
    fun draggingAMemberOutOfItsGroupUngroupsItAndKeepsTheRestTogether() {
        newState(listOf("a", "b", "c", "d"))
        groupOfBandC()
        setStrip()

        pressAndDrag("tab-c", "tab-d", fraction = 0.8f)
        assertTrue(exists("tab-drop-bar"))

        releaseMouse()
        assertEquals(null, state.tabGroupOf(TabRef.Log("c")))
        assertEquals(listOf(TabRef.Log("b")), state.tabGroups.single().members)
        assertEquals(4, state.reconciledTabOrder().size)
        assertEquals(TabRef.Log("b"), state.reconciledTabOrder().first { it in state.tabGroups.single().members })
    }

    @Test
    fun theDraggedTabCoversItsNeighbourInsteadOfLettingItShowThrough() {
        newState(listOf("a", "b", "c", "d"))
        state.updateSettings { it.copy(tabStripStyle = TabStripStyle.PILL) }
        state.activateTab("c")
        setStrip()

        // Short of c's centre plus the snap bias, so c has not slid out of the way yet.
        pressAndDrag("tab-d", "tab-c", fraction = 0.85f)
        val dragged = rule.onNodeWithTag("tab-d").getBoundsInRoot()
        val neighbour = rule.onNodeWithTag("tab-c").getBoundsInRoot()
        val overlapLeft = maxOf(dragged.left, neighbour.left)
        val overlapRight = minOf(dragged.right, neighbour.right)
        assertTrue(overlapRight - overlapLeft > 40.dp, "the dragged tab overlaps its neighbour")

        // Two points on the dragged pill's flat top edge, clear of both labels: one over the neighbour, one over bare strip. With an
        // opaque backdrop both show the same colour; without it the active neighbour's tint would show through the first.
        val density = rule.density.density
        val overlapX = ((overlapLeft + overlapRight) / 2f - dragged.left).value * density
        val bareX = (neighbour.right + 14.dp - dragged.left).value * density
        val y = (3f * density).toInt()
        val image = rule.onNodeWithTag("tab-d").captureToImage().toPixelMap()
        val over = Color(image[overlapX.toInt(), y].toArgb())
        val bare = Color(image[bareX.toInt(), y].toArgb())

        fun near(a: Float, b: Float) = abs(a - b) < 2f / 255f
        assertTrue(
            near(over.red, bare.red) && near(over.green, bare.green) && near(over.blue, bare.blue),
            "the dragged tab looks the same over its neighbour as over bare strip: $over vs $bare",
        )
        releaseMouse()
    }
}
