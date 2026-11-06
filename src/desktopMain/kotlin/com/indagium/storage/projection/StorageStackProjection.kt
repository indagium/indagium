package com.indagium.storage.projection

import androidx.compose.ui.graphics.Color
import com.indagium.storage.EntryId
import com.indagium.storage.StorageFailure
import com.indagium.storage.StorageQuery
import com.indagium.storage.StorageScanLimiter
import com.indagium.storage.StorageStackGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.BitSet

/** Stack-only projection; sequences and manual blocks require a different grouping traversal. */
@Suppress("CyclomaticComplexMethod", "ComplexCondition", "MagicNumber") // Preserves stack traversal and legacy row colour.
internal suspend fun buildStorageStackProjection(
    query: StorageQuery,
    stackGroups: StorageStackGroups,
    generation: ProjectionGeneration,
    expanded: Set<String>,
    matchCount: Long,
    pageSize: Int,
    maxDisplayPageRows: Int,
    sequencesEnabled: Boolean,
    manualGroupsEnabled: Boolean,
    maxRows: Int = 10_000_000,
    maxMembers: Int = 20_000_000,
): DisplayProjection = withContext(Dispatchers.Default) {
    StorageScanLimiter.withScan {
        if (sequencesEnabled || manualGroupsEnabled) {
            throw StorageFailure.InvalidRequest("Stack-only projection cannot represent sequences or manual blocks")
        }
        if (generation.sourceVersion != query.version || stackGroups.version != query.version || generation.queryId != query.queryId) {
            throw StorageFailure.InvalidRequest("Projection inputs belong to different query generations")
        }
        if (matchCount < 0 || matchCount > maxRows || maxRows < 0 || maxMembers < 0 || pageSize <= 0) {
            throw StorageFailure.InvalidRequest("Invalid projection limits")
        }
        val context = currentCoroutineContext()
        query.validate()
        val groups = HashMap<Int, VisibleStack>()
        var memberTotal = 0L
        for (group in stackGroups.groups) {
            context.ensureActive()
            memberTotal += group.memberCount
            if (memberTotal > maxMembers) throw StorageFailure.InvalidRequest("Stack projection membership limit exceeded")
            val rootPosition = query.positionOf(EntryId(group.rid)) ?: continue
            val rootPage = query.ordinals(rootPosition, 1)
            check(rootPage.size == 1 && rootPage.version == query.version) { "Inconsistent root ordinal" }
            val positions = LongArray(group.memberCount)
            val ordinals = LongArray(group.memberCount)
            val ids = IntArray(group.memberCount)
            var count = 0
            for (i in 0 until group.memberCount) {
                context.ensureActive()
                val id = group.memberId(i)
                val position = query.positionOf(EntryId(id)) ?: continue
                val page = query.ordinals(position, 1)
                check(page.size == 1 && page.version == query.version) { "Inconsistent member ordinal" }
                positions[count] = position
                ordinals[count] = page[0].value
                ids[count++] = id
            }
            if (count != 0) {
                groups[group.rid] = VisibleStack(
                    group.gid, rootPage[0].value, positions.copyOf(count), ordinals.copyOf(count), ids.copyOf(count),
                )
            }
        }
        val builder = DisplayProjection.Builder(generation, maxDisplayPageRows)
        val claimed = BitSet(matchCount.toInt())
        val memberStyle = RowStyle(indent = 1, groupColor = Color(0xFFf85149))
        var start = 0L
        while (start < matchCount) {
            context.ensureActive()
            val count = minOf(pageSize.toLong(), matchCount - start).toInt()
            val rows = query.rows(start, count)
            val ordinals = query.ordinals(start, count)
            check(rows.version == query.version && rows.start == start && rows.items.size == count &&
                ordinals.version == query.version && ordinals.start == start && ordinals.size == count &&
                rows.endReached == (start + count == matchCount) && ordinals.endReached == rows.endReached
            ) { "Inconsistent query page" }
            for (index in 0 until count) {
                context.ensureActive()
                val row = rows.items[index]
                val group = groups[row.ref.id.value]
                if (group != null) {
                    val isExpanded = group.id in expanded
                    builder.appendHeader(HeaderDescriptor(
                        group.id, "stack", group.rootOrdinal, -1, -1, 0, isExpanded,
                        group.ordinals, row.ref.id, group.ids.size,
                    ))
                    for (position in group.positions) claimed.set(position.toInt())
                    if (isExpanded) {
                        for (i in group.ids.indices) {
                            builder.appendRow(group.ordinals[i], EntryId(group.ids[i]), memberStyle)
                        }
                    }
                } else if (!claimed[(start + index).toInt()]) {
                    builder.appendRow(ordinals[index].value, row.ref.id)
                }
            }
            start += count
        }
        // Validate even empty results and reject a stale or incorrect count.
        val end = query.ordinals(matchCount, 1)
        check(end.size == 0 && end.endReached) { "Incorrect query match count" }
        val projection = builder.finish()
        query.validate()
        context.ensureActive()
        projection
    }
}

private class VisibleStack(
    val id: String,
    val rootOrdinal: Long,
    val positions: LongArray,
    val ordinals: LongArray,
    val ids: IntArray,
)
