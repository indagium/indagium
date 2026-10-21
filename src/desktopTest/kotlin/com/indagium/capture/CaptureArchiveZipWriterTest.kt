package com.indagium.capture

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Suppress("MagicNumber")
class CaptureArchiveZipWriterTest {
    @Test
    fun countedZipBytesEqualTheSerializedArchiveIncludingCompressionModes() {
        val root = createTempDirectory("capture-counted-zip").toFile()
        val work = File(root, "work").apply { mkdirs() }
        File(work, CAPTURE_DESCRIPTOR_NAME).writeText("portable descriptor v4\n")
        File(work, CAPTURED_WITH_INDAGIUM_NAME).writeText("Open this archive in Indagium\n")
        val logPattern = "Tag: repeated capture row\n".toByteArray()
        val logBytes = ByteArray(32_000) { logPattern[it % logPattern.size] }
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
        val expectedSize = CaptureArchiveZipWriter.countBytes(work, descriptor)
        val archive = File(root, "real.zip")
        CaptureArchiveZipWriter.write(work, archive, descriptor)

        assertEquals(archive.length(), expectedSize)
        ZipFile(archive).use { zip ->
            assertEquals(logBytes.size.toLong(), zip.getEntry("logcat.log").size)
            assertTrue(zip.getEntry("logcat.log").compressedSize < logBytes.size / 10)
            assertEquals(videoBytes.size.toLong(), zip.getEntry("screen.mp4").size)
            assertTrue(zip.getEntry("screen.mp4").compressedSize >= videoBytes.size)
            assertTrue(zip.getEntry("screen.mp4").compressedSize < videoBytes.size + 1_024)
            assertTrue(zip.getEntry("screenshots/1-screenshot-1000.png").compressedSize >= screenshotBytes.size)
            assertEquals(timelineBytes.size.toLong(), zip.getEntry("timeline/rows.jsonl").size)
        }
        root.deleteRecursively()
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

    private fun asset(path: String, bytes: ByteArray) = CaptureArchiveAsset(path, bytes.size.toLong(), sha256(bytes))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
