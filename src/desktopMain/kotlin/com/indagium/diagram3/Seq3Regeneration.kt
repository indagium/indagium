package com.indagium.diagram3

// ── Regenerate is a reviewed proposal, never a wholesale replace (design spec §08) ─────────────
//
// [reviewSeq3Regeneration] diffs a CURRENT document against a FRESHLY generated one (a caller runs
// `generateSeq3` again over the same range and passes both in — this file never calls the generator
// itself, keeping it a pure diff/apply pair like `diagram.ManualDiagramRegeneration`'s own review
// functions). [applySeq3Regeneration] turns the user's per-row decisions into exactly ONE new
// document — Seq3Commands.kt wraps that call as a single undo step (spec §08: "Apply is one
// undoable transaction, not 15").
//
// Matching, per this phase's brief: EVIDENCE first (shared `Seq3Occurrence.entryId`s between a
// current and a fresh message — the same real log lines, re-scanned), and only when BOTH sides of a
// candidate pair carry no evidence at all does template equality get a turn, and even then only
// when it picks out a UNIQUE fresh candidate; two equally good matches are left distinct rather than
// guessed at (see [matchEvidenceFreeMessages]'s own doc).

/** [UNCHANGED] is a matched, non-edited pair whose content is byte-identical — never shown in the
 *  review sheet's rows (nothing to review) and never counted in [Seq3RegenSummary], but STILL a row
 *  here so [applySeq3Regeneration] can reconstruct the complete message list purely by resolving
 *  [Seq3RegenReview.rows] — see that function's own doc for why iterating rows alone must be enough. */
enum class Seq3RegenChangeKind { NEW, CHANGED, REMOVED, EDITED_KEPT, UNCHANGED }

enum class Seq3RegenDecision { PENDING, ACCEPT, REJECT }

/** One review row. [current]/[fresh] are null exactly as their [kind] implies (no `current` for
 *  NEW, no `fresh` for REMOVED — unless a match happened to exist for an [EDITED_KEPT] row, kept
 *  purely so the review sheet can show what regeneration WOULD have proposed).
 *  [Seq3RegenDecision.PENDING] means "unreviewed" and [applySeq3Regeneration] treats it as the
 *  SAFE, status-quo choice for that row kind — see that function's own doc for the per-kind
 *  mapping. */
data class Seq3RegenRow(
    val id: String,
    val kind: Seq3RegenChangeKind,
    val current: Seq3Message?,
    val fresh: Seq3Message?,
    val decision: Seq3RegenDecision = Seq3RegenDecision.PENDING,
    /** Set only by [unlockSeq3RegenRow] on an [Seq3RegenChangeKind.EDITED_KEPT] row — the review
     *  sheet's own "🔒 unlock" verb (spec §08). Until this is true, [applySeq3Regeneration] keeps
     *  [current] verbatim NO MATTER WHAT [decision] says: an edited message is locked against
     *  regeneration by definition (spec §03's own "edited (locked against regeneration)"). */
    val unlocked: Boolean = false,
)

data class Seq3RegenSummary(val newCount: Int, val changedCount: Int, val removedCount: Int, val editsKeptCount: Int)

data class Seq3RegenReview(val rows: List<Seq3RegenRow>, val freshDocument: Seq3Document) {
    /** The summary chips: `"12 new · 3 changed · 2 no longer in the log · 8 of your edits kept"`
     *  (spec §08). [Seq3RegenSummary.editsKeptCount] excludes rows the user has since unlocked —
     *  those are no longer "kept" edits, they are ordinary reviewable rows. */
    val summary: Seq3RegenSummary
        get() = Seq3RegenSummary(
            newCount = rows.count { it.kind == Seq3RegenChangeKind.NEW },
            changedCount = rows.count { it.kind == Seq3RegenChangeKind.CHANGED },
            removedCount = rows.count { it.kind == Seq3RegenChangeKind.REMOVED },
            editsKeptCount = rows.count { it.kind == Seq3RegenChangeKind.EDITED_KEPT && !it.unlocked },
        )
}

private fun evidenceIds(message: Seq3Message): Set<Int> = message.occurrences.mapTo(HashSet()) { it.entryId }

/**
 * Builds the [Seq3RegenReview] for `current -> fresh`. See this file's header for the matching
 * rule. A row is emitted for EVERY current message (as [Seq3RegenChangeKind.UNCHANGED] when matched
 * and byte-identical — see that constant's own doc for why even a nothing-to-review pair still gets
 * a row) and for every unmatched fresh message ([Seq3RegenChangeKind.NEW]).
 */
