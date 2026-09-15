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
    /** Phase 1 of the manual-activation-bars feature: non-null exactly when this span came from a
     *  user-placed [Seq3ManualActivation] rather than the automatic call/return pairing above —
     *  carries that [Seq3ManualActivation.id] through so phase 2's canvas can hit-test/drag the
     *  right bar. Appended LAST (this file's own field-versioning convention — a plain data class,
     *  no codec, but every existing positional/named construction site below keeps compiling
     *  unchanged because this has a default). Always null for a span [seq3ActivationSpans] itself
     *  produces; only `seq3MergedActivationSpans` (below) ever sets it. */
    val manualId: String? = null,
    /** Phase 2: true exactly when this MANUAL span's [endIndex] is [Seq3ManualActivation
     *  .endMessageId] `== null` ("until the end of the diagram") AND that end survived
     *  [seq3ResolveActivationCrossings] unclamped — i.e. [endIndex] genuinely reflects the last
     *  emission, not a row a crossing bar clamped it to. `Seq3Layout.kt`'s `buildActivationBars`
     *  reads this to draw the bar down to the LIFELINE's own bottom (where its dashed guide line
     *  ends) instead of the last emission's row y — the two can differ once a manual bar with a
     *  null end is the LAST thing drawn on a lifeline that keeps a little vertical margin below its
     *  final row (see that function's own doc). [seq3ResolveActivationCrossings] resets this to
     *  `false` the moment it clamps a manual span's [endIndex] down to an earlier row: at that
     *  point the span's true bottom IS that row, not the diagram's, and drawing it at the lifeline's
     *  bottom would overshoot the very clamp that was just computed. Appended LAST, same convention
     *  as [manualId]; always `false` for an AUTO span (auto spans have no such "until the end"
     *  concept — [seq3ActivationSpans]' own rule 1 always closes an unmatched call at a concrete
     *  row). */
    val toDiagramEnd: Boolean = false,
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
 * machine: one stack of open frames per callee lifeline id,
 * walked once over [events] in order.
 *
 * | `kind` | action |
 * |---|---|
 * | `CALL` | push a frame containing caller, callee, `index`, and depth on [toLifelineId]'s stack
 * |         | (the callee is what starts executing). A null `toLifelineId` (an unresolved-target
 * |         | message) is a no-op — there is no lifeline to open a bar on. |
 * | `RETURN` | with a null [Seq3ActivationEvent.toLifelineId], pop the top frame on
 * |           | [fromLifelineId]'s stack (the legacy per-callee LIFO behavior). With a target,
 * |           | close the most recently opened frame whose caller is that target and whose callee
 * |           | is [fromLifelineId]. A target that has no matching frame is a no-op. |
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
 * Rule 2 — **unmatched returns are silently dropped.** A `RETURN` whose sender stack is empty, or
 * whose explicit target has no matching caller/callee frame, opens and closes nothing; this
 * package's posture everywhere is never to throw on degenerate input (see `utils/TextMatch.kt`'s
 * own doc for the same idea one layer down).
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
private data class Seq3OpenActivationFrame(
    val callerLifelineId: String,
    val calleeLifelineId: String,
    val startIndex: Int,
    val depth: Int,
)

fun seq3ActivationSpans(events: List<Seq3ActivationEvent>, lastIndex: Int): List<Seq3ActivationSpan> {
    // A list is deliberately used instead of a plain stack: explicitly addressed RETURN events
    // must remove the newest frame for a particular caller while preserving unrelated frames.
    val openStacks = mutableMapOf<String, MutableList<Seq3OpenActivationFrame>>()
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
                val stack = openStacks.getOrPut(calleeId) { mutableListOf() }
                if (stack.size < SEQ3_MAX_ACTIVATION_DEPTH) {
                    stack += Seq3OpenActivationFrame(
                        callerLifelineId = event.fromLifelineId,
                        calleeLifelineId = calleeId,
                        startIndex = event.index,
                        depth = stack.size,
                    )
                }
                // Rule 3: past the cap, the push is dropped — no span opens for it, and no
                // corresponding pop will find it, which is fine (rule 2 drops that pop too).
            }
            Seq3Kind.RETURN -> {
                val stack = openStacks[event.fromLifelineId]
                val frame = when {
                    stack.isNullOrEmpty() -> null
                    // Keep historic documents and synthetic callers that omit a return target
                    // compatible: they retain the old per-callee LIFO pairing.
                    event.toLifelineId == null -> stack.removeAt(stack.lastIndex)
                    else -> stack.indexOfLast {
                        it.callerLifelineId == event.toLifelineId &&
                            it.calleeLifelineId == event.fromLifelineId
                    }.takeIf { it >= 0 }?.let(stack::removeAt)
                }
                if (frame != null) {
                    closedSpans += Seq3ActivationSpan(
                        lifelineId = frame.calleeLifelineId,
                        startIndex = frame.startIndex,
                        endIndex = event.index,
                        depth = frame.depth,
                        unmatched = false,
                    )
                } // Rule 2: no frame means an unmatched return — dropped, opens/closes nothing.
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
    // touching row. Frames retain their opening depth so targeted returns can safely close a
    // non-top frame without changing the nesting identity of unrelated frames.
    for ((lifelineId, stack) in openStacks) {
        stack.forEach { frame ->
            val endIndex = lastTouchIndex[lifelineId] ?: lastIndex
            closedSpans += Seq3ActivationSpan(lifelineId, frame.startIndex, endIndex, frame.depth, unmatched = true)
        }
    }

    return closedSpans.sortedWith(compareBy({ it.startIndex }, { it.lifelineId }))
}

