@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.indagium.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.indagium.capture.CaptureTools
import com.indagium.capture.ProcessBuilderCaptureRunner
import com.indagium.capture.mirror.AdbScrcpyTransport
import com.indagium.capture.mirror.DirectH264Decoder
import com.indagium.capture.mirror.EmbeddedDeviceSession
import com.indagium.capture.mirror.EmbeddedMirrorRuntime
import com.indagium.capture.mirror.EmbeddedMirrorSnapshot
import com.indagium.capture.mirror.EmbeddedMirrorState
import com.indagium.capture.mirror.H264Decoder
import com.indagium.capture.mirror.JavaCvH264Decoder
import com.indagium.capture.mirror.MacVideoToolboxMirrorDecoder
import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorCoordinateMapper
import com.indagium.capture.mirror.MirrorFrame
import com.indagium.capture.mirror.MirrorKeyAction
import com.indagium.capture.mirror.MirrorStreamOptions
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.capture.mirror.ScrcpyControlEncoder
import com.indagium.debug.AppLogger
import com.indagium.model.LogTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyAdapter
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelListener
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.awt.event.KeyEvent as AwtKeyEvent

/** Only a live capture tab may keep its detached mirror window after navigation. */
internal fun activeDetachedEmbeddedMirrorTabs(tabs: List<LogTab>, detachedTabIds: Set<String>): List<LogTab> =
    tabs.filter { it.id in detachedTabIds && it.captureSessionId != null }

/**
 * UI-owned adapter around either a standalone [EmbeddedMirrorRuntime] (its own embedded scrcpy
 * server — used only when the capture isn't recording video) or, when it is, a shared view onto
 * the *recording's own* [EmbeddedDeviceSession] (`attachDecoder`/`detachDecoder`/`sendControl` —
 * see [MirrorBackend.SharedRecordingSession]) so mirroring never opens a second device encoder.
 * Either way this class turns connection/frame state into one StateFlow and keeps the device/
 * control API out of CaptureStrip's layout code.
 *
 * **Threading invariant (the Disconnect -> Connect freeze).** The EDT/UI thread must never wait on
 * a mirror lifecycle lock, and no mirror lifecycle lock may be held while waiting for the EDT.
 * Tearing a native surface down needs the EDT (see [EmbeddedMirrorMacSurface.close]); when a
 * Disconnect on an IO thread held `SharedRecordingSession.lifecycleLock` across that, a Connect
 * clicked on the EDT blocked on the same lock — each waited for the other, permanently. Therefore:
 *  - EVERY lifecycle transition (start, stop, close, live-audio toggle) runs, strictly in order, on
 *    this handle's own single-thread lifecycle lane. The blocking [start]/[stop]/[close] are just
 *    `request*` + a wait and are only for non-UI threads (IO pool, tests); nothing reaches the
 *    backend around the lane, so a start can never interleave with, or run after, a close.
 *  - UI-reachable code uses the `request*` methods: they return immediately, so a slow Disconnect
 *    can never overlap a Connect and the caller never blocks. Rapid click sequences collapse: a
 *    Disconnect/Connect request is skipped when a later Disconnect/Connect request exists, so the
 *    state of the LAST request wins — except that a Connect which supersedes a pending Disconnect
 *    becomes a restart (detach, then re-attach), because the user's Disconnect -> Connect is a
 *    recovery gesture that must really tear down and rebuild the stream.
 *  - A native surface close waits for the EDT only for a bounded time ([runOnEdtBounded]).
 *  - **Close ordering.** [requestClose] first tears the native surfaces down (the only EDT-bound
 *    work): inline when called on the EDT (so quitting or closing a tab never queues EDT work behind
 *    a thread the EDT is about to wait for, and the surface is gone before its SwingPanel is
 *    disposed), or via a non-blocking `invokeLater` from another thread. The lane then runs the
 *    remaining, EDT-free part (decoder detach, adb/scrcpy cleanup). The returned future completes
 *    when that is done, so callers needing "mirror closed, then stop the recorder" chain on it.
 */
