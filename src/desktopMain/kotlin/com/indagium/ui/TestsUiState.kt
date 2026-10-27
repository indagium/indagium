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
import com.indagium.testing.model.CaseRunCell
import com.indagium.testing.model.CaseRunMatrix
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.RunSummary
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.SuiteCaseHistory
import com.indagium.testing.model.SuiteCaseLaneHistory
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.summary
import com.indagium.testing.run.rerunFailedCasesConfig
import com.indagium.testing.store.StoreResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// UI-free state derivation for the Tests workspace (ui/TestsWorkspace.kt and friends): everything that
// can be decided without Compose lives here so it is unit-testable (TestsLimitsUiStateTest and friends).

/** Which screen of the Tests workspace the centre pane shows. */
internal sealed interface TestsNav {
    data object Suites : TestsNav

    data object SharedSteps : TestsNav

    data object Scripts : TestsNav

    data object Runs : TestsNav

    data object Issues : TestsNav

    data object AgentExamples : TestsNav
}

/** Tabs within one suite; stored in workspace state so switching navigation does not reset the active tab. */
internal enum class SuiteTab(val label: String) {
    Cases("Cases"),
    Health("Health"),
    Runs("Runs"),
    Settings("Settings"),
}

/**
 * Selection and search state of the Tests workspace. Owned by [AppState] (not by the composable) so it
 * survives switching to another tab and back. Ephemeral: never autosaved.
 */
internal class TestsViewState {
    var nav: TestsNav by mutableStateOf(TestsNav.Suites)
    var selectedSuiteId: String? by mutableStateOf(null)
    var selectedSuiteTab: SuiteTab by mutableStateOf(SuiteTab.Cases)

    /** Non-null opens the case editor for that case in the centre pane. */
    var selectedCaseId: String? by mutableStateOf(null)

    /**
     * The case row selected in the suite's Cases tab: its detail panel shows beside the table. A row click selects it, a second click
     * (or Enter, or the panel's Edit steps) opens the editor through [selectedCaseId]. Only honoured while it is a case of the suite on screen.
     */
    var previewCaseId: String? by mutableStateOf(null)

    /** The suite screen's inline "cases are read-only" notice was dismissed. Session only; it comes back after a restart. */
    var lockedCasesNoticeDismissed: Boolean by mutableStateOf(false)

    /** The one step selected in the step list (its editor is the inspector), per container (case id or shared-step id). */
    var selectedStepId: String? by mutableStateOf(null)

    /**
     * The case-level field whose editor the case screen's inspector shows instead of the selected step's (a summary chip was
     * clicked); null shows the step editor. Selecting a step, Done and Esc clear it.
     */
    var caseInspectorField: CaseField? by mutableStateOf(null)

    /** A step the list should scroll into view (set by keyboard selection); the row clears it once it did. */
    var revealStepId: String? by mutableStateOf(null)

    /** A just-added step whose Action field should take the cursor; the step editor clears it once it did. */
    var focusStepActionId: String? by mutableStateOf(null)

    /** Whether the step inspector's Advanced section is open. In memory only, shared by every step. */
    var stepAdvancedExpanded: Boolean by mutableStateOf(false)

    /** What the user chose for a step's Examples section (by step id); a step without a choice follows [examplesExpanded]'s default. */
    private var examplesChoice: Map<String, Boolean> by mutableStateOf(emptyMap())

    /** A step whose Examples section should scroll into view (a screenshot thumbnail was clicked); the section clears it once it did. */
    var examplesRevealStepId: String? by mutableStateOf(null)

    /** Whether the Examples section of [stepId] is open: the user's choice for that step, else open exactly when it has examples. */
    fun examplesExpanded(stepId: String, hasExamples: Boolean): Boolean = examplesChoice[stepId] ?: hasExamples

    fun setExamplesExpanded(stepId: String, open: Boolean) {
        examplesChoice = examplesChoice + (stepId to open)
    }

    var selectedScriptId: String? by mutableStateOf(null)
    var selectedSharedStepId: String? by mutableStateOf(null)

