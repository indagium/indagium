package com.indagium

import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.MessageRule
import com.indagium.model.RuleTarget
import com.indagium.utils.FilterPlan
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.containsPattern
import com.indagium.utils.matchesPidTidTokens
import com.indagium.utils.passesFilter
import com.indagium.utils.resolvePidTidTokens
import com.indagium.utils.tagMatchesPrefix
import com.indagium.utils.tagMsgContainsPattern
import com.indagium.utils.visibleLogLineText
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The per-run FilterPlan must give exactly the verdict of the per-row computation it replaced.
// legacyPasses below is that earlier per-row code, kept here as the oracle: it rebuilds the rule
// lists and re-resolves the PID/TID tokens for every row.
@Suppress("MagicNumber") // Fixture pids, tids and row counts, not tunable constants.
class FilterPlanParityTest {
    private val tags = listOf("Net", "com.app.Net", "com.app.Net.Http", "com.app.Ui", "Camera", "Boot", "Db")
    private val words = listOf("connect", "timeout", "retry", "open", "closed", "frame", "error 42", "ok", "slow path")
    private val processNames = mapOf(100 to "com.app", 101 to "com.app", 200 to "com.other", 300 to "system_server")
    private val tagPids = mapOf("Net" to setOf(100, 300), "Camera" to setOf(200), "Db" to emptySet())

    private fun rows(count: Int, seed: Long): List<LogEntry> {
        val random = Random(seed)
        val pids = intArrayOf(100, 101, 200, 300, 400)
        val tids = intArrayOf(100, 150, 200, 777, 400)
        return (1..count).map { id ->
            LogEntry(
                id = id,
                ts = "10:00:%02d.%03d".format(id / 1000 % 60, id % 1000),
                level = LogLevel.entries[random.nextInt(LogLevel.entries.size)],
                tag = tags[random.nextInt(tags.size)],
                msg = words[random.nextInt(words.size)] + " " + words[random.nextInt(words.size)],
                pid = pids[random.nextInt(pids.size)],
                tid = tids[random.nextInt(tids.size)],
            )
        }
    }

    private fun rule(
        id: String,
        include: Boolean,
        pattern: String,
        regex: Boolean = false,
        tag: String? = null,
        prefix: String? = null,
        enabled: Boolean = true,
        target: RuleTarget = RuleTarget.MESSAGE,
        mode: FilterMode = FilterMode.TAGS,
    ) = MessageRule(id, include, pattern, regex, tag, prefix, enabled, target, mode)

    private val filters: List<Pair<String, Filter>> = listOf(
        "default" to Filter(),
        "levels" to Filter(levels = setOf(LogLevel.E, LogLevel.W)),
        "active tags" to Filter(activeTags = setOf("Net", "Db")),
        "prefixes" to Filter(pkgPrefixes = setOf("com.app.Net"), activeTags = setOf("com.app.Net.Http")),
        "excludes" to Filter(excludeTags = setOf("Boot"), excludePkgPrefixes = setOf("com.app.Ui"), excludeKw = "slow"),
        "positive message rule" to Filter(messageRules = listOf(rule("p", true, "timeout"))),
        "positive regex rule + base tag" to Filter(
            activeTags = setOf("Camera"),
            messageRules = listOf(rule("p", true, "err.r \\d+", regex = true)),
        ),
        "negative rule" to Filter(messageRules = listOf(rule("n", false, "retry"))),
        "scoped rules" to Filter(
            messageRules = listOf(
                rule("p1", true, "connect", tag = "Net"),
                rule("p2", true, "frame", prefix = "com.app"),
                rule("n1", false, "closed", prefix = "com.app.Net"),
            ),
        ),
        "disabled, blank and keyword-mode rules are ignored" to Filter(
            messageRules = listOf(
                rule("d", true, "timeout", enabled = false),
                rule("b", true, "  "),
                rule("k", true, "open", mode = FilterMode.KEYWORD),
            ),
        ),
        "numeric pid selector" to Filter(pidTidFilter = "100, 777"),
        "process-name selector" to Filter(pidTidFilter = "com.app"),
        "tag token selector" to Filter(pidTidFilter = "tag:Net"),
        "mixed tokens selector" to Filter(pidTidFilter = "com.other tag:Camera 400 tag:Missing unknown.pkg"),
        "selector plus base filter" to Filter(pidTidFilter = "200", activeTags = setOf("Boot")),
        "selector plus kwInTag" to Filter(pidTidFilter = "system_server", kwInTag = "ok", kwInTagRegex = false),
        "kwInTag regex alone" to Filter(kwInTag = "fr.me", kwInTagRegex = true),
        "pid/tid rules, positive and negative" to Filter(
            messageRules = listOf(
                rule("pp", true, "100 com.other", target = RuleTarget.PID_TID),
                rule("pn", false, "tag:Camera", target = RuleTarget.PID_TID),
            ),
        ),
        "scoped pid/tid rule by name" to Filter(
            messageRules = listOf(rule("ps", true, "com.app", tag = "Net", target = RuleTarget.PID_TID)),
            activeTags = setOf("Db"),
        ),
        "everything at once" to Filter(
            levels = setOf(LogLevel.V, LogLevel.D, LogLevel.I, LogLevel.E),
            activeTags = setOf("Db"),
            excludeTags = setOf("Boot"),
            pidTidFilter = "300 tag:Camera",
            kwInTag = "ok",
            messageRules = listOf(
                rule("p", true, "open"),
                rule("n", false, "error 42"),
                rule("pp", true, "com.app", target = RuleTarget.PID_TID),
                rule("pn", false, "777", target = RuleTarget.PID_TID),
            ),
        ),
        "keyword mode plain" to Filter(mode = FilterMode.KEYWORD, kwText = "retry"),
        "keyword mode regex" to Filter(mode = FilterMode.KEYWORD, kwText = "(open|closed) .*ok", kwRegex = true),
        "keyword mode ignores message rules and tag excludes" to Filter(
            mode = FilterMode.KEYWORD,
            kwText = "frame",
            excludeTags = setOf("Net"),
            messageRules = listOf(rule("p", true, "timeout"), rule("n", false, "frame")),
        ),
        "keyword mode with selector" to Filter(mode = FilterMode.KEYWORD, kwText = "slow", pidTidFilter = "com.other"),
    )

