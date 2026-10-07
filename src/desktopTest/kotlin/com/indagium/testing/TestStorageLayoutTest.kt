package com.indagium.testing

import com.indagium.testing.store.TEST_STORAGE_MIGRATED_MARKER
import com.indagium.testing.store.migrateLegacyTestStorage
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val LEGACY_LIBRARY = "legacy library"
private const val LEGACY_SUITE = "legacy suite"

/** The one-time move of the old `<app data>/testing` layout into the three configurable folders. */
class TestStorageLayoutTest {
    private lateinit var root: File
    private lateinit var legacy: File
    private lateinit var suites: File
    private lateinit var issues: File
    private lateinit var runs: File

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("test-storage-layout").toFile()
        legacy = File(root, "app-data/testing")
        suites = File(root, "Indagium/test-suites")
        issues = File(root, "Indagium/test-issues")
        runs = File(root, "Indagium/test-runs")
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(file: File, text: String) {
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private fun fillLegacy() {
        write(File(legacy, "library.json"), LEGACY_LIBRARY)
        write(File(legacy, "suites/s1.json"), LEGACY_SUITE)
        write(File(legacy, "assets/s1/shot.png"), "png")
        write(File(legacy, "issues/i1/issue.json"), "issue")
        write(File(legacy, "issues/i1/attachments/log.txt"), "log")
        write(File(legacy, "runs/r1/run.json"), "run")
    }

    private fun migrate() = migrateLegacyTestStorage(legacy, suites, issues, runs)

    @Test
    fun aFreshStartWithNoLegacyFolderDoesNothingAndCreatesNothing() {
        val report = migrate()

        assertTrue(report.isEmpty)
        assertFalse(legacy.exists())
        assertFalse(suites.exists() || issues.exists() || runs.exists())
    }

    @Test
    fun theLibraryIssuesAndRunsMoveToTheirFoldersAndTheEmptyLegacyFolderIsRemoved() {
        fillLegacy()

        val report = migrate()

        assertEquals(LEGACY_LIBRARY, File(suites, "library.json").readText())
        assertEquals(LEGACY_SUITE, File(suites, "suites/s1.json").readText())
        assertEquals("png", File(suites, "assets/s1/shot.png").readText())
        assertEquals("issue", File(issues, "i1/issue.json").readText())
        assertEquals("log", File(issues, "i1/attachments/log.txt").readText())
        assertEquals("run", File(runs, "r1/run.json").readText())
        assertEquals(5, report.moved.size, report.moved.toString())
        assertTrue(report.failed.isEmpty() && report.kept.isEmpty())
        assertFalse(legacy.exists(), "nothing is left, so the old folder is removed")
    }

    @Test
    fun aTargetThatAlreadyHasALibraryIsNeverOverwrittenAndTheLegacyLibraryStaysPut() {
        fillLegacy()
        write(File(suites, "library.json"), "new library")
        write(File(suites, "suites/s9.json"), "new suite")

        val report = migrate()

        assertEquals("new library", File(suites, "library.json").readText())
        assertEquals("new suite", File(suites, "suites/s9.json").readText())
        assertFalse(File(suites, "suites/s1.json").exists())
        assertEquals(LEGACY_LIBRARY, File(legacy, "library.json").readText())
        assertEquals(LEGACY_SUITE, File(legacy, "suites/s1.json").readText())
        assertTrue(report.kept.isNotEmpty())
        assertEquals("issue", File(issues, "i1/issue.json").readText(), "issues and runs still move into their empty folders")
        assertTrue(File(legacy, TEST_STORAGE_MIGRATED_MARKER).exists())
    }

    @Test
    fun issuesAndRunsOnlyMoveIntoAnEmptyFolder() {
        fillLegacy()
        write(File(issues, "mine/issue.json"), "mine")

        val report = migrate()

        assertEquals("issue", File(legacy, "issues/i1/issue.json").readText(), "the occupied Issues folder keeps the legacy issues in place")
        assertFalse(File(issues, "i1").exists())
        assertEquals("mine", File(issues, "mine/issue.json").readText())
        assertEquals("run", File(runs, "r1/run.json").readText())
        assertEquals(1, report.kept.size, report.kept.toString())
    }

    @Test
    fun aFileThatExistsOnBothSidesOfAMergeStaysAsItIs() {
        write(File(legacy, "library.json"), LEGACY_LIBRARY)
        write(File(legacy, "suites/s1.json"), LEGACY_SUITE)
        write(File(legacy, "suites/s2.json"), "second")
        write(File(suites, "suites/s1.json"), "target copy")

        val report = migrate()

        assertEquals("target copy", File(suites, "suites/s1.json").readText())
        assertEquals("second", File(suites, "suites/s2.json").readText())
        assertEquals(LEGACY_LIBRARY, File(suites, "library.json").readText())
        assertEquals(LEGACY_SUITE, File(legacy, "suites/s1.json").readText(), "the colliding source file is left in place")
        assertTrue(report.kept.any { it.contains("s1.json") }, report.kept.toString())
    }

    @Test
    fun aLayoutThatNeverChangedIsLeftAloneWithoutAMarker() {
        write(File(legacy, "library.json"), LEGACY_LIBRARY)
        write(File(legacy, "issues/i1/issue.json"), "issue")
        write(File(legacy, "runs/r1/run.json"), "run")

        val report = migrateLegacyTestStorage(legacy, legacy, File(legacy, "issues"), File(legacy, "runs"))

        assertTrue(report.isEmpty)
        assertEquals(LEGACY_LIBRARY, File(legacy, "library.json").readText())
        assertFalse(File(legacy, TEST_STORAGE_MIGRATED_MARKER).exists())
    }

    @Test
    fun leftoversAreNotMovedLaterIntoAnotherFolder() {
        fillLegacy()
        write(File(suites, "library.json"), "new library")
        migrate()
        File(suites, "library.json").delete()

        val second = migrate()

        assertTrue(second.isEmpty)
        assertFalse(File(suites, "suites/s1.json").exists())
        assertEquals(LEGACY_LIBRARY, File(legacy, "library.json").readText())
    }

    @Test
    fun aMoveThatFailsLeavesTheSourceInPlaceAndIsReportedOnce() {
        fillLegacy()
        val blocker = File(root, "blocker").apply { writeText("a file where a folder is needed") }
        val blockedSuites = File(blocker, "test-suites")

        val report = migrateLegacyTestStorage(legacy, blockedSuites, issues, runs)

        assertEquals(LEGACY_LIBRARY, File(legacy, "library.json").readText())
        assertEquals(LEGACY_SUITE, File(legacy, "suites/s1.json").readText())
        assertTrue(report.failed.isNotEmpty(), report.toString())
        assertEquals("issue", File(issues, "i1/issue.json").readText(), "the other folders still moved")
        assertFalse(File(legacy, TEST_STORAGE_MIGRATED_MARKER).exists(), "a failure is retried at the next start")
    }
}
