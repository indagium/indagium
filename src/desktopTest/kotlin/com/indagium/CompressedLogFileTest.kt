package com.indagium

import com.indagium.ui.AppState
import com.indagium.utils.ArchiveBudgetExceededException
import com.indagium.utils.isSupportedArchiveFile
import com.indagium.utils.parseCompressedLog
import com.indagium.utils.parseLogFile
import com.indagium.utils.parseLogcat
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// parseLogFile/parseCompressedLog at the utils level — CompressedLogOpenBehaviorTest covers the
// same feature through AppState (tabs, dedup, autosave restore, tailing).
class CompressedLogFileTest {
    private val sampleLog = """
        06-26 10:00:00.000  100  200 I App: starting up
        06-26 10:00:00.500  100  200 E App: boom
        06-26 10:00:01.000  100  200 D App: cleanup
    """.trimIndent() + "\n"

    private fun buildCompressed(dir: File, name: String, text: String, wrap: (OutputStream) -> OutputStream): File {
        val file = File(dir, name)
        wrap(file.outputStream()).use { it.write(text.toByteArray()) }
        return file
    }

    @Test
    fun parseLogFileOnEveryCompressorEqualsParseLogcatOnTheOriginalText() {
        val dir = createTempDirectory("compressed-log-file").toFile()
        val original = File(dir, "original.log").apply { writeText(sampleLog) }
        val expected = parseLogcat(original)

        val gz = buildCompressed(dir, "payload.bin", sampleLog) { GzipCompressorOutputStream(it) }
        val bz2 = buildCompressed(dir, "payload2.bin", sampleLog) { BZip2CompressorOutputStream(it) }
        val xz = buildCompressed(dir, "payload3.bin", sampleLog) { XZCompressorOutputStream(it) }
        val lzma = buildCompressed(dir, "payload4.bin", sampleLog) { LZMACompressorOutputStream(it) }

        assertEquals(expected, parseLogFile(gz))
        assertEquals(expected, parseLogFile(bz2))
        assertEquals(expected, parseLogFile(xz))
        assertEquals(expected, parseLogFile(lzma))
    }

    @Test
    fun parseLogFileOnAnUncompressedFileIsUnchangedFromParseLogcat() {
        val dir = createTempDirectory("compressed-log-file").toFile()
        val plain = File(dir, "plain.log").apply { writeText(sampleLog) }

        assertEquals(parseLogcat(plain), parseLogFile(plain))
    }

    @Test
    fun parseCompressedLogRejectsContentOverTheByteBudget() {
        val dir = createTempDirectory("compressed-log-file").toFile()
        val huge = "A".repeat(50_000)
        val gz = buildCompressed(dir, "big.log.gz", huge) { GzipCompressorOutputStream(it) }

        assertFailsWith<ArchiveBudgetExceededException> {
            parseCompressedLog(gz, compressorName = "gz", maxBytes = 1_000)
        }
    }

    @Test
    fun compressedLogIsNotASupportedArchiveButIsOpenableAsALog() {
        val dir = createTempDirectory("compressed-log-file").toFile()
        val gz = buildCompressed(dir, "logcat.txt.gz", sampleLog) { GzipCompressorOutputStream(it) }

        // isSupportedArchiveFile deliberately excludes CompressedFile: an archive shows a picker,
        // a compressed log must not (see BugReportZip.kt's table doc / AppState.openPath).
        assertFalse(isSupportedArchiveFile(gz))

        val state = AppState(File(dir, "state.cache"))
        assertTrue(state.isOpenableAsLog(gz))
    }
}