    @Test
    fun planPathMatchesTheLegacyPerRowComputationForEveryFilter() {
        val entries = rows(1500, seed = 7)
        val emptyNames = emptyMap<Int, String>()
        val emptyTagPids = emptyMap<String, Set<Int>>()
        for ((name, filter) in filters) {
            for ((names, pids) in listOf(emptyNames to emptyTagPids, processNames to tagPids)) {
                val plan = FilterPlan(filter, names, pids)
                val planContext = RegexEvaluationContext()
                val oneOffContext = RegexEvaluationContext()
                val legacyContext = RegexEvaluationContext()
                var passing = 0
                for (entry in entries) {
                    val expected = legacyPasses(entry, filter, names, legacyContext, pids)
                    assertEquals(expected, passesFilter(entry, plan, planContext), "plan path: $name, row ${entry.id}")
                    assertEquals(expected, passesFilter(entry, filter, names, oneOffContext, pids), "one-off path: $name, row ${entry.id}")
                    if (expected) passing++
                }
                // Guard against a vacuous comparison: no filter in the list may admit or drop every row
                // unless that is genuinely what it is for.
                if (names === processNames && name != "keyword mode regex") {
                    assertTrue(passing in 1..entries.size, "$name admitted $passing of ${entries.size}")
                }
            }
        }
    }

    @Test
    fun theThreeAndTwoArgumentFormsStillAgreeWithThePlan() {
        val entries = rows(200, seed = 11)
        for ((name, filter) in filters) {
            val plan = FilterPlan(filter, emptyMap())
            for (entry in entries) {
                val expected = passesFilter(entry, plan, RegexEvaluationContext())
                assertEquals(expected, passesFilter(entry, filter), "2-arg: $name, row ${entry.id}")
                assertEquals(expected, passesFilter(entry, filter, RegexEvaluationContext()), "3-arg: $name, row ${entry.id}")
            }
        }
    }

