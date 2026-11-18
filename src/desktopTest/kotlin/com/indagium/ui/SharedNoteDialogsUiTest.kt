@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.indagium.model.AnnBlock
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The New shared note / Share note dialog and the dialogs the Shared badge's popover opens. */
@OptIn(ExperimentalTestApi::class)
class SharedNoteDialogsUiTest : SharedNoteUiTestBase() {
    private fun openShareDialog(existing: Boolean, tabId: String = "a") {
        state.sharedNoteDialog = SharedNoteDialogRequest.Share(tabId, shareExistingNote = existing)
        rule.waitForIdle()
    }

    @Test
    fun theDialogListsThisTabFirstTickedThenTheOthersWithTheirFormat() {
        setup()
        state.activeTabId = "b"
        rule.waitForIdle()
        openShareDialog(existing = false, tabId = "b")

        assertTrue(textExists("New shared note"))
        assertTrue(textExists("Lines you add from any of these tabs go into this one note."))
        assertTrue(textExists("Tabs can be added or removed later from the note's Shared badge."))
        assertEquals(1, rule.onAllNodesWithText("This tab · ", substring = true).fetchSemanticsNodes().size)
        tag(sharedNoteTabRowTag("b")).assertIsOn()
        tag(sharedNoteTabRowTag("a")).assertIsOff()
        tag(sharedNoteTabRowTag("c")).assertIsOff()
        val order = listOf("b", "a", "c").map { id ->
            id to rule.onNodeWithTag(sharedNoteTabRowTag(id)).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(listOf("b", "a", "c"), order.sortedBy { it.second }.map { it.first }, "this tab is listed first, the others keep their order")
    }

    @Test
    fun aTabRowTogglesFromAnyPointOfItsWholeWidth() {
        setup()
        openShareDialog(existing = false)
        val row = tag(sharedNoteTabRowTag("b"))
        val bounds = row.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width > 300f, "the toggle is the whole row, not just the checkbox and the label")
        row.performMouseInput { click(Offset(2f, center.y)) }
        rule.waitForIdle()
        row.assertIsOn()
        row.performMouseInput { click(Offset(width - 2f, center.y)) }
        rule.waitForIdle()
        row.assertIsOff()
    }

    @Test
    fun aRowShowsTheHoverTintAcrossItsWholeWidthAndLeavesNoBandAfterAClick() {
        setup()
        openShareDialog(existing = false)
        val row = tag(sharedNoteTabRowTag("c"))

        fun edge(): Color = row.captureToImage().toPixelMap().let { it[2, it.height / 2] }
        val resting = edge()
        row.performMouseInput { moveTo(Offset(width / 2f, center.y)) }
        rule.waitForIdle()
        assertNotEquals(resting, edge(), "the pointer over the row tints it from its left edge, not from the checkbox")
        row.performMouseInput { click(center) }
        rule.waitForIdle()
        row.performMouseInput { exit() }
        rule.waitForIdle()
        row.assertIsOn()
        assertEquals(resting, edge(), "a clicked row keeps no grey band once the pointer has left it")
    }

    @Test
    fun twoTabsWithTheSameFileNameAreToldApartByTheirFolder() {
        setup(
            configure = { s ->
                s.tabs = s.tabs.map {
                    if (it.id == "b") it.copy(filename = "a.log", sourcePath = File(File(dir, "other"), "a.log").absolutePath) else it
                }
            },
        )
        openShareDialog(existing = false)

        fun shown(id: String) = tag(sharedNoteTabRowTag(id)).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue(shown("b").contains("a.log — other"), shown("b"))
        assertTrue(shown("a").contains("a.log — ${dir.name}"), shown("a"))
        assertFalse(shown("c").contains("—"), "a file name no other tab has needs no help: ${shown("c")}")
    }

