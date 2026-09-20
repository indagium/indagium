package com.indagium.capture

import com.indagium.utils.MAX_ARCHIVE_ENTRIES_SCANNED
import com.indagium.utils.MAX_ARCHIVE_ENTRY_BYTES
import com.indagium.utils.openLogTextReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

const val CAPTURE_DESCRIPTOR_NAME = "capture.indagium.json"
const val CAPTURE_ARCHIVE_VERSION = 1

private const val MAX_DESCRIPTOR_BYTES = 1024 * 1024L
private const val MAX_MAPPING_BYTES = MAX_ARCHIVE_ENTRY_BYTES
private const val MAX_MAPPING_ROWS = 10_000_000
private const val MAX_MAPPING_LINE_BYTES = 64 * 1024
private const val MAX_SCREENSHOT_BYTES = 100 * 1024 * 1024L
private const val MAX_SCREENSHOTS_BYTES = 500 * 1024 * 1024L
private const val MAX_VIDEO_BYTES = 20L * 1024L * 1024L * 1024L
private const val COPY_BUFFER_BYTES = 128 * 1024
private const val PREFLIGHT_OVERHEAD_BYTES = 16L * 1024L * 1024L
private const val RANGE_MINUTES = 60_000L
private const val LAST_FIVE_MINUTES = 5L
private const val LAST_TEN_MINUTES = 10L
private const val MAX_SCREENSHOT_NAME_LENGTH = 80
private const val MAX_CAPTURE_FILENAME_STEM_LENGTH = 180

data class CaptureArchiveAsset(val path: String, val sizeBytes: Long, val sha256: String)

data class CaptureArchiveDevice(
    val serial: String,
    val state: String,
    val model: String,
    val emulator: Boolean,
)

data class CaptureArchiveCoverage(
    val logStartMs: Long,
    val logEndMs: Long,
    val videoRequestedStartMs: Long?,
    val videoActualStartMs: Long?,
    val videoEndMs: Long?,
)

data class CaptureArchiveDescriptor(
    val formatVersion: Int,
    val sessionId: String,
    val device: CaptureArchiveDevice,
    val settings: CaptureSettings,
    val sessionStartedEpochMs: Long,
    val exportedEpochMs: Long,
    val range: CaptureRange,
    val coverage: CaptureArchiveCoverage,
    val quality: String,
    val uncertaintyMs: Long?,
    val manualOffsetMs: Long,
    val interruptions: List<String>,
    val log: CaptureArchiveAsset,
    val mapping: CaptureArchiveAsset,
    val video: CaptureArchiveAsset?,
    val screenshots: List<CaptureArchiveAsset>,
)

data class ImportedCapture(
    val logFile: File,
    val videoFile: File?,
    val timeline: CaptureTimeline,
    val descriptor: CaptureArchiveDescriptor,
    /** The archive, descriptor, or extracted directory supplied by the caller. */
    val source: File,
)

class CaptureArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Serializes exports per exporter instance. Interrupting the export thread cancels FFmpeg/copying
 * and leaves no destination or temporary archive behind.
 */
