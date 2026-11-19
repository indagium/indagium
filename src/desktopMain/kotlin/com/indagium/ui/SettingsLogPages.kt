package com.indagium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.indagium.model.RecordFieldNameColor
import com.indagium.model.RecordValueAlignment

// Settings › Log view and Settings › Search & filters: rows of switches, steppers and dropdowns in titled groups. Issue categories and
// Source code are in SettingsSourcePages.kt.

// The row descriptions the search index shares (SettingsSearchIndex.kt), so a result reads exactly like the row it points at.
internal const val LOG_ROW_NUMBERS_HINT = "A gutter with each row's original number. It stays put when you filter or fold rows."
internal const val LOG_ROW_WRAPPING_HINT = "Auto fits long lines to the panel. Fixed wraps at a column and scrolls sideways."
internal const val LOG_ROW_WRAPPING_DETAIL = LOG_ROW_WRAPPING_HINT + " Hold Shift while scrolling if a trackpad swipe doesn't scroll sideways."
internal const val LOG_WRAP_AT_HINT = "The column Fixed wrapping breaks lines at, from 80 to 20,000."
internal const val LOG_PROCESS_NAMES_HINT = "New tabs show names from “Start proc” lines instead of PIDs. A tab's own pick resets every session."
internal const val LOG_RECORD_FIELD_NAMES_HINT = "Colour of field names when a JSON record is expanded and in Message details."
internal const val LOG_RECORD_VALUES_HINT = "Where a field's value starts in an expanded JSON record and in Message details."
internal const val LOG_CRASH_ROWS_HINT = "Colour every row of an expanded crash group, not just its header."
internal const val LOG_ERROR_ROWS_HINT = "Tint every Error-level row in every tab. A tab with its own style keeps it until you reset it."
internal const val LOG_OPEN_WITH_ORIGINAL_HINT = "New files start with the unfiltered Original panel visible."
internal const val LOG_COMPOSITION_STARTUP_LABEL = "Log composition on startup"
internal const val LOG_COMPOSITION_STARTUP_HINT = "Closed waits for you to expand the tile before scanning. Last state restores the tile as you left it."
internal const val LOG_MINIMAP_HINT = "Replace the scrollbar with a miniature of the file, coloured by level. Click it to jump."
internal const val LOG_FOLLOW_LIVE_HINT = "Stay on the newest line while live-watching. Scrolling up pauses, scrolling to the end resumes."
internal const val SEARCH_OPENS_HINT = "Find highlights matches and hides nothing. Tags and Regex focus that filter and hide other rows."
internal const val SEARCH_SCOPE_HINT = "Which field Find opens in. Ctrl/Cmd+Alt+F always opens Unfiltered, and compare mode uses Filtered."
internal const val SEARCH_ALSO_ORIGINAL_HINT = "Reveal the unfiltered Original panel whenever Ctrl/Cmd+F opens. Single-tab view only."
internal const val SEARCH_LINK_SPLIT_HINT =
    "Clicking a filtered row selects and scrolls to it in Original too, and Find can mirror a match. Off keeps each panel local."
internal const val SEARCH_MOST_USED_TAGS_HINT = "How many frequent tags are offered as quick picks."
internal const val SEARCH_LIST_HEIGHT_HINT = "Visible rows in the filter panel's lists before they scroll."
internal const val SEARCH_REGEX_SUMMARY_HINT = "Show the Tags-mode selectors above the Regex field. For reference only; it doesn't filter."
internal const val SEARCH_SCROLL_MARGIN_HINT = "Rows kept visible above and below the selection when moving with the arrow keys."

private const val WRAP_MIN_COLUMNS = 80
private const val WRAP_MAX_COLUMNS = 20_000
private const val WRAP_COLUMNS_DIGITS = 5

private val MOST_USED_TAG_LIMITS = listOf(0, 3, 5, 10, 20)
private val FILTER_LIST_ROW_LIMITS = listOf(3, 5, 8, 10, 15)
private val SCROLL_MARGINS = listOf(0, 2, 3, 5, 8, 12)

