package com.indagium.diagram3

// ── Pure queue filter / sort / selection / bulk-action logic ───────────────────────────────────
//
// No UI. Design spec §04 (the panel row list) and §06 (selection verbs) distilled into functions
// over [Seq3Document] and plain ids — `ui.Seq3QueuePanel` (phase 4) is a thin composable shell
// around this file, exactly like `diagram.ManualDiagramMessageQueue` was for the v1/v2 panel.
//
// The one rule every function here is written to protect: **sort is a VIEW, never an edit**
// (spec §07's own words). Every `seq3*Sort*` function returns a NEW list built from
// [Seq3Document.messages] — it never calls `document.copy(messages = ...)`. Only the bulk-action
// and order-pin functions below (which are explicit user EDITS, not sorts) ever return a document.

// ── Filter chips (spec §04) ─────────────────────────────────────────────────────────────────

enum class Seq3Filter { ALL, NEEDS_TARGET, EDITED, HIDDEN }

data class Seq3FilterCounts(val all: Int, val needsTarget: Int, val edited: Int, val hidden: Int)

fun seq3FilterCounts(document: Seq3Document): Seq3FilterCounts = Seq3FilterCounts(
    all = document.messages.size,
    needsTarget = document.messages.count { it.state == Seq3State.NEEDS_TARGET },
    edited = document.messages.count { it.authoring == Seq3Authoring.EDITED },
    hidden = document.messages.count { it.visibility == Seq3Visibility.HIDDEN },
)

private fun passesFilter(message: Seq3Message, filter: Seq3Filter): Boolean = when (filter) {
    Seq3Filter.ALL -> true
    Seq3Filter.NEEDS_TARGET -> message.state == Seq3State.NEEDS_TARGET
    Seq3Filter.EDITED -> message.authoring == Seq3Authoring.EDITED
    Seq3Filter.HIDDEN -> message.visibility == Seq3Visibility.HIDDEN
}

/** Matches [Seq3Message.labelTemplate] or the underlying [Seq3Match.template] — the two can differ
 *  once a label has been renamed independently of its match pattern (Seq3Message's own doc). */
private fun passesTextFilter(message: Seq3Message, text: String): Boolean {
    if (text.isBlank()) return true
    return message.labelTemplate.contains(text, ignoreCase = true) || message.match.template.contains(text, ignoreCase = true)
}

// ── Sort (spec §07: a VIEW, never an edit — see this file's header) ────────────────────────────

enum class Seq3Sort { LOG_ORDER, LIFELINE, OCCURRENCES, STATE }

private fun firstTimestamp(message: Seq3Message): Long = message.primaryTimestampMillis ?: Long.MAX_VALUE

private fun stateSortRank(state: Seq3State): Int = when (state) {
    Seq3State.NEEDS_TARGET -> 0
    Seq3State.EDITED -> 1
    Seq3State.AUTO -> 2
}

/**
 * Filters (chip + text) [document]'s messages, then arranges them into [sort]'s VIEW order.
 * [Seq3Sort.LOG_ORDER] returns the filtered messages in [Seq3Document.messages]'s own order — that
 * list is already log-clock order (Seq3Generator's own doc) with any [nudgeSeq3OrderPin] applied,
 * so this never re-derives it. The other three sorts build a NEW list; they never touch
 * [document].
 */
fun seq3QueueRows(document: Seq3Document, filter: Seq3Filter, textFilter: String = "", sort: Seq3Sort = Seq3Sort.LOG_ORDER): List<Seq3Message> {
    val filtered = document.messages.filter { passesFilter(it, filter) && passesTextFilter(it, textFilter) }
    return when (sort) {
        Seq3Sort.LOG_ORDER -> filtered
        Seq3Sort.LIFELINE -> filtered.sortedWith(compareBy({ it.fromLifelineId }, ::firstTimestamp))
        Seq3Sort.OCCURRENCES -> filtered.sortedWith(compareByDescending { it.occurrences.size })
        Seq3Sort.STATE -> filtered.sortedWith(compareBy({ stateSortRank(it.state) }, ::firstTimestamp))
    }
}

