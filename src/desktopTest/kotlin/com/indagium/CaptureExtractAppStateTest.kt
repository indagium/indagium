package com.indagium

import com.indagium.capture.CaptureExtractKind
import com.indagium.capture.CaptureExtractionCache
import com.indagium.ui.AppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WAIT_MS = 10_000L
private const val POLL_MS = 20L

/** AppState's side of "Extract from this archive": status, the extraction lease, cancel, and the folder re-opening. */
@Suppress("MagicNumber")
class CaptureExtractAppStateTest {
    @Test
    fun extractingAllFilesPublishesAnExtractedResultAndTheFolderReopensInTheApp() = runBlocking {
        val root = createTempDirectory("extract-app-all").toFile()
        val (state, tabId, _) = openArchive(root)
        try {
            val session = assertNotNull(state.captureSnapshotSessionFor(tabId))
            val folder = File(root, "out/opened-archive")

            state.extractImportedCapture(tabId, CaptureExtractKind.ALL_FILES, folder)
            assertTrue(state.captureExportBusy, "busy is set before the IO job even starts")
            assertEquals(session.id, state.captureExportOwner, "the strip's status line for this tab owns the result")
            awaitIdle(state)

            assertNull(state.captureExportError)
            val result = assertNotNull(state.captureExportResult)
            assertTrue(result.extracted)
            assertEquals(folder, result.file)
            assertTrue(File(folder, "logcat.log").isFile)

            val reopenedId = state.openCaptureFile(folder)
            withTimeout(WAIT_MS) { while (state.isLoadInFlight(reopenedId)) delay(POLL_MS) }
            val original = assertNotNull(state.tab(tabId))
            val reopened = assertNotNull(state.tab(reopenedId), state.openError?.message)
            assertEquals(original.logData.map { it.msg }, reopened.logData.map { it.msg })
            assertNotNull(reopened.attachedVideo, "the extracted folder keeps its video")
            assertTrue(state.isImportedCaptureSnapshot(reopenedId))
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun theExtractionStaysLeasedForTheWholeCopyEvenIfTheTabIsClosed() = runBlocking {
        val root = createTempDirectory("extract-app-lease").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (state, tabId, _) = openArchive(root) {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS))
        }
        try {
            val directory = assertNotNull(state.captureExtractSourceFor(tabId)).root
            val tabClaims = CaptureExtractionCache.openUseCount(directory)
            val target = File(root, "out/video.${videoExtension(state, tabId)}")

            state.extractImportedCapture(tabId, CaptureExtractKind.VIDEO_ONLY, target)
            assertTrue(entered.await(15, TimeUnit.SECONDS), "the extraction did not start")
            assertEquals(tabClaims + 1, CaptureExtractionCache.openUseCount(directory), "the copy takes its own claim")

            state.closeTab(tabId)
            withTimeout(WAIT_MS) { while (CaptureExtractionCache.openUseCount(directory) > 1) delay(POLL_MS) }
            delay(200)
            assertEquals(1, CaptureExtractionCache.openUseCount(directory), "only the running copy still holds the directory")
            assertTrue(CaptureExtractionCache.holdsLease(directory))
            assertTrue(CaptureExtractionCache.isInUse(directory))

            release.countDown()
            awaitIdle(state)
            assertNull(state.captureExportError)
            assertTrue(target.isFile && target.length() > 0L)
            withTimeout(WAIT_MS) { while (CaptureExtractionCache.holdsLease(directory)) delay(POLL_MS) }
            assertEquals(0, CaptureExtractionCache.openUseCount(directory), "the claim is released when the copy ends")
        } finally {
            release.countDown()
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellingAnExtractionShowsCancelledAndLeavesNothingBehind() = runBlocking {
        val root = createTempDirectory("extract-app-cancel").toFile()
        val entered = CountDownLatch(1)
        val (state, tabId, _) = openArchive(root) {
            entered.countDown()
            // Models a copy that is interrupted: the extraction job's cancel interrupts this thread.
            Thread.sleep(15_000)
        }
        try {
            val directory = assertNotNull(state.captureExtractSourceFor(tabId)).root
            val folder = File(root, "out/cancelled")
            state.extractImportedCapture(tabId, CaptureExtractKind.ALL_FILES, folder)
            assertTrue(entered.await(15, TimeUnit.SECONDS))

            state.cancelCaptureSnapshot()
            withTimeout(WAIT_MS) { while (state.captureExportBusy) delay(POLL_MS) }

            assertTrue(state.captureExportCancelled)
            assertNull(state.captureExportError)
            assertNull(state.captureExportResult)
            assertFalse(folder.exists())
            assertTrue(File(root, "out").listFiles().orEmpty().none { it.name.contains(".extracting-") })
            assertEquals(1, CaptureExtractionCache.openUseCount(directory), "only the open tab holds the directory again")
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun anExistingFolderIsReportedAsAFailureAndLeftAlone() = runBlocking {
        val root = createTempDirectory("extract-app-existing").toFile()
        val (state, tabId, _) = openArchive(root)
        try {
            val folder = File(root, "out/taken").apply { mkdirs() }
            File(folder, "mine.txt").writeText("keep")

            state.extractImportedCapture(tabId, CaptureExtractKind.ALL_FILES, folder, overwrite = true)
            awaitIdle(state)

            assertTrue(state.captureExportError.orEmpty().contains("already exists"), state.captureExportError)
            assertNull(state.captureExportResult)
            assertEquals(listOf("mine.txt"), folder.list().orEmpty().toList())
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aTabNotOpenedFromAnArchiveHasNothingToExtract() = runBlocking {
        val root = createTempDirectory("extract-app-none").toFile()
        val state = AppState(autosaveFile = File(root, "autosave"), autoExportNotes = false, archiveCacheDir = File(root, "cache"))
        try {
            val log = File(root, "plain.log").apply { writeText("09-19 12:00:00.000  100  101 I Tag: row\n") }
            val tabId = assertNotNull(state.openFile(log))
            withTimeout(WAIT_MS) { while (state.isLoadInFlight(tabId)) delay(POLL_MS) }
            assertNull(state.captureExtractSourceFor(tabId))

            state.extractImportedCapture(tabId, CaptureExtractKind.LOG_ONLY, File(root, "x.log"))

            assertEquals("The capture source is no longer available", state.captureExportError)
            assertFalse(state.captureExportBusy)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    private fun videoExtension(state: AppState, tabId: String): String =
        assertNotNull(assertNotNull(state.captureExtractSourceFor(tabId)).descriptor.video).path.substringAfterLast('.')

    private suspend fun openArchive(root: File, beforeExtract: () -> Unit = {}): Triple<AppState, String, File> {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val state = AppState(
            autosaveFile = File(root, "autosave"),
            notesDir = File(root, "notes"),
            archiveCacheDir = File(root, "cache"),
            autoExportNotes = false,
            platformDefaultSaveRootDir = File(root, "save-root"),
            beforeCaptureSnapshotExport = beforeExtract,
        )
        val tabId = state.openCaptureFile(archive)
        withTimeout(WAIT_MS) {
            while (state.isLoadInFlight(tabId) || state.captureSnapshotSessionFor(tabId) == null) delay(POLL_MS)
        }
        return Triple(state, tabId, archive)
    }

    private suspend fun awaitIdle(state: AppState) {
        withTimeout(WAIT_MS) { while (state.captureExportBusy) delay(POLL_MS) }
    }
}
