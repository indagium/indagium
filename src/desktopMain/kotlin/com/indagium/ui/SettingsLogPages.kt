package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

// Settings › Log view and Settings › Search & filters: rows of switches, steppers and dropdowns in titled groups. Issue categories and
// Source code are in SettingsSourcePages.kt.

private val SELECTOR_WIDTH = 168.dp
private val WIDE_SELECTOR_WIDTH = 200.dp

private val MOST_USED_TAG_LIMITS = listOf(0, 3, 5, 10, 20)
private val FILTER_LIST_ROW_LIMITS = listOf(3, 5, 8, 10, 15)
private val SCROLL_MARGINS = listOf(0, 2, 3, 5, 8, 12)

/** Test tags of the Log view and Search & filters controls that have no tag of their own. */
internal object LogPageTags {
    const val ROW_NUMBERS = "setting-row-numbers"
    const val PROCESS_NAMES = "setting-process-names-new-tabs"
    const val CRASH_ROWS = "setting-crash-rows"
    const val OPEN_WITH_ORIGINAL = "setting-open-with-original"
    const val MINIMAP = "setting-minimap"
    const val FOLLOW_LIVE = "setting-follow-live-logs"
    const val ALSO_SHOW_ORIGINAL = "setting-ctrl-f-also-shows-original"
    const val LINK_SPLIT = "setting-link-split-selection"
    const val REGEX_SUMMARY = "setting-regex-summary"
    const val MOST_USED_TAGS = "setting-most-used-tags"
    const val LIST_HEIGHT = "setting-filter-list-rows"
    const val SCROLL_MARGIN = "setting-keyboard-scroll-margin"
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
        SettingsRow(
            "Row numbers",
            "A gutter with each row's original number. It stays put when you filter or fold rows.",
            anchor = "Row number",
        ) {
            SettingsToggle(
                checked = settings.showRowNumbers,
                label = "Row numbers",
                onChange = { next -> state.updateSettings { it.copy(showRowNumbers = next) } },
                reclaimFocus = reclaimFocus,
                testTag = LogPageTags.ROW_NUMBERS,
            )
        }
        // AWT has no horizontal mouse-wheel axis, so Compose Desktop only produces a horizontal scroll delta while Shift is held
        // (ui/LinuxHorizontalScroll.kt bridges a Linux trackpad's two-finger swipe); hence the Shift tip.
        SettingsRow(
            "Row wrapping",
            "Auto fits long lines to the panel. Fixed wraps at a column and scrolls sideways; hold Shift while scrolling if a trackpad " +
                "swipe doesn't scroll sideways.",
        ) {
            RowWrapControl(
                auto = settings.autoLogRowWrap,
                wrapChars = settings.logRowWrapLimitChars,
                onToggleAuto = { state.updateSettings { it.copy(autoLogRowWrap = !it.autoLogRowWrap) } },
                onWrapCharsChange = { limit -> state.updateSettings { it.copy(logRowWrapLimitChars = limit) } },
            )
        }
        SettingsRow(
            "Process names in new tabs",
            "New tabs show names from “Start proc” lines instead of PIDs. Each tab can still switch on its own, but its pick resets every session.",
        ) {
            SettingsToggle(
                checked = settings.showProcessNamesInNewTabs,
                label = "Process names in new tabs",
                onChange = { next -> state.updateSettings { it.copy(showProcessNamesInNewTabs = next) } },
                reclaimFocus = reclaimFocus,
                testTag = LogPageTags.PROCESS_NAMES,
            )
        }
    }
    SettingsGroup("Highlights") {
        SettingsRow("Crash rows", "Colour every row of an expanded crash group, not just its header.") {
            SettingsToggle(
                checked = settings.highlightEntireCrashGroup,
                label = "Crash rows",
                onChange = { next -> state.updateSettings { it.copy(highlightEntireCrashGroup = next) } },
                reclaimFocus = reclaimFocus,
                testTag = LogPageTags.CRASH_ROWS,
            )
        }
        SettingsRow(
            "Error rows",
            "Tint every Error-level row in every tab. A tab with its own style keeps it until you reset it.",
        ) {
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
        SettingsRow(
            "Open files with Original",
            "New files start with the unfiltered Original panel visible.",
            anchor = "Original panel",
        ) {
            SettingsToggle(
                checked = settings.openNewFilesWithUnfiltered,
                label = "Open files with Original",
                onChange = { next -> state.updateSettings { it.copy(openNewFilesWithUnfiltered = next) } },
                reclaimFocus = reclaimFocus,
                testTag = LogPageTags.OPEN_WITH_ORIGINAL,
            )
        }
        SettingsRow("Minimap", "Replace the scrollbar with a miniature of the file, coloured by level. Click it to jump.") {
            SettingsToggle(
                checked = settings.showMinimap,
                label = "Minimap",
                onChange = { next -> state.updateSettings { it.copy(showMinimap = next) } },
                reclaimFocus = reclaimFocus,
                testTag = LogPageTags.MINIMAP,
            )
        }
        SettingsRow(
            "Follow live logs",
            "Stay on the newest line while live-watching. Scrolling up pauses, scrolling to the end resumes.",
        ) {
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

/** Settings › Search & filters: what Ctrl/Cmd+F does and how Find behaves in split views, the filter panel's lists and keyboard navigation. */
@Composable
internal fun SearchFiltersSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    val settings = state.settings
    SettingsGroup("Ctrl/Cmd+F") {
        SettingsRow(
            "Opens",
            "Find highlights matches and hides nothing. Tags and Regex focus that filter and hide other rows.",
            anchor = "Ctrl/Cmd+F opens",
        ) {
            Box(Modifier.width(SELECTOR_WIDTH)) {
                CtrlFTargetSelector(
                    selected = settings.ctrlFTarget,
                    onSelected = { target -> state.updateSettings { it.copy(ctrlFTarget = target) } },
                    onInteraction = reclaimFocus,
                )
            }
        }
        SettingsRow(
            "Find searches",
            "Which field Find opens in. Ctrl/Cmd+Alt+F always opens Unfiltered, and compare mode uses Filtered.",
            anchor = "Search scope behaviour",
        ) {
            Box(Modifier.width(WIDE_SELECTOR_WIDTH)) {
                SearchScopeBehaviorSelector(
                    selected = settings.searchScopeBehavior,
                    onSelected = { behavior -> state.updateSettings { it.copy(searchScopeBehavior = behavior) } },
                    onInteraction = reclaimFocus,
                )
            }
        }
        SettingsRow(
            "Also show Original",
            "Reveal the unfiltered Original panel whenever Ctrl/Cmd+F opens. Single-tab view only.",
            anchor = "Ctrl/Cmd+F also shows Original",
        ) {
            SettingsToggle(
                checked = settings.openUnfilteredOnCtrlF,
                label = "Also show Original",
                onChange = { next -> state.updateSettings { it.copy(openUnfilteredOnCtrlF = next) } },
                reclaimFocus = reclaimFocus,
                testTag = LogPageTags.ALSO_SHOW_ORIGINAL,
            )
        }
        SettingsRow(
            "Link split selection and Find",
            "Clicking a filtered row selects and scrolls to it in Original too, and Find can mirror a match. Off keeps each panel local.",
        ) {
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
        SettingsRow("Most-used tags", "How many frequent tags are offered as quick picks.") {
            SettingsStepper(
                options = MOST_USED_TAG_LIMITS,
                value = settings.mostUsedTagLimit,
                onChange = { v -> state.updateSettings { it.copy(mostUsedTagLimit = v) } },
                modifier = Modifier.testTag(LogPageTags.MOST_USED_TAGS),
            )
        }
        SettingsRow("List height", "Visible rows in the filter panel's lists before they scroll.", anchor = "Filter list rows") {
            SettingsStepper(
                options = FILTER_LIST_ROW_LIMITS,
                value = settings.filterListRows,
                onChange = { v -> state.updateSettings { it.copy(filterListRows = v) } },
                modifier = Modifier.testTag(LogPageTags.LIST_HEIGHT),
            )
        }
        SettingsRow(
            "Regex summary",
            "Show the Tags-mode selectors above the Regex field. For reference only; it doesn't filter.",
        ) {
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
        SettingsRow(
            "Scroll margin",
            "Rows kept visible above and below the selection when moving with the arrow keys.",
            anchor = "Keyboard scroll margin",
        ) {
            SettingsStepper(
                options = SCROLL_MARGINS,
                value = settings.navScrollMargin,
                onChange = { v -> state.updateSettings { it.copy(navScrollMargin = v) } },
                modifier = Modifier.testTag(LogPageTags.SCROLL_MARGIN),
            )
        }
    }
}
