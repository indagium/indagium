package com.indagium.utils

import com.indagium.model.LogEntry
import com.indagium.model.VIDEO_FILE_EXTENSIONS
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
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
//
// internal (not private): CompressedLog.kt's parseCompressedLog wraps a bare compressed file's
// decompressed stream in this too, for the same reason a bare .log.gz deserves the same bomb
// protection an archive entry already has (see its doc comment).
internal class BoundedInputStream(private val delegate: InputStream, private val budget: Long) : InputStream() {
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

// Paired lister results from one pass over an archive's entries — see scanArchiveCandidates below.
data class ArchiveScan(val logCandidates: List<ZipLogCandidate>, val videoCandidates: List<ZipLogCandidate>)

private val LOG_EXTENSIONS = setOf("txt", "log")
private val ANR_EXTENSIONS = setOf("", "txt", "trace", "traces")

// Content-sniffed, not extension-gated — same "open by content" philosophy as isLikelyTextFile.
// Delegates to detectArchiveFormat's magic-byte-detect-then-validate pipeline (ArchiveFormat.kt)
// rather than attempting a ZipFile open directly, so this stops being the *first* thing tried for
// every dropped file — see detectArchiveFormat's memoization doc for why that matters once there
// are 8+ formats to consider.
fun isZipFile(file: File): Boolean = detectArchiveFormat(file) == ArchiveFormat.Zip

fun isSupportedArchiveFile(file: File): Boolean = when (detectArchiveFormat(file)) {
    ArchiveFormat.Zip, ArchiveFormat.SevenZ -> true
    is ArchiveFormat.Sequential -> true
    else -> false
}

// Candidate filter: entries named like logs/ANR traces remain eligible, and every readable .txt
// entry is eligible even when its name says nothing about logs. The entry's own content must
// still sniff as text — a bug-report zip bundles binary buffers
// (bugreport-*.txt is itself text, but nested traces/heap dumps aren't) alongside the logcat
// buffers we actually want.
fun listLogcatCandidates(zipFile: File, maxEntries: Int = MAX_ARCHIVE_ENTRIES_SCANNED): List<ZipLogCandidate> =
    listArchiveLogCandidates(zipFile, maxEntries)

fun listArchiveLogCandidates(archiveFile: File, maxEntries: Int = MAX_ARCHIVE_ENTRIES_SCANNED): List<ZipLogCandidate> =
    scanArchiveCandidates(archiveFile, maxEntries).logCandidates

// Parallel lister to listArchiveLogCandidates above, for screen recordings instead of log text —
// extension-gated only (VIDEO_FILE_EXTENSIONS), not content-sniffed, since isLikelyTextStream
// would (correctly) reject every video as non-text.
fun listArchiveVideoCandidates(archiveFile: File, maxEntries: Int = MAX_ARCHIVE_ENTRIES_SCANNED): List<ZipLogCandidate> =
    scanArchiveCandidates(archiveFile, maxEntries).videoCandidates

// Single pass over an archive's entries producing both listers' results together. Not just a
// convenience: for a zip this is two cheap central-directory reads either way, but a sequential
// format (tar/ar, no index) has to walk the whole entry stream to answer either question — calling
// the log and video listers separately means decompressing a multi-GB .tar.gz twice for what one
// walk already sees.
fun scanArchiveCandidates(archiveFile: File, maxEntries: Int = MAX_ARCHIVE_ENTRIES_SCANNED): ArchiveScan {
    val logs = mutableListOf<ZipLogCandidate>()
    val videos = mutableListOf<ZipLogCandidate>()
    when (val format = detectArchiveFormat(archiveFile)) {
        ArchiveFormat.Zip -> scanZip(archiveFile, ScanBudget(maxEntries), logs, videos)
        ArchiveFormat.SevenZ -> scanSevenZ(archiveFile, ScanBudget(maxEntries), logs, videos)
        is ArchiveFormat.Sequential -> scanSequential(archiveFile, format, maxEntries, logs, videos)
        else -> Unit
    }
    return ArchiveScan(logs, videos)
}

// Entries examined so far, shared across nesting levels (an outer .tar.7z entry and everything
// found by unpacking it count against the same cap) — see the one-level-nesting doc on scanZip.
private class ScanBudget(var remaining: Int)

private fun scanZip(zipFile: File, budget: ScanBudget, logs: MutableList<ZipLogCandidate>, videos: MutableList<ZipLogCandidate>) {
    runCatching {
        ZipFile(zipFile).use { zf ->
            for (entry in zf.entries().asSequence()) {
                if (budget.remaining <= 0) break
                if (entry.isDirectory) continue
                budget.remaining--
                if (isNestedTarContainer(entry.name)) {
                    scanNestedTar(entry.name, zf.getInputStream(entry), budget, logs, videos)
                } else {
                    // Fresh per-entry stream: close it here (see classifyEntry's doc).
                    classifyEntry(entry.name, entry.size, logs, videos) {
                        zf.getInputStream(entry).use(::isLikelyTextStream)
                    }
                }
            }
        }
    }
}

private fun scanSevenZ(archiveFile: File, budget: ScanBudget, logs: MutableList<ZipLogCandidate>, videos: MutableList<ZipLogCandidate>) {
    runCatching {
        sevenZFile(archiveFile).use { sevenZ ->
            for (entry in sevenZ.entries) {
                if (budget.remaining <= 0) break
                if (entry.isDirectory) continue
                budget.remaining--
                if (isNestedTarContainer(entry.name)) {
                    scanNestedTar(entry.name, sevenZ.getInputStream(entry), budget, logs, videos)
                } else {
                    // Fresh per-entry stream: close it here (see classifyEntry's doc).
                    classifyEntry(entry.name, entry.size, logs, videos) {
                        sevenZ.getInputStream(entry).use(::isLikelyTextStream)
                    }
                }
            }
        }
    }
}

private fun scanSequential(
    file: File,
    format: ArchiveFormat.Sequential,
    maxEntries: Int,
    logs: MutableList<ZipLogCandidate>,
    videos: MutableList<ZipLogCandidate>,
) {
    // forEachSequentialEntry hands `stream` back BORROWED — it is the one stream for the whole
    // archive, so this must never close it (see classifyEntry's doc). candidateKind calls the
    // sniff at most once per entry, and getNextEntry() skips whatever of the entry that sniff
    // left unread. The visit return value isn't needed; logs/videos are filled by side effect.
    forEachSequentialEntry(file, format, maxEntries) { name, size, isDirectory, stream ->
        if (!isDirectory) classifyEntry(name, size, logs, videos) { isLikelyTextStream(stream) }
        null
    }
}

// One level of nesting: a Zip or SevenZ container entry whose name ends in ".tar" isn't itself a
// candidate — it's unpacked in place and its own entries become candidates instead, qualified as
// "$outerEntryPath!$innerEntryName" (openZipEntry already builds sourcePath the same way for a
// plain archive entry, and archiveQualifiedLabel splits on the *first* "!", so this degrades to an
// ugly-but-correct label rather than breaking anything downstream). Deliberately not recursive —
// an inner entry that itself ends in ".tar" is just classified (and almost certainly rejected) as
// an ordinary candidate, not unpacked again.
private fun scanNestedTar(
    outerEntryPath: String,
    outerStream: InputStream,
    budget: ScanBudget,
    logs: MutableList<ZipLogCandidate>,
    videos: MutableList<ZipLogCandidate>,
) {
    val tar = runCatching { TarArchiveInputStream(outerStream) }.getOrNull() ?: return
    // Same shared-stream rule as forEachSequentialEntry: never close an individual entry's stream
    // here, always advance via getNextEntry(). tar itself is never explicitly closed — it wraps
    // outerStream, which belongs to (and is closed by) the enclosing ZipFile/SevenZFile entry
    // iteration in scanZip/scanSevenZ, not to this function.
    while (budget.remaining > 0) {
        val entry = runCatching { tar.getNextEntry() }.getOrNull() ?: break
        if (entry.isDirectory) continue
        budget.remaining--
        val nestedPath = "$outerEntryPath!${entry.name}"
        // Borrowed, exactly like scanSequential — `tar` is the single stream for this nested
        // archive and closing it would end the walk at its first entry.
        classifyEntry(nestedPath, entry.size, logs, videos) { isLikelyTextStream(tar) }
    }
}

private fun isNestedTarContainer(entryName: String): Boolean = entryName.lowercase().endsWith(".tar")

// "$outer!$inner" -> (outer, inner), or null if entryPath isn't a nested reference. Mirrors
// scanNestedTar's path-building above and openZipEntry's "$archivePath!$entryPath" sourcePath
// convention (AppState.kt) — both use the first/only "!" as the separator.
private fun splitNestedEntryPath(entryPath: String): Pair<String, String>? {
    val idx = entryPath.indexOf('!')
    if (idx < 0) return null
    return entryPath.substring(0, idx) to entryPath.substring(idx + 1)
}

// [sniffText] rather than a `() -> InputStream`, because the two archive families disagree about
// who closes the entry stream and only the caller knows which rule applies. A random-access format
// hands out a FRESH stream per entry that the caller must close — leaving 20k of them (see
// MAX_ARCHIVE_ENTRIES_SCANNED) open until ZipFile.close() pins an inflater, and its native zlib
// buffer, for every entry scanned. A sequential format has exactly ONE stream for the whole
// archive, and closing it ends the scan at its first entry. isLikelyTextStream never closes what
// it reads, so pushing the decision up to the call site is what keeps both correct.
private fun classifyEntry(
    entryPath: String,
    size: Long,
    logs: MutableList<ZipLogCandidate>,
    videos: MutableList<ZipLogCandidate>,
    sniffText: () -> Boolean,
) {
    val name = entryPath.substringAfterLast('/')
    if (isVideoEntryName(name)) {
        videos += ZipLogCandidate(entryPath, name, size, ZipLogCandidateKind.VIDEO)
        return
    }
    val kind = candidateKind(entryPath, sniffText) ?: return
    logs += ZipLogCandidate(entryPath, name, size, kind)
}

private fun sevenZFile(file: File): SevenZFile = SevenZFile.builder().setFile(file).get()

// internal (not private): reused by FolderScan.kt so folder and archive candidate classification
// can't drift apart from each other.
internal fun candidateKind(entryPath: String, isText: () -> Boolean): ZipLogCandidateKind? {
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

// internal (not private): reused by FolderScan.kt, same rationale as candidateKind above.
internal fun isVideoEntryName(name: String): Boolean =
    name.substringAfterLast('.', missingDelimiterValue = "").lowercase() in VIDEO_FILE_EXTENSIONS

private val archiveVideoCacheLock = Any()

// Skips in-flight extractions: extractArchiveVideoToCache stages into ".<name>.partial" before the
// atomic rename to the real cache filename, so a prune that races an extraction must never treat
// that staging file as unreferenced garbage.
private fun isPartialCacheFile(file: File): Boolean = file.name.startsWith(".") && file.name.endsWith(".partial")

/**
 * Pure fingerprint->key->filename computation shared by [extractArchiveVideoToCache] and the
 * prune/budget functions below, so the two sides of the cache lifecycle can never diverge on what
 * a given archive+candidate is named on disk. Callers reconstructing this for pruning purposes
 * (see [com.indagium.ui.AppState.referencedArchiveVideoFileNames]) must pass a [ZipLogCandidate]
 * with `sizeBytes = -1L`, matching how `resolveVideoPlaybackPath` reconstructs it for playback.
 */
fun archiveVideoCacheFileName(archiveFile: File, candidate: ZipLogCandidate): String {
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
    return "$key.$suffix"
}

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
    val destination = File(File(cacheDir, "videos"), archiveVideoCacheFileName(archiveFile, candidate))
    synchronized(archiveVideoCacheLock) {
        if (destination.isFile && destination.length() > 0) {
            runCatching { destination.setLastModified(System.currentTimeMillis()) }
            return@synchronized destination
        }
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, ".${destination.name}.partial")
        runCatching { partial.delete() }
        val stream = openArchiveCandidateStream(archiveFile, candidate) ?: return@synchronized null
        try {
            BoundedInputStream(stream, maxEntryBytes).use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            if (!partial.renameTo(destination) && !(destination.delete() && partial.renameTo(destination))) {
                // Only reachable if renameTo fails for a reason deletion-and-retry can't fix
                // (e.g. a permissions issue) — a genuine failure, not a truncation risk: unlike
                // the old fallback here (a second, non-atomic stream copy straight into
                // `destination`'s name), nothing between these two attempts ever leaves a
                // partially-written file at `destination` for the cache-hit check above to find
                // and reuse as if it were complete.
                partial.delete()
                return@synchronized null
            }
            destination
        } catch (t: Throwable) {
            partial.delete()
            throw t
        }
    }
}.getOrNull()

