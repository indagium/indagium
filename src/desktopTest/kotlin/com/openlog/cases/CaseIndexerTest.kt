package com.openlog.cases

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaseIndexerTest {
    @Test
    fun buildIndexesEveryMdAnnPairWithTitleTagsAndTokens() {
        val dir = createTempDirectory("openlog-case-indexer").toFile()
        writeCaseNote(
            dir, "crash_analysis",
            title = "ANR in main thread",
            issueDescription = "App freezes on cold start",
            tags = listOf("ActivityManager", "InputDispatcher"),
            decisiveTags = listOf("ActivityManager"),
            appVersion = "1.5.0",
            sourcePath = "/logs/crash.log",
        )

        val index = CaseIndexer.build(listOf(dir))

        assertEquals(1, index.records.size)
        val record = index.records.single()
        assertEquals("ANR in main thread", record.title)
        assertEquals("App freezes on cold start", record.issueDescription)
        assertEquals("/logs/crash.log", record.sourcePath)
        assertEquals("1.5.0", record.appVersion)
        assertEquals(listOf("ActivityManager"), record.decisiveTags)
        // tags = decisiveTags ∪ tags harvested from LogRef sourceEntries
        assertEquals(setOf("ActivityManager", "InputDispatcher"), record.tags)
        assertTrue("anr" in record.tokens)
        assertTrue("freezes" in record.tokens)
        assertEquals(File(dir, "crash_analysis.md").absolutePath, record.id)
        assertEquals(File(dir, "crash_analysis.md").absolutePath, record.mdPath)
        assertEquals(File(dir, "crash_analysis.ann").absolutePath, record.annPath)
        // .ann is preferred as the backing (staleness-tracked) file when both exist.
        assertEquals(File(dir, "crash_analysis.ann").absolutePath, record.backingPath)
    }

    @Test
    fun indexesALoneHandCopiedAnnWithNoPairedMd() {
        val dir = createTempDirectory("openlog-case-indexer-lone-ann").toFile()
        writeCaseNote(
            dir, "pasted_note",
            title = "unused",
            issueDescription = "Network timeout on retry",
            tags = listOf("OkHttp"),
            writeMd = false,
        )

        val index = CaseIndexer.build(listOf(dir))

        assertEquals(1, index.records.size)
        val record = index.records.single()
        // No .md exists, so title falls back to the base filename.
        assertEquals("pasted_note", record.title)
        assertNull(record.mdPath)
        assertNotNull(record.annPath)
        assertEquals("Network timeout on retry", record.issueDescription)
    }

    @Test
    fun readCaseTextPrefersMdVerbatimAndReconstructsWhenAnnOnly() {
        val dir = createTempDirectory("openlog-case-indexer-text").toFile()
        writeCaseNote(dir, "with_md", title = "Has MD", issueDescription = "desc", extraMdText = "Full markdown body.")
        writeCaseNote(dir, "ann_only", title = "unused", issueDescription = "desc2", writeMd = false)

        val index = CaseIndexer.build(listOf(dir))
        val withMd = index.records.single { it.id.endsWith("with_md.md") }
        val annOnly = index.records.single { it.title == "ann_only" }

        val mdText = CaseIndexer.readCaseText(withMd)
        assertNotNull(mdText)
        assertTrue(mdText.contains("Full markdown body."))

        val reconstructed = CaseIndexer.readCaseText(annOnly)
        assertNotNull(reconstructed)
    }

    @Test
    fun enumerateBaseNamesFindsBothMdOnlyAndAnnOnlyNotes() {
        val dir = createTempDirectory("openlog-case-indexer-enum").toFile()
        writeCaseNote(dir, "both", title = "Both", issueDescription = "d")
        writeCaseNote(dir, "ann-only", title = "unused", issueDescription = "d2", writeMd = false)
        File(dir, "plain.md").writeText("# Plain md only\n")

        val baseNames = CaseIndexer.enumerateBaseNames(dir).toSet()

        assertEquals(setOf("both", "ann-only", "plain"), baseNames)
    }

    @Test
    fun buildOnEmptyOrMissingDirYieldsNoRecords() {
        val missing = File(createTempDirectory("openlog-case-indexer-missing").toFile(), "does-not-exist")
        assertEquals(0, CaseIndexer.build(listOf(missing)).records.size)
    }
}
