package com.indagium.debug

import com.indagium.testing.FIXTURE_SERIAL
import com.indagium.testing.ScriptedAdbRunner
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestScript
import com.indagium.testing.openFixtureSession
import com.indagium.testing.run.EngineTuning
import com.indagium.testing.run.LaneDeviceOpener
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import com.indagium.ui.TestRunOverrides
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val AWAIT_MS = 15_000L
private const val POLL_MS = 15L

/** The authoring and run tools for manual and script steps, over the gateway. */
class StepKindToolsGatewayTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private lateinit var operations: IndagiumToolOperations
    private val adb = ScriptedAdbRunner()

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("step-kind-tools").toFile()
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
        state.testRunOverrides = TestRunOverrides(
            openDevice = LaneDeviceOpener { _, laneDir, _ -> openFixtureSession(adb, laneDir) },
            deviceProblem = { serial -> if (serial == FIXTURE_SERIAL) null else "Device $serial is not connected." },
            tuning = EngineTuning(persistDebounceMs = 50L, manualPollMs = 30L),
        )
        operations = IndagiumToolOperations(state)
    }

    @AfterTest
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun call(tool: String, vararg args: Pair<String, Any?>): Map<String, Any?> = runBlocking {
        val wire = Json.decode(Json.encode(mapOf(*args))) as Map<String, Any?>
        Json.decode(Json.encode(operations.toolGateway.executeSuspending(tool, wire))) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.list(key: String): List<Map<String, Any?>> = this[key] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.map(key: String): Map<String, Any?> = this[key] as Map<String, Any?>

    private fun newCase(): String {
        val suite = (state.createTestSuite("Kinds") as StoreResult.Ok).value
        return (state.createTestCase(suite.id, TestCase("", "Hub")) as StoreResult.Ok).value.id
    }

    private fun newScript(toolName: String = "hub_on", permission: ScriptPermission = ScriptPermission.AUTO): TestScript =
        (state.createTestScript(TestScript("", toolName, commandTemplate = "true", permission = permission)) as StoreResult.Ok).value

    private val usbCheck = mapOf("type" to "deviceState", "key" to "usb.devices", "expectedRegex" to "0781:5581", "withinMs" to 3000)

    @Test
    fun createTestStepAcceptsKindScriptManualCompletionAndDeviceStateChecks() {
        val caseId = newCase()
        val script = newScript()

        val scripted = call(
            "create_test_step", "caseId" to caseId, "action" to "Power the hub on", "kind" to "SCRIPT",
            "script" to mapOf("scriptId" to script.id, "args" to emptyMap<String, String>(), "exitCode" to null, "stdoutContains" to "on"),
        )
        assertEquals(null, scripted["error"], scripted.toString())
        assertEquals("SCRIPT", scripted["kind"])
        assertEquals(script.id, scripted.map("script")["scriptId"])
        assertNull(scripted.map("script")["exitCode"], "an explicit null exit code means any")

        val manual = call(
            "create_test_step", "caseId" to caseId, "action" to "Plug the USB stick into the phone", "kind" to "manual",
            "manualCompletion" to "checks_only", "checks" to listOf(usbCheck), "script" to mapOf("scriptId" to script.id),
        )
        assertEquals(null, manual["error"], manual.toString())
        assertEquals("MANUAL", manual["kind"])
        assertEquals("CHECKS_ONLY", manual["manualCompletion"])
        assertEquals("600000", manual["timeoutMs"].toString(), "a manual step waits for a person: 10 minutes by default")
        assertEquals("deviceState", manual.list("checks").single()["type"])
        assertEquals("usb.devices", manual.list("checks").single()["key"])

        val ai = call("create_test_step", "caseId" to caseId, "action" to "Tap")
        assertFalse(ai.containsKey("kind"), "an AI step is returned exactly as before: $ai")
    }

    @Test
    fun badKindsScriptsAndChecksAreRefusedWithAMessage() {
        val caseId = newCase()
        val script = newScript()
        val setupOnly = newScript("only_setup", ScriptPermission.SETUP_TEARDOWN_ONLY)

        fun error(vararg args: Pair<String, Any?>) = call("create_test_step", "caseId" to caseId, *args)["error"].toString()

        assertTrue(error("action" to "x", "kind" to "ROBOT").contains("kind must be one of"))
        assertTrue(error("action" to "x", "kind" to "SCRIPT").contains("needs a script"))
        assertTrue(error("action" to "x", "kind" to "SCRIPT", "script" to mapOf("scriptId" to "script-gone")).contains("not in the library"))
        assertTrue(error("action" to "x", "kind" to "SCRIPT", "script" to mapOf("scriptId" to setupOnly.id)).contains("setup and teardown"))
        assertTrue(error("action" to "x", "kind" to "SCRIPT", "script" to mapOf("scriptId" to script.id, "bogus" to 1)).contains("unknown field"))
        val unknownArgument = mapOf("scriptId" to script.id, "args" to mapOf("nope" to "1"))
        assertTrue(error("action" to "x", "kind" to "SCRIPT", "script" to unknownArgument).contains("Unknown argument"))
        assertTrue(error("action" to "x", "script" to mapOf("scriptId" to script.id)).contains("Only manual and script steps"))
        assertTrue(error("action" to "x", "kind" to "MANUAL", "manualCompletion" to "SOMEDAY").contains("manualCompletion must be one of"))
        assertTrue(error("action" to "x", "kind" to "MANUAL", "manualCompletion" to "CHECKS_ONLY").contains("at least one log-appears or device-state check"))
        assertTrue(error("action" to "x", "checks" to listOf(mapOf("type" to "deviceState", "key" to "usb.devices"))).contains("expectedRegex"))
        val badRegex = mapOf("type" to "deviceState", "key" to "usb.devices", "expectedRegex" to "(")
        assertTrue(error("action" to "x", "checks" to listOf(badRegex)).contains("regular expression"))
    }

    @Test
    fun updateTestStepChangesTheKindMovesTheDefaultTimeoutAndClearsTheScript() {
        val caseId = newCase()
        val script = newScript()
        val created = call("create_test_step", "caseId" to caseId, "action" to "Hub", "kind" to "SCRIPT", "script" to mapOf("scriptId" to script.id))
        val stepId = created["id"] as String

        val manual = call("update_test_step", "stepId" to stepId, "kind" to "MANUAL")
        assertEquals(null, manual["error"], manual.toString())
        assertEquals("MANUAL", manual["kind"])
        assertEquals("600000", manual["timeoutMs"].toString())
        assertNotNull(manual["script"], "the script stays as the manual step's automation alternative")

        val cleared = call("update_test_step", "stepId" to stepId, "script" to null)
        assertEquals(null, cleared["error"], cleared.toString())
        assertFalse(cleared.containsKey("script"))

        val ai = call("update_test_step", "stepId" to stepId, "kind" to "AGENT")
        assertEquals("60000", ai["timeoutMs"].toString())
        assertFalse(ai.containsKey("kind"))
    }

    private fun startExternal(suiteId: String, vararg extra: Pair<String, Any?>): Map<String, Any?> = call(
        "run_test_suite", "suiteId" to suiteId, "lanes" to listOf(mapOf("profileId" to "external", "deviceSerial" to FIXTURE_SERIAL)), *extra,
    )

    private fun awaitStatus(runId: String, vararg wanted: String): Map<String, Any?> = runBlocking {
        withTimeout(AWAIT_MS) {
            while (true) {
                val status = call("get_test_run_status", "runId" to runId)
                if (status["status"] in wanted) return@withTimeout status
                delay(POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            emptyMap()
        }
    }

    private fun awaitWaiting(runId: String): Map<String, Any?> = runBlocking {
        withTimeout(AWAIT_MS) {
            while (true) {
                val waiting = call("get_test_run_status", "runId" to runId).list("waitingLanes")
                if (waiting.isNotEmpty()) return@withTimeout waiting.single()
                delay(POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            emptyMap()
        }
    }

    private fun suiteWithManualStep(completion: String = "TESTER_ONLY"): String {
        val caseId = newCase()
        val created = call(
            "create_test_step", "caseId" to caseId, "action" to "Plug the USB stick into the phone", "kind" to "MANUAL", "manualCompletion" to completion,
        )
        assertEquals(null, created["error"], created.toString())
        return state.testLibrary.findCase(caseId)!!.suite.id
    }

    @Test
    fun aWaitingLaneIsListedInTheStatusAndResolveManualStepAnswersIt() {
        val started = startExternal(suiteWithManualStep())
        assertEquals(null, started["error"], started.toString())
        val runId = started["runId"] as String
        val laneId = (started["laneIds"] as List<*>).single() as String

        val waiting = awaitWaiting(runId)
        assertEquals(laneId, waiting["laneId"])
        assertEquals("Plug the USB stick into the phone", waiting["instructions"])
        assertEquals(FIXTURE_SERIAL, waiting["deviceSerial"])
        assertEquals("TESTER_ONLY", waiting["completion"])
        assertEquals(true, waiting["testerCanComplete"])

        assertTrue(call("resolve_manual_step", "runId" to runId, "laneId" to laneId, "decision" to "maybe")["error"].toString().contains("decision"))
        assertTrue(call("resolve_manual_step", "runId" to runId, "laneId" to "lane-nope", "decision" to "done")["error"].toString().contains("no lane"))
        val answered = call("resolve_manual_step", "runId" to runId, "laneId" to laneId, "decision" to "done", "note" to "plugged in")
        assertEquals(null, answered["error"], answered.toString())
        assertEquals("done", answered["decision"])

        val status = awaitStatus(runId, "PASSED", "FAILED", "ERROR")
        assertEquals("PASSED", status["status"], status.toString())
        assertTrue(status.list("waitingLanes").isEmpty())
        assertTrue(call("resolve_manual_step", "runId" to runId, "laneId" to laneId, "decision" to "done")["error"].toString().contains("not waiting"))

        @Suppress("UNCHECKED_CAST")
        val report = call("get_test_run_report", "runId" to runId)["report"] as Map<String, Any?>
        val step = (report.list("lanes").single().list("cases").single()["steps"] as List<Map<String, Any?>>).single()
        assertEquals("MANUAL", step["kind"])
        assertEquals("TESTER", step["completedBy"])
        assertEquals("TESTER_ONLY", step["manualCompletion"])
        val markdown = call("get_test_run_report", "runId" to runId, "format" to "markdown")["markdown"] as String
        assertTrue(markdown.contains("Manual step · confirmed by the tester · completion: tester only"), markdown)
    }

    @Test
    fun aStepCompletedByItsChecksCannotBeAnsweredDoneOverMcp() {
        val caseId = newCase()
        call(
            "create_test_step", "caseId" to caseId, "action" to "Plug it in", "kind" to "MANUAL", "manualCompletion" to "CHECKS_ONLY",
            "checks" to listOf(usbCheck),
        )
        val started = startExternal(state.testLibrary.findCase(caseId)!!.suite.id)
        val runId = started["runId"] as String
        val laneId = (started["laneIds"] as List<*>).single() as String
        awaitWaiting(runId)

        val refused = call("resolve_manual_step", "runId" to runId, "laneId" to laneId, "decision" to "done")
        assertTrue(refused["error"].toString().contains("checks only"), refused.toString())
        assertEquals(null, call("resolve_manual_step", "runId" to runId, "laneId" to laneId, "decision" to "skip")["error"])
        assertEquals("PASSED", awaitStatus(runId, "PASSED", "FAILED", "ERROR")["status"], "a skipped step does not fail the run")
    }

    @Test
    fun theManualStepsRunOptionIsValidatedAndSkipDoesNotWait() {
        val suiteId = suiteWithManualStep()

        assertTrue(startExternal(suiteId, "manualSteps" to "sometimes")["error"].toString().contains("manualSteps must be one of"))
        val started = startExternal(suiteId, "manualSteps" to "skip")
        assertEquals(null, started["error"], started.toString())
        assertTrue((started["warnings"] as List<*>).any { it.toString().contains("will be skipped") })
        val status = awaitStatus(started["runId"] as String, "PASSED", "FAILED", "ERROR")
        assertEquals(listOf("SKIPPED"), status.list("lanes").single().list("cases").single()["steps"])
    }

    @Test
    fun answeringAManualStepNeedsNoPerCallApprovalLikeResumingAPausedStep() {
        assertFalse("resolve_manual_step" in PER_CALL_APPROVAL_MCP_TOOLS)
        assertFalse("resume_paused_step" in PER_CALL_APPROVAL_MCP_TOOLS)
        assertEquals(
            operations.toolGateway.actionPolicy("resume_paused_step"),
            operations.toolGateway.actionPolicy("resolve_manual_step"),
        )
    }
}
