package com.indagium

import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import com.indagium.ui.TILE_SCROLLBAR_CLEARANCE_DP
import com.indagium.ui.TileBodyMeasure
import com.indagium.ui.TileBodySizing
import com.indagium.ui.TileDropTarget
import com.indagium.ui.TileEdge
import com.indagium.ui.TileGeometry
import com.indagium.ui.TileGeometrySpec
import com.indagium.ui.TileRect
import com.indagium.ui.TileScrollRegion
import com.indagium.ui.TileScrollState
import com.indagium.ui.TileSizing
import com.indagium.ui.dropTile
import com.indagium.ui.projectTileLayout
import com.indagium.ui.resetProjected
import com.indagium.ui.resizeProjected
import com.indagium.ui.tileDropTargetAt
import com.indagium.ui.tileGeometry
import com.indagium.ui.tileLeafSections
import com.indagium.ui.tileScrollbarRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private enum class S { LOG, B, C, D, E }

private const val EPS = 0.01f
private const val GAP = 6f
private const val MIN = 72f
private const val HEADER = 32f
private const val MIN_SHARE = 0.1f
private const val SCROLL_MIN = 80f
private const val SCROLL_MAX = 2000f
private const val HOST_W = 1000f
private const val HOST_H = 600f

private val SPEC = TileGeometrySpec(
    gapRowDp = 8f, gapColumnDp = GAP, collapsedExtentDp = HEADER, minTileDp = MIN, minPairShare = MIN_SHARE,
    scrollMinDp = SCROLL_MIN, scrollMaxDp = SCROLL_MAX,
)

// Everything but LOG may sit in a scrolling column.
private val scrolls: (S) -> Boolean = { it != S.LOG }

private fun leaf(id: S, weight: Float = 1f, dp: Float? = null): TileNode<S> = TileLeaf(id, weight, dp)

private fun column(vararg c: TileNode<S>, weight: Float = 1f, dp: Float? = null): TileNode<S> = TileSplit(TileAxis.COLUMN, c.toList(), weight, dp)

private fun row(vararg c: TileNode<S>, weight: Float = 1f, dp: Float? = null): TileNode<S> = TileSplit(TileAxis.ROW, c.toList(), weight, dp)

// LOG | a column of side sections, the shape of the filter column next to the log.
private fun sideColumn(vararg c: TileNode<S>): TileNode<S> = row(leaf(S.LOG), column(*c, dp = 250f))

private fun geometry(root: TileNode<S>, collapsed: Set<S> = emptySet(), height: Float = HOST_H) =
    tileGeometry(root, HOST_W, height, SPEC, collapsed, scrolls)

private fun TileGeometry<S>.rect(id: S): TileRect = assertNotNull(cardOf(id), "card $id")

private fun resize(
    root: TileNode<S>,
    gapIndex: Int,
    delta: Float,
    collapsed: Set<S> = emptySet(),
    hidden: Set<S> = emptySet(),
    height: Float = HOST_H,
): TileNode<S> {
    val projection = assertNotNull(projectTileLayout(root) { it !in hidden })
    val gap = geometry(projection.root, collapsed, height).gaps.filter { it.scrolling }[gapIndex - 1]
    return resizeProjected(root, projection, gap, delta, MIN, MIN_SHARE, collapsed, HEADER, SCROLL_MIN, SCROLL_MAX)
}

private fun TileNode<S>.slots(): List<Pair<Float, Float?>> =
    ((this as TileSplit<S>).children.last() as TileSplit<S>).children.map { it.weight to it.sizeDp }

class TileScrollColumnTest {
    // ---- which splits scroll ---------------------------------------------------------------------------------------

    @Test
    fun aColumnOfSideSectionsIsAScrollingColumnAndOneWithTheLogIsNot() {
        val side = geometry(sideColumn(leaf(S.B), leaf(S.C)))
        assertEquals(1, side.scrollRegions.size)
        assertEquals(S.B, side.scrollRegions.single().key)
        assertEquals(listOf(false, true), side.gaps.map { it.scrolling })
        // The log sits in the column: it always fits its window.
        val withLog = geometry(row(column(leaf(S.LOG), leaf(S.B)), column(leaf(S.C), leaf(S.D))))
        assertEquals(listOf(S.C), withLog.scrollRegions.map { it.key })
        assertTrue(geometry(row(column(leaf(S.LOG), leaf(S.B)))).scrollRegions.isEmpty())
        // Without a predicate nothing scrolls, as before.
        assertTrue(tileGeometry(sideColumn(leaf(S.B), leaf(S.C)), HOST_W, HOST_H, SPEC).scrollRegions.isEmpty())
    }

    @Test
    fun aRowNestedInAScrollingColumnIsLaidOutInsideItsChildRect() {
        val geo = geometry(sideColumn(leaf(S.B), row(leaf(S.C), column(leaf(S.D), leaf(S.E)))))
        // One region, for the outer column; the nested column is laid out normally inside the row's rect.
        assertEquals(1, geo.scrollRegions.size)
        val d = geo.rect(S.D)
        val e = geo.rect(S.E)
        assertEquals(d.w, e.w, EPS)
        assertEquals(geo.rect(S.C).h, d.h + GAP + e.h, EPS)
        assertTrue(geo.cards.filter { it.section != S.LOG }.all { it.scrollRegion == 0 })
        assertNull(geo.cards.single { it.section == S.LOG }.scrollRegion)
    }

