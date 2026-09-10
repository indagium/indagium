package com.indagium.diagram3

// ── Shared activation-span pairing ───────────────────────────────────────────────────────────
//
// `Seq3Generator.kt:21` records that this package draws "no activation spans" — every message is
// a bare arrow, with nothing showing that a participant is executing between the call that reached
// it and the return that leaves it. A UML activation bar (ExecutionSpecification) is that narrow
// rectangle on a lifeline; this file is the pure call/return pairing a bar needs, and NOTHING else
// — no geometry, no color, no drawing. WP2 turns a [Seq3ActivationSpan] into a rectangle on the
// Compose canvas and the raster; WP3 turns one into `activate`/`deactivate` (Mermaid) or
// `activate`/`deactivate` (PlantUML) text. Neither exists yet — this file only has to be correct
// on its own terms, checkable without a renderer in the loop.
//
// Why this must be ONE shared function and not two independent loops:
// `Seq3Layout.expandForLayout` (~line 604) and `Seq3Emitters.expandMessage` (~line 151) are two
// DELIBERATELY independent expansions of a message into drawn/emitted rows — both files' own
// comments say the duplication is intentional (it is how WP9's `occurrenceLabel` drift and this
// package's `Seq3ArrowStyle.kt` divergence both got fixed: give the two renderers a single shared
// source for the one piece of logic they must agree on, and let everything else about "how to walk
// a message into rows" keep evolving independently). If each of those two files grew its own
// call/return stack, the exported PNG and the exported Mermaid/PlantUML text could close different
// bars at different rows — this is the identical class of bug that produced `Seq3ArrowStyle.kt`
// (raster branched on `Seq3Kind`, the Compose canvas did not, so a RETURN looked like a CALL on
// screen until you exported it) and that pushed `seq3ChronologicalOrder` out into
// `Seq3LabelSummary.kt`. The expansions stay duplicated; the pairing is shared.
//
// Deliberately UI-free, like every other file in this package (see Seq3Model.kt's own header):
// no Compose import, no geometry, and no `com.indagium.model` import — a caller hands in the bare
// per-row facts ([Seq3ActivationEvent]) already resolved from whatever domain type it started
// from, so this file never needs to know what a `LogEntry` or a `LogTab` is.

/**
 * One drawn/emitted row's activation-relevant facts, already resolved by the caller (WP2's layout
 * expansion or WP3's text expansion) from whichever [Seq3Message]/occurrence produced that row.
 *
 * [index] is the row's position in that caller's own expanded row sequence — NOT a message index
 * and NOT a `Seq3Occurrence` index. See "pairing is per row" below for why that distinction is the
 * whole point of this shape.
 */
data class Seq3ActivationEvent(
    val index: Int,
    val messageId: String,
    val kind: Seq3Kind,
    val fromLifelineId: String,
    val toLifelineId: String?,
)

/**
 * One open-to-close activation bar on [lifelineId], spanning row indices [startIndex]..[endIndex]
 * (inclusive; both indices are values from [Seq3ActivationEvent.index]).
 *
 * [depth] counts other bars already open on the SAME lifeline when this one opened — 0 for a bar
 * opening on an idle lifeline, 1 for one opening while another is already open on it (reentrancy),
 * and so on. A renderer insets each successive depth so nested activation is visible as nested
 * rectangles rather than one solid block.
 *
 * [unmatched] is true when [endIndex] was never actually closed by a `RETURN` and was instead
 * assigned by the "close at the last event touching this lifeline" fallback (see
 * [seq3ActivationSpans]'s own doc, rule 1) — a renderer may want to draw an unmatched bar
 * differently (e.g. no closing cap) though this file takes no position on that; it only tells the
 * truth about whether a close was ever observed.
 */
data class Seq3ActivationSpan(
    val lifelineId: String,
    val startIndex: Int,
    val endIndex: Int,
    val depth: Int,
    val unmatched: Boolean,
)

