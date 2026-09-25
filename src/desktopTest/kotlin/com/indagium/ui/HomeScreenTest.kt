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
    fun filterRecentEntriesDefaultFiltersMatchExisting2ArgBehavior() {
        val entries = listOf(
            entry("/a/one.log", name = "one.log", kind = RecentKind.LOG),
            entry("/b/two.zip", name = "two.zip", kind = RecentKind.ARCHIVE),
        )
        assertEquals(entries, filterRecentEntries(entries, ""))
    }

    @Test
    fun filterRecentEntriesTypeFilterKeepsOnlyMatchingKind() {
        val entries = listOf(
            entry("/a/one.log", kind = RecentKind.LOG),
            entry("/b/two.zip", kind = RecentKind.ARCHIVE),
            entry("/c/three.ann", kind = RecentKind.NOTES),
        )
        assertEquals(
            listOf(entries[0]),
            filterRecentEntries(entries, "", RecentFilters(type = RecentTypeFilter.LOGS)),
        )
        assertEquals(
            listOf(entries[1]),
            filterRecentEntries(entries, "", RecentFilters(type = RecentTypeFilter.ARCHIVES)),
        )
        assertEquals(
            listOf(entries[2]),
            filterRecentEntries(entries, "", RecentFilters(type = RecentTypeFilter.NOTES)),
        )
    }

    @Test
    fun filterRecentEntriesModifiedFilterUsesLocalCalendarDayForToday() {
        val now = 10L * 24 * 60 * 60 * 1000 + 12 * 60 * 60 * 1000 // some day, mid-afternoon UTC-ish
        val today = entry("/a.log", lastModifiedMs = now - 60_000L)
        val startOfToday = java.time.Instant.ofEpochMilli(now)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val yesterday = entry("/b.log", lastModifiedMs = startOfToday - 60_000L)
        val sixDaysAgo = entry("/c.log", lastModifiedMs = now - 6L * 24 * 60 * 60 * 1000)
        val twentyDaysAgo = entry("/d.log", lastModifiedMs = now - 20L * 24 * 60 * 60 * 1000)
        val missing = entry("/e.log", lastModifiedMs = null, sizeBytes = null, exists = false)
        val entries = listOf(today, yesterday, sixDaysAgo, twentyDaysAgo, missing)

        assertEquals(
            listOf(today),
            filterRecentEntries(entries, "", RecentFilters(modified = RecentModifiedFilter.TODAY), now),
        )
        assertEquals(
            listOf(today, yesterday, sixDaysAgo),
            filterRecentEntries(entries, "", RecentFilters(modified = RecentModifiedFilter.LAST_7_DAYS), now),
        )
        assertEquals(
            listOf(today, yesterday, sixDaysAgo, twentyDaysAgo),
            filterRecentEntries(entries, "", RecentFilters(modified = RecentModifiedFilter.LAST_30_DAYS), now),
        )
        // Missing files are excluded by a non-default Modified filter.
        assertFalse(
            filterRecentEntries(entries, "", RecentFilters(modified = RecentModifiedFilter.LAST_30_DAYS), now)
                .contains(missing),
        )
        // ...but included when Modified stays at its default.
        assertTrue(filterRecentEntries(entries, "", RecentFilters(), now).contains(missing))
    }

    @Test
    fun filterRecentEntriesSizeFilterBoundariesAndMissingExclusion() {
        val now = 1_000_000L
        val small = entry("/a.log", sizeBytes = 5L * 1024 * 1024) // 5 MB
        val mid = entry("/b.log", sizeBytes = 50L * 1024 * 1024) // 50 MB
        val big = entry("/c.log", sizeBytes = 150L * 1024 * 1024) // 150 MB
        val missing = entry("/d.log", sizeBytes = null, lastModifiedMs = null, exists = false)
        val entries = listOf(small, mid, big, missing)

        assertEquals(listOf(small), filterRecentEntries(entries, "", RecentFilters(size = RecentSizeFilter.UNDER_10_MB), now))
        assertEquals(listOf(mid), filterRecentEntries(entries, "", RecentFilters(size = RecentSizeFilter.BETWEEN_10_AND_100_MB), now))
        assertEquals(listOf(big), filterRecentEntries(entries, "", RecentFilters(size = RecentSizeFilter.OVER_100_MB), now))
        assertTrue(filterRecentEntries(entries, "", RecentFilters(), now).contains(missing))
    }

    @Test
    fun filterRecentEntriesSortOptions() {
        val now = 1_000_000L
        val a = entry("/z_first_opened.log", name = "z_first_opened.log", sizeBytes = 10L, lastModifiedMs = 500L)
        val b = entry("/a_second_opened.log", name = "a_second_opened.log", sizeBytes = 30L, lastModifiedMs = 900L)
        val missing = entry("/missing.log", name = "missing.log", sizeBytes = null, lastModifiedMs = null, exists = false)
        val entries = listOf(a, b, missing) // "recently opened" order == this list order

        assertEquals(entries, filterRecentEntries(entries, "", RecentFilters(sort = RecentSortOption.RECENTLY_OPENED), now))
        assertEquals(
            listOf(b, a, missing),
            filterRecentEntries(entries, "", RecentFilters(sort = RecentSortOption.MODIFIED_NEWEST), now),
        )
        assertEquals(
            listOf(b, missing, a),
            filterRecentEntries(entries, "", RecentFilters(sort = RecentSortOption.NAME_AZ), now),
        )
        assertEquals(
            listOf(b, a, missing),
            filterRecentEntries(entries, "", RecentFilters(sort = RecentSortOption.SIZE_LARGEST), now),
        )
    }

    @Test
    fun homeRecentEmptyMessageMentionsQueryOnlyWhenOneIsActive() {
        assertEquals("Nothing matches these filters", homeRecentEmptyMessage("", RecentFilters(type = RecentTypeFilter.LOGS)))
        assertEquals("Nothing matches \"foo\"", homeRecentEmptyMessage("foo", RecentFilters()))
        assertEquals(
            "Nothing matches \"foo\" with these filters",
            homeRecentEmptyMessage("foo", RecentFilters(type = RecentTypeFilter.LOGS)),
        )
    }

    @Test
    fun recentKindIconMapsEveryKindDistinctly() {
        val icons = RecentKind.entries.map(::recentKindIcon)
        assertEquals(icons.toSet().size, icons.size, "every RecentKind should map to a distinct icon")
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
