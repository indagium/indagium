package com.openlog

import com.openlog.ui.DesktopStorage
import com.openlog.ui.MIGRATION_MAX_BYTES
import com.openlog.ui.MigrationOutcome
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Stage 1 of the openLog2 -> Indagium rename: DesktopStorage.migrateAppDataDir copies the entries
// worth keeping from the old app-data dir into the new one, once. These tests exercise the copy
// policy table and the hardening rules directly with two temp dirs, no environment mocking.
class AppDataDirMigrationTest {
    private fun newRoot(name: String): File = createTempDirectory(name).toFile()

    @Test
    fun legacyAppDataDirReturnsThreeOldPlatformLiterals() {
        val mac = DesktopStorage.legacyAppDataDir("Mac OS X", "/Users/me") { null }
        val windows = DesktopStorage.legacyAppDataDir("Windows 11", "C:/Users/me") { key ->
            if (key == "APPDATA") "C:/Users/me/AppData/Roaming" else null
        }
        val linux = DesktopStorage.legacyAppDataDir("Linux", "/home/me") { key ->
            if (key == "XDG_STATE_HOME") "/home/me/.local/state" else null
        }

        assertEquals(File("/Users/me/Library/Application Support/openLog2"), mac)
        assertEquals(File("C:/Users/me/AppData/Roaming", "openLog2"), windows)
        assertEquals(File("/home/me/.local/state", "openLog2"), linux)
    }

    @Test
    fun copiesAutosaveCacheAndNestedNotes() {
        val root = newRoot("indagium-migrate-basic")
        val oldDir = File(root, "openLog2").apply { mkdirs() }
        File(oldDir, "autosave.cache").writeText("openLog2-cache-v1|tab1")
        val nested = File(oldDir, "notes/sub").apply { mkdirs() }
        File(nested, "deep.md").writeText("nested note content")
        val newDir = File(root, "Indagium")

        val outcome = DesktopStorage.migrateAppDataDir(newDir, oldDir)

        assertTrue(outcome is MigrationOutcome.Migrated)
        assertEquals("openLog2-cache-v1|tab1", File(newDir, "autosave.cache").readText())
        assertEquals("nested note content", File(newDir, "notes/sub/deep.md").readText())
        assertTrue(File(newDir, ".migrated-from-openLog2").exists())
    }

    @Test
    fun copiesRemainingUserContentEntries() {
        val root = newRoot("indagium-migrate-user-content")
        val oldDir = File(root, "openLog2").apply { mkdirs() }
        File(oldDir, "custom-ai-commands").apply { mkdirs() }
            .let { File(it, "cmd.json").writeText("{}") }
        File(oldDir, "filter-backups").apply { mkdirs() }
            .let { File(it, "backup1.json").writeText("[]") }
        File(oldDir, "source-index").writeText("source-index-bytes")
        File(oldDir, "case-index").writeText("case-index-bytes")
        val newDir = File(root, "Indagium")

        val outcome = DesktopStorage.migrateAppDataDir(newDir, oldDir)

        assertTrue(outcome is MigrationOutcome.Migrated)
        assertEquals("{}", File(newDir, "custom-ai-commands/cmd.json").readText())
        assertEquals("[]", File(newDir, "filter-backups/backup1.json").readText())
        assertEquals("source-index-bytes", File(newDir, "source-index").readText())
        assertEquals("case-index-bytes", File(newDir, "case-index").readText())
    }

    @Test
    fun skipsArchiveCacheAndPerInstallFiles() {
        val root = newRoot("indagium-migrate-skip")
        val oldDir = File(root, "openLog2").apply { mkdirs() }
        File(oldDir, "archive-cache").apply { mkdirs() }
            .let { File(it, "extracted.bin").writeText("derived, rebuildable") }
        File(oldDir, "control-token").writeText("per-install-secret")
        File(oldDir, "single-instance.lock").writeText("stale")
        File(oldDir, "single-instance.port").writeText("12345")
        File(oldDir, "openlog-debug.log").writeText("old log")
        File(oldDir, "autosave.cache").writeText("kept")
        val newDir = File(root, "Indagium")

        val outcome = DesktopStorage.migrateAppDataDir(newDir, oldDir)

        assertTrue(outcome is MigrationOutcome.Migrated)
        assertTrue(File(newDir, "autosave.cache").exists())
        assertFalse(File(newDir, "archive-cache").exists())
        assertFalse(File(newDir, "control-token").exists())
        assertFalse(File(newDir, "single-instance.lock").exists())
        assertFalse(File(newDir, "single-instance.port").exists())
        assertFalse(File(newDir, "openlog-debug.log").exists())
    }

    @Test
    fun voiceModelsArriveWithCorrectContent() {
        val root = newRoot("indagium-migrate-voice")
        val oldDir = File(root, "openLog2").apply { mkdirs() }
        val voiceDir = File(oldDir, "voice-models").apply { mkdirs() }
        File(voiceDir, "ggml-base.bin").writeText("pretend-whisper-model-bytes")
        val newDir = File(root, "Indagium")

        val outcome = DesktopStorage.migrateAppDataDir(newDir, oldDir)

        assertTrue(outcome is MigrationOutcome.Migrated)
        // Content is what matters (hardlinked or copied are both acceptable outcomes per the
        // copy policy) — not whether the destination happens to be a distinct inode.
        assertEquals("pretend-whisper-model-bytes", File(newDir, "voice-models/ggml-base.bin").readText())
    }

