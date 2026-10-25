package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.DEFAULT_HOME_LAYOUT
import com.indagium.model.HomeLayoutLeaf
import com.indagium.model.HomeLayoutNode
import com.indagium.model.HomeLayoutSplit
import com.indagium.model.HomeSectionId
import com.indagium.model.HomeSectionId.CAPTURE
import com.indagium.model.HomeSectionId.OPEN
import com.indagium.model.HomeSectionId.TESTS
import com.indagium.model.HomeSplitAxis
import com.indagium.ui.HOME_MIN_PAIR_SHARE
import com.indagium.ui.HOME_PAGE_BAND_DP
import com.indagium.ui.HomeDropTarget
import com.indagium.ui.HomeEdge
import com.indagium.ui.HomeLayoutPreset
import com.indagium.ui.HomeRect
import com.indagium.ui.dropHomeSection
import com.indagium.ui.homeDropLabel
import com.indagium.ui.homeDropPreview
import com.indagium.ui.homeDropTargetAt
import com.indagium.ui.homeGeometry
import com.indagium.ui.homeLayoutShapeEquals
import com.indagium.ui.homeLeafSections
import com.indagium.ui.normalizeHomeLayout
import com.indagium.ui.removeHomeSection
import com.indagium.ui.resizeHomeSplit
import com.indagium.ui.sanitizeHomeLayoutState
import com.indagium.ui.withHomeLayout
import com.indagium.ui.withHomeLayoutPreset
import com.indagium.ui.withHomeSectionHidden
import com.indagium.ui.withHomeSectionShown
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val EPS = 0.001f
private const val HOST_W = 1000f
private const val HOST_H = 600f

private fun leaf(section: HomeSectionId, weight: Float = 1f) = HomeLayoutLeaf(section, weight)

private fun row(vararg children: HomeLayoutNode, weight: Float = 1f) = HomeLayoutSplit(HomeSplitAxis.ROW, children.toList(), weight)

private fun col(vararg children: HomeLayoutNode, weight: Float = 1f) = HomeLayoutSplit(HomeSplitAxis.COLUMN, children.toList(), weight)

private fun assertShape(expected: HomeLayoutNode, actual: HomeLayoutNode?) {
    assertNotNull(actual)
    assertTrue(homeLayoutShapeEquals(expected, actual), "expected shape $expected but was $actual")
}

private fun weightsOf(node: HomeLayoutNode?): List<Float> = (node as HomeLayoutSplit).children.map { it.weight }

private fun assertWeights(expected: List<Float>, node: HomeLayoutNode?) {
    val actual = weightsOf(node)
    assertEquals(expected.size, actual.size, "weights $actual")
    expected.indices.forEach { assertEquals(expected[it], actual[it], EPS, "weight $it of $actual") }
}

private val threeColumns = HomeLayoutPreset.THREE_COLUMNS.layout

class HomeLayoutTest {
    // ---- normalize / remove -------------------------------------------------------------------------------------

    @Test
    fun normalizeCollapsesASingleChildSplitKeepingTheSplitsWeight() {
        val result = normalizeHomeLayout(row(col(leaf(OPEN, 3f), weight = 2f), leaf(CAPTURE)))
        assertShape(row(leaf(OPEN), leaf(CAPTURE)), result)
        assertWeights(listOf(2f, 1f), result)
    }

    @Test
    fun normalizeFlattensASameAxisChildAndKeepsItsShare() {
        // The inner row (weight 2) holds 1:3, so it flattens into 0.5 + 1.5 and the outer shares stay 2 : 1.
        val result = normalizeHomeLayout(row(row(leaf(OPEN, 1f), leaf(CAPTURE, 3f), weight = 2f), leaf(TESTS, 1f)))
        assertShape(row(leaf(OPEN), leaf(CAPTURE), leaf(TESTS)), result)
        assertWeights(listOf(0.5f, 1.5f, 1f), result)
    }

