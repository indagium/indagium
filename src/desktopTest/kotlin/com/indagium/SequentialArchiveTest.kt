package com.indagium

import com.indagium.utils.ArchiveBudgetExceededException
import com.indagium.utils.extractCandidate
import com.indagium.utils.listArchiveLogCandidates
import com.indagium.utils.listArchiveVideoCandidates
import com.indagium.utils.scanArchiveCandidates
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Mirrors BugReportZipTest's cases, applied to the sequential (tar/ar, no index) formats instead
// of zip/7z — same candidate/extraction contract, different container underneath.
class SequentialArchiveTest {
    private fun tarBytes(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tos ->
            entries.forEach { (path, content) ->
                tos.putArchiveEntry(TarArchiveEntry(path).apply { size = content.size.toLong() })
                tos.write(content)
                tos.closeArchiveEntry()
            }
            tos.finish()
        }
        return out.toByteArray()
    }

    private fun buildTar(dir: File, name: String, entries: Map<String, ByteArray>): File =
        File(dir, name).apply { writeBytes(tarBytes(entries)) }

    private fun buildTextTar(dir: File, name: String, entries: Map<String, String>): File =
        buildTar(dir, name, entries.mapValues { it.value.toByteArray() })

    private fun buildTarGz(dir: File, name: String, entries: Map<String, String>): File {
        val out = ByteArrayOutputStream()
        GzipCompressorOutputStream(out).use { it.write(tarBytes(entries.mapValues { e -> e.value.toByteArray() })) }
        return File(dir, name).apply { writeBytes(out.toByteArray()) }
    }

    private fun buildTextAr(dir: File, name: String, entries: Map<String, String>): File {
        val file = File(dir, name)
        ArArchiveOutputStream(file.outputStream()).use { aos ->
            entries.forEach { (path, text) ->
                val bytes = text.toByteArray()
                aos.putArchiveEntry(ArArchiveEntry(path, bytes.size.toLong()))
                aos.write(bytes)
                aos.closeArchiveEntry()
            }
            aos.finish()
        }
        return file
    }

    // A 7z container holding exactly one entry, "payload.tar" — the .tar.7z one-level-nesting
    // shape from ArchiveFormat.kt's doc comment on scanNestedTar.
    private fun buildTarNestedInSevenZ(dir: File, name: String, innerEntryName: String, tarEntries: Map<String, String>): File {
        val file = File(dir, name)
        val tarPayload = tarBytes(tarEntries.mapValues { it.value.toByteArray() })
        SevenZOutputFile(file).use { sevenZ ->
            sevenZ.putArchiveEntry(SevenZArchiveEntry().apply { this.name = innerEntryName; this.size = tarPayload.size.toLong() })
            sevenZ.write(tarPayload)
            sevenZ.closeArchiveEntry()
        }
        return file
    }

    @Test
    fun listArchiveLogCandidatesFindsLogEntriesInATarAndRejectsBinaryOnesWithoutTruncatingTheScan() {
        val dir = createTempDirectory("sequential-archive").toFile()
        val binary = byteArrayOf(0, 1, 2, 3, 0x89.toByte(), 'H'.code.toByte(), 'E'.code.toByte(), 'A'.code.toByte())
        // Deliberately ordered so a stray `.use {}` on the shared tar stream while classifying
        // "a.log" (which DOES read from its stream, via candidateKind's isText() check) or
        // "bad.log" (same — read, then rejected for containing NUL bytes) would truncate the scan
        // before ever reaching "c.log". Finding "c.log" is the regression guard.
        val tar = buildTar(
            dir, "diag.tar",
            linkedMapOf(
                "a.log" to "06-26 10:00:00.000  100  100 I App: one\n".toByteArray(),
                "bad.log" to binary,
                "c.log" to "06-26 10:00:00.000  100  100 I App: three\n".toByteArray(),
            ),
        )

        val candidates = listArchiveLogCandidates(tar)

        assertEquals(setOf("a.log", "c.log"), candidates.map { it.entryPath }.toSet())
    }

    @Test
    fun sizeBytesIsTheRealUncompressedSizeForATarGzEntry() {
        val dir = createTempDirectory("sequential-archive").toFile()
        val content = "06-26 10:00:00.000  100  100 I App: " + "x".repeat(5_000)
        val tarGz = buildTarGz(dir, "diag.tar.gz", mapOf("app.log" to content))

        val candidate = listArchiveLogCandidates(tarGz).single()

        assertEquals(content.toByteArray().size.toLong(), candidate.sizeBytes)
    }

    @Test
    fun extractCandidatePicksTheRightEntryOutOfATar() {
        val dir = createTempDirectory("sequential-archive").toFile()
        val tar = buildTextTar(
            dir, "diag.tar",
            linkedMapOf(
                "first.log" to "06-26 10:00:00.000  100  100 I App: first\n",
                "second.log" to "06-26 10:00:00.000  100  100 E App: second\n",
            ),
        )
        val candidates = listArchiveLogCandidates(tar)
        val target = candidates.single { it.entryPath == "second.log" }

        val entries = extractCandidate(tar, target)

        assertEquals("second", entries.single().msg)
    }

    @Test
    fun extractCandidateRejectsAnArEntryOverTheByteBudget() {
        val dir = createTempDirectory("sequential-archive").toFile()
        val ar = buildTextAr(dir, "diag.ar", mapOf("main.log" to "x".repeat(200)))
        val candidate = listArchiveLogCandidates(ar).single()

        assertFailsWith<ArchiveBudgetExceededException> {
            extractCandidate(ar, candidate, maxEntryBytes = 50)
        }
    }

    @Test
    fun extractCandidateAllowsATarEntryAtOrUnderTheByteBudget() {
        val dir = createTempDirectory("sequential-archive").toFile()
        val content = "06-26 10:00:00.000  100  100 I App: hi"
        val tar = buildTextTar(dir, "diag.tar", mapOf("main.log" to content))
        val candidate = listArchiveLogCandidates(tar).single()

        val entries = extractCandidate(tar, candidate, maxEntryBytes = content.length.toLong())

        assertEquals(1, entries.size)
    }

    @Test
    fun listArchiveLogCandidatesCapsHowManyTarEntriesAreScanned() {
        val dir = createTempDirectory("sequential-archive").toFile()
        val tar = buildTextTar(
            dir, "diag.tar",
            (1..10).associate { "log_$it.txt" to "06-26 10:00:00.000  100  100 I App: entry $it" },
        )

        val candidates = listArchiveLogCandidates(tar, maxEntries = 3)

        assertTrue(candidates.size <= 3, "expected at most 3 candidates from a capped scan, got ${candidates.size}")
    }

    @Test
    fun tarNestedInsideSevenZYieldsAQualifiedNestedEntryPath() {
        val dir = createTempDirectory("sequential-archive").toFile()
        val archive = buildTarNestedInSevenZ(
            dir, "bugreport.7z", "payload.tar",
            mapOf("logs/main.log" to "06-26 10:00:00.000  100  100 I App: nested\n"),
        )

        val scan = scanArchiveCandidates(archive)

        assertEquals(listOf("payload.tar!logs/main.log"), scan.logCandidates.map { it.entryPath })
        val entries = extractCandidate(archive, scan.logCandidates.single())
        assertEquals("nested", entries.single().msg)
    }

    @Test
    fun scanArchiveCandidatesAgreesFieldForFieldWithTheTwoLegacyListers() {
        val dir = createTempDirectory("sequential-archive").toFile()
        val tarGz = buildTarGz(
            dir, "bugreport.tar.gz",
            mapOf(
                "FS/data/anr/main_log.txt" to "06-26 10:00:00.000  100  100 I App: hi",
                "FS/data/anr/traces.txt" to "DALVIK THREADS (1):\n\"main\" prio=5\n",
            ),
        )

        val scan = scanArchiveCandidates(tarGz)

        assertEquals(listArchiveLogCandidates(tarGz), scan.logCandidates)
        assertEquals(listArchiveVideoCandidates(tarGz), scan.videoCandidates)
    }

    @Test
    fun deliberatelyUnsupportedFormatsYieldZeroCandidatesRatherThanThrowing() {
        val dir = createTempDirectory("sequential-archive").toFile()
        val rar = File(dir, "payload.bin").apply { writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00, 0x00) + ByteArray(64)) }
        val zst = File(dir, "payload2.bin").apply { writeBytes(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()) + ByteArray(64)) }

        assertEquals(emptyList(), listArchiveLogCandidates(rar))
        assertEquals(emptyList(), listArchiveLogCandidates(zst))
    }
}
