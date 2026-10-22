package com.indagium

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.indagium.capture.CaptureArchiveExporter
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureExportResult
import com.indagium.capture.CaptureExtractKind
import com.indagium.capture.CaptureRange
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureStatus
import com.indagium.capture.RecorderSnapshot
import com.indagium.capture.RecorderState
import com.indagium.ui.AppState
import com.indagium.ui.CaptureExportNotice
import com.indagium.ui.CaptureExtractConflict
import com.indagium.ui.CaptureSnapshotPopover
import com.indagium.ui.FileView
import com.indagium.ui.captureExtractConflict
import com.indagium.ui.captureExtractConflictMessage
import com.indagium.ui.captureExtractDefaultName
import com.indagium.ui.captureExtractEnabled
import com.indagium.ui.captureExtractShowsAv1Note
import com.indagium.ui.captureExtractSuffix
import com.indagium.ui.mkTab
import org.junit.Rule
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

private const val UI_TIMEOUT_MS = 15_000L

/** The "Extract from this archive" group of the snapshot popover: when it exists, what it disables, what it skips. */
@OptIn(ExperimentalTestApi::class)
@Suppress("MagicNumber")
class CaptureExtractPopoverTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theExtractGroupShowsOnlyForATabOpenedFromAnArchive() {
        val root = createTempDirectory("extract-popover-visible").toFile()
        val (state, tabId) = openArchive(root)
        try {
            composeRule.setContent { state.tab(tabId)?.let { FileView(state, it) } }
            openPopover()
            composeRule.onNodeWithText("Extract from this archive").assertIsDisplayed()
            composeRule.onNodeWithText("All files · folder").assertIsDisplayed()
            composeRule.onNodeWithText("Log only · .log (plain text)").assertIsDisplayed()
        } finally {
            state.close()
            root.deleteRecursively()
        }

