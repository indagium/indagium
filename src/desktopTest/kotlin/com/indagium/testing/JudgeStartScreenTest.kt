package com.indagium.testing

import com.indagium.ai.LlmRole
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.JudgeTiming
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.TestRun
import com.indagium.testing.run.COMPARISON_SYSTEM_PROMPT
import com.indagium.testing.run.JUDGE_CHANCE_CLASSIFICATION_RULE
import com.indagium.testing.run.JUDGE_MAX_ATTACHED_IMAGES
import com.indagium.testing.run.JUDGE_SYSTEM_PROMPT
import com.indagium.testing.run.JUDGE_OPTIONAL_RULE
import com.indagium.testing.run.JudgeEvidence
import com.indagium.testing.run.JudgeImage
import com.indagium.testing.run.JudgeLaneEvidence
import com.indagium.testing.run.JudgeTools
import com.indagium.testing.run.START_SCREEN_LABEL
import com.indagium.testing.run.StartRunResult
import com.indagium.testing.run.StepLogSlice
import com.indagium.testing.run.buildJudgeBrief
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val AWAIT_MS = 15_000L
private const val SIDE = 16
private val START_SCREEN = byteArrayOf(1, 1, 1)
private val END_SCREEN = byteArrayOf(2, 2, 2)
private val GOLDEN_SCREEN = byteArrayOf(3, 3, 3)

private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

private fun laneOf(label: String = "Lane", start: ByteArray? = START_SCREEN, withStart: Boolean = true) = JudgeLaneEvidence(
    label = label,
    laneId = "id-$label",
    deterministic = emptyList(),
    screenshot = { JudgeImage(END_SCREEN, "image/jpeg") },
    log = { _, _ -> StepLogSlice(emptyList(), 0L, more = false, totalBytes = 0L) },
    logBytes = 0L,
    startScreenshot = if (withStart) ({ start?.let { JudgeImage(it, "image/jpeg") } }) else null,
)

private fun evidenceOf(lane: JudgeLaneEvidence = laneOf(), examples: List<StepExample> = emptyList()) =
    JudgeEvidence("Feed", 2, 3, "Scroll the feed", "The feed has scrolled", emptyList(), examples, listOf(lane))

private fun golden(id: String) = StepExample.GoldenScreenshot(id, "assets/$id.png", "Golden $id")

/** The judge sees the screen a step started from, so an action such as a scroll is judged against what it changed. */
class JudgeStartScreenTest {
    // ── The first message ───────────────────────────────────────────

    @Test
    fun theStartScreenIsAttachedBeforeTheEndScreenAndLabelled() = runBlocking<Unit> {
        val brief = buildJudgeBrief(evidenceOf(examples = listOf(golden("g1"))), { JudgeImage(GOLDEN_SCREEN, "image/jpeg") }, attachImages = true)

        assertEquals(listOf(b64(START_SCREEN), b64(END_SCREEN), b64(GOLDEN_SCREEN)), brief.images.map { it.base64 })
        assertContains(
            brief.prompt,
            "Attached images, in this order:\n1. $START_SCREEN_LABEL (Lane)\n2. The screen when the step ended (Lane)\n3. Expected screenshot g1",
        )
        assertFalse(brief.needsTools)
    }

    @Test
    fun startAndEndScreensTakePriorityOverGoldensWithinTheImageCap() = runBlocking<Unit> {
        val examples = List(JUDGE_MAX_ATTACHED_IMAGES + 1) { golden("g$it") }

        val brief = buildJudgeBrief(evidenceOf(examples = examples), { JudgeImage(GOLDEN_SCREEN, "image/jpeg") }, attachImages = true)

        assertEquals(JUDGE_MAX_ATTACHED_IMAGES, brief.images.size)
        assertEquals(listOf(b64(START_SCREEN), b64(END_SCREEN)), brief.images.take(2).map { it.base64 })
        assertTrue(brief.images.drop(2).all { it.base64 == b64(GOLDEN_SCREEN) }, "what is left of the cap goes to the goldens")
        val firstLeft = "g${JUDGE_MAX_ATTACHED_IMAGES - 2}"
        assertContains(brief.prompt, "$firstLeft is not attached; call get_example with exampleId \"$firstLeft\"")
        assertTrue(brief.needsTools)
    }

    @Test
    fun aStepWithoutAStartScreenSaysSoAndFetchesNothing() = runBlocking<Unit> {
        val brief = buildJudgeBrief(evidenceOf(laneOf(start = null)), { null }, attachImages = true)

        assertEquals(listOf(b64(END_SCREEN)), brief.images.map { it.base64 })
        assertContains(brief.prompt, "$START_SCREEN_LABEL (Lane) is not available.")
        assertFalse(brief.needsTools)
    }

