package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.Filter
import com.indagium.model.LogAnalysis
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.ManualCollapseBlock
import com.indagium.model.ManualCollapseDirection
import com.indagium.model.SequenceDef
import com.indagium.model.StackTraceGroup
import com.indagium.ui.mkTab
import com.indagium.utils.SELECTION_EXPANSION_MAX_IDS
import com.indagium.utils.cachedVisibleEntriesFor
import com.indagium.utils.expandSelectionThroughCollapsedBlocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Pure utils/Filter.kt coverage for expandSelectionThroughCollapsedBlocks — no Compose, no
// AppState. Fixture shapes mirror SequenceGroupingTest.kt / FilterBehaviorTest.kt so a reader
// moving between the three files finds the same log/block/sequence patterns.
class FoldSelectionExpansionTest {
    private fun entries(vararg tagsAndMsgs: Pair<String, String>): List<LogEntry> =
        tagsAndMsgs.mapIndexed { index, (tag, msg) -> LogEntry(index + 1, "10:00:00.${index}00", LogLevel.I, tag, msg) }

    @Test
    fun plainRowSelectionReturnsTheIdenticalSetInstance() {
        val tab = mkTab("log", "test.log", entries("A" to "one", "A" to "two"))

        val selected = setOf(1)
        val result = expandSelectionThroughCollapsedBlocks(tab, selected).ids

        // No fold matched anything, so the fast path must hand back the exact same instance rather
        // than an equal-but-freshly-allocated copy — proving no scan ran at all.
        assertSame(selected, result)
    }

    @Test
    fun emptySelectionIsReturnedAsIs() {
        val tab = mkTab("log", "test.log", entries("A" to "one"))
        val selected = emptySet<Int>()

        assertSame(selected, expandSelectionThroughCollapsedBlocks(tab, selected).ids)
    }

    @Test
    fun manualToStartExpandsEverythingBeforeTheAnchor() {
        val tab = mkTab("log", "test.log", entries("A" to "1", "A" to "2", "A" to "3", "A" to "4", "A" to "5"))
            .copy(manualBlocks = listOf(ManualCollapseBlock("m1", 3, ManualCollapseDirection.TO_START)))

        val result = expandSelectionThroughCollapsedBlocks(tab, setOf(3)).ids

        // The whole reason this function exists: TO_START's swallowed ids are strictly LOWER than
        // the only selectable (anchor) row, so a naive min..max span could never reach them.
        assertEquals(setOf(1, 2, 3), result)
    }

    @Test
    fun manualToEndExpandsEverythingAfterTheAnchor() {
        val tab = mkTab("log", "test.log", entries("A" to "1", "A" to "2", "A" to "3", "A" to "4", "A" to "5"))
            .copy(manualBlocks = listOf(ManualCollapseBlock("m1", 3, ManualCollapseDirection.TO_END)))

        val result = expandSelectionThroughCollapsedBlocks(tab, setOf(3)).ids

        assertEquals(setOf(3, 4, 5), result)
    }

    @Test
    fun manualRangeExpandsBetweenAnchorAndEndRegardlessOfOrder() {
        val tab = mkTab("log", "test.log", entries("A" to "1", "A" to "2", "A" to "3", "A" to "4", "A" to "5"))
            .copy(manualBlocks = listOf(ManualCollapseBlock("m1", 4, ManualCollapseDirection.RANGE, endId = 2)))

        val result = expandSelectionThroughCollapsedBlocks(tab, setOf(4)).ids

        // anchorId(4) > endId(2): Filter.kt's manualRangesFor takes min/max of the two resolved
        // indices, so the range still comes out ascending.
        assertEquals(setOf(2, 3, 4), result)
    }

    @Test
    fun disabledManualBlockIsNeverExpanded() {
        val tab = mkTab("log", "test.log", entries("A" to "1", "A" to "2", "A" to "3"))
            .copy(manualBlocks = listOf(ManualCollapseBlock("m1", 2, ManualCollapseDirection.TO_START, enabled = false)))

        val selected = setOf(2)
        assertSame(selected, expandSelectionThroughCollapsedBlocks(tab, selected).ids)
    }

    @Test
    fun alreadyExpandedManualBlockIsNotExpandedAgain() {
        val tab = mkTab("log", "test.log", entries("A" to "1", "A" to "2", "A" to "3"))
            .copy(
                manualBlocks = listOf(ManualCollapseBlock("m1", 2, ManualCollapseDirection.TO_START)),
                expanded = setOf("m1"),
            )

        val selected = setOf(2)
        assertSame(selected, expandSelectionThroughCollapsedBlocks(tab, selected).ids)
    }

