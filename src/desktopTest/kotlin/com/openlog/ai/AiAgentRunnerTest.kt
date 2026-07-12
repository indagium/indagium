package com.openlog.ai

import com.openlog.debug.OpenLogToolDescriptor
import com.openlog.debug.OpenLogToolGateway
import com.openlog.ui.AppState
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiAgentRunnerTest {
    @Test
    fun executesAutomaticToolThenStreamsFinalAnswer() = runBlocking {
        var executions = 0
        val runner = runner(
            responses = listOf(
                listOf(
                    LlmStreamEvent.ToolCall(LlmToolCall("call-1", "set_filter", "{\"tabId\":\"tab-1\"}")),
                    LlmStreamEvent.Completed,
                ),
                listOf(LlmStreamEvent.TextDelta("Investigation complete."), LlmStreamEvent.Completed),
            ),
            handlers = mapOf("set_filter" to { _: Map<String, Any?> -> executions++; mapOf("ok" to true) }),
        )
        try {
            val session = AiSession("tab-1")
            val run = runner.start(session, "model", "Investigate")
            run.job!!.join()
            val events = run.events.replayCache

            assertEquals(1, executions)
            assertTrue(events.any { it is AiRunEvent.ToolRequested && it.call.name == "set_filter" })
            assertTrue(events.any { it is AiRunEvent.ToolCompleted && it.resultPreview.contains("ok=true") })
            assertTrue(events.any { it == AiRunEvent.AssistantDelta("Investigation complete.") })
            assertEquals(AiRunEvent.Done, events.last())
            assertEquals(4, session.messages.size) // user, assistant tool call, tool result, final assistant
            assertNull(session.activeRun)
            assertEquals(listOf(run), session.runs)
            assertEquals(events, run.history)
        } finally {
            runner.close()
        }
    }

    @Test
    fun failedRunKeepsItsHistoryAndPartialAssistantMessageInTheSession() = runBlocking {
        val runner = runner(
            responses = listOf(
                listOf(LlmStreamEvent.TextDelta("Partial finding"), LlmStreamEvent.Error("provider failed")),
            ),
            handlers = emptyMap(),
        )
        try {
            val session = AiSession("tab-1")
            val run = runner.start(session, "model", "Investigate")
            run.job!!.join()

            assertNull(session.activeRun)
            assertEquals(listOf(run), session.runs)
            assertTrue(run.history.any { it is AiRunEvent.Error && it.message == "provider failed" })
            assertEquals("Partial finding", session.messages.last().content)
        } finally {
            runner.close()
        }
    }

    @Test
    fun confirmationToolCannotExecuteBeforeApprovalAndRejectedActionIsReportedToModel() = runBlocking {
        var executions = 0
        val runner = runner(
            responses = listOf(
                listOf(
                    LlmStreamEvent.ToolCall(LlmToolCall("close-1", "close_tab", "{\"tabId\":\"tab-1\"}")),
                    LlmStreamEvent.Completed,
                ),
                listOf(LlmStreamEvent.TextDelta("Kept the tab open."), LlmStreamEvent.Completed),
            ),
            handlers = mapOf("close_tab" to { _: Map<String, Any?> -> executions++; mapOf("ok" to true) }),
        )
        try {
            val run = runner.start(AiSession("tab-1"), "model", "Close it")
            val confirmation = async { run.events.filterIsInstance<AiRunEvent.ConfirmationRequired>().first().confirmation }.await()
            assertEquals(0, executions, "The gateway must not run before approval.")
            assertTrue(runner.resolveConfirmation(run, confirmation.id, accepted = false))
            run.job!!.join()

            assertEquals(0, executions)
            assertTrue(run.events.replayCache.filterIsInstance<AiRunEvent.ToolCompleted>().single().resultPreview.contains("declined"))
            assertEquals(AiRunEvent.Done, run.events.replayCache.last())
        } finally {
            runner.close()
        }
    }

    @Test
    fun acceptedConfirmationExecutesTheGatewayOnce() = runBlocking {
        var executions = 0
        val runner = runner(
            responses = listOf(
                listOf(
                    LlmStreamEvent.ToolCall(LlmToolCall("close-1", "close_tab", "{\"tabId\":\"tab-1\"}")),
                    LlmStreamEvent.Completed,
                ),
                listOf(LlmStreamEvent.Completed),
            ),
            handlers = mapOf("close_tab" to { _: Map<String, Any?> -> executions++; mapOf("ok" to true) }),
        )
        try {
            val run = runner.start(AiSession("tab-1"), "model", "Close it")
            val confirmation = async { run.events.filterIsInstance<AiRunEvent.ConfirmationRequired>().first().confirmation }.await()
            assertTrue(runner.resolveConfirmation(run, confirmation.id, accepted = true))
            run.job!!.join()

            assertEquals(1, executions)
            assertEquals(AiRunEvent.Done, run.events.replayCache.last())
        } finally {
            runner.close()
        }
    }

    @Test
    fun cancellationInvalidatesPendingConfirmationWithoutExecutingGateway() = runBlocking {
        var executions = 0
        val runner = runner(
            responses = listOf(
                listOf(
                    LlmStreamEvent.ToolCall(LlmToolCall("close-1", "close_tab", "{\"tabId\":\"tab-1\"}")),
                    LlmStreamEvent.Completed,
                ),
            ),
            handlers = mapOf("close_tab" to { _: Map<String, Any?> -> executions++; mapOf("ok" to true) }),
        )
        try {
            val run = runner.start(AiSession("tab-1"), "model", "Close it")
            val confirmation = async { run.events.filterIsInstance<AiRunEvent.ConfirmationRequired>().first().confirmation }.await()
            runner.cancel(run)
            run.job!!.join()

            assertEquals(0, executions)
            assertFalse(runner.resolveConfirmation(run, confirmation.id, accepted = true))
            assertTrue(run.events.replayCache.any { it == AiRunEvent.Cancelled })
        } finally {
            runner.close()
        }
    }

    @Test
    fun malformedArgumentsBecomeAToolResultWithoutTouchingGateway() = runBlocking {
        var executions = 0
        val runner = runner(
            responses = listOf(
                listOf(
                    LlmStreamEvent.ToolCall(LlmToolCall("bad-1", "set_filter", "not-json")),
                    LlmStreamEvent.Completed,
                ),
                listOf(LlmStreamEvent.Completed),
            ),
            handlers = mapOf("set_filter" to { _: Map<String, Any?> -> executions++ }),
        )
        try {
            val run = runner.start(AiSession("tab-1"), "model", "Investigate")
            run.job!!.join()

            assertEquals(0, executions)
            assertTrue(run.events.replayCache.filterIsInstance<AiRunEvent.ToolCompleted>().single().resultPreview.contains("JSON object"))
            assertEquals(AiRunEvent.Done, run.events.replayCache.last())
        } finally {
            runner.close()
        }
    }

    @Test
    fun toolFailureIsReturnedToTheModelAndTrace() = runBlocking {
        val runner = runner(
            responses = listOf(
                listOf(
                    LlmStreamEvent.ToolCall(LlmToolCall("fail-1", "set_filter", "{}")),
                    LlmStreamEvent.Completed,
                ),
                listOf(LlmStreamEvent.Completed),
            ),
            handlers = mapOf("set_filter" to { _: Map<String, Any?> -> throw IllegalStateException("broken filter") }),
        )
        try {
            val run = runner.start(AiSession("tab-1"), "model", "Investigate")
            run.job!!.join()

            val result = run.events.replayCache.filterIsInstance<AiRunEvent.ToolCompleted>().single()
            assertTrue(result.resultPreview.contains("broken filter"))
            assertFalse(result.resultTruncated)
        } finally {
            runner.close()
        }
    }

    @Test
    fun truncatedToolResultsCarryAnExplicitNotice() = runBlocking {
        val runner = runner(
            responses = listOf(
                listOf(
                    LlmStreamEvent.ToolCall(LlmToolCall("large-1", "set_filter", "{}")),
                    LlmStreamEvent.Completed,
                ),
                listOf(LlmStreamEvent.Completed),
            ),
            handlers = mapOf("set_filter" to { _: Map<String, Any?> -> mapOf("data" to "x".repeat(500)) }),
            maxToolResultChars = 100,
        )
        try {
            val run = runner.start(AiSession("tab-1"), "model", "Investigate")
            run.job!!.join()

            val result = run.events.replayCache.filterIsInstance<AiRunEvent.ToolCompleted>().single()
            assertTrue(result.resultTruncated)
            assertTrue(result.resultPreview.contains("truncated"))
            assertTrue(result.resultPreview.length <= 100)
        } finally {
            runner.close()
        }
    }

    @Test
    fun cancellationCancelsProviderCollection() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val runner = AiAgentRunner(
            provider = object : LlmProvider {
                override val capabilities = ProviderCapabilities(streaming = true, toolCalls = true, modelDiscovery = false)

                override suspend fun listModels() = ModelDiscoveryResult.Unavailable("not used in test")

                override fun streamChat(request: LlmRequest): Flow<LlmStreamEvent> = kotlinx.coroutines.flow.flow {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            },
            toolGateway = gateway(emptyMap()),
        )
        try {
            val run = runner.start(AiSession("tab-1"), "model", "Investigate")
            started.await()
            runner.cancel(run)
            run.job!!.join()

            cancelled.await()
            assertTrue(run.events.replayCache.any { it == AiRunEvent.Cancelled })
        } finally {
            runner.close()
        }
    }

    @Test
    fun stopsBeforeThirteenthToolRound() = runBlocking {
        var executions = 0
        val toolResponse = listOf(
            LlmStreamEvent.ToolCall(LlmToolCall("call", "set_filter", "{}")),
            LlmStreamEvent.Completed,
        )
        val runner = runner(
            responses = List(13) { toolResponse },
            handlers = mapOf("set_filter" to { _: Map<String, Any?> -> executions++ }),
        )
        try {
            val run = runner.start(AiSession("tab-1"), "model", "Keep investigating")
            run.job!!.join()

            assertEquals(12, executions)
            assertIs<AiRunEvent.Error>(run.events.replayCache.last())
            assertTrue((run.events.replayCache.last() as AiRunEvent.Error).message.contains("12 tool rounds"))
        } finally {
            runner.close()
        }
    }

    @Test
    fun sessionRegistryIsEphemeralAndNeverEntersAutosave() {
        val registry = AiSessionRegistry()
        val original = registry.sessionFor("tab-1")
        val privatePrompt = "must-not-enter-autosave"
        original.messages += LlmMessage(LlmRole.USER, privatePrompt)
        val autosave = File.createTempFile("openlog-ai-session", ".cache")
        val state = AppState(autosaveFile = autosave)
        try {
            state.autosaveNow()
            assertFalse(autosave.readText().contains(privatePrompt))

            registry.clear()
            val replacement = registry.sessionFor("tab-1")
            assertTrue(replacement.messages.isEmpty())
            assertFalse(original === replacement)
        } finally {
            state.close()
            autosave.delete()
        }
    }

    private fun runner(
        responses: List<List<LlmStreamEvent>>,
        handlers: Map<String, (Map<String, Any?>) -> Any?>,
        maxToolResultChars: Int = 12_000,
    ): AiAgentRunner = AiAgentRunner(ScriptedProvider(responses), gateway(handlers), maxToolResultChars = maxToolResultChars)

    private fun gateway(handlers: Map<String, (Map<String, Any?>) -> Any?>): OpenLogToolGateway {
        val catalog = handlers.keys.map { name ->
            OpenLogToolDescriptor(name, name, ToolSchema(properties = buildJsonObject { }))
        }
        return OpenLogToolGateway(catalog, handlers)
    }

    private class ScriptedProvider(private val responses: List<List<LlmStreamEvent>>) : LlmProvider {
        private var requestIndex = 0

        override val capabilities = ProviderCapabilities(streaming = true, toolCalls = true, modelDiscovery = false)

        override suspend fun listModels(): ModelDiscoveryResult = ModelDiscoveryResult.Unavailable("not used in test")

        override fun streamChat(request: LlmRequest): Flow<LlmStreamEvent> =
            flow {
                responses.getOrElse(requestIndex++) { listOf(LlmStreamEvent.Completed) }.forEach { emit(it) }
            }
    }
}
