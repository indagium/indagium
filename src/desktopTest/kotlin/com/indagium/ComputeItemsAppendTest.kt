@file:Suppress("MagicNumber")

package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.LogAnalysis
import com.indagium.model.LogEntry
import com.indagium.model.LogItem
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.ManualCollapseBlock
import com.indagium.model.ManualCollapseDirection
import com.indagium.model.MessageRule
import com.indagium.model.RuleTarget
import com.indagium.model.SequenceDef
import com.indagium.model.StackTraceGroup
import com.indagium.ui.mkRmap
import com.indagium.ui.mkTab
import com.indagium.ui.spliceSummarize
import com.indagium.ui.summarizeItems
import com.indagium.utils.AppendOnlyLogList
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.appendFastPathHits
import com.indagium.utils.appendLogEntries
import com.indagium.utils.computeItems
import com.indagium.utils.computeProcessNames
import com.indagium.utils.invalidateComputeCache
import com.indagium.utils.mergeTagPids
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// The append fast path (appendComputeFast in utils/Filter.kt) must be indistinguishable from a full
// recompute. Ground truth for every step is a fresh computeItems on a COPY of the tab under another
// id (so its own cache slot is cold and the main slot keeps holding the fast path's result, which
// lets consecutive appends chain fast results the way a live capture does). Each step also asserts
// WHICH path ran through appendFastPathHits, so a guard that silently stops firing is caught too.
class ComputeItemsAppendTest {
    private fun crashEntries(): List<LogEntry> = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello before", pid = 5),
        LogEntry(2, "10:00:00.001", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 5),
        LogEntry(3, "10:00:00.002", LogLevel.E, "AndroidRuntime", "java.lang.IllegalStateException: boom", pid = 5),
        LogEntry(4, "10:00:00.003", LogLevel.E, "AndroidRuntime", "    at com.example.Foo.bar(Foo.kt:1)", pid = 5),
        LogEntry(5, "10:00:00.004", LogLevel.I, "App", "middle", pid = 5),
        LogEntry(6, "10:00:00.005", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: worker", pid = 5),
        LogEntry(7, "10:00:00.006", LogLevel.E, "AndroidRuntime", "    at com.example.Baz.qux(Baz.kt:9)", pid = 5),
        LogEntry(8, "10:00:00.007", LogLevel.I, "App", "after", pid = 5),
    )

    private val tags = listOf("A", "B", "Net")

    private fun mixed(startId: Int, count: Int, level: (Int) -> LogLevel = { LogLevel.I }, pid: Int = 5): List<LogEntry> =
        (0 until count).map { k ->
            val id = startId + k
            LogEntry(id, "10:00:01.${id.toString().padStart(3, '0')}", level(id), tags[id % tags.size], if (id % 2 == 0) "key $id" else "m $id", pid = pid)
        }

    // Mirrors TailCoordinator.appendTailedLines: logData/rmap replaced, analysis copied (stack groups
    // keep their identity), processNames/tagPids merged.
    private fun append(tab: LogTab, batch: List<LogEntry>): LogTab {
        val data = appendLogEntries(tab.logData, batch)
        return tab.copy(
            logData = data,
            rmap = mkRmap(data),
            analysis = tab.analysis.copy(
                processNames = tab.analysis.processNames + computeProcessNames(batch),
                tagPids = mergeTagPids(tab.analysis.tagPids, batch),
            ),
        )
    }

    private fun checkedCompute(tab: LogTab, applyFilter: Boolean, expectFast: Boolean?, label: String): List<LogItem> {
        val before = appendFastPathHits.get()
        val result = computeItems(tab, applyFilter)
        val fast = appendFastPathHits.get() - before
        val freshTab = tab.copy(id = tab.id + "~fresh")
        invalidateComputeCache(freshTab.id)
        val fresh = computeItems(freshTab, applyFilter)
        invalidateComputeCache(freshTab.id)
        assertEquals(fresh, result, "result differs from a full recompute ($label)")
        if (expectFast != null) assertEquals(if (expectFast) 1 else 0, fast, "unexpected path ($label)")
        return result
    }

