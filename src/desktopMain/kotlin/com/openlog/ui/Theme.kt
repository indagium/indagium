package com.openlog.ui

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openlog.model.ThemePreset

private const val ALPHA_HOVER = 0.04f
private const val ALPHA_ACCENT_BG = 0.15f
private const val ALPHA_SELECTION = 0.18f

// Search highlights derive from the active theme's own palette so they stay on-theme in every
// preset (a rose theme gets rose/teal, not a fixed blue/orange): the current match uses the accent
// colour, other matches the secondary sequence colour. Alpha is tuned per light/dark (dark panels
// need more opacity to register), and the current match gets more than the others so it dominates.
private const val LIGHT_LUMINANCE_THRESHOLD = 0.5f
private const val ALPHA_SEARCH_MATCH_LIGHT = 0.26f
private const val ALPHA_SEARCH_MATCH_DARK = 0.34f
private const val ALPHA_SEARCH_CURRENT_LIGHT = 0.42f
private const val ALPHA_SEARCH_CURRENT_DARK = 0.55f

data class ThemeColors(
    val bg: Color, val p: Color, val p2: Color, val br: Color,
    val tx: Color, val ts: Color, val td: Color,
    val ac: Color, val abg: Color, val sl: Color, val hv: Color,
    val seq1: Color, val seq2: Color,
    // In-view "Find" bar match backgrounds (ui/SearchBar.kt, buildFullLineAnnotation in LogViewer.kt):
    // searchMatchBg tints every match, searchCurrentBg the one Enter/Next/Prev last landed on. Derived
    // per-theme (see theme()) so both read correctly on light vs dark backgrounds instead of the old
    // fixed constants that looked identical in every palette.
    val searchMatchBg: Color, val searchCurrentBg: Color,
)

// Relative luminance of a packed 0xAARRGGBB background — lets theme() pick light- vs dark-tuned
// search-highlight alphas without every preset having to declare which family it belongs to.
private fun bgIsLight(argb: Long): Boolean = Color(argb).luminance() > LIGHT_LUMINANCE_THRESHOLD

