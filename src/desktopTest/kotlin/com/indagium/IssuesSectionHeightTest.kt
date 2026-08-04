package com.indagium

import com.indagium.model.CrashKind
import com.indagium.model.CrashSite
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.issuesBoxHeightDp
import com.indagium.utils.IssueSiteGroup
import kotlin.test.Test
import kotlin.test.assertEquals

// Mirrors FilterPanel.kt's private CRASH_ROW_DP/CRASH_OCCURRENCE_ROW_DP constants — kept in sync by
// their own doc comments (border + padding + line-height derivations) rather than by import, since
// both are file-private consts and issuesBoxHeightDp (the function under test) is the seam that's
// actually exposed (internal) for this pure-arithmetic test to reach.
private const val COLLAPSED_ROW_DP = 64
private const val OCCURRENCE_ROW_DP = 26

/**
 * Pins the arithmetic behind the Issues section's bounding box (see FilterPanel.kt's
 * issuesBoxHeightDp). There's no Compose layout harness here, so this is what actually catches a
 * regression back to "size from crashGroups.size at collapsed height" — the bug where expanding a
 * group didn't grow the box that scrolls it.
 */
class IssuesSectionHeightTest {
    private fun group(id: String, occurrenceCount: Int): IssueSiteGroup {
        val sites = (1..occurrenceCount).map { n ->
            val entry = LogEntry(n, "10:00:00.000", LogLevel.E, "AndroidRuntime", "boom $id")
            CrashSite("crash_${id}_$n", entry, CrashKind.EXCEPTION, groupGid = null, signature = id)
        }
        return IssueSiteGroup(representative = sites.first(), occurrences = sites)
    }

    @Test
    fun allCollapsedGroupsSizeToOneRowEach() {
        val groups = listOf(group("a", 3), group("b", 1), group("c", 5))

        val height = issuesBoxHeightDp(groups, expandedIds = emptySet(), rowCap = 100)

        assertEquals(3 * COLLAPSED_ROW_DP, height)
    }

    @Test
    fun oneExpandedGroupAddsAnOccurrenceRowPerExtraOccurrence() {
        val expandedGroup = group("a", occurrenceCount = 4)
        val collapsedGroup = group("b", occurrenceCount = 1)

        val height = issuesBoxHeightDp(
            groups = listOf(expandedGroup, collapsedGroup),
            expandedIds = setOf(expandedGroup.representative.id),
            rowCap = 100,
        )

        // group a: header row + 3 extra occurrence rows (drop(1) — the representative is the header,
        // not a sub-row); group b: a single collapsed row (count == 1, so it has no expand control
        // regardless of expandedIds).
        val expected = (COLLAPSED_ROW_DP + 3 * OCCURRENCE_ROW_DP) + COLLAPSED_ROW_DP
        assertEquals(expected, height)
    }

    @Test
    fun allGroupsExpandedStillRespectsTheRowCap() {
        val groups = (1..10).map { group("g$it", occurrenceCount = 5) }
        val allIds = groups.map { it.representative.id }.toSet()

        val height = issuesBoxHeightDp(groups, expandedIds = allIds, rowCap = 3)

        // Fully expanded, 10 groups of 5 occurrences each would be far taller than 3 collapsed
        // rows — the section must not grow unbounded and swallow the panel, so the cap (in
        // CRASH_ROW_DP-sized units of filterListRows) still wins.
        assertEquals(3 * COLLAPSED_ROW_DP, height)
    }

    @Test
    fun singleOccurrenceGroupIgnoresExpandedIdsSinceItHasNoExpandControl() {
        val solo = group("solo", occurrenceCount = 1)

        val height = issuesBoxHeightDp(listOf(solo), expandedIds = setOf(solo.representative.id), rowCap = 10)

        assertEquals(COLLAPSED_ROW_DP, height)
    }

    @Test
    fun zeroRowCapStillYieldsOneCollapsedRowOfCapNotZero() {
        val groups = listOf(group("a", 1), group("b", 1))

        val height = issuesBoxHeightDp(groups, expandedIds = emptySet(), rowCap = 0)

        // rowCap is coerced to at least 1 so a misconfigured/zero filterListRows can't collapse the
        // cap to zero and hide every row; two collapsed groups (128dp of content) still clip down
        // to one row's worth of cap rather than zero.
        assertEquals(COLLAPSED_ROW_DP, height)
    }
}
