package com.indagium.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Consume completed query results with backpressure, without retaining earlier decoded pages. */
internal suspend fun StorageQuery.forEachPage(
    pageSize: Int,
    consume: suspend (StoragePage<LegacyDecodedRow>) -> Unit,
) {
    if (pageSize <= 0) throw StorageFailure.InvalidRequest("Page size must be positive")
    var position = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val page = rows(position, pageSize)
        if (page.items.isNotEmpty()) consume(page)
        currentCoroutineContext().ensureActive()
        if (page.endReached) return
        check(page.items.isNotEmpty() && position <= Long.MAX_VALUE - page.items.size) {
            "Query returned an inconsistent stream page"
        }
        position += page.items.size
    }
}
