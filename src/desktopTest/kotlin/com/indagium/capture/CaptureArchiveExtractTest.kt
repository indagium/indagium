package com.indagium.capture

import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ROWS = 3
private const val ROW_STEP_MS = 1_000L
private const val SESSION_MS = 4_000L
private const val VIDEO_BYTES = 400 * 1024

/** "Extract from this archive": the whole archive as a plain folder, only the log, or only the video. */
@Suppress("MagicNumber")
class CaptureArchiveExtractTest {
    @Test
    fun allFilesFromACompactIndWithZstdLogAndAv1VideoIsByteIdenticalAndReopens() = withRoot { root ->
        val fixture = fixture(root)
        val archive = fixture.export(File(root, "capture.ind"), CaptureExportFormat.COMPACT)
        commonsZip(archive).use { zip ->
            assertEquals(ZIP_METHOD_ZSTD, zip.getEntry("logcat.log").method, "the fixture must really ship a zstd log")
        }
        assertAllFilesRoundTrip(root, fixture, archive)
    }

    @Test
    fun allFilesFromAV5SparseZipIsByteIdenticalAndReopens() = withRoot { root ->
        val fixture = fixture(root)
        val archive = fixture.export(File(root, "capture.zip"), CaptureExportFormat.ZIP)
        assertAllFilesRoundTrip(root, fixture, archive)
    }

    @Test
    fun allFilesFromLegacyV1AndV2ZipsKeepTheirNestedLayoutAndReopen() = withRoot { root ->
        listOf(1, 2).forEach { version ->
            val log = "01-01 10:00:00.000  1  1 I Tag: legacy row\n".toByteArray()
            val mapping = "{\"ordinal\":1,\"elapsedMs\":1000,\"videoMs\":null}\n".toByteArray()
            val notes = "n1|Plain legacy note".toByteArray()
            val extras = if (version == 2) {
                ",\"notes\":{\"path\":\"notes/capture.ann\",\"sizeBytes\":${notes.size},\"sha256\":\"${sha256(notes)}\"}," +
                    "\"markers\":[{\"noteBlockId\":\"n1\",\"firstOrdinal\":1,\"lastOrdinal\":1}]"
            } else {
                ""
            }
            val descriptor = """
                {"format":"indagium-capture","formatVersion":$version,"sessionId":"legacy-v$version",
                 "device":{"serial":"serial","state":"device","model":"Pixel","emulator":false},
                 "settings":{"formatVersion":1},
                 "sessionStartedEpochMs":1700000000000,"exportedEpochMs":1700000001000,"range":"ALL",
                 "coverage":{"logStartMs":1000,"logEndMs":1000,"videoRequestedStartMs":null,"videoActualStartMs":null,"videoEndMs":null},
                 "mappingMetadata":{"quality":"estimated","uncertaintyMs":null,"manualOffsetMs":0},
                 "interruptions":[],
                 "log":{"path":"logs/logcat.log","sizeBytes":${log.size},"sha256":"${sha256(log)}"},
                 "mapping":{"path":"mapping/log-video.jsonl","sizeBytes":${mapping.size},"sha256":"${sha256(mapping)}"},
                 "video":null,"screenshots":[]$extras}
            """.trimIndent()
            val zip = File(root, "legacy-v$version.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry(CAPTURE_DESCRIPTOR_NAME)); out.write(descriptor.toByteArray()); out.closeEntry()
                out.putNextEntry(ZipEntry("logs/logcat.log")); out.write(log); out.closeEntry()
                out.putNextEntry(ZipEntry("mapping/log-video.jsonl")); out.write(mapping); out.closeEntry()
                if (version == 2) {
                    out.putNextEntry(ZipEntry("notes/capture.ann")); out.write(notes); out.closeEntry()
                }
            }
            val (imported, source) = open(zip, File(root, "cache-v$version"))
            val folder = File(root, "extracted-v$version")

            CaptureArchiveExtractor.extract(source, CaptureExtractKind.ALL_FILES, folder)

            assertContentEquals(log, File(folder, "logs/logcat.log").readBytes())
            assertContentEquals(mapping, File(folder, "mapping/log-video.jsonl").readBytes())
            assertEquals(descriptor, File(folder, CAPTURE_DESCRIPTOR_NAME).readText(), "a legacy descriptor is kept as shipped")
            val reopened = CaptureArchiveReader.open(folder, File(root, "cache-reopen-v$version"))
            assertEquals(imported.descriptor, reopened.descriptor)
            assertEquals(version, reopened.descriptor.formatVersion)
            assertEquals(imported.timeline?.rows, reopened.timeline?.rows)
            reopened.close()
            imported.close()
        }
    }

