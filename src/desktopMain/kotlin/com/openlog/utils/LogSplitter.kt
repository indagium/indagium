package com.openlog.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
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
): List<File> {
    require(outputFiles.isNotEmpty()) { "At least one output file is required" }
    outputFiles.forEach { file ->
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(0))
    }
    val targetBytes = max(1L, (sourceSizeBytes + outputFiles.size - 1) / outputFiles.size)
    SplitWriter(outputFiles, targetBytes).use { writer ->
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

private class SplitWriter(
    private val outputFiles: List<File>,
    private val targetBytes: Long,
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
