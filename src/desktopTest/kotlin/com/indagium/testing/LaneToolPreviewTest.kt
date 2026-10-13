package com.indagium.testing

import com.indagium.testing.run.laneToolPreview
import com.indagium.testing.run.sanitizeHistoricalPreview
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

    @Test
    fun stdoutStringsAreRedactedBeforePreviewTruncationAndEncoding() {
        val credential = "private-output-token"
        val preview = laneToolPreview(mapOf("stdout" to "prefix token=$credential ${"x".repeat(2_000)}"))

        assertTrue(preview.length <= 2_000)
        assertTrue(preview.contains("token=[REDACTED]"), preview.take(100))
        assertFalse(preview.contains(credential))
    }

    @Test
    fun bearerAndJsonCredentialStringsAreRedacted() {
        val preview = laneToolPreview(
            mapOf(
                "stdout" to "Bearer live-bearer-value",
                "nested" to "{\"password\":\"embedded-password\"}",
                "authorization" to "live-authorization-value",
            ),
        )

        assertTrue(preview.contains("Bearer [REDACTED]"), preview)
        assertTrue(preview.contains("embedded-password").not(), preview)
        assertTrue(preview.contains("live-authorization-value").not(), preview)
        assertTrue(preview.contains("[REDACTED]"), preview)
    }

    @Test
    fun truncatedHistoricalJsonPreviewStillRedactsAssignments() {
        val credential = "truncated-historical-secret"
        val preview = sanitizeHistoricalPreview("{\"stdout\":\"Bearer $credential\"")

        assertFalse(preview.contains(credential))
        assertTrue(preview.contains("Bearer [REDACTED]"), preview)
    }

    @Test
    fun truncatedHistoricalEscapedNestedJsonRedactsCredentialAndAuthorizationFields() {
        val password = "nested-truncated-password-value"
        val passwordPreview = sanitizeHistoricalPreview(
            """{"stdout":"prefix {\"nested\":{\"password\":\"$password""",
        )

        assertFalse(passwordPreview.contains(password), passwordPreview)
        assertTrue(passwordPreview.contains("[REDACTED]"), passwordPreview)

        val authorization = "nested-authorization-value"
        val authorizationPreview = sanitizeHistoricalPreview(
            """{"stdout":"{\"authorization\":\"$authorization""",
        )

        assertFalse(authorizationPreview.contains(authorization), authorizationPreview)
        assertTrue(authorizationPreview.contains("[REDACTED]"), authorizationPreview)
    }
}
