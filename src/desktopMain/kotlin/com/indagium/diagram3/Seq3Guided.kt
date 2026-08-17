package com.indagium.diagram3

import com.indagium.model.LogEntry

// ── Guided pass state machine (design spec §05 "Fix these") ───────────────────────────────────
//
// A MODE, not a dialog — "keeps position, progress and keyboard across six fixes" (spec's own
// words). This file owns only the pure state; `ui.Seq3GuidedPass` (phase 5) is the composable that
// renders it and wires the `1`-`9`/`S`/`Esc` keys onto the functions below.

/** [messageIds] is the REMAINING queue for this pass, current = the first entry — see
 *  [advanceSeq3GuidedPass]'s own doc for why a visited id is never re-inserted. [totalAtStart]
 *  never changes for the life of one pass, so `"n / totalAtStart"` progress stays stable even as
 *  [messageIds] shrinks. */
data class Seq3GuidedPassState(val messageIds: List<String>, val totalAtStart: Int) {
    val currentMessageId: String? get() = messageIds.firstOrNull()
    val remaining: Int get() = messageIds.size
    val completedCount: Int get() = (totalAtStart - messageIds.size).coerceIn(0, totalAtStart)
}

/** Starts a pass over every currently [Seq3State.NEEDS_TARGET] message, in [Seq3Document.messages]'
 *  own (log-clock) order. Returns null when there is nothing to fix — the amber banner this backs
 *  disappears at zero (spec §04), so there is no such thing as an empty pass to enter. */
fun beginSeq3GuidedPass(document: Seq3Document): Seq3GuidedPassState? {
    val ids = document.messages.filter { it.state == Seq3State.NEEDS_TARGET }.map { it.id }
    return ids.takeIf { it.isNotEmpty() }?.let { Seq3GuidedPassState(it, it.size) }
}

/**
 * Call after resolving OR skipping [Seq3GuidedPassState.currentMessageId]. Always drops the current
 * id from the queue — mirrors `diagram.advanceGuidedTargetPass`'s own rule that "a skipped group ...
 * is never revisited during this pass" — and additionally drops any id [document] no longer reports
 * as [Seq3State.NEEDS_TARGET] (resolved by this step, or by an edit made outside the pass). Returns
 * null once the queue empties, ending the pass rather than wrapping back to the first row.
 */
fun advanceSeq3GuidedPass(document: Seq3Document, state: Seq3GuidedPassState): Seq3GuidedPassState? {
    val stillUnresolved = document.messages.asSequence().filter { it.state == Seq3State.NEEDS_TARGET }.mapTo(linkedSetOf()) { it.id }
    val remaining = state.messageIds.drop(1).filter { it in stillUnresolved }
    return remaining.takeIf { it.isNotEmpty() }?.let { Seq3GuidedPassState(it, state.totalAtStart) }
}

fun seq3GuidedCurrentMessage(document: Seq3Document, state: Seq3GuidedPassState): Seq3Message? =
    state.currentMessageId?.let { id -> document.messages.firstOrNull { it.id == id } }

// ── Target suggestion — pre-selected, NEVER auto-applied (spec §05) ────────────────────────────

private const val DEFAULT_SUGGESTION_WINDOW = 25

/**
 * The suggested target lifeline for [message]: the next entry in [entries] AFTER its first
 * occurrence, on the SAME OS thread (real, non-zero pid+tid match — same guard
 * `Seq3Correlation.isThreadHandoff` uses and for the same reason: a brief/RAW logcat with no
 * pid/tid would otherwise "suggest" an arbitrary later tag), within [window] entries, whose tag
 * belongs to a DIFFERENT lifeline than [message]'s own `from`. Returns null when nothing in the
 * window qualifies — the caller then shows no pre-selection, never a fabricated guess.
 *
 * This is a SUGGESTION only: nothing here mutates [document] or [message] — see
 * [applySeq3GuidedTarget] for the one function that actually applies a choice, always explicitly.
 */
fun suggestSeq3Target(
    message: Seq3Message,
    document: Seq3Document,
    entries: List<LogEntry>,
    window: Int = DEFAULT_SUGGESTION_WINDOW,
): Seq3Lifeline? {
    val anchorEntryId = message.occurrences.firstOrNull()?.entryId ?: return null
    val anchorIndex = entries.indexOfFirst { it.id == anchorEntryId }
    if (anchorIndex < 0) return null
    val anchor = entries[anchorIndex]
    if (anchor.pid == 0 || anchor.tid == 0) return null
    val searchEnd = minOf(anchorIndex + window, entries.lastIndex)
    return ((anchorIndex + 1)..searchEnd).asSequence()
        .map { entries[it] }
        .filter { it.tag != anchor.tag && it.pid == anchor.pid && it.tid == anchor.tid }
        .mapNotNull { candidate -> document.lifelines.firstOrNull { candidate.tag in it.tagIds } }
        .firstOrNull { it.id != message.fromLifelineId }
}

