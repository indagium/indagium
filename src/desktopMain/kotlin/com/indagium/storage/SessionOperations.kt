package com.indagium.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Owns operation child jobs, including waiters, without cancelling the caller's parent job. */
internal class SessionOperations(maxConcurrent: Int) : AutoCloseable {
    private val lock = Any()
    private val permits = Semaphore(maxConcurrent)
    private val active = mutableSetOf<Job>()
    private val drained = CompletableDeferred<Unit>()
    private var closed = false

    suspend fun <T> run(block: suspend () -> T): T = coroutineScope {
        val job = checkNotNull(currentCoroutineContext()[Job])
        synchronized(lock) {
            if (closed) throw StorageFailure.Closed()
            active.add(job)
        }
        try {
            permits.withPermit {
                val result = block()
                currentCoroutineContext().ensureActive()
                synchronized(lock) { if (closed) throw StorageFailure.Closed() }
                result
            }
        } finally {
            synchronized(lock) {
                active.remove(job)
                if (closed && active.isEmpty()) drained.complete(Unit)
            }
        }
    }

    override fun close() {
        val jobs = synchronized(lock) {
            if (closed) return
            closed = true
            if (active.isEmpty()) drained.complete(Unit)
            active.toList()
        }
        jobs.forEach { it.cancel("Storage session closed") }
    }

    suspend fun awaitClosed() = drained.await()
}
