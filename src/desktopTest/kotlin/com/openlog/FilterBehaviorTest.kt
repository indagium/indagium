package com.openlog

import androidx.compose.ui.graphics.Color
import com.openlog.model.Filter
import com.openlog.model.Highlighter
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.model.MessageRule
import com.openlog.ui.buildFullLineAnnotation
import com.openlog.utils.passesFilter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilterBehaviorTest {
    @Test
    fun activeTagNarrowsWithinPackagePrefix() {
        val selected = LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.auth.Login", "shown")
        val sibling = LogEntry(2, "10:00:00.001", LogLevel.I, "com.app.net.Http", "hidden")
        val otherPackage = LogEntry(3, "10:00:00.002", LogLevel.I, "com.other.auth.Login", "hidden")
        val filter = Filter(
            pkgPrefixes = setOf("com.app"),
            activeTags = setOf("com.app.auth.Login"),
        )

        assertTrue(passesFilter(selected, filter))
        assertFalse(passesFilter(sibling, filter))
        assertFalse(passesFilter(otherPackage, filter))
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
}
