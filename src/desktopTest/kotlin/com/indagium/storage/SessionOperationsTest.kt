package com.indagium.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionOperationsTest {
    @Test
    fun closeCancelsActiveAndWaitingChildrenWithoutCancellingTheirParent(): Unit = runBlocking {
        val operations = SessionOperations(1)
        val parent = Job()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val waitingStarted = CompletableDeferred<Unit>()
        val waitingEntered = AtomicBoolean(false)
        val active = launch(parent + Dispatchers.Default) {
            operations.run {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
        }
        entered.await()
        val waiting = launch(parent + Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            waitingStarted.complete(Unit)
            operations.run { waitingEntered.set(true) }
        }
        waitingStarted.await()
        operations.close()
        assertNull(withTimeoutOrNull(50) { operations.awaitClosed(); true })
        release.complete(Unit)
        withTimeout(5_000) { active.join(); waiting.join(); operations.awaitClosed() }
        assertFalse(waitingEntered.get())
        assertTrue(parent.isActive)
        assertFailsWith<StorageFailure.Closed> { operations.run { } }
        parent.cancel()
    }
}