class CaptureArchiveExporter(
    private val videoExporter: CaptureVideoExporter = FfmpegCaptureVideoExporter(),
) {
    @Synchronized
    @Suppress("CyclomaticComplexMethod")
    fun export(session: CaptureSession, request: CaptureExportRequest): CaptureExportResult {
        checkNotInterrupted()
        require(request.customMinutes > 0) { "Custom capture range must be positive" }
        val destination = request.destination.absoluteFile
        require(!destination.exists()) { "Capture destination already exists: $destination" }
        val parent = destination.parentFile ?: File(".").absoluteFile
        require(parent.mkdirs() || parent.isDirectory) { "Cannot create capture destination directory: $parent" }
        require(session.logFile.isFile) { "Capture log is missing: ${session.logFile}" }
        require(session.indexFile.isFile) { "Capture index is missing: ${session.indexFile}" }

        // These lengths are the export's immutable request-time boundary. Appends after this point
        // are invisible, and a partial final index line is discarded while freezing selection.
        val frozenLogBytes = session.logFile.length()
        val frozenIndexBytes = session.indexFile.length()
        val logStartMs = rangeStartMs(request.range, request.cutoffElapsedMs, request.customMinutes, session.logCheckpointMs)
        val videoRangeStartMs = rangeStartMs(request.range, request.cutoffElapsedMs, request.customMinutes, session.videoCheckpointMs)
        val videoConfigured = session.settings.recordVideo && request.includeVideo
        if (videoConfigured) {
            require(session.videoStartElapsedMs != null) { "Capture video has no start-time reference" }
            require(session.videoFile.isFile) { "Capture video is missing: ${session.videoFile}" }
        }
        val includeVideo = videoConfigured
        val snapshotScreenshots = snapshotScreenshots(session.directory, logStartMs, request.cutoffElapsedMs)
        require(snapshotScreenshots.sumOf { it.length } <= MAX_SCREENSHOTS_BYTES) {
            "Capture screenshots exceed the combined size limit"
        }
        val work = Files.createTempDirectory(parent.toPath(), ".${safeStem(destination.name)}-export-").toFile()
        val archiveTemp = File(parent, ".${destination.name}.tmp-${UUID.randomUUID()}")
        var published = false
        try {
            val selection = freezeSelection(
                indexFile = session.indexFile,
                frozenBytes = frozenIndexBytes,
                frozenLogBytes = frozenLogBytes,
                selectedStartMs = logStartMs,
                selectedEndMs = request.cutoffElapsedMs,
                excludeStart = request.range == CaptureRange.SINCE_SAVE && session.logCheckpointMs >= 0,
                destination = File(work, ".selected-index.jsonl"),
            )
            require(selection.recordCount > 0 || (request.range == CaptureRange.SINCE_SAVE && includeVideo)) {
                "The selected capture range contains no complete log lines"
            }
            preflight(
                destination = destination,
                reserveBytes = session.settings.freeSpaceReserveBytes,
                logBytes = selection.logBytes,
                videoBytes = if (includeVideo) session.videoFile.length() else 0,
                screenshotsBytes = snapshotScreenshots.sumOf { it.length },
            )
            val stagedLog = File(work, "logs/logcat.log").also { it.parentFile.mkdirs() }
            copySelectedLog(session.logFile, selection.indexFile, stagedLog)

            val stagedVideo = if (includeVideo) File(work, "video/screen.mkv").also { it.parentFile.mkdirs() } else null
            val videoStartElapsed = session.videoStartElapsedMs
            val clip = if (stagedVideo != null && videoStartElapsed != null) {
                val requestedSourceStart = (videoRangeStartMs - videoStartElapsed + session.manualOffsetMs).coerceAtLeast(0)
                val requestedSourceEnd = (request.cutoffElapsedMs - videoStartElapsed + session.manualOffsetMs)
                    .coerceAtLeast(requestedSourceStart)
                videoExporter.export(session.videoFile, stagedVideo, requestedSourceStart, requestedSourceEnd)
            } else {
                null
            }
            checkNotInterrupted()

            val stagedMapping = File(work, "mapping/log-video.jsonl").also { it.parentFile.mkdirs() }
            writeMapping(stagedMapping, selection.indexFile, session, clip)
            val stagedScreenshots = stageScreenshots(snapshotScreenshots, work)

            val logAsset = asset("logs/logcat.log", stagedLog)
            val mappingAsset = asset("mapping/log-video.jsonl", stagedMapping)
            val videoAsset = stagedVideo?.takeIf(File::isFile)?.let { asset("video/screen.mkv", it) }
            val screenshotAssets = stagedScreenshots.map { (path, file) -> asset(path, file) }
            val descriptor = CaptureArchiveDescriptor(
                formatVersion = CAPTURE_ARCHIVE_VERSION,
                sessionId = session.id,
                device = CaptureArchiveDevice(
                    session.device.serial,
                    session.device.state,
                    session.device.model,
                    session.device.emulator,
                ),
                settings = session.settings.copy(adbPath = "", scrcpyPath = ""),
                sessionStartedEpochMs = session.startedEpochMs,
                exportedEpochMs = System.currentTimeMillis(),
                range = request.range,
                coverage = CaptureArchiveCoverage(
                    logStartMs = selection.firstElapsedMs ?: request.cutoffElapsedMs,
                    logEndMs = selection.lastElapsedMs ?: request.cutoffElapsedMs,
                    videoRequestedStartMs = if (clip != null) videoRangeStartMs else null,
                    videoActualStartMs = clip?.actualStartMs?.plus(videoStartElapsed ?: 0)?.minus(session.manualOffsetMs),
                    videoEndMs = clip?.coveredEndMs?.plus(videoStartElapsed ?: 0)?.minus(session.manualOffsetMs),
                ),
                quality = "estimated",
                uncertaintyMs = null,
                manualOffsetMs = session.manualOffsetMs,
                interruptions = session.interruptions,
                log = logAsset,
                mapping = mappingAsset,
                video = videoAsset,
                screenshots = screenshotAssets,
            )
            File(work, CAPTURE_DESCRIPTOR_NAME).writeText(descriptor.toJson(), Charsets.UTF_8)
            writeZip(work, archiveTemp, descriptor)
            checkNotInterrupted()
            publishWithoutOverwrite(archiveTemp, destination)
            published = true
            return CaptureExportResult(
                file = destination,
                logCoveredEndMs = selection.lastElapsedMs ?: request.cutoffElapsedMs,
                videoCoveredEndMs = descriptor.coverage.videoEndMs,
                videoActualStartMs = descriptor.coverage.videoActualStartMs,
                message = if (videoAsset != null) "Capture exported with video" else "Capture exported",
            )
        } finally {
            deleteTree(work)
            if (!published) archiveTemp.delete()
        }
    }
}

object CaptureArchiveReader {
    fun isCaptureArchive(file: File): Boolean = runCatching {
        when {
            file.isDirectory -> isCaptureDescriptor(readSmallFile(descriptorFile(file)))
            file.isFile && file.name == CAPTURE_DESCRIPTOR_NAME -> isCaptureDescriptor(readSmallFile(file))
            file.isFile -> ZipFile.builder().setFile(file).get().use { zip ->
                val descriptor = findDescriptorEntry(zip) ?: return@use false
                val raw = zip.getInputStream(descriptor).use { readBoundedText(it, MAX_DESCRIPTOR_BYTES) }
                isCaptureDescriptor(raw)
            }
            else -> false
        }
    }.getOrDefault(false)

    fun open(file: File, cacheDirectory: File): ImportedCapture {
        require(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) { "Cannot create capture cache: $cacheDirectory" }
        return when {
            file.isDirectory -> openExtracted(descriptorFile(file), file, file)
            file.isFile && file.name == CAPTURE_DESCRIPTOR_NAME -> openExtracted(file, file.parentFile, file)
            file.isFile -> openZip(file, cacheDirectory)
            else -> throw CaptureArchiveException("Capture does not exist: $file")
        }
    }

