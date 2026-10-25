package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.CompareFilterTarget
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.Highlighter
import com.indagium.model.LogLevel
import com.indagium.ui.compareDisplayedFilters
import com.indagium.ui.compareFilterEditTabId
import com.indagium.ui.compareSideIsEdited
import com.indagium.ui.highlightOnly
import com.indagium.ui.nearestEntryIndexByTime
import com.indagium.utils.TS_UNKNOWN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The pure rules behind the compare view's "Filters apply to A | B | Both" switch and its timestamp sync scroll. */
class CompareFiltersTest {
    private val hlA = Highlighter("hl-a", "alpha", regex = false, color = Color.Red, on = true)
    private val hlB = Highlighter("hl-b", "beta", regex = false, color = Color.Blue, on = true)

    private val left = Filter(
        levels = setOf(LogLevel.E),
        activeTags = setOf("TagA"),
        kwText = "kw-a",
        mode = FilterMode.KEYWORD,
        highlighters = listOf(hlA),
    )
    private val right = Filter(levels = setOf(LogLevel.W), activeTags = setOf("TagB"), highlighters = listOf(hlB))

    @Test
    fun highlightOnlyKeepsTheHighlightersAndFiltersNothing() {
        val shown = highlightOnly(left)
        assertEquals(listOf(hlA), shown.highlighters)
        assertEquals(Filter(highlighters = listOf(hlA)), shown)
        assertEquals(LogLevel.entries.toSet(), shown.levels)
        assertEquals(emptySet(), shown.activeTags)
        assertEquals("", shown.kwText)
    }

    @Test
    fun targetAShowsAsFilterOnAAndOnlyAsHighlightersOnB() {
        val (shownA, shownB) = compareDisplayedFilters(CompareFilterTarget.A, left, right)
        assertSame(left, shownA)
        assertEquals(Filter(highlighters = listOf(hlA)), shownB)
        // B's own stored filter plays no part: A's highlighters paint B (the approved behaviour change).
        assertFalse(shownB.activeTags.contains("TagB"))
    }

    @Test
    fun targetBShowsAsFilterOnBAndOnlyAsHighlightersOnA() {
        val (shownA, shownB) = compareDisplayedFilters(CompareFilterTarget.B, left, right)
        assertSame(right, shownB)
        assertEquals(Filter(highlighters = listOf(hlB)), shownA)
    }

    @Test
    fun targetBothMirrorsALeftFilterOnBothPanes() {
        val (shownA, shownB) = compareDisplayedFilters(CompareFilterTarget.BOTH, left, right)
        assertSame(left, shownA)
        assertSame(left, shownB)
    }

    @Test
    fun theFilterPanelEditsBOnlyForTargetB() {
        assertEquals("a", compareFilterEditTabId(CompareFilterTarget.A, "a", "b"))
        assertEquals("b", compareFilterEditTabId(CompareFilterTarget.B, "a", "b"))
        assertEquals("a", compareFilterEditTabId(CompareFilterTarget.BOTH, "a", "b"))
    }

    @Test
    fun theBadgesMarkTheSideWhoseFilterIsEdited() {
        assertEquals(true, compareSideIsEdited(CompareFilterTarget.A, sideIsRight = false))
        assertEquals(false, compareSideIsEdited(CompareFilterTarget.A, sideIsRight = true))
        assertEquals(false, compareSideIsEdited(CompareFilterTarget.B, sideIsRight = false))
        assertEquals(true, compareSideIsEdited(CompareFilterTarget.B, sideIsRight = true))
        assertEquals(true, compareSideIsEdited(CompareFilterTarget.BOTH, sideIsRight = false))
        assertEquals(true, compareSideIsEdited(CompareFilterTarget.BOTH, sideIsRight = true))
    }

    // ── nearestEntryIndexByTime ──────────────────────────────────────────

    private fun nearest(times: LongArray, target: Long): Int? = nearestEntryIndexByTime(times.size, { times[it] }, target)

    @Test
    fun nearestFindsAnExactTime() {
        assertEquals(2, nearest(longArrayOf(100, 200, 300, 400), 300))
    }

    @Test
    fun nearestPicksTheCloserNeighbourAndTiesGoToTheEarlierRow() {
        val times = longArrayOf(100, 200, 400)
        assertEquals(1, nearest(times, 240))
        assertEquals(2, nearest(times, 360))
        assertEquals(1, nearest(times, 300))
    }

    @Test
    fun nearestClampsBeforeTheFirstAndAfterTheLastRow() {
        val times = longArrayOf(100, 200, 300)
        assertEquals(0, nearest(times, 5))
        assertEquals(2, nearest(times, 9_999))
    }

    @Test
    fun nearestSkipsRowsWithoutATime() {
        val times = longArrayOf(100, TS_UNKNOWN, TS_UNKNOWN, 400, TS_UNKNOWN, 700)
        assertEquals(0, nearest(times, 150))
        assertEquals(3, nearest(times, 380))
        assertEquals(5, nearest(times, 650))
        assertEquals(5, nearest(times, 5_000))
    }

    @Test
    fun nearestHasNoAnswerForAnEmptyListAnUnknownTargetOrOnlyUnknownRows() {
        assertNull(nearest(longArrayOf(), 100))
        assertNull(nearest(longArrayOf(100, 200), TS_UNKNOWN))
        assertNull(nearest(longArrayOf(TS_UNKNOWN, TS_UNKNOWN), 100))
    }

    @Test
    fun nearestIsExactOnALargeSortedListWithGaps() {
        val times = LongArray(10_000) { i -> if (i % 7 == 3) TS_UNKNOWN else i * 10L }
        for (probe in listOf(0L, 5L, 12_340L, 49_995L, 99_990L, 150_000L)) {
            val expected = times.indices.filter { times[it] != TS_UNKNOWN }
                .minWith(compareBy({ kotlin.math.abs(times[it] - probe) }, { it }))
            assertEquals(expected, nearest(times, probe), "target $probe")
        }
    }
}
