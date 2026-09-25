package com.indagium

import com.indagium.capture.*
import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.AppSettings
import com.indagium.ui.AppState
import com.indagium.ui.mkTab
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WAIT_TIMEOUT_MS = 10_000L
private const val POLL_INTERVAL_MS = 20L
private const val NO_FREE_SPACE_RESERVE_BYTES = 0L
private const val FIXTURE_EPOCH_MS = 1_700_000_000_000L
private const val FIXTURE_DURATION_MS = 4_000L
private const val VIDEO_START_MS = 500L
private const val ROW_INTERVAL_MS = 1_000L
private const val VIDEO_CLIP_START_MS = 0L
private const val VIDEO_CLIP_END_MS = 3_500L
private const val FIXTURE_MAX_FPS = 24
private const val EXPECTED_VIDEO_MS = 1_500L
private const val CALIBRATED_VIDEO_MS = 1_800L
private const val FIRST_ROW_ORDINAL = 1
private const val FIXTURE_ROW_COUNT = 3
private const val SECOND_ROW_INDEX = 1

class CaptureAppRoundTripTest {
    @Test
    fun capturePreferencesUseJsonWithoutChangingLegacyDefaults() {
        val settings = AppSettings(
            captureSettings = CaptureSettings(
                adbPath = "/SDK path/adb",
                recordVideo = true,
                maxFps = FIXTURE_MAX_FPS,
            ),
        )
        assertEquals(settings.captureSettings, settingsFromJson(settings.settingsJson())?.captureSettings)
        assertEquals(CaptureSettings(), settingsFromJson("{}")?.captureSettings)
    }

