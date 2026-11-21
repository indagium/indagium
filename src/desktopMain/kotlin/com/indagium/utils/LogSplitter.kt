package com.indagium.utils

import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import java.io.Reader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.math.max

const val SPLIT_PROMPT_BYTES: Long = 500L * 1024L * 1024L
const val DEFAULT_SPLIT_POSTFIX: String = "part"

fun requiresSplitPrompt(sizeBytes: Long, thresholdBytes: Long = SPLIT_PROMPT_BYTES): Boolean =
    sizeBytes >= thresholdBytes

fun suggestedSplitPartCount(sizeBytes: Long, thresholdBytes: Long = SPLIT_PROMPT_BYTES): Int {
    if (sizeBytes <= 0L) return 1
    return max(1, ((sizeBytes + thresholdBytes - 1) / thresholdBytes).toInt())
}

fun planSplitOutputs(
    sourceName: String,
    destinationDir: File,
    postfix: String,
    partCount: Int,
): List<File> {
    val cleanPostfix = postfix.trim().ifBlank { DEFAULT_SPLIT_POSTFIX }
    val count = partCount.coerceAtLeast(1)
    val stem = sourceName.substringBeforeLast('.', sourceName)
    val ext = sourceName.substringAfterLast('.', missingDelimiterValue = "")
        .takeIf { sourceName.contains('.') }
        ?.let { ".$it" }
        .orEmpty()

    fun candidates(extraSuffix: String): List<File> = (1..count).map { idx ->
        File(destinationDir, "${stem}${extraSuffix}_${cleanPostfix}_$idx$ext")
    }

    var suffixIndex = 1
    var outputs = candidates("")
    while (outputs.any { it.exists() }) {
        suffixIndex += 1
        outputs = candidates("_$suffixIndex")
    }
    return outputs
}

private const val SPLIT_BUFFER_BYTES = 1 shl 20

// Chunked copy with one held-open buffered stream per part. The first version of this read the
// stream one byte at a time and called File.appendBytes per line — i.e. it opened and closed the
// output file for every one of ~10M lines, which made splitting a 1.5GB file take minutes while
// merely opening it took seconds. Part-rotation decisions only matter at line starts near a part
// boundary, so everything else is bulk arraycopy I/O. Contract: parts contain only whole lines,
// concatenating them reproduces the source byte-for-byte, and each non-final part lands within
// about one line of its byte target (a line straddling a bulk-copied chunk edge may overshoot
// the target by its own length — the old per-line code rotated one line earlier there).
fun splitStreamToFiles(
    input: InputStream,
    outputFiles: List<File>,
    sourceSizeBytes: Long,
): List<File> = splitStreamToFiles(input, outputFiles, sourceSizeBytes, partPreamble = null)

// DLT Viewer CSV overload: [partPreamble] is the header line's raw bytes (including its trailing
// newline), rewritten at the start of parts 2..n so every part parses standalone as its own CSV —
// part 1 already has the header naturally, as the stream's own first line. The preamble is written
// directly to the newly opened part and is NOT counted against `written`/targetBytes, so it never
// perturbs the rotation math that already ran on the un-prefixed byte stream — a part's *content*
// still lands within about one line of its byte target, same as the no-preamble contract below; the
// header repeat is purely additive. A `null` preamble (every other caller) is byte-identical to the
// original 3-arg overload — this is the only shared code path, so that identity is structural, not
// just tested.
internal fun splitStreamToFiles(
    input: InputStream,
    outputFiles: List<File>,
    sourceSizeBytes: Long,
    partPreamble: ByteArray?,
): List<File> {
    require(outputFiles.isNotEmpty()) { "At least one output file is required" }
    outputFiles.forEach { file ->
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(0))
    }
    val targetBytes = max(1L, (sourceSizeBytes + outputFiles.size - 1) / outputFiles.size)
    SplitWriter(outputFiles, targetBytes, partPreamble).use { writer ->
        input.use { stream ->
            val buf = ByteArray(SPLIT_BUFFER_BYTES)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                writer.consume(buf, n)
            }
            writer.finishPendingLine()
        }
    }
    return outputFiles
}

