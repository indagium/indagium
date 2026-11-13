package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.AppSettings
import com.indagium.model.ErrorHighlightStyle
import com.indagium.model.Filter
import com.indagium.model.Highlighter
import com.indagium.model.LogLevel
import com.indagium.model.effectiveErrorHighlightStyle
import com.indagium.ui.highlighterEnabledCount
import com.indagium.ui.isVocabularyDuplicate
import com.indagium.ui.mkTab
import com.indagium.ui.vocabularyHelperText
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorHighlightStyleTest {
    @Test
    fun tabsInheritGlobalChangesUntilOverriddenAndResetReturnsToInheritance() {
        val global = ErrorHighlightStyle(color = Color.Red)
        val changedGlobal = ErrorHighlightStyle(color = Color.Blue)
        val inherited = mkTab("inherited", "a.log", emptyList())
        val overridden = inherited.copy(errorHighlightStyle = ErrorHighlightStyle(color = Color.Green))

        assertEquals(global, inherited.effectiveErrorHighlightStyle(global))
        assertEquals(changedGlobal, inherited.effectiveErrorHighlightStyle(changedGlobal))
        assertEquals(ErrorHighlightStyle(color = Color.Green), overridden.effectiveErrorHighlightStyle(changedGlobal))
        assertEquals(changedGlobal, overridden.copy(errorHighlightStyle = null).effectiveErrorHighlightStyle(changedGlobal))
    }

    @Test
    fun compareFilterCopiesKeepTheSourceTabsAppearance() {
        val global = AppSettings().errorHighlightStyle
        val tab = mkTab("compare", "a.log", emptyList())
        val overridden = tab.copy(errorHighlightStyle = ErrorHighlightStyle(color = Color.Blue))

        assertEquals(global, tab.copy(filter = Filter()).effectiveErrorHighlightStyle(global))
        assertEquals(Color.Blue, overridden.copy(filter = Filter()).effectiveErrorHighlightStyle(global).color)
    }

    @Test
    fun defaultErrorColorIsTheErrorLevelColor() {
        assertEquals(LogLevel.E.defaultColor, ErrorHighlightStyle().color)
    }

    @Test
    fun enabledCountCountsHighlightersAndTheRegexRowButNotTheErrorRow() {
        fun highlighter(id: String, on: Boolean) =
            Highlighter(id = id, pattern = id, regex = false, color = Color.Red, on = on)
        val filter = Filter(highlighters = listOf(highlighter("a", true), highlighter("b", false)))

        assertEquals(1, highlighterEnabledCount(filter, regexHighlightAvailable = false))
        assertEquals(2, highlighterEnabledCount(filter, regexHighlightAvailable = true))
        assertEquals(1, highlighterEnabledCount(filter.copy(kwHighlightEnabled = false), regexHighlightAvailable = true))
    }

    @Test
    fun vocabularyHelperLineShowsTheCountWithTheRightPluralOrTheDuplicateWarning() {
        assertEquals("No saved words", vocabularyHelperText("", emptyList()))
        assertEquals("1 word", vocabularyHelperText("", listOf("Indagium")))
        assertEquals("2 words", vocabularyHelperText("", listOf("Indagium", "Logcat")))
        assertEquals("2 words", vocabularyHelperText("new", listOf("Indagium", "Logcat")))
        assertEquals("Already in your vocabulary.", vocabularyHelperText("  indagium ", listOf("Indagium", "Logcat")))
        assertEquals(true, isVocabularyDuplicate("LOGCAT", listOf("Indagium", "Logcat")))
        assertEquals(false, isVocabularyDuplicate("two words", listOf("Indagium")))
    }
}
