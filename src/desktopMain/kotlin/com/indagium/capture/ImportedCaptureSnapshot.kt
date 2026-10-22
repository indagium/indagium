package com.indagium.capture

import com.indagium.model.LogEntry
import com.indagium.model.VideoSource
import com.indagium.utils.readTextShared
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
private const val NEWLINE_BYTE = '\n'.code.toByte()

/** A range-exportable view over immutable archive assets plus its app-local, source-keyed cursor. */
internal data class ImportedCaptureSnapshot(
    val identity: String,
    val session: CaptureSession,
    val checkpointFile: File,
    /**
     * Its own claim on the archive's extraction directory (the session reads the log, video, screenshots and block
     * index from there): whoever stores the snapshot owns it and closes it when the snapshot is dropped. Appended last.
     */
    val extractionUse: CaptureExtractionUse? = null,
    /** Where "Extract from this archive" reads the archive's decoded assets; null when the opener gave no root. Appended last. */
    val extractSource: CaptureExtractSource? = null,
) : AutoCloseable {
    override fun close() {
        extractionUse?.close()
    }
}

private data class ImportedSnapshotCursors(
    val logMs: Long = -1L,
    val videoMs: Long = -1L,
    val exportCount: Int = 0,
    val byteOffset: Long = -1L,
    val rowOrdinal: Int = 0,
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
    val cacheDirectory = File(indexDirectory, identity)
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
    val sparseMetadata = imported.sparseSyncMetadata ?: legacySparseCache(imported, cacheDirectory, parsedLog, duration)
    val blockIndexFile = importedBlockIndexFile(imported, cacheDirectory)

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
        snapshotCheckpointMs = savedCursors.logMs,
        logCheckpointMs = savedCursors.logMs,
        videoCheckpointMs = savedCursors.videoMs,
        exportCounter = savedCursors.exportCount,
        interruptions = imported.descriptor.interruptions,
        manualOffsetMs = 0L,
        availabilityIntervals = imported.descriptor.availabilityIntervals,
        logFileOverride = imported.logFile,
        indexFileOverride = null,
        videoFileOverride = video,
        snapshotVideoSource = snapshotVideoSource,
        screenshotsOverride = importedScreenshots(imported),
        storageVersion = CaptureStorageVersion.BLOCKS_V2,
        blockIndexFileOverride = blockIndexFile,
        syncMetadataOverride = sparseMetadata,
        manualSegmentAnchors = sparseMetadata.segments.mapNotNull(CaptureSyncSegment::manualAnchor),
        frozenLogBytes = sparseMetadata.logBytes,
        frozenBlockIndexBytes = blockIndexFile.length(),
        logCheckpointByteOffset = savedCursors.byteOffset,
        logCheckpointRowOrdinal = savedCursors.rowOrdinal,
    )
    val extractSource = imported.root?.let { CaptureExtractSource(it, imported.descriptor, source) }
    return ImportedCaptureSnapshot(identity, session, checkpointFile, imported.extractionUse?.retain(), extractSource)
}

/**
 * Deletes the flat `<identity>.jsonl` files that older builds kept directly in [indexDirectory] (rebuilt on every open and
 * never reused). The current cache is one directory per identity, so only plain files are removed. Returns the count.
 */
internal fun sweepLegacyImportedIndexFiles(indexDirectory: File): Int =
    indexDirectory.listFiles().orEmpty().count { file ->
        file.isFile && !Files.isSymbolicLink(file.toPath()) && file.delete()
    }

/** The reusable sparse cache of a v1-v4 archive's earlier open, else a freshly built one. */
private fun legacySparseCache(
    imported: ImportedCapture,
    cacheDirectory: File,
    parsedLog: List<LogEntry>,
    durationMs: Long,
): CaptureSyncMetadata = reuseImportedSparseCache(imported, cacheDirectory, parsedLog.size, durationMs)
    ?: buildImportedSparseCache(imported, cacheDirectory, parsedLog, durationMs)

private fun importedBlockIndexFile(imported: ImportedCapture, cacheDirectory: File): File =
    if (imported.sparseSyncMetadata != null) {
        // Descriptor paths are relative to the archive root, not to the log's folder: an in-place capture folder
        // keeps its log in logs/, so logFile.parentFile/mapping/blocks.jsonl would be logs/mapping/blocks.jsonl.
        File(imported.root ?: imported.logFile.parentFile ?: File("."), requireNotNull(imported.descriptor.blocks).path)
    } else {
        File(cacheDirectory, "mapping/blocks.jsonl")
    }

