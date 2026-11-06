@file:Suppress("MagicNumber") // Byte markers and benchmark buffer sizes are protocol/fixture constants.

package com.indagium.ram

import com.indagium.model.LogEntry
import com.indagium.utils.parseLogcatLines
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.LinkedHashMap

/** Experimental benchmark reader. Only use with generated fixtures under [fixtureRoot]. */
internal class DirectFileLogStore private constructor(
    private val path: Path,
    private val channel: FileChannel,
    private val identity: BasicFileAttributes,
    private val starts: LongArray,
    private val ends: LongArray,
) : AutoCloseable {
    val rowCount: Int get() = starts.size
    val cacheBudgetBytes: Long = 32L * 1024 * 1024
    val cacheBytes: Long get() = synchronized(sharedBlocks) { sharedBytes }
    private var closed = false

    @Synchronized
    operator fun get(index: Int): LogEntry {
        checkOpenAndUnchanged()
        require(index in 0 until rowCount) { "Row index out of bounds: $index" }
        return loadBlock(index / BLOCK_ROWS)[index % BLOCK_ROWS]
    }

    // Caller holds the store monitor and validates the source before and after a block load.
    private fun loadBlock(blockIndex: Int): List<LogEntry> {
        synchronized(sharedBlocks) {
            sharedBlocks[this to blockIndex]?.let { return it.entries }
        }
        val first = blockIndex * BLOCK_ROWS
        val last = minOf(first + BLOCK_ROWS, rowCount)
        val entries = ArrayList<LogEntry>(last - first)
        var bytes = BLOCK_OVERHEAD
        for (row in first until last) {
            val raw = readRow(row)
            val entry = parseLogcatLines(sequenceOf(raw), row + 1).single()
            entries.add(entry)
            // Conservative accounting for entry/list/string/array objects and their UTF-16 content.
            bytes += ENTRY_OVERHEAD + 2L * (entry.ts.length + entry.tag.length + entry.msg.length)
        }
        checkOpenAndUnchanged()
        synchronized(sharedBlocks) {
            if (bytes <= cacheBudgetBytes) {
                while (sharedBytes + bytes > cacheBudgetBytes) {
                    val iterator = sharedBlocks.entries.iterator()
                    sharedBytes -= iterator.next().value.bytes
                    iterator.remove()
                }
                sharedBlocks[this to blockIndex] = Block(entries, bytes)
                sharedBytes += bytes
            }
        }
        return entries
    }

    fun entries(): Sequence<LogEntry> = (0 until rowCount).asSequence().map { get(it) }

    /** Experimental sequential scan: validate at each block boundary, never on each row. */
    fun batchedEntries(): Sequence<LogEntry> = sequence {
        var first = 0
        while (first < rowCount) {
            val block = synchronized(this@DirectFileLogStore) {
                checkOpenAndUnchanged()
                loadBlock(first / BLOCK_ROWS)
            }
            for (entry in block) yield(entry)
            synchronized(this@DirectFileLogStore) { checkOpenAndUnchanged() }
            first += block.size
        }
    }

    private fun readRow(row: Int): String {
        val length = ends[row] - starts[row]
        require(length <= Int.MAX_VALUE) { "Fixture row exceeds JVM array limit" }
        val buffer = ByteBuffer.allocate(length.toInt())
        var position = starts[row]
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            check(read > 0) { "Fixture was truncated while reading" }
            position += read
        }
        return String(buffer.array(), StandardCharsets.UTF_8)
    }

    // Metadata detects normal replacement, overwrite and truncation; restored timestamps can hide
    // same-size in-place changes, so benchmark fixtures must remain immutable while open.
    private fun checkOpenAndUnchanged() {
        check(!closed) { "Store is closed" }
        val now = Files.readAttributes(path, BasicFileAttributes::class.java)
        check(now.isRegularFile && now.size() == identity.size() && now.lastModifiedTime() == identity.lastModifiedTime() &&
            now.fileKey() == identity.fileKey() && channel.size() == identity.size()) { "Fixture changed after opening" }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            synchronized(sharedBlocks) {
                val iterator = sharedBlocks.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.key.first === this) {
                        sharedBytes -= entry.value.bytes
                        iterator.remove()
                    }
                }
            }
            channel.close()
        }
    }

    companion object {
        // Estimated retained bytes, conservatively allowing object and string overhead; not a JVM heap measurement.
        private data class Block(val entries: List<LogEntry>, val bytes: Long)

        private val sharedBlocks = LinkedHashMap<Pair<DirectFileLogStore, Int>, Block>(16, 0.75f, true)
        private var sharedBytes = 0L
        private const val BLOCK_ROWS = 128
        private const val BLOCK_OVERHEAD = 256L
        private const val ENTRY_OVERHEAD = 512L

        // Close the channel even if indexing fails with an Error or cancellation.
        @Suppress("TooGenericExceptionCaught")
        fun open(fixtureRoot: Path, path: Path): DirectFileLogStore {
            val rootInput = fixtureRoot.toAbsolutePath().normalize()
            val root = rootInput.toRealPath()
            require(Files.isDirectory(root)) { "Fixture root must be a directory" }
            val absolute = path.toAbsolutePath().normalize()
            require(absolute.startsWith(rootInput) || absolute.startsWith(root)) { "Fixture must be under the canonical fixture root" }
            val real = absolute.toRealPath()
            require(real.startsWith(root)) { "Fixture symlink escapes fixture root" }
            val attrs = Files.readAttributes(real, BasicFileAttributes::class.java)
            require(attrs.isRegularFile) { "Fixture must be a regular file" }
            val channel = FileChannel.open(real, StandardOpenOption.READ)
            try {
                val offsets = scan(channel)
                val after = Files.readAttributes(real, BasicFileAttributes::class.java)
                check(after.size() == attrs.size() && after.lastModifiedTime() == attrs.lastModifiedTime() &&
                    after.fileKey() == attrs.fileKey() && channel.size() == attrs.size()) { "Fixture changed while indexing" }
                return DirectFileLogStore(real, channel, attrs, offsets.first, offsets.second)
            } catch (t: Throwable) {
                channel.close()
                throw t
            }
        }

        private fun scan(channel: FileChannel): Pair<LongArray, LongArray> {
            var starts = LongArray(1024)
            var ends = LongArray(1024)
            var count = 0
            var start = 0L
            var pos = 0L
            var previousCr = false
            val line = ByteArrayOutputStream()

            fun finish(end: Long) {
                var bytes = line.toByteArray()
                var contentStart = start
                val hasBom = bytes.size >= 3 && bytes[0] == 0xef.toByte() &&
                    bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()
                if (start == 0L && hasBom) {
                    bytes = bytes.copyOfRange(3, bytes.size)
                    contentStart += 3
                }
                val text = String(bytes, StandardCharsets.UTF_8).trim()
                if (text.isNotEmpty() && !text.startsWith("-----")) {
                    check(count < Int.MAX_VALUE) { "Too many fixture rows" }
                    if (count == starts.size) {
                        val size = minOf(Int.MAX_VALUE.toLong(), starts.size.toLong() * 2).toInt()
                        starts = starts.copyOf(size)
                        ends = ends.copyOf(size)
                    }
                    starts[count] = contentStart
                    ends[count++] = end
                }
                line.reset()
            }
            val buffer = ByteBuffer.allocate(64 * 1024)
            while (true) {
                buffer.clear()
                val n = channel.read(buffer, pos)
                if (n < 0) break
                check(n > 0) { "Unable to read fixture" }
                buffer.flip()
                while (buffer.hasRemaining()) {
                    val b = buffer.get()
                    if (previousCr && b == 10.toByte()) {
                        start = pos + 1
                    } else if (b == 10.toByte() || b == 13.toByte()) {
                        finish(pos)
                        start = pos + 1
                    } else {
                        line.write(b.toInt())
                    }
                    previousCr = b == 13.toByte()
                    pos++
                }
            }
            if (start < pos) finish(pos)
            return starts.copyOf(count) to ends.copyOf(count)
        }
    }
}
