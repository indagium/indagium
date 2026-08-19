package com.indagium.diagram3

// ── Every mutation as a named, undoable command ─────────────────────────────────────────────
//
// The single choke point every document edit in v3 flows through, so `⌘Z` (spec §09) is always
// exactly one step per user action — INCLUDING a whole regeneration apply (spec §08: "Apply is one
// undoable transaction, not 15"). The mechanism is deliberately the simplest one that is always
// correct: [Seq3UndoEntry] carries the WHOLE document as it was immediately before the command, so
// undo is just "restore that snapshot" regardless of how many fields the command touched — no
// per-field diffing to keep in sync as this package grows. `ui.Seq3Session` (phase 3) is what turns
// a stack of these into the actual `⌘Z`/`⌘⇧Z` keys.
//
// This file also owns the lifeline-arrangement mutations (reorder/rename/merge, spec §07) — they
// did not fit Seq3Queue.kt's own "messages" framing or Seq3Guided.kt's "resolving one message"
// framing, and "every mutation" (this phase's brief) makes this file their natural home.

sealed class Seq3Command {
    data class Bulk(val selectedIds: Set<String>, val action: Seq3BulkAction) : Seq3Command()

    data class NudgePin(val messageId: String, val direction: Seq3PinDirection) : Seq3Command()

    data class ClearPin(val messageId: String) : Seq3Command()

    data class GuidedTarget(val messageId: String, val lifelineId: String, val applyToAllOccurrences: Boolean = true) : Seq3Command()

    data class GuidedSelfCall(val messageId: String) : Seq3Command()

    data class GuidedNewLifeline(val messageId: String, val newLifeline: Seq3Lifeline) : Seq3Command()

    data class ApplyRegeneration(val review: Seq3RegenReview) : Seq3Command()

    /** "Revert to generated" on ONE row (phase 2's `Seq3Session.revertMessage`, item 15) — unlike
     *  [ApplyRegeneration]'s whole-document review, this replaces exactly the message [messageId]
     *  names with [replacement] and touches nothing else. The async "regenerate fresh, find the
     *  match via [matchOneMessage]" half lives in `ui.Seq3Session`; this command is the pure,
     *  already-resolved apply step, kept a single undo step like every other command here. */
    data class ReplaceMessage(
        val messageId: String,
        val replacement: Seq3Message,
        /** Other split rows representing the same generated message to remove atomically. */
        val removeMessageIds: Set<String> = emptySet(),
    ) : Seq3Command()

    /** Applies an externally-built WHOLE [Seq3Document] as one undo step (item 2, the queue panel's
     *  "Add ＋") — the strict generalization of [ReplaceMessage] for a caller (`addSeq3MessageFromSelection`)
     *  that builds a new document outside this file (a new lifeline plus a new message) rather than
     *  mutating one field in place. Still routes through the same `Seq3Session.applyCommand` choke
     *  point as every other verb; this is simply the pure, already-resolved apply step for "set the
     *  document to X". Unapplied (nothing to undo) when [document] is byte-identical to the current
     *  one — mirrors every other command's "a no-op edit pushes no undo entry" contract. */
    data class ReplaceDocument(val document: Seq3Document) : Seq3Command()

    /** Adds a message with explicit endpoints, timestamp, and queue position as one undoable edit. */
    data class AddCustomMessage(val spec: Seq3CustomMessageSpec) : Seq3Command()

    /** Updates an author-controlled timestamp without rewriting the message's log evidence. */
    data class SetMessageTimestamp(
        val messageId: String,
        val timestampMillis: Long?,
        val rawTimestamp: String = "",
    ) : Seq3Command()

    /** Moves one queue row to an explicit position as one undoable edit. */
    data class MoveMessage(val messageId: String, val position: Seq3InsertionPosition) : Seq3Command()

    /** Splits one occurrence out of a grouped generated message and places it at its evidence
     *  timestamp. The remaining grouped message stays in place; both rows remain undoable as one
     *  queue edit. */
    data class MoveOccurrenceOut(val messageId: String, val entryId: Int) : Seq3Command()

    /** Splits several checked occurrences in one undoable queue edit. */
    data class MoveOccurrencesOut(val occurrences: List<Seq3OccurrenceRef>) : Seq3Command()

    /** Returns one standalone Move out row to the exact group it came from. */
    data class MoveOccurrenceBack(val messageId: String) : Seq3Command()

