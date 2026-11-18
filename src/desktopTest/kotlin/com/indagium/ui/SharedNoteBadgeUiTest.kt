@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.indagium.model.AnnBlock
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Notes tile header's Shared badge: its text and count, the members popover and the dialogs the popover opens. */
@OptIn(ExperimentalTestApi::class)
class SharedNoteBadgeUiTest : SharedNoteUiTestBase() {
    private fun assertBadge(text: String) = tag(SHARED_BADGE_TAG).assertTextEquals(text)

    private fun share(name: String = "Pairing", vararg tabs: String = arrayOf("a", "b")): String =
        assertNotNull(state.createSharedNote(name, tabs.toList())).also { rule.waitForIdle() }

    private fun openPopover() = click(SHARED_BADGE_TAG)

    @Test
    fun theBadgeReadsSharedAndTheNumberOfOpenMemberTabs() {
        setup()
        assertFalse(exists(SHARED_BADGE_TAG), "a tab on its own note has no badge")

        val host = share()

        assertBadge("Shared · 2 tabs")
        state.setSharedNoteMembers(host, listOf("a", "b", "c"))
        rule.waitForIdle()
        assertBadge("Shared · 3 tabs")
        state.setSharedNoteMembers(host, listOf("a"))
        rule.waitForIdle()
        assertBadge("Shared · 1 tab")
    }

    @Test
    fun aClosedMemberIsNotCountedInTheBadge() {
        setup()
        share()
        assertBadge("Shared · 2 tabs")

        state.closeTab("b")
        rule.waitForIdle()

        assertBadge("Shared · 1 tab")
    }

    @Test
    fun theNoteNameFollowsTheBadgeOnlyWhenThereIsRoom() {
        setup()
        share("Watch drops after pairing")
        assertFalse(exists("notes-shared-name"), "the narrow right column drops the name first")

        maximizeNotes()

        tag("notes-shared-name").assertExists()
        assertTrue(textExists("Watch drops after pairing"))
    }

    @Test
    fun thePopoverListsTheMembersAndUntickingOneRemovesIt() {
        setup()
        val host = share(tabs = arrayOf("a", "b", "c"))

        openPopover()

        assertTrue(exists(SHARED_POPOVER_TAG))
        assertTrue(textExists("Tabs writing into this note"))
        assertTrue(textExists("this tab"))
        listOf("a", "b", "c").forEach { tag(sharedMemberTag(it)).assertIsOn() }

        click(sharedMemberTag("c"))

        assertEquals(listOf("a", "b"), state.sharedNoteHost(host)!!.sharedNote!!.memberTabIds)
        assertNull(state.tab("c")!!.notesView, "the removed tab goes back to its own note")
        tag(sharedMemberTag("c")).assertIsOff()
        assertBadge("Shared · 2 tabs")

        click(sharedMemberTag("c"))
        assertEquals(listOf("a", "b", "c"), state.sharedNoteHost(host)!!.sharedNote!!.memberTabIds)
    }

    @Test
    fun aClosedMemberIsGreyedWithItsFileNameAndAReopenLink() {
        setup()
        val host = share()
        state.closeTab("b")
        rule.waitForIdle()

        openPopover()

        assertTrue(exists("shared-note-closed-b"))
        assertTrue(textExists("b.log"))
        assertTrue(textExists("closed"))

        click(SHARED_REOPEN_TAG_PREFIX + "b")

        waitUntil("the closed member reopens and joins the note again") {
            state.sharedNoteHost(host)!!.sharedNote!!.closedMembers.isEmpty() && state.tabs.any { it.sourcePath?.endsWith("b.log") == true }
        }
        assertEquals(2, state.sharedNoteHost(host)!!.sharedNote!!.memberTabIds.size)
    }

    @Test
    fun addTabOpensAPickerOfTheOpenTabsThatAreNotInTheNote() {
        setup()
        val host = share()

        openPopover()
        click(SHARED_ADD_TAB_TAG)

        assertFalse(exists(SHARED_POPOVER_TAG), "the popover closed")
        assertTrue(exists("shared-note-add-dialog"))
        assertTrue(exists(sharedNoteTabRowTag("c")))
        assertFalse(exists(sharedNoteTabRowTag("a")), "tabs already in the note are not offered")
        rule.onNodeWithText("Add").performClick()
        rule.waitForIdle()
        assertEquals(2, state.sharedNoteHost(host)!!.sharedNote!!.memberTabIds.size, "Add does nothing while no tab is ticked")

        click(sharedNoteTabRowTag("c"))
        rule.onNodeWithText("Add").performClick()
        rule.waitForIdle()

        assertEquals(listOf("a", "b", "c"), state.sharedNoteHost(host)!!.sharedNote!!.memberTabIds)
        assertFalse(exists("shared-note-add-dialog"))
    }

    @Test
    fun renameAsksForANewNameAndKeepsTheNote() {
        setup()
        val host = share("Pairing")

        openPopover()
        click(SHARED_RENAME_TAG)
        assertTrue(textExists("Rename shared note"))
        tag("profile-name-field").performTextReplacement("Watch drops")
        rule.onNodeWithText("Rename").performClick()
        rule.waitForIdle()

        assertEquals("Watch drops", state.sharedNoteHost(host)!!.sharedNote!!.name)
        assertFalse(exists("profile-name-field"))
    }

