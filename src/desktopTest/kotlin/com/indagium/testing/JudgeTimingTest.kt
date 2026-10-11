package com.indagium.testing

import com.indagium.ai.AiRun
import com.indagium.ai.AiRunEvent
import com.indagium.ai.AiUsageAggregation
import com.indagium.ai.LlmStreamEvent
import com.indagium.ai.LlmToolCall
import com.indagium.ai.aiRunTiming
import com.indagium.ai.logSummary
import com.indagium.model.AiRunTiming
import com.indagium.model.summaryLabel
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.JudgeTiming
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.VerificationState
import com.indagium.testing.model.newCaseId
import com.indagium.testing.model.newCheckId
import com.indagium.testing.run.AgentSegmentRequest
import com.indagium.testing.run.EngineTuning
import com.indagium.testing.run.JudgeConcurrencyLimiter
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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

/** The scripted judge has its evidence in the first message: one response is its verdict; the model may start a second before the engine cancels the run. */
private val MODEL_TURNS_TO_VERDICT = 1L..2L

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
        assertTrue(finished.long("modelTurns") in MODEL_TURNS_TO_VERDICT, "the verdict, and maybe one more before the cancel: $finished")
        assertEquals(1L, finished.long("toolCalls"), "only submit_verdict: the evidence came with the first message")
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
        assertEquals(1, timing.toolCalls)
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
            stepTiming.long("executionDurationMs")!! + stepTiming.long("queueWaitMs")!! + stepTiming.long("judgeMs")!!,
            stepTiming.long("totalMs"),
        )
        assertEquals(run.allSteps().single().durationMs, stepTiming.long("executionDurationMs"))
        assertEquals(1L, stepTiming.long("stepNumber"))
    }

    @Test
    fun allJudgmentSchedulesFinishRequestedEveryStepVerification() {
        for (timingMode in JudgeTiming.entries) {
            harness?.close()
            val agent = AutoAgentProvider(listOf("pass" to "It opened"))
            val judge = ScriptedJudgeProvider { JudgeAnswer(verdict = "pass", reasoning = "It matches.") }
            val suite = suiteOf(caseOf("Login", step("Open the app")))
            val h = RunHarness(
                libraryOf(suite),
                profile = profileOf(LANE_A_PROFILE_ID),
                extraProfiles = listOf(judgeProfile),
                agentFactory = agentsByProfile(mapOf(LANE_A_PROFILE_ID to agent, JUDGE_PROFILE_ID to judge)),
            ).also { harness = it }
            val config = h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(
                judgeProfileId = JUDGE_PROFILE_ID,
                judgeMode = JudgeMode.EVERY_STEP.wire,
                judgeTiming = timingMode,
            )
            val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
            val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

            assertEquals(RunStatus.PASSED, run.status, "$timingMode: $run")
            val stepResult = run.allSteps().single()
            assertEquals(StepStatus.PASS, stepResult.status, timingMode.name)
            assertEquals(VerificationState.VERIFIED, stepResult.verificationState, timingMode.name)
            val transcript = lines(File(h.store.runDir(run.id), assertNotNull(run.lanes.single().transcriptPath)))
            val kind = if (timingMode == JudgeTiming.INLINE) "inline_judgment_finished" else "deferred_judgment_finished"
            val timingRecord = transcript.single { it.text("kind") == kind }
            assertNotNull(timingRecord.long("queueWaitMs"), "$timingMode records queue delay independently")
            assertNotNull(timingRecord.long("judgmentMs"), "$timingMode records evaluation duration")
            assertEquals(timingMode.wire, run.config.judgeTiming.wire)
        }
    }

    @Test
    fun slowInlineJudgmentDoesNotInflateStepExecutionDuration() {
        val agent = AutoAgentProvider(listOf("pass" to "The screen is ready"))
        val judge = ScriptedJudgeProvider(responseDelayMs = 500L) { JudgeAnswer(verdict = "pass", reasoning = "It matches.") }
        val suite = suiteOf(caseOf("Timed judgment", step("Open the app")))
        val h = RunHarness(
            libraryOf(suite),
            profile = profileOf(LANE_A_PROFILE_ID),
            extraProfiles = listOf(judgeProfile),
            agentFactory = agentsByProfile(mapOf(LANE_A_PROFILE_ID to agent, JUDGE_PROFILE_ID to judge)),
        ).also { harness = it }
        val config = h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(
            judgeProfileId = JUDGE_PROFILE_ID,
            judgeMode = JudgeMode.EVERY_STEP.wire,
            judgeTiming = JudgeTiming.INLINE,
        )
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }
        val stepResult = run.allSteps().single()
        val judgmentMs = assertNotNull(stepResult.judgmentDurationMs)

        assertEquals(StepStatus.PASS, stepResult.status)
        assertTrue(judgmentMs >= 450L, "the provider held the judgment for 500 ms; reported $judgmentMs ms")
        assertTrue(
            stepResult.durationMs < judgmentMs,
            "step duration is execution only (${stepResult.durationMs} ms), independent of the $judgmentMs ms judgment",
        )
        val transcript = lines(File(h.store.runDir(run.id), assertNotNull(run.lanes.single().transcriptPath)))
        val timing = transcript.single { it.text("kind") == "finish_step_timing" }
        assertNotNull(timing.long("executionCompletedAtMs"))
        assertNotNull(timing.long("verificationCompletedAtMs"))
        assertTrue(
            assertNotNull(timing.long("executionCompletedAtMs")) <= assertNotNull(timing.long("verificationCompletedAtMs")),
            "verification completes after execution",
        )
    }

    @Test
    fun afterCaseWaitsForTeardownWhileExplicitAskJudgeRemainsInline() {
        val marker = File(dir, "case-teardown-finished")
        val script = hostScript("mark_teardown", "printf done > '${marker.absolutePath}'")
        val observedTeardown = CopyOnWriteArrayList<Boolean>()
        val agent = TurnProvider(listOf(finishTurn("pass", "ordinary action"), finishTurn("pass", "explicit checkpoint"), textTurn))
        val judge = ScriptedJudgeProvider(onSubmitted = { observedTeardown += marker.exists() })
        val suite = suiteOf(
            caseOf(
                "Boundary",
                step("Ordinary action"),
                step("Explicit checkpoint", checks = listOf(StepCheck.AskJudge(newCheckId(), "Is the screen ready?"))),
                teardown = listOf(scriptHook(script)),
            ),
        )
        val h = RunHarness(
            libraryOf(suite, script),
            profile = profileOf(LANE_A_PROFILE_ID),
            extraProfiles = listOf(judgeProfile),
            agentFactory = agentsByProfile(mapOf(LANE_A_PROFILE_ID to agent, JUDGE_PROFILE_ID to judge)),
        ).also { harness = it }
        val config = h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(
            judgeProfileId = JUDGE_PROFILE_ID,
            judgeMode = JudgeMode.EVERY_STEP.wire,
            judgeTiming = JudgeTiming.AFTER_CASE,
        )
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        assertEquals(RunStatus.PASSED, run.status, run.toString())
        assertTrue(marker.exists(), "teardown completed")
        assertEquals(listOf(false, true), observedTeardown, "Ask judge is inline, ordinary judgment starts after teardown")
        assertEquals(2, observedTeardown.size)
    }

    @Test
    fun backgroundJudgmentsOverlapLaterActionsAndDrainPastTheEightItemQueue() {
        val firstJudgeWaiting = CompletableDeferred<Unit>()
        val releaseFirstJudge = CompletableDeferred<Unit>()
        val completed = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val laterActionOverlapped = AtomicInteger()
        val submissions = AtomicInteger()
        val judge = ScriptedJudgeProvider(
            onStarted = {
                active.incrementAndGet().also { now -> peak.updateAndGet { prior -> maxOf(prior, now) } }
            },
            onCompleted = { active.decrementAndGet(); completed.incrementAndGet() },
            beforeSubmit = {
                if (submissions.incrementAndGet() == 1) {
                    firstJudgeWaiting.complete(Unit)
                    releaseFirstJudge.await()
                }
            },
        )
        val turns: List<Turn> = buildList {
            add(finishTurn("pass", "first action"))
            add {
                withTimeout(AWAIT_MS) { firstJudgeWaiting.await() }
                if (completed.get() == 0) laterActionOverlapped.set(1)
                emit(LlmStreamEvent.ToolCall(LlmToolCall("later-action", "finish_step", """{"status":"pass","observation":"action during judge"}""")))
                emit(LlmStreamEvent.Completed)
            }
            repeat(8) { index -> add(finishTurn("pass", "action ${index + 3}")) }
            add(textTurn)
        }
        val agent = TurnProvider(turns)
        val steps = List(10) { step("Action ${it + 1}") }
        val suite = suiteOf(TestCase(newCaseId(), "Queue", steps = steps))
        val h = RunHarness(
            libraryOf(suite),
            profile = profileOf(LANE_A_PROFILE_ID),
            extraProfiles = listOf(judgeProfile),
            agentFactory = agentsByProfile(mapOf(LANE_A_PROFILE_ID to agent, JUDGE_PROFILE_ID to judge)),
        ).also { harness = it }
        val config = h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(
            judgeProfileId = JUDGE_PROFILE_ID,
            judgeMode = JudgeMode.EVERY_STEP.wire,
            judgeTiming = JudgeTiming.BACKGROUND,
        )
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        runBlocking { withTimeout(AWAIT_MS) { firstJudgeWaiting.await() } }
        val transcriptPath = assertNotNull(h.coordinator.run(started.runId)?.lanes?.single()?.transcriptPath)
        val transcriptFile = File(h.store.runDir(started.runId), transcriptPath)
        try {
            runBlocking {
                withTimeout(AWAIT_MS) {
                    while (lines(transcriptFile).count { it.text("kind") == "deferred_judgment_queued" } < 10) delay(POLL_MS)
                }
            }
            assertEquals(10, agent.requests.size, "all ten finishes reached the full background queue")
            val waitingRun = assertNotNull(h.coordinator.run(started.runId))
            assertEquals(RunStatus.RUNNING, waitingRun.status)
            assertEquals(9, waitingRun.caseResult("Queue").steps.count { !it.setup }, "the tenth finish waits on queue capacity")
        } finally {
            releaseFirstJudge.complete(Unit)
        }
        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        assertEquals(RunStatus.PASSED, run.status, run.toString())
        assertEquals(1, laterActionOverlapped.get(), "the lane completed another action while its first judgment was active")
        assertEquals(1, peak.get(), "a single lane serializes access to its judge process")
        assertEquals(10, submissions.get(), "each requested judgment eventually submitted once after capacity was released")
        assertTrue(run.caseResult("Queue").steps.filterNot { it.setup }.all { it.verificationState == VerificationState.VERIFIED })
        val transcript = lines(File(h.store.runDir(run.id), assertNotNull(run.lanes.single().transcriptPath)))
        assertEquals(10, transcript.count { it.text("kind") == "deferred_judgment_queued" })
        assertEquals(10, transcript.count { it.text("kind") == "deferred_judgment_finished" })
    }

    @Test
    fun cancellingAnActiveBackgroundJudgeLeavesItsVerificationPending() {
        val judgeStarted = CompletableDeferred<Unit>()
        val agent = TurnProvider(listOf(finishTurn("pass", "agent completed"), textTurn))
        val judge = ScriptedJudgeProvider(responseDelayMs = 60_000L, onStarted = { judgeStarted.complete(Unit) })
        val suite = suiteOf(caseOf("Cancel judge", step("Open the app")))
        val h = RunHarness(
            libraryOf(suite),
            profile = profileOf(LANE_A_PROFILE_ID),
            extraProfiles = listOf(judgeProfile),
            agentFactory = agentsByProfile(mapOf(LANE_A_PROFILE_ID to agent, JUDGE_PROFILE_ID to judge)),
        ).also { harness = it }
        val config = h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(
            judgeProfileId = JUDGE_PROFILE_ID,
            judgeMode = JudgeMode.EVERY_STEP.wire,
            judgeTiming = JudgeTiming.BACKGROUND,
        )
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })

        val run = runBlocking {
            withTimeout(AWAIT_MS) { judgeStarted.await() }
            assertTrue(h.coordinator.cancel(started.runId))
            withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) }
        }
        assertEquals(RunStatus.CANCELLED, run.status)
        val result = run.caseResult("Cancel judge").steps.single { !it.setup }
        assertEquals(StepStatus.PASS, result.status)
        assertEquals(VerificationState.PENDING, result.verificationState, "completed execution is retained without inventing a verdict")
    }

    @Test
    fun inlineJudgmentTakesPriorityAndTheLimiterCapsRunWideConcurrency() = runBlocking {
        val limiter = JudgeConcurrencyLimiter(maxConcurrent = 2)
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val thirdStarted = CompletableDeferred<Unit>()
        val inlineStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val releaseThird = CompletableDeferred<Unit>()
        val releaseInline = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val peak = AtomicInteger()

        suspend fun held(entered: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) {
            active.incrementAndGet().also { now -> peak.updateAndGet { prior -> maxOf(prior, now) } }
            entered.complete(Unit)
            try {
                release.await()
            } finally {
                active.decrementAndGet()
            }
        }

        val first = launch(start = CoroutineStart.UNDISPATCHED) { limiter.background("lane-a") { held(firstStarted, releaseFirst) } }
        val second = launch(start = CoroutineStart.UNDISPATCHED) { limiter.background("lane-b") { held(secondStarted, releaseSecond) } }
        withTimeout(AWAIT_MS) { firstStarted.await(); secondStarted.await() }
        val inline = launch(start = CoroutineStart.UNDISPATCHED) {
            limiter.inline("lane-c") { held(inlineStarted, releaseInline) }
        }
        val third = launch(start = CoroutineStart.UNDISPATCHED) { limiter.background("lane-d") { held(thirdStarted, releaseThird) } }

        releaseFirst.complete(Unit)
        withTimeout(AWAIT_MS) { inlineStarted.await() }
        assertFalse(thirdStarted.isCompleted, "background work yields to the already-waiting inline checkpoint")
        releaseInline.complete(Unit)
        withTimeout(AWAIT_MS) { thirdStarted.await() }
        releaseSecond.complete(Unit)
        releaseThird.complete(Unit)
        first.join()
        second.join()
        inline.join()
        third.join()

        assertEquals(2, peak.get(), "the shared run limiter never exceeds its two concurrent judge slots")
        assertEquals(0, active.get())
    }

    @Test
    fun deferredJudgmentWithNoCapturedImagesStillUsesItsFrozenTextEvidence() {
        val agent = AutoAgentProvider(listOf("pass" to "The expected screen is visible."))
        val judge = ScriptedJudgeProvider { JudgeAnswer(verdict = "pass", reasoning = "The bounded text evidence supports it.") }
        val suite = suiteOf(caseOf("Text evidence", step("Open the app", "The welcome screen appears")))
        val h = RunHarness(
            libraryOf(suite),
            profile = profileOf(LANE_A_PROFILE_ID),
            extraProfiles = listOf(judgeProfile),
            agentFactory = agentsByProfile(mapOf(LANE_A_PROFILE_ID to agent, JUDGE_PROFILE_ID to judge)),
        ).also { harness = it }
        h.adb.png = byteArrayOf(0) // Make screenshot decoding fail while logs and the expected text remain available.
        val config = h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(
            evidence = com.indagium.testing.model.EvidenceFlags(screenshots = false, logcat = true, transcript = true),
            judgeProfileId = JUDGE_PROFILE_ID,
            judgeMode = JudgeMode.EVERY_STEP.wire,
            judgeTiming = JudgeTiming.AFTER_CASE,
        )
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        val result = run.allSteps().single()
        assertEquals(VerificationState.VERIFIED, result.verificationState, result.toString())
        val written = lines(File(h.store.runDir(run.id), TEST_RUN_JUDGE_FILE_NAME))
        val finished = written.single { it.text("kind") == "judge_finished" }
        assertEquals("PASS", finished.text("verdict"))
        val deferred = lines(File(h.store.runDir(run.id), assertNotNull(run.lanes.single().transcriptPath)))
            .single { it.text("kind") == "deferred_judgment_finished" }
        assertNull(deferred.text("evidenceUnavailableReason"), "absence of an image is missing evidence, not a false budget overflow")
    }

    @Test
    fun deferredRetryVerdictsAndUsageUpdateTheExactHistoricalAttempt() {
        for (mode in listOf(JudgeMode.FAILURES_ONLY, JudgeMode.EVERY_STEP)) {
            harness?.close()
            val judgeCall = AtomicLong()
            val agent = TurnProvider(listOf(finishTurn("fail", "first execution failed"), finishTurn("pass", "retry passed"), textTurn))
            val judge = ScriptedJudgeProvider(answer = {
                val index = judgeCall.getAndIncrement()
                JudgeAnswer(
                    verdict = if (index == 0L) "fail" else "pass",
                    reasoning = "Verdict for judge call $index",
                    inputTokens = 100L * (index + 1),
                    outputTokens = 10L,
                )
            })
            val suite = suiteOf(caseOf("Retry", step("Open the app", retries = 1)))
            val h = RunHarness(
                libraryOf(suite),
                profile = profileOf(LANE_A_PROFILE_ID),
                extraProfiles = listOf(judgeProfile),
                agentFactory = agentsByProfile(mapOf(LANE_A_PROFILE_ID to agent, JUDGE_PROFILE_ID to judge)),
            ).also { harness = it }
            val config = h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(
                judgeProfileId = JUDGE_PROFILE_ID,
                judgeMode = mode.wire,
                judgeTiming = JudgeTiming.AFTER_CASE,
            )
            val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
            val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

            val case = run.caseResult("Retry")
            val stepResult = case.steps.single { !it.setup }
            val earlier = stepResult.attemptHistory.single { it.attempt == 1 }
            assertEquals(CaseStatus.PASS, case.status, "$mode: ${case.steps}")
            assertEquals(2, stepResult.attempts)
            assertEquals(StepStatus.FAIL, earlier.status)
            assertEquals(JudgeVerdict.FAIL, earlier.judge?.verdict, "$mode must update the prior attempt's deferred verdict")
            assertEquals(VerificationState.FAILED, earlier.verificationState)
            assertEquals(100L, earlier.judgeUsage?.inputTokens)
            assertNotNull(earlier.judgeQueueWaitMs, "$mode persists queue wait on the exact historical attempt")
            assertNotNull(earlier.judgmentDurationMs, "$mode persists judge duration separately from execution")
            when (mode) {
                JudgeMode.FAILURES_ONLY -> {
                    assertEquals(1L, judgeCall.get())
                    assertEquals(VerificationState.NOT_REQUESTED, stepResult.verificationState, "the recovered pass is not judged in failures-only mode")
                    assertEquals(null, stepResult.judge)
                    assertEquals(100L, case.judgeUsage?.inputTokens)
                }
                JudgeMode.EVERY_STEP -> {
                    assertEquals(2L, judgeCall.get())
                    assertEquals(JudgeVerdict.PASS, stepResult.judge?.verdict)
                    assertEquals(VerificationState.VERIFIED, stepResult.verificationState)
                    assertEquals(300L, case.judgeUsage?.inputTokens, "one prior-attempt verdict plus the final verdict, no double count")
                }
                JudgeMode.OFF -> error("not exercised")
            }
        }
    }

    @Test
    fun deferredJudgeFailureClearsFlakyAfterAnExecutionRetryPasses() {
        harness?.close()
        val judgeCall = AtomicLong()
        val agent = TurnProvider(listOf(finishTurn("fail", "first execution failed"), finishTurn("pass", "retry passed"), textTurn))
        val judge = ScriptedJudgeProvider(answer = {
            judgeCall.incrementAndGet()
            JudgeAnswer(verdict = "fail", reasoning = "The expected state was not present")
        })
        val suite = suiteOf(caseOf("Retry", step("Open the app", retries = 1)))
        val h = RunHarness(
            libraryOf(suite),
            profile = profileOf(LANE_A_PROFILE_ID),
            extraProfiles = listOf(judgeProfile),
            agentFactory = agentsByProfile(mapOf(LANE_A_PROFILE_ID to agent, JUDGE_PROFILE_ID to judge)),
        ).also { harness = it }
        val config = h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(
            judgeProfileId = JUDGE_PROFILE_ID,
            judgeMode = JudgeMode.EVERY_STEP.wire,
            judgeTiming = JudgeTiming.AFTER_CASE,
        )
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        val case = run.caseResult("Retry")
        val stepResult = case.steps.single { !it.setup }
        assertEquals(2L, judgeCall.get())
        assertEquals(StepStatus.FAIL, stepResult.status)
        assertFalse(stepResult.flaky, "the final deferred FAIL invalidates the execution-only recovered-pass marker")
        assertFalse(case.flaky)
    }
}
