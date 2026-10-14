@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import com.indagium.model.LogSearchState
import com.indagium.model.SearchMode
import com.indagium.model.SearchScope
import com.indagium.model.SearchScopeBehavior

/** Text/caret state retained by LogViewer when a Find field moves between single and split layouts. */
internal class SearchBarFieldState(initialQuery: String) {
    val value = mutableStateOf(TextFieldValue(initialQuery, TextRange(initialQuery.length)))
    val handledFocusNonce = mutableStateOf(0)
    val focusRestoreRequestNonce = mutableIntStateOf(0)
}

/** Per-tab, per-scope Find field state. Search data remains owned by AppState; this only retains UI caret state. */
internal class SearchBarFieldStateStore {
    private val fields = mutableMapOf<SearchScope, SearchBarFieldState>()

    fun field(scope: SearchScope, query: String): SearchBarFieldState =
        fields.getOrPut(scope) { SearchBarFieldState(query) }
}

/** One field for one concrete scope. In Two searches mode the caller composes this twice with
 *  different state and callbacks; no query, cursor, case option or focus state is shared. */
@Composable
internal fun SearchBar(
    tabId: String,
    search: LogSearchState,
    onQueryChange: (SearchScope, String) -> Unit,
    onToggleCase: (SearchScope) -> Unit,
    onNext: (SearchScope) -> Unit,
    onPrev: (SearchScope) -> Unit,
    onClose: (SearchScope) -> Unit,
    modifier: Modifier = Modifier,
    fieldLabel: String? = null,
    mode: SearchMode = SearchMode.FILTERED,
    showModeSelector: Boolean = false,
    onModeChange: (SearchMode) -> Unit = {},
    onFocused: (SearchScope) -> Unit = {},
    autoFocus: Boolean = false,
    /** Non-null only where a Filtered field may hand its query to the Unfiltered field (FileView's
     *  single-field Filtered mode); shown as a "Search all lines" action when nothing matched. */
    onSearchAllLines: (() -> Unit)? = null,
    showSyncToggle: Boolean = false,
    syncEnabled: Boolean = true,
    onSyncToggle: () -> Unit = {},
    sameModeIsNoop: Boolean = true,
    searchScopeBehavior: SearchScopeBehavior = SearchScopeBehavior.FILTERED,
    lastManualSearchMode: SearchMode = SearchMode.FILTERED,
    fieldState: SearchBarFieldState? = null,
) {
    val tc = tc()
    val focusRequester = remember(tabId, search.scope) { FocusRequester() }
    val localFieldState = fieldState ?: remember(tabId, search.scope) { SearchBarFieldState(search.query) }
    var fieldValue by localFieldState.value
    var handledFocusNonce by localFieldState.handledFocusNonce
    var focusRestoreRequestNonce by localFieldState.focusRestoreRequestNonce

    fun withSearchFocus(action: () -> Unit) {
        onFocused(search.scope)
        focusRestoreRequestNonce++
        action()
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(search.query) {
        if (fieldValue.text != search.query) {
            fieldValue = TextFieldValue(search.query, TextRange(search.query.length))
        }
    }

    LaunchedEffect(search.focusNonce, autoFocus, focusRestoreRequestNonce) {
        if (autoFocus && search.focusNonce > handledFocusNonce) {
            runCatching { focusRequester.requestFocus() }
            fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
            handledFocusNonce = search.focusNonce
        } else {
            // Mode changes can move a field between composition branches. A live
            // focus owner is restored without select-all; inactive fields never claim focus from
            // an older restore request after another scope is chosen.
            if (autoFocus) runCatching { focusRequester.requestFocus() }
        }
    }

    // While a query edit recomputes, the previous count stays visible (dimmed) instead of
    // switching to a wider "Searching…" label on every keystroke; the label is only used when there
    // is no previous result to show.
    val counterText = when {
        search.query.isEmpty() -> ""
        search.isSearching && search.matchCount == 0 -> "Searching…"
        search.isSearching -> "${search.currentIdx + 1}/${search.matchCount}"
        search.timedOut -> "Timed out"
        search.invalidPattern -> "Invalid regex"
        search.matchCount == 0 -> "0/0"
        else -> "${search.currentIdx + 1}/${search.matchCount}"
    }
    val counterColor = when {
        search.isSearching -> tc.td.copy(alpha = 0.55f)
        search.invalidPattern || search.timedOut -> DANGER_RED
        else -> tc.td
    }
    val showSearchAllLines = onSearchAllLines != null && search.scope == SearchScope.FILTERED &&
        search.query.isNotEmpty() && !search.isSearching && !search.invalidPattern && !search.timedOut &&
        search.matchCount == 0

    Row(
        modifier
            .fillMaxWidth()
            .background(tc.p)
            .border(BorderStroke(1.dp, tc.br))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (fieldLabel != null) {
            AppText(
                fieldLabel,
                color = tc.ac,
                fontSize = 10.sp,
                fontFamily = UI,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 82.dp, max = 112.dp),
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = fieldValue,
                onValueChange = { new ->
                    fieldValue = new
                    if (new.text != search.query) onQueryChange(search.scope, new.text)
                },
                singleLine = true,
                textStyle = TextStyle(color = tc.tx, fontSize = 12.sp, fontFamily = MONO),
                cursorBrush = SolidColor(tc.ac),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = if (showModeSelector) 108.dp else 0.dp)
                    .testTag("search-field-${search.scope.name.lowercase()}")
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        if (it.isFocused) {
                            onFocused(search.scope)
                        }
                    }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            ev.key == Key.Enter && ev.isShiftPressed -> { onPrev(search.scope); true }
                            ev.key == Key.Enter -> { onNext(search.scope); true }
                            ev.key == Key.Escape -> { onClose(search.scope); true }
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
            if (showModeSelector) {
                SearchModeDropdown(
                    mode = mode,
                    onChange = onModeChange,
                    onDismiss = { targetScope ->
                        onFocused(targetScope)
                        if (targetScope == search.scope) {
                            focusRestoreRequestNonce++
                        }
                    },
                    currentScope = search.scope,
                    sameModeIsNoop = sameModeIsNoop,
                    showSyncToggle = showSyncToggle,
                    syncEnabled = syncEnabled,
                    onSyncToggle = onSyncToggle,
                    searchScopeBehavior = searchScopeBehavior,
                    lastManualSearchMode = lastManualSearchMode,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        if (counterText.isNotEmpty()) {
            AppText(
                counterText,
                color = counterColor,
                fontSize = 10.sp,
                fontFamily = MONO,
                modifier = Modifier.widthIn(min = 38.dp).testTag("search-counter-${search.scope.name.lowercase()}"),
            )
        }
        onSearchAllLines?.takeIf { showSearchAllLines }?.let { searchAllLines ->
            PillBtn(
                "Search all lines",
                active = false,
                modifier = Modifier.testTag("search-all-lines"),
                // No withSearchFocus: this field leaves composition, and the Unfiltered field that
                // replaces it claims focus itself (its slot becomes the focused scope).
                onClick = searchAllLines,
            )
        }
        PillBtn("Aa", active = search.caseSensitive, onClick = { withSearchFocus { onToggleCase(search.scope) } })
        SquareIconButton(
            "↑", fontSize = 12.sp, onClick = { withSearchFocus { onPrev(search.scope) } }, size = 20.dp,
            modifier = Modifier.testTag("search-prev-${search.scope.name.lowercase()}"),
        )
        SquareIconButton(
            "↓", fontSize = 12.sp, onClick = { withSearchFocus { onNext(search.scope) } }, size = 20.dp,
            modifier = Modifier.testTag("search-next-${search.scope.name.lowercase()}"),
        )
        CloseButton(onClick = { onClose(search.scope) })
    }
}

@Composable
private fun SearchModeDropdown(
    mode: SearchMode,
    onChange: (SearchMode) -> Unit,
    onDismiss: (SearchScope) -> Unit,
    currentScope: SearchScope,
    sameModeIsNoop: Boolean,
    showSyncToggle: Boolean,
    syncEnabled: Boolean,
    onSyncToggle: () -> Unit,
    searchScopeBehavior: SearchScopeBehavior,
    lastManualSearchMode: SearchMode,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val popupWidth = if (windowSize.width > 0) {
        minOf(286.dp, (with(density) { windowSize.width.toDp() } - 16.dp).coerceAtLeast(1.dp))
    } else {
        286.dp
    }
    val popupMaxHeight = if (windowSize.height > 0) {
        (with(density) { windowSize.height.toDp() } - 24.dp).coerceAtLeast(1.dp)
    } else {
        420.dp
    }
    var open by remember { mutableStateOf(false) }
    var suppressTooltip by remember { mutableStateOf(false) }
    var tooltipResetRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(tooltipResetRequest) {
        if (tooltipResetRequest > 0) {
            delay(900)
            suppressTooltip = false
        }
    }
    fun dismissPopup(restoreScope: SearchScope?) {
        if (!open) return
        open = false
        suppressTooltip = true
        tooltipResetRequest++
        restoreScope?.let(onDismiss)
    }
    val labels = listOf("Filtered", "Unfiltered", "Two searches")
    val modes = SearchMode.entries
    val selectedIndex = modes.indexOf(mode)
    val shortcut = if (isMacOs) "⌘F" else "Ctrl+F"
    val unfilteredShortcut = if (isMacOs) "⌘⌥F" else "Ctrl+Alt+F"
    val lastModeName = labels.getOrElse(SearchMode.entries.indexOf(lastManualSearchMode)) { "Filtered" }
    val openPolicy = when (searchScopeBehavior) {
        SearchScopeBehavior.FILTERED -> "opens Filtered"
        SearchScopeBehavior.UNFILTERED -> "opens Unfiltered"
        SearchScopeBehavior.LAST_USED -> "last mode ($lastModeName)"
        SearchScopeBehavior.FOLLOW_VIEW -> "follows Original view"
    }
    val modeTip = when (mode) {
        SearchMode.FILTERED -> "Filtered: visible rows; collapsed groups included."
        SearchMode.UNFILTERED -> "Unfiltered: all log lines."
        SearchMode.TWO_SEARCHES -> "Two fields; separate queries and case sensitivity."
    }
    val tip = buildList {
        add(modeTip)
        add("$shortcut: $openPolicy")
        add("$unfilteredShortcut: open/focus Unfiltered")
        add("Enter / Shift+Enter: next / previous")
        add("Esc: close this field")
        if (searchScopeBehavior == SearchScopeBehavior.FOLLOW_VIEW) {
            add("Manual choice resets on Find/view change.")
        }
    }.joinToString("\n")
    val trigger: @Composable () -> Unit = {
        Row(
            Modifier
                .height(22.dp)
                .testTag("search-mode-dropdown")
                .clip(RoundedCornerShape(10.dp))
                .background(tc.p2)
                .border(0.5.dp, if (open) tc.ac else tc.br, RoundedCornerShape(10.dp))
                .clickable {
                    if (open) {
                        dismissPopup(currentScope)
                    } else {
                        open = true
                    }
                }
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppText(labels.getOrElse(selectedIndex) { labels[0] }, color = tc.tx, fontSize = 10.sp, maxLines = 1)
            AppText(if (open) "▲" else "▼", color = tc.td, fontSize = 8.sp)
        }
    }

    // Keep Popup in a Box whose measured bounds are the trigger's bounds. The default Popup
    // alignment is relative to its direct parent, so a sibling Popup used to anchor at the
    // containing search row and could expand to the application width.
    Box(modifier) {
        if (open || suppressTooltip) {
            trigger()
        } else {
            TooltipArea(tooltip = { SearchModeTooltip(tip) }) { trigger() }
        }
        if (open) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, with(density) { 28.dp.roundToPx() }),
                onDismissRequest = { dismissPopup(currentScope) },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .width(popupWidth)
                        .heightIn(max = popupMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .background(tc.p, RoundedCornerShape(7.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                        .padding(4.dp)
                        .testTag("search-mode-popup"),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    modes.forEachIndexed { index, choice ->
                        val active = choice == mode
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (active) tc.abg else Color.Transparent)
                                .testTag("search-mode-option-${choice.name.lowercase()}")
                                .clickable {
                                    val noOp = choice == mode && sameModeIsNoop
                                    val targetScope = when (choice) {
                                        SearchMode.FILTERED -> SearchScope.FILTERED
                                        SearchMode.UNFILTERED -> SearchScope.UNFILTERED
                                        SearchMode.TWO_SEARCHES -> currentScope
                                    }
                                    dismissPopup(if (noOp) currentScope else targetScope)
                                    // Even an already-active mode is an explicit manual choice;
                                    // AppState persists that preference without changing the
                                    // active session, focus nonce, or query results.
                                    onChange(choice)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppText(labels[index], color = if (active) tc.ac else tc.tx, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                    if (showSyncToggle && mode == SearchMode.TWO_SEARCHES) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp).height(1.dp).background(tc.br))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(5.dp))
                                .testTag("search-link-toggle")
                                .clickable(onClick = onSyncToggle)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            AppText("Link selection & Find navigation", color = tc.tx, fontSize = 10.sp, maxLines = 2,
                                modifier = Modifier.weight(1f))
                            AppText(
                                if (syncEnabled) "Linked" else "Independent",
                                color = if (syncEnabled) tc.ac else tc.td,
                                fontSize = 10.sp,
                            )
                        }
                        AppText(
                            if (syncEnabled) {
                                "In any split view, selecting a Filtered row or finding a row can move both panels. " +
                                    "Original row clicks stay local; queries stay separate."
                            } else {
                                "Each panel keeps its own selection and scroll position in every Find mode. " +
                                    "Selecting or finding a row does not move the other panel. Queries stay separate."
                            },
                            color = tc.td,
                            fontSize = 9.sp,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchModeTooltip(text: String) {
    val tc = tc()
    Box(
        Modifier
            .testTag("search-mode-tooltip")
            .widthIn(min = 180.dp, max = 270.dp)
            .background(tc.p2, RoundedCornerShape(4.dp))
            .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        AppText(text, color = tc.tx, fontSize = 11.sp, maxLines = 12)
    }
}
