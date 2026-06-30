package com.openlog

import androidx.compose.ui.graphics.Color
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
import com.openlog.utils.computeItems
import com.openlog.utils.passesFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilterBehaviorTest {
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
        val otherTag = LogEntry(3, "10:00:00.002", LogLevel.I, "com.app.Auth", "login complete")
        val filter = Filter(
            messageRules = listOf(
                MessageRule(id = "r1", include = true, pattern = "request", tag = "com.app.Network"),
            ),
        )

        assertTrue(passesFilter(desired, filter))
        assertFalse(passesFilter(spam, filter))
        assertTrue(passesFilter(otherTag, filter))
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
    fun invalidRegexFilterDoesNotThrowOrMatch() {
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Network", "request complete")
        val filter = Filter(mode = FilterMode.KEYWORD, kwText = "[", kwRegex = true)

        assertFalse(passesFilter(entry, filter))
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
    fun unscopedPositiveRuleOverridesTagFilter() {
        val matchesRule = LogEntry(1, "10:00:00.000", LogLevel.I, "com.other.Network", "ERROR logged")
        val noMatch = LogEntry(2, "10:00:00.001", LogLevel.I, "com.other.Network", "normal debug")
        // activeTags restricts to Auth, but unscoped positive rule for ERROR overrides that
        val filter = Filter(
            activeTags = setOf("com.app.Auth"),
            messageRules = listOf(MessageRule(id = "r1", include = true, pattern = "ERROR")),
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
