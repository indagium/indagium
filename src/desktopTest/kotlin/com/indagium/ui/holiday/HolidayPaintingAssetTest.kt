package com.indagium.ui.holiday

import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import org.jetbrains.skia.Image
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HolidayPaintingAssetTest {
    @Test fun psdExportsRetainAuthoredTransparencyAndTheWholeCanvasAtDisplaySize() {
        for (theme in listOf("bear", "tulips")) {
            val bytes = requireNotNull(javaClass.getResourceAsStream("/holiday/$theme/painting.png")).use { it.readBytes() }
            val bitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()
            // A smaller export of the full 1668 x 2388 canvas: big enough for the largest placement at 2x, a third of the memory.
            assertEquals(1000, bitmap.width)
            assertEquals(1432, bitmap.height)
            val pixels = bitmap.toPixelMap()
            assertEquals(0f, pixels[0, 0].alpha, "transparent canvas must not become a white rectangle")
            var feathered = 0
            var solid = 0
            for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
                val alpha = pixels[x, y].alpha
                if (alpha > 0f && alpha < 1f) feathered++
                if (alpha == 1f) solid++
            }
            assertTrue(feathered > 1000, "$theme retains the painter's translucent brushwork")
            assertTrue(solid > 1000, "$theme retains opaque painted details")
        }
    }
}
