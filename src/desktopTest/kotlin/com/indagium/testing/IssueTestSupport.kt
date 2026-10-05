package com.indagium.testing

import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.StepFix
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.newCaseId
import com.indagium.testing.model.newCheckId
import com.indagium.testing.model.newExampleId
import com.indagium.testing.model.newHookId
import com.indagium.testing.model.newRunId
import com.indagium.testing.model.newSharedStepId
import com.indagium.testing.model.newStepId
import com.indagium.testing.model.newSuiteId
import java.io.File

// A finished synthetic run with real evidence files, for the issue tests. Nothing is derived from a real log.

internal const val ISSUE_LANE_ID = "lane-issue-1"
internal const val FIXTURE_LOG_BEFORE = "01-02 03:04:05.006  100  101 I App: before the step\n"
internal const val FIXTURE_LOG_DURING = "01-02 03:04:06.006  100  101 I App: tapping settings\n"
internal const val FIXTURE_CRASH_LINE = "01-02 03:04:07.006  100  101 E AndroidRuntime: FATAL EXCEPTION: main\n"

internal class IssueRunFixture(
    val runDir: File,
    val run: TestRun,
    val caseId: String,
    val failingStep: StepResult,
    val steps: List<TestStep>,
    val goldenFile: File,
) {
    val laneId: String get() = ISSUE_LANE_ID
    val logFile: File get() = File(runDir, checkNotNull(run.lanes.single().logPath))
    val screenshot: File get() = File(runDir, checkNotNull(failingStep.screenshotPath))
    val videoFile: File get() = File(logFile.parentFile.parentFile, "video/screen.mkv")
}

/**
 * The run: one lane, one case with three steps; step 2 failed (judged APP_DEFECT unless [classification] says otherwise). The
 * lane's log holds one line before the step, one during it and, with [crash], a FATAL EXCEPTION line during it.
 */
