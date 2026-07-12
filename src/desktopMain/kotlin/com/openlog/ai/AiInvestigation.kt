package com.openlog.ai

import java.util.UUID

/**
 * Context deliberately pinned to an AI request.  It is independent from the currently visible
 * tab by the time the model starts responding, so a delayed request cannot silently investigate
 * another tab after the user switches tabs.
 */
internal data class AiInvestigationContext(
    val tabId: String,
    val lineId: Int? = null,
    val action: AiQuickAction? = null,
)

/** Pre-built investigations exposed by the in-app panel and log-row context menu. */
internal enum class AiQuickAction(val label: String, val prompt: String, val requiresLine: Boolean) {
    LOG_LINE(
        label = "Log line",
        prompt = "Analyze this log line. Explain what it means, related evidence, and the next useful checks.",
        requiresLine = true,
    ),
    SELECTED_ERROR(
        label = "Selected error",
        prompt = "Analyze the selected error. Explain what happened, likely causes, evidence, and the next useful checks.",
        requiresLine = true,
    ),
    ROOT_CAUSE(
        label = "Root cause",
        prompt = "Investigate the selected line and determine the most likely root cause. Build a timeline from real log evidence before concluding.",
        requiresLine = true,
    ),
    FILTERED_RESULT(
        label = "Filtered result",
        prompt = "Analyze the current filtered result. Summarize the important evidence, patterns, and recommended next checks.",
        requiresLine = false,
    ),
    TIMELINE(
        label = "Timeline",
        prompt = "Build a timeline around the selected line. Identify preceding events that plausibly led to it and cite only tool-returned evidence.",
        requiresLine = true,
    ),
    MAPPED_SOURCE(
        label = "Mapped source",
        prompt = "Resolve the selected log line to source code and explain the relevant mapped method using tool-returned source evidence.",
        requiresLine = true,
    ),
}

/** One session-only request queued by a context action until its owning tab's sidebar composes. */
internal data class AiPromptRequest(
    val id: String = UUID.randomUUID().toString(),
    val context: AiInvestigationContext,
    val prompt: String,
)

/**
 * Navigation data is derived exclusively from a completed gateway result.  Model Markdown is
 * intentionally never parsed for line numbers, paths, or annotation ids.
 */
internal sealed interface AiEvidence {
    data class LogRows(val tabId: String, val lineIds: List<Int>) : AiEvidence

    data class Source(
        val filePath: String,
        val methodName: String,
        val methodStartLine: Int,
        val methodEndLine: Int,
        val callLine: Int,
        val tag: String?,
        val confidence: Double,
        val stale: Boolean,
    ) : AiEvidence

    data class Note(val tabId: String, val blockId: String) : AiEvidence
}

/** Extracts only concrete IDs and paths returned by known gateway operations. */
internal object AiEvidenceExtractor {
    fun from(toolName: String, result: Any?): List<AiEvidence> {
        val map = result as? Map<*, *> ?: return emptyList()
        if (map["error"] != null) return emptyList()
        return when (toolName) {
            "get_line_context" -> logRows(map, "lines")
            "get_visible_lines" -> logRows(map, "items")
            "get_crash_sites" -> crashRows(map)
            "select_lines" -> selectedRows(map)
            "resolve_log_source" -> sourceMatches(map)
            "add_text_note", "add_log_note", "update_note_block", "move_note_block" -> note(map)
            else -> emptyList()
        }
    }

    private fun logRows(map: Map<*, *>, rowsKey: String): List<AiEvidence> {
        val tabId = map.string("tabId") ?: return emptyList()
        val lineIds = ((map[rowsKey] as? List<*>) ?: emptyList<Any?>())
            .mapNotNull { (it as? Map<*, *>)?.int("id") }
            .distinct()
        return lineIds.takeIf { it.isNotEmpty() }?.let { listOf(AiEvidence.LogRows(tabId, it)) } ?: emptyList()
    }

    private fun crashRows(map: Map<*, *>): List<AiEvidence> {
        val tabId = map.string("tabId") ?: return emptyList()
        val lineIds = ((map["sites"] as? List<*>) ?: emptyList<Any?>())
            .mapNotNull { (it as? Map<*, *>)?.int("logId") }
            .distinct()
        return lineIds.takeIf { it.isNotEmpty() }?.let { listOf(AiEvidence.LogRows(tabId, it)) } ?: emptyList()
    }

    private fun selectedRows(map: Map<*, *>): List<AiEvidence> {
        val tabId = map.string("tabId") ?: return emptyList()
        val lineIds = ((map["selected"] as? List<*>) ?: emptyList<Any?>())
            .mapNotNull { it.toIntOrNull() }
            .distinct()
        return lineIds.takeIf { it.isNotEmpty() }?.let { listOf(AiEvidence.LogRows(tabId, it)) } ?: emptyList()
    }

    private fun sourceMatches(map: Map<*, *>): List<AiEvidence> = ((map["matches"] as? List<*>) ?: emptyList<Any?>())
        .mapNotNull { raw ->
            val match = raw as? Map<*, *> ?: return@mapNotNull null
            val path = match.string("filePath") ?: return@mapNotNull null
            val method = match.string("methodName") ?: return@mapNotNull null
            val start = match.int("methodStartLine") ?: return@mapNotNull null
            val end = match.int("methodEndLine") ?: return@mapNotNull null
            val call = match.int("callLine") ?: return@mapNotNull null
            AiEvidence.Source(
                filePath = path,
                methodName = method,
                methodStartLine = start,
                methodEndLine = end,
                callLine = call,
                tag = match.string("tag"),
                confidence = match.double("confidence") ?: 0.0,
                stale = match["stale"] as? Boolean ?: false,
            )
        }

    private fun note(map: Map<*, *>): List<AiEvidence> {
        val tabId = map.string("tabId") ?: return emptyList()
        val blockId = map.string("blockId") ?: return emptyList()
        return listOf(AiEvidence.Note(tabId, blockId))
    }

    private fun Map<*, *>.string(key: String): String? = this[key] as? String

    private fun Map<*, *>.int(key: String): Int? = this[key].toIntOrNull()

    private fun Map<*, *>.double(key: String): Double? = when (val value = this[key]) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    private fun Any?.toIntOrNull(): Int? = when (this) {
        is Number -> toInt()
        is String -> toIntOrNull()
        else -> null
    }
}
