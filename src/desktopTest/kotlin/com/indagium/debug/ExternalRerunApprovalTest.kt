package com.indagium.debug

import com.indagium.testing.FIXTURE_SERIAL
import com.indagium.testing.ScriptedAdbRunner
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.newRunId
import com.indagium.testing.openFixtureSession
import com.indagium.testing.run.EngineTuning
import com.indagium.testing.run.LaneDeviceOpener
import com.indagium.testing.script.TestScriptRunner
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.encodeRunFile
import com.indagium.ui.AppState
import com.indagium.ui.TestRunOverrides
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeFalse
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val INITIALIZE_REQUEST =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
        """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"Developer Claude","version":"1"}}}"""
private const val AWAIT_MS = 15_000L
private const val POLL_MS = 10L
private const val HOLD_MS = 400L
private val HTTP_OK_RANGE = 200..299

/** An external MCP client must be approved for every rerun_test_step call: it starts a new run that drives a device. */
class ExternalRerunApprovalTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private lateinit var server: ControlServer
    private val client = HttpClient.newHttpClient()
    private val adb = ScriptedAdbRunner()
    private lateinit var arguments: String

    @BeforeTest
    fun setUp() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        dir = createTempDirectory("external-rerun-approval").toFile()
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
            deviceProblem = { null },
            scriptRunner = TestScriptRunner(hostShell = listOf("/bin/sh", "-c")),
            tuning = EngineTuning(persistDebounceMs = 50L),
        )
        state.createTestScript(
            TestScript(
                id = "", toolName = "mark_it", commandTemplate = "printf ran > '${File(dir, "ran.txt").absolutePath}'",
                permission = ScriptPermission.AUTO,
            ),
        )
        val suite = (state.createTestSuite("Rerun suite") as StoreResult.Ok).value
        val case = (state.createTestCase(suite.id, TestCase("", "Only case")) as StoreResult.Ok).value
        val first = (state.createTestStep(case.id, TestStep("", "Look around", "Nothing odd")) as StoreResult.Ok).value
        state.createTestStep(case.id, TestStep("", "Look again", "Still nothing odd"))
        val lane = LaneConfig(kind = LaneKind.EXTERNAL, deviceSerial = FIXTURE_SERIAL)
        val run = TestRun(
            id = newRunId(), suite = checkNotNull(state.testLibrary.suite(suite.id)), scripts = emptyList(), sharedSteps = emptyList(),
            config = RunConfig(suite.id, null, listOf(lane)),
            lanes = listOf(LaneResult(lane.id, lane, RunStatus.FAILED, listOf(CaseResult(case.id, case.name, 1, CaseStatus.FAIL)))),
            status = RunStatus.FAILED, createdAt = 1L, finishedAt = 2L,
        )
        File(dir, "testing/runs/${run.id}/run.json").also { it.parentFile.mkdirs() }.writeText(encodeRunFile(run))
        arguments = """{"runId":"${run.id}","laneId":"${lane.id}","caseId":"${case.id}","stepId":"${first.id}"}"""
        server = ControlServer(state, 0)
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        state.close()
        dir.deleteRecursively()
    }

    private fun post(json: String, sessionId: String? = null): CompletableFuture<HttpResponse<String>> {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.boundPort}/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Authorization", "Bearer ${server.token}")
            .POST(HttpRequest.BodyPublishers.ofString(json))
        if (sessionId != null) request = request.header("Mcp-Session-Id", sessionId)
        return client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun session(): String {
        val init = post(INITIALIZE_REQUEST).get()
        assertTrue(init.statusCode() in HTTP_OK_RANGE, init.body())
        val id = init.headers().firstValue("mcp-session-id").orElseThrow { AssertionError("no session:\n${init.body()}") }
        post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", id).get()
        return id
    }

    private fun callTool(sessionId: String, tool: String, arguments: String) = post(
        """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""",
        sessionId,
    )

    private fun awaitApproval(): com.indagium.ui.ExternalDeviceAiApproval = runBlocking {
        withTimeout(AWAIT_MS) {
            while (state.externalDeviceAiApprovals.isEmpty()) delay(POLL_MS)
            state.externalDeviceAiApprovals.first()
        }
    }

    @Test
    fun rerunWaitsForTheUserAndShowsTheCaseTheStepTheDeviceAndTheScripts() {
        val response = callTool(session(), "rerun_test_step", arguments)

        val approval = awaitApproval()
        Thread.sleep(HOLD_MS)
        assertFalse(response.isDone, "the call waits for the user")
        assertTrue(state.testRuns.isEmpty(), "nothing started before approval")
        val action = assertNotNull(approval.action)
        assertEquals("Run a step again?", action.title)
        assertTrue(action.summary.contains("Developer Claude") && action.summary.contains("step 1"), action.summary)
        val fields = action.fields.toMap()
        assertTrue(fields.getValue("Case").contains("Only case") && fields.getValue("Case").contains("steps 1–1 of 2"), fields.toString())
        assertTrue(fields.getValue("Lane").contains(FIXTURE_SERIAL), fields.toString())
        assertTrue(fields.getValue("Scripts that may run").contains("mark_it"), fields.toString())
        assertEquals("Start run", action.allowLabel)

        state.resolveExternalDeviceAiApproval(approval.requestId, true)
        val body = response.get().body()
        assertTrue(body.contains("runId"), body)
        val runs = state.testRunCoordinator.runsFlow.value
        assertEquals(1, runs.size)
        assertEquals(listOf("Look around"), runs.single().suite.cases.single().steps.map { it.action })
        runs.forEach { state.testRunCoordinator.cancel(it.id) }
    }

    @Test
    fun aDeniedRerunNeverStartsAndACallThatWouldBeRefusedAnywayDoesNotPrompt() {
        val sessionId = session()
        val response = callTool(sessionId, "rerun_test_step", arguments)
        state.resolveExternalDeviceAiApproval(awaitApproval().requestId, false)
        assertTrue(response.get().body().contains("declined"))
        assertTrue(state.testRunCoordinator.runsFlow.value.isEmpty())

        val refused = callTool(sessionId, "rerun_test_step", """{"runId":"run-missing","laneId":"lane-x","caseId":"case-x","stepId":"step-x"}""").get().body()
        assertTrue(refused.contains("not found"), refused)
        assertTrue(state.externalDeviceAiApprovals.isEmpty(), "nothing to approve")
    }
}
