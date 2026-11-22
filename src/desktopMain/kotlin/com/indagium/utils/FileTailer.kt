package com.indagium.utils

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
private const val UTF8_BOM_CHAR = "\uFEFF"
private const val CARRIAGE_RETURN_BYTE = '\r'.code.toByte()

// Caps a single readNewLines() allocation so a large backlog (app suspended, or a burst of many
// MB written between polls) doesn't allocate one huge buffer sized to the entire unread delta.
// Large enough that ordinary multi-line bursts never hit the long-line fallback below.
private const val MAX_TAIL_READ_BYTES = 4L * 1024L * 1024L

/**
 * The last line of a file that does not end with a newline: [startOffset] is the byte offset just after the last `\n` or lone `\r`
 * before it (0 for a file with no earlier line break at all), [text] the line as a reader shows it (no `\r`, no byte-order mark),
 * [fileLength] the length of the file this was read from (the line ends there). A final `\r` is not a line break yet, as it may be
 * half of a `\r\n` still being written: the line stays unfinished, and the tail re-reads it once the next byte is known.
 */
internal class TrailingPartialLine(val startOffset: Long, val text: String, val fileLength: Long)

// The longest unfinished last line [trailingPartialLine] will look for; a longer one is left as the v1 tail leaves it.
private const val MAX_PARTIAL_LINE_BYTES = 1 shl 20
private const val PARTIAL_LINE_SCAN_CHUNK = 8 * 1024

/**
 * The unfinished last line of [file], or null when it is empty, ends with a newline, or its last line is longer than a megabyte.
 * A tailer started at [TrailingPartialLine.startOffset] reads that line whole once its writer finishes it, instead of only the
 * bytes written after the tail started.
 */