        // A tab that is not an opened archive (here: a plain tab with a retained-style session) offers no extraction.
        val plainRoot = createTempDirectory("extract-popover-plain").toFile()
        val plain = AppState(autosaveFile = File(plainRoot, "autosave"), autoExportNotes = false, archiveCacheDir = File(plainRoot, "cache"))
        try {
            val session = CaptureSession(
                id = "local-session",
                directory = File(plainRoot, "session"),
                device = CaptureDevice("emulator-5554", "device"),
                settings = CaptureSettings(recordVideo = false),
                startedEpochMs = 1_700_000_000_000L,
                elapsedMs = 3_000L,
                status = CaptureStatus.STOPPED,
            )
            val tab = mkTab("plain-tab", "plain", emptyList())
            composeRule.setContent {
                CaptureSnapshotPopover(
                    state = plain,
                    tab = tab,
                    snapshot = RecorderSnapshot(state = RecorderState.STOPPED, session = session),
                    triggerBounds = null,
                    onDismiss = {},
                    onReturnFocus = {},
                )
            }
            composeRule.onNodeWithText("Save ZIP").assertIsDisplayed()
            composeRule.onAllNodesWithText("Extract from this archive").assertCountEquals(0)
        } finally {
            plain.close()
            plainRoot.deleteRecursively()
        }
    }

    @Test
    fun extractModeDisablesTheRangeHidesIncludeVideoRenamesTheFieldAndRunsNoPreview() {
        val root = createTempDirectory("extract-popover-mode").toFile()
        val (state, tabId) = openArchive(root)
        try {
            val stem = captureExtractDefaultName(assertNotNull(state.captureExtractSourceFor(tabId)).archive)
            composeRule.setContent { state.tab(tabId)?.let { FileView(state, it) } }
            openPopover()
            composeRule.waitUntil(UI_TIMEOUT_MS) { state.captureExportPreview != null }
            composeRule.onNodeWithText("Include video").assertIsDisplayed()
            composeRule.onNodeWithText("Archive name").assertIsDisplayed()

            composeRule.onNodeWithText("Log only · .log (plain text)").performClick()

            composeRule.onNodeWithText("Extract always copies the whole archive.").assertIsDisplayed()
            composeRule.onAllNodesWithText("Include video").assertCountEquals(0)
            composeRule.onAllNodesWithText("Archive name").assertCountEquals(0)
            composeRule.onNodeWithText("File name").assertIsDisplayed()
            composeRule.onNodeWithText(".log").assertIsDisplayed()
            composeRule.onNodeWithText(stem).assertIsDisplayed()
            composeRule.onNodeWithText("Extract").assertIsDisplayed()
            composeRule.onAllNodesWithText("Save ZIP").assertCountEquals(0)
            composeRule.onAllNodesWithText("Save + open").assertCountEquals(0)
            // The range controls are disabled: choosing "Last N minutes" must not reveal its minutes field.
            composeRule.onNodeWithText("Last N minutes").performClick()
            composeRule.onAllNodesWithText("Minutes").assertCountEquals(0)

            // The sparse preview scan neither runs nor leaves a result behind while Extract is selected.
            composeRule.mainClock.advanceTimeBy(3_000)
            composeRule.waitForIdle()
            assertNull(state.captureExportPreview)
            assertNull(state.captureExportPreviewKey)

            composeRule.onNodeWithText("All files · folder").performClick()
            composeRule.onNodeWithText("Folder name").assertIsDisplayed()
            composeRule.onAllNodesWithText(".log").assertCountEquals(0)
            composeRule.onNodeWithText("Notes are copied as shipped", substring = true).assertIsDisplayed()

            // Back to an archive format: the preview and the usual controls return.
            composeRule.onNodeWithText("ZIP · original video").performClick()
            composeRule.onNodeWithText("Save ZIP").assertIsDisplayed()
            composeRule.onNodeWithText("Include video").assertIsDisplayed()
            composeRule.waitUntil(UI_TIMEOUT_MS) { state.captureExportPreview != null }
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun videoOnlyIsDisabledWithAHintWhenTheArchiveHasNoVideo() {
        val root = createTempDirectory("extract-popover-novideo").toFile()
        val (state, tabId) = openArchive(root, withVideo = false)
        try {
            composeRule.setContent { state.tab(tabId)?.let { FileView(state, it) } }
            openPopover()
            composeRule.onNodeWithText("This archive has no video").assertIsDisplayed()

            composeRule.onNodeWithText("Video only · .mkv").performClick()

            composeRule.onAllNodesWithText("Extract").assertCountEquals(0)
            composeRule.onNodeWithText("Save ZIP").assertIsDisplayed()
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun extractWritesTheLogAndAskedForOverwriteConfirmationOnlyForAnExistingFile() {
        val root = createTempDirectory("extract-popover-run").toFile()
        val (state, tabId) = openArchive(root)
        try {
            val stem = captureExtractDefaultName(assertNotNull(state.captureExtractSourceFor(tabId)).archive)
            val target = File(state.effectiveCaptureSnapshotsDir(), "$stem.log").apply {
                parentFile.mkdirs()
                writeText("old")
            }
            composeRule.setContent { state.tab(tabId)?.let { FileView(state, it) } }
            openPopover()
            composeRule.onNodeWithText("Log only · .log (plain text)").performClick()

            composeRule.onNodeWithText("A file with this name already exists. Confirm overwrite to continue.").assertIsDisplayed()
            composeRule.onNodeWithText("Extract").assertIsNotEnabled()
            composeRule.onNodeWithText("Confirm overwrite").performScrollTo().performClick()
            composeRule.onNodeWithText("Extract").assertIsEnabled().performClick()

            composeRule.waitUntil(UI_TIMEOUT_MS) { state.captureExportResult != null || state.captureExportError != null }
            assertNull(state.captureExportError)
            assertTrue(assertNotNull(state.captureExportResult).extracted)
            assertTrue(target.readText().contains("row 1"), "the old file was replaced by the archive's log")

            // A folder with the chosen name is never overwritten: no confirm, Extract stays disabled.
            File(state.effectiveCaptureSnapshotsDir(), stem).mkdirs()
            composeRule.onNodeWithText("All files · folder").performClick()
            composeRule.onNodeWithText("A folder with this name already exists. Choose another name.").assertIsDisplayed()
            composeRule.onAllNodesWithText("Confirm overwrite").assertCountEquals(0)
            composeRule.onNodeWithText("Extract").assertIsNotEnabled()
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun theConflictLineNamesWhatIsInTheWayAndOnlyOffersOverwriteForAFile() {
        val root = createTempDirectory("extract-popover-conflict").toFile()
        val (state, tabId) = openArchive(root)
        try {
            val stem = captureExtractDefaultName(assertNotNull(state.captureExtractSourceFor(tabId)).archive)
            val snapshots = state.effectiveCaptureSnapshotsDir().apply { mkdirs() }
            File(snapshots, stem).writeText("a file, not a folder")
            File(snapshots, "$stem.log").mkdirs()
            composeRule.setContent { state.tab(tabId)?.let { FileView(state, it) } }
            openPopover()

            // All files onto an existing FILE: the line says what is there, and a folder never replaces it.
            composeRule.onNodeWithText("All files · folder").performClick()
            composeRule.onNodeWithText("A file with this name already exists. Choose another name.").assertIsDisplayed()
            composeRule.onAllNodesWithText("Confirm overwrite").assertCountEquals(0)
            composeRule.onNodeWithText("Extract").assertIsNotEnabled()

            // Log only onto an existing FOLDER: overwriting cannot help, so no confirmation is offered.
            composeRule.onNodeWithText("Log only · .log (plain text)").performClick()
            composeRule.onNodeWithText("A folder with this name already exists. Choose another name.").assertIsDisplayed()
            composeRule.onAllNodesWithText("Confirm overwrite").assertCountEquals(0)
            composeRule.onNodeWithText("Extract").assertIsNotEnabled()
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun theExtractGroupAppearsOnceARestoredArchiveTabLinksItsSnapshot() {
        val root = createTempDirectory("extract-popover-restore").toFile()
        val autosave = File(root, "autosave")
        val archive = exportWithoutVideo(root)
        val linking = CountDownLatch(1)
        val release = CountDownLatch(1)
        var restored: AppState? = null
        try {
            val (first, tabId) = openArchive(root, archive)
            first.autosaveNow()
            first.close()

            // After a restart the rows come back first; the imported snapshot is linked a moment later.
            val app = AppState(
                autosaveFile = autosave,
                restoreOnCreate = true,
                notesDir = File(root, "notes"),
                archiveCacheDir = File(root, "cache-after-restart"),
                autoExportNotes = false,
                platformDefaultSaveRootDir = File(root, "save-root"),
                beforeImportedSnapshotStored = {
                    linking.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                },
            ).also { restored = it }
            app.startPendingRestoredTabLoads()
            assertTrue(linking.await(15, TimeUnit.SECONDS), "the restore never reached the snapshot link")
            assertNotNull(app.tab(tabId))
            assertNull(app.captureExtractSourceFor(tabId))

            // A popover opened inside that window: nothing to extract from yet.
            val linkingSession = CaptureSession(
                id = "linking",
                directory = File(root, "linking"),
                device = CaptureDevice("emulator-5554", "device"),
                settings = CaptureSettings(recordVideo = false),
                startedEpochMs = 1_700_000_000_000L,
                elapsedMs = 3_000L,
                status = CaptureStatus.STOPPED,
            )
            composeRule.setContent {
                app.tab(tabId)?.let { tab ->
                    CaptureSnapshotPopover(
                        state = app,
                        tab = tab,
                        snapshot = RecorderSnapshot(
                            state = RecorderState.STOPPED,
                            session = app.captureSnapshotSessionFor(tab.id) ?: linkingSession,
                        ),
                        triggerBounds = null,
                        onDismiss = {},
                        onReturnFocus = {},
                    )
                }
            }
            composeRule.onNodeWithText("Save ZIP").assertIsDisplayed()
            composeRule.onAllNodesWithText("Extract from this archive").assertCountEquals(0)

            // Once linked, the open popover offers extraction without being reopened, with its default name filled in.
            release.countDown()
            composeRule.waitUntil(UI_TIMEOUT_MS) {
                composeRule.onAllNodesWithText("Extract from this archive").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("All files · folder").performClick()
            composeRule.onNodeWithText("Extract").assertIsEnabled()
        } finally {
            release.countDown()
            restored?.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun theStripNoticeNamesAnExtractionAsExtractedNotSaved() {
        val result = CaptureExportResult(
            file = File("/tmp/opened-archive"),
            logCoveredEndMs = 0L,
            videoCoveredEndMs = null,
            videoActualStartMs = null,
            message = "14 files",
            extracted = true,
        )
        composeRule.setContent { CaptureExportNotice(result = result, error = null, onClose = {}, onReclaimFocus = {}) }
        composeRule.onNodeWithText("Extracted to opened-archive").assertIsDisplayed()
        composeRule.onNodeWithText("Open folder").assertIsDisplayed()
        composeRule.onAllNodesWithText("Saved opened-archive").assertCountEquals(0)
    }

    @Test
    fun theDecisionHelpersFollowThePlan() {
        assertEquals("", captureExtractSuffix(CaptureExtractKind.ALL_FILES, "mkv"))
        assertEquals(".log", captureExtractSuffix(CaptureExtractKind.LOG_ONLY, "mkv"))
        assertEquals(".mp4", captureExtractSuffix(CaptureExtractKind.VIDEO_ONLY, "mp4"))
        assertEquals(".mkv", captureExtractSuffix(CaptureExtractKind.VIDEO_ONLY, null))

        assertEquals("trace", captureExtractDefaultName(File("/x/trace.zip")))
        assertEquals("trace", captureExtractDefaultName(File("/x/trace.ind")))
        assertEquals("trace", captureExtractDefaultName(File("/x/trace.ind.zip")))

        // AV1 note: a .ind assumes AV1 until the probe says otherwise; any archive shows it when the probe finds AV1.
        assertTrue(captureExtractShowsAv1Note(CaptureExtractKind.ALL_FILES, hasVideo = true, compactSource = true, probedAv1 = null))
        assertFalse(captureExtractShowsAv1Note(CaptureExtractKind.ALL_FILES, hasVideo = true, compactSource = true, probedAv1 = false))
        assertTrue(captureExtractShowsAv1Note(CaptureExtractKind.VIDEO_ONLY, hasVideo = true, compactSource = false, probedAv1 = true))
        assertFalse(captureExtractShowsAv1Note(CaptureExtractKind.LOG_ONLY, hasVideo = true, compactSource = true, probedAv1 = true))
        assertFalse(captureExtractShowsAv1Note(CaptureExtractKind.ALL_FILES, hasVideo = false, compactSource = true, probedAv1 = true))

        fun enabled(kind: CaptureExtractKind, conflict: CaptureExtractConflict, confirmed: Boolean = false, video: Boolean = true) =
            captureExtractEnabled(
                kind, targetNameValid = true, videoAvailable = video, conflict = conflict,
                overwriteConfirmed = confirmed, busy = false,
            )
        assertTrue(enabled(CaptureExtractKind.ALL_FILES, CaptureExtractConflict.NONE))
        assertFalse(enabled(CaptureExtractKind.ALL_FILES, CaptureExtractConflict.BLOCKED_BY_FOLDER, confirmed = true), "a folder is never replaced")
        assertFalse(enabled(CaptureExtractKind.ALL_FILES, CaptureExtractConflict.BLOCKED_BY_FILE, confirmed = true), "nor is a file, by a folder")
        assertFalse(enabled(CaptureExtractKind.LOG_ONLY, CaptureExtractConflict.NEEDS_OVERWRITE))
        assertTrue(enabled(CaptureExtractKind.LOG_ONLY, CaptureExtractConflict.NEEDS_OVERWRITE, confirmed = true))
        assertFalse(enabled(CaptureExtractKind.LOG_ONLY, CaptureExtractConflict.BLOCKED_BY_FOLDER, confirmed = true), "no file replaces a folder")
        assertFalse(enabled(CaptureExtractKind.VIDEO_ONLY, CaptureExtractConflict.NONE, video = false))
        assertFalse(captureExtractEnabled(CaptureExtractKind.LOG_ONLY, false, true, CaptureExtractConflict.NONE, false, false))
        assertFalse(captureExtractEnabled(CaptureExtractKind.LOG_ONLY, true, true, CaptureExtractConflict.NONE, false, busy = true))

        assertEquals(CaptureExtractConflict.NONE, captureExtractConflict(CaptureExtractKind.LOG_ONLY, exists = false, isDirectory = false))
        assertEquals(CaptureExtractConflict.BLOCKED_BY_FOLDER, captureExtractConflict(CaptureExtractKind.LOG_ONLY, exists = true, isDirectory = true))
        assertEquals(CaptureExtractConflict.BLOCKED_BY_FOLDER, captureExtractConflict(CaptureExtractKind.ALL_FILES, exists = true, isDirectory = true))
        assertEquals(CaptureExtractConflict.BLOCKED_BY_FILE, captureExtractConflict(CaptureExtractKind.ALL_FILES, exists = true, isDirectory = false))
        assertEquals(CaptureExtractConflict.NEEDS_OVERWRITE, captureExtractConflict(CaptureExtractKind.VIDEO_ONLY, exists = true, isDirectory = false))
        assertNull(captureExtractConflictMessage(CaptureExtractConflict.NONE))
        assertEquals(
            "A file with this name already exists. Choose another name.",
            captureExtractConflictMessage(CaptureExtractConflict.BLOCKED_BY_FILE),
        )
    }

    private fun openPopover() {
        composeRule.onNodeWithText("Export ▾").performClick()
        composeRule.onNodeWithText("Export capture range…").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Export capture range").assertIsDisplayed()
    }

    private fun openArchive(root: File, withVideo: Boolean = true): Pair<AppState, String> =
        openArchive(root, if (withVideo) CaptureArchiveFixtures.exportWithVideo(root) else exportWithoutVideo(root))

    private fun openArchive(root: File, archive: File): Pair<AppState, String> {
        val state = AppState(
            autosaveFile = File(root, "autosave"),
            notesDir = File(root, "notes"),
            archiveCacheDir = File(root, "cache"),
            autoExportNotes = false,
            platformDefaultSaveRootDir = File(root, "save-root"),
        )
        val tabId = state.openCaptureFile(archive)
        val deadline = System.nanoTime() + UI_TIMEOUT_MS * 1_000_000
        while ((state.isLoadInFlight(tabId) || state.captureSnapshotSessionFor(tabId) == null) && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
        assertNotNull(state.tab(tabId), state.openError?.message)
        return state to tabId
    }

    private fun exportWithoutVideo(root: File): File {
        val session = CaptureSession(
            id = "no-video",
            directory = File(root, "session"),
            device = CaptureDevice("emulator-5554", "device"),
            settings = CaptureSettings(recordVideo = false, freeSpaceReserveBytes = 0L),
            startedEpochMs = 1_700_000_000_000L,
            elapsedMs = 3_000L,
            status = CaptureStatus.STOPPED,
        )
        session.logFile.parentFile.mkdirs()
        session.indexFile.parentFile.mkdirs()
        var offset = 0L
        session.logFile.outputStream().use { log ->
            session.indexFile.bufferedWriter().use { index ->
                for (i in 1..3) {
                    val bytes = "09-19 12:00:0${i - 1}.000  100  101 I Tag: row $i\n".toByteArray()
                    log.write(bytes)
                    index.appendLine("{\"byteOffset\":$offset,\"byteLength\":${bytes.size},\"elapsedMs\":${i * 1_000L},\"rowOrdinal\":$i}")
                    offset += bytes.size
                }
            }
        }
        return CaptureArchiveExporter().export(
            session,
            CaptureExportRequest(File(root, "no-video-capture.zip"), CaptureRange.ALL, includeVideo = false, cutoffElapsedMs = 3_000L),
        ).file
    }
}
