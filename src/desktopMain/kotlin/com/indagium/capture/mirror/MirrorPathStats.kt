package com.indagium.capture.mirror

import com.indagium.debug.AppLogger
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free progress counters for one device stream (video or audio) between the adb-forwarded
 * socket and the muxer. Everything is an atomic so a reader thread can bump a counter per packet
 * without contention and a diagnostic thread can read them without ever taking a lock (the whole
 * point is to still work when the recorder/session locks are the thing that is stuck).
 *
 * Event times are capture-clock milliseconds ([NEVER] = has not happened yet).
 */
internal class StreamPathCounters(private val clockMs: () -> Long, private val onFirst: (String) -> Unit = {}) {
    val socketAttempts = AtomicInteger(0)
    val socketConnectedAtMs = AtomicLong(NEVER)

    /** 0 = probe not run, 1 = first byte arrived during the readiness probe, 2 = probe timed out with no data. */
    val probeOutcome = AtomicInteger(0)
    val headerAtMs = AtomicLong(NEVER)
    val codecId = AtomicLong(NEVER)
    val bytesRead = AtomicLong(0)
    val readCalls = AtomicLong(0)
    val firstBytesAtMs = AtomicLong(NEVER)
    val sessionMetaRecords = AtomicLong(0)
    val packets = AtomicLong(0)
    val configPackets = AtomicLong(0)
    val keyFrames = AtomicLong(0)
    val firstConfigAtMs = AtomicLong(NEVER)
    val firstKeyFrameAtMs = AtomicLong(NEVER)

    /** Monotonic nanoTime of the last read that returned data; 0 = never. */
    val lastReadNanos = AtomicLong(0)

    fun socketConnected() {
        socketConnectedAtMs.compareAndSet(NEVER, clockMs())
    }

    fun headerRead(id: Int) {
        codecId.set(id.toLong() and UNSIGNED_INT_MASK)
        headerAtMs.compareAndSet(NEVER, clockMs())
    }

    fun bytes(count: Int) {
        if (count <= 0) return
        bytesRead.addAndGet(count.toLong())
        readCalls.incrementAndGet()
        lastReadNanos.set(System.nanoTime())
        if (firstBytesAtMs.get() == NEVER && firstBytesAtMs.compareAndSet(NEVER, clockMs())) {
            onFirst("first bytes arrived")
        }
    }

    fun event(event: ScrcpyStreamEvent) {
        when (event) {
            is ScrcpyStreamEvent.SessionMeta -> sessionMetaRecords.incrementAndGet()
            is ScrcpyStreamEvent.Packet -> {
                packets.incrementAndGet()
                if (event.config) {
                    configPackets.incrementAndGet()
                    if (firstConfigAtMs.get() == NEVER && firstConfigAtMs.compareAndSet(NEVER, clockMs())) {
                        onFirst("first config packet arrived")
                    }
                }
                if (event.keyFrame) {
                    keyFrames.incrementAndGet()
                    if (firstKeyFrameAtMs.get() == NEVER && firstKeyFrameAtMs.compareAndSet(NEVER, clockMs())) {
                        onFirst("first key frame arrived")
                    }
                }
            }
        }
    }

    fun describe(nowNanos: Long = System.nanoTime()): String {
        val lastRead = lastReadNanos.get()
        val ago = if (lastRead == 0L) "never" else "${(nowNanos - lastRead) / NANOS_PER_MS}ms ago"
        val codec = codecId.get().let { if (it == NEVER) "none" else "0x" + it.toString(HEX_RADIX) }
        val probe = when (probeOutcome.get()) {
            1 -> "byte"
            2 -> "timeout"
            else -> "none"
        }
        return "socketAttempts=${socketAttempts.get()} connectedAt=${ms(socketConnectedAtMs)} probe=$probe " +
            "headerAt=${ms(headerAtMs)} codec=$codec bytes=${bytesRead.get()} reads=${readCalls.get()} " +
            "firstBytesAt=${ms(firstBytesAtMs)} lastRead=$ago meta=${sessionMetaRecords.get()} " +
            "packets=${packets.get()} config=${configPackets.get()} keyFrames=${keyFrames.get()} " +
            "firstConfigAt=${ms(firstConfigAtMs)} firstKeyFrameAt=${ms(firstKeyFrameAtMs)}"
    }

    companion object {
        const val NEVER = Long.MIN_VALUE
        private const val NANOS_PER_MS = 1_000_000L
        private const val HEX_RADIX = 16
        private const val UNSIGNED_INT_MASK = 0xffffffffL

        internal fun ms(value: AtomicLong): String = value.get().let { if (it == NEVER) "never" else "${it}ms" }
    }
}

/**
 * Cheap, lock-free observability for the host side of one capture's device streams: how far the
 * video (and, for comparison, audio) stream got between the adb socket and the muxer. Bumped from
 * the socket reader/pump threads and the transport; read by the one-shot "no video" diagnostic
 * (see `NoVideoDiagnostic`) and, once per stage, logged as timing baselines ("Video path: ...")
 * so successful captures leave a reference in the debug log too. Never per-packet logging.
 */
internal class MirrorPathStats(
    private val clockMs: () -> Long,
    private val log: (String) -> Unit = { AppLogger.info("capture", it) },
) {
    val video = StreamPathCounters(clockMs) { what -> log("Video path: $what ${clockMs()} ms after capture start") }
    val audio = StreamPathCounters(clockMs)

    val transportOpenStartedAtMs = AtomicLong(StreamPathCounters.NEVER)
    val transportOpenReturnedAtMs = AtomicLong(StreamPathCounters.NEVER)
    val transportOpens = AtomicInteger(0)
    val muxerStartedAtMs = AtomicLong(StreamPathCounters.NEVER)
    val muxedVideoPackets = AtomicLong(0)
    val muxedAudioPackets = AtomicLong(0)

    fun transportOpenStarted() {
        transportOpens.incrementAndGet()
        transportOpenStartedAtMs.set(clockMs())
    }

    fun transportOpenReturned() {
        transportOpenReturnedAtMs.set(clockMs())
    }

    fun muxerStarted() {
        muxerStartedAtMs.compareAndSet(StreamPathCounters.NEVER, clockMs())
    }

    fun countersFor(role: String): StreamPathCounters? = when (role) {
        "video" -> video
        "audio" -> audio
        else -> null
    }

    /** One line with every counter; safe to call from any thread at any time. */
    fun describe(): String =
        "transport{opens=${transportOpens.get()} lastOpenStartedAt=${StreamPathCounters.ms(transportOpenStartedAtMs)} " +
            "lastOpenReturnedAt=${StreamPathCounters.ms(transportOpenReturnedAtMs)}} " +
            "video{${video.describe()}} audio{${audio.describe()}} " +
            "muxer{startedAt=${StreamPathCounters.ms(muxerStartedAtMs)} videoPackets=${muxedVideoPackets.get()} " +
            "audioPackets=${muxedAudioPackets.get()}}"
}

/** Counts bytes/reads passing through [delegate] into [counters]; adds only a few atomic ops per read. */
internal class CountingInputStream(delegate: InputStream, private val counters: StreamPathCounters) : FilterInputStream(delegate) {
    override fun read(): Int {
        val value = super.read()
        if (value >= 0) counters.bytes(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) counters.bytes(count)
        return count
    }
}
