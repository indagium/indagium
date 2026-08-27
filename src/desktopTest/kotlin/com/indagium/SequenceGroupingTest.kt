package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.Filter
import com.indagium.model.LogEntry
import com.indagium.model.LogItem
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.ManualCollapseBlock
import com.indagium.model.ManualCollapseDirection
import com.indagium.model.SequenceDef
import com.indagium.ui.DANGER_RED
import com.indagium.ui.mkTab
import com.indagium.utils.CancellationCheck
import com.indagium.utils.CrossingThreadHint
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.cachedCrossingThreadHintsFor
import com.indagium.utils.computeItems
import com.indagium.utils.computeSeqGroups
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

    // ── Wave 2.1: thread-scoped ("async") sequences ─────────────────────────────────────────────
    //
    // scopeTid narrows a SequenceDef to one thread on top of (never instead of) tag scoping: a
    // start logged by thread A could otherwise be closed by an end from thread B, and two
    // interleaved runs of the same flow become one crossing group the user can't attribute to
    // either run. These cover the three places SeqComputer applies it (start match, end match,
    // childIds) plus a regression guard that the null (unscoped) default is unaffected.

    @Test
    fun scopeTidNarrowsWhichEntryCanStartASequence() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Auth", "flow started", tid = 200),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Auth", "flow started", tid = 100),
            LogEntry(3, "10:00:00.200", LogLevel.I, "Auth", "flow finished", tid = 100),
        )
        val sequence = SequenceDef(
            id = "auth-flow", matchText = "flow started", priority = 1, color = Color.Red,
            endMatchText = "flow finished", scopeTid = 100,
        )

        val groups = computeSeqGroups(logs, listOf(sequence))

        // The identical "flow started" text from tid 200 never opens a group at all — it's
        // ignored outright as a candidate, the same way a tag mismatch already is.
        assertEquals(listOf(2), groups.map { it.rid })
    }

    @Test
    fun scopeTidNarrowsWhichEntryCanEndASequence() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Auth", "flow started", tid = 100),
            // false end: wrong thread
            LogEntry(2, "10:00:00.100", LogLevel.I, "Auth", "flow finished", tid = 200),
            LogEntry(3, "10:00:00.200", LogLevel.I, "Auth", "middle work", tid = 100),
            // real end
            LogEntry(4, "10:00:00.300", LogLevel.I, "Auth", "flow finished", tid = 100),
        )
        val sequence = SequenceDef(
            id = "auth-flow", matchText = "flow started", priority = 1, color = Color.Red,
            endMatchText = "flow finished", scopeTid = 100,
        )

        val groups = computeSeqGroups(logs, listOf(sequence))

        assertEquals(listOf(1), groups.map { it.rid })
        // The tid-200 "flow finished" is ignored outright as an end candidate — the group closes
        // at the real (tid-100) end two lines later, not prematurely at entry 2. Entry 2 itself is
        // additionally excluded from `plain` by the childIds tid filter proven separately below.
        assertEquals(listOf(3, 4), groups.single().plain)
    }

    @Test
    fun scopeTidExcludesOtherThreadsEntriesFromChildIds() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Auth", "flow started", tid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Worker", "unrelated noise from another thread", tid = 200),
            LogEntry(3, "10:00:00.200", LogLevel.I, "Auth", "same-thread middle event", tid = 100),
            LogEntry(4, "10:00:00.300", LogLevel.I, "Auth", "flow finished", tid = 100),
        )
        val sequence = SequenceDef(
            id = "auth-flow", matchText = "flow started", priority = 1, color = Color.Red,
            endMatchText = "flow finished", scopeTid = 100,
        )

        val groups = computeSeqGroups(logs, listOf(sequence))

        assertEquals(listOf(1), groups.map { it.rid })
        // Entry 2 falls inside the group's index range but came from a different thread — it must
        // render as a plain row OUTSIDE the group, not be silently swallowed into this run.
        assertEquals(listOf(3, 4), groups.single().plain)
    }

    @Test
    fun unscopedSequenceStillSwallowsEntriesFromEveryThreadRegressionGuard() {
        // scopeTid defaults to null (unscoped) — an interleaved line from a different thread must
        // still be swallowed exactly as it always was, with no behavior change for every existing
        // (unscoped) SequenceDef.
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Auth", "flow started", tid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Worker", "interleaved from another thread", tid = 200),
            LogEntry(3, "10:00:00.200", LogLevel.I, "Auth", "flow finished", tid = 100),
        )
        val sequence = SequenceDef(
            id = "auth-flow", matchText = "flow started", priority = 1, color = Color.Red,
            endMatchText = "flow finished",
        )

        val groups = computeSeqGroups(logs, listOf(sequence))

        assertEquals(listOf(2, 3), groups.single().plain)
    }

    // ── renderRange's own scopeTid handling (bug: computeSeqGroups already excluded a foreign-tid
    // entry from `plain`/`ch`, but the RENDERER never consulted scopeTid at all — every index in
    // [start, endExclusive) rendered nested and sequence-tinted regardless, which is what the user
    // actually saw: a foreign-thread line rendered inside an async sequence it wasn't part of). ──

    private fun asyncScopeLogs() = listOf(
        LogEntry(1, "t", LogLevel.I, "Other", "before",       pid = 9, tid = 200),
        LogEntry(2, "t", LogLevel.I, "Seq",   "touch start",  pid = 1, tid = 100),
        LogEntry(3, "t", LogLevel.I, "Other", "foreign work", pid = 9, tid = 200),
        LogEntry(4, "t", LogLevel.I, "Mine",  "own work",     pid = 1, tid = 100),
        LogEntry(5, "t", LogLevel.I, "Seq",   "touch end",    pid = 1, tid = 100),
        LogEntry(6, "t", LogLevel.I, "Other", "after",        pid = 9, tid = 200),
    )

    private fun asyncScopeTab(expanded: Set<String>): LogTab {
        val logs = asyncScopeLogs()
        return LogTab(
            id = "log", filename = "t.log", logData = logs, rmap = logs.associateBy { it.id },
            filter = Filter(sequences = listOf(
                SequenceDef("s", "touch start", priority = 1, color = Color.Red, tag = "Seq",
                    endMatchText = "touch end", endTag = "Seq", scopeTid = 100),
            )),
            expanded = expanded,
        )
    }

    @Test
    fun expandedAsyncSequenceRendersAForeignThreadRowOutsideTheGroupAtTheEnclosingLevel() {
        val tab = asyncScopeTab(expanded = setOf("sg_s_2"))

        val items = computeItems(tab, applyFilter = true)

        val foreign = items.filterIsInstance<LogItem.Row>().single { it.entry.id == 3 }
        assertEquals(0, foreign.indent, "foreign-tid row must render at the enclosing (non-nested) indent")
        assertEquals(null, foreign.groupColor, "foreign-tid row must not be tinted with the async sequence's color")
        val ownThread = items.filterIsInstance<LogItem.Row>().single { it.entry.id == 4 }
        assertEquals(1, ownThread.indent, "same-tid row must still render nested inside the async sequence")
        assertEquals(Color.Red, ownThread.groupColor, "same-tid row must still be tinted with the sequence's color")
        assertEveryEntryAccountedForExactlyOnce(tab.logData.size, items)
    }

    @Test
    fun expandedAsyncSequenceTintsOnlyGenuineMembersScopedSeqColor() {
        // Task: "color the timestamp and pid/tid too" for a row belonging to a thread-scoped
        // ("async") sequence. LogRow reads LogItem.Row.scopedSeqColor (not groupColor alone,
        // which an ordinary/unscoped sequence's member also carries) to decide whether to tint
        // those columns — this is the pure-logic half of that decision, computed in renderRange.
        val tab = asyncScopeTab(expanded = setOf("sg_s_2"))

        val items = computeItems(tab, applyFilter = true)

        val ownThread = items.filterIsInstance<LogItem.Row>().single { it.entry.id == 4 }
        assertEquals(Color.Red, ownThread.scopedSeqColor, "genuine same-tid member must carry the sequence's color for ts/pid/tid tinting")
        val foreign = items.filterIsInstance<LogItem.Row>().single { it.entry.id == 3 }
        assertEquals(null, foreign.scopedSeqColor, "foreign-tid row must stay untinted — that contrast is the whole point")
        val before = items.filterIsInstance<LogItem.Row>().single { it.entry.id == 1 }
        assertEquals(null, before.scopedSeqColor, "a top-level row outside any sequence must never be tinted")
    }

    @Test
    fun ordinaryUnscopedSequenceMembersAreNotMarkedAsScopedSeqColor() {
        // Contrast case for the above: an ORDINARY (unscoped, scopeTid == null) sequence's member
        // keeps today's appearance exactly — groupColor is still set (the indent/bar already say
        // it belongs), but scopedSeqColor stays null so LogRow does NOT additionally tint ts/pid/tid.
        val logs = listOf(
            LogEntry(1, "t", LogLevel.I, "Seq", "flow start", pid = 1, tid = 100),
            LogEntry(2, "t", LogLevel.I, "Seq", "flow child", pid = 1, tid = 100),
        )
        val tab = LogTab(
            id = "log", filename = "t.log", logData = logs, rmap = logs.associateBy { it.id },
            filter = Filter(sequences = listOf(
                SequenceDef("s", "flow start", priority = 1, color = Color.Blue, tag = "Seq"),
            )),
            expanded = setOf("sg_s_1"),
        )

        val items = computeItems(tab, applyFilter = true)

        val member = items.filterIsInstance<LogItem.Row>().single { it.entry.id == 2 }
        assertEquals(Color.Blue, member.groupColor, "unscoped member still carries groupColor as before")
        assertEquals(null, member.scopedSeqColor, "unscoped member must NOT be marked for the extra ts/pid/tid tint")
    }

    @Test
    fun collapsedAsyncSequenceStillRendersForeignThreadRowsInItsSpan() {
        val tab = asyncScopeTab(expanded = emptySet())

        val items = computeItems(tab, applyFilter = true)

        val header = items.filterIsInstance<LogItem.SeqHeader>().single { it.entry.id == 2 }
        assertFalse(header.expanded)
        // Only the two same-tid entries (id 4, the own-thread work, and id 5, the end line itself)
        // are actually hidden by the collapse — id 3 (tid 200) was never this run's content, so it
        // must render even while the group is collapsed.
        assertEquals(2, header.count)
        val foreign = items.filterIsInstance<LogItem.Row>().single { it.entry.id == 3 }
        assertEquals(0, foreign.indent)
        assertEquals(null, foreign.groupColor)
        val rows = items.filterIsInstance<LogItem.Row>().map { it.entry.id }
        assertEquals(listOf(1, 3, 6), rows) // id 5 (the end line) is folded into the header itself
        assertEveryEntryAccountedForExactlyOnce(tab.logData.size, items)
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
    @Suppress("MagicNumber")
    fun sequenceScanSharesOneRegexContextAcrossAllEntries() {
        val catastrophicMessage = "a".repeat(40) + "!"
        val logs = (1..200).map { id ->
            LogEntry(id, "10:00:00.000", LogLevel.I, "App", catastrophicMessage)
        }
        val seq = SequenceDef("bad", "(a+)+$", isRegex = true, priority = 1, color = Color.Blue)
        val regexContext = RegexEvaluationContext(matchBudgetNanos = 1L)

        val groups = computeSeqGroups(logs, listOf(seq), CancellationCheck {}, regexContext)

        assertTrue(groups.isEmpty())
        assertEquals(1, regexContext.timeoutCountForTesting)
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

    // ── Stack-trace member skip must be ownership-aware, not global (row-loss regression) ─────
    //
    // Filter.kt used to decide "skip this stack-trace member row" from a single global bitset of
    // every id any StackTraceGroup ever claimed — regardless of whether that group's own
    // StackTraceHeader actually got emitted. Header emission is positional (a container branch
    // starting at the same index can pre-empt it, or a collapsed ManualC can jump straight over
    // it), so a member could be claimed and skipped with no header left anywhere to reveal it —
    // gone with no row and no count. Shared fixture for the tests below: a plain line, a
    // "FATAL EXCEPTION: main" trigger, its exception-class follow-up line, four "at ..." frames
    // (all unconditional continuations, satisfying computeStackTraceGroups' Rule C), then three
    // more plain lines — all on the same (default) pid/tid so the whole dump is one group:
    // rid = 2 ("st_2"), memberIds = [3, 4, 5, 6, 7].

    private fun crashDumpLogs() = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "App", "plain before"),
        LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main"),
        LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "java.lang.NullPointerException: boom"),
        LogEntry(4, "10:00:00.300", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)"),
        LogEntry(5, "10:00:00.400", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onResume(Main.java:20)"),
        LogEntry(6, "10:00:00.500", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onStart(Main.java:30)"),
        LogEntry(7, "10:00:00.600", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onStop(Main.java:40)"),
        // Doubles as the "endMatchText" target for the expanded/collapsed-sequence tests below —
        // an ordinary plain line the manual-block-only tests never look at.
        LogEntry(8, "10:00:00.700", LogLevel.I, "App", "after the dump"),
        LogEntry(9, "10:00:00.800", LogLevel.I, "App", "plain after 2"),
        LogEntry(10, "10:00:00.900", LogLevel.I, "App", "plain after 3"),
    )

    // Set-based sibling to assertEveryEntryAccountedForExactlyOnce (above): that helper sums
    // header `count`s, which double-counts an id that's both folded into a collapsed header's
    // count AND rendered again as its own escaped header — exactly the accepted-duplicate shape
    // below — so it can't be used on these fixtures. This instead resolves *which ids* a
    // collapsed header hides (from the tab's own analysis.stackTraceGroups, a fresh
    // computeSeqGroups() over tab.filter.sequences — SeqGroup isn't cached on LogAnalysis, unlike
    // stack groups — and the manual block's own resolved range) and asserts only coverage
    // (rendered ∪ hidden == every id), never uniqueness — so the accepted duplicates still pass.
    private fun assertNoEntryDisappears(tab: LogTab, items: List<LogItem>) {
        val stackGroupsByGid = tab.analysis.stackTraceGroups.associateBy { it.gid }
        val seqHiddenIdsByGid = mutableMapOf<String, Set<Int>>()
        computeSeqGroups(tab.logData, tab.filter.sequences).forEach { sg ->
            seqHiddenIdsByGid[sg.gid] = sg.plain.toSet() + sg.nested.flatMap { ng -> listOf(ng.rid) + ng.ch }
            sg.nested.forEach { ng -> seqHiddenIdsByGid[ng.gid] = ng.ch.toSet() }
        }
        val manualBlocksById = tab.manualBlocks.filter { it.enabled }.associateBy { it.id }
        val idsById = tab.logData.map { it.id }

        fun manualHiddenIds(block: ManualCollapseBlock): Set<Int> {
            val anchorIdx = idsById.indexOf(block.anchorId)
            val range = when (block.direction) {
                ManualCollapseDirection.TO_START -> 0..anchorIdx
                ManualCollapseDirection.TO_END -> anchorIdx..idsById.lastIndex
                ManualCollapseDirection.RANGE -> {
                    val endIdx = idsById.indexOf(block.endId)
                    minOf(anchorIdx, endIdx)..maxOf(anchorIdx, endIdx)
                }
            }
            // The header itself already displays/renders the anchor entry, same -1 the count-based
            // helper above applies for ManualHeader.
            return range.map { idsById[it] }.toSet() - block.anchorId
        }

        val renderedIds = mutableSetOf<Int>()
        val hiddenIds = mutableSetOf<Int>()
        items.forEach { item ->
            when (item) {
                is LogItem.Row -> renderedIds += item.entry.id
                is LogItem.SeqHeader -> {
                    renderedIds += item.entry.id
                    if (!item.expanded) hiddenIds += seqHiddenIdsByGid[item.gid].orEmpty()
                }
                is LogItem.StackTraceHeader -> {
                    renderedIds += item.entry.id
                    if (!item.expanded) hiddenIds += stackGroupsByGid[item.gid]?.memberIds.orEmpty()
                }
                is LogItem.ManualHeader -> {
                    renderedIds += item.entry.id
                    if (!item.expanded) hiddenIds += manualBlocksById[item.gid]?.let(::manualHiddenIds).orEmpty()
                }
            }
        }
        assertEquals(
            tab.logData.map { it.id }.toSet(),
            renderedIds + hiddenIds,
            "some entries are neither rendered nor accounted for by a collapsed header that could reveal them",
        )
    }

    @Test
    fun collapsedManualBlockCuttingAStackDumpStillRendersTheDumpTail() {
        // RANGE 1..4 collapses over the crash trigger (id 2) and two of its members (ids 3, 4),
        // ending in the MIDDLE of the dump — the group's StackTraceHeader is jumped over entirely
        // (never reached, never emitted), so its remaining members (5, 6, 7) must fall through and
        // render as plain rows rather than vanish with no header left to reveal them.
        val logs = crashDumpLogs()
        val block = ManualCollapseBlock("m1", 1, ManualCollapseDirection.RANGE, endId = 4)
        val tab = mkTab("log", "test.log", logs).copy(manualBlocks = listOf(block))

        val items = computeItems(tab, applyFilter = true)

        val header = items.filterIsInstance<LogItem.ManualHeader>().single()
        assertEquals(1, header.entry.id) // RANGE 1..4's anchor is id 1 itself — no leading plain row
        assertFalse(header.expanded)
        assertEquals(4, header.count)
        assertTrue(items.filterIsInstance<LogItem.StackTraceHeader>().isEmpty())
        assertEquals(listOf(5, 6, 7, 8, 9, 10), items.filterIsInstance<LogItem.Row>().map { it.entry.id })
        assertNoEntryDisappears(tab, items)
    }

    @Test
    fun collapsedManualBlockStartingOnTheCrashHeaderStillRendersTheDumpTail() {
        // Same defect, the block starting exactly ON the crash trigger this time (RANGE 2..4):
        // the ManualC branch pre-empts the stack-header branch at that shared index, so again no
        // StackTraceHeader is ever emitted and 5, 6, 7 must still render.
        val logs = crashDumpLogs()
        val block = ManualCollapseBlock("m1", 2, ManualCollapseDirection.RANGE, endId = 4)
        val tab = mkTab("log", "test.log", logs).copy(manualBlocks = listOf(block))

        val items = computeItems(tab, applyFilter = true)

        assertTrue(items.filterIsInstance<LogItem.StackTraceHeader>().isEmpty())
        assertEquals(listOf(1, 5, 6, 7, 8, 9, 10), items.filterIsInstance<LogItem.Row>().map { it.entry.id })
        assertNoEntryDisappears(tab, items)
    }

    @Test
    fun expandedSequenceStartingOnACrashHeaderStillRendersTheDumpRows() {
        // The sequence's own start pattern matches the crash trigger line itself, so the
        // ChildRef.SeqC branch pre-empts the stack-header branch at that index — no
        // StackTraceHeader is ever emitted for this group. Expanded, the dump's rows must still
        // render as this sequence's own (untinted-red) plain children, not vanish.
        val logs = crashDumpLogs()
        val seq = SequenceDef(
            "crashSeq", "FATAL EXCEPTION", priority = 1, color = Color.Red, tag = "AndroidRuntime",
            endMatchText = "after the dump", endTag = "App",
        )
        val tab = mkTab("log", "test.log", logs)
            .copy(filter = Filter(sequences = listOf(seq)), expanded = setOf("sg_crashSeq_2"))

        val items = computeItems(tab, applyFilter = true)

        assertTrue(items.filterIsInstance<LogItem.StackTraceHeader>().isEmpty())
        val seqHeader = items.filterIsInstance<LogItem.SeqHeader>().single()
        assertEquals(2, seqHeader.entry.id)
        assertTrue(seqHeader.expanded)
        val rows = items.filterIsInstance<LogItem.Row>().map { it.entry.id }
        assertEquals(listOf(3, 4, 5, 6, 7), rows.filter { it in 3..7 })
        assertNoEntryDisappears(tab, items)
    }

    @Test
    fun collapsedSequenceStartingOnACrashHeaderAccountsForDumpRowsInItsCount() {
        // Collapsed counterpart of the test above: the escaped-header defect only bites the
        // EXPANDED case (a header that's never emitted can't be "expanded" — see the plan). While
        // collapsed, the sequence's own swallow walk hides its interior the ordinary way and its
        // count already accounts for every dump row plus the end-of-sequence line.
        val logs = crashDumpLogs()
        val seq = SequenceDef(
            "crashSeq", "FATAL EXCEPTION", priority = 1, color = Color.Red, tag = "AndroidRuntime",
            endMatchText = "after the dump", endTag = "App",
        )
        val tab = mkTab("log", "test.log", logs).copy(filter = Filter(sequences = listOf(seq)))

        val items = computeItems(tab, applyFilter = true)

        assertTrue(items.filterIsInstance<LogItem.StackTraceHeader>().isEmpty())
        val seqHeader = items.filterIsInstance<LogItem.SeqHeader>().single()
        assertFalse(seqHeader.expanded)
        assertEquals(6, seqHeader.count) // ids 3-7 (the dump) plus id 8, the end-of-sequence line
        assertEquals(listOf(1, 9, 10), items.filterIsInstance<LogItem.Row>().map { it.entry.id })
        assertNoEntryDisappears(tab, items)
    }

    // ── Accepted duplicates (NOT this fix's target — see the plan's Non-goals) ────────────────
    //
    // Both cases below are pre-existing, deliberate overlaps: a container whose header is emitted
    // at the SAME index as a stack-trace trigger, both expanded, renders that one entry twice —
    // once as the container's own header/row, once as the stack trace's header. De-duplicating
    // these would require knowing at emission time whether a later container header will really
    // render, which the ascending index walk can't answer yet. Pinned here so a future change to
    // this area has to decide about them consciously instead of accidentally fixing (or worsening)
    // them as a side effect.

    @Test
    fun knownDuplicateExpandedManualBlockAnchoredOnACrashHeaderRendersItTwice() {
        val logs = crashDumpLogs()
        val block = ManualCollapseBlock("m1", 2, ManualCollapseDirection.TO_END, color = Color.Red)
        val tab = mkTab("log", "test.log", logs)
            .copy(manualBlocks = listOf(block), expanded = setOf(block.id, "st_2"))

        val items = computeItems(tab, applyFilter = true)

        // id 2 renders BOTH as the ManualHeader's own entry AND as the nested StackTraceHeader's
        // entry (the manual block's inner recursion re-evaluates the stack-header branch, and
        // renderRange's Row-only filter for the anchor doesn't strip a header).
        val manualHeader = items.filterIsInstance<LogItem.ManualHeader>().single()
        assertEquals(2, manualHeader.entry.id)
        val stackHeader = items.filterIsInstance<LogItem.StackTraceHeader>().single()
        assertEquals(2, stackHeader.entry.id)
    }

    @Test
    fun knownDuplicateSequenceStartingOnAStackMemberRendersItTwice() {
        val logs = crashDumpLogs()
        // Starts on id 5, a MEMBER of the crash dump (not its trigger) — a different overlap shape
        // from the tests above, where the sequence and the stack trigger share an index.
        val seq = SequenceDef("resumeSeq", "onResume", priority = 1, color = Color.Blue, tag = "AndroidRuntime")
        val tab = mkTab("log", "test.log", logs)
            .copy(filter = Filter(sequences = listOf(seq)), expanded = setOf("st_2", "sg_resumeSeq_5"))

        val items = computeItems(tab, applyFilter = true)

        // id 5 renders BOTH as one of the expanded StackTraceHeader's own member rows (emitted
        // directly from stg.memberIds, independent of the main index walk) AND, when the main walk
        // itself later reaches index 5, as its own SeqHeader.
        val memberRow = items.filterIsInstance<LogItem.Row>().single { it.entry.id == 5 }
        assertEquals(DANGER_RED, memberRow.groupColor)
        val seqHeader = items.filterIsInstance<LogItem.SeqHeader>().single()
        assertEquals(5, seqHeader.entry.id)
    }

    @Test
    fun stackTraceUnderACollapsingManualBlockNeverLosesAnEntryAcrossExpansionCombinations() {
        // The bitset that marks "this member's header was emitted" must be shared across every
        // recursion level (renderRange's walk is globally monotone in index — see the comment in
        // Filter.kt), because an expanded ManualC's inner recursion can emit a stack header whose
        // members extend past the block's own declared end: those members are rendered right there
        // (from stg.memberIds, not from the inner recursion's own bounded index walk) and must
        // still be correctly skipped once the OUTER walk reaches their own index afterward — a
        // per-level-local bitset would double-render them instead.
        val logs = crashDumpLogs()
        // RANGE 1..4: covers the crash trigger (id 2) and two of its members (3, 4); members 5-7
        // fall outside the block's own declared range but inside the stack group.
        val block = ManualCollapseBlock("m1", 1, ManualCollapseDirection.RANGE, endId = 4)
        val allCombos = listOf(
            emptySet(), setOf(block.id), setOf("st_2"), setOf(block.id, "st_2"),
        )
        for (expanded in allCombos) {
            val tab = mkTab("log", "test.log", logs).copy(manualBlocks = listOf(block), expanded = expanded)
            val items = computeItems(tab, applyFilter = true)
            assertNoEntryDisappears(tab, items)
            // The other half of the shared-bitset claim, which assertNoEntryDisappears (coverage
            // only, never uniqueness — see its doc) can't make: no id renders twice. This fixture
            // has none of the accepted-duplicate overlaps pinned above, so every rendered id here
            // must be distinct — which is exactly what a per-level-local bitset would break.
            val renderedIds = items.map { item ->
                when (item) {
                    is LogItem.Row -> item.entry.id
                    is LogItem.SeqHeader -> item.entry.id
                    is LogItem.StackTraceHeader -> item.entry.id
                    is LogItem.ManualHeader -> item.entry.id
                }
            }
            assertEquals(renderedIds.size, renderedIds.toSet().size, "an entry id was rendered more than once for expanded=$expanded")
        }
    }

    // ── Crossing top-level sequences (data-loss regression) ──────────────────────────────────
    //
    // assignParents (SeqComputer.kt) only nests a candidate that's FULLY CONTAINED in another; two
    // sequences whose ranges partially overlap ("cross" — neither contains the other) both survive
    // as independent top-level SeqGroups and used to both land in renderRange's topChildren with
    // overlapping [start, end) ranges. That silently dropped every row from the swallowed one's own
    // start through its own end: no header, no row, not even folded into a count — gone.
    //
    // Shared fixture for the tests below: A = ids [1..5] (endMatchText "A end" on id 5), B = ids
    // [3..6] (endMatchText "B end" on id 6). B's start (id 3) falls inside A's still-open range and
    // B's end (id 6) falls past A's own end — a genuine crossing, not a containment, in either
    // direction. A starts first, so A is expected to host B's full extent, nested one level in.

    private fun crossingSequenceLogs() = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "SeqA", "A start"),
        LogEntry(2, "10:00:00.100", LogLevel.I, "Mid", "between"),
        LogEntry(3, "10:00:00.200", LogLevel.I, "SeqB", "B start"),
        LogEntry(4, "10:00:00.300", LogLevel.I, "Mid", "between"),
        LogEntry(5, "10:00:00.400", LogLevel.I, "SeqA", "A end"),
        LogEntry(6, "10:00:00.500", LogLevel.I, "SeqB", "B end"),
    )

    private fun crossingSequenceDefs(): List<SequenceDef> {
        val a = SequenceDef("seqA", "A start", priority = 1, color = Color.Red, tag = "SeqA", endMatchText = "A end", endTag = "SeqA")
        val b = SequenceDef("seqB", "B start", priority = 2, color = Color.Blue, tag = "SeqB", endMatchText = "B end", endTag = "SeqB")
        return listOf(a, b)
    }

    // Every entry in `data` appears exactly once in the output: as a Row, as a header's own row,
    // or accounted for inside a COLLAPSED header's `count`. SeqHeader/StackTraceHeader.count is the
    // number of children BELOW the header (the header's own row is separate — see e.g.
    // startEndSequenceCanUseDifferentTagsAndIncludesEndLine); ManualHeader.count is the block's
    // whole declared range, which DOES include its own anchor row (see
    // manualCollapseToEndHidesRowsAfterAnchor), hence the -1 there. This catches a dropped row (the
    // sum comes up short) and a duplicated one (rendered ids aren't distinct) even in shapes the
    // targeted assertions elsewhere in this file don't happen to look at.
    private fun assertEveryEntryAccountedForExactlyOnce(totalEntries: Int, items: List<LogItem>) {
        val renderedIds = mutableListOf<Int>()
        var hidden = 0
        items.forEach { item ->
            when (item) {
                is LogItem.Row -> renderedIds += item.entry.id
                is LogItem.SeqHeader -> {
                    renderedIds += item.entry.id
                    if (!item.expanded) hidden += item.count
                }
                is LogItem.StackTraceHeader -> {
                    renderedIds += item.entry.id
                    if (!item.expanded) hidden += item.count
                }
                is LogItem.ManualHeader -> {
                    renderedIds += item.entry.id
                    if (!item.expanded) hidden += item.count - 1
                }
            }
        }
        assertEquals(renderedIds.size, renderedIds.toSet().size, "an entry id was rendered more than once")
        assertEquals(
            totalEntries,
            renderedIds.size + hidden,
            "some entries are neither rendered nor accounted for in a collapsed group's count",
        )
    }

    private fun crossingSequenceTab(expanded: Set<String>): LogTab {
        val logs = crossingSequenceLogs()
        return LogTab(
            id = "log",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(sequences = crossingSequenceDefs()),
            expanded = expanded,
        )
    }

    // ── Task 4: crossing-different-thread detection (CrossingThreadHint) ──────────────────────
    //
    // Reuses the SAME crossing pair computeItems' own hosting-resolution pass already finds above
    // (crossingSequenceLogs/crossingSequenceDefs: A hosts B, a genuine crossing) — these tests only
    // add tid to the two roots to prove the hint is correctly gated on the roots' own tids, not a
    // second detection pass.

    @Test
    fun crossingSequencesOnDifferentThreadsProduceACrossingThreadHint() {
        val logs = crossingSequenceLogs().map {
            when (it.id) {
                1 -> it.copy(tid = 100) // A's own start
                3 -> it.copy(tid = 200) // B's own start
                else -> it
            }
        }
        val tab = LogTab(
            id = "log", filename = "test.log", logData = logs, rmap = logs.associateBy { it.id },
            filter = Filter(sequences = crossingSequenceDefs()),
        )

        computeItems(tab, applyFilter = true) // warms the cache cachedCrossingThreadHintsFor peeks
        val hints = cachedCrossingThreadHintsFor(tab, applyFilter = true)

        assertEquals(listOf(CrossingThreadHint("seqA", 100, "seqB", 200)), hints)
    }

    @Test
    fun crossingSequencesOnTheSameThreadProduceNoHint() {
        // crossingSequenceTab's fixture leaves every entry at the LogEntry default tid (0) — A and
        // B still cross (this exact fixture is what crossingTopLevelSequences* above exercises),
        // but scoping both to the one shared tid they're already both on wouldn't separate them,
        // so no hint should be offered.
        val tab = crossingSequenceTab(expanded = emptySet())

        computeItems(tab, applyFilter = true)
        val hints = cachedCrossingThreadHintsFor(tab, applyFilter = true)

        assertEquals(emptyList(), hints)
    }

    @Test
    fun crossingTopLevelSequencesAreAccountedForExactlyOnceInAllFourExpansionCombinations() {
        val allCombos = listOf(
            emptySet(), setOf("sg_seqA_1"), setOf("sg_seqB_3"), setOf("sg_seqA_1", "sg_seqB_3"),
        )
        for (expanded in allCombos) {
            val tab = crossingSequenceTab(expanded)
            val items = computeItems(tab, applyFilter = true)
            assertEveryEntryAccountedForExactlyOnce(tab.logData.size, items)
        }
    }

    @Test
    fun crossingTopLevelSequencesBothExpandedNestTheLaterStartingOneUnderTheEarlier() {
        val tab = crossingSequenceTab(setOf("sg_seqA_1", "sg_seqB_3"))

        val items = computeItems(tab, applyFilter = true)

        val headerA = items.filterIsInstance<LogItem.SeqHeader>().single { it.entry.id == 1 }
        val headerB = items.filterIsInstance<LogItem.SeqHeader>().single { it.entry.id == 3 }
        assertEquals(0, headerA.indent)
        assertEquals(1, headerB.indent) // nested one level inside A, past A's own declared end
        assertTrue(items.indexOf(headerB) > items.indexOf(headerA))
        // Own reported counts are unaffected by hosting (matches the sequence-vs-manual precedent):
        // A's own plain children (ids 2, 3, 4, 5 — SeqComputer has no notion of the crossing).
        assertEquals(4, headerA.count)
        assertEquals(3, headerB.count) // B's own plain children: ids 4, 5, 6
        val rows = items.filterIsInstance<LogItem.Row>().map { it.entry.id }
        assertEquals(listOf(2, 4, 5, 6), rows) // ids 1 and 3 render as headers, not rows
        assertEveryEntryAccountedForExactlyOnce(tab.logData.size, items)
    }

    @Test
    fun crossingTopLevelSequencesHostExpandedGuestCollapsedHidesOnlyTheGuestsOwnChildren() {
        val tab = crossingSequenceTab(setOf("sg_seqA_1"))

        val items = computeItems(tab, applyFilter = true)

        val headerB = items.filterIsInstance<LogItem.SeqHeader>().single { it.entry.id == 3 }
        assertFalse(headerB.expanded)
        assertEquals(3, headerB.count) // ids 4, 5, 6 — still B's own true count, not truncated
        val rows = items.filterIsInstance<LogItem.Row>().map { it.entry.id }
        assertEquals(listOf(2), rows) // id 3 is B's header; ids 4-6 are folded into headerB's count
        assertEveryEntryAccountedForExactlyOnce(tab.logData.size, items)
    }

    @Test
    fun crossingTopLevelSequencesCollapsedHostHidesItsOwnSpanButTheGuestsTailStillRenders() {
        // A collapsed OUTER header hides its whole subtree unconditionally (matching
        // seqGroupExpansionIds' documented behavior for containment) regardless of B's own expanded
        // flag — B's own header never gets a chance to show. But B's tail past A's own declared end
        // (id 6) is not part of what A's collapse declares as hidden (A's count is unaffected by
        // hosting), so it must still render rather than silently vanish.
        for (expanded in listOf(emptySet(), setOf("sg_seqB_3"))) {
            val tab = crossingSequenceTab(expanded)

            val items = computeItems(tab, applyFilter = true)

            val headerA = items.filterIsInstance<LogItem.SeqHeader>().single()
            assertEquals(1, headerA.entry.id)
            assertFalse(headerA.expanded)
            assertEquals(4, headerA.count) // ids 2, 3, 4, 5 — A's own plain children, B's rid included
            val rows = items.filterIsInstance<LogItem.Row>().map { it.entry.id }
            assertEquals(listOf(6), rows) // B's tail beyond A's own end, with nowhere left to hide
            assertEveryEntryAccountedForExactlyOnce(tab.logData.size, items)
        }
    }

    @Test
    fun collapsedManualBlockHostingACrossingSequenceStillRendersTheSequencesTail() {
        // Mirror of the sequence-hosts-manual crossing tests above, but for the direction the bug
        // report specifically called out: a MANUAL block starts first and hosts a sequence whose own
        // true end (no end pattern configured, so it runs to end-of-log) extends past the manual
        // block's declared end. Manual is left COLLAPSED — before the fix, ManualC.end at Filter.kt
        // was stretched to the sequence's endExclusive but the collapsed path jumped straight to
        // declaredEnd, so :850's fallthrough silently swallowed every index in between.
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "before"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Seq", "flow start"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "Seq", "flow child"),
            LogEntry(4, "10:00:00.300", LogLevel.I, "App", "after"),
        )
        // Manual range is just [id1, id2] — the sequence (no end pattern) runs from id2 to
        // end-of-log (id4), well past the manual block's own declared end.
        val block = ManualCollapseBlock("m1", 1, ManualCollapseDirection.RANGE, color = Color.Red, endId = 2)
        val sequence = SequenceDef("flow", "flow start", priority = 1, color = Color.Blue, tag = "Seq")
        val tab = LogTab(
            id = "log",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(sequences = listOf(sequence)),
            // manual block left collapsed
            expanded = emptySet(),
            manualBlocks = listOf(block),
        )

        val items = computeItems(tab, applyFilter = true)

        val manualHeader = items.filterIsInstance<LogItem.ManualHeader>().single()
        assertFalse(manualHeader.expanded)
        assertEquals(1, manualHeader.entry.id)
        assertEquals(2, manualHeader.count) // its own declared range only: ids 1 and 2
        assertTrue(items.filterIsInstance<LogItem.SeqHeader>().isEmpty()) // the sequence's own header sits inside the hidden manual range
        val rows = items.filterIsInstance<LogItem.Row>().map { it.entry.id }
        assertEquals(listOf(3, 4), rows) // the sequence's tail, with no header left to hide it under
        assertEveryEntryAccountedForExactlyOnce(logs.size, items)
    }

    // ── Chained (3+) crossing sequences (data-loss regression, chained) ──────────────────────
    //
    // The two-way fix above folds a crossing pair by having whichever starts first host the
    // other's full extent. That alone is not enough for a CHAIN of three or more mutually
    // crossing sequences (A crosses B, B crosses C, ... — the actually-reported scenario: three
    // parallel threads each recording a sequence, so each thread's sequence overlaps its
    // neighbor's but not necessarily the one after that). A flat "fold everyone under the first
    // starter" resolution stranded the tail of a chain three deep: a COLLAPSED middle host doesn't
    // jump past its own declared end (only an EXPANDED one does), it relies on its *parent's*
    // recursion `idx` walk to keep going — and that parent's recursion used to be bounded by the
    // parent's own declared end, not the full chain's effective end, so the walk ran out of room
    // one level too soon. Fixed by (a) resolving the hosting as a genuine CHAIN — each host claims
    // only its own immediate next unclaimed crossing partner, so B is nested under A and ALSO
    // hosts C, rather than both B and C being flattened as siblings under A — and (b) widening the
    // `hi` passed to a SeqC's own recursion in renderRange to its full recursive seqEffectiveEnd,
    // not just its own declared end, so a collapsed link's swallow walk has room to reach whatever
    // falls through past the end of the whole chain it anchors.
    //
    // Shared 3-way fixture: A = ids [1..5] ("A end" on id 5), B = ids [3..8] ("B end" on id 8),
    // C = ids [6..10] ("C end" on id 10). A crosses B (B starts inside A, ends past it); B crosses
    // C (C starts inside B, ends past it); A and C never directly overlap at all — this is exactly
    // the shape a flat single-host resolution cannot represent correctly.

    private fun chainedThreeWaySequenceLogs() = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "SeqA", "A start"),
        LogEntry(2, "10:00:00.100", LogLevel.I, "Mid", "between"),
        LogEntry(3, "10:00:00.200", LogLevel.I, "SeqB", "B start"),
        LogEntry(4, "10:00:00.300", LogLevel.I, "Mid", "between"),
        LogEntry(5, "10:00:00.400", LogLevel.I, "SeqA", "A end"),
        LogEntry(6, "10:00:00.500", LogLevel.I, "SeqC", "C start"),
        LogEntry(7, "10:00:00.600", LogLevel.I, "Mid", "between"),
        LogEntry(8, "10:00:00.700", LogLevel.I, "SeqB", "B end"),
        LogEntry(9, "10:00:00.800", LogLevel.I, "Mid", "between"),
        LogEntry(10, "10:00:00.900", LogLevel.I, "SeqC", "C end"),
    )

    private fun chainedThreeWaySequenceDefs(): List<SequenceDef> = listOf(
        SequenceDef("seqA", "A start", priority = 1, color = Color.Red, tag = "SeqA", endMatchText = "A end", endTag = "SeqA"),
        SequenceDef("seqB", "B start", priority = 2, color = Color.Blue, tag = "SeqB", endMatchText = "B end", endTag = "SeqB"),
        SequenceDef("seqC", "C start", priority = 3, color = Color.Green, tag = "SeqC", endMatchText = "C end", endTag = "SeqC"),
    )

    private fun chainedThreeWayTab(expanded: Set<String>): LogTab {
        val logs = chainedThreeWaySequenceLogs()
        return LogTab(
            id = "log",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(sequences = chainedThreeWaySequenceDefs()),
            expanded = expanded,
        )
    }

    @Test
    fun chainedThreeWaySequencesAreAccountedForExactlyOnceAcrossExpansionCombinations() {
        val allCombos = listOf(
            emptySet(),
            setOf("sg_seqA_1"),
            setOf("sg_seqA_1", "sg_seqB_3"),
            setOf("sg_seqA_1", "sg_seqB_3", "sg_seqC_6"),
            // B and C open but their host A never expanded
            setOf("sg_seqB_3", "sg_seqC_6"),
            // A and C open, middle link B left collapsed
            setOf("sg_seqA_1", "sg_seqC_6"),
        )
        for (expanded in allCombos) {
            val tab = chainedThreeWayTab(expanded)
            val items = computeItems(tab, applyFilter = true)
            assertEveryEntryAccountedForExactlyOnce(tab.logData.size, items)
        }
    }

    @Test
    fun chainedThreeWaySequencesAllExpandedNestEachOneLevelDeeperThanTheLast() {
        val tab = chainedThreeWayTab(setOf("sg_seqA_1", "sg_seqB_3", "sg_seqC_6"))

        val items = computeItems(tab, applyFilter = true)

        val headerA = items.filterIsInstance<LogItem.SeqHeader>().single { it.entry.id == 1 }
        val headerB = items.filterIsInstance<LogItem.SeqHeader>().single { it.entry.id == 3 }
        val headerC = items.filterIsInstance<LogItem.SeqHeader>().single { it.entry.id == 6 }
        assertEquals(0, headerA.indent)
        assertEquals(1, headerB.indent) // nested under A
        assertEquals(2, headerC.indent) // nested under B, which is nested under A
        assertTrue(items.indexOf(headerA) < items.indexOf(headerB))
        assertTrue(items.indexOf(headerB) < items.indexOf(headerC))
        // Own reported counts are unaffected by hosting (matches the two-way precedent): each
        // header's count is exactly its own SeqComputer-declared plain children.
        assertEquals(4, headerA.count) // ids 2, 3, 4, 5
        assertEquals(5, headerB.count) // ids 4, 5, 6, 7, 8
        assertEquals(4, headerC.count) // ids 7, 8, 9, 10
        val rows = items.filterIsInstance<LogItem.Row>().map { it.entry.id }
        assertEquals(listOf(2, 4, 5, 7, 8, 9, 10), rows) // ids 1, 3, 6 render as headers, not rows
        assertEveryEntryAccountedForExactlyOnce(tab.logData.size, items)
    }

    @Test
    fun chainedThreeWaySequencesAllCollapsedHidesTheWholeChainUnderTheOutermostHeader() {
        val tab = chainedThreeWayTab(emptySet())

        val items = computeItems(tab, applyFilter = true)

        val headerA = items.filterIsInstance<LogItem.SeqHeader>().single()
        assertEquals(1, headerA.entry.id)
        assertFalse(headerA.expanded)
        assertEquals(4, headerA.count) // ids 2, 3, 4, 5 — A's own declared span only
        // Everything past A's own declared end (id 6 onward) has no header left to hide under —
        // it falls through and renders as plain rows, same as the two-way precedent.
        val rows = items.filterIsInstance<LogItem.Row>().map { it.entry.id }
        assertEquals(listOf(6, 7, 8, 9, 10), rows)
        assertEveryEntryAccountedForExactlyOnce(tab.logData.size, items)
    }

    @Test
    fun chainedThreeWaySequencesOutermostExpandedMiddleCollapsedHidesTheInnermostEntirely() {
        // A expanded, B collapsed: this is the exact shape that used to drop ids 9 and 10 — B's
        // collapsed swallow walk runs inside A's OWN recursion, which is now widened to
        // seqEffectiveEnd(A) so it has room to reach past B's declared end into C's tail. C's own
        // header (id 6) has nowhere else to go: it sits inside B's own declared span, so it's
        // silently absorbed into B's count like any other entry there — C's own `expanded` flag is
        // irrelevant, matching the pre-existing "collapsed OUTER hides its whole subtree
        // unconditionally" rule (seqGroupExpansionIds' own doc).
        for (expanded in listOf(setOf("sg_seqA_1"), setOf("sg_seqA_1", "sg_seqC_6"))) {
            val tab = chainedThreeWayTab(expanded)

            val items = computeItems(tab, applyFilter = true)

            val headerA = items.filterIsInstance<LogItem.SeqHeader>().single { it.entry.id == 1 }
            val headerB = items.filterIsInstance<LogItem.SeqHeader>().single { it.entry.id == 3 }
            assertTrue(headerA.expanded)
            assertFalse(headerB.expanded)
            assertEquals(4, headerA.count)
            assertEquals(5, headerB.count) // ids 4, 5, 6, 7, 8 — includes C's own header id, id 6
            assertTrue(items.none { it is LogItem.SeqHeader && it.entry.id == 6 }) // C's header never shows
            val rows = items.filterIsInstance<LogItem.Row>().map { it.entry.id }
            assertEquals(listOf(2, 9, 10), rows) // ids 9, 10: C's tail past B's own declared end
            assertEveryEntryAccountedForExactlyOnce(tab.logData.size, items)
        }
    }

    // ── 4-way chain — generalization check ────────────────────────────────────────────────────
    //
    // A crosses B crosses C crosses D. If the chained-hosting fix is genuinely general (not just
    // patched for depth 3), this must be lossless too; if it only special-cased three links, this
    // is exactly what would catch it.

    // Sequential fixture entry ids (1..13) — literal by design, same as every other fixture in
    // this file; 11 alone falls outside config/detekt.yml's MagicNumber ignoreNumbers range.
    @Suppress("MagicNumber")
    private fun chainedFourWaySequenceLogs() = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "SeqA", "A start"),
        LogEntry(2, "10:00:00.100", LogLevel.I, "Mid", "between"),
        LogEntry(3, "10:00:00.200", LogLevel.I, "SeqB", "B start"),
        LogEntry(4, "10:00:00.300", LogLevel.I, "Mid", "between"),
        LogEntry(5, "10:00:00.400", LogLevel.I, "SeqA", "A end"),
        LogEntry(6, "10:00:00.500", LogLevel.I, "SeqC", "C start"),
        LogEntry(7, "10:00:00.600", LogLevel.I, "Mid", "between"),
        LogEntry(8, "10:00:00.700", LogLevel.I, "SeqB", "B end"),
        LogEntry(9, "10:00:00.800", LogLevel.I, "SeqD", "D start"),
        LogEntry(10, "10:00:00.900", LogLevel.I, "Mid", "between"),
        LogEntry(11, "10:00:01.000", LogLevel.I, "SeqC", "C end"),
        LogEntry(12, "10:00:01.100", LogLevel.I, "Mid", "between"),
        LogEntry(13, "10:00:01.200", LogLevel.I, "SeqD", "D end"),
    )

    private fun chainedFourWaySequenceDefs(): List<SequenceDef> = listOf(
        SequenceDef("seqA", "A start", priority = 1, color = Color.Red, tag = "SeqA", endMatchText = "A end", endTag = "SeqA"),
        SequenceDef("seqB", "B start", priority = 2, color = Color.Blue, tag = "SeqB", endMatchText = "B end", endTag = "SeqB"),
        SequenceDef("seqC", "C start", priority = 3, color = Color.Green, tag = "SeqC", endMatchText = "C end", endTag = "SeqC"),
        SequenceDef("seqD", "D start", priority = 4, color = Color.Magenta, tag = "SeqD", endMatchText = "D end", endTag = "SeqD"),
    )

    private fun chainedFourWayTab(expanded: Set<String>): LogTab {
        val logs = chainedFourWaySequenceLogs()
        return LogTab(
            id = "log",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(sequences = chainedFourWaySequenceDefs()),
            expanded = expanded,
        )
    }

    @Test
    fun chainedFourWaySequencesAreAccountedForExactlyOnceAcrossExpansionCombinations() {
        val a = "sg_seqA_1"
        val b = "sg_seqB_3"
        val c = "sg_seqC_6"
        val d = "sg_seqD_9"
        val allCombos = listOf(
            emptySet(),
            setOf(a, b, c, d),
            setOf(a),
            setOf(a, b),
            setOf(a, b, c),
            // gaps in the chain: middle links left collapsed
            setOf(a, c),
            // host A never opened at all
            setOf(b, d),
        )
        for (expanded in allCombos) {
            val tab = chainedFourWayTab(expanded)
            val items = computeItems(tab, applyFilter = true)
            assertEveryEntryAccountedForExactlyOnce(tab.logData.size, items)
        }
    }

    @Test
    fun chainedFourWaySequencesAllExpandedNestFourLevelsDeep() {
        val tab = chainedFourWayTab(setOf("sg_seqA_1", "sg_seqB_3", "sg_seqC_6", "sg_seqD_9"))

        val items = computeItems(tab, applyFilter = true)

        val headerA = items.filterIsInstance<LogItem.SeqHeader>().single { it.entry.id == 1 }
        val headerB = items.filterIsInstance<LogItem.SeqHeader>().single { it.entry.id == 3 }
        val headerC = items.filterIsInstance<LogItem.SeqHeader>().single { it.entry.id == 6 }
        val headerD = items.filterIsInstance<LogItem.SeqHeader>().single { it.entry.id == 9 }
        assertEquals(listOf(0, 1, 2, 3), listOf(headerA.indent, headerB.indent, headerC.indent, headerD.indent))
        assertEquals(
            listOf(headerA, headerB, headerC, headerD),
            listOf(headerA, headerB, headerC, headerD).sortedBy { items.indexOf(it) },
        )
        assertEveryEntryAccountedForExactlyOnce(tab.logData.size, items)
    }

    // ── W5: O(roots²) crossing resolution — cost fix, must not change behaviour ────────────────
    //
    // Generalizes chainedFourWaySequenceLogs to N mutually-crossing roots: root k's start falls
    // inside root (k-1)'s still-open range and root k's end falls past root (k-1)'s own end — the
    // same "open, mid, open, mid, close-the-earlier-one" interleaving as the 3-/4-way fixtures
    // above, just repeated. Event shape: open(0), mid, open(1), mid, close(0), then for each
    // further k: open(k), mid, close(k-1); finally mid, close(N-1).
    private fun manyCrossingChainFixture(n: Int): Pair<List<LogEntry>, List<SequenceDef>> {
        data class Ev(val kind: Char, val k: Int)
        val events = mutableListOf<Ev>()
        events += Ev('O', 0)
        events += Ev('M', -1)
        events += Ev('O', 1)
        events += Ev('M', -1)
        events += Ev('C', 0)
        for (k in 2 until n) {
            events += Ev('O', k)
            events += Ev('M', -1)
            events += Ev('C', k - 1)
        }
        events += Ev('M', -1)
        events += Ev('C', n - 1)
        val logs = events.mapIndexed { idx, ev ->
            val id = idx + 1
            when (ev.kind) {
                'O' -> LogEntry(id, "10:00:00.${id}00", LogLevel.I, "Seq${ev.k}", "S${ev.k} start")
                'C' -> LogEntry(id, "10:00:00.${id}00", LogLevel.I, "Seq${ev.k}", "S${ev.k} end")
                else -> LogEntry(id, "10:00:00.${id}00", LogLevel.I, "Mid", "between")
            }
        }
        val defs = (0 until n).map { k ->
            SequenceDef("seq$k", "S$k start", priority = k + 1, color = Color.Red, tag = "Seq$k", endMatchText = "S$k end", endTag = "Seq$k")
        }
        return logs to defs
    }

    @Test
    fun manyCrossingRootsResolveToTheSameChainRegardlessOfHowManyPriorRootsAreAlreadyClaimed() {
        // The claimed-guest `continue` used to run BEFORE the sorted-start `break`, so once a root
        // (`a`) started claiming guests, a long run of already-claimed roots after it was walked
        // all the way to the end of `roots` on every outer iteration instead of stopping at the
        // first root starting past a.endExclusive — O(roots²) instead of O(roots) total. This
        // fixture's long chain of mutually-crossing roots (each one claimed as a guest by its
        // predecessor, then itself walked as a host on its own turn) is exactly the shape that made
        // the old ordering expensive. Asserting the resolved chain here pins that reordering the two
        // checks changed only cost, not which root ends up hosting which guest.
        val n = 200
        val (logs, defs) = manyCrossingChainFixture(n)
        val startIdByK = (0 until n).associateWith { k -> logs.first { it.tag == "Seq$k" && it.msg == "S$k start" }.id }
        val allGids = (0 until n).map { k -> "sg_seq${k}_${startIdByK.getValue(k)}" }
        val tab = LogTab(
            id = "log", filename = "test.log", logData = logs, rmap = logs.associateBy { it.id },
            filter = Filter(sequences = defs), expanded = allGids.toSet(),
        )

        val items = computeItems(tab, applyFilter = true)

        val headers = allGids.map { gid -> items.filterIsInstance<LogItem.SeqHeader>().single { it.gid == gid } }
        // Each root, once resolved, is nested exactly one level deeper than the one before it — a
        // genuine chain, never a flat fan-out (see seqHostsSeqDirect's own doc in Filter.kt).
        assertEquals((0 until n).toList(), headers.map { it.indent })
        // And in document order: root k's header renders strictly before root k+1's.
        for (k in 0 until n - 1) assertTrue(items.indexOf(headers[k]) < items.indexOf(headers[k + 1]))
        assertEveryEntryAccountedForExactlyOnce(logs.size, items)

        // Fully collapsed too — the fold-hosting bookkeeping this loop feeds is unaffected by the
        // reorder either way.
        val collapsedItems = computeItems(tab.copy(expanded = emptySet()), applyFilter = true)
        assertEveryEntryAccountedForExactlyOnce(logs.size, collapsedItems)
    }
}