    // ---- sizes -----------------------------------------------------------------------------------------------------

    @Test
    fun aWeightedColumnLooksExactlyAsItDidBeforeAndNeverScrolls() {
        val root = sideColumn(leaf(S.B, 2f), leaf(S.C, 1f), leaf(S.D, 1f, dp = 90f))
        val before = tileGeometry(root, HOST_W, HOST_H, SPEC)
        val now = geometry(root)
        listOf(S.LOG, S.B, S.C, S.D).forEach { assertEquals(before.rect(it), now.rect(it), "rect of $it") }
        val region = now.scrollRegions.single()
        assertFalse(region.scrolls)
        assertEquals(HOST_H, region.contentHeight, EPS)
        assertEquals(0f, region.maxOffset)
    }

    @Test
    fun pinnedHeightsAddUpToContentTallerThanTheViewport() {
        val geo = geometry(sideColumn(leaf(S.B, dp = 300f), leaf(S.C, dp = 300f), leaf(S.D, dp = 300f)))
        val region = geo.scrollRegions.single()
        assertEquals(3 * 300f + 2 * GAP, region.contentHeight, EPS)
        assertEquals(HOST_H, region.viewport.h, EPS)
        assertEquals(3 * 300f + 2 * GAP - HOST_H, region.maxOffset, EPS)
        assertTrue(region.scrolls)
        assertEquals(0f, geo.rect(S.B).y, EPS)
        assertEquals(300f + GAP, geo.rect(S.C).y, EPS)
        assertEquals(2 * (300f + GAP), geo.rect(S.D).y, EPS)
        assertEquals(300f, geo.rect(S.D).h, EPS)
        // The cards belong to the region, and the column's width is the viewport's.
        assertTrue(geo.cards.filter { it.section != S.LOG }.all { it.scrollRegion == 0 })
        assertEquals(geo.rect(S.B).w, region.viewport.w, EPS)
    }

    @Test
    fun aPinnedTileSmallerThanTheMinimumIsRaisedToIt() {
        val geo = geometry(sideColumn(leaf(S.B, dp = 20f), leaf(S.C, dp = 300f)))
        assertEquals(80f, geo.rect(S.B).h, EPS)
    }

    @Test
    fun whenEverythingIsPinnedAndShortTheLastOpenTileTakesTheRest() {
        val geo = geometry(sideColumn(leaf(S.B, dp = 100f), leaf(S.C, dp = 100f), leaf(S.D, dp = 100f)))
        assertEquals(100f, geo.rect(S.B).h, EPS)
        assertEquals(100f, geo.rect(S.C).h, EPS)
        assertEquals(HOST_H - 2 * (100f + GAP), geo.rect(S.D).h, EPS)
        assertEquals(HOST_H, geo.rect(S.D).bottom, EPS)
        assertFalse(geo.scrollRegions.single().scrolls)
        // With the last one collapsed it is the one before that.
        val collapsed = geometry(sideColumn(leaf(S.B, dp = 100f), leaf(S.C, dp = 100f), leaf(S.D, dp = 100f)), collapsed = setOf(S.D))
        assertEquals(HEADER, collapsed.rect(S.D).h, EPS)
        assertEquals(HOST_H - (100f + GAP) - HEADER - GAP, collapsed.rect(S.C).h, EPS)
    }

    @Test
    fun weightedTilesShareWhatThePinnedOnesLeaveAndNeverGetLessThanTheMinimum() {
        val roomy = geometry(sideColumn(leaf(S.B, dp = 200f), leaf(S.C, 1f), leaf(S.D, 3f)))
        val share = HOST_H - 2 * GAP - 200f
        assertEquals(share / 4, roomy.rect(S.C).h, EPS)
        assertEquals(share * 3 / 4, roomy.rect(S.D).h, EPS)
        // Pinned tiles are not squeezed any more: the weighted ones keep the minimum and the column scrolls.
        val tight = geometry(sideColumn(leaf(S.B, dp = 500f), leaf(S.C, 1f), leaf(S.D, 1f)))
        assertEquals(500f, tight.rect(S.B).h, EPS)
        assertEquals(MIN, tight.rect(S.C).h, EPS)
        assertEquals(MIN, tight.rect(S.D).h, EPS)
        assertTrue(tight.scrollRegions.single().scrolls)
    }

    @Test
    fun aCollapsedTileKeepsOnlyItsHeader() {
        val geo = geometry(sideColumn(leaf(S.B, dp = 300f), leaf(S.C, dp = 300f), leaf(S.D, dp = 300f)), collapsed = setOf(S.C))
        assertEquals(HEADER, geo.rect(S.C).h, EPS)
        assertEquals(300f + GAP + HEADER + GAP, geo.rect(S.D).y, EPS)
        assertEquals(listOf(true, false), geo.gaps.filter { it.scrolling }.map { it.resizable })
    }

