package com.indagium.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.HighlightTarget
import com.indagium.model.Highlighter
import com.indagium.model.LogTab
import com.indagium.model.MessageCompositionState
import com.indagium.model.MessageTemplate
import com.indagium.utils.CancellationCheck
import com.indagium.utils.HighlightRowCounts
import com.indagium.utils.countHighlighterRows
import com.indagium.utils.isValidRegexPattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// The filter panel's "Highlighters" section: the list of highlighters (each editable in place), the
// search-to-add field with its Tags / Messages / Text dropdown, and the keyword-regex highlight row.
// Moved out of FilterPanel.kt, which is already about 3.9k lines; FilterPanel keeps only the state
// that must outlive this section (the add field's FocusRequester, the panel-wide "a text field has
// focus" flag its key handler needs) and passes it in.

private const val HL_ROW_DP = 30
private const val HL_DROPDOWN_ROW_DP = 28
private const val HL_DROPDOWN_MAX_DP = 220
private const val HL_EDITOR_EXTRA_DP = 620
private const val HL_COUNT_DEBOUNCE_MS = 200L
private const val HL_COUNT_DEBOUNCE_LARGE_MS = 450L
private const val HL_SEARCH_DEBOUNCE_MS = 120L
private const val HL_SEARCH_DEBOUNCE_LARGE_MS = 350L
private const val HL_CANDIDATE_ID = "candidate"
private const val HIGH_CONTRAST_LUMINANCE_THRESHOLD = 0.48f

// Colour-picker state the panel's Escape handling needs to reach from outside this section (Esc on
// the panel with no text field focused closes any open picker, as it always did).
internal class HighlighterSectionState {
    var rowColorPickerId by mutableStateOf<String?>(null)
    var addColorPickerOpen by mutableStateOf(false)
    var addForegroundPickerOpen by mutableStateOf(false)
    var addWholeLine by mutableStateOf(false)
    var addBackgroundEnabled by mutableStateOf(true)
    var addTextColor by mutableStateOf<Color?>(null)
    var addFontFamily by mutableStateOf<String?>(null)
    var addBold by mutableStateOf<Boolean?>(null)
    var addItalic by mutableStateOf<Boolean?>(null)
    val addScopeChip = mutableStateOf<String?>(null)

    fun closeColorPickers() {
        rowColorPickerId = null
        addColorPickerOpen = false
        addForegroundPickerOpen = false
    }
}

// The section's callbacks in one bag, so FilterPanel passes one parameter instead of a dozen (see
// LogCompositionActions for the same idea).
internal typealias AddHighlighterAction = (
    pattern: String,
    regex: Boolean,
    color: Color,
    wholeLine: Boolean,
    target: HighlightTarget,
    tag: String?,
    backgroundEnabled: Boolean,
    textColor: Color?,
    fontFamily: String?,
    bold: Boolean?,
    italic: Boolean?,
) -> Unit

internal data class HighlighterActions(
    val onAdd: AddHighlighterAction,
    val onRemove: (String) -> Unit,
    val onToggle: (String) -> Unit,
    val onSetColor: (String, Color) -> Unit,
    val onUpdate: (String, (Highlighter) -> Highlighter) -> Unit,
    val onSetNewPattern: (String) -> Unit,
    val onSetNewRegex: (Boolean) -> Unit,
    val onSetNewColor: (Color) -> Unit,
    val onSetKwHighlightEnabled: (Boolean) -> Unit,
    val onSetKwHighlightColor: (Color) -> Unit,
    val onRequestMessageComposition: () -> Unit,
    val customColors: List<Color>,
    val paletteColumns: Int,
    val onSaveCustomColor: (Color) -> Unit,
    val onDeleteCustomColor: (Color) -> Unit,
    val onPaletteColumnsChange: (Int) -> Unit,
    val customColorEditorExpanded: Boolean = true,
    val onCustomColorEditorExpandedChange: (Boolean) -> Unit = {},
)

// Counts run off the UI thread and are keyed on the fields that decide what matches (not colour,
// on/off or whole-line), same "cancel and relaunch, keep the last answer on screen" shape as
// FilterPanel's rememberUnifiedCandidates. Large files are only scanned up to
// LARGE_FILE_CANDIDATE_SCAN_LIMIT entries, which the result reports as capped ("≥N").
@Composable
internal fun rememberHighlighterRowCounts(tab: LogTab, highlighters: List<Highlighter>): HighlightRowCounts {
    var result by remember(tab.id, tab.largeFileMode) { mutableStateOf(HighlightRowCounts()) }
    val keys = highlighters.map { it.matchKey() }
    LaunchedEffect(tab.id, tab.largeFileMode, tab.logData.size, keys) {
        if (keys.isEmpty()) {
            result = HighlightRowCounts()
            return@LaunchedEffect
        }
        delay(if (tab.largeFileMode) HL_COUNT_DEBOUNCE_LARGE_MS else HL_COUNT_DEBOUNCE_MS)
        val entries = tab.logData
        val limit = if (tab.largeFileMode) LARGE_FILE_CANDIDATE_SCAN_LIMIT else Int.MAX_VALUE
        result = withContext(Dispatchers.Default) {
            countHighlighterRows(entries, highlighters, CancellationCheck { ensureActive() }, limit)
        }
    }
    return result
}

private fun pseudoHighlighter(candidate: HighlightCandidate) = Highlighter(
    id = HL_CANDIDATE_ID,
    pattern = candidate.pattern,
    regex = candidate.regex,
    color = Color.Transparent,
    on = true,
    target = candidate.target,
    tag = candidate.tag,
)

