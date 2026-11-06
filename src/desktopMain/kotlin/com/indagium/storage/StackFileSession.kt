package com.indagium.storage

import com.indagium.model.AppSettings
import com.indagium.model.Filter
import com.indagium.model.LogAnalysis
import com.indagium.model.LogFormat
import com.indagium.model.LogTab
import com.indagium.storage.projection.DecodedDisplayPage
import com.indagium.storage.projection.DisplayProjection
import com.indagium.storage.projection.ProjectionGeneration
import com.indagium.storage.projection.ProjectionPageReader
import com.indagium.storage.projection.buildStorageStackProjection
import com.indagium.utils.LogLinePresentationContext
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.SearchComputeResult
import com.indagium.utils.presentLogLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Path
import java.util.UUID

private const val NAVIGATION_CHECK_INTERVAL = 1024

/**
 * Experimental immutable file view. It supports stack folding only; sequence/manual groups and
 * all application routing remain separate. Closing invalidates new work and releases owned leases.
 */
internal class StackFileSession private constructor(
    private val source: IndexedFileSource,
    private val query: StorageQuery,
    val projection: DisplayProjection,
    private val reader: ProjectionPageReader,
    private val pageRows: Int,
    val analysis: StorageAnalysis,
    val filter: Filter,
    val expanded: Set<String>,
    private val stackGroups: StorageStackGroups,
    private val matchCount: Long,
) : AutoCloseable {
    private val lock = Any()
    private val commitGate = StorageCommitGate()
    private val operations = SessionOperations(maxConcurrent = 2)
    private val resourcesClosed = CompletableDeferred<Unit>()
    private var closed = false

    val generation: ProjectionGeneration get() = projection.generation
    val displayCount: Long get() = projection.size

    suspend fun page(start: Long, limit: Int): DecodedDisplayPage = operations.run {
        checkOpen()
        reader.page(start, limit)
    }

    suspend fun selectionOccurrence(position: Long, id: EntryId): SelectionOccurrence = operations.run {
        checkOpen()
        SelectionOccurrence(id, position, projection.occurrenceAt(position, id))
    }

    suspend fun navigationTarget(id: EntryId): StorageNavigationTarget = operations.run {
        checkOpen()
        projection.locateItem(id)?.let { return@run StorageNavigationTarget.Visible(it) }
        for (group in stackGroups.groups) {
            currentCoroutineContext().ensureActive()
            if (group.gid !in expanded) {
                for (index in 0 until group.memberCount) {
                    if (index % NAVIGATION_CHECK_INTERVAL == 0) currentCoroutineContext().ensureActive()
                    if (group.memberId(index) == id.value) return@run StorageNavigationTarget.Expand(group.gid)
                }
            }
        }
        StorageNavigationTarget.Missing
    }

    suspend fun search(
        pattern: String,
        caseSensitive: Boolean,
        applyFilter: Boolean,
        regexContext: RegexEvaluationContext = RegexEvaluationContext(),
    ): SearchComputeResult = operations.run {
        checkOpen()
        if (applyFilter) {
            source.snapshot().use { searchStorageQuery(query, stackGroups, it, matchCount, pageRows, pattern, caseSensitive, regexContext) }
        } else {
            val allRows = StorageQuery(source.snapshot(), pageRows, pageRows)
            try {
                val count = allRows.scan { true }
                source.snapshot().use { searchStorageQuery(allRows, stackGroups, it, count, pageRows, pattern, caseSensitive, regexContext) }
            } finally {
                allRows.close()
            }
        }
    }

    /** Resolves selected IDs for copy; callers must still fence clipboard publication. */
    suspend fun selectedIdsForCopy(
        selection: StorageSelection,
        current: SelectionGeneration,
        pageRows: Int = this.pageRows,
        maxSelectedIds: Int = 10_000_000,
    ): IntArray = operations.run {
        checkOpen()
        if (current.sourceId != source.id) throw StorageFailure.InvalidRequest("Selection source identity mismatch")
        val lookup = source.snapshot()
        try {
            resolveSelectionForCopy(selection, current, projection, reader, lookup, pageRows, maxSelectedIds = maxSelectedIds)
        } finally {
            lookup.close()
        }
    }

    /** Builds a bounded clipboard payload; callers must fence publication against their current view. */
    @Suppress("CyclomaticComplexMethod") // Validates and formats the selected indexed rows in bounded runs.
    suspend fun selectedTextForCopy(
        selection: StorageSelection,
        current: SelectionGeneration,
        settings: AppSettings,
        showTimeDelta: Boolean,
        maxChars: Int,
        maxSelectedIds: Int = 10_000_000,
    ): String = operations.run {
        checkOpen()
        if (current.sourceId != source.id || maxChars < 0) throw StorageFailure.InvalidRequest("Invalid copy request")
        val lookup = source.snapshot()
        try {
            val ids = resolveSelectionForCopy(selection, current, projection, reader, lookup, pageRows, maxSelectedIds = maxSelectedIds)
            if (ids.isEmpty()) return@run ""
            val anchor = lookup.resolve(EntryId(ids.first())) ?: throw StorageFailure.InvalidRequest("Selection anchor is missing")
            val anchorPage = lookup.rows(anchor, 1)
            val anchorEntry = anchorPage.items.singleOrNull()?.entry ?: throw StorageFailure.InvalidRequest("Selection anchor is missing")
            if (anchorEntry.id != ids.first()) throw StorageFailure.InvalidRequest("Selection anchor ID mismatch")
            val tab = LogTab(
                id = "storage-copy", filename = "", logData = emptyList(), rmap = mapOf(anchorEntry.id to anchorEntry),
                selected = setOf(anchorEntry.id), analysis = LogAnalysis(processNames = analysis.processNames),
                showTimeDelta = showTimeDelta,
            )
            val context = LogLinePresentationContext(tab, settings, emptyList())
            val text = StringBuilder()
            var index = 0
            while (index < ids.size) {
                currentCoroutineContext().ensureActive()
                // IndexedFileSource assigns IDs in source order, starting at one. Batch runs so
                // select-all does not open and validate the source separately for every row.
                val first = ids[index]
                if (first <= 0 || first.toLong() > lookup.rowCount) throw StorageFailure.InvalidRequest("Selected row is missing")
                var count = 1
                while (index + count < ids.size && count < pageRows && ids[index + count].toLong() == first.toLong() + count) count++
                val page = lookup.rows(RowOrdinal(first.toLong() - 1), count)
                if (page.version != current.sourceVersion || page.items.size != count) {
                    throw StorageFailure.InvalidRequest("Selected page changed")
                }
                for (offset in 0 until count) {
                    currentCoroutineContext().ensureActive()
                    val entry = page.items[offset].entry
                    if (entry.id != ids[index + offset]) throw StorageFailure.InvalidRequest("Selected row ID mismatch")
                    val line = presentLogLine(tab, entry, settings, context)
                    val extra = line.length.toLong() + if (text.isEmpty()) 0 else 1
                    if (text.length.toLong() + extra > maxChars) throw StorageFailure.InvalidRequest("Copy exceeds the character limit")
                    if (text.isNotEmpty()) text.append('\n')
                    text.append(line)
                }
                index += count
            }
            val end = lookup.rows(RowOrdinal(lookup.rowCount), 1)
            check(end.version == current.sourceVersion && end.start == lookup.rowCount && end.items.isEmpty() && end.endReached)
            currentCoroutineContext().ensureActive()
            text.toString()
        } finally {
            lookup.close()
        }
    }

    /** Exports filtered rows; a commit that wins before close may finish after close. */
    suspend fun export(
        destination: File,
        spec: StorageExportSpec,
        pageRows: Int = this.pageRows,
        callerFence: StorageExportCommitFence? = null,
    ) = operations.run {
        checkOpen()
        val anchor = source.snapshot()
        try {
            val fence = if (callerFence == null) commitGate else StorageExportCommitFence { action ->
                commitGate.commit { callerFence.commit(action) }
            }
            exportStorageQuery(query, anchor, destination, spec, pageRows, fence)
        } finally {
            anchor.close()
        }
    }

    private fun checkOpen() = synchronized(lock) { if (closed) throw StorageFailure.Closed() }

    @Suppress("TooGenericExceptionCaught") // Report shutdown failures to awaitClosed.
    override fun close() {
        synchronized(lock) {
            if (closed) return
            commitGate.close()
            closed = true
        }
        operations.close()
        try {
            try {
                reader.close()
            } finally {
                try {
                    query.close()
                } finally {
                    source.close()
                }
            }
            resourcesClosed.complete(Unit)
        } catch (failure: Throwable) {
            resourcesClosed.completeExceptionally(failure)
            throw failure
        }
    }

    /** Waits until resource closure and all cancelled operations have finished. */
    suspend fun awaitClosed() {
        try {
            resourcesClosed.await()
        } finally {
            operations.awaitClosed()
        }
    }

    companion object {
        /** Uses caller-provided analysis metadata; does not infer process names or route unsupported workflows. */
        @Suppress("TooGenericExceptionCaught", "ThrowsCount") // Preserve typed failures and release every owned lease.
        suspend fun open(
            id: SourceId,
            path: Path,
            filter: Filter,
            sourceFormat: LogFormat,
            hasManualGroups: Boolean,
            processNames: Map<Int, String>? = null,
            tagPids: Map<String, Set<Int>>? = null,
            expanded: Set<String> = emptySet(),
            pageRows: Int = 256,
            regexContext: RegexEvaluationContext = RegexEvaluationContext(),
        ): StackFileSession {
            val frozenFilter = filter.copy(
                levels = filter.levels.toSet(), activeTags = filter.activeTags.toSet(),
                excludeTags = filter.excludeTags.toSet(), highlighters = filter.highlighters.toList(),
                messageRules = filter.messageRules.toList(), pkgPrefixes = filter.pkgPrefixes.toSet(),
                excludePkgPrefixes = filter.excludePkgPrefixes.toSet(), sequences = filter.sequences.toList(),
            )
            if (sourceFormat != LogFormat.LOGCAT) throw StorageFailure.InvalidRequest("Stack file session supports logcat only")
            if (hasManualGroups || (frozenFilter.seqOn && frozenFilter.sequences.any { it.enabled })) {
                throw StorageFailure.InvalidRequest("Stack file session does not support manual or sequence groups")
            }
            if (pageRows <= 0) throw StorageFailure.InvalidRequest("Page size must be positive")
            val frozenNames = processNames?.toMap()
            val frozenTagPids = tagPids?.mapValues { it.value.toSet() }
            val frozenExpanded = expanded.toSet()
            val source = IndexedFileSource.open(id, path, pageRows)
            var query: StorageQuery? = null
            var reader: ProjectionPageReader? = null
            try {
                val activeQuery = StorageQuery(source.snapshot(), pageRows, pageRows)
                query = activeQuery
                val metadata = StorageAnalysisAccumulator()
                val groups = source.snapshot().use { scanStorageStackGroups(it, LogFormat.LOGCAT, pageRows, consumeRow = metadata::feed) }
                val discovered = metadata.finish()
                val analysis = discovered.copy(
                    processNames = frozenNames ?: discovered.processNames,
                    tagPids = frozenTagPids ?: discovered.tagPids,
                )
                val count = activeQuery.scanFilter(frozenFilter, analysis.processNames, analysis.tagPids, regexContext)
                if (regexContext.hasTimedOut) throw StorageFailure.InvalidRequest("Filter timed out")
                val generation = ProjectionGeneration(activeQuery.version, activeQuery.queryId, UUID.randomUUID().toString(), 0)
                val projection = buildStorageStackProjection(
                    activeQuery, groups, generation, frozenExpanded, count, pageRows, pageRows, false, false,
                )
                val activeReader = ProjectionPageReader(projection, source.snapshot(), pageRows)
                reader = activeReader
                currentCoroutineContext().ensureActive()
                return StackFileSession(
                    source, activeQuery, projection, activeReader, pageRows, analysis, frozenFilter, frozenExpanded, groups, count,
                )
            } catch (failure: Throwable) {
                reader?.close()
                query?.close()
                source.close()
                throw failure
            }
        }
    }
}
