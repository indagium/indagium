package com.indagium

import com.indagium.capture.CaptureExtractionCache
import com.indagium.ui.AppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val RELEASE_TIMEOUT_MS = 5_000L
private const val POLL_MS = 20L
private const val ARCHIVE_COUNT = 4
private const val BASE_VIDEO_BYTES = 70

/**
 * Tabs do not track which extraction directory they read, so before reference-counted uses every opened archive kept
 * its lease channel (one fd) and in-use entry until app shutdown, and a closed tab's directory stayed protected from
 * other instances' sweeps. These pin the new contract through the real tab open/close/restore flows.
 */
class CaptureExtractionReleaseOnCloseTest {
    @Test
    fun closingCaptureTabsReleasesTheirLeasesWhileAnOpenTabKeepsItsDirectoryProtected() = runBlocking {
        val root = createTempDirectory("capture-extraction-release").toFile()
        val cache = File(root, "cache")
        val state = AppState(autosaveFile = File(root, "autosave"), autoExportNotes = false, archiveCacheDir = cache)
        try {
            val archives = (1..ARCHIVE_COUNT).map { CaptureArchiveFixtures.exportWithVideo(root, BASE_VIDEO_BYTES + it) }
            val ids = archives.map { archive -> state.openCaptureFile(archive).also { waitLoaded(state, it) } }
            val directories = ids.map { assertNotNull(state.captureSnapshotSessionFor(it)).directory }
            assertEquals(ARCHIVE_COUNT, extractionDirectories(cache).size)
            assertEquals(extractionDirectories(cache), directories.sorted())
            directories.forEach {
                assertTrue(CaptureExtractionCache.holdsLease(it), "an open tab holds its extraction's lease")
                assertTrue(CaptureExtractionCache.isInUse(it))
            }

            ids.dropLast(1).forEach(state::closeTab)

            awaitReleased(directories.take(ARCHIVE_COUNT - 1))
            directories.take(ARCHIVE_COUNT - 1).forEach {
                assertFalse(CaptureExtractionCache.isInUse(it), "a closed tab no longer pins its directory")
            }
            val stillOpen = directories.last()
            assertTrue(CaptureExtractionCache.holdsLease(stillOpen), "the remaining tab still reads its directory")
            assertTrue(CaptureExtractionCache.isInUse(stillOpen))

            // A re-open of a released archive takes the lease again.
            val reopened = state.openCaptureFile(archives.first()).also { waitLoaded(state, it) }
            assertTrue(CaptureExtractionCache.holdsLease(directories.first()))
            state.closeTab(reopened)
            state.closeTab(ids.last())
            awaitReleased(directories)
            directories.forEach { assertFalse(CaptureExtractionCache.isInUse(it)) }
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aRestoredCaptureTabHoldsItsExtractionUntilItIsClosed() = runBlocking {
        val root = createTempDirectory("capture-extraction-restore-release").toFile()
        val cache = File(root, "cache")
        try {
            val archive = CaptureArchiveFixtures.exportWithVideo(root)
            val autosave = File(root, "autosave")
            val app = AppState(autosaveFile = autosave, autoExportNotes = false, archiveCacheDir = cache)
            val id = app.openCaptureFile(archive).also { waitLoaded(app, it) }
            val directory = extractionDirectories(cache).single()
            app.autosaveNow()
            app.close()
            assertFalse(CaptureExtractionCache.holdsLease(directory), "closing the app gives every lease back")

            val restored = AppState(autosaveFile = autosave, restoreOnCreate = true, autoExportNotes = false, archiveCacheDir = cache)
            try {
                restored.startPendingRestoredTabLoads()
                waitLoaded(restored, id)
                assertNotNull(restored.captureSnapshotSessionFor(id))
                assertTrue(CaptureExtractionCache.holdsLease(directory), "a restored tab re-acquires its extraction")

                restored.closeTab(id)

                awaitReleased(listOf(directory))
                assertFalse(CaptureExtractionCache.isInUse(directory))
            } finally {
                restored.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun extractionDirectories(cache: File): List<File> =
        File(cache, "captures").listFiles { f -> f.isDirectory && f.name.matches(Regex("capture-[0-9a-f]{32}")) }.orEmpty().sorted()

    private suspend fun awaitReleased(directories: List<File>) {
        withTimeout(RELEASE_TIMEOUT_MS) {
            while (directories.any { CaptureExtractionCache.holdsLease(it) }) delay(POLL_MS)
        }
    }

    private suspend fun waitLoaded(app: AppState, id: String) {
        withTimeout(RELEASE_TIMEOUT_MS * 2) { while (app.isLoadInFlight(id)) delay(POLL_MS) }
        assertNotNull(app.tab(id), app.openError?.message)
    }
}