// Parses in-memory, straight from the archive entry's stream — never extracts to a temp dir.
// [maxEntryBytes] bounds actual decompressed bytes read (see BoundedInputStream); a candidate that
// exceeds it throws ArchiveBudgetExceededException instead of silently swallowing to an empty list
// the way other extraction failures (corrupt entry, IO error) still do below.
fun extractCandidate(zipFile: File, candidate: ZipLogCandidate, maxEntryBytes: Long = MAX_ARCHIVE_ENTRY_BYTES): List<LogEntry> =
    when (val format = detectArchiveFormat(zipFile)) {
        ArchiveFormat.Zip -> extractZipCandidate(zipFile, candidate, maxEntryBytes)
        ArchiveFormat.SevenZ -> extractSevenZCandidate(zipFile, candidate, maxEntryBytes)
        is ArchiveFormat.Sequential -> extractSequentialCandidate(zipFile, format, candidate, maxEntryBytes)
        else -> emptyList()
    }

fun openArchiveCandidateStream(archiveFile: File, candidate: ZipLogCandidate): InputStream? =
    when (val format = detectArchiveFormat(archiveFile)) {
        ArchiveFormat.Zip -> openZipCandidateStream(archiveFile, candidate)
        ArchiveFormat.SevenZ -> openSevenZCandidateStream(archiveFile, candidate)
        is ArchiveFormat.Sequential -> openSequentialCandidateStream(archiveFile, format, candidate)
        else -> null
    }

