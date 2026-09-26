package com.indagium.debug

import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceAiMcpImageTest {
    @Test
    fun deviceScreenMcpResultHasCoordinateContractBeforeImageAndNoBase64InText() {
        val result = toCallToolResult(
            toolName = "get_device_screen",
            rawResult = mapOf(
                "message" to "Current Android device screen from capture-1",
                "imageBase64" to "c2NyZWVu",
                "mimeType" to "image/jpeg",
                "width" to 648,
                "height" to 1440,
                "coordinateInstructions" to "Coordinates use returned image pixels and are mapped to physical device pixels.",
            ),
            textFallback = "unused",
        )

        assertEquals(2, result.content.size)
        val text = assertIs<TextContent>(result.content[0]).text
        val image = assertIs<ImageContent>(result.content[1])
        assertTrue(text.contains("648×1440"))
        assertTrue(text.contains("returned image pixels"))
        assertFalse(text.contains("c2NyZWVu"))
        assertEquals("c2NyZWVu", image.data)
        assertEquals("image/jpeg", image.mimeType)
    }
}