/** A push past this depth is dropped rather than nested further — same defensive posture as
 *  `Seq3Generator.kt`'s `MAX_THREAD_PEER_TAGS`: a pathological log with (say) 200 unmatched calls
 *  into one lifeline before its first return would otherwise nest 200 insets and draw as a solid
 *  block, which communicates nothing. 8 is generous for anything a human would actually read as a
 *  nested-call diagram; deeper genuine nesting is vanishingly rare and, if it happens, degrades to
 *  "further calls stop opening new bars" rather than a crash or a runaway render. */
private const val SEQ3_MAX_ACTIVATION_DEPTH = 8

/**
 * Pairs [events] — one per drawn/emitted row, in row order — into activation spans via a stack
 * machine: one `ArrayDeque<Int>` of open start-[Seq3ActivationEvent.index] values per lifeline id,
 * walked once over [events] in order.
 *
 * | `kind` | action |
 * |---|---|
 * | `CALL` | push `index` on [toLifelineId]'s stack (the callee is what starts executing); `depth`
 * |         | is that stack's size BEFORE the push. A null `toLifelineId` (an unresolved-target
 * |         | message) is a no-op — there is no lifeline to open a bar on. |
 * | `RETURN` | pop the top open index on [fromLifelineId]'s stack (the sender of a return is what
 * |           | stops executing) and close that span at this `index`. |
 * | `ASYNC`/`SELF`/`NOTE` | neutral — no push, no pop. |
 *
 * Rule 1 — **unmatched calls close at the last row index that touches their lifeline**
 * (`fromLifelineId == lifelineId || toLifelineId == lifelineId`), falling back to [lastIndex] if
 * literally nothing in [events] touches that lifeline (defensive only — the call event that opened
 * the span always itself touches it, via `toLifelineId`, so this branch is not expected to be
 * reachable from real input, only from a caller handing in a lifeline that has since been removed
 * from [events] entirely). This is a correctness requirement, not cosmetics: Mermaid raises a
 * parse error on an `activate` with no matching `deactivate`, and `Seq3Generator.generateSeq3`
 * never mints a `RETURN` (its messages either take the `Seq3Kind.CALL` default or are explicitly
 * `SELF`/`NOTE` — grep it), so a freshly generated document is *all* calls. If an unmatched span
 * did not close at a concrete index, WP3 could not emit it at all — it would either have to skip
 * every activation bar on a fresh document (defeating the whole feature) or hand Mermaid invalid
 * syntax.
 *
 * Rule 2 — **unmatched returns are silently dropped.** A `RETURN` whose `fromLifelineId` stack is
 * empty opens and closes nothing; this package's posture everywhere is never to throw on degenerate
 * input (see `utils/TextMatch.kt`'s own doc for the same idea one layer down).
 *
 * Rule 3 — the [SEQ3_MAX_ACTIVATION_DEPTH] cap: see that constant's own doc.
 *
 * Rule 4 — **pairing is per event (per drawn row), never per message.** Callers pass one event per
 * drawn/emitted row, so under `Seq3Repeat.COLLAPSE_ABOVE` one row standing for *n* occurrences
 * yields exactly one push/pop, and under `Seq3Repeat.FIRST_LAST` a single message contributes two
 * rows (and therefore pushes twice, if it is a `CALL`). This function makes no attempt to
 * reconcile occurrence counts across repeat modes — doing so would require `Seq3Layout` and
 * `Seq3Emitters`'s two independent expansions to agree on something beyond row order, which is
 * exactly the coupling this package spent WP9/the arrow-style fix removing (see this file's own
 * header). Any mismatch a repeat mode introduces between pushes and pops is absorbed by rule 1
 * (an extra open push just closes unmatched) or rule 2 (an extra pop just drops) — never a defect
 * to guard against here.
 *
 * Returned spans are sorted by [Seq3ActivationSpan.startIndex] then [Seq3ActivationSpan.lifelineId]
 * so callers (and tests) never depend on `HashMap`/`ArrayDeque` iteration order.
 */
