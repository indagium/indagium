package com.indagium.capture

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Spacing between the synthetic index's rows in fiveRowIndexWithASeparator() below — pulled out to
// a named constant (rather than repeating "1_000" as a literal) purely to keep that helper function
// outside detekt's MagicNumber rule, which exempts @Test-annotated functions but not the private
// helper they call.
private const val MARKER_TEST_ROW_MS = 1_000L

/**
 * `ordinalRangeForElapsedWindow`/`captureLogEntriesForOrdinals` back AppState.markIssue's window
 * scan (restyle plan Phase 3). Both stream a synthetic `capture-index.jsonl` built by hand here —
 * same JSON shape `parseIndexRecord` (CaptureArchive.kt) reads: byteOffset/byteLength/elapsedMs/
 * rowOrdinal, with a separator row's rowOrdinal written as `null`.
 */
class CaptureMarkerWindowTest {
    private fun indexLine(byteOffset: Long, byteLength: Int, elapsedMs: Long, rowOrdinal: Int?): String {
        val ordinalJson = rowOrdinal?.toString() ?: "null"
        return """{"byteOffset":$byteOffset,"byteLength":$byteLength,"elapsedMs":$elapsedMs,"rowOrdinal":$ordinalJson}"""
    }

    // Five ordinary rows (ordinals 1..5, one byte each so offsets are trivial to reason about),
    // one second apart, plus one separator row (rowOrdinal null) sitting between rows 2 and 3.
    private fun fiveRowIndexWithASeparator(): String = buildString {
        val rowMs = MARKER_TEST_ROW_MS
        appendLine(indexLine(0, 1, rowMs, 1))
        appendLine(indexLine(1, 1, rowMs * 2, 2))
        appendLine(indexLine(2, 1, rowMs * 2 + rowMs / 2, null)) // separator: inside every window below, must be skipped
        appendLine(indexLine(3, 1, rowMs * 3, 3))
        appendLine(indexLine(4, 1, rowMs * 4, 4))
        appendLine(indexLine(5, 1, rowMs * 5, 5))
    }

    private fun writeIndex(dir: File, content: String): File {
        val file = File(dir, "capture-index.jsonl")
        file.writeText(content)
        return file
    }

    @Test
    fun windowFullyInsideReturnsExactOrdinalBounds() {
        val dir = Files.createTempDirectory("marker-window").toFile()
        try {
            val content = fiveRowIndexWithASeparator()
            val index = writeIndex(dir, content)
            val range = ordinalRangeForElapsedWindow(index, index.length(), 2_000, 4_000)
            assertEquals(2..4, range)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun windowClippedAtTheStartOnlyIncludesRowsFromTheRequestedStart() {
        val dir = Files.createTempDirectory("marker-window").toFile()
        try {
            val index = writeIndex(dir, fiveRowIndexWithASeparator())
            // Window starts before the capture began; only rows 1..2 fall at or before endMs=2000.
            val range = ordinalRangeForElapsedWindow(index, index.length(), -1_000, 2_000)
            assertEquals(1..2, range)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun windowWithNoMatchingRowsReturnsNull() {
        val dir = Files.createTempDirectory("marker-window").toFile()
        try {
            val index = writeIndex(dir, fiveRowIndexWithASeparator())
            val range = ordinalRangeForElapsedWindow(index, index.length(), 10_000, 20_000)
            assertNull(range)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun separatorRowsNeverContributeAnOrdinalEvenAloneInTheWindow() {
        val dir = Files.createTempDirectory("marker-window").toFile()
        try {
            // A window that covers ONLY the separator row (elapsedMs 2500) and nothing else.
            val onlySeparator = indexLine(0, 1, 2_500, null)
            val index = writeIndex(dir, onlySeparator + "\n")
            val range = ordinalRangeForElapsedWindow(index, index.length(), 2_400, 2_600)
            assertNull(range)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun neverReadsPastTheSuppliedIndexBytesBound() {
        val dir = Files.createTempDirectory("marker-window").toFile()
        try {
            val content = fiveRowIndexWithASeparator()
            val index = writeIndex(dir, content)
            // Freeze the scan at the byte offset right after ordinal 3's line — ordinals 4 and 5
            // exist on disk (the recorder kept writing after the press) but must not be visible.
            val frozenBytes = content.lineSequence().filter { it.isNotBlank() }.take(4).sumOf { (it + "\n").toByteArray().size }.toLong()
            val range = ordinalRangeForElapsedWindow(index, frozenBytes, 1_000, 5_000)
            assertEquals(1..3, range)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun emptyOrMissingIndexReturnsNull() {
        val dir = Files.createTempDirectory("marker-window").toFile()
        try {
            val missing = File(dir, "does-not-exist.jsonl")
            assertNull(ordinalRangeForElapsedWindow(missing, 100, 0, 10_000))
            val empty = writeIndex(dir, "")
            assertNull(ordinalRangeForElapsedWindow(empty, 0, 0, 10_000))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun captureLogEntriesForOrdinalsParsesOnlyTheWantedRowsFromDisk() {
        val dir = Files.createTempDirectory("marker-window-log").toFile()
        try {
            val lines = listOf(
                "01-01 00:00:01.000     1     1 I Tag1   : first line",
                "01-01 00:00:02.000     1     1 I Tag2   : second line",
                "01-01 00:00:03.000     1     1 I Tag3   : third line",
            )
            val logFile = File(dir, "logcat.log")
            var offset = 0L
            val records = mutableListOf<String>()
            lines.forEachIndexed { i, line ->
                val bytes = (line + "\n").toByteArray()
                records += indexLine(offset, bytes.size, (i + 1) * 1_000L, i + 1)
                offset += bytes.size
            }
            logFile.writeBytes(lines.joinToString("\n", postfix = "\n").toByteArray())
            val index = writeIndex(dir, records.joinToString("\n", postfix = "\n"))

            val entries = captureLogEntriesForOrdinals(index, index.length(), logFile, setOf(1, 3))
            assertEquals(listOf(1, 3), entries.map { it.id })
            assertEquals("Tag1", entries[0].tag)
            assertEquals("Tag3", entries[1].tag)
        } finally {
            dir.deleteRecursively()
        }
    }
}
