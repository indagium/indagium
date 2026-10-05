package com.indagium.testing

import com.indagium.testing.model.HookItem
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.newCheckId
import com.indagium.testing.model.newHookId
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.TestLibraryStore
import com.indagium.testing.store.decodeSuiteFile
import com.indagium.testing.store.encodeSuiteFile
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestLibraryStoreTest {
    private fun <T> StoreResult<T>.ok(): T {
        assertIs<StoreResult.Ok<T>>(this, "expected Ok but was $this")
        return value
    }

    private fun newStore(dir: File = tempTestingDir()) = TestLibraryStore(dir)

    private fun suiteFile(dir: File, id: String) = File(dir, "suites/$id.json")

    @Test
    fun suitesCasesStepsScriptsAndSharedStepsPersistAndReloadFromDisk() {
        val dir = tempTestingDir()
        val store = newStore(dir)
        val script = store.createScript(sampleScript()).ok()
        val shared = store.createSharedStep(sampleSharedStep()).ok()
        val suite = store.createSuite("Smoke", "desc", "instr").ok()
        val case = store.createCase(suite.id, plainCase("Login")).ok()
        val step = store.createStep(case.id, plainStep("Tap login")).ok()
        store.updateStep(step.id) { it.copy(checks = listOf(StepCheck.AskJudge(newCheckId(), "ok?"))) }.ok()
        store.updateSuite(suite.id) { it.copy(setup = listOf(HookItem.Script(newHookId(), script.id)), name = "Smoke 2") }.ok()

        val reloaded = TestLibraryStore(dir)

        assertEquals(store.library.value, reloaded.library.value)
        assertEquals("Smoke 2", reloaded.library.value.suites.single().name)
        assertEquals(listOf(script), reloaded.library.value.scripts)
        assertEquals(listOf(shared), reloaded.library.value.sharedSteps)
        assertEquals(1, reloaded.library.value.suites.single().cases.single().steps.single().checks.size)
        assertTrue(reloaded.loadIssues.isEmpty())
    }

    @Test
    fun eachSuiteIsItsOwnFileAndLibraryJsonHoldsTheOrder() {
        val dir = tempTestingDir()
        val store = newStore(dir)
        val a = store.createSuite("A").ok()
        val b = store.createSuite("B").ok()

        assertTrue(suiteFile(dir, a.id).isFile)
        assertTrue(suiteFile(dir, b.id).isFile)
        assertTrue(File(dir, "library.json").isFile)
        assertEquals(listOf(a.id, b.id), TestLibraryStore(dir).library.value.suites.map { it.id })
    }

    @Test
    fun movingSuitesChangesTheOrderAndSurvivesAReload() {
        val dir = tempTestingDir()
        val store = newStore(dir)
        val ids = listOf("A", "B", "C").map { store.createSuite(it).ok().id }

        store.moveSuite(ids[2], 0).ok()

        assertEquals(listOf(ids[2], ids[0], ids[1]), store.library.value.suites.map { it.id })
        assertEquals(listOf(ids[2], ids[0], ids[1]), TestLibraryStore(dir).library.value.suites.map { it.id })
    }

    @Test
    fun movingCasesWithinASuiteReordersThemAndPersists() {
        val dir = tempTestingDir()
        val store = newStore(dir)
        val suite = store.createSuite("S").ok()
        val cases = (1..3).map { store.createCase(suite.id, plainCase("c$it")).ok().id }

        store.moveCase(cases[0], 2).ok()

        val expected = listOf(cases[1], cases[2], cases[0])
        assertEquals(expected, store.library.value.suite(suite.id)!!.cases.map { it.id })
        assertEquals(expected, TestLibraryStore(dir).library.value.suite(suite.id)!!.cases.map { it.id })
    }

    @Test
    fun movingACaseToAnotherSuiteMovesItAtTheRequestedIndexAndPersistsBothSuites() {
        val dir = tempTestingDir()
        val store = newStore(dir)
        val from = store.createSuite("From").ok()
        val to = store.createSuite("To").ok()
        val moving = store.createCase(from.id, plainCase("moving", steps = 2)).ok()
        val stay = store.createCase(from.id, plainCase("stay")).ok()
        val t1 = store.createCase(to.id, plainCase("t1")).ok()
        val t2 = store.createCase(to.id, plainCase("t2")).ok()

        val moved = store.moveCase(moving.id, toIndex = 1, toSuiteId = to.id).ok()

        assertEquals(moving, moved)
        val reloaded = TestLibraryStore(dir).library.value
        assertEquals(listOf(stay.id), reloaded.suite(from.id)!!.cases.map { it.id })
        assertEquals(listOf(t1.id, moving.id, t2.id), reloaded.suite(to.id)!!.cases.map { it.id })
        assertEquals(2, reloaded.suite(to.id)!!.cases[1].steps.size)
    }

    @Test
    fun movingStepsScriptsAndSharedStepsReorderThem() {
        val store = newStore()
        val suite = store.createSuite("S").ok()
        val case = store.createCase(suite.id, plainCase("c", steps = 3)).ok()
        val stepIds = store.library.value.findCase(case.id)!!.case.steps.map { it.id }
        val scripts = listOf("one_tool", "two_tool", "three_tool").map { store.createScript(sampleScript(it)).ok().id }
        val shared = (1..3).map { store.createSharedStep(sampleSharedStep().copy(name = "s$it")).ok().id }

        store.moveStep(stepIds[0], 2).ok()
        store.moveScript(scripts[2], 0).ok()
        store.moveSharedStep(shared[1], 2).ok()

        assertEquals(listOf(stepIds[1], stepIds[2], stepIds[0]), store.library.value.findCase(case.id)!!.case.steps.map { it.id })
        assertEquals(listOf(scripts[2], scripts[0], scripts[1]), store.library.value.scripts.map { it.id })
        assertEquals(listOf(shared[0], shared[2], shared[1]), store.library.value.sharedSteps.map { it.id })
    }

    @Test
    fun deletingASuiteRemovesItsFileAndItsOrderEntry() {
        val dir = tempTestingDir()
        val store = newStore(dir)
        val a = store.createSuite("A").ok()
        val b = store.createSuite("B").ok()
        assertTrue(suiteFile(dir, a.id).isFile)

        store.deleteSuite(a.id).ok()

        assertFalse(suiteFile(dir, a.id).exists())
        assertTrue(suiteFile(dir, b.id).isFile)
        assertEquals(listOf(b.id), TestLibraryStore(dir).library.value.suites.map { it.id })
    }

    @Test
    fun deletingCasesAndStepsPersists() {
        val dir = tempTestingDir()
        val store = newStore(dir)
        val suite = store.createSuite("S").ok()
        val case = store.createCase(suite.id, plainCase("c", steps = 2)).ok()
        val stepId = store.library.value.findCase(case.id)!!.case.steps.first().id

        store.deleteStep(stepId).ok()
        assertEquals(1, TestLibraryStore(dir).library.value.findCase(case.id)!!.case.steps.size)

        store.deleteCase(case.id).ok()
        assertTrue(TestLibraryStore(dir).library.value.suite(suite.id)!!.cases.isEmpty())
    }

    @Test
    fun unknownIdsReturnNotFoundWithoutChangingAnything() {
        val store = newStore()
        val before = store.library.value

        assertIs<StoreResult.NotFound>(store.updateSuite("suite-nope") { it })
        assertIs<StoreResult.NotFound>(store.deleteSuite("suite-nope"))
        assertIs<StoreResult.NotFound>(store.moveSuite("suite-nope", 0))
        assertIs<StoreResult.NotFound>(store.createCase("suite-nope", plainCase("x")))
        assertIs<StoreResult.NotFound>(store.updateCase("case-nope") { it })
        assertIs<StoreResult.NotFound>(store.moveCase("case-nope", 0))
        assertIs<StoreResult.NotFound>(store.createStep("case-nope", plainStep()))
        assertIs<StoreResult.NotFound>(store.updateStep("step-nope") { it })
        assertIs<StoreResult.NotFound>(store.moveStep("step-nope", 0))
        assertIs<StoreResult.NotFound>(store.deleteScript("script-nope"))
        assertIs<StoreResult.NotFound>(store.moveSharedStep("shared-nope", 0))
        assertIs<StoreResult.NotFound>(store.exportSuite("suite-nope"))
        assertEquals(before, store.library.value)
    }

    @Test
    fun updatesKeepIdsAndTheChildrenTheyDoNotOwn() {
        val store = newStore()
        val suite = store.createSuite("S").ok()
        val case = store.createCase(suite.id, plainCase("c", steps = 2)).ok()

        val updatedSuite = store.updateSuite(suite.id) { it.copy(id = "hijack", cases = emptyList(), name = "Renamed", createdAt = 1L) }.ok()
        val updatedCase = store.updateCase(case.id) { it.copy(id = "hijack", steps = emptyList(), name = "Renamed case") }.ok()

        assertEquals(suite.id, updatedSuite.id)
        assertEquals(suite.createdAt, updatedSuite.createdAt)
        assertEquals(1, updatedSuite.cases.size)
        assertEquals(case.id, updatedCase.id)
        assertEquals(2, updatedCase.steps.size)
        assertEquals("Renamed case", store.library.value.findCase(case.id)!!.case.name)
    }

    @Test
    fun updatedAtIsBumpedWhenAnythingInsideTheSuiteChanges() {
        var now = 100L
        val store = TestLibraryStore(tempTestingDir(), clock = { now })
        val suite = store.createSuite("S").ok()
        assertEquals(100L, suite.updatedAt)

        now = 500L
        store.createCase(suite.id, plainCase("c")).ok()

        assertEquals(500L, store.suite(suite.id)!!.updatedAt)
        assertEquals(100L, store.suite(suite.id)!!.createdAt)
    }

    @Test
    fun invalidInputIsRejectedWithAReason() {
        val store = newStore()
        val suite = store.createSuite("S").ok()
        val case = store.createCase(suite.id, plainCase("c", steps = 1)).ok()
        val stepId = store.library.value.findCase(case.id)!!.case.steps.single().id
        store.createScript(sampleScript("good_tool")).ok()

        assertIs<StoreResult.Invalid>(store.createSuite("  "))
        assertIs<StoreResult.Invalid>(store.createCase(suite.id, plainCase(" ")))
        assertIs<StoreResult.Invalid>(store.createScript(sampleScript("Bad Name")))
        assertIs<StoreResult.Invalid>(store.createScript(sampleScript("good_tool")))
        assertIs<StoreResult.Invalid>(store.createScript(sampleScript("tap")))
        val reservedParam = sampleScript("other_tool").let { it.copy(params = it.params + com.indagium.testing.model.ScriptParam("path")) }
        assertIs<StoreResult.Invalid>(store.createScript(reservedParam))
        val badRegex = StepCheck.LogAppears(newCheckId(), null, "([unclosed")
        assertIs<StoreResult.Invalid>(store.updateStep(stepId) { it.copy(checks = listOf(badRegex)) })
        assertIs<StoreResult.Invalid>(store.updateStep(stepId) { it.copy(timeoutMs = 0) })
        assertEquals(1, store.library.value.scripts.size)
    }

    @Test
    fun suiteTargetPackageAndTagsAreValidatedAndNormalised() {
        val store = newStore()
        val suite = store.createSuite("S").ok()

        val updated = store.updateSuite(suite.id) {
            it.copy(targetPackage = " com.example.app ", deviceProfileHint = "Pixel", tags = listOf(" a ", "A", "", "b"))
        }.ok()
        assertEquals("com.example.app", updated.targetPackage)
        assertEquals(listOf("a", "b"), updated.tags)

        assertIs<StoreResult.Invalid>(store.updateSuite(suite.id) { it.copy(targetPackage = "not a package") })
        assertIs<StoreResult.Invalid>(store.updateSuite(suite.id) { it.copy(targetPackage = "com.example.app; rm -rf") })
        assertIs<StoreResult.Invalid>(store.updateSuite(suite.id) { it.copy(tags = listOf("x".repeat(41))) })
        assertEquals(updated.tags, store.suite(suite.id)!!.tags, "a refused update changes nothing")
        assertEquals("", store.updateSuite(suite.id) { it.copy(targetPackage = "") }.ok().targetPackage)
        assertEquals(40, store.updateSuite(suite.id) { it.copy(tags = listOf("x".repeat(40))) }.ok().tags.single().length)
    }

    @Test
    fun stepMaxToolCallsMustBeBetweenOneAndOneHundred() {
        val store = newStore()
        val suite = store.createSuite("S").ok()
        val case = store.createCase(suite.id, plainCase("c")).ok()
        assertIs<StoreResult.Invalid>(store.createStep(case.id, plainStep().copy(maxToolCalls = 0)))
        assertIs<StoreResult.Invalid>(store.createStep(case.id, plainStep().copy(maxToolCalls = 101)))
        assertEquals(1, store.createStep(case.id, plainStep().copy(maxToolCalls = 1)).ok().maxToolCalls)
        assertEquals(100, store.createStep(case.id, plainStep().copy(maxToolCalls = 100)).ok().maxToolCalls)
    }

    @Test
    fun duplicatingACaseGivesItsHooksNewIds() {
        val store = newStore()
        val suite = store.createSuite("S").ok()
        val original = fullSuite().cases.first()
        val case = store.createCase(suite.id, original).ok()
        val copy = store.duplicateCase(case.id).ok()
        assertEquals(case.setup.size, copy.setup.size)
        assertTrue((case.setup + case.teardown).map { it.id }.intersect((copy.setup + copy.teardown).map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun aBlankIdOnCreateIsReplacedWithAFreshPrefixedId() {
        val store = newStore()
        val suite = store.createSuite("S").ok()

        val case = store.createCase(suite.id, plainCase("c").copy(id = "")).ok()
        val step = store.createStep(case.id, plainStep().copy(id = "")).ok()
        val script = store.createScript(sampleScript().copy(id = "")).ok()

        assertTrue(case.id.startsWith("case-"))
        assertTrue(step.id.startsWith("step-"))
        assertTrue(script.id.startsWith("script-"))
    }

    @Test
    fun creatingWithADuplicateIdIsRejected() {
        val store = newStore()
        val suite = store.createSuite("S").ok()
        val case = store.createCase(suite.id, plainCase("c")).ok()

        assertIs<StoreResult.Invalid>(store.createCase(suite.id, plainCase("other").copy(id = case.id)))
    }

    @Test
    fun duplicatingASuiteDeepCopiesItWithFreshIdsRightAfterTheOriginal() {
        val dir = tempTestingDir()
        val store = newStore(dir)
        val original = store.importSuite(encodeSuiteFile(fullSuite())).ok()
        val other = store.createSuite("Other").ok()

        val copy = store.duplicateSuite(original.id).ok()

        assertEquals("${original.name} (copy)", copy.name)
        assertEquals(listOf(original.id, copy.id, other.id), store.library.value.suites.map { it.id })
        assertEquals(original.cases.size, copy.cases.size)
        val originalIds = original.cases.flatMap { c -> listOf(c.id) + c.steps.map { it.id } + c.steps.flatMap { s -> s.checks.map { it.id } } }
        val copyIds = copy.cases.flatMap { c -> listOf(c.id) + c.steps.map { it.id } + c.steps.flatMap { s -> s.checks.map { it.id } } }
        assertTrue(originalIds.intersect(copyIds.toSet()).isEmpty())
        assertTrue(suiteFile(dir, copy.id).isFile)
    }

    @Test
    fun duplicatingACaseAndAStepPlacesTheCopyRightAfterTheOriginal() {
        val store = newStore()
        val suite = store.createSuite("S").ok()
        val first = store.createCase(suite.id, plainCase("first", steps = 2)).ok()
        val second = store.createCase(suite.id, plainCase("second")).ok()

        val caseCopy = store.duplicateCase(first.id).ok()
        val stepId = store.library.value.findCase(first.id)!!.case.steps.first().id
        val stepCopy = store.duplicateStep(stepId).ok()

        assertEquals(listOf(first.id, caseCopy.id, second.id), store.library.value.suite(suite.id)!!.cases.map { it.id })
        assertEquals("first (copy)", caseCopy.name)
        assertNotEquals(first.steps.first().id, caseCopy.steps.first().id)
        assertEquals(stepId, store.library.value.findCase(first.id)!!.case.steps[0].id)
        assertEquals(stepCopy.id, store.library.value.findCase(first.id)!!.case.steps[1].id)
    }

    @Test
    fun exportThenImportIntoAnotherStoreKeepsContentWithFreshIds() {
        val source = newStore()
        val suite = source.importSuite(encodeSuiteFile(fullSuite())).ok()
        val text = source.exportSuite(suite.id).ok()
        val target = newStore()

        val imported = target.importSuite(text).ok()

        assertNotEquals(suite.id, imported.id)
        assertEquals(suite.name, imported.name)
        assertEquals(suite.cases.map { it.name }, imported.cases.map { it.name })
        assertEquals(suite.cases.first().steps.first().checks.size, imported.cases.first().steps.first().checks.size)
    }

    @Test
    fun exportAndImportThroughFilesWork() {
        val dir = tempTestingDir()
        val store = newStore(dir)
        val suite = store.createSuite("To file").ok()
        val file = File(dir, "exported/suite.json")

        store.exportSuiteToFile(suite.id, file).ok()
        val imported = newStore().importSuiteFromFile(file).ok()

        assertEquals("To file", imported.name)
        assertTrue(decodeSuiteFile(file.readText()).isSuccess)
        assertIs<StoreResult.Invalid>(store.importSuiteFromFile(File(dir, "missing.json")))
    }

    @Test
    fun importRejectsGarbageAndFilesFromANewerVersion() {
        val store = newStore()
        val newer = encodeSuiteFile(fullSuite()).replace("\"version\": 1", "\"version\": 9")

        assertIs<StoreResult.Invalid>(store.importSuite("not json"))
        assertIs<StoreResult.Invalid>(store.importSuite("""{"format":"indagium-test-library","version":1}"""))
        assertIs<StoreResult.Invalid>(store.importSuite(newer))
        assertTrue(store.library.value.suites.isEmpty())
    }

    @Test
    fun importWarnsAboutReferencesToScriptsThatAreNotInThisLibrary() {
        val store = newStore()

        val result = store.importSuite(encodeSuiteFile(fullSuite()))

        assertIs<StoreResult.Ok<TestSuite>>(result)
        assertTrue(result.warnings.any { it.contains("reference") })
    }

    @Test
    fun aSuiteFileFromANewerVersionLoadsReadOnlyAndIsNeverRewritten() {
        val dir = tempTestingDir()
        val suite = fullSuite().copy(id = "suite-future")
        val file = suiteFile(dir, suite.id)
        file.parentFile.mkdirs()
        val newerText = encodeSuiteFile(suite).replace("\"version\": 1", "\"version\": 5").replace("\"name\": \"Smoke\"", "\"name\": \"Smoke\", \"fancy\": 1")
        file.writeText(newerText)
        val store = newStore(dir)

        assertTrue(store.library.value.suite("suite-future")!!.readOnly)
        assertIs<StoreResult.Invalid>(store.updateSuite("suite-future") { it.copy(name = "x") })
        assertIs<StoreResult.Invalid>(store.createCase("suite-future", plainCase("c")))
        store.createSuite("Another").ok()

        assertEquals(newerText, file.readText())
        store.deleteSuite("suite-future").ok()
        assertFalse(file.exists())
    }

    @Test
    fun aNewerLibraryFileMakesEveryLibraryLevelWriteInvalidWithoutTouchingTheFile() {
        val dir = tempTestingDir()
        dir.mkdirs()
        val text = """{"format":"indagium-test-library","version":4,"suiteOrder":[],"scripts":[],"sharedSteps":[],"newThing":{}}"""
        File(dir, "library.json").writeText(text)
        val store = newStore(dir)

        assertTrue(store.library.value.readOnly)
        assertIs<StoreResult.Invalid>(store.createSuite("S"))
        assertIs<StoreResult.Invalid>(store.createScript(sampleScript()))
        assertEquals(text, File(dir, "library.json").readText())
    }

    @Test
    fun aCorruptLibraryFileIsSetAsideInsteadOfBeingOverwritten() {
        val dir = tempTestingDir()
        dir.mkdirs()
        File(dir, "library.json").writeText("{ this is not json")

        val store = newStore(dir)

        assertTrue(store.library.value.scripts.isEmpty())
        assertTrue(store.loadIssues.single().contains("library.json"))
        assertNotNull(dir.listFiles()!!.firstOrNull { it.name.startsWith("library.json.corrupt-") })
        assertFalse(File(dir, "library.json").exists())
    }

    @Test
    fun badAndMismatchedSuiteFilesAreSkippedAndReported() {
        val dir = tempTestingDir()
        val good = newStore(dir).createSuite("Good").ok()
        File(dir, "suites/broken.json").writeText("garbage")
        File(dir, "suites/renamed.json").writeText(encodeSuiteFile(fullSuite().copy(id = "suite-other")))

        val reloaded = TestLibraryStore(dir)

        assertEquals(listOf(good.id), reloaded.library.value.suites.map { it.id })
        assertEquals(2, reloaded.loadIssues.size)
    }

    @Test
    fun suitesMissingFromTheOrderListAreAppendedByCreationTime() {
        val dir = tempTestingDir()
        val first = newStore(dir).createSuite("First").ok()
        val orphan = fullSuite().copy(id = "suite-orphan", createdAt = Long.MAX_VALUE)
        File(dir, "suites/suite-orphan.json").writeText(encodeSuiteFile(orphan))

        assertEquals(listOf(first.id, "suite-orphan"), TestLibraryStore(dir).library.value.suites.map { it.id })
    }

    @Test
    fun aDiskFailureKeepsTheChangeInMemoryAndIsReportedUntilTheNextGoodWrite() {
        val dir = tempTestingDir()
        dir.mkdirs()
        File(dir, "suites").writeText("a file where the suites folder should be")
        val store = newStore(dir)

        val result = store.createSuite("S")

        assertIs<StoreResult.Ok<TestSuite>>(result)
        assertEquals(1, store.library.value.suites.size)
        assertNotNull(store.persistError.value)

        File(dir, "suites").delete()
        store.createSuite("T").ok()
        assertNull(store.persistError.value)
    }

    @Test
    fun theLibraryFlowPublishesEveryChange() {
        val store = newStore()
        val seen = mutableListOf<Int>()
        seen += store.library.value.suites.size
        store.createSuite("A").ok()
        seen += store.library.value.suites.size
        store.createSuite("B").ok()
        seen += store.library.value.suites.size
        assertEquals(listOf(0, 1, 2), seen)
    }

    @Test
    fun scriptsPersistWithTheirPermissionAndParameters() {
        val dir = tempTestingDir()
        val store = newStore(dir)
        val script = store.createScript(sampleScript()).ok()
        store.updateScript(script.id) { it.copy(permission = ScriptPermission.SETUP_TEARDOWN_ONLY, workingDir = "  ") }.ok()

        val reloaded = TestLibraryStore(dir).library.value.scripts.single()

        assertEquals(ScriptPermission.SETUP_TEARDOWN_ONLY, reloaded.permission)
        assertNull(reloaded.workingDir)
        assertEquals(3, reloaded.params.size)
    }

    @Test
    fun concurrentMutationsFromSeveralThreadsStayConsistentAndMatchTheDisk() {
        val dir = tempTestingDir()
        val store = newStore(dir)
        val suiteA = store.createSuite("A").ok()
        val suiteB = store.createSuite("B").ok()
        val threads = 8
        val perThread = 10
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val failures = AtomicInteger()
        repeat(threads) { t ->
            pool.execute {
                start.await()
                runCatching {
                    repeat(perThread) { i ->
                        val suiteId = if ((t + i) % 2 == 0) suiteA.id else suiteB.id
                        val case = store.createCase(suiteId, plainCase("t$t-$i")).ok()
                        store.createStep(case.id, plainStep("s")).ok()
                        store.moveCase(case.id, 0).ok()
                        if (i % 3 == 0) store.moveCase(case.id, 0, if (suiteId == suiteA.id) suiteB.id else suiteA.id).ok()
                        if (i % 4 == 0) store.createScript(sampleScript("tool_${t}_$i")).ok()
                    }
                }.onFailure { failures.incrementAndGet() }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))

        assertEquals(0, failures.get())
        val library = store.library.value
        val cases = library.suites.flatMap { it.cases }
        assertEquals(threads * perThread, cases.size)
        assertEquals(cases.size, cases.map { it.id }.toSet().size)
        assertTrue(cases.all { it.steps.size == 1 })
        assertEquals(threads * 3, library.scripts.size)
        assertEquals(library, TestLibraryStore(dir).library.value)
        assertNull(store.persistError.value)
        assertFalse(dir.walkTopDown().any { it.name.contains(".tmp-") })
    }

    @Test
    fun anEmptyDirectoryGivesAnEmptyLibraryAndWritesNothingUntilAMutation() {
        val dir = File(tempTestingDir(), "not-yet-created")

        val store = TestLibraryStore(dir)

        assertEquals(TestLibrary(), store.library.value)
        assertFalse(dir.exists())
    }
}
