@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.indagium.model.AppSettings
import com.indagium.model.TabSizeMode
import com.indagium.model.TabStripStyle
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The small link mark a tab draws while it is an open member of a shared note: in every strip style, without changing FIXED widths. */
@OptIn(ExperimentalTestApi::class)
class SharedNoteTabMarkUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private fun setup(vertical: Boolean = false, configure: (AppState) -> Unit = {}) {
        directory = createTempDirectory("shared-note-tab-mark").toFile()
        state = AppState(File(directory, "state.cache"), notesDir = File(directory, "notes"), autoExportNotes = false)
        // Mid-length names, so no FIT width is clamped to its minimum or maximum.
        state.tabs = listOf("a", "b", "c").map { id ->
            mkTab(id, "mid-name-$id.log", emptyList()).copy(sourcePath = "/logs/$id/mid-name-$id.log")
        }
        state.activeTabId = "a"
        configure(state)
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                if (vertical) {
                    Box(Modifier.size(220.dp, 400.dp)) { TabStrip(state, ChromeOrientation.VERTICAL, Modifier.size(220.dp, 400.dp)) }
                } else {
                    Box(Modifier.width(1400.dp)) { TabStrip(state, ChromeOrientation.HORIZONTAL) }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun style(style: TabStripStyle) {
        state.updateSettings { it.copy(tabStripStyle = style) }
        rule.waitForIdle()
    }

    private fun mark(id: String) = rule.onAllNodesWithTag("tab-shared-$id", useUnmergedTree = true)

    private fun width(id: String): Float = rule.onNodeWithTag("tab-$id").getBoundsInRoot().width.value

    private fun share() = assertNotNull(state.createSharedNote("Pairing", listOf("a", "b"))).also { rule.waitForIdle() }

    @Test
    fun onlyTheOpenMembersOfASharedNoteShowTheMarkInEveryStyle() {
        setup()
        TabStripStyle.entries.forEach { s ->
            style(s)
            assertEquals(0, mark("a").fetchSemanticsNodes().size, "$s: no mark before sharing")
        }
        share()
        TabStripStyle.entries.forEach { s ->
            style(s)
            assertEquals(1, mark("a").fetchSemanticsNodes().size, "$s: member a")
            assertEquals(1, mark("b").fetchSemanticsNodes().size, "$s: member b")
            assertEquals(0, mark("c").fetchSemanticsNodes().size, "$s: c is not a member")
            rule.onNodeWithTag("tab-shared-a", useUnmergedTree = true).assertContentDescriptionEquals("In a shared note")
        }
    }

    @Test
    fun aClosedMemberLeavesNoMarkOnTheOtherTabs() {
        setup()
        share()
        state.closeTab("b")
        rule.waitForIdle()

        assertEquals(1, mark("a").fetchSemanticsNodes().size)
        assertFalse(rule.onAllNodesWithTag("tab-b", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun fixedWidthsAreTheSameWithAndWithoutTheMark() {
        setup()
        TabStripStyle.entries.forEach { s ->
            style(s)
            val before = listOf("a", "b", "c").map(::width)
            assertEquals(before[0], before[1], 0.01f, "$s: equal shares before")
            share()
            val after = listOf("a", "b", "c").map(::width)
            assertEquals(before, after, "$s: sharing does not change a fixed tab's width")
            state.deleteSharedNote(state.sharedNoteHosts.single().id)
            rule.waitForIdle()
        }
    }

    @Test
    fun fitWidthsGrowByTheMarkAndItNeverOverlapsTheLabel() {
        setup { it.updateSettings { s -> s.copy(tabSizeMode = TabSizeMode.FIT) } }
        TabStripStyle.entries.forEach { s ->
            style(s)
            val before = listOf("a", "b", "c").map(::width)
            share()
            val after = listOf("a", "b", "c").map(::width)
            val growth = SHARED_NOTE_MARK_DP + 7f
            assertEquals(before[0] + growth, after[0], 0.6f, "$s: member a grows by the mark")
            assertEquals(before[1] + growth, after[1], 0.6f, "$s: member b grows by the mark")
            assertEquals(before[2], after[2], 0.01f, "$s: a non-member is unchanged")
            val markBox = rule.onNodeWithTag("tab-shared-a", useUnmergedTree = true).getBoundsInRoot()
            val label = rule.onNodeWithTag("tab-label-a", useUnmergedTree = true).getBoundsInRoot()
            assertTrue(markBox.right <= label.left + 0.5.dp, "$s: the mark ends before the label starts ($markBox, $label)")
            assertTrue(label.height > 0.dp)
            state.deleteSharedNote(state.sharedNoteHosts.single().id)
            rule.waitForIdle()
        }
    }

    @Test
    fun theVerticalStripMarksTheMembersToo() {
        setup(vertical = true)
        share()

        assertEquals(1, mark("a").fetchSemanticsNodes().size)
        assertEquals(1, mark("b").fetchSemanticsNodes().size)
        assertEquals(0, mark("c").fetchSemanticsNodes().size)
        TabStripStyle.entries.forEach { s ->
            style(s)
            assertEquals(1, mark("a").fetchSemanticsNodes().size, "$s: vertical member a")
        }
    }

    // ── The pure maths ───────────────────────────────────────────────────────────────────────────

    @Test
    fun theFitWidthMathsAddsTheMarkAndAGapOnlyForAMember() {
        val plain = TabAppearance.Default
        val member = plain.copy(inSharedNote = true)

        assertEquals(tabFitContentDp("a-label.log", plain) + SHARED_NOTE_MARK_DP + 7f, tabFitContentDp("a-label.log", member), 0.001f)
    }

    @Test
    fun theDescriptorCarriesTheMemberFlag() {
        val tab = mkTab("a", "a.log", emptyList())

        assertFalse(logTabDescriptor(tab, listOf(tab), AppSettings()).appearance.inSharedNote)
        assertTrue(logTabDescriptor(tab, listOf(tab), AppSettings(), inSharedNote = true).appearance.inSharedNote)
    }
}
