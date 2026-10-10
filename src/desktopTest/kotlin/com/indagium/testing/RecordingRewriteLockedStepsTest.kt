@file:Suppress("MagicNumber") // Fixture counts, not tunable constants.

package com.indagium.testing

import com.indagium.testing.authoring.LOCKED_STEP_LABEL
import com.indagium.testing.authoring.LockedRewriteStep
import com.indagium.testing.authoring.REWRITE_SYSTEM_PROMPT
import com.indagium.testing.authoring.RecordedTestStep
import com.indagium.testing.authoring.RecordingRewrite
import com.indagium.testing.authoring.RecordingRewriteService
import com.indagium.testing.authoring.RecordingRewriteTools
import com.indagium.testing.authoring.RewriteExpectations
import com.indagium.testing.authoring.RewriteGeneration
import com.indagium.testing.authoring.RewriteScope
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.buildRewriteEvidence
import com.indagium.testing.authoring.buildRewrittenRows
import com.indagium.testing.authoring.isAcceptedRewriteStep
import com.indagium.testing.authoring.lockedRewriteStepsOf
import com.indagium.testing.authoring.parseRewriteResponse
import com.indagium.testing.authoring.rewriteInputsOf
import com.indagium.testing.authoring.rewritePrompt
import com.indagium.testing.store.StoreResult
import com.indagium.ui.REWRITE_ALL_AGAIN_LABEL
import com.indagium.ui.REWRITE_REVIEW_LABEL
import com.indagium.ui.rewriteButtonPlan
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The first answer: step 1 (inputs 1 and 2) is fine, step 2 (input 3) is flagged for review. */
private const val FIRST_ANSWER = """{"steps":[
    {"action":"Search for 'lofi'","expected":"The search field shows 'lofi'","sourceInputs":[1,2]},
    {"action":"Press Enter","expected":"","sourceInputs":[3],"reviewReason":"Confirm what the search shows."}
],"notes":"first"}"""

/** The second answer: step 1 returned unchanged, step 2 now resolved. */
private const val SECOND_ANSWER = """{"steps":[
    {"action":"Search for 'lofi'","expected":"The search field shows 'lofi'","sourceInputs":[1,2]},
    {"action":"Submit the search","expected":"Results for 'lofi' are listed","sourceInputs":[3]}
],"notes":"second"}"""

/** Three single-input steps: step 1 fine, steps 2 and 3 flagged. */
private const val THREE_ANSWER = """{"steps":[
    {"action":"Tap Search","expected":"The search field is focused","sourceInputs":[1]},
    {"action":"Enter text: lofi","expected":"","sourceInputs":[2],"reviewReason":"Check what was typed."},
    {"action":"Press Enter","expected":"","sourceInputs":[3],"reviewReason":"Check the result."}
],"notes":"three"}"""

/** Rewrite again keeps the steps nobody flagged, exactly, and opens only the flagged ones. */
class RecordingRewriteLockedStepsTest {
    private val sessions = mutableListOf<TestStepRecordingSession>()

    @AfterTest
    fun closeSessions() = sessions.forEach(TestStepRecordingSession::close)

    private fun session() = recordedShopSession().also { sessions += it }

    private fun service(generate: suspend (RewriteGeneration) -> String) =
        RecordingRewriteService(library = { shopLibrary() }, preflight = { _, _ -> StoreResult.Ok(Unit) }, generate = generate)

    private suspend fun RecordingRewriteService.run(session: TestStepRecordingSession, scope: RewriteScope? = null) =
        rewrite(session, SHOP_SUITE_ID, SHOP_CASE_ID, "profile", null, null, "", scope)

    private fun reason(result: StoreResult<RecordingRewrite>): String = assertIs<StoreResult.Invalid>(result).reason

    /** A session rewritten once with [first]. */
    private fun rewrittenOnce(first: String = FIRST_ANSWER): TestStepRecordingSession {
        val session = session()
        val result = runBlocking { service { first }.run(session) }
        assertIs<StoreResult.Ok<RecordingRewrite>>(result)
        return session
    }

