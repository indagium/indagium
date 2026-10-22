package com.indagium.capture

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.name

/**
 * Where [CaptureArchiveReader] keeps the contents of a capture `.zip`/`.ind` it has extracted, and
 * how a later open (every restart, every restored tab) finds them again instead of extracting and
 * SHA-256-hashing the whole archive, video included, a second time.
 *
 * Layout under the cache directory (`archive-cache/captures`):
 *  - `capture-<32 hex>`      a finished extraction, named by [keyFor] (canonical path + size + mtime);
 *                            holds the assets plus [MARKER_NAME].
 *  - `capture-tmp-<key>-<uuid>`  an extraction in progress, atomically renamed to the name above.
 *  - `capture-<uuid>`        the pre-reuse naming (one new directory per open, never deleted); none of
 *                            these can be reused, so [sweep] just removes them.
 *
 * Integrity trade-off: the first extraction of an archive still verifies every asset's SHA-256 while
 * streaming it out, exactly as before. A reuse does NOT re-hash. It instead trusts a directory only if
 * its marker names the same key, lists exactly the assets the archive's own descriptor lists (same
 * path and checksum), and every one of those files still exists with the byte length it had when it
 * was verified. A deleted or truncated asset therefore falls back to a full re-extraction; a same-size
 * in-place bit flip on the local disk is not detected, which is the price of not re-reading ~200 MB of
 * video per restored tab on every launch.
 */
