package com.indagium.capture

import com.github.luben.zstd.ZstdOutputStream
import com.indagium.generated.BuildInfo
import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.VideoSource
import com.indagium.ui.annotationsFromToken
import com.indagium.ui.annotationsToken
import com.indagium.utils.CaptureZstd
import com.indagium.utils.MAX_ARCHIVE_ENTRIES_SCANNED
import com.indagium.utils.MAX_ARCHIVE_ENTRY_BYTES
import com.indagium.utils.openLogTextReader
import com.indagium.utils.parseLogcat
import com.indagium.utils.parseLogcatLines
import com.indagium.utils.readTextShared
import com.indagium.utils.unrollLogTimeline
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
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipMethod
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
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.Deflater

const val CAPTURE_DESCRIPTOR_NAME = "capture.indagium.json"

/** v3's flat layout replaces the old `notes/capture.ann`; the boilerplate line naming both is kept
 *  here (rather than inlined at every call site) since [captureWithIndagiumBlurb] and the allowlist
 *  in [validateEntriesAndFindDescriptor] must always agree on the exact filename. */
const val CAPTURED_WITH_INDAGIUM_NAME = "captured_with_indagium.txt"

// v3 (archive v3: flat layout) bumped this 2 -> 3 for the flattened directory layout (no more
// logs/, video/, mapping/, notes/ subfolders — see this file's own module doc) and the simplified
// descriptor shape (drops the full settings dump, quality/uncertainty, requested-vs-actual video
// starts, and per-asset sizeBytes in favor of one sha256 map). parseDescriptor still reads v1's
// nested logs/video/mapping/notes/capture.ann layout and v2's optional notes/markers keys — see
// its own doc for exactly which reads stay conditional on `version`. Bumping this on every
// backward-incompatible WRITER change (not on every new optional field) is what makes an old build
// fail with an honest "unsupported/newer archive version" instead of a confusing "unreferenced
// file" error.
const val CAPTURE_ARCHIVE_VERSION = 5

private const val MAX_DESCRIPTOR_BYTES = 1024 * 1024L
private const val MAX_MAPPING_BYTES = MAX_ARCHIVE_ENTRY_BYTES
private const val MAX_MAPPING_ROWS = 10_000_000
private const val MAX_TIMELINE_BYTES = 1024L * 1024L * 1024L
private const val MAX_MAPPING_LINE_BYTES = 64 * 1024
private const val MAX_SCREENSHOT_BYTES = 100 * 1024 * 1024L
private const val MAX_SCREENSHOTS_BYTES = 500 * 1024 * 1024L

// A .ann token embeds its own image blocks' bytes inline (see AutosaveCodec.kt's annotationsToken),
// so it can legitimately be large — bounded at the same order as the log itself rather than at
// MAX_MAPPING_BYTES's much smaller scale.
private const val MAX_NOTES_BYTES = MAX_ARCHIVE_ENTRY_BYTES
private const val MAX_VIDEO_BYTES = 20L * 1024L * 1024L * 1024L
private const val COPY_BUFFER_BYTES = 128 * 1024
private const val SPARSE_INDEX_RECORD_OVERHEAD_BYTES = 512L
private const val PREFLIGHT_OVERHEAD_BYTES = 16L * 1024L * 1024L
private const val RANGE_MINUTES = 60_000L
private const val LAST_FIVE_MINUTES = 5L
private const val LAST_TEN_MINUTES = 10L
private const val MAX_SCREENSHOT_NAME_LENGTH = 80
private const val MAX_CAPTURE_FILENAME_STEM_LENGTH = 180
private const val DEFAULT_VIDEO_COVERAGE_WAIT_POLL_MS = 200L
private const val DEFAULT_VIDEO_QUIET_AGE_MS = 10_000L
private const val DEFAULT_VIDEO_STABILITY_SAMPLE_MS = 500L
private const val VIDEO_COVERAGE_WAIT_POLL_MAX_MS = 1_000L
private const val VIDEO_COVERAGE_WAIT_POLL_BACKOFF_MULTIPLIER = 2

/** The real, production wait bound for [CaptureArchiveExporter]'s `videoCoverageWaitMs` — public so
 * the one production call site (TabCaptureController) can opt into it explicitly; see that
 * constructor parameter's doc for why the class itself defaults to disabled (0).
 *
 * Lowered from 8s: recording now writes `session.videoFile` directly via
 * [com.indagium.capture.mirror.EmbeddedDeviceSession]/[StreamingMkvWriter] instead of relying on
 * host `scrcpy --record`'s own Matroska muxer, which buffered clusters in memory badly enough that
 * a snapshot could wait the full 8s and still see 0 bytes of new video. StreamingMkvWriter closes
 * clusters every ~750ms (see its own doc), so a short bound is now enough to catch the ordinary
 * "video packet already in flight" case without making Save feel slow when video genuinely isn't
 * covering the request yet (e.g. recording just started, or the device stream reconnecting).
 */
const val DEFAULT_VIDEO_COVERAGE_WAIT_MS = 2_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L

/** [sizeBytes]/[sha256] are always populated in memory (computed from the staged file at export
 *  time, or from the zip entry + sha256 map at import time) even though v3's own JSON no longer
 *  writes a per-asset size — see [CaptureArchiveDescriptor.toJson]/[parseDescriptor]. */
data class CaptureArchiveAsset(val path: String, val sizeBytes: Long, val sha256: String)

data class CaptureArchiveDevice(
    val serial: String,
    val state: String,
    val model: String,
    val emulator: Boolean,
)

/**
 * v3 (archive v3: flat and simpler): the JSON descriptor's own shape shrank a lot, but this
 * in-memory type stays close to its v1/v2 predecessor — [parseDescriptor] fills every field the
 * same way regardless of which version it read, so nothing downstream needs to branch on
 * [formatVersion]. Dropped entirely relative to v1/v2 (per the archive-v3 plan): the full
 * [CaptureSettings] dump (nothing outside this file ever read `descriptor.settings` back), `quality`/
 * `uncertaintyMs` (same — only ever fed straight into [CaptureTimeline], which is happy to default
 * them), and the coverage object's `videoRequestedStartMs` (display-only, never read back either).
 * [app] is the writer's own version string ("Indagium 1.8.6"); empty for a v1/v2 archive, which
 * never recorded one.
 */
data class CaptureArchiveDescriptor(
    val formatVersion: Int,
    val app: String,
    val sessionId: String,
    val device: CaptureArchiveDevice,
    val sessionStartedEpochMs: Long,
    val exportedEpochMs: Long,
    val range: CaptureRange,
    val manualOffsetMs: Long,
    val interruptions: List<String>,
    val log: CaptureArchiveAsset,
    val logStartMs: Long,
    val logEndMs: Long,
    // Non-null only for a v1/v2 archive's row-by-row mapping file. v3 replaced the whole mapping
    // file with a single sync anchor instead — see [syncAnchor] and
    // com.indagium.capture.estimateCaptureSyncAnchor.
    val mapping: CaptureArchiveAsset?,
    val video: CaptureArchiveAsset?,
    val videoStartMs: Long?,
    val videoEndMs: Long?,
    val screenshots: List<CaptureArchiveAsset>,
    // Both optional on read (parseDescriptor), so a v1 archive — which has neither key at all —
    // still opens: null/empty is exactly what "this session had no Mark issue notes, or
    // markerNotesInSnapshot was off" already means for a v2/v3 archive built the same way.
    val notes: CaptureArchiveAsset?,
    val markers: List<CaptureArchiveMarker>,
    // v3 only, appended last per this class's own convention (see CaptureSettings's comment on the
    // same pattern): the single log-row <-> video-position pin this export's writer estimated in
    // place of the old per-row mapping file — see estimateCaptureSyncAnchor. Null for a v1/v2
    // archive (which instead has a non-null [mapping]), and also null for a v3 archive exported/
    // finalized with no video, or with no row that had both a parseable log timestamp and a known
    // video position.
    val syncAnchor: CaptureSyncAnchor? = null,
    /** v4 bounded JSONL row timing asset; row ordinals restart at one for every archive cut. */
    val timeline: CaptureArchiveAsset? = null,
    /** v4 device-offline intervals, relative to the export's first elapsed millisecond. */
    val availabilityIntervals: List<CaptureAvailabilityInterval> = emptyList(),
    /** Session elapsed value represented by timeline row 0; every child archive cut rebases this. */
    val timelineBaseElapsedMs: Long = 0L,
    /** Timeline duration from [timelineBaseElapsedMs], including trailing periods without log rows. */
    val timelineDurationMs: Long = 0L,
    /** Whether unavailable cards are already part of the exported media track. */
    val availabilityFramesRendered: Boolean = false,
    /** Frozen record-time preference; null only for legacy archives. */
    val showUnavailableFrames: Boolean? = null,
    /** v5 sparse raw-log block index; per-row timelines are not written by this format. */
    val blocks: CaptureArchiveAsset? = null,
    /** v5 checksummed anchor/segment metadata. */
    val sparseSync: CaptureArchiveAsset? = null,
)

/**
 * One marker's EXPORT-LOCAL ordinal window — the numbering `forEachSelectedRecord` assigns this
 * export's own rows (one increment per row that carries a `rowOrdinal`) and the reopened tab's own
 * LogParser reproduces 1:1 (see CaptureMarkerCodec's doc on why
 * "ordinal" always means that), already clipped to whatever this export actually kept. A marker
 * whose window shares no row with the export is simply absent from [CaptureArchiveDescriptor.markers]
 * — see [clipMarkersForExport] (export side) and [reanchorImportedCaptureNotes] (import side, which
 * treats the absence as "drop this marker's jump, keep its note and screenshot").
 */
data class CaptureArchiveMarker(
    val noteBlockId: String,
    val firstOrdinal: Int,
    val lastOrdinal: Int,
)

data class ImportedCapture(
    val logFile: File,
    val videoFile: File?,
    // Non-null only for a v1/v2 archive's row-by-row mapping. v3 exposes [syncAnchor] instead — see
    // CaptureArchiveDescriptor.mapping/syncAnchor for which one a given formatVersion populates.
    val timeline: CaptureTimeline?,
    val descriptor: CaptureArchiveDescriptor,
    /** The archive, descriptor, or extracted directory supplied by the caller. */
    val source: File,
    // Appended last (Phase 4). The RAW notes decoded straight from the notes asset (`notes.ann` in
    // v3, `notes/capture.ann` in v1/v2) — still numbered against the ORIGINAL capture session, not
    // this archive's own freshly parsed log. Callers that
    // actually open the log (AppState.openCaptureFile has the only production `logData`) must run
    // this through [reanchorImportedCaptureNotes] before showing it; capture does not do that itself
    // here because it has no parsed log to re-anchor against, only the raw log FILE. Null for a v1
    // archive, or when the exporting session had markerNotesInSnapshot off.
    val notes: Annotations? = null,
    // v3 only, appended last per this class's own convention above. AppState turns this straight
    // into a plain VideoAnchor(videoMs, logData[row-1].id) — see AppState.openCaptureFile/
    // restoreCaptureLink/attachFinalizedCapture — rather than rehydrating a CaptureTimeline. Null
    // for v1/v2 (which populate [timeline] instead) and for a v3 archive with no usable estimate.
    val syncAnchor: CaptureSyncAnchor? = null,
    // Appended last. Non-null only when the load-time guard (recomputeStaleInPlaceSyncAnchor)
    // replaced a descriptor anchor that pointed at a pre-recording row: this is that original,
    // wrong anchor, so a caller that persisted it elsewhere (a restored tab's autosaved VideoAnchor)
    // can tell "untouched stale anchor" from a deliberate manual re-link.
    val staleSyncAnchor: CaptureSyncAnchor? = null,
    // Appended last. The descriptor's screenshot assets resolved inside the opened archive root, in
    // descriptor order; the imported snapshot session re-exports them from here.
    val screenshotFiles: List<File> = emptyList(),
    /** Parsed v5 segment manifest; null for all older archive versions. */
    val sparseSyncMetadata: CaptureSyncMetadata? = null,
    /**
     * The directory every descriptor asset path is relative to: the folder that holds `capture.indagium.json`
     * (an in-place capture folder, where [logFile] lives in `logs/`, not here) or the extraction directory of
     * a ZIP. Appended last; null only for a value built outside [CaptureArchiveReader].
     */
    val root: File? = null,
    /**
     * Keeps a ZIP's extraction directory leased and in use for as long as a consumer reads its files; null for an
     * in-place folder or a value built outside [CaptureArchiveReader]. Appended last. Whoever opened the capture owns
     * it: a consumer that outlives the opener (a tab's imported snapshot) takes its own [CaptureExtractionUse.retain].
     */
    val extractionUse: CaptureExtractionUse? = null,
) : AutoCloseable {
    /** Releases [extractionUse]; safe to call more than once. */
    override fun close() {
        extractionUse?.close()
    }
}

class CaptureArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause)

// zstd entries (ZIP method 93) are decoded by commons-compress through zstd-jni, so a missing or
// unloadable native library surfaces as a LinkageError (UnsatisfiedLinkError / NoClassDefFoundError)
// while opening OR reading an entry. AppState's import paths catch only Exception, so without this
// mapping a Compact archive would take the thread down instead of showing an actionable message.
private fun zstdLoadFailure(cause: LinkageError) = CaptureArchiveException(
    "This capture archive uses zstd compression, which could not be loaded on this system",
    cause,
)

/**
 * Serializes exports per exporter instance. Interrupting the export thread cancels FFmpeg/copying
 * and leaves no destination or temporary archive behind.
 */
