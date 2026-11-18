@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.indagium.model.AnnBlock
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Notes header's New ▾: a plain click on New is what it always was, the ▾ menu adds New shared note… and Share this note…. */
@OptIn(ExperimentalTestApi::class)
class SharedNoteNewMenuUiTest : SharedNoteUiTestBase() {
    private fun ownBlocks(tabId: String) = state.tab(tabId)!!.annotations.blocks

    @Test
    fun aPlainClickOnNewStillMakesAPrivateNote() {
        setup()
        putBlocks("a", AnnBlock.Note("n1", "my finding"))
        assertEquals(1, ownBlocks("a").size)

        click("notes-button-new")

        assertTrue(ownBlocks("a").isEmpty(), "New cleared the tab's own note as it always did")
        assertTrue(state.sharedNoteHosts.isEmpty())
        assertNull(state.tab("a")!!.notesView)
        assertFalse(exists(NOTES_NEW_POPUP_TAG), "a plain click opens no menu")
    }

    @Test
    fun theArrowOpensTheMenuWithItsThreeItemsAndCaptions() {
        setup()

        click(NOTES_NEW_MENU_TAG)

        assertTrue(exists(NOTES_NEW_POPUP_TAG))
        listOf(
            "New note", "For this tab only",
            "New shared note…", "Choose the tabs that write into it",
            "Share this note…", "Keeps its text; this tab starts a fresh private note",
        ).forEach { assertTrue(textExists(it), "the menu shows \"$it\"") }
        tag(NOTES_NEW_ITEM_NEW_TAG).assertIsEnabled()
        tag(NOTES_NEW_ITEM_SHARED_TAG).assertIsEnabled()
        tag(NOTES_NEW_ITEM_SHARE_TAG).assertIsEnabled()
    }

    @Test
    fun newNoteInTheMenuDoesWhatTheButtonDoes() {
        setup()
        putBlocks("a", AnnBlock.Note("n1", "my finding"))

        click(NOTES_NEW_MENU_TAG)
        click(NOTES_NEW_ITEM_NEW_TAG)

        assertTrue(ownBlocks("a").isEmpty())
        assertFalse(exists(NOTES_NEW_POPUP_TAG), "choosing an item closes the menu")
    }

    @Test
    fun newSharedNoteOpensTheDialogAndCancelClosesIt() {
        setup()

        click(NOTES_NEW_MENU_TAG)
        click(NOTES_NEW_ITEM_SHARED_TAG)

        assertTrue(exists("shared-note-dialog"))
        assertTrue(textExists("New shared note"))
        assertFalse(exists(NOTES_NEW_POPUP_TAG))
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertFalse(exists("shared-note-dialog"))
        assertTrue(state.sharedNoteHosts.isEmpty())
    }

    @Test
    fun shareThisNoteOpensTheShareDialogWithTheNoteNameFilledIn() {
        setup()
        state.upAnn("a") { it.copy(annotations = it.annotations.copy(prefix = "Watch drops after pairing")) }

        click(NOTES_NEW_MENU_TAG)
        click(NOTES_NEW_ITEM_SHARE_TAG)

        assertTrue(exists("shared-note-dialog"))
        assertTrue(textExists("Share note"))
        assertTrue(textExists("Watch drops after pairing"), "the name field starts with the note's title")
    }

    @Test
    fun shareThisNoteIsDisabledWhileTheTabShowsASharedNote() {
        setup()
        assertNotNull(state.createSharedNote("Pairing", listOf("a", "b")))
        rule.waitForIdle()

        click(NOTES_NEW_MENU_TAG)

        tag(NOTES_NEW_ITEM_SHARE_TAG).assertIsNotEnabled()
        tag(NOTES_NEW_ITEM_SHARED_TAG).assertIsEnabled()
        tag(NOTES_NEW_ITEM_NEW_TAG).assertIsEnabled()
    }

    @Test
    fun newNoteWhileViewingASharedNoteClearsTheTabsOwnNoteNeverTheSharedOne() {
        setup()
        val host = assertNotNull(state.createSharedNote("Pairing", listOf("a", "b")))
        putBlocks(host, AnnBlock.Note("hn", "shared finding"))
        putBlocks("a", AnnBlock.Note("an", "private thought"))
        rule.waitForIdle()

        click("notes-button-new")

        assertEquals(1, state.noteTab(host)!!.annotations.blocks.size, "the shared note keeps its text")
        assertTrue(ownBlocks("a").isEmpty())
        assertNull(state.tab("a")!!.notesView, "the tab is back on its own note")
    }

    @Test
    fun theArrowStaysInTheCompactWrappedBarWhenTheNotesCardIsNarrow() {
        setupPanel(width = 260)
        assertTrue(exists("notes-toolbar"), "the buttons are in the panel's bar")

        click(NOTES_NEW_MENU_TAG)

        assertTrue(exists(NOTES_NEW_POPUP_TAG))
        assertTrue(textExists("New shared note…"))
        assertTrue(textExists("Share this note…"))
    }

    @Test
    fun theArrowIsInTheHeaderRowWhenTheCardIsWide() {
        setup()
        maximizeNotes()
        assertFalse(exists("notes-toolbar"), "the buttons left the bar for the header")

        click(NOTES_NEW_MENU_TAG)

        assertTrue(textExists("Share this note…"))
    }
}