    /** Hides or shows one occurrence without changing any of its sibling occurrences. */
    data class SetOccurrenceVisibility(
        val messageId: String,
        val entryId: Int,
        val visibility: Seq3Visibility,
    ) : Seq3Command()

    /** Moves/resizes one canvas note without changing the messages it annotates. */
    data class SetNoteGeometry(
        val noteId: String,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    ) : Seq3Command()

    data class ReorderLifelines(val orderedLifelineIds: List<String>) : Seq3Command()

    data class RenameLifeline(val lifelineId: String, val name: String) : Seq3Command()

    data class MergeLifelines(val keepLifelineId: String, val mergedLifelineId: String) : Seq3Command()

    /** Adds an author-created lifeline. It may have no source tag and can still be used as a
     *  target for authored or unresolved messages. */
    data class AddLifeline(val lifeline: Seq3Lifeline) : Seq3Command()

    data class SetLifelineVisibility(val lifelineId: String, val visibility: Seq3Visibility) : Seq3Command()

    /** Removes a lifeline while preserving target-side messages by making those targets unresolved. */
    data class RemoveLifeline(val lifelineId: String) : Seq3Command()

    /** One undoable merge for a checkbox selection of lifelines. */
    data class MergeLifelineSelection(val keepLifelineId: String, val mergedLifelineIds: Set<String>) : Seq3Command()

    /** Moves one represented source tag out of a merged lifeline into [newLifeline]. */
    data class SplitLifeline(
        val lifelineId: String,
        val tagId: String,
        val newLifeline: Seq3Lifeline,
    ) : Seq3Command()

    /** Actor vs participant glyph (item 3's "participant ▾" control) — offered for every
     *  lifeline, not just manual ones, since [Seq3Lifeline.kind] has no generated/manual
     *  distinction to respect. */
    data class SetLifelineKind(val lifelineId: String, val kind: Seq3LifelineKind) : Seq3Command()

    /** Per-lifeline display-name override (item 3's "name ▾" control) — null resets to "inherit
     *  the diagram default" ([Seq3Document.lifelineDisplaySegments]). */
    data class SetLifelineDisplaySegments(val lifelineId: String, val segments: Int?) : Seq3Command()

    /** Diagram-wide default for every lifeline that doesn't set its own [SetLifelineDisplaySegments]
     *  override. */
    data class SetDocumentDisplaySegments(val segments: Int) : Seq3Command()

    /** Per-diagram theme override (WP4's toolbar dropdown) — null means "follow the app theme". */
    data class SetDocumentTheme(val themePresetName: String?) : Seq3Command()
}

/** Snapshot-based undo record — see this file's header for why a whole-document snapshot, not a
 *  per-command inverse, is what makes "apply is one undo step" trivially true for every command
 *  including [Seq3Command.ApplyRegeneration]. */
data class Seq3UndoEntry(val label: String, val before: Seq3Document)

data class Seq3CommandResult(val document: Seq3Document, val applied: Boolean, val reason: String? = null, val undo: Seq3UndoEntry? = null)

/** Applies [command] to [document]. An unapplied result (invalid selection, invalid pin, unknown
 *  id, …) always returns [document] itself, byte-identical, with [Seq3CommandResult.undo] null —
 *  there is nothing to undo for an edit that never happened, matching every pure function this
 *  dispatches to (`applySeq3BulkAction`, `nudgeSeq3OrderPin`, …) already returning the untouched
 *  document on failure.
 */
fun applySeq3Command(document: Seq3Document, command: Seq3Command): Seq3CommandResult {
    val outcome = dispatch(document, command)
    return if (!outcome.applied) {
        Seq3CommandResult(document, applied = false, reason = outcome.reason)
    } else {
        Seq3CommandResult(outcome.document, applied = true, undo = Seq3UndoEntry(outcome.label, document))
    }
}

/** Restores the document exactly as it was before the command [entry] recorded — the whole of
 *  `⌘Z`'s implementation for this package. */
fun undoSeq3Command(entry: Seq3UndoEntry): Seq3Document = entry.before

private class Outcome(val document: Seq3Document, val applied: Boolean, val label: String, val reason: String? = null)

private fun applied(document: Seq3Document, label: String) = Outcome(document, true, label)

private fun unapplied(document: Seq3Document, reason: String) = Outcome(document, false, "", reason)

