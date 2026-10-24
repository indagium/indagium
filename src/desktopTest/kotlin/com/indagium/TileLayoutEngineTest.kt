package com.indagium

import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import com.indagium.ui.TileDropTarget
import com.indagium.ui.TileEdge
import com.indagium.ui.TileGeometrySpec
import com.indagium.ui.TileRect
import com.indagium.ui.dropTile
import com.indagium.ui.maximizedTileGeometry
import com.indagium.ui.normalizeTileLayout
import com.indagium.ui.projectTileLayout
import com.indagium.ui.resetTilePair
import com.indagium.ui.resizeProjected
import com.indagium.ui.resizeTileSplit
import com.indagium.ui.stackedTileGeometry
import com.indagium.ui.tileGeometry
import com.indagium.ui.tileLeafSections
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private enum class T { A, B, C, D, E }

private const val EPS = 0.01f
private const val MIN_DP = 100f
private const val MIN_SHARE = 0.1f

private val SPEC = TileGeometrySpec(gapRowDp = 8f, gapColumnDp = 4f, minTileDp = MIN_DP, minPairShare = MIN_SHARE)

private fun leaf(id: T, weight: Float = 1f, dp: Float? = null) = TileLeaf(id, weight, dp)

private fun row(vararg c: TileNode<T>, weight: Float = 1f, dp: Float? = null) = TileSplit(TileAxis.ROW, c.toList(), weight, dp)

private fun col(vararg c: TileNode<T>, weight: Float = 1f, dp: Float? = null) = TileSplit(TileAxis.COLUMN, c.toList(), weight, dp)

private fun TileNode<T>.child(i: Int): TileNode<T> = (this as TileSplit<T>).children[i]

private fun assertRect(expected: TileRect, actual: TileRect?) {
    assertNotNull(actual)
    assertEquals(expected.x, actual.x, EPS, "x of $actual")
    assertEquals(expected.y, actual.y, EPS, "y of $actual")
    assertEquals(expected.w, actual.w, EPS, "w of $actual")
    assertEquals(expected.h, actual.h, EPS, "h of $actual")
}

private fun resize(root: TileNode<T>, index: Int, delta: Float, extent: Float, path: List<Int> = emptyList(), collapsed: Set<T> = emptySet()) =
    resizeTileSplit(root, path, index, delta, extent, MIN_DP, MIN_SHARE, collapsed)

class TileLayoutEngineTest {
    // ---- geometry ------------------------------------------------------------------------------------------------

    @Test
    fun aPinnedChildTakesItsSizeAndTheWeightedOnesShareTheRest() {
        val geo = tileGeometry(row(leaf(T.A, dp = 300f), leaf(T.B), leaf(T.C, 3f)), 1016f, 500f, SPEC)
        assertRect(TileRect(0f, 0f, 300f, 500f), geo.cardOf(T.A))
        assertRect(TileRect(308f, 0f, 175f, 500f), geo.cardOf(T.B))
        assertRect(TileRect(491f, 0f, 525f, 500f), geo.cardOf(T.C))
        assertTrue(geo.gaps.all { it.resizable && it.splitExtent == 1000f })
    }

    @Test
    fun pinnedSizesScaleDownWhenTheyWouldSqueezeTheWeightedChildren() {
        // 900 pinned + 2 x 100 minimum does not fit in 1000: the pin scales to 800.
        val geo = tileGeometry(row(leaf(T.A, dp = 900f), leaf(T.B), leaf(T.C)), 1016f, 100f, SPEC)
        assertEquals(800f, geo.cardOf(T.A)!!.w, EPS)
        assertEquals(100f, geo.cardOf(T.B)!!.w, EPS)
        assertEquals(100f, geo.cardOf(T.C)!!.w, EPS)
    }

