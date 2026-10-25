package com.indagium

import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import com.indagium.ui.TileDropTarget
import com.indagium.ui.TileEdge
import com.indagium.ui.TileGeometry
import com.indagium.ui.TileGeometrySpec
import com.indagium.ui.TileRect
import com.indagium.ui.TileScrollRegion
import com.indagium.ui.TileScrollState
import com.indagium.ui.dropTile
import com.indagium.ui.projectTileLayout
import com.indagium.ui.resetProjected
import com.indagium.ui.resizeProjected
import com.indagium.ui.tileDropTargetAt
import com.indagium.ui.tileGeometry
import com.indagium.ui.tileLeafSections
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
}
