@file:Suppress("MagicNumber")

package com.indagium.capture.mirror

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * scrcpy v4.1 codec IDs: a 4-byte big-endian ASCII FourCC. Verified against the pinned server's
 * `video/VideoCodec.java` / `audio/AudioCodec.java` (tag v4.1) — do not "correct" these against an
 * older scrcpy protocol doc without re-checking the actual pinned server source.
 */
internal object ScrcpyCodecIds {
    const val H264 = 0x68_32_36_34
    const val H265 = 0x68_32_36_35
    const val AV1 = 0x00_61_76_31
    const val OPUS = 0x6f_70_75_73
    const val AAC = 0x00_61_61_63
    const val FLAC = 0x66_6c_61_63
    const val RAW_AUDIO = 0x00_72_61_77

    /** Written by `Streamer.writeDisableStream(false)`: this stream (always audio, in practice)
     * could not be captured (unsupported Android version, capture denied, etc). Not a failure —
     * the caller should continue with the other stream(s). */
    const val STREAM_DISABLED = 0x00_00_00_00

    /** Written by `Streamer.writeDisableStream(true)`: a genuine server-side configuration error.
     * The server is about to exit; treat as a transport failure. */
    const val STREAM_ERROR = 0x00_00_00_01
}

internal sealed interface ScrcpyStreamHeader {
    data class Codec(val id: Int) : ScrcpyStreamHeader

    /** See [ScrcpyCodecIds.STREAM_DISABLED]. */
    data object Disabled : ScrcpyStreamHeader

    /** See [ScrcpyCodecIds.STREAM_ERROR]. */
    data object Error : ScrcpyStreamHeader
}

internal sealed interface ScrcpyStreamEvent {
    /**
     * Written by `Streamer.writeSessionMeta`: once when the stream starts, and again after every
     * encoder resize/reconfigure (rotation, client-requested resize, display change). Video only —
     * the audio streamer never calls it. Carries no packet payload of its own.
     */
    data class SessionMeta(val width: Int, val height: Int, val clientResize: Boolean) : ScrcpyStreamEvent

    data class Packet(val ptsUs: Long, val config: Boolean, val keyFrame: Boolean, val data: ByteArray) : ScrcpyStreamEvent
}

/**
 * Parses one scrcpy v4.1 socket's frame-meta wire format, as written by the pinned server when
 * launched with `send_stream_meta=true send_frame_meta=true send_device_meta=false` (see
 * [AdbScrcpyTransport]). Verified line-by-line against the pinned server's
 * `device/Streamer.java`/`device/DesktopConnection.java`/`video/SurfaceEncoder.java` (tag v4.1):
 *
 * - One-time 4-byte header ([readHeader]): a big-endian codec FourCC (see [ScrcpyCodecIds]), or one
 *   of the two "disable" sentinels.
 * - Then a repeating stream of 12-byte records ([readNext]), each either:
 *   - a **session-meta** record (video only): `flags:Int` (bit 31 always set — the marker this
 *     parser keys off; bit 0 = client-resize) + `width:Int` + `height:Int`, no trailing payload; or
 *   - a **packet header**: `ptsAndFlags:Long` (bit 63 = session marker, already excluded above; bit
 *     62 = config/non-media packet; bit 61 = key frame; the rest is the PTS in microseconds for a
 *     non-config packet) + `size:Int`, followed by exactly `size` payload bytes.
 *
 * This bit layout (SESSION=63, CONFIG=62, KEY_FRAME=61) is what the currently pinned v4.1 server
 * actually writes — NOT the "CONFIG=63, KEY_FRAME=62" layout some older scrcpy protocol
 * descriptions use for pre-SESSION-flag server builds. Re-verify against the pinned server's
 * `Streamer.java` before changing these constants.
 */