@Suppress("LongMethod")
internal fun issueRunFixture(
    root: File,
    crash: Boolean = true,
    classification: JudgeClassification? = JudgeClassification.APP_DEFECT,
    withVideo: Boolean = true,
    onFailure: OnFailure = OnFailure.CREATE_ISSUE_AND_CONTINUE,
): IssueRunFixture {
    val runDir = File(root, "run").apply { mkdirs() }
    val goldenFile = File(root, "golden/home.png").apply { parentFile.mkdirs(); writeBytes(fixturePng(8, 8)) }
    val script = hostScript("reset_app", "echo reset")
    val shared = SharedStep(newSharedStepId(), "Log in", steps = listOf(plainStep("Open login"), plainStep("Submit credentials")))
    val golden = StepExample.GoldenScreenshot(newExampleId(), "home.png", "Home")
    val logCheck = StepCheck.LogAppears(newCheckId(), null, "Displayed .*Settings", LOG_WITHIN_MS)
    val screenCheck = StepCheck.ScreenJudge(newCheckId(), "The settings title is visible", golden.id)
    val steps = listOf(
        TestStep(newStepId(), "Open the app", "The app opens"),
        TestStep(
            newStepId(), "Tap   Settings\nin the toolbar", "The settings screen is shown", listOf(logCheck, screenCheck), listOf(golden),
            onFailure = onFailure,
        ),
        TestStep(newStepId(), "Go back", "The home screen is shown"),
    )
    val case = TestCase(
        id = newCaseId(), name = "Settings", preconditions = "Signed in as the test user",
        setup = listOf(HookItem.Shared(newHookId(), shared.id)), steps = steps,
    )
    val suite = TestSuite(
        newSuiteId(), "Smoke", targetPackage = "com.example.app", tags = listOf("smoke", "Settings", "SMOKE"),
        setup = listOf(HookItem.Script(newHookId(), script.id, mapOf("package_name" to "com.example.app"))), cases = listOf(case),
    )
    val sessionDir = File(runDir, "lanes/$ISSUE_LANE_ID/capture/session")
    val log = File(sessionDir, "logs/logcat.log").apply { parentFile.mkdirs() }
    val during = FIXTURE_LOG_DURING + if (crash) FIXTURE_CRASH_LINE else ""
    log.writeText(FIXTURE_LOG_BEFORE + during)
    val logStart = FIXTURE_LOG_BEFORE.toByteArray().size.toLong()
    val logEnd = log.length()
    if (withVideo) File(sessionDir, "video/screen.mkv").apply { parentFile.mkdirs(); writeBytes(ByteArray(VIDEO_BYTES)) }
    val shot = File(runDir, "lanes/$ISSUE_LANE_ID/screens/c1-i1-s2-a2.png").apply { parentFile.mkdirs(); writeBytes(fixturePng(8, 8)) }
    val transcript = File(runDir, "lanes/$ISSUE_LANE_ID/transcript.jsonl")
    val before = "{\"kind\":\"case_started\"}\n"
    val during2 = "{\"kind\":\"tool_call\",\"tool\":\"tap\"}\n"
    transcript.writeText(before + during2)
    val judge = classification?.let {
        StepJudgement(
            verdict = JudgeVerdict.FAIL, reasoning = "The settings title is missing.", classification = it, judgedAt = 10L,
            suggestedFix = StepFix(note = "Ask for the title text."),
        )
    }
    val failing = StepResult(
        stepId = steps[1].id, stepNumber = 2, action = steps[1].action, expected = steps[1].expected, status = StepStatus.FAIL, attempts = 2,
        agentClaim = "pass", observation = "I saw the main screen\nnothing else",
        checks = listOf(
            CheckResult(logCheck.id, "logAppears", CheckStatus.FAIL, "no log line matched /Displayed .*Settings/ within 1000 ms"),
            CheckResult(screenCheck.id, "screenJudge", CheckStatus.FAIL, "The judge says the title is not visible."),
        ),
        screenshotPath = shot.relativeTo(runDir).invariantSeparatorsPath,
        logStartOffset = logStart, logEndOffset = logEnd,
        transcriptStartOffset = before.toByteArray().size.toLong(), transcriptEndOffset = transcript.length(),
        startedAt = 100_000L + 65_000L, durationMs = 12_000L, issueRequested = onFailure == OnFailure.CREATE_ISSUE_AND_CONTINUE, judge = judge,
    )
    val first = StepResult(steps[0].id, 1, steps[0].action, steps[0].expected, status = StepStatus.PASS)
    val lane = LaneConfig(ISSUE_LANE_ID, LaneKind.AGENT_PROFILE, "profile-1", FIXTURE_SERIAL)
    val run = TestRun(
        id = newRunId(), suite = suite, scripts = listOf(script), sharedSteps = listOf(shared),
        config = RunConfig(suite.id, null, listOf(lane)),
        lanes = listOf(
            LaneResult(
                ISSUE_LANE_ID, lane, RunStatus.FAILED,
                listOf(CaseResult(case.id, case.name, 1, CaseStatus.FAIL, listOf(first, failing))),
                startedAt = 100_000L, logPath = log.relativeTo(runDir).invariantSeparatorsPath,
                transcriptPath = transcript.relativeTo(runDir).invariantSeparatorsPath,
            ),
        ),
        status = RunStatus.FAILED, createdAt = 1L, finishedAt = 2L,
    )
    return IssueRunFixture(runDir, run, case.id, failing, steps, goldenFile)
}

private const val VIDEO_BYTES = 2048
private const val LOG_WITHIN_MS = 1_000L

/** Puts the fixture's run where an AppState without a save folder keeps runs (`<testing dir>/runs/<runId>`). */
internal fun installRun(testingDir: File, fixture: IssueRunFixture): File {
    val target = File(testingDir, "runs/${fixture.run.id}")
    fixture.runDir.copyRecursively(target, overwrite = true)
    File(target, "run.json").writeText(com.indagium.testing.store.encodeRunFile(fixture.run))
    return target
}
