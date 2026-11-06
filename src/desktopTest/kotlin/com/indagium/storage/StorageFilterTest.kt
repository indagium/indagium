package com.indagium.storage

import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogLevel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class StorageFilterTest {
    @Test
    fun keywordAndPidSelectorsUseExistingFilterSemantics() = runBlocking {
        val entries = listOf(
            LogEntry(4, "", LogLevel.I, "alpha", "Denied access", pid = 101),
            LogEntry(9, "", LogLevel.W, "beta", "allowed", pid = 202),
            LogEntry(20, "", LogLevel.E, "alpha", "denied again", pid = 202),
        )
        val source = LegacyInMemorySource(SourceId("filter-fixture"), LogFormat.LOGCAT, entries)
        try {
            val keyword = StorageQuery(source.snapshot(), 2, 2)
            try {
                assertEquals(2L, keyword.scanFilter(Filter(mode = FilterMode.KEYWORD, kwText = "denied")))
                assertEquals(listOf(4, 20), keyword.rows(0, 2).items.map { it.entry.id })
            } finally {
                keyword.close()
            }
            val pid = StorageQuery(source.snapshot(), 2, 2)
            try {
                assertEquals(2L, pid.scanFilter(Filter(pidTidFilter = "demo"), processNames = mapOf(202 to "demo")))
                assertEquals(listOf(9, 20), pid.rows(0, 2).items.map { it.entry.id })
            } finally {
                pid.close()
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun regexAndInvalidPatternsFollowExistingSemantics() = runBlocking {
        val source = LegacyInMemorySource(
            SourceId("regex-fixture"), LogFormat.LOGCAT,
            listOf(LogEntry(1, "", LogLevel.I, "tag", "Привіт 42"), LogEntry(2, "", LogLevel.I, "tag", "other")),
        )
        try {
            val matches = StorageQuery(source.snapshot(), 2, 2)
            try {
                assertEquals(1L, matches.scanFilter(Filter(mode = FilterMode.KEYWORD, kwText = "Привіт.*42", kwRegex = true)))
                assertEquals(listOf(1), matches.rows(0, 2).items.map { it.entry.id })
            } finally {
                matches.close()
            }
            val invalid = StorageQuery(source.snapshot(), 2, 2)
            try {
                assertEquals(0L, invalid.scanFilter(Filter(mode = FilterMode.KEYWORD, kwText = "[", kwRegex = true)))
            } finally {
                invalid.close()
            }
        } finally {
            source.close()
        }
    }
}
