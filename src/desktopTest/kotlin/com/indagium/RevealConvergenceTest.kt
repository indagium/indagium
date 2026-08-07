package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.Filter
import com.indagium.model.LogEntry
import com.indagium.model.LogItem
import com.indagium.model.LogLevel
import com.indagium.model.ManualCollapseBlock
import com.indagium.model.ManualCollapseDirection
import com.indagium.model.SequenceDef
import com.indagium.ui.AppState
import com.indagium.ui.ComputedLogItems
import com.indagium.ui.ItemsSummary
import com.indagium.ui.expansionAndIndexForEntry
import com.indagium.ui.mkTab
import com.indagium.utils.computeItems
import com.indagium.utils.invalidateComputeCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the multi-round reveal LOOP, not just one [expansionAndIndexForEntry] call.
 *
 * At runtime the reveal never happens in a single pass: LogViewer's annotation-nav and search-nav
 * LaunchedEffects resolve a target, apply `target.expanded - tab.expanded` through
 * [AppState.toggleGroup], and are then cancelled and restarted by their own `tab.expanded` key —
 * re-resolving against the freshly recomputed item list. A reveal that resolves correctly once but
 * fails to converge (or re-collapses a group the other panel just opened) would still look broken
 * in the app while every single-call test stayed green. These drive that loop directly.
 */
class RevealConvergenceTest {

    // Crash (a stack-trace group root) folded inside a collapsed "Collapse → To start" block: the
    // exact Issues-panel scenario, needing TWO nested folds opened to reach one line.
    private fun crashInsideCollapseToStart(): AppState {
        val state = AppState()
        state.tabs = listOf(
            mkTab(
                "log",
                "test.log",
                crashLogEntries(),
            ).copy(manualBlocks = listOf(ManualCollapseBlock("m1", 6, ManualCollapseDirection.TO_START))),
        )
        return state
    }

    // Same crash entries as [crashInsideCollapseToStart], but with no manual block wrapping them:
    // entry 2 (the stack-trace group root) is a top-level collapsed StackTraceHeader from the very
    // first round, with no other fold in the way.
    private fun crashTopLevelNoManualBlock(): AppState {
        val state = AppState()
        state.tabs = listOf(mkTab("log", "test.log", crashLogEntries()))
        return state
    }