internal class EmbeddedMirrorHandle private constructor(
    private val backend: MirrorBackend,
) : Closeable {
    private val _snapshot = MutableStateFlow(backend.snapshot())
    val snapshot: StateFlow<EmbeddedMirrorSnapshot> = _snapshot

    // One worker per handle, created lazily on first use and released after LIFECYCLE_IDLE_MS (a
    // handle that never sees a lifecycle call never owns a thread). FIFO + a single thread is what
    // serializes start/stop/close for this tab without any lock the UI thread could wait on.
    private val lifecycleLane = ThreadPoolExecutor(
        1,
        1,
        LIFECYCLE_IDLE_MS,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
    ) { task -> Thread(task, "embedded-mirror-lifecycle").apply { isDaemon = true } }
        .apply { allowCoreThreadTimeOut(true) }

    private enum class LifecycleKind { START, STOP, CLOSE, AUDIO }

    // Guards the request bookkeeping below. Held only for a few field reads/writes — never across a
    // backend call, so neither the UI thread nor the lane can ever wait on it for long.
    private val requestLock = Any()

    // Bumped by every start/stop request; a queued start/stop only runs when no later start/stop
    // superseded it.
    private var latestRequestSeq = 0L

    // A Disconnect was requested and has not run (or been collapsed into a later request) yet. The
    // next Connect that actually runs consumes it and restarts the stream instead of being a no-op
    // against a backend that still reports itself attached.
    private var disconnectPending = false

    @Volatile private var closeRequested = false
    private var closeDone = false

    @Volatile private var laneThread: Thread? = null

    /** Non-null only for the macOS mirror-only VideoToolbox/Metal path. */
    internal val macSurface: EmbeddedMirrorMacSurface? get() = backend.macSurface

    /** Non-null only while a Windows D3D11 or Linux VAAPI/EGL preview is active. */
    internal val gpuSurface: EmbeddedMirrorGpuSurface? get() = backend.gpuSurface

    /** Keeps same-window Compose popups above the heavyweight native Metal layer. */
    fun setOverlayOccluded(occluded: Boolean) = backend.setOverlayOccluded(occluded)

    /**
     * Non-blocking Connect for UI callers: queues a start on the lifecycle lane and, if it actually
     * runs (not superseded by a later [requestStop]/[requestStart], not closed), then [afterStart].
     * The future completes when the job ran or was skipped; it fails if the start threw. A Connect
     * that superseded a still-pending Disconnect runs as a restart (see the class doc).
     */
    fun requestStart(
        serial: String,
        options: MirrorStreamOptions,
        afterStart: () -> Unit = {},
    ): CompletableFuture<Unit> = enqueueLifecycle(LifecycleKind.START) { restart ->
        if (restart && backend.isAlreadyStarted(serial)) stopNow()
        startNow(serial, options)
        afterStart()
    }

    /** Non-blocking Disconnect for UI callers — see [requestStart]. */
    fun requestStop(): CompletableFuture<Unit> = enqueueLifecycle(LifecycleKind.STOP) { stopNow() }

    /**
     * Non-blocking live-audio toggle: runs on the lane, ordered with Connect/Disconnect/close, and is
     * skipped once a close was requested — so a late toggle can never attach a player to a mirror
     * that no longer has any UI to stop it.
     */
    fun requestSetLiveAudioEnabled(
        enabled: Boolean,
        volume: () -> Float = { 1f },
        onDiagnostic: (String) -> Unit = {},
    ): CompletableFuture<Unit> = enqueueLifecycle(LifecycleKind.AUDIO) {
        backend.setLiveAudioEnabled(enabled, volume, onDiagnostic)
    }

    /**
     * Non-blocking terminal close for UI callers. Supersedes (skips) every queued start/stop/audio
     * job, runs after whatever job is executing right now, then retires the lane. Never skipped.
     *
     * The EDT-bound native surface teardown is started here, not on the lane: inline when the caller
     * IS the EDT (an EDT-safe, lock-free call that therefore cannot deadlock and leaves nothing for
     * the lane to wait on the EDT for), otherwise queued with `invokeLater` (never waited for).
     */
    fun requestClose(): CompletableFuture<Unit> {
        synchronized(requestLock) { closeRequested = true }
        closeNativeSurfacesFromAnyThread()
        return enqueueLifecycle(LifecycleKind.CLOSE) { closeNow() }
    }

    private fun closeNativeSurfacesFromAnyThread() {
        if (EventQueue.isDispatchThread()) {
            runCatching { backend.closeNativeSurfaces() }
        } else if (backend.macSurface != null || backend.gpuSurface != null) {
            EventQueue.invokeLater { runCatching { backend.closeNativeSurfaces() } }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun enqueueLifecycle(
        kind: LifecycleKind,
        action: (restartAfterPendingDisconnect: Boolean) -> Unit,
    ): CompletableFuture<Unit> {
        val seq = synchronized(requestLock) {
            when (kind) {
                LifecycleKind.START -> ++latestRequestSeq
                LifecycleKind.STOP -> {
                    disconnectPending = true
                    ++latestRequestSeq
                }
                else -> latestRequestSeq
            }
        }
        val done = CompletableFuture<Unit>()
        try {
            lifecycleLane.execute {
                laneThread = Thread.currentThread()
                try {
                    // Decided under the same lock requestClose() takes, so a request that was
                    // already past this point when a close was requested simply finishes first (the
                    // close is queued behind it) and one that comes after it is skipped.
                    var restart = false
                    val run = synchronized(requestLock) {
                        val shouldRun = when (kind) {
                            LifecycleKind.CLOSE -> true
                            LifecycleKind.AUDIO -> !closeRequested
                            LifecycleKind.START, LifecycleKind.STOP -> !closeRequested && latestRequestSeq == seq
                        }
                        if (shouldRun && (kind == LifecycleKind.START || kind == LifecycleKind.STOP)) {
                            restart = kind == LifecycleKind.START && disconnectPending
                            disconnectPending = false
                        }
                        shouldRun
                    }
                    if (run) action(restart)
                    done.complete(Unit)
                } catch (failure: Throwable) {
                    AppLogger.warn("embedded-mirror", "mirror lifecycle request failed", failure)
                    done.completeExceptionally(failure)
                }
            }
        } catch (_: RejectedExecutionException) {
            // The lane is shut down: the handle is already closed, so there is nothing left to do.
            done.complete(Unit)
        }
        return done
    }

    private fun startNow(serial: String, options: MirrorStreamOptions) {
        // Idempotency is backend-specific: a standalone runtime's own connection state (device
        // serial + CONNECTING/LIVE/RECONNECTING) tells us whether calling start() again would be
        // redundant. A shared backend's connection state instead reflects the *recording's* own
        // session — already LIVE well before any decoder is ever attached — so it can't be used the
        // same way; SharedRecordingSession.start() has its own "decoder already attached" guard.
        if (backend.isAlreadyStarted(serial)) return
        backend.start(serial, options)
        _snapshot.value = backend.snapshot()
    }

    private fun stopNow() {
        backend.stop()
        _snapshot.value = backend.snapshot()
    }

    private fun closeNow() {
        synchronized(requestLock) {
            if (closeDone) return
            closeDone = true
        }
        try {
            backend.close()
            _snapshot.value = backend.snapshot()
        } finally {
            lifecycleLane.shutdown()
        }
    }

    /** Blocks for [done] unless that would be wrong: never on the lane itself (self-deadlock) and,
     * for [timeoutMs] > 0, never longer than that. Failures of the job are rethrown unwrapped. */
    private fun awaitLifecycle(done: CompletableFuture<Unit>, timeoutMs: Long = 0L) {
        if (Thread.currentThread() === laneThread) return
        try {
            if (timeoutMs > 0L) done.get(timeoutMs, TimeUnit.MILLISECONDS) else done.get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } catch (_: TimeoutException) {
            AppLogger.warn("embedded-mirror", "mirror lifecycle request still running after ${timeoutMs}ms; not waiting further")
        }
    }

    /** Blocking; never call from the UI thread — use [requestStart]. Goes through the lane. */
    fun start(serial: String, options: MirrorStreamOptions) = awaitLifecycle(requestStart(serial, options))

    /** For a shared backend this only detaches the mirror decoder — the recording session itself
     * is never stopped or disturbed (see [MirrorBackend.SharedRecordingSession.stop]). Blocking;
     * never call from the UI thread — use [requestStop]. Goes through the lane. */
    fun stop() = awaitLifecycle(requestStop())

    fun send(command: MirrorControlCommand): Boolean = backend.send(command)

    /** Whether this mirror's device stream has an audio track to play — see [MirrorBackend.hasLiveAudio]. */
    val hasLiveAudio: Boolean get() = backend.hasLiveAudio

    fun sendTouch(
        mapper: MirrorCoordinateMapper,
        action: MirrorTouchAction,
        pointerId: Long,
        viewportX: Float,
        viewportY: Float,
    ): Boolean {
        // Only a press is confined to the picture; MOVE/UP/CANCEL clamp to its edge so a touch that
        // started inside and ends in the letterbox still releases (else the device stays pressed).
        val point = mapper.map(viewportX, viewportY, clamp = action != MirrorTouchAction.DOWN) ?: return false
        return send(
            MirrorControlCommand.Touch(
                action = action,
                pointerId = pointerId,
                x = point.x,
                y = point.y,
                screenWidth = mapper.screenWidth,
                screenHeight = mapper.screenHeight,
            ),
        )
    }

    /**
     * Idempotent close. Always goes through the lane ([requestClose]); a non-UI caller additionally
     * waits for it, bounded by [CLOSE_WAIT_MS] (the close itself is never skipped — only the wait
     * ends). On the EDT, or on the lane itself, it does not wait at all: the EDT-bound surface
     * teardown has already happened inline by then, and the rest is the lane's business.
     */
    override fun close() {
        val done = requestClose()
        if (EventQueue.isDispatchThread()) return
        awaitLifecycle(done, CLOSE_WAIT_MS)
    }

    companion object {
        private const val LIFECYCLE_IDLE_MS = 10_000L
        private const val CLOSE_WAIT_MS = 15_000L

        /**
         * Test seam: a handle around a caller-supplied [backend]. The constructor stays private so
         * production code only builds handles through [create]/[createShared]/[createAroundRuntime].
         */
        internal fun forBackend(backend: MirrorBackend): EmbeddedMirrorHandle = EmbeddedMirrorHandle(backend)

        /**
         * [sharedSession] is the *recording's* own [EmbeddedDeviceSession], when video recording
         * is currently running for this tab (`TabCaptureController.activeEmbeddedSession()`); null
         * for a mirror-only capture (`recordVideo=false`) or when no capture is recording. Passing
         * a session wires [MirrorBackend.SharedRecordingSession] instead of opening a second
         * embedded scrcpy server — see that class's doc.
         */
        fun create(
            tools: CaptureTools,
            root: File,
            sharedSession: EmbeddedDeviceSession? = null,
            hardwareMirrorEnabled: Boolean = false,
        ): EmbeddedMirrorHandle =
            if (sharedSession != null) {
                // Recording video already owns the device session. On macOS its packet pump can
                // feed VideoToolbox/Metal directly too, without a second scrcpy encoder or the
                // JavaCV -> BufferedImage -> Compose copy that made the shared path feel delayed.
                when {
                    shouldUseMacNativeMirror() -> createSharedMacNativeOrCompose(sharedSession)
                    shouldUseDesktopGpuMirror(enabled = hardwareMirrorEnabled) -> createSharedGpuNativeOrCompose(sharedSession)
                    else -> createShared(sharedSession)
                }
            } else if (shouldUseMacNativeMirror()) {
                createMacNativeOrCompose(tools, root)
            } else if (shouldUseDesktopGpuMirror(enabled = hardwareMirrorEnabled)) {
                createGpuNativeOrCompose(tools, root)
            } else {
                createComposeStandalone(tools, root)
            }

        private fun createComposeStandalone(tools: CaptureTools, root: File): EmbeddedMirrorHandle =
            createAroundRuntime { listener -> createComposeRuntime(tools, root, listener) }

        private fun createComposeRuntime(
            tools: CaptureTools,
            root: File,
            listener: (EmbeddedMirrorSnapshot) -> Unit,
        ): EmbeddedMirrorRuntime = EmbeddedMirrorRuntime(
            transport = AdbScrcpyTransport(
                tools = tools,
                runner = ProcessBuilderCaptureRunner(),
                localRoot = root,
            ),
            decoder = JavaCvH264Decoder(),
            listener = listener,
        )

        // Native VideoToolbox/Metal surface setup can fail in many unrelated ways (missing
        // dylib, JAWT attach failure, ...); any of them must fall back to the Compose decoder
        // rather than crash, so catching Throwable here is intentional.
        @Suppress("TooGenericExceptionCaught")
        private fun createMacNativeOrCompose(tools: CaptureTools, root: File): EmbeddedMirrorHandle {
            var surface: EmbeddedMirrorMacSurface? = null
            return try {
                lateinit var backend: MirrorBackend.StandaloneRuntime
                surface = EmbeddedMirrorMacSurface(
                    diagnosticSink = { diagnostic -> AppLogger.warn("embedded-mirror", diagnostic) },
                )
                val metalSurface = requireNotNull(surface)
                lateinit var handle: EmbeddedMirrorHandle
                val nativeFrameReported = AtomicBoolean(false)
                val runtime = EmbeddedMirrorRuntime(
                    transport = AdbScrcpyTransport(
                        tools = tools,
                        runner = ProcessBuilderCaptureRunner(),
                        localRoot = root,
                    ),
                    directDecoder = MacVideoToolboxMirrorDecoder(metalSurface),
                    onDirectFrame = { },
                    directFallbackDecoder = JavaCvH264Decoder(),
                    listener = { snapshot ->
                        handle._snapshot.value = snapshot
                        if (snapshot.frame != null) backend.hideMacSurface()
                        // This is now emitted only for the initial frame / a size change, so it
                        // realizes the AWT canvas without posting a repaint for every frame.
                        snapshot.frameInfo?.let { frame ->
                            if (nativeFrameReported.compareAndSet(false, true)) {
                                AppLogger.info(
                                    "embedded-mirror",
                                    "mode=videotoolbox-metal status=active width=${frame.width} height=${frame.height}",
                                )
                            }
                            metalSurface.requestDisplay()
                        }
                    },
                    onDirectDecoderFailure = { failure ->
                        AppLogger.warn(
                            "embedded-mirror",
                            "mode=videotoolbox-metal status=failed; switching the existing connection to Compose at the next key frame",
                            failure,
                        )
                    },
                )
                backend = MirrorBackend.StandaloneRuntime(
                    runtime = runtime,
                    macSurface = metalSurface,
                )
                handle = EmbeddedMirrorHandle(backend)
                handle
            } catch (failure: Throwable) {
                runCatching { surface?.close() }
                AppLogger.warn(
                    "embedded-mirror",
                    "VideoToolbox mirror unavailable; using Compose fallback (${failure.message ?: failure::class.simpleName})",
                    failure,
                )
                createComposeStandalone(tools, root)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun createGpuNativeOrCompose(tools: CaptureTools, root: File): EmbeddedMirrorHandle {
            var surface: EmbeddedMirrorGpuSurface? = null
            return try {
                surface = EmbeddedMirrorGpuSurface.create()
                val gpuSurface = requireNotNull(surface)
                lateinit var backend: MirrorBackend.StandaloneRuntime
                lateinit var handle: EmbeddedMirrorHandle
                val nativeFrameReported = AtomicBoolean(false)
                val runtime = EmbeddedMirrorRuntime(
                    transport = AdbScrcpyTransport(
                        tools = tools,
                        runner = ProcessBuilderCaptureRunner(),
                        localRoot = root,
                    ),
                    directDecoder = gpuSurface.createDecoder(),
                    onDirectFrame = { },
                    directFallbackDecoder = JavaCvH264Decoder(),
                    listener = { snapshot ->
                        handle._snapshot.value = snapshot
                        if (snapshot.frame != null) backend.hideGpuSurface()
                        snapshot.frameInfo?.let { frame ->
                            if (nativeFrameReported.compareAndSet(false, true)) {
                                AppLogger.info(
                                    "embedded-mirror",
                                    "mode=${gpuSurface.mode} status=active width=${frame.width} height=${frame.height}",
                                )
                            }
                        }
                    },
                    onDirectDecoderFailure = { failure ->
                        AppLogger.warn(
                            "embedded-mirror",
                            "mode=${gpuSurface.mode} status=failed; switching the existing connection to Compose at the next key frame",
                            failure,
                        )
                    },
                )
                backend = MirrorBackend.StandaloneRuntime(runtime = runtime, gpuSurface = gpuSurface)
                handle = EmbeddedMirrorHandle(backend)
                handle
            } catch (failure: Throwable) {
                runCatching { surface?.close() }
                AppLogger.warn(
                    "embedded-mirror",
                    "Windows/Linux GPU mirror unavailable; using Compose fallback (${failure.message ?: failure::class.simpleName})",
                    failure,
                )
                createComposeStandalone(tools, root)
            }
        }

        internal fun createShared(
            session: EmbeddedDeviceSession,
            decoder: H264Decoder = JavaCvH264Decoder(),
            liveAudioSinkFactory: (
                onDiagnostic: (String) -> Unit,
                volume: () -> Float,
            ) -> com.indagium.capture.mirror.LiveAudioSink = { onDiagnostic, volume ->
                com.indagium.capture.mirror.LiveAudioPlayer(onDiagnostic = onDiagnostic, volume = volume)
            },
        ): EmbeddedMirrorHandle {
            lateinit var handle: EmbeddedMirrorHandle
            val backend = MirrorBackend.SharedRecordingSession(
                session = session,
                decoder = decoder,
                onSnapshotChanged = { snapshot -> handle._snapshot.value = snapshot },
                liveAudioSinkFactory = liveAudioSinkFactory,
            )
            handle = EmbeddedMirrorHandle(backend)
            return handle
        }

        // Same rationale as createMacNativeOrCompose above: native surface setup failing here
        // must fall back to the Compose-decoded shared path rather than crash.
        @Suppress("TooGenericExceptionCaught")
        private fun createSharedMacNativeOrCompose(session: EmbeddedDeviceSession): EmbeddedMirrorHandle {
            var surface: EmbeddedMirrorMacSurface? = null
            return try {
                val surfaceFactory = {
                    EmbeddedMirrorMacSurface(
                        diagnosticSink = { diagnostic -> AppLogger.warn("embedded-mirror", diagnostic) },
                    )
                }
                surface = surfaceFactory()
                lateinit var handle: EmbeddedMirrorHandle
                val backend = MirrorBackend.SharedRecordingSession(
                    session = session,
                    decoder = JavaCvH264Decoder(),
                    macSurface = requireNotNull(surface),
                    directDecoderFactory = { activeSurface -> MacVideoToolboxMirrorDecoder(activeSurface) },
                    macSurfaceFactory = surfaceFactory,
                ) { snapshot -> handle._snapshot.value = snapshot }
                handle = EmbeddedMirrorHandle(backend)
                handle
            } catch (failure: Throwable) {
                runCatching { surface?.close() }
                AppLogger.warn(
                    "embedded-mirror",
                    "VideoToolbox recording mirror unavailable; using Compose (${failure.message ?: failure::class.simpleName})",
                    failure,
                )
                createShared(session)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun createSharedGpuNativeOrCompose(session: EmbeddedDeviceSession): EmbeddedMirrorHandle {
            var surface: EmbeddedMirrorGpuSurface? = null
            return try {
                val surfaceFactory = { EmbeddedMirrorGpuSurface.create() }
                surface = surfaceFactory()
                lateinit var handle: EmbeddedMirrorHandle
                lateinit var backend: MirrorBackend.SharedRecordingSession
                val gpuSurface = requireNotNull(surface)
                backend = MirrorBackend.SharedRecordingSession(
                    session = session,
                    decoder = JavaCvH264Decoder(),
                    gpuSurface = gpuSurface,
                    gpuDirectDecoderFactory = { activeSurface -> activeSurface.createDecoder() },
                    gpuSurfaceFactory = surfaceFactory,
                    onSnapshotChanged = { snapshot ->
                        handle._snapshot.value = snapshot
                        if (snapshot.frame != null) backend.hideGpuSurface()
                    },
                )
                handle = EmbeddedMirrorHandle(backend)
                handle
            } catch (failure: Throwable) {
                runCatching { surface?.close() }
                AppLogger.warn(
                    "embedded-mirror",
                    "Windows/Linux recording mirror unavailable; using Compose (${failure.message ?: failure::class.simpleName})",
                    failure,
                )
                createShared(session)
            }
        }

        /**
         * Test seam: builds a handle around a caller-supplied runtime (a fake transport/decoder,
         * typically), wiring the same listener-to-`_snapshot`-StateFlow bridge [create] uses in
         * production. Without this, a test-constructed `EmbeddedMirrorHandle(EmbeddedMirrorRuntime(
         * fakeTransport, fakeDecoder))` only reflects the runtime's state at the instant
         * start()/stop() were called — an async transition like CONNECTING -> LIVE from the
         * runtime's own worker thread is invisible on the handle's `snapshot` StateFlow, since
         * `_snapshot` is private and nothing else republishes it.
         */
        internal fun createAroundRuntime(
            runtimeFactory: (listener: (EmbeddedMirrorSnapshot) -> Unit) -> EmbeddedMirrorRuntime,
        ): EmbeddedMirrorHandle {
            lateinit var handle: EmbeddedMirrorHandle
            val runtime = runtimeFactory { snapshot -> handle._snapshot.value = snapshot }
            handle = EmbeddedMirrorHandle(MirrorBackend.StandaloneRuntime(runtime))
            return handle
        }
    }
}

/** Platform gate deliberately kept independent from user preferences: this fast route is macOS-only. */
internal fun shouldUseMacNativeMirror(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    osName.contains("mac", ignoreCase = true)

/** [enabled] is the persisted `CaptureSettings.hardwareMirror` opt-in (default false — see that
 * field's doc): neither the D3D11 nor the VAAPI/EGL path has proven itself on real hardware yet, so
 * Windows/Linux only take this route when a user has explicitly turned it on in Settings → Capture. */
internal fun shouldUseDesktopGpuMirror(
    osName: String = System.getProperty("os.name").orEmpty(),
    enabled: Boolean,
): Boolean = enabled && (osName.contains("win", ignoreCase = true) || osName.contains("linux", ignoreCase = true))

/** What [EmbeddedMirrorHandle] drives — either its own standalone transport, or a shared view onto
 * a live recording's device stream. See each implementation's doc. Deliberately not `sealed` so
 * tests can drive [EmbeddedMirrorHandle.forBackend] with a fake.
 *
 * Every member here is blocking and must be called from a non-UI thread — see the threading
 * invariant on [EmbeddedMirrorHandle]; UI code goes through its `request*` methods instead. */
internal interface MirrorBackend : Closeable {
    val macSurface: EmbeddedMirrorMacSurface? get() = null
    val gpuSurface: EmbeddedMirrorGpuSurface? get() = null

    /**
     * Compose popups are rendered in a scene layer above their anchor, but JAWT Metal is an AppKit
     * sibling. Backends with a native surface must retain this state across a decoder reconnect.
     */
    fun setOverlayOccluded(occluded: Boolean) {
        macSurface?.setOverlayOccluded(occluded)
    }

    fun snapshot(): EmbeddedMirrorSnapshot

    fun start(serial: String, options: MirrorStreamOptions)

    fun stop()

    /**
     * The EDT-bound half of [close]: tears the native surfaces down (idempotent; inline when already
     * on the EDT, bounded wait otherwise — see [EmbeddedMirrorMacSurface.close]) and, being part of
     * a terminal close, forbids creating or attaching any new surface afterwards. Must NOT take a
     * lifecycle lock or do any blocking device I/O, because [EmbeddedMirrorHandle.requestClose]
     * calls it directly on the UI thread. [close] must still be called afterwards for the rest.
     */
    fun closeNativeSurfaces() = Unit

    fun send(command: MirrorControlCommand): Boolean

    /** Whether calling [start] again for [serial] right now would be redundant — see
     * [EmbeddedMirrorHandle.start]'s doc for why this can't be answered the same way for both
     * backends. */
    fun isAlreadyStarted(serial: String): Boolean

    /** Whether this backend's device stream actually has an audio track to play — the mirror
     * panel's speaker toggle only shows when this is true. Only [SharedRecordingSession] can ever
     * say yes: a standalone runtime's own transport never reads the scrcpy audio socket at all (see
     * that class's doc), so live playback there would need a materially larger change to the
     * mirror-only path — out of scope for this pass, see LiveAudioPlayer.kt's own doc. */
    val hasLiveAudio: Boolean get() = false

    /** Starts or stops live audio playback. A no-op for a backend that never has [hasLiveAudio]. */
    fun setLiveAudioEnabled(enabled: Boolean, volume: () -> Float, onDiagnostic: (String) -> Unit) = Unit

    /** The pre-redesign path: opens its own embedded scrcpy server/device encoder. Used only when
     * the capture isn't recording video — see [EmbeddedMirrorHandle.create]'s doc. */
    class StandaloneRuntime(
        private var runtime: EmbeddedMirrorRuntime,
        override var macSurface: EmbeddedMirrorMacSurface? = null,
        override var gpuSurface: EmbeddedMirrorGpuSurface? = null,
    ) : MirrorBackend {
        private val lock = Any()
        private var startedSerial: String? = null
        private var startedOptions: MirrorStreamOptions? = null
        private var switchedToCompose = false

        // Set under [lock] by close(); start() checks it under the same lock, so a start can never
        // begin a new adb forward + scrcpy server once close() has been entered.
        private var closed = false

        override fun snapshot(): EmbeddedMirrorSnapshot = runtime.snapshot()

        override fun isAlreadyStarted(serial: String): Boolean {
            val current = runtime.snapshot()
            return current.deviceSerial == serial && current.state in setOf(
                EmbeddedMirrorState.CONNECTING,
                EmbeddedMirrorState.LIVE,
                EmbeddedMirrorState.RECONNECTING,
            )
        }

        override fun start(serial: String, options: MirrorStreamOptions) {
            synchronized(lock) {
                if (closed) return
                startedSerial = serial
                startedOptions = options
            }
            if (macSurface != null) {
                AppLogger.info(
                    "embedded-mirror",
                    "mode=videotoolbox-metal status=connecting max_size=${options.maxSize} " +
                        "max_fps=${options.maxFps} bitrate_bps=${options.serverVideoBitRateBitsPerSecond} " +
                        "keyframe_interval_s=${options.keyFrameIntervalSeconds} audio=${options.audio} " +
                        "compose_interop_blending=${System.getProperty("compose.interop.blending")}",
                )
            }
            runtime.start(serial, options)
        }

        override fun stop() = runtime.stop()

        override fun send(command: MirrorControlCommand): Boolean = runtime.send(command)

        /** Compose has decoded a replacement frame from the existing connection. */
        fun hideMacSurface() {
            val retiredSurface = synchronized(lock) {
                if (switchedToCompose) return
                switchedToCompose = true
                val previous = macSurface
                macSurface = null
                previous
            }
            // Native teardown detaches the JAWT CALayer; queue it on the EDT before SwingPanel
            // disposal so that stale native pixels cannot remain above the Compose fallback.
            if (retiredSurface != null) {
                EventQueue.invokeLater { runCatching { retiredSurface.close() } }
            }
            AppLogger.warn("embedded-mirror", "mode=compose-fallback status=active connection=reused")
        }

        /** A Compose fallback frame replaces the direct GPU Canvas without restarting transport. */
        fun hideGpuSurface() {
            val retiredSurface = synchronized(lock) {
                if (switchedToCompose) return
                switchedToCompose = true
                val previous = gpuSurface
                gpuSurface = null
                previous
            }
            if (retiredSurface != null) {
                EventQueue.invokeLater { runCatching { retiredSurface.close() } }
            }
            AppLogger.warn("embedded-mirror", "mode=compose-fallback status=active connection=reused")
        }

        override fun closeNativeSurfaces() {
            val (mac, gpu) = synchronized(lock) { macSurface to gpuSurface }
            runCatching { mac?.close() }
            runCatching { gpu?.close() }
        }

        override fun close() {
            synchronized(lock) { closed = true }
            runtime.close()
            closeNativeSurfaces()
        }
    }

    /**
     * A view onto a live recording's own [EmbeddedDeviceSession] — the approved "one embedded
     * scrcpy session feeds both recording and the in-app mirror" design. [start]/[stop] (Connect/
     * Disconnect) only attach or detach a decoder on the *shared* session via
     * [EmbeddedDeviceSession.attachDecoder]/[EmbeddedDeviceSession.detachDecoder]; they never call
     * [EmbeddedDeviceSession.start]/[EmbeddedDeviceSession.stop] — the session's own connect/
     * reconnect lifecycle is owned exclusively by `CaptureRecorder`, and disconnecting the mirror
     * must never touch the recording. [send] forwards control commands over the same session's
     * control socket ([EmbeddedDeviceSession.sendControl]). The published [snapshot] combines the
     * session's own connection state (registered once via [EmbeddedDeviceSession.addConnectionListener]
     * so it keeps tracking the session's reconnects even while no decoder is attached) with the most
     * recently decoded frame.
     */
    class SharedRecordingSession(
        private val session: EmbeddedDeviceSession,
        private val decoder: H264Decoder,
        override var macSurface: EmbeddedMirrorMacSurface? = null,
        private var directDecoderFactory: ((EmbeddedMirrorMacSurface) -> DirectH264Decoder)? = null,
        private val macSurfaceFactory: (() -> EmbeddedMirrorMacSurface)? = null,
        override var gpuSurface: EmbeddedMirrorGpuSurface? = null,
        private var gpuDirectDecoderFactory: ((EmbeddedMirrorGpuSurface) -> DirectH264Decoder)? = null,
        private val gpuSurfaceFactory: (() -> EmbeddedMirrorGpuSurface)? = null,
        private val liveAudioSinkFactory: (
            onDiagnostic: (String) -> Unit,
            volume: () -> Float,
        ) -> com.indagium.capture.mirror.LiveAudioSink = { onDiagnostic, volume ->
            com.indagium.capture.mirror.LiveAudioPlayer(onDiagnostic = onDiagnostic, volume = volume)
        },
        private val onSnapshotChanged: (EmbeddedMirrorSnapshot) -> Unit,
    ) : MirrorBackend {
        private val lock = Any()

        // Serializes start/stop/fallback transitions without holding [lock] while a session call
        // can join a decoder worker or invoke native teardown callbacks. The state lock remains
        // for short snapshot updates made by those callbacks.
        //
        // Invariant: the EDT/UI thread never waits on this lock (UI code goes through
        // EmbeddedMirrorHandle.request*, which run on a non-UI lane), and the native surface
        // close/recreate — the work that needs the EDT — happens OUTSIDE it (see [stop]). The
        // only EDT-bound call still reachable under it is the detached decoder's own surface close
        // inside session.detachDecoder(), and that waits for the EDT for a bounded time only
        // (runOnEdtBounded), so even a caller that violated the first rule cannot deadlock.
        private val lifecycleLock = ReentrantLock()

        // Set (under [lifecycleLock]) while [stop] swaps the native surface outside the lock, so a
        // concurrent start/stop/close waits for the replacement instead of racing it.
        private var surfaceSwapInFlight = false
        private val surfaceSwapDone = lifecycleLock.newCondition()

        @Volatile private var closed = false

        // Serializes setLiveAudioEnabled against close() — see setLiveAudioEnabled.
        private val audioLock = Any()

        private companion object {
            /** Generous: a native surface close waits at most [EDT_CLOSE_WAIT_MS] for the EDT. */
            const val SURFACE_SWAP_WAIT_NANOS = 5_000_000_000L
        }

        private var connectionSnapshot = session.connectionSnapshot()
        private var connectionSnapshotVersion = Long.MIN_VALUE
        private var lastFrame: MirrorFrame? = null
        private var lastFrameInfo: com.indagium.capture.mirror.MirrorFrameInfo? = null
        private var attached = false
        private var overlayOccluded = false
        private var liveAudioPlayer: com.indagium.capture.mirror.LiveAudioSink? = null
        private val connectionListener = session.addConnectionListener { snapshot, version ->
            val next = synchronized(lock) {
                if (version < connectionSnapshotVersion) {
                    null
                } else {
                    connectionSnapshotVersion = version
                    connectionSnapshot = snapshot
                    composeLocked()
                }
            }
            next?.let(onSnapshotChanged)
        }

        override fun snapshot(): EmbeddedMirrorSnapshot = synchronized(lock) { composeLocked() }

        override fun setOverlayOccluded(occluded: Boolean) {
            val surface = synchronized(lock) {
                overlayOccluded = occluded
                macSurface
            }
            surface?.setOverlayOccluded(occluded)
        }

        /** While [attached], mirrors the recording's own connection (LIVE/RECONNECTING/FAILED all
         * pass through unchanged) plus whatever this decoder has produced. While detached — after
         * [stop] — the recording connection itself stays LIVE (see the class doc), but this mirror
         * is no longer decoding it, so reporting [connectionSnapshot] here regardless of [attached]
         * left the control bar saying "Live" and the surface black after Disconnect. Report
         * DISCONNECTED with no error/frame instead; [start] (Connect) re-attaches. */
        private fun composeLocked(): EmbeddedMirrorSnapshot =
            if (attached) {
                connectionSnapshot.copy(frame = lastFrame, frameInfo = lastFrameInfo)
            } else {
                EmbeddedMirrorSnapshot()
            }

        /** Ignores [serial] — the shared session's own connection state (already LIVE, well before
         * any decoder ever attaches) says nothing about whether a decoder is attached; only
         * [attached] does. */
        override fun isAlreadyStarted(serial: String): Boolean = synchronized(lock) { attached }

        /** [serial]/[options] are ignored — the shared session is already connected under its own
         * recording options; this only attaches a decoder, at most once.
         *
         * Native decoder construction/attach can fail in many unrelated ways; both catches inside
         * must fall back to the Compose decoder rather than leave the mirror stuck, so catching
         * Throwable is intentional (see the inline comments at each catch site). */
        @Suppress("TooGenericExceptionCaught")
        override fun start(serial: String, options: MirrorStreamOptions) {
            lifecycleLock.withLock {
                awaitSurfaceSwapLocked()
                if (closed || synchronized(lock) { attached }) return
                synchronized(lock) { attached = true }
                // Publish immediately: composeLocked() now depends on [attached], so Connect must
                // report the recording's current state (LIVE, most commonly) right away rather than
                // waiting for this decoder's first frame — see the class doc's Disconnect fix.
                onSnapshotChanged(snapshot())
                val direct = try {
                    synchronized(lock) {
                        when {
                            macSurface != null && directDecoderFactory != null ->
                                directDecoderFactory!!.invoke(requireNotNull(macSurface)) to "videotoolbox-metal"
                            gpuSurface != null && gpuDirectDecoderFactory != null ->
                                gpuDirectDecoderFactory!!.invoke(requireNotNull(gpuSurface)) to requireNotNull(gpuSurface).mode
                            else -> null to null
                        }
                    }
                } catch (failure: Throwable) {
                    // Native decoder construction happens before the session has attached anything.
                    // Clear the optimistic flag here; otherwise Retry returns early forever after a
                    // construction error and the mirror appears disconnected but cannot reconnect.
                    AppLogger.warn(
                        "embedded-mirror",
                        "Native recording mirror could not start; using Compose",
                        failure,
                    )
                    session.reportMirrorDiagnostic(
                        "native GPU initialization failed; using Compose (${failure.message ?: failure::class.simpleName})",
                    )
                    switchToComposeFallbackAfterSetupFailure()
                    return
                }
                val selectedMode = direct.second
                val nativeModeReported = AtomicBoolean(false)
                if (direct.first != null) {
                    val connectingMessage = "mode=$selectedMode status=connecting source=recording-session"
                    AppLogger.info("embedded-mirror", connectingMessage)
                    session.reportMirrorDiagnostic(connectingMessage)
                    try {
                        session.attachDirectDecoder(requireNotNull(direct.first), onFrame = { frame ->
                            if (nativeModeReported.compareAndSet(false, true)) {
                                session.reportMirrorDiagnostic(
                                    "mode=$selectedMode status=active width=${frame.width} height=${frame.height}",
                                )
                            }
                            val next = synchronized(lock) {
                                lastFrame = null
                                lastFrameInfo = frame
                                composeLocked()
                            }
                            onSnapshotChanged(next)
                        }, onFailure = { failure ->
                            switchToComposeFallback(failure)
                        })
                    } catch (failure: Throwable) {
                        runCatching { direct.first?.close() }
                        AppLogger.warn(
                            "embedded-mirror",
                            "Native recording mirror could not attach; using Compose",
                            failure,
                        )
                        switchToComposeFallbackAfterSetupFailure()
                    }
                } else {
                    attachComposeDecoder()
                }
            }
        }

        /** Restores a usable state after direct-decoder setup fails before its worker can report it. */
        private fun switchToComposeFallbackAfterSetupFailure() {
            val retiredSurfaces = synchronized(lock) {
                if (!attached) return
                directDecoderFactory = null
                gpuDirectDecoderFactory = null
                lastFrameInfo = null
                listOfNotNull(macSurface.also { macSurface = null }, gpuSurface.also { gpuSurface = null })
            }
            retiredSurfaces.forEach { runCatching { it.close() } }
            attachComposeDecoder()
        }

        private fun attachComposeDecoder() {
            session.attachDecoder(decoder) { frame ->
                val next = synchronized(lock) {
                    lastFrame = frame
                    lastFrameInfo = null
                    composeLocked()
                }
                hideGpuSurface()
                onSnapshotChanged(next)
            }
        }

        /** Keeps the recorder's one device connection and switches only its local presentation. */
        private fun switchToComposeFallback(failure: Throwable) {
            val retiredSurfaces = lifecycleLock.withLock {
                awaitSurfaceSwapLocked()
                synchronized(lock) {
                    if (!attached || (directDecoderFactory == null && gpuDirectDecoderFactory == null)) return
                    directDecoderFactory = null
                    gpuDirectDecoderFactory = null
                    lastFrameInfo = null
                    listOfNotNull(macSurface.also { macSurface = null }, gpuSurface.also { gpuSurface = null })
                }
            }
            // Closing the Swing surface may need the EDT, so it happens outside [lifecycleLock]. It
            // is idempotent, and makes the next Compose snapshot remove it.
            retiredSurfaces.forEach { runCatching { it.close() } }
            AppLogger.warn(
                "embedded-mirror",
                "mode=native-gpu status=failed; switching shared recording mirror to Compose",
                failure,
            )
            lifecycleLock.withLock {
                // A Disconnect may have slipped in while the lock was released: then there is
                // nothing to attach (the session holds one decoder slot, so a Connect that also
                // slipped in cannot end up with two either — attachDecoder replaces).
                if (!closed && synchronized(lock) { attached }) attachComposeDecoder()
            }
        }

        override fun stop() = stop(recreateNativeSurface = true)

        /** What [stop] retired under [lifecycleLock] and still has to close + replace outside it. */
        private sealed interface RetiredSurface {
            class Gpu(val previous: EmbeddedMirrorGpuSurface?) : RetiredSurface

            class Mac(val previous: EmbeddedMirrorMacSurface?) : RetiredSurface
        }

        private fun stop(recreateNativeSurface: Boolean) {
            val retired = lifecycleLock.withLock {
                awaitSurfaceSwapLocked()
                val shouldRecreateNativeSurface = synchronized(lock) {
                    attached = false
                    lastFrame = null
                    lastFrameInfo = null
                    recreateNativeSurface && !closed && (
                        (directDecoderFactory != null && macSurface != null) ||
                            (gpuDirectDecoderFactory != null && gpuSurface != null)
                    )
                }
                // Publish DISCONNECTED immediately rather than waiting for detachDecoder()/native
                // surface recreation below (which can take real time) — see the class doc's
                // Disconnect fix and composeLocked()'s own doc.
                onSnapshotChanged(snapshot())
                session.detachDecoder()
                if (shouldRecreateNativeSurface) retireSurfaceForRecreationLocked() else null
            } ?: return
            // detachDecoder closes the per-attachment VideoToolbox decoder and its surface. A later
            // Connect must get a new native handle; reusing the old one would retain the decoder's
            // closed flag and leave the mirror permanently blank. The close/recreate needs the EDT,
            // so it runs here, outside [lifecycleLock] — see the invariant on that field.
            try {
                when (retired) {
                    is RetiredSurface.Gpu -> recreateGpuSurface(retired.previous)
                    is RetiredSurface.Mac -> recreateMacSurface(retired.previous)
                }
            } finally {
                lifecycleLock.withLock {
                    surfaceSwapInFlight = false
                    surfaceSwapDone.signalAll()
                }
            }
        }

        /** Under [lifecycleLock]: detaches the surface [stop] is about to replace and flags the swap. */
        private fun retireSurfaceForRecreationLocked(): RetiredSurface {
            surfaceSwapInFlight = true
            return synchronized(lock) {
                if (gpuDirectDecoderFactory != null) {
                    RetiredSurface.Gpu(gpuSurface.also { gpuSurface = null })
                } else {
                    RetiredSurface.Mac(macSurface.also { macSurface = null })
                }
            }
        }

        private fun recreateGpuSurface(previous: EmbeddedMirrorGpuSurface?) {
            runCatching { previous?.close() }
            if (closed) return
            val replacement = runCatching { gpuSurfaceFactory?.invoke() }.getOrNull()
            if (replacement == null) {
                synchronized(lock) { gpuDirectDecoderFactory = null }
                AppLogger.warn("embedded-mirror", "GPU recording mirror could not be recreated; using Compose on the next connect")
            } else if (closed) {
                runCatching { replacement.close() }
            } else {
                synchronized(lock) { gpuSurface = replacement }
            }
        }

        private fun recreateMacSurface(previous: EmbeddedMirrorMacSurface?) {
            runCatching { previous?.close() }
            if (closed) return
            val replacement = runCatching { macSurfaceFactory?.invoke() }.getOrNull()
            if (replacement == null) {
                synchronized(lock) { directDecoderFactory = null }
                AppLogger.warn("embedded-mirror", "VideoToolbox recording mirror could not be recreated; using Compose on the next connect")
            } else if (closed) {
                runCatching { replacement.close() }
            } else {
                val occluded = synchronized(lock) {
                    macSurface = replacement
                    overlayOccluded
                }
                replacement.setOverlayOccluded(occluded)
            }
        }

        /** Under [lifecycleLock]: waits (releasing it while waiting) for an in-flight surface swap
         * by another thread. Bounded, so a wedged native close can delay but never hang a caller. */
        private fun awaitSurfaceSwapLocked() {
            var remainingNanos = SURFACE_SWAP_WAIT_NANOS
            while (surfaceSwapInFlight) {
                if (remainingNanos <= 0L) {
                    AppLogger.warn("embedded-mirror", "native surface swap is taking long; continuing without waiting for it")
                    return
                }
                remainingNanos = surfaceSwapDone.awaitNanos(remainingNanos)
            }
        }

        override fun send(command: MirrorControlCommand): Boolean =
            session.sendControl(ScrcpyControlEncoder.encode(command))

        override val hasLiveAudio: Boolean get() = session.hasAudioStream()

        /** Creates (or tears down) a [com.indagium.capture.mirror.LiveAudioPlayer] and attaches/
         * detaches it on the shared session — the recording and its device connection are never
         * touched either way, same as [start]/[stop] above only ever attach/detach a decoder. */
        override fun setLiveAudioEnabled(enabled: Boolean, volume: () -> Float, onDiagnostic: (String) -> Unit) {
            // Whole enable/disable under [audioLock] (never held with the EDT or [lifecycleLock]), so
            // close()'s disable is strictly ordered after an enable that was already in flight, and
            // an enable that comes after close() sees [closed] and attaches nothing: a player
            // attached to a closed mirror would have no UI left to stop it.
            synchronized(audioLock) {
                if (enabled && closed) return
                val newPlayer = synchronized(lock) {
                    val current = liveAudioPlayer
                    if (enabled == (current != null)) return
                    val next = if (enabled) {
                        liveAudioSinkFactory(onDiagnostic, volume)
                    } else {
                        null
                    }
                    liveAudioPlayer = next
                    next
                }
                if (newPlayer != null) {
                    // EmbeddedDeviceSession.attachLiveAudio() closes the previous sink itself (see its
                    // own doc) — never attached here since [enabled] only reaches this branch when there
                    // was none.
                    session.attachLiveAudio(newPlayer)
                } else {
                    // detachLiveAudio() closes the sink it removes (the player this same method created
                    // last time it was enabled), so there is nothing further to close here.
                    session.detachLiveAudio()
                }
            }
        }

        /** Terminal and EDT-safe: no new surface may be created or attached after this (stop() and the
         * replacement paths check [closed]); never takes [lifecycleLock]. Idempotent. */
        override fun closeNativeSurfaces() {
            closed = true
            val surfaces = synchronized(lock) { listOfNotNull(macSurface, gpuSurface) }
            surfaces.forEach { runCatching { it.close() } }
        }

        /** Detaches the decoder and stops listening for the session's connection state — never
         * stops or closes the shared recording session itself. */
        override fun close() {
            // Closing a handle is terminal. Do not create a fresh native surface only to close it
            // immediately: that briefly creates an unattached Canvas and can race window teardown.
            closed = true
            stop(recreateNativeSurface = false)
            setLiveAudioEnabled(false, volume = { 1f }, onDiagnostic = {})
            connectionListener.close()
            runCatching { macSurface?.close() }
            macSurface = null
            runCatching { gpuSurface?.close() }
            gpuSurface = null
        }

        fun hideGpuSurface() {
            val retiredSurface = synchronized(lock) {
                gpuDirectDecoderFactory = null
                gpuSurface.also { gpuSurface = null }
            }
            runCatching { retiredSurface?.close() }
        }
    }
}

private fun clipboardString(): String? = runCatching {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) null
    else clipboard.getData(DataFlavor.stringFlavor) as? String
}.getOrNull()

// One copy (IntArray -> the ByteArray Skia installs) instead of the previous two BufferedImage
// round-trips (setRGB into a TYPE_INT_ARGB raster, then toComposeImageBitmap()'s own Java2D-based
// conversion into a Skia bitmap). ColorType.N32 is BGRA_8888 on every little-endian desktop this
// runs on, so a little-endian int view of the bytes is exactly pixelsArgb's ARGB packed format —
// no per-pixel channel shuffling needed, just a bulk IntBuffer.put(). OPAQUE (not PREMUL): every
// MirrorFrame pixel is fully opaque device video, matching pixelsArgb's own documented meaning.
private fun MirrorFrame.toComposeBitmap(): androidx.compose.ui.graphics.ImageBitmap {
    val rowBytes = width * Int.SIZE_BYTES
    val bytes = ByteArray(rowBytes * height)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixelsArgb)
    val bitmap = org.jetbrains.skia.Bitmap()
    check(
        bitmap.installPixels(org.jetbrains.skia.ImageInfo.makeN32(width, height, org.jetbrains.skia.ColorAlphaType.OPAQUE), bytes, rowBytes),
    ) { "could not install mirror frame pixels into a Skia bitmap" }
    return bitmap.asComposeImageBitmap()
}

/** A tall portrait device can grow within the scrollable sidebar without artificial 420dp cap. */
internal val MIRROR_SIDEBAR_MAX_HEIGHT = 900.dp

/** The initial portrait surface height keeps the capture card compact until the user drags it. */
internal val MIRROR_DEFAULT_HEIGHT = 420.dp
private val MIRROR_MIN_HEIGHT = 120.dp

private fun mirrorStateLabel(state: EmbeddedMirrorState, reconnectAttempt: Int): String = when (state) {
    EmbeddedMirrorState.DISCONNECTED -> "Disconnected"
    EmbeddedMirrorState.CONNECTING -> "Connecting…"
    EmbeddedMirrorState.LIVE -> "Live"
    EmbeddedMirrorState.RECONNECTING -> "Reconnecting ($reconnectAttempt)…"
    EmbeddedMirrorState.FAILED -> "Failed"
}

/** Right-panel embedded device surface. The panel owns no recorder and never opens a native window. */
@Composable
internal fun EmbeddedMirrorPanel(
    handle: EmbeddedMirrorHandle?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    detached: Boolean = false,
    onDetach: (() -> Unit)? = null,
    onReturnToSidebar: (() -> Unit)? = null,
    sidebarSurfaceHeight: Dp? = null,
    /** Size the surface from the height this panel is given rather than [sidebarSurfaceHeight] —
     *  see the `flexible` comment at the BoxWithConstraints below. Implied by [detached]. */
    fillAvailableHeight: Boolean = false,
    // A setup failure (tool resolution, asset deploy, handle creation) from before any handle
    // existed — the runtime's own FAILED snapshot only exists once a handle does, so without this
    // the panel silently showed "Connect to show the device" (DISCONNECTED, no error) for a real
    // failure the app already knew about (see AppState.embeddedMirrorSetupError's doc).
    setupError: String? = null,
    // Passed by CaptureCard, remembered per tab, so the typed text and the open/closed text row
    // survive the panel leaving and re-entering composition; the detached window uses its own.
    clipboardState: MirrorClipboardState? = null,
    // Live audio playback (the speaker toggle) — see LiveAudioPlayer.kt. hasAudio gates whether the
    // button shows at all (only a shared recording session with audio=true ever has one, see
    // MirrorBackend.hasLiveAudio); onToggleLiveAudio is null exactly when the caller has nowhere to
    // persist/apply the change (e.g. no handle yet), same convention as onDetach above.
    hasAudio: Boolean = false,
    liveAudioEnabled: Boolean = false,
    onToggleLiveAudio: (() -> Unit)? = null,
) {
    val colors = tc()
    val snapshot by (handle?.snapshot ?: remember { MutableStateFlow(EmbeddedMirrorSnapshot()) }).collectAsState()
    val ownClipboardState = clipboardState ?: remember { MirrorClipboardState() }
    val focusRequester = remember { FocusRequester() }
    val frame = snapshot.frame
    val frameInfo = snapshot.frameInfo
    val macSurface = handle?.macSurface
    val gpuSurface = handle?.gpuSurface
    val macOverlayOccluded by remember(macSurface) {
        macSurface?.overlayOccludedState ?: MutableStateFlow(false)
    }.collectAsState()
    val macUnderlayActive by remember(macSurface) {
        macSurface?.underlayActiveState ?: MutableStateFlow(false)
    }.collectAsState()
    val macSurfaceHostToken = remember(macSurface) { Any() }
    // The Metal layer is an AppKit sibling of Compose's scene. Its owner is this panel's actual
    // SwingPanel host, so navigation out of a capture tab detaches the layer while leaving the
    // recorder, packet pump, and decoder alive. Detached mirror windows keep their own host mounted.
    DisposableEffect(macSurface) {
        macSurface?.setHostMounted(macSurfaceHostToken, true)
        onDispose { macSurface?.setHostMounted(macSurfaceHostToken, false) }
    }
    // A pre-handle setup failure only makes sense to show while there's still no live/queued
    // connection attempt to report its own state instead.
    val effectiveError = snapshot.error ?: setupError?.takeIf { handle == null }
    val displayedState = if (effectiveError != null && snapshot.state == EmbeddedMirrorState.DISCONNECTED) {
        EmbeddedMirrorState.FAILED
    } else {
        snapshot.state
    }
    // Gates mounting the Windows/Linux native SwingPanel below: LIVE or RECONNECTING only — never
    // CONNECTING/DISCONNECTED/FAILED, even though frameInfo can still hold a previous connection's
    // last dimensions (composeLocked()/publishLocked() don't clear it just because the state moved
    // to FAILED). Without this a FAILED connection kept showing the native surface's last frame
    // frozen on screen instead of the error text below.
    val mirrorConnected = displayedState == EmbeddedMirrorState.LIVE || displayedState == EmbeddedMirrorState.RECONNECTING
    // Recompute the ImageBitmap only when a genuinely new frame arrives, not on every
    // recomposition — frame.toComposeBitmap() used to allocate a fresh BufferedImage, copy every
    // pixel via setRGB, then re-encode it as a Compose ImageBitmap, and this composable previously
    // ran that on every recomposition (dropped-frame count changing, a button's enabled state,
    // etc.), not just once per decoded frame. It now installs the frame's pixels straight into a
    // Skia Bitmap, but the memoization still matters for the same reason.
    val bitmap = remember(frame) { frame?.toComposeBitmap() }
    val frameWidth = frame?.width ?: frameInfo?.width
    val frameHeight = frame?.height ?: frameInfo?.height
    val aspectRatio = if (frameWidth != null && frameHeight != null && frameHeight > 0) {
        frameWidth.toFloat() / frameHeight.toFloat()
    } else {
        null
    }

    // SwingPanel is a heavyweight native surface, so its events do not bubble to Compose's
    // pointerInput/onPreviewKeyEvent modifiers below. Keep the same mirror-control protocol by
    // translating AWT input at that boundary; all toolbar controls remain ordinary Compose UI.
    val previewCanvas = macSurface?.canvas ?: gpuSurface?.canvas
    if (previewCanvas != null && frameWidth != null && frameHeight != null) {
        val liveHandle = requireNotNull(handle)
        val activeMacSurface = macSurface
        DisposableEffect(activeMacSurface, gpuSurface, liveHandle, frameWidth, frameHeight) {
            val canvas = previewCanvas
            val pointerId = 1L
            var activeTouch = false
            var lastTouchX = 0f
            var lastTouchY = 0f

            fun mapper(): MirrorCoordinateMapper? = canvas.width.takeIf { it > 0 }?.let { width ->
                canvas.height.takeIf { it > 0 }?.let { height -> MirrorCoordinateMapper(width, height, frameWidth, frameHeight) }
            }

            fun cancelActiveTouchOnEdt() {
                if (!activeTouch) return
                mapper()?.let {
                    liveHandle.sendTouch(it, MirrorTouchAction.CANCEL, pointerId, lastTouchX, lastTouchY)
                }
                activeTouch = false
            }

            fun cancelActiveTouch() {
                // Mouse callbacks and the mutable touch state belong to AWT's event thread.
                // Compose can dispose this effect from another dispatcher, so serialize cleanup
                // before it reads or changes the active pointer state.
                if (EventQueue.isDispatchThread()) cancelActiveTouchOnEdt()
                else EventQueue.invokeLater(::cancelActiveTouchOnEdt)
            }
            activeMacSurface?.setOverlayOcclusionListener(::cancelActiveTouch)
            val mouseListener = object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (activeMacSurface?.isOverlayOccluded == true) {
                        activeMacSurface.forwardOverlayMouseEvent(event)
                        return
                    }
                    canvas.requestFocusInWindow()
                    val x = event.x.toFloat()
                    val y = event.y.toFloat()
                    lastTouchX = x
                    lastTouchY = y
                    activeTouch = mapper()?.let { liveHandle.sendTouch(it, MirrorTouchAction.DOWN, pointerId, x, y) } == true
                }

                override fun mouseDragged(event: MouseEvent) {
                    if (activeMacSurface?.isOverlayOccluded == true) {
                        activeMacSurface.forwardOverlayMouseEvent(event)
                        return
                    }
                    if (!activeTouch) return
                    lastTouchX = event.x.toFloat()
                    lastTouchY = event.y.toFloat()
                    mapper()?.let { liveHandle.sendTouch(it, MirrorTouchAction.MOVE, pointerId, lastTouchX, lastTouchY) }
                }

                override fun mouseReleased(event: MouseEvent) {
                    if (activeMacSurface?.isOverlayOccluded == true) {
                        activeMacSurface.forwardOverlayMouseEvent(event)
                        return
                    }
                    if (!activeTouch) return
                    lastTouchX = event.x.toFloat()
                    lastTouchY = event.y.toFloat()
                    mapper()?.let {
                        liveHandle.sendTouch(it, MirrorTouchAction.UP, pointerId, lastTouchX, lastTouchY)
                    }
                    activeTouch = false
                }

                override fun mouseMoved(event: MouseEvent) {
                    if (activeMacSurface?.isOverlayOccluded == true) activeMacSurface.forwardOverlayMouseEvent(event)
                }

                override fun mouseEntered(event: MouseEvent) {
                    if (activeMacSurface?.isOverlayOccluded == true) activeMacSurface.forwardOverlayMouseEvent(event)
                }

                override fun mouseExited(event: MouseEvent) {
                    if (activeMacSurface?.isOverlayOccluded == true) activeMacSurface.forwardOverlayMouseEvent(event)
                }
            }
            val keyListener = object : KeyAdapter() {
                override fun keyPressed(event: AwtKeyEvent) {
                    if (activeMacSurface?.isOverlayOccluded == true) {
                        activeMacSurface.forwardOverlayKeyEvent(event)
                        return
                    }
                    if (event.isControlDown || event.isMetaDown || event.isAltDown) return
                    val keycode = when (event.keyCode) {
                        AwtKeyEvent.VK_ENTER -> 66
                        AwtKeyEvent.VK_BACK_SPACE -> 67
                        AwtKeyEvent.VK_LEFT -> 21
                        AwtKeyEvent.VK_RIGHT -> 22
                        AwtKeyEvent.VK_UP -> 19
                        AwtKeyEvent.VK_DOWN -> 20
                        AwtKeyEvent.VK_ESCAPE -> 111
                        else -> null
                    } ?: return
                    if (liveHandle.send(MirrorControlCommand.Key(MirrorKeyAction.DOWN, keycode))) {
                        liveHandle.send(MirrorControlCommand.Key(MirrorKeyAction.UP, keycode))
                        event.consume()
                    }
                }
            }
            val mouseWheelListener = MouseWheelListener { event ->
                if (activeMacSurface?.isOverlayOccluded == true) activeMacSurface.forwardOverlayMouseWheelEvent(event)
            }
            canvas.addMouseListener(mouseListener)
            canvas.addMouseMotionListener(mouseListener)
            canvas.addMouseWheelListener(mouseWheelListener)
            canvas.addKeyListener(keyListener)
            onDispose {
                cancelActiveTouch()
                activeMacSurface?.setOverlayOcclusionListener(null)
                canvas.removeMouseListener(mouseListener)
                canvas.removeMouseMotionListener(mouseListener)
                canvas.removeMouseWheelListener(mouseWheelListener)
                canvas.removeKeyListener(keyListener)
            }
        }
    }

    val live = snapshot.state == EmbeddedMirrorState.LIVE
    // No header row: the state word, Connect/Retry, Disconnect and detach/return all live in the
    // control bar under the surface (a 32dp "DEVICE · Live · ↗ · ⋯" header cost the picture height
    // for four small controls, and its tooltips opened over — i.e. under — the native layer).
    Column(modifier) {
        Column(
            // fill = detached: the detached window centres the picture in all of its space, while in
            // the sidebar the panel reports its natural height so CaptureCard can hand the rest of
            // the card to the markers (MirrorAboveMarkersLayout).
            Modifier.weight(1f, fill = detached).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // BoxWithConstraints (not Modifier.aspectRatio directly) so a portrait phone's height is
            // computed explicitly and capped: aspectRatio() alone derives height from the full sidebar
            // width, which for a 1080x2400 phone made the surface ~2.2x the sidebar's width tall. The
            // computed box width can end up narrower than the sidebar for a capped portrait frame — the
            // outer Center alignment below keeps it centered rather than stuck to one edge; the touch
            // mapper (mirrorTouchInput) reads the box's current measured size on every event, so it
            // stays correct for whatever size this computes, capped or not.
            // `flexible` = size the surface from the height this panel was actually given, instead of
            // a caller-supplied number. The detached window has always worked this way; the sidebar
            // now does too (see CaptureCard), because a fixed height there could not know how much of
            // the slot the header/control bar/markers/footer below already consumed — and since
            // boxWidth is derived from boxHeight * aspectRatio, guessing the height too low collapses
            // a portrait phone to a thumbnail a few tens of dp wide rather than merely making it
            // shorter. The attached control bar below the surface (item 2 of the restyle) eats into
            // that same flexible allocation, so its height is subtracted up front, not added after —
            // otherwise surface + bar together would overflow whatever space `weight(1f)` handed us.
            val flexible = detached || fillAvailableHeight
            BoxWithConstraints(
                if (flexible) Modifier.fillMaxWidth().weight(1f, fill = detached) else Modifier.fillMaxWidth(),
            ) {
                // The bar, and the text row under it while open, share the flexible allocation.
                val chromeHeight = MIRROR_CONTROL_BAR_HEIGHT + if (ownClipboardState.expanded) MIRROR_TEXT_ROW_HEIGHT else 0.dp
                val availableSurfaceHeight = if (flexible) (maxHeight - chromeHeight).coerceAtLeast(0.dp) else maxHeight
                val naturalHeight = if (aspectRatio != null) {
                    (maxWidth / aspectRatio).coerceAtMost(if (flexible) availableSurfaceHeight else MIRROR_SIDEBAR_MAX_HEIGHT)
                } else {
                    if (flexible) availableSurfaceHeight else MIRROR_DEFAULT_HEIGHT
                }
                val boxHeight = if (!flexible && sidebarSurfaceHeight != null) {
                    sidebarSurfaceHeight.coerceAtMost(naturalHeight).coerceAtLeast(MIRROR_MIN_HEIGHT.coerceAtMost(naturalHeight))
                } else {
                    naturalHeight
                }
                val boxWidth = if (aspectRatio != null) (boxHeight * aspectRatio).coerceAtMost(maxWidth) else maxWidth
                // The black frame always spans the full width and letterboxes the surface inside
                // it, so the control bar below can span the same width even when a capped portrait
                // picture is narrower than the panel. No border: the native layer draws above
                // Compose, so it covered the border's top edge but not the sides — the picture sat
                // 1dp higher than the black bars beside it, and the bottom edge left a pale seam
                // between the frame and the bar.
                Column(Modifier.align(Alignment.Center).width(maxWidth)) {
                    Box(
                        Modifier.fillMaxWidth().height(boxHeight)
                            .background(Color.Black, MIRROR_SURFACE_TOP_CORNERS),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.width(boxWidth).height(boxHeight)
                                .then(if (macUnderlayActive) MirrorUnderlayHole else Modifier)
                                .then(
                                    mirrorSurfaceModifier(
                                        handle = handle,
                                        frameWidth = frameWidth,
                                        frameHeight = frameHeight,
                                        focusRequester = focusRequester,
                                    ),
                                )
                                .onGloballyPositioned { coordinates ->
                                    val fullBounds = coordinates.boundsInWindow(clipBounds = false)
                                    macSurface?.setVisibleClip(
                                        fullBounds = fullBounds,
                                        // A detached AWT Canvas has no scroll viewport. Passing its full
                                        // bounds resets the native mask before the same layer is reparented.
                                        clippedBounds = if (detached) fullBounds else coordinates.boundsInWindow(clipBounds = true),
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (macSurface != null) {
                                if (macOverlayOccluded && !macUnderlayActive) {
                                    Column(
                                        Modifier.fillMaxSize().background(Color.Black, MIRROR_SURFACE_TOP_CORNERS).padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        AppText("Device mirror is hidden while this panel is open", color = colors.ts, fontSize = 10.sp)
                                        AppText("Capture and streaming continue.", color = colors.td, fontSize = 9.sp)
                                    }
                                } else {
                                    SwingPanel(
                                        background = Color.Transparent,
                                        factory = { macSurface.canvas },
                                        modifier = Modifier.fillMaxSize(),
                                        update = { macSurface.requestDisplay() },
                                    )
                                }
                            } else if (gpuSurface != null && frameInfo != null && mirrorConnected) {
                                // Mounted only once the direct decoder has actually presented a
                                // frame (frameInfo becomes non-null there — see
                                // HardwareH264MirrorDecoder.decode) and the connection is live:
                                // mounting this heavyweight Canvas any earlier showed a black box
                                // before the first frame and, since stop() recreates a fresh
                                // surface, after every Disconnect too. Otherwise this falls through
                                // to the placeholder text below, same as the macSurface branch above.
                                SwingPanel(
                                    background = Color.Transparent,
                                    factory = { gpuSurface.canvas },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else if (bitmap != null) {
                                Image(bitmap, "Device mirror", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            } else {
                                AppText(
                                    effectiveError ?: if (displayedState == EmbeddedMirrorState.DISCONNECTED) {
                                        "Connect to show the device"
                                    } else {
                                        "Waiting for the first frame…"
                                    },
                                    color = if (effectiveError != null) DANGER_RED else colors.td,
                                    fontSize = 10.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    MirrorControlBar(
                        handle = handle,
                        live = live,
                        clipboardState = ownClipboardState,
                        displayedState = displayedState,
                        reconnectAttempt = snapshot.reconnectAttempt,
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                        detached = detached,
                        onDetach = onDetach,
                        onReturnToSidebar = onReturnToSidebar,
                        hasAudio = hasAudio,
                        liveAudioEnabled = liveAudioEnabled,
                        onToggleLiveAudio = onToggleLiveAudio,
                    )
                    if (ownClipboardState.expanded) {
                        MirrorTextRow(handle = handle, live = live, clipboardState = ownClipboardState)
                    }
                }
            }
            if (snapshot.droppedFrames > 0) {
                AppText("Dropped ${snapshot.droppedFrames} frame(s)", color = colors.td, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private val DEVICE_HEADER_HEIGHT = 32.dp

/** Underlay only: the native layer sits *below* Compose there, so Compose must leave a transparent
 * hole over the surface or the frame's black background (and the window background) cover the
 * video permanently. Clear writes transparent pixels straight into the window surface; it only
 * reaches it if no ancestor forces an offscreen layer (alpha, graphicsLayer, shadow). */
private val MirrorUnderlayHole = Modifier.drawBehind { drawRect(Color.Black, blendMode = BlendMode.Clear) }
private val MIRROR_CONTROL_BAR_HEIGHT = 30.dp
internal val MIRROR_TEXT_ROW_HEIGHT = 38.dp
private val MIRROR_CONTROL_BAR_BG = Color(0xFF0D1117)
private val MIRROR_CONTROL_BAR_TINT = Color(0xFFE6EDF3)
private val MIRROR_STATUS_LIVE = Color(0xFF3FB950)
private val MIRROR_STATUS_PENDING = Color(0xFFD29922)
private val MIRROR_BAR_ICON_GAP = 8.dp

/** Below the button, never at the cursor: over the surface a tooltip would render under the
 * native layer on macOS. */
private val MIRROR_BAR_TOOLTIP_PLACEMENT = TooltipPlacement.ComponentRect(
    anchor = Alignment.BottomCenter,
    alignment = Alignment.BottomCenter,
    offset = DpOffset(0.dp, 4.dp),
)
private val MIRROR_SURFACE_TOP_CORNERS = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
private val MIRROR_BAR_BOTTOM_CORNERS = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)

/** Text typed for the device, shared by the bar's Paste (which falls back to the system
 * clipboard when this is empty) and the text row, plus whether that row is open. */
internal class MirrorClipboardState {
    var text by mutableStateOf("")
    var expanded by mutableStateOf(false)
}

/** Draws a single [color] line along one edge only, unlike [Modifier.border] which always draws
 * all four — used for the DEVICE header's full-bleed bottom rule and the clipboard footer's top
 * rule (TabBar.kt's active-tab underline uses the same drawBehind+drawRect technique). */
private fun Modifier.edgeBorder(color: Color, strokeWidth: Dp = 1.dp, top: Boolean): Modifier = drawBehind {
    val stroke = strokeWidth.toPx()
    val y = if (top) 0f else size.height - stroke
    drawRect(color = color, topLeft = Offset(0f, y), size = Size(size.width, stroke))
}

/** Full-bleed 32dp card header: a title on the left (SectionHeader's own title style) and
 * caller-supplied trailing content on the right, with a bottom rule instead of the surrounding
 * card's own padding — full-bleed so the rule spans the card's entire width. Only CaptureCard's
 * EXTERNAL/DISABLED/detached branches use it: an embedded, attached mirror has no header, its
 * controls live in [MirrorControlBar] so the picture gets the height. */
@Composable
internal fun DeviceHeaderRow(
    title: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = tc()
    Row(
        modifier.fillMaxWidth().height(DEVICE_HEADER_HEIGHT).edgeBorder(colors.br, top = false).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (title != null) {
            AppText(title, color = colors.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** The mirror's only chrome: one dark bar joined to the bottom of the surface frame.
 *
 * Left: a status dot (its tooltip carries the old state word — Live / Reconnecting (n)… / Failed)
 * and, when the mirror is not LIVE/RECONNECTING, the Connect/Retry link. Centre: Back, Home, Power
 * and Paste. Right: the text-row toggle ([MirrorTextRow] opens under the bar), detach/return and,
 * when LIVE/RECONNECTING, Disconnect. These are the controls, conditions and handlers of the former
 * header row, button row and clipboard footer; only placement changed.
 *
 * It is laid out below the surface, never overlaid on it: the macOS Metal layer draws above
 * Compose, so anything overlaid would be hidden. For the same reason every tooltip here is placed
 * below its button — the default cursor placement can open over the surface and render under it. */
@Composable
private fun MirrorControlBar(
    handle: EmbeddedMirrorHandle?,
    live: Boolean,
    clipboardState: MirrorClipboardState,
    displayedState: EmbeddedMirrorState,
    reconnectAttempt: Int,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    detached: Boolean,
    onDetach: (() -> Unit)?,
    onReturnToSidebar: (() -> Unit)?,
    hasAudio: Boolean = false,
    liveAudioEnabled: Boolean = false,
    onToggleLiveAudio: (() -> Unit)? = null,
) {
    val connected = displayedState == EmbeddedMirrorState.LIVE || displayedState == EmbeddedMirrorState.RECONNECTING
    Box(
        Modifier.fillMaxWidth().height(MIRROR_CONTROL_BAR_HEIGHT)
            // Square bottom while the text row is open, so the row reads as the bar's continuation.
            .background(MIRROR_CONTROL_BAR_BG, if (clipboardState.expanded) RectangleShape else MIRROR_BAR_BOTTOM_CORNERS)
            .padding(horizontal = 8.dp),
    ) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            MirrorStatusDot(displayedState, reconnectAttempt)
            if (!connected) {
                Spacer(Modifier.width(4.dp))
                MirrorBarTextLink(if (displayedState == EmbeddedMirrorState.FAILED) "Retry" else "Connect", onConnect)
            }
        }
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(MIRROR_BAR_ICON_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MirrorControlBarButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", live) {
                handle?.send(MirrorControlCommand.Back())
            }
            MirrorControlBarButton(Icons.Outlined.Home, "Home", live) { handle?.sendAndroidKey(3) }
            MirrorControlBarButton(Icons.Outlined.PowerSettingsNew, "Power", live) { handle?.sendAndroidKey(26) }
            MirrorBarDivider()
            MirrorControlBarButton(Icons.Outlined.ContentPaste, "Paste clipboard to device", live) {
                val text = clipboardState.text.ifEmpty { clipboardString().orEmpty() }
                if (text.isNotEmpty()) handle?.send(MirrorControlCommand.Clipboard(text, paste = true))
            }
            if (hasAudio && onToggleLiveAudio != null) {
                MirrorBarDivider()
                MirrorControlBarButton(
                    icon = if (liveAudioEnabled) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
                    tooltip = "Play device audio on this computer (≈0.1–0.2 s behind)",
                    enabled = live,
                    active = liveAudioEnabled,
                    onClick = onToggleLiveAudio,
                )
            }
        }
        Row(
            Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MirrorControlBarButton(
                Icons.Outlined.Keyboard,
                if (clipboardState.expanded) "Hide the text row" else "Type text to send to the device",
                enabled = true,
                active = clipboardState.expanded,
            ) { clipboardState.expanded = !clipboardState.expanded }
            if (detached) {
                onReturnToSidebar?.let {
                    MirrorControlBarButton(Icons.Outlined.CloseFullscreen, "Return the mirror to the sidebar", true, onClick = it)
                }
            } else if (onDetach != null) {
                MirrorControlBarButton(Icons.AutoMirrored.Outlined.OpenInNew, "Open the mirror in its own window", true, onClick = onDetach)
            }
            if (connected) {
                MirrorControlBarButton(Icons.Outlined.LinkOff, "Disconnect the mirror", true, onClick = onDisconnect)
            }
        }
    }
}

/** Light status dot replacing the header's state word; the word itself moves into the tooltip. */
@Composable
private fun MirrorStatusDot(state: EmbeddedMirrorState, reconnectAttempt: Int) {
    val color = when (state) {
        EmbeddedMirrorState.LIVE -> MIRROR_STATUS_LIVE
        EmbeddedMirrorState.CONNECTING, EmbeddedMirrorState.RECONNECTING -> MIRROR_STATUS_PENDING
        EmbeddedMirrorState.FAILED -> DANGER_RED
        EmbeddedMirrorState.DISCONNECTED -> MIRROR_CONTROL_BAR_TINT.copy(alpha = .35f)
    }
    TooltipArea(tooltip = { ToolbarTooltip(mirrorStateLabel(state, reconnectAttempt)) }, tooltipPlacement = MIRROR_BAR_TOOLTIP_PLACEMENT) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
        }
    }
}

@Composable
private fun MirrorBarDivider() {
    Box(Modifier.width(1.dp).height(15.dp).background(MIRROR_CONTROL_BAR_TINT.copy(alpha = .25f)))
}

/** Connect/Retry on the dark bar: the one action that matters while the mirror is down, so it
 * stays a word rather than an icon. */
@Composable
private fun MirrorBarTextLink(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier
            .background(if (hovered && enabled) MIRROR_CONTROL_BAR_TINT.copy(alpha = .12f) else Color.Transparent, CORNER_MD)
            .clip(CORNER_MD)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        AppText(
            text,
            color = if (enabled) MIRROR_CONTROL_BAR_TINT else MIRROR_CONTROL_BAR_TINT.copy(alpha = .35f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MirrorControlBarButton(
    icon: ImageVector,
    tooltip: String,
    enabled: Boolean,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val tint = if (enabled) MIRROR_CONTROL_BAR_TINT else MIRROR_CONTROL_BAR_TINT.copy(alpha = .35f)
    TooltipArea(tooltip = { ToolbarTooltip(tooltip) }, tooltipPlacement = MIRROR_BAR_TOOLTIP_PLACEMENT) {
        Box(
            Modifier.size(22.dp)
                .background(
                    when {
                        active -> MIRROR_CONTROL_BAR_TINT.copy(alpha = .18f)
                        hovered && enabled -> MIRROR_CONTROL_BAR_TINT.copy(alpha = .12f)
                        else -> Color.Transparent
                    },
                    CORNER_MD,
                )
                .clip(CORNER_MD)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = tooltip, tint = tint, modifier = Modifier.size(16.dp))
        }
    }
}

/** The bar's expandable second row: a text field and Send, in the bar's own dark style, opened by
 * the bar's keyboard button. Send (or Enter) sets the device clipboard to the typed text, exactly
 * what the old footer's Send did, and is live under the same condition. */
@Composable
private fun MirrorTextRow(handle: EmbeddedMirrorHandle?, live: Boolean, clipboardState: MirrorClipboardState) {
    val canSend = live && clipboardState.text.isNotEmpty()
    val send = { if (canSend) handle?.send(MirrorControlCommand.Clipboard(clipboardState.text)) }
    Row(
        Modifier.fillMaxWidth().height(MIRROR_TEXT_ROW_HEIGHT)
            .background(MIRROR_CONTROL_BAR_BG, MIRROR_BAR_BOTTOM_CORNERS)
            .drawBehind {
                // Hairline between the bar and this row.
                drawRect(MIRROR_CONTROL_BAR_TINT.copy(alpha = .12f), size = Size(size.width, 1.dp.toPx()))
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = clipboardState.text,
            onValueChange = { clipboardState.text = it },
            singleLine = true,
            textStyle = TextStyle(color = MIRROR_CONTROL_BAR_TINT, fontSize = 11.sp, fontFamily = LocalUiFontFamily.current),
            cursorBrush = SolidColor(MIRROR_CONTROL_BAR_TINT),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f)
                .background(MIRROR_CONTROL_BAR_TINT.copy(alpha = .08f), CORNER_MD)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            decorationBox = { inner ->
                if (clipboardState.text.isEmpty()) {
                    AppText("Text to send to the device", color = MIRROR_CONTROL_BAR_TINT.copy(alpha = .45f), fontSize = 11.sp)
                }
                inner()
            },
        )
        MirrorBarTextLink("Send", enabled = canSend, onClick = { send() })
    }
}

/**
 * Detached windows live at App scope rather than inside FileView: FileView is keyed to the active
 * tab, and disposing it during a tab switch must not dispose the sole SwingPanel hosting the
 * running Metal Canvas. There is still exactly one [EmbeddedMirrorPanel] for a tab at a time.
 */
@Composable
internal fun DetachedEmbeddedMirrorWindows(state: AppState) {
    state.detachedEmbeddedMirrorTabs().forEach { tab ->
        androidx.compose.runtime.key(tab.id) {
            DetachedEmbeddedMirrorWindow(state, tab)
        }
    }
}

@Composable
private fun DetachedEmbeddedMirrorWindow(state: AppState, tab: LogTab) {
    Window(
        onCloseRequest = { state.returnEmbeddedMirrorToSidebar(tab.id) },
        title = "Device mirror — ${tab.filename.removePrefix("Capture — ")}",
        state = rememberWindowState(size = DpSize(620.dp, 760.dp)),
        resizable = true,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalMirrorOverlayAppState provides state,
            LocalMirrorOverlayTabId provides tab.id,
        ) {
            val detachedMirror = state.embeddedMirrorFor(tab.id)
            EmbeddedMirrorPanel(
                handle = detachedMirror,
                setupError = state.embeddedMirrorSetupError(tab.id),
                onConnect = { state.openCaptureMirror(tab.id) },
                onDisconnect = { state.stopEmbeddedMirror(tab.id) },
                detached = true,
                onReturnToSidebar = { state.returnEmbeddedMirrorToSidebar(tab.id) },
                // The header row is full-bleed (see DeviceHeaderRow's doc); the panel's own inner
                // content Column applies the 12dp inset below it, so this outer modifier must not
                // pad the header too.
                modifier = Modifier.fillMaxSize().background(tc().p),
                hasAudio = detachedMirror?.hasLiveAudio == true,
                liveAudioEnabled = state.settings.captureSettings.playAudioLive,
                onToggleLiveAudio = { state.setEmbeddedMirrorLiveAudioEnabled(tab.id, !state.settings.captureSettings.playAudioLive) },
            )
        }
    }
}

private fun EmbeddedMirrorHandle.sendAndroidKey(keycode: Int): Boolean =
    send(MirrorControlCommand.Key(MirrorKeyAction.DOWN, keycode)) &&
        send(MirrorControlCommand.Key(MirrorKeyAction.UP, keycode))

private fun mirrorSurfaceModifier(
    handle: EmbeddedMirrorHandle?,
    frameWidth: Int?,
    frameHeight: Int?,
    focusRequester: FocusRequester,
): Modifier {
    val interaction = Modifier
        .focusRequester(focusRequester)
        .focusable()
        .onPreviewKeyEvent { event -> handleMirrorKeyEvent(handle, event) }
    if (handle == null || frameWidth == null || frameHeight == null) return interaction
    return interaction.mirrorTouchInput(handle, frameWidth, frameHeight) { mapper, action, pointerId, x, y ->
        handle.sendTouch(mapper, action, pointerId, x, y)
    }
}

/**
 * Turns pointer input on this element into mirror touches. The viewport is the element's CURRENT
 * size, read from [PointerInputScope.size] on every event: the surface box resizes whenever the
 * frame's aspect ratio arrives or changes (a landscape device), the window or sidebar is resized,
 * or the device rotates. This used to build one mapper from a size captured by a plain local
 * variable when the gesture handler started, so after any resize taps were converted against the
 * old dimensions and landed shifted or scaled toward a corner (and a handler restarted after a
 * recomposition saw a zero size and dropped touches entirely).
 */
internal fun Modifier.mirrorTouchInput(
    key: Any?,
    frameWidth: Int,
    frameHeight: Int,
    onTouch: (mapper: MirrorCoordinateMapper, action: MirrorTouchAction, pointerId: Long, x: Float, y: Float) -> Unit,
): Modifier = pointerInput(key, frameWidth, frameHeight) {
    trackMirrorTouchGestures(frameWidth, frameHeight, onTouch)
}

/** Maps a keydown to the Android keycode scrcpy expects and forwards a DOWN+UP pair. Split out
 * of [mirrorSurfaceModifier] to keep it under detekt's cyclomatic-complexity threshold; same
 * modifier guard and keycode table as before. */
private fun handleMirrorKeyEvent(handle: EmbeddedMirrorHandle?, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return false
    val live = handle ?: return false
    val keycode = when (event.key) {
        Key.Enter -> 66
        Key.Backspace -> 67
        Key.DirectionLeft -> 21
        Key.DirectionRight -> 22
        Key.DirectionUp -> 19
        Key.DirectionDown -> 20
        Key.Escape -> 111
        else -> null
    } ?: return false
    return if (live.send(MirrorControlCommand.Key(MirrorKeyAction.DOWN, keycode))) {
        live.send(MirrorControlCommand.Key(MirrorKeyAction.UP, keycode))
        true
    } else {
        false
    }
}

/** Pumps raw pointer events into scrcpy touch commands for the lifetime of the enclosing
 * pointerInput block. Split out of [mirrorTouchInput] to keep it under detekt's cyclomatic-
 * complexity threshold; same DOWN/MOVE/UP/CANCEL sequencing as before. */
private suspend fun PointerInputScope.trackMirrorTouchGestures(
    frameWidth: Int,
    frameHeight: Int,
    onTouch: (mapper: MirrorCoordinateMapper, action: MirrorTouchAction, pointerId: Long, x: Float, y: Float) -> Unit,
) {
    awaitPointerEventScope {
        // `size` is this element's current measured size; a fresh mapper per event keeps the
        // conversion right across any resize (see mirrorTouchInput's doc).
        fun currentMapper(): MirrorCoordinateMapper? = size.takeIf { it.width > 0 && it.height > 0 }
            ?.let { MirrorCoordinateMapper(it.width, it.height, frameWidth, frameHeight) }
        var pointer: PointerId? = null
        var downX = 0f
        var downY = 0f
        try {
            while (true) {
                val event = awaitPointerEvent()
                // Null only before the element has been measured; nothing to map yet.
                val mapper = currentMapper()
                val active = pointer
                if (mapper == null) {
                    Unit
                } else if (active == null) {
                    event.changes.firstOrNull { it.changedToDown() }?.let { down ->
                        pointer = down.id
                        downX = down.position.x
                        downY = down.position.y
                        onTouch(mapper, MirrorTouchAction.DOWN, down.id.value, downX, downY)
                    }
                } else {
                    event.changes.firstOrNull { it.id == active }?.let { change ->
                        if (change.pressed && event.type == PointerEventType.Move) {
                            onTouch(mapper, MirrorTouchAction.MOVE, change.id.value, change.position.x, change.position.y)
                        } else if (!change.pressed) {
                            onTouch(mapper, MirrorTouchAction.UP, change.id.value, change.position.x, change.position.y)
                            pointer = null
                        }
                    }
                }
            }
        } finally {
            val active = pointer
            val mapper = currentMapper()
            if (active != null && mapper != null) onTouch(mapper, MirrorTouchAction.CANCEL, active.value, downX, downY)
        }
    }
}