    /** The example the Examples screen's inspector shows, as `<stepId>/<exampleId>`; null (or a gone one) shows the first. */
    var selectedExampleKey: String? by mutableStateOf(null)
    var search: String by mutableStateOf("")

    /** Non-null while the run dialog is open. */
    var runDialog: RunDialogTarget? by mutableStateOf(null)

    /** The suite or case whose recording setup was opened; remains fixed while the user browses elsewhere. */
    var recordDialogTarget: TestsOperationTarget? by mutableStateOf(null)

    /** Opens the review for the recording held by AppState, whose target is fixed at session creation. */
    var recordingReviewOpen: Boolean by mutableStateOf(false)

    /** The selected draft row while review is active; separate from the selected persisted case step. */
    var recordingSelectedRowId: String? by mutableStateOf(null)

    /** Replay choices made in draft review; keyed by row id so reordering does not change them. */
    var recordingReplayRowIds: Set<String> by mutableStateOf(emptySet())

    /** Non-null while the "Improve steps with AI" dialog is open. */
    var improveDialog: ImproveStepsTarget? by mutableStateOf(null)

    /** The run whose report the Runs screen shows; null shows the list of runs. */
    var selectedRunId: String? by mutableStateOf(null)

    /** The Runs screen's status pill and suite filter (null suite: all suites). Kept here so they survive opening a report and coming back. */
    var runsFilter: RunFilter by mutableStateOf(RunFilter.ALL)
    var runsSuiteId: String? by mutableStateOf(null)

    /** The tab of the open run report; the report puts it back to Results when it is left. */
    var reportTab: RunReportTab by mutableStateOf(RunReportTab.Results)

    /** The issue whose detail the Issues screen shows; null shows the list of issues. */
    var selectedIssueId: String? by mutableStateOf(null)

    /** Non-null while the issue dialog is open. */
    var issueDialog: IssueDialogTarget? by mutableStateOf(null)

    /** Where "Back to the step" returns to after a script was created for a step and its editor opened; see [openScriptEditorFor]. */
    var scriptReturn: ScriptReturn? by mutableStateOf(null)

    /** Width of the left navigation column in dp; dragged through the shared HDivider, clamped by [clampTestsNavWidth]. */
    var navWidthDp: Float by mutableStateOf(TESTS_NAV_WIDTH_DEFAULT_DP)

    /** Width of the right inspector column in dp (wide windows only); clamped by [clampTestsInspectorWidth]. */
    var inspectorWidthDp: Float by mutableStateOf(TESTS_INSPECTOR_WIDTH_DEFAULT_DP)

    /** The one tile temporarily maximized on the Tests surface; not persisted across app restarts. */
    var maximizedTile: TestsWorkspaceTileId? by mutableStateOf(null)

    /** The suites whose cases the navigation tree lists. The selected suite is added when it is selected. */
    var expandedSuiteIds: Set<String> by mutableStateOf(emptySet())
}

/** Expands [suiteId] in the navigation tree (a no-op when it already is). */
internal fun TestsViewState.expandSuite(suiteId: String) {
    if (suiteId !in expandedSuiteIds) expandedSuiteIds = expandedSuiteIds + suiteId
}

/** Shows [suiteId]'s Cases tab on the Suites screen: no case open, the suite selected and expanded in the navigation tree. */
internal fun TestsViewState.showSuite(suiteId: String) {
    recordingReviewOpen = false
    nav = TestsNav.Suites
    selectedSuiteId = suiteId
    selectedCaseId = null
    selectedSuiteTab = SuiteTab.Cases
    expandSuite(suiteId)
}

/** Collapses an expanded suite and expands a collapsed one. */
internal fun TestsViewState.toggleSuiteExpanded(suiteId: String) {
    expandedSuiteIds = if (suiteId in expandedSuiteIds) expandedSuiteIds - suiteId else expandedSuiteIds + suiteId
}

/** Selects [stepId] in the step list and closes any case-field editor, so the inspector shows that step. */
internal fun TestsViewState.selectStep(stepId: String) {
    recordingReviewOpen = false
    selectedStepId = stepId
    caseInspectorField = null
}