    @Test
    fun whenEveryChildIsPinnedTheSizesActAsWeights() {
        val geo = tileGeometry(row(leaf(T.A, dp = 200f), leaf(T.B, dp = 600f)), 408f, 100f, SPEC)
        assertEquals(100f, geo.cardOf(T.A)!!.w, EPS)
        assertEquals(300f, geo.cardOf(T.B)!!.w, EPS)
    }

    @Test
    fun aPinnedSizeAppliesAlongAColumnsHeight() {
        val geo = tileGeometry(col(leaf(T.A, dp = 120f), leaf(T.B)), 200f, 504f, SPEC)
        assertEquals(120f, geo.cardOf(T.A)!!.h, EPS)
        assertEquals(380f, geo.cardOf(T.B)!!.h, EPS)
        assertEquals(200f, geo.cardOf(T.B)!!.w, EPS)
    }

    @Test
    fun gapsUseTheirOwnSizePerAxis() {
        val geo = tileGeometry(col(row(leaf(T.A), leaf(T.B)), leaf(T.C)), 1008f, 504f, SPEC)
        val vertical = geo.gaps.single { it.axis == TileAxis.ROW }
        val horizontal = geo.gaps.single { it.axis == TileAxis.COLUMN }
        assertEquals(8f, vertical.rect.w, EPS)
        assertEquals(4f, horizontal.rect.h, EPS)
        assertEquals(1000f, vertical.splitExtent, EPS)
        assertEquals(500f, horizontal.splitExtent, EPS)
        assertRect(TileRect(0f, 0f, 500f, 250f), geo.cardOf(T.A))
        assertRect(TileRect(0f, 254f, 1008f, 250f), geo.cardOf(T.C))
    }

    @Test
    fun aCollapsedLeafOfAColumnTakesExactlyItsExtentAndFreezesItsGaps() {
        val geo = tileGeometry(col(leaf(T.A), leaf(T.B), leaf(T.C)), 400f, 700f, SPEC, collapsed = setOf(T.B))
        assertRect(TileRect(0f, 0f, 400f, 330f), geo.cardOf(T.A))
        assertRect(TileRect(0f, 334f, 400f, 32f), geo.cardOf(T.B))
        assertRect(TileRect(0f, 370f, 400f, 330f), geo.cardOf(T.C))
        assertEquals(listOf(false, false), geo.gaps.map { it.resizable })
    }

    @Test
    fun aCollapsedLeafOfARowIsLaidOutNormallyAndOnlyColumnNeighboursOfItFreeze() {
        val inRow = tileGeometry(row(leaf(T.A), leaf(T.B)), 408f, 100f, SPEC, collapsed = setOf(T.B))
        assertEquals(200f, inRow.cardOf(T.B)!!.w, EPS)
        assertTrue(inRow.gaps.single().resizable)
        val four = tileGeometry(col(leaf(T.A), leaf(T.B), leaf(T.C), leaf(T.D)), 100f, 812f, SPEC, collapsed = setOf(T.B))
        assertEquals(listOf(false, false, true), four.gaps.map { it.resizable })
        assertEquals(256f, four.cardOf(T.A)!!.h, EPS)
        assertEquals(256f, four.cardOf(T.D)!!.h, EPS)
    }

    @Test
    fun degenerateHostsNeverProduceNegativeRects() {
        val geo = tileGeometry(row(leaf(T.A, dp = 300f), col(leaf(T.B), leaf(T.C, dp = 50f))), 5f, 5f, SPEC, setOf(T.B))
        assertEquals(3, geo.cards.size)
        assertTrue(geo.cards.all { it.rect.w >= 0f && it.rect.h >= 0f })
    }