    // ---- resize ----------------------------------------------------------------------------------------------------

    @Test
    fun resizingAGapChangesOnlyTheTileAboveAndTheTilesBelowMoveDown() {
        val root = sideColumn(leaf(S.B, dp = 200f), leaf(S.C, dp = 200f), leaf(S.D, dp = 200f), leaf(S.E, dp = 200f))
        val before = geometry(root)
        val resized = resize(root, 2, 70f)
        val after = geometry(resized)
        assertEquals(before.rect(S.B), after.rect(S.B))
        assertEquals(before.rect(S.C).h + 70f, after.rect(S.C).h, EPS)
        assertEquals(before.rect(S.D).h, after.rect(S.D).h, EPS)
        assertEquals(before.rect(S.E).h, after.rect(S.E).h, EPS)
        assertEquals(before.rect(S.D).y + 70f, after.rect(S.D).y, EPS)
        assertEquals(before.rect(S.E).y + 70f, after.rect(S.E).y, EPS)
        assertEquals(before.scrollRegions.single().contentHeight + 70f, after.scrollRegions.single().contentHeight, EPS)
    }

    @Test
    fun theFirstResizePinsTheWeightedTilesAtTheHeightTheyHaveSoNothingJumps() {
        val root = sideColumn(leaf(S.B, 2f), leaf(S.C, 1f), leaf(S.D, 1f))
        val before = geometry(root)
        val resized = resize(root, 1, 40f)
        val after = geometry(resized)
        assertEquals(before.rect(S.B).h + 40f, after.rect(S.B).h, EPS)
        assertEquals(before.rect(S.C).h, after.rect(S.C).h, EPS)
        assertEquals(before.rect(S.D).h, after.rect(S.D).h, EPS)
        resized.slots().forEach { assertNotNull(it.second, "every tile has a height now: ${resized.slots()}") }
        // The content is 40 dp taller than the window, so it scrolls instead of squeezing C and D.
        assertEquals(40f, after.scrollRegions.single().maxOffset, EPS)
    }

    @Test
    fun resizingNeverBakesInTheStretchOfTheLastTile() {
        val root = sideColumn(leaf(S.B, dp = 100f), leaf(S.C, dp = 100f), leaf(S.D, dp = 100f))
        val resized = resize(root, 1, 50f)
        assertEquals(listOf(150f, 100f, 100f), resized.slots().map { it.second })
        val after = geometry(resized)
        assertEquals(HOST_H, after.rect(S.D).bottom, EPS)
        assertEquals(HOST_H - 150f - 100f - 2 * GAP, after.rect(S.D).h, EPS)
    }

    @Test
    fun aTileCannotBeMadeSmallerThanTheMinimumOrLargerThanTheMaximum() {
        val root = sideColumn(leaf(S.B, dp = 300f), leaf(S.C, dp = 300f))
        assertEquals(80f, geometry(resize(root, 1, -5000f)).rect(S.B).h, EPS)
        assertEquals(2000f, geometry(resize(root, 1, 90000f)).rect(S.B).h, EPS)
    }

    @Test
    fun aGapUnderACollapsedTileDoesNothing() {
        val root = sideColumn(leaf(S.B, dp = 300f), leaf(S.C, dp = 300f), leaf(S.D, dp = 300f))
        val collapsed = setOf(S.C)
        val geo = geometry(root, collapsed)
        assertEquals(listOf(true, false), geo.gaps.filter { it.scrolling }.map { it.resizable })
        val gap = geo.gaps.last()
        val projection = assertNotNull(projectTileLayout(root) { true })
        assertEquals(root, resizeProjected(root, projection, gap, 30f, MIN, MIN_SHARE, collapsed, HEADER, SCROLL_MIN, SCROLL_MAX))
        // The gap above the collapsed tile resizes the tile above it, and leaves the collapsed one alone.
        val above = resize(root, 1, 30f, collapsed)
        assertEquals(listOf(330f, 300f, 300f), above.slots().map { it.second })
    }

    @Test
    fun hiddenTilesKeepTheirSlotWhenAVisibleGapIsResized() {
        // C is hidden: the gap the user sees is B | D, and C's own slot is untouched.
        val root = sideColumn(leaf(S.B, 1f), leaf(S.C, 5f, dp = 123f), leaf(S.D, 1f))
        val resized = resize(root, 1, 25f, hidden = setOf(S.C))
        val slots = resized.slots()
        assertEquals(5f to 123f, slots[1])
        assertNotNull(slots[0].second)
        assertNotNull(slots[2].second)
        assertEquals(geometry(root.let { projectTileLayout(it) { id -> id != S.C }!!.root }).rect(S.B).h + 25f, slots[0].second!!, EPS)
    }

