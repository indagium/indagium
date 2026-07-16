package com.openlog

import androidx.compose.ui.graphics.Color
import com.openlog.model.DEFAULT_KEYWORD_HIGHLIGHT_COLOR
import com.openlog.model.Filter
import com.openlog.model.FilterMode
import com.openlog.model.Highlighter
import com.openlog.model.LogEntry
import com.openlog.model.LogItem
import com.openlog.model.LogLevel
import com.openlog.model.LogTab
import com.openlog.model.MessageRule
import com.openlog.model.SequenceDef
import com.openlog.ui.buildFullLineAnnotation
import com.openlog.ui.keywordRegexHighlightRanges
import com.openlog.utils.RegexEvaluationContext
import com.openlog.utils.computeItems
import com.openlog.utils.passesFilter
import com.openlog.utils.visibleEntries
import com.openlog.utils.visibleLogLineText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilterBehaviorTest {
    @Test
    fun regexSearchAndHighlightUseTheSameVisibleLogRowText() {
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "Receiver", "receive : message after 42 ms")
        val lineText = visibleLogLineText(entry)
        val filter = Filter(
            mode = FilterMode.KEYWORD,
            kwText = """receive : message.*\d+""",
            kwRegex = true,
            kwHighlightColor = Color.Cyan,
        )

        val ranges = keywordRegexHighlightRanges(lineText, filter)

        assertTrue(passesFilter(entry, filter))
        assertEquals(listOf(lineText.indexOf("receive") to lineText.indexOf(" ms")), ranges)
        val annotation = buildFullLineAnnotation(entry, emptyList(), Color.Gray, Color.Gray, Color.Gray, Color.Gray, filter)
        assertEquals(ranges.single().first, annotation.spanStyles.last().start)
        assertEquals(ranges.single().second, annotation.spanStyles.last().end)
    }

    @Test
    fun regexSearchMatchesTheVisibleTagMessagePunctuation() {
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "Receiver", "message")
        val filter = Filter(mode = FilterMode.KEYWORD, kwText = """Receiver:\s+message""", kwRegex = true)

        assertTrue(passesFilter(entry, filter))
        assertEquals(
            listOf("Receiver: message".let { visibleLogLineText(entry).indexOf(it) to visibleLogLineText(entry).indexOf(it) + it.length }),
            keywordRegexHighlightRanges(visibleLogLineText(entry), filter),
        )
    }

    @Test
    fun regexSearchHighlightRangesAreEmptyForInvalidPlainOrDisabledRegexSearch() {
        val lineText = "10:00:00.000  I  App: timeout after 42 ms"

        assertTrue(
            keywordRegexHighlightRanges(
                lineText,
                Filter(mode = FilterMode.KEYWORD, kwText = "[broken", kwRegex = true),
            ).isEmpty(),
        )
        assertTrue(
            keywordRegexHighlightRanges(
                lineText,
                Filter(mode = FilterMode.KEYWORD, kwText = "timeout", kwRegex = false),
            ).isEmpty(),
        )
        assertTrue(
            keywordRegexHighlightRanges(
                lineText,
                Filter(mode = FilterMode.KEYWORD, kwText = "timeout", kwRegex = true, kwHighlightEnabled = false),
            ).isEmpty(),
        )
    }

    @Test
    fun disablingRegexHighlightDoesNotChangeRegexFiltering() {
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "App", "timeout after 42 ms")
        val enabled = Filter(mode = FilterMode.KEYWORD, kwText = """timeout.*\d+""", kwRegex = true)
        val disabled = enabled.copy(kwHighlightEnabled = false, kwHighlightColor = Color.Cyan)

        assertTrue(passesFilter(entry, enabled))
        assertTrue(passesFilter(entry, disabled))
        assertTrue(keywordRegexHighlightRanges(visibleLogLineText(entry), disabled).isEmpty())
    }

    @Test
    fun regexSearchHighlightDefaultsToEnabledStableColor() {
        val filter = Filter(mode = FilterMode.KEYWORD, kwText = "timeout", kwRegex = true)

        assertTrue(filter.kwHighlightEnabled)
        assertEquals(DEFAULT_KEYWORD_HIGHLIGHT_COLOR, filter.kwHighlightColor)
    }

    @Test
    fun activeTagsRefineMatchingPackagePrefixAndAddExternalTags() {
        val selectedInsidePackage = LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.design.Example", "shown")
        val siblingInsidePackage = LogEntry(2, "10:00:00.001", LogLevel.I, "com.app.net.Http", "hidden")
        val selectedExternalTag = LogEntry(3, "10:00:00.002", LogLevel.I, "com.other.auth.Login", "shown")
        val unrelated = LogEntry(4, "10:00:00.003", LogLevel.I, "org.other.Network", "hidden")
        val filter = Filter(
            pkgPrefixes = setOf("com.app"),
            activeTags = setOf("com.app.design.Example", "com.other.auth.Login"),
        )

        assertTrue(passesFilter(selectedInsidePackage, filter))
        assertFalse(passesFilter(siblingInsidePackage, filter))
        assertTrue(passesFilter(selectedExternalTag, filter))
        assertFalse(passesFilter(unrelated, filter))
    }

    @Test
    fun excludedPackagePrefixRemovesMatchingTags() {
        val blockedExact = LogEntry(1, "10:00:00.000", LogLevel.I, "com.app", "hidden")
        val blockedChild = LogEntry(2, "10:00:00.001", LogLevel.I, "com.app.Network", "hidden")
        val allowed = LogEntry(3, "10:00:00.002", LogLevel.I, "com.other.Network", "shown")
        val filter = Filter(excludePkgPrefixes = setOf("com.app"))

        assertFalse(passesFilter(blockedExact, filter))
        assertFalse(passesFilter(blockedChild, filter))
        assertTrue(passesFilter(allowed, filter))
    }

    @Test
    fun excludedPackagePrefixWinsOverIncludedPackagePrefix() {
        val blocked = LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.noisy.Sync", "hidden")
        val allowed = LogEntry(2, "10:00:00.001", LogLevel.I, "com.app.auth.Login", "shown")
        val filter = Filter(
            pkgPrefixes = setOf("com.app"),
            excludePkgPrefixes = setOf("com.app.noisy"),
        )

        assertFalse(passesFilter(blocked, filter))
        assertTrue(passesFilter(allowed, filter))
    }

    @Test
    fun scopedIncludeMessageRuleNarrowsOnlyMatchingTag() {
        val desired = LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Network", "request complete")
        val spam = LogEntry(2, "10:00:00.001", LogLevel.I, "com.app.Network", "heartbeat")
        // No base tag/package filter is configured, so a scoped rule is the sole positive
        // selector — an entry outside its scope must NOT vacuously pass just because the base
        // filter happens to be empty (that would make "show only X" show everything instead).
        val otherTag = LogEntry(3, "10:00:00.002", LogLevel.I, "com.app.Auth", "login complete")
        val filter = Filter(
            messageRules = listOf(
                MessageRule(id = "r1", include = true, pattern = "request", tag = "com.app.Network"),
            ),
        )

        assertTrue(passesFilter(desired, filter))
        assertFalse(passesFilter(spam, filter))
        assertFalse(passesFilter(otherTag, filter))
    }

    // Regression test: Message rules are a Tags-mode tool. Regex/Keyword mode keeps any persisted
    // rules for compatibility, but they must be inert there so hidden rules cannot affect results.
    @Test
    fun messageRuleOnlyAppliesInTagsModeAndDoesNotAffectRegexMode() {
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Network", "request complete")
        val tagsOnlyRule = MessageRule(id = "r1", include = true, pattern = "request", mode = FilterMode.TAGS)
        val keywordOnlyExclude = MessageRule(id = "r2", include = false, pattern = "request", mode = FilterMode.KEYWORD)

        val tagsFilter = Filter(mode = FilterMode.TAGS, messageRules = listOf(tagsOnlyRule, keywordOnlyExclude))
        val keywordFilter = Filter(mode = FilterMode.KEYWORD, messageRules = listOf(tagsOnlyRule, keywordOnlyExclude))

        // In TAGS mode: the TAGS-scoped positive rule is the sole active selector, so it matches;
        // the KEYWORD-scoped exclude rule must be inert here despite being present on the Filter.
        assertTrue(passesFilter(entry, tagsFilter))
        // In KEYWORD mode: both message rules are inert. With no keyword text configured, the row
        // passes through the empty Regex-mode base filter instead of being excluded by the hidden rule.
        assertTrue(passesFilter(entry, keywordFilter))
    }

    @Test
    fun regexModeUsesOnlyKeywordPatternAndIgnoresMessageRules() {
        val matchesRegex = LogEntry(1, "10:00:00.000", LogLevel.I, "App", "timeout after 42 ms")
        val matchesHiddenPositiveRule = LogEntry(2, "10:00:00.001", LogLevel.I, "App", "hidden rule only")
        val matchesHiddenNegativeRule = LogEntry(3, "10:00:00.002", LogLevel.I, "App", "timeout blocked by rule 7")
        val filter = Filter(
            mode = FilterMode.KEYWORD,
            kwText = """timeout .* \d+""",
            kwRegex = true,
            messageRules = listOf(
                MessageRule(id = "inc", include = true, pattern = "hidden rule", mode = FilterMode.KEYWORD),
                MessageRule(id = "exc", include = false, pattern = "blocked", mode = FilterMode.KEYWORD),
            ),
        )

        assertTrue(passesFilter(matchesRegex, filter))
        assertFalse(passesFilter(matchesHiddenPositiveRule, filter))
        assertTrue(passesFilter(matchesHiddenNegativeRule, filter))
    }

    @Test
    fun kwInTagOnlyAppliesInTagsMode() {
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Network", "request complete")
        val tagsFilter = Filter(mode = FilterMode.TAGS, kwInTag = "request")
        val keywordFilter = Filter(mode = FilterMode.KEYWORD, kwInTag = "request")

        assertTrue(passesFilter(entry, tagsFilter))
        // kwInTag must not act as a positive selector while in KEYWORD mode — with no other
        // filter configured, an unfiltered entry still passes via the (empty) base filter, but
        // kwInTag itself must not be the reason it does.
        assertTrue(passesFilter(entry, keywordFilter))
        val nonMatching = LogEntry(2, "10:00:00.001", LogLevel.I, "com.app.Network", "unrelated")
        assertFalse(passesFilter(nonMatching, tagsFilter)) // kwInTag correctly excludes in TAGS mode
        assertTrue(passesFilter(nonMatching, keywordFilter)) // but has no effect at all in KEYWORD mode
    }

    @Test
    fun excludeTagsOnlyApplyInTagsMode() {
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Network", "heartbeat")
        val tagsFilter = Filter(mode = FilterMode.TAGS, excludeTags = setOf("com.app.Network"))
        val keywordFilter = Filter(mode = FilterMode.KEYWORD, excludeTags = setOf("com.app.Network"))

        assertFalse(passesFilter(entry, tagsFilter))
        assertTrue(passesFilter(entry, keywordFilter))
    }

    @Test
    fun positiveRuleAddsToActiveBaseFilterInsteadOfReplacingIt() {
        // Regression test: adding an unscoped message rule unrelated to an already-active
        // package filter must show BOTH the package filter's own matches AND the rule's
        // matches (union) — not replace the package filter's results with the rule's alone.
        val packageMatch = LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Ui", "render frame")
        val ruleMatch = LogEntry(2, "10:00:00.001", LogLevel.I, "system.Binder",
            "ArrayIndexOutOfBoundsException: length=16; index=17")
        val neither = LogEntry(3, "10:00:00.002", LogLevel.I, "system.Other", "unrelated noise")
        val filter = Filter(
            pkgPrefixes = setOf("com.app"),
            messageRules = listOf(
                MessageRule(id = "r1", include = true, pattern = "ArrayIndexOutOfBoundsException"),
            ),
        )

        assertTrue(passesFilter(packageMatch, filter))
        assertTrue(passesFilter(ruleMatch, filter))
        assertFalse(passesFilter(neither, filter))
    }

    @Test
    fun scopedExcludeMessageRuleRemovesPackagePrefixSpam() {
        val spam = LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Network", "poll heartbeat")
        val desired = LogEntry(2, "10:00:00.001", LogLevel.I, "com.app.Network", "request complete")
        val otherPackage = LogEntry(3, "10:00:00.002", LogLevel.I, "org.app.Network", "poll heartbeat")
        val filter = Filter(
            messageRules = listOf(
                MessageRule(id = "r1", include = false, pattern = "heartbeat", packagePrefix = "com.app"),
            ),
        )

        assertFalse(passesFilter(spam, filter))
        assertTrue(passesFilter(desired, filter))
        assertTrue(passesFilter(otherPackage, filter))
    }

    @Test
    fun highlighterMatchesFullRenderedLineIncludingTag() {
        val line = buildFullLineAnnotation(
            entry = LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Network", "request complete"),
            highlighters = listOf(Highlighter("h1", "Network", false, Color.Yellow, true)),
            tsColor = Color.Gray,
            pidColor = Color.Gray,
            tagColor = Color.DarkGray,
            msgColor = Color.Black,
        )

        val start = line.text.indexOf("Network")
        val end = start + "Network".length

        assertTrue(
            line.spanStyles.any { span ->
                span.start <= start && span.end >= end && span.item.background == Color.Yellow.copy(alpha = 0.6f)
            },
        )
    }

    @Test
    @Suppress("MagicNumber")
    fun logHighlightBatchQuarantinesCatastrophicPattern() {
        val regexContext = RegexEvaluationContext(matchBudgetNanos = 1L)
        val highlighter = Highlighter("h1", "(a+)+$", true, Color.Yellow, true)

        repeat(100) { id ->
            buildFullLineAnnotation(
                entry = LogEntry(id, "10:00:00.000", LogLevel.I, "App", "a".repeat(40) + "!"),
                highlighters = listOf(highlighter),
                tsColor = Color.Gray,
                pidColor = Color.Gray,
                tagColor = Color.DarkGray,
                msgColor = Color.Black,
                keywordRegexFilter = null,
                regexContext = regexContext,
            )
        }

        assertEquals(1, regexContext.timeoutCountForTesting)
    }

    @Test
    fun invalidRegexFilterDoesNotThrowOrMatch() {
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Network", "request complete")
        val filter = Filter(mode = FilterMode.KEYWORD, kwText = "[", kwRegex = true)

        assertFalse(passesFilter(entry, filter))
    }

    @Test
    @Suppress("MagicNumber")
    fun bulkFilteringQuarantinesCatastrophicPatternForWholeOperation() {
        val catastrophicMessage = "a".repeat(40) + "!"
        val logs = (1..200).map { id ->
            LogEntry(id, "10:00:00.000", LogLevel.I, "App", catastrophicMessage)
        }
        val tab = LogTab(
            id = "regex-timeout",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(mode = FilterMode.KEYWORD, kwText = "(a+)+$", kwRegex = true),
        )
        val regexContext = RegexEvaluationContext(matchBudgetNanos = 1L)

        val visible = visibleEntries(tab, applyFilter = true, regexContext = regexContext)

        assertTrue(visible.isEmpty())
        assertEquals(1, regexContext.timeoutCountForTesting)
    }

    @Test
    @Suppress("MagicNumber")
    fun timedOutComputeResultDoesNotPoisonLaterOperationCache() {
        val catastrophicMessage = "a".repeat(40) + "!"
        val logs = (1..20).map { id ->
            LogEntry(id, "10:00:00.000", LogLevel.I, "App", catastrophicMessage)
        }
        val tab = LogTab(
            id = "regex-timeout-cache",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(mode = FilterMode.KEYWORD, kwText = "(a+)+$", kwRegex = true),
        )
        val firstOperation = RegexEvaluationContext(matchBudgetNanos = 1L)
        val secondOperation = RegexEvaluationContext(matchBudgetNanos = 1L)

        assertTrue(computeItems(tab, applyFilter = true, regexContext = firstOperation).isEmpty())
        assertTrue(computeItems(tab, applyFilter = true, regexContext = secondOperation).isEmpty())

        assertEquals(1, firstOperation.timeoutCountForTesting)
        assertEquals(1, secondOperation.timeoutCountForTesting)
    }

    @Test
    fun levelExclusionBlocksEntry() {
        val debug = LogEntry(1, "10:00:00.000", LogLevel.D, "App", "debug message")
        val info = LogEntry(2, "10:00:00.001", LogLevel.I, "App", "info message")
        val filter = Filter(levels = setOf(LogLevel.I, LogLevel.W, LogLevel.E, LogLevel.A, LogLevel.V))

        assertFalse(passesFilter(debug, filter))
        assertTrue(passesFilter(info, filter))
    }

    @Test
    fun tagExclusionBlocksMatchingTag() {
        val spammy = LogEntry(1, "10:00:00.000", LogLevel.I, "SpammyTag", "noise")
        val allowed = LogEntry(2, "10:00:00.001", LogLevel.I, "UsefulTag", "signal")
        val filter = Filter(excludeTags = setOf("SpammyTag"))

        assertFalse(passesFilter(spammy, filter))
        assertTrue(passesFilter(allowed, filter))
    }

    @Test
    fun excludeKwPlainTextBlocksMatchingEntry() {
        val noisy = LogEntry(1, "10:00:00.000", LogLevel.I, "App", "heartbeat ping")
        val useful = LogEntry(2, "10:00:00.001", LogLevel.I, "App", "user login success")
        val filter = Filter(excludeKw = "heartbeat")

        assertFalse(passesFilter(noisy, filter))
        assertTrue(passesFilter(useful, filter))
    }

    @Test
    fun excludeKwRegexBlocksMatchingEntry() {
        val noisy = LogEntry(1, "10:00:00.000", LogLevel.I, "App", "error code 404")
        val useful = LogEntry(2, "10:00:00.001", LogLevel.I, "App", "request success")
        val filter = Filter(excludeKw = """\d{3}""", excludeKwRegex = true)

        assertFalse(passesFilter(noisy, filter))
        assertTrue(passesFilter(useful, filter))
    }

    @Test
    fun negativeRuleWithExactTagScopeBlocksOnlyThatTag() {
        val blocked = LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Network", "heartbeat")
        val otherTag = LogEntry(2, "10:00:00.001", LogLevel.I, "com.other.Network", "heartbeat")
        val filter = Filter(
            messageRules = listOf(
                MessageRule(id = "r1", include = false, pattern = "heartbeat", tag = "com.app.Network"),
            ),
        )

        assertFalse(passesFilter(blocked, filter))
        assertTrue(passesFilter(otherTag, filter))
    }

    @Test
    fun unscopedPositiveRuleAddsMatchesFromAnyTagToActiveTagFilter() {
        val matchesRule = LogEntry(1, "10:00:00.000", LogLevel.I, "com.other.Network", "ERROR logged")
        val matchesActiveTagOnly = LogEntry(2, "10:00:00.001", LogLevel.I, "com.app.Auth", "normal debug")
        val matchesNeither = LogEntry(3, "10:00:00.002", LogLevel.I, "com.other.Network", "normal debug")
        // activeTags restricts to Auth; an unscoped positive rule for ERROR adds matches from
        // any tag on top of that — it doesn't replace the tag filter's own results.
        val filter = Filter(
            activeTags = setOf("com.app.Auth"),
            messageRules = listOf(MessageRule(id = "r1", include = true, pattern = "ERROR")),
        )

        assertTrue(passesFilter(matchesRule, filter))
        assertTrue(passesFilter(matchesActiveTagOnly, filter))
        assertFalse(passesFilter(matchesNeither, filter))
    }

    @Test
    fun messageRuleRegexMatchesMessagePattern() {
        val matchesRule = LogEntry(1, "10:00:00.000", LogLevel.I, "App", "timeout after 42 ms")
        val noMatch = LogEntry(2, "10:00:00.001", LogLevel.I, "App", "timeout soon")
        val filter = Filter(
            messageRules = listOf(MessageRule(id = "r1", include = true, pattern = """timeout\s+\w+\s+\d+""", regex = true)),
        )

        assertTrue(passesFilter(matchesRule, filter))
        assertFalse(passesFilter(noMatch, filter))
    }

    @Test
    fun scopedPositiveRuleMatchesOnlyItsTagAndPattern() {
        val hit = LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Network", "ERROR occurred")
        val wrongPattern = LogEntry(2, "10:00:00.001", LogLevel.I, "com.app.Network", "request ok")
        val filter = Filter(
            messageRules = listOf(
                MessageRule(id = "r1", include = true, pattern = "ERROR", tag = "com.app.Network"),
            ),
        )

        assertTrue(passesFilter(hit, filter))
        assertFalse(passesFilter(wrongPattern, filter))
    }

    @Test
    fun scopedPositiveRuleMatchesPackagePrefixChildrenOnly() {
        val childHit = LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Network", "ERROR occurred")
        val exactHit = LogEntry(2, "10:00:00.001", LogLevel.I, "com.app", "ERROR occurred")
        val wrongPrefix = LogEntry(3, "10:00:00.002", LogLevel.I, "com.other.Network", "ERROR occurred")
        val wrongPattern = LogEntry(4, "10:00:00.003", LogLevel.I, "com.app.Auth", "request ok")
        val filter = Filter(
            messageRules = listOf(
                MessageRule(id = "r1", include = true, pattern = "ERROR", packagePrefix = "com.app"),
            ),
        )

        assertTrue(passesFilter(childHit, filter))
        assertTrue(passesFilter(exactHit, filter))
        assertFalse(passesFilter(wrongPrefix, filter))
        assertFalse(passesFilter(wrongPattern, filter))
    }

    @Test
    fun pidTidFilterAllowsMatchingPidAndBlocksOthers() {
        val matching = LogEntry(1, "10:00:00.000", LogLevel.I, "App", "msg", pid = 1234)
        val notMatching = LogEntry(2, "10:00:00.001", LogLevel.I, "App", "msg", pid = 5678)
        val filter = Filter(pidTidFilter = "1234")

        assertTrue(passesFilter(matching, filter))
        assertFalse(passesFilter(notMatching, filter))
    }

    @Test
    fun pidTidFilterWorksWithCommaAndSpaceSeparators() {
        val byPid = LogEntry(1, "10:00:00.000", LogLevel.I, "App", "msg", pid = 1234)
        val byTid = LogEntry(2, "10:00:00.001", LogLevel.I, "App", "msg", tid = 5678)
        val excluded = LogEntry(3, "10:00:00.002", LogLevel.I, "App", "msg", pid = 9999)

        assertTrue(passesFilter(byPid, Filter(pidTidFilter = "1234 5678")))
        assertTrue(passesFilter(byTid, Filter(pidTidFilter = "1234 5678")))
        assertFalse(passesFilter(excluded, Filter(pidTidFilter = "1234 5678")))
        assertTrue(passesFilter(byPid, Filter(pidTidFilter = "1234,5678")))
        assertTrue(passesFilter(byTid, Filter(pidTidFilter = "1234,5678")))
    }

    @Test
    fun entryOutOfScopeOfScopedPositiveRuleFallsThroughToTagFilter() {
        // Rule covers com.app.Network; both entries have different tags so they fall through to
        // the tag filter, which shows only com.app.Auth.
        val inActiveTag = LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Auth", "login")
        val notInActiveTag = LogEntry(2, "10:00:00.001", LogLevel.I, "com.other.UI", "data")
        val filter = Filter(
            activeTags = setOf("com.app.Auth"),
            messageRules = listOf(
                MessageRule(id = "r1", include = true, pattern = "ERROR", tag = "com.app.Network"),
            ),
        )

        assertTrue(passesFilter(inActiveTag, filter))
        assertFalse(passesFilter(notInActiveTag, filter))
    }

    // ── computeItems ──────────────────────────────────────────────────────────

    @Test
    fun computeItemsApplyFilterFalseShowsEntriesExcludedByFilter() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "TagA", "first"),
            LogEntry(2, "10:00:00.001", LogLevel.I, "TagB", "second"),
            LogEntry(3, "10:00:00.002", LogLevel.I, "TagA", "third"),
        )
        val tab = LogTab(
            id = "t1",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(activeTags = setOf("TagA")),
        )

        val filtered = computeItems(tab, applyFilter = true)
        val unfiltered = computeItems(tab, applyFilter = false)

        assertEquals(listOf(1, 3), filtered.filterIsInstance<LogItem.Row>().map { it.entry.id })
        assertEquals(listOf(1, 2, 3), unfiltered.filterIsInstance<LogItem.Row>().map { it.entry.id })
    }

    @Test
    fun computeItemsCollapsedSequenceShowsHeaderWithoutChildren() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "flow start"),
            LogEntry(2, "10:00:00.001", LogLevel.I, "App", "child one"),
            LogEntry(3, "10:00:00.002", LogLevel.I, "App", "child two"),
        )
        val seq = SequenceDef("flow", "flow start", priority = 1, color = Color.Blue, tag = "App")
        val tab = LogTab(
            id = "t1",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(sequences = listOf(seq)),
            expanded = emptySet(),
        )

        val items = computeItems(tab, applyFilter = false)

        val header = items.filterIsInstance<LogItem.SeqHeader>().single()
        assertEquals(1, header.entry.id)
        assertEquals(false, header.expanded)
        assertEquals(2, header.count)
        assertTrue(items.filterIsInstance<LogItem.Row>().isEmpty())
    }

    @Test
    fun computeItemsExpandedSequenceHeaderAtIndent0ChildrenAtIndent1() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "flow start"),
            LogEntry(2, "10:00:00.001", LogLevel.I, "App", "child"),
        )
        val seq = SequenceDef("flow", "flow start", priority = 1, color = Color.Blue, tag = "App")
        val tab = LogTab(
            id = "t1",
            filename = "test.log",
            logData = logs,
            rmap = logs.associateBy { it.id },
            filter = Filter(sequences = listOf(seq)),
            expanded = setOf("sg_flow_1"),
        )

        val items = computeItems(tab, applyFilter = false)

        val header = items.filterIsInstance<LogItem.SeqHeader>().single()
        val child = items.filterIsInstance<LogItem.Row>().single()
        assertEquals(0, header.indent)
        assertEquals(1, child.indent)
        assertEquals(2, child.entry.id)
    }
}
