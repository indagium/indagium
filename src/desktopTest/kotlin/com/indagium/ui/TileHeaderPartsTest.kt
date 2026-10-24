package com.indagium.ui

import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private enum class HeaderTile { A, B, C }

// The pure helpers of the tiling host (TilingHost.kt): header parts, splitter adjacency, and the projected double-click reset.
class TileHeaderPartsTest {
    @Test
    fun aWideHeaderKeepsEverything() {
        val parts = tileHeaderParts(TILE_HEADER_SUMMARY_MIN_DP + 1f)
        assertTrue(parts.showIcon)
        assertTrue(parts.showSummary)
    }

    @Test
    fun aNarrowerHeaderDropsTheSummaryFirst() {
        val parts = tileHeaderParts((TILE_HEADER_ICON_MIN_DP + TILE_HEADER_SUMMARY_MIN_DP) / 2f)
        assertTrue(parts.showIcon, "the icon outlives the summary")
        assertFalse(parts.showSummary)
    }

    @Test
    fun theNarrowestHeaderDropsTheIconToo() {
        val parts = tileHeaderParts(TILE_HEADER_ICON_MIN_DP - 1f)
        assertFalse(parts.showIcon)
        assertFalse(parts.showSummary)
    }

    @Test
    fun theBoundariesAreInclusive() {
        assertTrue(tileHeaderParts(TILE_HEADER_ICON_MIN_DP).showIcon)
        assertTrue(tileHeaderParts(TILE_HEADER_SUMMARY_MIN_DP).showSummary)
    }

    private val spec = TileGeometrySpec(gapRowDp = 8f, gapColumnDp = 8f, minTileDp = 50f, minPairShare = 0.1f)

    private fun row(vararg children: TileNode<HeaderTile>) = TileSplit(TileAxis.ROW, children.toList())

    @Test
    fun aCardTouchesOnlyTheGapsAlongItsEdges() {
        val root = TileSplit(
            TileAxis.ROW,
            listOf(TileLeaf(HeaderTile.A), TileSplit(TileAxis.COLUMN, listOf(TileLeaf(HeaderTile.B), TileLeaf(HeaderTile.C)))),
        )
        val geometry = tileGeometry(root, 1000f, 600f, spec)
        val vertical = geometry.gaps.single { it.axis == TileAxis.ROW }
        val horizontal = geometry.gaps.single { it.axis == TileAxis.COLUMN }
        val a = assertNotNull(geometry.cardOf(HeaderTile.A))
        val b = assertNotNull(geometry.cardOf(HeaderTile.B))
        val c = assertNotNull(geometry.cardOf(HeaderTile.C))
        assertTrue(tileTouchesGap(a, vertical) && tileTouchesGap(b, vertical) && tileTouchesGap(c, vertical))
        assertFalse(tileTouchesGap(a, horizontal), "A is not beside the B/C gap")
        assertTrue(tileTouchesGap(b, horizontal) && tileTouchesGap(c, horizontal))
    }

    @Test
    fun resettingAProjectedGapEqualizesOnlyTheVisiblePair() {
        // B is hidden between A (1) and C (3): the gap the user sees is A|C, and B must stay as it is.
        val full = row(TileLeaf(HeaderTile.A, 1f), TileLeaf(HeaderTile.B, 2f), TileLeaf(HeaderTile.C, 3f))
        val projection = assertNotNull(projectTileLayout(full) { it != HeaderTile.B })
        val gap = tileGeometry(projection.root, 1000f, 600f, spec).gaps.single()
        val reset = resetProjected(full, projection, gap) { null } as TileSplit<HeaderTile>
        assertEquals(listOf(2f, 2f, 2f), reset.children.map { it.weight })
    }

    @Test
    fun resettingToADefaultSizePinsThatNode() {
        val full = row(TileLeaf(HeaderTile.A, 1f), TileLeaf(HeaderTile.B, 1f))
        val projection = assertNotNull(projectTileLayout(full) { true })
        val gap = tileGeometry(projection.root, 1000f, 600f, spec).gaps.single()
        val reset = resetProjected(full, projection, gap) { if ((it as? TileLeaf)?.section == HeaderTile.A) 300f else null } as TileSplit<HeaderTile>
        assertEquals(300f, reset.children[0].sizeDp)
        assertEquals(null, reset.children[1].sizeDp)
    }
}
