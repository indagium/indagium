package com.indagium.ui

import androidx.compose.ui.graphics.Color
import com.indagium.model.ErrorHighlightStyle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** JSON-only app settings representation; tab tokens use the append-only positional representation below. */
internal fun ErrorHighlightStyle.toJson(): JsonObject = buildJsonObject {
    put("enabled", enabled)
    put("color", color.value.toString())
    put("backgroundEnabled", backgroundEnabled)
    textColor?.let { put("textColor", it.value.toString()) }
    fontFamily?.let { put("fontFamily", it) }
    bold?.let { put("bold", it) }
    italic?.let { put("italic", it) }
}

internal fun JsonObject.errorHighlightStyleFromJson(key: String): ErrorHighlightStyle? = runCatching {
    val value = this[key] as? JsonObject ?: return@runCatching null
    val color = value.errorStyleString("color")?.toULongOrNull()?.let(::Color) ?: return@runCatching null
    ErrorHighlightStyle(
        enabled = value.errorStyleBoolean("enabled") ?: true,
        color = color,
        backgroundEnabled = value.errorStyleBoolean("backgroundEnabled") ?: true,
        textColor = value.errorStyleString("textColor")?.toULongOrNull()?.let(::Color),
        fontFamily = value.errorStyleString("fontFamily")?.takeIf { it.isNotBlank() },
        bold = value.errorStyleBoolean("bold"),
        italic = value.errorStyleBoolean("italic"),
    )
}.getOrNull()

internal fun ErrorHighlightStyle.errorHighlightStyleToken(): String = tokenFields(
    enabled.toString(),
    color.value.toString(),
    backgroundEnabled.toString(),
    textColor?.value?.toString().orEmpty(),
    fontFamily.orEmpty(),
    bold?.toString().orEmpty(),
    italic?.toString().orEmpty(),
)

internal fun String.errorHighlightStyleFromToken(): ErrorHighlightStyle? = runCatching {
    val parts = tokenFields()
    if (parts.size < 3) return@runCatching null
    ErrorHighlightStyle(
        enabled = parts[0].toBooleanStrict(),
        color = Color(parts[1].toULong()),
        backgroundEnabled = parts[2].toBooleanStrict(),
        textColor = parts.getOrNull(3)?.takeIf { it.isNotBlank() }?.toULongOrNull()?.let(::Color),
        fontFamily = parts.getOrNull(4)?.takeIf { it.isNotBlank() },
        bold = parts.getOrNull(5)?.toBooleanStrictOrNull(),
        italic = parts.getOrNull(6)?.toBooleanStrictOrNull(),
    )
}.getOrNull()

private fun JsonObject.errorStyleString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.errorStyleBoolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
