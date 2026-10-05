package com.indagium.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.indagium.edition.EditionLimits
import com.indagium.testing.limits.FREE_EDITION_LIMIT_HINT
import com.indagium.testing.limits.LimitDecision
import com.indagium.testing.limits.LimitOperation
import com.indagium.testing.limits.activeCaseIds
import com.indagium.testing.limits.activeSuiteIds
import com.indagium.testing.limits.decide
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.store.StoreResult

// UI-free state derivation for the Tests workspace (ui/TestsWorkspace.kt and friends): everything that
// can be decided without Compose lives here so it is unit-testable (TestsLimitsUiStateTest and friends).

/** Which screen of the Tests workspace the centre pane shows. */
internal sealed interface TestsNav {
    data object Suites : TestsNav

    data object SharedSteps : TestsNav

    data object Scripts : TestsNav

    data object Runs : TestsNav

    data object Issues : TestsNav
}

/**
 * Selection and search state of the Tests workspace. Owned by [AppState] (not by the composable) so it
 * survives switching to another tab and back. Ephemeral: never autosaved.
 */
internal class TestsViewState {
    var nav: TestsNav by mutableStateOf(TestsNav.Suites)
    var selectedSuiteId: String? by mutableStateOf(null)

    /** Non-null opens the case editor for that case in the centre pane. */
    var selectedCaseId: String? by mutableStateOf(null)

    /** The one step shown expanded in the step list, per container (case id or shared-step id). */
    var expandedStepId: String? by mutableStateOf(null)
    var selectedScriptId: String? by mutableStateOf(null)
    var selectedSharedStepId: String? by mutableStateOf(null)
    var search: String by mutableStateOf("")

    /** Non-null while the run dialog is open. */
    var runDialog: RunDialogTarget? by mutableStateOf(null)

    /** The run whose report the Runs screen shows; null shows the list of runs. */
    var selectedRunId: String? by mutableStateOf(null)

    /** The issue whose detail the Issues screen shows; null shows the list of issues. */
    var selectedIssueId: String? by mutableStateOf(null)

    /** Non-null while the issue dialog is open. */
    var issueDialog: IssueDialogTarget? by mutableStateOf(null)

    /** Width of the left navigation column in dp; dragged through the shared HDivider, clamped by [clampTestsNavWidth]. */
    var navWidthDp: Float by mutableStateOf(TESTS_NAV_WIDTH_DEFAULT_DP)
}

internal const val TESTS_NAV_WIDTH_DEFAULT_DP = 248f
internal const val TESTS_NAV_WIDTH_MIN_DP = 180f
internal const val TESTS_NAV_WIDTH_MAX_DP = 420f

/** [current] widened by [deltaDp], kept within the navigation column's bounds. */
internal fun clampTestsNavWidth(current: Float, deltaDp: Float): Float =
    (current + deltaDp).coerceIn(TESTS_NAV_WIDTH_MIN_DP, TESTS_NAV_WIDTH_MAX_DP)

// ── Edition limits ───────────────────────────────────────────────────

/** The hint shown for a refused action: the Free-edition wording when [limits] is the Free edition. */
internal fun limitHintFor(limits: EditionLimits): String = when {
    limits == EditionLimits.FREE -> FREE_EDITION_LIMIT_HINT
    else -> "Edition limit: ${limits.maxSuites ?: "unlimited"} suite(s) / ${limits.maxCasesPerSuite ?: "unlimited"} case(s) per suite"
}

/** What the edition limits mean for the controls on screen. Derived by [testsLimitsUiState]; never stored. */
internal data class TestsLimitsUiState(
    val hint: String,
    val canCreateSuite: Boolean,
    val lockedSuiteIds: Set<String>,
    val lockedCaseIds: Set<String>,
    val casesCreatableInSuiteIds: Set<String>,
    val duplicableSuiteIds: Set<String>,
    val duplicableCaseIds: Set<String>,
) {
    fun isSuiteLocked(suiteId: String): Boolean = suiteId in lockedSuiteIds

    fun isCaseLocked(caseId: String): Boolean = caseId in lockedCaseIds

    fun canCreateCase(suiteId: String): Boolean = suiteId in casesCreatableInSuiteIds

    fun canDuplicateSuite(suiteId: String): Boolean = suiteId in duplicableSuiteIds

    fun canDuplicateCase(caseId: String): Boolean = caseId in duplicableCaseIds

    /** True when at least one suite or case is locked, so the workspace shows its banner. */
    val hasLockedItems: Boolean get() = lockedSuiteIds.isNotEmpty() || lockedCaseIds.isNotEmpty()

    /** The banner text, or null when nothing is locked. */
    val banner: String?
        get() = if (!hasLockedItems) null else "$hint. Locked: ${lockedSuiteIds.size} suite(s), ${lockedCaseIds.size} case(s). " +
            "Locked items stay readable, exportable, reorderable and deletable."
}

