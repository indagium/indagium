package com.indagium.capture

import com.indagium.CaptureArchiveFixtures
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val LEASE_DAY_MS = 24L * 60 * 60 * 1000

/**
 * Child JVM for [CaptureExtractionLeaseTest]: takes a lease on one directory (shared like an open tab, or with
 * `exclusive` like a sweep that is deleting it), announces it, and waits for stdin to close.
 */
internal object CaptureExtractionLeaseHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        val directory = File(args[0])
        var exclusiveChannel: FileChannel? = null
        if (args.getOrNull(1) == "exclusive") {
            val channel = FileChannel.open(
                File(directory, CaptureExtractionCache.LEASE_NAME).toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
            check(channel.tryLock() != null) { "exclusive lease refused" }
            exclusiveChannel = channel
        } else {
            check(CaptureExtractionCache.markInUse(directory)) { "lease refused" }
        }
        println("LEASED")
        System.out.flush()
        while (System.`in`.read() != -1) Unit
        exclusiveChannel?.close()
    }
}

@Suppress("MagicNumber")
class CaptureExtractionLeaseTest {
    private lateinit var root: File
    private lateinit var cache: File

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("capture-extraction-lease").toFile()
        cache = File(root, "cache").also { it.mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        CaptureExtractionCache.leaseWaitMs = CaptureExtractionCache.DEFAULT_LEASE_WAIT_MS
        CaptureExtractionCache.extractionObserver = null
        CaptureExtractionCache.releaseLeases()
        root.deleteRecursively()
    }

    @Test
    fun sweepSkipsADirectoryWhoseLeaseIsHeldByAnotherLockHolder() {
        val now = System.currentTimeMillis()
        val directory = oldDirectory(cache, "a".repeat(32), now)
        val held = directory.leaseFile()
        FileChannel.open(held.toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            assertNotNull(channel.lock(0L, Long.MAX_VALUE, true))

            val result = CaptureExtractionCache.sweep(cache, nowMs = now, budgetBytes = 0, graceMs = 0)

            assertTrue(directory.exists(), "a leased directory survives expiry and the budget pass")
            assertEquals(0, result.deletedTotal)
        }

        val afterRelease = CaptureExtractionCache.sweep(cache, nowMs = now, budgetBytes = 0, graceMs = 0)

        assertFalse(directory.exists(), "once the lease is released the directory is swept, lease file included")
        assertEquals(1, afterRelease.deletedExpired + afterRelease.deletedForBudget)
    }

    @Test
    fun sweepDeletesADirectoryWhoseLeaseFileIsNotHeldOrMissing() {
        val now = System.currentTimeMillis()
        val staleLease = oldDirectory(cache, "b".repeat(32), now).also { it.leaseFile().writeBytes(ByteArray(0)) }
        val noLease = oldDirectory(cache, "c".repeat(32), now)

        CaptureExtractionCache.sweep(cache, nowMs = now, graceMs = 0)

        assertFalse(staleLease.exists(), "a lease file nobody locks (a crashed instance) never protects a directory")
        assertFalse(noLease.exists())
    }

    @Test
    fun markInUseTakesALeaseThatDeleteDirectoryAndReleaseLeasesDrop() {
        val a = oldDirectory(cache, "d".repeat(32), System.currentTimeMillis())
        val b = oldDirectory(cache, "e".repeat(32), System.currentTimeMillis())

        assertTrue(CaptureExtractionCache.markInUse(a))
        assertTrue(CaptureExtractionCache.markInUse(a), "re-marking a directory this process already leases is fine")
        assertTrue(CaptureExtractionCache.markInUse(b))
        assertTrue(CaptureExtractionCache.holdsLease(a) && CaptureExtractionCache.holdsLease(b))
        assertTrue(a.leaseFile().isFile)

        CaptureExtractionCache.deleteDirectory(a)
        assertFalse(a.exists())
        assertFalse(CaptureExtractionCache.holdsLease(a))
        assertFalse(CaptureExtractionCache.isInUse(a))

        CaptureExtractionCache.releaseLeases()
        assertFalse(CaptureExtractionCache.holdsLease(b))
        assertTrue(CaptureExtractionCache.isInUse(b), "the in-use set outlives the lease")
    }

    @Test
    fun markInUseIsRefusedWhileAnotherHolderLocksTheLeaseExclusively() {
        val directory = oldDirectory(cache, "f".repeat(32), System.currentTimeMillis())
        FileChannel.open(directory.leaseFile().toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            assertNotNull(channel.lock())

            assertFalse(CaptureExtractionCache.markInUse(directory), "a sweep is deleting it; the opener must extract again")
            assertFalse(CaptureExtractionCache.isInUse(directory))
        }
    }

    @Test
    fun theLeaseFileDoesNotCountAsAnUnexpectedAssetOnReuse() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val first = CaptureArchiveReader.open(archive, cache)
        val directory = first.logFile.parentFile
        assertTrue(directory.leaseFile().isFile)
        assertTrue(CaptureExtractionCache.holdsLease(directory))