fun seq3ActivationSpans(events: List<Seq3ActivationEvent>, lastIndex: Int): List<Seq3ActivationSpan> {
    val openStacks = mutableMapOf<String, ArrayDeque<Int>>()
    val closedSpans = mutableListOf<Seq3ActivationSpan>()

    // Rule 1's fallback target: the last row index touching each lifeline, either as sender or
    // receiver. A single forward pass, independent of the stack machine below.
    val lastTouchIndex = mutableMapOf<String, Int>()
    for (event in events) {
        lastTouchIndex[event.fromLifelineId] = event.index
        event.toLifelineId?.let { lastTouchIndex[it] = event.index }
    }

    for (event in events) {
        when (event.kind) {
            // WP10: a CREATE is a real invocation into a concrete `toLifelineId` (the constructed
            // lifeline), same shape as CALL — the constructor runs, so the callee is genuinely
            // active. Unlike CALL there is rarely a matching RETURN modeling "construction
            // finished", so almost every CREATE push lands in rule 1's unmatched fallback and
            // closes at the callee's own last touching row — which is exactly the existing,
            // already-correct behavior for any CALL that never got an explicit RETURN.
            Seq3Kind.CALL, Seq3Kind.CREATE -> {
                val calleeId = event.toLifelineId ?: continue
                val stack = openStacks.getOrPut(calleeId) { ArrayDeque() }
                if (stack.size < SEQ3_MAX_ACTIVATION_DEPTH) stack.addLast(event.index)
                // Rule 3: past the cap, the push is dropped — no span opens for it, and no
                // corresponding pop will find it, which is fine (rule 2 drops that pop too).
            }
            Seq3Kind.RETURN -> {
                val stack = openStacks[event.fromLifelineId]
                if (stack != null && stack.isNotEmpty()) {
                    // The popped entry is always the current top, so its depth (the stack's size
                    // at the moment IT was pushed) equals the stack's size right now, minus one —
                    // see this file's own reasoning: nothing can sit above it in a LIFO stack while
                    // it is still open, so its distance from the bottom never changes while open.
                    val depth = stack.size - 1
                    val startIndex = stack.removeLast()
                    closedSpans += Seq3ActivationSpan(event.fromLifelineId, startIndex, event.index, depth, unmatched = false)
                } // Rule 2: an empty stack means an unmatched return — dropped, opens/closes nothing.
            }
            // WP9 enum append forces a decision here (this file is otherwise off limits per that
            // package's brief) — LOST/FOUND join the existing neutral bucket, not a new one: both
            // always have a null `toLifelineId` (same as NOTE) and Seq3Emitters' own
            // `activationEventOf` already maps their emission to a neutral Seq3Kind.NOTE
            // activation event for the identical reason, so this is the one answer consistent
            // with that precedent, not a new decision made from scratch.
            //
            // WP10: DESTROY is neutral too, but for a different reason than LOST/FOUND — it does
            // have a real `toLifelineId` (the destroyed lifeline). RETURN closes a stack keyed by
            // `fromLifelineId` (the returning callee popping its own frame); DESTROY's sender is
            // the *destroyer*, not the destroyed lifeline, so it has no stack of its own to pop,
            // and reaching across to forcibly close whatever the destroyed lifeline happens to
            // have open is a heuristic this WP's brief explicitly rules out (geometry only —
            // `Seq3Layout` truncates the destroyed column's guide line at this row regardless of
            // what the activation machinery does). Any activation still open on the destroyed
            // lifeline falls through to rule 1's own fallback exactly like an unmatched CALL does.
            Seq3Kind.ASYNC, Seq3Kind.SELF, Seq3Kind.NOTE, Seq3Kind.LOST, Seq3Kind.FOUND, Seq3Kind.DESTROY -> Unit // neutral: no push, no pop
        }
    }

    // Whatever is left open never saw a RETURN — rule 1 closes each at its lifeline's last
    // touching row. Depth is the entry's position from the bottom of its own stack (0-indexed),
    // which `ArrayDeque` preserves in push order.
    for ((lifelineId, stack) in openStacks) {
        stack.forEachIndexed { depth, startIndex ->
            val endIndex = lastTouchIndex[lifelineId] ?: lastIndex
            closedSpans += Seq3ActivationSpan(lifelineId, startIndex, endIndex, depth, unmatched = true)
        }
    }

    return closedSpans.sortedWith(compareBy({ it.startIndex }, { it.lifelineId }))
}