/** Maps the library and the edition's limits to the flags the UI needs. Pure; reuses [decide], the same function the store uses. */
internal fun testsLimitsUiState(library: TestLibrary, limits: EditionLimits): TestsLimitsUiState {
    fun allowed(operation: LimitOperation) = decide(library, operation, limits) !is LimitDecision.Refused
    val activeSuites = activeSuiteIds(library, limits)
    val activeCases = activeCaseIds(library, limits)
    val allCaseIds = library.suites.flatMap { suite -> suite.cases.map { it.id } }
    return TestsLimitsUiState(
        hint = limitHintFor(limits),
        canCreateSuite = allowed(LimitOperation.CreateSuite),
        lockedSuiteIds = library.suites.map { it.id }.filterNot { it in activeSuites }.toSet(),
        lockedCaseIds = allCaseIds.filterNot { it in activeCases }.toSet(),
        casesCreatableInSuiteIds = library.suites.filter { allowed(LimitOperation.CreateCase(it.id)) }.map { it.id }.toSet(),
        duplicableSuiteIds = library.suites.filter { allowed(LimitOperation.DuplicateSuite(it.id)) }.map { it.id }.toSet(),
        duplicableCaseIds = library.suites.flatMap { suite ->
            suite.cases.filter { allowed(LimitOperation.DuplicateCase(suite.id, it.id)) }.map { it.id }
        }.toSet(),
    )
}

// ── Edit access ──────────────────────────────────────────────────────

internal const val LIBRARY_READ_ONLY_MESSAGE =
    "The test library was saved by a newer version of Indagium and is read-only here. Update Indagium to change it."
internal const val SUITE_READ_ONLY_MESSAGE =
    "This suite was saved by a newer version of Indagium and is read-only here. Update Indagium to change it."

/** Whether a screen's fields may be edited, and why not when they may not. */
internal data class TestsEditAccess(val editable: Boolean, val reason: String? = null) {
    companion object {
        val EDITABLE = TestsEditAccess(true)
    }
}

internal fun libraryEditAccess(library: TestLibrary): TestsEditAccess =
    if (library.readOnly) TestsEditAccess(false, LIBRARY_READ_ONLY_MESSAGE) else TestsEditAccess.EDITABLE

/** A suite is read-only when the library or the suite file is from a newer version, or the edition locks it. */
internal fun suiteEditAccess(library: TestLibrary, suite: TestSuite, limits: TestsLimitsUiState): TestsEditAccess = when {
    library.readOnly -> TestsEditAccess(false, LIBRARY_READ_ONLY_MESSAGE)
    suite.readOnly -> TestsEditAccess(false, SUITE_READ_ONLY_MESSAGE)
    limits.isSuiteLocked(suite.id) -> TestsEditAccess(false, "This suite is locked. ${limits.hint}")
    else -> TestsEditAccess.EDITABLE
}

/** A case is also read-only when it alone is locked (it sits past the edition's per-suite case limit). */
internal fun caseEditAccess(library: TestLibrary, suite: TestSuite, caseId: String, limits: TestsLimitsUiState): TestsEditAccess {
    val suiteAccess = suiteEditAccess(library, suite, limits)
    return when {
        !suiteAccess.editable -> suiteAccess
        limits.isCaseLocked(caseId) -> TestsEditAccess(false, "This case is locked. ${limits.hint}")
        else -> TestsEditAccess.EDITABLE
    }
}

// ── Messages ─────────────────────────────────────────────────────────

/** The text to show for a failed store call, or null when it succeeded. */
internal fun StoreResult<*>.userMessage(): String? = when (this) {
    is StoreResult.Ok -> null
    is StoreResult.Invalid -> reason
    is StoreResult.NotFound -> message
    is StoreResult.LimitReached -> "${decision.message} ${decision.hint}"
}