class CaptureArchiveExporter(
    private val videoExporter: CaptureVideoExporter = FfmpegCaptureVideoExporter(),
    // Bounded wait, at real-export time only, for the growing MKV's muxer/AVIO buffering lag to
    // catch up to the requested video end (see waitForVideoCoverage below). Deliberately OFF
    // (0 = disabled, see waitForVideoCoverage's early return) by default: every capture fixture in
    // this codebase's tests defaults CaptureSession.status to RECORDING (there was no prior reason
    // for a fixture to care), so an on-by-default wait would silently make dozens of existing
    // export tests poll for up to DEFAULT_VIDEO_COVERAGE_WAIT_MS against fakes that never satisfy
    // it (e.g. one that intentionally throws to test failure handling). TabCaptureController, the
    // one production call site, opts in explicitly with DEFAULT_VIDEO_COVERAGE_WAIT_MS.
    private val videoCoverageWaitMs: Long = 0L,
    private val videoCoverageWaitPollMs: Long = DEFAULT_VIDEO_COVERAGE_WAIT_POLL_MS,
    // How long a video file of an INTERRUPTED session must have been untouched, and how far apart
    // its two (length, mtime) samples are, before [export] trusts it as final (see [isVideoFinal]).
    // Tests shrink these; the clock is a seam for the same reason.
    private val videoQuietAgeMs: Long = DEFAULT_VIDEO_QUIET_AGE_MS,
    private val videoStabilitySampleMs: Long = DEFAULT_VIDEO_STABILITY_SAMPLE_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val compactVideoTranscoder: CaptureCompactVideoTranscoder = FfmpegAv1CompactVideoTranscoder(),
    // Test seam: whether Compact may store the log with zstd (falls back to DEFLATE when the native library can't load).
    private val zstdAvailable: () -> Boolean = { CaptureZstd.isAvailable },
) {
    /**
     * Cheap coverage-probe result cache keyed by source path+length so the popover's debounced
     * preview polling doesn't even pay for a fresh scan when the growing MKV hasn't changed since
     * the last tick. Read and written only from [probeCoverageEndMs], which is reached exclusively
     * through [previewVideoCoverage] (called by [preview]) and [waitForVideoCoverage] (called by
     * [export]) — both [preview] and [export] are themselves `@Synchronized`, i.e. both synchronize
     * on this same instance's monitor before either can touch this field, so a plain `private var`
     * is already safe here: there is no path to this field that isn't already serialized by one of
     * those two entry points, and adding a second, separate lock would just be redundant.
     */
    private var coverageProbeCache: Pair<CoverageProbeKey, Long?>? = null

    /** Serializes [finalizeSessionInPlace] with itself only; see its doc for why it must not share the export monitor. */
    private val finalizeLock = Any()

    private data class CoverageProbeKey(
        val sourcePath: String,
        val sourceLength: Long,
        val requestedStartMs: Long,
        val requestedEndMs: Long,
    )

    /** Checks that [session] is stopped with regular, size-bounded files, and returns its non-empty video file. */
    private fun requireFinalizableSession(session: CaptureSession): File? {
        val unfinalizable = when {
            session.status == CaptureStatus.RECORDING -> "Cannot finalize an active capture session"
            !session.directory.isDirectory -> "Capture session directory is missing: ${session.directory}"
            else -> null
        }
        if (unfinalizable != null) throw CaptureArchiveException(unfinalizable)
        requireRegularCaptureFile(session.logFile, "Capture log")
        if (session.storageVersion == CaptureStorageVersion.BLOCKS_V2) {
            requireRegularCaptureFile(session.blockIndexFile, "Capture block index")
            requireRegularCaptureFile(session.syncFile, "Capture sync metadata")
        } else {
            requireRegularCaptureFile(session.indexFile, "Capture index")
        }
        require(session.logFile.length() <= MAX_ARCHIVE_ENTRY_BYTES) {
            "Capture log exceeds the size limit"
        }
        val mappingLength = if (session.storageVersion == CaptureStorageVersion.BLOCKS_V2) {
            session.blockIndexFile.length()
        } else {
            session.indexFile.length()
        }
        require(mappingLength <= MAX_MAPPING_BYTES) {
            "Capture mapping exceeds the size limit"
        }

        val videoFile = session.videoFile.takeIf { it.isFile && it.length() > 0L }
        if (session.settings.recordVideo && session.videoFile.exists() && !session.videoFile.isFile) {
            throw CaptureArchiveException("Capture video is not a regular file: ${session.videoFile}")
        }
        if (videoFile != null) {
            require(videoFile.length() <= MAX_VIDEO_BYTES) {
                "Capture video exceeds the size limit"
            }
            require(session.videoStartElapsedMs != null) {
                "Capture video has no start-time reference"
            }
        }
        return videoFile
    }

    /**
     * Publishes the durable representation of a stopped capture beside its recorder output.
     *
     * This deliberately does not create a ZIP, remux the MKV, or copy any raw asset. The
     * recorder's files are already the canonical extracted capture; finalization only derives
     * the portable log-to-video mapping and descriptor, then re-opens that directory through the
     * normal reader so callers receive exactly the same validation as imported archives.
     *
     * Serialized on [finalizeLock], NOT on this exporter's monitor ([export]/[preview] hold that one for the
     * whole of a Compact encode, which can take minutes, and Stop must not wait for it). That is safe because
     * the two never write the same file: an export only reads the session's frozen log/index prefix, sync
     * metadata, video and screenshots and writes into its own temp/work directories next to the destination
     * (plus [coverageProbeCache], which finalization never touches), while finalization only reads those same
     * files and writes the session's own descriptor (`capture.indagium.json`, and for a legacy session
     * `timeline/rows.jsonl`, which no export reads) from a `.finalize-` staging directory of its own.
     */
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    fun finalizeSessionInPlace(session: CaptureSession): ImportedCapture = synchronized(finalizeLock) {
        finalizeSessionInPlaceLocked(session)
    }

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    private fun finalizeSessionInPlaceLocked(session: CaptureSession): ImportedCapture {
        val videoFile = requireFinalizableSession(session)

        if (session.storageVersion == CaptureStorageVersion.BLOCKS_V2) {
            return finalizeSparseSessionInPlace(session, videoFile)
        }

        val input = validateFinalizationInput(session)
        val staging = Files.createTempDirectory(session.directory.toPath(), ".finalize-").toFile()
        val stagingSelection = File(staging, ".selected-index.jsonl")
        val stagingDescriptor = File(staging, CAPTURE_DESCRIPTOR_NAME)
        val descriptorFile = File(session.directory, CAPTURE_DESCRIPTOR_NAME)
        try {
            val selection = freezeSelection(
                indexFile = session.indexFile,
                frozenBytes = session.indexFile.length(),
                frozenLogBytes = session.logFile.length(),
                selectedStartMs = Long.MIN_VALUE,
                selectedEndMs = Long.MAX_VALUE,
                excludeStart = false,
                destination = stagingSelection,
            )
            require(selection.recordCount >= input.indexRecordCount) {
                "Capture index changed while finalizing"
            }
            // Archive v3: no row-by-row mapping file is published any more (see this file's module
            // doc and estimateCaptureSyncAnchor) — a stopped/finalized-in-place session references
            // the raw, unremuxed MKV, so there is no clip to bound the anchor's video coverage
            // (includeRawVideo=true, clip=null, same as the old writeMapping call this replaces).
            val syncAnchor = computeCaptureSyncAnchor(
                logFile = session.logFile,
                selectionIndex = stagingSelection,
                session = session,
                clip = null,
                includeRawVideo = videoFile != null,
            )
            val clippedAvailability = clipAvailabilityIntervals(
                session.availabilityIntervals,
                baseElapsedMs = 0L,
                endElapsedMs = session.elapsedMs.coerceAtLeast(0L),
            )
            val stagedTimeline = File(staging, "timeline/rows.jsonl").apply { parentFile?.mkdirs() }
            writeTimelineRows(
                session.indexFile,
                session.indexFile.length(),
                stagedTimeline,
                baseElapsedMs = 0L,
                durationMs = session.elapsedMs.coerceAtLeast(0L),
                availabilityIntervals = clippedAvailability,
            )
            val timelineAsset = asset("timeline/rows.jsonl", stagedTimeline)

            val logAsset = asset("logs/logcat.log", session.logFile)
            val videoAsset = videoFile?.let { asset("video/screen.mkv", it) }
            val videoStart = session.videoStartElapsedMs
            val videoActualStart = if (videoAsset != null && videoStart != null) {
                videoStart - session.manualOffsetMs
            } else {
                null
            }
            val descriptor = CaptureArchiveDescriptor(
                // Legacy retained sessions are never rewritten into the new layout just because
                // this build can emit v5. Keep their established v4 files and row timeline intact.
                formatVersion = 4,
                app = "Indagium ${BuildInfo.APP_VERSION}",
                sessionId = session.id,
                device = CaptureArchiveDevice(
                    session.device.serial,
                    session.device.state,
                    session.device.model,
                    session.device.emulator,
                ),
                sessionStartedEpochMs = session.startedEpochMs,
                exportedEpochMs = System.currentTimeMillis(),
                range = CaptureRange.ALL,
                manualOffsetMs = session.manualOffsetMs,
                interruptions = session.interruptions,
                log = logAsset,
                logStartMs = input.firstElapsedMs ?: 0L,
                logEndMs = input.lastElapsedMs ?: session.elapsedMs.coerceAtLeast(0L),
                mapping = null,
                video = videoAsset,
                videoStartMs = videoActualStart,
                videoEndMs = if (videoAsset != null) input.lastVideoElapsedMs else null,
                screenshots = emptyList(),
                // finalizeSessionInPlace publishes the recorder's own directory in place, never a
                // snapshot .zip — there's no "notes as of this snapshot" concept here, only the live
                // tab's own (unexported) Notes, which stay exactly where they already are.
                notes = null,
                markers = emptyList(),
                syncAnchor = syncAnchor,
                timeline = timelineAsset,
                availabilityIntervals = clippedAvailability,
                timelineBaseElapsedMs = 0L,
                timelineDurationMs = session.elapsedMs.coerceAtLeast(0L),
                availabilityFramesRendered = false,
                showUnavailableFrames = session.settings.showUnavailableFrames,
            )
            publishReplacing(stagedTimeline, File(session.directory, "timeline/rows.jsonl"))
            stagingDescriptor.writeText(descriptor.toJson(), Charsets.UTF_8)

            publishReplacing(stagingDescriptor, descriptorFile)

            return CaptureArchiveReader.open(descriptorFile, session.directory)
        } catch (failure: CaptureArchiveException) {
            throw failure
        } catch (failure: InterruptedException) {
            throw failure
        } catch (failure: Exception) {
            throw CaptureArchiveException(
                "Capture finalization failed: ${failure.message ?: failure::class.simpleName}",
                failure,
            )
        } finally {
            deleteTree(staging)
        }
    }

    /** Publishes the sparse recorder files directly; no per-row index or timeline is staged. */
    @Suppress("TooGenericExceptionCaught") // Filesystem and archive-reader failures become one contextual finalization error.
    private fun finalizeSparseSessionInPlace(session: CaptureSession, videoFile: File?): ImportedCapture {
        val descriptorFile = File(session.directory, CAPTURE_DESCRIPTOR_NAME)
        try {
            val input = validateFinalizationInput(session)
            val metadata = session.syncMetadataOverride ?: CaptureSyncMetadata.parse(readTextShared(session.syncFile))
            require(metadata.logBytes == session.logFile.length() && metadata.rowCount == input.rowCount) {
                "Capture sync metadata does not match the frozen recorder files"
            }
            val blocks = CaptureBlockIndex.read(
                session.blockIndexFile,
                session.logFile,
                maxIndexBytes = session.frozenBlockIndexBytes ?: session.blockIndexFile.length(),
                maxLogBytes = session.frozenLogBytes ?: session.logFile.length(),
            )
            require(blocks.indexedLogBytes == metadata.logBytes && blocks.indexedRows == metadata.rowCount) {
                "Capture block index does not match the sync metadata"
            }
            val clippedAvailability = clipAvailabilityIntervals(
                session.availabilityIntervals,
                baseElapsedMs = 0L,
                endElapsedMs = session.elapsedMs.coerceAtLeast(0L),
            )
            val logAsset = asset("logs/logcat.log", session.logFile)
            val blocksAsset = asset("mapping/blocks.jsonl", session.blockIndexFile)
            val syncAsset = asset("timeline/sync.json", session.syncFile)
            val videoAsset = videoFile?.let { asset("video/screen.mkv", it) }
            val videoActualStart = if (videoAsset != null) {
                session.videoStartElapsedMs?.minus(session.manualOffsetMs)
            } else {
                null
            }
            val descriptor = CaptureArchiveDescriptor(
                formatVersion = CAPTURE_ARCHIVE_VERSION,
                app = "Indagium ${BuildInfo.APP_VERSION}",
                sessionId = session.id,
                device = CaptureArchiveDevice(session.device.serial, session.device.state, session.device.model, session.device.emulator),
                sessionStartedEpochMs = session.startedEpochMs,
                exportedEpochMs = System.currentTimeMillis(),
                range = CaptureRange.ALL,
                manualOffsetMs = session.manualOffsetMs,
                interruptions = session.interruptions,
                log = logAsset,
                logStartMs = input.firstElapsedMs ?: 0L,
                logEndMs = input.lastElapsedMs ?: session.elapsedMs.coerceAtLeast(0L),
                mapping = null,
                video = videoAsset,
                videoStartMs = videoActualStart,
                videoEndMs = if (videoAsset != null) input.lastVideoElapsedMs else null,
                screenshots = emptyList(),
                notes = null,
                markers = emptyList(),
                timeline = null,
                availabilityIntervals = clippedAvailability,
                timelineBaseElapsedMs = metadata.timelineBaseElapsedMs,
                timelineDurationMs = metadata.timelineDurationMs,
                availabilityFramesRendered = false,
                showUnavailableFrames = session.settings.showUnavailableFrames,
                blocks = blocksAsset,
                sparseSync = syncAsset,
            )
            descriptorFile.writeText(descriptor.toJson(), Charsets.UTF_8)
            return CaptureArchiveReader.open(descriptorFile, session.directory)
        } catch (failure: CaptureArchiveException) {
            throw failure
        } catch (failure: Exception) {
            throw CaptureArchiveException(
                "Capture finalization failed: ${failure.message ?: failure::class.simpleName}",
                failure,
            )
        }
    }

    /**
     * Computes the exact frozen range and current video coverage without publishing or changing
     * the session. The temporary index spool is deleted before returning; callers can therefore
     * use this as a preflight while the recorder keeps writing.
     */
    @Synchronized
    fun preview(session: CaptureSession, request: CaptureExportRequest): CaptureExportPreview {
        return previewSparseV5(session, request)
    }

    /**
     * Cheap by construction, because the popover asks again every second: block checksums are not verified here (export
     * still verifies them), blocks wholly inside the window and inside one reliable clock segment contribute their
     * recorded time envelope without being read, and the answer is memoized process-wide by everything it depends on
     * ([CaptureExportPreviewCache]) so a stopped session is only ever scanned once.
     */
    @Suppress("CyclomaticComplexMethod") // Coordinates the frozen log prefix with an independent video-coverage preview.
    private fun previewSparseV5(originalSession: CaptureSession, request: CaptureExportRequest): CaptureExportPreview {
        checkNotInterrupted()
        require(request.customMinutes > 0) { "Custom capture range must be positive" }
        val key = CaptureExportPreviewKey.of(originalSession, request, videoExporter)
        val destinationExists = request.destination.absoluteFile.exists()
        CaptureExportPreviewCache.get(key)?.let { return it.copy(destinationExists = destinationExists) }
        val lease = if (originalSession.storageVersion == CaptureStorageVersion.BLOCKS_V2) null
        else LegacySparseAdapterCache.acquire(originalSession)
        try {
            CapturePreviewDiagnostics.computations.incrementAndGet()
            val session = lease?.adapter?.session ?: originalSession
            val checkpoint = requireSnapshotCheckpoint(session, request)
            require(session.logFile.isFile && session.blockIndexFile.isFile) { "Capture log or block index is missing" }
            val frozenLogBytes = session.frozenLogBytes ?: session.logFile.length()
            val frozenBlockBytes = session.frozenBlockIndexBytes ?: session.blockIndexFile.length()
            require(frozenLogBytes in 0..session.logFile.length()) { "Capture log is shorter than its frozen boundary" }
            val metadata = mergeManualAnchors(
                session.syncMetadataOverride ?: CaptureSyncMetadata.parse(readTextShared(session.syncFile)),
                session.manualSegmentAnchors + request.captureSegmentAnchors,
            )
            require(metadata.logBytes == frozenLogBytes) { "Capture sync metadata does not match the frozen log boundary" }
            val index = CaptureBlockIndex.read(
                session.blockIndexFile,
                session.logFile,
                maxIndexBytes = frozenBlockBytes,
                maxLogBytes = frozenLogBytes,
                verifyByteRange = 0L..-1L,
            )
            require(index.indexedRows == metadata.rowCount && index.indexedLogBytes == metadata.logBytes) {
                "Capture block index does not match the frozen sync metadata"
            }
            val timing = sparseTimelineForSession(session, metadata)
            val selection = if (request.range == CaptureRange.SELECTION) {
                val first = requireNotNull(request.selectedFirstRowOrdinal) { "Selection export requires a first row" }
                val last = requireNotNull(request.selectedLastRowOrdinal) { "Selection export requires a last row" }
                require(first > 0 && first <= last && last <= metadata.rowCount) {
                    "Selection export row bounds must be positive, ordered, and inside the frozen capture"
                }
                findSparseSelectionBounds(session, metadata, timing, index, frozenBlockBytes, frozenLogBytes, first, last, verifyBlocks = false)
            } else {
                null
            }
            val start = selection?.firstElapsedMs ?: requestedRangeStartMs(request, checkpoint)
            val end = selection?.lastElapsedMs ?: requestedRangeEndMs(request)
            val cursorByte = session.logCheckpointByteOffset.takeIf {
                request.range == CaptureRange.SINCE_SAVE && it >= 0L
            }
            require(cursorByte == null || cursorByte <= frozenLogBytes) { "Saved byte cursor is beyond the frozen log boundary" }
            require(cursorByte == null || session.logCheckpointRowOrdinal <= metadata.rowCount) {
                "Saved row cursor is beyond the frozen capture"
            }
            val logBounds = selection ?: findSparseRangeBounds(
                SparseScanContext(session, metadata, timing, index, frozenBlockBytes, frozenLogBytes, verifyBlocks = false),
                SparseRangeQuery(
                    start, end, request.range == CaptureRange.SINCE_SAVE && checkpoint >= 0L,
                    cursorByte, session.logCheckpointRowOrdinal.takeIf { cursorByte != null },
                ),
                previewEnvelope = CapturePreviewDiagnostics.envelopeEnabled,
            )
            val videoStart = selection?.firstElapsedMs ?: requestedRangeStartMs(
                request,
                if (request.range == CaptureRange.SINCE_SAVE) session.effectiveVideoCheckpointMs else checkpoint,
            )
            val videoEnd = selection?.lastElapsedMs ?: end
            val includeVideo = session.settings.recordVideo && request.includeVideo
            val availabilityStart = videoStart.takeUnless { it == Long.MIN_VALUE } ?: 0L
            val renderAvailability = includeVideo && session.settings.showUnavailableFrames &&
                videoExporter is CaptureAvailabilityVideoExporter &&
                hasAvailabilityMetadataInRange(session, availabilityStart, videoEnd)
            val coverage = if (!includeVideo) {
                null
            } else if (renderAvailability) {
                PreviewVideoCoverage(videoEnd, 0L)
            } else {
                previewVideoCoverage(session, videoStart, videoEnd)
            }
            val preview = CaptureExportPreview(
                logStartMs = logBounds?.firstElapsedMs,
                logEndMs = logBounds?.lastElapsedMs,
                videoCoveredEndMs = coverage?.coveredEndMs,
                videoShortfallMs = coverage?.shortfallMs,
                selectedFirstRowOrdinal = request.selectedFirstRowOrdinal,
                selectedLastRowOrdinal = request.selectedLastRowOrdinal,
                destinationExists = destinationExists,
                includeVideo = includeVideo,
            )
            CaptureExportPreviewCache.put(key, preview)
            return preview
        } finally {
            lease?.close()
        }
    }

    /**
     * Uses the same growing-file snapshot and packet-window implementation as [export], but stages
     * its result in an unreferenced temporary file. The session and destination are untouched, so
     * this can safely run repeatedly while the recorder and its tailer remain active.
     */
    private fun previewVideoCoverage(
        session: CaptureSession,
        videoRangeStartMs: Long,
        selectedEndMs: Long,
        sourceStartOverrideMs: Long? = null,
        sourceEndOverrideMs: Long? = null,
    ): PreviewVideoCoverage? {
        val requestedElapsedStart = videoRangeStartMs.takeUnless { it == Long.MIN_VALUE } ?: 0L
        val expectedShortfall = (selectedEndMs - requestedElapsedStart).coerceAtLeast(0L)
        val videoStartElapsed = session.videoStartElapsedMs ?: return PreviewVideoCoverage(
            coveredEndMs = null,
            shortfallMs = expectedShortfall,
        )
        val videoAvailableStart = maxOf(videoRangeStartMs, videoStartElapsed - session.manualOffsetMs)
        val effectiveShortfall = (selectedEndMs - videoAvailableStart).coerceAtLeast(0L)
        val source = session.videoFile
        if (!source.isFile || source.length() <= 0L) {
            return PreviewVideoCoverage(coveredEndMs = null, shortfallMs = effectiveShortfall)
        }
        val requestedSourceStart = sourceStartOverrideMs?.coerceAtLeast(0L) ?: if (videoRangeStartMs == Long.MIN_VALUE) {
            0L
        } else {
            (videoRangeStartMs - videoStartElapsed + session.manualOffsetMs).coerceAtLeast(0L)
        }
        val requestedSourceEnd = sourceEndOverrideMs?.coerceAtLeast(requestedSourceStart)
            ?: (selectedEndMs - videoStartElapsed + session.manualOffsetMs).coerceAtLeast(requestedSourceStart)
        if (requestedSourceEnd <= requestedSourceStart) {
            return PreviewVideoCoverage(coveredEndMs = null, shortfallMs = effectiveShortfall)
        }
        checkNotInterrupted()
        val coveredEndSourceMs = probeCoverageEndMs(source, requestedSourceStart, requestedSourceEnd)
        checkNotInterrupted()
        return if (coveredEndSourceMs == null) {
            // A growing source may not contain a complete packet/window yet. Preflight reports that
            // gap and leaves the real export to surface the actionable exporter error on Save.
            PreviewVideoCoverage(coveredEndMs = null, shortfallMs = effectiveShortfall)
        } else {
            val coveredEndMs = coveredEndSourceMs + videoStartElapsed - session.manualOffsetMs
            PreviewVideoCoverage(
                coveredEndMs = coveredEndMs,
                shortfallMs = (selectedEndMs - coveredEndMs).coerceAtLeast(0L),
            )
        }
    }

    /**
     * Reports the covered end (source video PTS, ms) of [requestedStartMs]..[requestedEndMs] in the
     * live [source], using [CaptureVideoCoverageProbe] when [videoExporter] implements it (the real
     * [FfmpegCaptureVideoExporter] always does) and caching by (path, length, request) so a
     * debounced poll against an unchanged file costs nothing. Falls back to the heavier
     * export-and-discard path only for a [CaptureVideoExporter] that doesn't also implement the
     * cheap probe (e.g. a minimal test fake) — no production code path takes that branch.
     */
    private fun probeCoverageEndMs(source: File, requestedStartMs: Long, requestedEndMs: Long): Long? {
        val key = CoverageProbeKey(source.absolutePath, source.length(), requestedStartMs, requestedEndMs)
        coverageProbeCache?.let { (cachedKey, cachedResult) -> if (cachedKey == key) return cachedResult }
        val probe = videoExporter as? CaptureVideoCoverageProbe
        val result = if (probe != null) {
            runCatching { probe.coverageEndMs(source, requestedStartMs, requestedEndMs) }.getOrNull()
        } else {
            val temporaryDestination = Files.createTempFile("capture-preview-video-", ".mkv").toFile()
            try {
                runCatching { videoExporter.export(source, temporaryDestination, requestedStartMs, requestedEndMs).coveredEndMs }.getOrNull()
            } finally {
                temporaryDestination.delete()
            }
        }
        coverageProbeCache = key to result
        return result
    }

    private data class PreviewVideoCoverage(val coveredEndMs: Long?, val shortfallMs: Long)

    /**
     * Polls, bounded by [videoCoverageWaitMs], for a live recording's growing MKV to cover up to
     * [requestedEndMs] before [export] snapshots its prefix (see the call site's comment for why).
     * Never throws for "still not covered" — a bound that expires just means [export] proceeds and
     * reports whatever coverage genuinely exists, exactly as it did before this wait existed;
     * [checkNotInterrupted] is what makes the wait itself cancellable, same as every other step.
     */
    private fun waitForVideoCoverage(
        source: File,
        requestedStartMs: Long,
        requestedEndMs: Long,
        onWaitingForVideo: (() -> Unit)?,
    ) {
        if (videoCoverageWaitMs <= 0L) return
        val deadlineNanos = System.nanoTime() + videoCoverageWaitMs * NANOS_PER_MILLISECOND
        var notified = false
        // Backs off (doubling, capped) rather than polling at a flat interval for the whole wait:
        // each poll is cheap now (probeCoverageEndMs scans the live file directly — see
        // FfmpegCaptureVideoExporter.coverageEndMs — and the length-keyed cache skips the scan
        // entirely when the file hasn't grown), but a session stalled for the full 8s bound would
        // otherwise still pay for up to 40 of them.
        var pollMs = videoCoverageWaitPollMs
        while (true) {
            checkNotInterrupted()
            val coveredEndMs = probeCoverageEndMs(source, requestedStartMs, requestedEndMs)
            if (coveredEndMs != null && coveredEndMs >= requestedEndMs) return
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return
            if (!notified) {
                notified = true
                runCatching { onWaitingForVideo?.invoke() }
            }
            val sleepMs = minOf(pollMs, remainingNanos / NANOS_PER_MILLISECOND).coerceAtLeast(1L)
            try {
                Thread.sleep(sleepMs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            pollMs = (pollMs * VIDEO_COVERAGE_WAIT_POLL_BACKOFF_MULTIPLIER).coerceAtMost(VIDEO_COVERAGE_WAIT_POLL_MAX_MS)
        }
    }

    /**
     * Whether [session]'s video file may be read directly ([CaptureVideoExporter.exportFinal]) instead
     * of through a prefix snapshot. [CaptureSession.status] alone is not proof: it can be read from
     * disk, and `CaptureRecorder.recoverSessions()` (run by any other recorder's start, or another app
     * instance where single-instance is skipped) marks a still-recording session INTERRUPTED until its
     * owner persists again, while its MKV keeps growing. So:
     * - RECORDING is never final.
     * - A session this process is still recording ([LiveCaptureSessions]) is never final.
     * - STOPPED is final: only the owning recorder writes it, after it closed the MKV.
     * - INTERRUPTED is final only when the file is demonstrably quiet: untouched for
     *   [videoQuietAgeMs] and unchanged (length and mtime) across two samples [videoStabilitySampleMs]
     *   apart. Anything else falls back to the always-safe snapshot export.
     */
    private fun isVideoFinal(session: CaptureSession): Boolean = when {
        session.status == CaptureStatus.RECORDING -> false
        LiveCaptureSessions.isLive(session.directory) -> false
        session.status == CaptureStatus.STOPPED -> true
        else -> isVideoQuiet(session.videoFile)
    }

    private fun isVideoQuiet(file: File): Boolean {
        val first = FileStamp.of(file)
        if (first.lastModifiedMs <= 0L || clockMs() - first.lastModifiedMs < videoQuietAgeMs) return false
        try {
            Thread.sleep(videoStabilitySampleMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return FileStamp.of(file) == first
    }

    private data class FileStamp(val length: Long, val lastModifiedMs: Long) {
        companion object {
            fun of(file: File) = FileStamp(file.length(), file.lastModified())
        }
    }

    private fun isNoUsableVideoInterval(failure: Exception): Boolean {
        if (failure is IllegalArgumentException) return true
        val message = failure.message?.lowercase() ?: return false
        return "no readable video" in message ||
            "no readable keyframe" in message ||
            "no complete video" in message ||
            "no readable bytes" in message
    }

    /**
     * Picks the video that ships in a Compact archive. Errors from [CaptureCompactVideoTranscoder.transcode]
     * and cancellation still fail the export; only an unavailable encoder or a result that is not
     * smaller keeps the already-staged original instead.
     */
    private fun chooseCompactVideo(
        stagedVideo: File?,
        work: File,
        onStatus: ((String) -> Unit)?,
        liveSource: Boolean = false,
    ): CompactVideoChoice {
        if (stagedVideo == null) return CompactVideoChoice(null, null, CompactVideoOutcome.NO_VIDEO)
        val compactStaging = File(work, ".compact-screen.mkv")
        // Compact is offered from the live snapshot popover: while the capture is still recording the encode must
        // not take the cores the recording itself needs.
        val compactVideoTranscoder = if (liveSource) this.compactVideoTranscoder.forLiveCapture() else this.compactVideoTranscoder
        val passthrough = compactVideoTranscoder.isAlreadyAv1(stagedVideo)
        if (passthrough) {
            runCatching { onStatus?.invoke("Copying existing AV1 video…") }
            compactVideoTranscoder.copyAlreadyAv1(stagedVideo, compactStaging)
        } else {
            try {
                compactVideoTranscoder.ensureAvailable()
            } catch (_: IOException) {
                return CompactVideoChoice(stagedVideo.name, stagedVideo, CompactVideoOutcome.KEPT_ORIGINAL_ENCODER_UNAVAILABLE)
            }
            runCatching { onStatus?.invoke(COMPACT_ENCODING_STATUS) }
            val reporter = CompactEncodingStatusReporter(onStatus)
            compactVideoTranscoder.transcode(stagedVideo, compactStaging, reporter::onProgress)
        }
        require(compactStaging.isFile && compactStaging.length() > 0L) { "Compact AV1 encoder produced no video" }
        if (!passthrough && compactStaging.length() >= stagedVideo.length()) {
            compactStaging.delete()
            return CompactVideoChoice(stagedVideo.name, stagedVideo, CompactVideoOutcome.KEPT_ORIGINAL_NOT_SMALLER)
        }
        val compactVideo = File(work, COMPACT_VIDEO_ENTRY_NAME)
        if (stagedVideo.name != COMPACT_VIDEO_ENTRY_NAME) stagedVideo.delete()
        Files.move(compactStaging.toPath(), compactVideo.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val outcome = if (passthrough) CompactVideoOutcome.PASSTHROUGH_AV1 else CompactVideoOutcome.ENCODED_AV1
        return CompactVideoChoice(COMPACT_VIDEO_ENTRY_NAME, compactVideo, outcome)
    }

    private fun compactMessage(outcome: CompactVideoOutcome, videoUnavailableForRange: Boolean): String = when (outcome) {
        CompactVideoOutcome.PASSTHROUGH_AV1 -> "Compact capture saved; existing AV1 video was copied without conversion"
        CompactVideoOutcome.ENCODED_AV1 -> "Compact capture exported with AV1 video (lossy; audio copied)"
        CompactVideoOutcome.KEPT_ORIGINAL_NOT_SMALLER ->
            "Compact capture exported; AV1 would not make this video smaller, so the original video was kept"
        CompactVideoOutcome.KEPT_ORIGINAL_ENCODER_UNAVAILABLE ->
            "Compact capture exported; the AV1 encoder is unavailable in this build, so the original video was kept"
        CompactVideoOutcome.NO_VIDEO ->
            if (videoUnavailableForRange) "Compact capture exported; video has no usable coverage for this range"
            else "Compact capture exported without video"
    }

    /**
     * [onWaitingForVideo] fires at most once, only if [waitForVideoCoverage] actually starts
     * polling (i.e. the requested end wasn't already covered) — the UI uses it to swap the export
     * popover's generic busy message for one that explains the pause instead of looking stuck.
     */
    @Synchronized
    fun export(
        session: CaptureSession,
        request: CaptureExportRequest,
        onWaitingForVideo: (() -> Unit)? = null,
        notes: Annotations? = null,
        onStatus: ((String) -> Unit)? = null,
    ): CaptureExportResult = exportSparseV5(session, request, onWaitingForVideo, notes, onStatus)

    @Suppress("CyclomaticComplexMethod", "LongMethod", "TooGenericExceptionCaught")
    private fun exportSparseV5(
        originalSession: CaptureSession,
        request: CaptureExportRequest,
        onWaitingForVideo: (() -> Unit)?,
        notes: Annotations?,
        onStatus: ((String) -> Unit)?,
    ): CaptureExportResult {
        checkNotInterrupted()
        require(request.customMinutes > 0) { "Custom capture range must be positive" }
        val destination = request.destination.absoluteFile
        require(request.overwriteExisting || !destination.exists()) { "Capture destination already exists: $destination" }
        val parent = destination.parentFile ?: File(".").absoluteFile
        require(parent.mkdirs() || parent.isDirectory) { "Cannot create capture destination directory: $parent" }
        val adapter = if (originalSession.storageVersion == CaptureStorageVersion.BLOCKS_V2) null
        else buildLegacySparseAdapter(originalSession, parent)
        val session = adapter?.session ?: originalSession
        try {
            val snapshotCheckpoint = requireSnapshotCheckpoint(session, request)
            require(session.logFile.isFile) { "Capture log is missing: ${session.logFile}" }
            require(session.blockIndexFile.isFile) { "Capture block index is missing: ${session.blockIndexFile}" }
            val frozenLogBytes = session.frozenLogBytes ?: session.logFile.length()
            val frozenBlockBytes = session.frozenBlockIndexBytes ?: session.blockIndexFile.length()
            require(frozenLogBytes in 0..session.logFile.length()) { "Capture log is shorter than its frozen boundary" }
            val sourceMetadata = (session.syncMetadataOverride ?: CaptureSyncMetadata.parse(readTextShared(session.syncFile)))
            val metadata = mergeManualAnchors(sourceMetadata, session.manualSegmentAnchors + request.captureSegmentAnchors)
            require(metadata.logBytes == frozenLogBytes) { "Capture sync metadata does not match the frozen log boundary" }
            val blockIndex = CaptureBlockIndex.read(
                session.blockIndexFile,
                session.logFile,
                maxIndexBytes = frozenBlockBytes,
                maxLogBytes = frozenLogBytes,
                verifyByteRange = 0L..-1L,
            )
            require(blockIndex.indexedRows == metadata.rowCount && blockIndex.indexedLogBytes == metadata.logBytes) {
                "Capture block index does not match the frozen sync metadata"
            }
            val timing = sparseTimelineForSession(session, metadata)
            val selectionBounds = if (request.range == CaptureRange.SELECTION) {
                val first = requireNotNull(request.selectedFirstRowOrdinal) { "Selection export requires a first row" }
                val last = requireNotNull(request.selectedLastRowOrdinal) { "Selection export requires a last row" }
                require(first > 0 && first <= last && last <= metadata.rowCount) {
                    "Selection export row bounds must be positive, ordered, and inside the frozen capture"
                }
                findSparseSelectionBounds(session, metadata, timing, blockIndex, frozenBlockBytes, frozenLogBytes, first, last)
            } else {
                null
            }
            val requestedLogStartMs = selectionBounds?.firstElapsedMs ?: requestedRangeStartMs(request, snapshotCheckpoint)
            val selectedEndMs = selectionBounds?.lastElapsedMs ?: requestedRangeEndMs(request)
            val sourceCursorByteOffset = session.logCheckpointByteOffset.takeIf {
                request.range == CaptureRange.SINCE_SAVE && it >= 0L
            }
            val sourceCursorRowOrdinal = session.logCheckpointRowOrdinal.takeIf { sourceCursorByteOffset != null }
            require(sourceCursorByteOffset == null || sourceCursorByteOffset <= frozenLogBytes) {
                "Saved byte cursor is beyond the frozen log boundary"
            }
            require(sourceCursorByteOffset == null || requireNotNull(sourceCursorRowOrdinal) <= metadata.rowCount) {
                "Saved row cursor is beyond the frozen capture"
            }
            val logBounds = selectionBounds ?: findSparseRangeBounds(
                SparseScanContext(session, metadata, timing, blockIndex, frozenBlockBytes, frozenLogBytes),
                SparseRangeQuery(
                    requestedLogStartMs, selectedEndMs, request.range == CaptureRange.SINCE_SAVE && snapshotCheckpoint >= 0L,
                    sourceCursorByteOffset, sourceCursorRowOrdinal,
                ),
            )
            val logStartMs = logBounds?.firstElapsedMs ?: requestedLogStartMs
            val videoRangeStartMs = selectionBounds?.firstElapsedMs
                ?: requestedRangeStartMs(request, if (request.range == CaptureRange.SINCE_SAVE) session.effectiveVideoCheckpointMs else snapshotCheckpoint)
            val requestedTimelineBaseLocalMs = when {
                request.range == CaptureRange.ALL -> 0L
                request.range == CaptureRange.SELECTION -> selectionBounds?.firstElapsedMs ?: 0L
                request.range == CaptureRange.SINCE_SAVE -> sinceSaveTimelineBaseElapsedMs(
                    snapshotCheckpoint,
                    session.effectiveVideoCheckpointMs,
                    request.includeVideo,
                    session.settings.recordVideo,
                )
                requestedLogStartMs != Long.MIN_VALUE -> requestedLogStartMs
                else -> 0L
            }
            val timelineBaseLocalMs = if (sourceCursorByteOffset != null && logBounds != null) {
                minOf(requestedTimelineBaseLocalMs, logBounds.firstElapsedMs)
            } else {
                requestedTimelineBaseLocalMs
            }
            val videoConfigured = session.settings.recordVideo && request.includeVideo
            val videoStartElapsed = session.videoStartElapsedMs
            val requestedSourceStart = selectionBounds?.firstVideoMs?.coerceAtLeast(0L) ?: videoStartElapsed?.let {
                if (videoRangeStartMs == Long.MIN_VALUE) 0L else (videoRangeStartMs - it + session.manualOffsetMs).coerceAtLeast(0L)
            }
            val requestedSourceEnd = selectionBounds?.lastVideoMs?.coerceAtLeast(requestedSourceStart ?: 0L) ?: videoStartElapsed?.let {
                (selectedEndMs - it + session.manualOffsetMs).coerceAtLeast(requestedSourceStart ?: 0L)
            }
            val normalVideoRange = videoConfigured && videoStartElapsed != null && session.videoFile.isFile &&
                session.videoFile.length() > 0L && requestedSourceStart != null && requestedSourceEnd != null &&
                requestedSourceEnd > requestedSourceStart
            val availabilityStart = videoRangeStartMs.takeUnless { it == Long.MIN_VALUE } ?: 0L
            val renderAvailability = videoConfigured && session.settings.showUnavailableFrames &&
                videoExporter is CaptureAvailabilityVideoExporter &&
                hasAvailabilityMetadataInRange(session, availabilityStart, selectedEndMs)
            val hasOfflineMetadata = hasAvailabilityMetadataInRange(session, availabilityStart, selectedEndMs)
            val includeVideo = normalVideoRange || renderAvailability
            val screenshotStartMs = selectionBounds?.firstElapsedMs ?: requestedLogStartMs
            val screenshots = snapshotScreenshots(
                session,
                screenshotStartMs,
                selectedEndMs,
                timelineBaseElapsedMs = metadata.timelineBaseElapsedMs,
            )
            require(screenshots.sumOf { it.length } <= MAX_SCREENSHOTS_BYTES) { "Capture screenshots exceed the combined size limit" }
            val preflightCandidates = sparseCandidateBlocks(
                blockIndex = blockIndex,
                metadata = metadata,
                selectedStartMs = logStartMs,
                selectedEndMs = selectedEndMs,
                selectedFirstRowOrdinal = request.selectedFirstRowOrdinal.takeIf { request.range == CaptureRange.SELECTION },
                selectedLastRowOrdinal = request.selectedLastRowOrdinal.takeIf { request.range == CaptureRange.SELECTION },
                sourceCursorByteOffset = sourceCursorByteOffset,
            )
            val estimatedLogBytes = preflightCandidates.fold(0L) { total, block ->
                saturatingAdd(total, block.byteEnd - block.byteStart)
            }
            val estimatedIndexBytes = preflightCandidates.fold(metadata.toJson().toString().toByteArray(Charsets.UTF_8).size.toLong()) { total, block ->
                saturatingAdd(
                    total,
                    block.toJson().toString().toByteArray(Charsets.UTF_8).size.toLong() + SPARSE_INDEX_RECORD_OVERHEAD_BYTES,
                )
            }
            preflight(
                destination = destination,
                reserveBytes = session.settings.freeSpaceReserveBytes,
                logBytes = estimatedLogBytes,
                videoBytes = if (includeVideo) session.videoFile.length() else 0L,
                screenshotsBytes = screenshots.sumOf { it.length },
                timelineBytes = estimatedIndexBytes,
                // Compact reserve: a second copy of the log plus a transcoded video. It also covers the zstd
                // temp file in `work` (zstd worst case is input + ~0.4%, and normally a small fraction of it).
                extraWorkingBytes = if (request.format == CaptureExportFormat.COMPACT) {
                    saturatingAdd(
                        saturatingAdd(estimatedLogBytes, if (includeVideo) session.videoFile.length() else 0L),
                        if (includeVideo) session.videoFile.length() else 0L,
                    )
                } else {
                    0L
                },
            )
            val work = Files.createTempDirectory(parent.toPath(), ".${safeStem(destination.name)}-export-").toFile()
            val archiveTemp = File(parent, ".${destination.name}.tmp-${UUID.randomUUID()}")
            var published = false
            try {
                val markerRanges = if (session.settings.markerNotesInSnapshot && notes != null) {
                    notes.blocks.filterIsInstance<AnnBlock.Note>().mapNotNull { note ->
                        parseMarkerHeader(note.text)?.copy(noteBlockId = note.id)
                    }
                } else {
                    emptyList()
                }
                val staged = stageSparseSelection(
                    SparseStageRequest(
                        scan = SparseScanContext(session, metadata, timing, blockIndex, frozenBlockBytes, frozenLogBytes),
                        filter = SparseLineFilter(
                            selectedStartMs = selectionBounds?.firstElapsedMs ?: requestedLogStartMs,
                            selectedEndMs = selectedEndMs,
                            selectedFirstRowOrdinal = request.selectedFirstRowOrdinal.takeIf { request.range == CaptureRange.SELECTION },
                            selectedLastRowOrdinal = request.selectedLastRowOrdinal.takeIf { request.range == CaptureRange.SELECTION },
                            sourceCursorByteOffset = sourceCursorByteOffset,
                            sourceCursorRowOrdinal = sourceCursorRowOrdinal,
                        ),
                        excludeStart = request.range == CaptureRange.SINCE_SAVE && snapshotCheckpoint >= 0,
                        timelineBaseLocalMs = timelineBaseLocalMs,
                        markerRanges = markerRanges,
                        work = work,
                    ),
                )
                require(staged.recordCount > 0 || (request.range == CaptureRange.SINCE_SAVE && includeVideo) || hasOfflineMetadata) {
                    "The selected capture range contains no complete log lines"
                }
                preflight(
                    destination = destination,
                    reserveBytes = session.settings.freeSpaceReserveBytes,
                    logBytes = staged.logBytes,
                    videoBytes = if (includeVideo) session.videoFile.length() else 0L,
                    screenshotsBytes = screenshots.sumOf { it.length },
                    timelineBytes = staged.blockIndexFile.length() + staged.syncFile.length(),
                    // Same Compact reserve as above, zstd log temp file included.
                    extraWorkingBytes = if (request.format == CaptureExportFormat.COMPACT) {
                        saturatingAdd(
                            saturatingAdd(staged.logBytes, if (includeVideo) session.videoFile.length() else 0L),
                            if (includeVideo) session.videoFile.length() else 0L,
                        )
                    } else {
                        0L
                    },
                )
                val clippedAvailability = clipAvailabilityIntervals(
                    session.availabilityIntervals,
                    baseElapsedMs = timelineBaseLocalMs,
                    endElapsedMs = selectedEndMs,
                )
                val requestedContainer = session.settings.videoContainer
                val capturesAudio = session.settings.audio || session.settings.microphoneDeviceId != MICROPHONE_OFF_ID
                val container = if (requestedContainer == CaptureVideoContainer.MP4 && capturesAudio) CaptureVideoContainer.MKV else requestedContainer
                val containerFellBackForAudio = container != requestedContainer
                val videoEntryName = "screen.${container.extension}"
                val stagedVideo = if (includeVideo) File(work, videoEntryName) else null
                var videoUnavailableForRange = videoConfigured && !includeVideo
                var availabilityFramesRendered = false
                val clip = if (stagedVideo != null && renderAvailability) {
                    try {
                        val source = session.videoFile.takeIf { it.isFile && it.length() > 0L }
                        if (source != null && session.status == CaptureStatus.RECORDING && requestedSourceStart != null && requestedSourceEnd != null) {
                            waitForVideoCoverage(source, requestedSourceStart, requestedSourceEnd, onWaitingForVideo)
                        }
                        (videoExporter as CaptureAvailabilityVideoExporter).exportWithAvailability(
                            source = source,
                            destination = stagedVideo,
                            timelineStartElapsedMs = availabilityStart,
                            timelineEndElapsedMs = selectedEndMs,
                            sourceElapsedOriginMs = (videoStartElapsed ?: timelineBaseLocalMs) - session.manualOffsetMs,
                            availabilityIntervals = session.availabilityIntervals,
                            sourceIsFinal = source != null && isVideoFinal(session),
                        ).also { availabilityFramesRendered = true }
                    } catch (failure: Exception) {
                        if (!isNoUsableVideoInterval(failure)) throw failure
                        videoUnavailableForRange = true
                        null
                    }
                } else if (stagedVideo != null && requestedSourceStart != null && requestedSourceEnd != null) {
                    try {
                        if (session.status == CaptureStatus.RECORDING) {
                            waitForVideoCoverage(session.videoFile, requestedSourceStart, requestedSourceEnd, onWaitingForVideo)
                        }
                        if (isVideoFinal(session)) videoExporter.exportFinal(session.videoFile, stagedVideo, requestedSourceStart, requestedSourceEnd)
                        else videoExporter.export(session.videoFile, stagedVideo, requestedSourceStart, requestedSourceEnd)
                    } catch (failure: Exception) {
                        if (!isNoUsableVideoInterval(failure)) throw failure
                        videoUnavailableForRange = true
                        null
                    }
                } else {
                    null
                }
                checkNotInterrupted()
                val videoAvailable = stagedVideo?.let { it.isFile && it.length() > 0L } == true
                val effectiveClip = clip.takeIf { videoAvailable }
                if (request.range == CaptureRange.SINCE_SAVE) {
                    val hasUsableVideo = effectiveClip?.durationMs?.let { it > 0L } == true
                    require(staged.rowCount > 0 || hasUsableVideo || hasOfflineMetadata) {
                        "Nothing new to save: this Since last save range has no complete log rows, usable video coverage, or device-availability records"
                    }
                }
                val isCompact = request.format == CaptureExportFormat.COMPACT
                // Only the video can differ in size between ZIP and Compact (every other entry is
                // identical), so the choice is made here, once, before any notes/descriptor are built.
                val liveSource = originalSession.status == CaptureStatus.RECORDING || LiveCaptureSessions.isLive(originalSession.directory)
                val compactChoice = if (isCompact) {
                    chooseCompactVideo(stagedVideo?.takeIf { videoAvailable }, work, onStatus, liveSource)
                } else {
                    null
                }
                val shippedVideoFile = if (compactChoice != null) compactChoice.file else stagedVideo?.takeIf { videoAvailable }
                val shippedVideoEntryName = if (compactChoice != null) compactChoice.entryName else videoEntryName.takeIf { videoAvailable }
                val outputVideoOrigin = when {
                    !videoAvailable -> null
                    availabilityFramesRendered -> Math.addExact(sourceMetadata.timelineBaseElapsedMs, availabilityStart)
                    else -> {
                        val sourceOrigin = Math.subtractExact(
                            Math.addExact(
                                session.videoStartElapsedMs ?: 0L,
                                sourceMetadata.timelineBaseElapsedMs,
                            ),
                            session.manualOffsetMs,
                        )
                        Math.addExact(sourceOrigin, effectiveClip?.actualStartMs ?: 0L)
                    }
                }
                val sourceVideoOrigin = if (videoAvailable) {
                    val sourceElapsedOrigin = Math.subtractExact(videoStartElapsed ?: timelineBaseLocalMs, session.manualOffsetMs)
                    Math.addExact(sourceMetadata.timelineBaseElapsedMs, sourceElapsedOrigin)
                } else {
                    null
                }
                val manualVideoShift = if (sourceVideoOrigin != null && outputVideoOrigin != null) {
                    Math.subtractExact(sourceVideoOrigin, outputVideoOrigin)
                } else {
                    null
                }
                if (manualVideoShift != null) {
                    val rebased = staged.metadata.copy(
                        segments = staged.metadata.segments.map { segment ->
                            val anchor = segment.manualAnchor ?: return@map segment
                            segment.copy(manualAnchor = anchor.copy(videoMs = Math.addExact(anchor.videoMs, manualVideoShift)))
                        },
                    )
                    staged.syncFile.writeText(rebased.toJson(), Charsets.UTF_8)
                }
                val stagedScreenshots = stageScreenshots(screenshots, work)
                val (notesAsset, markerAssets) = stageNotes(work, session, notes, null, effectiveClip, shippedVideoEntryName, staged.markerClips)
                val logCodec = if (isCompact && zstdAvailable()) CaptureArchiveLogCodec.ZSTD else CaptureArchiveLogCodec.DEFLATE
                writeCapturedWithIndagiumBlurb(work, compact = isCompact, zstdLog = logCodec == CaptureArchiveLogCodec.ZSTD)
                val logAsset = asset("logcat.log", staged.logFile)
                val videoAsset = shippedVideoFile?.let { asset(requireNotNull(shippedVideoEntryName), it) }
                val screenshotAssets = stagedScreenshots.map { (path, file) -> asset(path, file) }
                val descriptorBase = Math.addExact(sourceMetadata.timelineBaseElapsedMs, timelineBaseLocalMs)
                val descriptor = CaptureArchiveDescriptor(
                    formatVersion = CAPTURE_ARCHIVE_VERSION,
                    app = "Indagium ${BuildInfo.APP_VERSION}",
                    sessionId = session.id,
                    device = CaptureArchiveDevice(session.device.serial, session.device.state, session.device.model, session.device.emulator),
                    sessionStartedEpochMs = session.startedEpochMs,
                    exportedEpochMs = System.currentTimeMillis(),
                    range = request.range,
                    manualOffsetMs = session.manualOffsetMs,
                    interruptions = session.interruptions,
                    log = logAsset,
                    logStartMs = staged.firstElapsedMs ?: timelineBaseLocalMs,
                    logEndMs = staged.lastElapsedMs ?: selectedEndMs,
                    mapping = null,
                    video = videoAsset,
                    videoStartMs = outputVideoOrigin,
                    videoEndMs = outputVideoOrigin?.let { origin ->
                        effectiveClip?.let { origin + it.durationMs }
                            ?: if (availabilityFramesRendered) origin + (selectedEndMs - availabilityStart) else null
                    },
                    screenshots = screenshotAssets,
                    notes = notesAsset,
                    markers = markerAssets,
                    syncAnchor = null,
                    timeline = null,
                    availabilityIntervals = clippedAvailability,
                    timelineBaseElapsedMs = descriptorBase,
                    timelineDurationMs = staged.metadata.timelineDurationMs,
                    availabilityFramesRendered = availabilityFramesRendered,
                    showUnavailableFrames = session.settings.showUnavailableFrames,
                    blocks = asset("mapping/blocks.jsonl", staged.blockIndexFile),
                    sparseSync = asset("timeline/sync.json", staged.syncFile),
                )
                File(work, CAPTURE_DESCRIPTOR_NAME).writeText(descriptor.toJson(), Charsets.UTF_8)
                writeZip(work, archiveTemp, descriptor, logCodec)
                checkNotInterrupted()
                runCatching { onStatus?.invoke(if (request.format == CaptureExportFormat.COMPACT) "Publishing Compact archive…" else "Publishing ZIP…") }
                checkNotInterrupted()
                if (request.overwriteExisting) publishReplacing(archiveTemp, destination) else publishWithoutOverwrite(archiveTemp, destination)
                published = true
                return CaptureExportResult(
                    file = destination,
                    logCoveredEndMs = staged.lastElapsedMs ?: selectedEndMs,
                    // The descriptor's video times sit on the archive's absolute time base (they include the
                    // source session's timelineBaseElapsedMs), but the result feeds the source session's
                    // local Since-last-save cursors, like logCoveredEndMs: report them session-local.
                    videoCoveredEndMs = descriptor.videoEndMs?.let { Math.subtractExact(it, sourceMetadata.timelineBaseElapsedMs) },
                    videoActualStartMs = descriptor.videoStartMs?.let { Math.subtractExact(it, sourceMetadata.timelineBaseElapsedMs) },
                    message = buildString {
                        append(
                            if (compactChoice != null) {
                                compactMessage(compactChoice.outcome, videoUnavailableForRange)
                            } else {
                                when {
                                    videoAsset != null -> "Capture exported with video"
                                    videoUnavailableForRange -> "Capture exported; video has no usable coverage for this range"
                                    else -> "Capture exported"
                                }
                            },
                        )
                        val originalVideoShips = videoAsset != null && (compactChoice == null || compactChoice.shipsOriginalVideo)
                        if (originalVideoShips && containerFellBackForAudio) {
                            append(" (used .mkv: audio export to .mp4 is not supported)")
                        }
                        if (isCompact && logCodec == CaptureArchiveLogCodec.DEFLATE) {
                            append(" (log stored with DEFLATE: zstd is unavailable on this system)")
                        }
                        if (staged.uncertainBoundary) {
                            append(". Some capture timing is uncertain; boundary log rows may extend beyond the requested time range.")
                        }
                    },
                    logCoveredEndByteOffset = staged.sourceEndByteOffset,
                    logCoveredEndRowOrdinal = staged.sourceEndRowOrdinal,
                )
            } finally {
                deleteTree(work)
                if (!published) archiveTemp.delete()
            }
        } finally {
            adapter?.directory?.let(::releaseLegacyAdapterDirectory)
        }
    }
}

private const val COMPACT_VIDEO_ENTRY_NAME = "screen.mkv"

private enum class CompactVideoOutcome {
    NO_VIDEO,
    PASSTHROUGH_AV1,
    ENCODED_AV1,
    KEPT_ORIGINAL_NOT_SMALLER,
    KEPT_ORIGINAL_ENCODER_UNAVAILABLE,
}

/** The video a Compact export ships: [entryName]/[file] are null only for [CompactVideoOutcome.NO_VIDEO]. */
private data class CompactVideoChoice(val entryName: String?, val file: File?, val outcome: CompactVideoOutcome) {
    val shipsOriginalVideo: Boolean
        get() = outcome == CompactVideoOutcome.KEPT_ORIGINAL_NOT_SMALLER ||
            outcome == CompactVideoOutcome.KEPT_ORIGINAL_ENCODER_UNAVAILABLE
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

    /**
     * Restores the verified log when an unrelated capture asset (usually video) no longer matches
     * its checksum. The caller keeps the tab as a plain log and separately disables capture sync;
     * this path never blesses the damaged archive or returns its timeline/video metadata.
     */
    fun extractVerifiedLogForRestore(source: File, expectedLogPath: String?, cacheDirectory: File): File? = runCatching {
        require(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) { "Cannot create capture restore cache" }
        if (source.isDirectory || source.name == CAPTURE_DESCRIPTOR_NAME) {
            val descriptorFile = if (source.isDirectory) descriptorFile(source) else source
            val root = descriptorFile.parentFile ?: error("Capture descriptor has no parent")
            val descriptor = parseDescriptor(readSmallFile(descriptorFile))
            require(expectedLogPath == null || descriptor.log.path == expectedLogPath) {
                "Restored capture log entry no longer matches its descriptor"
            }
            val logFile = resolveSafe(root, descriptor.log.path)
            require(logFile.isFile && !hasSymbolicLink(root, logFile)) { "Capture log is missing or unsafe" }
            require(logFile.length() <= assetLimit(descriptor, descriptor.log)) { "Capture log exceeds its size limit" }
            require(sha256(logFile) == descriptor.log.sha256) { "Capture log checksum mismatch" }
            return@runCatching logFile
        }
        require(source.isFile) { "Capture archive is missing" }
        val output = File(cacheDirectory, "capture-log-restore-${UUID.randomUUID()}")
        require(output.mkdir()) { "Cannot create capture log restore directory" }
        var success = false
        try {
            ZipFile.builder().setFile(source).get().use { zip ->
                val scan = scanZipEntries(zip)
                val descriptorEntry = scan.descriptorEntry ?: error("Capture descriptor is missing")
                val descriptorText = zip.openEntryStream(descriptorEntry, "capture descriptor").use { readBoundedText(it, MAX_DESCRIPTOR_BYTES) }
                val descriptor = parseDescriptor(descriptorText)
                require(expectedLogPath == null || descriptor.log.path == expectedLogPath) {
                    "Restored capture log entry no longer matches its descriptor"
                }
                val entrySize = scan.entrySizes[descriptor.log.path] ?: error("Capture log is missing")
                require(entrySize <= assetLimit(descriptor, descriptor.log)) { "Capture log exceeds its size limit" }
                val entry = zip.getEntry(descriptor.log.path) ?: error("Capture log is missing")
                val target = resolveSafe(output, descriptor.log.path)
                target.parentFile?.mkdirs()
                zip.openEntryStream(entry, "capture log").use { input ->
                    extractVerified(input, target, descriptor.log, assetLimit(descriptor, descriptor.log))
                }
                success = true
                target
            }
        } catch (e: LinkageError) {
            throw zstdLoadFailure(e)
        } finally {
            if (!success) deleteTree(output)
        }
    }.getOrNull()

    private fun openZip(source: File, cacheDirectory: File): ImportedCapture = try {
        extractZip(source, cacheDirectory)
    } catch (e: LinkageError) {
        throw zstdLoadFailure(e)
    }

    /**
     * Opens [source] through the reuse cache ([CaptureExtractionCache]): the first open of an archive
     * state extracts and verifies it into `capture-<key>`; later opens (the next launch, a second
     * tab on the same file) validate the marker and reuse that directory. Everything before the
     * cache check (entry scan, descriptor parse, size limits) runs on every open, so a malformed
     * archive fails with the same exceptions as before and never gets a marker.
     */
    private fun extractZip(source: File, cacheDirectory: File): ImportedCapture {
        val key = CaptureExtractionCache.keyFor(source)
        val finalDirectory = CaptureExtractionCache.directoryFor(cacheDirectory, key)
        return CaptureExtractionCache.withLock(finalDirectory) {
            ZipFile.builder().setFile(source).get().use { zip ->
                val scan = scanZipEntries(zip)
                val descriptorEntry = scan.descriptorEntry ?: throw CaptureArchiveException("Capture descriptor is missing")
                require(!descriptorEntry.isDirectory) { "Capture descriptor is not a file" }
                val descriptorText = zip.openEntryStream(descriptorEntry, "capture descriptor").use { readBoundedText(it, MAX_DESCRIPTOR_BYTES) }
                val descriptor = parseDescriptor(descriptorText)
                val screenshotsBytes = descriptor.screenshots.sumOf { scan.entrySizes[it.path] ?: 0L }
                require(screenshotsBytes <= MAX_SCREENSHOTS_BYTES) {
                    "Capture screenshots exceed the combined size limit"
                }
                val target = when (val opening = reuseOrClear(cacheDirectory, finalDirectory, key, descriptor, scan, source)) {
                    is Opening.Reused -> return@use opening.capture
                    is Opening.Extract -> opening.directory
                }
                requireExtractionSpace(cacheDirectory, scan.entrySizes.values.fold(0L, ::saturatingAdd))
                CaptureExtractionCache.extractionObserver?.invoke(target)
                val temporary = CaptureExtractionCache.temporaryDirectoryFor(cacheDirectory, key)
                require(temporary.mkdir()) { "Cannot create capture extraction directory: $temporary" }
                CaptureExtractionCache.beginTemporary(temporary)
                var published = false
                try {
                    extractAssets(zip, temporary, descriptor)
                    CaptureExtractionCache.writeMarker(temporary, key, descriptor.assets())
                    publishExtraction(temporary, target, key, descriptor, scan)
                    published = true
                } finally {
                    CaptureExtractionCache.endTemporary(temporary)
                    if (!published) deleteTree(temporary)
                }
                // Our own fresh directory; a refusal here means another instance's sweep claimed it first.
                val use = CaptureExtractionCache.acquireUse(target) ?: throw captureCacheBusy()
                var valid = false
                try {
                    validateImported(target, descriptor, source).copy(extractionUse = use).also { valid = true }
                } finally {
                    // A directory that does not validate must not be offered to the next open.
                    if (!valid) CaptureExtractionCache.deleteDirectory(target)
                }
            }
        }
    }

    private sealed interface Reuse {
        class Reused(val capture: ImportedCapture) : Reuse

        /** Absent, stale, damaged or no longer validating: extract again. */
        data object Miss : Reuse

        /** Another process holds the directory's lease exclusively (its sweep is deleting it): touch nothing. */
        data object Busy : Reuse
    }

    /** What [reuseOrClear] settled: an open that reused a directory, or the empty directory to extract into. */
    private sealed interface Opening {
        class Reused(val capture: ImportedCapture) : Opening

        class Extract(val directory: File) : Opening
    }

    private fun captureCacheBusy() = CaptureArchiveException(
        "The capture archive cache is being cleaned by another Indagium window; try opening it again",
    )

    /**
     * The reused extraction, or the directory to extract into once [finalDirectory] has been cleared. While
     * another process's sweep holds its lease, polls (bounded by [CaptureExtractionCache.leaseWaitMs]) until
     * that sweep is done: the directory is then reusable or gone. Never deletes or writes a busy directory, and
     * never deletes one another process still reads: replacing needs exclusive ownership, and when only a shared
     * lease is on offer the archive is extracted into a separate directory (named like the legacy per-open ones,
     * so the sweep removes it as junk once nothing uses it) and the damaged one is left to its readers.
     */
    private fun reuseOrClear(
        cacheDirectory: File,
        finalDirectory: File,
        key: String,
        descriptor: CaptureArchiveDescriptor,
        scan: ZipEntryScan,
        source: File,
    ): Opening {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CaptureExtractionCache.leaseWaitMs)
        var attempt = 0
        while (true) {
            when (val outcome = reuseExtraction(finalDirectory, key, descriptor, scan, source)) {
                is Reuse.Reused -> return Opening.Reused(outcome.capture)
                Reuse.Busy -> Unit
                Reuse.Miss -> when (CaptureExtractionCache.clearForReplacement(finalDirectory)) {
                    CaptureExtractionCache.Replacement.CLEARED -> return Opening.Extract(finalDirectory)
                    CaptureExtractionCache.Replacement.HELD_BY_OTHERS ->
                        return Opening.Extract(CaptureExtractionCache.separateDirectoryFor(cacheDirectory))
                    CaptureExtractionCache.Replacement.BUSY -> Unit
                }
            }
            if (!CaptureExtractionCache.pauseBeforeRetry(attempt++, deadline)) throw captureCacheBusy()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun reuseExtraction(
        finalDirectory: File,
        key: String,
        descriptor: CaptureArchiveDescriptor,
        scan: ZipEntryScan,
        source: File,
    ): Reuse {
        if (!CaptureExtractionCache.isReusable(finalDirectory, key, descriptor.assets(), scan.entrySizes.keys)) return Reuse.Miss
        // Lease and touch before validating: another instance's sweep may be deleting an idle directory right now,
        // and once we hold the lease it cannot. A refused lease means that sweep got there first.
        val use = CaptureExtractionCache.acquireUse(finalDirectory) ?: return Reuse.Busy
        CaptureExtractionCache.touch(finalDirectory)
        return try {
            Reuse.Reused(validateImported(finalDirectory, descriptor, source).copy(extractionUse = use))
        } catch (_: Exception) {
            // Not usable: give the claim back so replacing the directory can take it exclusively.
            use.close()
            Reuse.Miss
        }
    }

    /** Atomically renames [temporary] to [finalDirectory]; if another process won the race, uses its directory. */
    private fun publishExtraction(
        temporary: File,
        finalDirectory: File,
        key: String,
        descriptor: CaptureArchiveDescriptor,
        scan: ZipEntryScan,
    ) {
        try {
            try {
                Files.move(temporary.toPath(), finalDirectory.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), finalDirectory.toPath())
            }
        } catch (failure: IOException) {
            if (!CaptureExtractionCache.isReusable(finalDirectory, key, descriptor.assets(), scan.entrySizes.keys)) throw failure
            deleteTree(temporary)
        }
    }

    private fun extractAssets(zip: ZipFile, output: File, descriptor: CaptureArchiveDescriptor) {
        val remainingAssets = descriptor.assets().associateBy { it.path }.toMutableMap()
        val entries = zip.entries
        var entryCount = 0
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            entryCount++
            require(entryCount <= MAX_ARCHIVE_ENTRIES_SCANNED) { "Capture archive has too many entries" }
            if (entry.isDirectory) continue
            val name = normalizedEntryName(entry.name)
            if (name == CAPTURE_DESCRIPTOR_NAME || name == CAPTURED_WITH_INDAGIUM_NAME) continue
            val asset = remainingAssets.remove(name)
                ?: throw CaptureArchiveException("Capture archive contains an unreferenced file: $name")
            val limit = assetLimit(descriptor, asset)
            // A size the zip's own central directory declares is a cheap fail-fast bound;
            // [extractVerified] re-enforces the same limit while actually streaming, which is
            // what an inconsistent/lying declared size can't get past.
            if (entry.size >= 0) require(entry.size <= limit) { "Capture asset exceeds its size limit: ${asset.path}" }
            val target = resolveSafe(output, asset.path)
            target.parentFile?.mkdirs()
            val what = if (asset == descriptor.log) "capture log" else "capture asset ${asset.path}"
            zip.openEntryStream(entry, what).use { input -> extractVerified(input, target, asset, limit) }
        }
        require(remainingAssets.isEmpty()) {
            "Capture asset is missing: ${remainingAssets.keys.first()}"
        }
    }

    private fun openExtracted(descriptorFile: File, root: File, source: File): ImportedCapture {
        require(descriptorFile.isFile) { "Capture descriptor is missing: $descriptorFile" }
        require(!Files.isSymbolicLink(descriptorFile.toPath())) { "Capture descriptor cannot be a symbolic link" }
        val descriptor = parseDescriptor(readSmallFile(descriptorFile))
        var screenshotsBytes = 0L
        descriptor.assets().forEach { asset ->
            val target = resolveSafe(root, asset.path)
            require(!hasSymbolicLink(root, target)) { "Capture asset uses a symbolic link: ${asset.path}" }
            require(target.isFile) { "Capture asset is missing: ${asset.path}" }
            require(target.length() <= assetLimit(descriptor, asset)) {
                "Capture asset exceeds its size limit: ${asset.path}"
            }
            if (asset in descriptor.screenshots) screenshotsBytes = saturatingAdd(screenshotsBytes, target.length())
            require(sha256(target) == asset.sha256) { "Capture asset checksum mismatch: ${asset.path}" }
        }
        require(screenshotsBytes <= MAX_SCREENSHOTS_BYTES) {
            "Capture screenshots exceed the combined size limit"
        }
        return validateImported(root, descriptor, source)
    }

    private fun timelineFromRows(
        root: File,
        descriptor: CaptureArchiveDescriptor,
        timelineAsset: CaptureArchiveAsset,
        parsedLogCount: Int,
    ): CaptureTimeline {
        val elapsedRows = readTimelineRows(
            resolveSafe(root, timelineAsset.path),
            parsedLogCount,
            descriptor.timelineBaseElapsedMs,
            descriptor.timelineDurationMs,
            descriptor.availabilityIntervals,
        )
        val anchor = descriptor.syncAnchor
        val anchorElapsed = anchor?.let { elapsedRows.getOrNull(it.row - 1)?.second }
        val videoDurationMs = if (descriptor.videoStartMs != null && descriptor.videoEndMs != null) {
            (descriptor.videoEndMs - descriptor.videoStartMs).coerceAtLeast(0L)
        } else {
            null
        }
        val rows = elapsedRows.mapIndexed { index, (ordinal, elapsedMs) ->
            val videoMs = if (anchor != null && anchorElapsed != null && descriptor.video != null) {
                runCatching {
                    Math.addExact(anchor.videoMs, Math.subtractExact(elapsedMs, anchorElapsed))
                }.getOrNull()?.takeIf { position ->
                    position >= 0L && (videoDurationMs == null || position <= videoDurationMs)
                }
            } else {
                null
            }
            require(elapsedMs <= descriptor.timelineDurationMs) { "Capture timeline row exceeds its declared duration" }
            CaptureMappingRow(index + 1, elapsedMs, videoMs)
        }
        return CaptureTimeline(
            rows = rows,
            quality = if (anchor != null) "estimated" else "unmapped",
            availabilityIntervals = descriptor.availabilityIntervals,
            timelineBaseElapsedMs = descriptor.timelineBaseElapsedMs,
            timelineDurationMs = descriptor.timelineDurationMs,
            // The row anchor is authoritative for the PTS-to-capture mapping. In particular,
            // it preserves the manual calibration already reflected in the rows instead of
            // deriving a subtly different origin from the descriptor's coverage edge.
            videoOriginElapsedMs = if (anchor != null && anchorElapsed != null) {
                runCatching {
                    Math.addExact(
                        descriptor.timelineBaseElapsedMs,
                        Math.subtractExact(anchorElapsed, anchor.videoMs),
                    )
                }.getOrNull()
            } else {
                descriptor.videoStartMs
            },
            availabilityFramesRendered = descriptor.availabilityFramesRendered,
            showUnavailableFrames = descriptor.showUnavailableFrames,
        )
    }

    private fun timelineFromSparse(
        root: File,
        descriptor: CaptureArchiveDescriptor,
        parsedLogCount: Int,
        logFile: File,
    ): Pair<CaptureTimeline, CaptureSyncMetadata> {
        val blockAsset = requireNotNull(descriptor.blocks)
        val syncAsset = requireNotNull(descriptor.sparseSync)
        val blocksFile = resolveSafe(root, blockAsset.path)
        val syncFile = resolveSafe(root, syncAsset.path)
        val rawMetadata = CaptureSyncMetadata.parse(readTextShared(syncFile))
        val retainedAnchors = if (descriptor.log.path == IN_PLACE_LOG_PATH) {
            readCaptureSessionDirectory(root)?.manualSegmentAnchors.orEmpty()
        } else {
            emptyList()
        }
        val retainedOverrides = retainedAnchors.associateBy(CaptureSegmentManualAnchor::segmentId)
        require(retainedOverrides.size == retainedAnchors.size) { "Retained capture has duplicate manual segment anchors" }
        require(retainedOverrides.keys.all { id -> rawMetadata.segments.any { it.id == id } }) {
            "Retained capture manual anchor references an unknown segment"
        }
        val metadata = if (retainedOverrides.isEmpty()) rawMetadata else rawMetadata.copy(
            segments = rawMetadata.segments.map { segment ->
                retainedOverrides[segment.id]?.let { segment.copy(manualAnchor = it) } ?: segment
            },
        )
        require(metadata.logBytes == logFile.length() && metadata.rowCount == parsedLogCount) {
            "Capture sync metadata does not match the archive log"
        }
        require(metadata.timelineBaseElapsedMs == descriptor.timelineBaseElapsedMs &&
            metadata.timelineDurationMs == descriptor.timelineDurationMs
        ) { "Capture sync bounds do not match the archive descriptor" }
        val blocks = CaptureBlockIndex.read(blocksFile, logFile)
        require(blocks.indexedLogBytes == metadata.logBytes && blocks.indexedRows == metadata.rowCount) {
            "Capture block index does not match the archive log"
        }
        val videoDurationMs = if (descriptor.videoStartMs != null && descriptor.videoEndMs != null) {
            (descriptor.videoEndMs - descriptor.videoStartMs).coerceAtLeast(0L)
        } else {
            null
        }
        val timeline = CaptureTimeline(
            rows = emptyList(),
            quality = if (metadata.segments.any { it.reliability == CaptureSyncReliability.RELIABLE }) "estimated" else "uncertain",
            availabilityIntervals = descriptor.availabilityIntervals,
            timelineBaseElapsedMs = descriptor.timelineBaseElapsedMs,
            timelineDurationMs = descriptor.timelineDurationMs,
            videoOriginElapsedMs = descriptor.videoStartMs,
            availabilityFramesRendered = descriptor.availabilityFramesRendered,
            showUnavailableFrames = descriptor.showUnavailableFrames,
            syncSegments = metadata.segments,
            videoDurationMs = videoDurationMs,
            sparseTiming = true,
            sourceStartedEpochMs = descriptor.sessionStartedEpochMs,
        )
        return timeline to metadata
    }

    private fun timelineFromLegacyMapping(
        root: File,
        descriptor: CaptureArchiveDescriptor,
        mappingAsset: CaptureArchiveAsset,
        parsedLogCount: Int,
    ): CaptureTimeline {
        val rows = readMapping(resolveSafe(root, mappingAsset.path))
        require(rows.size == parsedLogCount) {
            "Capture mapping row count ${rows.size} does not match parsed log row count $parsedLogCount"
        }
        return CaptureTimeline(
            rows = rows,
            manualOffsetMs = descriptor.manualOffsetMs,
            timelineDurationMs = rows.lastOrNull()?.elapsedMs ?: 0L,
            videoOriginElapsedMs = rows.firstNotNullOfOrNull { row ->
                row.videoMs?.let { row.elapsedMs - it }
            },
        )
    }

    private fun validateImported(root: File, descriptor: CaptureArchiveDescriptor, source: File): ImportedCapture {
        val logFile = resolveSafe(root, descriptor.log.path)
        val videoFile = descriptor.video?.let { resolveSafe(root, it.path) }
        val parsedLogCount = logFile.inputStream().use { input ->
            openLogTextReader(input).useLines { lines -> lines.count { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() && !trimmed.startsWith("-----")
            } }
        }
        // v1/v2: a full row-by-row mapping file to validate and rehydrate into a CaptureTimeline.
        // v3: no mapping file at all — descriptor.mapping is null (see its own doc) and there is
        // nothing here to read; AppState instead turns descriptor.syncAnchor into a plain
        // VideoAnchor once it has parsed the log itself.
        val sparseTimeline = if (descriptor.formatVersion >= 5) timelineFromSparse(root, descriptor, parsedLogCount, logFile) else null
        val timeline = sparseTimeline?.first
            ?: descriptor.timeline?.let { timelineAsset -> timelineFromRows(root, descriptor, timelineAsset, parsedLogCount) }
            ?: descriptor.mapping?.let { mappingAsset -> timelineFromLegacyMapping(root, descriptor, mappingAsset, parsedLogCount) }
        descriptor.syncAnchor?.let { anchor ->
            require(anchor.row in 1..parsedLogCount) {
                "Capture sync anchor row ${anchor.row} is outside the parsed log ($parsedLogCount rows)"
            }
        }
        val correctedAnchor = recomputeStaleInPlaceSyncAnchor(root, descriptor, logFile)
        // Decoded but NOT re-anchored here — see ImportedCapture.notes' own doc for why that's the
        // caller's job (it needs the actually-parsed `List<LogEntry>`, which this layer never builds).
        // A malformed/corrupt notes.ann degrades to "no notes" rather than failing the whole import;
        // the log/video/mapping this function exists to validate are unaffected either way.
        val notes = descriptor.notes?.let { asset ->
            runCatching { resolveSafe(root, asset.path).readText(Charsets.UTF_8).annotationsFromToken() }.getOrNull()
        }
        return ImportedCapture(
            logFile = logFile,
            videoFile = videoFile,
            timeline = timeline,
            descriptor = descriptor,
            source = source,
            notes = notes,
            syncAnchor = if (correctedAnchor != null) correctedAnchor.anchor else descriptor.syncAnchor,
            staleSyncAnchor = correctedAnchor?.let { descriptor.syncAnchor },
            screenshotFiles = descriptor.screenshots.map { resolveSafe(root, it.path) },
            sparseSyncMetadata = sparseTimeline?.second,
            root = root,
        )
    }
}

/** The result of [recomputeStaleInPlaceSyncAnchor]: a recomputed [anchor], which may be null when
 *  no row of the capture can be anchored at all. */
private class CorrectedSyncAnchor(val anchor: CaptureSyncAnchor?)

/**
 * Load-time guard for captures finalized before [estimateCaptureSyncAnchor] restricted its anchor
 * candidates to rows recorded while the video was running. Such a capture's descriptor can pin the
 * anchor to a buffered, pre-recording row (see that function's doc for how), which shifts the whole
 * log hours away from the video. Returns the recomputed anchor when [descriptor]'s anchor points at
 * a row whose index `elapsedMs` precedes the video's start, and null when the anchor is fine or
 * cannot be checked.
 *
 * Only an in-place finalized capture folder can be checked: it still carries the recorder's own
 * `mapping/capture-index.jsonl` (per-row host `elapsedMs`) next to the raw log and video, which a
 * snapshot `.zip` export never includes — that one is left exactly as written. The descriptor on
 * disk is not rewritten; the correction lives in the returned [ImportedCapture] only, and any
 * failure to read the index degrades to "keep the anchor as is".
 */
private fun recomputeStaleInPlaceSyncAnchor(
    root: File,
    descriptor: CaptureArchiveDescriptor,
    logFile: File,
): CorrectedSyncAnchor? {
    val anchor = descriptor.syncAnchor ?: return null
    val videoStart = descriptor.videoStartMs ?: return null
    if (descriptor.log.path != IN_PLACE_LOG_PATH || descriptor.video?.path != IN_PLACE_VIDEO_PATH) return null
    val indexFile = File(root, IN_PLACE_INDEX_PATH)
    if (!indexFile.isFile || Files.isSymbolicLink(indexFile.toPath()) || indexFile.length() > MAX_MAPPING_BYTES) return null
    return runCatching {
        var anchorElapsedMs: Long? = null
        forEachSelectedRecord(indexFile) { record ->
            if (record.rowOrdinal == anchor.row) anchorElapsedMs = record.elapsedMs
        }
        val elapsed = anchorElapsedMs ?: return@runCatching null
        if (elapsed >= videoStart) return@runCatching null
        // videoStartMs is already `videoStartElapsedMs - manualOffsetMs` (see finalizeSessionInPlace),
        // so a zero manual offset here reproduces the session's own `elapsed - videoStartMs` math.
        CorrectedSyncAnchor(
            computeCaptureSyncAnchor(logFile, indexFile, videoStart, 0L, clip = null, includeRawVideo = true),
        )
    }.getOrNull()
}

private const val IN_PLACE_LOG_PATH = "logs/logcat.log"
private const val IN_PLACE_VIDEO_PATH = "video/screen.mkv"
private const val IN_PLACE_INDEX_PATH = "mapping/capture-index.jsonl"

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

private data class ZipEntryScan(
    val descriptorEntry: org.apache.commons.compress.archivers.zip.ZipArchiveEntry?,
    // Every non-directory, non-descriptor, non-blurb entry's OWN declared uncompressed size, keyed
    // by its normalized name (ZipFile — random-access mode, unlike a streaming ZipInputStream —
    // always knows this from the central directory without reading entry bytes). Used for
    // [requireExtractionSpace]'s preflight and the screenshots-combined limit instead of any
    // per-asset declared size, which v3's own JSON no longer carries — see [CaptureArchiveAsset]'s
    // own doc.
    val entrySizes: Map<String, Long>,
)

private fun scanZipEntries(zip: ZipFile): ZipEntryScan {
    val names = HashSet<String>()
    val entrySizes = HashMap<String, Long>()
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
            when (name) {
                CAPTURE_DESCRIPTOR_NAME -> descriptor = entry
                CAPTURED_WITH_INDAGIUM_NAME -> Unit
                else -> entrySizes[name] = entry.size.coerceAtLeast(0L)
            }
        }
    }
    return ZipEntryScan(descriptor, entrySizes)
}

private data class FrozenScreenshot(val source: File, val length: Long, val path: String)

private fun snapshotScreenshots(
    session: CaptureSession,
    startElapsedMs: Long,
    endElapsedMs: Long,
    timelineBaseElapsedMs: Long = session.syncMetadataOverride?.timelineBaseElapsedMs ?: 0L,
): List<FrozenScreenshot> {
    val candidates: List<Pair<File, Long>> = session.screenshotsOverride?.map { it.file to it.elapsedMs }
        ?.sortedWith(compareBy({ it.second }, { it.first.name }))
        ?: run {
            val directory = File(session.directory, "screenshots")
            if (!directory.isDirectory) return emptyList()
            directory.walkTopDown().mapNotNull { file ->
                SCREENSHOT_NAME.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()?.let { file to it }
            }.toList()
        }
    var index = 0
    return candidates.filter { (file, elapsed) ->
        file.isFile && !Files.isSymbolicLink(file.toPath()) && elapsed in startElapsedMs..endElapsedMs
    }.map { (file, elapsed) ->
        checkNotInterrupted()
        require(file.length() <= MAX_SCREENSHOT_BYTES) { "Capture screenshot exceeds the size limit: ${file.name}" }
        val extension = file.extension.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }?.lowercase(Locale.ROOT)
        // The name is rebuilt from the elapsed time on every export (never from the previous archive's
        // entry name) so re-exporting an imported capture does not accumulate index prefixes.
        val exportedElapsed = runCatching { Math.addExact(timelineBaseElapsedMs, elapsed) }.getOrDefault(elapsed)
        val name = if (session.screenshotsOverride != null) "screenshot-$exportedElapsed"
        else sanitizeFilename(file.nameWithoutExtension).ifBlank { "screenshot" }
        val suffix = if (extension == null) "" else ".$extension"
        FrozenScreenshot(file, file.length(), "screenshots/${++index}-${name.take(MAX_SCREENSHOT_NAME_LENGTH)}$suffix")
    }
}

private fun stageScreenshots(screenshots: List<FrozenScreenshot>, work: File): List<Pair<String, File>> = screenshots.map { shot ->
    val target = resolveSafe(work, shot.path)
    target.parentFile?.mkdirs()
    shot.source.inputStream().use { input -> target.outputStream().use { output -> copyExactly(input, output, shot.length) } }
    require(shot.source.length() >= shot.length) { "Capture screenshot changed during export: ${shot.source.name}" }
    shot.path to target
}

private val SCREENSHOT_NAME = Regex("screenshot-(\\d+)\\.[A-Za-z0-9]{1,8}")

/**
 * Writes `notes.ann` (gated on [CaptureSettings.markerNotesInSnapshot]) and computes each marker's
 * clipped [CaptureArchiveMarker] alongside it. Deliberately does NOT rewrite the ordinal numbering
 * on [notes]' blocks before serializing them — the shipped notes.ann keeps every marker's
 * Note/LogRef exactly as the live tab had them (ORIGINAL, session-wide ordinals and all), and it's
 * [markers] (this function's second return value) that carries the export-local translation. Import-
 * side reconciliation — dropping an out-of-range marker's jump, clipping a partially-covered one,
 * and clearing stale sourceEntries — all happens once, at re-open time, in
 * [reanchorImportedCaptureNotes]; duplicating that logic here (to also produce "clean" shipped
 * bytes) would just be two places for the same rule to drift apart.
 *
 * [AnnBlock.Image] video frames ARE rewritten here, though — see [rewriteExportedVideoFrames].
 * [clip] is the effective clip this export actually staged ([export]'s own `effectiveClip`, null
 * when no video was included), and [videoEntryName] is that clip's archive entry name (e.g.
 * `"screen.mp4"`), also null when no video was included.
 */
private fun stageNotes(
    work: File,
    session: CaptureSession,
    notes: Annotations?,
    selectionIndexFile: File?,
    clip: CaptureVideoClip?,
    videoEntryName: String?,
    sparseMarkers: List<CaptureArchiveMarker>? = null,
): Pair<CaptureArchiveAsset?, List<CaptureArchiveMarker>> {
    if (!session.settings.markerNotesInSnapshot || notes == null) return null to emptyList()
    val markers = notes.blocks.filterIsInstance<AnnBlock.Note>()
        .mapNotNull { note -> parseMarkerHeader(note.text)?.copy(noteBlockId = note.id) }
    val clipped = sparseMarkers ?: clipMarkersForExport(markers, requireNotNull(selectionIndexFile))
    val rewritten = rewriteExportedVideoFrames(notes, session, clip, videoEntryName)
    val target = File(work, "notes.ann")
    target.writeText(rewritten.annotationsToken(), Charsets.UTF_8)
    return asset("notes.ann", target) to clipped
}

internal data class LegacySparseAdapter(val session: CaptureSession, val directory: File)

private data class SparseExportStage(
    val logFile: File,
    val blockIndexFile: File,
    val syncFile: File,
    val metadata: CaptureSyncMetadata,
    val recordCount: Long,
    val rowCount: Int,
    val logBytes: Long,
    val firstElapsedMs: Long?,
    val lastElapsedMs: Long?,
    val uncertainBoundary: Boolean,
    val markerClips: List<CaptureArchiveMarker>,
    val sourceEndByteOffset: Long?,
    val sourceEndRowOrdinal: Int?,
)

private data class SparseCandidateLine(
    val raw: ByteArray,
    val sourceByteOffset: Long,
    val rowOrdinal: Int?,
    val block: CaptureBlock,
    val entry: com.indagium.model.LogEntry?,
    val point: CaptureTimingPoint?,
)

private data class SparseScanContext(
    val session: CaptureSession,
    val metadata: CaptureSyncMetadata,
    val timing: CaptureTimeline,
    val blockIndex: CaptureBlockIndex,
    val frozenBlockBytes: Long,
    val frozenLogBytes: Long,
    /** Export always verifies each block's SHA-256 before trusting its bytes; the preview skips it (it never publishes them). */
    val verifyBlocks: Boolean = true,
)

private data class SparseLineFilter(
    val selectedStartMs: Long,
    val selectedEndMs: Long,
    val selectedFirstRowOrdinal: Int? = null,
    val selectedLastRowOrdinal: Int? = null,
    val sourceCursorByteOffset: Long? = null,
    val sourceCursorRowOrdinal: Int? = null,
)

private data class SparseStageRequest(
    val scan: SparseScanContext,
    val filter: SparseLineFilter,
    val excludeStart: Boolean,
    val timelineBaseLocalMs: Long,
    val markerRanges: List<CaptureMarker>,
    val work: File,
)

private class SparseCursorPrefix(private val endElapsedMs: Long) {
    private var ended = false
    val isEnded: Boolean get() = ended

    fun includes(candidate: SparseCandidateLine): Boolean {
        if (ended) return false
        val elapsed = candidate.point?.captureElapsedMs
        val withinCutoff = elapsed?.let { it <= endElapsedMs } ?: (candidate.block.firstElapsedMs <= endElapsedMs)
        if (!withinCutoff) ended = true
        return withinCutoff
    }
}

/** Converts an old retained per-row index into a temporary sparse index without creating another
 *  per-row cache. The old per-row elapsed values are used only as transient estimator input; the
 *  resulting portable metadata keeps one compact anchor per continuous clock segment. */
internal fun buildLegacySparseAdapter(session: CaptureSession, parent: File): LegacySparseAdapter {
    require(session.logFile.isFile && session.indexFile.isFile) { "Legacy capture log or index is missing" }
    val directory = Files.createTempDirectory(parent.toPath(), LEGACY_ADAPTER_DIRECTORY_PREFIX).toFile()
    registerLegacyAdapterDirectory(directory)
    val blockFile = File(directory, "mapping/blocks.jsonl")
    val syncFile = File(directory, "timeline/sync.json")
    val tracker = CaptureSyncRecorder(session.startedEpochMs)
    val checkpointMs = session.effectiveSnapshotCheckpointMs
    val indexBytes = session.frozenBlockIndexBytes ?: session.indexFile.length()
    val logBytes = session.frozenLogBytes ?: session.logFile.length()
    require(indexBytes in 0..session.indexFile.length()) { "Legacy capture index is shorter than its frozen boundary" }
    require(logBytes in 0..session.logFile.length()) { "Legacy capture log is shorter than its frozen boundary" }
    var completed = false
    try {
        val conversion = convertLegacySparseIndex(session, blockFile, indexBytes, logBytes, checkpointMs, tracker)
        val rowCount = conversion.rowCount
        val tracked = tracker.snapshot(
            logBytes = logBytes,
            rowCount = rowCount,
            elapsedMs = maxOf(session.elapsedMs, conversion.lastElapsedMs),
            finalize = true,
        )
        val metadata = legacyConversionMetadata(tracked, conversion.driftedSegments)
        syncFile.parentFile?.mkdirs()
        syncFile.writeText(metadata.toJson(), Charsets.UTF_8)
        val adapter = LegacySparseAdapter(
            session.copy(
                storageVersion = CaptureStorageVersion.BLOCKS_V2,
                blockIndexFileOverride = blockFile,
                syncMetadataOverride = metadata,
                manualSegmentAnchors = emptyList(),
                frozenLogBytes = logBytes,
                frozenBlockIndexBytes = blockFile.length(),
                logCheckpointByteOffset = session.logCheckpointByteOffset.takeIf { it >= 0L }
                    ?: conversion.inferredCursorByteOffset,
                logCheckpointRowOrdinal = if (session.logCheckpointByteOffset >= 0L) {
                    session.logCheckpointRowOrdinal
                } else {
                    conversion.inferredCursorRowOrdinal
                },
            ),
            directory,
        )
        completed = true
        return adapter
    } finally {
        if (!completed) releaseLegacyAdapterDirectory(directory)
    }
}

private data class LegacySparseConversion(
    val rowCount: Int,
    val lastElapsedMs: Long,
    val inferredCursorByteOffset: Long,
    val inferredCursorRowOrdinal: Int,
    val driftedSegments: Set<Int>,
)

private class LegacySparseConversionState(
    private val session: CaptureSession,
    private val checkpointMs: Long,
    private val tracker: CaptureSyncRecorder,
) {
    var expectedByte = 0L
        private set
    var nextRow = 1
        private set
    var lastElapsedMs = 0L
        private set
    var inferredCursorByteOffset = -1L
        private set
    var inferredCursorRowOrdinal = 0
        private set
    private var crossedLegacyCheckpoint = false
    val driftedSegments = HashSet<Int>()

    fun append(record: CaptureLogIndexRecord, log: RandomAccessFile, writer: CaptureBlockWriter, logBytes: Long) {
        validateLegacyIndexRecord(record, expectedByte, nextRow, logBytes)
        val raw = ByteArray(record.byteLength)
        log.seek(record.byteOffset)
        log.readFully(raw)
        observeLegacyIndexRecord(record, raw, writer)
        updateLegacyCursor(record)
        lastElapsedMs = maxOf(lastElapsedMs, record.elapsedMs)
        expectedByte += record.byteLength
    }

    private fun observeLegacyIndexRecord(record: CaptureLogIndexRecord, raw: ByteArray, writer: CaptureBlockWriter) {
        val text = raw.toString(Charsets.UTF_8)
        val row = record.rowOrdinal
        val parsed = row?.let { parseLogcatLines(sequenceOf(text), startId = it).singleOrNull() }
        val synthetic = text.contains("IndagiumCapture:")
        val segmentId = if (row != null) {
            tracker.observe(row, text, record.elapsedMs, eligibleForAnchor = parsed != null && !synthetic)
        } else {
            (nextRow - 1).takeIf { it > 0 }?.let(tracker::segmentIdForRow)
        }
        val timing = tracker.rowTiming(text, record.elapsedMs, resolvable = row != null && parsed != null && !synthetic)
        val resolved = timing.resolvedElapsedMs
        if (resolved != null && segmentId != null && captureTimingsDiffer(resolved, record.elapsedMs)) {
            driftedSegments += segmentId
        }
        writer.append(record.byteOffset, raw, row, record.elapsedMs, segmentId, resolved, envelopeSettled = timing.settled)
        if (row != null) nextRow++
    }

    private fun updateLegacyCursor(record: CaptureLogIndexRecord) {
        if (session.logCheckpointByteOffset >= 0L || checkpointMs < 0L) return
        if (!crossedLegacyCheckpoint && record.elapsedMs <= checkpointMs) {
            inferredCursorByteOffset = record.byteOffset + record.byteLength
            inferredCursorRowOrdinal = nextRow - 1
        } else if (record.elapsedMs > checkpointMs) {
            crossedLegacyCheckpoint = true
        }
    }

    fun result(): LegacySparseConversion = LegacySparseConversion(
        rowCount = nextRow - 1,
        lastElapsedMs = lastElapsedMs,
        inferredCursorByteOffset = inferredCursorByteOffset,
        inferredCursorRowOrdinal = inferredCursorRowOrdinal,
        driftedSegments = driftedSegments,
    )
}

private fun convertLegacySparseIndex(
    session: CaptureSession,
    blockFile: File,
    indexBytes: Long,
    logBytes: Long,
    checkpointMs: Long,
    tracker: CaptureSyncRecorder,
): LegacySparseConversion {
    val state = LegacySparseConversionState(session, checkpointMs, tracker)
    RandomAccessFile(session.logFile, "r").use { log ->
        CaptureBlockWriter(blockFile).use { writer ->
            session.indexFile.inputStream().use { base ->
                val input = LimitedInputStream(BufferedInputStream(base), indexBytes)
                forEachCompleteLine(input, MAX_MAPPING_LINE_BYTES, rejectTrailingPartial = true) { line ->
                    checkNotInterrupted()
                    state.append(parseIndexRecord(line), log, writer, logBytes)
                }
            }
            writer.freeze()
        }
    }
    require(state.expectedByte == logBytes) { "Legacy capture index does not cover the complete log" }
    return state.result()
}

private fun validateLegacyIndexRecord(record: CaptureLogIndexRecord, expectedByte: Long, nextRow: Int, logBytes: Long) {
    require(record.byteOffset == expectedByte && record.byteLength > 0) {
        "Legacy capture index has a gap or invalid byte range"
    }
    require(record.byteOffset <= logBytes - record.byteLength) { "Legacy capture index exceeds the log boundary" }
    require(record.rowOrdinal == null || record.rowOrdinal == nextRow) { "Legacy capture row bounds are invalid" }
}

private fun legacyConversionMetadata(
    tracked: CaptureSyncMetadata,
    driftedSegments: Set<Int>,
): CaptureSyncMetadata = if (driftedSegments.isEmpty()) {
    tracked
} else {
    tracked.copy(
        segments = tracked.segments.map { segment ->
            if (segment.id in driftedSegments) segment.copy(reliability = CaptureSyncReliability.UNCERTAIN) else segment
        },
        uncertainBoundary = true,
    )
}

private fun mergeManualAnchors(
    metadata: CaptureSyncMetadata,
    anchors: List<CaptureSegmentManualAnchor>,
): CaptureSyncMetadata {
    val unique = LinkedHashMap<Int, CaptureSegmentManualAnchor>()
    anchors.forEach { unique[it.segmentId] = it }
    require(unique.keys.all { id -> metadata.segments.any { it.id == id } }) {
        "Manual capture anchor references an unknown sync segment"
    }
    if (unique.isEmpty()) return metadata
    return metadata.copy(segments = metadata.segments.map { segment ->
        unique[segment.id]?.let { segment.copy(manualAnchor = it) } ?: segment
    })
}

private fun sparseTimelineForSession(session: CaptureSession, metadata: CaptureSyncMetadata): CaptureTimeline = CaptureTimeline(
    rows = emptyList(),
    quality = if (metadata.segments.any { it.reliability == CaptureSyncReliability.RELIABLE }) "estimated" else "uncertain",
    manualOffsetMs = 0L,
    availabilityIntervals = session.availabilityIntervals,
    timelineBaseElapsedMs = metadata.timelineBaseElapsedMs,
    timelineDurationMs = metadata.timelineDurationMs,
    videoOriginElapsedMs = session.videoStartElapsedMs?.let {
        runCatching { Math.addExact(it, metadata.timelineBaseElapsedMs) }.getOrNull()?.minus(session.manualOffsetMs)
    },
    sparseTiming = true,
    sourceStartedEpochMs = session.startedEpochMs,
)

private fun findSparseSelectionBounds(
    session: CaptureSession,
    metadata: CaptureSyncMetadata,
    timing: CaptureTimeline,
    blockIndex: CaptureBlockIndex,
    frozenBlockBytes: Long,
    frozenLogBytes: Long,
    firstOrdinal: Int,
    lastOrdinal: Int,
    verifyBlocks: Boolean = true,
): SelectionBounds {
    var first: Long? = null
    var last: Long? = null
    var firstVideo: Long? = null
    var lastVideo: Long? = null
    forEachSparseCandidateLine(
        SparseScanContext(session, metadata, timing, blockIndex, frozenBlockBytes, frozenLogBytes, verifyBlocks),
        SparseLineFilter(Long.MIN_VALUE, Long.MAX_VALUE, firstOrdinal, lastOrdinal),
    ) { candidate ->
        val ordinal = candidate.rowOrdinal ?: return@forEachSparseCandidateLine
        if (ordinal !in firstOrdinal..lastOrdinal) return@forEachSparseCandidateLine
        val elapsed = candidate.point?.captureElapsedMs ?: candidate.block.firstElapsedMs
        first = first?.let { minOf(it, elapsed) } ?: elapsed
        last = last?.let { maxOf(it, elapsed) } ?: elapsed
        candidate.point?.videoMs?.let { videoMs ->
            firstVideo = firstVideo?.let { minOf(it, videoMs) } ?: videoMs
            lastVideo = lastVideo?.let { maxOf(it, videoMs) } ?: videoMs
        }
    }
    return SelectionBounds(
        requireNotNull(first) { "The first selected capture row does not exist" },
        requireNotNull(last) { "The last selected capture row does not exist" },
        firstVideo,
        lastVideo,
    )
}

/** The range part of a bounds query: what [findSparseRangeBounds] selects from the scanned blocks. */
private data class SparseRangeQuery(
    val selectedStartMs: Long,
    val selectedEndMs: Long,
    val excludeStart: Boolean,
    val sourceCursorByteOffset: Long?,
    val sourceCursorRowOrdinal: Int?,
)

private fun findSparseRangeBounds(scan: SparseScanContext, query: SparseRangeQuery, previewEnvelope: Boolean = false): SelectionBounds? {
    val bounds = SparseBoundsAccumulator()
    val filter = SparseLineFilter(
        query.selectedStartMs,
        query.selectedEndMs,
        sourceCursorByteOffset = query.sourceCursorByteOffset,
        sourceCursorRowOrdinal = query.sourceCursorRowOrdinal,
    )
    val cursorPrefix = query.sourceCursorByteOffset?.let { SparseCursorPrefix(query.selectedEndMs) }
    // Preview only: an interior block of one reliable clock segment adds its recorded envelope instead of being read.
    // Its rows' resolved times sit within milliseconds of it (the rows' own log clock, mapped through the segment anchor),
    // and the first and last candidate blocks, where buffered or boundary rows can differ more, are always read.
    val envelope: ((CaptureBlock, Boolean) -> Boolean)? = if (previewEnvelope && cursorPrefix == null) {
        { block, isEdge ->
            val interior = !isEdge && sparseBlockUsesEnvelope(
                scan.metadata, block, query.selectedStartMs, query.selectedEndMs, query.excludeStart,
            )
            if (interior) bounds.addElapsed(block.firstElapsedMs, block.lastElapsedMs)
            interior
        }
    } else {
        null
    }
    forEachSparseCandidateLine(
        scan,
        filter,
        stopAfterCurrentBlock = { cursorPrefix?.isEnded == true },
        envelope = envelope,
    ) { candidate ->
        if (cursorPrefix?.includes(candidate) == false) return@forEachSparseCandidateLine
        if (!sparseCandidateMatchesRange(candidate, filter, query.excludeStart)) return@forEachSparseCandidateLine
        val resolved = candidate.point?.captureElapsedMs ?: candidate.block.firstElapsedMs
        bounds.addElapsed(resolved, resolved)
        candidate.point?.videoMs?.let(bounds::addVideo)
    }
    return bounds.toBounds()
}

/** Running minimum and maximum of the elapsed (and video) times of the selected rows. */
private class SparseBoundsAccumulator {
    private var first: Long? = null
    private var last: Long? = null
    private var firstVideo: Long? = null
    private var lastVideo: Long? = null

    fun addElapsed(low: Long, high: Long) {
        first = first?.let { minOf(it, low) } ?: low
        last = last?.let { maxOf(it, high) } ?: high
    }

    fun addVideo(videoMs: Long) {
        firstVideo = firstVideo?.let { minOf(it, videoMs) } ?: videoMs
        lastVideo = lastVideo?.let { maxOf(it, videoMs) } ?: videoMs
    }

    fun toBounds(): SelectionBounds? = first?.let { SelectionBounds(it, requireNotNull(last), firstVideo, lastVideo) }
}

private fun sparseCandidateMatchesRange(
    candidate: SparseCandidateLine,
    filter: SparseLineFilter,
    excludeStart: Boolean,
): Boolean {
    if (candidate.rowOrdinal == null) return false
    val elapsed = candidate.point?.captureElapsedMs
    if (filter.sourceCursorByteOffset != null) return true
    if (elapsed != null && candidate.point?.reliable == true) {
        val afterStart = if (excludeStart) elapsed > filter.selectedStartMs else elapsed >= filter.selectedStartMs
        return afterStart && elapsed <= filter.selectedEndMs
    }
    return sparseBlockOverlapsRange(candidate.block, filter.selectedStartMs, filter.selectedEndMs, excludeStart)
}

private fun sparseBlockOverlapsRange(
    block: CaptureBlock,
    selectedStartMs: Long,
    selectedEndMs: Long,
    excludeStart: Boolean,
): Boolean {
    val start = selectedStartMs.coerceAtLeast(0L)
    val startsBeforeEnd = block.firstElapsedMs <= selectedEndMs
    val extendsAfterStart = if (excludeStart) block.lastElapsedMs > start else block.lastElapsedMs >= start
    return startsBeforeEnd && extendsAfterStart
}

private fun forEachSparseCandidateLine(
    scan: SparseScanContext,
    filter: SparseLineFilter,
    stopAfterCurrentBlock: () -> Boolean = { false },
    // Preview only: (block, isFirstOrLastCandidate) -> true when the block was accounted for without being read.
    envelope: ((CaptureBlock, Boolean) -> Boolean)? = null,
    block: (SparseCandidateLine) -> Unit,
) {
    val candidates = sparseCandidateBlocks(
        scan.blockIndex,
        scan.metadata,
        filter.selectedStartMs,
        filter.selectedEndMs,
        filter.selectedFirstRowOrdinal,
        filter.selectedLastRowOrdinal,
        filter.sourceCursorByteOffset,
    )
    requireSparseCandidates(scan.metadata, filter, candidates)
    var expectedBlockStart: Long? = null
    for ((position, candidateBlock) in candidates.withIndex()) {
        if (stopAfterCurrentBlock()) break
        checkNotInterrupted()
        if (envelope?.invoke(candidateBlock, position == 0 || position == candidates.lastIndex) == true) {
            expectedBlockStart = candidateBlock.byteEnd
            continue
        }
        val bytes = readSparseCandidateBlock(scan, candidateBlock, expectedBlockStart)
        SparseCandidateBlockScanner(scan, filter, candidateBlock, bytes, block).scan()
        expectedBlockStart = candidateBlock.byteEnd
    }
}

private fun requireSparseCandidates(
    metadata: CaptureSyncMetadata,
    filter: SparseLineFilter,
    candidates: List<CaptureBlock>,
) {
    val cursorAtEnd = filter.sourceCursorByteOffset != null && filter.sourceCursorByteOffset == metadata.logBytes
    val emptyIsExpected = metadata.rowCount == 0 || cursorAtEnd || filter.sourceCursorByteOffset != null
    require(candidates.isNotEmpty() || emptyIsExpected) { "No indexed capture blocks overlap the requested range" }
}

private fun readSparseCandidateBlock(
    scan: SparseScanContext,
    candidateBlock: CaptureBlock,
    expectedStart: Long?,
): ByteArray {
    require(candidateBlock.byteEnd <= scan.frozenLogBytes && candidateBlock.byteEnd > candidateBlock.byteStart) {
        "Capture block exceeds the frozen log boundary"
    }
    CapturePreviewDiagnostics.blockReads.incrementAndGet()
    if (scan.verifyBlocks) CaptureBlockIndex.verifyBlock(scan.session.logFile, candidateBlock)
    require(expectedStart == null || candidateBlock.byteStart >= expectedStart) {
        "Capture candidate blocks are out of order"
    }
    require(candidateBlock.byteEnd <= scan.session.logFile.length() && scan.frozenBlockBytes >= 0L) {
        "Capture block is outside the frozen source"
    }
    val blockLength = candidateBlock.byteEnd - candidateBlock.byteStart
    require(blockLength <= MAX_ARCHIVE_ENTRY_BYTES && blockLength <= Int.MAX_VALUE) {
        "Capture block exceeds the supported scan size"
    }
    return ByteArray(blockLength.toInt()).also { bytes ->
        RandomAccessFile(scan.session.logFile, "r").use { input ->
            input.seek(candidateBlock.byteStart)
            input.readFully(bytes)
        }
    }
}

private class SparseCandidateBlockScanner(
    private val scan: SparseScanContext,
    private val filter: SparseLineFilter,
    private val candidateBlock: CaptureBlock,
    private val bytes: ByteArray,
    private val consume: (SparseCandidateLine) -> Unit,
) {
    private var rowOrdinal = candidateBlock.firstRow
    private var lineStart = 0

    fun scan() {
        for (index in bytes.indices) {
            if (bytes[index] == '\n'.code.toByte()) emitLine(index + 1)
        }
        if (lineStart < bytes.size) {
            require(candidateBlock.byteEnd == scan.frozenLogBytes) { "Capture block ends with a partial raw log line" }
            emitLine(bytes.size)
        }
        require(lineStart == bytes.size) { "Capture block ends with a partial raw log line" }
        require(rowOrdinal == candidateBlock.lastRowExclusive) {
            "Capture block row bounds do not match its raw log bytes"
        }
    }

    private fun emitLine(endExclusive: Int) {
        val raw = bytes.copyOfRange(lineStart, endExclusive)
        val sourceByteOffset = candidateBlock.byteStart + lineStart
        val text = raw.toString(Charsets.UTF_8)
        val currentOrdinal = nextRowOrdinal(text)
        val entry = currentOrdinal?.let { parseLogcatLines(sequenceOf(text)).singleOrNull() }
        val point = if (entry != null && currentOrdinal != null) {
            sparseCaptureTimingPoint(entry, currentOrdinal, scan.metadata, scan.timing)
        } else {
            null
        }
        if (lineIsAfterCursor(sourceByteOffset, currentOrdinal)) {
            consume(SparseCandidateLine(raw, sourceByteOffset, currentOrdinal, candidateBlock, entry, point))
        }
        lineStart = endExclusive
    }

    private fun nextRowOrdinal(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("-----")) return null
        return rowOrdinal++
    }

    private fun lineIsAfterCursor(sourceByteOffset: Long, currentOrdinal: Int?): Boolean {
        val cursor = filter.sourceCursorByteOffset ?: return true
        if (sourceByteOffset < cursor) return false
        val cursorRow = filter.sourceCursorRowOrdinal ?: return true
        return currentOrdinal == null || currentOrdinal > cursorRow
    }
}

