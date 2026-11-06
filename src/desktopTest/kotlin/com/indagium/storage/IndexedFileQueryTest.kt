package com.indagium.storage

import com.indagium.model.Filter
import com.indagium.model.FilterMode
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IndexedFileQueryTest {
    @Test
    fun generatedFileCanBeFilteredAndPagedThroughTheStorageBoundary() = runBlocking {
        val path = Files.createTempFile("indexed-query-", ".log")
        try {
            Files.writeString(path, "----- beginning\n01-01 12:00:00.000 1 2 I Tag: first\n" +
                "\n01-01 12:00:00.001 1 2 W Tag: denied\nraw denied\n")
            val source = IndexedFileSource.open(SourceId("generated"), path, maxPageRows = 2)
            try {
                val query = StorageQuery(source.snapshot(), 2, 2)
                try {
                    assertEquals(2L, query.scanFilter(Filter(mode = FilterMode.KEYWORD, kwText = "denied")))
                    val page = query.rows(0, 2)
                    assertEquals(listOf(2, 3), page.items.map { it.ref.id.value })
                    assertEquals(listOf("denied", "raw denied"), page.items.map { it.entry.msg })
                    assertTrue(page.endReached)
                } finally {
                    query.close()
                }
            } finally {
                source.close()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun changedSourceDoesNotPublishPartialQueryResults(): Unit = runBlocking {
        val path = Files.createTempFile("indexed-query-change-", ".log")
        try {
            Files.writeString(path, "first\nsecond\nthird\n")
            val source = IndexedFileSource.open(SourceId("generated"), path, maxPageRows = 1)
            val query = StorageQuery(source.snapshot(), 1, 1)
            try {
                assertFailsWith<IndexedFileFailure.Changed> {
                    query.scan {
                        Files.writeString(path, "changed data\n")
                        true
                    }
                }
                assertFailsWith<StorageFailure.InvalidRequest> { query.rows(0, 1) }
            } finally {
                query.close()
                source.close()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