// ── Selection (spec §06: click / ⇧click range / ⌘click additive) ───────────────────────────────

data class Seq3Selection(val selectedIds: Set<String> = emptySet(), val anchorId: String? = null)

/**
 * Pure selection semantics over the CURRENTLY VISIBLE (filtered+sorted) id list — a caller re-runs
 * this against whatever [seq3QueueRows] just produced, so filtering/sorting can change without
 * disturbing [Seq3Selection.anchorId]'s meaning (mirrors
 * `diagram.selectManualQueueMessageIds`'s own contract). A click on an id that scrolled out of the
 * current filter is a no-op — the previous selection/anchor pass through unchanged.
 */
fun seq3Select(
    visibleIds: List<String>,
    current: Seq3Selection,
    clickedId: String,
    additive: Boolean = false,
    range: Boolean = false,
): Seq3Selection {
    if (clickedId !in visibleIds) return current
    val anchorVisible = current.anchorId in visibleIds
    val next = when {
        range && anchorVisible -> {
            val start = visibleIds.indexOf(current.anchorId)
            val end = visibleIds.indexOf(clickedId)
            val span = visibleIds.subList(minOf(start, end), maxOf(start, end) + 1).toSet()
            if (additive) current.selectedIds + span else span
        }
        additive -> if (clickedId in current.selectedIds) current.selectedIds - clickedId else current.selectedIds + clickedId
        else -> setOf(clickedId)
    }
    val nextAnchor = if (range && anchorVisible) current.anchorId else clickedId
    return Seq3Selection(next, nextAnchor)
}

// ── Bulk verbs (spec §06) ───────────────────────────────────────────────────────────────────
//
// Deliberately NOT here, per the design spec's own "Never here" list: bulk delete, bulk reorder,
// bulk pattern edit. Every verb below is additive/edit-in-place; nothing removes a message from
// the document or changes its position.

sealed class Seq3BulkAction {
    data class SetFrom(val lifelineId: String) : Seq3BulkAction()

    data class SetTo(val lifelineId: String?) : Seq3BulkAction()

    data class Merge(val mergedId: String) : Seq3BulkAction()

    data class Group(val fragment: Seq3Fragment) : Seq3BulkAction()

    data object Hide : Seq3BulkAction()

    data object Show : Seq3BulkAction()

    data class Note(val note: Seq3Note) : Seq3BulkAction()

    // ── Fragment/note rename (spec §06's `Group ▾`/`Note` are add-only; these are the missing
    //    edit-in-place counterparts) ───────────────────────────────────────────────────────────
    //
    // Unlike [Group]/[Note] above — which build a NEW fragment/note spanning the CURRENT message
    // selection — these two identify the EXISTING fragment/note to rename by its own id, entirely
    // independent of whatever messages happen to be selected when the rename fires. They are still
    // routed through [Seq3Command.Bulk]/[applySeq3BulkAction] like every other verb (spec: "every
    // editing verb goes through Seq3Session.applyCommand"), just with `selectedIds` unused by the
    // action itself — mirrors how [Seq3BulkAction.Merge]'s `mergedId` already names a specific
    // target independent of the selection's own ids.

    /** Renames an EXISTING fragment's label in place. A no-op (unapplied) for an unknown
     *  [fragmentId] — see [applySeq3BulkAction]'s own "invalid selection is always a safe no-op"
     *  contract, extended here to "invalid target id" for a verb that isn't selection-keyed. */
    data class SetFragmentLabel(val fragmentId: String, val label: String) : Seq3BulkAction()

    /** Renames an EXISTING note's text in place. Same unknown-id-is-a-safe-no-op contract as
     *  [SetFragmentLabel]. */
    data class SetNoteText(val noteId: String, val text: String) : Seq3BulkAction()

