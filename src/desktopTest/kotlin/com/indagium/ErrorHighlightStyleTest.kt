package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.AppSettings
import com.indagium.model.ErrorHighlightStyle
import com.indagium.model.Filter
import com.indagium.model.effectiveErrorHighlightStyle
import com.indagium.ui.mkTab
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
}
