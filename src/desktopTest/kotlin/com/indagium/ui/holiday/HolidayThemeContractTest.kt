// Slot sizes, contrast ratios and counts of the contract checks are literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.indagium.model.HolidayPack
import com.indagium.ui.ThemeColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The checks every registered [HolidayTheme] must pass. Registering a new theme in [HOLIDAY_THEMES] is all it takes to have it tested here:
 * palettes as legible as every preset, a complete icon set, unique effect ids, valid dates, a scene that stays in its slot, sane sizes.
 */
class HolidayThemeContractTest {
    private val themes = HOLIDAY_THEMES.values.toList()

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + LUMINANCE_OFFSET) / (minOf(la, lb) + LUMINANCE_OFFSET)
    }

    private fun eachPalette(check: (String, ThemeColors) -> Unit) = themes.forEach { theme ->
        listOf(true, false).forEach { dark -> check("${theme.pack} ${if (dark) "dark" else "light"}", theme.palette(dark)) }
    }

    @Test
    fun everyPackHasExactlyOneRegisteredTheme() {
        assertTrue(themes.isNotEmpty())
        assertEquals(HolidayPack.entries.toSet(), HOLIDAY_THEMES.keys, "every pack is registered")
        HOLIDAY_THEMES.forEach { (pack, theme) -> assertEquals(pack, theme.pack, "registered under its own pack") }
        themes.forEach { assertTrue(it.label.isNotBlank() && it.occasion.isNotBlank(), "${it.pack} needs a label and an occasion") }
    }

    @Test
    fun successReachesThreeToOneOnBackgroundAndPanelAndIsGreen() = eachPalette { name, tc ->
        assertTrue(contrast(tc.success, tc.bg) >= MIN_SUCCESS, "$name success on bg: ${contrast(tc.success, tc.bg)}")
        assertTrue(contrast(tc.success, tc.p) >= MIN_SUCCESS, "$name success on panel: ${contrast(tc.success, tc.p)}")
        assertTrue(tc.success.green > tc.success.red && tc.success.green > tc.success.blue, "$name success is not green")
    }

    @Test
    fun warnAndOkAreSolidDistinctAndReadableAgainstTheBackground() = eachPalette { name, tc ->
        for ((role, color) in listOf("warn" to tc.warn, "ok" to tc.ok)) {
            assertEquals(1f, color.alpha, "$name: $role must be a solid colour")
            assertNotEquals(tc.bg, color, "$name: $role must not collapse into the background")
            assertTrue(contrast(color, tc.bg) >= MIN_WARN_OK, "$name: $role contrast ${contrast(color, tc.bg)}")
        }
        assertNotEquals(tc.warn, tc.ok, "$name: warn and ok must read as different colours")
    }

    @Test
    fun warnBgIsATranslucentWash() = eachPalette { name, tc ->
        assertTrue(tc.warnBg.alpha > 0f && tc.warnBg.alpha < 1f, "$name warnBg alpha ${tc.warnBg.alpha}")
    }

    @Test
    fun bodyTextIsAA() = eachPalette { name, tc ->
        assertTrue(contrast(tc.tx, tc.bg) >= MIN_TEXT, "$name tx on bg: ${contrast(tc.tx, tc.bg)}")
        assertTrue(contrast(tc.tx, tc.p) >= MIN_TEXT, "$name tx on panel: ${contrast(tc.tx, tc.p)}")
    }

    @Test
    fun textOnAccentIsAA() = eachPalette { name, tc ->
        assertTrue(contrast(tc.onAc, tc.ac) >= MIN_TEXT, "$name onAc on ac: ${contrast(tc.onAc, tc.ac)}")
    }

    @Test
    fun palettesHaveDistinctSurfacesAndSeqColours() = eachPalette { name, tc ->
        assertNotEquals(tc.bg, tc.p, "$name needs a distinct panel")
        assertNotEquals(tc.ac, tc.bg, "$name needs a visible accent")
        assertNotEquals(tc.seq1, tc.seq2, "$name needs two sequence colours")
    }

    @Test
    fun anIconSetCoversEveryToolbarButtonWithNamesAndMnemonics() {
        themes.mapNotNull { t -> t.icons?.let { t to it } }.forEach { (theme, set) ->
            listOf(Color.White, Color.Black).forEach { outline ->
                val icons = set.icons(outline)
                assertEquals(ToolbarIconId.entries.toSet(), icons.keys, "${theme.pack} icons")
                icons.forEach { (id, icon) ->
                    assertTrue(icon.name.isNotBlank() && icon.mnemonic.isNotBlank(), "${theme.pack} $id needs a name and a mnemonic")
                    assertEquals(32f, icon.vector.viewportWidth, "${theme.pack} $id")
                }
            }
        }
    }

    @Test
    fun effectIdsAreUniqueAcrossAllThemesAndLabelled() {
        val specs = themes.flatMap { it.effects }
        assertEquals(specs.size, specs.map { it.id }.toSet().size, "effect ids must be globally unique: ${specs.map { it.id }}")
        specs.forEach { assertTrue(it.id.isNotBlank() && it.label.isNotBlank() && it.noun.isNotBlank(), "effect ${it.id} needs an id, a label and a noun") }
    }

    @Test
    fun theDatesAreValidAndIncludeTheirOwnEnds() {
        themes.forEach { theme ->
            val d = theme.dates
            val year = java.time.LocalDate.of(2026, 1, 1)
            assertTrue(d.contains(d.start.atYear(year.year)), "${theme.pack} contains its first day")
            assertTrue(d.contains(d.end.atYear(year.year)), "${theme.pack} contains its last day")
        }
    }

    @Test
    fun aTabSceneStaysInsideItsSlotAtSeveralSizes() {
        themes.mapNotNull { t -> t.tabScene?.let { t to it } }.forEach { (theme, painter) ->
            val horizontal = listOf(Rect(0f, 0f, 160f, 24f), Rect(300f, 0f, 460f, 48f), Rect(50f, 0f, 2450f, 36f), Rect(0f, 0f, 160f, 60f))
            val vertical = listOf(Rect(0f, 0f, 120f, 300f), Rect(0f, 300f, 200f, 800f), Rect(10f, 100f, 370f, 1500f), Rect(0f, 760f, 200f, 800f))
            (horizontal.map { it to false } + vertical.map { it to true }).forEach { (slot, isVertical) ->
                val layout = painter.layout(slot, isVertical)
                assertEquals(slot, layout.slot, "${theme.pack} reports the slot it was given")
                layout.elementBounds.forEach {
                    val inside = it.left >= slot.left - EPS && it.right <= slot.right + EPS && it.top >= slot.top - EPS && it.bottom <= slot.bottom + EPS
                    assertTrue(inside, "${theme.pack} element $it escapes $slot (vertical=$isVertical)")
                }
            }
        }
    }

    @Test
    fun designSizesAndCornersArePositive() {
        themes.forEach { theme ->
            theme.picture?.let { assertTrue(it.designWidthDp > 0f && it.designHeightDp > 0f, "${theme.pack} picture design size") }
            theme.companion?.let {
                assertTrue(it.designSizeDp > 0f, "${theme.pack} companion design size")
                assertTrue(it.label.isNotBlank(), "${theme.pack} companion needs a label for the menu")
            }
            assertTrue(theme.corners.tabDp > 0f && theme.corners.toolbarDp > 0 && theme.corners.tileDp > 0, "${theme.pack} corners")
        }
    }

    @Test
    fun theChromeColoursAreSolidAndTheSceneIsDistinctFromThePanel() {
        themes.forEach { theme ->
            listOf(true, false).forEach { dark ->
                val c = theme.chrome(dark)
                assertEquals(1f, c.tabBar.alpha, "${theme.pack} tab bar colour is solid")
                assertEquals(1f, c.tabBg.alpha, "${theme.pack} tab fill colour is solid")
                assertNotEquals(c.tabBar, theme.palette(dark).p, "${theme.pack} tab bar differs from the panel")
            }
        }
    }

    private companion object {
        const val LUMINANCE_OFFSET = 0.05
        const val MIN_SUCCESS = 3.0
        const val MIN_WARN_OK = 1.5
        const val MIN_TEXT = 4.5
        const val EPS = 0.01f
    }
}
