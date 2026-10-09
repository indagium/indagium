package com.indagium.testing

import com.indagium.ai.AiRun
import com.indagium.ai.AiRunEvent
import com.indagium.ai.AiUsageAggregation
import com.indagium.ai.LlmToolCall
import com.indagium.ai.aiRunTiming
import com.indagium.ai.logSummary
import com.indagium.model.AiRunTiming
import com.indagium.model.summaryLabel
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.run.AgentSegmentRequest
import com.indagium.testing.run.EngineTuning
import com.indagium.testing.run.JudgeEvidence
import com.indagium.testing.run.JudgeImage
import com.indagium.testing.run.JudgeLaneEvidence
import com.indagium.testing.run.JudgeService
import com.indagium.testing.run.JudgeSite
import com.indagium.testing.run.LaneAgent
import com.indagium.testing.run.ProviderLaneAgent
import com.indagium.testing.run.StartRunResult
import com.indagium.testing.run.TranscriptTail
import com.indagium.testing.run.toMarkdown
import com.indagium.testing.store.TEST_RUN_JUDGE_FILE_NAME
import com.indagium.testing.store.TranscriptWriter
import com.indagium.testing.store.decodeRunFile
import com.indagium.testing.store.encodeRunFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
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
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CLOCK_START_MS = 1_000L
private const val CLOCK_STEP_MS = 7L
private const val AWAIT_MS = 15_000L
private const val POLL_MS = 20L

/** The scripted judge needs three responses to its verdict; the model may start a fourth before the engine cancels the run. */
private val MODEL_TURNS_TO_VERDICT = 3L..4L

/** A clock that moves forward by [CLOCK_STEP_MS] every time anybody reads it, from any thread. */
private class TickingClock : () -> Long {
    private val now = AtomicLong(CLOCK_START_MS)

    override fun invoke(): Long = now.getAndAdd(CLOCK_STEP_MS)
}

/** A clock a test sets by hand: the run reads whatever [now] holds. */
private class SetClock(var now: Long = 0L) : () -> Long {
    override fun invoke(): Long = now
}

private fun lines(file: File): List<JsonObject> = file.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun call(name: String) = LlmToolCall("call-$name", name, "{}")

/** Synthetic evidence of one lane; the judge is shown a four-byte "screenshot" (the scripted judge never decodes it). */
private fun evidence() = JudgeEvidence(
    caseName = "Login",
    stepNumber = 1,
    stepCount = 2,
    action = "Open the app",
    expected = "The login screen is shown",
    judgeChecks = emptyList(),
    examples = emptyList(),
    lanes = listOf(JudgeLaneEvidence("Lane", "", emptyList(), { JudgeImage(ByteArray(4), "image/jpeg") }, { _, _ -> null }, null)),
)

private val site = JudgeSite("case-1", "c1-i1", "step-1", 1, 1)

