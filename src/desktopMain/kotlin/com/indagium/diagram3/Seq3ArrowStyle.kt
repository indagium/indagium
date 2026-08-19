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
    else -> Seq3ArrowStyle(dash = null, filledHead = true, thin = false)
}