/** The case-level fields the case screen summarises as chips; each opens its editor in the inspector. */
internal enum class CaseField(val label: String, val inspectorTitle: String) {
    Preconditions("Preconditions", "Preconditions"),
    Instructions("Instructions", "Instructions for the agent"),
    Hooks("Hooks", "Hooks"),
    Tools("Tools", "Tools"),
    Examples("Examples", "Examples"),
}

/** What a summary chip shows: [text], and whether the field is empty (the chip then mutes it). */
internal data class CaseChipValue(val text: String, val empty: Boolean)

internal const val CASE_CHIP_EMPTY = "—"

/** The examples attached to the steps of [case], in step order. */
internal fun caseExampleCount(case: TestCase): Int = case.steps.sumOf { it.examples.size }

/**
 * The chip text of [field] for [case]: the first non-blank line of Preconditions / Instructions, "setup · teardown"
 * counts for Hooks, "All" or "N allowed" for Tools, the example count for Examples. An empty field shows "—".
 */
internal fun caseChipValue(case: TestCase, field: CaseField): CaseChipValue {
    fun firstLine(text: String) = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    val text = when (field) {
        CaseField.Preconditions -> firstLine(case.preconditions)
        CaseField.Instructions -> firstLine(case.instructions)
        CaseField.Hooks -> if (case.setup.isEmpty() && case.teardown.isEmpty()) null else "${case.setup.size} · ${case.teardown.size}"
        CaseField.Tools -> case.allowedTools?.let { "${it.size} allowed" } ?: "All"
        CaseField.Examples -> caseExampleCount(case).takeIf { it > 0 }?.toString()
    }
    return CaseChipValue(text ?: CASE_CHIP_EMPTY, empty = text == null)
}

/** The place a step was edited in, so the script editor opened from it can lead back there. */
internal data class ScriptReturn(
    val nav: TestsNav,
    val suiteId: String?,
    val suiteTab: SuiteTab,
    val caseId: String?,
    val sharedStepId: String?,
    val stepId: String,
)

/** Opens the script editor on [scriptId], remembering the step (and where it is) that asked for it. */
internal fun TestsViewState.openScriptEditorFor(scriptId: String, stepId: String) {
    scriptReturn = ScriptReturn(nav, selectedSuiteId, selectedSuiteTab, selectedCaseId, selectedSharedStepId, stepId)
    selectedScriptId = scriptId
    nav = TestsNav.Scripts
}

/** Leads back to the step that opened the script editor, selected again; does nothing when no step did. */
internal fun TestsViewState.returnFromScriptEditor() {
    val target = scriptReturn ?: return
    scriptReturn = null
    nav = target.nav
    selectedSuiteId = target.suiteId
    selectedSuiteTab = target.suiteTab
    selectedCaseId = target.caseId
    selectedSharedStepId = target.sharedStepId
    selectedStepId = target.stepId
}

internal const val TESTS_NAV_WIDTH_DEFAULT_DP = 248f
internal const val TESTS_NAV_WIDTH_MIN_DP = 180f
internal const val TESTS_NAV_WIDTH_MAX_DP = 420f

/** [current] widened by [deltaDp], kept within the navigation column's bounds. */
internal fun clampTestsNavWidth(current: Float, deltaDp: Float): Float =
    (current + deltaDp).coerceIn(TESTS_NAV_WIDTH_MIN_DP, TESTS_NAV_WIDTH_MAX_DP)

internal const val TESTS_INSPECTOR_WIDTH_DEFAULT_DP = 380f
internal const val TESTS_INSPECTOR_WIDTH_MIN_DP = 300f
internal const val TESTS_INSPECTOR_WIDTH_MAX_DP = 560f

/** [current] widened by [deltaDp], kept within the inspector column's bounds. */
internal fun clampTestsInspectorWidth(current: Float, deltaDp: Float): Float =
    (current + deltaDp).coerceIn(TESTS_INSPECTOR_WIDTH_MIN_DP, TESTS_INSPECTOR_WIDTH_MAX_DP)

/** Below this total width the navigation column collapses to a ☰ toggle. */
internal const val TESTS_NAV_COLLAPSE_BELOW_DP = 600f

