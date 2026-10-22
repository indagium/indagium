package com.indagium

import com.indagium.utils.moveReplacingAtomically
import com.indagium.utils.writeFileAtomically
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AtomicFileWriteTest {
    @Test
    fun writesContentToANewDestinationFile() {
        val dir = createTempDirectory("openlog-atomic-write").toFile()
        val dest = File(dir, "out.txt")

        writeFileAtomically(dest) { writer -> writer.write("hello") }

        assertEquals("hello", dest.readText())
    }

    @Test
    fun createsParentDirectoriesIfMissing() {
        val dir = createTempDirectory("openlog-atomic-write").toFile()
        val dest = File(dir, "nested/deeper/out.txt")

        writeFileAtomically(dest) { writer -> writer.write("hi") }

        assertEquals("hi", dest.readText())
    }

    @Test
    fun replacesExistingDestinationContentOnSuccess() {
        val dir = createTempDirectory("openlog-atomic-write").toFile()
        val dest = File(dir, "out.txt").apply { writeText("old content") }

        writeFileAtomically(dest) { writer -> writer.write("new content") }

        assertEquals("new content", dest.readText())
    }

    @Test
    fun leavesDestinationUntouchedAndCleansUpTheTempFileOnFailure() {
        val dir = createTempDirectory("openlog-atomic-write").toFile()
        val dest = File(dir, "out.txt").apply { writeText("original") }

        assertFailsWith<IOException> {
            writeFileAtomically(dest) { writer ->
                writer.write("partial")
                throw IOException("simulated failure mid-write")
            }
        }

        assertEquals("original", dest.readText(), "a failed write must never touch the existing destination")
        val leftoverTempFiles = dir.listFiles().orEmpty().filter { it.name != "out.txt" }
        assertTrue(leftoverTempFiles.isEmpty(), "expected no leftover temp files, found: ${leftoverTempFiles.map { it.name }}")
    }

    @Test
    fun leavesNoFileBehindOnFailureWhenDestinationNeverExisted() {
        val dir = createTempDirectory("openlog-atomic-write").toFile()
        val dest = File(dir, "out.txt")

        assertFailsWith<IOException> {
            writeFileAtomically(dest) { throw IOException("simulated failure") }
        }

        assertTrue(!dest.exists())
        assertTrue(dir.listFiles().orEmpty().isEmpty(), "expected no leftover temp files")
    }

    @Test
    fun moveReplacingAtomicallyReplacesTheDestination() {
        val dir = createTempDirectory("openlog-atomic-move").toFile()
        val source = File(dir, "source.txt").apply { writeText("new") }
        val dest = File(dir, "dest.txt").apply { writeText("old") }

        moveReplacingAtomically(source.toPath(), dest.toPath())

        assertEquals("new", dest.readText())
        assertTrue(!source.exists())
    }

    @Test
    fun moveReplacingAtomicallyFallsBackToAPlainReplacingMoveWhenAtomicIsUnsupported() {
        val dir = createTempDirectory("openlog-atomic-move-fallback").toFile()
        val source = File(dir, "source.txt").apply { writeText("new") }
        val dest = File(dir, "dest.txt").apply { writeText("old") }
        val attempts = mutableListOf<Boolean>()

        moveReplacingAtomically(source.toPath(), dest.toPath()) { from, to, atomic ->
            attempts += atomic
            if (atomic) throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "test")
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }

        assertEquals(listOf(true, false), attempts)
        assertEquals("new", dest.readText())
    }
}
