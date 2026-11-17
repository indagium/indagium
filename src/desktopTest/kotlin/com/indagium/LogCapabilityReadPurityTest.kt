package com.indagium

import androidx.compose.runtime.snapshots.Snapshot
import com.indagium.model.CapabilityAvailability
import com.indagium.model.LogCapability
import com.indagium.model.LogEntry
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogLevel
import com.indagium.model.deriveLogCapabilities
import com.indagium.ui.AppState
import com.indagium.ui.mkTab
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LogCapabilityReadPurityTest {
    @Test
    fun fallbackCapabilityReadInMutableSnapshotDoesNotConflictWithBackgroundLoad() {
        val directory = createTempDirectory("capability-read-snapshot").toFile()
        val state = newState(directory)
        try {
            val initialTab = mkTab("capabilities", "pending.log", emptyList()).copy(logCapabilities = emptyMap())
            state.tabs = listOf(initialTab)
            val snapshot = Snapshot.takeMutableSnapshot()
            try {
                val capabilities = snapshot.enter {
                    val tabsBeforeRead = state.tabs
                    val resolved = state.logCapabilitiesForTab(initialTab.id)
                    assertSame(tabsBeforeRead, state.tabs, "capability lookup must not publish its fallback summary")
                    resolved
                }
                assertEquals(CapabilityAvailability.AVAILABLE, capabilities.getValue(LogCapability.TEXT_SEARCH).availability)

                val loadedRows = listOf(LogEntry(2, "10:00:00", LogLevel.I, "Loaded", "published row"))
                val backgroundUpdate = Thread {
                    state.upTab(initialTab.id) { current ->
                        current.copy(logData = loadedRows, rmap = mapOf(2 to loadedRows.single()))
                    }
                }.apply { start() }
                backgroundUpdate.join(TimeUnit.SECONDS.toMillis(5))
                assertTrue(!backgroundUpdate.isAlive, "the background tab update must finish")

                assertTrue(snapshot.apply().succeeded, "a read-only composition snapshot should apply after the tab load")
                assertEquals(loadedRows, assertNotNull(state.tab(initialTab.id)).logData)
            } finally {
                snapshot.dispose()
            }
        } finally {
            state.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun configuredSourceLookupOverlayDoesNotWriteBackToTheTab() {
        val directory = createTempDirectory("capability-source-overlay").toFile()
        val state = newState(directory)
        val sourceRoot = File(directory, "source").apply { mkdirs() }
        File(sourceRoot, "Feature.kt").writeText("class Feature { fun ready() { Log.i(\"Api\", \"ready\") } }\n")
        try {
            state.updateSettings { it.copy(sourceFolders = listOf(sourceRoot.canonicalPath)) }
            state.reindexSources(sourceRoot.canonicalPath)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)

            fun sourceIndexIsReady() = synchronized(state.stateLock) { state.sourceIndex != null }
            while (!sourceIndexIsReady() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(sourceIndexIsReady(), "the source index and resolver should publish before checking the overlay")

            val profile = LogFormatProfile(
                id = "source-overlay-profile",
                name = "Source overlay profile",
                engine = LogFormatEngine.JSON_LINES,
                fieldMappings = mapOf(
                    LogFieldRole.COMPONENT to "logger",
                    LogFieldRole.MESSAGE to "message",
                ),
            )
            val entries = listOf(LogEntry(1, "", LogLevel.I, "Api", "ready"))
            val cachedCapabilities = deriveLogCapabilities(profile, entries, sourceLookupSupported = false)
            assertEquals(
                CapabilityAvailability.UNAVAILABLE,
                cachedCapabilities.getValue(LogCapability.SOURCE_LOOKUP).availability,
            )
            val tab = mkTab("source-overlay", "api.jsonl", entries, profile = profile, profileCapabilities = cachedCapabilities)
            state.tabs = listOf(tab)
            val tabsBeforeRead = state.tabs
            val tabBeforeRead = assertNotNull(state.tab(tab.id))

            val capabilities = state.logCapabilitiesForTab(tab.id)

            assertEquals(CapabilityAvailability.AVAILABLE, capabilities.getValue(LogCapability.SOURCE_LOOKUP).availability)
            assertSame(tabsBeforeRead, state.tabs, "runtime source lookup availability is an overlay, not a tab write")
            assertSame(tabBeforeRead, state.tab(tab.id))
            assertSame(cachedCapabilities, assertNotNull(state.tab(tab.id)).logCapabilities)
        } finally {
            state.close()
            directory.deleteRecursively()
        }
    }

    private fun newState(directory: File) = AppState(
        autosaveFile = File(directory, "state.cache"),
        sourceIndexFile = File(directory, "source-index"),
        autoExportNotes = false,
    )
}
