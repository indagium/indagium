// Generated from the Dia de Muertos design canvas (the art of the picture, the companion and the title bar). Coordinates are the
// design's: the picture is 300 x 420, the companion 240 x 240, the title bar 1600 x 30.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.muertos

import androidx.compose.ui.graphics.Color

/** A candle flame: its base centre ([x], [y]) and size; [variant] picks one of the three flicker rhythms (1, 2 or 3). */
internal data class MuertosFlame(val x: Float, val y: Float, val scale: Float, val variant: Int)

/** The warm glow around a candle's flame. */
internal data class MuertosGlow(val x: Float, val y: Float, val radius: Float)

/** A falling marigold petal: [x], [y] is the tip of the 4 x 10 petal shape, turned [rotation] degrees; [slow] is the 8.5 s rhythm. */
internal data class MuertosPetal(val x: Float, val y: Float, val rotation: Float, val color: Color, val slow: Boolean)

/** A monarch: body centre, turn in degrees, scale of the 60-unit wingspan, and which of three float rhythms (1, 2 or 3). */
internal data class MuertosMonarch(val x: Float, val y: Float, val rotation: Float, val scale: Float, val float: Int)

/** A papel picado flag in the title bar: its top-left corner, colour, cut-out [kind] (0 skull, 1 flower) and sway rhythm. */
internal data class MuertosFlag(val x: Float, val y: Float, val color: Color, val kind: Int, val slowSway: Boolean)

internal val PICTURE_GLOWS = listOf(
    MuertosGlow(101f, 170f, 22f),
    MuertosGlow(199f, 170f, 22f),
    MuertosGlow(38f, 266f, 30f),
    MuertosGlow(262f, 266f, 30f),
    MuertosGlow(60f, 282f, 22f),
    MuertosGlow(240f, 282f, 22f),
)
internal val PICTURE_FLAMES = listOf(
    MuertosFlame(101f, 175f, 1f, 1),
    MuertosFlame(199f, 175f, 1f, 2),
    MuertosFlame(38f, 271f, 1.25f, 1),
    MuertosFlame(262f, 271f, 1.25f, 3),
    MuertosFlame(60f, 287f, 1f, 2),
    MuertosFlame(240f, 287f, 1f, 1),
)
internal val PICTURE_PETALS = listOf(
    MuertosPetal(40f, 20f, 20f, Color(0xFFff9a1f), slow = false),
    MuertosPetal(250f, 50f, -30f, Color(0xFFffc02e), slow = true),
    MuertosPetal(110f, 12f, 40f, Color(0xFFe8650c), slow = false),
    MuertosPetal(190f, 40f, 70f, Color(0xFFff9a1f), slow = true),
    MuertosPetal(76f, 90f, -50f, Color(0xFFffc02e), slow = true),
)
internal val PICTURE_MONARCHS = listOf(
    MuertosMonarch(38f, 180f, -18f, 0.5f, float = 1),
    MuertosMonarch(264f, 216f, 20f, 0.42f, float = 2),
    MuertosMonarch(150f, 104f, 8f, 0.36f, float = 3),
)

/** Copal smoke over the burner: path, stroke width, opacity, and whether it runs half a cycle later. */
internal data class MuertosSmoke(val d: String, val width: Float, val alpha: Float, val late: Boolean)

internal val PICTURE_SMOKE = listOf(
    MuertosSmoke("M150 284c-5 -7 5 -11 0 -18s5 -11 0 -16", 3f, 0.55f, late = false),
    MuertosSmoke("M155 282c-4 -6 4 -9 0 -15s4 -9 0 -13", 2.4f, 0.45f, late = true),
)
internal val COMPANION_PETALS = listOf(
    MuertosPetal(30f, 30f, 25f, Color(0xFFff9a1f), slow = false),
    MuertosPetal(52f, 60f, -20f, Color(0xFFffc02e), slow = true),
)
internal val COMPANION_MONARCHS = listOf(
    MuertosMonarch(204f, 62f, 16f, 0.62f, float = 1),
)

