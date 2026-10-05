package com.indagium.testing

import com.indagium.edition.EditionLimits
import com.indagium.testing.limits.FREE_EDITION_LIMIT_HINT
import com.indagium.testing.limits.LimitDecision
import com.indagium.testing.limits.LimitKind
import com.indagium.testing.limits.LimitOperation
import com.indagium.testing.limits.activeCaseIds
import com.indagium.testing.limits.activeSuiteIds
import com.indagium.testing.limits.decide
import com.indagium.testing.limits.isCaseLocked
import com.indagium.testing.limits.isSuiteLocked
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestSuite
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.TestLibraryStore
import com.indagium.testing.store.encodeSuiteFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TestLimitsTest {
    private val free = EditionLimits.FREE
    private val unlimited = EditionLimits.UNLIMITED

    private fun <T> StoreResult<T>.ok(): T {
        assertIs<StoreResult.Ok<T>>(this, "expected Ok but was $this")
        return value
    }

    private fun StoreResult<*>.assertRefused(kind: LimitKind) {
        assertIs<StoreResult.LimitReached>(this, "expected LimitReached but was $this")
        assertEquals(kind, decision.kind)
        assertEquals(FREE_EDITION_LIMIT_HINT, decision.hint)
    }

    /** A store whose active edition can be flipped mid-test, like downgrading after creating a big library. */
    private class FlippableStore {
        var limits: EditionLimits = EditionLimits.UNLIMITED
        val store = TestLibraryStore(tempTestingDir(), limits = { limits })
    }

    @Test
    fun freeEditionRefusesASecondSuite() {
        val store = TestLibraryStore(tempTestingDir(), limits = { free })
        store.createSuite("One").ok()

        store.createSuite("Two").assertRefused(LimitKind.SUITE_LIMIT)

        assertEquals(1, store.library.value.suites.size)
    }

    @Test
    fun freeEditionRefusesASixthCase() {
        val store = TestLibraryStore(tempTestingDir(), limits = { free })
        val suite = store.createSuite("One").ok()
        repeat(5) { store.createCase(suite.id, plainCase("c$it")).ok() }

        store.createCase(suite.id, plainCase("sixth")).assertRefused(LimitKind.CASE_LIMIT)

        assertEquals(5, store.library.value.suites.single().cases.size)
    }

    @Test
    fun freeEditionRefusesToDuplicateASuiteOrACaseBeyondTheLimit() {
        val store = TestLibraryStore(tempTestingDir(), limits = { free })
        val suite = store.createSuite("One").ok()
        val cases = (1..5).map { store.createCase(suite.id, plainCase("c$it")).ok() }

        store.duplicateSuite(suite.id).assertRefused(LimitKind.SUITE_LIMIT)
        store.duplicateCase(cases.first().id).assertRefused(LimitKind.CASE_LIMIT)

        assertEquals(1, store.library.value.suites.size)
        assertEquals(5, store.library.value.suites.single().cases.size)
    }

    @Test
    fun freeEditionDuplicatesACaseWhileThereIsRoom() {
        val store = TestLibraryStore(tempTestingDir(), limits = { free })
        val suite = store.createSuite("One").ok()
        val case = store.createCase(suite.id, plainCase("c")).ok()

        store.duplicateCase(case.id).ok()

        assertEquals(2, store.library.value.suites.single().cases.size)
    }

    @Test
    fun importingASuiteWithSevenCasesKeepsAllSevenWithTwoLockedAndAWarning() {
        val sevenCases = encodeSuiteFile(suiteWithCases("Big", 7))
        val dir = tempTestingDir()
        val store = TestLibraryStore(dir, limits = { free })

        val result = store.importSuite(sevenCases)

        assertIs<StoreResult.Ok<TestSuite>>(result)
        assertEquals(7, result.value.cases.size)
        assertTrue(result.warnings.any { it.contains("7") && it.contains("locked") })
        val library = store.library.value
        assertEquals(5, activeCaseIds(library, free).size)
        val locked = result.value.cases.filter { isCaseLocked(library, it.id, free) }
        assertEquals(result.value.cases.takeLast(2).map { it.id }, locked.map { it.id })
        assertEquals(7, TestLibraryStore(dir).library.value.suites.single().cases.size)
    }

    @Test
    fun importingASuiteIsRefusedWhenTheSuiteLimitIsReached() {
        val store = TestLibraryStore(tempTestingDir(), limits = { free })
        store.createSuite("One").ok()

        store.importSuite(encodeSuiteFile(suiteWithCases("Another", 1))).assertRefused(LimitKind.SUITE_LIMIT)

        assertEquals(1, store.library.value.suites.size)
    }

    @Test
    fun lockedSuitesAndCasesAreReadableAndExportableButNotEditableDuplicableOrRunnable() {
        val flip = FlippableStore()
        val store = flip.store
        val first = store.createSuite("First").ok()
        val second = store.createSuite("Second").ok()
        val secondCases = (1..7).map { store.createCase(second.id, plainCase("s$it", steps = 1)).ok() }
        val firstCases = (1..7).map { store.createCase(first.id, plainCase("f$it", steps = 1)).ok() }
        flip.limits = free

        // Suite level: the second suite is locked.
        store.updateSuite(second.id) { it.copy(name = "x") }.assertRefused(LimitKind.LOCKED)
        store.duplicateSuite(second.id).assertRefused(LimitKind.LOCKED)
        store.createCase(second.id, plainCase("new")).assertRefused(LimitKind.LOCKED)
        store.updateCase(secondCases[0].id) { it.copy(name = "x") }.assertRefused(LimitKind.LOCKED)
        assertIs<StoreResult.Ok<String>>(store.exportSuite(second.id))
        assertEquals(7, store.library.value.suite(second.id)!!.cases.size)

        // Case level: the 6th and 7th case of the first (active) suite are locked.
        store.updateCase(firstCases[5].id) { it.copy(name = "x") }.assertRefused(LimitKind.LOCKED)
        store.duplicateCase(firstCases[6].id).assertRefused(LimitKind.LOCKED)
        val stepId = store.library.value.findCase(firstCases[5].id)!!.case.steps.single().id
        store.updateStep(stepId) { it.copy(action = "x") }.assertRefused(LimitKind.LOCKED)
        store.moveStep(stepId, 0).assertRefused(LimitKind.LOCKED)

        // The active cases still edit fine.
        store.updateCase(firstCases[0].id) { it.copy(name = "edited") }.ok()
    }

    @Test
    fun lockedItemsStayDeletableAndReorderableAndReorderingChoosesWhichAreActive() {
        val flip = FlippableStore()
        val store = flip.store
        val first = store.createSuite("First").ok()
        val second = store.createSuite("Second").ok()
        val cases = (1..7).map { store.createCase(first.id, plainCase("c$it")).ok() }
        flip.limits = free
        assertEquals(setOf(first.id), activeSuiteIds(store.library.value, free))

        // Reordering a locked suite to the front makes it the active one.
        store.moveSuite(second.id, 0).ok()
        assertEquals(setOf(second.id), activeSuiteIds(store.library.value, free))
        assertTrue(isSuiteLocked(store.library.value, first.id, free))

        // Reordering cases inside a (now locked) suite is allowed.
        store.moveCase(cases[6].id, 0).ok()
        assertEquals(cases[6].id, store.library.value.suite(first.id)!!.cases.first().id)

        // Deleting locked cases and a locked suite is allowed.
        store.deleteCase(cases[0].id).ok()
        store.deleteSuite(first.id).ok()
        assertEquals(listOf(second.id), store.library.value.suites.map { it.id })
    }

    @Test
    fun movingACaseIntoALockedOrFullSuiteIsRefusedButMovingOutIsAllowed() {
        val flip = FlippableStore()
        val store = flip.store
        val a = store.createSuite("A").ok()
        val b = store.createSuite("B").ok()
        val aCases = (1..5).map { store.createCase(a.id, plainCase("a$it")).ok() }
        val bCase = store.createCase(b.id, plainCase("b1")).ok()
        flip.limits = free

        store.moveCase(bCase.id, 0, a.id).assertRefused(LimitKind.CASE_LIMIT)
        store.moveCase(aCases[0].id, 0, b.id).assertRefused(LimitKind.LOCKED)

        // Make room, then a case can move into the active suite from the locked one.
        store.deleteCase(aCases[4].id).ok()
        store.moveCase(bCase.id, 0, a.id).ok()
        assertEquals(5, store.library.value.suite(a.id)!!.cases.size)
        assertTrue(store.library.value.suite(b.id)!!.cases.isEmpty())
    }

    @Test
    fun anOverLimitLibraryRefusesNewSuitesButNeverDropsAnything() {
        val flip = FlippableStore()
        val store = flip.store
        repeat(3) { store.createSuite("S$it").ok() }
        flip.limits = free

        store.createSuite("another").assertRefused(LimitKind.SUITE_LIMIT)

        assertEquals(3, store.library.value.suites.size)
        assertEquals(setOf(store.library.value.suites.first().id), activeSuiteIds(store.library.value, free))
    }

    @Test
    fun unlimitedEditionNeverRefuses() {
        val store = TestLibraryStore(tempTestingDir(), limits = { unlimited })
        val suites = (1..4).map { store.createSuite("S$it").ok() }
        repeat(9) { store.createCase(suites[0].id, plainCase("c$it")).ok() }
        store.duplicateSuite(suites[0].id).ok()
        val result = store.importSuite(encodeSuiteFile(suiteWithCases("Big", 12)))

        assertIs<StoreResult.Ok<TestSuite>>(result)
        assertTrue(result.warnings.isEmpty())
        assertEquals(12, result.value.cases.size)
        assertEquals(store.library.value.suites.size, activeSuiteIds(store.library.value, unlimited).size)
        assertEquals(store.library.value.suites.sumOf { it.cases.size }, activeCaseIds(store.library.value, unlimited).size)
    }

    @Test
    fun decideIsPureAndAnswersForEveryOperationKind() {
        val library = TestLibrary(suites = listOf(suiteWithCases("A", 5), suiteWithCases("B", 2)))
        val a = library.suites[0]
        val b = library.suites[1]

        assertIs<LimitDecision.Refused>(decide(library, LimitOperation.CreateSuite, free))
        assertEquals(LimitDecision.Allowed, decide(library, LimitOperation.CreateSuite, unlimited))
        assertIs<LimitDecision.Refused>(decide(library, LimitOperation.DuplicateSuite(a.id), free))
        assertIs<LimitDecision.Refused>(decide(library, LimitOperation.CreateCase(a.id), free))
        assertIs<LimitDecision.Refused>(decide(library, LimitOperation.EditSuite(b.id), free))
        assertIs<LimitDecision.Refused>(decide(library, LimitOperation.RunSuite(b.id), free))
        assertIs<LimitDecision.Refused>(decide(library, LimitOperation.RunCase(b.id, b.cases[0].id), free))
        assertEquals(LimitDecision.Allowed, decide(library, LimitOperation.RunCase(a.id, a.cases[0].id), free))
        assertEquals(LimitDecision.Allowed, decide(library, LimitOperation.EditCase(a.id, a.cases[4].id), free))
        assertEquals(LimitDecision.Allowed, decide(library, LimitOperation.MoveCaseToSuite(a.cases[0].id, a.id), free))
        // Unknown ids are not the limits layer's problem.
        assertEquals(LimitDecision.Allowed, decide(library, LimitOperation.EditSuite("suite-nope"), free))
    }

    @Test
    fun runningASuiteWithLockedCasesWarnsThatTheyWillNotRun() {
        val library = TestLibrary(suites = listOf(suiteWithCases("A", 7)))

        val decision = decide(library, LimitOperation.RunSuite(library.suites[0].id), free)

        assertIs<LimitDecision.AllowedWithWarning>(decision)
        assertTrue(decision.warning.contains("2"))
    }

    @Test
    fun theRefusalCarriesTheLimitNumberAndTheHintText() {
        val library = TestLibrary(suites = listOf(suiteWithCases("A", 5)))

        val decision = decide(library, LimitOperation.CreateCase(library.suites[0].id), free)

        assertIs<LimitDecision.Refused>(decision)
        assertEquals(5, decision.limit)
        assertEquals("Free edition: 1 suite / 5 cases — upgrade to add more", decision.hint)
    }
}
