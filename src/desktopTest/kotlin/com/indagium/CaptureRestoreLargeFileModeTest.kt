package com.indagium

import com.indagium.ui.AppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val LOAD_TIMEOUT_MS = 10_000L
private const val POLL_MS = 20L
private const val TINY_THRESHOLD_BYTES = 10L

class CaptureRestoreLargeFileModeTest {
    /**
     * The checksum-verified fallback restore parses the extracted log and then deletes it; the size
     * used for large-file mode must be read before that delete (a deleted file reports length 0).
     */
    @Test
    fun verifiedLogFallbackKeepsLargeFileModeForALogAboveTheThreshold() = runBlocking {
        val root = createTempDirectory("capture-restore-large-mode").toFile()
        try {
            val archive = CaptureArchiveFixtures.exportWithVideo(root)
            val cache = File(root, "app-cache")
            val autosave = File(root, "autosave")
            val app = AppState(autosaveFile = autosave, autoExportNotes = false, archiveCacheDir = cache)
            val id = app.openCaptureFile(archive)
            awaitLoaded(app, id)
            app.autosaveNow()
            app.close()

            // A tampered video makes CaptureArchiveReader.open fail while the log still verifies, which
            // is exactly the "descriptor no longer opens" situation the fallback exists for.
            val tampered = File(root, "tampered.zip")
            CaptureArchiveFixtures.rewriteZip(archive, tampered) { name, bytes ->
                if (name.endsWith(".mp4") || name.startsWith("video")) bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() } else bytes
            }
            tampered.copyTo(archive, overwrite = true)
            cache.deleteRecursively()

            val restored = AppState(
                autosaveFile = autosave,
                restoreOnCreate = true,
                autoExportNotes = false,
                archiveCacheDir = cache,
                restoredArchiveLargeFileModeBytes = TINY_THRESHOLD_BYTES,
            )
            try {
                restored.startPendingRestoredTabLoads()
                awaitLoaded(restored, id)
                val tab = assertNotNull(restored.tab(id))
                assertTrue(tab.logData.isNotEmpty(), "the verified log was restored through the fallback")
                assertTrue(tab.largeFileMode, "log bytes above the threshold must still flag large-file mode")
            } finally {
                restored.close()
            }
        } finally {
            root.deleteRecursively()
        }
        Unit
    }

    private suspend fun awaitLoaded(app: AppState, id: String) {
        withTimeout(LOAD_TIMEOUT_MS) { while (app.isLoadInFlight(id) || app.tab(id) == null) delay(POLL_MS) }
        assertNotNull(app.tab(id), app.openError?.message)
    }
}
