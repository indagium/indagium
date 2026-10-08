@file:Suppress("MagicNumber") // Fixture counts and timeouts, not tunable constants.

package com.indagium.testing

import com.indagium.testing.authoring.RecordingRewrite
import com.indagium.testing.authoring.RecordingRewriteService
import com.indagium.testing.authoring.RewriteGeneration
import com.indagium.testing.authoring.TestStepRecordingSession
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
            "checks":[{"type":"askJudge","text":"Is the result list visible?"}]}]}"""
        assertIs<StoreResult.Ok<RecordingRewrite>>(service(generate = { response }).run(session))
        val check = session.snapshot.value.steps.single().checks.single()
        assertEquals("Is the result list visible?", assertIs<StepCheck.AskJudge>(check).text)
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
        assertEquals(before, session.snapshot.value, "a rejected answer is never applied partially")
        assertNull(session.snapshot.value.rawSteps)
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
        val before = session.snapshot.value
        val reason = invalid(service(generate = { error("The provider could not rewrite the recording: boom") }).run(session))
        assertTrue("boom" in reason)
        assertEquals(before, session.snapshot.value)
    }

    // ── Timeout and cancel ───────────────────────────────────────────

    @Test
    fun aSlowProviderTimesOutWithAnActionableMessage() = runBlocking {
        val session = session()
        val before = session.snapshot.value
        val reason = invalid(service(timeoutMs = 20, generate = { awaitCancellation() }).run(session))
        assertTrue("timed out" in reason, reason)
        assertEquals(before, session.snapshot.value)
    }

    @Test
    fun cancellingTheCallerCancelsTheProviderAndChangesNothing() = runBlocking {
        val session = session()
        val before = session.snapshot.value
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val request = async {
            service(generate = {
                try {
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
        assertEquals(before, session.snapshot.value)
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
        assertTrue("No input was recorded" in invalid(service.run(empty)))

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
