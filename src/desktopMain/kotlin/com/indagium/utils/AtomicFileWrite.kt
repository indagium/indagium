package com.indagium.utils

import java.io.File
import java.io.Writer
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

// Shared by every export/persistence write that must never leave a partial/corrupt file at
// [destination] on failure or cancellation — streaming writers (ExportFilteredLog.kt) and,
// eventually, autosave persistence both need this exact temp-file-then-replace shape rather than
// each hand-rolling it. Writes go to a temp file in destination's own directory (so the final move
// stays on one filesystem, a prerequisite for ATOMIC_MOVE), then replace destination only once
// [write] returns successfully. Whatever [write] or the move throws propagates unchanged (no catch
// here); `finally` only deletes the temp file when the move never happened, leaving destination
// exactly as it was.
fun writeFileAtomically(destination: File, write: (Writer) -> Unit) {
    destination.parentFile?.mkdirs()
    val tmp = File(destination.parentFile, ".${destination.name}.tmp-${System.nanoTime()}")
    var moved = false
    try {
        tmp.bufferedWriter().use { writer -> write(writer) }
        moveAtomicallyIfPossible(tmp, destination)
        moved = true
    } finally {
        if (!moved) tmp.delete()
    }
}

// Falls back to a plain (non-atomic) replace if the filesystem doesn't support ATOMIC_MOVE for
// this pair of paths (e.g. certain network/cross-volume mounts) — still correct (the temp file is
// fully written before this runs), just not crash-safe against a failure during the move itself.
private fun moveAtomicallyIfPossible(tmp: File, destination: File) {
    moveReplacingAtomically(tmp.toPath(), destination.toPath())
}

private const val REPLACE_RETRY_ATTEMPTS = 5
private const val REPLACE_RETRY_BACKOFF_MS = 25L

/**
 * Moves [source] over [destination], atomically where the filesystem supports it and as a plain
 * replacing move otherwise (certain network/cross-volume mounts throw [AtomicMoveNotSupportedException]).
 * [move] is the `Files.move` seam for tests; its flag says whether the move must be atomic.
 *
 * On Windows a reader that opened [destination] without FILE_SHARE_DELETE (a scanner, a plain
 * `File.readText`) makes the replace fail with a sharing violation for as long as it keeps the file open,
 * which is milliseconds. That surfaces as [AccessDeniedException] or a bare [FileSystemException], so those
 * (and only those; not a missing source or an unsupported atomic move) are retried a few times with a short
 * [sleep] backoff before the failure propagates. POSIX never hits the retry: the first attempt succeeds.
 */
internal fun moveReplacingAtomically(
    source: Path,
    destination: Path,
    sleep: (Long) -> Unit = { Thread.sleep(it) },
    move: (Path, Path, Boolean) -> Unit = { from, to, atomic ->
        if (atomic) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } else {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    },
) {
    var atomic = true
    var attempt = 0
    while (true) {
        try {
            move(source, destination, atomic)
            return
        } catch (_: AtomicMoveNotSupportedException) {
            check(atomic) { "A plain replacing move reported that atomic moves are unsupported" }
            atomic = false
        } catch (failure: FileSystemException) {
            if (!isRetriableSharingViolation(failure, ++attempt)) throw failure
            sleep(REPLACE_RETRY_BACKOFF_MS * attempt)
        }
    }
}

/**
 * Moves [source] (a file or a whole directory) to [destination] without ever replacing anything: an existing
 * [destination] fails with [java.nio.file.FileAlreadyExistsException], which is not retried. The same Windows
 * sharing violations as [moveReplacingAtomically] are retried: an antivirus scanner or the indexer briefly holding
 * a file just written under [source] fails the rename for a few milliseconds. [move] is the `Files.move` seam for tests.
 */
internal fun moveWithoutReplacing(
    source: Path,
    destination: Path,
    sleep: (Long) -> Unit = { Thread.sleep(it) },
    move: (Path, Path) -> Unit = { from, to -> Files.move(from, to) },
) {
    var attempt = 0
    while (true) {
        try {
            move(source, destination)
            return
        } catch (failure: FileSystemException) {
            if (!isRetriableSharingViolation(failure, ++attempt)) throw failure
            sleep(REPLACE_RETRY_BACKOFF_MS * attempt)
        }
    }
}

/**
 * Whether [failure] on the [attempt]th try (1-based) is a sharing violation worth another try: an [AccessDeniedException]
 * or a bare [FileSystemException], never NoSuchFile, FileAlreadyExists and the like, and only while attempts remain.
 */
private fun isRetriableSharingViolation(failure: FileSystemException, attempt: Int): Boolean =
    attempt < REPLACE_RETRY_ATTEMPTS &&
        (failure is AccessDeniedException || failure.javaClass == FileSystemException::class.java)

/**
 * Reads [file] through NIO. On Windows NIO opens with FILE_SHARE_DELETE, so a reader of a file that a recorder
 * replaces atomically (`session.json`, `sync.json`) never makes that replace fail with a sharing violation (which
 * would interrupt a healthy capture); `File.readText` (java.io) does not share delete.
 */
fun readTextShared(file: File, charset: java.nio.charset.Charset = Charsets.UTF_8): String =
    String(Files.readAllBytes(file.toPath()), charset)
