package com.indagium.capture

import com.indagium.debug.AppLogger
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
 *
 * Cross-process lease: on macOS the single-instance guard is skipped, so two Indagium JVMs (a dev
 * build and the installed app) can share one cache. A directory this process uses therefore also
 * holds a shared OS file lock on [LEASE_NAME] inside it ([markInUse]); [sweep] takes the lock
 * exclusively with `tryLock` (never waiting) before deleting and skips every directory it cannot get,
 * so one instance's sweep cannot delete what another instance's open tab still reads. The lock dies
 * with its process, so a crashed instance leaves no stale lease. A directory with no lease file (made
 * by an older build, or where locking is unsupported) falls back to the in-use set and the grace period.
 *
 * Ownership: every consumer of a directory ([CaptureArchiveReader.open] hands one out inside
 * [ImportedCapture.extractionUse]) holds a [CaptureExtractionUse]; the lease and the in-use entry live exactly as
 * long as at least one use is open, so a closed tab no longer pins its directory (or a file descriptor) until exit.
 * Replacing a damaged directory needs EXCLUSIVE ownership ([clearForReplacement]): a reader in another process
 * keeps its files, and the caller extracts into a separate directory instead.
 *
 * Every registry here is keyed by canonical path ([canonicalRegistryKey]) so a path alias cannot split one
 * directory into two entries, which would also let a second channel on one lease file drop the first one's lock.
 */
