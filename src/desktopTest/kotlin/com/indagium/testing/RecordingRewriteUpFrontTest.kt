@file:Suppress("MagicNumber") // Fixture counts, not tunable constants.

package com.indagium.testing

import com.indagium.ai.LlmRole
import com.indagium.ai.LlmStreamEvent
import com.indagium.debug.AppLogger
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.testing.authoring.RecordingRewrite
import com.indagium.testing.authoring.RecordingRewriteService
import com.indagium.testing.authoring.RewriteGeneration
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.run.LaneAgent
import com.indagium.testing.run.LaneAgentFactory
import com.indagium.testing.run.ProviderLaneAgent
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import com.indagium.ui.TestRunOverrides
import com.indagium.ui.generateRecordingRewrite
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PROFILE_ID = "rewrite-up-front-profile"

/** What the rewrite service hands the agent: the evidence up front when the agent takes images, the tools when it does not. */
class RecordingRewriteUpFrontTest {
    private val sessions = mutableListOf<TestStepRecordingSession>()
    private lateinit var dir: File
    private lateinit var logFile: File
    private var state: AppState? = null

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("rewrite-up-front").toFile()
        logFile = File(dir, "app.log")
    }

    @AfterTest
    fun tearDown() {
        AppLogger.configure(enabled = false, path = null)
        sessions.forEach(TestStepRecordingSession::close)
        state?.close()
        dir.deleteRecursively()
    }

    private fun session() = recordedShopSession().also { sessions += it }

    private fun service(
        acceptsImages: (String) -> Boolean = { true },
        generate: suspend (RewriteGeneration) -> String,
    ) = RecordingRewriteService(
        library = { shopLibrary() },
        preflight = { _, _ -> StoreResult.Ok(Unit) },
        generate = generate,
        acceptsImagesUpFront = acceptsImages,
    )

    private suspend fun RecordingRewriteService.run(session: TestStepRecordingSession) =
        rewrite(session, SHOP_SUITE_ID, SHOP_CASE_ID, PROFILE_ID, null, null, "")

    // ── The request the service builds ───────────────────────────────

    @Test
    fun anAgentThatTakesImagesGetsTheScreensAndASmallCloseUpBudget() = runBlocking {
        AppLogger.configure(enabled = true, path = logFile.absolutePath)
        val seen = mutableListOf<RewriteGeneration>()

        val result = service(generate = { seen += it; VALID_SHOP_REWRITE }).run(session())

        assertIs<StoreResult.Ok<RecordingRewrite>>(result)
        val request = seen.single()
        val attachment = assertNotNull(request.attachment)
        assertEquals(2, attachment.images.size, "input 1 before and input 3 after are the screens that were kept")
        assertEquals(listOf("image/jpeg", "image/jpeg"), attachment.images.map { it.mimeType })
        assertEquals(6, request.toolCallLimit, "close-ups only")
        assertEquals(9, attachment.fallbackToolCallLimit, "three looks per input without the evidence")
        assertTrue("Evidence is attached to this message as 2 image(s)" in request.prompt, request.prompt)
        assertTrue("Image 1: input 1 before" in request.prompt && "Image 2: input 3 after" in request.prompt, request.prompt)
        assertTrue("UI near the touch (before): \"Search\"" in request.prompt, request.prompt)
        assertTrue("The screenshots are NOT attached" in attachment.fallbackPrompt && "NOT attached" !in request.prompt)
        val tools = listOf("get_recorded_input", "get_recorded_screen", "get_recorded_ui")
        assertEquals(tools, request.gateway.tools.map { it.name }, "the tools stay for close-ups")
        val line = logFile.readLines().single { "rewrite_brief" in it }
        assertTrue("evidenceUpFront=true" in line && "images=2" in line && "toolBudget=6" in line && "promptChars=" in line && "briefMs=" in line, line)
        assertTrue("source=screenshots" in line && "settledAfter=1/3" in line && "lockedSteps=0" in line, line)
    }

    @Test
    fun anAgentThatTakesNoImagesKeepsTheToolsAndTheFullBudget() = runBlocking {
        val seen = mutableListOf<RewriteGeneration>()

        service(acceptsImages = { false }, generate = { seen += it; VALID_SHOP_REWRITE }).run(session())

        val request = seen.single()
        assertNull(request.attachment)
        assertEquals(9, request.toolCallLimit)
        assertEquals(15, request.maxTurns)
        assertTrue("The screenshots are NOT attached" in request.prompt && "get_recorded_screen" in request.prompt, request.prompt)
        assertFalse("Evidence is attached" in request.prompt)
    }

    @Test
    fun aRecordingWithoutAnyKeptScreenIsRewrittenFromTheToolsAlone() = runBlocking {
        val seen = mutableListOf<RewriteGeneration>()

        val result = service(generate = { seen += it; VALID_SHOP_REWRITE }).run(recordedShopSession(images = false).also { sessions += it })

        assertIs<StoreResult.Ok<RecordingRewrite>>(result)
        val request = seen.single()
        assertNull(request.attachment, "there is nothing to attach")
        assertEquals(9, request.toolCallLimit, "the tools are the only evidence, so the budget stays")
        assertTrue("The screenshots are NOT attached" in request.prompt, request.prompt)
    }

    // ── Through a scripted provider ──────────────────────────────────

    private fun appWith(factory: LaneAgentFactory): AppState {
        val profile = AiProviderProfile(PROFILE_ID, "Up front model", "http://127.0.0.1:1234/v1", "base-model", kind = AiProviderKind.OPENAI_COMPATIBLE)
        val app = AppState(autosaveFile = File(dir, "state.cache"), autoExportNotes = false, notesDir = File(dir, "notes"), testingDir = File(dir, "testing"))
        app.updateSettings { it.copy(aiProviderProfiles = listOf(profile)) }
        app.testRunOverrides = TestRunOverrides(agentFactory = factory)
        state = app
        return app
    }

    private fun answerProvider() = TurnProvider(
        List(3) {
            {
                emit(LlmStreamEvent.TextDelta(VALID_SHOP_REWRITE))
                emit(LlmStreamEvent.Completed)
            }
        },
    )

    @Test
    fun aScriptedProviderRewritesInOneModelTurnWithTheImagesOnTheFirstMessage() = runBlocking {
        val provider = answerProvider()
        val app = appWith(LaneAgentFactory { chosen, _ -> ProviderLaneAgent(provider, chosen) })

        val result = service(generate = { app.generateRecordingRewrite(it) }).run(session())

        assertIs<StoreResult.Ok<RecordingRewrite>>(result)
        val request = provider.requests.single()
        val first = request.messages.first { it.role == LlmRole.USER }
        assertEquals(2, first.images.size)
        assertTrue("Evidence is attached" in first.content.orEmpty())
        assertTrue(request.messages.filter { it.role == LlmRole.SYSTEM }.any { "6-call budget" in it.content.orEmpty() })
    }

    @Test
    fun anAgentThatReportsNoImageSupportRunsTheFallbackWithTheFullBudget() = runBlocking {
        val provider = answerProvider()
        val app = appWith(
            LaneAgentFactory { chosen, _ ->
                val inner = ProviderLaneAgent(provider, chosen)
                object : LaneAgent by inner {
                    override val acceptsImagesUpFront: Boolean get() = false
                }
            },
        )
        // The service believes the profile takes images (its own check says so); the agent itself says it cannot.
        val result = service(generate = { app.generateRecordingRewrite(it) }).run(session())

        assertIs<StoreResult.Ok<RecordingRewrite>>(result)
        val request = provider.requests.single()
        val first = request.messages.first { it.role == LlmRole.USER }
        assertTrue(first.images.isEmpty())
        assertTrue("The screenshots are NOT attached" in first.content.orEmpty(), first.content)
        assertTrue(request.messages.filter { it.role == LlmRole.SYSTEM }.any { "9-call budget" in it.content.orEmpty() })
    }
}