/** Timestamps of events, the breakdown of a judge run and where both end up: judge.jsonl, run.json, the reports. */
class JudgeTimingTest {
    private lateinit var dir: File
    private var harness: RunHarness? = null

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("judge-timing").toFile()
    }

    @AfterTest
    fun tearDown() {
        harness?.close()
        dir.deleteRecursively()
    }

    private fun service(agent: LaneAgent, clock: () -> Long, file: File, tuning: EngineTuning = FAST_TUNING) =
        JudgeService("run-1", "suite-1", agent, tuning, TranscriptWriter(file, clock), { _, _ -> null }, clock)

    // ── Emit-time timestamps ────────────────────────────────────────

    @Test
    fun anEventIsStampedWhenTheRunEmitsItNotWhenTheTailWritesIt() = runBlocking {
        val runClock = SetClock()
        val run = AiRun(tabId = "tab", clock = runClock)
        runClock.now = 1_000L
        run.emit(AiRunEvent.Status("Starting…"))
        runClock.now = 2_500L
        run.emit(AiRunEvent.ToolRequested(call("get_step_brief")))
        runClock.now = 4_000L
        run.emit(AiRunEvent.Cancelled)

        val file = File(dir, "tail.jsonl")
        val writerClock = SetClock(99_999L)
        TranscriptTail(run, TranscriptWriter(file, writerClock)).close()

        val written = lines(file)
        assertEquals(listOf("status", "tool_call", "cancelled"), written.map { it.text("kind") })
        assertEquals(listOf(1_000L, 2_500L, 4_000L), written.map { it.long("t") }, "the lines carry the emit times, not the late write time")
        assertEquals(listOf(1_000L, 2_500L, 4_000L), run.timedHistory.map { it.at })
        assertEquals(4_000L, run.completedAt)
        assertEquals(2_500L, run.firstResponseAt)
    }

    @Test
    fun aJudgeRunsLinesFollowTheRunWithIncreasingTimesAndEndWithTheBreakdown() = runBlocking {
        val clock = TickingClock()
        val file = File(dir, TEST_RUN_JUDGE_FILE_NAME)
        val provider = ScriptedJudgeProvider { JudgeAnswer(verdict = "pass", reasoning = "It matches.") }
        val judge = service(ProviderLaneAgent(provider, judgeProfile, clock), clock, file)

        val judgement = judge.judgeStep("lane-1", site, evidence())

        assertEquals(JudgeVerdict.PASS, judgement.verdict)
        val written = lines(file)
        val times = written.map { it.long("t") ?: error("no t in $it") }
        assertEquals(times.sorted(), times, "the lines are in time order")
        assertTrue(times.toSet().size > written.size / 2, "the times are real emit times, not one finish time: $times")
        assertEquals("judge_started", written.first().text("kind"))
        val finished = written.last()
        assertEquals("judge_finished", finished.text("kind"))
        assertTrue(finished.long("modelTurns") in MODEL_TURNS_TO_VERDICT, "brief, screenshot, verdict, and maybe one more before the cancel: $finished")
        assertEquals(3L, finished.long("toolCalls"))
        assertEquals("PASS", finished.text("verdict"))
        assertEquals("false", finished.text("partial"))
        val startup = assertNotNull(finished.long("startupMs"))
        val first = assertNotNull(finished.long("firstResponseMs"))
        val verdict = assertNotNull(finished.long("verdictMs"))
        val total = assertNotNull(finished.long("totalMs"))
        assertTrue(startup <= first && first <= verdict && verdict <= total, "startup <= first response <= verdict <= total: $finished")
        assertEquals(total - verdict, finished.long("cleanupMs"))
        assertTrue(written.any { it.text("kind") == "tool_call" && it.text("tool") == "submit_verdict" })

        val timing = assertNotNull(judgement.timing)
        assertEquals(total, timing.totalMs)
        assertTrue(timing.modelTurns.toLong() in MODEL_TURNS_TO_VERDICT)
        assertFalse(timing.partial)
    }

    // ── The breakdown from a scripted run ───────────────────────────

    @Test
    fun anInAppModelsRunIsReadyAtOnceAndEachCompletedStreamIsATurn() = runBlocking {
        val clock = SetClock(10_000L)
        val run = AiRun(tabId = "tab", clock = clock)
        run.markAgentReady()
        clock.now = 10_200L
        run.emit(AiRunEvent.Status("Generating response…"))
        clock.now = 11_000L
        run.emit(AiRunEvent.ToolRequested(call("get_step_brief")))
        run.markModelResponse()
        clock.now = 12_500L
        run.emit(AiRunEvent.ToolRequested(call("get_step_screenshot")))
        run.markModelResponse()
        clock.now = 14_000L
        run.emit(AiRunEvent.ToolRequested(call("submit_verdict")))
        run.markModelResponse()
        clock.now = 14_300L

        val timing = aiRunTiming(run, endedAt = 14_900L, answerToolNames = setOf("submit_verdict"))

        assertEquals(
            AiRunTiming(
                startupMs = 0L, firstResponseMs = 1_000L, modelTurns = 3, toolCalls = 3, verdictMs = 4_000L, totalMs = 4_900L,
                inputTokens = null, outputTokens = null, partial = false,
            ),
            timing,
        )
        assertEquals("4.9 s (3 model turns)", timing.summaryLabel())
    }

    @Test
    fun anAccountAgentsRunSeparatesStartupFromTheModelsTurnsAndReadsTokensFromTheCumulativeReports() = runBlocking {
        val clock = SetClock(50_000L)
        val run = AiRun(tabId = "tab", clock = clock)
        clock.now = 50_100L
        run.emit(AiRunEvent.Status("Checking Codex…"))
        clock.now = 53_400L
        run.emit(AiRunEvent.Status("Indagium MCP: ready"))
        run.markAgentReady()
        clock.now = 53_500L
        run.emit(AiRunEvent.Status("Codex is investigating…"))
        run.markAgentReady() // a second ready signal changes nothing
        clock.now = 55_000L
        run.emit(usage(14_069L, 84L))
        run.markModelResponse()
        run.emit(AiRunEvent.ToolRequested(call("get_step_brief")))
        clock.now = 58_000L
        run.emit(usage(37_714L, 146L))
        run.markModelResponse()
        run.emit(AiRunEvent.ToolRequested(call("submit_verdict")))
        clock.now = 59_000L
        run.emit(AiRunEvent.Cancelled)

        val timing = aiRunTiming(run, endedAt = 59_500L, answerToolNames = setOf("submit_verdict"), partial = false)

        assertEquals(3_400L, timing.startupMs, "from creation to the managed MCP server being ready")
        assertEquals(5_000L, timing.firstResponseMs)
        assertEquals(2, timing.modelTurns)
        assertEquals(2, timing.toolCalls)
        assertEquals(8_000L, timing.verdictMs)
        assertEquals(9_500L, timing.totalMs)
        assertEquals(37_714L, timing.inputTokens, "cumulative reports are not added up")
        assertEquals(146L, timing.outputTokens)
        val line = timing.logSummary()
        assertTrue(line.contains("startup 3.4 s") && line.contains("2 model turns") && line.contains("total 9.5 s"), line)
    }

    @Test
    fun aRunThatNeverGotReadyOrAnsweredHasNullPartsAndNoVerdict() = runBlocking {
        val clock = SetClock(1_000L)
        val run = AiRun(tabId = "tab", clock = clock)
        clock.now = 1_300L
        run.emit(AiRunEvent.Status("Checking Codex…"))

        val timing = aiRunTiming(run, endedAt = 2_000L, answerToolNames = setOf("submit_verdict"), partial = true)

        assertNull(timing.startupMs)
        assertNull(timing.firstResponseMs)
        assertNull(timing.verdictMs)
        assertEquals(0, timing.modelTurns)
        assertEquals(1_000L, timing.totalMs)
        assertTrue(timing.partial)
        assertTrue(timing.summaryLabel().endsWith("partial)"), timing.summaryLabel())
    }

    private fun usage(input: Long, output: Long) = AiRunEvent.Usage(
        inputTokens = input,
        outputTokens = output,
        totalTokens = input + output,
        requestId = "thread-1",
        aggregation = AiUsageAggregation.RUN_CUMULATIVE,
    )

    // ── Judges that do not finish ───────────────────────────────────

    /** An agent whose run says it is working and then never answers, until it is cancelled. */
    private class HangingAgent(private val clock: () -> Long) : LaneAgent {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        override fun start(request: AgentSegmentRequest): AiRun {
            val run = AiRun(tabId = request.session.tabId, clock = clock)
            run.job = scope.launch {
                run.emit(AiRunEvent.Status("Starting Codex…"))
                run.markAgentReady()
                run.emit(AiRunEvent.AgentProgress("Looking at the brief"))
                run.emit(AiRunEvent.ToolRequested(call("get_step_brief")))
                run.markModelResponse()
                try {
                    awaitCancellation()
                } catch (cancelled: CancellationException) {
                    run.emit(AiRunEvent.Cancelled)
                    throw cancelled
                }
            }
            return run
        }

        override fun close() = scope.cancel()
    }

    @Test
    fun aCancelledJudgeStillWritesWhatItDidAndAPartialFinishedLine() = runBlocking {
        val clock = TickingClock()
        val file = File(dir, TEST_RUN_JUDGE_FILE_NAME)
        val judge = service(HangingAgent(clock), clock, file, FAST_TUNING.copy(judgeTimeoutMs = AWAIT_MS * 4))

        val job = launch(Dispatchers.Default) { judge.judgeStep("lane-1", site, evidence()) }
        withTimeout(AWAIT_MS) {
            while (!file.isFile || !file.readText().contains("get_step_brief")) delay(POLL_MS)
        }
        job.cancelAndJoin()

        val written = lines(file)
        val kinds = written.map { it.text("kind") }
        assertTrue("status" in kinds && "tool_call" in kinds && "cancelled" in kinds, kinds.toString())
        val finished = written.last()
        assertEquals("judge_finished", finished.text("kind"))
        assertEquals("true", finished.text("partial"))
        assertNull(finished.text("verdict"))
        assertNull(finished.long("verdictMs"))
        assertEquals(1L, finished.long("modelTurns"))
        assertEquals(1L, finished.long("toolCalls"))
        assertNotNull(finished.long("startupMs"))
        assertEquals(1, kinds.count { it == "judge_finished" })
    }

    @Test
    fun aJudgeThatCannotStartWritesAPartialFinishedLineWithTheReason() = runBlocking {
        val clock = TickingClock()
        val file = File(dir, TEST_RUN_JUDGE_FILE_NAME)
        val agent = object : LaneAgent {
            override fun start(request: AgentSegmentRequest): AiRun = error("no judge available")

            override fun close() = Unit
        }

        val judgement = service(agent, clock, file).judgeStep("lane-1", site, evidence())

        assertEquals(JudgeVerdict.INCONCLUSIVE, judgement.verdict)
        val timing = assertNotNull(judgement.timing)
        assertTrue(timing.partial)
        assertEquals(0, timing.modelTurns)
        val finished = lines(file).last()
        assertEquals("judge_finished", finished.text("kind"))
        assertEquals("true", finished.text("partial"))
        assertTrue(finished.text("error").orEmpty().contains("no judge available"), finished.toString())
    }

    // ── Persisted timing ────────────────────────────────────────────

    private fun judgedResult(timing: AiRunTiming?) = StepResult(
        stepId = "step-1", stepNumber = 1, action = "Open the app", status = StepStatus.PASS,
        judge = StepJudgement(verdict = JudgeVerdict.PASS, reasoning = "ok", durationMs = 4_200L, timing = timing),
    )

    private fun runWith(result: StepResult): TestRun {
        val suite = suiteOf(caseOf("Login", step("Open the app")))
        val lane = LaneResult("lane-1", agentLane(), cases = listOf(CaseResult(suite.cases.single().id, "Login", steps = listOf(result))))
        return TestRun("run-1", suite, emptyList(), emptyList(), RunConfig(suite.id, null, listOf(agentLane())), listOf(lane))
    }

    @Test
    fun theJudgeTimingSurvivesTheRunFileAndAnOldFileWithoutItStillLoads() {
        val timing = AiRunTiming(startupMs = 2_100L, firstResponseMs = 3_000L, modelTurns = 2, toolCalls = 3, verdictMs = 4_000L, totalMs = 4_200L,
            inputTokens = 100L, outputTokens = 20L)
        val encoded = encodeRunFile(runWith(judgedResult(timing)))

        val decoded = decodeRunFile(encoded).getOrThrow().lanes.single().cases.single().steps.single()
        assertEquals(timing, decoded.judge?.timing)
        assertTrue(encoded.contains("\"judgeMs\": 4200") && encoded.contains("\"judgeModelTurns\": 2"), "the step carries the figures the report shows")

        val oldFile = encodeRunFile(runWith(judgedResult(null)))
        assertFalse(oldFile.contains("\"timing\""), "no timing, no key: the shape of a file written before timing existed")
        val old = decodeRunFile(oldFile).getOrThrow().lanes.single().cases.single().steps.single()
        assertEquals(JudgeVerdict.PASS, old.judge?.verdict)
        assertNull(old.judge?.timing)

        val malformed = oldFile.replace("\"verdict\": \"PASS\"", "\"verdict\": \"PASS\", \"timing\": \"fast\"")
        assertNull(decodeRunFile(malformed).getOrThrow().lanes.single().cases.single().steps.single().judge?.timing, "a mistyped timing is ignored")
    }

    @Test
    fun theMarkdownReportShowsTheJudgeTimeOfAStep() {
        val timing = AiRunTiming(modelTurns = 2, toolCalls = 3, totalMs = 4_200L)
        val markdown = runWith(judgedResult(timing)).toMarkdown()
        assertTrue(markdown.contains("Judge time: 4.2 s (2 model turns)"), markdown)
    }

    // ── Through the real engine ─────────────────────────────────────

    @Test
    fun aJudgedRunRecordsTheBreakdownInJudgeJsonlRunJsonAndTheLaneTranscript() {
        val agent = AutoAgentProvider(listOf("pass" to "It opened"))
        val judge = ScriptedJudgeProvider { JudgeAnswer(verdict = "pass", reasoning = "It matches.") }
        val suite = suiteOf(caseOf("Login", step("Open the app")))
        val h = RunHarness(
            libraryOf(suite),
            profile = profileOf(LANE_A_PROFILE_ID),
            extraProfiles = listOf(judgeProfile),
            agentFactory = agentsByProfile(mapOf(LANE_A_PROFILE_ID to agent, JUDGE_PROFILE_ID to judge)),
        ).also { harness = it }
        val config = h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(judgeProfileId = JUDGE_PROFILE_ID, judgeMode = JudgeMode.EVERY_STEP.wire)
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        assertEquals(RunStatus.PASSED, run.status, run.toString())
        val timing = assertNotNull(run.allSteps().single().judge?.timing)
        assertTrue(timing.modelTurns.toLong() in MODEL_TURNS_TO_VERDICT, timing.toString())
        assertEquals(3, timing.toolCalls)
        assertFalse(timing.partial)

        val runDir = h.store.runDir(run.id)
        val finished = lines(File(runDir, TEST_RUN_JUDGE_FILE_NAME)).single { it.text("kind") == "judge_finished" }
        assertEquals(timing.totalMs, finished.long("totalMs"))
        assertEquals(timing.modelTurns.toLong(), finished.long("modelTurns"))

        val transcript = File(runDir, assertNotNull(run.lanes.single().transcriptPath))
        val stepTiming = lines(transcript).single { it.text("kind") == "finish_step_timing" }
        assertNotNull(stepTiming.long("evidenceCaptureMs"))
        assertNotNull(stepTiming.long("deterministicChecksMs"))
        assertNotNull(stepTiming.long("judgeMs"), "a judged step reports the judge's share of finish_step")
        assertEquals(
            stepTiming.long("evidenceCaptureMs")!! + stepTiming.long("deterministicChecksMs")!! + stepTiming.long("judgeMs")!!,
            stepTiming.long("totalMs"),
        )
        assertEquals(1L, stepTiming.long("stepNumber"))
    }
}
