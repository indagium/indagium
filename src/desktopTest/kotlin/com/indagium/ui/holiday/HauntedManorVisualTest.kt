package com.indagium.ui.holiday

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.indagium.model.TabStripPosition
import com.indagium.ui.holiday.manor.HauntedManorTheme
import com.indagium.ui.holiday.manor.ManorCompanion
import com.indagium.ui.holiday.manor.ManorPicture
import com.indagium.ui.holiday.pumpkin.PumpkinIcons
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The illustration is transparent at its edges and uses the reference Halloween kit. */
class HauntedManorVisualTest {
    @get:Rule val rule = createComposeRule()

    @Test fun illustrationEdgesAndHalloweenIconKit() {
        assertSame(PumpkinIcons, HauntedManorTheme.icons)
        rule.setContent {
            Row(Modifier.testTag("manor-art")) {
                for (dark in listOf(true, false)) {
                    val bg = if (dark) Color(0xFF0d1311) else Color(0xFFedf0ea)
                    Canvas(Modifier.size(255.dp, 357.dp).background(bg)) {
                        scale(size.width / 300f, pivot = androidx.compose.ui.geometry.Offset.Zero) { ManorPicture.draw(this, 0f, dark) }
                    }
                    Canvas(Modifier.size(204.dp, 204.dp).background(bg)) {
                        scale(size.width / 240f, pivot = androidx.compose.ui.geometry.Offset.Zero) { ManorCompanion.draw(this, 0f, dark) }
                    }
                }
            }
        }
        val image = rule.onNodeWithTag("manor-art").captureToImage()
        val pixels = image.toPixelMap()
        if (java.lang.Boolean.getBoolean("indagium.captureManor")) {
            val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) for (x in 0 until image.width) rendered.setRGB(x, y, pixels[x, y].toArgb())
            ImageIO.write(rendered, "png", File(System.getProperty("java.io.tmpdir"), "haunted-manor-art.png"))
        }
        // Bottom-left of the picture is background: no opaque rectangular ground or fog.
        val density = rule.density.density
        assertEquals(Color(0xFF0d1311).toArgb(), pixels[(2f * density).toInt(), (331f * density).toInt()].toArgb())
        assertEquals(Color(0xFFedf0ea).toArgb(), pixels[(461f * density).toInt(), (331f * density).toInt()].toArgb())
    }

    @Test fun manorInactiveTabUsesStoneSpecklesInsteadOfPumpkinRibs() {
        val theme = HauntedManorTheme
        val chrome = theme.chrome(true)
        rule.setContent {
            Box(
                Modifier.size(200.dp, 30.dp).testTag("manor-tab")
                    .holidayTabChrome(false, false, theme.palette(true), chrome, TabStripPosition.TOP,
                        shape = theme.tabShape!!.shape(TabStripPosition.TOP), texture = theme.tabTexture),
            )
        }
        val pixels = rule.onNodeWithTag("manor-tab").captureToImage().toPixelMap()
        val density = rule.density.density

        fun pixel(x: Float, y: Float) = pixels[(x * density).toInt(), (y * density).toInt()].toArgb()
        assertEquals(chrome.tabBg.toArgb(), pixel(18f, 15f), "no pumpkin rib at 18 dp")
        kotlin.test.assertNotEquals(chrome.tabBg.toArgb(), pixel(44f, 21.6f), "the first stone speckle is visible")
    }
}
