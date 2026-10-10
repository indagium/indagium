package com.indagium.testing

import com.indagium.testing.authoring.RecordedTestStep
import com.indagium.testing.authoring.RecordingImageStore
import com.indagium.testing.authoring.ReplacementPlan
import com.indagium.testing.authoring.StoredPrivacy
import com.indagium.testing.authoring.StoredRecording
import com.indagium.testing.authoring.StoredVideoInfo
import com.indagium.testing.authoring.decodeStoredRecording
import com.indagium.testing.authoring.encodeStoredRecording
import com.indagium.testing.authoring.planReplacement
import com.indagium.testing.model.StepIntent
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestStep
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.TestLibraryStore
import com.indagium.testing.store.decodeSuiteFile
import com.indagium.testing.store.encodeSuiteFile
import com.indagium.testing.store.stepToJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How a step's link to stored recording evidence travels through the library: codec, copies, exports, deletes, moves and replacement. */
class RecordingEvidenceLibraryTest {
    private val dir = tempTestingDir()
    private val store = TestLibraryStore(dir, cleanup = { it() })

    private fun <T> StoreResult<T>.ok(): T {
        assertIs<StoreResult.Ok<T>>(this, "expected Ok but was $this")
        return value
    }

    private fun recordedStep(action: String, ref: String? = "rec-1") = plainStep(action).copy(recordingRef = ref)

    /** A suite with a case whose steps came from recording `rec-1`, and a stored folder for it. */
    private fun suiteWithRecording(): Pair<String, TestCase> {
        val suite = store.createSuite("Suite").ok()
        val case = store.createCase(suite.id, plainCase("Case").copy(steps = listOf(recordedStep("a"), recordedStep("b"), plainStep("c")))).ok()
        File(dir, "assets/${suite.id}/recordings/rec-1").apply { mkdirs() }
            .resolve("recording.json").writeText("""{"format":"indagium-test-recording","version":1,"recordingId":"rec-1","caseId":"${case.id}"}""")
        return suite.id to case
    }

    @Test
    fun theLinkIsAppendedLastRoundTripsAndAFileWithoutItStillLoads() {
        val step = recordedStep("Open")
        assertEquals("rec-1", stepToJson(step)["recordingRef"]?.toString()?.trim('"'))
        assertFalse(stepToJson(plainStep("x")).containsKey("recordingRef"), "a step without evidence is written exactly as before")
        val suite = suiteWithCases("S", 0).copy(cases = listOf(plainCase("C").copy(steps = listOf(step))))

        val decoded = decodeSuiteFile(encodeSuiteFile(suite)).getOrThrow().suite
        assertEquals("rec-1", decoded.cases.single().steps.single().recordingRef)

        val old = encodeSuiteFile(suite).replace(Regex(",\\s*\"recordingRef\": \"rec-1\""), "")
        assertNull(decodeSuiteFile(old).getOrThrow().suite.cases.single().steps.single().recordingRef)
        val unsafe = encodeSuiteFile(suite).replace("\"rec-1\"", "\"../x\"")
        assertNull(decodeSuiteFile(unsafe).getOrThrow().suite.cases.single().steps.single().recordingRef, "an unsafe id is not kept")
    }

    @Test
    fun retainedRecordingRowsRoundTripPlaybackSemanticsAndReviewProvenance() {
        val images = object : RecordingImageStore {
            override fun put(bytes: ByteArray) = ""

            override fun get(ref: String): ByteArray? = null
        }
        val row = RecordedTestStep(
            id = "recorded-row",
            action = "Keep the video paused",
            expected = "Playback remains paused",
            intent = StepIntent.ENSURE_STATE,
            precondition = "Playback is active",
            humanReviewed = true,
            modelSupported = true,
        )
        val recording = StoredRecording(
            recordingId = "recording-1",
            caseId = "case-1",
            deviceSerial = "emulator-5554",
            createdAtMs = 1L,
            updatedAtMs = 2L,
            stepIds = listOf("step-1"),
            rows = listOf(row),
            rawRows = null,
            rewriteApplied = true,
            rewriteNotes = "",
            excludedSourceInputIds = emptySet(),
            screenStates = emptyList(),
            probeScreenshots = emptyList(),
            warnings = emptyList(),
            privacy = StoredPrivacy(),
            video = StoredVideoInfo(stored = false),
        )

        val reopened = decodeStoredRecording(encodeStoredRecording(recording, images), images).getOrThrow()

        assertEquals(StepIntent.ENSURE_STATE, reopened.rows.single().intent)
        assertEquals("Playback is active", reopened.rows.single().precondition)
        assertTrue(reopened.rows.single().humanReviewed)
        assertTrue(reopened.rows.single().modelSupported)
    }

    @Test
    fun theLinkSurvivesAReloadFromDisk() {
        val (suiteId, _) = suiteWithRecording()
        val reloaded = TestLibraryStore(dir)
        assertEquals(listOf("rec-1", "rec-1", null), reloaded.suite(suiteId)!!.cases.single().steps.map { it.recordingRef })
    }

