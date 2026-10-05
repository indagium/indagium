package com.indagium.testing

import com.indagium.ai.AiRunEvent
import com.indagium.ai.LlmToolCall
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeComparison
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.StepFix
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.newCheckId
import com.indagium.testing.model.newExampleId
import com.indagium.testing.model.newStepId
import com.indagium.testing.run.PauseDecision
import com.indagium.testing.run.PausedStep
import com.indagium.testing.run.PausedStepInfo
import com.indagium.testing.run.PendingTestConfirmation
import com.indagium.testing.run.toolCallLines
import com.indagium.ui.ConsensusCell
import com.indagium.ui.ConsensusTone
import com.indagium.ui.DeviceChoice
import com.indagium.ui.EXTERNAL_LANE_LABEL
import com.indagium.ui.JUDGE_FEED_LIMIT
import com.indagium.ui.LIVE_TOOL_FEED_LINES
import com.indagium.ui.LaneChoice
import com.indagium.ui.LaneDraft
import com.indagium.ui.MatrixRow
import com.indagium.ui.RunDialogModel
import com.indagium.ui.buildMatrix
import com.indagium.ui.consensusFor
import com.indagium.ui.fixChanges
import com.indagium.ui.goldenExampleFor
import com.indagium.ui.judgeFeed
import com.indagium.ui.liveColumns
import com.indagium.ui.liveGridColumns
import com.indagium.ui.toConfig
import com.indagium.ui.withDefaultDevices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure state behind the live view, the judge feed, the consensus column, fix previews and the multi-lane run dialog. */
class TestRunLiveStateTest {
    private val profiles = listOf(
        AiProviderProfile("p1", "My Claude", "", "", kind = AiProviderKind.CLAUDE_CODE_ACCOUNT),
        AiProviderProfile("p2", "", "http://localhost:1234", "m", kind = AiProviderKind.OPENAI_COMPATIBLE),
    )
    private val first = step("Open")
    private val second = step("Close")
    private val third = step("Done")
    private val case = caseOf("Case A", first, second, third)
    private val suite = suiteOf(case)
    private val laneA = LaneConfig(kind = LaneKind.AGENT_PROFILE, profileId = "p1", deviceSerial = "SER-A")
    private val laneB = LaneConfig(kind = LaneKind.EXTERNAL, deviceSerial = "SER-B")

    private fun result(step: TestStep, number: Int, status: StepStatus, judge: StepJudgement? = null, shot: String? = null) =
        StepResult(step.id, number, step.action, status = status, judge = judge, screenshotPath = shot)

    private fun verdict(v: JudgeVerdict, at: Long, why: String = "Because.") = StepJudgement(verdict = v, reasoning = why, judgedAt = at)

    private fun run(vararg lanes: LaneResult, comparisons: List<JudgeComparison> = emptyList(), judge: Boolean = false) = TestRun(
        id = "run-1", suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
        config = RunConfig(suite.id, null, lanes.map { it.config }, judgeProfileId = if (judge) "p2" else null, judgeMode = if (judge) "every_step" else "off"),
        lanes = lanes.toList(), comparisons = comparisons,
    )

    private fun runningLaneA() = LaneResult(
        laneA.id, laneA, RunStatus.RUNNING,
        listOf(
            CaseResult(
                case.id, case.name, 1, null,
                listOf(
                    result(first, 1, StepStatus.PASS, shot = "lanes/a/screens/s1.png"),
                    result(second, 2, StepStatus.FAIL, shot = "lanes/a/screens/s2.png"),
                ),
            ),
        ),
        currentCase = "Case A", currentStepNumber = 3, currentStepAction = "Done",
    )

    // ── Live columns ────────────────────────────────────────────────

