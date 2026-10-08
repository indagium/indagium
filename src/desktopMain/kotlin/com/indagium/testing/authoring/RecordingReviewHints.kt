package com.indagium.testing.authoring

import com.indagium.testing.model.MAX_STEP_CONDITION_CHARS

internal enum class RecordingRowIssue { ACTION, EXPECTED, CONDITION }

/** One shared authoring rule for reviewed recording rows in UI previews, session updates and final Apply. */
internal fun recordingRowIssues(
    row: RecordedTestStep,
    expected: String = row.expected,
    optional: Boolean = row.optional,
    condition: String? = row.condition,
    requireExpected: Boolean = true,
): Set<RecordingRowIssue> = buildSet {
    if (row.action.isBlank()) add(RecordingRowIssue.ACTION)
    if (hasConditionIssue(optional, condition)) {
        add(RecordingRowIssue.CONDITION)
    }
    if (hasExpectedIssue(row, expected, optional, requireExpected)) {
        add(RecordingRowIssue.EXPECTED)
    }
}

private fun hasConditionIssue(optional: Boolean, condition: String?): Boolean = when {
    optional && condition.isNullOrBlank() -> true
    !optional && !condition.isNullOrBlank() -> true
    condition != null && condition.length > MAX_STEP_CONDITION_CHARS -> true
    else -> false
}

private fun hasExpectedIssue(row: RecordedTestStep, expected: String, optional: Boolean, requireExpected: Boolean): Boolean = when {
    requireExpected && !optional && expected.isBlank() -> true
    optional && expected.isBlank() && !row.reviewReason.isNullOrBlank() -> true
    else -> false
}

private const val MAX_LISTED_STEP_NUMBERS = 6

/** Names the tap target from before evidence and keeps before/after app facts separate. */
fun RecordedTestStep.contextHint(): String? {
    val element = tappedElement?.label()?.takeIf(String::isNotBlank)

    fun context(ref: RecordingScreenRef?): String = ref
        ?.let { listOfNotNull(it.packageName, it.activity).joinToString(" ") }
        ?.takeIf(String::isNotBlank)
        ?: "unavailable"
    val beforeContext = context(before)
    val afterContext = context(after)
    if (element == null && beforeContext == "unavailable" && afterContext == "unavailable") return null
    return buildString {
        element?.let { append("target '$it' from before evidence; ") }
        append("before: ").append(beforeContext).append("; after: ").append(afterContext)
    }
}

/**
 * Why "Apply recorded steps" cannot be used yet, in words the reviewer can act on; null when nothing blocks it. Every step
 * needs an action and an expected result, and screen snapshots still being read must finish first.
 */
fun recordingApplyBlockedReason(snapshot: TestStepRecordingSnapshot): String? {
    if (snapshot.active) return "Stop the recording first."
    if (snapshot.steps.isEmpty()) return if (snapshot.excludedSourceInputIds.isNotEmpty()) {
        "No steps remain. Restore removed inputs or keep at least one step before applying."
    } else {
        "No input was recorded."
    }
    if (snapshot.pendingSnapshots > 0) return "Waiting for ${snapshot.pendingSnapshots} screen snapshot(s) to finish."
    val issues = snapshot.steps.mapIndexed { index, row ->
        (index + 1) to recordingRowIssues(row)
    }
    val missingAction = issues.filter { (_, found) -> RecordingRowIssue.ACTION in found }.map { it.first }
    val missingExpected = issues.filter { (_, found) -> RecordingRowIssue.EXPECTED in found }.map { it.first }
    val missingCondition = issues.filter { (_, found) -> RecordingRowIssue.CONDITION in found }.map { it.first }
    val reasons = listOfNotNull(
        missingAction.takeIf(List<Int>::isNotEmpty)?.let { "Fill in Action for ${stepList(it)}" },
        missingExpected.takeIf(List<Int>::isNotEmpty)?.let { "Fill in Expected for ${stepList(it)}" },
        missingCondition.takeIf(List<Int>::isNotEmpty)?.let { "Add a condition for optional ${stepList(it)}" },
    )
    return reasons.takeIf(List<String>::isNotEmpty)?.joinToString("; ", postfix = ".")
}

private fun stepList(numbers: List<Int>): String {
    val shown = numbers.take(MAX_LISTED_STEP_NUMBERS).joinToString(", ")
    val rest = numbers.size - MAX_LISTED_STEP_NUMBERS
    val label = if (numbers.size == 1) "step" else "steps"
    return if (rest > 0) "$label $shown and $rest more" else "$label $shown"
}