    private fun openZip(source: File, cacheDirectory: File): ImportedCapture {
        val output = File(cacheDirectory, "capture-${UUID.randomUUID()}")
        require(output.mkdir()) { "Cannot create capture extraction directory: $output" }
        var success = false
        try {
            val descriptor: CaptureArchiveDescriptor
            ZipFile.builder().setFile(source).get().use { zip ->
                val descriptorEntry = validateEntriesAndFindDescriptor(zip)
                    ?: throw CaptureArchiveException("Capture descriptor is missing")
                require(!descriptorEntry.isDirectory) { "Capture descriptor is not a file" }
                val descriptorText = zip.getInputStream(descriptorEntry).use { readBoundedText(it, MAX_DESCRIPTOR_BYTES) }
                descriptor = parseDescriptor(descriptorText)
                require(descriptor.screenshots.sumOf { it.sizeBytes } <= MAX_SCREENSHOTS_BYTES) {
                    "Capture screenshots exceed the combined size limit"
                }
                requireExtractionSpace(cacheDirectory, descriptor)
                val remainingAssets = descriptor.assets().associateBy { it.path }.toMutableMap()
                val entries = zip.entries
                var entryCount = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    entryCount++
                    require(entryCount <= MAX_ARCHIVE_ENTRIES_SCANNED) { "Capture archive has too many entries" }
                    if (entry.isDirectory) continue
                    val name = normalizedEntryName(entry.name)
                    if (name == CAPTURE_DESCRIPTOR_NAME) continue
                    val asset = remainingAssets.remove(name)
                        ?: throw CaptureArchiveException("Capture archive contains an unreferenced file: $name")
                    val limit = assetLimit(descriptor, asset)
                    require(asset.sizeBytes in 0..limit) { "Capture asset exceeds its size limit: ${asset.path}" }
                    if (entry.size >= 0) require(entry.size == asset.sizeBytes) { "Capture asset size mismatch: ${asset.path}" }
                    val target = resolveSafe(output, asset.path)
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> extractVerified(input, target, asset, limit) }
                }
                require(remainingAssets.isEmpty()) {
                    "Capture asset is missing: ${remainingAssets.keys.first()}"
                }
            }
            val imported = validateImported(output, descriptor, source)
            success = true
            return imported
        } finally {
            if (!success) deleteTree(output)
        }
    }

    private fun openExtracted(descriptorFile: File, root: File, source: File): ImportedCapture {
        require(descriptorFile.isFile) { "Capture descriptor is missing: $descriptorFile" }
        require(!Files.isSymbolicLink(descriptorFile.toPath())) { "Capture descriptor cannot be a symbolic link" }
        val descriptor = parseDescriptor(readSmallFile(descriptorFile))
        require(descriptor.screenshots.sumOf { it.sizeBytes } <= MAX_SCREENSHOTS_BYTES) {
            "Capture screenshots exceed the combined size limit"
        }
        descriptor.assets().forEach { asset ->
            require(asset.sizeBytes <= assetLimit(descriptor, asset)) {
                "Capture asset exceeds its size limit: ${asset.path}"
            }
            val target = resolveSafe(root, asset.path)
            require(!hasSymbolicLink(root, target)) { "Capture asset uses a symbolic link: ${asset.path}" }
            require(target.isFile) { "Capture asset is missing: ${asset.path}" }
            require(target.length() == asset.sizeBytes) { "Capture asset size mismatch: ${asset.path}" }
            require(sha256(target) == asset.sha256) { "Capture asset checksum mismatch: ${asset.path}" }
        }
        return validateImported(root, descriptor, source)
    }

    private fun validateImported(root: File, descriptor: CaptureArchiveDescriptor, source: File): ImportedCapture {
        val logFile = resolveSafe(root, descriptor.log.path)
        val mappingFile = resolveSafe(root, descriptor.mapping.path)
        val videoFile = descriptor.video?.let { resolveSafe(root, it.path) }
        val rows = readMapping(mappingFile)
        val parsedLogCount = logFile.inputStream().use { input ->
            openLogTextReader(input).useLines { lines -> lines.count { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() && !trimmed.startsWith("-----")
            } }
        }
        require(rows.size == parsedLogCount) {
            "Capture mapping row count ${rows.size} does not match parsed log row count $parsedLogCount"
        }
        return ImportedCapture(
            logFile = logFile,
            videoFile = videoFile,
            timeline = CaptureTimeline(rows, descriptor.quality, descriptor.uncertaintyMs, descriptor.manualOffsetMs),
            descriptor = descriptor,
            source = source,
        )
    }
}

/** Detection deliberately accepts future versions so [open] can report the supported-version error. */
private fun isCaptureDescriptor(raw: String): Boolean = runCatching {
    Json.parseToJsonElement(raw).jsonObject.string("format") == "indagium-capture"
}.getOrDefault(false)

private fun findDescriptorEntry(zip: ZipFile): org.apache.commons.compress.archivers.zip.ZipArchiveEntry? {
    val entries = zip.entries
    var entryCount = 0
    var descriptor: org.apache.commons.compress.archivers.zip.ZipArchiveEntry? = null
    while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        entryCount++
        if (entryCount > MAX_ARCHIVE_ENTRIES_SCANNED) return null
        if (!entry.isDirectory && normalizedEntryName(entry.name) == CAPTURE_DESCRIPTOR_NAME) {
            if (descriptor != null) return null
            descriptor = entry
        }
    }
    return descriptor
}