/** Test tags of the Log view and Search & filters controls that have no tag of their own. */
internal object LogPageTags {
    const val ROW_NUMBERS = "setting-row-numbers"
    const val PROCESS_NAMES = "setting-process-names-new-tabs"
    const val RECORD_FIELD_NAMES = "setting-record-field-names"
    const val RECORD_FIELD_NAMES_COLOR = "setting-record-field-names-color"
    const val RECORD_VALUE_ALIGNMENT = "setting-record-value-alignment"
    const val CRASH_ROWS = "setting-crash-rows"
    const val OPEN_WITH_ORIGINAL = "setting-open-with-original"
    const val LOG_COMPOSITION_STARTUP = "setting-log-composition-startup"
    const val MINIMAP = "setting-minimap"
    const val FOLLOW_LIVE = "setting-follow-live-logs"
    const val ALSO_SHOW_ORIGINAL = "setting-ctrl-f-also-shows-original"
    const val LINK_SPLIT = "setting-link-split-selection"
    const val REGEX_SUMMARY = "setting-regex-summary"
    const val MOST_USED_TAGS = "setting-most-used-tags"
    const val LIST_HEIGHT = "setting-filter-list-rows"
    const val SCROLL_MARGIN = "setting-keyboard-scroll-margin"
    const val ROW_WRAP_MODE = "setting-row-wrap-mode"
    const val ROW_WRAP_COLUMNS = "setting-row-wrap-columns"
}

/**
 * A [SettingsSwitch] that gives the keyboard focus its click took back to the page through [reclaimFocus], so Cmd/Ctrl+F and Esc keep
 * working after a click. [onChange] runs first.
 */
@Composable
internal fun SettingsToggle(
    checked: Boolean,
    label: String,
    onChange: (Boolean) -> Unit,
    reclaimFocus: () -> Unit,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    SettingsSwitch(
        checked = checked,
        onCheckedChange = { next ->
            onChange(next)
            reclaimFocus()
        },
        label = label,
        enabled = enabled,
        testTag = testTag,
    )
}

