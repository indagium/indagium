package com.openlog

import androidx.compose.ui.graphics.Color
import com.openlog.model.LogEntry
import com.openlog.model.LogItem
import com.openlog.model.LogLevel
import com.openlog.model.TidMapTarget
import com.openlog.ui.SEQ_COLORS
import com.openlog.utils.TidMapBranch
import com.openlog.utils.assignTidMapColors
import com.openlog.utils.computeTidMapBranches
import com.openlog.utils.computeTidMapColors
import com.openlog.utils.findTidMapSpan
import com.openlog.utils.tidMapHighlightedEntryRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TidMapTest {
    private fun row(id: Int, pid: Int, tid: Int): LogItem.Row =
        LogItem.Row(LogEntry(id, "10:00:00.000", LogLevel.I, "Tag", "msg $id", pid = pid, tid = tid), indent = 0)

    private fun entry(id: Int, pid: Int, tid: Int): LogEntry =
        LogEntry(id, "10:00:00.000", LogLevel.I, "Tag", "msg $id", pid = pid, tid = tid)

    // ── findTidMapSpan ─────────────────────────────────────────────────

    @Test
    fun spanIsNullWhenTheTargetHasNoOccurrenceInTheCurrentView() {
        val items = listOf(row(1, pid = 100, tid = 100), row(2, pid = 200, tid = 200))

        assertNull(findTidMapSpan(items, TidMapTarget(pid = 999, tid = 999)))
    }

    @Test
    fun spanIsASingleRowWhenTheTargetsPidOccursExactlyOnce() {
        val items = listOf(row(1, pid = 100, tid = 100), row(2, pid = 200, tid = 200), row(3, pid = 300, tid = 300))

        val span = findTidMapSpan(items, TidMapTarget(pid = 200, tid = 200))

        assertEquals(1..1, span)
    }

    @Test
    fun spanIsFirstToLastAcrossMultipleOccurrences() {
        val items = listOf(
            // target, index 0
            row(1, pid = 100, tid = 200),
            // unrelated
            row(2, pid = 999, tid = 999),
            // same pid, different tid — a match (pid-scoped, not exact-pair)
            row(3, pid = 100, tid = 999),
            // target again, index 3
            row(4, pid = 100, tid = 200),
            row(5, pid = 999, tid = 999),
        )

        val span = findTidMapSpan(items, TidMapTarget(pid = 100, tid = 200))

        assertEquals(0..3, span)
    }

    @Test
    fun spanMatchesByPidAloneIgnoringTid() {
        // The map is scoped to a PROCESS, not one specific thread — a row sharing only the pid (a
        // sibling thread) must still count as a match; a row sharing only the tid (a numeric
        // coincidence with a different pid) must not.
        val items = listOf(
            // pid matches, tid doesn't — DOES match (pid-only)
            row(1, pid = 100, tid = 999),
            // tid matches, pid doesn't — does NOT match
            row(2, pid = 999, tid = 200),
        )

        assertEquals(0..0, findTidMapSpan(items, TidMapTarget(pid = 100, tid = 200)))
    }

    // ── computeTidMapBranches ─────────────────────────────────────────

    @Test
    fun differentThreadsOfTheSamePidGetDifferentColorKeys() {
        // colorKey is the row's own TID, not its pid — sibling threads of the same process are
        // meant to be visually distinguishable from each other, not flattened into one color.
        val items = listOf(row(1, pid = 500, tid = 500), row(2, pid = 500, tid = 501), row(3, pid = 500, tid = 502))
        val branches = computeTidMapBranches(items, 0..2, TidMapTarget(pid = 500, tid = 500))

        assertEquals(listOf(500, 501, 502), branches.map { it.colorKey })
    }

    @Test
    fun theSameThreadRepeatedProducesTheSameColorKeyEachTime() {
        val items = listOf(row(1, pid = 100, tid = 100), row(2, pid = 100, tid = 105), row(3, pid = 100, tid = 100))
        val branches = computeTidMapBranches(items, 0..2, TidMapTarget(pid = 100, tid = 100))

        assertEquals(100, branches[0].colorKey)
        assertEquals(105, branches[1].colorKey)
        assertEquals(100, branches[2].colorKey)
    }

    @Test
    fun rowsOfADifferentPidWithinTheSpanGetNoBranchAtAll() {
        // The map is scoped to one process: a row from a DIFFERENT pid that happens to fall
        // POSITIONALLY between two occurrences of the target pid is part of the span's index range,
        // but must be skipped entirely — no branch, not even one colored differently.
        val items = listOf(
            row(1, pid = 100, tid = 100),
            row(2, pid = 777, tid = 778),
            row(3, pid = 100, tid = 100),
        )
        val branches = computeTidMapBranches(items, 0..2, TidMapTarget(pid = 100, tid = 100))

        assertEquals(2, branches.size)
        assertTrue(branches.none { it.entryId == 2 })
        assertTrue(branches.all { it.colorKey == 100 })
    }

    @Test
    fun branchIndentComesFromTheItemsOwnIndentAndManualHeaderHasZeroIndent() {
        val manual = LogItem.ManualHeader(
            LogEntry(9, "10:00:00.000", LogLevel.I, "Tag", "msg", pid = 100, tid = 100),
            gid = "m_1",
            direction = com.openlog.model.ManualCollapseDirection.RANGE,
            expanded = false,
            count = 1,
            color = Color.Red,
        )
        val items = listOf(row(1, pid = 100, tid = 100).let { it.copy(indent = 3) }, manual)
        val branches = computeTidMapBranches(items, 0..1, TidMapTarget(pid = 100, tid = 100))

        assertEquals(3, branches[0].indent)
        assertEquals(0, branches[1].indent)
    }

    // ── assignTidMapColors ────────────────────────────────────────────
    //
    // These tests construct TidMapBranch lists directly via the branch() helper below rather than
    // deriving them from computeTidMapBranches — assignTidMapColors is a general pure function over
    // colorKey, agnostic to what that key actually represents (pid, tid, or anything else).

    private fun branch(entryId: Int, colorKey: Int): TidMapBranch =
        TidMapBranch(entryId = entryId, indent = 0, colorKey = colorKey)

    @Test
    fun colorAssignmentGivesDistinctColorsToDistinctKeysInFirstAppearanceOrder() {
        val branches = listOf(branch(1, colorKey = 10), branch(2, colorKey = 20), branch(3, colorKey = 30))

        val colors = assignTidMapColors(branches)

        assertEquals(3, colors.size)
        assertEquals(setOf(colors[10], colors[20], colors[30]).size, 3) // all distinct
        assertEquals(SEQ_COLORS[0], colors[10])
        assertEquals(SEQ_COLORS[1], colors[20])
        assertEquals(SEQ_COLORS[2], colors[30])
    }

    @Test
    fun colorAssignmentUsesTheProvidedPaletteNotTheDefaultSeqColorsWhenOneIsGiven() {
        // AppState.toggleTidMap passes [theme.ac, theme.seq2, theme.seq1] instead of the global
        // SEQ_COLORS default — this is the seam that makes that possible: a caller-supplied palette
        // must actually be used, cycled the same way, not silently ignored in favor of SEQ_COLORS.
        val themePalette = listOf(Color.Cyan, Color.Yellow)
        val branches = listOf(branch(1, colorKey = 10), branch(2, colorKey = 20), branch(3, colorKey = 30))

        val colors = assignTidMapColors(branches, palette = themePalette)

        assertEquals(Color.Cyan, colors[10])
        assertEquals(Color.Yellow, colors[20])
        // Palette has only 2 entries — the 3rd distinct key must wrap back into it, not fall through
        // to SEQ_COLORS or crash.
        assertEquals(Color.Cyan, colors[30])
        assertTrue(colors.values.all { it == Color.Cyan || it == Color.Yellow })
    }

    @Test
    fun colorAssignmentPreservesColorsAlreadyInExisting() {
        val branches = listOf(branch(1, colorKey = 10), branch(2, colorKey = 20))
        val existing = mapOf(10 to Color.Magenta)

        val colors = assignTidMapColors(branches, existing)

        assertEquals(Color.Magenta, colors[10])
        // The new key must not collide with the preserved color.
        assertTrue(colors[20] != Color.Magenta)
    }

    @Test
    fun colorAssignmentWrapsThePaletteOnceExhaustedWithoutCrashing() {
        // One more distinct key than SEQ_COLORS has entries — every earlier color must already be
        // taken, so the last key necessarily reuses one (wrapping, not throwing/crashing).
        val branches = (1..SEQ_COLORS.size + 1).map { branch(it, colorKey = it * 10) }

        val colors = assignTidMapColors(branches)

        assertEquals(SEQ_COLORS.size + 1, colors.size)
        assertTrue(colors.values.all { it in SEQ_COLORS })
    }

    // ── computeTidMapColors: canonical, panel-independent color assignment ──────────────

    @Test
    fun colorsComputedFromLogDataAreEmptyWhenTheTargetHasNoOccurrence() {
        val logData = listOf(entry(1, pid = 100, tid = 100), entry(2, pid = 200, tid = 200))

        assertEquals(emptyMap(), computeTidMapColors(logData, TidMapTarget(pid = 999, tid = 999)))
    }

    @Test
    fun colorsComputedFromLogDataHaveOneEntryPerDistinctThreadOfTheTargetsPidOnly() {
        // pid 10 has two distinct threads (10 and 11); pids 20/30 are different processes and must
        // not appear in the result at all (see rowsOfADifferentPidWithinTheSpanGetNoBranchAtAll).
        val logData = listOf(
            entry(1, pid = 10, tid = 10),
            entry(2, pid = 20, tid = 21),
            entry(3, pid = 10, tid = 11),
            entry(4, pid = 30, tid = 31),
            entry(5, pid = 10, tid = 10),
        )
        val target = TidMapTarget(pid = 10, tid = 10)

        val colors = computeTidMapColors(logData, target)

        assertEquals(setOf(10, 11), colors.keys)
    }

    @Test
    fun theRightClickedThreadAlwaysGetsThePalettesFirstColorRegardlessOfScanOrder() {
        // Thread 999 (the target's OWN tid) appears AFTER thread 11 in first-appearance order — a
        // naive scan would assign palette[0] to 11 and palette[1] to 999. The right-clicked thread
        // must still get palette[0], since it's the one the spine represents.
        val logData = listOf(
            entry(1, pid = 10, tid = 11),
            entry(2, pid = 10, tid = 999),
            entry(3, pid = 10, tid = 11),
        )
        val target = TidMapTarget(pid = 10, tid = 999)
        val palette = listOf(Color.Cyan, Color.Yellow)

        val colors = computeTidMapColors(logData, target, palette)

        assertEquals(Color.Cyan, colors[999])
        assertEquals(Color.Yellow, colors[11])
    }

    @Test
    fun sameLogDataAlwaysProducesTheSameTidToColorMappingRegardlessOfCallCount() {
        // The actual bug this guards against: TidMapOverlay used to assign colors PER PANEL, from
        // each panel's own (possibly differently filtered) item list. computeTidMapColors is
        // anchored to one canonical source (logData) precisely so calling it twice, independently,
        // over the SAME logData yields an IDENTICAL map every time.
        val logData = listOf(entry(1, pid = 10, tid = 10), entry(2, pid = 10, tid = 11))
        val target = TidMapTarget(pid = 10, tid = 10)

        val first = computeTidMapColors(logData, target)
        val second = computeTidMapColors(logData, target)

        assertEquals(first, second)
    }

    @Test
    fun colorsComputedFromLogDataMatchThePlainBuildingBlocksOverTheSameSpan() {
        // Cross-check against the lower-level building blocks computeTidMapColors is built from
        // (findTidMapSpan + computeTidMapBranches + assignTidMapColors, with the target's own tid
        // seeded first) — same inputs, wrapped as LogItem.Row instead of LogEntry, must produce the
        // identical result, since that's exactly what computeTidMapColors does internally.
        val logData = listOf(entry(1, pid = 100, tid = 100), entry(2, pid = 777, tid = 778))
        val target = TidMapTarget(pid = 100, tid = 100)
        val palette = listOf(Color.Cyan, Color.Yellow)

        val viaLogData = computeTidMapColors(logData, target, palette)

        val items = logData.map { LogItem.Row(it, indent = 0) }
        val span = findTidMapSpan(items, target)!!
        val viaBuildingBlocks = assignTidMapColors(
            computeTidMapBranches(items, span, target),
            existing = mapOf(target.tid to palette.first()),
            palette = palette,
        )

        assertEquals(viaBuildingBlocks, viaLogData)
    }

    // ── tidMapHighlightedEntryRange ──────────────────────────────────────────────

    @Test
    fun highlightedRangeIsNullWhenNoVisibleBranchMatchesTheHighlightedColor() {
        val visible = listOf(branch(1, colorKey = 10), branch(2, colorKey = 20))

        assertNull(tidMapHighlightedEntryRange(visible, highlightedColorKey = 999))
    }

    @Test
    fun highlightedRangeIsTheSingleEntryWhenOnlyOneVisibleBranchMatches() {
        val visible = listOf(branch(1, colorKey = 10), branch(2, colorKey = 20), branch(3, colorKey = 10))

        // Only entry 2 has colorKey 20 — first and last of the matching sub-range are the same row.
        assertEquals(2 to 2, tidMapHighlightedEntryRange(visible, highlightedColorKey = 20))
    }

    @Test
    fun highlightedRangeSpansFirstToLastVisibleMatchInGivenOrder() {
        // Order matters and is NOT re-sorted by this function — it trusts the caller's own on-screen
        // top-to-bottom ordering (see the function's own doc for why that responsibility is the
        // caller's, not this pure core's).
        val visible = listOf(
            branch(1, colorKey = 10),
            branch(2, colorKey = 20),
            branch(3, colorKey = 10),
            branch(4, colorKey = 30),
            branch(5, colorKey = 10),
        )

        assertEquals(1 to 5, tidMapHighlightedEntryRange(visible, highlightedColorKey = 10))
    }

    // ── regression: the user's own fixture ────────────────────────────

    @Test
    fun reproUserFixtureOnlyTheSelectedPidGetsBranchesEveryOtherPidIsExcluded() {
        // Mirrors the exact pid/tid sequence from the user's own screenshot: pid 4242 (target, main
        // thread, logs constantly, always as tid 4242 — no sibling threads in this fixture),
        // interleaved positionally with 610 (several tids), 1745, 1834, 1188, 2441. This is the
        // exact scenario the user pointed at asking "why is there a line to pid 610" — none of
        // those other pids should produce a branch or a color entry at all now.
        val logData = listOf(
            entry(1, 4242, 4242), entry(2, 1745, 1796), entry(3, 4242, 4242), entry(4, 4242, 4242),
            entry(5, 4242, 4242), entry(6, 4242, 4242), entry(7, 4242, 4242), entry(8, 4242, 4242),
            entry(9, 4242, 4242), entry(10, 4242, 4242), entry(11, 610, 747), entry(12, 4242, 4242),
            entry(13, 1834, 1871), entry(14, 610, 724), entry(15, 4242, 4242), entry(16, 4242, 4242),
            entry(17, 4242, 4242), entry(18, 4242, 4242), entry(19, 4242, 4242), entry(20, 610, 724),
            entry(21, 1188, 1188), entry(22, 610, 733), entry(23, 610, 701), entry(24, 610, 701),
            entry(25, 610, 701), entry(26, 610, 701), entry(27, 610, 701), entry(28, 2441, 2470),
            entry(29, 4242, 4242), entry(30, 4242, 4242), entry(31, 610, 736),
        )
        val target = TidMapTarget(pid = 4242, tid = 4242)

        val colors = computeTidMapColors(logData, target)
        assertEquals(setOf(4242), colors.keys)

        val items = logData.map { LogItem.Row(it, indent = 0) }
        val span = findTidMapSpan(items, target)!!
        val branches = computeTidMapBranches(items, span, target)
        assertTrue(branches.all { it.colorKey == 4242 })
        val excludedEntryIds = setOf(2, 11, 13, 14, 20, 21, 22, 23, 24, 25, 26, 27, 28, 31)
        assertTrue(branches.none { it.entryId in excludedEntryIds })
    }
}
