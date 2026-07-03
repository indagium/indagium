package com.openlog.utils

import com.openlog.model.LogEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipFile

enum class ZipLogCandidateKind { LOGCAT, ANR_TEXT }

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

// Candidate filter: entry basename contains "log" (case-insensitive), extension is .txt/.log/none,
// and the entry's own content sniffs as text — a bug-report zip bundles binary buffers
// (bugreport-*.txt is itself text, but nested traces/heap dumps aren't) alongside the logcat
// buffers we actually want.
fun listLogcatCandidates(zipFile: File): List<ZipLogCandidate> = listArchiveLogCandidates(zipFile)

fun listArchiveLogCandidates(archiveFile: File): List<ZipLogCandidate> = when {
    isZipFile(archiveFile) -> listZipLogCandidates(archiveFile)
    isSevenZFile(archiveFile) -> listSevenZLogCandidates(archiveFile)
    else -> emptyList()
}

private fun listZipLogCandidates(zipFile: File): List<ZipLogCandidate> = runCatching {
    ZipFile(zipFile).use { zf ->
        zf.entries().asSequence()
            .filter { entry -> !entry.isDirectory }
            .mapNotNull { entry ->
                candidateKind(entry.name) { zf.getInputStream(entry).use(::isLikelyTextStream) }
                    ?.let { kind -> ZipLogCandidate(entry.name, entry.name.substringAfterLast('/'), entry.size, kind) }
            }
            .toList()
    }
}.getOrDefault(emptyList())

private fun listSevenZLogCandidates(archiveFile: File): List<ZipLogCandidate> = runCatching {
    sevenZFile(archiveFile).use { sevenZ ->
        sevenZ.entries
            .asSequence()
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
    val looksLikeLog = name.contains("log", ignoreCase = true) && (ext.isEmpty() || ext.lowercase() in LOG_EXTENSIONS)
    val inAnrDir = lowerPath.contains("/anr/") || lowerPath.startsWith("anr/")
    val looksLikeAnrTrace = lowerName.startsWith("anr_") ||
        lowerName.startsWith("traces") ||
        lowerName.contains("anr") && (ext.isEmpty() || ext.lowercase() in ANR_EXTENSIONS)
    val looksLikeAnr = (inAnrDir || looksLikeAnrTrace) && (ext.isEmpty() || ext.lowercase() in ANR_EXTENSIONS)
    if (!looksLikeLog && !looksLikeAnr) return null
    if (!runCatching { isText() }.getOrDefault(false)) return null

    if (looksLikeLog) {
        return ZipLogCandidateKind.LOGCAT
    }

    return if (looksLikeAnr) {
        ZipLogCandidateKind.ANR_TEXT
    } else {
        null
    }
}

// Parses in-memory, straight from the zip entry's stream — never extracts to a temp dir.
fun extractCandidate(zipFile: File, candidate: ZipLogCandidate): List<LogEntry> = when {
    isZipFile(zipFile) -> extractZipCandidate(zipFile, candidate)
    isSevenZFile(zipFile) -> extractSevenZCandidate(zipFile, candidate)
    else -> emptyList()
}

fun openArchiveCandidateStream(archiveFile: File, candidate: ZipLogCandidate): InputStream? = when {
    isZipFile(archiveFile) -> openZipCandidateStream(archiveFile, candidate)
    isSevenZFile(archiveFile) -> openSevenZCandidateStream(archiveFile, candidate)
    else -> null
}

private fun extractZipCandidate(zipFile: File, candidate: ZipLogCandidate): List<LogEntry> = runCatching {
    ZipFile(zipFile).use { zf ->
        val entry = zf.getEntry(candidate.entryPath) ?: return@use emptyList()
        zf.getInputStream(entry).use { stream -> parseLogcatLines(stream.bufferedReader().lineSequence()) }
    }
}.getOrDefault(emptyList())

private fun extractSevenZCandidate(archiveFile: File, candidate: ZipLogCandidate): List<LogEntry> = runCatching {
    sevenZFile(archiveFile).use { sevenZ ->
        val entry = sevenZ.entries.firstOrNull { it.name == candidate.entryPath } ?: return@use emptyList()
        sevenZ.getInputStream(entry).use { stream -> parseLogcatLines(stream.bufferedReader().lineSequence()) }
    }
}.getOrDefault(emptyList())

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