// Next add-form colour: the one after [current] that no highlighter uses yet, else just the next.
private fun nextHighlightColor(current: Color, used: Set<Color>): Color {
    val idx = HL_COLORS.indexOf(current)
    return (HL_COLORS.drop(idx + 1) + HL_COLORS).firstOrNull { it !in used } ?: HL_COLORS[(idx + 1) % HL_COLORS.size]
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun HighlighterSection(
    tab: LogTab,
    fpState: FilterPanelUiState,
    sectionState: HighlighterSectionState,
    actions: HighlighterActions,
    sortedTags: List<String>,
    tagUsage: Map<String, Int>,
    mostUsedTagLimit: Int,
    filterListRows: Int,
    newHlPat: String,
    newHlRx: Boolean,
    newHlColor: Color,
    inputFocusRequester: FocusRequester,
    onInputFocusedChange: (Boolean) -> Unit,
    onTabOut: () -> Unit,
    onReclaimFocus: () -> Unit,
    onUiStateChanged: () -> Unit,
) {
    val tc = tc()
    val filter = tab.filter
    val regexHighlightAvailable = filter.mode == FilterMode.KEYWORD && filter.kwRegex && filter.kwText.isNotBlank()
    val displayedCount = filter.highlighters.size + if (regexHighlightAvailable) 1 else 0
    val enabledCount = filter.highlighters.count { it.on } + if (regexHighlightAvailable && filter.kwHighlightEnabled) 1 else 0
    var kwPickerOpen by remember { mutableStateOf(false) }
    var editingId by remember(tab.id) { mutableStateOf<String?>(null) }
    var addFocused by remember { mutableStateOf(false) }
    val counts = rememberHighlighterRowCounts(tab, filter.highlighters)
    val editing = filter.highlighters.firstOrNull { it.id == editingId }
    // The editor's own pattern field, and the editor as a whole, count as "a text field has focus"
    // so the panel stops treating arrows/Enter/Delete as list navigation while it is open.
    LaunchedEffect(editing != null) { onInputFocusedChange(addFocused || editing != null) }
    DisposableEffect(Unit) { onDispose { onInputFocusedChange(false) } }
    // A highlighter deleted elsewhere (MCP, another panel action) must not leave a dangling editor.
    LaunchedEffect(editing == null, editingId) { if (editing == null && editingId != null) editingId = null }

    Box(Modifier.testTag("highlighters-section-header")) {
        SectionHeader(
            "Highlighters",
            trailing = if (displayedCount > 0) {
                {
                    Row(
                        Modifier.hoverPill().clickable {
                            val listWasVisible = fpState.highlightersExpanded && fpState.hlListExpanded
                            if (fpState.highlightersExpanded) {
                                fpState.hlListExpanded = !fpState.hlListExpanded
                            } else {
                                fpState.highlightersExpanded = true
                                fpState.hlListExpanded = true
                            }
                            onUiStateChanged()
                            if (listWasVisible) {
                                editingId = null
                                kwPickerOpen = false
                                sectionState.rowColorPickerId = null
                            }
                            onInputFocusedChange(addFocused)
                            onReclaimFocus()
                        }
                            .padding(horizontal = 5.dp, vertical = 3.dp)
                            .testTag("highlighters-list-toggle"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        AppText(
                            "$enabledCount active",
                            color = if (enabledCount > 0) tc.ac else tc.td,
                            fontSize = 10.sp,
                            fontFamily = UI,
                            fontWeight = FontWeight.SemiBold,
                        )
                        AppText(
                            if (fpState.highlightersExpanded && fpState.hlListExpanded) "▾" else "▸",
                            color = tc.ts,
                            fontSize = 10.sp,
                        )
                    }
                }
            } else {
                null
            },
            expanded = fpState.highlightersExpanded,
            onToggle = {
                fpState.highlightersExpanded = !fpState.highlightersExpanded
                onUiStateChanged()
                if (!fpState.highlightersExpanded) {
                    editingId = null
                    addFocused = false
                    kwPickerOpen = false
                    sectionState.closeColorPickers()
                    onInputFocusedChange(false)
                }
            },
        )
    }
    if (fpState.highlightersExpanded && displayedCount > 0 && fpState.hlListExpanded) {
        val rows = minOf(displayedCount, filterListRows) * HL_ROW_DP + if (editing != null) HL_EDITOR_EXTRA_DP else 0
        BoundedScrollBoxDp(rows) {
            if (regexHighlightAvailable) {
                KeywordHighlightRow(filter, kwPickerOpen, { kwPickerOpen = it }, actions)
            }
            filter.highlighters.forEach { hl ->
                Column {
                    HighlighterRow(
                        hl = hl,
                        countText = counts.counts[hl.id]?.let { formatHighlightCount(it, counts.capped) },
                        colorPicker = {
                            HighlighterColorPicker(
                                color = hl.color,
                                onColorChange = { actions.onSetColor(hl.id, it) },
                                customColors = actions.customColors,
                                onSaveCustomColor = actions.onSaveCustomColor,
                                onDeleteCustomColor = actions.onDeleteCustomColor,
                                paletteColumns = actions.paletteColumns,
                                onPaletteColumnsChange = actions.onPaletteColumnsChange,
                                pickerOpen = sectionState.rowColorPickerId == hl.id,
                                onPickerOpenChange = { open -> sectionState.rowColorPickerId = if (open) hl.id else null },
                                testTagPrefix = "highlighter-row-color",
                                customColorEditorExpanded = actions.customColorEditorExpanded,
                                onCustomColorEditorExpandedChange = actions.onCustomColorEditorExpandedChange,
                            )
                        },
                        editing = editingId == hl.id,
                        onEdit = { editingId = if (editingId == hl.id) null else hl.id },
                        onToggleMode = { actions.onUpdate(hl.id) { it.copy(wholeLine = !it.wholeLine) } },
                        onToggleOn = { actions.onToggle(hl.id) },
                        onRemove = { actions.onRemove(hl.id) },
                    )
                    if (editingId == hl.id) {
                        HighlighterEditor(
                            tab = tab,
                            original = hl,
                            sortedTags = sortedTags,
                            actions = actions,
                            onDone = { edited ->
                                actions.onUpdate(hl.id) { current ->
                                    current.copy(
                                        pattern = edited.pattern,
                                        regex = edited.regex,
                                        caseSensitive = edited.caseSensitive,
                                        wholeLine = edited.wholeLine,
                                        target = edited.target,
                                        tag = edited.tag,
                                        color = edited.color,
                                        textColor = edited.textColor,
                                        backgroundEnabled = edited.backgroundEnabled,
                                        fontFamily = edited.fontFamily,
                                        bold = edited.bold,
                                        italic = edited.italic,
                                    )
                                }
                                editingId = null
                                onReclaimFocus()
                            },
                            onCancel = { editingId = null; onReclaimFocus() },
                            onDelete = { editingId = null; actions.onRemove(hl.id); onReclaimFocus() },
                        )
                    }
                }
            }
        }
    }
    if (fpState.highlightersExpanded) {
        HighlighterAddForm(
            tab = tab,
            sectionState = sectionState,
            actions = actions,
            sortedTags = sortedTags,
            tagUsage = tagUsage,
            mostUsedTagLimit = mostUsedTagLimit,
            newHlPat = newHlPat,
            newHlRx = newHlRx,
            newHlColor = newHlColor,
            inputFocusRequester = inputFocusRequester,
            onFocusedChange = { focused ->
                addFocused = focused
                onInputFocusedChange(focused || editing != null)
            },
            onTabOut = onTabOut,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordHighlightRow(
    filter: Filter,
    pickerOpen: Boolean,
    onPickerOpenChange: (Boolean) -> Unit,
    actions: HighlighterActions,
) {
    val tc = tc()
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HighlighterColorPicker(
                color = filter.kwHighlightColor,
                onColorChange = { color -> actions.onSetKwHighlightColor(color); onPickerOpenChange(false) },
                customColors = actions.customColors,
                onSaveCustomColor = actions.onSaveCustomColor,
                onDeleteCustomColor = actions.onDeleteCustomColor,
                paletteColumns = actions.paletteColumns,
                onPaletteColumnsChange = actions.onPaletteColumnsChange,
                pickerOpen = pickerOpen,
                onPickerOpenChange = onPickerOpenChange,
                testTagPrefix = "keyword-highlight-color",
                customColorEditorExpanded = actions.customColorEditorExpanded,
                onCustomColorEditorExpandedChange = actions.onCustomColorEditorExpandedChange,
            )
            AppText(
                "/${filter.kwText}/i",
                color = if (filter.kwHighlightEnabled) tc.tx else tc.td,
                fontSize = 11.sp,
                fontFamily = MONO,
                modifier = Modifier.weight(1f),
                overflow = TextOverflow.Ellipsis,
            )
            RoundIndicator(
                active = filter.kwHighlightEnabled,
                color = filter.kwHighlightColor,
                onClick = { actions.onSetKwHighlightEnabled(!filter.kwHighlightEnabled) },
            )
        }
    }
}

// Small "Match" / "Line" chip: the icon and label say what the highlighter paints, a click flips it.
@Composable
private fun HighlightModeChip(wholeLine: Boolean, onClick: () -> Unit) {
    val tc = tc()
    val accent = if (wholeLine) tc.ac else tc.ts
    Row(
        Modifier
            .border(1.dp, if (wholeLine) tc.ac.copy(.6f) else tc.br, CORNER_SM)
            .clip(CORNER_SM)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            if (wholeLine) Icons.Outlined.FormatColorFill else Icons.Outlined.Highlight,
            contentDescription = null,
            modifier = Modifier.size(10.dp),
            tint = accent,
        )
        AppText(if (wholeLine) "Line" else "Match", color = accent, fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HighlighterRow(
    hl: Highlighter,
    countText: String?,
    colorPicker: @Composable () -> Unit,
    editing: Boolean,
    onEdit: () -> Unit,
    onToggleMode: () -> Unit,
    onToggleOn: () -> Unit,
    onRemove: () -> Unit,
) {
    val tc = tc()
    val label = highlighterLabel(hl)
    val badges = highlighterBadges(hl)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        colorPicker()
        // The whole pattern area opens the editor; clicking it again closes it.
        Row(
            Modifier.weight(1f)
                .background(if (editing) tc.hv else Color.Transparent, CORNER_SM)
                .clip(CORNER_SM)
                .clickable(onClick = onEdit),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val textColor = if (hl.on) tc.tx else tc.td
            if (label.scopePrefix != null) {
                AppText(
                    label.scopePrefix.trimEnd(),
                    color = tc.td,
                    fontSize = 11.sp,
                    fontFamily = MONO,
                    modifier = Modifier.widthIn(max = 90.dp),
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FullTextHint(
                (label.scopePrefix ?: "") + label.body,
                modifier = Modifier.weight(1f, fill = false),
            ) { onTextLayout ->
                AppText(
                    label.body,
                    color = textColor,
                    fontSize = 11.sp,
                    fontFamily = MONO,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = onTextLayout,
                )
            }
            badges.forEach { badge ->
                AppText(badge, color = tc.td, fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
            }
        }
        HighlightModeChip(hl.wholeLine, onToggleMode)
        if (countText != null) {
            AppText(countText, color = tc.td, fontSize = 10.sp, fontFamily = MONO, modifier = Modifier.widthIn(min = 20.dp))
        }
        RoundIndicator(active = hl.on, color = hl.color, onClick = onToggleOn)
        SquareIconButton("×", fontSize = 14.sp, onClick = onRemove)
    }
}

// "Match in" / "Only tag" style rows: a fixed-width caption, then the control.
@Composable
private fun EditorRow(caption: String, content: @Composable RowScope.() -> Unit) {
    val tc = tc()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText(caption, color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(52.dp))
        content()
    }
}

private data class HighlighterStyleControlsState(
    val backgroundColor: Color,
    val backgroundEnabled: Boolean,
    val foregroundColor: Color?,
    val bold: Boolean?,
    val italic: Boolean?,
    val fontFamily: String?,
    val backgroundPickerOpen: Boolean,
    val foregroundPickerOpen: Boolean,
)

private data class HighlighterStyleControlActions(
    val onBackgroundColor: (Color) -> Unit,
    val onBackgroundEnabled: (Boolean) -> Unit,
    val onForegroundColor: (Color?) -> Unit,
    val onBold: (Boolean?) -> Unit,
    val onItalic: (Boolean?) -> Unit,
    val onFontFamily: (String?) -> Unit,
    val onBackgroundPickerOpen: (Boolean) -> Unit,
    val onForegroundPickerOpen: (Boolean) -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HighlighterStyleControls(
    state: HighlighterStyleControlsState,
    actions: HighlighterActions,
    onChange: HighlighterStyleControlActions,
    testTagPrefix: String,
) {
    val tc = tc()
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HighlighterStyleColorControl(
            label = "Bg",
            enabled = state.backgroundEnabled,
            color = state.backgroundColor,
            pickerOpen = state.backgroundPickerOpen,
            testTagPrefix = "$testTagPrefix-background",
            customColors = actions.customColors,
            onSaveCustomColor = actions.onSaveCustomColor,
            onDeleteCustomColor = actions.onDeleteCustomColor,
            onColorChange = onChange.onBackgroundColor,
            onPickerOpenChange = onChange.onBackgroundPickerOpen,
            customColorEditorExpanded = actions.customColorEditorExpanded,
            onCustomColorEditorExpandedChange = actions.onCustomColorEditorExpandedChange,
            onToggleEnabled = {
                val enabled = !state.backgroundEnabled
                onChange.onBackgroundEnabled(enabled)
                if (!enabled) onChange.onBackgroundPickerOpen(false)
            },
            onEnableAndOpen = {
                onChange.onBackgroundEnabled(true)
                onChange.onBackgroundPickerOpen(true)
            },
            groupTestTag = "$testTagPrefix-background-group",
            toggleTestTag = "$testTagPrefix-background-toggle",
        )
        HighlighterStyleColorControl(
            label = "Fg",
            enabled = state.foregroundColor != null,
            color = state.foregroundColor ?: contrastingTextColor(state.backgroundColor),
            pickerOpen = state.foregroundPickerOpen,
            testTagPrefix = "$testTagPrefix-foreground",
            customColors = actions.customColors,
            onSaveCustomColor = actions.onSaveCustomColor,
            onDeleteCustomColor = actions.onDeleteCustomColor,
            onColorChange = { onChange.onForegroundColor(it) },
            onPickerOpenChange = onChange.onForegroundPickerOpen,
            customColorEditorExpanded = actions.customColorEditorExpanded,
            onCustomColorEditorExpandedChange = actions.onCustomColorEditorExpandedChange,
            onToggleEnabled = {
                if (state.foregroundColor == null) {
                    onChange.onForegroundColor(contrastingTextColor(state.backgroundColor))
                } else {
                    onChange.onForegroundColor(null)
                    onChange.onForegroundPickerOpen(false)
                }
            },
            onEnableAndOpen = {
                onChange.onForegroundColor(contrastingTextColor(state.backgroundColor))
                onChange.onForegroundPickerOpen(true)
            },
            groupTestTag = "$testTagPrefix-foreground-group",
            toggleTestTag = "$testTagPrefix-foreground-toggle",
        )
        HighlighterStyleCycleButton(
            label = "Bold",
            glyph = "B",
            value = state.bold,
            testTag = "$testTagPrefix-bold-cycle",
            fontWeight = FontWeight.Bold,
            onChange = onChange.onBold,
        )
        HighlighterStyleCycleButton(
            label = "Italic",
            glyph = "I",
            value = state.italic,
            testTag = "$testTagPrefix-italic-cycle",
            fontStyle = FontStyle.Italic,
            onChange = onChange.onItalic,
        )
        SearchableFontDropdown(
            label = null,
            selectedFamily = state.fontFamily,
            defaultLabel = "Log font",
            fallbackFamily = LocalLogFontFamily.current,
            onSelect = onChange.onFontFamily,
            modifier = Modifier.widthIn(min = 88.dp, max = 112.dp),
            testTagPrefix = "$testTagPrefix-font",
            showPreview = false,
        )
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        AppText("Preview", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        AppText(
            "Bold ${styleOverrideName(state.bold)} · Italic ${styleOverrideName(state.italic)}",
            color = tc.td,
            fontSize = 9.sp,
            modifier = Modifier.testTag("$testTagPrefix-style-state"),
        )
    }
}

@Composable
@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)
private fun HighlighterStyleColorControl(
    label: String,
    enabled: Boolean,
    color: Color,
    pickerOpen: Boolean,
    testTagPrefix: String,
    customColors: List<Color>,
    onSaveCustomColor: (Color) -> Unit,
    onDeleteCustomColor: (Color) -> Unit,
    onColorChange: (Color) -> Unit,
    onPickerOpenChange: (Boolean) -> Unit,
    customColorEditorExpanded: Boolean,
    onCustomColorEditorExpandedChange: (Boolean) -> Unit,
    onToggleEnabled: () -> Unit,
    onEnableAndOpen: () -> Unit,
    groupTestTag: String,
    toggleTestTag: String,
) {
    val tc = tc()
    val shape = RoundedCornerShape(6.dp)
    var hovered by remember(groupTestTag) { mutableStateOf(false) }
    val targetName = if (label == "Bg") "Background" else "Text color"
    val tooltipText = when (label to enabled) {
        "Bg" to true -> "Background is on. Click chip to turn off; click swatch to choose color."
        "Bg" to false -> "Background is off. Click chip to turn on; swatch chooses color and turns it on."
        "Fg" to true -> "Text color is on. Click chip to inherit log color; swatch chooses text color."
        else -> "Text color is inherited. Click chip to set it; swatch chooses text color."
    }
    TooltipArea(tooltip = { ToolbarTooltip(tooltipText, maxLines = 3) }) {
        Box(
            Modifier.height(25.dp)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .testTag(groupTestTag),
        ) {
            Row(
                Modifier.height(25.dp).clip(shape)
                    .background(
                        when {
                            hovered -> tc.hv
                            enabled -> tc.ac.copy(alpha = .09f)
                            else -> tc.p2
                        },
                    )
                    .border(1.dp, if (enabled) tc.ac else tc.br, shape),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(width = 24.dp, height = 25.dp)
                        .clickable {
                            if (enabled) onPickerOpenChange(!pickerOpen) else onEnableAndOpen()
                        }
                        .semantics {
                            contentDescription = if (enabled) {
                                "Choose $targetName color"
                            } else {
                                "Enable $targetName and choose a color"
                            }
                            role = Role.Button
                        }
                        .testTag(if (enabled) "$testTagPrefix-swatch-zone" else "$testTagPrefix-enable-swatch"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (enabled) {
                        HighlighterColorPicker(
                            color = color,
                            onColorChange = onColorChange,
                            customColors = customColors,
                            onSaveCustomColor = onSaveCustomColor,
                            onDeleteCustomColor = onDeleteCustomColor,
                            paletteColumns = 10,
                            onPaletteColumnsChange = {},
                            pickerOpen = pickerOpen,
                            onPickerOpenChange = onPickerOpenChange,
                            testTagPrefix = testTagPrefix,
                            customColorEditorExpanded = customColorEditorExpanded,
                            onCustomColorEditorExpandedChange = onCustomColorEditorExpandedChange,
                        )
                    } else {
                        Box(
                            Modifier.size(16.dp).clip(CORNER_SM)
                                .border(1.dp, tc.td.copy(alpha = .65f), CORNER_SM),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppText("·", color = tc.td, fontSize = 11.sp)
                        }
                    }
                }
                Box(
                    Modifier.height(25.dp)
                        .clickable(onClick = onToggleEnabled)
                        .semantics {
                            contentDescription = targetName
                            stateDescription = if (enabled) "On" else "Off"
                            role = Role.Switch
                        }
                        .testTag(toggleTestTag)
                        .padding(start = 2.dp, end = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(label, color = if (enabled) tc.ac else tc.ts, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun HighlighterStyleCycleButton(
    label: String,
    glyph: String,
    value: Boolean?,
    testTag: String,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    onChange: (Boolean?) -> Unit,
) {
    val tc = tc()
    val currentLabel = styleOverrideName(value)
    TooltipArea(
        tooltip = { ToolbarTooltip("$label $currentLabel. Click to cycle Default, On, and Off.") },
    ) {
        Box(
            Modifier.size(25.dp).clip(RoundedCornerShape(6.dp))
                .background(if (value == true) tc.ac.copy(alpha = .12f) else tc.p2)
                .border(1.dp, if (value == true) tc.ac.copy(alpha = .65f) else tc.br, RoundedCornerShape(6.dp))
                .clickable {
                    onChange(when (value) { null -> true; true -> false; false -> null })
                }
                .semantics {
                    contentDescription = "$label $currentLabel. Cycle Default, On, Off."
                    role = Role.Button
                }
                .testTag(testTag),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                glyph,
                style = TextStyle(
                    color = if (value == false) tc.td else tc.tx,
                    fontSize = 12.sp,
                    fontFamily = UI,
                    fontWeight = fontWeight ?: FontWeight.Normal,
                    fontStyle = fontStyle,
                ),
            )
        }
    }
}

private fun styleOverrideName(value: Boolean?): String = when (value) {
    null -> "inherit"
    true -> "on"
    false -> "off"
}

private fun contrastingTextColor(background: Color): Color =
    if (background.luminance() > HIGH_CONTRAST_LUMINANCE_THRESHOLD) Color.Black else Color.White

@Composable
private fun LargeBackgroundColorPreview(color: Color, onClick: () -> Unit) {
    val tc = tc()
    Box(
        Modifier.size(24.dp).clip(CORNER_SM)
            .background(color)
            .border(1.dp, tc.br, CORNER_SM)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "Choose background color"
                role = Role.Button
            }
            .testTag("highlighter-add-color-preview"),
    )
}

@Composable
private fun StylePreview(highlighter: Highlighter) {
    val tc = tc()
    val previewAlpha = if (highlighter.wholeLine) {
        HL_WHOLE_LINE_BACKGROUND_ALPHA
    } else {
        HL_MATCH_BACKGROUND_ALPHA
    }
    val previewBg = when {
        !highlighter.backgroundEnabled -> tc.p2
        highlighter.kloggStyle -> highlighter.color
        else -> highlighter.color.copy(alpha = highlighter.color.alpha * previewAlpha)
    }
    val weight = highlighter.bold?.let { if (it) FontWeight.Bold else FontWeight.Normal }
        ?: if (highlighter.wholeLine || highlighter.kloggStyle) null else FontWeight.SemiBold
    Box(
        Modifier.fillMaxWidth()
            .background(previewBg, RoundedCornerShape(4.dp))
            .border(1.dp, tc.br, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            "Preview matched text",
            style = TextStyle(
                color = highlighter.textColor ?: tc.tx,
                fontFamily = FontCatalog.resolveOrNull(highlighter.fontFamily) ?: LocalLogFontFamily.current,
                fontWeight = weight,
                fontStyle = highlighter.italic?.let { if (it) FontStyle.Italic else FontStyle.Normal },
                fontSize = 11.sp,
            ),
        )
    }
}

private val EDITOR_TARGETS = listOf(HighlightTarget.TAG, HighlightTarget.MESSAGE, HighlightTarget.ANY)

// Inline editor under a highlighter row. Edits a draft; Done / Enter commit it, Esc cancels, Delete
// removes the highlighter. Colours are drafted too, so Esc really does discard everything.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HighlighterEditor(
    tab: LogTab,
    original: Highlighter,
    sortedTags: List<String>,
    actions: HighlighterActions,
    onDone: (Highlighter) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val tc = tc()
    var draft by remember(original.id) { mutableStateOf(original) }
    var tagPickerOpen by remember(original.id) { mutableStateOf(false) }
    var tagSearch by remember(original.id) { mutableStateOf("") }
    var backgroundColorPickerOpen by remember(original.id) { mutableStateOf(false) }
    var foregroundColorPickerOpen by remember(original.id) { mutableStateOf(false) }
    val patternFr = remember { FocusRequester() }
    LaunchedEffect(original.id) { runCatching { patternFr.requestFocus() } }
    val counts = rememberHighlighterRowCounts(tab, listOf(draft))
    val canCommit = draft.pattern.isNotBlank()
    val regexInvalid = draft.regex && draft.pattern.isNotBlank() && !isValidRegexPattern(draft.pattern, !draft.caseSensitive)

    fun commit() {
        if (canCommit) onDone(draft)
    }
    Column(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp)
            .background(tc.p2, CORNER_MD)
            .border(BorderStroke(1.dp, tc.br), CORNER_MD)
            .padding(8.dp)
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                    onCancel()
                    true
                } else {
                    false
                }
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            InlineField(
                draft.pattern,
                { draft = draft.copy(pattern = it) },
                "pattern",
                Modifier.weight(1f)
                    .testTag("highlighter-editor-pattern")
                    .focusRequester(patternFr)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.NumPadEnter)) {
                            commit()
                            true
                        } else {
                            false
                        }
                    },
            )
            PillBtn(".*", active = draft.regex, onClick = { draft = draft.copy(regex = !draft.regex) })
            PillBtn("Aa", active = draft.caseSensitive, onClick = { draft = draft.copy(caseSensitive = !draft.caseSensitive) })
        }
        if (regexInvalid) {
            AppText("Invalid regex, this highlighter matches nothing", color = DANGER_RED, fontSize = 10.sp, fontFamily = UI)
        }
        EditorRow("Match in") {
            SegmentedControl(
                options = listOf("Tag", "Message", "Anywhere"),
                selectedIndices = setOf(EDITOR_TARGETS.indexOf(draft.target)),
                onToggle = { draft = draft.copy(target = EDITOR_TARGETS[it]) },
                modifier = Modifier.weight(1f),
                fillWidth = true,
                segmentHeight = 22.dp,
                segmentFontSize = 10.sp,
                segmentHorizontalPadding = 4.dp,
            )
        }
        EditorRow("Only tag") {
            PillBtn(draft.tag?.takeIf { it.isNotBlank() } ?: "Any tag", active = !draft.tag.isNullOrBlank(), onClick = {
                tagPickerOpen = !tagPickerOpen
                tagSearch = ""
            })
        }
        if (tagPickerOpen) {
            // Only exact tags: a highlighter's tag limit is the same exact-tag rule message rules use,
            // so the package-prefix options of the shared scope list don't apply.
            val options = remember(sortedTags, tagSearch) {
                messageRuleScopeOptions(sortedTags, tagSearch).filter { it.packagePrefix == null }
            }
            InlineField(tagSearch, { tagSearch = it }, "scope tag…", Modifier.fillMaxWidth(), onClear = { tagSearch = "" })
            ScrollableItems(options.size, maxDp = 120) {
                options.forEach { option ->
                    HoverBox(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        baseBg = if (option.tag == draft.tag || (option.isAll && draft.tag.isNullOrBlank())) tc.abg else Color.Transparent,
                        hoverBg = tc.hv,
                        onClick = { draft = draft.copy(tag = option.tag); tagPickerOpen = false },
                    ) {
                        AppText(
                            if (option.isAll) "Any tag" else option.label,
                            color = tc.ts,
                            fontSize = 11.sp,
                            fontFamily = MONO,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        EditorRow("Style") {
            SegmentedControl(
                options = listOf("Match", "Whole line"),
                selectedIndices = setOf(if (draft.wholeLine) 1 else 0),
                onToggle = { draft = draft.copy(wholeLine = it == 1) },
                modifier = Modifier.weight(1f),
                fillWidth = true,
                segmentHeight = 22.dp,
                segmentFontSize = 10.sp,
                segmentHorizontalPadding = 4.dp,
            )
        }
        HighlighterStyleControls(
            state = HighlighterStyleControlsState(
                backgroundColor = draft.color,
                backgroundEnabled = draft.backgroundEnabled,
                foregroundColor = draft.textColor,
                bold = draft.bold,
                italic = draft.italic,
                fontFamily = draft.fontFamily,
                backgroundPickerOpen = backgroundColorPickerOpen,
                foregroundPickerOpen = foregroundColorPickerOpen,
            ),
            actions = actions,
            onChange = HighlighterStyleControlActions(
                onBackgroundColor = { draft = draft.copy(color = it) },
                onBackgroundEnabled = { draft = draft.copy(backgroundEnabled = it) },
                onForegroundColor = { draft = draft.copy(textColor = it) },
                onBold = { draft = draft.copy(bold = it) },
                onItalic = { draft = draft.copy(italic = it) },
                onFontFamily = { draft = draft.copy(fontFamily = it) },
                onBackgroundPickerOpen = { backgroundColorPickerOpen = it },
                onForegroundPickerOpen = { foregroundColorPickerOpen = it },
            ),
            testTagPrefix = "highlighter-editor",
        )
        StylePreview(draft)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val matched = counts.counts[draft.id]
            AppText(
                if (matched == null) "Counting matches…" else "Matches ${formatHighlightCount(matched, counts.capped)} lines",
                color = tc.td,
                fontSize = 10.sp,
                fontFamily = UI,
                modifier = Modifier.weight(1f),
                overflow = TextOverflow.Ellipsis,
            )
            AppButton("Delete", onClick = onDelete, variant = ButtonVariant.Ghost, isDanger = true)
            AppButton(
                "Done",
                onClick = { commit() },
                variant = ButtonVariant.Primary,
                enabled = canCommit,
                modifier = Modifier.testTag("highlighter-editor-done"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun HighlighterAddForm(
    tab: LogTab,
    sectionState: HighlighterSectionState,
    actions: HighlighterActions,
    sortedTags: List<String>,
    tagUsage: Map<String, Int>,
    mostUsedTagLimit: Int,
    newHlPat: String,
    newHlRx: Boolean,
    newHlColor: Color,
    inputFocusRequester: FocusRequester,
    onFocusedChange: (Boolean) -> Unit,
    onTabOut: () -> Unit,
) {
    val tc = tc()
    val filter = tab.filter
    var scopeChip by sectionState.addScopeChip
    val addWholeLine = sectionState.addWholeLine
    val addBackgroundEnabled = sectionState.addBackgroundEnabled
    val addTextColor = sectionState.addTextColor
    val addFontFamily = sectionState.addFontFamily
    val addBold = sectionState.addBold
    val addItalic = sectionState.addItalic
    val addForegroundPickerOpen = sectionState.addForegroundPickerOpen
    var selectedIdx by remember(tab.id) { mutableStateOf(-1) }
    // null = follow the Match text | Whole line control; 0 / 1 = ←/→ picked Match / Line for the row.
    var rowAction by remember(tab.id) { mutableStateOf<Int?>(null) }
    var fieldFocused by remember { mutableStateOf(false) }
    var dropdownHovered by remember { mutableStateOf(false) }
    var showDropdown by remember { mutableStateOf(false) }
    var search by remember(tab.id) { mutableStateOf(newHlPat) }
    LaunchedEffect(newHlPat) {
        if (newHlPat.isBlank()) {
            search = ""
        } else {
            delay(if (tab.largeFileMode) HL_SEARCH_DEBOUNCE_LARGE_MS else HL_SEARCH_DEBOUNCE_MS)
            search = newHlPat
        }
    }
    LaunchedEffect(fieldFocused, dropdownHovered) {
        if (fieldFocused || dropdownHovered) {
            showDropdown = true
        } else {
            delay(100)
            if (!fieldFocused && !dropdownHovered) showDropdown = false
        }
    }

    val query = remember(search, scopeChip, newHlRx) { parseHighlighterQuery(search, scopeChip, newHlRx) }
    val composition = tab.messageComposition
    val templates: List<MessageTemplate> = remember(composition) {
        when (composition) {
            is MessageCompositionState.Computed -> composition.histogram.templates
            is MessageCompositionState.Computing -> composition.previous?.templates.orEmpty()
            else -> emptyList()
        }
    }
    // Message templates come from the Log composition scan; ask for it the first time it is wanted.
    LaunchedEffect(tab.id, query.isBlank, composition is MessageCompositionState.NotComputed) {
        if (!query.isBlank && composition is MessageCompositionState.NotComputed) actions.onRequestMessageComposition()
    }
    val tagCounts = tab.analysis.tagCounts
    val candidates = remember(query, sortedTags, tagCounts, tagUsage, mostUsedTagLimit, templates) {
        highlighterCandidates(query, sortedTags, tagCounts, tagUsage, mostUsedTagLimit, templates)
    }
    val literalCandidate = candidates.firstOrNull { it.group == HighlightCandidateGroup.TEXT }
    val literalCounts = rememberHighlighterRowCounts(tab, listOfNotNull(literalCandidate?.let(::pseudoHighlighter)))

    fun commit(candidate: HighlightCandidate, wholeLine: Boolean) {
        val existing = existingHighlighterFor(filter.highlighters, candidate)
        if (existing != null) {
            // Same shape already listed: switch it to the chosen mode instead of stacking a copy.
            actions.onUpdate(existing.id) { it.copy(wholeLine = wholeLine, on = true) }
        } else {
            actions.onAdd(
                candidate.pattern, candidate.regex, newHlColor, wholeLine, candidate.target, candidate.tag,
                addBackgroundEnabled, addTextColor, addFontFamily, addBold, addItalic,
            )
            actions.onSetNewColor(nextHighlightColor(newHlColor, (filter.highlighters.map { it.color } + newHlColor).toSet()))
        }
        actions.onSetNewPattern("")
        selectedIdx = -1
        rowAction = null
        runCatching { inputFocusRequester.requestFocus() }
    }

    fun clearInput() {
        actions.onSetNewPattern("")
        scopeChip = null
        selectedIdx = -1
        rowAction = null
        showDropdown = false
    }

    // Enter / + Add act on exactly what is in the field right now, not on the debounced dropdown, so
    // a fast "type, then Enter" never adds a stale prefix of the text.
    val typed = remember(newHlPat, scopeChip, newHlRx, tagCounts) {
        val live = parseHighlighterQuery(newHlPat, scopeChip, newHlRx)
        highlighterCandidates(live, emptyList(), tagCounts, tagUsage, mostUsedTagLimit, emptyList()).firstOrNull()
    }
    val canAdd = typed != null
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        scopeChip?.let { chip ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TagPill("tag:$chip", tc.ac) { scopeChip = null; selectedIdx = -1 }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            LargeBackgroundColorPreview(newHlColor) {
                if (!addBackgroundEnabled) sectionState.addBackgroundEnabled = true
                sectionState.addColorPickerOpen = true
            }
            InlineField(
                newHlPat,
                { actions.onSetNewPattern(it); selectedIdx = -1; rowAction = null },
                "text, tag, message or /regex/",
                Modifier.weight(1f).testTag("highlighter-add-pattern")
                    .focusRequester(inputFocusRequester)
                    .onFocusChanged {
                        fieldFocused = it.isFocused
                        onFocusedChange(it.isFocused)
                    }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val selected = candidates.getOrNull(selectedIdx)
                        when (ev.key) {
                            Key.Tab -> {
                                // Tab on an exact tag turns it into a scope chip; anywhere else it keeps its
                                // tab-order job of moving on to the Tags field.
                                val chip = selected?.let(::scopeChipFor)
                                if (chip != null) {
                                    scopeChip = chip
                                    actions.onSetNewPattern("")
                                    selectedIdx = -1
                                    rowAction = null
                                } else {
                                    onTabOut()
                                }
                                true
                            }
                            Key.DirectionDown -> {
                                selectedIdx = (selectedIdx + 1).coerceAtMost(candidates.lastIndex)
                                rowAction = null
                                true
                            }
                            Key.DirectionUp -> {
                                selectedIdx = (selectedIdx - 1).coerceAtLeast(-1)
                                rowAction = null
                                true
                            }
                            Key.DirectionLeft -> if (selected != null) {
                                rowAction = 0
                                true
                            } else {
                                false
                            }
                            Key.DirectionRight -> if (selected != null) {
                                rowAction = 1
                                true
                            } else {
                                false
                            }
                            Key.Escape -> { clearInput(); true }
                            Key.Backspace -> if (newHlPat.isEmpty() && scopeChip != null) {
                                scopeChip = null
                                selectedIdx = -1
                                true
                            } else {
                                false
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                // No row picked: add what was typed.
                                val target = selected ?: typed
                                if (target != null) {
                                    commit(target, highlightModeFor(if (selected != null) rowAction else null, addWholeLine))
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    },
                onClear = { clearInput() },
            )
            PillBtn(".*", active = newHlRx, onClick = { actions.onSetNewRegex(!newHlRx) })
        }
        if (showDropdown && candidates.isNotEmpty()) {
            HighlightCandidateDropdown(
                candidates = candidates,
                highlighters = filter.highlighters,
                literalCount = literalCounts.counts[HL_CANDIDATE_ID]?.let { formatHighlightCount(it, literalCounts.capped) },
                selectedIdx = selectedIdx,
                selectedWholeLine = highlightModeFor(rowAction, addWholeLine),
                onHoverChange = { dropdownHovered = it },
                onPick = { candidate, wholeLine -> commit(candidate, wholeLine) },
                defaultWholeLine = addWholeLine,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            SegmentedControl(
                options = listOf("Match", "Whole line"),
                selectedIndices = setOf(if (addWholeLine) 1 else 0),
                onToggle = { sectionState.addWholeLine = it == 1; rowAction = null },
                modifier = Modifier.weight(1f),
                fillWidth = true,
                segmentHeight = 22.dp,
                segmentFontSize = 10.sp,
                segmentHorizontalPadding = 4.dp,
            )
            AppButton(
                "+ Add",
                onClick = { typed?.let { commit(it, addWholeLine) } },
                variant = ButtonVariant.Primary,
                enabled = canAdd,
                modifier = Modifier.testTag("highlighter-add-submit"),
            )
        }
        HighlighterStyleControls(
            state = HighlighterStyleControlsState(
                backgroundColor = newHlColor,
                backgroundEnabled = addBackgroundEnabled,
                foregroundColor = addTextColor,
                bold = addBold,
                italic = addItalic,
                fontFamily = addFontFamily,
                backgroundPickerOpen = sectionState.addColorPickerOpen,
                foregroundPickerOpen = addForegroundPickerOpen,
            ),
            actions = actions,
            onChange = HighlighterStyleControlActions(
                onBackgroundColor = actions.onSetNewColor,
                onBackgroundEnabled = { sectionState.addBackgroundEnabled = it },
                onForegroundColor = { sectionState.addTextColor = it },
                onBold = { sectionState.addBold = it },
                onItalic = { sectionState.addItalic = it },
                onFontFamily = { sectionState.addFontFamily = it },
                onBackgroundPickerOpen = { sectionState.addColorPickerOpen = it },
                onForegroundPickerOpen = { sectionState.addForegroundPickerOpen = it },
            ),
            testTagPrefix = "highlighter-add",
        )
        StylePreview(
            Highlighter(
                id = "preview", pattern = "", regex = false, color = newHlColor, on = true,
                wholeLine = addWholeLine, textColor = addTextColor, backgroundEnabled = addBackgroundEnabled,
                fontFamily = addFontFamily, bold = addBold, italic = addItalic,
            ),
        )
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun HighlightCandidateDropdown(
    candidates: List<HighlightCandidate>,
    highlighters: List<Highlighter>,
    literalCount: String?,
    selectedIdx: Int,
    selectedWholeLine: Boolean,
    defaultWholeLine: Boolean,
    onHoverChange: (Boolean) -> Unit,
    onPick: (HighlightCandidate, Boolean) -> Unit,
) {
    val tc = tc()
    // Group headers are rows of the same height as candidate rows, so the shared list's
    // scroll-into-view (which assumes one row height) lands on the right row.
    val headerCount = candidates.map { it.group }.distinct().size
    val scrollTo = if (selectedIdx < 0) {
        -1
    } else {
        selectedIdx + candidates.take(selectedIdx + 1).map { it.group }.distinct().size
    }
    Column {
        ScrollableItems(
            candidates.size + headerCount,
            rowDp = HL_DROPDOWN_ROW_DP,
            maxDp = HL_DROPDOWN_MAX_DP,
            scrollToIndex = scrollTo,
            modifier = Modifier
                .onPointerEvent(PointerEventType.Enter) { onHoverChange(true) }
                .onPointerEvent(PointerEventType.Exit) { onHoverChange(false) },
        ) {
            var lastGroup: HighlightCandidateGroup? = null
            candidates.forEachIndexed { idx, candidate ->
                if (candidate.group != lastGroup) {
                    lastGroup = candidate.group
                    Box(Modifier.fillMaxWidth().height(HL_DROPDOWN_ROW_DP.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                        AppText(candidate.group.label, color = tc.td, fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                    }
                }
                val isSelected = idx == selectedIdx
                val existing = existingHighlighterFor(highlighters, candidate)
                val countText = candidate.count?.toString() ?: literalCount
                HoverBox(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    baseBg = if (isSelected) tc.abg else Color.Transparent,
                    hoverBg = tc.hv,
                    onClick = { onPick(candidate, defaultWholeLine) },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(5.dp).background(if (existing != null) tc.ac else tc.td, RoundedCornerShape(50)))
                        FullTextHint(candidate.label, modifier = Modifier.weight(1f), forceShow = isSelected) { onTextLayout ->
                            AppText(
                                candidate.label,
                                color = if (isSelected || existing != null) tc.tx else tc.ts,
                                fontSize = 11.sp,
                                fontFamily = MONO,
                                modifier = Modifier.fillMaxWidth(),
                                overflow = TextOverflow.Ellipsis,
                                onTextLayout = onTextLayout,
                            )
                        }
                        if (countText != null) {
                            AppText(countText, color = tc.td, fontSize = 10.sp, fontFamily = MONO, overflow = TextOverflow.Clip)
                        }
                        // Selected row: the keyboard's pick. Other rows: the mode already applied, if any.
                        PillBtn(
                            "Match",
                            active = if (isSelected) !selectedWholeLine else existing != null && !existing.wholeLine,
                            onClick = { onPick(candidate, false) },
                        )
                        PillBtn(
                            "Line",
                            active = if (isSelected) selectedWholeLine else existing != null && existing.wholeLine,
                            onClick = { onPick(candidate, true) },
                        )
                    }
                }
            }
        }
        AppText(
            "↑↓ pick · ←→ Match / Line · Enter add · Esc close",
            color = tc.td,
            fontSize = 9.sp,
            fontFamily = UI,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The Log composition row's Highlight control: the main part is today's match-only toggle, the ▾
 * offers Match text / Whole line. [appliedWholeLine] is the mode of the highlighter the row
 * already has (null = none), shown as a check in the menu and as the active state of the button.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun HighlightSplitButton(
    appliedWholeLine: Boolean?,
    onToggle: () -> Unit,
    onChooseMode: (wholeLine: Boolean) -> Unit,
) {
    val tc = tc()
    val density = LocalDensity.current
    val active = appliedWholeLine != null
    var menuOpen by remember { mutableStateOf(false) }
    var heightPx by remember { mutableStateOf(0) }
    var mainHovered by remember { mutableStateOf(false) }
    var menuHovered by remember { mutableStateOf(false) }
    val borderColor = if (active) tc.ac else tc.br

    fun fill(hovered: Boolean) = if (active) tc.ac.copy(.15f) else if (hovered) tc.hv else Color.Transparent
    val textColor = if (active) tc.ac else tc.ts
    val leftShape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
    val rightShape = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
    Box {
        Row(Modifier.onSizeChanged { heightPx = it.height }) {
            Box(
                Modifier.border(1.dp, borderColor, leftShape).background(fill(mainHovered), leftShape).clip(leftShape)
                    .clickable(onClick = onToggle)
                    .onPointerEvent(PointerEventType.Enter) { mainHovered = true }
                    .onPointerEvent(PointerEventType.Exit) { mainHovered = false },
            ) {
                AppText("Highlight", color = textColor, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
            }
            Box(
                Modifier.border(1.dp, borderColor, rightShape).background(fill(menuHovered), rightShape).clip(rightShape)
                    .clickable { menuOpen = !menuOpen }
                    .onPointerEvent(PointerEventType.Enter) { menuHovered = true }
                    .onPointerEvent(PointerEventType.Exit) { menuHovered = false },
                contentAlignment = Alignment.Center,
            ) {
                AppText("▾", color = textColor, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            }
        }
        if (menuOpen) {
            // Not focusable, so opening it never steals keyboard focus from the panel (see the
            // Popup/clickable focus note in CLAUDE.md); it closes on a pick or an outside click.
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, heightPx + with(density) { 2.dp.roundToPx() }),
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    Modifier.widthIn(min = 120.dp)
                        .shadow(8.dp, RoundedCornerShape(7.dp))
                        .background(tc.p, RoundedCornerShape(7.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                        .padding(vertical = 4.dp),
                ) {
                    listOf(false to "Match text", true to "Whole line").forEach { (wholeLine, label) ->
                        HoverBox(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { menuOpen = false; onChooseMode(wholeLine) },
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AppText(label, color = tc.tx, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                if (appliedWholeLine == wholeLine) AppText("✓", color = tc.ac, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
