package com.indagium.utils

import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import java.io.File

private const val TAIL_CANCELLATION_INTERVAL = 128L
private const val ROOT_ARRAY_PEEK_CHARS = 1 shl 16

/** Whether the first non-whitespace character [reader] holds is `[`; the reader is left where it was. */
private fun startsWithJsonArray(reader: java.io.BufferedReader): Boolean {
    reader.mark(ROOT_ARRAY_PEEK_CHARS)
    try {
        repeat(ROOT_ARRAY_PEEK_CHARS) {
            val c = reader.read()
            if (c < 0) return false
            if (!c.toChar().isWhitespace()) return c.toChar() == '['
        }
        return false
    } finally {
        reader.reset()
    }
}

/**
 * Parses a growing file batch by batch and keeps the state a record that spans lines needs: an open
 * CSV quote, the CSV header row, a multiline regex or JSON value that has not reached its boundary,
 * and CRI partial fragments. One instance per tailed tab; callers serialize access (the tail holds the
 * state lock). It reuses the same record machines as the whole-file parse, so any split of a file
 * yields the rows that parsing the whole file would.
 *
 * Engines whose record is one physical line (plain text, syslog, logfmt, single-line
 * regex) keep no state and go through [parseProfileLines]. DLT is binary and is rejected.
 */