    @Test
    fun oneColumnPerLaneWithTheAgentsNameAChecklistAndTheLatestScreenshot() {
        val queued = LaneResult(laneB.id, laneB, RunStatus.QUEUED)
        val columns = liveColumns(run(runningLaneA(), queued), profiles, { emptyList() }, emptyList(), emptyList())

        assertEquals(listOf("My Claude", EXTERNAL_LANE_LABEL), columns.map { it.title })
        assertEquals(listOf("SER-A", "SER-B"), columns.map { it.deviceSerial })
        val a = columns.first()
        assertEquals("Case A", a.currentCase)
        assertEquals("Step 3: Done", a.currentStep)
        assertEquals(listOf(StepStatus.PASS, StepStatus.FAIL, null), a.steps.map { it.status })
        assertEquals(listOf(false, false, true), a.steps.map { it.current }, "step 3 has no result yet and is the one in progress")
        assertEquals("lanes/a/screens/s2.png", a.screenshotPath, "the newest screenshot")
        val b = columns.last()
        assertEquals(RunStatus.QUEUED, b.status)
        assertTrue(b.steps.isEmpty() && b.screenshotPath == null && b.currentStep == null)
    }

    @Test
    fun aBlankProfileNameFallsBackToItsKindAndAnUnknownProfileToItsId() {
        val onSecond = LaneConfig(kind = LaneKind.AGENT_PROFILE, profileId = "p2", deviceSerial = "SER-C")
        val gone = LaneConfig(kind = LaneKind.AGENT_PROFILE, profileId = "p-gone", deviceSerial = "SER-D")
        val columns = liveColumns(run(LaneResult(onSecond.id, onSecond), LaneResult(gone.id, gone)), profiles, { emptyList() }, emptyList(), emptyList())
        assertTrue(columns[0].title.startsWith("OpenAI-compatible"), columns[0].title)
        assertEquals("p-gone", columns[1].title)
    }

    @Test
    fun confirmationsAndPausesAreSortedIntoTheirLaneAndOtherRunsAreIgnored() {
        val mine = PendingTestConfirmation("run-1", laneA.id, "conf-1", "ask_me", "Run script ask_me")
        val other = PendingTestConfirmation("run-2", laneA.id, "conf-2", "ask_me", "Elsewhere")
        val otherLane = PendingTestConfirmation("run-1", laneB.id, "conf-3", "ask_me", "Lane B's")
        val paused = PausedStepInfo("run-1", laneB.id, PausedStep("Case A", 2, "Close", StepStatus.FAIL, "bad", emptyList()))
        val columns = liveColumns(
            run(runningLaneA(), LaneResult(laneB.id, laneB, RunStatus.RUNNING)), profiles, { emptyList() },
            listOf(mine, other, otherLane), listOf(paused),
        )
        assertEquals(listOf("conf-1"), columns[0].confirmations.map { it.confirmationId })
        assertEquals(listOf("conf-3"), columns[1].confirmations.map { it.confirmationId })
        assertNull(columns[0].pause)
        assertEquals(2, columns[1].pause!!.step.stepNumber)
        assertEquals(PauseDecision.entries.size, 3)
    }

    @Test
    fun theToolFeedKeepsTheLastFewCallsOfThatLane() {
        val many = (1..10).map { "tap $it" }
        val columns = liveColumns(run(runningLaneA()), profiles, { laneId -> if (laneId == laneA.id) many else emptyList() }, emptyList(), emptyList())
        assertEquals(many.takeLast(LIVE_TOOL_FEED_LINES), columns.single().toolCalls)

        val events = (1..4).map {
            val call = if (it == 2) LlmToolCall("c$it", "get_current_step", "{}") else LlmToolCall("c$it", "tap", """{"x":$it}""")
            AiRunEvent.ToolRequested(call)
        }
        assertEquals(listOf("get_current_step", "tap {\"x\":3}", "tap {\"x\":4}"), toolCallLines(events, 3))
    }

    @Test
    fun lanesWrapWhenTheWindowIsNarrow() {
        assertEquals(1, liveGridColumns(300f, 4))
        assertEquals(2, liveGridColumns(600f, 4))
        assertEquals(4, liveGridColumns(2_000f, 4), "never more columns than lanes")
        assertEquals(1, liveGridColumns(100f, 3), "always at least one")
        assertEquals(1, liveGridColumns(900f, 0))
    }

    // ── Judge feed ──────────────────────────────────────────────────

