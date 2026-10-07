package com.indagium.capture

import com.indagium.capture.mirror.AdbScrcpyTransport
import com.indagium.capture.mirror.EmbeddedDeviceSession
import com.indagium.capture.mirror.EmbeddedMirrorTransport
import com.indagium.capture.mirror.MirrorStreamOptions
import com.indagium.debug.AppLogger
import com.indagium.utils.writeFileAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

enum class RecorderState { IDLE, RECORDING, STOPPING, STOPPED, INTERRUPTED }

data class RecorderSnapshot(
    val state: RecorderState = RecorderState.IDLE,
    val session: CaptureSession? = null,
    val diagnostics: List<String> = emptyList(),
    val logBytes: Long = 0,
    val indexedRows: Int = 0,
    val videoRecording: Boolean = false,
)

/** Stable, flushed bounds an exporter can consume while capture continues. */
data class CaptureSessionBoundary(
    val session: CaptureSession,
    val logLength: Long,
    val indexLength: Long,
    val elapsedMs: Long,
)

/**
 * A successful device screenshot plus the capture-clock metadata needed to make it a durable
 * annotation frame. The existing [CaptureRecorder.screenshot] API still returns just [file] for
 * callers that only need the persisted PNG; the richer seam is used by the live-tab UI.
 */
data class CaptureScreenshot(
    val file: File,
    val bytes: ByteArray,
    val elapsedMs: Long,
    val videoStartElapsedMs: Long?,
    val videoFile: File,
)

fun interface CaptureClock {
    fun monotonicMillis(): Long

    companion object {
        val SYSTEM = CaptureClock { System.nanoTime() / 1_000_000L }
    }
}

fun interface CaptureDiskSpace {
    fun usableBytes(directory: File): Long

    companion object {
        val SYSTEM = CaptureDiskSpace { it.usableSpace }
    }
}

