package com.indagium.storage

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StorageCommitGateTest {
    @Test
    fun closeDoesNotBlockACommitThatAlreadyWon() {
        val gate = StorageCommitGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val commit = executor.submit {
                gate.commit {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            gate.close()
            assertFailsWith<StorageFailure.Closed> { gate.commit {} }
            release.countDown()
            commit.get(5, TimeUnit.SECONDS)
            assertFailsWith<StorageFailure.Closed> { gate.commit {} }
        } finally {
            release.countDown()
            gate.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun closeBeforeCommitRejectsTheAction() {
        val gate = StorageCommitGate()
        gate.close()
        assertFailsWith<StorageFailure.Closed> { gate.commit { error("must not run") } }
    }
}
