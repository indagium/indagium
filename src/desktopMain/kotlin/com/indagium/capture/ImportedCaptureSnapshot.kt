package com.indagium.capture

import com.indagium.model.LogEntry
import com.indagium.model.VideoSource
import com.indagium.utils.unrollLogTimeline
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

private const val BYTE_MASK = 0xFF

/** A range-exportable view over immutable archive assets plus its app-local, source-keyed cursor. */
internal data class ImportedCaptureSnapshot(
    val identity: String,
    val session: CaptureSession,
    val checkpointFile: File,
)

/**
 * Builds the index the ordinary snapshot exporter expects without copying the archive's log/video.
 * v4 and v1/v2 carry exact elapsed row timing; v3's one sync anchor is expanded using log
 * timestamps, with declared coverage endpoints as a fallback for rows without parseable times.
 */
internal fun importedCaptureSnapshot(
    imported: ImportedCapture,
    source: File,
    parsedLog: List<LogEntry>,
    indexDirectory: File,
    checkpointDirectory: File,
): ImportedCaptureSnapshot {
    val identity = importedCaptureSnapshotIdentity(source, imported.descriptor)
    require(indexDirectory.mkdirs() || indexDirectory.isDirectory) { "Cannot create imported capture index cache" }
    require(checkpointDirectory.mkdirs() || checkpointDirectory.isDirectory) { "Cannot create imported capture cursor store" }
    val indexFile = File(indexDirectory, "$identity.jsonl")
    val checkpointFile = File(checkpointDirectory, "$identity.properties")
    val timeline = imported.timeline
    val baseElapsed = imported.descriptor.timelineBaseElapsedMs
    val duration = when {
        imported.descriptor.formatVersion >= 4 -> imported.descriptor.timelineDurationMs
        timeline != null -> maxOf(
            timeline.timelineDurationMs,
            imported.descriptor.logEndMs,
            imported.descriptor.videoEndMs ?: 0L,
        )
        else -> maxOf(imported.descriptor.logEndMs, imported.descriptor.videoEndMs ?: 0L)
    }.coerceAtLeast(0L)
    val elapsedByOrdinal = importedElapsedRows(imported, parsedLog, duration)
    if (!indexFile.isFile || indexFile.length() == 0L) {
        writeImportedIndex(imported.logFile, indexFile, elapsedByOrdinal)
    }

    val savedCursors = readImportedSnapshotCursors(checkpointFile)
    val localVideoOrigin = timeline?.videoOriginElapsedMs?.let { it - baseElapsed }
        ?: imported.descriptor.videoStartMs?.minus(baseElapsed)
    val video = imported.videoFile
    val settings = CaptureSettings(
        recordVideo = video?.isFile == true,
        videoContainer = if (video?.extension.equals("mkv", ignoreCase = true)) CaptureVideoContainer.MKV
        else CaptureVideoContainer.MP4,
        showUnavailableFrames = imported.descriptor.showUnavailableFrames ?: true,
        writeDeviceAvailabilityMarkers = true,
    )
    val snapshotVideoSource = video?.takeIf { it.isFile }?.let { localVideo ->
        if (source.isDirectory || source.name == CAPTURE_DESCRIPTOR_NAME) {
            VideoSource.LocalFile(localVideo.absolutePath)
        } else {
            VideoSource.ArchiveEntry(source.absolutePath, imported.descriptor.video!!.path, localVideo.name)
        }
    }
    val session = CaptureSession(
        id = "imported-$identity",
        directory = imported.logFile.parentFile ?: File("."),
        device = CaptureDevice(
            imported.descriptor.device.serial,
            imported.descriptor.device.state,
            imported.descriptor.device.model,
            imported.descriptor.device.emulator,
        ),
        settings = settings,
        startedEpochMs = imported.descriptor.sessionStartedEpochMs,
        elapsedMs = duration,
        status = CaptureStatus.STOPPED,
        videoStartElapsedMs = localVideoOrigin,
        snapshotCheckpointMs = savedCursors.first,
        logCheckpointMs = savedCursors.first,
        videoCheckpointMs = savedCursors.second,
        exportCounter = savedCursors.third,
        interruptions = imported.descriptor.interruptions,
        manualOffsetMs = 0L,
        availabilityIntervals = imported.descriptor.availabilityIntervals,
        logFileOverride = imported.logFile,
        indexFileOverride = indexFile,
        videoFileOverride = video,
        snapshotVideoSource = snapshotVideoSource,
    )
    return ImportedCaptureSnapshot(identity, session, checkpointFile)
}

