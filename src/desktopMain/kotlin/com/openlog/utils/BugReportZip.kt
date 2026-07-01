package com.openlog.utils

import com.openlog.model.LogEntry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class ZipLogCandidate(val entryPath: String, val displayName: String, val sizeBytes: Long)

private val LOG_EXTENSIONS = setOf("txt", "log")

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
            .filter { entry -> !entry.isDirectory && isCandidateEntry(entry, zf) }
            .map { entry -> ZipLogCandidate(entry.name, entry.name.substringAfterLast('/'), entry.size) }
            .toList()
    }
}.getOrDefault(emptyList())

private fun isCandidateEntry(entry: ZipEntry, zf: ZipFile): Boolean {
    val name = entry.name.substringAfterLast('/')
    if (!name.contains("log", ignoreCase = true)) return false
    val ext = name.substringAfterLast('.', missingDelimiterValue = "")
    if (ext.isNotEmpty() && ext.lowercase() !in LOG_EXTENSIONS) return false
    return runCatching { zf.getInputStream(entry).use { isLikelyTextStream(it) } }.getOrDefault(false)
}

// Parses in-memory, straight from the zip entry's stream — never extracts to a temp dir.
fun extractCandidate(zipFile: File, candidate: ZipLogCandidate): List<LogEntry> = runCatching {
    ZipFile(zipFile).use { zf ->
        val entry = zf.getEntry(candidate.entryPath) ?: return@use emptyList()
        zf.getInputStream(entry).use { stream -> parseLogcatLines(stream.bufferedReader().lineSequence()) }
    }
}.getOrDefault(emptyList())
