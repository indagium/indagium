package com.indagium.debug

import com.indagium.testing.model.ScriptParam
import com.indagium.testing.model.ScriptParamType
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.TestScript
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import com.indagium.ui.ExternalActionDetails
import kotlinx.coroutines.async
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val INITIALIZE_REQUEST =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
        """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"Rogue Client","version":"1"}}}"""
private const val AWAIT_MS = 5_000L
private const val POLL_MS = 10L
private const val HOLD_MS = 400L
private val HTTP_OK_RANGE = 200..299

/** try_test_script from an external MCP client runs only after the user allowed that exact call. */
class ExternalScriptApprovalTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private lateinit var server: ControlServer
    private val client = HttpClient.newHttpClient()

    @BeforeTest
    fun setUp() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        dir = createTempDirectory("external-script-approval").toFile()
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
        server = ControlServer(state, 0)
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        state.close()
        dir.deleteRecursively()
    }

    private var scriptCounter = 0

    private fun script(template: String, params: List<ScriptParam> = emptyList(), target: ScriptTarget = ScriptTarget.HOST_SHELL): TestScript {
        val toolName = "try_me_${scriptCounter++}"
        val created = state.createTestScript(TestScript(id = "", toolName = toolName, params = params, commandTemplate = template, target = target))
        return (created as StoreResult.Ok).value
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

    private fun callTry(sessionId: String, arguments: String): CompletableFuture<HttpResponse<String>> = post(
        """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"try_test_script","arguments":$arguments}}""",
        sessionId,
    )

    private fun awaitApproval(): com.indagium.ui.ExternalDeviceAiApproval = runBlocking {
        withTimeout(AWAIT_MS) {
            while (state.externalDeviceAiApprovals.isEmpty()) delay(POLL_MS)
            state.externalDeviceAiApprovals.first()
        }
    }

    private fun answer(accepted: Boolean) {
        state.resolveExternalDeviceAiApproval(awaitApproval().requestId, accepted)
    }

    @Test
    fun theCallBlocksUntilTheUserAllowsItAndOnlyThenRuns() {
        val marker = File(dir, "ran.txt")
        val saved = script(
            "echo \"\$word\" > '${marker.absolutePath}'; printf ran",
            listOf(ScriptParam("word", ScriptParamType.STRING)),
        )
        val sessionId = session()
        val response = callTry(sessionId, """{"scriptId":"${saved.id}","args":{"word":"hello there"}}""")

        val approval = awaitApproval()
        Thread.sleep(HOLD_MS)
        assertFalse(response.isDone, "the call must wait for the user")
        assertFalse(marker.exists(), "nothing runs before approval")
        val action = assertNotNull(approval.action)
        assertEquals("Rogue Client", approval.clientName)
        assertEquals("Run a script?", action.title)
        assertTrue(action.summary.contains("Rogue Client") && action.summary.contains("try_me_"), action.summary)
        val fields = action.fields.toMap()
        assertTrue(fields.getValue("Script").startsWith("try_me_"))
        assertEquals("This computer", fields["Runs on"])
        assertTrue(fields.getValue("Command").contains("echo \"\$word\""), "the exact command template is shown")
        assertEquals("word = hello there", fields["Arguments"])

        answer(true)
        val body = response.get().body()
        assertTrue(body.contains("ran"), body)
        assertTrue(marker.isFile, "runs after approval")
        assertTrue(state.externalDeviceAiApprovals.isEmpty(), "the dialog entry is gone")
    }

    @Test
    fun aDenialReturnsTheDeclinedErrorAndNothingRuns() {
        val marker = File(dir, "ran.txt")
        val saved = script("touch '${marker.absolutePath}'")
        val response = callTry(session(), """{"scriptId":"${saved.id}"}""")

        answer(false)

        val body = response.get().body()
        assertTrue(body.contains("declined"), body)
        assertFalse(marker.exists())
        assertTrue(state.externalDeviceAiApprovals.isEmpty())
    }

    @Test
    fun approvalIsPerCallNeverRememberedForTheSession() {
        val marker = File(dir, "count.txt")
        val saved = script("echo x >> '${marker.absolutePath}'")
        val sessionId = session()

        val first = callTry(sessionId, """{"scriptId":"${saved.id}"}""")
        answer(true)
        first.get()
        val second = callTry(sessionId, """{"scriptId":"${saved.id}"}""")
        val again = awaitApproval()
        assertNotNull(again.action, "the second call asks again")
        Thread.sleep(HOLD_MS)
        assertFalse(second.isDone)
        assertEquals(1, marker.readLines().size)

        state.resolveExternalDeviceAiApproval(again.requestId, false)
        assertTrue(second.get().body().contains("declined"))
        assertEquals(1, marker.readLines().size)
    }

    @Test
    fun aCallTheToolWouldRefuseAnywayDoesNotPromptTheUser() {
        val saved = script("true", listOf(ScriptParam("n", ScriptParamType.INT)))
        val sessionId = session()

        val unknown = callTry(sessionId, """{"scriptId":"script-missing"}""").get().body()
        val invalid = callTry(sessionId, """{"scriptId":"${saved.id}","args":{"n":"x"}}""").get().body()
        val device = script("true", target = ScriptTarget.ADB_SHELL)
        val noDevice = callTry(sessionId, """{"scriptId":"${device.id}"}""").get().body()

        assertTrue(unknown.contains("not found"), unknown)
        assertTrue(invalid.contains("whole number"), invalid)
        assertTrue(noDevice.contains("choose a device"), noDevice)
        assertTrue(state.externalDeviceAiApprovals.isEmpty())
    }

    @Test
    fun closingTheSessionDeniesAPendingApproval() {
        val saved = script("true")
        val sessionId = session()
        val response = callTry(sessionId, """{"scriptId":"${saved.id}"}""")
        val pending = awaitApproval()

        // The server closes a session by its own id (the one the approval is keyed by), as ControlServer does on disconnect.
        state.revokeExternalDeviceAiApproval(pending.sessionId)

        val body = response.get().body()
        assertTrue(body.contains("declined"), body)
    }

    @Test
    fun theDialogTextShowsTheExactCommandAndTruncatesHugeValues() {
        val long = "z".repeat(3_000)
        val saved = script("printf '%s' \"\$text\"", listOf(ScriptParam("text", ScriptParamType.STRING)))
        val details = assertNotNull(describeTryScriptCall(state, mapOf("scriptId" to saved.id, "args" to mapOf("text" to long)), "Client"))
        val argument = details.fields.toMap().getValue("Arguments")
        assertTrue(argument.length < 400, "was ${argument.length}")
        assertTrue(argument.contains("more characters"))
        assertEquals("printf '%s' \"\$text\"", details.fields.toMap()["Command"])

        val adb = script("pm clear x", target = ScriptTarget.ADB_SHELL)
        val adbDetails = assertNotNull(describeTryScriptCall(state, mapOf("scriptId" to adb.id, "deviceSerial" to "SER-1"), "Client"))
        assertTrue(adbDetails.fields.toMap().getValue("Runs on").contains("SER-1"))
        assertEquals("(none)", adbDetails.fields.toMap()["Arguments"])
        assertNull(describeTryScriptCall(state, mapOf("scriptId" to adb.id), "Client"))
    }

    @Test
    fun theSharedApprovalDoesNotTouchTheDeviceApprovalWording() = runBlocking {
        val device = async { state.awaitExternalDeviceAiApproval("s-1", "Claude Code", "Pixel (SERIAL-1)") }
        val pending = withTimeout(AWAIT_MS) {
            while (state.externalDeviceAiApprovals.isEmpty()) delay(POLL_MS)
            state.externalDeviceAiApprovals.single()
        }
        assertNull(pending.action, "device approvals carry no action details")
        assertEquals("Pixel (SERIAL-1)", pending.deviceLabel)
        state.resolveExternalDeviceAiApproval(pending.requestId, true)
        assertTrue(device.await())
        assertTrue(state.awaitExternalDeviceAiApproval("s-1", "Claude Code", "Pixel (SERIAL-1)"), "still remembered for the session")
        val details = ExternalActionDetails("t", "s", emptyList(), "ok", "declined")
        val denied = async { state.executeExternalApprovedAction("s-1", "Claude Code", details) { "ran" } }
        withTimeout(AWAIT_MS) { while (state.externalDeviceAiApprovals.isEmpty()) delay(POLL_MS) }
        state.resolveExternalDeviceAiApproval(state.externalDeviceAiApprovals.single().requestId, false)
        assertEquals(mapOf("error" to "declined"), denied.await())
    }
}
