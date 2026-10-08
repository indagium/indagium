package com.indagium.testing.store

import com.indagium.edition.EditionLimits
import com.indagium.model.AiUsageStats
import com.indagium.model.sumAiUsage
import com.indagium.testing.limits.LimitDecision
import com.indagium.testing.limits.LimitOperation
import com.indagium.testing.limits.decide
import com.indagium.testing.model.CaseLocation
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.StepLocation
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.copyName
import com.indagium.testing.model.moveById
import com.indagium.testing.model.newCaseId
import com.indagium.testing.model.newScriptId
import com.indagium.testing.model.newSharedStepId
import com.indagium.testing.model.newStepId
import com.indagium.testing.model.newSuiteId
import com.indagium.testing.model.normalizeTags
import com.indagium.testing.model.withFreshIds
import com.indagium.utils.writeFileAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

const val TEST_LIBRARY_FILE_NAME = "library.json"
const val TEST_SUITES_DIR_NAME = "suites"
const val MAX_TEST_FILE_BYTES = 16L * 1024L * 1024L
private const val SUITE_FILE_EXTENSION = ".json"
private const val LIBRARY_READ_ONLY_REASON =
    "The test library was saved by a newer version of Indagium and is read-only here. Update Indagium to change it."
private const val SUITE_READ_ONLY_REASON =
    "This suite was saved by a newer version of Indagium and is read-only here. Update Indagium to change it."
private const val DEFAULT_IMPORTED_SUITE_NAME = "Imported suite"

/**
 * The persisted library of test suites, scripts and shared steps, kept under [rootDir]:
 * `library.json` (suite order, scripts, shared steps) and one `suites/<id>.json` per suite.
 *
 * Locking follows [com.indagium.ui.DiagramLibraryStore]: [lock] is held only to compute and publish the
 * next immutable [TestLibrary]; [writeLock] (fair) covers only the disk write, which re-reads the
 * freshest library once it holds the lock, so concurrent writers can never leave an older snapshot on
 * disk. Both are LEAF locks: nothing in here calls out to AppState (or anything else that locks) while
 * holding them, and [limits] is read before [lock] is taken. A [library] collector that is resumed
 * inline (an unconfined dispatcher) runs inside [lock], so collectors must not call back into the store.
 *
 * Every operation returns a [StoreResult] instead of throwing for an expected problem. A disk failure
 * does not undo the in-memory change; it is reported on [persistError] and cleared by the next good write.
 */
