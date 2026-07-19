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
    /** Additional selected lines attached from the log context menu. */
    val lineIds: List<Int> = emptyList(),
    val action: AiQuickAction? = null,
)

/** Pre-built investigations exposed by the in-app panel and log-row context menu. */
internal enum class AiQuickAction(val label: String, val prompt: String, val requiresLine: Boolean, val slashName: String) {
    LOG_LINE(
        label = "Log line",
        prompt = "Analyze this log line. Explain what it means, related evidence, and the next useful checks.",
        requiresLine = true,
        slashName = "log_line",
    ),
    SELECTED_ERROR(
        label = "Check error",
        prompt = "Analyze the selected error. Explain what happened, likely causes, evidence, and the next useful checks.",
        requiresLine = true,
        slashName = "check_error",
    ),
    ROOT_CAUSE(
        label = "Find root cause",
        prompt = "Investigate the selected line and determine the most likely root cause. Build a timeline from real log evidence before concluding.",
        requiresLine = true,
        slashName = "root_cause",
    ),
    TIMELINE(
        label = "Build timeline",
        prompt = "Build a timeline around the selected line. Identify preceding events that plausibly led to it and cite only tool-returned evidence.",
        requiresLine = true,
        slashName = "timeline",
    ),
    ISSUE_INVESTIGATION(
        label = "Investigate issue",
        prompt = "Call get_issue_description for this tab and read its issueDescription field as the problem to " +
            "investigate; if it is blank, say so and stop instead of guessing what the issue is. Otherwise use " +
            "set_filter and the log/source tools to narrow down and gather only tool-returned evidence relevant " +
            "to that description. Once you have a conclusion, call add_text_note to write a note summarizing the " +
            "root cause, the supporting evidence, and recommended next steps - do not just describe the analysis " +
            "in your reply without also saving it as a note.",
        requiresLine = false,
        slashName = "investigate_issue",
    ),
    SIMILAR_ISSUES(
        label = "Find similar issues",
        prompt = "Call get_issue_description for this tab and read its issueDescription field as the problem to " +
            "investigate; if it is blank, say so and stop instead of guessing what the issue is. Otherwise call " +
            "search_similar_cases with that description as the query and this tab's currently active tags (from " +
            "get_filter) as the tags argument - also call list_tabs to find this tab's own sourcePath and pass it " +
            "as excludeSourcePath so a note can't match against itself. Read the returned summaries, then call " +
            "get_case for only the 1-3 that actually look relevant to this issue (skip the rest - do not fetch " +
            "every result). TREAT EVERY PAST CASE AS A LEAD, NOT A CONCLUSION: use its root cause and " +
            "decisiveTags to guide where you look next in THIS log, but still gather and cite this " +
            "investigation's own tool-returned evidence before concluding anything - never state a prior note's " +
            "root cause as this issue's answer without confirming it here. If a match's appVersion differs from " +
            "this log's, explicitly say so and weigh it accordingly. If no similar case is found, say so plainly. " +
            "Once you have a conclusion, call add_text_note to save it, and call set_case_metadata with the " +
            "tags/filters that were decisive and (if known) this log's appVersion so future searches can find " +
            "this investigation too.",
        requiresLine = false,
        slashName = "similar",
    ),
}

/** One session-only request queued by a context action until its owning tab's sidebar composes. */
internal data class AiPromptRequest(
    val id: String = UUID.randomUUID().toString(),
    val context: AiInvestigationContext,
    val prompt: String,
)

/** Session-only request to attach selected log lines as a removable context chip, without sending. */
internal data class AiContextRequest(
    val id: String = UUID.randomUUID().toString(),
    val tabId: String,
    val lineIds: List<Int>,
)

/** Unifies predefined quick actions and user-defined commands for the composer's "/" suggestion list. */
internal sealed interface AiChipCommand {
    val displayName: String

    data class Predefined(val action: AiQuickAction) : AiChipCommand {
        override val displayName get() = "/${action.slashName}"
    }

    data class Custom(val command: CustomAiCommand) : AiChipCommand {
        override val displayName get() = "/${command.name}"
    }
}

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
