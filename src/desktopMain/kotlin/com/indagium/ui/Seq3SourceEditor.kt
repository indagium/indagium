@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3SourceImportResult
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import kotlin.math.roundToInt

internal fun openSeq3SourceEditor(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState) {
    if (!state.seq3Sessions.prepareSourceDraft(session.id)) return
    setDiagramWorkspaceTileVisible(view, DiagramWorkspaceSectionId.SOURCE, true)
    view.sourceFocusRequestGeneration++
    state.autosaveInBackground()
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

private fun Seq3ViewState.sourceHasUnappliedDraft(): Boolean =
    sourceDraftStale || (sourceDraftDirty && sourceDraft != sourceLiveLastAppliedText)

@Composable
internal fun Seq3SourceHeaderActions(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState) {
    Seq3SourceDialectSelector(state, session, view)
    if (LocalTileHeaderWidthDp.current >= 700f) {
        SourceModeToggle("Live preview", view.sourceLiveEnabled, "seq3-source-live-toggle") {
            view.sourceLiveEnabled = !view.sourceLiveEnabled
            view.sourceFocusRequestGeneration++
        }
        SourceModeToggle("Autocomplete", view.sourceAutocompleteEnabled, "seq3-source-autocomplete-toggle") {
            view.sourceAutocompleteEnabled = !view.sourceAutocompleteEnabled
            view.sourceFocusRequestGeneration++
        }
    } else Seq3SourceSettingsMenu(view)
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
    val scroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val gutterListState = rememberLazyListState()
    var showSourceMarkers by remember(session.id, view.sourceEditorGeneration) { mutableStateOf(false) }
    var dismissedSuggestionStart by remember(session.id, view.sourceEditorGeneration) { mutableStateOf<Int?>(null) }
    var selectedSuggestionIndex by remember(session.id, view.sourceEditorGeneration) { mutableStateOf(0) }
    var snippetFields by remember(session.id, view.sourceEditorGeneration) { mutableStateOf<List<SourceSnippetField>>(emptyList()) }
    var snippetText by remember(session.id, view.sourceEditorGeneration) { mutableStateOf(view.sourceDraft) }
    var endpointMenu by remember(session.id, view.sourceEditorGeneration) { mutableStateOf<Int?>(null) }
    var ignoreEndpointRelease by remember(session.id, view.sourceEditorGeneration) { mutableStateOf(false) }
    var showEvidenceLossDialog by remember(session.id) { mutableStateOf(false) }
    var showSourceDiagnosticsDialog by remember(session.id) { mutableStateOf(false) }
    var pendingNewDiagramEvidenceRemoval by remember(session.id) { mutableStateOf<Pair<String, Seq3Dialect>?>(null) }
    LaunchedEffect(session.id) {
        if (!view.sourceDraftInitialized) state.seq3Sessions.prepareSourceDraft(session.id)
    }
    LaunchedEffect(session.id, view.sourceFocusRequestGeneration) {
        runCatching { requester.requestFocus() }
    }
    LaunchedEffect(session.id, view.sourceEditorGeneration, editorState) {
        snapshotFlow { TextFieldValue(editorState.text.toString(), editorState.selection) }.collect { next ->
            if (next.text != lastValue.text) {
                if (snippetText != next.text) {
                    snippetFields = rebaseSourceSnippetFields(snippetFields, snippetText, next.text)
                    snippetText = next.text
                }
                if (skipHistoryText != next.text) {
                    undo += lastValue
                    if (undo.size > 500) undo.removeAt(0)
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
    val lineHeightDp = with(LocalDensity.current) { 18.sp.toDp() }
    val lineHeightPx = with(LocalDensity.current) { 18.sp.toPx() }
    LaunchedEffect(scroll.value, lineCount, lineHeightPx) {
        val firstLine = (scroll.value / lineHeightPx).toInt().coerceIn(0, lineCount - 1)
        val offset = (scroll.value - firstLine * lineHeightPx).roundToInt()
        gutterListState.scrollToItem(firstLine, offset)
    }

    fun currentEditorValue(): TextFieldValue = TextFieldValue(editorState.text.toString(), editorState.selection)

    fun setEditorValue(next: TextFieldValue, record: Boolean = true) {
        val current = currentEditorValue()
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
        val next = insertSeq3SourceSnippet(editorState.text.toString(), editorState.selection, snippet, view.sourceEditorDialect, header)
        val start = next.selection.start - snippet.trim('\n', '\r').length
        snippetFields = fields.map { it.copy(start = it.start + start, end = it.end + start) }
        val first = snippetFields.firstOrNull()
        endpointMenu = null
        setEditorValue(if (first == null) next else next.copy(selection = TextRange(first.start, first.end)), true)
        runCatching { requester.requestFocus() }
    }

    val selection = editorState.selection
    val endpointIndex = snippetFields.indexOfFirst { it.endpoint && selection.start >= it.start && selection.end <= it.end }
    val endpointField = snippetFields.getOrNull(endpointIndex)
        ?: sourceEndpointAt(sourceText, selection, view.sourceEditorDialect)
    val endpointQuery = endpointField?.let { sourceText.substring(it.start.coerceAtMost(sourceText.length), it.end.coerceAtMost(sourceText.length)) }.orEmpty()
    val participantChoices = sourceParticipants(sourceText, view.sourceEditorDialect)
    val identifierChoices = if (view.sourceAutocompleteEnabled && endpointField != null && selection.collapsed && endpointQuery.isNotBlank()) {
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

    val suggestionTrigger = if (view.sourceAutocompleteEnabled && endpointField == null) {
        seq3SourceSuggestionTrigger(sourceText, editorState.selection)
    } else null
    if (suggestionTrigger == null || suggestionTrigger.start != dismissedSuggestionStart) {
        dismissedSuggestionStart = null
    }
    val sourceSuggestions = suggestionTrigger?.let {
        seq3SourceSuggestions(view.sourceEditorDialect, it.query)
    }.orEmpty()
    LaunchedEffect(suggestionTrigger?.query, view.sourceEditorDialect) {
        selectedSuggestionIndex = 0
    }
    val suggestionsVisible = suggestionTrigger != null && sourceSuggestions.isNotEmpty() &&
        suggestionTrigger.start != dismissedSuggestionStart
    if (selectedSuggestionIndex !in sourceSuggestions.indices) selectedSuggestionIndex = 0

    LaunchedEffect(
        session.id,
        sourceText,
        view.sourceEditorDialect,
        view.sourceLiveEnabled,
        view.sourceDraftInitialized,
        suggestionsVisible,
    ) {
        if (!view.sourceLiveEnabled || !view.sourceDraftInitialized || suggestionsVisible) return@LaunchedEffect
        delay(400)
        // sourceDraft moves on first when New diagram or a reload replaces the source, before this
        // effect is cancelled; applying sourceText then would bring the replaced text back.
        if (editorState.text.toString() == sourceText && view.sourceDraft == sourceText &&
            view.sourceLiveEnabled && !suggestionsVisible
        ) {
            state.seq3Sessions.applyLiveSourceDraft(session.id, sourceText, view.sourceEditorDialect)
        }
    }

    fun insertSuggestion(index: Int) {
        val trigger = suggestionTrigger ?: return
        val suggestion = sourceSuggestions.getOrNull(index) ?: return
        setEditorValue(insertSeq3SourceSuggestion(sourceText, trigger, suggestion, view.sourceEditorDialect), true)
        dismissedSuggestionStart = null
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
        Row(Modifier.weight(1f).fillMaxWidth().background(tc.p2)) {
            LazyColumn(
                Modifier.width(42.dp).fillMaxHeight().padding(top = 8.dp, bottom = 8.dp),
                state = gutterListState,
                userScrollEnabled = false,
            ) {
                items(lineCount) { index ->
                    val line = visibleLines.lineNumberAt(index)
                    val hasDiagnostic = view.sourceDiagnostics.any { it.line == line }
                    Box(
                        Modifier.fillMaxWidth().height(lineHeightDp).padding(end = 8.dp)
                            .testTag("seq3-source-gutter-line-$line"),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        AppText(
                            line.toString(),
                            color = if (hasDiagnostic) tc.warn else tc.td,
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
                outputTransformation = remember(view.sourceEditorDialect, showSourceMarkers, tc.tx, tc.ac, tc.ts, tc.td) {
                    OutputTransformation {
                        transformSeq3SourceOutput(
                            this,
                            view.sourceEditorDialect,
                            tc.tx,
                            tc.ac,
                            tc.ts,
                            tc.td,
                            showSourceMarkers,
                        )
                    }
                },
                scrollState = scroll,
                modifier = Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp, bottom = 8.dp)
                    .testTag("seq3-source-editor")
                    .focusRequester(requester)
                    .onFocusChanged { view.textFieldFocused = it.hasFocus }
                    .horizontalScroll(horizontalScroll)
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
                                    dismissedSuggestionStart = suggestionTrigger.start
                                    runCatching { requester.requestFocus() }
                                    view.textFieldFocused = true
                                    return@onPreviewKeyEvent true
                                }
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
                            else -> false
                        }
                    },
                decorator = TextFieldDecorator { inner ->
                    Box {
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
                            Column(
                                Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 6.dp)
                                    .widthIn(min = 210.dp, max = 320.dp).width(IntrinsicSize.Max).heightIn(max = 208.dp)
                                    .shadow(8.dp, RoundedCornerShape(8.dp))
                                    .background(tc.p, RoundedCornerShape(8.dp))
                                    .border(1.dp, tc.br, RoundedCornerShape(8.dp)).verticalScroll(rememberScrollState()).padding(4.dp)
                                    .testTag("seq3-source-suggestions"),
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
                        }
                    }
                },
            )
        }
        Seq3SourceStatusStrip(
            state = state,
            session = session,
            view = view,
            suggestionsVisible = suggestionsVisible,
            onReviewEvidence = { showEvidenceLossDialog = true },
            onShowDiagnostics = { showSourceDiagnosticsDialog = true },
        )
    }

    if (showSourceDiagnosticsDialog && view.sourceDiagnostics.isNotEmpty()) {
        Dialog(
            onDismissRequest = { showSourceDiagnosticsDialog = false; runCatching { requester.requestFocus() } },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                Modifier.width(460.dp).heightIn(max = 420.dp).background(tc.p, RoundedCornerShape(12.dp))
                    .border(1.dp, tc.br, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppText("Source diagnostics", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                view.sourceDiagnostics.forEach { diagnostic ->
                    AppText("Line ${diagnostic.line}: ${diagnostic.message}", color = tc.warn, fontSize = 11.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    AppButton("Close", onClick = { showSourceDiagnosticsDialog = false; runCatching { requester.requestFocus() } },
                        variant = ButtonVariant.Ghost)
                }
            }
        }
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
                Modifier.width(420.dp).background(tc.p, RoundedCornerShape(12.dp))
                    .border(1.dp, tc.br, RoundedCornerShape(12.dp)).padding(18.dp),
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
                Modifier.width(390.dp).background(tc.p, RoundedCornerShape(12.dp))
                    .border(1.dp, tc.br, RoundedCornerShape(12.dp)).padding(18.dp),
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

@Composable
private fun Seq3SourceStatusStrip(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    suggestionsVisible: Boolean,
    onReviewEvidence: () -> Unit,
    onShowDiagnostics: () -> Unit,
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
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f).testTag("seq3-source-stale"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AppButton(
                    "Reload",
                    onClick = { reloadSeq3SourceDraft(state, session, view) },
                    variant = ButtonVariant.Ghost,
                    horizontalPadding = 6.dp,
                    modifier = Modifier.testTag("seq3-source-reload"),
                )
                if (!view.sourceLiveEnabled) {
                    AppButton(
                        "Apply",
                        onClick = { applySeq3SourceDraft(state, session, view) },
                        variant = ButtonVariant.Primary,
                        enabled = false,
                        horizontalPadding = 6.dp,
                        modifier = Modifier.testTag("seq3-source-apply"),
                    )
                }
            }
            view.sourceEvidenceLossPending && view.sourceLiveEnabled -> {
                AppText(
                    "This edit removes linked log evidence.",
                    color = tc().warn,
                    fontSize = 10.sp,
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
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f).testTag("seq3-source-draft-status"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AppButton(
                    "Details",
                    onClick = onShowDiagnostics,
                    variant = ButtonVariant.Ghost,
                    horizontalPadding = 6.dp,
                    modifier = Modifier.testTag("seq3-source-diagnostics-details"),
                )
                AppButton(
                    "Discard",
                    onClick = { reloadSeq3SourceDraft(state, session, view) },
                    variant = ButtonVariant.Ghost,
                    horizontalPadding = 6.dp,
                    modifier = Modifier.testTag("seq3-source-reload"),
                )
            }
            view.sourceHasUnappliedDraft() -> {
                AppText(
                    "Draft",
                    color = tc().ac,
                    fontSize = 10.sp,
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
                    variant = ButtonVariant.Ghost,
                    horizontalPadding = 6.dp,
                    modifier = Modifier.testTag("seq3-source-reload"),
                )
            }
            else -> Box(Modifier.weight(1f))
        }
    }
}

internal data class Seq3SourceSuggestion(
    val keyword: String,
    val syntax: String,
    val insertion: String,
    val cursorOffset: Int = insertion.length,
)

internal data class Seq3SourceSuggestionTrigger(
    val start: Int,
    val end: Int,
    val query: String,
    val prefix: String = "/",
)

internal fun seq3SourceSuggestionTrigger(source: String, selection: TextRange): Seq3SourceSuggestionTrigger? {
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
    if (query.isEmpty()) return null
    val lineStart = source.lastIndexOf('\n', (queryStart - 1).coerceAtLeast(0)).let { it + 1 }
    val beforeToken = source.substring(lineStart, queryStart)
    if (beforeToken.any { !it.isWhitespace() }) return null
    return Seq3SourceSuggestionTrigger(queryStart, end, query, "")
}

internal fun seq3SourceSuggestions(dialect: Seq3Dialect, query: String): List<Seq3SourceSuggestion> =
    sourceSuggestionEntries(dialect)
        .filter { it.keyword.startsWith(query, ignoreCase = true) }

private fun sourceSuggestionEntries(dialect: Seq3Dialect): List<Seq3SourceSuggestion> {
    val note = if (dialect == Seq3Dialect.MERMAID) "Note over A: note text" else "note over A: note text"
    val participant = if (dialect == Seq3Dialect.MERMAID) "participant Alias as Name" else "participant \"Name\" as Alias"
    val actor = if (dialect == Seq3Dialect.MERMAID) "actor Alias as Name" else "actor \"Name\" as Alias"
    val entries = mutableListOf(
        Seq3SourceSuggestion(
            "participant", participant,
            if (dialect == Seq3Dialect.MERMAID) "participant  as " else "participant \"\" as ",
            cursorOffset = if (dialect == Seq3Dialect.MERMAID) "participant ".length else "participant \"".length,
        ),
        Seq3SourceSuggestion(
            "actor", actor,
            if (dialect == Seq3Dialect.MERMAID) "actor  as " else "actor \"\" as ",
            cursorOffset = if (dialect == Seq3Dialect.MERMAID) "actor ".length else "actor \"".length,
        ),
        Seq3SourceSuggestion("title", "title Diagram title", "title "),
        Seq3SourceSuggestion("note", note, if (dialect == Seq3Dialect.MERMAID) "Note over A: " else "note over A: "),
        Seq3SourceSuggestion("activate", "activate Alias", "activate "),
        Seq3SourceSuggestion("deactivate", "deactivate Alias", "deactivate "),
        Seq3SourceSuggestion("create", "create Alias (before its message)", "create "),
        Seq3SourceSuggestion("destroy", "destroy Alias (after its message)", "destroy "),
    )
    listOf("loop", "alt", "opt", "par", "critical", "break", "group").forEach { keyword ->
        val insertion = "$keyword condition\n    \nend"
        entries += Seq3SourceSuggestion(keyword, "$keyword condition … end", insertion, "$keyword condition\n    ".length)
    }
    entries += Seq3SourceSuggestion("end", "end current fragment", "end")
    return entries
}

internal fun insertSeq3SourceSuggestion(
    source: String,
    trigger: Seq3SourceSuggestionTrigger,
    suggestion: Seq3SourceSuggestion,
    dialect: Seq3Dialect = Seq3Dialect.PLANTUML,
): TextFieldValue {
    val start = trigger.start.coerceIn(0, source.length)
    val end = trigger.end.coerceIn(start, source.length)
    val isTitle = suggestion.keyword.equals("title", ignoreCase = true)
    val isParticipant = suggestion.keyword.equals("participant", ignoreCase = true) ||
        suggestion.keyword.equals("actor", ignoreCase = true)
    return if (isTitle || isParticipant) {
        val withoutTrigger = source.removeRange(start, end)
        val insertionAt = seq3SourceHeaderInsertionOffset(withoutTrigger, dialect, titleOnly = isTitle)
        insertSeq3SourceAt(withoutTrigger, insertionAt, insertionAt, suggestion.insertion, dialect, suggestion.cursorOffset)
    } else {
        insertSeq3SourceAt(source, start, end, suggestion.insertion, dialect, suggestion.cursorOffset)
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
    Row(
        Modifier.fillMaxWidth().background(if (active) tc.abg else Color.Transparent, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("$prefix${suggestion.keyword}", color = if (active) tc.ac else tc.tx, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        AppText(suggestion.syntax, color = tc.ts, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private data class Seq3SourceParticipant(val alias: String, val name: String, val actor: Boolean)

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
            if (dialect != view.sourceEditorDialect) {
                if (view.sourceHasUnappliedDraft()) view.sourceDialectChangePending = dialect
                else state.seq3Sessions.switchSourceDraftDialect(session.id, dialect)
            }
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
private fun Seq3SourceSettingsMenu(view: Seq3ViewState) {
    SourceCompactMenu("Settings ▾", "seq3-source-settings", listOf(
        "Live preview: ${if (view.sourceLiveEnabled) "On" else "Off"}",
        "Autocomplete: ${if (view.sourceAutocompleteEnabled) "On" else "Off"}",
    ), onSelect = { index ->
        if (index == 0) view.sourceLiveEnabled = !view.sourceLiveEnabled
        else view.sourceAutocompleteEnabled = !view.sourceAutocompleteEnabled
        view.sourceFocusRequestGeneration++
    }, tags = listOf("seq3-source-live-toggle", "seq3-source-autocomplete-toggle"))
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
        }, variant = ButtonVariant.Secondary, horizontalPadding = 7.dp, modifier = Modifier.testTag(tag))
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
                    Box(if (index < tags.size) Modifier.testTag(tags[index]) else Modifier) {
                        Seq3DropdownMenuItem(option, active = false) { open = false; onSelect(index) }
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
        val alias = sourceUniqueAlias("Alias", sourceParticipants(editorState.text.toString(), dialect))
        val snippet = sourceParticipantDeclaration(alias, "Name", actor, dialect)
        val fields = if (dialect == Seq3Dialect.MERMAID) sourceSnippetFields(snippet, alias to false, "Name" to false).reversed()
            else sourceSnippetFields(snippet, "Name" to false, alias to false)
        insertSnippet(snippet, fields, true)
    }
    fun addMessage(style: String) {
        val snippet = sourceMessageLine("From", "To", style, dialect, "message label")
        insertSnippet(snippet, sourceSnippetFields(snippet, "From" to true, "To" to true, "message label" to false), false)
    }
    fun applyNewDiagram() {
        val starter = seq3SourceStarter(dialect)
        confirmNewDiagram = false
        when (val result = state.seq3Sessions.startNewSourceDiagram(session.id, starter, dialect)) {
            is Seq3SourceImportResult.Failure -> view.sourceDiagnostics = result.diagnostics
            is Seq3SourceImportResult.Success -> if (result.evidenceLoss) onNewDiagramEvidenceLoss(starter, dialect) else {
                view.sourceDiagnostics = emptyList(); view.sourceEvidenceLossPending = false
            }
        }
        reclaimFocus()
    }
    FlowRow(Modifier.fillMaxWidth().background(tc().p).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        AppButton(if (showSourceMarkers) "Hide markers" else "Show markers", onClick = { onToggleSourceMarkers(); reclaimFocus() },
            variant = ButtonVariant.Secondary, horizontalPadding = 7.dp, modifier = Modifier.testTag("seq3-source-toggle-markers"))
        AppButton("+ Participant", onClick = { addParticipant(false) }, variant = ButtonVariant.Secondary,
            horizontalPadding = 7.dp, modifier = Modifier.testTag("seq3-source-insert-participant"))
        AppButton("+ Actor", onClick = { addParticipant(true) }, variant = ButtonVariant.Secondary,
            horizontalPadding = 7.dp, modifier = Modifier.testTag("seq3-source-insert-actor"))
        SourceCompactMenu("+ Message ▾", "seq3-source-message-menu", listOf("Call", "Return", "Async"),
            onSelect = { addMessage(listOf("Call", "Return", "Async")[it]) }, onClose = reclaimFocus)
        AppButton("+ Note", onClick = {
            val scope = seq3SourceNoteScope(editorState.text.toString(), editorState.selection.start, dialect,
                sourceParticipants(editorState.text.toString(), dialect).map { it.alias }) ?: "Participant"
            val snippet = "${if (dialect == Seq3Dialect.MERMAID) "Note" else "note"} over $scope: note text"
            insertSnippet(snippet, sourceSnippetFields(snippet, "note text" to false), false)
        }, variant = ButtonVariant.Secondary, horizontalPadding = 7.dp, modifier = Modifier.testTag("seq3-source-insert-note"))
        val blocks = listOf("loop", "alt", "opt", "par", "critical", "break", "group")
        SourceCompactMenu("+ Block ▾", "seq3-source-fragment-menu", blocks, onSelect = {
            val snippet = "${blocks[it]} condition\n    \nend"
            insertSnippet(snippet, sourceSnippetFields(snippet, "condition" to false), false)
        }, onClose = reclaimFocus)
        SourceCompactMenu("+ Activation ▾", "seq3-source-activation-menu", listOf("Activate", "Deactivate"), onSelect = {
            val snippet = "${if (it == 0) "activate" else "deactivate"} Participant"
            insertSnippet(snippet, sourceSnippetFields(snippet, "Participant" to true), false)
        }, onClose = reclaimFocus)
        AppButton("New diagram", onClick = {
            val latest = state.seq3Sessions.sessions.firstOrNull { it.id == session.id } ?: session
            if (seq3SourceHasUserContent(view.sourceDraft, dialect, latest.document.title) || seq3DocumentHasUserContent(latest.document))
                confirmNewDiagram = true else applyNewDiagram()
        }, variant = ButtonVariant.Secondary, horizontalPadding = 7.dp, modifier = Modifier.testTag("seq3-source-new-diagram"))
    }
    if (confirmNewDiagram) Dialog(onDismissRequest = { confirmNewDiagram = false; reclaimFocus() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(420.dp).background(tc().p, RoundedCornerShape(12.dp)).border(1.dp, tc().br, RoundedCornerShape(12.dp)).padding(18.dp),
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
    val text = line.trim()
    if (dialect == Seq3Dialect.MERMAID) Regex("^\\S+(?:-->>|->>|-\\))\\S+:.*$").matches(text)
    else Regex("^\\S+\\s+(?:-->|->>|->)\\s+\\S+:.*$").matches(text)
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

private fun sourceParticipants(source: String, dialect: Seq3Dialect): List<Seq3SourceParticipant> {
    val result = linkedMapOf<String, Seq3SourceParticipant>()
    source.lineSequence().forEach { raw ->
        val text = raw.trim()
        val match = if (dialect == Seq3Dialect.MERMAID) {
            Regex("^(participant|actor)\\s+(\\S+)\\s+as\\s+(.+)$").matchEntire(text)?.let {
                Seq3SourceParticipant(it.groupValues[2], it.groupValues[3], it.groupValues[1] == "actor")
            }
        } else {
            Regex("^(participant|actor)\\s+\"(.*)\"\\s+as\\s+(\\S+)$").matchEntire(text)?.let {
                Seq3SourceParticipant(it.groupValues[3], it.groupValues[2], it.groupValues[1] == "actor")
            }
        }
        match?.let { result[it.alias] = it }
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
        val arrow = if (dialect == Seq3Dialect.MERMAID) {
            Regex("^(\\S+?)(?:-->>|->>|-\\))(\\S+):").find(trimmed)?.let { it.groupValues[1] to it.groupValues[2] }
        } else {
            Regex("^(\\S+)\\s+(?:-->|->>|->)\\s+(\\S+):").find(trimmed)?.let { it.groupValues[1] to it.groupValues[2] }
        }
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

private fun insertSeq3SourceSnippet(
    source: String,
    selection: TextRange,
    snippet: String,
    dialect: Seq3Dialect,
    header: Boolean = false,
    titleHeader: Boolean = false,
): TextFieldValue {
    val start = if (header) {
        seq3SourceHeaderInsertionOffset(source, dialect, titleOnly = titleHeader)
    } else {
        val caret = selection.start.coerceIn(0, source.length)
        source.indexOf('\n', caret).let { if (it < 0) source.length else it }
    }
    val end = start
    return insertSeq3SourceAt(source, start, end, snippet, dialect)
}

private fun insertSeq3SourceAt(
    source: String,
    start: Int,
    end: Int,
    snippet: String,
    dialect: Seq3Dialect,
    cursorOffset: Int = snippet.length,
): TextFieldValue {
    val clampedStart = start.coerceIn(0, source.length)
    val clampedEnd = end.coerceIn(clampedStart, source.length)
    val terminator = if (dialect == Seq3Dialect.PLANTUML) "@enduml" else null
    val terminatorAt = terminator?.let { source.indexOf(it).takeIf { index -> index >= 0 } } ?: source.length
    val insertionAt = minOf(clampedStart, terminatorAt)
    val replacedEnd = minOf(clampedEnd, terminatorAt).coerceAtLeast(insertionAt)
    val before = source.substring(0, insertionAt)
    val after = source.substring(replacedEnd)
    val content = snippet.trim('\n', '\r')
    val prefix = if (before.isNotEmpty() && !before.endsWith('\n')) "\n" else ""
    val suffix = if (after.isNotEmpty() && !after.startsWith('\n')) "\n" else ""
    val next = before + prefix + content + suffix + after
    val caret = (before.length + prefix.length + cursorOffset.coerceIn(0, content.length)).coerceIn(0, next.length)
    return TextFieldValue(next, TextRange(caret))
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
    text: Color,
    keyword: Color,
    comment: Color,
    directive: Color,
    showMarkers: Boolean,
) {
    val rawText = buffer.asCharSequence().toString()
    if (!showMarkers) {
        seq3SourceMarkerDeletionRanges(rawText, dialect).asReversed().forEach { range ->
            buffer.replace(range.start, range.endExclusive, "")
        }
    }
    val visible = buffer.asCharSequence().toString()
    highlightSeq3Source(visible, dialect, text, keyword, comment, directive).spanStyles.forEach { range ->
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

internal fun highlightSeq3Source(
    source: String,
    dialect: Seq3Dialect,
    text: Color,
    keyword: Color,
    comment: Color,
    directive: Color,
): AnnotatedString = buildAnnotatedString {
    append(source)
    var offset = 0
    source.split('\n').forEach { line ->
        val trimmed = line.trimStart()
        val leading = line.length - trimmed.length
        val start = offset + leading
        val lineEnd = offset + line.length
        val lineColor = when {
            dialect == Seq3Dialect.MERMAID && trimmed.startsWith("%%") -> comment
            dialect == Seq3Dialect.PLANTUML && trimmed.startsWith("'") -> comment
            SOURCE_KEYWORD_LINE.containsMatchIn(trimmed) -> keyword
            SOURCE_FRAGMENT_LINE.containsMatchIn(trimmed) -> directive
            else -> text
        }
        if (lineColor != text && start < lineEnd) addStyle(SpanStyle(color = lineColor), start, lineEnd)
        offset = lineEnd + 1
    }
}

private val SOURCE_KEYWORD_LINE = Regex("^(?:@startuml|@enduml|sequenceDiagram|participant|actor|title|activate|deactivate|create|destroy|note\\b|Note\\b|end\\b)")
private val SOURCE_FRAGMENT_LINE = Regex("^(?:loop|alt|opt|par|critical|break|group|else|and|option)\\b")
