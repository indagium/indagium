package com.indagium.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.window.Popup
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private enum class FakeTile(val key: String) { A("a"), B("b"), C("c") }

private const val HOST_W = 1000
private const val HOST_H = 600
private const val HEADER_DP = 32f

private val FAKE_SPEC = TileChromeSpec<FakeTile>(
    headerDp = HEADER_DP,
    titleSp = 12f,
    gapRowDp = 8f,
    gapColumnDp = 8f,
    cornerDp = 6,
    sizeLabel = TileSizeLabelMode.ON_HOVER_OR_DRAG,
    sizeLabelMinDp = 0f,
    minTileDp = 60f,
    minPairShare = 0.1f,
    tagPrefix = "t",
    keyOf = { it.key },
)

private fun fakeLeaf(id: FakeTile, weight: Float = 1f) = TileLeaf(id, weight)

// A | (B over C)
private val SIDE_COLUMN: TileNode<FakeTile> = TileSplit(
    TileAxis.ROW,
    listOf(fakeLeaf(FakeTile.A), TileSplit(TileAxis.COLUMN, listOf(fakeLeaf(FakeTile.B), fakeLeaf(FakeTile.C)))),
)

// Drives TilingSurface with fake bodies (a clickable counter each): no AppState, no files.
class TilingSurfaceUiTest {
    @get:Rule
    val rule = createComposeRule()

    private var layout by mutableStateOf(SIDE_COLUMN)
    private var collapsed by mutableStateOf(emptySet<FakeTile>())
    private var parked by mutableStateOf(emptySet<FakeTile>())
    private var maximized by mutableStateOf<FakeTile?>(null)
    private var locked by mutableStateOf(false)
    private var sizeMode = TileSizeLabelMode.ON_HOVER_OR_DRAG
    private val commits = mutableListOf<TileNode<FakeTile>>()

    private fun slot(id: FakeTile) = TileSlot(
        icon = Icons.Outlined.Description,
        title = "Tile ${id.key}",
        collapsible = true,
        maximizable = true,
    ) {
        // Local remembered state: it must survive parking, maximizing and the tile moving about.
        var clicks by remember { mutableStateOf(0) }
        Box(Modifier.testTag("counter-${id.key}").clickable { clicks += 1 }) { Text("${id.key} clicks $clicks") }
    }