internal object CaptureExtractionCache {
    const val MARKER_NAME = ".indagium-extracted"
    const val LEASE_NAME = ".indagium-lease"
    private const val MARKER_MAGIC = "indagium-capture-extract-v1"
    private const val TEMP_PREFIX = "capture-tmp-"
    private const val KEY_HEX_CHARS = 32
    private val keyedName = Regex("capture-[0-9a-f]{$KEY_HEX_CHARS}")
    private val legacyName = Regex("capture-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    const val DEFAULT_MAX_UNUSED_MS = 14L * 24 * 60 * 60 * 1000
    const val DEFAULT_BUDGET_BYTES = 5L * 1024 * 1024 * 1024
    const val DEFAULT_GRACE_MS = 60L * 60 * 1000
    const val DEFAULT_LEASE_WAIT_MS = 5_000L
    private const val LEASE_POLL_BASE_MS = 50L
    private const val LEASE_POLL_MAX_SHIFT = 3

    /** Test hook: called with the final directory each time a real extraction (not a reuse) starts. */
    @Volatile var extractionObserver: ((File) -> Unit)? = null

    /**
     * How long an open waits for another process's sweep to give up a directory's exclusive lease before
     * failing (test hook). Only IO threads wait; every caller of [CaptureArchiveReader.open] is off the UI thread.
     */
    @Volatile var leaseWaitMs: Long = DEFAULT_LEASE_WAIT_MS

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
        val lock = locks.computeIfAbsent(keyOf(directory)) { ReentrantLock() }
        lock.lockInterruptibly()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private fun keyOf(directory: File): String = canonicalRegistryKey(directory)

    // Shared lease locks (see the class KDoc), keyed like [inUse] and guarded by their own monitor, which also guards
    // [uses]. A lease channel lives while its directory has an open [CaptureExtractionUse] (or until the directory is
    // deleted by this process, [releaseLeases] at AppState.close, or process exit, when the OS drops the lock).
    // Closing a channel drops every lock this process holds on that file (POSIX), so nothing outside this object may
    // open a lease file of a directory it holds a lease on.
    private val leases = HashMap<String, FileChannel>()

    // The open uses of each directory (identity sets); the last one to close releases the lease and the in-use entry.
    private val uses = HashMap<String, MutableSet<CaptureExtractionUse>>()

    /**
     * Marks [directory] as used by this process, takes its cross-process lease and returns the use that keeps both
     * alive until it is closed. Null only when another process holds the lease exclusively, i.e. its sweep is
     * deleting the directory right now: the caller must not use it. Never waits, and fails open (a use) when the
     * lease file cannot be created or the filesystem has no file locks, which leaves the in-use set and the sweep
     * grace period as protection.
     */
    fun acquireUse(directory: File): CaptureExtractionUse? = synchronized(leases) {
        val key = keyOf(directory)
        if (!acquireLease(directory, key)) return null
        val use = CaptureExtractionUse(key)
        uses.getOrPut(key) { java.util.Collections.newSetFromMap(java.util.IdentityHashMap()) } += use
        inUse += key
        use
    }

    /** Test/legacy form of [acquireUse] whose use is never closed (only [deleteDirectory] or [releaseLeases] end it). */
    fun markInUse(directory: File): Boolean = acquireUse(directory) != null

    /** A second, independent use of the same directory as the still-open [use], or null once [use] was closed. */
    internal fun retain(use: CaptureExtractionUse): CaptureExtractionUse? = synchronized(leases) {
        val open = uses[use.key] ?: return null
        if (use !in open) return null
        CaptureExtractionUse(use.key).also { open += it }
    }

    /** Ends [use]; the lease and the in-use entry go with the directory's last open use. Idempotent. */
    internal fun release(use: CaptureExtractionUse) {
        // The close stays under the monitor: a lease re-taken on the same file between leaving it and closing would
        // have its POSIX lock dropped by this channel's close (closing any channel drops all of the process's locks).
        synchronized(leases) {
            val open = uses[use.key] ?: return
            if (!open.remove(use) || open.isNotEmpty()) return
            uses.remove(use.key)
            inUse -= use.key
            runCatching { leases.remove(use.key)?.close() }
        }
    }

    /** What [clearForReplacement] decided. */
    enum class Replacement {
        /** The directory is gone (this process owned it exclusively): extract into it. */
        CLEARED,

        /** Another process holds the lease exclusively, i.e. its sweep is deleting it right now: wait and look again. */
        BUSY,

        /** Another process still reads the directory (a shared lease): nothing was touched, extract elsewhere. */
        HELD_BY_OTHERS,
    }

    /**
     * Clears [directory] (absent, stale or damaged) so a fresh extraction can take its place, but only with
     * EXCLUSIVE ownership: a shared lease does not exclude another process's readers, so deleting under it could
     * remove files an open tab in that process still reads. This process's own shared lease is dropped first (a
     * same-JVM upgrade is impossible, and a second channel's close would drop the first's lock anyway), then the
     * lease is taken exclusively without waiting, exactly like the sweep. When that fails nothing is deleted:
     * [Replacement.BUSY] when the holder is exclusive (a sweep), [Replacement.HELD_BY_OTHERS] when it only reads, in
     * which case this process's own lease is restored if any of its uses remain.
     */
    fun clearForReplacement(directory: File): Replacement {
        if (!directory.exists()) {
            deleteDirectory(directory)
            return Replacement.CLEARED
        }
        val key = keyOf(directory)
        releaseLease(directory)
        val lease = acquireForDeletion(directory)
        if (lease != null) {
            deleteLeased(directory, lease)
            forget(key)
            return Replacement.CLEARED
        }
        val shared = acquireLease(directory, key)
        if (shared && synchronized(leases) { uses[key].isNullOrEmpty() }) releaseLease(directory)
        return if (shared) Replacement.HELD_BY_OTHERS else Replacement.BUSY
    }

    /** A directory name no key maps to, for an extraction that must not reuse or replace [directoryFor]'s. */
    fun separateDirectoryFor(cacheDirectory: File): File = File(cacheDirectory, "capture-${java.util.UUID.randomUUID()}")

    /** Sleeps one growing backoff step (50, 100, 200, 400 ms, ...) of [attempt]; false once [deadlineNanos] has passed. */
    fun pauseBeforeRetry(attempt: Int, deadlineNanos: Long): Boolean {
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
        if (remainingMs <= 0) return false
        Thread.sleep(minOf(remainingMs, LEASE_POLL_BASE_MS shl minOf(attempt, LEASE_POLL_MAX_SHIFT)))
        return true
    }

    private fun acquireLease(directory: File, key: String): Boolean = synchronized(leases) {
        if (key in leases) return true
        try {
            // Read access is what a shared lock requires, write what the sweep's exclusive one does.
            val channel = FileChannel.open(
                File(directory, LEASE_NAME).toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock(0L, Long.MAX_VALUE, true)
            } catch (_: OverlappingFileLockException) {
                null
            } catch (failure: IOException) {
                channel.close()
                throw failure
            }
            if (lock == null) {
                channel.close()
                return false
            }
            leases[key] = channel
        } catch (failure: IOException) {
            runCatching { AppLogger.warn("capture", "Capture extraction lease unavailable: ${failure.message}") }
        }
        true
    }

    /** Drops this process's lease channel on [directory], if any, so the directory (lease file included) can be deleted. */
    private fun releaseLease(directory: File) {
        // Closed under the monitor for the same reason as in [release].
        synchronized(leases) { runCatching { leases.remove(keyOf(directory))?.close() } }
    }

    /** Forgets every use and in-use entry of [key]; the uses' later close is a no-op. */
    private fun forget(key: String) {
        synchronized(leases) {
            uses.remove(key)
            inUse -= key
        }
    }

    /**
     * Releases every lease channel this process holds (the in-use set and the open uses are kept); `AppState.close`
     * calls it. A use closed afterwards has no channel left to close.
     */
    fun releaseLeases() {
        synchronized(leases) {
            leases.values.forEach { runCatching { it.close() } }
            leases.clear()
        }
    }

    /** True when this process holds a lease on [directory] (test hook). */
    internal fun holdsLease(directory: File): Boolean = synchronized(leases) { keyOf(directory) in leases }

    /** How many uses of [directory] are open in this process (test hook). */
    internal fun openUseCount(directory: File): Int = synchronized(leases) { uses[keyOf(directory)]?.size ?: 0 }

    fun isInUse(directory: File): Boolean = keyOf(directory) in inUse

    /** An extraction in progress is "in use" too, so a concurrent [sweep] cannot delete it mid-write. */
    fun beginTemporary(directory: File) {
        inUse += keyOf(directory)
    }

    fun endTemporary(directory: File) {
        inUse -= keyOf(directory)
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
        // A deleted directory is no longer in use, and on Windows an open lease file would block its removal.
        forget(keyOf(directory))
        releaseLease(directory)
        if (!directory.exists()) return
        runCatching { directory.walkBottomUp().forEach { it.delete() } }
    }

    /** An exclusive lease on a directory a sweep is about to delete; [channel] is null when it has no lease file. */
    private class DeletionLease(val channel: FileChannel?) {
        fun close() {
            runCatching { channel?.close() }
        }
    }

    /**
     * Takes [directory]'s lease exclusively without waiting. Null when another process (or this JVM,
     * which [OverlappingFileLockException] reports) still holds it, so the sweep must leave the directory
     * alone. A missing lease file, or any other IO failure, means no holder can be detected and the in-use
     * set and grace period remain the protection. Only call for a directory this process does not use.
     */
    private fun acquireForDeletion(directory: File): DeletionLease? {
        if (synchronized(leases) { keyOf(directory) in leases }) return null
        val leaseFile = File(directory, LEASE_NAME)
        if (!leaseFile.isFile) return DeletionLease(null)
        val channel = try {
            FileChannel.open(leaseFile.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)
        } catch (_: IOException) {
            return DeletionLease(null)
        }
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: IOException) {
            runCatching { channel.close() }
            return DeletionLease(null)
        }
        if (lock == null) {
            runCatching { channel.close() }
            return null
        }
        return DeletionLease(channel)
    }