    @Test
    fun logOnlyAndVideoOnlyWriteTheExactBytesAndOverwriteNeedsConfirmation() = withRoot { root ->
        val fixture = fixture(root)
        val archive = fixture.export(File(root, "capture.ind"), CaptureExportFormat.COMPACT)
        val (imported, source) = open(archive, File(root, "cache"))
        val out = File(root, "out").apply { mkdirs() }

        val log = File(out, "capture.log")
        val result = CaptureArchiveExtractor.extract(source, CaptureExtractKind.LOG_ONLY, log)
        assertEquals(log, result.output)
        assertContentEquals(fixture.logBytes, log.readBytes())
        val video = File(out, "capture.mkv")
        CaptureArchiveExtractor.extract(source, CaptureExtractKind.VIDEO_ONLY, video)
        assertContentEquals(fixture.videoBytes, video.readBytes())

        // An existing target is refused untouched unless the caller confirmed the overwrite.
        log.writeText("precious")
        val refused = assertFailsWith<IllegalArgumentException> {
            CaptureArchiveExtractor.extract(source, CaptureExtractKind.LOG_ONLY, log)
        }
        assertTrue(refused.message.orEmpty().contains("already exists"), refused.message)
        assertEquals("precious", log.readText())
        CaptureArchiveExtractor.extract(source, CaptureExtractKind.LOG_ONLY, log, overwrite = true)
        assertContentEquals(fixture.logBytes, log.readBytes())

        // A folder is never replaced by a file, even when overwriting.
        val folderTarget = File(out, "taken.log").apply { mkdirs() }
        assertFailsWith<IllegalArgumentException> {
            CaptureArchiveExtractor.extract(source, CaptureExtractKind.LOG_ONLY, folderTarget, overwrite = true)
        }
        assertTrue(folderTarget.isDirectory)
        assertNoStaging(out)
        imported.close()
    }

    @Test
    fun videoOnlyIsRejectedForAnArchiveWithoutVideo() = withRoot { root ->
        val fixture = fixture(root, withVideo = false)
        val archive = fixture.export(File(root, "capture.zip"), CaptureExportFormat.ZIP)
        val (imported, source) = open(archive, File(root, "cache"))
        val target = File(root, "out/video.mkv")

        val failure = assertFailsWith<IllegalArgumentException> {
            CaptureArchiveExtractor.extract(source, CaptureExtractKind.VIDEO_ONLY, target)
        }

        assertEquals("This archive has no video", failure.message)
        assertFalse(target.exists())
        // The log and the folder still work for a video-less archive.
        val folder = File(root, "out/all")
        CaptureArchiveExtractor.extract(source, CaptureExtractKind.ALL_FILES, folder)
        assertContentEquals(fixture.logBytes, File(folder, "logcat.log").readBytes())
        imported.close()
    }

    @Test
    fun anExistingDestinationFolderIsRefusedAndLeftUntouched() = withRoot { root ->
        val fixture = fixture(root)
        val archive = fixture.export(File(root, "capture.zip"), CaptureExportFormat.ZIP)
        val (imported, source) = open(archive, File(root, "cache"))
        val existing = File(root, "existing").apply { mkdirs() }
        File(existing, "mine.txt").writeText("keep me")

        val failure = assertFailsWith<IllegalArgumentException> {
            CaptureArchiveExtractor.extract(source, CaptureExtractKind.ALL_FILES, existing, overwrite = true)
        }

        assertTrue(failure.message.orEmpty().contains("already exists"), failure.message)
        assertEquals(listOf("mine.txt"), existing.list().orEmpty().toList(), "nothing is merged into or removed from it")
        assertEquals("keep me", File(existing, "mine.txt").readText())
        assertNoStaging(root)
        imported.close()
    }

