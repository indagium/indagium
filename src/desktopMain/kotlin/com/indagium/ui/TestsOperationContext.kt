package com.indagium.ui

import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestRun

/** A suite or case whose run/record controls were opened from the Tests workspace. */
internal data class TestsOperationTarget(val suiteId: String, val caseId: String? = null)

/** A user-facing label for the target captured by a run or recording, using the run's frozen suite snapshot. */
internal fun TestRun.operationTargetLabel(): String {
    val suiteName = suite.name.ifBlank { "Untitled suite" }
    val selected = config.caseIds
    val caseNames = selected.orEmpty().mapNotNull { id -> suite.cases.firstOrNull { it.id == id }?.name?.ifBlank { null } }
    return when {
        selected == null -> suiteName
        selected.size == 1 -> "$suiteName / ${caseNames.singleOrNull() ?: "Deleted case"}"
        else -> "$suiteName / ${selected.size} cases"
    }
}

/** The current library label for a pinned target, used while recording setup is still open. */
internal fun TestLibrary.operationTargetLabel(target: TestsOperationTarget): String {
    val suite = suite(target.suiteId) ?: return "Deleted suite"
    val suiteName = suite.name.ifBlank { "Untitled suite" }
    val caseName = target.caseId?.let { id -> suite.cases.firstOrNull { it.id == id }?.name }
    return if (target.caseId == null) suiteName else "$suiteName / ${caseName ?: "Deleted case"}"
}

/** Where a recording started from a suite should be applied. A new case gets its id only after creation succeeds. */
internal sealed interface RecordingCaseDestination {
    data object NewCase : RecordingCaseDestination

    data class ExistingCase(val caseId: String) : RecordingCaseDestination
}

/** The currently selected suite or case, ignoring a stale case id that no longer belongs to that suite. */
internal fun selectedTestsOperationTarget(
    suiteId: String?,
    selectedCaseId: String?,
    suiteCaseIds: Set<String>,
): TestsOperationTarget? {
    if (suiteId == null) return null
    return TestsOperationTarget(suiteId, selectedCaseId?.takeIf { it in suiteCaseIds })
}

/** Resolves the target after a suite-level recording destination has been picked and, for a new case, created. */
internal fun recordingTestsOperationTarget(
    suiteId: String,
    destination: RecordingCaseDestination,
    createdCaseId: String? = null,
    suiteCaseIds: Set<String>,
): TestsOperationTarget? = when (destination) {
    RecordingCaseDestination.NewCase -> createdCaseId?.takeIf { it in suiteCaseIds }?.let { TestsOperationTarget(suiteId, it) }
    is RecordingCaseDestination.ExistingCase -> destination.caseId.takeIf { it in suiteCaseIds }?.let { TestsOperationTarget(suiteId, it) }
}
