package com.indagium.utils

import com.fasterxml.jackson.core.JsonToken
import com.indagium.model.LogEntry
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import kotlinx.serialization.json.JsonObject
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

private const val SECTION_INDEX_CANCELLATION_ROWS = 128
private const val SECTION_WINDOW_BYTES = 1024 * 1024
private const val UTF8_BOM_LENGTH = 3
private const val ENCODING_SAMPLE_BYTES = 8 * 1024
private const val VALIDATION_CHAR_BUFFER = 8 * 1024
private const val MAX_UTF8_TAIL_BYTES = 4

/** A file as it was when it was indexed: the index is used only while the file still looks exactly like this. */
internal data class MetadataJsonFileStamp(val path: String, val length: Long, val lastModified: Long) {
    fun matches(file: File): Boolean =
        file.absolutePath == path && file.isFile && file.length() == length && file.lastModified() == lastModified

    companion object {
        fun of(file: File): MetadataJsonFileStamp? =
            file.takeIf { it.isFile }?.let { MetadataJsonFileStamp(it.absolutePath, it.length(), it.lastModified()) }
    }
}

/**
 * One section's objects in document order: the global row id of each (4 bytes per object) and the RUNS they sit in. A run is a maximal
 * stretch of directly neighbouring objects of the section (only commas and whitespace between them), normally the whole section array;
 * it breaks wherever anything else lies between two of the section's objects. A run keeps the byte range from its first object's first
 * byte to its last object's last byte, the source line the first object starts on, and its object count (8 + 8 + 4 + 4 bytes), so
 * the section's lines and byte positions are found again by parsing the run, not stored per object.
 */
internal class MetadataJsonSpans(
    val ids: IntArray,
    val runStarts: LongArray,
    val runEnds: LongArray,
    val runStartLines: IntArray,
    val runCounts: IntArray,
) {
    val size: Int get() = ids.size
    val runCount: Int get() = runStarts.size
}

/**
 * Where each section of one plain metadata JSON file lives, so switching sections reads only that section's bytes.
 * It is session-only (never saved) and is used only while the file's path, length and modification time are unchanged.
 */
class MetadataJsonSectionIndex internal constructor(
    internal val stamp: MetadataJsonFileStamp,
    internal val startId: Int,
    internal val sourceClockId: String?,
    internal val sourceLineOffset: Long,
    internal val sectionNameHint: String?,
    internal val sectionIds: Set<String>,
    internal val records: Map<String, MetadataJsonSpans>,
    internal val summaries: Map<String, MetadataJsonSpans>,
) {
    /**
     * The index arrays' size: 4 bytes per object (its id) plus 24 per run, a few runs per section; the per-section maps are small
     * next to it.
     */
    val estimatedBytes: Long
        get() = (records.values + summaries.values).sumOf { it.size.toLong() * BYTES_PER_OBJECT + it.runCount.toLong() * BYTES_PER_RUN }

    fun matches(file: File): Boolean = stamp.matches(file)

    private companion object {
        const val BYTES_PER_OBJECT = 4L
        const val BYTES_PER_RUN = 24L
    }
}

private const val RUN_CAPACITY = 4

/** Collects one section's ids and folds objects of the same scanner run into one run entry. */
private class SpanBuilder(count: Int) {
    private val ids = IntArray(count)
    private var starts = LongArray(RUN_CAPACITY)
    private var ends = LongArray(RUN_CAPACITY)
    private var lines = IntArray(RUN_CAPACITY)
    private var counts = IntArray(RUN_CAPACITY)
    private var runs = 0
    private var lastRun = -1
    private var lastEnd = 0L
    private var next = 0

    @Suppress("ReturnCount")
    fun add(start: Long, end: Long, startLine: Long, id: Int, run: Int): Boolean {
        if (run < 0 || next >= ids.size || startLine !in 1..Int.MAX_VALUE) return false
        if (start < lastEnd || end <= start) return false
        if (runs == 0 || run != lastRun) {
            if (runs == starts.size) grow()
            starts[runs] = start
            lines[runs] = startLine.toInt()
            counts[runs] = 0
            runs++
            lastRun = run
        }
        ends[runs - 1] = end
        counts[runs - 1]++
        ids[next++] = id
        lastEnd = end
        return true
    }

