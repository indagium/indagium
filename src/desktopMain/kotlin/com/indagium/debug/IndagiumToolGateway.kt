package com.indagium.debug

import com.indagium.ai.LlmToolDefinition
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Transport-neutral entry point for Indagium's tool contract.
 *
 * The HTTP/MCP server and the future in-app agent both call this class instead of maintaining
 * their own lists of operations.  [executor] deliberately receives only plain Kotlin values:
 * protocol adapters are responsible for converting their request objects before this boundary.
 *
 * A gateway normally holds [handlers] only. A tool that has to wait (the test-suite lane tools:
 * `wait_for_log`, `finish_step`, scripts) is registered as a suspending handler in [suspendHandlers]
 * instead, and is reached through [executeSuspending] so no Ktor or Default thread blocks on it.
 * [extraConfirmationRequired] adds tool names to the confirmation policy (used for per-lane tools whose
 * names are only known at run time) and [confirmationDescriptions] gives such a tool its confirmation text.
 */
internal class IndagiumToolGateway(
    private val catalog: List<IndagiumToolDescriptor>,
    private val handlers: Map<String, (arguments: Map<String, Any?>) -> Any?>,
    private val suspendHandlers: Map<String, suspend (arguments: Map<String, Any?>) -> Any?> = emptyMap(),
    private val extraConfirmationRequired: Set<String> = emptySet(),
    private val confirmationDescriptions: Map<String, String> = emptyMap(),
    private val externalServiceCall: (name: String, arguments: Map<String, Any?>) -> Boolean = ::sendsToExternalService,
) {
    init {
        require(catalog.map { it.name }.distinct().size == catalog.size) { "tool names must be unique" }
        require(handlers.keys.intersect(suspendHandlers.keys).isEmpty()) { "a tool has either a handler or a suspending handler" }
        require(catalog.map { it.name }.toSet() == handlers.keys + suspendHandlers.keys) { "catalog and handlers must stay in parity" }
    }

    val tools: List<IndagiumToolDescriptor> get() = catalog

    /**
     * Synchronous entry point for callers that cannot suspend. A tool with a suspending handler is run to
     * completion on the calling thread here, so production code on a request or UI thread must use
     * [executeSuspending] instead.
     */
    fun execute(name: String, arguments: Map<String, Any?>): Any? {
        handlers[name]?.let { handler -> return handler.invoke(arguments) ?: unknownOperation(name) }
        val suspending = suspendHandlers[name] ?: return unknownOperation(name)
        return runBlocking { suspending.invoke(arguments) } ?: unknownOperation(name)
    }

    /** Runs a synchronous handler directly and a suspending handler as a suspension; same results as [execute]. */
    suspend fun executeSuspending(name: String, arguments: Map<String, Any?>): Any? {
        handlers[name]?.let { handler -> return handler.invoke(arguments) ?: unknownOperation(name) }
        val suspending = suspendHandlers[name] ?: return unknownOperation(name)
        return suspending.invoke(arguments) ?: unknownOperation(name)
    }

    private fun unknownOperation(name: String): Map<String, Any?> = mapOf("error" to "unknown operation: $name")

    /** The text shown on this tool's confirmation card when it supplied one, else null (the coordinator's own wording applies). */
    fun confirmationDescription(name: String): String? = confirmationDescriptions[name]

    /** Task 04 uses this classification before it invokes a mutation. */
    fun actionPolicy(name: String): IndagiumToolActionPolicy? =
        catalog.firstOrNull { it.name == name }?.let { policyFor(it.name, extraConfirmationRequired) }

    /**
     * The policy of one CALL: [actionPolicy] of the tool, raised to CONFIRMATION_REQUIRED when this call's arguments make an
     * otherwise automatic tool send data to an external service (create_issue_from_step with destination tracker).
     */
    fun actionPolicy(name: String, arguments: Map<String, Any?>): IndagiumToolActionPolicy? =
        actionPolicy(name)?.let { base ->
            if (base == IndagiumToolActionPolicy.AUTOMATIC && externalServiceCall(name, arguments)) IndagiumToolActionPolicy.CONFIRMATION_REQUIRED else base
        }

    /**
     * OpenAI-compatible function definitions generated from the exact MCP schema.  This keeps
     * a model's function-call contract in lockstep with tools/list without a second hand-written
     * catalogue.
     */
    fun openAiFunctions(): List<LlmToolDefinition> = catalog.map { tool ->
        LlmToolDefinition(
            name = tool.name,
            description = tool.description,
            parameters = tool.schema.toOpenAiParameters(),
        )
    }
}