class CaptureRecorder internal constructor(
    private val sessionsRoot: File,
    private val runner: CaptureProcessRunner = ProcessBuilderCaptureRunner(),
    private val clock: CaptureClock = CaptureClock.SYSTEM,
    private val epochMillis: () -> Long = System::currentTimeMillis,
    private val diskSpace: CaptureDiskSpace = CaptureDiskSpace.SYSTEM,
    private val watchdogIntervalMs: Long = PREVIEW_PUBLISH_INTERVAL_MS,
    private val spaceCheckIntervalMs: Long = SPACE_CHECK_INTERVAL_MS,
    private val metadataPersistIntervalMs: Long = SESSION_METADATA_PERSIST_INTERVAL_MS,
    // Test seam: production builds a real AdbScrcpyTransport (embedded scrcpy server over adb);
    // tests substitute a fake transport so the embedded recording path — including reconnects and
    // PTS continuity — can be driven end to end without a device. See EmbeddedDeviceSession and its
    // own unit tests for the packet-level behaviour this wires up; CaptureRecorderTest exercises the
    // recorder-level lifecycle (start/stop, diagnostics, videoStartElapsedMs persistence) against a
    // fake transport supplied here.
    private val embeddedTransportFactory: (CaptureTools) -> EmbeddedMirrorTransport = { tools ->
        AdbScrcpyTransport(tools = tools, runner = runner, localRoot = sessionsRoot)
    },
    // Test seam: lets a test hand the recorder a storage stream that fails on demand (disk full).
    private val openStorage: (File) -> FileOutputStream = { FileOutputStream(it, true) },
) : Closeable {
    private val lock = Any()
    private val active = AtomicBoolean(false)
    private val diagnostics = ArrayDeque<String>(MAX_DIAGNOSTICS)
    private val mutableSnapshot = MutableStateFlow(RecorderSnapshot())
    private val mutableSelectedSession = MutableStateFlow<CaptureSession?>(null)
    private var currentSession: CaptureSession? = null
    private var currentTools: CaptureTools? = null

    /** Serializes the window between start() being requested and its log process becoming active. */
    private var starting = false

    /** Set by stop/close while start is still preparing files or launching adb. */
    private var startCancellationRequested = false
    private var starterThread: Thread? = null
    private var startLatch = CountDownLatch(0)

    // Deliberately lock-free (not guarded by [lock]) so elapsedNow() never needs to acquire it —
    // see elapsedNow()'s own doc for why that matters: EmbeddedDeviceSession calls back into it
    // (as its injected `elapsedMillis`) from inside ITS OWN lock, and CaptureRecorder's watchdog
    // separately calls into EmbeddedDeviceSession while holding [lock] — two objects each calling
    // into the other while holding their own lock is a textbook lock-order deadlock (caught by a
    // hung EmbeddedMirrorAutostartTest run; see git history). Neither field needs [lock] for
    // correctness: startedMonotonicMs is written once per capture start, and lastElapsedMs only
    // ever needs to be monotonically non-decreasing, which AtomicLong.updateAndGet guarantees
    // without any lock at all.
    @Volatile private var startedMonotonicMs: Long = 0
    private val lastElapsedMsAtomic = AtomicLong(0)
    private var logProcess: RunningCaptureProcess? = null
    private var mirrorProcess: RunningCaptureProcess? = null

    /** Set while [openMirror] is launching scrcpy, so two overlapping calls cannot start two processes. */
    private var mirrorOpening = false
    private var embeddedSession: EmbeddedDeviceSession? = null
    private val mutableEmbeddedSession = MutableStateFlow<EmbeddedDeviceSession?>(null)
    private var logThread: Thread? = null
    private var watchdogThread: Thread? = null
    private var logFileOutput: FileOutputStream? = null
    private var indexFileOutput: FileOutputStream? = null
    private var logOutput: BufferedOutputStream? = null
    private var indexOutput: BufferedOutputStream? = null
    private var logBytes: Long = 0
    private var rowOrdinal: Int = 0
    private var lastPublishMs: Long = Long.MIN_VALUE
    private var lastSpaceCheckMs: Long = Long.MIN_VALUE
    private var lastMetadataPersistMs: Long = Long.MIN_VALUE
    private var stopLatch = CountDownLatch(0)

    /** First storage failure seen while stopping (guarded by [lock]); turns the final status INTERRUPTED. */
    private var stopStorageFailure: String? = null

    val snapshot: StateFlow<RecorderSnapshot> = mutableSnapshot.asStateFlow()
    val selectedSession: StateFlow<CaptureSession?> = mutableSelectedSession.asStateFlow()

    /** Mirrors [activeEmbeddedSession] as a flow so a waiter (e.g. `AppState.ensureEmbeddedMirror`)
     * can suspend until the embedded session appears instead of racing a single synchronous read —
     * see that function's doc for the race this closes. */
    internal val embeddedSessionFlow: StateFlow<EmbeddedDeviceSession?> = mutableEmbeddedSession.asStateFlow()

    /**
     * Starts a recorder. [beforeLogcatLaunch] is invoked after the session directory and both
     * append-only streams have been opened, but before adb is spawned. The streaming-tab owner
     * uses this narrow seam to publish the empty log tab and start its tailer at byte offset 0;
     * without it, the first adb lines could land between tab-open and tailer-start and be lost.
     */
    fun start(
        device: CaptureDevice,
        settings: CaptureSettings,
        tools: CaptureTools,
        // Old-glibc Linux (see NativeMediaSupport.kt): surfaced as one diagnostic line right after
        // the session activates below, the same live-session channel every other capture-start
        // diagnostic (audio plan, video-start failure) already uses. Null on every other system.
        startupNotice: String? = null,
        beforeLogcatLaunch: ((CaptureSession) -> Unit)? = null,
    ): CaptureSession {
        synchronized(lock) {
            require(device.available) { deviceStateGuidance(device.state, device.wireless) ?: "Device is not available" }
            check(!active.get()) { "A capture session is already recording" }
            check(!starting) { "A capture session is already starting" }
            // Only CUSTOM depends on `buffers` being non-empty (DEFAULT/ALL never read it — see
            // logcatBufferArgs()). Guarding on `buffers` alone (the old check) blocked a user who
            // emptied the custom list and then switched back to Default from ever recording again.
            check(settings.bufferMode != CaptureBufferMode.CUSTOM || settings.buffers.isNotEmpty()) {
                "Select at least one logcat buffer"
            }
            starting = true
            startCancellationRequested = false
            starterThread = Thread.currentThread()
            startLatch = CountDownLatch(1)
        }
        return try {
            startCapture(device, settings, tools, startupNotice, beforeLogcatLaunch)
        } finally {
            synchronized(lock) {
                starting = false
                startCancellationRequested = false
                starterThread = null
                startLatch.countDown()
            }
        }
    }

    // Keep startup phases together so cancellation can be checked between file creation,
    // adb launch, and optional video launch without exposing a half-started session.
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ThrowsCount", "TooGenericExceptionCaught")
    private fun startCapture(
        device: CaptureDevice,
        settings: CaptureSettings,
        tools: CaptureTools,
        startupNotice: String?,
        beforeLogcatLaunch: ((CaptureSession) -> Unit)?,
    ): CaptureSession {
        recoverSessions()
        val startedEpochMs = epochMillis()
        val sessionId = sessionId(startedEpochMs)
        val directory = File(sessionsRoot, sessionId)
        listOf("logs", "mapping", "video", "screenshots").forEach { File(directory, it).mkdirs() }
        check(diskSpace.usableBytes(directory) >= settings.freeSpaceReserveBytes) {
            "Capture cannot start: less than the configured free-space reserve is available"
        }
        var session = CaptureSession(
            id = sessionId,
            directory = directory,
            device = device,
            settings = settings,
            startedEpochMs = startedEpochMs,
        )
        persistSession(session)
        var openedLogFile: FileOutputStream? = null
        var openedIndexFile: FileOutputStream? = null
        try {
            openedLogFile = openStorage(session.logFile)
            openedIndexFile = openStorage(session.indexFile)
        } catch (failure: IOException) {
            runCatching { openedLogFile?.close() }
            runCatching { openedIndexFile?.close() }
            session = session.copy(
                status = CaptureStatus.INTERRUPTED,
                interruptions = listOf("Unable to open capture storage: ${failure.message ?: failure::class.simpleName}"),
            )
            persistSession(session)
            throw failure
        }
        val cancelledBeforeActivation = synchronized(lock) {
            if (startCancellationRequested) {
                true
            } else {
                diagnostics.clear()
                currentSession = session
                currentTools = tools
                startedMonotonicMs = clock.monotonicMillis()
                lastElapsedMsAtomic.set(0)
                logBytes = 0
                rowOrdinal = 0
                lastPublishMs = Long.MIN_VALUE
                lastSpaceCheckMs = Long.MIN_VALUE
                lastMetadataPersistMs = Long.MIN_VALUE
                stopLatch = CountDownLatch(1)
                logFileOutput = requireNotNull(openedLogFile)
                indexFileOutput = requireNotNull(openedIndexFile)
                logOutput = BufferedOutputStream(requireNotNull(logFileOutput), CAPTURE_WRITE_BUFFER_BYTES)
                indexOutput = BufferedOutputStream(requireNotNull(indexFileOutput), CAPTURE_WRITE_BUFFER_BYTES)
                active.set(true)
                LiveCaptureSessions.add(session.directory)
                mutableSelectedSession.value = session
                publishLocked(RecorderState.RECORDING, force = true)
                false
            }
        }
        if (cancelledBeforeActivation) {
            runCatching { openedLogFile.close() }
            runCatching { openedIndexFile.close() }
            session = session.copy(
                status = CaptureStatus.INTERRUPTED,
                interruptions = listOf("Capture was interrupted while starting."),
            )
            persistSession(session)
            synchronized(lock) {
                currentSession = session
                currentTools = null
                mutableSelectedSession.value = session
                publishLocked(RecorderState.INTERRUPTED, force = true)
                stopLatch.countDown()
            }
            return session
        }
        startupNotice?.let(::addDiagnostic)

        try {
            beforeLogcatLaunch?.invoke(session)
            val logcatArguments = buildList {
                add("logcat")
                addAll(listOf("-v", "threadtime"))
                // DEFAULT mode emits no -b at all (see CaptureBufferMode/logcatBufferArgs doc) —
                // previously this always hardcoded -b main -b system -b crash, which silently
                // dropped adb's own real default buffer (`kernel`) and could error out on a
                // device missing one of the three named buffers.
                addAll(settings.logcatBufferArgs())
                if (!settings.includeBufferedLogs) addAll(listOf("-T", "1"))
            }
            val process = runner.start(tools.adbSpec(device.serial, logcatArguments))
            val accepted = synchronized(lock) {
                if (active.get()) {
                    logProcess = process
                    true
                } else {
                    false
                }
            }
            if (!accepted) {
                process.terminate()
                process.close()
                return synchronized(lock) { requireNotNull(currentSession) }
            }
            drainDiagnostics(process.errorStream, "adb")
            logThread = thread(name = "capture-logcat-${session.id}", isDaemon = true) {
                consumeLogcat(process)
            }
            watchdogThread = thread(name = "capture-watchdog-${session.id}", isDaemon = true) {
                runWatchdog()
            }
        } catch (failure: IOException) {
            stopInternal(
                CaptureStatus.INTERRUPTED,
                "Unable to start adb logcat: ${failure.message ?: failure::class.simpleName}",
            )
            throw failure
        } catch (failure: RuntimeException) {
            stopInternal(
                CaptureStatus.INTERRUPTED,
                "Unable to start adb logcat: ${failure.message ?: failure::class.simpleName}",
            )
            throw failure
        }

        if (settings.recordVideo) {
            if (!active.get()) {
                return synchronized(lock) { requireNotNull(currentSession) }
            }
            try {
                // The embedded scrcpy session owns the device stream directly (adb + the bundled
                // server asset) and writes session.videoFile itself via StreamingMkvWriter — no
                // host `scrcpy` binary involved. videoStartElapsedMs is set from
                // onVideoStartElapsedMs, fired when the *first video packet* actually reaches the
                // muxer (not when this method is called), so the log<->video mapping anchors on
                // real captured video rather than on however long adb/the device took to start
                // streaming.
                val newSession = EmbeddedDeviceSession(
                    transport = embeddedTransportFactory(tools),
                    muxer = StreamingMkvWriter(session.videoFile),
                    elapsedMillis = ::elapsedNow,
                    onDiagnostic = ::addDiagnostic,
                    onVideoStartElapsedMs = ::recordVideoStartElapsedMs,
                    microphoneDeviceId = settings.microphoneDeviceId,
                )
                var accepted = false
                synchronized(lock) {
                    if (active.get()) {
                        embeddedSession = newSession
                        mutableEmbeddedSession.value = newSession
                        accepted = true
                        publishLocked(RecorderState.RECORDING, force = true)
                    }
                }
                if (!accepted) {
                    newSession.close()
                    return synchronized(lock) { requireNotNull(currentSession) }
                }
                startNoVideoDiagnosticWatcher(session.id, device.serial, settings.recordVideo, newSession)
                val audioPlan = resolveAudioPlan(tools, device.serial, settings)
                newSession.start(
                    device.serial,
                    MirrorStreamOptions(
                        maxSize = settings.maxSize,
                        maxFps = settings.maxFps,
                        bitrateMbps = settings.bitrateMbps,
                        audio = settings.audio,
                        audioServerArgs = audioPlan.serverArgs,
                    ),
                )
            } catch (failure: IOException) {
                addDiagnostic("Video recording could not start: ${failure.message ?: failure::class.simpleName}")
            } catch (failure: RuntimeException) {
                addDiagnostic("Video recording could not start: ${failure.message ?: failure::class.simpleName}")
            }
        }
        return synchronized(lock) { requireNotNull(currentSession) }
    }

    fun stop(): CaptureSession? = stopInternal(CaptureStatus.STOPPED, null)

    fun screenshot(): File = screenshotCapture().file

    /** Reads a transient screen image for an AI/MCP tool. Unlike [screenshotCapture], this does not
     * persist the image into the capture; only an explicit marker or archive export does that. */
    fun readScreen(): ByteArray {
        val (session, tools) = synchronized(lock) {
            check(active.get()) { "Screen capture requires an active capture session" }
            requireNotNull(currentSession) to requireNotNull(currentTools)
        }
        return readScreenPng(session, tools)
    }

    fun screenshotCapture(): CaptureScreenshot {
        val (session, tools) = synchronized(lock) {
            check(active.get()) { "Screenshot requires an active capture session" }
            requireNotNull(currentSession) to requireNotNull(currentTools)
        }
        val elapsed = elapsedNow()
        val pngBytes = readScreenPng(session, tools)
        val destination = File(session.directory, "screenshots/screenshot-$elapsed.png")
        FileOutputStream(destination).use { output ->
            output.write(pngBytes)
            output.fd.sync()
        }
        return CaptureScreenshot(
            file = destination,
            bytes = pngBytes,
            elapsedMs = elapsed,
            videoStartElapsedMs = session.videoStartElapsedMs,
            videoFile = session.videoFile,
        )
    }

    private fun readScreenPng(session: CaptureSession, tools: CaptureTools): ByteArray {
        val result = runner.run(
            tools.adbSpec(session.device.serial, SCREENCAP_ARGUMENTS),
            timeout = Duration.ofSeconds(SCREENSHOT_TIMEOUT_SECONDS),
            outputLimitBytes = MAX_SCREENSHOT_BYTES,
        )
        return screencapPngFrom(result, ::addDiagnostic)
    }

    /** Performs the per-session functional screenshot capability probe lazily. */
    fun supportsScreenshots(): Boolean {
        val (session, tools) = synchronized(lock) {
            check(active.get()) { "Screenshot support requires an active capture session" }
            requireNotNull(currentSession) to requireNotNull(currentTools)
        }
        return tools.supportsScreenshots(session.device.serial)
    }

    /**
     * The active embedded recording session, when video recording is on and currently running —
     * used by the UI layer (`AppState.ensureEmbeddedMirror`) to attach a shared mirror decoder to
     * the *same* device stream instead of opening a second embedded scrcpy server. Null whenever
     * `recordVideo` is off, or between capture sessions.
     */
    internal fun activeEmbeddedSession(): EmbeddedDeviceSession? = synchronized(lock) { embeddedSession }

    /** Opens at most one auxiliary, visible, non-recording scrcpy process for this session. */
    fun openMirror(): Boolean = openMirror(forceNoAudio = false)

    /**
     * [forceNoAudio] builds the argument list from a copy of the settings with audio off (yielding
     * `--no-audio`) for the one-shot retry after an audio crash; the session's own settings are never
     * touched or persisted.
     */
    private fun openMirror(forceNoAudio: Boolean): Boolean {
        val (session, tools) = synchronized(lock) {
            check(active.get()) { "Mirror requires an active capture session" }
            requireNotNull(currentSession) to requireNotNull(currentTools)
        }
        synchronized(lock) {
            if (mirrorOpening || mirrorProcess?.isAlive == true) return true
            mirrorProcess = null
            mirrorOpening = true
        }
        var stillOpening = true
        try {
            val settings = if (forceNoAudio) session.settings.copy(audio = false) else session.settings
            if (settings.audio) tools.legacyScrcpyAudioWarning()?.let(::addDiagnostic)
            val audioPlan = resolveAudioPlan(tools, session.device.serial, settings)
            val audioRequested = tools.scrcpyMirrorUsesAudio(settings)
            val process = runner.start(tools.scrcpyMirrorSpec(session.device.serial, settings, audioPlan.cliArgs))
            val launchedAtNanos = System.nanoTime()
            val accepted = synchronized(lock) {
                mirrorOpening = false
                stillOpening = false
                if (!active.get()) {
                    false
                } else {
                    mirrorProcess = process
                    true
                }
            }
            if (!accepted) {
                process.terminate()
                process.close()
                return false
            }
            thread(name = "capture-mirror-${session.id}", isDaemon = true) {
                monitorMirror(process, launchedAtNanos, audioRequested, retried = forceNoAudio)
            }
            return true
        } finally {
            // Only the failure path (legacy warning, audio plan or runner.start threw) still owns the flag.
            if (stillOpening) synchronized(lock) { mirrorOpening = false }
        }
    }

    fun listSessions(): List<CaptureSession> {
        sessionsRoot.mkdirs()
        return sessionsRoot.listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .mapNotNull(::readSession)
            .sortedByDescending(CaptureSession::startedEpochMs)
            .toList()
    }

    fun recoverSessions(): List<CaptureSession> = listSessions().map { session ->
        if (session.status != CaptureStatus.RECORDING || currentSession?.id == session.id && active.get()) return@map session
        val recoveredElapsed = maxOf(session.elapsedMs, lastIndexedElapsedMs(session.indexFile))
        val recovered = session.copy(
            elapsedMs = recoveredElapsed,
            status = CaptureStatus.INTERRUPTED,
            interruptions = (session.interruptions + "The app exited before this capture stopped.").takeLast(MAX_DIAGNOSTICS),
        )
        persistSession(recovered)
        recovered
    }

    fun selectSession(sessionId: String?): CaptureSession? {
        val selected = sessionId?.let { id -> listSessions().firstOrNull { it.id == id } }
        mutableSelectedSession.value = selected
        return selected
    }

    fun deleteSession(sessionId: String): Boolean {
        val session = listSessions().firstOrNull { it.id == sessionId } ?: return false
        check(session.status != CaptureStatus.RECORDING) { "A recording capture session cannot be deleted" }
        check(currentSession?.id != sessionId || !active.get()) { "The active capture session cannot be deleted" }
        val deleted = session.directory.deleteRecursively()
        if (deleted && mutableSelectedSession.value?.id == sessionId) mutableSelectedSession.value = null
        return deleted
    }

    fun setManualOffset(sessionId: String, offsetMs: Long): CaptureSession {
        return updateSession(sessionId) { it.copy(manualOffsetMs = offsetMs) }
    }

    /**
     * Advances the Since-last-save cursors after one successful export. The log cursor always
     * advances to the export's log-covered end, whether or not that export included video — a
     * failed/skipped video must never hold the log cursor back. The video cursor only advances when
     * [videoCheckpointMs] is non-null, i.e. the export actually produced video; a video-less export
     * (missing coverage, growing MKV not yet caught up, recordVideo off) leaves it exactly where it
     * was, so the next Since-last-save export's video range still starts from the last point video
     * was truly exported (see [CaptureSession.effectiveVideoCheckpointMs]).
     */
    fun updateSuccessfulExportCheckpoints(
        sessionId: String,
        logCheckpointMs: Long,
        videoCheckpointMs: Long?,
    ): CaptureSession = updateSession(sessionId) { session ->
        val checkpoint = maxOf(session.effectiveSnapshotCheckpointMs, logCheckpointMs)
        session.copy(
            snapshotCheckpointMs = checkpoint,
            // Keep the old constructor field meaningful for source compatibility with callers
            // compiled against the pre-canonical model. It is no longer persisted or used for range
            // selection.
            logCheckpointMs = maxOf(session.logCheckpointMs, logCheckpointMs),
            videoCheckpointMs = videoCheckpointMs?.let { maxOf(session.videoCheckpointMs, it) } ?: session.videoCheckpointMs,
            exportCounter = session.exportCounter + 1,
        )
    }

    fun snapshotForExport(sessionId: String): CaptureSessionBoundary {
        synchronized(lock) {
            val session = if (currentSession?.id == sessionId) currentSession else readSession(File(sessionsRoot, sessionId))
            val resolved = requireNotNull(session) { "Capture session not found: $sessionId" }
            if (currentSession?.id == sessionId && active.get()) {
                logOutput?.flush()
                logFileOutput?.fd?.sync()
                indexOutput?.flush()
                indexFileOutput?.fd?.sync()
            }
            val elapsed = if (currentSession?.id == sessionId && active.get()) elapsedNow() else resolved.elapsedMs
            val durable = resolved.copy(elapsedMs = maxOf(resolved.elapsedMs, elapsed))
            return CaptureSessionBoundary(durable, durable.logFile.length(), durable.indexFile.length(), elapsed)
        }
    }

    override fun close() {
        if (active.get() || synchronized(lock) { starting }) {
            stopInternal(CaptureStatus.INTERRUPTED, "Capture was interrupted while closing.")
        }
    }

    @Suppress("TooGenericExceptionCaught") // A pluggable process stream may fail with unchecked I/O wrappers.
    private fun consumeLogcat(process: RunningCaptureProcess) {
        // Set when this reader itself stopped the capture (size limit, free-space reserve, write
        // failure). interrupt() hands the stop to another thread, so `active` can still read true
        // when the loop below returns; without this flag the "logcat ended" check after it raced
        // that thread and, if it won, replaced the real reason with "the device may have
        // disconnected".
        var stoppedForStorage = false
        try {
            process.inputStream.use { input ->
                forEachRawLine(input) { raw ->
                    val elapsed = elapsedNow()
                    var storageFailure: String? = null
                    synchronized(lock) {
                        val session = currentSession ?: return@synchronized
                        // Once stopInternal has claimed the stop, active is false but the
                        // process may still have bytes buffered in its pipe. Keep consuming and
                        // indexing those bytes until EOF; stopInternal joins this thread before
                        // publishing the final session. This is the recorder-side half of the
                        // mandatory tail drain and prevents a final adb line from disappearing.
                        if (logOutput == null || indexOutput == null) return@synchronized
                        try {
                            enforceStorageLimitsLocked(session, elapsed, raw.size)
                            val offset = logBytes
                            val ordinal = if (isSeparator(raw)) null else ++rowOrdinal
                            val log = requireNotNull(logOutput)
                            log.write(raw)
                            logBytes += raw.size
                            val record = CaptureLogIndexRecord(offset, raw.size, elapsed, ordinal)
                            val indexBytes = (indexRecordJson(record) + "\n").toByteArray(Charsets.UTF_8)
                            val index = requireNotNull(indexOutput)
                            index.write(indexBytes)
                        } catch (failure: IOException) {
                            storageFailure = failure.message ?: "Capture storage failed"
                        } catch (failure: RuntimeException) {
                            storageFailure = failure.message ?: "Capture storage failed"
                        }
                    }
                    if (storageFailure != null) {
                        stoppedForStorage = true
                        interrupt(storageFailure)
                        false
                    } else {
                        true
                    }
                }
            }
            if (!stoppedForStorage && active.get()) interrupt("adb logcat ended; the device may have disconnected.")
        } catch (failure: IOException) {
            if (!stoppedForStorage && active.get()) {
                interrupt("adb logcat failed: ${failure.message ?: failure::class.simpleName}")
            }
        } finally {
            process.close()
        }
    }

    /** [EmbeddedDeviceSession.onVideoStartElapsedMs]: fires once, from the recording pump thread,
     * the first time a video packet actually reaches the muxer. */
    private fun recordVideoStartElapsedMs(startElapsedMs: Long) {
        AppLogger.info("capture", "Video recording received its first packet $startElapsedMs ms after capture start")
        synchronized(lock) {
            val updated = (currentSession ?: return@synchronized).copy(videoStartElapsedMs = startElapsedMs)
            currentSession = updated
            if (mutableSelectedSession.value?.id == updated.id) mutableSelectedSession.value = updated
            persistSession(updated)
        }
    }

    /**
     * One-shot "no video" evidence (see [reportNoVideoDiagnostic]). Runs on its own daemon thread
     * and reads only lock-free state ([elapsedNow], the session's atomics) — deliberately NOT on the
     * watchdog thread, which takes [lock] and could be the very thread that is stuck. Fires at most
     * once per capture, when the capture is [CAPTURE_NO_VIDEO_AFTER_MS] old and no first video packet
     * has arrived; ends as soon as video arrives, the capture stops, or the session is stopped.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun startNoVideoDiagnosticWatcher(
        sessionId: String,
        serial: String,
        recordVideo: Boolean,
        embedded: EmbeddedDeviceSession,
    ) {
        val trigger = NoVideoDiagnosticTrigger(
            elapsedMs = ::elapsedNow,
            recordVideo = recordVideo,
            firstVideoPacketArrived = embedded::hasReceivedFirstVideoPacket,
        )
        if (trigger.isFinished) return
        thread(name = "capture-no-video-diagnostic-$sessionId", isDaemon = true) {
            try {
                while (active.get() && !embedded.isStopped && !trigger.isFinished) {
                    Thread.sleep(watchdogIntervalMs.coerceAtLeast(1))
                    if (trigger.poll()) {
                        reportNoVideoDiagnostic(serial, sessionId, elapsedNow(), embedded.pathStats)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (failure: Throwable) {
                runCatching { AppLogger.warn("capture", "No-video diagnostic watcher failed: ${failure.message}") }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun monitorMirror(process: RunningCaptureProcess, launchedAtNanos: Long, audioRequested: Boolean, retried: Boolean) {
        val recentStderr = ArrayDeque<String>()
        val stderrReader = drainDiagnostics(process.errorStream, "scrcpy mirror") { line ->
            synchronized(recentStderr) {
                if (recentStderr.size == MIRROR_STDERR_TAIL_LINES) recentStderr.removeFirst()
                recentStderr.addLast(line)
            }
        }
        discardOutput(process.inputStream, "scrcpy mirror")
        process.waitFor(Duration.ofDays(VIDEO_MONITOR_MAX_WAIT_DAYS))
        // The reader runs on its own thread and may still hold the last lines (the audio error is
        // typically the very last thing scrcpy prints); collect them before deciding anything. Bounded,
        // because a grandchild that inherited the pipe could keep it open after scrcpy itself is gone.
        stderrReader.join(MIRROR_STDERR_JOIN_MS)
        val exitCode = process.exitCode()
        exitCode?.takeIf { it != 0 }?.let {
            addDiagnostic("scrcpy mirror exited with status $it; see the scrcpy mirror output above.")
        }
        synchronized(lock) {
            if (mirrorProcess === process) mirrorProcess = null
            if (active.get()) publishLocked(RecorderState.RECORDING, force = true)
        }
        process.close()
        val uptimeMs = (System.nanoTime() - launchedAtNanos) / NANOS_PER_MILLI
        val stderrLines = synchronized(recentStderr) { recentStderr.toList() }
        if (!shouldRetryMirrorWithoutAudio(exitCode, stderrLines, audioRequested, retried, uptimeMs) || !active.get()) return
        addDiagnostic(MIRROR_AUDIO_FALLBACK_DIAGNOSTIC)
        // mirrorProcess is already cleared above and the lock is released, so this goes through the
        // same openMirror guard as a user click: whichever of the two takes the lock first starts the
        // one process and the other sees mirrorOpening / a live process and no-ops.
        try {
            openMirror(forceNoAudio = true)
        } catch (failure: Throwable) {
            addDiagnostic("scrcpy mirror could not reopen without audio: ${failure.message ?: failure::class.simpleName}")
        }
    }

    // Storage failures while stopping (disk full) must never stop the teardown: processes are
    // always terminated and streams always closed, and the failure is what decides the final status.
    @Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")
    private fun stopInternal(status: CaptureStatus, reason: String?): CaptureSession? {
        var ownsStop = false
        var processes = emptyList<RunningCaptureProcess>()
        var videoSession: EmbeddedDeviceSession? = null
        var startup: CountDownLatch? = null
        val calledFromStarter = synchronized(lock) { Thread.currentThread() === starterThread }
        var liveDirectory: File? = null
        val completion = synchronized(lock) {
            if (starting) startCancellationRequested = true
            if (active.getAndSet(false)) {
                ownsStop = true
                liveDirectory = currentSession?.directory
                reason?.let(::addDiagnosticLocked)
                publishLocked(RecorderState.STOPPING, force = true)
                processes = listOfNotNull(logProcess, mirrorProcess)
                videoSession = embeddedSession
            } else if (starting) {
                startup = startLatch
            }
            stopLatch
        }
        if (!ownsStop) {
            if (startup != null) {
                if (!calledFromStarter) startup.await(STOP_WAIT_SECONDS, TimeUnit.SECONDS)
                if (active.get()) return stopInternal(status, reason)
            } else {
                completion.await(STOP_WAIT_SECONDS, TimeUnit.SECONDS)
            }
            return synchronized(lock) { currentSession }
        }
        processes.forEach { runCatching { it.terminate() } }
        val current = Thread.currentThread()
        if (logThread !== current) logThread?.join(PROCESS_JOIN_TIMEOUT_MS)
        if (watchdogThread !== current) watchdogThread?.join(WATCHDOG_JOIN_TIMEOUT_MS)
        // Finalize the MKV (stop the reconnect loop, write the trailer) before the session below is
        // marked stopped/interrupted and handed to finalizeSessionInPlace — see EmbeddedDeviceSession.
        runCatching { videoSession?.close() }
        try {
            synchronized(lock) {
                var storageFailure = stopStorageFailure

                fun guarded(step: () -> Unit) {
                    try {
                        step()
                    } catch (failure: IOException) {
                        if (storageFailure == null) storageFailure = failure.message ?: failure::class.simpleName
                    } catch (failure: RuntimeException) {
                        if (storageFailure == null) storageFailure = failure.message ?: failure::class.simpleName
                    }
                }
                guarded { logOutput?.flush(); logFileOutput?.fd?.sync() }
                guarded { indexOutput?.flush(); indexFileOutput?.fd?.sync() }
                guarded { logOutput?.close() }
                guarded { indexOutput?.close() }
                stopStorageFailure = null
                logOutput = null
                indexOutput = null
                logFileOutput = null
                indexFileOutput = null
                logProcess = null
                mirrorProcess = null
                embeddedSession = null
                mutableEmbeddedSession.value = null
                val old = currentSession ?: return null
                val storageReason = storageFailure?.let { "Capture storage failed while stopping: $it" }
                val interruptionList = (old.interruptions + listOfNotNull(reason, storageReason)).takeLast(MAX_DIAGNOSTICS)
                // A failed final flush/sync/close means the tail of the log or index may be missing,
                // so the session must not claim a clean STOPPED.
                val finalStatus = if (storageReason != null) CaptureStatus.INTERRUPTED else status
                val finished = old.copy(
                    elapsedMs = maxOf(old.elapsedMs, elapsedNow()),
                    status = finalStatus,
                    interruptions = interruptionList,
                )
                currentSession = finished
                mutableSelectedSession.value = finished
                persistSession(finished)
                publishLocked(
                    if (finalStatus == CaptureStatus.STOPPED) RecorderState.STOPPED else RecorderState.INTERRUPTED,
                    force = true,
                )
                return finished
            }
        } finally {
            // After the MKV is closed and the final status persisted: until now the file may still grow.
            liveDirectory?.let(LiveCaptureSessions::remove)
            completion.countDown()
        }
    }

    private fun interrupt(reason: String) {
        if (!active.get()) return
        thread(name = "capture-interrupt", isDaemon = true) {
            stopInternal(CaptureStatus.INTERRUPTED, reason)
        }
    }

    private fun updateSession(sessionId: String, transform: (CaptureSession) -> CaptureSession): CaptureSession {
        synchronized(lock) {
            val existing = if (currentSession?.id == sessionId) currentSession else readSession(File(sessionsRoot, sessionId))
            val updated = transform(requireNotNull(existing) { "Capture session not found: $sessionId" })
            persistSession(updated)
            if (currentSession?.id == sessionId) currentSession = updated
            if (mutableSelectedSession.value?.id == sessionId) mutableSelectedSession.value = updated
            publishLocked(mutableSnapshot.value.state, force = true)
            return updated
        }
    }

    @Suppress("TooGenericExceptionCaught") // A failing flush during stop must not abort the teardown.
    private fun publishLocked(state: RecorderState, force: Boolean = false) {
        val now = clock.monotonicMillis()
        if (!force && lastPublishMs != Long.MIN_VALUE && now >= lastPublishMs &&
            now - lastPublishMs < PREVIEW_PUBLISH_INTERVAL_MS
        ) return
        if (active.get()) {
            logOutput?.flush()
            indexOutput?.flush()
        } else {
            // Stop path: stopInternal has claimed the stop and still has processes to terminate and
            // streams to close. Record the failure; the final flush there decides the session status.
            try {
                logOutput?.flush()
                indexOutput?.flush()
            } catch (failure: IOException) {
                recordStopStorageFailureLocked(failure)
            } catch (failure: RuntimeException) {
                recordStopStorageFailureLocked(failure)
            }
        }
        lastPublishMs = now
        val sessionForUi = currentSession?.let { session ->
            if (active.get()) session.copy(elapsedMs = maxOf(session.elapsedMs, elapsedNow())) else session
        }
        if (sessionForUi != null && mutableSelectedSession.value?.id == sessionForUi.id) {
            mutableSelectedSession.value = sessionForUi
        }
        mutableSnapshot.value = RecorderSnapshot(
            state = state,
            session = sessionForUi,
            diagnostics = diagnostics.toList(),
            logBytes = logBytes,
            indexedRows = rowOrdinal,
            videoRecording = embeddedSession?.hasStartedVideo() == true,
        )
    }

    private fun recordStopStorageFailureLocked(failure: Exception) {
        val message = failure.message ?: failure::class.simpleName ?: "Capture storage failed"
        if (stopStorageFailure == null) stopStorageFailure = message
        addDiagnosticLocked("Capture storage failed while stopping: $message")
    }

    private fun addDiagnostic(message: String) = synchronized(lock) {
        addDiagnosticLocked(message)
        publishLocked(mutableSnapshot.value.state, force = true)
    }

    /** Resolves the "keep sound on the device" delta args for this session's audio (see
     *  [captureAudioPlan]), reading the device's SDK level once via adb only when it's actually
     *  needed (audio and the setting are both on) — never on the far more common "audio off" or
     *  "keep off" paths. Any diagnostic the plan carries (the Android-13+ fallback notice) is
     *  surfaced immediately, the same way every other capture-start diagnostic is. */
    private fun resolveAudioPlan(tools: CaptureTools, serial: String, settings: CaptureSettings): CaptureAudioPlan {
        if (!settings.audio || !settings.keepDeviceAudio) {
            return captureAudioPlan(settings.audio, settings.keepDeviceAudio, deviceSdk = null)
        }
        val sdk = runCatching { tools.readDeviceSdkLevel(serial) }.getOrNull()
        return captureAudioPlan(settings.audio, settings.keepDeviceAudio, sdk).also { plan ->
            plan.diagnostic?.let(::addDiagnostic)
        }
    }

    private fun addDiagnosticLocked(message: String) {
        if (diagnostics.size == MAX_DIAGNOSTICS) diagnostics.removeFirst()
        diagnostics.addLast(message.take(MAX_DIAGNOSTIC_CHARS))
        // The strip's Diagnostics list is in-memory only and gone once the capture closes, so a
        // "video never started" report had nothing to go on. Mirror each line into the opt-in
        // debug log (a no-op unless enabled); AppLogger.safeText redacts paths and secrets.
        AppLogger.info("capture", message.take(MAX_DIAGNOSTIC_CHARS))
    }

    private fun drainDiagnostics(input: InputStream, source: String, onLine: (String) -> Unit = {}): Thread =
        thread(name = "capture-$source-diagnostics", isDaemon = true) {
            input.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        onLine(line)
                        addDiagnostic("$source: $line")
                    }
                }
            }
        }

    /** Reads and drops [input] so a chatty child can never block on a full stdout pipe. */
    private fun discardOutput(input: InputStream, source: String) {
        thread(name = "capture-$source-stdout", isDaemon = true) {
            runCatching { input.use { it.copyTo(OutputStream.nullOutputStream()) } }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Disk checks include filesystem and caller-supplied runtime failures.
    private fun runWatchdog() {
        while (active.get()) {
            try {
                Thread.sleep(watchdogIntervalMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (!active.get()) return
            var failure: String? = null
            synchronized(lock) {
                val session = currentSession ?: return@synchronized
                val elapsed = elapsedNow()
                try {
                    enforceStorageLimitsLocked(session, elapsed, 0)
                    if (lastMetadataPersistMs == Long.MIN_VALUE ||
                        elapsed - lastMetadataPersistMs >= metadataPersistIntervalMs
                    ) {
                        val persisted = session.copy(elapsedMs = maxOf(session.elapsedMs, elapsed))
                        currentSession = persisted
                        if (mutableSelectedSession.value?.id == persisted.id) {
                            mutableSelectedSession.value = persisted
                        }
                        persistSession(persisted)
                        lastMetadataPersistMs = elapsed
                    }
                    publishLocked(RecorderState.RECORDING, force = true)
                } catch (error: IOException) {
                    failure = error.message ?: "Capture storage failed"
                } catch (error: RuntimeException) {
                    failure = error.message ?: "Capture storage failed"
                }
            }
            if (failure != null) {
                interrupt(requireNotNull(failure))
                return
            }
        }
    }

    private fun enforceStorageLimitsLocked(session: CaptureSession, elapsed: Long, incomingLogBytes: Int) {
        check(logBytes + incomingLogBytes <= session.settings.sessionLimitBytes) {
            "Capture stopped at the configured session size limit"
        }
        if (lastSpaceCheckMs != Long.MIN_VALUE &&
            elapsed - lastSpaceCheckMs < spaceCheckIntervalMs
        ) return
        lastSpaceCheckMs = elapsed
        // Include buffered log/index bytes in the directory accounting before checking a limit.
        logOutput?.flush()
        indexOutput?.flush()
        val used = directorySize(session.directory)
        check(used < session.settings.sessionLimitBytes) { "Capture stopped at the configured session size limit" }
        check(diskSpace.usableBytes(session.directory) >= session.settings.freeSpaceReserveBytes) {
            "Capture stopped to preserve the configured free-space reserve"
        }
    }

    // Deliberately does NOT synchronize on [lock] — see startedMonotonicMs's doc. This is called as
    // EmbeddedDeviceSession's `elapsedMillis` from inside that session's own lock (its
    // onVideoStartElapsedMs callback), so it must never itself try to acquire [lock] — the
    // watchdog thread holds [lock] while calling back into the session (hasStartedVideo()), and two
    // objects each waiting on the other's lock is a deadlock, not a slow path.
    private fun elapsedNow(): Long {
        val observed = (clock.monotonicMillis() - startedMonotonicMs).coerceAtLeast(0)
        return lastElapsedMsAtomic.updateAndGet { current -> maxOf(current, observed) }
    }

    private fun persistSession(session: CaptureSession) {
        val destination = File(session.directory, SESSION_FILE)
        writeFileAtomically(destination) { it.write(sessionJson(session)) }
    }

    private fun readSession(directory: File): CaptureSession? {
        val file = File(directory, SESSION_FILE)
        return runCatching { sessionFromJson(file.readText(), directory) }.getOrNull()
    }
}

/** Reads only complete, parseable index records so an interrupted final write is ignored. */
private fun lastIndexedElapsedMs(indexFile: File): Long = runCatching {
    if (!indexFile.isFile) return@runCatching 0L
    var last = 0L
    indexFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines.forEach { line ->
            runCatching {
                Json.parseToJsonElement(line).jsonObject["elapsedMs"]?.jsonPrimitive?.longOrNull
            }.getOrNull()?.let { last = maxOf(last, it) }
        }
    }
    last
}.getOrDefault(0L)

private fun forEachRawLine(input: InputStream, consume: (ByteArray) -> Boolean) {
    val line = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        for (index in 0 until count) {
            val value = buffer[index]
            line.write(value.toInt())
            if (value == '\n'.code.toByte()) {
                if (!consume(line.toByteArray())) return
                line.reset()
            }
        }
    }
    if (line.size() > 0) consume(line.toByteArray())
}

private fun isSeparator(raw: ByteArray): Boolean =
    raw.toString(Charsets.UTF_8).trim().let { it.isEmpty() || it.startsWith("-----") }

internal fun indexRecordJson(record: CaptureLogIndexRecord): String = buildJsonObject {
    put("byteOffset", record.byteOffset)
    put("byteLength", record.byteLength)
    put("elapsedMs", record.elapsedMs)
    record.rowOrdinal?.let { put("rowOrdinal", it) }
}.toString()

internal fun sessionJson(session: CaptureSession): String = buildJsonObject {
    put("formatVersion", 1)
    put("id", session.id)
    put("device", buildJsonObject {
        put("serial", session.device.serial)
        put("state", session.device.state)
        put("model", session.device.model)
        put("emulator", session.device.emulator)
    })
    put("settings", Json.parseToJsonElement(captureSettingsToJson(session.settings)))
    put("startedEpochMs", session.startedEpochMs)
    put("elapsedMs", session.elapsedMs)
    put("status", session.status.name)
    session.videoStartElapsedMs?.let { put("videoStartElapsedMs", it) }
    put("snapshotCheckpointMs", session.effectiveSnapshotCheckpointMs)
    // Was never written before the video/log cursor split (only read, always defaulting to -1 on
    // reload) even though updateSuccessfulExportCheckpoints already tracked it in memory — every
    // restart silently forgot which video range had already been saved. Persisted verbatim (not
    // through an "effective" accessor): -1 here means "no export has ever included video" and must
    // stay distinguishable from "video coverage happens to end at the log checkpoint".
    put("videoCheckpointMs", session.videoCheckpointMs)
    put("exportCounter", session.exportCounter)
    put("interruptions", buildJsonArray { session.interruptions.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    put("manualOffsetMs", session.manualOffsetMs)
}.toString()

/**
 * Reads the `session.json` of one capture session [directory] without needing a recorder rooted at
 * its parent — for callers that already know the exact session (a restored tab whose log lives in
 * it). Null when the file is missing or not a recognised session.
 */
fun readCaptureSessionDirectory(directory: File): CaptureSession? = runCatching {
    sessionFromJson(File(directory, SESSION_FILE).readText(), directory)
}.getOrNull()

private fun sessionFromJson(raw: String, directory: File): CaptureSession? = runCatching {
    val root = Json.parseToJsonElement(raw).jsonObject
    require(root["formatVersion"]?.jsonPrimitive?.intOrNull == 1)
    val deviceJson = root.getValue("device").jsonObject
    val settingsJson = root.getValue("settings").toString()
    CaptureSession(
        id = root["id"]?.jsonPrimitive?.contentOrNull ?: directory.name,
        directory = directory,
        device = CaptureDevice(
            serial = deviceJson.getValue("serial").jsonPrimitive.content,
            state = deviceJson["state"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            model = deviceJson["model"]?.jsonPrimitive?.contentOrNull ?: deviceJson.getValue("serial").jsonPrimitive.content,
            emulator = deviceJson["emulator"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: deviceJson.getValue("serial").jsonPrimitive.content.startsWith("emulator-"),
        ),
        settings = requireNotNull(captureSettingsFromJson(settingsJson)),
        startedEpochMs = root.getValue("startedEpochMs").jsonPrimitive.long,
        elapsedMs = root["elapsedMs"]?.jsonPrimitive?.longOrNull ?: 0,
        status = root["status"]?.jsonPrimitive?.contentOrNull?.let { CaptureStatus.valueOf(it) } ?: CaptureStatus.INTERRUPTED,
        videoStartElapsedMs = root["videoStartElapsedMs"]?.jsonPrimitive?.longOrNull,
        // Sessions written before the canonical cursor used logCheckpointMs. Prefer the new
        // field when present, otherwise migrate the old log cursor in memory. The video cursor is
        // intentionally ignored: it was a separate cursor and could not describe one atomic
        // archive boundary.
        snapshotCheckpointMs = root["snapshotCheckpointMs"]?.jsonPrimitive?.longOrNull
            ?: root["logCheckpointMs"]?.jsonPrimitive?.longOrNull
            ?: -1,
        logCheckpointMs = root["logCheckpointMs"]?.jsonPrimitive?.longOrNull ?: -1,
        videoCheckpointMs = root["videoCheckpointMs"]?.jsonPrimitive?.longOrNull ?: -1,
        exportCounter = root["exportCounter"]?.jsonPrimitive?.intOrNull ?: 0,
        interruptions = root["interruptions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
        manualOffsetMs = root["manualOffsetMs"]?.jsonPrimitive?.longOrNull ?: 0,
    )
}.getOrNull()

private fun directorySize(directory: File): Long = directory.walkTopDown()
    .filter(File::isFile)
    .sumOf(File::length)

private fun sessionId(epochMs: Long): String {
    val timestamp = SESSION_ID_TIME.format(Instant.ofEpochMilli(epochMs))
    return "$timestamp-${UUID.randomUUID().toString().take(8)}"
}

private val SESSION_ID_TIME: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyyMMdd-HHmmss")
    .withZone(ZoneOffset.UTC)

/** Standard 8-byte PNG file signature (`\x89PNG\r\n\x1a\n`). */
private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
)

/**
 * One bounded `adb exec-out screencap -p` read for [serial], without a running capture session; the output is
 * limited to [maxBytes]. Throws [IllegalStateException] with the adb diagnostic when it fails or times out.
 */
internal fun CaptureTools.readScreencapPng(serial: String, maxBytes: Int = MAX_SCREENSHOT_BYTES): ByteArray =
    screencapPngFrom(runAdb(serial, SCREENCAP_ARGUMENTS, Duration.ofSeconds(SCREENSHOT_TIMEOUT_SECONDS), maxBytes))

/** Validates a `screencap -p` [result] and returns its PNG bytes, stripping any banner text before the signature. */
private fun screencapPngFrom(result: CaptureCommandResult, onDiagnostic: (String) -> Unit = {}): ByteArray {
    check(!result.timedOut) { "Screenshot timed out" }
    check(result.exitCode == 0 && result.stdout.isNotEmpty()) {
        val detail = result.stderrText().trim().take(MAX_DIAGNOSTIC_CHARS)
        if (detail.isEmpty()) "Screenshot failed (exit ${result.exitCode})" else "Screenshot failed: $detail"
    }
    // Multi-display devices may prefix a warning line to the PNG stream. Strip it in both the
    // transient AI path and durable screenshot path before image consumers see the bytes.
    val pngBytes = stripLeadingNonPngBytes(result.stdout)
    checkNotNull(pngBytes) { "Screenshot did not contain PNG data" }
    if (pngBytes.size != result.stdout.size) {
        onDiagnostic(
            "Screenshot: stripped ${result.stdout.size - pngBytes.size} byte(s) of device banner text " +
                "before the PNG signature",
        )
    }
    return pngBytes
}

/**
 * Finds the PNG signature in [bytes] and returns the data from there on, dropping any prefix
 * (see [CaptureRecorder.screenshotCapture]'s banner-stripping note above). Returns the original
 * array unchanged when the signature is already at offset 0 (the common case), and null when no
 * PNG signature is present anywhere in [bytes].
 */
private fun stripLeadingNonPngBytes(bytes: ByteArray): ByteArray? {
    if (bytes.size < PNG_SIGNATURE.size) return null
    outer@ for (start in 0..(bytes.size - PNG_SIGNATURE.size)) {
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[start + i] != PNG_SIGNATURE[i]) continue@outer
        }
        return if (start == 0) bytes else bytes.copyOfRange(start, bytes.size)
    }
    return null
}

private const val SESSION_FILE = "session.json"
private const val PREVIEW_PUBLISH_INTERVAL_MS = 250L
private const val SPACE_CHECK_INTERVAL_MS = 1_000L
private const val SESSION_METADATA_PERSIST_INTERVAL_MS = 5_000L
private const val MAX_DIAGNOSTICS = 50
private const val MAX_DIAGNOSTIC_CHARS = 4_096
private const val MAX_SCREENSHOT_BYTES = 64 * 1024 * 1024
private const val CAPTURE_WRITE_BUFFER_BYTES = 256 * 1024
private const val STOP_WAIT_SECONDS = 8L
private const val SCREENSHOT_TIMEOUT_SECONDS = 15L
private val SCREENCAP_ARGUMENTS = listOf("exec-out", "screencap", "-p")
private const val VIDEO_MONITOR_MAX_WAIT_DAYS = 3_650L
private const val PROCESS_JOIN_TIMEOUT_MS = 3_000L
private const val WATCHDOG_JOIN_TIMEOUT_MS = 1_000L
private const val MIRROR_STDERR_TAIL_LINES = 50
private const val MIRROR_STDERR_JOIN_MS = 2_000L
private const val NANOS_PER_MILLI = 1_000_000L

/** A scrcpy window that dies this soon after launch never got going, which is how a broken host audio
 *  device shows up when its error line is not recognisable. */
internal const val MIRROR_EARLY_EXIT_MS = 5_000L

/** Diagnostic added when the visible mirror is reopened with `--no-audio`; the strip keys its short
 *  notice off this prefix (see latestMirrorAudioFallbackNotice). */
internal const val MIRROR_AUDIO_FALLBACK_DIAGNOSTIC =
    "scrcpy window crashed while opening audio; reopened without sound (--no-audio)."

private val MIRROR_LOG_LEVEL_WORD = Regex("\\b(error|warn|warning)\\b")

/**
 * Whether a visible scrcpy window that just exited should be reopened once with `--no-audio`: it must
 * have failed (non-zero exit), have had audio on ([audioRequested]), not already be that retry, and
 * either logged an ERROR/WARN line mentioning audio or died within [MIRROR_EARLY_EXIT_MS] of launch.
 */
internal fun shouldRetryMirrorWithoutAudio(
    exitCode: Int?,
    stderrLines: List<String>,
    audioRequested: Boolean,
    alreadyRetried: Boolean,
    uptimeMs: Long,
): Boolean {
    if (alreadyRetried || !audioRequested || exitCode == null || exitCode == 0) return false
    val audioError = stderrLines.any { line ->
        val lower = line.lowercase()
        "audio" in lower && MIRROR_LOG_LEVEL_WORD.containsMatchIn(lower)
    }
    return audioError || uptimeMs < MIRROR_EARLY_EXIT_MS
}

/**
 * Directories of capture sessions that a [CaptureRecorder] in THIS process is recording right now:
 * added when a recorder activates, removed once its stop has closed the video file and persisted the
 * final status. [CaptureArchiveExporter] consults it because a session's on-disk status can lie
 * (`recoverSessions()` may mark a live session INTERRUPTED), so "the video stopped growing" must not
 * be inferred from the status of a session this process still owns.
 */
internal object LiveCaptureSessions {
    private val directories = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun key(directory: File): String = directory.absoluteFile.normalize().path

    fun add(directory: File) {
        directories.add(key(directory))
    }

    fun remove(directory: File) {
        directories.remove(key(directory))
    }

    fun isLive(directory: File): Boolean = key(directory) in directories
}