private fun sparseCandidateBlocks(
    blockIndex: CaptureBlockIndex,
    metadata: CaptureSyncMetadata,
    selectedStartMs: Long,
    selectedEndMs: Long,
    selectedFirstRowOrdinal: Int?,
    selectedLastRowOrdinal: Int?,
    sourceCursorByteOffset: Long?,
): List<CaptureBlock> = when {
    selectedFirstRowOrdinal != null && selectedLastRowOrdinal != null ->
        blockIndex.candidateBlocksForRows(selectedFirstRowOrdinal, selectedLastRowOrdinal + 1)
    // A block can span clock segments, and manual corrections can move its rows before its
    // original time envelope. The shared cursor-prefix state stops at the first future row.
    sourceCursorByteOffset != null -> blockIndex.blocks.filter { it.byteEnd > sourceCursorByteOffset }
    selectedStartMs == Long.MIN_VALUE -> blockIndex.blocks
    // A block's recorded envelope only brackets its rows when they were resolved through their segment's final anchor
    // (a row logged before the anchor existed, or re-timed by a manual correction, can resolve outside it), so blocks
    // whose envelope cannot be trusted are always scanned and the per-row filter decides.
    else -> blockIndex.candidateBlocksForResolvedElapsed(metadata, selectedStartMs, selectedEndMs)
}

