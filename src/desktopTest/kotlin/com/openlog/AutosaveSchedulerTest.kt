package com.openlog

import androidx.compose.ui.graphics.Color
import com.openlog.model.ManualCollapseBlock
import com.openlog.model.ManualCollapseDirection
import com.openlog.ui.AppState
import com.openlog.ui.AutosaveScheduler
import com.openlog.ui.mkTab
import com.openlog.ui.persistedSnapshot
import com.openlog.ui.tabToken
import com.openlog.utils.ZipLogCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutosaveSchedulerTest {
    @Test
    fun newerSynchronousSaveWinsOverOlderInFlightBackgroundSave() {
        val dir = createTempDirectory("openlog-autosave-order").toFile()
        val cacheFile = File(dir, "state.cache")
        val scope = testScope()
        val currentState = AtomicReference("old")
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val synchronousSaveReady = CountDownLatch(1)
        val writes = AtomicInteger()
        val scheduler = AutosaveScheduler(
            autosaveFile = cacheFile,
            scope = scope,
            serialize = { currentState.get() },
            backgroundDelayMs = 0,
            write = { file, content ->
                if (writes.incrementAndGet() == 1) {
                    firstWriteStarted.countDown()
                    assertTrue(releaseFirstWrite.await(2, TimeUnit.SECONDS))
                }
                file.writeText(content)
            },
            onSynchronousSaveReady = { synchronousSaveReady.countDown() },
        )

        try {
            scheduler.saveInBackground()
            assertTrue(firstWriteStarted.await(2, TimeUnit.SECONDS))
            currentState.set("new")

            val synchronousSaveFinished = CountDownLatch(1)
            val synchronousSave = thread(start = true, name = "autosave-now-test") {
                scheduler.saveNow()
                synchronousSaveFinished.countDown()
            }
            assertTrue(synchronousSaveReady.await(2, TimeUnit.SECONDS))
            assertEquals(1L, synchronousSaveFinished.count, "saveNow must wait behind an older active write")

            releaseFirstWrite.countDown()
            synchronousSave.join(2_000)

            assertFalse(synchronousSave.isAlive)
            assertEquals(2, writes.get())
            assertEquals("new", cacheFile.readText())
        } finally {
            releaseFirstWrite.countDown()
            scope.cancel()
        }
    }

    @Test
    fun invalidatedBackgroundSaveWaitingForWriterSkipsAfterSaveNow() {
        val dir = createTempDirectory("openlog-autosave-invalidated").toFile()
        val cacheFile = File(dir, "state.cache")
        val scope = testScope()
        val currentState = AtomicReference("first")
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val secondBackgroundWriteReady = CountDownLatch(1)
        val releaseSecondBackgroundWrite = CountDownLatch(1)
        val secondBackgroundWriterContended = CountDownLatch(1)
        val synchronousSaveReady = CountDownLatch(1)
        val backgroundJobsFinished = CountDownLatch(2)
        val backgroundWriteReadyCalls = AtomicInteger()
        val writes = AtomicInteger()
        val scheduler = AutosaveScheduler(
            autosaveFile = cacheFile,
            scope = scope,
            serialize = { currentState.get() },
            backgroundDelayMs = 0,
            write = { file, content ->
                if (writes.incrementAndGet() == 1) {
                    firstWriteStarted.countDown()
                    assertTrue(releaseFirstWrite.await(2, TimeUnit.SECONDS))
                }
                file.writeText(content)
            },
            onBackgroundWriteReady = {
                if (backgroundWriteReadyCalls.incrementAndGet() == 2) {
                    secondBackgroundWriteReady.countDown()
                    assertTrue(releaseSecondBackgroundWrite.await(2, TimeUnit.SECONDS))
                }
            },
            onBackgroundWriterContended = { secondBackgroundWriterContended.countDown() },
            onBackgroundJobFinished = { backgroundJobsFinished.countDown() },
            onSynchronousSaveReady = { synchronousSaveReady.countDown() },
        )

        try {
            scheduler.saveInBackground()
            assertTrue(firstWriteStarted.await(2, TimeUnit.SECONDS))
            currentState.set("invalidated")
            scheduler.saveInBackground()
            assertTrue(secondBackgroundWriteReady.await(2, TimeUnit.SECONDS))
            currentState.set("final")

            val synchronousSave = thread(start = true) { scheduler.saveNow() }
            assertTrue(synchronousSaveReady.await(2, TimeUnit.SECONDS))
            releaseSecondBackgroundWrite.countDown()
            assertTrue(secondBackgroundWriterContended.await(2, TimeUnit.SECONDS))
            releaseFirstWrite.countDown()
            synchronousSave.join(2_000)
            assertTrue(backgroundJobsFinished.await(2, TimeUnit.SECONDS))

            assertFalse(synchronousSave.isAlive)
            assertEquals(2, writes.get(), "the invalidated background writer must skip after acquiring the lock")
            assertEquals("final", cacheFile.readText())
        } finally {
            releaseSecondBackgroundWrite.countDown()
            releaseFirstWrite.countDown()
            scope.cancel()
        }
    }

    @Test
    fun saveNowCancelsPendingDebouncedSave() {
        val dir = createTempDirectory("openlog-autosave-cancel").toFile()
        val cacheFile = File(dir, "state.cache")
        val scope = testScope()
        val serializations = AtomicInteger()
        val backgroundStarted = CountDownLatch(1)
        val backgroundFinished = CountDownLatch(1)
        val scheduler = AutosaveScheduler(
            autosaveFile = cacheFile,
            scope = scope,
            serialize = { "value-${serializations.incrementAndGet()}" },
            backgroundDelayMs = 200,
            write = { file, content -> file.writeText(content) },
            onBackgroundJobStarted = { backgroundStarted.countDown() },
            onBackgroundJobFinished = { backgroundFinished.countDown() },
        )

        try {
            scheduler.saveInBackground()
            assertTrue(backgroundStarted.await(2, TimeUnit.SECONDS))
            scheduler.saveNow()
            assertTrue(backgroundFinished.await(2, TimeUnit.SECONDS))

            assertEquals(1, serializations.get())
            assertEquals("value-1", cacheFile.readText())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun rapidBackgroundCallsCoalesceAndSchedulingDoesNotBlockCaller() {
        val dir = createTempDirectory("openlog-autosave-coalesce").toFile()
        val cacheFile = File(dir, "state.cache")
        val scope = testScope()
        val serializationStarted = CountDownLatch(1)
        val releaseSerialization = CountDownLatch(1)
        val writeFinished = CountDownLatch(1)
        val serializations = AtomicInteger()
        val scheduler = AutosaveScheduler(
            autosaveFile = cacheFile,
            scope = scope,
            serialize = {
                serializations.incrementAndGet()
                serializationStarted.countDown()
                assertTrue(releaseSerialization.await(2, TimeUnit.SECONDS))
                "coalesced"
            },
            backgroundDelayMs = 50,
            write = { file, content ->
                file.writeText(content)
                writeFinished.countDown()
            },
        )

        try {
            repeat(25) { scheduler.saveInBackground() }
            assertTrue(serializationStarted.await(2, TimeUnit.SECONDS))
            assertEquals(1, serializations.get())

            // The caller reached this assertion while serialization is deliberately blocked on a
            // background thread, proving saveInBackground itself did not wait for the writer.
            releaseSerialization.countDown()
            assertTrue(writeFinished.await(2, TimeUnit.SECONDS))
            assertEquals("coalesced", cacheFile.readText())
        } finally {
            releaseSerialization.countDown()
            scope.cancel()
        }
    }

    @Test
    fun finalOrderedWriteControlsAutosaveError() {
        val dir = createTempDirectory("openlog-autosave-errors").toFile()
        val cacheFile = File(dir, "state.cache")
        val scope = testScope()
        val firstFailureStarted = CountDownLatch(1)
        val releaseFirstFailure = CountDownLatch(1)
        val writes = AtomicInteger()
        val scheduler = AutosaveScheduler(
            autosaveFile = cacheFile,
            scope = scope,
            serialize = { "state" },
            backgroundDelayMs = 0,
            write = { file, content ->
                when (writes.incrementAndGet()) {
                    1 -> {
                        firstFailureStarted.countDown()
                        assertTrue(releaseFirstFailure.await(2, TimeUnit.SECONDS))
                        error("older failure")
                    }
                    3 -> error("final failure")
                    else -> file.writeText(content)
                }
            },
        )

        try {
            scheduler.saveInBackground()
            assertTrue(firstFailureStarted.await(2, TimeUnit.SECONDS))
            val successfulSave = thread(start = true) { scheduler.saveNow() }
            releaseFirstFailure.countDown()
            successfulSave.join(2_000)

            assertFalse(successfulSave.isAlive)
            assertEquals(null, scheduler.autosaveError, "newer success must clear an older failure")

            scheduler.saveNow()

            assertNotNull(scheduler.autosaveError)
            assertTrue(scheduler.autosaveError!!.contains("final failure"))
        } finally {
            releaseFirstFailure.countDown()
            scope.cancel()
        }
    }

    @Test
    fun autosaveNowThenClosePreservesFinalMutationAndCloseIsIdempotent() {
        val dir = createTempDirectory("openlog-autosave-close").toFile()
        val cacheFile = File(dir, "state.cache")
        val state = AppState(autosaveFile = cacheFile)
        state.recentFiles = listOf("/final")

        state.autosaveNow()
        state.close()
        state.close()

        val restored = AppState(autosaveFile = cacheFile, restoreOnCreate = true)
        try {
            assertEquals(listOf("/final"), restored.recentFiles)
        } finally {
            restored.close()
        }
    }

    @Test
    fun persistedSnapshotStaysAlignedWithTabTokenFields() {
        val base = mkTab("tab", "base.log", emptyList())
        val variants = listOf(
            base.copy(id = "other"),
            base.copy(filename = "other.log"),
            base.copy(sourcePath = "/tmp/base.log"),
            base.copy(filter = base.filter.copy(kwText = "needle")),
            base.copy(annotations = base.annotations.copy(prefix = "Before")),
            base.copy(showAnnMd = true),
            base.copy(showUnfiltered = true),
            base.copy(expanded = setOf("sequence")),
            base.copy(
                manualBlocks = listOf(
                    ManualCollapseBlock("block", 1, ManualCollapseDirection.TO_END, Color.Red),
                ),
            ),
            base.copy(archiveCandidate = ZipLogCandidate("logs/main.txt", "main.txt", 42)),
        )

        assertEquals(10, base.persistedSnapshot().size)
        variants.forEach { variant ->
            assertNotEquals(base.persistedSnapshot(), variant.persistedSnapshot())
            assertNotEquals(base.tabToken(), variant.tabToken())
        }

        val sessionOnlyChange = base.copy(selected = setOf(1))
        assertEquals(base.persistedSnapshot(), sessionOnlyChange.persistedSnapshot())
        assertEquals(base.tabToken(), sessionOnlyChange.tabToken())
    }

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