    @Test
    fun evidenceWithoutAStartScreenProviderSaysNothingAboutOne() = runBlocking<Unit> {
        // A comparison is built from the screens kept on disk, which are end screens only.
        val brief = buildJudgeBrief(evidenceOf(laneOf(withStart = false)), { null }, attachImages = true)

        assertEquals(listOf(b64(END_SCREEN)), brief.images.map { it.base64 })
        assertFalse(START_SCREEN_LABEL in brief.prompt, brief.prompt)
        assertFalse("moment" in brief.prompt, brief.prompt)
    }

    @Test
    fun aJudgeThatCannotTakeImagesIsToldHowToFetchTheStartScreen() = runBlocking<Unit> {
        val brief = buildJudgeBrief(evidenceOf(), { null }, attachImages = false)

        assertTrue(brief.images.isEmpty() && brief.needsTools)
        assertContains(brief.prompt, "- $START_SCREEN_LABEL (Lane): get_step_screenshot with lane \"Lane\" and moment \"start\".")
        assertContains(brief.prompt, "- The screen when the step ended (Lane): get_step_screenshot with lane \"Lane\".")
    }

    // ── The fallback tool ───────────────────────────────────────────

    private fun screenshot(evidence: JudgeEvidence, arguments: Map<String, Any?>): Map<*, *> = runBlocking {
        val tools = JudgeTools(evidence) { null }
        assertEquals(setOf("lane", "moment"), tools.gateway.tools.single { it.name == "get_step_screenshot" }.schema.properties?.keys)
        tools.gateway.executeSuspending("get_step_screenshot", arguments) as Map<*, *>
    }

    @Test
    fun getStepScreenshotReturnsTheStartScreenForMomentStartAndTheEndScreenByDefault() {
        val evidence = evidenceOf()

        val start = screenshot(evidence, mapOf("moment" to "start"))
        assertEquals(b64(START_SCREEN), start["imageBase64"])
        assertEquals("$START_SCREEN_LABEL (Lane)", start["message"])

        val byDefault = screenshot(evidence, emptyMap())
        assertEquals(b64(END_SCREEN), byDefault["imageBase64"])
        assertEquals("The screen when the step ended (Lane)", byDefault["message"])
        assertEquals(b64(END_SCREEN), screenshot(evidence, mapOf("moment" to "END"))["imageBase64"])
    }

    @Test
    fun getStepScreenshotSaysWhenThereIsNoStartScreenOrTheMomentIsUnknown() {
        val none = screenshot(evidenceOf(laneOf(start = null)), mapOf("moment" to "start"))
        assertContains(none["error"].toString(), "start of the step")
        assertEquals(null, none["imageBase64"])

        val noProvider = screenshot(evidenceOf(laneOf(withStart = false)), mapOf("moment" to "start"))
        assertContains(noProvider["error"].toString(), "start of the step")

        val unknown = screenshot(evidenceOf(), mapOf("moment" to "middle"))
        assertContains(unknown["error"].toString(), "moment must be")
    }

    // ── Classification wording ──────────────────────────────────────

    @Test
    fun theJudgePromptsAndTheSubmitToolsClassifyLuckDependentStepsAsStepProblems() = runBlocking<Unit> {
        listOf("ads", "picture-in-picture", "background behaviour", "network", "timing").forEach {
            assertContains(JUDGE_CHANCE_CLASSIFICATION_RULE, it)
        }
        assertContains(JUDGE_CHANCE_CLASSIFICATION_RULE, "classify it agent_or_step_problem, not app_defect")
        assertContains(JUDGE_CHANCE_CLASSIFICATION_RULE, "suggestedStepFix with a stable rewording")
        assertContains(JUDGE_SYSTEM_PROMPT, JUDGE_CHANCE_CLASSIFICATION_RULE)
        assertContains(COMPARISON_SYSTEM_PROMPT, JUDGE_CHANCE_CLASSIFICATION_RULE)
        assertContains(JUDGE_SYSTEM_PROMPT, "the screen the step started from")

        val single = JudgeTools(evidenceOf()) { null }.gateway.tools.single { it.name == "submit_verdict" }
        assertContains(single.description, JUDGE_CHANCE_CLASSIFICATION_RULE)
        val comparison = evidenceOf().let { JudgeEvidence(it.caseName, 1, 1, "a", "b", emptyList(), emptyList(), listOf(laneOf("Lane 1"), laneOf("Lane 2"))) }
        val both = JudgeTools(comparison) { null }.gateway.tools.single { it.name == "submit_comparison" }
        assertContains(both.description, JUDGE_CHANCE_CLASSIFICATION_RULE)
        // The judge stays blind: the rule is about the step's text, never about what the agent said.
        assertFalse(JUDGE_CHANCE_CLASSIFICATION_RULE.contains("agent said") || JUDGE_CHANCE_CLASSIFICATION_RULE.contains("claim"))
    }

