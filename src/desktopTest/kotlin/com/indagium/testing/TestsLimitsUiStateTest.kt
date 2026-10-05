package com.indagium.testing

import com.indagium.edition.EditionLimits
import com.indagium.testing.limits.FREE_EDITION_LIMIT_HINT
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.moveById
import com.indagium.testing.model.newCheckId
import com.indagium.testing.model.newStepId
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.TestLibraryStore
import com.indagium.ui.LIBRARY_READ_ONLY_MESSAGE
import com.indagium.ui.SUITE_READ_ONLY_MESSAGE
import com.indagium.ui.TESTS_NAV_WIDTH_MAX_DP
import com.indagium.ui.TESTS_NAV_WIDTH_MIN_DP
import com.indagium.ui.caseEditAccess
import com.indagium.ui.checkKindLabel
import com.indagium.ui.clampTestsNavWidth
import com.indagium.ui.filterSuites
import com.indagium.ui.formatKeyValueLines
import com.indagium.ui.libraryEditAccess
import com.indagium.ui.limitHintFor
import com.indagium.ui.parseKeyValueLines
import com.indagium.ui.parseWholeNumber
import com.indagium.ui.scriptUsageCount
import com.indagium.ui.sharedStepUsageCount
import com.indagium.ui.stepCheckBadges
import com.indagium.ui.suiteEditAccess
import com.indagium.ui.suiteExportFileName
import com.indagium.ui.testsLimitsUiState
import com.indagium.ui.uniqueIdentifier
import com.indagium.ui.uniqueName
import com.indagium.ui.userMessage
import com.indagium.ui.userWarnings
import com.indagium.ui.withoutExample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure state behind the Tests workspace: which controls the edition limits enable, and the small helpers its screens use. */
class TestsLimitsUiStateTest {
    private val free = EditionLimits.FREE
    private val unlimited = EditionLimits.UNLIMITED

    private fun library(vararg suites: TestSuite) = TestLibrary(suites = suites.toList())

    @Test
    fun unlimitedEditionEnablesEverythingAndLocksNothing() {
        val lib = library(suiteWithCases("A", 8), suiteWithCases("B", 2))

        val ui = testsLimitsUiState(lib, unlimited)

        assertTrue(ui.canCreateSuite)
        assertTrue(lib.suites.all { ui.canCreateCase(it.id) && ui.canDuplicateSuite(it.id) })
        assertTrue(ui.lockedSuiteIds.isEmpty() && ui.lockedCaseIds.isEmpty())
        assertFalse(ui.hasLockedItems)
        assertNull(ui.banner)
    }

    @Test
    fun freeEditionAtTheLimitDisablesCreationAndDuplication() {
        val suite = suiteWithCases("One", 5)
        val ui = testsLimitsUiState(library(suite), free)

        assertFalse(ui.canCreateSuite, "a second suite is over the limit")
        assertFalse(ui.canCreateCase(suite.id), "a sixth case is over the limit")
        assertFalse(ui.canDuplicateSuite(suite.id), "a duplicate would be a second suite")
        assertFalse(suite.cases.any { ui.canDuplicateCase(it.id) }, "a duplicate would be a sixth case")
        assertEquals(FREE_EDITION_LIMIT_HINT, ui.hint)
        assertNull(ui.banner, "nothing is locked yet, so there is no banner")
    }

    @Test
    fun freeEditionBelowTheLimitStillAllowsCases() {
        val suite = suiteWithCases("One", 2)
        val ui = testsLimitsUiState(library(suite), free)

        assertTrue(ui.canCreateCase(suite.id))
        assertTrue(suite.cases.all { ui.canDuplicateCase(it.id) })
        assertFalse(ui.canCreateSuite)
    }

    @Test
    fun anOverLimitLibraryLocksTheLaterSuitesAndCases() {
        val first = suiteWithCases("First", 7)
        val second = suiteWithCases("Second", 2)

        val ui = testsLimitsUiState(library(first, second), free)

        assertFalse(ui.isSuiteLocked(first.id))
        assertTrue(ui.isSuiteLocked(second.id))
        assertEquals(first.cases.drop(5).map { it.id }.toSet() + second.cases.map { it.id }, ui.lockedCaseIds)
        assertFalse(ui.canCreateCase(second.id), "a locked suite takes no new case")
        assertFalse(ui.canDuplicateCase(second.cases.first().id))
        assertTrue(ui.hasLockedItems)
        val banner = assertNotNull(ui.banner)
        assertTrue(banner.contains(FREE_EDITION_LIMIT_HINT) && banner.contains("1 suite(s)") && banner.contains("4 case(s)"), banner)
    }