    @Test
    fun normalizeFlattensAfterACollapseExposedASameAxisSplit() {
        val result = normalizeHomeLayout(row(col(row(leaf(OPEN), leaf(CAPTURE)), weight = 1f), leaf(TESTS)))
        assertShape(row(leaf(OPEN), leaf(CAPTURE), leaf(TESTS)), result)
    }

    @Test
    fun normalizeDropsEmptySplitsAndReturnsNullForAnEmptyTree() {
        assertShape(row(leaf(OPEN), leaf(TESTS)), normalizeHomeLayout(row(leaf(OPEN), col(), leaf(TESTS))))
        assertNull(normalizeHomeLayout(row()))
        assertNull(normalizeHomeLayout(row(col(), row())))
    }

    @Test
    fun removeHomeSectionNormalizesAndReturnsNullForTheLastSection() {
        assertShape(col(leaf(OPEN), leaf(TESTS)), removeHomeSection(DEFAULT_HOME_LAYOUT, CAPTURE))
        assertWeights(listOf(1.6f, 1f), removeHomeSection(DEFAULT_HOME_LAYOUT, CAPTURE))
        assertNull(removeHomeSection(leaf(OPEN), OPEN))
        assertShape(leaf(OPEN), removeHomeSection(row(leaf(OPEN), leaf(CAPTURE)), CAPTURE))
    }

    // ---- drop ---------------------------------------------------------------------------------------------------

    @Test
    fun dropBesideATargetOfTheParentsAxisInsertsAndHalvesTheTargetsWeight() {
        val start = row(leaf(OPEN, 1f), leaf(CAPTURE, 2f), leaf(TESTS, 1f))
        val onLeft = dropHomeSection(start, TESTS, HomeDropTarget.OnSection(CAPTURE, HomeEdge.LEFT))
        assertShape(row(leaf(OPEN), leaf(TESTS), leaf(CAPTURE)), onLeft)
        assertWeights(listOf(1f, 1f, 1f), onLeft)

        val onRight = dropHomeSection(start, OPEN, HomeDropTarget.OnSection(CAPTURE, HomeEdge.RIGHT))
        assertShape(row(leaf(CAPTURE), leaf(OPEN), leaf(TESTS)), onRight)
        assertWeights(listOf(1f, 1f, 1f), onRight)
    }

    @Test
    fun dropOnTheOtherAxisWrapsTheTargetKeepingItsWeight() {
        val start = row(leaf(OPEN, 1f), leaf(CAPTURE, 2f), leaf(TESTS, 1f))
        val result = dropHomeSection(start, TESTS, HomeDropTarget.OnSection(CAPTURE, HomeEdge.TOP))
        assertShape(row(leaf(OPEN), col(leaf(TESTS), leaf(CAPTURE))), result)
        assertWeights(listOf(1f, 2f), result)
        assertWeights(listOf(1f, 1f), (result as HomeLayoutSplit).children[1])

        val below = dropHomeSection(leaf(OPEN), CAPTURE, HomeDropTarget.OnSection(OPEN, HomeEdge.BOTTOM))
        assertShape(col(leaf(OPEN), leaf(CAPTURE)), below)
    }

    @Test
    fun dropOnASingleLeafRootWrapsItToo() {
        val result = dropHomeSection(row(leaf(OPEN), leaf(CAPTURE)), CAPTURE, HomeDropTarget.OnSection(OPEN, HomeEdge.LEFT))
        assertShape(row(leaf(CAPTURE), leaf(OPEN)), result)
    }

    @Test
    fun dropOnAPageEdgeOfTheRootsAxisJoinsTheRootWithTheAverageSiblingWeight() {
        val start = row(leaf(OPEN, 1f), leaf(CAPTURE, 3f), leaf(TESTS, 2f))
        val leftmost = dropHomeSection(start, TESTS, HomeDropTarget.OnPage(HomeEdge.LEFT))
        assertShape(row(leaf(TESTS), leaf(OPEN), leaf(CAPTURE)), leftmost)
        assertWeights(listOf(2f, 1f, 3f), leftmost)

        val rightmost = dropHomeSection(start, OPEN, HomeDropTarget.OnPage(HomeEdge.RIGHT))
        assertShape(row(leaf(CAPTURE), leaf(TESTS), leaf(OPEN)), rightmost)
        assertWeights(listOf(3f, 2f, 2.5f), rightmost)
    }