    @Test
    fun maximizedAndStackedGeometry() {
        val max = maximizedTileGeometry(T.C, 300f, 200f)
        assertEquals(listOf(T.C), max.cards.map { it.section })
        assertRect(TileRect(0f, 0f, 300f, 200f), max.cardOf(T.C))
        assertTrue(max.gaps.isEmpty())
        val stack = stackedTileGeometry(listOf(T.B, T.A, T.D), 300f, 100f, 12f)
        assertEquals(listOf(T.B, T.A, T.D), stack.cards.map { it.section })
        assertRect(TileRect(0f, 224f, 300f, 100f), stack.cardOf(T.D))
        assertNull(stack.cardOf(T.E))
    }

    // ---- resize --------------------------------------------------------------------------------------------------

    @Test
    fun resizingBetweenAPinnedAndAWeightedChildChangesThePinnedSize() {
        val pinnedFirst = row(leaf(T.A, dp = 300f), leaf(T.B))
        assertEquals(350f, resize(pinnedFirst, 1, 50f, 1000f).child(0).sizeDp)
        assertEquals(100f, resize(pinnedFirst, 1, -5000f, 1000f).child(0).sizeDp, "clamped to the minimum")
        assertEquals(900f, resize(pinnedFirst, 1, 5000f, 1000f).child(0).sizeDp, "the weighted child keeps its minimum")
        assertEquals(1f, resize(pinnedFirst, 1, 50f, 1000f).child(1).weight)
        val pinnedLast = row(leaf(T.B), leaf(T.A, dp = 300f))
        assertEquals(250f, resize(pinnedLast, 1, 50f, 1000f).child(1).sizeDp, "moving the gap towards the end shrinks it")
    }

    @Test
    fun resizingTwoPinnedChildrenMovesDpBetweenThem() {
        val root = row(leaf(T.A, dp = 300f), leaf(T.B, dp = 300f), leaf(T.C))
        val moved = resize(root, 1, 100f, 1000f)
        assertEquals(400f, moved.child(0).sizeDp)
        assertEquals(200f, moved.child(1).sizeDp)
        val clamped = resize(root, 1, 1000f, 1000f)
        assertEquals(500f, clamped.child(0).sizeDp)
        assertEquals(100f, clamped.child(1).sizeDp)
    }

    @Test
    fun resizingTwoWeightedChildrenNextToAPinnedOneUsesOnlyTheWeightedExtent() {
        val root = row(leaf(T.A, dp = 200f), leaf(T.B), leaf(T.C, 3f))
        // The weighted children share 1000 - 200 = 800 dp for a weight of 4: 100 dp = 0.5 weight.
        val moved = resize(root, 2, 100f, 1000f)
        assertEquals(1.5f, moved.child(1).weight, EPS)
        assertEquals(2.5f, moved.child(2).weight, EPS)
        assertEquals(200f, moved.child(0).sizeDp)
        val geo = tileGeometry(moved, 1016f, 100f, SPEC)
        assertEquals(300f, geo.cardOf(T.B)!!.w, EPS)
        assertEquals(500f, geo.cardOf(T.C)!!.w, EPS)
    }

    @Test
    fun resizingNextToACollapsedTileIsRefusedAndTheRestUsesWhatIsLeft() {
        val root = col(leaf(T.A), leaf(T.B), leaf(T.C), leaf(T.D))
        val collapsed = setOf(T.B)
        assertEquals(root, resize(root, 1, 40f, 800f, collapsed = collapsed))
        assertEquals(root, resize(root, 2, 40f, 800f, collapsed = collapsed))
        // A, C and D share 800 - 32 = 768 dp for a weight of 3: 64 dp = 0.25 weight.
        val moved = resize(root, 3, 64f, 800f, collapsed = collapsed)
        assertEquals(1.25f, moved.child(2).weight, EPS)
        assertEquals(0.75f, moved.child(3).weight, EPS)
    }

    @Test
    fun weightedResizeKeepsThePairTotalAndTheMinimumShare() {
        val root = row(leaf(T.A), leaf(T.B, 3f))
        val moved = resize(root, 1, 100f, 1000f)
        assertEquals(4f, moved.child(0).weight + moved.child(1).weight, EPS)
        val clamped = resize(root, 1, 100000f, 1000f)
        assertEquals(3.6f, clamped.child(0).weight, EPS)
        assertEquals(root, resize(root, 1, Float.NaN, 1000f))
        assertEquals(root, resize(root, 5, 10f, 1000f))
    }