    private fun grow() {
        val capacity = starts.size * 2
        starts = starts.copyOf(capacity)
        ends = ends.copyOf(capacity)
        lines = lines.copyOf(capacity)
        counts = counts.copyOf(capacity)
    }

    fun build(): MetadataJsonSpans? =
        if (next != ids.size) null else MetadataJsonSpans(ids, starts.copyOf(runs), ends.copyOf(runs), lines.copyOf(runs), counts.copyOf(runs))
}

/**
 * Records the ids and runs of a full parse, giving up (null) on anything it cannot reproduce exactly: objects out of byte order,
 * a missing run number or a count that does not add up.
 */
internal class MetadataJsonIndexBuilder(
    private val stamp: MetadataJsonFileStamp,
    private val startId: Int,
    private val sourceClockId: String?,
    private val sourceLineOffset: Long,
    private val sectionNameHint: String?,
) {
    @Suppress("ReturnCount")
    fun build(
        records: List<MetadataJsonPacket>,
        ordered: List<MetadataJsonPacket>,
        packets: List<LogEntry>,
        keptSummaries: List<MetadataJsonSummary>,
        summaryEntries: List<LogEntry>,
        sectionIds: Set<String>,
    ): MetadataJsonSectionIndex? {
        val fallbackName = sectionNameHint ?: sourceClockId
        val sectionOfFile = arrayOfNulls<String>(records.size)
        val idOfFile = IntArray(records.size)
        for (position in ordered.indices) {
            val packet = ordered[position]
            val section = packets[position].profileFields.getValue("section")
            if (packet.fileIndex !in records.indices || records[packet.fileIndex] !== packet) return null
            if (metadataJsonPacketSection(packet, fallbackName) != section) return null
            sectionOfFile[packet.fileIndex] = section
            idOfFile[packet.fileIndex] = packets[position].id
        }
        val counts = HashMap<String, Int>()
        sectionOfFile.forEach { section -> counts.merge(section ?: return null, 1, Int::plus) }
        val builders = counts.mapValues { SpanBuilder(it.value) }
        for (fileIndex in records.indices) {
            val packet = records[fileIndex]
            val builder = builders.getValue(sectionOfFile[fileIndex]!!)
            if (!builder.add(packet.byteStart, packet.byteEnd, packet.startLine, idOfFile[fileIndex], packet.run)) return null
        }
        val summaryCounts = HashMap<String, Int>()
        keptSummaries.forEach { summaryCounts.merge(it.section, 1, Int::plus) }
        val summaryBuilders = summaryCounts.mapValues { SpanBuilder(it.value) }
        for (position in keptSummaries.indices) {
            val summary = keptSummaries[position]
            val entry = summaryEntries[position]
            if (entry.profileFields["section"] != summary.section) return null
            if (!summaryBuilders.getValue(summary.section).add(summary.byteStart, summary.byteEnd, summary.startLine, entry.id, summary.run)) {
                return null
            }
        }
        return MetadataJsonSectionIndex(
            stamp, startId, sourceClockId, sourceLineOffset, sectionNameHint, sectionIds.toSet(),
            builders.mapValues { it.value.build() ?: return null }, summaryBuilders.mapValues { it.value.build() ?: return null },
        )
    }
}

/**
 * Rejects bytes the JDK's replacing decoder would turn into U+FFFD, so a byte-stream parse never sees text the
 * reader-based parse would have read differently. Such a file is parsed the ordinary way.
 */
