package com.indagium.storage

import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogLevel
import com.indagium.storage.projection.DisplayProjection
import com.indagium.storage.projection.HeaderDescriptor
import com.indagium.storage.projection.ProjectionEvent
import com.indagium.storage.projection.ProjectionGeneration
import com.indagium.storage.projection.ProjectionPageReader
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class StorageSelectionResolverTest {
    @Test
    fun resolvesDisplayedPoliciesRangesDuplicatesAndHiddenInclusions(): Unit = runBlocking {
        val rows = listOf(10, 20, 30, 40).map { LogEntry(it, "", LogLevel.I, "tag", "row") }
        val sourceId = SourceId("selection")
        val source = LegacyInMemorySource(sourceId, LogFormat.LOGCAT, rows, maxPageRows = 2)
        val lookup = source.snapshot()
        val generation = ProjectionGeneration(lookup.version, "query", "projection", 1)
        val header = HeaderDescriptor("group", "stack", 1, -1, -1, 0, true, longArrayOf(), EntryId(20))
        val projection = DisplayProjection.build(generation, listOf(
            ProjectionEvent.Header(header), ProjectionEvent.Row(1, EntryId(20)),
            ProjectionEvent.Row(0, EntryId(10)), ProjectionEvent.Row(2, EntryId(30)),
        ), 2)
        val reader = ProjectionPageReader(projection, source.snapshot(), 2)
        val current = SelectionGeneration(sourceId, lookup.version, "query", "projection", 1, 1)
        try {
            val all = StorageSelection(current, SelectionBase.AllDisplayedRows,
                inclusions = setOf(EntryId(40)), exclusions = setOf(EntryId(20)))
            assertContentEquals(intArrayOf(10, 30, 40), resolveSelectionForCopy(all, current, projection, reader, lookup, 2))
            val range = StorageSelection(current, SelectionBase.DisplayRange(
                SelectionOccurrence(EntryId(20), 0, 0), SelectionOccurrence(EntryId(10), 2, 0),
            ))
            assertContentEquals(intArrayOf(10, 20), resolveSelectionForCopy(range, current, projection, reader, lookup, 2))
            val duplicate = StorageSelection(current, SelectionBase.DisplayRange(
                SelectionOccurrence(EntryId(20), 1, 1), SelectionOccurrence(EntryId(30), 3, 0),
            ))
            assertContentEquals(intArrayOf(10, 20, 30), resolveSelectionForCopy(duplicate, current, projection, reader, lookup, 2))
            val stale = StorageSelection(current, SelectionBase.DisplayRange(
                SelectionOccurrence(EntryId(20), 1, 0), SelectionOccurrence(EntryId(30), 3, 0),
            ))
            assertFailsWith<StorageFailure.InvalidRequest> { resolveSelectionForCopy(stale, current, projection, reader, lookup, 2) }
            assertFailsWith<StorageFailure.InvalidRequest> {
                resolveSelectionForCopy(all, current.copy(foldGeneration = 2), projection, reader, lookup, 2)
            }
            assertFailsWith<StorageFailure.InvalidRequest> { resolveSelectionForCopy(all, current, projection, reader, lookup, 2, maxSelectedIds = 2) }
            val changed = object : ReadSnapshot by lookup {
                override suspend fun rows(start: RowOrdinal, limit: Int): StoragePage<LegacyDecodedRow> {
                    if (start.value == rowCount) error("source changed")
                    return lookup.rows(start, limit)
                }
            }
            assertFailsWith<IllegalStateException> { resolveSelectionForCopy(all, current, projection, reader, changed, 2) }
        } finally {
            reader.close()
            lookup.close()
            source.close()
        }
    }
}
