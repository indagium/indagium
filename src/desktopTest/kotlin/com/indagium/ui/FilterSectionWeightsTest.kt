package com.indagium.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilterSectionWeightsTest {
    @Test
    fun redistributeKeepsTheWeightSumConstant() {
        val (a, b) = redistributeSectionWeights(wA = 1f, wB = 2f, hA = 100f, hB = 200f, deltaDp = 40f)
        assertEquals(3f, a + b, 1e-4f)
        // 140 of 300 dp now belongs to the upper section.
        assertEquals(3f * 140f / 300f, a, 1e-4f)
    }

    @Test
    fun redistributeClampsAtTheMinimumHeightOnBothSides() {
        val (aDown, bDown) = redistributeSectionWeights(1f, 1f, hA = 100f, hB = 100f, deltaDp = -500f, minDp = 48f)
        assertEquals(2f * 48f / 200f, aDown, 1e-4f)
        assertEquals(2f, aDown + bDown, 1e-4f)

        val (aUp, bUp) = redistributeSectionWeights(1f, 1f, hA = 100f, hB = 100f, deltaDp = 500f, minDp = 48f)
        assertEquals(2f * (200f - 48f) / 200f, aUp, 1e-4f)
        assertEquals(2f, aUp + bUp, 1e-4f)
    }

    @Test
    fun redistributeReturnsTheInputUnchangedForAZeroDeltaOrTooLittleRoom() {
        assertEquals(0.7f to 1.3f, redistributeSectionWeights(0.7f, 1.3f, 120f, 80f, 0f))
        // Less than 2 * minDp between the pair: nothing sensible to do.
        assertEquals(1f to 1f, redistributeSectionWeights(1f, 1f, 30f, 30f, 10f))
    }

    @Test
    fun defaultsGiveSequencesASmallerShare() {
        val state = FilterPanelUiState()
        assertEquals(0.7f, state.weightOf(FilterSection.SEQUENCES))
        assertEquals(1f, state.weightOf(FilterSection.TAGS))
    }

    @Test
    fun weightsRoundTripThroughTheToken() {
        val original = FilterPanelUiState().apply {
            sectionWeights[FilterSection.TAGS] = 2.5f
            sectionWeights[FilterSection.ISSUES] = 0.25f
            sectionWeights[FilterSection.SEQUENCES] = 3f
        }
        val restored = FilterPanelUiState().apply { restoreFilterPanelToken(original.filterPanelToken()) }
        FilterSection.entries.forEach { section ->
            assertEquals(original.weightOf(section), restored.weightOf(section), "$section")
        }
    }

    @Test
    fun aLegacyThirteenFieldTokenRestoresTheDefaultWeights() {
        // Fields 0-6 are the expand flags, 7-10 are crashExpanded, crashCategory, sfCollapsedFolderIds
        // and sfFavoritesExpanded, 11-12 are logCompositionExpanded and highlightersExpanded.
        val legacy = legacyTokenFields(
            "true", "true", "true", "true", "true", "true", "true",
            "true", "ALL", "", "true",
            "false", "true",
        )
        val restored = FilterPanelUiState().apply { sectionWeights[FilterSection.TAGS] = 9f }
        restored.restoreFilterPanelToken(legacy)
        FilterSection.entries.forEach { section ->
            assertEquals(section.defaultWeight, restored.weightOf(section), "$section")
        }
    }

    @Test
    fun aGarbageWeightsFieldIsIgnoredAndOutOfRangeValuesAreClamped() {
        val base = FilterPanelUiState().filterPanelToken().split("|").take(13)

        fun withField13(value: String): String = (base + value.legacyFieldToken()).joinToString("|")

        val garbage = FilterPanelUiState().apply { restoreFilterPanelToken(withField13("not a weight list ::: ,,")) }
        FilterSection.entries.forEach { assertEquals(it.defaultWeight, garbage.weightOf(it), "$it") }

        val mixed = FilterPanelUiState().apply {
            restoreFilterPanelToken(withField13("TAGS:2,NOPE:5,ISSUES:abc,SAVED_FILTERS:0,SEQUENCES:1e9,HIGHLIGHTERS:NaN"))
        }
        assertEquals(2f, mixed.weightOf(FilterSection.TAGS))
        assertEquals(FilterSection.ISSUES.defaultWeight, mixed.weightOf(FilterSection.ISSUES))
        assertEquals(FILTER_SECTION_MIN_WEIGHT, mixed.weightOf(FilterSection.SAVED_FILTERS))
        assertEquals(FILTER_SECTION_MAX_WEIGHT, mixed.weightOf(FilterSection.SEQUENCES))
        assertEquals(FilterSection.HIGHLIGHTERS.defaultWeight, mixed.weightOf(FilterSection.HIGHLIGHTERS))
        assertTrue(mixed.weightOf(FilterSection.MESSAGE_RULES) > 0f)
    }

    // Mirrors the private field encoding in ui/AutosaveCodec.kt: b64 for a non-empty value, the "~"
    // sentinel for an empty one (same trick as FilterPanelTokenTest).
    private fun String.legacyFieldToken(): String = if (isEmpty()) "~" else b64()

    private fun legacyTokenFields(vararg values: String): String = values.joinToString("|") { it.legacyFieldToken() }
}