    private fun fenced(prompt: String) = prompt.substringAfter("<untrusted_data source=\"recorded_inputs\">").substringBefore("</untrusted_data>")

    // ── The second rewrite ───────────────────────────────────────────

    @Test
    fun aRewriteAgainKeepsTheAcceptedStepAndOnlyTheFlaggedInputIsOpen() = runBlocking {
        val session = rewrittenOnce()
        val accepted = session.snapshot.value.steps[0]
        assertNull(accepted.reviewReason)
        assertNotNull(session.snapshot.value.steps[1].reviewReason)
        val seen = mutableListOf<RewriteGeneration>()

        val result = service { seen += it; SECOND_ANSWER }.run(session)

        assertIs<StoreResult.Ok<RecordingRewrite>>(result)
        val request = seen.single()
        val lines = fenced(request.prompt)
        val locked = "1-2. [$LOCKED_STEP_LABEL] covers input(s) 1-2 | action: \"Search for 'lofi'\" | expected: \"The search field shows 'lofi'\""
        assertTrue(locked in lines, lines)
        assertFalse("\n1. " in "\n$lines" || "\n2. " in "\n$lines", "the locked inputs have no lines of their own: $lines")
        assertTrue("3. [KEY] Press Enter" in lines, lines)
        assertTrue("Lines marked [$LOCKED_STEP_LABEL] are steps from an earlier rewrite" in request.prompt, request.prompt)
        assertEquals(1, request.attachment?.images?.size, "only the open input 3 gets evidence; the locked inputs get none")
        assertTrue("Image 1: input 3 after" in request.prompt, request.prompt)
        val steps = session.snapshot.value.steps
        assertSame(accepted, steps[0], "the accepted row is the very same row")
        assertEquals(listOf("Search for 'lofi'", "Submit the search"), steps.map { it.action })
        assertNull(steps[1].reviewReason)
    }

    @Test
    fun anAnswerThatChangesDropsOrMergesALockedStepIsRefusedAndNothingChanges() = runBlocking {
        val session = rewrittenOnce()
        val before = session.snapshot.value
        val answers = mapOf(
            "reworded" to """{"steps":[{"action":"Search for lofi","expected":"The search field shows 'lofi'","sourceInputs":[1,2]},
                {"action":"Submit","expected":"Results","sourceInputs":[3]}]}""",
            "new expected" to """{"steps":[{"action":"Search for 'lofi'","expected":"Something else","sourceInputs":[1,2]},
                {"action":"Submit","expected":"Results","sourceInputs":[3]}]}""",
            "split" to """{"steps":[{"action":"Tap Search","expected":"Focused","sourceInputs":[1]},
                {"action":"Type","expected":"Typed","sourceInputs":[2]},{"action":"Submit","expected":"Results","sourceInputs":[3]}]}""",
            "merged" to """{"steps":[{"action":"Search for 'lofi' and submit","expected":"Results","sourceInputs":[1,2,3]}]}""",
            "dropped" to """{"steps":[{"action":"Submit","expected":"Results","sourceInputs":[3]}]}""",
            "with a reason" to """{"steps":[{"action":"Search for 'lofi'","expected":"The search field shows 'lofi'","sourceInputs":[1,2],
                "reviewReason":"Check"},{"action":"Submit","expected":"Results","sourceInputs":[3]}]}""",
            "with checks" to """{"steps":[{"action":"Search for 'lofi'","expected":"The search field shows 'lofi'","sourceInputs":[1,2],
                "checks":[{"type":"askJudge","text":"Is it fine?"}]},{"action":"Submit","expected":"Results","sourceInputs":[3]}]}""",
            "optional flipped" to """{"steps":[{"action":"Search for 'lofi'","expected":"The search field shows 'lofi'","sourceInputs":[1,2],
                "optional":true,"condition":"If shown"},{"action":"Submit","expected":"Results","sourceInputs":[3]}]}""",
        )
        for ((name, answer) in answers) {
            val message = reason(service { answer }.run(session))
            assertTrue(
                "locked" in message || "Every recorded input" in message || "Inputs must be covered" in message || "input 1" in message,
                "$name: $message",
            )
            assertEquals(before.steps, session.snapshot.value.steps, "$name: a refused answer changes nothing")
            assertEquals(before.rawSteps, session.snapshot.value.rawSteps, "$name: the recorded rows stay")
            assertEquals(before.rewriteNotes, session.snapshot.value.rewriteNotes)
        }
    }