    @Test
    fun dropOnAPageEdgeOfASingleLeafRootSplitsIt() {
        // Dragging the only other section out leaves a single leaf; the drop re-adds it on the requested edge.
        val result = dropHomeSection(row(leaf(OPEN, 2f), leaf(CAPTURE)), CAPTURE, HomeDropTarget.OnPage(HomeEdge.TOP))
        assertShape(col(leaf(CAPTURE), leaf(OPEN)), result)
        assertWeights(listOf(1f, 1f), result)
    }

    @Test
    fun dropOnAPageEdgeOfTheOtherAxisBuildsANewRootInEdgeOrder() {
        val start = row(leaf(OPEN), leaf(CAPTURE), leaf(TESTS))
        val bottom = dropHomeSection(start, TESTS, HomeDropTarget.OnPage(HomeEdge.BOTTOM))
        assertShape(col(row(leaf(OPEN), leaf(CAPTURE)), leaf(TESTS)), bottom)
        assertWeights(listOf(1.6f, 1f), bottom)

        val top = dropHomeSection(start, TESTS, HomeDropTarget.OnPage(HomeEdge.TOP))
        assertShape(col(leaf(TESTS), row(leaf(OPEN), leaf(CAPTURE))), top)
        assertWeights(listOf(1f, 1.6f), top)
    }

    @Test
    fun droppingOnItselfOrOnAnUnknownTargetLeavesTheLayoutUnchanged() {
        assertSame(DEFAULT_HOME_LAYOUT, dropHomeSection(DEFAULT_HOME_LAYOUT, OPEN, HomeDropTarget.OnSection(OPEN, HomeEdge.LEFT)))
        val twoOnly = row(leaf(OPEN), leaf(CAPTURE))
        assertSame(twoOnly, dropHomeSection(twoOnly, OPEN, HomeDropTarget.OnSection(TESTS, HomeEdge.LEFT)))
        // The only section on the page: nothing to move it relative to.
        val only = leaf(OPEN)
        assertSame(only, dropHomeSection(only, OPEN, HomeDropTarget.OnPage(HomeEdge.LEFT)))
    }

    @Test
    fun designScenarioTestsToThePageBottomThenCaptureBesideTests() {
        val first = dropHomeSection(threeColumns, TESTS, HomeDropTarget.OnPage(HomeEdge.BOTTOM))
        assertShape(col(row(leaf(OPEN), leaf(CAPTURE)), leaf(TESTS)), first)

        val second = dropHomeSection(first, CAPTURE, HomeDropTarget.OnSection(TESTS, HomeEdge.LEFT))
        assertShape(col(leaf(OPEN), row(leaf(CAPTURE), leaf(TESTS))), second)
        assertContentEquals(listOf(OPEN, CAPTURE, TESTS), homeLeafSections(second))
    }

    @Test
    fun everyDropKeepsEachSectionExactlyOnce() {
        val layouts = HomeLayoutPreset.entries.map { it.layout }.filter { homeLeafSections(it).size == HomeSectionId.entries.size }
        for (layout in layouts) {
            for (dragged in HomeSectionId.entries) {
                val targets = HomeEdge.entries.map { HomeDropTarget.OnPage(it) } +
                    HomeSectionId.entries.flatMap { s -> HomeEdge.entries.map { HomeDropTarget.OnSection(s, it) } }
                for (target in targets) {
                    val result = dropHomeSection(layout, dragged, target)
                    assertEquals(HomeSectionId.entries.toSet(), homeLeafSections(result).toSet(), "$layout $dragged $target")
                    assertEquals(HomeSectionId.entries.size, homeLeafSections(result).size, "$layout $dragged $target")
                }
            }
        }
    }

