package com.openlog.debug

import com.openlog.ai.LlmToolDefinition
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Transport-neutral entry point for openLog's tool contract.
 *
 * The HTTP/MCP server and the future in-app agent both call this class instead of maintaining
 * their own lists of operations.  [executor] deliberately receives only plain Kotlin values:
 * protocol adapters are responsible for converting their request objects before this boundary.
 */
internal class OpenLogToolGateway(
    private val catalog: List<OpenLogToolDescriptor>,
    private val handlers: Map<String, (arguments: Map<String, Any?>) -> Any?>,
) {
    init {
        require(catalog.map { it.name }.distinct().size == catalog.size) { "tool names must be unique" }
        require(catalog.map { it.name }.toSet() == handlers.keys) { "catalog and handlers must stay in parity" }
    }

    val tools: List<OpenLogToolDescriptor> get() = catalog

    fun execute(name: String, arguments: Map<String, Any?>): Any? {
        return handlers[name]?.invoke(arguments) ?: mapOf("error" to "unknown operation: $name")
    }

    /** Task 04 uses this classification before it invokes a mutation. */
    fun actionPolicy(name: String): OpenLogToolActionPolicy? =
        catalog.firstOrNull { it.name == name }?.let { policyFor(it.name) }

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

internal enum class OpenLogToolActionPolicy { AUTOMATIC, CONFIRMATION_REQUIRED }

private val CONFIRMATION_REQUIRED_TOOLS = setOf(
    "open_log_file", "split_log_file", "close_tab", "export_analysis",
    "export_filtered_log", "save_annotations", "load_annotations", "merge_tabs", "start_tailing", "stop_tailing",
    // reindex_sources kicks off a heavy background disk scan; save_filter_preset persists a preset
    // (and writes the filter backup to disk). set_highlighters / add_manual_collapse / add_sequence
    // are view-only mutations, left AUTOMATIC to match set_filter / toggle_group.
    "reindex_sources", "save_filter_preset",
)

private fun policyFor(name: String): OpenLogToolActionPolicy =
    if (name in CONFIRMATION_REQUIRED_TOOLS) OpenLogToolActionPolicy.CONFIRMATION_REQUIRED
    else OpenLogToolActionPolicy.AUTOMATIC

/** A single operation descriptor shared by MCP, REST routing, and OpenAI-compatible providers. */
internal data class OpenLogToolDescriptor(
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