    /** Warms the cache on [start], then appends each of [batches] and checks every step. */
    private fun runAppends(
        start: LogTab,
        applyFilter: Boolean,
        batches: List<List<LogEntry>>,
        expectFast: List<Boolean>? = null,
    ): LogTab {
        invalidateComputeCache(start.id)
        computeItems(start, applyFilter)
        var tab = start
        batches.forEachIndexed { i, batch ->
            tab = append(tab, batch)
            checkedCompute(tab, applyFilter, expectFast?.get(i), "${tab.id} batch $i applyFilter=$applyFilter")
        }
        return tab
    }

    private fun batchesFrom(firstId: Int): List<List<LogEntry>> {
        val sizes = listOf(3, 1, 5, 2)
        var next = firstId
        return sizes.map { n -> mixed(next, n).also { next += n } }
    }

    private fun mixedTab(id: String, filter: Filter = Filter()): LogTab =
        mkTab(id, "a.log", mixed(1, 12)).copy(filter = filter)

    private fun both(name: String, filter: Filter) {
        for (apply in listOf(true, false)) {
            runAppends(mixedTab("$name-$apply", filter), apply, batchesFrom(13), List(4) { true })
        }
    }

    @Test
    fun noFilter() = both("ap-nofilter", Filter())

    @Test
    fun keywordFilter() = both("ap-kw", Filter(mode = FilterMode.KEYWORD, kwText = "key"))

    @Test
    fun tagFilter() = both("ap-tag", Filter(activeTags = setOf("A", "Net")))

    @Test
    fun messageRules() = both(
        "ap-rules",
        Filter(
            messageRules = listOf(
                MessageRule(id = "r1", include = true, pattern = "key"),
                MessageRule(id = "r2", include = false, pattern = "10"),
            ),
        ),
    )

    @Test
    fun appendedBatchFilteredOutEntirelyKeepsTheSameItems() {
        val tab = mixedTab("ap-none", Filter(mode = FilterMode.KEYWORD, kwText = "nomatch"))
        runAppends(tab, true, batchesFrom(13), List(4) { true })
    }

    @Test
    fun stackGroupsInPrefixAreFoldedAndTailIsUnfolded() {
        val prefix = crashEntries() + mixed(9, 5)
        val tab = mkTab("ap-stack", "a.log", prefix)
        assertTrue(tab.analysis.stackTraceGroups.isNotEmpty())
        runAppends(tab, true, batchesFrom(14), List(4) { true })
        runAppends(tab.copy(id = "ap-stack-exp", expanded = tab.analysis.stackTraceGroups.map { it.gid }.toSet()), true, batchesFrom(14), List(4) { true })
        runAppends(tab.copy(id = "ap-stack-noapply"), false, batchesFrom(14), List(4) { true })
    }

    @Test
    fun stackGroupsUnderKeywordFilter() {
        val tab = mkTab("ap-stack-kw", "a.log", crashEntries() + mixed(9, 5))
            .copy(filter = Filter(mode = FilterMode.KEYWORD, kwText = "e"))
        runAppends(tab, true, batchesFrom(14), List(4) { true })
    }

    @Test
    fun tailThatNarrowsAFullyVisiblePrefixThenAStackToggleStillMatches() {
        // Prefix fully visible (no row filtered), tail partly filtered out: the allStackGroups
        // selection (`data.size == logData.size`) flips branches between cache build and extension.
        val filter = Filter(levels = setOf(LogLevel.I, LogLevel.E))
        val tab = mkTab("ap-flip", "a.log", crashEntries() + mixed(9, 3)).copy(filter = filter)
        val batches = listOf(
            mixed(12, 4, level = { if (it % 2 == 0) LogLevel.D else LogLevel.I }),
            mixed(16, 3),
        )
        val grown = runAppends(tab, true, batches, listOf(true, true))
        val gid = grown.analysis.stackTraceGroups.first().gid
        // A single-group toggle after the fast path stored its entry splices from it.
        checkedCompute(grown.copy(expanded = setOf(gid)), true, null, "toggle after append")
        checkedCompute(grown.copy(expanded = emptySet()), true, null, "toggle back")
        // ...and appending again on top of the spliced entry still matches.
        val again = append(grown, mixed(19, 2))
        checkedCompute(again, true, true, "append after toggle")
    }

    @Test
    fun analysisPending() {
        val tab = mkTab("ap-pending", "a.log", mixed(1, 12), analysis = LogAnalysis(pending = true))
        runAppends(tab, true, batchesFrom(13), List(4) { true })
    }

