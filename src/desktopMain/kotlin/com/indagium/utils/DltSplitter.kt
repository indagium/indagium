package com.indagium.utils

import java.io.File
import java.io.InputStream
import kotlin.math.max

// Frame-aware DLT byte splitter — mirrors splitStreamToFiles' contract (LogSplitter.kt) but rotates
// on whole DLT records instead of newlines: every output is created/truncated up front, parts target
// ceil(size/parts) bytes, and a part rotates only when written > 0, it isn't the last part, and
// written + recordSize would overflow the target. Invariant: concatenating the parts reproduces the
// source bytes exactly, for both a clean stream and one with trailing garbage.
private const val DLT_SPLIT_BUFFER_BYTES = 1 shl 20

private const val STORAGE_HEADER_SIZE = 16
private const val STD_HEADER_SIZE = 4
private const val STORAGE_RECORD_HEAD_SIZE = STORAGE_HEADER_SIZE + STD_HEADER_SIZE // 20: storage header + std frame header
private val STORAGE_MAGIC = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 1)

/**
 * Splits a DLT v1 stream ([LogContentKind.DLT_STORAGE] or [LogContentKind.DLT_RAW]) into
 * [outputFiles] without ever cutting a frame in half. A record is the 16-byte storage header plus
 * the frame (storage mode) or just the frame (raw mode); the frame's own `LEN` (big-endian u16 at
 * frame bytes 2..3) already counts its own 4-byte standard header, matching [walkRawV1Frames] in
 * DltDetection.kt. The first record that fails structural validation (bad magic/version, or a
 * `LEN` too small to hold a standard header) or runs out of stream mid-record is not re-parsed —
 * whatever of it was already read, plus everything left in [input], is copied verbatim into the
 * current part and splitting stops there. That keeps the byte-identical-concatenation invariant
 * without duplicating the tolerant parser's resync logic (DltParser.kt decodes the result and
 * resyncs on its own from any offset).
 */
internal fun splitDltStreamToFiles(
    input: InputStream,
    outputFiles: List<File>,
    sourceSizeBytes: Long,
    kind: LogContentKind,
): List<File> {
    require(outputFiles.isNotEmpty()) { "At least one output file is required" }
    require(kind == LogContentKind.DLT_STORAGE || kind == LogContentKind.DLT_RAW) {
        "splitDltStreamToFiles only supports DLT_STORAGE/DLT_RAW, got $kind"
    }
    outputFiles.forEach { file ->
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(0))
    }
    val targetBytes = max(1L, (sourceSizeBytes + outputFiles.size - 1) / outputFiles.size)
    DltSplitWriter(outputFiles, targetBytes).use { writer ->
        input.use { stream ->
            if (kind == LogContentKind.DLT_STORAGE) copyStorageRecords(stream, writer) else copyRawRecords(stream, writer)
        }
    }
    return outputFiles
}

private fun copyStorageRecords(stream: InputStream, writer: DltSplitWriter) {
    while (true) {
        val head = ByteArray(STORAGE_RECORD_HEAD_SIZE)
        val headRead = readFully(stream, head)
        if (headRead == 0) return // clean end between records
        if (headRead < STORAGE_RECORD_HEAD_SIZE || !isValidStorageRecordHead(head)) {
            writer.write(head, 0, headRead)
            writer.copyRemaining(stream)
            return
        }
        val len = unsigned16BE(head[18], head[19])
        writer.rotateIfNeeded(recordSize = STORAGE_HEADER_SIZE.toLong() + len)
        writer.write(head, 0, STORAGE_RECORD_HEAD_SIZE)
        val bodyRemaining = len - STD_HEADER_SIZE
        val body = ByteArray(bodyRemaining)
        val bodyRead = readFully(stream, body)
        writer.write(body, 0, bodyRead)
        if (bodyRead < bodyRemaining) return // truncated frame at EOF — nothing left to copy
    }
}

private fun copyRawRecords(stream: InputStream, writer: DltSplitWriter) {
    while (true) {
        val head = ByteArray(STD_HEADER_SIZE)
        val headRead = readFully(stream, head)
        if (headRead == 0) return // clean end between records
        val len = if (headRead == STD_HEADER_SIZE) unsigned16BE(head[2], head[3]) else -1
        if (headRead < STD_HEADER_SIZE || !isValidRawRecordHead(head) || len < STD_HEADER_SIZE) {
            writer.write(head, 0, headRead)
            writer.copyRemaining(stream)
            return
        }
        writer.rotateIfNeeded(recordSize = len.toLong())
        writer.write(head, 0, STD_HEADER_SIZE)
        val bodyRemaining = len - STD_HEADER_SIZE
        val body = ByteArray(bodyRemaining)
        val bodyRead = readFully(stream, body)
        writer.write(body, 0, bodyRead)
        if (bodyRead < bodyRemaining) return // truncated frame at EOF — nothing left to copy
    }
}

private fun isValidStorageRecordHead(head: ByteArray): Boolean {
    if (head[0] != STORAGE_MAGIC[0] || head[1] != STORAGE_MAGIC[1] || head[2] != STORAGE_MAGIC[2] || head[3] != STORAGE_MAGIC[3]) return false
    val htyp = head[16].toInt() and 0xff
    if ((htyp ushr 5) != 1) return false
    return unsigned16BE(head[18], head[19]) >= STD_HEADER_SIZE
}

private fun isValidRawRecordHead(head: ByteArray): Boolean {
    val htyp = head[0].toInt() and 0xff
    return (htyp ushr 5) == 1
}

private fun unsigned16BE(msb: Byte, lsb: Byte): Int = ((msb.toInt() and 0xff) shl 8) or (lsb.toInt() and 0xff)

// Reads until `target` is full or the stream ends; returns how many bytes actually landed (fewer
// than target.size means EOF hit mid-read — same contract as DltParser.kt's readAvailable).
private fun readFully(input: InputStream, target: ByteArray): Int {
    var pos = 0
    while (pos < target.size) {
        val n = input.read(target, pos, target.size - pos)
        if (n < 0) break
        pos += n
    }
    return pos
}

private class DltSplitWriter(
    private val outputFiles: List<File>,
    private val targetBytes: Long,
) : AutoCloseable {
    private var outputIndex = 0
    private var out = outputFiles[0].outputStream().buffered(DLT_SPLIT_BUFFER_BYTES)
    private var written = 0L

    fun rotateIfNeeded(recordSize: Long) {
        if (written > 0L && outputIndex < outputFiles.lastIndex && written + recordSize > targetBytes) {
            out.close()
            outputIndex += 1
            out = outputFiles[outputIndex].outputStream().buffered(DLT_SPLIT_BUFFER_BYTES)
            written = 0L
        }
    }

    fun write(src: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        out.write(src, off, len)
        written += len
    }

    fun copyRemaining(stream: InputStream) {
        val buf = ByteArray(DLT_SPLIT_BUFFER_BYTES)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            write(buf, 0, n)
        }
    }

    override fun close() {
        out.close()
    }
}
