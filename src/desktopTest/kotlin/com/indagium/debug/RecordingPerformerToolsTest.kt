@file:Suppress("MagicNumber") // Fixture timestamps and counts, not tunable constants.

package com.indagium.debug

import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.testing.authoring.EnvironmentEvent
import com.indagium.testing.authoring.InputSource
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.touchCommand
import com.indagium.testing.model.ScriptParam
import com.indagium.testing.model.ScriptParamType
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestScript
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the recording tools show and change about who performs a row: stateChanges, external events, kind, script, completion, checks. */
class RecordingPerformerToolsTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private lateinit var operations: IndagiumToolOperations
    private lateinit var session: TestStepRecordingSession
    private var suiteId = ""
    private var caseId = ""
    private var hubId = ""

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("recording-performer").toFile()
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
        suiteId = (state.createTestSuite("Hub suite") as StoreResult.Ok).value.id
        caseId = (state.createTestCase(suiteId, TestCase("", "Plug in a stick")) as StoreResult.Ok).value.id
        hubId = (
            state.createTestScript(
                TestScript(
                    "", "usb_hub_on", params = listOf(ScriptParam("port", ScriptParamType.INT)), commandTemplate = "hub on \$port",
                    permission = ScriptPermission.AUTO,
                ),
            ) as StoreResult.Ok
        ).value.id
        operations = IndagiumToolOperations(state)
        session = recording()
    }

    @AfterTest
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    /** A tap that turned Wi-Fi off (row 1) and a USB stick that appeared on its own (row 2), stopped and registered. */
    private fun recording(): TestStepRecordingSession {
        val recorded = TestStepRecordingSession("fixture-device")
        recorded.accept(touchCommand(MirrorTouchAction.DOWN, 540, 1200), null, 960, InputSource.DEVICE)
        recorded.accept(touchCommand(MirrorTouchAction.UP, 540, 1200), null, 1_000, InputSource.DEVICE)
        recorded.environmentSink.environmentEvents(
            listOf(
                EnvironmentEvent("wifi.enabled", "true", "false", atMs = 1_900, sinceMs = 1_500),
                EnvironmentEvent("usb.devices", "", "0781:5581 Ultra", atMs = 9_000, sinceMs = 8_000),
            ),
        )
        runBlocking { recorded.stopAndDrain() }
        check(state.registerTestStepRecording(recorded, suiteId, caseId) is StoreResult.Ok)
        return recorded
    }

    @Suppress("UNCHECKED_CAST")
    private fun call(tool: String, vararg args: Pair<String, Any?>): Map<String, Any?> =
        runBlocking { operations.toolGateway.executeSuspending(tool, mapOf(*args)) as Map<String, Any?> }

    @Suppress("UNCHECKED_CAST")
    private fun rows(response: Map<String, Any?>) = response["steps"] as List<Map<String, Any?>>

    private fun edit(rows: List<Map<String, Any?>>, rowId: String, extra: Map<String, Any?>) = rows.map { row ->
        val base = mapOf("id" to row["id"], "action" to row["action"], "expected" to row["expected"])
        if (row["id"] == rowId) base + extra else base
    }

    private fun externalId() = session.snapshot.value.steps[1].id

    @Test
    fun theRecordingShowsTheEvidenceOnAnInputAndTheExternalEventAsAProposedManualStep() {
        val shown = rows(call("get_test_recording", "sessionId" to session.id))

        val tap = shown[0]

        @Suppress("UNCHECKED_CAST")
        val tapChanges = tap["stateChanges"] as List<Map<String, Any?>>
        assertEquals("Wi-Fi turned off", tapChanges.single()["text"])
        assertEquals("wifi.enabled", tapChanges.single()["key"])
        assertNull(tap["inputKind"])
        assertNull(tap["stepKind"])

        val external = shown[1]
        assertEquals("EXTERNAL", external["inputKind"])
        assertEquals("MANUAL", external["stepKind"])
        assertEquals("Connect the USB device 'Ultra' (0781:5581) to the phone", external["action"])
        assertEquals("TESTER_OR_CHECKS", external["manualCompletion"])
        assertNull(external["script"])

        @Suppress("UNCHECKED_CAST")
        val checks = external["stepChecks"] as List<Map<String, Any?>>
        val check = checks.single()
        assertEquals(listOf("deviceState", "usb.devices", "0781:5581"), listOf(check["type"], check["key"], check["expectedRegex"]))

        @Suppress("UNCHECKED_CAST")
        assertEquals("USB device 'Ultra' (0781:5581) connected", (external["stateChanges"] as List<Map<String, Any?>>).single()["text"])
    }

    @Test
    fun anExternalRowCanBeHandedToALibraryScriptOrKeepTheTesterAsAFallback() {
        val shown = rows(call("get_test_recording", "sessionId" to session.id))

        val scripted = call(
            "update_test_recording",
            "sessionId" to session.id,
            "steps" to edit(shown, externalId(), mapOf("kind" to "SCRIPT", "script" to mapOf("scriptId" to hubId, "args" to mapOf("port" to "2")))),
        )

        assertNull(scripted["error"], scripted.toString())
        val row = rows(scripted)[1]
        assertEquals("SCRIPT", row["stepKind"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(hubId, (row["script"] as Map<String, Any?>)["scriptId"])
        assertEquals(listOf(StepKind.AGENT, StepKind.SCRIPT), session.snapshot.value.steps.map { it.stepKind })

        val fallback = call(
            "update_test_recording",
            "sessionId" to session.id,
            "steps" to edit(
                rows(scripted), externalId(),
                mapOf(
                    "kind" to "MANUAL", "manualCompletion" to "CHECKS_ONLY",
                    "checks" to listOf(mapOf("type" to "deviceState", "key" to "usb.devices", "expectedRegex" to "Ultra", "withinMs" to 30_000)),
                ),
            ),
        )

        assertNull(fallback["error"], fallback.toString())
        val manual = session.snapshot.value.steps[1]
        assertEquals(StepKind.MANUAL, manual.stepKind)
        assertEquals(hubId, manual.script?.scriptId, "the script stays as the automation alternative")
        assertEquals(listOf(StepCheck.DeviceState(manual.checks.single().id, "usb.devices", "Ultra", 30_000L)), manual.checks)
        assertEquals("CHECKS_ONLY", rows(fallback)[1]["manualCompletion"])
    }

    @Test
    fun theEditsAreRefusedForWhatCannotBeReassignedOrDoesNotValidate() {
        val shown = rows(call("get_test_recording", "sessionId" to session.id))
        val tapId = session.snapshot.value.steps[0].id

        fun refused(rowId: String, extra: Map<String, Any?>, vararg parts: String) {
            val result = call("update_test_recording", "sessionId" to session.id, "steps" to edit(shown, rowId, extra))
            parts.forEach { assertTrue(it in result["error"].toString(), "'$it' missing from ${result["error"]}") }
            assertEquals(listOf(StepKind.AGENT, StepKind.MANUAL), session.snapshot.value.steps.map { it.stepKind }, "nothing changed")
        }
        refused(tapId, mapOf("kind" to "MANUAL"), "steps[0]", "only a row you added")
        refused(externalId(), mapOf("kind" to "AGENT"), "cannot be an AI step")
        refused(externalId(), mapOf("kind" to "SCRIPT"), "needs a script")
        refused(externalId(), mapOf("kind" to "SCRIPT", "script" to mapOf("scriptId" to "script-nope")), "not in the library")
        refused(externalId(), mapOf("kind" to "SCRIPT", "script" to mapOf("scriptId" to hubId)), "usb_hub_on")
        refused(externalId(), mapOf("kind" to "MANUAL", "manualCompletion" to "CHECKS_ONLY", "checks" to emptyList<Any>()), "at least one")
        refused(externalId(), mapOf("kind" to "ROBOT"), "kind must be one of")

        val noId = call("update_test_recording", "sessionId" to session.id, "steps" to shownWithoutIds(shown, mapOf("kind" to "MANUAL")))
        assertTrue("steps[1].id is required" in noId["error"].toString() || "steps[0].id is required" in noId["error"].toString(), noId.toString())
    }

    private fun shownWithoutIds(shown: List<Map<String, Any?>>, extra: Map<String, Any?>) =
        shown.map { mapOf("action" to it["action"], "expected" to it["expected"]) + extra }

    @Test
    fun aRowYouInsertCanBeAManualOrScriptStepAndIsValidatedLikeAStepYouCreate() {
        val manual = call(
            "insert_test_recording_row",
            "sessionId" to session.id, "index" to 0, "action" to "Plug the hub in", "kind" to "MANUAL",
            "checks" to listOf(mapOf("type" to "deviceState", "key" to "usb.devices", "expectedRegex" to "Ultra")),
        )
        assertNull(manual["error"], manual.toString())
        val first = rows(manual)[0]
        assertEquals(true, first["authorAdded"])
        assertEquals("MANUAL", first["stepKind"])
        assertEquals(1, (first["stepChecks"] as List<*>).size)

        val scripted = call(
            "insert_test_recording_row",
            "sessionId" to session.id, "index" to 1, "action" to "Hub on", "kind" to "SCRIPT",
            "script" to mapOf("scriptId" to hubId, "args" to mapOf("port" to "1")),
        )
        assertNull(scripted["error"], scripted.toString())
        assertEquals("SCRIPT", rows(scripted)[1]["stepKind"])

        val noScript = call("insert_test_recording_row", "sessionId" to session.id, "index" to 0, "action" to "Hub on", "kind" to "SCRIPT")
        assertTrue("needs a script" in noScript["error"].toString(), noScript.toString())
        val badScript = call(
            "insert_test_recording_row",
            "sessionId" to session.id, "index" to 0, "action" to "Hub on", "kind" to "SCRIPT", "script" to mapOf("scriptId" to "script-nope"),
        )
        assertTrue("not in the library" in badScript["error"].toString(), badScript.toString())
        assertEquals(4, session.snapshot.value.steps.size)
    }

    @Test
    fun applyingKeepsTheKindsSoAnExternalEventReachesTheCaseAsAScriptStep() {
        val shown = rows(call("get_test_recording", "sessionId" to session.id))
        call(
            "update_test_recording",
            "sessionId" to session.id,
            "steps" to edit(shown, externalId(), mapOf("kind" to "SCRIPT", "script" to mapOf("scriptId" to hubId, "args" to mapOf("port" to "2")))),
        )
        val reviewed = rows(call("get_test_recording", "sessionId" to session.id))

        val applied = call(
            "apply_test_recording",
            "sessionId" to session.id,
            "steps" to reviewed.map { mapOf("id" to it["id"], "action" to it["action"], "expected" to (it["expected"] as String).ifBlank { "Wi-Fi is off" }) },
        )

        assertNull(applied["error"], applied.toString())
        val steps = state.testLibrary.findCase(caseId)!!.case.steps
        assertEquals(listOf(StepKind.AGENT, StepKind.SCRIPT), steps.map { it.kind })
        assertEquals(hubId, steps[1].script?.scriptId)
        assertEquals("usb.devices", (steps[1].checks.single() as StepCheck.DeviceState).key, "the check verifies the script's effect")
    }
}
