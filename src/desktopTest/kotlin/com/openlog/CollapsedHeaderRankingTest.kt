package com.openlog

import com.openlog.ui.rankCollapsedHeadersByProximity
import kotlin.test.Test
import kotlin.test.assertEquals

class CollapsedHeaderRankingTest {
    @Test
    fun nearestPrecedingHeaderIsRankedFirst() {
        val headers = listOf("far-before" to 1, "near-before" to 90, "after" to 200)

        val ranked = rankCollapsedHeadersByProximity(headers, entryId = 100)

        assertEquals(listOf("near-before", "far-before", "after"), ranked)
    }

    @Test
    fun headerAtExactlyEntryIdCountsAsPreceding() {
        val headers = listOf("exact" to 100, "after" to 150)

        val ranked = rankCollapsedHeadersByProximity(headers, entryId = 100)

        assertEquals(listOf("exact", "after"), ranked)
    }

    @Test
    fun onlyFollowingHeadersFallBackToNearestFollowingFirst() {
        // Covers manual TO_START blocks, the one backward-covering collapsed-group kind.
        val headers = listOf("far-after" to 500, "near-after" to 101)

        val ranked = rankCollapsedHeadersByProximity(headers, entryId = 100)

        assertEquals(listOf("near-after", "far-after"), ranked)
    }

    @Test
    fun manyUnrelatedHeadersDoNotOutrankTheActualContainingOne() {
        // Simulates a real bug-report log with hundreds of unrelated collapsed sequences, all
        // well before the target — the one immediately preceding entryId must still come out on
        // top, not just any earlier header.
        val unrelated = (1..500).map { "unrelated-$it" to it }
        val headers = unrelated + listOf("containing" to 998, "later" to 1600)

        val ranked = rankCollapsedHeadersByProximity(headers, entryId = 1000)

        assertEquals("containing", ranked.first())
    }

    @Test
    fun emptyHeaderListProducesEmptyRanking() {
        assertEquals(emptyList(), rankCollapsedHeadersByProximity(emptyList(), entryId = 42))
    }
}
