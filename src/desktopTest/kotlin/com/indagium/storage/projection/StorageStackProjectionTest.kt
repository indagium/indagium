package com.indagium.storage.projection

import com.indagium.model.LogAnalysis
import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogItem
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.storage.LegacyInMemorySource
import com.indagium.storage.SourceId
import com.indagium.storage.StorageFailure
import com.indagium.storage.StorageQuery
import com.indagium.storage.StorageStackGroups
import com.indagium.storage.scanStorageStackGroups
import com.indagium.utils.CompactStackGroup
import com.indagium.utils.computeItems
import com.indagium.utils.computeStackTraceGroups
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StorageStackProjectionTest {
    private fun row(id: Int, message: String, pid: Int = 1) =
        LogEntry(id, "", LogLevel.E, "app", message, pid = pid, tid = 1)

    private suspend fun events(projection: DisplayProjection): List<DisplayEvent> {
        val out = mutableListOf<DisplayEvent>()
        var start = 0L
        while (true) {
            val page = projection.page(start, 2)
            out.addAll(page.items)
            if (page.endReached) return out
            start += page.items.size
        }
    }

    private fun labels(events: List<DisplayEvent>): List<String> = events.map {
        when (it) {
            is DisplayEvent.Row -> "row:${it.id.value}"
            is DisplayEvent.Header -> "header:${it.descriptor.anchorId?.value}:${it.descriptor.count}"
        }
    }

    @Test
    fun stackProjectionPreservesFilteredAndExpandedOrder(): Unit = runBlocking {
        val rows = listOf(
            row(1, "FATAL EXCEPTION: main"), row(2, "other", 2),
            row(3, "java.lang.IllegalStateException: bad"), row(4, "    at app.Example.go(A.kt:1)"),
            row(5, "done"),
        )
        val source = LegacyInMemorySource(SourceId("synthetic"), LogFormat.LOGCAT, rows, maxPageRows = 2)
        val analysis = source.snapshot()
        try {
            val groups = scanStorageStackGroups(analysis, LogFormat.LOGCAT, 2)
            for ((expanded, expected) in listOf(
                emptySet<String>() to listOf("header:1:2", "row:2", "row:5"),
                setOf("st_1") to listOf("header:1:2", "row:3", "row:4", "row:2", "row:5"),
            )) {
                val query = StorageQuery(source.snapshot(), 2, 2)
                try {
                    val count = query.scan { true }
                    val generation = ProjectionGeneration(query.version, query.queryId, "p", 1)
                    val projection = buildStorageStackProjection(query, groups, generation, expanded, count, 2, 2, false, false)
                    val actual = events(projection)
                    assertEquals(expected, labels(actual))
                    val tab = LogTab("tab", "generated", rows, rows.associateBy { it.id }, expanded = expanded,
                        analysis = LogAnalysis(pending = false, stackTraceGroups = computeStackTraceGroups(rows)))
                    val legacy = computeItems(tab, false).map {
                        when (it) {
                            is LogItem.Row -> "row:${it.entry.id}"
                            is LogItem.StackTraceHeader -> "header:${it.entry.id}:${it.count}"
                            else -> error("Unexpected group")
                        }
                    }
                    assertEquals(legacy, labels(actual))
                } finally {
                    query.close()
                }
            }
            val filtered = StorageQuery(source.snapshot(), 2, 2)
            try {
                val count = filtered.scan { it.id != 3 }
                val generation = ProjectionGeneration(filtered.version, filtered.queryId, "filtered", 2)
                val projection = buildStorageStackProjection(filtered, groups, generation, setOf("st_1"), count, 2, 2, false, false)
                assertEquals(listOf("header:1:1", "row:4", "row:2", "row:5"), labels(events(projection)))
                assertFailsWith<StorageFailure.InvalidRequest> {
                    buildStorageStackProjection(filtered, groups, generation, emptySet(), count, 2, 2, true, false)
                }
            } finally {
                filtered.close()
            }
            val withoutRoot = StorageQuery(source.snapshot(), 2, 2)
            try {
                val count = withoutRoot.scan { it.id != 1 }
                val generation = ProjectionGeneration(withoutRoot.version, withoutRoot.queryId, "no-root", 3)
                val projection = buildStorageStackProjection(withoutRoot, groups, generation, emptySet(), count, 2, 2, false, false)
                assertEquals(listOf("row:2", "row:3", "row:4", "row:5"), labels(events(projection)))
            } finally {
                withoutRoot.close()
            }
        } finally {
            analysis.close()
            source.close()
        }
    }

    @Test
    fun laterHeaderTakesPrecedenceOverEarlierMembership() = runBlocking {
        val rows = (1..4).map { row(it, "row $it") }
        val source = LegacyInMemorySource(SourceId("synthetic"), LogFormat.LOGCAT, rows, maxPageRows = 2)
        val query = StorageQuery(source.snapshot(), 2, 2)
        try {
            val count = query.scan { true }
            val groups = StorageStackGroups(query.version, listOf(
                CompactStackGroup(1, false, "one", intArrayOf(2)),
                CompactStackGroup(2, false, "two", intArrayOf(3)),
            ))
            val generation = ProjectionGeneration(query.version, query.queryId, "overlap", 1)
            val projection = buildStorageStackProjection(query, groups, generation, setOf("st_1", "st_2"), count, 2, 2, false, false)
            assertEquals(listOf("header:1:1", "row:2", "header:2:1", "row:3", "row:4"), labels(events(projection)))
        } finally {
            query.close()
            source.close()
        }
    }
}