    // ── Single-message field edits (Seq3Inspector, phase 4 — spec §03) ─────────────────────────
    //
    // These route the Inspector's per-message controls through the SAME bulk pipeline as every
    // other verb (usually called with a singleton `selectedIds`, but nothing here requires that) —
    // "every editing verb goes through Seq3Session.applyCommand", never a bespoke command type per
    // field. All three stamp EDITED via [editMessages], exactly like [SetFrom]/[SetTo] already do:
    // a hand-adjusted kind/pattern/repeat policy must survive a later regeneration too.

    /** Inspector's kind segmented control. Switching TO [Seq3Kind.SELF] also snaps `toLifelineId`
     *  to the message's own `fromLifelineId` (mirrors [applySeq3GuidedSelfCall]) so a self-call
     *  never reads as needs-target; switching AWAY from SELF leaves `toLifelineId` exactly as it
     *  was — the user's own `to` choice for a call/return/async/note is never second-guessed here. */
    data class SetKind(val kind: Seq3Kind) : Seq3BulkAction()

    /** Inspector's pattern field (the power-user escape hatch, spec §03) — replaces the WHOLE
     *  match/label pair. [match] and [labelTemplate] are taken as already-validated by the caller
     *  (UI parses `{name}` tokens out of the typed template); this only rejects a blank template. */
    data class SetPattern(val match: Seq3Match, val labelTemplate: String) : Seq3BulkAction()

    /** Canvas double-click inline label editor (spec §04) — renames the arrow text WITHOUT
     *  touching the underlying [Seq3Message.match], the lighter-weight sibling of [SetPattern]. */
    data class SetLabel(val labelTemplate: String) : Seq3BulkAction()

    /** Inspector's repeats control: collapse-above-N / every / first+last (spec §03).
     *  [threshold] only matters for [Seq3Repeat.COLLAPSE_ABOVE] (mirrors
     *  [Seq3Message.repeatThreshold]'s own doc) but is always required here to keep this action's
     *  shape total rather than silently reusing whatever threshold a message happened to have. */
    data class SetRepeat(val repeat: Seq3Repeat, val threshold: Int) : Seq3BulkAction()
}

data class Seq3BulkResult(val document: Seq3Document, val applied: Boolean, val reason: String? = null)

private fun unapplied(document: Seq3Document, reason: String) = Seq3BulkResult(document, applied = false, reason = reason)

/**
 * Applies [action] to every message in [selectedIds], or leaves [document] byte-identical (an
 * unapplied result carrying [Seq3BulkResult.reason]) when the selection or payload is invalid —
 * the same "invalid selection is always a safe no-op" contract
 * `diagram.applyManualMessageBulkAction` held itself to.
 */
fun applySeq3BulkAction(document: Seq3Document, selectedIds: Set<String>, action: Seq3BulkAction): Seq3BulkResult {
    val selected = document.messages.filter { it.id in selectedIds }
    // [SetFragmentLabel]/[SetNoteText] name their target by [fragmentId]/[noteId], not by the
    // message selection (see those variants' own doc) — "select at least one message" would be a
    // pointless block on a rename that never reads `selectedIds` at all.
    if (selected.isEmpty() && action !is Seq3BulkAction.SetFragmentLabel && action !is Seq3BulkAction.SetNoteText) {
        return unapplied(document, "Select at least one message")
    }
    return when (action) {
        is Seq3BulkAction.SetFrom -> applySetFrom(document, selectedIds, action)
        is Seq3BulkAction.SetTo -> applySetTo(document, selectedIds, action)
        is Seq3BulkAction.Merge -> applyMerge(document, selected, action)
        is Seq3BulkAction.Group -> applyGroup(document, selectedIds, action)
        Seq3BulkAction.Hide -> applyVisibility(document, selectedIds, Seq3Visibility.HIDDEN)
        Seq3BulkAction.Show -> applyVisibility(document, selectedIds, Seq3Visibility.VISIBLE)
        is Seq3BulkAction.Note -> applyNote(document, selectedIds, action)
        is Seq3BulkAction.SetFragmentLabel -> applySetFragmentLabel(document, action)
        is Seq3BulkAction.SetNoteText -> applySetNoteText(document, action)
        is Seq3BulkAction.SetKind -> applySetKind(document, selectedIds, action)
        is Seq3BulkAction.SetPattern -> applySetPattern(document, selectedIds, action)
        is Seq3BulkAction.SetLabel -> applySetLabel(document, selectedIds, action)
        is Seq3BulkAction.SetRepeat -> applySetRepeat(document, selectedIds, action)
    }
}

