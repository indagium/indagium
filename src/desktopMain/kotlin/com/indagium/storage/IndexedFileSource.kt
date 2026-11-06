package com.indagium.storage

import com.indagium.model.LogFormat
import com.indagium.utils.LogcatLineParser
import com.indagium.utils.detectLogTextEncoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.UUID

/** Expected failures specific to the experimental static file source. */
internal sealed class IndexedFileFailure(message: String) : StorageFailure(message) {
    class Changed : IndexedFileFailure("Source file changed or became unavailable")

    class Unsupported : IndexedFileFailure("Only UTF-8 logcat files are supported")

    class LimitExceeded : IndexedFileFailure("Source exceeds the supported entry ID range")

    class RecordTooLong : IndexedFileFailure("Source record exceeds the JVM array limit")

    class Io(cause: IOException) : IndexedFileFailure("File I/O failed: ${cause.message}") {
        init {
            initCause(cause)
        }
    }
}

/**
 * Experimental, static UTF-8 logcat source; not connected to the viewer. Indexing reads the whole
 * file and retains one Long byte offset per emitted row in segmented arrays. Pages decode on demand,
 * without a cache. At most two scans or page reads execute at once across all sources. Record size
 * is not bounded by the row limit. UTF-16,
 * including the existing decoder's BOM-less detection, is rejected. This is not an append store.
 *
 * Each operation checks file identity, size and timestamps before and after I/O; a rewrite that
 * preserves all observed metadata may go undetected. Replacing, deleting or modifying a file
 * invalidates existing snapshots rather than preserving a copy of the original bytes. Snapshots
 * survive handle closure; closing a snapshot prevents new operations, while an operation already
 * underway may finish. Each operation owns and closes its own file reader.
 */
