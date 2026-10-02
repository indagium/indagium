package com.indagium.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Compose smoke test for the Filters panel's splitter slots: two expanded sections share the column
 * by weight, a capped list inside them renders uncapped (no nested-scroll infinite-height crash),
 * and a divider drag re-splits the weights.
 */
@OptIn(ExperimentalTestApi::class)
class FilterSectionSlotUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun expandedSectionsShareHeightByWeightAndAreResizedByTheDivider() {
        val fpState = FilterPanelUiState().apply {
            sectionWeights[FilterSection.TAGS] = 1f
            sectionWeights[FilterSection.ISSUES] = 3f
        }
        val heights = HashMap<FilterSection, Int>()
        val layout = FilterSectionLayout(
            fpState = fpState,
            expandedSections = setOf(FilterSection.TAGS, FilterSection.ISSUES),
            bodyHeightsPx = heights,
            density = 1f,
            onDragEnd = {},
        )
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides WARM_PAPER) {
                Column(Modifier.width(240.dp).height(640.dp)) {
                    FilterSectionBody(layout, FilterSection.TAGS, expanded = true) {
                        // A list that would normally cap at 60dp and scroll itself.
                        BoundedScrollBoxDp(60) { repeat(60) { AppText("tag row $it") } }
                    }
                    FilterSectionDivider(layout, FilterSection.TAGS, expanded = true)
                    FilterSectionBody(layout, FilterSection.ISSUES, expanded = true) {
                        BoundedScrollBoxDp(60) { repeat(60) { AppText("issue row $it") } }
                    }
                }
            }
        }
        rule.waitForIdle()

        val tags = assertNotNull(heights[FilterSection.TAGS])
        val issues = assertNotNull(heights[FilterSection.ISSUES])
        assertTrue(tags > 60, "the uncapped list must not stay at its 60dp cap, was $tags")
        assertEquals(3.0, issues.toDouble() / tags, 0.05, "weights 1:3 must split the height 1:3")

        layout.dragDivider(FilterSection.TAGS, FilterSection.ISSUES, deltaDp = 100f)
        rule.waitForIdle()

        assertEquals(4f, fpState.weightOf(FilterSection.TAGS) + fpState.weightOf(FilterSection.ISSUES), 1e-4f)
        assertTrue(assertNotNull(heights[FilterSection.TAGS]) > tags, "dragging down must grow the upper section")
        assertEquals(tags + issues, heights.getValue(FilterSection.TAGS) + heights.getValue(FilterSection.ISSUES), "total is constant")
    }
}