    @Test
    fun copiesImportsAndExportsDoNotCarryTheLinkButTheOriginalKeepsIt() {
        val (suiteId, case) = suiteWithRecording()

        val caseCopy = store.duplicateCase(case.id).ok()
        val stepCopy = store.duplicateStep(case.steps.first().id).ok()
        val suiteCopy = store.duplicateSuite(suiteId).ok()
        val exported = store.exportSuite(suiteId).ok()
        val imported = store.importSuite(exported).ok()

        assertTrue(caseCopy.steps.all { it.recordingRef == null })
        assertNull(stepCopy.recordingRef)
        assertTrue(suiteCopy.cases.flatMap { it.steps }.all { it.recordingRef == null })
        assertFalse("recordingRef" in exported, "an export never names stored evidence")
        assertTrue(imported.cases.flatMap { it.steps }.all { it.recordingRef == null })
        assertEquals("rec-1", store.suite(suiteId)!!.cases.first().steps.first().recordingRef)
        assertTrue(store.suiteHasRecordingEvidence(suiteId))
        assertFalse(store.suiteHasRecordingEvidence(suiteCopy.id))
        assertTrue(store.evidence.exists(suiteId, "rec-1"), "copying never touches the stored folder")
    }

    @Test
    fun deletingTheCaseOrTheSuiteDeletesItsRecordings() {
        val (suiteId, case) = suiteWithRecording()
        File(dir, "assets/$suiteId/golden.png").writeText("x")

        store.deleteCase(case.id).ok()
        assertFalse(store.evidence.exists(suiteId, "rec-1"))

        val (second, _) = suiteWithRecording()
        store.deleteSuite(second).ok()
        assertFalse(File(dir, "assets/$second/recordings").exists())
        assertTrue(File(dir, "assets/$suiteId/golden.png").exists(), "golden screenshots are not recordings")
    }

    @Test
    fun movingTheCaseToAnotherSuiteMovesItsRecordingsAlong() {
        val (suiteId, case) = suiteWithRecording()
        val target = store.createSuite("Target").ok()

        store.moveCase(case.id, 0, target.id).ok()

        assertFalse(store.evidence.exists(suiteId, "rec-1"))
        assertTrue(store.evidence.exists(target.id, "rec-1"))
    }

    @Test
    fun replacingStepsKeepsThePlaceAndRefusesAnythingThatIsNotTheContiguousBlock() {
        val (_, case) = suiteWithRecording()
        val ids = case.steps.take(2).map { it.id }

        val replaced = store.replaceSteps(case.id, ids, listOf(recordedStep("x"), recordedStep("y"), recordedStep("z"))).ok()

        assertEquals(3, replaced.size)
        val steps = store.library.value.findCase(case.id)!!.case.steps
        assertEquals(listOf("x", "y", "z", "c"), steps.map { it.action })
        assertTrue(steps.take(3).all { it.id !in ids }, "the new steps have fresh ids")

        val swapped = store.replaceSteps(case.id, listOf(steps[1].id, steps[0].id), listOf(plainStep("q")))
        assertIs<StoreResult.Invalid>(swapped)
        val gap = store.replaceSteps(case.id, listOf(steps[0].id, steps[2].id), listOf(plainStep("q")))
        assertIs<StoreResult.Invalid>(gap)
        assertIs<StoreResult.Invalid>(store.replaceSteps(case.id, listOf("step-gone"), listOf(plainStep("q"))))
        assertEquals(listOf("x", "y", "z", "c"), store.library.value.findCase(case.id)!!.case.steps.map { it.action }, "nothing changed")
    }

    @Test
    fun replacingStepsRejectsSameIdContentChangedAfterRecordingWasReopened() {
        val (_, originalCase) = suiteWithRecording()
        val baseline = originalCase.steps.take(2)
        val ids = baseline.map { it.id }
        store.updateStep(ids.first()) { it.copy(action = "Edited after reopen") }.ok()

        val result = store.replaceSteps(originalCase.id, ids, listOf(plainStep("replacement")), expectedBaseline = baseline)

        assertIs<StoreResult.Invalid>(result)
        assertEquals("Edited after reopen", store.library.value.findCase(originalCase.id)!!.case.steps.first().action)
    }

    @Test
    fun clearingLinksByRecordingOrByStepLeavesTheOtherStepsAlone() {
        val (suiteId, case) = suiteWithRecording()

        assertEquals(1, store.clearRecordingRefsOfSteps(setOf(case.steps[0].id)).ok())
        assertEquals(listOf(null, "rec-1", null), store.suite(suiteId)!!.cases.single().steps.map { it.recordingRef })
        assertEquals(1, store.clearRecordingRefs(setOf("rec-1")).ok())
        assertTrue(store.suite(suiteId)!!.cases.single().steps.all { it.recordingRef == null })
    }

    @Test
    fun theReplacementPlanNeedsEveryProducedStepTogetherAndInOrder() {
        val steps = listOf(plainStep("a"), plainStep("b"), plainStep("c"), plainStep("d"))
        val case = plainCase("C").copy(steps = steps)
        val ids = steps.map { it.id }

        assertEquals(ids.subList(1, 3), assertIs<ReplacementPlan.Replace>(planReplacement(case, ids.subList(1, 3))).stepIds)
        assertIs<ReplacementPlan.Ambiguous>(planReplacement(case, listOf(ids[0], ids[2])), "a step between them")
        assertIs<ReplacementPlan.Ambiguous>(planReplacement(case, listOf(ids[2], ids[1])), "reordered")
        assertTrue(assertIs<ReplacementPlan.Ambiguous>(planReplacement(case, listOf(ids[1], "gone"))).reason.contains("no longer"))
        assertIs<ReplacementPlan.Ambiguous>(planReplacement(case, emptyList()))
        val fromStep: TestStep = steps.first()
        assertEquals(fromStep.id, ids.first())
    }
}
