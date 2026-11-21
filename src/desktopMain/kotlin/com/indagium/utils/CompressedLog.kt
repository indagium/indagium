package com.indagium.utils

import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogFormatProfile
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

// A bare compressed single log — foo.log.gz, foo.txt.bz2 — parsed straight from the decompressed
// stream, without ever materializing the uncompressed content to disk. [uncompressedBytes] is the
// real decompressed size actually read (not the file's on-disk compressed size, which under-reports
// by roughly the compression ratio); see its use in AppState.kt for why openFileInternal wants it.
data class CompressedLogParse(
    val entries: List<LogEntry>,
    val uncompressedBytes: Long,
    val format: LogFormat = LogFormat.LOGCAT,
    val profile: LogFormatProfile? = null,
    val diagnostics: List<String> = emptyList(),
    val sourceClockId: String? = null,
    val sections: List<ParsedLogSection> = emptyList(),
    val selectedSectionId: String? = null,
)

// Counts bytes as they pass through, purely for CompressedLogParse.uncompressedBytes — unlike
// BoundedInputStream (which this wraps), a byte count alone never fails closed; the budget
// enforcement is BoundedInputStream's job, this is just bookkeeping on top of it. internal (not
// private): DltParser.kt reuses it to compute byte offsets for its tolerant-framing marker rows.
internal class CountingInputStream(private val delegate: InputStream) : InputStream() {
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
    return parseCompressedLogWithProfile(file, compressorName, maxBytes, null, emptyList())
}

fun parseCompressedLogWithProfile(
    file: File,
    compressorName: String,
    maxBytes: Long = MAX_ARCHIVE_ENTRY_BYTES,
    profile: LogFormatProfile? = null,
    profiles: List<LogFormatProfile> = emptyList(),
): CompressedLogParse {
    val (parsed, uncompressedBytes) = parseCompressed(file, compressorName, maxBytes, profile, profiles)
    return CompressedLogParse(
        parsed.entries, uncompressedBytes, parsed.format, parsed.profile, parsed.diagnostics, parsed.sourceClockId,
        parsed.sections, parsed.selectedSectionId,
    )
}

private fun parseCompressed(
    file: File,
    compressorName: String,
    maxBytes: Long,
    profile: LogFormatProfile?,
    profiles: List<LogFormatProfile>,
): Pair<ParsedLog, Long> {
    val bounded = BoundedInputStream(openCompressedLogStream(file, compressorName), maxBytes)
    return CountingInputStream(bounded).use { counting ->
        // Strip only the compressor suffix, e.g. capture.dlt.gz becomes capture.dlt.
        val parsed = parseLogContent(
            counting,
            fileName = file.nameWithoutExtension,
            profile = profile,
            profiles = profiles,
            sourceClockId = file.absolutePath,
        )
        parsed to counting.count
    }
}

/** Parsed compressed-file result used by AppState so content detection is never inferred from a name. */
fun parseCompressedLogResult(
    file: File,
    compressorName: String,
    maxBytes: Long = MAX_ARCHIVE_ENTRY_BYTES,
): ParsedLog = parseCompressed(file, compressorName, maxBytes, null, emptyList()).first

fun parseCompressedLogResultWithProfiles(
    file: File,
    compressorName: String,
    profiles: List<LogFormatProfile>,
    profile: LogFormatProfile? = null,
    maxBytes: Long = MAX_ARCHIVE_ENTRY_BYTES,
): ParsedLog = parseCompressed(file, compressorName, maxBytes, profile, profiles).first

/** Content-aware file parser. The List-returning [parseLogFile] remains the compatibility seam. */
fun parseLogFileResult(file: File): ParsedLog {
    return when (val format = detectArchiveFormat(file)) {
        is ArchiveFormat.CompressedFile -> parseCompressedLogResult(file, format.compressorName)
        else -> file.inputStream().use { parseLogContent(it, fileName = file.name, sourceClockId = file.absolutePath) }
    }
}

fun parseLogFileResultWithProfiles(
    file: File,
    profiles: List<LogFormatProfile>,
    profile: LogFormatProfile? = null,
): ParsedLog {
    return when (val format = detectArchiveFormat(file)) {
        is ArchiveFormat.CompressedFile -> parseCompressedLogResultWithProfiles(file, format.compressorName, profiles, profile)
        else -> file.inputStream().use {
            parseLogContent(
                it, fileName = file.name, profile = profile, profiles = profiles, sourceClockId = file.absolutePath, indexSource = file,
            )
        }
    }
}

// The one hook that makes bare compressed logs "just work" everywhere a plain file already did:
// AppState's `parser` constructor seam defaults to this instead of ::parseLogcat, which covers
// openFileInternal, the split path, and — critically — loadRestoredTab, which would otherwise
// re-parse gzip bytes as raw (garbage) logcat lines on every relaunch of a restored .log.gz tab.
fun parseLogFile(file: File): List<LogEntry> {
    return when (val format = detectArchiveFormat(file)) {
        is ArchiveFormat.CompressedFile -> parseCompressedLog(file, format.compressorName).entries
        else -> file.inputStream().use { parseLogContent(it, fileName = file.name).entries }
    }
}
