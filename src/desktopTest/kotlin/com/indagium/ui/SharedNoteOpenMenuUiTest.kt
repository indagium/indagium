@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.indagium.model.AnnBlock
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Open ▾ lists this tab's own note and the shared notes above the recent files; picking one switches the tile's note. */
@OptIn(ExperimentalTestApi::class)
class SharedNoteOpenMenuUiTest : SharedNoteUiTestBase() {
    private lateinit var pairing: String
    private lateinit var firmware: String

    private fun setupWithTwoSharedNotes() {
        setup()
        pairing = assertNotNull(state.createSharedNote("Pairing", listOf("a", "b")))
        firmware = assertNotNull(state.createSharedNote("Firmware regressions", listOf("b", "c")))
        rule.waitForIdle()
    }

    private fun countIn(tag: String, text: String): Int =
        rule.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun checkedRows(): Int = countIn(NOTES_RECENT_POPUP_TAG, "✓")

    @Test
    fun withoutSharedNotesOrRecentNotesThereIsNoArrow() {
        setup()

        assertFalse(exists(NOTES_RECENT_NOTES_TAG))
    }

    @Test
    fun theMenuListsThisTabTheSharedNotesItIsInAndTheOthers() {
        setupWithTwoSharedNotes()

        click(NOTES_RECENT_NOTES_TAG)

        assertTrue(exists(NOTES_RECENT_POPUP_TAG))
        listOf("This tab", "Shared notes", "Other shared notes", "a.log notes", "private", "Pairing", "Firmware regressions").forEach {
            assertTrue(textExists(it), "the menu shows \"$it\"")
        }
        assertEquals(2, countIn(NOTES_RECENT_POPUP_TAG, "2 tabs"))
        assertEquals(1, checkedRows(), "only the shown note is checked")
        assertEquals(1, countIn(notesOpenSharedTag(pairing), "✓"))
    }

    @Test
    fun choosingThisTabsOwnNoteShowsItAndClosesTheMenu() {
        setupWithTwoSharedNotes()
        putBlocks(pairing, AnnBlock.Note("hn", "shared finding"))
        putBlocks("a", AnnBlock.Note("an", "private thought"))
        rule.waitForIdle()
        assertTrue(textExists("shared finding"))

        click(NOTES_RECENT_NOTES_TAG)
        click(NOTES_OPEN_OWN_TAG)

        assertNull(state.tab("a")!!.notesView)
        assertFalse(exists(NOTES_RECENT_POPUP_TAG))
        assertTrue(textExists("private thought"))
        assertFalse(textExists("shared finding"))
        assertFalse(exists(SHARED_BADGE_TAG))
    }

    @Test
    fun choosingAnotherSharedNoteAddsThisTabToItAndShowsIt() {
        setupWithTwoSharedNotes()
        putBlocks(firmware, AnnBlock.Note("fn", "firmware finding"))

        click(NOTES_RECENT_NOTES_TAG)
        click(notesOpenOtherTag(firmware))

        assertEquals(firmware, state.tab("a")!!.notesView)
        assertTrue("a" in state.sharedNoteHost(firmware)!!.sharedNote!!.memberTabIds, "choosing it joined the note")
        assertTrue(textExists("firmware finding"))
        tag(SHARED_BADGE_TAG).assertTextEquals("Shared · 3 tabs")
    }

    @Test
    fun choosingAnotherSharedNoteThisTabIsInSwitchesWithoutChangingMembers() {
        setupWithTwoSharedNotes()
        state.setNotesView("a", firmware)
        rule.waitForIdle()
        state.setNotesView("a", null)
        rule.waitForIdle()
        val members = state.sharedNoteHost(firmware)!!.sharedNote!!.memberTabIds

        click(NOTES_RECENT_NOTES_TAG)
        assertTrue(rule.onAllNodes(hasTestTag(notesOpenSharedTag(firmware))).fetchSemanticsNodes().isNotEmpty(), "it is now under Shared notes")
        click(notesOpenSharedTag(pairing))

        assertEquals(pairing, state.tab("a")!!.notesView)
        assertEquals(members, state.sharedNoteHost(firmware)!!.sharedNote!!.memberTabIds)
    }

    @Test
    fun theArrowKeysAndEnterPickARow() {
        setupWithTwoSharedNotes()
        state.setNotesView("a", null)
        rule.waitForIdle()

        click(NOTES_RECENT_NOTES_TAG)
        // The shown note (this tab's own) starts selected: Down moves to the first shared note, Enter shows it.
        tag(NOTES_RECENT_POPUP_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        tag(NOTES_RECENT_POPUP_TAG).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        assertEquals(pairing, state.tab("a")!!.notesView)
        assertFalse(exists(NOTES_RECENT_POPUP_TAG))
    }

    @Test
    fun theRecentFilesStayBelowTheNoteSectionsUnchanged() {
        setupWithTwoSharedNotes()
        // The tile shows the Pairing note, so its recent files are the ones named after it.
        val recent = java.io.File(dir, analysisNoteMarkdownName("Pairing")).apply { writeText("# old") }
        state.recentNotes = listOf(recent.absolutePath)
        rule.waitForIdle()

        click(NOTES_RECENT_NOTES_TAG)

        assertTrue(textExists(recent.name))
        assertTrue(textExists("Pairing"))
        val own = tag(NOTES_OPEN_OWN_TAG).fetchSemanticsNode().boundsInRoot.top
        val file = rule.onAllNodes(hasText(recent.name)).fetchSemanticsNodes().first().boundsInRoot.top
        assertTrue(own < file, "the notes come first, the files after")
    }
}
