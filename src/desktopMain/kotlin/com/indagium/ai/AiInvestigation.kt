package com.indagium.ai

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
        prompt = """
            Investigate the issue in this pinned tab and leave a durable, evidence-backed analysis in Notes.

            ## Intake and scope
            1. Call `get_issue_description`. If `issueDescription` is blank, say that the issue description is
               missing and stop; do not infer a bug from the log.
            2. Call `get_annotation_sections` to preserve existing From and Next steps context, and
               `get_annotation_blocks` to inspect existing evidence blocks by id before editing them. Call `get_filter`
               and `get_crash_sites` once to establish the current view and any high-signal anchors. Use
               `get_project_info` with `maxContentChars: 4000` at most once when registered source-folder context would help identify the
               functional area. Do not ask for source code or README content repeatedly.
            3. State a working functional area only as a hypothesis, derived from the issue description, exact
               tags/packages, crash site, or a source mapping. Do not treat the reporter's area as proof of cause.

            ## Evidence loop — minimise tokens
            - Aim for about 20 focused evidence/operational calls. Only those calls consume the configured
              MCP budget; Notes/annotation reads and writes are unlimited, but still avoid noisy duplicate edits.
            - Never call unfiltered `get_visible_lines`. First narrow with `set_filter`, using exact tags,
              package prefixes, PIDs/TIDs, levels, or a literal keyword discovered from a crash/description.
              Use `get_tags` or `get_packages` only when needed to discover valid filter values.
            - Read small samples: normally 50–200 rows with `compact: true` and fields `id`, `ts`, `level`,
              `tag`, and `msg`. Paginate or widen only when the prior sample creates a specific question.
            - For an anchor, use `get_line_context` with a small before/after window rather than widening the
              filter. Resolve source only for the few decisive lines with `resolve_log_source`; source matches
              identify ownership and control flow, not proof on their own. If source context is needed, first
              call `list_source_declarations` using a returned filePath, then fetch only relevant bodies with
              `get_source_declarations` and its revision. Use paginated `get_source_file` only for broader
              context the exact declaration cannot provide.
            - Inspect existing sequences through `get_sequence_summary`; then inspect only error-containing,
              longest, or otherwise representative occurrences by their boundary line ids. Add a new sequence
              only after bounded evidence reveals real start/end markers. Never expand every group or read every
              sequence occurrence.
            - Keep a claim only when its time order and causal connection are supported by returned evidence.
              A missing line, noisy framework message, or a past case is a lead, not evidence of this root cause.

            ## Functional-area pivot and critic pass
            Before writing Notes, perform a separate compact critic pass over the tentative analysis: list the
            strongest alternative cause, each unsupported leap, and the one most valuable missing check. If the
            critic finds a material gap, gather only the targeted evidence and revise the hypothesis before
            continuing. Do not claim that an external reviewer or subagent was used.

            If the evidence points to a root-cause functional area different from the reported area, explicitly
            record both areas and restart the narrowed evidence loop in the newly discovered area before making a
            root-cause conclusion. Do not merely relabel the conclusion; confirm the cross-area handoff with log
            or source evidence. If it remains uncertain, report competing hypotheses rather than a false root cause.

            ## Required Notes output
            Save the result even if the outcome is "inconclusive". Preserve existing Notes; only append useful,
            non-duplicated content:
            1. Use `append_annotation_section` on `prefix` to add a concise investigation scope/title when the
               existing From section does not already identify it.
            2. Create a chronological evidence timeline with `add_log_note` for the decisive log rows. Anchor each
               block to the smallest useful set of line ids (first/last for a contiguous range) and give it a
               factual caption. Do not dump all matching rows into Notes.
            3. Use `add_text_note` for the synthesis: issue, reported area, root-cause area, conclusion and
               confidence, causal chain, evidence limitations, and rejected alternatives.
            4. Use `append_annotation_section` on `suffix` for concrete follow-up: fix owner/area, verification,
               and any unanswered question. Do not overwrite existing From or Next steps text.

            In the chat reply, give a short summary and link it to the note/evidence you created. Cite only
            tool-returned facts; never invent log lines, source mappings, or actions.
        """.trimIndent(),
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
