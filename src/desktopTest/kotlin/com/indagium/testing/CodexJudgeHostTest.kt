package com.indagium.testing

import com.indagium.ai.AccountAgentRunner
import com.indagium.ai.AccountCliCheck
import com.indagium.ai.CodexAppServerClient
import com.indagium.ai.CodexJudgeHost
import com.indagium.ai.ManagedMcpServerLease
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.RunStatus
import com.indagium.testing.run.AccountLaneAgent
import com.indagium.testing.run.AccountRunnerFactory
import com.indagium.testing.run.AgentSegmentRequest
import com.indagium.testing.run.JudgeEvidence
import com.indagium.testing.run.JudgeImage
import com.indagium.testing.run.JudgeLaneEvidence
import com.indagium.testing.run.JudgeService
import com.indagium.testing.run.JudgeSite
import com.indagium.testing.run.LaneAgentFactory
import com.indagium.testing.run.ProviderLaneAgent
import com.indagium.testing.run.StartRunResult
import com.indagium.testing.store.TEST_RUN_JUDGE_FILE_NAME
import com.indagium.testing.store.TranscriptWriter
import com.indagium.ui.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val AWAIT_MS = 20_000L
private const val POLL_MS = 10L
private const val SETTLE_MS = 300L
private const val SLOW_START_MS = 400L
private const val PASS_ARGUMENTS = """{"verdict":"pass","reasoning":"The screen matches."}"""
private const val COMPARE_ARGUMENTS = """{"verdicts":{"Lane 1":"pass","Lane 2":"fail"},"explanation":"They differ."}"""
private const val BRIEF_CALLS = 3

private val site = JudgeSite("case-1", "c1-i1", "step-1", 1, 1)

private val codexProfile = AiProviderProfile("codex", "Codex", "", "", kind = AiProviderKind.CODEX_ACCOUNT)
private val claudeProfile = AiProviderProfile("claude", "Claude Code", "", "", kind = AiProviderKind.CLAUDE_CODE_ACCOUNT)

private fun laneOf(label: String) =
    JudgeLaneEvidence(label, "id-$label", emptyList(), { JudgeImage(byteArrayOf(1, 2, 3, 4), "image/jpeg") }, { _, _ -> null }, null)

private fun evidenceOf(action: String, vararg lanes: String = arrayOf("Lane")) =
    JudgeEvidence("Login", 1, 2, action, "The login screen is shown", emptyList(), emptyList(), lanes.map(::laneOf))

private fun linesOf(file: File): List<JsonObject> =
    file.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

/** What the fake model of a Codex turn is told, and which process, thread and turn it runs on. */
private class TurnInfo(val process: FakeProcess, val number: Int, val threadId: String, val turnId: String, val text: String)

/** One fake `codex app-server`; its managed MCP client is connected once per process, as a real Codex does. */
private class FakeProcess(val index: Int, val process: ScriptedCodexProcess, command: List<String>, environment: Map<String, String>) {
    private val url = command.windowed(2).map { it[1] }.single { it.startsWith("mcp_servers.indagium.url=") }.substringAfter("=").trim('"')
    private val token = environment.getValue("INDAGIUM_MCP_TOKEN")
    val mcp: FakeAgentMcp by lazy { FakeAgentMcp(url, token).also { it.start() } }
    val threads = CopyOnWriteArrayList<String>()
    val turns = CopyOnWriteArrayList<String>()
}

/** Fake Codex processes: what they answer, which requests they saw, and whether they take and settle an interrupt. */
private class FakeCodexFarm {
    val processes = CopyOnWriteArrayList<FakeProcess>()
    val interrupts = CopyOnWriteArrayList<String>()
    val turnStarts = AtomicInteger()

    @Volatile var initializeDelayMs = 0L

    @Volatile var answerInterrupt = true

    @Volatile var answerThreadStart = true

    @Volatile var endTurnOnInterrupt = true

