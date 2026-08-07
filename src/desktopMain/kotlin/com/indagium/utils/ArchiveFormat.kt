package com.indagium.utils

import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveException
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.compressors.CompressorException
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipFile

// What kind of container (if any) a file is, decided by content — never by extension. Replaces
// the old "try to ZipFile/SevenZFile it and see if that succeeds" approach (still true for Zip/
// SevenZ as the *validation* step below, but no longer as the *first* thing tried for every file:
// with 8+ formats in play, opening each candidate reader in turn to find out which one works would
// mean several full-file attempts per dropped file — see detectArchiveFormat's memoization doc).
sealed interface ArchiveFormat {
    // Random access via java.util.zip.ZipFile — also covers every zip-shaped extension this app
    // already opens by content (.jar/.apk/.cbz/.epub/.odt/... — see BugReportZipTest), since they
    // share the same PK\x03\x04 signature and ZipFile doesn't care what the entries are named.
    data object Zip : ArchiveFormat

    // Random access via SevenZFile, which needs a real File (not just a stream) to seek its
    // central directory.
    data object SevenZ : ArchiveFormat

    // A container with no index — tar and ar. [archiverName] is one of ArchiveStreamFactory.TAR /
    // .AR (the string commons-compress itself uses to pick an ArchiveInputStream implementation).
    // [compressorName] is non-null for the compressed-then-archived shape (.tar.gz, .tar.bz2,
    // .tar.xz, .tar.lzma) and null for a bare .tar/.ar — either way, reading it means walking
    // entries forward with getNextEntry(), never seeking.
    data class Sequential(val archiverName: String, val compressorName: String?) : ArchiveFormat

    // A single compressed stream with no archive container inside — foo.log.gz, foo.txt.bz2.
    // [compressorName] is a CompressorStreamFactory constant (GZIP/BZIP2/XZ/LZMA).
    data class CompressedFile(val compressorName: String) : ArchiveFormat

    // Recognized by magic bytes but deliberately not wired up — RAR (no free/compatible library),
    // Zstandard/lzip/lzop (would need a new dependency). [label] is the user-facing format name.
    data class Unsupported(val label: String) : ArchiveFormat

    // A plain file (or directory, or something unreadable) — not a container of any kind. The
    // caller's job from here is the ordinary "is this a log/text file" sniff.
    data object None : ArchiveFormat
}

// Compressor names CompressorStreamFactory.detect() can hand back that this project actually
// knows how to decompress with what's on the classpath (commons-compress + the xz library — no
// zstd-jni, no brotli codec, no external `Z`/pack200 tooling). Anything else it recognizes by
// magic becomes a precise Unsupported instead of silently being treated as "not compressed".
private val SUPPORTED_COMPRESSOR_NAMES = setOf(
    CompressorStreamFactory.GZIP,
    CompressorStreamFactory.BZIP2,
    CompressorStreamFactory.XZ,
    CompressorStreamFactory.LZMA,
)

// Archiver names this project treats as a "list entries, stream one out" sequential container
// (ArchiveFormat.Sequential). commons-compress' generic ArchiveInputStream interface would let
// CPIO/ARJ/DUMP work identically, but they're not in the plan's in-scope list — an entry detected
// as one of those falls through to ArchiveFormat.None (today's "not an archive" behavior) rather
// than silently expanding what this app claims to support.
private val SEQUENTIAL_ARCHIVER_NAMES = setOf(ArchiveStreamFactory.TAR, ArchiveStreamFactory.AR)

// Read-ahead budget for detect()'s internal mark/reset. Generous on purpose — a BufferedInputStream
// grows its buffer to fit whatever readlimit mark() is given, on demand, so oversizing this is free
// and just needs to comfortably exceed the largest signature any detect() call here inspects
// (tens of bytes for every format in play).
private const val MAGIC_PEEK_LIMIT = 64 * 1024

// Deliberately-unsupported formats commons-compress' factories don't recognize at all (RAR isn't
// an archiver constant it has; lzip/lzop aren't compressor constants it has either) — matched by
// raw magic bytes as the last resort before falling back to extension. Zstandard *is* one of
// CompressorStreamFactory's constants (so it's normally caught earlier, in classifyCompressed's
// "recognized but not in SUPPORTED_COMPRESSOR_NAMES" branch), but its signature is listed here too
// as a safety net in case detect() ever declines to report it (e.g. no provider registered).
private val UNSUPPORTED_MAGIC_TABLE: List<Pair<ByteArray, String>> = listOf(
    // "Rar!"
    byteArrayOf(0x52, 0x61, 0x72, 0x21) to "RAR",
    byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()) to "Zstandard (.zst)",
    // "LZIP"
    byteArrayOf(0x4C, 0x5A, 0x49, 0x50) to "lzip (.lz)",
    byteArrayOf(0x89.toByte(), 0x4C, 0x5A, 0x4F) to "lzop (.lzo)",
)