    @Test
    fun doubleClickResetEqualizesWeightsOrRestoresTheDefaultSize() {
        val weighted = row(leaf(T.A, 3f), leaf(T.B, 1f), leaf(T.C))
        val equal = resetTilePair(weighted, emptyList(), 1) { null }
        assertEquals(2f, equal.child(0).weight)
        assertEquals(2f, equal.child(1).weight)
        assertEquals(1f, equal.child(2).weight)
        val pinned = row(leaf(T.A, dp = 420f), leaf(T.B), leaf(T.C, dp = 90f))
        val reset = resetTilePair(pinned, emptyList(), 1) { if ((it as? TileLeaf<T>)?.section == T.A) 280f else null }
        assertEquals(280f, reset.child(0).sizeDp)
        assertNull(reset.child(1).sizeDp)
        assertEquals(90f, reset.child(2).sizeDp)
        assertEquals(weighted, resetTilePair(weighted, emptyList(), 0) { null })
    }

    // ---- normalize / drop ----------------------------------------------------------------------------------------

    @Test
    fun normalizeNeverFlattensAPinnedChildSplitAndPassesPinsToTheSingleChild() {
        val pinnedSplit = row(row(leaf(T.A), leaf(T.B), dp = 250f), leaf(T.C))
        assertEquals(pinnedSplit, normalizeTileLayout(pinnedSplit))
        val plain = row(row(leaf(T.A), leaf(T.B), weight = 2f), leaf(T.C))
        val flat = normalizeTileLayout(plain) as TileSplit<T>
        assertEquals(3, flat.children.size)
        assertEquals(1f, flat.children[0].weight, EPS)
        val single = normalizeTileLayout(row(col(leaf(T.A, 5f), weight = 2f, dp = 250f), leaf(T.B)))
        assertEquals(TileLeaf(T.A, 2f, 250f), single!!.child(0))
    }

    @Test
    fun flatteningKeepsPinnedGrandchildrenAndRescalesTheWeightedOnes() {
        val tree = row(row(leaf(T.A, dp = 200f), leaf(T.B, 1f), leaf(T.C, 3f), weight = 2f), leaf(T.D))
        val flat = normalizeTileLayout(tree) as TileSplit<T>
        assertEquals(200f, flat.children[0].sizeDp)
        assertEquals(0.5f, flat.children[1].weight, EPS)
        assertEquals(1.5f, flat.children[2].weight, EPS)
    }

    @Test
    fun droppingNextToATileLeavesHiddenSiblingsAlone() {
        val root = row(leaf(T.A), leaf(T.B, 2f), leaf(T.C), leaf(T.D))
        // B stays in the tree while it is hidden from the geometry; moves around it must not touch it.
        val beside = dropTile(root, T.D, TileDropTarget.OnSection(T.A, TileEdge.RIGHT))
        assertContentEquals(listOf(T.A, T.D, T.B, T.C), tileLeafSections(beside))
        assertEquals(2f, (beside as TileSplit<T>).children.single { (it as? TileLeaf<T>)?.section == T.B }.weight)
        val above = dropTile(root, T.D, TileDropTarget.OnSection(T.C, TileEdge.TOP))
        assertContentEquals(listOf(T.A, T.B, T.D, T.C), tileLeafSections(above))
        assertTrue(above.child(2) is TileSplit<*>)
        val page = dropTile(root, T.A, TileDropTarget.OnPage(TileEdge.BOTTOM))
        assertEquals(TileAxis.COLUMN, (page as TileSplit<T>).axis)
    }

