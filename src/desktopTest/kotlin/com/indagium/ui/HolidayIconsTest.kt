// The viewport size and hex colours under test are literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.indagium.model.AppSettings
import com.indagium.model.HolidayMode
import com.indagium.ui.holiday.*
import com.indagium.ui.holiday.pumpkin.PumpkinPatchTheme
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HolidayIconsTest {
    @get:Rule
    val rule = createComposeRule()

    private val darkOutline = Color(0xFFf1e6d6)
    private val lightOutline = Color(0xFF2b1c12)

    private fun paths(vector: ImageVector): List<VectorPath> {
        fun walk(node: VectorNode): List<VectorPath> = when (node) {
            is VectorPath -> listOf(node)
            is VectorGroup -> node.flatMap(::walk)
            else -> emptyList()
        }
        return walk(vector.root)
    }

    private fun strokeColors(vector: ImageVector) = paths(vector).mapNotNull { (it.stroke as? SolidColor)?.value }

    private fun lookWith(funIcons: Boolean): HolidayLook {
        val settings = AppSettings().withHoliday { it.copy(mode = HolidayMode.ALWAYS, funIcons = funIcons) }
        return resolveHolidayLook(settings, LocalDate.of(2026, 10, 31))!!
    }

    private fun capture(look: HolidayLook?, id: ToolbarIconId): HolidayIcon? {
        var result: HolidayIcon? = null
        rule.setContent {
            CompositionLocalProvider(LocalHoliday provides look, LocalTheme provides (look?.colors ?: LIGHT_THEME)) {
                result = holidayToolbarIcon(id)
            }
        }
        rule.waitForIdle()
        return result
    }

    @Test
    fun everyToolbarIdBuildsA32By32IconForBothOutlineColours() {
        for (outline in listOf(darkOutline, lightOutline)) {
            val set = PumpkinPatchTheme.icons.icons(outline)
            assertEquals(ToolbarIconId.entries.toSet(), set.keys)
            set.forEach { (id, icon) ->
                assertEquals(32f, icon.vector.viewportWidth, "$id")
                assertEquals(32f, icon.vector.viewportHeight, "$id")
                assertEquals(32.dp, icon.vector.defaultWidth, "$id")
                assertTrue(paths(icon.vector).isNotEmpty(), "$id has no paths")
            }
        }
    }

    @Test
    fun tooltipsPairTheRealNameWithItsMnemonic() {
        val tips = PumpkinPatchTheme.icons.icons(lightOutline).mapValues { it.value.tooltip }
        assertEquals(
            mapOf(
                ToolbarIconId.NEW to "New tab · the tombstone",
                ToolbarIconId.FILTERS to "Filters · the pumpkin",
                ToolbarIconId.NOTES to "Notes · the spellbook",
                ToolbarIconId.AI to "AI · the crystal ball",
                ToolbarIconId.VIDEO to "Video · the bat",
                ToolbarIconId.COMPARE to "Compare · the ghosts",
                ToolbarIconId.CASES to "Cases · the eyeball",
                ToolbarIconId.OPEN to "Open file · the coffin",
                ToolbarIconId.LAYOUT to "Layout · the web",
                ToolbarIconId.SETTINGS to "Settings · the cauldron",
            ),
            tips,
        )
    }

    @Test
    fun theOutlineColourIsAppliedToTheLineWork() {
        val dark = PumpkinPatchTheme.icons.icons(darkOutline)
        val light = PumpkinPatchTheme.icons.icons(lightOutline)
        ToolbarIconId.entries.forEach { id ->
            assertTrue(darkOutline in strokeColors(dark.getValue(id).vector), "$id dark outline")
            assertTrue(lightOutline in strokeColors(light.getValue(id).vector), "$id light outline")
            assertTrue(lightOutline !in strokeColors(dark.getValue(id).vector), "$id dark set leaks the light outline")
        }
    }

    @Test
    fun theColourfulPartsKeepTheirOwnColours() {
        val pumpkin = PumpkinPatchTheme.icons.icons(lightOutline).getValue(ToolbarIconId.FILTERS).vector
        val fills = paths(pumpkin).mapNotNull { (it.fill as? SolidColor)?.value }
        assertTrue(Color(0xFFe86f12) in fills && Color(0xFFff9a3c) in fills, "pumpkin keeps its oranges")
        assertNotEquals(strokeColors(pumpkin).toSet(), setOf(lightOutline), "pumpkin has a green stem stroke too")
    }

    @Test
    fun noIconWithoutAPack() {
        assertNull(capture(null, ToolbarIconId.FILTERS))
    }

    @Test
    fun noIconWhenFunIconsIsOff() {
        assertNull(capture(lookWith(funIcons = false), ToolbarIconId.FILTERS))
    }

    @Test
    fun anIconUnderTheActivePack() {
        val icon = capture(lookWith(funIcons = true), ToolbarIconId.FILTERS)
        assertNotNull(icon)
        assertEquals("Filters · the pumpkin", icon.tooltip)
    }
}
