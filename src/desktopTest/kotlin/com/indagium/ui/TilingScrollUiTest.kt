package com.indagium.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
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
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private enum class ScrollTile(val key: String) { LOG("log"), B("b"), C("c"), D("d"), E("e") }

private const val HOST_W = 1000
private const val HOST_H = 400
private const val GAP_DP = 8f
private const val COLUMN_WIDTH_DP = 300f
private const val COLUMN_CENTER_FROM_RIGHT_DP = 150f
private const val OUTER_EXTRA_DP = 400
private const val WHEEL_TURNS_TO_END = 40
private const val WHEEL_TURN = 200f
private val HOST_BACKGROUND = Color(0xFF204060)
private val OUTER_BACKGROUND = Color(0xFF884488)

private val SCROLL_SPEC = TileChromeSpec<ScrollTile>(
    headerDp = 32f,
    titleSp = 12f,
    gapRowDp = GAP_DP,
    gapColumnDp = GAP_DP,
    cornerDp = 6,
    minTileDp = 60f,
    minPairShare = 0.1f,
    tagPrefix = "t",
    keyOf = { it.key },
    scrollMinDp = 80f,
    scrollMaxDp = 2000f,
    scrolls = { it != ScrollTile.LOG },
)

private fun pinned(id: ScrollTile, dp: Float?): TileNode<ScrollTile> = TileLeaf(id, 1f, dp)

// LOG | a column of B, C, D, E (300 dp wide), each with a height of its own.
private fun layoutOf(vararg heights: Pair<ScrollTile, Float?>): TileNode<ScrollTile> = TileSplit(
    TileAxis.ROW,
    listOf(TileLeaf(ScrollTile.LOG), TileSplit(TileAxis.COLUMN, heights.map { pinned(it.first, it.second) }, 1f, COLUMN_WIDTH_DP)),
)

private val FOUR_TALL = layoutOf(ScrollTile.B to 200f, ScrollTile.C to 200f, ScrollTile.D to 200f, ScrollTile.E to 200f)

/** Drives a TilingSurface with a scrolling side column: wheel, scrollbar, resize, clipping, nested scrolling and drops. */
@OptIn(ExperimentalTestApi::class)
class TilingScrollUiTest {
    @get:Rule
    val rule = createComposeRule()

    private var layout by mutableStateOf(FOUR_TALL)
    private var collapsed by mutableStateOf(emptySet<ScrollTile>())
    private val commits = mutableListOf<TileNode<ScrollTile>>()
    private var innerScroll: ScrollState? = null
    private val clicks = mutableMapOf<ScrollTile, Int>()
    private val focusE = FocusRequester()

    private fun slot(id: ScrollTile) = TileSlot(icon = Icons.Outlined.Description, title = "Tile ${id.key}", collapsible = true) {
        if (id == ScrollTile.C) {
            val inner = rememberScrollState()
            innerScroll = inner
            Column(Modifier.fillMaxSize().verticalScroll(inner).testTag("inner-c")) {
                repeat(40) { Text("row $it", Modifier.height(24.dp)) }
            }
        } else {
            Box(
                Modifier.fillMaxSize().testTag("body-${id.key}")
                    .then(if (id == ScrollTile.E) Modifier.focusRequester(focusE).focusable() else Modifier)
                    .clickable { clicks[id] = (clicks[id] ?: 0) + 1 },
            )
        }
    }