    @Test
    fun analysisPendingFlippingToCompleteFallsBack() {
        val data = crashEntries() + mixed(9, 3)
        val pendingTab = mkTab("ap-pend-flip", "a.log", data, analysis = LogAnalysis(pending = true))
        invalidateComputeCache(pendingTab.id)
        computeItems(pendingTab, true)
        val done = append(pendingTab, mixed(12, 2)).let { it.copy(analysis = it.analysis.copy(pending = false)) }
        checkedCompute(done, true, false, "pending -> complete")
    }

    // ── manual blocks ─────────────────────────────────────────────────────────

    private fun blockTab(id: String, block: ManualCollapseBlock, expanded: Set<String> = emptySet()) =
        mixedTab(id).copy(manualBlocks = listOf(block), expanded = expanded)

    @Test
    fun toStartBlockUsesFastPath() {
        val block = ManualCollapseBlock("b1", anchorId = 5, direction = ManualCollapseDirection.TO_START)
        runAppends(blockTab("ap-ts", block), true, batchesFrom(13), List(4) { true })
        runAppends(blockTab("ap-ts-exp", block, setOf("b1")), true, batchesFrom(13), List(4) { true })
        runAppends(blockTab("ap-ts-noapply", block), false, batchesFrom(13), List(4) { true })
    }

    @Test
    fun rangeBlockUsesFastPath() {
        val block = ManualCollapseBlock("b1", anchorId = 3, direction = ManualCollapseDirection.RANGE, endId = 8)
        runAppends(blockTab("ap-range", block), true, batchesFrom(13), List(4) { true })
        runAppends(blockTab("ap-range-exp", block, setOf("b1")), true, batchesFrom(13), List(4) { true })
    }

    @Test
    fun toStartBlockUnderKeywordFilterUsesFastPath() {
        val block = ManualCollapseBlock("b1", anchorId = 6, direction = ManualCollapseDirection.TO_START)
        val tab = blockTab("ap-ts-kw", block).copy(filter = Filter(mode = FilterMode.KEYWORD, kwText = "key"))
        runAppends(tab, true, batchesFrom(13), List(4) { true })
    }

    @Test
    fun toEndBlockFallsBack() {
        val block = ManualCollapseBlock("b1", anchorId = 5, direction = ManualCollapseDirection.TO_END)
        runAppends(blockTab("ap-te", block), true, batchesFrom(13), List(4) { false })
        runAppends(blockTab("ap-te-exp", block, setOf("b1")), true, batchesFrom(13), List(4) { false })
    }

    @Test
    fun disabledToEndBlockIsIgnored() {
        val block = ManualCollapseBlock("b1", anchorId = 5, direction = ManualCollapseDirection.TO_END, enabled = false)
        runAppends(blockTab("ap-te-off", block), true, batchesFrom(13), List(4) { true })
    }

    @Test
    fun blockWhoseAnchorArrivesInTheTailFallsBackUntilItIsResolved() {
        // Prefix ids 1..12; batches cover 13-15, 16, 17-21, 22-23. Anchor 20 sits in batch 2, so
        // batches 0 and 1 (anchor not yet present) and batch 2 (anchor arrives) must fall back; once
        // the anchor is within the cached rows, batch 3 is fast.
        val block = ManualCollapseBlock("b1", anchorId = 20, direction = ManualCollapseDirection.TO_START)
        runAppends(blockTab("ap-late", block), true, batchesFrom(13), listOf(false, false, false, true))
    }

    @Test
    fun rangeBlockWhoseEndArrivesInTheTailFallsBack() {
        val block = ManualCollapseBlock("b1", anchorId = 3, direction = ManualCollapseDirection.RANGE, endId = 17)
        runAppends(blockTab("ap-late-range", block), true, batchesFrom(13), listOf(false, false, false, true))
    }

    // ── sequences ─────────────────────────────────────────────────────────────

    private fun seq(enabled: Boolean = true) = SequenceDef(id = "sq", matchText = "m 3", priority = 1, color = Color.Red, enabled = enabled)

    @Test
    fun activeSequenceFoldingFallsBack() {
        val tab = mixedTab("ap-seq", Filter(sequences = listOf(seq())))
        runAppends(tab, true, batchesFrom(13), List(4) { false })
    }

