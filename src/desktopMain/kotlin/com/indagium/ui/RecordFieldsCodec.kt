package com.indagium.ui

import com.indagium.model.AnnBlock
import com.indagium.utils.RECORD_FIELDS_MAX_JSON_CHARS

// The chosen fields in the "R" annotation block token (AutosaveCodec.kt's annBlockToken).

/**
 * The chosen fields of a fields reference: each one `<path segments, base64, comma separated>:<json, base64>`, the fields
 * separated by ";". Every part is base64, so none of the separators can occur inside one.
 */
internal fun List<AnnBlock.RecordFieldRef>.recordFieldsToken(): String =
    joinToString(";") { field -> field.path.joinToString(",") { it.b64() } + ":" + field.json.b64() }

/** Reads [recordFieldsToken]; null when nothing readable is in it, or when it holds more JSON than a reference may freeze. */
internal fun String.recordFieldsFromToken(): List<AnnBlock.RecordFieldRef>? {
    val fields = split(";").mapNotNull { part ->
        val separator = part.indexOf(':')
        if (separator < 0) return@mapNotNull null
        runCatching {
            val path = part.substring(0, separator).split(",").map { it.unb64() }
            AnnBlock.RecordFieldRef(path, part.substring(separator + 1).unb64())
        }.getOrNull()
    }
    return fields.takeIf { it.isNotEmpty() && it.sumOf { field -> field.json.length } <= RECORD_FIELDS_MAX_JSON_CHARS }
}
