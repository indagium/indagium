package com.indagium.testing

import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestSuite
import com.indagium.ui.TESTS_INSPECTOR_WIDTH_DEFAULT_DP
import com.indagium.ui.TESTS_INSPECTOR_WIDTH_MAX_DP
import com.indagium.ui.TESTS_INSPECTOR_WIDTH_MIN_DP
import com.indagium.ui.TestsViewState
import com.indagium.ui.clampTestsInspectorWidth
import com.indagium.ui.expandSuite
import com.indagium.ui.filterNavTree
import com.indagium.ui.navCaseCountLabel
import com.indagium.ui.testsInspectorStacked
import com.indagium.ui.testsNavCollapsed
import com.indagium.ui.toggleSuiteExpanded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure parts of the Tests workspace shell: the nav tree's search, expansion state and the layout breakpoints. */
class TestsNavTreeTest {
    private val login = TestCase("c1", "Login flow", description = "Signs in with a saved account")
    private val checkout = TestCase("c2", "Checkout")
    private val tour = TestCase("c3", "Settings tour")
    private val alpha = TestSuite("s1", "Alpha", cases = listOf(login, checkout))
    private val beta = TestSuite("s2", "Beta", description = "Nightly", cases = listOf(tour))
    private val suites = listOf(alpha, beta)

    @Test
    fun aBlankQueryListsEverySuiteWithAllItsCasesAndForcesNothingOpen() {
        val tree = filterNavTree(suites, "  ")

        assertEquals(listOf("s1", "s2"), tree.map { it.suite.id })
        assertEquals(listOf(listOf(login, checkout), listOf(tour)), tree.map { it.cases })
        assertTrue(tree.none { it.forceExpanded })
    }

    @Test
    fun aCaseMatchListsItsSuiteWithOnlyTheMatchingCasesAndOpensIt() {
        val tree = filterNavTree(suites, "CHECK")

        assertEquals(listOf("s1"), tree.map { it.suite.id })
        assertEquals(listOf(checkout), tree.single().cases)
        assertTrue(tree.single().forceExpanded)
    }

    @Test
    fun aCaseIsAlsoFoundByItsDescription() {
        assertEquals(listOf(login), filterNavTree(suites, "saved account").single().cases)
    }

    @Test
    fun aSuiteOnlyMatchStaysCollapsedWithoutCases() {
        val entry = filterNavTree(suites, "nightly").single()

        assertEquals("s2", entry.suite.id)
        assertTrue(entry.cases.isEmpty())
        assertFalse(entry.forceExpanded)
    }

    @Test
    fun noMatchListsNothing() {
        assertTrue(filterNavTree(suites, "zzz").isEmpty())
    }

    @Test
    fun expandingAndTogglingSuitesUpdatesTheExpandedSet() {
        val view = TestsViewState()
        view.expandSuite("s1")
        view.expandSuite("s1")
        assertEquals(setOf("s1"), view.expandedSuiteIds)

        view.toggleSuiteExpanded("s2")
        assertEquals(setOf("s1", "s2"), view.expandedSuiteIds)
        view.toggleSuiteExpanded("s1")
        assertEquals(setOf("s2"), view.expandedSuiteIds)
    }

    @Test
    fun theInspectorWidthIsClampedAndStartsAtItsDefault() {
        assertEquals(TESTS_INSPECTOR_WIDTH_DEFAULT_DP, TestsViewState().inspectorWidthDp)
        assertEquals(TESTS_INSPECTOR_WIDTH_MIN_DP, clampTestsInspectorWidth(TESTS_INSPECTOR_WIDTH_DEFAULT_DP, -1_000f))
        assertEquals(TESTS_INSPECTOR_WIDTH_MAX_DP, clampTestsInspectorWidth(TESTS_INSPECTOR_WIDTH_DEFAULT_DP, 1_000f))
        assertEquals(400f, clampTestsInspectorWidth(380f, 20f))
    }

    @Test
    fun theBreakpointsCollapseTheNavAndStackTheInspector() {
        assertTrue(testsNavCollapsed(599f))
        assertFalse(testsNavCollapsed(600f))
        assertTrue(testsInspectorStacked(899f))
        assertFalse(testsInspectorStacked(900f))
    }

    @Test
    fun theFooterCountsCases() {
        assertEquals("0 cases", navCaseCountLabel(0))
        assertEquals("1 case", navCaseCountLabel(1))
        assertEquals("12 cases", navCaseCountLabel(12))
    }
}
