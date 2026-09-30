package com.indagium.capture

import com.indagium.model.LogEntry
import com.indagium.utils.parseLogcatLines
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
