package com.indagium

import com.indagium.model.LogTab
import com.indagium.ui.mkTab
import com.indagium.ui.plainNoteSourcePath
import com.indagium.ui.tabShellFromToken
import com.indagium.ui.tabToken
import com.indagium.utils.ZipLogCandidate
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether a tab is a plain file for a shared note's closed-source bookkeeping is decided from what the tab knows, not from the disk:
 * the callers run under the state lock. A `!` in the path is part of a plain file's name unless the tab is an archive entry.
 */
class PlainNoteSourcePathTest {
    private fun tab(path: String): LogTab = mkTab("t", "t.log", emptyList()).copy(sourcePath = path)

    private fun restored(tab: LogTab): LogTab = assertNotNull(tab.tabToken().tabShellFromToken()).tab

    @Test
    fun aPlainFileWhoseNameHasABangIsPlainWhetherOrNotItIsOnDisk() {
        val missing = File(createTempDirectory("plain-bang-missing").toFile(), "a!b.log").absolutePath
        assertEquals(missing, tab(missing).plainNoteSourcePath(), "no look at the disk: a missing file is as plain as any")

        val dir = createTempDirectory("plain-bang").toFile()
        try {
            val existing = File(dir, "x!y.log").apply { writeText("line\n") }.absolutePath
            val back = restored(tab(existing))
            assertFalse(back.legacyArchiveEntry)
            assertEquals(existing, back.plainNoteSourcePath())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aLegacyRestoredArchiveEntryWithoutCandidateMetadataIsNotPlain() {
        val dir = createTempDirectory("plain-legacy-archive").toFile()
        try {
            val archive = File(dir, "report.zip").apply { writeText("not read here") }
            val back = restored(tab("${archive.absolutePath}!logs/main.txt"))

            assertNull(back.archiveCandidate, "an autosave from before the candidate field")
            assertTrue(back.legacyArchiveEntry, "the restore noted what it is")
            assertNull(back.plainNoteSourcePath())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun anArchiveEntryWithItsCandidateIsNotPlainAndNeitherIsACaptureOrAPathlessLog() {
        val dir = createTempDirectory("plain-archive-entry").toFile()
        try {
            val path = "${File(dir, "report.zip").absolutePath}!logs/main.txt"
            val withCandidate = tab(path).copy(archiveCandidate = ZipLogCandidate("logs/main.txt", "main.txt", 42))
            assertNull(withCandidate.plainNoteSourcePath())
            assertFalse(restored(withCandidate).legacyArchiveEntry, "its candidate comes back with it")
            assertNull(restored(withCandidate).plainNoteSourcePath())

            assertNull(tab(path).copy(captureArchivePath = File(dir, "cap.zip").absolutePath).plainNoteSourcePath())
            assertNull(mkTab("p", "p.log", emptyList()).plainNoteSourcePath())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aPathWithoutABangIsPlainAsBefore() {
        val path = File("somewhere/plain.log").absolutePath
        assertEquals(path, tab(path).plainNoteSourcePath())
    }
}
