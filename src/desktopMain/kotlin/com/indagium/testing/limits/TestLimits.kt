package com.indagium.testing.limits

import com.indagium.edition.EditionLimits
import com.indagium.testing.model.TestLibrary

// Edition limits for the test library, as one pure function the store, the MCP tools, import,
// duplicate, the UI and the run engine all share.
//
// Over-limit rules: the first N suites (and the first M cases of each active suite) in the user's own
// order are ACTIVE; everything after is LOCKED. Locked items stay readable, exportable, deletable and
// reorderable (reordering is how the user picks which ones are active) but cannot be edited, run or
// duplicated. Creating, duplicating or importing a suite beyond the suite limit is refused. Importing
// a suite with more cases than the limit succeeds with the extra cases locked. Data is never dropped.

const val FREE_EDITION_LIMIT_HINT = "Free edition: 1 suite / 5 cases — upgrade to add more"

enum class LimitKind { SUITE_LIMIT, CASE_LIMIT, LOCKED }

/** A request the limits layer is asked about. Unknown ids are treated as unlimited: the store reports NotFound itself. */
sealed interface LimitOperation {
    data object CreateSuite : LimitOperation

    data class DuplicateSuite(val suiteId: String) : LimitOperation

    data class ImportSuite(val caseCount: Int) : LimitOperation

    data class EditSuite(val suiteId: String) : LimitOperation

    data class CreateCase(val suiteId: String) : LimitOperation

    data class DuplicateCase(val suiteId: String, val caseId: String) : LimitOperation

    data class EditCase(val suiteId: String, val caseId: String) : LimitOperation

    /** Moving a case into a DIFFERENT suite; reordering inside one suite is always allowed. */
    data class MoveCaseToSuite(val caseId: String, val toSuiteId: String) : LimitOperation

    data class RunSuite(val suiteId: String) : LimitOperation

    data class RunCase(val suiteId: String, val caseId: String) : LimitOperation
}

sealed interface LimitDecision {
    data object Allowed : LimitDecision

    /** Allowed, but the caller should show [warning] to the user. */
    data class AllowedWithWarning(val warning: String) : LimitDecision

    /** Refused. [limit] is the number that was exceeded, when the refusal is about a count. */
    data class Refused(val kind: LimitKind, val message: String, val limit: Int? = null) : LimitDecision {
        val hint: String get() = FREE_EDITION_LIMIT_HINT
    }
}

private fun EditionLimits.suiteCap(): Int = maxSuites ?: Int.MAX_VALUE

private fun EditionLimits.caseCap(): Int = maxCasesPerSuite ?: Int.MAX_VALUE

/** Ids of the suites that are not locked, in user order. */
fun activeSuiteIds(library: TestLibrary, limits: EditionLimits): Set<String> =
    library.suites.take(limits.suiteCap()).mapTo(LinkedHashSet()) { it.id }

/** Ids of the cases that are not locked: the first cases of each active suite. Every case of a locked suite is locked. */
fun activeCaseIds(library: TestLibrary, limits: EditionLimits): Set<String> {
    val ids = LinkedHashSet<String>()
    library.suites.take(limits.suiteCap()).forEach { suite -> suite.cases.take(limits.caseCap()).forEach { ids += it.id } }
    return ids
}

fun isSuiteLocked(library: TestLibrary, suiteId: String, limits: EditionLimits): Boolean =
    library.suite(suiteId) != null && suiteId !in activeSuiteIds(library, limits)

fun isCaseLocked(library: TestLibrary, caseId: String, limits: EditionLimits): Boolean =
    library.findCase(caseId) != null && caseId !in activeCaseIds(library, limits)

private fun suiteCountRefusal(library: TestLibrary, limits: EditionLimits): LimitDecision.Refused? {
    val max = limits.maxSuites ?: return null
    if (library.suites.size < max) return null
    return LimitDecision.Refused(LimitKind.SUITE_LIMIT, "Suite limit reached: this edition allows $max suite(s).", max)
}

private fun caseCountRefusal(library: TestLibrary, suiteId: String, limits: EditionLimits): LimitDecision.Refused? {
    val max = limits.maxCasesPerSuite ?: return null
    val suite = library.suite(suiteId) ?: return null
    if (suite.cases.size < max) return null
    return LimitDecision.Refused(LimitKind.CASE_LIMIT, "Case limit reached: this edition allows $max case(s) per suite.", max)
}