@Suppress("CyclomaticComplexMethod")
private fun dispatch(document: Seq3Document, command: Seq3Command): Outcome = when (command) {
    is Seq3Command.Bulk -> dispatchBulk(document, command)
    is Seq3Command.NudgePin -> dispatchNudge(document, command)
    is Seq3Command.ClearPin -> applied(clearSeq3OrderPin(document, command.messageId), "Clear order pin")
    is Seq3Command.GuidedTarget ->
        applied(applySeq3GuidedTarget(document, command.messageId, command.lifelineId, command.applyToAllOccurrences), "Set target")
    is Seq3Command.GuidedSelfCall -> applied(applySeq3GuidedSelfCall(document, command.messageId), "Make self-call")
    is Seq3Command.GuidedNewLifeline -> applied(applySeq3GuidedNewLifeline(document, command.messageId, command.newLifeline), "Add lifeline")
    is Seq3Command.ApplyRegeneration -> applied(applySeq3Regeneration(document, command.review), "Regenerate")
    is Seq3Command.ReplaceMessage -> dispatchReplaceMessage(document, command)
    is Seq3Command.ReplaceDocument -> dispatchReplaceDocument(document, command)
    is Seq3Command.AddCustomMessage -> dispatchAddCustomMessage(document, command)
    is Seq3Command.SetMessageTimestamp -> dispatchSetMessageTimestamp(document, command)
    is Seq3Command.MoveMessage -> dispatchMoveMessage(document, command)
    is Seq3Command.MoveOccurrenceOut -> dispatchMoveOccurrenceOut(document, command)
    is Seq3Command.MoveOccurrencesOut -> dispatchMoveOccurrencesOut(document, command)
    is Seq3Command.MoveOccurrenceBack -> dispatchMoveOccurrenceBack(document, command)
    is Seq3Command.SetOccurrenceVisibility -> dispatchSetOccurrenceVisibility(document, command)
    is Seq3Command.SetNoteGeometry -> dispatchSetNoteGeometry(document, command)
    is Seq3Command.ReorderLifelines -> dispatchReorder(document, command)
    is Seq3Command.RenameLifeline -> dispatchRename(document, command)
    is Seq3Command.MergeLifelines -> dispatchMergeLifelines(document, command)
    is Seq3Command.AddLifeline -> dispatchAddLifeline(document, command)
    is Seq3Command.SetLifelineVisibility -> dispatchSetLifelineVisibility(document, command)
    is Seq3Command.RemoveLifeline -> dispatchRemoveLifeline(document, command)
    is Seq3Command.MergeLifelineSelection -> dispatchMergeLifelineSelection(document, command)
    is Seq3Command.SplitLifeline -> dispatchSplitLifeline(document, command)
    is Seq3Command.SetLifelineKind -> dispatchSetLifelineKind(document, command)
    is Seq3Command.SetLifelineDisplaySegments -> dispatchSetLifelineDisplaySegments(document, command)
    is Seq3Command.SetDocumentDisplaySegments -> dispatchSetDocumentDisplaySegments(document, command)
    is Seq3Command.SetDocumentTheme -> dispatchSetDocumentTheme(document, command)
}

private fun dispatchBulk(document: Seq3Document, command: Seq3Command.Bulk): Outcome {
    val result = applySeq3BulkAction(document, command.selectedIds, command.action)
    return if (result.applied) applied(result.document, bulkLabel(command.action)) else unapplied(document, result.reason ?: "Not applied")
}

private fun bulkLabel(action: Seq3BulkAction): String = when (action) {
    is Seq3BulkAction.SetFrom -> "Set from"
    is Seq3BulkAction.SetTo -> "Set target"
    is Seq3BulkAction.Merge -> "Merge messages"
    is Seq3BulkAction.Group -> "Group as fragment"
    Seq3BulkAction.Hide -> "Hide"
    Seq3BulkAction.Show -> "Show"
    is Seq3BulkAction.Note -> "Add note"
    is Seq3BulkAction.DeleteFragment -> "Remove fragment"
    is Seq3BulkAction.DeleteNote -> "Remove note"
    is Seq3BulkAction.SetFragmentLabel -> "Rename fragment"
    is Seq3BulkAction.SetNoteText -> "Rename note"
    is Seq3BulkAction.SetKind -> "Set kind"
    is Seq3BulkAction.SetPattern -> "Set pattern"
    is Seq3BulkAction.SetLabel -> "Rename label"
    is Seq3BulkAction.SetRepeat -> "Set repeat"
    is Seq3BulkAction.SetFragmentVisibility -> if (action.visibility == Seq3Visibility.HIDDEN) "Hide fragment" else "Show fragment"
    is Seq3BulkAction.SetNoteVisibility -> if (action.visibility == Seq3Visibility.HIDDEN) "Hide note" else "Show note"
    Seq3BulkAction.SwapEndpoints -> "Swap direction"
}