    @Test
    fun reorderingChoosesWhichSuiteAndCasesStayActive() {
        val first = suiteWithCases("First", 6)
        val second = suiteWithCases("Second", 1)
        val lib = library(first, second)
        val reordered = lib.copy(suites = lib.suites.moveById(second.id, 0) { it.id })

        val before = testsLimitsUiState(lib, free)
        val after = testsLimitsUiState(reordered, free)

        assertTrue(before.isSuiteLocked(second.id) && !before.isSuiteLocked(first.id))
        assertTrue(after.isSuiteLocked(first.id) && !after.isSuiteLocked(second.id))
        assertTrue(after.isCaseLocked(first.cases.first().id), "every case of a locked suite is locked")
        assertFalse(after.isCaseLocked(second.cases.first().id))
    }

    @Test
    fun theHintNamesTheActualLimitsOfANonFreeCap() {
        assertEquals(FREE_EDITION_LIMIT_HINT, limitHintFor(free))
        assertTrue(limitHintFor(EditionLimits(maxSuites = 3, maxCasesPerSuite = null)).contains("3 suite(s) / unlimited case(s)"))
    }

    @Test
    fun editAccessFollowsLibrarySuiteAndCaseState() {
        val first = suiteWithCases("First", 6)
        val second = suiteWithCases("Second", 1)
        val lib = library(first, second)
        val limits = testsLimitsUiState(lib, free)

        assertTrue(suiteEditAccess(lib, first, limits).editable)
        assertTrue(caseEditAccess(lib, first, first.cases.first().id, limits).editable)
        val lockedCase = caseEditAccess(lib, first, first.cases.last().id, limits)
        assertFalse(lockedCase.editable)
        assertTrue(lockedCase.reason!!.contains(FREE_EDITION_LIMIT_HINT))
        val lockedSuite = suiteEditAccess(lib, second, limits)
        assertFalse(lockedSuite.editable)
        assertFalse(caseEditAccess(lib, second, second.cases.first().id, limits).editable)

        val newerSuite = lib.copy(suites = listOf(first.copy(readOnly = true), second))
        assertEquals(SUITE_READ_ONLY_MESSAGE, suiteEditAccess(newerSuite, newerSuite.suites.first(), limits).reason)
        val newerLibrary = lib.copy(readOnly = true)
        assertEquals(LIBRARY_READ_ONLY_MESSAGE, suiteEditAccess(newerLibrary, first, limits).reason)
        assertFalse(libraryEditAccess(newerLibrary).editable)
        assertTrue(libraryEditAccess(lib).editable)
    }

    @Test
    fun storeResultsBecomeUserMessages() {
        assertNull(StoreResult.Ok(Unit).userMessage())
        assertEquals("nope", StoreResult.Invalid("nope").userMessage())
        assertEquals("No suite with id 'x'.", StoreResult.NotFound("suite", "x").userMessage())
        assertEquals(listOf("careful"), StoreResult.Ok(Unit, listOf("careful")).userWarnings())
        assertTrue(StoreResult.Invalid("x").userWarnings().isEmpty())
        val store = TestLibraryStore(tempTestingDir(), limits = { free })
        store.createSuite("One")
        val refused = store.createSuite("Two")
        assertTrue(refused.userMessage()!!.contains(FREE_EDITION_LIMIT_HINT))
    }

    @Test
    fun uniqueNamesSkipTakenOnesIgnoringCase() {
        assertEquals("New case", uniqueName("New case", emptyList()))
        assertEquals("New case 2", uniqueName("New case", listOf("new CASE")))
        assertEquals("New case 3", uniqueName("New case", listOf("New case", "New case 2")))
        assertEquals("new_script", uniqueIdentifier("new_script", emptyList()))
        assertEquals("new_script_3", uniqueIdentifier("new_script", listOf("new_script", "new_script_2")))
    }

    @Test
    fun searchMatchesNameDescriptionPackageAndTags() {
        val a = suiteWithCases("Login flow", 0).copy(tags = listOf("smoke"))
        val b = suiteWithCases("Settings", 0).copy(description = "Dark mode toggles", targetPackage = "com.example.settings")
        val suites = listOf(a, b)

        assertEquals(suites, filterSuites(suites, "  "))
        assertEquals(listOf(a), filterSuites(suites, "LOGIN"))
        assertEquals(listOf(a), filterSuites(suites, "smoke"))
        assertEquals(listOf(b), filterSuites(suites, "dark"))
        assertEquals(listOf(b), filterSuites(suites, "example.settings"))
        assertTrue(filterSuites(suites, "zzz").isEmpty())
    }