    @Test
    fun sequencesSwitchedOffOrDisabledDoNotBlockTheFastPath() {
        runAppends(mixedTab("ap-seq-off", Filter(seqOn = false, sequences = listOf(seq()))), true, batchesFrom(13), List(4) { true })
        runAppends(mixedTab("ap-seq-dis", Filter(sequences = listOf(seq(enabled = false)))), true, batchesFrom(13), List(4) { true })
    }

    // ── pid / tag maps ────────────────────────────────────────────────────────

    private fun tagFollowTab(id: String): LogTab {
        val data = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "X", "first run", pid = 100, tid = 100),
            LogEntry(2, "10:00:01.000", LogLevel.I, "Other", "restarted, not yet logging X", pid = 200, tid = 200),
        )
        val rule = MessageRule(id = "r1", include = true, target = RuleTarget.PID_TID, pattern = "tag:X")
        return mkTab(id, "a.log", data).copy(filter = Filter(messageRules = listOf(rule)))
    }

    @Test
    fun newlyLearnedPidFallsBackAndRevealsOldRows() {
        val tab = tagFollowTab("ap-pid")
        val batches = listOf(
            listOf(LogEntry(3, "10:00:02.000", LogLevel.I, "Other", "still not X", pid = 200, tid = 200)),
            listOf(LogEntry(4, "10:00:03.000", LogLevel.I, "X", "second run", pid = 200, tid = 200)),
            listOf(LogEntry(5, "10:00:04.000", LogLevel.I, "Other", "more", pid = 200, tid = 200)),
        )
        // Batch 1 learns that pid 200 logged X: old rows 2 and 3 become visible -> full recompute.
        // Batches 0 and 2 leave the maps equal, so the fast path applies.
        val grown = runAppends(tab, true, batches, listOf(true, false, true))
        val visible = computeItems(grown, true).map { (it as LogItem.Row).entry.id }
        assertEquals(listOf(1, 2, 3, 4, 5), visible)
    }

    @Test
    fun pidTidFilterAlsoNeedsStableMaps() {
        val tab = tagFollowTab("ap-pidfilter").copy(filter = Filter(pidTidFilter = "tag:X"))
        val batches = listOf(
            listOf(LogEntry(3, "10:00:02.000", LogLevel.I, "X", "second run", pid = 200, tid = 200)),
            listOf(LogEntry(4, "10:00:03.000", LogLevel.I, "Other", "more", pid = 200, tid = 200)),
        )
        runAppends(tab, true, batches, listOf(false, true))
    }

    @Test
    fun learningAPidDoesNotBlockFiltersThatIgnoreThePidMaps() {
        val tab = mixedTab("ap-pid-irrelevant", Filter(mode = FilterMode.KEYWORD, kwText = "key"))
        val batches = listOf(mixed(13, 3, pid = 77), mixed(16, 3, pid = 78))
        runAppends(tab, true, batches, listOf(true, true))
    }

    // ── other fallbacks ───────────────────────────────────────────────────────

    @Test
    fun replacedLogDataThatIsNotAnExtensionFallsBack() {
        val tab = mixedTab("ap-replace")
        invalidateComputeCache(tab.id)
        computeItems(tab, true)
        // Same ids, different row objects: not an append of the cached list.
        val replaced = mixed(1, 15)
        val other = tab.copy(logData = replaced, rmap = mkRmap(replaced))
        checkedCompute(other, true, false, "replaced")
    }

    @Test
    fun filterChangeAtTheSameTimeAsAnAppendFallsBack() {
        val tab = mixedTab("ap-filterchange")
        invalidateComputeCache(tab.id)
        computeItems(tab, true)
        val grown = append(tab, mixed(13, 3)).copy(filter = Filter(mode = FilterMode.KEYWORD, kwText = "key"))
        checkedCompute(grown, true, false, "filter + append")
    }

    @Test
    fun expandedChangeAtTheSameTimeAsAnAppendFallsBack() {
        val tab = mkTab("ap-expchange", "a.log", crashEntries() + mixed(9, 3))
        invalidateComputeCache(tab.id)
        computeItems(tab, true)
        val gid = tab.analysis.stackTraceGroups.first().gid
        val grown = append(tab, mixed(12, 3)).copy(expanded = setOf(gid))
        checkedCompute(grown, true, false, "expand + append")
    }

    @Test
    fun stackGroupReachingIntoTheTailFallsBack() {
        // A group that already references an id the tail is about to introduce (should not happen
        // for a live capture, but the fast path must not assume it).
        val data = mixed(1, 12)
        val group = StackTraceGroup(gid = "st_10", rid = 10, memberIds = listOf(11, 14))
        val analysis = LogAnalysis(stackTraceGroups = listOf(group), pending = false)
        val tab = mkTab("ap-reach", "a.log", data, analysis = analysis)
        runAppends(tab, true, listOf(mixed(13, 3)), listOf(false))
    }

    @Test
    fun priorRowObjectsKeepTheirIdentityAndTheSpliceSummaryMatches() {
        val tab = mkTab("ap-identity", "a.log", mixed(1, 5000))
        invalidateComputeCache(tab.id)
        val before = computeItems(tab, true)
        val beforeSummary = summarizeItems(before)
        val grown = append(tab, mixed(5001, 50))
        val hitsBefore = appendFastPathHits.get()
        val after = computeItems(grown, true)
        assertEquals(hitsBefore + 1, appendFastPathHits.get())
        assertEquals(5050, after.size)
        for (i in before.indices) assertSame(before[i], after[i], "row $i lost its identity")
        val spliced = spliceSummarize(before, beforeSummary, after)
        assertNotNull(spliced)
        val full = summarizeItems(after)
        assertTrue(full.allIds.contentEquals(spliced.allIds))
        assertTrue(full.rowIds.contentEquals(spliced.rowIds))
        assertEquals(full.idBits, spliced.idBits)
        assertEquals(full.collapsedGroupCount, spliced.collapsedGroupCount)
        assertEquals(full.expandedGroupCount, spliced.expandedGroupCount)
    }

    @Test
    fun storeRegrowthAcrossAppendsStaysOnTheFastPath() {
        // Start in a small AppendOnlyLogList (capacity 1024) and grow past it several times; the
        // later views live on different stores, which extendsSnapshot must see through.
        val first = mixed(1, 10)
        val data = appendLogEntries(emptyList(), first)
        assertTrue(data is AppendOnlyLogList)
        val tab = mkTab("ap-regrow", "a.log", data).copy(filter = Filter(mode = FilterMode.KEYWORD, kwText = "key"))
        var next = 11
        val batches = List(8) { mixed(next, 400).also { next += 400 } }
        runAppends(tab, true, batches, List(8) { true })
    }

    @Test
    fun probeCallersMayUseTheFastPathButNeverStore() {
        val tab = mixedTab("ap-probe")
        invalidateComputeCache(tab.id)
        computeItems(tab, true)
        val grown = append(tab, mixed(13, 4))
        val before = appendFastPathHits.get()
        val probe = computeItems(grown, true, RegexEvaluationContext(), storeInCache = false)
        assertEquals(before + 1, appendFastPathHits.get())
        // Nothing was stored, so the very same call takes the fast path again.
        computeItems(grown, true, RegexEvaluationContext(), storeInCache = false)
        assertEquals(before + 2, appendFastPathHits.get())
        val freshTab = grown.copy(id = "ap-probe~fresh")
        invalidateComputeCache(freshTab.id)
        assertEquals(computeItems(freshTab, true), probe)
        invalidateComputeCache(freshTab.id)
    }

    @Test
    fun regexTimeoutWhileFilteringTheTailIsNotCached() {
        val catastrophic = "a".repeat(40) + "!"
        val prefix = (1..20).map { LogEntry(it, "10:00:00.000", LogLevel.I, "App", "ok") }
        val tab = mkTab("ap-timeout", "a.log", prefix)
            .copy(filter = Filter(mode = FilterMode.KEYWORD, kwText = "(a+)+$", kwRegex = true))
        invalidateComputeCache(tab.id)
        computeItems(tab, true)
        val tail = (21..30).map { LogEntry(it, "10:00:01.000", LogLevel.I, "App", catastrophic) }
        val grown = append(tab, tail)
        val ctx = RegexEvaluationContext(matchBudgetNanos = 1L)
        val before = appendFastPathHits.get()
        computeItems(grown, true, ctx)
        assertTrue(ctx.hasTimedOut)
        assertEquals(before + 1, appendFastPathHits.get())
        // The timed-out extension was dropped, not stored: the same tab recomputes in full.
        computeItems(grown, true, RegexEvaluationContext(matchBudgetNanos = 1L))
        assertEquals(before + 1, appendFastPathHits.get())
    }
}
