package com.indagium.storage

import com.indagium.storage.projection.DecodedDisplayPage
import com.indagium.storage.projection.ProjectionGeneration
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

/** Owns a stack-file session and publishes only the most recently requested bounded page. */
internal class ProjectionViewportController private constructor(
    private val provider: ProjectionViewportProvider,
    val maxPageRows: Int,
) : AutoCloseable {
    constructor(session: StackFileSession, maxPageRows: Int) : this(SessionViewportProvider(session), maxPageRows)

    internal constructor(provider: ProjectionViewportProvider, maxPageRows: Int, @Suppress("UNUSED_PARAMETER") testing: Unit) :
        this(provider, maxPageRows)

    private val lock = Any()
    private val parent = SupervisorJob()
    private val scope = CoroutineScope(parent + Dispatchers.IO)
    private val termination = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow<ProjectionViewportState>(ProjectionViewportState.Idle)
    private var closed = false
    private var requestNumber = 0L
    private var job: Job? = null

    val state: StateFlow<ProjectionViewportState> = mutableState.asStateFlow()
    val generation: ProjectionGeneration get() = provider.generation
    val size: Long get() = provider.size

    init {
        if (maxPageRows <= 0) throw StorageFailure.InvalidRequest("Page limit must be positive")
    }

    /** Requests never perform a file read on the caller's thread. */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount") // Read errors are part of the viewport state.
    fun request(start: Long, limit: Int): Job {
        if (start < 0 || limit !in 1..maxPageRows) throw StorageFailure.InvalidRequest("Invalid viewport page")
        val task: Job
        synchronized(lock) {
            if (closed) throw StorageFailure.Closed()
            job?.cancel()
            val token = ++requestNumber
            mutableState.value = ProjectionViewportState.Loading(token, start, limit)
            task = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val page = provider.page(start, limit)
                    val expected = minOf(limit.toLong(), (size - start).coerceAtLeast(0)).toInt()
                    check(page.generation == generation && page.start == start && page.items.size == expected &&
                        page.endReached == (start >= size || expected.toLong() == size - start)) {
                        "Inconsistent projection viewport page"
                    }
                    val safe = page.copy(items = Collections.unmodifiableList(ArrayList(page.items)))
                    publish(token, ProjectionViewportState.Ready(token, safe))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    publish(token, ProjectionViewportState.Failed(token, error))
                }
            }
            job = task
        }
        task.start()
        return task
    }

    private fun publish(token: Long, value: ProjectionViewportState) = synchronized(lock) {
        if (!closed && requestNumber == token) mutableState.value = value
    }

    @Suppress("TooGenericExceptionCaught") // Shutdown failures are reported by awaitClosed.
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            scope.cancel()
            mutableState.value = ProjectionViewportState.Closed
        }
        CoroutineScope(Dispatchers.IO).launch {
            var failure: Throwable? = null
            try {
                provider.close()
            } catch (error: Throwable) {
                failure = error
            }
            try {
                provider.awaitClosed()
                parent.join()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            if (failure == null) termination.complete(Unit) else termination.completeExceptionally(failure)
        }
    }

    suspend fun awaitClosed() = termination.await()
}

internal interface ProjectionViewportProvider : AutoCloseable {
    val generation: ProjectionGeneration
    val size: Long

    suspend fun page(start: Long, limit: Int): DecodedDisplayPage

    suspend fun awaitClosed()
}

private class SessionViewportProvider(private val session: StackFileSession) : ProjectionViewportProvider {
    override val generation get() = session.generation
    override val size get() = session.displayCount

    override suspend fun page(start: Long, limit: Int) = session.page(start, limit)

    override fun close() = session.close()

    override suspend fun awaitClosed() = session.awaitClosed()
}

internal sealed interface ProjectionViewportState {
    data object Idle : ProjectionViewportState

    data class Loading(val token: Long, val start: Long, val limit: Int) : ProjectionViewportState

    data class Ready(val token: Long, val page: DecodedDisplayPage) : ProjectionViewportState

    data class Failed(val token: Long, val error: Exception) : ProjectionViewportState

    data object Closed : ProjectionViewportState
}
