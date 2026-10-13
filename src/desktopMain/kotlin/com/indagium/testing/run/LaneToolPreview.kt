package com.indagium.testing.run

import com.indagium.ai.redactDiagnosticSecrets
import com.indagium.debug.Json
import com.indagium.testing.model.TestRun
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_PREVIEW_CHARS = 2_000
private const val MAX_PREVIEW_DEPTH = 4
private const val MAX_PREVIEW_STRING_CHARS = 800
private const val MAX_PREVIEW_MAP_ENTRIES = 50
private const val MAX_PREVIEW_KEY_CHARS = 100

/** Stable, bounded text for tool activity. Image bytes and credential-shaped fields never enter run history. */
internal fun laneToolPreview(value: Any?): String {
    val encoded = runCatching { Json.encode(sanitize(value, 0)) }.getOrElse { value?.javaClass?.simpleName ?: "null" }
    return encoded.take(MAX_PREVIEW_CHARS)
}

/** Redacts text from a preview already stored by an older run, including clipped JSON that no longer parses. */
internal fun sanitizeHistoricalPreview(text: String): String {
    val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) }.getOrNull()
        ?: return redactDiagnosticSecrets(text)
    return encodeSanitizedJson(parsed)
}

/** Redacts all text and credential-keyed values in a stored JSON document without changing its JSON shape. */
internal fun sanitizeHistoricalJson(text: String): String {
    val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) }.getOrNull()
        ?: return redactDiagnosticSecrets(text)
    return encodeSanitizedJson(parsed)
}

/** Sanitizes every row of a stored JSONL activity stream. Invalid legacy lines remain readable and redacted. */
internal fun sanitizeHistoricalActivityJsonl(text: String): String = text.split('\n').joinToString("\n") { line ->
    if (line.isBlank()) {
        line
    } else {
        val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(line) }.getOrNull()
        if (parsed == null) redactDiagnosticSecrets(line) else encodeSanitizedJson(parsed)
    }
}

/** Makes the cached preview text in a historical run safe for JSON and Markdown exports. */
internal fun TestRun.withRedactedToolPreviews(): TestRun = copy(
    lanes = lanes.map { lane ->
        lane.copy(
            toolCalls = lane.toolCalls.map { call ->
                call.copy(
                    argumentsPreview = sanitizeHistoricalPreview(call.argumentsPreview),
                    resultPreview = sanitizeHistoricalPreview(call.resultPreview),
                )
            },
        )
    },
)

private fun encodeSanitizedJson(value: JsonElement): String = kotlinx.serialization.json.Json.encodeToString(
    JsonElement.serializer(), sanitizeJson(value),
)

private fun sanitizeJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { (key, child) ->
        if (isCredentialKey(key)) JsonPrimitive("[redacted]") else sanitizeJson(child)
    })
    is JsonArray -> JsonArray(value.map(::sanitizeJson))
    is JsonPrimitive -> if (value.isString) JsonPrimitive(redactDiagnosticSecrets(value.content)) else value
    else -> value
}

private fun isCredentialKey(name: String): Boolean {
    val normalized = name.lowercase().replace(Regex("[^a-z0-9]"), "")
    return listOf("password", "token", "secret", "apikey", "authorization").any(normalized::contains)
}

private fun sanitize(value: Any?, depth: Int): Any? {
    if (depth >= MAX_PREVIEW_DEPTH) return "[nested value omitted]"
    return when (value) {
        null, is Boolean, is Number -> value
        is String -> redactDiagnosticSecrets(value).take(MAX_PREVIEW_STRING_CHARS)
        is ByteArray -> "[binary evidence omitted: ${value.size} bytes]"
        is Map<*, *> -> value.entries.take(MAX_PREVIEW_MAP_ENTRIES).associate { (key, child) ->
            val name = key?.toString()?.take(MAX_PREVIEW_KEY_CHARS) ?: "unknown"
            val normalized = name.lowercase().replace(Regex("[^a-z0-9]"), "")
            name to when {
                normalized.contains("base64") || normalized in setOf("image", "png", "jpegbytes", "imagebytes") -> "[image payload omitted]"
                isCredentialKey(name) -> "[redacted]"
                else -> sanitize(child, depth + 1)
            }
        }
        is Iterable<*> -> value.take(MAX_PREVIEW_MAP_ENTRIES).map { sanitize(it, depth + 1) }
        is Array<*> -> value.take(MAX_PREVIEW_MAP_ENTRIES).map { sanitize(it, depth + 1) }
        else -> redactDiagnosticSecrets(value.toString()).take(MAX_PREVIEW_STRING_CHARS)
    }
}
