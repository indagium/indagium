package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private enum class BarTile(val key: String) { A("a"), B("b"), C("c"), D("d"), LOG("log") }

private const val BAR_HOST_W = 1000
private const val BAR_HOST_H = 400
private const val BAR_GAP_DP = 8f
private const val BAR_COLUMN_DP = 300f
private const val BAR_TILE_DP = 200f
private const val BAR_RESIZE_DP = 40f
private const val BAR_THUMB_DRAG_DP = 60f
private val BAR_HOST_BACKGROUND = Color(0xFF204060)

private val BAR_SPEC = TileChromeSpec<BarTile>(
    headerDp = 32f,
    titleSp = 12f,
    gapRowDp = BAR_GAP_DP,
    gapColumnDp = BAR_GAP_DP,
    cornerDp = 6,
    minTileDp = 60f,
    minPairShare = 0.1f,
    tagPrefix = "t",
    keyOf = { it.key },
    scrollMinDp = 80f,
    scrollMaxDp = 2000f,
    scrolls = { it != BarTile.LOG },
)

// A scrolling column of four tall tiles on the LEFT (300 dp wide), then the log: the column's scrollbar sits in the gap that also holds the
// column's width splitter.
private val LEFT_COLUMN = TileSplit(
    TileAxis.ROW,
    listOf(
        TileSplit(TileAxis.COLUMN, listOf(BarTile.A, BarTile.B, BarTile.C, BarTile.D).map { TileLeaf(it, 1f, BAR_TILE_DP) }, 1f, BAR_COLUMN_DP),
        TileLeaf(BarTile.LOG),
    ),
)

/**
 * The scrollbar of a scrolling column is drawn in the gap beside it, over the column's width splitter. Only its thumb takes the pointer:
 * the rest of the strip, above and below the thumb, still resizes the column (drag, cursor, double-click reset).
 */
@OptIn(ExperimentalTestApi::class)
class TilingScrollbarSplitterUiTest {
    @get:Rule
    val rule = createComposeRule()

    private var layout: TileNode<BarTile> by mutableStateOf(LEFT_COLUMN)
    private val commits = mutableListOf<TileNode<BarTile>>()

