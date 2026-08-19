package com.indagium

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.model.AppSettings
import com.indagium.model.ThemePreset
import com.indagium.ui.Seq3RenderCache
import com.indagium.ui.resolveSeq3ThemeColors
import com.indagium.ui.themeColors
import com.indagium.ui.toSeq3RasterTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Covers `ui/Theme.kt`'s new `warn`/`warnBg`/`ok` roles and `ui/Seq3Theme.kt`'s
 *  `toSeq3RasterTheme()`/`Seq3RenderCache`. */
class Seq3ThemeTest {
    @BeforeTest
    fun resetCache() = Seq3RenderCache.clearForTest()

    // WCAG relative-luminance contrast ratio, same formula ui/Theme.kt's own bgIsLight() luminance
    // check is built on (Color.luminance() already implements WCAG relative luminance).
    private fun contrastRatio(a: Color, b: Color): Float {
        val lighter = maxOf(a.luminance(), b.luminance())
        val darker = minOf(a.luminance(), b.luminance())
        return (lighter + WCAG_CONTRAST_OFFSET) / (darker + WCAG_CONTRAST_OFFSET)
    }

    @Test
    fun everyPresetYieldsAWarnColourDistinctAndReadableAgainstItsOwnBackground() {
        for (preset in ThemePreset.entries) {
            val tc = themeColors(preset)
            assertEquals(1f, tc.warn.alpha, "$preset: warn must be a solid foreground colour")
            assertNotEquals(tc.bg, tc.warn, "$preset: warn must not collapse into the background")
            assertTrue(
                contrastRatio(tc.warn, tc.bg) >= MIN_CONTRAST,
                "$preset: warn contrast ${contrastRatio(tc.warn, tc.bg)} against bg is below $MIN_CONTRAST",
            )
        }
    }

    @Test
    fun everyPresetYieldsAWarnBgThatIsATintNotAnOpaqueOrInvisibleFill() {
        for (preset in ThemePreset.entries) {
            val tc = themeColors(preset)
            assertTrue(tc.warnBg.alpha > 0f, "$preset: warnBg must not be fully transparent")
            assertTrue(tc.warnBg.alpha < 1f, "$preset: warnBg must stay a translucent wash, not a solid fill")
        }
    }

    @Test
    fun everyPresetYieldsAnOkColourDistinctAndReadableAgainstItsOwnBackground() {
        for (preset in ThemePreset.entries) {
            val tc = themeColors(preset)
            assertEquals(1f, tc.ok.alpha, "$preset: ok must be a solid foreground colour")
            assertNotEquals(tc.bg, tc.ok, "$preset: ok must not collapse into the background")
            assertTrue(
                contrastRatio(tc.ok, tc.bg) >= MIN_CONTRAST,
                "$preset: ok contrast ${contrastRatio(tc.ok, tc.bg)} against bg is below $MIN_CONTRAST",
            )
        }
    }

    @Test
    fun warnAndOkAreDistinctRolesInEveryPreset() {
        for (preset in ThemePreset.entries) {
            val tc = themeColors(preset)
            assertNotEquals(tc.warn, tc.ok, "$preset: warn (needs-target) and ok (edited) must read as different colours")
        }
    }

    // ── toSeq3RasterTheme(): exact channel round-tripping ───────────────────────────────────────

    private fun referenceChannel(v: Float): Int = kotlin.math.round(v * REFERENCE_MAX).toInt().coerceIn(0, REFERENCE_MAX.toInt())