    // ---- resize -------------------------------------------------------------------------------------------------

    @Test
    fun resizeMovesWeightBetweenTheNeighboursAndKeepsTheirTotal() {
        // 900 dp shared by total weight 3: 90 dp is 0.3 weight.
        val result = resizeHomeSplit(threeColumns, emptyList(), 1, 90f, 900f)
        assertWeights(listOf(1.3f, 0.7f, 1f), result)
        val back = resizeHomeSplit(result, emptyList(), 1, -90f, 900f)
        assertWeights(listOf(1f, 1f, 1f), back)
    }

    @Test
    fun resizeClampsToTheMinimumPairShareAndTheMinimumDp() {
        // Large extent: the 0.18 share binds (pair weight 2 -> 0.36).
        val shareBound = resizeHomeSplit(threeColumns, emptyList(), 1, 100_000f, 3000f)
        assertEquals(2f * HOME_MIN_PAIR_SHARE, weightsOf(shareBound)[1], EPS)
        assertEquals(2f - 2f * HOME_MIN_PAIR_SHARE, weightsOf(shareBound)[0], EPS)

        // 900 dp for weight 3 puts the 200 dp minimum at 0.667 weight.
        val dpBound = resizeHomeSplit(threeColumns, emptyList(), 1, 100_000f, 900f)
        assertEquals(200f * 3f / 900f, weightsOf(dpBound)[1], EPS)
        val dpBoundOtherWay = resizeHomeSplit(threeColumns, emptyList(), 1, -100_000f, 900f)
        assertEquals(200f * 3f / 900f, weightsOf(dpBoundOtherWay)[0], EPS)
        assertEquals(3f, weightsOf(dpBound).sum(), EPS)
    }

    @Test
    fun resizeIgnoresInvalidRequests() {
        assertSame(threeColumns, resizeHomeSplit(threeColumns, emptyList(), 0, 10f, 900f))
        assertSame(threeColumns, resizeHomeSplit(threeColumns, emptyList(), 3, 10f, 900f))
        assertEquals(threeColumns, resizeHomeSplit(threeColumns, emptyList(), 1, 10f, 0f))
        assertEquals(threeColumns, resizeHomeSplit(threeColumns, emptyList(), 1, Float.NaN, 900f))
        assertEquals(threeColumns, resizeHomeSplit(threeColumns, listOf(0), 1, 10f, 900f))
        // A pair that cannot hold two minimum-size sections is left alone.
        assertEquals(threeColumns, resizeHomeSplit(threeColumns, emptyList(), 1, 10f, 300f))
    }

    @Test
    fun resizeReachesANestedSplitByPath() {
        val result = resizeHomeSplit(DEFAULT_HOME_LAYOUT, listOf(0), 1, 99.2f, 992f)
        assertWeights(listOf(1.6f, 1f), result)
        val top = (result as HomeLayoutSplit).children[0]
        assertWeights(listOf(1.2f, 0.8f), top)
    }

    // ---- geometry -----------------------------------------------------------------------------------------------

    @Test
    fun defaultGeometryAt1000By600() {
        val geo = homeGeometry(DEFAULT_HOME_LAYOUT, 1000f, 600f, 8f)
        val topH = 592f * 1.6f / 2.6f
        val open = geo.cardOf(OPEN)!!
        val capture = geo.cardOf(CAPTURE)!!
        val tests = geo.cardOf(TESTS)!!
        assertRect(HomeRect(0f, 0f, 496f, topH), open)
        assertRect(HomeRect(504f, 0f, 496f, topH), capture)
        assertRect(HomeRect(0f, topH + 8f, 1000f, 592f - topH), tests)
        assertEquals(600f, tests.bottom, EPS)

        assertEquals(2, geo.gaps.size)
        val horizontal = geo.gaps.single { it.axis == HomeSplitAxis.COLUMN }
        assertEquals(emptyList(), horizontal.path)
        assertEquals(1, horizontal.index)
        assertEquals(592f, horizontal.splitExtent, EPS)
        assertRect(HomeRect(0f, topH, 1000f, 8f), horizontal.rect)
        val vertical = geo.gaps.single { it.axis == HomeSplitAxis.ROW }
        assertEquals(listOf(0), vertical.path)
        assertEquals(1, vertical.index)
        assertEquals(992f, vertical.splitExtent, EPS)
        assertRect(HomeRect(496f, 0f, 8f, topH), vertical.rect)
    }

