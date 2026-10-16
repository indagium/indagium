package com.indagium

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.indagium.capture.CaptureArchiveExporter
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureRange
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureStatus
import com.indagium.capture.CaptureDevice
import com.indagium.ui.AppState
import com.indagium.ui.FileView
import com.indagium.ui.captureExportStatusFor
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalTestApi::class)
class CaptureSnapshotExportLifecycleTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun importedArchiveUsesExportMenuRangeEditorWithoutCaptureStrip() = runBlocking {
        val root = createTempDirectory("imported-range-menu").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (state, tabId) = openImportedSource(root) {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS))
        }
        try {
            composeRule.setContent {
                state.tab(tabId)?.let { FileView(state, it) }
            }
            composeRule.onAllNodesWithText("Save snapshot ▾").assertCountEquals(0)
            composeRule.onAllNodesWithText("Stopped ·", substring = true).assertCountEquals(0)

            composeRule.onNodeWithText("Export ▾").performClick()
            composeRule.onNodeWithText("Export capture range…").assertIsDisplayed().performClick()
            composeRule.onNodeWithText("Export capture range").assertIsDisplayed()
            composeRule.waitUntil(10_000) { state.captureExportPreview != null }
            composeRule.onNodeWithText("Save ZIP").performClick()
            assertTrue(entered.await(15, TimeUnit.SECONDS), "range export did not enter the blocking exporter seam")

            // Click the real editor close control while the AppState export job is in flight.
            // This exercises the same popup dismissal path as Escape/outside click/trigger toggle.
            composeRule.onNodeWithText("×").performClick()
            composeRule.onAllNodesWithText("Export capture range").assertCountEquals(0)
            assertTrue(state.captureExportBusy)
            assertFalse(state.captureExportCancelled)
            assertEquals(state.captureExportSessionId, state.captureExportOwner)
            composeRule.onNodeWithText("Exporting capture…").assertIsDisplayed()

            release.countDown()
            withTimeout(10_000) { while (state.captureExportBusy) delay(10) }
            assertNotNull(state.captureExportResult)
            assertNull(state.captureExportError)
        } finally {
            release.countDown()
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun dismissingTheRangeEditorKeepsExportAndSourceRoutingUntilCompletion() = runBlocking {
        val root = createTempDirectory("capture-export-dismiss").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (state, tabId) = openImportedSource(root) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        try {
            val source = assertNotNull(state.captureSnapshotSessionFor(tabId))
            state.exportCaptureSnapshot(tabId, requestFor(root, source), openAfterSuccess = false)
            assertTrue(entered.await(5, TimeUnit.SECONDS), "export did not reach the blocking exporter seam")

            // The editor's close path invalidates only preview work. clearCaptureExportStatus is
            // also safe to race here: it must preserve the active job and its source identity.
            state.invalidateCaptureSnapshotPreview(tabId)
            state.clearCaptureExportStatus()
            assertTrue(state.captureExportBusy)
            assertEquals(source.id, state.captureExportSessionId)
            assertEquals(source.id, state.captureExportOwner)

            release.countDown()
            awaitExport(state)
            assertNotNull(state.captureExportResult)
            assertNull(state.captureExportError)
            assertFalse(state.captureExportCancelled)
        } finally {
            release.countDown()
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun saveAndOpenIntentSurvivesEditorDismissalAndOpensTheCompletedArchive() = runBlocking {
        val root = createTempDirectory("capture-export-open-after-dismiss").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (state, tabId) = openImportedSource(root) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        try {
            val source = assertNotNull(state.captureSnapshotSessionFor(tabId))
            state.exportCaptureSnapshot(tabId, requestFor(root, source), openAfterSuccess = true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            state.invalidateCaptureSnapshotPreview(tabId)
            state.clearCaptureExportStatus()
            assertTrue(state.captureExportBusy)
            assertEquals(source.id, state.captureExportSessionId)
            release.countDown()

            awaitExport(state)
            val output = assertNotNull(state.captureExportResult).file
            withTimeout(10_000) {
                while (state.tabs.none { it.captureArchivePath == output.absolutePath }) delay(20)
            }
            assertNull(state.captureExportError)
            assertFalse(state.captureExportCancelled)
        } finally {
            release.countDown()
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun explicitCancellationStaysCancelledAndDoesNotAdvanceImportedCheckpoints() = runBlocking {
        val root = createTempDirectory("capture-export-cancel").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (state, tabId) = openImportedSource(root) {
            entered.countDown()
            try {
                release.await()
            } catch (_: InterruptedException) {
                // Model an encoder that wraps interruption in its own IOException; the user
                // explicitly cancelled, so this must still be presented as cancellation.
                throw IOException("Video export interrupted")
            }
        }
        try {
            val source = assertNotNull(state.captureSnapshotSessionFor(tabId))
            val logCheckpoint = source.effectiveSnapshotCheckpointMs
            val videoCheckpoint = source.effectiveVideoCheckpointMs
            state.exportCaptureSnapshot(tabId, requestFor(root, source))
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            state.cancelCaptureSnapshot()
            withTimeout(5_000) { while (state.captureExportBusy) delay(10) }

            assertTrue(state.captureExportCancelled)
            assertNull(state.captureExportError)
            assertNull(state.captureExportResult)
            val status = captureExportStatusFor(
                sessionId = source.id,
                owner = state.captureExportOwner,
                busy = state.captureExportBusy,
                result = state.captureExportResult,
                error = state.captureExportError,
                cancelled = state.captureExportCancelled,
            )
            assertTrue(status.cancelled)
            val after = assertNotNull(state.captureSnapshotSessionFor(tabId))
            assertEquals(logCheckpoint, after.effectiveSnapshotCheckpointMs)
            assertEquals(videoCheckpoint, after.effectiveVideoCheckpointMs)
        } finally {
            release.countDown()
            state.close()
            root.deleteRecursively()
        }
    }

    private suspend fun openImportedSource(root: File, beforeExport: () -> Unit): Pair<AppState, String> {
        val sourceSession = CaptureSession(
            id = "snapshot-lifecycle",
            directory = File(root, "session"),
            device = CaptureDevice("emulator-5554", "device"),
            settings = CaptureSettings(recordVideo = false, freeSpaceReserveBytes = 0),
            startedEpochMs = 1_700_000_000_000L,
            elapsedMs = 3_000L,
            status = CaptureStatus.STOPPED,
        )
        sourceSession.logFile.parentFile.mkdirs()
        sourceSession.indexFile.parentFile.mkdirs()
        val lines = listOf(
            "09-19 12:00:01.000  100  101 I Tag: row one\n",
            "09-19 12:00:02.000  100  101 I Tag: row two\n",
        )
        var offset = 0L
        sourceSession.logFile.outputStream().use { log ->
            sourceSession.indexFile.bufferedWriter().use { index ->
                lines.forEachIndexed { rowIndex, line ->
                    val bytes = line.toByteArray()
                    log.write(bytes)
                    index.appendLine(
                        "{\"byteOffset\":$offset,\"byteLength\":${bytes.size}," +
                            "\"elapsedMs\":${(rowIndex + 1) * 1_000L},\"rowOrdinal\":${rowIndex + 1}}",
                    )
                    offset += bytes.size
                }
            }
        }
        val sourceArchive = CaptureArchiveExporter().export(
            sourceSession,
            CaptureExportRequest(
                destination = File(root, "source.zip"),
                range = CaptureRange.ALL,
                includeVideo = false,
                cutoffElapsedMs = sourceSession.elapsedMs,
            ),
        ).file
        val state = AppState(
            autosaveFile = File(root, "autosave"),
            notesDir = File(root, "notes"),
            archiveCacheDir = File(root, "cache"),
            customCommandsDir = File(root, "commands"),
            controlTokenFile = File(root, "control-token"),
            sourceIndexFile = File(root, "source-index"),
            testingDir = File(root, "testing"),
            autoExportNotes = false,
            platformDefaultSaveRootDir = File(root, "save-root"),
            beforeCaptureSnapshotExport = beforeExport,
        )
        val tabId = state.openCaptureFile(sourceArchive)
        withTimeout(10_000) {
            while (state.isLoadInFlight(tabId) || state.captureSnapshotSessionFor(tabId) == null) delay(20)
        }
        return state to tabId
    }

    private fun requestFor(root: File, session: CaptureSession) = CaptureExportRequest(
        destination = File(root, "range-${System.nanoTime()}.zip"),
        range = CaptureRange.ALL,
        includeVideo = false,
        cutoffElapsedMs = session.elapsedMs,
    )

    private suspend fun awaitExport(state: AppState) {
        withTimeout(10_000) { while (state.captureExportBusy) delay(10) }
    }
}