// ── Manual activation bars (phase 1) ────────────────────────────────────────────────────────
//
// [seq3ActivationSpans] above is the AUTO pairing; everything below resolves and merges in the
// USER-PLACED bars ([Seq3ManualActivation], Seq3Model.kt) so the canvas (WP2, phase 2), the raster,
// and both text emitters read the merged result from ONE shared function — exactly the reasoning
// this file's own header gives for why the auto pairing itself is a single shared function rather
// than two independent loops. A caller (Seq3Layout.kt's `buildActivationBars`, Seq3Emitters.kt's
// `activationMaps`) resolves its OWN (messageId, occurrenceEntryId) -> index maps — each has a
// different Emission type in a different index space — and hands the resolved result here.

/** One [Seq3ManualActivation] resolved to concrete emission-space indices by its caller — see
 *  [seq3ResolveManualActivations]'s own doc for the exact resolution rules. [endIndex] is never
 *  less than [startIndex] (the resolver itself clamps that). */
data class Seq3ResolvedManualActivation(
    val id: String,
    val lifelineId: String,
    val startIndex: Int,
    val endIndex: Int,
    /** True exactly when [Seq3ManualActivation.endMessageId] was `null` — see
     *  [Seq3ActivationSpan.toDiagramEnd]'s own doc for what this drives downstream. Appended LAST,
     *  same field-versioning convention as that one. */
    val toDiagramEnd: Boolean = false,
)

/**
 * Resolves every VISIBLE entry of [manual] against a caller's own emission-index maps, dropping
 * anything that cannot be drawn — the "dangling reference draws nothing, never crashes" contract
 * [Seq3ManualActivation]'s own doc documents for itself:
 *  - A hidden manual activation ([Seq3ManualActivation.visibility] != VISIBLE) produces no bar.
 *  - [Seq3ManualActivation.lifelineId] not present in [lifelineIds] (hidden lifeline, or one
 *    removed entirely) -> no bar: there is no column to draw it on.
 *  - **Start**: the exact occurrence ([indexByOccurrence] keyed by [Seq3OccurrenceRef]) when
 *    [Seq3ManualActivation.startOccurrenceEntryId] is set and resolves, else the message's FIRST
 *    emission ([firstIndexByMessage]). A [startMessageId][Seq3ManualActivation.startMessageId] that
 *    resolves to neither -> no bar; there is no row to anchor the top of the bar to.
 *  - **End**: `endMessageId == null` means [lastIndex] — "until the end of the diagram", per
 *    [Seq3ManualActivation]'s own doc on that field. Otherwise the exact occurrence when
 *    [Seq3ManualActivation.endOccurrenceEntryId] resolves, else the message's LAST emission
 *    ([lastIndexByMessage]). An [endMessageId][Seq3ManualActivation.endMessageId] that resolves to
 *    neither clamps to the START index — a bar with a genuinely unresolvable bottom degrades to a
 *    zero-length one at its own top rather than disappearing.
 *  - Finally, an end that resolved BEFORE its own start (a message reordered/edited so the end
 *    anchor now sits earlier than the start anchor) clamps up to the start index too — the bar
 *    never draws upside down.
 */
