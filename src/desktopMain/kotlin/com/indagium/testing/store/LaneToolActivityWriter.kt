package com.indagium.testing.store

import com.indagium.debug.Json
import com.indagium.testing.model.LaneToolCall
import java.io.File
import java.io.IOException

/** Keeps the full bounded lane activity stream even when the run.json live cache has rotated older calls. */
internal class LaneToolActivityWriter(private val file: File, private val maxBytes: Long = MAX_BYTES) {
    private val lock = Any()
    private var truncated = false

    fun append(phase: String, call: LaneToolCall) {
        val fields = mapOf(
            "phase" to phase,
            "id" to call.id,
            "caseId" to call.caseId,
            "stepId" to call.stepId,
            "iteration" to call.iteration,
            "attempt" to call.attempt,
            "toolName" to call.toolName,
            "argumentsPreview" to call.argumentsPreview,
            "resultPreview" to call.resultPreview,
            "status" to call.status.name,
            "startedAt" to call.startedAt,
            "durationMs" to call.durationMs,
        )
        appendLine(fields)
    }

    private fun appendLine(fields: Map<String, Any?>) = synchronized(lock) {
        try {
            file.parentFile?.mkdirs()
            val line = Json.encode(fields).replace('\n', ' ') + "\n"
            val current = file.length()
            val marker = truncationMarker()
            val markerBytes = marker.toByteArray(Charsets.UTF_8).size
            if (current + line.toByteArray(Charsets.UTF_8).size + markerBytes > maxBytes) {
                if (!truncated) {
                    truncated = true
                    if (current + markerBytes <= maxBytes) file.appendText(marker, Charsets.UTF_8)
                }
                return@synchronized
            }
            file.appendText(line, Charsets.UTF_8)
        } catch (_: IOException) {
            // Tool evidence must not interrupt a lane run.
        }
    }

    private fun truncationMarker(): String = Json.encode(
        mapOf("phase" to "truncated", "reason" to "Lane tool activity reached its $maxBytes byte storage limit."),
    ) + "\n"

    private companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
    }
}
