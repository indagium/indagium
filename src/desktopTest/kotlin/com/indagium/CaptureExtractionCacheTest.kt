package com.indagium

import com.indagium.capture.CaptureArchiveExporter
import com.indagium.capture.CaptureArchiveReader
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureExtractionCache
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureStatus
import com.indagium.capture.CaptureVideoClip
import com.indagium.capture.CaptureVideoExporter
import com.indagium.ui.AppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
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

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val MB = 1024L * 1024L

@Suppress("MagicNumber", "TooGenericExceptionCaught")
class CaptureExtractionCacheTest {
    private val extractions = AtomicInteger()
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("capture-extraction-cache").toFile()
        extractions.set(0)
        CaptureExtractionCache.extractionObserver = { extractions.incrementAndGet() }
    }

    @AfterTest
    fun tearDown() {
        CaptureExtractionCache.extractionObserver = null
        root.deleteRecursively()
    }

    @Test
    fun openingTheSameArchiveTwiceReusesTheDirectoryWithoutExtractingAgain() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root, videoBytes = 2 * MB.toInt())
        val cache = File(root, "cache")

        val first = CaptureArchiveReader.open(archive, cache)
        val video = requireNotNull(first.videoFile)
        val videoModified = video.lastModified()
        val videoKey = fileIdentity(video)
        val second = CaptureArchiveReader.open(archive, cache)

        assertEquals(1, extractions.get())
        assertEquals(first.logFile.parentFile, second.logFile.parentFile)
        assertEquals(videoModified, requireNotNull(second.videoFile).lastModified())
        assertEquals(videoKey, fileIdentity(second.videoFile!!))
        assertEquals(first.logFile.readText(), second.logFile.readText())
        assertEquals(first.descriptor, second.descriptor)
        assertEquals(listOf(first.logFile.parentFile.name), cache.list()!!.toList())
    }

    @Test
    fun aChangedArchiveGetsANewDirectory() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val cache = File(root, "cache")
        val first = CaptureArchiveReader.open(archive, cache)

        assertTrue(archive.setLastModified(archive.lastModified() + 5_000))
        val touched = CaptureArchiveReader.open(archive, cache)

        assertNotEquals(first.logFile.parentFile, touched.logFile.parentFile)
        assertEquals(2, extractions.get())
        assertEquals(2, cache.listFiles()!!.size)
    }

    @Test
    fun aDeletedAssetIsExtractedAgain() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val cache = File(root, "cache")
        val first = CaptureArchiveReader.open(archive, cache)
        val video = requireNotNull(first.videoFile)
        assertTrue(video.delete())

        val second = CaptureArchiveReader.open(archive, cache)

        assertEquals(2, extractions.get())
        assertEquals(first.logFile.parentFile, second.logFile.parentFile)
        assertTrue(requireNotNull(second.videoFile).isFile)
        assertEquals(1, cache.listFiles()!!.size)
    }

    @Test
    fun aTruncatedAssetIsExtractedAgain() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root, videoBytes = 4096)
        val cache = File(root, "cache")
        val first = CaptureArchiveReader.open(archive, cache)
        val video = requireNotNull(first.videoFile)
        val original = video.length()
        java.io.RandomAccessFile(video, "rw").use { it.setLength(original / 2) }

        val second = CaptureArchiveReader.open(archive, cache)

        assertEquals(2, extractions.get())
        assertEquals(original, requireNotNull(second.videoFile).length())
    }

    @Test
    fun aDirectoryWithoutItsMarkerIsNeverTrusted() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val cache = File(root, "cache")
        val first = CaptureArchiveReader.open(archive, cache)
        assertTrue(File(first.logFile.parentFile, CaptureExtractionCache.MARKER_NAME).delete())

        CaptureArchiveReader.open(archive, cache)

        assertEquals(2, extractions.get())
    }

    @Test
    fun concurrentOpensOfOneArchiveExtractOnce() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root, videoBytes = 3 * MB.toInt())
        val cache = File(root, "cache")
        val start = CountDownLatch(1)
        val dirs = java.util.Collections.synchronizedList(mutableListOf<File>())
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = List(8) {
            thread {
                try {
                    start.await()
                    dirs += CaptureArchiveReader.open(archive, cache).logFile.parentFile
                } catch (failure: Throwable) {
                    failures += failure
                }
            }
        }
        start.countDown()
        threads.forEach { it.join(30_000) }

        assertEquals(emptyList(), failures)
        assertEquals(1, extractions.get())
        assertEquals(1, dirs.toSet().size)
        assertEquals(8, dirs.size)
        assertEquals(1, cache.listFiles()!!.size)
    }

    @Test
    fun aCorruptedArchiveStillFailsAndLeavesNothingBehind() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val tampered = File(root, "tampered.zip")
        CaptureArchiveFixtures.rewriteZip(archive, tampered) { name, bytes ->
            if (name == "logcat.log") bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() } else bytes
        }
        val cache = File(root, "cache")

        repeat(2) { assertFailsWith<Exception> { CaptureArchiveReader.open(tampered, cache) } }

        assertEquals(emptyList(), cache.list()!!.toList(), "no extraction directory or marker survives a failed open")
        assertEquals(2, extractions.get(), "a corrupted archive is verified again on every open")
    }

    @Test
    fun sweepRemovesLegacyTemporaryAndExpiredButKeepsRecentAndInUse() {
        val cache = File(root, "cache").also { it.mkdirs() }
        val now = System.currentTimeMillis()
        val legacy = fakeDirectory(cache, "capture-3f2b8c1e-1a2b-4c3d-8e9f-0123456789ab", 1, now - 2 * DAY_MS)
        val temp = fakeDirectory(cache, "capture-tmp-${"a".repeat(32)}-uuid", 1, now - 2 * DAY_MS)
        val expired = keyedDirectory(cache, "1".repeat(32), 1, lastUsed = now - 20 * DAY_MS)
        val recent = keyedDirectory(cache, "2".repeat(32), 1, lastUsed = now - 2 * DAY_MS)
        val inUse = keyedDirectory(cache, "3".repeat(32), 1, lastUsed = now - 40 * DAY_MS).also(CaptureExtractionCache::markInUse)
        val referenced = keyedDirectory(cache, "4".repeat(32), 1, lastUsed = now - 40 * DAY_MS)
        val freshLegacy = fakeDirectory(cache, "capture-aaaaaaaa-1a2b-4c3d-8e9f-0123456789ab", 1, now)
        val unrelated = fakeDirectory(cache, "notes-for-someone-else", 1, now - 99 * DAY_MS)

        val result = CaptureExtractionCache.sweep(cache, nowMs = now, isReferenced = { it == referenced })

        assertFalse(legacy.exists())
        assertFalse(temp.exists())
        assertFalse(expired.exists())
        assertTrue(recent.exists())
        assertTrue(inUse.exists(), "a directory this process opened is never swept")
        assertTrue(referenced.exists(), "a directory persisted/open state points into is never swept")
        assertTrue(freshLegacy.exists(), "a directory active within the grace period may belong to another instance")
        assertTrue(unrelated.exists(), "names outside the capture-* scheme are left alone")
        assertEquals(1, result.deletedLegacy)
        assertEquals(1, result.deletedTemporary)
        assertEquals(1, result.deletedExpired)
        assertEquals(0, result.deletedForBudget)
    }

    @Test
    fun sweepEnforcesTheBudgetLeastRecentlyUsedFirstSkippingInUse() {
        val cache = File(root, "cache").also { it.mkdirs() }
        val now = System.currentTimeMillis()
        val oldest = keyedDirectory(cache, "a".repeat(32), MB.toInt(), lastUsed = now - 5 * DAY_MS)
        val protectedOld = keyedDirectory(cache, "b".repeat(32), MB.toInt(), lastUsed = now - 4 * DAY_MS)
            .also(CaptureExtractionCache::markInUse)
        val middle = keyedDirectory(cache, "c".repeat(32), MB.toInt(), lastUsed = now - 3 * DAY_MS)
        val newest = keyedDirectory(cache, "d".repeat(32), MB.toInt(), lastUsed = now - 2 * DAY_MS)

        val result = CaptureExtractionCache.sweep(cache, nowMs = now, budgetBytes = 2 * MB + MB / 2)

        assertFalse(oldest.exists())
        assertTrue(protectedOld.exists())
        assertFalse(middle.exists(), "the next least recently used goes once the in-use one is skipped")
        assertTrue(newest.exists())
        assertEquals(2, result.deletedForBudget)
        assertTrue(result.remainingBytes <= 2 * MB + MB / 2)
    }

    @Test
    fun sweepKeepsADirectoryAnOpenIsReusing() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val cache = File(root, "cache")
        val opened = CaptureArchiveReader.open(archive, cache)
        val farFuture = System.currentTimeMillis() + 365 * DAY_MS

        CaptureExtractionCache.sweep(cache, nowMs = farFuture, budgetBytes = 0)

        assertTrue(opened.logFile.isFile && requireNotNull(opened.videoFile).isFile)
    }

    @Test
    fun reusingADirectoryRefreshesItsLastUsedTime() {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val cache = File(root, "cache")
        val first = CaptureArchiveReader.open(archive, cache)
        val marker = File(first.logFile.parentFile, CaptureExtractionCache.MARKER_NAME)
        assertTrue(marker.setLastModified(System.currentTimeMillis() - 30 * DAY_MS))

        CaptureArchiveReader.open(archive, cache)

        assertTrue(System.currentTimeMillis() - marker.lastModified() < DAY_MS)
        assertEquals(1, extractions.get())
    }

    @Test
    fun restoreLogLeftoversAreSweptAfterTheGracePeriod() {
        val cache = File(root, "capture-log-restore").also { it.mkdirs() }
        val now = System.currentTimeMillis()
        val stale = fakeDirectory(cache, "capture-log-restore-1", 1, now - DAY_MS)
        val fresh = fakeDirectory(cache, "capture-log-restore-2", 1, now)

        assertEquals(1, CaptureExtractionCache.sweepRestoreLogLeftovers(cache, now))

        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun restoringTheSameCaptureTabTwiceLeavesExactlyOneExtraction() = runBlocking {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val cache = File(root, "app-cache")
        val autosave = File(root, "autosave")
        val app = AppState(autosaveFile = autosave, autoExportNotes = false, archiveCacheDir = cache)
        val id = app.openCaptureFile(archive)
        awaitLoaded(app, id)
        app.autosaveNow()
        app.close()
        assertEquals(1, File(cache, "captures").listFiles()!!.size)

        repeat(2) {
            val restored = AppState(autosaveFile = autosave, restoreOnCreate = true, autoExportNotes = false, archiveCacheDir = cache)
            try {
                restored.startPendingRestoredTabLoads()
                awaitLoaded(restored, id)
                assertNotNull(restored.tab(id)?.attachedVideo)
                assertEquals(archive.absolutePath, restored.tab(id)?.captureArchivePath)
                assertNotNull(restored.captureSnapshotSessionFor(id))
            } finally {
                restored.close()
            }
        }

        assertEquals(1, File(cache, "captures").listFiles()!!.size, "two restarts add no extraction directory")
        assertEquals(1, extractions.get(), "only the first open extracted the archive")
        Unit
    }

    @Test
    fun startupSweepRemovesLegacyDirectoriesButNotTheOnesARestoredTabUses() = runBlocking {
        val archive = CaptureArchiveFixtures.exportWithVideo(root)
        val cache = File(root, "app-cache")
        val autosave = File(root, "autosave")
        val app = AppState(autosaveFile = autosave, autoExportNotes = false, archiveCacheDir = cache)
        val id = app.openCaptureFile(archive)
        awaitLoaded(app, id)
        app.autosaveNow()
        app.close()
        val captures = File(cache, "captures")
        val legacy = fakeDirectory(captures, "capture-3f2b8c1e-1a2b-4c3d-8e9f-0123456789ab", 1, System.currentTimeMillis() - 2 * DAY_MS)

        val restored = AppState(autosaveFile = autosave, restoreOnCreate = true, autoExportNotes = false, archiveCacheDir = cache)
        try {
            restored.startPendingRestoredTabLoads()
            awaitLoaded(restored, id)
            // The constructor already started the startup sweep (restoreOnCreate); wait for it.
            withTimeout(10_000) { while (legacy.exists()) delay(20) }
            // Even a sweep far in the future with no grace period and a zero budget must keep what
            // this process opened.
            val result = restored.sweepCaptureExtractionCache(nowMs = System.currentTimeMillis() + 60 * DAY_MS, graceMs = 0, budgetBytes = 0)
            assertEquals(0, result.deletedTotal)
            assertEquals(1, captures.listFiles()!!.size, "the keyed directory the restored tab opened survives")
            assertTrue(requireNotNull(restored.tab(id)).logData.isNotEmpty())
        } finally {
            restored.close()
        }
        Unit
    }

    private suspend fun awaitLoaded(app: AppState, id: String) {
        // Wait for the load to settle, not for the tab: a failed open leaves no tab and no load in
        // flight, and the assertion below then reports openError instead of a bare timeout.
        withTimeout(10_000) { while (app.isLoadInFlight(id)) delay(20) }
        assertNotNull(app.tab(id), app.openError?.message)
    }

    /** The inode where the file system has one (POSIX), else the creation time (Windows has no `unix` view). */
    private fun fileIdentity(file: File): Any? =
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).let { it.fileKey() ?: it.creationTime() }

    private fun fakeDirectory(parent: File, name: String, bytes: Int, modified: Long): File =
        File(parent, name).also { dir ->
            dir.mkdirs()
            File(dir, "data.bin").writeBytes(ByteArray(bytes))
            dir.setLastModified(modified)
            File(dir, "data.bin").setLastModified(modified)
            dir.setLastModified(modified)
        }

    private fun keyedDirectory(parent: File, hex: String, bytes: Int, lastUsed: Long): File {
        val dir = File(parent, "capture-$hex").also { it.mkdirs() }
        File(dir, "data.bin").writeBytes(ByteArray(bytes))
        CaptureExtractionCache.writeMarker(dir, hex, emptyList())
        File(dir, CaptureExtractionCache.MARKER_NAME).setLastModified(lastUsed)
        File(dir, "data.bin").setLastModified(lastUsed)
        dir.setLastModified(lastUsed)
        return dir
    }
}

