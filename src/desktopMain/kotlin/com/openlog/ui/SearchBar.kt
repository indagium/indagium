package com.openlog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlog.model.LogSearchState

// Non-destructive in-view "Find" bar (Ctrl/Cmd+F when Settings.ctrlFTarget == FIND_BAR — see
// AppState.openSearch and App.kt's onFocusFilterSearch). Rendered above ColHeader in
// LogViewer.kt, only while tab.search.active; drives buildFullLineAnnotation's search-highlight
// spans (LogViewer.kt) and jumps the row selection via AppState.requestScrollAnchor.
@Composable
fun SearchBar(
    search: LogSearchState,
    onQueryChange: (String) -> Unit,
    onToggleCase: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    val focusRequester = remember { FocusRequester() }
    var fieldValue by remember { mutableStateOf(TextFieldValue(search.query, TextRange(search.query.length))) }

    // Resyncs the field from external query changes (a fresh openSearch, or this tab's search
    // state otherwise changing under us) without clobbering the user's own cursor position on
    // every recomposition a debounced match recompute causes — those never touch `query` itself.
    LaunchedEffect(search.query) {
        if (fieldValue.text != search.query) {
            fieldValue = TextFieldValue(search.query, TextRange(search.query.length))
        }
    }

    // Bumped by AppState.openSearch on every Ctrl/Cmd+F, including a repeat press while this bar
    // is already open. App.kt's root onPreviewKeyEvent (handleGlobalKey) always intercepts Ctrl+F
    // on the way down before it could ever reach this field's own onPreviewKeyEvent below, so
    // "refocus + select all" has to be driven from here via the nonce rather than a local Ctrl+F
    // branch that would never actually fire.
    LaunchedEffect(search.focusNonce) {
        runCatching { focusRequester.requestFocus() }
        fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
    }

    val counterText = when {
        search.query.isEmpty() -> ""
        search.invalidPattern -> "invalid"
        else -> "${if (search.matchCount == 0) 0 else search.currentIdx + 1}/${search.matchCount}"
    }
    val counterColor = when {
        search.invalidPattern -> DANGER_RED
        else -> tc.td
    }

    Row(
        modifier
            .fillMaxWidth()
            .background(tc.p)
            .border(BorderStroke(1.dp, tc.br))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = fieldValue,
                onValueChange = { new ->
                    fieldValue = new
                    if (new.text != search.query) onQueryChange(new.text)
                },
                singleLine = true,
                textStyle = TextStyle(color = tc.tx, fontSize = 12.sp, fontFamily = MONO),
                cursorBrush = SolidColor(tc.ac),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            ev.key == Key.Enter && ev.isShiftPressed -> { onPrev(); true }
                            ev.key == Key.Enter -> { onNext(); true }
                            ev.key == Key.Escape -> { onClose(); true }
                            else -> false
                        }
                    },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (fieldValue.text.isEmpty()) {
                            AppText("Find in filtered log (regex)…", color = tc.td, fontSize = 12.sp, fontFamily = MONO)
                        }
                        inner()
                    }
                },
            )
        }
        AppText(
            counterText, color = counterColor, fontSize = 11.sp, fontFamily = MONO,
            modifier = Modifier.widthIn(min = 40.dp),
        )
        PillBtn("Aa", active = search.caseSensitive, onClick = onToggleCase)
        SquareIconButton("↑", fontSize = 12.sp, onClick = onPrev, size = 20.dp)
        SquareIconButton("↓", fontSize = 12.sp, onClick = onNext, size = 20.dp)
        CloseButton(onClick = onClose)
    }
}