    @Test
    fun theAllScopeIgnoresTheLocksLikeTodayAndRewritesEveryInput() = runBlocking {
        val session = rewrittenOnce()
        val seen = mutableListOf<RewriteGeneration>()

        val result = service { seen += it; VALID_SHOP_REWRITE }.run(session, RewriteScope.ALL)

        assertIs<StoreResult.Ok<RecordingRewrite>>(result)
        val lines = fenced(seen.single().prompt)
        assertFalse(LOCKED_STEP_LABEL in lines || "steps from an earlier rewrite" in seen.single().prompt)
        assertTrue("1. [TAP]" in lines && "2. [TEXT]" in lines && "3. [KEY]" in lines, lines)
        assertEquals(listOf("Search for 'lofi'", "Submit the search"), session.snapshot.value.steps.map { it.action })
        assertEquals(2, seen.single().attachment?.images?.size, "the same evidence as the first rewrite")
    }

    @Test
    fun theNeedsReviewScopeWithNothingToReviewIsRefusedWhileTheDefaultRewritesEverything() = runBlocking {
        val session = rewrittenOnce(VALID_SHOP_REWRITE)
        assertTrue(session.snapshot.value.steps.all { it.isAcceptedRewriteStep() })
        val prompts = mutableListOf<String>()

        val refused = reason(service { prompts += it.prompt; VALID_SHOP_REWRITE }.run(session, RewriteScope.NEEDS_REVIEW))
        val default = service { prompts += it.prompt; VALID_SHOP_REWRITE }.run(session)

        assertTrue("nothing needs review" in refused && "scope" in refused, refused)
        assertEquals(1, prompts.size, "the refusal never reached the model")
        assertIs<StoreResult.Ok<RecordingRewrite>>(default)
        assertFalse(LOCKED_STEP_LABEL in fenced(prompts.single()) || "steps from an earlier rewrite" in prompts.single())
        assertTrue("1. [TAP]" in fenced(prompts.single()))
    }

    @Test
    fun aRowTheReviewerEditedIsAcceptedAndKeptAndARemovedRowIsNotLocked() = runBlocking {
        val session = rewrittenOnce(THREE_ANSWER)
        val rows = session.snapshot.value.steps
        assertEquals(listOf(true, false, false), rows.map { it.isAcceptedRewriteStep() })
        // The reviewer fills in the second row's result: its review reason ends and the row counts as accepted.
        val edits = listOf(rows[0].action to rows[0].expected, "Type 'lofi'" to "The field shows 'lofi'", rows[2].action to rows[2].expected)
        assertTrue(session.updateReviewedSteps(edits))
        assertEquals(listOf(true, true, false), session.snapshot.value.steps.map { it.isAcceptedRewriteStep() })
        val edited = session.snapshot.value.steps[1]
        val seen = mutableListOf<RewriteGeneration>()
        val answer = """{"steps":[{"action":"Tap Search","expected":"The search field is focused","sourceInputs":[1]},
            {"action":"Type 'lofi'","expected":"The field shows 'lofi'","sourceInputs":[2]},
            {"action":"Submit the search","expected":"Results are listed","sourceInputs":[3]}]}"""

        val result = service { seen += it; answer }.run(session)

        assertIs<StoreResult.Ok<RecordingRewrite>>(result)
        val lines = fenced(seen.single().prompt)
        assertTrue("1. [$LOCKED_STEP_LABEL] covers input(s) 1 | action: \"Tap Search\"" in lines, lines)
        assertTrue("[$LOCKED_STEP_LABEL] covers input(s) 2 | action: \"Type 'lofi'\"" in lines, lines)
        assertTrue("3. [KEY] Press Enter" in lines, lines)
        assertSame(edited, session.snapshot.value.steps[1])
        assertEquals("Submit the search", session.snapshot.value.steps[2].action)
    }