    @Test
    fun geometryCoversEveryLeafAndSurvivesADegenerateHost() {
        val geo = homeGeometry(threeColumns, 600f, 400f, 0f)
        assertEquals(3, geo.cards.size)
        assertEquals(200f, geo.cardOf(CAPTURE)!!.x, EPS)
        assertTrue(geo.gaps.all { it.rect.w == 0f })
        val tiny = homeGeometry(threeColumns, 4f, 4f, 8f)
        assertTrue(tiny.cards.all { it.rect.w >= 0f && it.rect.h >= 0f })
    }

    // ---- hit testing --------------------------------------------------------------------------------------------

    private val geometry = homeGeometry(DEFAULT_HOME_LAYOUT, 1000f, 600f, 8f)

    private fun hit(x: Float, y: Float, dragged: HomeSectionId = TESTS) = homeDropTargetAt(geometry, HOST_W, HOST_H, x, y, dragged)

    @Test
    fun pageBandsWinOverTheCardUnderThePointer() {
        assertEquals(HomeDropTarget.OnPage(HomeEdge.LEFT), hit(HOME_PAGE_BAND_DP - 1f, 200f))
        assertEquals(HomeDropTarget.OnPage(HomeEdge.RIGHT), hit(1000f - HOME_PAGE_BAND_DP + 1f, 200f))
        assertEquals(HomeDropTarget.OnPage(HomeEdge.TOP), hit(500f, 5f))
        assertEquals(HomeDropTarget.OnPage(HomeEdge.BOTTOM), hit(500f, 595f))
        // A corner picks the nearer edge, ties going to the horizontal axis first.
        assertEquals(HomeDropTarget.OnPage(HomeEdge.LEFT), hit(5f, 5f))
        assertEquals(HomeDropTarget.OnPage(HomeEdge.TOP), hit(8f, 3f))
        // The band also wins over the dragged card itself.
        assertEquals(HomeDropTarget.OnPage(HomeEdge.LEFT), hit(5f, 200f, dragged = OPEN))
        // Just inside the band is a card again.
        assertEquals(HomeDropTarget.OnSection(OPEN, HomeEdge.LEFT), hit(HOME_PAGE_BAND_DP + 1f, 200f))
    }

    @Test
    fun cardEdgeIsTheNearestOneByNormalizedDistance() {
        // The open card is (0,0)-(496,364.3).
        assertEquals(HomeDropTarget.OnSection(OPEN, HomeEdge.LEFT), hit(60f, 180f))
        assertEquals(HomeDropTarget.OnSection(OPEN, HomeEdge.RIGHT), hit(440f, 180f))
        assertEquals(HomeDropTarget.OnSection(OPEN, HomeEdge.TOP), hit(250f, 60f))
        assertEquals(HomeDropTarget.OnSection(OPEN, HomeEdge.BOTTOM), hit(250f, 330f))
        assertEquals(HomeDropTarget.OnSection(CAPTURE, HomeEdge.TOP), hit(750f, 60f))
        assertEquals(HomeDropTarget.OnSection(TESTS, HomeEdge.TOP), hit(500f, 395f, dragged = OPEN))
    }

