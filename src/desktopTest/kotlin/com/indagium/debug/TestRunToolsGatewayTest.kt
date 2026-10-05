package com.indagium.debug

import com.indagium.edition.Edition
import com.indagium.testing.FIXTURE_SERIAL
import com.indagium.testing.ScriptedAdbRunner
import com.indagium.testing.logRow
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.newCheckId
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
import kotlin.test.assertTrue

private const val AWAIT_MS = 15_000L
private const val POLL_MS = 15L

/** The run tools over the gateway: a whole run driven as an EXTERNAL lane through test_lane_tool_call, then read back as a report. */
class TestRunToolsGatewayTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private lateinit var operations: IndagiumToolOperations
    private val adb = ScriptedAdbRunner()

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("test-run-tools").toFile()
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
            tuning = EngineTuning(persistDebounceMs = 50L),
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

    private fun lane(runId: String, laneId: String, tool: String, vararg args: Pair<String, Any?>) =
        call("test_lane_tool_call", "runId" to runId, "laneId" to laneId, "tool" to tool, "arguments" to mapOf(*args))

    private fun createSuite(vararg stepActions: String, checks: List<StepCheck> = emptyList(), onFailure: OnFailure = OnFailure.STOP_CASE): String {
        val suite = (state.createTestSuite("External suite") as StoreResult.Ok).value
        state.updateTestSuite(suite.id) { it.copy(targetPackage = "com.example.app") }
        val case = (state.createTestCase(suite.id, TestCase("", "Sign in")) as StoreResult.Ok).value
        stepActions.forEachIndexed { index, action ->
            state.createTestStep(case.id, TestStep("", action, "It works", if (index == 0) checks else emptyList(), onFailure = onFailure, retries = 0))
        }
        return suite.id
    }

    private fun startExternal(suiteId: String): Pair<String, String> {
        val started = call("run_test_suite", "suiteId" to suiteId, "lanes" to listOf(mapOf("profileId" to "external", "deviceSerial" to FIXTURE_SERIAL)))
        assertEquals(null, started["error"], started.toString())
        return started["runId"] as String to (started["laneIds"] as List<*>).single() as String
    }

    private fun awaitStep(runId: String, laneId: String): Map<String, Any?> = runBlocking {
        withTimeout(AWAIT_MS) {
            while (true) {
                val step = lane(runId, laneId, "get_current_step")
                if (step["action"] != null) return@withTimeout step
                delay(POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            emptyMap()
        }
    }

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

    @Test
    fun anExternalLaneIsDrivenEntirelyThroughTestLaneToolCallAndTheReportHasTheResults() {
        val suiteId = createSuite("Open the app", "Sign in", checks = listOf(StepCheck.LogAppears(newCheckId(), null, "Login ok", 3_000L)))
        val (runId, laneId) = startExternal(suiteId)

        val first = awaitStep(runId, laneId)
        assertEquals("Open the app", first["action"])
        assertEquals(1, first["stepNumber"])
        assertEquals(2, first["stepCount"])
        assertNotNull(lane(runId, laneId, "take_screenshot")["imageBase64"])
        assertEquals(true, lane(runId, laneId, "tap", "x" to 10, "y" to 10)["ok"])
        adb.logcat.emit(logRow("Login ok"))
        val waited = lane(runId, laneId, "wait_for_log", "regex" to "Login ok", "timeoutMs" to 3_000)
        assertEquals(true, waited["matched"], waited.toString())
        assertEquals(null, lane(runId, laneId, "report_observation", "text" to "The app opened")["error"])
        val finished = lane(runId, laneId, "finish_step", "status" to "pass", "observation" to "The login screen is visible")
        assertEquals("next_step", finished["result"], finished.toString())
        assertEquals("Sign in", (finished["step"] as Map<*, *>)["action"])

        val last = lane(runId, laneId, "finish_step", "status" to "pass", "observation" to "Signed in")
        assertEquals("case_finished", last["result"], last.toString())

        val status = awaitStatus(runId, "PASSED", "FAILED", "ERROR")
        assertEquals("PASSED", status["status"], status.toString())
        val laneStatus = status.list("lanes").single()
        assertEquals("EXTERNAL", laneStatus["kind"])
        assertEquals("PASSED", laneStatus["status"])
        assertEquals(listOf("PASS", "PASS"), laneStatus.list("cases").single()["steps"])

        val report = call("get_test_run_report", "runId" to runId)

        @Suppress("UNCHECKED_CAST")
        val steps = ((((report["report"] as Map<String, Any?>).list("lanes").single()).list("cases").single())["steps"]) as List<Map<String, Any?>>
        assertEquals(listOf("PASS", "PASS"), steps.map { it["status"] })
        assertEquals("The login screen is visible", steps.first()["observation"].toString().lines().last())
        assertEquals("PASS", steps.first().list("checks").single()["status"])
        assertNotNull(steps.first()["screenshotPath"])
        assertFalse((report["report"] as Map<*, *>).containsKey("suite"), "the report omits the frozen suite")

        val markdown = call("get_test_run_report", "runId" to runId, "format" to "markdown")["markdown"] as String
        assertTrue(markdown.contains("Open the app") && markdown.contains("(untrusted)"), markdown)

        assertTrue(call("list_test_runs").list("runs").any { it["runId"] == runId && it["status"] == "PASSED" })
        val onDisk = File(dir, "testing/runs/$runId/run.json")
        assertTrue(onDisk.isFile, "the run folder is under the fallback base: ${onDisk.absolutePath}")
    }

    @Test
    fun aRefusedStartIsDataWithAnErrorAndNothingStarts() {
        val suiteId = createSuite("A")
        val lanes = listOf(mapOf("profileId" to "external", "deviceSerial" to FIXTURE_SERIAL))
        assertTrue(call("run_test_suite", "suiteId" to "suite-nope", "lanes" to lanes)["error"].toString().contains("not found"))
        assertTrue(call("run_test_suite", "suiteId" to suiteId, "lanes" to lanes, "repeat" to 2)["error"].toString().contains("repeat"))
        assertTrue(call("run_test_suite", "suiteId" to suiteId, "lanes" to emptyList<Any>())["error"].toString().contains("at least one lane"))
        val offline = call("run_test_suite", "suiteId" to suiteId, "lanes" to listOf(mapOf("profileId" to "external", "deviceSerial" to "GONE")))
        assertTrue(offline["error"].toString().contains("not connected"), offline.toString())
        assertTrue(call("run_test_suite", "suiteId" to suiteId, "lanes" to listOf(mapOf("profileId" to "no-such-profile", "deviceSerial" to FIXTURE_SERIAL)))
            ["error"].toString().contains("does not exist"))
        assertTrue(call("run_test_suite", "suiteId" to suiteId)["error"].toString().contains("lanes is required"))
        assertTrue(call("list_test_runs").list("runs").isEmpty())
    }

    @Test
    fun theFreeEditionRefusesToRunALockedSuiteWithTheLimitShape() {
        val first = createSuite("A")
        val second = createSuite("B")
        state.editionService.setForDev(Edition.FREE)
        val lanes = listOf(mapOf("profileId" to "external", "deviceSerial" to FIXTURE_SERIAL))

        val refused = call("run_test_suite", "suiteId" to second, "lanes" to lanes)
        assertTrue(refused["error"].toString().contains("locked"), refused.toString())
        @Suppress("UNCHECKED_CAST")
        val limit = refused["limit"] as Map<String, Any?>
        assertEquals("LOCKED", limit["kind"])
        assertEquals("FREE", limit["edition"])

        val started = call("run_test_suite", "suiteId" to first, "lanes" to lanes)
        assertEquals(null, started["error"], started.toString())
        call("cancel_test_run", "runId" to started["runId"])
    }

    @Test
    fun cancelStopsAnUnattendedExternalRunAndTheDeviceIsFreeAgain() {
        val suiteId = createSuite("Never driven")
        val (runId, laneId) = startExternal(suiteId)
        awaitStep(runId, laneId)

        assertEquals(true, call("cancel_test_run", "runId" to runId)["cancelling"])
        val status = awaitStatus(runId, "CANCELLED")
        assertEquals("CANCELLED", status.list("lanes").single()["status"])
        assertTrue(call("cancel_test_run", "runId" to runId)["error"].toString().contains("already over"))
        assertTrue(call("cancel_test_run", "runId" to "run-nope")["error"].toString().contains("not running"))
        val again = call("run_test_suite", "suiteId" to suiteId, "lanes" to listOf(mapOf("profileId" to "external", "deviceSerial" to FIXTURE_SERIAL)))
        assertEquals(null, again["error"], again.toString())
        call("cancel_test_run", "runId" to again["runId"])
    }

    @Test
    fun laneCallsAreRefusedForUnknownRunsAndNoActiveCaseAndEveryToolIsGuarded() {
        assertTrue(lane("run-nope", "lane-x", "tap")["error"].toString().contains("not running in this session"))
        val suiteId = createSuite("Only step")
        val (runId, laneId) = startExternal(suiteId)
        assertTrue(lane(runId, "lane-other", "tap")["error"].toString().contains("no lane"))
        awaitStep(runId, laneId)
        assertTrue(lane(runId, laneId, "list_tabs")["error"].toString().contains("unknown operation"), "a global tool is not a lane tool")
        assertTrue(call("resume_paused_step", "runId" to runId, "laneId" to laneId, "decision" to "retry")["error"].toString().contains("not paused"))
        assertTrue(call("resolve_test_confirmation", "runId" to runId, "confirmationId" to "nope", "allow" to true)["error"].toString().contains("No pending"))
        assertTrue(call("resume_paused_step", "runId" to runId, "laneId" to laneId, "decision" to "maybe")["error"].toString().contains("decision"))
        call("cancel_test_run", "runId" to runId)
        awaitStatus(runId, "CANCELLED")
        assertTrue(lane(runId, laneId, "tap")["error"].toString().contains("cancelled"))
    }

    @Test
    fun theRunToolsAreConfirmationGatedAndExternalApprovalCoversTheRightTools() {
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("run_test_suite"))
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("cancel_test_run"))
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("get_test_run_status"))
        assertTrue("run_test_suite" in PER_CALL_APPROVAL_MCP_TOOLS)
        assertTrue("test_lane_tool_call" in PER_CALL_APPROVAL_MCP_TOOLS)
        assertTrue("test_lane_tool_call" in SCREEN_IMAGE_TOOL_NAMES)
    }
}
