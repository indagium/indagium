@file:Suppress("MagicNumber")

package com.indagium

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.SharedNoteInfo
import com.indagium.ui.AppState
import com.indagium.ui.ChromeOrientation
import com.indagium.ui.LIGHT_THEME
import com.indagium.ui.LocalTheme
import com.indagium.ui.TabStrip
import com.indagium.ui.mkTab
import com.indagium.ui.sharedNoteHostTab
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals

/** The tab strip is built from AppState.tabs, so a shared-note host (a separate list) must never appear in it. */
@OptIn(ExperimentalTestApi::class)
class SharedNoteTabStripUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theTabStripShowsOnlyOrdinaryTabsNotSharedNoteHosts() {
        val dir = createTempDirectory("shared-note-strip").toFile()
        val state = AppState(File(dir, "state.cache"), notesDir = File(dir, "notes"))
        state.tabs = listOf(mkTab("a", "alpha.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "x"))))
        state.activeTabId = "a"
        state.addSharedNoteHost(sharedNoteHostTab(SharedNoteInfo("shared-1", "ZZ pairing note", listOf("a"))))

        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                TabStrip(state, ChromeOrientation.HORIZONTAL)
            }
        }
        rule.waitForIdle()

        assertEquals(1, rule.onAllNodesWithText("alpha.log", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("ZZ pairing note", substring = true).fetchSemanticsNodes().size)
        dir.deleteRecursively()
    }
}
