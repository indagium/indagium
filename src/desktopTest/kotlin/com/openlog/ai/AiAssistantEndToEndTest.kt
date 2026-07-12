package com.openlog.ai

import com.openlog.debug.OpenLogToolDescriptor
import com.openlog.debug.OpenLogToolGateway
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Acceptance-level fake-provider contract: the agent must retain a streamed answer, execute a
 * safe tool, surface evidence from that real tool result, pause before a lifecycle action, and
 * continue after the user decides. Individual unit tests cover the failure modes in more detail.
 */
class AiAssistantEndToEndTest {
    @Test
    fun streamedInvestigationUsesToolEvidenceAndWaitsForConfirmation() = runBlocking {
        var closeExecutions = 0
        val gateway = OpenLogToolGateway(
            catalog = listOf("get_line_context", "close_tab").map(::descriptor),
            handlers = mapOf(
                "get_line_context" to {
                    mapOf(
                        "tabId" to "tab-1",
                        "lines" to listOf(
                            mapOf("id" to 41, "message" to "before crash"),
                            mapOf("id" to 42, "message" to "fatal error"),
                        ),
                    )
                },
                "close_tab" to { closeExecutions++; mapOf("ok" to true) },
            ),
        )
        val provider = ScriptedProvider(
            listOf(
                LlmStreamEvent.TextDelta("I found the relevant context. "),
                LlmStreamEvent.ToolCall(LlmToolCall("context-1", "get_line_context", "{\"tabId\":\"tab-1\",\"lineId\":42}")),
                LlmStreamEvent.ToolCall(LlmToolCall("close-1", "close_tab", "{\"tabId\":\"tab-1\"}")),
                LlmStreamEvent.Completed,
            ),
            listOf(LlmStreamEvent.TextDelta("I kept the tab open and cited the evidence."), LlmStreamEvent.Completed),
        )
        val runner = AiAgentRunner(provider, gateway)
        try {
            val run = runner.start(AiSession("tab-1"), "fake-model", "Investigate this failure")
            val confirmation = async {
                run.events.filterIsInstance<AiRunEvent.ConfirmationRequired>().first().confirmation
            }.await()

            val contextResult = run.history.filterIsInstance<AiRunEvent.ToolCompleted>()
                .single { it.call.name == "get_line_context" }
            assertEquals(AiEvidence.LogRows("tab-1", listOf(41, 42)), contextResult.evidence.single())
            assertEquals(0, closeExecutions, "The lifecycle action must wait for the user decision.")

            assertTrue(runner.resolveConfirmation(run, confirmation.id, accepted = false))
            run.job!!.join()

            assertEquals(0, closeExecutions)
            assertTrue(run.history.any { it == AiRunEvent.AssistantDelta("I found the relevant context. ") })
            assertTrue(run.history.any { it == AiRunEvent.AssistantDelta("I kept the tab open and cited the evidence.") })
            val declined = run.history.filterIsInstance<AiRunEvent.ToolCompleted>().single { it.call.name == "close_tab" }
            assertTrue(declined.resultPreview.contains("declined"))
            assertFalse(declined.evidence.isNotEmpty())
            assertEquals(AiRunEvent.Done, run.history.last())
        } finally {
            runner.close()
        }
    }

    private fun descriptor(name: String) = OpenLogToolDescriptor(
        name = name,
        description = name,
        schema = ToolSchema(properties = buildJsonObject { }),
    )

    private class ScriptedProvider(private vararg val responses: List<LlmStreamEvent>) : LlmProvider {
        private var requestIndex = 0

        override val capabilities = ProviderCapabilities(streaming = true, toolCalls = true, modelDiscovery = false)

        override suspend fun listModels(): ModelDiscoveryResult = ModelDiscoveryResult.Unavailable("not needed")

        override fun streamChat(request: LlmRequest): Flow<LlmStreamEvent> =
            responses.getOrElse(requestIndex++) { listOf(LlmStreamEvent.Completed) }.asFlow()
    }
}
