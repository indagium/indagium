package com.indagium.debug

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs
import kotlin.math.floor

// Glue between the debug transport's plain Map/List/String/Number values and kotlinx JsonElement, plus
// a strict argument reader for the test-suite tools. The codec in testing/store encodes and decodes
// JsonElements, so routing MCP input and output through these adapters gives the tools exactly the
// same JSON shape as the on-disk suite files.

/** A problem with a tool argument; turned into `{ "error": message }` by the handler wrapper, never thrown to a transport. */
internal class ToolArgException(message: String) : IllegalArgumentException(message)

internal fun toolArgError(message: String): Nothing = throw ToolArgException(message)

/** Largest magnitude at which a Double still holds a whole number exactly. */
private const val MAX_EXACT_DOUBLE_INTEGER = 9_007_199_254_740_992.0

internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Int, is Long, is Short, is Byte -> JsonPrimitive(this as Number)
    is Number -> toDouble().let { d ->
        if (d.isFinite() && d == floor(d) && abs(d) < MAX_EXACT_DOUBLE_INTEGER) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
    }
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

internal fun Map<String, Any?>.toJsonObject(): JsonObject = toJsonElement() as JsonObject

/** The inverse of [toJsonElement]: String/Boolean/Int/Long/Double, List and an insertion-ordered Map. */
internal fun JsonElement.toPlain(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> entries.associateTo(LinkedHashMap<String, Any?>()) { (k, v) -> k to v.toPlain() }
    is JsonArray -> map { it.toPlain() }
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        longOrNull != null -> longOrNull!!.let { if (it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) it.toInt() else it }
        doubleOrNull != null -> doubleOrNull
        else -> content
    }
}

@Suppress("UNCHECKED_CAST")
internal fun JsonObject.toPlainMap(): MutableMap<String, Any?> = toPlain() as MutableMap<String, Any?>

/**
 * Reads one tool call's arguments. A missing or null argument is "not supplied"; an argument of the
 * wrong type is a [ToolArgException] naming it, rather than being silently ignored.
 */
internal class ToolArgs(val map: Map<String, Any?>) {
    /** True when [key] was sent (a JSON null counts: it is how a caller clears an optional value). */
    fun hasKey(key: String): Boolean = map.containsKey(key)

    fun has(key: String): Boolean = map[key] != null

    fun string(key: String): String? = when (val v = map[key]) {
        null -> null
        is String -> v
        else -> toolArgError("$key must be a string.")
    }

    fun requiredString(key: String): String = string(key)?.takeIf { it.isNotBlank() } ?: toolArgError("$key is required.")

    fun long(key: String): Long? = when (val v = map[key]) {
        null -> null
        is Number -> v.toWholeLong() ?: toolArgError("$key must be a whole number.")
        is String -> v.trim().toLongOrNull() ?: toolArgError("$key must be a whole number.")
        else -> toolArgError("$key must be a whole number.")
    }

    fun int(key: String): Int? = long(key)?.let {
        if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else toolArgError("$key is out of range.")
    }

    fun requiredInt(key: String): Int = int(key) ?: toolArgError("$key is required.")

    fun bool(key: String): Boolean? = when (val v = map[key]) {
        null -> null
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull() ?: toolArgError("$key must be true or false.")
        else -> toolArgError("$key must be true or false.")
    }

    fun list(key: String): List<Any?>? = when (val v = map[key]) {
        null -> null
        is List<*> -> v
        else -> toolArgError("$key must be an array.")
    }

    fun strings(key: String): List<String>? = list(key)?.mapIndexed { i, v -> v as? String ?: toolArgError("$key[$i] must be a string.") }

    /** An array whose every element is an object. */
    fun objects(key: String): List<Map<String, Any?>>? = list(key)?.mapIndexed { i, v -> v.asObject("$key[$i]") }

    /** A case-insensitive enum value, or null when [key] was not supplied. */
    fun <E : Enum<E>> enum(key: String, entries: List<E>): E? {
        val raw = string(key) ?: return null
        return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: toolArgError("$key must be one of ${entries.joinToString(", ") { it.name }}.")
    }
}

private fun Number.toWholeLong(): Long? {
    val d = toDouble()
    return if (d.isFinite() && d == floor(d) && abs(d) < MAX_EXACT_DOUBLE_INTEGER) toLong() else null
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.asObject(label: String): Map<String, Any?> =
    (this as? Map<*, *>)?.let { it as Map<String, Any?> } ?: toolArgError("$label must be an object.")

/**
 * Checks the JSON types of the named fields of one nested object (a check, an example, a hook, ...).
 * The codec reads a mistyped field as its default, which would hide a caller's mistake.
 */
internal fun requireFieldTypes(
    item: Map<String, Any?>,
    label: String,
    strings: Set<String> = emptySet(),
    numbers: Set<String> = emptySet(),
    booleans: Set<String> = emptySet(),
    objects: Set<String> = emptySet(),
) {
    item.forEach { (key, value) ->
        if (value == null) return@forEach
        val valid = when (key) {
            in strings -> value is String
            in numbers -> value is Number && value.toWholeLong() != null
            in booleans -> value is Boolean
            in objects -> value is Map<*, *>
            else -> true
        }
        if (!valid) toolArgError("$label.$key has the wrong type.")
    }
}