fun seq3ResolveManualActivations(
    manual: List<Seq3ManualActivation>,
    lifelineIds: Set<String>,
    firstIndexByMessage: Map<String, Int>,
    lastIndexByMessage: Map<String, Int>,
    indexByOccurrence: Map<Seq3OccurrenceRef, Int>,
    lastIndex: Int,
): List<Seq3ResolvedManualActivation> = manual.mapNotNull { activation ->
    if (activation.visibility != Seq3Visibility.VISIBLE) return@mapNotNull null
    if (activation.lifelineId !in lifelineIds) return@mapNotNull null
    val startIndex = activation.startOccurrenceEntryId
        ?.let { entryId -> indexByOccurrence[Seq3OccurrenceRef(activation.startMessageId, entryId)] }
        ?: firstIndexByMessage[activation.startMessageId]
        ?: return@mapNotNull null
    val resolvedEnd = when {
        activation.endMessageId == null -> lastIndex
        else -> activation.endOccurrenceEntryId
            ?.let { entryId -> indexByOccurrence[Seq3OccurrenceRef(activation.endMessageId, entryId)] }
            ?: lastIndexByMessage[activation.endMessageId]
            ?: startIndex
    }
    val endIndex = if (resolvedEnd < startIndex) startIndex else resolvedEnd
    Seq3ResolvedManualActivation(activation.id, activation.lifelineId, startIndex, endIndex, toDiagramEnd = activation.endMessageId == null)
}

/**
 * Resolves every crossing pair in [spans] (all on ONE lifeline) to a fixed point, so the result is
 * always a proper LAMINAR family — every pair of spans is either disjoint or one fully contains the
 * other, never a partial overlap — before [seq3MergedActivationSpans]' own stack walk assigns depth.
 *
 * Two spans "cross" when the one starting first (a tied start goes to whichever has the LARGER end
 * — the exact convention [seq3MergedActivationSpans]' own sort uses, so a tie is proper containment,
 * never a crossing) is nonetheless not long enough to contain the other: the second span starts at
 * or before the first's own end, but ends AFTER it.
 *
 * **Only a MANUAL span is ever clamped — an AUTO span's own call/return pairing is always
 * authoritative and this function must never rewrite it:**
 *  - If the LATER-starting (inner) span in a crossing pair is manual, it is clamped to the earlier
 *    (outer) span's own end — this is the "a manual bar dragged past its enclosing span's end
 *    snaps back to it" case.
 *  - If the EARLIER-starting (outer) span is manual and the later-starting (inner) one is AUTO —
 *    the case the naive single-pass version of this function used to miss entirely, letting an
 *    inner auto bar visibly stick out past its (wrongly) still-longer manual parent — the outer
 *    manual span is clamped instead, to `inner.startIndex - 1` (never below its own start): the
 *    auto span is left completely untouched, and the manual bar simply ends right where the auto
 *    one begins rather than nesting around it.
 *  - Two AUTO spans crossing each other is not expected to occur ([seq3ActivationSpans] itself only
 *    ever produces a properly-nested family), so that case is left as-is rather than guessing which
 *    one to break — this function never claims to fix input it cannot legally touch.
 *
 * A single clamp can create or resolve OTHER crossings against a third span (`endLimitWithExclusion...`
 * / chained-clamp tests, Seq3ActivationTest.kt), so this re-scans from scratch after every change
 * until a full pass finds none — bounded by [spans].size² (generous: real documents have only a
 * handful of activation spans per lifeline, and every clamp strictly shrinks a manual span's own
 * end, so this always terminates well before the cap; the cap only guards against a case this
 * function's own invariants say cannot happen).
 */
@Suppress("LoopWithTooManyJumpStatements")
private fun seq3ResolveActivationCrossings(spans: List<Seq3ActivationSpan>): List<Seq3ActivationSpan> {
    if (spans.size < 2) return spans
    val working = spans.toMutableList()
    val maxPasses = working.size * working.size + working.size + 4
    var pass = 0
    var changed = true
    while (changed && pass < maxPasses) {
        changed = false
        pass++
        for (i in working.indices) {
            for (j in working.indices) {
                if (i == j) continue
                val a = working[i]
                val b = working[j]
                val aIsOuter = a.startIndex < b.startIndex || (a.startIndex == b.startIndex && a.endIndex >= b.endIndex)
                val outerIdx = if (aIsOuter) i else j
                val innerIdx = if (aIsOuter) j else i
                val outer = working[outerIdx]
                val inner = working[innerIdx]
                if (inner.startIndex > outer.endIndex) continue // disjoint
                if (inner.endIndex <= outer.endIndex) continue // properly nested, not a crossing
                // inner.endIndex > outer.endIndex here: a genuine crossing.
                if (inner.manualId != null) {
                    // toDiagramEnd = false: the clamp just replaced "until the end of the diagram"
                    // with a concrete earlier row (outer's own end) — see that field's own doc.
                    working[innerIdx] = inner.copy(endIndex = outer.endIndex, toDiagramEnd = false)
                    changed = true
                } else if (outer.manualId != null) {
                    working[outerIdx] = outer.copy(endIndex = (inner.startIndex - 1).coerceAtLeast(outer.startIndex), toDiagramEnd = false)
                    changed = true
                }
                // else: both auto — left untouched, see this function's own doc.
            }
        }
    }
    return working
}