private fun lockedSuiteRefusal(library: TestLibrary, suiteId: String, limits: EditionLimits): LimitDecision.Refused? =
    LimitDecision.Refused(LimitKind.LOCKED, "This suite is locked by the edition limit; it can be read, exported, reordered and deleted.")
        .takeIf { isSuiteLocked(library, suiteId, limits) }

private fun lockedCaseRefusal(library: TestLibrary, caseId: String, limits: EditionLimits): LimitDecision.Refused? =
    LimitDecision.Refused(LimitKind.LOCKED, "This case is locked by the edition limit; it can be read, exported, reordered and deleted.")
        .takeIf { isCaseLocked(library, caseId, limits) }

private fun importDecision(library: TestLibrary, operation: LimitOperation.ImportSuite, limits: EditionLimits): LimitDecision {
    suiteCountRefusal(library, limits)?.let { return it }
    val max = limits.maxCasesPerSuite ?: return LimitDecision.Allowed
    if (operation.caseCount <= max) return LimitDecision.Allowed
    val locked = operation.caseCount - max
    return LimitDecision.AllowedWithWarning(
        "This suite has ${operation.caseCount} cases; this edition allows $max per suite, so the last $locked arrive locked (read-only).",
    )
}

private fun runSuiteDecision(library: TestLibrary, suiteId: String, limits: EditionLimits): LimitDecision {
    lockedSuiteRefusal(library, suiteId, limits)?.let { return it }
    val lockedCases = library.suite(suiteId)?.let { it.cases.size - minOf(it.cases.size, limits.caseCap()) } ?: 0
    if (lockedCases == 0) return LimitDecision.Allowed
    return LimitDecision.AllowedWithWarning("$lockedCases locked case(s) will not run.")
}

private fun moveCaseDecision(library: TestLibrary, operation: LimitOperation.MoveCaseToSuite, limits: EditionLimits): LimitDecision {
    val from = library.findCase(operation.caseId)?.suite?.id
    if (from == operation.toSuiteId) return LimitDecision.Allowed
    lockedSuiteRefusal(library, operation.toSuiteId, limits)?.let { return it }
    return caseCountRefusal(library, operation.toSuiteId, limits) ?: LimitDecision.Allowed
}

private fun suiteLevelDecision(library: TestLibrary, operation: LimitOperation, limits: EditionLimits): LimitDecision? = when (operation) {
    LimitOperation.CreateSuite -> suiteCountRefusal(library, limits) ?: LimitDecision.Allowed
    is LimitOperation.DuplicateSuite ->
        lockedSuiteRefusal(library, operation.suiteId, limits) ?: suiteCountRefusal(library, limits) ?: LimitDecision.Allowed
    is LimitOperation.ImportSuite -> importDecision(library, operation, limits)
    is LimitOperation.EditSuite -> lockedSuiteRefusal(library, operation.suiteId, limits) ?: LimitDecision.Allowed
    is LimitOperation.RunSuite -> runSuiteDecision(library, operation.suiteId, limits)
    else -> null
}

private fun caseLevelDecision(library: TestLibrary, operation: LimitOperation, limits: EditionLimits): LimitDecision = when (operation) {
    is LimitOperation.CreateCase ->
        lockedSuiteRefusal(library, operation.suiteId, limits) ?: caseCountRefusal(library, operation.suiteId, limits) ?: LimitDecision.Allowed
    is LimitOperation.DuplicateCase ->
        lockedSuiteRefusal(library, operation.suiteId, limits) ?: lockedCaseRefusal(library, operation.caseId, limits)
            ?: caseCountRefusal(library, operation.suiteId, limits) ?: LimitDecision.Allowed
    is LimitOperation.EditCase ->
        lockedSuiteRefusal(library, operation.suiteId, limits) ?: lockedCaseRefusal(library, operation.caseId, limits) ?: LimitDecision.Allowed
    is LimitOperation.MoveCaseToSuite -> moveCaseDecision(library, operation, limits)
    is LimitOperation.RunCase ->
        lockedSuiteRefusal(library, operation.suiteId, limits) ?: lockedCaseRefusal(library, operation.caseId, limits) ?: LimitDecision.Allowed
    else -> LimitDecision.Allowed
}

/** Decides whether [operation] may proceed on [library] under [limits]. Pure: no I/O, no clock. */
fun decide(library: TestLibrary, operation: LimitOperation, limits: EditionLimits): LimitDecision =
    suiteLevelDecision(library, operation, limits) ?: caseLevelDecision(library, operation, limits)