    @Test
    fun noTargetOverTheDraggedCardAGapOrOutsideTheHost() {
        assertNull(hit(250f, 180f, dragged = OPEN))
        assertNull(hit(500f, 180f)) // in the vertical gap
        assertNull(hit(500f, 368f)) // in the horizontal gap
        assertNull(hit(-1f, 100f))
        assertNull(hit(100f, 601f))
    }

    @Test
    fun previewAndLabelDescribeTheTarget() {
        val onCard = homeDropPreview(geometry, 1000f, 600f, HomeDropTarget.OnSection(OPEN, HomeEdge.LEFT))!!
        assertRect(HomeRect(0f, 0f, 248f, geometry.cardOf(OPEN)!!.h), onCard)
        val bottomHalf = homeDropPreview(geometry, 1000f, 600f, HomeDropTarget.OnSection(TESTS, HomeEdge.BOTTOM))!!
        assertEquals(600f, bottomHalf.bottom, EPS)
        assertEquals(geometry.cardOf(TESTS)!!.h / 2, bottomHalf.h, EPS)
        val page = homeDropPreview(geometry, 1000f, 600f, HomeDropTarget.OnPage(HomeEdge.RIGHT))!!
        assertEquals(1000f, page.right, EPS)
        assertEquals(600f, page.h, EPS)
        assertNull(homeDropPreview(homeGeometry(leaf(OPEN), 100f, 100f, 8f), 100f, 100f, HomeDropTarget.OnSection(TESTS, HomeEdge.LEFT)))

        assertEquals("Full height", homeDropLabel(HomeDropTarget.OnPage(HomeEdge.LEFT)))
        assertEquals("Full width", homeDropLabel(HomeDropTarget.OnPage(HomeEdge.BOTTOM)))
        assertEquals("Left of Capture", homeDropLabel(HomeDropTarget.OnSection(CAPTURE, HomeEdge.LEFT)))
        assertEquals("Below Run tests", homeDropLabel(HomeDropTarget.OnSection(TESTS, HomeEdge.BOTTOM)))
    }

    // ---- presets ------------------------------------------------------------------------------------------------

    @Test
    fun everyPresetHoldsEachSectionExactlyOnceAndSurvivesSanitizing() {
        for (preset in HomeLayoutPreset.entries) {
            val all = homeLeafSections(preset.layout) + preset.hidden
            assertEquals(HomeSectionId.entries.size, all.size, preset.name)
            assertEquals(HomeSectionId.entries.toSet(), all.toSet(), preset.name)
            val state = sanitizeHomeLayoutState(preset.layout, preset.hidden)
            assertEquals(preset.layout, state.layout, preset.name)
            assertEquals(preset.hidden, state.hidden, preset.name)
            assertTrue(preset.isActiveIn(AppSettings().withHomeLayoutPreset(preset)), preset.name)
        }
        assertEquals(HomeLayoutPreset.entries.size, HomeLayoutPreset.entries.map { it.layout to it.hidden }.toSet().size)
    }

    @Test
    fun shapeEqualityIgnoresWeightsButNotStructure() {
        assertTrue(homeLayoutShapeEquals(DEFAULT_HOME_LAYOUT, col(row(leaf(OPEN, 3f), leaf(CAPTURE, 7f), weight = 9f), leaf(TESTS, 4f))))
        assertFalse(homeLayoutShapeEquals(DEFAULT_HOME_LAYOUT, col(row(leaf(CAPTURE), leaf(OPEN)), leaf(TESTS))))
        assertFalse(homeLayoutShapeEquals(DEFAULT_HOME_LAYOUT, threeColumns))
        assertFalse(homeLayoutShapeEquals(leaf(OPEN), row(leaf(OPEN))))
    }

