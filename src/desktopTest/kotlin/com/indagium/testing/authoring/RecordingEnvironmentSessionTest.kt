@file:Suppress("MagicNumber") // Fixture timestamps and counts, not tunable constants.

package com.indagium.testing.authoring

import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.testing.model.DEFAULT_DEVICE_STATE_WITHIN_MS
import com.indagium.testing.model.DEFAULT_MANUAL_STEP_TIMEOUT_MS
import com.indagium.testing.model.ManualCompletion
import com.indagium.testing.model.ScriptCall
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.StepKind
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class CountingCloseable : Closeable {
    val closes = AtomicInteger()

    override fun close() {
        closes.incrementAndGet()
    }
}

/** How the recording session turns the environment watcher's findings into evidence on an input or a row of their own. */
class RecordingEnvironmentSessionTest {
    private val sessions = mutableListOf<TestStepRecordingSession>()

    @AfterTest
    fun closeSessions() = sessions.forEach(TestStepRecordingSession::close)

    private fun session() = TestStepRecordingSession("device-1").also { sessions += it }

    private fun TestStepRecordingSession.tapAt(upMs: Long) {
        accept(touchCommand(MirrorTouchAction.DOWN, 540, 1200), null, upMs - 40, InputSource.DEVICE)
        accept(touchCommand(MirrorTouchAction.UP, 540, 1200), null, upMs, InputSource.DEVICE)
    }

    private fun TestStepRecordingSession.report(vararg events: EnvironmentEvent) = environmentSink.environmentEvents(events.toList())

    private val wifiOff = EnvironmentEvent("wifi.enabled", "true", "false", atMs = 2_500, sinceMs = 2_000)
    private val usbIn = EnvironmentEvent("usb.devices", "", "0781:5581 Ultra", atMs = 9_000, sinceMs = 8_000)

    // ── Attribution ──────────────────────────────────────────────────

    @Test
    fun aChangeSoonAfterAnInputIsThatInputsEvidenceNotARow() {
        val session = session()
        session.tapAt(1_000)

        session.report(wifiOff)

        val rows = session.snapshot.value.steps
        assertEquals(1, rows.size)
        assertEquals(listOf(wifiOff), rows.single().stateChanges)
        assertEquals(RecordedInputKind.TAP, rows.single().kind)
    }

    @Test
    fun aChangeNoInputExplainsBecomesAnExternalRowProposedAsAManualStep() {
        val session = session()
        session.tapAt(1_000)

        session.report(usbIn)

        val rows = session.snapshot.value.steps
        assertEquals(2, rows.size)
        assertTrue(rows[0].stateChanges.isEmpty(), "the tap did not cause it")
        val external = rows[1]
        assertEquals(RecordedInputKind.EXTERNAL, external.kind)
        assertEquals(StepKind.MANUAL, external.stepKind)
        assertEquals("Connect the USB device 'Ultra' (0781:5581) to the phone", external.action)
        assertEquals(listOf(StepCheck.DeviceState(external.checks.single().id, "usb.devices", "0781:5581", DEFAULT_MANUAL_STEP_TIMEOUT_MS)), external.checks)
        assertEquals(listOf(usbIn), external.stateChanges)
        assertEquals(ManualCompletion.TESTER_OR_CHECKS, external.manualCompletion)
        assertNull(external.script)
        assertTrue("USB device 'Ultra' (0781:5581) connected" in external.screenContext.orEmpty(), external.screenContext)
        assertEquals(9_000L, external.inputAtMs)
        assertTrue(external.performerEditable && external.isExternalEvent && !external.authorAdded)
    }

    @Test
    fun theThreeSecondWindowIsMeasuredFromTheInputToWhenTheChangeCouldHaveHappened() {
        val inside = session().also { it.tapAt(1_000) }
        inside.report(EnvironmentEvent("airplane", "false", "true", atMs = 5_000, sinceMs = 3_999))
        assertEquals(1, inside.snapshot.value.steps.size, "the change may have happened 2.999 s after the tap")

        val outside = session().also { it.tapAt(1_000) }
        outside.report(EnvironmentEvent("airplane", "false", "true", atMs = 6_000, sinceMs = 4_001))
        assertEquals(2, outside.snapshot.value.steps.size, "it cannot have happened within 3 s of the tap")

        val before = session().also { it.tapAt(1_000) }
        before.report(EnvironmentEvent("airplane", "false", "true", atMs = 500, sinceMs = 100))
        assertEquals(2, before.snapshot.value.steps.size, "a change before an input is never that input's")
    }

    @Test
    fun theLatestInputBeforeTheChangeGetsTheEvidence() {
        val session = session()
        session.tapAt(1_000)
        session.tapAt(2_000)

        session.report(wifiOff)

        assertEquals(emptyList(), session.snapshot.value.steps[0].stateChanges)
        assertEquals(listOf(wifiOff), session.snapshot.value.steps[1].stateChanges)
    }

