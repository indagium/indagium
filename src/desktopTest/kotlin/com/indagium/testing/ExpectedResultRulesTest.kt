@file:Suppress("MagicNumber") // Fixture counts and timeouts, not tunable constants.

package com.indagium.testing

import com.indagium.testing.authoring.INCIDENTAL_EXPECTED_WARNING
import com.indagium.testing.authoring.RecordingRewrite
import com.indagium.testing.authoring.RecordingRewriteService
import com.indagium.testing.authoring.RewriteGeneration
import com.indagium.testing.authoring.STABLE_EXPECTED_DRAFT_REMEDY
import com.indagium.testing.authoring.STABLE_EXPECTED_REWRITE_REMEDY
import com.indagium.testing.authoring.STABLE_EXPECTED_RULE
import com.indagium.testing.authoring.TestStepDraftService
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.incidentalExpectedWarning
import com.indagium.testing.authoring.looksIncidental
import com.indagium.testing.authoring.recordingApplyBlockedReason
import com.indagium.testing.authoring.reviewReasonAfterEdit
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestSuite
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Expected results must describe the stable outcome of an action. The AI is told so (rewrite and draft prompts), and a
 * deterministic check flags, without rejecting, the expected results that still name content which changes between runs.
 */
class ExpectedResultRulesTest {
    private val sessions = mutableListOf<TestStepRecordingSession>()

    @AfterTest
    fun closeSessions() = sessions.forEach(TestStepRecordingSession::close)

    private fun service(generate: suspend (RewriteGeneration) -> String) =
        RecordingRewriteService(library = { shopLibrary() }, preflight = { _, _ -> StoreResult.Ok(Unit) }, generate = generate, timeoutMs = 5_000L)

    private fun rewrite(answer: String, seen: MutableList<RewriteGeneration> = mutableListOf()): TestStepRecordingSession {
        val session = recordedShopSession().also { sessions += it }
        val result = runBlocking { service { seen += it; answer }.rewrite(session, SHOP_SUITE_ID, SHOP_CASE_ID, "profile", null, null, "") }
        assertIs<StoreResult.Ok<RecordingRewrite>>(result)
        return session
    }

    // ── What the AI is told ─────────────────────────────────────────

    @Test
    fun theRewriteIsToldToDescribeOnlyStableOutcomes() {
        val seen = mutableListOf<RewriteGeneration>()
        rewrite(VALID_SHOP_REWRITE, seen)

        val request = seen.single()
        val remedy = STABLE_EXPECTED_REWRITE_REMEDY
        assertTrue("$STABLE_EXPECTED_RULE $remedy" in request.systemPrompt, "the system prompt carries the rule:\n${request.systemPrompt}")
        assertTrue("$STABLE_EXPECTED_RULE $remedy" in request.prompt, "the output contract repeats it where the expected field is defined")
        listOf("advertisements", "sponsored", "specific video titles", "timestamps", "notifications", "picture-in-picture", "recommendations").forEach {
            assertTrue(it in STABLE_EXPECTED_RULE, "the rule names '$it'")
        }
        assertTrue("mention it in notes" in remedy && "optional with a condition" in remedy, remedy)
    }

    @Test
    fun theStepDraftPromptCarriesTheSameRule() {
        val prompts = mutableListOf<String>()
        val library = TestLibrary(suites = listOf(TestSuite("suite-1", "Suite", cases = listOf(TestCase("case-1", "Case")))))
        val service = TestStepDraftService(
            library = { library },
            preflight = { _, _ -> StoreResult.Ok(Unit) },
            generate = { _, prompt, _, _ -> prompts += prompt; """{"steps":[{"action":"Tap sign in","expected":"Home appears"}]}""" },
            insert = { _, steps, _ -> StoreResult.Ok(steps) },
            assetExists = { _, _ -> true },
        )

        assertIs<StoreResult.Ok<*>>(runBlocking { service.create("suite-1", "case-1", "profile", "Sign in") })

        assertTrue("$STABLE_EXPECTED_RULE $STABLE_EXPECTED_DRAFT_REMEDY" in prompts.single(), prompts.single())
        assertFalse("notes" in STABLE_EXPECTED_DRAFT_REMEDY, "a draft has no notes field and no optional steps to point to")
    }

    // ── The check ───────────────────────────────────────────────────

    @Test
    fun anExpectedResultThatNamesChangingContentIsFlagged() {
        listOf(
            "A YouTube video player opens and a sponsored advertisement is visible.",
            "The Android home screen appears with YouTube in a small picture-in-picture window.",
            "The video keeps playing in Picture in Picture",
            "YouTube shows as a PiP window",
            "An ad is playing",
            "Ads are shown above the feed",
            "A notification about the download is shown",
            "The video shows 1.2M views",
            "The page lists 340 views",
            "The player shows 3:45 of 10:02",
            "The clock reads 12:30",
            "An advert banner is displayed",
        ).forEach { expected ->
            assertTrue(looksIncidental(expected), "should be flagged: $expected")
            assertEquals(INCIDENTAL_EXPECTED_WARNING, incidentalExpectedWarning(expected, optional = false), expected)
        }
    }