internal class IndexedFileSource private constructor(
    override val id: SourceId,
    private val path: Path,
    private val stamp: Stamp,
    offsets: OffsetIndex,
    private val maxPageRows: Int,
) : SourceHandle {
    override val format = LogFormat.LOGCAT
    private val version = SourceVersion(UUID.randomUUID().toString(), 0)
    private val lock = Any()
    private var offsets: OffsetIndex? = offsets

    companion object {
        private const val ENCODING_SAMPLE_BYTES = 8192
        internal const val MAX_CONCURRENT_READS = 2
        private val readPermits = Semaphore(MAX_CONCURRENT_READS)

        /** The callback is a deterministic test seam, invoked after scanning each physical line. */
        suspend fun open(
            id: SourceId,
            path: Path,
            maxPageRows: Int = LegacyInMemorySource.DEFAULT_MAX_PAGE_ROWS,
            onScanLine: (() -> Unit)? = null,
        ): IndexedFileSource = withContext(Dispatchers.IO) {
            readPermits.withPermit {
                fileIo(path, null) {
                    if (maxPageRows <= 0) throw StorageFailure.InvalidRequest("Maximum page size must be positive")
                    val initial = readStamp(path)
                    val offsets = OffsetIndex()
                    fileIo(path, initial) {
                        Files.newInputStream(path).use { input ->
                            val buffered = BufferedInputStream(input)
                            buffered.mark(ENCODING_SAMPLE_BYTES)
                            val sample = buffered.readNBytes(ENCODING_SAMPLE_BYTES)
                            buffered.reset()
                            if (detectLogTextEncoding(sample).isUtf16) throw IndexedFileFailure.Unsupported()
                            val bom = sample.hasUtf8Bom()
                            val scanner = LineScanner(buffered, 0) { checkStamp(path, initial) }
                            var first = true
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val line = scanner.next() ?: break
                                val bytes = if (first && bom) line.bytes.copyOfRange(3, line.bytes.size) else line.bytes
                                first = false
                                val text = bytes.toString(Charsets.UTF_8)
                                if (text.trim().let { it.isNotEmpty() && !it.startsWith("-----") }) {
                                    if (offsets.size == Int.MAX_VALUE) throw IndexedFileFailure.LimitExceeded()
                                    offsets.add(line.offset)
                                }
                                onScanLine?.invoke()
                            }
                        }
                    }
                    checkStamp(path, initial)
                    currentCoroutineContext().ensureActive()
                    IndexedFileSource(id, path, initial, offsets, maxPageRows)
                }
            }
        }
    }

    override suspend fun snapshot(): ReadSnapshot {
        currentCoroutineContext().ensureActive()
        val result = synchronized(lock) {
            Snapshot(id, path, stamp, offsets ?: throw StorageFailure.Closed(), maxPageRows, version)
        }
        return result
    }

    override fun close() { synchronized(lock) { offsets = null } }

    private class Snapshot(
        private val source: SourceId,
        private val path: Path,
        private val stamp: Stamp,
        offsets: OffsetIndex,
        private val maxPageRows: Int,
        override val version: SourceVersion,
    ) : ReadSnapshot {
        private val lock = Any()
        private var offsets: OffsetIndex? = offsets
        override val rowCount = offsets.size.toLong()

        private fun requireOpen(): OffsetIndex = synchronized(lock) { offsets ?: throw StorageFailure.Closed() }

        override suspend fun rows(start: RowOrdinal, limit: Int): StoragePage<LegacyDecodedRow> =
            withContext(Dispatchers.IO) {
                readPermits.withPermit {
                    fileIo(path, stamp) {
                        val offsets = requireOpen()
                        if (start.value < 0 || limit !in 1..maxPageRows) {
                            throw StorageFailure.InvalidRequest("Page requires a nonnegative start and limit in 1..$maxPageRows")
                        }
                        checkStamp(path, stamp)
                        val first = minOf(start.value, rowCount).toInt()
                        val end = first + minOf(limit, offsets.size - first)
                        val rows = ArrayList<LegacyDecodedRow>(end - first)
                        if (first < end) {
                            RandomAccessFile(path.toFile(), "r").use { file ->
                                val offset = offsets[first]
                                file.seek(offset)
                                val stream = object : java.io.InputStream() {
                                    override fun read(): Int = file.read()

                                    override fun read(bytes: ByteArray, start: Int, length: Int): Int =
                                        file.read(bytes, start, length)
                                }
                                val scanner = LineScanner(stream, offset) { checkStamp(path, stamp) }
                                var ordinal = first
                                val parser = LogcatLineParser()
                                while (ordinal < end) {
                                    val line = scanner.next() ?: throw IndexedFileFailure.Changed()
                                    val bytes = if (line.offset == 0L && line.bytes.hasUtf8Bom()) {
                                        line.bytes.copyOfRange(3, line.bytes.size)
                                    } else {
                                        line.bytes
                                    }
                                    val entry = parser.parse(bytes.toString(Charsets.UTF_8), ordinal + 1)
                                    if (entry != null) {
                                        if (line.offset != offsets[ordinal]) throw IndexedFileFailure.Changed()
                                        rows.add(LegacyDecodedRow(RowRef(source, EntryId(entry.id)), entry))
                                        ordinal++
                                    }
                                }
                            }
                        }
                        checkStamp(path, stamp)
                        currentCoroutineContext().ensureActive()
                        StoragePage(version, start.value, Collections.unmodifiableList(rows), end == offsets.size)
                    }
                }
            }

        override suspend fun resolve(id: EntryId): RowOrdinal? = withContext(Dispatchers.IO) {
            requireOpen()
            try {
                checkStamp(path, stamp)
            } catch (error: IOException) {
                throw IndexedFileFailure.Io(error)
            }
            currentCoroutineContext().ensureActive()
            if (id.value > 0 && id.value.toLong() <= rowCount) RowOrdinal(id.value.toLong() - 1) else null
        }

        override fun close() { synchronized(lock) { offsets = null } }
    }
}

private data class Stamp(
    val key: Any?,
    val size: Long,
    val modified: java.nio.file.attribute.FileTime,
    val created: java.nio.file.attribute.FileTime,
)

private fun readStamp(path: Path): Stamp {
    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
    if (!attrs.isRegularFile) throw IndexedFileFailure.Unsupported()
    return Stamp(attrs.fileKey(), attrs.size(), attrs.lastModifiedTime(), attrs.creationTime())
}

private fun checkStamp(path: Path, expected: Stamp) {
    val actual = try {
        readStamp(path)
    } catch (_: IndexedFileFailure.Unsupported) {
        null
    } catch (_: NoSuchFileException) {
        null
    }
    if (actual != expected) throw IndexedFileFailure.Changed()
}