    /**
     * Deletes the contents, lease file last, while the exclusive lease is still held (another instance
     * cannot start using the directory halfway through), then closes the lease and removes the directory
     * only if it is empty: once the lease is gone another instance may already have published a fresh
     * extraction under the same name, and a recursive delete here would remove it.
     */
    private fun deleteLeased(directory: File, lease: DeletionLease) {
        val leaseFile = File(directory, LEASE_NAME)
        runCatching { directory.walkBottomUp().filter { it != leaseFile && it != directory }.forEach { it.delete() } }
        // Deleting an open file is allowed on POSIX and, because Java opens it with FILE_SHARE_DELETE, on
        // Windows too (there the name goes away when the channel closes).
        runCatching { leaseFile.delete() }
        lease.close()
        runCatching { directory.delete() }
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
     * progress, was active within [graceMs] (another Indagium instance on macOS may be using it), or
     * whose cross-process lease another instance still holds (see the class KDoc).
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
            val lock = locks.computeIfAbsent(keyOf(dir)) { ReentrantLock() }
            if (!lock.tryLock()) return false
            try {
                if (!stillEligible()) return false
                val size = directorySize(dir)
                // Eligibility (including "not in use by this process") is settled before the lease is probed.
                val lease = acquireForDeletion(dir) ?: return false
                deleteLeased(dir, lease)
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

/**
 * One consumer's claim on an extraction directory ([CaptureExtractionCache] keeps its lease and in-use entry alive
 * while any claim is open). Close it when the consumer (a tab, its imported snapshot, a transient probe) is done;
 * closing is idempotent. A directory that was deleted or replaced meanwhile makes the close a no-op.
 */
class CaptureExtractionUse internal constructor(internal val key: String) : AutoCloseable {
    /** An independent claim on the same directory (for work that may outlive this one), or null if already closed. */
    fun retain(): CaptureExtractionUse? = CaptureExtractionCache.retain(this)

    override fun close() = CaptureExtractionCache.release(this)
}
