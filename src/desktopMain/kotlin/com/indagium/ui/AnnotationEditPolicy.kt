package com.indagium.ui

import androidx.compose.ui.text.input.TextFieldValue

/** Result of reconciling a rich-editor draft with a newer Notes value from another writer. */
internal data class MarkdownDraftSync(
    val baseline: String,
    val draft: String,
    val latest: String,
    val conflict: Boolean,
)

/**
 * Keeps the upstream baseline separate from a user's draft. A pristine draft follows an upstream
 * update; a dirty draft remains untouched and is marked as a conflict until the user chooses
 * Reload latest or explicitly overwrites the latest value.
 */
internal fun reconcileMarkdownDraft(baseline: String, draft: String, latest: String): MarkdownDraftSync =
    when {
        latest == baseline -> MarkdownDraftSync(baseline, draft, latest, conflict = false)
        draft == baseline -> MarkdownDraftSync(latest, latest, latest, conflict = false)
        else -> MarkdownDraftSync(baseline, draft, latest, conflict = true)
    }

/**
 * Undo/redo history for the Markdown editor. Toolbar actions mutate [TextFieldValue] directly,
 * outside BasicTextField's keyboard edit pipeline, so they need an explicit history boundary.
 * Store the complete value (including selection) so undoing formatting restores the exact draft
 * that was on screen immediately before the action.
 */
internal class MarkdownEditorUndoHistory {
    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()

    fun record(previous: TextFieldValue, current: TextFieldValue) {
        if (previous.text == current.text) return
        undoStack.addLast(previous)
        redoStack.clear()
    }

    fun undo(current: TextFieldValue): TextFieldValue? {
        if (undoStack.isEmpty()) return null
        val previous = undoStack.removeLast()
        redoStack.addLast(current)
        return previous
    }

    fun redo(current: TextFieldValue): TextFieldValue? {
        if (redoStack.isEmpty()) return null
        val next = redoStack.removeLast()
        undoStack.addLast(current)
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}

/** Central predicate used by Notes mutation callbacks and controls. */
internal fun notesMutationAllowed(notesLocked: Boolean): Boolean = !notesLocked

/**
 * Files that should continue through the app-wide drop router while Notes is locked. Image files
 * are consumed by the Notes drop target (without insertion); logs, videos, and other non-images
 * must remain routable instead of being swallowed by the locked target.
 */
internal fun <T> forwardedDropFilesWhenNotesLocked(files: List<T>, isImage: (T) -> Boolean): List<T> =
    files.filterNot(isImage)