private fun extractZipCandidate(zipFile: File, candidate: ZipLogCandidate, maxEntryBytes: Long): List<LogEntry> {
    val nested = splitNestedEntryPath(candidate.entryPath)
    val result = runCatching {
        ZipFile(zipFile).use { zf ->
            if (nested != null) {
                val (outerPath, innerPath) = nested
                val outerEntry = zf.getEntry(outerPath) ?: return@use emptyList()
                extractNestedTarEntry(zf.getInputStream(outerEntry), innerPath, maxEntryBytes)
            } else {
                val entry = zf.getEntry(candidate.entryPath) ?: return@use emptyList()
                BoundedInputStream(zf.getInputStream(entry), maxEntryBytes).use { stream ->
                    parseLogcatLines(stream.bufferedReader().lineSequence())
                }
            }
        }
    }
    // Budget breaches propagate (callers must surface a clear error); every other failure mode
    // (corrupt entry, IO error) keeps the pre-existing silent-empty-list behavior.
    result.exceptionOrNull()?.let { if (it is ArchiveBudgetExceededException) throw it }
    return result.getOrDefault(emptyList())
}

private fun extractSevenZCandidate(archiveFile: File, candidate: ZipLogCandidate, maxEntryBytes: Long): List<LogEntry> {
    val nested = splitNestedEntryPath(candidate.entryPath)
    val result = runCatching {
        sevenZFile(archiveFile).use { sevenZ ->
            if (nested != null) {
                val (outerPath, innerPath) = nested
                val outerEntry = sevenZ.entries.firstOrNull { it.name == outerPath } ?: return@use emptyList()
                extractNestedTarEntry(sevenZ.getInputStream(outerEntry), innerPath, maxEntryBytes)
            } else {
                val entry = sevenZ.entries.firstOrNull { it.name == candidate.entryPath } ?: return@use emptyList()
                BoundedInputStream(sevenZ.getInputStream(entry), maxEntryBytes).use { stream ->
                    parseLogcatLines(stream.bufferedReader().lineSequence())
                }
            }
        }
    }
    result.exceptionOrNull()?.let { if (it is ArchiveBudgetExceededException) throw it }
    return result.getOrDefault(emptyList())
}

