@file:Suppress("MagicNumber")

package com.indagium.capture.mirror

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
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
 * Reads scrcpy frame metadata on a transport-side pump and exposes a bounded Annex-B decoder
 * input. Config/SPS-PPS packets always survive queue pressure; delta packets are discarded after
 * an overflow until the next keyframe, so a decoder never receives an unbounded stale backlog or
 * a delta frame that references pictures it missed. Session-meta records are consumed here.
 *
 * This is used by standalone mirror-only captures before either JavaCV decoder. The recording
 * session's shared mirror path uses [BoundedAnnexBFeed] directly because it already owns the
 * packet-reader loop and must mux the same metadata.
 */
internal class BoundedScrcpyAnnexBFeed(
    private val rawInput: InputStream,
    capacity: Int = DEFAULT_CAPACITY,
) : Closeable {
    private val feed = BoundedAnnexBFeed(capacity)
    val input: InputStream = feed.input
    private val pump = thread(name = "scrcpy-mirror-packet-feed", isDaemon = true) {
        try {
            val reader = ScrcpyPacketReader(rawInput)
            when (reader.readHeader()) {
                is ScrcpyStreamHeader.Codec -> Unit
                ScrcpyStreamHeader.Disabled -> throw IOException("scrcpy video stream is disabled")
                ScrcpyStreamHeader.Error -> throw ScrcpyStreamErrorException("scrcpy reported a server configuration error")
            }
            while (true) {
                val event = reader.readNext() ?: break
                when (event) {
                    is ScrcpyStreamEvent.SessionMeta -> Unit
                    is ScrcpyStreamEvent.Packet -> feed.offer(event.data, event.config, event.keyFrame)
                }
            }
        } catch (_: EOFException) {
            // A socket closing at a record boundary is the normal reconnect signal owned by the
            // runtime; closing the decoder side below turns it into an ordinary input EOF.
        } catch (_: IOException) {
            // The decoder sees EOF and the runtime starts its bounded reconnect sequence.
        } finally {
            // A clean socket EOF still has packets queued in the bounded fan-out. Finish by
            // appending its poison marker and draining those packets; close() is reserved for an
            // explicit detach, where dropping queued data is intentional.
            feed.finish()
        }
    }

    val droppedPackets: Long get() = feed.droppedPackets

    override fun close() {
        feed.close()
        runCatching { rawInput.close() }
        if (pump !== Thread.currentThread()) runCatching { pump.join(FEED_CLOSE_JOIN_MS) }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
        const val FEED_CLOSE_JOIN_MS = 500L
    }
}

/** Packet-preserving mirror-only fan-out. One scrcpy packet remains one VideoToolbox access unit;
 * switching to Compose creates a fresh bounded Annex-B pipe at the next key frame on the same
 * socket, so neither path needs a second scrcpy connection. */
internal data class ScrcpyPacketFeedDiagnostics(
    val queuedPackets: Int,
    val queuedConfigPackets: Int,
    val queuedBytes: Long,
    val oldestPacketAgeMs: Long,
    val droppedPackets: Long,
    val resyncEvents: Long,
    val resyncPending: Boolean,
)

