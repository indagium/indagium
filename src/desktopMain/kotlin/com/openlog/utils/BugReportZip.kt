package com.openlog.utils

import com.openlog.model.LogEntry
import java.io.File
import java.util.zip.ZipEntry
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

// Candidate filter: entry basename contains "log" (case-insensitive), extension is .txt/.log/none,
// and the entry's own content sniffs as text — a bug-report zip bundles binary buffers
// (bugreport-*.txt is itself text, but nested traces/heap dumps aren't) alongside the logcat
// buffers we actually want.
fun listLogcatCandidates(zipFile: File): List<ZipLogCandidate> = runCatching {
    ZipFile(zipFile).use { zf ->
        zf.entries().asSequence()
            .filter { entry -> !entry.isDirectory }
            .mapNotNull { entry -> candidateKind(entry, zf)?.let { kind -> ZipLogCandidate(entry.name, entry.name.substringAfterLast('/'), entry.size, kind) } }
            .toList()
    }
}.getOrDefault(emptyList())

private fun candidateKind(entry: ZipEntry, zf: ZipFile): ZipLogCandidateKind? {
    val name = entry.name.substringAfterLast('/')
    val lowerPath = entry.name.lowercase()
    val lowerName = name.lowercase()
    val ext = name.substringAfterLast('.', missingDelimiterValue = "")
    val isText = runCatching { zf.getInputStream(entry).use { isLikelyTextStream(it) } }.getOrDefault(false)
    if (!isText) return null

    if (name.contains("log", ignoreCase = true) && (ext.isEmpty() || ext.lowercase() in LOG_EXTENSIONS)) {
        return ZipLogCandidateKind.LOGCAT
    }

    val inAnrDir = lowerPath.contains("/anr/") || lowerPath.startsWith("anr/")
    val looksLikeAnrTrace = lowerName.startsWith("anr_") ||
        lowerName.startsWith("traces") ||
        lowerName.contains("anr") && (ext.isEmpty() || ext.lowercase() in ANR_EXTENSIONS)
    return if ((inAnrDir || looksLikeAnrTrace) && (ext.isEmpty() || ext.lowercase() in ANR_EXTENSIONS)) {
        ZipLogCandidateKind.ANR_TEXT
    } else {
        null
    }
}

// Parses in-memory, straight from the zip entry's stream — never extracts to a temp dir.
fun extractCandidate(zipFile: File, candidate: ZipLogCandidate): List<LogEntry> = runCatching {
    ZipFile(zipFile).use { zf ->
        val entry = zf.getEntry(candidate.entryPath) ?: return@use emptyList()
        zf.getInputStream(entry).use { stream -> parseLogcatLines(stream.bufferedReader().lineSequence()) }
    }
}.getOrDefault(emptyList())
