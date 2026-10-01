@file:Suppress("MagicNumber")

package com.indagium.capture

import java.io.File
import java.io.RandomAccessFile

/**
 * Minimal EBML walker for asserting that a Matroska file was finalized (trailer written) without
 * relying on ffprobe: a finalized file has a Segment whose size is known (the muxer rewrites the
 * "unknown size" placeholder in `write_trailer`), a Cues element, and a Segment Info Duration.
 */
internal data class MkvStructure(
    val segmentSizeKnown: Boolean,
    val topLevelIds: List<Long>,
    val durationMs: Double?,
) {
    val hasCues: Boolean get() = CUES in topLevelIds
    val clusterCount: Int get() = topLevelIds.count { it == CLUSTER }

    /** True when the muxer trailer ran: known segment size, cues and a duration. */
    val isFinalized: Boolean get() = segmentSizeKnown && hasCues && (durationMs ?: 0.0) > 0.0

    companion object {
        const val SEGMENT = 0x18538067L
        const val CUES = 0x1C53BB6BL
        const val CLUSTER = 0x1F43B675L
        const val INFO = 0x1549A966L
        const val DURATION = 0x4489L
        const val TIMESTAMP_SCALE = 0x2AD7B1L

        fun read(file: File): MkvStructure {
            RandomAccessFile(file, "r").use { raf ->
                val bytes = ByteArray(raf.length().toInt())
                raf.readFully(bytes)
                return parse(bytes)
            }
        }

        private fun parse(d: ByteArray): MkvStructure {
            var index = 0
            // EBML header, then the Segment.
            val header = readElement(d, index)
            index = header.bodyStart + header.size.toInt()
            val segment = readElement(d, index)
            require(segment.id == SEGMENT) { "no Segment element" }
            val ids = mutableListOf<Long>()
            var duration: Double? = null
            var timestampScaleNs = 1_000_000L
            var cursor = segment.bodyStart
            val end = d.size
            while (cursor < end) {
                val child = runCatching { readElement(d, cursor) }.getOrNull() ?: break
                ids += child.id
                if (child.id == INFO && !child.unknownSize) {
                    var inner = child.bodyStart
                    val innerEnd = child.bodyStart + child.size.toInt()
                    while (inner < innerEnd) {
                        val element = readElement(d, inner)
                        if (element.id == TIMESTAMP_SCALE) timestampScaleNs = readUnsigned(d, element.bodyStart, element.size.toInt())
                        if (element.id == DURATION) duration = readFloat(d, element.bodyStart, element.size.toInt())
                        inner = element.bodyStart + element.size.toInt()
                    }
                }
                cursor = if (child.unknownSize) break else child.bodyStart + child.size.toInt()
            }
            val ms = duration?.let { it * timestampScaleNs / 1_000_000.0 }
            return MkvStructure(!segment.unknownSize, ids, ms)
        }

        private class Element(val id: Long, val size: Long, val bodyStart: Int, val unknownSize: Boolean)

        private fun readElement(d: ByteArray, at: Int): Element {
            val idLength = vintLength(d[at])
            var id = 0L
            for (k in 0 until idLength) id = (id shl 8) or (d[at + k].toLong() and 0xFF)
            val sizeAt = at + idLength
            val sizeLength = vintLength(d[sizeAt])
            var size = d[sizeAt].toLong() and ((0x80 shr (sizeLength - 1)) - 1).toLong()
            var allOnes = size == ((0x80 shr (sizeLength - 1)) - 1).toLong()
            for (k in 1 until sizeLength) {
                val b = d[sizeAt + k].toLong() and 0xFF
                size = (size shl 8) or b
                if (b != 0xFFL) allOnes = false
            }
            return Element(id, size, sizeAt + sizeLength, allOnes)
        }

        private fun vintLength(first: Byte): Int {
            var length = 1
            var mask = 0x80
            while (length <= 8 && (first.toInt() and mask) == 0) {
                length++
                mask = mask shr 1
            }
            return length
        }

        private fun readUnsigned(d: ByteArray, at: Int, length: Int): Long {
            var v = 0L
            for (k in 0 until length) v = (v shl 8) or (d[at + k].toLong() and 0xFF)
            return v
        }

        private fun readFloat(d: ByteArray, at: Int, length: Int): Double = when (length) {
            4 -> java.lang.Float.intBitsToFloat(readUnsigned(d, at, 4).toInt()).toDouble()
            8 -> java.lang.Double.longBitsToDouble(readUnsigned(d, at, 8))
            else -> 0.0
        }
    }
}