    private fun setHost(content: @Composable TileScope<FakeTile>.() -> Unit = { FakeTile.entries.forEach { Tile(it, slot(it)) } }) {
        rule.setContent {
            Box(Modifier.size(HOST_W.dp, HOST_H.dp)) {
                TilingSurface(
                    fullLayout = layout,
                    isVisible = { true },
                    collapsed = collapsed,
                    parked = parked,
                    maximized = maximized,
                    locked = locked,
                    widthDp = HOST_W.toFloat(),
                    heightDp = HOST_H.toFloat(),
                    spec = FAKE_SPEC.copy(sizeLabel = sizeMode),
                    callbacks = TilingCallbacks(
                        onCommitLayout = {
                            commits += it
                            layout = it
                        },
                        onHide = {},
                        onToggleCollapse = {},
                        onMaximize = {},
                        onReclaimFocus = {},
                        dropLabel = { "drop" },
                    ),
                    content = content,
                )
            }
        }
    }

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag).getBoundsInRoot()

    @Test
    fun collapsingAColumnTileShrinksItToItsHeaderAndTheNeighbourTakesTheRest() {
        setHost()
        val before = bounds("t-card-b").height
        assertTrue(bounds("t-card-c").height > before / 2, "c starts as tall as b")
        collapsed = setOf(FakeTile.C)
        rule.waitForIdle()
        assertEquals(HEADER_DP, bounds("t-card-c").height.value, 0.01f)
        assertTrue(bounds("t-card-b").height > before, "b grew into the freed height")
        rule.onNodeWithTag("t-collapse-c").assertExists()
    }

    @Test
    fun theSizeReadoutShowsOnlyWhileTheHeaderIsHovered() {
        setHost()
        rule.onNodeWithTag("t-size-a").assertDoesNotExist()
        rule.onNodeWithTag("t-header-a").performMouseInput { enter(center) }
        rule.waitForIdle()
        rule.onNodeWithTag("t-size-a").assertExists()
        rule.onNodeWithTag("t-header-a").performMouseInput { exit(Offset(-10f, -10f)) }
        rule.waitForIdle()
        rule.onNodeWithTag("t-size-a").assertDoesNotExist()
    }

    @Test
    fun lockingRemovesTheGripsButNotTheSplitters() {
        setHost()
        rule.onNodeWithTag("t-grip-a").assertExists()
        locked = true
        rule.waitForIdle()
        FakeTile.entries.forEach { rule.onNodeWithTag("t-grip-${it.key}").assertDoesNotExist() }
        rule.onNodeWithTag("t-splitter-0").assertExists()
    }

    @Test
    fun aParkedTileKeepsItsStateAndGivesItsSpaceToTheOthers() {
        setHost()
        val cBefore = bounds("t-card-c").height
        rule.onNodeWithTag("counter-b").performClick()
        rule.onNodeWithTag("counter-b").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("counter-b").assertTextEquals("b clicks 2")
        parked = setOf(FakeTile.B)
        rule.waitForIdle()
        assertEquals(0f, bounds("t-card-b").width.value, 0.01f)
        assertEquals(0f, bounds("t-card-b").height.value, 0.01f)
        assertTrue(bounds("t-card-c").height > cBefore, "c takes b's height")
        parked = emptySet()
        rule.waitForIdle()
        rule.onNodeWithTag("counter-b").assertTextEquals("b clicks 2")
        assertTrue(bounds("t-card-b").height > 0.dp)
    }

    @Test
    fun maximizingFillsTheHostAndEveryOtherBodyKeepsItsState() {
        setHost()
        rule.onNodeWithTag("counter-a").performClick()
        rule.onNodeWithTag("counter-c").performClick()
        rule.waitForIdle()
        maximized = FakeTile.C
        rule.waitForIdle()
        val full = bounds("t-card-c")
        assertEquals(HOST_W.toFloat(), full.width.value, 0.5f)
        assertEquals(HOST_H.toFloat(), full.height.value, 0.5f)
        assertEquals(0f, bounds("t-card-a").width.value, 0.01f)
        rule.onNodeWithTag("t-splitter-0").assertDoesNotExist()
        rule.onNodeWithTag("t-grip-c").assertDoesNotExist()
        rule.onNodeWithTag("t-maximize-c").assertExists()
        maximized = null
        rule.waitForIdle()
        assertTrue(bounds("t-card-a").width.value > 100f)
        rule.onNodeWithTag("counter-a").assertTextEquals("a clicks 1")
        rule.onNodeWithTag("counter-c").assertTextEquals("c clicks 1")
    }

    @Test
    fun doubleClickingASplitterEqualizesItsPair() {
        layout = TileSplit(TileAxis.ROW, listOf(fakeLeaf(FakeTile.A, 1f), fakeLeaf(FakeTile.B, 3f)))
        setHost { listOf(FakeTile.A, FakeTile.B).forEach { Tile(it, slot(it)) } }
        assertTrue(bounds("t-card-b").width > bounds("t-card-a").width)
        rule.onNodeWithTag("t-splitter-0").performTouchInput { doubleClick() }
        rule.waitForIdle()
        assertEquals(1, commits.size)
        assertEquals(listOf(2f, 2f), (commits.single() as TileSplit<FakeTile>).children.map { it.weight })
        assertEquals(bounds("t-card-a").width.value, bounds("t-card-b").width.value, 1f)
    }

    @Test
    fun aGripDragCommitsExactlyOnceOnRelease() {
        setHost()
        val grip = rule.onNodeWithTag("t-grip-c")
        val gripBounds = bounds("t-grip-c")
        val a = bounds("t-card-a")
        val d = rule.density.density
        // The right part of a's card, vertically centred: well clear of the page bands.
        val local = Offset(
            (a.left.value + a.width.value * 0.85f - gripBounds.left.value) * d,
            (a.top.value + a.height.value / 2f - gripBounds.top.value) * d,
        )
        grip.performTouchInput {
            down(center)
            moveBy(Offset(40f, -40f))
            moveTo(local)
        }
        rule.waitForIdle()
        assertEquals(0, commits.size, "nothing is committed while dragging")
        rule.onNodeWithTag("t-drop-preview").assertExists()
        grip.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(1, commits.size, "one commit, on release")
        rule.onNodeWithTag("t-drop-preview").assertDoesNotExist()
        val root = commits.single() as TileSplit<FakeTile>
        assertEquals(TileAxis.ROW, root.axis)
        assertEquals(listOf(FakeTile.A, FakeTile.C, FakeTile.B), tileLeafSections(root))
    }

    @Test
    fun oneComposableEmittingTwoTilesPlacesEachByItsId() {
        layout = TileSplit(TileAxis.ROW, listOf(fakeLeaf(FakeTile.A), fakeLeaf(FakeTile.B)))
        // Emitted in the opposite order to the layout, from one composable: placement follows the ids.
        setHost { BothTiles() }
        assertTrue(bounds("t-card-a").right <= bounds("t-card-b").left, "a is left of b although b is emitted first")
        assertTrue(bounds("t-card-a").width.value > 100f && bounds("t-card-b").width.value > 100f)
    }

    @Test
    fun aStrayPopupAtTheTopOfTheContentDoesNotBreakTheSurface() {
        setHost {
            Popup { Text("stray popup") }
            FakeTile.entries.forEach { Tile(it, slot(it)) }
        }
        rule.waitForIdle()
        FakeTile.entries.forEach { rule.onNodeWithTag("t-card-${it.key}").assertExists() }
        assertTrue(bounds("t-card-a").width.value > 100f)
    }

    @Composable
    private fun TileScope<FakeTile>.BothTiles() {
        Tile(FakeTile.B, slot(FakeTile.B))
        Tile(FakeTile.A, slot(FakeTile.A))
    }
}