/** Settings › Log view: how rows look, how Error and crash rows are tinted, and the panels and live-log behaviour. */
@Composable
internal fun LogViewSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    val settings = state.settings
    val stylePalette = remember(state, settings.highlighterCustomColors, settings.highlighterCustomColorEditorExpanded) {
        HighlighterStylePaletteActions(
            customColors = customHighlightColors(settings.highlighterCustomColors),
            onSaveCustomColor = { color ->
                val hex = highlightColorHex(color)
                state.updateSettings { current ->
                    val saved = customHighlightColors(current.highlighterCustomColors)
                    if (hex in saved.map(::highlightColorHex) || saved.size >= MAX_CUSTOM_HIGHLIGHT_COLORS) current
                    else current.copy(highlighterCustomColors = (saved.map(::highlightColorHex) + hex).take(MAX_CUSTOM_HIGHLIGHT_COLORS))
                }
            },
            onDeleteCustomColor = { color ->
                state.updateSettings { current ->
                    current.copy(highlighterCustomColors = current.highlighterCustomColors.filterNot { parseHighlightHex(it) == color })
                }
            },
            customColorEditorExpanded = settings.highlighterCustomColorEditorExpanded,
            onCustomColorEditorExpandedChange = { expanded ->
                state.updateSettings { it.copy(highlighterCustomColorEditorExpanded = expanded) }
            },
        )
    }
    var errorStyleExpanded by remember { mutableStateOf(false) }

    SettingsGroup("Rows") {
        SettingsRow("Row numbers", LOG_ROW_NUMBERS_HINT) {
            SettingsToggle(
                checked = settings.showRowNumbers,
                label = "Row numbers",
                onChange = { next -> state.updateSettings { it.copy(showRowNumbers = next) } },
                reclaimFocus = reclaimFocus,
                testTag = LogPageTags.ROW_NUMBERS,
            )
        }
        // AWT has no horizontal mouse-wheel axis, so Compose Desktop only produces a horizontal scroll delta while Shift is held
        // (ui/LinuxHorizontalScroll.kt bridges a Linux trackpad's two-finger swipe); hence the Shift tip in the hover hint.
        SettingsRow("Row wrapping", LOG_ROW_WRAPPING_HINT, detail = LOG_ROW_WRAPPING_DETAIL) {
            SettingsSegmented(
                options = listOf("Auto", "Fixed"),
                selectedIndices = setOf(if (settings.autoLogRowWrap) 0 else 1),
                onToggle = { index ->
                    val auto = index == 0
                    if (auto != settings.autoLogRowWrap) state.updateSettings { it.copy(autoLogRowWrap = auto) }
                    reclaimFocus()
                },
                modifier = Modifier.testTag(LogPageTags.ROW_WRAP_MODE),
            )
        }
        WrapColumnsRow(state)
        SettingsRow("Process names in new tabs", LOG_PROCESS_NAMES_HINT) {
            SettingsToggle(
                checked = settings.showProcessNamesInNewTabs,
                label = "Process names in new tabs",
                onChange = { next -> state.updateSettings { it.copy(showProcessNamesInNewTabs = next) } },
                reclaimFocus = reclaimFocus,
                testTag = LogPageTags.PROCESS_NAMES,
            )
        }
        RecordFieldNamesRow(state, stylePalette, reclaimFocus)
        RecordValuesRow(state, reclaimFocus)
    }
    SettingsGroup("Highlights") {
        SettingsRow("Crash rows", LOG_CRASH_ROWS_HINT) {
            SettingsToggle(
                checked = settings.highlightEntireCrashGroup,
                label = "Crash rows",
                onChange = { next -> state.updateSettings { it.copy(highlightEntireCrashGroup = next) } },
                reclaimFocus = reclaimFocus,
                testTag = LogPageTags.CRASH_ROWS,
            )
        }
        SettingsRow("Error rows", LOG_ERROR_ROWS_HINT) {
            ErrorRowsSettingControl(
                style = settings.errorHighlightStyle,
                styleExpanded = errorStyleExpanded,
                onStyleExpandedChange = { errorStyleExpanded = it },
                onChange = { style -> state.updateSettings { it.copy(errorHighlightStyle = style) } },
                palette = stylePalette,
                tabOverrideCount = state.errorHighlightOverrideCount,
                onResetTabOverrides = state::resetErrorHighlightOverrides,
                onStyleClosed = reclaimFocus,
            )
        }
    }
    SettingsGroup("Panels and live logs") {
        SettingsRow("Open files with Original", LOG_OPEN_WITH_ORIGINAL_HINT, anchor = "Original panel") {
            SettingsToggle(
                checked = settings.openNewFilesWithUnfiltered,
                label = "Open files with Original",
                onChange = { next -> state.updateSettings { it.copy(openNewFilesWithUnfiltered = next) } },
                reclaimFocus = reclaimFocus,
                testTag = LogPageTags.OPEN_WITH_ORIGINAL,
            )
        }
        SettingsRow(LOG_COMPOSITION_STARTUP_LABEL, LOG_COMPOSITION_STARTUP_HINT) {
            SettingsSegmented(
                options = listOf("Closed", "Last state"),
                selectedIndices = setOf(if (settings.restoreLogCompositionOnStartup) 1 else 0),
                onToggle = { index ->
                    val restore = index == 1
                    if (restore != settings.restoreLogCompositionOnStartup) {
                        state.updateSettings { it.copy(restoreLogCompositionOnStartup = restore) }
                    }
                    reclaimFocus()
                },
                modifier = Modifier.testTag(LogPageTags.LOG_COMPOSITION_STARTUP),
            )
        }
        SettingsRow("Minimap", LOG_MINIMAP_HINT) {
            SettingsToggle(
                checked = settings.showMinimap,
                label = "Minimap",
                onChange = { next -> state.updateSettings { it.copy(showMinimap = next) } },
                reclaimFocus = reclaimFocus,
                testTag = LogPageTags.MINIMAP,
            )
        }
        SettingsRow("Follow live logs", LOG_FOLLOW_LIVE_HINT) {
            SettingsToggle(
                checked = settings.autoScrollWhileTailing,
                label = "Follow live logs",
                onChange = { next -> state.updateSettings { it.copy(autoScrollWhileTailing = next) } },
                reclaimFocus = reclaimFocus,
                testTag = LogPageTags.FOLLOW_LIVE,
            )
        }
    }
}

