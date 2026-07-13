package com.openlog.ai

import com.openlog.debug.OpenLogToolDescriptor
import com.openlog.debug.OpenLogToolGateway
import com.openlog.model.defaultAiProviderProfile
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
        val gateway = OpenLogToolGateway(
            names.map { OpenLogToolDescriptor(it, it, ToolSchema(properties = buildJsonObject { })) },
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