fun reviewSeq3Regeneration(current: Seq3Document, fresh: Seq3Document): Seq3RegenReview {
    val freshById = fresh.messages.associateBy { it.id }
    val pairing = matchMessages(current.messages, fresh.messages)

    val rows = mutableListOf<Seq3RegenRow>()
    current.messages.forEach { c ->
        val freshMatch = pairing.freshIdByCurrentId[c.id]?.let(freshById::get)
        rows += when {
            c.authoring == Seq3Authoring.EDITED -> Seq3RegenRow(c.id, Seq3RegenChangeKind.EDITED_KEPT, c, freshMatch)
            freshMatch == null -> Seq3RegenRow(c.id, Seq3RegenChangeKind.REMOVED, c, null)
            !sameContent(c, freshMatch) -> Seq3RegenRow(c.id, Seq3RegenChangeKind.CHANGED, c, freshMatch)
            else -> Seq3RegenRow(c.id, Seq3RegenChangeKind.UNCHANGED, c, freshMatch)
        }
    }
    fresh.messages.forEach { f -> if (f.id !in pairing.matchedFreshIds) rows += Seq3RegenRow(f.id, Seq3RegenChangeKind.NEW, null, f) }
    return Seq3RegenReview(rows, fresh)
}

private fun sameContent(a: Seq3Message, b: Seq3Message): Boolean =
    a.fromLifelineId == b.fromLifelineId && a.toLifelineId == b.toLifelineId && a.labelTemplate == b.labelTemplate &&
        a.kind == b.kind && a.match.template == b.match.template && a.occurrences.size == b.occurrences.size

// ── Matching ─────────────────────────────────────────────────────────────────────────────────

private class MessagePairing(val freshIdByCurrentId: Map<String, String>, val matchedFreshIds: Set<String>)

private fun matchMessages(currentMessages: List<Seq3Message>, freshMessages: List<Seq3Message>): MessagePairing {
    val matchedCurrentIds = HashSet<String>()
    val matchedFreshIds = HashSet<String>()
    val pairs = HashMap<String, String>()

    matchByEvidence(currentMessages, freshMessages).forEach { (currentId, freshId) ->
        if (currentId in matchedCurrentIds || freshId in matchedFreshIds) return@forEach
        matchedCurrentIds += currentId
        matchedFreshIds += freshId
        pairs[currentId] = freshId
    }
    matchEvidenceFreeMessages(currentMessages, freshMessages, matchedCurrentIds, matchedFreshIds).forEach { (currentId, freshId) ->
        matchedCurrentIds += currentId
        matchedFreshIds += freshId
        pairs[currentId] = freshId
    }
    return MessagePairing(pairs, matchedFreshIds)
}

/** Greedy strongest-overlap-first pairing by shared [Seq3Occurrence.entryId]s — a current/fresh
 *  pair with more shared real log lines is a stronger match than one with fewer, and ties break
 *  deterministically on id so this never depends on list iteration order. Internal (not private) so
 *  [matchOneMessage] can reuse this exact rule for a single-message revert instead of re-deriving
 *  it — see that function's own doc. */
internal fun matchByEvidence(currentMessages: List<Seq3Message>, freshMessages: List<Seq3Message>): List<Pair<String, String>> {
    data class Candidate(val currentId: String, val freshId: String, val overlap: Int)

    val candidates = currentMessages.flatMap { c ->
        val cIds = evidenceIds(c)
        if (cIds.isEmpty()) return@flatMap emptyList<Candidate>()
        freshMessages.mapNotNull { f ->
            val overlap = cIds.intersect(evidenceIds(f)).size
            if (overlap > 0) Candidate(c.id, f.id, overlap) else null
        }
    }
    return candidates
        .sortedWith(compareByDescending<Candidate> { it.overlap }.thenBy { it.currentId }.thenBy { it.freshId })
        .map { it.currentId to it.freshId }
}

/** Fallback for messages with NO evidence at all on either side (see this file's header) — matched
 *  by template equality, but ONLY when exactly one still-unmatched fresh candidate shares the
 *  template; two-or-more candidates are left unmatched (ambiguous) rather than picked arbitrarily,
 *  per this phase's brief. Internal for the same reason as [matchByEvidence] — [matchOneMessage]
 *  reuses it rather than re-implementing the fallback rule. */
internal fun matchEvidenceFreeMessages(
    currentMessages: List<Seq3Message>,
    freshMessages: List<Seq3Message>,
    matchedCurrentIds: Set<String>,
    matchedFreshIds: Set<String>,
): List<Pair<String, String>> {
    val freeCurrent = currentMessages.filter { it.id !in matchedCurrentIds && evidenceIds(it).isEmpty() }
    val freeFresh = freshMessages.filter { it.id !in matchedFreshIds && evidenceIds(it).isEmpty() }
    val claimedFresh = HashSet<String>()
    return freeCurrent.mapNotNull { c ->
        val candidates = freeFresh.filter { it.match.template == c.match.template && it.id !in claimedFresh }
        candidates.singleOrNull()?.let { f -> claimedFresh += f.id; c.id to f.id }
    }
}

