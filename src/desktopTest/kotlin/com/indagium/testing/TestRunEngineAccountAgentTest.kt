package com.indagium.testing

import com.indagium.ai.AccountAgentRunner
import com.indagium.ai.AccountCliCheck
import com.indagium.ai.ClaudeCodeProcess
import com.indagium.ai.ClaudeCodeProcessFactory
import com.indagium.ai.CodexAppServerClient
import com.indagium.ai.CodexAppServerProcess
import com.indagium.ai.ManagedMcpServerLease
import com.indagium.ai.defaultAccountPromptPreamble
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepStatus
import com.indagium.testing.run.AccountLaneAgent
import com.indagium.testing.run.AccountRunnerFactory
import com.indagium.testing.run.LaneAgentFactory
import com.indagium.testing.run.StartRunResult
import com.indagium.testing.run.lanePromptPreamble
import com.indagium.ui.AppState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val AWAIT_MS = 20_000L
private const val INITIALIZE_REQUEST =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
        """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"fake-agent","version":"1"}}}"""

/** A fake agent's MCP client: what Claude Code or Codex would do against the lane's private endpoint. */
private class FakeAgentMcp(private val url: String, private val token: String) {
    private val http = HttpClient.newHttpClient()
    private var session: String? = null

    private fun post(json: String): HttpResponse<String> {
        var request = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.ofString(json))
        session?.let { request = request.header("Mcp-Session-Id", it) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun start() {
        val init = post(INITIALIZE_REQUEST)
        session = init.headers().firstValue("mcp-session-id").orElseThrow { AssertionError("no MCP session:\n${init.body()}") }
        post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
    }

    fun toolsList(): String = post("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""").body()

    fun call(name: String, arguments: String = "{}"): String =
        post("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}""").body()
}

private class ScriptedClaudeProcess(private val body: () -> String) : ClaudeCodeProcess {
    override val stdout: InputStream = object : InputStream() {
        private val bytes by lazy { (body() + "\n").toByteArray() }
        private val stream by lazy { ByteArrayInputStream(bytes) }

        override fun read(): Int = stream.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
    }
    override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))
    override var isAlive: Boolean = true

    override fun waitFor(): Int {
        isAlive = false
        return 0
    }

    override fun destroy() {
        isAlive = false
    }

    override fun destroyForcibly() = destroy()
}

private class ScriptedCodexProcess(private val onWrite: (ScriptedCodexProcess, String) -> Unit) : CodexAppServerProcess {
    private val output = LinkedBlockingQueue<String>()

    @Volatile private var alive = true

    override fun writeLine(line: String) = onWrite(this, line)

    override fun readLine(): String? {
        val line = output.take()
        return line.takeIf { it != EOF }
    }

    override fun isAlive(): Boolean = alive

    override fun exitCode(): Int? = if (alive) null else 0

    override fun destroy() {
        alive = false
        output.offer(EOF)
    }

    override fun close() = destroy()

    fun send(json: String) = output.put(json)

    private companion object {
        const val EOF = "__EOF__"
    }
}

/** Claude Code and Codex lanes: the managed MCP endpoint serves the lane gateway, the prompt preamble is the lane's, the budget frees the protocol tools. */
class TestRunEngineAccountAgentTest {
    private lateinit var state: AppState
    private lateinit var dir: File
    private var harness: RunHarness? = null

