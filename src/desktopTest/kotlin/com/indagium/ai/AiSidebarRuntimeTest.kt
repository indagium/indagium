package com.indagium.ai

import com.indagium.debug.IndagiumToolDescriptor
import com.indagium.debug.IndagiumToolGateway
import com.indagium.model.defaultAiProviderProfile
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AiSidebarRuntimeTest {
    @Test
    fun keepsSessionsPerTabAndRetriesTheLastPromptWithoutDuplicatingIt() = runBlocking<Unit> {
        val providers = ArrayDeque(
            listOf(
                ScriptedProvider(listOf(LlmStreamEvent.Error("offline"))),
                ScriptedProvider(listOf(LlmStreamEvent.TextDelta("Recovered"), LlmStreamEvent.Completed)),
            ),
        )
        val runtime = runtime(provider = { providers.removeFirst() })
        try {
            val profile = defaultAiProviderProfile().copy(model = "local-model")
            val first = assertIs<AiStartResult.Started>(runtime.start("tab-a", profile, "", "Explain the crash")).run
            first.job!!.join()
            assertEquals("Explain the crash", runtime.sessionFor("tab-a").lastPrompt)
            assertTrue(runtime.sessionFor("tab-b").runs.isEmpty())

            val retry = assertIs<AiStartResult.Started>(runtime.retry("tab-a", profile, "")).run
            retry.job!!.join()

            val userMessages = runtime.sessionFor("tab-a").messages.filter { it.role == LlmRole.USER }
            assertEquals(listOf("Explain the crash"), userMessages.map { it.content })
            assertTrue(retry.history.any { it == AiRunEvent.AssistantDelta("Recovered") })
        } finally {
            runtime.close()
        }
    }

    @Test
    fun captureReplacementMovesTheActiveConversationAndRunToTheNewTab() {
        val runtime = runtime(provider = { ScriptedProvider(emptyList()) })
        try {
            val session = runtime.sessionFor("capture-before")
            val run = AiRun(
                tabId = "capture-before",
                userPrompt = "Start a new capture",
                context = AiInvestigationContext("capture-before", isDeviceCapture = true),
            )
            session.activeRun = run
            session.retain(run)
            session.messages += LlmMessage(LlmRole.USER, "Start a new capture")

            runtime.transferCaptureSession("capture-before", "capture-after")

            assertSame(session, runtime.sessionFor("capture-after"))
            assertEquals("capture-after", session.tabId)
            assertEquals("capture-after", run.tabId)
            assertEquals("capture-after", run.context.tabId)
            assertTrue(run.context.isDeviceCapture)
            assertEquals("capture-after", session.lastContext.tabId)
            assertTrue(session.lastContext.isDeviceCapture)
            assertEquals(listOf("Start a new capture"), session.messages.map { it.content })
            assertTrue(runtime.sessionFor("capture-before").runs.isEmpty())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun liveDeviceCaptureGuidanceIsOnlyAddedForDeviceCaptureTabs() {
        val guidance = deviceCapturePromptGuidance(isDeviceCapture = true)
        assertTrue(guidance.contains("The Mac's Chrome/browser and desktop are not the captured device"))
        assertTrue(guidance.contains("get_device_screen"))
        assertTrue(guidance.contains("device_tap"))
        assertTrue(guidance.contains("mark_device_issue"))
        assertTrue(deviceCapturePromptGuidance(isDeviceCapture = false).isEmpty())
    }

    @Test
    fun rejectsUnsafeProfileBeforeCreatingAProvider() {
        var created = false
        val runtime = runtime(provider = { created = true; ScriptedProvider(emptyList()) })
        try {
            val unsafe = defaultAiProviderProfile().copy(
                baseUrl = "https://remote.example/v1",
                model = "remote-model",
                remoteDisclosureAcknowledged = false,
            )
            val result = runtime.start("tab-a", unsafe, "secret", "Investigate")

            assertIs<AiStartResult.Rejected>(result)
            assertTrue(result.message.contains("Acknowledge"))
            assertTrue(!created)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun confirmationIsExposedAndMustBeResolvedThroughRuntime() = runBlocking<Unit> {
        var executions = 0
        val provider = ScriptedProvider(
            listOf(
                LlmStreamEvent.ToolCall(LlmToolCall("close", "close_tab", "{}")),
                LlmStreamEvent.Completed,
            ),
            // After the user declines the confirmation, the agent must send the tool result back
            // to the model. A real provider then produces a final answer; model that second turn
            // explicitly so joining this run verifies the whole lifecycle rather than waiting for
            // an identical, artificial second confirmation.
            listOf(LlmStreamEvent.TextDelta("I will leave the tab open."), LlmStreamEvent.Completed),
        )
        val runtime = runtime(
            provider = { provider },
            handlers = mapOf("close_tab" to { executions++; mapOf("ok" to true) }),
        )
        try {
            val run = assertIs<AiStartResult.Started>(
                runtime.start("tab-a", defaultAiProviderProfile().copy(model = "local-model"), "", "Close it"),
            ).run
            val confirmation = run.events.filterIsInstance<AiRunEvent.ConfirmationRequired>().first().confirmation
            assertEquals(1, run.pendingConfirmationCount)
            assertEquals(0, executions)
            assertTrue(runtime.resolveConfirmation(run, confirmation, accepted = false))
            run.job!!.join()
            assertEquals(0, executions)
            assertEquals(0, run.pendingConfirmationCount)
            assertNotNull(run.history.filterIsInstance<AiRunEvent.ToolCompleted>().singleOrNull())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun liveCaptureDeviceOperationsRunWithoutApprovalAndStayPinnedToTheirCapture() = runBlocking<Unit> {
        val receivedArguments = mutableListOf<Map<String, Any?>>()
        var round = 0
        val provider = object : LlmProvider {
            override val capabilities = ProviderCapabilities(streaming = true, toolCalls = true, modelDiscovery = false)

            override suspend fun listModels(): ModelDiscoveryResult = ModelDiscoveryResult.Unavailable("not used")

            override fun streamChat(request: LlmRequest): Flow<LlmStreamEvent> = flow {
                when (round++) {
                    0 -> emit(LlmStreamEvent.ToolCall(LlmToolCall("screen", "get_device_screen", "{}")))
                    1 -> emit(LlmStreamEvent.ToolCall(LlmToolCall("tap", "device_tap", "{\"x\":20,\"y\":30}")))
                    else -> emit(LlmStreamEvent.TextDelta("I inspected the device."))
                }
                emit(LlmStreamEvent.Completed)
            }
        }
        val runtime = runtime(
            provider = { provider },
            handlers = mapOf(
                "get_device_screen" to { args ->
                    receivedArguments += args
                    mapOf("deviceSerial" to "emulator-5554", "imageBase64" to "c2NyZWVu", "mimeType" to "image/jpeg")
                },
                "device_tap" to { args ->
                    receivedArguments += args
                    mapOf("deviceSerial" to "emulator-5554", "action" to "tap")
                },
            ),
        )
        try {
            val started = assertIs<AiStartResult.Started>(
                runtime.start(
                    tabId = "capture-tab",
                    profile = defaultAiProviderProfile().copy(model = "local-model"),
                    apiKey = "",
                    prompt = "Inspect this device",
                    context = AiInvestigationContext("capture-tab", isDeviceCapture = true),
                ),
            )
            val run = started.run
            run.job!!.join()

            // No approval card: the user's own prompt that started this in-app run authorizes
            // both device tool calls, so neither waits on a confirmation.
            assertEquals(listOf("capture-tab", "capture-tab"), receivedArguments.map { it["tabId"] })
            assertTrue(run.history.filterIsInstance<AiRunEvent.ConfirmationRequired>().isEmpty())
            assertEquals("capture-tab", run.deviceCaptureTabId)
            // The bound serial is scoped to one run and is reset once it completes, same as the
            // approval state it replaced.
            assertNull(run.deviceBoundSerial)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun explicitConnectionTestKeepsStatusPerProfileAndReportsFailures() = runBlocking<Unit> {
        val providers = ArrayDeque<LlmProvider>(
            listOf(
                object : LlmProvider {
                    override val capabilities = ProviderCapabilities(true, true, true)

                    override suspend fun listModels() = ModelDiscoveryResult.Available(listOf(LlmModel("local")))

                    override fun streamChat(request: LlmRequest): Flow<LlmStreamEvent> = flow { emit(LlmStreamEvent.Completed) }
                },
                object : LlmProvider {
                    override val capabilities = ProviderCapabilities(true, true, true)

                    override suspend fun listModels() = ModelDiscoveryResult.Unavailable("HTTP 503")

                    override fun streamChat(request: LlmRequest): Flow<LlmStreamEvent> = flow { emit(LlmStreamEvent.Completed) }
                },
            ),
        )
        val runtime = runtime(provider = { providers.removeFirst() })
        try {
            val profile = defaultAiProviderProfile().copy(model = "local-model")
            assertEquals(AiConnectionState.NotChecked, runtime.connectionState(profile.id))
            assertEquals(AiConnectionState.Ready, runtime.testConnection(profile, ""))
            assertIs<AiConnectionState.Failed>(runtime.testConnection(profile.copy(id = "other"), ""))
            assertEquals(AiConnectionState.Ready, runtime.connectionState(profile.id))
        } finally {
            runtime.close()
        }
    }

    private fun runtime(
        provider: (() -> LlmProvider),
        handlers: Map<String, (Map<String, Any?>) -> Any?> = emptyMap(),
    ): AiSidebarRuntime {
        val names = if (handlers.isEmpty()) setOf("noop") else handlers.keys
        val allHandlers = if (handlers.isEmpty()) mapOf("noop" to { _: Map<String, Any?> -> Unit }) else handlers
        val gateway = IndagiumToolGateway(
            names.map { IndagiumToolDescriptor(it, it, ToolSchema(properties = buildJsonObject { })) },
            allHandlers,
        )
        return AiSidebarRuntime(
            sessions = AiSessionRegistry(),
            toolGatewayFactory = { gateway },
            providerFactory = AiProviderFactory { _, _ -> provider() },
        )
    }

    private class ScriptedProvider(vararg responses: List<LlmStreamEvent>) : LlmProvider {
        private val responses = ArrayDeque(responses.asList())

        override val capabilities = ProviderCapabilities(streaming = true, toolCalls = true, modelDiscovery = false)

        override suspend fun listModels(): ModelDiscoveryResult = ModelDiscoveryResult.Unavailable("not used")

        override fun streamChat(request: LlmRequest): Flow<LlmStreamEvent> =
            flow {
                (responses.removeFirstOrNull() ?: listOf(LlmStreamEvent.Completed)).forEach { emit(it) }
            }
    }
}