/** Warnings of a successful call (for example "2 cases arrive locked"), else empty. */
internal fun StoreResult<*>.userWarnings(): List<String> = (this as? StoreResult.Ok<*>)?.warnings.orEmpty()

// ── Names ────────────────────────────────────────────────────────────

/** [base] when unused, else "[base] 2", "[base] 3", ... (compared ignoring case). */
internal fun uniqueName(base: String, existing: Collection<String>): String {
    val taken = existing.mapTo(HashSet()) { it.lowercase() }
    if (base.lowercase() !in taken) return base
    var n = 2
    while ("$base $n".lowercase() in taken) n++
    return "$base $n"
}

/** Like [uniqueName] for identifiers: [base], then "[base]_2", "[base]_3", ... */
internal fun uniqueIdentifier(base: String, existing: Collection<String>): String {
    if (base !in existing) return base
    var n = 2
    while ("${base}_$n" in existing) n++
    return "${base}_$n"
}

// ── Search ───────────────────────────────────────────────────────────

/** Suites whose name, description, target package or tags contain [query] (ignoring case); all suites for a blank query. */
internal fun filterSuites(suites: List<TestSuite>, query: String): List<TestSuite> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return suites
    return suites.filter { suite ->
        suite.name.lowercase().contains(q) || suite.description.lowercase().contains(q) ||
            suite.targetPackage.lowercase().contains(q) || suite.tags.any { it.lowercase().contains(q) }
    }
}

// ── Step badges, numbers and key=value text ──────────────────────────

/** The short label of a check's kind, shown as a badge on a collapsed step row. */
internal fun checkKindLabel(check: StepCheck): String = when (check) {
    is StepCheck.LogAppears -> "Log appears"
    is StepCheck.LogAbsent -> "Log absent"
    is StepCheck.ScreenJudge -> "Screen"
    is StepCheck.ScriptResult -> "Script"
    is StepCheck.AskJudge -> "Judge"
}

/** One badge label per distinct check kind of [step], in first-seen order, with a count when a kind repeats. */
internal fun stepCheckBadges(step: TestStep): List<String> =
    step.checks.groupBy { checkKindLabel(it) }.map { (label, checks) -> if (checks.size > 1) "$label ×${checks.size}" else label }

/** [text] as a whole number within [min]..[max], or null when it is not one. */
internal fun parseWholeNumber(text: String, min: Long, max: Long): Long? = text.trim().toLongOrNull()?.takeIf { it in min..max }

/** `key=value` lines, one per entry, in map order. */
internal fun formatKeyValueLines(map: Map<String, String>): String = map.entries.joinToString("\n") { "${it.key}=${it.value}" }

/** The map and the lines that were not `key=value` (a blank key counts as not valid). Blank lines are skipped; a later duplicate key wins. */
internal fun parseKeyValueLines(text: String): Pair<Map<String, String>, List<String>> {
    val map = LinkedHashMap<String, String>()
    val bad = mutableListOf<String>()
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
        val key = line.substringBefore('=', missingDelimiterValue = "").trim()
        if (!line.contains('=') || key.isEmpty()) bad += line else map[key] = line.substringAfter('=').trim()
    }
    return map to bad
}

// ── Usage of library entries ─────────────────────────────────────────

private fun TestSuite.allHooks(): List<HookItem> = setup + teardown + cases.flatMap { it.setup + it.teardown }

private fun TestSuite.allSteps(): List<TestStep> = cases.flatMap { it.steps }

/** How many hooks and script-result checks (in suites and shared steps) point at script [scriptId]. */
internal fun scriptUsageCount(library: TestLibrary, scriptId: String): Int {
    val hooks = library.suites.flatMap { it.allHooks() }.count { it is HookItem.Script && it.scriptId == scriptId }
    val steps = library.suites.flatMap { it.allSteps() } + library.sharedSteps.flatMap { it.steps }
    val checks = steps.flatMap { it.checks }.count { it is StepCheck.ScriptResult && it.scriptId == scriptId }
    return hooks + checks
}

/** How many suite and case hooks run shared step [sharedStepId]. */
internal fun sharedStepUsageCount(library: TestLibrary, sharedStepId: String): Int =
    library.suites.flatMap { it.allHooks() }.count { it is HookItem.Shared && it.sharedStepId == sharedStepId }