    @Test
    fun anOrdinaryExpectedResultIsNotFlagged() {
        listOf(
            "Add to cart is visible",
            "The item is added to the cart",
            "Results for 'lofi' are listed",
            "The cart view opens",
            "The notification shade is open",
            "The Notifications settings screen is shown",
            "The address field shows the saved address",
            "The video is paused and the play button is visible",
            "The aspect ratio option reads 16:9",
            "The download list is empty",
            "A pipeline status label is visible",
            "The home screen is shown",
        ).forEach { expected ->
            assertFalse(looksIncidental(expected), "should not be flagged: $expected")
            assertNull(incidentalExpectedWarning(expected, optional = false), expected)
        }
    }

    @Test
    fun aConditionalOptionalStepIsExemptBecauseThatIsWhereAnAdBelongs() {
        assertNull(incidentalExpectedWarning("The ad is dismissed", optional = true))
        assertEquals(INCIDENTAL_EXPECTED_WARNING, incidentalExpectedWarning("The ad is dismissed", optional = false))
    }

    // ── The rewrite flags, it does not reject ───────────────────────

    @Test
    fun aFlaggedExpectedResultIsKeptAndTheRowCarriesTheWarning() {
        val answer = """{"steps":[
            {"action":"Search for 'lofi'","expected":"A sponsored result is listed first","sourceInputs":[1,2]},
            {"action":"Submit the search","expected":"Results for 'lofi' are listed","sourceInputs":[3]}
        ]}"""

        val snapshot = rewrite(answer).snapshot.value
        val rows = snapshot.steps

        assertEquals("A sponsored result is listed first", rows[0].expected, "kept as the AI wrote it")
        assertEquals(INCIDENTAL_EXPECTED_WARNING, rows[0].reviewReason)
        assertNull(rows[1].reviewReason, "a stable result carries no warning")
    }

    @Test
    fun anOptionalAdStepAndAStepTheAiAlreadyExplainedAreNotOverwritten() {
        val answer = """{"steps":[
            {"action":"Search for 'lofi'","expected":"","optional":true,"condition":"A skippable ad is visible","sourceInputs":[1,2]},
            {"action":"Submit the search","expected":"A notification is shown","sourceInputs":[3],"reviewReason":"Confirm what appears."}
        ]}"""

        val snapshot = rewrite(answer).snapshot.value
        val rows = snapshot.steps

        assertNull(rows[0].reviewReason)
        assertEquals("Confirm what appears.", rows[1].reviewReason, "the AI's own reason wins over the generic warning")
        assertEquals("A notification is shown", rows[1].expected, "the model's supported wording stays visible for review")
        assertTrue(
            recordingApplyBlockedReason(snapshot).orEmpty().contains("Resolve the rewrite review note"),
            "the note still prevents applying an unresolved proposal",
        )
    }

    @Test
    fun theWarningFollowsTheReviewersEditsOfTheExpectedResult() {
        val answer = """{"steps":[
            {"action":"Search for 'lofi'","expected":"A sponsored result is listed first","sourceInputs":[1,2]},
            {"action":"Submit the search","expected":"Results for 'lofi' are listed","sourceInputs":[3]}
        ]}"""
        val session = rewrite(answer)
        val rows = session.snapshot.value.steps
        val ids = rows.map { it.id }

        assertTrue(session.updateReviewedSteps(rows.map { it.action to it.expected }, expectedRowIds = ids))
        assertEquals(INCIDENTAL_EXPECTED_WARNING, session.snapshot.value.steps[0].reviewReason, "an edit elsewhere keeps the warning")

        assertTrue(session.updateReviewedSteps(listOf(rows[0].action to "An ad is playing", rows[1].action to rows[1].expected), expectedRowIds = ids))
        assertEquals(INCIDENTAL_EXPECTED_WARNING, session.snapshot.value.steps[0].reviewReason, "still incidental: still flagged")

        assertTrue(session.updateReviewedSteps(listOf(rows[0].action to "Results are listed", rows[1].action to rows[1].expected), expectedRowIds = ids))
        assertNull(session.snapshot.value.steps[0].reviewReason, "reworded to something stable: the warning goes")
    }

    @Test
    fun aReasonAboutABlankExpectedResultStaysUntilItIsFilledIn() {
        assertEquals("Identify the tap.", reviewReasonAfterEdit("Identify the tap.", "", optional = false))
        assertNull(reviewReasonAfterEdit("Identify the tap.", "Search opens", optional = false))
        assertNull(reviewReasonAfterEdit(null, "An ad is playing", optional = false))
    }
}
