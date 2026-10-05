package com.indagium.debug

import com.indagium.testing.FIXTURE_SERIAL
import com.indagium.testing.ScriptedAdbRunner
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep
import com.indagium.testing.openFixtureSession
import com.indagium.testing.run.EngineTuning
import com.indagium.testing.run.LaneDeviceOpener
import com.indagium.testing.script.TestScriptRunner
import com.indagium.testing.store.StoreResult
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

/** An external MCP client must be approved for every run_test_suite call and every script it runs on an external lane. */
class ExternalRunApprovalTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private lateinit var server: ControlServer
    private val client = HttpClient.newHttpClient()
    private val adb = ScriptedAdbRunner()
    private lateinit var script: TestScript
    private lateinit var suiteId: String

    @BeforeTest
    fun setUp() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        dir = createTempDirectory("external-run-approval").toFile()
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
        script = (state.createTestScript(
            TestScript(
                id = "", toolName = "mark_it", commandTemplate = "printf ran > '${File(dir, "ran.txt").absolutePath}'",
                permission = ScriptPermission.AUTO,
            ),
        ) as StoreResult.Ok).value
        val suite = (state.createTestSuite("Approval suite") as StoreResult.Ok).value
        val case = (state.createTestCase(suite.id, TestCase("", "Only case")) as StoreResult.Ok).value
        state.createTestStep(case.id, TestStep("", "Look around", "Nothing odd"))
        suiteId = suite.id
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

    private val lanes get() = """[{"profileId":"external","deviceSerial":"$FIXTURE_SERIAL"}]"""

    @Test
    fun runTestSuiteWaitsForTheUserAndShowsTheSuiteLanesAndScripts() {
        val sessionId = session()
        val response = callTool(sessionId, "run_test_suite", """{"suiteId":"$suiteId","lanes":$lanes}""")

        val approval = awaitApproval()
        Thread.sleep(HOLD_MS)
        assertFalse(response.isDone, "the call waits for the user")
        assertTrue(state.testRuns.isEmpty(), "nothing started before approval")
        val action = assertNotNull(approval.action)
        assertEquals("Start a test run?", action.title)
        assertTrue(action.summary.contains("Developer Claude") && action.summary.contains("Approval suite"), action.summary)
        val fields = action.fields.toMap()
        assertTrue(fields.getValue("Lanes").contains(FIXTURE_SERIAL) && fields.getValue("Lanes").contains("driven by you"), fields.toString())
        val scripts = fields.getValue("Scripts that may run")
        assertTrue(scripts.contains("mark_it") && scripts.contains("printf ran"), fields.toString())
        assertEquals("Start run", action.allowLabel)

        state.resolveExternalDeviceAiApproval(approval.requestId, true)
        val body = response.get().body()
        assertTrue(body.contains("runId"), body)
        assertEquals(1, state.testRunCoordinator.runsFlow.value.size)
        state.testRunCoordinator.runsFlow.value.forEach { state.testRunCoordinator.cancel(it.id) }
    }

    @Test
    fun aDeniedRunNeverStarts() {
        val response = callTool(session(), "run_test_suite", """{"suiteId":"$suiteId","lanes":$lanes}""")
        state.resolveExternalDeviceAiApproval(awaitApproval().requestId, false)

        val body = response.get().body()
        assertTrue(body.contains("declined"), body)
        assertTrue(state.testRunCoordinator.runsFlow.value.isEmpty())
    }

    @Test
    fun aCallThatWouldBeRefusedAnywayDoesNotPromptAndTheReadToolsNeverDo() {
        val sessionId = session()
        val unknown = callTool(sessionId, "run_test_suite", """{"suiteId":"suite-missing","lanes":$lanes}""").get().body()
        assertTrue(unknown.contains("not found"), unknown)
        assertTrue(callTool(sessionId, "list_test_runs", "{}").get().body().contains("runs"))
        assertTrue(state.externalDeviceAiApprovals.isEmpty())
    }

    @Test
    fun aScriptToolOfAnExternalLaneNeedsApprovalButBuiltInLaneToolsDoNot() {
        val sessionId = session()
        val started = callTool(sessionId, "run_test_suite", """{"suiteId":"$suiteId","lanes":$lanes}""")
        state.resolveExternalDeviceAiApproval(awaitApproval().requestId, true)
        val body = started.get().body()
        val runId = Regex("""runId\\?":\\?"(run-[^"\\]+)""").find(body)?.groupValues?.get(1) ?: error("no run id in $body")
        val laneId = Regex("""lane-[A-Za-z0-9-]+""").find(body)?.value ?: error("no lane id in $body")
        runBlocking {
            withTimeout(AWAIT_MS) {
                while (state.testRunCoordinator.run(runId)?.lanes?.single()?.currentStepNumber != 1) delay(POLL_MS)
            }
        }

        val builtIn = callTool(sessionId, "test_lane_tool_call", """{"runId":"$runId","laneId":"$laneId","tool":"get_current_step"}""").get().body()
        assertTrue(builtIn.contains("Look around"), builtIn)
        assertTrue(state.externalDeviceAiApprovals.isEmpty(), "a built-in lane tool needs no approval")

        val scriptCall = callTool(sessionId, "test_lane_tool_call", """{"runId":"$runId","laneId":"$laneId","tool":"mark_it"}""")
        val approval = awaitApproval()
        Thread.sleep(HOLD_MS)
        assertFalse(scriptCall.isDone)
        assertFalse(File(dir, "ran.txt").exists(), "nothing runs before approval")
        assertEquals("Run a script in a test run?", assertNotNull(approval.action).title)
        assertTrue(approval.action!!.fields.toMap().getValue("Command").contains("printf ran"))

        state.resolveExternalDeviceAiApproval(approval.requestId, true)
        val result = scriptCall.get().body()
        assertTrue(result.contains("exitCode"), result)
        assertEquals("ran", File(dir, "ran.txt").readText())
        state.testRunCoordinator.cancel(runId)
    }
}
