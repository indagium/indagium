package com.indagium.capture

import com.indagium.capture.mirror.AdbScrcpyTransport
import com.indagium.capture.mirror.EmbeddedDeviceSession
import com.indagium.capture.mirror.EmbeddedMirrorTransport
import com.indagium.capture.mirror.MirrorStreamOptions
import com.indagium.debug.AppLogger
import com.indagium.utils.writeFileAtomically
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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

/** Emitted after a verified restart's device logging restore attempt finishes. */
data class DeviceLoggingRestoreEvent(val serial: String)

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
    private val reconnectBaseDelayMs: Long = 250L,
    // Test seam: production builds a real AdbScrcpyTransport (embedded scrcpy server over adb);
    // tests substitute a fake transport so the embedded recording path — including reconnects and
    // PTS continuity — can be driven end to end without a device. See EmbeddedDeviceSession and its
    // own unit tests for the packet-level behaviour this wires up; CaptureRecorderTest exercises the
    // recorder-level lifecycle (start/stop, diagnostics, videoStartElapsedMs persistence) against a
    // fake transport supplied here.
    private val embeddedTransportFactory: (CaptureTools) -> EmbeddedMirrorTransport = { tools ->
        AdbScrcpyTransport(tools = tools, runner = runner, localRoot = sessionsRoot)
    },
    // Test seam for exercising the bounded replay-index overflow path without multi-gigabyte logs.
    private val replayHistoryByteLimitForTests: Int = MAX_REPLAY_HISTORY_BYTES,
    private val replayHistoryRowLimitForTests: Int = MAX_REPLAY_HISTORY_ROWS,
    // Test seam: lets a test hand the recorder a storage stream that fails on demand (disk full).
    private val openStorage: (File) -> FileOutputStream = { FileOutputStream(it, true) },
    // Test seams for the bounded post-boot settings restore window. Production waits for Android
    // boot completion, then verifies that host-side emulator initialization has stopped changing
    // logd settings before reporting the saved configuration as restored.
    private val loggingRestoreStabilityWindowMs: Long = LOGGING_RESTORE_STABILITY_WINDOW_MS,
    private val loggingRestoreSampleIntervalMs: Long = LOGGING_RESTORE_SAMPLE_INTERVAL_MS,
    private val loggingRestoreBootWaitMs: Long = LOGGING_RESTORE_BOOT_WAIT_MS,
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
    private val mutableDeviceLoggingRestoreEvents = MutableSharedFlow<DeviceLoggingRestoreEvent>(replay = 1, extraBufferCapacity = 1)
    private var logThread: Thread? = null
    private var watchdogThread: Thread? = null
    private var loggingRestoreThread: Thread? = null
    private val loggingRestoreGeneration = AtomicLong(0)
    private var logFileOutput: FileOutputStream? = null
    private var indexFileOutput: FileOutputStream? = null
    private var logOutput: BufferedOutputStream? = null
    private var indexOutput: BufferedOutputStream? = null
    private var logBytes: Long = 0
    private var rowOrdinal: Int = 0
    private var lastPublishMs: Long = Long.MIN_VALUE
    private var lastSpaceCheckMs: Long = Long.MIN_VALUE
    private var lastMetadataPersistMs: Long = Long.MIN_VALUE
    @Volatile private var lastKnownBootId: String? = null
    @Volatile private var lastKnownBootUptimeMs: Long? = null
    @Volatile private var bootUptimeObservedElapsedMs: Long = 0
    @Volatile private var lastKnownDeviceWallClockMsOfYear: Long? = null
    @Volatile private var deviceWallClockObservedElapsedMs: Long = 0
    private var stopLatch = CountDownLatch(0)
    /** Wakes the reconnect backoff promptly on Stop without interrupting the active logcat reader. */
    private val reconnectWaitMonitor = Object()

    /** First storage failure seen while stopping (guarded by [lock]); turns the final status INTERRUPTED. */
    private var stopStorageFailure: String? = null

    val snapshot: StateFlow<RecorderSnapshot> = mutableSnapshot.asStateFlow()
    val selectedSession: StateFlow<CaptureSession?> = mutableSelectedSession.asStateFlow()
    val deviceLoggingRestoreEvents: SharedFlow<DeviceLoggingRestoreEvent> = mutableDeviceLoggingRestoreEvents.asSharedFlow()

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
    ): CaptureSession = startInternal(device, settings, tools, null, startupNotice, beforeLogcatLaunch)

    /** Starts with an off-thread device logging snapshot. Kept separate from [start] so existing
     * trailing-lambda call sites remain source-compatible. */
    fun startWithDeviceLoggingConfiguration(
        device: CaptureDevice,
        settings: CaptureSettings,
        tools: CaptureTools,
        deviceLoggingConfiguration: DeviceLoggingConfiguration?,
        startupNotice: String? = null,
        beforeLogcatLaunch: ((CaptureSession) -> Unit)? = null,
    ): CaptureSession = startInternal(device, settings, tools, deviceLoggingConfiguration, startupNotice, beforeLogcatLaunch)

    private fun startInternal(
        device: CaptureDevice,
        settings: CaptureSettings,
        tools: CaptureTools,
        deviceLoggingConfiguration: DeviceLoggingConfiguration?,
        startupNotice: String?,
        beforeLogcatLaunch: ((CaptureSession) -> Unit)?,
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
            startCapture(device, settings, tools, deviceLoggingConfiguration, startupNotice, beforeLogcatLaunch)
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
        deviceLoggingConfiguration: DeviceLoggingConfiguration?,
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
            deviceLoggingConfiguration = deviceLoggingConfiguration,
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
            lastKnownBootId = readBootId(tools, device.serial)
            lastKnownBootUptimeMs = readBootUptimeMs(tools, device.serial)
            lastKnownDeviceWallClockMsOfYear = readDeviceWallClockMsOfYear(tools, device.serial)
            val readinessObservedElapsedMs = elapsedNow()
            bootUptimeObservedElapsedMs = readinessObservedElapsedMs
            deviceWallClockObservedElapsedMs = readinessObservedElapsedMs
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
            val initialLogcatError = AtomicReference<String?>(null)
            val initialErrorDrain = drainDiagnostics(process.errorStream, "adb") { line ->
                if (isFatalLogcatDiagnostic(line)) initialLogcatError.compareAndSet(null, line.take(MAX_DIAGNOSTIC_CHARS))
            }
            logThread = thread(name = "capture-logcat-${session.id}", isDaemon = true) {
                consumeLogcat(process, initialLogcatError, initialErrorDrain)
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

    /** Replaces known pieces after a successful in-session device logging change. Unknown reads are
     * ignored, while an explicitly empty global property/map remains a meaningful device default. */
    fun updateDeviceLoggingConfiguration(serial: String, configuration: DeviceLoggingConfiguration) {
        var failureMessage: String? = null
        synchronized(lock) {
            val old = currentSession ?: return
            if (!active.get() || old.device.serial != serial) return
            val known = old.deviceLoggingConfiguration ?: DeviceLoggingConfiguration()
            val updated = old.copy(deviceLoggingConfiguration = known.mergeKnown(configuration))
            currentSession = updated
            mutableSelectedSession.value = updated
            try {
                persistSession(updated)
            } catch (failure: IOException) {
                failureMessage = failure.message ?: "Capture session metadata could not be saved"
                recordStopStorageFailureLocked(failure)
            } catch (failure: RuntimeException) {
                failureMessage = failure.message ?: "Capture session metadata could not be saved"
                recordStopStorageFailureLocked(failure)
            }
        }
        failureMessage?.let { interrupt("Capture storage failed while saving device logging settings: $it") }
    }

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

    @Suppress("TooGenericExceptionCaught")
    private fun consumeLogcat(
        initialProcess: RunningCaptureProcess,
        initialError: AtomicReference<String?>,
        initialErrorDrain: Thread,
    ) {
        val session = synchronized(lock) { currentSession } ?: return
        val tools = synchronized(lock) { currentTools } ?: return
        val readerState = LogcatReaderState(replayHistoryByteLimitForTests, replayHistoryRowLimitForTests)
        var process = initialProcess
        var processError = initialError
        var errorDrain = initialErrorDrain
        var attempt = 0
        while (true) {
            val storageFailure = consumeLogcatStream(process, readerState)
            val streamEndedElapsedMs = elapsedNow()
            process.close()
            if (errorDrain !== Thread.currentThread()) errorDrain.join(LOGCAT_DIAGNOSTIC_JOIN_MS)
            synchronized(lock) { if (logProcess === process) logProcess = null }
            if (storageFailure || !active.get()) return
            val error = processError.get()
            if (error != null) {
                interrupt("Capture stopped because adb logcat rejected its configuration: $error")
                return
            }
            val initialReadiness = probeDeviceReadiness(tools, session.device.serial)
            val wasUnavailable = synchronized(lock) {
                currentSession?.availabilityIntervals?.lastOrNull()?.endElapsedMs == null &&
                    currentSession?.availabilityIntervals?.isNotEmpty() == true
            }
            val bootChangedDuringDisconnect = lastKnownBootId != null && initialReadiness.bootId != null &&
                lastKnownBootId != initialReadiness.bootId
            val sameIdRestartDuringDisconnect = sameIdentityRestartEvidence(initialReadiness)
            if (!initialReadiness.ready || bootChangedDuringDisconnect || sameIdRestartDuringDisconnect) {
                cancelDeviceLoggingRestore()
                if (!recordDeviceUnavailable(session.device, streamEndedElapsedMs)) return
            } else if (wasUnavailable) {
                // The device became shell-responsive before this reader observed the transition.
                // Close the interval after a replacement stream is accepted below.
            } else {
                addDiagnostic("Logcat stream ended while the device remained available; retrying logcat.")
            }
            attempt = 0
            var reconnectReadiness = initialReadiness
            while (active.get()) {
                try {
                    synchronized(reconnectWaitMonitor) {
                        if (active.get()) reconnectWaitMonitor.wait(reconnectDelay(attempt++))
                    }
                } catch (_: InterruptedException) {
                    // Only a Stop or a programmer error can interrupt this thread. Stop itself
                    // uses notifyAll so it never cuts off buffered bytes from the current stream.
                    if (!active.get()) return
                }
                if (!active.get()) return
                if (!reconnectReadiness.ready) reconnectReadiness = probeDeviceReadiness(tools, session.device.serial)
                if (!reconnectReadiness.ready) continue
                val priorBootId = lastKnownBootId
                val bootChanged = priorBootId != null && reconnectReadiness.bootId != null && priorBootId != reconnectReadiness.bootId
                val sameIdRestart = !bootChanged && sameIdentityRestartEvidence(reconnectReadiness)
                val restartVerified = bootChanged || sameIdRestart
                var drainComplete = false
                var sameIdCursorEstablishedByPartialDrain = false
                var replayOccurrenceBudget: Map<ReplayRowFingerprint, Int>? = null
                if (restartVerified) {
                    if (bootChanged) readerState.resetForNewBoot() else readerState.beginBoundaryDeduplication()
                    // This is the immutable old-session boundary used by the overflow fallback.
                    // The reader has ended, so flushing here captures every row written before
                    // the disconnect without holding the UI thread or retaining the log in RAM.
                    val preGapLogLength = if (sameIdRestart) flushedLogLength(session) else 0L
                    var drainAttempt = 0
                    var waitingForSafeDrain = false
                    while (active.get()) {
                        val drain = drainReconnectBuffer(
                            tools,
                            session,
                            readerState,
                            searchForSameBootOverlap = !bootChanged,
                            preGapLogLength = preGapLogLength,
                        )
                        if (drain.fatalConfigurationFailure) {
                            interrupt(drain.diagnostic ?: "Capture stopped because adb logcat rejected its configuration.")
                            return
                        }
                        val hasReplayRows = drain.bufferedTimestampedRows > 0
                        if (sameIdRestart && !drain.complete && !hasReplayRows) {
                            if (!waitingForSafeDrain) {
                                addDiagnostic("Waiting for a complete buffered log sample after the device clock reset; capture remains active.")
                                waitingForSafeDrain = true
                            }
                            if (!awaitReconnectRetry(drainAttempt++)) return
                            continue
                        }
                        if (sameIdRestart && drain.complete && !hasReplayRows) {
                            // An inspected empty ring is a safe boundary. The old timestamp may
                            // be in the future after QuickBoot, so it must not survive this reset.
                            readerState.resetForEmptyRestartBuffer()
                        } else if (sameIdRestart && !drain.complete && drain.appendedTimestampedRows > 0) {
                            // A capped sample still establishes a usable cursor when it contains
                            // complete, newly indexed timestamped rows. Resume from that cursor;
                            // the unterminated tail was already excluded by the drain parser.
                            sameIdCursorEstablishedByPartialDrain = true
                        } else if (sameIdRestart && drain.appendedTimestampedRows == 0) {
                            // The current ring is fully known replay. Keep its row occurrence quotas
                            // for the replacement stream and clear the stale clock cursor below.
                            readerState.clearTimestampCursor()
                        }
                        drainComplete = drain.complete
                        replayOccurrenceBudget = drain.replayOccurrenceBudget
                        drain.diagnostic?.let(::addDiagnostic)
                        if (!drainComplete && drain.diagnostic == null) {
                            addDiagnostic("The buffered device log drain reached its size limit; continuing capture without resizing buffers.")
                        }
                        break
                    }
                    if (!active.get()) return
                }
                // A same-identity clock rewind can invalidate the old timestamp cursor. Keep
                // exact row occurrence quotas across buffer ordering instead of comparing old
                // timestamps. A confirmed new boot has a fresh cursor established by its drain.
                val resumeCursor = if (sameIdRestart && !sameIdCursorEstablishedByPartialDrain) {
                    null
                } else {
                    readerState.lastDeviceTimestamp
                }
                val replacement = try {
                    runner.start(tools.adbSpec(
                        session.device.serial,
                        logcatArguments(session.settings, resumeCursor, retainNewBootBuffer = restartVerified),
                    ))
                } catch (failure: IOException) {
                    interrupt("Capture stopped because adb logcat could not be restarted: ${failure.message ?: failure::class.simpleName}")
                    return
                } catch (failure: RuntimeException) {
                    interrupt("Capture stopped because adb logcat could not be restarted: ${failure.message ?: failure::class.simpleName}")
                    return
                }
                val accepted = synchronized(lock) {
                    if (active.get()) {
                        logProcess = replacement
                        true
                    } else false
                }
                if (!accepted) {
                    replacement.terminate()
                    replacement.close()
                    return
                }
                if (sameIdRestart) readerState.beginReplayOccurrenceDeduplication(replayOccurrenceBudget)
                val replacementError = AtomicReference<String?>(null)
                errorDrain = drainDiagnostics(replacement.errorStream, "adb") { line ->
                    if (isFatalLogcatDiagnostic(line)) replacementError.compareAndSet(null, line.take(MAX_DIAGNOSTIC_CHARS))
                }
                processError = replacementError
                if (!recordDeviceReconnected(reconnectReadiness.bootId)) {
                    replacement.terminate()
                    replacement.close()
                    return
                }
                if (restartVerified) {
                    val latestSession = synchronized(lock) { currentSession } ?: session
                    startDeviceLoggingRestore(tools, latestSession, restoreBufferSizes = drainComplete)
                    if (!sameIdRestart) readerState.beginBoundaryDeduplication()
                } else {
                    readerState.beginBoundaryDeduplication()
                }
                process = replacement
                lastKnownBootId = reconnectReadiness.bootId ?: lastKnownBootId
                lastKnownBootUptimeMs = reconnectReadiness.uptimeMs ?: lastKnownBootUptimeMs
                lastKnownDeviceWallClockMsOfYear = reconnectReadiness.deviceWallClockMsOfYear ?: lastKnownDeviceWallClockMsOfYear
                val readinessObservedElapsedMs = elapsedNow()
                bootUptimeObservedElapsedMs = readinessObservedElapsedMs
                deviceWallClockObservedElapsedMs = readinessObservedElapsedMs
                reconnectReadiness = DeviceReadiness(false)
                break
            }
        }
    }

    private fun consumeLogcatStream(process: RunningCaptureProcess, state: LogcatReaderState): Boolean {
        // Set when this reader itself stopped the capture (size limit, free-space reserve, write
        // failure). interrupt() hands the stop to another thread, so `active` can still read true
        // when the loop below returns; without this flag the "logcat ended" check after it raced
        // that thread and, if it won, replaced the real reason with "the device may have
        // disconnected".
        var stoppedForStorage = false
        try {
            process.inputStream.use { input ->
                forEachRawLine(input) { raw ->
                    val line = raw.toString(Charsets.UTF_8).trimEnd('\r', '\n')
                    val emittedLines = if (isSeparator(raw)) {
                        listOf(DeviceLogLine(line, raw))
                    } else {
                        state.accept(DeviceLogLine(line, raw))
                    }
                    if (!writeDeviceLogLines(state, emittedLines, allowAfterStop = true)) {
                        stoppedForStorage = true
                        false
                    } else true
                }
            }
            if (!stoppedForStorage && !writeDeviceLogLines(state, state.flushPending(), allowAfterStop = true)) stoppedForStorage = true
        } catch (failure: IOException) {
            if (!stoppedForStorage && active.get()) {
                AppLogger.info("capture", "adb logcat stream ended: ${failure.message ?: failure::class.simpleName}")
            }
        }
        return stoppedForStorage
    }

    /** Writes already-parsed device rows through the same append-only log/index path used by the
     * streaming reader. Used for a bounded `logcat -d` drain before a reboot restore can resize
     * buffers, so early boot evidence is durable before the ring is changed. */
    private fun writeDeviceLogLines(
        state: LogcatReaderState,
        lines: List<DeviceLogLine>,
        allowAfterStop: Boolean = false,
    ): Boolean {
        for (emitted in lines) {
            if (!allowAfterStop && !active.get()) return false
            val elapsed = elapsedNow()
            var storageFailure: String? = null
            synchronized(lock) {
                val session = currentSession ?: return@synchronized
                if (logOutput == null || indexOutput == null) return@synchronized
                try {
                    enforceStorageLimitsLocked(session, elapsed, emitted.raw.size)
                    val offset = logBytes
                    val separator = isSeparator(emitted.raw)
                    val ordinal = if (separator) null else ++rowOrdinal
                    requireNotNull(logOutput).write(emitted.raw)
                    logBytes += emitted.raw.size
                    val record = CaptureLogIndexRecord(offset, emitted.raw.size, elapsed, ordinal)
                    requireNotNull(indexOutput).write((indexRecordJson(record) + "\n").toByteArray(Charsets.UTF_8))
                    if (!separator) state.record(emitted.text)
                } catch (failure: IOException) {
                    storageFailure = failure.message ?: "Capture storage failed"
                    recordStopStorageFailureLocked(failure)
                } catch (failure: RuntimeException) {
                    storageFailure = failure.message ?: "Capture storage failed"
                    recordStopStorageFailureLocked(failure)
                }
            }
            if (storageFailure != null) {
                // The failure is recorded under lock above so Stop cannot finalize between the
                // failed append and the asynchronous interrupt request.
                interrupt(storageFailure)
                return false
            }
            // Stop deliberately terminates logcat and then joins this reader so it can append the
            // final stream bytes. Reconnect-buffer drains have no such ownership and must abort
            // as soon as Stop has claimed the session.
            if (!allowAfterStop && !active.get()) return false
        }
        return true
    }

    private fun drainReconnectBuffer(
        tools: CaptureTools,
        session: CaptureSession,
        readerState: LogcatReaderState,
        searchForSameBootOverlap: Boolean,
        preGapLogLength: Long?,
    ): BufferedReconnectDrain {
        if (!active.get()) return BufferedReconnectDrain(complete = false)
        val outputLimitBytes = reconnectBufferOutputLimit(session)
        val result = runCatching {
            tools.runAdb(
                session.device.serial,
                buildList {
                    add("logcat")
                    add("-d")
                    addAll(listOf("-v", "threadtime"))
                    addAll(session.settings.logcatBufferArgs())
                },
                timeout = Duration.ofSeconds(8),
                outputLimitBytes = outputLimitBytes,
            )
        }.getOrNull() ?: return BufferedReconnectDrain(false, "Could not drain buffered boot logs before restoring device logging settings.")
        if (result.timedOut || result.exitCode != 0) {
            val permission = isPermissionFailure(result)
            val detail = if (permission) " (permission denied)" else ""
            val diagnostic = "Could not drain buffered boot logs$detail before restoring device logging settings."
            return BufferedReconnectDrain(
                complete = false,
                diagnostic = diagnostic,
                fatalConfigurationFailure = isFatalLogcatDiagnostic(result.stderrText()),
            )
        }
        val outputWasTruncated = result.stdout.size >= outputLimitBytes
        // adb's bounded-output reader may end in the middle of a UTF-8/logcat line. Index only
        // rows terminated by LF so the saved timestamp cursor can never move beyond unread bytes.
        val completeOutputLength = if (outputWasTruncated) {
            val lastCompleteLine = result.stdout.indexOfLast { it == '\n'.code.toByte() }
            if (lastCompleteLine < 0) 0 else lastCompleteLine + 1
        } else result.stdout.size
        val lines = ArrayList<DeviceLogLine>()
        forEachRawLine(ByteArrayInputStream(result.stdout, 0, completeOutputLength)) { raw ->
            lines += DeviceLogLine(raw.toString(Charsets.UTF_8).trimEnd('\r', '\n'), raw)
            true
        }
        var replayOccurrenceBudget: Map<ReplayRowFingerprint, Int>? = null
        var fallbackDiagnostic: String? = null
        val appendLines = if (searchForSameBootOverlap) {
            val priorCounts = readerState.replayHistoryOccurrencesSnapshot()
            var priorReplayCounts = priorCounts
            if (readerState.replayHistoryWasTruncated && lines.isNotEmpty()) {
                val candidates = readerState.countReplayOccurrences(lines, MAX_REPLAY_CANDIDATE_KEYS)
                when {
                    candidates == null -> {
                        fallbackDiagnostic = "QuickBoot replay history exceeded its bounded index and the buffered sample had too many distinct rows to scan safely; uncovered rows are preserved."
                    }
                    candidates.any { (fingerprint, count) -> count > (priorCounts[fingerprint] ?: 0) } -> {
                        val scanned = scanPreGapReplayOccurrences(
                            session.logFile,
                            preGapLogLength,
                            candidates,
                            readerState,
                        )
                        if (scanned == null) {
                            fallbackDiagnostic = "QuickBoot replay history exceeded its bounded index; the pre-gap log scan was interrupted, so uncovered rows are preserved."
                        } else {
                            priorReplayCounts = readerState.mergeReplayOccurrenceCounts(priorCounts, scanned)
                        }
                    }
                }
            }
            val filtered = readerState.removeBufferedReplayOverlap(lines, priorReplayCounts)
            val newlyAppended = readerState.countReplayOccurrences(filtered, MAX_REPLAY_CANDIDATE_KEYS)
            if (newlyAppended == null && fallbackDiagnostic == null) {
                fallbackDiagnostic = "QuickBoot replay rows exceeded the bounded stream replay quota; rows outside the quota are preserved."
            }
            replayOccurrenceBudget = newlyAppended?.let {
                readerState.addReplayOccurrenceCounts(priorReplayCounts, it)
            }
            if (newlyAppended != null && replayOccurrenceBudget == null && fallbackDiagnostic == null) {
                fallbackDiagnostic = "QuickBoot replay quotas exceeded the bounded stream replay limit; rows outside the quota are preserved."
            }
            filtered
        } else lines
        if (!writeDeviceLogLines(readerState, appendLines)) return BufferedReconnectDrain(false)
        val sizeLimitDiagnostic = if (outputWasTruncated) {
            "The buffered device log drain reached its ${outputLimitBytes / (1024 * 1024)} MiB size limit; continuing capture without resizing buffers."
        } else null
        return BufferedReconnectDrain(
            complete = !outputWasTruncated,
            diagnostic = listOfNotNull(fallbackDiagnostic, sizeLimitDiagnostic).joinToString(" ").takeIf { it.isNotEmpty() },
            bufferedTimestampedRows = lines.count { LOGCAT_THREADTIME_PREFIX.containsMatchIn(it.text) },
            appendedTimestampedRows = appendLines.count { LOGCAT_THREADTIME_PREFIX.containsMatchIn(it.text) },
            replayOccurrenceBudget = replayOccurrenceBudget,
        )
    }

    /** Flushes the ended stream and returns its fixed pre-gap byte boundary for replay scans. */
    private fun flushedLogLength(session: CaptureSession): Long? = synchronized(lock) {
        if (currentSession?.id == session.id) {
            try {
                logOutput?.flush()
                logFileOutput?.fd?.sync()
            } catch (failure: IOException) {
                addDiagnosticLocked("Could not flush the pre-gap log before checking QuickBoot replay: ${failure.message ?: "storage error"}.")
                return@synchronized null
            }
        }
        session.logFile.length()
    }

    /** Uses a 32 MiB floor for an unknown ring and a 2x formatting margin for known rings. */
    private fun reconnectBufferOutputLimit(session: CaptureSession): Int {
        val sizes = session.deviceLoggingConfiguration?.bufferSizesBytes ?: return MAX_RECONNECT_BUFFER_BYTES
        val selected = when (session.settings.bufferMode) {
            CaptureBufferMode.DEFAULT, CaptureBufferMode.ALL -> sizes.keys
            CaptureBufferMode.CUSTOM -> session.settings.buffers.distinct()
        }
        if (selected.isEmpty() || selected.any { it !in sizes }) return MAX_RECONNECT_BUFFER_BYTES
        val total = selected.fold(0L) { sum, name ->
            val next = sum + (sizes[name] ?: 0L)
            if (next < sum) Long.MAX_VALUE else next
        }
        val formatted = if (total > Long.MAX_VALUE / 2L) Long.MAX_VALUE else total * 2L
        return maxOf(
            MAX_RECONNECT_BUFFER_BYTES,
            formatted.coerceAtMost(MAX_RECONNECT_BUFFER_BYTES_WITH_SIZES.toLong()).toInt(),
        )
    }

    /** Counts only drain-row candidates while streaming the fixed pre-gap log boundary. */
    private fun scanPreGapReplayOccurrences(
        logFile: File,
        preGapLogLength: Long?,
        candidates: Map<ReplayRowFingerprint, Int>,
        readerState: LogcatReaderState,
    ): Map<ReplayRowFingerprint, Int>? {
        if (!active.get()) return null
        val limit = preGapLogLength ?: return null
        if (limit <= 0L || candidates.isEmpty()) return emptyMap()
        val remaining = candidates.toMutableMap()
        val found = HashMap<ReplayRowFingerprint, Int>(minOf(candidates.size, 16_384))
        return runCatching {
            FileInputStream(logFile).use { fileInput ->
                forEachRawLine(BoundedInputStream(fileInput, limit)) { raw ->
                    if (!active.get()) return@forEachRawLine false
                    if (raw.lastOrNull() != '\n'.code.toByte()) return@forEachRawLine true
                    val line = raw.toString(Charsets.UTF_8).trimEnd('\r', '\n')
                    val fingerprint = readerState.fingerprintIfDeviceRow(line) ?: return@forEachRawLine true
                    val needed = remaining[fingerprint] ?: return@forEachRawLine true
                    found[fingerprint] = (found[fingerprint] ?: 0) + 1
                    if (needed <= 1) remaining.remove(fingerprint) else remaining[fingerprint] = needed - 1
                    remaining.isNotEmpty()
                }
            }
            if (!active.get()) null else found
        }.getOrNull()
    }

    private fun restoreDeviceLoggingConfiguration(
        tools: CaptureTools,
        session: CaptureSession,
        restoreBufferSizes: Boolean,
        generation: Long,
    ) {
        if (!isCurrentLoggingRestore(generation)) return
        val initialConfiguration = latestLoggingConfiguration(session.id)
        if (initialConfiguration == null) {
            addDiagnostic("No device logging settings were captured; leaving device logging configuration unchanged after restart.")
            return
        }
        if (initialConfiguration.bufferSizesBytes == null && initialConfiguration.globalLogTag == null &&
            initialConfiguration.perTagOverrides == null
        ) {
            addDiagnostic("No complete device logging settings were available to restore; device settings were left unchanged.")
            return
        }
        val bootDeadlineNanos = System.nanoTime() + loggingRestoreBootWaitMs.coerceAtLeast(0) * NANOS_PER_MILLI
        var permissionDenied = false
        fun runAdb(arguments: List<String>, deadlineNanos: Long, outputLimit: Int = LOGGING_RESTORE_OUTPUT_LIMIT): CaptureCommandResult? {
            if (!isCurrentLoggingRestore(generation) || System.nanoTime() >= deadlineNanos) return null
            val remainingMs = ((deadlineNanos - System.nanoTime()) / NANOS_PER_MILLI).coerceAtLeast(1)
            val result = runCatching {
                tools.runAdb(
                    session.device.serial,
                    arguments,
                    timeout = Duration.ofMillis(minOf(LOGGING_RESTORE_COMMAND_TIMEOUT_MS, remainingMs)),
                    outputLimitBytes = outputLimit,
                )
            }.getOrNull()
            if (!isCurrentLoggingRestore(generation)) return null
            if (result != null && (result.timedOut || result.exitCode != 0) && isPermissionFailure(result)) {
                permissionDenied = true
            }
            return result
        }

        fun apply(arguments: List<String>, deadlineNanos: Long): Boolean {
            repeat(LOGGING_RESTORE_ATTEMPTS) { attempt ->
                if (!isCurrentLoggingRestore(generation) || System.nanoTime() >= deadlineNanos) return false
                val result = runAdb(arguments, deadlineNanos)
                if (!isCurrentLoggingRestore(generation)) return false
                if (result != null && !result.timedOut && result.exitCode == 0) return true
                if (attempt + 1 < LOGGING_RESTORE_ATTEMPTS && isCurrentLoggingRestore(generation) && System.nanoTime() < deadlineNanos) {
                    try {
                        Thread.sleep(LOGGING_RESTORE_RETRY_DELAY_MS)
                    } catch (_: InterruptedException) {
                        if (!isCurrentLoggingRestore(generation)) return false
                    }
                }
            }
            return false
        }

        fun sleepCancellable(ms: Long): Boolean {
            if (ms <= 0L) return isCurrentLoggingRestore(generation)
            return try {
                Thread.sleep(ms)
                isCurrentLoggingRestore(generation)
            } catch (_: InterruptedException) {
                isCurrentLoggingRestore(generation)
            }
        }

        // adb can become shell-responsive before Android has completed boot. Emulator host-side
        // initialization runs after that point and may resize logd buffers, so do not write the
        // saved settings until the framework reports boot completion.
        var bootCompleted = false
        while (isCurrentLoggingRestore(generation) && System.nanoTime() < bootDeadlineNanos) {
            val result = runAdb(listOf("shell", "getprop", "sys.boot_completed"), bootDeadlineNanos)
            if (result != null && !result.timedOut && result.exitCode == 0 && result.stdoutText().trim() == "1") {
                bootCompleted = true
                break
            }
            if (!sleepCancellable(LOGGING_RESTORE_BOOT_POLL_MS)) return
        }
        if (!isCurrentLoggingRestore(generation)) return
        if (!bootCompleted) {
            addDiagnostic("Device logging settings were not restored after restart because Android did not report boot completion; capture continued.")
            return
        }

        val deadlineNanos = System.nanoTime() + MAX_LOGGING_RESTORE_TIME_MS * NANOS_PER_MILLI
        var firstPass = true
        var attempted = false
        var unavailableReadDiagnosticAdded = false
        var priorExpected: DeviceLoggingConfiguration? = null
        var stableSinceNanos: Long? = null
        var settingsVerified = false

        data class Readback(
            val bufferSizesBytes: Map<String, Long>?,
            val globalLogTag: String?,
            val perTagOverrides: Map<String, String>?,
        )

        fun readback(expected: DeviceLoggingConfiguration): Readback {
            val sizes = if (expected.bufferSizesBytes != null) {
                val result = runAdb(listOf("logcat", "-b", "all", "-g"), deadlineNanos, MAX_DEVICE_GETPROP_OUTPUT_BYTES)
                result?.takeIf { !it.timedOut && it.exitCode == 0 }?.let {
                    parseCompleteLogBufferConfiguration(
                        it.stdoutText(),
                        outputWasTruncated = it.stdout.size >= MAX_DEVICE_GETPROP_OUTPUT_BYTES ||
                            it.stderr.size >= MAX_DEVICE_GETPROP_OUTPUT_BYTES,
                    )
                }
            } else null
            val global = if (expected.globalLogTag != null) {
                val result = runAdb(listOf("shell", "getprop", "log.tag"), deadlineNanos, MAX_DEVICE_GETPROP_OUTPUT_BYTES)
                result?.takeIf { !it.timedOut && it.exitCode == 0 }?.let {
                    knownGlobalLogTag(
                        it.stdoutText(),
                        outputWasTruncated = it.stdout.size >= MAX_DEVICE_GETPROP_OUTPUT_BYTES ||
                            it.stderr.size >= MAX_DEVICE_GETPROP_OUTPUT_BYTES,
                    )
                }
            } else null
            val overrides = if (expected.perTagOverrides != null) {
                val result = runAdb(listOf("shell", "getprop"), deadlineNanos, MAX_DEVICE_GETPROP_OUTPUT_BYTES)
                result?.takeIf { !it.timedOut && it.exitCode == 0 }?.let {
                    parseCompletePerTagLogOverrides(
                        it.stdoutText(),
                        outputWasTruncated = it.stdout.size >= MAX_DEVICE_GETPROP_OUTPUT_BYTES ||
                            it.stderr.size >= MAX_DEVICE_GETPROP_OUTPUT_BYTES,
                    )
                }
            } else null
            return Readback(sizes, global, overrides)
        }

        fun matches(expected: DeviceLoggingConfiguration, current: Readback): Boolean {
            val buffersMatch = expected.bufferSizesBytes == null || !restoreBufferSizes ||
                expected.bufferSizesBytes.all { (name, size) -> current.bufferSizesBytes?.get(name) == size }
            val globalMatches = expected.globalLogTag == null || current.globalLogTag == expected.globalLogTag
            val overridesMatch = expected.perTagOverrides == null || current.perTagOverrides == expected.perTagOverrides
            return buffersMatch && globalMatches && overridesMatch
        }

        fun expectedIsCurrent(expected: DeviceLoggingConfiguration): Boolean =
            isCurrentLoggingRestore(generation) && latestLoggingConfiguration(session.id) == expected

        fun applyExpected(expected: DeviceLoggingConfiguration, current: Readback?) {
            if (!expectedIsCurrent(expected) || System.nanoTime() >= deadlineNanos) return
            val needGlobal = expected.globalLogTag != null &&
                (firstPass || current?.globalLogTag != expected.globalLogTag)
            if (needGlobal) {
                if (!expectedIsCurrent(expected)) return
                val value = expected.globalLogTag
                if (value != null && value.length <= MAX_SAVED_LOG_PROPERTY_VALUE_CHARS) {
                    attempted = true
                    val script = "setprop ${shellQuoteCapture("log.tag")} ${shellQuoteCapture(value)}"
                    apply(listOf("shell", script), deadlineNanos)
                }
            }

            val savedOverrides = expected.perTagOverrides
            if (savedOverrides != null && (firstPass || current?.perTagOverrides != savedOverrides)) {
                if (!expectedIsCurrent(expected)) return
                val currentOverrides = current?.perTagOverrides
                if (currentOverrides == null) {
                    if (!unavailableReadDiagnosticAdded) {
                        addDiagnostic("Could not read current per-tag overrides during restart recovery; unknown properties were left unchanged.")
                        unavailableReadDiagnosticAdded = true
                    }
                } else {
                    val propertyCommands = mutableListOf<String>()
                    currentOverrides.keys.filter { it !in savedOverrides }.forEach { tag ->
                        if (isSafeLogTag(tag)) propertyCommands += "setprop ${shellQuoteCapture("log.tag.$tag")} '' || status=1"
                    }
                    savedOverrides.forEach { (tag, value) ->
                        if (isSafeLogTag(tag) && value.length <= MAX_SAVED_LOG_PROPERTY_VALUE_CHARS) {
                            propertyCommands += "setprop ${shellQuoteCapture("log.tag.$tag")} ${shellQuoteCapture(value)} || status=1"
                        }
                    }
                    if (propertyCommands.isNotEmpty()) {
                        attempted = true
                        val script = "status=0; ${propertyCommands.joinToString("; ")}; exit \$status"
                        apply(listOf("shell", script), deadlineNanos)
                    }
                }
            }

            val savedSizes = expected.bufferSizesBytes
            if (restoreBufferSizes && savedSizes != null && (firstPass || savedSizes.any { (name, size) -> current?.bufferSizesBytes?.get(name) != size })) {
                for ((buffer, sizeBytes) in savedSizes) {
                    if (!expectedIsCurrent(expected) || System.nanoTime() >= deadlineNanos) {
                        break
                    }
                    if (!isSafeLogBuffer(buffer) || sizeBytes !in 1..MAX_RESTORABLE_LOG_BUFFER_BYTES) {
                        continue
                    }
                    if (!firstPass && current?.bufferSizesBytes?.get(buffer) == sizeBytes) continue
                    attempted = true
                    apply(listOf("logcat", "-b", buffer, "-G", sizeBytes.toString()), deadlineNanos)
                }
            }
        }

        while (isCurrentLoggingRestore(generation) && System.nanoTime() < deadlineNanos) {
            // A launcher can save a new setting while recovery is waiting. Read the recorder's
            // current persisted target on every pass so an old restart snapshot never wins later.
            val expected = latestLoggingConfiguration(session.id) ?: break
            if (expected != priorExpected) {
                stableSinceNanos = null
                priorExpected = expected
            }
            val current = readback(expected)
            if (!isCurrentLoggingRestore(generation)) return
            if (!expectedIsCurrent(expected)) {
                stableSinceNanos = null
                continue
            }
            if (expected.perTagOverrides != null && current.perTagOverrides == null && !unavailableReadDiagnosticAdded) {
                addDiagnostic("Could not read current per-tag overrides during restart recovery; unknown properties were left unchanged.")
                unavailableReadDiagnosticAdded = true
            }
            if (matches(expected, current)) {
                val now = System.nanoTime()
                if (stableSinceNanos == null) stableSinceNanos = now
                if (now - requireNotNull(stableSinceNanos) >= loggingRestoreStabilityWindowMs.coerceAtLeast(0) * NANOS_PER_MILLI) {
                    if (!expectedIsCurrent(expected)) {
                        stableSinceNanos = null
                        continue
                    }
                    settingsVerified = true
                    break
                }
            } else {
                stableSinceNanos = null
                // A denied write cannot be repaired by retrying it every sample. The readback
                // above still gets the chance to show that the target already matches.
                if (permissionDenied) break
                applyExpected(expected, current)
                firstPass = false
            }
            if (!sleepCancellable(loggingRestoreSampleIntervalMs.coerceAtLeast(1))) return
        }
        if (!isCurrentLoggingRestore(generation)) return

        val configuration = latestLoggingConfiguration(session.id) ?: session.deviceLoggingConfiguration
        if (settingsVerified && configuration != priorExpected) settingsVerified = false
        val incompleteSnapshot = configuration == null || configuration.bufferSizesBytes == null ||
            configuration.globalLogTag == null || configuration.perTagOverrides == null
        val bufferSizesSkipped = !restoreBufferSizes && configuration?.bufferSizesBytes != null
        when {
            permissionDenied && !settingsVerified -> addDiagnostic(
                "Some device logging settings could not be restored after restart because Android denied permission. Capture continued; reapply them from Device logging or review the device's permissions.",
            )
            bufferSizesSkipped -> addDiagnostic("Buffer sizes were left unchanged after restart because buffered logs could not be fully drained; capture continued.")
            !settingsVerified -> addDiagnostic("Some device logging settings could not be restored and verified after restart. Capture continued; reapply them from Device logging if needed.")
            incompleteSnapshot -> addDiagnostic("Only device logging settings that were readable at capture start could be restored and verified; unknown values were left unchanged.")
            attempted || configuration != null -> addDiagnostic("Device logging settings were restored and verified after restart.")
            else -> addDiagnostic("No complete device logging settings were available to restore; device settings were left unchanged.")
        }
    }

    private fun latestLoggingConfiguration(sessionId: String): DeviceLoggingConfiguration? = synchronized(lock) {
        currentSession?.takeIf { it.id == sessionId }?.deviceLoggingConfiguration
    }

    private fun shellQuoteCapture(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun isSafeLogTag(tag: String): Boolean =
        tag.length in 1..MAX_LOG_TAG_NAME_CHARS && tag.all { it.isLetterOrDigit() || it in "_.$-" }

    private fun isSafeLogBuffer(buffer: String): Boolean =
        buffer.length in 1..MAX_LOG_BUFFER_NAME_CHARS && buffer.all { it.isLetterOrDigit() || it in "_.-" }

    private fun startDeviceLoggingRestore(tools: CaptureTools, session: CaptureSession, restoreBufferSizes: Boolean) {
        val worker = synchronized(lock) {
            if (!active.get()) return@synchronized null
            val old = loggingRestoreThread
            if (old?.isAlive == true) {
                addDiagnosticLocked("A previous device logging restore is still running; this restart's settings will be retried on the next reconnect.")
                return@synchronized null
            }
            val generation = loggingRestoreGeneration.incrementAndGet()
            val worker = Thread({
                try {
                    restoreDeviceLoggingConfiguration(tools, session, restoreBufferSizes, generation)
                } finally {
                    if (isCurrentLoggingRestore(generation)) {
                        mutableDeviceLoggingRestoreEvents.tryEmit(DeviceLoggingRestoreEvent(session.device.serial))
                    }
                    synchronized(lock) {
                        if (loggingRestoreThread === Thread.currentThread()) loggingRestoreThread = null
                    }
                }
            }, "capture-log-config-restore-${session.id}").apply { isDaemon = true }
            loggingRestoreThread = worker
            worker
        }
        // Start after releasing the recorder lock so even a very fast restore cannot publish its
        // completion while another thread is still holding that lock on the start path.
        worker?.start()
    }

    private fun isCurrentLoggingRestore(generation: Long): Boolean =
        active.get() && loggingRestoreGeneration.get() == generation

    /** A previous boot's restore must be stopped before draining or configuring a later boot. */
    private fun cancelDeviceLoggingRestore() {
        val worker = synchronized(lock) {
            loggingRestoreGeneration.incrementAndGet()
            loggingRestoreThread
        } ?: return
        if (worker === Thread.currentThread()) return
        worker.interrupt()
        worker.join(LOGGING_RESTORE_JOIN_TIMEOUT_MS)
    }

    private data class DeviceReadiness(
        val ready: Boolean,
        val bootId: String? = null,
        val uptimeMs: Long? = null,
        val deviceWallClockMsOfYear: Long? = null,
    )

    private data class BufferedReconnectDrain(
        val complete: Boolean,
        val diagnostic: String? = null,
        val bufferedTimestampedRows: Int = 0,
        val appendedTimestampedRows: Int = 0,
        val fatalConfigurationFailure: Boolean = false,
        val replayOccurrenceBudget: Map<ReplayRowFingerprint, Int>? = null,
    )

    private data class DeviceLogLine(val text: String, val raw: ByteArray)

    private data class ReplayRowFingerprint(val a: Long, val b: Long, val c: Long, val d: Long)

    private class LogcatReaderState(
        private val replayHistoryByteLimit: Int,
        private val replayHistoryRowLimit: Int,
    ) {
        var lastDeviceTimestamp: String? = null
            private set
        private val latestTimestampOccurrences = linkedMapOf<String, Int>()
        private var latestTimestampOccurrenceBytes = 0
        private var boundaryTimestamp: String? = null
        private var boundaryOccurrences: MutableMap<String, Int> = mutableMapOf()
        private var deduplicateBoundary = false
        private val replayDigest = MessageDigest.getInstance("SHA-256")
        private val replayHistory = ArrayDeque<ReplayHistoryRow>()
        private val replayHistoryOccurrences = linkedMapOf<ReplayRowFingerprint, Int>()
        private var replayHistoryBytes = 0
        var replayHistoryWasTruncated: Boolean = false
            private set
        private var replayOccurrences: MutableMap<ReplayRowFingerprint, Int>? = null

        fun beginBoundaryDeduplication() {
            replayOccurrences = null
            boundaryTimestamp = lastDeviceTimestamp
            boundaryOccurrences = latestTimestampOccurrences.toMutableMap()
            deduplicateBoundary = boundaryTimestamp != null
        }

        fun resetForNewBoot() {
            lastDeviceTimestamp = null
            latestTimestampOccurrences.clear()
            latestTimestampOccurrenceBytes = 0
            clearReplayHistory()
            clearBoundaryDeduplication()
        }

        fun resetForEmptyRestartBuffer() {
            lastDeviceTimestamp = null
            latestTimestampOccurrences.clear()
            latestTimestampOccurrenceBytes = 0
            clearReplayHistory()
            clearBoundaryDeduplication()
        }

        /** The timestamp from before a same-identity uptime rewind is unsafe for later -T use.
         * Keep row history for subsequent overlap matching but wait for a new row to establish a
         * timestamp cursor. */
        fun clearTimestampCursor() {
            lastDeviceTimestamp = null
            latestTimestampOccurrences.clear()
            latestTimestampOccurrenceBytes = 0
            clearBoundaryDeduplication()
        }

        /** Suppress rows already observed in this capture while a same-identity QuickBoot snapshot
         * is replayed. Fingerprint counts are independent of buffer ordering, while the FIFO keeps
         * memory bounded even for long captures. */
        fun beginReplayOccurrenceDeduplication(occurrenceBudget: Map<ReplayRowFingerprint, Int>? = null) {
            clearBoundaryDeduplication()
            replayOccurrences = (occurrenceBudget ?: replayHistoryOccurrences).toMutableMap().takeIf { it.isNotEmpty() }
        }

        private fun clearBoundaryDeduplication() {
            boundaryTimestamp = null
            boundaryOccurrences.clear()
            deduplicateBoundary = false
        }

        /** Remove exact rows that the recorder already captured before the reconnect. The device
         * can group rows by log buffer in a different order from the live stream, so occurrence
         * counts provide a stable overlap even when no ordered suffix matches. Extra identical
         * occurrences in the current ring remain new rows. */
        fun removeBufferedReplayOverlap(
            lines: List<DeviceLogLine>,
            priorOccurrences: Map<ReplayRowFingerprint, Int> = replayHistoryOccurrences,
        ): List<DeviceLogLine> {
            val remaining = priorOccurrences.toMutableMap()
            boundaryTimestamp = null
            boundaryOccurrences.clear()
            deduplicateBoundary = false
            if (lines.isEmpty() || remaining.isEmpty()) return lines
            val output = ArrayList<DeviceLogLine>(lines.size)
            for (line in lines) {
                val fingerprint = fingerprintIfDeviceRow(line.text)
                if (fingerprint == null || !decrementIfPresent(remaining, fingerprint)) output += line
            }
            return output
        }

        fun replayHistoryOccurrencesSnapshot(): Map<ReplayRowFingerprint, Int> = replayHistoryOccurrences.toMap()

        /** Count exact timestamped drain rows with a hard key bound; null means the bounded map
         * could not represent every candidate and the caller must report that fallback limit. */
        fun countReplayOccurrences(
            lines: List<DeviceLogLine>,
            maxKeys: Int,
        ): Map<ReplayRowFingerprint, Int>? {
            val counts = HashMap<ReplayRowFingerprint, Int>()
            for (line in lines) {
                val fingerprint = fingerprintIfDeviceRow(line.text) ?: continue
                if (fingerprint !in counts && counts.size >= maxKeys) return null
                counts[fingerprint] = (counts[fingerprint] ?: 0) + 1
            }
            return counts
        }

        fun mergeReplayOccurrenceCounts(
            first: Map<ReplayRowFingerprint, Int>,
            second: Map<ReplayRowFingerprint, Int>,
        ): Map<ReplayRowFingerprint, Int> {
            val merged = HashMap(first)
            for ((fingerprint, count) in second) {
                if (count > (merged[fingerprint] ?: 0)) merged[fingerprint] = count
            }
            return merged
        }

        fun addReplayOccurrenceCounts(
            first: Map<ReplayRowFingerprint, Int>,
            second: Map<ReplayRowFingerprint, Int>,
        ): Map<ReplayRowFingerprint, Int>? {
            val added = HashMap(first)
            for ((fingerprint, count) in second) {
                if (fingerprint !in added && added.size >= MAX_REPLAY_CANDIDATE_KEYS) return null
                added[fingerprint] = (added[fingerprint] ?: 0) + count
            }
            return added
        }

        fun fingerprintIfDeviceRow(line: String): ReplayRowFingerprint? =
            if (timestampOf(line) == null) null else fingerprint(line)

        private fun <K> decrementIfPresent(remaining: MutableMap<K, Int>, key: K): Boolean {
            val count = remaining[key] ?: return false
            if (count <= 1) remaining.remove(key) else remaining[key] = count - 1
            return true
        }

        private fun timestampOf(line: String): String? = LOGCAT_THREADTIME_PREFIX.find(line)?.value

        private fun timestampBefore(value: String, cursor: String): Boolean = compareTimestamp(value, cursor) < 0

        private fun timestampAfter(value: String, cursor: String): Boolean = compareTimestamp(value, cursor) > 0

        /** Logcat threadtime omits the year. Compare around the cursor using the shortest distance
         * on a leap-year calendar so December rows sort before a January cursor, and January rows
         * sort after a December cursor. */
        private fun compareTimestamp(value: String, cursor: String): Int {
            val valueMillis = timestampMillis(value) ?: return value.compareTo(cursor)
            val cursorMillis = timestampMillis(cursor) ?: return value.compareTo(cursor)
            val yearMillis = 366L * MILLIS_PER_DAY
            var delta = valueMillis - cursorMillis
            if (delta > yearMillis / 2) delta -= yearMillis
            if (delta < -yearMillis / 2) delta += yearMillis
            return delta.compareTo(0L)
        }

        private fun timestampMillis(value: String): Long? = runCatching {
            val prefix = LOGCAT_THREADTIME_PREFIX.find(value)?.value ?: return null
            val month = prefix.substring(0, 2).toInt()
            val day = prefix.substring(3, 5).toInt()
            val hour = prefix.substring(6, 8).toInt()
            val minute = prefix.substring(9, 11).toInt()
            val second = prefix.substring(12, 14).toInt()
            val millis = prefix.substring(15, 18).toInt()
            val dayOfYear = java.time.LocalDate.of(2000, month, day).dayOfYear - 1
            dayOfYear * MILLIS_PER_DAY + hour * 3_600_000L + minute * 60_000L + second * 1_000L + millis
        }.getOrNull()

        private fun fingerprint(line: String): ReplayRowFingerprint {
            val digest = replayDigest.digest(line.toByteArray(Charsets.UTF_8))
            val buffer = ByteBuffer.wrap(digest)
            return ReplayRowFingerprint(buffer.long, buffer.long, buffer.long, buffer.long)
        }

        private fun clearReplayHistory() {
            replayHistory.clear()
            replayHistoryOccurrences.clear()
            replayHistoryBytes = 0
            replayHistoryWasTruncated = false
            replayOccurrences = null
        }

        fun accept(line: DeviceLogLine): List<DeviceLogLine> {
            val replay = replayOccurrences
            if (replay != null) {
                val fingerprint = fingerprintIfDeviceRow(line.text)
                if (fingerprint != null && decrementIfPresent(replay, fingerprint)) {
                    if (replay.isEmpty()) replayOccurrences = null
                    return emptyList()
                }
                return listOf(line)
            }
            if (!deduplicateBoundary) return listOf(line)
            val cursor = boundaryTimestamp ?: run {
                deduplicateBoundary = false
                return listOf(line)
            }
            val timestamp = timestampOf(line.text) ?: return listOf(line)
            if (timestampBefore(timestamp, cursor)) return emptyList()
            if (timestamp == cursor && decrementIfPresent(boundaryOccurrences, line.text)) return emptyList()
            deduplicateBoundary = false
            return listOf(line)
        }

        fun flushPending(): List<DeviceLogLine> = emptyList()

        fun record(line: String) {
            if (deduplicateBoundary) return
            val timestamp = timestampOf(line) ?: return
            val byteLength = line.toByteArray(Charsets.UTF_8).size
            if (byteLength <= replayHistoryByteLimit && replayHistoryByteLimit > 0 && replayHistoryRowLimit > 0) {
                while (
                    replayHistory.isNotEmpty() &&
                    (replayHistoryBytes + byteLength > replayHistoryByteLimit ||
                        replayHistory.size >= replayHistoryRowLimit)
                ) {
                    val evicted = replayHistory.removeFirst()
                    replayHistoryBytes -= evicted.lengthBytes
                    replayHistoryWasTruncated = true
                    val count = replayHistoryOccurrences.getValue(evicted.fingerprint)
                    if (count <= 1) replayHistoryOccurrences.remove(evicted.fingerprint)
                    else replayHistoryOccurrences[evicted.fingerprint] = count - 1
                }
                val fingerprint = fingerprint(line)
                replayHistory.addLast(ReplayHistoryRow(fingerprint, byteLength))
                replayHistoryOccurrences[fingerprint] = (replayHistoryOccurrences[fingerprint] ?: 0) + 1
                replayHistoryBytes += byteLength
            } else {
                // A single oversized row cannot enter the FIFO; record that the index is partial
                // so same-ID recovery uses the pre-gap candidate scan for any matching drain row.
                replayHistoryWasTruncated = true
            }
            if (timestamp != lastDeviceTimestamp) {
                latestTimestampOccurrences.clear()
                latestTimestampOccurrenceBytes = 0
                lastDeviceTimestamp = timestamp
            }
            if (timestamp == lastDeviceTimestamp) {
                val existing = latestTimestampOccurrences[line]
                if (existing != null) {
                    latestTimestampOccurrences[line] = existing + 1
                } else if (
                    latestTimestampOccurrences.size < MAX_SAME_TIMESTAMP_OCCURRENCE_KEYS &&
                    latestTimestampOccurrenceBytes + line.length <= MAX_SAME_TIMESTAMP_OCCURRENCE_CHARS
                ) {
                    latestTimestampOccurrences[line] = 1
                    latestTimestampOccurrenceBytes += line.length
                }
            }
        }

        private data class ReplayHistoryRow(val fingerprint: ReplayRowFingerprint, val lengthBytes: Int)
    }

    private fun logcatArguments(
        settings: CaptureSettings,
        resumeTimestamp: String? = null,
        retainNewBootBuffer: Boolean = false,
    ): List<String> = buildList {
        add("logcat")
        addAll(listOf("-v", "threadtime"))
        addAll(settings.logcatBufferArgs())
        when {
            resumeTimestamp != null -> addAll(listOf("-T", resumeTimestamp))
            !retainNewBootBuffer && !settings.includeBufferedLogs -> addAll(listOf("-T", "1"))
        }
    }

    private fun reconnectDelay(attempt: Int): Long {
        val multiplier = 1L shl attempt.coerceIn(0, 5)
        return (reconnectBaseDelayMs.coerceAtLeast(1) * multiplier).coerceAtMost(MAX_DEVICE_RECONNECT_DELAY_MS)
    }

    private fun awaitReconnectRetry(attempt: Int): Boolean {
        try {
            synchronized(reconnectWaitMonitor) {
                if (active.get()) reconnectWaitMonitor.wait(reconnectDelay(attempt))
            }
        } catch (_: InterruptedException) {
            if (!active.get()) return false
        }
        return active.get()
    }

    private fun isFatalLogcatDiagnostic(line: String): Boolean {
        val normalized = line.lowercase()
        return listOf("unknown buffer", "not a valid buffer", "unknown option", "invalid option", "usage:", "failed to parse").any(normalized::contains)
    }

    private fun probeDeviceReadiness(tools: CaptureTools, serial: String): DeviceReadiness {
        val state = runCatching { tools.runAdb(serial, listOf("get-state"), Duration.ofSeconds(3)) }.getOrNull()
        if (state == null || state.timedOut || state.exitCode != 0 || state.stdoutText().trim() != "device") {
            return DeviceReadiness(false)
        }
        val shell = runCatching {
            tools.runAdb(serial, listOf("shell", "echo", "capture-ready"), Duration.ofSeconds(3), outputLimitBytes = 64)
        }.getOrNull()
        if (shell == null || shell.timedOut || shell.exitCode != 0 || !shell.stdoutText().contains("capture-ready")) {
            return DeviceReadiness(false)
        }
        return DeviceReadiness(
            true,
            readBootId(tools, serial),
            readBootUptimeMs(tools, serial),
            readDeviceWallClockMsOfYear(tools, serial),
        )
    }

    private fun readBootId(tools: CaptureTools, serial: String): String? = runCatching {
        val result = tools.runAdb(
            serial,
            listOf("shell", "cat", "/proc/sys/kernel/random/boot_id"),
            Duration.ofSeconds(3),
            outputLimitBytes = 256,
        )
        result.stdoutText().trim().takeIf { !result.timedOut && result.exitCode == 0 && it.isNotBlank() }
    }.getOrNull()

    private fun readBootUptimeMs(tools: CaptureTools, serial: String): Long? = runCatching {
        val result = tools.runAdb(
            serial,
            listOf("shell", "cat", "/proc/uptime"),
            Duration.ofSeconds(2),
            outputLimitBytes = 128,
        )
        if (result.timedOut || result.exitCode != 0) return@runCatching null
        val seconds = result.stdoutText().trim().substringBefore(' ').toDoubleOrNull() ?: return@runCatching null
        (seconds * 1_000.0).toLong().takeIf { it >= 0 }
    }.getOrNull()

    /** Reads the device's current clock separately from host/session elapsed time. Emulator
     * QuickBoot can restore the Android wall clock while keeping both serial and boot id stable. */
    private fun readDeviceWallClockMsOfYear(tools: CaptureTools, serial: String): Long? = runCatching {
        val result = tools.runAdb(
            serial,
            listOf("shell", "date", "-u", "+%m-%dT%H:%M:%S"),
            Duration.ofSeconds(2),
            outputLimitBytes = 128,
        )
        if (result.timedOut || result.exitCode != 0) return@runCatching null
        val match = DEVICE_WALL_CLOCK_PATTERN.matchEntire(result.stdoutText().trim()) ?: return@runCatching null
        val month = match.groupValues[1].toInt()
        val day = match.groupValues[2].toInt()
        val hour = match.groupValues[3].toInt()
        val minute = match.groupValues[4].toInt()
        val second = match.groupValues[5].toInt()
        val dayOfYear = java.time.LocalDate.of(2000, month, day).dayOfYear - 1
        dayOfYear * MILLIS_PER_DAY + hour * 3_600_000L + minute * 60_000L + second * 1_000L
    }.getOrNull()

    /** Detects same-identity emulator QuickBoot from an uptime reset or a substantial device
     * wall-clock rollback. Ordinary reconnects keep using the timestamp cursor. Availability's
     * public rebootConfirmed field remains tied to an actual boot-id change. */
    private fun sameIdentityRestartEvidence(readiness: DeviceReadiness): Boolean {
        val id = readiness.bootId ?: return false
        if (lastKnownBootId != id) return false
        val actualUptime = readiness.uptimeMs
        val previousUptime = lastKnownBootUptimeMs
        if (actualUptime != null && previousUptime != null) {
            val expectedUptime = previousUptime + (elapsedNow() - bootUptimeObservedElapsedMs).coerceAtLeast(0)
            if (actualUptime + QUICKBOOT_UPTIME_REWIND_TOLERANCE_MS < expectedUptime) return true
        }
        val actualWallClock = readiness.deviceWallClockMsOfYear ?: return false
        val previousWallClock = lastKnownDeviceWallClockMsOfYear ?: return false
        val expectedWallClock = previousWallClock + (elapsedNow() - deviceWallClockObservedElapsedMs).coerceAtLeast(0)
        return circularWallClockDelta(actualWallClock, expectedWallClock) < -QUICKBOOT_UPTIME_REWIND_TOLERANCE_MS
    }

    private fun circularWallClockDelta(actual: Long, expected: Long): Long {
        val yearMillis = 366L * MILLIS_PER_DAY
        var delta = actual - expected
        if (delta > yearMillis / 2) delta -= yearMillis
        if (delta < -yearMillis / 2) delta += yearMillis
        return delta
    }

    private fun recordDeviceUnavailable(device: CaptureDevice, elapsed: Long): Boolean {
        var storageFailure: String? = null
        synchronized(lock) {
            if (!active.get()) return false
            val old = currentSession ?: return false
            if (old.availabilityIntervals.lastOrNull()?.endElapsedMs == null && old.availabilityIntervals.isNotEmpty()) return true
            val updated = old.copy(
                elapsedMs = maxOf(old.elapsedMs, elapsed),
                availabilityIntervals = old.availabilityIntervals +
                    CaptureAvailabilityInterval(startElapsedMs = elapsed, bootIdBefore = lastKnownBootId),
            )
            currentSession = updated
            mutableSelectedSession.value = updated
            try {
                persistSession(updated)
            } catch (failure: IOException) {
                storageFailure = failure.message ?: "Capture session metadata could not be saved"
                recordStopStorageFailureLocked(failure)
            } catch (failure: RuntimeException) {
                storageFailure = failure.message ?: "Capture session metadata could not be saved"
                recordStopStorageFailureLocked(failure)
            }
            addDiagnosticLocked("Device unavailable; waiting for the original device ${device.serial}.")
            if (device.wireless) {
                addDiagnosticLocked("Wireless reconnect stays on ${device.serial}; a changed endpoint needs verified pairing to follow safely.")
            }
            if (old.settings.writeDeviceAvailabilityMarkers) {
                storageFailure = appendAvailabilityMarkerLocked(elapsed, "Device unavailable; elapsedMs=$elapsed") ?: storageFailure
                storageFailure?.let { recordStopStorageFailureLocked(IOException(it)) }
            }
            publishLocked(RecorderState.RECORDING, force = true)
        }
        storageFailure?.let {
            interrupt("Capture storage failed while recording device availability: $it")
            return false
        }
        return true
    }

    private fun recordDeviceReconnected(bootId: String?): Boolean {
        val elapsed = elapsedNow()
        var storageFailure: String? = null
        synchronized(lock) {
            if (!active.get()) return false
            val old = currentSession ?: return false
            val openIndex = old.availabilityIntervals.indexOfLast { it.endElapsedMs == null }
            if (openIndex < 0) {
                lastKnownBootId = bootId ?: lastKnownBootId
                return true
            }
            val open = old.availabilityIntervals[openIndex]
            val reboot = if (open.bootIdBefore != null && bootId != null) open.bootIdBefore != bootId else null
            val intervals = old.availabilityIntervals.toMutableList().also {
                it[openIndex] = open.copy(endElapsedMs = elapsed, bootIdAfter = bootId, rebootConfirmed = reboot)
            }
            val updated = old.copy(elapsedMs = maxOf(old.elapsedMs, elapsed), availabilityIntervals = intervals)
            currentSession = updated
            mutableSelectedSession.value = updated
            try {
                persistSession(updated)
            } catch (failure: IOException) {
                storageFailure = failure.message ?: "Capture session metadata could not be saved"
                recordStopStorageFailureLocked(failure)
            } catch (failure: RuntimeException) {
                storageFailure = failure.message ?: "Capture session metadata could not be saved"
                recordStopStorageFailureLocked(failure)
            }
            val duration = (elapsed - open.startElapsedMs).coerceAtLeast(0)
            val rebootText = when (reboot) {
                true -> "; reboot confirmed"
                false -> "; same boot"
                null -> ""
            }
            addDiagnosticLocked("Device reconnected after ${formatAvailabilityDuration(duration)}$rebootText.")
            if (old.settings.writeDeviceAvailabilityMarkers) {
                storageFailure = appendAvailabilityMarkerLocked(
                    elapsed,
                    "Device reconnected; unavailableMs=$duration; reboot=${reboot?.toString() ?: "unknown"}",
                ) ?: storageFailure
                storageFailure?.let { recordStopStorageFailureLocked(IOException(it)) }
            }
            lastKnownBootId = bootId ?: lastKnownBootId
            publishLocked(RecorderState.RECORDING, force = true)
        }
        storageFailure?.let {
            interrupt("Capture storage failed while recording device availability: $it")
            return false
        }
        return true
    }

    /** Called under lock. The synthetic line follows logcat threadtime and receives a normal index row. */
    private fun appendAvailabilityMarkerLocked(elapsed: Long, message: String): String? = try {
        val session = requireNotNull(currentSession)
        val timestamp = AVAILABILITY_LOG_TIMESTAMP.format(Instant.ofEpochMilli(session.startedEpochMs + elapsed))
        val raw = "$timestamp  0  0 W IndagiumCapture: $message\n".toByteArray(Charsets.UTF_8)
        enforceStorageLimitsLocked(session, elapsed, raw.size)
        val offset = logBytes
        requireNotNull(logOutput).write(raw)
        logBytes += raw.size
        val ordinal = ++rowOrdinal
        requireNotNull(indexOutput).write(
            (indexRecordJson(CaptureLogIndexRecord(offset, raw.size, elapsed, ordinal)) + "\n").toByteArray(Charsets.UTF_8),
        )
        null
    } catch (failure: IOException) {
        failure.message ?: "Capture storage failed"
    } catch (failure: RuntimeException) {
        failure.message ?: "Capture storage failed"
    }

    private fun formatAvailabilityDuration(milliseconds: Long): String =
        if (milliseconds < 1_000) "${milliseconds}ms"
        else String.format(java.util.Locale.ROOT, "%.1fs", milliseconds / 1_000.0)

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
        var restoreToJoin: Thread? = null
        var startup: CountDownLatch? = null
        val calledFromStarter = synchronized(lock) { Thread.currentThread() === starterThread }
        var liveDirectory: File? = null
        val completion = synchronized(lock) {
            if (starting) startCancellationRequested = true
            if (active.getAndSet(false)) {
                ownsStop = true
                loggingRestoreGeneration.incrementAndGet()
                liveDirectory = currentSession?.directory
                reason?.let(::addDiagnosticLocked)
                publishLocked(RecorderState.STOPPING, force = true)
                processes = listOfNotNull(logProcess, mirrorProcess)
                restoreToJoin = loggingRestoreThread
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
        synchronized(reconnectWaitMonitor) { reconnectWaitMonitor.notifyAll() }
        val current = Thread.currentThread()
        restoreToJoin?.takeIf { it !== current }?.let { restoreThread ->
            restoreThread.interrupt()
            restoreThread.join(LOGGING_RESTORE_JOIN_TIMEOUT_MS)
        }
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
                var finished = old.copy(
                    elapsedMs = maxOf(old.elapsedMs, elapsedNow()),
                    status = if (storageReason != null) CaptureStatus.INTERRUPTED else status,
                    interruptions = interruptionList,
                )
                currentSession = finished
                mutableSelectedSession.value = finished
                guarded { persistSession(finished) }
                if (storageFailure != null && finished.status == CaptureStatus.STOPPED) {
                    val failureText = storageFailure ?: "Capture storage failed"
                    finished = finished.copy(
                        status = CaptureStatus.INTERRUPTED,
                        interruptions = (finished.interruptions + "Capture storage failed while stopping: $failureText").takeLast(MAX_DIAGNOSTICS),
                    )
                    currentSession = finished
                    mutableSelectedSession.value = finished
                    guarded { persistSession(finished) }
                }
                publishLocked(
                    if (finished.status == CaptureStatus.STOPPED) RecorderState.STOPPED else RecorderState.INTERRUPTED,
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

/** Reads no bytes after the fixed pre-gap file boundary used by a QuickBoot fallback scan. */
private class BoundedInputStream(input: InputStream, limitBytes: Long) : FilterInputStream(input) {
    private var remaining = limitBytes.coerceAtLeast(0L)

    override fun read(): Int {
        if (remaining <= 0L) return -1
        val value = super.read()
        if (value >= 0) remaining--
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0L) return -1
        val allowed = minOf(length.toLong(), remaining).toInt()
        val count = super.read(buffer, offset, allowed)
        if (count > 0) remaining -= count
        return count
    }
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
    put("availabilityIntervals", buildJsonArray {
        session.availabilityIntervals.forEach { interval ->
            add(buildJsonObject {
                put("startElapsedMs", interval.startElapsedMs)
                interval.endElapsedMs?.let { put("endElapsedMs", it) }
                interval.bootIdBefore?.let { put("bootIdBefore", it) }
                interval.bootIdAfter?.let { put("bootIdAfter", it) }
                interval.rebootConfirmed?.let { put("rebootConfirmed", it) }
            })
        }
    })
    session.deviceLoggingConfiguration?.let { configuration ->
        put("deviceLoggingConfiguration", buildJsonObject {
            configuration.bufferSizesBytes?.let { sizes ->
                put("bufferSizesBytes", buildJsonObject {
                    sizes.forEach { (buffer, bytes) -> put(buffer, bytes) }
                })
            }
            configuration.globalLogTag?.let { put("globalLogTag", it) }
            configuration.perTagOverrides?.let { overrides ->
                put("perTagOverrides", buildJsonObject {
                    overrides.forEach { (tag, value) -> put(tag, value) }
                })
            }
        })
    }
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
        availabilityIntervals = root["availabilityIntervals"]?.jsonArray.orEmpty().mapNotNull { element ->
            runCatching {
                val interval = element.jsonObject
                CaptureAvailabilityInterval(
                    startElapsedMs = interval.getValue("startElapsedMs").jsonPrimitive.long,
                    endElapsedMs = interval["endElapsedMs"]?.jsonPrimitive?.longOrNull,
                    bootIdBefore = interval["bootIdBefore"]?.jsonPrimitive?.contentOrNull,
                    bootIdAfter = interval["bootIdAfter"]?.jsonPrimitive?.contentOrNull,
                    rebootConfirmed = interval["rebootConfirmed"]?.jsonPrimitive?.booleanOrNull,
                )
            }.getOrNull()
        },
        deviceLoggingConfiguration = root["deviceLoggingConfiguration"]?.let { element ->
            runCatching {
                val config = element.jsonObject
                DeviceLoggingConfiguration(
                    bufferSizesBytes = config["bufferSizesBytes"]?.let { value -> runCatching {
                        val values = value.jsonObject
                        require(values.size <= MAX_PERSISTED_LOG_CONFIG_ITEMS)
                        values.map { (buffer, bytes) ->
                            val parsed = bytes.jsonPrimitive.longOrNull ?: error("Invalid buffer size")
                            require(buffer.matches(Regex("^[A-Za-z0-9_.-]{1,64}$")) && parsed in 1..MAX_RESTORABLE_LOG_BUFFER_BYTES)
                            buffer to parsed
                        }.toMap()
                    }.getOrNull()
                    },
                    globalLogTag = config["globalLogTag"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length <= 512 },
                    perTagOverrides = config["perTagOverrides"]?.let { value -> runCatching {
                        val values = value.jsonObject
                        require(values.size <= MAX_PERSISTED_LOG_CONFIG_ITEMS)
                        values.map { (tag, entry) ->
                            val text = entry.jsonPrimitive.contentOrNull ?: error("Invalid property value")
                            require(tag.matches(Regex("^[A-Za-z0-9_.$-]{1,128}$")) && text.length <= 512 && text.isNotBlank())
                            tag to text
                        }.toMap()
                    }.getOrNull()
                    },
                )
            }.getOrNull()
        },
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

const val CAPTURE_SESSION_FILE_NAME = "session.json"
private const val SESSION_FILE = CAPTURE_SESSION_FILE_NAME
private const val PREVIEW_PUBLISH_INTERVAL_MS = 250L
private const val SPACE_CHECK_INTERVAL_MS = 1_000L
private const val SESSION_METADATA_PERSIST_INTERVAL_MS = 5_000L
private const val MAX_DIAGNOSTICS = 50
// Exact replay fingerprints are retained for the most recent 32 MiB of device rows. That covers
// reconnect snapshots without retaining the session's potentially multi-gigabyte log in memory.
private const val MAX_REPLAY_HISTORY_BYTES = 32 * 1024 * 1024
private const val MAX_REPLAY_HISTORY_ROWS = 262_144
private const val MAX_REPLAY_CANDIDATE_KEYS = 262_144
private const val MAX_SAME_TIMESTAMP_OCCURRENCE_KEYS = 4_096
private const val MAX_SAME_TIMESTAMP_OCCURRENCE_CHARS = 512 * 1024
private const val MAX_RECONNECT_BUFFER_BYTES = 32 * 1024 * 1024
private const val MAX_RECONNECT_BUFFER_BYTES_WITH_SIZES = 128 * 1024 * 1024
private const val MAX_DEVICE_GETPROP_OUTPUT_BYTES = 256 * 1024
private const val QUICKBOOT_UPTIME_REWIND_TOLERANCE_MS = 5_000L
private const val MAX_LOG_TAG_NAME_CHARS = 128
private const val MAX_LOG_BUFFER_NAME_CHARS = 64
private const val MAX_SAVED_LOG_PROPERTY_VALUE_CHARS = 512
private const val MAX_PERSISTED_LOG_CONFIG_ITEMS = 256
private const val MAX_RESTORABLE_LOG_BUFFER_BYTES = 1024L * 1024L * 1024L
private const val MAX_LOGGING_RESTORE_TIME_MS = 30_000L
private const val LOGGING_RESTORE_BOOT_WAIT_MS = 120_000L
private const val LOGGING_RESTORE_BOOT_POLL_MS = 250L
private const val LOGGING_RESTORE_STABILITY_WINDOW_MS = 10_000L
private const val LOGGING_RESTORE_SAMPLE_INTERVAL_MS = 500L
private const val LOGGING_RESTORE_COMMAND_TIMEOUT_MS = 2_000L
private const val LOGGING_RESTORE_OUTPUT_LIMIT = 4 * 1024
private const val LOGGING_RESTORE_ATTEMPTS = 2
private const val LOGGING_RESTORE_RETRY_DELAY_MS = 200L
private const val LOGGING_RESTORE_JOIN_TIMEOUT_MS = 4_000L
private const val LOGCAT_DIAGNOSTIC_JOIN_MS = 250L
private const val MAX_DIAGNOSTIC_CHARS = 4_096
private const val MAX_SCREENSHOT_BYTES = 64 * 1024 * 1024
private const val MAX_DEVICE_RECONNECT_DELAY_MS = 5_000L
private val AVAILABILITY_LOG_TIMESTAMP =
    DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
private val LOGCAT_THREADTIME_PREFIX = Regex("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}")
private val DEVICE_WALL_CLOCK_PATTERN = Regex("^(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})$")
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
private const val MILLIS_PER_DAY = 86_400_000L

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
