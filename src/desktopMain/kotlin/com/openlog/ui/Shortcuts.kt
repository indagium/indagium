package com.openlog.ui

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed

val isMacOs: Boolean = System.getProperty("os.name").lowercase().startsWith("mac")

val KeyEvent.isActionKey: Boolean get() = if (isMacOs) isMetaPressed else isCtrlPressed

enum class KeyboardPanel {
    GLOBAL,
    PANEL_NAVIGATION,
    LOG_VIEW,
    FILTERS,
    NOTES,
    POPUPS,
}

data class ShortcutSpec(
    val macLabel: String,
    val otherLabel: String,
    val description: String,
    val panel: KeyboardPanel,
) {
    fun label(mac: Boolean = isMacOs): String = if (mac) macLabel else otherLabel
}

data class ShortcutHelpRow(
    val label: String,
    val description: String,
    val panel: KeyboardPanel,
)

data class ShortcutHelpGroup(
    val title: String,
    val rows: List<ShortcutHelpRow>,
)

data class RovingItem(
    val id: String,
    val enabled: Boolean = true,
)

enum class KeyboardTargetKind {
    FilterModeTags,
    FilterModeRegex,
    FilterTagInput,
    FilterMessageInput,
    FilterHighlighterInput,
    FilterSection,
    FilterLogLevel,
    FilterSequence,
    FilterManualCollapse,
    FilterNewSequence,
    FilterSavedFilter,
    FilterSaveCurrent,
    FilterClearFilters,
    FilterExportFilters,
    FilterImportFilters,
    NotePreview,
    NoteCopy,
    NoteSave,
    NoteOpen,
    NoteRecentNotes,
    NotePrefix,
    NoteBlock,
    NoteAddTextBlock,
    NoteSuffix,
}

data class KeyboardTarget(
    val id: String,
    val kind: KeyboardTargetKind,
    val enabled: Boolean = true,
) {
    fun asRovingItem(): RovingItem = RovingItem(id, enabled)
}

fun rovingMove(
    items: List<RovingItem>,
    currentIndex: Int,
    delta: Int,
    wrap: Boolean = false,
): Int {
    if (items.isEmpty() || delta == 0) return currentIndex.coerceInOrZero(items)
    val enabledIndices = items.indices.filter { items[it].enabled }
    if (enabledIndices.isEmpty()) return currentIndex.coerceInOrZero(items)
    val start = currentIndex.coerceInOrZero(items)
    var candidate = start + delta.sign()
    while (candidate in items.indices) {
        if (items[candidate].enabled) return candidate
        candidate += delta.sign()
    }
    if (!wrap) return if (items[start].enabled) start else enabledIndices.first()
    return if (delta > 0) enabledIndices.first() else enabledIndices.last()
}

fun rovingActivationId(items: List<RovingItem>, index: Int): String? =
    items.getOrNull(index)?.takeIf { it.enabled }?.id

fun filterKeyboardTargets(
    levelCount: Int,
    sequenceIds: List<String>,
    manualCollapseIds: List<String>,
    savedFilterIds: List<String>,
): List<KeyboardTarget> = buildList {
    add(KeyboardTarget("filter-mode-tags", KeyboardTargetKind.FilterModeTags))
    add(KeyboardTarget("filter-mode-regex", KeyboardTargetKind.FilterModeRegex))
    add(KeyboardTarget("filter-tag-input", KeyboardTargetKind.FilterTagInput))
    add(KeyboardTarget("filter-message-input", KeyboardTargetKind.FilterMessageInput))
    add(KeyboardTarget("filter-highlighter-input", KeyboardTargetKind.FilterHighlighterInput))
    add(KeyboardTarget("filter-section-levels", KeyboardTargetKind.FilterSection))
    repeat(levelCount) { idx -> add(KeyboardTarget("level-$idx", KeyboardTargetKind.FilterLogLevel)) }
    add(KeyboardTarget("filter-section-sequences", KeyboardTargetKind.FilterSection))
    sequenceIds.forEach { id -> add(KeyboardTarget("sequence:$id", KeyboardTargetKind.FilterSequence)) }
    manualCollapseIds.forEach { id -> add(KeyboardTarget("manual:$id", KeyboardTargetKind.FilterManualCollapse)) }
    add(KeyboardTarget("new-sequence", KeyboardTargetKind.FilterNewSequence))
    add(KeyboardTarget("filter-section-saved", KeyboardTargetKind.FilterSection))
    savedFilterIds.forEach { id -> add(KeyboardTarget("saved:$id", KeyboardTargetKind.FilterSavedFilter)) }
    add(KeyboardTarget("save-current-filter", KeyboardTargetKind.FilterSaveCurrent))
    add(KeyboardTarget("clear-filters", KeyboardTargetKind.FilterClearFilters))
    add(KeyboardTarget("export-filters", KeyboardTargetKind.FilterExportFilters))
    add(KeyboardTarget("import-filters", KeyboardTargetKind.FilterImportFilters))
}

