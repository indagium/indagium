package com.indagium.storage

import java.util.concurrent.atomic.AtomicInteger

/**
 * A nonblocking lifetime fence. If commit wins its compare-and-set before close, its action may
 * finish after close; otherwise it is rejected. Only one commit can be in progress at a time.
 */
internal class StorageCommitGate : StorageExportCommitFence, AutoCloseable {
    private val state = AtomicInteger(ACTIVE)

    override fun commit(action: () -> Unit) {
        if (!state.compareAndSet(ACTIVE, COMMITTING)) {
            if (state.get() == COMMITTING) throw StorageFailure.InvalidRequest("Another export is committing")
            throw StorageFailure.Closed()
        }
        try {
            action()
        } finally {
            if (!state.compareAndSet(COMMITTING, ACTIVE)) state.set(CLOSED)
        }
    }

    override fun close() {
        while (true) {
            when (val current = state.get()) {
                ACTIVE -> if (state.compareAndSet(current, CLOSED)) return
                COMMITTING -> if (state.compareAndSet(current, CLOSING)) return
                else -> return
            }
        }
    }

    private companion object {
        const val ACTIVE = 0
        const val COMMITTING = 1
        const val CLOSING = 2
        const val CLOSED = 3
    }
}
