package com.indagium.testing

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.testing.authoring.RecordedTestStep
import com.indagium.testing.authoring.RewriteDescription
import com.indagium.testing.authoring.TestStepRecordingApplyService
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.describe
import com.indagium.testing.authoring.recordingApplyBlockedReason
import com.indagium.testing.authoring.rewriteInputsOf
import com.indagium.testing.model.DEFAULT_MANUAL_STEP_TIMEOUT_MS
import com.indagium.testing.model.ManualCompletion
import com.indagium.testing.model.ScriptCall
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.newCheckId
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.TestLibraryStore
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Author-added rows of the recording review can be manual or script steps; they keep their kind through review, the rewrite and Apply. */
class RecordingStepKindsTest {
    private val sessions = mutableListOf<TestStepRecordingSession>()

    @AfterTest
    fun closeSessions() = sessions.forEach(TestStepRecordingSession::close)

    private fun stopped(): TestStepRecordingSession {
        val session = TestStepRecordingSession("fixture-device").also { sessions += it }
        session.accept(MirrorControlCommand.Text("hello"), nowMs = 1_000L)
        runBlocking { session.stopAndDrain() }
        return session
    }

    private val usb = StepCheck.DeviceState(newCheckId(), "usb.devices", "0781:5581")

    @Test
    fun anInsertedRowKeepsItsKindScriptCompletionAndChecks() {
        val session = stopped()
        val row = RecordedTestStep(
            action = "Plug the stick in", stepKind = StepKind.MANUAL, script = ScriptCall("script-hub"),
            manualCompletion = ManualCompletion.CHECKS_ONLY, checks = listOf(usb),
        )

        assertTrue(session.insertReviewedRow(0, row))

        val inserted = session.snapshot.value.steps.first()
        assertTrue(inserted.authorAdded)
        assertEquals(StepKind.MANUAL, inserted.stepKind)
        assertEquals(ScriptCall("script-hub"), inserted.script)
        assertEquals(ManualCompletion.CHECKS_ONLY, inserted.manualCompletion)
        assertEquals(listOf(usb), inserted.checks)
        assertEquals(StepKind.AGENT, session.snapshot.value.steps.last().stepKind, "a recorded input stays an AI step")
    }

    @Test
    fun aManualOrScriptRowNeedsNoExpectedResultButAScriptRowNeedsItsScript() {
        val session = stopped()
        session.insertReviewedRow(0, RecordedTestStep(action = "Plug it in", stepKind = StepKind.MANUAL))
        session.insertReviewedRow(1, RecordedTestStep(action = "Hub off", stepKind = StepKind.SCRIPT))
        session.insertReviewedRow(2, RecordedTestStep(action = "Hub on", stepKind = StepKind.SCRIPT, script = ScriptCall("script-hub")))

        val blank = session.snapshot.value.let { it.copy(steps = it.steps.map { row -> row.copy(expected = "") }) }
        assertEquals(
            "Fill in Expected for step 4; Choose a script for step 2.", recordingApplyBlockedReason(blank),
            "only the recorded AI input needs an expected result",
        )

        val fixed = session.snapshot.value.steps.map { row ->
            when {
                row.stepKind == StepKind.SCRIPT && row.script == null -> row.copy(script = ScriptCall("script-hub"))
                row.authorAdded -> row
                else -> row.copy(expected = "text is visible")
            }
        }
        assertTrue(session.replaceSteps(fixed))
        assertNull(recordingApplyBlockedReason(session.snapshot.value))
    }

    @Test
    fun applyingTurnsTheRowsIntoTheRightKindsOfSteps() {
        val session = stopped()
        session.insertReviewedRow(0, RecordedTestStep(action = "Plug it in", stepKind = StepKind.MANUAL, checks = listOf(usb)))
        val hubCall = ScriptCall("script-hub", mapOf("port" to "2"), null)
        session.insertReviewedRow(1, RecordedTestStep(action = "Hub on", stepKind = StepKind.SCRIPT, script = hubCall))
        assertTrue(session.updateReviewedSteps(session.snapshot.value.steps.map { it.action to if (it.authorAdded) "" else "text is visible" }))

        val steps = session.toTestSteps()

        assertEquals(listOf(StepKind.MANUAL, StepKind.SCRIPT, StepKind.AGENT), steps.map { it.kind })
        assertEquals(DEFAULT_MANUAL_STEP_TIMEOUT_MS, steps[0].timeoutMs, "a manual step waits for a person")
        assertEquals(listOf(usb), steps[0].checks)
        assertEquals(ManualCompletion.TESTER_OR_CHECKS, steps[0].manualCompletion)
        assertEquals(ScriptCall("script-hub", mapOf("port" to "2"), null), steps[1].script)
        assertNull(steps[2].script)
    }