private class Utf8ValidatingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    private val chars = CharBuffer.allocate(VALIDATION_CHAR_BUFFER)
    private var scratch = ByteArray(ENCODING_SAMPLE_BYTES)
    private var tailLength = 0
    private var finished = false
    private val single = ByteArray(1)

    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and BYTE_MASK

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = super.read(b, off, len)
        validate(b, off, maxOf(count, 0), endOfInput = count < 0)
        return count
    }

    override fun skip(n: Long): Long = 0

    override fun markSupported(): Boolean = false

    private fun validate(bytes: ByteArray, offset: Int, count: Int, endOfInput: Boolean) {
        if (finished) return
        finished = endOfInput
        val total = tailLength + count
        if (scratch.size < total) scratch = scratch.copyOf(maxOf(total, scratch.size * 2))
        System.arraycopy(bytes, offset, scratch, tailLength, count)
        val input = ByteBuffer.wrap(scratch, 0, total)
        while (true) {
            chars.clear()
            val result = decoder.decode(input, chars, endOfInput)
            if (result.isError) result.throwException()
            if (result.isUnderflow) break
        }
        if (endOfInput) {
            chars.clear()
            decoder.flush(chars).let { if (it.isError) it.throwException() }
        }
        tailLength = input.remaining()
        if (tailLength > MAX_UTF8_TAIL_BYTES) throw CharacterCodingException()
        System.arraycopy(scratch, total - tailLength, scratch, 0, tailLength)
    }

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}

/** The length of the UTF-8 BOM (0 or 3) when [file] is plain UTF-8 text the byte parser reads like the text reader, else null. */
private fun plainUtf8PrefixLength(file: File): Int? {
    val sample = file.inputStream().use { it.readNBytes(ENCODING_SAMPLE_BYTES) }
    val bom = when (detectLogTextEncoding(sample)) {
        LogTextEncoding.UTF_8 -> 0
        LogTextEncoding.UTF_8_BOM -> UTF8_BOM_LENGTH
        else -> return null
    }
    // A leading NUL means UTF-16/32 to Jackson's own detection even where the text reader would call it UTF-8.
    if (sample.size < bom + 2 || sample[bom].toInt() == 0 || sample[bom + 1].toInt() == 0) return null
    return bom
}

/**
 * Parses a plain metadata JSON file from its bytes so the section index can be built in the same pass, giving the same rows as the
 * reader-based parse. Returns null (parse the ordinary way, no index) for anything that is not plain valid UTF-8 JSON read cleanly.
 */
internal fun parseMetadataJsonFile(
    file: File,
    profile: LogFormatProfile,
    startId: Int,
    sourceClockId: String?,
    sectionNameHint: String?,
): ParsedLog? {
    val stamp = MetadataJsonFileStamp.of(file) ?: return null
    val prefix = try {
        plainUtf8PrefixLength(file)
    } catch (_: IOException) {
        null
    } ?: return null
    val scanned = try {
        FileInputStream(file).use { raw ->
            if (prefix > 0 && raw.skip(prefix.toLong()) != prefix.toLong()) return null
            val parser = METADATA_JSON_FACTORY.createParser(Utf8ValidatingInputStream(raw))
            scanMetadataJson(parser, Int.MAX_VALUE, prefix.toLong())
        }
    } catch (_: IOException) {
        return null
    }
    // A damaged or oversized document reports its diagnostics from the ordinary parse, which stays the one source of those words.
    if (scanned.diagnostics.isNotEmpty() || !stamp.matches(file)) return null
    return buildMetadataJsonLog(scanned, profile, startId, sourceClockId, 0, sectionNameHint, stamp)
}

/**
 * Serves bytes [from, to) of a file as the elements of a JSON array: a virtual "[", the bytes, a virtual "]". A run holds objects
 * separated by commas, which a parser accepts only inside an array, so this lets one parser read the whole run. The "[" has no line break,
 * so the parser's line numbers are the run's own.
 */
private class RunInputStream(
    private val channel: FileChannel,
    private val buffer: ByteArray,
    private var position: Long,
    private val end: Long,
) : InputStream() {
    private var buffered = 0
    private var bufferedFrom = 0
    private var prefixPending = true
    private var suffixPending = true
    private val single = ByteArray(1)

    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and BYTE_MASK

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var written = 0
        if (prefixPending) {
            b[off] = '['.code.toByte()
            prefixPending = false
            written = 1
        }
        while (written < len) {
            if (bufferedFrom == buffered && !refill()) break
            val take = minOf(len - written, buffered - bufferedFrom)
            System.arraycopy(buffer, bufferedFrom, b, off + written, take)
            bufferedFrom += take
            written += take
        }
        if (written < len && suffixPending) {
            b[off + written] = ']'.code.toByte()
            suffixPending = false
            written++
        }
        return if (written == 0) -1 else written
    }

    private fun refill(): Boolean {
        if (position >= end) return false
        val target = ByteBuffer.wrap(buffer, 0, minOf(buffer.size.toLong(), end - position).toInt())
        while (target.hasRemaining()) {
            val read = channel.read(target, position + target.position())
            if (read < 0) throw EOFException("The indexed JSON run is past the end of the file.")
        }
        buffered = target.position()
        bufferedFrom = 0
        position += buffered
        return true
    }

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}