internal object CaptureExtractionCache {
    const val MARKER_NAME = ".indagium-extracted"
    private const val MARKER_MAGIC = "indagium-capture-extract-v1"
    private const val TEMP_PREFIX = "capture-tmp-"
    private const val KEY_HEX_CHARS = 32
    private val keyedName = Regex("capture-[0-9a-f]{$KEY_HEX_CHARS}")
    private val legacyName = Regex("capture-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    const val DEFAULT_MAX_UNUSED_MS = 14L * 24 * 60 * 60 * 1000
    const val DEFAULT_BUDGET_BYTES = 5L * 1024 * 1024 * 1024
    const val DEFAULT_GRACE_MS = 60L * 60 * 1000

    /** Test hook: called with the final directory each time a real extraction (not a reuse) starts. */
    @Volatile var extractionObserver: ((File) -> Unit)? = null

    // One lock per final directory path: concurrent opens of the same archive (two restored tabs on
    // one file) serialize and the second reuses the first's result. Only IO threads take these
    // (every caller of CaptureArchiveReader.open is off the UI thread); nothing here waits on the EDT.
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    // Directories this process has opened or is extracting. A tab (or its imported snapshot session)
    // keeps reading from them for as long as it is open, so [sweep] must never delete them.
    private val inUse: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun keyFor(source: File): String {
        val canonical = runCatching { source.canonicalPath }.getOrDefault(source.absolutePath)
        val identity = "$canonical|${source.length()}|${source.lastModified()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(KEY_HEX_CHARS)
    }

    fun directoryFor(cacheDirectory: File, key: String): File = File(cacheDirectory, "capture-$key")

    fun temporaryDirectoryFor(cacheDirectory: File, key: String): File =
        File(cacheDirectory, "$TEMP_PREFIX$key-${java.util.UUID.randomUUID()}")

    fun <T> withLock(directory: File, block: () -> T): T {
        val lock = locks.computeIfAbsent(directory.absolutePath) { ReentrantLock() }
        lock.lockInterruptibly()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    fun markInUse(directory: File) {
        inUse += directory.absolutePath
    }

    fun isInUse(directory: File): Boolean = directory.absolutePath in inUse

    /** An extraction in progress is "in use" too, so a concurrent [sweep] cannot delete it mid-write. */
    fun beginTemporary(directory: File) {
        inUse += directory.absolutePath
    }

    fun endTemporary(directory: File) {
        inUse -= directory.absolutePath
    }

    /** Writes the marker that makes [directory] reusable; call after every asset has been extracted and verified. */
    fun writeMarker(directory: File, key: String, assets: List<CaptureArchiveAsset>) {
        val text = buildString {
            appendLine(MARKER_MAGIC)
            appendLine("key=$key")
            assets.forEach { asset ->
                val length = File(directory, asset.path).length()
                appendLine("asset\t$length\t${asset.sha256}\t${asset.path}")
            }
        }
        File(directory, MARKER_NAME).writeText(text, Charsets.UTF_8)
    }

    private class Marker(val key: String, val assets: Map<String, Pair<Long, String>>)

    private fun readMarker(directory: File): Marker? = runCatching {
        val file = File(directory, MARKER_NAME)
        if (!file.isFile) return null
        val lines = file.readLines(Charsets.UTF_8)
        if (lines.firstOrNull() != MARKER_MAGIC) return null
        val keyLine = lines.getOrNull(1) ?: return null
        if (!keyLine.startsWith("key=")) return null
        val key = keyLine.removePrefix("key=")
        val assets = HashMap<String, Pair<Long, String>>()
        lines.drop(2).forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split('\t', limit = 4)
            if (parts.size != 4 || parts[0] != "asset") return null
            assets[parts[3]] = (parts[1].toLongOrNull() ?: return null) to parts[2]
        }
        Marker(key, assets)
    }.getOrNull()

    /**
     * True when [directory] is a complete extraction of exactly this archive state: marker key matches,
     * the archive holds precisely the descriptor's assets ([archiveEntryNames], descriptor/blurb
     * excluded), the marker recorded the same checksums, and every asset file is still present at the
     * recorded length. Anything else means "extract again".
     */
    fun isReusable(
        directory: File,
        key: String,
        assets: List<CaptureArchiveAsset>,
        archiveEntryNames: Set<String>,
    ): Boolean {
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return false
        val marker = readMarker(directory) ?: return false
        if (marker.key != key) return false
        if (archiveEntryNames != assets.mapTo(HashSet()) { it.path }) return false
        if (marker.assets.keys != archiveEntryNames) return false
        return assets.all { asset ->
            val recorded = marker.assets[asset.path] ?: return@all false
            val file = File(directory, asset.path)
            recorded.second == asset.sha256 && file.isFile && !Files.isSymbolicLink(file.toPath()) &&
                file.length() == recorded.first
        }
    }

    /** Records a use, so [sweep]'s 14-day and least-recently-used rules see this directory as fresh. */
    fun touch(directory: File, nowMs: Long = System.currentTimeMillis()) {
        runCatching { File(directory, MARKER_NAME).setLastModified(nowMs) }
    }

    fun deleteDirectory(directory: File) {
        if (!directory.exists()) return
        runCatching { directory.walkBottomUp().forEach { it.delete() } }
    }

    data class SweepResult(
        val deletedLegacy: Int = 0,
        val deletedTemporary: Int = 0,
        val deletedExpired: Int = 0,
        val deletedForBudget: Int = 0,
        val bytesFreed: Long = 0L,
        val remainingBytes: Long = 0L,
    ) {
        val deletedTotal: Int get() = deletedLegacy + deletedTemporary + deletedExpired + deletedForBudget
    }

    /**
     * Housekeeping for the extraction cache; never touches a directory that is in use (this process
     * opened it, or [isReferenced] says persisted/open state points into it), is held by an open in
     * progress, or was active within [graceMs] (another Indagium instance on macOS may be using it).
     *  - legacy `capture-<uuid>` and abandoned `capture-tmp-*` directories are removed;
     *  - keyed directories unused for [maxUnusedMs] are removed;
     *  - if what remains exceeds [budgetBytes], least recently used go first until it fits.
     * Each deletion rechecks its conditions under the directory's own lock, so a concurrent open either
     * reuses the directory before the sweep takes the lock or finds it gone and re-extracts.
     */
    fun sweep(
        cacheDirectory: File,
        nowMs: Long = System.currentTimeMillis(),
        maxUnusedMs: Long = DEFAULT_MAX_UNUSED_MS,
        budgetBytes: Long = DEFAULT_BUDGET_BYTES,
        graceMs: Long = DEFAULT_GRACE_MS,
        isReferenced: (File) -> Boolean = { false },
    ): SweepResult {
        val children = cacheDirectory.listFiles().orEmpty()
            .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
        val run = SweepRun(nowMs, maxUnusedMs, graceMs, isReferenced)
        children.filter { legacyName.matches(it.name) }.forEach { run.removeJunk(it, Removal.LEGACY) }
        children.filter { it.name.startsWith(TEMP_PREFIX) }.forEach { run.removeJunk(it, Removal.TEMPORARY) }
        val live = children.filter { keyedName.matches(it.name) }.mapNotNull(run::examineKeyed)
        run.enforceBudget(live, budgetBytes)
        return run.result.copy(remainingBytes = run.remainingBytes)
    }

    private enum class Removal { LEGACY, TEMPORARY, EXPIRED, BUDGET }

    private class LiveDirectory(val directory: File, val lastUsedMs: Long)

    private class SweepRun(
        val nowMs: Long,
        val maxUnusedMs: Long,
        val graceMs: Long,
        val isReferenced: (File) -> Boolean,
    ) {
        var result = SweepResult()
        var remainingBytes = 0L

        private fun isProtected(dir: File, lastUsedMs: Long): Boolean =
            isInUse(dir) || isReferenced(dir) || nowMs - lastUsedMs < graceMs

        /** Deletes [dir] under its own lock when [stillEligible] holds; false if it was skipped. */
        private fun deleteIfIdle(dir: File, removal: Removal, stillEligible: () -> Boolean): Boolean {
            val lock = locks.computeIfAbsent(dir.absolutePath) { ReentrantLock() }
            if (!lock.tryLock()) return false
            try {
                if (!stillEligible()) return false
                val size = directorySize(dir)
                deleteDirectory(dir)
                if (dir.exists()) return false
                result = result.record(removal, size)
                return true
            } finally {
                lock.unlock()
            }
        }

        fun removeJunk(dir: File, removal: Removal) {
            deleteIfIdle(dir, removal) { !isProtected(dir, lastActivity(dir)) }
        }

        /** Returns the directory when it stays in the cache (so the budget pass counts it). */
        fun examineKeyed(dir: File): LiveDirectory? {
            val marker = File(dir, MARKER_NAME)
            if (readMarker(dir) == null) {
                // An incomplete or damaged keyed directory can never be reused; treat it as junk.
                removeJunk(dir, Removal.TEMPORARY)
                return dir.takeIf { it.exists() }?.let { LiveDirectory(it, lastActivity(it)) }
            }
            val lastUsed = marker.lastModified()
            if (nowMs - lastUsed > maxUnusedMs) {
                deleteIfIdle(dir, Removal.EXPIRED) {
                    !isProtected(dir, marker.lastModified()) && nowMs - marker.lastModified() > maxUnusedMs
                }
            }
            return dir.takeIf { it.exists() }?.let { LiveDirectory(it, lastUsed) }
        }

        fun enforceBudget(live: List<LiveDirectory>, budgetBytes: Long) {
            var total = live.sumOf { directorySize(it.directory) }
            for (entry in live.sortedBy { it.lastUsedMs }) {
                if (total <= budgetBytes) break
                val size = directorySize(entry.directory)
                val removed = deleteIfIdle(entry.directory, Removal.BUDGET) { !isProtected(entry.directory, entry.lastUsedMs) }
                if (removed) total -= size
            }
            remainingBytes = total
        }
    }

    private fun SweepResult.record(removal: Removal, size: Long): SweepResult = when (removal) {
        Removal.LEGACY -> copy(deletedLegacy = deletedLegacy + 1, bytesFreed = bytesFreed + size)
        Removal.TEMPORARY -> copy(deletedTemporary = deletedTemporary + 1, bytesFreed = bytesFreed + size)
        Removal.EXPIRED -> copy(deletedExpired = deletedExpired + 1, bytesFreed = bytesFreed + size)
        Removal.BUDGET -> copy(deletedForBudget = deletedForBudget + 1, bytesFreed = bytesFreed + size)
    }

    /**
     * Removes `capture-log-restore-<uuid>` extractions left in [cacheDirectory] (the verified-log
     * fallback deletes its own once parsed, so only a crash leaves one). Returns how many were removed.
     */
    fun sweepRestoreLogLeftovers(
        cacheDirectory: File,
        nowMs: Long = System.currentTimeMillis(),
        graceMs: Long = DEFAULT_GRACE_MS,
    ): Int = cacheDirectory.listFiles().orEmpty().count { dir ->
        dir.isDirectory && !Files.isSymbolicLink(dir.toPath()) && nowMs - lastActivity(dir) >= graceMs &&
            run { deleteDirectory(dir); !dir.exists() }
    }

    /** Newest modification time among [dir] and its direct children: an extraction in progress keeps moving. */
    private fun lastActivity(dir: File): Long =
        maxOf(dir.lastModified(), dir.listFiles().orEmpty().maxOfOrNull { it.lastModified() } ?: 0L)

    fun directorySize(dir: File): Long =
        runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

    /** Name of the cache directory a path under [cacheDirectory] belongs to, or null if it is outside. */
    fun owningDirectory(cacheDirectory: File, path: File): File? {
        val root = cacheDirectory.toPath().toAbsolutePath().normalize()
        val target = path.toPath().toAbsolutePath().normalize()
        if (!target.startsWith(root) || target == root) return null
        return File(cacheDirectory, root.relativize(target).first().name)
    }
}