    private fun setHost() {
        rule.setContent {
            // The host sits in a taller box of another colour, so anything a card paints outside the host shows.
            Box(Modifier.size(HOST_W.dp, (HOST_H + OUTER_EXTRA_DP).dp).background(OUTER_BACKGROUND)) {
                Box(Modifier.size(HOST_W.dp, HOST_H.dp).background(HOST_BACKGROUND)) {
                    TilingSurface(
                        fullLayout = layout,
                        isVisible = { true },
                        collapsed = collapsed,
                        parked = emptySet(),
                        maximized = null,
                        locked = false,
                        widthDp = HOST_W.toFloat(),
                        heightDp = HOST_H.toFloat(),
                        spec = SCROLL_SPEC,
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
                    ) {
                        ScrollTile.entries.forEach { Tile(it, slot(it)) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun top(id: ScrollTile) = bounds("t-card-${id.key}").top.value

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun wheel(tag: String, delta: Float) {
        rule.onNodeWithTag(tag).performMouseInput {
            moveTo(center)
            scroll(delta)
        }
        rule.waitForIdle()
    }

    // A wheel turn over the column at [yDp] below the host's top: whichever tile is there gets it, scrolled or not.
    private fun wheelOverColumn(delta: Float, yDp: Float = 100f) {
        val x = (HOST_W - COLUMN_CENTER_FROM_RIGHT_DP) * rule.density.density
        rule.onRoot().performMouseInput {
            moveTo(Offset(x, yDp * rule.density.density))
            scroll(delta)
        }
        rule.waitForIdle()
    }

    private fun scrollToEnd() = repeat(WHEEL_TURNS_TO_END) { wheelOverColumn(WHEEL_TURN) }

    // ---- content taller than the window ------------------------------------------------------------------------

    @Test
    fun contentTallerThanTheHostScrollsWithTheWheelAndShowsAScrollbar() {
        setHost()
        assertTrue(exists("t-scrollbar-b"), "the column overflows, so it has a scrollbar")
        assertEquals(0f, top(ScrollTile.B), 0.5f)
        assertEquals(4 * 200f + 3 * GAP_DP, bounds("t-card-e").bottom.value - top(ScrollTile.B), 0.5f)
        assertTrue(bounds("t-card-e").top.value > HOST_H, "E starts below the host")
        wheelOverColumn(60f)
        assertTrue(top(ScrollTile.B) < 0f, "the wheel moved the content up: ${top(ScrollTile.B)}")
        scrollToEnd()
        // The end: the content's bottom lines up with the viewport's bottom, and nothing scrolls any further.
        assertEquals(HOST_H.toFloat(), bounds("t-card-e").bottom.value, 0.5f)
        wheelOverColumn(200f)
        assertEquals(HOST_H.toFloat(), bounds("t-card-e").bottom.value, 0.5f)
        // And back to the start.
        repeat(40) { wheelOverColumn(-200f) }
        assertEquals(0f, top(ScrollTile.B), 0.5f)
        // The log, outside the column, never moved.
        assertEquals(0f, bounds("t-card-log").top.value, 0.5f)
    }

    @Test
    fun cardsOutsideTheViewportDrawNothingOutsideTheHost() {
        setHost()
        // Unscrolled, C ends 8 dp and D, E wholly below the host's bottom edge: none of it may paint there.
        assertTrue(bounds("t-card-d").top.value > HOST_H)
        val image = rule.onRoot().captureToImage().toPixelMap()
        val density = rule.density.density
        val columnX = ((HOST_W - 150f) * density).toInt()
        // Below the host there is only the surrounding box: just under its edge, as far down as the next tile would reach.
        listOf(HOST_H + 2f, HOST_H + 12f, HOST_H + 100f, HOST_H + 300f).forEach { y ->
            assertEquals(Color(OUTER_BACKGROUND.toArgb()), image[columnX, (y * density).toInt()], "nothing is drawn $y dp down")
        }
    }

    @Test
    fun theWheelScrollsTheColumnFromAnyTileAndInnerScrollablesGoFirst() {
        setHost()
        val inner = checkNotNull(innerScroll)
        // The pointer is over C's own scrollable list: it scrolls, the column stays.
        wheel("inner-c", 60f)
        assertTrue(inner.value > 0, "the inner list scrolled")
        assertEquals(0f, top(ScrollTile.B), 0.5f)
        // Once it can scroll no further the rest goes to the column.
        runBlocking { inner.scrollTo(inner.maxValue) }
        rule.waitForIdle()
        wheel("inner-c", 120f)
        assertTrue(top(ScrollTile.B) < 0f, "the column scrolled once the list was at its end")
    }

    @Test
    fun focusingAFieldInATileBelowTheViewportScrollsItIntoView() {
        setHost()
        assertTrue(bounds("t-card-e").top.value > HOST_H, "E starts below the host")
        rule.runOnUiThread { focusE.requestFocus() }
        rule.waitForIdle()
        // E's body took the keyboard focus and the column scrolled just far enough to show it.
        assertTrue(bounds("t-card-e").bottom.value <= HOST_H + 0.5f, "E is in view: ${bounds("t-card-e")}")
        assertTrue(bounds("t-card-e").bottom.value > HOST_H - 40f, "and not scrolled further than needed")
    }

    @Test
    fun aClickOnAScrolledTileReachesItsBody() {
        setHost()
        scrollToEnd()
        // E is the last tile and fully in view now.
        rule.onNodeWithTag("body-e").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(1, clicks[ScrollTile.E])
    }

    // ---- shorter content ---------------------------------------------------------------------------------------

    @Test
    fun contentShorterThanTheHostFillsItAndHasNoScrollbar() {
        layout = layoutOf(ScrollTile.B to 80f, ScrollTile.C to 80f, ScrollTile.D to 80f)
        setHost()
        assertEquals(80f, bounds("t-card-b").height.value, 0.5f)
        assertEquals(80f, bounds("t-card-c").height.value, 0.5f)
        assertEquals(HOST_H.toFloat(), bounds("t-card-d").bottom.value, 0.5f)
        assertTrue(!exists("t-scrollbar-b"), "nothing to scroll")
        wheelOverColumn(100f)
        assertEquals(0f, top(ScrollTile.B), 0.5f)
    }

    // ---- resize ------------------------------------------------------------------------------------------------

    @Test
    fun draggingTheSplitterBelowATileResizesOnlyThatTileAndCommitsOnRelease() {
        setHost()
        val before = ScrollTile.entries.associateWith { bounds("t-card-${it.key}") }
        val splitter = rule.onNodeWithTag("t-splitter-1")
        val density = rule.density.density
        splitter.performTouchInput {
            down(center)
            moveBy(Offset(0f, 30f * density))
            moveBy(Offset(0f, 30f * density))
        }
        rule.waitForIdle()
        assertEquals(0, commits.size, "nothing is written while dragging")
        // The touch slop eats a few pixels of the 60 dp that were dragged.
        val grown = bounds("t-card-b").height.value - before.getValue(ScrollTile.B).height.value
        assertTrue(grown in 40f..60.5f, "B grew by $grown while dragging")
        assertEquals(before.getValue(ScrollTile.C).top.value + grown, top(ScrollTile.C), 1f)
        splitter.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(1, commits.size)
        val column = (commits.single() as TileSplit<ScrollTile>).children.last() as TileSplit<ScrollTile>
        assertEquals(listOf(200f, 200f, 200f), column.children.drop(1).map { it.sizeDp })
        assertEquals(200f + grown, column.children[0].sizeDp!!, 1f)
        // The others kept their size; the tiles below moved down.
        listOf(ScrollTile.C, ScrollTile.D, ScrollTile.E).forEach { id ->
            assertEquals(before.getValue(id).height.value, bounds("t-card-${id.key}").height.value, 0.5f, "$id kept its height")
        }
        assertEquals(before.getValue(ScrollTile.E).top.value + grown, top(ScrollTile.E), 1f)
    }

    @Test
    fun aTileCannotBeDraggedBelowItsMinimumHeight() {
        setHost()
        val density = rule.density.density
        rule.onNodeWithTag("t-splitter-1").performTouchInput {
            down(center)
            moveBy(Offset(0f, -600f * density))
            up()
        }
        rule.waitForIdle()
        assertEquals(80f, bounds("t-card-b").height.value, 0.5f)
    }

    // ---- collapse ----------------------------------------------------------------------------------------------

    @Test
    fun collapsingShrinksATileToItsHeaderAndTheTilesBelowMoveUp() {
        setHost()
        val dBefore = top(ScrollTile.D)
        collapsed = setOf(ScrollTile.C)
        rule.waitForIdle()
        assertEquals(32f, bounds("t-card-c").height.value, 0.5f)
        assertEquals(dBefore - 200f + 32f, top(ScrollTile.D), 0.5f)
    }

    // ---- drop --------------------------------------------------------------------------------------------------

    @Test
    fun aDropOntoAScrolledColumnLandsOnTheTileThatIsOnScreenThere() {
        setHost()
        scrollToEnd()
        // E (the last tile) now fills the bottom of the viewport; D is above it, partly in view.
        val density = rule.density.density
        val grip = rule.onNodeWithTag("t-grip-log")
        val gripBounds = bounds("t-grip-log")
        val e = bounds("t-card-e")
        // A little below E's middle, clear of the page bands: the target is E's bottom edge.
        val targetX = e.left.value + e.width.value / 2f
        val targetY = e.top.value + e.height.value * 0.6f
        grip.performTouchInput {
            down(center)
            moveBy(Offset(10f, 10f))
            moveTo(Offset((targetX - gripBounds.left.value) * density, (targetY - gripBounds.top.value) * density))
        }
        rule.waitForIdle()
        assertEquals(0, commits.size)
        rule.onNodeWithTag("t-drop-preview").getUnclippedBoundsInRoot().let { preview ->
            assertTrue(preview.top.value >= e.top.value - 1f, "the preview is on E: $preview vs $e")
            assertTrue(preview.bottom.value <= HOST_H + 0.5f, "the preview is on the visible part of E, not where E is unscrolled: $preview")
        }
        grip.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(1, commits.size)
        val root = commits.single()
        assertEquals(listOf(ScrollTile.B, ScrollTile.C, ScrollTile.D, ScrollTile.E, ScrollTile.LOG), tileLeafSections(root))
    }

    // ---- splitters are invisible --------------------------------------------------------------------------------

    private fun pixelsOf(tag: String): Set<Int> {
        val image = rule.onRoot().captureToImage().toPixelMap()
        val density = rule.density.density
        val b = bounds(tag)
        val colors = HashSet<Int>()
        for (x in (b.left.value * density).toInt() until (b.right.value * density).toInt()) {
            for (y in (b.top.value * density).toInt() until (b.bottom.value * density).toInt()) colors += image[x, y].toArgb()
        }
        return colors
    }

    @Test
    fun splittersDrawNothingAtRestHoveredOrDragged() {
        layout = layoutOf(ScrollTile.B to 80f, ScrollTile.C to 80f, ScrollTile.D to 80f)
        setHost()
        val background = HOST_BACKGROUND.toArgb()
        // t-splitter-0 is the vertical gap between the log and the column, t-splitter-1 a horizontal gap inside the column.
        listOf("t-splitter-0", "t-splitter-1").forEach { tag ->
            assertEquals(setOf(background), pixelsOf(tag), "$tag at rest")
            rule.onNodeWithTag(tag).performMouseInput { moveTo(center) }
            rule.waitForIdle()
            assertEquals(setOf(background), pixelsOf(tag), "$tag hovered")
            rule.onNodeWithTag(tag).performMouseInput { moveTo(Offset(-500f, -500f)) }
            rule.waitForIdle()
        }
        // While it is being dragged it draws nothing either (and still resizes).
        val splitter = rule.onNodeWithTag("t-splitter-0")
        val before = bounds("t-card-log").width.value
        splitter.performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
        }
        rule.waitForIdle()
        assertTrue(bounds("t-card-log").width.value != before, "the drag still resizes")
        assertEquals(setOf(background), pixelsOf("t-splitter-0"), "dragged")
        splitter.performTouchInput { up() }
    }
}
