package com.openlog.cases

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaseIndexStoreTest {
    private fun sampleIndex(dir: File): CaseIndex {
        writeCaseNote(
            dir, "note_a", title = "Issue A", issueDescription = "First issue",
            tags = listOf("TagA"), decisiveTags = listOf("TagA"), appVersion = "1.0.0",
        )
        writeCaseNote(
            dir, "note_b", title = "Issue B", issueDescription = "Second issue",
            tags = listOf("TagB"),
        )
        return CaseIndexer.build(listOf(dir))
    }

    @Test
    fun roundTripsFullIndexThroughSaveAndLoad() {
        val dir = createTempDirectory("openlog-case-store").toFile()
        val index = sampleIndex(dir)
        assertEquals(2, index.records.size)
        assertEquals(2, index.fileMeta.size)

        val storeFile = File(createTempDirectory("openlog-case-store-out").toFile(), "case-index")
        CaseIndexStore.save(index, storeFile)
        val loaded = CaseIndexStore.load(storeFile)

        assertEquals(index, loaded)
    }

    @Test
    fun loadOfMissingFileReturnsNull() {
        val dir = createTempDirectory("openlog-case-store-missing").toFile()
        assertNull(CaseIndexStore.load(File(dir, "does-not-exist")))
    }

    @Test
    fun loadOfEmptyFileReturnsNull() {
        val dir = createTempDirectory("openlog-case-store-empty").toFile()
        val empty = File(dir, "case-index").apply { writeText("") }
        assertNull(CaseIndexStore.load(empty))
    }

    @Test
    fun loadWithWrongMagicReturnsNull() {
        val dir = createTempDirectory("openlog-case-store-magic").toFile()
        val file = File(dir, "case-index").apply { writeText("not-the-right-magic\nversion\t1\n") }
        assertNull(CaseIndexStore.load(file))
    }

    @Test
    fun loadWithMismatchedVersionReturnsNull() {
        val dir = createTempDirectory("openlog-case-store-version").toFile()
        val file = File(dir, "case-index").apply {
            writeText("openLog2-case-index-v1\nversion\t999\nbuiltAt\t0\n")
        }
        assertNull(CaseIndexStore.load(file))
    }

    @Test
    fun oneCorruptRecordLineDoesNotTakeDownTheRestOfTheFile() {
        val dir = createTempDirectory("openlog-case-store-corrupt-line").toFile()
        val index = sampleIndex(dir)
        val storeFile = File(createTempDirectory("openlog-case-store-corrupt-out").toFile(), "case-index")
        CaseIndexStore.save(index, storeFile)

        // Corrupt exactly one "record" line (truncate its fields) while leaving everything else
        // (including the other record) intact.
        val lines = storeFile.readLines().toMutableList()
        val recordLineIdx = lines.indexOfFirst { it.startsWith("record\t") }
        lines[recordLineIdx] = "record\tnotenoughfields"
        storeFile.writeText(lines.joinToString("\n") + "\n")

        val loaded = CaseIndexStore.load(storeFile)
        assertEquals(index.version, loaded?.version)
        // Exactly one of the two records was dropped by the corrupt line; the other survives.
        assertEquals(1, loaded?.records?.size)
    }
}