internal fun LogFormatProfile.usesRecordAwareSplit(): Boolean =
    engine == LogFormatEngine.CSV || engine == LogFormatEngine.JSON_LINES ||
        engine == LogFormatEngine.REGEX && multilineStartPattern.isNotBlank() || joinsContinuations()

/** Split at the CSV, JSON, regex or syslog record boundaries recognised by the selected profile. */
@Suppress("TooGenericExceptionCaught") // Remove incomplete parts and propagate the split failure.
internal fun splitProfileRecordsToFiles(
    input: InputStream,
    outputFiles: List<File>,
    sourceSizeBytes: Long,
    profile: LogFormatProfile,
): List<File> {
    require(profile.usesRecordAwareSplit()) { "This profile does not require record-aware splitting." }
    require(outputFiles.isNotEmpty()) { "At least one output file is required" }
    val targetBytes = max(1L, (sourceSizeBytes + outputFiles.size - 1) / outputFiles.size)
    val created = mutableListOf<File>()
    try {
        outputFiles.forEach { file ->
            file.parentFile?.mkdirs()
            check(file.createNewFile()) { "A split part already exists: ${file.name}" }
            created += file
        }
        PushbackInputStream(BufferedInputStream(input, SPLIT_BUFFER_BYTES), SPLIT_ENCODING_SAMPLE_BYTES).use { stream ->
            val prefix = stream.readNBytes(SPLIT_ENCODING_SAMPLE_BYTES)
            val encoding = detectLogTextEncoding(prefix)
            stream.unread(prefix)
            ProfileRecordSplitWriter(outputFiles, targetBytes, encoding.charset, encoding == LogTextEncoding.UTF_8_BOM).use { writer ->
                if (profile.engine == LogFormatEngine.JSON_LINES) {
                    openLogTextReader(stream).use { splitJsonRecords(it, writer, profile) }
                } else {
                    val bomLength = when {
                        encoding == LogTextEncoding.UTF_8_BOM -> 3
                        encoding.isUtf16 && prefix.startsWithUtf16Bom() -> 2
                        else -> 0
                    }
                    stream.skipNBytes(bomLength.toLong())
                    val (charset, newlineBytes) = splitEncoding(encoding)
                    val lines = SplitPhysicalLineReader(stream, newlineBytes)
                    if (profile.engine == LogFormatEngine.CSV) splitCsvRecords(lines, charset, writer, profile)
                    else splitHeaderRecords(lines, charset, writer, profile)
                }
            }
        }
        return outputFiles
    } catch (failure: Throwable) {
        created.forEach { it.delete() }
        throw failure
    }
}

private fun ByteArray.startsWithUtf16Bom(): Boolean =
    take(2) == listOf(0xFE.toByte(), 0xFF.toByte()) || take(2) == listOf(0xFF.toByte(), 0xFE.toByte())

private fun splitCsvRecords(
    lines: SplitPhysicalLineReader,
    charset: Charset,
    writer: ProfileRecordSplitWriter,
    profile: LogFormatProfile,
) {
    val machine = CsvRecordMachine(profile, mutableListOf(), initialHeaders = null, sourceLineOffset = 0)
    val ready = ArrayList<ProfileRecord>(1)
    val pending = ByteArrayOutputStream()
    var header: ByteArray? = null
    while (true) {
        val bytes = lines.next() ?: break
        pending.write(bytes)
        machine.accept(decodeSplitLine(bytes, charset), ready)
        ready.clear()
        if (machine.hasPendingRecord()) continue
        val record = pending.toByteArray()
        pending.reset()
        val waitingForHeader = profile.hasHeader && header == null
        writer.writeRecord(record, header, allowRotation = !waitingForHeader)
        if (waitingForHeader && machine.headers != null) header = record
    }
    if (pending.size() > 0) writer.writeRecord(pending.toByteArray(), header, allowRotation = !profile.hasHeader || header != null)
}

