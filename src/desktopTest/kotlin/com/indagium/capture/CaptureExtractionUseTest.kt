package com.indagium.capture

import com.indagium.CaptureArchiveFixtures
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val USE_DAY_MS = 24L * 60 * 60 * 1000
private const val DISTINCT_ARCHIVES = 12
private const val BASE_VIDEO_BYTES = 100

/** [CaptureExtractionUse]: the lease and in-use entry of an extraction live exactly as long as one of its uses is open. */
@Suppress("MagicNumber")
class CaptureExtractionUseTest {
    private lateinit var root: File
    private lateinit var cache: File

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("capture-extraction-use").toFile()
        cache = File(root, "cache").also { it.mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        CaptureExtractionCache.extractionObserver = null
        CaptureExtractionCache.leaseWaitMs = CaptureExtractionCache.DEFAULT_LEASE_WAIT_MS
        CaptureExtractionCache.releaseLeases()
        root.deleteRecursively()
    }

    @Test
    fun openingManyArchivesAndClosingThemLeavesNoLeaseChannelOrInUseEntry() {
        val opened = (1..DISTINCT_ARCHIVES).map { CaptureArchiveReader.open(CaptureArchiveFixtures.exportWithVideo(root, BASE_VIDEO_BYTES + it), cache) }
        val directories = opened.map { it.logFile.parentFile }
        assertEquals(DISTINCT_ARCHIVES, directories.toSet().size)
        directories.forEach {
            assertTrue(CaptureExtractionCache.holdsLease(it) && CaptureExtractionCache.isInUse(it))
            assertEquals(1, CaptureExtractionCache.openUseCount(it))
        }

        opened.forEach { it.close() }

        directories.forEach {
            assertFalse(CaptureExtractionCache.holdsLease(it), "no lease channel is left per closed archive")
            assertFalse(CaptureExtractionCache.isInUse(it))
            assertEquals(0, CaptureExtractionCache.openUseCount(it))
            assertTrue(it.isDirectory, "releasing keeps the extraction for reuse")
        }
        opened.forEach { it.close() } // idempotent
    }

    @Test
    fun aDirectoryStaysProtectedUntilItsLastConsumerCloses() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val first = CaptureArchiveReader.open(archive, cache)
        val second = CaptureArchiveReader.open(archive, cache)
        val directory = first.logFile.parentFile
        assertEquals(directory, second.logFile.parentFile)
        assertEquals(2, CaptureExtractionCache.openUseCount(directory))

        first.close()

        assertTrue(CaptureExtractionCache.holdsLease(directory) && CaptureExtractionCache.isInUse(directory))
        val farFuture = System.currentTimeMillis() + 60 * USE_DAY_MS
        val whileOpen = CaptureExtractionCache.sweep(cache, nowMs = farFuture, maxUnusedMs = 0, budgetBytes = 0, graceMs = 0)
        assertTrue(directory.isDirectory, "the remaining consumer keeps it out of the sweep")
        assertEquals(0, whileOpen.deletedTotal)

        second.close()

        assertFalse(CaptureExtractionCache.holdsLease(directory))
        val afterClose = CaptureExtractionCache.sweep(cache, nowMs = farFuture, maxUnusedMs = 0, budgetBytes = 0, graceMs = 0)
        assertFalse(directory.exists(), "once nobody uses it the sweep may take it")
        assertEquals(1, afterClose.deletedTotal)
    }

    @Test
    fun aRetainedUseKeepsTheDirectoryAfterTheOpenerCloses() {
        val imported = CaptureArchiveReader.open(CaptureArchiveFixtures.exportWithVideo(root), cache)
        val directory = imported.logFile.parentFile
        val retained = assertNotNull(requireNotNull(imported.extractionUse).retain())

        imported.close()
        assertTrue(CaptureExtractionCache.holdsLease(directory) && CaptureExtractionCache.isInUse(directory))
        retained.close()

        assertFalse(CaptureExtractionCache.holdsLease(directory))
        assertFalse(CaptureExtractionCache.isInUse(directory))
        assertNull(requireNotNull(imported.extractionUse).retain(), "a closed use cannot be retained")
    }

    @Test
    fun aReopenAfterTheReleaseTakesTheLeaseAgain() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val first = CaptureArchiveReader.open(archive, cache)
        val directory = first.logFile.parentFile
        first.close()
        val extractions = AtomicInteger()
        CaptureExtractionCache.extractionObserver = { extractions.incrementAndGet() }

        val again = CaptureArchiveReader.open(archive, cache)

        assertEquals(0, extractions.get(), "the released extraction is reused")
        assertEquals(directory, again.logFile.parentFile)
        assertTrue(CaptureExtractionCache.holdsLease(directory) && CaptureExtractionCache.isInUse(directory))
    }

    @Test
    fun aDirectoryReplacedWhileAUseIsOpenIgnoresTheStaleUsesLaterClose() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val stale = CaptureArchiveReader.open(archive, cache)
        val directory = stale.logFile.parentFile
        assertTrue(requireNotNull(stale.videoFile).delete())

        val replacement = CaptureArchiveReader.open(archive, cache)
        assertEquals(directory, replacement.logFile.parentFile)
        stale.close()

        assertTrue(CaptureExtractionCache.holdsLease(directory), "the replaced directory's old use must not end the new one")
        assertEquals(1, CaptureExtractionCache.openUseCount(directory))
        replacement.close()
        assertFalse(CaptureExtractionCache.holdsLease(directory))
    }

    @Test
    fun aDamagedDirectoryIsReplacedInPlaceOnceTheOtherProcessLeaveAndThisOneRetakesTheLease() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val first = CaptureArchiveReader.open(archive, cache)
        val directory = first.logFile.parentFile
        first.close()
        val child = startSharedLeaseHolder(directory)
        assertTrue(requireNotNull(first.videoFile).delete())
        val whileShared = CaptureArchiveReader.open(archive, cache)
        assertTrue(whileShared.logFile.parentFile != directory, "extracted beside the shared one")
        whileShared.close()
        child.outputStream.close()
        assertTrue(child.waitFor(60, TimeUnit.SECONDS))

        val afterExit = CaptureArchiveReader.open(archive, cache)

        assertEquals(directory, afterExit.logFile.parentFile, "with no other reader the damaged directory is replaced in place")
        assertTrue(requireNotNull(afterExit.videoFile).isFile)
        assertTrue(CaptureExtractionCache.holdsLease(directory), "the replacement is leased by this process")
    }

    @Test
    fun clearForReplacementKeepsThisProcessLeaseWhenAnotherReaderBlocksTheDeletion() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val imported = CaptureArchiveReader.open(archive, cache)
        val directory = imported.logFile.parentFile
        val child = startSharedLeaseHolder(directory)
        try {
            assertEquals(CaptureExtractionCache.Replacement.HELD_BY_OTHERS, CaptureExtractionCache.clearForReplacement(directory))

            assertTrue(directory.isDirectory)
            assertTrue(CaptureExtractionCache.holdsLease(directory), "this process still reads it, so its shared lease is back")
        } finally {
            child.outputStream.close()
            child.waitFor(60, TimeUnit.SECONDS)
        }
        imported.close()
    }

    private fun startSharedLeaseHolder(directory: File): Process {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
            CaptureExtractionLeaseHolder::class.java.name, directory.absolutePath, "shared",
        ).redirectErrorStream(true).start()
        val reader = process.inputStream.bufferedReader()
        var line: String?
        do {
            line = reader.readLine()
            if (line == "LEASED") return process
        } while (line != null)
        process.destroyForcibly()
        error("lease holder did not report a lease")
    }
}
