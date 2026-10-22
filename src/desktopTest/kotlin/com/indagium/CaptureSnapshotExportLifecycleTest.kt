package com.indagium

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.indagium.capture.CaptureArchiveExporter
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureRange
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureStatus
import com.indagium.capture.CaptureVideoClip
import com.indagium.capture.CaptureVideoContainer
import com.indagium.capture.CaptureVideoExporter
import com.indagium.ui.AppState
import com.indagium.ui.FileView
import com.indagium.ui.captureExportStatusFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.zip.ZipFile
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.junit.Rule
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@Suppress("MagicNumber") // Fixture timings, counts and sizes, not tunable constants.
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
    fun snapshotFormatSelectorSwitchesZipToIndAndKeepsVideoIncluded(): Unit = runBlocking {
        val root = createTempDirectory("imported-range-menu-ind").toFile()
        val (state, tabId) = openImportedSource(root, withVideo = true)
        try {
            composeRule.setContent {
                state.tab(tabId)?.let { FileView(state, it) }
            }
            composeRule.onNodeWithText("Export ▾").performClick()
            composeRule.onNodeWithText("Export capture range…").assertIsDisplayed().performClick()
            composeRule.onNodeWithText("Save ZIP").assertIsDisplayed()
            composeRule.onNodeWithText("Include video").assertIsDisplayed()
            // Only the archive name field contains ".zip"; the Compact radio label says "(.ind)".
            composeRule.onAllNodes(hasText(".zip", substring = true)).assertCountEquals(1)

            composeRule.onNodeWithText("Indagium Compact (.ind) · zstd log + AV1").performClick()
            composeRule.onNodeWithText("Save Compact").assertIsDisplayed()
            composeRule.onNodeWithText("Include video").assertIsDisplayed()
            composeRule.onAllNodesWithText("Save ZIP").assertCountEquals(0)
            composeRule.onAllNodes(hasText(".ind", substring = true)).assertCountEquals(2)

            composeRule.onNodeWithText("Save Compact").performClick()
            composeRule.waitUntil(30_000) {
                state.captureExportResult != null || state.captureExportError != null
            }
            assertNull(state.captureExportError)
            val exported = assertNotNull(state.captureExportResult).file
            assertTrue(exported.name.endsWith(".ind"), "the selected format should reach the exporter destination")
            // Compact stores the log as ZIP method 93, which the JDK's ZipFile cannot open.
            ZipFile.builder().setFile(exported).get().use { archive ->
                assertNotNull(archive.getEntry("screen.mkv"), "Compact export should contain its staged MKV video")
            }
        } finally {
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

    private suspend fun openImportedSource(
        root: File,
        withVideo: Boolean = false,
        beforeExport: () -> Unit = {},
    ): Pair<AppState, String> {
        val sourceSession = CaptureSession(
            id = "snapshot-lifecycle",
            directory = File(root, "session"),
            device = CaptureDevice("emulator-5554", "device"),
            settings = CaptureSettings(
                recordVideo = withVideo,
                videoContainer = CaptureVideoContainer.MKV,
                freeSpaceReserveBytes = 0,
            ),
            startedEpochMs = 1_700_000_000_000L,
            elapsedMs = 3_000L,
            status = CaptureStatus.STOPPED,
            videoStartElapsedMs = if (withVideo) 0L else null,
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
        val archiveExporter = if (withVideo) {
            createVideoFixture(sourceSession.videoFile)
            CaptureArchiveExporter(
                videoExporter = CaptureVideoExporter { _, destination, start, end ->
                    destination.writeBytes(sourceSession.videoFile.readBytes())
                    CaptureVideoClip(start, end, end - start)
                },
            )
        } else {
            CaptureArchiveExporter()
        }
        val sourceArchive = archiveExporter.export(
            sourceSession,
            CaptureExportRequest(
                destination = File(root, "source.zip"),
                range = CaptureRange.ALL,
                includeVideo = withVideo,
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

    private fun createVideoFixture(file: File) {
        file.parentFile.mkdirs()
        val recorder = FFmpegFrameRecorder(file, 160, 90).apply {
            format = "matroska"
            videoCodecName = "mpeg4"
            frameRate = 10.0
            videoBitrate = 1_000_000
        }
        val frame = Frame(160, 90, Frame.DEPTH_UBYTE, 3)
        try {
            recorder.start()
            repeat(30) { index ->
                val pixels = frame.image[0] as ByteBuffer
                for (y in 0 until frame.imageHeight) {
                    for (x in 0 until frame.imageWidth) {
                        val offset = y * frame.imageStride + x * 3
                        pixels.put(offset, ((x * 7 + index * 23) and 0xff).toByte())
                        pixels.put(offset + 1, ((y * 5 + index * 17) and 0xff).toByte())
                        pixels.put(offset + 2, ((x + y + index * 31) and 0xff).toByte())
                    }
                }
                recorder.timestamp = index * 100_000L
                recorder.record(frame)
            }
            recorder.stop()
        } finally {
            runCatching { recorder.release() }
            frame.close()
        }
    }
}
