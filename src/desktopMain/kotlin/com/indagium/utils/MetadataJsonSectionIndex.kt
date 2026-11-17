package com.indagium.utils

import com.indagium.model.LogEntry
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import kotlinx.serialization.json.JsonElement
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
 * The byte spans, source lines and global row ids of one section's objects, in document order, as parallel arrays
 * (8 + 4 + 4 + 4 + 4 = 24 bytes per object).
 */
internal class MetadataJsonSpans(
    val starts: LongArray,
    val lengths: IntArray,
    val startLines: IntArray,
    val endLines: IntArray,
    val ids: IntArray,
) {
    val size: Int get() = ids.size
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
    /** The index arrays' size; the per-section maps and ids are small next to it. */
    val estimatedBytes: Long
        get() = (records.values + summaries.values).sumOf { it.size.toLong() * BYTES_PER_SPAN }

    fun matches(file: File): Boolean = stamp.matches(file)

    private companion object {
        const val BYTES_PER_SPAN = 24L
    }
}

private class SpanBuilder(count: Int) {
    val starts = LongArray(count)
    val lengths = IntArray(count)
    val startLines = IntArray(count)
    val endLines = IntArray(count)
    val ids = IntArray(count)
    var next = 0

    fun add(start: Long, end: Long, startLine: Long, endLine: Long, id: Int): Boolean {
        val length = end - start
        if (length <= 0 || length > Int.MAX_VALUE || startLine !in 0..Int.MAX_VALUE || endLine !in 0..Int.MAX_VALUE) return false
        starts[next] = start
        lengths[next] = length.toInt()
        startLines[next] = startLine.toInt()
        endLines[next] = endLine.toInt()
        ids[next] = id
        next++
        return true
    }

    fun build() = MetadataJsonSpans(starts, lengths, startLines, endLines, ids)
}

/** Records the spans of a full parse, giving up (null) on anything it cannot reproduce exactly. */
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
            if (!builder.add(packet.byteStart, packet.byteEnd, packet.startLine, packet.endLine, idOfFile[fileIndex])) return null
        }
        val summaryCounts = HashMap<String, Int>()
        keptSummaries.forEach { summaryCounts.merge(it.section, 1, Int::plus) }
        val summaryBuilders = summaryCounts.mapValues { SpanBuilder(it.value) }
        for (position in keptSummaries.indices) {
            val summary = keptSummaries[position]
            val entry = summaryEntries[position]
            if (entry.profileFields["section"] != summary.section) return null
            if (!summaryBuilders.getValue(summary.section).add(summary.byteStart, summary.byteEnd, summary.startLine, summary.endLine, entry.id)) {
                return null
            }
        }
        return MetadataJsonSectionIndex(
            stamp, startId, sourceClockId, sourceLineOffset, sectionNameHint, sectionIds.toSet(),
            builders.mapValues { it.value.build() }, summaryBuilders.mapValues { it.value.build() },
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

private class SpanWindow(private val channel: FileChannel) {
    private var buffer = ByteArray(0)
    private var start = 0L
    private var length = 0

    val bytes: ByteArray get() = buffer

    /** Makes [from, from + count) available in [bytes] and returns its offset there. */
    fun offsetOf(from: Long, count: Int): Int {
        if (from < start || from + count > start + length) {
            val want = maxOf(SECTION_WINDOW_BYTES, count)
            if (buffer.size < want) buffer = ByteArray(want)
            val target = ByteBuffer.wrap(buffer, 0, want)
            while (target.hasRemaining()) {
                if (channel.read(target, from + target.position()) < 0) break
            }
            start = from
            length = target.position()
            if (count > length) throw EOFException("The indexed JSON span is past the end of the file.")
        }
        return (from - start).toInt()
    }
}

private fun parseSpan(window: SpanWindow, start: Long, count: Int): JsonElement? {
    val offset = window.offsetOf(start, count)
    METADATA_JSON_FACTORY.createParser(window.bytes, offset, count).use { parser ->
        return MetadataJsonScanner(parser, Int.MAX_VALUE).readFragment(emptyList())?.element
    }
}

/**
 * The rows of one section, read from the bytes the index points at: the same rows, ids and source lines the full parse gives that
 * section. Null means the index cannot be used (changed file, unknown section, unreadable span); parse the ordinary way then.
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
            val window = SpanWindow(handle.channel)
            val rows = ArrayList<LogEntry>(index.records[sectionId]?.size ?: 0)
            index.records[sectionId]?.let { spans ->
                for (i in 0 until spans.size) {
                    if (i % SECTION_INDEX_CANCELLATION_ROWS == 0) checkParserCancellation()
                    val value = parseSpan(window, spans.starts[i], spans.lengths[i]) as? JsonObject ?: return null
                    if (!isMetadataRecord(value)) return null
                    val packet = MetadataJsonPacket(value, sectionId, spans.startLines[i].toLong(), spans.endLines[i].toLong())
                    rows += metadataJsonEntry(
                        packet, spans.ids[i], index.sourceClockId, index.sourceLineOffset, compactor, viewProfile, index.sectionNameHint,
                    )
                }
            }
            // The full parse numbers records by time across the whole document; a section's rows follow in that order.
            rows.sortBy { it.id }
            index.summaries[sectionId]?.let { spans ->
                for (i in 0 until spans.size) {
                    val value = parseSpan(window, spans.starts[i], spans.lengths[i]) as? JsonObject ?: return null
                    val summary = MetadataJsonSummary(value, sectionId, spans.startLines[i].toLong(), spans.endLines[i].toLong())
                    rows += metadataJsonObjectEntry(summary, spans.ids[i], index.sourceLineOffset, viewProfile, compactor)
                }
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