/** The flame: outer and inner tongue, drawn from the base centre. */
internal const val FLAME_OUTER = "M0 -14C5.5 -7 6 -1 0 2C-6 -1 -5.5 -7 0 -14Z"
internal const val FLAME_INNER = "M0 -8C2.8 -4 3 -1 0 1C-3 -1 -2.8 -4 0 -8Z"
internal const val PETAL = "M0 0c3 2 4 6 0 10c-4 -4 -3 -8 0 -10z"

/** The monarch's body and antennae (the wings are monarch-wings.svg, centred on the same origin), with their stroke widths. */
internal const val MONARCH_BODY = "M0 -9V16"
internal const val MONARCH_BODY_WIDTH = 3.2f
internal const val MONARCH_ANTENNAE = "M0 -9C-1 -13 -4 -15 -6 -16M0 -9C1 -13 4 -15 6 -16"
internal const val MONARCH_ANTENNAE_WIDTH = 1f

/** The title bar's string, with the flags hanging from it. */
internal const val TITLE_WIRE = "M84 2Q169.0 8 254 2Q339.0 8 424 2Q509.0 8 594 2Q679.0 8 764 2Q849.0 8 934 2Q1019.0 8 1104 2Q1189.0 8 1274 2" +
    "Q1359.0 8 1444 2Q1529.0 8 1614 2"

