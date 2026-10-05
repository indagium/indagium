package com.indagium.testing.store

import com.indagium.testing.model.TestRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Writes a running test run to disk without a write per result: [request] schedules one save [debounceMs] later (further
// requests inside that window ride along) and the save reads the freshest run through [current] when it happens.
// [flush] writes right now and cancels a pending save; it is what a finishing run calls so the file is final.

const val RUN_SAVE_DEBOUNCE_MS = 1_000L

class RunPersister(
    private val store: TestRunStore,
    private val scope: CoroutineScope,
    private val current: () -> TestRun,
    private val debounceMs: Long = RUN_SAVE_DEBOUNCE_MS,
) {
    private val lock = Any()
    private val saveLock = Any()
    private var pending: Job? = null

    fun request() {
        synchronized(lock) {
            if (pending?.isActive == true) return
            pending = scope.launch(Dispatchers.IO) {
                delay(debounceMs)
                synchronized(lock) { pending = null }
                saveNow()
            }
        }
    }

    /** Saves the current run now, even when the calling coroutine is being cancelled. */
    suspend fun flush() {
        val job = synchronized(lock) { pending.also { pending = null } }
        job?.cancel()
        withContext(Dispatchers.IO + NonCancellable) { saveNow() }
    }

    /** Reading the run INSIDE the lock means a later save can never write an older run over a newer one. */
    private fun saveNow() {
        synchronized(saveLock) { store.save(current()) }
    }
}