internal class IncrementalProfileParser(
    private val profile: LogFormatProfile,
    private val sourceClockId: String? = null,
    csvHeaders: List<String>? = null,
) {
    private val diagnostics = mutableListOf<String>()
    private val machine: ProfileRecordMachine?
    private val cri: CriRecordAssembler?
    private val timeParser: ProfileTimeParser
    private val compactor = ProfileRowCompactor()
    private val seedEntryIdsByStartLine = mutableMapOf<Long, Int>()

    private class PendingSeed(val file: File, val consumedLines: Long?, val last: LogEntry?)

    // The seeding [seed] left for [prepare]: it reads the file. Guarded by this parser's monitor.
    private var pendingSeed: PendingSeed? = null

    /** How many open-record seeds the parser holds; a test seam for the "never grows with closed records" guarantee. */
    internal val seedCountForTesting: Int get() = seedEntryIdsByStartLine.size

    /** Existing rows whose content was extended and must be replaced in-place by the tail owner. */
    var replacedEntryIds: Set<Int> = emptySet()
        private set

    init {
        require(profile.engine != LogFormatEngine.DLT) { "DLT cannot be parsed incrementally." }
        require(profile.engine != LogFormatEngine.METADATA_JSON) { "A sectioned JSON document cannot be parsed incrementally." }
        validateLogFormatProfile(profile)
        machine = newTailRecordMachine(profile, diagnostics, csvHeaders)
        cri = if (profile.engine == LogFormatEngine.CRI) CriRecordAssembler(diagnostics) else null
        timeParser = ProfileTimeParser(profile, sourceClockId)
    }

    /**
     * Feeds physical [lines] that follow [sourceLineOffset] earlier lines of the file and returns the
     * records those lines completed. A record still waiting for more lines is kept for the next call.
     * [ParsedLog.sourceLineCount] is the absolute count of lines consumed so far.
     */
    @Synchronized
    fun append(lines: List<String>, startId: Int, sourceLineOffset: Long): ParsedLog {
        require(sourceLineOffset >= 0) { "Source line offset cannot be negative." }
        prepare()
        if (machine == null && cri == null) {
            replacedEntryIds = emptySet()
            return parseProfileLines(lines, profile, startId, sourceClockId, sourceLineOffset)
        }
        val ready = ArrayList<ProfileRecord>()
        var lineNumber = sourceLineOffset
        if (machine != null) {
            machine.lineNumber = sourceLineOffset
            for (line in lines) machine.accept(line, ready)
            // Show the newest record now; if more lines join it later, the same row is replaced.
            machine.pendingSnapshot(ready)
            lineNumber = machine.lineNumber
        } else if (cri != null) {
            for (raw in lines) {
                lineNumber++
                if (lineNumber % TAIL_CANCELLATION_INTERVAL == 0L) checkParserCancellation()
                cri.accept(raw, lineNumber).mapTo(ready) { it.toProfileRecord() }
            }
        }
        return result(ready, startId, lineNumber)
    }

    /** Returns the record still held when tailing stops; it is complete as far as the file goes. */
    @Synchronized
    fun flush(startId: Int, sourceLineOffset: Long): ParsedLog {
        // Still unseeded means no line was appended since the tab's own parse, which already ended (and showed) the open record.
        if (pendingSeed != null) {
            pendingSeed = null
            return result(emptyList(), startId, sourceLineOffset)
        }
        val ready = ArrayList<ProfileRecord>()
        machine?.finish(ready)
        cri?.finish()?.mapTo(ready) { it.toProfileRecord() }
        return result(ready, startId, sourceLineOffset)
    }

    private fun result(records: List<ProfileRecord>, startId: Int, lineCount: Long): ParsedLog {
        val entries = ArrayList<LogEntry>(records.size)
        val replaced = linkedSetOf<Int>()
        var nextId = startId
        for (record in records) {
            // The line locates a previously seeded row, but several new JSON records may share it.
            val seededId = seedEntryIdsByStartLine.remove(record.sourceStartLine)
            val id = seededId ?: nextId++
            if (seededId != null) replaced += seededId
            entries += recordToEntry(id, record, profile, timeParser, compactor)
        }
        // Remember the row that shows the still-open record, so its next state replaces it. A joining
        // machine holds at most one open record, so a seed for any other start line belongs to a record
        // that has since closed and is dropped; otherwise the map would grow with every record of a long tail.
        (machine as? ContinuationJoinMachine)?.let { join ->
            val openStart = join.pendingReplacementStartLine()
            val openId = openStart?.let { start ->
                entries.lastOrNull { it.sourceStartLine == start }?.id ?: seedEntryIdsByStartLine[start]
            }
            seedEntryIdsByStartLine.clear()
            if (openStart != null && openId != null) seedEntryIdsByStartLine[openStart] = openId
        }
        replacedEntryIds = replaced
        return ParsedLog(LogFormat.LOGCAT, entries, profile, diagnostics.toList(), sourceClockId, lineCount)
    }

    /**
     * Seeds any record that was flushed by the initial whole-file parse but is still open at EOF.
     * This uses only the already parsed rows, so starting a tail does not reread a large source.
     */
    private fun seed(existingEntries: List<LogEntry>, file: File?, consumedLines: Long?) {
        when (profile.engine) {
            LogFormatEngine.JSON_LINES ->
                machine?.let { recordMachine ->
                    // A document whose root array is still open at EOF keeps state no row carries (the array itself), so the rows
                    // alone cannot rebuild it: the file is streamed once. That read is deferred to [prepare] so it never runs on the
                    // thread that starts the tail (the UI thread for the Tail menu item) or under stateLock.
                    val last = existingEntries.lastOrNull()
                    if (file == null) seedSingleRecord(recordMachine, last) else pendingSeed = PendingSeed(file, consumedLines, last)
                }
            LogFormatEngine.REGEX, LogFormatEngine.CSV,
            LogFormatEngine.SYSLOG_RFC5424, LogFormatEngine.SYSLOG_RFC3164,
            ->
                machine?.let { seedSingleRecord(it, existingEntries.lastOrNull()) }
            LogFormatEngine.CRI -> cri?.let { seedCriRecords(it, existingEntries) }
            else -> Unit
        }
    }

    /**
     * Rebuilds [recordMachine] for a file whose first character is `[`: the first [consumedLines] lines (the whole file when null) are
     * streamed through the machine and every record it emits is dropped, so the memory held is one record and no rows are kept.
     * The element still open at that point is the one the tab shows as its last row; it is seeded like [seedSingleRecord] does, so
     * its completion replaces that row. False, with the machine untouched in effect, when the file is not a root array or cannot be read.
     */
    private fun seedRootArray(recordMachine: ProfileRecordMachine, file: File, consumedLines: Long?, last: LogEntry?): Boolean {
        val scanned = runCatching {
            file.inputStream().use { input ->
                openLogTextReader(input).use { reader ->
                    if (!startsWithJsonArray(reader)) return@use false
                    val dropped = ArrayList<ProfileRecord>(2)
                    var lines = 0L
                    for (line in reader.lineSequence()) {
                        if (consumedLines != null && lines >= consumedLines) break
                        recordMachine.accept(line, dropped)
                        dropped.clear()
                        lines++
                    }
                    true
                }
            }
        }.getOrDefault(false)
        diagnostics.clear()
        if (!scanned) return false
        val openStart = recordMachine.pendingReplacementStartLine()
        if (recordMachine.hasPendingRecord() && openStart != null && last != null && last.sourceStartLine == openStart) {
            seedEntryIdsByStartLine[openStart] = last.id
        }
        return true
    }

    /**
     * Runs the seeding [seed] deferred (a JSON file: whether it is a root array, and if so the scan that rebuilds the array state).
     * It reads the file, so TailCoordinator calls it on a background thread right after the tail starts and again, outside
     * stateLock, before each batch; [append] calls it too so the state is right whoever comes first. Cheap once done.
     */
    @Synchronized
    fun prepare() {
        val pending = pendingSeed ?: return
        pendingSeed = null
        val recordMachine = machine ?: return
        if (!seedRootArray(recordMachine, pending.file, pending.consumedLines, pending.last)) seedSingleRecord(recordMachine, pending.last)
    }

    private fun seedSingleRecord(recordMachine: ProfileRecordMachine, last: LogEntry?) {
        val start = last?.sourceStartLine ?: return
        val raw = last.rawRecord ?: return
        recordMachine.lineNumber = start - 1
        val emitted = ArrayList<ProfileRecord>(1)
        raw.split('\n').forEach { recordMachine.accept(it, emitted) }
        if (emitted.isEmpty() && recordMachine.hasPendingRecord()) {
            seedEntryIdsByStartLine[recordMachine.pendingReplacementStartLine() ?: start] = last.id
            // The tab already shows this record; only a later change should replace it.
            recordMachine.pendingSnapshot(ArrayList(1))
        }
    }

    private fun seedCriRecords(assembler: CriRecordAssembler, existingEntries: List<LogEntry>) {
        // A still-open stream cannot be more than the reorder bounds behind the displayed suffix.
        val rows = existingEntries.takeLast(MAX_CRI_REORDERED_RECORDS + 2).filter { it.sourceStartLine != null }
        val candidates = rows.filter { it.profileFields["cri_tag"] == "P" }
            .groupBy { it.profileFields["stream"].orEmpty() }
            .values.mapNotNull { sameStream -> sameStream.maxByOrNull { it.sourceStartLine ?: 0L } }
        candidates.forEach { candidate -> seedCriRecord(assembler, rows, candidate) }
    }

    private fun seedCriRecord(assembler: CriRecordAssembler, suffix: List<LogEntry>, candidate: LogEntry) {
        val start = candidate.sourceStartLine ?: return
        val end = candidate.sourceEndLine ?: return
        val raw = candidate.rawRecord ?: return
        val stream = candidate.profileFields["stream"].orEmpty()
        // A historical partial may have been released by the assembler's reorder bounds. Only
        // the latest partial on its stream can still be open, and only while its suffix fits those bounds.
        val followedOnStream = suffix.any { row ->
            row.profileFields["stream"] == stream && (row.sourceStartLine ?: 0L) > start
        }
        if (followedOnStream) return
        val after = suffix.filter { (it.sourceStartLine ?: 0L) > start }
        val charsAfter = after.sumOf { row ->
            (row.rawRecord?.length ?: 0) +
                (row.msg.length.takeIf { com.indagium.model.LogFieldRole.MESSAGE.name in row.profileFields } ?: 0)
        }
        if (assembler.seedPending(raw, start, end, after.size, charsAfter)) {
            seedEntryIdsByStartLine[start] = candidate.id
        }
    }

    companion object {
        /**
         * A parser for [file], which a tab was opened from. A CSV with a header row needs that row, which
         * is line 1 and not part of any later batch, so it is read from the file again. Returns null when
         * the header cannot be found: guessing would turn the first appended data row into the header.
         */
        fun forFile(
            profile: LogFormatProfile,
            sourceClockId: String?,
            file: File,
            existingEntries: List<LogEntry> = emptyList(),
            // How many lines of [file] the tab has consumed; a root-array JSON scan stops there. Null scans the whole file.
            consumedLines: Long? = null,
        ): IncrementalProfileParser? {
            val parser = if (profile.engine != LogFormatEngine.CSV || !profile.hasHeader) {
                IncrementalProfileParser(profile, sourceClockId)
            } else {
                val headers = runCatching {
                    file.inputStream().use { input ->
                        openLogTextReader(input).use { reader -> readCsvHeaderRow(reader.lineSequence(), profile) }
                    }
                }.getOrNull() ?: return null
                IncrementalProfileParser(profile, sourceClockId, headers)
            }
            parser.seed(existingEntries, file, consumedLines)
            return parser
        }
    }
}
