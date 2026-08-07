package com.indagium.utils

import com.indagium.model.LogEntry
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

// A bare compressed single log — foo.log.gz, foo.txt.bz2 — parsed straight from the decompressed
// stream, without ever materializing the uncompressed content to disk. [uncompressedBytes] is the
// real decompressed size actually read (not the file's on-disk compressed size, which under-reports
// by roughly the compression ratio); see its use in AppState.kt for why openFileInternal wants it.
data class CompressedLogParse(val entries: List<LogEntry>, val uncompressedBytes: Long)

// Counts bytes as they pass through, purely for CompressedLogParse.uncompressedBytes — unlike
// BoundedInputStream (which this wraps), a byte count alone never fails closed; the budget
// enforcement is BoundedInputStream's job, this is just bookkeeping on top of it.
private class CountingInputStream(private val delegate: InputStream) : InputStream() {
    var count = 0L
        private set

    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) count += n
        return n
    }

    override fun close() = delegate.close()
}

// Raw decompressed bytes, uninterpreted — callers that just want a stream (rather than parsed
// entries) go through here, same shape as openArchiveCandidateStream for an archive entry.
fun openCompressedLogStream(file: File, compressorName: String): InputStream =
    CompressorStreamFactory().createCompressorInputStream(compressorName, BufferedInputStream(FileInputStream(file)))

// Wraps the decompressed stream in the same BoundedInputStream an archive entry gets (see its doc
// comment) — a hostile or corrupt .gz can decompress to far more than its on-disk size implies, and
// a bare compressed log deserves the identical zip-bomb protection an archive entry already has,
// not a weaker one just because it arrived without a container around it.
fun parseCompressedLog(file: File, compressorName: String, maxBytes: Long = MAX_ARCHIVE_ENTRY_BYTES): CompressedLogParse {
    val bounded = BoundedInputStream(openCompressedLogStream(file, compressorName), maxBytes)
    val counting = CountingInputStream(bounded)
    val entries = counting.bufferedReader().useLines { lines -> parseLogcatLines(lines) }
    return CompressedLogParse(entries, counting.count)
}

// The one hook that makes bare compressed logs "just work" everywhere a plain file already did:
// AppState's `parser` constructor seam defaults to this instead of ::parseLogcat, which covers
// openFileInternal, the split path, and — critically — loadRestoredTab, which would otherwise
// re-parse gzip bytes as raw (garbage) logcat lines on every relaunch of a restored .log.gz tab.
fun parseLogFile(file: File): List<LogEntry> = when (val format = detectArchiveFormat(file)) {
    is ArchiveFormat.CompressedFile -> parseCompressedLog(file, format.compressorName).entries
    else -> parseLogcat(file)
}
