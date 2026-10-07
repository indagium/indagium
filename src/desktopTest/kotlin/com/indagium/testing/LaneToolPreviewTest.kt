package com.indagium.testing

import com.indagium.testing.run.laneToolPreview
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaneToolPreviewTest {
    @Test
    fun previewIsBoundedAndRedactsCredentialsAndImagePayloads() {
        val preview = laneToolPreview(
            mapOf(
                "text" to "visible result",
                "api_key" to "never show this",
                "imageBase64" to "A".repeat(100_000),
                "nested" to mapOf("token" to "secret token"),
            ),
        )

        assertTrue(preview.length <= 2_000)
        assertTrue(preview.contains("visible result"))
        assertTrue(preview.contains("image payload omitted"))
        assertTrue(preview.contains("redacted"))
        assertFalse(preview.contains("never show this"))
        assertFalse(preview.contains("secret token"))
        assertFalse(preview.contains("AAAA"))
    }
}