private fun stageSparseSelection(request: SparseStageRequest): SparseExportStage {
    val scan = request.scan
    val logFile = File(request.work, "logcat.log")
    val outputBlocks = File(request.work, "mapping/blocks.jsonl")
    val syncFile = File(request.work, "timeline/sync.json")
    outputBlocks.parentFile?.mkdirs()
    val accumulator = SparseStageAccumulator(scan.metadata, request)
    val writer = CaptureBlockWriter(outputBlocks)
    try {
        logFile.outputStream().buffered().use { logOutput ->
            forEachSparseCandidateLine(
                scan,
                request.filter,
                stopAfterCurrentBlock = { accumulator.cursorPrefixEnded },
            ) { candidate ->
                accumulator.append(candidate, logOutput, writer)
            }
        }
        val blockSnapshot = writer.freeze()
        require(blockSnapshot.indexedLogBytes == accumulator.outputBytes && blockSnapshot.indexedRows == accumulator.outputRows) {
            "Sparse export block index does not cover the staged log"
        }
    } finally {
        writer.close()
    }
    val outputMetadata = accumulator.toMetadata()
    syncFile.parentFile?.mkdirs()
    syncFile.writeText(outputMetadata.toJson(), Charsets.UTF_8)
    return SparseExportStage(
        logFile,
        outputBlocks,
        syncFile,
        outputMetadata,
        accumulator.outputRecords,
        accumulator.outputRows,
        accumulator.outputBytes,
        accumulator.firstElapsed,
        accumulator.lastElapsed,
        accumulator.uncertainBoundary,
        accumulator.markerClips(),
        accumulator.sourceEndByteOffset,
        accumulator.sourceEndRowOrdinal,
    )
}