private fun splitHeaderRecords(
    lines: SplitPhysicalLineReader,
    charset: Charset,
    writer: ProfileRecordSplitWriter,
    profile: LogFormatProfile,
) {
    val boundary = profile.multilineStartPattern.takeIf(String::isNotBlank)?.let(::BoundedProfileRegexMatcher)
    val parseHeader = continuationHeaderParser(profile)
    var lineNumber = 0L
    while (true) {
        val bytes = lines.next() ?: break
        val line = decodeSplitLine(bytes, charset)
        lineNumber++
        checkParserCancellation()
        val startsRecord = boundary?.groups(line) != null ||
            (line.isNotBlank() && parseHeader?.invoke(line, lineNumber)?.parseFields == true)
        writer.writeLine(bytes, startsRecord)
    }
}

private fun splitJsonRecords(
    reader: Reader,
    writer: ProfileRecordSplitWriter,
    profile: LogFormatProfile,
) {
    val ready = ArrayList<ProfileRecord>(2)
    // Drain the completed records before changing the output envelope, even mid-line.
    val machine = JsonRecordMachine(profile, mutableListOf(), 0, splitMode = true) { opening ->
        writer.writeJsonRecords(ready)
        if (opening) writer.beginJsonArray() else writer.endJsonArray()
    }
    readSplitFragments(reader) { fragment, startsLine, endsLine ->
        machine.acceptFragment(fragment, startsLine, endsLine, ready)
        writer.writeJsonRecords(ready)
    }
    machine.finish(ready)
    writer.writeJsonRecords(ready)
    writer.endJsonArray()
}

/** Like BufferedReader's line handling, with bounded fragments for long physical lines. */
private fun readSplitFragments(reader: Reader, accept: (fragment: String, startsLine: Boolean, endsLine: Boolean) -> Unit) {
    val buffer = CharArray(SPLIT_ENCODING_SAMPLE_BYTES)
    var startsLine = true
    var skipLf = false
    while (true) {
        val count = reader.read(buffer)
        if (count < 0) return
        checkParserCancellation()
        var start = 0
        for (index in 0 until count) {
            val char = buffer[index]
            if (char == '\n' && skipLf) {
                start = index + 1
            } else if (char == '\n' || char == '\r') {
                accept(String(buffer, start, index - start), startsLine, true)
                start = index + 1
                startsLine = true
            }
            skipLf = char == '\r'
        }
        if (start < count) {
            accept(String(buffer, start, count - start), startsLine, false)
            startsLine = false
        }
    }
}

private const val MAX_PROFILE_SPLIT_RECORD_BYTES = 1 shl 20
private const val SPLIT_ENCODING_SAMPLE_BYTES = 8 * 1024
private const val SPLIT_BYTE_MASK = 0xff
private val UTF8_BOM = "\uFEFF".toByteArray(Charsets.UTF_8)