/** "Record field names": Accent, Plain or Custom, and the colour picker while Custom is chosen. */
@Composable
private fun RecordFieldNamesRow(state: AppState, palette: HighlighterStylePaletteActions, reclaimFocus: () -> Unit) {
    val settings = state.settings
    val accent = tc().ac
    var pickerOpen by remember { mutableStateOf(false) }
    val choices = RecordFieldNameColor.entries
    SettingsRow("Record field names", LOG_RECORD_FIELD_NAMES_HINT) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (settings.recordFieldNameColor == RecordFieldNameColor.CUSTOM) {
                HighlighterColorPicker(
                    color = settings.recordFieldNameCustomArgb?.let { Color(it.toInt()) } ?: accent,
                    onColorChange = { color ->
                        state.updateSettings { it.copy(recordFieldNameCustomArgb = color.toArgb().toLong() and ARGB_MASK) }
                    },
                    customColors = palette.customColors,
                    onSaveCustomColor = palette.onSaveCustomColor,
                    onDeleteCustomColor = palette.onDeleteCustomColor,
                    paletteColumns = settings.highlighterPaletteColumns,
                    onPaletteColumnsChange = {},
                    pickerOpen = pickerOpen,
                    onPickerOpenChange = {
                        pickerOpen = it
                        if (!it) reclaimFocus()
                    },
                    testTagPrefix = LogPageTags.RECORD_FIELD_NAMES_COLOR,
                    customColorEditorExpanded = palette.customColorEditorExpanded,
                    onCustomColorEditorExpandedChange = palette.onCustomColorEditorExpandedChange,
                )
            }
            SettingsSegmented(
                options = choices.map { it.label },
                selectedIndices = setOf(choices.indexOf(settings.recordFieldNameColor)),
                onToggle = { index ->
                    val choice = choices[index]
                    if (choice != settings.recordFieldNameColor) {
                        state.updateSettings { current ->
                            current.copy(
                                recordFieldNameColor = choice,
                                // A first Custom starts from the accent, so the swatch shows what the names look like now.
                                recordFieldNameCustomArgb = current.recordFieldNameCustomArgb
                                    ?: if (choice == RecordFieldNameColor.CUSTOM) accent.toArgb().toLong() and ARGB_MASK else null,
                            )
                        }
                    }
                    if (choice != RecordFieldNameColor.CUSTOM) pickerOpen = false
                    reclaimFocus()
                },
                modifier = Modifier.testTag(LogPageTags.RECORD_FIELD_NAMES),
                segmentModifier = { index -> Modifier.testTag("${LogPageTags.RECORD_FIELD_NAMES}-${choices[index].name.lowercase()}") },
            )
        }
    }
}

/** "Record values": the value starts right after its field name, or in one column per tree. */
@Composable
private fun RecordValuesRow(state: AppState, reclaimFocus: () -> Unit) {
    val choices = RecordValueAlignment.entries
    SettingsRow("Record values", LOG_RECORD_VALUES_HINT) {
        SettingsSegmented(
            options = choices.map { it.label },
            selectedIndices = setOf(choices.indexOf(state.settings.recordValueAlignment)),
            onToggle = { index ->
                val choice = choices[index]
                if (choice != state.settings.recordValueAlignment) state.updateSettings { it.copy(recordValueAlignment = choice) }
                reclaimFocus()
            },
            modifier = Modifier.testTag(LogPageTags.RECORD_VALUE_ALIGNMENT),
            segmentModifier = { index -> Modifier.testTag("${LogPageTags.RECORD_VALUE_ALIGNMENT}-${choices[index].name.lowercase().replace('_', '-')}") },
        )
    }
}

private const val ARGB_MASK = 0xFFFFFFFFL

