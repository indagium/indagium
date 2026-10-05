package com.indagium.debug

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val SLOW_HANDLER_MS = 50L

class IndagiumToolGatewaySuspendTest {
    private fun descriptor(name: String) = IndagiumToolDescriptor(name, "", ToolSchema(properties = buildJsonObject { }))

    private val syncCalls = AtomicInteger()

    private fun gateway(
        extraConfirmation: Set<String> = emptySet(),
        descriptions: Map<String, String> = emptyMap(),
    ) = IndagiumToolGateway(
        catalog = listOf(descriptor("quick"), descriptor("slow"), descriptor("open_log_file")),
        handlers = mapOf(
            "quick" to { args: Map<String, Any?> -> syncCalls.incrementAndGet(); mapOf("echo" to args["v"]) },
            "open_log_file" to { _: Map<String, Any?> -> mapOf("ok" to true) },
        ),
        suspendHandlers = mapOf(
            "slow" to { args: Map<String, Any?> ->
                delay(SLOW_HANDLER_MS)
                mapOf("waited" to true, "echo" to args["v"])
            },
        ),
        extraConfirmationRequired = extraConfirmation,
        confirmationDescriptions = descriptions,
    )

    @Test
    fun syncHandlersAreWrappedAndSuspendHandlersRunDirectly() = runBlocking {
        val gateway = gateway()
        assertEquals(mapOf("echo" to 1), gateway.executeSuspending("quick", mapOf("v" to 1)))
        assertEquals(mapOf("waited" to true, "echo" to 2), gateway.executeSuspending("slow", mapOf("v" to 2)))
        assertEquals(1, syncCalls.get())
    }

    @Test
    fun aSuspendHandlerActuallySuspendsInsteadOfBlockingTheCaller() = runBlocking {
        val gateway = gateway()
        val order = mutableListOf<String>()
        // One thread runs both coroutines: "other" can only run first if the slow handler suspended.
        val pending = async { gateway.executeSuspending("slow", emptyMap()); order += "slow finished" }
        val other = async { order += "other ran" }
        pending.await()
        other.await()
        assertEquals(listOf("other ran", "slow finished"), order)
    }

    @Test
    fun executeStillWorksForBothKindsForCallersThatCannotSuspend() {
        val gateway = gateway()
        assertEquals(mapOf("echo" to "a"), gateway.execute("quick", mapOf("v" to "a")))
        assertEquals(mapOf("waited" to true, "echo" to "b"), gateway.execute("slow", mapOf("v" to "b")))
    }

    @Test
    fun unknownOperationsAreAnErrorMapEitherWay() = runBlocking {
        val gateway = gateway()
        assertEquals(mapOf("error" to "unknown operation: nope"), gateway.execute("nope", emptyMap()))
        assertEquals(mapOf("error" to "unknown operation: nope"), gateway.executeSuspending("nope", emptyMap()))
    }

    @Test
    fun theExistingTwoArgumentConstructorIsUnchanged() = runBlocking {
        val plain = IndagiumToolGateway(listOf(descriptor("a")), mapOf("a" to { _: Map<String, Any?> -> "x" }))
        assertEquals("x", plain.execute("a", emptyMap()))
        assertEquals("x", plain.executeSuspending("a", emptyMap()))
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, plain.actionPolicy("a"))
        assertNull(plain.confirmationDescription("a"))
    }

    @Test
    fun parityIsEnforcedAcrossBothHandlerKinds() {
        val handler = { _: Map<String, Any?> -> 1 }
        val suspending: suspend (Map<String, Any?>) -> Any? = { 2 }
        assertFailsWith<IllegalArgumentException> { IndagiumToolGateway(listOf(descriptor("a"), descriptor("b")), mapOf("a" to handler)) }
        assertFailsWith<IllegalArgumentException> { IndagiumToolGateway(listOf(descriptor("a")), mapOf("a" to handler, "b" to handler)) }
        assertFailsWith<IllegalArgumentException> {
            IndagiumToolGateway(listOf(descriptor("a")), mapOf("a" to handler), mapOf("a" to suspending))
        }
        assertFailsWith<IllegalArgumentException> { IndagiumToolGateway(listOf(descriptor("a"), descriptor("a")), mapOf("a" to handler)) }
        IndagiumToolGateway(listOf(descriptor("a"), descriptor("b")), mapOf("a" to handler), mapOf("b" to suspending))
    }

    @Test
    fun extraConfirmationIsMergedWithoutChangingTheGlobalPolicy() {
        val gateway = gateway(extraConfirmation = setOf("slow"), descriptions = mapOf("slow" to "Run the slow thing"))
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, gateway.actionPolicy("slow"))
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, gateway.actionPolicy("quick"))
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, gateway.actionPolicy("open_log_file"), "the global set still applies")
        assertEquals("Run the slow thing", gateway.confirmationDescription("slow"))
        assertNull(gateway.actionPolicy("not_in_catalog"))
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, gateway().actionPolicy("open_log_file"))
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, gateway().actionPolicy("slow"))
    }

    @Test
    fun tryTestScriptAsksForConfirmationInTheAiPanel() {
        val catalog = TEST_SUITE_MCP_TOOLS.first { it.name == "try_test_script" }
        val gateway = IndagiumToolGateway(listOf(catalog), mapOf("try_test_script" to { _: Map<String, Any?> -> 1 }))
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, gateway.actionPolicy("try_test_script"))
    }
}
