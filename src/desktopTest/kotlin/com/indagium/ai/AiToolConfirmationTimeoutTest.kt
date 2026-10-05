package com.indagium.ai

import com.indagium.debug.IndagiumToolDescriptor
import com.indagium.debug.IndagiumToolGateway
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 80L
private const val WAIT_LIMIT_MS = 5_000L

class AiToolConfirmationTimeoutTest {
    private val executions = AtomicInteger()

    private fun coordinator(): AiToolExecutionCoordinator {
        val gateway = IndagiumToolGateway(
            catalog = listOf(IndagiumToolDescriptor("delete_test_suite", "", ToolSchema(properties = buildJsonObject { }))),
            handlers = mapOf("delete_test_suite" to { _: Map<String, Any?> -> executions.incrementAndGet(); mapOf("deleted" to true) }),
        )
        return AiToolExecutionCoordinator(gateway)
    }

    private suspend fun awaitPending(run: AiRun): String {
        withTimeout(WAIT_LIMIT_MS) { while (run.confirmations.isEmpty()) delay(5) }
        return run.confirmations.keys.single()
    }

    @Test
    fun anUnansweredConfirmationIsDeniedAfterTheTimeoutAndTheHandlerNeverRuns() = runBlocking {
        val run = AiRun(tabId = "tab", confirmationTimeoutMs = TIMEOUT_MS)

        val result = coordinator().executeManaged(run, "delete_test_suite", emptyMap())

        assertTrue(result.content.contains("denied"), result.content)
        assertEquals(0, executions.get())
        assertEquals(0, run.pendingConfirmationCount, "the card is gone")
        assertTrue(run.history.any { it is AiRunEvent.ConfirmationRequired })
        assertTrue(run.history.filterIsInstance<AiRunEvent.Status>().any { it.text.contains("timed out") })
        assertEquals(1, run.history.filterIsInstance<AiRunEvent.ToolCompleted>().size)
    }

    @Test
    fun aLateAnswerAfterTheTimeoutFindsNothingToResolve() = runBlocking {
        val run = AiRun(tabId = "tab", confirmationTimeoutMs = TIMEOUT_MS)
        coordinator().executeManaged(run, "delete_test_suite", emptyMap())
        val confirmationId = (run.history.first { it is AiRunEvent.ConfirmationRequired } as AiRunEvent.ConfirmationRequired).confirmation.id

        assertEquals(false, run.isConfirmationPending(confirmationId))
        assertEquals(0, executions.get())
    }

    @Test
    fun anAnswerBeforeTheTimeoutStillWorks() = runBlocking {
        val accepted = AiRun(tabId = "tab", confirmationTimeoutMs = WAIT_LIMIT_MS)
        val call = async { coordinator().executeManaged(accepted, "delete_test_suite", emptyMap()) }
        accepted.confirmations.getValue(awaitPending(accepted)).complete(true)
        assertTrue(call.await().content.contains("deleted=true"))
        assertEquals(1, executions.get())

        val declined = AiRun(tabId = "tab", confirmationTimeoutMs = WAIT_LIMIT_MS)
        val other = async { coordinator().executeManaged(declined, "delete_test_suite", emptyMap()) }
        declined.confirmations.getValue(awaitPending(declined)).complete(false)
        assertTrue(other.await().content.contains("declined"))
        assertEquals(1, executions.get())
    }

    @Test
    fun withoutATimeoutTheConfirmationWaitsUntilAnswered() = runBlocking {
        val run = AiRun(tabId = "tab")
        assertEquals(null, run.confirmationTimeoutMs)
        val call = async { coordinator().executeManaged(run, "delete_test_suite", emptyMap()) }
        val id = awaitPending(run)
        delay(TIMEOUT_MS * 3)
        assertTrue(call.isActive, "still waiting well past the timeout a bounded run would have used")
        run.confirmations.getValue(id).complete(true)
        assertTrue(call.await().content.contains("deleted=true"))
    }

    @Test
    fun confirmationDescriptionsComeFromTheGatewayWhenItHasThem() = runBlocking {
        val gateway = IndagiumToolGateway(
            catalog = listOf(IndagiumToolDescriptor("my_script", "", ToolSchema(properties = buildJsonObject { }))),
            handlers = emptyMap(),
            suspendHandlers = mapOf("my_script" to { _: Map<String, Any?> -> mapOf("ok" to true) }),
            extraConfirmationRequired = setOf("my_script"),
            confirmationDescriptions = mapOf("my_script" to "Run script 'my_script' on this computer"),
        )
        val run = AiRun(tabId = "tab")
        val call = async { AiToolExecutionCoordinator(gateway).executeManaged(run, "my_script", emptyMap()) }
        val id = awaitPending(run)
        val card = (run.history.first { it is AiRunEvent.ConfirmationRequired } as AiRunEvent.ConfirmationRequired).confirmation
        assertEquals("Run script 'my_script' on this computer", card.description)
        run.confirmations.getValue(id).complete(true)
        assertTrue(call.await().content.contains("ok=true"))
    }
}
