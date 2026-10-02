package com.indagium

import com.indagium.model.Filter
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.RuleTarget
import com.indagium.ui.PendingMessageRuleDraft
import com.indagium.ui.computeRelevantScopeTagsSync
import com.indagium.ui.computeUnifiedCandidatesSync
import com.indagium.ui.mkTab
import com.indagium.utils.CancellationCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Regression coverage for FilterPanel.kt's single-pass rewrite of unifiedCandidates/
// relevantScopeTags (Wave 1, 1.2): the old ~7-pass version ran synchronously inside composition on
// the UI thread; computeUnifiedCandidatesSync/computeRelevantScopeTagsSync are the same
// classification, collapsed into one pass per entry, callable off-thread. These tests pin the exact
// candidate shape so the collapse can't silently change what gets suggested.
class UnifiedCandidateScanTest {
    private val noCancellation = CancellationCheck {}

    @Test
    fun inScopeAndOutOfScopeMessageMatchesAreBothOfferedInScopeFirst() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "connection timeout occurred", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Net", "connection timeout retry", pid = 200),
            LogEntry(3, "10:00:00.200", LogLevel.I, "App", "unrelated line", pid = 100),
        )
        val tab = mkTab("t", "f.log", logs).copy(filter = Filter(activeTags = setOf("App")))

        val candidates = computeUnifiedCandidatesSync(tab, tab.filter, "timeout", noCancellation)

        assertEquals(
            listOf("connection timeout occurred" to true, "connection timeout retry" to false),
            candidates.map { it.pattern to it.inScope },
        )
        assertTrue(candidates.all { it.target == RuleTarget.MESSAGE })
    }

    @Test
    fun tagQualifiedMatchSurfacesAsAContextualCandidateAheadOfPlainMessageStems() {
        // "com.my.app" doesn't appear in the message alone, only across the tag/message boundary —
        // contextualMessageRuleCandidates' own contract (see MessageRuleInputTest, which pins the
        // exact same four variants for this entry).
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "com.my.app", "method call: id=42"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "com.my.app", "plain timeout message"),
        )
        val tab = mkTab("t", "f.log", logs)

        val candidates = computeUnifiedCandidatesSync(tab, tab.filter, "com.my.app: method", noCancellation)

        val contextual = candidates.filter { it.addsImmediately }
        assertEquals(
            listOf(
                "com.my.app: method call",
                "com.my.app: method call: id",
                "method call",
                "method call: id",
            ),
            contextual.map { it.label },
        )
        assertTrue(contextual.all { it.inScope })
        // entry 2's message alone never matches "com.my.app: method" (tag-qualified), so it
        // contributes nothing here — confirms accumulateContextualAndMsgs' matchesMsgOnly
        // short-circuit didn't also swallow entry 1's contextual variants.
        assertTrue(candidates.none { it.pattern.contains("timeout") })
    }

    @Test
    fun numericSearchOffersMatchingPidCandidates() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "line one", pid = 1234),
            LogEntry(2, "10:00:00.100", LogLevel.I, "App", "line two", pid = 5678),
        )
        val tab = mkTab("t", "f.log", logs)

        val candidates = computeUnifiedCandidatesSync(tab, tab.filter, "1234", noCancellation)

        val pidCandidates = candidates.filter { it.target == RuleTarget.PID_TID }
        assertEquals(listOf("1234"), pidCandidates.map { it.pattern })
    }

    @Test
    fun pidCandidatesSurviveWhenManyMessagesContainTheNumber() {
        val logs = (1..20).map { id ->
            LogEntry(id, "10:00:00.000", LogLevel.I, "App", "worker $id for process 1234 started", pid = if (id == 1) 1234 else 99)
        }
        val tab = mkTab("t", "f.log", logs)

        val candidates = computeUnifiedCandidatesSync(tab, tab.filter, "1234", noCancellation)

        assertEquals("1234", candidates.first().pattern)
        assertEquals(RuleTarget.PID_TID, candidates.first().target)
        assertTrue(candidates.size <= 8)
    }

    @Test
    fun pidCandidatesRankExactThenPrefixThenSubstringAndAcceptPidPrefix() {
        val pids = listOf(51234, 12345, 1234, 7123)
        val logs = pids.mapIndexed { i, pid -> LogEntry(i + 1, "10:00:00.000", LogLevel.I, "App", "line", pid = pid) }
        val tab = mkTab("t", "f.log", logs)

        val plain = computeUnifiedCandidatesSync(tab, tab.filter, " 1234 ", noCancellation)
            .filter { it.target == RuleTarget.PID_TID }
        val prefixed = computeUnifiedCandidatesSync(tab, tab.filter, "pid:1234", noCancellation)
            .filter { it.target == RuleTarget.PID_TID }

        assertEquals(listOf("1234", "12345", "51234"), plain.map { it.pattern })
        assertEquals(plain.map { it.pattern }, prefixed.map { it.pattern })
    }

    @Test
    fun processNameQuerySuggestsItsPidWithTheNameInTheLabel() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "line one", pid = 4321),
            LogEntry(2, "10:00:00.100", LogLevel.I, "App", "line two", pid = 8765),
        )
        val base = mkTab("t", "f.log", logs)
        val tab = base.copy(analysis = base.analysis.copy(processNames = mapOf(4321 to "com.example.camera", 8765 to "system_server")))

        val candidates = computeUnifiedCandidatesSync(tab, tab.filter, "camera", noCancellation)

        val pidCandidates = candidates.filter { it.target == RuleTarget.PID_TID }
        assertEquals(listOf("4321"), pidCandidates.map { it.pattern })
        assertEquals("4321 · com.example.camera", pidCandidates.single().label)
    }

    @Test
    fun blankSearchProducesNoCandidatesWithoutScanningAnything() {
        val logs = listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "anything"))
        val tab = mkTab("t", "f.log", logs)

        assertTrue(computeUnifiedCandidatesSync(tab, tab.filter, "", noCancellation).isEmpty())
        assertTrue(computeUnifiedCandidatesSync(tab, tab.filter, "   ", noCancellation).isEmpty())
    }

    @Test
    fun cancellationCheckFiresPeriodicallyAcrossManyEntriesWithoutChangingTheResult() {
        val logs = (1..10_000).map { id ->
            LogEntry(id, "10:00:00.000", LogLevel.I, "App", if (id == 5000) "special timeout marker" else "noise")
        }
        val tab = mkTab("t", "f.log", logs)
        var checkCount = 0

        val candidates = computeUnifiedCandidatesSync(tab, tab.filter, "timeout", CancellationCheck { checkCount++ })

        assertTrue(checkCount > 0, "expected the cancellation check to fire at least once over 10k entries")
        assertEquals(listOf("special timeout marker"), candidates.map { it.pattern })
    }

    @Test
    fun relevantScopeTagsNarrowsToTagsThatActuallyMatchedThePendingMessagePattern() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Net", "timeout while connecting"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "com.app.Ui", "render frame"),
        )
        val tab = mkTab("t", "f.log", logs)
        val pending = PendingMessageRuleDraft(include = true, pattern = "timeout", regex = false, target = RuleTarget.MESSAGE)

        val tags = computeRelevantScopeTagsSync(tab, pending, noCancellation)

        assertEquals(setOf("com.app.Net"), tags)
    }

    @Test
    fun relevantScopeTagsResolvesPidTidTargetThroughTheSharedTokenizer() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Net", "msg", pid = 1234),
            LogEntry(2, "10:00:00.100", LogLevel.I, "com.app.Ui", "msg", pid = 5678),
        )
        val tab = mkTab("t", "f.log", logs)
        val pending = PendingMessageRuleDraft(include = true, pattern = "1234", regex = false, target = RuleTarget.PID_TID)

        val tags = computeRelevantScopeTagsSync(tab, pending, noCancellation)

        assertEquals(setOf("com.app.Net"), tags)
    }
}
