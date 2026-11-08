package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3SourceImportResult
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The log entry id of the occurrence the evidence tests start from. */
private const val ORIGINAL_ENTRY_ID = 41

/** Its time in milliseconds, matching its "00:00:01.234" text. */
private const val ORIGINAL_TIME_MS = 1_234L

/** How long a wait for the UI may take. */
private const val WAIT_TIMEOUT_MS = 5_000L

class Seq3SourceGuidedActionsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var root: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test
    fun snippetsCreateValidPlantUml() = buildDiagramWithSnippets(Seq3Dialect.PLANTUML)

    @Test
    fun snippetsCreateValidMermaid() = buildDiagramWithSnippets(Seq3Dialect.MERMAID)

    @Test
    fun compactMenusStayAnchoredAtNarrowWorkspaceWidth() {
        val sessionId = startWorkspace("source-narrow-menus")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.diagramHiddenTiles = listOf(DiagramWorkspaceSectionId.MESSAGES, DiagramWorkspaceSectionId.LIFELINES,
            DiagramWorkspaceSectionId.ARTIFACTS, DiagramWorkspaceSectionId.PREVIEW)
        showWorkspace(sessionId, width = 560, height = 760)
        rule.onNodeWithTag("seq3-header-more").performClick()
        rule.onNodeWithText("Edit source", useUnmergedTree = true).performClick()
        val opener = rule.onNodeWithTag("seq3-source-message-menu")
        val openerBounds = opener.getBoundsInRoot()
        opener.performClick()
        val options = rule.onNodeWithTag("seq3-source-message-menu-options", useUnmergedTree = true).assertIsDisplayed()
        val optionsBounds = options.getBoundsInRoot()
        assertTrue(optionsBounds.right.value - optionsBounds.left.value <= 260f, "message menu should stay compact: $optionsBounds")
        assertTrue(kotlin.math.abs(optionsBounds.left.value - openerBounds.left.value) <= 2f,
            "message menu should align with its opener: opener=$openerBounds menu=$optionsBounds")
        assertTrue(optionsBounds.top.value >= openerBounds.bottom.value - 1f,
            "message menu should open below its opener: opener=$openerBounds menu=$optionsBounds")
        rule.onNodeWithText("Call", useUnmergedTree = true).assertIsDisplayed()
        saveScreenshot("seq3-source-message-menu-compact.png", rule.onNodeWithTag("seq3-workspace-root").captureToImage())
    }

    @Test
    fun livePreviewAppliesAfterDebounceAndManualModeExposesApply() {
        val sessionId = startWorkspace("source-live-preview-ui")
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        val source = "@startuml\ntitle Live preview\nparticipant \"Alpha\" as A\nparticipant \"Beta\" as B\nA -> B: Request\n@enduml"
        view.zoomMode = Seq3ZoomMode.FIT
        val editor = rule.onNodeWithTag("seq3-source-editor")
        val generation = view.sourceEditorGeneration
        editor.performTextReplacement(source)
        rule.waitUntil(WAIT_TIMEOUT_MS) {
            currentSession(sessionId).document.title == "Live preview" &&
                currentSession(sessionId).document.lifelines.size == 2 && currentSession(sessionId).document.messages.size == 1
        }

        assertEquals(source, view.sourceDraft, "live import keeps the editor buffer text")
        assertEquals(generation, view.sourceEditorGeneration, "live import does not reload the editor")
        rule.onNodeWithTag("seq3-source-apply").assertDoesNotExist()
        editor.performTextInputSelection(TextRange(0))
        rule.waitForIdle()
        saveScreenshot("seq3-source-workspace-live.png", rule.onNodeWithTag("seq3-workspace-root").captureToImage())
        toggleMode("seq3-source-live-toggle")
        editor.performTextReplacement("@startuml\ntitle Manual draft\n@enduml")
        rule.waitUntil(2_000) { view.sourceDraft.contains("Manual draft") }
        assertEquals("Live preview", currentSession(sessionId).document.title)
        rule.onNodeWithTag("seq3-source-apply").assertIsDisplayed()
    }

    @Test
    fun autocompleteCanBeDisabledAndSupportsKeywordsWithoutSlash() {
        val sessionId = startWorkspace("source-keyword-autocomplete")
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val editor = rule.onNodeWithTag("seq3-source-editor")
        editor.performTextReplacement("@startuml\npart")
        rule.onNodeWithText("participant", useUnmergedTree = true).assertIsDisplayed()

        toggleMode("seq3-source-autocomplete-toggle")
        editor.performTextReplacement("@startuml\nactor")
        rule.onNodeWithTag("seq3-source-suggestions", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("@startuml\nactor", requireNotNull(state.seq3Sessions.viewState(sessionId)).sourceDraft)
    }

    @Test
    fun clearingSourcePreviewsEmptyDiagramThenNewDiagramSeedsBothDialects() {
        clearAndStartNewDiagram(Seq3Dialect.PLANTUML)
        clearAndStartNewDiagram(Seq3Dialect.MERMAID)
    }

    @Test
    fun newDiagramConfirmsBeforeReplacingAnExistingDiagram() {
        val sessionId = startWorkspace("source-new-diagram-confirm")
        val original = Seq3Document(
            title = "Existing diagram",
            lifelines = listOf(Seq3Lifeline("old", "Existing participant", setOf("Existing participant"), 0)),
        )
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.ReplaceDocument(original))
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        val originalSource = view.sourceDraft

        openMoreItem("seq3-source-new-diagram")
        rule.onNodeWithTag("seq3-source-confirm-new-diagram").assertIsDisplayed()
        assertEquals(original, currentSession(sessionId).document)
        assertEquals(originalSource, view.sourceDraft)

        rule.onNodeWithText("Keep editing", useUnmergedTree = true).performClick()
        openMoreItem("seq3-source-new-diagram")
        rule.onNodeWithTag("seq3-source-confirm-new-diagram").performClick()
        rule.waitForIdle()

        assertEquals("Existing diagram", currentSession(sessionId).document.title, "New diagram keeps a real title")
        assertTrue(currentSession(sessionId).document.lifelines.isEmpty())
        assertTrue(view.sourceDraft.contains("@startuml"))
    }

    @Test
    fun newDiagramUsesSeparateEvidenceConfirmationAndCancelKeepsEverything() {
        val sessionId = startWorkspace("source-new-diagram-evidence-gate")
        val evidence = Seq3Occurrence(52, 2_345, "00:00:02.345", 11, 13, 'I', "call")
        val original = Seq3Document(
            title = "Evidence diagram",
            lifelines = listOf(
                Seq3Lifeline("alpha", "Alpha", setOf("Alpha"), 0),
                Seq3Lifeline("beta", "Beta", setOf("Beta"), 1),
            ),
            messages = listOf(
                Seq3Message(
                    "message", Seq3Match("Alpha", "call"), "alpha", "beta", "call", Seq3Kind.CALL,
                    occurrences = listOf(evidence),
                ),
            ),
        )
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.ReplaceDocument(original))
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        val originalSource = view.sourceDraft

        openMoreItem("seq3-source-new-diagram")
        rule.onNodeWithTag("seq3-source-confirm-new-diagram").performClick()
        rule.onNodeWithText("Remove log evidence?", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(original, currentSession(sessionId).document)
        assertEquals(originalSource, view.sourceDraft)

        rule.onNodeWithText("Keep editing", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(original, currentSession(sessionId).document)
        assertEquals(originalSource, view.sourceDraft)

        openMoreItem("seq3-source-new-diagram")
        rule.onNodeWithTag("seq3-source-confirm-new-diagram").performClick()
        rule.onNodeWithText("Remove evidence and apply", useUnmergedTree = true).performClick()
        rule.waitForIdle()

        assertTrue(currentSession(sessionId).document.lifelines.isEmpty())
        assertTrue(currentSession(sessionId).document.messages.isEmpty())
        assertEquals("Evidence diagram", currentSession(sessionId).document.title, "New diagram keeps a real title")
        assertFalse(view.sourceEvidenceLossPending)
    }

    @Test
    fun evidenceRemovalWaitsForReviewInsteadOfOpeningAModalWhileTyping() {
        val sessionId = startWorkspace("source-evidence-removal-review")
        val evidence = Seq3Occurrence(ORIGINAL_ENTRY_ID, ORIGINAL_TIME_MS, "00:00:01.234", 7, 9, 'I', "call")
        val document = Seq3Document(
            title = "Evidence diagram",
            lifelines = listOf(
                Seq3Lifeline("alpha", "Alpha", setOf("Alpha"), 0),
                Seq3Lifeline("beta", "Beta", setOf("Beta"), 1),
            ),
            messages = listOf(
                Seq3Message(
                    "message", Seq3Match("Alpha", "call"), "alpha", "beta", "call", Seq3Kind.CALL,
                    occurrences = listOf(evidence),
                ),
            ),
        )
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.ReplaceDocument(document))
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        rule.onNodeWithTag("seq3-source-editor").performTextReplacement("")
        rule.waitUntil(WAIT_TIMEOUT_MS) { view.sourceEvidenceLossPending }

        rule.onNodeWithTag("seq3-source-evidence-review-status").assertIsDisplayed()
        rule.onNodeWithText("Remove log evidence?", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(1, currentSession(sessionId).document.messages.size, "the live preview keeps the last valid diagram")

        rule.onNodeWithTag("seq3-source-review-evidence").performClick()
        rule.onNodeWithText("Remove log evidence?", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Remove evidence and apply", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(currentSession(sessionId).document.messages.isEmpty())
        assertFalse(view.sourceEvidenceLossPending)
    }

    @Test
    fun hiddenMarkersStayInTheDraftAndKeepEvidenceWhenAnAuthoredLabelChanges() {
        val sessionId = startWorkspace("source-marker-evidence")
        val evidence = Seq3Occurrence(
            entryId = 41,
            timestampMillis = 1_234,
            rawTimestamp = "00:00:01.234",
            pid = 7,
            tid = 9,
            level = 'I',
            text = "original call",
        )
        val diagram = Seq3Document(
            title = "Evidence source",
            lifelines = listOf(
                Seq3Lifeline("alpha", "Alpha", setOf("Alpha"), 0),
                Seq3Lifeline("beta", "Beta", setOf("Beta"), 1),
            ),
            messages = listOf(
                Seq3Message(
                    id = "stable-message-id",
                    match = Seq3Match("Alpha", "original call"),
                    fromLifelineId = "alpha",
                    toLifelineId = "beta",
                    labelTemplate = "original call",
                    kind = Seq3Kind.CALL,
                    occurrences = listOf(evidence),
                ),
            ),
        )
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.ReplaceDocument(diagram))
        val marker = "' indagium-seq3:v1 "
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.waitForIdle()

        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        toggleMode("seq3-source-live-toggle")
        val editor = rule.onNodeWithTag("seq3-source-editor")
        assertTrue(view.sourceDraft.contains(marker), "the editable draft retains identity/evidence markers")
        rule.onNodeWithTag("seq3-source-more").performClick()
        rule.onNodeWithTag("seq3-source-toggle-markers").assertIsDisplayed()
        rule.onNodeWithTag("seq3-source-more").performClick()
        saveScreenshot("seq3-source-marker-folded.png", editor.captureToImage())

        val markerCount = view.sourceDraft.windowed(marker.length).count { it == marker }
        openMoreItem("seq3-source-toggle-markers")
        rule.waitForIdle()
        assertTrue(view.sourceDraft.contains(marker), "showing markers changes only the view projection")
        saveScreenshot("seq3-source-marker-visible.png", editor.captureToImage())
        openMoreItem("seq3-source-toggle-markers")
        rule.waitForIdle()
        val labelStart = view.sourceDraft.indexOf("original call")
        editor.performTextInputSelection(TextRange(labelStart, labelStart + "original call".length))
        editor.performTextInput("authored call")
        rule.waitForIdle()

        assertTrue(view.sourceDraft.contains(marker), "folding and editing visible text does not delete hidden marker source")
        assertEquals(markerCount, view.sourceDraft.windowed(marker.length).count { it == marker })
        rule.onNodeWithTag("seq3-source-apply").performClick()
        rule.waitForIdle()

        val updated = currentSession(sessionId).document.messages.single()
        assertEquals("stable-message-id", updated.id)
        assertEquals("authored call", updated.labelTemplate)
        assertEquals(listOf(evidence), updated.occurrences)
        assertFalse(view.sourceDraftDirty)
    }

    private fun markedDiagram() = Seq3Document(
        title = "Evidence source",
        lifelines = listOf(
            Seq3Lifeline("alpha", "Alpha", setOf("Alpha"), 0),
            Seq3Lifeline("beta", "Beta", setOf("Beta"), 1),
        ),
        messages = listOf(
            Seq3Message(
                id = "stable-message-id",
                match = Seq3Match("Alpha", "original call"),
                fromLifelineId = "alpha",
                toLifelineId = "beta",
                labelTemplate = "original call",
                kind = Seq3Kind.CALL,
                occurrences = listOf(Seq3Occurrence(ORIGINAL_ENTRY_ID, ORIGINAL_TIME_MS, "00:00:01.234", 7, 9, 'I', "original call")),
            ),
        ),
    )

    @Test
    fun backspaceAndDeleteNextToHiddenMarkersRemoveTheBlankLineAndKeepTheMarkers() {
        val sessionId = startWorkspace("source-backspace-markers")
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.ReplaceDocument(markedDiagram()))
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.waitForIdle()
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        toggleMode("seq3-source-live-toggle")
        val editor = rule.onNodeWithTag("seq3-source-editor")
        val original = view.sourceDraft
        val marker = "' indagium-seq3:v1 "
        val markerStart = original.indexOf(marker)
        val markerLineEnd = original.indexOf('\n', markerStart) + 1
        assertTrue(markerStart > 0)
        val withBlank = original.substring(0, markerLineEnd) + "\n" + original.substring(markerLineEnd)

        editor.performTextReplacement(withBlank)
        rule.waitForIdle()
        assertEquals(withBlank, view.sourceDraft)
        editor.performTextInputSelection(TextRange(markerLineEnd))
        editor.performKeyInput { pressKey(Key.Backspace) }
        rule.waitForIdle()
        assertEquals(original, view.sourceDraft, "Backspace removes the blank line, not the marker above it")

        editor.performTextReplacement(withBlank)
        rule.waitForIdle()
        editor.performTextInputSelection(TextRange(markerStart - 1))
        editor.performKeyInput { pressKey(Key.Delete) }
        rule.waitForIdle()
        assertEquals(original, view.sourceDraft, "Delete at the end of the line above removes the blank line below the markers")

        editor.performTextInputSelection(TextRange(markerLineEnd))
        editor.performKeyInput { pressKey(Key.Backspace) }
        rule.waitForIdle()
        assertEquals(original, view.sourceDraft, "with no blank line to remove, Backspace leaves the markers alone")
    }

    @Test
    fun backspaceAtTheNavigatedLineStartOfAStatementBelowAnIndentedMarkerChangesNothing() {
        val sessionId = startWorkspace("source-backspace-navigated")
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        toggleMode("seq3-source-live-toggle")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        val editor = rule.onNodeWithTag("seq3-source-editor")
        val raw = "@startuml\nparticipant A\nparticipant B\nalt ok\n    ' indagium-seq3:v1 abc123\n    B -> A: hi\nend\n@enduml"
        editor.performTextReplacement(raw)
        rule.waitForIdle()
        assertEquals(raw, view.sourceDraft)

        // The caret goes to the statement's visible line start by real navigation, as in the app.
        editor.performTextInputSelection(TextRange(raw.indexOf("B -> A") + 3))
        editor.performKeyInput { pressKey(Key.MoveHome) }
        rule.waitForIdle()
        editor.performKeyInput { pressKey(Key.Backspace) }
        rule.waitForIdle()
        assertEquals(raw, view.sourceDraft, "Backspace must not eat the hidden marker or join the statement onto `alt ok`")

        // Delete at the visible start of the statement removes its first character, not the marker's.
        editor.performKeyInput { pressKey(Key.Delete) }
        rule.waitForIdle()
        val statement = raw.indexOf("    B -> A")
        assertEquals(raw.removeRange(statement, statement + 1), view.sourceDraft)
        assertTrue(view.sourceDraft.contains("    ' indagium-seq3:v1 abc123\n"))
    }

    @Test
    fun deleteAtTheNavigatedEndOfTheLineAboveAnIndentedMarkerChangesNothing() {
        val sessionId = startWorkspace("source-delete-navigated")
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        toggleMode("seq3-source-live-toggle")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        val editor = rule.onNodeWithTag("seq3-source-editor")
        val raw = "@startuml\nparticipant A\nparticipant B\nalt ok\n    ' indagium-seq3:v1 abc123\n    B -> A: hi\nend\n@enduml"
        editor.performTextReplacement(raw)
        rule.waitForIdle()

        editor.performTextInputSelection(TextRange(raw.indexOf("alt ok") + 2))
        editor.performKeyInput { pressKey(Key.MoveEnd) }
        editor.performKeyInput { pressKey(Key.Delete) }
        rule.waitForIdle()
        assertEquals(raw, view.sourceDraft, "Delete must not join the marker onto `alt ok`")

        val withBlank = raw.replace("abc123\n", "abc123\n\n")
        editor.performTextReplacement(withBlank)
        rule.waitForIdle()
        editor.performTextInputSelection(TextRange(withBlank.indexOf("alt ok") + 2))
        editor.performKeyInput { pressKey(Key.MoveEnd) }
        editor.performKeyInput { pressKey(Key.Delete) }
        rule.waitForIdle()
        assertEquals(raw, view.sourceDraft, "Delete removes the blank line below the markers instead")
    }

    private val markedStatementSource =
        "@startuml\nparticipant A\nparticipant B\nalt ok\n    ' indagium-seq3:v1 abc123\n    B -> A: hi\nend\n@enduml"

    /** Hidden markers, an indented marker line, the caret at the statement below it by real Home. */
    private fun withCaretAtTheShownStatementStart(act: (androidx.compose.ui.test.SemanticsNodeInteraction) -> Unit, expected: (String, Int) -> String) {
        val sessionId = startWorkspace("source-edit-at-run")
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        toggleMode("seq3-source-live-toggle")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        val editor = rule.onNodeWithTag("seq3-source-editor")
        val raw = markedStatementSource
        editor.performTextReplacement(raw)
        rule.waitForIdle()
        editor.performTextInputSelection(TextRange(raw.indexOf("B -> A") + 3))
        editor.performKeyInput { pressKey(Key.MoveHome) }
        rule.waitForIdle()

        act(editor)
        rule.waitForIdle()
        val statement = raw.indexOf("    B -> A")
        assertEquals(expected(raw, statement), view.sourceDraft)
        assertTrue(view.sourceDraft.contains("    ' indagium-seq3:v1 abc123\n"), "the marker line is intact")

        editor.performKeyInput { withKeyDown(if (isMacOs) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Z) } }
        rule.waitForIdle()
        assertEquals(raw, view.sourceDraft, "one Ctrl+Z restores the previous text")
    }

    @Test
    fun aTypedCharacterAtTheShownStatementStartLandsThereAndKeepsTheMarker() =
        withCaretAtTheShownStatementStart({ it.performTextInput("x") }) { raw, at -> raw.substring(0, at) + "x" + raw.substring(at) }

    @Test
    fun pastedTextAtTheShownStatementStartLandsThereAndKeepsTheMarker() =
        withCaretAtTheShownStatementStart({ it.performTextInput("pasted text ") }) { raw, at -> raw.substring(0, at) + "pasted text " + raw.substring(at) }

    @Test
    fun enterAtTheShownStatementStartBreaksTheLineThereAndKeepsTheMarker() =
        withCaretAtTheShownStatementStart({ it.performKeyInput { pressKey(Key.Enter) } }) { raw, at -> raw.substring(0, at) + "\n" + raw.substring(at) }

    @Test
    fun tabAtTheShownStatementStartIndentsThereAndKeepsTheMarker() =
        withCaretAtTheShownStatementStart({ it.performKeyInput { pressKey(Key.Tab) } }) { raw, at -> raw.substring(0, at) + "    " + raw.substring(at) }

    @Test
    fun joiningAStatementLineOntoThePreviousOneIsOneUndoStep() {
        val sessionId = startWorkspace("source-join-undo")
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        toggleMode("seq3-source-live-toggle")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        val editor = rule.onNodeWithTag("seq3-source-editor")
        val source = "@startuml\nparticipant A\nparticipant B\n@enduml"
        editor.performTextReplacement(source)
        rule.waitForIdle()

        editor.performTextInputSelection(TextRange(source.indexOf("participant B")))
        editor.performKeyInput { pressKey(Key.Backspace) }
        rule.waitForIdle()
        assertEquals("@startuml\nparticipant Aparticipant B\n@enduml", view.sourceDraft)

        editor.performKeyInput { withKeyDown(if (isMacOs) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Z) } }
        rule.waitForIdle()
        assertEquals(source, view.sourceDraft, "one Ctrl+Z splits the lines again")
    }

    @Test
    fun typingARunOfCharactersIsOneUndoStepPerWord() {
        val sessionId = startWorkspace("source-word-undo")
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        toggleMode("seq3-source-live-toggle")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.sourceClockMs = { 1_000L }
        val editor = rule.onNodeWithTag("seq3-source-editor")
        editor.performTextReplacement("")
        rule.waitForIdle()
        "xyz uvw".forEach { editor.performTextInput(it.toString()) }
        rule.waitForIdle()
        assertEquals("xyz uvw", view.sourceDraft)

        fun undo() {
            editor.performKeyInput { withKeyDown(if (isMacOs) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Z) } }
            rule.waitForIdle()
        }
        undo()
        assertEquals("xyz", view.sourceDraft, "the second word, with its space, is one step")
        undo()
        assertEquals("", view.sourceDraft, "the first word is one step")
    }

    @Test
    fun longDraftScrollKeepsLineNumberGutterAlignedWithNativeTextLayout() {
        val sessionId = startWorkspace("source-long-scroll")
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        val source = buildString {
            append("@startuml\n")
            repeat(180) { append("' body comment $it\n") }
            append("@enduml")
        }
        val editor = rule.onNodeWithTag("seq3-source-editor")
        editor.performTextReplacement(source)
        rule.waitForIdle()
        editor.performTextInputSelection(TextRange(source.length - "@enduml".length))
        rule.waitForIdle()

        rule.onNodeWithTag("seq3-source-gutter-line-182").assertIsDisplayed()
        rule.onNodeWithTag("seq3-source-gutter-line-1").assertDoesNotExist()
        assertEquals(source, view.sourceDraft)
    }

    @Test
    fun guidedMenusReachTheVisibleEditorAfterNewDiagramReloadsIt() {
        val sessionId = startWorkspace("source-menus-new-diagram")
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.onNodeWithTag("seq3-source-insert-participant").performClick()
        rule.waitForIdle()
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        val generation = view.sourceEditorGeneration

        openMoreItem("seq3-source-new-diagram")
        rule.onNodeWithTag("seq3-source-confirm-new-diagram").performClick()
        rule.waitForIdle()

        assertTrue(view.sourceEditorGeneration > generation, "New diagram should reload the editor")
        assertFalse(view.sourceDraft.contains("Alias"), "New diagram should replace the earlier source: ${view.sourceDraft}")
        assertMenuInserts(sessionId, "seq3-source-fragment-menu", "loop", "loop condition")
        assertMenuInserts(sessionId, "seq3-source-message-menu", "Call", "From -> To: message label")
        assertMenuInserts(sessionId, "seq3-source-activation-menu", "Activate", "activate Participant")
    }

    @Test
    fun guidedMenusReachTheVisibleEditorAfterADialectSwitch() {
        val sessionId = startWorkspace("source-menus-dialect")
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.onNodeWithTag("seq3-source-dialect").performClick()
        rule.onAllNodesWithText("Mermaid", useUnmergedTree = true)[1].performClick()
        rule.waitForIdle()

        assertMenuInserts(sessionId, "seq3-source-fragment-menu", "loop", "loop condition")
        assertMenuInserts(sessionId, "seq3-source-activation-menu", "Activate", "activate Participant")
    }

    @Test
    fun guidedMenusReachTheVisibleEditorWhenSourceIsVisibleAtFirstComposition() {
        val sessionId = startWorkspace("source-menus-restored-tile")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        view.diagramHiddenTiles = listOf(DiagramWorkspaceSectionId.MESSAGES, DiagramWorkspaceSectionId.LIFELINES,
            DiagramWorkspaceSectionId.ARTIFACTS)
        showWorkspace(sessionId)

        assertMenuInserts(sessionId, "seq3-source-message-menu", "Call", "From -> To: message label")
        assertMenuInserts(sessionId, "seq3-source-fragment-menu", "loop", "loop condition")
    }

    @Test
    fun theBlockMenuInsertsABlockThatImports() {
        val sessionId = startWorkspace("source-block-valid")
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.onNodeWithTag("seq3-source-fragment-menu").performClick()
        rule.onNodeWithText("alt", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        assertTrue(view.sourceDraft.contains("alt condition\n    From -> To: message label\nend"), view.sourceDraft)
        val declared = view.sourceDraft.replace("@startuml", "@startuml\nparticipant From\nparticipant To")
        val imported = com.indagium.diagram3.importSeq3Source(declared, Seq3Dialect.PLANTUML, com.indagium.diagram3.Seq3Document())
        assertTrue(imported is Seq3SourceImportResult.Success, imported.toString())
    }

    private fun buildDiagramWithSnippets(dialect: Seq3Dialect) {
        val sessionId = startWorkspace("source-guided-${dialect.name.lowercase()}")
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        toggleMode("seq3-source-live-toggle")
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        if (dialect == Seq3Dialect.MERMAID) {
            rule.onNodeWithTag("seq3-source-dialect").performClick()
            rule.onAllNodesWithText("Mermaid", useUnmergedTree = true)[1].performClick()
        }
        val editor = rule.onNodeWithTag("seq3-source-editor")

        fun participant(name: String, alias: String, actor: Boolean) {
            rule.onNodeWithTag(if (actor) "seq3-source-insert-actor" else "seq3-source-insert-participant").performClick()
            editor.performTextInput(name)
            editor.performKeyInput { pressKey(Key.Tab) }
            editor.performTextInput(alias)
        }
        participant("Alpha", "A", true)
        participant("Beta", "B", false)
        rule.onNodeWithTag("seq3-source-message-menu").performClick()
        rule.onNodeWithText("Call", useUnmergedTree = true).performClick()
        editor.performTextInput("A")
        editor.performKeyInput { pressKey(Key.Tab) }
        editor.performTextInput("B")
        editor.performKeyInput { pressKey(Key.Tab) }
        editor.performTextInput("Request")
        rule.onNodeWithTag("seq3-source-apply").performClick()
        rule.waitForIdle()
        assertTrue(view.sourceDiagnostics.isEmpty(), "generated source should import: ${view.sourceDiagnostics}")
        assertEquals(listOf("Alpha", "Beta"), currentSession(sessionId).document.lifelines.map { it.name })
        assertEquals(listOf("Request"), currentSession(sessionId).document.messages.map { it.labelTemplate })
    }

    private fun assertMenuInserts(sessionId: String, menuTag: String, option: String, expected: String) {
        rule.onNodeWithTag(menuTag).performClick()
        rule.onNodeWithText(option, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        val visible = rule.onNodeWithTag("seq3-source-editor").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        assertTrue(visible.contains(expected), "$option should reach the visible editor: $visible")
        assertEquals(visible, view.sourceDraft, "the draft should match the visible editor after $option")
    }

    private fun toggleMode(tag: String) {
        if (rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            rule.onNodeWithTag("seq3-source-settings").performClick()
        rule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    }

    private fun clearAndStartNewDiagram(dialect: Seq3Dialect) {
        val sessionId = startWorkspace("source-new-diagram-${dialect.name.lowercase()}")
        val original = Seq3Document(
            title = "Untitled diagram",
            lifelines = listOf(Seq3Lifeline("old", "Old participant", setOf("Old participant"), 0)),
        )
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.ReplaceDocument(original))
        showWorkspace(sessionId)
        rule.onNodeWithTag("seq3-source-edit").performClick()
        val view = requireNotNull(state.seq3Sessions.viewState(sessionId))
        if (dialect == Seq3Dialect.MERMAID) {
            rule.onNodeWithTag("seq3-source-dialect").performClick()
            rule.onAllNodesWithText("Mermaid", useUnmergedTree = true)[1].performClick()
        }
        val editor = rule.onNodeWithTag("seq3-source-editor")
        editor.performTextReplacement("")
        rule.waitUntil(WAIT_TIMEOUT_MS) { currentSession(sessionId).document.lifelines.isEmpty() }
        assertTrue(currentSession(sessionId).document.messages.isEmpty())
        assertFalse(view.sourceEvidenceLossPending)

        openMoreItem("seq3-source-new-diagram")
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-source-confirm-new-diagram").assertDoesNotExist()
        assertEquals("Untitled diagram", currentSession(sessionId).document.title, "New diagram keeps a real title")
        assertTrue(currentSession(sessionId).document.lifelines.isEmpty())
        assertEquals(dialect, view.sourceEditorDialect)
        assertTrue(view.sourceDraft.startsWith(if (dialect == Seq3Dialect.MERMAID) "sequenceDiagram" else "@startuml"))
        assertTrue(view.sourceDraft.contains(if (dialect == Seq3Dialect.MERMAID) "sequenceDiagram" else "@enduml"))
    }

    /** Opens the source toolbar's "⋯" menu and picks the item with [tag]. */
    private fun openMoreItem(tag: String) {
        rule.onNodeWithTag("seq3-source-more").performClick()
        rule.onNodeWithTag(tag).performClick()
    }

    private fun startWorkspace(tabId: String): String {
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
        return requireNotNull(state.seq3Sessions.beginEmpty(tab.id)).also { state.seq3Sessions.setDialect(it, Seq3Dialect.PLANTUML) }
    }

    private fun currentSession(sessionId: String) =
        requireNotNull(state.seq3Sessions.sessions.singleOrNull { it.id == sessionId })

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

    private fun saveScreenshot(name: String, image: androidx.compose.ui.graphics.ImageBitmap) {
        val pixels = image.toPixelMap()
        val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            bitmap.setRGB(x, y, pixels[x, y].toArgb())
        }
        val directory = File("build/seq3-source-qa").apply { mkdirs() }
        assertTrue(ImageIO.write(bitmap, "png", File(directory, name)))
    }
}