/** Below this width for the centre screen plus its inspector, the inspector stacks under the centre content. */
internal const val TESTS_INSPECTOR_STACK_BELOW_DP = 900f

internal fun testsNavCollapsed(totalWidthDp: Float): Boolean = totalWidthDp < TESTS_NAV_COLLAPSE_BELOW_DP

internal fun testsInspectorStacked(centreAndInspectorWidthDp: Float): Boolean = centreAndInspectorWidthDp < TESTS_INSPECTOR_STACK_BELOW_DP

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

    /** True when at least one suite or case is locked. */
    val hasLockedItems: Boolean get() = lockedSuiteIds.isNotEmpty() || lockedCaseIds.isNotEmpty()
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

// ── Edition usage ────────────────────────────────────────────────────

/**
 * How much of a limited edition's allowance the library uses: [suitesUsed] unlocked suites against [suiteCap] (null: no suite limit), and
 * [activeCases] unlocked cases against [caseCapacity] (the per-suite case limit times the suites it applies to; null: no case limit).
 * [totalCases] counts every case, [lockedCases] the ones past the limits.
 */
internal data class TestsEditionUsage(
    val suitesUsed: Int,
    val suiteCap: Int?,
    val activeCases: Int,
    val caseCapacity: Int?,
    val totalCases: Int,
    val lockedCases: Int,
)

/** The usage of [library] under [limits], or null for an unlimited edition (nothing to meter). Pure. */
internal fun testsEditionUsage(library: TestLibrary, limits: EditionLimits): TestsEditionUsage? {
    if (limits.maxSuites == null && limits.maxCasesPerSuite == null) return null
    val activeCases = activeCaseIds(library, limits).size
    val totalCases = library.suites.sumOf { it.cases.size }
    val casePerSuite = limits.maxCasesPerSuite
    val capacity = casePerSuite?.let { perSuite -> perSuite * (limits.maxSuites ?: library.suites.size) }
    return TestsEditionUsage(
        suitesUsed = activeSuiteIds(library, limits).size,
        suiteCap = limits.maxSuites,
        activeCases = activeCases,
        caseCapacity = capacity,
        totalCases = totalCases,
        lockedCases = totalCases - activeCases,
    )
}

/** "Free · 1 of 1 suite · 5 of 5 cases": the edition label and what is used of each limited allowance. */
internal fun editionUsageLine(editionLabel: String, usage: TestsEditionUsage): String = listOfNotNull(
    editionLabel,
    usage.suiteCap?.let { "${usage.suitesUsed} of $it ${if (it == 1) "suite" else "suites"}" },
    usage.caseCapacity?.let { "${usage.activeCases} of $it ${if (it == 1) "case" else "cases"}" },
).joinToString(" · ")

/**
 * The inline notice above a suite's case table when some of its cases are locked: "Cases 6–8 are read-only on Free. You can still view,
 * export, reorder and delete them." Null when no case of the suite is locked, or when the whole suite is (its own read-only notice covers that).
 */
internal fun lockedCasesNoticeText(suite: TestSuite, limits: TestsLimitsUiState, editionLabel: String): String? {
    if (limits.isSuiteLocked(suite.id)) return null
    val positions = suite.cases.mapIndexedNotNull { index, case -> (index + 1).takeIf { limits.isCaseLocked(case.id) } }
    if (positions.isEmpty() || positions.size == suite.cases.size) return null
    val single = positions.size == 1
    return "${if (single) "Case" else "Cases"} ${caseNumberRanges(positions)} ${if (single) "is" else "are"} read-only on $editionLabel. " +
        "You can still view, export, reorder and delete ${if (single) "it" else "them"}."
}

/** 1-based case [positions] as ranges: "7", "6–8", "2, 4–5" (consecutive numbers collapse, order and duplicates are ignored). */
internal fun caseNumberRanges(positions: List<Int>): String {
    val sorted = positions.distinct().sorted()
    val parts = mutableListOf<String>()
    var start = 0
    while (start < sorted.size) {
        var end = start
        while (end + 1 < sorted.size && sorted[end + 1] == sorted[end] + 1) end++
        parts += if (end == start) "${sorted[start]}" else "${sorted[start]}–${sorted[end]}"
        start = end + 1
    }
    return parts.joinToString(", ")
}

