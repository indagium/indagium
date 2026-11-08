package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3CustomMessageSpec
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.sourceEmissionAnchors
import com.indagium.testing.setTestsViewport
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How far the test clock moves to let a debounced edit settle. */
private const val CLOCK_ADVANCE_MS = 2_000L

/** How long a wait for the UI may take. */
private const val WAIT_TIMEOUT_MS = 5_000L

/** The test window's width. */
private const val VIEWPORT_WIDTH_DP = 1280

/** The test window's height. */
private const val VIEWPORT_HEIGHT_DP = 900

/** Phase 6 in a composition: caret line and diagram message linked both ways, Cmd/Ctrl+/ to toggle
 *  comments, and the find bar. */
class Seq3SourceLinkAndFindUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var root: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
        if (::root.isInitialized) root.deleteRecursively()
    }

    // ── Linking: the caret focuses the message ───────────────────────────────────────────────────

    @Test fun aCaretMovedByTheUserOntoAMessageLineFocusesThatMessageInPlantUml() = caretFocusesMessage(Seq3Dialect.PLANTUML)

    @Test fun aCaretMovedByTheUserOntoAMessageLineFocusesThatMessageInMermaid() = caretFocusesMessage(Seq3Dialect.MERMAID)

    private fun caretFocusesMessage(dialect: Seq3Dialect) {
        val sessionId = startWorkspace("link-caret-${dialect.name}", dialect, messageLabels = listOf("First", "Second"))
        val view = viewOf(sessionId)
        openEditor(view)
        val second = messageIds(sessionId)[1]
        val line = sourceEmissionAnchors(view.sourceDraft, dialect).first { it.messageId == second }.line
        assertNull(view.focusedMessageId)

        // A selection written without a key or a click (as a canvas edit or a jump would) links nothing.
        val editor = rule.onNodeWithTag("seq3-source-editor")
        editor.performTextInputSelection(TextRange(rawLineStart(line, view)))
        rule.mainClock.advanceTimeBy(CLOCK_ADVANCE_MS)
        rule.waitForIdle()
        assertNull(view.focusedMessageId, "${dialect.name}: a caret nobody moved does not link")

        // The user steps along the line: after a pause the diagram follows.
        editor.performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        rule.waitUntil(WAIT_TIMEOUT_MS) { view.focusedMessageId != null }
        assertEquals(second, view.focusedMessageId, dialect.name)

        // The first message's line focuses the first one.
        val first = messageIds(sessionId)[0]
        val firstLine = sourceEmissionAnchors(view.sourceDraft, dialect).first { it.messageId == first }.line
        editor.performTextInputSelection(TextRange(rawLineStart(firstLine, view)))
        editor.performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitUntil(WAIT_TIMEOUT_MS) { view.focusedMessageId == first }
    }

    // ── Linking: the focused message reveals its line ───────────────────────────────────────────

    @Test fun aMessageFocusedElsewhereTintsItsLineAndLeavesTheCaretAloneInPlantUml() = focusedMessageTintsLine(Seq3Dialect.PLANTUML)

    @Test fun aMessageFocusedElsewhereTintsItsLineAndLeavesTheCaretAloneInMermaid() = focusedMessageTintsLine(Seq3Dialect.MERMAID)

    private fun focusedMessageTintsLine(dialect: Seq3Dialect) {
        val sessionId = startWorkspace("link-reveal-${dialect.name}", dialect, messageLabels = listOf("First", "Second", "Third"))
        val view = viewOf(sessionId)
        openEditor(view)
        val ids = messageIds(sessionId)
        val anchors = sourceEmissionAnchors(view.sourceDraft, dialect)
        val raw = ids.map { id -> anchors.first { it.messageId == id }.line }
        assertTrue(raw.distinct().size == 3, raw.toString())
        val before = selectionRange()

        view.focusedMessageId = ids[1]
        view.scrollRequestId = ids[1]
        rule.waitForIdle()
        assertEquals("Linked line", gutterState(raw[1]), dialect.name)
        assertNull(gutterState(raw[0]))
        assertNull(gutterState(raw[2]))
        assertEquals(before, selectionRange(), "${dialect.name}: the caret stays")

        view.focusedMessageId = ids[2]
        rule.waitForIdle()
        assertNull(gutterState(raw[1]))
        assertEquals("Linked line", gutterState(raw[2]))
        view.focusedMessageId = null
        rule.waitForIdle()
        assertNull(gutterState(raw[2]))
    }

    @Test
    fun aMessageBelowTheViewportIsScrolledIntoViewWithTheMarkersHidden() {
        val labels = (1..90).map { "Step $it" }
        val sessionId = startWorkspace("link-scroll", Seq3Dialect.PLANTUML, messageLabels = labels)
        val view = viewOf(sessionId)
        openEditor(view)
        val last = messageIds(sessionId).last()
        val rawLine = sourceEmissionAnchors(view.sourceDraft, Seq3Dialect.PLANTUML).first { it.messageId == last }.line
        // Markers are hidden: far more raw lines than lines shown, and the last message is out of sight.
        assertTrue(view.sourceDraft.lines().size > labels.size * 2, "one marker line per message")
        assertTrue(rule.onAllGutterLines().none { it == rawLine }, "the last line starts out of view")

        view.focusedMessageId = last
        view.scrollRequestId = last
        rule.waitUntil(WAIT_TIMEOUT_MS) { gutterRowShown(rawLine) }
        assertEquals("Linked line", gutterState(rawLine))
    }

    // ── Comment toggle ───────────────────────────────────────────────────────────────────────────

    @Test fun theCommentToggleKeepsMarkersAndIsOneUndoStepInPlantUml() = commentToggle(Seq3Dialect.PLANTUML)

    @Test fun theCommentToggleKeepsMarkersAndIsOneUndoStepInMermaid() = commentToggle(Seq3Dialect.MERMAID)

    private fun commentToggle(dialect: Seq3Dialect) {
        val sessionId = startWorkspace("comment-${dialect.name}", dialect, messageLabels = listOf("First", "Second"))
        val view = viewOf(sessionId)
        openEditor(view, showMarkers = true)
        val original = view.sourceDraft
        val prefix = seq3SourceCommentPrefix(dialect)
        val markerLines = original.lines().filter { isSeq3SourceMarkerLine(it, dialect) }
        assertTrue(markerLines.size >= 2, original)

        val editor = rule.onNodeWithTag("seq3-source-editor")
        editor.performTextInputSelection(TextRange(0, original.length))
        toggleComment()
        val commented = view.sourceDraft
        assertEquals(markerLines, commented.lines().filter { isSeq3SourceMarkerLine(it, dialect) }, "${dialect.name}: markers are untouched")
        val body = commented.lines().zip(original.lines()).filter { (_, before) -> before.isNotBlank() && !isSeq3SourceMarkerLine(before, dialect) }
        assertTrue(body.isNotEmpty())
        body.forEach { (after, before) -> assertEquals(before.replaceFirst(before.trimStart(), "$prefix ${before.trimStart()}"), after, dialect.name) }
        assertEquals(commented, editorText(), "the editor and the draft agree")
        assertEquals(commented, selectedText(), "the selection still covers the same lines")

        // Toggling again uncomments, and one undo returns each step.
        toggleComment()
        assertEquals(original, view.sourceDraft, dialect.name)
        undo()
        assertEquals(commented, view.sourceDraft, "${dialect.name}: one undo undoes one toggle")
        undo()
        assertEquals(original, view.sourceDraft, dialect.name)
    }

    @Test
    fun theCommentToggleOnAVisibleLineWorksWhileMarkersAreHidden() {
        val dialect = Seq3Dialect.PLANTUML
        val sessionId = startWorkspace("comment-hidden", dialect, messageLabels = listOf("First", "Second"))
        val view = viewOf(sessionId)
        openEditor(view)
        val original = view.sourceDraft
        val markerLines = original.lines().filter { isSeq3SourceMarkerLine(it, dialect) }
        assertFalse(editorText().contains("indagium-seq3:v1"))
        val offset = view.sourceDraft.indexOf("Second")
        rule.onNodeWithTag("seq3-source-editor").performTextInputSelection(TextRange(offset))
        toggleComment()
        val after = view.sourceDraft.lines()
        assertEquals(markerLines, after.filter { isSeq3SourceMarkerLine(it, dialect) })
        assertEquals(1, after.count { it.trimStart().startsWith("' ") && !isSeq3SourceMarkerLine(it, dialect) }, view.sourceDraft)
        assertTrue(after.single { it.trimStart().startsWith("' ") && !isSeq3SourceMarkerLine(it, dialect) }.contains("Second"))
        undo()
        assertEquals(original, view.sourceDraft)
    }

    // ── Find ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theFindBarStepsThroughMatchesAndCountsThem() {
        val sessionId = startWorkspace("find-step", Seq3Dialect.PLANTUML, messageLabels = emptyList())
        val view = viewOf(sessionId)
        openEditor(view)
        val source = "@startuml\nparticipant A\nparticipant B\nA -> B: foo\nB -> A: Foo\nA -> B: bar foo\n@enduml"
        rule.onNodeWithTag("seq3-source-editor").performTextReplacement(source)
        rule.waitForIdle()
        openFind()
        rule.onNodeWithTag("seq3-source-find-field").assertIsFocused()
        rule.onNodeWithTag("seq3-source-find-field").performTextInput("foo")
        rule.waitForIdle()
        assertEquals("1 of 3", findCount())
        assertEquals("foo", selectedText(), "the first match is selected in the editor")
        val matches = Regex("foo", RegexOption.IGNORE_CASE).findAll(source).map { it.range.first }.toList()
        assertEquals(matches[0], selectionRange().min)

        rule.onNodeWithTag("seq3-source-find-field").performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("2 of 3", findCount())
        assertEquals(matches[1], selectionRange().min)
        rule.onNodeWithTag("seq3-source-find-next").performClick()
        rule.waitForIdle()
        assertEquals("3 of 3", findCount())
        rule.onNodeWithTag("seq3-source-find-next").performClick()
        rule.waitForIdle()
        assertEquals("1 of 3", findCount(), "next wraps")
        rule.onNodeWithTag("seq3-source-find-prev").performClick()
        rule.waitForIdle()
        assertEquals("3 of 3", findCount(), "previous wraps")
        rule.onNodeWithTag("seq3-source-find-field").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        rule.waitForIdle()
        assertEquals("2 of 3", findCount())

        rule.onNodeWithTag("seq3-source-find-field").performTextReplacement("nothing here")
        rule.waitForIdle()
        assertEquals("No results", findCount())
    }

    @Test
    fun replaceTakesOneMatchAtATimeAndReplaceAllIsOneUndoStep() {
        val sessionId = startWorkspace("find-replace", Seq3Dialect.PLANTUML, messageLabels = emptyList())
        val view = viewOf(sessionId)
        openEditor(view)
        val source = "@startuml\nparticipant A\nparticipant B\nA -> B: foo\nB -> A: foo\nA -> B: foo\n@enduml"
        rule.onNodeWithTag("seq3-source-editor").performTextReplacement(source)
        rule.waitForIdle()
        openFind()
        rule.onNodeWithTag("seq3-source-find-field").performTextInput("foo")
        rule.onNodeWithTag("seq3-source-replace-field").performTextInput("bar")
        rule.waitForIdle()
        assertEquals("1 of 3", findCount())

        rule.onNodeWithTag("seq3-source-replace-one").performClick()
        rule.waitForIdle()
        assertEquals(source.replaceFirst("foo", "bar"), view.sourceDraft)
        assertEquals("1 of 2", findCount(), "the next match is current")
        rule.onNodeWithTag("seq3-source-editor").assertIsFocused()
        undo()
        assertEquals(source, view.sourceDraft, "one undo takes one replace back")

        rule.onNodeWithTag("seq3-source-replace-all").performClick()
        rule.waitForIdle()
        assertEquals(source.replace("foo", "bar"), view.sourceDraft)
        assertEquals("No results", findCount())
        undo()
        assertEquals(source, view.sourceDraft, "one undo takes every replacement back")
    }

    @Test
    fun matchesInsideHiddenMarkersAreSkipped() {
        val sessionId = startWorkspace("find-hidden", Seq3Dialect.PLANTUML, messageLabels = listOf("First", "Second"))
        val view = viewOf(sessionId)
        openEditor(view)
        val markers = view.sourceDraft.lines().count { isSeq3SourceMarkerLine(it, Seq3Dialect.PLANTUML) }
        assertTrue(markers >= 2)
        openFind()
        // Every marker holds the base64 of `{"`.
        rule.onNodeWithTag("seq3-source-find-field").performTextInput("eyJ")
        rule.waitForIdle()
        assertEquals("No results", findCount())
        rule.onNodeWithTag("seq3-source-replace-all").performClick()
        rule.waitForIdle()
        assertTrue(view.sourceDraft.lines().count { isSeq3SourceMarkerLine(it, Seq3Dialect.PLANTUML) } == markers)

        rule.onNodeWithTag("seq3-source-more").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-source-toggle-markers").performClick()
        rule.waitForIdle()
        assertEquals("1 of $markers", findCount(), "shown markers are searchable")
    }

    @Test
    fun escapeClosesTheFindBarWithoutRestoringAMaximizedTile() {
        val sessionId = startWorkspace("find-escape", Seq3Dialect.PLANTUML, messageLabels = listOf("First"))
        val view = viewOf(sessionId)
        openEditor(view)
        view.maximizedDiagramTile = DiagramWorkspaceSectionId.SOURCE
        rule.waitForIdle()
        openFind()
        rule.onNodeWithTag("seq3-source-find-bar").assertIsDisplayed()
        assertTrue(view.sourceFindOpen)

        rule.onNodeWithTag("seq3-source-find-field").performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-source-find-bar").assertDoesNotExist()
        assertFalse(view.sourceFindOpen)
        assertEquals(DiagramWorkspaceSectionId.SOURCE, view.maximizedDiagramTile, "Esc only closed the bar")
        rule.onNodeWithTag("seq3-source-editor").assertIsFocused()
    }

    @Test
    fun escapeFromTheReplaceFieldAndTheEditorClosesTheBarToo() {
        val sessionId = startWorkspace("find-escape-2", Seq3Dialect.PLANTUML, messageLabels = listOf("First"))
        val view = viewOf(sessionId)
        openEditor(view)
        view.maximizedDiagramTile = DiagramWorkspaceSectionId.SOURCE
        rule.waitForIdle()
        openFind()
        rule.onNodeWithTag("seq3-source-replace-field").performClick()
        rule.onNodeWithTag("seq3-source-replace-field").performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertFalse(view.sourceFindOpen)
        assertEquals(DiagramWorkspaceSectionId.SOURCE, view.maximizedDiagramTile)

        // Back in the editor, with the bar open again, Esc closes the bar first as well.
        openFind()
        rule.onNodeWithTag("seq3-source-editor").performClick()
        rule.onNodeWithTag("seq3-source-editor").performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertFalse(view.sourceFindOpen)
        assertEquals(DiagramWorkspaceSectionId.SOURCE, view.maximizedDiagramTile)
    }

    @Test
    fun theEditorKeepsTheFindAndCommentChordsFromTheWindowShortcuts() {
        val owned = mutableListOf<Pair<Key, Boolean>>()
        val sessionId = startWorkspace(
            "chords", Seq3Dialect.PLANTUML, messageLabels = emptyList(),
            around = Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) owned += event.key to diagramSourceOwnsChord(event, state)
                false
            },
        )
        val view = viewOf(sessionId)
        state.activeSurface = ActiveSurface.Diagram3(sessionId)
        openEditor(view)
        assertTrue(view.sourceEditorFocused, "the editor reports the keyboard")
        val editor = rule.onNodeWithTag("seq3-source-editor")

        owned.clear()
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Slash) } }
        assertTrue(owned.single { it.first == Key.Slash }.second, "Cmd/Ctrl+/ while the editor has the keyboard")
        owned.clear()
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
        rule.waitForIdle()
        assertTrue(owned.single { it.first == Key.F }.second, "Cmd/Ctrl+F too")
        assertTrue(view.sourceEditorFocused, "the find field keeps the keyboard in the same area")

        owned.clear()
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } } }
        assertFalse(owned.single { it.first == Key.F }.second, "other chords stay global")
        view.sourceEditorFocused = false
        owned.clear()
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
        assertFalse(owned.single { it.first == Key.F }.second, "outside the editor the window owns them")
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun startWorkspace(
        tabId: String,
        dialect: Seq3Dialect,
        messageLabels: List<String>,
        around: Modifier = Modifier,
    ): String {
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
        val sessionId = requireNotNull(state.seq3Sessions.beginEmpty(tab.id))
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddLifeline(Seq3Lifeline("a", "A", setOf("A"), 0)))
        state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddLifeline(Seq3Lifeline("b", "B", setOf("B"), 1)))
        messageLabels.forEach {
            state.seq3Sessions.applyCommand(sessionId, Seq3Command.AddCustomMessage(Seq3CustomMessageSpec("a", "b", it)))
        }
        state.seq3Sessions.setDialect(sessionId, dialect)
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.fillMaxSize().then(around)) { Seq3Workspace(state, sessionId) }
            }
        }
        rule.setTestsViewport(VIEWPORT_WIDTH_DP, VIEWPORT_HEIGHT_DP)
        rule.waitForIdle()
        return sessionId
    }

    private fun viewOf(sessionId: String) = requireNotNull(state.seq3Sessions.viewState(sessionId))

    private fun messageIds(sessionId: String): List<String> =
        requireNotNull(state.seq3Sessions.sessions.singleOrNull { it.id == sessionId }).document.messages.map { it.id }

    /** Opens the editor; live preview is switched off so a half-edited source adds no noise. */
    private fun openEditor(view: Seq3ViewState, showMarkers: Boolean = false) {
        rule.onNodeWithTag("seq3-source-edit").performClick()
        rule.waitForIdle()
        view.sourceLiveEnabled = false
        rule.waitForIdle()
        if (showMarkers) {
            rule.onNodeWithTag("seq3-source-more").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("seq3-source-toggle-markers").performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithTag("seq3-source-editor").performTextInputSelection(TextRange(0))
        rule.waitForIdle()
    }

    private fun openFind() {
        rule.onNodeWithTag("seq3-source-editor").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
        rule.waitForIdle()
    }

    private fun toggleComment() {
        rule.onNodeWithTag("seq3-source-editor").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Slash) } }
        rule.waitForIdle()
    }

    private fun undo() {
        rule.onNodeWithTag("seq3-source-editor").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        rule.waitForIdle()
    }

    private fun editorText(): String =
        rule.onNodeWithTag("seq3-source-editor").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun selectionRange(): TextRange =
        rule.onNodeWithTag("seq3-source-editor").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]

    private fun selectedText(): String = selectionRange().let { editorText().substring(it.min, it.max) }

    private fun findCount(): String =
        rule.onNodeWithTag("seq3-source-find-count", useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    /** The gutter row of raw line [raw] is composed and lies inside the editor's own rectangle. */
    private fun gutterRowShown(raw: Int): Boolean = runCatching {
        val row = rule.onNodeWithTag("seq3-source-gutter-line-$raw", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val editor = rule.onNodeWithTag("seq3-source-editor").fetchSemanticsNode().boundsInRoot
        row.top >= editor.top - 1f && row.bottom <= editor.bottom + 1f
    }.getOrDefault(false)

    /** The state description of the gutter row standing for raw source line [raw], or null. */
    private fun gutterState(raw: Int): String? =
        rule.onNodeWithTag("seq3-source-gutter-line-$raw", useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription)

    /** The raw line numbers of the gutter rows that are composed (the visible ones). */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllGutterLines(): List<Int> =
        onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher("a gutter row") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("seq3-source-gutter-line-") == true
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("seq3-source-gutter-line-")?.toIntOrNull() }

    /** The raw offset of the start of raw source line [raw]; the test selection input is relative to the raw text. */
    private fun rawLineStart(raw: Int, view: Seq3ViewState): Int = seq3SourceLines(view.sourceDraft)[raw - 1].start
}
