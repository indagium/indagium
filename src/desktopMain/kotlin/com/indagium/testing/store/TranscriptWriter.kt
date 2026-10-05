package com.indagium.testing.store

import com.indagium.ai.redactDiagnosticSecrets
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

// Appends one JSON object per line to a lane's transcript.jsonl. EVERY string value is passed through the shared secret
// redaction (bearer tokens, `token=...` assignments) before it is written, so a transcript can be kept and shared
// without carrying a credential a tool result happened to echo. A failed write is swallowed (the transcript is
// evidence, not the run) and reported through [lastError].

class TranscriptWriter(val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()

    @Volatile
    var lastError: String? = null
        private set

    /** Bytes written so far; the offset the next line starts at. */
    val sizeBytes: Long get() = if (file.isFile) file.length() else 0L

    fun append(kind: String, fields: Map<String, Any?> = emptyMap()) {
        val line = buildJsonObject {
            put("t", clock())
            put("kind", kind)
            fields.forEach { (key, value) -> put(key, redacted(value)) }
        }.toString() + "\n"
        synchronized(lock) {
            try {
                file.parentFile?.mkdirs()
                file.appendBytes(line.toByteArray(Charsets.UTF_8))
            } catch (failure: IOException) {
                lastError = "Could not write the transcript: ${failure.message}"
            }
        }
    }

    private fun redacted(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(redactDiagnosticSecrets(value))
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(redactDiagnosticSecrets(value.toString()))
    }
}