    @Test
    fun doubleClickingAScrollingGapResetsOnlyTheTileAboveIt() {
        val root = sideColumn(leaf(S.B, dp = 400f), leaf(S.C, dp = 320f), leaf(S.D, dp = 200f))
        val projection = assertNotNull(projectTileLayout(root) { true })
        val gap = geometry(root).gaps.first { it.scrolling }
        val reset = resetProjected(root, projection, gap) { if ((it as? TileLeaf<S>)?.section == S.B) 220f else null }
        assertEquals(listOf(220f, 320f, 200f), reset.slots().map { it.second })
        // Without a default the tile above goes back to being weighted.
        val unpinned = resetProjected(root, projection, gap) { null }
        assertEquals(listOf(null, 320f, 200f), unpinned.slots().map { it.second })
    }

    // ---- scrolled view and drops -----------------------------------------------------------------------------------

    private val tall = geometry(sideColumn(leaf(S.B, dp = 300f), leaf(S.C, dp = 300f), leaf(S.D, dp = 300f)))

    private fun visible(offset: Float) = tall.visibleCards { offset }

    @Test
    fun scrollingShiftsTheCardsAndCutsThemToTheViewport() {
        val top = visible(0f)
        assertEquals(300f, top.rect(S.B).h, EPS)
        // C starts at 306 and would end at 606: cut at the viewport's bottom. D is below the viewport altogether.
        assertEquals(HOST_H - (300f + GAP), top.rect(S.C).h, EPS)
        assertNull(top.cardOf(S.D))
        val end = tall.scrollRegions.single().maxOffset
        val scrolled = visible(end)
        // At the end the first tile has scrolled out of sight, and the last one ends at the viewport's bottom.
        assertNull(scrolled.cardOf(S.B))
        assertEquals(HOST_H, scrolled.rect(S.D).bottom, EPS)
        assertEquals(300f, scrolled.rect(S.D).h, EPS)
        // A card half scrolled out is cut at the viewport's top.
        val half = visible(450f)
        assertEquals(0f, half.rect(S.C).y, EPS)
        assertEquals(300f + GAP + 300f - 450f, half.rect(S.C).h, EPS)
        // The log, outside any region, is where it was.
        assertEquals(tall.rect(S.LOG), scrolled.rect(S.LOG))
    }

    @Test
    fun aDropIsHitTestedAgainstWhatIsOnScreen() {
        val columnX = tall.scrollRegions.single().viewport.x + 10f
        val unscrolled = tileDropTargetAt(visible(0f), HOST_W, HOST_H, columnX, 300f + GAP + 150f, S.LOG)
        assertEquals(S.C, (unscrolled as TileDropTarget.OnSection).section)
        // Scrolled by 306: the point that was over C is now over D, and B has gone.
        val scrolled = tileDropTargetAt(visible(300f + GAP), HOST_W, HOST_H, columnX, 300f + GAP + 150f, S.LOG)
        assertEquals(S.D, (scrolled as TileDropTarget.OnSection).section)
        // Dropping the log onto the bottom half of D lands beside D in the column, below it.
        val root = sideColumn(leaf(S.B, dp = 300f), leaf(S.C, dp = 300f), leaf(S.D, dp = 300f))
        val dropped = dropTile(root, S.LOG, TileDropTarget.OnSection(S.D, TileEdge.BOTTOM))
        assertEquals(listOf(S.B, S.C, S.D, S.LOG), tileLeafSections(dropped))
    }

    // ---- scroll state ----------------------------------------------------------------------------------------------

    @Test
    fun scrollOffsetsAreKeptInsideTheContentAndReportWhatWasConsumed() {
        val state = TileScrollState<S>()
        val region = tall.scrollRegions.single()
        state.regions = listOf(region)
        assertEquals(0f, state.offsetOf(region))
        assertEquals(120f, state.scrollBy(S.B, 120f), EPS)
        assertEquals(120f, state.offsetOf(region), EPS)
        // Past the end only what was left is consumed; past the start likewise.
        assertEquals(region.maxOffset - 120f, state.scrollBy(S.B, 99999f), EPS)
        assertEquals(region.maxOffset, state.offsetOf(region), EPS)
        assertEquals(-region.maxOffset, state.scrollBy(S.B, -99999f), EPS)
        assertEquals(0f, state.scrollBy(S.B, -5f))
        // A stored offset follows the content when it shrinks.
        state.scrollTo(S.B, region.maxOffset)
        val shorter = TileScrollRegion(S.B, region.viewport, region.viewport.h + 50f)
        assertEquals(50f, state.offsetOf(shorter), EPS)
        // An unknown column consumes nothing.
        assertEquals(0f, state.scrollBy(S.E, 10f))
    }

    @Test
    fun regionsKnowWhetherTheyScroll() {
        val viewport = TileRect(0f, 0f, 200f, 100f)
        assertFalse(TileScrollRegion(S.B, viewport, 100f).scrolls)
        assertFalse(TileScrollRegion(S.B, viewport, 100.4f).scrolls)
        assertTrue(TileScrollRegion(S.B, viewport, 101f).scrolls)
        assertEquals(40f, TileScrollRegion(S.B, viewport, 140f).maxOffset, EPS)
    }

