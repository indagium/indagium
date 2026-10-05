package com.indagium.testing.tracker

import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.testing.model.TrackerSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerConfigTest {
    private val profile =
        AiProviderProfile(id = "p1", displayName = "P", baseUrl = "http://127.0.0.1:1234", model = "m", kind = AiProviderKind.OPENAI_COMPATIBLE)
    private val ready = TrackerSettings(enabled = true, mcpUrl = "https://tracker.example.com/mcp", agentProfileId = "p1")

    @Test
    fun anHttpsUrlIsValidWithoutAWarning() {
        val check = checkTrackerUrl(" https://tracker.example.com/mcp ")

        assertTrue(check.valid)
        assertNull(check.warning)
    }

    @Test
    fun plainHttpToAnotherHostWarnsButLoopbackDoesNot() {
        assertNotNull(checkTrackerUrl("http://tracker.example.com/mcp").warning)
        assertTrue(checkTrackerUrl("http://tracker.example.com/mcp").valid)
        for (local in listOf("http://127.0.0.1:8080/mcp", "http://localhost/mcp", "http://[::1]:9/mcp")) assertNull(checkTrackerUrl(local).warning, local)
    }

    @Test
    fun anInvalidUrlIsRefusedWithAReason() {
        assertEquals("Enter the tracker's MCP URL.", checkTrackerUrl("").problem)
        assertTrue(checkTrackerUrl("ftp://x/mcp").problem!!.contains("http"))
        assertTrue(checkTrackerUrl("file:///etc/passwd").problem!!.contains("http"))
        assertTrue(checkTrackerUrl("https:///mcp").problem!!.contains("host"))
        assertTrue(checkTrackerUrl("https://user:pw@tracker.example.com/mcp").problem!!.contains("token field"))
        assertTrue(checkTrackerUrl("not a url").problem != null)
    }

    @Test
    fun theAuthHeaderNameIsValidated() {
        assertNull(checkAuthHeaderName("Authorization"))
        assertNull(checkAuthHeaderName("X-Api-Key"))
        assertNull(checkAuthHeaderName("   "), "blank means no authentication")
        assertNotNull(checkAuthHeaderName("Bad Header"))
        assertNotNull(checkAuthHeaderName("A:B"))
        for (reserved in listOf("Host", "content-length", "Content-Type", "Mcp-Session-Id", "Accept")) assertNotNull(checkAuthHeaderName(reserved), reserved)
    }

    @Test
    fun theHeaderValueIsBearerPrefixedOrRaw() {
        assertEquals("Bearer abc", trackerAuthHeaderValue(TrackerSettings(bearerPrefix = true), "abc"))
        assertEquals("abc", trackerAuthHeaderValue(TrackerSettings(bearerPrefix = false), "abc"))
        val connection = trackerConnection(TrackerSettings(mcpUrl = " https://x/mcp ", authHeaderName = " Authorization "), "abc")
        assertEquals("https://x/mcp", connection.url)
        assertEquals("Authorization", connection.headerName)
        assertEquals("Bearer abc", connection.headerValue())
        assertTrue(connection.hasAuth)
        assertFalse(connection.toString().contains("abc"))
        assertFalse(trackerConnection(TrackerSettings(authHeaderName = ""), "").hasAuth)
    }

    @Test
    fun theProblemListsWhatIsStillMissingInOrder() {
        val profiles = listOf(profile)

        assertEquals(TRACKER_NOT_CONFIGURED_HINT, trackerProblem(TrackerSettings(), profiles, tokenPresent = true))
        assertEquals("Enter the tracker's MCP URL.", trackerProblem(TrackerSettings(enabled = true), profiles, true))
        assertTrue(trackerProblem(ready.copy(authHeaderName = "Host"), profiles, true)!!.contains("set by the connection"))
        assertTrue(trackerProblem(ready.copy(agentProfileId = "gone"), profiles, true)!!.contains("AI profile"))
        assertTrue(trackerProblem(ready, profiles, tokenPresent = false)!!.contains("access token"))
        assertTrue(trackerProblem(ready, profiles, tokenPresent = null)!!.contains("Checking"))
        assertNull(trackerProblem(ready, profiles, tokenPresent = true))
        assertNull(trackerProblem(ready.copy(authHeaderName = ""), profiles, tokenPresent = false), "no authentication, no token needed")
    }
}