private fun extractSequentialCandidate(
    file: File,
    format: ArchiveFormat.Sequential,
    candidate: ZipLogCandidate,
    maxEntryBytes: Long,
): List<LogEntry> {
    val result = runCatching {
        openSequentialArchive(file, format).use { archive ->
            val found = findSequentialEntry(archive, candidate.entryPath)
            if (found == null) {
                emptyList()
            } else {
                // Bounded-wrapping `archive` itself (not a fresh per-entry stream — sequential
                // formats don't have those) is safe here specifically because this is the
                // terminal read: we return right after, so there's no remaining entry that a
                // premature close could truncate away. Not `.use {}`'d separately — the outer
                // openSequentialArchive(...).use{} below already closes `archive` (and therefore
                // this wrapper's delegate) once the lambda returns.
                parseLogcatLines(BoundedInputStream(archive, maxEntryBytes).bufferedReader().lineSequence())
            }
        }
    }
    result.exceptionOrNull()?.let { if (it is ArchiveBudgetExceededException) throw it }
    return result.getOrDefault(emptyList())
}

// Reads forward until [innerPath] is found inside the tar wrapped around [outerStream] (an already
// -open entry stream from the enclosing zip/7z), then parses just that entry — the same "terminal
// read, safe to close the shared stream now" reasoning as extractSequentialCandidate above.
private fun extractNestedTarEntry(outerStream: InputStream, innerPath: String, maxEntryBytes: Long): List<LogEntry> =
    TarArchiveInputStream(outerStream).use { tar ->
        val found = findTarEntry(tar, innerPath)
        if (found == null) {
            emptyList()
        } else {
            // Not `.use {}`'d separately — see extractSequentialCandidate's identical note on why
            // wrapping `tar` itself (rather than a fresh per-entry stream) is safe for this,
            // the terminal read, and why the outer `.use {}` above is enough cleanup on its own.
            parseLogcatLines(BoundedInputStream(tar, maxEntryBytes).bufferedReader().lineSequence())
        }
    }