    // ---- content-sized tiles ---------------------------------------------------------------------------------------

    // The log view's setup: room beyond the host's right edge for a scrollbar there, and a splitter under the last tile.
    private val sizedSpec = SPEC.copy(scrollEdgeRoomDp = 8f, scrollEndHandle = true)

    private fun sizedGeometry(root: TileNode<S>, sizing: Map<S, TileSizing>, collapsed: Set<S> = emptySet(), spec: TileGeometrySpec = sizedSpec) =
        tileGeometry(root, HOST_W, HOST_H, spec, collapsed, scrolls) { sizing[it] }

    private fun resizeSized(root: TileNode<S>, sizing: Map<S, TileSizing>, gapIndex: Int, delta: Float): TileNode<S> {
        val projection = assertNotNull(projectTileLayout(root) { true })
        val gap = sizedGeometry(projection.root, sizing).gaps.filter { it.scrolling }[gapIndex - 1]
        return resizeProjected(root, projection, gap, delta, MIN, MIN_SHARE, emptySet(), HEADER, SCROLL_MIN, SCROLL_MAX) { sizing[it] }
    }

    // Content: exactly 100 dp. Flex: 120..400 dp, 200 by default.
    private val content100 = TileSizing(100f, 100f, 100f)
    private val flex120to400 = TileSizing(120f, 400f, 200f)

    @Test
    fun aContentTileIsExactlyAsTallAsItsContentWhateverItsStoredSize() {
        val sizing = mapOf(S.B to content100, S.C to flex120to400)
        val root = sideColumn(leaf(S.B, dp = 400f), leaf(S.C, dp = 300f))
        val geo = sizedGeometry(root, sizing)
        // The stored 400 is clamped at layout time to what the content needs; the stored tree is untouched.
        assertEquals(100f, geo.rect(S.B).h, EPS)
        assertEquals(300f, geo.rect(S.C).h, EPS)
        assertEquals(listOf(false, true), geo.gaps.filter { it.scrolling }.map { it.resizable })
        // Nothing to drag under B: a resize of that gap leaves the layout as it is.
        val projection = assertNotNull(projectTileLayout(root) { true })
        val gap = geo.gaps.first { it.scrolling }
        assertEquals(root, resizeProjected(root, projection, gap, 80f, MIN, MIN_SHARE, emptySet(), HEADER, SCROLL_MIN, SCROLL_MAX) { sizing[it] })
        // Unpinned or pinned small, B is the same 100.
        assertEquals(100f, sizedGeometry(sideColumn(leaf(S.B), leaf(S.C, dp = 300f)), sizing).rect(S.B).h, EPS)
        assertEquals(100f, sizedGeometry(sideColumn(leaf(S.B, dp = 20f), leaf(S.C, dp = 300f)), sizing).rect(S.B).h, EPS)
    }

    @Test
    fun aFlexTileResizesWithinItsBoundsAndABiggerStoredSizeIsClampedWithoutBeingRewritten() {
        val sizing = mapOf(S.B to flex120to400, S.C to content100)
        val root = sideColumn(leaf(S.B, dp = 300f), leaf(S.C))
        assertEquals(400f, sizedGeometry(resizeSized(root, sizing, 1, 5000f), sizing).rect(S.B).h, EPS)
        assertEquals(120f, sizedGeometry(resizeSized(root, sizing, 1, -5000f), sizing).rect(S.B).h, EPS)
        // An old file stored 1500: the tile is drawn at its maximum and the tree keeps the 1500 until the user resizes.
        val old = sideColumn(leaf(S.B, dp = 1500f), leaf(S.C))
        assertEquals(400f, sizedGeometry(old, sizing).rect(S.B).h, EPS)
        assertEquals(1500f, (((old as TileSplit<S>).children.last() as TileSplit<S>).children.first()).sizeDp)
        // A resize starts from the height it is drawn at, not from the stored number.
        assertEquals(350f, sizedGeometry(resizeSized(old, sizing, 1, -50f), sizing).rect(S.B).h, EPS)
    }

    @Test
    fun anUnpinnedTileTakesItsDefaultAndTheLastOneGrowsIntoWhatIsLeftOnlyUpToItsMaximum() {
        val extent = HOST_H - GAP - GAP // two tiles: one gap between, one end handle below
        // Content on top, a flex tile with a default last: it grows to its maximum.
        val grown = sizedGeometry(sideColumn(leaf(S.B), leaf(S.C)), mapOf(S.B to content100, S.C to flex120to400))
        assertEquals(100f, grown.rect(S.B).h, EPS)
        assertEquals(400f, grown.rect(S.C).h, EPS)
        // With a larger maximum it takes everything that is left, which fills the column exactly (and nothing scrolls).
        val roomy = sizedGeometry(sideColumn(leaf(S.B), leaf(S.C)), mapOf(S.B to content100, S.C to TileSizing(120f, 900f, 200f)))
        assertEquals(extent - 100f, roomy.rect(S.C).h, EPS)
        assertFalse(roomy.scrollRegions.single().scrolls)
        // A stored size is not stretched, and neither is a last tile that is only content: the rest stays empty.
        val pinned = sizedGeometry(sideColumn(leaf(S.B), leaf(S.C, dp = 150f)), mapOf(S.B to content100, S.C to flex120to400))
        assertEquals(150f, pinned.rect(S.C).h, EPS)
        val contentLast = sizedGeometry(sideColumn(leaf(S.B), leaf(S.C)), mapOf(S.B to flex120to400, S.C to content100))
        assertEquals(200f, contentLast.rect(S.B).h, EPS)
        assertEquals(100f, contentLast.rect(S.C).h, EPS)
        assertTrue(contentLast.rect(S.C).bottom < HOST_H - 100f, "the column's leftover stays empty")
    }