private suspend inline fun <T> fileIo(path: Path, expected: Stamp?, block: () -> T): T = try {
    block()
} catch (error: IOException) {
    if (expected != null) {
        try {
            checkStamp(path, expected)
        } catch (checkError: IOException) {
            throw IndexedFileFailure.Io(checkError)
        }
    }
    throw IndexedFileFailure.Io(error)
}

private val UTF8_BOM = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())

private fun ByteArray.hasUtf8Bom(): Boolean =
    size >= UTF8_BOM.size && UTF8_BOM.indices.all { this[it] == UTF8_BOM[it] }

private data class FileLine(val offset: Long, val bytes: ByteArray)

/** BufferedReader.readLine semantics for UTF-8: CR, LF, and CRLF are line terminators. */
private class LineScanner(
    private val input: java.io.InputStream,
    initialOffset: Long,
    private val checkpoint: () -> Unit,
) {
    companion object {
        private const val CHECKPOINT_BYTES = 65536L
        private const val INITIAL_BUFFER_BYTES = 256
        private const val INPUT_BUFFER_BYTES = 8192
        private const val MAX_RETAINED_BUFFER_BYTES = 256 * 1024
        private const val MAX_ARRAY_BYTES = Int.MAX_VALUE - 8
        private const val UNSIGNED_BYTE_MASK = 0xff
    }

    private var position = initialOffset
    private var pending = -1
    private var nextCheckpoint = initialOffset
    private var buffer = ByteArray(INITIAL_BUFFER_BYTES)
    private val inputBuffer = ByteArray(INPUT_BUFFER_BYTES)
    private var inputPosition = 0
    private var inputLimit = 0

    private fun nextByte(): Int {
        if (inputLimit < 0) return -1
        if (inputPosition == inputLimit) {
            inputLimit = input.read(inputBuffer)
            inputPosition = 0
            if (inputLimit < 0) return -1
            if (inputLimit == 0) throw IOException("Source read made no progress")
        }
        return inputBuffer[inputPosition++].toInt() and UNSIGNED_BYTE_MASK
    }

    private fun finish(start: Long, length: Int): FileLine {
        val bytes = buffer.copyOf(length)
        // An oversized record is returned intact, but its scratch buffer is not kept for later rows.
        if (buffer.size > MAX_RETAINED_BUFFER_BYTES) buffer = ByteArray(INITIAL_BUFFER_BYTES)
        return FileLine(start, bytes)
    }

    suspend fun next(): FileLine? {
        currentCoroutineContext().ensureActive()
        val start = position
        var length = 0
        while (true) {
            if (position >= nextCheckpoint) {
                currentCoroutineContext().ensureActive()
                checkpoint()
                nextCheckpoint = if (position > Long.MAX_VALUE - CHECKPOINT_BYTES) Long.MAX_VALUE else position + CHECKPOINT_BYTES
            }
            val b = if (pending >= 0) pending.also { pending = -1 } else nextByte()
            if (b < 0) return if (length == 0) null else finish(start, length)
            position++
            if (b == 10) return finish(start, length)
            if (b == 13) {
                val next = nextByte()
                if (next == 10) position++ else if (next >= 0) pending = next
                return finish(start, length)
            }
            if (length == buffer.size) {
                if (length >= MAX_ARRAY_BYTES) throw IndexedFileFailure.RecordTooLong()
                buffer = buffer.copyOf(minOf(MAX_ARRAY_BYTES.toLong(), buffer.size.toLong() * 2).toInt())
            }
            buffer[length++] = b.toByte()
        }
    }
}

/** Segmented to avoid both boxed offsets and repeated copying of a growing contiguous array. */
private class OffsetIndex {
    private val chunks = ArrayList<LongArray>()
    var size: Int = 0
        private set

    fun add(value: Long) {
        if (size % CHUNK_SIZE == 0) chunks.add(LongArray(CHUNK_SIZE))
        chunks[size / CHUNK_SIZE][size % CHUNK_SIZE] = value
        size++
    }

    operator fun get(index: Int): Long = chunks[index / CHUNK_SIZE][index % CHUNK_SIZE]

    companion object {
        private const val CHUNK_SIZE = 4096
    }
}