/** The three log lines the guided pass's "Surrounding lines" box shows (spec §05), centered on
 *  [message]'s first occurrence. Any side missing (message at the very start/end of [entries], or
 *  the anchor entry no longer present) is null rather than throwing. */
data class Seq3GuidedContext(val previous: LogEntry?, val current: LogEntry?, val next: LogEntry?)

fun seq3GuidedContext(message: Seq3Message, entries: List<LogEntry>): Seq3GuidedContext {
    val anchorEntryId = message.occurrences.firstOrNull()?.entryId ?: return Seq3GuidedContext(null, null, null)
    val idx = entries.indexOfFirst { it.id == anchorEntryId }
    if (idx < 0) return Seq3GuidedContext(null, null, null)
    return Seq3GuidedContext(entries.getOrNull(idx - 1), entries.getOrNull(idx), entries.getOrNull(idx + 1))
}

// ── Applying a choice — always explicit (spec: "never auto-applied") ───────────────────────────

/**
 * Sets [messageId]'s target to [lifelineId]. When [applyToAllOccurrences] (the footer's
 * default-checked "Apply to all ×n occurrences") is true, the WHOLE message's target changes in
 * place — the common case, since every [Seq3Message] already groups all of its evidence under one
 * `to`. When false, only the message's FIRST occurrence is split into its own new, resolved
 * message (id `"<id>:resolved:<entryId>"`); the remainder stays under the original id, still
 * [Seq3State.NEEDS_TARGET] — mirrors `diagram.setManualMessageTargetForOccurrences`'s own partial-
 * resolution split, adapted to v3's single durable message (no separate interaction list to edit).
 * Either way, accepting a suggestion marks the touched message(s) [Seq3Authoring.EDITED] so a later
 * regeneration will not undo the choice (spec §05's own closing line). Choosing the message's OWN
 * `from` lifeline as its target also snaps [Seq3Message.kind] to [Seq3Kind.SELF], the same auto-flip
 * [applySeq3GuidedSelfCall] performs explicitly — a manually-picked same/same target must never read
 * as an ordinary arrow pointing at itself.
 */
fun applySeq3GuidedTarget(document: Seq3Document, messageId: String, lifelineId: String, applyToAllOccurrences: Boolean = true): Seq3Document {
    val message = document.messages.firstOrNull { it.id == messageId } ?: return document
    val kind = if (lifelineId == message.fromLifelineId) Seq3Kind.SELF else message.kind
    if (applyToAllOccurrences || message.occurrences.size <= 1) {
        return document.copy(messages = document.messages.map {
            if (it.id == messageId) it.copy(toLifelineId = lifelineId, kind = kind, authoring = Seq3Authoring.EDITED) else it
        })
    }
    val first = message.occurrences.first()
    val resolved = message.copy(
        id = "$messageId:resolved:${first.entryId}",
        occurrences = listOf(first),
        toLifelineId = lifelineId,
        kind = kind,
        authoring = Seq3Authoring.EDITED,
    )
    val remaining = message.copy(occurrences = message.occurrences.drop(1))
    return document.copy(messages = document.messages.flatMap { if (it.id == messageId) listOf(remaining, resolved) else listOf(it) })
}

/** "Make it a self-call" (spec §05): [messageId]'s target becomes its own `from` lifeline and its
 *  kind becomes [Seq3Kind.SELF]. */
fun applySeq3GuidedSelfCall(document: Seq3Document, messageId: String): Seq3Document = document.copy(
    messages = document.messages.map {
        if (it.id == messageId) it.copy(toLifelineId = it.fromLifelineId, kind = Seq3Kind.SELF, authoring = Seq3Authoring.EDITED) else it
    },
)

/** "＋ New lifeline" (spec §05): appends [newLifeline] (caller assigns id/ordinal) without
 *  assigning it to [messageId]. Adding a candidate is deliberately separate from choosing a
 *  target: the guided pass must never create a new lifeline and silently retarget the current
 *  message before the user confirms that choice. A duplicate [Seq3Lifeline.id] is rejected
 *  (returns [document] unchanged) rather than silently overwriting an existing column. */
fun applySeq3GuidedNewLifeline(document: Seq3Document, messageId: String, newLifeline: Seq3Lifeline): Seq3Document {
    if (document.messages.none { it.id == messageId } || document.lifelines.any { it.id == newLifeline.id }) return document
    return document.copy(lifelines = document.lifelines + newLifeline)
}
