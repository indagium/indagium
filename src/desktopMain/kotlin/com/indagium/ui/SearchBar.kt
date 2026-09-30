@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.LogSearchState
import com.indagium.model.SearchScope

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
    /** Compare mode has no Original panel to search, so CompareView.kt leaves the chip off. */
    showScopeChip: Boolean = false,
    onToggleScope: () -> Unit = {},
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
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
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
                            val hint = if (search.scope == SearchScope.UNFILTERED) {
                                "Find in all lines (regex)…"
                            } else {
                                "Find in filtered log (regex)…"
                            }
                            AppText(hint, color = tc.td, fontSize = 12.sp, fontFamily = MONO)
                        }
                        inner()
                    }
                },
            )
            if (showScopeChip) {
                SearchScopeChip(
                    scope = search.scope,
                    onClick = {
                        onToggleScope()
                        // A clickable steals keyboard focus (see CLAUDE.md), which would leave the
                        // bar's Enter/Esc handling dead — hand it straight back to the field.
                        runCatching { focusRequester.requestFocus() }
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        // Only takes space while there is something to show, so the scope chip (at the field's
        // right end) sits right beside the Aa button when no query is typed.
        if (counterText.isNotEmpty()) {
            AppText(
                counterText, color = counterColor, fontSize = 11.sp, fontFamily = MONO,
                modifier = Modifier.widthIn(min = 40.dp),
            )
        }
        PillBtn("Aa", active = search.caseSensitive, onClick = onToggleCase)
        SquareIconButton("↑", fontSize = 12.sp, onClick = onPrev, size = 20.dp)
        SquareIconButton("↓", fontSize = 12.sp, onClick = onNext, size = 20.dp)
        CloseButton(onClick = onClose)
    }
}

// Filtered vs. all-lines toggle at the right end of the Find field. Unfiltered borrows the warm
// warn tint so it's obvious the search now reaches rows the filter hides.
@Composable
private fun SearchScopeChip(scope: SearchScope, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    val unfiltered = scope == SearchScope.UNFILTERED
    val shape = RoundedCornerShape(10.dp)
    val fg = if (unfiltered) tc.warn else tc.ts
    val bg = when {
        unfiltered -> tc.warnBg
        hovered -> tc.hv
        else -> tc.p2
    }
    val tip = if (unfiltered) {
        "Searching all lines in the Original panel. Click to search only the filtered view " +
            "(${if (isMacOs) "⌘F" else "Ctrl+F"})"
    } else {
        "Searching the filtered view — only lines your filter shows. Click to search all lines " +
            "(${if (isMacOs) "⌘⌥F" else "Ctrl+Alt+F"})"
    }
    TooltipArea(tooltip = { ToolbarTooltip(tip, maxLines = 3) }, modifier = modifier) {
        Row(
            Modifier
                .height(20.dp)
                // Clipped to the pill first so the hover/press highlight follows its rounded shape
                // instead of the default square indication.
                .clip(shape)
                .background(bg)
                .border(0.5.dp, if (unfiltered) tc.warn.copy(alpha = .6f) else tc.br, shape)
                .clickable(onClick = onClick)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = if (unfiltered) Icons.Outlined.FilterAltOff else Icons.Outlined.FilterAlt,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(11.dp),
            )
            AppText(if (unfiltered) "Unfiltered" else "Filtered", color = fg, fontSize = 10.sp)
        }
    }
}
