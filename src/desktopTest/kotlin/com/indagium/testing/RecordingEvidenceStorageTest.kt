@file:Suppress("MagicNumber") // Fixture timestamps, sizes and counts, not tunable constants.

package com.indagium.testing

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.testing.authoring.MAX_RECORDING_EVIDENCE_BYTES
import com.indagium.testing.authoring.RECORDING_FILE_FORMAT
import com.indagium.testing.authoring.RECORDING_FILE_VERSION
import com.indagium.testing.authoring.REDACTED_NODE_RESOURCE_ID
import com.indagium.testing.authoring.RecordingEvidenceSaver
import com.indagium.testing.authoring.RecordingRewrite
import com.indagium.testing.authoring.RecordingRewriteService
import com.indagium.testing.authoring.RewriteGeneration
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.VIDEO_FIXTURE_BASE_WALL_MS
import com.indagium.testing.authoring.finishedFixtureTimeline
import com.indagium.testing.authoring.loadStoredRecording
import com.indagium.testing.authoring.restoreRecordingSession
import com.indagium.testing.store.RECORDING_FILE_NAME
import com.indagium.testing.store.RecordingEvidenceFiles
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saving the evidence of an applied recording, reading it back, and what the privacy and size rules leave out. */
class RecordingEvidenceStorageTest {
    private lateinit var root: File
    private lateinit var files: RecordingEvidenceFiles
    private val opened = ArrayList<TestStepRecordingSession>()

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("recording-evidence").toFile()
        files = RecordingEvidenceFiles(root)
    }

    @AfterTest
    fun tearDown() {
        opened.forEach(TestStepRecordingSession::close)
        root.deleteRecursively()
    }

    private fun session(block: () -> TestStepRecordingSession) = block().also(opened::add)

    private fun saved(session: TestStepRecordingSession, id: String = "rec-1", stepIds: List<String> = listOf("step-1", "step-2")) =
        RecordingEvidenceSaver(files).saveNew(SHOP_SUITE_ID, SHOP_CASE_ID, id, session.evidence(), stepIds)

    private fun recordingJson(id: String = "rec-1"): JsonObject =
        Json.parseToJsonElement(File(files.recordingDir(SHOP_SUITE_ID, id)!!, RECORDING_FILE_NAME).readText()).jsonObject

    private fun touch(action: MirrorTouchAction) =
        MirrorControlCommand.Touch(action = action, pointerId = 1, x = 50, y = 50, screenWidth = 100, screenHeight = 100)

    /** A recording of two taps over the fixture video, stopped and drained. */
    private fun videoSession(): TestStepRecordingSession = session {
        val recording = TestStepRecordingSession("fixture-device", videoTimeline = finishedFixtureTimeline())
        recording.accept(touch(MirrorTouchAction.DOWN), nowMs = VIDEO_FIXTURE_BASE_WALL_MS)
        recording.accept(touch(MirrorTouchAction.UP), nowMs = VIDEO_FIXTURE_BASE_WALL_MS + 50)
        recording.accept(touch(MirrorTouchAction.DOWN), nowMs = VIDEO_FIXTURE_BASE_WALL_MS + 300)
        recording.accept(touch(MirrorTouchAction.UP), nowMs = VIDEO_FIXTURE_BASE_WALL_MS + 350)
        runBlocking { recording.stopAndDrain() }
        recording
    }

    private fun digest(dir: File): Map<String, String> = dir.walkTopDown().filter { it.isFile }.associate { file ->
        file.relativeTo(dir).path to MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun theEnvelopeCarriesTheRowsScreensAndStepsAndReadsBackAsAStoppedRecording() {
        val recording = session { recordedShopSession("lofi") }
        val before = recording.snapshot.value

        val result = assertIs<StoreResult.Ok<*>>(saved(recording))

        val json = recordingJson()
        assertEquals(RECORDING_FILE_FORMAT, json["format"]!!.jsonPrimitive.content)
        assertEquals(RECORDING_FILE_VERSION, json["version"]!!.jsonPrimitive.int)
        assertEquals(SHOP_CASE_ID, json["caseId"]!!.jsonPrimitive.content)
        assertEquals("fixture-device", json["deviceSerial"]!!.jsonPrimitive.content)
        assertEquals(listOf("step-1", "step-2"), json["stepIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(3, json["rows"]!!.jsonArray.size)
        assertEquals(2, json["screenStates"]!!.jsonArray.size)
        assertFalse(json["privacy"]!!.jsonObject["passwordSensitive"]!!.jsonPrimitive.boolean)
        assertTrue(result.value.toString().isNotEmpty())
        val screens = File(files.recordingDir(SHOP_SUITE_ID, "rec-1")!!, "screens").listFiles().orEmpty()
        assertEquals(2, screens.size, "the start and the end image, once each")

        val loaded = assertIs<StoreResult.Ok<com.indagium.testing.authoring.LoadedRecording>>(loadStoredRecording(files, SHOP_SUITE_ID, "rec-1")).value
        val stored = loaded.recording
        assertEquals(listOf("step-1", "step-2"), stored.stepIds)
        assertEquals(before.steps.map { it.id }, stored.rows.map { it.id })
        assertEquals(before.steps.map { it.action }, stored.rows.map { it.action })
        assertEquals(before.steps.map { it.kind }, stored.rows.map { it.kind })
        assertEquals(before.steps.map { it.before }, stored.rows.map { it.before })
        assertEquals(before.steps.map { it.tappedElement }, stored.rows.map { it.tappedElement })
        assertEquals(before.steps.map { it.gesturePath }, stored.rows.map { it.gesturePath })
        assertContentEquals(SHOP_BEFORE_IMAGE, stored.screenStates.first { it.seq == 1 }.screenshotJpeg)
        assertEquals("Search", stored.screenStates.first { it.seq == 1 }.nodes.first { it.resourceId.endsWith("search_button") }.text)

        val restored = session { restoreRecordingSession(loaded) }
        val snapshot = restored.snapshot.value
        assertFalse(snapshot.active)
        assertEquals(0, snapshot.pendingSnapshots)
        assertEquals(before.steps.map { it.id }, snapshot.steps.map { it.id })
        assertEquals("fixture-device", restored.deviceSerial)
        assertNotNull(restored.screenState(1))
        assertNotNull(snapshot.reopened)
        assertEquals("rec-1", snapshot.reopened?.recordingId)
        assertNull(restored.rewriteBlockedReason(), "the restored recording can be rewritten")
    }

    @Test
    fun aPasswordKeepsAnyVideoAndTheScreensInsideItsWindowOutOfTheFolder() {
        val recording = session { recordedShopSession("hunter2-secret", passwordField = true) }
        val text = recording.snapshot.value.steps.first().action
        assertTrue("••••" in text && "hunter2-secret" !in text, "the typed password is already hidden in the row")

        assertIs<StoreResult.Ok<*>>(saved(recording))

        val dir = files.recordingDir(SHOP_SUITE_ID, "rec-1")!!
        val everything = dir.walkTopDown().filter { it.isFile }.joinToString("\n") { it.name + "=" + (if (it.extension == "json") it.readText() else "") }
        assertFalse("Signed in" in everything, "no screen text from inside the window")
        assertFalse("Search" in everything.replace("\"Search for", ""), "no UI node text from inside the window")
        assertFalse("hunter2-secret" in everything)
        assertFalse(File(dir, "video").exists(), "no video is stored for a recording with a password")
        assertEquals(0, File(dir, "screens").listFiles().orEmpty().size, "every image of the window was dropped")
        val json = recordingJson()
        val privacy = json["privacy"]!!.jsonObject
        assertTrue(privacy["passwordSensitive"]!!.jsonPrimitive.boolean)
        assertFalse(privacy["videoStored"]!!.jsonPrimitive.boolean)
        assertTrue(privacy["statement"]!!.jsonPrimitive.content.contains("password"))
        assertTrue(privacy["windows"]!!.jsonArray.isNotEmpty())
        assertFalse(json["video"]!!.jsonObject["stored"]!!.jsonPrimitive.boolean)
        val nodes = json["screenStates"]!!.jsonArray.flatMap { it.jsonObject["nodes"]!!.jsonArray }
        assertTrue(nodes.all { it.jsonObject["resourceId"]!!.jsonPrimitive.content == REDACTED_NODE_RESOURCE_ID }, "only the marker node is kept")

        val loaded = assertIs<StoreResult.Ok<com.indagium.testing.authoring.LoadedRecording>>(loadStoredRecording(files, SHOP_SUITE_ID, "rec-1")).value
        val restored = session { restoreRecordingSession(loaded) }
        assertTrue(restored.screenState(1)!!.passwordFieldLikelyEdited(), "the window is still recognised when the recording is reopened")
        assertTrue(restored.snapshot.value.reopened!!.passwordSensitive)
        assertTrue(restored.snapshot.value.reopened!!.notes.any { "video" in it })
    }

    @Test
    fun theVideoIsCopiedAndReopensReadOnlyWithTheSameFrames() {
        val recording = videoSession()
        val live = recording.videoTimeline!!
        val liveStills = live.stillsAt(listOf(120L, 500L, 5_000L), emptyList())!!.map { it?.image?.getRGB(5, 200) }

        val result = assertIs<StoreResult.Ok<*>>(saved(recording))
        assertTrue(result.value.toString().isNotEmpty())
        recording.close()

        val dir = files.recordingDir(SHOP_SUITE_ID, "rec-1")!!
        assertTrue(File(dir, "video/timeline-1.mkv").length() > 0L)
        assertTrue(File(dir, "video/timeline.jsonl").length() > 0L)
        assertTrue(recordingJson()["video"]!!.jsonObject["stored"]!!.jsonPrimitive.boolean)
        val stored = digest(dir)

        val loaded = assertIs<StoreResult.Ok<com.indagium.testing.authoring.LoadedRecording>>(loadStoredRecording(files, SHOP_SUITE_ID, "rec-1")).value
        val restored = session { restoreRecordingSession(loaded) }
        val timeline = assertNotNull(restored.videoTimeline)
        assertTrue(timeline.isStored)
        assertTrue(timeline.summary().available)
        assertEquals(live.summary().durationMs, timeline.summary().durationMs)
        assertEquals(liveStills, timeline.stillsAt(listOf(120L, 500L, 5_000L), emptyList())!!.map { it?.image?.getRGB(5, 200) })
        assertEquals(3, liveStills.filterNotNull().distinct().size, "three different scenes were found")
        restored.close()

        assertEquals(stored, digest(dir), "reading and closing a reopened recording never changes a stored file")
    }

    @Test
    fun overTheSizeCapTheVideoIsLeftOutWithAWarningAndThenTheImages() {
        val full = assertIs<StoreResult.Ok<com.indagium.testing.authoring.SavedRecording>>(saved(videoSession(), id = "rec-full"))
        val dir = files.recordingDir(SHOP_SUITE_ID, "rec-full")!!
        val videoBytes = File(dir, "video").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue(videoBytes > 0L && full.value.videoStored)
        assertTrue(MAX_RECORDING_EVIDENCE_BYTES == 200L * 1024 * 1024)

        val withoutVideo = RecordingEvidenceSaver(files, capBytes = full.value.bytes - videoBytes / 2)
            .saveNew(SHOP_SUITE_ID, SHOP_CASE_ID, "rec-nv", videoSession().evidence(), listOf("step-1"))

        val capped = assertIs<StoreResult.Ok<com.indagium.testing.authoring.SavedRecording>>(withoutVideo).value
        assertFalse(capped.videoStored)
        assertTrue(capped.notes.any { "video" in it && "not stored" in it }, capped.notes.toString())
        assertFalse(File(files.recordingDir(SHOP_SUITE_ID, "rec-nv")!!, "video").exists())
        assertTrue(recordingJson("rec-nv")["notices"]!!.jsonArray.isNotEmpty())

        val bare = RecordingEvidenceSaver(files, capBytes = 10L)
            .saveNew(SHOP_SUITE_ID, SHOP_CASE_ID, "rec-bare", session { recordedShopSession() }.evidence(), listOf("step-1"))

        val tiny = assertIs<StoreResult.Ok<com.indagium.testing.authoring.SavedRecording>>(bare).value
        assertTrue(tiny.notes.any { "screenshots were not stored" in it }, tiny.notes.toString())
        assertEquals(0, File(files.recordingDir(SHOP_SUITE_ID, "rec-bare")!!, "screens").listFiles().orEmpty().size)
    }

    @Test
    fun aFailedSaveLeavesNoFolderBehindAndTheSuiteIdCannotEscape() {
        val recording = session { recordedShopSession() }

        val unsafe = RecordingEvidenceSaver(files).saveNew("../escape", SHOP_CASE_ID, "rec-1", recording.evidence(), listOf("step-1"))

        assertIs<StoreResult.Invalid>(unsafe)
        assertNull(files.recordingDir("../escape", "rec-1"))
        assertNull(files.recordingDir(SHOP_SUITE_ID, "../rec"))
        assertNull(files.recordingDir(SHOP_SUITE_ID, ".staging-x"))
        assertFalse(File(root, "assets").exists() && File(root, "assets").walkTopDown().any { it.isFile })
    }

    @Test
    fun updatingTheRowsOfAStoredRecordingTouchesOnlyItsJson() {
        val recording = session { recordedShopSession() }
        assertIs<StoreResult.Ok<*>>(saved(recording))
        val dir = files.recordingDir(SHOP_SUITE_ID, "rec-1")!!
        val imagesBefore = digest(File(dir, "screens"))
        val loaded = assertIs<StoreResult.Ok<com.indagium.testing.authoring.LoadedRecording>>(loadStoredRecording(files, SHOP_SUITE_ID, "rec-1")).value
        val reopened = session { restoreRecordingSession(loaded) }
        assertTrue(reopened.updateReviewedSteps(reopened.snapshot.value.steps.map { "${it.action} (edited)" to "It works" }))

        val updated = RecordingEvidenceSaver(files).updateRows(SHOP_SUITE_ID, loaded.recording, reopened.evidence(), listOf("n-1", "n-2", "n-3"))

        assertIs<StoreResult.Ok<*>>(updated)
        val json = recordingJson()
        assertEquals(listOf("n-1", "n-2", "n-3"), json["stepIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(json["rows"]!!.jsonArray.all { it.jsonObject["action"]!!.jsonPrimitive.content.endsWith("(edited)") })
        assertEquals(imagesBefore, digest(File(dir, "screens")))
    }

    @Test
    fun aNewerFileVersionAndForeignFilesAreRefusedNotHalfRead() {
        val recording = session { recordedShopSession() }
        assertIs<StoreResult.Ok<*>>(saved(recording))
        val file = File(files.recordingDir(SHOP_SUITE_ID, "rec-1")!!, RECORDING_FILE_NAME)
        val original = file.readText()

        file.writeText(original.replace("\"version\": $RECORDING_FILE_VERSION", "\"version\": 99"))
        val newer = loadStoredRecording(files, SHOP_SUITE_ID, "rec-1")
        assertTrue(assertIs<StoreResult.Invalid>(newer).reason.contains("newer version"))

        file.writeText("""{"format":"something-else","version":1}""")
        assertIs<StoreResult.Invalid>(loadStoredRecording(files, SHOP_SUITE_ID, "rec-1"))
        file.writeText("not json")
        assertIs<StoreResult.Invalid>(loadStoredRecording(files, SHOP_SUITE_ID, "rec-1"))
        assertIs<StoreResult.NotFound>(loadStoredRecording(files, SHOP_SUITE_ID, "rec-missing"))
    }

    @Test
    fun theRewriteRunsOnAReopenedRecordingFromItsStoredScreensAndVideo() {
        val recording = videoSession()
        assertIs<StoreResult.Ok<*>>(saved(recording))
        recording.close()
        val loaded = assertIs<StoreResult.Ok<com.indagium.testing.authoring.LoadedRecording>>(loadStoredRecording(files, SHOP_SUITE_ID, "rec-1")).value
        val reopened = session { restoreRecordingSession(loaded) }
        var seen: RewriteGeneration? = null
        val answer = """{"steps":[{"action":"Open YouTube","expected":"YouTube is open","sourceInputs":[1]},""" +
            """{"action":"Pause playback","expected":"The player is paused","sourceInputs":[2]}]}"""
        val service = RecordingRewriteService({ shopLibrary() }, { _, _ -> StoreResult.Ok(Unit) }, { request -> seen = request; answer })

        val result = runBlocking { service.rewrite(reopened, SHOP_SUITE_ID, SHOP_CASE_ID, "p", null, null, "") }

        assertIs<StoreResult.Ok<RecordingRewrite>>(result)
        val request = assertNotNull(seen)
        assertEquals(1, assertNotNull(request.attachment).images.size, "both inputs on one sheet of the stored video")
        assertTrue("recorded video" in request.prompt, request.prompt)
        // Neither tap has a resolved target and the scripted answer inspected no video, so both rows keep their text for review.
        assertTrue(reopened.snapshot.value.rewriteApplied)
        assertEquals(listOf(1, 1), reopened.snapshot.value.steps.map { it.sourceInputIds.size })
        assertEquals(2, reopened.snapshot.value.rawSteps!!.size)
    }

    @Test
    fun theFolderHelpersMoveDeleteAndMeasureRecordings() {
        val a = session { recordedShopSession() }
        assertIs<StoreResult.Ok<*>>(saved(a, "rec-a"))
        assertIs<StoreResult.Ok<*>>(saved(session { recordedShopSession() }, "rec-b"))
        assertEquals(listOf("rec-a", "rec-b"), files.listIds(SHOP_SUITE_ID))
        assertEquals(SHOP_CASE_ID, files.caseIdOf(SHOP_SUITE_ID, "rec-a"))
        assertEquals(2, files.usage().recordings)
        assertTrue(files.usage().bytes > 0L)

        files.moveCase(SHOP_SUITE_ID, "suite-2", SHOP_CASE_ID)
        assertEquals(emptyList(), files.listIds(SHOP_SUITE_ID))
        assertEquals(listOf("rec-a", "rec-b"), files.listIds("suite-2"))

        files.deleteCase("suite-2", SHOP_CASE_ID)
        assertEquals(emptyList(), files.listIds("suite-2"))
        assertEquals(0, files.usage().recordings)
    }

    @Test
    fun jsonOfAnUnsensitiveRecordingKeepsEveryNodeTextAndTheImageFileNamesAreContentHashes() {
        val recording = session { recordedShopSession() }
        assertIs<StoreResult.Ok<*>>(saved(recording))
        val names = File(files.recordingDir(SHOP_SUITE_ID, "rec-1")!!, "screens").listFiles().orEmpty().map { it.name }

        assertTrue(names.all { Regex("^[0-9a-f]{32}\\.jpg$").matches(it) }, names.toString())
        val array: JsonArray = recordingJson()["rows"]!!.jsonArray
        assertTrue(array.all { (it.jsonObject["id"] as JsonPrimitive).content.isNotBlank() })
    }
}
