package com.indagium.testing

import com.indagium.edition.Edition
import com.indagium.edition.EditionService
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppStateTestLibraryTest {
    private fun appState(dir: File, edition: EditionService = EditionService()) = AppState(
        autosaveFile = File(dir, "state.cache"),
        autoExportNotes = false,
        notesDir = File(dir, "notes"),
        archiveCacheDir = File(dir, "archive"),
        customCommandsDir = File(dir, "commands"),
        controlTokenFile = File(dir, "token"),
        sourceIndexFile = File(dir, "source-index"),
        testingDir = File(dir, "testing"),
        editionService = edition,
    )

    @Test
    fun delegatesMirrorTheStoreIntoComposeStateAndPersistUnderTheInjectedDirectory() {
        val dir = createTempDirectory("appstate-testing").toFile()
        val app = appState(dir)
        try {
            val suite = (app.createTestSuite("Smoke") as StoreResult.Ok).value
            val case = (app.createTestCase(suite.id, plainCase("c")) as StoreResult.Ok).value
            app.createTestStep(case.id, plainStep())
            val second = (app.createTestSuite("Second") as StoreResult.Ok).value
            app.moveTestSuite(second.id, 0)

            assertEquals(listOf(second.id, suite.id), app.testLibrary.suites.map { it.id })
            assertEquals(1, app.testLibrary.suite(suite.id)!!.cases.single().steps.size)
            assertTrue(File(dir, "testing/suites/${suite.id}.json").isFile)
            assertTrue(File(dir, "testing/library.json").isFile)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aNewAppStateReloadsTheLibraryFromTheSameDirectory() {
        val dir = createTempDirectory("appstate-testing-reload").toFile()
        val first = appState(dir)
        try {
            first.createTestSuite("Persisted")
        } finally {
            first.close()
        }
        val second = appState(dir)
        try {
            assertEquals(listOf("Persisted"), second.testLibrary.suites.map { it.name })
        } finally {
            second.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun theEditionServiceSuppliesTheLimitsAndCanBeSwitchedForDev() {
        val dir = createTempDirectory("appstate-testing-edition").toFile()
        val app = appState(dir)
        try {
            app.createTestSuite("One")
            assertIs<StoreResult.Ok<*>>(app.createTestSuite("Two"))

            assertTrue(app.editionService.setForDev(Edition.FREE))

            assertIs<StoreResult.LimitReached>(app.createTestSuite("Three"))
            assertEquals(2, app.testLibrary.suites.size)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aBareLibraryStartsEmptyAndWritesNothing() {
        val dir = createTempDirectory("appstate-testing-empty").toFile()
        val app = appState(dir)
        try {
            assertTrue(app.testLibrary.suites.isEmpty())
            assertFalse(File(dir, "testing").exists())
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }
}