    @Test
    fun stopSharingConfirmsThenMovesTheNoteBackIntoThisTab() {
        setup()
        val host = share("Pairing")
        putBlocks(host, AnnBlock.Note("hn", "shared finding"))

        openPopover()
        click(SHARED_STOP_TAG)

        assertTrue(textExists("Stop sharing Pairing?"))
        assertTrue(textExists("The note moves back into this tab as its private note. The other tabs go back to their own notes."))
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertNotNull(state.sharedNoteHost(host), "Cancel keeps the shared note")

        openPopover()
        click(SHARED_STOP_TAG)
        rule.onNodeWithText("Stop sharing", useUnmergedTree = true).performClick()
        rule.waitForIdle()

        assertNull(state.sharedNoteHost(host))
        assertEquals("shared finding", (state.tab("a")!!.annotations.blocks.single() as AnnBlock.Note).text)
        assertNull(state.tab("a")!!.notesView)
        assertNull(state.tab("b")!!.notesView)
        assertFalse(exists(SHARED_BADGE_TAG))
    }

    @Test
    fun stopSharingAsksBeforeReplacingAPrivateNoteThatHasContent() {
        setup()
        putBlocks("a", AnnBlock.Note("an", "private thought"))
        val host = share("Pairing")
        putBlocks("a", AnnBlock.Note("an2", "newer private thought"))
        putBlocks(host, AnnBlock.Note("hn", "shared finding"))

        openPopover()
        click(SHARED_STOP_TAG)
        rule.onNodeWithText("Stop sharing", useUnmergedTree = true).performClick()
        rule.waitForIdle()

        assertTrue(textExists("Replace this tab's note?"))
        assertTrue(textExists("Its current private note is replaced by the shared note."))
        assertNotNull(state.sharedNoteHost(host), "nothing changed yet")

        rule.onNodeWithText("Replace note").performClick()
        rule.waitForIdle()

        assertNull(state.sharedNoteHost(host))
        assertEquals(listOf("shared finding"), state.tab("a")!!.annotations.blocks.map { (it as AnnBlock.Note).text })
    }

    @Test
    fun deleteRemovesTheSharedNoteFromEveryTabAfterConfirming() {
        setup()
        val host = share("Pairing")

        openPopover()
        click(SHARED_DELETE_TAG)

        assertTrue(textExists("Delete Pairing?"))
        assertTrue(textExists("The shared note is removed from every tab. Its saved file stays on disk."))
        rule.onNodeWithText("Delete shared note", useUnmergedTree = true).performClick()
        rule.waitForIdle()

        assertNull(state.sharedNoteHost(host))
        assertNull(state.tab("a")!!.notesView)
        assertNull(state.tab("b")!!.notesView)
    }

    // ── Focus ────────────────────────────────────────────────────────────────────────────────────

    private lateinit var panelFocus: FocusRequester

    /** The badge and the Notes panel on one focus requester, the way the tile wires them; the panel starts focused. */
    private fun setupBadgeAndPanel() {
        dir = createTempDirectory("shared-note-focus").toFile()
        state = AppState(File(dir, "state.cache"), notesDir = File(dir, "notes"), autoExportNotes = false)
        state.tabs = listOf("a", "b").map { tabOf(it) }
        state.activeTabId = "a"
        val host = assertNotNull(state.createSharedNote("Pairing", listOf("a", "b")))
        panelFocus = FocusRequester()
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Column(Modifier.size(700.dp, 500.dp)) {
                    SharedNoteBadge(state, "a", host, 600f, onReclaimFocus = { runCatching { panelFocus.requestFocus() } })
                    BoundAnnotationPanel(
                        state = state,
                        tab = state.tab("a")!!,
                        width = 600f,
                        focusRequester = panelFocus,
                        onPanelFocusChanged = {},
                        modifier = Modifier.width(600.dp).testTag("notes-panel-root"),
                    )
                    SharedNoteDialogHost(state)
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { panelFocus.requestFocus() }
        rule.waitForIdle()
        tag("notes-panel-root").assertIsFocused()
    }

    @Test
    fun theNotesPanelGetsTheKeyboardBackWhenThePopoverIsDismissed() {
        setupBadgeAndPanel()

        openPopover()
        assertTrue(exists(SHARED_POPOVER_TAG))
        tag("notes-panel-root").assertIsNotFocused()
        tag(SHARED_POPOVER_TAG).performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()

        assertFalse(exists(SHARED_POPOVER_TAG))
        tag("notes-panel-root").assertIsFocused()
    }

    @Test
    fun theNotesPanelGetsTheKeyboardBackWhenADialogClosesAfterThePopover() {
        setupBadgeAndPanel()
        openPopover()
        click(SHARED_RENAME_TAG)
        assertTrue(exists("profile-name-field"))

        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()

        assertFalse(exists("profile-name-field"))
        tag("notes-panel-root").assertIsFocused()
    }
}