/** Refreshes the app-local cursors while holding the same source lock used through export. */
internal fun refreshImportedCaptureSnapshotCursors(source: ImportedCaptureSnapshot): ImportedCaptureSnapshot =
    synchronized(CaptureSnapshotCheckpointLocks.forSession(source.session)) {
        val (logCheckpointMs, videoCheckpointMs, exportCounter) = readImportedSnapshotCursors(source.checkpointFile)
        val old = source.session
        source.copy(
            session = old.copy(
                snapshotCheckpointMs = maxOf(old.effectiveSnapshotCheckpointMs, logCheckpointMs),
                logCheckpointMs = maxOf(old.logCheckpointMs, logCheckpointMs),
                videoCheckpointMs = maxOf(old.videoCheckpointMs, videoCheckpointMs),
                exportCounter = maxOf(old.exportCounter, exportCounter),
            ),
        )
    }

internal fun persistImportedCaptureSnapshotCursors(
    source: ImportedCaptureSnapshot,
    logCheckpointMs: Long,
    videoCheckpointMs: Long?,
): ImportedCaptureSnapshot = synchronized(CaptureSnapshotCheckpointLocks.forSession(source.session)) {
    val disk = readImportedSnapshotCursors(source.checkpointFile)
    val old = source.session
    val updated = old.copy(
        snapshotCheckpointMs = maxOf(old.effectiveSnapshotCheckpointMs, disk.first, logCheckpointMs),
        logCheckpointMs = maxOf(old.logCheckpointMs, disk.first, logCheckpointMs),
        videoCheckpointMs = maxOf(old.videoCheckpointMs, disk.second, videoCheckpointMs ?: -1L),
        exportCounter = maxOf(old.exportCounter, disk.third) + 1,
    )
    val properties = Properties().apply {
        setProperty("logCheckpointMs", updated.effectiveSnapshotCheckpointMs.toString())
        setProperty("videoCheckpointMs", updated.videoCheckpointMs.toString())
        setProperty("exportCounter", updated.exportCounter.toString())
    }
    source.checkpointFile.parentFile?.mkdirs()
    val temp = File(source.checkpointFile.parentFile, ".${source.checkpointFile.name}.${UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(temp).use { output ->
            properties.store(output, "Imported capture snapshot cursors; source archive is immutable")
            output.fd.sync()
        }
        try {
            Files.move(temp.toPath(), source.checkpointFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), source.checkpointFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temp.delete()
    }
    source.copy(session = updated)
}