// ── Edit access ──────────────────────────────────────────────────────

internal const val LIBRARY_READ_ONLY_MESSAGE =
    "The test library could not be loaded safely and is read-only here. Check its version, file size and access permissions before editing it."
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

/** One suite of the navigation tree: the cases listed under it and whether a search forces it open. */
internal data class NavSuiteEntry(val suite: TestSuite, val cases: List<TestCase>, val forceExpanded: Boolean)

/** Cases whose name, description or instructions contain [query] (ignoring case). */
private fun TestCase.matchesQuery(q: String): Boolean =
    name.lowercase().contains(q) || description.lowercase().contains(q) || instructions.lowercase().contains(q)

/**
 * The navigation tree for [query]. A blank query lists every suite with all its cases. Otherwise a suite is listed when it
 * matches ([filterSuites]) or one of its cases does; its cases are then only the matching ones, and a suite with a matching
 * case is [NavSuiteEntry.forceExpanded] so the match is visible.
 */
internal fun filterNavTree(suites: List<TestSuite>, query: String): List<NavSuiteEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return suites.map { NavSuiteEntry(it, it.cases, forceExpanded = false) }
    val suiteMatches = filterSuites(suites, query).mapTo(HashSet()) { it.id }
    return suites.mapNotNull { suite ->
        val cases = suite.cases.filter { it.matchesQuery(q) }
        if (suite.id in suiteMatches || cases.isNotEmpty()) NavSuiteEntry(suite, cases, forceExpanded = cases.isNotEmpty()) else null
    }
}

/** The navigation footer's total: "1 case" / "5 cases". */
internal fun navCaseCountLabel(count: Int): String = if (count == 1) "1 case" else "$count cases"

// ── Step badges, numbers and key=value text ──────────────────────────

/** The short label of a check's kind, shown as a badge on a collapsed step row. */
internal fun checkKindLabel(check: StepCheck): String = when (check) {
    is StepCheck.LogAppears -> "Log appears"
    is StepCheck.LogAbsent -> "Log absent"
    is StepCheck.ScreenJudge -> "Screen"
    is StepCheck.ScriptResult -> "Script"
    is StepCheck.AskJudge -> "Judge"
    is StepCheck.DeviceState -> "Device state"
}

/** One badge label per distinct check kind of [step], in first-seen order, with a count when a kind repeats. */
internal fun stepCheckBadges(step: TestStep): List<String> =
    step.checks.groupBy { checkKindLabel(it) }.map { (label, checks) -> if (checks.size > 1) "$label ×${checks.size}" else label }

/** The label next to a step row's dot: the kind of its only check, "N checks" for several, null for none. */
internal fun stepChecksSummary(step: TestStep): String? = when (step.checks.size) {
    0 -> null
    1 -> checkKindLabel(step.checks.single())
    else -> "${step.checks.size} checks"
}

/** The id of the step selected in [steps]: [selectedId] when it is one of them, else the first step, else null. */
internal fun effectiveSelectedStepId(steps: List<TestStep>, selectedId: String?): String? =
    steps.firstOrNull { it.id == selectedId }?.id ?: steps.firstOrNull()?.id

/** The step to select once [deletedId] is gone: the one after it, else the one before it, else null. */
internal fun stepSelectionAfterDelete(steps: List<TestStep>, deletedId: String): String? {
    val index = steps.indexOfFirst { it.id == deletedId }
    if (index < 0) return null
    return (steps.getOrNull(index + 1) ?: steps.getOrNull(index - 1))?.id
}

/** The step [delta] places from [currentId], clamped to the list ends; the first step when [currentId] is not in it. */
internal fun stepSelectionMovedBy(steps: List<TestStep>, currentId: String?, delta: Int): String? {
    if (steps.isEmpty()) return null
    val index = steps.indexOfFirst { it.id == currentId }
    if (index < 0) return steps.first().id
    return steps[(index + delta).coerceIn(0, steps.lastIndex)].id
}

