package com.indagium

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.indagium.model.Filter
import com.indagium.model.Highlighter
import com.indagium.model.LogAnalysis
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.ui.FilterBar
import com.indagium.ui.FilterBarActions
import com.indagium.ui.FilterBarModel
import com.indagium.ui.FilterPanelSection
import com.indagium.ui.FilterPanelUiState
import com.indagium.ui.HL_COLORS
import com.indagium.ui.HighlighterActions
import com.indagium.ui.HighlighterSection
import com.indagium.ui.HighlighterSectionState
import com.indagium.ui.residualChipSectionsToReveal
import com.indagium.ui.revealSections
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Residual filter-bar chip -> which Filters-panel sections get expanded and scrolled to. */
@OptIn(ExperimentalTestApi::class)
class FilterPanelRevealTest {
    @get:Rule
    val rule = createComposeRule()

    private fun hl(id: String, pattern: String, on: Boolean = true) =
        Highlighter(id = id, pattern = pattern, regex = false, color = Color.Red, on = on)

    @Test
    fun defaultFilterRevealsNothing() {
        assertEquals(emptySet(), residualChipSectionsToReveal(Filter()))
    }

    @Test
    fun enabledHighlightersRevealTheHighlightersSection() {
        val filter = Filter(highlighters = listOf(hl("h1", "a"), hl("h2", "b", on = false)))
        assertEquals(setOf(FilterPanelSection.HIGHLIGHTERS), residualChipSectionsToReveal(filter))
    }

    @Test
    fun onlyDisabledHighlightersRevealNothing() {
        assertEquals(emptySet(), residualChipSectionsToReveal(Filter(highlighters = listOf(hl("h1", "a", on = false)))))
    }

    @Test
    fun nonDefaultLevelsRevealTheLogLevelSection() {
        val filter = Filter(levels = setOf(LogLevel.W, LogLevel.E))
        assertEquals(setOf(FilterPanelSection.LOG_LEVEL), residualChipSectionsToReveal(filter))
    }

    @Test
    fun excludeKeywordAndPidTidHaveNoPanelSectionToReveal() {
        assertEquals(emptySet(), residualChipSectionsToReveal(Filter(excludeKw = "spam", pidTidFilter = "1234")))
    }

    @Test
    fun bothRevealedInPanelOrder() {
        val filter = Filter(levels = setOf(LogLevel.E), highlighters = listOf(hl("h1", "a")))
        assertEquals(
            listOf(FilterPanelSection.HIGHLIGHTERS, FilterPanelSection.LOG_LEVEL),
            residualChipSectionsToReveal(filter).toList(),
        )
    }

    @Test
    fun revealSectionsReachesTheEightActiveEndStateAndRequestsScroll() {
        val state = FilterPanelUiState().apply {
            highlightersExpanded = false
            hlListExpanded = false
            lvlExpanded = false
        }
        state.revealSections(setOf(FilterPanelSection.HIGHLIGHTERS, FilterPanelSection.LOG_LEVEL))
        assertTrue(state.highlightersExpanded)
        assertTrue(state.hlListExpanded)
        assertTrue(state.lvlExpanded)
        assertEquals(FilterPanelSection.HIGHLIGHTERS, state.revealSection)
    }

    @Test
    fun revealingNothingLeavesStateUntouched() {
        val state = FilterPanelUiState().apply { highlightersExpanded = false; lvlExpanded = false }
        state.revealSections(emptySet())
        assertEquals(false, state.highlightersExpanded)
        assertEquals(false, state.lvlExpanded)
        assertNull(state.revealSection)
    }

    @Test
    fun clickingTheResidualChipExpandsTheCollapsedHighlightersList() {
        val fpState = FilterPanelUiState().apply {
            highlightersExpanded = false
            hlListExpanded = false
        }
        val filter = Filter(highlighters = listOf(hl("h1", "needle-pattern"), hl("h2", "other-pattern")))
        val tab = LogTab(
            id = "reveal-ui", filename = "reveal.log", logData = emptyList(), rmap = emptyMap(),
            filter = filter, analysis = LogAnalysis(pending = false),
        )
        var focusRequests = 0
        rule.setContent {
            var current by remember { mutableStateOf(tab) }
            Column {
                FilterBar(
                    tab = current,
                    model = FilterBarModel(emptyList(), emptyMap(), 4, emptyList()),
                    actions = noOpBarActions(onOpenFilterPanel = {
                        fpState.revealSections(residualChipSectionsToReveal(current.filter))
                        focusRequests++
                    }),
                    logFocusRequester = null,
                )
                HighlighterSection(
                    tab = current, fpState = fpState, sectionState = HighlighterSectionState(),
                    actions = noOpHighlighterActions(), sortedTags = emptyList(), tagUsage = emptyMap(),
                    mostUsedTagLimit = 5, filterListRows = 5, newHlPat = "", newHlRx = false,
                    newHlColor = HL_COLORS.first(), inputFocusRequester = FocusRequester(),
                    onInputFocusedChange = {}, onTabOut = {}, onReclaimFocus = {}, onUiStateChanged = {},
                )
            }
        }
        rule.onNodeWithTag("highlighters-list-toggle").assertExists()
        rule.onNodeWithText("needle-pattern", substring = true).assertDoesNotExist()

        rule.onNodeWithText("+ 2 highlighters").performClick()

        rule.onNodeWithText("needle-pattern", substring = true).assertExists()
        assertEquals(1, focusRequests)
    }

    private fun noOpBarActions(onOpenFilterPanel: () -> Unit) = FilterBarActions(
        onSetFilterMode = {}, onStartRegexSearch = {}, onToggleTag = {}, onToggleExcludeTag = {},
        onAddPkgPrefix = {}, onRemovePkgPrefix = {}, onAddExcludePkgPrefix = {}, onRemoveExcludePkgPrefix = {},
        onSetKwInTag = {}, onToggleKwInTagRegex = {}, onSetKw = {},
        onAddMessageRule = { _, _, _, _, _, _ -> }, onRemoveMessageRule = {},
        onRememberRegexPattern = {}, onClearRegexHistory = {}, onOpenFilterPanel = onOpenFilterPanel,
    )

    private fun noOpHighlighterActions() = HighlighterActions(
        onAdd = { _, _, _, _, _, _, _, _, _, _, _ -> },
        onRemove = {}, onToggle = {}, onSetColor = { _, _ -> }, onUpdate = { _, _ -> },
        onSetNewPattern = {}, onSetNewRegex = {}, onSetNewColor = {},
        onSetKwHighlightEnabled = {}, onSetKwHighlightColor = {}, onRequestMessageComposition = {},
        customColors = emptyList(), paletteColumns = 10,
        onSaveCustomColor = {}, onDeleteCustomColor = {}, onPaletteColumnsChange = {},
    )
}