private class SparseStageAccumulator(
    private val metadata: CaptureSyncMetadata,
    private val request: SparseStageRequest,
) {
    private val markerBounds = LinkedHashMap<String, Pair<Int, Int>>()
    private val outputSegments = ArrayList<CaptureSyncSegment>()
    private val selectedMarkers = request.markerRanges.mapNotNull { marker ->
        val noteId = marker.noteBlockId ?: return@mapNotNull null
        val first = marker.firstOrdinal ?: return@mapNotNull null
        val last = marker.lastOrdinal ?: return@mapNotNull null
        Triple(noteId, first, last)
    }
    private val cursorPrefix = request.filter.sourceCursorByteOffset?.let {
        SparseCursorPrefix(request.filter.selectedEndMs)
    }
    val cursorPrefixEnded: Boolean get() = cursorPrefix?.isEnded == true
    private var selectionStarted = request.filter.selectedFirstRowOrdinal == null
    private var selectionEnded = false
    var outputRows = 0
        private set
    var outputRecords = 0L
        private set
    var outputBytes = 0L
        private set
    var uncertainBoundary = metadata.uncertainBoundary
        private set
    var firstElapsed: Long? = null
        private set
    var lastElapsed: Long? = null
        private set
    var sourceEndByteOffset: Long? = null
        private set
    var sourceEndRowOrdinal: Int? = null
        private set
    private var maximumOutputElapsedMs = 0L

    fun append(candidate: SparseCandidateLine, logOutput: java.io.OutputStream, writer: CaptureBlockWriter) {
        if (!shouldSelect(candidate)) return
        require(outputBytes <= MAX_ARCHIVE_ENTRY_BYTES - candidate.raw.size) {
            "Selected capture log exceeds the size limit"
        }
        candidate.rowOrdinal?.let { recordSelectedRow(it, candidate) }
        val sourceElapsed = candidate.point?.captureElapsedMs ?: candidate.block.firstElapsedMs
        val outputElapsed = rebaseElapsed(sourceElapsed)
        val outputBlockFirstElapsed = minOf(rebaseElapsed(candidate.block.firstElapsedMs), outputElapsed)
        val outputBlockLastElapsed = maxOf(rebaseElapsed(candidate.block.lastElapsedMs), outputElapsed)
        maximumOutputElapsedMs = maxOf(maximumOutputElapsedMs, outputElapsed)
        logOutput.write(candidate.raw)
        writer.append(
            byteOffset = outputBytes,
            rawLine = candidate.raw,
            rowOrdinal = candidate.rowOrdinal?.let { outputRows },
            elapsedMs = outputBlockFirstElapsed,
            segmentId = candidate.rowOrdinal?.let { row -> metadata.segmentContaining(row)?.id },
            resolvedCaptureElapsedMs = outputBlockLastElapsed,
        )
        outputBytes += candidate.raw.size
        outputRecords++
        sourceEndByteOffset = maxOf(sourceEndByteOffset ?: 0L, candidate.sourceByteOffset + candidate.raw.size)
    }

    private fun shouldSelect(candidate: SparseCandidateLine): Boolean {
        val filter = request.filter
        cursorPrefix?.let { return it.includes(candidate) }
        val first = filter.selectedFirstRowOrdinal
        val last = filter.selectedLastRowOrdinal
        if (first != null && last != null) return isSelectedOrdinal(candidate.rowOrdinal, first, last)
        val row = candidate.rowOrdinal ?: return sparseBlockOverlapsRange(
            candidate.block,
            filter.selectedStartMs,
            filter.selectedEndMs,
            request.excludeStart,
        )
        val elapsed = candidate.point?.captureElapsedMs
        if (elapsed == null) {
            uncertainBoundary = true
            return sparseBlockOverlapsRange(
                candidate.block,
                filter.selectedStartMs,
                filter.selectedEndMs,
                request.excludeStart,
            )
        }
        val afterStart = if (request.excludeStart) elapsed > filter.selectedStartMs else elapsed >= filter.selectedStartMs
        return afterStart && elapsed <= filter.selectedEndMs
    }

    private fun isSelectedOrdinal(rowOrdinal: Int?, first: Int, last: Int): Boolean = when {
        selectionEnded -> false
        rowOrdinal == null -> selectionStarted
        rowOrdinal < first -> false
        rowOrdinal > last -> {
            selectionEnded = selectionStarted
            false
        }
        else -> {
            selectionStarted = true
            if (rowOrdinal == last) selectionEnded = true
            true
        }
    }

    private fun recordSelectedRow(row: Int, candidate: SparseCandidateLine) {
        require(outputRows < MAX_MAPPING_ROWS) { "Selected capture has too many rows" }
        outputRows++
        val sourceSegment = metadata.segmentContaining(row)
        if (sourceSegment == null) {
            uncertainBoundary = true
        } else {
            appendSegmentRow(sourceSegment)
        }
        if (candidate.point?.reliable != true) uncertainBoundary = true
        rememberMarkerRows(row)
        val elapsed = candidate.point?.captureElapsedMs ?: candidate.block.firstElapsedMs
        firstElapsed = firstElapsed?.let { minOf(it, elapsed) } ?: elapsed
        lastElapsed = lastElapsed?.let { maxOf(it, elapsed) } ?: elapsed
        sourceEndRowOrdinal = maxOf(sourceEndRowOrdinal ?: 0, row)
    }

    private fun appendSegmentRow(segment: CaptureSyncSegment) {
        val previous = outputSegments.lastOrNull()
        if (previous?.id == segment.id) {
            outputSegments[outputSegments.lastIndex] = previous.copy(lastRowExclusive = outputRows + 1)
        } else {
            outputSegments += segment.copy(firstRow = outputRows, lastRowExclusive = outputRows + 1)
        }
    }

    private fun rememberMarkerRows(row: Int) {
        selectedMarkers.forEach { (id, first, last) ->
            if (row in first..last) {
                val old = markerBounds[id]
                markerBounds[id] = if (old == null) outputRows to outputRows else old.first to outputRows
            }
        }
    }

    private fun rebaseElapsed(sourceElapsedMs: Long): Long = runCatching {
        Math.subtractExact(sourceElapsedMs, request.timelineBaseLocalMs)
    }.getOrDefault(0L).coerceAtLeast(0L)

    fun toMetadata(): CaptureSyncMetadata = CaptureSyncMetadata(
        logBytes = outputBytes,
        rowCount = outputRows,
        timelineBaseElapsedMs = Math.addExact(metadata.timelineBaseElapsedMs, request.timelineBaseLocalMs),
        timelineDurationMs = maxOf(
            (request.filter.selectedEndMs - request.timelineBaseLocalMs).coerceAtLeast(0L),
            maximumOutputElapsedMs,
        ),
        segments = outputSegments,
        uncertainBoundary = uncertainBoundary,
    )

    fun markerClips(): List<CaptureArchiveMarker> = markerBounds.mapNotNull { (id, ordinals) ->
        if (ordinals.first <= ordinals.second) CaptureArchiveMarker(id, ordinals.first, ordinals.second) else null
    }
}