    // ── From a real run ─────────────────────────────────────────────

    private var harness: RunHarness? = null

    @AfterTest
    fun tearDown() {
        harness?.close()
    }

    private fun solidPng(color: Color): ByteArray = ByteArrayOutputStream().also {
        val image = BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { this.color = color; fillRect(0, 0, SIDE, SIDE); dispose() }
        ImageIO.write(image, "png", it)
    }.toByteArray()

    @Test
    fun theJudgeGetsAnAttemptFreshStartScreenInsteadOfAStalePreviousEnd() {
        val suite = suiteOf(caseOf("Feed", step("Open the feed"), step("Scroll the feed")))
        val judge = ScriptedJudgeProvider { JudgeAnswer(verdict = "pass", reasoning = "It matches.") }
        // The device shows a different screen each time the agent finishes a step.
        val screens = listOf(Color.RED, Color.GREEN, Color.BLUE)
        lateinit var current: RunHarness
        val agent = AutoAgentProvider(listOf("pass" to "ok", "pass" to "ok")) { answered ->
            current.adb.png = solidPng(screens[answered])
        }
        val h = RunHarness(
            libraryOf(suite),
            profile = profileOf(LANE_A_PROFILE_ID),
            extraProfiles = listOf(judgeProfile),
            agentFactory = agentsByProfile(mapOf(LANE_A_PROFILE_ID to agent, JUDGE_PROFILE_ID to judge)),
            tuning = FAST_TUNING,
        ).also {
            harness = it
            current = it
        }
        val config: RunConfig = h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(judgeProfileId = JUDGE_PROFILE_ID, judgeMode = JudgeMode.EVERY_STEP.wire)
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        val run: TestRun = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }
        assertEquals(2, run.lanes.single().cases.single().steps.size)

        val firstMessages = judge.requests.filter { r -> r.messages.none { it.role == LlmRole.TOOL } }.map { r -> r.messages.first { it.role == LlmRole.USER } }
        assertEquals(2, firstMessages.size, "one judge per step")
        val (first, second) = firstMessages
        assertEquals(2, first.images.size, "even the first attempt has a start capture immediately before verification")
        assertContains(first.content.orEmpty(), "1. $START_SCREEN_LABEL (Lane)\n2. The screen when the step ended (Lane)")
        assertEquals(2, second.images.size)
        assertContains(second.content.orEmpty(), "1. $START_SCREEN_LABEL (Lane)\n2. The screen when the step ended (Lane)")
        assertNotEquals(first.images[1].base64, second.images[0].base64, "the second attempt captures its current screen instead of reusing the prior end")
    }

    @Test
    fun backgroundJudgeReceivesTheAuthorsOptionalConditionWithoutTheAgentsClaim() {
        val condition = "home screen is shown already"
        val suite = suiteOf(caseOf("YouTube", step("Swipe up").copy(optional = true, condition = condition, expected = "The Home screen is visible.")))
        val judge = ScriptedJudgeProvider { JudgeAnswer(verdict = "pass", reasoning = "The evidence matches.") }
        val agent = AutoAgentProvider(listOf("pass" to "private agent observation"))
        val h = RunHarness(
            libraryOf(suite), profile = profileOf(LANE_A_PROFILE_ID), extraProfiles = listOf(judgeProfile),
            agentFactory = agentsByProfile(mapOf(LANE_A_PROFILE_ID to agent, JUDGE_PROFILE_ID to judge)), tuning = FAST_TUNING,
        ).also { harness = it }
        h.adb.uiDump = overviewUiDump()
        val config = h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(
            judgeProfileId = JUDGE_PROFILE_ID, judgeMode = JudgeMode.EVERY_STEP.wire, judgeTiming = JudgeTiming.BACKGROUND,
        )
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }
        val request = judge.requests.first()
        val prompt = request.messages.first { it.role == LlmRole.USER }.content.orEmpty()
        assertContains(prompt, "\"optional\":true")
        assertContains(prompt, "\"condition\":\"$condition\"")
        assertContains(prompt, "\"intent\":\"perform_action\"")
        assertFalse("private agent observation" in prompt)
        assertContains(prompt, "\"surface\":\"recent_apps\"")
        assertContains(prompt, "overview_panel")
        assertContains(prompt, "task_view_single")
        assertContains(JUDGE_SYSTEM_PROMPT, JUDGE_OPTIONAL_RULE)
        assertContains(COMPARISON_SYSTEM_PROMPT, JUDGE_OPTIONAL_RULE)
    }
}