    @Test
    fun aChangeThatAddedARowOfItsOwnIsNeverAttachedToAnExternalRow() {
        val session = session()
        session.report(usbIn)
        session.report(wifiOff.copy(atMs = 9_500, sinceMs = 9_400))

        val rows = session.snapshot.value.steps
        assertEquals(listOf(RecordedInputKind.EXTERNAL, RecordedInputKind.EXTERNAL), rows.map { it.kind })
        assertEquals(1, rows[0].stateChanges.size)
    }

    @Test
    fun theBatteryStatusIsEvidenceOnlyAndNeverToldTwiceWithThePowerSource() {
        val session = session()
        val unplugged = EnvironmentEvent("power.source", "ac", "none", atMs = 9_000, sinceMs = 8_000)
        val discharging = EnvironmentEvent("battery.status", "charging", "discharging", atMs = 9_000, sinceMs = 8_000)
        session.report(unplugged, discharging)
        assertEquals(1, session.snapshot.value.steps.size)
        assertEquals("Disconnect the charger from the phone", session.snapshot.value.steps.single().action)

        val alone = session()
        alone.report(discharging)
        assertEquals(0, alone.snapshot.value.steps.size, "a battery status alone is not worth a step")

        val afterTap = session().also { it.tapAt(8_900) }
        afterTap.report(discharging)
        assertEquals(listOf(discharging), afterTap.snapshot.value.steps.single().stateChanges)
    }

