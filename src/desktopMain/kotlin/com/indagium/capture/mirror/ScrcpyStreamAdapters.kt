@file:Suppress("MagicNumber")

package com.indagium.capture.mirror

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread

/**
 * Adapts a raw scrcpy v4.1 frame-meta video socket ([EmbeddedMirrorConnection.videoInput]) back
 * into a plain decodable Annex-B elementary stream, by discarding [ScrcpyPacketReader]'s per-packet
 * metadata (pts/config/key flags, session-meta records) and concatenating each packet's payload —
 * which is already Annex-B NAL data with start codes, exactly as the device's MediaCodec emitted it.
 * [JavaCvH264Decoder] (and its tests) keep consuming a plain [InputStream] unmodified; only the
 * transport underneath changed from `raw_stream=true` to the frame-meta protocol.
 *
 * The recording path ([com.indagium.capture.mirror.EmbeddedDeviceSession]) does NOT use this
 * adapter — it needs the per-packet PTS/config/key metadata this class discards, so it reads
 * [ScrcpyPacketReader] events directly instead.
 */
internal class ScrcpyToAnnexBInputStream(rawInput: InputStream) : InputStream() {
    private val reader = ScrcpyPacketReader(rawInput)
    private var headerRead = false
    private var pending: ByteArray? = null
    private var pendingOffset = 0
    private var eof = false

    override fun read(): Int {
        val single = ByteArray(1)
        val count = read(single, 0, 1)
        return if (count <= 0) -1 else single[0].toInt() and 0xff
    }

    // A premature EOFException from readNext() (the socket closing mid-record) is deliberately
    // treated the same as a clean end of stream here: the mirror decoder already tolerates an
    // ordinary InputStream EOF (see JavaCvH264Decoder), and re-throwing would surface a decode
    // error for what is, from the mirror's perspective, just "the connection ended" — the runtime's
    // own reconnect logic (EmbeddedMirrorRuntime) is what actually needs to see and act on this.
    @Suppress("SwallowedException")
    override fun read(destination: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (eof) return -1
        if (!headerRead) {
            headerRead = true
            when (reader.readHeader()) {
                is ScrcpyStreamHeader.Codec -> Unit
                ScrcpyStreamHeader.Disabled -> {
                    eof = true
                    return -1
                }
                ScrcpyStreamHeader.Error -> throw IOException("scrcpy reported a server configuration error")
            }
        }
        while (pending == null || pendingOffset >= requireNotNull(pending).size) {
            val event = try {
                reader.readNext()
            } catch (failure: EOFException) {
                eof = true
                return -1
            } ?: run {
                eof = true
                return -1
            }
            pending = when (event) {
                is ScrcpyStreamEvent.SessionMeta -> null
                is ScrcpyStreamEvent.Packet -> event.data
            }
            pendingOffset = 0
        }
        val data = requireNotNull(pending)
        val count = minOf(len, data.size - pendingOffset)
        System.arraycopy(data, pendingOffset, destination, off, count)
        pendingOffset += count
        return count
    }
}

/**
 * Bounded fan-out from the recording packet pump to an attached mirror decoder
 * ([EmbeddedDeviceSession.attachDecoder]), so a slow/stalled decoder can never back-pressure the
 * device socket reader that also feeds [com.indagium.capture.StreamingMkvWriter]. A dedicated pump
 * thread drains the queue into a [PipedOutputStream]/[PipedInputStream] pair that
 * [H264Decoder.decode] reads exactly like a real socket; when the bounded queue is full, packets
 * are dropped until the next key frame (config packets are always delivered — a decoder that missed
 * SPS/PPS can never recover without them).
 */
internal class BoundedAnnexBFeed(capacity: Int = DEFAULT_CAPACITY) : Closeable {
    private val queue = ArrayBlockingQueue<ByteArray>(capacity)
    private val pipeOut = PipedOutputStream()
    val input: PipedInputStream = PipedInputStream(pipeOut, PIPE_BUFFER_BYTES)

    @Volatile private var closed = false

    // Starts true, not false: a decoder normally attaches to an already-running recording (the
    // whole point of sharing one session — see EmbeddedDeviceSession.attachDecoder's doc), so a
    // delta frame can arrive as the very first frame packet this feed ever sees. Feeding a decoder
    // a delta frame before its first key frame means it references pictures it never decoded and
    // fails ("no frame!"); starting latched exactly as if the initial queue had already overflowed
    // makes attach-mid-stream behave the same as any other resync: wait for the next key frame.
    @Volatile private var droppedSinceKeyframe = true

    @Volatile var droppedPackets: Long = 0
        private set

    private val pump = thread(name = "mirror-feed-pump", isDaemon = true) {
        try {
            while (true) {
                val chunk = queue.take()
                if (chunk === POISON) break
                pipeOut.write(chunk)
                pipeOut.flush()
            }
        } catch (_: IOException) {
            // The decoder side closed its end (e.g. mirror disconnected) — stop pumping quietly.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { pipeOut.close() }
        }
    }

    /** Offers one packet. [config] packets bypass the drop policy entirely; an ordinary packet is
     * dropped (and [droppedSinceKeyframe] latched) once the queue is full, and every subsequent
     * non-key packet is dropped too until a fresh key frame arrives to resynchronize the decoder. */
    fun offer(data: ByteArray, config: Boolean, keyFrame: Boolean) {
        if (closed) return
        if (config) {
            forceOffer(data)
            return
        }
        if (droppedSinceKeyframe && !keyFrame) {
            droppedPackets++
            return
        }
        if (!queue.offer(data)) {
            droppedSinceKeyframe = true
            droppedPackets++
            return
        }
        if (keyFrame) droppedSinceKeyframe = false
    }

    /** Makes room for a packet that must never be dropped (a config/SPS-PPS packet) by discarding
     * the oldest queued packet(s) if necessary. */
    private fun forceOffer(data: ByteArray) {
        while (!queue.offer(data)) {
            queue.poll()
            droppedPackets++
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        queue.clear()
        forceOffer(POISON) // wake the pump thread
        pump.interrupt()
        runCatching { pump.join(FEED_CLOSE_JOIN_MS) }
        runCatching { input.close() }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
        const val PIPE_BUFFER_BYTES = 512 * 1024
        const val FEED_CLOSE_JOIN_MS = 500L
        val POISON = ByteArray(0)
    }
}