/**
 * The case [delta] places from [current] in [caseIds], clamped to the ends. With no (or an unknown) current case: the first,
 * or the last for a negative [delta].
 */
internal fun caseSelectionMovedBy(caseIds: List<String>, current: String?, delta: Int): String? {
    if (caseIds.isEmpty()) return null
    val index = caseIds.indexOf(current)
    if (index < 0) return if (delta < 0) caseIds.last() else caseIds.first()
    return caseIds[(index + delta).coerceIn(0, caseIds.lastIndex)]
}

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
    val stepScripts = steps.count { it.script?.scriptId == scriptId }
    return hooks + checks + stepScripts
}

/** How many suite and case hooks run shared step [sharedStepId]. */
internal fun sharedStepUsageCount(library: TestLibrary, sharedStepId: String): Int =
    library.suites.flatMap { it.allHooks() }.count { it is HookItem.Shared && it.sharedStepId == sharedStepId }

// ── Suite screen ─────────────────────────────────────────────────────

/** Below this content width (dp) the suite's case table becomes two-line rows without the column header. */
internal const val SUITE_COMPACT_BELOW_DP = 720f

internal fun suiteCasesCompact(contentWidthDp: Float): Boolean = contentWidthDp < SUITE_COMPACT_BELOW_DP

/**
 * Opens the run dialog on a re-run of [run]'s failed, blocked, errored and interrupted cases (its own configuration, narrowed to those
 * cases). Says why in the banner when there is nothing to re-run. Used by the report and by the suite header.
 */
internal fun TestsUi.openRerunDialog(run: TestRun) {
    rerunFailedCasesConfig(run).onSuccess { config ->
        view.runDialog = RunDialogTarget(run.suite.id, initialCaseIds = config.caseIds.orEmpty().toSet(), initialConfig = config)
    }.onFailure { info(it.message ?: "Failed cases cannot be re-run.") }
}

/** "Cases (3)", "Health", "Runs (2)", "Settings". The run count is null while the suite's run history is still loading (no "Runs (0)" flash). */
internal fun suiteTabLabel(tab: SuiteTab, caseCount: Int, runCount: Int?): String = when (tab) {
    SuiteTab.Cases -> "${tab.label} ($caseCount)"
    SuiteTab.Health -> tab.label
    SuiteTab.Runs -> if (runCount == null) tab.label else "${tab.label} ($runCount)"
    SuiteTab.Settings -> tab.label
}

/** What the suite screen's "last run" line shows. */
internal sealed interface SuiteLastRun {
    /** The suite's run history is still being read: never claim "not run yet" at this point. */
    data object Loading : SuiteLastRun

    /** The history is loaded and holds no finished run. */
    data object NeverRun : SuiteLastRun

    data class Ran(
        val runId: String,
        val status: RunStatus,
        val momentMs: Long,
        val caseCount: Int,
        val laneCount: Int,
        val passedSteps: Int,
        val totalSteps: Int,
    ) : SuiteLastRun
}

/** A finished run is shown even while the rest of the history loads; with none, [loaded] decides between Loading and NeverRun. */
internal fun suiteLastRun(loaded: Boolean, latestFinished: TestRun?): SuiteLastRun {
    if (latestFinished == null) return if (loaded) SuiteLastRun.NeverRun else SuiteLastRun.Loading
    val summary = latestFinished.summary()
    return SuiteLastRun.Ran(
        runId = latestFinished.id,
        status = latestFinished.status,
        momentMs = latestFinished.finishedAt ?: latestFinished.createdAt,
        caseCount = summary.caseCount,
        laneCount = summary.laneCount,
        passedSteps = summary.passedSteps,
        totalSteps = summary.totalSteps,
    )
}

/**
 * Run number by run id for display ("#3"): the oldest run is 1, ordered by creation time then id. Display only: it is not stored, so
 * numbers shift when an older run is deleted, and the same run can have another number in a list that holds a different set of runs.
 */
internal fun runOrdinals(summaries: List<RunSummary>): Map<String, Int> {
    val ordinals = LinkedHashMap<String, Int>()
    summaries.distinctBy { it.id }.sortedWith(compareBy<RunSummary> { it.createdAt }.thenBy { it.id })
        .forEachIndexed { index, summary -> ordinals[summary.id] = index + 1 }
    return ordinals
}

