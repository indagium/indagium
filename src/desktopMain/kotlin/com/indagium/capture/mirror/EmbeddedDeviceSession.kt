@file:Suppress("TooGenericExceptionCaught")

package com.indagium.capture.mirror

import com.indagium.capture.StreamingMkvWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Owns one embedded scrcpy device stream end to end for [com.indagium.capture.CaptureRecorder]:
 * opens the transport, parses the frame-meta protocol directly (no Annex-B re-flattening — the
 * muxer needs real PTS/config/key-frame boundaries, unlike the mirror-only path in
 * [EmbeddedMirrorRuntime]/[ScrcpyToAnnexBInputStream]), writes video (+ optional audio) straight
 * into [StreamingMkvWriter], and reconnects with PTS continuity on a stream drop. A live mirror
 * decoder can be attached/detached at any time via [attachDecoder]/[detachDecoder] without
 * disturbing the recording — see [BoundedAnnexBFeed] for how a slow/stalled decoder is kept from
 * ever blocking the socket reader that also feeds the muxer.
 *
 * Not currently wired to share one server session with a live [EmbeddedMirrorRuntime] connection —
 * see this class's call site in `CaptureRecorder` for the current scope.
 */
internal class EmbeddedDeviceSession(
    private val transport: EmbeddedMirrorTransport,
    private val muxer: StreamingMkvWriter,
    private val elapsedMillis: () -> Long,
    private val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
    private val reconnectDelay: Duration = Duration.ofMillis(DEFAULT_RECONNECT_DELAY_MS),
    private val onDiagnostic: (String) -> Unit = {},
    private val onVideoStartElapsedMs: (Long) -> Unit = {},
    // Test-only observability seam (mirrors FfmpegCaptureVideoExporter's reencodeDiagnosticsHook
    // pattern): production never sets this; tests use it to verify the actual output PTS sequence
    // — including across a reconnect — without needing to decode the (often synthetic, non-H.264)
    // packet bytes a unit test writes. See EmbeddedDeviceSessionTest.
    private val onVideoPacketWrittenHook: ((ptsUs: Long, keyFrame: Boolean) -> Unit)? = null,
    private val onAudioPacketWrittenHook: ((ptsUs: Long) -> Unit)? = null,
) : Closeable {
    private val lock = Any()
    private val generation = AtomicLong(0)
    private var worker: Thread? = null
    private var stopping = false
    private var currentConnection: EmbeddedMirrorConnection? = null
    private var connectionSnapshotValue = EmbeddedMirrorSnapshot()

    // This session's own device-connection state (CONNECTING/LIVE/RECONNECTING/FAILED),
    // independent of whether a mirror decoder is attached: a shared mirror source
    // (EmbeddedMirrorHandle.createShared in ui/) layers frame data on top of this same snapshot,
    // via addConnectionListener — registered lazily, whenever a mirror actually attaches, rather
    // than at construction time (CaptureRecorder builds this session before any mirror UI exists to
    // listen), so the panel's Connect/Disconnect state reflects the recording session's own
    // reconnects even though nothing may be listening at all for most of a capture's lifetime.
    private val connectionListeners = CopyOnWriteArrayList<(EmbeddedMirrorSnapshot) -> Unit>()

    /** Registers [listener] for every future connection-state change and immediately replays the
     * current one. The returned [Closeable] unregisters it. */
    fun addConnectionListener(listener: (EmbeddedMirrorSnapshot) -> Unit): Closeable {
        connectionListeners.add(listener)
        runCatching { listener(connectionSnapshot()) }
        return Closeable { connectionListeners.remove(listener) }
    }

    // Video PTS bookkeeping — survives reconnects, protected by [lock].
    // @Volatile so hasStartedVideo() can be read lock-free — see that function's doc. Every write
    // still happens under [lock] alongside the muxer.start()/addAudio() calls it's paired with;
    // @Volatile only adds a safe, un-synchronized *read* path, it doesn't remove the existing
    // synchronized writers' mutual exclusion.
    @Volatile private var muxerStarted = false
    private var firstAnchorPtsUs: Long? = null
    private var ptsOffsetUs = 0L
    private var lastOutputPtsUs = -1L
    private var pendingWidth = 0
    private var pendingHeight = 0
    private var audioStarted = false

    /** The most recent video SPS/PPS config bytes seen — replayed to a decoder that
     * [attachDecoder]es after the original config packet already went by. See that function's doc. */
    private var lastConfigBytes: ByteArray? = null

    // Coordinates the muxer's one-time start against BOTH sockets, not just video: video packets
    // start flowing well before a device's MediaCodec-based audio encoder finishes its (async)
    // setup, so without this, video's first config packet would call muxer.start() before audio's
    // config ever arrived — and StreamingMkvWriter.addAudio() refuses a stream once the header is
    // already written, silently leaving the recording video-only with no audio track at all. See
    // EmbeddedDeviceSessionTest's audio-arrives-late tests and this file's own bug history.
    private var audioResolved = false
    private var pendingAudioExtradata: ByteArray? = null

    // Attached mirror decoder — independent of the recording/reconnect lifecycle above. Exactly
    // one feed is live at a time. The packet-preserving branch keeps the recording's source PTS
    // and access-unit boundaries for VideoToolbox/Metal, while the Annex-B branch remains the
    // portable JavaCV/Compose fallback.
    private var decoderFeed: BoundedAnnexBFeed? = null
    private var directDecoderFeed: BoundedScrcpyPacketFeed? = null
    private var decoderThread: Thread? = null
    private var attachedDecoder: Closeable? = null

    fun start(deviceSerial: String, options: MirrorStreamOptions) {
        // Published before the worker thread is even created (not inside the same synchronized
        // block as before): publishConnectionSnapshot() itself briefly takes [lock] and then calls
        // external listeners *without* holding it — see that function's doc — so a fast/synchronous
        // fake transport in a test could otherwise have the worker thread publish LIVE before this
        // CONNECTING publish ran, if both raced inside one lock scope.
        publishConnectionSnapshot(EmbeddedMirrorSnapshot(EmbeddedMirrorState.CONNECTING, deviceSerial = deviceSerial))
        synchronized(lock) {
            stopping = false
            val runId = generation.incrementAndGet()
            worker = thread(name = "embedded-recording-$deviceSerial", isDaemon = true) {
                runSession(runId, deviceSerial, options)
            }
        }
    }

    fun stop() {
        val threadToJoin: Thread?
        val connectionToClose: EmbeddedMirrorConnection?
        synchronized(lock) {
            stopping = true
            generation.incrementAndGet()
            threadToJoin = worker
            worker = null
            connectionToClose = currentConnection
            currentConnection = null
        }
        publishConnectionSnapshot(EmbeddedMirrorSnapshot())
        runCatching { connectionToClose?.close() }
        if (threadToJoin !== Thread.currentThread()) threadToJoin?.join(STOP_JOIN_MS)
        detachDecoder()
    }

    /**
     * True once at least one video packet has actually reached the muxer — the point at which
     * [onVideoStartElapsedMs] has already fired with the capture-clock anchor.
     *
     * Deliberately reads the `@Volatile` field directly rather than `synchronized(lock) { ... }`:
     * `CaptureRecorder`'s watchdog thread calls this from inside its OWN lock
     * (`publishLocked`/`runWatchdog`), and this session's worker thread separately calls back into
     * `CaptureRecorder` (via `elapsedMillis`) from inside *this* lock — two objects each acquiring
     * the other's lock while holding their own is a deadlock, not just contention. A plain volatile
     * read needs no lock at all and closes that cycle from this side.
     */
    fun hasStartedVideo(): Boolean = muxerStarted

    /** This session's own device-connection state — see [onConnectionSnapshot]. Never carries a
     * [MirrorFrame]; a shared mirror source overlays its own decoded frames on top of this. */
    fun connectionSnapshot(): EmbeddedMirrorSnapshot = synchronized(lock) { connectionSnapshotValue }

    /**
     * Callable whether or not the caller already holds [lock] itself — no, actually: callers must
     * NOT already hold [lock] when calling this. It takes the lock itself just long enough to
     * update [connectionSnapshotValue], then releases it before notifying [connectionListeners] —
     * an external listener (e.g. a shared mirror source's own state) must never be invoked while
     * this session's lock is held, or a listener that needs its own lock (which some other thread
     * might be holding while trying to call back into *this* session) creates the same kind of
     * lock-order deadlock this function's callers were once written to avoid only by accident.
     */
    private fun publishConnectionSnapshot(snapshot: EmbeddedMirrorSnapshot) {
        synchronized(lock) { connectionSnapshotValue = snapshot }
        connectionListeners.forEach { listener -> runCatching { listener(snapshot) } }
    }

    fun sendControl(bytes: ByteArray): Boolean {
        val connection = synchronized(lock) { currentConnection } ?: return false
        return runCatching { connection.sendControl(bytes); true }.getOrDefault(false)
    }

    /**
     * Attaches a live mirror decoder to this same device stream. Safe to call whether or not the
     * session is currently connected — frames simply resume once a connection exists. Detaches any
     * previously attached decoder first.
     *
     * A decoder normally attaches *after* recording (and therefore the one-time SPS/PPS config
     * packet) has already started — the whole point of sharing one session — so it must be handed
     * the most recently seen config explicitly here; [BoundedAnnexBFeed] otherwise only forwards
     * packets that arrive *after* it was created, and an H.264 decoder that never saw SPS/PPS can
     * never produce a frame ("non-existing PPS referenced"). [BoundedAnnexBFeed] itself starts in
     * its "drop until next key frame" state, so any delta frame already in flight when this feed is
     * created is discarded rather than fed to a decoder with no reference picture yet.
     */
    fun attachDecoder(decoder: H264Decoder, onFrame: (MirrorFrame) -> Unit) {
        detachDecoder()
        val feed = BoundedAnnexBFeed()
        val replayConfig = synchronized(lock) {
            decoderFeed = feed
            attachedDecoder = decoder
            lastConfigBytes
        }
        replayConfig?.let { feed.offer(it, config = true, keyFrame = false) }
        decoderThread = thread(name = "embedded-recording-mirror-decode", isDaemon = true) {
            try {
                decoder.decode(feed.input) { frame -> onFrame(frame) }
            } catch (_: IOException) {
                // Expected when detachDecoder()/stop() closes the feed.
            } catch (failure: Throwable) {
                onDiagnostic("Attached mirror decoder failed: ${failure.message ?: failure::class.simpleName}")
            }
        }
    }

    /**
     * Attaches a direct native decoder to this recorder's existing packet pump. This keeps the
     * recorder as the sole owner of the adb/scrcpy connection while avoiding JavaCV's pixel copy
     * and Compose image conversion for the live mirror. The feed is bounded and drops stale
     * packets until a key frame, so native presentation can never delay MKV writes.
     */
    fun attachDirectDecoder(
        decoder: DirectH264Decoder,
        onFrame: (MirrorFrameInfo) -> Unit,
        onFailure: (Throwable) -> Unit = {},
    ) {
        detachDecoder()
        val feed = BoundedScrcpyPacketFeed(
            rawInput = InputStream.nullInputStream(),
            startPump = false,
            closeInputOnClose = false,
        )
        val replayConfig = synchronized(lock) {
            directDecoderFeed = feed
            attachedDecoder = decoder
            lastConfigBytes
        }
        replayConfig?.let { bytes ->
            feed.offerPacket(BoundedScrcpyPacketFeed.Packet(ptsUs = 0L, config = true, keyFrame = false, data = bytes))
        }
        decoderThread = thread(name = "embedded-recording-mirror-direct-decode", isDaemon = true) {
            try {
                decoder.decode(feed, onFrame)
            } catch (failure: Throwable) {
                // Closing the feed is the normal detach signal. Do not turn it into a fallback
                // request after a user disconnects or capture shutdown has already removed it.
                val stillAttached = synchronized(lock) { directDecoderFeed === feed }
                if (stillAttached) {
                    onDiagnostic("Attached native mirror decoder failed: ${failure.message ?: failure::class.simpleName}")
                    onFailure(failure)
                }
            }
        }
    }

    fun detachDecoder() {
        val feed: BoundedAnnexBFeed?
        val directFeed: BoundedScrcpyPacketFeed?
        val decodeThread: Thread?
        val decoder: Closeable?
        synchronized(lock) {
            feed = decoderFeed
            decoderFeed = null
            directFeed = directDecoderFeed
            directDecoderFeed = null
            decodeThread = decoderThread
            decoderThread = null
            decoder = attachedDecoder
            attachedDecoder = null
        }
        runCatching { feed?.close() }
        runCatching { directFeed?.close() }
        if (decodeThread !== Thread.currentThread()) runCatching { decodeThread?.join(STOP_JOIN_MS) }
        runCatching { decoder?.close() }
    }

    override fun close() {
        stop()
        runCatching { muxer.finish() }
    }

    private fun runSession(runId: Long, serial: String, options: MirrorStreamOptions) {
        var attempt = 0
        while (isCurrent(runId)) {
            if (attempt > 0) {
                onDiagnostic("Embedded recording: reconnecting (attempt $attempt of $maxReconnectAttempts)…")
                if (!sleepBeforeReconnect(runId)) return
            }
            val gapStartElapsedMs = elapsedMillis()
            try {
                val connection = transport.open(serial, options)
                if (!isCurrent(runId)) {
                    runCatching { connection.close() }
                    return
                }
                synchronized(lock) { currentConnection = connection }
                publishConnectionSnapshot(EmbeddedMirrorSnapshot(EmbeddedMirrorState.LIVE, deviceSerial = serial, reconnectAttempt = attempt))
                // Only a reconnect *after* the muxer already has real video is a genuine
                // interruption worth offsetting/reporting: if the very first connection attempt(s)
                // failed before ever reaching a keyframe, nothing was recorded yet, so there is no
                // "gap" to bridge — applying one anyway would stamp the eventually-successful
                // connection's very first packet with a spurious non-zero PTS (start_time != 0 on
                // the finalized file, dropping what looks like leading video that was never
                // actually captured). See EmbeddedDeviceSessionTest's reconnect-before-first-video
                // regression test.
                if (attempt > 0 && muxerStarted) {
                    val gapMs = elapsedMillis() - gapStartElapsedMs
                    synchronized(lock) {
                        // Continue the output timeline from where the previous connection's video
                        // left off, offset by the real wall-clock gap the reconnect took — not by
                        // the new connection's own device-clock pts, which restarts from ~0.
                        ptsOffsetUs = maxOf(lastOutputPtsUs, 0L) + gapMs * MICROS_PER_MILLI
                        firstAnchorPtsUs = null
                    }
                    onDiagnostic("Embedded recording: video gap ${gapMs}ms (reconnect attempt $attempt)")
                }
                // Audio (when requested) streams concurrently with video on its own socket and
                // must be pumped on its own thread — video and audio packets arrive from the
                // device at the same time, and pumpVideo() below blocks on the video socket for
                // as long as the connection lives. Pumping them sequentially (video-to-EOF, then
                // audio) would leave the audio socket's receive buffer never drained while
                // recording is actually live, eventually stalling the device's own audio encoder.
                // Not joined: it's a daemon thread blocked on a read of connection.audioInput, and
                // that read only unblocks once this connection is closed below (or by stop()) —
                // joining it here would just wait out that same close on every reconnect.
                if (options.audio) {
                    thread(name = "embedded-recording-audio-$serial", isDaemon = true) {
                        runCatching { pumpAudioIfRequested(runId, connection, options) }
                            .onFailure { failure ->
                                if (isCurrent(runId)) {
                                    onDiagnostic("Embedded recording: audio pump failed (${failure.message}); continuing video-only.")
                                }
                            }
                    }
                }
                pumpVideo(runId, connection, options.audio)
                if (!isCurrent(runId)) return
                // A clean EOF without stop() is a real drop — fall through and reconnect.
                attempt++
            } catch (failure: Throwable) {
                if (!isCurrent(runId)) return
                attempt++
                val diagnostic = "Embedded recording transport failed: ${failure.message ?: failure::class.simpleName}"
                onDiagnostic(diagnostic)
                publishConnectionSnapshot(
                    EmbeddedMirrorSnapshot(EmbeddedMirrorState.RECONNECTING, deviceSerial = serial, reconnectAttempt = attempt, error = diagnostic),
                )
            } finally {
                synchronized(lock) { currentConnection?.let { runCatching { it.close() } }; currentConnection = null }
            }
            if (attempt > maxReconnectAttempts) {
                val diagnostic = "Embedded recording: giving up after $maxReconnectAttempts reconnect attempt(s); log capture continues."
                onDiagnostic(diagnostic)
                publishConnectionSnapshot(
                    EmbeddedMirrorSnapshot(EmbeddedMirrorState.FAILED, deviceSerial = serial, reconnectAttempt = attempt, error = diagnostic),
                )
                return
            }
        }
    }

    private fun pumpVideo(runId: Long, connection: EmbeddedMirrorConnection, audioRequested: Boolean) {
        val reader = ScrcpyPacketReader(connection.videoInput)
        when (val header = reader.readHeader()) {
            is ScrcpyStreamHeader.Codec -> Unit
            ScrcpyStreamHeader.Disabled -> throw IOException("embedded video stream unexpectedly disabled")
            ScrcpyStreamHeader.Error -> throw ScrcpyStreamErrorException("embedded scrcpy server reported a configuration error")
        }
        var pendingConfig: ByteArray? = null
        while (isCurrent(runId)) {
            val event = reader.readNext() ?: return
            when (event) {
                is ScrcpyStreamEvent.SessionMeta -> {
                    pendingWidth = event.width
                    pendingHeight = event.height
                }
                is ScrcpyStreamEvent.Packet -> {
                    feedDecoder(event)
                    if (event.config) {
                        pendingConfig = handleConfigPacket(event.data, pendingConfig, audioRequested)
                    } else {
                        pendingConfig = writeVideoFrame(event, pendingConfig)
                    }
                }
            }
        }
    }

    /** Returns the still-pending config to carry forward (null once consumed by [handleConfigPacket]
     * or merged into a key frame). */
    private fun handleConfigPacket(data: ByteArray, currentPending: ByteArray?, audioRequested: Boolean): ByteArray? {
        synchronized(lock) { lastConfigBytes = data }
        if (!muxerStarted) {
            startMuxerCoordinatingWithAudio(data, audioRequested)
            return null
        }
        // A later config packet (mid-stream resize, or a fresh reconnect's own SPS/PPS) cannot be
        // installed as new extradata once the muxer header is written — matroska streams are
        // configured once. Merge it in-band ahead of the next key frame instead (both are Annex-B
        // NAL data with start codes, so concatenation is itself valid Annex-B); most H.264 decoders
        // — including FFmpeg's, which is what re-reads this file — reparse SPS from the bitstream
        // and adopt the new resolution even though the container's declared codecpar size still
        // reflects the very first configuration.
        return currentPending?.plus(data) ?: data
    }

    /**
     * The very first time the muxer is ever started (never again — reconnects always find
     * `muxerStarted` already true, taking [handleConfigPacket]'s in-band-merge branch instead), and
     * only when audio was requested: video's own encoder config almost always lands before audio's
     * (audio setup goes through an extra async MediaCodec callback registration on the device — see
     * `AudioEncoder.encode()`), so starting the muxer as soon as video's config arrives would call
     * `StreamingMkvWriter.start()` before `addAudio()` ever had a chance to run, and
     * `addAudio()` refuses a stream once the header is written — silently producing a video-only
     * file with no diagnostic. Wait a bounded grace period for audio to resolve (either a real
     * config, or a Disabled/Error outcome) before deciding.
     */
    private fun startMuxerCoordinatingWithAudio(videoExtradata: ByteArray, audioRequested: Boolean) {
        if (audioRequested) {
            val deadlineNanos = System.nanoTime() + AUDIO_CONFIG_GRACE_MS * NANOS_PER_MILLI
            while (System.nanoTime() < deadlineNanos && synchronized(lock) { !audioResolved && !muxerStarted }) {
                Thread.sleep(AUDIO_CONFIG_POLL_MS)
            }
        }
        // onDiagnostic must never be called while holding [lock] (see hasStartedVideo()'s doc for
        // the deadlock this class was actually caught in) — compute what to report under the lock,
        // then fire it after releasing.
        var diagnosticToReport: String? = null
        synchronized(lock) {
            if (muxerStarted) return
            muxer.start(pendingWidth.coerceAtLeast(1), pendingHeight.coerceAtLeast(1), videoExtradata)
            muxerStarted = true
            val audioExtradata = pendingAudioExtradata
            when {
                !audioRequested -> Unit
                audioExtradata != null -> {
                    val result = runCatching { muxer.addAudio(OPUS_SAMPLE_RATE_HZ, OPUS_CHANNELS, audioExtradata) }
                    audioStarted = true
                    result.onFailure { failure ->
                        diagnosticToReport = "Embedded recording: could not add audio track (${failure.message}); recording video only."
                    }
                }
                else -> diagnosticToReport =
                    "Embedded recording: audio config had not arrived when recording started; recording video only."
            }
        }
        diagnosticToReport?.let(onDiagnostic)
    }

    private fun writeVideoFrame(event: ScrcpyStreamEvent.Packet, pendingConfig: ByteArray?): ByteArray? {
        // onVideoStartElapsedMs/elapsedMillis must never be called while holding [lock]: elapsedMillis
        // is CaptureRecorder's own elapsedNow(), and CaptureRecorder's watchdog separately calls
        // into this session (hasStartedVideo()) while holding *its* lock — two objects each calling
        // into the other from inside their own lock deadlocks instead of just contending. Only set
        // the flag under the lock; fire the callback after releasing it.
        var shouldReportVideoStart = false
        val outputPts = synchronized(lock) {
            val anchor = firstAnchorPtsUs ?: event.ptsUs.also {
                firstAnchorPtsUs = it
                shouldReportVideoStart = true
            }
            (event.ptsUs - anchor + ptsOffsetUs).coerceAtLeast(0L)
        }
        if (shouldReportVideoStart) onVideoStartElapsedMs(elapsedMillis())
        val payload = if (event.keyFrame && pendingConfig != null) pendingConfig + event.data else event.data
        if (!muxerStarted) {
            // A device that never emits a leading config packet (shouldn't happen for H.264, but
            // don't silently drop video if it does) starts the muxer with empty extradata.
            synchronized(lock) {
                if (!muxerStarted) {
                    muxer.start(pendingWidth.coerceAtLeast(1), pendingHeight.coerceAtLeast(1), ByteArray(0))
                    muxerStarted = true
                }
            }
        }
        muxer.writeVideoPacket(outputPts, event.keyFrame, payload)
        synchronized(lock) { lastOutputPtsUs = maxOf(lastOutputPtsUs, outputPts) }
        onVideoPacketWrittenHook?.invoke(outputPts, event.keyFrame)
        return if (event.keyFrame) null else pendingConfig
    }

    private fun pumpAudioIfRequested(runId: Long, connection: EmbeddedMirrorConnection, options: MirrorStreamOptions) {
        if (!options.audio) return
        val audioInput = connection.audioInput
        if (audioInput == null) {
            resolveAudio(null) // no audio socket at all — never keep video waiting for it
            return
        }
        val reader = ScrcpyPacketReader(audioInput)
        if (!readAudioHeader(reader)) {
            resolveAudio(null)
            return
        }
        pumpAudioPackets(runId, reader)
    }

    /** Records audio's outcome for [startMuxerCoordinatingWithAudio]'s wait — `extradata == null`
     * means "no audio is coming" (disabled, error, unreadable, wrong codec, or no socket at all).
     * A no-op once the muxer has already decided (a reconnect's own late audio config, arriving
     * after the very first, one-time muxer start already happened). */
    private fun resolveAudio(extradata: ByteArray?) {
        synchronized(lock) {
            if (!audioResolved) {
                pendingAudioExtradata = extradata
                audioResolved = true
            }
        }
    }

    /** Reads and validates the one-time audio stream header. False for every "stop pumping audio,
     * keep recording video" outcome — an unreadable header, a device-reported disable, a server
     * error, or an unexpected codec — never a Throwable; the caller treats them all identically. */
    private fun readAudioHeader(reader: ScrcpyPacketReader): Boolean {
        val header = try {
            reader.readHeader()
        } catch (failure: IOException) {
            onDiagnostic("Embedded recording: audio header unreadable (${failure.message}); continuing video-only.")
            return false
        }
        return when (header) {
            ScrcpyStreamHeader.Disabled -> {
                onDiagnostic("Embedded recording: device audio capture is unavailable; recording video only.")
                false
            }
            ScrcpyStreamHeader.Error -> false
            is ScrcpyStreamHeader.Codec -> {
                val isOpus = header.id == ScrcpyCodecIds.OPUS
                if (!isOpus) {
                    onDiagnostic("Embedded recording: unexpected audio codec id 0x${header.id.toString(16)}; recording video only.")
                }
                isOpus
            }
        }
    }

    private fun pumpAudioPackets(runId: Long, reader: ScrcpyPacketReader) {
        var audioStreamAdded = false
        var configPending: ByteArray? = null
        while (isCurrent(runId)) {
            val event = reader.readNext() ?: return
            when {
                event !is ScrcpyStreamEvent.Packet -> Unit
                event.config -> {
                    configPending = event.data
                    resolveAudio(event.data) // unblocks startMuxerCoordinatingWithAudio's wait, if still waiting
                }
                else -> audioStreamAdded = writeAudioFrame(event, configPending, audioStreamAdded)
            }
        }
    }

    /** Writes one non-config audio packet if the timeline is already anchored (by video) and the
     * stream has a config/extradata to start with; returns whether the audio stream is now started. */
    private fun writeAudioFrame(event: ScrcpyStreamEvent.Packet, configPending: ByteArray?, alreadyAdded: Boolean): Boolean {
        val anchor = synchronized(lock) { firstAnchorPtsUs } ?: return alreadyAdded // drop until video anchors the timeline
        val started = alreadyAdded || (configPending?.let { startAudioStream(it); true } ?: false)
        if (!started) return false
        val outputPts = (event.ptsUs - anchor + ptsOffsetUs).coerceAtLeast(0L)
        runCatching { muxer.writeAudioPacket(outputPts, event.data) }
        onAudioPacketWrittenHook?.invoke(outputPts)
        return true
    }

    private fun startAudioStream(extradata: ByteArray) {
        var diagnosticToReport: String? = null
        synchronized(lock) {
            if (!audioStarted) {
                val result = runCatching { muxer.addAudio(OPUS_SAMPLE_RATE_HZ, OPUS_CHANNELS, extradata) }
                audioStarted = true
                result.onFailure { failure ->
                    // Expected once in the "audio arrived too late" case:
                    // startMuxerCoordinatingWithAudio already started the muxer without audio
                    // before this config packet showed up, and StreamingMkvWriter refuses to
                    // add a track after that. Diagnosed (not silently dropped) so a slow device
                    // audio pipeline is visible instead of just "no audio, no explanation".
                    diagnosticToReport = "Embedded recording: could not add audio track (${failure.message}); recording video only."
                }
            }
        }
        diagnosticToReport?.let(onDiagnostic)
    }

    private fun feedDecoder(event: ScrcpyStreamEvent.Packet) {
        val (feed, directFeed) = synchronized(lock) { decoderFeed to directDecoderFeed }
        feed?.offer(event.data, config = event.config, keyFrame = event.keyFrame)
        directFeed?.offerPacket(
            BoundedScrcpyPacketFeed.Packet(
                ptsUs = event.ptsUs,
                config = event.config,
                keyFrame = event.keyFrame,
                data = event.data,
            ),
        )
    }

    private fun sleepBeforeReconnect(runId: Long): Boolean = try {
        Thread.sleep(reconnectDelay.toMillis().coerceAtLeast(0))
        isCurrent(runId)
    } catch (_: InterruptedException) {
        false
    }

    private fun isCurrent(runId: Long): Boolean = synchronized(lock) { !stopping && generation.get() == runId }

    private companion object {
        const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 5
        const val DEFAULT_RECONNECT_DELAY_MS = 500L
        const val STOP_JOIN_MS = 2_000L
        const val MICROS_PER_MILLI = 1_000L
        const val OPUS_SAMPLE_RATE_HZ = 48_000
        const val OPUS_CHANNELS = 2

        // Android's audio MediaCodec setup (AudioEncoder.encode()) goes through an extra async
        // callback-registration round trip that video's SurfaceEncoder doesn't, so audio's config
        // routinely lands a bit after video's — bounded here rather than blocking recording start
        // indefinitely if audio genuinely never arrives.
        const val AUDIO_CONFIG_GRACE_MS = 1_500L
        const val AUDIO_CONFIG_POLL_MS = 20L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
