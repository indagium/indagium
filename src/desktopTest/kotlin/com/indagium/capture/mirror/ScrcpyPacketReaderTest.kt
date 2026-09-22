@file:Suppress("MagicNumber")

package com.indagium.capture.mirror

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScrcpyPacketReaderTest {
    @Test
    fun readsVideoCodecHeaderThenSessionMetaThenPackets() {
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeInt(ScrcpyCodecIds.H264)
                // Session-meta record: flags (bit31 set, client-resize clear) + width + height.
                writeInt(0x80000000.toInt())
                writeInt(1080)
                writeInt(2400)
                // Config packet: ptsAndFlags == CONFIG_FLAG exactly, no real pts.
                writeLong(1L shl 62)
                writeInt(4)
                write(byteArrayOf(0, 0, 0, 1))
                // Key frame packet at pts=1000us.
                writeLong(1000L or (1L shl 61))
                writeInt(3)
                write(byteArrayOf(9, 8, 7))
                // Ordinary delta frame at pts=2000us.
                writeLong(2000L)
                writeInt(2)
                write(byteArrayOf(5, 6))
            }
        }.toByteArray()
        val reader = ScrcpyPacketReader(ByteArrayInputStream(bytes))

        val header = reader.readHeader()
        assertEquals(ScrcpyStreamHeader.Codec(ScrcpyCodecIds.H264), header)

        val session = reader.readNext()
        assertEquals(ScrcpyStreamEvent.SessionMeta(1080, 2400, clientResize = false), session)

        val config = reader.readNext() as ScrcpyStreamEvent.Packet
        assertTrue(config.config)
        assertTrue(!config.keyFrame)
        assertEquals(0L, config.ptsUs)
        assertContentEquals(byteArrayOf(0, 0, 0, 1), config.data)

        val key = reader.readNext() as ScrcpyStreamEvent.Packet
        assertTrue(!key.config)
        assertTrue(key.keyFrame)
        assertEquals(1000L, key.ptsUs)
        assertContentEquals(byteArrayOf(9, 8, 7), key.data)

        val delta = reader.readNext() as ScrcpyStreamEvent.Packet
        assertTrue(!delta.config)
        assertTrue(!delta.keyFrame)
        assertEquals(2000L, delta.ptsUs)
        assertContentEquals(byteArrayOf(5, 6), delta.data)

        assertNull(reader.readNext())
    }

    @Test
    fun sessionMetaCanRecurAfterAResizeWithoutBeingMistakenForAPacket() {
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeInt(ScrcpyCodecIds.H264)
                writeInt(0x80000000.toInt())
                writeInt(640)
                writeInt(480)
                // Rotation: a second session-meta record, this time client-resize set.
                writeInt(0x80000001.toInt())
                writeInt(480)
                writeInt(640)
            }
        }.toByteArray()
        val reader = ScrcpyPacketReader(ByteArrayInputStream(bytes))
        reader.readHeader()
        assertEquals(ScrcpyStreamEvent.SessionMeta(640, 480, clientResize = false), reader.readNext())
        assertEquals(ScrcpyStreamEvent.SessionMeta(480, 640, clientResize = true), reader.readNext())
        assertNull(reader.readNext())
    }

    @Test
    fun audioDisabledHeaderIsNotAnError() {
        val bytes = ByteArrayOutputStream().also { out -> DataOutputStream(out).writeInt(ScrcpyCodecIds.STREAM_DISABLED) }.toByteArray()
        val reader = ScrcpyPacketReader(ByteArrayInputStream(bytes))
        assertEquals(ScrcpyStreamHeader.Disabled, reader.readHeader())
    }

    @Test
    fun serverConfigurationErrorHeaderIsDistinctFromDisabled() {
        val bytes = ByteArrayOutputStream().also { out -> DataOutputStream(out).writeInt(ScrcpyCodecIds.STREAM_ERROR) }.toByteArray()
        val reader = ScrcpyPacketReader(ByteArrayInputStream(bytes))
        assertEquals(ScrcpyStreamHeader.Error, reader.readHeader())
    }

    @Test
    fun opusAudioHeaderIsRecognised() {
        val bytes = ByteArrayOutputStream().also { out -> DataOutputStream(out).writeInt(ScrcpyCodecIds.OPUS) }.toByteArray()
        val reader = ScrcpyPacketReader(ByteArrayInputStream(bytes))
        assertEquals(ScrcpyStreamHeader.Codec(ScrcpyCodecIds.OPUS), reader.readHeader())
    }

    @Test
    fun packetSizeAboveTheLimitIsRejected() {
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeLong(500L)
                writeInt(64)
            }
        }.toByteArray()
        val reader = ScrcpyPacketReader(ByteArrayInputStream(bytes), maxPacketBytes = 32)
        assertFailsWith<IllegalArgumentException> { reader.readNext() }
    }

    @Test
    fun eofMidHeaderThrows() {
        val reader = ScrcpyPacketReader(ByteArrayInputStream(byteArrayOf(0, 0, 0, 1, 2, 3)))
        assertFailsWith<EOFException> { reader.readNext() }
    }

    @Test
    fun eofMidPayloadThrows() {
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeLong(10L)
                writeInt(8)
                write(byteArrayOf(1, 2, 3)) // only 3 of the promised 8 bytes
            }
        }.toByteArray()
        val reader = ScrcpyPacketReader(ByteArrayInputStream(bytes))
        assertFailsWith<EOFException> { reader.readNext() }
    }

    @Test
    fun cleanEofExactlyOnARecordBoundaryReturnsNull() {
        val reader = ScrcpyPacketReader(ByteArrayInputStream(ByteArray(0)))
        assertNull(reader.readNext())
    }

    @Test
    fun readsAcrossShortChunkedReads() {
        // A live socket rarely hands back a full 12-byte record (or the whole payload) in one
        // read() call; a naive single-read implementation would silently truncate here.
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeLong(42L or (1L shl 61))
                writeInt(5)
                write(byteArrayOf(1, 2, 3, 4, 5))
            }
        }.toByteArray()
        val reader = ScrcpyPacketReader(OneByteAtATimeInputStream(bytes))
        val packet = reader.readNext() as ScrcpyStreamEvent.Packet
        assertEquals(42L, packet.ptsUs)
        assertTrue(packet.keyFrame)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), packet.data)
    }

    private class OneByteAtATimeInputStream(private val bytes: ByteArray) : InputStream() {
        private var offset = 0

        override fun read(): Int {
            if (offset >= bytes.size) return -1
            return bytes[offset++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (offset >= bytes.size) return -1
            if (len <= 0) return 0
            buffer[off] = bytes[offset++]
            return 1
        }
    }
}
