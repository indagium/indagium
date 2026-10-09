package com.indagium.debug

import com.indagium.ai.LlmStreamEvent
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.testing.TurnProvider
import com.indagium.testing.authoring.RewriteGeneration
import com.indagium.testing.run.LaneAgentFactory
import com.indagium.testing.run.ProviderLaneAgent
import com.indagium.ui.AppState
import com.indagium.ui.TestRunOverrides
import com.indagium.ui.generateRecordingRewrite
import com.indagium.ui.generateTestStepDraft
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PROFILE_ID = "timing-log-profile"

/** A step draft and a recording rewrite write the judge's timing breakdown, in the same words, to the app log. */
class AgentTimingLogTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private lateinit var logFile: File

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("agent-timing-log").toFile()
        logFile = File(dir, "app.log")
        state = AppState(autosaveFile = File(dir, "state.cache"), autoExportNotes = false, notesDir = File(dir, "notes"), testingDir = File(dir, "testing"))
        val profile = AiProviderProfile(PROFILE_ID, "Timing model", "http://127.0.0.1:1234/v1", "base-model", kind = AiProviderKind.OPENAI_COMPATIBLE)
        state.updateSettings { it.copy(aiProviderProfiles = listOf(profile)) }
        AppLogger.configure(enabled = true, path = logFile.absolutePath) // after the state: opening it sets the logger from its own settings
        val provider = TurnProvider(
            List(2) {
                {
                    emit(LlmStreamEvent.TextDelta("""{"steps":[]}"""))
                    emit(LlmStreamEvent.Completed)
                }
            },
        )
        state.testRunOverrides = TestRunOverrides(agentFactory = LaneAgentFactory { chosen, _ -> ProviderLaneAgent(provider, chosen) })
    }

    @AfterTest
    fun tearDown() {
        AppLogger.configure(enabled = false, path = null)
        state.close()
        dir.deleteRecursively()
    }

    private fun timingLines(prefix: String): List<String> = logFile.readLines().filter { "test-agent-timing" in it && prefix in it }

    @Test
    fun aStepDraftLogsWhereItsTimeWent() {
        runBlocking { state.generateTestStepDraft(PROFILE_ID, "Draft a login test") }

        val line = timingLines("test-step-draft").single()
        assertTrue("startup" in line && "first response" in line && "1 model turns" in line && "0 tool calls" in line && "total" in line, line)
        assertTrue("Timing model" in line && "base-model" in line, "the line says which profile and model ran: $line")
    }

    @Test
    fun aRecordingRewriteLogsTheSameBreakdown() {
        val request = RewriteGeneration(
            profileId = PROFILE_ID, model = null, effort = null, prompt = "Rewrite this", systemPrompt = "You rewrite.",
            gateway = IndagiumToolGateway(emptyList(), emptyMap()), toolCallLimit = 4, maxTurns = 8,
        )

        runBlocking { state.generateRecordingRewrite(request) }

        val line = timingLines("test-recording-rewrite").single()
        assertTrue("startup" in line && "1 model turns" in line && "total" in line, line)
        assertEquals(1, logFile.readLines().count { "test-agent-timing" in it && "test-recording-rewrite" in it })
    }
}
