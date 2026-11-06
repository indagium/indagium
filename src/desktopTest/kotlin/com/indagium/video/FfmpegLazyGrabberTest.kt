package com.indagium.video

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The FFmpeg grabber (whose first use loads every FFmpeg native library) must be created on the
 * decode thread, never by constructing or closing a controller on the UI thread.
 */
class FfmpegLazyGrabberTest {
    private val missingPath = "/nonexistent/indagium-lazy-grabber-test.mp4"

    @Test
    fun constructingAControllerDoesNotCreateAGrabber() {
        val calls = AtomicInteger()
        val controller = videoPlayerControllerWithGrabberFactory(missingPath) {
            calls.incrementAndGet()
            throw UnsatisfiedLinkError("must not be reached")
        }
        try {
            assertEquals(0, calls.get())
            assertNull(controller.error)
        } finally {
            controller.close()
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun closeWithoutStartNeverCreatesAGrabber() {
        val calls = AtomicInteger()
        val controller = videoPlayerControllerWithGrabberFactory(missingPath) {
            calls.incrementAndGet()
            throw UnsatisfiedLinkError("must not be reached")
        }
        controller.close()
        controller.close()
        controller.start() // a closed controller must not start the decode thread either
        Thread.sleep(SETTLE_MS)
        assertEquals(0, calls.get())
    }

    @Test
    fun grabberIsCreatedOnTheDecodeThreadAfterStart() {
        val factoryThread = AtomicReference<Thread?>()
        val called = CountDownLatch(1)
        val controller = videoPlayerControllerWithGrabberFactory(missingPath) {
            factoryThread.set(Thread.currentThread())
            called.countDown()
            error("stop after recording the thread")
        }
        try {
            controller.start()
            assertTrue(called.await(WAIT_SECONDS, TimeUnit.SECONDS), "the factory should run after start()")
            val thread = assertNotNull(factoryThread.get())
            assertEquals("indagium-video-decode", thread.name)
            assertNotEquals(Thread.currentThread(), thread)
        } finally {
            controller.close()
        }
    }

    @Test
    fun nativeLoadFailureBecomesTheControllerErrorState() {
        val controller = videoPlayerControllerWithGrabberFactory(missingPath) {
            throw UnsatisfiedLinkError("simulated FFmpeg native load failure")
        }
        try {
            controller.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            while (controller.error == null && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
            val error = controller.error ?: fail("the load failure should become the controller's error state")
            assertTrue("simulated FFmpeg native load failure" in error, "unexpected error: $error")
            // The decode thread ends after the failed open; releasing never created a grabber to release.
            controller.close()
        } finally {
            controller.close()
        }
    }

    @Test
    fun oneTimeLoadRunsOnceAndAnInterruptedWaiterNeitherInterruptsNorSkipsIt() {
        val runs = AtomicInteger()
        val loadInterrupted = AtomicReference<Boolean?>()
        val loadThread = AtomicReference<Thread?>()
        val release = CountDownLatch(1)
        val load = UninterruptedOneTimeLoad("test-load") {
            runs.incrementAndGet()
            loadThread.set(Thread.currentThread())
            release.await(WAIT_SECONDS, TimeUnit.SECONDS)
            loadInterrupted.set(Thread.currentThread().isInterrupted)
        }
        val keptFlag = AtomicReference<Boolean?>()
        val waiter = Thread {
            load.await()
            keptFlag.set(Thread.currentThread().isInterrupted)
        }
        waiter.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (loadThread.get() == null && System.nanoTime() < deadline) Thread.sleep(POLL_MS)

        waiter.interrupt()
        Thread.sleep(SETTLE_MS)
        assertTrue(waiter.isAlive, "an interrupted waiter must keep waiting for the load")

        release.countDown()
        waiter.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        load.await()

        assertEquals(1, runs.get())
        assertNotEquals(waiter, loadThread.get())
        assertEquals(false, loadInterrupted.get())
        assertEquals(true, keptFlag.get(), "the waiter's interrupt flag must survive the wait")
    }

    @Test
    fun aFailedOneTimeLoadStillReleasesItsWaiters() {
        val load = UninterruptedOneTimeLoad("test-load-failure") { throw UnsatisfiedLinkError("simulated") }
        val returned = CountDownLatch(1)
        val waiter = Thread {
            load.await()
            returned.countDown()
        }
        waiter.start()
        assertTrue(returned.await(WAIT_SECONDS, TimeUnit.SECONDS), "a failed load must not strand its waiters")
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val POLL_MS = 10L
        const val SETTLE_MS = 200L
    }
}