        val reused = CaptureArchiveReader.open(archive, cache)

        assertEquals(directory, reused.logFile.parentFile)
        assertEquals(1, cache.listFiles()!!.size)
    }

    @Test
    fun reExtractionReplacesALeasedDirectoryOfThisProcess() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val first = CaptureArchiveReader.open(archive, cache)
        val directory = first.logFile.parentFile
        assertTrue(requireNotNull(first.videoFile).delete())

        val second = CaptureArchiveReader.open(archive, cache)

        assertTrue(requireNotNull(second.videoFile).isFile)
        assertEquals(directory, second.logFile.parentFile)
        assertTrue(CaptureExtractionCache.holdsLease(directory), "the replacement is leased again")
    }

    @Test
    fun aDamagedDirectoryAnotherProcessStillReadsIsLeftIntactAndTheArchiveIsExtractedElsewhere() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val first = CaptureArchiveReader.open(archive, cache)
        val directory = first.logFile.parentFile
        val video = requireNotNull(first.videoFile)
        CaptureExtractionCache.releaseLeases()
        val child = startLeaseHolder(directory)
        try {
            // Damage that makes this process re-extract, while the other process's tab still reads the rest.
            assertTrue(video.delete())
            val filesBefore = directory.walkTopDown().filter { it.isFile }.toSet()
            val extractions = AtomicInteger()
            CaptureExtractionCache.extractionObserver = { extractions.incrementAndGet() }

            val reopened = CaptureArchiveReader.open(archive, cache)

            assertEquals(1, extractions.get(), "the archive is extracted again")
            assertNotEquals(directory, reopened.logFile.parentFile, "a shared lease is no right to delete: extract elsewhere")
            assertTrue(requireNotNull(reopened.videoFile).isFile)
            assertTrue(reopened.logFile.isFile)
            assertEquals(
                filesBefore,
                directory.walkTopDown().filter { it.isFile }.toSet(),
                "every file the other process's reader depends on is untouched",
            )
            assertTrue(File(directory, CaptureExtractionCache.MARKER_NAME).isFile)
            assertTrue(
                Regex("capture-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(reopened.logFile.parentFile.name),
                "the separate directory is named so the sweep treats it as junk once nothing uses it",
            )
        } finally {
            stop(child)
        }
    }

    @Test
    fun anotherProcessLeaseKeepsADirectoryUntilThatProcessExits() {
        val now = System.currentTimeMillis()
        val directory = oldDirectory(cache, "9".repeat(32), now)
        val child = startLeaseHolder(directory)
        try {
            val whileHeld = CaptureExtractionCache.sweep(cache, nowMs = now, budgetBytes = 0, graceMs = 0)

            assertTrue(directory.exists(), "the sweep must keep what another JVM still has leased")
            assertEquals(0, whileHeld.deletedTotal)
        } finally {
            child.outputStream.close()
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "lease holder did not exit")
        }

        val afterExit = CaptureExtractionCache.sweep(cache, nowMs = now, budgetBytes = 0, graceMs = 0)

        assertFalse(directory.exists(), "the OS drops the lease with its process, so the directory is swept")
        assertEquals(1, afterExit.deletedExpired + afterExit.deletedForBudget)
    }

    @Test
    fun openingAnArchiveWhoseDirectoryAnotherProcessSweepsFailsBusyWithoutDeletingIt() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val directory = extractedAndUnleased(archive)
        val child = startLeaseHolder(directory, exclusive = true)
        try {
            CaptureExtractionCache.leaseWaitMs = 300

            val failure = assertFailsWith<CaptureArchiveException> { CaptureArchiveReader.open(archive, cache) }

            assertTrue(failure.message.orEmpty().contains("being cleaned by another Indagium window"), failure.message)
            assertTrue(directory.leaseFile().isFile, "the sweeping process's lease file is not ours to delete")
            assertTrue(File(directory, CaptureExtractionCache.MARKER_NAME).isFile, "its files are untouched too")
            assertFalse(CaptureExtractionCache.holdsLease(directory))
        } finally {
            stop(child)
        }
    }

    @Test
    fun aSweepThatReleasesTheLeaseWhileWaitingLetsTheOpenReuseTheDirectory() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val directory = extractedAndUnleased(archive)
        val child = startLeaseHolder(directory, exclusive = true)
        val extractions = AtomicInteger()
        CaptureExtractionCache.extractionObserver = { extractions.incrementAndGet() }
        CaptureExtractionCache.leaseWaitMs = 30_000
        val release = releaseAfter(child, 400)
        try {
            val reused = CaptureArchiveReader.open(archive, cache)

            assertEquals(directory, reused.logFile.parentFile)
            assertEquals(0, extractions.get(), "the directory survived the wait, so it is reused, not re-extracted")
            assertTrue(CaptureExtractionCache.holdsLease(directory))
        } finally {
            release.join()
            stop(child)
        }
    }

    @Test
    fun aPartiallySweptDirectoryIsReExtractedOnlyAfterTheSweepReleasesIt() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val directory = extractedAndUnleased(archive)
        val child = startLeaseHolder(directory, exclusive = true)
        // The sweep removes the marker first, so the directory already looks damaged while it is still leased.
        assertTrue(File(directory, CaptureExtractionCache.MARKER_NAME).delete())
        val filesBefore = directory.walkTopDown().filter { it.isFile }.toSet()
        try {
            CaptureExtractionCache.leaseWaitMs = 300
            assertFailsWith<CaptureArchiveException> { CaptureArchiveReader.open(archive, cache) }
            val filesAfter = directory.walkTopDown().filter { it.isFile }.toSet()
            assertEquals(filesBefore, filesAfter, "a damaged-looking directory under a foreign lease is busy, not junk")
        } finally {
            stop(child)
        }
        val extractions = AtomicInteger()
        CaptureExtractionCache.extractionObserver = { extractions.incrementAndGet() }
        CaptureExtractionCache.leaseWaitMs = CaptureExtractionCache.DEFAULT_LEASE_WAIT_MS

        val reopened = CaptureArchiveReader.open(archive, cache)

        assertEquals(1, extractions.get(), "once the lease is free the damaged directory is replaced")
        assertEquals(directory, reopened.logFile.parentFile)
        assertTrue(File(directory, CaptureExtractionCache.MARKER_NAME).isFile)
    }

    @Test
    fun sweepLeavesAnIncompleteKeyedDirectoryThatAnotherProcessStillHoldsExclusively() {
        val now = System.currentTimeMillis()
        val directory = oldDirectory(cache, "8".repeat(32), now)
        assertTrue(File(directory, CaptureExtractionCache.MARKER_NAME).delete())
        FileChannel.open(directory.leaseFile().toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            assertNotNull(channel.lock())

            val result = CaptureExtractionCache.sweep(cache, nowMs = now, graceMs = 0)

            assertTrue(directory.exists(), "a leased directory is busy, never junk, even without its marker")
            assertEquals(0, result.deletedTotal)
        }
    }

    /** Opens [archive] once so its extraction exists, then drops this JVM's lease on it (a closed tab, say). */
    private fun extractedAndUnleased(archive: File): File {
        val directory = CaptureArchiveReader.open(archive, cache).logFile.parentFile
        CaptureExtractionCache.releaseLeases()
        assertTrue(directory.leaseFile().isFile)
        return directory
    }

    private fun releaseAfter(child: Process, delayMs: Long): Thread = Thread {
        Thread.sleep(delayMs)
        child.outputStream.close()
        child.waitFor(60, TimeUnit.SECONDS)
    }.also { it.start() }

    private fun stop(child: Process) {
        runCatching { child.outputStream.close() }
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "lease holder did not exit")
    }

    private fun startLeaseHolder(directory: File, exclusive: Boolean = false): Process {
        val java = File(System.getProperty("java.home"), "bin/java").path
        // The test classpath alone exceeds Windows' 32k command-line limit (CreateProcess error=206),
        // so it travels in a java launcher @argfile, where a quoted value needs its backslashes doubled.
        val argFile = File.createTempFile("lease-holder", ".args").apply { deleteOnExit() }
        argFile.writeText("-cp\n\"${System.getProperty("java.class.path").replace("\\", "\\\\")}\"\n")
        val process = ProcessBuilder(
            java,
            "-Djava.awt.headless=true",
            "@${argFile.absolutePath}",
            CaptureExtractionLeaseHolder::class.java.name,
            directory.absolutePath,
            if (exclusive) "exclusive" else "shared",
        ).redirectErrorStream(true).start()
        val reader = process.inputStream.bufferedReader()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        var line: String?
        do {
            line = reader.readLine()
            if (line == "LEASED") return process
        } while (line != null && System.nanoTime() < deadline)
        process.destroyForcibly()
        error("lease holder did not report a lease (last output: $line)")
    }

    private fun File.leaseFile() = File(this, CaptureExtractionCache.LEASE_NAME)

    /** A complete keyed directory last used 40 days ago, so expiry and the budget would both remove it. */
    private fun oldDirectory(parent: File, hex: String, now: Long): File {
        val dir = File(parent, "capture-$hex").also { it.mkdirs() }
        File(dir, "data.bin").writeBytes(ByteArray(1024))
        CaptureExtractionCache.writeMarker(dir, hex, emptyList())
        val lastUsed = now - 40 * LEASE_DAY_MS
        File(dir, CaptureExtractionCache.MARKER_NAME).setLastModified(lastUsed)
        File(dir, "data.bin").setLastModified(lastUsed)
        dir.setLastModified(lastUsed)
        return dir
    }
}