    @Test
    fun activePresetIsHighlightedByShapeAndHiddenSections() {
        val settings = AppSettings().withHomeLayoutPreset(HomeLayoutPreset.LOGS_ON_TOP)
        assertTrue(HomeLayoutPreset.LOGS_ON_TOP.isActiveIn(settings))
        assertFalse(HomeLayoutPreset.DEFAULT.isActiveIn(settings))
        val resized = settings.copy(homeLayout = resizeHomeSplit(settings.homeLayout, emptyList(), 1, 50f, 800f))
        assertTrue(HomeLayoutPreset.LOGS_ON_TOP.isActiveIn(resized))
        // Same single-leaf arrangement but different hidden sets is not the same preset.
        assertFalse(HomeLayoutPreset.LOGS_ONLY.isActiveIn(AppSettings(homeLayout = leaf(OPEN))))
    }

    // ---- hide / show --------------------------------------------------------------------------------------------

    @Test
    fun hidingRemovesTheSectionFromTheTreeAndRemembersIt() {
        val hidden = AppSettings().withHomeSectionHidden(CAPTURE)
        assertShape(col(leaf(OPEN), leaf(TESTS)), hidden.homeLayout)
        assertEquals(listOf(CAPTURE), hidden.homeHiddenSections)
        val twice = hidden.withHomeSectionHidden(CAPTURE)
        assertSame(hidden, twice)
    }

    @Test
    fun hidingTheLastVisibleSectionIsRefused() {
        val settings = AppSettings().withHomeSectionHidden(CAPTURE).withHomeSectionHidden(TESTS)
        assertShape(leaf(OPEN), settings.homeLayout)
        assertEquals(listOf(CAPTURE, TESTS), settings.homeHiddenSections)
        assertSame(settings, settings.withHomeSectionHidden(OPEN))
        assertEquals(listOf(OPEN), homeLeafSections(settings.homeLayout))
    }

    @Test
    fun showingAddsTheSectionAtTheTrailingPageEdge() {
        // ROW root -> right.
        val rowRoot = AppSettings(homeLayout = row(leaf(OPEN), leaf(TESTS)), homeHiddenSections = listOf(CAPTURE)).withHomeSectionShown(CAPTURE)
        assertShape(row(leaf(OPEN), leaf(TESTS), leaf(CAPTURE)), rowRoot.homeLayout)
        assertTrue(rowRoot.homeHiddenSections.isEmpty())
        // COLUMN root -> bottom.
        val colRoot = AppSettings().withHomeSectionHidden(CAPTURE).withHomeSectionShown(CAPTURE)
        assertShape(col(leaf(OPEN), leaf(TESTS), leaf(CAPTURE)), colRoot.homeLayout)
        // Single leaf root -> right.
        val leafRoot = AppSettings(homeLayout = leaf(OPEN), homeHiddenSections = listOf(CAPTURE, TESTS)).withHomeSectionShown(TESTS)
        assertShape(row(leaf(OPEN), leaf(TESTS)), leafRoot.homeLayout)
        assertEquals(listOf(CAPTURE), leafRoot.homeHiddenSections)
        // Not hidden: unchanged.
        val settings = AppSettings()
        assertSame(settings, settings.withHomeSectionShown(TESTS))
    }

    @Test
    fun withHomeLayoutSanitizesAgainstTheHiddenList() {
        val settings = AppSettings(homeLayout = row(leaf(OPEN), leaf(TESTS)), homeHiddenSections = listOf(CAPTURE))
        val updated = settings.withHomeLayout(row(leaf(TESTS, Float.NaN), leaf(OPEN, -3f), leaf(TESTS)))
        assertShape(row(leaf(TESTS), leaf(OPEN)), updated.homeLayout)
        assertEquals(listOf(CAPTURE), updated.homeHiddenSections)
        assertWeights(listOf(1f, 1f), updated.homeLayout)
    }
}

private fun assertRect(expected: HomeRect, actual: HomeRect) {
    assertEquals(expected.x, actual.x, EPS, "x of $actual")
    assertEquals(expected.y, actual.y, EPS, "y of $actual")
    assertEquals(expected.w, actual.w, EPS, "w of $actual")
    assertEquals(expected.h, actual.h, EPS, "h of $actual")
}
