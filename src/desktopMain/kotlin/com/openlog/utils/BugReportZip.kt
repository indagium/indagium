package com.openlog.utils

import com.openlog.model.LogEntry
import com.openlog.model.VIDEO_FILE_EXTENSIONS
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

// Applies to extractZipCandidate/extractSevenZCandidate, which fully materialize a candidate's
// decompressed content into a List<LogEntry> in memory (unlike openZipCandidateStream/
// openSevenZCandidateStream, used only by the split-to-disk path, which already keeps a bounded
// working set regardless of entry size). Mirrors SPLIT_PROMPT_BYTES — the same size that prompts a
// plain file to be split instead of opened whole now bounds how much of one archive entry gets
// decompressed into memory before giving up with a clear error.
const val MAX_ARCHIVE_ENTRY_BYTES: Long = 500L * 1024L * 1024L

// Caps how many entries listLogcatCandidates/listArchiveLogCandidates will examine — an archive
// crafted with an enormous number of entries could otherwise stall listing even before any entry
// is decompressed.
const val MAX_ARCHIVE_ENTRIES_SCANNED: Int = 20_000

// Thrown when an archive entry's actual decompressed byte count exceeds the configured budget —
// callers surface this as a clear, bounded, user-facing error rather than letting extraction OOM
// or hang on a zip-bomb-style entry.
class ArchiveBudgetExceededException(message: String) : IOException(message)

// Fails closed once more than [budget] bytes have actually been read through this stream. The
// entry's declared/reported size (ZipEntry.size, SevenZArchiveEntry.size) is metadata only and is
// never trusted as the real limit — a hostile entry can under-report or omit it while still
// decompressing to gigabytes.
private class BoundedInputStream(private val delegate: InputStream, private val budget: Long) : InputStream() {
    private var readSoFar = 0L

    private fun accumulate(justRead: Int) {
        if (justRead <= 0) return
        readSoFar += justRead
        if (readSoFar > budget) throw ArchiveBudgetExceededException("archive entry exceeded the $budget byte extraction limit")
    }

    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) accumulate(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) accumulate(n)
        return n
    }

    override fun close() = delegate.close()
}

enum class ZipLogCandidateKind { LOGCAT, ANR_TEXT, VIDEO }

data class ZipLogCandidate(
    val entryPath: String,
    val displayName: String,
    val sizeBytes: Long,
    val kind: ZipLogCandidateKind = ZipLogCandidateKind.LOGCAT,
)

private val LOG_EXTENSIONS = setOf("txt", "log")
private val ANR_EXTENSIONS = setOf("", "txt", "trace", "traces")

// Content-sniffed, not extension-gated — same "open by content" philosophy as isLikelyTextFile:
// attempt to open as a zip archive and see if it succeeds, rather than trusting the .zip suffix.
fun isZipFile(file: File): Boolean {
    if (!file.isFile) return false
    return runCatching { ZipFile(file).use { } }.isSuccess
}

fun isSupportedArchiveFile(file: File): Boolean = isZipFile(file) || isSevenZFile(file)

private fun isSevenZFile(file: File): Boolean {
    if (!file.isFile) return false
    return runCatching { sevenZFile(file).use { } }.isSuccess
}

// Candidate filter: entries named like logs/ANR traces remain eligible, and every readable .txt
// entry is eligible even when its name says nothing about logs. The entry's own content must
// still sniff as text — a bug-report zip bundles binary buffers
// (bugreport-*.txt is itself text, but nested traces/heap dumps aren't) alongside the logcat
// buffers we actually want.
fun listLogcatCandidates(zipFile: File, maxEntries: Int = MAX_ARCHIVE_ENTRIES_SCANNED): List<ZipLogCandidate> =
    listArchiveLogCandidates(zipFile, maxEntries)

fun listArchiveLogCandidates(archiveFile: File, maxEntries: Int = MAX_ARCHIVE_ENTRIES_SCANNED): List<ZipLogCandidate> = when {
    isZipFile(archiveFile) -> listZipLogCandidates(archiveFile, maxEntries)
    isSevenZFile(archiveFile) -> listSevenZLogCandidates(archiveFile, maxEntries)
    else -> emptyList()
}

private fun listZipLogCandidates(zipFile: File, maxEntries: Int = MAX_ARCHIVE_ENTRIES_SCANNED): List<ZipLogCandidate> = runCatching {
    ZipFile(zipFile).use { zf ->
        zf.entries().asSequence()
            .take(maxEntries)
            .filter { entry -> !entry.isDirectory }
            .mapNotNull { entry ->
                candidateKind(entry.name) { zf.getInputStream(entry).use(::isLikelyTextStream) }
                    ?.let { kind -> ZipLogCandidate(entry.name, entry.name.substringAfterLast('/'), entry.size, kind) }
            }
            .toList()
    }
}.getOrDefault(emptyList())

