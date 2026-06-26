package com.openlog

import com.openlog.ui.displayTagForPrefix
import com.openlog.ui.packagePrefixCandidates
import com.openlog.ui.tagCandidates
import kotlin.test.Test
import kotlin.test.assertEquals

class TagSuggestionTest {
    private val tags = listOf(
        "com.app.auth.Login",
        "com.app.net.Http",
        "com.other.auth.Login",
        "ActivityManager",
    )

    @Test
    fun hidesDefaultTagSuggestionsWithoutPrefixSearchOrUsage() {
        assertEquals(emptyList(), tagCandidates(tags, "", emptySet(), emptySet(), emptyMap(), mostUsedLimit = 5))
    }

    @Test
    fun searchCanFindAnyTagEvenWithoutPrefix() {
        assertEquals(listOf("com.app.net.Http"), tagCandidates(tags, "net", emptySet(), emptySet(), emptyMap(), mostUsedLimit = 5))
    }

    @Test
    fun prefixShowsMatchingTagsAndDisplayKeepsPackageContext() {
        val candidates = tagCandidates(tags, "", emptySet(), setOf("com.app"), emptyMap(), mostUsedLimit = 5)

        assertEquals(listOf("com.app.auth.Login", "com.app.net.Http"), candidates)
        assertEquals("auth.Login" to "com.app", displayTagForPrefix("com.app.auth.Login", setOf("com.app")))
    }

    @Test
    fun frequentlyUsedTagsCanAppearWithoutPrefix() {
        val candidates = tagCandidates(
            tags + listOf("FrequentA", "FrequentB", "FrequentC", "FrequentD", "FrequentE", "FrequentF"),
            "",
            emptySet(),
            emptySet(),
            mapOf(
                "FrequentA" to 10,
                "FrequentB" to 9,
                "FrequentC" to 8,
                "FrequentD" to 7,
                "FrequentE" to 6,
                "FrequentF" to 5,
                "ActivityManager" to 3,
            ),
            mostUsedLimit = 5,
        )

        assertEquals(listOf("FrequentA", "FrequentB", "FrequentC", "FrequentD", "FrequentE"), candidates)
    }

    @Test
    fun customMostUsedLimitControlsDefaultTagCount() {
        val candidates = tagCandidates(
            tags + listOf("FrequentA", "FrequentB", "FrequentC"),
            "",
            emptySet(),
            emptySet(),
            mapOf("FrequentA" to 10, "FrequentB" to 9, "FrequentC" to 8),
            mostUsedLimit = 2,
        )

        assertEquals(listOf("FrequentA", "FrequentB"), candidates)
    }

    @Test
    fun activeTagsAreNotDuplicatedInDefaultSuggestionResults() {
        val candidates = tagCandidates(
            tags + listOf("FrequentA", "FrequentB", "FrequentC", "FrequentD", "FrequentE", "FrequentF"),
            "",
            selectedTags = setOf("FrequentA"),
            packagePrefixes = emptySet(),
            tagUsage = mapOf(
                "FrequentA" to 10,
                "FrequentB" to 9,
                "FrequentC" to 8,
                "FrequentD" to 7,
                "FrequentE" to 6,
                "FrequentF" to 5,
            ),
            mostUsedLimit = 5,
        )

        assertEquals(listOf("FrequentB", "FrequentC", "FrequentD", "FrequentE", "FrequentF"), candidates)
    }

    @Test
    fun packagePrefixDefaultSuggestionsRespectMostUsedLimit() {
        val prefixTags = listOf(
            "com.app.A",
            "com.app.B",
            "com.app.C",
            "com.app.D",
            "com.app.E",
            "com.app.F",
        )

        val candidates = tagCandidates(
            sortedTags = prefixTags,
            search = "",
            selectedTags = setOf("com.app.A"),
            packagePrefixes = setOf("com.app"),
            tagUsage = mapOf(
                "com.app.A" to 10,
                "com.app.B" to 9,
                "com.app.C" to 8,
                "com.app.D" to 7,
                "com.app.E" to 6,
                "com.app.F" to 5,
            ),
            mostUsedLimit = 3,
        )

        assertEquals(listOf("com.app.B", "com.app.C", "com.app.D"), candidates)
    }

    @Test
    fun segmentSearchCanSuggestPackagePrefixFirst() {
        val candidates = packagePrefixCandidates(tags, "auth")

        assertEquals(listOf("com.app.auth", "com.other.auth"), candidates)
    }
}
