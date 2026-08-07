package com.indagium

import com.indagium.utils.ArchiveFormat
import com.indagium.utils.detectArchiveFormat
import com.indagium.utils.detectArchiveFormatUncached
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

// Every fixture in this file is named "payload.bin" (or a sibling like "payload2.bin") wherever
// the point of the assertion is that detection is by content, not extension — mirroring
// BugReportZipTest's isZipFileDetectsByContentNotExtension contract for the whole new format set.
class ArchiveFormatDetectionTest {
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

    private fun buildTar(dir: File, name: String, entries: Map<String, String>): File =
        File(dir, name).apply { writeBytes(tarBytes(entries.mapValues { it.value.toByteArray() })) }

    private fun buildTarGz(dir: File, name: String, entries: Map<String, String>): File {
        val out = ByteArrayOutputStream()
        GzipCompressorOutputStream(out).use { it.write(tarBytes(entries.mapValues { e -> e.value.toByteArray() })) }
        return File(dir, name).apply { writeBytes(out.toByteArray()) }
    }

    private fun buildAr(dir: File, name: String, entries: Map<String, String>): File {
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

    private fun buildZip(dir: File, name: String, entries: Map<String, String>): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (path, text) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(text.toByteArray())
                zos.closeEntry()
            }
        }
        return file
    }

    private fun buildSevenZ(dir: File, name: String, entries: Map<String, String>): File {
        val file = File(dir, name)
        SevenZOutputFile(file).use { sevenZ ->
            entries.forEach { (path, text) ->
                val bytes = text.toByteArray()
                sevenZ.putArchiveEntry(SevenZArchiveEntry().apply { this.name = path; this.size = bytes.size.toLong() })
                sevenZ.write(bytes)
                sevenZ.closeArchiveEntry()
            }
        }
        return file
    }

    private fun buildGzipLog(dir: File, name: String, text: String): File {
        val file = File(dir, name)
        GzipCompressorOutputStream(file.outputStream()).use { it.write(text.toByteArray()) }
        return file
    }

    @Test
    fun zipAndSevenZDetectByContentNotExtension() {
        val dir = createTempDirectory("archive-format-detect").toFile()
        val zip = buildZip(dir, "payload.bin", mapOf("main_log.txt" to "hello"))
        val sevenZ = buildSevenZ(dir, "payload2.bin", mapOf("main_log.txt" to "hello"))

        assertEquals(ArchiveFormat.Zip, detectArchiveFormat(zip))
        assertEquals(ArchiveFormat.SevenZ, detectArchiveFormat(sevenZ))
    }

    @Test
    fun bareTarAndArDetectAsSequentialWithNoCompressor() {
        val dir = createTempDirectory("archive-format-detect").toFile()
        val tar = buildTar(dir, "payload.bin", mapOf("main_log.txt" to "hello"))
        val ar = buildAr(dir, "payload2.bin", mapOf("main_log.txt" to "hello"))

        assertEquals(ArchiveFormat.Sequential("tar", null), detectArchiveFormat(tar))
        assertEquals(ArchiveFormat.Sequential("ar", null), detectArchiveFormat(ar))
    }

    @Test
    fun compressedTarDetectsAsSequentialWithTheCompressorNamed() {
        val dir = createTempDirectory("archive-format-detect").toFile()
        val tarGz = buildTarGz(dir, "payload.bin", mapOf("main_log.txt" to "hello"))

        assertEquals(ArchiveFormat.Sequential("tar", "gz"), detectArchiveFormat(tarGz))
    }

    @Test
    fun compressedLogWithNoArchiveInsideIsCompressedFileNotSequential() {
        val dir = createTempDirectory("archive-format-detect").toFile()
        val gz = buildGzipLog(dir, "payload.bin", "06-26 10:00:00.000  100  100 I App: hi")

        val format = detectArchiveFormat(gz)

        assertIs<ArchiveFormat.CompressedFile>(format)
        assertEquals("gz", format.compressorName)
    }

    @Test
    fun plainLogcatAndPlainTextAreNone() {
        val dir = createTempDirectory("archive-format-detect").toFile()
        val logcat = File(dir, "payload.bin").apply { writeText("06-26 10:00:00.000  100  100 I App: hi\n") }
        val text = File(dir, "payload2.bin").apply { writeText("x".repeat(300)) }

        assertEquals(ArchiveFormat.None, detectArchiveFormat(logcat))
        assertEquals(ArchiveFormat.None, detectArchiveFormat(text))
    }

    @Test
    fun rarZstdAndLzipMagicAreUnsupportedNotSilentlyRejected() {
        val dir = createTempDirectory("archive-format-detect").toFile()
        val rar = File(dir, "payload.bin").apply { writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00, 0x00) + ByteArray(64)) }
        val zst = File(dir, "payload2.bin").apply { writeBytes(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()) + ByteArray(64)) }
        val lzip = File(dir, "payload3.bin").apply { writeBytes("LZIP".toByteArray() + ByteArray(64)) }

        assertIs<ArchiveFormat.Unsupported>(detectArchiveFormat(rar))
        assertIs<ArchiveFormat.Unsupported>(detectArchiveFormat(zst))
        assertIs<ArchiveFormat.Unsupported>(detectArchiveFormat(lzip))
    }

    @Test
    fun truncatedZipIsNoneProvingValidationDidNotRegress() {
        val dir = createTempDirectory("archive-format-detect").toFile()
        val zip = buildZip(dir, "payload.bin", mapOf("main_log.txt" to "hello world, this is a real zip entry with real content"))
        // Local file header/magic survives (so a magic-only check would still say "zip"), but the
        // central directory at the end is gone — the same corruption isZipFile's ZipFile(file).use{}
        // validation step exists to catch. Chopping to a quarter guarantees the cut lands well
        // before the central directory regardless of how small the fixture entry compresses to.
        val bytes = zip.readBytes()
        zip.writeBytes(bytes.copyOf(bytes.size / 4))

        assertEquals(ArchiveFormat.None, detectArchiveFormat(zip))
    }

    @Test
    fun cacheReturnsAFreshValueAfterARewriteWithBumpedMtime() {
        val dir = createTempDirectory("archive-format-detect").toFile()
        val file = File(dir, "payload.bin")
        file.writeText("06-26 10:00:00.000  100  100 I App: hi\n")
        file.setLastModified(1_700_000_000_000L)
        assertEquals(ArchiveFormat.None, detectArchiveFormat(file))

        // Rewrite the same path as a zip with a distinctly bumped mtime, so the cache key
        // (canonicalPath + length + lastModified) is guaranteed to change even if the two
        // versions' lengths happened to coincide.
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("main_log.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }
        file.setLastModified(1_700_000_100_000L)

        assertEquals(ArchiveFormat.Zip, detectArchiveFormat(file))
    }

    @Test
    fun uncachedDetectionAgreesWithTheMemoizedResult() {
        val dir = createTempDirectory("archive-format-detect").toFile()
        val zip = buildZip(dir, "payload.bin", mapOf("main_log.txt" to "hello"))
        val tarGz = buildTarGz(dir, "payload2.bin", mapOf("main_log.txt" to "hello"))

        assertEquals(detectArchiveFormatUncached(zip), detectArchiveFormat(zip))
        assertEquals(detectArchiveFormatUncached(tarGz), detectArchiveFormat(tarGz))
        // Sanity check that the two fixtures actually landed on different format kinds — an
        // equality check between two ArchiveFormat.None values would trivially "agree" without
        // proving anything about the cache.
        assertNotEquals(detectArchiveFormat(zip), detectArchiveFormat(tarGz))
    }
}