/**
 * The single-message counterpart to [matchMessages] (backs `Seq3Session.revertMessage`, phase 2's
 * "revert to generated" verb on one edited row): finds [current]'s counterpart in [freshMessages]
 * using the exact same evidence-first/unique-template-fallback rule the whole-document regeneration
 * review uses — composed from [matchByEvidence]/[matchEvidenceFreeMessages] rather than
 * re-implemented, so a one-message revert and a full regeneration review can never silently diverge
 * on what "the same message" means. Returns null when nothing in [freshMessages] qualifies (no
 * shared evidence and no unique template match) — the caller then leaves [current] untouched rather
 * than guessing.
 */
internal fun matchOneMessage(current: Seq3Message, freshMessages: List<Seq3Message>): Seq3Message? {
    val freshById = freshMessages.associateBy { it.id }
    val evidenceMatch = matchByEvidence(listOf(current), freshMessages).firstOrNull()?.second
    val freshId = evidenceMatch ?: matchEvidenceFreeMessages(listOf(current), freshMessages, emptySet(), emptySet()).firstOrNull()?.second
    return freshId?.let(freshById::get)
}

// ── Decisions ────────────────────────────────────────────────────────────────────────────────

fun withSeq3RegenDecision(review: Seq3RegenReview, rowId: String, decision: Seq3RegenDecision): Seq3RegenReview =
    review.copy(rows = review.rows.map { if (it.id == rowId) it.copy(decision = decision) else it })

private fun isLocked(row: Seq3RegenRow): Boolean = row.kind == Seq3RegenChangeKind.EDITED_KEPT && !row.unlocked

fun acceptAllSeq3Regen(review: Seq3RegenReview): Seq3RegenReview =
    review.copy(rows = review.rows.map { if (isLocked(it)) it else it.copy(decision = Seq3RegenDecision.ACCEPT) })

fun rejectAllSeq3Regen(review: Seq3RegenReview): Seq3RegenReview =
    review.copy(rows = review.rows.map { if (isLocked(it)) it else it.copy(decision = Seq3RegenDecision.REJECT) })

/** The review sheet's "🔒 unlock" verb — turns an [Seq3RegenChangeKind.EDITED_KEPT] row into an
 *  ordinary reviewable one (see [Seq3RegenRow.unlocked]'s own doc). A no-op for any other row kind
 *  or an unknown [rowId]. */
fun unlockSeq3RegenRow(review: Seq3RegenReview, rowId: String): Seq3RegenReview = review.copy(
    rows = review.rows.map { if (it.id == rowId && it.kind == Seq3RegenChangeKind.EDITED_KEPT) it.copy(unlocked = true) else it },
)

// ── Apply — ONE new document (spec §08: "one undoable transaction, not 15") ───────────────────

/**
 * Resolves every row's decision into the final message list. [Seq3Document.lifelines] is taken
 * from [Seq3RegenReview.freshDocument] — a lifeline gained or lost by the fresh scan is always
 * reflected, independent of any one row's decision, since lifelines are not themselves reviewable
 * rows. [current]'s title/sourceFile/range/fragments/notes/defaultRepeat are preserved: a fragment
 * or note bracket around a message that this apply removes is left dangling rather than silently
 * deleted — a later phase's fragment/note editing affordances are the place to clean that up
 * deliberately, not a side effect buried in this function.
 */
fun applySeq3Regeneration(current: Seq3Document, review: Seq3RegenReview): Seq3Document =
    current.copy(lifelines = review.freshDocument.lifelines, messages = review.rows.mapNotNull(::resolveRow))

private fun resolveRow(row: Seq3RegenRow): Seq3Message? = when (row.kind) {
    Seq3RegenChangeKind.UNCHANGED -> row.current
    Seq3RegenChangeKind.EDITED_KEPT -> if (row.unlocked) resolveDecided(row) else row.current
    Seq3RegenChangeKind.NEW -> if (row.decision == Seq3RegenDecision.ACCEPT) row.fresh else null
    Seq3RegenChangeKind.CHANGED, Seq3RegenChangeKind.REMOVED -> resolveDecided(row)
}

/** ACCEPT means "take regeneration's proposal" — the fresh message for a CHANGED row, or the
 *  removal (null) for a REMOVED row. REJECT and the safe PENDING default both mean "keep mine". */
private fun resolveDecided(row: Seq3RegenRow): Seq3Message? = when (row.decision) {
    Seq3RegenDecision.ACCEPT -> row.fresh
    Seq3RegenDecision.REJECT, Seq3RegenDecision.PENDING -> row.current
}
