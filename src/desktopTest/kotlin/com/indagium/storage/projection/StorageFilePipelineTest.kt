package com.indagium.storage.projection

import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.LogFormat
import com.indagium.storage.IndexedFileSource
import com.indagium.storage.SourceId
import com.indagium.storage.StorageQuery
import com.indagium.storage.scanFilter
import com.indagium.storage.scanStorageStackGroups
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StorageFilePipelineTest {
    @Test
    fun generatedFileIsIndexedFilteredGroupedAndPaged() = runBlocking {
        val path = Files.createTempFile("storage-pipeline-", ".log")
        try {
            Files.writeString(path, """
                01-01 12:00:00.000 1 1 E App: FATAL EXCEPTION: main
                01-01 12:00:00.001 2 1 I Other: background
                01-01 12:00:00.002 1 1 E App: java.lang.IllegalStateException: denied
                01-01 12:00:00.003 1 1 E App:     at app.Example.go(A.kt:1)
                01-01 12:00:00.004 1 1 I App: finished
            """.trimIndent())
            val source = IndexedFileSource.open(SourceId("pipeline"), path, maxPageRows = 2)
            val analysis = source.snapshot()
            val query = StorageQuery(source.snapshot(), 2, 2)
            try {
                val groups = scanStorageStackGroups(analysis, LogFormat.LOGCAT, 2)
                val count = query.scanFilter(Filter(mode = FilterMode.KEYWORD, kwText = "App"))
                val generation = ProjectionGeneration(query.version, query.queryId, "pipeline", 1)
                val projection = buildStorageStackProjection(query, groups, generation, setOf("st_1"), count, 2, 2, false, false)
                val reader = ProjectionPageReader(projection, source.snapshot(), 2)
                try {
                    val first = reader.page(0, 2)
                    assertEquals(listOf(1, 3), first.items.map {
                        when (it) {
                            is DecodedDisplayEvent.Header -> it.anchor.entry.id
                            is DecodedDisplayEvent.Row -> it.row.entry.id
                        }
                    })
                    assertTrue(first.items.first() is DecodedDisplayEvent.Header)
                    val second = reader.page(2, 2)
                    assertEquals(listOf(4, 5), second.items.map { (it as DecodedDisplayEvent.Row).row.entry.id })
                    assertTrue(second.endReached)
                } finally {
                    reader.close()
                }
            } finally {
                query.close()
                analysis.close()
                source.close()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
