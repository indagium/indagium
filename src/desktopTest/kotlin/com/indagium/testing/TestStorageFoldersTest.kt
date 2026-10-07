package com.indagium.testing

import com.indagium.testing.model.EXTERNAL_LANE_PROFILE_ID
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestStep
import com.indagium.testing.run.EngineTuning
import com.indagium.testing.run.LaneDeviceOpener
import com.indagium.testing.run.StartRunResult
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.TestLibraryStore
import com.indagium.ui.AppState
import com.indagium.ui.SaveFolderKind
import com.indagium.ui.TestRunOverrides
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val AWAIT_MS = 15_000L
private const val POLL_MS = 15L

/** The three AI test folders follow the settings at runtime: re-root, refusal while a run is active, and the startup migration. */
class TestStorageFoldersTest {
    private lateinit var dir: File
    private lateinit var saveRoot: File
    private lateinit var legacy: File
    private val states = mutableListOf<AppState>()
    private val adb = ScriptedAdbRunner()

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("test-storage-folders").toFile()
        saveRoot = File(dir, "Indagium")
        legacy = File(dir, "testing")
    }

    @AfterTest
    fun tearDown() {
        states.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun appState(withSaveRoot: Boolean = true, autosave: String = "state.cache", restore: Boolean = false): AppState = AppState(
        autosaveFile = File(dir, autosave),
        restoreOnCreate = restore,
        autoExportNotes = false,
        notesDir = File(dir, "notes"),
        archiveCacheDir = File(dir, "archive"),
        customCommandsDir = File(dir, "commands"),
        controlTokenFile = File(dir, "token"),
        sourceIndexFile = File(dir, "source-index"),
        testingDir = legacy,
        platformDefaultSaveRootDir = saveRoot.takeIf { withSaveRoot },
    ).also { states += it }

    private fun libraryIn(folder: File, vararg suiteNames: String) {
        val store = TestLibraryStore(folder)
        suiteNames.forEach { assertIs<StoreResult.Ok<*>>(store.createSuite(it)) }
    }

    private fun AppState.suiteNames() = testLibrary.suites.map { it.name }

    @Test
    fun theDefaultsAreSubfoldersOfTheDefaultSaveFolderAndAnEmptyStartWritesNothing() {
        val state = appState()

        assertEquals(File(saveRoot, "test-suites"), state.effectiveTestSuitesDir())
        assertEquals(File(saveRoot, "test-runs"), state.effectiveTestRunsDir())
        assertEquals(File(saveRoot, "test-issues"), state.effectiveTestIssuesDir())
        assertEquals(Triple(File(saveRoot, "test-suites"), File(saveRoot, "test-runs"), File(saveRoot, "test-issues")), state.activeTestFolders())
        assertTrue(state.testLibrary.suites.isEmpty())
        assertFalse(saveRoot.exists(), "nothing is created until the first write")
    }

    @Test
    fun withNoSaveRootTheFoldersStayInsideTheInjectedLegacyDirectory() {
        val state = appState(withSaveRoot = false)

        assertEquals(legacy, state.effectiveTestSuitesDir())
        assertEquals(File(legacy, "runs"), state.effectiveTestRunsDir())
        assertEquals(File(legacy, "issues"), state.effectiveTestIssuesDir())
        state.createTestSuite("Bare")
        assertTrue(File(legacy, "library.json").isFile)
    }

    @Test
    fun anExplicitFolderOverridesTheDefaultForThatFolderOnly() {
        val state = appState()
        val mine = File(dir, "mine")

        assertTrue(state.setSaveFolder(SaveFolderKind.TEST_RUNS, mine.absolutePath))

        assertEquals(mine, state.effectiveTestRunsDir())
        assertEquals(File(saveRoot, "test-suites"), state.effectiveTestSuitesDir())
        assertEquals(File(saveRoot, "test-issues"), state.effectiveTestIssuesDir())
        assertEquals(mine, state.activeTestFolders().second)
        assertEquals(mine, state.testRunCoordinator.runDir("r1").parentFile)
    }

    @Test
    fun changingTheTestSuitesFolderReloadsTheLibraryFromTheNewFolderAndBack() {
        val other = File(dir, "other-suites")
        libraryIn(other, "From other")
        val state = appState()
        state.createTestSuite("In default")
        assertEquals(listOf("In default"), state.suiteNames())

        assertTrue(state.setSaveFolder(SaveFolderKind.TEST_SUITES, other.absolutePath))

        assertEquals(listOf("From other"), state.suiteNames(), "the mirror follows the new folder")
        state.createTestSuite("Added after switching")
        assertEquals(listOf("From other", "Added after switching"), state.suiteNames())
        assertEquals(2, TestLibraryStore(other).library.value.suites.size, "writes go to the new folder")
        assertEquals(1, TestLibraryStore(File(saveRoot, "test-suites")).library.value.suites.size, "the old folder is untouched")

        assertTrue(state.setSaveFolder(SaveFolderKind.TEST_SUITES, null))
        assertEquals(listOf("In default"), state.suiteNames())
    }

    @Test
    fun goldenImagesAreImportedIntoAndResolvedFromTheCurrentSuitesFolder() {
        val other = File(dir, "other-suites")
        val state = appState()
        state.setSaveFolder(SaveFolderKind.TEST_SUITES, other.absolutePath)
        val suite = (state.createTestSuite("With image") as StoreResult.Ok).value
        val source = File(dir, "shot.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val name = (state.importTestGoldenImage(suite.id, source) as StoreResult.Ok).value

        assertTrue(File(other, "assets/${suite.id}/$name").isFile)
        assertEquals(File(other, "assets/${suite.id}/$name").canonicalFile, state.testGoldenImageFile(suite.id, name)?.canonicalFile)
    }

    @Test
    fun changingTheDefaultSaveFolderMovesEveryDefaultFolderWithIt() {
        val newRoot = File(dir, "NewRoot")
        libraryIn(File(newRoot, "test-suites"), "At new root")
        val state = appState()
        state.createTestSuite("At old root")

        assertTrue(state.setSaveFolder(SaveFolderKind.ROOT, newRoot.absolutePath))

        assertEquals(listOf("At new root"), state.suiteNames())
        assertEquals(Triple(File(newRoot, "test-suites"), File(newRoot, "test-runs"), File(newRoot, "test-issues")), state.activeTestFolders())
        assertEquals(File(newRoot, "test-issues/x1"), state.issueStore.issueDir("x1"))
    }

    @Test
    fun anExplicitTestFolderDoesNotMoveWhenTheDefaultSaveFolderChanges() {
        val mine = File(dir, "mine")
        val state = appState()
        state.setSaveFolder(SaveFolderKind.TEST_ISSUES, mine.absolutePath)

        state.setSaveFolder(SaveFolderKind.ROOT, File(dir, "NewRoot").absolutePath)

        assertEquals(mine, state.activeTestFolders().third)
        assertEquals(File(dir, "NewRoot/test-runs"), state.activeTestFolders().second)
    }

    @Test
    fun theChosenFoldersSurviveARestartAndOpenTheirOwnData() {
        val mine = File(dir, "mine-suites")
        libraryIn(mine, "Mine")
        val first = appState()
        first.setSaveFolder(SaveFolderKind.TEST_SUITES, mine.absolutePath)
        first.autosaveNow()
        first.close()

        val second = appState(restore = true)

        assertEquals(mine.absolutePath, second.settings.testSuitesDir)
        assertEquals(listOf("Mine"), second.suiteNames())
        assertEquals(mine, second.activeTestFolders().first)
    }

    private fun startExternalRun(state: AppState): String {
        state.testRunOverrides = TestRunOverrides(
            openDevice = LaneDeviceOpener { _, laneDir, _ -> openFixtureSession(adb, laneDir) },
            deviceProblem = { null },
            tuning = EngineTuning(persistDebounceMs = 50L),
        )
        val suite = (state.createTestSuite("Running") as StoreResult.Ok).value
        state.updateTestSuite(suite.id) { it.copy(targetPackage = "com.example.app") }
        val case = (state.createTestCase(suite.id, TestCase("", "Case")) as StoreResult.Ok).value
        state.createTestStep(case.id, TestStep("", "Open the app", "It opens"))
        val lane = LaneConfig(kind = LaneKind.EXTERNAL, profileId = EXTERNAL_LANE_PROFILE_ID, deviceSerial = FIXTURE_SERIAL)
        val started = runBlocking { state.testRunCoordinator.start(RunConfig(suite.id, null, listOf(lane))) }
        return assertIs<StartRunResult.Started>(started).runId
    }

    private fun awaitUntil(condition: () -> Boolean) = runBlocking {
        withTimeout(AWAIT_MS) { while (!condition()) delay(POLL_MS) }
    }

    @Test
    fun theTestFoldersCannotChangeWhileARunIsActive() {
        val state = appState()
        val runId = startExternalRun(state)
        assertTrue(state.isTestRunActive())
        val before = state.activeTestFolders()
        val other = File(dir, "elsewhere")

        for (kind in listOf(SaveFolderKind.TEST_SUITES, SaveFolderKind.TEST_RUNS, SaveFolderKind.TEST_ISSUES, SaveFolderKind.ROOT)) {
            assertFalse(state.setSaveFolder(kind, other.absolutePath), kind.name)
        }

        assertEquals(before, state.activeTestFolders())
        assertNull(state.settings.testSuitesDir)
        assertNull(state.settings.saveRootDir)
        assertNotNull(state.testStorageStatus)
        assertTrue(state.setSaveFolder(SaveFolderKind.ANALYSIS, other.absolutePath), "folders that do not touch the test data stay editable")
        state.testRunCoordinator.cancel(runId)
        awaitUntil { !state.isTestRunActive() }
        awaitUntil { state.testStorageStatus == null }
        assertTrue(state.setSaveFolder(SaveFolderKind.TEST_RUNS, other.absolutePath))
        assertEquals(other, state.activeTestFolders().second)
    }

    @Test
    fun aSettingChangedByOtherMeansWhileARunIsActiveIsAppliedWhenTheRunEnds() {
        val state = appState()
        val runId = startExternalRun(state)
        val oldRuns = state.activeTestFolders().second
        val other = File(dir, "later-runs")

        state.updateSettings { it.copy(testRunsDir = other.absolutePath) }

        assertEquals(oldRuns, state.activeTestFolders().second, "the run keeps writing where it started")
        assertNotNull(state.testStorageStatus)
        state.testRunCoordinator.cancel(runId)
        awaitUntil { state.activeTestFolders().second == other }
        assertNull(state.testStorageStatus)
        assertTrue(state.testRuns.isEmpty(), "the finished run belongs to the old folder")
    }

    @Test
    fun startupMovesTheLegacyDataIntoTheResolvedFoldersAndKeepsWhatItCannotOverwrite() {
        libraryIn(legacy, "Legacy suite")
        File(legacy, "issues/i1").mkdirs()
        File(legacy, "issues/i1/issue.json").writeText("issue")
        File(legacy, "runs/r1").mkdirs()
        File(legacy, "runs/r1/run.json").writeText("run")
        File(saveRoot, "test-runs/mine").mkdirs()

        val state = appState()

        assertEquals(listOf("Legacy suite"), state.suiteNames())
        assertTrue(File(saveRoot, "test-suites/library.json").isFile)
        assertEquals("issue", File(saveRoot, "test-issues/i1/issue.json").readText())
        assertEquals("run", File(legacy, "runs/r1/run.json").readText(), "the occupied runs folder keeps the legacy runs where they were")
        assertFalse(File(legacy, "library.json").exists())
    }

    @Test
    fun startupNeverOverwritesALibraryAlreadyInTheResolvedFolder() {
        libraryIn(legacy, "Legacy suite")
        libraryIn(File(saveRoot, "test-suites"), "Already here")

        val state = appState()

        assertEquals(listOf("Already here"), state.suiteNames())
        assertTrue(File(legacy, "library.json").isFile)
    }

    @Test
    fun aBareStateWithNoSaveRootNeverMovesItsOwnDataOrWritesAMarker() {
        libraryIn(legacy, "Bare suite")

        val state = appState(withSaveRoot = false)

        assertEquals(listOf("Bare suite"), state.suiteNames())
        assertTrue(File(legacy, "library.json").isFile)
        assertEquals(listOf("library.json", "suites"), legacy.list()!!.sorted())
    }
}