    /** What the model of turn [TurnInfo.number] does; the default submits a passing verdict (or a comparison) and talks on. */
    @Volatile var onTurn: (TurnInfo) -> Unit = { turn ->
        if (turn.text.contains("Compare 2 lanes")) turn.process.mcp.call("submit_comparison", COMPARE_ARGUMENTS)
        else turn.process.mcp.call("submit_verdict", PASS_ARGUMENTS)
    }

    fun launch(command: List<String>, environment: Map<String, String>, @Suppress("UNUSED_PARAMETER") scope: CoroutineScope): CodexAppServerClient {
        val index = processes.size + 1
        lateinit var fake: FakeProcess
        val process = ScriptedCodexProcess { process, line -> handle(fake, process, line) }
        fake = FakeProcess(index, process, command, environment)
        processes += fake
        return CodexAppServerClient(process)
    }

    private fun handle(fake: FakeProcess, process: ScriptedCodexProcess, line: String) {
        val message = Json.parseToJsonElement(line).jsonObject
        val id = message["id"]
        val params = message["params"]?.jsonObject
        when (message["method"]?.jsonPrimitive?.content) {
            "initialize" -> thread {
                Thread.sleep(initializeDelayMs)
                process.send("""{"id":$id,"result":{"serverInfo":{"name":"codex","version":"1"}}}""")
            }
            "thread/start" -> {
                val threadId = "thread-${fake.index}-${fake.threads.size + 1}"
                fake.threads += threadId
                if (answerThreadStart) process.send("""{"id":$id,"result":{"thread":{"id":"$threadId"}}}""")
            }
            "turn/start" -> startTurn(fake, process, id.toString(), params!!)
            "turn/interrupt" -> {
                val threadId = params!!.getValue("threadId").jsonPrimitive.content
                val turnId = params.getValue("turnId").jsonPrimitive.content
                interrupts += "$threadId/$turnId"
                if (answerInterrupt) process.send("""{"id":$id,"result":{}}""")
                if (endTurnOnInterrupt) process.send(turnCompleted(threadId, turnId, "interrupted"))
            }
        }
    }

    private fun startTurn(fake: FakeProcess, process: ScriptedCodexProcess, id: String, params: JsonObject) {
        val threadId = params.getValue("threadId").jsonPrimitive.content
        val turnId = "turn-${fake.index}-${fake.turns.size + 1}"
        fake.turns += turnId
        val text = params.getValue("input").toString()
        val number = turnStarts.incrementAndGet()
        process.send("""{"id":$id,"result":{"turn":{"id":"$turnId","threadId":"$threadId","status":{"type":"inProgress"}}}}""")
        process.send("""{"method":"turn/started","params":{"turn":{"id":"$turnId","threadId":"$threadId","status":"inProgress"}}}""")
        thread { runCatching { onTurn(TurnInfo(fake, number, threadId, turnId, text)) } }
    }

    companion object {
        fun turnCompleted(threadId: String, turnId: String, status: String) =
            """{"method":"turn/completed","params":{"threadId":"$threadId","turn":{"id":"$turnId","status":{"type":"$status"}}}}"""
    }
}

