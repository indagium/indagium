package com.indagium.ai

import com.indagium.debug.IndagiumToolActionPolicy
import com.indagium.debug.IndagiumToolGateway
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
    private val toolGateway: IndagiumToolGateway,
    private val maxToolResultChars: Int = DEFAULT_MAX_TOOL_RESULT_CHARS,
    private val onCaptureTabChanged: (String, String) -> Unit = { _, _ -> },
) {
    init {
        require(maxToolResultChars > 0) { "maxToolResultChars must be positive" }
    }

    suspend fun execute(run: AiRun, call: LlmToolCall): AiToolExecutionResult {
        val arguments = parseArguments(call.argumentsJson)
            ?: return rejectMalformedArguments(run, call)
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
        // Reserve before any confirmation or gateway access. This is the shared enforcement point
        // for direct API providers and managed Codex/Claude MCP sessions, which can call from
        // different threads.
        val budgetDecision = run.toolCallBudget.tryConsume(call.name)
        if (!budgetDecision.allowed) {
            val result = AiToolExecutionResult.error(
                run.toolCallBudget.rejectionMessage(budgetDecision.isNotesWrite, budgetDecision.snapshot),
            ).withBudgetFooter(run.toolCallBudget.resultFooter(budgetDecision.snapshot))
            return complete(
                run,
                call,
                result,
                run.toolCallBudget.recordResult(result.returnedChars, result.truncated),
            )
        }
        // The in-app panel is always tied to the tab that created the run. External MCP clients
        // remain explicitly multi-tab; only managed account-agent sessions take this path.
        //
        // In-app runs (direct-API providers via AiAgentRunner, and managed account agents via
        // ManagedMcpRunRegistry) never show a device-approval card: the user's own prompt that
        // started this run is the authorization to view and control the bound capture's device.
        // Only external MCP clients still gate device tools behind a per-session approval — see
        // ControlServer.executeExternalDeviceTool / AppState.executeExternalDeviceAiAction, which
        // this coordinator is never used for.
        val prepared = prepareCallArguments(run, call, arguments)
        if (!awaitConfirmationIfRequired(run, call)) {
            return completeCountedResult(run, call, AiToolExecutionResult.error("The user declined this action; no changes were made."))
        }

        val result = try {
            val rawResult = toolGateway.execute(call.name, prepared.arguments)
            AiToolExecutionResult.from(rawResult, maxToolResultChars, AiEvidenceExtractor.from(call.name, rawResult))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AiToolExecutionResult.error("Tool '${call.name}' failed: ${error.message ?: "unexpected error"}")
        }
        updateDeviceStateAfterExecution(run, call, prepared.deviceScoped, result)
        return completeCountedResult(run, call, result)
    }

    /** Resolves the arguments actually sent to the gateway: `tabId` pinning for device/tab-scoped
     *  tools, and `deviceSerial` auto-fill/invalidation for the run's currently bound device. */
    private fun prepareCallArguments(run: AiRun, call: LlmToolCall, arguments: Map<String, Any?>): PreparedCall {
        val deviceScoped = call.name in DEVICE_CAPTURE_TOOL_NAMES
        val requestedDeviceSerial = (arguments["deviceSerial"] as? String)?.trim()?.takeIf(String::isNotBlank)
        if (
            call.name == "start_device_capture" && requestedDeviceSerial != null &&
            run.deviceBoundSerial != null && requestedDeviceSerial != run.deviceBoundSerial
        ) {
            // Switching devices drops the serial this run auto-fills into later device calls; a
            // successful start below re-binds it to whichever device actually started.
            run.deviceBoundSerial = null
        }
        val pinnedArguments = when {
            deviceScoped -> arguments + ("tabId" to run.deviceCaptureTabId)
            call.name in TAB_SCOPED_TOOL_NAMES || (call.name == "resolve_log_source" && "tabId" in arguments) ->
                arguments + ("tabId" to run.tabId)
            else -> arguments
        }
        val finalArguments = if (
            call.name == "start_device_capture" && requestedDeviceSerial == null && run.deviceBoundSerial != null
        ) {
            pinnedArguments + ("deviceSerial" to run.deviceBoundSerial)
        } else {
            pinnedArguments
        }
        return PreparedCall(deviceScoped, finalArguments)
    }

    /** Returns false only when a CONFIRMATION_REQUIRED tool's card was declined; true when no
     *  card was needed or the user accepted it. */
    private suspend fun awaitConfirmationIfRequired(run: AiRun, call: LlmToolCall): Boolean {
        if (toolGateway.actionPolicy(call.name) != IndagiumToolActionPolicy.CONFIRMATION_REQUIRED) return true
        val confirmation = AiToolConfirmation(id = UUID.randomUUID().toString(), call = call, description = confirmationDescription(call.name))
        val decision = CompletableDeferred<Boolean>()
        run.confirmations[confirmation.id] = decision
        run.emit(AiRunEvent.ConfirmationRequired(confirmation))
        return try {
            decision.await()
        } finally {
            run.confirmations.remove(confirmation.id, decision)
        }
    }

    /** Post-execution device bookkeeping: follow a capture replacement to its new tab, bind the
     *  device serial a call resolved (once per run), and drop it again on a disconnect-shaped
     *  failure so a later call doesn't auto-fill a stale one. */
    private fun updateDeviceStateAfterExecution(run: AiRun, call: LlmToolCall, deviceScoped: Boolean, result: AiToolExecutionResult) {
        val resultMap = result.raw as? Map<*, *>
        if (call.name == "start_device_capture") {
            (resultMap?.get("tabId") as? String)?.takeIf(String::isNotBlank)?.let { newTabId ->
                val previousTabId = run.deviceCaptureTabId
                if (newTabId != previousTabId) {
                    // A start after stop_device_capture also returns a new tab even when
                    // newCapture=false. Move the active conversation before returning to the
                    // model so the visible sidebar, retained transcript, and subsequent
                    // tab-scoped tools follow whichever capture was actually started.
                    onCaptureTabChanged(previousTabId, newTabId)
                    run.tabId = newTabId
                    run.context = AiInvestigationContext(newTabId, isDeviceCapture = true)
                }
                run.deviceCaptureTabId = newTabId
            }
        }
        (resultMap?.get("deviceSerial") as? String)?.takeIf(String::isNotBlank)?.let { returnedSerial ->
            if (run.deviceBoundSerial == null) run.deviceBoundSerial = returnedSerial
        }
        val deviceFailure = (resultMap?.get("error") as? String).orEmpty().lowercase()
        if (deviceScoped && listOf("disconnected", "not connected", "no longer live", "no active session").any(deviceFailure::contains)) {
            run.deviceBoundSerial = null
        }
    }

    private data class PreparedCall(val deviceScoped: Boolean, val arguments: Map<String, Any?>)

    private suspend fun rejectMalformedArguments(run: AiRun, call: LlmToolCall): AiToolExecutionResult {
        run.emit(AiRunEvent.ToolRequested(call))
        val budgetDecision = run.toolCallBudget.tryConsume(call.name)
        if (!budgetDecision.allowed) {
            val result = AiToolExecutionResult.error(
                run.toolCallBudget.rejectionMessage(budgetDecision.isNotesWrite, budgetDecision.snapshot),
            ).withBudgetFooter(run.toolCallBudget.resultFooter(budgetDecision.snapshot))
            return complete(run, call, result, run.toolCallBudget.recordResult(result.returnedChars, result.truncated))
        }
        return completeCountedResult(run, call, AiToolExecutionResult.error("Tool arguments must be a JSON object."))
    }

    private suspend fun completeCountedResult(
        run: AiRun,
        call: LlmToolCall,
        result: AiToolExecutionResult,
    ): AiToolExecutionResult {
        // The warning depends only on consumed calls, so determine it before recording this
        // response's diagnostics; the returned-character metric includes the warning itself.
        val withFooter = result.withBudgetFooter(run.toolCallBudget.resultFooter(run.toolCallBudget.snapshot()))
        return complete(
            run,
            call,
            withFooter,
            run.toolCallBudget.recordResult(withFooter.returnedChars, withFooter.truncated),
        )
    }

    private suspend fun complete(
        run: AiRun,
        call: LlmToolCall,
        result: AiToolExecutionResult,
        snapshot: AiToolBudgetSnapshot,
    ): AiToolExecutionResult {
        run.emit(AiRunEvent.ToolCompleted(call, result.preview, result.truncated, result.evidence, snapshot, result.returnedChars))
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
        "clear_all_notes" -> "Clear all Notes sections and annotation blocks"
        else -> "Perform a confirmation-required action"
    }

    private companion object {
        const val DEFAULT_MAX_TOOL_RESULT_CHARS = 12_000
        val TAB_SCOPED_TOOL_NAMES = setOf(
            "build_sequence_diagram",
            "close_tab", "get_filter", "set_filter", "get_visible_lines", "get_line_context",
            "select_lines", "get_selection", "toggle_group", "expand_all", "collapse_all",
            "get_tags", "get_packages", "get_log_composition", "get_crash_sites", "get_issue_description",
            "get_annotation_sections", "get_annotation_blocks", "append_annotation_section", "set_annotation_section",
            "add_text_note", "add_log_note", "add_image_note", "update_note_block", "update_note_caption", "move_note_block",
            "delete_note_block", "clear_all_notes", "export_analysis", "export_filtered_log", "save_annotations",
            "load_annotations", "apply_filter_preset", "start_tailing", "stop_tailing",
            // search_similar_cases/get_case are deliberately NOT pinned — search_similar_cases is
            // tab-independent (it searches the whole notes corpus) and get_case takes a case `id`,
            // not a tabId, so pinning either would silently inject an unused/wrong argument.
            "set_case_metadata",
        )

        // Tab-pinned for in-app runs: the coordinator always injects the run's bound capture tab
        // as `tabId` for these, so the model never has to (and can't accidentally target another
        // tab). Kept in sync with ControlServer's DEVICE_CAPTURE_MCP_INSTRUCTIONS/handler set.
        val DEVICE_CAPTURE_TOOL_NAMES = setOf(
            "start_device_capture", "get_device_screen", "device_tap", "device_swipe", "device_key", "device_text",
            "stop_device_capture", "mark_device_issue", "export_capture_snapshot", "capture_device_screenshot",
            "device_launch_app", "list_device_apps", "device_open_url", "get_device_capture_status",
            "get_device_log_settings", "set_device_log_settings",
        )
        val json = Json { ignoreUnknownKeys = true }
    }
}

