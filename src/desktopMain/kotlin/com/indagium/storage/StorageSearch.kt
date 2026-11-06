package com.indagium.storage

import com.indagium.storage.projection.DecodedDisplayEvent
import com.indagium.storage.projection.ProjectionGeneration
import com.indagium.storage.projection.ProjectionPageReader
import com.indagium.storage.projection.buildStorageStackProjection
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.SearchComputeResult
import com.indagium.utils.isValidRegexPattern
import com.indagium.utils.regexRanges
import com.indagium.utils.visibleLogLineText
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID

/** Searches the same fully expanded stack projection used by the eager viewer. */
internal suspend fun searchStorageQuery(
    query: StorageQuery,
    groups: StorageStackGroups,
    source: ReadSnapshot,
    matchCount: Long,
    pageRows: Int,
    pattern: String,
    caseSensitive: Boolean,
    regex: RegexEvaluationContext = RegexEvaluationContext(),
): SearchComputeResult {
    val context = currentCoroutineContext()
    context.ensureActive()
    if (pattern.isEmpty()) return SearchComputeResult(IntArray(0), invalidPattern = false, timedOut = false)
    if (!isValidRegexPattern(pattern, ignoreCase = !caseSensitive)) {
        return SearchComputeResult(IntArray(0), invalidPattern = true, timedOut = false)
    }
    val generation = ProjectionGeneration(query.version, query.queryId, UUID.randomUUID().toString(), 0)
    val expanded = groups.groups.mapTo(HashSet()) { it.gid }
    val projection = buildStorageStackProjection(query, groups, generation, expanded, matchCount, pageRows, pageRows, false, false)
    val reader = ProjectionPageReader(projection, source, pageRows)
    try {
        var ids = IntArray(16)
        var count = 0
        var start = 0L
        while (start < projection.size) {
            context.ensureActive()
            val page = reader.page(start, pageRows)
            check(page.start == start && page.items.isNotEmpty() && page.items.size.toLong() <= projection.size - start)
            for (event in page.items) {
                context.ensureActive()
                val row = when (event) {
                    is DecodedDisplayEvent.Row -> event.row.entry
                    is DecodedDisplayEvent.Header -> event.anchor.entry
                }
                if (regexRanges(visibleLogLineText(row), pattern, ignoreCase = !caseSensitive, regexContext = regex).isNotEmpty()) {
                    if (count == Int.MAX_VALUE) throw StorageFailure.InvalidRequest("Too many search matches")
                    if (count == ids.size) ids = ids.copyOf(minOf(Int.MAX_VALUE.toLong(), ids.size.toLong() * 2).toInt())
                    ids[count++] = row.id
                }
            }
            start += page.items.size
            check(page.endReached == (start == projection.size))
        }
        context.ensureActive()
        return SearchComputeResult(ids.copyOf(count), invalidPattern = false, timedOut = regex.hasTimedOut)
    } finally {
        reader.close()
    }
}