fun annotationKeyboardTargets(
    blockIds: List<String>,
    hasRecentNotes: Boolean,
    hasBlocks: Boolean,
): List<KeyboardTarget> = buildList {
    add(KeyboardTarget("note-preview", KeyboardTargetKind.NotePreview, enabled = hasBlocks))
    add(KeyboardTarget("note-copy", KeyboardTargetKind.NoteCopy))
    add(KeyboardTarget("note-save", KeyboardTargetKind.NoteSave))
    add(KeyboardTarget("note-open", KeyboardTargetKind.NoteOpen))
    add(KeyboardTarget("note-recent", KeyboardTargetKind.NoteRecentNotes, enabled = hasRecentNotes))
    add(KeyboardTarget("note-prefix", KeyboardTargetKind.NotePrefix))
    add(KeyboardTarget("add-at-start", KeyboardTargetKind.NoteAddTextBlock))
    blockIds.forEach { id -> add(KeyboardTarget("block:$id", KeyboardTargetKind.NoteBlock)) }
    add(KeyboardTarget("add-after-last", KeyboardTargetKind.NoteAddTextBlock))
    add(KeyboardTarget("note-suffix", KeyboardTargetKind.NoteSuffix, enabled = hasBlocks))
}

fun keyboardShortcutHelpGroups(mac: Boolean = isMacOs): List<ShortcutHelpGroup> {
    val rowsByPanel = shortcutSpecs().map { spec ->
        ShortcutHelpRow(spec.label(mac), spec.description, spec.panel)
    }.groupBy { it.panel }
    return listOf(
        KeyboardPanel.GLOBAL to "Global",
        KeyboardPanel.PANEL_NAVIGATION to "Panel navigation",
        KeyboardPanel.LOG_VIEW to "Log view",
        KeyboardPanel.FILTERS to "Filters",
        KeyboardPanel.NOTES to "Notes",
        KeyboardPanel.POPUPS to "Context menus and popups",
    ).mapNotNull { (panel, title) ->
        rowsByPanel[panel]?.let { ShortcutHelpGroup(title, it) }
    }
}

// Splits groups into up to [columns] contiguous columns, minimizing the tallest column's row
// count (binary search on the max-column-weight, like the classic "split array, minimize the
// largest sum" problem) so no single column runs much longer than the others. Pads with empty
// columns if there are fewer natural splits than [columns].
fun splitShortcutGroupsIntoColumns(groups: List<ShortcutHelpGroup>, columns: Int): List<List<ShortcutHelpGroup>> {
    if (groups.isEmpty() || columns <= 1) return listOf(groups)
    val weights = groups.map { it.rows.size + 1 }

    fun columnsNeeded(maxWeight: Int): Int {
        var count = 1
        var current = 0
        for (w in weights) {
            if (current != 0 && current + w > maxWeight) {
                count++
                current = 0
            }
            current += w
        }
        return count
    }

    var lo = weights.max()
    var hi = weights.sum()
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (columnsNeeded(mid) <= columns) hi = mid else lo = mid + 1
    }

    val result = mutableListOf<MutableList<ShortcutHelpGroup>>()
    var current = mutableListOf<ShortcutHelpGroup>()
    var currentWeight = 0
    groups.forEachIndexed { i, group ->
        val w = weights[i]
        if (currentWeight != 0 && currentWeight + w > lo) {
            result.add(current)
            current = mutableListOf()
            currentWeight = 0
        }
        current.add(group)
        currentWeight += w
    }
    result.add(current)
    while (result.size < columns) result.add(mutableListOf())
    return result
}

