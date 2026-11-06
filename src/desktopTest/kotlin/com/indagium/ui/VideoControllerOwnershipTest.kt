package com.indagium.ui

import com.indagium.model.LogTab
import com.indagium.model.VideoAttachment
import com.indagium.model.VideoSource
import com.indagium.video.FailedVideoPlayerController
import com.indagium.video.VideoPlayerController
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VideoControllerOwnershipTest {
    private class Controller : VideoPlayerController by FailedVideoPlayerController("fixture") {
        val closeCount = AtomicInteger()

        override fun close() { closeCount.incrementAndGet() }
    }

    @Test
    fun concurrentConstructionClosesUnpublishedController() {
        val dir = createTempDirectory("video-owner-").toFile()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val created = java.util.Collections.synchronizedList(mutableListOf<Controller>())
        val state = state(dir) {
            Controller().also {
                created.add(it)
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val a = executor.submit<VideoPlayerController?> { state.videoController("tab") }
            val b = executor.submit<VideoPlayerController?> { state.videoController("tab") }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            release.countDown()
            val winner = a.get(10, TimeUnit.SECONDS)
            assertSame(winner, b.get(10, TimeUnit.SECONDS))
            assertEquals(1, created.sumOf { it.closeCount.get() })
            state.close()
            assertEquals(2, created.sumOf { it.closeCount.get() })
        } finally {
            release.countDown()
            executor.shutdownNow()
            state.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun existingControllerIsReturnedWithoutTakingStateLock() {
        val dir = createTempDirectory("video-owner-lockfree-").toFile()
        val controller = Controller()
        val state = state(dir) { controller }
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertSame(controller, state.videoController("tab"), "first call constructs and publishes")
            // Composable bodies call videoController on every recomposition: with the lock held
            // elsewhere, a published controller must still come back without waiting for it.
            val lookup = synchronized(state.stateLock) {
                val future = executor.submit<VideoPlayerController?> { state.videoController("tab") }
                assertSame(controller, future.get(5, TimeUnit.SECONDS))
                future
            }
            assertTrue(lookup.isDone)
            assertEquals(0, controller.closeCount.get())
        } finally {
            executor.shutdownNow()
            state.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun constructionFinishingAfterTabCloseIsDiscarded() = lateConstruction { it.closeTab("tab") }

    @Test
    fun constructionFinishingAfterVideoReplacementIsDiscarded() = lateConstruction {
        it.attachVideoToTab(File("replacement.mp4"), "tab")
    }

    @Test
    fun constructionFinishingAfterVideoDetachIsDiscarded() = lateConstruction { it.removeVideo("tab") }

    @Test
    fun constructionFinishingAfterShutdownIsDiscarded() = lateConstruction { it.close() }

    private fun lateConstruction(change: (AppState) -> Unit) {
        val dir = createTempDirectory("video-owner-late-").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val controller = Controller()
        val state = state(dir) {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            controller
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<VideoPlayerController?> { state.videoController("tab") }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            change(state)
            release.countDown()
            assertNull(result.get(10, TimeUnit.SECONDS))
            assertEquals(1, controller.closeCount.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
            state.close()
            dir.deleteRecursively()
        }
    }

    private fun state(dir: File, factory: (String) -> VideoPlayerController): AppState = AppState(
        autosaveFile = File(dir, "state.cache"),
        notesDir = File(dir, "notes"),
        archiveCacheDir = File(dir, "archive-cache"),
        customCommandsDir = File(dir, "commands"),
        sourceIndexFile = File(dir, "source-index"),
        testingDir = File(dir, "testing"),
        autoExportNotes = false,
        videoControllerFactory = factory,
    ).also {
        it.tabs = listOf(LogTab("tab", "fixture.log", emptyList(), emptyMap()).copy(
            attachedVideo = VideoAttachment(VideoSource.LocalFile(File(dir, "video.mp4").absolutePath), "video.mp4"),
        ))
    }
}