/** A Codex judge is a process kept for the judges of one lane: launched once, one thread per judge, tools routed to the judge that is on. */
class CodexJudgeHostTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private val farm = FakeCodexFarm()
    private val hosts = CopyOnWriteArrayList<CodexJudgeHost>()
    private var harness: RunHarness? = null
    private val services = CopyOnWriteArrayList<JudgeService>()

    @BeforeTest
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("codex-judge-host").toFile()
        state = AppState(autosaveFile = File(dir, "state.cache"), autoExportNotes = false, notesDir = File(dir, "notes"), testingDir = File(dir, "testing"))
    }

    @AfterTest
    fun tearDown() {
        harness?.close()
        services.forEach { runCatching { it.close() } }
        hosts.forEach { it.close() }
        farm.processes.forEach { it.process.destroy() }
        state.close()
        dir.deleteRecursively()
    }

    private val readyCheck = { _: AiProviderProfile -> AccountCliCheck(true, "") }

    private fun runners(settleMs: Long = SETTLE_MS) = object : AccountRunnerFactory {
        override fun create(request: AgentSegmentRequest): AccountAgentRunner = AccountAgentRunner(
            managedMcpServerFactory = { run -> ManagedMcpServerLease.start(state, run, request.gateway) },
            maxToolRounds = request.toolCallLimit,
            maxTurns = request.maxTurns,
            promptPreamble = request.promptPreamble,
            cliCheck = readyCheck,
            codexLauncher = { command, environment, scope -> farm.launch(command, environment, scope) },
        )

        override fun judgeHost(profile: AiProviderProfile): CodexJudgeHost = CodexJudgeHost(
            profile = profile,
            routeLeaseFactory = { route -> ManagedMcpServerLease.startSwitchable(state, route) },
            cliCheck = readyCheck,
            codexLauncher = { command, environment, scope -> farm.launch(command, environment, scope) },
            interruptSettleMs = settleMs,
        ).also { hosts += it }
    }

    private fun service(settleMs: Long = SETTLE_MS, file: File = File(dir, TEST_RUN_JUDGE_FILE_NAME)): JudgeService =
        JudgeService(
            "run-1", "suite-1", AccountLaneAgent(codexProfile, runners(settleMs)), FAST_TUNING, TranscriptWriter(file), { _, _ -> null },
            System::currentTimeMillis,
        ).also { services += it }

    private fun judge(service: JudgeService, action: String = "Open the app", lane: String = "lane-1") =
        runBlocking { withTimeout(AWAIT_MS) { service.judgeStep(lane, site, evidenceOf(action)) } }

    private fun awaitUntil(condition: () -> Boolean) = runBlocking {
        withTimeout(AWAIT_MS) { while (!condition()) delay(POLL_MS) }
    }

    private fun finishedLines(file: File = File(dir, TEST_RUN_JUDGE_FILE_NAME)) = linesOf(file).filter { it.text("kind") == "judge_finished" }

    // ── One process, one thread per judge ───────────────────────────

    @Test
    fun twoJudgesOfALaneStartOneProcessAndOneEphemeralThreadEach() {
        farm.initializeDelayMs = SLOW_START_MS
        val service = service()

        val first = judge(service)
        val second = judge(service)

        assertEquals(JudgeVerdict.PASS, first.verdict, first.toString())
        assertEquals(JudgeVerdict.PASS, second.verdict, second.toString())
        assertEquals(1, farm.processes.size, "one process for both judges")
        val process = farm.processes.single()
        assertEquals(2, process.threads.size, "a new thread per judge")
        assertEquals(2, process.turns.size)
        assertEquals(2, farm.interrupts.size, "each judge's talking turn was interrupted once its verdict was in")
        val finished = finishedLines()
        assertEquals(listOf("false", "true"), finished.map { it.text("hostReused") }, "the second judge took over the process")
        val firstStartup = assertNotNull(finished[0].long("startupMs"))
        val secondStartup = assertNotNull(finished[1].long("startupMs"))
        assertTrue(firstStartup >= SLOW_START_MS, "the first judge paid for launching Codex: $firstStartup")
        assertTrue(secondStartup < firstStartup, "the second only started a thread: $secondStartup vs $firstStartup")
        assertTrue(finished.all { it.text("partial") == "false" }, finished.toString())
    }

    @Test
    fun theImagesOfAJudgeLiveInItsOwnDirectoryAndGoAwayWithIt() {
        val paths = CopyOnWriteArrayList<String>()
        farm.onTurn = { turn ->
            Regex(""""path":"([^"]+)"""").findAll(turn.text).forEach { paths += it.groupValues[1] }
            turn.process.mcp.call("submit_verdict", PASS_ARGUMENTS)
        }
        val service = service()

        judge(service)
        judge(service)

        assertEquals(2, paths.size, paths.toString())
        assertTrue(paths[0].substringBeforeLast('/') != paths[1].substringBeforeLast('/'), "a directory per judge: $paths")
        awaitUntil { paths.none { File(it).exists() } }
    }

    // ── The gateway switches with the judge ─────────────────────────

    @Test
    fun eachJudgeSeesOnlyItsOwnEvidenceAndSpendsOnlyItsOwnBudget() {
        val briefs = CopyOnWriteArrayList<List<String>>()
        farm.onTurn = { turn ->
            // Three evidence calls and the verdict: with one budget shared by both judges (4) the second judge would be refused.
            briefs += List(BRIEF_CALLS) { turn.process.mcp.call("get_step_brief") }
            turn.process.mcp.call("submit_verdict", PASS_ARGUMENTS)
        }
        val service = service()

        judge(service, action = "Open the ALPHA screen")
        judge(service, action = "Open the BETA screen")

        assertEquals(2, briefs.size)
        assertTrue(briefs[0].all { it.contains("ALPHA") && !it.contains("BETA") }, briefs[0].toString())
        assertTrue(briefs[1].all { it.contains("BETA") && !it.contains("ALPHA") }, briefs[1].toString())
        assertTrue(briefs[0].last().contains("3/4 analysis") && briefs[1].last().contains("3/4 analysis"), "a budget of its own each: $briefs")
        assertEquals(1, farm.processes.size)
    }

    @Test
    fun aToolCallThatArrivesAfterTheJudgeEndedIsRefused() {
        val service = service()
        judge(service, action = "Open the ALPHA screen")

        val late = farm.processes.single().mcp.call("get_step_brief")

        assertTrue(late.contains("not active"), late)
        assertFalse(late.contains("ALPHA"), "no evidence is given to a judge that is over: $late")
    }

    // ── Failures ────────────────────────────────────────────────────

    @Test
    fun aProcessThatExitsDuringAJudgeIsReplacedForTheNextJudge() {
        farm.onTurn = { turn ->
            if (turn.number == 1) turn.process.process.destroy() else turn.process.mcp.call("submit_verdict", PASS_ARGUMENTS)
        }
        val service = service()

        val first = judge(service)
        val second = judge(service)

        assertEquals(JudgeVerdict.INCONCLUSIVE, first.verdict)
        assertTrue(first.error.orEmpty().contains("exited"), first.toString())
        assertEquals(JudgeVerdict.PASS, second.verdict, second.toString())
        assertEquals(2, farm.processes.size, "the second judge got a fresh process")
        assertFalse(farm.processes[0].process.isAlive(), "the broken one is closed")
        assertEquals(listOf("false", "false"), finishedLines().map { it.text("hostReused") })
    }

    @Test
    fun aProcessThatExitsBetweenJudgesIsReplacedForTheNextJudge() {
        val service = service()
        judge(service)
        farm.processes.single().process.destroy()
        // The exit is noticed by the reader thread; give it the moment it takes.
        runBlocking { delay(SETTLE_MS) }

        val second = judge(service)

        assertEquals(JudgeVerdict.PASS, second.verdict, second.toString())
        assertEquals(2, farm.processes.size)
    }

    @Test
    fun aCancelledJudgeInterruptsItsTurnAndTheProcessSurvivesForTheNextJudge() {
        farm.onTurn = { turn -> if (turn.number > 1) turn.process.mcp.call("submit_verdict", PASS_ARGUMENTS) }
        val service = service()

        runBlocking {
            val running = launch { service.judgeStep("lane-1", site, evidenceOf("Open the app")) }
            withTimeout(AWAIT_MS) { while (farm.turnStarts.get() < 1) delay(POLL_MS) }
            running.cancelAndJoin()
        }
        val second = judge(service)

        assertEquals("thread-1-1/turn-1-1", farm.interrupts.first(), "the cancelled judge's turn was interrupted: ${farm.interrupts}")
        assertEquals(JudgeVerdict.PASS, second.verdict, second.toString())
        assertEquals(1, farm.processes.size, "the process outlived the interrupt")
        assertTrue(farm.processes.single().process.isAlive())
        assertEquals(listOf("thread-1-1", "thread-1-2"), farm.processes.single().threads)
    }

    @Test
    fun aTurnThatDoesNotEndAfterTheInterruptCostsTheProcess() {
        farm.endTurnOnInterrupt = false
        val service = service()

        judge(service)
        val second = judge(service)

        assertEquals(JudgeVerdict.PASS, second.verdict, second.toString())
        assertEquals(2, farm.processes.size, "the process that kept the turn running was dropped")
        assertFalse(farm.processes[0].process.isAlive())
    }

    @Test
    fun anInterruptThatIsNotAnsweredCostsTheProcess() {
        farm.answerInterrupt = false
        farm.endTurnOnInterrupt = false
        val service = service()

        val first = judge(service)
        val second = judge(service)

        assertEquals(JudgeVerdict.PASS, first.verdict, first.toString())
        assertEquals(JudgeVerdict.PASS, second.verdict, second.toString())
        assertEquals(2, farm.processes.size)
        assertFalse(farm.processes[0].process.isAlive())
    }

    @Test
    fun aRequestLeftUnansweredByACancelledJudgeCostsTheProcess() {
        // Codex does not answer thread/start in time: the judge is cancelled with the request outstanding, and the answer may still come.
        farm.answerThreadStart = false
        val service = service()
        runBlocking {
            val running = launch { service.judgeStep("lane-1", site, evidenceOf("Open the app")) }
            withTimeout(AWAIT_MS) { while (farm.processes.firstOrNull()?.threads?.isEmpty() != false) delay(POLL_MS) }
            running.cancelAndJoin()
        }
        farm.answerThreadStart = true

        val second = judge(service)

        assertEquals(JudgeVerdict.PASS, second.verdict, second.toString())
        assertEquals(2, farm.processes.size, "the process with a request nobody answered is not given to the next judge")
        assertFalse(farm.processes[0].process.isAlive())
    }

    // ── Lifetime ────────────────────────────────────────────────────

    @Test
    fun theEndOfTheLaneClosesTheProcessAndItsManagedServer() {
        val service = service()
        judge(service)
        val process = farm.processes.single()
        process.mcp.toolsList()

        runBlocking { service.releaseLane("lane-1") }

        assertFalse(process.process.isAlive(), "the Codex process was closed")
        assertFails { process.mcp.call("get_step_brief") }
        // A lane that judges again (it should not) simply gets a new host.
        assertEquals(JudgeVerdict.PASS, judge(service).verdict)
        assertEquals(2, farm.processes.size)
    }

    @Test
    fun closingTheRunClosesTheProcessesOfEveryLane() {
        val service = service()
        judge(service, lane = "lane-1")
        judge(service, lane = "lane-2")
        assertEquals(2, farm.processes.size, "a host per lane")
        assertEquals(1, farm.processes[0].threads.size)
        assertEquals(1, farm.processes[1].threads.size)

        runBlocking { service.shutdown() }

        assertTrue(farm.processes.none { it.process.isAlive() })
        assertEquals(2, hosts.size)
    }

    @Test
    fun closingTheRunWhileAJudgeIsOnClosesTheProcessToo() {
        farm.onTurn = { }
        val service = service()
        runBlocking {
            val running = launch { service.judgeStep("lane-1", site, evidenceOf("Open the app")) }
            withTimeout(AWAIT_MS) { while (farm.turnStarts.get() < 1) delay(POLL_MS) }
            running.cancelAndJoin()
            service.shutdown()
        }

        assertTrue(farm.processes.none { it.process.isAlive() })
    }

    // ── Who uses a host ─────────────────────────────────────────────

    @Test
    fun theComparisonJudgeKeepsAShortLivedRunOfItsOwn() {
        val service = service()
        judge(service)
        val lane = farm.processes.single()

        val comparison = runBlocking {
            withTimeout(AWAIT_MS) { service.compare("case-1", 1, "step-1", evidenceOf("Open the app", "Lane 1", "Lane 2")) }
        }

        assertEquals(mapOf("id-Lane 1" to JudgeVerdict.PASS, "id-Lane 2" to JudgeVerdict.FAIL), comparison.verdicts, comparison.toString())
        assertEquals(2, farm.processes.size, "the comparison started its own process")
        assertEquals(1, lane.threads.size, "and did not touch the lane's")
        assertTrue(lane.process.isAlive(), "the lane's process is still there for its next judge")
        awaitUntil { !farm.processes[1].process.isAlive() }
    }

    @Test
    fun onlyACodexAccountJudgeKeepsAProcess() {
        val factory = runners()
        assertNotNull(AccountLaneAgent(codexProfile, factory).reusableForLaneJudge())
        assertNull(AccountLaneAgent(claudeProfile, factory).reusableForLaneJudge(), "Claude Code is one process per request")
        assertNull(AccountLaneAgent(codexProfile, AccountRunnerFactory { error("not used") }).reusableForLaneJudge(), "no host without a factory for it")
        val http = ProviderLaneAgent(ScriptedJudgeProvider(), judgeProfile)
        assertNull(http.reusableForLaneJudge(), "an HTTP provider is already shared by the lane")
        assertEquals(1, hosts.size, "only the Codex one was built")
    }

    @Test
    fun anHttpJudgeIsJudgedExactlyAsBefore() {
        val service = JudgeService(
            "run-1", "suite-1", ProviderLaneAgent(ScriptedJudgeProvider(), judgeProfile), FAST_TUNING,
            TranscriptWriter(File(dir, "http.jsonl")), { _, _ -> null }, System::currentTimeMillis,
        ).also { services += it }

        val judgement = judge(service)

        assertEquals(JudgeVerdict.PASS, judgement.verdict, judgement.toString())
        assertEquals(listOf("false"), finishedLines(File(dir, "http.jsonl")).map { it.text("hostReused") })
        assertTrue(farm.processes.isEmpty())
    }

    // ── Through the real engine ─────────────────────────────────────

    @Test
    fun aRunWithTwoLanesKeepsOneProcessPerLaneAndClosesThemWhenItEnds() {
        val codexJudge = AiProviderProfile("codex-judge", "Codex judge", "", "", kind = AiProviderKind.CODEX_ACCOUNT)
        val codexAgents = AccountLaneAgent(codexJudge, runners())
        val suite = suiteOf(caseOf("Login", step("Open the app"), step("Look at the home screen")))
        val farmDevices = DeviceFarm()
        val h = RunHarness(
            libraryOf(suite),
            extraProfiles = listOf(profileOf(LANE_A_PROFILE_ID), profileOf(LANE_B_PROFILE_ID), codexJudge),
            agentFactory = LaneAgentFactory { chosen, key ->
                if (chosen.id == codexJudge.id) {
                    codexAgents
                } else {
                    agentsByProfile(
                        mapOf(
                            LANE_A_PROFILE_ID to AutoAgentProvider(listOf("pass" to "ok", "pass" to "ok")),
                            LANE_B_PROFILE_ID to AutoAgentProvider(listOf("pass" to "ok", "pass" to "ok")),
                        ),
                    ).create(chosen, key)
                }
            },
            openDevice = farmDevices.opener,
        ).also { harness = it }
        val config = h.config(suite, agentLane(LANE_A_PROFILE_ID, "SER-A"), agentLane(LANE_B_PROFILE_ID, "SER-B"))
            .copy(judgeProfileId = codexJudge.id, judgeMode = JudgeMode.EVERY_STEP.wire)
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })

        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        assertEquals(RunStatus.PASSED, run.status, run.toString())
        assertEquals(2, farm.processes.size, "one Codex process per lane, not one per judged step")
        assertEquals(listOf(2, 2), farm.processes.map { it.threads.size }, "each judged both of its steps")
        val finished = finishedLines(File(h.store.runDir(run.id), TEST_RUN_JUDGE_FILE_NAME))
        assertEquals(4, finished.size)
        assertEquals(2, finished.count { it.text("hostReused") == "true" }, finished.toString())
        awaitUntil { farm.processes.none { it.process.isAlive() } }
    }
}