internal data class AiToolExecutionResult(
    val content: String,
    val preview: String,
    val truncated: Boolean,
    val evidence: List<AiEvidence> = emptyList(),
    // The un-stringified tool result (a Map, for every existing tool), kept alongside `content`
    // so a caller that needs the original shape — e.g. ControlServer.managedMcpServer's
    // get_video_frame special-case, which needs the real imageBase64/mimeType values rather than
    // their Map.toString() rendering — doesn't have to re-parse `content`. Null for `error()`
    // results, where there is no underlying tool value.
    val raw: Any? = null,
    val images: List<LlmImage> = emptyList(),
) {
    val returnedChars: Int get() = content.length

    fun withBudgetFooter(footer: String?): AiToolExecutionResult =
        if (footer == null) this else copy(content = content + footer, preview = preview + footer)

    companion object {
        fun from(value: Any?, maxChars: Int, evidence: List<AiEvidence>): AiToolExecutionResult {
            val valueMap = value as? Map<*, *>
            val imageBase64 = valueMap?.get("imageBase64") as? String
            val mimeType = valueMap?.get("mimeType") as? String ?: "image/png"
            val displayValue = if (imageBase64 != null) valueMap.minus("imageBase64") else value
            val rendered = displayValue?.toString() ?: "null"
            val images = imageBase64?.let { listOf(LlmImage(it, mimeType)) }.orEmpty()
            return if (rendered.length <= maxChars) {
                AiToolExecutionResult(rendered, rendered, truncated = false, evidence = evidence, raw = value, images = images)
            } else {
                val notice = "\n\n[Tool result truncated to $maxChars characters by Indagium.]"
                val bounded = rendered.take((maxChars - notice.length).coerceAtLeast(0)) + notice
                AiToolExecutionResult(bounded, bounded, truncated = true, evidence = evidence, raw = value, images = images)
            }
        }

        fun error(message: String): AiToolExecutionResult = AiToolExecutionResult(message, message, truncated = false)
    }
}
