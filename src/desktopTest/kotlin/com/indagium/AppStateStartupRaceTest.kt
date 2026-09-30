package com.indagium

import com.indagium.source.SOURCE_INDEX_VERSION
import com.indagium.source.SourceIndex
import com.indagium.source.SourceIndexStore
import com.indagium.ui.AppState
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

// Regression guard for an AppState construction-order race. The constructor used to launch
// loadPersistedSourceIndex on ioScope from an init block declared thousands of lines before
// fields that coroutine reads (changedFileCountRefreshInFlight). With a persisted source index on
// disk, the coroutine could run before those fields were initialized and die with an NPE, which
// CI reported as UncaughtExceptionsBeforeTest in whichever runTest-based UI test ran next.
// Dispatchers.Unconfined runs each launched coroutine inline at its launch site, so any startup
// coroutine that reads a not-yet-initialized field fails here every time, not just on a slow runner.
class AppStateStartupRaceTest {
    @Test
    fun startupCoroutinesOnlyRunOnceTheWholeAppStateIsConstructed() {
        val dir = createTempDirectory("appstate-startup-race").toFile()
        val indexFile = File(dir, "source-index")
        SourceIndexStore.save(
            SourceIndex(version = SOURCE_INDEX_VERSION, roots = emptyList(), sites = emptyList(), fileMeta = emptyMap(), builtAt = 0L),
            indexFile,
        )
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, failure -> failures += failure }
        try {
            val app = AppState(
                autosaveFile = File(dir, "state.cache"),
                autoExportNotes = false,
                sourceIndexFile = indexFile,
                ioDispatcher = Dispatchers.Unconfined,
            )
            try {
                // The persisted index really was published by the startup coroutine.
                assertEquals(SOURCE_INDEX_VERSION, app.sourceIndex?.version)
            } finally {
                app.close()
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            dir.deleteRecursively()
        }
        assertEquals(emptyList(), failures.map { "${it::class.simpleName}: ${it.message}" })
    }
}
