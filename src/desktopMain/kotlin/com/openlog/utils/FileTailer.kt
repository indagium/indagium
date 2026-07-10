package com.openlog.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds

private const val NEWLINE_BYTE = '\n'.code.toByte()
private const val CARRIAGE_RETURN = '\r'

// Caps a single readNewLines() allocation so a large backlog (app suspended, or a burst of many
// MB written between polls) doesn't allocate one huge buffer sized to the entire unread delta.
// Large enough that ordinary multi-line bursts never hit the long-line fallback below.
private const val MAX_TAIL_READ_BYTES = 4L * 1024L * 1024L

// Watches a file that's growing externally (e.g. `adb logcat > out.log` run outside the app — no
// direct adb integration, by design) and emits newly-appended, complete lines in batches.
//
// Empirically confirmed on this dev machine (see FileTailerTest): java.nio.file.WatchService's
// blocking poll(timeout, unit) is unreliable here — some registrations simply never deliver an
// event within many seconds, for reasons that didn't reproduce deterministically across runs
// (consistent with known flakiness in the JDK's macOS WatchService backend, not a bug in this
// class). This design therefore does NOT depend on WatchService firing, or on its timed poll
// respecting its own timeout: delay(pollIntervalMs) is the real, always-reliable throttle (a
// proper coroutine suspension point), and readNewLines() runs unconditionally every interval
// regardless of whether the watch mechanism noticed anything. WatchService is kept registered and
// drained on a best-effort, non-blocking basis only — a possible future optimization to wake up
// earlier than the next interval, never a correctness dependency.
//
// v1 never replays existing file content on the initial start(): tailing begins from the current
// end-of-file, only new growth is emitted. If the file later shrinks (rotated/truncated
// externally, e.g. an external `adb logcat > out.log` restarting), the read position resets to
// the start of the file's new content and re-reads it — see readNewLines() for why resetting to
// the new end-of-file instead would silently discard an entire fast truncate-and-rewrite.
class FileTailer(
    private val file: File,
    private val onNewLines: (List<String>) -> Unit,
    private val pollIntervalMs: Long = 500,
) {
    // moreAvailable: the chunk cap (MAX_TAIL_READ_BYTES) held back data still waiting beyond
    // newOffset — start()'s poll loop uses this to keep draining without waiting a full extra
    // pollIntervalMs per chunk.
    private class ReadResult(val lines: List<String>, val newOffset: Long, val moreAvailable: Boolean)

    // The starting offset is captured synchronously, on the CALLER's thread, before scope.launch
    // even schedules the coroutine body — not lazily inside it. scope.launch returns immediately;
    // if a caller appends to the file right after start() returns (the exact pattern every test
    // here uses), a lazily-captured offset can race and land AFTER that append, permanently
    // treating already-new content as pre-existing. This bug reproduced consistently in
    // FileTailerTest until fixed this way.
    fun start(scope: CoroutineScope): Job {
        var offset = if (file.exists()) file.length() else 0L
        val parentPath = file.parentFile?.toPath()
        return scope.launch {
            if (!file.exists() || parentPath == null) return@launch
            val watchService = FileSystems.getDefault().newWatchService()
            val watchKey = runCatching {
                parentPath.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE)
            }.getOrNull()

            try {
                while (isActive) {
                    delay(pollIntervalMs)
                    watchKey?.let { key ->
                        // Non-blocking, best-effort drain — see class doc for why this isn't
                        // relied on for correctness or timing.
                        if (watchService.poll() != null) {
                            key.pollEvents()
                            key.reset()
                        }
                    }
                    // Drain in bounded chunks until caught up (or cancelled) rather than waiting a
                    // full extra pollIntervalMs per chunk — a large backlog still catches up
                    // within this tick, it just never allocates more than one chunk at a time.
                    do {
                        val result = readNewLines(offset)
                        if (result.lines.isNotEmpty()) {
                            offset = result.newOffset
                            onNewLines(result.lines)
                        }
                    } while (isActive && result.moreAvailable)
                }
            } finally {
                watchKey?.cancel()
                watchService.close()
            }
        }
    }

    private fun readNewLines(fromOffset: Long): ReadResult {
        if (!file.exists()) return ReadResult(emptyList(), fromOffset, moreAvailable = false)
        val length = file.length()
        // Rotated/truncated externally — resume from the start of whatever's there now, rather
        // than jumping straight to the new end-of-file. A truncate-and-rewrite (e.g.
        // `adb logcat > out.log` restarting) typically completes faster than one poll interval:
        // by the time this is noticed, the "new" file may already be fully written, and jumping
        // to its EOF would silently discard all of it instead of showing the new session.
        val readFrom = if (length < fromOffset) 0L else fromOffset
        if (length == readFrom) return ReadResult(emptyList(), readFrom, moreAvailable = false)

        val cappedTo = minOf(length, readFrom + MAX_TAIL_READ_BYTES)
        readChunk(readFrom, cappedTo)?.let { return it }

        // No complete line within the capped chunk, but there's more data beyond the cap — an
        // abnormally long single line straddled the chunk boundary. Widen to the true
        // end-of-file for this one call (the original unbounded behavior) so it still completes
        // correctly; ordinary bursts of normal-length lines never take this path.
        if (cappedTo < length) readChunk(readFrom, length)?.let { return it }
        // Only emit complete lines — a trailing partial line (no newline yet) is left unread by
        // not advancing past it, so the next flush picks it up whole.
        return ReadResult(emptyList(), readFrom, moreAvailable = false)
    }

    // Reads [from, to) and returns a result only if it contains at least one complete line; null
    // tells the caller "no newline in this range" so it can decide whether to widen the range.
    private fun readChunk(from: Long, to: Long): ReadResult? {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            val bytes = ByteArray((to - from).toInt())
            raf.readFully(bytes)

            var lastNewlineIdx = -1
            for (i in bytes.indices.reversed()) {
                if (bytes[i] == NEWLINE_BYTE) {
                    lastNewlineIdx = i
                    break
                }
            }
            if (lastNewlineIdx < 0) return null

            val completeBytes = bytes.copyOfRange(0, lastNewlineIdx + 1)
            val lines = String(completeBytes, Charsets.UTF_8)
                .split('\n')
                .dropLast(1) // split on a string ending in '\n' leaves a trailing "" — drop it
                .map { it.trimEnd(CARRIAGE_RETURN) }
            val newOffset = from + completeBytes.size
            return ReadResult(lines, newOffset, moreAvailable = newOffset < file.length())
        }
    }
}