    @Test
    fun fillTilesShareWhatIsLeftByWeightEachWithinItsBounds() {
        val fill = { max: Float -> TileSizing(100f, max, null) }
        val extent = HOST_H - GAP - GAP
        val even = sizedGeometry(sideColumn(leaf(S.B), leaf(S.C)), mapOf(S.B to fill(2000f), S.C to fill(2000f)))
        assertEquals(extent / 2, even.rect(S.B).h, EPS)
        assertEquals(extent / 2, even.rect(S.C).h, EPS)
        // C cannot be taller than its content, so B gets the rest.
        val capped = sizedGeometry(sideColumn(leaf(S.B), leaf(S.C)), mapOf(S.B to fill(2000f), S.C to fill(200f)))
        assertEquals(200f, capped.rect(S.C).h, EPS)
        assertEquals(extent - 200f, capped.rect(S.B).h, EPS)
        // Weights count: 3 to 1.
        val weighted = sizedGeometry(sideColumn(leaf(S.B, 3f), leaf(S.C, 1f)), mapOf(S.B to fill(2000f), S.C to fill(2000f)))
        assertEquals(extent * 3 / 4, weighted.rect(S.B).h, EPS)
    }

    @Test
    fun theLastTileHasAnEndHandleThatResizesItAndWritesItsSize() {
        val sizing = mapOf(S.B to content100, S.C to flex120to400)
        val root = sideColumn(leaf(S.B), leaf(S.C, dp = 250f))
        val geo = sizedGeometry(root, sizing)
        val handles = geo.gaps.filter { it.scrolling }
        assertEquals(listOf(1, 2), handles.map { it.index }, "a gap under B and one under the last tile")
        val end = handles.last()
        assertEquals(geo.rect(S.C).bottom, end.rect.y, EPS)
        assertEquals(GAP, end.rect.h, EPS)
        assertEquals(geo.rect(S.C).w, end.rect.w, EPS)
        assertTrue(end.resizable)
        // The content ends below the handle, so scrolling reaches it.
        assertEquals(end.rect.bottom, geo.scrollRegions.single().contentHeight, EPS)
        val resized = resizeSized(root, sizing, 2, 60f)
        val after = sizedGeometry(resized, sizing)
        assertEquals(310f, after.rect(S.C).h, EPS)
        assertEquals(listOf(null, 310f), (((resized as TileSplit<S>).children.last() as TileSplit<S>).children).map { it.sizeDp })
        // Double click: the tile goes back to the height its content asks for.
        val projection = assertNotNull(projectTileLayout(resized) { true })
        val reset = resetProjected(resized, projection, sizedGeometry(resized, sizing).gaps.last { it.scrolling }) { null }
        assertEquals(listOf(null, null), (((reset as TileSplit<S>).children.last() as TileSplit<S>).children).map { it.sizeDp })
        // Without the option there is no handle, as before.
        assertEquals(1, sizedGeometry(root, sizing, spec = SPEC).gaps.count { it.scrolling })
    }

    @Test
    fun theFirstResizePinsOnlyTheTilesThatDependOnTheOthers() {
        val fill = TileSizing(100f, 2000f, null)
        val sizing = mapOf(S.B to flex120to400, S.C to content100, S.D to fill, S.E to flex120to400)
        val root = sideColumn(leaf(S.B), leaf(S.C), leaf(S.D), leaf(S.E))
        val before = sizedGeometry(root, sizing)
        val resized = resizeSized(root, sizing, 1, 30f)
        val slots = ((resized as TileSplit<S>).children.last() as TileSplit<S>).children.map { it.sizeDp }
        // B (resized), D (shares what is left) and E (the last one, which may grow into what is left) are pinned; C is just content.
        assertEquals(listOf(230f, null, before.rect(S.D).h, before.rect(S.E).h), slots)
        val after = sizedGeometry(resized, sizing)
        assertEquals(before.rect(S.D).h, after.rect(S.D).h, EPS)
        assertEquals(before.rect(S.E).h, after.rect(S.E).h, EPS)
    }