private fun validateEntriesAndFindDescriptor(zip: ZipFile): org.apache.commons.compress.archivers.zip.ZipArchiveEntry? {
    val names = HashSet<String>()
    val entries = zip.entries
    var entryCount = 0
    var descriptor: org.apache.commons.compress.archivers.zip.ZipArchiveEntry? = null
    while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        entryCount++
        require(entryCount <= MAX_ARCHIVE_ENTRIES_SCANNED) { "Capture archive has too many entries" }
        val name = normalizedEntryName(entry.name).trimEnd('/')
        require(isSafeRelativePath(name)) { "Unsafe capture archive path: ${entry.name}" }
        require(!entry.isUnixSymlink) { "Capture archive contains a symbolic link: $name" }
        if (!entry.isDirectory) {
            require(names.add(name)) { "Capture archive contains duplicate entry: $name" }
            if (name == CAPTURE_DESCRIPTOR_NAME) descriptor = entry
        }
    }
    return descriptor
}

private data class FrozenScreenshot(val source: File, val length: Long, val path: String)

private fun snapshotScreenshots(sessionDirectory: File, startElapsedMs: Long, endElapsedMs: Long): List<FrozenScreenshot> {
    val directory = File(sessionDirectory, "screenshots")
    if (!directory.isDirectory) return emptyList()
    var index = 0
    return directory.walkTopDown().filter { file ->
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) return@filter false
        val elapsed = SCREENSHOT_NAME.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
        elapsed != null && elapsed in startElapsedMs..endElapsedMs
    }.map { file ->
        checkNotInterrupted()
        require(file.length() <= MAX_SCREENSHOT_BYTES) { "Capture screenshot exceeds the size limit: ${file.name}" }
        val extension = file.extension.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }?.lowercase(Locale.ROOT)
        val name = sanitizeFilename(file.nameWithoutExtension).ifBlank { "screenshot" }
        val suffix = if (extension == null) "" else ".$extension"
        FrozenScreenshot(file, file.length(), "screenshots/${++index}-${name.take(MAX_SCREENSHOT_NAME_LENGTH)}$suffix")
    }.toList()
}

private fun stageScreenshots(screenshots: List<FrozenScreenshot>, work: File): List<Pair<String, File>> = screenshots.map { shot ->
    val target = resolveSafe(work, shot.path)
    target.parentFile?.mkdirs()
    shot.source.inputStream().use { input -> target.outputStream().use { output -> copyExactly(input, output, shot.length) } }
    require(shot.source.length() >= shot.length) { "Capture screenshot changed during export: ${shot.source.name}" }
    shot.path to target
}

private val SCREENSHOT_NAME = Regex("screenshot-(\\d+)\\.[A-Za-z0-9]{1,8}")

private data class FrozenSelection(
    val indexFile: File,
    val recordCount: Long,
    val logBytes: Long,
    val firstElapsedMs: Long?,
    val lastElapsedMs: Long?,
)

/**
 * Spools only the selected frozen index rows to disk. A recording can have millions of index rows,
 * so retaining the whole session index in memory just to make an export is not acceptable.
 */
private fun freezeSelection(
    indexFile: File,
    frozenBytes: Long,
    frozenLogBytes: Long,
    selectedStartMs: Long,
    selectedEndMs: Long,
    excludeStart: Boolean,
    destination: File,
): FrozenSelection {
    var recordCount = 0L
    var mappingRecordCount = 0L
    var logBytes = 0L
    var spooledIndexBytes = 0L
    var firstElapsedMs: Long? = null
    var lastElapsedMs: Long? = null
    var lastRecord: CaptureLogIndexRecord? = null
    destination.bufferedWriter(Charsets.UTF_8).use { output ->
        indexFile.inputStream().use { base ->
            val input = LimitedInputStream(BufferedInputStream(base), frozenBytes)
            forEachCompleteLine(input, MAX_MAPPING_LINE_BYTES) { line ->
                checkNotInterrupted()
                val record = parseIndexRecord(line)
                require(record.byteOffset >= 0 && record.byteLength >= 0) { "Capture index contains a negative byte range" }
                require(record.byteOffset <= frozenLogBytes - record.byteLength) {
                    "Capture index points beyond the frozen log boundary"
                }
                val previous = lastRecord
                require(previous == null || record.byteOffset >= previous.byteOffset + previous.byteLength) {
                    "Capture index byte ranges are not ordered"
                }
                require(previous == null || record.elapsedMs >= previous.elapsedMs) { "Capture index times are not ordered" }
                lastRecord = record
                val afterStart = if (excludeStart) record.elapsedMs > selectedStartMs else record.elapsedMs >= selectedStartMs
                if (afterStart && record.elapsedMs <= selectedEndMs) {
                    val encodedLineBytes = line.toByteArray(Charsets.UTF_8).size.toLong() + 1L
                    require(spooledIndexBytes <= MAX_MAPPING_BYTES - encodedLineBytes) {
                        "Selected capture mapping exceeds the size limit"
                    }
                    require(logBytes <= MAX_ARCHIVE_ENTRY_BYTES - record.byteLength) {
                        "Selected capture log exceeds the size limit"
                    }
                    if (record.rowOrdinal != null) {
                        require(mappingRecordCount < MAX_MAPPING_ROWS) { "Selected capture mapping has too many rows" }
                        mappingRecordCount++
                    }
                    output.append(line)
                    output.newLine()
                    recordCount++
                    logBytes += record.byteLength
                    spooledIndexBytes += encodedLineBytes
                    if (firstElapsedMs == null) firstElapsedMs = record.elapsedMs
                    lastElapsedMs = record.elapsedMs
                }
            }
        }
    }
    return FrozenSelection(destination, recordCount, logBytes, firstElapsedMs, lastElapsedMs)
}

