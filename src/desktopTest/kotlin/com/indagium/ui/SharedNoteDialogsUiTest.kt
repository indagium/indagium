@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.indagium.model.AnnBlock
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
