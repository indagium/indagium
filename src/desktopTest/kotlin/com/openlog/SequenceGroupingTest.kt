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
import com.openlog.utils.computeItems
import com.openlog.utils.computeSeqGroups
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals(listOf(2), items.filterIsInstance<LogItem.Row>().map { it.entry.id })
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
        assertEquals(listOf(1, 3), items.filterIsInstance<LogItem.Row>().map { it.entry.id })
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