    @Test
    fun theJudgeFeedIsNewestFirstWithComparisonsAmongTheVerdicts() {
        val comparison = JudgeComparison(
            caseId = case.id, iteration = 1, stepId = second.id, stepNumber = 2, action = "Close",
            verdicts = mapOf(laneA.id to JudgeVerdict.PASS, laneB.id to JudgeVerdict.FAIL),
            classification = JudgeClassification.AGENT_OR_STEP_PROBLEM, explanation = "Lane 2 took another path.", judgedAt = 30L,
        )
        val a = LaneResult(
            laneA.id, laneA, RunStatus.RUNNING,
            listOf(
                CaseResult(
                    case.id, case.name, 1, null,
                    listOf(
                        result(first, 1, StepStatus.PASS, verdict(JudgeVerdict.PASS, 10L, "A one")),
                        result(second, 2, StepStatus.PASS, verdict(JudgeVerdict.PASS, 40L, "A two")),
                    ),
                ),
            ),
        )
        val b = LaneResult(
            laneB.id, laneB, RunStatus.RUNNING,
            listOf(CaseResult(case.id, case.name, 1, null, listOf(result(first, 1, StepStatus.FAIL, verdict(JudgeVerdict.FAIL, 20L, "B one"))))),
        )
        val feed = judgeFeed(run(a, b, comparisons = listOf(comparison), judge = true))

        assertEquals(listOf(40L, 30L, 20L, 10L), feed.map { it.at })
        assertEquals(listOf("Lane 1", "Comparison", "Lane 2", "Lane 1"), feed.map { it.lane })
        assertEquals(JudgeVerdict.FAIL, feed[1].verdict, "a comparison counts as failed when any lane failed")
        assertTrue(feed[1].comparison && feed[1].reasoning == "Lane 2 took another path.")
        assertEquals("Case A", feed[0].caseName)
        assertEquals(2, feed[0].stepNumber)
    }

    @Test
    fun theJudgeFeedIsBoundedAndItsReasoningIsAnExcerpt() {
        val steps = (1..(JUDGE_FEED_LIMIT + 5)).map { n -> result(first, n, StepStatus.PASS, verdict(JudgeVerdict.PASS, n.toLong(), "x".repeat(400))) }
        val lane = LaneResult(laneA.id, laneA, RunStatus.RUNNING, listOf(CaseResult(case.id, case.name, 1, null, steps)))
        val feed = judgeFeed(run(lane, judge = true))
        assertEquals(JUDGE_FEED_LIMIT, feed.size)
        assertEquals((JUDGE_FEED_LIMIT + 5).toLong(), feed.first().at)
        assertTrue(feed.first().reasoning.length < 200 && feed.first().reasoning.endsWith("…"))
        assertTrue(judgeFeed(run(runningLaneA())).isEmpty(), "no judge, no feed")
    }

    // ── Consensus ───────────────────────────────────────────────────

    private fun twoLaneRun(a: StepResult?, b: StepResult?, comparisons: List<JudgeComparison> = emptyList()): Pair<TestRun, MatrixRow.Step> {
        val laneOne = LaneResult(laneA.id, laneA, RunStatus.PASSED, listOf(CaseResult(case.id, case.name, 1, CaseStatus.PASS, listOfNotNull(a))))
        val laneTwo = LaneResult(laneB.id, laneB, RunStatus.PASSED, listOf(CaseResult(case.id, case.name, 1, CaseStatus.PASS, listOfNotNull(b))))
        val run = run(laneOne, laneTwo, comparisons = comparisons)
        return run to buildMatrix(run).filterIsInstance<MatrixRow.Step>().first { it.stepId == first.id }
    }