private val EXPORTED_SCREENSHOT_NAME = Regex("(?:\\d+-)?screenshot-(\\d+)\\.[A-Za-z0-9]{1,8}")

/**
 * The archive's screenshots on the imported session's local time base. An exported entry is named
 * `<index>-screenshot-<elapsed>.<ext>` where `<elapsed>` is on the exporting session's time base, i.e.
 * [CaptureArchiveDescriptor.timelineBaseElapsedMs] ahead of this session's local zero. Screenshots
 * whose name carries no elapsed time (or that precede the base) cannot be placed and are omitted.
 */
private fun importedScreenshots(imported: ImportedCapture): List<CaptureSessionScreenshot> {
    val base = imported.descriptor.timelineBaseElapsedMs
    return imported.screenshotFiles.mapNotNull { file ->
        val original = EXPORTED_SCREENSHOT_NAME.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
        original?.minus(base)?.takeIf { it >= 0L }?.let { CaptureSessionScreenshot(file, it) }
    }
}

/** Refreshes the app-local cursors while holding the same source lock used through export. */
internal fun refreshImportedCaptureSnapshotCursors(source: ImportedCaptureSnapshot): ImportedCaptureSnapshot =
    synchronized(CaptureSnapshotCheckpointLocks.forSession(source.session)) {
        val cursors = readImportedSnapshotCursors(source.checkpointFile)
        val old = source.session
        val sparseCursorAdvanced = cursors.byteOffset > old.logCheckpointByteOffset
        source.copy(
            session = old.copy(
                snapshotCheckpointMs = maxOf(old.effectiveSnapshotCheckpointMs, cursors.logMs),
                // Legacy mirror of snapshotCheckpointMs, kept for source compatibility.
                logCheckpointMs = @Suppress("DEPRECATION") maxOf(old.logCheckpointMs, cursors.logMs),
                videoCheckpointMs = maxOf(old.videoCheckpointMs, cursors.videoMs),
                exportCounter = maxOf(old.exportCounter, cursors.exportCount),
                logCheckpointByteOffset = if (sparseCursorAdvanced) cursors.byteOffset else old.logCheckpointByteOffset,
                logCheckpointRowOrdinal = if (sparseCursorAdvanced) cursors.rowOrdinal else old.logCheckpointRowOrdinal,
            ),
        )
    }

