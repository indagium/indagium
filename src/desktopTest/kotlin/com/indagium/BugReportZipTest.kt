package com.indagium

import com.indagium.model.LogLevel
import com.indagium.utils.ArchiveBudgetExceededException
import com.indagium.utils.LogContentKind
import com.indagium.utils.ZipLogCandidateKind
import com.indagium.utils.candidateKindFromContent
import com.indagium.utils.extractArchiveVideoToCache
import com.indagium.utils.extractCandidate
import com.indagium.utils.isSupportedArchiveFile
import com.indagium.utils.isZipFile
import com.indagium.utils.listArchiveLogCandidates
import com.indagium.utils.listArchiveVideoCandidates
import com.indagium.utils.listLogcatCandidates
import com.indagium.utils.openArchiveCandidateStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BugReportZipTest {
    private fun buildZip(dir: File, name: String, entries: Map<String, ByteArray>): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (path, content) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return file
    }

    private fun buildTextZip(dir: File, name: String, entries: Map<String, String>): File =
        buildZip(dir, name, entries.mapValues { (_, text) -> text.toByteArray() })

    private fun buildSevenZ(dir: File, name: String, entries: Map<String, String>): File {
        val file = File(dir, name)
        SevenZOutputFile(file).use { sevenZ ->
            entries.forEach { (path, content) ->
                val bytes = content.toByteArray()
                val entry = SevenZArchiveEntry().apply {
                    this.name = path
                    this.size = bytes.size.toLong()
                }
                sevenZ.putArchiveEntry(entry)
                sevenZ.write(bytes)
                sevenZ.closeArchiveEntry()
            }
        }
        return file
    }

    private fun buildSevenZBytes(dir: File, name: String, entries: Map<String, ByteArray>): File {
        val file = File(dir, name)
        SevenZOutputFile(file).use { sevenZ ->
            entries.forEach { (path, bytes) ->
                val entry = SevenZArchiveEntry().apply {
                    this.name = path
                    this.size = bytes.size.toLong()
                }
                sevenZ.putArchiveEntry(entry)
                sevenZ.write(bytes)
                sevenZ.closeArchiveEntry()
            }
        }
        return file
    }

    @Test
    fun isZipFileDetectsByContentNotExtension() {
        val dir = createTempDirectory("openlog-zip").toFile()
        val zip = buildTextZip(dir, "bugreport.zip", mapOf("main_log.txt" to "hello"))
        val renamed = File(dir, "bugreport.bin").apply { zip.copyTo(this) }
        val plainText = File(dir, "notes.zip").apply { writeText("just plain text, not a zip") }

        assertTrue(isZipFile(zip))
        assertTrue(isZipFile(renamed))
        assertFalse(isZipFile(plainText))
    }

    @Test
    fun listLogcatCandidatesFindsLogcatAndAnrTextEntriesOnly() {
        val dir = createTempDirectory("openlog-zip").toFile()
        val binaryHeapDump = byteArrayOf(0, 1, 2, 3, 0x89.toByte(), 'H'.code.toByte(), 'E'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte())
        val zip = buildZip(
            dir, "bugreport.zip",
            mapOf(
                "FS/data/anr/main_log.txt" to "06-26 10:00:00.000  100  100 I App: hi".toByteArray(),
                "FS/data/anr/anr_2026-07-02-20-59-33" to "----- pid 123 at 2026-07-02 -----\nCmd line: com.example\n".toByteArray(),
                "FS/data/anr/traces.txt" to "DALVIK THREADS (42):\n\"main\" prio=5\n".toByteArray(),
                "FS/data/system/dropbox_log.log" to "06-26 10:00:01.000  100  100 I App: bye".toByteArray(),
                "FS/data/misc/heap_dump.log" to binaryHeapDump,
                "FS/data/photos/vacation.png" to "not a log at all".toByteArray(),
                "FS/data/system/system_log.zip" to "nested zip entry, wrong extension".toByteArray(),
            ),
        )

        val candidates = listLogcatCandidates(zip)

        assertEquals(
            setOf(
                "FS/data/anr/main_log.txt",
                "FS/data/anr/anr_2026-07-02-20-59-33",
                "FS/data/anr/traces.txt",
                "FS/data/system/dropbox_log.log",
            ),
            candidates.map { it.entryPath }.toSet(),
        )
        assertEquals(
            ZipLogCandidateKind.ANR_TEXT,
            candidates.single { it.entryPath == "FS/data/anr/anr_2026-07-02-20-59-33" }.kind,
        )
        assertEquals(
            ZipLogCandidateKind.LOGCAT,
            candidates.single { it.entryPath == "FS/data/anr/main_log.txt" }.kind,
        )
    }

    @Test
    fun listLogcatCandidatesAcceptsExtensionlessLogNamedEntries() {
        val dir = createTempDirectory("openlog-zip").toFile()
        val zip = buildTextZip(dir, "bugreport.zip", mapOf("main_log" to "06-26 10:00:00.000  100  100 I App: hi"))

        val candidates = listLogcatCandidates(zip)

        assertEquals(listOf("main_log"), candidates.map { it.entryPath })
    }

    @Test
    fun listLogcatCandidatesAcceptsReadableTxtRegardlessOfItsName() {
        val dir = createTempDirectory("openlog-zip").toFile()
        val zip = buildTextZip(dir, "archive(1).zip", mapOf("documents/session.txt" to "plain readable diagnostic text"))

        assertEquals(listOf("documents/session.txt"), listLogcatCandidates(zip).map { it.entryPath })
    }

    @Test
    fun binaryDltCandidateIsDetectedByContentWithoutExtensionAndPreservesMetadataOnExtraction() {
        val dir = createTempDirectory("openlog-dlt-zip").toFile()
        val zip = buildZip(dir, "bugreport.zip", mapOf("capture.bin" to dltTestFrame("from zip", ecu = "ECU1")))

        val candidate = listArchiveLogCandidates(zip).single()
        assertEquals("capture.bin", candidate.entryPath)
        assertEquals(ZipLogCandidateKind.DLT, candidate.kind)
        val entry = extractCandidate(zip, candidate).single()
        assertEquals("from zip", entry.msg)
        assertEquals("ECU1", entry.dltEcuId)
    }

    @Test
    fun identifiableV2DltCandidateIsRoutedToTheClearUnsupportedVersionError() {
        val dir = createTempDirectory("openlog-dlt-v2-zip").toFile()
        // HTYP version bits identify protocol v2; this is deliberately a minimal frame because
        // v2 is routed only far enough to produce the explicit unsupported-version error.
        val zip = buildZip(dir, "capture.zip", mapOf("capture.dlt" to byteArrayOf(0x41, 0, 0, 4)))

        val candidate = listArchiveLogCandidates(zip).single()
        assertEquals(ZipLogCandidateKind.DLT, candidate.kind)
        val error = assertFailsWith<IllegalArgumentException> { extractCandidate(zip, candidate) }
        assertEquals("DLT protocol v2 is not supported", error.message)
    }

    @Test
    fun storageDltPrefixesAreClassifiedBeforeTextHeuristicsForV1AndV2() {
        val dir = createTempDirectory("openlog-dlt-storage-prefix").toFile()
        // These samples deliberately contain no NUL bytes, which makes them text-like to the
        // generic bounded sniff. The explicit storage signature must nevertheless win.
        val printableStorage = { version: Byte -> byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), version) + ByteArray(12) { 0x20 } }
        val zip = buildZip(
            dir,
            "capture.zip",
            mapOf("v1.bin" to printableStorage(1), "v2.bin" to printableStorage(2)),
        )

        assertEquals(
            setOf("v1.bin", "v2.bin"),
            listArchiveLogCandidates(zip).filter { it.kind == ZipLogCandidateKind.DLT }.map { it.entryPath }.toSet(),
        )
    }

    @Test
    fun ordinaryBinaryEntriesNeverMisclassifyAsDltCandidates() {
        val dir = createTempDirectory("openlog-ordinary-binaries").toFile()
        // Real magic bytes for each format, deliberately including ones whose leading byte's
        // top 3 bits happen to look like DLT's v1/v2 version field — the point of the regression
        // is that name-gating (not a content coincidence) is what keeps these out.
        val innerZipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(16)
        val sqliteBytes = "SQLite format 3\u0000".toByteArray() + ByteArray(16)
        val protoBytes = byteArrayOf(0x22, 0x10) + ByteArray(16)
        val randomTextLikeBytes = ByteArray(64) { ('A' + (it % 26)).code.toByte() }
        val zip = buildZip(
            dir, "bugreport.zip",
            mapOf(
                "inner.zip" to innerZipBytes,
                "app.db" to sqliteBytes,
                "activity.proto" to protoBytes,
                "data.bin" to randomTextLikeBytes,
            ),
        )

        assertTrue(listArchiveLogCandidates(zip).isEmpty())
    }

    @Test
    fun sniffIsNeverInvokedForEntriesWhoseNameDoesNotAlreadySuggestALogOrDlt() {
        var calls = 0
        val sniff = { calls++; LogContentKind.OTHER }

        assertEquals(null, candidateKindFromContent("photos/image.png", sniff))
        assertEquals(null, candidateKindFromContent("libs/x.so", sniff))
        assertEquals(null, candidateKindFromContent("proto/a.proto", sniff))

        assertEquals(0, calls, "the sniff lambda must not run for names that are name-gated out")
    }

    @Test
    fun sevenZDltCandidateUsesTheSameContentSniffingPath() {
        val dir = createTempDirectory("openlog-dlt-7z").toFile()
        val archive = buildSevenZBytes(dir, "capture.7z", mapOf("capture.raw" to dltTestFrame("from 7z")))

        val candidate = listArchiveLogCandidates(archive).single()
        assertEquals(ZipLogCandidateKind.DLT, candidate.kind)
        assertEquals("from 7z", extractCandidate(archive, candidate).single().msg)
    }

    @Test
    fun extractCandidateParsesEntryContentInMemory() {
        val dir = createTempDirectory("openlog-zip").toFile()
        val zip = buildTextZip(
            dir, "bugreport.zip",
            mapOf("main_log.txt" to "06-26 10:00:00.000  100  100 E App: boom\n06-26 10:00:00.100  100  100 I App: after"),
        )
        val candidate = listLogcatCandidates(zip).single()

        val entries = extractCandidate(zip, candidate)

        assertEquals(2, entries.size)
        assertEquals(LogLevel.E, entries[0].level)
        assertEquals("boom", entries[0].msg)
        assertEquals(1, entries[0].id)
        assertEquals(2, entries[1].id)
    }

    @Test
    fun archiveCandidateSniffAndExtractionSupportUtf16Be() {
        val dir = createTempDirectory("openlog-zip-utf16").toFile()
        val text = "--------- beginning of kernel\n06-26 10:00:00.000  100  100 E App: boom\n"
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(StandardCharsets.UTF_16BE)
        val zip = buildZip(dir, "bugreport.zip", mapOf("main_log.txt" to bytes))

        val candidate = listLogcatCandidates(zip).single()
        val entries = extractCandidate(zip, candidate)

        assertEquals(1, entries.size)
        assertEquals("boom", entries.single().msg)
        assertEquals("App", entries.single().tag)
    }

    @Test
    fun openArchiveCandidateStreamReadsZipEntryWithoutParsingIt() {
        val dir = createTempDirectory("openlog-zip-stream").toFile()
        val text = "06-26 10:00:00.000  100  100 E App: boom\nraw second line\n"
        val zip = buildTextZip(dir, "bugreport.zip", mapOf("main_log.txt" to text))
        val candidate = listLogcatCandidates(zip).single()

        val raw = openArchiveCandidateStream(zip, candidate)!!.bufferedReader().use { it.readText() }

        assertEquals(text, raw)
    }

    @Test
    fun sevenZArchivesUseTheSameCandidateAndExtractionFlow() {
        val dir = createTempDirectory("openlog-7z").toFile()
        val archive = buildSevenZ(
            dir,
            "bugreport.7z",
            mapOf("FS/data/anr/main_log.txt" to "06-26 10:00:00.000  100  100 E App: boom\n"),
        )

        val candidates = listArchiveLogCandidates(archive)
        val entries = extractCandidate(archive, candidates.single())

        assertTrue(isSupportedArchiveFile(archive))
        assertEquals("FS/data/anr/main_log.txt", candidates.single().entryPath)
        assertEquals("boom", entries.single().msg)
    }

    @Test
    fun listLogcatCandidatesReturnsEmptyForNonZipFile() {
        val dir = createTempDirectory("openlog-zip").toFile()
        val notAZip = File(dir, "notes.txt").apply { writeText("hello") }

        assertTrue(listLogcatCandidates(notAZip).isEmpty())
    }

    @Test
    fun archiveVideoExtractionUsesReusableManagedCacheFile() {
        val dir = createTempDirectory("openlog-video-cache").toFile()
        val archive = buildZip(dir, "bugreport.zip", mapOf("recordings/repro.mp4" to byteArrayOf(1, 2, 3, 4)))
        val candidate = listArchiveVideoCandidates(archive).single()
        val cacheDir = File(dir, "managed-cache")

        val first = extractArchiveVideoToCache(archive, candidate, cacheDir, maxEntryBytes = 10)
        val second = extractArchiveVideoToCache(archive, candidate, cacheDir, maxEntryBytes = 10)

        assertTrue(first?.isFile == true)
        assertEquals(first, second)
        assertTrue(first!!.toPath().startsWith(cacheDir.toPath()))
        assertTrue(first.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    // ── Bounded archive extraction (S-03) ───────────────────────────────

    @Test
    fun extractCandidateRejectsEntryOverTheByteBudget() {
        val dir = createTempDirectory("openlog-zip-budget").toFile()
        val zip = buildTextZip(dir, "bugreport.zip", mapOf("main_log.txt" to "x".repeat(200)))
        val candidate = listLogcatCandidates(zip).single()

        assertFailsWith<ArchiveBudgetExceededException> {
            extractCandidate(zip, candidate, maxEntryBytes = 50)
        }
    }

    @Test
    fun extractCandidateAllowsEntryAtOrUnderTheByteBudget() {
        val dir = createTempDirectory("openlog-zip-budget").toFile()
        val content = "06-26 10:00:00.000  100  100 I App: hi"
        val zip = buildTextZip(dir, "bugreport.zip", mapOf("main_log.txt" to content))
        val candidate = listLogcatCandidates(zip).single()

        // Exactly at the budget must still succeed — only strictly-over is rejected.
        val entries = extractCandidate(zip, candidate, maxEntryBytes = content.length.toLong())

        assertEquals(1, entries.size)
    }

    @Test
    fun extractCandidateEnforcesBudgetAgainstActualBytesNotDeclaredSize() {
        // Highly compressible content: a few KB of decompressed text shrinks to a tiny compressed
        // size under DEFLATE — the same shape as a zip-bomb entry, where declared/compressed size
        // gives no hint of the real decompressed volume. The budget must be enforced against bytes
        // actually read off the stream, not any size metadata.
        val dir = createTempDirectory("openlog-zip-budget").toFile()
        val highlyCompressible = "A".repeat(50_000)
        val zip = buildTextZip(dir, "bugreport.zip", mapOf("main_log.txt" to highlyCompressible))
        val candidate = listLogcatCandidates(zip).single()

        assertFailsWith<ArchiveBudgetExceededException> {
            extractCandidate(zip, candidate, maxEntryBytes = 1_000)
        }
    }

    @Test
    fun extractCandidateBudgetAlsoAppliesToSevenZEntries() {
        val dir = createTempDirectory("openlog-7z-budget").toFile()
        val archive = buildSevenZ(dir, "bugreport.7z", mapOf("FS/data/anr/main_log.txt" to "x".repeat(200)))
        val candidate = listArchiveLogCandidates(archive).single()

        assertFailsWith<ArchiveBudgetExceededException> {
            extractCandidate(archive, candidate, maxEntryBytes = 50)
        }
    }

    @Test
    fun listArchiveLogCandidatesCapsHowManyEntriesAreScanned() {
        val dir = createTempDirectory("openlog-zip-entrycap").toFile()
        val zip = buildTextZip(
            dir, "bugreport.zip",
            (1..10).associate { "log_$it.txt" to "06-26 10:00:00.000  100  100 I App: entry $it" },
        )

        // All 10 entries would otherwise qualify; capping the scan at 3 must bound the result to
        // at most the first 3 entries examined, not silently scan the whole archive anyway.
        val candidates = listArchiveLogCandidates(zip, maxEntries = 3)

        assertTrue(candidates.size <= 3, "expected at most 3 candidates from a capped scan, got ${candidates.size}")
    }
}
