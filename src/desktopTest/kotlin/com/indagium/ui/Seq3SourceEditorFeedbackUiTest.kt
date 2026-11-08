package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.indagium.diagram3.Seq3Dialect
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The suggestion list's scrollbar, the toolbar pictograms, Auto-indent and name completion, in a composition. */
class Seq3SourceEditorFeedbackUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var root: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
        if (::root.isInitialized) root.deleteRecursively()
    }

    // ── The suggestion list's scrollbar ─────────────────────────────────────────────────────────

    @Test
    fun anOverflowingListShowsAThinScrollbarBesideItsRowsAndAShortListShowsNone() {
        val sessionId = startWorkspace("fb-scrollbar", Seq3Dialect.PLANTUML)
        showSourceOnly(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val editor = rule.onNodeWithTag("seq3-source-editor")
        // Lines of filler give the list room above the caret line, so it is not cut to a sliver.
        val filler = (1..30).joinToString("\n") { "' filler $it" }

        editor.performTextReplacement("@startuml\n$filler\n/pa")
        rule.waitForIdle()
        val shortList = rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        val bars = rule.onAllNodesWithTag("seq3-source-suggestions-scrollbar", useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(bars.isEmpty(), "a list of two rows needs no bar: list=$shortList editor=${editor.getBoundsInRoot()}")

        editor.performTextReplacement("@startuml\n$filler\n/")
        rule.waitForIdle()
        val list = rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        val bar = rule.onNodeWithTag("seq3-source-suggestions-scrollbar", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        assertTrue(bar.left >= list.left && bar.right <= list.right && bar.top >= list.top && bar.bottom <= list.bottom,
            "the bar is inside the list: list=$list bar=$bar")
        assertTrue((bar.right - bar.left).value <= THIN_SCROLLBAR_DP + 0.5f, "the bar is the thin one: $bar")
        // No row reaches under the bar, and no pictogram sits there.
        val row = rule.onNodeWithTag("seq3-source-suggestion-row-participant", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(row.right.value <= bar.left.value + 0.5f, "rows end before the bar: row=$row bar=$bar")
        val glyph = rule.onNodeWithTag("seq3-source-suggestion-glyph-participant", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(glyph.right < bar.left, "pictograms stay clear of the bar: glyph=$glyph bar=$bar")
    }

    @Test
    fun theScrollbarStillShowsWhenTheListIsCutToTheRoomAboveOrBelowTheCaret() {
        val sessionId = startWorkspace("fb-scrollbar-cut", Seq3Dialect.PLANTUML)
        showWorkspace(sessionId) // the default tiles leave the editor short: the list has little room
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val editor = rule.onNodeWithTag("seq3-source-editor")
        // A short document: the caret line has little room on either side, so the list is cut well below its 208dp.
        editor.performTextReplacement("@startuml\n/")
        rule.waitForIdle()

        val list = rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        assertTrue((list.bottom - list.top).value < 150f, "the list is cut to its room: $list")
        val bar = rule.onNodeWithTag("seq3-source-suggestions-scrollbar", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        assertTrue(bar.top >= list.top && bar.bottom <= list.bottom && bar.right <= list.right, "list=$list bar=$bar")
        val row = rule.onNodeWithTag("seq3-source-suggestion-row-participant", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(row.right.value <= bar.left.value + 0.5f, "rows end before the bar: row=$row bar=$bar")

        // A caret near the bottom of a long document cuts the list to the room above it, and it still scrolls.
        val lines = (1..80).joinToString("\n") { "' filler $it" }
        editor.performTextReplacement("@startuml\n$lines\n/")
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-source-suggestions-scrollbar", useUnmergedTree = true).assertIsDisplayed()
    }

    // ── Pictograms on the toolbar ───────────────────────────────────────────────────────────────

    @Test
    fun everyToolbarButtonAndMenuItemHasAPictogram() {
        val sessionId = startWorkspace("fb-toolbar-glyphs", Seq3Dialect.PLANTUML)
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.waitForIdle()

        listOf("seq3-source-insert-participant-glyph", "seq3-source-insert-actor-glyph", "seq3-source-insert-note-glyph",
            "seq3-source-message-menu-glyph", "seq3-source-fragment-menu-glyph", "seq3-source-activation-menu-glyph").forEach {
            rule.onNodeWithTag(it, useUnmergedTree = true).assertIsDisplayed()
        }
        listOf("seq3-source-message-menu" to 3, "seq3-source-fragment-menu" to 7, "seq3-source-activation-menu" to 2).forEach { (menu, count) ->
            rule.onNodeWithTag(menu).performClick()
            rule.waitForIdle()
            repeat(count) { index ->
                rule.onNodeWithTag("$menu-glyph-$index", useUnmergedTree = true).assertIsDisplayed()
            }
            // Escape-free close: pick nothing by clicking the opener again.
            rule.onNodeWithTag(menu).performClick()
            rule.waitForIdle()
        }
    }

    @Test
    fun theGlyphedToolbarStaysInOneGroupAtANarrowWidthAndStillInserts() {
        val sessionId = startWorkspace("fb-toolbar-narrow", Seq3Dialect.PLANTUML)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.diagramHiddenTiles = listOf(DiagramWorkspaceSectionId.MESSAGES, DiagramWorkspaceSectionId.LIFELINES,
            DiagramWorkspaceSectionId.ARTIFACTS, DiagramWorkspaceSectionId.PREVIEW)
        showWorkspace(sessionId, width = 560, height = 760)
        rule.onNodeWithTag("seq3-header-more").performClick()
        rule.onNodeWithText("Edit source", useUnmergedTree = true).performClick()
        rule.waitForIdle()

        val group = rule.onNodeWithTag("seq3-source-insert-group").getBoundsInRoot()
        for (tag in listOf("seq3-source-insert-participant", "seq3-source-insert-actor", "seq3-source-message-menu",
            "seq3-source-insert-note", "seq3-source-fragment-menu", "seq3-source-activation-menu")) {
            val bounds = rule.onNodeWithTag(tag).assertIsDisplayed().getBoundsInRoot()
            assertTrue(bounds.left >= group.left && bounds.right <= group.right, "$tag $bounds is in the group $group")
            assertTrue(bounds.right.value <= 560f, "$tag $bounds is inside the 560dp window")
        }
        rule.onNodeWithTag("seq3-source-insert-participant-glyph", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("seq3-source-insert-actor").performClick()
        rule.waitForIdle()
        assertTrue(view.sourceDraft.contains("actor \""), view.sourceDraft)
        // The menu still opens under its button.
        val opener = rule.onNodeWithTag("seq3-source-message-menu").getBoundsInRoot()
        rule.onNodeWithTag("seq3-source-message-menu").performClick()
        val options = rule.onNodeWithTag("seq3-source-message-menu-options", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        assertTrue(options.top >= opener.bottom - 1.dp, "the options open below the button: $opener $options")
    }

    // ── Auto-indent and the persisted toggles ───────────────────────────────────────────────────

    @Test
    fun enterAfterActivateIndentsAndTypedDeactivateComesBackOutWhileAutoIndentIsOn() {
        val sessionId = startWorkspace("fb-indent-on", Seq3Dialect.PLANTUML)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val editor = rule.onNodeWithTag("seq3-source-editor")

        editor.performTextReplacement("@startuml\nactivate B")
        rule.waitForIdle()
        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("@startuml\nactivate B\n    ", view.sourceDraft)

        editor.performTextReplacement("@startuml\nactivate B\n    B -> C: x\n    deactivate ")
        rule.waitForIdle()
        editor.performTextInput("B")
        rule.waitForIdle()
        assertEquals("@startuml\nactivate B\n    B -> C: x\ndeactivate B", view.sourceDraft)
    }

    @Test
    fun withAutoIndentOffEnterKeepsOnlyTheLeadingWhitespaceAndTypedDeactivateStaysPut() {
        val sessionId = startWorkspace("fb-indent-off", Seq3Dialect.PLANTUML)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val editor = rule.onNodeWithTag("seq3-source-editor")
        toggleMode("seq3-source-auto-indent-toggle")
        assertFalse(state.settings.diagramSourceAutoIndent)

        editor.performTextReplacement("@startuml\n  activate B")
        rule.waitForIdle()
        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("@startuml\n  activate B\n  ", view.sourceDraft, "only the line's own whitespace is kept")

        editor.performTextReplacement("@startuml\nactivate B\n    B -> C: x\n    deactivate ")
        rule.waitForIdle()
        editor.performTextInput("B")
        rule.waitForIdle()
        assertEquals("@startuml\nactivate B\n    B -> C: x\n    deactivate B", view.sourceDraft, "no typed outdent")

        // Tab still indents by hand.
        editor.performTextReplacement("@startuml\nx")
        rule.waitForIdle()
        editor.performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()
        assertEquals("@startuml\nx    ", view.sourceDraft)
    }

    @Test
    fun theTogglesAreAppWideSettingsThatOutliveTheViewAndLabelTheirState() {
        val sessionId = startWorkspace("fb-toggles", Seq3Dialect.PLANTUML)
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        assertTrue(state.settings.diagramSourceAutocomplete)
        assertTrue(state.settings.diagramSourceAutoIndent)

        toggleMode("seq3-source-autocomplete-toggle")
        toggleMode("seq3-source-auto-indent-toggle")
        rule.waitForIdle()
        assertFalse(state.settings.diagramSourceAutocomplete)
        assertFalse(state.settings.diagramSourceAutoIndent)

        // A second diagram in the same app starts with the same choice.
        val other = requireNotNull(state.seq3Sessions.beginEmpty("fb-toggles"))
        assertFalse(state.settings.diagramSourceAutocomplete, "still off for $other")
        assertFalse(state.settings.diagramSourceAutoIndent)

        toggleMode("seq3-source-auto-indent-toggle")
        rule.waitForIdle()
        assertTrue(state.settings.diagramSourceAutoIndent)
    }

    // ── Name completion ─────────────────────────────────────────────────────────────────────────

    @Test
    fun anArrowTargetOffersDeclaredNamesInPlantUml() = arrowTargetOffersNames(Seq3Dialect.PLANTUML)

    @Test
    fun anArrowTargetOffersDeclaredNamesInMermaid() = arrowTargetOffersNames(Seq3Dialect.MERMAID)

    private fun arrowTargetOffersNames(dialect: Seq3Dialect) {
        val sessionId = startWorkspace("fb-names-${dialect.name}", dialect)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val editor = rule.onNodeWithTag("seq3-source-editor")
        val (head, arrow) = when (dialect) {
            Seq3Dialect.PLANTUML -> "@startuml\nparticipant \"Alice\" as A\nactor \"Bob Smith\" as Bob\n" to "A -> "
            Seq3Dialect.MERMAID -> "sequenceDiagram\nparticipant A as Alice\nactor Bob as Bob Smith\n" to "A->>"
        }
        editor.performTextReplacement(head + arrow + "B")
        rule.waitForIdle()

        rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("seq3-source-suggestion-glyph-name-Bob", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Bob Smith", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("seq3-source-suggestion-glyph-name-A", useUnmergedTree = true).assertDoesNotExist()

        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(head + arrow + "Bob", view.sourceDraft, "Enter inserts the alias; ${dialect.name}")

        // Typed in full: nothing more to complete, so Enter is a newline.
        rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertDoesNotExist()
        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertTrue(view.sourceDraft.startsWith(head + arrow + "Bob\n"), "${dialect.name}: ${view.sourceDraft}")
    }

    @Test
    fun aLineStartShowsNamesBeforeKeywordsAndTheParticipantGlyphForAParticipant() {
        val sessionId = startWorkspace("fb-names-linestart", Seq3Dialect.PLANTUML)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showSourceOnly(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val editor = rule.onNodeWithTag("seq3-source-editor")
        val head = "@startuml\n" + (1..30).joinToString("\n") { "' filler $it" } + "\nparticipant \"Pat\" as Pat\nactor \"Parker\" as Parker\n"

        editor.performTextReplacement(head + "pa")
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-source-suggestion-glyph-name-Pat", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("seq3-source-suggestion-glyph-name-Parker", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("seq3-source-suggestion-glyph-participant", useUnmergedTree = true).assertIsDisplayed()
        val name = rule.onNodeWithTag("seq3-source-suggestion-row-name-Pat", useUnmergedTree = true).getBoundsInRoot()
        val keyword = rule.onNodeWithTag("seq3-source-suggestion-row-participant", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(name.top < keyword.top, "names come first: $name $keyword")

        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(head + "Pat", view.sourceDraft, "the first entry, the name, is accepted")

        // One letter at a line start is still not enough for a list.
        editor.performTextReplacement(head + "P")
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun escapeClosesTheNameListAndNamesAreOffWithAutocompleteOff() {
        val sessionId = startWorkspace("fb-names-escape", Seq3Dialect.PLANTUML)
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val editor = rule.onNodeWithTag("seq3-source-editor")
        val text = "@startuml\nparticipant \"Bob Smith\" as Bob\nactivate Bo"

        editor.performTextReplacement(text)
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertIsDisplayed()
        editor.performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(text, view.sourceDraft)

        toggleMode("seq3-source-autocomplete-toggle")
        editor.performTextReplacement("@startuml\nparticipant \"Bob Smith\" as Bob\nnote over Bo")
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertDoesNotExist()
    }

    // ── Scaffolding ─────────────────────────────────────────────────────────────────────────────

    private fun toggleMode(tag: String) {
        if (rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            rule.onNodeWithTag("seq3-source-settings").performClick()
        }
        rule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    private fun startWorkspace(tabId: String, dialect: Seq3Dialect): String {
        if (::root.isInitialized) root.deleteRecursively()
        root = createTempDirectory("$tabId-ui").toFile()
        val tab = mkTab(tabId, "$tabId.log", emptyList())
        state = AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        ).also {
            it.tabs = listOf(tab)
            it.activateTab(tab.id)
        }
        return requireNotNull(state.seq3Sessions.beginEmpty(tab.id)).also { state.seq3Sessions.setDialect(it, dialect) }
    }

    /** The Source tile alone in the window, so the editor is tall. */
    private fun showSourceOnly(sessionId: String) {
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.diagramHiddenTiles = listOf(DiagramWorkspaceSectionId.MESSAGES, DiagramWorkspaceSectionId.LIFELINES,
            DiagramWorkspaceSectionId.ARTIFACTS, DiagramWorkspaceSectionId.PREVIEW)
        showWorkspace(sessionId)
    }

    private fun showWorkspace(sessionId: String, width: Int = 1280, height: Int = 900) {
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(width.dp, height.dp)) {
                    Seq3Workspace(state, sessionId)
                }
            }
        }
        rule.waitForIdle()
    }
}