private fun forEachSelectedRecord(indexFile: File, block: (CaptureLogIndexRecord) -> Unit) {
    indexFile.inputStream().use { input ->
        forEachCompleteLine(BufferedInputStream(input), MAX_MAPPING_LINE_BYTES, rejectTrailingPartial = true) { line ->
            block(parseIndexRecord(line))
        }
    }
}

private fun parseIndexRecord(line: String): CaptureLogIndexRecord {
    val root = Json.parseToJsonElement(line).jsonObject
    val offset = root.long("byteOffset") ?: error("Capture index row is missing byteOffset")
    val length = root.int("byteLength") ?: error("Capture index row is missing byteLength")
    val elapsed = root.long("elapsedMs") ?: error("Capture index row is missing elapsedMs")
    val ordinalElement = root["rowOrdinal"]
    val ordinal = if (ordinalElement == null || ordinalElement is JsonNull) null else (ordinalElement as? JsonPrimitive)?.content?.toIntOrNull()
        ?: error("Capture index row has an invalid rowOrdinal")
    return CaptureLogIndexRecord(offset, length, elapsed, ordinal)
}

private fun rangeStartMs(range: CaptureRange, cutoffMs: Long, customMinutes: Int, checkpointMs: Long): Long = when (range) {
    CaptureRange.ALL -> Long.MIN_VALUE
    CaptureRange.LAST_FIVE -> cutoffMs - LAST_FIVE_MINUTES * RANGE_MINUTES
    CaptureRange.LAST_TEN -> cutoffMs - LAST_TEN_MINUTES * RANGE_MINUTES
    CaptureRange.CUSTOM -> cutoffMs - customMinutes.toLong() * RANGE_MINUTES
    CaptureRange.SINCE_SAVE -> if (checkpointMs >= 0) checkpointMs else Long.MIN_VALUE
}

