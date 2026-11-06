package com.indagium.storage

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewportControllerTest {
    private val version = SourceVersion("viewport-test", 1)

    private class Provider(
        override val version: SourceVersion,
        val closeFailure: Exception? = null,
        val read: suspend (Long, Int) -> StoragePage<LegacyDecodedRow>,
    ) : ViewportPageProvider {
        val closes = AtomicInteger()
        val closed = CountDownLatch(1)

        override suspend fun rows(start: Long, limit: Int) = read(start, limit)

        override fun close() {
            closes.incrementAndGet()
            closed.countDown()
            closeFailure?.let { throw it }
        }
    }

    @Test
    fun newerRequestWinsWhenCancelledReadReturnsLate() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val provider = Provider(version) { start, _ ->
            if (start == 0L) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                returned.countDown()
            }
            StoragePage(version, start, emptyList(), true)
        }
        val controller = ViewportController(provider, 2, Unit)
        try {
            val first = controller.request(0, 1)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.request(1, 1)
            val latest = withTimeout(5000) { controller.state.first { it is ViewportState.Ready } } as ViewportState.Ready
            assertEquals(1L, latest.page.start)
            release.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            withTimeout(5000) { first.join() }
            assertEquals(latest, controller.state.value)
        } finally {
            release.countDown()
            controller.close()
            assertTrue(provider.closed.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun closeIsIdempotentAndRejectsLateResults(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val provider = Provider(version) { start, _ ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            returned.countDown()
            StoragePage(version, start, emptyList(), true)
        }
        val controller = ViewportController(provider, 2, Unit)
        try {
            val first = controller.request(0, 1)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.close()
            controller.close()
            assertTrue(provider.closed.await(5, TimeUnit.SECONDS))
            assertNull(withTimeoutOrNull(100) { controller.awaitClosed(); true })
            release.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            withTimeout(5000) { first.join() }
            withTimeout(5000) { controller.awaitClosed() }
            assertEquals(ViewportState.Closed, controller.state.value)
            assertEquals(1, provider.closes.get())
            assertFailsWith<StorageFailure.Closed> { controller.request(0, 1) }
        } finally {
            release.countDown()
            controller.close()
        }
    }

    @Test
    fun readErrorsArePublishedAndLaterRequestsCanRecover() = runBlocking {
        val provider = Provider(version) { start, _ ->
            if (start == 0L) error("read failed")
            StoragePage(version, start, emptyList(), true)
        }
        val controller = ViewportController(provider, 2, Unit)
        try {
            assertFailsWith<StorageFailure.InvalidRequest> { controller.request(0, 3) }
            controller.request(0, 1)
            val failed = withTimeout(5000) { controller.state.first { it is ViewportState.Failed } }
            assertEquals("read failed", assertIs<ViewportState.Failed>(failed).error.message)
            controller.request(2, 1)
            val ready = withTimeout(5000) { controller.state.first { it is ViewportState.Ready } }
            assertEquals(2L, assertIs<ViewportState.Ready>(ready).page.start)
        } finally {
            controller.close()
            assertTrue(provider.closed.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun awaitClosedReportsCompletionAndCloseFailure() = runBlocking {
        val failure = IllegalStateException("close failed")
        val provider = Provider(version, failure) { start, _ -> StoragePage(version, start, emptyList(), true) }
        val controller = ViewportController(provider, 1, Unit)
        controller.close()
        controller.close()
        val thrown = assertFailsWith<IllegalStateException> { withTimeout(5000) { controller.awaitClosed() } }
        assertEquals(failure.message, thrown.message)
        assertEquals(1, provider.closes.get())
    }

    @Test
    fun failedConstructionLeavesProviderOwnedByCaller() {
        val provider = Provider(version) { start, _ -> StoragePage(version, start, emptyList(), true) }
        assertFailsWith<StorageFailure.InvalidRequest> { ViewportController(provider, 0, Unit) }
        assertEquals(0, provider.closes.get())
        provider.close()
        assertEquals(1, provider.closes.get())
    }
}
