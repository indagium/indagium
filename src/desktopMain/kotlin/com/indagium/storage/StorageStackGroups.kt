package com.indagium.storage

import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.utils.CompactStackGroup
import com.indagium.utils.StackTraceScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Collections

/** Limits bound retained item counts, not bytes or temporary decoded-page/record allocations. */
internal data class StorageStackLimits(
    val maxRows: Long = 20_000_000,
    val maxMembers: Long = 20_000_000,
    val maxOpenStates: Long = 1_000_000,
)

internal class StackScanLimitExceeded(val limitName: String, val limit: Long) :
    StorageFailure("Stack scan $limitName limit exceeded: $limit")

/** Complete stack analysis for one snapshot; membership remains primitive and scales with traces. */
internal data class StorageStackGroups(
    val version: SourceVersion,
    val groups: List<CompactStackGroup>,
)

/**
 * Scans the unfiltered source. The caller owns [snapshot] and must keep it open until this returns.
 * The source implementation determines how changes are detected; metadata checks cannot detect
 * every rewrite. Cancellation, a read failure, a limit or a version mismatch returns no partial
 * result. Limits bound counts, not bytes, and do not cap decoded page or oversized-record scratch.
 * Open-key and prelude metadata can grow up to the configured state-count limit.
 * DLT follows the application route, which deliberately does not infer Java stack groups.
 */
@Suppress("CyclomaticComplexMethod") // One scan retains the legacy state-machine order.
internal suspend fun scanStorageStackGroups(
    snapshot: ReadSnapshot,
    format: LogFormat,
    pageRows: Int,
    limits: StorageStackLimits = StorageStackLimits(),
    consumeRow: (LogEntry) -> Unit = {},
): StorageStackGroups = withContext(Dispatchers.Default) { StorageScanLimiter.withScan {
    if (pageRows <= 0 || limits.maxRows < 0 || limits.maxMembers < 0 || limits.maxOpenStates < 0) {
        throw StorageFailure.InvalidRequest("Invalid stack scan limits")
    }
    val context = currentCoroutineContext()
    val version = snapshot.version
    val count = snapshot.rowCount
    check(count >= 0) { "Negative source row count" }
    if (format != LogFormat.DLT && count > limits.maxRows) {
        throw StackScanLimitExceeded("rows", limits.maxRows)
    }
    var memberCount = 0L
    val scanner = StackTraceScanner(
        beforeMember = {
            if (memberCount >= limits.maxMembers) throw StackScanLimitExceeded("members", limits.maxMembers)
            memberCount++
        },
        beforeStateAdd = { existing ->
            if (existing >= limits.maxOpenStates) throw StackScanLimitExceeded("open states", limits.maxOpenStates)
        },
    )
    if (format != LogFormat.DLT) {
        var start = 0L
        while (start < count) {
            context.ensureActive()
            val page = snapshot.rows(RowOrdinal(start), pageRows)
            check(page.version == version && page.start == start && page.items.isNotEmpty()) {
                "Inconsistent source page"
            }
            check(page.items.size <= pageRows && page.items.size.toLong() <= count - start &&
                page.endReached == (start + page.items.size == count)) { "Inconsistent source page" }
            for (row in page.items) {
                context.ensureActive()
                scanner.feed(row.entry)
                consumeRow(row.entry)
            }
            start += page.items.size
        }
    }
    context.ensureActive()
    val groups = if (format == LogFormat.DLT) emptyList() else scanner.finish { context.ensureActive() }
    // Revalidate after finalization too, including an empty source or the DLT no-op route.
    val end = snapshot.rows(RowOrdinal(count), 1)
    check(end.version == version && end.start == count && end.items.isEmpty() && end.endReached) {
        "Inconsistent source end"
    }
    context.ensureActive()
    StorageStackGroups(version, Collections.unmodifiableList(groups))
} }