    @Test
    fun aRowNestedInAScrollingColumnIsAsTallAsItsTallestTileAsksAndResizesLikeATile() {
        // A tile dropped beside another one makes a row inside the column. The column is full (E alone is 500 of 600 dp), so the row used to get
        // the 88 dp that were left over and was squeezed; now it asks for what its tiles ask for: the flex tile's 200 dp by default.
        val sizing = mapOf(S.E to TileSizing(500f, 500f, 500f), S.C to flex120to400, S.D to content100)
        val root = row(leaf(S.LOG), column(leaf(S.E), row(leaf(S.C), leaf(S.D)), dp = 250f))
        val geo = sizedGeometry(root, sizing)
        assertEquals(500f, geo.rect(S.E).h, EPS)
        assertEquals(200f, geo.rect(S.C).h, EPS)
        assertEquals(200f, geo.rect(S.D).h, EPS)
        assertEquals(geo.rect(S.C).y, geo.rect(S.D).y, EPS, "side by side")
        // The gap under the row is its handle: it resizes the row between the least and the most its tiles allow, and writes it as the row's size.
        val handle = geo.gaps.filter { it.scrolling }.last()
        assertTrue(handle.resizable)
        val resized = resizeSized(root, sizing, 2, 30f)
        val nested = ((resized as TileSplit<S>).children.last() as TileSplit<S>).children.last()
        assertEquals(230f, nested.sizeDp!!, EPS)
        val least = resizeSized(root, sizing, 2, -1000f)
        assertEquals(120f, (((least as TileSplit<S>).children.last() as TileSplit<S>).children.last()).sizeDp!!, EPS)
    }

    @Test
    fun theCardsUseTheWholeColumnAndTheScrollbarLivesInTheGapBesideIt() {
        val sizing = mapOf(S.B to flex120to400, S.C to flex120to400)
        val geo = sizedGeometry(sideColumn(leaf(S.B, dp = 400f), leaf(S.C, dp = 400f)), sizing)
        val region = geo.scrollRegions.single()
        // No width is given up for the bar, overflowing or not.
        geo.cards.filter { it.section != S.LOG }.forEach { assertEquals(region.viewport.w, it.rect.w, EPS, "${it.section} is narrowed") }
        geo.gaps.filter { it.scrolling }.forEach { assertEquals(region.viewport.w, it.rect.w, EPS) }
        val short = sizedGeometry(sideColumn(leaf(S.B, dp = 150f)), mapOf(S.B to flex120to400))
        assertEquals(region.viewport.w, short.rect(S.B).w, EPS)
        assertFalse(short.scrollRegions.single().scrolls)
        // The column in these tests is the last one of the row, so the room beyond it is the surface's edge room.
        assertEquals(8f, region.roomRightDp, EPS)
    }

    @Test
    fun aColumnWithANeighbourHasTheRowGapBesideItAndOneAtTheEdgeHasTheEdgeRoom() {
        val sizing = mapOf(S.B to flex120to400, S.C to flex120to400)
        val side = TileSplit(TileAxis.COLUMN, listOf(leaf(S.B, dp = 400f), leaf(S.C, dp = 400f)), 1f, 300f)
        val both = TileSplit(TileAxis.ROW, listOf(side, leaf(S.LOG), side.copy(children = listOf(leaf(S.D, dp = 400f), leaf(S.E, dp = 400f)))))
        val geo = tileGeometry(both, HOST_W, HOST_H, sizedSpec.copy(scrollEdgeRoomDp = 6f), emptySet(), scrolls) { sizing[it] ?: flex120to400 }
        val rooms = geo.scrollRegions.map { it.roomRightDp }
        assertEquals(listOf(SPEC.gapRowDp, 6f), rooms, "left column: the 8 dp gap to the log; right column: the room at the host's edge")
    }

    @Test
    fun theScrollbarIsCentredInTheRoomWithAClearanceAndNarrowerWhenTheRoomIsSmall() {
        val viewport = TileRect(10f, 20f, 200f, 300f)

        fun bar(room: Float) = tileScrollbarRect(TileScrollRegion(S.B, viewport, 900f, room))
        // The 8 dp gap holds the 6 dp bar with a dp on each side.
        assertEquals(TileRect(211f, 20f, 6f, 300f), bar(8f))
        // A 6 dp room (the host padding) gets a 4 dp bar with a dp on each side, never touching a card or the edge.
        assertEquals(TileRect(211f, 20f, 4f, 300f), bar(6f))
        // A roomier gap centres the full bar.
        assertEquals(TileRect(213f, 20f, 6f, 300f), bar(12f))
        // A surface that keeps no room at all draws the bar over the column's own right edge.
        assertEquals(TileRect(204f, 20f, 6f, 300f), bar(0f))
        // Whatever the room, the bar is between the card and what is beyond.
        listOf(6f, 7f, 8f, 10f, 14f).forEach { room ->
            val rect = bar(room)
            val clear = TILE_SCROLLBAR_CLEARANCE_DP - EPS
            assertTrue(rect.x >= viewport.right + clear && rect.right <= viewport.right + room - clear, "room $room: $rect")
        }
    }

