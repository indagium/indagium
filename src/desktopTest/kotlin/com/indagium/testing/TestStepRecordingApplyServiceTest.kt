package com.indagium.testing

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.testing.authoring.TestStepRecordingApplyService
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestStepRecordingApplyServiceTest {
    private fun stoppedSession(): TestStepRecordingSession = TestStepRecordingSession("fixture-device").also { session ->
        session.accept(MirrorControlCommand.Text("recorded"))
        session.stop()
        assertTrue(session.updateReviewedSteps(listOf("type recorded" to "text is visible")))
    }

    @Test
    fun concurrentApplyIsRejectedAndOnlyOneMutationRuns() = runBlocking {
        val enteredPreflight = CountDownLatch(1)
        val releasePreflight = CountDownLatch(1)
        val inserted = AtomicInteger()
        val service = TestStepRecordingApplyService(
            preflight = { _, _ ->
                enteredPreflight.countDown()
                check(releasePreflight.await(2, TimeUnit.SECONDS))
                StoreResult.Ok(Unit)
            },
            importImage = { _, _ -> error("No screenshot is expected") },
            resolveImage = { _, _ -> null },
            insertSteps = { _, steps, _ ->
                inserted.incrementAndGet()
                StoreResult.Ok(steps)
            },
        )
        val session = stoppedSession()
        val first = async(Dispatchers.Default) { service.apply(session, "suite-fixture", "case-fixture") }
        assertTrue(withContext(Dispatchers.IO) { enteredPreflight.await(2, TimeUnit.SECONDS) })
        val second = service.apply(session, "suite-fixture", "case-fixture")
        assertTrue(second is StoreResult.Invalid)
        releasePreflight.countDown()
        assertTrue(first.await() is StoreResult.Ok)
        assertEquals(1, inserted.get())
        session.close()
    }

    @Test
    fun refusedPreflightDoesNotCopyAssetsOrInsertAndSessionCanRetry() = runBlocking {
        val insertCalls = AtomicInteger()
        val imports = AtomicInteger()
        val service = TestStepRecordingApplyService(
            preflight = { _, _ -> StoreResult.Invalid("Case is locked.") },
            importImage = { _, _ -> imports.incrementAndGet(); StoreResult.Ok("screen.jpg") },
            resolveImage = { _, _ -> File("unused") },
            insertSteps = { _, steps, _ -> insertCalls.incrementAndGet(); StoreResult.Ok(steps) },
        )
        val session = stoppedSession()
        val result = service.apply(session, "suite-fixture", "case-fixture")
        assertEquals("Case is locked.", (result as StoreResult.Invalid).reason)
        assertEquals(0, imports.get())
        assertEquals(0, insertCalls.get())
        assertFalse(session.snapshot.value.active)
        session.close()
    }

    @Test
    fun reviewedRowsAreFrozenBeforeAssetWorkAndApplyUsesThatExactSnapshot() = runBlocking {
        val enteredPreflight = CountDownLatch(1)
        val releasePreflight = CountDownLatch(1)
        var insertedAction = ""
        val service = TestStepRecordingApplyService(
            preflight = { _, _ -> enteredPreflight.countDown(); check(releasePreflight.await(2, TimeUnit.SECONDS)); StoreResult.Ok(Unit) },
            importImage = { _, _ -> error("No screenshot is expected") },
            resolveImage = { _, _ -> null },
            insertSteps = { _, steps, _ -> insertedAction = steps.single().action; StoreResult.Ok(steps) },
        )
        val session = stoppedSession()
        val apply = async(Dispatchers.Default) { service.apply(session, "suite-fixture", "case-fixture") }
        assertTrue(withContext(Dispatchers.IO) { enteredPreflight.await(2, TimeUnit.SECONDS) })
        assertFalse(session.updateReviewedSteps(listOf("tampered after apply began" to "different result")))
        releasePreflight.countDown()
        assertTrue(apply.await() is StoreResult.Ok)
        assertEquals("type recorded", insertedAction)
        session.close()
    }
}