/** The two flag cut-out patterns, from the flag's top-left corner; fill with even-odd so the cut-outs show the bar behind. */
internal val FLAG_SHAPES = listOf(
    "M0 0h30v21q-3 4.6 -6 0q-3 4.6 -6 0q-3 4.6 -6 0q-3 4.6 -6 0q-3 4.6 -6 0ZM8.8 9.9a6.2 6.2 0 1 0 12.4 0a6.2 6.2 0 1 0 -12.4 0ZM11.8 14.9h6.4v3.4" +
        "h-6.4ZM10.7 9.5a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0ZM15.9 9.5a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0ZM4.2 7.9l1.9 2.6l-1.9 2.6" +
        "l-1.9 -2.6ZM25.8 7.9l1.9 2.6l-1.9 2.6l-1.9 -2.6ZM3.4 2.6a0.8 0.8 0 1 0 1.7 0a0.8 0.8 0 1 0 -1.7 0ZM8.8 2.6a0.8 0.8 0 1 0 1.7 0" +
        "a0.8 0.8 0 1 0 -1.7 0ZM14.2 2.6a0.8 0.8 0 1 0 1.7 0a0.8 0.8 0 1 0 -1.7 0ZM19.6 2.6a0.8 0.8 0 1 0 1.7 0a0.8 0.8 0 1 0 -1.7 0ZM25 2.6" +
        "a0.8 0.8 0 1 0 1.7 0a0.8 0.8 0 1 0 -1.7 0Z",
    "M0 0h30v21q-3 4.6 -6 0q-3 4.6 -6 0q-3 4.6 -6 0q-3 4.6 -6 0q-3 4.6 -6 0ZM13 10.5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0ZM18.4 10.5a1.8 1.8 0 1 0 3.6 0" +
        "a1.8 1.8 0 1 0 -3.6 0ZM15.8 15a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0ZM10.6 15a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0ZM8 10.5" +
        "a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0ZM10.6 6a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0ZM15.8 6a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0Z" +
        "M4.2 7.9l1.9 2.6l-1.9 2.6l-1.9 -2.6ZM25.8 7.9l1.9 2.6l-1.9 2.6l-1.9 -2.6ZM3.3 2.6a0.8 0.8 0 1 0 1.7 0a0.8 0.8 0 1 0 -1.7 0ZM8.8 2.6" +
        "a0.8 0.8 0 1 0 1.7 0a0.8 0.8 0 1 0 -1.7 0ZM14.2 2.6a0.8 0.8 0 1 0 1.7 0a0.8 0.8 0 1 0 -1.7 0ZM19.6 2.6a0.8 0.8 0 1 0 1.7 0" +
        "a0.8 0.8 0 1 0 -1.7 0ZM25 2.6a0.8 0.8 0 1 0 1.7 0a0.8 0.8 0 1 0 -1.7 0Z",
)
internal val TITLE_FLAGS = listOf(
    MuertosFlag(96f, 2.8f, Color(0xFFe6007e), 0, slowSway = true),
    MuertosFlag(131f, 3.4f, Color(0xFFff9a1f), 1, slowSway = false),
    MuertosFlag(166f, 3.5f, Color(0xFF12b5b0), 0, slowSway = true),
    MuertosFlag(201f, 3f, Color(0xFF8a3fd1), 1, slowSway = false),
    MuertosFlag(236f, 2.1f, Color(0xFFffc02e), 0, slowSway = true),
    MuertosFlag(271f, 2.9f, Color(0xFFe6007e), 1, slowSway = false),
    MuertosFlag(306f, 3.4f, Color(0xFFff9a1f), 0, slowSway = true),
    MuertosFlag(341f, 3.4f, Color(0xFF12b5b0), 1, slowSway = false),
    MuertosFlag(376f, 2.9f, Color(0xFF8a3fd1), 0, slowSway = true),
    MuertosFlag(411f, 2.1f, Color(0xFFffc02e), 1, slowSway = false),
    MuertosFlag(446f, 3f, Color(0xFFe6007e), 0, slowSway = true),
    MuertosFlag(481f, 3.5f, Color(0xFFff9a1f), 1, slowSway = false),
    MuertosFlag(516f, 3.4f, Color(0xFF12b5b0), 0, slowSway = true),
    MuertosFlag(551f, 2.8f, Color(0xFF8a3fd1), 1, slowSway = false),
    MuertosFlag(586f, 2.2f, Color(0xFFffc02e), 0, slowSway = true),
    MuertosFlag(621f, 3.1f, Color(0xFFe6007e), 1, slowSway = false),
    MuertosFlag(656f, 3.5f, Color(0xFFff9a1f), 0, slowSway = true),
    MuertosFlag(936f, 2.5f, Color(0xFF12b5b0), 1, slowSway = false),
    MuertosFlag(971f, 3.3f, Color(0xFF8a3fd1), 0, slowSway = true),
    MuertosFlag(1006f, 3.5f, Color(0xFFffc02e), 1, slowSway = false),
    MuertosFlag(1041f, 3.2f, Color(0xFFe6007e), 0, slowSway = true),
    MuertosFlag(1076f, 2.4f, Color(0xFFff9a1f), 1, slowSway = false),
    MuertosFlag(1111f, 2.7f, Color(0xFF12b5b0), 0, slowSway = true),
    MuertosFlag(1146f, 3.3f, Color(0xFF8a3fd1), 1, slowSway = false),
    MuertosFlag(1181f, 3.5f, Color(0xFFffc02e), 0, slowSway = true),
    MuertosFlag(1216f, 3.1f, Color(0xFFe6007e), 1, slowSway = false),
    MuertosFlag(1251f, 2.3f, Color(0xFFff9a1f), 0, slowSway = true),
    MuertosFlag(1286f, 2.8f, Color(0xFF12b5b0), 1, slowSway = false),
    MuertosFlag(1321f, 3.4f, Color(0xFF8a3fd1), 0, slowSway = true),
    MuertosFlag(1356f, 3.5f, Color(0xFFffc02e), 1, slowSway = false),
    MuertosFlag(1391f, 3f, Color(0xFFe6007e), 0, slowSway = true),
    MuertosFlag(1426f, 2.1f, Color(0xFFff9a1f), 1, slowSway = false),
    MuertosFlag(1461f, 2.9f, Color(0xFF12b5b0), 0, slowSway = true),
    MuertosFlag(1496f, 3.4f, Color(0xFF8a3fd1), 1, slowSway = false),
    MuertosFlag(1531f, 3.4f, Color(0xFFffc02e), 0, slowSway = true),
)