private fun dispatchNudge(document: Seq3Document, command: Seq3Command.NudgePin): Outcome {
    val result = nudgeSeq3OrderPin(document, command.messageId, command.direction)
    return if (result.applied) applied(result.document, "Pin order") else unapplied(document, result.reason ?: "Not applied")
}

private fun dispatchReplaceMessage(document: Seq3Document, command: Seq3Command.ReplaceMessage): Outcome {
    if (document.messages.none { it.id == command.messageId }) return unapplied(document, "Unknown message")
    // A freshly generated counterpart can carry the original generated id. Revert must retain
    // the durable queue row id, otherwise moving an occurrence out and reverting it can introduce
    // a duplicate LazyColumn key when the group is uncollapsed.
    val replacement = command.replacement.copy(id = command.messageId)
    val removedIds = command.removeMessageIds - command.messageId
    val repointIds = { ids: List<String> -> ids.map { if (it in removedIds) command.messageId else it }.distinct() }
    val repointOccurrenceRefs = { refs: List<Seq3OccurrenceRef> ->
        refs.map { ref -> if (ref.messageId in removedIds) ref.copy(messageId = command.messageId) else ref }.distinct()
    }
    val replaced = document.copy(
        messages = document.messages
            .filterNot { it.id in removedIds }
            .map { if (it.id == command.messageId) replacement else it },
        fragments = document.fragments.map {
            it.copy(
                messageIds = repointIds(it.messageIds),
                occurrenceRefs = repointOccurrenceRefs(it.occurrenceRefs),
            )
        },
        notes = document.notes.map { it.copy(messageIds = repointIds(it.messageIds)) },
    )
    return applied(replaced, "Revert to generated")
}

private fun dispatchReplaceDocument(document: Seq3Document, command: Seq3Command.ReplaceDocument): Outcome {
    if (command.document == document) return unapplied(document, "No change")
    return applied(command.document, "Add message")
}

private fun dispatchAddCustomMessage(document: Seq3Document, command: Seq3Command.AddCustomMessage): Outcome =
    when (val result = addSeq3CustomMessage(document, command.spec)) {
        is Seq3CustomMessageResult.Added -> applied(result.document, "Add custom message")
        is Seq3CustomMessageResult.Rejected -> unapplied(document, result.reason)
    }

private fun dispatchSetMessageTimestamp(document: Seq3Document, command: Seq3Command.SetMessageTimestamp): Outcome =
    when (val result = updateSeq3MessageTimestamp(document, command.messageId, command.timestampMillis, command.rawTimestamp)) {
        is Seq3MessageEditResult.Updated -> if (result.document == document) {
            unapplied(document, "No change")
        } else {
            applied(result.document, "Edit message timestamp")
        }
        is Seq3MessageEditResult.Rejected -> unapplied(document, result.reason)
    }

private fun dispatchMoveMessage(document: Seq3Document, command: Seq3Command.MoveMessage): Outcome =
    when (val result = moveSeq3Message(document, command.messageId, command.position)) {
        is Seq3MessageEditResult.Updated -> if (result.document == document) {
            unapplied(document, "No change")
        } else {
            applied(result.document, "Move message")
        }
        is Seq3MessageEditResult.Rejected -> unapplied(document, result.reason)
    }

private fun dispatchMoveOccurrenceOut(document: Seq3Document, command: Seq3Command.MoveOccurrenceOut): Outcome =
    when (val result = moveSeq3OccurrenceOut(document, command.messageId, command.entryId)) {
        is Seq3MessageEditResult.Updated -> if (result.document == document) {
            unapplied(document, "No change")
        } else {
            applied(result.document, "Move occurrence out")
        }
        is Seq3MessageEditResult.Rejected -> unapplied(document, result.reason)
    }

