package com.indagium.testing

import com.indagium.ai.LlmRole
import com.indagium.edition.EditionLimits
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.newCheckId
import com.indagium.testing.model.newExampleId
import com.indagium.testing.model.newHookId
import com.indagium.testing.model.newSharedStepId
import com.indagium.testing.model.newStepId
import com.indagium.testing.run.JUDGE_TOOL_CALL_BUDGET
import com.indagium.testing.run.LaneStepStatus
import com.indagium.testing.run.StartRunResult
import com.indagium.testing.run.settleWithJudge
import com.indagium.testing.run.shouldJudge
import com.indagium.testing.run.validateRun
import com.indagium.testing.store.TEST_RUN_JUDGE_FILE_NAME
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val AWAIT_MS = 15_000L
private const val SECRET_OBSERVATION = "AGENT-SECRET-OBSERVATION-4711"
private const val SECOND_SECRET = "AGENT-SECOND-OBSERVATION-0815"

/** The judge never sees what the agent claimed or reported, and the judge's checks and verdict rules hold. */
class JudgeBlindnessTest {
    private var harness: RunHarness? = null

    /** The harness of the running test, for the model's hooks (they run while [start] is still waiting for the run). */
    private val current = java.util.concurrent.atomic.AtomicReference<RunHarness>()

    @AfterTest
    fun tearDown() {
        harness?.close()
    }

    private val screenJudge = StepCheck.ScreenJudge(newCheckId(), "The login title is visible")
    private val askJudge = StepCheck.AskJudge(newCheckId(), "Does the screen look tidy?")
    private val reference = StepExample.ReferenceLog(newExampleId(), "I/App: ready", "Ready line")

    private fun judgedStep(): TestStep = TestStep(
        newStepId(), "Open the app", "The login screen is shown",
        checks = listOf(logAppears("Login ok"), screenJudge, askJudge), examples = listOf(reference),
        timeoutMs = 30_000L, retries = 0, maxToolCalls = 15,
    )

    private fun suite(vararg steps: TestStep): TestSuite = suiteOf(caseOf("Login", *steps))

