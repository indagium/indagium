package com.indagium.capture

import com.indagium.debug.AppLogger
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/** Counters the tests read to prove a preview was answered without rescanning or re-converting anything. */
internal object CapturePreviewDiagnostics {
    /** Previews computed from scratch (cache misses). */
    val computations = AtomicInteger()

    /** Raw-log blocks read by a sparse scan, preview and export alike. */
    val blockReads = AtomicInteger()

    /** Legacy per-row indexes converted into a temporary sparse index for a preview. */
    val legacyConversions = AtomicInteger()

    /** Test switch: false makes previews read every candidate block, the reference the envelope shortcut must match. */
    @Volatile var envelopeEnabled = true
}

/**
 * Everything [CaptureArchiveExporter.preview] reads, so an equal key always means an equal answer: the whole session
 * value and request (minus fields that do not affect the answer), plus the stat of every file the answer is derived
 * from (a stopped session's never change; a recording's grow, which is what invalidates a live entry), and the kind
 * of video exporter, whose coverage probe is part of the answer.
 */
internal data class CaptureExportPreviewKey(
    val session: CaptureSession,
    val request: CaptureExportRequest,
    val canonicalLog: String,
    val logStamp: CaptureFileStamp,
    val mappingStamp: CaptureFileStamp,
    val syncStamp: CaptureFileStamp,
    val videoStamp: CaptureFileStamp,
    val exporter: Class<*>,
) {
    companion object {
        private val IGNORED_DESTINATION = File("")

        fun of(session: CaptureSession, request: CaptureExportRequest, exporter: Any): CaptureExportPreviewKey =
            CaptureExportPreviewKey(
                session = session,
                // The destination only feeds destinationExists, which is re-evaluated on every call.
                request = request.copy(destination = IGNORED_DESTINATION, overwriteExisting = false, format = CaptureExportFormat.ZIP),
                canonicalLog = runCatching { session.logFile.canonicalPath }.getOrDefault(session.logFile.absolutePath),
                logStamp = CaptureFileStamp.of(session.logFile),
                mappingStamp = CaptureFileStamp.of(
                    if (session.storageVersion == CaptureStorageVersion.BLOCKS_V2) session.blockIndexFile else session.indexFile,
                ),
                syncStamp = CaptureFileStamp.of(session.syncFile),
                videoStamp = CaptureFileStamp.of(session.videoFile),
                exporter = exporter.javaClass,
            )
    }
}

internal data class CaptureFileStamp(val length: Long, val lastModifiedMs: Long) {
    companion object {
        fun of(file: File) = CaptureFileStamp(file.length(), file.lastModified())
    }
}

/**
 * Process-wide memo of recent previews. The popover re-asks every second and a stopped session's answer never
 * changes, yet each stopped-session preview builds a fresh exporter, so the memo cannot live on the instance.
 * Bounded and access-ordered; a recording session's key changes with every flush, so its entries simply age out.
 */
internal object CaptureExportPreviewCache {
    private const val MAX_ENTRIES = 8
    private const val INITIAL_CAPACITY = 16
    private const val LOAD_FACTOR = 0.75f

    private val entries = object : LinkedHashMap<CaptureExportPreviewKey, CaptureExportPreview>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CaptureExportPreviewKey, CaptureExportPreview>): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(key: CaptureExportPreviewKey): CaptureExportPreview? = entries[key]

    @Synchronized
    fun put(key: CaptureExportPreviewKey, value: CaptureExportPreview) {
        entries[key] = value
    }

    @Synchronized
    fun clear() = entries.clear()
}

/**
 * Directories of legacy sparse adapters that an export in this process is still using. They live next to the export
 * destination and are deleted when it ends; [sweepStaleLegacySparseSources] must never take one from a running export.
 */
private val activeLegacyAdapterDirectories: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

internal fun registerLegacyAdapterDirectory(directory: File) {
    activeLegacyAdapterDirectories += directory.absolutePath
}

internal fun releaseLegacyAdapterDirectory(directory: File) {
    activeLegacyAdapterDirectories -= directory.absolutePath
    directory.deleteRecursively()
}

internal const val LEGACY_ADAPTER_DIRECTORY_PREFIX = ".sparse-source-"
internal const val DEFAULT_STALE_ADAPTER_AGE_MS = 60L * 60 * 1000

/**
 * Removes `.sparse-source-*` directories that earlier builds (every preview) or a crashed export left directly in a
 * capture [root], once nothing in them has changed for [graceMs]. Only direct children of [root] are examined, so
 * no session directory is ever entered, and an adapter an export of this process is using is always kept.
 * Returns the number removed.
 */
internal fun sweepStaleLegacySparseSources(
    root: File,
    nowMs: Long = System.currentTimeMillis(),
    graceMs: Long = DEFAULT_STALE_ADAPTER_AGE_MS,
): Int = root.listFiles().orEmpty().count { directory ->
    directory.isDirectory && !Files.isSymbolicLink(directory.toPath()) &&
        directory.name.startsWith(LEGACY_ADAPTER_DIRECTORY_PREFIX) &&
        directory.absolutePath !in activeLegacyAdapterDirectories &&
        nowMs - newestModification(directory) >= graceMs &&
        run { directory.deleteRecursively(); !directory.exists() }
}

private fun newestModification(directory: File): Long =
    runCatching { directory.walkTopDown().maxOf(File::lastModified) }.getOrDefault(directory.lastModified())

/**
 * Preview-only cache of legacy sessions converted into a temporary sparse index. A stopped legacy session previews
 * every second while its popover is open, and converting costs one seek and read per row, so the conversion is kept
 * (keyed by everything it is derived from) in a directory under the system temp folder, never in the capture root.
 * An entry in use by a running preview is deleted only after that preview releases it.
 */
