package com.openlog

import com.openlog.model.LogLevel
import com.openlog.utils.extractCandidate
import com.openlog.utils.isZipFile
import com.openlog.utils.listLogcatCandidates
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun listLogcatCandidatesFindsLogNamedTextEntriesOnly() {
        val dir = createTempDirectory("openlog-zip").toFile()
        val binaryHeapDump = byteArrayOf(0, 1, 2, 3, 0x89.toByte(), 'H'.code.toByte(), 'E'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte())
        val zip = buildZip(
            dir, "bugreport.zip",
            mapOf(
                "FS/data/anr/main_log.txt" to "06-26 10:00:00.000  100  100 I App: hi".toByteArray(),
                "FS/data/system/dropbox_log.log" to "06-26 10:00:01.000  100  100 I App: bye".toByteArray(),
                "FS/data/misc/heap_dump.log" to binaryHeapDump,
                "FS/data/photos/vacation.png" to "not a log at all".toByteArray(),
                "FS/data/system/system_log.zip" to "nested zip entry, wrong extension".toByteArray(),
            ),
        )

        val candidates = listLogcatCandidates(zip)

        assertEquals(setOf("FS/data/anr/main_log.txt", "FS/data/system/dropbox_log.log"), candidates.map { it.entryPath }.toSet())
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
    fun listLogcatCandidatesReturnsEmptyForNonZipFile() {
        val dir = createTempDirectory("openlog-zip").toFile()
        val notAZip = File(dir, "notes.txt").apply { writeText("hello") }

        assertTrue(listLogcatCandidates(notAZip).isEmpty())
    }
}
