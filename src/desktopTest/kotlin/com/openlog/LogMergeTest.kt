package com.openlog

import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.utils.MergeSourceFile
import com.openlog.utils.mergeLogs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogMergeTest {
    @Test
    fun interleavesTwoSourcesByTimeOfDayAndRenumbersIds() {
        val main = MergeSourceFile(
            "main",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "App", "main first"),
                LogEntry(2, "10:00:02.000", LogLevel.I, "App", "main second"),
            ),
        )
        val system = MergeSourceFile(
            "system",
            listOf(
                LogEntry(1, "10:00:01.000", LogLevel.I, "Sys", "system first"),
                LogEntry(2, "10:00:03.000", LogLevel.I, "Sys", "system second"),
            ),
        )

        val merged = mergeLogs(listOf(main, system))

        assertEquals(listOf("main first", "system first", "main second", "system second"), merged.map { it.msg })
        assertEquals(listOf(1, 2, 3, 4), merged.map { it.id })
        assertEquals(listOf("main", "system", "main", "system"), merged.map { it.sourceTag })
    }

    @Test
    fun tagsEveryMergedEntryWithItsSourceRegardlessOfOrder() {
        val a = MergeSourceFile("a", listOf(LogEntry(1, "10:00:05.000", LogLevel.I, "Tag", "later")))
        val b = MergeSourceFile("b", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "Tag", "earlier")))

        val merged = mergeLogs(listOf(a, b))

        assertEquals(listOf("earlier", "later"), merged.map { it.msg })
        assertEquals(listOf("b", "a"), merged.map { it.sourceTag })
    }

    @Test
    fun unparseableTimestampsCarryForwardTheLastKnownGoodTimeFromTheirOwnSource() {
        val main = MergeSourceFile(
            "main",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "App", "known"),
                // Brief-format / RAW entries have no timestamp at all.
                LogEntry(2, "", LogLevel.I, "RAW", "unparseable right after known"),
            ),
        )
        val system = MergeSourceFile(
            "system",
            listOf(LogEntry(1, "10:00:00.500", LogLevel.I, "Sys", "between them")),
        )

        val merged = mergeLogs(listOf(main, system))

        // "unparseable right after known" carries the 10:00:00.000 timestamp from its own source,
        // so it sorts immediately after "known" and before system's 10:00:00.500 entry, keeping
        // its original relative position next to where it actually occurred.
        assertEquals(listOf("known", "unparseable right after known", "between them"), merged.map { it.msg })
    }

    @Test
    fun entriesBeforeAnyKnownGoodTimestampInTheirSourceSortToTheFront() {
        val main = MergeSourceFile(
            "main",
            listOf(
                LogEntry(1, "", LogLevel.I, "RAW", "no timestamp yet"),
                LogEntry(2, "10:00:00.000", LogLevel.I, "App", "first known"),
            ),
        )
        val system = MergeSourceFile(
            "system",
            listOf(LogEntry(1, "09:59:59.000", LogLevel.I, "Sys", "earliest known")),
        )

        val merged = mergeLogs(listOf(main, system))

        assertEquals(listOf("no timestamp yet", "earliest known", "first known"), merged.map { it.msg })
    }

    @Test
    fun sourceLogEntriesKeepSourceTagNullUnlessMerged() {
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi")

        assertNull(entry.sourceTag)
    }
}
