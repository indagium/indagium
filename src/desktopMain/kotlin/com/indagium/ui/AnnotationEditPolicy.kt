package com.indagium.ui

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

/** Central predicate used by Notes mutation callbacks and controls. */
internal fun notesMutationAllowed(notesLocked: Boolean): Boolean = !notesLocked

/**
 * Files that should continue through the app-wide drop router while Notes is locked. Image files
 * are consumed by the Notes drop target (without insertion); logs, videos, and other non-images
 * must remain routable instead of being swallowed by the locked target.
 */
internal fun <T> forwardedDropFilesWhenNotesLocked(files: List<T>, isImage: (T) -> Boolean): List<T> =
    files.filterNot(isImage)
