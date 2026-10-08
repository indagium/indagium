@file:Suppress("MagicNumber") // Fixture sizes, not tunable constants.

package com.indagium.testing

import com.indagium.debug.IMAGE_RESULT_TOOL_NAMES
import com.indagium.debug.IndagiumToolActionPolicy
import com.indagium.debug.toCallToolResult
import com.indagium.testing.authoring.RecordingRewriteTools
import com.indagium.testing.authoring.RewriteInput
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.rewriteInputsOf
import com.indagium.testing.authoring.rewriteToolCallLimit
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecordingRewriteToolsTest {
    private val session: TestStepRecordingSession = recordedShopSession()
    private val inputs: List<RewriteInput> = rewriteInputsOf(session.snapshot.value.steps, session::screenState)
    private val gateway = RecordingRewriteTools(inputs).gateway

    @AfterTest
    fun tearDown() = session.close()

    @Suppress("UNCHECKED_CAST")
    private fun call(tool: String, vararg args: Pair<String, Any?>): Map<String, Any?> = gateway.execute(tool, mapOf(*args)) as Map<String, Any?>

    @Test
    fun theScreenToolReturnsTheStoredImageForBeforeAndAfter() {
        val before = call("get_recorded_screen", "index" to 1, "moment" to "before")
        assertEquals("image/jpeg", before["mimeType"])
        assertTrue(SHOP_BEFORE_IMAGE.contentEquals(Base64.getDecoder().decode(before["imageBase64"] as String)))
        val after = call("get_recorded_screen", "index" to 3, "moment" to "AFTER")
        assertTrue(SHOP_AFTER_IMAGE.contentEquals(Base64.getDecoder().decode(after["imageBase64"] as String)))
    }

    @Test
    fun aMissingImageABadMomentAndABadIndexAreErrorsNotCrashes() {
        assertTrue("No screenshot" in call("get_recorded_screen", "index" to 2, "moment" to "before")["error"].toString())
        assertTrue("No screenshot" in call("get_recorded_screen", "index" to 1, "moment" to "after")["error"].toString())
        assertTrue("before or after" in call("get_recorded_screen", "index" to 1, "moment" to "during")["error"].toString())
        assertTrue("before or after" in call("get_recorded_screen", "index" to 1)["error"].toString())
        assertTrue("no recorded input 4" in call("get_recorded_screen", "index" to 4, "moment" to "before")["error"].toString())
        assertTrue("no recorded input 0" in call("get_recorded_input", "index" to 0)["error"].toString())
        assertTrue("index is required" in call("get_recorded_input")["error"].toString())
    }

    @Test
    fun theImageToolReachesClaudeCodeAndCodexAsRealImageContent() {
        assertTrue("get_recorded_screen" in IMAGE_RESULT_TOOL_NAMES)
        val result = toCallToolResult("get_recorded_screen", call("get_recorded_screen", "index" to 1, "moment" to "before"), "unused")
        val image = assertIs<ImageContent>(result.content.last())
        assertEquals("image/jpeg", image.mimeType)
        assertFalse("get_recorded_input" in IMAGE_RESULT_TOOL_NAMES)
    }

    @Test
    fun theInputToolDescribesTheGestureAndFencesTheDeviceText() {
        val tap = call("get_recorded_input", "index" to 1)
        assertEquals("TAP", tap["kind"])
        assertEquals(true, tap["hasBeforeScreen"])
        assertEquals(false, tap["hasAfterScreen"])
        assertEquals(listOf(mapOf("x" to 54L, "y" to 31L)), tap["gesturePercent"])
        @Suppress("UNCHECKED_CAST")
        val data = tap["untrusted_data"] as Map<String, Any?>
        assertEquals("recorded_input", data["source"])
        assertEquals("Tap at (54%, 31%)", data["action"])
        assertEquals("Search", (data["tappedElement"] as Map<*, *>)["text"])
        assertTrue("untrusted_data_notice" in tap)
        assertEquals("Press Enter", ((call("get_recorded_input", "index" to 3)["untrusted_data"]) as Map<*, *>)["action"])
    }

    @Test
    fun theUiToolListsLabelledElementsInAnEnvelopeOrSaysTheyAreMissing() {
        val ui = call("get_recorded_ui", "index" to 1, "moment" to "before")
        assertEquals(2, ui["count"])
        @Suppress("UNCHECKED_CAST")
        val data = ui["untrusted_data"] as Map<String, Any?>
        assertEquals("recorded_ui", data["source"])
        assertEquals(".HomeActivity", data["activity"])
        val elements = data["elements"] as List<*>
        val search = elements.map { it as Map<*, *> }.single { it["text"] == "Search" }
        assertEquals("search_button", search["resourceId"])
        assertEquals("Button", search["className"])
        assertEquals(mapOf("x" to 60L, "y" to 31L), search["centerPercent"])
        assertTrue("No screen elements" in call("get_recorded_ui", "index" to 2, "moment" to "after")["error"].toString())
    }

    @Test
    fun theGatewayIsReadOnlyAndKnowsNothingElse() {
        assertEquals(listOf("get_recorded_input", "get_recorded_screen", "get_recorded_ui"), gateway.tools.map { it.name })
        gateway.tools.forEach { assertEquals(IndagiumToolActionPolicy.AUTOMATIC, gateway.actionPolicy(it.name)) }
        assertTrue("unknown operation" in call("apply_test_recording", "sessionId" to session.id)["error"].toString())
        assertTrue("unknown operation" in call("update_test_recording", "steps" to emptyList<Any>())["error"].toString())
        val before = session.snapshot.value
        gateway.tools.forEach { tool -> call(tool.name, "index" to 1, "moment" to "before") }
        assertEquals(before, session.snapshot.value, "reading the recording never changes it")
    }

    @Test
    fun theToolBudgetIsThreeLooksPerInputUpToSixty() {
        assertEquals(3, rewriteToolCallLimit(1))
        assertEquals(30, rewriteToolCallLimit(10))
        assertEquals(60, rewriteToolCallLimit(20))
        assertEquals(60, rewriteToolCallLimit(100))
    }
}
