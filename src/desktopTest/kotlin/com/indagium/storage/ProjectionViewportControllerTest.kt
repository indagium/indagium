package com.indagium.storage

import com.indagium.model.Filter
import com.indagium.model.LogFormat
import com.indagium.storage.projection.DecodedDisplayPage
import com.indagium.storage.projection.ProjectionGeneration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectionViewportControllerTest {
    private val generation = ProjectionGeneration(SourceVersion("test", 1), "query", "projection", 0)

    @Test
    fun lateReadCannotOverwriteNewerPageOrClose(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val closeRelease = CountDownLatch(1)
        val provider = object : ProjectionViewportProvider {
            override val generation = this@ProjectionViewportControllerTest.generation
            override val size = 0L

            override suspend fun page(start: Long, limit: Int): DecodedDisplayPage {
                if (start == 0L) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                if (start == 2L) {
                    closeEntered.countDown()
                    check(closeRelease.await(5, TimeUnit.SECONDS))
                }
                return DecodedDisplayPage(generation, start, emptyList(), true)
            }

            override fun close() = Unit

            override suspend fun awaitClosed() = Unit
        }
        val controller = ProjectionViewportController(provider, 2, Unit)
        try {
            val first = controller.request(0, 1)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.request(1, 1)
            val latest = withTimeout(5_000) { controller.state.first { it is ProjectionViewportState.Ready } }
            assertEquals(1L, (latest as ProjectionViewportState.Ready).page.start)
            release.countDown()
            withTimeout(5_000) { first.join() }
            assertEquals(latest, controller.state.value)
            val closing = controller.request(2, 1)
            assertTrue(closeEntered.await(5, TimeUnit.SECONDS))
            controller.close()
            assertNull(withTimeoutOrNull(50) { controller.awaitClosed(); true })
            closeRelease.countDown()
            withTimeout(5_000) { closing.join(); controller.awaitClosed() }
            assertEquals(ProjectionViewportState.Closed, controller.state.value)
            assertFailsWith<StorageFailure.Closed> { controller.request(0, 1) }
        } finally {
            release.countDown()
            closeRelease.countDown()
            controller.close()
        }
    }

    @Test
    fun generatedFileSessionPublishesBoundedPages(): Unit = runBlocking {
        val path = Files.createTempFile("projection-viewport-", ".log")
        try {
            Files.writeString(path, "01-01 12:00:00.000 1 1 I App: first\n01-01 12:00:00.001 1 1 I App: second\n")
            val session = StackFileSession.open(SourceId("generated"), path, Filter(), LogFormat.LOGCAT, false, pageRows = 1)
            val controller = ProjectionViewportController(session, 1)
            try {
                assertEquals(2L, controller.size)
                controller.request(1, 1)
                val ready = withTimeout(5_000) { controller.state.first { it is ProjectionViewportState.Ready } }
                    as ProjectionViewportState.Ready
                assertEquals(1L, ready.page.start)
                assertEquals(1, ready.page.items.size)
                assertTrue(ready.page.endReached)
            } finally {
                controller.close()
                withTimeout(5_000) { controller.awaitClosed() }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun rejectsMalformedPageAndRecoversOnNextRequest(): Unit = runBlocking {
        val provider = object : ProjectionViewportProvider {
            override val generation = this@ProjectionViewportControllerTest.generation
            override val size = 0L

            override suspend fun page(start: Long, limit: Int) =
                DecodedDisplayPage(generation, start + if (start == 0L) 1 else 0, emptyList(), true)

            override fun close() = Unit

            override suspend fun awaitClosed() = Unit
        }
        val controller = ProjectionViewportController(provider, 1, Unit)
        try {
            controller.request(0, 1)
            withTimeout(5_000) { controller.state.first { it is ProjectionViewportState.Failed } }
            controller.request(1, 1)
            val ready = withTimeout(5_000) { controller.state.first { it is ProjectionViewportState.Ready } }
            assertEquals(1L, (ready as ProjectionViewportState.Ready).page.start)
        } finally {
            controller.close()
            withTimeout(5_000) { controller.awaitClosed() }
        }
    }
}
