@file:Suppress("MagicNumber") // Fixture numbers, not tunable constants.

package com.indagium.testing

import com.indagium.testing.authoring.ImproveEvidenceSummary
import com.indagium.testing.authoring.TestCaseImproveProposal
import com.indagium.testing.authoring.parseImproveResponse
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.SUITE_SETUP_CASE_ID
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.ui.ImproveReview
import com.indagium.ui.ImproveStepsTarget
import com.indagium.ui.formatRunTime
import com.indagium.ui.improvableCaseIds
import com.indagium.ui.improveBlockedReason
import com.indagium.ui.improveEvidenceLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The state behind the "Improve steps with AI" dialog: what it says will be sent, when it can run, and the per-step review. */
class TestCaseImproveUiStateTest {
    private val steps = improveSteps()

    private fun entry(index: Int, action: String = steps[index].action, expected: String = steps[index].expected) =
        """{"stepId":"${steps[index].id}","action":"$action","expected":"$expected","reason":"r"}"""

    private fun proposal(vararg entries: String): TestCaseImproveProposal {
        val rows = parseImproveResponse("""{"steps":[${entries.joinToString(",")}]}""", steps, listOf(improveScript()))
        return TestCaseImproveProposal("p-1", IMPROVE_SUITE_ID, IMPROVE_CASE_ID, IMPROVE_PROFILE_ID, null, rows, ImproveEvidenceSummary())
    }

    private fun review() = ImproveReview(
        proposal(entry(0, action = "Launch the app"), entry(1, expected = "A results list is shown"), entry(2), entry(3)),
        listOf(improveScript()),
    )

    // ── The evidence line ────────────────────────────────────────────

    @Test
    fun theLabelSaysWhichRunHowManyScreenshotsAndJudgeSuggestionsWillBeSent() {
        val summary = ImproveEvidenceSummary(
            runId = "run-1", runCreatedAt = 1_760_000_000_000L, runScreenshots = 3, goldenScreenshots = 1, judgeSuggestions = 2,
        )

        val label = improveEvidenceLabel(summary)

        assertEquals("Latest run ${formatRunTime(1_760_000_000_000L)} · 4 screenshots · 2 judge suggestions", label)
        assertTrue(Regex("^Latest run \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} · 4 screenshots · 2 judge suggestions$").matches(label), label)
        assertTrue(improveEvidenceLabel(summary, explicitRun = true).startsWith("Run "))
    }

    @Test
    fun theLabelHandlesSingularsAndNothingToSendAndWhatTheLimitsLeftOut() {
        val single = ImproveEvidenceSummary(runId = "r", runCreatedAt = 0L, runScreenshots = 1, judgeSuggestions = 1)
        assertTrue(improveEvidenceLabel(single).endsWith("· 1 screenshot · 1 judge suggestion"))
        assertEquals("No run of this case yet · steps only", improveEvidenceLabel(ImproveEvidenceSummary()))
        assertEquals("No run of this case yet · 2 golden screenshots", improveEvidenceLabel(ImproveEvidenceSummary(goldenScreenshots = 2)))
        val limited = ImproveEvidenceSummary(runId = "r", runCreatedAt = 0L, runScreenshots = 12, leftOut = 3)
        assertTrue(improveEvidenceLabel(limited).endsWith("· 3 left out by the image limit"))
    }

    @Test
    fun theDialogCannotRunForAGoneEmptyOrReadOnlyCase() {
        val case = improveSuite().cases.single()
        assertNull(improveBlockedReason(case, editable = true))
        assertEquals("This case no longer exists.", improveBlockedReason(null, editable = true))
        assertEquals("This case is read-only.", improveBlockedReason(case, editable = false))
        assertTrue("no steps" in improveBlockedReason(TestCase("c", "Empty"), editable = true).orEmpty())
        assertEquals(ImproveStepsTarget("s", "c", null), ImproveStepsTarget("s", "c"))
    }

    // ── The run report shortcut ──────────────────────────────────────

    @Test
    fun theReportOffersTheDialogOnlyForCasesWithFailedOrUnjudgedSteps() {
        val failing = improveCaseResult(improveResult(STEP_OPEN, 1, "Open the app", "Home is visible", StepStatus.FAIL))
        val inconclusive = CaseResult(
            "case-two", "Two", steps = listOf(
                improveResult("a", 1, "x", "y", StepStatus.PASS, judge = improveJudgement(JudgeVerdict.INCONCLUSIVE, "could not tell")),
            ),
        )
        val passing = CaseResult("case-three", "Three", steps = listOf(improveResult("b", 1, "x", "y")))
        val hook = CaseResult(SUITE_SETUP_CASE_ID, "Suite setup", steps = listOf(improveResult("c", 1, "x", "y", StepStatus.FAIL)))
        val run = improveRun("run-1", 1L, listOf(failing, inconclusive, passing, hook))

        assertEquals(setOf(IMPROVE_CASE_ID, "case-two"), improvableCaseIds(run))
    }

    // ── The review ───────────────────────────────────────────────────

    @Test
    fun changedStepsStartAcceptedAndUnchangedOnesDoNot() {
        val review = review()

        assertEquals(listOf(true, true, false, false), review.rows.map { it.accepted })
        assertEquals(2, review.proposedChanges)
        assertEquals(setOf(STEP_OPEN, STEP_SEARCH), review.acceptedIds())
        assertEquals("Apply 2 selected steps", review.applyLabel)
        assertNull(review.blockedReason)
        assertEquals(steps.map { it.id }, review.edits().map { it.stepId }, "every step is sent back in order, accepted or not")
    }

    @Test
    fun untickingAStepRemovesItFromTheApplyAndTheLabelFollows() {
        val review = review()

        review.rows[0].accepted = false

        assertEquals(setOf(STEP_SEARCH), review.acceptedIds())
        assertEquals("Apply 1 selected step", review.applyLabel)
        review.rows[1].accepted = false
        assertEquals("Select at least one changed step to apply.", review.blockedReason)
    }

    @Test
    fun editingTheNewTextChangesWhatWouldBeWrittenAndEditingBackToTheSavedTextEndsTheChange() {
        val review = review()
        val open = review.rows[0]

        open.action = "Launch the shop app"
        assertEquals("Launch the shop app", review.edits()[0].action)
        assertTrue(open.changed)

        open.action = steps[0].action
        assertFalse(open.changed, "text equal to the saved text is not a change")
        assertFalse(STEP_OPEN in review.acceptedIds())
    }

    @Test
    fun anInvalidEditBlocksApplyOnlyWhileItsStepIsSelected() {
        val review = review()
        val open = review.rows[0]

        open.action = " "
        assertTrue("action must not be blank" in open.problem.orEmpty())
        assertTrue(review.blockedReason.orEmpty().startsWith("Step 1:"))

        open.accepted = false
        assertNull(review.blockedReason, "a step that is not selected cannot block")
        assertEquals(setOf(STEP_SEARCH), review.acceptedIds())
    }

    @Test
    fun onlyAnAiStepCanBeMadeOptionalAndTheIncidentalWarningFollowsTheExpectedText() {
        val review = review()

        assertTrue(review.rows[0].canBeOptional)
        assertFalse(review.rows[2].canBeOptional, "a manual step is performed by a tester")
        val search = review.rows[1]
        assertNull(search.warning)
        search.expected = "The first video is a sponsored advertisement"
        assertTrue(search.warning != null)
        search.optional = true
        assertNull(search.warning, "an optional step is exempt")
        assertTrue("needs a condition" in search.problem.orEmpty())
        search.condition = "An ad is shown"
        assertNull(search.problem)
    }
}
