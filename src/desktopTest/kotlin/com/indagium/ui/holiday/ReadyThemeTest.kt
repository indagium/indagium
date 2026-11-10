package com.indagium.ui.holiday

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.HolidaySettings
import com.indagium.ui.holiday.ready.ReadyTheme
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadyThemeTest {
    @Test fun selectionAndEffectsUseTheSeasonalFramework() {
        val settings = HolidaySettings(mode = HolidayMode.ALWAYS, pack = HolidayPack.READY_PROMPT)
        assertEquals(ReadyTheme, activeHolidayTheme(settings, LocalDateTime.of(2026, 6, 1, 12, 0)))
        assertNull(activeHolidayTheme(settings.copy(mode = HolidayMode.AUTOMATIC), LocalDateTime.of(2026, 6, 1, 12, 0)))
        assertEquals(Color(0xFF2a2165), ReadyTheme.palette(true).bg)
        assertEquals(Color(0xFFece6d6), ReadyTheme.palette(false).bg)
        assertEquals(2, resolveHolidayEffects(settings, ReadyTheme).activeEffects.size)
        assertTrue(resolveHolidayEffects(settings.copy(intensity = HolidayIntensity.OFF), ReadyTheme).activeEffects.isEmpty())
        assertEquals(1, resolveHolidayEffects(settings.copy(effectToggles = mapOf("ready.scanlines" to false)), ReadyTheme).activeEffects.size)
        assertEquals("Find", assertNotNull(ReadyTheme.icons.findIcon(Color.Black)).name)
        assertEquals(HolidayMode.ALWAYS, HolidaySettings(mode = HolidayMode.AUTOMATIC).withPackOn(HolidayPack.READY_PROMPT).mode,
            "selecting an anytime pack turns it on even from Automatic")
    }

    @Test fun artworkRendersFromBundledResources() {
        for (painter in listOf(ReadyTheme.picture, ReadyTheme.companion)) {
            val (w, h) = when (painter) {
                is HolidayPicturePainter -> painter.designWidthDp.toInt() to painter.designHeightDp.toInt()
                is HolidayCompanionPainter -> painter.designWidthDp.toInt() to painter.designHeightDp.toInt()
                else -> error("Unknown painter")
            }
            val bitmap = ImageBitmap(w, h)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
                when (painter) {
                    is HolidayPicturePainter -> painter.draw(this, 0f, true)
                    is HolidayCompanionPainter -> painter.draw(this, 0f, true)
                }
            }
            val pixels = bitmap.toPixelMap()
            assertTrue((0 until h step 4).any { y -> (0 until w step 4).any { x -> pixels[x, y].alpha > 0f } })
        }
    }
}