/** The latest finished run that ran a case: [status] is the case's worst status in it, [durationMs] its time (null when not recorded). */
internal data class CaseLastRun(val runId: String, val momentMs: Long, val durationMs: Long?, val status: CaseStatus?)

/** The newest run of [matrix] that included [caseId] (the newest column whose cell is not NOT_IN_RUN), or null when none did. */
internal fun caseLastRun(matrix: CaseRunMatrix, caseId: String): CaseLastRun? {
    val cells = matrix.rows[caseId]?.cells ?: return null
    val index = cells.indexOfLast { it != CaseRunCell.NOT_IN_RUN }
    val column = matrix.columns.getOrNull(index) ?: return null
    val outcome = column.cases[caseId]
    return CaseLastRun(column.runId, column.finishedAt ?: column.createdAt, outcome?.durationMs, outcome?.status)
}

/**
 * The status of each step of [caseId] in [run] (by step id), the worst over lanes and iterations; a step with no result is absent, so
 * a lookup gives null for "not run". Hook steps are not the case's steps and are left out. Null [run] gives an empty map.
 */
internal fun latestStepStatuses(run: TestRun?, caseId: String): Map<String, StepStatus?> {
    if (run == null) return emptyMap()
    val statuses = LinkedHashMap<String, StepStatus?>()
    for (lane in run.lanes) {
        for (result in lane.cases) {
            if (result.caseId != caseId) continue
            for (step in result.steps) {
                if (step.setup) continue
                val previous = statuses[step.stepId]
                statuses[step.stepId] = if (previous == null) step.status else worseStepStatus(previous, step.status)
            }
        }
    }
    return statuses
}

/** The words of a "last runs" square for assistive tech and tests: "#21 passed" ("passed" alone when the run has no number). */
internal fun lastRunCellDescription(ordinal: Int?, cell: CaseRunCell): String {
    val word = when (cell) {
        CaseRunCell.PASSED -> "passed"
        CaseRunCell.FAILED -> "failed"
        CaseRunCell.ERROR -> "error"
        CaseRunCell.BLOCKED -> "blocked"
        CaseRunCell.OTHER -> "not completed"
        CaseRunCell.NOT_IN_RUN -> "not in run"
    }
    return if (ordinal == null) word else "#$ordinal $word"
}

private fun worseStepStatus(left: StepStatus, right: StepStatus): StepStatus {
    fun rank(status: StepStatus): Int = when (status) {
        StepStatus.ERROR -> 6
        StepStatus.FAIL -> 5
        StepStatus.TIMEOUT -> 4
        StepStatus.BLOCKED -> 3
        StepStatus.SKIPPED -> 2
        StepStatus.PASS -> 1
    }
    return if (rank(left) >= rank(right)) left else right
}

/** "3 cases · 1 lane · 12 of 12 steps passed". */
internal fun SuiteLastRun.Ran.detail(): String =
    "$caseCount case${if (caseCount == 1) "" else "s"} · $laneCount lane${if (laneCount == 1) "" else "s"} · $passedSteps of $totalSteps steps passed"

/** The lanes of a case's latest result that actually ran it (a lane the case is absent from is left out). */
internal fun SuiteCaseHistory?.ranLanes(): List<SuiteCaseLaneHistory> = this?.lanes.orEmpty().filter { it.status != null }

/**
 * One tooltip line for a lane's result: "Pixel 8 · Pass · 48s ×2" ("Pixel 8 · Not run" for a lane that did not run the case).
 * [laneName] is resolved by the caller (profile or device).
 */
internal fun laneResultLine(laneName: String, status: CaseStatus?, durationMs: Long, repeatCount: Int): String {
    if (status == null) return "$laneName · Not run"
    val repeats = if (repeatCount > 1) " ×$repeatCount" else ""
    return "$laneName · ${status.label()} · ${formatDuration(durationMs)}$repeats"
}

/**
 * The issues linked to a case: the ones its latest run raised plus the stored issues whose source is that case, in that order,
 * without duplicates. The first is what the Issues column opens.
 */