internal enum class IndagiumToolActionPolicy { AUTOMATIC, CONFIRMATION_REQUIRED }

private val CONFIRMATION_REQUIRED_TOOLS = setOf(
    "open_log_file", "split_log_file", "close_tab", "export_analysis",
    "export_filtered_log", "save_annotations", "load_annotations", "merge_tabs", "start_tailing", "stop_tailing",
    "clear_all_notes",
    // reindex_sources kicks off a heavy background disk scan; save_filter_preset persists a preset
    // (and writes the filter backup to disk). set_highlighters / add_manual_collapse / add_sequence
    // are view-only mutations, left AUTOMATIC to match set_filter / toggle_group.
    "reindex_sources", "save_filter_preset",
    // Test-suite authoring: deletes and file import/export ask first; set_edition is a development
    // switch that changes what the whole feature allows, so an in-app AI run must never flip it unasked.
    "delete_test_suite", "delete_test_case", "delete_test_script", "import_test_suite", "export_test_suite",
    "import_test_script", "export_test_script", "apply_test_step_draft", "set_edition",
    // try_test_script runs a user-authored shell command on the computer or the device.
    "try_test_script",
    // Starting a run (or re-running a step) drives devices and may run scripts; cancelling one stops work in progress;
    // apply_step_fix rewrites a step of the user's library.
    "run_test_suite", "cancel_test_run", "rerun_test_step", "rerun_failed_test_cases", "export_test_run_report",
    "collect_android_bugreport", "export_issue_step_clip", "apply_step_fix", "start_test_recording", "apply_test_recording",
    // delete_issue removes a stored issue and its copied evidence for good. send_issue_to_tracker (and create_issue_from_step with
    // destination tracker, see sendsToExternalService) hands the issue text and evidence to an AI agent and an external tracker.
    "delete_issue", "send_issue_to_tracker",
)

/** Whether a call of the otherwise automatic [name] with [arguments] sends the user's data to an external service. */
internal fun sendsToExternalService(name: String, arguments: Map<String, Any?>): Boolean =
    name == "create_issue_from_step" && (arguments["destination"] as? String)?.trim().equals("tracker", ignoreCase = true)

internal fun policyFor(name: String, extraConfirmationRequired: Set<String>): IndagiumToolActionPolicy =
    if (name in CONFIRMATION_REQUIRED_TOOLS || name in extraConfirmationRequired) IndagiumToolActionPolicy.CONFIRMATION_REQUIRED
    else IndagiumToolActionPolicy.AUTOMATIC

/** A single operation descriptor shared by MCP, REST routing, and OpenAI-compatible providers. */
internal data class IndagiumToolDescriptor(
    val name: String,
    val description: String,
    val schema: ToolSchema,
)

/**
 * Serializing the SDK schema preserves all of its JSON Schema details (including enum item types)
 * rather than attempting to reconstruct them from a reduced local model.
 */
private fun ToolSchema.toOpenAiParameters(): JsonObject {
    val encoded = Json.encodeToJsonElement(ToolSchema.serializer(), this).jsonObject
    return buildJsonObject {
        put("type", "object")
        encoded.forEach { (key, value) -> put(key, value) }
    }
}