/** Settings › Search & filters: what Ctrl/Cmd+F does and how Find behaves in split views, the filter panel's lists and keyboard navigation. */
@Composable
internal fun SearchFiltersSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    val settings = state.settings
    SettingsGroup("Ctrl/Cmd+F") {
        SettingsRow("Opens", SEARCH_OPENS_HINT, anchor = "Ctrl/Cmd+F opens") {
            CtrlFTargetSelector(
                selected = settings.ctrlFTarget,
                onSelected = { target -> state.updateSettings { it.copy(ctrlFTarget = target) } },
                onInteraction = reclaimFocus,
            )
        }
        SettingsRow("Find searches", SEARCH_SCOPE_HINT, anchor = "Search scope behaviour") {
            Box(Modifier.width(SETTINGS_FIELD_SHORT)) {
                SearchScopeBehaviorSelector(
                    selected = settings.searchScopeBehavior,
                    onSelected = { behavior -> state.updateSettings { it.copy(searchScopeBehavior = behavior) } },
                    onInteraction = reclaimFocus,
                )
            }
        }
        SettingsRow("Also show Original", SEARCH_ALSO_ORIGINAL_HINT, anchor = "Ctrl/Cmd+F also shows Original") {
            SettingsToggle(
                checked = settings.openUnfilteredOnCtrlF,
                label = "Also show Original",
                onChange = { next -> state.updateSettings { it.copy(openUnfilteredOnCtrlF = next) } },
                reclaimFocus = reclaimFocus,
                testTag = LogPageTags.ALSO_SHOW_ORIGINAL,
            )
        }
        SettingsRow("Link split selection and Find", SEARCH_LINK_SPLIT_HINT) {
            SettingsToggle(
                checked = settings.syncSplitPanelSelectionAndScroll,
                label = "Link split selection and Find",
                onChange = { next -> state.updateSettings { it.copy(syncSplitPanelSelectionAndScroll = next) } },
                reclaimFocus = reclaimFocus,
                testTag = LogPageTags.LINK_SPLIT,
            )
        }
    }
    SettingsGroup("Filter panel") {
        SettingsRow("Most-used tags", SEARCH_MOST_USED_TAGS_HINT) {
            SettingsStepper(
                options = MOST_USED_TAG_LIMITS,
                value = settings.mostUsedTagLimit,
                onChange = { v -> state.updateSettings { it.copy(mostUsedTagLimit = v) } },
                modifier = Modifier.testTag(LogPageTags.MOST_USED_TAGS),
            )
        }
        SettingsRow("List height", SEARCH_LIST_HEIGHT_HINT, anchor = "Filter list rows") {
            SettingsStepper(
                options = FILTER_LIST_ROW_LIMITS,
                value = settings.filterListRows,
                onChange = { v -> state.updateSettings { it.copy(filterListRows = v) } },
                modifier = Modifier.testTag(LogPageTags.LIST_HEIGHT),
            )
        }
        SettingsRow("Regex summary", SEARCH_REGEX_SUMMARY_HINT) {
            SettingsToggle(
                checked = settings.showRegexFilterSummary,
                label = "Regex summary",
                onChange = { next -> state.updateSettings { it.copy(showRegexFilterSummary = next) } },
                reclaimFocus = reclaimFocus,
                testTag = LogPageTags.REGEX_SUMMARY,
            )
        }
    }
    SettingsGroup("Keyboard") {
        SettingsRow("Scroll margin", SEARCH_SCROLL_MARGIN_HINT, anchor = "Keyboard scroll margin") {
            SettingsStepper(
                options = SCROLL_MARGINS,
                value = settings.navScrollMargin,
                onChange = { v -> state.updateSettings { it.copy(navScrollMargin = v) } },
                modifier = Modifier.testTag(LogPageTags.SCROLL_MARGIN),
            )
        }
    }
}

/** "Wrap at": the column Fixed wrapping breaks at. Dependent on the Row wrapping choice: dimmed and locked while it is Auto, the value is kept. */
@Composable
private fun WrapColumnsRow(state: AppState) {
    val settings = state.settings
    var text by remember(settings.logRowWrapLimitChars) { mutableStateOf(settings.logRowWrapLimitChars.toString()) }
    SettingsRow("Wrap at", LOG_WRAP_AT_HINT, indent = true, enabled = !settings.autoLogRowWrap) {
        SettingsNumberField(
            value = text,
            onValueChange = { digits ->
                text = digits
                digits.toIntOrNull()?.let { limit ->
                    state.updateSettings { it.copy(logRowWrapLimitChars = limit.coerceIn(WRAP_MIN_COLUMNS, WRAP_MAX_COLUMNS)) }
                }
            },
            unit = "columns",
            maxDigits = WRAP_COLUMNS_DIGITS,
            testTag = LogPageTags.ROW_WRAP_COLUMNS,
        )
    }
}
