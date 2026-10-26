package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.CrashKind
import com.indagium.model.CrashSite
import com.indagium.model.CustomIssueSite
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.Highlighter
import com.indagium.model.LogAnalysis
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.ManualCollapseBlock
import com.indagium.model.ManualCollapseDirection
import com.indagium.model.MessageRule
import com.indagium.model.SequenceDef
import com.indagium.ui.highlightersSummary
import com.indagium.ui.issuesSummary
import com.indagium.ui.logLevelSummary
import com.indagium.ui.messageRulesSummary
import com.indagium.ui.originalSummary
import com.indagium.ui.savedFiltersSummary
import com.indagium.ui.sequencesSummary
import com.indagium.ui.tagsSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogViewTileSummariesTest {
    private fun entry(id: Int) = LogEntry(id, "10:00:00.000", LogLevel.E, "Tag", "m$id")

    private fun crash(id: Int, kind: CrashKind, fatal: Boolean = false, signature: String = "s$id") =
        CrashSite("c$id", entry(id), kind, null, isFatal = fatal, signature = signature)

    private fun analysis(vararg sites: CrashSite, custom: List<CustomIssueSite> = emptyList()) =
        LogAnalysis(crashSites = sites.toList(), customIssueSites = custom, pending = false)

    @Test
    fun tagsSummaryCountsActiveAndExcludedTagsAndPrefixes() {
        assertNull(tagsSummary(Filter()))
        assertEquals("2 active", tagsSummary(Filter(activeTags = setOf("A", "B"))))
        assertEquals("1 excluded", tagsSummary(Filter(excludeTags = setOf("C"))))
        assertEquals(
            "2 active · 2 excluded",
            tagsSummary(Filter(activeTags = setOf("A"), pkgPrefixes = setOf("com.foo"), excludeTags = setOf("C"), excludePkgPrefixes = setOf("com.bar"))),
        )
    }

    @Test
    fun messageRulesSummaryCountsOnlyEnabledTagsModeRules() {
        assertNull(messageRulesSummary(Filter()))
        val rules = listOf(
            MessageRule("1", include = false, pattern = "noise"),
            MessageRule("2", include = false, pattern = "off", enabled = false),
            MessageRule("3", include = true, pattern = "keep"),
            MessageRule("4", include = false, pattern = "kw", mode = FilterMode.KEYWORD),
        )
        assertEquals("1 excluded · 1 included", messageRulesSummary(Filter(messageRules = rules)))
        assertEquals("1 excluded", messageRulesSummary(Filter(messageRules = rules.take(2))))
    }

    @Test
    fun highlightersSummaryCountsSwitchedOnRules() {
        fun h(id: String, on: Boolean) = Highlighter(id, "p", regex = false, color = Color.Red, on = on)
        assertNull(highlightersSummary(Filter()))
        assertNull(highlightersSummary(Filter(highlighters = listOf(h("1", false)))))
        assertEquals("2 active", highlightersSummary(Filter(highlighters = listOf(h("1", true), h("2", true), h("3", false)))))
    }

    @Test
    fun logLevelSummaryNamesWhatIsHidden() {
        assertNull(logLevelSummary(Filter()))
        assertEquals("V hidden", logLevelSummary(Filter(levels = LogLevel.entries.toSet() - LogLevel.V)))
        assertEquals("V, D hidden", logLevelSummary(Filter(levels = LogLevel.entries.toSet() - LogLevel.V - LogLevel.D)))
        assertEquals("only E, A", logLevelSummary(Filter(levels = setOf(LogLevel.E, LogLevel.A))))
        assertEquals("none shown", logLevelSummary(Filter(levels = emptySet())))
    }

    @Test
    fun sequencesSummaryCountsDefinitionsAndRanges() {
        fun seq(id: String, enabled: Boolean = true) = SequenceDef(id, "x", priority = 1, color = Color.Blue, enabled = enabled)

        fun range(id: String, enabled: Boolean = true) =
            ManualCollapseBlock(id, 1, ManualCollapseDirection.RANGE, enabled = enabled, endId = 5)
        assertNull(sequencesSummary(Filter(), emptyList()))
        assertEquals("2", sequencesSummary(Filter(sequences = listOf(seq("a"), seq("b"), seq("c", enabled = false))), emptyList()))
        assertEquals("2 · 1 range", sequencesSummary(Filter(sequences = listOf(seq("a"), seq("b"))), listOf(range("r"), range("x", enabled = false))))
        assertEquals("2 ranges", sequencesSummary(Filter(), listOf(range("r"), range("s"))))
        assertEquals("off", sequencesSummary(Filter(seqOn = false, sequences = listOf(seq("a"))), emptyList()))
    }

    @Test
    fun issuesSummaryGroupsLikeTheIssuesPanel() {
        assertNull(issuesSummary(LogAnalysis()))
        assertNull(issuesSummary(analysis()))
        assertEquals("1 ANR", issuesSummary(analysis(crash(1, CrashKind.ANR))))
        assertEquals(
            "2 crashes",
            issuesSummary(analysis(crash(1, CrashKind.NATIVE_CRASH), crash(2, CrashKind.EXCEPTION, fatal = true))),
        )
        // two occurrences of one signature are one group
        assertEquals("1 crash", issuesSummary(analysis(crash(1, CrashKind.NATIVE_CRASH, signature = "x"), crash(2, CrashKind.NATIVE_CRASH, signature = "x"))))
        assertEquals(
            "1 crash · 2 ANRs · 1 exception · 1 custom",
            issuesSummary(
                analysis(
                    crash(1, CrashKind.NATIVE_CRASH),
                    crash(2, CrashKind.ANR),
                    crash(3, CrashKind.ANR),
                    crash(4, CrashKind.EXCEPTION),
                    custom = listOf(CustomIssueSite("u1", entry(5), "r", "Rule")),
                ),
            ),
        )
    }

    @Test
    fun savedFiltersAndOriginalSummaries() {
        assertNull(savedFiltersSummary(0))
        assertEquals("3 saved", savedFiltersSummary(3))
        assertNull(originalSummary(0))
        assertEquals("1 line", originalSummary(1))
        assertEquals("1999 lines", originalSummary(1999))
    }
}