private val UNSUPPORTED_EXTENSION_TABLE = mapOf(
    "rar" to "RAR",
    "zst" to "Zstandard (.zst)",
    "lz" to "lzip (.lz)",
    "lzo" to "lzop (.lzo)",
)

private const val ARCHIVE_FORMAT_CACHE_CAP = 256

private val archiveFormatCacheLock = Any()

// Access-order LinkedHashMap doubling as an LRU: removeEldestEntry evicts whatever wasn't touched
// most recently once the cap is exceeded. Not an optimization — isSupportedArchiveFile alone is
// called 3-4x per file per open, and with Zip/SevenZ validation now costing a real file open too,
// skipping this would mean several full re-detections of the same file on every single open.
private val archiveFormatCache = object : LinkedHashMap<String, ArchiveFormat>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArchiveFormat>): Boolean =
        size > ARCHIVE_FORMAT_CACHE_CAP
}

// canonicalPath + length + lastModified: an archive replaced in place (same path, new content)
// self-invalidates because at least one of length/lastModified almost always changes with it —
// this is the same fingerprint shape archiveVideoCacheFileName uses for exactly that reason.
private fun archiveFormatCacheKey(file: File): String? =
    runCatching { "${file.canonicalPath}#${file.length()}#${file.lastModified()}" }.getOrNull()

fun detectArchiveFormat(file: File): ArchiveFormat {
    val key = archiveFormatCacheKey(file) ?: return detectArchiveFormatUncached(file)
    synchronized(archiveFormatCacheLock) {
        archiveFormatCache[key]?.let { return it }
    }
    val detected = detectArchiveFormatUncached(file)
    synchronized(archiveFormatCacheLock) {
        archiveFormatCache[key] = detected
    }
    return detected
}

// Test seam: exercises detection without going through (or polluting) the memoization cache.
internal fun detectArchiveFormatUncached(file: File): ArchiveFormat {
    if (!file.isFile) return ArchiveFormat.None
    return runCatching {
        FileInputStream(file).use { fis ->
            val outer = BufferedInputStream(fis)
            outer.mark(MAGIC_PEEK_LIMIT)
            val compressorName = detectCompressor(outer)
            outer.reset()
            if (compressorName != null) return@use classifyCompressed(outer, compressorName)

            val archiverName = detectArchiver(outer)
            outer.reset()
            when (archiverName) {
                ArchiveStreamFactory.ZIP -> if (isValidZip(file)) ArchiveFormat.Zip else ArchiveFormat.None
                ArchiveStreamFactory.SEVEN_Z -> if (isValidSevenZ(file)) ArchiveFormat.SevenZ else ArchiveFormat.None
                // Non-null: `null` is never a member of SEQUENTIAL_ARCHIVER_NAMES, but Kotlin's
                // smart-cast doesn't see through Set.contains() the way it would `!= null`.
                in SEQUENTIAL_ARCHIVER_NAMES -> ArchiveFormat.Sequential(archiverName!!, null)
                else -> detectUnsupportedMagic(outer)?.let { ArchiveFormat.Unsupported(it) }
                    ?: detectUnsupportedExtension(file)
                    ?: ArchiveFormat.None
            }
        }
    }.getOrDefault(ArchiveFormat.None)
}

private fun detectCompressor(stream: InputStream): String? = try {
    CompressorStreamFactory.detect(stream)
} catch (_: CompressorException) {
    null
}

private fun detectArchiver(stream: InputStream): String? = try {
    ArchiveStreamFactory.detect(stream)
} catch (_: ArchiveException) {
    null
}

// [outer] is already positioned at byte 0 (the caller just reset it after the compressor detect
// call that produced [compressorName]). A gzip/bzip2/xz/lzma hit alone doesn't say whether this is
// a bare compressed log or a compressed *archive* (.tar.gz etc) — decompress just far enough to run
// ArchiveStreamFactory.detect() again on the decoded bytes to tell the two apart.
private fun classifyCompressed(outer: InputStream, compressorName: String): ArchiveFormat {
    if (compressorName !in SUPPORTED_COMPRESSOR_NAMES) {
        return ArchiveFormat.Unsupported(unsupportedCompressorLabel(compressorName))
    }
    return runCatching {
        val decompressed = CompressorStreamFactory().createCompressorInputStream(compressorName, outer)
        val inner = BufferedInputStream(decompressed)
        inner.mark(MAGIC_PEEK_LIMIT)
        val innerArchiverName = detectArchiver(inner)
        inner.reset()
        if (innerArchiverName != null && innerArchiverName in SEQUENTIAL_ARCHIVER_NAMES) {
            ArchiveFormat.Sequential(innerArchiverName, compressorName)
        } else {
            ArchiveFormat.CompressedFile(compressorName)
        }
    }.getOrDefault(ArchiveFormat.CompressedFile(compressorName))
}

