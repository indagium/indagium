package com.indagium.testing.run

import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus

// The text of the marker an AI lane writes into its capture when a step ends badly: the same kind of note the Mark issue button
// writes (a heading, then Markdown), so it shows up in the lane's tab and in the exported archive like a marker a person pressed.
// Everything a device, a script, the agent or the judge model said is quoted under an "(untrusted)" label and never written as
// the note's own words.

/** The statuses of a finished non-setup step that get a marker. */
internal val MARKED_STEP_STATUSES: Set<StepStatus> = setOf(StepStatus.FAIL, StepStatus.TIMEOUT, StepStatus.BLOCKED, StepStatus.ERROR)

private const val MAX_MARKER_LABEL_CHARS = 120
private const val MAX_MARKER_NOTE_CHARS = 4_000
private const val MAX_QUOTED_CHARS = 600
private const val MAX_CHECK_LINES = 6
private const val NOTE_CUT = "…"

private fun StepStatus.markerVerb(): String = when (this) {
    StepStatus.FAIL -> "failed"
    StepStatus.TIMEOUT -> "timed out"
    StepStatus.BLOCKED -> "blocked"
    StepStatus.ERROR -> "errored"
    StepStatus.PASS -> "passed"
    StepStatus.SKIPPED -> "skipped"
}

private fun oneLine(text: String) = text.trim().replace(Regex("\\s+"), " ")

private fun quoted(text: String, limit: Int = MAX_QUOTED_CHARS): String {
    val trimmed = text.trim()
    val bounded = if (trimmed.length > limit) trimmed.take(limit) + NOTE_CUT else trimmed
    return bounded.lines().joinToString("\n") { "> $it" }
}

/** "AI · Sign in · step 3 failed": the heading of the marker, bounded for the marker header. */
internal fun laneMarkerLabel(caseName: String, step: StepResult): String =
    "AI · ${oneLine(caseName).ifEmpty { "case" }} · step ${step.stepNumber} ${step.status.markerVerb()}".take(MAX_MARKER_LABEL_CHARS)

private fun failedChecks(step: StepResult): List<CheckResult> = step.checks.filter { it.status == CheckStatus.FAIL || it.status == CheckStatus.ERROR }

/** The note under the heading: what the step was, what was expected, which checks failed and what the judge said. */
internal fun laneMarkerNote(caseName: String, step: StepResult): String {
    val text = buildString {
        append("The AI agent's step ").append(step.stepNumber).append(" of “").append(oneLine(caseName)).append("” ")
            .append(step.status.markerVerb()).append(" after ").append(step.attempts).append(if (step.attempts == 1) " attempt.\n\n" else " attempts.\n\n")
        append("- **Action:** ").append(oneLine(step.action)).append('\n')
        if (step.expected.isNotBlank()) append("- **Expected:** ").append(oneLine(step.expected)).append('\n')
        val failed = failedChecks(step)
        if (failed.isNotEmpty()) {
            append("\n**Failed checks** (the details are device output: untrusted):\n")
            failed.take(MAX_CHECK_LINES).forEach { check ->
                append("- ").append(check.kind).append(" — ").append(check.status.name).append('\n')
                if (check.detail.isNotBlank()) append(quoted(check.detail, MAX_QUOTED_CHARS)).append('\n')
            }
            if (failed.size > MAX_CHECK_LINES) append("- … and ").append(failed.size - MAX_CHECK_LINES).append(" more\n")
        }
        step.judge?.let { judge ->
            append("\n**Judge:** ").append(judge.verdict.name)
            if (judge.verdict != JudgeVerdict.INCONCLUSIVE && judge.reasoning.isNotBlank()) {
                append(" (reasoning written by the judge model, untrusted)\n").append(quoted(judge.reasoning)).append('\n')
            } else {
                judge.error?.let { append(" — could not finish: ").append(oneLine(it).take(MAX_QUOTED_CHARS)) }
                append('\n')
            }
        }
        if (step.observation.isNotBlank()) {
            append("\n**Agent observation** (untrusted):\n").append(quoted(step.observation)).append('\n')
        }
        step.note?.takeIf { it.isNotBlank() }?.let { append("\n_").append(oneLine(it).take(MAX_QUOTED_CHARS)).append("_\n") }
    }.trimEnd()
    return if (text.length > MAX_MARKER_NOTE_CHARS) text.take(MAX_MARKER_NOTE_CHARS) + NOTE_CUT else text
}