    @Test
    fun markerMakesSecondRunANoOpEvenIfOldDirChangedInBetween() {
        val root = newRoot("indagium-migrate-marker")
        val oldDir = File(root, "openLog2").apply { mkdirs() }
        File(oldDir, "autosave.cache").writeText("first")
        val newDir = File(root, "Indagium")

        val first = DesktopStorage.migrateAppDataDir(newDir, oldDir)
        assertTrue(first is MigrationOutcome.Migrated)
        assertEquals("first", File(newDir, "autosave.cache").readText())

        File(oldDir, "autosave.cache").writeText("second-should-be-ignored")
        File(oldDir, "added-later.txt").writeText("should never arrive")

        val second = DesktopStorage.migrateAppDataDir(newDir, oldDir)

        assertEquals(MigrationOutcome.AlreadyDone, second)
        assertEquals("first", File(newDir, "autosave.cache").readText())
        assertFalse(File(newDir, "added-later.txt").exists())
    }

    @Test
    fun noLegacyDirWritesMarkerAndReportsNoLegacyData() {
        val root = newRoot("indagium-migrate-none")
        val oldDir = File(root, "openLog2") // deliberately never created
        val newDir = File(root, "Indagium")

        val outcome = DesktopStorage.migrateAppDataDir(newDir, oldDir)

        assertEquals(MigrationOutcome.NoLegacyData, outcome)
        assertTrue(File(newDir, ".migrated-from-openLog2").exists())
    }

    @Test
    fun symlinkUnderNotesIsNotFollowed() {
        val root = newRoot("indagium-migrate-symlink")
        val oldDir = File(root, "openLog2").apply { mkdirs() }
        val notesDir = File(oldDir, "notes").apply { mkdirs() }
        File(notesDir, "real.md").writeText("kept")
        // A symlink back at its own parent would be an infinite loop if the walk followed it.
        Files.createSymbolicLink(File(notesDir, "loop").toPath(), notesDir.toPath())
        val newDir = File(root, "Indagium")

        val outcome = DesktopStorage.migrateAppDataDir(newDir, oldDir)

        assertTrue(outcome is MigrationOutcome.Migrated)
        assertEquals("kept", File(newDir, "notes/real.md").readText())
        assertFalse(File(newDir, "notes/loop").exists())
    }

    @Test
    fun oversizedByteCopiedEntryIsSkippedButOthersStillArrive() {
        val root = newRoot("indagium-migrate-cap-copy")
        val oldDir = File(root, "openLog2").apply { mkdirs() }
        File(oldDir, "autosave.cache").writeText("kept-session")
        File(oldDir, "notes").apply { mkdirs() }.let { File(it, "n.md").writeText("kept-note") }
        // A sparse file: its reported length exceeds the cap without actually consuming that much
        // disk space or copy time, so the test stays fast. case-index is COPY-mode (never
        // hardlinked), so it is the entry the cap should actually trip on.
        RandomAccessFile(File(oldDir, "case-index"), "rw").use { it.setLength(MIGRATION_MAX_BYTES + 1) }
        val newDir = File(root, "Indagium")

        val outcome = DesktopStorage.migrateAppDataDir(newDir, oldDir)

        assertTrue(outcome is MigrationOutcome.Migrated)
        assertEquals(listOf("case-index"), outcome.skipped)
        // The oversized entry did not cost the user their session or notes.
        assertEquals("kept-session", File(newDir, "autosave.cache").readText())
        assertEquals("kept-note", File(newDir, "notes/n.md").readText())
        assertFalse(File(newDir, "case-index").exists())
        assertTrue(File(newDir, ".migrated-from-openLog2").exists())
    }

    @Test
    fun oversizedHardlinkedVoiceModelsDoesNotTripTheCap() {
        val root = newRoot("indagium-migrate-cap-hardlink")
        val oldDir = File(root, "openLog2").apply { mkdirs() }
        File(oldDir, "autosave.cache").writeText("kept-session")
        val voiceDir = File(oldDir, "voice-models").apply { mkdirs() }
        val bigModel = File(voiceDir, "ggml-large.bin")
        // Same trick: a sparse file logically bigger than the whole cap by itself. Because
        // voice-models is HARDLINK_OR_COPY and same-volume linking is expected to succeed here,
        // it must never be charged against MIGRATION_MAX_BYTES.
        RandomAccessFile(bigModel, "rw").use { it.setLength(MIGRATION_MAX_BYTES + 1) }
        val newDir = File(root, "Indagium")

        val outcome = DesktopStorage.migrateAppDataDir(newDir, oldDir)

        assertTrue(outcome is MigrationOutcome.Migrated)
        assertEquals(emptyList(), outcome.skipped)
        assertEquals("kept-session", File(newDir, "autosave.cache").readText())
        assertEquals(MIGRATION_MAX_BYTES + 1, File(newDir, "voice-models/ggml-large.bin").length())
    }

    @Test
    fun failureLeavesNoMarkerAndIntactOldDir() {
        val root = newRoot("indagium-migrate-fail")
        val oldDir = File(root, "openLog2").apply { mkdirs() }
        File(oldDir, "autosave.cache").writeText("session-data")
        val newDirParent = File(root, "unwritable-parent").apply { mkdirs() }
        val newDir = File(newDirParent, "Indagium")

        // Making the parent read-only prevents staging (a sibling of newDir) from ever being
        // created, which is a permission-independent way to force the copy phase to fail.
        assertTrue(newDirParent.setWritable(false))
        try {
            val outcome = DesktopStorage.migrateAppDataDir(newDir, oldDir)

            assertTrue(outcome is MigrationOutcome.Failed)
            assertFalse(newDir.exists())
            assertEquals("session-data", File(oldDir, "autosave.cache").readText())
        } finally {
            newDirParent.setWritable(true)
        }
    }
}
