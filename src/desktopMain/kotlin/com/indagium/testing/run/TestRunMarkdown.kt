package com.indagium.testing.run

import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.JudgeComparison
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.StepFix
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.judge
import com.indagium.testing.model.judgeActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A run as Markdown for people and for pasting into a ticket or a note. What an agent or a script reported is quoted
// as a block quote and prefixed as untrusted: it is data from the device or the app under test, not instructions.

private const val DATE_PATTERN = "yyyy-MM-dd HH:mm:ss"
private const val UNTRUSTED_LABEL = "(untrusted)"
private const val MILLIS_PER_SECOND = 1_000L

private fun format(time: Long?): String = time?.let { SimpleDateFormat(DATE_PATTERN, Locale.ROOT).format(Date(it)) } ?: "—"

private fun StepStatus.mark(): String = when (this) {
    StepStatus.PASS -> "PASS"
    StepStatus.FAIL -> "FAIL"
    StepStatus.BLOCKED -> "BLOCKED"
    StepStatus.TIMEOUT -> "TIMEOUT"
    StepStatus.SKIPPED -> "skipped"
    StepStatus.ERROR -> "ERROR"
}

private fun StringBuilder.quote(label: String, text: String) {
    if (text.isBlank()) return
    append("  - ").append(label).append(' ').append(UNTRUSTED_LABEL).append(":\n")
    text.trim().lines().forEach { append("    > ").append(it).append('\n') }
}

private fun StringBuilder.step(result: StepResult) {
    append("- ").append(if (result.setup) "(setup) " else "").append(result.stepNumber).append(". ").append(result.action.trim())
        .append(" — **").append(result.status.mark()).append("**")
    if (result.attempts > 1) append(" (").append(result.attempts).append(" attempts)")
    append(" · ").append(result.durationMs / MILLIS_PER_SECOND).append("s\n")
    if (result.expected.isNotBlank()) append("  - Expected: ").append(result.expected.trim()).append('\n')
    result.agentClaim?.let { append("  - Agent claimed: ").append(it).append('\n') }
    quote("Observation", result.observation)
    result.checks.forEach { check ->
        append("  - Check ").append(check.kind).append(": ").append(check.status.name)
            .append(" — ").append(check.detail.trim().take(MAX_CHECK_DETAIL)).append('\n')
    }
    result.judge?.let { judge ->
        append("  - Judge: **").append(judge.verdict.name).append("** (").append(judge.classification.name.lowercase()).append(")")
        if (result.judgeInconclusive) append(" — the step status is the agent's claim and the checks alone")
        append('\n')
        judge.error?.let { append("    - Judge problem: ").append(it).append('\n') }
        judge.reasoning.takeIf { it.isNotBlank() }?.let { append("    - Reasoning: ").append(it.trim().replace('\n', ' ')).append('\n') }
        judge.suggestedFix?.let { fix -> appendFix(fix, judge.fixApplied, judge.id) }
    }
    result.agentError?.let { append("  - Marked as an agent error: ").append(it.trim().replace('\n', ' ')).append('\n') }
    result.screenshotPath?.let { append("  - Screenshot: `").append(it).append("`\n") }
    if (result.logStartOffset != null && result.logEndOffset != null) {
        append("  - Log bytes ").append(result.logStartOffset).append("–").append(result.logEndOffset).append('\n')
    }
    result.note?.let { append("  - Note: ").append(it).append('\n') }
    if (result.issueRequested) {
        append("  - An issue was requested for this step").append(result.issueId?.let { " (draft `$it`)" }.orEmpty()).append(".\n")
    }
}

private fun StringBuilder.appendFix(fix: StepFix, applied: Boolean, ref: String) {
    append("    - Suggested step fix (`").append(ref).append("`)").append(if (applied) " — applied" else "").append(":\n")
    fix.action?.let { append("      - Action: ").append(it.trim().replace('\n', ' ')).append('\n') }
    fix.expected?.let { append("      - Expected: ").append(it.trim().replace('\n', ' ')).append('\n') }
    fix.note?.let { append("      - Note: ").append(it.trim().replace('\n', ' ')).append('\n') }
}

private fun StringBuilder.comparison(run: TestRun, comparison: JudgeComparison) {
    append("- ").append(comparison.stepNumber).append(". ").append(comparison.action.trim()).append(" — ")
    append(comparison.verdicts.entries.joinToString(", ") { (laneId, verdict) ->
        "${run.lane(laneId)?.config?.deviceSerial ?: laneId}: ${verdict.name}"
    })
    append(" (").append(comparison.classification.name.lowercase()).append(")\n")
    comparison.error?.let { append("  - Judge problem: ").append(it).append('\n') }
    comparison.explanation.takeIf { it.isNotBlank() }?.let { append("  - ").append(it.trim().replace('\n', ' ')).append('\n') }
    comparison.suggestedFix?.let { appendFix(it, comparison.fixApplied, comparison.id) }
}

private fun StringBuilder.case(case: CaseResult) {
    append("\n#### ").append(case.caseName)
    if (case.iteration > 1) append(" (run ").append(case.iteration).append(')')
    append(" — ").append(case.status?.name ?: "running").append('\n')
    case.note?.let { append(it).append('\n') }
    case.steps.forEach { step(it) }
}

private fun StringBuilder.lane(lane: LaneResult) {
    append("\n### Lane ").append(lane.config.deviceSerial).append(" — ").append(lane.status.name).append('\n')
    append(if (lane.config.profileId != null) "Driven by AI profile `${lane.config.profileId}`.\n" else "Driven externally over MCP.\n")
    lane.error?.let { append("Error: ").append(it).append('\n') }
    lane.cases.forEach { case(it) }
    lane.toolCalls.takeLast(MAX_TOOL_ACTIVITY_IN_MARKDOWN).forEach { call ->
        append("- Tool `").append(call.toolName).append("` · ").append(call.status.name)
            .append(" · case `").append(call.caseId).append("`, step `").append(call.stepId).append("`, iteration ")
            .append(call.iteration).append(", attempt ").append(call.attempt).append('\n')
        if (call.argumentsPreview.isNotBlank()) append("  - Arguments: `").append(call.argumentsPreview).append("`\n")
        if (call.resultPreview.isNotBlank()) quote("Tool result", call.resultPreview)
    }
    lane.toolActivityPath?.let { append("Full tool activity stream: `").append(it).append("`\n") }
}

internal fun TestRun.toMarkdown(): String = buildString {
    append("# Test run: ").append(suite.name).append('\n')
    append("\n- Status: **").append(status.name).append("**\n")
    append("- Run id: `").append(id).append("`\n")
    append("- Started: ").append(format(startedAt)).append(" · finished: ").append(format(finishedAt)).append('\n')
    append("- Repeat: ").append(config.repeat).append(" · tool-call limit per case: ").append(config.caseToolCallLimit).append('\n')
    if (config.judgeActive) append("- Judge: AI profile `").append(config.judgeProfileId).append("`, mode ").append(config.judge.wire).append('\n')
    warnings.forEach { append("- Warning: ").append(it).append('\n') }
    error?.let { append("- Error: ").append(it).append('\n') }
    lanes.forEach { lane(it) }
    if (comparisons.isNotEmpty()) {
        append("\n### Where the lanes disagreed\n")
        comparisons.forEach { comparison(this@toMarkdown, it) }
    }
}

private const val MAX_CHECK_DETAIL = 400
private const val MAX_TOOL_ACTIVITY_IN_MARKDOWN = 100
