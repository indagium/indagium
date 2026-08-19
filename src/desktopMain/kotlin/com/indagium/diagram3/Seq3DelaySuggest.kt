package com.indagium.diagram3

// ── Time-gap markers: auto-SUGGEST (WP11) ───────────────────────────────────────────────────
//
// The user's explicit decision (round-2 corrections plan): "manual insert plus auto-suggest,
// explicitly not silent automatic insertion." This file computes CANDIDATES only — a dismissible
// affordance the panel/canvas may offer next to a big gap, never something that mutates the
// document on its own. Turning a suggestion into a real [Seq3Delay] still goes through the exact
// same [Seq3BulkAction.AddDelay] a manual "Insert delay after this" does.
//
// Works at MESSAGE granularity (`Seq3Message.primaryTimestampMillis`), not per-drawn-row: a
// [Seq3Repeat.EVERY] message's own 40 occurrences would otherwise each be a candidate boundary
// against their neighbours, and [Seq3Delay.afterMessageId] only names a message anyway — there is
// no coarser-than-a-message anchor to offer. This intentionally reuses [seq3ChronologicalOrder]
// (Seq3LabelSummary.kt), the SAME comparator Seq3Layout/Seq3Emitters draw by, so "consecutive" here
// means the same thing it means on screen.

/** A gap between two chronologically ADJACENT messages has to be at least this long before it is
 *  worth a user's attention — short enough to catch a genuine pause (a request that took a few
 *  seconds to come back), long enough that ordinary inter-call spacing in a busy log never fires
 *  one. 30 seconds is a coarse, easily-explained default (round-2 corrections plan: "pick a
 *  sensible default threshold") — not tuned against a corpus, and deliberately a `const val` a
 *  later phase can promote to a Settings knob without touching the suggestion logic itself. */
const val SEQ3_AUTO_SUGGEST_DELAY_GAP_MILLIS = 30_000L

/** One offer: "there's a [gapMillis]-long gap right after [afterMessageId] and before
 *  [beforeMessageId] — want a delay marker there?" [afterMessageId] is exactly the value a
 *  resulting [Seq3Delay.afterMessageId] would carry if the offer is accepted. */
data class Seq3DelaySuggestion(
    val afterMessageId: String,
    val beforeMessageId: String,
    val gapMillis: Long,
)

/**
 * Every gap in [document] at or above [thresholdMillis] between two chronologically adjacent,
 * VISIBLE, timestamped messages — excluding a gap that already has a VISIBLE delay anchored to its
 * `afterMessageId` (re-offering a marker the user already placed, or already dismissed and the
 * document still models as present, would be noise, not help). A message with no timestamp at all
 * contributes no boundary on either side of it (there is nothing to measure a gap against), same
 * as how [seq3ChronologicalOrder]'s own fallback interpolation only ever stands in for LAYOUT
 * position, never for a real elapsed-time measurement like this one.
 */
fun seq3SuggestedDelays(document: Seq3Document, thresholdMillis: Long = SEQ3_AUTO_SUGGEST_DELAY_GAP_MILLIS): List<Seq3DelaySuggestion> {
    val alreadyMarked = document.delays.filter { it.visibility == Seq3Visibility.VISIBLE }.mapTo(hashSetOf()) { it.afterMessageId }
    val visible = document.messages.filter { it.visibility == Seq3Visibility.VISIBLE }
    val ordered = seq3ChronologicalOrder(
        document,
        visible,
        messageIdOf = { it.id },
        timestampMillisOf = { it.primaryTimestampMillis },
        entryIdOf = { it.occurrences.firstOrNull()?.entryId },
    )
    val suggestions = mutableListOf<Seq3DelaySuggestion>()
    for (i in 0 until ordered.size - 1) {
        val before = ordered[i]
        val after = ordered[i + 1]
        val beforeTs = before.primaryTimestampMillis ?: continue
        val afterTs = after.primaryTimestampMillis ?: continue
        val gap = afterTs - beforeTs
        if (gap >= thresholdMillis && before.id !in alreadyMarked) {
            suggestions += Seq3DelaySuggestion(before.id, after.id, gap)
        }
    }
    return suggestions
}
