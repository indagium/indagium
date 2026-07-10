package com.openlog

import com.openlog.model.LogLevel
import com.openlog.utils.ArchiveBudgetExceededException
import com.openlog.utils.ZipLogCandidateKind
import com.openlog.utils.extractCandidate
import com.openlog.utils.isSupportedArchiveFile
import com.openlog.utils.isZipFile
import com.openlog.utils.listArchiveLogCandidates
import com.openlog.utils.listLogcatCandidates
import com.openlog.utils.openArchiveCandidateStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.File
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
