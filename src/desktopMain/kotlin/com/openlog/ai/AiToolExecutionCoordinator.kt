package com.openlog.ai

import com.openlog.debug.OpenLogToolActionPolicy
import com.openlog.debug.OpenLogToolGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/**
 * Shared in-process policy boundary for model function calls and managed MCP calls.
 * It is deliberately below the model/agent loops so every provider gets the same tab pinning,
 * confirmation behavior, result bounding, and evidence extraction.
 */
internal class AiToolExecutionCoordinator(
    private val toolGateway: OpenLogToolGateway,
    private val maxToolResultChars: Int = DEFAULT_MAX_TOOL_RESULT_CHARS,
) {
    init {
        require(maxToolResultChars > 0) { "maxToolResultChars must be positive" }
    }

    suspend fun execute(run: AiRun, call: LlmToolCall): AiToolExecutionResult {
        val arguments = parseArguments(call.argumentsJson)
            ?: return complete(run, call, AiToolExecutionResult.error("Tool arguments must be a JSON object."))
        return execute(run, call, arguments)
    }

    suspend fun executeManaged(run: AiRun, name: String, arguments: Map<String, Any?>): AiToolExecutionResult =
        execute(run, LlmToolCall("managed-${UUID.randomUUID()}", name, "{}"), arguments)

    private suspend fun execute(
        run: AiRun,
        call: LlmToolCall,
        arguments: Map<String, Any?>,
    ): AiToolExecutionResult {
        run.emit(AiRunEvent.ToolRequested(call))
        // The in-app panel is always tied to the tab that created the run. External MCP clients
        // remain explicitly multi-tab; only managed account-agent sessions take this path.
        val pinnedArguments = if (
            call.name in TAB_SCOPED_TOOL_NAMES || (call.name == "resolve_log_source" && "tabId" in arguments)
        ) {
            arguments + ("tabId" to run.tabId)
        } else {
            arguments
        }
        if (toolGateway.actionPolicy(call.name) == OpenLogToolActionPolicy.CONFIRMATION_REQUIRED) {
            val confirmation = AiToolConfirmation(
                id = UUID.randomUUID().toString(),
                call = call,
                description = confirmationDescription(call.name),
            )
            val decision = CompletableDeferred<Boolean>()
            run.confirmations[confirmation.id] = decision
            run.emit(AiRunEvent.ConfirmationRequired(confirmation))
            val accepted = try {
                decision.await()
            } finally {
                run.confirmations.remove(confirmation.id, decision)
            }
            if (!accepted) return complete(run, call, AiToolExecutionResult.error("The user declined this action; no changes were made."))
        }

        val result = try {
            val rawResult = toolGateway.execute(call.name, pinnedArguments)
            AiToolExecutionResult.from(rawResult, maxToolResultChars, AiEvidenceExtractor.from(call.name, rawResult))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AiToolExecutionResult.error("Tool '${call.name}' failed: ${error.message ?: "unexpected error"}")
        }
        return complete(run, call, result)
    }

    private suspend fun complete(run: AiRun, call: LlmToolCall, result: AiToolExecutionResult): AiToolExecutionResult {
        run.emit(AiRunEvent.ToolCompleted(call, result.preview, result.truncated, result.evidence))
        return result
    }

    private fun parseArguments(rawArguments: String): Map<String, Any?>? = try {
        json.parseToJsonElement(rawArguments).jsonObject.toKotlinMap()
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.toKotlinMap(): Map<String, Any?> = entries.associate { (key, value) -> key to value.toKotlinValue() }

    private fun JsonElement.toKotlinValue(): Any? = when (this) {
        JsonNull -> null
        is JsonObject -> toKotlinMap()
        is JsonArray -> map { it.toKotlinValue() }
        is JsonPrimitive -> booleanOrNull ?: doubleOrNull ?: content
    }

    private fun confirmationDescription(toolName: String): String = when (toolName) {
        "open_log_file", "split_log_file" -> "Open or split a log file"
        "close_tab", "merge_tabs" -> "Change open log tabs"
        "start_tailing", "stop_tailing" -> "Change live tailing"
        "export_analysis", "export_filtered_log" -> "Write an export file"
        "save_annotations", "load_annotations" -> "Save or load annotation files"
        else -> "Perform a confirmation-required action"
    }

    private companion object {
        const val DEFAULT_MAX_TOOL_RESULT_CHARS = 12_000
        val TAB_SCOPED_TOOL_NAMES = setOf(
            "close_tab", "get_filter", "set_filter", "get_visible_lines", "get_line_context",
            "select_lines", "get_selection", "toggle_group", "expand_all", "collapse_all",
            "get_tags", "get_packages", "get_crash_sites", "get_issue_description",
            "get_annotation_sections", "append_annotation_section",
            "add_text_note", "add_log_note", "update_note_block", "move_note_block",
            "delete_note_block", "export_analysis", "export_filtered_log", "save_annotations",
            "load_annotations", "apply_filter_preset", "start_tailing", "stop_tailing",
            // search_similar_cases/get_case are deliberately NOT pinned — search_similar_cases is
            // tab-independent (it searches the whole notes corpus) and get_case takes a case `id`,
            // not a tabId, so pinning either would silently inject an unused/wrong argument.
            "set_case_metadata",
        )
        val json = Json { ignoreUnknownKeys = true }
    }
}

internal data class AiToolExecutionResult(
    val content: String,
    val preview: String,
    val truncated: Boolean,
    val evidence: List<AiEvidence> = emptyList(),
) {
    companion object {
        fun from(value: Any?, maxChars: Int, evidence: List<AiEvidence>): AiToolExecutionResult {
            val rendered = value?.toString() ?: "null"
            return if (rendered.length <= maxChars) {
                AiToolExecutionResult(rendered, rendered, truncated = false, evidence = evidence)
            } else {
                val notice = "\n\n[Tool result truncated to $maxChars characters by openLog.]"
                val bounded = rendered.take((maxChars - notice.length).coerceAtLeast(0)) + notice
                AiToolExecutionResult(bounded, bounded, truncated = true, evidence = evidence)
            }
        }

        fun error(message: String): AiToolExecutionResult = AiToolExecutionResult(message, message, truncated = false)
    }
}