private fun dispatchMoveOccurrencesOut(document: Seq3Document, command: Seq3Command.MoveOccurrencesOut): Outcome =
    when (val result = moveSeq3OccurrencesOut(document, command.occurrences)) {
        is Seq3MessageEditResult.Updated -> if (result.document == document) {
            unapplied(document, "No change")
        } else {
            applied(result.document, "Move occurrences out")
        }
        is Seq3MessageEditResult.Rejected -> unapplied(document, result.reason)
    }

private fun dispatchMoveOccurrenceBack(document: Seq3Document, command: Seq3Command.MoveOccurrenceBack): Outcome {
    val moved = document.messages.firstOrNull { it.id == command.messageId }
        ?: return unapplied(document, "Unknown message")
    val targetId = moved.movedOutFromMessageId ?: return unapplied(document, "Message was not moved out")
    if (document.messages.none { it.id == targetId }) return unapplied(document, "Original message group is unavailable")
    val result = applySeq3BulkAction(document, setOf(targetId, moved.id), Seq3BulkAction.Merge(targetId))
    return if (result.applied) applied(result.document, "Move occurrence back") else {
        unapplied(document, result.reason ?: "Move back was rejected")
    }
}

private fun dispatchSetOccurrenceVisibility(document: Seq3Document, command: Seq3Command.SetOccurrenceVisibility): Outcome =
    when (val result = setSeq3OccurrenceVisibility(document, command.messageId, command.entryId, command.visibility)) {
        is Seq3MessageEditResult.Updated -> if (result.document == document) {
            unapplied(document, "No change")
        } else {
            applied(result.document, "Set occurrence visibility")
        }
        is Seq3MessageEditResult.Rejected -> unapplied(document, result.reason)
    }

private fun dispatchSetNoteGeometry(document: Seq3Document, command: Seq3Command.SetNoteGeometry): Outcome {
    if (document.notes.none { it.id == command.noteId }) return unapplied(document, "Unknown note")
    if (command.width <= 1.0 || command.height <= 1.0) return unapplied(document, "Note size must be positive")
    val updated = document.copy(
        notes = document.notes.map { note ->
            if (note.id == command.noteId) note.copy(
                x = command.x,
                y = command.y,
                width = command.width,
                height = command.height,
            ) else note
        },
    )
    return if (updated == document) unapplied(document, "No change") else applied(updated, "Move/resize note")
}

private fun dispatchReorder(document: Seq3Document, command: Seq3Command.ReorderLifelines): Outcome {
    if (command.orderedLifelineIds.toSet() != document.lifelines.map { it.id }.toSet()) return unapplied(document, "Must list every lifeline exactly once")
    val ordinalById = command.orderedLifelineIds.withIndex().associate { (i, id) -> id to i }
    val reordered = document.copy(lifelines = document.lifelines.map { it.copy(ordinal = ordinalById.getValue(it.id)) })
    return applied(reordered, "Reorder lifelines")
}

private fun dispatchRename(document: Seq3Document, command: Seq3Command.RenameLifeline): Outcome {
    val name = command.name.trim()
    if (name.isBlank()) return unapplied(document, "Lifeline name is required")
    val lifeline = document.lifelines.firstOrNull { it.id == command.lifelineId }
        ?: return unapplied(document, "Unknown lifeline")
    if (lifeline.name == name) return unapplied(document, "No change")
    val renamed = document.copy(lifelines = document.lifelines.map { if (it.id == command.lifelineId) it.copy(name = name) else it })
    return applied(renamed, "Rename lifeline")
}

/** "merge two lifelines that are the same actor under two tags" (spec §07): [command.mergedLifelineId]'s
 *  `tagIds` fold into [command.keepLifelineId], every message endpoint referencing the merged id is
 *  repointed, and the merged lifeline is dropped from the document. */
private fun dispatchMergeLifelines(document: Seq3Document, command: Seq3Command.MergeLifelines): Outcome {
    if (command.keepLifelineId == command.mergedLifelineId) return unapplied(document, "Cannot merge a lifeline with itself")
    return mergeLifelines(document, command.keepLifelineId, setOf(command.mergedLifelineId))
}

