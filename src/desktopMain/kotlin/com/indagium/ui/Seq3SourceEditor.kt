@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3LifelineKind
import com.indagium.diagram3.Seq3SourceDiagnostic
import com.indagium.diagram3.Seq3SourceFix
import com.indagium.diagram3.Seq3SourceImportResult
import com.indagium.diagram3.parseSeq3SourceArrowEndpoints
import com.indagium.diagram3.parseSeq3SourceParticipant
import com.indagium.diagram3.sourceEmissionAnchors
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import kotlin.math.roundToInt

/** A caret that settles on a message's line links the diagram to it after this pause. */
internal const val SEQ3_SOURCE_LINK_DEBOUNCE_MS = 150L

/** Only a caret move this soon after a key or pointer press in the editor counts as the user's. */
internal const val SEQ3_SOURCE_LINK_INPUT_WINDOW_MS = 1000L

internal fun openSeq3SourceEditor(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState) {
    if (!state.seq3Sessions.prepareSourceDraft(session.id)) return
    // On a wide window the editor opens beside the preview, if the user has not arranged the tiles.
    if (DiagramWorkspaceSectionId.SOURCE in view.diagramHiddenTiles) {
        diagramWorkspaceSourceBesidePreview(view.diagramTileLayout, view.diagramHostWidthDp)?.let { view.diagramTileLayout = it }
    }
    setDiagramWorkspaceTileVisible(view, DiagramWorkspaceSectionId.SOURCE, true)
    view.sourceFocusRequestGeneration++
    state.autosaveInBackground()
}

/**
 * The one dialect control, behind both the header's PlantUML/Mermaid toggle and the Source tile's
 * dropdown: the diagram's dialect and the editor's change together. An unapplied draft asks first
 * ([Seq3ViewState.sourceDialectChangePending], answered in the editor pane, so the Source tile is
 * shown for the question); [focusEditor] moves the keyboard focus into the editor afterwards.
 */
internal fun changeSeq3Dialect(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    dialect: Seq3Dialect,
    focusEditor: Boolean,
) {
    val unchanged = session.dialect == dialect && (!view.sourceDraftInitialized || view.sourceEditorDialect == dialect)
    if (unchanged) return
    if (view.sourceDraftInitialized && view.sourceEditorDialect != dialect && view.sourceHasUnappliedDraft()) {
        view.sourceDialectChangePending = dialect
        if (setDiagramWorkspaceTileVisible(view, DiagramWorkspaceSectionId.SOURCE, true)) state.autosaveInBackground()
        return
    }
    state.seq3Sessions.switchSourceDraftDialect(session.id, dialect)
    if (focusEditor) view.sourceFocusRequestGeneration++
}

internal fun reloadSeq3SourceDraft(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState) {
    if (!state.seq3Sessions.reloadSourceDraft(session.id)) return
    view.sourceFocusRequestGeneration++
}

internal fun applySeq3SourceDraft(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    confirmEvidenceLoss: Boolean = false,
) {
    when (val result = state.seq3Sessions.importSource(
        session.id,
        view.sourceDraft,
        view.sourceEditorDialect,
        confirmEvidenceLoss = confirmEvidenceLoss,
    )) {
        is Seq3SourceImportResult.Failure -> {
            view.sourceDiagnostics = if (view.sourceDraftStale) emptyList() else result.diagnostics
            view.sourceEvidenceLossPending = false
        }
        is Seq3SourceImportResult.Success -> {
            if (result.evidenceLoss && !confirmEvidenceLoss) {
                view.sourceEvidenceLossPending = true
                view.sourceDiagnostics = emptyList()
            } else {
                view.sourceDiagnostics = emptyList()
                view.sourceEvidenceLossPending = false
            }
        }
    }
    view.sourceFocusRequestGeneration++
}

internal fun Seq3ViewState.sourceHasUnappliedDraft(): Boolean =
    sourceDraftStale || (sourceDraftDirty && sourceDraft != sourceLiveLastAppliedText)

@Composable
internal fun Seq3SourceHeaderActions(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState) {
    Seq3SourceDialectSelector(state, session, view)
    if (LocalTileHeaderWidthDp.current >= SEQ3_SOURCE_TOGGLES_MIN_HEADER_DP) {
        SourceModeToggle("Live preview", view.sourceLiveEnabled, "seq3-source-live-toggle") {
            view.sourceLiveEnabled = !view.sourceLiveEnabled
            view.sourceFocusRequestGeneration++
        }
        SourceModeToggle("Autocomplete", state.settings.diagramSourceAutocomplete, "seq3-source-autocomplete-toggle") {
            toggleSeq3SourceAutocomplete(state)
            view.sourceFocusRequestGeneration++
        }
        SourceModeToggle("Auto-indent", state.settings.diagramSourceAutoIndent, "seq3-source-auto-indent-toggle") {
            toggleSeq3SourceAutoIndent(state)
            view.sourceFocusRequestGeneration++
        }
    } else Seq3SourceSettingsMenu(state, view)
}

/** The Source tile header shows its three toggles inline from this width; narrower, they fold into one Settings menu. */
private const val SEQ3_SOURCE_TOGGLES_MIN_HEADER_DP = 700f

/** Autocomplete and Auto-indent are app-wide settings (JSON settings), not per diagram or view. */
internal fun toggleSeq3SourceAutocomplete(state: AppState) {
    state.updateSettings { it.copy(diagramSourceAutocomplete = !it.diagramSourceAutocomplete) }
}

internal fun toggleSeq3SourceAutoIndent(state: AppState) {
    state.seq3Sessions.setSourceAutoIndent(!state.settings.diagramSourceAutoIndent)
}

