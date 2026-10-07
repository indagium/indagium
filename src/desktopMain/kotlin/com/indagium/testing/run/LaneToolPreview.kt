package com.indagium.testing.run

import com.indagium.debug.Json

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

private fun sanitize(value: Any?, depth: Int): Any? {
    if (depth >= MAX_PREVIEW_DEPTH) return "[nested value omitted]"
    return when (value) {
        null, is Boolean, is Number -> value
        is String -> value.take(MAX_PREVIEW_STRING_CHARS)
        is ByteArray -> "[binary evidence omitted: ${value.size} bytes]"
        is Map<*, *> -> value.entries.take(MAX_PREVIEW_MAP_ENTRIES).associate { (key, child) ->
            val name = key?.toString()?.take(MAX_PREVIEW_KEY_CHARS) ?: "unknown"
            val normalized = name.lowercase().replace(Regex("[^a-z0-9]"), "")
            name to when {
                normalized.contains("base64") || normalized in setOf("image", "png", "jpegbytes", "imagebytes") -> "[image payload omitted]"
                listOf("password", "token", "secret", "apikey").any(normalized::contains) -> "[redacted]"
                else -> sanitize(child, depth + 1)
            }
        }
        is Iterable<*> -> value.take(MAX_PREVIEW_MAP_ENTRIES).map { sanitize(it, depth + 1) }
        is Array<*> -> value.take(MAX_PREVIEW_MAP_ENTRIES).map { sanitize(it, depth + 1) }
        else -> value.toString().take(MAX_PREVIEW_STRING_CHARS)
    }
}