    @Test
    fun cancellingMidCopyLeavesNoStagingFolderAndNothingAtTheDestination() = withRoot { root ->
        val fixture = fixture(root)
        val archive = fixture.export(File(root, "capture.ind"), CaptureExportFormat.COMPACT)
        val (imported, source) = open(archive, File(root, "cache"))
        val destination = File(root, "cancelled")

        try {
            assertFailsWith<InterruptedException> {
                CaptureArchiveExtractor.extract(source, CaptureExtractKind.ALL_FILES, destination) { _, _ ->
                    Thread.currentThread().interrupt()
                }
            }
        } finally {
            Thread.interrupted()
        }

        assertFalse(destination.exists())
        assertNoStaging(root)
        // The same holds for a single file, including one that already existed: it is not replaced.
        val file = File(root, "cancelled.mkv").apply { writeText("old") }
        try {
            assertFailsWith<InterruptedException> {
                CaptureArchiveExtractor.extract(source, CaptureExtractKind.VIDEO_ONLY, file, overwrite = true) { _, _ ->
                    Thread.currentThread().interrupt()
                }
            }
        } finally {
            Thread.interrupted()
        }
        assertEquals("old", file.readText())
        assertNoStaging(root)
        imported.close()
    }

    @Test
    fun aCacheFileCorruptedAfterOpenFailsTheChecksumAndPublishesNothing() = withRoot { root ->
        val fixture = fixture(root)
        val archive = fixture.export(File(root, "capture.zip"), CaptureExportFormat.ZIP)
        val (imported, source) = open(archive, File(root, "cache"))
        val video = assertNotNull(imported.videoFile)
        video.writeBytes(video.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
        val folder = File(root, "corrupt-all")
        val single = File(root, "corrupt.mkv")

        val failure = assertFailsWith<IllegalArgumentException> {
            CaptureArchiveExtractor.extract(source, CaptureExtractKind.ALL_FILES, folder)
        }
        assertTrue(failure.message.orEmpty().contains("checksum mismatch"), failure.message)
        assertFailsWith<IllegalArgumentException> {
            CaptureArchiveExtractor.extract(source, CaptureExtractKind.VIDEO_ONLY, single)
        }

        assertFalse(folder.exists())
        assertFalse(single.exists())
        assertNoStaging(root)
        imported.close()
    }

    @Test
    fun aSymbolicLinkInTheCacheIsRefused() = withRoot { root ->
        val fixture = fixture(root)
        val archive = fixture.export(File(root, "capture.zip"), CaptureExportFormat.ZIP)
        val (imported, source) = open(archive, File(root, "cache"))
        val log = imported.logFile
        val real = File(root, "elsewhere.log").also { log.copyTo(it) }
        log.delete()
        val linked = runCatching { java.nio.file.Files.createSymbolicLink(log.toPath(), real.toPath()) }.isSuccess
        if (linked) {
            assertFailsWith<IllegalArgumentException> {
                CaptureArchiveExtractor.extract(source, CaptureExtractKind.LOG_ONLY, File(root, "linked.log"))
            }
            assertFalse(File(root, "linked.log").exists())
        }
        imported.close()
    }

    @Test
    fun theExtractedTargetNameRejectsWhatSomePlatformsCannotStore() {
        assertEquals("trace", captureExtractTargetName("trace", ""))
        assertEquals("trace.log", captureExtractTargetName("trace", ".log"))
        assertEquals("trace.log", captureExtractTargetName("trace.LOG", ".log"), "the suffix is not doubled")
        assertEquals("a.b.mkv", captureExtractTargetName(" a.b ", ".mkv"))
        listOf("", "  ", ".", "..", "a/b", "a\\b", "bad:name", "q?", "tab\tname", "trailing.", "CON", "nul.txt", "COM1", "x".repeat(181))
            .forEach { assertNull(captureExtractTargetName(it, ".log"), "'$it' must be rejected") }
    }

    private fun assertAllFilesRoundTrip(root: File, fixture: Fixture, archive: File) {
        val (imported, source) = open(archive, File(root, "cache"))
        val folder = File(root, "extracted")

        val result = CaptureArchiveExtractor.extract(source, CaptureExtractKind.ALL_FILES, folder)

        assertEquals(folder, result.output)
        // The log is the original plain text, whatever the archive stored it as; the video is as stored (no re-encode).
        assertContentEquals(fixture.logBytes, File(folder, imported.descriptor.log.path).readBytes())
        assertContentEquals(fixture.videoBytes, File(folder, requireNotNull(imported.descriptor.video).path).readBytes())
        imported.descriptor.assets().forEach { asset ->
            assertEquals(asset.sha256, sha256(File(folder, asset.path).readBytes()), "hash of ${asset.path}")
        }
        val blurb = File(folder, CAPTURED_WITH_INDAGIUM_NAME).readText()
        assertTrue(blurb.contains("Indagium") && !blurb.contains("zstd"), blurb)
        assertNoStaging(root)

        // The folder opens again through the in-place folder import, with the same rows, notes and video.
        val reopened = CaptureArchiveReader.open(folder, File(root, "cache-reopen"))
        assertEquals(imported.descriptor, reopened.descriptor)
        assertContentEquals(imported.logFile.readBytes(), reopened.logFile.readBytes())
        assertEquals(imported.notes?.blocks, reopened.notes?.blocks)
        assertEquals(listOf("n1"), reopened.notes?.blocks?.map { it.id })
        assertContentEquals(fixture.videoBytes, assertNotNull(reopened.videoFile).readBytes())
        assertEquals(folder.canonicalFile, reopened.root?.canonicalFile)
        reopened.close()
        imported.close()
    }

    private fun assertNoStaging(directory: File) {
        val leftovers = directory.walkTopDown().filter { ".extracting-" in it.name }.toList()
        assertTrue(leftovers.isEmpty(), "staging left behind: $leftovers")
    }

    private fun open(archive: File, cache: File): Pair<ImportedCapture, CaptureExtractSource> {
        val imported = CaptureArchiveReader.open(archive, cache)
        return imported to CaptureExtractSource(requireNotNull(imported.root), imported.descriptor, archive)
    }

    private fun withRoot(block: (File) -> Unit) {
        val root = createTempDirectory("capture-extract").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun commonsZip(file: File) = org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(file).get()

    private class Fixture(val root: File, val session: CaptureSession, val logBytes: ByteArray, val videoBytes: ByteArray) {
        /** Exports the whole session; the "video" is always [videoBytes] and passes Compact as an already-AV1 stream. */
        fun export(destination: File, format: CaptureExportFormat): File {
            val videoExporter = CaptureVideoExporter { _, staged, start, end ->
                staged.writeBytes(videoBytes)
                CaptureVideoClip(start, end, end - start)
            }
            val av1 = object : CaptureCompactVideoTranscoder {
                override fun ensureAvailable() = error("an existing AV1 stream must not require an encoder")

                override fun isAlreadyAv1(source: File): Boolean = true

                override fun transcode(source: File, destination: File) = error("an existing AV1 stream must not be re-encoded")
            }
            val notes = Annotations(blocks = listOf(AnnBlock.Note("n1", "Analysis note shipped with the archive")))
            return CaptureArchiveExporter(videoExporter, compactVideoTranscoder = av1).export(
                session,
                CaptureExportRequest(
                    destination,
                    CaptureRange.ALL,
                    includeVideo = session.settings.recordVideo,
                    cutoffElapsedMs = session.elapsedMs,
                    format = format,
                ),
                notes = notes,
            ).file
        }
    }

    private fun fixture(root: File, withVideo: Boolean = true): Fixture {
        val session = CaptureSession(
            id = "extract-fixture",
            directory = File(root, "session"),
            device = CaptureDevice("emulator-5554", "device"),
            settings = CaptureSettings(recordVideo = withVideo, freeSpaceReserveBytes = 0L),
            startedEpochMs = 1_700_000_000_000L,
            elapsedMs = SESSION_MS,
            status = CaptureStatus.STOPPED,
            videoStartElapsedMs = if (withVideo) 0L else null,
        )
        session.logFile.parentFile.mkdirs()
        session.indexFile.parentFile.mkdirs()
        var offset = 0L
        session.logFile.outputStream().use { log ->
            session.indexFile.bufferedWriter().use { index ->
                for (i in 1..ROWS) {
                    val bytes = "09-19 12:00:0${i - 1}.000  100  101 I Tag: row $i\n".toByteArray()
                    log.write(bytes)
                    index.appendLine(
                        "{\"byteOffset\":$offset,\"byteLength\":${bytes.size},\"elapsedMs\":${i * ROW_STEP_MS},\"rowOrdinal\":$i}",
                    )
                    offset += bytes.size
                }
            }
        }
        val video = ByteArray(VIDEO_BYTES) { (it * 31 % 251).toByte() }
        if (withVideo) {
            session.videoFile.parentFile.mkdirs()
            session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        }
        return Fixture(root, session, session.logFile.readBytes(), video)
    }
}

/** ZIP method 93, the zstd entries a Compact archive stores its log as. */
private const val ZIP_METHOD_ZSTD = 93
