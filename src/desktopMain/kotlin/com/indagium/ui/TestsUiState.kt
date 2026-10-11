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
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestCase
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

    data object AgentExamples : TestsNav
}

/** Tabs within one suite; stored in workspace state so switching navigation does not reset the active tab. */
internal enum class SuiteTab(val label: String) {
    Cases("Cases"),
    Runs("Runs"),
    SetupTeardown("Setup & teardown"),
    AgentInstructions("Agent instructions"),
    Variables("Variables"),
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

    /** Whether the step inspector's Examples / Advanced sections are open. In memory only, shared by every step. */
    var stepExamplesExpanded: Boolean by mutableStateOf(false)
    var stepAdvancedExpanded: Boolean by mutableStateOf(false)
    var selectedScriptId: String? by mutableStateOf(null)
    var selectedSharedStepId: String? by mutableStateOf(null)
    var search: String by mutableStateOf("")

    /** Non-null while the run dialog is open. */
    var runDialog: RunDialogTarget? by mutableStateOf(null)

    /** Non-null while the "Improve steps with AI" dialog is open. */
    var improveDialog: ImproveStepsTarget? by mutableStateOf(null)

    /** The run whose report the Runs screen shows; null shows the list of runs. */
    var selectedRunId: String? by mutableStateOf(null)

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

    /** The suites whose cases the navigation tree lists. The selected suite is added when it is selected. */
    var expandedSuiteIds: Set<String> by mutableStateOf(emptySet())
}

/** Expands [suiteId] in the navigation tree (a no-op when it already is). */
internal fun TestsViewState.expandSuite(suiteId: String) {
    if (suiteId !in expandedSuiteIds) expandedSuiteIds = expandedSuiteIds + suiteId
}

/** Collapses an expanded suite and expands a collapsed one. */
internal fun TestsViewState.toggleSuiteExpanded(suiteId: String) {
    expandedSuiteIds = if (suiteId in expandedSuiteIds) expandedSuiteIds - suiteId else expandedSuiteIds + suiteId
}

/** Selects [stepId] in the step list and closes any case-field editor, so the inspector shows that step. */
internal fun TestsViewState.selectStep(stepId: String) {
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

/** The badge that says who performs [step] when it is not the AI agent, else null. */
internal fun stepKindBadge(step: TestStep): String? = if (step.kind == StepKind.AGENT) null else stepKindLabel(step.kind)

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
