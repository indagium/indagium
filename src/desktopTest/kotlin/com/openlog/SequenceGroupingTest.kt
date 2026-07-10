package com.openlog

import androidx.compose.ui.graphics.Color
import com.openlog.model.Filter
import com.openlog.model.LogEntry
import com.openlog.model.LogItem
import com.openlog.model.LogLevel
import com.openlog.model.LogTab
import com.openlog.model.ManualCollapseBlock
import com.openlog.model.ManualCollapseDirection
import com.openlog.model.SequenceDef
import com.openlog.ui.DANGER_RED
import com.openlog.ui.mkTab
import com.openlog.utils.computeItems
import com.openlog.utils.computeSeqGroups
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SequenceGroupingTest {
    @Test
    fun tagScopedSequencesDoNotMatchTheSameMessageFromOtherTags() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Auth", "request started"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "com.app.Network", "request started"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "com.app.Auth", "request finished"),
        )
        val sequence = SequenceDef("auth-start", "request started", priority = 1, color = Color.Red, tag = "com.app.Auth")

        val groups = computeSeqGroups(logs, listOf(sequence))

        assertEquals(listOf(1), groups.map { it.rid })
        assertEquals(listOf(2, 3), groups.single().plain)
    }

    @Test
    fun startEndSequenceCanUseDifferentTagsAndIncludesEndLine() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Auth", "flow started"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "com.app.Auth", "flow finished"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "com.app.Worker", "middle event"),
            LogEntry(4, "10:00:00.300", LogLevel.I, "com.app.Lifecycle", "flow finished"),
            LogEntry(5, "10:00:00.400", LogLevel.I, "com.app.Auth", "after flow"),
        )
        val sequence = SequenceDef(
            id = "auth-flow",
            matchText = "flow started",
            priority = 1,
            color = Color.Red,
            tag = "com.app.Auth",
            endMatchText = "flow finished",
            endTag = "com.app.Lifecycle",
        )

        val groups = computeSeqGroups(logs, listOf(sequence))

        assertEquals(listOf(1), groups.map { it.rid })
        assertEquals(listOf(2, 3, 4), groups.single().plain)
    }

    @Test
    fun startEndSequenceCanContainNestedStartEndSequence() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Outer", "outer start"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Inner", "inner start"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "Inner", "inner work"),
            LogEntry(4, "10:00:00.300", LogLevel.I, "Inner", "inner end"),
            LogEntry(5, "10:00:00.400", LogLevel.I, "Outer", "outer tail"),
            LogEntry(6, "10:00:00.500", LogLevel.I, "Outer", "outer end"),
        )
        val outer = SequenceDef("outer", "outer start", priority = 1, color = Color.Red, tag = "Outer", endMatchText = "outer end", endTag = "Outer")
        val inner = SequenceDef("inner", "inner start", priority = 2, color = Color.Blue, tag = "Inner", endMatchText = "inner end", endTag = "Inner")

        val groups = computeSeqGroups(logs, listOf(outer, inner))

        assertEquals(listOf(1), groups.map { it.rid })
        assertEquals(listOf(5, 6), groups.single().plain)
        val nested = groups.single().nested.single()
        assertEquals(2, nested.rid)
        assertEquals(listOf(3, 4), nested.ch)
    }

    @Test
    fun expandedNestedSequenceItemsKeepOriginalLineOrder() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Outer", "outer start"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Inner", "inner start"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "Inner", "inner work"),
            LogEntry(4, "10:00:00.300", LogLevel.I, "Inner", "inner end"),
            LogEntry(5, "10:00:00.400", LogLevel.I, "Outer", "outer tail"),
            LogEntry(6, "10:00:00.500", LogLevel.I, "Outer", "outer end"),
        )
        val outer = SequenceDef("outer", "outer start", priority = 1, color = Color.Red, tag = "Outer", endMatchText = "outer end", endTag = "Outer")
        val inner = SequenceDef("inner", "inner start", priority = 2, color = Color.Blue, tag = "Inner", endMatchText = "inner end", endTag = "Inner")
        val tab = LogTab(
            id = "log",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(sequences = listOf(outer, inner)),
            expanded = setOf("sg_outer_1", "sg_inner_2"),
        )

        val items = computeItems(tab, applyFilter = true)
        val orderedIds = items.map {
            when (it) {
                is LogItem.Row -> it.entry.id
                is LogItem.SeqHeader -> it.entry.id
                is LogItem.ManualHeader -> it.entry.id
                is LogItem.StackTraceHeader -> it.entry.id
            }
        }

        assertEquals(listOf(1, 2, 3, 4, 5, 6), orderedIds)
    }

    @Test
    fun singleBoundarySequenceStillEndsAtNextBoundary() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Auth", "request started"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "com.app.Auth", "inside first"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "com.app.Auth", "request started"),
            LogEntry(4, "10:00:00.300", LogLevel.I, "com.app.Auth", "inside second"),
        )
        val sequence = SequenceDef("auth-start", "request started", priority = 1, color = Color.Red, tag = "com.app.Auth")

        val groups = computeSeqGroups(logs, listOf(sequence))

        assertEquals(listOf(1, 3), groups.map { it.rid })
        assertEquals(listOf(2), groups[0].plain)
        assertEquals(listOf(4), groups[1].plain)
    }

    @Test
    fun twoOccurrencesOfAnUnresolvedEndPatternAreSiblingsNotNested() {
        // The def has an endMatchText, but it never actually appears anywhere in this log (e.g.
        // the log was cut before the matching event happened). Both occurrences of the start
        // pattern independently fail to find their own end and fall back — the fallback must stop
        // each one at the next start match (same as if no end pattern were configured at all), not
        // literal end-of-log: falling back to end-of-log for both would give them the exact same
        // endExclusive, and parentByChild's containment check would then treat the second
        // occurrence as nested inside the first purely because they share that coincidental
        // fallback boundary, not because it's actually contained within it.
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Auth", "request started"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "com.app.Auth", "inside first"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "com.app.Auth", "request started"),
            LogEntry(4, "10:00:00.300", LogLevel.I, "com.app.Auth", "inside second"),
        )
        val sequence = SequenceDef(
            "auth-start", "request started", priority = 1, color = Color.Red, tag = "com.app.Auth",
            endMatchText = "request finished",
        )

        val groups = computeSeqGroups(logs, listOf(sequence))

        assertEquals(listOf(1, 3), groups.map { it.rid })
        assertTrue(groups.all { it.nested.isEmpty() })
        assertEquals(listOf(2), groups[0].plain)
        assertEquals(listOf(4), groups[1].plain)
    }

    @Test
    fun sameRuleStartBeforeFirstEndStartsSiblingSequence() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "flow start"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "App", "flow start"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "App", "flow end"),
        )
        val sequence = SequenceDef(
            "flow",
            "flow start",
            priority = 1,
            color = Color.Blue,
            tag = "App",
            endMatchText = "flow end",
            endTag = "App",
        )

        val groups = computeSeqGroups(logs, listOf(sequence))

        assertEquals(listOf(1, 2), groups.map { it.rid })
        assertTrue(groups.all { it.nested.isEmpty() })
        assertTrue(groups[0].plain.isEmpty())
        assertEquals(listOf(3), groups[1].plain)
    }

    @Test
    fun manualCollapseToEndHidesRowsAfterAnchor() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "before"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "App", "anchor"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "App", "hidden"),
        )
        val tab = LogTab(
            id = "log",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(),
            manualBlocks = listOf(ManualCollapseBlock("m1", 2, ManualCollapseDirection.TO_END)),
        )

        val items = computeItems(tab, applyFilter = true)

        assertEquals(listOf(1), items.filterIsInstance<LogItem.Row>().map { it.entry.id })
        val header = items.filterIsInstance<LogItem.ManualHeader>().single()
        assertEquals(2, header.entry.id)
        assertEquals(2, header.count)
    }

    @Test
    fun expandedManualCollapseShowsRangeRowsWithGuideColor() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "before"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "App", "anchor"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "App", "hidden"),
        )
        val block = ManualCollapseBlock("m1", 2, ManualCollapseDirection.TO_END, color = Color.Red)
        val tab = LogTab(
            id = "log",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(),
            expanded = setOf(block.id),
            manualBlocks = listOf(block),
        )

        val items = computeItems(tab, applyFilter = true)

        val guidedRows = items.filterIsInstance<LogItem.Row>().filter { it.groupColor == Color.Red }
        assertEquals(listOf(3), guidedRows.map { it.entry.id })
    }

    @Test
    fun manualCollapseRangeCollapsesExactlyTheSpecifiedSpan() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "before"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "App", "start"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "App", "middle"),
            LogEntry(4, "10:00:00.300", LogLevel.I, "App", "end"),
            LogEntry(5, "10:00:00.400", LogLevel.I, "App", "after"),
        )
        val tab = LogTab(
            id = "log",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(),
            manualBlocks = listOf(ManualCollapseBlock("m1", 2, ManualCollapseDirection.RANGE, endId = 4)),
        )

        val items = computeItems(tab, applyFilter = true)

        assertEquals(listOf(1, 5), items.filterIsInstance<LogItem.Row>().map { it.entry.id })
        val header = items.filterIsInstance<LogItem.ManualHeader>().single()
        assertEquals(2, header.entry.id)
        assertEquals(3, header.count)
    }

    @Test
    fun manualCollapseRangeIsOrderIndependentBetweenAnchorAndEnd() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "before"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "App", "start"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "App", "middle"),
            LogEntry(4, "10:00:00.300", LogLevel.I, "App", "end"),
        )
        // anchorId is the LATER id and endId is the EARLIER one — the covered range must still be
        // [2,4] via min/max, regardless of which end is which.
        val tab = LogTab(
            id = "log",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(),
            manualBlocks = listOf(ManualCollapseBlock("m1", 4, ManualCollapseDirection.RANGE, endId = 2)),
        )

        val items = computeItems(tab, applyFilter = true)

        assertEquals(listOf(1), items.filterIsInstance<LogItem.Row>().map { it.entry.id })
        val header = items.filterIsInstance<LogItem.ManualHeader>().single()
        assertEquals(3, header.count)
    }

    @Test
    fun expandedManualCollapseCanShowExpandedSequencesInsideItsRange() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Seq", "flow start"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Seq", "flow child"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "App", "anchor"),
        )
        val block = ManualCollapseBlock("m1", 3, ManualCollapseDirection.TO_START, color = Color.Red)
        val sequence = SequenceDef("flow", "flow start", priority = 1, color = Color.Blue, tag = "Seq")
        val tab = LogTab(
            id = "log",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(sequences = listOf(sequence)),
            expanded = setOf(block.id, "sg_flow_1"),
            manualBlocks = listOf(block),
        )

        val items = computeItems(tab, applyFilter = true)

        val sequenceHeader = items.filterIsInstance<LogItem.SeqHeader>().single()
        assertEquals(1, sequenceHeader.entry.id)
        assertTrue(sequenceHeader.expanded)
        assertEquals(2, sequenceHeader.count) // swallows both id 2 and id 3, unaffected by the manual block
        val row2 = items.filterIsInstance<LogItem.Row>().single()
        assertEquals(2, row2.entry.id)
        assertEquals(2, row2.indent) // nested inside both the manual header and the sequence header
    }

    @Test
    fun expandedManualCollapseCanShowSequenceStartedByAnchor() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "before"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Seq", "flow start"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "Seq", "flow child"),
        )
        val block = ManualCollapseBlock("m1", 2, ManualCollapseDirection.TO_START, color = Color.Red)
        val sequence = SequenceDef("flow", "flow start", priority = 1, color = Color.Blue, tag = "Seq")
        val tab = LogTab(
            id = "log",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(sequences = listOf(sequence)),
            expanded = setOf(block.id, "sg_flow_2"),
            manualBlocks = listOf(block),
        )

        val items = computeItems(tab, applyFilter = true)

        val sequenceHeader = items.filterIsInstance<LogItem.SeqHeader>().single()
        assertEquals(2, sequenceHeader.entry.id)
        assertTrue(sequenceHeader.expanded)
        // The sequence's true end (id 3) lies past the manual block's own declared end (which
        // covers only ids 1-2) — before the fix this got truncated to a count of 0 and id 3
        // rendered as an orphaned top-level row instead of nesting under the sequence.
        assertEquals(1, sequenceHeader.count)
        val rows = items.filterIsInstance<LogItem.Row>().associateBy { it.entry.id }
        assertEquals(listOf(1, 3), rows.keys.sorted())
        assertEquals(1, rows.getValue(1).indent) // directly under the manual header
        assertEquals(2, rows.getValue(3).indent) // nested one level deeper, under the sequence
    }

    @Test
    fun manualBlockStraddlingASequenceDoesNotTruncateItWhenBothEndsAreUnaffected() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "before"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Seq", "flow start"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "Seq", "middle"),
            LogEntry(4, "10:00:00.300", LogLevel.I, "Seq", "flow end"),
            LogEntry(5, "10:00:00.400", LogLevel.I, "App", "after"),
        )
        // Manual range strictly inside the sequence's true range [id2, id4] — entries before (id1)
        // and after (id5) the sequence are both untouched by the manual block.
        val block = ManualCollapseBlock("m1", 3, ManualCollapseDirection.RANGE, color = Color.Red, endId = 3)
        val sequence = SequenceDef(
            "flow", "flow start", priority = 1, color = Color.Blue, tag = "Seq",
            endMatchText = "flow end", endTag = "Seq",
        )
        val tab = LogTab(
            id = "log",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(sequences = listOf(sequence)),
            // Sequence expanded, manual block left collapsed.
            expanded = setOf("sg_flow_2"),
            manualBlocks = listOf(block),
        )

        val items = computeItems(tab, applyFilter = true)

        val sequenceHeader = items.filterIsInstance<LogItem.SeqHeader>().single()
        assertEquals(2, sequenceHeader.entry.id)
        assertEquals(2, sequenceHeader.count) // ids 3 and 4, both still swallowed — not truncated
        // Nested inside the expanded sequence's children (not a disjoint top-level chunk) — proven
        // by the manual header only appearing between the sequence header and its id-4 child.
        val seqIdx = items.indexOf(sequenceHeader)
        val manualHeader = items.filterIsInstance<LogItem.ManualHeader>().single()
        assertFalse(manualHeader.expanded)
        val manualIdx = items.indexOf(manualHeader)
        assertTrue(manualIdx > seqIdx)
        val rows = items.filterIsInstance<LogItem.Row>().map { it.entry.id }.sorted()
        assertEquals(listOf(1, 4, 5), rows) // id3 is inside the collapsed manual block, hidden
    }

    @Test
    fun manualBlockFullyContainingASequenceNestsItInside() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Seq", "flow start"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Seq", "middle"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "Seq", "flow end"),
        )
        val block = ManualCollapseBlock("m1", 1, ManualCollapseDirection.RANGE, color = Color.Red, endId = 3)
        val sequence = SequenceDef(
            "flow", "flow start", priority = 1, color = Color.Blue, tag = "Seq",
            endMatchText = "flow end", endTag = "Seq",
        )
        val tab = LogTab(
            id = "log",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(sequences = listOf(sequence)),
            expanded = setOf(block.id, "sg_flow_1"),
            manualBlocks = listOf(block),
        )

        val items = computeItems(tab, applyFilter = true)

        val manualHeader = items.filterIsInstance<LogItem.ManualHeader>().single()
        assertEquals(3, manualHeader.count) // ids 1-3, the block's own declared range
        val sequenceHeader = items.filterIsInstance<LogItem.SeqHeader>().single()
        assertEquals(1, sequenceHeader.indent) // nested one level inside the manual header
        assertEquals(2, sequenceHeader.count) // ids 2 and 3, both swallowed — not truncated
        assertTrue(items.indexOf(sequenceHeader) > items.indexOf(manualHeader))
        val rows = items.filterIsInstance<LogItem.Row>().map { it.entry.id }.sorted()
        assertEquals(listOf(2, 3), rows) // id1 renders as the sequence header, not a separate row
    }

    // Mirror of expandedManualCollapseCanShowSequenceStartedByAnchor's crossing case, but with the
    // sequence starting first and the manual block's range extending past the sequence's own end —
    // the sequence should still host the manual block, nested one level in, past its own boundary.
    @Test
    fun manualBlockCrossingPastASequencesEndNestsUnderTheSequence() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Seq", "flow start"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Seq", "middle"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "Seq", "flow end"),
            LogEntry(4, "10:00:00.300", LogLevel.I, "App", "after"),
        )
        // Sequence's true range is [id1, id3]. The manual range [id3, id4] starts on the
        // sequence's own last entry but extends one entry past the sequence's end.
        val block = ManualCollapseBlock("m1", 3, ManualCollapseDirection.RANGE, color = Color.Red, endId = 4)
        val sequence = SequenceDef(
            "flow", "flow start", priority = 1, color = Color.Blue, tag = "Seq",
            endMatchText = "flow end", endTag = "Seq",
        )
        val tab = LogTab(
            id = "log",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(sequences = listOf(sequence)),
            expanded = setOf("sg_flow_1", block.id),
            manualBlocks = listOf(block),
        )

        val items = computeItems(tab, applyFilter = true)

        val sequenceHeader = items.filterIsInstance<LogItem.SeqHeader>().single()
        assertEquals(1, sequenceHeader.entry.id)
        assertEquals(2, sequenceHeader.count) // ids 2 and 3, both still swallowed — not truncated
        val manualHeader = items.filterIsInstance<LogItem.ManualHeader>().single()
        assertTrue(items.indexOf(manualHeader) > items.indexOf(sequenceHeader))
        val rows = items.filterIsInstance<LogItem.Row>().map { it.entry.id }.sorted()
        assertEquals(listOf(2, 4), rows) // id3 is the manual block's own anchor, filtered from its body
    }

    // ── SeqComputer gap coverage ──────────────────────────────────────────────

    @Test
    fun disabledSequenceDefIsIgnored() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "flow start"),
            LogEntry(2, "10:00:00.001", LogLevel.I, "App", "child"),
        )
        val seq = SequenceDef("flow", "flow start", priority = 1, color = Color.Blue, tag = "App", enabled = false)

        val groups = computeSeqGroups(logs, listOf(seq))

        assertTrue(groups.isEmpty())
    }

    @Test
    fun lowerPriorityNumberWinsWhenTwoDefsMatchSameEntry() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "start here"),
            LogEntry(2, "10:00:00.001", LogLevel.I, "App", "child"),
        )
        val winner = SequenceDef("winner", "start", priority = 1, color = Color.Red)
        val loser = SequenceDef("loser", "start", priority = 2, color = Color.Blue)

        // Pass in reverse order to confirm sorting, not insertion order, decides the winner
        val groups = computeSeqGroups(logs, listOf(loser, winner))

        assertEquals(1, groups.size)
        assertEquals("winner", groups.single().defId)
    }

    @Test
    fun regexMatchTextMatchesEntryByPattern() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "Activity resumed: MainActivity"),
            LogEntry(2, "10:00:00.001", LogLevel.I, "App", "Activity paused: MainActivity"),
        )
        val seq = SequenceDef("activity", """Activity\s+resumed""", isRegex = true, priority = 1, color = Color.Blue)

        val groups = computeSeqGroups(logs, listOf(seq))

        assertEquals(1, groups.size)
        assertEquals(1, groups.single().rid)
    }

    @Test
    fun invalidRegexProducesNoGroupsWithoutException() {
        val logs = listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "something"))
        val seq = SequenceDef("bad", "[broken", isRegex = true, priority = 1, color = Color.Blue)

        val groups = computeSeqGroups(logs, listOf(seq))

        assertTrue(groups.isEmpty())
    }

    @Test
    fun emptyLogDataProducesNoGroups() {
        val seq = SequenceDef("flow", "start", priority = 1, color = Color.Blue)

        val groups = computeSeqGroups(emptyList(), listOf(seq))

        assertTrue(groups.isEmpty())
    }

    @Test
    fun groupWithNoChildrenHasEmptyPlainAndNested() {
        val logs = listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "flow start"))
        val seq = SequenceDef("flow", "flow start", priority = 1, color = Color.Blue, tag = "App")

        val groups = computeSeqGroups(logs, listOf(seq))

        assertEquals(1, groups.size)
        val group = groups.single()
        assertEquals(1, group.rid)
        assertTrue(group.plain.isEmpty())
        assertTrue(group.nested.isEmpty())
    }

    @Test
    fun adjacentEndMatchTextGroupsDoNotOverlap() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "flow start"),
            LogEntry(2, "10:00:00.001", LogLevel.I, "App", "flow content"),
            LogEntry(3, "10:00:00.002", LogLevel.I, "App", "flow end"),
            LogEntry(4, "10:00:00.003", LogLevel.I, "App", "flow start"),
            LogEntry(5, "10:00:00.004", LogLevel.I, "App", "flow content again"),
            LogEntry(6, "10:00:00.005", LogLevel.I, "App", "flow end"),
        )
        val seq = SequenceDef("flow", "flow start", priority = 1, color = Color.Blue, endMatchText = "flow end")

        val groups = computeSeqGroups(logs, listOf(seq))

        assertEquals(2, groups.size)
        assertEquals(1, groups[0].rid)
        assertEquals(listOf(2, 3), groups[0].plain)
        assertEquals(4, groups[1].rid)
        assertEquals(listOf(5, 6), groups[1].plain)
    }

    // ── Stack-trace / sequence interaction ─────────────────────────────────────

    @Test
    fun topLevelStackTraceChildRowsGetDangerRedGuideColor() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
        )
        // mkTab computes real analysis (stackTraceGroups) synchronously, unlike a bare LogTab(...)
        // whose analysis now defaults to pending — this test needs the fold already resolved.
        val tab = mkTab("log", "test.log", logs).copy(expanded = setOf("st_1"))

        val items = computeItems(tab, applyFilter = true)

        val header = items.filterIsInstance<LogItem.StackTraceHeader>().single()
        assertEquals(1, header.entry.id)
        val row = items.filterIsInstance<LogItem.Row>().single()
        assertEquals(2, row.entry.id)
        assertEquals(DANGER_RED, row.groupColor)
    }

    @Test
    fun unboundedSequenceDoesNotSwallowCrashItAlwaysRendersAtTopLevel() {
        // "burst" has no endMatchText, so per computeSeqGroups() it swallows everything up to the
        // next start match (there is none here) or end-of-log as its own unstructured "plain"
        // children — including the FATAL EXCEPTION block that follows, which has nothing to do
        // with the sequence. The crash must still render as its own always-visible collapsible
        // block, "escaping" the sequence — even while the sequence itself stays collapsed,
        // unlike its own genuinely-swallowed plain content ("plain content" below).
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "burst start", pid = 1),
            LogEntry(2, "10:00:00.100", LogLevel.I, "App", "plain content", pid = 1),
            LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 1),
            LogEntry(4, "10:00:00.300", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 1),
        )
        val seq = SequenceDef("burst", "burst start", priority = 1, color = Color.Blue, tag = "App")
        val tab = mkTab("log", "test.log", logs).copy(filter = Filter(sequences = listOf(seq)))

        val items = computeItems(tab, applyFilter = true)

        val seqHeader = items.filterIsInstance<LogItem.SeqHeader>().single()
        assertEquals(1, seqHeader.entry.id)
        assertTrue(!seqHeader.expanded)
        assertTrue(items.filterIsInstance<LogItem.Row>().none { it.entry.id == 2 })
        val crashHeader = items.filterIsInstance<LogItem.StackTraceHeader>().single()
        assertEquals(3, crashHeader.entry.id)
        assertEquals(0, crashHeader.indent)
        assertTrue(!crashHeader.expanded)
        assertEquals(1, crashHeader.count)
    }

    @Test
    fun crashNestsInsideItsSequenceOnceThatSequenceIsExpanded() {
        // Once the swallowing sequence is expanded, the crash renders nested inside it (a "this
        // happened during X" grouping) rather than at the top level — the reverse of the
        // collapsed case above, but still requiring no *new* expansion to reveal: both gids here
        // were already in tab.expanded before this render, not added by it.
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "burst start", pid = 1),
            LogEntry(2, "10:00:00.100", LogLevel.I, "App", "plain content", pid = 1),
            LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 1),
            LogEntry(4, "10:00:00.300", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 1),
        )
        val seq = SequenceDef("burst", "burst start", priority = 1, color = Color.Blue, tag = "App")
        val tab = mkTab("log", "test.log", logs)
            .copy(filter = Filter(sequences = listOf(seq)), expanded = setOf("sg_burst_1", "st_3"))

        val items = computeItems(tab, applyFilter = true)

        val rows = items.filterIsInstance<LogItem.Row>()
        assertEquals(listOf(2), rows.filter { it.groupColor != DANGER_RED }.map { it.entry.id })
        val crashHeader = items.filterIsInstance<LogItem.StackTraceHeader>().single()
        assertEquals(3, crashHeader.entry.id)
        assertEquals(1, crashHeader.indent)
        assertTrue(crashHeader.expanded)
        val memberRow = rows.single { it.entry.id == 4 }
        assertEquals(2, memberRow.indent)
        assertEquals(DANGER_RED, memberRow.groupColor)
    }

    @Test
    fun crashCollapsingBackToTopLevelWhenItsSequenceCollapses() {
        // The reverse transition: while the sequence was expanded the crash rendered nested (see
        // above); once the sequence collapses again, the crash must still be visible — as its own
        // top-level block, not hidden along with the rest of the sequence's swallowed content.
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "burst start", pid = 1),
            LogEntry(2, "10:00:00.100", LogLevel.I, "App", "plain content", pid = 1),
            LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 1),
            LogEntry(4, "10:00:00.300", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 1),
        )
        val seq = SequenceDef("burst", "burst start", priority = 1, color = Color.Blue, tag = "App")
        val tab = mkTab("log", "test.log", logs).copy(
            filter = Filter(sequences = listOf(seq)),
            // Sequence collapsed again, but the crash's own gid is still marked expanded from
            // before — it must still show as a top-level, expanded StackTraceHeader.
            expanded = setOf("st_3"),
        )

        val items = computeItems(tab, applyFilter = true)

        assertTrue(items.filterIsInstance<LogItem.Row>().none { it.entry.id == 2 })
        val crashHeader = items.filterIsInstance<LogItem.StackTraceHeader>().single()
        assertEquals(3, crashHeader.entry.id)
        assertEquals(0, crashHeader.indent)
        assertTrue(crashHeader.expanded)
        val memberRow = items.filterIsInstance<LogItem.Row>().single { it.entry.id == 4 }
        assertEquals(1, memberRow.indent)
        assertEquals(DANGER_RED, memberRow.groupColor)
    }

    @Test
    fun sequencesDisabledLeavesStackTraceFoldingUnaffected() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "burst start", pid = 1),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 1),
            LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 1),
        )
        val seq = SequenceDef("burst", "burst start", priority = 1, color = Color.Blue, tag = "App")
        val tab = mkTab("log", "test.log", logs).copy(filter = Filter(sequences = listOf(seq), seqOn = false))

        val items = computeItems(tab, applyFilter = true)

        assertTrue(items.filterIsInstance<LogItem.SeqHeader>().isEmpty())
        val header = items.filterIsInstance<LogItem.StackTraceHeader>().single()
        assertEquals(2, header.entry.id)
        assertEquals(0, header.indent)
        assertTrue(!header.expanded)
        assertEquals(listOf(1), items.filterIsInstance<LogItem.Row>().map { it.entry.id })
    }

    @Test
    fun disabledManualCollapseDoesNotHideRows() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "before"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "App", "anchor"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "App", "hidden"),
        )
        val tab = LogTab(
            id = "log",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(),
            manualBlocks = listOf(ManualCollapseBlock("m1", 2, ManualCollapseDirection.TO_END, enabled = false)),
        )

        val items = computeItems(tab, applyFilter = true)

        assertEquals(listOf(1, 2, 3), items.filterIsInstance<LogItem.Row>().map { it.entry.id })
        assertTrue(items.filterIsInstance<LogItem.ManualHeader>().isEmpty())
    }
}
