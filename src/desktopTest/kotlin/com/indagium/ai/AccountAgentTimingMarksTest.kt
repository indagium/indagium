package com.indagium.ai

import com.indagium.debug.IndagiumToolGateway
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.ui.AppState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val AWAIT_MS = 20_000L
private const val CLOCK_START_MS = 1_000L

private class ScriptedCodex(private val onWrite: (ScriptedCodex, String) -> Unit) : CodexAppServerProcess {
    private val output = LinkedBlockingQueue<String>()

    @Volatile private var alive = true

    override fun writeLine(line: String) = onWrite(this, line)

    override fun readLine(): String? = output.take().takeIf { it != EOF }

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

private class ScriptedClaude(private val lines: List<String>) : ClaudeCodeProcess {
    override val stdout: InputStream = ByteArrayInputStream((lines.joinToString("\n") + "\n").toByteArray())
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

/** The marks an account agent's run puts on its [AiRun] for the timing breakdown: when it was ready and how many model responses came. */
class AccountAgentTimingMarksTest {
    private lateinit var state: AppState
    private lateinit var dir: File
    private val clock = AtomicLong(CLOCK_START_MS)

    @BeforeTest
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("account-timing").toFile()
        state = AppState(autosaveFile = File(dir, "state.cache"), autoExportNotes = false, notesDir = File(dir, "notes"), testingDir = File(dir, "testing"))
    }

    @AfterTest
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    private fun runner(claude: ClaudeCodeProcessFactory? = null, codex: ((List<String>) -> CodexAppServerClient)? = null) = AccountAgentRunner(
        managedMcpServerFactory = { run -> ManagedMcpServerLease.start(state, run, IndagiumToolGateway(emptyList(), emptyMap())) },
        maxToolRounds = 4,
        cliCheck = { AccountCliCheck(true, "") },
        claudeProcessFactory = claude ?: ClaudeCodeProcessFactory { _, _ -> error("not used") },
        codexLauncher = { command, _, _ -> codex?.invoke(command) ?: error("not used") },
        clock = { clock.getAndAdd(1L) },
    )

    private fun finish(runner: AccountAgentRunner, kind: AiProviderKind): AiRun {
        val session = AiSession("timing-tab")
        val profile = AiProviderProfile("account", "Account", "", "", kind = kind)
        val run = runner.start(session, profile, "Judge this step.", "You judge.", AiInvestigationContext("timing-tab"))
        runBlocking { withTimeout(AWAIT_MS) { run.job?.join() } }
        session.deleteClaudeCodeWorkspace()
        runner.close()
        return run
    }

    @Test
    fun codexIsReadyWhenItsManagedMcpServerIsAndEveryNewTokenTotalIsOneModelResponse() {
        val usage = { total: Long ->
            """{"method":"thread/tokenUsage/updated","params":{"threadId":"thread-1","turnId":"turn-1","tokenUsage":{"total":""" +
                """{"inputTokens":$total,"cachedInputTokens":0,"outputTokens":10,"reasoningOutputTokens":0,"totalTokens":$total}}}}"""
        }
        val launcher = { _: List<String> ->
            CodexAppServerClient(
                ScriptedCodex { process, line ->
                    val message = Json.parseToJsonElement(line).jsonObject
                    val id = message["id"]
                    when (message["method"]?.jsonPrimitive?.content) {
                        "initialize" -> process.send("""{"id":$id,"result":{"serverInfo":{"name":"codex","version":"1"}}}""")
                        "thread/start" -> process.send("""{"id":$id,"result":{"thread":{"id":"thread-1"}}}""")
                        "turn/start" -> {
                            process.send("""{"id":$id,"result":{"turn":{"id":"turn-1","threadId":"thread-1","status":{"type":"inProgress"}}}}""")
                            process.send("""{"method":"mcpServer/startupStatus/updated","params":{"name":"indagium","status":"ready"}}""")
                            process.send("""{"method":"turn/started","params":{"turn":{"id":"turn-1","threadId":"thread-1","status":"inProgress"}}}""")
                            process.send(usage(100L))
                            process.send(usage(100L))
                            process.send(usage(250L))
                            process.send(
                                """{"method":"turn/completed","params":{"threadId":"thread-1",""" +
                                    """"turn":{"id":"turn-1","status":{"type":"completed"}}}}""",
                            )
                        }
                    }
                },
            )
        }

        val run = finish(runner(codex = launcher), AiProviderKind.CODEX_ACCOUNT)

        val ready = assertNotNull(run.agentReadyAt, "Codex said it was ready")
        val timed = run.timedHistory
        val mcpReady = timed.first { (it.event as? AiRunEvent.Status)?.text == "Indagium MCP: ready" }
        assertTrue(ready <= mcpReady.at, "ready is marked when the MCP status arrives, not when the turn is over")
        assertEquals(2, run.modelResponseTimes.size, "a repeated total is not a new response")
        assertTrue(run.createdAt < ready)
        assertTrue(timed.any { it.event == AiRunEvent.Done })
    }

    @Test
    fun claudeCodeIsReadyAtItsFirstStreamLineAndEachAssistantTurnIsOneModelResponse() {
        val lines = listOf(
            """{"type":"system","subtype":"init","session_id":"s1"}""",
            """{"type":"assistant","session_id":"s1","message":{"content":[{"type":"text","text":"Looking."}]}}""",
            """{"type":"assistant","session_id":"s1","message":{"content":[{"type":"text","text":"Done."}]}}""",
            """{"type":"result","subtype":"success","result":"Done.","session_id":"s1"}""",
        )
        val factory = ClaudeCodeProcessFactory { _: List<String>, _: Path? -> ScriptedClaude(lines) }

        val run = finish(runner(claude = factory), AiProviderKind.CLAUDE_CODE_ACCOUNT)

        val ready = assertNotNull(run.agentReadyAt, "the first stream line names the session")
        assertEquals(2, run.modelResponseTimes.size)
        assertTrue(run.createdAt < ready)
        assertTrue(run.modelResponseTimes.all { it >= ready })
        assertTrue(run.timedHistory.any { it.event == AiRunEvent.Done })
    }
}