    @BeforeTest
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("account-agent-run").toFile()
        state = AppState(autosaveFile = File(dir, "state.cache"), autoExportNotes = false, notesDir = File(dir, "notes"), testingDir = File(dir, "testing"))
    }

    @AfterTest
    fun tearDown() {
        harness?.close()
        state.close()
        dir.deleteRecursively()
    }

    private fun accountFactory(
        profile: AiProviderProfile,
        claude: ClaudeCodeProcessFactory?,
        codex: ((List<String>, Map<String, String>) -> CodexAppServerClient)?,
    ) =
        LaneAgentFactory { _, _ ->
            AccountLaneAgent(
                profile,
                AccountRunnerFactory { request ->
                    AccountAgentRunner(
                        managedMcpServerFactory = { run -> ManagedMcpServerLease.start(state, run, request.gateway) },
                        maxToolRounds = request.toolCallLimit,
                        maxTurns = request.maxTurns,
                        promptPreamble = request.promptPreamble,
                        cliCheck = { AccountCliCheck(true, "") },
                        claudeProcessFactory = claude ?: ClaudeCodeProcessFactory { _, _ -> error("not used") },
                        codexLauncher = { command, environment, scope ->
                            codex?.invoke(command, environment)?.let { it } ?: error("not used: $command $environment $scope")
                        },
                    )
                },
            )
        }

    private fun mcpConfigOf(command: List<String>): Pair<String, String> {
        val json = Json.parseToJsonElement(command[command.indexOf("--mcp-config") + 1]).jsonObject
        val server = json.getValue("mcpServers").jsonObject.getValue("indagium").jsonObject
        val token = server.getValue("headers").jsonObject.getValue("Authorization").jsonPrimitive.content.removePrefix("Bearer ")
        return server.getValue("url").jsonPrimitive.content to token
    }

    @Test
    fun aClaudeCodeLaneUsesTheManagedLaneGatewayAndTheLanePreamble() {
        val profile = AiProviderProfile("claude-lane", "Claude Code", "", "", kind = AiProviderKind.CLAUDE_CODE_ACCOUNT)
        val commands = CopyOnWriteArrayList<List<String>>()
        val toolLists = CopyOnWriteArrayList<String>()
        val factory = ClaudeCodeProcessFactory { command: List<String>, _: Path? ->
            commands += command
            ScriptedClaudeProcess {
                val (url, token) = mcpConfigOf(command)
                val mcp = FakeAgentMcp(url, token)
                mcp.start()
                toolLists += mcp.toolsList()
                mcp.call("get_current_step")
                mcp.call("finish_step", """{"status":"pass","observation":"Everything is fine"}""")
                """{"type":"result","subtype":"success","result":"Done","session_id":"s1"}"""
            }
        }
        val suite = suiteOf(caseOf("Via Claude", step("Open the app")))
        val h = RunHarness(libraryOf(suite), profile = profile, agentFactory = accountFactory(profile, factory, null)).also { harness = it }
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(h.config(suite, toolLimit = 7)) })
        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        assertEquals(RunStatus.PASSED, run.status, run.toString())
        assertEquals(StepStatus.PASS, run.caseResult("Via Claude").steps.single().status)
        assertEquals("Everything is fine", run.caseResult("Via Claude").steps.single().observation)

        val command = commands.single()
        val prompt = command.last()
        val globalOnly = "list_tabs"
        assertTrue(prompt.contains("Current step 1 of 1"), prompt)
        assertTrue(prompt.contains("QA tester"), "the lane system prompt is part of the request:\n$prompt")
        assertTrue(prompt.contains("get_current_step, report_observation and finish_step are free"), "promptPreamble applied:\n$prompt")
        assertTrue(prompt.contains("strict 7-call budget"), prompt)
        assertFalse(prompt.contains("Notes and annotation reads/writes are unlimited"), "the sidebar's budget wording is not used for a lane")
        assertTrue(command[command.indexOf("--max-turns") + 1].toInt() > 7, "protocol tools are free but still turns: $command")
        assertTrue(toolLists.single().contains("\"finish_step\""), toolLists.single())
        assertFalse(toolLists.single().contains("\"$globalOnly\""), "the managed server serves ONLY the lane's tools:\n${toolLists.single()}")
    }

    @Test
    fun aCodexLaneUsesTheManagedLaneGatewayToo() {
        val profile = AiProviderProfile("codex-lane", "Codex", "", "", kind = AiProviderKind.CODEX_ACCOUNT)
        val toolLists = CopyOnWriteArrayList<String>()
        val commands = CopyOnWriteArrayList<List<String>>()
        val turnPrompts = CopyOnWriteArrayList<String>()
        val launcher = { command: List<String>, environment: Map<String, String> ->
            commands += command
            val config = command.windowed(2).map { it[1] }.filter { it.startsWith("mcp_servers.indagium.url=") }.single()
            val url = config.substringAfter("=").trim('"')
            val token = environment.getValue("INDAGIUM_MCP_TOKEN")
            val process = ScriptedCodexProcess { process, line ->
                val message = Json.parseToJsonElement(line).jsonObject
                val id = message["id"]
                when (message["method"]?.jsonPrimitive?.content) {
                    "initialize" -> process.send("""{"id":$id,"result":{"serverInfo":{"name":"codex","version":"1"}}}""")
                    "thread/start" -> process.send("""{"id":$id,"result":{"thread":{"id":"thread-1"}}}""")
                    "turn/start" -> {
                        val input = message.getValue("params").jsonObject.getValue("input").toString()
                        turnPrompts += input
                        process.send("""{"id":$id,"result":{"turn":{"id":"turn-1","threadId":"thread-1","status":{"type":"inProgress"}}}}""")
                        thread {
                            val mcp = FakeAgentMcp(url, token)
                            mcp.start()
                            toolLists += mcp.toolsList()
                            mcp.call("finish_step", """{"status":"pass","observation":"Codex saw it"}""")
                            process.send(
                                """{"method":"turn/completed","params":{"threadId":"thread-1",""" +
                                    """"turn":{"id":"turn-1","status":{"type":"completed"}}}}""",
                            )
                        }
                    }
                }
            }
            CodexAppServerClient(process)
        }
        val suite = suiteOf(caseOf("Via Codex", step("Open the app")))
        val h = RunHarness(libraryOf(suite), profile = profile, agentFactory = accountFactory(profile, null, launcher)).also { harness = it }
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(h.config(suite)) })
        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        assertEquals(RunStatus.PASSED, run.status, run.toString())
        assertEquals(CaseStatus.PASS, run.caseResult("Via Codex").status)
        assertEquals("Codex saw it", run.caseResult("Via Codex").steps.single().observation)
        val prompt = turnPrompts.single()
        assertTrue(prompt.contains("finish_step are free"), prompt)
        assertTrue(prompt.contains("Current step 1 of 1"), prompt)
        assertTrue(toolLists.single().contains("\"finish_step\"") && !toolLists.single().contains("\"list_tabs\""), toolLists.single())
        assertTrue(commands.single().contains("mcp_servers.indagium.required=true"))
    }

    /** Like [accountFactory] but the agent is built from the profile the coordinator hands over, which carries the lane's overrides. */
    private fun overridingFactory(claude: ClaudeCodeProcessFactory?, codex: ((List<String>, Map<String, String>) -> CodexAppServerClient)?) =
        LaneAgentFactory { chosen, key -> accountFactory(chosen, claude, codex).create(chosen, key) }

    @Test
    fun aClaudeCodeLaneOverrideBecomesTheModelAndEffortArgumentsOfTheCli() {
        val profile = AiProviderProfile("claude-lane", "Claude Code", "", "sonnet", kind = AiProviderKind.CLAUDE_CODE_ACCOUNT, reasoningEffort = "low")
        val commands = CopyOnWriteArrayList<List<String>>()
        val factory = ClaudeCodeProcessFactory { command: List<String>, _: Path? ->
            commands += command
            ScriptedClaudeProcess { """{"type":"result","subtype":"success","result":"Done","session_id":"s1"}""" }
        }
        val suite = suiteOf(caseOf("Via Claude", step("Open the app")))
        val h = RunHarness(libraryOf(suite), profile = profile, agentFactory = overridingFactory(factory, null)).also { harness = it }
        val config = h.config(suite, agentLane(profile.id).copy(model = "opus", reasoningEffort = "max"))
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        val command = commands.first()
        assertEquals("opus", command[command.indexOf("--model") + 1], command.toString())
        assertEquals("max", command[command.indexOf("--effort") + 1], command.toString())
    }

    @Test
    fun aClaudeCodeLaneWithoutAnOverrideKeepsTheProfilesOwnModelAndEffort() {
        val profile = AiProviderProfile("claude-lane", "Claude Code", "", "sonnet", kind = AiProviderKind.CLAUDE_CODE_ACCOUNT, reasoningEffort = "low")
        val commands = CopyOnWriteArrayList<List<String>>()
        val factory = ClaudeCodeProcessFactory { command: List<String>, _: Path? ->
            commands += command
            ScriptedClaudeProcess { """{"type":"result","subtype":"success","result":"Done","session_id":"s1"}""" }
        }
        val suite = suiteOf(caseOf("Via Claude", step("Open the app")))
        val h = RunHarness(libraryOf(suite), profile = profile, agentFactory = overridingFactory(factory, null)).also { harness = it }
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(h.config(suite, agentLane(profile.id))) })
        runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        val command = commands.first()
        assertEquals("sonnet", command[command.indexOf("--model") + 1])
        assertEquals("low", command[command.indexOf("--effort") + 1])
    }

    @Test
    fun aCodexLaneOverrideBecomesTheModelAndEffortOfTheTurn() {
        val profile = AiProviderProfile("codex-lane", "Codex", "", "gpt-base", kind = AiProviderKind.CODEX_ACCOUNT)
        val turnParams = CopyOnWriteArrayList<kotlinx.serialization.json.JsonObject>()
        val launcher = { _: List<String>, _: Map<String, String> ->
            val process = ScriptedCodexProcess { process, line ->
                val message = Json.parseToJsonElement(line).jsonObject
                val id = message["id"]
                when (message["method"]?.jsonPrimitive?.content) {
                    "initialize" -> process.send("""{"id":$id,"result":{"serverInfo":{"name":"codex","version":"1"}}}""")
                    "thread/start" -> process.send("""{"id":$id,"result":{"thread":{"id":"thread-1"}}}""")
                    "turn/start" -> {
                        turnParams += message.getValue("params").jsonObject
                        process.send("""{"id":$id,"result":{"turn":{"id":"turn-1","threadId":"thread-1","status":{"type":"inProgress"}}}}""")
                        process.send(
                            """{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":{"type":"completed"}}}}""",
                        )
                    }
                }
            }
            CodexAppServerClient(process)
        }
        val suite = suiteOf(caseOf("Via Codex", step("Open the app")))
        val h = RunHarness(libraryOf(suite), profile = profile, agentFactory = overridingFactory(null, launcher)).also { harness = it }
        val config = h.config(suite, agentLane(profile.id).copy(model = "gpt-other", reasoningEffort = "xhigh"))
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        val params = turnParams.first()
        assertEquals("gpt-other", params.getValue("model").jsonPrimitive.content)
        assertEquals("xhigh", params.getValue("effort").jsonPrimitive.content)
    }

    @Test
    fun theSidebarPromptIsUnchangedByDefault() {
        // The default preamble is the exact wording the sidebar always used.
        val run = com.indagium.ai.AiRun(tabId = "tab", maxToolCalls = 12)
        val preamble = defaultAccountPromptPreamble(run)
        assertEquals(
            "${run.toolCallBudget.initialGuidance()}\n\nYou have one MCP server named indagium. Use only its tools for log, source, filter, " +
                "tab, device, or note evidence and actions. Do not use host shell/browser/desktop actions, " +
                "and do not inspect the local workspace; it is intentionally empty.",
            preamble,
        )
        assertTrue(lanePromptPreamble(run).contains("strict 12-call budget"))
    }
}