private fun dispatchAddLifeline(document: Seq3Document, command: Seq3Command.AddLifeline): Outcome {
    val trimmedName = command.lifeline.name.trim()
    // A manual lifeline with NO represented tag contributes nothing to `mergeLifelines`' tagIds
    // fold (that function only folds what's already there), so it could never show as "merged · N
    // tags" and could never be split back out (`dispatchSplitLifeline` requires `tagIds.size > 1`).
    // Defaulting to the lifeline's own name gives it a synthetic represented identity — see
    // `AddCustomMessage`'s `Seq3Match.tag` population (Seq3Generator.kt), which reads
    // `from.tagIds.firstOrNull()` and so now aligns with this default for any message authored on
    // this lifeline afterward.
    val tagIds = command.lifeline.tagIds.ifEmpty { setOf(trimmedName) }
    val lifeline = command.lifeline.copy(name = trimmedName, tagIds = tagIds)
    if (lifeline.id.isBlank()) return unapplied(document, "Lifeline id is required")
    if (lifeline.name.isBlank()) return unapplied(document, "Lifeline name is required")
    if (document.lifelines.any { it.id == lifeline.id }) return unapplied(document, "Lifeline already exists")
    return applied(document.copy(lifelines = document.lifelines + lifeline), "Add lifeline")
}

private fun dispatchSetLifelineVisibility(document: Seq3Document, command: Seq3Command.SetLifelineVisibility): Outcome {
    val lifeline = document.lifelines.firstOrNull { it.id == command.lifelineId }
        ?: return unapplied(document, "Unknown lifeline")
    if (lifeline.visibility == command.visibility) return unapplied(document, "No change")
    return applied(
        document.copy(lifelines = document.lifelines.map {
            if (it.id == command.lifelineId) it.copy(visibility = command.visibility) else it
        }),
        if (command.visibility == Seq3Visibility.HIDDEN) "Hide lifeline" else "Show lifeline",
    )
}

private fun dispatchRemoveLifeline(document: Seq3Document, command: Seq3Command.RemoveLifeline): Outcome {
    if (document.lifelines.none { it.id == command.lifelineId }) return unapplied(document, "Unknown lifeline")
    if (document.messages.any { it.fromLifelineId == command.lifelineId }) {
        return unapplied(document, "Reassign this lifeline's source messages before removing it")
    }
    val updatedMessages = document.messages.map { message ->
        if (message.toLifelineId == command.lifelineId) {
            message.copy(
                toLifelineId = null,
                kind = if (message.kind == Seq3Kind.SELF) Seq3Kind.CALL else message.kind,
            )
        } else {
            message
        }
    }
    return applied(
        document.copy(
            lifelines = document.lifelines.filterNot { it.id == command.lifelineId },
            messages = updatedMessages,
        ),
        "Remove lifeline",
    )
}

private fun dispatchMergeLifelineSelection(document: Seq3Document, command: Seq3Command.MergeLifelineSelection): Outcome {
    if (command.mergedLifelineIds.isEmpty()) return unapplied(document, "Select at least two lifelines")
    if (command.keepLifelineId in command.mergedLifelineIds) return unapplied(document, "Cannot merge a lifeline with itself")
    return mergeLifelines(document, command.keepLifelineId, command.mergedLifelineIds)
}

private fun mergeLifelines(document: Seq3Document, keepId: String, mergeIds: Set<String>): Outcome {
    val keep = document.lifelines.firstOrNull { it.id == keepId }
        ?: return unapplied(document, "Unknown lifeline to keep")
    if (mergeIds.isEmpty()) return unapplied(document, "Select a lifeline to merge")
    if (mergeIds.any { id -> id == keepId || document.lifelines.none { it.id == id } }) {
        return unapplied(document, "Unknown lifeline to merge")
    }
    val mergedTags = document.lifelines.filter { it.id in mergeIds }.flatMapTo(keep.tagIds.toMutableSet()) { it.tagIds }
    fun repoint(id: String?): String? = if (id != null && id in mergeIds) keepId else id
    val survivingLifelines = document.lifelines
        .filterNot { it.id in mergeIds }
        .map { if (it.id == keepId) it.copy(tagIds = mergedTags) else it }
    // Compact the ordinal sequence after removal — matching `dispatchReorder` and
    // `dispatchSplitLifeline`, which both already keep `ordinal` gap-free. Rank is taken from the
    // SURVIVORS' existing ordinal order (not `document.lifelines`' own list position, which is
    // display-insignificant — Seq3Layout always sorts by `ordinal`), then written back onto each
    // element in place so the list's own element order/identity is otherwise untouched, exactly
    // like `dispatchReorder`'s own `ordinalById.getValue(it.id)` lookup-and-map.
    val ordinalById = survivingLifelines.sortedBy { it.ordinal }.withIndex().associate { (index, l) -> l.id to index }
    val result = document.copy(
        lifelines = survivingLifelines.map { it.copy(ordinal = ordinalById.getValue(it.id)) },
        messages = document.messages.map {
            it.copy(
                fromLifelineId = repoint(it.fromLifelineId) ?: it.fromLifelineId,
                toLifelineId = repoint(it.toLifelineId),
            )
        },
    )
    return if (result == document) unapplied(document, "No change") else applied(result, "Merge lifelines")
}

