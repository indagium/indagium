package com.openlog

import androidx.compose.ui.input.key.Key
import com.openlog.ui.KeyboardPanel
import com.openlog.ui.KeyboardTargetKind
import com.openlog.ui.RovingItem
import com.openlog.ui.annotationKeyboardTargets
import com.openlog.ui.annotationPreviewCopyShortcutHandled
import com.openlog.ui.filterKeyboardTargets
import com.openlog.ui.keyboardShortcutHelpGroups
import com.openlog.ui.messageRuleInputConsumesKey
import com.openlog.ui.rovingActivationId
import com.openlog.ui.rovingMove
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyboardNavigationTest {
    @Test
    fun rovingMoveSkipsDisabledItemsAndWrapsWhenRequested() {
        val items = listOf(
            RovingItem("header"),
            RovingItem("disabled", enabled = false),
            RovingItem("levels"),
            RovingItem("saved"),
        )

        assertEquals(2, rovingMove(items, currentIndex = 0, delta = 1))
        assertEquals(3, rovingMove(items, currentIndex = 2, delta = 1))
        assertEquals(0, rovingMove(items, currentIndex = 3, delta = 1, wrap = true))
        assertEquals(3, rovingMove(items, currentIndex = 3, delta = 1, wrap = false))
    }

    @Test
    fun rovingActivationReturnsOnlyEnabledItemIds() {
        val items = listOf(
            RovingItem("mode"),
            RovingItem("disabled", enabled = false),
        )

        assertEquals("mode", rovingActivationId(items, 0))
        assertEquals(null, rovingActivationId(items, 1))
        assertEquals(null, rovingActivationId(items, -1))
    }

    @Test
    fun shortcutHelpHasNoDuplicateGlobalCombinationsAndDocumentsNewShortcuts() {
        val globalRows = keyboardShortcutHelpGroups(mac = false)
            .flatMap { group -> group.rows.filter { it.panel == KeyboardPanel.GLOBAL } }

        val labels = globalRows.map { it.label }
        assertEquals(labels.toSet().size, labels.size)
        assertTrue(labels.contains("Ctrl F"))
        assertTrue(labels.contains("Ctrl 1 / Ctrl 2 / Ctrl 3"))
        assertTrue(labels.contains("Ctrl Tab / Ctrl Shift Tab"))
        assertTrue(labels.contains("Ctrl /"))
        assertFalse(
            keyboardShortcutHelpGroups(mac = false)
                .flatMap { it.rows }
                .any { it.description.contains("click to focus", ignoreCase = true) },
        )
    }

    @Test
    fun filterTargetsIncludePanelOrderForKeyboardNavigation() {
        val targets = filterKeyboardTargets(
            levelCount = 6,
            sequenceIds = listOf("seq-a", "seq-b"),
            manualCollapseIds = listOf("manual-a"),
            savedFilterIds = listOf("saved-a"),
        )

        assertEquals(KeyboardTargetKind.FilterModeTags, targets[0].kind)
        assertEquals(KeyboardTargetKind.FilterModeRegex, targets[1].kind)
        assertEquals(KeyboardTargetKind.FilterTagInput, targets[2].kind)
        assertTrue(targets.any { it.kind == KeyboardTargetKind.FilterLogLevel && it.id == "level-0" })
        assertTrue(targets.any { it.kind == KeyboardTargetKind.FilterSequence && it.id == "sequence:seq-a" })
        assertTrue(targets.any { it.kind == KeyboardTargetKind.FilterManualCollapse && it.id == "manual:manual-a" })
        assertTrue(targets.any { it.kind == KeyboardTargetKind.FilterSavedFilter && it.id == "saved:saved-a" })
        assertEquals(KeyboardTargetKind.FilterImportFilters, targets.last().kind)
    }

    @Test
    fun annotationTargetsIncludeHeaderActionsBlocksAndSuffix() {
        val targets = annotationKeyboardTargets(
            blockIds = listOf("note-a", "log-a"),
            hasRecentNotes = true,
            hasBlocks = true,
        )

        assertEquals(KeyboardTargetKind.NotePreview, targets[0].kind)
        assertTrue(targets.any { it.kind == KeyboardTargetKind.NoteRecentNotes })
        assertTrue(targets.any { it.kind == KeyboardTargetKind.NotePrefix })
        assertTrue(targets.any { it.kind == KeyboardTargetKind.NoteBlock && it.id == "block:note-a" })
        assertTrue(targets.any { it.kind == KeyboardTargetKind.NoteAddTextBlock && it.id == "add-after-last" })
        assertEquals(KeyboardTargetKind.NoteSuffix, targets.last().kind)
    }

    @Test
    fun messageRuleInputLeavesHorizontalArrowsForTextCursor() {
        assertFalse(messageRuleInputConsumesKey(Key.DirectionLeft))
        assertFalse(messageRuleInputConsumesKey(Key.DirectionRight))
        assertTrue(messageRuleInputConsumesKey(Key.DirectionUp))
        assertTrue(messageRuleInputConsumesKey(Key.DirectionDown))
        assertTrue(messageRuleInputConsumesKey(Key.Enter))
    }

    @Test
    fun annotationPreviewCopyShortcutDoesNotStealFocusedTextFieldCopy() {
        assertFalse(annotationPreviewCopyShortcutHandled(actionPressed = true, key = Key.C, textFieldFocused = true))
        assertTrue(annotationPreviewCopyShortcutHandled(actionPressed = true, key = Key.C, textFieldFocused = false))
        assertFalse(annotationPreviewCopyShortcutHandled(actionPressed = false, key = Key.C, textFieldFocused = false))
    }
}
