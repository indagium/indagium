package com.indagium.testing.run

import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
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
    result.screenshotPath?.let { append("  - Screenshot: `").append(it).append("`\n") }
    if (result.logStartOffset != null && result.logEndOffset != null) {
        append("  - Log bytes ").append(result.logStartOffset).append("–").append(result.logEndOffset).append('\n')
    }
    result.note?.let { append("  - Note: ").append(it).append('\n') }
    if (result.issueRequested) append("  - An issue was requested for this step.\n")
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
}

internal fun TestRun.toMarkdown(): String = buildString {
    append("# Test run: ").append(suite.name).append('\n')
    append("\n- Status: **").append(status.name).append("**\n")
    append("- Run id: `").append(id).append("`\n")
    append("- Started: ").append(format(startedAt)).append(" · finished: ").append(format(finishedAt)).append('\n')
    append("- Repeat: ").append(config.repeat).append(" · tool-call limit per case: ").append(config.caseToolCallLimit).append('\n')
    warnings.forEach { append("- Warning: ").append(it).append('\n') }
    error?.let { append("- Error: ").append(it).append('\n') }
    lanes.forEach { lane(it) }
}

private const val MAX_CHECK_DETAIL = 400
