package com.openlog.ui

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openlog.model.ThemePreset

private const val ALPHA_HOVER = 0.04f
private const val ALPHA_ACCENT_BG = 0.15f
private const val ALPHA_SELECTION = 0.18f

data class ThemeColors(
    val bg: Color, val p: Color, val p2: Color, val br: Color,
    val tx: Color, val ts: Color, val td: Color,
    val ac: Color, val abg: Color, val sl: Color, val hv: Color,
    val seq1: Color, val seq2: Color,
)

private fun theme(
    bg: Long, p: Long, p2: Long, br: Long,
    tx: Long, ts: Long, td: Long,
    ac: Long, seq1: Long, seq2: Long,
    hv: Color = Color.White.copy(ALPHA_HOVER),
) = ThemeColors(
    bg = Color(bg), p = Color(p), p2 = Color(p2), br = Color(br),
    tx = Color(tx), ts = Color(ts), td = Color(td),
    ac = Color(ac), abg = Color(ac).copy(ALPHA_ACCENT_BG), sl = Color(ac).copy(ALPHA_SELECTION), hv = hv,
    seq1 = Color(seq1), seq2 = Color(seq2),
)

val DARK_GITHUB  = theme(0xFF0d1117, 0xFF161b22, 0xFF1c2128, 0xFF21262d, 0xFFc9d1d9, 0xFF8b949e, 0xFF6e7681, 0xFF388bfd, 0xFF8957e5, 0xFFf0883e)
val LIGHT_THEME  = theme(0xFFf6f8fa, 0xFFffffff, 0xFFf0f2f5, 0xFFd0d7de, 0xFF1f2328, 0xFF636c76, 0xFF9198a1, 0xFF0969da, 0xFF8250df, 0xFFbc4c00,
    hv = Color.Black.copy(ALPHA_HOVER))
val DRACULA      = theme(0xFF282a36, 0xFF21222c, 0xFF191a21, 0xFF44475a, 0xFFf8f8f2, 0xFF6272a4, 0xFF4d5566, 0xFF8be9fd, 0xFFbd93f9, 0xFFffb86c)
val SOLARIZED_DK = theme(0xFF002b36, 0xFF073642, 0xFF00212b, 0xFF094252, 0xFF839496, 0xFF657b83, 0xFF586e75, 0xFF268bd2, 0xFF6c71c4, 0xFFcb4b16)

fun themeColors(preset: ThemePreset) = when (preset) {
    ThemePreset.DARK_GITHUB    -> DARK_GITHUB
    ThemePreset.LIGHT          -> LIGHT_THEME
    ThemePreset.DRACULA        -> DRACULA
    ThemePreset.SOLARIZED_DARK -> SOLARIZED_DK
}

fun appScrollbarStyle(tc: ThemeColors) = ScrollbarStyle(
    minimalHeight = 24.dp,
    thickness = 10.dp,
    shape = CORNER_SM,
    hoverDurationMillis = 120,
    unhoverColor = tc.ac.copy(alpha = 0.42f),
    hoverColor = tc.ac.copy(alpha = 0.82f),
)

val LocalTheme     = staticCompositionLocalOf { LIGHT_THEME }
val LocalFontBase  = staticCompositionLocalOf { 12 }
val LocalUseMono   = staticCompositionLocalOf { true }

val HL_COLORS = listOf(
    Color(0xFFfacc15), Color(0xFFf97316), Color(0xFFec4899), Color(0xFF22c55e),
    Color(0xFF06b6d4), Color(0xFFa78bfa), Color(0xFF38bdf8), Color(0xFFfb923c),
    Color(0xFF4ade80), Color(0xFFf472b6), Color(0xFFe879f9), Color(0xFF34d399),
    Color(0xFFfde047), Color(0xFFfb7185), Color(0xFFc084fc), Color(0xFF2dd4bf),
    Color(0xFF818cf8), Color(0xFFbef264), Color(0xFFfda4af), Color(0xFF67e8f9),
    Color(0xFFd946ef), Color(0xFF84cc16), Color(0xFFef4444), Color(0xFF14b8a6),
)
val SEQ_COLORS = listOf(
    Color(0xFF8957e5), Color(0xFFf0883e), Color(0xFF3fb950), Color(0xFF388bfd),
    Color(0xFFec4899), Color(0xFF06b6d4), Color(0xFFfacc15), Color(0xFFa78bfa),
    Color(0xFF34d399), Color(0xFFf87171), Color(0xFF60a5fa), Color(0xFFfb923c),
    Color(0xFF22c55e), Color(0xFFeab308), Color(0xFF8b5cf6), Color(0xFF0ea5e9),
    Color(0xFFf43f5e), Color(0xFF10b981), Color(0xFF6366f1), Color(0xFFd946ef),
    Color(0xFF84cc16), Color(0xFFef4444), Color(0xFF14b8a6), Color(0xFFf59e0b),
)

val MONO = FontFamily.Monospace
val UI   = FontFamily.Default

// Semantic colour constants (theme-agnostic — same across all themes)
val DANGER_RED = Color(0xFFf85149)   // error / danger / exclude
val PKG_CYAN   = Color(0xFF06b6d4)   // package-prefix indicator

// Corner radius tokens
val CORNER_SM = RoundedCornerShape(3.dp)   // badges, text fields, small pills
val CORNER_MD = RoundedCornerShape(4.dp)   // buttons (PillBtn, ToolbarBtn), note rows

// Log-row layout constants
val INDENT_STEP   = 18.dp   // per-level nesting indent
val ROW_START_PAD = 11.dp   // base horizontal start padding before indent
val ROW_V_PAD     =  3.dp   // vertical (top/bottom) padding for all log rows