internal class ScrcpyPacketReader(
    private val input: InputStream,
    private val maxPacketBytes: Int = DEFAULT_MAX_PACKET_BYTES,
) {
    init { require(maxPacketBytes > 0) { "maxPacketBytes must be positive" } }

    /** Reads the one-time 4-byte stream header. Must be called exactly once, before [readNext]. */
    fun readHeader(): ScrcpyStreamHeader {
        val bytes = ByteArray(4)
        readFully(bytes, "scrcpy stream header")
        return when (val id = beInt(bytes, 0)) {
            ScrcpyCodecIds.STREAM_DISABLED -> ScrcpyStreamHeader.Disabled
            ScrcpyCodecIds.STREAM_ERROR -> ScrcpyStreamHeader.Error
            else -> ScrcpyStreamHeader.Codec(id)
        }
    }

    /**
     * Reads the next session-meta record or packet. Returns null only on a clean EOF that lands
     * exactly on a 12-byte record boundary (the socket closed between records); an EOF partway
     * through a header or payload throws [EOFException] — a truncated mid-record read is always a
     * transport failure, never a normal end of stream.
     */
    fun readNext(): ScrcpyStreamEvent? {
        val record = readRecord() ?: return null
        val isSessionMeta = (record[0].toInt() and 0x80) != 0
        return if (isSessionMeta) {
            val flags = beInt(record, 0)
            val width = beInt(record, 4)
            val height = beInt(record, 8)
            ScrcpyStreamEvent.SessionMeta(width, height, clientResize = (flags and 1) != 0)
        } else {
            val ptsAndFlags = beLong(record, 0)
            val size = beInt(record, 8)
            require(size in 0..maxPacketBytes) {
                "scrcpy packet size $size is outside the accepted 0..$maxPacketBytes range"
            }
            val config = (ptsAndFlags and CONFIG_FLAG) != 0L
            val keyFrame = (ptsAndFlags and KEY_FRAME_FLAG) != 0L
            val pts = ptsAndFlags and PTS_MASK
            val data = ByteArray(size)
            readFully(data, "scrcpy packet payload")
            ScrcpyStreamEvent.Packet(pts, config, keyFrame, data)
        }
    }

    private fun readRecord(): ByteArray? {
        val buffer = ByteArray(RECORD_BYTES)
        var offset = 0
        while (offset < RECORD_BYTES) {
            val read = input.read(buffer, offset, RECORD_BYTES - offset)
            if (read < 0) {
                if (offset == 0) return null
                throw EOFException("scrcpy stream ended mid record (got $offset/$RECORD_BYTES bytes)")
            }
            offset += read
        }
        return buffer
    }

    private fun readFully(destination: ByteArray, what: String) {
        var offset = 0
        while (offset < destination.size) {
            val read = input.read(destination, offset, destination.size - offset)
            if (read < 0) throw EOFException("scrcpy stream ended while reading $what (got $offset/${destination.size} bytes)")
            offset += read
        }
    }

    companion object {
        private const val RECORD_BYTES = 12

        // Bit 63 (SESSION_FLAG) is checked directly against record[0]'s top bit in readNext()
        // rather than via a Long mask — cheaper and avoids building the 8-byte value at all for
        // the (far more common) non-session-meta path.
        private const val CONFIG_FLAG = 1L shl 62
        private const val KEY_FRAME_FLAG = 1L shl 61
        private const val PTS_MASK = (1L shl 61) - 1
        const val DEFAULT_MAX_PACKET_BYTES = 32 * 1024 * 1024
    }
}

private fun beInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 24) or
        ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or
        (bytes[offset + 3].toInt() and 0xff)

private fun beLong(bytes: ByteArray, offset: Int): Long =
    ((beInt(bytes, offset).toLong() and 0xffffffffL) shl 32) or (beInt(bytes, offset + 4).toLong() and 0xffffffffL)

/** Thrown when [ScrcpyPacketReader.readHeader] sees [ScrcpyStreamHeader.Error] — the server hit a
 * genuine configuration error and is about to exit; distinct from [ScrcpyStreamHeader.Disabled]. */
internal class ScrcpyStreamErrorException(message: String) : IOException(message)