/**
 * Bug fix: a marker screenshot's [com.indagium.model.VideoFrameReference] used to keep pointing at
 * the SESSION's own raw recording (`VideoSource.LocalFile(session.videoFile.absolutePath)`) at a
 * SOURCE-VIDEO-relative position (see AppState.attachMarkerScreenshot/screenshotCapture). After
 * Save + reopen, the tab's attached video is instead this export's own CLIP, starting at [clip]'s
 * `actualStartMs`, and identified by a durable [VideoSource.ArchiveEntry] rather than that same raw
 * file path — so [AppState.navigateToVideoFrame]'s exact-source-identity check always rejected the
 * old frame, and even a looser check would have seeked to the wrong offset (never shifted by the
 * clip's own start).
 *
 * Every [AnnBlock.Image] whose frame is from THIS session's own video is rewritten here to a
 * PORTABLE marker — `VideoSource.ArchiveEntry("", videoEntryName, videoEntryName)`, empty
 * `archivePath` meaning "this same archive" — with [VideoFrameReference.positionMs] rebased to be
 * relative to the clip's own start. [AppState.repointPortableVideoFrames] (called from
 * `openCaptureFile`/`offerCaptureNotesReimportIfNeeded` at import time) re-points that portable
 * marker at whatever source the reopened tab's attached video actually resolves to (an
 * `ArchiveEntry(zipPath, …)` for a `.zip`, or a `LocalFile` for an extracted directory). A frame
 * that falls outside what was actually exported — or no video was exported at all — is DETACHED
 * (kept as a plain image, `videoFrame = null`) instead of shipped with a stale or out-of-range
 * position: a missing seek link is a much smaller surprise than one that silently lands on the
 * wrong moment.
 */
private fun rewriteExportedVideoFrames(
    notes: Annotations,
    session: CaptureSession,
    clip: CaptureVideoClip?,
    videoEntryName: String?,
): Annotations {
    val sessionVideoSources = buildSet {
        add(VideoSource.LocalFile(session.videoFile.absolutePath))
        session.snapshotVideoSource?.let(::add)
    }
    var changed = false
    val rewrittenBlocks = notes.blocks.map { block ->
        if (block !is AnnBlock.Image) return@map block
        val frame = block.videoFrame ?: return@map block
        if (frame.source !in sessionVideoSources) return@map block
        changed = true
        val inClip = clip != null && frame.positionMs in clip.actualStartMs..clip.coveredEndMs
        if (videoEntryName == null || clip == null || !inClip) {
            block.copy(
                videoFrame = null,
                provenance = "${frame.sourceLabel} (video not included in this export)",
            )
        } else {
            val rewrittenFrame = frame.copy(
                source = VideoSource.ArchiveEntry(archivePath = "", entryPath = videoEntryName, displayName = videoEntryName),
                sourceLabel = "$CAPTURE_DESCRIPTOR_NAME/$videoEntryName",
                positionMs = frame.positionMs - clip.actualStartMs,
            )
            block.copy(videoFrame = rewrittenFrame, provenance = rewrittenFrame.provenanceLabel)
        }
    }
    return if (changed) notes.copy(blocks = rewrittenBlocks) else notes
}

/**
 * Computes each marker's EXPORT-LOCAL ordinal bounds — the numbering [forEachSelectedRecord] assigns
 * this export's own rows, which the reopened tab's own LogParser reproduces 1:1 (see
 * CaptureMarkerCodec's doc on why "ordinal" always means that) — by intersecting the marker's
 * ORIGINAL, session-wide [CaptureMarker.firstOrdinal]/[CaptureMarker.lastOrdinal] window against
 * exactly the rows this export kept. A marker with no intersection at all is simply absent from the
 * result. [selectionIndexFile] must be the SAME frozen, already-filtered index [computeCaptureSyncAnchor]
 * reads for this same export — this scans it with the identical local-ordinal counter (one
 * increment per row that carries a `rowOrdinal`) so the two can never disagree about what row N of
 * the exported log is.
 */
private fun clipMarkersForExport(markers: List<CaptureMarker>, selectionIndexFile: File): List<CaptureArchiveMarker> {
    val ranged = markers.filter { it.noteBlockId != null && it.firstOrdinal != null && it.lastOrdinal != null }
    if (ranged.isEmpty()) return emptyList()
    val clip = HashMap<String, IntRange>()
    var localOrdinal = 0
    forEachSelectedRecord(selectionIndexFile) { record ->
        val original = record.rowOrdinal ?: return@forEachSelectedRecord
        localOrdinal += 1
        ranged.forEach { marker ->
            val first = requireNotNull(marker.firstOrdinal)
            val last = requireNotNull(marker.lastOrdinal)
            if (original in first..last) {
                val noteId = requireNotNull(marker.noteBlockId)
                val prior = clip[noteId]
                clip[noteId] = minOf(prior?.first ?: localOrdinal, localOrdinal)..maxOf(prior?.last ?: localOrdinal, localOrdinal)
            }
        }
    }
    return clip.map { (noteId, range) -> CaptureArchiveMarker(noteId, range.first, range.last) }
}

private data class FrozenSelection(
    val indexFile: File,
    val recordCount: Long,
    val logBytes: Long,
    val firstElapsedMs: Long?,
    val lastElapsedMs: Long?,
)

private data class FinalizationInput(
    val indexRecordCount: Long,
    val firstElapsedMs: Long?,
    val lastElapsedMs: Long?,
    val lastVideoElapsedMs: Long?,
    val rowCount: Int,
)

private fun requireRegularCaptureFile(file: File, label: String) {
    if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
        throw CaptureArchiveException("$label is missing or is not a regular file: $file")
    }
}

/** Validates the recorder's complete output before derived files are published. */
private fun validateFinalizationInput(session: CaptureSession): FinalizationInput =
    if (session.storageVersion == CaptureStorageVersion.BLOCKS_V2) {
        validateSparseFinalizationInput(session)
    } else {
        validateLegacyFinalizationInput(session)
    }

private fun validateSparseFinalizationInput(session: CaptureSession): FinalizationInput {
    val metadata = session.syncMetadataOverride ?: CaptureSyncMetadata.parse(readTextShared(session.syncFile))
    val blocks = CaptureBlockIndex.read(
        session.blockIndexFile,
        session.logFile,
        maxIndexBytes = session.frozenBlockIndexBytes ?: session.blockIndexFile.length(),
        maxLogBytes = session.frozenLogBytes ?: session.logFile.length(),
    )
    require(metadata.logBytes == blocks.indexedLogBytes && metadata.rowCount == blocks.indexedRows) {
        "Capture block index does not match the sync metadata"
    }
    require(metadata.logBytes == session.logFile.length()) { "Capture sync metadata does not cover the capture log" }
    val firstElapsed = blocks.blocks.minOfOrNull(CaptureBlock::firstElapsedMs)
    val lastElapsed = blocks.blocks.maxOfOrNull(CaptureBlock::lastElapsedMs)
    val lastVideoElapsed = lastElapsed?.takeIf { elapsed ->
        session.videoStartElapsedMs?.let { videoStart -> elapsed - videoStart + session.manualOffsetMs >= 0L } == true
    }
    return FinalizationInput(blocks.blocks.size.toLong(), firstElapsed, lastElapsed, lastVideoElapsed, metadata.rowCount)
}

@Suppress("TooGenericExceptionCaught") // Normalize legacy file and parser failures into one finalization error.
private fun validateLegacyFinalizationInput(session: CaptureSession): FinalizationInput {
    val logBytes = session.logFile.length()
    val parsedLogCount = countLegacyLogRows(session.logFile)
    val state = LegacyFinalizationScanState(session, logBytes)
    try {
        session.indexFile.inputStream().use { base ->
            val input = LimitedInputStream(BufferedInputStream(base), session.indexFile.length())
            forEachCompleteLine(input, MAX_MAPPING_LINE_BYTES, rejectTrailingPartial = true) { line ->
                state.accept(parseIndexRecord(line))
            }
        }
    } catch (failure: CaptureArchiveException) {
        throw failure
    } catch (failure: Exception) {
        throw CaptureArchiveException(
            "Capture index could not be validated during finalization: ${failure.message ?: failure::class.simpleName}",
            failure,
        )
    }
    require(state.rowCount == parsedLogCount) {
        "Capture mapping row count ${state.rowCount} does not match parsed log row count $parsedLogCount"
    }
    return state.result()
}

@Suppress("TooGenericExceptionCaught") // Legacy text decoding and parser adapters can fail through different exception types.
private fun countLegacyLogRows(logFile: File): Long = try {
    logFile.inputStream().use { input ->
        openLogTextReader(input).useLines { lines ->
            lines.fold(0L) { count, line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("-----")) count + 1 else count
            }
        }
    }
} catch (failure: Exception) {
    throw CaptureArchiveException(
        "Capture log could not be parsed during finalization: ${failure.message ?: failure::class.simpleName}",
        failure,
    )
}

private class LegacyFinalizationScanState(private val session: CaptureSession, private val logBytes: Long) {
    private var indexRecordCount = 0L
    private var nextOrdinal = 1
    private var firstElapsedMs: Long? = null
    private var lastElapsedMs: Long? = null
    private var lastVideoElapsedMs: Long? = null
    private var previous: CaptureLogIndexRecord? = null

    val rowCount: Long get() = nextOrdinal - 1L

    fun accept(record: CaptureLogIndexRecord) {
        validateLegacyFinalizationRecord(record, previous, logBytes, nextOrdinal)
        previous = record
        indexRecordCount++
        record.rowOrdinal?.let { ordinal ->
            nextOrdinal++
            if (firstElapsedMs == null) firstElapsedMs = record.elapsedMs
            lastElapsedMs = record.elapsedMs
            if (isVideoElapsed(record.elapsedMs)) lastVideoElapsedMs = record.elapsedMs
        }
    }

    private fun isVideoElapsed(elapsedMs: Long): Boolean = session.videoStartElapsedMs?.let { videoStart ->
        elapsedMs - videoStart + session.manualOffsetMs >= 0L
    } == true

    fun result(): FinalizationInput = FinalizationInput(
        indexRecordCount,
        firstElapsedMs,
        lastElapsedMs,
        lastVideoElapsedMs,
        rowCount.toInt(),
    )
}

private fun validateLegacyFinalizationRecord(
    record: CaptureLogIndexRecord,
    previous: CaptureLogIndexRecord?,
    logBytes: Long,
    nextOrdinal: Int,
) {
    require(record.byteOffset >= 0 && record.byteLength >= 0) { "Capture index contains a negative byte range" }
    require(record.byteOffset <= logBytes - record.byteLength) { "Capture index points beyond the capture log boundary" }
    require(previous == null || record.byteOffset >= previous.byteOffset + previous.byteLength) {
        "Capture index byte ranges are not ordered"
    }
    require(previous == null || record.elapsedMs >= previous.elapsedMs) { "Capture index times are not ordered" }
    record.rowOrdinal?.let { ordinal ->
        require(ordinal == nextOrdinal) { "Capture index row ordinals are not contiguous at $ordinal (expected $nextOrdinal)" }
    }
}

private data class SelectionBounds(
    val firstElapsedMs: Long,
    val lastElapsedMs: Long,
    val firstVideoMs: Long? = null,
    val lastVideoMs: Long? = null,
)