    @Test
    fun aTileSizingComesFromWhatTheBodyMeasured() {
        // A flex body: 33 dp of header, 50 dp that always shows and a 600 dp list capped at 240 by default.
        val flex = TileBodyMeasure(TileBodySizing.FLEX, chromeDp = 33f, fixedDp = 50f, flexNaturalDp = 600f, flexCapDp = 240f)
        // Resizable up to the surface's maximum (not just to the list's height); the unstored height and the leftover-space stretch stay content based.
        assertEquals(TileSizing(33f + 50f + 40f, 2000f, 33f + 50f + 240f, fitDp = 33f + 50f + 600f), flex.toSizing(2000f))
        // A short list is shown whole by default (the same height as before) but the tile can be made taller than it: the extra is empty list area.
        val short = flex.copy(flexNaturalDp = 20f).toSizing(2000f)!!
        assertEquals(33f + 50f + 20f, short.minDp, EPS)
        assertEquals(2000f, short.maxDp, EPS)
        assertEquals(short.minDp, short.defaultDp!!, EPS)
        assertEquals(short.minDp, short.fitDp, EPS)
        assertTrue(short.resizable)
        // An empty list leaves the header and the fixed part by default and still resizes.
        val empty = flex.copy(flexNaturalDp = 0f).toSizing(2000f)!!
        assertEquals(83f, empty.minDp, EPS)
        assertEquals(83f, empty.defaultDp!!, EPS)
        assertEquals(83f, empty.fitDp, EPS)
        assertEquals(2000f, empty.maxDp, EPS)
        assertTrue(empty.resizable)
        // No cap: the tile shares the column by weight, within [min, fit]. It may be resized to the surface's maximum.
        val fill = flex.copy(flexCapDp = null, flexNaturalDp = 5000f).toSizing(2000f)!!
        assertNull(fill.defaultDp)
        assertEquals(2000f, fill.maxDp, EPS)
        assertEquals(2000f, fill.fitDp, EPS)
        val emptyFill = flex.copy(flexCapDp = null, flexNaturalDp = 0f).toSizing(2000f)!!
        assertNull(emptyFill.defaultDp)
        assertEquals(83f, emptyFill.fitDp, EPS)
        assertEquals(2000f, emptyFill.maxDp, EPS)
        // Content: header + body, no more, no less. Fill (not measured): unknown.
        val content = TileBodyMeasure(TileBodySizing.CONTENT, chromeDp = 33f, fixedDp = 47f).toSizing(2000f)!!
        assertEquals(TileSizing(80f, 80f, 80f), content)
        assertFalse(content.resizable)
        assertNull(TileBodyMeasure(TileBodySizing.FILL, 33f).toSizing(2000f))
    }

    // ---- flex tiles resize regardless of their content ----------------------------------------------------------------------------------------

    // A flex tile whose list holds nothing today: 80 dp by default (header + fixed part), resizable up to the surface's maximum.
    private val emptyFlex = TileSizing(80f, 2000f, 80f, fitDp = 80f)

    @Test
    fun aFlexTileWithAnEmptyListResizesTallerThanItsContentAndKeepsItsDefaultHeightUntilThen() {
        val sizing = mapOf(S.B to emptyFlex, S.C to TileSizing(100f, 100f, 100f))
        val root = sideColumn(leaf(S.B), leaf(S.C))
        // Default: content based, and not stretched into the room the column has.
        assertEquals(80f, sizedGeometry(root, sizing).rect(S.B).h, EPS)
        assertTrue(sizedGeometry(root, sizing).gaps.first { it.scrolling }.resizable, "there is a handle under it")
        // Dragged taller than any content it has; the size is the tile's own sizeDp.
        val resized = resizeSized(root, sizing, 1, 300f)
        assertEquals(380f, sizedGeometry(resized, sizing).rect(S.B).h, EPS)
        assertEquals(380f, ((resized as TileSplit<S>).children.last() as TileSplit<S>).children.first().sizeDp!!, EPS)
        // And as far as the surface's maximum, not a dp further.
        assertEquals(2000f, sizedGeometry(resizeSized(root, sizing, 1, 99999f), sizing).rect(S.B).h, EPS)
        // Back down to its least: the fixed part with no list.
        assertEquals(80f, sizedGeometry(resizeSized(resized, sizing, 1, -5000f), sizing).rect(S.B).h, EPS)
    }

    @Test
    fun aFillTileIsAsTallAsItsContentByDefaultButResizesPastIt() {
        val fill = TileSizing(100f, 2000f, null, fitDp = 140f)
        val sizing = mapOf(S.B to content100, S.C to fill)
        val root = sideColumn(leaf(S.B), leaf(S.C))
        // Sharing what is left by weight, it is never taller than its content...
        assertEquals(140f, sizedGeometry(root, sizing).rect(S.C).h, EPS)
        // ... until the user asks for more (the end handle under the last tile).
        val resized = resizeSized(root, sizing, 2, 400f)
        assertEquals(540f, sizedGeometry(resized, sizing).rect(S.C).h, EPS)
        // A stored size past the content is honoured as it is.
        assertEquals(500f, sizedGeometry(sideColumn(leaf(S.B), leaf(S.C, dp = 500f)), sizing).rect(S.C).h, EPS)
    }
}
