@file:Suppress("MagicNumber")

package com.indagium.testing

import com.indagium.testing.authoring.RecordingRewriteService
import com.indagium.testing.authoring.RewriteStage
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** A running rewrite reports where it is, so the review panel can say more than "Rewriting…". */
class RecordingRewriteStageTest {
    private val sessions = mutableListOf<TestStepRecordingSession>()

    @AfterTest
    fun closeSessions() = sessions.forEach(TestStepRecordingSession::close)

    private fun service(generate: suspend (com.indagium.testing.authoring.RewriteGeneration) -> String) =
        RecordingRewriteService(library = { shopLibrary() }, preflight = { _, _ -> StoreResult.Ok(Unit) }, generate = generate, timeoutMs = 5_000L)

    @Test
    fun theStagesArePreparingThenWaitingForTheModelThenApplyingTheStepsItProposed() {
        val seen = CopyOnWriteArrayList<RewriteStage>()
        val session = recordedShopSession().also { sessions += it }

        val result = runBlocking {
            service {
                // The model is being waited for when this runs: the first two stages are already out.
                assertEquals(listOf(RewriteStage.Preparing, RewriteStage.WaitingForModel), seen.toList())
                VALID_SHOP_REWRITE
            }.rewrite(session, SHOP_SUITE_ID, SHOP_CASE_ID, "profile", null, null, "", onStage = { seen += it })
        }

        assertIs<StoreResult.Ok<*>>(result)
        assertEquals(3, seen.size)
        val applying = assertIs<RewriteStage.Applying>(seen.last())
        assertEquals(session.snapshot.value.steps.size, applying.steps)
        assertEquals("Preparing evidence…", seen[0].label)
        assertEquals("Waiting for the model…", seen[1].label)
        assertEquals("Applying ${applying.steps} steps…", applying.label)
        assertEquals("Applying 1 step…", RewriteStage.Applying(1).label)
    }

    @Test
    fun aRewriteThatFailsInTheModelNeverReachesTheApplyingStage() {
        val seen = CopyOnWriteArrayList<RewriteStage>()
        val session = recordedShopSession().also { sessions += it }

        val result = runBlocking {
            service { error("the model is down") }.rewrite(session, SHOP_SUITE_ID, SHOP_CASE_ID, "profile", null, null, "", onStage = { seen += it })
        }

        assertIs<StoreResult.Invalid>(result)
        assertEquals(listOf(RewriteStage.Preparing, RewriteStage.WaitingForModel), seen.toList())
    }

    @Test
    fun cancellingDuringTheModelWaitPropagatesTheCancel() {
        val session = recordedShopSession().also { sessions += it }

        assertFailsWith<CancellationException> {
            runBlocking {
                service { throw CancellationException("user cancelled") }.rewrite(session, SHOP_SUITE_ID, SHOP_CASE_ID, "profile", null, null, "")
            }
        }
    }
}