    @Test
    fun theCreateButtonNeedsAtLeastOneTickedTab() {
        setup()
        openShareDialog(existing = false)
        tag(sharedNoteTabRowTag("a")).performClick()
        rule.waitForIdle()
        tag(sharedNoteTabRowTag("a")).assertIsOff()

        rule.onNodeWithText("Create shared note").performClick()
        rule.waitForIdle()

        assertTrue(exists("shared-note-dialog"), "nothing is created and the dialog stays")
        assertTrue(state.sharedNoteHosts.isEmpty())

        tag(sharedNoteTabRowTag("b")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Create shared note").performClick()
        rule.waitForIdle()
        assertEquals(1, state.sharedNoteHosts.size)
    }

    @Test
    fun creatingASharedNoteMakesItWithTheNameAndTheTickedTabs() {
        setup()
        openShareDialog(existing = false)
        tag(SHARE_DIALOG_NAME_TAG).performTextInput("Watch drops after pairing")
        tag(sharedNoteTabRowTag("c")).performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Create shared note").performClick()
        rule.waitForIdle()

        val host = state.sharedNoteHosts.single()
        assertEquals("Watch drops after pairing", host.sharedNote!!.name)
        assertEquals(listOf("a", "c"), host.sharedNote!!.memberTabIds)
        assertEquals(host.id, state.tab("a")!!.notesView)
        assertEquals(host.id, state.tab("c")!!.notesView)
        assertNull(state.tab("b")!!.notesView)
        assertFalse(exists("shared-note-dialog"))
        assertTrue(exists(SHARED_BADGE_TAG), "the tile now shows the Shared badge")
    }

    @Test
    fun anEmptyNameFallsBackToTheDefaultName() {
        setup()
        openShareDialog(existing = false)

        rule.onNodeWithText("Create shared note").performClick()
        rule.waitForIdle()

        assertEquals("Shared note", state.sharedNoteHosts.single().sharedNote!!.name)
    }

    @Test
    fun sharingTheNoteKeepsItsTextAndStartsAFreshPrivateNote() {
        setup()
        putBlocks("a", AnnBlock.Note("n1", "my finding"))
        state.upAnn("a") { it.copy(annotations = it.annotations.copy(prefix = "Watch drops after pairing")) }
        openShareDialog(existing = true)
        assertTrue(textExists("Share note"))
        assertTrue(textExists("Watch drops after pairing"), "the name defaults to the note's title")

        tag(SHARE_DIALOG_NAME_TAG).performTextReplacement("Pairing")
        tag(sharedNoteTabRowTag("b")).performClick()
        rule.waitForIdle()
        tag(SHARE_DIALOG_CONFIRM_TAG).performClick()
        rule.waitForIdle()

        val host = state.sharedNoteHosts.single()
        assertEquals("Pairing", host.sharedNote!!.name)
        assertEquals(listOf("a", "b"), host.sharedNote!!.memberTabIds)
        assertTrue(host.annotations.blocks.any { it is AnnBlock.Note && it.text == "my finding" }, "the shared note took the text")
        assertTrue(state.tab("a")!!.annotations.blocks.isEmpty(), "the tab starts a fresh private note")
        assertEquals(host.id, state.tab("a")!!.notesView)
    }

    @Test
    fun theShareNameDefaultsToTheFileNameWhenTheNoteHasNoTitle() {
        setup()
        openShareDialog(existing = true)

        assertTrue(textExists("a.log analysis"))
    }

    @Test
    fun whenTheOperationIsRefusedTheAppsNoticeExplainsAndTheDialogCloses() {
        setup()
        val tab = state.tab("a")!!
        state.pendingNoteOverwrite = PendingNoteOverwrite("a", File(dir, "a_analysis.md").absolutePath, "a_analysis.md", pendingTab = tab)
        openShareDialog(existing = true)

        tag(SHARE_DIALOG_CONFIRM_TAG).performClick()
        rule.waitForIdle()

        assertFalse(exists("shared-note-dialog"))
        assertTrue(state.sharedNoteHosts.isEmpty())
        assertEquals("Couldn't share the note", assertNotNull(state.pendingDiagramNotice).title)
    }
}