private fun listSevenZLogCandidates(archiveFile: File, maxEntries: Int = MAX_ARCHIVE_ENTRIES_SCANNED): List<ZipLogCandidate> = runCatching {
    sevenZFile(archiveFile).use { sevenZ ->
        sevenZ.entries
            .asSequence()
            .take(maxEntries)
            .filter { entry -> !entry.isDirectory }
            .mapNotNull { entry ->
                candidateKind(entry.name) { sevenZ.getInputStream(entry).use(::isLikelyTextStream) }
                    ?.let { kind -> ZipLogCandidate(entry.name, entry.name.substringAfterLast('/'), entry.size, kind) }
            }
            .toList()
    }
}.getOrDefault(emptyList())

private fun sevenZFile(file: File): SevenZFile = SevenZFile.builder().setFile(file).get()

private fun candidateKind(entryPath: String, isText: () -> Boolean): ZipLogCandidateKind? {
    val name = entryPath.substringAfterLast('/')
    val lowerPath = entryPath.lowercase()
    val lowerName = name.lowercase()
    val ext = name.substringAfterLast('.', missingDelimiterValue = "")
    val isTextFile = ext.equals("txt", ignoreCase = true)
    val looksLikeLog = name.contains("log", ignoreCase = true) && (ext.isEmpty() || ext.lowercase() in LOG_EXTENSIONS)
    val inAnrDir = lowerPath.contains("/anr/") || lowerPath.startsWith("anr/")
    val looksLikeAnrTrace = lowerName.startsWith("anr_") ||
        lowerName.startsWith("traces") ||
        lowerName.contains("anr") && (ext.isEmpty() || ext.lowercase() in ANR_EXTENSIONS)
    val looksLikeAnr = (inAnrDir || looksLikeAnrTrace) && (ext.isEmpty() || ext.lowercase() in ANR_EXTENSIONS)
    if (!looksLikeLog && !looksLikeAnr && !isTextFile) return null
    if (!runCatching { isText() }.getOrDefault(false)) return null

    if (looksLikeLog || isTextFile) {
        return ZipLogCandidateKind.LOGCAT
    }

    return if (looksLikeAnr) {
        ZipLogCandidateKind.ANR_TEXT
    } else {
        null
    }
}

// Parallel lister to listArchiveLogCandidates above, reusing the same ZipFile/SevenZFile entry
// iteration but for screen recordings instead of log text — extension-gated only (VIDEO_FILE_
// EXTENSIONS), not content-sniffed, since isLikelyTextStream would (correctly) reject every video
// as non-text. Kept as its own function rather than folded into candidateKind() because that
// function's whole shape (looksLikeLog/looksLikeAnr + a text-content check) doesn't apply here.
fun listArchiveVideoCandidates(archiveFile: File, maxEntries: Int = MAX_ARCHIVE_ENTRIES_SCANNED): List<ZipLogCandidate> = when {
    isZipFile(archiveFile) -> listZipVideoCandidates(archiveFile, maxEntries)
    isSevenZFile(archiveFile) -> listSevenZVideoCandidates(archiveFile, maxEntries)
    else -> emptyList()
}

private fun isVideoEntryName(name: String): Boolean =
    name.substringAfterLast('.', missingDelimiterValue = "").lowercase() in VIDEO_FILE_EXTENSIONS

private fun listZipVideoCandidates(zipFile: File, maxEntries: Int): List<ZipLogCandidate> = runCatching {
    ZipFile(zipFile).use { zf ->
        zf.entries().asSequence()
            .take(maxEntries)
            .filter { entry -> !entry.isDirectory && isVideoEntryName(entry.name) }
            .map { entry -> ZipLogCandidate(entry.name, entry.name.substringAfterLast('/'), entry.size, ZipLogCandidateKind.VIDEO) }
            .toList()
    }
}.getOrDefault(emptyList())

private fun listSevenZVideoCandidates(archiveFile: File, maxEntries: Int): List<ZipLogCandidate> = runCatching {
    sevenZFile(archiveFile).use { sevenZ ->
        sevenZ.entries
            .asSequence()
            .take(maxEntries)
            .filter { entry -> !entry.isDirectory && isVideoEntryName(entry.name) }
            .map { entry -> ZipLogCandidate(entry.name, entry.name.substringAfterLast('/'), entry.size, ZipLogCandidateKind.VIDEO) }
            .toList()
    }
}.getOrDefault(emptyList())

// Legacy one-shot extraction API. New video attachments use [extractArchiveVideoToCache] so their
// persisted source remains the archive entry, not this process-local temp path.
fun extractEntryToTempFile(archiveFile: File, candidate: ZipLogCandidate, maxEntryBytes: Long = MAX_ARCHIVE_ENTRY_BYTES): File? = runCatching {
    val stream = openArchiveCandidateStream(archiveFile, candidate) ?: return@runCatching null
    val suffix = candidate.displayName.substringAfterLast('.', missingDelimiterValue = "").ifBlank { "bin" }
    val tempFile = File.createTempFile("openlog-video-", ".$suffix")
    tempFile.deleteOnExit()
    BoundedInputStream(stream, maxEntryBytes).use { input ->
        tempFile.outputStream().use { output -> input.copyTo(output) }
    }
    tempFile
}.getOrNull()

private val archiveVideoCacheLock = Any()

