package com.indagium.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.input.key.Key
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.EventQueue
import com.indagium.capture.CaptureTools
import com.indagium.capture.ProcessBuilderCaptureRunner
import com.indagium.debug.AppLogger
import com.indagium.capture.mirror.AdbScrcpyTransport
import com.indagium.capture.mirror.EmbeddedDeviceSession
import com.indagium.capture.mirror.EmbeddedMirrorRuntime
import com.indagium.capture.mirror.EmbeddedMirrorSnapshot
import com.indagium.capture.mirror.EmbeddedMirrorState
import com.indagium.capture.mirror.H264Decoder
import com.indagium.capture.mirror.MacVideoToolboxMirrorDecoder
import com.indagium.capture.mirror.JavaCvH264Decoder
import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorCoordinateMapper
import com.indagium.capture.mirror.MirrorFrame
import com.indagium.capture.mirror.MirrorKeyAction
import com.indagium.capture.mirror.MirrorStreamOptions
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.capture.mirror.ScrcpyControlEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent as AwtKeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UI-owned adapter around either a standalone [EmbeddedMirrorRuntime] (its own embedded scrcpy
 * server — used only when the capture isn't recording video) or, when it is, a shared view onto
 * the *recording's own* [EmbeddedDeviceSession] (`attachDecoder`/`detachDecoder`/`sendControl` —
 * see [MirrorBackend.SharedRecordingSession]) so mirroring never opens a second device encoder.
 * Either way this class turns connection/frame state into one StateFlow and keeps the device/
 * control API out of CaptureStrip's layout code.
 */