    private fun referenceArgb(c: Color): Int {
        val a = referenceChannel(c.alpha)
        val r = referenceChannel(c.red)
        val g = referenceChannel(c.green)
        val b = referenceChannel(c.blue)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    @Test
    fun toSeq3RasterThemeRoundTripsEveryOpaqueRoleExactlyForEveryPreset() {
        for (preset in ThemePreset.entries) {
            val tc = themeColors(preset)
            val raster = tc.toSeq3RasterTheme()
            assertEquals(referenceArgb(tc.bg), raster.background, "$preset background")
            assertEquals(referenceArgb(tc.td), raster.lifeline, "$preset lifeline")
            assertEquals(referenceArgb(tc.p2), raster.headerFill, "$preset headerFill")
            assertEquals(referenceArgb(tc.br), raster.headerBorder, "$preset headerBorder")
            assertEquals(referenceArgb(tc.tx), raster.headerText, "$preset headerText")
            assertEquals(referenceArgb(tc.ts), raster.arrow, "$preset arrow")
            assertEquals(referenceArgb(tc.tx), raster.label, "$preset label")
            assertEquals(referenceArgb(tc.p2), raster.badgeBg, "$preset badgeBg")
            assertEquals(referenceArgb(tc.ts), raster.badgeText, "$preset badgeText")
            assertEquals(referenceArgb(tc.seq1), raster.fragmentBorder, "$preset fragmentBorder")
            assertEquals(referenceArgb(tc.seq2), raster.noteBorder, "$preset noteBorder")
            assertEquals(referenceArgb(tc.tx), raster.noteText, "$preset noteText")
            assertEquals(referenceArgb(tc.warn), raster.warn, "$preset warn")
            assertEquals(referenceArgb(tc.warnBg), raster.warnBg, "$preset warnBg")
        }
    }

    @Test
    fun toSeq3RasterThemeRoundsRatherThanTruncatesTheAlphaChannel() {
        // 0.5f * 255f == 127.5 exactly (255/2 has an exact float32 representation): a truncating
        // conversion reads back 127, the documented-correct roundToInt() reads back 128 — the exact
        // "channel one short" trap ui/DiagramTheming.kt's toAwt() describes, reproduced here with a
        // value chosen to provably land on the boundary rather than merely asserted about.
        val base = themeColors(ThemePreset.LIGHT)
        val tc = base.copy(warnBg = base.warnBg.copy(alpha = HALF_ALPHA))

        val raster = tc.toSeq3RasterTheme()

        val alphaByte = (raster.warnBg ushr 24) and ALPHA_BYTE_MASK
        assertEquals(HALF_ALPHA_ROUNDED_BYTE, alphaByte, "0.5f alpha must round up to 128, not truncate to 127")
    }

    // ── Seq3RenderCache ──────────────────────────────────────────────────────────────────────────

    private fun oneLifelineDocument() = Seq3Document(lifelines = listOf(Seq3Lifeline(id = "l1", name = "One", tagIds = setOf("One"), ordinal = 0)))

    @Test
    fun renderCacheReusesTheSameLayoutAndRasterForIdenticalInputs() {
        val document = oneLifelineDocument()
        val theme = themeColors(ThemePreset.LIGHT).toSeq3RasterTheme()

        val first = Seq3RenderCache.display(document, theme)
        val second = Seq3RenderCache.display(document, theme)

        assertEquals(1, Seq3RenderCache.layoutMissCountForTest(), "an identical document must reuse the cached layout")
        assertEquals(1, Seq3RenderCache.renderMissCountForTest(), "an identical (layout, theme, scale) must reuse the cached raster")
        assertTrue(first === second || first == second, "cached display results must be stable across calls")
    }

    @Test
    fun renderCacheRebuildsOnlyTheRasterWhenOnlyThemeChanges() {
        val document = oneLifelineDocument()
        val light = themeColors(ThemePreset.LIGHT).toSeq3RasterTheme()
        val dark = themeColors(ThemePreset.DARK_GITHUB).toSeq3RasterTheme()

        Seq3RenderCache.display(document, light)
        Seq3RenderCache.display(document, dark)

        assertEquals(1, Seq3RenderCache.layoutMissCountForTest(), "the SAME document must not re-run layout just because the theme changed")
        assertEquals(2, Seq3RenderCache.renderMissCountForTest(), "each distinct theme must still rasterize once")
    }

    // ── WP4: resolveSeq3ThemeColors ─────────────────────────────────────────────────────────────

    @Test
    fun documentThemeOverrideWinsOverTheAppTheme() {
        val document = oneLifelineDocument().copy(themePresetName = ThemePreset.DRACULA.name)
        val settings = AppSettings(theme = ThemePreset.LIGHT)

        val resolved = resolveSeq3ThemeColors(document, settings)

        assertEquals(themeColors(ThemePreset.DRACULA), resolved)
    }

    @Test
    fun nullDocumentThemeFallsBackToTheAppTheme() {
        val document = oneLifelineDocument().copy(themePresetName = null)
        val settings = AppSettings(theme = ThemePreset.GRUVBOX)

        val resolved = resolveSeq3ThemeColors(document, settings)

        assertEquals(themeColors(ThemePreset.GRUVBOX), resolved)
    }

    @Test
    fun anUnknownOrGarbagePresetNameFallsBackRatherThanThrowing() {
        // A document saved by a later build that renamed/removed a preset must still open — this
        // is the exact scenario the runCatching/getOrNull guard in resolveSeq3ThemeColors exists
        // for (themeColors is THEME_PALETTES.getValue(), which throws on a missing key).
        val document = oneLifelineDocument().copy(themePresetName = "NOT_A_REAL_PRESET")
        val settings = AppSettings(theme = ThemePreset.TOKYO_NIGHT)

        val resolved = resolveSeq3ThemeColors(document, settings)

        assertEquals(themeColors(ThemePreset.TOKYO_NIGHT), resolved)
    }

    @Test
    fun stringOverloadAgreesWithTheDocumentOverload() {
        val settings = AppSettings(theme = ThemePreset.LIGHT)
        val document = oneLifelineDocument().copy(themePresetName = ThemePreset.SOLARIZED_DARK.name)

        assertEquals(
            resolveSeq3ThemeColors(document, settings),
            resolveSeq3ThemeColors(document.themePresetName, settings),
        )
    }

    @Test
    fun renderCacheStillDistinguishesTwoDocumentsResolvedToDifferentThemes() {
        // Task 2's own claim: Seq3RenderCache is keyed on (layout, theme, scale) already, so
        // per-document themes need no cache change — verified here rather than assumed, end to
        // end through resolveSeq3ThemeColors rather than a raw Seq3RasterTheme literal. Sampling
        // the top-left pixel (pure background fill, painted before anything else — see
        // Seq3Raster.paintSeq3) is enough to prove the two rasters actually differ, not merely
        // that two distinct cache entries were allocated.
        val document = oneLifelineDocument()
        val darkDocument = document.copy(themePresetName = ThemePreset.DRACULA.name)
        val settings = AppSettings(theme = ThemePreset.LIGHT)

        val followsAppTheme = Seq3RenderCache.display(document, resolveSeq3ThemeColors(document, settings).toSeq3RasterTheme())
        val ownTheme = Seq3RenderCache.display(darkDocument, resolveSeq3ThemeColors(darkDocument, settings).toSeq3RasterTheme())

        assertEquals(2, Seq3RenderCache.renderMissCountForTest(), "each distinct resolved theme must rasterize once")
        assertNotEquals(
            followsAppTheme.rendered.image.getRGB(0, 0),
            ownTheme.rendered.image.getRGB(0, 0),
            "a document with its own theme must actually paint differently from one following the app theme",
        )
    }

    private companion object {
        const val REFERENCE_MAX = 255f
        const val ALPHA_BYTE_MASK = 0xFF
        const val HALF_ALPHA = 0.5f
        const val HALF_ALPHA_ROUNDED_BYTE = 128

        // The WCAG relative-luminance contrast-ratio formula's own constant: (L1+0.05)/(L2+0.05).
        const val WCAG_CONTRAST_OFFSET = 0.05f

        // Deliberately lenient (not WCAG AA's 4.5:1): warn/ok are derived from seq2/seq1, and those
        // two are ALREADY used as foreground text/icon colour elsewhere in this codebase at this
        // same contrast level — LogViewer.kt's SectionBanner draws its label directly in `tc.seq1`
        // today, for every preset including WAVE_FOAM's seq1 (the single worst case, ~1.73:1). This
        // threshold only needs to catch an actual regression (identical to bg, alpha dropped to 0,
        // …), not re-litigate a contrast trade-off this codebase already made and shipped.
        const val MIN_CONTRAST = 1.5f
    }
}