private fun dispatchSplitLifeline(document: Seq3Document, command: Seq3Command.SplitLifeline): Outcome {
    val source = document.lifelines.firstOrNull { it.id == command.lifelineId }
        ?: return unapplied(document, "Unknown lifeline")
    if (source.tagIds.size <= 1 || command.tagId !in source.tagIds) {
        return unapplied(document, "Only a represented tag from a merged lifeline can be moved out")
    }
    val newLifeline = command.newLifeline.copy(tagIds = setOf(command.tagId))
    if (newLifeline.id.isBlank() || newLifeline.name.isBlank()) return unapplied(document, "New lifeline name is required")
    if (document.lifelines.any { it.id == newLifeline.id }) return unapplied(document, "Lifeline already exists")
    val insertedOrdinal = source.ordinal + 1
    val lifelines = document.lifelines.map {
        when {
            it.id == source.id -> it.copy(tagIds = it.tagIds - command.tagId)
            it.ordinal >= insertedOrdinal -> it.copy(ordinal = it.ordinal + 1)
            else -> it
        }
    } + newLifeline.copy(ordinal = insertedOrdinal)
    val messages = document.messages.map {
        if (it.fromLifelineId == source.id && it.match.tag == command.tagId) {
            it.copy(
                fromLifelineId = newLifeline.id,
                // A self-call's target mirrors its source. Preserve that invariant when the
                // represented tag is split out; ordinary calls still keep their existing target.
                toLifelineId = if (it.kind == Seq3Kind.SELF && it.toLifelineId == source.id) newLifeline.id else it.toLifelineId,
            )
        } else {
            it
        }
    }
    return applied(document.copy(lifelines = lifelines, messages = messages), "Move tag to lifeline")
}

// ── Lifeline identity / diagram-wide display commands (WP1 model foundation) ──────────────────

private fun dispatchSetLifelineKind(document: Seq3Document, command: Seq3Command.SetLifelineKind): Outcome {
    val lifeline = document.lifelines.firstOrNull { it.id == command.lifelineId } ?: return unapplied(document, "Unknown lifeline")
    if (lifeline.kind == command.kind) return unapplied(document, "No change")
    return applied(
        document.copy(lifelines = document.lifelines.map { if (it.id == command.lifelineId) it.copy(kind = command.kind) else it }),
        if (command.kind == Seq3LifelineKind.ACTOR) "Set as actor" else "Set as participant",
    )
}

private fun dispatchSetLifelineDisplaySegments(document: Seq3Document, command: Seq3Command.SetLifelineDisplaySegments): Outcome {
    val lifeline = document.lifelines.firstOrNull { it.id == command.lifelineId } ?: return unapplied(document, "Unknown lifeline")
    if (lifeline.displaySegments == command.segments) return unapplied(document, "No change")
    return applied(
        document.copy(
            lifelines = document.lifelines.map { if (it.id == command.lifelineId) it.copy(displaySegments = command.segments) else it },
        ),
        "Set display name",
    )
}

private fun dispatchSetDocumentDisplaySegments(document: Seq3Document, command: Seq3Command.SetDocumentDisplaySegments): Outcome {
    val segments = command.segments.coerceAtLeast(0)
    if (document.lifelineDisplaySegments == segments) return unapplied(document, "No change")
    return applied(document.copy(lifelineDisplaySegments = segments), "Set default display name")
}

private fun dispatchSetDocumentTheme(document: Seq3Document, command: Seq3Command.SetDocumentTheme): Outcome {
    if (document.themePresetName == command.themePresetName) return unapplied(document, "No change")
    return applied(document.copy(themePresetName = command.themePresetName), "Set diagram theme")
}