    @Test
    fun aPlanIsFixedAtItsInputsSoLaterChangesToTheAnalysisMapsDoNotLeakIn() {
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "Net", "connect", pid = 100, tid = 5)
        val filter = Filter(pidTidFilter = "com.app")
        val names = mutableMapOf(100 to "com.app")
        val plan = FilterPlan(filter, names)
        assertTrue(passesFilter(entry, plan, RegexEvaluationContext()))
        // A new run (new plan) sees the new map; the earlier plan keeps its resolved tokens.
        names.clear()
        assertTrue(passesFilter(entry, plan, RegexEvaluationContext()))
        assertEquals(false, passesFilter(entry, FilterPlan(filter, names), RegexEvaluationContext()))
    }

    // ── Oracle: the per-row implementation as it was before FilterPlan ──────────────

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // A verbatim copy of the replaced per-row code.
    private fun legacyPasses(
        entry: LogEntry,
        filter: Filter,
        processNames: Map<Int, String>,
        regexContext: RegexEvaluationContext,
        tagPids: Map<String, Set<Int>>,
    ): Boolean {
        val enabledRules = if (filter.mode == FilterMode.TAGS) {
            filter.messageRules.filter { it.enabled && it.pattern.isNotBlank() && it.mode == FilterMode.TAGS }
        } else {
            emptyList()
        }
        if (entry.level !in filter.levels) return false
        if (filter.mode == FilterMode.TAGS) {
            if (entry.tag in filter.excludeTags) return false
            if (filter.excludePkgPrefixes.any { pfx -> tagMatchesPrefix(entry.tag, pfx) }) return false
        }
        if (filter.excludeKw.isNotBlank() &&
            tagMsgContainsPattern(entry.tag, entry.msg, filter.excludeKw, filter.excludeKwRegex, regexContext = regexContext)
        ) {
            return false
        }
        val negative = enabledRules.filter { !it.include }
        if (negative.any { rule -> legacyScope(entry, rule) && legacyRule(entry, rule, processNames, regexContext, tagPids) }) return false
        val posRules = enabledRules.filter { it.include }
        val hasKwInTag = filter.mode == FilterMode.TAGS && filter.kwInTag.isNotBlank()
        val hasPosPidTid = filter.pidTidFilter.isNotBlank()
        if (posRules.isNotEmpty() || hasKwInTag || hasPosPidTid) {
            if (posRules.any { rule -> legacyScope(entry, rule) && legacyRule(entry, rule, processNames, regexContext, tagPids) }) return true
            if (hasKwInTag && containsPattern(entry.msg, filter.kwInTag, filter.kwInTagRegex, regexContext = regexContext)) return true
            if (hasPosPidTid &&
                matchesPidTidTokens(entry, resolvePidTidTokens(filter.pidTidFilter, processNames, tagPids))
            ) {
                return true
            }
            val activeBase = when (filter.mode) {
                FilterMode.TAGS -> filter.activeTags.isNotEmpty() || filter.pkgPrefixes.isNotEmpty()
                FilterMode.KEYWORD -> filter.kwText.isNotBlank()
            }
            return activeBase && legacyBase(entry, filter, regexContext)
        }
        return legacyBase(entry, filter, regexContext)
    }

    private fun legacyRule(
        entry: LogEntry,
        rule: MessageRule,
        processNames: Map<Int, String>,
        regexContext: RegexEvaluationContext,
        tagPids: Map<String, Set<Int>>,
    ): Boolean = when (rule.target) {
        RuleTarget.PID_TID -> matchesPidTidTokens(entry, resolvePidTidTokens(rule.pattern, processNames, tagPids))
        RuleTarget.MESSAGE ->
            if (!rule.regex) {
                containsPattern(entry.msg, rule.pattern, regex = false, regexContext = regexContext)
            } else {
                containsPattern(entry.msg, rule.pattern, regex = true, regexContext = regexContext) ||
                    containsPattern("${entry.tag} ${entry.msg}", rule.pattern, regex = true, regexContext = regexContext) ||
                    containsPattern("${entry.tag}: ${entry.msg}", rule.pattern, regex = true, regexContext = regexContext) ||
                    containsPattern("${entry.tag} : ${entry.msg}", rule.pattern, regex = true, regexContext = regexContext)
            }
    }

    private fun legacyScope(entry: LogEntry, rule: MessageRule): Boolean {
        val exact = rule.tag?.takeIf { it.isNotBlank() }
        val prefix = rule.packagePrefix?.takeIf { it.isNotBlank() }
        if (exact != null && entry.tag != exact) return false
        if (prefix != null && entry.tag != prefix && !entry.tag.startsWith("$prefix.")) return false
        return true
    }

    private fun legacyBase(entry: LogEntry, filter: Filter, regexContext: RegexEvaluationContext): Boolean = when (filter.mode) {
        FilterMode.TAGS ->
            if (filter.activeTags.isEmpty() && filter.pkgPrefixes.isEmpty()) {
                true
            } else if (entry.tag in filter.activeTags) {
                true
            } else {
                filter.pkgPrefixes
                    .filter { pfx -> tagMatchesPrefix(entry.tag, pfx) }
                    .any { pfx -> filter.activeTags.filter { tag -> tagMatchesPrefix(tag, pfx) }.isEmpty() }
            }
        FilterMode.KEYWORD ->
            if (filter.kwText.isBlank()) {
                true
            } else if (filter.kwRegex) {
                containsPattern(visibleLogLineText(entry), filter.kwText, regex = true, regexContext = regexContext)
            } else {
                tagMsgContainsPattern(entry.tag, entry.msg, filter.kwText, filter.kwRegex, regexContext = regexContext)
            }
    }
}
