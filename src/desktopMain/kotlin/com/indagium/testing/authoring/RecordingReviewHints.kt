package com.indagium.testing.authoring

private const val MAX_LISTED_STEP_NUMBERS = 6

/** "on 'Search' in com.app", "in com.app" or "on 'Search'" for a row whose screen context is known; null otherwise. */
fun RecordedTestStep.contextHint(): String? {
    val element = tappedElement?.label()?.takeIf(String::isNotBlank)
    val app = (before?.packageName ?: after?.packageName)?.takeIf(String::isNotBlank)
    return when {
        element != null && app != null -> "on '$element' in $app"
        element != null -> "on '$element'"
        app != null -> "in $app"
        else -> null
    }
}

/**
 * Why "Apply recorded steps" cannot be used yet, in words the reviewer can act on; null when nothing blocks it. Every step
 * needs an action and an expected result, and screen snapshots still being read must finish first.
 */
fun recordingApplyBlockedReason(snapshot: TestStepRecordingSnapshot): String? {
    if (snapshot.active) return "Stop the recording first."
    if (snapshot.steps.isEmpty()) return "No input was recorded."
    if (snapshot.pendingSnapshots > 0) return "Waiting for ${snapshot.pendingSnapshots} screen snapshot(s) to finish."
    val missingAction = snapshot.steps.mapIndexedNotNull { index, row -> (index + 1).takeIf { row.action.isBlank() } }
    val missingExpected = snapshot.steps.mapIndexedNotNull { index, row -> (index + 1).takeIf { row.expected.isBlank() } }
    val reasons = listOfNotNull(
        missingAction.takeIf(List<Int>::isNotEmpty)?.let { "Fill in Action for ${stepList(it)}" },
        missingExpected.takeIf(List<Int>::isNotEmpty)?.let { "Fill in Expected for ${stepList(it)}" },
    )
    return reasons.takeIf(List<String>::isNotEmpty)?.joinToString("; ", postfix = ".")
}

private fun stepList(numbers: List<Int>): String {
    val shown = numbers.take(MAX_LISTED_STEP_NUMBERS).joinToString(", ")
    val rest = numbers.size - MAX_LISTED_STEP_NUMBERS
    val label = if (numbers.size == 1) "step" else "steps"
    return if (rest > 0) "$label $shown and $rest more" else "$label $shown"
}