/**
 * Merges [autoSpans] (the call/return pairing — pass `emptyList()` when
 * [Seq3Document.showActivations] is off; manual bars still draw) with already-[resolved][manual]
 * manual activations into ONE proper per-lifeline nesting — the single shared function every
 * consumer (Seq3Layout's canvas/PNG geometry, Seq3Emitters' Mermaid/PlantUML text) calls, so they
 * can never draw/export a different bar arrangement for the same document (the exact class of bug
 * this file's own header describes for the auto pairing itself).
 *
 * **Per lifeline**, [seq3ResolveActivationCrossings] first clamps away every crossing (never an
 * AUTO span, only ever a MANUAL one — see that function's own doc for both directions this can
 * happen in) so the spans form a proper laminar family. Only THEN are they ordered — by
 * [Seq3ActivationSpan.startIndex] ascending, then by [Seq3ActivationSpan.endIndex] DESCENDING (a
 * longer span sorts first, so it becomes the enclosing one when two spans start together), then
 * AUTO before MANUAL on an exact start+end tie, then by [Seq3ActivationSpan.manualId] (auto spans
 * compare equal to each other here and fall back to their own pre-sorted relative order, which
 * `sortedWith`'s stability preserves) — a fully deterministic order so two runs over the same input
 * never disagree — and walked with a stack that assigns depth: a span starting at or before the
 * current top-of-stack's own end nests one level deeper than it; a span starting after the top's
 * end pops it (and anything else already closed) first. This pop only ever needs to inspect the
 * TOP of the stack because the spans are now laminar — anything still open below an open top is
 * guaranteed to end no earlier than that top, an invariant a crossing set would have broken.
 * [SEQ3_MAX_ACTIVATION_DEPTH] is enforced exactly like [seq3ActivationSpans]'s own rule 3: a span
 * that would open past the cap is dropped entirely rather than nested further.
 *
 * Every manual-derived [Seq3ActivationSpan] keeps [Seq3ActivationSpan.unmatched] `false` (a manual
 * bar was never "supposed to" close via a RETURN, so there is nothing to report as unmatched) and
 * carries its originating id in [Seq3ActivationSpan.manualId]; every auto-derived span keeps
 * `manualId == null` and whatever `unmatched` [seq3ActivationSpans] itself computed.
 *
 * The returned list is sorted by [Seq3ActivationSpan.startIndex] then [Seq3ActivationSpan.lifelineId]
 * — the same contract [seq3ActivationSpans] documents for its own return value.
 */
@Suppress("UnusedParameter")
fun seq3MergedActivationSpans(
    autoSpans: List<Seq3ActivationSpan>,
    manual: List<Seq3ResolvedManualActivation>,
    lastIndex: Int,
): List<Seq3ActivationSpan> {
    val byLifeline = LinkedHashMap<String, MutableList<Seq3ActivationSpan>>()
    autoSpans.forEach { span -> byLifeline.getOrPut(span.lifelineId) { mutableListOf() } += span }
    manual.forEach { m ->
        byLifeline.getOrPut(m.lifelineId) { mutableListOf() } +=
            Seq3ActivationSpan(m.lifelineId, m.startIndex, m.endIndex, depth = 0, unmatched = false, manualId = m.id, toDiagramEnd = m.toDiagramEnd)
    }

    val result = mutableListOf<Seq3ActivationSpan>()
    byLifeline.values.forEach { spans ->
        val laminar = seq3ResolveActivationCrossings(spans)
        val ordered = laminar.sortedWith(
            compareBy<Seq3ActivationSpan> { it.startIndex }
                .thenByDescending { it.endIndex }
                .thenBy { if (it.manualId == null) 0 else 1 }
                .thenBy { it.manualId ?: "" },
        )
        // One stack of already-emitted (depth-resolved) frames per lifeline — mirrors
        // seq3ActivationSpans' own per-callee stack, just walking already-paired, now-laminar spans
        // instead of raw call/return events. No clamping happens here any more — the pre-pass above
        // already resolved every crossing — this only ever assigns depth.
        val stack = ArrayDeque<Seq3ActivationSpan>()
        ordered.forEach { span ->
            while (stack.isNotEmpty() && stack.last().endIndex < span.startIndex) stack.removeLast()
            val depth = stack.size
            if (depth >= SEQ3_MAX_ACTIVATION_DEPTH) return@forEach // rule 3: dropped, not nested further
            val resolved = span.copy(depth = depth)
            stack.addLast(resolved)
            result += resolved
        }
    }
    return result.sortedWith(compareBy({ it.startIndex }, { it.lifelineId }))
}