    @Test
    fun aRemovedRowIsNotLockedAndItsInputsStayOut() = runBlocking {
        val session = rewrittenOnce(THREE_ANSWER)
        val rows = session.snapshot.value.steps
        assertTrue(session.removeReviewedRows(setOf(rows[2].id)))
        val seen = mutableListOf<RewriteGeneration>()
        val answer = """{"steps":[{"action":"Tap Search","expected":"The search field is focused","sourceInputs":[1]},
            {"action":"Type 'lofi'","expected":"The field shows 'lofi'","sourceInputs":[2]}]}"""

        val result = service { seen += it; answer }.run(session)

        assertIs<StoreResult.Ok<RecordingRewrite>>(result)
        val lines = fenced(seen.single().prompt)
        assertTrue("covers input(s) 1 |" in lines && "2. [TEXT] Enter text: lofi" in lines && "Press Enter" !in lines, lines)
        assertSame(rows[0], session.snapshot.value.steps[0])
    }

    @Test
    fun aFirstRewriteHasNoLocksAndIsUnchanged() = runBlocking {
        val session = session()
        val seen = mutableListOf<RewriteGeneration>()

        service { seen += it; VALID_SHOP_REWRITE }.run(session)

        assertFalse(LOCKED_STEP_LABEL in fenced(seen.single().prompt))
        assertFalse("steps from an earlier rewrite" in seen.single().prompt)
        assertEquals(2, seen.single().attachment?.images?.size)
    }

    // ── The pieces ───────────────────────────────────────────────────

    private fun recordedRows() = session().snapshot.value.steps

    private fun lockedExpectations(rows: List<RecordedTestStep>): RewriteExpectations {
        val rewritten = RecordedTestStep(
            action = "Search for 'lofi'", expected = "The field shows 'lofi'", sourceInputIds = listOf(rows[0].id, rows[1].id),
        )
        return RewriteExpectations(3, lockedSteps = lockedRewriteStepsOf(rows, listOf(rewritten)))
    }

    private val unchanged = """{"action":"Search for 'lofi'","expected":"The field shows 'lofi'","sourceInputs":[1,2]}"""
    private val open = """{"action":"Submit","expected":"Results","sourceInputs":[3]}"""

    @Test
    fun aLockedGroupReturnedUnchangedPassesTheValidator() {
        val expectations = lockedExpectations(recordedRows())

        val proposal = parseRewriteResponse("""{"steps":[$unchanged,$open]}""", expectations)

        assertEquals(listOf(listOf(1, 2), listOf(3)), proposal.steps.map { it.sourceInputs })
    }

    @Test
    fun aLockedGroupMayStandAnywhereAmongTheOpenSteps() {
        val rows = recordedRows()
        val rewritten = RecordedTestStep(action = "Submit", expected = "Results", sourceInputIds = listOf(rows[2].id))
        val expectations = RewriteExpectations(3, lockedSteps = lockedRewriteStepsOf(rows, listOf(rewritten)))

        val proposal = parseRewriteResponse(
            """{"steps":[{"action":"Search for 'lofi'","expected":"Typed","sourceInputs":[1,2]},""" +
                """{"action":"Submit","expected":"Results","sourceInputs":[3]}]}""",
            expectations,
        )

        assertEquals(2, proposal.steps.size)
    }