    @Test
    fun theConsensusColumnAddsUpTheLanes() {
        val pass = result(first, 1, StepStatus.PASS)
        val fail = result(first, 1, StepStatus.FAIL)

        fun cell(a: StepResult?, b: StepResult?, comparisons: List<JudgeComparison> = emptyList()): ConsensusCell {
            val (run, row) = twoLaneRun(a, b, comparisons)
            return consensusFor(run, row)
        }

        assertEquals(ConsensusTone.NONE, cell(null, null).tone)
        assertEquals("Lanes agree: Pass", cell(pass, pass).label)
        assertEquals(ConsensusTone.PASS, cell(pass, pass).tone)
        assertEquals(ConsensusTone.FAIL, cell(fail, fail).tone)
        assertEquals(ConsensusTone.DISAGREE, cell(pass, fail).tone)
        assertEquals(ConsensusTone.NONE, cell(pass, null).tone, "one lane alone has no consensus")

        val judgedPass = result(first, 1, StepStatus.PASS, verdict(JudgeVerdict.PASS, 1L))
        val judgedFail = result(first, 1, StepStatus.FAIL, verdict(JudgeVerdict.FAIL, 1L))
        assertEquals("Judge: pass", cell(judgedPass, judgedPass).label)
        assertEquals(ConsensusTone.PASS, cell(judgedPass, judgedPass).tone)
        assertEquals(ConsensusTone.FAIL, cell(judgedFail, judgedFail).tone)
        assertEquals("Judges differ", cell(judgedPass, judgedFail).label)
        assertEquals(ConsensusTone.UNSURE, cell(result(first, 1, StepStatus.PASS, verdict(JudgeVerdict.INCONCLUSIVE, 1L)), null).tone)

        val comparison = JudgeComparison(
            caseId = case.id, iteration = 1, stepId = first.id, stepNumber = 1, action = "Open", verdicts = emptyMap(),
            classification = JudgeClassification.APP_DEFECT,
        )
        assertEquals("Lanes differ: app defect", cell(judgedPass, judgedFail, listOf(comparison)).label, "a comparison wins and names its classification")
        val elsewhere = comparison.copy(stepId = second.id)
        assertEquals("Judges differ", cell(judgedPass, judgedFail, listOf(elsewhere)).label)
    }

    // ── Fixes and golden screenshots ────────────────────────────────

    @Test
    fun aFixPreviewListsOnlyTheFieldsThatWouldChange() {
        val current = TestStep("step-1", "Tap the button", "Something happens")
        assertEquals(
            listOf("Action" to "Tap the big button"),
            fixChanges(current, StepFix(action = "Tap the big button", expected = "Something happens")).map { it.field to it.after },
        )
        assertEquals(listOf("Expected result"), fixChanges(current, StepFix(expected = "A dialog opens")).map { it.field })
        assertEquals(listOf("Tap the button"), fixChanges(current, StepFix(action = "Tap the big button")).map { it.before })
        assertTrue(fixChanges(current, StepFix(action = "Tap the button", note = "advice")).isEmpty())
        assertTrue(fixChanges(current, StepFix(note = "only advice")).isEmpty())
        assertTrue(fixChanges(current, StepFix(action = "  ")).isEmpty())
    }

    @Test
    fun theGoldenScreenshotIsTheOneAScreenJudgeNamesElseTheFirst() {
        val golden1 = StepExample.GoldenScreenshot(newExampleId(), "a.png", "A")
        val golden2 = StepExample.GoldenScreenshot(newExampleId(), "b.png", "B")
        val log = StepExample.ReferenceLog(newExampleId(), "I/App: ok")
        val plain = TestStep(newStepId(), "x", examples = listOf(log, golden1, golden2))
        assertEquals(golden1, goldenExampleFor(plain))
        val named = plain.copy(checks = listOf(StepCheck.ScreenJudge(newCheckId(), "title", golden2.id)))
        assertEquals(golden2, goldenExampleFor(named))
        assertNull(goldenExampleFor(TestStep(newStepId(), "x", examples = listOf(log))))
        assertNull(goldenExampleFor(null))
    }

    // ── The multi-lane dialog ───────────────────────────────────────

    private val agent = LaneChoice("p1", "My Claude")
    private val external = LaneChoice(null, EXTERNAL_LANE_LABEL)
    private val all = listOf("case-1", "case-2")

    private fun model() = RunDialogModel("suite-1", all.toSet(), agent, "SER-A")

