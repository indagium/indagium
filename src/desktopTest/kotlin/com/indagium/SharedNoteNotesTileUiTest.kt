@file:Suppress("MagicNumber")

package com.indagium

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
import com.indagium.ui.BoundAnnotationPanel
import com.indagium.ui.LIGHT_THEME
import com.indagium.ui.LocalTheme
import com.indagium.ui.createSharedNote
import com.indagium.ui.mkTab
import com.indagium.ui.setNotesView
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The Notes tile's panel renders the note its tab shows: a tab that views a shared note shows (and edits) the host's blocks,
 * a tab on its own note is unchanged.
 */
@OptIn(ExperimentalTestApi::class)
class SharedNoteNotesTileUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun entry(id: Int) = LogEntry(id, "10:00:00.%03d".format(id), LogLevel.I, "App", "line $id")

    private fun state(dir: File): AppState = AppState(File(dir, "state.cache"), notesDir = File(dir, "notes")).also { s ->
        s.updateSettings { it.copy(autoExportNotes = false, renderAnnotationMarkdownInline = true) }
        s.tabs = listOf(mkTab("a", "a.log", listOf(entry(1))), mkTab("c", "c.log", listOf(entry(1))))
        s.activeTabId = "a"
    }

    private fun AppState.setBlocks(noteId: String, vararg blocks: AnnBlock) =
        upAnn(noteId) { it.copy(annotations = it.annotations.copy(blocks = blocks.toList())) }

    private fun show(app: AppState, tabId: String) {
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                BoundAnnotationPanel(
                    state = app,
                    tab = app.tabs.first { it.id == tabId },
                    width = 640f,
                    focusRequester = null,
                    onPanelFocusChanged = {},
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun aTabThatViewsASharedNoteShowsTheHostsBlocksAndEditsThem() {
        val dir = createTempDirectory("shared-tile").toFile()
        val app = state(dir)
        try {
            val host = assertNotNull(app.createSharedNote("Pairing", listOf("a")))
            app.setBlocks(host, AnnBlock.Note("hn", "shared finding"))
            app.setBlocks("a", AnnBlock.Note("an", "private thought"))

            show(app, "a")

            rule.onNodeWithText("shared finding").assertIsDisplayed()
            rule.onNodeWithText("private thought").assertDoesNotExist()

            rule.onNodeWithTag("annotation-note-field").performClick()
            rule.onNodeWithTag("annotation-note-field").performKeyInput { pressKey(Key.MoveEnd) }
            rule.onNodeWithTag("annotation-note-field").performTextInput(" edited")
            rule.waitForIdle()

            val shared = assertNotNull(app.noteTab(host)).annotations.blocks.single() as AnnBlock.Note
            assertEquals("shared finding edited", shared.text, "the edit went to the shared note")
            val own = app.tab("a")!!.annotations.blocks.single() as AnnBlock.Note
            assertEquals("private thought", own.text, "the tab's own note is untouched")
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aTabOnItsOwnNoteStillShowsAndEditsItsOwnNote() {
        val dir = createTempDirectory("shared-tile-own").toFile()
        val app = state(dir)
        try {
            val host = assertNotNull(app.createSharedNote("Pairing", listOf("a")))
            app.setBlocks(host, AnnBlock.Note("hn", "shared finding"))
            app.setBlocks("c", AnnBlock.Note("cn", "c's own note"))

            show(app, "c")

            rule.onNodeWithText("c's own note").assertIsDisplayed()
            rule.onNodeWithText("shared finding").assertDoesNotExist()
            rule.onNodeWithTag("annotation-note-field").performClick()
            rule.onNodeWithTag("annotation-note-field").performKeyInput { pressKey(Key.MoveEnd) }
            rule.onNodeWithTag("annotation-note-field").performTextInput("!")
            rule.waitForIdle()

            assertEquals("c's own note!", (app.tab("c")!!.annotations.blocks.single() as AnnBlock.Note).text)
            assertEquals("shared finding", (assertNotNull(app.noteTab(host)).annotations.blocks.single() as AnnBlock.Note).text)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun switchingTheNotesViewSwapsTheNoteTheTileShows() {
        val dir = createTempDirectory("shared-tile-swap").toFile()
        val app = state(dir)
        try {
            val host = assertNotNull(app.createSharedNote("Pairing", listOf("a")))
            app.setBlocks(host, AnnBlock.Note("hn", "shared finding"))
            app.setBlocks("a", AnnBlock.Note("an", "private thought"))
            show(app, "a")
            rule.onNodeWithText("shared finding").assertIsDisplayed()

            app.setNotesView("a", null)
            rule.waitForIdle()

            rule.onNodeWithText("private thought").assertIsDisplayed()
            rule.onNodeWithText("shared finding").assertDoesNotExist()
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }
}