/** Spools the frozen legacy selection without retaining its per-row mapping in memory. */
private fun freezeSelection(
    indexFile: File,
    frozenBytes: Long,
    frozenLogBytes: Long,
    selectedStartMs: Long,
    selectedEndMs: Long,
    excludeStart: Boolean,
    selectedFirstRowOrdinal: Int? = null,
    selectedLastRowOrdinal: Int? = null,
    destination: File,
): FrozenSelection {
    val accumulator = FrozenSelectionAccumulator(
        frozenLogBytes,
        selectedStartMs,
        selectedEndMs,
        excludeStart,
        selectedFirstRowOrdinal,
        selectedLastRowOrdinal,
    )
    destination.bufferedWriter(Charsets.UTF_8).use { output ->
        indexFile.inputStream().use { base ->
            val input = LimitedInputStream(BufferedInputStream(base), frozenBytes)
            forEachCompleteLine(input, MAX_MAPPING_LINE_BYTES) { line ->
                checkNotInterrupted()
                accumulator.append(parseIndexRecord(line), line, output)
            }
        }
    }
    return accumulator.result(destination)
}

private class FrozenSelectionAccumulator(
    private val frozenLogBytes: Long,
    private val selectedStartMs: Long,
    private val selectedEndMs: Long,
    private val excludeStart: Boolean,
    private val selectedFirstRowOrdinal: Int?,
    private val selectedLastRowOrdinal: Int?,
) {
    private var recordCount = 0L
    private var mappingRecordCount = 0L
    private var logBytes = 0L
    private var spooledIndexBytes = 0L
    private var firstElapsedMs: Long? = null
    private var lastElapsedMs: Long? = null
    private var lastRecord: CaptureLogIndexRecord? = null
    private var selectionStarted = false
    private var selectionEnded = false

    fun append(record: CaptureLogIndexRecord, line: String, output: java.io.BufferedWriter) {
        validateRecord(record)
        lastRecord = record
        if (!isSelected(record)) return
        appendSelectedRecord(record, line, output)
    }

    private fun validateRecord(record: CaptureLogIndexRecord) {
        require(record.byteOffset >= 0 && record.byteLength >= 0) { "Capture index contains a negative byte range" }
        require(record.byteOffset <= frozenLogBytes - record.byteLength) {
            "Capture index points beyond the frozen log boundary"
        }
        val previous = lastRecord
        require(previous == null || record.byteOffset >= previous.byteOffset + previous.byteLength) {
            "Capture index byte ranges are not ordered"
        }
        require(previous == null || record.elapsedMs >= previous.elapsedMs) { "Capture index times are not ordered" }
    }

    private fun isSelected(record: CaptureLogIndexRecord): Boolean {
        if (selectedFirstRowOrdinal != null && selectedLastRowOrdinal != null) {
            return isSelectedOrdinal(record.rowOrdinal, selectedFirstRowOrdinal, selectedLastRowOrdinal)
        }
        val afterStart = if (excludeStart) record.elapsedMs > selectedStartMs else record.elapsedMs >= selectedStartMs
        return afterStart && record.elapsedMs <= selectedEndMs
    }

    private fun isSelectedOrdinal(rowOrdinal: Int?, first: Int, last: Int): Boolean {
        if (selectionEnded) return false
        if (rowOrdinal == null) return selectionStarted
        if (rowOrdinal < first) return false
        if (rowOrdinal > last) {
            selectionEnded = selectionStarted
            return false
        }
        selectionStarted = true
        if (rowOrdinal == last) selectionEnded = true
        return true
    }

    private fun appendSelectedRecord(record: CaptureLogIndexRecord, line: String, output: java.io.BufferedWriter) {
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
        if (selectedFirstRowOrdinal != null && record.rowOrdinal == selectedLastRowOrdinal) selectionEnded = true
    }

    fun result(destination: File): FrozenSelection =
        FrozenSelection(destination, recordCount, logBytes, firstElapsedMs, lastElapsedMs)
}

private fun forEachSelectedRecord(indexFile: File, block: (CaptureLogIndexRecord) -> Unit) {
    indexFile.inputStream().use { input ->
        forEachCompleteLine(BufferedInputStream(input), MAX_MAPPING_LINE_BYTES, rejectTrailingPartial = true) { line ->
            block(parseIndexRecord(line))
        }
    }
}

// internal, not private: capture/CaptureMarkerWindow.kt's ordinalRangeForElapsedWindow and
// captureLogEntriesForOrdinals reuse this exact parser for the "Mark issue" window scan, per the
// restyle plan's Phase 3 — see that file's own header for why a second parser was not written.
internal fun parseIndexRecord(line: String): CaptureLogIndexRecord =
    parseIndexRecordFast(line) ?: parseIndexRecordJson(line)

private const val INDEX_KEY_OFFSET = "{\"byteOffset\":"
private const val INDEX_KEY_LENGTH = ",\"byteLength\":"
private const val INDEX_KEY_ELAPSED = ",\"elapsedMs\":"
private const val INDEX_KEY_ORDINAL = ",\"rowOrdinal\":"
private const val NO_NUMBER = Long.MIN_VALUE

/**
 * Allocation-free parse of the exact canonical line `indexRecordJson` writes:
 * `{"byteOffset":N,"byteLength":N,"elapsedMs":N}` optionally followed by `,"rowOrdinal":N` before the
 * closing `}`. No whitespace, no other keys, no `null`; N is an optional `-` and decimal digits with
 * no leading zero. Returns null on ANY deviation (or a number out of range) so the caller falls back
 * to [parseIndexRecordJson], which keeps the original semantics and error messages.
 */
private fun parseIndexRecordFast(line: String): CaptureLogIndexRecord? {
    val end = line.length
    var pos = matchLiteral(line, 0, INDEX_KEY_OFFSET)
    val offsetEnd = canonicalNumberEnd(line, pos)
    val offset = parseCanonicalLong(line, pos, offsetEnd)
    pos = matchLiteral(line, offsetEnd, INDEX_KEY_LENGTH)
    val lengthEnd = canonicalNumberEnd(line, pos)
    val length = parseCanonicalLong(line, pos, lengthEnd)
    pos = matchLiteral(line, lengthEnd, INDEX_KEY_ELAPSED)
    val elapsedEnd = canonicalNumberEnd(line, pos)
    val elapsed = parseCanonicalLong(line, pos, elapsedEnd)
    var ordinal = NO_NUMBER
    var tail = elapsedEnd
    val ordinalStart = matchLiteral(line, tail, INDEX_KEY_ORDINAL)
    if (ordinalStart >= 0) {
        tail = canonicalNumberEnd(line, ordinalStart)
        ordinal = parseCanonicalLong(line, ordinalStart, tail)
        if (ordinal == NO_NUMBER) return null
    }
    val valid = offset != NO_NUMBER && length != NO_NUMBER && elapsed != NO_NUMBER &&
        length in Int.MIN_VALUE..Int.MAX_VALUE && (ordinalStart < 0 || ordinal in Int.MIN_VALUE..Int.MAX_VALUE) &&
        tail >= 0 && tail == end - 1 && line[tail] == '}'
    if (!valid) return null
    return CaptureLogIndexRecord(offset, length.toInt(), elapsed, if (ordinalStart < 0) null else ordinal.toInt())
}

/** Index just past [literal] when it matches [line] at [from], else -1 (also when [from] is already -1). */
private fun matchLiteral(line: String, from: Int, literal: String): Int =
    if (from >= 0 && line.startsWith(literal, from)) from + literal.length else -1

/** End index of `-?(0|[1-9][0-9]*)` starting at [from], or -1 when there is no canonical number there. */
private fun canonicalNumberEnd(line: String, from: Int): Int {
    if (from < 0) return -1
    var i = from
    val negative = i < line.length && line[i] == '-'
    if (negative) i++
    val digitsStart = i
    while (i < line.length && line[i] in '0'..'9') i++
    val digits = i - digitsStart
    val leadingZero = digits > 0 && line[digitsStart] == '0' && (digits > 1 || negative)
    return if (digits == 0 || leadingZero) -1 else i
}

/** Parses the canonical number in [from, to) into a Long; [NO_NUMBER] on a miss or overflow. */
private fun parseCanonicalLong(line: String, from: Int, to: Int): Long {
    if (to < 0) return NO_NUMBER
    val negative = line[from] == '-'
    val limit = if (negative) Long.MIN_VALUE else -Long.MAX_VALUE
    val multMin = limit / 10
    var acc = 0L
    for (i in (if (negative) from + 1 else from) until to) {
        val d = line[i] - '0'
        // Accumulate negatively so Long.MIN_VALUE is representable; overflow => fall back.
        if (acc < multMin) return NO_NUMBER
        acc *= 10
        if (acc < limit + d) return NO_NUMBER
        acc -= d
    }
    return if (negative) acc else -acc
}

internal fun parseIndexRecordJson(line: String): CaptureLogIndexRecord {
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
    CaptureRange.SELECTION -> error("Selection range requires explicit row bounds")
    CaptureRange.EXPLICIT -> error("Explicit range requires elapsed-time bounds")
}

private fun requestedRangeStartMs(request: CaptureExportRequest, checkpointMs: Long): Long =
    if (request.range == CaptureRange.EXPLICIT) {
        val start = requireNotNull(request.explicitStartElapsedMs) { "Explicit range requires a start time" }
        val end = requireNotNull(request.explicitEndElapsedMs) { "Explicit range requires an end time" }
        require(start >= 0L && end > start) { "Explicit capture range must be non-negative and ordered" }
        start
    } else {
        rangeStartMs(request.range, request.cutoffElapsedMs, request.customMinutes, checkpointMs)
            .takeIf { it == Long.MIN_VALUE }
            ?: rangeStartMs(request.range, request.cutoffElapsedMs, request.customMinutes, checkpointMs).coerceAtLeast(0L)
    }

private fun requestedRangeEndMs(request: CaptureExportRequest): Long =
    if (request.range == CaptureRange.EXPLICIT) {
        val start = requireNotNull(request.explicitStartElapsedMs) { "Explicit range requires a start time" }
        val end = requireNotNull(request.explicitEndElapsedMs) { "Explicit range requires an end time" }
        require(start >= 0L && end > start) { "Explicit capture range must be non-negative and ordered" }
        val boundedEnd = minOf(end, request.cutoffElapsedMs.coerceAtLeast(0L))
        require(boundedEnd > start) { "Explicit capture range is empty at the current capture boundary" }
        boundedEnd
    } else {
        request.cutoffElapsedMs.coerceAtLeast(0L)
    }

private fun hasAvailabilityMetadataInRange(session: CaptureSession, startElapsedMs: Long, endElapsedMs: Long): Boolean {
    if (endElapsedMs <= startElapsedMs) return false
    val start = startElapsedMs.coerceAtLeast(0L)
    val end = minOf(endElapsedMs, session.elapsedMs.coerceAtLeast(0L))
    if (end <= start) return false
    return session.availabilityIntervals.any { interval ->
        val intervalEnd = interval.endElapsedMs ?: session.elapsedMs.coerceAtLeast(0L)
        start < intervalEnd && interval.startElapsedMs < end
    }
}

private fun requireSnapshotCheckpoint(session: CaptureSession, request: CaptureExportRequest): Long {
    val checkpoint = session.effectiveSnapshotCheckpointMs
    require(request.range != CaptureRange.SINCE_SAVE || checkpoint >= 0) {
        "Since last save is unavailable until the first successful snapshot"
    }
    val end = requestedRangeEndMs(request).coerceAtMost(session.elapsedMs.coerceAtLeast(0L))
    require(request.range != CaptureRange.SINCE_SAVE || checkpoint <= end) {
        "Since last save is unavailable because its saved endpoint is later than this capture range"
    }
    if (request.range == CaptureRange.SINCE_SAVE && checkpoint == end) {
        val videoCursor = session.effectiveVideoCheckpointMs
        val currentBytes = session.frozenLogBytes ?: session.logFile.length()
        val hasUncoveredLogBytes = session.logCheckpointByteOffset >= 0L && session.logCheckpointByteOffset < currentBytes
        require(hasUncoveredLogBytes || (request.includeVideo && session.settings.recordVideo && videoCursor < end)) {
            "Nothing new to save: the range ends at the saved endpoint"
        }
    }
    return checkpoint
}

/** Stream one export-local elapsed time for each parsed log row; no mapping row is retained in memory. */
private fun writeTimelineRows(
    indexFile: File,
    frozenBytes: Long,
    destination: File,
    baseElapsedMs: Long,
    durationMs: Long,
    availabilityIntervals: List<CaptureAvailabilityInterval>,
) {
    require(indexFile.length() <= MAX_TIMELINE_BYTES) { "Capture timeline source exceeds the size limit" }
    require(baseElapsedMs >= 0L && durationMs >= 0L) { "Capture timeline has invalid bounds" }
    var rows = 0
    var previousElapsedMs: Long? = null
    destination.parentFile?.mkdirs()
    indexFile.inputStream().use { base ->
        val input = LimitedInputStream(BufferedInputStream(base), frozenBytes)
        destination.bufferedWriter(Charsets.UTF_8).use { output ->
            fun writeLine(line: String) {
                require(line.length <= MAX_MAPPING_LINE_BYTES) { "Capture timeline metadata line exceeds the size limit" }
                output.append(line).append('\n')
                require(destination.length() <= MAX_TIMELINE_BYTES) { "Capture timeline exceeds the size limit" }
            }
            writeLine(
                buildJsonObject {
                    put("type", "captureTimeline")
                    put("baseElapsedMs", baseElapsedMs)
                    put("durationMs", durationMs)
                }.toString(),
            )
            availabilityIntervals.forEach { interval ->
                require(interval.endElapsedMs != null && interval.endElapsedMs > interval.startElapsedMs) {
                    "Capture timeline contains an open or empty availability interval"
                }
                require(interval.startElapsedMs >= 0L && interval.endElapsedMs <= durationMs) {
                    "Capture timeline availability is outside its declared duration"
                }
                writeLine(
                    buildJsonObject {
                        put("type", "availability")
                        put("startMs", interval.startElapsedMs)
                        nullableLong("endMs", interval.endElapsedMs)
                        nullableString("bootIdBefore", interval.bootIdBefore)
                        nullableString("bootIdAfter", interval.bootIdAfter)
                        if (interval.rebootConfirmed == null) put("rebootConfirmed", JsonNull)
                        else put("rebootConfirmed", interval.rebootConfirmed)
                    }.toString(),
                )
            }
            forEachCompleteLine(input, MAX_MAPPING_LINE_BYTES) { line ->
                checkNotInterrupted()
                val record = parseIndexRecord(line)
                if (record.rowOrdinal != null) {
                    require(++rows <= MAX_MAPPING_ROWS) { "Capture timeline contains too many rows" }
                    require(record.elapsedMs >= baseElapsedMs) { "Capture timeline row precedes its export base" }
                    require(previousElapsedMs == null || record.elapsedMs >= previousElapsedMs!!) {
                        "Capture timeline times are not ordered"
                    }
                    previousElapsedMs = record.elapsedMs
                    writeLine(
                        buildJsonObject {
                            put("row", rows)
                            put("elapsedMs", record.elapsedMs - baseElapsedMs)
                        }.toString(),
                    )
                }
            }
        }
    }
}

private fun clipAvailabilityIntervals(
    intervals: List<CaptureAvailabilityInterval>,
    baseElapsedMs: Long,
    endElapsedMs: Long,
): List<CaptureAvailabilityInterval> {
    if (endElapsedMs <= baseElapsedMs) return emptyList()
    return intervals.mapNotNull { interval ->
        val start = maxOf(interval.startElapsedMs, baseElapsedMs)
        val end = minOf(interval.endElapsedMs ?: endElapsedMs, endElapsedMs)
        if (end <= start) null else interval.copy(
            startElapsedMs = start - baseElapsedMs,
            endElapsedMs = end - baseElapsedMs,
        )
    }
}

/**
 * Archive v3's replacement for the old per-row `writeMapping` writer: reduces the same per-row
 * (elapsedMs, video position) evidence that function used to serialize wholesale into ONE sync
 * anchor (see [estimateCaptureSyncAnchor] and this file's own module doc).
 *
 * [logFile] is reopened and reparsed with [parseLogcat] rather than threading `ts` values through
 * [selectionIndex] (which only ever carried the host's own `elapsedMs`, never the row's own
 * device-embedded timestamp) — this guarantees the "log time" fed into the offset estimate is
 * produced by the EXACT SAME code path ([parseLogcat] + [unrollLogTimeline]) that AppState runs
 * over this same file at import time to build a manual anchor's elapsed timeline, so the two can
 * never disagree. For [export], [logFile] is the just-staged, already-selected `logcat.log`
 * (ordinals 1..N in export-local numbering); for [finalizeSessionInPlace], it is the session's own
 * full, never-restaged log (finalization keeps every row, so export-local ordinal == session
 * ordinal there). [selectionIndex] must be the SAME frozen, already-filtered index the caller used
 * to stage/select that same log — see [clipMarkersForExport]'s own doc for why this ordinal
 * counter (one increment per row that carries a `rowOrdinal`) must stay in lockstep with the one
 * LogParser reproduces when the shipped log is reopened.
 */
private fun computeCaptureSyncAnchor(
    logFile: File,
    selectionIndex: File,
    session: CaptureSession,
    clip: CaptureVideoClip?,
    includeRawVideo: Boolean = false,
): CaptureSyncAnchor? = computeCaptureSyncAnchor(
    logFile, selectionIndex, session.videoStartElapsedMs, session.manualOffsetMs, clip, includeRawVideo,
)

