@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.indagium.model.AnnBlock
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What a member tab shows of a shared note the moment another tab made it, and the names and files two shared notes get. */
@OptIn(ExperimentalTestApi::class)
class SharedNoteMemberViewUiTest : SharedNoteUiTestBase() {
    private fun openShareDialog(existing: Boolean, tabId: String = "a") {
        state.sharedNoteDialog = SharedNoteDialogRequest.Share(tabId, shareExistingNote = existing)
        rule.waitForIdle()
    }

    @Test
    fun aMemberTabShowsASharedNoteMadeFromAnotherTabWithoutAnyOtherAction() {
        setup()
        openShareDialog(existing = false)
        tag(SHARE_DIALOG_NAME_TAG).performTextInput("Pairing")
        tag(sharedNoteTabRowTag("b")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Create shared note").performClick()
        rule.waitForIdle()
        val host = state.sharedNoteHosts.single()
        assertEquals(listOf("a", "b"), host.sharedNote!!.memberTabIds)
        assertTrue(exists(SHARED_BADGE_TAG), "the tab that made it shows the badge")
        putBlocks(host.id, AnnBlock.Note("hn", "shared finding"))

        // Switch to the other member exactly as the tab strip does: nothing but the active tab changes.
        state.activeTabId = "b"
        rule.waitForIdle()

        assertTrue(exists(SHARED_BADGE_TAG), "tab b's Notes tile shows the Shared badge at once")
        assertTrue(textExists("Shared · 2 tabs"), "the badge counts the members")
        assertTrue(textExists("shared finding"), "tab b's Notes tile shows the host's blocks")

        // A tab that is not a member keeps its own note.
        state.activeTabId = "c"
        rule.waitForIdle()
        assertFalse(exists(SHARED_BADGE_TAG))
        assertFalse(textExists("shared finding"))
    }

    @Test
    fun aMemberTabShowsAnEmptySharedNoteAndItsBadgeToo() {
        setup()
        val host = assertNotNull(state.createSharedNote("Pairing", listOf("a", "b")))

        state.activeTabId = "b"
        rule.waitForIdle()

        assertTrue(exists(SHARED_BADGE_TAG))
        assertEquals(host, state.tab("b")!!.notesView)
    }

    @Test
    fun theShareDialogShowsTheNameABlankFieldWillGetAndTwoBlankNotesGetDifferentFiles() {
        setup()
        openShareDialog(existing = false)
        assertTrue(textExists("Shared note"), "the placeholder is the name that would be used")
        rule.onNodeWithText("Create shared note").performClick()
        rule.waitForIdle()
        openShareDialog(existing = false, tabId = "b")
        assertTrue(textExists("Shared note 2"), "the second note's placeholder counts up")
        rule.onNodeWithText("Create shared note").performClick()
        rule.waitForIdle()

        val (first, second) = state.sharedNoteHosts
        assertEquals("Shared note", first.sharedNote!!.name)
        assertEquals("Shared note 2", second.sharedNote!!.name)
        assertTrue(first.noteTargetName != null && first.noteTargetName != second.noteTargetName)
    }
}
