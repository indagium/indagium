package com.indagium.testing

import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.IssueSeverity
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeComparison
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.run.FOUND_BY_AGENT_LABEL
import com.indagium.testing.run.IssueDraftContext
import com.indagium.testing.run.buildIssueDraft
import com.indagium.testing.run.issueSeverityFor
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IssueDraftBuilderTest {
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("issue-draft").toFile()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun build(fixture: IssueRunFixture, step: StepResult = fixture.failingStep, withGolden: Boolean = true) =
        buildIssueDraft(
            IssueDraftContext(fixture.run, fixture.runDir) { _, assetPath -> fixture.goldenFile.takeIf { withGolden && assetPath == "home.png" } },
            fixture.laneId, fixture.caseId, 1, step,
        ).getOrThrow()

    @Test
    fun reproductionStepsAreTheSetupAndTheCaseStepsUpToAndIncludingTheFailingOne() {
        val fixture = issueRunFixture(root)
        val draft = build(fixture).draft

        assertEquals(
            listOf(
                "Suite setup: run script reset_app (package_name=com.example.app)",
                "Case setup: run shared step “Log in” (Open login; Submit credentials)",
                "Precondition: Signed in as the test user",
                "Open the app",
                "Tap Settings in the toolbar",
            ),
            draft.stepsToReproduce,
        )
        assertFalse(draft.stepsToReproduce.any { it.contains("Go back") }, "steps after the failing one are not part of the reproduction")
    }

    @Test
    fun expectedIsTheStepsExpectedResultPlusTheFailedChecksOnly() {
        val draft = build(issueRunFixture(root)).draft

        assertTrue(draft.expected.startsWith("The settings screen is shown"), draft.expected)
        assertTrue(draft.expected.contains("A log line matching /Displayed .*Settings/ appears within 1000 ms"), draft.expected)
        assertTrue(draft.expected.contains("The screen shows: The settings title is visible"), draft.expected)
    }

    @Test
    fun actualHoldsOnlyWhatTheRunRecordedAndLabelsAgentText() {
        val draft = build(issueRunFixture(root)).draft

        assertTrue(draft.actual.contains("The step failed after 2 attempts."), draft.actual)
        assertTrue(draft.actual.contains("- logAppears: FAIL — no log line matched /Displayed .*Settings/ within 1000 ms"), draft.actual)
        assertTrue(draft.actual.contains("Judge verdict: FAIL. Reasoning (written by the judge model): The settings title is missing."), draft.actual)
        assertTrue(draft.actual.contains("Agent observation (untrusted):\n> I saw the main screen\n> nothing else"), draft.actual)
        assertTrue(draft.judgeNotes.contains("Judge: FAIL (app defect)"), draft.judgeNotes)
        assertTrue(draft.judgeNotes.contains("- Note: Ask for the title text."), draft.judgeNotes)
    }

    @Test
    fun aStepWithoutChecksJudgeOrObservationGetsNothingInvented() {
        val fixture = issueRunFixture(root, classification = null, withVideo = false)
        val bare = StepResult(fixture.steps[0].id, 1, fixture.steps[0].action, "", status = StepStatus.BLOCKED, attempts = 1)
        val draft = build(fixture, bare).draft

        assertEquals("The step was blocked after 1 attempt.", draft.actual)
        assertEquals("The app opens", draft.expected, "falls back to the written expected result, no checks to list")
        assertEquals("", draft.judgeNotes)
        assertEquals(listOf(), draft.attachments, "no screenshot, log range, transcript or judge verdict was recorded for it")
        assertEquals(IssueSeverity.MEDIUM, draft.severity)
    }

    @Test
    fun comparisonExplanationsAndSuggestedFixesGoIntoTheJudgeNotes() {
        val fixture = issueRunFixture(root)
        val comparison = JudgeComparison(
            caseId = fixture.caseId, iteration = 1, stepId = fixture.failingStep.stepId, stepNumber = 2, action = "x",
            verdicts = mapOf(fixture.laneId to JudgeVerdict.FAIL), classification = JudgeClassification.APP_DEFECT,
            explanation = "Only this lane failed.",
        )
        val withComparison = IssueRunFixture(
            fixture.runDir, fixture.run.copy(comparisons = listOf(comparison)), fixture.caseId, fixture.failingStep, fixture.steps, fixture.goldenFile,
        )
        val notes = build(withComparison).draft.judgeNotes

        assertTrue(notes.contains("Lanes compared (${FIXTURE_SERIAL}: FAIL; app defect): Only this lane failed."), notes)
    }

    @Test
    fun severityFollowsTheClassificationAndACrashInTheLog() {
        assertEquals(IssueSeverity.CRITICAL, issueSeverityFor(JudgeClassification.APP_DEFECT, "E AndroidRuntime: FATAL EXCEPTION: main"))
        assertEquals(IssueSeverity.CRITICAL, issueSeverityFor(JudgeClassification.APP_DEFECT, "F libc: Fatal signal 11 (SIGSEGV)"))
        assertEquals(IssueSeverity.CRITICAL, issueSeverityFor(JudgeClassification.APP_DEFECT, "E ActivityManager: ANR in com.example.app"))
        assertEquals(IssueSeverity.HIGH, issueSeverityFor(JudgeClassification.APP_DEFECT, "I App: all fine"))
        assertEquals(IssueSeverity.HIGH, issueSeverityFor(JudgeClassification.APP_DEFECT, null))
        assertEquals(IssueSeverity.LOW, issueSeverityFor(JudgeClassification.AGENT_OR_STEP_PROBLEM, "FATAL EXCEPTION"))
        assertEquals(IssueSeverity.MEDIUM, issueSeverityFor(JudgeClassification.UNKNOWN, "FATAL EXCEPTION"))
        assertEquals(IssueSeverity.MEDIUM, issueSeverityFor(null, null))

        assertEquals(IssueSeverity.CRITICAL, build(issueRunFixture(root, crash = true)).draft.severity)
        val quiet = File(root, "quiet").apply { mkdirs() }
        assertEquals(IssueSeverity.HIGH, build(issueRunFixture(quiet, crash = false)).draft.severity)
        val agent = File(root, "agent").apply { mkdirs() }
        assertEquals(IssueSeverity.LOW, build(issueRunFixture(agent, classification = JudgeClassification.AGENT_OR_STEP_PROBLEM)).draft.severity)
    }

    @Test
    fun labelsAreTheSuiteTagsPlusFoundByAgentWithoutDuplicates() {
        val draft = build(issueRunFixture(root)).draft

        assertEquals(listOf("smoke", "Settings", FOUND_BY_AGENT_LABEL), draft.labels)
    }

    @Test
    fun titleNamesTheCaseTheStepTheFailureAndTheClassification() {
        val draft = build(issueRunFixture(root)).draft

        assertEquals("Settings · step 2: Tap Settings in the toolbar — failed: logAppears check failed (app defect)", draft.title)
    }

    @Test
    fun environmentCarriesPackageDeviceAgentAndRun() {
        val fixture = issueRunFixture(root)
        val built = build(fixture)

        assertEquals("com.example.app", built.draft.environment.appPackage)
        assertEquals(FIXTURE_SERIAL, built.draft.environment.deviceSerial)
        assertEquals("AI agent (profile profile-1)", built.draft.environment.agent)
        assertEquals(fixture.run.id, built.draft.environment.runId)
        assertEquals(fixture.run.suite.id, built.source.suiteId)
        assertEquals(fixture.caseId, built.source.caseId)
        assertEquals(fixture.failingStep.stepId, built.source.stepId)
        assertEquals(2, built.source.stepNumber)
    }

    @Test
    fun attachmentsCoverEveryEvidenceFileThatExists() {
        val fixture = issueRunFixture(root)
        val attachments = build(fixture).draft.attachments.associateBy { it.kind }

        assertEquals(
            setOf(
                IssueAttachmentKind.SCREENSHOT, IssueAttachmentKind.GOLDEN, IssueAttachmentKind.LOG_RANGE, IssueAttachmentKind.JUDGE_VERDICT,
                IssueAttachmentKind.TRANSCRIPT, IssueAttachmentKind.VIDEO_CLIP,
            ),
            attachments.keys,
        )
        assertEquals(fixture.screenshot.absolutePath, attachments.getValue(IssueAttachmentKind.SCREENSHOT).sourcePath)
        assertEquals(fixture.goldenFile.absolutePath, attachments.getValue(IssueAttachmentKind.GOLDEN).sourcePath)
        val log = assertNotNull(attachments.getValue(IssueAttachmentKind.LOG_RANGE).text)
        assertEquals(FIXTURE_LOG_DURING + FIXTURE_CRASH_LINE, log, "exactly the step's byte range of the lane log")
        assertTrue(assertNotNull(attachments.getValue(IssueAttachmentKind.JUDGE_VERDICT).text).contains("\"verdict\": \"FAIL\""))
        assertEquals("{\"kind\":\"tool_call\",\"tool\":\"tap\"}\n", attachments.getValue(IssueAttachmentKind.TRANSCRIPT).text)
        val video = attachments.getValue(IssueAttachmentKind.VIDEO_CLIP)
        assertEquals(fixture.videoFile.absolutePath, video.sourcePath)
        assertTrue(video.include, "a small video is included by default")
        assertTrue(video.note.contains("01:05") && video.note.contains("12 s"), "the step starts 65 s after the lane started: ${video.note}")
        assertTrue(attachments.values.all { it.sizeBytes > 0 })
    }

    @Test
    fun attachmentsExistOnlyForFilesThatExist() {
        val fixture = issueRunFixture(root, withVideo = false)
        fixture.screenshot.delete()

        val kinds = build(fixture, withGolden = false).draft.attachments.map { it.kind }.toSet()

        assertFalse(IssueAttachmentKind.SCREENSHOT in kinds, "the screenshot file is gone")
        assertFalse(IssueAttachmentKind.GOLDEN in kinds, "the golden image cannot be found")
        assertFalse(IssueAttachmentKind.VIDEO_CLIP in kinds, "the lane recorded no video")
        assertTrue(IssueAttachmentKind.LOG_RANGE in kinds)
        assertTrue(IssueAttachmentKind.JUDGE_VERDICT in kinds)
    }

    @Test
    fun aLogRangeLargerThanTheCapKeepsTheEndWhereTheFailureIs() {
        val fixture = issueRunFixture(root)
        val filler = "01-02 03:04:08.006  100  101 I App: filler line\n".repeat(8_000)
        fixture.logFile.writeText(FIXTURE_LOG_BEFORE + filler + FIXTURE_CRASH_LINE)
        val long = fixture.failingStep.copy(logEndOffset = fixture.logFile.length())

        val log = assertNotNull(build(fixture, long).draft.attachments.first { it.kind == IssueAttachmentKind.LOG_RANGE }.text)

        assertTrue(log.startsWith("… ("), log.take(80))
        assertTrue(log.endsWith(FIXTURE_CRASH_LINE), "the end of the range is what is kept")
        assertTrue(log.length < 300_000)
    }

    @Test
    fun onlyCaseStepsOfAKnownLaneAndCaseCanBecomeIssues() {
        val fixture = issueRunFixture(root)
        val context = IssueDraftContext(fixture.run, fixture.runDir)

        val setupStep = fixture.failingStep.copy(setup = true)
        assertTrue(buildIssueDraft(context, fixture.laneId, fixture.caseId, 1, setupStep).isFailure)
        assertTrue(buildIssueDraft(context, "lane-x", fixture.caseId, 1, fixture.failingStep).isFailure)
        assertTrue(buildIssueDraft(context, fixture.laneId, "case-x", 1, fixture.failingStep).isFailure)
        assertTrue(buildIssueDraft(context, fixture.laneId, fixture.caseId, 1, fixture.failingStep.copy(stepId = "step-x")).isFailure)
        assertNull(buildIssueDraft(context, fixture.laneId, fixture.caseId, 1, fixture.failingStep).exceptionOrNull())
    }
}