    @Test
    fun theApplyServicePassesTheKindsOnToTheLibrary() {
        val session = stopped()
        session.insertReviewedRow(0, RecordedTestStep(action = "Plug it in", stepKind = StepKind.MANUAL))
        assertTrue(session.updateReviewedSteps(session.snapshot.value.steps.map { it.action to if (it.authorAdded) "" else "text is visible" }))
        var inserted: List<TestStep> = emptyList()
        val service = TestStepRecordingApplyService(
            preflight = { _, _ -> StoreResult.Ok(Unit) },
            importImage = { _, _ -> error("No screenshot is expected") },
            resolveImage = { _, _ -> null },
            insertSteps = { _, steps, _ -> inserted = steps; StoreResult.Ok(steps) },
        )

        assertIs<StoreResult.Ok<*>>(runBlocking { service.apply(session, "suite-x", "case-x") })

        assertEquals(listOf(StepKind.MANUAL, StepKind.AGENT), inserted.map { it.kind })
    }

    @Test
    fun theRewriteBriefNamesWhoPerformsAKeptAuthorStep() {
        val manual = RecordedTestStep(action = "Plug it in", authorAdded = true, stepKind = StepKind.MANUAL)
        val plain = RecordedTestStep(action = "Sign in", authorAdded = true)

        val inputs = rewriteInputsOf(listOf(manual, plain)) { null }

        assertTrue(inputs[0].describe(RewriteDescription.BRIEF).contains("performed by: manual"), inputs[0].describe(RewriteDescription.BRIEF))
        assertFalse(inputs[1].describe(RewriteDescription.BRIEF).contains("performed by"))
    }

    @Test
    fun aScriptRowOnlyReachesTheLibraryWhenItsScriptIsThereAndMayBeStartedFromAStep() {
        val store = TestLibraryStore(tempTestingDir())
        val suite = (store.createSuite("S") as StoreResult.Ok).value
        val case = (store.createCase(suite.id, plainCase("C")) as StoreResult.Ok).value
        val script = (store.createScript(sampleScript("hub_on").copy(permission = ScriptPermission.AUTO, params = emptyList())) as StoreResult.Ok).value
        val session = stopped()
        session.insertReviewedRow(0, RecordedTestStep(action = "Hub", stepKind = StepKind.SCRIPT, script = ScriptCall("script-gone")))
        assertTrue(session.updateReviewedSteps(session.snapshot.value.steps.map { it.action to "ok" }))
        val service = TestStepRecordingApplyService(
            preflight = { _, _ -> StoreResult.Ok(Unit) },
            importImage = { _, _ -> error("No screenshot is expected") },
            resolveImage = { _, _ -> null },
            insertSteps = { caseId, steps, index -> store.createSteps(caseId, steps, index) },
        )

        val refused = runBlocking { service.apply(session, suite.id, case.id) }
        assertTrue(assertIs<StoreResult.Invalid>(refused).reason.contains("not in the library"), refused.toString())

        session.updateReviewedSteps(session.snapshot.value.steps.map { it.action to "ok" })
        val fixed = session.snapshot.value.steps.map { if (it.authorAdded) it.copy(script = ScriptCall(script.id)) else it }
        session.replaceSteps(fixed)
        assertIs<StoreResult.Ok<*>>(runBlocking { service.apply(session, suite.id, case.id) })
        assertEquals(listOf(StepKind.SCRIPT, StepKind.AGENT), store.library.value.findCase(case.id)?.case?.steps?.map { it.kind })
    }
}