class TestLibraryStore(
    private val rootDir: File,
    private val limits: () -> EditionLimits = { EditionLimits.UNLIMITED },
    private val clock: () -> Long = System::currentTimeMillis,
    private val copyAsset: (File, File) -> Unit = { source, target -> Files.copy(source.toPath(), target.toPath()) },
) {
    private val suitesDir = File(rootDir, TEST_SUITES_DIR_NAME)
    private val libraryFile = File(rootDir, TEST_LIBRARY_FILE_NAME)
    private val lock = Any()
    private val writeLock = ReentrantLock(true)
    private val loaded = loadFromDisk()
    private val state = MutableStateFlow(loaded.library)
    private val persistErrorState = MutableStateFlow<String?>(null)

    /** The whole library in the user's suite order. Every published value is immutable. */
    val library: StateFlow<TestLibrary> = state.asStateFlow()

    /** The last disk-write failure, or null when the last write succeeded. */
    val persistError: StateFlow<String?> = persistErrorState.asStateFlow()

    /** Files that were skipped while loading (unreadable, invalid, or named differently from their id). */
    val loadIssues: List<String> = loaded.issues

    fun suite(id: String): TestSuite? = state.value.suite(id)

    // ── Suites ───────────────────────────────────────────────────────

    fun createSuite(name: String, description: String = "", instructions: String = ""): StoreResult<TestSuite> {
        val lim = limits()
        return mutate { lib ->
            libraryFileRejection(lib)?.let { return@mutate it }
            refusal(decide(lib, LimitOperation.CreateSuite, lim))?.let { return@mutate it }
            validateName("Suite", name)?.let { return@mutate invalid(it) }
            val now = clock()
            val suite = TestSuite(newSuiteId(), name.trim(), description, instructions, createdAt = now, updatedAt = now)
            Outcome.Apply(lib.copy(suites = lib.suites + suite), suite, Dirty(setOf(suite.id), libraryFile = true))
        }
    }

    /** Replaces the suite's own fields (name, description, instructions, target, tags, hooks, variables). Its id, cases and creation time are kept. */
    fun updateSuite(suiteId: String, transform: (TestSuite) -> TestSuite): StoreResult<TestSuite> =
        editSuite(
            locate = { it.suite(suiteId) },
            notFound = StoreResult.NotFound("suite", suiteId),
            operation = { LimitOperation.EditSuite(suiteId) },
        ) { _, old ->
            val transformed = transform(old)
            val updated = transformed.copy(
                id = old.id, cases = old.cases, createdAt = old.createdAt, readOnly = false,
                targetPackage = transformed.targetPackage.trim(), tags = normalizeTags(transformed.tags),
            )
            validateSuite(updated)?.let { return@editSuite SuiteEdit.Fail(it) }
            SuiteEdit.Done(updated, updated)
        }

    fun deleteSuite(suiteId: String): StoreResult<Unit> = mutate { lib ->
        libraryFileRejection(lib)?.let { return@mutate it }
        if (lib.suite(suiteId) == null) return@mutate notFound("suite", suiteId)
        Outcome.Apply(lib.copy(suites = lib.suites.filterNot { it.id == suiteId }), Unit, Dirty(setOf(suiteId), libraryFile = true))
    }

    /** A deep copy with new ids, placed right after the original. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "TooGenericExceptionCaught") // Stage, revalidate, publish and clean copied assets on refusal.
    fun duplicateSuite(suiteId: String): StoreResult<TestSuite> {
        val lim = limits()
        val initial = synchronized(lock) { state.value }
        libraryFileRejection(initial)?.let { return it.result }
        val sourceIndex = initial.suites.indexOfFirst { it.id == suiteId }
        if (sourceIndex < 0) return StoreResult.NotFound("suite", suiteId)
        val source = initial.suites[sourceIndex]
        val sharedIds = referencedSharedIds(source)
        val referencedShared = initial.sharedSteps.filter { it.id in sharedIds }
        readOnlySuiteRejection(source)?.let { return it.result }
        refusal(decide(initial, LimitOperation.DuplicateSuite(suiteId), lim))?.let { return it.result }
        val now = clock()
        val copy = source.withFreshIds().copy(name = copyName(source.name), createdAt = now, updatedAt = now)
        val staged = stageSuiteAssets(initial, source, copy.id)
        if (staged.error != null) return StoreResult.Invalid(staged.error)

        var assetsPublished = false
        try {
            staged.stageDir?.let { stage ->
                val target = checkNotNull(staged.destinationDir)
                if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    return StoreResult.Invalid("Could not duplicate suite assets: the destination asset folder already exists.")
                }
                try {
                    Files.move(stage.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(stage.toPath(), target.toPath())
                }
                assetsPublished = true
            }
            val applied = synchronized(lock) {
                val current = state.value
                val liveIndex = current.suites.indexOfFirst { it.id == suiteId }
                val live = current.suites.getOrNull(liveIndex)
                val refusal = libraryFileRejection(current)
                    ?: refusal(decide(current, LimitOperation.DuplicateSuite(suiteId), lim))
                    ?: live?.let(::readOnlySuiteRejection)
                when {
                    refusal != null -> Outcome.Reject(refusal.result)
                    live == null -> Outcome.Reject(StoreResult.NotFound("suite", suiteId))
                    live != source -> Outcome.Reject(StoreResult.Invalid("The suite changed while its assets were being copied. Retry duplication."))
                    current.sharedSteps.filter { it.id in sharedIds } != referencedShared ->
                        Outcome.Reject(StoreResult.Invalid("A referenced shared step changed while its assets were being copied. Retry duplication."))
                    else -> {
                        val suites = current.suites.toMutableList().apply { add(liveIndex + 1, copy) }
                        val outcome = Outcome.Apply(current.copy(suites = suites), copy, Dirty(setOf(copy.id), libraryFile = true))
                        state.value = outcome.library
                        outcome
                    }
                }
            }
            if (applied is Outcome.Reject) {
                if (assetsPublished) staged.destinationDir?.deleteRecursively()
                return applied.result
            }
            applied as Outcome.Apply<TestSuite>
            persist(applied.dirty)
            return StoreResult.Ok(applied.value)
        } catch (failure: Exception) {
            if (assetsPublished) staged.destinationDir?.deleteRecursively()
            return StoreResult.Invalid("Could not duplicate suite assets: ${failure.message ?: "file operation failed"}. The library was not changed.")
        } finally {
            staged.stageDir?.takeIf(File::exists)?.deleteRecursively()
        }
    }

    /** Reordering is always allowed, even for locked suites: it is how the user picks which ones are active. */
    fun moveSuite(suiteId: String, toIndex: Int): StoreResult<Unit> = mutate { lib ->
        libraryFileRejection(lib)?.let { return@mutate it }
        if (lib.suite(suiteId) == null) return@mutate notFound("suite", suiteId)
        val moved = lib.suites.moveById(suiteId, toIndex) { it.id }
        if (moved === lib.suites) return@mutate Outcome.Apply(lib, Unit, Dirty())
        Outcome.Apply(lib.copy(suites = moved), Unit, Dirty(libraryFile = true))
    }

    /** The suite as a self-contained `indagium-test-suite` file's text. */
    fun exportSuite(suiteId: String): StoreResult<String> {
        val snapshot = state.value
        val suite = snapshot.suite(suiteId) ?: return StoreResult.NotFound("suite", suiteId)
        val assets = referencedGoldenPaths(snapshot, suite).isNotEmpty()
        return StoreResult.Ok(encodeSuiteFile(suite, externalAssetsRequired = assets))
    }

    /** Whether a metadata-only suite export needs its external golden image files to remain usable. */
    fun suiteRequiresExternalAssets(suiteId: String): Boolean =
        synchronized(lock) {
            val snapshot = state.value
            snapshot.suite(suiteId)?.let { referencedGoldenPaths(snapshot, it).isNotEmpty() } == true
        }

    fun exportSuiteToFile(suiteId: String, destination: File): StoreResult<File> {
        val text = when (val exported = exportSuite(suiteId)) {
            is StoreResult.Ok -> exported.value
            is StoreResult.NotFound -> return exported
            is StoreResult.Invalid -> return exported
            is StoreResult.LimitReached -> return exported
        }
        return try {
            writeFileAtomically(destination) { it.write(text) }
            StoreResult.Ok(destination)
        } catch (e: IOException) {
            StoreResult.Invalid("Could not write ${destination.name}: ${e.message}")
        }
    }

    /**
     * Adds a suite from the text of an exported file, with fresh ids throughout. A suite with more cases
     * than the edition allows is imported anyway; the extra cases are locked and a warning says so.
     */
    fun importSuite(text: String): StoreResult<TestSuite> {
        if (text.length > MAX_TEST_FILE_BYTES) return StoreResult.Invalid("This file is too large to be a test suite.")
        val decoded = decodeSuiteFile(text).getOrElse { return StoreResult.Invalid(it.message ?: "This file is not a test suite.") }
        if (decoded.readOnly) return StoreResult.Invalid("This suite was saved by a newer version of Indagium. Update Indagium to import it.")
        val lim = limits()
        return mutate { lib ->
            libraryFileRejection(lib)?.let { return@mutate it }
            val decision = decide(lib, LimitOperation.ImportSuite(decoded.suite.cases.size), lim)
            refusal(decision)?.let { return@mutate it }
            val warnings = mutableListOf<String>()
            (decision as? LimitDecision.AllowedWithWarning)?.let { warnings += it.warning }
            val now = clock()
            val base = decoded.suite.withFreshIds()
            val suite = base.copy(name = base.name.ifBlank { DEFAULT_IMPORTED_SUITE_NAME }, createdAt = now, updatedAt = now)
            danglingReferenceWarning(lib, suite)?.let { warnings += it }
            Outcome.Apply(lib.copy(suites = lib.suites + suite), suite, Dirty(setOf(suite.id), libraryFile = true), warnings)
        }
    }

    fun importSuiteFromFile(source: File): StoreResult<TestSuite> {
        if (!source.isFile) return StoreResult.Invalid("${source.name} is not a file.")
        if (source.length() > MAX_TEST_FILE_BYTES) return StoreResult.Invalid("${source.name} is too large to be a test suite.")
        val text = try {
            source.readText()
        } catch (e: IOException) {
            return StoreResult.Invalid("Could not read ${source.name}: ${e.message}")
        }
        return importSuite(text)
    }

    // ── Cases ────────────────────────────────────────────────────────

    /** Adds [case] at [atIndex] (default: the end). A blank id is replaced by a fresh one. */
    fun createCase(suiteId: String, case: TestCase, atIndex: Int? = null): StoreResult<TestCase> {
        val withId = if (case.id.isBlank()) case.copy(id = newCaseId()) else case
        return editSuite(
            locate = { it.suite(suiteId) },
            notFound = StoreResult.NotFound("suite", suiteId),
            operation = { LimitOperation.CreateCase(suiteId) },
        ) { lib, suite ->
            val problem = validateEntityId("Case", withId.id) ?: validateCase(withId)
            when {
                problem != null -> SuiteEdit.Fail(problem)
                lib.findCase(withId.id) != null -> SuiteEdit.Fail("A case with id '${withId.id}' already exists.")
                else -> {
                    val cases = suite.cases.toMutableList().apply { add((atIndex ?: size).coerceIn(0, size), withId) }
                    SuiteEdit.Done(suite.copy(cases = cases), withId)
                }
            }
        }
    }

    /** Replaces the case's own fields; its id and steps are kept (steps have their own operations). */
    fun updateCase(caseId: String, transform: (TestCase) -> TestCase): StoreResult<TestCase> =
        editSuite(
            locate = { lib -> lib.findCase(caseId)?.suite },
            notFound = StoreResult.NotFound("case", caseId),
            operation = { LimitOperation.EditCase(it.id, caseId) },
        ) { _, suite ->
            val old = suite.cases.first { it.id == caseId }
            val updated = transform(old).copy(id = caseId, steps = old.steps)
            validateCase(updated)?.let { return@editSuite SuiteEdit.Fail(it) }
            SuiteEdit.Done(suite.copy(cases = suite.cases.map { if (it.id == caseId) updated else it }), updated)
        }

    /** Deleting is always allowed, even for a locked case. */
    fun deleteCase(caseId: String): StoreResult<Unit> =
        editSuite(
            locate = { lib -> lib.findCase(caseId)?.suite },
            notFound = StoreResult.NotFound("case", caseId),
            operation = { null },
        ) { _, suite -> SuiteEdit.Done(suite.copy(cases = suite.cases.filterNot { it.id == caseId }), Unit) }

    /** A deep copy with new ids, placed right after the original. */
    fun duplicateCase(caseId: String): StoreResult<TestCase> =
        editSuite(
            locate = { lib -> lib.findCase(caseId)?.suite },
            notFound = StoreResult.NotFound("case", caseId),
            operation = { LimitOperation.DuplicateCase(it.id, caseId) },
        ) { _, suite ->
            val index = suite.cases.indexOfFirst { it.id == caseId }
            val copy = suite.cases[index].withFreshIds().let { it.copy(name = copyName(it.name)) }
            val cases = suite.cases.toMutableList().apply { add(index + 1, copy) }
            SuiteEdit.Done(suite.copy(cases = cases), copy)
        }

    /**
     * Moves a case to [toIndex] in its own suite, or into [toSuiteId] at that index. Reordering inside a
     * suite is always allowed; moving into another suite is refused when that suite is locked or full.
     */
    fun moveCase(caseId: String, toIndex: Int, toSuiteId: String? = null): StoreResult<TestCase> {
        val lim = limits()
        return mutate { lib ->
            val from = lib.findCase(caseId) ?: return@mutate notFound("case", caseId)
            if (toSuiteId == null || toSuiteId == from.suite.id) return@mutate reorderCaseOutcome(lib, from, toIndex)
            val target = lib.suite(toSuiteId) ?: return@mutate notFound("suite", toSuiteId)
            readOnlySuiteRejection(from.suite)?.let { return@mutate it }
            readOnlySuiteRejection(target)?.let { return@mutate it }
            refusal(decide(lib, LimitOperation.MoveCaseToSuite(caseId, toSuiteId), lim))?.let { return@mutate it }
            val now = clock()
            val source = from.suite.copy(cases = from.suite.cases.filterNot { it.id == caseId }, updatedAt = now)
            val destination = target.copy(
                cases = target.cases.toMutableList().apply { add(toIndex.coerceIn(0, size), from.case) },
                updatedAt = now,
            )
            val suites = lib.suites.map { if (it.id == source.id) source else if (it.id == destination.id) destination else it }
            Outcome.Apply(lib.copy(suites = suites), from.case, Dirty(setOf(source.id, destination.id)))
        }
    }

    private fun reorderCaseOutcome(lib: TestLibrary, from: CaseLocation, toIndex: Int): Outcome<TestCase> {
        readOnlySuiteRejection(from.suite)?.let { return it }
        val moved = from.suite.cases.moveById(from.case.id, toIndex) { it.id }
        if (moved === from.suite.cases) return Outcome.Apply(lib, from.case, Dirty())
        val suite = from.suite.copy(cases = moved, updatedAt = clock())
        return Outcome.Apply(lib.copy(suites = lib.suites.map { if (it.id == suite.id) suite else it }), from.case, Dirty(setOf(suite.id)))
    }

    // ── Steps ────────────────────────────────────────────────────────

    /** Adds [step] to the case at [atIndex] (default: the end). A blank id is replaced by a fresh one. */
    fun createStep(caseId: String, step: TestStep, atIndex: Int? = null): StoreResult<TestStep> {
        val withId = if (step.id.isBlank()) step.copy(id = newStepId()) else step
        return editCase(caseId) { lib, case ->
            val problem = validateEntityId("Step", withId.id) ?: validateStep(withId)
            when {
                problem != null -> CaseEdit.Fail(problem)
                lib.findStep(withId.id) != null -> CaseEdit.Fail("A step with id '${withId.id}' already exists.")
                else -> {
                    val steps = case.steps.toMutableList().apply { add((atIndex ?: size).coerceIn(0, size), withId) }
                    CaseEdit.Done(case.copy(steps = steps), withId)
                }
            }
        }
    }

    /** Inserts a reviewed sequence in one case edit with fresh step/check/example ids. */
    fun createSteps(caseId: String, steps: List<TestStep>, atIndex: Int? = null, creationUsage: AiUsageStats? = null): StoreResult<List<TestStep>> {
        if (steps.isEmpty()) return StoreResult.Invalid("At least one step is required.")
        val copies = steps.map { it.withFreshIds() }
        return editCase(caseId) { lib, case ->
            copies.forEachIndexed { index, step ->
                val problem = validateEntityId("Step", step.id) ?: validateStep(step)
                if (problem != null) return@editCase CaseEdit.Fail("Step ${index + 1}: $problem")
                if (lib.findStep(step.id) != null || case.steps.any { existing -> copies.any { it.id == existing.id } }) {
                    return@editCase CaseEdit.Fail("A step with an inserted id already exists.")
                }
            }
            val insertion = (atIndex ?: case.steps.size).coerceIn(0, case.steps.size)
            val updated = case.steps.toMutableList().apply { addAll(insertion, copies) }
            val usage = if (creationUsage == null) case.creationUsage else sumAiUsage(listOf(case.creationUsage, creationUsage))
            CaseEdit.Done(case.copy(steps = updated, creationUsage = usage), copies)
        }
    }

    /** Replaces the step; its id is kept. Checks and examples are replaced as whole ordered lists. */
    fun updateStep(stepId: String, transform: (TestStep) -> TestStep): StoreResult<TestStep> =
        editStep(stepId) { case, old ->
            val updated = transform(old).copy(id = stepId)
            val problem = validateStep(updated)
            if (problem != null) {
                CaseEdit.Fail(problem)
            } else {
                CaseEdit.Done(case.copy(steps = case.steps.map { if (it.id == stepId) updated else it }), updated)
            }
        }

    fun deleteStep(stepId: String): StoreResult<Unit> =
        editStep(stepId) { case, _ -> CaseEdit.Done(case.copy(steps = case.steps.filterNot { it.id == stepId }), Unit) }

    /** A copy with new ids, placed right after the original. */
    fun duplicateStep(stepId: String): StoreResult<TestStep> =
        editStep(stepId) { case, old ->
            val copy = old.withFreshIds()
            val index = case.steps.indexOfFirst { it.id == stepId }
            CaseEdit.Done(case.copy(steps = case.steps.toMutableList().apply { add(index + 1, copy) }), copy)
        }

    /** Moves the step to [toIndex] within its case. */
    fun moveStep(stepId: String, toIndex: Int): StoreResult<Unit> =
        editStep(stepId) { case, _ -> CaseEdit.Done(case.copy(steps = case.steps.moveById(stepId, toIndex) { it.id }), Unit) }

    // ── Scripts ──────────────────────────────────────────────────────

    /** Adds [script]. A blank id is replaced by a fresh one. */
    fun createScript(script: TestScript): StoreResult<TestScript> {
        val withId = if (script.id.isBlank()) script.copy(id = newScriptId()) else script
        return mutate { lib ->
            libraryFileRejection(lib)?.let { return@mutate it }
            val problem = validateEntityId("Script", withId.id)
                ?: (if (lib.script(withId.id) != null) "A script with id '${withId.id}' already exists." else null)
                ?: validateScript(withId, lib.scripts)
            if (problem != null) return@mutate invalid(problem)
            Outcome.Apply(lib.copy(scripts = lib.scripts + withId), withId, Dirty(libraryFile = true))
        }
    }

    /** Replaces the script; its id is kept. */
    fun updateScript(scriptId: String, transform: (TestScript) -> TestScript): StoreResult<TestScript> = mutate { lib ->
        libraryFileRejection(lib)?.let { return@mutate it }
        val old = lib.script(scriptId) ?: return@mutate notFound("script", scriptId)
        val transformed = transform(old)
        val updated = transformed.copy(id = scriptId, workingDir = transformed.workingDir?.takeIf { it.isNotBlank() })
        validateScript(updated, lib.scripts.filterNot { it.id == scriptId })?.let { return@mutate invalid(it) }
        Outcome.Apply(lib.copy(scripts = lib.scripts.map { if (it.id == scriptId) updated else it }), updated, Dirty(libraryFile = true))
    }

    /** Hooks and checks that reference a deleted script are left in place (data is never dropped). */
    fun deleteScript(scriptId: String): StoreResult<Unit> = mutate { lib ->
        libraryFileRejection(lib)?.let { return@mutate it }
        if (lib.script(scriptId) == null) return@mutate notFound("script", scriptId)
        Outcome.Apply(lib.copy(scripts = lib.scripts.filterNot { it.id == scriptId }), Unit, Dirty(libraryFile = true))
    }

    fun moveScript(scriptId: String, toIndex: Int): StoreResult<Unit> = mutate { lib ->
        libraryFileRejection(lib)?.let { return@mutate it }
        if (lib.script(scriptId) == null) return@mutate notFound("script", scriptId)
        Outcome.Apply(lib.copy(scripts = lib.scripts.moveById(scriptId, toIndex) { it.id }), Unit, Dirty(libraryFile = true))
    }

    // ── Shared steps ─────────────────────────────────────────────────

    /** Adds [shared]. A blank id is replaced by a fresh one. */
    fun createSharedStep(shared: SharedStep): StoreResult<SharedStep> {
        val withId = if (shared.id.isBlank()) shared.copy(id = newSharedStepId()) else shared
        return mutate { lib ->
            libraryFileRejection(lib)?.let { return@mutate it }
            val problem = validateEntityId("Shared step", withId.id)
                ?: (if (lib.sharedStep(withId.id) != null) "A shared step with id '${withId.id}' already exists." else null)
                ?: validateSharedStep(withId)
            if (problem != null) return@mutate invalid(problem)
            Outcome.Apply(lib.copy(sharedSteps = lib.sharedSteps + withId), withId, Dirty(libraryFile = true))
        }
    }

    /** Replaces the shared step; its id is kept. */
    fun updateSharedStep(sharedId: String, transform: (SharedStep) -> SharedStep): StoreResult<SharedStep> = mutate { lib ->
        libraryFileRejection(lib)?.let { return@mutate it }
        val old = lib.sharedStep(sharedId) ?: return@mutate notFound("shared step", sharedId)
        val updated = transform(old).copy(id = sharedId)
        validateSharedStep(updated)?.let { return@mutate invalid(it) }
        Outcome.Apply(lib.copy(sharedSteps = lib.sharedSteps.map { if (it.id == sharedId) updated else it }), updated, Dirty(libraryFile = true))
    }

    fun deleteSharedStep(sharedId: String): StoreResult<Unit> = mutate { lib ->
        libraryFileRejection(lib)?.let { return@mutate it }
        if (lib.sharedStep(sharedId) == null) return@mutate notFound("shared step", sharedId)
        Outcome.Apply(lib.copy(sharedSteps = lib.sharedSteps.filterNot { it.id == sharedId }), Unit, Dirty(libraryFile = true))
    }

    fun moveSharedStep(sharedId: String, toIndex: Int): StoreResult<Unit> = mutate { lib ->
        libraryFileRejection(lib)?.let { return@mutate it }
        if (lib.sharedStep(sharedId) == null) return@mutate notFound("shared step", sharedId)
        Outcome.Apply(lib.copy(sharedSteps = lib.sharedSteps.moveById(sharedId, toIndex) { it.id }), Unit, Dirty(libraryFile = true))
    }

    // ── Mutation machinery ───────────────────────────────────────────

    /** What an operation changed on disk: the suite files to rewrite (or delete) and whether library.json is dirty. */
    private class Dirty(val suiteIds: Set<String> = emptySet(), val libraryFile: Boolean = false)

    private sealed interface Outcome<out T> {
        class Reject(val result: StoreResult<Nothing>) : Outcome<Nothing>

        class Apply<T>(
            val library: TestLibrary,
            val value: T,
            val dirty: Dirty,
            val warnings: List<String> = emptyList(),
        ) : Outcome<T>
    }

    private sealed interface SuiteEdit<out T> {
        class Fail(val reason: String) : SuiteEdit<Nothing>

        class Done<T>(val suite: TestSuite, val value: T) : SuiteEdit<T>
    }

    private sealed interface CaseEdit<out T> {
        class Fail(val reason: String) : CaseEdit<Nothing>

        class Done<T>(val case: TestCase, val value: T) : CaseEdit<T>
    }

    private fun invalid(reason: String) = Outcome.Reject(StoreResult.Invalid(reason))

    private fun notFound(kind: String, id: String) = Outcome.Reject(StoreResult.NotFound(kind, id))

    private fun libraryFileRejection(lib: TestLibrary): Outcome.Reject? = if (lib.readOnly) invalid(LIBRARY_READ_ONLY_REASON) else null

    private fun readOnlySuiteRejection(suite: TestSuite): Outcome.Reject? = if (suite.readOnly) invalid(SUITE_READ_ONLY_REASON) else null

    /** A [LimitDecision.Refused] as a rejected outcome; null for Allowed and AllowedWithWarning. */
    private fun refusal(decision: LimitDecision): Outcome.Reject? =
        (decision as? LimitDecision.Refused)?.let { Outcome.Reject(StoreResult.LimitReached(it)) }

    /** Runs [block] on the current library under [lock], publishes the library it returns, then writes the changed files. */
    private fun <T> mutate(block: (TestLibrary) -> Outcome<T>): StoreResult<T> {
        val applied = synchronized(lock) {
            when (val outcome = block(state.value)) {
                is Outcome.Reject -> return outcome.result
                is Outcome.Apply -> outcome.also { state.value = it.library }
            }
        }
        persist(applied.dirty)
        return StoreResult.Ok(applied.value, applied.warnings)
    }

    /** One suite-level edit: find the suite, check read-only and the edition limits, apply [edit], bump `updatedAt`. */
    private fun <T> editSuite(
        locate: (TestLibrary) -> TestSuite?,
        notFound: StoreResult.NotFound,
        operation: (TestSuite) -> LimitOperation?,
        edit: (TestLibrary, TestSuite) -> SuiteEdit<T>,
    ): StoreResult<T> {
        val lim = limits()
        return mutate { lib ->
            val suite = locate(lib) ?: return@mutate Outcome.Reject(notFound)
            readOnlySuiteRejection(suite)?.let { return@mutate it }
            operation(suite)?.let { op -> refusal(decide(lib, op, lim)) }?.let { return@mutate it }
            when (val result = edit(lib, suite)) {
                is SuiteEdit.Fail -> invalid(result.reason)
                is SuiteEdit.Done -> {
                    val updated = result.suite.copy(updatedAt = clock())
                    Outcome.Apply(lib.copy(suites = lib.suites.map { if (it.id == updated.id) updated else it }), result.value, Dirty(setOf(updated.id)))
                }
            }
        }
    }

    /** A case-level edit (adding/removing/changing steps) on the case [caseId]. */
    private fun <T> editCase(caseId: String, edit: (TestLibrary, TestCase) -> CaseEdit<T>): StoreResult<T> =
        editSuite(
            locate = { lib -> lib.findCase(caseId)?.suite },
            notFound = StoreResult.NotFound("case", caseId),
            operation = { LimitOperation.EditCase(it.id, caseId) },
        ) { lib, suite -> applyCaseEdit(lib, suite, caseId, edit) }

    private fun <T> editStep(stepId: String, edit: (TestCase, TestStep) -> CaseEdit<T>): StoreResult<T> =
        editSuite(
            locate = { lib -> lib.findStep(stepId)?.suite },
            notFound = StoreResult.NotFound("step", stepId),
            operation = { suite -> suite.cases.firstOrNull { c -> c.steps.any { it.id == stepId } }?.let { LimitOperation.EditCase(suite.id, it.id) } },
        ) { lib, suite ->
            val location: StepLocation = lib.findStep(stepId) ?: return@editSuite SuiteEdit.Fail("No step with id '$stepId'.")
            applyCaseEdit(lib, suite, location.case.id) { _, case -> edit(case, location.step) }
        }

    private fun <T> applyCaseEdit(
        lib: TestLibrary,
        suite: TestSuite,
        caseId: String,
        edit: (TestLibrary, TestCase) -> CaseEdit<T>,
    ): SuiteEdit<T> {
        val case = suite.cases.firstOrNull { it.id == caseId } ?: return SuiteEdit.Fail("No case with id '$caseId'.")
        return when (val result = edit(lib, case)) {
            is CaseEdit.Fail -> SuiteEdit.Fail(result.reason)
            is CaseEdit.Done -> SuiteEdit.Done(suite.copy(cases = suite.cases.map { if (it.id == caseId) result.case else it }), result.value)
        }
    }

    private fun danglingReferenceWarning(lib: TestLibrary, suite: TestSuite): String? {
        val hooks = suite.setup + suite.teardown + suite.cases.flatMap { it.setup + it.teardown }
        val checks = suite.cases.flatMap { c -> c.steps.flatMap { it.checks } }
        val missingScripts = hooks.filterIsInstance<HookItem.Script>().count { lib.script(it.scriptId) == null } +
            checks.filterIsInstance<StepCheck.ScriptResult>().count { lib.script(it.scriptId) == null }
        val missingShared = hooks.filterIsInstance<HookItem.Shared>().count { lib.sharedStep(it.sharedStepId) == null }
        val total = missingScripts + missingShared
        if (total == 0) return null
        return "$total reference(s) point to scripts or shared steps that are not in this library."
    }

    /** Stage every golden image referenced by [source] and publish the complete directory as one move. */
    private data class AssetStage(val stageDir: File?, val destinationDir: File?, val error: String? = null)

    @Suppress("ReturnCount", "TooGenericExceptionCaught") // Each failure has an actionable asset error and staged files are removed on any copy failure.
    private fun stageSuiteAssets(lib: TestLibrary, source: TestSuite, destinationId: String): AssetStage {
        val paths = referencedGoldenPaths(lib, source)
        if (paths.isEmpty()) return AssetStage(null, null)
        val assetsRoot = File(rootDir, TEST_ASSETS_DIR_NAME)
        if (Files.isSymbolicLink(assetsRoot.toPath())) {
            return AssetStage(null, null, "Could not duplicate suite: the golden image asset folder must not be a symbolic link.")
        }
        val stage = File(assetsRoot, ".stage-${UUID.randomUUID()}")
        val destination = testAssetDir(rootDir, destinationId)
        var handedOff = false
        try {
            if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return AssetStage(null, null, "Could not duplicate suite assets: the destination asset folder already exists.")
            }
            Files.createDirectories(stage.toPath())
            if (Files.isSymbolicLink(stage.toPath()) || !stage.canonicalFile.toPath().startsWith(assetsRoot.canonicalFile.toPath())) {
                return AssetStage(null, null, "Could not duplicate suite: staging directory escaped the golden image asset folder.")
            }
            val stageRoot = stage.canonicalFile
            for (path in paths) {
                val sourceFile = resolveTestAsset(rootDir, source.id, path)
                    ?: return AssetStage(null, null, "Could not duplicate suite: golden image path '$path' is not a safe relative asset path.")
                if (!sourceFile.isFile) return AssetStage(null, null, "Could not duplicate suite: referenced golden image '$path' is missing.")
                val target = File(stage, path).canonicalFile
                if (target != stageRoot && !target.path.startsWith(stageRoot.path + File.separator)) {
                    return AssetStage(null, null, "Could not duplicate suite: golden image path '$path' escapes the asset folder.")
                }
                Files.createDirectories(target.parentFile.toPath())
                copyAsset(sourceFile, target)
            }
            handedOff = true
            return AssetStage(stage, destination)
        } catch (failure: Exception) {
            stage.deleteRecursively()
            return AssetStage(null, null, "Could not duplicate suite golden images: ${failure.message ?: "file copy failed"}. The library was not changed.")
        } finally {
            if (!handedOff && stage.exists()) stage.deleteRecursively()
        }
    }

    private fun referencedGoldenPaths(lib: TestLibrary, suite: TestSuite): List<String> {
        val steps = suite.cases.flatMap { it.steps }
        val sharedIds = referencedSharedIds(suite)
        return (steps + lib.sharedSteps.filter { it.id in sharedIds }.flatMap { it.steps })
            .flatMap { it.examples }
            .filterIsInstance<StepExample.GoldenScreenshot>()
            .map { it.assetPath }
            .distinct()
    }

    private fun referencedSharedIds(suite: TestSuite): Set<String> =
        (suite.setup + suite.teardown + suite.cases.flatMap { it.setup + it.teardown })
            .filterIsInstance<HookItem.Shared>().map { it.sharedStepId }.toSet()

    // ── Disk ─────────────────────────────────────────────────────────

    private fun suiteFile(id: String) = File(suitesDir, id + SUITE_FILE_EXTENSION)

    /**
     * Brings the files in line with the freshest library. The library is read AFTER [writeLock] is held
     * (see the class doc), so whichever write lands last always carries the newest state. Suites that no
     * longer exist have their file deleted.
     */
    private fun persist(dirty: Dirty) {
        if (dirty.suiteIds.isEmpty() && !dirty.libraryFile) return
        writeLock.withLock {
            val snapshot = state.value
            var failure: String? = null

            fun attempt(what: String, action: () -> Unit) {
                try {
                    action()
                } catch (e: IOException) {
                    failure = failure ?: "Could not save $what: ${e.message}"
                }
            }
            dirty.suiteIds.forEach { id ->
                val suite = snapshot.suite(id)
                when {
                    suite == null -> attempt("suite $id") { Files.deleteIfExists(suiteFile(id).toPath()) }
                    suite.readOnly -> Unit
                    else -> attempt("suite ${suite.name}") { writeFileAtomically(suiteFile(id)) { it.write(encodeSuiteFile(suite)) } }
                }
            }
            if (dirty.libraryFile && !snapshot.readOnly) {
                attempt("the test library") {
                    writeFileAtomically(libraryFile) { it.write(encodeLibraryFile(snapshot.suites.map { s -> s.id }, snapshot.scripts, snapshot.sharedSteps)) }
                }
            }
            persistErrorState.value = failure
        }
    }

    private class Loaded(val library: TestLibrary, val issues: List<String>)

    private fun readCapped(file: File): String? =
        if (file.isFile && file.length() <= MAX_TEST_FILE_BYTES) runCatching { file.readText() }.getOrNull() else null

    private fun loadFromDisk(): Loaded {
        val issues = mutableListOf<String>()
        val libraryFileData = loadLibraryFile(issues)
        val byId = LinkedHashMap<String, TestSuite>()
        val files = suitesDir.listFiles { f -> f.isFile && f.name.endsWith(SUITE_FILE_EXTENSION) && !f.name.startsWith(".") }
            .orEmpty().sortedBy { it.name }
        for (file in files) {
            val suite = loadSuiteFile(file, issues)
            when {
                suite == null -> Unit
                suite.id in byId -> issues += "Skipped ${file.name}: duplicate suite id ${suite.id}."
                else -> byId[suite.id] = suite
            }
        }
        val ordered = libraryFileData.suiteOrder.mapNotNull { byId.remove(it) } +
            byId.values.sortedWith(compareBy<TestSuite> { it.createdAt }.thenBy { it.id })
        return Loaded(
            TestLibrary(ordered, libraryFileData.scripts, libraryFileData.sharedSteps, libraryFileData.readOnly),
            issues,
        )
    }

    private fun loadLibraryFile(issues: MutableList<String>): DecodedLibraryFile {
        val empty = DecodedLibraryFile(emptyList(), emptyList(), emptyList(), readOnly = false)
        if (!libraryFile.exists()) return empty
        val text = readCapped(libraryFile)
        if (text == null) {
            issues += "Could not read $TEST_LIBRARY_FILE_NAME."
            return empty
        }
        return decodeLibraryFile(text).getOrElse { failure ->
            // Set the damaged file aside so the next save does not overwrite it with an empty library.
            val aside = File(rootDir, "$TEST_LIBRARY_FILE_NAME.corrupt-${clock()}")
            runCatching { Files.move(libraryFile.toPath(), aside.toPath()) }
            issues += "$TEST_LIBRARY_FILE_NAME was unreadable (${failure.message}); it was moved to ${aside.name}."
            empty
        }
    }

    private fun loadSuiteFile(file: File, issues: MutableList<String>): TestSuite? {
        val text = readCapped(file)
        if (text == null) {
            issues += "Skipped ${file.name}: unreadable or too large."
            return null
        }
        val decoded = decodeSuiteFile(text).getOrElse { failure ->
            issues += "Skipped ${file.name}: ${failure.message}"
            return null
        }
        if (decoded.suite.id + SUITE_FILE_EXTENSION != file.name) {
            issues += "Skipped ${file.name}: its suite id (${decoded.suite.id}) does not match the file name."
            return null
        }
        return decoded.suite
    }
}