/** A sentinel [Seq3ManualActivation.id] that can never collide with a real (user/UUID-minted) one —
 *  used only internally by [seq3ManualActivationEndLimit] to probe [seq3MergedActivationSpans]
 *  without a real id. */
private const val SEQ3_PROBE_MANUAL_ID = " seq3-manual-activation-probe"

/**
 * The maximum emission index a manual activation bar starting at [startIndex] on [lifelineId] could
 * reach without crossing an already-drawn enclosing span — phase 2's own drag-to-resize snapping
 * reads this to cap how far down the bottom handle may travel. Reuses
 * [seq3MergedActivationSpans] itself (rather than re-implementing the same clamp rule) by probing it
 * with a hypothetical manual span that reaches all the way to [lastIndex] and reading back whatever
 * that merge clamped it to — the one guarantee this function needs (the limit always agrees with
 * what an actual add/resize would produce) falls out for free from calling the same function, rather
 * than needing to be kept in sync with it by hand.
 *
 * [excludeManualId] lets a caller resizing an EXISTING bar leave that bar's own current span out of
 * the probe (otherwise the bar being resized would "enclose" its own probe and cap it at its current
 * length). If the probe itself gets dropped entirely (past [SEQ3_MAX_ACTIVATION_DEPTH] — a
 * pathologically deep lifeline), [startIndex] is returned: the only length that could still be
 * placed is a zero-length one right where the drag started.
 */
fun seq3ManualActivationEndLimit(
    autoSpans: List<Seq3ActivationSpan>,
    manual: List<Seq3ResolvedManualActivation>,
    lifelineId: String,
    startIndex: Int,
    lastIndex: Int,
    excludeManualId: String? = null,
): Int {
    val others = manual.filter { it.lifelineId == lifelineId && it.id != excludeManualId }
    val probe = Seq3ResolvedManualActivation(SEQ3_PROBE_MANUAL_ID, lifelineId, startIndex, lastIndex)
    val merged = seq3MergedActivationSpans(autoSpans, others + probe, lastIndex)
    return merged.firstOrNull { it.manualId == SEQ3_PROBE_MANUAL_ID }?.endIndex ?: startIndex
}

/**
 * True when [candidateEndIndex] is a valid end for a manual bar starting at [startIndex] on
 * [lifelineId] — meaning [seq3MergedActivationSpans] draws it at EXACTLY that end, unclamped.
 * Phase 2's drag-to-resize snapping is expected to only ever offer the user a row as a snap target
 * when this holds, rather than silently drawing a shorter bar than the row the handle visually
 * landed on. Reuses [seq3MergedActivationSpans] the same way [seq3ManualActivationEndLimit] does
 * (a probe with [candidateEndIndex] as its declared end, [excludeManualId] left out of the other
 * spans it is merged against) — same reasoning: the one guarantee this needs, agreeing with what an
 * actual add/resize would produce, falls out for free from calling the same function rather than
 * needing to be kept in sync with it by hand. `false` both when the merge clamps the probe to
 * something shorter than requested AND when the probe is dropped entirely (past
 * [SEQ3_MAX_ACTIVATION_DEPTH]) — a bar that wouldn't even draw is not a valid snap target either.
 */
fun seq3IsValidManualActivationEnd(
    autoSpans: List<Seq3ActivationSpan>,
    manual: List<Seq3ResolvedManualActivation>,
    lifelineId: String,
    startIndex: Int,
    candidateEndIndex: Int,
    lastIndex: Int,
    excludeManualId: String? = null,
): Boolean {
    val others = manual.filter { it.lifelineId == lifelineId && it.id != excludeManualId }
    val probe = Seq3ResolvedManualActivation(SEQ3_PROBE_MANUAL_ID, lifelineId, startIndex, candidateEndIndex)
    val merged = seq3MergedActivationSpans(autoSpans, others + probe, lastIndex)
    val probeResult = merged.firstOrNull { it.manualId == SEQ3_PROBE_MANUAL_ID } ?: return false
    return probeResult.endIndex == candidateEndIndex
}
