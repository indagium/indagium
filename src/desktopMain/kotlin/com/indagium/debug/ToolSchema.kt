package com.indagium.debug

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// JSON-Schema builders for the MCP tool catalogues (ControlServer.kt's MCP_TOOLS and
// TestSuiteToolCatalog.kt). They live in their own file so every catalogue depends on this one
// and none on another catalogue's file: ControlServerKt -> TestSuiteToolCatalogKt -> ToolSchemaKt
// has no cycle, whichever class the JVM initialises first.

// "array" means array-of-string (tag lists, tab ids, ...); "array<integer>" means array-of-number
// (line ids). Getting this right in the schema matters beyond cosmetics: a client model sees this
// JSON Schema at tools/list time, and a schema that (wrongly) says "array of strings" measurably
// nudges models toward quoting line ids as "73" instead of emitting bare 73 — observed with a
// local Gemma build mangling its own tool-call syntax on a quoted lineIds element.
// `enums` constrains a property to a fixed set of string values (for a scalar prop it goes on the
// property; for an "array"/"array<integer>" prop it goes on the array items). `descriptions` adds a
// per-property JSON-Schema description. Both are opt-in so untouched call sites are unaffected —
// but for enum-shaped fields (levels, mode, format) declaring them lets a compliant client avoid
// sending values the handler would otherwise reject (see setFilter's level/mode validation).
// Item schema for an "array of objects" property (sequences, messageRules, ...) — plug one of
// these into schema()'s `objectArrays` map to replace that property's default array-of-string
// shape with a real object schema, so a client sees the actual fields it must send instead of
// guessing (or, worse, quoting a whole object as a string).
// `nested` replaces an "array" item property's bare `{"type":"array"}` with a real array-of-object
// schema (a step's `checks`, a script's `params`, ...).
internal data class ObjectArrayItemSchema(
    val props: List<Pair<String, String>>,
    val required: List<String> = emptyList(),
    val enums: Map<String, List<String>> = emptyMap(),
    val descriptions: Map<String, String> = emptyMap(),
    val nested: Map<String, ObjectArrayItemSchema> = emptyMap(),
)

internal fun schema(
    vararg props: Pair<String, String>,
    required: List<String> = emptyList(),
    enums: Map<String, List<String>> = emptyMap(),
    descriptions: Map<String, String> = emptyMap(),
    objectArrays: Map<String, ObjectArrayItemSchema> = emptyMap(),
): ToolSchema =
    ToolSchema(
        properties = buildJsonObject {
            props.forEach { (name, type) ->
                put(
                    name,
                    buildJsonObject {
                        when (type) {
                            "array" -> objectArrays[name]?.let { arrayOfObjectItems(it) }
                                ?: arrayOfItems("string", enums[name])
                            "array<integer>" -> arrayOfItems("integer")
                            else -> {
                                put("type", type)
                                enums[name]?.let { putEnum(it) }
                            }
                        }
                        descriptions[name]?.let { put("description", it) }
                    },
                )
            }
        },
        required = required.ifEmpty { null },
    )

private fun JsonObjectBuilder.arrayOfItems(itemType: String, itemEnum: List<String>? = null) {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", itemType)
        itemEnum?.let { putEnum(it) }
    })
}

private fun JsonObjectBuilder.arrayOfObjectItems(item: ObjectArrayItemSchema) {
    put("type", "array")
    put(
        "items",
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    item.props.forEach { (name, type) ->
                        put(
                            name,
                            buildJsonObject {
                                val nestedItem = item.nested[name]
                                if (type == "array" && nestedItem != null) arrayOfObjectItems(nestedItem) else put("type", type)
                                item.enums[name]?.let { putEnum(it) }
                                item.descriptions[name]?.let { put("description", it) }
                            },
                        )
                    }
                },
            )
            if (item.required.isNotEmpty()) put("required", buildJsonArray { item.required.forEach { add(it) } })
        },
    )
}

private fun JsonObjectBuilder.putEnum(values: List<String>) {
    put("enum", buildJsonArray { values.forEach { add(it) } })
}