private fun computeCaptureSyncAnchor(
    logFile: File,
    selectionIndex: File,
    videoStart: Long?,
    manualOffsetMs: Long,
    clip: CaptureVideoClip?,
    includeRawVideo: Boolean = false,
): CaptureSyncAnchor? {
    val logTimeByOrdinal = unrollLogTimeline(parseLogcat(logFile)).byId
    var ordinal = 0
    val samples = ArrayList<CaptureSyncSample>()
    forEachSelectedRecord(selectionIndex) { record ->
        if (record.rowOrdinal == null) return@forEachSelectedRecord
        ordinal += 1
        val sourceVideoMs = videoStart?.let { record.elapsedMs - it + manualOffsetMs }
        val exportedVideoMs = when {
            clip != null && sourceVideoMs != null && sourceVideoMs in clip.actualStartMs..clip.coveredEndMs ->
                sourceVideoMs - clip.actualStartMs
            includeRawVideo && sourceVideoMs != null && sourceVideoMs >= 0 -> sourceVideoMs
            else -> null
        }
        samples += CaptureSyncSample(ordinal, logTimeByOrdinal[ordinal], exportedVideoMs)
    }
    return estimateCaptureSyncAnchor(samples, videoDurationMs = clip?.durationMs)
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

private fun readTimelineRows(
    file: File,
    expectedRowCount: Int,
    expectedBaseElapsedMs: Long,
    expectedDurationMs: Long,
    expectedAvailability: List<CaptureAvailabilityInterval>,
): List<Pair<Int, Long>> {
    require(file.length() <= MAX_TIMELINE_BYTES) { "Capture timeline exceeds the size limit" }
    val rows = ArrayList<Pair<Int, Long>>(expectedRowCount.coerceAtMost(MAX_MAPPING_ROWS))
    var timelineHeaderSeen = false
    var headerBaseElapsedMs: Long? = null
    var headerDurationMs: Long? = null
    val availability = ArrayList<CaptureAvailabilityInterval>()
    file.inputStream().use { input ->
        forEachCompleteLine(BufferedInputStream(input), MAX_MAPPING_LINE_BYTES, rejectTrailingPartial = true) { line ->
            val root = Json.parseToJsonElement(line).jsonObject
            when (root.string("type")) {
                "captureTimeline" -> {
                    require(!timelineHeaderSeen && rows.isEmpty() && availability.isEmpty()) {
                        "Capture timeline metadata header is misplaced or duplicated"
                    }
                    timelineHeaderSeen = true
                    headerBaseElapsedMs = root.long("baseElapsedMs")
                        ?: error("Capture timeline metadata is missing its base time")
                    headerDurationMs = root.long("durationMs")
                        ?: error("Capture timeline metadata is missing its duration")
                    return@forEachCompleteLine
                }
                "availability" -> {
                    require(timelineHeaderSeen && rows.isEmpty()) { "Capture timeline availability is misplaced" }
                    require(availability.size < MAX_MAPPING_ROWS) { "Capture timeline has too many availability intervals" }
                    availability += root.toAvailabilityInterval()
                    return@forEachCompleteLine
                }
                null -> Unit // Headerless v4 assets from early development builds remain readable.
                else -> error("Capture timeline has an unknown record type")
            }
            require(rows.size < MAX_MAPPING_ROWS) { "Capture timeline has too many rows" }
            val ordinal = root.int("row") ?: error("Capture timeline row is missing its local ordinal")
            require(ordinal == rows.size + 1) { "Capture timeline ordinals are not contiguous" }
            val elapsed = root.long("elapsedMs") ?: error("Capture timeline row is missing elapsedMs")
            require(elapsed >= 0L) { "Capture timeline contains a negative elapsed time" }
            require(rows.lastOrNull()?.second?.let { elapsed >= it } != false) { "Capture timeline times are not ordered" }
            rows += ordinal to elapsed
        }
    }
    require(rows.size == expectedRowCount) {
        "Capture timeline row count ${rows.size} does not match parsed log row count $expectedRowCount"
    }
    if (timelineHeaderSeen) {
        require(headerBaseElapsedMs == expectedBaseElapsedMs && headerDurationMs == expectedDurationMs) {
            "Capture timeline metadata does not match the capture descriptor"
        }
        require(availability == expectedAvailability) {
            "Capture timeline availability does not match the capture descriptor"
        }
    }
    return rows
}

/**
 * v3's own flat, simplified shape (see this file's module doc and the archive-v3 plan): a plain
 * `sessionId`/`app`/`device`/`range`/`interruptions` header, one object per asset carrying just its
 * archive-relative `file` name plus the timing that asset needs (`log`/`video`), `notes` as a bare
 * path string (or null), `markers` as `{"note", "rows":[first,last]}`, `screenshots` as bare path
 * strings, and one flat `sha256` map keyed by path — replacing the old one-`sizeBytes`-and-`sha256`-
 * per-asset objects. [CaptureArchiveAsset.sizeBytes] is not recoverable from this shape alone, so
 * [parseDescriptorV3] leaves every asset's `sizeBytes` at -1 (unused by anything downstream — see
 * that field's own doc).
 *
 * `sync` replaced its old `{"file","offsetMs"}` mapping-file pointer with the single anchor
 * [estimateCaptureSyncAnchor] computes: `{"row","videoMs"}`, or `null` when this export has no
 * video or no row an offset could be estimated from (see [CaptureArchiveDescriptor.syncAnchor]'s
 * own doc). `manualOffsetMs` moved out to its own top-level `offsetMs` key since it is no longer
 * part of the same object as a "sync" pointer.
 */
private fun CaptureArchiveDescriptor.toJson(): String = buildJsonObject {
    put("format", "indagium-capture")
    put("version", formatVersion)
    put("app", app)
    put("sessionId", sessionId)
    put("device", buildJsonObject {
        put("model", device.model)
        put("serial", device.serial)
        put("state", device.state)
        put("emulator", device.emulator)
    })
    put("startedAt", isoInstant(sessionStartedEpochMs))
    put("exportedAt", isoInstant(exportedEpochMs))
    put("range", range.name)
    put("log", buildJsonObject {
        put("file", log.path)
        put("startMs", logStartMs)
        put("endMs", logEndMs)
    })
    if (video != null) {
        put("video", buildJsonObject {
            put("file", video.path)
            nullableLong("startMs", videoStartMs)
            nullableLong("endMs", videoEndMs)
        })
    } else {
        put("video", JsonNull)
    }
    if (syncAnchor != null) {
        put("sync", buildJsonObject {
            put("row", syncAnchor.row)
            put("videoMs", syncAnchor.videoMs)
        })
    } else {
        put("sync", JsonNull)
    }
    put("offsetMs", manualOffsetMs)
    if (notes != null) put("notes", notes.path) else put("notes", JsonNull)
    put(
        "markers",
        buildJsonArray {
            markers.forEach { marker ->
                add(
                    buildJsonObject {
                        put("note", marker.noteBlockId)
                        put("rows", buildJsonArray { add(marker.firstOrdinal); add(marker.lastOrdinal) })
                    },
                )
            }
        },
    )
    put("screenshots", buildJsonArray { screenshots.forEach { add(it.path) } })
    put("interruptions", buildJsonArray { interruptions.forEach { add(it) } })
    nullableString("timeline", timeline?.path)
    nullableString("blocks", blocks?.path)
    nullableString("syncMetadata", sparseSync?.path)
    put("timelineBaseMs", timelineBaseElapsedMs)
    put("timelineDurationMs", timelineDurationMs)
    put("availabilityFramesRendered", availabilityFramesRendered)
    if (showUnavailableFrames == null) put("showUnavailableFrames", JsonNull)
    else put("showUnavailableFrames", showUnavailableFrames)
    put("availability", buildJsonArray {
        availabilityIntervals.forEach { interval ->
            add(buildJsonObject {
                put("startMs", interval.startElapsedMs)
                nullableLong("endMs", interval.endElapsedMs)
                nullableString("bootIdBefore", interval.bootIdBefore)
                nullableString("bootIdAfter", interval.bootIdAfter)
                if (interval.rebootConfirmed == null) put("rebootConfirmed", JsonNull)
                else put("rebootConfirmed", interval.rebootConfirmed)
            })
        }
    })
    put("sha256", buildJsonObject { assets().forEach { put(it.path, it.sha256) } })
}.toString()

private fun kotlinx.serialization.json.JsonObjectBuilder.nullableLong(name: String, value: Long?) {
    if (value == null) put(name, JsonNull) else put(name, value)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.nullableString(name: String, value: String?) {
    if (value == null) put(name, JsonNull) else put(name, value)
}

private fun isoInstant(epochMs: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs))

private fun parseIsoInstant(text: String): Long = Instant.parse(text).toEpochMilli()

/** Top-level entry: reads the descriptor's version, then dispatches to the v3 or legacy (v1/v2)
 *  shape — see [CaptureArchiveDescriptor]'s own doc for what each version writes/omits. */
@Suppress("TooGenericExceptionCaught")
private fun parseDescriptor(raw: String): CaptureArchiveDescriptor {
    try {
        val root = Json.parseToJsonElement(raw).jsonObject
        require(root.string("format") == "indagium-capture") { "Not an Indagium capture descriptor" }
        // v3 renamed the version key "formatVersion" -> "version"; both are read so a v1/v2
        // archive (which only ever wrote "formatVersion") still opens.
        val version = root.int("version") ?: root.int("formatVersion")
            ?: error("Capture descriptor is missing its format version")
        // 1..CAPTURE_ARCHIVE_VERSION, not an exact match — see CAPTURE_ARCHIVE_VERSION's own doc
        // comment for why an older archive (missing keys a later version added) still opens here.
        require(version in 1..CAPTURE_ARCHIVE_VERSION) { "Unsupported capture archive version: $version" }
        val descriptor = if (version >= 3) parseDescriptorV3(root, version) else parseDescriptorLegacy(root, version)
        validateParsedDescriptor(descriptor)
        return descriptor
    } catch (e: CaptureArchiveException) {
        throw e
    } catch (e: Exception) {
        throw CaptureArchiveException(e.message ?: "Invalid capture descriptor", e)
    }
}

@Suppress("CyclomaticComplexMethod") // This parser maps one historical descriptor schema with many optional fields.
private fun parseDescriptorV3(root: JsonObject, version: Int): CaptureArchiveDescriptor {
    val device = root.requiredObject("device")
    val log = root.requiredObject("log")
    val videoObj = root["video"] as? JsonObject
    // The current writer's "sync" is either the {"row","videoMs"} anchor object or null (no video /
    // no estimate) — never required. An in-progress v3 build on this same branch could still have
    // written the older {"file","offsetMs"} mapping-file pointer; tolerated by simply reading no
    // anchor from it rather than trying to resurrect a mapping file that no longer ships (see this
    // class's own doc — nothing outside this machine has ever seen that shape).
    val sync = root["sync"] as? JsonObject
    val syncAnchor = sync?.toSyncAnchorOrNull()
    val sha256 = root["sha256"] as? JsonObject ?: JsonObject(emptyMap())

    fun assetFor(path: String) = CaptureArchiveAsset(
        path = path,
        sizeBytes = -1L,
        sha256 = sha256.string(path) ?: error("Capture descriptor is missing a sha256 entry for $path"),
    )
    return CaptureArchiveDescriptor(
        formatVersion = version,
        app = root.string("app") ?: "",
        sessionId = root.requiredString("sessionId"),
        device = device.toArchiveDevice(),
        sessionStartedEpochMs = root.string("startedAt")?.let(::parseIsoInstant) ?: root.requiredLong("sessionStartedEpochMs"),
        exportedEpochMs = root.string("exportedAt")?.let(::parseIsoInstant) ?: root.requiredLong("exportedEpochMs"),
        range = root.requiredString("range").let { CaptureRange.valueOf(it) },
        // Prefers the current top-level "offsetMs"; falls back to the old sync-nested one so an
        // in-progress v3 build's descriptor still round-trips its manual offset.
        manualOffsetMs = root.long("offsetMs") ?: sync?.long("offsetMs") ?: 0,
        interruptions = root.stringList("interruptions") ?: emptyList(),
        log = assetFor(log.requiredString("file")),
        logStartMs = log.requiredLong("startMs"),
        logEndMs = log.requiredLong("endMs"),
        mapping = null,
        video = videoObj?.let { assetFor(it.requiredString("file")) },
        videoStartMs = videoObj?.nullableLong("startMs"),
        videoEndMs = videoObj?.nullableLong("endMs"),
        screenshots = (root["screenshots"] as? JsonArray)?.map { assetFor((it as JsonPrimitive).content) } ?: emptyList(),
        notes = root.string("notes")?.let(::assetFor),
        markers = (root["markers"] as? JsonArray)?.map { it.jsonObject.toMarkerV3() } ?: emptyList(),
        syncAnchor = syncAnchor,
        timeline = if (version == 4) root.string("timeline")?.let(::assetFor) else null,
        availabilityIntervals = if (version >= 4) root.availabilityIntervals() else emptyList(),
        timelineBaseElapsedMs = root.long("timelineBaseMs") ?: 0L,
        timelineDurationMs = root.long("timelineDurationMs") ?: 0L,
        availabilityFramesRendered = root.boolean("availabilityFramesRendered") ?: false,
        showUnavailableFrames = root.boolean("showUnavailableFrames"),
        blocks = if (version >= 5) root.string("blocks")?.let(::assetFor) else null,
        sparseSync = if (version >= 5) root.string("syncMetadata")?.let(::assetFor) else null,
    )
}

private fun JsonObject.toArchiveDevice(): CaptureArchiveDevice = CaptureArchiveDevice(
    serial = requiredString("serial"),
    state = string("state") ?: "device",
    model = requiredString("model"),
    emulator = boolean("emulator") ?: false,
)

private fun JsonObject.toSyncAnchorOrNull(): CaptureSyncAnchor? {
    val row = int("row")
    val videoMs = long("videoMs")
    return if (row != null && videoMs != null) CaptureSyncAnchor(row = row, videoMs = videoMs) else null
}

private fun JsonObject.availabilityIntervals(): List<CaptureAvailabilityInterval> =
    (this["availability"] as? JsonArray)?.map { it.jsonObject.toAvailabilityInterval() } ?: emptyList()

private fun JsonObject.toAvailabilityInterval(): CaptureAvailabilityInterval = CaptureAvailabilityInterval(
    startElapsedMs = requiredLong("startMs"),
    endElapsedMs = nullableLong("endMs"),
    bootIdBefore = string("bootIdBefore"),
    bootIdAfter = string("bootIdAfter"),
    rebootConfirmed = boolean("rebootConfirmed"),
)

private fun JsonObject.toMarkerV3(): CaptureArchiveMarker {
    val rows = this["rows"] as? JsonArray ?: error("Capture marker is missing rows")
    require(rows.size == 2) { "Capture marker rows must have exactly two elements" }

    fun ordinal(index: Int) = (rows[index] as? JsonPrimitive)?.content?.toIntOrNull()
        ?: error("Capture marker has an invalid row")
    return CaptureArchiveMarker(noteBlockId = requiredString("note"), firstOrdinal = ordinal(0), lastOrdinal = ordinal(1))
}

/** v1/v2's nested `coverage`/`mappingMetadata` shape, with a per-asset `{path,sizeBytes,sha256}`
 *  object everywhere v3 uses a bare path string. The full `settings` dump both versions wrote is
 *  parsed by neither this function nor anything downstream — nothing ever read `descriptor.settings`
 *  back (see [CaptureArchiveDescriptor]'s own doc), so it's simply skipped. */
private fun parseDescriptorLegacy(root: JsonObject, version: Int): CaptureArchiveDescriptor {
    val device = root.requiredObject("device")
    val coverage = root.requiredObject("coverage")
    val metadata = root.requiredObject("mappingMetadata")
    return CaptureArchiveDescriptor(
        formatVersion = version,
        app = "",
        sessionId = root.requiredString("sessionId"),
        device = device.toArchiveDevice(),
        sessionStartedEpochMs = root.requiredLong("sessionStartedEpochMs"),
        exportedEpochMs = root.requiredLong("exportedEpochMs"),
        range = root.requiredString("range").let { CaptureRange.valueOf(it) },
        manualOffsetMs = metadata.long("manualOffsetMs") ?: 0,
        interruptions = root.stringList("interruptions") ?: emptyList(),
        log = root.requiredObject("log").toLegacyAsset(),
        logStartMs = coverage.requiredLong("logStartMs"),
        logEndMs = coverage.requiredLong("logEndMs"),
        mapping = root.requiredObject("mapping").toLegacyAsset(),
        video = (root["video"] as? JsonObject)?.toLegacyAsset(),
        videoStartMs = coverage.nullableLong("videoActualStartMs"),
        videoEndMs = coverage.nullableLong("videoEndMs"),
        screenshots = (root["screenshots"] as? JsonArray)?.map { it.jsonObject.toLegacyAsset() } ?: emptyList(),
        // Phase 4 — both keys OPTIONAL: absent (a v1 archive, or a v2 one exported with
        // markerNotesInSnapshot off) means null/empty, exactly like `video` above.
        notes = (root["notes"] as? JsonObject)?.toLegacyAsset(),
        markers = (root["markers"] as? JsonArray)?.map { it.jsonObject.toLegacyMarker() } ?: emptyList(),
    )
}

private fun JsonObject.toLegacyAsset(): CaptureArchiveAsset = CaptureArchiveAsset(
    path = requiredString("path"),
    sizeBytes = requiredLong("sizeBytes"),
    sha256 = requiredString("sha256"),
)

private fun JsonObject.toLegacyMarker(): CaptureArchiveMarker = CaptureArchiveMarker(
    noteBlockId = requiredString("noteBlockId"),
    firstOrdinal = int("firstOrdinal") ?: error("Capture marker is missing firstOrdinal"),
    lastOrdinal = int("lastOrdinal") ?: error("Capture marker is missing lastOrdinal"),
)

@Suppress("CyclomaticComplexMethod") // Keep the archive's cross-field and version-specific invariants together at the trust boundary.
private fun validateParsedDescriptor(descriptor: CaptureArchiveDescriptor) {
    require(descriptor.sessionId.isNotBlank()) { "Capture session id is blank" }
    require(descriptor.logEndMs >= descriptor.logStartMs) { "Capture log coverage is invalid" }
    descriptor.assets().forEach { asset ->
        require(isSafeRelativePath(asset.path)) { "Unsafe capture asset path: ${asset.path}" }
        require(asset.sha256.matches(Regex("[0-9a-f]{64}"))) { "Capture asset has an invalid SHA-256: ${asset.path}" }
    }
    require(descriptor.assets().map { it.path }.distinct().size == descriptor.assets().size) {
        "Capture descriptor repeats an asset path"
    }
    require(descriptor.assets().none { it.path == CAPTURE_DESCRIPTOR_NAME }) {
        "Capture descriptor cannot also be an asset"
    }
    descriptor.markers.forEach { marker ->
        require(marker.firstOrdinal in 1..marker.lastOrdinal) {
            "Capture marker has an invalid ordinal range: ${marker.noteBlockId}"
        }
    }
    require(descriptor.markers.map { it.noteBlockId }.distinct().size == descriptor.markers.size) {
        "Capture descriptor repeats a marker note id"
    }
    require(descriptor.notes != null || descriptor.markers.isEmpty()) {
        "Capture descriptor has markers but no notes asset"
    }
    // v3 replaced the mapping file with syncAnchor — the two are mutually exclusive by construction
    // (parseDescriptorV3 never sets mapping, parseDescriptorLegacy never sets syncAnchor), and this
    // keeps that invariant checked rather than merely assumed.
    require((descriptor.mapping == null) == (descriptor.formatVersion >= 3)) {
        "Capture descriptor mapping presence does not match its format version"
    }
    require(descriptor.formatVersion != 4 || descriptor.timeline != null) {
        "Capture archive v4 is missing its timeline asset"
    }
    require(descriptor.formatVersion < 5 || descriptor.blocks != null && descriptor.sparseSync != null) {
        "Capture archive v5 is missing its sparse metadata assets"
    }
    require(descriptor.formatVersion < 5 || descriptor.timeline == null && descriptor.syncAnchor == null) {
        "Capture archive v5 cannot contain a legacy row timeline or sync anchor"
    }
    require(descriptor.timelineBaseElapsedMs >= 0L) { "Capture timeline has an invalid base time" }
    require(descriptor.timelineDurationMs >= 0L) { "Capture timeline has an invalid duration" }
    if (descriptor.formatVersion >= 4) {
        var previousEnd = 0L
        descriptor.availabilityIntervals.forEachIndexed { index, interval ->
            val end = interval.endElapsedMs
            require(interval.startElapsedMs >= 0L && interval.startElapsedMs < descriptor.timelineDurationMs) {
                "Capture descriptor has an availability interval outside its timeline"
            }
            require(end != null && end > interval.startElapsedMs && end <= descriptor.timelineDurationMs) {
                "Capture descriptor has an invalid availability interval"
            }
            require(index == 0 || interval.startElapsedMs >= previousEnd) {
                "Capture availability intervals overlap or are out of order"
            }
            previousEnd = end
        }
    } else {
        require(descriptor.availabilityIntervals.all { interval ->
            interval.startElapsedMs >= 0L && (interval.endElapsedMs == null || interval.endElapsedMs >= interval.startElapsedMs)
        }) { "Capture descriptor has an invalid availability interval" }
    }
    require(descriptor.syncAnchor == null || descriptor.video != null) {
        "Capture descriptor has a sync anchor but no video"
    }
    require(descriptor.syncAnchor == null || descriptor.syncAnchor.row >= 1) {
        "Capture sync anchor has an invalid row"
    }
    require(descriptor.syncAnchor == null || descriptor.syncAnchor.videoMs >= 0) {
        "Capture sync anchor has a negative video position"
    }
    require(descriptor.formatVersion < 5 || descriptor.blocks?.path == "mapping/blocks.jsonl") {
        "Capture archive v5 has an invalid sparse block asset path"
    }
    require(descriptor.formatVersion < 5 || descriptor.sparseSync?.path == "timeline/sync.json") {
        "Capture archive v5 has an invalid sparse sync asset path"
    }
}

private fun CaptureArchiveDescriptor.assets(): List<CaptureArchiveAsset> =
    buildList {
        add(log)
        mapping?.let(::add)
        video?.let(::add)
        addAll(screenshots)
        notes?.let(::add)
        timeline?.let(::add)
        blocks?.let(::add)
        sparseSync?.let(::add)
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

private val ALREADY_COMPRESSED_EXTENSIONS = setOf("mkv", "mp4", "png", "jpg", "jpeg", "webp")

private fun isAlreadyCompressedMedia(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in ALREADY_COMPRESSED_EXTENSIONS

private fun writeZip(work: File, destination: File, descriptor: CaptureArchiveDescriptor, logCodec: CaptureArchiveLogCodec) =
    CaptureArchiveZipWriter.write(work, destination, descriptor, logCodec)

/** ZIP serialization path for real archives. */
internal object CaptureArchiveZipWriter {
    fun write(
        work: File,
        destination: File,
        descriptor: CaptureArchiveDescriptor,
        logCodec: CaptureArchiveLogCodec = CaptureArchiveLogCodec.DEFLATE,
    ) {
        // The Path constructor opens a seekable output: sizes and CRCs are patched into each local
        // header after the data, so unknown-size DEFLATE entries (video can reach 20 GiB) still get
        // ZIP64 when needed. A stream-backed output would downgrade those entries to Zip64Mode.Never.
        ZipArchiveOutputStream(destination.toPath()).use { zip ->
            zip.setUseZip64(Zip64Mode.AsNeeded)
            val paths = listOf(CAPTURE_DESCRIPTOR_NAME, CAPTURED_WITH_INDAGIUM_NAME) + descriptor.assets().map { it.path }
            paths.forEach { path ->
                checkNotInterrupted()
                val source = resolveSafe(work, path)
                if (logCodec == CaptureArchiveLogCodec.ZSTD && path == descriptor.log.path) {
                    writeZstdEntry(zip, work, path, source)
                } else {
                    // H.264/Opus video and PNG/JPEG/WebP screenshots are already compressed: DEFLATE only
                    // burns CPU for ~0% gain. Level is per entry and needs no CRC pre-pass (unlike STORED).
                    zip.setLevel(if (isAlreadyCompressedMedia(path)) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
                    zip.putArchiveEntry(
                        ZipArchiveEntry(path).apply {
                            method = ZipMethod.DEFLATED.code
                            time = 0
                        },
                    )
                    source.inputStream().use { input -> copyInterruptibly(input, zip) }
                    zip.closeArchiveEntry()
                }
            }
        }
    }

    /**
     * commons-compress cannot zstd-compress a streamed entry itself: for method 93 `putArchiveEntry`
     * would compute the entry CRC over the compressed bytes. So the log is compressed to a temp file
     * first (CRC and size of the uncompressed bytes collected on the way) and then copied in as a
     * raw, fully pre-described entry via `addRawArchiveEntry`.
     */
    private fun writeZstdEntry(zip: ZipArchiveOutputStream, work: File, path: String, source: File) {
        val compressed = File.createTempFile(".zstd-", ".tmp", work)
        try {
            val crc = CRC32()
            var uncompressedBytes = 0L
            ZstdOutputStream(BufferedOutputStream(compressed.outputStream()), COMPACT_LOG_ZSTD_LEVEL).use { zstd ->
                zstd.setWorkers(compactLogZstdWorkers(Runtime.getRuntime().availableProcessors()))
                source.inputStream().use { input ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        checkNotInterrupted()
                        val count = input.read(buffer)
                        if (count < 0) break
                        crc.update(buffer, 0, count)
                        uncompressedBytes += count
                        zstd.write(buffer, 0, count)
                    }
                }
            }
            val entry = ZipArchiveEntry(path).apply {
                method = ZipMethod.ZSTD.code
                size = uncompressedBytes
                compressedSize = compressed.length()
                this.crc = crc.value
                time = 0
            }
            compressed.inputStream().use { input -> zip.addRawArchiveEntry(entry, input) }
        } finally {
            compressed.delete()
        }
    }
}

/** `captured_with_indagium.txt` — a plain-text hint for anyone who opens the archive outside Indagium
 *  (e.g. a bug tracker's inline zip preview). Not a tracked/hashed asset (see [scanZipEntries] and
 *  [CaptureArchiveDescriptor.assets] — it's deliberately absent from both), just a fixed courtesy
 *  file every export writes and [scanZipEntries]/`openZip` allow through unreferenced. */
private fun writeCapturedWithIndagiumBlurb(work: File, compact: Boolean = false, zstdLog: Boolean = false) {
    val extension = if (compact) ".ind" else ".zip"
    val zstdNote = if (zstdLog) {
        "logcat.log is compressed with zstd (ZIP method 93), so some unzip tools cannot extract it; " +
            "open the archive in Indagium instead.\n"
    } else {
        ""
    }
    File(work, CAPTURED_WITH_INDAGIUM_NAME).writeText(
        "Captured with Indagium ${BuildInfo.APP_VERSION} — https://indagium.com\n" +
            "Open this $extension in Indagium to see the synced log, video and markers together.\n" +
            zstdNote,
        Charsets.UTF_8,
    )
}

private fun preflight(
    destination: File,
    reserveBytes: Long,
    logBytes: Long,
    videoBytes: Long,
    screenshotsBytes: Long,
    timelineBytes: Long,
    extraWorkingBytes: Long = 0L,
) {
    val payload = saturatingAdd(saturatingAdd(saturatingAdd(logBytes, videoBytes), screenshotsBytes), timelineBytes)
    val workingAndArchive = saturatingAdd(saturatingAdd(payload, payload), extraWorkingBytes)
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

private fun publishReplacing(temp: File, destination: File) {
    destination.parentFile?.mkdirs()
    try {
        Files.move(
            temp.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
            require(count <= limit) { "Capture asset exceeds its size limit: ${asset.path}" }
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
        }
    }
    // No exact-size cross-check any more — v3's own JSON carries no per-asset declared size (see
    // CaptureArchiveAsset's own doc), only a limit and a SHA-256, and the hash below is a strictly
    // stronger integrity guarantee than a size match ever was on its own.
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    require(actual == asset.sha256) { "Capture asset checksum mismatch: ${asset.path}" }
}

private fun assetLimit(descriptor: CaptureArchiveDescriptor, asset: CaptureArchiveAsset): Long = when (asset) {
    descriptor.log -> MAX_ARCHIVE_ENTRY_BYTES
    descriptor.mapping -> MAX_MAPPING_BYTES
    descriptor.timeline -> MAX_TIMELINE_BYTES
    descriptor.blocks -> MAX_MAPPING_BYTES
    descriptor.sparseSync -> MAX_TIMELINE_BYTES
    descriptor.video -> MAX_VIDEO_BYTES
    descriptor.notes -> MAX_NOTES_BYTES
    else -> MAX_SCREENSHOT_BYTES
}

private fun requireExtractionSpace(cacheDirectory: File, requiredBytes: Long) {
    require(cacheDirectory.usableSpace >= requiredBytes) {
        "Not enough free space to import capture (need $requiredBytes bytes, have ${cacheDirectory.usableSpace} bytes)"
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

internal const val COMPACT_ENCODING_STATUS = "Encoding AV1 video…"
private const val COMPACT_STATUS_MIN_INTERVAL_NANOS = 250_000_000L
private const val COMPACT_STATUS_PERCENT_SCALE = 100

/** Turns the transcoder's progress fractions into "Encoding AV1 video… 42%" status updates: only when the
 *  whole percent changes, and at most four times per second (the final 100% always goes through). A throwing
 *  status listener never fails the export. */
internal class CompactEncodingStatusReporter(
    private val onStatus: ((String) -> Unit)?,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var lastPercent = -1
    private var lastEmitNanos = 0L

    fun onProgress(fraction: Double) {
        if (onStatus == null || !fraction.isFinite()) return
        val percent = (fraction * COMPACT_STATUS_PERCENT_SCALE).toInt().coerceIn(0, COMPACT_STATUS_PERCENT_SCALE)
        if (percent == lastPercent) return
        val now = nanoTime()
        val complete = percent == COMPACT_STATUS_PERCENT_SCALE
        if (lastPercent >= 0 && !complete && now - lastEmitNanos < COMPACT_STATUS_MIN_INTERVAL_NANOS) return
        lastPercent = percent
        lastEmitNanos = now
        runCatching { onStatus?.invoke("$COMPACT_ENCODING_STATUS $percent%") }
    }
}