private fun shortcutSpecs(): List<ShortcutSpec> = listOf(
    ShortcutSpec("⌘ ⇧ F", "Ctrl Shift F", "Toggle filter panel", KeyboardPanel.GLOBAL),
    ShortcutSpec("⌘ ⇧ A", "Ctrl Shift A", "Toggle notes panel", KeyboardPanel.GLOBAL),
    ShortcutSpec("⌘ ⇧ D", "Ctrl Shift D", "Toggle compare mode", KeyboardPanel.GLOBAL),
    ShortcutSpec("⌘ F", "Ctrl F", "Focus filter search", KeyboardPanel.GLOBAL),
    ShortcutSpec("⌘ 1 / ⌘ 2 / ⌘ 3", "Ctrl 1 / Ctrl 2 / Ctrl 3", "Focus Filters / Log / Notes", KeyboardPanel.GLOBAL),
    ShortcutSpec("⌘ ]", "Ctrl ]", "Next tab", KeyboardPanel.GLOBAL),
    ShortcutSpec("⌘ [", "Ctrl [", "Previous tab", KeyboardPanel.GLOBAL),
    ShortcutSpec("Ctrl Tab / Ctrl ⇧ Tab", "Ctrl Tab / Ctrl Shift Tab", "Next / previous tab", KeyboardPanel.GLOBAL),
    ShortcutSpec("⌘ W", "Ctrl W", "Close current tab", KeyboardPanel.GLOBAL),
    ShortcutSpec("⌘ /", "Ctrl /", "Show keyboard shortcuts", KeyboardPanel.GLOBAL),
    ShortcutSpec("F6", "F6", "Move focus to next panel", KeyboardPanel.PANEL_NAVIGATION),
    ShortcutSpec("⇧ F6", "Shift F6", "Move focus to previous panel", KeyboardPanel.PANEL_NAVIGATION),
    ShortcutSpec("↑ / ↓", "Up / Down", "Move through the focused panel", KeyboardPanel.PANEL_NAVIGATION),
    ShortcutSpec("Enter / Space", "Enter / Space", "Activate the focused item", KeyboardPanel.PANEL_NAVIGATION),
    ShortcutSpec("Escape", "Escape", "Close popup or leave edit mode", KeyboardPanel.PANEL_NAVIGATION),
    ShortcutSpec("↑ / ↓", "Up / Down", "Move selected log row", KeyboardPanel.LOG_VIEW),
    ShortcutSpec("Page Up / Page Down", "Page Up / Page Down", "Move by page", KeyboardPanel.LOG_VIEW),
    ShortcutSpec("Home / End", "Home / End", "Jump to first / last row", KeyboardPanel.LOG_VIEW),
    ShortcutSpec("⇧ ↑ / ⇧ ↓", "Shift Up / Shift Down", "Extend row selection", KeyboardPanel.LOG_VIEW),
    ShortcutSpec("⌘ A / ⌘ C", "Ctrl A / Ctrl C", "Select all visible rows / copy selected rows", KeyboardPanel.LOG_VIEW),
    ShortcutSpec("Enter / ⇧ F10", "Enter / Shift F10", "Open context menu for selected row", KeyboardPanel.LOG_VIEW),
    ShortcutSpec("Space", "Space", "Toggle current row selection", KeyboardPanel.LOG_VIEW),
    ShortcutSpec("↑ / ↓", "Up / Down", "Move through filter controls", KeyboardPanel.FILTERS),
    ShortcutSpec("← / →", "Left / Right", "Change option or row action", KeyboardPanel.FILTERS),
    ShortcutSpec("Enter / Space", "Enter / Space", "Apply focused filter control", KeyboardPanel.FILTERS),
    ShortcutSpec("Alt ↑ / Alt ↓", "Alt Up / Alt Down", "Move selected sequence", KeyboardPanel.FILTERS),
    ShortcutSpec("Delete", "Delete", "Remove selected filter item", KeyboardPanel.FILTERS),
    ShortcutSpec("⌘ S / ⌘ O", "Ctrl S / Ctrl O", "Save note / open note", KeyboardPanel.NOTES),
    ShortcutSpec("⌘ C", "Ctrl C", "Copy note markdown", KeyboardPanel.NOTES),
    ShortcutSpec("Alt ↑ / Alt ↓", "Alt Up / Alt Down", "Move selected note block", KeyboardPanel.NOTES),
    ShortcutSpec("Ctrl Enter", "Ctrl Enter", "Add text block below", KeyboardPanel.NOTES),
    ShortcutSpec("Delete", "Delete", "Remove selected note block", KeyboardPanel.NOTES),
    ShortcutSpec("↑ / ↓", "Up / Down", "Move highlight", KeyboardPanel.POPUPS),
    ShortcutSpec("Enter", "Enter", "Activate highlighted item", KeyboardPanel.POPUPS),
    ShortcutSpec("Escape", "Escape", "Close menu or popup", KeyboardPanel.POPUPS),
)

private fun Int.sign(): Int = when {
    this > 0 -> 1
    this < 0 -> -1
    else -> 0
}

private fun Int.coerceInOrZero(items: List<*>): Int {
    if (items.isEmpty()) return 0
    return coerceIn(0, items.lastIndex)
}
