package com.indagium.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * Experimental infrastructure; not connected to the application viewport.
 * Ownership of a scanned query transfers only when construction succeeds. If construction fails,
 * including for an invalid [maxPageRows], the caller retains ownership and must close the query.
 */
internal class ViewportController private constructor(
    private val provider: ViewportPageProvider,
    private val maxPageRows: Int,
) : AutoCloseable {
    constructor(query: StorageQuery, maxPageRows: Int) : this(QueryPageProvider(query), maxPageRows)

    // The provider seam also permits testing a read that ignores cancellation.
    internal constructor(provider: ViewportPageProvider, maxPageRows: Int, @Suppress("UNUSED_PARAMETER") testing: Unit) :
        this(provider, maxPageRows)

    private val lock = Any()
    private val parentJob = SupervisorJob()
    private val scope = CoroutineScope(parentJob + Dispatchers.IO)
    private val termination = CompletableDeferred<Unit>()
    private val queryToken = nextQueryToken.incrementAndGet()
    private var requestToken = 0L
    private var closed = false
    private var job: Job? = null
    private val mutableState = MutableStateFlow<ViewportState>(ViewportState.Idle)
    val state: StateFlow<ViewportState> = mutableState.asStateFlow()

    init {
        if (maxPageRows <= 0) throw StorageFailure.InvalidRequest("Page limit must be positive")
    }

    /** Schedules a bounded page read; no source access occurs on the caller's thread. */
    @Suppress("ThrowsCount", "TooGenericExceptionCaught") // Surface provider failures as viewport state.
    fun request(start: Long, limit: Int): Job {
        if (start < 0 || limit !in 1..maxPageRows) throw StorageFailure.InvalidRequest("Invalid viewport page")
        val task: Job
        synchronized(lock) {
            if (closed) throw StorageFailure.Closed()
            job?.cancel()
            val token = ViewportToken(provider.version, queryToken, ++requestToken)
            mutableState.value = ViewportState.Loading(token, start, limit)
            task = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val page = provider.rows(start, limit)
                    check(page.version == token.version && page.start == start && page.items.size <= limit) {
                        "Inconsistent viewport page"
                    }
                    val immutable = page.copy(items = Collections.unmodifiableList(ArrayList(page.items)))
                    publish(token, ViewportState.Ready(token, immutable))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    publish(token, ViewportState.Failed(token, error))
                }
            }
            job = task
        }
        task.start()
        return task
    }

    private fun publish(token: ViewportToken, result: ViewportState) {
        synchronized(lock) {
            if (!closed && token.query == queryToken && token.request == requestToken && token.version == provider.version) {
                mutableState.value = result
            }
        }
    }

    /** Invalidates publication immediately; query closure runs off the caller's thread. */
    @Suppress("TooGenericExceptionCaught") // Termination must report even an unexpected close failure.
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            job?.cancel()
            scope.cancel()
            mutableState.value = ViewportState.Closed
        }
        CoroutineScope(Dispatchers.IO).launch {
            var failure: Throwable? = null
            try {
                provider.close()
            } catch (error: Throwable) {
                failure = error
            }
            parentJob.join()
            if (failure == null) termination.complete(Unit) else termination.completeExceptionally(failure)
        }
    }

    /** Waits for query closure and all reads to finish; rethrows any query closure failure. */
    suspend fun awaitClosed() = termination.await()

    private companion object {
        val nextQueryToken = AtomicLong()
    }
}

internal interface ViewportPageProvider : AutoCloseable {
    val version: SourceVersion

    suspend fun rows(start: Long, limit: Int): StoragePage<LegacyDecodedRow>
}

private class QueryPageProvider(private val query: StorageQuery) : ViewportPageProvider {
    override val version: SourceVersion get() = query.version

    override suspend fun rows(start: Long, limit: Int): StoragePage<LegacyDecodedRow> = query.rows(start, limit)

    override fun close() = query.close()
}

internal data class ViewportToken(val version: SourceVersion, val query: Long, val request: Long)

internal sealed interface ViewportState {
    data object Idle : ViewportState

    data class Loading(val token: ViewportToken, val start: Long, val limit: Int) : ViewportState

    data class Ready(val token: ViewportToken, val page: StoragePage<LegacyDecodedRow>) : ViewportState

    data class Failed(val token: ViewportToken, val error: Exception) : ViewportState

    data object Closed : ViewportState
}