private fun editMessages(document: Seq3Document, selectedIds: Set<String>, transform: (Seq3Message) -> Seq3Message): Seq3Document =
    document.copy(messages = document.messages.map { if (it.id in selectedIds) transform(it).copy(authoring = Seq3Authoring.EDITED) else it })

private fun applySetFrom(document: Seq3Document, selectedIds: Set<String>, action: Seq3BulkAction.SetFrom): Seq3BulkResult {
    if (document.lifelines.none { it.id == action.lifelineId }) return unapplied(document, "Unknown source lifeline")
    return Seq3BulkResult(editMessages(document, selectedIds) { it.copy(fromLifelineId = action.lifelineId) }, applied = true)
}

private fun applySetTo(document: Seq3Document, selectedIds: Set<String>, action: Seq3BulkAction.SetTo): Seq3BulkResult {
    if (action.lifelineId != null && document.lifelines.none { it.id == action.lifelineId }) return unapplied(document, "Unknown target lifeline")
    return Seq3BulkResult(
        editMessages(document, selectedIds) { m ->
            // Picking a message's own `from` as its `to` must read as a self-call, not an ordinary
            // arrow pointing at itself — same auto-flip [applySeq3GuidedTarget] performs for the
            // guided pass, mirrored here for the Inspector's bulk "Set target" verb.
            m.copy(toLifelineId = action.lifelineId, kind = if (action.lifelineId == m.fromLifelineId) Seq3Kind.SELF else m.kind)
        },
        applied = true,
    )
}

private fun applySetKind(document: Seq3Document, selectedIds: Set<String>, action: Seq3BulkAction.SetKind): Seq3BulkResult =
    Seq3BulkResult(
        editMessages(document, selectedIds) { m ->
            m.copy(kind = action.kind, toLifelineId = if (action.kind == Seq3Kind.SELF) m.fromLifelineId else m.toLifelineId)
        },
        applied = true,
    )

private fun applySetPattern(document: Seq3Document, selectedIds: Set<String>, action: Seq3BulkAction.SetPattern): Seq3BulkResult {
    if (action.match.template.isBlank() || action.labelTemplate.isBlank()) return unapplied(document, "Pattern and label are required")
    return Seq3BulkResult(
        editMessages(document, selectedIds) { it.copy(match = action.match, labelTemplate = action.labelTemplate) },
        applied = true,
    )
}

private fun applySetLabel(document: Seq3Document, selectedIds: Set<String>, action: Seq3BulkAction.SetLabel): Seq3BulkResult {
    if (action.labelTemplate.isBlank()) return unapplied(document, "Label is required")
    return Seq3BulkResult(editMessages(document, selectedIds) { it.copy(labelTemplate = action.labelTemplate) }, applied = true)
}

private fun applySetRepeat(document: Seq3Document, selectedIds: Set<String>, action: Seq3BulkAction.SetRepeat): Seq3BulkResult {
    if (action.threshold <= 0) return unapplied(document, "Repeat threshold must be positive")
    return Seq3BulkResult(
        editMessages(document, selectedIds) { it.copy(repeat = action.repeat, repeatThreshold = action.threshold) },
        applied = true,
    )
}

private fun applyVisibility(document: Seq3Document, selectedIds: Set<String>, visibility: Seq3Visibility): Seq3BulkResult =
    // Visibility is intentionally NOT routed through editMessages' EDITED stamp: hiding/showing a
    // message must not itself flip an otherwise-AUTO message into EDITED (spec §03: "Hidden is a
    // separate visibility flag, not a state"), so a later regeneration still treats it as ordinary.
    Seq3BulkResult(
        document.copy(messages = document.messages.map { if (it.id in selectedIds) it.copy(visibility = visibility) else it }),
        applied = true,
    )