    @Test
    fun filterHiddenAnchorYieldsNoExpansion() {
        // Row 2 (the block's own anchor) is filtered out by an active-tags filter that only keeps
        // "Keep" — collapsedManual still finds the block (selection membership doesn't consult the
        // filter), but indexOfId over the FILTERED visible list can't locate the anchor, so
        // manualRangesFor silently drops it, exactly like computeItems' own pre-hoist behavior.
        val tab = mkTab("log", "test.log", entries("Keep" to "1", "Hide" to "2", "Keep" to "3", "Keep" to "4"))
            .copy(
                manualBlocks = listOf(ManualCollapseBlock("m1", 2, ManualCollapseDirection.TO_START)),
                filter = Filter(activeTags = setOf("Keep")),
            )

        val selected = setOf(2)
        assertEquals(selected, expandSelectionThroughCollapsedBlocks(tab, selected).ids)
    }

    @Test
    fun stackTraceHeaderExpandsToItsMembers() {
        val tab = mkTab(
            "log", "test.log",
            entries("App" to "before", "App" to "Exception", "App" to "at foo", "App" to "at bar", "App" to "after"),
            analysis = LogAnalysis(stackTraceGroups = listOf(StackTraceGroup(gid = "st_2", rid = 2, memberIds = listOf(3, 4)))),
        )

        val result = expandSelectionThroughCollapsedBlocks(tab, setOf(2)).ids

        assertEquals(setOf(2, 3, 4), result)
    }

    @Test
    fun alreadyExpandedStackTraceHeaderIsNotExpandedAgain() {
        val tab = mkTab(
            "log", "test.log",
            entries("App" to "before", "App" to "Exception", "App" to "at foo"),
            analysis = LogAnalysis(stackTraceGroups = listOf(StackTraceGroup(gid = "st_2", rid = 2, memberIds = listOf(3)))),
        ).copy(expanded = setOf("st_2"))

        val selected = setOf(2)
        assertSame(selected, expandSelectionThroughCollapsedBlocks(tab, selected).ids)
    }

    @Test
    fun sequenceHeaderExpandsToItsPlainAndNestedChildren() {
        val outer = SequenceDef("outer", "outer start", priority = 1, color = Color.Red, tag = "Outer", endMatchText = "outer end", endTag = "Outer")
        val inner = SequenceDef("inner", "inner start", priority = 2, color = Color.Blue, tag = "Inner", endMatchText = "inner end", endTag = "Inner")
        val logs = entries(
            "Outer" to "outer start",
            "Inner" to "inner start",
            "Inner" to "inner work",
            "Inner" to "inner end",
            "Outer" to "outer tail",
            "Outer" to "outer end",
        )
        val tab = mkTab("log", "test.log", logs).copy(filter = Filter(sequences = listOf(outer, inner)))

        val result = expandSelectionThroughCollapsedBlocks(tab, setOf(1)).ids

        // rid 1's own plain ids (5, 6) plus the whole nested child it owns — its header row (2) AND
        // its ch (3, 4) — matching what SequenceGroupingTest's computeSeqGroups asserts this same
        // fixture produces, plus computeItems' own totalCh accounting for the nested header row.
        assertEquals(setOf(1, 2, 3, 4, 5, 6), result)
    }

    @Test
    fun collapsedNestedHeaderWithParentAlreadyOpenExpandsOnlyItsOwnChildren() {
        val outer = SequenceDef("outer", "outer start", priority = 1, color = Color.Red, tag = "Outer", endMatchText = "outer end", endTag = "Outer")
        val inner = SequenceDef("inner", "inner start", priority = 2, color = Color.Blue, tag = "Inner", endMatchText = "inner end", endTag = "Inner")
        val logs = entries(
            "Outer" to "outer start",
            "Inner" to "inner start",
            "Inner" to "inner work",
            "Inner" to "inner end",
            "Outer" to "outer tail",
            "Outer" to "outer end",
        )
        val tab = mkTab("log", "test.log", logs).copy(
            filter = Filter(sequences = listOf(outer, inner)),
            // Outer (sg_outer_1) is already expanded; only the nested header (sg_inner_2) is
            // collapsed — selecting it must reach only its own ch, not re-walk the outer group.
            expanded = setOf("sg_outer_1"),
        )

        val result = expandSelectionThroughCollapsedBlocks(tab, setOf(2)).ids

        assertEquals(setOf(2, 3, 4), result)
    }

