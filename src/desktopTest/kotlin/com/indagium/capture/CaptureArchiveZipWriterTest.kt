package com.indagium.capture

import org.apache.commons.compress.archivers.zip.ZipMethod
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("MagicNumber")
class CaptureArchiveZipWriterTest {
    @Test
    fun zipEntriesUseCompressionModesMatchingTheirContent() {
        val fixture = writeFixture("capture-counted-zip")
        val archive = File(fixture.root, "real.zip")
        CaptureArchiveZipWriter.write(fixture.work, archive, fixture.descriptor)

        ZipFile(archive).use { zip ->
            assertEquals(fixture.logBytes.size.toLong(), zip.getEntry("logcat.log").size)
            assertTrue(zip.getEntry("logcat.log").compressedSize < fixture.logBytes.size / 10)
            assertEquals(fixture.videoBytes.size.toLong(), zip.getEntry("screen.mp4").size)
            assertTrue(zip.getEntry("screen.mp4").compressedSize >= fixture.videoBytes.size)
            assertTrue(zip.getEntry("screen.mp4").compressedSize < fixture.videoBytes.size + 1_024)
            assertTrue(zip.getEntry("screenshots/1-screenshot-1000.png").compressedSize >= fixture.screenshotBytes.size)
            assertEquals(fixture.timelineBytes.size.toLong(), zip.getEntry("timeline/rows.jsonl").size)
            zip.entries().asSequence().forEach { assertEquals(java.util.zip.ZipEntry.DEFLATED, it.method, it.name) }
            // time = 0 keeps archives byte-reproducible: every entry carries the same fixed DOS timestamp.
            assertEquals(1, zip.entries().asSequence().map { it.lastModifiedTime }.distinct().count())
        }
        fixture.root.deleteRecursively()
    }

    @Test
    fun identicalInputsProduceByteIdenticalZipFiles() {
        val fixture = writeFixture("capture-deterministic-zip")
        val first = File(fixture.root, "first.zip")
        val second = File(fixture.root, "second.zip")
        CaptureArchiveZipWriter.write(fixture.work, first, fixture.descriptor)
        CaptureArchiveZipWriter.write(fixture.work, second, fixture.descriptor)

        assertTrue(first.readBytes().contentEquals(second.readBytes()))
        fixture.root.deleteRecursively()
    }

    @Test
    fun zstdLogEntryUsesMethod93WithTheUncompressedSizeAndCrc() {
        val fixture = writeFixture("capture-zstd-zip")
        val archive = File(fixture.root, "zstd.zip")
        CaptureArchiveZipWriter.write(fixture.work, archive, fixture.descriptor, CaptureArchiveLogCodec.ZSTD)

        org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(archive).get().use { zip ->
            val log = zip.getEntry("logcat.log")
            assertEquals(ZipMethod.ZSTD.code, log.method)
            assertEquals(fixture.logBytes.size.toLong(), log.size)
            assertEquals(CRC32().also { it.update(fixture.logBytes) }.value, log.crc)
            assertTrue(log.compressedSize < log.size / 15, "zstd must beat deflate on repetitive logcat: ${log.compressedSize}/${log.size}")
            val roundTripped = zip.getInputStream(log).use { it.readBytes() }
            assertTrue(fixture.logBytes.contentEquals(roundTripped))
            listOf(CAPTURE_DESCRIPTOR_NAME, CAPTURED_WITH_INDAGIUM_NAME, "timeline/rows.jsonl", "screen.mp4", "screenshots/1-screenshot-1000.png")
                .forEach { assertEquals(ZipMethod.DEFLATED.code, zip.getEntry(it).method, it) }
            assertTrue(zip.getEntry("screen.mp4").compressedSize >= fixture.videoBytes.size)
        }
        // The pre-compressed temp file is cleaned up even on success.
        assertTrue(fixture.work.listFiles().orEmpty().none { it.name.startsWith(".zstd-") })
        fixture.root.deleteRecursively()
    }

    @Test
    fun compactLogZstdWorkersAreBoundedAndLeaveACoreFree() {
        assertEquals(1, compactLogZstdWorkers(0))
        assertEquals(1, compactLogZstdWorkers(1))
        assertEquals(1, compactLogZstdWorkers(2))
        assertEquals(4, compactLogZstdWorkers(5))
        assertEquals(4, compactLogZstdWorkers(64))
    }

