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
    data class ReplaceMessage(val messageId: String, val replacement: Seq3Message) : Seq3Command()

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

    /** Hides or shows one occurrence without changing any of its sibling occurrences. */
    data class SetOccurrenceVisibility(
        val messageId: String,
        val entryId: Int,
        val visibility: Seq3Visibility,
    ) : Seq3Command()

    data class ReorderLifelines(val orderedLifelineIds: List<String>) : Seq3Command()

    data class RenameLifeline(val lifelineId: String, val name: String) : Seq3Command()

    data class MergeLifelines(val keepLifelineId: String, val mergedLifelineId: String) : Seq3Command()
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
    is Seq3Command.SetOccurrenceVisibility -> dispatchSetOccurrenceVisibility(document, command)
    is Seq3Command.ReorderLifelines -> dispatchReorder(document, command)
    is Seq3Command.RenameLifeline -> dispatchRename(document, command)
    is Seq3Command.MergeLifelines -> dispatchMergeLifelines(document, command)
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
    is Seq3BulkAction.SetFragmentLabel -> "Rename fragment"
    is Seq3BulkAction.SetNoteText -> "Rename note"
    is Seq3BulkAction.SetKind -> "Set kind"
    is Seq3BulkAction.SetPattern -> "Set pattern"
    is Seq3BulkAction.SetLabel -> "Rename label"
    is Seq3BulkAction.SetRepeat -> "Set repeat"
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
    val replaced = document.copy(messages = document.messages.map { if (it.id == command.messageId) replacement else it })
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

private fun dispatchSetOccurrenceVisibility(document: Seq3Document, command: Seq3Command.SetOccurrenceVisibility): Outcome =
    when (val result = setSeq3OccurrenceVisibility(document, command.messageId, command.entryId, command.visibility)) {
        is Seq3MessageEditResult.Updated -> if (result.document == document) {
            unapplied(document, "No change")
        } else {
            applied(result.document, "Set occurrence visibility")
        }
        is Seq3MessageEditResult.Rejected -> unapplied(document, result.reason)
    }

private fun dispatchReorder(document: Seq3Document, command: Seq3Command.ReorderLifelines): Outcome {
    if (command.orderedLifelineIds.toSet() != document.lifelines.map { it.id }.toSet()) return unapplied(document, "Must list every lifeline exactly once")
    val ordinalById = command.orderedLifelineIds.withIndex().associate { (i, id) -> id to i }
    val reordered = document.copy(lifelines = document.lifelines.map { it.copy(ordinal = ordinalById.getValue(it.id)) })
    return applied(reordered, "Reorder lifelines")
}

private fun dispatchRename(document: Seq3Document, command: Seq3Command.RenameLifeline): Outcome {
    if (command.name.isBlank()) return unapplied(document, "Lifeline name is required")
    if (document.lifelines.none { it.id == command.lifelineId }) return unapplied(document, "Unknown lifeline")
    val renamed = document.copy(lifelines = document.lifelines.map { if (it.id == command.lifelineId) it.copy(name = command.name) else it })
    return applied(renamed, "Rename lifeline")
}

/** "merge two lifelines that are the same actor under two tags" (spec §07): [command.mergedLifelineId]'s
 *  `tagIds` fold into [command.keepLifelineId], every message endpoint referencing the merged id is
 *  repointed, and the merged lifeline is dropped from the document. */
private fun dispatchMergeLifelines(document: Seq3Document, command: Seq3Command.MergeLifelines): Outcome {
    val keepId = command.keepLifelineId
    val mergeId = command.mergedLifelineId
    if (keepId == mergeId) return unapplied(document, "Cannot merge a lifeline with itself")
    val keep = document.lifelines.firstOrNull { it.id == keepId } ?: return unapplied(document, "Unknown lifeline to keep")
    val merge = document.lifelines.firstOrNull { it.id == mergeId } ?: return unapplied(document, "Unknown lifeline to merge")
    val mergedLifeline = keep.copy(tagIds = keep.tagIds + merge.tagIds)

    fun repoint(id: String?): String? = if (id == mergeId) keepId else id
    val result = document.copy(
        lifelines = document.lifelines.filterNot { it.id == mergeId }.map { if (it.id == keepId) mergedLifeline else it },
        messages = document.messages.map { it.copy(fromLifelineId = repoint(it.fromLifelineId) ?: it.fromLifelineId, toLifelineId = repoint(it.toLifelineId)) },
    )
    return applied(result, "Merge lifelines")
}
