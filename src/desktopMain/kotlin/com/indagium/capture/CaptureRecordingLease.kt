package com.indagium.capture

import com.indagium.debug.AppLogger
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * The key an in-process registry of file-lock owners uses for [file]: its canonical path, so an alias (a symlinked
 * save folder, macOS `/tmp` vs `/private/tmp`) can never miss the registry and open a second channel on a file this
 * JVM holds a POSIX lock on, which would drop that lock on close. Falls back to the absolute normalized path when the
 * filesystem cannot canonicalize it. Compute it the same way at add, check and remove.
 */
internal fun canonicalRegistryKey(file: File): String =
    runCatching { file.canonicalPath }.getOrElse { file.absoluteFile.normalize().path }

/**
 * An exclusive OS file lock on [LEASE_NAME] inside a capture session directory, held by the recorder for as long as
 * the session is recording.
 *
 * Why: on macOS the single-instance guard is skipped, so two Indagium JVMs (a dev build and the installed app) can
 * share one capture root. Every RECORDING session a recorder does not own is otherwise indistinguishable from one
 * a crash left behind, and `CaptureRecorder.recoverSessions` truncates its log and rewrites its block index. A
 * recovery therefore calls [tryTakeOver] first and leaves the session completely untouched when the lease is held.
 * The lock dies with its process, so a crashed recorder leaves no stale lease (the file stays, the lock does not).
 *
 * POSIX locks belong to the process, not the channel, and closing ANY channel on the file drops every lock this
 * JVM holds on it. So no code may open a lease file this JVM already holds a lease on: [held] is the in-process
 * registry that [tryTakeOver] and [create] consult before they open anything, which also keeps two recoveries
 * in one JVM (the service's helper recorder and the capture recorder) from probing the same file at once.
 */
internal class CaptureRecordingLease private constructor(
    private val key: String,
    private val channel: FileChannel,
) : AutoCloseable {
    override fun close() {
        // Closing the channel drops the OS lock; the registry entry goes last so nobody reopens a still-locked file.
        runCatching { channel.close() }
        held.remove(key)
    }

    /** What a recovery found when it asked for a session's lease. */
    sealed interface Attempt {
        /** The session has no lease file: it was written by a build that did not take leases (or by no recorder). */
        data object Absent : Attempt

        /** Another recorder, in this JVM or another process, holds the lease: the session is live. */
        data object Held : Attempt

        /** The lease exists but cannot be tested (no file locks, IO failure): liveness is unknown. */
        data object Unavailable : Attempt

        /** Nobody holds the lease; the caller now owns it and must close it when its recovery is done. */
        class Acquired(val lease: CaptureRecordingLease) : Attempt
    }

    companion object {
        const val LEASE_NAME = ".recording.lock"

        // Canonical keys of the leases this JVM holds or is probing right now.
        private val held = ConcurrentHashMap.newKeySet<String>()

        private fun keyOf(directory: File): String = canonicalRegistryKey(directory)

        private fun leaseFile(directory: File): File = File(directory, LEASE_NAME)

        /**
         * Creates and exclusively locks the lease of a session [directory] that is about to start recording. Null when
         * it is already held or cannot be created or locked (no file locks, read-only folder): the recording is never
         * blocked by that, it just goes unprotected like a session written by an older build.
         */
        fun create(directory: File): CaptureRecordingLease? {
            val key = keyOf(directory)
            if (!held.add(key)) return null
            var lease: CaptureRecordingLease? = null
            try {
                val channel = FileChannel.open(
                    leaseFile(directory).toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                )
                lease = lockedLeaseOrClose(key, channel)
            } catch (failure: IOException) {
                runCatching { AppLogger.warn("capture", "Capture recording lease unavailable: ${failure.message}") }
            } finally {
                if (lease == null) held.remove(key)
            }
            return lease
        }

        /**
         * Asks for the lease of a RECORDING session [directory] that this recorder does not own. Never waits and
         * never writes anything but the lock itself.
         */
        fun tryTakeOver(directory: File): Attempt {
            val key = keyOf(directory)
            if (!held.add(key)) return Attempt.Held
            var attempt: Attempt = Attempt.Unavailable
            try {
                attempt = probe(key, leaseFile(directory))
            } finally {
                // Only an Acquired lease keeps the registry entry (until it is closed).
                if (attempt !is Attempt.Acquired) held.remove(key)
            }
            return attempt
        }

        private fun probe(key: String, file: File): Attempt = try {
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)
            lockedLeaseOrClose(key, channel)?.let { Attempt.Acquired(it) } ?: Attempt.Held
        } catch (_: NoSuchFileException) {
            Attempt.Absent
        } catch (failure: IOException) {
            runCatching { AppLogger.warn("capture", "Capture recording lease could not be tested: ${failure.message}") }
            Attempt.Unavailable
        }

        /** True when this JVM holds (or is probing) the lease of [directory] (test hook). */
        internal fun isHeldByThisProcess(directory: File): Boolean = keyOf(directory) in held

        // tryLock returns null when another process holds the lock; the in-process case never reaches it (see [held]),
        // but an OverlappingFileLockException still means "held", never an error.
        private fun lockedLeaseOrClose(key: String, channel: FileChannel): CaptureRecordingLease? {
            val locked = try {
                channel.tryLock() != null
            } catch (_: OverlappingFileLockException) {
                false
            } catch (failure: IOException) {
                runCatching { channel.close() }
                throw failure
            }
            if (!locked) {
                runCatching { channel.close() }
                return null
            }
            return CaptureRecordingLease(key, channel)
        }
    }
}
