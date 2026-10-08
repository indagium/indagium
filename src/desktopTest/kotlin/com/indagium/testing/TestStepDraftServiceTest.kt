package com.indagium.testing

import com.indagium.model.AiUsageStats
import com.indagium.testing.authoring.TestStepDraft
import com.indagium.testing.authoring.TestStepDraftService
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestStepDraftServiceTest {
    private fun library() = TestLibrary(
        suites = listOf(TestSuite("suite-1", "Suite", cases = listOf(TestCase("case-1", "Case")))),
    )

    private fun service(
        generate: suspend (String, String, String?, String?) -> String = { _, _, _, _ -> """{"steps":[{"action":"Tap sign in","expected":"Home appears"}]}""" },
        preflight: (String, String) -> StoreResult<Unit> = { _, _ -> StoreResult.Ok(Unit) },
        inserts: AtomicInteger = AtomicInteger(),
        insert: ((String, List<TestStep>, Int?) -> StoreResult<List<TestStep>>)? = null,
    ) = TestStepDraftService(
        library = ::library,
        preflight = preflight,
        generate = generate,
        insert = insert ?: { _, steps, _ -> inserts.incrementAndGet(); StoreResult.Ok(steps) },
        assetExists = { _, _ -> true },
    )

    @Test
    fun generationOnlyCreatesValidatedPreviewAndEditedStepsAreRevalidatedBeforeApply() = runBlocking {
        val inserts = AtomicInteger()
        val service = service(inserts = inserts)
        val preview = assertIs<StoreResult.Ok<com.indagium.testing.authoring.TestStepDraft>>(service.create("suite-1", "case-1", "profile", "Sign in"))
        assertEquals(0, inserts.get(), "preview must not mutate the library")

        val invalid = service.apply(preview.value.id, preview.value.steps.map { it.copy(action = " ") })
        assertTrue(invalid is StoreResult.Invalid)
        assertEquals(0, inserts.get())

        val edited = preview.value.steps.map { it.copy(action = "Tap the updated sign-in button") }
        val applied = assertIs<StoreResult.Ok<List<TestStep>>>(service.apply(preview.value.id, edited, 0))
        assertEquals("Tap the updated sign-in button", applied.value.single().action)
        assertEquals(1, inserts.get())
        assertTrue(service.apply(preview.value.id, edited) is StoreResult.Invalid, "a preview can be applied only once")
    }

    @Test
    fun readonlyOrFreePreflightRefusesBeforePaidGeneration() = runBlocking {
        val generatorCalls = AtomicInteger()
        val service = service(
            generate = { _, _, _, _ -> generatorCalls.incrementAndGet(); "{}" },
            preflight = { _, _ -> StoreResult.Invalid("The case is locked by the Free limit.") },
        )
        val result = service.create("suite-1", "case-1", "profile", "Add sign-in")
        assertEquals("The case is locked by the Free limit.", assertIs<StoreResult.Invalid>(result).reason)
        assertEquals(0, generatorCalls.get())
    }

    @Test
    fun malformedProviderProposalIsNeverPublishedAsAPreview() = runBlocking {
        val service = service(generate = { _, _, _, _ -> """{"steps":[{"expected":"Missing action"}]}""" })
        val result = service.create("suite-1", "case-1", "profile", "Add a step")
        assertTrue(result is StoreResult.Invalid)
    }

    @Test
    fun concurrentReplayCannotInsertTheSamePreviewTwice() = runBlocking {
        val enteredInsert = CountDownLatch(1)
        val releaseInsert = CountDownLatch(1)
        val inserts = AtomicInteger()
        val service = service(
            insert = { _, steps, _ ->
                inserts.incrementAndGet()
                enteredInsert.countDown()
                check(releaseInsert.await(2, TimeUnit.SECONDS))
                StoreResult.Ok(steps)
            },
        )
        val draft = assertIs<StoreResult.Ok<com.indagium.testing.authoring.TestStepDraft>>(service.create("suite-1", "case-1", "profile", "Add a step")).value
        val first = async(Dispatchers.Default) { service.apply(draft.id, draft.steps) }
        assertTrue(withTimeout(2_000) { kotlinx.coroutines.withContext(Dispatchers.IO) { enteredInsert.await(2, TimeUnit.SECONDS) } })
        val replay = service.apply(draft.id, draft.steps)
        assertTrue(replay is StoreResult.Invalid)
        releaseInsert.countDown()
        assertTrue(first.await() is StoreResult.Ok)
        assertEquals(1, inserts.get())
    }

    @Test
    fun draftUsageShowsLatestAndSessionTotalAndAppliesEachAttemptOnlyOnce() {
        val applied = mutableListOf<AiUsageStats?>()
        val attempt = AtomicInteger()
        val sessionId = "dialog-session"
        val service = TestStepDraftService(
            library = ::library,
            preflight = { _, _ -> StoreResult.Ok(Unit) },
            generate = { _, _, _, _ -> error("usage-aware generator is used") },
            insert = { _, steps, _ -> StoreResult.Ok(steps) },
            assetExists = { _, _ -> true },
            generateWithUsage = { _, _, _, _, report ->
                val number = attempt.incrementAndGet()
                report(AiUsageStats(toolCalls = 1, inputTokens = number * 10L, outputTokens = number.toLong(), totalTokens = number * 11L))
                """{"steps":[{"action":"Add attempt $number","expected":"It appears"}]}"""
            },
            insertWithUsage = { _, steps, _, usage -> applied += usage; StoreResult.Ok(steps) },
        )

        val first = runBlocking {
            assertIs<StoreResult.Ok<TestStepDraft>>(
                service.create("suite-1", "case-1", "profile", "Add first", authoringSessionId = sessionId),
            ).value
        }
        val second = runBlocking {
            assertIs<StoreResult.Ok<TestStepDraft>>(
                service.create("suite-1", "case-1", "profile", "Add second", authoringSessionId = sessionId),
            ).value
        }
        assertEquals(10L, first.latestUsage?.inputTokens)
        assertEquals(10L, first.creationUsage?.inputTokens)
        assertEquals(20L, second.latestUsage?.inputTokens)
        assertEquals(30L, second.creationUsage?.inputTokens)

        assertIs<StoreResult.Ok<List<TestStep>>>(service.apply(second.id, second.steps))
        assertIs<StoreResult.Ok<List<TestStep>>>(service.apply(first.id, first.steps))
        assertEquals(30L, applied[0]?.inputTokens, "applying the latest preview includes both session attempts")
        assertNull(applied[1], "an older preview cannot attach an already applied attempt again")
    }

    @Test
    fun concurrentDraftAppliesReserveUsagePerAuthoringSession() = runBlocking {
        val sessionId = "dialog-session"
        val applied = java.util.Collections.synchronizedList(mutableListOf<AiUsageStats?>())
        val applyCount = AtomicInteger()
        val insertEntered = CountDownLatch(1)
        val releaseInsert = CountDownLatch(1)
        val attempts = AtomicInteger()
        val service = TestStepDraftService(
            library = ::library,
            preflight = { _, _ -> StoreResult.Ok(Unit) },
            generate = { _, _, _, _ -> error("usage-aware generator is used") },
            insert = { _, steps, _ -> StoreResult.Ok(steps) },
            assetExists = { _, _ -> true },
            generateWithUsage = { _, _, _, _, report ->
                val number = attempts.incrementAndGet()
                report(AiUsageStats(toolCalls = 1, inputTokens = number.toLong(), outputTokens = 1, totalTokens = number + 1L))
                """{"steps":[{"action":"Attempt $number","expected":"It appears"}]}"""
            },
            insertWithUsage = { _, steps, _, usage ->
                applied += usage
                if (applyCount.incrementAndGet() == 1) {
                    insertEntered.countDown()
                    check(releaseInsert.await(2, TimeUnit.SECONDS))
                }
                StoreResult.Ok(steps)
            },
        )
        val first = assertIs<StoreResult.Ok<com.indagium.testing.authoring.TestStepDraft>>(
            service.create("suite-1", "case-1", "profile", "Add first", authoringSessionId = sessionId),
        ).value
        val second = assertIs<StoreResult.Ok<com.indagium.testing.authoring.TestStepDraft>>(
            service.create("suite-1", "case-1", "profile", "Add second", authoringSessionId = sessionId),
        ).value

        val firstApply = async(Dispatchers.IO) { service.apply(first.id, first.steps) }
        assertTrue(insertEntered.await(2, TimeUnit.SECONDS))
        val rejected = service.apply(second.id, second.steps)
        assertTrue(rejected is StoreResult.Invalid && "being applied" in rejected.reason)
        releaseInsert.countDown()
        assertIs<StoreResult.Ok<List<TestStep>>>(firstApply.await())
        assertIs<StoreResult.Ok<List<TestStep>>>(service.apply(second.id, second.steps))

        assertEquals(1L, applied[0]?.inputTokens)
        assertEquals(2L, applied[1]?.inputTokens, "the second preview applies only its new attempt after reservation release")
    }

    @Test
    fun absentProviderUsageIsRetainedAsPartialInsteadOfBeingReportedAsZero() = runBlocking {
        val result = service().create("suite-1", "case-1", "profile", "Add a step")
        val draft = assertIs<StoreResult.Ok<com.indagium.testing.authoring.TestStepDraft>>(result).value
        assertEquals(null, draft.creationUsage?.totalTokens)
        assertTrue(draft.creationUsage?.partial == true)
    }

    @Test
    fun generationTimeoutIsActionableAndUserCancellationStillCancels() = runBlocking {
        val timeout = service(generate = { _, _, _, _ -> withTimeout(10) { CompletableDeferred<String>().await() } })
        val timedOut = assertIs<StoreResult.Invalid>(timeout.create("suite-1", "case-1", "profile", "Slow generation"))
        assertTrue(timedOut.reason.contains("timed out", ignoreCase = true))

        val entered = CompletableDeferred<Unit>()
        val cancelled = service(generate = { _, _, _, _ -> entered.complete(Unit); awaitCancellation() })
        val request = async { cancelled.create("suite-1", "case-1", "profile", "Cancel generation") }
        entered.await()
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
        assertFalse(cancelled.apply("unknown-preview", emptyList()) is StoreResult.Ok)
    }
}