private fun applyGroup(document: Seq3Document, selectedIds: Set<String>, action: Seq3BulkAction.Group): Seq3BulkResult {
    val fragment = action.fragment
    return when {
        fragment.id.isBlank() || fragment.label.isBlank() -> unapplied(document, "Fragment id and label are required")
        document.fragments.any { it.id == fragment.id } -> unapplied(document, "Fragment id already exists")
        fragment.messageIds.toSet() != selectedIds -> unapplied(document, "Fragment must contain exactly the selected messages")
        else -> Seq3BulkResult(document.copy(fragments = document.fragments + fragment), applied = true)
    }
}

private fun applyNote(document: Seq3Document, selectedIds: Set<String>, action: Seq3BulkAction.Note): Seq3BulkResult {
    val note = action.note
    return when {
        note.id.isBlank() || note.text.isBlank() -> unapplied(document, "Note id and text are required")
        document.notes.any { it.id == note.id } -> unapplied(document, "Note id already exists")
        note.messageIds.isEmpty() || !selectedIds.containsAll(note.messageIds) -> unapplied(document, "Note must span selected messages")
        else -> Seq3BulkResult(document.copy(notes = document.notes + note), applied = true)
    }
}

/** Renames an EXISTING fragment's label — the edit-in-place counterpart [applyGroup] doesn't have
 *  (see [Seq3BulkAction.SetFragmentLabel]'s own doc for why this targets [action.fragmentId]
 *  rather than the message selection). A safe no-op for an unknown id or a blank label. */
private fun applySetFragmentLabel(document: Seq3Document, action: Seq3BulkAction.SetFragmentLabel): Seq3BulkResult {
    if (document.fragments.none { it.id == action.fragmentId }) return unapplied(document, "Unknown fragment")
    if (action.label.isBlank()) return unapplied(document, "Fragment label is required")
    return Seq3BulkResult(
        document.copy(fragments = document.fragments.map { if (it.id == action.fragmentId) it.copy(label = action.label) else it }),
        applied = true,
    )
}

/** Renames an EXISTING note's text — the edit-in-place counterpart [applyNote] doesn't have. Same
 *  unknown-id/blank-text safe-no-op contract as [applySetFragmentLabel]. */
private fun applySetNoteText(document: Seq3Document, action: Seq3BulkAction.SetNoteText): Seq3BulkResult {
    if (document.notes.none { it.id == action.noteId }) return unapplied(document, "Unknown note")
    if (action.text.isBlank()) return unapplied(document, "Note text is required")
    return Seq3BulkResult(
        document.copy(notes = document.notes.map { if (it.id == action.noteId) it.copy(text = action.text) else it }),
        applied = true,
    )
}

/**
 * Collapses [selected] into ONE message whose combined occurrences are re-tokenized (Seq3Tokenizer)
 * into a single proven pattern — "differing runs become tokens automatically" (spec §06). Requires
 * identical `from`/`to`/`kind` across the selection (otherwise "merge" would silently redirect an
 * arrow) and a tag shared by construction: every occurrence in a Seq3Document was scanned under its
 * OWN `fromLifelineId`'s tag, so a from-mismatch is rejected before the tokenizer ever runs.
 *
 * REVERSIBLE (spec §06): merging never deletes information — every source occurrence, with its
 * original entryId/timestamps/text, survives inside the merged message's [Seq3Message.occurrences].
 * A caller (Seq3Commands) restoring the pre-merge document via undo therefore loses nothing; there
 * is no separate "unmerge" needed here for that guarantee to hold. Fragments/notes that referenced
 * one of the now-removed source ids are repointed at [Seq3BulkAction.Merge.mergedId] so a merge
 * never orphans a fragment bracket or note anchor.
 */
