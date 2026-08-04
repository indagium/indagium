package com.openlog.ai

/**
 * Thread-safe, per-request allowance for in-panel MCP invocations. The configured allowance is
 * intentionally spent only by analysis/operational tools. Notes and annotation reads/writes are
 * unlimited so an agent can always inspect, repair, and save its durable investigation output.
 */
internal class AiToolCallBudget(totalCalls: Int) {
    init {
        require(totalCalls > 0) { "maxToolCalls must be positive" }
    }

    private val evidenceBudget = totalCalls
    private var evidenceUsed = 0
    private var notesWritesUsed = 0
    private var returnedChars = 0
    private var truncatedResults = 0

    @Synchronized
    fun tryConsume(toolName: String): AiToolBudgetDecision {
        val isNotesWrite = toolName in NOTES_ANNOTATION_TOOLS
        val allowed = isNotesWrite || evidenceUsed < evidenceBudget
        if (allowed) {
            if (toolName in NOTES_WRITE_TOOLS) notesWritesUsed++
            else if (!isNotesWrite) evidenceUsed++
        }
        return AiToolBudgetDecision(allowed, isNotesWrite, snapshotLocked())
    }

    @Synchronized
    fun recordResult(chars: Int, truncated: Boolean): AiToolBudgetSnapshot {
        returnedChars += chars.coerceAtLeast(0)
        if (truncated) truncatedResults++
        return snapshotLocked()
    }

    @Synchronized
    fun snapshot(): AiToolBudgetSnapshot = snapshotLocked()

    fun initialGuidance(): String {
        val snapshot = snapshot()
        return "This request has a strict ${snapshot.totalBudget}-call MCP budget for analysis/operational calls. " +
            "Notes and annotation reads/writes are unlimited and do not consume that budget. Plan evidence " +
            "efficiently, and save or refine Notes whenever it helps the investigation."
    }

    fun rejectionMessage(isNotesWrite: Boolean, snapshot: AiToolBudgetSnapshot): String {
        check(!isNotesWrite) { "Notes/annotation operations are unlimited and cannot exhaust the MCP budget." }
        return "MCP analysis/operational call budget exhausted " +
            "(${snapshot.evidenceUsed}/${snapshot.evidenceBudget}). No handler or confirmation was started. " +
            "Notes and annotation operations remain unlimited."
    }

    fun resultFooter(snapshot: AiToolBudgetSnapshot): String? =
        if (snapshot.totalRemaining in RESULT_FOOTER_REMAINING) {
            "\n\n[Indagium MCP budget: ${snapshot.evidenceUsed}/${snapshot.evidenceBudget} analysis/operations; " +
                "Notes writes unlimited (${snapshot.notesWritesUsed} used); annotation reads unlimited; " +
                "${snapshot.totalRemaining} analysis/operational call(s) remaining.]"
        } else {
            null
        }

    private fun snapshotLocked(): AiToolBudgetSnapshot = AiToolBudgetSnapshot(
        evidenceUsed = evidenceUsed,
        evidenceBudget = evidenceBudget,
        notesWritesUsed = notesWritesUsed,
        notesWritesUnlimited = true,
        returnedChars = returnedChars,
        truncatedResults = truncatedResults,
    )

    private companion object {
        val RESULT_FOOTER_REMAINING = setOf(5, 3, 1)
        val NOTES_WRITE_TOOLS = setOf(
            "append_annotation_section", "set_annotation_section", "add_text_note", "add_log_note",
            "add_image_note", "update_note_block", "move_note_block", "delete_note_block",
            "clear_all_notes", "save_annotations", "load_annotations", "set_case_metadata",
        )

        // Set union rather than a spread: `*NOTES_WRITE_TOOLS.toTypedArray()` copies the whole set
        // into an array just to splat it back into another set (detekt SpreadOperator).
        val NOTES_ANNOTATION_TOOLS = NOTES_WRITE_TOOLS + setOf(
            "get_issue_description", "get_annotation_sections", "get_annotation_blocks",
        )
    }
}

internal data class AiToolBudgetDecision(
    val allowed: Boolean,
    val isNotesWrite: Boolean,
    val snapshot: AiToolBudgetSnapshot,
)

internal data class AiToolBudgetSnapshot(
    val evidenceUsed: Int,
    val evidenceBudget: Int,
    val notesWritesUsed: Int,
    val notesWritesUnlimited: Boolean,
    val returnedChars: Int,
    val truncatedResults: Int,
) {
    val totalUsed: Int get() = evidenceUsed
    val totalBudget: Int get() = evidenceBudget
    val totalRemaining: Int get() = totalBudget - totalUsed
}