    @Test
    fun droppingNextToAPinnedTileKeepsItsSize() {
        val root = row(leaf(T.A, dp = 260f), leaf(T.B), leaf(T.C))
        val beside = dropTile(root, T.C, TileDropTarget.OnSection(T.A, TileEdge.LEFT)) as TileSplit<T>
        assertEquals(TileLeaf(T.C), beside.children[0])
        assertEquals(TileLeaf(T.A, 1f, 260f), beside.children[1])
        val wrapped = dropTile(root, T.C, TileDropTarget.OnSection(T.A, TileEdge.TOP)) as TileSplit<T>
        val inner = wrapped.children[0] as TileSplit<T>
        assertEquals(260f, inner.sizeDp, "the new split takes the pinned slot")
        assertNull(inner.children[1].sizeDp)
    }

    // ---- projection ----------------------------------------------------------------------------------------------

    @Test
    fun projectionPrunesHiddenLeavesAndEmptySplitsButKeepsSingleChildSplits() {
        val tree = col(row(leaf(T.B)), row(leaf(T.C), leaf(T.D)), row(leaf(T.A), leaf(T.E)))
        val projection = projectTileLayout(tree) { it != T.B && it != T.E }!!
        assertEquals(col(row(leaf(T.C), leaf(T.D)), row(leaf(T.A))), projection.root)
        assertEquals(listOf(1), projection.fullPathOf(listOf(0)))
        assertEquals(listOf(2), projection.fullPathOf(listOf(1)))
        assertEquals(1, projection.fullIndexOf(listOf(0), 1))
        assertNull(projectTileLayout(tree) { false })
        val whole = projectTileLayout(tree) { true }!!
        assertEquals(tree, whole.root)
        assertEquals(listOf(2, 1), whole.fullPathOf(listOf(2, 1)))
    }

    @Test
    fun projectedResizeWritesBackToTheMatchingFullTreeSiblings() {
        val tree = col(row(leaf(T.B)), row(leaf(T.C, 2f), leaf(T.D, 6f)))
        val projection = projectTileLayout(tree) { it != T.B }!!
        val geo = tileGeometry(projection.root, 1008f, 300f, SPEC)
        val gap = geo.gaps.single { it.axis == TileAxis.ROW }
        assertEquals(listOf(0), gap.path)
        val moved = resizeProjected(tree, projection, gap, 125f, MIN_DP, MIN_SHARE)
        val full = moved.child(1) as TileSplit<T>
        // 1000 dp for a weight of 8: 125 dp = 1 weight.
        assertEquals(3f, full.children[0].weight, EPS)
        assertEquals(5f, full.children[1].weight, EPS)
        assertEquals(tree.child(0), moved.child(0), "the hidden row is untouched")
    }

    @Test
    fun projectedResizeSkipsHiddenSiblingsBetweenThePair() {
        val tree = row(leaf(T.A, 2f), leaf(T.B, 5f), leaf(T.C, 2f))
        val projection = projectTileLayout(tree) { it != T.B }!!
        val geo = tileGeometry(projection.root, 408f, 100f, SPEC)
        val moved = resizeProjected(tree, projection, geo.gaps.single(), 50f, MIN_DP, MIN_SHARE)
        assertEquals(2.5f, moved.child(0).weight, EPS)
        assertEquals(5f, moved.child(1).weight)
        assertEquals(1.5f, moved.child(2).weight, EPS)
    }

    @Test
    fun projectedResizeOfAFrozenGapChangesNothing() {
        val tree = col(leaf(T.A), leaf(T.B), leaf(T.C))
        val projection = projectTileLayout(tree) { true }!!
        val geo = tileGeometry(projection.root, 100f, 700f, SPEC, setOf(T.B))
        assertEquals(tree, resizeProjected(tree, projection, geo.gaps.first(), 30f, MIN_DP, MIN_SHARE, setOf(T.B)))
        assertFalse(geo.gaps.first().resizable)
    }
}
