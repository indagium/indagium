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

val DARK_GITHUB = theme(
    0xFF0d1117, 0xFF161b22, 0xFF1c2128, 0xFF21262d, 0xFFc9d1d9, 0xFF8b949e,
    0xFF6e7681, 0xFF388bfd, 0xFF8957e5, 0xFFf0883e,
)
val LIGHT_THEME = theme(
    0xFFf6f8fa, 0xFFffffff, 0xFFf0f2f5, 0xFFd0d7de, 0xFF1f2328, 0xFF636c76,
    0xFF9198a1, 0xFF0969da, 0xFF8250df, 0xFFbc4c00,
    hv = Color.Black.copy(ALPHA_HOVER),
)
val LIGHT_PRO = theme(
    0xFFeff6ff, 0xFFf8fbff, 0xFFdbeafe, 0xFFb7c7df, 0xFF172033, 0xFF4b5f7a,
    0xFF7a8aa3, 0xFF1d4ed8, 0xFF7c3aed, 0xFFd97706,
    hv = Color.Black.copy(ALPHA_HOVER),
)
val LIGHT_CONTRAST = theme(
    0xFFffffff, 0xFFf3f6fa, 0xFFe8edf5, 0xFFc8d1df, 0xFF0b1220, 0xFF475569,
    0xFF64748b, 0xFF005fcc, 0xFF6d28d9, 0xFFb45309,
    hv = Color.Black.copy(ALPHA_HOVER),
)
val NORD_LIGHT = theme(
    0xFFeceff4, 0xFFf8fafc, 0xFFe5e9f0, 0xFFd8dee9, 0xFF2e3440, 0xFF4c566a,
    0xFF718096, 0xFF5e81ac, 0xFFb48ead, 0xFFd08770,
    hv = Color.Black.copy(ALPHA_HOVER),
)
val WARM_PAPER = theme(
    0xFFfaf8f3, 0xFFfffdf8, 0xFFf1eadf, 0xFFded4c6, 0xFF2a241d, 0xFF62584d,
    0xFF918579, 0xFF0f766e, 0xFF7c3aed, 0xFFc2410c,
    hv = Color.Black.copy(ALPHA_HOVER),
)
val SAGE_PAPER = theme(
    0xFFf4f7f1, 0xFFfcfff8, 0xFFe8efe2, 0xFFcdd8c4, 0xFF243024, 0xFF566252,
    0xFF84907d, 0xFF3f7d58, 0xFF8b5cf6, 0xFFb7791f,
    hv = Color.Black.copy(ALPHA_HOVER),
)
val ROSE_PAPER = theme(
    0xFFfbf5f6, 0xFFfffafb, 0xFFf4e6e8, 0xFFdfc7cd, 0xFF332426, 0xFF6b565a,
    0xFF947e84, 0xFFbe5f73, 0xFF7c3aed, 0xFF0f766e,
    hv = Color.Black.copy(ALPHA_HOVER),
)
val INK_PAPER = theme(
    0xFFf7f1e7, 0xFFfffaf0, 0xFFede3d2, 0xFFd7c6ad, 0xFF241f1a, 0xFF5d5145,
    0xFF8b7c6a, 0xFF334155, 0xFF7c3aed, 0xFFb45309,
    hv = Color.Black.copy(ALPHA_HOVER),
)
val DRACULA = theme(
    0xFF282a36, 0xFF21222c, 0xFF191a21, 0xFF44475a, 0xFFf8f8f2, 0xFF6272a4,
    0xFF4d5566, 0xFF8be9fd, 0xFFbd93f9, 0xFFffb86c,
)
val SOLARIZED_DK = theme(
    0xFF002b36, 0xFF073642, 0xFF00212b, 0xFF094252, 0xFF839496, 0xFF657b83,
    0xFF586e75, 0xFF268bd2, 0xFF6c71c4, 0xFFcb4b16,
)
val GRAPHITE_DIM = theme(
    0xFF20252b, 0xFF2a3038, 0xFF252b32, 0xFF3b4652, 0xFFe5e7eb, 0xFFaab3bf,
    0xFF7f8a99, 0xFF38bdf8, 0xFFa78bfa, 0xFFfb923c,
)
val TERMINAL_DARK = theme(
    0xFF10140f, 0xFF171d15, 0xFF11180f, 0xFF2c3828, 0xFFdce8d4, 0xFF9fb39a,
    0xFF71806d, 0xFF22c55e, 0xFF38bdf8, 0xFFf59e0b,
)

fun themeColors(preset: ThemePreset) = when (preset) {
    ThemePreset.LIGHT          -> LIGHT_THEME
    ThemePreset.LIGHT_PRO      -> LIGHT_PRO
    ThemePreset.LIGHT_CONTRAST -> LIGHT_CONTRAST
    ThemePreset.NORD_LIGHT     -> NORD_LIGHT
    ThemePreset.WARM_PAPER     -> WARM_PAPER
    ThemePreset.SAGE_PAPER     -> SAGE_PAPER
    ThemePreset.ROSE_PAPER     -> ROSE_PAPER
    ThemePreset.INK_PAPER      -> INK_PAPER
    ThemePreset.DARK_GITHUB    -> DARK_GITHUB
    ThemePreset.DRACULA        -> DRACULA
    ThemePreset.SOLARIZED_DARK -> SOLARIZED_DK
    ThemePreset.GRAPHITE_DIM   -> GRAPHITE_DIM
    ThemePreset.TERMINAL_DARK  -> TERMINAL_DARK
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