private fun copySelectedLog(source: File, selectionIndex: File, destination: File) {
    RandomAccessFile(source, "r").use { input ->
        BufferedOutputStream(destination.outputStream()).use { output ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            forEachSelectedRecord(selectionIndex) { record ->
                checkNotInterrupted()
                input.seek(record.byteOffset)
                var remaining = record.byteLength
                while (remaining > 0) {
                    checkNotInterrupted()
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) throw CaptureArchiveException("Capture log was truncated during export")
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }
}

private fun writeMapping(
    file: File,
    selectionIndex: File,
    session: CaptureSession,
    clip: CaptureVideoClip?,
) {
    var ordinal = 0
    var bytesWritten = 0L
    val videoStart = session.videoStartElapsedMs
    file.bufferedWriter(Charsets.UTF_8).use { writer ->
        forEachSelectedRecord(selectionIndex) { record ->
            if (record.rowOrdinal == null) return@forEachSelectedRecord
            ordinal += 1
            val sourceVideoMs = videoStart?.let { record.elapsedMs - it + session.manualOffsetMs }
            val exportedVideoMs = if (
                clip != null && sourceVideoMs != null && sourceVideoMs in clip.actualStartMs..clip.coveredEndMs
            ) {
                sourceVideoMs - clip.actualStartMs
            } else {
                null
            }
            val row = buildJsonObject {
                put("ordinal", ordinal)
                put("elapsedMs", record.elapsedMs)
                if (exportedVideoMs != null) put("videoMs", exportedVideoMs) else put("videoMs", JsonNull)
            }.toString()
            val rowBytes = row.toByteArray(Charsets.UTF_8).size.toLong() + 1L
            require(bytesWritten <= MAX_MAPPING_BYTES - rowBytes) { "Capture mapping exceeds the size limit" }
            writer.append(row)
            writer.newLine()
            bytesWritten += rowBytes
        }
    }
}

private fun readMapping(file: File): List<CaptureMappingRow> {
    require(file.length() <= MAX_MAPPING_BYTES) { "Capture mapping exceeds the size limit" }
    val rows = ArrayList<CaptureMappingRow>()
    file.inputStream().use { input ->
        forEachCompleteLine(BufferedInputStream(input), MAX_MAPPING_LINE_BYTES, rejectTrailingPartial = true) { line ->
            require(rows.size < MAX_MAPPING_ROWS) { "Capture mapping has too many rows" }
            val root = Json.parseToJsonElement(line).jsonObject
            val ordinal = root.int("ordinal") ?: error("Capture mapping row is missing ordinal")
            require(ordinal == rows.size + 1) { "Capture mapping ordinals are not contiguous" }
            val elapsed = root.long("elapsedMs") ?: error("Capture mapping row is missing elapsedMs")
            val previous = rows.lastOrNull()
            require(previous == null || elapsed >= previous.elapsedMs) { "Capture mapping times are not ordered" }
            val videoElement = root["videoMs"]
            val video = if (videoElement == null || videoElement is JsonNull) null else (videoElement as? JsonPrimitive)?.content?.toLongOrNull()
                ?: error("Capture mapping row has invalid videoMs")
            require(video == null || video >= 0) { "Capture mapping contains a negative video position" }
            rows += CaptureMappingRow(ordinal, elapsed, video)
        }
    }
    return rows
}

private fun CaptureArchiveDescriptor.toJson(): String = buildJsonObject {
    put("format", "indagium-capture")
    put("formatVersion", formatVersion)
    put("sessionId", sessionId)
    put("device", buildJsonObject {
        put("serial", device.serial)
        put("state", device.state)
        put("model", device.model)
        put("emulator", device.emulator)
    })
    put("settings", Json.parseToJsonElement(captureSettingsToJson(settings)))
    put("sessionStartedEpochMs", sessionStartedEpochMs)
    put("exportedEpochMs", exportedEpochMs)
    put("range", range.name)
    put("coverage", buildJsonObject {
        put("logStartMs", coverage.logStartMs)
        put("logEndMs", coverage.logEndMs)
        nullableLong("videoRequestedStartMs", coverage.videoRequestedStartMs)
        nullableLong("videoActualStartMs", coverage.videoActualStartMs)
        nullableLong("videoEndMs", coverage.videoEndMs)
    })
    put("mappingMetadata", buildJsonObject {
        put("quality", quality)
        nullableLong("uncertaintyMs", uncertaintyMs)
        put("manualOffsetMs", manualOffsetMs)
    })
    put("interruptions", buildJsonArray { interruptions.forEach { add(it) } })
    put("log", log.toJson())
    put("mapping", mapping.toJson())
    if (video != null) put("video", video.toJson()) else put("video", JsonNull)
    put("screenshots", buildJsonArray { screenshots.forEach { add(it.toJson()) } })
}.toString()

private fun kotlinx.serialization.json.JsonObjectBuilder.nullableLong(name: String, value: Long?) {
    if (value == null) put(name, JsonNull) else put(name, value)
}

private fun CaptureArchiveAsset.toJson(): JsonObject = buildJsonObject {
    put("path", path)
    put("sizeBytes", sizeBytes)
    put("sha256", sha256)
}

@Suppress("TooGenericExceptionCaught")
private fun parseDescriptor(raw: String): CaptureArchiveDescriptor {
    try {
        val root = Json.parseToJsonElement(raw).jsonObject
        require(root.string("format") == "indagium-capture") { "Not an Indagium capture descriptor" }
        val version = root.int("formatVersion") ?: error("Capture descriptor is missing formatVersion")
        require(version == CAPTURE_ARCHIVE_VERSION) { "Unsupported capture archive version: $version" }
        val device = root.requiredObject("device")
        val settingsElement = root["settings"] ?: error("Capture descriptor is missing settings")
        val settings = captureSettingsFromJson(settingsElement.toString()) ?: error("Capture settings are invalid")
        val coverage = root.requiredObject("coverage")
        val metadata = root.requiredObject("mappingMetadata")
        val descriptor = CaptureArchiveDescriptor(
            formatVersion = version,
            sessionId = root.requiredString("sessionId"),
            device = CaptureArchiveDevice(
                serial = device.requiredString("serial"),
                state = device.string("state") ?: "device",
                model = device.requiredString("model"),
                emulator = device.boolean("emulator") ?: false,
            ),
            settings = settings,
            sessionStartedEpochMs = root.requiredLong("sessionStartedEpochMs"),
            exportedEpochMs = root.requiredLong("exportedEpochMs"),
            range = root.requiredString("range").let { CaptureRange.valueOf(it) },
            coverage = CaptureArchiveCoverage(
                logStartMs = coverage.requiredLong("logStartMs"),
                logEndMs = coverage.requiredLong("logEndMs"),
                videoRequestedStartMs = coverage.nullableLong("videoRequestedStartMs"),
                videoActualStartMs = coverage.nullableLong("videoActualStartMs"),
                videoEndMs = coverage.nullableLong("videoEndMs"),
            ),
            quality = metadata.string("quality") ?: "estimated",
            uncertaintyMs = metadata.nullableLong("uncertaintyMs"),
            manualOffsetMs = metadata.long("manualOffsetMs") ?: 0,
            interruptions = root.stringList("interruptions") ?: emptyList(),
            log = root.requiredObject("log").toAsset(),
            mapping = root.requiredObject("mapping").toAsset(),
            video = (root["video"] as? JsonObject)?.toAsset(),
            screenshots = (root["screenshots"] as? JsonArray)?.map { it.jsonObject.toAsset() } ?: emptyList(),
        )
        require(descriptor.sessionId.isNotBlank()) { "Capture session id is blank" }
        require(descriptor.coverage.logEndMs >= descriptor.coverage.logStartMs) { "Capture log coverage is invalid" }
        descriptor.assets().forEach { asset ->
            require(isSafeRelativePath(asset.path)) { "Unsafe capture asset path: ${asset.path}" }
            require(asset.sizeBytes >= 0) { "Capture asset has a negative size: ${asset.path}" }
            require(asset.sha256.matches(Regex("[0-9a-f]{64}"))) { "Capture asset has an invalid SHA-256: ${asset.path}" }
        }
        require(descriptor.assets().map { it.path }.distinct().size == descriptor.assets().size) {
            "Capture descriptor repeats an asset path"
        }
        require(descriptor.assets().none { it.path == CAPTURE_DESCRIPTOR_NAME }) {
            "Capture descriptor cannot also be an asset"
        }
        return descriptor
    } catch (e: CaptureArchiveException) {
        throw e
    } catch (e: Exception) {
        throw CaptureArchiveException(e.message ?: "Invalid capture descriptor", e)
    }
}

private fun JsonObject.toAsset(): CaptureArchiveAsset = CaptureArchiveAsset(
    path = requiredString("path"),
    sizeBytes = requiredLong("sizeBytes"),
    sha256 = requiredString("sha256"),
)

private fun CaptureArchiveDescriptor.assets(): List<CaptureArchiveAsset> =
    buildList {
        add(log)
        add(mapping)
        video?.let(::add)
        addAll(screenshots)
    }

private fun JsonObject.requiredObject(key: String): JsonObject = this[key] as? JsonObject
    ?: error("Capture descriptor is missing $key")

private fun JsonObject.requiredString(key: String): String = string(key)
    ?: error("Capture descriptor is missing $key")

private fun JsonObject.requiredLong(key: String): Long = long(key)
    ?: error("Capture descriptor is missing $key")

private fun JsonObject.nullableLong(key: String): Long? {
    val value = this[key] ?: return null
    return if (value is JsonNull) null else (value as? JsonPrimitive)?.content?.toLongOrNull()
        ?: error("Capture descriptor has invalid $key")
}

private fun asset(path: String, file: File): CaptureArchiveAsset =
    CaptureArchiveAsset(path, file.length(), sha256(file))

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            checkNotInterrupted()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun writeZip(work: File, destination: File, descriptor: CaptureArchiveDescriptor) {
    ZipOutputStream(BufferedOutputStream(destination.outputStream())).use { zip ->
        val paths = listOf(CAPTURE_DESCRIPTOR_NAME) + descriptor.assets().map { it.path }
        paths.forEach { path ->
            checkNotInterrupted()
            val source = resolveSafe(work, path)
            zip.putNextEntry(ZipEntry(path).apply { time = 0 })
            source.inputStream().use { input -> copyInterruptibly(input, zip) }
            zip.closeEntry()
        }
    }
}

private fun preflight(destination: File, reserveBytes: Long, logBytes: Long, videoBytes: Long, screenshotsBytes: Long) {
    val payload = saturatingAdd(saturatingAdd(logBytes, videoBytes), screenshotsBytes)
    val workingAndArchive = saturatingAdd(payload, payload)
    val required = saturatingAdd(saturatingAdd(workingAndArchive, PREFLIGHT_OVERHEAD_BYTES), reserveBytes)
    val usable = destination.parentFile?.usableSpace ?: 0
    require(usable >= required) { "Not enough free space to export capture (need $required bytes, have $usable bytes)" }
}

private fun saturatingAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

private fun publishWithoutOverwrite(temp: File, destination: File) {
    require(!destination.exists()) { "Capture destination already exists: $destination" }
    try {
        Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temp.toPath(), destination.toPath())
    }
}

private fun extractVerified(input: InputStream, target: File, asset: CaptureArchiveAsset, limit: Long) {
    val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
    target.outputStream().buffered().use { output ->
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            require(count <= limit && count <= asset.sizeBytes) { "Capture asset exceeds its declared size: ${asset.path}" }
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
        }
    }
    require(count == asset.sizeBytes) { "Capture asset size mismatch: ${asset.path}" }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    require(actual == asset.sha256) { "Capture asset checksum mismatch: ${asset.path}" }
}