    @Test
    fun aChangedSplitOrMergedLockedGroupIsRejectedWithItsReason() {
        val expectations = lockedExpectations(recordedRows())
        val cases = mapOf(
            """{"steps":[{"action":"Search","expected":"The field shows 'lofi'","sourceInputs":[1,2]},$open]}""" to "action and expected must equal",
            """{"steps":[{"action":"Tap Search","expected":"x","sourceInputs":[1]},{"action":"Type","expected":"y","sourceInputs":[2]},$open]}""" to
                "must be one step with sourceInputs [1, 2]",
            """{"steps":[{"action":"All","expected":"x","sourceInputs":[1,2,3]}]}""" to "must be one step with sourceInputs [1, 2]",
            """{"steps":[$unchanged,{"action":"Submit","expected":"Results","sourceInputs":[3],"kind":"manual"}]}""" to "",
        )
        for ((answer, expected) in cases) {
            val problem = assertFailsWith<IllegalArgumentException>(answer) { parseRewriteResponse(answer, expectations) }
            assertTrue(expected in problem.message.orEmpty(), problem.message)
        }
        val dropped = """{"steps":[$open]}"""
        assertTrue(assertFailsWith<IllegalArgumentException> { parseRewriteResponse(dropped, expectations) }.message.orEmpty().isNotBlank())
        val kind = """{"steps":[{"action":"Search for 'lofi'","expected":"The field shows 'lofi'","sourceInputs":[1,2],"kind":"manual"},$open]}"""
        assertTrue("kind" in assertFailsWith<IllegalArgumentException> { parseRewriteResponse(kind, expectations) }.message.orEmpty())
    }

    @Test
    fun theRowTheAnswerKeepsIsTheLockedRowItself() {
        val rows = recordedRows()
        val locked = RecordedTestStep(
            action = "Search for 'lofi'", expected = "The field shows 'lofi'", sourceInputIds = listOf(rows[0].id, rows[1].id), useScreenshotAsExpected = true,
        )
        val lock = LockedRewriteStep(listOf(1, 2), locked)
        val inputs = rewriteInputsOf(rows, listOf(lock)) { null }
        val proposal = parseRewriteResponse("""{"steps":[$unchanged,$open]}""", RewriteExpectations(3, lockedSteps = listOf(lock)))

        val built = buildRewrittenRows(proposal, inputs)

        assertSame(locked, built[0])
        assertEquals(listOf(rows[2].id), built[1].sourceInputIds)
    }

    @Test
    fun onlyConsecutiveGroupsWhoseInputsAreAllThereCanBeLocked() {
        val rows = recordedRows()
        val whole = RecordedTestStep(action = "A", expected = "x", sourceInputIds = listOf(rows[0].id, rows[1].id))
        val gap = RecordedTestStep(action = "B", expected = "y", sourceInputIds = listOf(rows[0].id, rows[2].id))
        val missing = RecordedTestStep(action = "C", expected = "z", sourceInputIds = listOf(rows[2].id, "gone"))
        val tooLong = RecordedTestStep(action = "D".repeat(2_001), expected = "x", sourceInputIds = listOf(rows[0].id))

        assertEquals(listOf(listOf(1, 2)), lockedRewriteStepsOf(rows, listOf(whole, gap, missing)).map { it.inputs })
        assertTrue(listOf(whole, tooLong).map { it.isAcceptedRewriteStep() } == listOf(true, false))
        assertFalse(RecordedTestStep(action = "Mine", expected = "x", authorAdded = true).isAcceptedRewriteStep())
        assertFalse(RecordedTestStep(action = "Raw").isAcceptedRewriteStep())
        assertFalse(RecordedTestStep(action = "A", sourceInputIds = listOf("x"), reviewReason = "Check").isAcceptedRewriteStep())
    }

    @Test
    fun lockedInputsGetNoEvidenceAndNoToolAccess() {
        val session = session()
        val rows = session.snapshot.value.steps
        val lockedRow = RecordedTestStep(action = "Search for 'lofi'", expected = "Typed", sourceInputIds = listOf(rows[0].id, rows[1].id))
        val lock = LockedRewriteStep(listOf(1, 2), lockedRow)
        val inputs = rewriteInputsOf(rows, listOf(lock), session::screenState)

        val evidence = buildRewriteEvidence(inputs)

        assertEquals(listOf(3), evidence.images.flatMap { it.inputs }.distinct())
        assertEquals("", evidence.lineExtra(1, true))
        val tools = RecordingRewriteTools(inputs)
        val input = tools.gateway.execute("get_recorded_input", mapOf("index" to 2)) as Map<*, *>
        assertEquals(true, input["locked"])
        assertEquals(listOf(1, 2), input["coveredInputs"])
        assertTrue("already accepted" in input["message"].toString())
    }