private fun theme(
    bg: Long, p: Long, p2: Long, br: Long,
    tx: Long, ts: Long, td: Long,
    ac: Long, seq1: Long, seq2: Long,
    hv: Color = Color.White.copy(ALPHA_HOVER),
): ThemeColors {
    val light = bgIsLight(bg)
    return ThemeColors(
        bg = Color(bg), p = Color(p), p2 = Color(p2), br = Color(br),
        tx = Color(tx), ts = Color(ts), td = Color(td),
        ac = Color(ac), abg = Color(ac).copy(ALPHA_ACCENT_BG), sl = Color(ac).copy(ALPHA_SELECTION), hv = hv,
        seq1 = Color(seq1), seq2 = Color(seq2),
        // Other matches: the theme's secondary sequence colour. Current match: the theme's accent,
        // at higher alpha so it stands out. Both track the palette, so every preset stays on-theme.
        searchMatchBg = Color(seq2).copy(alpha = if (light) ALPHA_SEARCH_MATCH_LIGHT else ALPHA_SEARCH_MATCH_DARK),
        searchCurrentBg = Color(ac).copy(alpha = if (light) ALPHA_SEARCH_CURRENT_LIGHT else ALPHA_SEARCH_CURRENT_DARK),
    )
}

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
val CATPPUCCIN_LATTE = theme(
    0xFFeff1f5, 0xFFffffff, 0xFFe6e9ef, 0xFFccd0da, 0xFF4c4f69, 0xFF6c6f85,
    0xFF9ca0b0, 0xFF1e66f5, 0xFF8839ef, 0xFFfe640b,
    hv = Color.Black.copy(ALPHA_HOVER),
)
val AQUARIUM_MIST = theme(
    0xFFeefbfb, 0xFFfbffff, 0xFFd8f2f2, 0xFFb7dede, 0xFF1c3438, 0xFF4d6a70,
    0xFF7e9ba0, 0xFF0e8f9a, 0xFF67b7dc, 0xFFf08a7d,
    hv = Color.Black.copy(ALPHA_HOVER),
)
val TIDEPOOL_LIGHT = theme(
    0xFFedf6f4, 0xFFf8fffc, 0xFFdcece8, 0xFFb9d1cc, 0xFF203134, 0xFF53686b,
    0xFF82979a, 0xFF0f766e, 0xFF2563eb, 0xFFd97706,
    hv = Color.Black.copy(ALPHA_HOVER),
)
val WAVE_FOAM = theme(
    0xFFf4fbff, 0xFFffffff, 0xFFe0f2fe, 0xFFbad7e8, 0xFF163044, 0xFF52697a,
    0xFF8497a6, 0xFF0284c7, 0xFF22d3ee, 0xFFf97316,
    hv = Color.Black.copy(ALPHA_HOVER),
)
val TOKYO_NIGHT = theme(
    0xFF1a1b26, 0xFF24283b, 0xFF1f2335, 0xFF3b4261, 0xFFc0caf5, 0xFF9aa5ce,
    0xFF6f7aa2, 0xFF7aa2f7, 0xFFbb9af7, 0xFFff9e64,
)
val GRUVBOX = theme(
    0xFF282828, 0xFF32302f, 0xFF3c3836, 0xFF504945, 0xFFebdbb2, 0xFFd5c4a1,
    0xFF928374, 0xFF83a598, 0xFFb16286, 0xFFd65d0e,
)
val DEEP_CURRENT = theme(
    0xFF061923, 0xFF0b2430, 0xFF102f3d, 0xFF1e4b5c, 0xFFd8f3f8, 0xFF9ac4cc,
    0xFF6e929a, 0xFF2dd4bf, 0xFF38bdf8, 0xFFfb7185,
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

private val THEME_PALETTES = mapOf(
    ThemePreset.LIGHT to LIGHT_THEME,
    ThemePreset.LIGHT_PRO to LIGHT_PRO,
    ThemePreset.LIGHT_CONTRAST to LIGHT_CONTRAST,
    ThemePreset.NORD_LIGHT to NORD_LIGHT,
    ThemePreset.WARM_PAPER to WARM_PAPER,
    ThemePreset.SAGE_PAPER to SAGE_PAPER,
    ThemePreset.ROSE_PAPER to ROSE_PAPER,
    ThemePreset.INK_PAPER to INK_PAPER,
    ThemePreset.CATPPUCCIN_LATTE to CATPPUCCIN_LATTE,
    ThemePreset.AQUARIUM_MIST to AQUARIUM_MIST,
    ThemePreset.TIDEPOOL_LIGHT to TIDEPOOL_LIGHT,
    ThemePreset.WAVE_FOAM to WAVE_FOAM,
    ThemePreset.TOKYO_NIGHT to TOKYO_NIGHT,
    ThemePreset.GRUVBOX to GRUVBOX,
    ThemePreset.DEEP_CURRENT to DEEP_CURRENT,
    ThemePreset.DARK_GITHUB to DARK_GITHUB,
    ThemePreset.DRACULA to DRACULA,
    ThemePreset.SOLARIZED_DARK to SOLARIZED_DK,
    ThemePreset.GRAPHITE_DIM to GRAPHITE_DIM,
    ThemePreset.TERMINAL_DARK to TERMINAL_DARK,
)

fun themeColors(preset: ThemePreset) = THEME_PALETTES.getValue(preset)

fun loadingOverlayBackground(tc: ThemeColors) = tc.bg

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
    Color(0xFF6366f1),
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
val ROW_NUM_GAP = 8.dp   // gap between the optional row-number gutter and the row content (Settings → Row number)

// Approximate monospace digit advance as a fraction of font size, used to size the optional
// row-number gutter (LogRow) and its "#" header cell (ColHeader) to their digit count, so short
// numbers hug the left edge instead of floating in a fixed-width cell. Shared by both so the header
// cell and the row gutter below it use the same width formula. Slightly generous so the last digit
// never clips under overflow=Clip.
private const val MONO_DIGIT_EM = 0.65f

fun rowNumberColumnWidth(fontSizeSp: Float, digitCount: Int): Dp =
    (digitCount.coerceAtLeast(1) * fontSizeSp * MONO_DIGIT_EM).dp

// Character budget for the Δt gutter cell (utils/LogTime.kt's formatDelta), sized to the widest
// delta string actually rendered for THIS tab/mode — not a fixed worst case ("+1h02m03s" style
// budgets left ~3 chars of permanent blank space on the common "+0.005"-shaped case). Callers
// derive charCount once per tab/selection-anchor via LogViewer.kt's rememberTimeDeltaChars (see its
// doc for how that's estimated cheaply without scanning every row) and pass the SAME int to both
// this (row) and ColHeader's own call, exactly mirroring how rowNumberColumnWidth/rowNumDigits keep
// the row gutter and its header cell in lockstep despite using different font sizes.
fun timeDeltaColumnWidth(fontSizeSp: Float, charCount: Int): Dp =
    (charCount.coerceAtLeast(1) * fontSizeSp * MONO_DIGIT_EM).dp

// NOTE: there is deliberately no width helper here for the tid-map gutter (unlike
// rowNumberColumnWidth/timeDeltaColumnWidth above) — it doesn't need one. Two earlier versions
// tried to fit it BETWEEN the timestamp and PID columns: first by assuming "HH:MM:SS.mmm" is
// always 12 characters and multiplying by MONO_DIGIT_EM, then by measuring the row's own rendered
// ts text via TextMeasurer. Both were fragile for the same underlying reason — trying to land
// inside a 2-character gap that's part of one continuous monospace string, where even a correct
// measurement leaves almost no margin for error. The gutter is now a fixed-width LEADING column
// (ui/TidMap.kt's TID_MAP_HIT_WIDTH), positioned before the timestamp entirely, reserved by a real
// layout Spacer in LogRow/ColHeader/the header-row variants — the same robust pattern
// rowNumberColumnWidth/timeDeltaColumnWidth already use, needing no measurement at all.