private fun applyMerge(document: Seq3Document, selected: List<Seq3Message>, action: Seq3BulkAction.Merge): Seq3BulkResult {
    val first = selected.first()
    if (selected.any { it.fromLifelineId != first.fromLifelineId || it.toLifelineId != first.toLifelineId || it.kind != first.kind }) {
        return unapplied(document, "Merged messages must share the same From, To, and kind")
    }
    val occurrences = selected.flatMap { it.occurrences }.sortedBy { it.entryId }
    if (occurrences.isEmpty()) return unapplied(document, "Nothing to merge")
    val tokenizeResult = tokenizeSeq3Messages(first.match.tag, occurrences.map { Seq3TokenizeInput(it.entryId.toString(), it.text) })
    val match = tokenizeResult.match ?: return unapplied(document, tokenizeResult.error ?: "Selected messages do not share a provable pattern")
    val mergedOccurrences = occurrences.map { it.copy(captureValues = tokenizeResult.captureValuesByOccurrence[it.entryId.toString()].orEmpty()) }
    val mergedId = action.mergedId.ifBlank { first.id }
    val merged = first.copy(id = mergedId, match = match, labelTemplate = match.template, authoring = Seq3Authoring.EDITED, occurrences = mergedOccurrences)
    val removedIds = selected.map { it.id }.toSet()
    val repointIds = { ids: List<String> -> ids.map { if (it in removedIds) mergedId else it }.distinct() }
    return Seq3BulkResult(
        document.copy(
            messages = document.messages.filterNot { it.id in removedIds } + merged,
            fragments = document.fragments.map { it.copy(messageIds = repointIds(it.messageIds)) },
            notes = document.notes.map { it.copy(messageIds = repointIds(it.messageIds)) },
        ),
        applied = true,
    )
}

// ── Order pin (spec §07: only between messages that genuinely tie on a timestamp) ──────────────

enum class Seq3PinDirection { UP, DOWN }

data class Seq3PinResult(val document: Seq3Document, val applied: Boolean, val reason: String? = null)

/**
 * Nudges [messageId] one step against its immediate [direction] neighbour in [Seq3Document.messages]
 * — swapping their positions and stamping both with a [Seq3OrderPin] recording the shared timestamp
 * they tied on. Rejected (unapplied, with [Seq3PinResult.reason]) for anything that is not a
 * genuine tie: an out-of-range neighbour, a message with no parseable first-occurrence timestamp, or
 * two messages whose timestamps merely happen to be close but not IDENTICAL. This is the one and
 * only way order changes in v3 — "remove drag-reorder on messages entirely; order is evidence, not
 * layout" (spec §07) — so there is no general reorder function anywhere in this package.
 */
fun nudgeSeq3OrderPin(document: Seq3Document, messageId: String, direction: Seq3PinDirection): Seq3PinResult {
    val idx = document.messages.indexOfFirst { it.id == messageId }
    if (idx < 0) return Seq3PinResult(document, false, "Unknown message")
    val neighborIdx = if (direction == Seq3PinDirection.UP) idx - 1 else idx + 1
    if (neighborIdx !in document.messages.indices) return Seq3PinResult(document, false, "No neighbouring message in that direction")
    val message = document.messages[idx]
    val neighbor = document.messages[neighborIdx]
    val ts = message.primaryTimestampMillis
    val neighborTs = neighbor.primaryTimestampMillis
    if (ts == null || ts != neighborTs) {
        return Seq3PinResult(document, false, "Order can only be pinned between messages that share an exact timestamp")
    }
    val reordered = document.messages.toMutableList()
    reordered[idx] = neighbor.copy(orderPin = Seq3OrderPin(ts, tieRank = idx))
    reordered[neighborIdx] = message.copy(orderPin = Seq3OrderPin(ts, tieRank = neighborIdx))
    return Seq3PinResult(document.copy(messages = reordered), true)
}

/** "One click reverts" (spec §07): clears [messageId]'s pin. Position is left as the pin last put
 *  it — reverting the BADGE, not silently re-nudging the row a second time; a user who wants the
 *  original order back nudges the opposite direction, which is exactly the same one-step operation
 *  that created the pin. */
fun clearSeq3OrderPin(document: Seq3Document, messageId: String): Seq3Document =
    document.copy(messages = document.messages.map { if (it.id == messageId) it.copy(orderPin = null) else it })
