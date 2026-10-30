@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3SourceImportResult
import kotlinx.coroutines.flow.collect
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
        Seq3Dialect.PLANTUML,
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

@Composable
internal fun Seq3SourceHeaderActions(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState) {
    if (view.sourceDraftStale || view.sourceDraftDirty) {
        AppText(
            if (view.sourceDraftStale) "Stale" else "Draft",
            color = if (view.sourceDraftStale) tc().warn else tc().ac,
            fontSize = 10.sp,
            modifier = Modifier.testTag("seq3-source-draft-status"),
        )
        AppButton(
            if (view.sourceDraftStale) "Reload" else "Discard",
            onClick = { reloadSeq3SourceDraft(state, session, view) },
            variant = ButtonVariant.Ghost,
            horizontalPadding = 6.dp,
            modifier = Modifier.testTag("seq3-source-reload"),
        )
    }
    AppButton(
        "Apply",
        onClick = { applySeq3SourceDraft(state, session, view) },
        variant = ButtonVariant.Primary,
        horizontalPadding = 7.dp,
        enabled = !view.sourceDraftStale,
        modifier = Modifier.testTag("seq3-source-apply"),
    )
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
    val gutterListState = rememberLazyListState()
    LaunchedEffect(session.id) {
        if (!view.sourceDraftInitialized) state.seq3Sessions.prepareSourceDraft(session.id)
    }
    LaunchedEffect(session.id, view.sourceFocusRequestGeneration) {
        runCatching { requester.requestFocus() }
    }
    LaunchedEffect(session.id, view.sourceEditorGeneration, editorState) {
        snapshotFlow { TextFieldValue(editorState.text.toString(), editorState.selection) }.collect { next ->
            if (next.text != lastValue.text) {
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
    val lineCount = editorState.text.toString().count { it == '\n' } + 1
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
        state.seq3Sessions.updateSourceDraftText(session.id, next.text)
    }

    Column(
        modifier.background(tc.bg),
    ) {
        if (view.sourceDraftStale) AppText(
            "Diagram changed. Reload source to continue.",
            color = tc.warn,
            fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth().background(tc.warnBg).padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("seq3-source-stale"),
        )
        if (view.sourceDiagnostics.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(tc.warnBg).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                view.sourceDiagnostics.forEach { diagnostic ->
                    AppText("Line ${diagnostic.line}: ${diagnostic.message}", color = tc.warn, fontSize = 10.sp)
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth().background(tc.p2)) {
            LazyColumn(
                Modifier.width(42.dp).fillMaxHeight().padding(top = 8.dp, bottom = 8.dp),
                state = gutterListState,
                userScrollEnabled = false,
            ) {
                items(lineCount) { index ->
                    val line = index + 1
                    val hasDiagnostic = view.sourceDiagnostics.any { it.line == line }
                    Box(
                        Modifier.fillMaxWidth().height(lineHeightDp).padding(end = 8.dp),
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
                scrollState = scroll,
                modifier = Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp, bottom = 8.dp)
                    .testTag("seq3-source-editor")
                    .focusRequester(requester)
                    .onFocusChanged { view.textFieldFocused = it.hasFocus }
                    .horizontalScroll(rememberScrollState())
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || !(event.isCtrlPressed || event.isMetaPressed)) return@onPreviewKeyEvent false
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
                        if (editorState.text.isEmpty()) AppText("@startuml", color = tc.td, fontSize = 11.sp, fontFamily = MONO)
                        inner()
                    }
                },
            )
        }
    }

    if (view.sourceEvidenceLossPending) {
        Dialog(
            onDismissRequest = {
                view.sourceEvidenceLossPending = false
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
                    "This source edit removes marked log rows from the diagram. " +
                        "Applying it will keep the authored diagram structure, but those source links " +
                        "cannot be restored from the draft.",
                    color = tc.ts,
                    fontSize = 11.sp,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    AppButton(
                        "Keep editing",
                        onClick = {
                            view.sourceEvidenceLossPending = false
                            runCatching { requester.requestFocus() }
                        },
                        variant = ButtonVariant.Ghost,
                    )
                    AppButton(
                        "Remove evidence and apply",
                        onClick = { applySeq3SourceDraft(state, session, view, confirmEvidenceLoss = true) },
                        variant = ButtonVariant.Primary,
                    )
                }
            }
        }
    }
}