internal fun trailingPartialLine(file: File): TrailingPartialLine? = runCatching {
    RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        if (length == 0L) return@use null
        raf.seek(length - 1)
        val last = raf.read()
        if (last == NEWLINE_BYTE.toInt()) return@use null
        var start = 0L
        // A final `\r` belongs to the unfinished line (it may be the first half of a `\r\n`), so the line starts after an earlier break.
        val endsWithCarriageReturn = last == CARRIAGE_RETURN_BYTE.toInt()
        var end = if (endsWithCarriageReturn) length - 1 else length
        val chunk = ByteArray(PARTIAL_LINE_SCAN_CHUNK)
        search@ while (end > 0L) {
            val from = maxOf(0L, end - chunk.size)
            val size = (end - from).toInt()
            raf.seek(from)
            raf.readFully(chunk, 0, size)
            for (i in size - 1 downTo 0) {
                if (chunk[i] == NEWLINE_BYTE || chunk[i] == CARRIAGE_RETURN_BYTE) {
                    start = from + i + 1
                    break@search
                }
            }
            if (length - from > MAX_PARTIAL_LINE_BYTES) return@use null
            end = from
        }
        if (length - start > MAX_PARTIAL_LINE_BYTES) return@use null
        val bytes = ByteArray((length - start).toInt() - if (endsWithCarriageReturn) 1 else 0)
        raf.seek(start)
        raf.readFully(bytes)
        val raw = String(bytes, Charsets.UTF_8)
        TrailingPartialLine(start, if (start == 0L) raw.removePrefix("\uFEFF") else raw, length)
    }
}.getOrNull()

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
// A line ends at `\n`, `\r\n` or a lone `\r`, exactly as the reader that opens a file splits it, so a tail numbers and cuts lines
// like a fresh open does. A tail that ends a line at a CR skips an LF if it arrives in a later read ([skipLeadingLf]).
//
// v1 never replays existing file content on the initial start() UNLESS the caller explicitly asks
// it to via [startOffset]: with the default (null), tailing begins from the current end-of-file,
// only new growth is emitted. Passing startOffset = 0L replays the whole file from the start —
// needed by a consumer that opens the file empty and wants to see every line it's about to have
// written into it (a live-capture tab, see ui/CaptureCoordinator.kt), where waiting for the
// current end-of-file would race the writer and silently skip whatever it wrote before start()
// ran. If the file later shrinks (rotated/truncated externally, e.g. an external
// `adb logcat > out.log` restarting), the read position resets to the start of the file's new
// content and re-reads it — see readNewLines() for why resetting to the new end-of-file instead
// would silently discard an entire fast truncate-and-rewrite.
class FileTailer(
    private val file: File,
    private val onNewLines: (List<String>) -> Unit,
    private val pollIntervalMs: Long = 500,
    /** Byte offset to begin from. null (default) = current end-of-file, the v1 behaviour: only
     *  new growth is emitted. 0 replays the whole file from the start. */
    private val startOffset: Long? = null,
    /** Called once each time the file is found shorter than the position already read (rotated or
     *  truncated), after the position has moved back to the start and BEFORE the first batch of the
     *  new content reaches [onNewLines]. Runs on whichever thread is reading: the tailer's own
     *  coroutine, or the caller of [drainToEndOfFile]/[readToEndOfFile]. A consumer keeping state
     *  that spans lines (a parsed header, an open record) resets it here. */
    private val onRestart: () -> Unit = {},
) {
    // moreAvailable: the chunk cap (MAX_TAIL_READ_BYTES) held back data still waiting beyond
    // newOffset — start()'s poll loop uses this to keep draining without waiting a full extra
    // pollIntervalMs per chunk.
    private class ReadResult(val lines: List<String>, val newOffset: Long, val moreAvailable: Boolean)

    // Written from the tailer's own coroutine (start()'s poll loop) on every flush, and by
    // readToEndOfFile() when called from another thread after the Job has been cancelled (see
    // that function's doc) — @Volatile so that caller sees the up-to-date value rather than a
    // stale cached one. Read-only from outside via the currentOffset property below.
    @Volatile
    private var offset: Long = 0L

    /** The byte offset up to which content has already been emitted to [onNewLines] (or replayed,
     *  if [startOffset] was set). Lets a caller that has just cancelled this tailer's Job do one
     *  final synchronous catch-up read — see [readToEndOfFile] — without re-deriving where the
     *  tailer left off. */
    val currentOffset: Long get() = offset

    // The highest length [file] has been seen at (by [start] or any read), so a rotation that leaves the file at least as long as
    // [offset] still shows as a shrink. The offset alone cannot: a tail started at an unfinished last line's first byte (see
    // [trailingPartialLine]) holds an offset far below the file's end, and so does any tail holding back a partial line. Raised on
    // every read, reset to the new length when a restart is announced; [resume] keeps it, so a rotation during a pause is seen too.
    @Volatile
    private var knownLength: Long = 0L

    // True while [offset] sits right after a `\r` whose line has been (or is being) ended: a `\n` that is the next byte then belongs
    // to that `\r\n` and is skipped, not read as an empty line. Set when a tail starts after a `\r` (a file cut between the two
    // bytes of a CRLF) and when a read ends at a CR.
    @Volatile
    private var skipLeadingLf: Boolean = false

    /** Bytes of [file] this tailer has not emitted yet (file length minus [currentOffset], never negative). */
    fun unreadBytes(): Long = (file.length() - offset).coerceAtLeast(0L)

    // The starting offset is captured synchronously, on the CALLER's thread, before scope.launch
    // even schedules the coroutine body — not lazily inside it. scope.launch returns immediately;
    // if a caller appends to the file right after start() returns (the exact pattern every test
    // here uses), a lazily-captured offset can race and land AFTER that append, permanently
    // treating already-new content as pre-existing. This bug reproduced consistently in
    // FileTailerTest until fixed this way.
    fun start(scope: CoroutineScope): Job {
        val length = if (file.exists()) file.length() else 0L
        offset = startOffset ?: length
        knownLength = length
        skipLeadingLf = followsCarriageReturn(offset)
        return launchPollLoop(scope)
    }

    // Restarts the poll loop from the offset this tailer already reached, instead of re-deriving it
    // like start() does (which would replay from [startOffset], or skip to the current end-of-file,
    // and so duplicate or lose rows). For a tailer whose Job was cancelled on purpose (the capture
    // log view's pause at critical heap pressure, see ui/TailCoordinator.kt pauseTailing): the file
    // kept growing meanwhile, and the first poll after resuming catches up from where it stopped,
    // in bounded chunks. The caller must have fully stopped the previous Job (cancelAndJoin) so two
    // loops never advance [offset] at once.
    fun resume(scope: CoroutineScope): Job = launchPollLoop(scope)

    private fun followsCarriageReturn(at: Long): Boolean = at > 0L && runCatching {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(at - 1)
            raf.read() == CARRIAGE_RETURN_BYTE.toInt()
        }
    }.getOrDefault(false)

    private fun launchPollLoop(scope: CoroutineScope): Job {
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

    // Synchronous final catch-up read, meant to be called right after cancelling start()'s Job so
    // nothing else is concurrently advancing [offset] — see ui/TailCoordinator.kt's
    // drainAndStopTailing for why this exists: stopping the tailer by cancelling its Job can leave
    // up to one poll interval of already-written bytes unread, and for a capture tab that gap
    // would silently desync the log↔video mapping (CaptureTimelineIndex's row-count guard). Reuses
    // readNewLines/readChunk's own line-splitting rather than a second, subtly different reader —
    // a trailing partial line (no newline yet) is left unread, exactly as the poll loop does,
    // unless [includeTrailingPartialLine] is set. That opt-in is for a caller that knows the writer
    // has finished and will never complete the line (a stopped capture: the recorder indexes its
    // final unterminated line as a row); normal polling never sets it.
    fun readToEndOfFile(includeTrailingPartialLine: Boolean = false): List<String> {
        val all = mutableListOf<String>()
        drainToEndOfFile(includeTrailingPartialLine) { all.addAll(it) }
        return all
    }

    // Same final catch-up as [readToEndOfFile], but hands each bounded chunk (at most
    // MAX_TAIL_READ_BYTES of lines) to [onBatch] as it is read, so a large backlog is appended
    // chunk by chunk instead of being accumulated into one list first.
    fun drainToEndOfFile(includeTrailingPartialLine: Boolean = false, onBatch: (List<String>) -> Unit) {
        while (true) {
            val result = readNewLines(offset)
            if (result.lines.isEmpty()) break
            offset = result.newOffset
            onBatch(result.lines)
            if (!result.moreAvailable) break
        }
        if (includeTrailingPartialLine) readTrailingPartialLine()?.let { onBatch(listOf(it)) }
    }

    // The bytes after the last complete line, as one line. A line break means the writer extended the file after the drain's read.
    private fun readTrailingPartialLine(): String? {
        if (!file.exists()) return null
        val remaining = file.length() - offset
        if (remaining <= 0L || remaining > Int.MAX_VALUE) return null
        val bytes = ByteArray(remaining.toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(bytes)
        }
        if (bytes.any { it == NEWLINE_BYTE || it == CARRIAGE_RETURN_BYTE }) return null
        val line = String(bytes, Charsets.UTF_8).let { if (offset == 0L) it.removePrefix(UTF8_BOM_CHAR) else it }
        offset += bytes.size
        return line
    }

    // A UTF-8 file starts with a byte order mark the reader that opens a file drops. A read that starts at byte 0 (the first read, or the
    // one after a rotation) meets it as the first character of the first line, which would break a header column or make a Logcat
    // line RAW. Offsets stay byte-exact: only the decoded text loses the mark.
    private fun List<String>.withoutByteOrderMark(from: Long): List<String> =
        if (from == 0L && isNotEmpty() && first().startsWith(UTF8_BOM_CHAR)) {
            toMutableList().also { it[0] = it[0].removePrefix(UTF8_BOM_CHAR) }
        } else {
            this
        }

    private fun readNewLines(fromOffset: Long): ReadResult {
        if (!file.exists()) return ReadResult(emptyList(), fromOffset, moreAvailable = false)
        val length = file.length()
        // Rotated/truncated externally — resume from the start of whatever's there now, rather
        // than jumping straight to the new end-of-file. A truncate-and-rewrite (e.g.
        // `adb logcat > out.log` restarting) typically completes faster than one poll interval:
        // by the time this is noticed, the "new" file may already be fully written, and jumping
        // to its EOF would silently discard all of it instead of showing the new session.
        //
        // The restart is announced here, in the one place every read passes through, with the offset
        // already moved back so a second read of the same shrunken file does not announce it again.
        // Done before any line of the new content is returned, so a consumer resets its state first.
        //
        // "Shorter than before", not just "shorter than the offset": the offset can sit well below the end of the file (a tail
        // started at its unfinished last line, a partial line held back), and a rewrite that is still longer than the offset
        // would be read as the continuation of the old content.
        var readFrom = if (length < maxOf(fromOffset, knownLength)) {
            offset = 0L
            knownLength = length
            skipLeadingLf = false
            onRestart()
            0L
        } else {
            knownLength = length
            fromOffset
        }
        if (skipLeadingLf && length > readFrom) {
            // The `\n` of a `\r\n` whose `\r` ended a line already: no line of its own, but the offset moves past it.
            skipLeadingLf = false
            if (byteAt(readFrom) == NEWLINE_BYTE) {
                readFrom++
                offset = readFrom
            }
        }
        if (length == readFrom) return ReadResult(emptyList(), readFrom, moreAvailable = false)

        val cappedTo = minOf(length, readFrom + MAX_TAIL_READ_BYTES)
        readChunk(readFrom, cappedTo)?.let { return it }

        // No complete line within the capped chunk, but there's more data beyond the cap — an
        // abnormally long single line straddled the chunk boundary. Widen to the true
        // end-of-file for this one call (the original unbounded behavior) so it still completes
        // correctly; ordinary bursts of normal-length lines never take this path.
        if (cappedTo < length) readChunk(readFrom, length)?.let { return it }
        // Leave an unterminated line unread so the next poll picks it up whole.
        return ReadResult(emptyList(), readFrom, moreAvailable = false)
    }

    private fun byteAt(position: Long): Byte? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(position)
            raf.read().takeIf { it >= 0 }?.toByte()
        }
    }.getOrNull()

    // Reads [from, to) and returns a result only if it contains at least one complete line; null
    // tells the caller "no line break in this range" so it can decide whether to widen the range.
    private fun readChunk(from: Long, to: Long): ReadResult? {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            val bytes = ByteArray((to - from).toInt())
            raf.readFully(bytes)

            var lastBreakIdx = -1
            for (i in bytes.indices.reversed()) {
                val b = bytes[i]
                if (b == NEWLINE_BYTE || b == CARRIAGE_RETURN_BYTE) {
                    lastBreakIdx = i
                    break
                }
            }
            if (lastBreakIdx < 0) return null

            val completeBytes = bytes.copyOfRange(0, lastBreakIdx + 1)
            val lines = splitLines(String(completeBytes, Charsets.UTF_8)).withoutByteOrderMark(from)
            val newOffset = from + completeBytes.size
            skipLeadingLf = completeBytes.last() == CARRIAGE_RETURN_BYTE
            return ReadResult(lines, newOffset, moreAvailable = newOffset < file.length())
        }
    }

    // [text] ends with a line break: each `\n`, `\r\n` or lone `\r` ends one line (what BufferedReader.readLine does).
    private fun splitLines(text: String): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                lines += text.substring(start, i)
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                start = i + 1
            }
            i++
        }
        return lines
    }
}
