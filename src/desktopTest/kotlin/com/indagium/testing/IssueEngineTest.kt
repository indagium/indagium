package com.indagium.testing

import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.IssueStatus
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.RecheckOutcome
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestSuite
import com.indagium.testing.run.StartRunResult
import com.indagium.testing.store.IssueStore
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val AWAIT_MS = 15_000L

/** The engine's issue hooks: a draft for a CREATE_ISSUE_AND_CONTINUE step, and the re-check of linked issues by a later run. */
class IssueEngineTest {
    private lateinit var issuesDir: File
    private lateinit var issues: IssueStore
    private var harness: RunHarness? = null

    @BeforeTest
    fun setUp() {
        issuesDir = createTempDirectory("engine-issues").toFile()
        issues = IssueStore(issuesDir)
    }

    @AfterTest
    fun tearDown() {
        harness?.close()
        issuesDir.deleteRecursively()
    }

    private fun suite(withCheck: Boolean = false): TestSuite = suiteOf(
        caseOf(
            "Login",
            step("Open the app"),
            step(
                "Tap login", onFailure = OnFailure.CREATE_ISSUE_AND_CONTINUE,
                checks = if (withCheck) listOf(logAppears("Login ok", withinMs = 200L)) else emptyList(),
            ),
            step("Look at the home screen"),
        ),
    )

    private fun harness(suite: TestSuite, turns: List<Turn>, withIssues: Boolean = true): RunHarness =
        RunHarness(TestLibrary(suites = listOf(suite)), TurnProvider(turns), issues = if (withIssues) issues else null).also { harness = it }

    private fun RunHarness.runToEnd(suite: TestSuite): TestRun = runBlocking {
        val started = assertIs<StartRunResult.Started>(coordinator.start(config(suite)))
        withTimeout(AWAIT_MS) { assertNotNull(coordinator.awaitFinished(started.runId)) }
    }

    private fun passFailPass(second: String) = listOf(finishTurn("pass"), finishTurn(second, "the button is missing"), finishTurn("pass"), textTurn)

    @Test
    fun aFailedCreateIssueStepGetsALocalDraftWithItsEvidenceAndTheIdIsOnTheResult() {
        val suite = suite(withCheck = true)
        val h = harness(suite, passFailPass("fail"))
        val run = h.runToEnd(suite)

        val steps = run.caseResult("Login").steps
        assertEquals(listOf(StepStatus.PASS, StepStatus.FAIL, StepStatus.PASS), steps.map { it.status })
        assertNull(steps[0].issueId)
        assertNull(steps[2].issueId)
        val issueId = assertNotNull(steps[1].issueId)
        assertTrue(steps[1].issueRequested)

        val issue = assertNotNull(issues.load(issueId))
        assertEquals(IssueStatus.DRAFT, issue.status)
        assertEquals(emptyList(), issue.destinationResults)
        assertEquals(run.id, issue.source.runId)
        assertEquals(suite.id, issue.source.suiteId)
        assertEquals(suite.cases.single().id, issue.source.caseId)
        assertEquals(steps[1].stepId, issue.source.stepId)
        assertEquals(2, issue.source.stepNumber)
        assertTrue(issue.draft.title.startsWith("Login · step 2: Tap login — failed"), issue.draft.title)
        assertEquals(listOf("Open the app", "Tap login"), issue.draft.stepsToReproduce)
        assertTrue(issue.draft.expected.startsWith("It works"), issue.draft.expected)
        assertTrue(issue.draft.actual.contains("the button is missing"), issue.draft.actual)
        assertTrue(issue.draft.actual.contains("logAppears: FAIL"), issue.draft.actual)
        assertTrue(issue.draft.labels.contains("found-by-agent"))
        val screenshot = issue.draft.attachments.first { it.kind == IssueAttachmentKind.SCREENSHOT }
        assertTrue(assertNotNull(issues.attachmentFile(issue, screenshot)).length() > 0, "the screenshot was copied into the issue's folder")

        val stored = assertNotNull(h.store.load(run.id))
        assertEquals(issueId, stored.caseResult("Login").steps[1].issueId, "run.json carries the issue id")
    }

    @Test
    fun noDraftIsMadeForAPassingStepAFailureThatDoesNotAskForOneOrARunWithoutAnIssueStore() {
        val suite = suiteOf(caseOf("Plain", step("One", onFailure = OnFailure.CONTINUE), step("Two", onFailure = OnFailure.CREATE_ISSUE_AND_CONTINUE)))
        val h = harness(suite, listOf(finishTurn("fail", "bad"), finishTurn("pass"), textTurn))
        h.runToEnd(suite)
        assertEquals(emptyList(), issues.list(), "a CONTINUE failure and a passing CREATE_ISSUE step make no issue")

        h.close()
        val other = suiteOf(caseOf("NoStore", step("One", onFailure = OnFailure.CREATE_ISSUE_AND_CONTINUE)))
        val bare = harness(other, listOf(finishTurn("fail", "bad"), textTurn), withIssues = false)
        val run = bare.runToEnd(other)
        val result = run.caseResult("NoStore").steps.single()
        assertTrue(result.issueRequested)
        assertNull(result.issueId)
        assertEquals(emptyList(), issues.list())
    }

    @Test
    fun aLinkedIssueIsMarkedStillFailingOrPassingNowByTheNextRunsOfItsCase() {
        val suite = suite()
        val h = harness(suite, passFailPass("fail") + passFailPass("pass") + passFailPass("fail"))
        val first = h.runToEnd(suite)
        val issueId = assertNotNull(first.caseResult("Login").steps[1].issueId)
        assertNull(issues.load(issueId)?.recheck, "nothing re-checked it yet")
        assertIs<StoreResult.Ok<*>>(issues.update(issueId) { it.copy(linkToCase = true) })

        val second = h.runToEnd(suite)
        val passing = assertNotNull(issues.load(issueId)?.recheck)
        assertEquals(RecheckOutcome.PASSING_NOW, passing.outcome)
        assertEquals(second.id, passing.runId)
        assertEquals(RunStatus.PASSED, second.status)

        val third = h.runToEnd(suite)
        val failing = assertNotNull(issues.load(issueId)?.recheck)
        assertEquals(RecheckOutcome.STILL_FAILING, failing.outcome)
        assertEquals(third.id, failing.runId)
    }

    @Test
    fun anIssueThatIsNotLinkedIsNeverReChecked() {
        val suite = suite()
        val h = harness(suite, passFailPass("fail") + passFailPass("pass"))
        val first = h.runToEnd(suite)
        val issueId = assertNotNull(first.caseResult("Login").steps[1].issueId)

        h.runToEnd(suite)

        assertNull(issues.load(issueId)?.recheck)
    }
}