    private fun start(
        suite: TestSuite,
        agent: AutoAgentProvider,
        judge: ScriptedJudgeProvider,
        mode: JudgeMode = JudgeMode.EVERY_STEP,
        judgeProfileId: String? = JUDGE_PROFILE_ID,
        tuning: com.indagium.testing.run.EngineTuning = FAST_TUNING,
    ): Pair<RunHarness, TestRun> {
        val h = RunHarness(
            libraryOf(suite),
            profile = profileOf(LANE_A_PROFILE_ID),
            extraProfiles = listOf(judgeProfile),
            agentFactory = agentsByProfile(mapOf(LANE_A_PROFILE_ID to agent, JUDGE_PROFILE_ID to judge)),
            tuning = tuning,
        ).also {
            harness = it
            current.set(it)
        }
        val config: RunConfig = h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(judgeProfileId = judgeProfileId, judgeMode = mode.wire)
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        return h to runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }
    }

    private fun TestRun.steps(): List<StepResult> = lanes.single().cases.first { it.caseName == "Login" }.steps

    // ── Blindness ───────────────────────────────────────────────────

    @Test
    fun theJudgeNeverSeesTheAgentsObservationOrClaimInItsPromptOrAnyToolResult() {
        val agent = AutoAgentProvider(listOf("pass" to SECRET_OBSERVATION, "fail" to SECOND_SECRET)) { answered ->
            if (answered == 0) current.get().emitLog("Login ok")
        }
        val judge = ScriptedJudgeProvider { JudgeAnswer(verdict = "pass", reasoning = "It matches.") }
        val suite = suite(judgedStep(), step("Look at the home screen"))
        val (h, run) = start(suite, agent, judge)

        assertTrue(judge.requests.isNotEmpty(), "the judge ran")
        val seen = judge.everythingSeen()
        assertFalse(seen.contains(SECRET_OBSERVATION), "the first observation reached the judge:\n$seen")
        assertFalse(seen.contains(SECOND_SECRET), "the second observation reached the judge:\n$seen")
        val toolResults = judge.requests.flatMap { it.messages }.filter { it.role == LlmRole.TOOL }.joinToString("\n") { it.content.orEmpty() }
        assertFalse(toolResults.contains("agentClaim") || toolResults.contains("observation"), "no result field carries the agent's words:\n$toolResults")
        val prompts = judge.requests.map { r -> r.messages.filter { it.role != LlmRole.TOOL }.joinToString("\n") { it.content.orEmpty() } }
        assertTrue(prompts.none { it.contains(SECRET_OBSERVATION) || it.contains(SECOND_SECRET) })
        assertTrue(judge.requests.any { r -> r.messages.any { it.images.isNotEmpty() } }, "the judge was shown the screenshot")

        // The agent's words are in the report, so the guarantee above is about what the judge is given, not about what exists.
        assertEquals(SECRET_OBSERVATION, run.steps().first().observation.lines().last())
        val transcript = File(h.store.runDir(run.id), TEST_RUN_JUDGE_FILE_NAME)
        assertTrue(transcript.isFile, "the judge's transcript is judge.jsonl in the run folder")
        assertFalse(transcript.readText().contains(SECRET_OBSERVATION), "judge.jsonl holds only what the judge saw and said")
        assertTrue(transcript.readText().contains("submit_verdict"))
    }

    @Test
    fun theBriefHoldsTheStepsAskAndEvidenceButNoStatusOrAgentFields() {
        val agent = AutoAgentProvider(listOf("pass" to SECRET_OBSERVATION)) { answered -> if (answered == 0) current.get().emitLog("Login ok") }
        val judge = ScriptedJudgeProvider()
        start(suite(judgedStep()), agent, judge)

        val brief = judge.requests.flatMap { it.messages }.first { it.role == LlmRole.TOOL }.content.orEmpty()
        assertTrue(brief.contains("Open the app") && brief.contains("The login screen is shown"), brief)
        val questions = brief.contains("The login title is visible") && brief.contains("Does the screen look tidy?")
        assertTrue(questions, "the judge checks are the judge's questions: $brief")
        assertTrue(brief.contains(reference.id) && brief.contains("Ready line"), "example metadata: $brief")
        assertTrue(brief.contains("untrusted_data"), "check details came from the device and are fenced: $brief")
        assertFalse(brief.contains("status=") && brief.contains("PASS"), "no step status: $brief")
    }

    // ── Judge checks are evaluated ──────────────────────────────────

    @Test
    fun screenAndAskJudgeChecksTakeTheJudgesVerdictInsteadOfBeingNotEvaluated() {
        val agent = AutoAgentProvider(listOf("pass" to "fine")) { answered -> if (answered == 0) current.get().emitLog("Login ok") }
        val (_, run) = start(suite(judgedStep()), agent, ScriptedJudgeProvider { JudgeAnswer(verdict = "pass", reasoning = "Title is visible.") })

        val result = run.steps().single()
        assertEquals(StepStatus.PASS, result.status, result.toString())
        assertEquals(listOf(CheckStatus.PASS, CheckStatus.PASS, CheckStatus.PASS), result.checks.map { it.status })
        assertTrue(result.checks.drop(1).all { it.detail.contains("judge") }, result.checks.toString())
        assertEquals(JudgeVerdict.PASS, assertNotNull(result.judge).verdict)
        assertFalse(result.judgeInconclusive)
    }

    @Test
    fun explicitJudgeChecksCannotRunWithoutAConfiguredJudge() {
        val agent = AutoAgentProvider(listOf("pass" to "fine")) { answered -> if (answered == 0) current.get().emitLog("Login ok") }
        val suite = suite(judgedStep())
        val h = RunHarness(
            libraryOf(suite),
            profile = profileOf(LANE_A_PROFILE_ID),
            agentFactory = agentsByProfile(mapOf(LANE_A_PROFILE_ID to agent)),
            tuning = FAST_TUNING,
        ).also { harness = it; current.set(it) }
        val result = runBlocking {
            h.coordinator.start(h.config(suite, agentLane(LANE_A_PROFILE_ID)).copy(judgeMode = JudgeMode.OFF.wire, judgeProfileId = null))
        }
        val rejected = assertIs<StartRunResult.Rejected>(result)
        assertTrue(rejected.errors.any { it.contains("explicit ScreenJudge/AskJudge") }, rejected.errors.toString())
        assertTrue(agent.requests.isEmpty(), "validation happens before the agent starts")
    }

    @Test
    fun judgeValidationIncludesReachableSharedHooksAndOnlySelectedSteps() {
        val shared = com.indagium.testing.model.SharedStep(newSharedStepId(), "Authentication", steps = listOf(judgedStep()))
        val withHook = caseOf("Uses shared setup", step("Check home"), setup = listOf(HookItem.Shared(newHookId(), shared.id)))
        val suite = suiteOf(withHook)
        val library = TestLibrary(suites = listOf(suite), sharedSteps = listOf(shared))
        val config = RunConfig(suite.id, listOf(withHook.id), listOf(externalLane()))

        val hooked = validateRun(config, library, EditionLimits.UNLIMITED, emptyList()) { "" }
        assertTrue(hooked.errors.any { it.contains("explicit ScreenJudge/AskJudge") && it.contains("Authentication") }, hooked.errors.toString())

        val earlier = step("Stop before the explicit check")
        val later = judgedStep()
        val selected = caseOf("Selected", earlier, later)
        val unselected = caseOf("Unselected but judged", judgedStep())
        val trimmedSuite = suiteOf(selected, unselected)
        val trimmedLibrary = TestLibrary(suites = listOf(trimmedSuite))
        val trimmed = RunConfig(
            trimmedSuite.id,
            listOf(selected.id),
            listOf(externalLane()),
            stopAfterStepId = earlier.id,
        )
        val accepted = validateRun(trimmed, trimmedLibrary, EditionLimits.UNLIMITED, emptyList()) { "" }
        assertFalse(accepted.errors.any { it.contains("explicit ScreenJudge/AskJudge") }, accepted.errors.toString())
    }

    @Test
    fun anInconclusiveJudgeBlocksExplicitJudgeChecksButOptionalJudgingStillPasses() {
        val judge = ScriptedJudgeProvider { JudgeAnswer("inconclusive", "unknown", "The screenshot is unclear.") }
        val judgeOnly = judgedStep().copy(checks = listOf(screenJudge, askJudge))
        val (_, checkedRun) = start(suite(judgeOnly), AutoAgentProvider(listOf("pass" to "fine")), judge)
        val checked = checkedRun.steps().single()
        assertEquals(StepStatus.BLOCKED, checked.status)
        assertEquals(listOf(CheckStatus.NOT_EVALUATED, CheckStatus.NOT_EVALUATED), checked.checks.map { it.status })
        assertTrue(checked.note.orEmpty().contains("explicit judge checks remain unresolved", ignoreCase = true), checked.note.orEmpty())

        val (_, optionalRun) = start(suite(step("No explicit judge check")), AutoAgentProvider(listOf("pass" to "fine")), judge)
        val optional = optionalRun.steps().single()
        assertEquals(StepStatus.PASS, optional.status)
        assertTrue(optional.judgeInconclusive)
    }

    @Test
    fun deterministicFailureStaysFailWhenTheAgentBlocksAndExplicitJudgeIsInconclusive() {
        val judge = ScriptedJudgeProvider { JudgeAnswer("inconclusive", "unknown", "The screenshot is unclear.") }
        val checks = listOf(logAppears("must not appear", withinMs = 100), screenJudge)
        val deterministicFailure = TestStep(newStepId(), "Check unavailable message", "Message appears", checks = checks, retries = 0)
        val (_, run) = start(suite(deterministicFailure), AutoAgentProvider(listOf("blocked" to "Cannot continue")), judge)
        val result = run.steps().single()

        assertEquals(StepStatus.FAIL, result.status)
        assertEquals(CheckStatus.FAIL, result.checks.first().status)
        assertEquals(CheckStatus.NOT_EVALUATED, result.checks.last().status)
        assertEquals(JudgeVerdict.INCONCLUSIVE, assertNotNull(result.judge).verdict)
        assertTrue(result.note.orEmpty().contains("deterministic failures still determine"))
    }

    // ── Verdict rules ───────────────────────────────────────────────

    @Test
    fun aJudgeFailOverridesAnAgentPassAndKeepsTheClassificationAndFix() {
        val fix = buildJsonObject { put("expected", "The login screen with a title is shown") }
        val judge = ScriptedJudgeProvider { JudgeAnswer("fail", "app_defect", "The title is missing.", fix) }
        val (_, run) = start(suite(step("Open the app"), step("Two")), AutoAgentProvider(listOf("pass" to "all good")), judge)

        val first = run.steps().first()
        assertEquals(StepStatus.FAIL, first.status)
        assertEquals("pass", first.agentClaim, "the agent's claim is kept as reported")
        val verdict = assertNotNull(first.judge)
        assertEquals(JudgeVerdict.FAIL, verdict.verdict)
        assertEquals(JudgeClassification.APP_DEFECT, verdict.classification)
        assertEquals("The login screen with a title is shown", verdict.suggestedFix?.expected)
        assertTrue(first.note.orEmpty().contains("judge failed a step the agent reported as passed"), first.note)
        assertEquals(RunStatus.FAILED, run.status)
    }

    @Test
    fun anInconclusiveJudgeKeepsTheAgentsClaimAndMarksTheResult() {
        val judge = ScriptedJudgeProvider { JudgeAnswer("inconclusive", "unknown", "The screenshot is blank.") }
        val (_, run) = start(suite(step("Open the app")), AutoAgentProvider(listOf("pass" to "ok")), judge)

        val result = run.steps().single()
        assertEquals(StepStatus.PASS, result.status)
        assertTrue(result.judgeInconclusive)
        assertEquals(JudgeVerdict.INCONCLUSIVE, assertNotNull(result.judge).verdict)
        assertEquals(RunStatus.PASSED, run.status)
    }

    @Test
    fun aJudgeCanNeverTurnADeterministicFailureOrAnAgentFailIntoAPass() {
        val judge = ScriptedJudgeProvider { JudgeAnswer("pass", "unknown", "Looks fine to me.") }
        val failing = TestStep(newStepId(), "Open the app", "Opened", checks = listOf(logAppears("never logged", withinMs = 200)), retries = 0)
        val (_, run) = start(suite(failing, step("Two")), AutoAgentProvider(listOf("pass" to "ok", "fail" to "broken")), judge)

        val steps = run.steps()
        assertEquals(StepStatus.FAIL, steps.first().status, "a failed log check stays a failure although the judge passed")
        assertEquals(JudgeVerdict.PASS, assertNotNull(steps.first().judge).verdict, "the judge still judged it (every step)")
        assertEquals(RunStatus.FAILED, run.status)
    }

    @Test
    fun aJudgeThatNeverAnswersIsInconclusiveWithTheReasonAndTheRunGoesOn() {
        val mute = object : com.indagium.ai.LlmProvider {
            override val capabilities = com.indagium.ai.ProviderCapabilities(streaming = true, toolCalls = true, modelDiscovery = false)

            override suspend fun listModels() = com.indagium.ai.ModelDiscoveryResult.Unavailable("not used")

            override fun streamChat(request: com.indagium.ai.LlmRequest) = kotlinx.coroutines.flow.flow<com.indagium.ai.LlmStreamEvent> {
                emit(com.indagium.ai.LlmStreamEvent.TextDelta("I have no opinion."))
                emit(com.indagium.ai.LlmStreamEvent.Completed)
            }
        }
        val h = RunHarness(
            libraryOf(suite(step("Open the app"))),
            profile = profileOf(LANE_A_PROFILE_ID),
            extraProfiles = listOf(judgeProfile),
            agentFactory = agentsByProfile(mapOf(LANE_A_PROFILE_ID to AutoAgentProvider(listOf("pass" to "ok")), JUDGE_PROFILE_ID to mute)),
        ).also { harness = it }
        val suiteUnderTest = h.library.suites.single()
        val config = h.config(suiteUnderTest, agentLane(LANE_A_PROFILE_ID)).copy(judgeProfileId = JUDGE_PROFILE_ID, judgeMode = JudgeMode.EVERY_STEP.wire)
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        val result = run.steps().single()
        assertEquals(StepStatus.PASS, result.status)
        assertTrue(result.judgeInconclusive)
        assertTrue(assertNotNull(result.judge).error.orEmpty().contains("without submitting"), result.judge.toString())
        assertEquals(RunStatus.PASSED, run.status)
    }

    // ── When the judge runs ─────────────────────────────────────────

    @Test
    fun failuresOnlyJudgesFailedOrBlockedStepsAndStepsWithJudgeChecksButNotPlainPasses() {
        val judge = ScriptedJudgeProvider()
        val (_, run) = start(
            suite(step("Plain pass"), step("Agent says fail"), step("Agent says blocked"), step("Plain pass again")),
            AutoAgentProvider(listOf("pass" to "ok", "fail" to "no", "blocked" to "cannot")),
            judge,
            mode = JudgeMode.FAILURES_ONLY,
        )
        // STOP_CASE on the failure ends the case after step 2: only the failing step was judged.
        assertEquals(listOf(false, true), run.steps().take(2).map { it.judge != null })
        assertEquals(listOf(JudgeVerdict.PASS), listOf(assertNotNull(run.steps()[1].judge).verdict))

        val checked = start(suite(judgedStep()), AutoAgentProvider(listOf("pass" to "ok")), ScriptedJudgeProvider(), mode = JudgeMode.FAILURES_ONLY).second
        assertNotNull(checked.steps().single().judge, "a step with judge checks needs the judge even when everything passed")
    }

    @Test
    fun theRulesArePureAndDocumented() {
        val plain = step("A")
        val pass = StepJudgement(verdict = JudgeVerdict.PASS)
        val fail = StepJudgement(verdict = JudgeVerdict.FAIL)
        val unsure = StepJudgement(verdict = JudgeVerdict.INCONCLUSIVE)

        assertFalse(shouldJudge(JudgeMode.OFF, plain, LaneStepStatus.FAIL, true))
        assertTrue(shouldJudge(JudgeMode.EVERY_STEP, plain, LaneStepStatus.PASS, false))
        assertFalse(shouldJudge(JudgeMode.FAILURES_ONLY, plain, LaneStepStatus.PASS, false))
        assertTrue(shouldJudge(JudgeMode.FAILURES_ONLY, plain, LaneStepStatus.PASS, true))
        assertTrue(shouldJudge(JudgeMode.FAILURES_ONLY, plain, LaneStepStatus.BLOCKED, false))

        assertEquals(StepStatus.PASS, settleWithJudge(StepStatus.PASS, null).status)
        assertEquals(StepStatus.BLOCKED, settleWithJudge(StepStatus.PASS, null, requiresVerdict = true).status)
        assertEquals(StepStatus.FAIL, settleWithJudge(StepStatus.PASS, fail).status)
        assertEquals(StepStatus.PASS, settleWithJudge(StepStatus.PASS, pass).status)
        assertTrue(settleWithJudge(StepStatus.PASS, unsure).inconclusive)
        assertEquals(StepStatus.PASS, settleWithJudge(StepStatus.PASS, unsure).status)
        assertEquals(StepStatus.BLOCKED, settleWithJudge(StepStatus.PASS, unsure, requiresVerdict = true).status)
        assertEquals(StepStatus.FAIL, settleWithJudge(StepStatus.FAIL, pass).status)
        assertEquals(StepStatus.BLOCKED, settleWithJudge(StepStatus.BLOCKED, pass).status)
        assertFalse(settleWithJudge(StepStatus.FAIL, unsure).inconclusive)
        assertTrue(JUDGE_TOOL_CALL_BUDGET in 6..10, "the judge's budget is small")
    }
}