    private fun crashLogEntries(): List<LogEntry> = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "ActivityManager", "boot", pid = 100),
        LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
        LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "java.lang.NullPointerException: boom", pid = 100),
        LogEntry(4, "10:00:00.300", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
        LogEntry(5, "10:00:00.400", LogLevel.E, "AndroidRuntime", "    at android.app.Activity.performCreate(Activity.java:1)", pid = 100),
        LogEntry(6, "10:00:00.500", LogLevel.I, "App", "anchor line", pid = 100),
        LogEntry(7, "10:00:00.600", LogLevel.I, "App", "after", pid = 100),
    )

    /** One iteration == one LaunchedEffect restart. Fails loudly on a dropped or non-converging jump. */
    private fun driveEffectLoop(state: AppState, applyFilter: Boolean, entryId: Int): Int {
        var restarts = 0
        while (restarts < 10) {
            val tab = state.tab("log")!!
            val target = expansionAndIndexForEntry(tab, applyFilter = applyFilter, entryId = entryId)
                ?: error("restart $restarts: expansionAndIndexForEntry returned null — the jump would be dropped")
            val toOpen = target.expanded - tab.expanded
            if (toOpen.isEmpty()) return restarts
            toOpen.forEach { gid -> state.toggleGroup("log", gid) }
            restarts++
        }
        error("reveal never converged within 10 effect restarts")
    }

    // CHANGE A: entry 2 is the stack-trace ROOT, which the collapsed StackTraceHeader displays
    // directly — the header is the correct landing spot and must stay collapsed. Only the OUTER
    // fold (m1, the manual "Collapse -> To start" block) hides that header itself, so only m1 gets
    // expanded; the stack trace gid must never appear in `expanded`.
    @Test
    fun filteredPanelRevealConvergesOnACrashInsideACollapseToStartBlock() {
        val state = crashInsideCollapseToStart()

        val restarts = driveEffectLoop(state, applyFilter = true, entryId = 2)

        val expanded = state.tab("log")!!.expanded
        assertTrue("m1" in expanded, "manual block never opened; expanded=$expanded")
        assertTrue(expanded.none { it.startsWith("st_") }, "stack trace must stay collapsed; expanded=$expanded")
        assertEquals(1, restarts, "should settle in a single effect restart; expanded=$expanded")

        val tab = state.tab("log")!!
        val target = assertNotNull(expansionAndIndexForEntry(tab, applyFilter = true, entryId = 2))
        val items = computeItems(tab.copy(expanded = target.expanded), applyFilter = true)
        val header = items[target.index] as LogItem.StackTraceHeader
        assertEquals(2, header.entry.id)
        assertFalse(header.expanded, "the resolved index must land on the still-collapsed header")
    }

    // The split view's Original panel resolves with applyFilter = false against its own item list,
    // but shares the one tab.expanded set — it must reveal the same nesting on its own.
    @Test
    fun originalPanelRevealConvergesOnACrashInsideACollapseToStartBlock() {
        val state = crashInsideCollapseToStart()

        val restarts = driveEffectLoop(state, applyFilter = false, entryId = 2)

        val expanded = state.tab("log")!!.expanded
        assertTrue("m1" in expanded, "manual block never opened; expanded=$expanded")
        assertTrue(expanded.none { it.startsWith("st_") }, "stack trace must stay collapsed; expanded=$expanded")
        assertEquals(1, restarts, "should settle in a single effect restart; expanded=$expanded")

        val tab = state.tab("log")!!
        val target = assertNotNull(expansionAndIndexForEntry(tab, applyFilter = false, entryId = 2))
        val items = computeItems(tab.copy(expanded = target.expanded), applyFilter = false)
        val header = items[target.index] as LogItem.StackTraceHeader
        assertEquals(2, header.entry.id)
        assertFalse(header.expanded, "the resolved index must land on the still-collapsed header")
    }

    // Split view resolves BOTH panels in sequence against one accumulating `opened` set. Since
    // onToggleGroup is a toggle, a gid counted twice would re-COLLAPSE what the first panel just
    // opened — this pins that the accumulation prevents it. CHANGE A: only m1 (the fold that hides
    // the header) ever gets expanded; the stack trace stays collapsed in both panels.
    @Test
    fun splitViewResolvingBothPanelsNeverRecollapsesAGroupTheOtherJustOpened() {
        val state = crashInsideCollapseToStart()
        var restarts = 0
        while (restarts < 10) {
            val tab = state.tab("log")!!
            var opened = tab.expanded
            expansionAndIndexForEntry(tab, applyFilter = true, entryId = 2)?.let { t ->
                (t.expanded - opened).forEach { gid -> state.toggleGroup("log", gid) }
                opened = opened + t.expanded
            }
            expansionAndIndexForEntry(tab, applyFilter = false, entryId = 2)?.let { t ->
                (t.expanded - opened).forEach { gid -> state.toggleGroup("log", gid) }
                opened = opened + t.expanded
            }
            if (state.tab("log")!!.expanded == tab.expanded) break
            restarts++
        }

        val expanded = state.tab("log")!!.expanded
        assertTrue("m1" in expanded, "manual block never opened; expanded=$expanded")
        assertTrue(expanded.none { it.startsWith("st_") }, "stack trace must stay collapsed; expanded=$expanded")
    }

    // Documents a REMAINING gap, deliberately not fixed here: a target the active filter excludes
    // can never be revealed by expanding, so the reveal returns null. LogViewer's nav effects now
    // hold a request open only while the item list is still being computed; once it has settled —
    // which is immediately in this case — they give up and consume. The jump then vanishes with no
    // user-visible signal, indistinguishable from "the app ignored my click". Change this test when
    // that path grows real feedback (tell the user WHY, or offer to clear the filter).
    @Test
    fun aTargetExcludedByTheActiveFilterStillResolvesToNullAndSoIsSilentlyDropped() {
        val state = crashInsideCollapseToStart()
        state.upTab("log") { it.copy(filter = it.filter.copy(levels = setOf(LogLevel.I))) }

        val target = expansionAndIndexForEntry(state.tab("log")!!, applyFilter = true, entryId = 2)

        assertEquals(null, target)
    }

    // ── largeFileMode reveal (bug A: Issues-panel crash click / Find Next-Prev did nothing) ──────

    // The DIRECT-HIT case: entryId IS a collapsed header's own displayed line (a StackTraceHeader's
    // root — exactly what CrashSite.entry always is), with nothing else in the way. CHANGE A: this
    // must resolve immediately with NO expansion at all — the header itself is the landing spot, and
    // the user opens it themselves — whereas before the largeFileMode guard used to run first and
    // return null outright.
    @Test
    fun largeFileModeResolvesADirectHitOnATopLevelCollapsedStackTraceHeaderWithoutExpandingIt() {
        val state = crashTopLevelNoManualBlock()
        state.upTab("log") { it.copy(largeFileMode = true) }
        val tab = state.tab("log")!!

        val target = assertNotNull(
            expansionAndIndexForEntry(tab, applyFilter = true, entryId = 2),
            "direct hit on a top-level collapsed StackTraceHeader must resolve in largeFileMode",
        )

        assertEquals(tab.expanded, target.expanded, "must resolve with NO expansion; expanded=${target.expanded}")
        val items = computeItems(tab.copy(expanded = target.expanded), applyFilter = true)
        val header = items[target.index] as LogItem.StackTraceHeader
        assertEquals(2, header.entry.id)
        assertFalse(header.expanded)
    }

    // Manual blocks stay probeable even on a huge file: a TO_START header displays its own ANCHOR
    // (entry 6 here), never the lines it folds, so a target inside one can never be a direct hit and
    // would otherwise be unreachable. Probing them is bounded by the number of user-created blocks,
    // not by file size. This is the "Collapse -> To start then click the crash inside it" case.
    // CHANGE A: only the outer manual block (m1) gets expanded; the inner stack trace stays collapsed.
    @Test
    fun largeFileModeStillProbesManualBlocksSoCollapseToStartDoesNotSwallowTheJump() {
        val state = crashInsideCollapseToStart()
        state.upTab("log") { it.copy(largeFileMode = true) }

        val restarts = driveEffectLoop(state, applyFilter = true, entryId = 2)

        val expanded = state.tab("log")!!.expanded
        assertTrue("m1" in expanded, "manual block never opened in largeFileMode; expanded=$expanded")
        assertTrue(
            expanded.none { it.startsWith("st_") },
            "stack trace must stay collapsed in largeFileMode; expanded=$expanded",
        )
        assertEquals(1, restarts, "should settle in a single effect restart; expanded=$expanded")
    }

    // Non-largeFileMode control for the same nested-fold shape: without the largeFileMode guard,
    // the ranked-candidate probing resolves it in one call (see crashInsideCollapseToStartConverges
    // in the tests above) — reconfirmed here explicitly against the no-manual-block fixture's twin.
    @Test
    fun aStackFrameInsideACollapsedTraceResolvesInBothLargeFileModeAndNormalMode() {
        val buried = crashTopLevelNoManualBlock()

        // Entry 4 is a stack frame INSIDE the collapsed stack-trace group rooted at entry 2 — when
        // collapsed, only the root line (entry 2) is displayed; entry 4 has no header of its own.
        // Stack membership is answerable straight from analysis.stackTraceGroups, so even
        // largeFileMode resolves this one without probing — the case a Find-bar match most often
        // lands on, since search runs over the fully-expanded list.
        val largeFileTarget = run {
            buried.upTab("log") { it.copy(largeFileMode = true) }
            expansionAndIndexForEntry(buried.tab("log")!!, applyFilter = true, entryId = 4)
        }
        assertTrue(largeFileTarget != null, "a stack frame inside a collapsed trace must resolve in largeFileMode via member lookup")
        assertTrue(
            largeFileTarget.expanded.any { it.startsWith("st_") },
            "stack trace gid never opened; expanded=${largeFileTarget.expanded}",
        )

        val normal = crashTopLevelNoManualBlock()
        val normalTarget = expansionAndIndexForEntry(normal.tab("log")!!, applyFilter = true, entryId = 4)
        assertTrue(normalTarget != null, "the same buried target must still resolve via ranked probing outside largeFileMode")
        assertTrue(normalTarget.expanded.any { it.startsWith("st_") }, "stack trace gid never opened; expanded=${normalTarget.expanded}")
    }

    // ── CHANGE B: the staleness predicate LogViewer's nav effects rely on ──────────────────────
    // ComputedLogItems.expandedAt records which `tab.expanded` set an item list was actually built
    // from. largeFileMode's async recompute can keep serving the PREVIOUS ComputedLogItems (built
    // from an older expanded set) for up to LOADING_GRACE_MS with loading == false; the nav effects
    // compare expandedAt against the live tab.expanded (`computedItems.expandedAt != tab.expanded`)
    // to detect that window and refuse to resolve/scroll against it — this pins that exact equality
    // check, independent of Compose, for both the stale and the fresh case, plus the "not computed
    // yet" (null) case, which must also count as stale.
    @Test
    fun computedLogItemsExpandedAtCorrectlyReportsStaleVsFreshAgainstATabsExpandedSet() {
        val emptySummary =
            ItemsSummary(IntArray(0), IntArray(0), java.util.BitSet(), collapsedGroupCount = 0, expandedGroupCount = 0)
        val liveExpanded = setOf("m1")

        val fresh = ComputedLogItems(emptyList(), emptySummary, loading = false, expandedAt = liveExpanded)
        val staleAfterAToggle = ComputedLogItems(emptyList(), emptySummary, loading = false, expandedAt = setOf("m1", "st_2"))
        val notYetComputed = ComputedLogItems(emptyList(), emptySummary, loading = true, expandedAt = null)

        assertFalse(fresh.expandedAt != liveExpanded, "built from the exact same expanded set must not be stale")
        assertTrue(
            staleAfterAToggle.expandedAt != liveExpanded,
            "built from a different (older) expanded set must be stale",
        )
        assertTrue(notYetComputed.expandedAt != liveExpanded, "null expandedAt (not computed yet) must count as stale")
    }

    // ── CHANGE C: cachedSeqGroupsFor gives largeFileMode a cheap sequence-membership escape hatch,
    // the same shape as the stack-trace member lookup just above it. Bug: a Find-bar match landing
    // on a line INSIDE a collapsed sequence group on a >64 MB log resolved to null and the group
    // never opened, because collapsedHeaders deliberately excludes SeqHeader candidates under
    // largeFileMode (probing one costs a full computeItems) and there was no cheaper answer. ──────

    // THE REPORTED BUG, reproduced exactly as the real call sites trigger it: every production call
    // site (LogViewer.kt's nav LaunchedEffects) always passes the panel's already-rendered `items`
    // as currentItems, so expansionAndIndexForEntry's own top-of-function
    // `currentItems ?: computeItems(...)` fallback never runs there — cachedSeqGroupsFor's memo is
    // the ONLY source of seqGroups largeFileMode ever gets. Mirroring that: computeItems(tab, true)
    // is called once first (exactly what building the panel's initial `items` does), and its result
    // is passed as currentItems, so the memo is warmed by that call and by nothing else.
    // This test was written and run BEFORE Change 2 (the largeFileMode sequence-membership lookup
    // in LogViewer.kt) existed, and failed then: expansionAndIndexForEntry returned null. After
    // Change 2 it passes. See the turn's report for the exact before/after command output.
    @Test
    fun largeFileModeRevealsALineInsideACollapsedSequenceGroupOnceTheMemoIsWarm() {
        val tab = mkTab(
            "log-seq-regression",
            "test.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Auth", "request started"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "com.app.Auth", "referenced"),
                LogEntry(3, "10:00:00.200", LogLevel.I, "com.app.Auth", "third"),
            ),
        ).copy(
            filter = Filter(
                sequences = listOf(
                    SequenceDef("auth-start", "request started", priority = 1, color = Color.Red, tag = "com.app.Auth"),
                ),
            ),
            largeFileMode = true,
        )
        // Warm the memo (and obtain the actual collapsed render), mirroring the app where the panel
        // has always just rendered before any nav effect fires.
        val collapsedItems = computeItems(tab, applyFilter = true)

        val target = expansionAndIndexForEntry(tab, applyFilter = true, entryId = 2, currentItems = collapsedItems)

        assertNotNull(target, "a line inside a collapsed sequence group must resolve in largeFileMode once the memo is warm")
        assertTrue(
            target.expanded.any { it.startsWith("sg_") },
            "sequence group gid never opened; expanded=${target.expanded}",
        )
        val items = computeItems(tab.copy(expanded = target.expanded), applyFilter = true)
        assertEquals(2, (items[target.index] as LogItem.Row).entry.id)
    }

    // Nested case: entry 3 ("inner work") sits two folds deep — the outer sequence group, then the
    // nested inner sub-sequence — so reaching it needs two effect restarts, outermost fold first
    // (opening the nested gid first would be useless while its parent is still folded and the
    // nested header itself unreachable). driveEffectLoop drives that multi-round convergence, using
    // tab id "log" like every other driveEffectLoop-based test in this file (the process-global
    // compute cache is keyed by tabId + an identity check on logData/analysis/filter, so reusing
    // "log" across tests with their own fresh LogEntry lists never leaks state between them — see
    // the identity checks cachedSeqGroupsFor and computeItems' own `prior` lookup both apply).
    @Test
    fun largeFileModeRevealsALineInsideANestedSubSequenceUnderACollapsedParent() {
        val state = AppState()
        state.tabs = listOf(
            mkTab(
                "log",
                "test.log",
                listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.I, "Outer", "outer start"),
                    LogEntry(2, "10:00:00.100", LogLevel.I, "Inner", "inner start"),
                    LogEntry(3, "10:00:00.200", LogLevel.I, "Inner", "inner work"),
                    LogEntry(4, "10:00:00.300", LogLevel.I, "Inner", "inner end"),
                    LogEntry(5, "10:00:00.400", LogLevel.I, "Outer", "outer tail"),
                    LogEntry(6, "10:00:00.500", LogLevel.I, "Outer", "outer end"),
                ),
            ).copy(
                filter = Filter(
                    sequences = listOf(
                        SequenceDef(
                            "outer", "outer start", priority = 1, color = Color.Red,
                            tag = "Outer", endMatchText = "outer end", endTag = "Outer",
                        ),
                        SequenceDef(
                            "inner", "inner start", priority = 2, color = Color.Blue,
                            tag = "Inner", endMatchText = "inner end", endTag = "Inner",
                        ),
                    ),
                ),
                largeFileMode = true,
            ),
        )
        computeItems(state.tab("log")!!, applyFilter = true) // warm the memo, mirroring the app

        val restarts = driveEffectLoop(state, applyFilter = true, entryId = 3)

        val expanded = state.tab("log")!!.expanded
        assertEquals(2, expanded.size, "both the outer sequence group and the nested inner sub-sequence must be open; expanded=$expanded")
        assertTrue(expanded.all { it.startsWith("sg_") }, "expected only sequence gids; expanded=$expanded")
        assertTrue(restarts >= 1, "must take at least one effect restart to open the outer fold before the nested one")

        val tab = state.tab("log")!!
        val target = assertNotNull(expansionAndIndexForEntry(tab, applyFilter = true, entryId = 3))
        val items = computeItems(tab.copy(expanded = target.expanded), applyFilter = true)
        assertEquals(3, (items[target.index] as LogItem.Row).entry.id)
    }

    // Cold-cache degradation: cachedSeqGroupsFor must return null (never crash) when nothing has
    // ever populated the memo for this (tab, applyFilter). Reaching that state genuinely requires
    // bypassing expansionAndIndexForEntry's own top-of-function `currentItems ?: computeItems(...)`
    // fallback — passing currentItems explicitly (as every real call site already does) is what
    // makes that possible; letting the fallback run would immediately self-warm the memo before the
    // largeFileMode branch is ever reached. currentItems here is a hand-built stand-in for "only the
    // collapsed header renders" — not real computeItems output — precisely so this tab/applyFilter
    // pair never once goes through computeItems and the cache stays genuinely cold. Uses a distinct
    // tab id (not "log", never used elsewhere in this file) plus an explicit
    // invalidateComputeCache() call as a second belt-and-suspenders guarantee against any possible
    // cross-test leakage, per both options the task offered.
    @Test
    fun largeFileModeWithNoWarmedMemoReturnsNullForABuriedSequenceMemberRatherThanCrashing() {
        val tab = mkTab(
            "log-seq-cold-cache",
            "test.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Auth", "request started"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "com.app.Auth", "referenced"),
                LogEntry(3, "10:00:00.200", LogLevel.I, "com.app.Auth", "third"),
            ),
        ).copy(
            filter = Filter(
                sequences = listOf(
                    SequenceDef("auth-start", "request started", priority = 1, color = Color.Red, tag = "com.app.Auth"),
                ),
            ),
            largeFileMode = true,
        )
        invalidateComputeCache(tab.id)
        val handBuiltCollapsedHeaderOnly = listOf(LogItem.Row(tab.logData[0], 0, null))

        val target = expansionAndIndexForEntry(tab, applyFilter = true, entryId = 2, currentItems = handBuiltCollapsedHeaderOnly)

        assertEquals(null, target, "a cold memo must degrade to null, never crash or hang")
    }

    // The CORE RULE still holds under largeFileMode for sequences specifically: entry 1 IS the
    // collapsed SeqHeader's own displayed root line, so it must resolve immediately with NO
    // expansion at all — the header itself is the landing spot, matching the pre-existing
    // StackTraceHeader/ManualHeader direct-hit tests above (largeFileModeResolvesADirectHitOn...).
    @Test
    fun largeFileModeCoreRuleStillLandsOnACollapsedSeqHeadersOwnRootLineWithoutExpandingIt() {
        val tab = mkTab(
            "log-seq-core-rule",
            "test.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Auth", "request started"),
                LogEntry(2, "10:00:00.100", LogLevel.I, "com.app.Auth", "referenced"),
                LogEntry(3, "10:00:00.200", LogLevel.I, "com.app.Auth", "third"),
            ),
        ).copy(
            filter = Filter(
                sequences = listOf(
                    SequenceDef("auth-start", "request started", priority = 1, color = Color.Red, tag = "com.app.Auth"),
                ),
            ),
            largeFileMode = true,
        )

        val target = assertNotNull(expansionAndIndexForEntry(tab, applyFilter = true, entryId = 1))

        assertEquals(tab.expanded, target.expanded, "the header's own root line must resolve with NO expansion; expanded=${target.expanded}")
    }
}