internal class EmbeddedMirrorHandle private constructor(
    private val backend: MirrorBackend,
) : Closeable {
    private val _snapshot = MutableStateFlow(backend.snapshot())
    val snapshot: StateFlow<EmbeddedMirrorSnapshot> = _snapshot

    /** Non-null only for the macOS mirror-only VideoToolbox/Metal path. */
    internal val macSurface: EmbeddedMirrorMacSurface? get() = backend.macSurface

    fun start(serial: String, options: MirrorStreamOptions) {
        // Idempotency is backend-specific: a standalone runtime's own connection state (device
        // serial + CONNECTING/LIVE/RECONNECTING) tells us whether calling start() again would be
        // redundant. A shared backend's connection state instead reflects the *recording's* own
        // session — already LIVE well before any decoder is ever attached — so it can't be used the
        // same way; SharedRecordingSession.start() has its own "decoder already attached" guard.
        if (backend.isAlreadyStarted(serial)) return
        backend.start(serial, options)
        _snapshot.value = backend.snapshot()
    }

    /** For a shared backend this only detaches the mirror decoder — the recording session itself
     * is never stopped or disturbed (see [MirrorBackend.SharedRecordingSession.stop]). */
    fun stop() {
        backend.stop()
        _snapshot.value = backend.snapshot()
    }

    fun send(command: MirrorControlCommand): Boolean = backend.send(command)

    fun sendTouch(
        mapper: MirrorCoordinateMapper,
        action: MirrorTouchAction,
        pointerId: Long,
        viewportX: Float,
        viewportY: Float,
    ): Boolean {
        val point = mapper.map(viewportX, viewportY) ?: return false
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

    override fun close() {
        backend.close()
        _snapshot.value = backend.snapshot()
    }

    companion object {
        /**
         * [sharedSession] is the *recording's* own [EmbeddedDeviceSession], when video recording
         * is currently running for this tab (`TabCaptureController.activeEmbeddedSession()`); null
         * for a mirror-only capture (`recordVideo=false`) or when no capture is recording. Passing
         * a session wires [MirrorBackend.SharedRecordingSession] instead of opening a second
         * embedded scrcpy server — see that class's doc.
         */
        fun create(tools: CaptureTools, root: File, sharedSession: EmbeddedDeviceSession? = null): EmbeddedMirrorHandle =
            if (sharedSession != null) {
                // Recording video already owns the device session. Keep this path on the tested
                // Compose decoder; only mirror-only captures are eligible for the VideoToolbox/Metal path.
                createShared(sharedSession)
            } else if (shouldUseMacNativeMirror()) {
                createMacNativeOrCompose(tools, root)
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

        private fun createMacNativeOrCompose(tools: CaptureTools, root: File): EmbeddedMirrorHandle {
            var surface: EmbeddedMirrorMacSurface? = null
            return try {
                lateinit var backend: MirrorBackend.StandaloneRuntime
                surface = EmbeddedMirrorMacSurface(
                    onDiagnostic = { diagnostic -> AppLogger.warn("embedded-mirror", diagnostic) },
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
                        AppLogger.warn("embedded-mirror", "mode=videotoolbox-metal status=failed; switching the existing connection to Compose at the next key frame", failure)
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

        internal fun createShared(session: EmbeddedDeviceSession, decoder: H264Decoder = JavaCvH264Decoder()): EmbeddedMirrorHandle {
            lateinit var handle: EmbeddedMirrorHandle
            val backend = MirrorBackend.SharedRecordingSession(session, decoder) { snapshot -> handle._snapshot.value = snapshot }
            handle = EmbeddedMirrorHandle(backend)
            return handle
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

/** What [EmbeddedMirrorHandle] drives — either its own standalone transport, or a shared view onto
 * a live recording's device stream. See each implementation's doc. */
internal sealed interface MirrorBackend : Closeable {
    val macSurface: EmbeddedMirrorMacSurface? get() = null

    fun snapshot(): EmbeddedMirrorSnapshot

    fun start(serial: String, options: MirrorStreamOptions)

    fun stop()

    fun send(command: MirrorControlCommand): Boolean

    /** Whether calling [start] again for [serial] right now would be redundant — see
     * [EmbeddedMirrorHandle.start]'s doc for why this can't be answered the same way for both
     * backends. */
    fun isAlreadyStarted(serial: String): Boolean

    /** The pre-redesign path: opens its own embedded scrcpy server/device encoder. Used only when
     * the capture isn't recording video — see [EmbeddedMirrorHandle.create]'s doc. */
    class StandaloneRuntime(
        private var runtime: EmbeddedMirrorRuntime,
        override var macSurface: EmbeddedMirrorMacSurface? = null,
    ) : MirrorBackend {
        private val lock = Any()
        private var startedSerial: String? = null
        private var startedOptions: MirrorStreamOptions? = null
        private var switchedToCompose = false

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

        override fun close() {
            runtime.close()
            macSurface?.close()
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
        private val onSnapshotChanged: (EmbeddedMirrorSnapshot) -> Unit,
    ) : MirrorBackend {
        private val lock = Any()
        private var connectionSnapshot = session.connectionSnapshot()
        private var lastFrame: MirrorFrame? = null
        private var attached = false
        private val connectionListener = session.addConnectionListener { snapshot ->
            val next = synchronized(lock) {
                connectionSnapshot = snapshot
                composeLocked()
            }
            onSnapshotChanged(next)
        }

        override fun snapshot(): EmbeddedMirrorSnapshot = synchronized(lock) { composeLocked() }

        private fun composeLocked(): EmbeddedMirrorSnapshot = connectionSnapshot.copy(frame = lastFrame)

        /** Ignores [serial] — the shared session's own connection state (already LIVE, well before
         * any decoder ever attaches) says nothing about whether a decoder is attached; only
         * [attached] does. */
        override fun isAlreadyStarted(serial: String): Boolean = synchronized(lock) { attached }

        /** [serial]/[options] are ignored — the shared session is already connected under its own
         * recording options; this only attaches a decoder, at most once. */
        override fun start(serial: String, options: MirrorStreamOptions) {
            if (synchronized(lock) { attached }) return
            synchronized(lock) { attached = true }
            session.attachDecoder(decoder) { frame ->
                val next = synchronized(lock) {
                    lastFrame = frame
                    composeLocked()
                }
                onSnapshotChanged(next)
            }
        }

        override fun stop() {
            synchronized(lock) {
                attached = false
                lastFrame = null
            }
            session.detachDecoder()
        }

        override fun send(command: MirrorControlCommand): Boolean =
            session.sendControl(ScrcpyControlEncoder.encode(command))

        /** Detaches the decoder and stops listening for the session's connection state — never
         * stops or closes the shared recording session itself. */
        override fun close() {
            stop()
            connectionListener.close()
        }
    }
}

private fun clipboardString(): String? = runCatching {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) null
    else clipboard.getData(DataFlavor.stringFlavor) as? String
}.getOrNull()

private fun MirrorFrame.toComposeBitmap(): androidx.compose.ui.graphics.ImageBitmap =
    BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also {
        it.setRGB(0, 0, width, height, pixelsArgb, 0, width)
    }.toComposeImageBitmap()

/** Cap for a portrait phone's mirror surface — without it, a 1080x2400 device rendered at the full
 * sidebar width would be roughly 2.2x that width tall. */
private val MIRROR_MAX_HEIGHT = 420.dp
private val MIRROR_DEFAULT_HEIGHT = 220.dp

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
    // A setup failure (tool resolution, asset deploy, handle creation) from before any handle
    // existed — the runtime's own FAILED snapshot only exists once a handle does, so without this
    // the panel silently showed "Connect to show the device" (DISCONNECTED, no error) for a real
    // failure the app already knew about (see AppState.embeddedMirrorSetupError's doc).
    setupError: String? = null,
) {
    val colors = tc()
    val snapshot by (handle?.snapshot ?: remember { MutableStateFlow(EmbeddedMirrorSnapshot()) }).collectAsState()
    var clipboard by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val frame = snapshot.frame
    val frameInfo = snapshot.frameInfo
    val macSurface = handle?.macSurface
    // A pre-handle setup failure only makes sense to show while there's still no live/queued
    // connection attempt to report its own state instead.
    val effectiveError = snapshot.error ?: setupError?.takeIf { handle == null }
    val displayedState = if (effectiveError != null && snapshot.state == EmbeddedMirrorState.DISCONNECTED) {
        EmbeddedMirrorState.FAILED
    } else {
        snapshot.state
    }
    // Recompute the ImageBitmap only when a genuinely new frame arrives, not on every
    // recomposition — frame.toComposeBitmap() allocates a fresh BufferedImage, copies every pixel
    // via setRGB, then re-encodes it as a Compose ImageBitmap, and this composable previously ran
    // that on every recomposition (dropped-frame count changing, a button's enabled state, etc.),
    // not just once per decoded frame.
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
    if (macSurface != null && frameWidth != null && frameHeight != null) {
        val liveHandle = handle ?: return
        DisposableEffect(macSurface, liveHandle, frameWidth, frameHeight) {
            val canvas = macSurface.canvas
            val pointerId = 1L
            fun mapper(): MirrorCoordinateMapper? = canvas.width.takeIf { it > 0 }?.let { width ->
                canvas.height.takeIf { it > 0 }?.let { height -> MirrorCoordinateMapper(width, height, frameWidth, frameHeight) }
            }
            val mouseListener = object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    canvas.requestFocusInWindow()
                    mapper()?.let { liveHandle.sendTouch(it, MirrorTouchAction.DOWN, pointerId, event.x.toFloat(), event.y.toFloat()) }
                }

                override fun mouseDragged(event: MouseEvent) {
                    mapper()?.let { liveHandle.sendTouch(it, MirrorTouchAction.MOVE, pointerId, event.x.toFloat(), event.y.toFloat()) }
                }

                override fun mouseReleased(event: MouseEvent) {
                    mapper()?.let { liveHandle.sendTouch(it, MirrorTouchAction.UP, pointerId, event.x.toFloat(), event.y.toFloat()) }
                }
            }
            val keyListener = object : KeyAdapter() {
                override fun keyPressed(event: AwtKeyEvent) {
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
            canvas.addMouseListener(mouseListener)
            canvas.addMouseMotionListener(mouseListener)
            canvas.addKeyListener(keyListener)
            onDispose {
                canvas.removeMouseListener(mouseListener)
                canvas.removeMouseMotionListener(mouseListener)
                canvas.removeKeyListener(keyListener)
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppText("Embedded mirror", color = colors.tx, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            AppText(
                mirrorStateLabel(displayedState, snapshot.reconnectAttempt), color = when (displayedState) {
                    EmbeddedMirrorState.LIVE -> colors.ok
                    EmbeddedMirrorState.FAILED -> DANGER_RED
                    else -> colors.ts
                }, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            )
            if (displayedState == EmbeddedMirrorState.LIVE || displayedState == EmbeddedMirrorState.RECONNECTING) {
                AppButton("Disconnect", onDisconnect, ButtonVariant.Ghost, horizontalPadding = 5.dp)
            } else {
                AppButton(
                    if (displayedState == EmbeddedMirrorState.FAILED) "Retry" else "Connect",
                    onConnect,
                    ButtonVariant.Secondary,
                    horizontalPadding = 6.dp,
                )
            }
        }
        // BoxWithConstraints (not Modifier.aspectRatio directly) so a portrait phone's height is
        // computed explicitly and capped: aspectRatio() alone derives height from the full sidebar
        // width, which for a 1080x2400 phone made the surface ~2.2x the sidebar's width tall. The
        // computed box width can end up narrower than the sidebar for a capped portrait frame — the
        // outer Center alignment below keeps it centered rather than stuck to one edge; the touch
        // mapper (mirrorSurfaceModifier) reads the box's real measured size via onSizeChanged, so it
        // stays correct for whatever size this computes, capped or not.
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val boxHeight = if (aspectRatio != null) {
                (maxWidth / aspectRatio).coerceAtMost(MIRROR_MAX_HEIGHT)
            } else {
                MIRROR_DEFAULT_HEIGHT
            }
            val boxWidth = if (aspectRatio != null) (boxHeight * aspectRatio).coerceAtMost(maxWidth) else maxWidth
            Box(
                Modifier.width(boxWidth).height(boxHeight)
                    .background(Color.Black, RoundedCornerShape(8.dp))
                    .border(1.dp, colors.br, RoundedCornerShape(8.dp))
                    .then(
                        mirrorSurfaceModifier(
                            handle = handle,
                            frameWidth = frameWidth,
                            frameHeight = frameHeight,
                            focusRequester = focusRequester,
                        ),
                    )
                    .onGloballyPositioned { coordinates ->
                        macSurface?.setVisibleClip(
                            fullBounds = coordinates.boundsInWindow(clipBounds = false),
                            clippedBounds = coordinates.boundsInWindow(clipBounds = true),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (macSurface != null) {
                    SwingPanel(
                        background = Color.Transparent,
                        factory = { macSurface.canvas },
                        modifier = Modifier.fillMaxSize(),
                        update = { macSurface.requestDisplay() },
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AppButton(
                "Back", { handle?.send(MirrorControlCommand.Back()) }, ButtonVariant.Secondary,
                enabled = snapshot.state == EmbeddedMirrorState.LIVE, horizontalPadding = 6.dp,
            )
            AppButton(
                "Home", { handle?.sendAndroidKey(3) }, ButtonVariant.Secondary,
                enabled = snapshot.state == EmbeddedMirrorState.LIVE, horizontalPadding = 6.dp,
            )
            AppButton(
                "Power", { handle?.sendAndroidKey(26) }, ButtonVariant.Secondary,
                enabled = snapshot.state == EmbeddedMirrorState.LIVE, horizontalPadding = 6.dp,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            InlineField(
                value = clipboard,
                onValue = { clipboard = it },
                placeholder = "Clipboard text",
                modifier = Modifier.weight(1f),
                fontSize = 10.sp,
            )
            AppButton(
                "Send", { handle?.send(MirrorControlCommand.Clipboard(clipboard)) },
                ButtonVariant.Secondary, enabled = snapshot.state == EmbeddedMirrorState.LIVE && clipboard.isNotEmpty(), horizontalPadding = 6.dp,
            )
            AppButton(
                "Paste", {
                    val text = clipboard.ifEmpty { clipboardString().orEmpty() }
                    if (text.isNotEmpty()) handle?.send(MirrorControlCommand.Clipboard(text, paste = true))
                },
                ButtonVariant.Secondary, enabled = snapshot.state == EmbeddedMirrorState.LIVE, horizontalPadding = 6.dp,
            )
        }
        if (snapshot.droppedFrames > 0) {
            AppText("Dropped ${snapshot.droppedFrames} frame(s)", color = colors.td, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
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
    var size = IntSize.Zero
    val interaction = Modifier
        .focusRequester(focusRequester)
        .focusable()
        .onSizeChanged { size = it }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return@onPreviewKeyEvent false
            val live = handle ?: return@onPreviewKeyEvent false
            val keycode = when (event.key) {
                Key.Enter -> 66
                Key.Backspace -> 67
                Key.DirectionLeft -> 21
                Key.DirectionRight -> 22
                Key.DirectionUp -> 19
                Key.DirectionDown -> 20
                Key.Escape -> 111
                else -> null
            } ?: return@onPreviewKeyEvent false
            if (live.send(MirrorControlCommand.Key(MirrorKeyAction.DOWN, keycode))) {
                live.send(MirrorControlCommand.Key(MirrorKeyAction.UP, keycode))
                true
            } else {
                false
            }
        }
    if (handle == null || frameWidth == null || frameHeight == null) return interaction
    return interaction.pointerInput(handle, frameWidth, frameHeight, size) {
        if (size.width <= 0 || size.height <= 0) return@pointerInput
        val mapper = MirrorCoordinateMapper(size.width, size.height, frameWidth, frameHeight)
        awaitPointerEventScope {
            var pointer: PointerId? = null
            var downX = 0f
            var downY = 0f
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (pointer == null) {
                        val down = event.changes.firstOrNull { it.changedToDown() } ?: continue
                        pointer = down.id
                        downX = down.position.x
                        downY = down.position.y
                        handle.sendTouch(mapper, MirrorTouchAction.DOWN, down.id.value, downX, downY)
                    } else {
                        val change = event.changes.firstOrNull { it.id == pointer } ?: continue
                        if (change.pressed && event.type == PointerEventType.Move) {
                            handle.sendTouch(mapper, MirrorTouchAction.MOVE, change.id.value, change.position.x, change.position.y)
                        } else if (!change.pressed) {
                            handle.sendTouch(mapper, MirrorTouchAction.UP, change.id.value, change.position.x, change.position.y)
                            pointer = null
                        }
                    }
                }
            } finally {
                pointer?.let { handle.sendTouch(mapper, MirrorTouchAction.CANCEL, it.value, downX, downY) }
            }
        }
    }
}
