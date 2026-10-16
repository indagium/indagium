package com.indagium.capture

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportedCaptureSnapshotTest {
    @Test
    fun concurrentStaleCursorWritesMergeMonotonicallyForOneSource() {
        val root = createTempDirectory("imported-capture-cursors").toFile()
        val session = CaptureSession(
            id = "imported-source-identity",
            directory = root,
            device = CaptureDevice("serial", "device", "Pixel"),
            settings = CaptureSettings(recordVideo = false),
            startedEpochMs = 1_700_000_000_000,
            elapsedMs = 20_000,
            status = CaptureStatus.STOPPED,
        )
        val checkpoint = File(root, "cursor.properties")
        val stale = ImportedCaptureSnapshot("source-identity", session, checkpoint)
        val start = CountDownLatch(1)
        val done = CountDownLatch(20)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(20) { index ->
            Thread {
                try {
                    assertTrue(start.await(2, TimeUnit.SECONDS))
                    persistImportedCaptureSnapshotCursors(stale, index.toLong() * 1_000, index.toLong() * 900)
                } catch (failure: Throwable) {
                    failures += failure
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true; start() }
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS), "checkpoint writes did not finish")
        assertTrue(failures.isEmpty(), failures.joinToString { it.message.orEmpty() })

        val refreshed = refreshImportedCaptureSnapshotCursors(stale).session
        assertEquals(19_000L, refreshed.effectiveSnapshotCheckpointMs)
        assertEquals(17_100L, refreshed.videoCheckpointMs)
        assertEquals(20, refreshed.exportCounter)
    }
}
