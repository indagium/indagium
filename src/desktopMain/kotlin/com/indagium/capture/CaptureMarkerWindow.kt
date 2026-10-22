package com.indagium.capture

import com.indagium.model.LogEntry
import com.indagium.utils.parseLogcatLines
import com.indagium.utils.readTextShared
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile

// Read-only helpers behind AppState.markIssue's "Mark issue" press (restyle plan Phase 3). Both
// functions below stream the frozen capture index a line at a time and never read past the
// caller-supplied [indexBytes] bound — the flushed length CaptureSessionBoundary/
// TabCaptureController.snapshotForExport reports, so a press mid-write can never race the
// recorder's own in-flight append. Malformed/unparsable lines are skipped rather than thrown, and
// a separator row (CaptureLogIndexRecord.rowOrdinal == null) never contributes an ordinal — same
// posture as every other index scan in CaptureArchive.kt, just tolerant instead of `require`-based
// since this path backs an optional UI action, not an export users depend on for data integrity.

private const val MAX_MARKER_INDEX_LINE_BYTES = 64 * 1024
private const val MAX_MARKER_SYNC_BYTES = 16L * 1024 * 1024

/** Frozen-session variant; sparse captures scan candidate blocks without building a dense index. */
fun ordinalRangeForElapsedWindow(session: CaptureSession, startMs: Long, endMs: Long): IntRange? {
    if (session.storageVersion == CaptureStorageVersion.LEGACY_DENSE) {
        return ordinalRangeForElapsedWindow(session.indexFile, session.indexFile.length(), startMs, endMs)
    }
    if (endMs < startMs || endMs < 0L) return null
    return runCatching {
        val source = sparseMarkerSource(session)
        var first: Int? = null
        var last: Int? = null
        val correctedUncertain = source.metadata.segments.filter {
            it.reliability == CaptureSyncReliability.UNCERTAIN && it.manualAnchor != null
        }
        val candidates = source.index.blocks.filter { block ->
            block.overlapsElapsed(startMs.coerceAtLeast(0L), endMs) ||
                correctedUncertain.any { block.overlapsRows(it.firstRow, it.lastRowExclusive) }
        }
        candidates.forEach { block ->
            forEachSparseMarkerEntry(session.logFile, block) { ordinal, entry ->
                val elapsed = CaptureTimingResolver.sparsePoint(entry, ordinal, source.metadata)?.captureElapsedMs
                // A missing/uncertain timestamp cannot narrow a capture-time block further.
                if (elapsed == null || elapsed in startMs..endMs) {
                    first = minOf(first ?: ordinal, ordinal)
                    last = maxOf(last ?: ordinal, ordinal)
                }
            }
        }
        first?.let { it..requireNotNull(last) }
    }.getOrNull()
}

/** Reads exact source ordinals, including untimestamped rows, within the frozen log boundary. */
fun captureLogEntriesForOrdinals(session: CaptureSession, wantedOrdinals: Set<Int>): List<LogEntry> {
    if (session.storageVersion == CaptureStorageVersion.LEGACY_DENSE) {
        return captureLogEntriesForOrdinals(session.indexFile, session.indexFile.length(), session.logFile, wantedOrdinals)
    }
    val wanted = wantedOrdinals.filterTo(java.util.TreeSet()) { it in 1 until Int.MAX_VALUE }
    if (wanted.isEmpty()) return emptyList()
    return runCatching {
        val source = sparseMarkerSource(session)
        val hits = ArrayList<LogEntry>(wanted.size)
        source.index.candidateBlocksForRows(wanted.first(), wanted.last() + 1).forEach { block ->
            if (wanted.ceiling(block.firstRow)?.let { it < block.lastRowExclusive } != true) return@forEach
            forEachSparseMarkerEntry(session.logFile, block) { ordinal, entry ->
                if (ordinal in wanted) hits += entry
            }
        }
        hits
    }.getOrDefault(emptyList())
}

private data class SparseMarkerSource(val metadata: CaptureSyncMetadata, val index: CaptureBlockIndex)

private fun sparseMarkerSource(session: CaptureSession): SparseMarkerSource {
    val metadata = session.syncMetadataOverride ?: run {
        require(session.syncFile.length() in 1..MAX_MARKER_SYNC_BYTES) { "Capture timing metadata is missing or too large" }
        CaptureSyncMetadata.parse(readTextShared(session.syncFile))
    }
    val logBytes = session.frozenLogBytes ?: metadata.logBytes
    require(logBytes == metadata.logBytes) { "Capture timing metadata does not match the frozen log" }
    val anchors = session.manualSegmentAnchors.associateBy { it.segmentId }
    val corrected = if (anchors.isEmpty()) metadata else metadata.copy(
        segments = metadata.segments.map { segment ->
            anchors[segment.id]?.let { segment.copy(manualAnchor = it) } ?: segment
        },
    )
    val index = CaptureBlockIndex.read(
        indexFile = session.blockIndexFile,
        logFile = session.logFile,
        maxIndexBytes = session.frozenBlockIndexBytes ?: session.blockIndexFile.length(),
        maxLogBytes = logBytes,
        verifyByteRange = LongRange.EMPTY,
    )
    require(index.indexedRows == metadata.rowCount) { "Capture block row bounds do not match timing metadata" }
    return SparseMarkerSource(corrected, index)
}