    private fun setHost() {
        rule.setContent {
            Box(Modifier.size(BAR_HOST_W.dp, BAR_HOST_H.dp).background(BAR_HOST_BACKGROUND)) {
                TilingSurface(
                    fullLayout = layout,
                    isVisible = { true },
                    collapsed = emptySet(),
                    parked = emptySet(),
                    maximized = null,
                    locked = false,
                    widthDp = BAR_HOST_W.toFloat(),
                    heightDp = BAR_HOST_H.toFloat(),
                    spec = BAR_SPEC,
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
                        // The column's default width, which a double-click on its splitter restores.
                        defaultDp = { node -> if (node is TileSplit && node.axis == TileAxis.COLUMN) BAR_COLUMN_DP else null },
                    ),
                ) {
                    BarTile.entries.forEach { id ->
                        Tile(id, TileSlot(icon = Icons.Outlined.Description, title = "Tile ${id.key}", collapsible = true) { Box(Modifier.fillMaxSize()) })
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun top(id: BarTile) = bounds("t-card-${id.key}").top.value

    // The ROW gap between the column and the log: the splitter that is taller than wide.
    private fun columnSplitter(): String = (0..8).map { "t-splitter-$it" }.first { tag ->
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() && bounds(tag).let { it.height.value > it.width.value }
    }

    private val barX get() = bounds("t-scrollbar-a").let { (it.left.value + it.right.value) / 2 }

    private fun atDp(x: Float, y: Float) = Offset(x * rule.density.density, y * rule.density.density)

    @Test
    fun theScrollbarSitsInTheSplittersStripAndStaysAClearOfTheCards() {
        setHost()
        val strip = bounds(columnSplitter())
        val bar = bounds("t-scrollbar-a")
        assertEquals(BAR_COLUMN_DP, bounds("t-card-a").right.value, 0.5f)
        assertTrue(bar.left.value >= strip.left.value && bar.right.value <= strip.right.value, "the bar $bar is inside the splitter's strip $strip")
        assertEquals(BAR_HOST_H.toFloat(), bar.height.value, 0.5f)
    }

    @Test
    fun aHorizontalDragOnTheStripAwayFromTheThumbResizesTheColumnAndWritesOnRelease() {
        setHost()
        val thumb = tileScrollbarThumb(TileScrollRegion(BarTile.A, TileRect(0f, 0f, BAR_COLUMN_DP, BAR_HOST_H.toFloat()), 4 * BAR_TILE_DP + 3 * BAR_GAP_DP), 0f)
        assertTrue(thumb.bottom < 250f, "the thumb ends at ${thumb.bottom}, so y = 300 is track below it")
        val x = barX
        // Below the thumb, on the track: the press goes to the splitter, which resizes the column.
        rule.onRoot().performTouchInput {
            down(atDp(x, 300f))
            moveBy(Offset(BAR_RESIZE_DP / 2 * rule.density.density, 0f))
            moveBy(Offset(BAR_RESIZE_DP / 2 * rule.density.density, 0f))
        }
        rule.waitForIdle()
        assertEquals(0, commits.size, "nothing is written while dragging")
        val grown = bounds("t-card-a").width.value - BAR_COLUMN_DP
        assertTrue(grown in 20f..BAR_RESIZE_DP + 0.5f, "the column grew by $grown while dragging (touch slop eats a few dp)")
        assertEquals(0f, top(BarTile.A), 0.5f, "the column did not scroll")
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(1, commits.size, "written once, on release")
        val column = (commits.single() as TileSplit<BarTile>).children.first()
        assertEquals(BAR_COLUMN_DP + grown, column.sizeDp!!, 1f)
        assertEquals(BAR_COLUMN_DP + grown, bounds("t-card-a").width.value, 1f)
    }

    @Test
    fun theStripAboveTheThumbAndABetweenTheThumbAndTheEdgeAlsoResize() {
        setHost()
        // Scroll the thumb to the middle, then drag on the track above it.
        rule.onNodeWithTag("t-card-a").performMouseInput { moveTo(center); scroll(100f) }
        rule.waitForIdle()
        val offset = -top(BarTile.A)
        assertTrue(offset > 50f, "scrolled by the wheel: $offset")
        val thumbTop = bounds("t-scrollbar-a").top.value + tileScrollbarThumb(
            TileScrollRegion(BarTile.A, TileRect(0f, 0f, BAR_COLUMN_DP, BAR_HOST_H.toFloat()), 4 * BAR_TILE_DP + 3 * BAR_GAP_DP), offset,
        ).top
        assertTrue(thumbTop > 12f, "there is track above the thumb: $thumbTop")
        rule.onRoot().performTouchInput {
            down(atDp(barX, thumbTop / 2))
            moveBy(Offset(BAR_RESIZE_DP * rule.density.density, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(1, commits.size)
        assertTrue(bounds("t-card-a").width.value > BAR_COLUMN_DP + 20f, "resized from the track above the thumb")
        assertEquals(offset, -top(BarTile.A), 0.5f, "and the column stayed where it was")
    }

    @Test
    fun aDoubleClickOnTheStripAwayFromTheThumbResetsTheColumn() {
        setHost()
        val column = LEFT_COLUMN.children.first() as TileSplit<BarTile>
        layout = TileSplit(TileAxis.ROW, listOf(column.copy(sizeDp = 420f), LEFT_COLUMN.children.last()))
        rule.waitForIdle()
        assertEquals(420f, bounds("t-card-a").width.value, 0.5f)
        rule.onRoot().performTouchInput { doubleClick(atDp(barX, 300f)) }
        rule.waitForIdle()
        assertTrue(commits.isNotEmpty(), "the reset was written")
        assertEquals(BAR_COLUMN_DP, bounds("t-card-a").width.value, 0.5f, "the column is back at its default width")
    }

    @Test
    fun aVerticalDragOnTheThumbScrollsTheColumnAndResizesNothing() {
        setHost()
        val region = TileScrollRegion(BarTile.A, TileRect(0f, 0f, BAR_COLUMN_DP, BAR_HOST_H.toFloat()), 4 * BAR_TILE_DP + 3 * BAR_GAP_DP)
        val thumb = tileScrollbarThumb(region, 0f)
        val expected = tileScrollbarOffsetDelta(region, thumb, BAR_THUMB_DRAG_DP)
        rule.onRoot().performTouchInput {
            down(atDp(barX, thumb.height / 2))
            moveBy(Offset(0f, BAR_THUMB_DRAG_DP / 2 * rule.density.density))
            moveBy(Offset(0f, BAR_THUMB_DRAG_DP / 2 * rule.density.density))
            up()
        }
        rule.waitForIdle()
        val scrolled = -top(BarTile.A)
        assertTrue(scrolled in expected * 0.5f..expected + 0.5f, "a $BAR_THUMB_DRAG_DP dp thumb drag scrolled the column by $scrolled (up to $expected)")
        assertEquals(0, commits.size, "the thumb does not resize")
        assertEquals(BAR_COLUMN_DP, bounds("t-card-a").width.value, 0.5f)
    }

    @Test
    fun theWheelOverTheThumbStillScrollsTheColumn() {
        setHost()
        rule.onRoot().performMouseInput {
            moveTo(atDp(barX, 20f))
            scroll(100f)
        }
        rule.waitForIdle()
        assertTrue(top(BarTile.A) < -50f, "the wheel over the thumb scrolled: ${top(BarTile.A)}")
    }

    @Test
    fun theThumbMathPlacesItByTheOffsetAndKeepsTheMinimumHeight() {
        val region = TileScrollRegion(BarTile.A, TileRect(0f, 0f, 300f, 400f), 10_000f)
        val start = tileScrollbarThumb(region, 0f)
        assertEquals(TILE_SCROLLBAR_MIN_THUMB_DP, start.height, 0.01f)
        assertEquals(0f, start.top, 0.01f)
        assertEquals(400f, tileScrollbarThumb(region, region.maxOffset).bottom, 0.01f)
        val mid = tileScrollbarThumb(region, region.maxOffset / 2)
        assertEquals((400f - mid.height) / 2, mid.top, 0.01f)
        val grab = tileScrollbarGrab(start, 400f)
        assertEquals(0f, grab.top, 0.01f)
        assertEquals(start.height + TILE_SCROLLBAR_GRAB_MARGIN_DP, grab.height, 0.01f)
        // Dragging the thumb over its whole travel scrolls over the whole range.
        assertEquals(region.maxOffset, tileScrollbarOffsetDelta(region, start, 400f - start.height), 0.5f)
    }
}