private class SplitPhysicalLineReader(
    private val input: InputStream,
    private val newlineBytes: ByteArray,
) {
    private val buffer = ByteArray(SPLIT_BUFFER_BYTES)
    private var cursor = 0
    private var count = 0

    fun next(): ByteArray? {
        val line = ByteArrayOutputStream()
        var previous = -1
        while (true) {
            if (cursor >= count) {
                count = input.read(buffer)
                cursor = 0
                if (count < 0) return line.toByteArray().takeIf(ByteArray::isNotEmpty)
            }
            val start = cursor
            while (cursor < count) {
                val value = buffer[cursor].toInt() and SPLIT_BYTE_MASK
                cursor++
                val foundNewline = (newlineBytes.size == 1 && value == (newlineBytes[0].toInt() and SPLIT_BYTE_MASK)) ||
                    (
                        newlineBytes.size == 2 && previous == (newlineBytes[0].toInt() and SPLIT_BYTE_MASK) &&
                            value == (newlineBytes[1].toInt() and SPLIT_BYTE_MASK) &&
                            // UTF-16 newline bytes can also straddle adjacent code units.
                            (line.size() + cursor - start) % newlineBytes.size == 0
                    )
                if (foundNewline) {
                    line.write(buffer, start, cursor - start)
                    return line.toByteArray()
                }
                previous = value
            }
            line.write(buffer, start, cursor - start)
            require(line.size() <= MAX_PROFILE_SPLIT_RECORD_BYTES) {
                "A physical line exceeds the 1 MiB safe splitting limit."
            }
        }
    }
}

private fun splitEncoding(encoding: LogTextEncoding): Pair<Charset, ByteArray> {
    val newline = when (encoding) {
        LogTextEncoding.UTF_16_BE -> byteArrayOf(0x00, 0x0A)
        LogTextEncoding.UTF_16_LE -> byteArrayOf(0x0A, 0x00)
        else -> byteArrayOf('\n'.code.toByte())
    }
    return encoding.charset to newline
}

private fun decodeSplitLine(bytes: ByteArray, charset: Charset): String =
    bytes.toString(charset).removeSuffix("\n").removeSuffix("\r")

private class ProfileRecordSplitWriter(
    private val outputFiles: List<File>,
    private val targetBytes: Long,
    private val textCharset: Charset,
    private val keepUtf8Bom: Boolean,
) : AutoCloseable {
    private var outputIndex = 0
    private var output = outputFiles[0].outputStream().buffered(SPLIT_BUFFER_BYTES)
    private var written = 0L
    private var jsonArrayOpen = false
    private var jsonArrayHasElement = false
    private val utf16Bom = when (textCharset) {
        StandardCharsets.UTF_16BE, StandardCharsets.UTF_16LE -> "\uFEFF".toByteArray(textCharset)
        else -> null
    }

    fun writeJsonRecords(records: MutableList<ProfileRecord>) {
        for (record in records) {
            val text = if (jsonArrayOpen) record.raw else record.raw + "\n"
            val bytes = text.toByteArray(textCharset)
            if (jsonArrayOpen) writeJsonArrayElement(bytes) else writeRecord(bytes)
        }
        records.clear()
    }

    fun beginJsonArray() {
        if (jsonArrayOpen) return
        if (written > 0L && outputIndex < outputFiles.lastIndex && written + 2L > targetBytes) rotate()
        if (written > 0L) writeText("\n")
        writeText("[")
        jsonArrayOpen = true
        jsonArrayHasElement = false
    }

    fun writeJsonArrayElement(record: ByteArray) {
        if (!jsonArrayOpen) beginJsonArray()
        if (jsonArrayHasElement && outputIndex < outputFiles.lastIndex &&
            written + 2L + record.size + 2L > targetBytes
        ) {
            endJsonArray()
            rotate()
            beginJsonArray()
        }
        if (jsonArrayHasElement) writeText(",\n")
        writeBytes(record)
        jsonArrayHasElement = true
    }

    fun endJsonArray() {
        if (!jsonArrayOpen) return
        writeText("]\n")
        jsonArrayOpen = false
        jsonArrayHasElement = false
    }

    fun writeRecord(record: ByteArray, repeatedHeader: ByteArray? = null, allowRotation: Boolean = true) {
        endJsonArray()
        if (allowRotation && written > 0 && outputIndex < outputFiles.lastIndex && written + record.size > targetBytes) {
            rotate()
            repeatedHeader?.let(::writeBytes)
        }
        writeBytes(record)
    }

    fun writeLine(line: ByteArray, startsRecord: Boolean) {
        if (startsRecord && written >= targetBytes) rotate()
        writeBytes(line)
    }

    private fun rotate() {
        if (outputIndex >= outputFiles.lastIndex) return
        output.close()
        outputIndex++
        output = outputFiles[outputIndex].outputStream().buffered(SPLIT_BUFFER_BYTES)
        written = 0L
    }

    private fun writeText(text: String) = writeBytes(text.toByteArray(textCharset))

    private fun writeBytes(bytes: ByteArray) {
        if (written == 0L) {
            val bom = utf16Bom ?: UTF8_BOM.takeIf { keepUtf8Bom && outputIndex == 0 }
            if (bom != null) {
                output.write(bom)
                written += bom.size
            }
        }
        output.write(bytes)
        written += bytes.size
    }

    override fun close() = output.close()
}

