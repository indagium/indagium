@file:Suppress("MagicNumber") // Fixture sizes, not tunable constants.

package com.indagium.testing

import com.indagium.debug.IMAGE_RESULT_TOOL_NAMES
import com.indagium.debug.IndagiumToolActionPolicy
import com.indagium.debug.toCallToolResult
import com.indagium.testing.authoring.RecordedInputKind
import com.indagium.testing.authoring.RecordedTestStep
import com.indagium.testing.authoring.RecordingRewriteTools
import com.indagium.testing.authoring.RecordingScreenState
import com.indagium.testing.authoring.RecordingScreenshotEvidence
import com.indagium.testing.authoring.RecordingScreenshotSource
import com.indagium.testing.authoring.RewriteInput
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.describe
import com.indagium.testing.authoring.rewriteInputsOf
import com.indagium.testing.authoring.rewritePrompt
import com.indagium.testing.authoring.rewriteToolCallLimit
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
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
        assertTrue("No eligible screenshot" in call("get_recorded_screen", "index" to 2, "moment" to "before")["error"].toString())
        assertTrue("No eligible screenshot" in call("get_recorded_screen", "index" to 1, "moment" to "after")["error"].toString())
        assertTrue("before, after, or input" in call("get_recorded_screen", "index" to 1, "moment" to "during")["error"].toString())
        assertTrue("before, after, or input" in call("get_recorded_screen", "index" to 1)["error"].toString())
        assertTrue("no recorded input 4" in call("get_recorded_screen", "index" to 4, "moment" to "before")["error"].toString())
        assertTrue("no recorded input 0" in call("get_recorded_input", "index" to 0)["error"].toString())
        assertTrue("index is required" in call("get_recorded_input")["error"].toString())
    }

    @Test
    fun theImageToolReachesClaudeCodeAndCodexAsRealImageContent() {
        assertTrue("get_recorded_screen" in IMAGE_RESULT_TOOL_NAMES)
        val result = toCallToolResult("get_recorded_screen", call("get_recorded_screen", "index" to 1, "moment" to "before"), "unused")
        val image = assertIs<ImageContent>(result.content.last())
        val metadata = assertIs<TextContent>(result.content.first()).text
        assertEquals("image/jpeg", image.mimeType)
        assertTrue("\"verifiedFor\":\"before\"" in metadata)
        assertFalse("imageBase64" in metadata)
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
        assertEquals(SHOP_PACKAGE, data["beforeApp"])
        assertEquals("unavailable", data["afterApp"])
        assertEquals(true, tap["hasBeforeUi"])
        assertEquals(false, tap["hasAfterUi"])
        assertTrue("untrusted_data_notice" in tap)
        assertEquals("Press Enter", ((call("get_recorded_input", "index" to 3)["untrusted_data"]) as Map<*, *>)["action"])
    }

    @Test
    fun launcherAndYoutubeFactsStayOnTheirOwnSidesOfTheInput() {
        val launcher = RecordingScreenState(1, 0, 50, "com.android.launcher3", ".Launcher", emptyList(), 0, 0)
        val youtube = RecordingScreenState(2, 200, 250, "com.google.android.youtube", ".MainActivity", emptyList(), 0, 0)
        val before = RecordingScreenshotEvidence(byteArrayOf(1, 2), RecordingScreenshotSource.SCREEN_PROBE, 10, 20, false)
        val after = RecordingScreenshotEvidence(byteArrayOf(3, 4), RecordingScreenshotSource.SCREEN_PROBE, 210, 220, false)
        val row = RecordedTestStep(
            action = "Tap at (50%, 50%)",
            kind = RecordedInputKind.TAP,
            before = launcher.ref(),
            after = youtube.ref(),
            beforeScreenshot = before,
            afterScreenshot = after,
        )
        val input = RewriteInput(1, row, launcher, youtube)
        val tools = RecordingRewriteTools(listOf(input)).gateway

        @Suppress("UNCHECKED_CAST")
        val result = tools.execute("get_recorded_input", mapOf("index" to 1)) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val facts = result["untrusted_data"] as Map<String, Any?>

        assertEquals("com.android.launcher3", facts["beforeApp"])
        assertEquals(".Launcher", facts["beforeActivity"])
        assertEquals("com.google.android.youtube", facts["afterApp"])
        assertEquals(".MainActivity", facts["afterActivity"])
        assertTrue("before app: com.android.launcher3" in input.describe(com.indagium.testing.authoring.RewriteDescription.BRIEF))
        assertTrue("after app: com.google.android.youtube" in input.describe(com.indagium.testing.authoring.RewriteDescription.BRIEF))
        val screenshot = tools.execute("get_recorded_screen", mapOf("index" to 1, "moment" to "before")) as Map<*, *>
        assertEquals("before", screenshot["verifiedFor"])
        assertEquals("screen_probe", screenshot["source"])
    }

    @Test
    fun aSharedCaptureKeepsOneEvidenceIdAcrossAdjacentInputs() {
        val shared = RecordingScreenshotEvidence(byteArrayOf(5, 6), RecordingScreenshotSource.SCREEN_PROBE, 100, 110, false)
        val first = RewriteInput(
            1,
            RecordedTestStep(action = "First action", afterScreenshot = shared, kind = RecordedInputKind.TAP),
            null,
            null,
        )
        val second = RewriteInput(
            2,
            RecordedTestStep(action = "Second action", beforeScreenshot = shared, kind = RecordedInputKind.TAP),
            null,
            null,
        )
        val tools = RecordingRewriteTools(listOf(first, second)).gateway

        @Suppress("UNCHECKED_CAST")
        val firstInput = tools.execute("get_recorded_input", mapOf("index" to 1)) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val secondInput = tools.execute("get_recorded_input", mapOf("index" to 2)) as Map<String, Any?>
        val after = tools.execute("get_recorded_screen", mapOf("index" to 1, "moment" to "after")) as Map<*, *>
        val before = tools.execute("get_recorded_screen", mapOf("index" to 2, "moment" to "before")) as Map<*, *>

        assertEquals(firstInput["afterScreenshotId"], secondInput["beforeScreenshotId"])
        assertEquals(after["evidenceId"], before["evidenceId"])
        assertEquals("capture-1", after["evidenceId"])
        val suite = shopLibrary().suites.single()
        val prompt = rewritePrompt(suite, suite.cases.single(), "", listOf(first, second))
        assertEquals(2, Regex("capture-1").findAll(prompt).count())
        val metadata = assertIs<TextContent>(
            toCallToolResult("get_recorded_screen", after, "unused").content.first(),
        ).text
        val evidenceId = after["evidenceId"] as String
        val expectedId = "\"evidenceId\":\"$evidenceId\""
        assertTrue(expectedId in metadata)
    }

    @Test
    fun rawMirrorPreviewIsAvailableOnlyAsInputWithUncertainProvenance() {
        val row = RecordedTestStep(
            action = "Tap at (50%, 50%)",
            screenshotJpeg = byteArrayOf(9, 8, 7),
            screenshotSource = RecordingScreenshotSource.MIRROR_INPUT,
            screenshotTimingUncertain = true,
            kind = RecordedInputKind.TAP,
        )
        val tools = RecordingRewriteTools(listOf(RewriteInput(1, row, null, null))).gateway

        @Suppress("UNCHECKED_CAST")
        val result = tools.execute("get_recorded_screen", mapOf("index" to 1, "moment" to "input")) as Map<String, Any?>

        assertEquals("mirror_input", result["source"])
        assertEquals(true, result["timingUncertain"])
        assertEquals(null, result["verifiedFor"])
        assertTrue("Uncertain raw input preview" in result["message"].toString())
        assertTrue("No eligible screenshot" in tools.execute("get_recorded_screen", mapOf("index" to 1, "moment" to "before")).toString())
        val mcp = toCallToolResult("get_recorded_screen", result, "unused")
        val metadata = assertIs<TextContent>(mcp.content.first()).text
        val image = assertIs<ImageContent>(mcp.content.last())
        assertTrue("\"source\":\"mirror_input\"" in metadata)
        assertTrue("\"timingUncertain\":true" in metadata)
        assertFalse("imageBase64" in metadata)
        assertTrue(byteArrayOf(9, 8, 7).contentEquals(Base64.getDecoder().decode(image.data)))
        @Suppress("UNCHECKED_CAST")
        val availability = tools.execute("get_recorded_input", mapOf("index" to 1)) as Map<String, Any?>
        assertEquals(false, availability["hasBeforeScreen"])
        assertEquals(true, availability["hasInputPreview"])
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
        assertEquals(4, rewriteToolCallLimit(1, videoAvailable = true))
        assertEquals(40, rewriteToolCallLimit(10, videoAvailable = true))
        assertEquals(60, rewriteToolCallLimit(20, videoAvailable = true))
    }
}