@Composable
internal fun Seq3SourceEditorPane(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    val requester = remember(session.id, view.sourceEditorGeneration) { FocusRequester() }
    val editorState = remember(session.id, view.sourceEditorGeneration) { TextFieldState(view.sourceDraft) }
    var lastValue by remember(session.id, view.sourceEditorGeneration) { mutableStateOf(TextFieldValue(view.sourceDraft)) }
    var skipHistoryText by remember(session.id, view.sourceEditorGeneration) { mutableStateOf<String?>(null) }
    val undo = remember(session.id, view.sourceEditorGeneration) { mutableStateListOf<TextFieldValue>() }
    val redo = remember(session.id, view.sourceEditorGeneration) { mutableStateListOf<TextFieldValue>() }
    // The typing run the history is currently extending, and the last external revision this editor
    // has seen. Plain holders: only the key handlers and the collector below read or write them.
    val typingRun = remember(session.id, view.sourceEditorGeneration) { arrayOfNulls<Seq3SourceTypingRun>(1) }
    val seenExternalRevision = remember(session.id, view.sourceEditorGeneration) { intArrayOf(view.sourceExternalRevision) }
    val scroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val gutterListState = rememberLazyListState()
    var showSourceMarkers by remember(session.id, view.sourceEditorGeneration) { mutableStateOf(false) }
    // The word whose suggestion list was closed (Escape, or accepting a suggestion), until a character is typed into it.
    var dismissedSuggestion by remember(session.id, view.sourceEditorGeneration) { mutableStateOf<Seq3SourceSuggestionDismissal?>(null) }
    var selectedSuggestionIndex by remember(session.id, view.sourceEditorGeneration) { mutableStateOf(0) }
    var snippetFields by remember(session.id, view.sourceEditorGeneration) { mutableStateOf<List<SourceSnippetField>>(emptyList()) }
    var snippetText by remember(session.id, view.sourceEditorGeneration) { mutableStateOf(view.sourceDraft) }
    var endpointMenu by remember(session.id, view.sourceEditorGeneration) { mutableStateOf<Int?>(null) }
    var ignoreEndpointRelease by remember(session.id, view.sourceEditorGeneration) { mutableStateOf(false) }
    var showEvidenceLossDialog by remember(session.id) { mutableStateOf(false) }
    // The in-tile issues list under the editor, opened from the status strip.
    var showSourceIssues by remember(session.id) { mutableStateOf(false) }
    var viewportPx by remember(session.id, view.sourceEditorGeneration) { mutableStateOf(0) }
    // The editor's last text layout (of the visible text), which places the suggestion list at the caret.
    var textLayout by remember(session.id, view.sourceEditorGeneration) { mutableStateOf<TextLayoutResult?>(null) }
    // The find bar: its two fields, the current match, and the request (bumped by Cmd+F) to move
    // the keyboard into the find field. The fields are the bar's focus children.
    var findValue by remember(session.id) { mutableStateOf(TextFieldValue("")) }
    var replaceValue by remember(session.id) { mutableStateOf("") }
    var findIndex by remember(session.id) { mutableStateOf(-1) }
    var findFocusGeneration by remember(session.id) { mutableStateOf(0) }
    val findRequester = remember(session.id) { FocusRequester() }
    val findWasOpen = remember(session.id) { booleanArrayOf(false) }
    var editorHasFocus by remember(session.id, view.sourceEditorGeneration) { mutableStateOf(false) }
    var findBarHasFocus by remember(session.id) { mutableStateOf(false) }
    // When a key or the pointer last went to the editor: only a caret move right after one is the
    // user's, and only that links the diagram. An edit that arrives from outside is never one.
    val lastEditorInputMs = remember(session.id, view.sourceEditorGeneration) { longArrayOf(Long.MIN_VALUE / 2) }
    val scope = rememberCoroutineScope()
    var pendingNewDiagramEvidenceRemoval by remember(session.id) { mutableStateOf<Pair<String, Seq3Dialect>?>(null) }
    LaunchedEffect(session.id) {
        if (!view.sourceDraftInitialized) state.seq3Sessions.prepareSourceDraft(session.id)
    }
    LaunchedEffect(session.id, view.sourceFocusRequestGeneration) {
        runCatching { requester.requestFocus() }
    }
    DisposableEffect(session.id) {
        onDispose {
            view.sourceEditorFocused = false
            view.sourceFindOpen = false
        }
    }
    LaunchedEffect(session.id, view.sourceEditorGeneration, editorState) {
        snapshotFlow { TextFieldValue(editorState.text.toString(), editorState.selection) }.collect { typed ->
            // A caret shown at a hidden marker run is a raw selection covering the run, and the next
            // edit would replace it: collapse it to where it is shown before anything is typed.
            val shownCaret = if (showSourceMarkers || typed.text != lastValue.text) null else
                seq3SourceShownCaretAtHiddenRun(typed.text, typed.selection.start, typed.selection.end, view.sourceEditorDialect)
            if (shownCaret != null) {
                editorState.edit { selection = TextRange(shownCaret) }
                return@collect
            }
            // Typing `end`, a block divider or `deactivate` moves its line to the opener's level (Auto-indent on). The correction
            // joins the keystroke's undo step: the history below records the text before it once.
            val outdent = if (typed.text != lastValue.text) {
                seq3SourceTypedOutdent(
                    lastValue.text, typed.text, typed.selection.start, typed.selection.collapsed, view.sourceEditorDialect,
                    autoIndent = state.settings.diagramSourceAutoIndent,
                )
            } else null
            val next = if (outdent == null) typed else {
                val fixed = TextFieldValue(outdent.text, TextRange(outdent.selectionStart, outdent.selectionEnd))
                editorState.edit {
                    replace(0, length, fixed.text)
                    selection = fixed.selection
                }
                fixed
            }
            if (next.text != lastValue.text) {
                if (snippetText != next.text) {
                    snippetFields = rebaseSourceSnippetFields(snippetFields, snippetText, next.text)
                    snippetText = next.text
                }
                if (skipHistoryText != next.text) {
                    // A typing run is one undo step: only its first character records the text before it.
                    val decision = seq3SourceHistoryDecision(lastValue.text, next.text, next.selection, typingRun[0], view.sourceClockMs())
                    typingRun[0] = decision.run
                    if (decision.pushStep) {
                        undo += lastValue
                        if (undo.size > 500) undo.removeAt(0)
                    }
                    redo.clear()
                }
                skipHistoryText = null
                lastValue = next
                state.seq3Sessions.updateSourceDraftText(session.id, next.text)
                view.sourceDiagnostics = emptyList()
            } else {
                lastValue = next
            }
        }
    }
    val sourceText = editorState.text.toString()
    val visibleLines = remember(sourceText, view.sourceEditorDialect, showSourceMarkers) {
        seq3SourceLineNumberMap(sourceText, view.sourceEditorDialect, showSourceMarkers)
    }
    val lineCount = visibleLines.lineCount
    val density = LocalDensity.current
    val lineHeightDp = with(density) { 18.sp.toDp() }
    val lineHeightPx = with(density) { 18.sp.toPx() }
    // The raw ranges the editor hides (marker lines): the layout holds the text without them.
    val hiddenRanges = remember(sourceText, view.sourceEditorDialect, showSourceMarkers) {
        if (showSourceMarkers) emptyList() else seq3SourceMarkerDeletionRanges(sourceText, view.sourceEditorDialect)
    }
    // The marked messages of the live text (raw lines) and the visible line the focused message is
    // drawn from: it is tinted, and the gutter marks it, whoever focused the message.
    val anchors = remember(sourceText, view.sourceEditorDialect) {
        if (sourceText.contains("indagium-seq3:v1")) sourceEmissionAnchors(sourceText, view.sourceEditorDialect) else emptyList()
    }
    val focusedMessage = view.focusedMessageId
    val focusedOccurrence = if (view.selectedOccurrenceMessageId == focusedMessage) view.selectedOccurrenceEntryId else null
    val linkedRawLine = remember(anchors, focusedMessage, focusedOccurrence, sourceText, view.sourceEditorDialect, showSourceMarkers) {
        focusedMessage?.let { seq3SourceLineOfMessage(anchors, it, focusedOccurrence) }?.let { line ->
            seq3SourceVisibleRawLines(sourceText, listOf(line), view.sourceEditorDialect, showSourceMarkers).first()
        }
    }
    val findMatches = remember(sourceText, findValue.text, view.sourceEditorDialect, showSourceMarkers, view.sourceFindOpen) {
        if (view.sourceFindOpen) seq3SourceFindMatches(sourceText, findValue.text, view.sourceEditorDialect, showSourceMarkers)
        else emptyList()
    }
    val currentFind = if (findMatches.isEmpty()) -1 else findIndex.coerceIn(0, findMatches.lastIndex)
    LaunchedEffect(scroll.value, lineCount, lineHeightPx) {
        val firstLine = (scroll.value / lineHeightPx).toInt().coerceIn(0, lineCount - 1)
        val offset = (scroll.value - firstLine * lineHeightPx).roundToInt()
        gutterListState.scrollToItem(firstLine, offset)
    }

    // The visible lines the diagnostics belong to: a diagnostic on a hidden marker line lands on
    // the line shown in its place, and the line the caret is on picks its message for the strip.
    val diagnosticLines = remember(sourceText, view.sourceDiagnostics, view.sourceEditorDialect, showSourceMarkers) {
        seq3SourceVisibleRawLines(sourceText, view.sourceDiagnostics.map { it.line }, view.sourceEditorDialect, showSourceMarkers).toSet()
    }
    val caretDiagnostic = seq3SourceDiagnosticAtCaret(
        sourceText, editorState.selection.start, view.sourceDiagnostics, view.sourceEditorDialect, showSourceMarkers,
    )
    // The numbers the gutter shows for the diagnostics' lines: the status strip and the issues list
    // use the same numbering as the gutter (visible lines 1..n while markers are hidden).
    val diagnosticNumbers = remember(sourceText, view.sourceDiagnostics, view.sourceEditorDialect, showSourceMarkers) {
        view.sourceDiagnostics.zip(
            seq3SourceDisplayLines(sourceText, view.sourceDiagnostics.map { it.line }, view.sourceEditorDialect, showSourceMarkers),
        ).toMap()
    }

    fun currentEditorValue(): TextFieldValue = TextFieldValue(editorState.text.toString(), editorState.selection)

    fun setEditorValue(next: TextFieldValue, record: Boolean = true) {
        val current = currentEditorValue()
        typingRun[0] = null
        if (next.text != current.text && record) {
            undo += current
            if (undo.size > 500) undo.removeAt(0)
            redo.clear()
            view.sourceDiagnostics = emptyList()
        }
        skipHistoryText = next.text
        editorState.edit {
            replace(0, length, next.text)
            selection = next.selection
        }
        lastValue = next
        snippetText = next.text
        state.seq3Sessions.updateSourceDraftText(session.id, next.text)
    }

    fun insertSnippet(snippet: String, fields: List<SourceSnippetField>, header: Boolean) {
        val inserted = insertSeq3SourceSnippet(
            editorState.text.toString(), editorState.selection, snippet, view.sourceEditorDialect, header,
            autoIndent = state.settings.diagramSourceAutoIndent,
        )
        val next = inserted.value
        snippetFields = fields.map {
            it.copy(start = inserted.mapSnippetOffset(it.start), end = inserted.mapSnippetOffset(it.end))
        }
        val first = snippetFields.firstOrNull()
        endpointMenu = null
        setEditorValue(if (first == null) next else next.copy(selection = TextRange(first.start, first.end)), true)
        runCatching { requester.requestFocus() }
    }

    /** An indentation edit: the same bookkeeping as a snippet insertion, kept to one undo step. */
    fun applySourceEdit(edit: Seq3SourceEdit) {
        val old = editorState.text.toString()
        if (edit.text != old) snippetFields = rebaseSourceSnippetFields(snippetFields, old, edit.text)
        endpointMenu = null
        setEditorValue(TextFieldValue(edit.text, TextRange(edit.selectionStart, edit.selectionEnd)), true)
    }

    /** Scrolls raw source line [rawLine] (the nearest visible line when that one is a hidden marker
     *  line) into view, centred when it was outside; the caret and the focus stay where they are. */
    fun revealLine(rawLine: Int) {
        val line = seq3SourceVisibleRawLines(editorState.text.toString(), listOf(rawLine), view.sourceEditorDialect, showSourceMarkers).first()
        val lineHeight = lineHeightPx.roundToInt()
        val top = visibleLines.indexOfLine(line) * lineHeight
        val visibleFrom = scroll.value
        if (top < visibleFrom || (viewportPx > 0 && top + lineHeight > visibleFrom + viewportPx)) {
            scope.launch { scroll.scrollTo((top - (viewportPx - lineHeight).coerceAtLeast(0) / 2).coerceAtLeast(0)) }
        }
    }

    /** Moves the caret to raw source line [rawLine] (the nearest visible line when that one is a
     *  hidden marker line), gives the editor focus back and scrolls the line into view. */
    fun goToLine(rawLine: Int) {
        val text = editorState.text.toString()
        val line = seq3SourceVisibleRawLines(text, listOf(rawLine), view.sourceEditorDialect, showSourceMarkers).first()
        val offset = seq3SourceCaretOffsetForLine(text, line, view.sourceEditorDialect, showSourceMarkers)
        endpointMenu = null
        editorState.edit { this.selection = TextRange(offset) }
        runCatching { requester.requestFocus() }
        revealLine(line)
    }

    /** A diagnostic's quick fix as one undoable editor edit. */
    fun applyIssueFix(fix: Seq3SourceFix) {
        val range = editorState.selection
        applySourceEdit(seq3SourceApplyFix(editorState.text.toString(), fix, range.start, range.end))
        runCatching { requester.requestFocus() }
    }

    /** Cmd/Ctrl+/: comments or uncomments the selected lines as one undoable edit. */
    fun toggleComment() {
        val text = editorState.text.toString()
        val range = editorState.selection
        // A caret shown at a hidden marker run is a raw selection over the run: it stands for the
        // start of the statement after it, which is the line the user means.
        val shown = if (showSourceMarkers) null else seq3SourceShownCaretAtHiddenRun(text, range.start, range.end, view.sourceEditorDialect)
        val edit = seq3SourceToggleComment(text, shown ?: range.start, shown ?: range.end, view.sourceEditorDialect)
        if (edit.text == text) return
        applySourceEdit(edit)
    }

    /** Selects match [index] of [matches] in the editor and scrolls it into view; the keyboard stays
     *  wherever it is, normally in the find bar. */
    fun selectFindMatch(matches: List<Seq3SourceRange>, index: Int) {
        val match = matches.getOrNull(index) ?: return
        findIndex = index
        endpointMenu = null
        editorState.edit { this.selection = TextRange(match.start, match.endExclusive) }
        revealLine(seq3SourceRawLineAt(editorState.text.toString(), match.start))
    }

    fun stepFind(forward: Boolean) {
        selectFindMatch(findMatches, seq3SourceFindStep(findMatches, currentFind, forward))
    }

    /** Cmd/Ctrl+F: opens the bar with the selected text (one line) as the query and the keyboard in its field. */
    fun openFind() {
        val text = editorState.text.toString()
        val range = editorState.selection
        val selected = if (range.collapsed) "" else text.substring(range.min, range.max)
        val usable = selected.isNotBlank() && selected.length <= 200 && '\n' !in selected &&
            seq3SourceShownCaretAtHiddenRun(text, range.start, range.end, view.sourceEditorDialect) == null
        if (usable) findValue = TextFieldValue(selected, TextRange(0, selected.length))
        view.sourceFindOpen = true
        findFocusGeneration++
    }

    /** Replace: the current match becomes the replacement, then the next match is selected. */
    fun replaceCurrentMatch() {
        val match = findMatches.getOrNull(currentFind) ?: return
        val edit = seq3SourceReplaceMatch(editorState.text.toString(), match, replaceValue)
        applySourceEdit(edit)
        val remaining = seq3SourceFindMatches(edit.text, findValue.text, view.sourceEditorDialect, showSourceMarkers)
        selectFindMatch(remaining, seq3SourceFindIndexFrom(remaining, edit.selectionEnd))
    }

    /** Replace all: every match in one edit, so one undo step brings them all back. */
    fun replaceEveryMatch() {
        if (findMatches.isEmpty()) return
        val range = editorState.selection
        applySourceEdit(seq3SourceReplaceAll(editorState.text.toString(), findMatches, replaceValue, range.start, range.end))
        findIndex = 0
    }

    // Typing in the find field moves to the first match at or after where the editor's selection is.
    LaunchedEffect(session.id, findValue.text, view.sourceFindOpen) {
        if (!view.sourceFindOpen || findValue.text.isEmpty()) return@LaunchedEffect
        selectFindMatch(findMatches, seq3SourceFindIndexFrom(findMatches, editorState.selection.min))
    }
    LaunchedEffect(session.id, findFocusGeneration) {
        if (findFocusGeneration == 0) return@LaunchedEffect
        runCatching { findRequester.requestFocus() }
        findValue = findValue.copy(selection = TextRange(0, findValue.text.length))
    }
    // Closing the bar, by Esc or its button, gives the keyboard back to the editor.
    LaunchedEffect(session.id, view.sourceFindOpen) {
        if (view.sourceFindOpen) findWasOpen[0] = true
        else if (findWasOpen[0]) {
            findWasOpen[0] = false
            runCatching { requester.requestFocus() }
        }
    }

    // A caret that settles on a marked message statement focuses that message in the diagram. Only a
    // caret the user moved links (a key or the pointer just went to the editor); one that moved
    // because the text was replaced from outside, or by a find or an issue jump, does not.
    val caretRawLine = seq3SourceRawLineAt(sourceText, editorState.selection.end)
    LaunchedEffect(session.id, view.sourceEditorGeneration, caretRawLine, editorState.selection.end, anchors) {
        delay(SEQ3_SOURCE_LINK_DEBOUNCE_MS)
        if (view.sourceClockMs() - lastEditorInputMs[0] > SEQ3_SOURCE_LINK_INPUT_WINDOW_MS) return@LaunchedEffect
        val anchor = seq3SourceAnchorAtLine(anchors, caretRawLine) ?: return@LaunchedEffect
        if (view.focusedMessageId == anchor.messageId) return@LaunchedEffect
        val latest = state.seq3Sessions.sessions.firstOrNull { it.id == session.id } ?: return@LaunchedEffect
        if (latest.document.messages.none { it.id == anchor.messageId }) return@LaunchedEffect
        view.focusedMessageId = anchor.messageId
        view.scrollRequestId = anchor.messageId
    }

    // The other way: a message the canvas (or the lists, or j/k) focuses has its line scrolled into
    // view. The caret stays; the line is tinted by [linkedRawLine].
    val lastReveal = remember(session.id, view.sourceEditorGeneration) { arrayOf<Pair<String?, String?>>(view.focusedMessageId to view.scrollRequestId) }
    LaunchedEffect(session.id, view.sourceEditorGeneration, view.focusedMessageId, view.scrollRequestId) {
        val key = view.focusedMessageId to view.scrollRequestId
        if (lastReveal[0] == key) return@LaunchedEffect
        lastReveal[0] = key
        val id = view.scrollRequestId ?: view.focusedMessageId ?: return@LaunchedEffect
        val occurrence = if (view.selectedOccurrenceMessageId == id) view.selectedOccurrenceEntryId else null
        seq3SourceLineOfMessage(anchors, id, occurrence)?.let { revealLine(it) }
    }

    // Text that changes from outside (a canvas edit, Reload, Discard) arrives as one undoable edit:
    // the editor, its history and its focus stay, and the caret keeps its place in the text.
    LaunchedEffect(session.id, view.sourceEditorGeneration, view.sourceExternalRevision) {
        if (seenExternalRevision[0] == view.sourceExternalRevision) return@LaunchedEffect
        seenExternalRevision[0] = view.sourceExternalRevision
        val external = view.sourceExternalText ?: return@LaunchedEffect
        val current = currentEditorValue()
        if (external == current.text) return@LaunchedEffect
        snippetFields = rebaseSourceSnippetFields(snippetFields, current.text, external)
        endpointMenu = null
        val caret = seq3SourceMapCaret(current.text, external, current.selection.start)
        setEditorValue(TextFieldValue(external, TextRange(caret)), record = true)
    }

    // The empty canvas' "+ Participant": once the draft is ready the editor inserts a new participant.
    LaunchedEffect(session.id, view.sourceEditorGeneration, view.sourceInsertParticipantRequest, view.sourceDraftInitialized) {
        if (!view.sourceInsertParticipantRequest || !view.sourceDraftInitialized) return@LaunchedEffect
        view.sourceInsertParticipantRequest = false
        val (snippet, fields) = seq3SourceParticipantSnippet(editorState.text.toString(), view.sourceEditorDialect, false)
        insertSnippet(snippet, fields, true)
    }

    val selection = editorState.selection
    val endpointIndex = snippetFields.indexOfFirst { it.endpoint && selection.start >= it.start && selection.end <= it.end }
    val endpointField = snippetFields.getOrNull(endpointIndex)
        ?: sourceEndpointAt(sourceText, selection, view.sourceEditorDialect)
    val endpointQuery = endpointField?.let { sourceText.substring(it.start.coerceAtMost(sourceText.length), it.end.coerceAtMost(sourceText.length)) }.orEmpty()
    val participantChoices = sourceParticipants(sourceText, view.sourceEditorDialect)
    val identifierChoices = if (state.settings.diagramSourceAutocomplete && endpointField != null && selection.collapsed && endpointQuery.isNotBlank()) {
        participantChoices.filter { it.alias.startsWith(endpointQuery, true) || it.name.startsWith(endpointQuery, true) }
            .filterNot { it.alias == endpointQuery }
    } else emptyList()
    fun chooseEndpoint(index: Int, alias: String) {
        val old = currentEditorValue()
        val field = snippetFields.getOrNull(index) ?: sourceEndpointAt(old.text, old.selection, view.sourceEditorDialect) ?: return
        val nextText = old.text.replaceRange(field.start, field.end, alias)
        snippetFields = rebaseSourceSnippetFields(snippetFields, old.text, nextText)
        setEditorValue(TextFieldValue(nextText, TextRange(field.start, field.start + alias.length)), true)
        endpointMenu = null
        runCatching { requester.requestFocus() }
    }

    val suggestionTrigger = if (state.settings.diagramSourceAutocomplete && endpointField == null) {
        seq3SourceSuggestionTrigger(sourceText, editorState.selection, view.sourceEditorDialect)
    } else null
    val activeDismissal = seq3SourceDismissalAfterEdit(dismissedSuggestion, suggestionTrigger)
    if (activeDismissal != dismissedSuggestion) dismissedSuggestion = activeDismissal
    val sourceSuggestions = suggestionTrigger?.let {
        seq3SourceActiveSuggestions(sourceText, it, view.sourceEditorDialect, participantChoices.map { choice -> choice.alias }, participantChoices)
    }.orEmpty()
    LaunchedEffect(suggestionTrigger?.query, view.sourceEditorDialect) {
        selectedSuggestionIndex = 0
    }
    val suggestionsVisible = suggestionTrigger != null && sourceSuggestions.isNotEmpty() && activeDismissal == null
    if (selectedSuggestionIndex !in sourceSuggestions.indices) selectedSuggestionIndex = 0

    // The preview keeps following the draft while the suggestion list is open; the half-typed word
    // the list belongs to is left out of what is imported, so it is not flagged while it is typed.
    val previewText = if (suggestionsVisible && suggestionTrigger != null) {
        sourceText.removeRange(suggestionTrigger.start.coerceIn(0, sourceText.length), suggestionTrigger.end.coerceIn(0, sourceText.length))
    } else sourceText
    LaunchedEffect(
        session.id,
        sourceText,
        previewText,
        view.sourceEditorDialect,
        view.sourceLiveEnabled,
        view.sourceDraftInitialized,
    ) {
        if (!view.sourceLiveEnabled || !view.sourceDraftInitialized) return@LaunchedEffect
        delay(400)
        // sourceDraft moves on first when New diagram or a reload replaces the source, before this
        // effect is cancelled; applying sourceText then would bring the replaced text back.
        if (editorState.text.toString() == sourceText && view.sourceDraft == sourceText && view.sourceLiveEnabled) {
            state.seq3Sessions.applyLiveSourceDraft(session.id, sourceText, view.sourceEditorDialect, previewText)
        }
    }

    fun insertSuggestion(index: Int) {
        val trigger = suggestionTrigger ?: return
        val suggestion = sourceSuggestions.getOrNull(index) ?: return
        val (inserted, fields) = insertSeq3SourceSuggestionWithFields(
            sourceText, trigger, suggestion, view.sourceEditorDialect, state.settings.diagramSourceAutoIndent,
        )
        snippetFields = fields
        endpointMenu = null
        setEditorValue(inserted, true)
        // The accepted word stays closed: `Alias` is a prefix of `Alias_2`, which must not reopen the list.
        dismissedSuggestion = seq3SourceDismissalAfterAccept(inserted, view.sourceEditorDialect)
        selectedSuggestionIndex = 0
        runCatching { requester.requestFocus() }
    }

    Column(
        modifier.background(tc.bg),
    ) {
        Seq3SourceActionForms(
            state = state,
            session = session,
            view = view,
            editorState = editorState,
            // A lambda, not ::insertSnippet: a local function reference's equals() ignores what it
            // captures, so Compose would treat a reloaded editor's reference as unchanged and keep
            // the menu callbacks writing into the previous generation's editorState.
            insertSnippet = { snippet, fields, header -> insertSnippet(snippet, fields, header) },
            reclaimFocus = { runCatching { requester.requestFocus() } },
            showSourceMarkers = showSourceMarkers,
            onToggleSourceMarkers = { showSourceMarkers = !showSourceMarkers },
            onNewDiagramEvidenceLoss = { source, dialect ->
                pendingNewDiagramEvidenceRemoval = source to dialect
                view.sourceEvidenceLossPending = true
                showEvidenceLossDialog = true
            },
        )
        if (view.sourceFindOpen) {
            Seq3SourceFindBar(
                findValue = findValue,
                onFindValue = { findValue = it },
                replaceValue = replaceValue,
                onReplaceValue = { replaceValue = it },
                current = currentFind,
                total = findMatches.size,
                findRequester = findRequester,
                onFocusChanged = { hasFocus ->
                    findBarHasFocus = hasFocus
                    view.sourceEditorFocused = editorHasFocus || findBarHasFocus
                    view.textFieldFocused = view.sourceEditorFocused
                },
                onStep = { forward -> stepFind(forward) },
                onReplace = { replaceCurrentMatch() },
                onReplaceAll = { replaceEveryMatch() },
                onSelectAll = { findValue = findValue.copy(selection = TextRange(0, findValue.text.length)) },
                onReclaimEditor = { runCatching { requester.requestFocus() } },
                onClose = { view.sourceFindOpen = false },
            )
        }
        Row(Modifier.weight(1f).fillMaxWidth().background(tc.p2)) {
            LazyColumn(
                Modifier.width(42.dp).fillMaxHeight().padding(top = 8.dp, bottom = 8.dp),
                state = gutterListState,
                userScrollEnabled = false,
            ) {
                items(lineCount) { index ->
                    // The tag keeps the raw line (a stable name for the source line); the number
                    // shown counts the visible lines.
                    val line = visibleLines.lineNumberAt(index)
                    val hasDiagnostic = line in diagnosticLines
                    val isLinked = line == linkedRawLine
                    Box(
                        Modifier.fillMaxWidth().height(lineHeightDp)
                            .background(if (hasDiagnostic) tc.warnBg else if (isLinked) tc.abg else Color.Transparent)
                            .padding(end = 8.dp)
                            .testTag("seq3-source-gutter-line-$line")
                            .semantics {
                                val parts = listOfNotNull("Has issue".takeIf { hasDiagnostic }, "Linked line".takeIf { isLinked })
                                if (parts.isNotEmpty()) stateDescription = parts.joinToString(", ")
                            },
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        AppText(
                            (index + 1).toString(),
                            color = if (hasDiagnostic) tc.warn else if (isLinked) tc.ac else tc.td,
                            fontSize = 11.sp,
                            fontFamily = MONO,
                        )
                    }
                }
            }
            BasicTextField(
                state = editorState,
                textStyle = TextStyle(color = tc.tx, fontSize = 11.sp, lineHeight = 18.sp, fontFamily = MONO),
                cursorBrush = SolidColor(tc.ac),
                lineLimits = TextFieldLineLimits.MultiLine(),
                outputTransformation = remember(view.sourceEditorDialect, showSourceMarkers, tc) {
                    val palette = seq3SourcePalette(tc)
                    OutputTransformation {
                        transformSeq3SourceOutput(this, view.sourceEditorDialect, palette, showSourceMarkers)
                    }
                },
                onTextLayout = { getResult -> getResult()?.let { textLayout = it } },
                scrollState = scroll,
                modifier = Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp, bottom = 8.dp)
                    .onSizeChanged { viewportPx = it.height }
                    .testTag("seq3-source-editor")
                    .focusRequester(requester)
                    .onFocusChanged {
                        // The editor and the find bar are one focus area for the window's shortcuts and the Esc rule.
                        editorHasFocus = it.hasFocus
                        view.sourceEditorFocused = editorHasFocus || findBarHasFocus
                        view.textFieldFocused = view.sourceEditorFocused
                    }
                    .horizontalScroll(horizontalScroll)
                    .onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) {
                        lastEditorInputMs[0] = view.sourceClockMs()
                    }
                    .onPointerEvent(PointerEventType.Release, pass = PointerEventPass.Final) {
                        if (ignoreEndpointRelease) ignoreEndpointRelease = false
                        else {
                            val selected = editorState.selection
                            endpointMenu = snippetFields.indexOfFirst { it.endpoint && selected.start >= it.start && selected.end <= it.end }
                                .takeIf { it >= 0 } ?: sourceEndpointAt(editorState.text.toString(), selected, view.sourceEditorDialect)?.let { -1 }
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        lastEditorInputMs[0] = view.sourceClockMs()
                        if (event.key == Key.Escape && endpointMenu != null) {
                            endpointMenu = null
                            return@onPreviewKeyEvent true
                        }
                        if (event.key == Key.Tab && !(event.isCtrlPressed || event.isMetaPressed) && snippetFields.isNotEmpty()) {
                            val currentText = editorState.text.toString()
                            if (snippetText != currentText) {
                                snippetFields = rebaseSourceSnippetFields(snippetFields, snippetText, currentText)
                                snippetText = currentText
                            }
                            val selected = editorState.selection
                            val current = snippetFields.indexOfFirst { selected.start >= it.start && selected.end <= it.end }
                            val nextIndex = if (event.isShiftPressed) current - 1 else current + 1
                            if (nextIndex in snippetFields.indices) {
                                val field = snippetFields[nextIndex]
                                editorState.edit { this.selection = TextRange(field.start, field.end) }
                                endpointMenu = null
                                return@onPreviewKeyEvent true
                            }
                            snippetFields = emptyList()
                        }
                        if (identifierChoices.isNotEmpty() && event.key == Key.Enter) {
                            chooseEndpoint(endpointIndex, identifierChoices.first().alias)
                            return@onPreviewKeyEvent true
                        }
                        if (suggestionsVisible && !(event.isCtrlPressed || event.isMetaPressed)) {
                            when (event.key) {
                                Key.DirectionDown -> {
                                    selectedSuggestionIndex = (selectedSuggestionIndex + 1).coerceAtMost(sourceSuggestions.lastIndex)
                                    return@onPreviewKeyEvent true
                                }
                                Key.DirectionUp -> {
                                    selectedSuggestionIndex = (selectedSuggestionIndex - 1).coerceAtLeast(0)
                                    return@onPreviewKeyEvent true
                                }
                                Key.Enter, Key.Tab -> {
                                    insertSuggestion(selectedSuggestionIndex)
                                    return@onPreviewKeyEvent true
                                }
                                Key.Escape -> {
                                    dismissedSuggestion = seq3SourceDismissalOf(suggestionTrigger)
                                    runCatching { requester.requestFocus() }
                                    view.textFieldFocused = true
                                    return@onPreviewKeyEvent true
                                }
                            }
                        }
                        if (event.key == Key.Tab && !(event.isCtrlPressed || event.isMetaPressed || event.isAltPressed)) {
                            val range = editorState.selection
                            applySourceEdit(seq3SourceTab(editorState.text.toString(), range.start, range.end, event.isShiftPressed))
                            return@onPreviewKeyEvent true
                        }
                        if ((event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                            !(event.isCtrlPressed || event.isMetaPressed || event.isAltPressed)
                        ) {
                            val range = editorState.selection
                            val edit = seq3SourceEnter(
                                editorState.text.toString(), range.start, range.end, view.sourceEditorDialect,
                                autoIndent = state.settings.diagramSourceAutoIndent,
                            )
                            if (edit != null) {
                                applySourceEdit(edit)
                                return@onPreviewKeyEvent true
                            }
                        }
                        if ((event.key == Key.Backspace || event.key == Key.Delete) &&
                            !(event.isCtrlPressed || event.isMetaPressed || event.isAltPressed)
                        ) {
                            val range = editorState.selection
                            val edit = seq3SourceDeleteNextToMarkers(
                                editorState.text.toString(),
                                range.start,
                                range.end,
                                view.sourceEditorDialect,
                                forward = event.key == Key.Delete,
                                showMarkers = showSourceMarkers,
                            )
                            if (edit != null) {
                                if (edit.text == editorState.text.toString()) {
                                    editorState.edit { this.selection = TextRange(edit.selectionStart, edit.selectionEnd) }
                                } else {
                                    applySourceEdit(edit)
                                }
                                return@onPreviewKeyEvent true
                            }
                        }
                        if (!(event.isCtrlPressed || event.isMetaPressed)) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.Z && event.isShiftPressed || event.key == Key.Y -> {
                                if (redo.isNotEmpty()) {
                                    undo += currentEditorValue()
                                    setEditorValue(redo.removeAt(redo.lastIndex), record = false)
                                }
                                true
                            }
                            event.key == Key.Z -> {
                                if (undo.isNotEmpty()) {
                                    redo += currentEditorValue()
                                    setEditorValue(undo.removeAt(undo.lastIndex), record = false)
                                }
                                true
                            }
                            event.key == Key.F && !event.isShiftPressed && !event.isAltPressed -> {
                                openFind()
                                true
                            }
                            event.key == Key.Slash && !event.isShiftPressed && !event.isAltPressed -> {
                                toggleComment()
                                true
                            }
                            else -> false
                        }
                    },
                decorator = TextFieldDecorator { inner ->
                    // At least the viewport wide, so the suggestion list never sits outside the box.
                    Box(Modifier.widthIn(min = with(density) { horizontalScroll.viewportSize.toDp() })) {
                        // Behind the text: the linked message's line, and the find matches (the current one stronger).
                        if (linkedRawLine != null || findMatches.isNotEmpty()) {
                            Box(
                                Modifier.matchParentSize().drawBehind {
                                    textLayout?.let { layout ->
                                        drawSeq3SourceHighlights(
                                            layout = layout,
                                            scrollY = scroll.value.toFloat(),
                                            linkedLine = linkedRawLine?.let { visibleLines.indexOfLine(it) },
                                            linkedColor = tc.abg,
                                            matches = findMatches,
                                            current = currentFind,
                                            hidden = hiddenRanges,
                                            matchColor = tc.searchMatchBg,
                                            currentColor = tc.searchCurrentBg,
                                        )
                                    }
                                },
                            )
                        }
                        if (editorState.text.isEmpty()) {
                            AppText(sourceStartHint(view.sourceEditorDialect), color = tc.td, fontSize = 11.sp, fontFamily = MONO)
                        }
                        inner()
                        val choices = if (endpointMenu != null) participantChoices else identifierChoices
                        if (choices.isNotEmpty() && (endpointMenu != null || identifierChoices.isNotEmpty())) {
                            Column(
                                Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 6.dp)
                                    .widthIn(min = 150.dp, max = 260.dp).width(IntrinsicSize.Max).heightIn(max = 200.dp)
                                    .background(tc.p, RoundedCornerShape(6.dp)).border(1.dp, tc.br, RoundedCornerShape(6.dp))
                                    .verticalScroll(rememberScrollState()).padding(4.dp).testTag("seq3-source-endpoint-choices"),
                            ) {
                                choices.forEach { participant ->
                                    Seq3DropdownMenuItem(sourceParticipantChoiceLabel(participant, participantChoices), active = false) {
                                        ignoreEndpointRelease = true
                                        chooseEndpoint(endpointMenu ?: endpointIndex, participant.alias)
                                    }
                                }
                            }
                        } else if (suggestionsVisible) {
                            // The list opens under the line the suggestion belongs to, or above it
                            // when the viewport has no room below. The caret rectangle comes from
                            // the layout of the visible text, so the hidden marker lines are
                            // taken out of the raw offset first.
                            val layout = textLayout
                            // The cursor rectangle gives the column; the line's own top and bottom
                            // (a cursor is shorter than the 18sp line) bound the list's way.
                            val anchor = layout?.let {
                                val visible = seq3SourceVisibleOffset(suggestionTrigger.start, hiddenRanges)
                                    .coerceIn(0, it.layoutInput.text.length)
                                val line = it.getLineForOffset(visible)
                                Rect(it.getCursorRect(visible).left, it.getLineTop(line), 0f, it.getLineBottom(line))
                            }
                            Box(Modifier.matchParentSize()) {
                                val listScroll = rememberScrollState()
                                // The list is cut to its room (see anchoredAtCaret) and scrolls; the bar
                                // shows only then. It sits in a lane of its own at the right, reserved
                                // whether or not the bar shows (so the layout never depends on the scroll
                                // state it produces), and never covers a pictogram or a label. The
                                // derived state keeps the layout's own writes to maxValue (anchoredAtCaret
                                // measures twice) from recomposing the list on every frame.
                                val overflowing by remember { derivedStateOf { listScroll.maxValue > 0 } }
                                Box(
                                    Modifier.anchoredAtCaret(
                                        left = anchor?.left ?: 0f,
                                        top = (anchor?.top ?: 0f) - scroll.value,
                                        bottom = (anchor?.bottom ?: lineHeightPx) - scroll.value,
                                        gap = with(density) { 2.dp.toPx() },
                                    )
                                        .widthIn(min = 210.dp, max = 320.dp).width(IntrinsicSize.Max).heightIn(max = 208.dp)
                                        .shadow(8.dp, RoundedCornerShape(8.dp))
                                        .background(tc.p, RoundedCornerShape(8.dp))
                                        .border(1.dp, tc.br, RoundedCornerShape(8.dp))
                                        .testTag("seq3-source-suggestions"),
                                ) {
                                    Column(
                                        Modifier.verticalScroll(listScroll).padding(
                                            start = 4.dp, top = 4.dp, bottom = 4.dp, end = SEQ3_SOURCE_LIST_SCROLLBAR_LANE,
                                        ),
                                    ) {
                                        sourceSuggestions.forEachIndexed { index, suggestion ->
                                            Seq3SourceSuggestionRow(
                                                suggestion = suggestion,
                                                prefix = suggestionTrigger.prefix,
                                                active = index == selectedSuggestionIndex,
                                                onClick = { insertSuggestion(index) },
                                            )
                                        }
                                    }
                                    if (overflowing) {
                                        Box(Modifier.matchParentSize().padding(vertical = 5.dp, horizontal = 3.dp)) {
                                            ThinVerticalScrollbar(
                                                listScroll,
                                                Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag("seq3-source-suggestions-scrollbar"),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
        if (showSourceIssues && view.sourceDiagnostics.isNotEmpty()) {
            Seq3SourceIssuesList(
                diagnostics = view.sourceDiagnostics,
                lineNumbers = diagnosticNumbers,
                caretDiagnostic = caretDiagnostic,
                onJump = { goToLine(it) },
                onFix = { applyIssueFix(it) },
                onClose = {
                    showSourceIssues = false
                    runCatching { requester.requestFocus() }
                },
            )
        }
        Seq3SourceStatusStrip(
            state = state,
            session = session,
            view = view,
            suggestionsVisible = suggestionsVisible,
            caretDiagnostic = caretDiagnostic,
            caretDiagnosticLine = caretDiagnostic?.let { diagnosticNumbers[it] },
            onReviewEvidence = { showEvidenceLossDialog = true },
            onToggleIssues = {
                showSourceIssues = !showSourceIssues
                runCatching { requester.requestFocus() }
            },
        )
    }

    if (showEvidenceLossDialog && view.sourceEvidenceLossPending) {
        Dialog(
            onDismissRequest = {
                showEvidenceLossDialog = false
                view.sourceEvidenceLossPending = false
                pendingNewDiagramEvidenceRemoval = null
                runCatching { requester.requestFocus() }
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                Modifier.width(420.dp).background(tc.p, RoundedCornerShape(8.dp))
                    .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AppText("Remove log evidence?", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                AppText(
                    if (pendingNewDiagramEvidenceRemoval != null) {
                        "Starting this new diagram removes linked log rows and replaces the current diagram. " +
                            "Those source links cannot be restored from the new source."
                    } else {
                        "This source edit removes marked log rows from the diagram. " +
                            "Applying it will keep the authored diagram structure, but those source links " +
                            "cannot be restored from the draft."
                    },
                    color = tc.ts,
                    fontSize = 11.sp,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    AppButton(
                        "Keep editing",
                        onClick = {
                            showEvidenceLossDialog = false
                            view.sourceEvidenceLossPending = false
                            pendingNewDiagramEvidenceRemoval = null
                            runCatching { requester.requestFocus() }
                        },
                        variant = ButtonVariant.Ghost,
                    )
                    AppButton(
                        "Remove evidence and apply",
                        onClick = {
                            showEvidenceLossDialog = false
                            val newDiagram = pendingNewDiagramEvidenceRemoval
                            pendingNewDiagramEvidenceRemoval = null
                            if (newDiagram == null) {
                                applySeq3SourceDraft(state, session, view, confirmEvidenceLoss = true)
                            } else {
                                when (val result = state.seq3Sessions.startNewSourceDiagram(
                                    session.id,
                                    newDiagram.first,
                                    newDiagram.second,
                                    confirmEvidenceLoss = true,
                                )) {
                                    is Seq3SourceImportResult.Failure -> view.sourceDiagnostics = result.diagnostics
                                    is Seq3SourceImportResult.Success -> {
                                        view.sourceDiagnostics = emptyList()
                                        view.sourceEvidenceLossPending = false
                                    }
                                }
                            }
                        },
                        variant = ButtonVariant.Primary,
                    )
                }
            }
        }
    }
    val pendingDialect = view.sourceDialectChangePending
    if (pendingDialect != null) {
        Dialog(
            onDismissRequest = {
                view.sourceDialectChangePending = null
                runCatching { requester.requestFocus() }
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                Modifier.width(390.dp).background(tc.p, RoundedCornerShape(8.dp))
                    .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AppText("Discard source draft?", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                AppText(
                    "Switching to ${sourceDialectName(pendingDialect)} replaces this unsaved draft with the latest diagram source.",
                    color = tc.ts,
                    fontSize = 11.sp,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    AppButton(
                        "Keep editing",
                        onClick = {
                            view.sourceDialectChangePending = null
                            runCatching { requester.requestFocus() }
                        },
                        variant = ButtonVariant.Ghost,
                    )
                    AppButton(
                        "Discard and switch",
                        onClick = {
                            state.seq3Sessions.switchSourceDraftDialect(session.id, pendingDialect)
                            view.sourceDialectChangePending = null
                            view.sourceFocusRequestGeneration++
                            runCatching { requester.requestFocus() }
                        },
                        variant = ButtonVariant.Primary,
                        modifier = Modifier.testTag("seq3-source-confirm-dialect-switch"),
                    )
                }
            }
        }
    }
}

/** The find bar over the editor: a find row (field, "n of m", previous, next, close) and a replace
 *  row (field, Replace, Replace all). Enter steps to the next match (Shift+Enter the previous) in the
 *  find field and replaces in the replace field. */
@Composable
private fun Seq3SourceFindBar(
    findValue: TextFieldValue,
    onFindValue: (TextFieldValue) -> Unit,
    replaceValue: String,
    onReplaceValue: (String) -> Unit,
    current: Int,
    total: Int,
    findRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onStep: (Boolean) -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onSelectAll: () -> Unit,
    onReclaimEditor: () -> Unit,
    onClose: () -> Unit,
) {
    val tc = tc()
    val hasQuery = findValue.text.isNotEmpty()
    Column(
        Modifier.fillMaxWidth().background(tc.p).onFocusChanged { onFocusChanged(it.hasFocus) }.testTag("seq3-source-find-bar"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            InlineField(
                value = findValue,
                onValue = onFindValue,
                placeholder = "Find",
                fontSize = 11.sp,
                modifier = Modifier.weight(1f).widthIn(min = 60.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.Enter || event.key == Key.NumPadEnter -> { onStep(!event.isShiftPressed); true }
                            (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.F -> { onSelectAll(); true }
                            else -> false
                        }
                    }
                    .focusRequester(findRequester)
                    .testTag("seq3-source-find-field"),
                onCancel = onClose,
            )
            AppText(
                when {
                    !hasQuery -> ""
                    total == 0 -> "No results"
                    else -> "${current + 1} of $total"
                },
                color = tc.ts,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 56.dp).testTag("seq3-source-find-count"),
            )
            // A clicked button takes the keyboard focus with it: the find field gets it back.
            SquareIconButton("↑", fontSize = 12.sp, onClick = { onStep(false); runCatching { findRequester.requestFocus() } }, enabled = total > 0, size = 20.dp,
                modifier = Modifier.testTag("seq3-source-find-prev"), tooltip = "Previous match (Shift+Enter)")
            SquareIconButton("↓", fontSize = 12.sp, onClick = { onStep(true); runCatching { findRequester.requestFocus() } }, enabled = total > 0, size = 20.dp,
                modifier = Modifier.testTag("seq3-source-find-next"), tooltip = "Next match (Enter)")
            SquareIconButton("×", fontSize = 14.sp, onClick = onClose, size = 20.dp,
                modifier = Modifier.testTag("seq3-source-find-close"), tooltip = "Close (Esc)")
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            InlineField(
                value = replaceValue,
                onValue = onReplaceValue,
                placeholder = "Replace with",
                fontSize = 11.sp,
                modifier = Modifier.weight(1f).widthIn(min = 60.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                            onReplace()
                            true
                        } else false
                    }
                    .testTag("seq3-source-replace-field"),
                onCancel = onClose,
            )
            // The replace buttons hand the keyboard to the editor, where one undo brings the text back.
            AppButton("Replace", onClick = { onReplace(); onReclaimEditor() }, variant = ButtonVariant.Secondary, enabled = total > 0,
                horizontalPadding = 7.dp, modifier = Modifier.testTag("seq3-source-replace-one"))
            AppButton("Replace all", onClick = { onReplaceAll(); onReclaimEditor() }, variant = ButtonVariant.Secondary, enabled = total > 0,
                horizontalPadding = 7.dp, modifier = Modifier.testTag("seq3-source-replace-all"))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
    }
}

/** Paints, behind the editor text, the tinted line of the linked message ([linkedLine] is its row in
 *  the visible text) and the find [matches] (raw offsets, [hidden] runs taken out) in the visible
 *  part of [layout], which is [scrollY] scrolled. */
@Suppress("LongParameterList")
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeq3SourceHighlights(
    layout: TextLayoutResult,
    scrollY: Float,
    linkedLine: Int?,
    linkedColor: Color,
    matches: List<Seq3SourceRange>,
    current: Int,
    hidden: List<Seq3SourceRange>,
    matchColor: Color,
    currentColor: Color,
) {
    val lineCount = layout.lineCount
    if (linkedLine != null && linkedLine in 0 until lineCount) {
        val top = layout.getLineTop(linkedLine) - scrollY
        val bottom = layout.getLineBottom(linkedLine) - scrollY
        if (bottom > 0f && top < size.height) {
            drawRect(linkedColor, androidx.compose.ui.geometry.Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, bottom - top))
        }
    }
    if (matches.isEmpty() || lineCount == 0) return
    val textLength = layout.layoutInput.text.length
    matches.forEachIndexed { index, match ->
        val start = seq3SourceVisibleOffset(match.start, hidden).coerceIn(0, textLength)
        val end = seq3SourceVisibleOffset(match.endExclusive, hidden).coerceIn(start, textLength)
        val line = layout.getLineForOffset(start)
        val top = layout.getLineTop(line) - scrollY
        if (top > size.height || layout.getLineBottom(line) - scrollY < 0f) return@forEachIndexed
        translate(top = -scrollY) {
            drawPath(layout.getPathForRange(start, end), if (index == current) currentColor else matchColor)
        }
    }
}

@Composable
private fun Seq3SourceStatusStrip(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    suggestionsVisible: Boolean,
    caretDiagnostic: Seq3SourceDiagnostic?,
    caretDiagnosticLine: Int?,
    onReviewEvidence: () -> Unit,
    onToggleIssues: () -> Unit,
) {
    val warning = view.sourceDraftStale || view.sourceDiagnostics.isNotEmpty() ||
        (view.sourceEvidenceLossPending && view.sourceLiveEnabled)
    Row(
        Modifier.fillMaxWidth().height(34.dp).background(if (warning) tc().warnBg else tc().p2)
            .testTag("seq3-source-status-strip").padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            view.sourceDraftStale -> {
                AppText(
                    "Diagram changed. Reload source to continue.",
                    color = tc().warn,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f).testTag("seq3-source-stale"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AppButton(
                    "Reload",
                    onClick = { reloadSeq3SourceDraft(state, session, view) },
                    variant = ButtonVariant.Secondary,
                    horizontalPadding = 6.dp,
                    modifier = Modifier.testTag("seq3-source-reload"),
                )
            }
            view.sourceEvidenceLossPending && view.sourceLiveEnabled -> {
                AppText(
                    "This edit removes linked log evidence.",
                    color = tc().warn,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f).testTag("seq3-source-evidence-review-status"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AppButton(
                    "Review removal",
                    onClick = onReviewEvidence,
                    variant = ButtonVariant.Secondary,
                    horizontalPadding = 7.dp,
                    modifier = Modifier.testTag("seq3-source-review-evidence"),
                )
            }
            view.sourceDiagnostics.isNotEmpty() -> {
                AppText(
                    "Invalid · preview unchanged (${view.sourceDiagnostics.size})",
                    color = tc().warn,
                    fontSize = 11.sp,
                    modifier = (if (caretDiagnostic == null) Modifier.weight(1f) else Modifier.widthIn(max = 210.dp))
                        .testTag("seq3-source-draft-status"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The message for the line the caret is on, so a typo is explained where it is made.
                if (caretDiagnostic != null) {
                    AppText(
                        "Line ${caretDiagnosticLine ?: caretDiagnostic.line}: ${caretDiagnostic.message}",
                        color = tc().tx,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f).testTag("seq3-source-caret-message"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AppButton(
                    "Details",
                    onClick = onToggleIssues,
                    variant = ButtonVariant.Secondary,
                    horizontalPadding = 6.dp,
                    modifier = Modifier.testTag("seq3-source-diagnostics-details"),
                )
                AppButton(
                    "Discard",
                    onClick = { reloadSeq3SourceDraft(state, session, view) },
                    variant = ButtonVariant.Secondary,
                    horizontalPadding = 6.dp,
                    modifier = Modifier.testTag("seq3-source-reload"),
                )
            }
            view.sourceHasUnappliedDraft() -> {
                AppText(
                    "Draft",
                    color = tc().ac,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f).testTag("seq3-source-draft-status"),
                )
                if (!view.sourceLiveEnabled) {
                    AppButton(
                        "Apply",
                        onClick = { applySeq3SourceDraft(state, session, view) },
                        variant = ButtonVariant.Primary,
                        enabled = !view.sourceDraftStale && !suggestionsVisible,
                        horizontalPadding = 6.dp,
                        modifier = Modifier.testTag("seq3-source-apply"),
                    )
                }
                AppButton(
                    "Discard",
                    onClick = { reloadSeq3SourceDraft(state, session, view) },
                    variant = ButtonVariant.Secondary,
                    horizontalPadding = 6.dp,
                    modifier = Modifier.testTag("seq3-source-reload"),
                )
            }
            else -> Box(Modifier.weight(1f))
        }
    }
}

/** The in-tile issues list under the editor: one row per diagnostic with its line and message.
 *  A row moves the caret to that line; a diagnostic with a quick fix offers it as a button. */
@Composable
private fun Seq3SourceIssuesList(
    diagnostics: List<Seq3SourceDiagnostic>,
    lineNumbers: Map<Seq3SourceDiagnostic, Int>,
    caretDiagnostic: Seq3SourceDiagnostic?,
    onJump: (Int) -> Unit,
    onFix: (Seq3SourceFix) -> Unit,
    onClose: () -> Unit,
) {
    val tc = tc()
    Column(Modifier.fillMaxWidth().background(tc.p).testTag("seq3-source-issues")) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(
                if (diagnostics.size == 1) "1 issue" else "${diagnostics.size} issues",
                color = tc.tx, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            AppButton(
                "Close", onClick = onClose, variant = ButtonVariant.Secondary, horizontalPadding = 6.dp,
                modifier = Modifier.testTag("seq3-source-issues-close"),
            )
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 132.dp).verticalScroll(rememberScrollState()).padding(bottom = 4.dp)) {
            diagnostics.forEachIndexed { index, diagnostic ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (diagnostic == caretDiagnostic) tc.warnBg else Color.Transparent)
                        .clickable { onJump(diagnostic.line) }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .testTag("seq3-source-issue-$index"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText("Line ${lineNumbers[diagnostic] ?: diagnostic.line}", color = tc.warn, fontSize = 11.sp, fontFamily = MONO)
                    AppText(diagnostic.message, color = tc.tx, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    diagnostic.fix?.let { fix ->
                        AppButton(
                            fix.label, onClick = { onFix(fix) }, variant = ButtonVariant.Secondary, horizontalPadding = 6.dp,
                            modifier = Modifier.testTag("seq3-source-issue-fix-$index"),
                        )
                    }
                }
            }
        }
    }
}

internal data class Seq3SourceSuggestion(
    val keyword: String,
    val syntax: String,
    val insertion: String,
    val cursorOffset: Int = insertion.length,
    /** Tab-able fields inside [insertion] (offsets in the raw insertion); the first is selected. */
    val fields: List<SourceSnippetField> = emptyList(),
    /** The picture in front of the row. */
    val glyph: Seq3SourceGlyph,
    /** A declared participant or actor name: [keyword] is its alias (what is inserted), [syntax] its
     *  display name when that differs. */
    val name: Boolean = false,
) {
    /** True when accepting this entry would only add blanks to the word [query] that was typed in full. */
    fun isTypedInFull(query: String): Boolean =
        !name && keyword.equals(query, ignoreCase = true) && insertion.trim().equals(keyword, ignoreCase = true)
}

internal data class Seq3SourceSuggestionTrigger(
    val start: Int,
    val end: Int,
    val query: String,
    val prefix: String = "/",
    /** Declared participant and actor names belong at this position. */
    val names: Boolean = false,
    /** Keywords (`loop`, `note`, ...) belong at this position; false where only a name can follow
     *  (after an arrow, `note over`, `activate`, ...). */
    val keywords: Boolean = true,
)

/**
 * A suggestion list closed for one word: where the word starts and what it held when it was closed.
 * It lasts while the word is unchanged or only shortened (Backspace), and ends with the first
 * character typed into it, or when the caret leaves the word.
 */
internal data class Seq3SourceSuggestionDismissal(val start: Int, val query: String)

internal fun seq3SourceDismissalOf(trigger: Seq3SourceSuggestionTrigger): Seq3SourceSuggestionDismissal =
    Seq3SourceSuggestionDismissal(trigger.start, trigger.query)

/** The dismissal after accepting a suggestion: the word now at the caret, which stays closed. */
internal fun seq3SourceDismissalAfterAccept(value: TextFieldValue, dialect: Seq3Dialect): Seq3SourceSuggestionDismissal? =
    seq3SourceSuggestionTrigger(value.text, value.selection, dialect)?.let(::seq3SourceDismissalOf)

/**
 * [dismissal] after the editor moved on to [trigger] (null: no word at the caret). A longer word, a
 * changed one or another start ends it, so typing into a word opens its list again, also after
 * characters were deleted from it; a shorter word keeps it, remembered at the new length.
 */
internal fun seq3SourceDismissalAfterEdit(
    dismissal: Seq3SourceSuggestionDismissal?,
    trigger: Seq3SourceSuggestionTrigger?,
): Seq3SourceSuggestionDismissal? {
    if (dismissal == null || trigger == null || trigger.start != dismissal.start) return null
    return when {
        trigger.query == dismissal.query -> dismissal
        trigger.query.length < dismissal.query.length -> seq3SourceDismissalOf(trigger)
        else -> null
    }
}

private const val SEQ3_SOURCE_BARE_TRIGGER_MIN_LENGTH = 2

/**
 * What the word at the caret can complete to. A slash word is always a keyword. A bare word at the
 * start of a line is a keyword or a declared name (an arrow's source, or `Al` for `Alice`) and
 * needs two letters. A bare word where only a name can stand (after an arrow, `note over` / `left
 * of` / `right of` and after a comma in the list, `activate`, `deactivate`, `destroy` and, in
 * PlantUML, `create`) is a name and needs one letter. Names are the letters, digits and underscores
 * the word scan takes, so an alias with other characters is completed up to its first such character.
 */
internal fun seq3SourceSuggestionTrigger(
    source: String,
    selection: TextRange,
    dialect: Seq3Dialect = Seq3Dialect.PLANTUML,
): Seq3SourceSuggestionTrigger? {
    if (selection.start != selection.end) return null
    val caret = selection.start.coerceIn(0, source.length)
    var queryStart = caret
    while (queryStart > 0 && source[queryStart - 1].isLetterOrDigitOrUnderscore()) queryStart--
    val slash = queryStart - 1
    var end = caret
    while (end < source.length && source[end].isLetterOrDigitOrUnderscore()) end++
    val query = source.substring(queryStart, caret) + source.substring(caret, end)
    if (slash >= 0 && source[slash] == '/') {
        if (slash > 0 && source[slash - 1].isLetterOrDigitOrUnderscore()) return null
        return Seq3SourceSuggestionTrigger(slash, end, query, "/")
    }
    val lineStart = source.lastIndexOf('\n', (queryStart - 1).coerceAtLeast(0)).let { it + 1 }
    val beforeToken = source.substring(lineStart, queryStart)
    if (beforeToken.all { it.isWhitespace() }) {
        // A bare word needs two letters: a single `a` is far more likely the start of a name or a message.
        if (query.length < SEQ3_SOURCE_BARE_TRIGGER_MIN_LENGTH) return null
        return Seq3SourceSuggestionTrigger(queryStart, end, query, "", names = true)
    }
    if (query.isEmpty() || !seq3SourceNameFollows(beforeToken.trimStart(), dialect)) return null
    return Seq3SourceSuggestionTrigger(queryStart, end, query, "", names = true, keywords = false)
}

private val SEQ3_SOURCE_PLANTUML_ARROW_BEFORE = Regex("^\\S+?\\s*(?:-->|->>|->)\\s*$")
private val SEQ3_SOURCE_MERMAID_ARROW_BEFORE = Regex("^\\S+?\\s*(?:-->>|->>|-\\))\\s*[+-]?$")
private val SEQ3_SOURCE_NOTE_TARGET_BEFORE = Regex("^(?i:note)\\s+(?:over|(?:left|right)\\s+of)\\s+(?:[^:,]+,\\s*)*$")
private val SEQ3_SOURCE_PLANTUML_NAME_KEYWORD_BEFORE = Regex("^(?:activate|deactivate|destroy|create)\\s+$")

// Mermaid writes `create participant X as Y`: what follows `create` is a keyword and a new name.
private val SEQ3_SOURCE_MERMAID_NAME_KEYWORD_BEFORE = Regex("^(?:activate|deactivate|destroy)\\s+$")

/** Whether a declared name can stand right after [before], the trimmed text of the line before the word. */
private fun seq3SourceNameFollows(before: String, dialect: Seq3Dialect): Boolean {
    val comment = if (dialect == Seq3Dialect.MERMAID) "%%" else "'"
    if (before.startsWith(comment)) return false
    return if (dialect == Seq3Dialect.MERMAID) {
        SEQ3_SOURCE_MERMAID_ARROW_BEFORE.matches(before) || SEQ3_SOURCE_NOTE_TARGET_BEFORE.matches(before) ||
            SEQ3_SOURCE_MERMAID_NAME_KEYWORD_BEFORE.matches(before)
    } else {
        SEQ3_SOURCE_PLANTUML_ARROW_BEFORE.matches(before) || SEQ3_SOURCE_NOTE_TARGET_BEFORE.matches(before) ||
            SEQ3_SOURCE_PLANTUML_NAME_KEYWORD_BEFORE.matches(before)
    }
}

/**
 * The declared participants and actors that [query] starts (their alias first, then those whose
 * display name starts with it), one entry each: the alias is what is inserted, the display name is
 * shown beside it when it differs. A name typed in full as the alias is left out, so a list with
 * nothing more to complete is empty.
 */
internal fun seq3SourceNameSuggestions(query: String, participants: List<Seq3SourceParticipant>): List<Seq3SourceSuggestion> {
    if (query.isEmpty()) return emptyList()
    val byAlias = participants.filter { it.alias.startsWith(query, ignoreCase = true) }
    val byName = participants.filter { !it.alias.startsWith(query, ignoreCase = true) && it.name.startsWith(query, ignoreCase = true) }
    return (byAlias + byName).filterNot { it.alias == query }.map {
        Seq3SourceSuggestion(
            keyword = it.alias,
            syntax = if (it.name != it.alias) it.name else "",
            insertion = it.alias,
            glyph = if (it.actor) Seq3SourceGlyph.ACTOR else Seq3SourceGlyph.PARTICIPANT,
            name = true,
        )
    }
}

/** [enclosingBlock] is the innermost open block (`alt`, `par`, ...) at the typing position: the
 *  divider that block owns is offered only inside it. */
internal fun seq3SourceSuggestions(
    dialect: Seq3Dialect,
    query: String,
    participantAliases: List<String> = emptyList(),
    enclosingBlock: String? = null,
): List<Seq3SourceSuggestion> =
    sourceSuggestionEntries(dialect, participantAliases, enclosingBlock)
        .filter { it.keyword.startsWith(query, ignoreCase = true) }

/**
 * The suggestions to show for [trigger] in [source]. A word typed in full that has nothing to expand
 * (`end`, `else`) shows no list, so Enter after it is an ordinary newline. The blocks come from the
 * lines before the trigger, with the rules the editor's indentation and the import both use. Where
 * the trigger takes names, the declared [participants] that match come before the keywords.
 */
internal fun seq3SourceActiveSuggestions(
    source: String,
    trigger: Seq3SourceSuggestionTrigger,
    dialect: Seq3Dialect,
    participantAliases: List<String>,
    participants: List<Seq3SourceParticipant> = emptyList(),
): List<Seq3SourceSuggestion> {
    val block = seq3SourceIndentStateAfter(source.substring(0, trigger.start.coerceIn(0, source.length)), dialect).innermostBlock
    // Names first: at a line start a name that matches is likelier than the keyword that also does.
    val names = if (trigger.names) seq3SourceNameSuggestions(trigger.query, participants) else emptyList()
    val keywords = if (trigger.keywords) seq3SourceSuggestions(dialect, trigger.query, participantAliases, block) else emptyList()
    val matches = names + keywords
    if (trigger.prefix.isEmpty() && matches.all { it.isTypedInFull(trigger.query) }) return emptyList()
    return matches
}

/** The word that divides a [block] into operands in [dialect], or null when that block has none. As
 *  in the import, only `alt`, `par` and `critical` blocks can be divided; PlantUML writes every
 *  divider `else`, Mermaid has `else`, `and` and `option`. */
internal fun seq3SourceDividerWord(block: String?, dialect: Seq3Dialect): String? = when (block) {
    "alt" -> "else"
    "par" -> if (dialect == Seq3Dialect.PLANTUML) "else" else "and"
    "critical" -> if (dialect == Seq3Dialect.PLANTUML) "else" else "option"
    else -> null
}

/** [raw] (an offset into the source) as an offset into the text with the [hidden] ranges taken out. */
internal fun seq3SourceVisibleOffset(raw: Int, hidden: List<Seq3SourceRange>): Int {
    var removed = 0
    for (range in hidden) {
        if (range.start >= raw) break
        removed += minOf(range.endExclusive, raw) - range.start
    }
    return raw - removed
}

/** The right-hand padding of a suggestion list: the lane of its thin scrollbar and a margin on each side of it. */
private val SEQ3_SOURCE_LIST_SCROLLBAR_LANE = (THIN_SCROLLBAR_DP + 3f + 3f).dp

/** Which side of the caret line a suggestion list takes, and how tall it may be there. */
internal data class Seq3SourceListSlot(val below: Boolean, val room: Float)

/**
 * The side for a list [listHeight] tall next to a caret line spanning [caretTop]..[caretBottom] in
 * an area [areaHeight] tall: below the line, above it when the list does not fit below, and the
 * roomier side (the list is then cut to its [Seq3SourceListSlot.room]) when it fits on neither.
 */
internal fun seq3SourceSuggestionSlot(
    caretTop: Float,
    caretBottom: Float,
    listHeight: Float,
    areaHeight: Float,
    gap: Float,
): Seq3SourceListSlot {
    val roomBelow = (areaHeight - caretBottom - gap).coerceAtLeast(0f)
    val roomAbove = (caretTop - gap).coerceAtLeast(0f)
    return when {
        listHeight <= roomBelow -> Seq3SourceListSlot(true, roomBelow)
        listHeight <= roomAbove -> Seq3SourceListSlot(false, roomAbove)
        roomBelow >= roomAbove -> Seq3SourceListSlot(true, roomBelow)
        else -> Seq3SourceListSlot(false, roomAbove)
    }
}

/** The top of the list in [slot]: right under the caret line, or ending right above it. */
internal fun seq3SourceSuggestionTop(slot: Seq3SourceListSlot, caretTop: Float, caretBottom: Float, listHeight: Float, gap: Float): Float =
    if (slot.below) caretBottom + gap else (caretTop - gap - listHeight).coerceAtLeast(0f)

/** Places its child at [left] (kept inside the parent) and under the caret line, or above it.
 *  The child is measured once, at the room of the roomier side: a list that fits on neither side is
 *  cut to that room (and scrolls), and the side is then picked as [seq3SourceSuggestionSlot] does.
 *  Measuring it twice (natural height, then cut) makes a scrolling child write its scroll state
 *  twice per pass, which a scrollbar reading that state turns into a layout that never settles. */
private fun Modifier.anchoredAtCaret(left: Float, top: Float, bottom: Float, gap: Float): Modifier =
    layout { measurable, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val roomBelow = (constraints.maxHeight - bottom - gap).coerceAtLeast(0f)
        val roomAbove = (top - gap).coerceAtLeast(0f)
        val placeable = measurable.measure(loose.copy(maxHeight = minOf(maxOf(roomBelow, roomAbove).toInt(), constraints.maxHeight)))
        val slot = seq3SourceSuggestionSlot(top, bottom, placeable.height.toFloat(), constraints.maxHeight.toFloat(), gap)
        val x = left.coerceIn(0f, (constraints.maxWidth - placeable.width).coerceAtLeast(0).toFloat())
        val y = seq3SourceSuggestionTop(slot, top, bottom, placeable.height.toFloat(), gap)
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x.roundToInt(), y.roundToInt()) }
    }

/** A guided block: its opener, one starter message so the block is valid at once, and `end`.  The
 *  starter message uses the first two declared participants when they exist. */
internal fun seq3SourceBlockSnippet(
    keyword: String,
    dialect: Seq3Dialect,
    participantAliases: List<String>,
): Pair<String, List<SourceSnippetField>> {
    val from = participantAliases.getOrNull(0) ?: "From"
    val to = participantAliases.getOrNull(1) ?: "To"
    val label = "message label"
    val snippet = "$keyword condition\n    ${sourceMessageLine(from, to, "Call", dialect, label)}\nend"
    return snippet to sourceSnippetFields(snippet, "condition" to false, from to true, to to true, label to false)
}

/** The pictogram of a keyword's suggestion row, so the toolbar buttons and menu items show the same one. */
internal fun seq3SourceKeywordGlyph(keyword: String): Seq3SourceGlyph =
    sourceSuggestionEntries(Seq3Dialect.PLANTUML, emptyList(), "alt").first { it.keyword == keyword }.glyph

private fun sourceSuggestionEntries(
    dialect: Seq3Dialect,
    participantAliases: List<String>,
    enclosingBlock: String?,
): List<Seq3SourceSuggestion> {
    val mermaid = dialect == Seq3Dialect.MERMAID
    val note = if (mermaid) "Note over A: note text" else "note over A: note text"
    val participant = if (mermaid) "participant Alias as Name" else "participant \"Name\" as Alias"
    val actor = if (mermaid) "actor Alias as Name" else "actor \"Name\" as Alias"
    val entries = mutableListOf(
        Seq3SourceSuggestion(
            "participant", participant,
            if (mermaid) "participant  as " else "participant \"\" as ",
            cursorOffset = if (mermaid) "participant ".length else "participant \"".length,
            glyph = Seq3SourceGlyph.PARTICIPANT,
        ),
        Seq3SourceSuggestion(
            "actor", actor,
            if (mermaid) "actor  as " else "actor \"\" as ",
            cursorOffset = if (mermaid) "actor ".length else "actor \"".length,
            glyph = Seq3SourceGlyph.ACTOR,
        ),
        Seq3SourceSuggestion("title", "title Diagram title", "title ", glyph = Seq3SourceGlyph.TITLE),
        Seq3SourceSuggestion("note", note, if (mermaid) "Note over A: " else "note over A: ", glyph = Seq3SourceGlyph.NOTE),
        Seq3SourceSuggestion("activate", "activate Alias", "activate ", glyph = Seq3SourceGlyph.ACTIVATION),
        Seq3SourceSuggestion("deactivate", "deactivate Alias", "deactivate ", glyph = Seq3SourceGlyph.ACTIVATION),
        // The import reads `create` as the creating statement of the next message; Mermaid declares
        // the new lifeline with it, PlantUML names one that is already declared.
        if (mermaid) Seq3SourceSuggestion(
            "create", "create participant Alias as Name (before its message)", "create participant ", glyph = Seq3SourceGlyph.CREATE,
        ) else Seq3SourceSuggestion(
            "create", "create Alias (before its message)", "create ", glyph = Seq3SourceGlyph.CREATE,
        ),
        Seq3SourceSuggestion("destroy", "destroy Alias (after its message)", "destroy ", glyph = Seq3SourceGlyph.DESTROY),
    )
    // The three arrow styles of the dialect, ready to fill in: the first two declared participants
    // are the sender and receiver when they exist.
    listOf(
        Triple("call", "Call", Seq3SourceGlyph.ARROW_SOLID),
        Triple("return", "Return", Seq3SourceGlyph.ARROW_DASHED),
        Triple("async", "Async", Seq3SourceGlyph.ARROW_ASYNC),
    ).forEach { (keyword, style, glyph) ->
        val from = participantAliases.getOrNull(0) ?: "From"
        val to = participantAliases.getOrNull(1) ?: "To"
        val label = "message label"
        val line = sourceMessageLine(from, to, style, dialect, label)
        entries += Seq3SourceSuggestion(
            keyword, sourceMessageLine("A", "B", style, dialect, "label"), line,
            fields = sourceSnippetFields(line, from to true, to to true, label to false),
            glyph = glyph,
        )
    }
    listOf("loop", "alt", "opt", "par", "critical", "break", "group").forEach { keyword ->
        val (insertion, fields) = seq3SourceBlockSnippet(keyword, dialect, participantAliases)
        entries += Seq3SourceSuggestion(
            keyword, "$keyword condition … end", insertion, "$keyword condition\n    ".length, fields, Seq3SourceGlyph.BLOCK,
        )
    }
    seq3SourceDividerWord(enclosingBlock, dialect)?.let { word ->
        entries += Seq3SourceSuggestion(word, "$word condition", "$word ", glyph = Seq3SourceGlyph.DIVIDER)
    }
    entries += Seq3SourceSuggestion("end", "end current fragment", "end", glyph = Seq3SourceGlyph.END)
    return entries
}

internal fun insertSeq3SourceSuggestion(
    source: String,
    trigger: Seq3SourceSuggestionTrigger,
    suggestion: Seq3SourceSuggestion,
    dialect: Seq3Dialect = Seq3Dialect.PLANTUML,
    autoIndent: Boolean = true,
): TextFieldValue = insertSeq3SourceSuggestionWithFields(source, trigger, suggestion, dialect, autoIndent).first

/** The inserted value (first field selected) and the suggestion's fields mapped into the new text. */
internal fun insertSeq3SourceSuggestionWithFields(
    source: String,
    trigger: Seq3SourceSuggestionTrigger,
    suggestion: Seq3SourceSuggestion,
    dialect: Seq3Dialect = Seq3Dialect.PLANTUML,
    autoIndent: Boolean = true,
): Pair<TextFieldValue, List<SourceSnippetField>> {
    val start = trigger.start.coerceIn(0, source.length)
    val end = trigger.end.coerceIn(start, source.length)
    if (suggestion.name) {
        // A name replaces the word typed for it where it stands: no new line, no re-indenting.
        val alias = suggestion.insertion
        return TextFieldValue(source.replaceRange(start, end, alias), TextRange(start + alias.length)) to emptyList()
    }
    val isTitle = suggestion.keyword.equals("title", ignoreCase = true)
    val isParticipant = suggestion.keyword.equals("participant", ignoreCase = true) ||
        suggestion.keyword.equals("actor", ignoreCase = true)
    return if (isTitle || isParticipant) {
        // Indentation typed before the trigger goes with it, leaving the same empty line as at column 0.
        val lineStart = source.lastIndexOf('\n', start - 1) + 1
        val removalStart = if (source.substring(lineStart, start).isBlank()) lineStart else start
        val withoutTrigger = source.removeRange(removalStart, end)
        val insertionAt = seq3SourceHeaderInsertionOffset(withoutTrigger, dialect, titleOnly = isTitle)
        insertSeq3SourceAtMapped(withoutTrigger, insertionAt, insertionAt, suggestion.insertion, dialect, suggestion.cursorOffset, autoIndent)
            .value to emptyList()
    } else {
        val inserted = insertSeq3SourceAtMapped(source, start, end, suggestion.insertion, dialect, suggestion.cursorOffset, autoIndent)
        val fields = suggestion.fields.map {
            it.copy(start = inserted.mapSnippetOffset(it.start), end = inserted.mapSnippetOffset(it.end))
        }
        val first = fields.firstOrNull()
        (if (first == null) inserted.value else inserted.value.copy(selection = TextRange(first.start, first.end))) to fields
    }
}

private fun Char.isLetterOrDigitOrUnderscore(): Boolean = isLetterOrDigit() || this == '_'

@Composable
private fun Seq3SourceSuggestionRow(
    suggestion: Seq3SourceSuggestion,
    prefix: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val tc = tc()
    // Arrow keys move the choice through a list longer than its window: keep it in view.
    val inView = remember { BringIntoViewRequester() }
    LaunchedEffect(active) { if (active) runCatching { inView.bringIntoView() } }
    Row(
        Modifier.fillMaxWidth().bringIntoViewRequester(inView).testTag("seq3-source-suggestion-row-${if (suggestion.name) "name-" else ""}${suggestion.keyword}")
            .background(if (active) tc.abg else Color.Transparent, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A name is told from a keyword of the same spelling by its tag; it has no slash.
        val tagId = if (suggestion.name) "name-${suggestion.keyword}" else suggestion.keyword
        Seq3SourceGlyphIcon(suggestion.glyph, if (active) tc.ac else tc.ts, Modifier.testTag("seq3-source-suggestion-glyph-$tagId"))
        AppText(
            if (suggestion.name) suggestion.keyword else "$prefix${suggestion.keyword}",
            color = if (active) tc.ac else tc.tx, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        )
        AppText(suggestion.syntax, color = tc.ts, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal data class Seq3SourceParticipant(val alias: String, val name: String, val actor: Boolean)

private fun sourceDialectName(dialect: Seq3Dialect): String = when (dialect) {
    Seq3Dialect.PLANTUML -> "PlantUML"
    Seq3Dialect.MERMAID -> "Mermaid"
}

private fun sourceStartHint(dialect: Seq3Dialect): String = when (dialect) {
    Seq3Dialect.PLANTUML -> "@startuml"
    Seq3Dialect.MERMAID -> "sequenceDiagram"
}

@Composable
private fun Seq3SourceDialectSelector(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState) {
    SourceCompactMenu(
        "${sourceDialectName(view.sourceEditorDialect)} ▾", "seq3-source-dialect",
        listOf("PlantUML", "Mermaid"), onSelect = { index ->
            val dialect = if (index == 0) Seq3Dialect.PLANTUML else Seq3Dialect.MERMAID
            changeSeq3Dialect(state, session, view, dialect, focusEditor = false)
            view.sourceFocusRequestGeneration++
        }, onClose = { view.sourceFocusRequestGeneration++ },
    )
}

internal data class SourceSnippetField(val start: Int, val end: Int, val endpoint: Boolean = false)

internal fun sourceEndpointAt(source: String, selection: TextRange, dialect: Seq3Dialect): SourceSnippetField? {
    val caret = selection.start.coerceIn(0, source.length)
    val lineStart = source.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)).let { it + 1 }
    val lineEnd = source.indexOf('\n', caret).let { if (it < 0) source.length else it }
    val line = source.substring(lineStart, lineEnd)
    val pattern = if (dialect == Seq3Dialect.MERMAID)
        Regex("^\\s*(\\S+?)(?:-->>|->>|-\\))(\\S+)\\s*:.*$")
    else Regex("^\\s*(\\S+)\\s+(?:-->|->>|->)\\s+(\\S+)\\s*:.*$")
    val match = pattern.matchEntire(line) ?: return null
    return (1..2).mapNotNull { match.groups[it] }.map { SourceSnippetField(lineStart + it.range.first, lineStart + it.range.last + 1, true) }
        .firstOrNull { selection.start >= it.start && selection.end <= it.end }
}

internal fun rebaseSourceSnippetFields(fields: List<SourceSnippetField>, old: String, next: String): List<SourceSnippetField> {
    if (old == next) return fields
    var start = 0
    while (start < old.length && start < next.length && old[start] == next[start]) start++
    var oldEnd = old.length
    var nextEnd = next.length
    while (oldEnd > start && nextEnd > start && old[oldEnd - 1] == next[nextEnd - 1]) { oldEnd--; nextEnd-- }
    val delta = nextEnd - oldEnd
    return fields.mapNotNull { field ->
        when {
            oldEnd <= field.start && start < field.start -> field.copy(start = field.start + delta, end = field.end + delta)
            start >= field.start && oldEnd <= field.end -> field.copy(end = field.end + delta)
            start >= field.end -> field
            else -> null
        }
    }
}

private fun sourceSnippetFields(snippet: String, vararg labels: Pair<String, Boolean>): List<SourceSnippetField> {
    var offset = 0
    return labels.map { (label, endpoint) ->
        val start = snippet.indexOf(label, offset).also { require(it >= 0) }
        offset = start + label.length
        SourceSnippetField(start, offset, endpoint)
    }
}

@Composable
private fun SourceModeToggle(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    AppButton("$label: ${if (enabled) "On" else "Off"}", onClick = onClick, variant = ButtonVariant.Ghost,
        textColor = if (enabled) tc().ac else tc().ts, horizontalPadding = 5.dp,
        modifier = Modifier.testTag(tag).semantics { stateDescription = if (enabled) "On" else "Off" })
}

@Composable
private fun Seq3SourceSettingsMenu(state: AppState, view: Seq3ViewState) {
    SourceCompactMenu("Settings ▾", "seq3-source-settings", listOf(
        "Live preview: ${if (view.sourceLiveEnabled) "On" else "Off"}",
        "Autocomplete: ${if (state.settings.diagramSourceAutocomplete) "On" else "Off"}",
        "Auto-indent: ${if (state.settings.diagramSourceAutoIndent) "On" else "Off"}",
    ), onSelect = { index ->
        when (index) {
            0 -> view.sourceLiveEnabled = !view.sourceLiveEnabled
            1 -> toggleSeq3SourceAutocomplete(state)
            else -> toggleSeq3SourceAutoIndent(state)
        }
        view.sourceFocusRequestGeneration++
    }, tags = listOf("seq3-source-live-toggle", "seq3-source-autocomplete-toggle", "seq3-source-auto-indent-toggle"))
}

/** Focusable popups consume the outside press that dismisses them, including an opener press. */
@Composable
private fun SourceCompactMenu(
    label: String,
    tag: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    tags: List<String> = emptyList(),
    onClose: () -> Unit = {},
    variant: ButtonVariant = ButtonVariant.Secondary,
    horizontalPadding: androidx.compose.ui.unit.Dp = 7.dp,
    /** The picture in front of each option, by index (tag `<tag>-glyph-<index>`); null for none. */
    optionGlyphs: List<Seq3SourceGlyph?> = emptyList(),
) {
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) wasOpen = true
        else if (wasOpen) {
            // A focusable Popup restores its previous focus while it is disposed. Wait until
            // that restoration finishes before returning focus to the source editor.
            withFrameNanos { }
            onClose()
            wasOpen = false
        }
    }
    Box {
        AppButton(label, onClick = {
            open = !open
        }, variant = variant, textColor = if (variant == ButtonVariant.Ghost) tc().tx else null,
            horizontalPadding = horizontalPadding, modifier = Modifier.testTag(tag))
        if (open) Popup(
            alignment = Alignment.TopStart,
            offset = with(density) { IntOffset(0, 32.dp.roundToPx()) },
            onDismissRequest = { open = false },
            properties = PopupProperties(focusable = true),
        ) {
            Column(
                Modifier.widthIn(min = 145.dp, max = 250.dp).width(IntrinsicSize.Max).heightIn(max = 220.dp)
                    .background(tc().p, RoundedCornerShape(6.dp))
                    .border(1.dp, tc().br, RoundedCornerShape(6.dp))
                    .verticalScroll(rememberScrollState()).padding(4.dp).testTag("$tag-options"),
            ) {
                options.forEachIndexed { index, option ->
                    val glyph = optionGlyphs.getOrNull(index)
                    Box(if (index < tags.size) Modifier.testTag(tags[index]) else Modifier) {
                        Seq3DropdownMenuItem(
                            option, active = false,
                            leading = glyph?.let { { color -> Seq3SourceGlyphIcon(glyph, color, Modifier.testTag("$tag-glyph-$index")) } },
                        ) { open = false; onSelect(index) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Seq3SourceActionForms(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    editorState: TextFieldState,
    insertSnippet: (String, List<SourceSnippetField>, Boolean) -> Unit,
    reclaimFocus: () -> Unit,
    showSourceMarkers: Boolean,
    onToggleSourceMarkers: () -> Unit,
    onNewDiagramEvidenceLoss: (String, Seq3Dialect) -> Unit,
) {
    val dialect = view.sourceEditorDialect
    var confirmNewDiagram by remember(session.id, view.sourceEditorGeneration) { mutableStateOf(false) }
    fun addParticipant(actor: Boolean) {
        val (snippet, fields) = seq3SourceParticipantSnippet(editorState.text.toString(), dialect, actor)
        insertSnippet(snippet, fields, true)
    }
    fun addMessage(style: String) {
        val snippet = sourceMessageLine("From", "To", style, dialect, "message label")
        insertSnippet(snippet, sourceSnippetFields(snippet, "From" to true, "To" to true, "message label" to false), false)
    }
    fun applyNewDiagram() {
        val starter = seq3SourceStarter(dialect, state.seq3Sessions.newSourceDiagramTitle(session.id))
        confirmNewDiagram = false
        when (val result = state.seq3Sessions.startNewSourceDiagram(session.id, starter, dialect)) {
            is Seq3SourceImportResult.Failure -> view.sourceDiagnostics = result.diagnostics
            is Seq3SourceImportResult.Success -> if (result.evidenceLoss) onNewDiagramEvidenceLoss(starter, dialect) else {
                view.sourceDiagnostics = emptyList(); view.sourceEvidenceLossPending = false
            }
        }
        reclaimFocus()
    }
    val hasMarkers = seq3SourceHasMarkers(editorState.text.toString(), dialect)
    val tcNow = tc()
    // The insert buttons are one group; New diagram and Show markers sit apart in the "⋯" menu, which
    // lists Show markers only while the source has markers to show.
    Row(Modifier.fillMaxWidth().background(tcNow.p).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        FlowRow(Modifier.weight(1f, fill = false).border(0.5.dp, tcNow.br, CORNER_MD).background(tcNow.p2, CORNER_MD).padding(2.dp)
            .testTag("seq3-source-insert-group"),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            // The same pictograms as the suggestion rows of these keywords; the other buttons carry them in their menus.
            AppButton("+ Participant", onClick = { addParticipant(false) }, variant = ButtonVariant.Ghost, textColor = tcNow.tx,
                horizontalPadding = 7.dp, modifier = Modifier.testTag("seq3-source-insert-participant"),
                leadingContent = { color -> Seq3SourceGlyphIcon(seq3SourceKeywordGlyph("participant"), color, Modifier.testTag("seq3-source-insert-participant-glyph")) })
            AppButton("+ Actor", onClick = { addParticipant(true) }, variant = ButtonVariant.Ghost, textColor = tcNow.tx,
                horizontalPadding = 7.dp, modifier = Modifier.testTag("seq3-source-insert-actor"),
                leadingContent = { color -> Seq3SourceGlyphIcon(seq3SourceKeywordGlyph("actor"), color, Modifier.testTag("seq3-source-insert-actor-glyph")) })
            SourceCompactMenu("+ Message ▾", "seq3-source-message-menu", listOf("Call", "Return", "Async"),
                onSelect = { addMessage(listOf("Call", "Return", "Async")[it]) }, onClose = reclaimFocus, variant = ButtonVariant.Ghost,
                optionGlyphs = listOf("call", "return", "async").map(::seq3SourceKeywordGlyph))
            AppButton("+ Note", onClick = {
                val scope = seq3SourceNoteScope(editorState.text.toString(), editorState.selection.start, dialect,
                    sourceParticipants(editorState.text.toString(), dialect).map { it.alias }) ?: "Participant"
                val snippet = "${if (dialect == Seq3Dialect.MERMAID) "Note" else "note"} over $scope: note text"
                insertSnippet(snippet, sourceSnippetFields(snippet, "note text" to false), false)
            }, variant = ButtonVariant.Ghost, textColor = tcNow.tx, horizontalPadding = 7.dp, modifier = Modifier.testTag("seq3-source-insert-note"))
            val blocks = listOf("loop", "alt", "opt", "par", "critical", "break", "group")
            SourceCompactMenu("+ Block ▾", "seq3-source-fragment-menu", blocks, onSelect = {
                val aliases = sourceParticipants(editorState.text.toString(), dialect).map { participant -> participant.alias }
                val (snippet, fields) = seq3SourceBlockSnippet(blocks[it], dialect, aliases)
                insertSnippet(snippet, fields, false)
            }, onClose = reclaimFocus, variant = ButtonVariant.Ghost,
                optionGlyphs = blocks.map(::seq3SourceKeywordGlyph))
            SourceCompactMenu("+ Activation ▾", "seq3-source-activation-menu", listOf("Activate", "Deactivate"), onSelect = {
                val snippet = "${if (it == 0) "activate" else "deactivate"} Participant"
                insertSnippet(snippet, sourceSnippetFields(snippet, "Participant" to true), false)
            }, onClose = reclaimFocus, variant = ButtonVariant.Ghost,
                optionGlyphs = listOf("activate", "deactivate").map(::seq3SourceKeywordGlyph))
        }
        val moreItems = buildList {
            add("New diagram")
            if (hasMarkers) add(if (showSourceMarkers) "Hide markers" else "Show markers")
        }
        SourceCompactMenu("⋯", "seq3-source-more", moreItems, onSelect = { index ->
            if (index == 0) {
                val latest = state.seq3Sessions.sessions.firstOrNull { it.id == session.id } ?: session
                if (seq3SourceHasUserContent(view.sourceDraft, dialect, latest.document.title) || seq3DocumentHasUserContent(latest.document))
                    confirmNewDiagram = true else applyNewDiagram()
            } else {
                onToggleSourceMarkers()
                reclaimFocus()
            }
        }, tags = listOf("seq3-source-new-diagram", "seq3-source-toggle-markers"), onClose = reclaimFocus, horizontalPadding = 9.dp)
    }
    if (confirmNewDiagram) Dialog(onDismissRequest = { confirmNewDiagram = false; reclaimFocus() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(420.dp).background(tc().p, RoundedCornerShape(8.dp)).border(1.dp, tc().br, RoundedCornerShape(8.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppText("Replace current diagram?", color = tc().tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            AppText("Starting a new diagram replaces the current source and diagram structure. Linked log evidence will be reviewed separately if it is affected.", color = tc().ts, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                AppButton("Keep editing", onClick = { confirmNewDiagram = false; reclaimFocus() }, variant = ButtonVariant.Ghost)
                AppButton("Replace diagram", onClick = { applyNewDiagram() }, variant = ButtonVariant.Primary,
                    modifier = Modifier.testTag("seq3-source-confirm-new-diagram"))
            }
        }
    }
}

/** The declaration and tab fields of a new participant (or actor) with an unused alias, as the
 *  "+ Participant" and "+ Actor" buttons insert it. */
internal fun seq3SourceParticipantSnippet(source: String, dialect: Seq3Dialect, actor: Boolean): Pair<String, List<SourceSnippetField>> {
    val alias = sourceUniqueAlias("Alias", sourceParticipants(source, dialect))
    val snippet = sourceParticipantDeclaration(alias, "Name", actor, dialect)
    val fields = if (dialect == Seq3Dialect.MERMAID) sourceSnippetFields(snippet, alias to false, "Name" to false).reversed()
        else sourceSnippetFields(snippet, "Name" to false, alias to false)
    return snippet to fields
}

private fun sourceUniqueAlias(base: String, participants: List<Seq3SourceParticipant>): String {
    val sanitized = base.replace(Regex("[^A-Za-z0-9_]"), "_").let { if (it.firstOrNull()?.isDigit() == true) "p$it" else it }
        .ifBlank { "Participant" }
    val used = participants.map { it.alias }.toSet()
    if (sanitized !in used) return sanitized
    var suffix = 2
    while ("${sanitized}_$suffix" in used) suffix++
    return "${sanitized}_$suffix"
}

private fun sourceContainsMessage(source: String, dialect: Seq3Dialect): Boolean = source.lineSequence().any { line ->
    parseSeq3SourceArrowEndpoints(line, dialect)?.second != null
}

private fun sourceEscape(value: String, dialect: Seq3Dialect): String {
    val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
    if (dialect == Seq3Dialect.MERMAID) return buildString(normalized.length) {
        normalized.forEach { char ->
            append(when (char) {
                '\n' -> "<br/>"
                ';' -> "#59;"
                '#' -> "#35;"
                ':' -> "#58;"
                '<' -> "#60;"
                '>' -> "#62;"
                '"' -> "#34;"
                '`' -> "#96;"
                else -> char.toString()
            })
        }
    }
    return buildString(normalized.length) {
        normalized.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                else -> append(char)
            }
        }
    }
}

/** Whether [source] holds identity marker lines, which the editor hides until asked to show them. */
internal fun seq3SourceHasMarkers(source: String, dialect: Seq3Dialect): Boolean =
    source.contains("indagium-seq3:v1") && source.lineSequence().any { isSeq3SourceMarkerLine(it, dialect) }

internal fun seq3SourceStarter(dialect: Seq3Dialect, title: String = ""): String = buildString {
    append(sourceStartHint(dialect))
    title.trim().takeIf(String::isNotEmpty)?.let { append("\ntitle ").append(sourceEscape(it, dialect)) }
    if (dialect == Seq3Dialect.PLANTUML) append("\n@enduml")
}

internal fun seq3SourceHasUserContent(source: String, dialect: Seq3Dialect, defaultTitle: String? = null): Boolean = source.lineSequence().any { raw ->
    val line = raw.trim()
    line.isNotEmpty() && line != sourceStartHint(dialect) && !(dialect == Seq3Dialect.PLANTUML && line == "@enduml") &&
        (!isSourceCommentLine(line, dialect) || !isSeq3SourceMarkerLine(line, dialect)) &&
        !(defaultTitle != null && isDefaultSeq3Title(defaultTitle) && line == "title $defaultTitle")
}

private fun seq3DocumentHasUserContent(document: com.indagium.diagram3.Seq3Document): Boolean =
    (document.title.isNotBlank() && !isDefaultSeq3Title(document.title)) || document.lifelines.isNotEmpty() || document.messages.isNotEmpty() ||
        document.fragments.isNotEmpty() || document.notes.isNotEmpty() || document.delays.isNotEmpty() ||
        document.stateInvariants.isNotEmpty()

private fun isDefaultSeq3Title(title: String): Boolean =
    title == "Untitled diagram" || title.matches(Regex("Untitled diagram \\d+"))

internal fun sourceParticipants(source: String, dialect: Seq3Dialect): List<Seq3SourceParticipant> {
    val result = linkedMapOf<String, Seq3SourceParticipant>()
    source.lineSequence().forEach { raw ->
        parseSeq3SourceParticipant(raw, dialect)?.let { (alias, name, kind) ->
            result[alias] = Seq3SourceParticipant(alias, name, kind == Seq3LifelineKind.ACTOR)
        }
    }
    return result.values.toList()
}

private fun sourceParticipantDisplayName(alias: String, participants: List<Seq3SourceParticipant>): String =
    participants.firstOrNull { it.alias == alias }?.name ?: alias

private fun sourceParticipantChoiceLabel(participant: Seq3SourceParticipant, participants: List<Seq3SourceParticipant>): String {
    if (participant.alias == participant.name) return "${participant.name} (${participant.alias})"
    val sameName = participants.count { it.name == participant.name } > 1
    if (!sameName) return participant.name
    val shortAlias = participant.alias.take(12).let { if (participant.alias.length > 12) "$it…" else it }
    return "${participant.name} ($shortAlias)"
}

internal fun seq3SourceNoteScope(
    source: String,
    cursor: Int,
    dialect: Seq3Dialect,
    participantAliases: List<String>,
): String? {
    var offset = 0
    var endpoints: Pair<String, String?>? = null
    source.lineSequence().forEach { line ->
        if (offset >= cursor) return@forEach
        val trimmed = line.trim()
        val arrow = parseSeq3SourceArrowEndpoints(trimmed, dialect)?.let { (from, to) -> to?.let { from to it } }
        if (arrow != null) endpoints = arrow
        offset += line.length + 1
    }
    val (from, to) = endpoints ?: return null
    val ordered = listOfNotNull(from, to).distinct().sortedBy { alias -> participantAliases.indexOf(alias).let { if (it < 0) Int.MAX_VALUE else it } }
    return ordered.takeIf { it.isNotEmpty() }?.joinToString(",")
}


private fun sourceParticipantDeclaration(alias: String, name: String, actor: Boolean, dialect: Seq3Dialect): String = when (dialect) {
    Seq3Dialect.MERMAID -> "${if (actor) "actor" else "participant"} $alias as ${sourceEscape(name, dialect)}"
    Seq3Dialect.PLANTUML -> "${if (actor) "actor" else "participant"} \"${sourceEscape(name, dialect)}\" as $alias"
}

private fun sourceMessageLine(from: String, to: String, style: String, dialect: Seq3Dialect, label: String = "message label"): String {
    val arrow = when (dialect to style) {
        Seq3Dialect.MERMAID to "Call" -> "->>"
        Seq3Dialect.MERMAID to "Return" -> "-->>"
        Seq3Dialect.MERMAID to "Async" -> "-)"
        Seq3Dialect.PLANTUML to "Call" -> "->"
        Seq3Dialect.PLANTUML to "Return" -> "-->"
        else -> "->>"
    }
    val separator = if (dialect == Seq3Dialect.MERMAID) "" else " "
    return "$from$separator$arrow${if (dialect == Seq3Dialect.MERMAID) "" else " "}$to: ${sourceEscape(label, dialect)}"
}

/** A snippet inserted into a source: the new text and caret, and a map from offsets in the raw
 *  snippet to offsets in the new text (indentation moves them). */
internal class Seq3SourceInsertion(
    val value: TextFieldValue,
    private val contentStart: Int,
    private val leadingTrim: Int,
    private val reindented: Seq3SourceReindented,
) {
    fun mapSnippetOffset(offset: Int): Int = contentStart + reindented.map(offset - leadingTrim)
}

private fun insertSeq3SourceSnippet(
    source: String,
    selection: TextRange,
    snippet: String,
    dialect: Seq3Dialect,
    header: Boolean = false,
    titleHeader: Boolean = false,
    autoIndent: Boolean = true,
): Seq3SourceInsertion {
    val start = if (header) {
        seq3SourceHeaderInsertionOffset(source, dialect, titleOnly = titleHeader)
    } else {
        val caret = selection.start.coerceIn(0, source.length)
        source.indexOf('\n', caret).let { if (it < 0) source.length else it }
    }
    val end = start
    return insertSeq3SourceAtMapped(source, start, end, snippet, dialect, autoIndent = autoIndent)
}

private fun insertSeq3SourceAt(
    source: String,
    start: Int,
    end: Int,
    snippet: String,
    dialect: Seq3Dialect,
    cursorOffset: Int = snippet.length,
): TextFieldValue = insertSeq3SourceAtMapped(source, start, end, snippet, dialect, cursorOffset).value

/** Inserts [snippet] over [start, end) with every line indented to the level of the insertion
 *  point (with [autoIndent] off, to the blanks of the line it follows). A whitespace-only stretch
 *  before the insertion on its line is replaced, not kept. */
private fun insertSeq3SourceAtMapped(
    source: String,
    start: Int,
    end: Int,
    snippet: String,
    dialect: Seq3Dialect,
    cursorOffset: Int = snippet.length,
    autoIndent: Boolean = true,
): Seq3SourceInsertion {
    val clampedStart = start.coerceIn(0, source.length)
    val clampedEnd = end.coerceIn(clampedStart, source.length)
    val terminator = if (dialect == Seq3Dialect.PLANTUML) "@enduml" else null
    val terminatorAt = terminator?.let { source.indexOf(it).takeIf { index -> index >= 0 } } ?: source.length
    val insertionAt = minOf(clampedStart, terminatorAt)
    val replacedEnd = minOf(clampedEnd, terminatorAt).coerceAtLeast(insertionAt)
    var before = source.substring(0, insertionAt)
    val lastLineStart = before.lastIndexOf('\n') + 1
    // Auto-indent off: the snippet sits at the blanks the line it follows (or replaces) starts with.
    val lineIndent = before.substring(lastLineStart).let { it.substring(0, it.length - it.trimStart(' ', '\t').length) }
    if (lastLineStart < before.length && before.substring(lastLineStart).isBlank()) before = before.substring(0, lastLineStart)
    val after = source.substring(replacedEnd)
    val content = snippet.trim('\n', '\r')
    val leadingTrim = snippet.length - snippet.trimStart('\n', '\r').length
    val reindented = if (autoIndent) seq3SourceReindentSnippet(content, seq3SourceIndentStateAfter(before, dialect))
        else seq3SourceFlatSnippet(content, lineIndent)
    val prefix = if (before.isNotEmpty() && !before.endsWith('\n')) "\n" else ""
    val suffix = if (after.isNotEmpty() && !after.startsWith('\n')) "\n" else ""
    val next = before + prefix + reindented.text + suffix + after
    val contentStart = before.length + prefix.length
    val caret = (contentStart + reindented.map(cursorOffset.coerceIn(0, content.length))).coerceIn(0, next.length)
    return Seq3SourceInsertion(TextFieldValue(next, TextRange(caret)), contentStart, leadingTrim, reindented)
}

internal data class Seq3SourceLine(
    val number: Int,
    val text: String,
    val start: Int,
    val end: Int,
    val nextStart: Int,
)

internal data class Seq3SourceRange(val start: Int, val endExclusive: Int)

internal data class Seq3SourceLineNumberMap(
    val lineCount: Int,
    private val visibleLineNumbers: List<Int>? = null,
) {
    fun lineNumberAt(index: Int): Int = visibleLineNumbers?.getOrElse(index) { index + 1 } ?: index + 1

    /** The row index showing raw line [line]; the nearest following row when that line is hidden. */
    fun indexOfLine(line: Int): Int {
        val numbers = visibleLineNumbers ?: return (line - 1).coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        val found = numbers.binarySearch(line)
        return (if (found >= 0) found else -found - 1).coerceIn(0, numbers.lastIndex)
    }

    fun asList(): List<Int> = visibleLineNumbers ?: (1..lineCount).toList()
}

internal fun seq3SourceLines(source: String): List<Seq3SourceLine> {
    val lines = mutableListOf<Seq3SourceLine>()
    var start = 0
    source.forEachIndexed { index, char ->
        if (char == '\n') {
            lines += Seq3SourceLine(lines.size + 1, source.substring(start, index), start, index, index + 1)
            start = index + 1
        }
    }
    lines += Seq3SourceLine(lines.size + 1, source.substring(start), start, source.length, source.length)
    return lines
}

internal fun isSeq3SourceMarkerLine(line: String, dialect: Seq3Dialect): Boolean {
    val text = line.trim()
    val prefix = if (dialect == Seq3Dialect.MERMAID) "%% indagium-seq3:v1 " else "' indagium-seq3:v1 "
    if (!text.startsWith(prefix)) return false
    val payload = text.removePrefix(prefix)
    return payload.isNotEmpty() && payload.all { it.isLetterOrDigit() || it == '_' || it == '-' }
}

private fun isSourceCommentLine(line: String, dialect: Seq3Dialect): Boolean =
    if (dialect == Seq3Dialect.MERMAID) line.startsWith("%%") else line.startsWith("'")

internal fun seq3SourceMarkerDeletionRanges(source: String, dialect: Seq3Dialect): List<Seq3SourceRange> {
    if (!source.contains("indagium-seq3:v1")) return emptyList()
    val lines = seq3SourceLines(source)
    val lastContentLine = if (source.endsWith('\n')) lines.lastIndex - 1 else lines.lastIndex
    val markerIndices = (0..lastContentLine).filter { isSeq3SourceMarkerLine(lines[it].text, dialect) }
    if (markerIndices.isEmpty()) return emptyList()

    val ranges = mutableListOf<Seq3SourceRange>()
    var runStart = markerIndices.first()
    var runEnd = runStart
    fun appendRun(first: Int, last: Int) {
        var previousVisible = first - 1
        while (previousVisible >= 0 && isSeq3SourceMarkerLine(lines[previousVisible].text, dialect)) previousVisible--
        var nextVisible = last + 1
        while (nextVisible <= lastContentLine && isSeq3SourceMarkerLine(lines[nextVisible].text, dialect)) nextVisible++
        val hasPreviousVisible = previousVisible >= 0
        val hasNextVisible = nextVisible <= lastContentLine
        val start = when {
            hasPreviousVisible && hasNextVisible -> lines[first].start
            hasPreviousVisible -> lines[previousVisible].end
            else -> 0
        }
        val end = if (hasNextVisible) lines[last].nextStart else source.length
        if (end > start) ranges += Seq3SourceRange(start, end)
    }
    markerIndices.drop(1).forEach { index ->
        if (index == runEnd + 1) {
            runEnd = index
        } else {
            appendRun(runStart, runEnd)
            runStart = index
            runEnd = index
        }
    }
    appendRun(runStart, runEnd)
    return ranges
}

internal fun seq3SourceVisibleLineNumbers(source: String, dialect: Seq3Dialect, showMarkers: Boolean): List<Int> {
    return seq3SourceLineNumberMap(source, dialect, showMarkers).asList()
}

internal fun seq3SourceLineNumberMap(source: String, dialect: Seq3Dialect, showMarkers: Boolean): Seq3SourceLineNumberMap {
    val rawLineCount = source.count { it == '\n' } + 1
    if (showMarkers || !source.contains("indagium-seq3:v1")) return Seq3SourceLineNumberMap(rawLineCount)
    val lines = seq3SourceLines(source)
    val hidden = lines.filter { isSeq3SourceMarkerLine(it.text, dialect) }.mapTo(HashSet()) { it.number }
    if (hidden.isEmpty()) return Seq3SourceLineNumberMap(lines.size)
    val hideTrailingEmpty = source.endsWith('\n') && lines.size > 1 &&
        isSeq3SourceMarkerLine(lines[lines.lastIndex - 1].text, dialect)
    val visible = lines.asSequence()
        .filterNot { it.number in hidden }
        .filterNot { hideTrailingEmpty && it.number == lines.last().number }
        .map { it.number }
        .toList()
        .ifEmpty { listOf(1) }
    return Seq3SourceLineNumberMap(visible.size, visible)
}

private fun transformSeq3SourceOutput(
    buffer: TextFieldBuffer,
    dialect: Seq3Dialect,
    palette: Seq3SourcePalette,
    showMarkers: Boolean,
) {
    val rawText = buffer.asCharSequence().toString()
    if (!showMarkers) {
        seq3SourceMarkerDeletionRanges(rawText, dialect).asReversed().forEach { range ->
            buffer.replace(range.start, range.endExclusive, "")
        }
    }
    val visible = buffer.asCharSequence().toString()
    highlightSeq3Source(visible, dialect, palette).spanStyles.forEach { range ->
        if (range.start < range.end) buffer.addStyle(range.item, range.start, range.end)
    }
}

internal fun seq3SourceHeaderInsertionOffset(source: String, dialect: Seq3Dialect, titleOnly: Boolean = false): Int {
    val lines = seq3SourceLines(source)
    val startLineIndex = lines.indexOfFirst { it.text.trim() == sourceStartHint(dialect) }
    var insertionOffset = if (startLineIndex >= 0) lines[startLineIndex].nextStart else 0
    var index = if (startLineIndex >= 0) startLineIndex + 1 else 0
    while (index < lines.size) {
        val line = lines[index]
        val text = line.text.trim()
        when {
            text.isEmpty() || isSeq3SourceMarkerLine(text, dialect) || isSourceCommentLine(text, dialect) -> Unit
            SOURCE_TITLE_LINE.matches(text) -> insertionOffset = line.nextStart
            !titleOnly && SOURCE_PARTICIPANT_LINE.matches(text) -> insertionOffset = line.nextStart
            else -> break
        }
        index++
    }
    return insertionOffset.coerceIn(0, source.length)
}

private val SOURCE_TITLE_LINE = Regex("^title\\b.*", RegexOption.IGNORE_CASE)
private val SOURCE_PARTICIPANT_LINE = Regex("^(?:participant|actor)\\b.*", RegexOption.IGNORE_CASE)
