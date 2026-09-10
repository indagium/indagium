package com.indagium.diagram3

// ── Shared arrow-style descriptor ────────────────────────────────────────────────────────────
//
// The permanent fix for a screen/PNG divergence: `Seq3Raster.strokeFor` (the headless export path)
// already branched on `Seq3Kind` to draw RETURN/ASYNC as dashed, open-headed, thin arrows, but the
// Compose canvas (`ui/Seq3Canvas.kt`'s `drawSeq3Arrow`) never read `row.kind` at all — every kind
// rendered identically on screen, so a message you set to `return` looked exactly like a `call`
// until you exported it. This file is the ONE descriptor both renderers now consume (WP2 rewires
// the Compose side; this phase only rewires the raster, which already had the right values, just
// duplicated inline in a `when`) so the two can never drift apart again.

/**
 * Everything a renderer needs to draw one [Seq3Kind]'s arrow, independent of which graphics API
 * (AWT `Graphics2D` here, Compose `DrawScope` in WP2) is doing the actual drawing.
 *
 * [dash] is a `List<Float>`, not a `FloatArray`, though `BasicStroke`'s own constructor and
 * Compose's `PathEffect.dashPathEffect` both natively want a `FloatArray`: a `data class` holding
 * a `FloatArray` gets ARRAY-IDENTITY `equals`/`hashCode` (two arrays with equal contents are still
 * unequal), and `Seq3RenderCache` keys its raster/display/png tiers on `(layout, theme, scale)`
 * equality (`Seq3Theme.kt:119`) — nothing in that key touches `Seq3ArrowStyle` directly today, but
 * the moment a future cache key or memoized composable closes over one of these (exactly the kind
 * of thing WP2's canvas rewrite is likely to do), a broken `equals` would silently defeat it. A
 * `List<Float>` has structural equality for free, and these dash patterns are two floats each —
 * there's no meaningful allocation cost to paying for that safety here. Callers convert to
 * `FloatArray` at the actual draw call via `.toFloatArray()`.
 */
data class Seq3ArrowStyle(
    val dash: List<Float>?,
    val filledHead: Boolean,
    val thin: Boolean,
)

// Values copied verbatim from Seq3Raster's PRE-EXISTING `strokeFor`/`paintArrowRow` (its own
// DASH_RETURN/DASH_ASYNC/STROKE_THIN/STROKE_THICK constants) so this refactor changes NOTHING
// about the exported PNG — see Seq3Raster.kt's own `strokeFor` for the raster-only cap/join detail
// (CAP_BUTT vs CAP_ROUND) this descriptor deliberately does not carry, since that is a rendering
// nuance the two graphics APIs don't share a common vocabulary for.
private val SEQ3_DASH_RETURN = listOf(6f, 4f)
private val SEQ3_DASH_ASYNC = listOf(3f, 3f)

fun seq3ArrowStyle(kind: Seq3Kind): Seq3ArrowStyle = when (kind) {
    Seq3Kind.RETURN -> Seq3ArrowStyle(dash = SEQ3_DASH_RETURN, filledHead = false, thin = true)
    Seq3Kind.ASYNC -> Seq3ArrowStyle(dash = SEQ3_DASH_ASYNC, filledHead = false, thin = true)
    // LOST/FOUND never actually draw through this descriptor: they always lay out as a
    // Seq3UnresolvedStubRow (WP9 Deliverable 2), whose own `terminal` field (DROP_PILL/LOST/FOUND)
    // is painted directly by Seq3Raster/Seq3Canvas as a line ending in a filled circle — there is
    // no arrowhead for `filledHead` to gate. Still its OWN branch, not folded into CALL/SELF/NOTE
    // below by reflex: a lost/found message is a RESOLVED, honest fact (it really happened; only
    // the other end is unobservable), which reads correctly as a SOLID line — unlike RETURN/
    // ASYNC's dashed "different arrow kind" semantics above, and unlike the separate amber/dashed
    // warning styling `Seq3UnresolvedStubRow`'s DROP_PILL terminal keeps for a genuinely
    // still-unresolved message (that dash lives in Seq3Raster/Seq3Canvas's own DASH_WARN, not
    // here). The values happen to match CALL's because "solid, filled, thick" is independently the
    // right answer for a resolved one-way send, not because this is a copy-paste of that branch.
    Seq3Kind.LOST, Seq3Kind.FOUND -> Seq3ArrowStyle(dash = null, filledHead = true, thin = false)
    // WP10: CREATE follows the UML convention of a dashed line with an open arrowhead pointing at
    // the box it constructs — the same visual vocabulary RETURN already uses above, reused
    // because it IS the same shape (a dashed, open-headed arrow), not because this is a fallback.
    // DESTROY is an ordinary synchronous send (solid/filled/thick, CALL's style) — the UML signal
    // that the target is gone is the X drawn at the foot of its lifeline (Deliverable 3), which is
    // a property of the *column*, not of this arrow's own stroke, so DESTROY needs no style of its
    // own here.
    Seq3Kind.CREATE -> Seq3ArrowStyle(dash = SEQ3_DASH_RETURN, filledHead = false, thin = true)
    // No `else`: exhaustive on purpose (WP8) so a new Seq3Kind forces a decision here instead of
    // silently inheriting the solid-filled-arrow default meant for CALL.
    Seq3Kind.CALL, Seq3Kind.SELF, Seq3Kind.NOTE, Seq3Kind.DESTROY -> Seq3ArrowStyle(dash = null, filledHead = true, thin = false)
}