private fun openZipCandidateStream(zipFile: File, candidate: ZipLogCandidate): InputStream? = runCatching {
    val zf = ZipFile(zipFile)
    val nested = splitNestedEntryPath(candidate.entryPath)
    if (nested != null) {
        val (outerPath, innerPath) = nested
        val outerEntry = zf.getEntry(outerPath) ?: run { zf.close(); return@runCatching null }
        val tar = TarArchiveInputStream(zf.getInputStream(outerEntry))
        val found = findTarEntry(tar, innerPath)
        if (found == null) {
            tar.close()
            zf.close()
            return@runCatching null
        }
        object : FilterInputStream(tar) {
            override fun close() {
                super.close()
                zf.close()
            }
        }
    } else {
        val entry = zf.getEntry(candidate.entryPath) ?: run { zf.close(); return@runCatching null }
        object : FilterInputStream(zf.getInputStream(entry)) {
            override fun close() {
                super.close()
                zf.close()
            }
        }
    }
}.getOrNull()

private fun openSevenZCandidateStream(archiveFile: File, candidate: ZipLogCandidate): InputStream? = runCatching {
    val sevenZ = sevenZFile(archiveFile)
    val nested = splitNestedEntryPath(candidate.entryPath)
    if (nested != null) {
        val (outerPath, innerPath) = nested
        val outerEntry = sevenZ.entries.firstOrNull { it.name == outerPath } ?: run { sevenZ.close(); return@runCatching null }
        val tar = TarArchiveInputStream(sevenZ.getInputStream(outerEntry))
        val found = findTarEntry(tar, innerPath)
        if (found == null) {
            tar.close()
            sevenZ.close()
            return@runCatching null
        }
        object : FilterInputStream(tar) {
            override fun close() {
                super.close()
                sevenZ.close()
            }
        }
    } else {
        val entry = sevenZ.entries.firstOrNull { it.name == candidate.entryPath } ?: run { sevenZ.close(); return@runCatching null }
        object : FilterInputStream(sevenZ.getInputStream(entry)) {
            override fun close() {
                super.close()
                sevenZ.close()
            }
        }
    }
}.getOrNull()

