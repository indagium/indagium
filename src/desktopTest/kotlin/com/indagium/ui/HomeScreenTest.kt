package com.indagium.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Covers HomeScreen.kt's pure, Compose-free helpers — there is no Compose UI test harness in this
// project (see the plan's own note), so the composables themselves stay exercised only by the
// manual pass; everything that CAN be a plain function is one, specifically so it can be tested
// here.
class HomeScreenTest {
    @Test
    fun recentKindForNameClassifiesByExtensionCaseInsensitively() {
        assertEquals(RecentKind.LOG, recentKindForName("logcat.log"))
        assertEquals(RecentKind.LOG, recentKindForName("dump.TXT"))
        assertEquals(RecentKind.ARCHIVE, recentKindForName("bugreport.zip"))
        assertEquals(RecentKind.ARCHIVE, recentKindForName("archive.7Z"))
        assertEquals(RecentKind.ARCHIVE, recentKindForName("logs.tar.gz"))
        assertEquals(RecentKind.NOTES, recentKindForName("analysis.ann"))
        assertEquals(RecentKind.NOTES, recentKindForName("analysis.MD"))
        assertEquals(RecentKind.OTHER, recentKindForName("readme"))
        assertEquals(RecentKind.OTHER, recentKindForName("capture.indagium.json"))
    }

    private fun entry(
        path: String,
        name: String = File(path).name,
        kind: RecentKind = RecentKind.LOG,
        sizeBytes: Long? = 1024L,
        lastModifiedMs: Long? = 0L,
        exists: Boolean = true,
    ) = RecentEntry(path, name, kind, sizeBytes, lastModifiedMs, exists)

    @Test
    fun filterRecentEntriesMatchesNameOrFolderCaseInsensitively() {
        val entries = listOf(
            entry("/home/roman/logs/logcat_main.log", name = "logcat_main.log"),
            entry("/home/roman/bugreports/report.zip", name = "report.zip"),
        )

        assertEquals(entries, filterRecentEntries(entries, ""))
        assertEquals(entries, filterRecentEntries(entries, "   "))
        assertEquals(listOf(entries[0]), filterRecentEntries(entries, "LOGCAT"))
        assertEquals(listOf(entries[1]), filterRecentEntries(entries, "bugreports"))
        assertTrue(filterRecentEntries(entries, "does-not-exist").isEmpty())
    }

    @Test
    fun formatRecentMetaReportsMissingFilesWithoutSizeOrTime() {
        val missing = entry("/gone/file.log", exists = false, sizeBytes = null, lastModifiedMs = null)
        assertEquals("File not found", formatRecentMeta(missing, now = 1_000_000L))
    }

    @Test
    fun formatRecentMetaFormatsSizeAndRelativeTime() {
        // An arbitrary fixed "now" far enough from the epoch that every case below stays positive.
        val now = 10L * 24 * 60 * 60 * 1000
        val justNow = entry("/a.log", sizeBytes = 500L, lastModifiedMs = now - 1_000L)
        val minutesAgo = entry("/b.log", sizeBytes = 2_048L, lastModifiedMs = now - 5 * 60_000L)
        val hoursAgo = entry("/c.log", sizeBytes = 3L * 1024 * 1024, lastModifiedMs = now - 3 * 60 * 60_000L)
        val daysAgo = entry("/d.log", sizeBytes = 100L, lastModifiedMs = now - 2L * 24 * 60 * 60_000L)

        assertTrue(formatRecentMeta(justNow, now).endsWith("just now"))
        assertTrue(formatRecentMeta(minutesAgo, now).contains("5 min ago"))
        assertTrue(formatRecentMeta(hoursAgo, now).contains("3 hr ago"))
        assertTrue(formatRecentMeta(daysAgo, now).contains("2 d ago"))
    }

    @Test
    fun readRecentEntriesReadsRealFilesAndFlagsADeletedPath() {
        val dir = Files.createTempDirectory("home-screen-test").toFile()
        try {
            val logFile = File(dir, "session.log").apply { writeText("hello") }
            val deletedPath = File(dir, "gone.log").absolutePath

            val entries = readRecentEntries(listOf(logFile.absolutePath, deletedPath))

            val present = entries.first { it.path == logFile.absolutePath }
            assertTrue(present.exists)
            assertEquals(RecentKind.LOG, present.kind)
            assertEquals(logFile.length(), present.sizeBytes)
            assertEquals(logFile.lastModified(), present.lastModifiedMs)

            val missing = entries.first { it.path == deletedPath }
            assertFalse(missing.exists)
            assertEquals(null, missing.sizeBytes)
            assertEquals(null, missing.lastModifiedMs)
        } finally {
            dir.deleteRecursively()
        }
    }
}
