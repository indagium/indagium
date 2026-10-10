@file:Suppress("MagicNumber") // Fixture counts, not tunable constants.

package com.indagium.testing

import com.indagium.debug.IndagiumToolOperations
import com.indagium.testing.authoring.ReplacementPlan
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestStep
import com.indagium.testing.store.RecordingCleanup
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Keep recording evidence with recorded cases" through AppState: the setting, apply, reopen, replace, discard, delete and the MCP tool. */
class RecordingEvidenceAppStateTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private var suiteId = ""
    private var caseId = ""

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("recording-evidence-app").toFile()
        state = AppState(
            autosaveFile = File(dir, "state.cache"),
            autoExportNotes = false,
            notesDir = File(dir, "notes"),
            archiveCacheDir = File(dir, "archive"),
            customCommandsDir = File(dir, "commands"),
            controlTokenFile = File(dir, "token"),
            sourceIndexFile = File(dir, "source-index"),
            testingDir = File(dir, "testing"),
        )
        suiteId = (state.createTestSuite("Shop") as StoreResult.Ok).value.id
        caseId = (state.createTestCase(suiteId, TestCase("", "Search")) as StoreResult.Ok).value.id
    }

    @AfterTest
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    private fun keep(on: Boolean) = state.updateSettings { it.copy(testing = it.testing.copy(keepRecordingEvidence = on)) }

    private fun recordingsDir() = File(dir, "testing/assets/$suiteId/recordings")

    private fun steps() = state.testLibrary.findCase(caseId)!!.case.steps

    /** Records, registers and applies a fresh shop recording; returns the steps it made. */
    private fun applyFresh(typed: String = "lofi", password: Boolean = false): List<TestStep> {
        val session = recordedShopSession(typed, passwordField = password)
        assertIs<StoreResult.Ok<*>>(state.registerTestStepRecording(session, suiteId, caseId))
        val rows = session.snapshot.value.steps
        val result = runBlocking { state.applyTestStepRecording(session.id, rows.map { it.action to "It works" }, null, emptySet(), rows.map { it.id }) }
        return assertIs<StoreResult.Ok<List<TestStep>>>(result).value
    }

    private fun digest(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate { file ->
        file.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }

    private fun reopen(): com.indagium.testing.authoring.TestStepRecordingSession =
        assertIs<StoreResult.Ok<com.indagium.testing.authoring.TestStepRecordingSession>>(runBlocking { state.reopenTestStepRecording(caseId) }).value

    @Test
    fun theSettingIsOffByDefaultAndThenNothingIsWritten() {
        assertFalse(state.settings.testing.keepRecordingEvidence)

        val made = applyFresh()

        assertEquals(3, made.size)
        assertTrue(made.all { it.recordingRef == null })
        assertFalse(File(dir, "testing/assets").exists(), "no evidence folder is created")
        assertEquals(emptyList(), state.storedRecordingIds(caseId))
        assertIs<StoreResult.Invalid>(runBlocking { state.reopenTestStepRecording(caseId) })
    }

    @Test
    fun withTheSettingOnApplyingSavesTheEvidenceAndLinksTheSteps() {
        keep(true)

        val made = applyFresh()

        val ref = assertNotNull(made.first().recordingRef)
        assertTrue(made.all { it.recordingRef == ref })
        assertEquals(listOf(ref), recordingsDir().list()!!.toList())
        val json = File(recordingsDir(), "$ref/recording.json").readText()
        assertTrue(made.all { "\"${it.id}\"" in json }, "the file names the steps it produced")
        assertEquals(listOf(ref), state.storedRecordingIds(caseId))
        assertEquals(ref, steps().first().recordingRef)
        assertEquals(1, state.storedRecordingUsage().recordings)
    }

    @Test
    fun aPasswordRecordingKeepsNoVideoAndNoScreensFromTheSensitiveTime() {
        keep(true)

        val made = applyFresh("hunter2-secret", password = true)

        val ref = made.first().recordingRef!!
        val folder = File(recordingsDir(), ref)
        assertFalse(File(folder, "video").exists())
        assertEquals(0, File(folder, "screens").listFiles().orEmpty().size)
        assertFalse("hunter2-secret" in folder.walkTopDown().filter { it.isFile }.joinToString { it.readText(Charsets.ISO_8859_1) })
        assertTrue(runBlocking { state.reopenTestStepRecording(caseId) } is StoreResult.Ok)
        assertTrue(state.testStepRecordingSession!!.snapshot.value.reopened!!.passwordSensitive)
    }

    @Test
    fun applyingAReopenedRecordingReplacesItsStepsInPlaceAndUpdatesTheStoredRows() {
        keep(true)
        val before = state.createTestStep(caseId, plainStep("First, unrelated")).let { (it as StoreResult.Ok).value }
        val made = applyFresh()
        state.createTestStep(caseId, plainStep("Last, unrelated"))
        val ref = made.first().recordingRef!!

        val session = reopen()
        assertEquals(3, session.snapshot.value.steps.size)
        assertIs<ReplacementPlan.Replace>(state.reopenedReplacementPlan(session.id))
        val rows = session.snapshot.value.steps.take(2)
        assertTrue(session.removeReviewedRows(setOf(session.snapshot.value.steps.last().id)))
        val result = runBlocking {
            state.applyTestStepRecording(session.id, rows.map { "${it.action} (re-reviewed)" to "Still works" }, 99, emptySet(), rows.map { it.id })
        }

        val inserted = assertIs<StoreResult.Ok<List<TestStep>>>(result).value
        assertEquals(2, inserted.size)
        val now = steps()
        assertEquals(before.id, now.first().id)
        assertEquals(listOf("First, unrelated") + rows.map { "${it.action} (re-reviewed)" } + "Last, unrelated", now.map { it.action })
        assertTrue(now.subList(1, 3).all { it.recordingRef == ref })
        assertNull(now.first().recordingRef)
        val json = File(recordingsDir(), "$ref/recording.json").readText()
        assertTrue(inserted.all { "\"${it.id}\"" in json } && made.none { "\"${it.id}\"" in json })
        assertTrue("(re-reviewed)" in json)
        assertNull(state.testStepRecordingSession, "a successful apply closes the review")
    }

    @Test
    fun whenTheProducedStepsWereDeletedItIsRefusedAndInsertAsNewKeepsTheOldOnes() {
        keep(true)
        val made = applyFresh()
        val ref = made.first().recordingRef!!
        state.deleteTestStep(made[1].id)

        val session = reopen()
        val plan = state.reopenedReplacementPlan(session.id)
        assertIs<ReplacementPlan.Ambiguous>(plan)
        val rows = session.snapshot.value.steps
        val edited = rows.map { it.action to "Result" }

        val refused = runBlocking { state.applyTestStepRecording(session.id, edited, null, emptySet(), rows.map { it.id }) }
        assertTrue(assertIs<StoreResult.Invalid>(refused).reason.contains("Insert as new steps"))
        assertEquals(2, steps().size, "nothing changed")
        assertNotNull(state.testStepRecordingSession, "the review stays open")

        val inserted = runBlocking { state.applyTestStepRecording(session.id, edited, 0, emptySet(), rows.map { it.id }, insertAsNew = true) }

        val added = assertIs<StoreResult.Ok<List<TestStep>>>(inserted).value
        assertEquals(5, steps().size)
        assertEquals(added.map { it.id }, steps().take(3).map { it.id })
        assertEquals(listOf(null, null), steps().drop(3).map { it.recordingRef }, "the old steps lost their link")
        val json = File(recordingsDir(), "$ref/recording.json").readText()
        assertTrue(added.all { "\"${it.id}\"" in json })
    }

    @Test
    fun discardingAReopenedRecordingLeavesTheStoredFilesUntouched() {
        keep(true)
        applyFresh()
        val stored = digest(recordingsDir())
        val librarySteps = steps()

        val session = reopen()
        assertTrue(session.updateReviewedSteps(session.snapshot.value.steps.map { "changed" to "changed" }))
        assertTrue(state.clearTestStepRecording(session.id))

        assertEquals(stored, digest(recordingsDir()))
        assertEquals(librarySteps, steps())
        assertEquals(1, state.storedRecordingIds(caseId).size)
    }

    @Test
    fun deletingTheCaseRemovesItsRecordingsAndDeleteAllClearsTheLinks() {
        keep(true)
        applyFresh()
        assertEquals(1, state.storedRecordingUsage().recordings)

        val open = reopen()
        assertIs<StoreResult.Invalid>(state.deleteStoredRecordingEvidence(), "refused while a recording is open")
        state.clearTestStepRecording(open.id)
        val removed = assertIs<StoreResult.Ok<*>>(state.deleteStoredRecordingEvidence())
        assertTrue(removed.value.toString().contains("recordings=1"))
        assertEquals(0, state.storedRecordingUsage().recordings)
        assertTrue(steps().all { it.recordingRef == null })

        applyFresh()
        state.deleteTestCase(caseId)
        RecordingCleanup.awaitIdle(5_000)
        assertEquals(0, state.storedRecordingUsage().recordings)
    }

    @Test
    fun theMcpToolReopensTheRecordingAndTheSuiteListsIt() {
        keep(true)
        val made = applyFresh()
        val operations = IndagiumToolOperations(state)

        @Suppress("UNCHECKED_CAST")
        fun call(tool: String, vararg args: Pair<String, Any?>) =
            runBlocking { operations.toolGateway.executeSuspending(tool, mapOf(*args)) as Map<String, Any?> }

        @Suppress("UNCHECKED_CAST")
        val cases = call("get_test_suite", "suiteId" to suiteId)["cases"] as List<Map<String, Any?>>

        @Suppress("UNCHECKED_CAST")
        val listed = cases.single()["recordings"] as List<Map<String, Any?>>
        assertEquals(made.first().recordingRef, listed.single()["recordingId"])
        assertEquals(3, listed.single()["stepCount"])

        val reopened = call("reopen_test_recording", "caseId" to caseId)
        assertTrue(reopened["error"] == null, reopened.toString())
        val sessionId = reopened["sessionId"] as String

        @Suppress("UNCHECKED_CAST")
        val info = reopened["reopened"] as Map<String, Any?>
        assertEquals(3, info["replacesStepCount"])
        assertEquals(false, info["passwordSensitive"])
        assertEquals(sessionId, (call("get_test_recording", "sessionId" to sessionId))["sessionId"])
        assertTrue(call("reopen_test_recording", "caseId" to caseId)["error"] != null, "only one recording at a time")
        assertEquals(true, call("discard_test_recording", "sessionId" to sessionId)["discarded"])
        assertTrue(call("export_test_suite", "suiteId" to suiteId)["recordingEvidence"] != null)
    }

    @Test
    fun reopeningAsksLikeStartingARecordingDoes() {
        assertEquals(
            com.indagium.debug.IndagiumToolActionPolicy.CONFIRMATION_REQUIRED,
            IndagiumToolOperations(state).toolGateway.actionPolicy("reopen_test_recording"),
        )
        assertTrue("reopen_test_recording" in com.indagium.debug.PER_CALL_APPROVAL_MCP_TOOLS)
        val details = runBlocking {
            com.indagium.debug.describePerCallApproval(state, "reopen_test_recording", mapOf("caseId" to caseId), "Fixture client")
        }
        assertNotNull(details)
        assertTrue(details.summary.contains("no device"))
        assertNull(runBlocking { com.indagium.debug.describePerCallApproval(state, "reopen_test_recording", mapOf("caseId" to "case-none"), "c") })
    }
}