private fun openSequentialCandidateStream(file: File, format: ArchiveFormat.Sequential, candidate: ZipLogCandidate): InputStream? = runCatching {
    val archive = openSequentialArchive(file, format)
    val found = findSequentialEntry(archive, candidate.entryPath)
    if (found == null) {
        archive.close()
        null
    } else {
        // `archive` (an ArchiveInputStream, itself an InputStream) IS the one and only stream
        // backing every entry — unlike zip/7z there's no separate per-entry stream to wrap, so
        // returning it directly and letting the caller close it closes the whole archive, which
        // is exactly right here.
        archive
    }
}.getOrNull()

private fun findTarEntry(tar: TarArchiveInputStream, entryName: String): TarArchiveEntry? {
    while (true) {
        val entry = tar.getNextEntry() ?: return null
        if (!entry.isDirectory && entry.name == entryName) return entry
    }
}

private fun findSequentialEntry(archive: ArchiveInputStream<out ArchiveEntry>, entryPath: String): ArchiveEntry? {
    while (true) {
        val entry = archive.getNextEntry() ?: return null
        if (!entry.isDirectory && entry.name == entryPath) return entry
    }
}

// Deletes cached archive-video files under cacheDir/videos that no open tab references anymore.
// [referencedFileNames] must be built with [archiveVideoCacheFileName] using sizeBytes = -1L (see
// that function's doc) or every live file looks unreferenced and gets deleted. In-flight
// ".*.partial" staging files are left alone regardless of referencedFileNames.
fun pruneUnreferencedArchiveVideos(cacheDir: File, referencedFileNames: Set<String>) {
    synchronized(archiveVideoCacheLock) {
        val files = File(cacheDir, "videos").listFiles() ?: return
        for (file in files) {
            if (isPartialCacheFile(file)) continue
            if (file.name !in referencedFileNames) runCatching { file.delete() }
        }
    }
}

// Evicts least-recently-modified cached archive-video files (skipping [protectedFileNames], the
// currently-referenced set) until total size is back under [budgetBytes], or only protected files
// remain. extractArchiveVideoToCache bumps lastModified on every cache hit, so recency here tracks
// playback use, not extraction time.
fun enforceArchiveVideoCacheBudget(cacheDir: File, budgetBytes: Long, protectedFileNames: Set<String>) {
    synchronized(archiveVideoCacheLock) {
        val files = (File(cacheDir, "videos").listFiles() ?: return).filterNot(::isPartialCacheFile)
        var total = files.sumOf { it.length() }
        if (total <= budgetBytes) return
        val evictable = files.filter { it.name !in protectedFileNames }.sortedBy { it.lastModified() }
        for (file in evictable) {
            if (total <= budgetBytes) break
            val length = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) total -= length
        }
    }
}