    @Test
    fun lanesCanBeAddedRemovedReorderedAndEditedAndTheFirstLaneIsNeverLost() {
        val one = model()
        val two = one.addLane(LaneDraft(choice = external, deviceSerial = "SER-B"))
        assertEquals(listOf("SER-A", "SER-B"), two.allLanes().map { it.deviceSerial })
        val ids = two.allLanes().map { it.id }

        val swapped = two.moveLane(ids[1], 0)
        assertEquals(listOf("SER-B", "SER-A"), swapped.allLanes().map { it.deviceSerial })
        assertEquals(external, swapped.choice, "the first row is the model's first lane")
        assertEquals(ids.reversed(), swapped.allLanes().map { it.id })

        val edited = swapped.updateLane(ids[0]) { it.copy(deviceSerial = "SER-Z") }
        assertEquals(listOf("SER-B", "SER-Z"), edited.allLanes().map { it.deviceSerial })

        val removed = swapped.removeLane(ids[0])
        assertEquals(listOf("SER-B"), removed.allLanes().map { it.deviceSerial })
        assertEquals(removed, removed.removeLane(removed.allLanes().single().id), "the last lane stays")
        assertEquals(two, two.withLanes(emptyList()))
    }

    @Test
    fun theDialogModelWithSeveralLanesBecomesAConfigInLaneOrderWithTheJudge() {
        val model = model().addLane(LaneDraft(choice = external, deviceSerial = "SER-B"))
            .copy(judgeProfileId = "p2", judgeMode = JudgeMode.FAILURES_ONLY, evidence = EvidenceFlags())
        val config = model.toConfig(all).getOrThrow()

        assertEquals(listOf("SER-A", "SER-B"), config.lanes.map { it.deviceSerial })
        assertEquals(listOf(LaneKind.AGENT_PROFILE, LaneKind.EXTERNAL), config.lanes.map { it.kind })
        assertEquals(model.allLanes().map { it.id }, config.lanes.map { it.id }, "the rows' ids are the lanes' ids")
        assertEquals("p2", config.judgeProfileId)
        assertEquals("failures_only", config.judgeMode)

        val off = model.copy(judgeMode = JudgeMode.OFF).toConfig(all).getOrThrow()
        assertNull(off.judgeProfileId, "no judge mode, no judge profile")
        assertEquals("off", off.judgeMode)
    }

    @Test
    fun laneAndJudgeProblemsNameTheLaneOrTheJudge() {
        fun problem(model: RunDialogModel) = model.toConfig(all).exceptionOrNull()?.message.orEmpty()
        val two = model().addLane(LaneDraft(choice = agent, deviceSerial = "SER-B"))
        val broken = two.updateLane(two.allLanes()[1].id) { it.copy(deviceSerial = null) }
        assertTrue(problem(broken).startsWith("Lane 2:") && problem(broken).contains("device"), problem(broken))
        val noChoice = two.updateLane(two.allLanes()[1].id) { it.copy(choice = null) }
        assertTrue(problem(noChoice).startsWith("Lane 2:") && problem(noChoice).contains("drives the lane"), problem(noChoice))
        assertTrue(problem(model().copy(deviceSerial = null)).startsWith("Choose a device"), "a single lane has no prefix")
        assertTrue(problem(model().copy(judgeMode = JudgeMode.EVERY_STEP, judgeProfileId = null)).contains("judge"))
    }

    @Test
    fun lanesSharingADeviceAreWarnedAboutAndNewRowsGetAFreeDevice() {
        val shared = model().addLane(LaneDraft(choice = agent, deviceSerial = "SER-A"))
        assertTrue(shared.deviceWarnings().single().contains("one after another"), shared.deviceWarnings().toString())
        assertTrue(model().addLane(LaneDraft(choice = agent, deviceSerial = "SER-B")).deviceWarnings().isEmpty())

        val devices = listOf(DeviceChoice("SER-A", "A"), DeviceChoice("SER-B", "B"))
        val fresh = RunDialogModel("suite-1", all.toSet(), agent, null).addLane(LaneDraft(choice = agent)).withDefaultDevices(devices)
        assertEquals(listOf("SER-A", "SER-B"), fresh.allLanes().map { it.deviceSerial }, "each row takes a device no other row uses")
        val more = fresh.addLane(LaneDraft(choice = agent)).withDefaultDevices(devices)
        assertEquals("SER-A", more.allLanes().last().deviceSerial, "when every device is taken the first is reused (and warned about)")
        val gone = model().withDefaultDevices(listOf(DeviceChoice("SER-B", "B")))
        assertEquals("SER-B", gone.deviceSerial, "a device that disappeared is replaced")
        val untouched = model()
        assertEquals(untouched, untouched.withDefaultDevices(emptyList()), "with no devices nothing changes")
    }
}