private fun unsupportedCompressorLabel(name: String): String = when (name) {
    CompressorStreamFactory.ZSTANDARD -> "Zstandard (.zst)"
    CompressorStreamFactory.BROTLI -> "Brotli"
    CompressorStreamFactory.PACK200 -> "Pack200"
    CompressorStreamFactory.Z -> "Unix compress (.Z)"
    CompressorStreamFactory.LZ4_BLOCK, CompressorStreamFactory.LZ4_FRAMED -> "LZ4"
    CompressorStreamFactory.SNAPPY_FRAMED, CompressorStreamFactory.SNAPPY_RAW -> "Snappy"
    CompressorStreamFactory.DEFLATE, CompressorStreamFactory.DEFLATE64 -> "raw Deflate"
    else -> name
}

// Confirms once with a real open, same as the pre-rewrite isZipFile/isSevenZFile did unconditionally
// for every file. Magic bytes alone accept a truncated zip (the local file header is intact but the
// central directory at the end got cut off) — dropping this step would be a behavior regression, not
// just a missed optimization.
private fun isValidZip(file: File): Boolean = runCatching { ZipFile(file).use { } }.isSuccess

private fun isValidSevenZ(file: File): Boolean = runCatching { SevenZFile.builder().setFile(file).get().use { } }.isSuccess

private fun detectUnsupportedMagic(stream: InputStream): String? {
    val prefix = runCatching { stream.readNBytes(8) }.getOrDefault(ByteArray(0))
    return UNSUPPORTED_MAGIC_TABLE.firstOrNull { (magic, _) ->
        prefix.size >= magic.size && prefix.copyOf(magic.size).contentEquals(magic)
    }?.second
}

private fun detectUnsupportedExtension(file: File): ArchiveFormat? {
    val label = UNSUPPORTED_EXTENSION_TABLE[file.extension.lowercase()] ?: return null
    return ArchiveFormat.Unsupported(label)
}

// Opens [file] as a forward-only stream of its sequential archive entries, applying [format]'s
// compressor (if any) first. Callers own the returned stream and must close it exactly once, after
// they're done walking entries — see forEachSequentialEntry below for the entry-lifecycle rules
// that make that safe.
internal fun openSequentialArchive(file: File, format: ArchiveFormat.Sequential): ArchiveInputStream<out ArchiveEntry> {
    val fileStream = BufferedInputStream(FileInputStream(file))
    val base: InputStream = if (format.compressorName != null) {
        CompressorStreamFactory().createCompressorInputStream(format.compressorName, fileStream)
    } else {
        fileStream
    }
    return ArchiveStreamFactory().createArchiveInputStream(format.archiverName, base)
}

// Walks every entry of a sequential archive up to [maxEntries], invoking [visit] once per
// non-directory entry and collecting its non-null results. Encapsulates the two traps that would
// otherwise silently truncate a scan to its first entry:
//   - the entry [InputStream] handed to [visit] is NEVER closed here — tar/ar have exactly one
//     shared underlying stream for the whole archive (unlike zip's per-entry streams), so closing
//     it after one entry would sever the connection for every entry after it. [visit] must treat
//     the stream as borrowed, never call .use{}/.close() on it, and read from it only during its
//     own invocation.
//   - entries are advanced with getNextEntry() alone, which itself skips whatever of the current
//     entry's payload [visit] left unread — never a manual skip, which wouldn't know how much of
//     the entry (padding, alignment) remains.
internal fun <T> forEachSequentialEntry(
    file: File,
    format: ArchiveFormat.Sequential,
    maxEntries: Int,
    visit: (name: String, size: Long, isDirectory: Boolean, stream: InputStream) -> T?,
): List<T> {
    val archive = runCatching { openSequentialArchive(file, format) }.getOrElse { return emptyList() }
    val results = mutableListOf<T>()
    archive.use { stream ->
        var examined = 0
        while (examined < maxEntries) {
            val entry = runCatching { stream.getNextEntry() }.getOrNull() ?: break
            examined++
            val result = runCatching { visit(entry.name, entry.size, entry.isDirectory, stream) }.getOrNull()
            if (result != null) results += result
        }
    }
    return results
}
