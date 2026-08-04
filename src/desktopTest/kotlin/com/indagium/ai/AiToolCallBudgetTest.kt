package com.indagium.ai

import com.indagium.debug.IndagiumToolDescriptor
import com.indagium.debug.IndagiumToolGateway
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiToolCallBudgetTest {
    @Test
    fun managedMcpCallsShareTheRunBudgetAndRejectBeforeTheHandler() = runBlocking {
        var executions = 0
        val gateway = IndagiumToolGateway(
            listOf(IndagiumToolDescriptor("set_filter", "", ToolSchema(properties = buildJsonObject { }))),
            mapOf("set_filter" to { _: Map<String, Any?> -> executions++; mapOf("ok" to true) }),
        )
        val coordinator = AiToolExecutionCoordinator(gateway)
        val run = AiRun(tabId = "tab", maxToolCalls = 2)

        val first = coordinator.executeManaged(run, "set_filter", emptyMap())
        val second = coordinator.executeManaged(run, "set_filter", emptyMap())
        val third = coordinator.executeManaged(run, "set_filter", emptyMap())

        assertTrue(first.content.contains("ok=true"))
        assertTrue(second.content.contains("ok=true"))
        assertTrue(third.content.contains("budget exhausted"))
        assertEquals(2, executions)
        val completed = run.history.filterIsInstance<AiRunEvent.ToolCompleted>()
        assertEquals(3, completed.size)
        assertEquals(2, completed.last().budget.evidenceUsed)
        assertTrue(completed.last().budget.notesWritesUnlimited)
    }

    @Test
    fun notesAndMetadataRemainAvailableAfterAnalysisBudgetIsExhausted() = runBlocking {
        var annotationExecutions = 0
        val gateway = IndagiumToolGateway(
            listOf(
                IndagiumToolDescriptor("get_filter", "", ToolSchema(properties = buildJsonObject { })),
                IndagiumToolDescriptor("get_annotation_blocks", "", ToolSchema(properties = buildJsonObject { })),
                IndagiumToolDescriptor("add_text_note", "", ToolSchema(properties = buildJsonObject { })),
                IndagiumToolDescriptor("set_case_metadata", "", ToolSchema(properties = buildJsonObject { })),
            ),
            mapOf(
                "get_filter" to { _: Map<String, Any?> -> mapOf("ok" to true) },
                "get_annotation_blocks" to { _: Map<String, Any?> -> mapOf("blocks" to emptyList<Any>()) },
                "add_text_note" to { _: Map<String, Any?> -> annotationExecutions++; mapOf("ok" to true) },
                "set_case_metadata" to { _: Map<String, Any?> -> annotationExecutions++; mapOf("ok" to true) },
            ),
        )
        val coordinator = AiToolExecutionCoordinator(gateway)
        val run = AiRun(tabId = "tab", maxToolCalls = 1)

        coordinator.executeManaged(run, "get_filter", emptyMap())
        val listed = coordinator.executeManaged(run, "get_annotation_blocks", emptyMap())
        val note = coordinator.executeManaged(run, "add_text_note", emptyMap())
        val metadata = coordinator.executeManaged(run, "set_case_metadata", emptyMap())

        assertTrue(listed.content.contains("blocks=[]"))
        assertTrue(note.content.contains("ok=true"))
        assertTrue(metadata.content.contains("ok=true"))
        assertEquals(2, annotationExecutions)
        assertEquals(1, run.toolCallBudget.snapshot().evidenceUsed)
        assertEquals(2, run.toolCallBudget.snapshot().notesWritesUsed)
    }

    @Test
    fun caseMetadataIsAnUnlimitedNotesOperation() {
        val budget = AiToolCallBudget(2)

        val decision = budget.tryConsume("set_case_metadata")

        assertTrue(decision.allowed)
        assertTrue(decision.isNotesWrite)
        assertEquals(1, decision.snapshot.notesWritesUsed)
        assertTrue(decision.snapshot.notesWritesUnlimited)
    }
}