    @Test
    fun stepBadgesSummariseCheckKinds() {
        val step = TestStep(
            newStepId(), "x",
            checks = listOf(
                StepCheck.LogAppears(newCheckId(), regex = "a"),
                StepCheck.LogAppears(newCheckId(), regex = "b"),
                StepCheck.AskJudge(newCheckId(), "ok?"),
            ),
        )

        assertEquals(listOf("Log appears ×2", "Judge"), stepCheckBadges(step))
        assertTrue(stepCheckBadges(TestStep(newStepId(), "none")).isEmpty())
        assertEquals("Screen", checkKindLabel(StepCheck.ScreenJudge(newCheckId(), "t")))
    }

    @Test
    fun wholeNumbersAreParsedWithinBounds() {
        assertEquals(5L, parseWholeNumber(" 5 ", 1, 10))
        assertNull(parseWholeNumber("11", 1, 10))
        assertNull(parseWholeNumber("0", 1, 10))
        assertNull(parseWholeNumber("1.5", 1, 10))
        assertNull(parseWholeNumber("", 1, 10))
    }

    @Test
    fun keyValueTextRoundTripsAndReportsBadLines() {
        val map = linkedMapOf("package_name" to "com.example", "count" to "3")
        assertEquals("package_name=com.example\ncount=3", formatKeyValueLines(map))

        val (parsed, bad) = parseKeyValueLines("package_name=com.example\n\n count = 3 \nnonsense\n=novalue\nurl=http://a/b?x=1")

        assertEquals(mapOf("package_name" to "com.example", "count" to "3", "url" to "http://a/b?x=1"), parsed)
        assertEquals(listOf("nonsense", "=novalue"), bad)
        assertEquals(mapOf("a" to "2"), parseKeyValueLines("a=1\na=2").first)
    }

    @Test
    fun usageCountsSeeHooksAndChecksInSuitesAndSharedSteps() {
        val script = sampleScript()
        val shared = sampleSharedStep()
        val suite = fullSuite(script, shared)
        val lib = TestLibrary(suites = listOf(suite), scripts = listOf(script), sharedSteps = listOf(shared))

        // fullSuite: the suite's setup and teardown hooks and the first case's teardown use the script (3), two script-result
        // checks do too (2); the suite's setup and the first case's setup run the shared step (2).
        assertEquals(3 + 2, scriptUsageCount(lib, script.id))
        assertEquals(2, sharedStepUsageCount(lib, shared.id))
        assertEquals(0, scriptUsageCount(lib, "script-unknown"))
        val sharedWithCheck = shared.copy(steps = listOf(TestStep(newStepId(), "x", checks = listOf(StepCheck.ScriptResult(newCheckId(), script.id)))))
        val extra = lib.copy(sharedSteps = listOf(sharedWithCheck))
        assertEquals(3 + 2 + 1, scriptUsageCount(extra, script.id))
        assertTrue(suite.setup.any { it is HookItem.Shared })
    }

    @Test
    fun removingAnExampleClearsTheScreenChecksThatReferencedIt() {
        val step = fullSuite().cases.first().steps.first()
        val golden = step.examples.first()

        val without = step.withoutExample(golden.id)

        assertEquals(1, without.examples.size)
        assertNull(without.checks.filterIsInstance<StepCheck.ScreenJudge>().single().exampleRef)
        assertEquals(step.checks.size, without.checks.size, "the check itself stays")
    }

    @Test
    fun navWidthIsClamped() {
        assertEquals(TESTS_NAV_WIDTH_MAX_DP, clampTestsNavWidth(400f, 100f))
        assertEquals(TESTS_NAV_WIDTH_MIN_DP, clampTestsNavWidth(190f, -100f))
        assertEquals(260f, clampTestsNavWidth(250f, 10f))
    }

    @Test
    fun exportFileNamesAreSafe() {
        assertEquals("Login_flow.json", suiteExportFileName("Login flow"))
        assertEquals("test_suite.json", suiteExportFileName("   "))
        assertEquals("a_b.json", suiteExportFileName("a/b"))
        assertEquals("test_suite.json", suiteExportFileName("../.."))
        assertTrue(suiteExportFileName("x".repeat(500)).length <= 70)
    }
}
