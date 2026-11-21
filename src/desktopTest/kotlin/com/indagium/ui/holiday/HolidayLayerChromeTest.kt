@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.indagium.model.AppSettings
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.ui.LocalTheme
import com.indagium.ui.TabShell
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A fake theme that adds a label prefix and an uneven ribbon: the two contract additions of the layered packs. */
private object PrefixTheme : HolidayTheme by FakeHolidayTheme {
    override val tabLabelPrefix = "~/ "

    override fun tileDividerBlocks(dark: Boolean) = HolidayDividerBlocks(RIBBON, blockDp = 4f, heightDp = 3f, widthsDp = RIBBON_WIDTHS)
}

private val RIBBON = listOf(Color(0xFFC62828), Color(0xFF1B7A3E), Color(0xFFE0B040), Color(0xFF1B7A3E), Color(0xFF0E4A28))
private val RIBBON_WIDTHS = listOf(7f, 6f, 1f, 6f, 2f)

@OptIn(ExperimentalTestApi::class)
class HolidayLayerChromeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test fun uniformBlocksStayAsTheyWere() {
        val blocks = HolidayDividerBlocks(RIBBON, blockDp = 34f, heightDp = 3f)
        val segments = holidayDividerSegments(blocks, 204f)
        assertEquals(6, segments.size)
        segments.forEachIndexed { i, s ->
            assertEquals(i * 34f, s.startDp, 0f)
            assertEquals(34f, s.widthDp, 0f)
            assertEquals(RIBBON[i % 5], s.color)
        }
        assertEquals(16f, holidayDividerSegments(blocks, 50f).last().let { it.widthDp }, 0f)
    }

    @Test fun unevenBlocksUseTheirOwnWidthsAndRepeat() {
        val blocks = HolidayDividerBlocks(RIBBON, blockDp = 4f, heightDp = 3f, widthsDp = RIBBON_WIDTHS)
        val segments = holidayDividerSegments(blocks, 50f)
        // 7 + 6 + 1 + 6 + 2 = 22 dp per pattern.
        assertEquals(listOf(0f, 7f, 13f, 14f, 20f, 22f, 29f, 35f, 36f, 42f, 44f), segments.map { it.startDp })
        assertEquals(listOf(7f, 6f, 1f, 6f, 2f, 7f, 6f, 1f, 6f, 2f, 6f), segments.map { it.widthDp })
        assertEquals(RIBBON + RIBBON + RIBBON[0], segments.map { it.color })
        assertEquals(50f, segments.last().let { it.startDp + it.widthDp }, 0f, "the last block ends at the edge")
        assertTrue(holidayDividerSegments(blocks.copy(widthsDp = listOf(0f)), 50f).isEmpty(), "no positive width draws nothing")
        assertTrue(holidayDividerSegments(blocks.copy(colors = emptyList()), 50f).isEmpty())
        assertEquals(listOf(0f, 3f), holidayDividerSegments(blocks.copy(widthsDp = listOf(3f, 0f)), 6f).map { it.startDp }, "a zero width is skipped")
    }

    @Test fun theUnevenBlockDividerPaintsEachBlockAtItsWidth() {
        val blocks = PrefixTheme.tileDividerBlocks(true)
        rule.setContent { Box(Modifier.width(50.dp).testTag("divider")) { HolidayBlockDivider(blocks) } }
        val pixels = rule.onNodeWithTag("divider").captureToImage().toPixelMap()
        val density = rule.density.density

        fun at(dp: Float) = pixels[(dp * density).toInt(), 1].toArgb()
        assertEquals(RIBBON[0].toArgb(), at(3f), "red 7")
        assertEquals(RIBBON[1].toArgb(), at(10f), "green 6")
        assertEquals(RIBBON[2].toArgb(), at(13.5f), "gold 1")
        assertEquals(RIBBON[1].toArgb(), at(17f), "green 6")
        assertEquals(RIBBON[4].toArgb(), at(21f), "dark green 2")
        assertEquals(RIBBON[0].toArgb(), at(25f), "the ribbon repeats from red")
        assertEquals(pixels.height, (3 * density).toInt())
    }

    private fun lookFor(theme: HolidayTheme, own: Boolean = false): HolidayLook {
        val owned = if (own) setOf(HolidayPack.PUMPKIN_PATCH) else emptySet()
        val settings = AppSettings().withHoliday { it.copy(mode = HolidayMode.ALWAYS, ownTabStylePacks = owned) }
        return assertNotNull(resolveHolidayLook(settings, LocalDate.of(2026, 6, 10), holidayThemeRegistry(theme)))
    }

    private fun show(look: HolidayLook, active: Boolean = false) {
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides look.colors, LocalHoliday provides look) {
                Box(Modifier.width(260.dp)) { TabShell("a", "alpha.log", "alpha.log", active, false, onClick = {}, onClose = {}) }
            }
        }
        rule.waitForIdle()
    }

    @Test fun theLabelPrefixIsDrawnBeforeTheLabelInTheDimColour() {
        val look = lookFor(PrefixTheme)
        show(look)
        rule.onNodeWithTag("tab-prefix-a", useUnmergedTree = true).assertTextEquals("~/ ")
        val layouts = mutableListOf<TextLayoutResult>()
        val config = rule.onNodeWithTag("tab-prefix-a", useUnmergedTree = true).fetchSemanticsNode().config
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        assertEquals(look.colors.td, layouts.single().layoutInput.style.color)
        val prefix = rule.onNodeWithTag("tab-prefix-a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val label = rule.onNodeWithTag("tab-label-a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(prefix.left >= label.left && prefix.right <= label.right, "inside the label's own row, so no extra width is claimed")
    }

    @Test fun theLabelPrefixAlsoShowsUnderMyTabStyleAndNeverWithoutOne() {
        show(lookFor(PrefixTheme, own = true), active = true)
        rule.onNodeWithTag("tab-prefix-a", useUnmergedTree = true).assertTextEquals("~/ ")
        rule.onAllNodesWithTag("tab-prefix-a", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
    }

    @Test fun aThemeWithoutAPrefixDrawsNone() {
        show(lookFor(FakeHolidayTheme))
        assertEquals(0, rule.onAllNodesWithTag("tab-prefix-a", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(null, FakeHolidayTheme.tabLabelPrefix)
    }
}