private class SplitWriter(
    private val outputFiles: List<File>,
    private val targetBytes: Long,
    private val partPreamble: ByteArray? = null,
) : AutoCloseable {
    private var outputIndex = 0
    private var out = outputFiles[0].outputStream().buffered(SPLIT_BUFFER_BYTES)
    private var written = 0L

    // Line spanning a boundary-zone chunk edge, buffered until its full length is known —
    // only lines near a part boundary ever land here, so it stays tiny.
    private val pendingLine = ByteArrayOutputStream()
    private var atLineStart = true

    fun consume(buf: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            if (pendingLine.size() > 0 || !atLineStart) {
                // Mid-line: finish it before any rotation decision is possible.
                val nl = indexOfNewline(buf, off, n)
                val end = if (nl >= 0) nl + 1 else n
                if (pendingLine.size() > 0) {
                    pendingLine.write(buf, off, end - off)
                    if (nl >= 0) flushPendingLine()
                } else {
                    write(buf, off, end - off)
                    atLineStart = nl >= 0
                }
                off = end
                continue
            }
            val lastPart = outputIndex == outputFiles.lastIndex
            if (lastPart || written + (n - off) <= targetBytes) {
                // Far from this part's boundary (or in the final part): the rotation rule can't
                // fire for any line in the rest of this chunk, so copy it wholesale.
                write(buf, off, n - off)
                atLineStart = buf[n - 1] == NEWLINE
                off = n
                continue
            }
            // Boundary zone: handle the next line individually.
            val nl = indexOfNewline(buf, off, n)
            if (nl < 0) {
                pendingLine.write(buf, off, n - off)
                off = n
            } else {
                rotateIfLineOverflows(lineLength = (nl + 1 - off).toLong())
                write(buf, off, nl + 1 - off)
                off = nl + 1
            }
        }
    }

    fun finishPendingLine() {
        if (pendingLine.size() > 0) flushPendingLine()
    }

    private fun flushPendingLine() {
        val line = pendingLine.toByteArray()
        pendingLine.reset()
        rotateIfLineOverflows(line.size.toLong())
        write(line, 0, line.size)
        atLineStart = line.isNotEmpty() && line.last() == NEWLINE
    }

    private fun rotateIfLineOverflows(lineLength: Long) {
        if (written > 0L && outputIndex < outputFiles.lastIndex && written + lineLength > targetBytes) {
            out.close()
            outputIndex += 1
            out = outputFiles[outputIndex].outputStream().buffered(SPLIT_BUFFER_BYTES)
            written = 0L
            partPreamble?.let { out.write(it) } // not counted in `written` — see the overload's KDoc
        }
    }

    private fun write(src: ByteArray, off: Int, len: Int) {
        out.write(src, off, len)
        written += len
    }

    override fun close() {
        out.close()
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()

        fun indexOfNewline(buf: ByteArray, from: Int, to: Int): Int {
            for (i in from until to) if (buf[i] == NEWLINE) return i
            return -1
        }
    }
}

fun splitFileToFiles(source: File, outputFiles: List<File>): List<File> =
    splitStreamToFiles(source.inputStream().buffered(), outputFiles, source.length())