/**
 * Materializes a video archive entry into app-managed cache storage. The returned path is only a
 * playback implementation detail: callers must persist [ZipLogCandidate.entryPath] plus the
 * archive path instead. The cache key includes archive identity/version metadata, so replacing an
 * archive at the same location cannot replay an old recording.
 */
fun extractArchiveVideoToCache(
    archiveFile: File,
    candidate: ZipLogCandidate,
    cacheDir: File,
    maxEntryBytes: Long = MAX_ARCHIVE_ENTRY_BYTES,
): File? = runCatching {
    if (candidate.kind != ZipLogCandidateKind.VIDEO || !archiveFile.isFile) return@runCatching null
    val fingerprint = listOf(
        archiveFile.canonicalPath,
        archiveFile.length().toString(),
        archiveFile.lastModified().toString(),
        candidate.entryPath,
        candidate.sizeBytes.toString(),
    ).joinToString("\u0000")
    val key = MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
    val suffix = candidate.displayName.substringAfterLast('.', missingDelimiterValue = "bin")
        .lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "bin" }
    val destination = File(File(cacheDir, "videos"), "$key.$suffix")
    synchronized(archiveVideoCacheLock) {
        if (destination.isFile && destination.length() > 0) return@synchronized destination
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, ".${destination.name}.partial")
        runCatching { partial.delete() }
        val stream = openArchiveCandidateStream(archiveFile, candidate) ?: return@synchronized null
        try {
            BoundedInputStream(stream, maxEntryBytes).use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            destination
        } catch (t: Throwable) {
            partial.delete()
            throw t
        }
    }
}.getOrNull()

// Parses in-memory, straight from the zip entry's stream — never extracts to a temp dir.
// [maxEntryBytes] bounds actual decompressed bytes read (see BoundedInputStream); a candidate that
// exceeds it throws ArchiveBudgetExceededException instead of silently swallowing to an empty list
// the way other extraction failures (corrupt entry, IO error) still do below.
fun extractCandidate(zipFile: File, candidate: ZipLogCandidate, maxEntryBytes: Long = MAX_ARCHIVE_ENTRY_BYTES): List<LogEntry> = when {
    isZipFile(zipFile) -> extractZipCandidate(zipFile, candidate, maxEntryBytes)
    isSevenZFile(zipFile) -> extractSevenZCandidate(zipFile, candidate, maxEntryBytes)
    else -> emptyList()
}

fun openArchiveCandidateStream(archiveFile: File, candidate: ZipLogCandidate): InputStream? = when {
    isZipFile(archiveFile) -> openZipCandidateStream(archiveFile, candidate)
    isSevenZFile(archiveFile) -> openSevenZCandidateStream(archiveFile, candidate)
    else -> null
}

private fun extractZipCandidate(zipFile: File, candidate: ZipLogCandidate, maxEntryBytes: Long): List<LogEntry> {
    val result = runCatching {
        ZipFile(zipFile).use { zf ->
            val entry = zf.getEntry(candidate.entryPath) ?: return@use emptyList()
            BoundedInputStream(zf.getInputStream(entry), maxEntryBytes).use { stream ->
                parseLogcatLines(stream.bufferedReader().lineSequence())
            }
        }
    }
    // Budget breaches propagate (callers must surface a clear error); every other failure mode
    // (corrupt entry, IO error) keeps the pre-existing silent-empty-list behavior.
    result.exceptionOrNull()?.let { if (it is ArchiveBudgetExceededException) throw it }
    return result.getOrDefault(emptyList())
}

private fun extractSevenZCandidate(archiveFile: File, candidate: ZipLogCandidate, maxEntryBytes: Long): List<LogEntry> {
    val result = runCatching {
        sevenZFile(archiveFile).use { sevenZ ->
            val entry = sevenZ.entries.firstOrNull { it.name == candidate.entryPath } ?: return@use emptyList()
            BoundedInputStream(sevenZ.getInputStream(entry), maxEntryBytes).use { stream ->
                parseLogcatLines(stream.bufferedReader().lineSequence())
            }
        }
    }
    result.exceptionOrNull()?.let { if (it is ArchiveBudgetExceededException) throw it }
    return result.getOrDefault(emptyList())
}

private fun openZipCandidateStream(zipFile: File, candidate: ZipLogCandidate): InputStream? = runCatching {
    val zf = ZipFile(zipFile)
    val entry = zf.getEntry(candidate.entryPath) ?: run {
        zf.close()
        return@runCatching null
    }
    object : FilterInputStream(zf.getInputStream(entry)) {
        override fun close() {
            super.close()
            zf.close()
        }
    }
}.getOrNull()

private fun openSevenZCandidateStream(archiveFile: File, candidate: ZipLogCandidate): InputStream? = runCatching {
    val sevenZ = sevenZFile(archiveFile)
    val entry = sevenZ.entries.firstOrNull { it.name == candidate.entryPath } ?: run {
        sevenZ.close()
        return@runCatching null
    }
    object : FilterInputStream(sevenZ.getInputStream(entry)) {
        override fun close() {
            super.close()
            sevenZ.close()
        }
    }
}.getOrNull()