    @Test
    fun cachedVisibleEntriesForIsNullOnAColdCacheAndTheFunctionStillResolvesCorrectly() {
        val tab = mkTab("log", "test.log", entries("A" to "1", "A" to "2", "A" to "3"))
            .copy(manualBlocks = listOf(ManualCollapseBlock("m1", 2, ManualCollapseDirection.TO_START)))

        // computeItems(tab, true) was never called, so there's nothing to memoize yet.
        assertNull(cachedVisibleEntriesFor(tab, applyFilter = true))

        val result = expandSelectionThroughCollapsedBlocks(tab, setOf(2)).ids

        assertEquals(setOf(1, 2), result)
    }

    @Test
    fun mixedSelectionAcrossAllThreeFoldKindsUnionsTheirExpansions() {
        val tab = mkTab(
            "log", "test.log",
            entries(
                "App" to "plain", "App" to "keep", "App" to "Exception", "App" to "at foo",
                "App" to "at bar", "App" to "manual anchor", "App" to "tail one", "App" to "tail two",
            ),
            analysis = LogAnalysis(stackTraceGroups = listOf(StackTraceGroup(gid = "st_3", rid = 3, memberIds = listOf(4, 5)))),
        ).copy(manualBlocks = listOf(ManualCollapseBlock("m1", 6, ManualCollapseDirection.TO_END)))

        val result = expandSelectionThroughCollapsedBlocks(tab, setOf(1, 3, 6)).ids

        assertEquals(setOf(1, 3, 4, 5, 6, 7, 8), result)
    }

    @Test
    fun manualBlockAnchoredInsideAnActiveSequenceGroupStillExpandsOnItsOwn() {
        val outer = SequenceDef("outer", "outer start", priority = 1, color = Color.Red, tag = "Outer", endMatchText = "outer end", endTag = "Outer")
        val logs = entries(
            "Outer" to "outer start", "Worker" to "step one", "Worker" to "step two",
            "Worker" to "step three", "Outer" to "outer end",
        )
        val tab = mkTab("log", "test.log", logs).copy(
            filter = Filter(sequences = listOf(outer)),
            // Anchored on row 3, which computeItems would render nested INSIDE the "outer" sequence
            // group (rid 1) once that group is expanded — but expandSelectionThroughCollapsedBlocks
            // treats each fold kind independently by id, so the manual block resolves correctly
            // whether or not its host sequence is selected too.
            manualBlocks = listOf(ManualCollapseBlock("m1", 3, ManualCollapseDirection.TO_START)),
        )

        val result = expandSelectionThroughCollapsedBlocks(tab, setOf(3)).ids

        assertEquals(setOf(1, 2, 3), result)
    }

    // W4: TO_START/TO_END on a huge tab can resolve to the entire file (`0..anchor` or
    // `anchor..lastIndex`) — these two guard the SELECTION_EXPANSION_MAX_IDS bound that exists to
    // stop that from allocating a multi-hundred-MB boxed set on the composition thread.

    @Test
    fun manualBlockOverBudgetSignalsBoundExceededAndKeepsOnlyBoundaryIds() {
        val n = SELECTION_EXPANSION_MAX_IDS + 5
        val logs = (1..n).map { LogEntry(it, "10:00:00.000", LogLevel.I, "A", "line $it") }
        val tab = mkTab("log", "test.log", logs)
            .copy(manualBlocks = listOf(ManualCollapseBlock("m1", n, ManualCollapseDirection.TO_START)))

        val expansion = expandSelectionThroughCollapsedBlocks(tab, setOf(n))

        // Not enumerated — the point of the bound — but the range's own endpoints (the file's first
        // id and the anchor itself) are still present so Seq3Session.rangeFor can still compute the
        // correct from/to span without them.
        assertTrue(expansion.boundExceeded)
        assertTrue(1 in expansion.ids)
        assertTrue(n in expansion.ids)
        assertTrue(expansion.ids.size < SELECTION_EXPANSION_MAX_IDS)
    }

    @Test
    fun manualBlockAtExactlyTheBudgetStillExpandsInFull() {
        val n = SELECTION_EXPANSION_MAX_IDS
        val logs = (1..n).map { LogEntry(it, "10:00:00.000", LogLevel.I, "A", "line $it") }
        val tab = mkTab("log", "test.log", logs)
            .copy(manualBlocks = listOf(ManualCollapseBlock("m1", n, ManualCollapseDirection.TO_START)))

        val expansion = expandSelectionThroughCollapsedBlocks(tab, setOf(n))

        assertFalse(expansion.boundExceeded)
        assertEquals(n, expansion.ids.size)
    }
}