private fun forEachSparseMarkerEntry(logFile: File, block: CaptureBlock, action: (Int, LogEntry) -> Unit) {
    CaptureBlockIndex.verifyBlock(logFile, block)
    RandomAccessFile(logFile, "r").use { input ->
        input.seek(block.byteEnd - 1L)
        require(input.read() == '\n'.code) { "Capture block ends inside a log row" }
    }
    logFile.inputStream().use { input ->
        input.channel.position(block.byteStart)
        val bounded = LimitedWindowInputStream(input, block.byteEnd - block.byteStart)
        var ordinal = block.firstRow
        bounded.bufferedReader(Charsets.UTF_8).forEachLine { raw ->
            val text = raw.trim()
            if (text.isNotEmpty() && !text.startsWith("-----")) {
                require(ordinal < block.lastRowExclusive) { "Capture block contains too many log rows" }
                val entry = parseLogcatLines(sequenceOf(raw), startId = ordinal).singleOrNull()
                requireNotNull(entry) { "Capture block row could not be parsed" }
                action(ordinal, entry)
                ordinal++
            }
        }
        require(ordinal == block.lastRowExclusive) { "Capture block row bounds do not match its log rows" }
    }
}

/**
 * Read-only ordinal-range variant of the private `scanElapsedBounds` in CaptureArchive.kt: instead
 * of returning the first/last *elapsedMs* in [startMs, endMs], this returns the min/max *ordinal*
 * — what AppState.markIssue actually needs to build a LogRef block. Returns null when no row in
 * the index falls inside the window (including an index with zero usable bytes).
 */
fun ordinalRangeForElapsedWindow(indexFile: File, indexBytes: Long, startMs: Long, endMs: Long): IntRange? {
    if (indexBytes <= 0L || !indexFile.isFile) return null
    var first: Int? = null
    var last: Int? = null
    indexFile.inputStream().use { base ->
        val input = LimitedWindowInputStream(BufferedInputStream(base), indexBytes)
        forEachWindowLine(input) { line ->
            val record = runCatching { parseIndexRecord(line) }.getOrNull() ?: return@forEachWindowLine
            val ordinal = record.rowOrdinal ?: return@forEachWindowLine
            if (record.elapsedMs in startMs..endMs) {
                first = minOf(first ?: ordinal, ordinal)
                last = maxOf(last ?: ordinal, ordinal)
            }
        }
    }
    val firstOrdinal = first ?: return null
    val lastOrdinal = last ?: return null
    return firstOrdinal..lastOrdinal
}

/**
 * Tail-lag fallback for [AppState.markIssue]'s trailing window: a live tab's `LogTab.logData` may
 * not have caught up to rows the recorder already flushed to disk (the tail-append coordinator
 * polls independently of a marker press). This reads exactly [wantedOrdinals]' raw bytes straight
 * from the capture log — the same seek-and-copy pattern `copySelectedLog` (CaptureArchive.kt) uses
 * for export — and parses each line individually so the returned [LogEntry.id] equals its capture
 * ordinal (LogParser.parseLogcatLines's `startId` parameter forces that). A wanted ordinal that
 * isn't found in the index (already outside [indexBytes], or the line fails to parse) is simply
 * absent from the result — callers merge this with whatever `LogTab.rmap` already had.
 */
fun captureLogEntriesForOrdinals(
    indexFile: File,
    indexBytes: Long,
    logFile: File,
    wantedOrdinals: Set<Int>,
): List<LogEntry> {
    if (wantedOrdinals.isEmpty() || indexBytes <= 0L || !indexFile.isFile || !logFile.isFile) return emptyList()
    val hits = ArrayList<CaptureLogIndexRecord>(wantedOrdinals.size)
    indexFile.inputStream().use { base ->
        val input = LimitedWindowInputStream(BufferedInputStream(base), indexBytes)
        forEachWindowLine(input) { line ->
            val record = runCatching { parseIndexRecord(line) }.getOrNull() ?: return@forEachWindowLine
            if (record.rowOrdinal in wantedOrdinals) hits.add(record)
        }
    }
    if (hits.isEmpty()) return emptyList()
    return runCatching {
        RandomAccessFile(logFile, "r").use { raf ->
            hits.mapNotNull { record -> readMarkerLogEntry(raf, record) }
        }
    }.getOrDefault(emptyList())
}

private fun readMarkerLogEntry(raf: RandomAccessFile, record: CaptureLogIndexRecord): LogEntry? {
    val ordinal = record.rowOrdinal ?: return null
    return runCatching {
        raf.seek(record.byteOffset)
        val bytes = ByteArray(record.byteLength)
        raf.readFully(bytes)
        val raw = bytes.toString(Charsets.UTF_8)
        parseLogcatLines(sequenceOf(raw), startId = ordinal).firstOrNull()
    }.getOrNull()
}

/** File-local duplicate of CaptureArchive.kt's private `LimitedInputStream` — that one is `private`
 *  (file-scoped in Kotlin, not package-scoped), so this window scan carries its own tiny copy
 *  rather than widening that file's visibility for a two-method helper. */
private class LimitedWindowInputStream(delegate: InputStream, private var remaining: Long) : FilterInputStream(delegate) {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val value = super.read()
        if (value >= 0) remaining--
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val count = super.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count > 0) remaining -= count
        return count
    }
}

/** File-local duplicate of CaptureArchive.kt's private `forEachCompleteLine`, trimmed to what this
 *  file needs (no `rejectTrailingPartial` mode — a trailing partial line here is simply the
 *  recorder mid-write, not a corruption to flag). */
private fun forEachWindowLine(input: InputStream, block: (String) -> Unit) {
    val line = ByteArrayOutputStream()
    while (true) {
        val value = input.read()
        if (value < 0) break
        if (value == '\n'.code) {
            val bytes = line.toByteArray()
            val length = if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
            if (length > 0) block(bytes.copyOf(length).toString(Charsets.UTF_8))
            line.reset()
        } else if (line.size() < MAX_MARKER_INDEX_LINE_BYTES) {
            line.write(value)
        }
    }
}