internal fun persistImportedCaptureSnapshotCursors(
    source: ImportedCaptureSnapshot,
    logCheckpointMs: Long,
    videoCheckpointMs: Long?,
    logCheckpointByteOffset: Long? = null,
    logCheckpointRowOrdinal: Int? = null,
): ImportedCaptureSnapshot = synchronized(CaptureSnapshotCheckpointLocks.forSession(source.session)) {
    val disk = readImportedSnapshotCursors(source.checkpointFile)
    val old = source.session
    val durableByteOffset = maxOf(old.logCheckpointByteOffset, disk.byteOffset)
    val nextByteOffset = logCheckpointByteOffset?.takeIf { it > durableByteOffset } ?: durableByteOffset
    val nextRowOrdinal = when {
        logCheckpointByteOffset != null && logCheckpointByteOffset > durableByteOffset -> logCheckpointRowOrdinal ?: old.logCheckpointRowOrdinal
        durableByteOffset == disk.byteOffset && disk.byteOffset > old.logCheckpointByteOffset -> disk.rowOrdinal
        else -> old.logCheckpointRowOrdinal
    }
    val updated = old.copy(
        snapshotCheckpointMs = maxOf(old.effectiveSnapshotCheckpointMs, disk.logMs, logCheckpointMs),
        // Legacy mirror of snapshotCheckpointMs, kept for source compatibility.
        logCheckpointMs = @Suppress("DEPRECATION") maxOf(old.logCheckpointMs, disk.logMs, logCheckpointMs),
        videoCheckpointMs = maxOf(old.videoCheckpointMs, disk.videoMs, videoCheckpointMs ?: -1L),
        exportCounter = maxOf(old.exportCounter, disk.exportCount) + 1,
        logCheckpointByteOffset = nextByteOffset,
        logCheckpointRowOrdinal = nextRowOrdinal,
    )
    val properties = Properties().apply {
        setProperty("logCheckpointMs", updated.effectiveSnapshotCheckpointMs.toString())
        setProperty("videoCheckpointMs", updated.videoCheckpointMs.toString())
        setProperty("exportCounter", updated.exportCounter.toString())
        updated.logCheckpointByteOffset.takeIf { it >= 0L }?.let { setProperty("logCheckpointByteOffset", it.toString()) }
        if (updated.logCheckpointRowOrdinal > 0) setProperty("logCheckpointRowOrdinal", updated.logCheckpointRowOrdinal.toString())
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
        listOfNotNull(
            descriptor.log, descriptor.mapping, descriptor.video, descriptor.notes, descriptor.timeline,
            descriptor.blocks, descriptor.sparseSync,
        )
            .plus(descriptor.screenshots)
            .sortedBy { it.path }
            .forEach { asset ->
                append(asset.path).append(':').append(asset.sha256).append('\u0000')
            }
    }
    return MessageDigest.getInstance("SHA-256").digest(stable.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
}

private fun readImportedSnapshotCursors(file: File): ImportedSnapshotCursors {
    if (!file.isFile) return ImportedSnapshotCursors()
    return runCatching {
        val properties = Properties().apply { file.inputStream().use { load(it) } }
        ImportedSnapshotCursors(
            logMs = properties.getProperty("logCheckpointMs")?.toLongOrNull()?.coerceAtLeast(-1L) ?: -1L,
            videoMs = properties.getProperty("videoCheckpointMs")?.toLongOrNull()?.coerceAtLeast(-1L) ?: -1L,
            exportCount = properties.getProperty("exportCounter")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            byteOffset = properties.getProperty("logCheckpointByteOffset")?.toLongOrNull()?.coerceAtLeast(-1L) ?: -1L,
            rowOrdinal = properties.getProperty("logCheckpointRowOrdinal")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        )
    }.getOrDefault(ImportedSnapshotCursors())
}

/**
 * The sparse cache of an earlier open of this same archive, when it is still whole. [cacheDirectory] is named by the
 * archive's identity (path plus every descriptor asset checksum), so its contents can only have been derived from these
 * very inputs; this just rejects a half-written or damaged pair. The sync manifest must parse (its checksum covers the
 * payload) and agree with the log's byte length, the parsed row count and the timeline bounds, and the block index must
 * read cleanly over exactly that log. Block hashes are not re-verified: that would read the whole log again, which is
 * the cost this reuse exists to avoid. Null means rebuild.
 */
private fun reuseImportedSparseCache(
    imported: ImportedCapture,
    cacheDirectory: File,
    rowCount: Int,
    durationMs: Long,
): CaptureSyncMetadata? = runCatching {
    val indexFile = File(cacheDirectory, "mapping/blocks.jsonl")
    val syncFile = File(cacheDirectory, "timeline/sync.json")
    if (!indexFile.isFile || !syncFile.isFile) return@runCatching null
    val logBytes = imported.logFile.length()
    val metadata = CaptureSyncMetadata.parse(readTextShared(syncFile))
    val matches = metadata.logBytes == logBytes && metadata.rowCount == rowCount &&
        metadata.timelineBaseElapsedMs == imported.descriptor.timelineBaseElapsedMs && metadata.timelineDurationMs == durationMs
    if (!matches) return@runCatching null
    val index = CaptureBlockIndex.read(
        indexFile,
        imported.logFile,
        maxIndexBytes = indexFile.length(),
        maxLogBytes = logBytes,
        verifyByteRange = LongRange.EMPTY,
    )
    metadata.takeIf { index.indexedLogBytes == logBytes && index.indexedRows == rowCount }
}.getOrNull()

private fun buildImportedSparseCache(
    imported: ImportedCapture,
    cacheDirectory: File,
    parsedLog: List<LogEntry>,
    durationMs: Long,
): CaptureSyncMetadata {
    require(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) { "Cannot create imported sparse cache" }
    val indexFile = File(cacheDirectory, "mapping/blocks.jsonl")
    val syncFile = File(cacheDirectory, "timeline/sync.json")
    val metadata = legacyImportedTimingMetadata(imported, parsedLog, durationMs)
    val tempIndex = File(cacheDirectory, "mapping/.blocks-${UUID.randomUUID()}.tmp").apply { parentFile?.mkdirs() }
    val tempSync = File(cacheDirectory, "timeline/.sync-${UUID.randomUUID()}.tmp").apply { parentFile?.mkdirs() }
    try {
        val result = writeImportedBlockIndex(imported.logFile, parsedLog, metadata, durationMs, tempIndex)
        require(result.rowCount == parsedLog.size && result.logBytes == imported.logFile.length()) {
            "Imported capture block row or byte bounds do not match the log"
        }
        moveAtomically(tempIndex, indexFile)
        tempSync.writeText(metadata.toJson(), Charsets.UTF_8)
        moveAtomically(tempSync, syncFile)
        return metadata
    } finally {
        tempIndex.delete()
        tempSync.delete()
    }
}

private data class ImportedIndexWriteResult(val rowCount: Int, val logBytes: Long)

private class ImportedIndexWriteState(var byteOffset: Long = 0L, var rowOrdinal: Int = 0, var fallbackElapsed: Long = 0L)

private fun writeImportedBlockIndex(
    logFile: File,
    parsedLog: List<LogEntry>,
    metadata: CaptureSyncMetadata,
    durationMs: Long,
    target: File,
): ImportedIndexWriteResult {
    val state = ImportedIndexWriteState()
    CaptureBlockWriter(target).use { writer ->
        forEachRawLine(logFile) { raw -> appendImportedBlockLine(raw, parsedLog, metadata, durationMs, state, writer) }
        writer.freeze()
    }
    return ImportedIndexWriteResult(state.rowOrdinal, state.byteOffset)
}

private const val IMPORTED_READ_CHUNK_BYTES = 64 * 1024

/** Calls [onLine] with each raw line of [file], terminator included; a final unterminated line is delivered too. */
private fun forEachRawLine(file: File, onLine: (ByteArray) -> Unit) {
    file.inputStream().use { input ->
        val chunk = ByteArray(IMPORTED_READ_CHUNK_BYTES)
        val line = ByteArrayOutputStream()
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            var lineStart = 0
            for (index in 0 until read) {
                if (chunk[index] != NEWLINE_BYTE) continue
                line.write(chunk, lineStart, index + 1 - lineStart)
                onLine(line.toByteArray())
                line.reset()
                lineStart = index + 1
            }
            if (lineStart < read) line.write(chunk, lineStart, read - lineStart)
        }
        if (line.size() > 0) onLine(line.toByteArray())
    }
}