    @Test
    fun deflateOnlyArchiveOpensWithTheJdkZipReader() {
        val fixture = writeFixture("capture-jdk-readable-zip")
        val archive = File(fixture.root, "plain.zip")
        CaptureArchiveZipWriter.write(fixture.work, archive, fixture.descriptor)

        ZipFile(archive).use { zip ->
            assertTrue(fixture.logBytes.contentEquals(zip.getInputStream(zip.getEntry("logcat.log")).use { it.readBytes() }))
            assertFalse(zip.entries().asSequence().any { it.method != java.util.zip.ZipEntry.DEFLATED })
        }
        fixture.root.deleteRecursively()
    }

    private class Fixture(
        val root: File,
        val work: File,
        val descriptor: CaptureArchiveDescriptor,
        val logBytes: ByteArray,
        val videoBytes: ByteArray,
        val screenshotBytes: ByteArray,
        val timelineBytes: ByteArray,
    )

    private fun writeFixture(prefix: String): Fixture {
        val root = createTempDirectory(prefix).toFile()
        val work = File(root, "work").apply { mkdirs() }
        File(work, CAPTURE_DESCRIPTOR_NAME).writeText("portable descriptor v4\n")
        File(work, CAPTURED_WITH_INDAGIUM_NAME).writeText("Open this archive in Indagium\n")
        val logBytes = (1..800).joinToString("") { "01-01 10:00:%02d.000  1  1 I Tag: repeated capture row ${it % 7}\n".format(it % 60) }.toByteArray()
        val videoBytes = ByteArray(512_000).also { java.util.Random(22).nextBytes(it) }
        val screenshotBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(128) { it.toByte() }
        val timelineBytes = ("{\"elapsedMs\":1000,\"rowOrdinal\":1}\n").repeat(1_000).toByteArray()
        File(work, "logcat.log").writeBytes(logBytes)
        File(work, "screen.mp4").writeBytes(videoBytes)
        File(work, "screenshots/1-screenshot-1000.png").apply {
            parentFile.mkdirs()
            writeBytes(screenshotBytes)
        }
        File(work, "timeline/rows.jsonl").apply {
            parentFile.mkdirs()
            writeBytes(timelineBytes)
        }
        val descriptor = CaptureArchiveDescriptor(
            formatVersion = 4,
            app = "Indagium test",
            sessionId = "counter-test",
            device = CaptureArchiveDevice("serial", "device", "Pixel", false),
            sessionStartedEpochMs = 1_700_000_000_000,
            exportedEpochMs = 1_700_000_000_100,
            range = CaptureRange.ALL,
            manualOffsetMs = 0,
            interruptions = emptyList(),
            log = asset("logcat.log", logBytes),
            logStartMs = 0,
            logEndMs = 1_000,
            mapping = null,
            video = asset("screen.mp4", videoBytes),
            videoStartMs = 0,
            videoEndMs = 1_000,
            screenshots = listOf(asset("screenshots/1-screenshot-1000.png", screenshotBytes)),
            notes = null,
            markers = emptyList(),
            timeline = asset("timeline/rows.jsonl", timelineBytes),
        )
        return Fixture(root, work, descriptor, logBytes, videoBytes, screenshotBytes, timelineBytes)
    }

    @Test
    fun encoderProcessorSelectionIsBoundedAndHandlesSmallHosts() {
        assertEquals(1, compactEncoderProcessorCount(0))
        assertEquals(1, compactEncoderProcessorCount(1))
        assertEquals(2, compactEncoderProcessorCount(2))
        assertEquals(2, compactEncoderProcessorCount(4))
        assertEquals(6, compactEncoderProcessorCount(8))
        assertEquals(6, compactEncoderProcessorCount(64))
    }

    @Test
    fun liveCaptureEncoderConcurrencyLeavesMostCoresToTheRecording() {
        assertEquals(1, liveCompactEncoderProcessorCount(0))
        assertEquals(1, liveCompactEncoderProcessorCount(4))
        assertEquals(1, liveCompactEncoderProcessorCount(7))
        assertEquals(2, liveCompactEncoderProcessorCount(8))
        assertEquals(2, liveCompactEncoderProcessorCount(64))
        // Never above what a stopped export would use on the same host.
        for (cores in listOf(1, 2, 4, 8, 16, 64)) {
            assertTrue(liveCompactEncoderProcessorCount(cores) <= compactEncoderProcessorCount(cores))
        }
    }

    private fun asset(path: String, bytes: ByteArray) = CaptureArchiveAsset(path, bytes.size.toLong(), sha256(bytes))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