    @Test
    fun thePromptsTellTheModelWhatALoadingScreenIs() {
        assertTrue("A loading, splash or transition screen right after an action is not that action's result" in REWRITE_SYSTEM_PROMPT)
        assertTrue("'Open <app>'" in REWRITE_SYSTEM_PROMPT && "get_recorded_video_storyboard" in REWRITE_SYSTEM_PROMPT)
        assertTrue("Use reviewReason only when the evidence, video included, genuinely cannot tell" in REWRITE_SYSTEM_PROMPT)
        assertTrue("A line marked [$LOCKED_STEP_LABEL]" in REWRITE_SYSTEM_PROMPT)
        val session = session()
        val suite = shopLibrary().suites.single()
        val prompt = rewritePrompt(suite, suite.cases.single(), "", rewriteInputsOf(session.snapshot.value.steps) { null })
        assertTrue("A loading, splash or transition screen right after an action is not its result" in prompt.replace(Regex("\\s+"), " "), prompt)
        assertTrue("A line marked [$LOCKED_STEP_LABEL] is a step the person already accepted" in prompt.replace(Regex("\\s+"), " "), prompt)
    }

    // ── The scope and the buttons ────────────────────────────────────

    @Test
    fun theScopeNamesParseOrAreRefused() {
        assertEquals(RewriteScope.NEEDS_REVIEW, RewriteScope.parse("needs_review"))
        assertEquals(RewriteScope.NEEDS_REVIEW, RewriteScope.parse(" Needs_Review "))
        assertEquals(RewriteScope.ALL, RewriteScope.parse("all"))
        assertNull(RewriteScope.parse(null))
        assertNull(RewriteScope.parse("  "))
        assertTrue("needs_review or all" in assertFailsWith<IllegalArgumentException> { RewriteScope.parse("some") }.message.orEmpty())
    }

    @Test
    fun theButtonsOfTheRewriteSectionFollowWhatTheRewriteLeftBehind() {
        val fresh = session().snapshot.value
        assertEquals("Rewrite", rewriteButtonPlan(fresh, rewriting = false).primaryLabel)
        assertNull(rewriteButtonPlan(fresh, rewriting = false).allLabel)

        val mixed = rewrittenOnce().snapshot.value
        val plan = rewriteButtonPlan(mixed, rewriting = false)
        assertEquals(REWRITE_REVIEW_LABEL, plan.primaryLabel)
        assertEquals("Rewrite steps that need review", plan.primaryLabel)
        assertEquals(RewriteScope.NEEDS_REVIEW, plan.primaryScope)
        assertEquals(REWRITE_ALL_AGAIN_LABEL, plan.allLabel)
        assertEquals("Rewrite all again", plan.allLabel)
        assertEquals(1 to 1, plan.keptSteps to plan.reviewSteps)
        val busy = rewriteButtonPlan(mixed, rewriting = true)
        assertEquals("Rewriting…", busy.primaryLabel)
        assertNull(busy.allLabel)

        val allReview = rewrittenOnce("""{"steps":[{"action":"Do","expected":"","sourceInputs":[1,2,3],"reviewReason":"Check"}]}""").snapshot.value
        assertEquals("Rewrite again", rewriteButtonPlan(allReview, rewriting = false).primaryLabel)
        assertNull(rewriteButtonPlan(allReview, rewriting = false).primaryScope)
        assertNull(rewriteButtonPlan(allReview, rewriting = false).allLabel)

        val allAccepted = rewrittenOnce(VALID_SHOP_REWRITE).snapshot.value
        assertEquals("Rewrite again", rewriteButtonPlan(allAccepted, rewriting = false).primaryLabel)
        assertNull(rewriteButtonPlan(allAccepted, rewriting = false).allLabel)
    }
}