private fun assetLimit(descriptor: CaptureArchiveDescriptor, asset: CaptureArchiveAsset): Long = when (asset) {
    descriptor.log -> MAX_ARCHIVE_ENTRY_BYTES
    descriptor.mapping -> MAX_MAPPING_BYTES
    descriptor.video -> MAX_VIDEO_BYTES
    else -> MAX_SCREENSHOT_BYTES
}

private fun requireExtractionSpace(cacheDirectory: File, descriptor: CaptureArchiveDescriptor) {
    val required = descriptor.assets().fold(0L) { total, asset ->
        require(asset.sizeBytes <= Long.MAX_VALUE - total) { "Capture assets exceed the supported size" }
        total + asset.sizeBytes
    }
    require(cacheDirectory.usableSpace >= required) {
        "Not enough free space to import capture (need $required bytes, have ${cacheDirectory.usableSpace} bytes)"
    }
}

private fun readSmallFile(file: File): String {
    require(file.length() <= MAX_DESCRIPTOR_BYTES) { "Capture descriptor exceeds the size limit" }
    return file.inputStream().use { readBoundedText(it, MAX_DESCRIPTOR_BYTES) }
}

private fun readBoundedText(input: InputStream, limit: Long): String {
    val bytes = ByteArrayOutputStream()
    copyInterruptibly(LimitedInputStream(input, limit + 1), bytes)
    require(bytes.size().toLong() <= limit) { "Capture descriptor exceeds the size limit" }
    return bytes.toString(Charsets.UTF_8)
}

private class LimitedInputStream(delegate: InputStream, private var remaining: Long) : FilterInputStream(delegate) {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val value = super.read()
        if (value >= 0) remaining--
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val count = super.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count > 0) remaining -= count
        return count
    }
}