internal class BoundedScrcpyPacketFeed(
    private val rawInput: InputStream,
    private val nanoTime: () -> Long = System::nanoTime,
    private val maxAgeNs: Long = MAX_AGE_NS,
    private val maxBytes: Long = MAX_BYTES,
    private val packetLimit: Int = MAX_PACKETS,
    startPump: Boolean = true,
    private val closeInputOnClose: Boolean = true,
) : Closeable {
    data class Packet(val ptsUs: Long, val config: Boolean, val keyFrame: Boolean, val data: ByteArray, val enqueuedNs: Long = System.nanoTime())

    private val lock = Object()
    private val queue = ArrayDeque<Packet>()
    private var queuedBytes = 0L
    private var latestConfig: Packet? = null
    private var resync = true
    private var resyncEvents = 0L
    private var closed = false
    private var eof = false
    private var compose: BoundedAnnexBFeed? = null

    @Volatile var droppedPackets = 0L
        private set

    init {
        require(maxAgeNs >= 0)
        require(maxBytes > 0)
        require(packetLimit > 0)
    }

    private val pump: Thread? = if (startPump) {
        thread(name = "scrcpy-mirror-packet-reader", isDaemon = true) {
            try {
                val reader = ScrcpyPacketReader(rawInput)
                when (reader.readHeader()) {
                    is ScrcpyStreamHeader.Codec -> Unit
                    else -> throw IOException("scrcpy video stream is unavailable")
                }
                while (true) {
                    val event = reader.readNext() ?: break
                    if (event is ScrcpyStreamEvent.Packet) {
                        offer(Packet(event.ptsUs, event.config, event.keyFrame, event.data, nanoTime()))
                    }
                }
            } catch (_: IOException) {
            } finally {
                val activeCompose = synchronized(lock) { eof = true; lock.notifyAll(); compose }
                activeCompose?.finish()
            }
        }
    } else {
        null
    }

    fun nextPacket(): Packet? = synchronized(lock) {
        while (true) {
            expire()
            if (queue.isNotEmpty() || eof || closed) break
            lock.wait()
        }
        // The producer may have woken us after a slow wait; freshness is evaluated at dequeue.
        expire()
        if (closed || queue.isEmpty()) null else queue.removeFirst().also { queuedBytes -= it.data.size }
    }

    fun switchToCompose(): BoundedAnnexBFeed = synchronized(lock) {
        if (closed) {
            return@synchronized compose ?: BoundedAnnexBFeed().also {
                compose = it
                it.finish()
            }
        }
        compose ?: BoundedAnnexBFeed().also {
            queue.clear(); queuedBytes = 0L; resync = true; compose = it
            latestConfig?.let { config -> it.offer(config.data, config = true, keyFrame = false) }
            if (eof) it.finish()
        }
    }

    /** Test seam for feeding controlled packets without starting the socket reader thread. */
    internal fun offerPacket(packet: Packet) = offer(packet)

    /**
     * Marks an externally-fed stream complete without closing its source input. Recording-session
     * mirrors use this mode: their recorder owns the device socket across reconnects, while this
     * feed only owns the bounded decoder queue.
     */
    internal fun finish() = synchronized(lock) {
        eof = true
        lock.notifyAll()
        compose?.finish()
    }

    internal fun diagnostics(): ScrcpyPacketFeedDiagnostics = synchronized(lock) {
        expire()
        val oldestAgeMs = queue.firstOrNull()?.let { (nanoTime() - it.enqueuedNs).coerceAtLeast(0L) / 1_000_000L } ?: 0L
        ScrcpyPacketFeedDiagnostics(
            queuedVideoPacketCount(),
            queue.count { it.config },
            queuedBytes + cachedConfigBytesOutsideQueue(),
            oldestAgeMs,
            droppedPackets,
            resyncEvents,
            resync,
        )
    }

    private fun offer(packet: Packet) = synchronized(lock) {
        if (closed) return@synchronized
        expire()
        compose?.let { it.offer(packet.data, packet.config, packet.keyFrame); droppedPackets = it.droppedPackets; return@synchronized }
        if (packet.config) {
            if (packet.data.size > maxBytes) {
                droppedPackets++
                clear()
                if (!resync) resyncEvents++
                resync = true
                latestConfig = null
                return@synchronized
            }
            // Only the newest SPS/PPS has value for future recovery. Keep one config packet in
            // the delivery queue as well as the replay cache, so a stream with frequent encoder
            // reconfiguration cannot grow queue nodes beyond the 3-picture bound.
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val queued = iterator.next()
                if (queued.config) {
                    iterator.remove()
                    queuedBytes -= queued.data.size
                    droppedPackets++
                }
            }
            latestConfig = packet
            // Config is retained/replayed independently of the three-packet video budget. It may
            // sit alongside three queued pictures; only the byte ceiling can force their eviction.
            if (queuedBytes + packet.data.size > maxBytes) {
                if (!resync) resyncEvents++
                clear()
                resync = true
            }
            add(packet)
            return@synchronized
        }
        if (packet.keyFrame) {
            if (!resync && !fits(packet)) resyncEvents++
            if (resync || !fits(packet)) {
                clear()
                latestConfig?.copy(enqueuedNs = nanoTime())?.takeIf(::fitsCachedConfig)?.let(::add)
            }
            if (fits(packet)) { add(packet); resync = false } else { droppedPackets++; resync = true }
        } else if (resync || !fits(packet)) {
            if (!resync) {
                clear()
                resyncEvents++
            }
            resync = true
            droppedPackets++
        } else {
            add(packet)
        }
    }

    private fun fits(packet: Packet) =
        queuedVideoPacketCount() < packetLimit &&
            packet.data.size <= maxBytes &&
            queuedBytes + cachedConfigBytesOutsideQueue() + packet.data.size <= maxBytes

    private fun fitsCachedConfig(packet: Packet) =
        queuedVideoPacketCount() < packetLimit && packet.data.size <= maxBytes && queuedBytes + packet.data.size <= maxBytes

    private fun queuedVideoPacketCount(): Int = queue.count { !it.config }

    private fun cachedConfigBytesOutsideQueue(): Long = latestConfig
        ?.takeUnless { config -> queue.any { it.data === config.data } }
        ?.data
        ?.size
        ?.toLong()
        ?: 0L

    private fun add(packet: Packet) { queue.addLast(packet); queuedBytes += packet.data.size; lock.notifyAll() }

    private fun clear() { droppedPackets += queue.size; queue.clear(); queuedBytes = 0L }

    private fun expire() {
        val cutoff = nanoTime() - maxAgeNs
        var stale = false
        while (queue.firstOrNull()?.enqueuedNs?.let { it < cutoff } == true) {
            val item = queue.removeFirst()
            queuedBytes -= item.data.size
            droppedPackets++
            stale = true
        }
        // A stale picture can be a reference for every remaining delta picture. Never hand those
        // younger deltas to VideoToolbox after one was lost: retain SPS/PPS externally and wait
        // for the next IDR just as an overflow does.
        if (stale) {
            clear()
            if (!resync) resyncEvents++
            resync = true
        }
    }

    override fun close() {
        synchronized(lock) { closed = true; clear(); lock.notifyAll() }
        compose?.close()
        if (closeInputOnClose) runCatching { rawInput.close() }
        pump?.takeIf { it !== Thread.currentThread() }?.join(500)
    }

    private companion object {
        const val MAX_AGE_NS = 75_000_000L
        const val MAX_BYTES = 32 * 1024 * 1024L
        const val MAX_PACKETS = 3
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
internal data class MirrorFeedDiagnostics(
    val queuedPackets: Int,
    val queuedBytes: Long,
    val oldestPacketAgeMs: Long,
    val droppedPackets: Long,
    val resyncPending: Boolean,
)

internal class BoundedAnnexBFeed(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val nanoTime: () -> Long = System::nanoTime,
) : Closeable {
    private data class Packet(val data: ByteArray, val enqueuedAtNs: Long)

    private val lock = Object()
    private val queue = ArrayDeque<Packet>()
    private var queuedBytes = 0L
    private val pipeOut = PipedOutputStream()
    val input: PipedInputStream = PipedInputStream(pipeOut, PIPE_BUFFER_BYTES)

    @Volatile private var closed = false

    @Volatile private var finished = false

    // Starts true, not false: a decoder normally attaches to an already-running recording (the
    // whole point of sharing one session — see EmbeddedDeviceSession.attachDecoder's doc), so a
    // delta frame can arrive as the very first frame packet this feed ever sees. Feeding a decoder
    // a delta frame before its first key frame means it references pictures it never decoded and
    // fails ("no frame!"); starting latched exactly as if the initial queue had already overflowed
    // makes attach-mid-stream behave the same as any other resync: wait for the next key frame.
    @Volatile private var droppedSinceKeyframe = true

    @Volatile private var latestConfig: ByteArray? = null

    @Volatile var droppedPackets: Long = 0
        private set

    private val pump = thread(name = "mirror-feed-pump", isDaemon = true) {
        try {
            while (true) {
                val chunk = synchronized(lock) {
                    while (queue.isEmpty() && !closed && !finished) lock.wait()
                    if (closed || (finished && queue.isEmpty())) {
                        null
                    } else {
                        queue.removeFirst().also { queuedBytes -= it.data.size.toLong() }
                    }
                } ?: break
                pipeOut.write(chunk.data)
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
    fun offer(data: ByteArray, config: Boolean, keyFrame: Boolean) = synchronized(lock) {
        if (closed || finished) return@synchronized
        expireStaleLocked()
        val copy = data.copyOf()
        if (config) {
            latestConfig = copy
            // Config is retained outside the bounded live queue. It is replayed immediately
            // before the recovery key frame, even if pressure subsequently evicts this copy.
            if (fitsLocked(copy)) enqueueLocked(copy)
            return@synchronized
        }
        if (keyFrame) {
            if (droppedSinceKeyframe) {
                clearQueuedLocked(countAsDrops = false)
                latestConfig?.takeIf(::fitsLocked)?.let(::enqueueLocked)
            }
            if (!fitsLocked(copy)) {
                clearQueuedLocked()
                latestConfig?.takeIf(::fitsLocked)?.let(::enqueueLocked)
            }
            if (fitsLocked(copy)) {
                enqueueLocked(copy)
                droppedSinceKeyframe = false
            } else {
                droppedPackets++
                droppedSinceKeyframe = true
            }
            return@synchronized
        }
        if (droppedSinceKeyframe || !fitsLocked(copy)) {
            if (!droppedSinceKeyframe) clearQueuedLocked()
            droppedSinceKeyframe = true
            droppedPackets++
            return@synchronized
        }
        enqueueLocked(copy)
    }

    /** A low-rate, allocation-free snapshot suitable for capture diagnostics. */
    fun diagnostics(): MirrorFeedDiagnostics = synchronized(lock) {
        expireStaleLocked()
        val now = nanoTime()
        MirrorFeedDiagnostics(
            queuedPackets = queue.size,
            queuedBytes = queuedBytes,
            oldestPacketAgeMs = queue.firstOrNull()?.let { (now - it.enqueuedAtNs).coerceAtLeast(0L) / NANOS_PER_MILLI } ?: 0L,
            droppedPackets = droppedPackets,
            resyncPending = droppedSinceKeyframe,
        )
    }

    /** Discards queued deltas after a decoder replacement and waits for a fresh key frame. */
    fun requestResync() = synchronized(lock) {
        if (closed || finished) return@synchronized
        clearQueuedLocked()
        droppedSinceKeyframe = true
    }

    private fun fitsLocked(data: ByteArray): Boolean =
        queue.size < capacity && data.size.toLong() <= maxBytes && queuedBytes + data.size <= maxBytes

    private fun enqueueLocked(data: ByteArray) {
        queue.addLast(Packet(data, nanoTime()))
        queuedBytes += data.size.toLong()
        lock.notifyAll()
    }

    private fun clearQueuedLocked(countAsDrops: Boolean = true) {
        if (countAsDrops && queue.isNotEmpty()) droppedPackets += queue.size
        queue.clear()
        queuedBytes = 0L
    }

    private fun expireStaleLocked() {
        val oldestAllowed = nanoTime() - maxAgeMs * NANOS_PER_MILLI
        var removed = false
        while (queue.firstOrNull()?.enqueuedAtNs?.let { it < oldestAllowed } == true) {
            val stale = queue.removeFirst()
            queuedBytes -= stale.data.size.toLong()
            droppedPackets++
            removed = true
        }
        if (removed) droppedSinceKeyframe = true
        if (removed) {
            // Remaining packets can reference an expired picture. Retain the separate latest
            // config cache, discard this GOP, and recover only at the next key frame.
            clearQueuedLocked()
        }
    }

    /** Ends the producer while allowing already accepted packets to reach the decoder first. */
    fun finish() {
        synchronized(lock) {
            if (closed || finished) return
            finished = true
            lock.notifyAll()
        }
        runCatching { pump.join(FEED_CLOSE_JOIN_MS) }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            clearQueuedLocked()
            lock.notifyAll()
        }
        pump.interrupt()
        runCatching { pump.join(FEED_CLOSE_JOIN_MS) }
        runCatching { input.close() }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 3
        const val DEFAULT_MAX_AGE_MS = 75L
        const val DEFAULT_MAX_BYTES = 32L * 1024L * 1024L
        const val NANOS_PER_MILLI = 1_000_000L
        const val PIPE_BUFFER_BYTES = 512 * 1024
        const val FEED_CLOSE_JOIN_MS = 500L
    }
}
