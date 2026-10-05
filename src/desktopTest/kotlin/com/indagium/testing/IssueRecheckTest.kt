package com.indagium.testing

import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueSource
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RecheckOutcome
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.newLaneId
import com.indagium.testing.run.markLinkedIssues
import com.indagium.testing.run.recheckOutcomeFor
import com.indagium.testing.run.withIssueId
import com.indagium.testing.store.IssueStore
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IssueRecheckTest {
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("issue-recheck").toFile()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private val fixture by lazy { issueRunFixture(root) }
    private val run: TestRun get() = fixture.run

    private fun issue(linked: Boolean = true, runId: String = "run-old", suiteId: String = run.suite.id) = IssueRecord(
        id = "issue-1", draft = IssueDraft("t"),
        source = IssueSource(runId, ISSUE_LANE_ID, suiteId, fixture.caseId, fixture.failingStep.stepId), linkToCase = linked,
    )

    private fun TestRun.withStepStatus(status: StepStatus): TestRun = withLane(ISSUE_LANE_ID) { lane ->
        lane.copy(
            cases = lane.cases.map { case ->
                case.copy(steps = case.steps.map { if (it.stepId == fixture.failingStep.stepId) it.copy(status = status) else it })
            },
        )
    }

    @Test
    fun aStepThatStillFailsIsStillFailingAndOnePassingIsPassingNow() {
        assertEquals(RecheckOutcome.STILL_FAILING, recheckOutcomeFor(run, issue()))
        assertEquals(RecheckOutcome.STILL_FAILING, recheckOutcomeFor(run.withStepStatus(StepStatus.TIMEOUT), issue()))
        assertEquals(RecheckOutcome.PASSING_NOW, recheckOutcomeFor(run.withStepStatus(StepStatus.PASS), issue()))
    }

    @Test
    fun onlyLinkedIssuesOfTheSameSuiteFromAnotherRunThatRanTheStepAreChecked() {
        assertNull(recheckOutcomeFor(run, issue(linked = false)))
        assertNull(recheckOutcomeFor(run, issue(runId = run.id)), "the run that created the issue says nothing new")
        assertNull(recheckOutcomeFor(run, issue(suiteId = "suite-other")))
        assertNull(recheckOutcomeFor(run.withStepStatus(StepStatus.SKIPPED), issue()), "a skipped step was not run")
        val noLanes = run.copy(lanes = emptyList())
        assertNull(recheckOutcomeFor(noLanes, issue()))
    }

    @Test
    fun anyLaneStillFailingMakesTheIssueStillFailing() {
        val passingLane = run.lanes.single().let { lane ->
            val config = LaneConfig(newLaneId(), LaneKind.EXTERNAL, null, "SER-2")
            LaneResult(config.id, config, lane.status, run.withStepStatus(StepStatus.PASS).lanes.single().cases)
        }
        val twoLanes = run.copy(lanes = run.lanes + passingLane)

        assertEquals(RecheckOutcome.STILL_FAILING, recheckOutcomeFor(twoLanes, issue()))
        val bothPass = twoLanes.withStepStatus(StepStatus.PASS)
        assertEquals(RecheckOutcome.PASSING_NOW, recheckOutcomeFor(bothPass, issue()))
    }

    @Test
    fun markLinkedIssuesWritesTheOutcomeOnlyOnLinkedIssues() {
        val store = IssueStore(File(root, "issues"))
        val linked = (store.create(IssueDraft("linked"), issue().source, linkToCase = true) as com.indagium.testing.store.StoreResult.Ok).value
        val plain = (store.create(IssueDraft("plain"), issue().source, linkToCase = false) as com.indagium.testing.store.StoreResult.Ok).value

        val marked = markLinkedIssues(store, run.withStepStatus(StepStatus.PASS), now = 99L)

        assertEquals(1, marked)
        assertEquals(RecheckOutcome.PASSING_NOW, store.load(linked.id)?.recheck?.outcome)
        assertEquals(99L, store.load(linked.id)?.recheck?.checkedAt)
        assertEquals(run.id, store.load(linked.id)?.recheck?.runId)
        assertNull(store.load(plain.id)?.recheck)
    }

    @Test
    fun withIssueIdSetsTheIdOnThatStepOnly() {
        val marked = run.withIssueId(ISSUE_LANE_ID, fixture.caseId, 1, fixture.failingStep.stepId, "issue-xyz")

        val steps = marked.lanes.single().cases.single().steps
        assertEquals(listOf(null, "issue-xyz"), steps.map { it.issueId })
        assertEquals(run, run.withIssueId(ISSUE_LANE_ID, fixture.caseId, 2, fixture.failingStep.stepId, "issue-xyz"), "another iteration is untouched")
        assertEquals(run, run.withIssueId("lane-x", fixture.caseId, 1, fixture.failingStep.stepId, "issue-xyz"))
    }
}
