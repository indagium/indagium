@file:Suppress("MagicNumber") // Fixture counts and timeouts, not tunable constants.

package com.indagium.testing

import com.indagium.model.AiUsageStats
import com.indagium.testing.authoring.RecordingRewrite
import com.indagium.testing.authoring.RecordingRewriteService
import com.indagium.testing.authoring.RewriteGeneration
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.TestStepRecordingSnapshot
import com.indagium.testing.limits.LimitDecision
import com.indagium.testing.limits.LimitKind
import com.indagium.testing.model.StepCheck
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingRewriteServiceTest {
    private val sessions = mutableListOf<TestStepRecordingSession>()

    @AfterTest
    fun closeSessions() = sessions.forEach(TestStepRecordingSession::close)

    private fun session(typed: String = "lofi", passwordField: Boolean = false) = recordedShopSession(typed, passwordField).also { sessions += it }

    private fun service(
        timeoutMs: Long = 5_000L,
        preflight: (String, String) -> StoreResult<Unit> = { _, _ -> StoreResult.Ok(Unit) },
        generate: suspend (RewriteGeneration) -> String = { VALID_SHOP_REWRITE },
    ) = RecordingRewriteService(library = { shopLibrary() }, preflight = preflight, generate = generate, timeoutMs = timeoutMs)

    private suspend fun RecordingRewriteService.run(session: TestStepRecordingSession, note: String = "", model: String? = null, effort: String? = null) =
        rewrite(session, SHOP_SUITE_ID, SHOP_CASE_ID, "profile", model, effort, note)

    private fun invalid(result: StoreResult<RecordingRewrite>): String = assertIs<StoreResult.Invalid>(result).reason

    // ── A valid rewrite ──────────────────────────────────────────────

    @Test
    fun aValidMergeReplacesTheRowsAndKeepsTheRecordedOnes() = runBlocking {
        val session = session()
        val recorded = session.snapshot.value.steps
        val seen = mutableListOf<RewriteGeneration>()
        val result = service(generate = { seen += it; VALID_SHOP_REWRITE }).run(session, note = "Searching for music", model = "m-1", effort = "high")

        val rewrite = assertIs<StoreResult.Ok<RecordingRewrite>>(result).value
        val snapshot = session.snapshot.value
        assertEquals(3, recorded.size)
        assertEquals(2, snapshot.steps.size)
        assertEquals(snapshot.steps, rewrite.steps)
        assertEquals(recorded, snapshot.rawSteps, "the recorded rows are kept exactly")
        assertEquals("Merged the tap on Search with the typing.", snapshot.rewriteNotes)
        assertEquals(listOf(recorded[0].id, recorded[1].id), snapshot.steps[0].sourceInputIds)
        assertEquals(listOf(recorded[2].id), snapshot.steps[1].sourceInputIds)
        assertEquals("Search for 'lofi'", snapshot.steps[0].action)
        assertEquals("Results for 'lofi' are listed", snapshot.steps[1].expected)
        assertTrue(snapshot.steps.none { it.useScreenshotAsExpected }, "an expected screenshot is only ever offered")
        assertNull(snapshot.steps[0].screenshotJpeg, "input 2 has no screen after it")
        assertTrue(SHOP_AFTER_IMAGE.contentEquals(snapshot.steps[1].screenshotJpeg), "the after-image of the input the AI named")

        val request = seen.single()
        assertEquals(listOf("profile", "m-1", "high"), listOf(request.profileId, request.model, request.effort))
        assertEquals(9, request.toolCallLimit, "three looks per input")
        assertTrue("Searching for music" in request.prompt)
        assertTrue("Shop suite" in request.prompt && "Use the staging account." in request.prompt && SHOP_PACKAGE in request.prompt)
        assertTrue("Search for music" in request.prompt && "Find a playlist" in request.prompt)
        assertTrue("1. [TAP] Tap at (54%, 31%)" in request.prompt && "element: \"Search\"" in request.prompt, request.prompt)
        assertTrue("before UI: available" in request.prompt && "after UI: unavailable" in request.prompt, request.prompt)
        assertTrue("never request unavailable evidence" in request.systemPrompt, request.systemPrompt)
        assertTrue("Repeated capture IDs" in request.systemPrompt, request.systemPrompt)
        assertTrue("Example uncertain step" in request.prompt && "reviewReason" in request.prompt, request.prompt)
        assertTrue("2. [TEXT] Enter text: lofi" in request.prompt && "3. [KEY] Press Enter" in request.prompt, request.prompt)
        assertTrue("<untrusted_data source=\"recorded_inputs\">" in request.prompt, "device and typed text is fenced")
        assertEquals(listOf("get_recorded_input", "get_recorded_screen", "get_recorded_ui"), request.gateway.tools.map { it.name })
    }

    @Test
    fun rewritingAgainStartsFromTheRecordedRowsNotFromThePreviousRewrite() = runBlocking {
        val session = session()
        val recorded = session.snapshot.value.steps
        val prompts = mutableListOf<String>()
        val service = service(generate = { prompts += it.prompt; VALID_SHOP_REWRITE })
        service.run(session)
        val second = service.run(session)

        assertIs<StoreResult.Ok<RecordingRewrite>>(second)
        assertEquals(prompts[0], prompts[1], "the second brief describes the same recorded inputs")
        assertEquals(recorded, session.snapshot.value.rawSteps)
        assertEquals(2, session.snapshot.value.steps.size)
    }

    @Test
    fun exclusionsRemainOutOfRepeatRewriteAndCoverageIsRequiredForEveryRetainedInput() = runBlocking {
        val session = session()
        val original = session.snapshot.value.steps
        assertTrue(session.removeReviewedRows(setOf(original[1].id)))
        val afterRemoval = session.snapshot.value
        val incomplete = service(generate = {
            """{"steps":[{"action":"Search","expected":"Search is ready","sourceInputs":[1]}]}"""
        }).run(session)
        assertTrue(invalid(incomplete).contains("input 2"))
        assertRejectedRewritePreservesReview(afterRemoval, session.snapshot.value)
        assertEquals(afterRemoval.excludedSourceInputIds, session.snapshot.value.excludedSourceInputIds)

        val seen = mutableListOf<RewriteGeneration>()
        val valid = service(generate = { seen += it; """{"steps":[
            {"action":"Search","expected":"Search field is ready","sourceInputs":[1]},
            {"action":"Submit","expected":"Results appear","sourceInputs":[2]}
        ]}""" }).run(session)
        assertIs<StoreResult.Ok<RecordingRewrite>>(valid)
        val prompt = seen.single().prompt
        assertTrue("1. [TAP]" in prompt && "2. [KEY]" in prompt, prompt)
        assertTrue("Do not recreate" in prompt || "do not recreate" in prompt, prompt)
        assertEquals(listOf(original[0].id, original[2].id), session.snapshot.value.steps.flatMap { it.sourceInputIds })
        assertEquals(setOf(original[1].id), session.snapshot.value.excludedSourceInputIds)

        assertTrue(session.restoreRaw())
        assertEquals(listOf(original[0].id, original[2].id), session.snapshot.value.steps.map { it.id })
        assertTrue(session.restoreExcludedInputs())
        assertEquals(original, session.snapshot.value.steps)
    }

    @Test
    fun markdownFencedJsonAndChatterAroundTheObjectAreAccepted() = runBlocking {
        val fenced = "```json\n$VALID_SHOP_REWRITE\n```"
        val chatter = "Here is the rewrite:\n$VALID_SHOP_REWRITE\nLet me know if you want changes."
        listOf(fenced, chatter).forEach { text ->
            val session = session()
            assertIs<StoreResult.Ok<RecordingRewrite>>(service(generate = { text }).run(session), text)
            assertEquals(2, session.snapshot.value.steps.size)
        }
    }

    @Test
    fun checksAreValidatedLikeADraftAndKeptOnTheRow() = runBlocking {
        val session = session()
        val response = """{"steps":[{"action":"Do all","expected":"Done","sourceInputs":[1,2,3],
            "reviewReason":null,"checks":[{"type":"askJudge","text":"Is the result list visible?"}]}]}"""
        assertIs<StoreResult.Ok<RecordingRewrite>>(service(generate = { response }).run(session))
        val check = session.snapshot.value.steps.single().checks.single()
        assertEquals("Is the result list visible?", assertIs<StepCheck.AskJudge>(check).text)
    }

    @Test
    fun anIncidentalOptionalActionStaysSeparateFromTheRequiredPlaybackAction() = runBlocking {
        val response = """{"steps":[
            {"action":"Dismiss the ad if Skip ad appears","expected":"","optional":true,
                "condition":"A visible Skip ad button appears.","sourceInputs":[1]},
            {"action":"Pause playback","expected":"The player shows paused state","sourceInputs":[2,3]}
        ]}"""
        val session = session()
        val seen = mutableListOf<RewriteGeneration>()

        assertIs<StoreResult.Ok<RecordingRewrite>>(service(generate = { seen += it; response }).run(session))

        val rows = session.snapshot.value.steps
        assertEquals(listOf("Dismiss the ad if Skip ad appears", "Pause playback"), rows.map { it.action })
        assertEquals(listOf(true, false), rows.map { it.optional })
        assertEquals("A visible Skip ad button appears.", rows.first().condition)
        assertEquals("", rows.first().expected)
        assertEquals(1, rows.first().sourceInputIds.size)
        assertEquals(2, rows.last().sourceInputIds.size)
        assertTrue(
            "never merge an optional ad dismissal with a required action" in seen.single().prompt,
        )
    }

    @Test
    fun emptyAndWhitespaceReviewReasonsAreNormalizedToAbsent() = runBlocking {
        val session = session()
        val response = """{"steps":[
            {"action":"Tap Search","expected":"Search field is ready","sourceInputs":[1],"reviewReason":""},
            {"action":"Enter query","expected":"Query is entered","sourceInputs":[2],"reviewReason":"   "},
            {"action":"Submit search","expected":"Results are shown","sourceInputs":[3],"reviewReason":" \t\n "}
        ]}"""

        assertIs<StoreResult.Ok<RecordingRewrite>>(service(generate = { response }).run(session))
        assertEquals(listOf(null, null, null), session.snapshot.value.steps.map { it.reviewReason })
    }

    // ── An invalid answer changes nothing ────────────────────────────

    private fun step(vararg sources: Int, extra: String = "") =
        """{"action":"A","expected":"B","sourceInputs":[${sources.joinToString(",")}]$extra}"""

    private fun answer(vararg steps: String) = """{"steps":[${steps.joinToString(",")}]}"""

    private fun assertRejected(response: String, vararg reasonParts: String) = runBlocking {
        val session = session()
        val before = session.snapshot.value
        val reason = invalid(service(generate = { response }).run(session))
        reasonParts.forEach { assertTrue(it in reason, "'$it' missing from: $reason") }
        assertRejectedRewritePreservesReview(before, session.snapshot.value)
        assertNull(session.snapshot.value.rawSteps)
    }

    private fun assertRejectedRewritePreservesReview(before: TestStepRecordingSnapshot, after: TestStepRecordingSnapshot) {
        val reviewAfterAttempt = after.copy(
            rewriteUsage = before.rewriteUsage,
            rewriteUsageTotal = before.rewriteUsageTotal,
            rewriteUsagePending = before.rewriteUsagePending,
            rewriteAttemptCount = before.rewriteAttemptCount,
            rewriteInProgress = before.rewriteInProgress,
        )
        assertEquals(before, reviewAfterAttempt, "a rejected answer preserves rows, evidence and exclusions")
        assertEquals(before.rewriteAttemptCount + 1, after.rewriteAttemptCount)
        assertFalse(after.rewriteInProgress)
        assertTrue(after.rewriteUsage?.partial == true, "the failed attempt keeps its unknown usage visible")
    }

    @Test
    fun coverageGapsOverlapsAndOutOfOrderInputsAreRejected() {
        assertRejected(answer(step(1, 2)), "input 3", "not covered")
        assertRejected(answer(step(1), step(3)), "input 2 is missing")
        assertRejected(answer(step(1, 2), step(2, 3)), "more than one step")
        assertRejected(answer(step(1, 1, 2, 3)), "more than one step")
        assertRejected(answer(step(1), step(3), step(2)), "in order", "input 2 is missing")
        assertRejected(answer(step(3), step(1, 2)), "in order")
        assertRejected(answer(step(1, 2, 3, 4)), "from 1 to 3")
        assertRejected(answer(step(0, 1, 2, 3)), "from 1 to 3")
        assertRejected(answer(step(extra = "")), "sourceInputs")
    }

    @Test
    fun blankFieldsAndTooManyStepsAreRejected() {
        assertRejected(answer("""{"action":"  ","expected":"B","sourceInputs":[1,2,3]}"""), "steps[0].action")
        assertRejected(answer("""{"action":"A","expected":"","sourceInputs":[1,2,3]}"""), "steps[0].expected")
        assertRejected(answer("""{"action":"A","sourceInputs":[1,2,3]}"""), "steps[0].expected")
        assertRejected("""{"steps":[]}""", "between 1 and 20")
        val tooMany = (1..21).joinToString(",") { """{"action":"A$it","expected":"B","sourceInputs":[1]}""" }
        assertRejected("""{"steps":[$tooMany]}""", "between 1 and 20")
        assertRejected("not json at all", "valid JSON")
        assertRejected("[]", "JSON object")
        assertRejected("""{"notes":"no steps"}""", "'steps' array")
    }

    @Test
    fun aBlankExpectedIsAcceptedOnlyWhenReviewReasonExplainsIt() = runBlocking {
        val session = session()
        val response = """{"steps":[{"action":"Search","expected":"",
            "reviewReason":"Confirm the result after search.","sourceInputs":[1,2,3]}]}"""

        assertIs<StoreResult.Ok<RecordingRewrite>>(service(generate = { response }).run(session))
        val row = session.snapshot.value.steps.single()
        assertEquals("", row.expected)
        assertEquals("Confirm the result after search.", row.reviewReason)
        assertTrue(row.action.contains("Tap at"), "review-required output keeps the recorded gesture description")
    }

    @Test
    fun anOptionalProposalWithReviewReasonBecomesARequiredRawReviewRow() = runBlocking {
        val session = session()
        val response = """{"steps":[{"action":"Skip ad","expected":"","optional":true,
            "condition":"A Skip ad button is visible.","reviewReason":"Confirm this gesture dismisses the ad.","sourceInputs":[1,2,3]}]}"""

        assertIs<StoreResult.Ok<RecordingRewrite>>(service(generate = { response }).run(session))

        val row = session.snapshot.value.steps.single()
        assertTrue(
            row.action.contains("Tap at"),
            "the gesture remains raw until someone resolves the target",
        )
        assertEquals("", row.expected)
        assertFalse(row.optional, "review uncertainty cannot be bypassed by optional=true")
        assertNull(row.condition)
        assertTrue(row.reviewReason.orEmpty().isNotBlank())
    }

    @Test
    fun whitespaceReasonDoesNotAuthorizeBlankExpectedAndInvalidReasonTypesOrLengthsAreRejected() {
        assertRejected(
            answer("""{"action":"A","expected":"","reviewReason":" \t\n ","sourceInputs":[1,2,3]}"""),
            "expected may be blank only",
        )
        assertRejected(
            answer("""{"action":"A","expected":"","reviewReason":null,"sourceInputs":[1,2,3]}"""),
            "expected may be blank only",
        )
        assertRejected(answer(step(1, 2, 3, extra = ""","reviewReason":7""")), "reviewReason must be a string or null")
        val tooLong = "x".repeat(501)
        assertRejected(answer(step(1, 2, 3, extra = ""","reviewReason":"$tooLong"""")), "longer than 500")
    }

    @Test
    fun checksThatPointOutsideTheStepAreRejected() {
        assertRejected(answer(step(1, 2, 3, extra = ""","checks":[{"type":"scriptResult","scriptId":"s1"}]""")), "script checks")
        assertRejected(answer(step(1, 2, 3, extra = ""","checks":[{"type":"screenJudge","text":"Same?","exampleRef":"ex-1"}]""")), "example")
        assertRejected(answer(step(1, 2, 3, extra = ""","checks":[{"type":"logAppears"}]""")), "regex")
        assertRejected(answer(step(1, 2, 3, extra = ""","checks":"none"""")), "checks must be an array")
    }

    @Test
    fun anExpectedScreenshotReferenceMustNameOneOfTheStepsInputs() {
        assertRejected(answer(step(1, 2, 3, extra = ""","expectedScreenshot":"after-of-input-9"""")), "expectedScreenshot")
        assertRejected(answer(step(1, 2, 3, extra = ""","expectedScreenshot":"the last one"""")), "expectedScreenshot")
        assertRejected(answer(step(1, 2), step(3, extra = ""","expectedScreenshot":"after-of-input-1"""")), "expectedScreenshot")
    }

    @Test
    fun aProviderFailureLeavesTheRecordingAsItWas() = runBlocking {
        val session = session()
        val before = session.snapshot.value.steps
        val reason = invalid(service(generate = { error("The provider could not rewrite the recording: boom") }).run(session))
        assertTrue("boom" in reason)
        assertEquals(before, session.snapshot.value.steps)
        assertEquals(1, session.snapshot.value.rewriteAttemptCount)
        assertFalse(session.snapshot.value.rewriteInProgress)
        assertTrue(session.snapshot.value.rewriteUsage?.partial == true)
    }

    // ── Timeout and cancel ───────────────────────────────────────────

    @Test
    fun aSlowProviderTimesOutWithAnActionableMessage() = runBlocking {
        val session = session()
        val before = session.snapshot.value.steps
        val reason = invalid(service(timeoutMs = 20, generate = { awaitCancellation() }).run(session))
        assertTrue("timed out" in reason, reason)
        assertEquals(before, session.snapshot.value.steps)
        assertTrue(session.snapshot.value.rewriteUsage?.partial == true)
        assertNull(session.snapshot.value.rewriteUsage?.inputTokens)
    }

    @Test
    fun cancellingTheCallerCancelsTheProviderAndChangesNothing() = runBlocking {
        val session = session()
        val before = session.snapshot.value.steps
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val request = async {
            service(generate = { generation ->
                try {
                    generation.onUsage(AiUsageStats(inputTokens = 23L, outputTokens = 7L, totalTokens = 30L))
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }).run(session)
        }
        entered.await()
        request.cancelAndJoin()
        withTimeout(2_000) { cancelled.await() }
        assertTrue(request.isCancelled)
        assertEquals(before, session.snapshot.value.steps)
        assertFalse(session.snapshot.value.rewriteInProgress)
        assertEquals(23L, session.snapshot.value.rewriteUsage?.inputTokens)
        assertEquals(30L, session.snapshot.value.rewriteUsage?.totalTokens)
        assertTrue(session.snapshot.value.rewriteUsage?.partial == true)
    }

    @Test
    fun theRecordingCannotBeAppliedUnderneathARewriteThatFinishesLate() = runBlocking {
        val session = session()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            service(generate = { entered.complete(Unit); release.await(); VALID_SHOP_REWRITE }).run(session)
        }
        entered.await()
        assertTrue(session.freezeReviewedSnapshotForApply() != null, "an apply reserved the recording while the AI was working")
        release.complete(Unit)

        assertTrue("nothing was applied" in invalid(request.await()))
        assertNull(session.snapshot.value.rawSteps)
        assertEquals(3, session.snapshot.value.steps.size)
    }

    // ── Preflight and state ──────────────────────────────────────────

    @Test
    fun aReadOnlyOrLimitedCaseIsRefusedBeforeAnyPaidCall() = runBlocking {
        val calls = AtomicInteger()
        val refusals = listOf<StoreResult<Unit>>(
            StoreResult.Invalid("The case is read-only."),
            StoreResult.LimitReached(LimitDecision.Refused(LimitKind.LOCKED, "Locked by the Free limit.")),
            StoreResult.NotFound("case", SHOP_CASE_ID),
        )
        refusals.forEach { refusal ->
            val session = session()
            val result = service(preflight = { _, _ -> refusal }, generate = { calls.incrementAndGet(); VALID_SHOP_REWRITE }).run(session)
            assertEquals(refusal::class.simpleName, result::class.simpleName)
            assertNull(session.snapshot.value.rawSteps)
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun anActiveEmptyPendingOrAppliedRecordingCannotBeRewritten() = runBlocking {
        val calls = AtomicInteger()
        val service = service(generate = { calls.incrementAndGet(); VALID_SHOP_REWRITE })

        val active = TestStepRecordingSession("fixture-device").also { sessions += it }
        assertTrue("Stop the recording" in invalid(service.run(active)))

        val empty = TestStepRecordingSession("fixture-device").also { sessions += it; it.stop() }
        assertTrue("No recorded inputs remain" in invalid(service.run(empty)))

        val applying = session()
        assertTrue(applying.freezeReviewedSnapshotForApply() != null)
        assertTrue("being applied" in invalid(service.run(applying)))
        applying.releaseApplyReservation()

        assertTrue("limited to" in invalid(service.run(session(), note = "n".repeat(1_001))))
        assertEquals(0, calls.get())
    }

    // ── Privacy ──────────────────────────────────────────────────────

    @Test
    fun aHiddenPasswordNeverReachesThePromptOrTheTools() = runBlocking {
        val session = session(typed = "hunter2", passwordField = true)
        assertEquals("Enter text: ••••", session.snapshot.value.steps[0].action)
        var prompt = ""
        var toolText = ""
        val result = service(
            generate = { request ->
                prompt = request.prompt + request.systemPrompt
                toolText = (1..3).joinToString("\n") { index ->
                    listOf("before", "after").joinToString("\n") { moment ->
                        "${request.gateway.execute("get_recorded_input", mapOf("index" to index))}" +
                            "${request.gateway.execute("get_recorded_ui", mapOf("index" to index, "moment" to moment))}"
                    }
                }
                VALID_SHOP_REWRITE
            },
        ).run(session, note = "Sign in")

        assertIs<StoreResult.Ok<RecordingRewrite>>(result)
        assertFalse("hunter2" in prompt, "the typed password is not in the brief")
        assertTrue("Enter text: ••••" in prompt)
        assertFalse("hunter2" in toolText, "nor in what the evidence tools return")
        assertTrue("Enter text: ••••" in toolText)
        assertTrue(session.snapshot.value.steps.none { "hunter2" in it.action + it.sourceHint.orEmpty() })
    }

    @Test
    fun applyRewriteRefusesWhenTheRecordedRowsAreNotTheOnesRewritten() = runBlocking {
        val session = session()
        val rows = session.snapshot.value.steps
        assertFalse(session.applyRewrite(listOf("another-id"), rows, "notes"), "different recorded rows")
        assertFalse(session.applyRewrite(rows.map { it.id }, emptyList(), "notes"), "nothing to swap in")
        assertNull(session.snapshot.value.rawSteps)
    }
}