internal fun caseIssueIds(case: SuiteCaseHistory?, caseId: String, suiteId: String, issues: List<IssueRecord>): List<String> =
    (case?.issueIds.orEmpty() + issues.filter { it.source.suiteId == suiteId && it.source.caseId == caseId }.map { it.id }).distinct()

// ── Run history ──────────────────────────────────────────────────────

private val DAY_LABEL_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.ENGLISH)
private val DAY_LABEL_YEAR_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy", Locale.ENGLISH)

/** The runs of one calendar [day] (in the zone they were grouped for), newest first, under their [label]. */
internal data class RunDayGroup(val day: LocalDate, val label: String, val runs: List<RunSummary>)

/** "Today", "Yesterday", "Friday, Oct 9" ("Friday, Oct 9, 2025" in another calendar year); [today] is the current date in the same zone. */
internal fun runDayLabel(day: LocalDate, today: LocalDate): String = when {
    day == today -> "Today"
    day == today.minusDays(1) -> "Yesterday"
    day.year == today.year -> DAY_LABEL_FORMAT.format(day)
    else -> DAY_LABEL_YEAR_FORMAT.format(day)
}

/**
 * [summaries] split by the calendar day of their creation in [zone] relative to [nowMs]: newest day first, and inside a day
 * newest run first (ties by id, descending, so the order is stable). A run created exactly at midnight belongs to the new day.
 */
internal fun groupRunsByDay(summaries: List<RunSummary>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<RunDayGroup> {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return summaries
        .sortedWith(compareByDescending<RunSummary> { it.createdAt }.thenByDescending { it.id })
        .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
        .map { (day, runs) -> RunDayGroup(day, runDayLabel(day, today), runs) }
        .sortedByDescending { it.day }
}

/** What a bulk "Delete N shown runs" did: how many went, how many could not, and the first reason. */
internal class RunsDeletion(val deleted: Int, val failed: Int, val firstError: String?)

/** Queued and running runs: their folders are being written, so they are never deleted. */
internal fun RunStatus.isActiveRun(): Boolean = this == RunStatus.QUEUED || this == RunStatus.RUNNING

/**
 * The runs among [shown] a bulk delete may remove: never one that is queued or running, by its status or because its id is in
 * [activeRunIds] (the runs of this launch that have not finished). Order is kept.
 */
internal fun deletableRuns(shown: List<RunSummary>, activeRunIds: Set<String> = emptySet()): List<RunSummary> =
    shown.filter { !it.status.isActiveRun() && it.id !in activeRunIds }

/** The status pills of the Runs screen. Queued and running runs only appear under [ALL]. */
internal enum class RunFilter(val label: String) {
    ALL("All"),
    FAILED("Failed"),
    PASSED("Passed"),
    CANCELLED("Cancelled"),
    ;

    fun matches(status: RunStatus): Boolean = when (this) {
        ALL -> true
        FAILED -> status == RunStatus.FAILED || status == RunStatus.ERROR
        PASSED -> status == RunStatus.PASSED
        CANCELLED -> status == RunStatus.CANCELLED
    }
}

/** How many of [summaries] each [RunFilter] matches (zero counts are present too). */
internal fun runFilterCounts(summaries: List<RunSummary>): Map<RunFilter, Int> =
    RunFilter.entries.associateWith { filter -> summaries.count { filter.matches(it.status) } }

/** [summaries] of [suiteId] (null: every suite) that match [filter], order kept. */
internal fun filterRuns(summaries: List<RunSummary>, suiteId: String?, filter: RunFilter): List<RunSummary> =
    summaries.filter { (suiteId == null || it.suiteId == suiteId) && filter.matches(it.status) }

/** The fraction of a run's steps that passed, 0..1 (0 while no step is known yet). */
internal fun RunSummary.passFraction(): Float = if (totalSteps <= 0) 0f else (passedSteps.toFloat() / totalSteps).coerceIn(0f, 1f)

/** "3 cases" / "1 case" / "2 lanes" / "1 lane". */
internal fun countNoun(count: Int, noun: String): String = "$count $noun${if (count == 1) "" else "s"}"
