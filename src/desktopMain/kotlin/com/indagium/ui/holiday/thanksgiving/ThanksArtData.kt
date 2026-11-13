// Generated from the Thanksgiving design canvas. Coordinates are the design's: the picture is 300 x 420, the companion 240 x 240,
// the title bar 1600 x 30. The static art is in resources/holiday/thanksgiving; these are the parts that move or glow.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.thanksgiving

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/** A falling leaf: centre, turn in degrees, scale of the 20-unit leaf, colour, shape (0 maple, 1 oak) and the 8.5 s rhythm when [slow]. */
internal data class ThanksLeaf(val x: Float, val y: Float, val rotation: Float, val scale: Float, val color: Color, val kind: Int, val slow: Boolean)

/** A candle flame at its origin; [variant] 1 or 2 is the 1.1 s or the 1.3 s (-0.4 s) flicker. */
internal data class ThanksFlame(val x: Float, val y: Float, val variant: Int)

/** A warm glow: centre and radius. */
internal data class ThanksGlow(val x: Float, val y: Float, val radius: Float)

/** Smoke from the pie: path, stroke width, opacity and whether it runs half a cycle late. */
internal data class ThanksSmoke(val d: String, val width: Float, val alpha: Float, val late: Boolean)

/** A goose of the V, relative to the flock's leader, at [scale]. */
internal data class ThanksGoose(val x: Float, val y: Float, val scale: Float)

/** A wheat stalk in the title bar: its SVG layer index, its base (it sways about it) and the slower rhythm. */
internal data class ThanksWheat(val index: Int, val x: Float, val y: Float, val slow: Boolean)

/** The low sun behind the hills, drawn natively (radial gradient #fff1b8 -> #ffd166 at .4 (.9) -> transparent), pulsing. */
internal val PICTURE_SUN = ThanksGlow(120f, 186f, 46f)
internal val GEESE_ORIGIN = Offset(110f, 76f)
internal val PICTURE_GEESE = listOf(
    ThanksGoose(0f, 0f, 0.6f),
    ThanksGoose(-13f, -6f, 0.6f),
    ThanksGoose(-13f, 6f, 0.6f),
    ThanksGoose(-26f, -12f, 0.6f),
    ThanksGoose(-26f, 12f, 0.6f),
)
internal const val GOOSE = "M-9 0Q-4.5 -5 0 0Q4.5 -5 9 0"
internal val PICTURE_GOOSE_COLOR = Color(0xFF3a2030)
internal const val GOOSE_STROKE_WIDTH = 2.6f
internal val WINDOW_LEAVES = listOf(
    ThanksLeaf(70f, 50f, 20f, 0.55f, Color(0xFFe8a33a), 0, slow = false),
    ThanksLeaf(130f, 40f, -30f, 0.55f, Color(0xFFb5451b), 1, slow = true),
    ThanksLeaf(170f, 70f, 50f, 0.55f, Color(0xFFc2321f), 0, slow = false),
    ThanksLeaf(96f, 110f, -20f, 0.55f, Color(0xFFd9772b), 0, slow = true),
    ThanksLeaf(226f, 60f, 10f, 0.55f, Color(0xFFe8a33a), 1, slow = false),
)
internal val PICTURE_GLOWS = listOf(
    ThanksGlow(178f, 252f, 24f),
    ThanksGlow(266f, 262f, 24f),
)
internal val PICTURE_FLAMES = listOf(
    ThanksFlame(178f, 257f, 1),
    ThanksFlame(266f, 267f, 2),
)
internal val PICTURE_SMOKE = listOf(
    ThanksSmoke("M228 282c-5 -7 5 -11 0 -18s5 -11 0 -16", 3f, 0.55f, late = false),
    ThanksSmoke("M244 280c-4 -6 4 -9 0 -15s4 -9 0 -13", 2.4f, 0.45f, late = true),
)

/** The tail fans and the head bobs about the bottom centre of their own boxes (the canvas animates with transform-box: fill-box). */
internal val TAIL_ORIGIN = Offset(120f, 157.19f)
internal val HEAD_ORIGIN = Offset(120f, 136f)
internal val COMPANION_LEAVES = listOf(
    ThanksLeaf(36f, 40f, 20f, 0.7f, Color(0xFFe8a33a), 0, slow = false),
    ThanksLeaf(206f, 30f, -30f, 0.65f, Color(0xFFb5451b), 1, slow = true),
)

/** The title bar's sun (disc #f5b041, glow #f5b041 at .35 from 45 % of the glow radius to transparent). */
internal val SCENE_SUN = ThanksGlow(1180f, 26f, 11f)
internal const val SCENE_SUN_GLOW_RADIUS = 24f
internal val SCENE_WHEAT = listOf(
    ThanksWheat(0, 1330f, 30f, slow = true),
    ThanksWheat(1, 1340f, 30f, slow = false),
    ThanksWheat(2, 1350f, 30f, slow = true),
    ThanksWheat(3, 1362f, 30f, slow = false),
    ThanksWheat(4, 1372f, 30f, slow = true),
    ThanksWheat(5, 1384f, 30f, slow = false),
)

/** The leaves: a maple and an oak, about 20 x 20 around the origin with the stem down; the vein is #000 at .22. */
internal const val MAPLE = "M0 -10L2.1 -5.4L6 -7.6L5 -2.6L10 -3L7.2 0.8L9.4 3.6L3.4 3L2.2 6.6L0.7 5.8L0.7 10L-0.7 10L-0.7 5.8L-2.2 6.6L-3.4 3" +
    "L-9.4 3.6L-7.2 0.8L-10 -3L-5 -2.6L-6 -7.6L-2.1 -5.4Z"
internal const val OAK = "M0 -10C3 -10 2.4 -7 4.2 -6.4C7 -6.2 5.2 -3.4 6.4 -2.4C9 -1.2 6.4 1.6 5.2 2.8C7 5 3.4 6 2 5.8L0.7 10L-0.7 10L-2 5.8" +
    "C-3.4 6 -7 5 -5.2 2.8C-6.4 1.6 -9 -1.2 -6.4 -2.4C-5.2 -3.4 -7 -6.2 -4.2 -6.4C-2.4 -7 -3 -10 0 -10Z"
internal const val LEAF_VEIN = "M0 9V-6M0 0L5 -3M0 0L-5 -3"
internal const val FLAME_OUTER = "M0 -14C5.5 -7 6 -1 0 2C-6 -1 -5.5 -7 0 -14Z"
internal const val FLAME_INNER = "M0 -8C2.8 -4 3 -1 0 1C-3 -1 -2.8 -4 0 -8Z"