private fun importedCaptureSnapshotIdentity(source: File, descriptor: CaptureArchiveDescriptor): String {
    val stable = buildString {
        append(source.canonicalFile.path).append('\u0000')
        append(descriptor.formatVersion).append('\u0000')
        append(descriptor.sessionId).append('\u0000')
        append(descriptor.exportedEpochMs).append('\u0000')
        listOfNotNull(descriptor.log, descriptor.mapping, descriptor.video, descriptor.notes, descriptor.timeline)
            .plus(descriptor.screenshots)
            .sortedBy { it.path }
            .forEach { asset ->
                append(asset.path).append(':').append(asset.sha256).append('\u0000')
            }
    }
    return MessageDigest.getInstance("SHA-256").digest(stable.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
}

private fun readImportedSnapshotCursors(file: File): Triple<Long, Long, Int> {
    if (!file.isFile) return Triple(-1L, -1L, 0)
    return runCatching {
        val properties = Properties().apply { file.inputStream().use { load(it) } }
        Triple(
            properties.getProperty("logCheckpointMs")?.toLongOrNull()?.coerceAtLeast(-1L) ?: -1L,
            properties.getProperty("videoCheckpointMs")?.toLongOrNull()?.coerceAtLeast(-1L) ?: -1L,
            properties.getProperty("exportCounter")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        )
    }.getOrDefault(Triple(-1L, -1L, 0))
}

private fun importedElapsedRows(imported: ImportedCapture, parsedLog: List<LogEntry>, duration: Long): LongArray {
    val exact = imported.timeline?.rows
    if (exact != null && exact.size == parsedLog.size) return LongArray(exact.size) { exact[it].elapsedMs.coerceIn(0L, duration) }

    val descriptor = imported.descriptor
    val anchor = imported.syncAnchor
    if (anchor != null && anchor.row in 1..parsedLog.size) {
        val unrolled = unrollLogTimeline(parsedLog).byId
        val anchorTime = unrolled[parsedLog[anchor.row - 1].id]
        if (anchorTime != null) {
            val anchorElapsed = ((descriptor.videoStartMs ?: 0L) + anchor.videoMs - descriptor.timelineBaseElapsedMs)
                .coerceIn(0L, duration)
            var previous = 0L
            return LongArray(parsedLog.size) { index ->
                val wallTime = unrolled[parsedLog[index].id]
                val estimate = if (wallTime == null) {
                    interpolateElapsed(index, parsedLog.size, descriptor.logStartMs, descriptor.logEndMs)
                } else {
                    (anchorElapsed + (wallTime - anchorTime)).coerceIn(0L, duration)
                }
                maxOf(previous, estimate).also { previous = it }
            }
        }
    }
    val mapping = imported.timeline?.rows
    if (mapping != null && mapping.size == parsedLog.size) return LongArray(mapping.size) { mapping[it].elapsedMs.coerceIn(0L, duration) }
    return LongArray(parsedLog.size) { index ->
        interpolateElapsed(index, parsedLog.size, descriptor.logStartMs, descriptor.logEndMs).coerceIn(0L, duration)
    }
}

private fun interpolateElapsed(index: Int, count: Int, start: Long, end: Long): Long {
    if (count <= 1) return start.coerceAtLeast(0L)
    val span = (end - start).coerceAtLeast(0L)
    return start + (span.toDouble() * index / (count - 1)).toLong()
}

private fun writeImportedIndex(logFile: File, indexFile: File, elapsedByOrdinal: LongArray) {
    val temp = File(indexFile.parentFile, ".${indexFile.name}.${UUID.randomUUID()}.tmp")
    try {
        var byteOffset = 0L
        var ordinal = 0
        var fallbackElapsed = elapsedByOrdinal.firstOrNull() ?: 0L
        BufferedInputStream(logFile.inputStream()).use { input ->
            temp.bufferedWriter(Charsets.UTF_8).use { output ->
                val line = ByteArrayOutputStream()

                fun emit() {
                    val raw = line.toByteArray()
                    val trimmed = raw.toString(Charsets.UTF_8).trim()
                    val parsedRow = trimmed.isNotEmpty() && !trimmed.startsWith("-----")
                    val rowOrdinal = if (parsedRow) ++ordinal else null
                    val elapsed = rowOrdinal?.let { elapsedByOrdinal.getOrNull(it - 1) } ?: fallbackElapsed
                    if (rowOrdinal != null) fallbackElapsed = elapsed
                    output.append(indexRecordJson(CaptureLogIndexRecord(byteOffset, raw.size, elapsed, rowOrdinal))).append('\n')
                    byteOffset += raw.size
                    line.reset()
                }
                while (true) {
                    val byte = input.read()
                    if (byte < 0) break
                    line.write(byte)
                    if (byte == '\n'.code) emit()
                }
                if (line.size() > 0) emit()
                require(ordinal == elapsedByOrdinal.size) {
                    "Imported capture index row count $ordinal does not match parsed row count ${elapsedByOrdinal.size}"
                }
            }
        }
        try {
            Files.move(temp.toPath(), indexFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temp.delete()
    }
}