private fun appendImportedBlockLine(
    raw: ByteArray,
    parsedLog: List<LogEntry>,
    metadata: CaptureSyncMetadata,
    durationMs: Long,
    state: ImportedIndexWriteState,
    writer: CaptureBlockWriter,
) {
    val trimmed = raw.toString(Charsets.UTF_8).trim()
    val rowOrdinal = if (trimmed.isNotEmpty() && !trimmed.startsWith("-----")) ++state.rowOrdinal else null
    val entry = rowOrdinal?.let { parsedLog.getOrNull(it - 1) }
    val point = if (entry != null) sparseCaptureTimingPoint(entry, rowOrdinal, metadata) else null
    val resolvedElapsed = point?.captureElapsedMs
    val elapsed = when {
        resolvedElapsed != null -> resolvedElapsed.also { state.fallbackElapsed = it }
        rowOrdinal == null -> state.fallbackElapsed
        else -> 0L
    }
    val segmentId = rowOrdinal?.let { row -> metadata.segmentContaining(row)?.id }
    writer.append(
        state.byteOffset,
        raw,
        rowOrdinal,
        elapsed,
        segmentId,
        resolvedCaptureElapsedMs = if (rowOrdinal != null && resolvedElapsed == null) durationMs else resolvedElapsed,
    )
    state.byteOffset += raw.size
}