@Suppress("MagicNumber")
internal object CaptureArchiveFixtures {
    /** A real exported capture archive: a three-row log plus a synthetic [videoBytes]-byte MP4 entry. */
    fun exportWithVideo(root: File, videoBytes: Int = 64): File {
        val session = CaptureSession(
            "cache-test",
            File(root, "session"),
            CaptureDevice("emulator-5554", "device"),
            CaptureSettings(recordVideo = true, freeSpaceReserveBytes = 0L),
            1_700_000_000_000L,
            elapsedMs = 4_000L,
            status = CaptureStatus.STOPPED,
            videoStartElapsedMs = 500L,
        )
        session.logFile.parentFile.mkdirs()
        session.indexFile.parentFile.mkdirs()
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeText("fixture")
        var offset = 0L
        session.logFile.outputStream().use { log ->
            session.indexFile.bufferedWriter().use { index ->
                for (i in 1..3) {
                    val bytes = "09-19 12:00:0${i - 1}.000  100  101 I Tag: row $i\n".toByteArray()
                    log.write(bytes)
                    index.appendLine("{\"byteOffset\":$offset,\"byteLength\":${bytes.size},\"elapsedMs\":${i * 1_000L},\"rowOrdinal\":$i}")
                    offset += bytes.size
                }
            }
        }
        val payload = ByteArray(videoBytes) { (it * 31 % 251).toByte() }
        val exporter = CaptureArchiveExporter(CaptureVideoExporter { _, destination, _, _ ->
            destination.writeBytes(payload)
            CaptureVideoClip(0L, 3_500L, 3_500L)
        })
        return exporter.export(
            session,
            CaptureExportRequest(File(root, "capture-$videoBytes.zip"), cutoffElapsedMs = 4_000L),
        ).file
    }

    fun rewriteZip(source: File, destination: File, transform: (String, ByteArray) -> ByteArray) {
        ZipFile(source).use { input ->
            ZipOutputStream(destination.outputStream()).use { output ->
                input.entries().asSequence().forEach { entry ->
                    val bytes = input.getInputStream(entry).use { it.readBytes() }
                    output.putNextEntry(ZipEntry(entry.name))
                    output.write(transform(entry.name, bytes))
                    output.closeEntry()
                }
            }
        }
    }
}