private fun forEachCompleteLine(
    input: InputStream,
    maxLineBytes: Int,
    rejectTrailingPartial: Boolean = false,
    block: (String) -> Unit,
) {
    val line = ByteArrayOutputStream()
    while (true) {
        checkNotInterrupted()
        val value = input.read()
        if (value < 0) break
        if (value == '\n'.code) {
            val bytes = line.toByteArray()
            val length = if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
            if (length > 0) block(bytes.copyOf(length).toString(Charsets.UTF_8))
            line.reset()
        } else {
            require(line.size() < maxLineBytes) { "Capture mapping line exceeds the size limit" }
            line.write(value)
        }
    }
    require(!rejectTrailingPartial || line.size() == 0) { "Capture mapping ends with an incomplete line" }
}

private fun copyInterruptibly(input: InputStream, output: OutputStream): Long {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var total = 0L
    while (true) {
        checkNotInterrupted()
        val count = input.read(buffer)
        if (count < 0) return total
        output.write(buffer, 0, count)
        total += count
    }
}

private fun copyExactly(input: InputStream, output: OutputStream, length: Long) {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var remaining = length
    while (remaining > 0) {
        checkNotInterrupted()
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (count < 0) throw CaptureArchiveException("Capture source was truncated during export")
        output.write(buffer, 0, count)
        remaining -= count
    }
}

private fun normalizedEntryName(name: String): String = name.replace('\\', '/')

private fun isSafeRelativePath(raw: String): Boolean {
    if (raw.isBlank() || raw.indexOf('\u0000') >= 0 || raw.startsWith('/') || raw.startsWith('\\')) return false
    if (Regex("^[A-Za-z]:").containsMatchIn(raw)) return false
    val normalized = normalizedEntryName(raw)
    val parts = normalized.split('/')
    return parts.none { it.isBlank() || it == "." || it == ".." }
}

private fun resolveSafe(root: File, relative: String): File {
    require(isSafeRelativePath(relative)) { "Unsafe capture asset path: $relative" }
    val normalizedRoot = root.toPath().toAbsolutePath().normalize()
    val resolved = normalizedRoot.resolve(normalizedEntryName(relative)).normalize()
    require(resolved.startsWith(normalizedRoot)) { "Capture asset escapes its root: $relative" }
    return resolved.toFile()
}

private fun hasSymbolicLink(root: File, target: File): Boolean {
    val rootPath = root.toPath().toAbsolutePath().normalize()
    var current = target.toPath().toAbsolutePath().normalize()
    while (current.startsWith(rootPath) && current != rootPath) {
        if (Files.isSymbolicLink(current)) return true
        current = current.parent ?: break
    }
    return Files.isSymbolicLink(rootPath)
}

private fun descriptorFile(directory: File): File = File(directory, CAPTURE_DESCRIPTOR_NAME)

private fun deleteTree(root: File) {
    if (!root.exists()) return
    root.walkBottomUp().forEach { it.delete() }
}

private fun checkNotInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Capture export cancelled")
}

private fun safeStem(name: String): String = sanitizeFilename(name.substringBeforeLast('.')).ifBlank { "capture" }

val CAPTURE_FILENAME_TOKENS: Set<String> = setOf("device", "serial", "start", "range", "counter", "label")

/** Null means valid; otherwise the returned text is suitable for a settings validation message. */
fun captureFilenameTemplateError(template: String): String? {
    if (template.isBlank()) return "Filename template cannot be blank"
    val unknown = Regex("\\{([^{}]+)}").findAll(template).map { it.groupValues[1] }.filter { it !in CAPTURE_FILENAME_TOKENS }.toList()
    if (unknown.isNotEmpty()) return "Unknown filename token: {${unknown.first()}}"
    if (template.replace(Regex("\\{[^{}]+}"), "x").contains('{') || template.replace(Regex("\\{[^{}]+}"), "x").contains('}')) {
        return "Filename template contains an unmatched brace"
    }
    val literal = template.replace(Regex("\\{[^{}]+}"), "x")
    if (literal.any { it.code < 32 || it in "<>:\"/\\|?*" }) return "Filename template contains a character that is unsafe on some platforms"
    if (!template.lowercase(Locale.ROOT).endsWith(".zip")) return "Filename template must end in .zip"
    return null
}

fun renderCaptureFilename(
    template: String,
    device: CaptureDevice,
    startEpochMs: Long,
    range: CaptureRange,
    counter: Int,
    label: String = "",
): String {
    captureFilenameTemplateError(template)?.let { throw IllegalArgumentException(it) }
    val start = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(startEpochMs))
    val values = mapOf(
        "device" to device.model.ifBlank { device.serial },
        "serial" to device.serial,
        "start" to start,
        "range" to range.name.lowercase(Locale.ROOT).replace('_', '-'),
        "counter" to counter.coerceAtLeast(0).toString(),
        "label" to label,
    )
    var rendered = template
    values.forEach { (token, value) -> rendered = rendered.replace("{$token}", sanitizeFilename(value)) }
    val stem = sanitizeFilename(rendered.removeSuffix(".zip")).take(MAX_CAPTURE_FILENAME_STEM_LENGTH).ifBlank { "capture" }
    return "$stem.zip"
}

private fun sanitizeFilename(value: String): String {
    var safe = value.trim().map { ch -> if (ch.code < 32 || ch in "<>:\"/\\|?*") '_' else ch }.joinToString("")
        .trimEnd(' ', '.')
    if (safe.substringBefore('.').uppercase(Locale.ROOT) in WINDOWS_RESERVED_NAMES) safe = "_$safe"
    return safe
}

private val WINDOWS_RESERVED_NAMES = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL"))
    for (index in 1..9) {
        add("COM$index")
        add("LPT$index")
    }
}