/**
 * Parses the objects of every run of [spans] in order, one parser per run, and hands each to [accept] with the global source lines the
 * full parse gave it: the run's first line plus the parser's own line (1-based) minus one, so the parser counts \n, \r and \r\n in
 * the separators between objects exactly as the full parse did. False means a run did not read back as the index promised.
 */
@Suppress("ReturnCount")
private fun readRuns(
    channel: FileChannel,
    buffer: ByteArray,
    spans: MetadataJsonSpans,
    accept: (value: JsonObject, startLine: Long, endLine: Long, id: Int) -> Boolean,
): Boolean {
    var objectIndex = 0
    for (run in 0 until spans.runCount) {
        val lineBase = spans.runStartLines[run] - 1L
        val stream = RunInputStream(channel, buffer, spans.runStarts[run], spans.runEnds[run])
        METADATA_JSON_FACTORY.createParser(stream).use { parser ->
            if (parser.nextToken() != JsonToken.START_ARRAY) return false
            repeat(spans.runCounts[run]) {
                if (objectIndex % SECTION_INDEX_CANCELLATION_ROWS == 0) checkParserCancellation()
                val located = MetadataJsonScanner(parser, Int.MAX_VALUE).readLocatedFragment(emptyList()) ?: return false
                val value = located.element as? JsonObject ?: return false
                if (objectIndex >= spans.size || !accept(value, lineBase + located.startLine, lineBase + located.endLine, spans.ids[objectIndex])) {
                    return false
                }
                objectIndex++
            }
            if (parser.nextToken() != JsonToken.END_ARRAY) return false
        }
    }
    return objectIndex == spans.size
}

/**
 * The rows of one section, read from the bytes the index points at: the same rows, ids and source lines the full parse gives that
 * section. Null means the index cannot be used (changed file, unknown section, unreadable run); parse the ordinary way then.
 */
@Suppress("ReturnCount", "TooGenericExceptionCaught")
internal fun loadMetadataJsonSection(
    file: File,
    index: MetadataJsonSectionIndex,
    sectionId: String,
    profile: LogFormatProfile,
): List<LogEntry>? {
    if (profile.engine != LogFormatEngine.METADATA_JSON || sectionId !in index.sectionIds || !index.matches(file)) return null
    validateLogFormatProfile(profile)
    val viewProfile = metadataJsonPresentationProfile(profile)
    val compactor = ProfileRowCompactor()
    try {
        RandomAccessFile(file, "r").use { handle ->
            val channel = handle.channel
            val buffer = ByteArray(SECTION_WINDOW_BYTES)
            val rows = ArrayList<LogEntry>(index.records[sectionId]?.size ?: 0)
            index.records[sectionId]?.let { spans ->
                val complete = readRuns(channel, buffer, spans) { value, startLine, endLine, id ->
                    isMetadataRecord(value) && rows.add(
                        metadataJsonEntry(
                            MetadataJsonPacket(value, sectionId, startLine, endLine), id, index.sourceClockId, index.sourceLineOffset,
                            compactor, viewProfile, index.sectionNameHint,
                        ),
                    )
                }
                if (!complete) return null
            }
            // The full parse numbers records by time across the whole document; a section's rows follow in that order.
            rows.sortBy { it.id }
            index.summaries[sectionId]?.let { spans ->
                val complete = readRuns(channel, buffer, spans) { value, startLine, endLine, id ->
                    !isMetadataRecord(value) && rows.add(
                        metadataJsonObjectEntry(
                            MetadataJsonSummary(value, sectionId, startLine, endLine), id, index.sourceLineOffset, viewProfile, compactor,
                        ),
                    )
                }
                if (!complete) return null
            }
            return rows
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        return null
    } catch (_: IllegalStateException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
}
