package com.indagium.utils

import java.io.File
import java.io.Writer
import java.nio.file.AtomicMoveNotSupportedException
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
    try {
        Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Moves [source] over [destination], atomically where the filesystem supports it and as a plain
 * replacing move otherwise (certain network/cross-volume mounts throw [AtomicMoveNotSupportedException]).
 * [move] is the `Files.move` seam for tests; its flag says whether the move must be atomic.
 */
internal fun moveReplacingAtomically(
    source: Path,
    destination: Path,
    move: (Path, Path, Boolean) -> Unit = { from, to, atomic ->
        if (atomic) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } else {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    },
) {
    try {
        move(source, destination, true)
    } catch (_: AtomicMoveNotSupportedException) {
        move(source, destination, false)
    }
}
