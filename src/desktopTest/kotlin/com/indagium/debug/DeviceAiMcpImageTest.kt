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

    @Test
    fun recordingContextMcpResultIsAReferenceImageWithoutDeviceCoordinateContract() {
        val result = toCallToolResult(
            toolName = "get_test_recording",
            rawResult = mapOf(
                "message" to "Saved input-time context",
                "kind" to "inputTimeContext",
                "exampleId" to "recording-row-1",
                "caption" to "Captured at input time before action: Open settings",
                "imageBase64" to "aW5wdXQtZnJhbWU=",
                "mimeType" to "image/jpeg",
            ),
            textFallback = "unused",
        )

        assertEquals(2, result.content.size)
        val text = assertIs<TextContent>(result.content[0]).text
        val image = assertIs<ImageContent>(result.content[1])
        assertTrue(text.contains("input-time context"))
        assertTrue(text.contains("not the current device screen"))
        assertTrue(text.contains("expected-result oracle"))
        assertFalse(text.contains("coordinate"))
        assertFalse(text.contains("aW5wdXQtZnJhbWU="))
        assertEquals("image/jpeg", image.mimeType)
        assertEquals("aW5wdXQtZnJhbWU=", image.data)
        assertFalse("get_test_recording" in SCREEN_IMAGE_TOOL_NAMES)
        assertTrue("get_test_recording" in IMAGE_RESULT_TOOL_NAMES)
    }
}
