package com.indagium.storage

import com.indagium.model.AppSettings
import com.indagium.model.LogAnalysis
import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogTab
import com.indagium.utils.LogLinePresentationContext
import com.indagium.utils.filteredCsvHeader
import com.indagium.utils.filteredCsvValues
import com.indagium.utils.moveReplacingAtomically
import com.indagium.utils.presentLogLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Frozen presentation inputs; [selectedMinimumId] is from the full selection, not the filtered view. */
internal data class StorageExportSpec(
    val format: LogFormat,
    val settings: AppSettings,
    val showTimeDelta: Boolean,
    val selectedMinimumId: Int?,
    val processNames: Map<Int, String>,
    val csv: Boolean,
)

/**
 * Synchronously invokes [action] exactly once while holding the caller's tab/query generation
 * lock, or throws if stale. Implementations must not retain the action after returning.
 */
internal fun interface StorageExportCommitFence {
    fun commit(action: () -> Unit)
}

/**
 * Experimental filtered export. The caller owns both leases for this whole operation and fences
 * publication against its own tab/request lifetime. [anchorSnapshot] must be an unfiltered
 * snapshot of the same source version as [query]. Source IDs must be unique, as in the supplied
 * storage adapters. A query page and at most two extra decoded entries are retained at a time.
 *
 * Output is first written to a sibling temporary file. Cancellation, a detected source change,
 * a rejected generation fence, or a write failure before replacement leaves the destination intact.
 * External source changes cannot be made atomic with this commit using metadata checks alone. The existing move helper may
 * fall back to a non-atomic replace on unsupported filesystems. Cancellation racing with or after
 * that move can still reach the caller even though the destination was replaced.
 */
internal suspend fun exportStorageQuery(
    query: StorageQuery,
    anchorSnapshot: ReadSnapshot,
    destination: File,
    spec: StorageExportSpec,
    pageRows: Int,
    commitFence: StorageExportCommitFence,
) {
    // Snapshot caller-owned maps before the first dispatcher transition.
    val frozen = spec.copy(processNames = spec.processNames.toMap())
    withContext(Dispatchers.IO) {
        require(pageRows > 0) { "Export page size must be positive" }
        check(anchorSnapshot.version == query.version) { "Anchor source version differs from query" }
        val context = currentCoroutineContext()
        context.ensureActive()
        query.validate()
        val anchorId = frozen.selectedMinimumId.takeIf { frozen.settings.copyTimeDelta && frozen.showTimeDelta }
        val anchor = anchorId?.let { id -> resolveAnchor(anchorSnapshot, id) }
        val tab = LogTab(
            id = "storage-export",
            filename = "",
            logData = emptyList(),
            rmap = anchor?.let { mapOf(it.id to it) } ?: emptyMap(),
            selected = anchorId?.let { setOf(it) } ?: emptySet(),
            analysis = LogAnalysis(processNames = frozen.processNames),
            showTimeDelta = frozen.showTimeDelta,
            logFormat = frozen.format,
        )
        val path = destination.toPath().toAbsolutePath().normalize()
        val parent = path.parent ?: throw IllegalArgumentException("Export destination has no parent")
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, ".${path.fileName}.", ".tmp")
        var committed = false
        try {
            Files.newBufferedWriter(temp, StandardCharsets.UTF_8).use { writer ->
                if (frozen.csv) writer.appendLine(filteredCsvHeader(tab, frozen.settings).joinToString(","))
                var previous: LogEntry? = null
                query.forEachPage(pageRows) { page ->
                    check(page.version == query.version) { "Query version changed during export" }
                    for (row in page.items) {
                        context.ensureActive()
                        val entry = row.entry
                        val entries = previous?.let { listOf(it, entry) } ?: listOf(entry)
                        val presentation = LogLinePresentationContext(tab, frozen.settings, entries)
                        val line = if (frozen.csv) {
                            filteredCsvValues(tab, entry, frozen.settings, presentation).joinToString(",", transform = ::csvField)
                        } else {
                            presentLogLine(tab, entry, frozen.settings, presentation)
                        }
                        writer.appendLine(line)
                        previous = entry
                    }
                }
            }
            query.validate()
            // Independently revalidate the anchor lease, including empty queries and missing anchors.
            anchorSnapshot.resolve(EntryId(0))
            context.ensureActive()
            commitFence.commit {
                check(!committed) { "Export commit invoked more than once" }
                context.ensureActive()
                moveReplacingAtomically(temp, path)
                committed = true
            }
            check(committed) { "Export fence did not commit or reject" }
        } finally {
            if (!committed) Files.deleteIfExists(temp)
        }
    }
}

private suspend fun resolveAnchor(snapshot: ReadSnapshot, id: Int): LogEntry? {
    val ordinal = snapshot.resolve(EntryId(id)) ?: return null
    val page = snapshot.rows(ordinal, 1)
    check(page.version == snapshot.version && page.start == ordinal.value && page.items.size == 1) {
        "Inconsistent anchor page"
    }
    val entry = page.items.single().entry
    check(entry.id == id) { "Anchor ID does not match its source position" }
    return entry
}

private fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${value.replace("\"", "\"\"")}\""
    } else {
        value
    }
