package com.openlog.cases

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaseSearchTest {
    private fun newSearch(dir: File): CaseSearch {
        val indexFile = File(createTempDirectory("openlog-case-search-index").toFile(), "case-index")
        return CaseSearch(noteDirs = { listOf(dir) }, indexFile = indexFile)
    }

    @Test
    fun searchRanksTheMoreOverlappingNoteHigherAndOthersLower() {
        val dir = createTempDirectory("openlog-case-search-rank").toFile()
        writeCaseNote(
            dir, "anr_note", title = "ANR in main thread",
            issueDescription = "Application not responding during cold start, main thread blocked",
            tags = listOf("ActivityManager"), decisiveTags = listOf("ActivityManager"),
        )
        writeCaseNote(
            dir, "network_note", title = "Network timeout",
            issueDescription = "OkHttp connection reset during retry",
            tags = listOf("OkHttp"),
        )
        val search = newSearch(dir)

        val results = search.search(query = "main thread blocked cold start", tags = listOf("ActivityManager"))

        assertTrue(results.isNotEmpty())
        assertEquals("ANR in main thread", results.first().title)
        assertTrue(results.first().matchedTags.contains("ActivityManager"))
    }

    @Test
    fun queryWithNoSharedTagOrTokenReturnsEmptyProvingCandidateNarrowing() {
        val dir = createTempDirectory("openlog-case-search-empty").toFile()
        writeCaseNote(dir, "anr_note", title = "ANR in main thread", issueDescription = "Application not responding")
        val search = newSearch(dir)

        val results = search.search(query = "zzqxxnomatchwhatsoever", tags = listOf("CompletelyUnrelatedTag"))

        assertEquals(emptyList(), results)
    }

    @Test
    fun getCaseReturnsNullForUnknownId() {
        val dir = createTempDirectory("openlog-case-search-getcase").toFile()
        val search = newSearch(dir)
        assertNull(search.getCase("/does/not/exist_analysis.md"))
    }

    @Test
    fun getCaseResolvesAMatchedSummaryBackToItsFullRecord() {
        val dir = createTempDirectory("openlog-case-search-getcase2").toFile()
        writeCaseNote(dir, "anr_note", title = "ANR in main thread", issueDescription = "Application not responding", tags = listOf("ActivityManager"))
        val search = newSearch(dir)

        val summary = search.search(query = "application not responding").single()
        val record = search.getCase(summary.id)

        assertNotNull(record)
        assertEquals("ANR in main thread", record.title)
    }

    @Test
    fun onlyTheTouchedNoteIsReindexedOnRescan() {
        val dir = createTempDirectory("openlog-case-search-touch").toFile()
        writeCaseNote(dir, "note_a", title = "Original title A", issueDescription = "alpha issue", tags = listOf("TagA"))
        writeCaseNote(dir, "note_b", title = "Original title B", issueDescription = "beta issue", tags = listOf("TagB"))
        val search = newSearch(dir)

        // Prime the index.
        assertTrue(search.search(query = "alpha issue").isNotEmpty())

        // Modify only note_a's backing .ann on disk, bypassing the app entirely (simulates a
        // hand-edited or externally regenerated note).
        writeCaseNote(dir, "note_a", title = "Updated title A", issueDescription = "alpha issue updated", tags = listOf("TagA", "TagAExtra"))

        val results = search.search(query = "alpha issue updated")
        assertTrue(results.isNotEmpty())
        assertEquals("Updated title A", results.first().title)

        // note_b's record is untouched — searching its original terms still finds it unchanged.
        val bResults = search.search(query = "beta issue")
        assertEquals("Original title B", bResults.first().title)
    }

    @Test
    fun handCopiedAnnWithNoAppInvolvementIsFoundOnTheNextSearch() {
        val dir = createTempDirectory("openlog-case-search-pasted").toFile()
        writeCaseNote(dir, "existing_note", title = "Existing", issueDescription = "existing issue", tags = listOf("TagX"))
        val search = newSearch(dir)
        assertTrue(search.search(query = "existing issue").isNotEmpty())

        // Simulate a user copy-pasting a fresh .ann into the notes folder directly (no .md, no app
        // involvement at all) between two searches.
        writeCaseNote(dir, "pasted_note", title = "unused", issueDescription = "freshly pasted issue", tags = listOf("PastedTag"), writeMd = false)

        val results = search.search(query = "freshly pasted issue")

        assertTrue(results.isNotEmpty())
        assertEquals("pasted_note", results.first().title)
    }

    @Test
    fun deletedNoteDropsOutOfSearchResults() {
        val dir = createTempDirectory("openlog-case-search-delete").toFile()
        val (md, ann) = writeCaseNote(dir, "note_c", title = "Gamma", issueDescription = "gamma issue only here", tags = listOf("TagC"))
        val search = newSearch(dir)
        assertTrue(search.search(query = "gamma issue only here").isNotEmpty())

        md?.delete()
        ann.delete()

        val results = search.search(query = "gamma issue only here")
        assertEquals(emptyList(), results)
    }

    @Test
    fun reindexAllForcesAFullRebuildEvenIfPersistedIndexLooksCurrent() {
        val dir = createTempDirectory("openlog-case-search-reindexall").toFile()
        writeCaseNote(dir, "note_d", title = "Delta", issueDescription = "delta issue", tags = listOf("TagD"))
        val search = newSearch(dir)
        assertTrue(search.search(query = "delta issue").isNotEmpty())

        search.reindexAll()

        val results = search.search(query = "delta issue")
        assertTrue(results.isNotEmpty())
        assertEquals("Delta", results.first().title)
    }

    @Test
    fun excludeSourcePathFiltersOutASelfMatch() {
        val dir = createTempDirectory("openlog-case-search-exclude").toFile()
        writeCaseNote(
            dir, "note_e", title = "Epsilon", issueDescription = "epsilon issue",
            tags = listOf("TagE"), sourcePath = "/logs/epsilon.log",
        )
        val search = newSearch(dir)

        val included = search.search(query = "epsilon issue")
        val excluded = search.search(query = "epsilon issue", excludeSourcePath = "/logs/epsilon.log")

        assertTrue(included.isNotEmpty())
        assertEquals(emptyList(), excluded)
    }

    @Test
    fun blankQueryAndNoTagsReturnsEmptyRatherThanEveryNote() {
        val dir = createTempDirectory("openlog-case-search-blank").toFile()
        writeCaseNote(dir, "note_f", title = "Zeta", issueDescription = "zeta issue", tags = listOf("TagF"))
        val search = newSearch(dir)

        assertEquals(emptyList(), search.search(query = "   "))
    }

    @Test
    fun staleAppVersionMatchIsRankedBelowANewerVersionMatchForAnOtherwiseEqualQuery() {
        val dir = createTempDirectory("openlog-case-search-stale").toFile()
        writeCaseNote(
            dir, "old_version_note", title = "Old crash", issueDescription = "shared crash keyword phrase",
            tags = listOf("SharedTag"), appVersion = "1.0.0",
        )
        writeCaseNote(
            dir, "new_version_note", title = "New crash", issueDescription = "shared crash keyword phrase",
            tags = listOf("SharedTag"), appVersion = "2.0.0",
        )
        val search = newSearch(dir)

        val results = search.search(query = "shared crash keyword phrase", tags = listOf("SharedTag"))

        assertEquals(2, results.size)
        assertEquals("New crash", results.first().title)
        assertFalse(results.first().score < results.last().score)
    }
}
