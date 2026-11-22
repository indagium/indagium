package com.indagium.utils

import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
 *
 * A JSON file whose root is an array defers part of its seeding to a scan of the file (see [prepare]). That scan runs on a
 * private machine and never under this parser's monitor, so [cancelSeeding] and [flush] never wait for it: a stop or a rotation
 * under the state lock gives the scan up instead.
 */
internal class IncrementalProfileParser(
    private val profile: LogFormatProfile,
    private val sourceClockId: String? = null,
    csvHeaders: List<String>? = null,
) {
    // The list the held [machine] reports into. Replaced together with the machine when a root-array scan is adopted.
    private var diagnostics = mutableListOf<String>()
    private var machine: ProfileRecordMachine?
    private val cri: CriRecordAssembler?
    private val timeParser: ProfileTimeParser
    private val compactor = ProfileRowCompactor()

    // Row ids waiting for the records that will replace them, by the physical line those records start on (in order).
    private val seedEntryIdsByStartLine = mutableMapOf<Long, ArrayDeque<Int>>()

    private class PendingSeed(
        val file: File,
        val consumedLines: Long?,
        val last: LogEntry?,
        val replacement: LineReplacement?,
        val jsonTailState: JsonTailState?,
    )

    /**
     * The unfinished last line of the file the tab was parsed from, which the tailer re-delivers whole: the first line [append]
     * receives replaces physical line [line] instead of following it. [rows] are the rows that showed that line, in order, the
     * first of which may start earlier (a record that line continued); [last] is the tab's last row, whichever line it ends on.
     */
    internal class LineReplacement(val line: Long, val rows: List<LogEntry>, val last: LogEntry?)

    // The seeding [seed] left for [prepare]: it reads the file. Written before the parser is published, read under the monitor.
    private var pendingSeed: PendingSeed? = null

    // Where the deferred seeding stands. PENDING -> SCANNING (one thread) -> DONE, or -> CANCELLED from either of the first two
    // (the scan result is then discarded). CANCELLED restores the published framing when the first line arrives. A parser without
    // deferred seeding is DONE from the start.
    private enum class SeedPhase { PENDING, SCANNING, DONE, CANCELLED }

    private val seedPhase = AtomicReference(SeedPhase.DONE)
    private val seedSettled = CountDownLatch(1)

    // Guarded by the monitor.
    private var replacement: LineReplacement? = null

    /** True when the first line this parser receives replaces the last physical line already consumed (see [forFile]). */
    val replacesLastLine: Boolean get() = replacement != null

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
     *
     * When [replacesLastLine] holds, the first of [lines] is the re-read of line [sourceLineOffset] (the file's last line, which
     * the tab showed unfinished), not a new one: the rows that line made are replaced and the line count does not move for it.
     */
    fun append(lines: List<String>, startId: Int, sourceLineOffset: Long): ParsedLog {
        require(sourceLineOffset >= 0) { "Source line offset cannot be negative." }
        // Outside the monitor: a scan another thread is running is waited for here, never while holding what its adoption needs.
        prepare()
        return appendSeeded(lines, startId, sourceLineOffset)
    }

    @Synchronized
    private fun appendSeeded(lines: List<String>, startId: Int, sourceLineOffset: Long): ParsedLog {
        seedIfCancelled()
        val plan = replacement?.takeIf { it.line == sourceLineOffset && lines.isNotEmpty() }
        replacement = null
        val offset = if (plan != null) sourceLineOffset - 1 else sourceLineOffset
        val fed = if (plan != null && plan.line == 1L) lines.mapIndexed { i, l -> if (i == 0) l.removePrefix(BYTE_ORDER_MARK) else l } else lines
        if (machine == null && cri == null) {
            val parsed = parseProfileLines(fed, profile, startId, sourceClockId, offset)
            if (plan == null) {
                replacedEntryIds = emptySet()
                return parsed
            }
            return reidentifyReplacedRows(parsed, plan, startId)
        }
        val ready = ArrayList<ProfileRecord>()
        var lineNumber = offset
        val recordMachine = machine
        if (recordMachine != null) {
            recordMachine.lineNumber = offset
            for (line in fed) recordMachine.accept(line, ready)
            // Show the newest record now; if more lines join it later, the same row is replaced.
            recordMachine.pendingSnapshot(ready)
            lineNumber = recordMachine.lineNumber
        } else if (cri != null) {
            for (raw in fed) {
                lineNumber++
                if (lineNumber % TAIL_CANCELLATION_INTERVAL == 0L) checkParserCancellation()
                cri.accept(raw, lineNumber).mapTo(ready) { it.toProfileRecord() }
            }
        }
        return result(ready, startId, lineNumber)
    }

    /**
     * A single-line engine's rows of the re-read line take the ids of the rows the unfinished line made (in order); any further
     * row is new. Ids of the rows after them stay consecutive from [startId].
     */
    private fun reidentifyReplacedRows(parsed: ParsedLog, plan: LineReplacement, startId: Int): ParsedLog {
        val ids = ArrayDeque(plan.rows.filter { it.sourceStartLine == plan.line }.map { it.id })
        val replaced = linkedSetOf<Int>()
        var nextId = startId
        val entries = parsed.entries.map { entry ->
            val reused = if (entry.sourceStartLine == plan.line) ids.removeFirstOrNull() else null
            if (reused != null) replaced += reused
            entry.copy(id = reused ?: nextId++)
        }
        replacedEntryIds = replaced
        return parsed.copy(entries = entries)
    }

    /** Returns the record still held when tailing stops; it is complete as far as the file goes. */
    fun flush(startId: Int, sourceLineOffset: Long): ParsedLog {
        // Seeding that has not completed means no line was appended since the tab's own parse, which already ended (and showed) the
        // open record. Giving it up here, without waiting for a running scan, is what lets a stop or a rotation under the state
        // lock return at once.
        if (cancelSeeding()) return emptyBatch(sourceLineOffset)
        return flushSeeded(startId, sourceLineOffset)
    }

    @Synchronized
    private fun flushSeeded(startId: Int, sourceLineOffset: Long): ParsedLog {
        // The re-delivered last line never came: the rows already show the file as far as it went, and what the machine holds is
        // that record without its unfinished line.
        if (replacement != null) return emptyBatch(sourceLineOffset)
        val jsonTailState = (machine as? JsonRecordMachine)?.tailState()
        val ready = ArrayList<ProfileRecord>()
        machine?.finish(ready)
        cri?.finish()?.mapTo(ready) { it.toProfileRecord() }
        return result(ready, startId, sourceLineOffset).copy(jsonTailState = jsonTailState)
    }

    // Touches no parser state, so it is safe while a scan or an append is in flight.
    private fun emptyBatch(lineCount: Long): ParsedLog =
        ParsedLog(LogFormat.LOGCAT, emptyList(), profile, emptyList(), sourceClockId, lineCount)

    private fun result(records: List<ProfileRecord>, startId: Int, lineCount: Long): ParsedLog {
        val entries = ArrayList<LogEntry>(records.size)
        val replaced = linkedSetOf<Int>()
        var nextId = startId
        for (record in records) {
            // The line locates a previously seeded row, but several new JSON records may share it.
            val seededId = takeSeedId(record.sourceStartLine)
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
                entries.lastOrNull { it.sourceStartLine == start }?.id ?: seedEntryIdsByStartLine[start]?.firstOrNull()
            }
            seedEntryIdsByStartLine.clear()
            if (openStart != null && openId != null) addSeedId(openStart, openId)
        }
        replacedEntryIds = replaced
        return ParsedLog(
            LogFormat.LOGCAT, entries, profile, diagnostics.toList(), sourceClockId, lineCount,
            jsonTailState = (machine as? JsonRecordMachine)?.tailState(),
        )
    }

    private fun takeSeedId(startLine: Long): Int? {
        val ids = seedEntryIdsByStartLine[startLine] ?: return null
        val id = ids.removeFirstOrNull()
        if (ids.isEmpty()) seedEntryIdsByStartLine.remove(startLine)
        return id
    }

    private fun addSeedId(startLine: Long, id: Int) {
        seedEntryIdsByStartLine.getOrPut(startLine) { ArrayDeque() }.addLast(id)
    }

    /**
     * Seeds an open record from the published rows. JSON can defer rebuilding the full scanner state to [prepare].
     * False when [lineReplacement] was given and this engine's state cannot be rebuilt without the replaced line.
     */
    private fun seed(
        existingEntries: List<LogEntry>,
        file: File?,
        consumedLines: Long?,
        lineReplacement: LineReplacement?,
        jsonTailState: JsonTailState?,
    ): Boolean {
        replacement = lineReplacement
        when (profile.engine) {
            LogFormatEngine.JSON_LINES ->
                machine?.let { recordMachine ->
                    // A document whose root array is still open at EOF keeps state no row carries (the array itself), so the rows
                    // alone cannot rebuild it: the file is streamed once. That read is deferred to [prepare] so it never runs on the
                    // thread that starts the tail (the UI thread for the Tail menu item) or under stateLock.
                    val last = existingEntries.lastOrNull()
                    if (file == null) {
                        return seedLastRow(recordMachine, last, lineReplacement)
                    }
                    // The re-delivered line is not part of the state the scan rebuilds.
                    val limit = if (lineReplacement != null) consumedLines?.minus(1) ?: (lineReplacement.line - 1) else consumedLines
                    pendingSeed = PendingSeed(file, limit, last, lineReplacement, jsonTailState)
                    seedPhase.set(SeedPhase.PENDING)
                }
            LogFormatEngine.REGEX, LogFormatEngine.CSV,
            LogFormatEngine.SYSLOG_RFC5424, LogFormatEngine.SYSLOG_RFC3164,
            ->
                machine?.let { return seedLastRow(it, existingEntries.lastOrNull(), lineReplacement) }
            LogFormatEngine.CRI -> cri?.let {
                if (lineReplacement != null) return seedCriReplacingLine(it, existingEntries, lineReplacement)
                seedCriRecords(it, existingEntries)
            }
            else -> Unit
        }
        return true
    }

    private fun seedLastRow(recordMachine: ProfileRecordMachine, last: LogEntry?, lineReplacement: LineReplacement?): Boolean {
        if (lineReplacement == null) {
            seedSingleRecord(recordMachine, last)
            return true
        }
        return seedWithoutReplacedLine(recordMachine, lineReplacement)
    }

    /**
     * Rebuilds [recordMachine] for a file whose first character is `[`: the first [limit] lines (the whole file when null) are
     * streamed through a machine of its own and every record it emits is dropped, so the memory held is one record and no rows are
     * kept. It is private to the scan so that nothing the parser holds is touched until the scan is adopted: a cancelled scan leaves
     * the parser as it was. The element still open at that point is the one the tab shows as its last row; the caller seeds it like
     * [seedSingleRecord] does, so its completion replaces that row. Null when the file is not a root array, cannot be read, or the
     * scan was cancelled.
     */
    private fun scanRootArray(pending: PendingSeed): RootArrayScan? {
        val scanDiagnostics = mutableListOf<String>()
        val scratch = newTailRecordMachine(profile, scanDiagnostics, null) ?: return null
        val scanned = runCatching {
            pending.file.inputStream().use { input ->
                openLogTextReader(input).use { reader ->
                    if (!startsWithJsonArray(reader)) return@use false
                    val dropped = ArrayList<ProfileRecord>(2)
                    var lines = 0L
                    for (line in reader.lineSequence()) {
                        if (pending.consumedLines != null && lines >= pending.consumedLines) break
                        // A scan someone gave up on stops reading at the next line.
                        if (seedPhase.get() != SeedPhase.SCANNING) return@use false
                        seedScanHookForTesting?.invoke(pending.file, lines)
                        scratch.accept(line, dropped)
                        dropped.clear()
                        lines++
                    }
                    seedPhase.get() == SeedPhase.SCANNING
                }
            }
        }.getOrDefault(false)
        if (!scanned) return null
        scanDiagnostics.clear()
        return RootArrayScan(scratch, scanDiagnostics)
    }

    private class RootArrayScan(val machine: ProfileRecordMachine, val diagnostics: MutableList<String>)

    /**
     * Completes the seeding [seed] deferred (a JSON file: whether it is a root array, and if so the scan that rebuilds the array
     * state). It reads the file, so TailCoordinator calls it on a background thread right after the tail starts and again, outside
     * stateLock, before each batch; [append] calls it too so the state is right whoever comes first. Cheap once done.
     *
     * Only one thread scans; another caller waits for it (it must not hold stateLock). A scan that [cancelSeeding] gave up on
     * ends this call with the scan abandoned. The first [append] then uses the published framing and rows.
     */
    fun prepare() {
        while (true) {
            when (seedPhase.get()) {
                SeedPhase.DONE, SeedPhase.CANCELLED -> return
                SeedPhase.PENDING -> if (seedPhase.compareAndSet(SeedPhase.PENDING, SeedPhase.SCANNING)) {
                    runSeedScan()
                    return
                }
                SeedPhase.SCANNING -> seedSettled.await()
            }
        }
    }

    private fun runSeedScan() {
        try {
            val pending = pendingSeed
            val scan = if (pending == null) null else runCatching { scanRootArray(pending) }.getOrNull()
            synchronized(this) {
                // Lost to a cancel (stop, rotation, close) when this fails: the scan's result is dropped untouched.
                if (seedPhase.compareAndSet(SeedPhase.SCANNING, SeedPhase.DONE)) adoptSeeding(pending, scan)
            }
        } finally {
            // An error must not leave the phase at SCANNING, or every waiter would wait forever.
            seedPhase.compareAndSet(SeedPhase.SCANNING, SeedPhase.CANCELLED)
            seedSettled.countDown()
        }
    }

    // Caller holds the monitor and has just moved the phase to DONE.
    private fun adoptSeeding(pending: PendingSeed?, scan: RootArrayScan?) {
        pendingSeed = null
        val recordMachine = machine ?: return
        if (pending == null) return
        if (scan == null) {
            seedJsonFromPublishedRows(recordMachine, pending)
            return
        }
        machine = scan.machine
        diagnostics = scan.diagnostics
        val openStart = scan.machine.pendingReplacementStartLine()
        val last = pending.last
        val plan = pending.replacement
        if (plan != null) {
            // The scan stopped before the re-delivered line: the rows that line made are replaced by what it yields.
            plan.rows.forEach { addSeedId(it.sourceStartLine ?: plan.line, it.id) }
        } else if (scan.machine.hasPendingRecord() && openStart != null && last != null && last.sourceStartLine == openStart) {
            addSeedId(openStart, last.id)
        }
    }

    /**
     * Waits at most [timeoutMs] for deferred seeding to settle (finish, or be given up on). For a caller that would rather have the
     * exact state when it is cheap to get, and then [cancelSeeding] when it is not. Never call it under stateLock.
     */
    fun awaitSeeding(timeoutMs: Long) {
        if (seedPhase.get() != SeedPhase.DONE) seedSettled.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Gives up on seeding that has not completed: a scan not started never will, a scan running ends at its next line and its
     * result is dropped. Never waits. The first [append] uses the published framing and rows. True when seeding had not completed (and
     * so no line was appended yet), false once it is done.
     */
    fun cancelSeeding(): Boolean {
        while (true) {
            when (val phase = seedPhase.get()) {
                SeedPhase.PENDING, SeedPhase.SCANNING -> if (seedPhase.compareAndSet(phase, SeedPhase.CANCELLED)) {
                    seedSettled.countDown()
                    return true
                }
                SeedPhase.CANCELLED -> return true
                SeedPhase.DONE -> return false
            }
        }
    }

    // Caller holds the monitor. A cancelled scan leaves the machine untouched.
    private fun seedIfCancelled() {
        if (seedPhase.get() != SeedPhase.CANCELLED) return
        val pending = pendingSeed
        pendingSeed = null
        if (pending != null) machine?.let { seedJsonFromPublishedRows(it, pending) }
        seedPhase.set(SeedPhase.DONE)
    }

    private fun seedJsonFromPublishedRows(recordMachine: ProfileRecordMachine, pending: PendingSeed) {
        val plan = pending.replacement
        val state = pending.jsonTailState
        if (state == null || recordMachine !is JsonRecordMachine) {
            seedLastRow(recordMachine, pending.last, plan)
            return
        }
        val boundary = if (plan == null) state.atEnd else state.beforeLastLine
        recordMachine.resumeArray(boundary.insideArray)
        if (plan != null && plan.rows.isNotEmpty()) {
            seedWithoutReplacedLine(recordMachine, plan)
        } else if (boundary.pendingRecordStart != null) {
            seedSingleRecord(recordMachine, pending.last?.takeIf { it.sourceStartLine == boundary.pendingRecordStart })
        }
    }

    private fun seedSingleRecord(recordMachine: ProfileRecordMachine, last: LogEntry?) {
        val start = last?.sourceStartLine ?: return
        val raw = last.rawRecord ?: return
        recordMachine.lineNumber = start - 1
        val emitted = ArrayList<ProfileRecord>(1)
        raw.split('\n').forEach { recordMachine.accept(it, emitted) }
        if (emitted.isEmpty() && recordMachine.hasPendingRecord()) {
            addSeedId(recordMachine.pendingReplacementStartLine() ?: start, last.id)
            // The tab already shows this record; only a later change should replace it.
            recordMachine.pendingSnapshot(ArrayList(1))
        }
    }

    /**
     * Seeds [recordMachine] as [seedSingleRecord] does, but without the physical line [plan] replaces: the record that line
     * continued is rebuilt from the lines before it (and still shown by its row, which its new state then replaces), and every row
     * that line made waits for the record that takes its place. False when the rows do not allow it (a record whose lines are not
     * all in its raw text, or one the machine closes without them).
     */
    private fun seedWithoutReplacedLine(recordMachine: ProfileRecordMachine, plan: LineReplacement): Boolean {
        val first = plan.rows.firstOrNull()
        if (first == null) {
            // The line made no row (it was blank): the machine is what the tab's last row leaves it, as without a replacement.
            seedSingleRecord(recordMachine, plan.last)
            return true
        }
        val firstStart = first.sourceStartLine
        if (firstStart != null && firstStart < plan.line) {
            val lines = first.rawRecord?.split('\n') ?: return false
            if (lines.size.toLong() != plan.line - firstStart + 1) return false
            recordMachine.lineNumber = firstStart - 1
            val emitted = ArrayList<ProfileRecord>(1)
            lines.dropLast(1).forEach { recordMachine.accept(it, emitted) }
            if (emitted.isNotEmpty() || !recordMachine.hasPendingRecord()) return false
            // Unlike [seedSingleRecord], the held record is NOT marked as shown: the row shows it with the unfinished line, so its
            // state without that line must still reach the row, whatever the re-read line turns out to be.
        }
        plan.rows.forEach { addSeedId(it.sourceStartLine ?: plan.line, it.id) }
        return true
    }

    /**
     * Seeds [assembler] without the physical line [plan] replaces. The row that line made (CRI is only replaced when it is one row)
     * either was that line alone, which then simply waits for the record that takes its place, or ended a record of earlier
     * fragments, which is rebuilt as a still-open partial record from them.
     */
    private fun seedCriReplacingLine(assembler: CriRecordAssembler, existing: List<LogEntry>, plan: LineReplacement): Boolean {
        val shown = plan.rows.singleOrNull()
        val rows = if (shown == null) {
            existing
        } else if ((shown.sourceStartLine ?: plan.line) >= plan.line) {
            existing.filter { it.id != shown.id }
        } else {
            val fragments = shown.rawRecord?.split('\n') ?: return false
            if (fragments.size < 2) return false
            val openMetadata = shown.recordMetadata?.let { meta ->
                meta.copy(
                    rawRecord = fragments.dropLast(1).joinToString("\n"),
                    sourceEndLine = plan.line - 1,
                    profileFields = meta.profileFields + ("cri_tag" to "P"),
                )
            }
            existing.map { if (it.id == shown.id) it.copy(recordMetadata = openMetadata) else it }
        }
        seedCriRecords(assembler, rows)
        plan.rows.forEach { row ->
            val start = row.sourceStartLine ?: plan.line
            if (seedEntryIdsByStartLine[start]?.contains(row.id) != true) addSeedId(start, row.id)
        }
        return true
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
            addSeedId(start, candidate.id)
        }
    }

    companion object {
        /** A test seam: called with the file and the 0-based line index before each line the root-array scan reads. */
        @Volatile
        internal var seedScanHookForTesting: ((File, Long) -> Unit)? = null

        /**
         * A parser for [file], which a tab was opened from. A CSV with a header row needs that row, which
         * is line 1 and not part of any later batch, so it is read from the file again. Returns null when
         * the header cannot be found: guessing would turn the first appended data row into the header.
         *
         * [unfinishedLine] is set when the file did not end with a newline: the tab's parse consumed that last line as it stood, and
         * the tailer will re-deliver it whole. The parser then treats the first line it is given as that line's replacement, if the
         * tab's rows allow it ([replacesLastLine]); otherwise it is the ordinary parser and the caller must tail from the end.
         *
         * That is only proven when the file has not changed since the tab was parsed: [parsedByteLength] (the file's length measured
         * before that parse) must equal the length [unfinishedLine] was read at. Without it, an unfinished line that merely looks like
         * the tab's last row (a line appended since, or a repeated line) could be mistaken for it.
         */
        fun forFile(
            profile: LogFormatProfile,
            sourceClockId: String?,
            file: File,
            existingEntries: List<LogEntry> = emptyList(),
            // How many lines of [file] the tab has consumed; a root-array JSON scan stops there. Null scans the whole file.
            consumedLines: Long? = null,
            unfinishedLine: TrailingPartialLine? = null,
            parsedByteLength: Long? = null,
            jsonTailState: JsonTailState? = null,
        ): IncrementalProfileParser? {
            if (unfinishedLine != null && parsedByteLength != null && unfinishedLine.fileLength == parsedByteLength) {
                val plan = planLineReplacement(profile, existingEntries, consumedLines, unfinishedLine)
                if (plan != null) {
                    // A header row that is itself the unfinished line is the first line the new parser is given.
                    val headerIsUnfinished = profile.engine == LogFormatEngine.CSV && profile.hasHeader && plan.line == 1L
                    val replacing = if (headerIsUnfinished) IncrementalProfileParser(profile, sourceClockId) else parserFor(profile, sourceClockId, file)
                    if (replacing != null && replacing.seed(existingEntries, file, consumedLines, plan, jsonTailState)) return replacing
                }
            }
            val parser = parserFor(profile, sourceClockId, file) ?: return null
            parser.seed(existingEntries, file, consumedLines, null, jsonTailState)
            return parser
        }

        private fun parserFor(profile: LogFormatProfile, sourceClockId: String?, file: File): IncrementalProfileParser? {
            if (profile.engine != LogFormatEngine.CSV || !profile.hasHeader) return IncrementalProfileParser(profile, sourceClockId)
            val headers = runCatching {
                file.inputStream().use { input ->
                    openLogTextReader(input).use { reader -> readCsvHeaderRow(reader.lineSequence(), profile) }
                }
            }.getOrNull() ?: return null
            return IncrementalProfileParser(profile, sourceClockId, headers)
        }

        /**
         * The rows that showed the file's unfinished last line, or null when that line should not be treated as replaceable: rows
         * without line positions, or a last row whose text is not the file's unfinished line (the file moved on since the tab was
         * parsed, so what it holds is not what the tailer will re-read).
         */
        private fun planLineReplacement(
            profile: LogFormatProfile,
            existing: List<LogEntry>,
            consumedLines: Long?,
            unfinished: TrailingPartialLine,
        ): LineReplacement? {
            val line = consumedLines ?: existing.lastOrNull()?.sourceEndLine ?: return null
            if (line <= 0L) return null
            val rows = rowsTouchingLine(profile, existing, line) ?: return null
            val reread = unfinished.text.trim()
            val lastRow = rows.lastOrNull()
            // No row shows the line: fine for a blank one (engines skip those) and for the one line of a file (a header row).
            val shown = if (lastRow == null) {
                reread.isEmpty() || (line == 1L && unfinished.startOffset == 0L)
            } else {
                showsLine(profile, lastRow, reread)
            }
            return if (shown) LineReplacement(line, rows, existing.lastOrNull()) else null
        }

        /** The rows that end on or after [line], in order; null when a row has no position, or CRI left several of them. */
        private fun rowsTouchingLine(profile: LogFormatProfile, existing: List<LogEntry>, line: Long): List<LogEntry>? {
            val cri = profile.engine == LogFormatEngine.CRI
            // CRI reorders its records by start line, so the row that ends on the last line need not be the last one; the one row
            // whose state can be rebuilt is then looked for among the rows the reorder buffers can still hold back.
            val window = if (cri) existing.takeLast(MAX_CRI_REORDERED_RECORDS + 2) else existing
            val rows = ArrayList<LogEntry>()
            for (entry in window.asReversed()) {
                val end = entry.sourceEndLine ?: return null
                if (end >= line) rows += entry else if (!cri) break
            }
            rows.reverse()
            return rows.takeUnless { cri && it.size > 1 }
        }

        // A sanity check on top of the unchanged-file proof (the file still has the length measured before the tab's parse, which is
        // what shows the line is the tab's own last one): the text the row ends with is the unfinished line. A JSON line may hold
        // several events (`{"a":1}{"b":2}{"c":`), of which the last row shows only the last, and its raw text may omit what
        // delimits an element of a root array (the comma after it or before it, the closing `]`), so for that engine the row's
        // text need only be contained in the line, those delimiters left out. Every row that touches the line is replaced.
        private fun showsLine(profile: LogFormatProfile, row: LogEntry, reread: String): Boolean {
            val shown = row.rawRecord?.substringAfterLast('\n')?.trim() ?: return false
            if (shown == reread) return true
            if (profile.engine != LogFormatEngine.JSON_LINES) return false
            if (shown.isNotEmpty() && reread.contains(shown)) return true
            val bare = withoutArrayDelimiters(shown)
            return bare.isNotEmpty() && withoutArrayDelimiters(reread).contains(bare)
        }

        private fun withoutArrayDelimiters(text: String): String =
            text.trim().removePrefix(",").trimStart().trimEnd(',', ']', ' ', '\t')
    }
}

private const val BYTE_ORDER_MARK = "\uFEFF"