private fun moveAtomically(source: File, destination: File) {
    destination.parentFile?.mkdirs()
    try {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private data class LegacyImportedTiming(val segments: List<CaptureSyncSegment>, val uncertain: Boolean)

private fun legacyImportedTimingMetadata(
    imported: ImportedCapture,
    parsedLog: List<LogEntry>,
    durationMs: Long,
): CaptureSyncMetadata {
    val expectedRows = parsedLog.size
    val descriptor = imported.descriptor
    val baseElapsedMs = descriptor.timelineBaseElapsedMs
    val exactRows = imported.timeline?.rows?.takeIf { it.size == expectedRows }
    val converted = when {
        expectedRows == 0 -> LegacyImportedTiming(emptyList(), uncertain = false)
        exactRows != null -> convertLegacyExactRows(imported, parsedLog, exactRows, durationMs)
        descriptor.formatVersion == 3 -> convertLegacySingleAnchor(imported, parsedLog)
        else -> LegacyImportedTiming(listOf(uncertainImportedSegment(expectedRows)), uncertain = true)
    }
    return CaptureSyncMetadata(
        logBytes = imported.logFile.length(),
        rowCount = expectedRows,
        timelineBaseElapsedMs = baseElapsedMs,
        timelineDurationMs = durationMs,
        segments = converted.segments,
        uncertainBoundary = converted.uncertain ||
            (descriptor.formatVersion < 5 && converted.segments.any { it.reliability == CaptureSyncReliability.UNCERTAIN }),
    )
}

private fun convertLegacyExactRows(
    imported: ImportedCapture,
    parsedLog: List<LogEntry>,
    exactRows: List<CaptureMappingRow>,
    durationMs: Long,
): LegacyImportedTiming {
    val descriptor = imported.descriptor
    val baseElapsedMs = descriptor.timelineBaseElapsedMs
    val tracker = CaptureSyncRecorder(descriptor.sessionStartedEpochMs)
    parsedLog.forEachIndexed { index, entry ->
        tracker.observeParsedEntry(
            rowOrdinal = index + 1,
            entry = entry,
            captureElapsedMs = Math.addExact(baseElapsedMs, exactRows[index].elapsedMs),
            eligibleForAnchor = entry.tag != "IndagiumCapture",
        )
    }
    val tracked = tracker.snapshot(
        logBytes = imported.logFile.length(),
        rowCount = parsedLog.size,
        elapsedMs = Math.addExact(baseElapsedMs, durationMs),
        finalize = true,
    )
    val driftedIds = legacyTimingDriftedSegments(tracked, parsedLog, exactRows, baseElapsedMs, durationMs)
    val segments = tracked.segments.map { segment ->
        if (segment.id in driftedIds) segment.copy(reliability = CaptureSyncReliability.UNCERTAIN) else segment
    }
    return LegacyImportedTiming(segments, uncertain = driftedIds.isNotEmpty())
}

private fun legacyTimingDriftedSegments(
    tracked: CaptureSyncMetadata,
    parsedLog: List<LogEntry>,
    exactRows: List<CaptureMappingRow>,
    timelineBaseElapsedMs: Long,
    durationMs: Long,
): Set<Int> = buildSet {
    parsedLog.forEachIndexed { index, entry ->
        val ordinal = index + 1
        val segment = tracked.segmentContaining(ordinal) ?: return@forEachIndexed
        if (segment.reliability != CaptureSyncReliability.RELIABLE) return@forEachIndexed
        val delta = segment.clockReference?.let { clockDeltaMs(it, entry) } ?: return@forEachIndexed
        val anchorElapsed = segment.anchorCaptureElapsedMs ?: return@forEachIndexed
        val impliedAbsolute = runCatching { Math.addExact(anchorElapsed, delta) }.getOrNull()
        val impliedLocal = impliedAbsolute?.let { runCatching { Math.subtractExact(it, timelineBaseElapsedMs) }.getOrNull() }
        if (impliedLocal == null || impliedLocal !in 0L..durationMs ||
            captureTimingsDiffer(impliedLocal, exactRows[index].elapsedMs)
        ) {
            add(segment.id)
        }
    }
}

private fun convertLegacySingleAnchor(imported: ImportedCapture, parsedLog: List<LogEntry>): LegacyImportedTiming {
    val anchor = imported.syncAnchor
    val rowCount = parsedLog.size
    if (anchor == null || anchor.row !in 1..rowCount) {
        return LegacyImportedTiming(listOf(uncertainImportedSegment(rowCount)), uncertain = true)
    }
    val entry = parsedLog[anchor.row - 1]
    val captureElapsed = runCatching {
        Math.addExact(imported.descriptor.videoStartMs ?: 0L, anchor.videoMs)
    }.getOrNull()?.takeIf { it >= 0L }
    val approximateEpoch = captureElapsed?.let {
        runCatching { Math.addExact(imported.descriptor.sessionStartedEpochMs, it) }.getOrNull()
    } ?: imported.descriptor.sessionStartedEpochMs
    val clock = captureClockReference(entry, approximateEpoch)
    val segment = if (captureElapsed != null && clock != null) {
        CaptureSyncSegment(
            id = 0,
            firstRow = 1,
            lastRowExclusive = rowCount + 1,
            anchorLogTimeMs = clockReferenceEpochMs(clock),
            anchorCaptureElapsedMs = captureElapsed,
            reliability = CaptureSyncReliability.RELIABLE,
            clockReference = clock,
        )
    } else {
        uncertainImportedSegment(rowCount)
    }
    return LegacyImportedTiming(listOf(segment), uncertain = segment.reliability == CaptureSyncReliability.UNCERTAIN)
}

private fun uncertainImportedSegment(rowCount: Int) = CaptureSyncSegment(
    id = 0,
    firstRow = 1,
    lastRowExclusive = rowCount + 1,
    anchorLogTimeMs = null,
    anchorCaptureElapsedMs = null,
    reliability = CaptureSyncReliability.UNCERTAIN,
)