    @Test
    fun zipAndExtractedDescriptorRestoreSameVideoPositionAfterRestart() = runBlocking {
        val root = createTempDirectory("capture-app-roundtrip").toFile()
        try {
            val zip = exportFixture(root)
            val extracted = File(root, "extracted").also { it.mkdirs() }
            ZipFile(zip).use { archive ->
                archive.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    File(extracted, entry.name).also { it.parentFile.mkdirs() }.outputStream().use { output ->
                        archive.getInputStream(entry).use { it.copyTo(output) }
                    }
                }
            }
            for ((index, source) in listOf(zip, File(extracted, CAPTURE_DESCRIPTOR_NAME)).withIndex()) {
                val autosave = File(root, "autosave-$index")
                val app = AppState(autosaveFile = autosave, autoExportNotes = false, archiveCacheDir = File(root, "cache-$index"))
                val id = app.openCaptureFile(source)
                waitLoaded(app, id)
                val tab = assertNotNull(app.tab(id))
                assertEquals(FIXTURE_ROW_COUNT, tab.logData.size)
                assertEquals(EXPECTED_VIDEO_MS, app.logIdToVideoMs(tab, tab.logData[SECOND_ROW_INDEX].id))
                assertTrue(app.isVideoDoubleClickSeekEnabled(id))
                app.setVideoAnchor(id, CALIBRATED_VIDEO_MS, tab.logData[SECOND_ROW_INDEX].id)
                assertEquals(
                    CALIBRATED_VIDEO_MS,
                    app.logIdToVideoMs(assertNotNull(app.tab(id)), tab.logData[SECOND_ROW_INDEX].id),
                )
                app.autosaveNow()
                app.close()

                val restored = AppState(autosaveFile = autosave, restoreOnCreate = true, autoExportNotes = false,
                    archiveCacheDir = File(root, "fresh-cache-$index"))
                try {
                    restored.startPendingRestoredTabLoads()
                    waitLoaded(restored, id)
                    val reopened = assertNotNull(restored.tab(id))
                    // Archive v3: no CaptureTimeline to rehydrate — the calibrated VideoAnchor set
                    // above is small enough to already be part of the restored tab token (see
                    // AutosaveCodec's anchorVideoMs/anchorLogId fields), and AppState.restoreCaptureLink
                    // must not clobber it with the archive's own (uncalibrated) estimate.
                    assertNull(reopened.captureTimeline)
                    assertNotNull(reopened.attachedVideo?.anchor)
                    assertEquals(
                        CALIBRATED_VIDEO_MS,
                        restored.logIdToVideoMs(reopened, reopened.logData[SECOND_ROW_INDEX].id),
                    )
                } finally {
                    restored.close()
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    // Regression test for the "Save + open" bug: CaptureStrip.kt's export-result LaunchedEffect
    // used to call state.openFile(file) directly on the just-exported ZIP, which parses the
    // archive's raw bytes as a plain text log instead of opening it as a capture archive. The fix
    // gates on CaptureArchiveReader.isCaptureArchive(file) and calls openCaptureFile for a true
    // capture archive, falling back to openFile only for something that genuinely isn't one — this
    // test exercises that exact gate against a real exported archive.
    @Test
    fun saveAndOpenGateRoutesARealExportedArchiveToTheCaptureOpener() = runBlocking {
        val root = createTempDirectory("capture-save-and-open").toFile()
        try {
            val zip = exportFixture(root)
            assertTrue(CaptureArchiveReader.isCaptureArchive(zip), "the exported ZIP must be recognized as a capture archive")

            val app = AppState(autosaveFile = File(root, "autosave"), autoExportNotes = false)
            try {
                val id = if (CaptureArchiveReader.isCaptureArchive(zip)) {
                    app.openCaptureFile(zip)
                } else {
                    checkNotNull(app.openFile(zip))
                }
                waitLoaded(app, id)
                val tab = assertNotNull(app.tab(id))
                assertEquals(FIXTURE_ROW_COUNT, tab.logData.size)
                assertTrue(
                    tab.logData.all { it.tag == "Tag" },
                    "rows must be the parsed capture log (tag \"Tag\"), not raw zip bytes",
                )
                assertTrue(tab.attachedVideo != null, "openCaptureFile must attach the video; plain openFile never does")
            } finally {
                app.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    // Documents the bug itself: routing the same archive through the plain-text opener (what
    // "Save + open" did before the fix) produces exactly the garbage the user reported — rows
    // tagged "RAW" whose message is the zip's raw binary, starting with the "PK" local-file-header
    // signature — and no attached video.
    @Test
    fun plainOpenFileOnACaptureArchiveProducesRawGarbageRowsNotTheParsedLog() = runBlocking {
        val root = createTempDirectory("capture-plain-open-garbage").toFile()
        try {
            val zip = exportFixture(root)
            val app = AppState(autosaveFile = File(root, "autosave"), autoExportNotes = false)
            try {
                val id = checkNotNull(app.openFile(zip))
                waitLoaded(app, id)
                val tab = assertNotNull(app.tab(id))
                assertNull(tab.attachedVideo)
                assertTrue(tab.logData.isNotEmpty())
                assertTrue(
                    tab.logData.any { it.tag == "RAW" && it.msg.contains("PK") },
                    "plain openFile must not have parsed the real capture log inside the zip",
                )
            } finally {
                app.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun alteredAssetsDisableMappingOnRestore() = runBlocking {
        val root = createTempDirectory("capture-tamper-restore").toFile()
        try {
            val zip = exportFixture(root)
            val autosave = File(root, "autosave")
            val app = AppState(autosaveFile = autosave, autoExportNotes = false)
            val id = app.openCaptureFile(zip)
            waitLoaded(app, id)
            app.autosaveNow()
            app.close()
            // An invalid archive must not inherit the prior session's synchronized timeline.
            zip.writeText("not an archive")
            val restored = AppState(autosaveFile = autosave, restoreOnCreate = true, autoExportNotes = false)
            try {
                restored.startPendingRestoredTabLoads()
                withTimeout(WAIT_TIMEOUT_MS) { while (restored.isLoadInFlight(id)) delay(POLL_INTERVAL_MS) }
                assertNull(restored.tab(id)?.captureTimeline)
                // Archive v3 tabs have no CaptureTimeline to begin with, so the assertion above
                // alone would be vacuous here — the real regression check for v3 is that a failed
                // reopen also disables double-click seek (AppState.restoreCaptureLink's catch path),
                // not that it silently keeps trusting a link it could no longer verify.
                assertFalse(restored.isVideoDoubleClickSeekEnabled(id))
            } finally {
                restored.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    // Regression test for Save ZIP silently dropping markers: CaptureStoppedStrip's "Save ZIP"
    // button (AppState.saveRetainedCapture) used to call CaptureService.exportRetainedSession with
    // no notes at all — only the live snapshot path (exportCaptureSnapshot) passed
    // tab.annotations.preparedForSave(tab). This exercises the retained path end to end: a stopped
    // session on disk plus an open tab carrying a marker note, exported via saveRetainedCapture(...,
    // tabId), then reopened — the marker note and its re-anchored LogRef must both survive.
    @Test
    fun saveRetainedCaptureIncludesTabNotesAndReopenedArchiveHasMarkers() = runBlocking {
        val root = createTempDirectory("capture-retained-notes").toFile()
        try {
            val sessionId = "session-notes"
            val session = CaptureSession(
                id = sessionId,
                directory = File(root, "captures/$sessionId"),
                device = CaptureDevice("emulator-5554", "device"),
                settings = CaptureSettings(recordVideo = false, freeSpaceReserveBytes = NO_FREE_SPACE_RESERVE_BYTES),
                startedEpochMs = FIXTURE_EPOCH_MS,
                elapsedMs = FIXTURE_ROW_COUNT * ROW_INTERVAL_MS,
                status = CaptureStatus.STOPPED,
            )
            session.logFile.parentFile.mkdirs()
            session.indexFile.parentFile.mkdirs()
            var offset = 0L
            session.logFile.outputStream().use { log ->
                session.indexFile.bufferedWriter().use { index ->
                    for (i in FIRST_ROW_ORDINAL..FIXTURE_ROW_COUNT) {
                        val bytes = "09-19 12:00:00.000  100  101 I Tag: row $i\n".toByteArray()
                        log.write(bytes)
                        index.appendLine(
                            "{\"byteOffset\":$offset,\"byteLength\":${bytes.size}," +
                                "\"elapsedMs\":${i * ROW_INTERVAL_MS},\"rowOrdinal\":$i}",
                        )
                        offset += bytes.size
                    }
                }
            }
            writeRetainedSessionMetadata(session)

            val app = AppState(autosaveFile = File(root, "autosave"), autoExportNotes = false)
            try {
                val marker = CaptureMarker(
                    id = "m1", elapsedMs = (SECOND_ROW_INDEX + 1) * ROW_INTERVAL_MS, firstOrdinal = SECOND_ROW_INDEX + 1,
                    lastOrdinal = SECOND_ROW_INDEX + 1, videoMs = null, label = "Marker 1",
                    preMs = 0L, postMs = 0L, screenshotPath = null,
                )
                val notes = Annotations(
                    blocks = listOf(
                        AnnBlock.Note("n1", markerHeader(marker) + "\n" + markerHeadingLine(1, marker.label) + "\n"),
                        AnnBlock.LogRef("r1", logIds = listOf(SECOND_ROW_INDEX + 1), caption = ""),
                    ),
                )
                val tab = mkTab("t1", "Capture — session-notes", emptyList())
                    .copy(captureSourceSessionId = sessionId, annotations = notes)
                app.tabs = listOf(tab)

                app.saveRetainedCapture(sessionId, "t1")
                withTimeout(WAIT_TIMEOUT_MS) {
                    while (app.captureExportResult == null && app.captureExportError == null) delay(POLL_INTERVAL_MS)
                }
                assertNull(app.captureExportError)
                val exported = assertNotNull(app.captureExportResult).file

                val reopenId = app.openCaptureFile(exported)
                waitLoaded(app, reopenId)
                val reopened = assertNotNull(app.tab(reopenId))
                val byId = reopened.annotations.blocks.associateBy { it.id }
                assertTrue("n1" in byId, "marker note must survive the retained Save ZIP -> reopen round trip")
                assertTrue("r1" in byId, "the marker's log reference must be re-anchored, not dropped")
                assertEquals(listOf(SECOND_ROW_INDEX + 1), (byId.getValue("r1") as AnnBlock.LogRef).logIds)
            } finally {
                app.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /** Mirrors CaptureRecorder's own private `sessionJson` format closely enough for
     *  CaptureService.retainedSession (via CaptureRecorder.listSessions/readSession) to recognize
     *  this as a retained session without spinning up a real adb/scrcpy recording. */
    private fun writeRetainedSessionMetadata(session: CaptureSession) {
        val json = buildJsonObject {
            put("formatVersion", 1)
            put("id", session.id)
            put("device", buildJsonObject {
                put("serial", session.device.serial)
                put("state", session.device.state)
                put("model", session.device.model)
                put("emulator", session.device.emulator)
            })
            put("settings", Json.parseToJsonElement(captureSettingsToJson(session.settings)))
            put("startedEpochMs", session.startedEpochMs)
            put("elapsedMs", session.elapsedMs)
            put("status", session.status.name)
        }.toString()
        File(session.directory, "session.json").also { it.parentFile.mkdirs() }.writeText(json)
    }

    private suspend fun waitLoaded(app: AppState, id: String) {
        withTimeout(WAIT_TIMEOUT_MS) { while (app.isLoadInFlight(id)) delay(POLL_INTERVAL_MS) }
        assertNotNull(app.tab(id), app.openError?.message)
    }

    private fun exportFixture(root: File): File {
        val session = CaptureSession(
            "test",
            File(root, "session"),
            CaptureDevice("emulator-5554", "device"),
            CaptureSettings(recordVideo = true, freeSpaceReserveBytes = NO_FREE_SPACE_RESERVE_BYTES),
            FIXTURE_EPOCH_MS,
            elapsedMs = FIXTURE_DURATION_MS,
            status = CaptureStatus.STOPPED,
            videoStartElapsedMs = VIDEO_START_MS,
        )
        session.logFile.parentFile.mkdirs()
        session.indexFile.parentFile.mkdirs()
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeText("fixture")
        var offset = 0L
        session.logFile.outputStream().use { log ->
            session.indexFile.bufferedWriter().use { index ->
                for (i in FIRST_ROW_ORDINAL..FIXTURE_ROW_COUNT) {
                    // Archive v3 (single sync anchor, no per-row mapping file): every row's video
                    // position is now derived from this ts via slope-1 arithmetic (see
                    // Model.kt's VideoAnchor doc), so each row needs its OWN, distinct device
                    // timestamp — one second apart, matching ROW_INTERVAL_MS's own host-elapsedMs
                    // spacing (a zero-jitter fixture) — rather than the repeated timestamp the old
                    // per-row-mapping-file archive could get away with.
                    val bytes = "09-19 12:00:0${i - 1}.000  100  101 I Tag: row $i\n".toByteArray()
                    log.write(bytes)
                    index.appendLine(
                        "{\"byteOffset\":$offset,\"byteLength\":${bytes.size}," +
                            "\"elapsedMs\":${i * ROW_INTERVAL_MS},\"rowOrdinal\":$i}",
                    )
                    offset += bytes.size
                }
            }
        }
        val exporter = CaptureArchiveExporter(CaptureVideoExporter { _, destination, _, _ ->
            destination.writeText("synthetic-video")
            CaptureVideoClip(VIDEO_CLIP_START_MS, VIDEO_CLIP_END_MS, VIDEO_CLIP_END_MS)
        })
        return exporter.export(
            session,
            CaptureExportRequest(File(root, "capture.zip"), cutoffElapsedMs = FIXTURE_DURATION_MS),
        ).file
    }
}