    @Test
    fun tooManyChangesStopBeingRecordedWithOneWarning() {
        val session = session()
        repeat(70) { index -> session.report(EnvironmentEvent("wifi.enabled", "true", "false", atMs = 10_000L + index * 100, sinceMs = 0)) }

        assertEquals(60, session.snapshot.value.steps.size)
        assertEquals(1, session.snapshot.value.warnings.count { "changes of the device's surroundings" in it })
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    @Test
    fun stoppingClosesTheWatcherAndNothingIsReportedAfterwards() {
        val session = session()
        val watcher = CountingCloseable()
        session.attachEnvironmentWatcher(watcher)
        session.tapAt(1_000)

        session.stop()
        session.report(usbIn)
        session.environmentSink.environmentWarning("late")
        session.close()

        assertEquals(1, watcher.closes.get())
        assertEquals(1, session.snapshot.value.steps.size)
        assertFalse("late" in session.snapshot.value.warnings)
    }

    @Test
    fun aWatcherAttachedToAStoppedOrAlreadyWatchedSessionIsClosedAtOnce() {
        val session = session()
        val first = CountingCloseable()
        val second = CountingCloseable()
        session.attachEnvironmentWatcher(first)
        session.attachEnvironmentWatcher(second)
        assertEquals(0, first.closes.get())
        assertEquals(1, second.closes.get())

        session.stop()
        val late = CountingCloseable()
        session.attachEnvironmentWatcher(late)
        assertEquals(1, late.closes.get())
    }

    @Test
    fun theWatchersWarningsReachTheRecording() {
        val session = session()

        session.environmentSink.environmentWarning("These device states could not be read, so changes to them are not recorded: audio.devices.")

        assertTrue(session.snapshot.value.warnings.single().contains("audio.devices"))
    }

    // ── From row to step ─────────────────────────────────────────────

    @Test
    fun anExternalRowWaitsForATesterAndTheCheckNoticesItDone() {
        val session = session()
        session.report(usbIn)

        val step = session.toTestSteps().single()

        assertEquals(StepKind.MANUAL, step.kind)
        assertEquals("Connect the USB device 'Ultra' (0781:5581) to the phone", step.action)
        assertEquals(DEFAULT_MANUAL_STEP_TIMEOUT_MS, step.timeoutMs)
        assertEquals("usb.devices", (step.checks.single() as StepCheck.DeviceState).key)
        assertNull(step.script)
        assertTrue(step.examples.isEmpty(), "nobody reads examples of a manual step")
    }

    @Test
    fun aScriptChoiceMakesItAScriptStepOrAManualOneWithAnAutomationAlternative() {
        val row = externalEventRow(usbIn)
        val call = ScriptCall("script-hub", mapOf("port" to "2"), null)
        val session = session()
        session.report(usbIn)

        val scripted = row.runScript(call, keepManualFallback = false)
        assertEquals(StepKind.SCRIPT, scripted.stepKind)
        assertEquals(call, scripted.script)
        assertEquals(DEFAULT_DEVICE_STATE_WITHIN_MS, (scripted.checks.single() as StepCheck.DeviceState).withinMs, "a script is verified soon after it ran")
        val scriptStep = session.toTestSteps(listOf(scripted)).single()
        assertEquals(StepKind.SCRIPT, scriptStep.kind)
        assertEquals(call, scriptStep.script)

        val fallback = row.runScript(call, keepManualFallback = true)
        assertEquals(StepKind.MANUAL, fallback.stepKind)
        assertEquals(DEFAULT_MANUAL_STEP_TIMEOUT_MS, (fallback.checks.single() as StepCheck.DeviceState).withinMs)
        val manualStep = session.toTestSteps(listOf(fallback)).single()
        assertEquals(StepKind.MANUAL, manualStep.kind)
        assertEquals(call, manualStep.script, "the script is the automation alternative")

        val back = scripted.waitForTester()
        assertEquals(StepKind.MANUAL, back.stepKind)
        assertNull(back.script)
        assertEquals(DEFAULT_MANUAL_STEP_TIMEOUT_MS, (back.checks.single() as StepCheck.DeviceState).withinMs)
    }

    @Test
    fun aWindowThePersonSetOnTheCheckIsLeftAlone() {
        val tuned = externalEventRow(usbIn).let { it.copy(checks = it.checks.map { check -> (check as StepCheck.DeviceState).copy(withinMs = 45_000L) }) }

        assertEquals(45_000L, (tuned.runScript(ScriptCall("s"), false).checks.single() as StepCheck.DeviceState).withinMs)
        assertEquals(45_000L, (tuned.waitForTester().checks.single() as StepCheck.DeviceState).withinMs)
    }

    @Test
    fun whatFollowedAnAiStepTravelsWithItAsAReferenceForTheAgentThatRunsIt() {
        val session = session()
        session.tapAt(1_000)
        session.report(wifiOff)

        val step = session.toTestSteps().single()

        val example = step.examples.filterIsInstance<StepExample.ReferenceLog>().single { "Observed on the device" in it.caption }
        assertTrue("Wi-Fi turned off" in example.text, example.text)
    }

    // ── Who performs a row ───────────────────────────────────────────

    private fun stoppedWithExternalRow(): TestStepRecordingSession {
        val session = session()
        session.tapAt(1_000)
        session.report(usbIn)
        session.stop()
        return session
    }

    private fun TestStepRecordingSession.edit(vararg edits: Pair<Int, RecordedPerformerEdit>) = snapshot.value.steps.let { rows ->
        updateReviewedSteps(
            rows.map { it.action to it.expected },
            performerEdits = edits.associate { (index, edit) -> rows[index].id to edit },
        )
    }

    @Test
    fun theReviewCanHandAnExternalRowToAScriptButNeverToTheAiAgent() {
        val session = stoppedWithExternalRow()
        val call = ScriptCall("script-hub")

        assertTrue(session.edit(1 to RecordedPerformerEdit(stepKind = StepKind.SCRIPT, setScript = true, script = call)))
        assertEquals(StepKind.SCRIPT, session.snapshot.value.steps[1].stepKind)
        assertEquals(call, session.snapshot.value.steps[1].script)

        assertFalse(session.edit(1 to RecordedPerformerEdit(stepKind = StepKind.AGENT)), "nobody can ask an agent to plug a cable in")
        assertEquals(StepKind.SCRIPT, session.snapshot.value.steps[1].stepKind)
    }

    @Test
    fun aRecordedInputStaysAnAiStep() {
        val session = stoppedWithExternalRow()

        assertFalse(session.edit(0 to RecordedPerformerEdit(stepKind = StepKind.MANUAL)))
        assertEquals(StepKind.AGENT, session.snapshot.value.steps[0].stepKind)
    }

    @Test
    fun aScriptRowWithoutItsScriptCannotBeSavedByTheReview() {
        val session = stoppedWithExternalRow()

        assertFalse(session.edit(1 to RecordedPerformerEdit(stepKind = StepKind.SCRIPT)), "a script step needs its script")
        assertEquals(StepKind.MANUAL, session.snapshot.value.steps[1].stepKind)
    }

    @Test
    fun anExternalRowMovesLikeAnyOtherRowAndKeepsItsEvent() {
        val session = stoppedWithExternalRow()
        val externalId = session.snapshot.value.steps[1].id

        assertTrue(session.moveReviewedRow(externalId, 0))

        assertEquals(externalId, session.snapshot.value.steps[0].id)
        assertEquals(listOf(usbIn), session.snapshot.value.steps[0].stateChanges)
    }

    @Test
    fun theKindListsAreAppendOnlyAtTheEnd() {
        assertEquals(RecordedInputKind.EXTERNAL, RecordedInputKind.entries.last())
        assertEquals("SCREEN_ON", RecordedInputKind.entries[RecordedInputKind.entries.size - 2].name)
    }
}