internal object LegacySparseAdapterCache {
    private const val MAX_ENTRIES = 4
    private const val INITIAL_CAPACITY = 8
    private const val LOAD_FACTOR = 0.75f
    private val lock = ReentrantLock()

    private data class Key(
        val logPath: String,
        val logStamp: CaptureFileStamp,
        val indexPath: String,
        val indexStamp: CaptureFileStamp,
        val frozenLogBytes: Long?,
        val frozenIndexBytes: Long?,
        val startedEpochMs: Long,
        val elapsedMs: Long,
        val checkpointMs: Long,
        val cursorByteOffset: Long,
        val cursorRowOrdinal: Int,
    )

    internal class Entry(val adapter: LegacySparseAdapter) {
        var users = 0
        var evicted = false
    }

    private val entries = LinkedHashMap<Key, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var baseDirectory: File? = null

    /** A converted adapter held for the duration of one preview; closing it lets an evicted adapter be deleted. */
    class Lease internal constructor(val adapter: LegacySparseAdapter, private val entry: Entry) : AutoCloseable {
        override fun close() = release(entry)
    }

    @Throws(InterruptedException::class)
    fun acquire(session: CaptureSession): Lease {
        lock.lockInterruptibly()
        try {
            val key = keyOf(session)
            // The OS temp cleaner (or another instance's stale sweep) may have removed a cached conversion: rebuild it.
            val cached = entries[key]?.takeIf { it.adapter.session.blockIndexFile.isFile } ?: run {
                entries.remove(key)?.let { gone ->
                    gone.evicted = true
                    if (gone.users <= 0) releaseLegacyAdapterDirectory(gone.adapter.directory)
                }
                null
            }
            val entry = if (cached != null) {
                cached
            } else {
                CapturePreviewDiagnostics.legacyConversions.incrementAndGet()
                Entry(buildLegacySparseAdapter(session, base())).also { created ->
                    entries[key] = created
                    evictOverflow()
                }
            }
            entry.users++
            return Lease(entry.adapter, entry)
        } finally {
            lock.unlock()
        }
    }

    private fun release(entry: Entry) {
        lock.lock()
        try {
            entry.users--
            if (entry.evicted && entry.users <= 0) releaseLegacyAdapterDirectory(entry.adapter.directory)
        } finally {
            lock.unlock()
        }
    }

    private fun evictOverflow() {
        val iterator = entries.entries.iterator()
        while (entries.size > MAX_ENTRIES && iterator.hasNext()) {
            val eldest = iterator.next().value
            iterator.remove()
            eldest.evicted = true
            if (eldest.users <= 0) releaseLegacyAdapterDirectory(eldest.adapter.directory)
        }
    }

    /** Drops every cached adapter that no preview is using (tests; the JVM exit hook). */
    fun clear() {
        lock.lock()
        try {
            entries.values.forEach { entry ->
                entry.evicted = true
                if (entry.users <= 0) releaseLegacyAdapterDirectory(entry.adapter.directory)
            }
            entries.clear()
        } finally {
            lock.unlock()
        }
    }

    /** The directory conversions are built in, outside every capture root; stale leftovers of a crash are swept once. */
    private fun base(): File = baseDirectory ?: File(System.getProperty("java.io.tmpdir"), "indagium-legacy-sparse").also { directory ->
        directory.mkdirs()
        sweepStale(directory)
        Runtime.getRuntime().addShutdownHook(Thread({ clear() }, "legacy-sparse-adapter-cleanup"))
        baseDirectory = directory
    }

    private fun sweepStale(directory: File) {
        val removed = runCatching { sweepStaleLegacySparseSources(directory) }.getOrDefault(0)
        if (removed > 0) runCatching { AppLogger.info("capture", "Removed $removed stale legacy preview conversions") }
    }

    private fun keyOf(session: CaptureSession) = Key(
        logPath = runCatching { session.logFile.canonicalPath }.getOrDefault(session.logFile.absolutePath),
        logStamp = CaptureFileStamp.of(session.logFile),
        indexPath = runCatching { session.indexFile.canonicalPath }.getOrDefault(session.indexFile.absolutePath),
        indexStamp = CaptureFileStamp.of(session.indexFile),
        frozenLogBytes = session.frozenLogBytes,
        frozenIndexBytes = session.frozenBlockIndexBytes,
        startedEpochMs = session.startedEpochMs,
        elapsedMs = session.elapsedMs,
        checkpointMs = session.effectiveSnapshotCheckpointMs,
        cursorByteOffset = session.logCheckpointByteOffset,
        cursorRowOrdinal = session.logCheckpointRowOrdinal,
    )
}

/**
 * Whether [block] can stand for all of its rows in a preview: it holds rows, they all belong to one reliable segment
 * with no manual correction (so every row resolves through the same anchor), and its whole recorded time envelope
 * lies inside the requested window, so every row is selected.
 */
internal fun sparseBlockUsesEnvelope(
    metadata: CaptureSyncMetadata,
    block: CaptureBlock,
    selectedStartMs: Long,
    selectedEndMs: Long,
    excludeStart: Boolean,
): Boolean {
    if (block.lastRowExclusive <= block.firstRow) return false
    val segment = metadata.segmentContaining(block.firstRow) ?: return false
    if (segment.reliability != CaptureSyncReliability.RELIABLE || segment.manualAnchor != null) return false
    if (!segment.contains(block.lastRowExclusive - 1)) return false
    val startsInside = selectedStartMs == Long.MIN_VALUE ||
        if (excludeStart) block.firstElapsedMs > selectedStartMs else block.firstElapsedMs >= selectedStartMs
    return startsInside && block.lastElapsedMs <= selectedEndMs
}
