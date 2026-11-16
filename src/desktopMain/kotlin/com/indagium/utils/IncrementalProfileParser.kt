package com.indagium.utils

import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import java.io.File

private const val TAIL_CANCELLATION_INTERVAL = 128L

/**
 * Parses a growing file batch by batch and keeps the state a record that spans lines needs: an open
 * CSV quote, the CSV header row, a multiline regex record that has not seen its next start line, and
 * CRI partial fragments. One instance per tailed tab; callers serialize access (the tail holds the
 * state lock). It reuses the same record machines as the whole-file parse, so any split of a file
 * yields the rows that parsing the whole file would.
 *
 * Engines whose record is one physical line (plain text, JSON Lines, syslog, logfmt, single-line
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

    init {
        require(profile.engine != LogFormatEngine.DLT) { "DLT cannot be parsed incrementally." }
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
    fun append(lines: List<String>, startId: Int, sourceLineOffset: Long): ParsedLog {
        require(sourceLineOffset >= 0) { "Source line offset cannot be negative." }
        if (machine == null && cri == null) return parseProfileLines(lines, profile, startId, sourceClockId, sourceLineOffset)
        val ready = ArrayList<ProfileRecord>()
        var lineNumber = sourceLineOffset
        if (machine != null) {
            machine.lineNumber = sourceLineOffset
            for (line in lines) machine.accept(line, ready)
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
    fun flush(startId: Int, sourceLineOffset: Long): ParsedLog {
        val ready = ArrayList<ProfileRecord>()
        machine?.finish(ready)
        cri?.finish()?.mapTo(ready) { it.toProfileRecord() }
        return result(ready, startId, sourceLineOffset)
    }

    private fun result(records: List<ProfileRecord>, startId: Int, lineCount: Long): ParsedLog {
        val entries = ArrayList<LogEntry>(records.size)
        records.forEachIndexed { index, record ->
            entries += recordToEntry(startId + index, record, profile, timeParser, compactor)
        }
        return ParsedLog(LogFormat.LOGCAT, entries, profile, diagnostics.toList(), sourceClockId, lineCount)
    }

    companion object {
        /**
         * A parser for [file], which a tab was opened from. A CSV with a header row needs that row, which
         * is line 1 and not part of any later batch, so it is read from the file again. Returns null when
         * the header cannot be found: guessing would turn the first appended data row into the header.
         */
        fun forFile(profile: LogFormatProfile, sourceClockId: String?, file: File): IncrementalProfileParser? {
            if (profile.engine != LogFormatEngine.CSV || !profile.hasHeader) {
                return IncrementalProfileParser(profile, sourceClockId)
            }
            val headers = runCatching {
                file.inputStream().use { input ->
                    openLogTextReader(input).use { reader -> readCsvHeaderRow(reader.lineSequence(), profile) }
                }
            }.getOrNull() ?: return null
            return IncrementalProfileParser(profile, sourceClockId, headers)
        }
    }
}
