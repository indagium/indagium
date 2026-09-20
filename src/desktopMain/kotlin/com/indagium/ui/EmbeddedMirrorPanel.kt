package com.indagium.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.capture.mirror.EmbeddedMirrorConnection
import com.indagium.capture.mirror.EmbeddedMirrorRuntime
import com.indagium.capture.mirror.EmbeddedMirrorSnapshot
import com.indagium.capture.mirror.EmbeddedMirrorState
import com.indagium.capture.mirror.H264Decoder
import com.indagium.capture.mirror.JavaCvH264Decoder
import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorCoordinateMapper
import com.indagium.capture.mirror.MirrorFrame
import com.indagium.capture.mirror.MirrorKeyAction
import com.indagium.capture.mirror.MirrorStreamOptions
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.capture.mirror.EmbeddedMirrorTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import com.indagium.capture.CaptureProcessRunner
import com.indagium.capture.CaptureTools
import com.indagium.capture.ProcessBuilderCaptureRunner
import com.indagium.capture.mirror.AdbScrcpyTransport

/**
 * UI-owned adapter around the transport-neutral embedded mirror runtime. The runtime deliberately
 * has no Compose dependency; this small bridge turns snapshots into a StateFlow and keeps the
 * device/control API out of CaptureStrip's layout code.
 */
internal class EmbeddedMirrorHandle(
    private val runtime: EmbeddedMirrorRuntime,
) : Closeable {
    private val _snapshot = MutableStateFlow(runtime.snapshot())
    val snapshot: StateFlow<EmbeddedMirrorSnapshot> = _snapshot

    fun start(serial: String, options: MirrorStreamOptions) {
        val current = runtime.snapshot()
        if (current.deviceSerial == serial && current.state in setOf(
                EmbeddedMirrorState.CONNECTING,
                EmbeddedMirrorState.LIVE,
                EmbeddedMirrorState.RECONNECTING,
            )) return
        runtime.start(serial, options)
        _snapshot.value = runtime.snapshot()
    }

    fun stop() {
        runtime.stop()
        _snapshot.value = runtime.snapshot()
    }

    fun send(command: MirrorControlCommand): Boolean = runtime.send(command)

    fun sendTouch(
        mapper: MirrorCoordinateMapper,
        action: MirrorTouchAction,
        pointerId: Long,
        viewportX: Float,
        viewportY: Float,
    ): Boolean = runtime.sendTouch(mapper, action, pointerId, viewportX, viewportY)

    override fun close() {
        runtime.close()
        _snapshot.value = runtime.snapshot()
    }

    companion object {
        fun create(tools: CaptureTools, root: File): EmbeddedMirrorHandle {
            lateinit var handle: EmbeddedMirrorHandle
            val runtime = EmbeddedMirrorRuntime(
                transport = AdbScrcpyTransport(
                    tools = tools,
                    runner = ProcessBuilderCaptureRunner(),
                    localRoot = root,
                ),
                decoder = JavaCvH264Decoder(),
                listener = { snapshot -> handle._snapshot.value = snapshot },
            )
            handle = EmbeddedMirrorHandle(runtime)
            return handle
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

private fun mirrorStateLabel(snapshot: EmbeddedMirrorSnapshot): String = when (snapshot.state) {
    EmbeddedMirrorState.DISCONNECTED -> "Disconnected"
    EmbeddedMirrorState.CONNECTING -> "Connecting…"
    EmbeddedMirrorState.LIVE -> "Live"
    EmbeddedMirrorState.RECONNECTING -> "Reconnecting (${snapshot.reconnectAttempt})…"
    EmbeddedMirrorState.FAILED -> "Failed"
}

/** Right-panel embedded device surface. The panel owns no recorder and never opens a native window. */
@Composable
internal fun EmbeddedMirrorPanel(
    handle: EmbeddedMirrorHandle?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = tc()
    val snapshot by (handle?.snapshot ?: remember { MutableStateFlow(EmbeddedMirrorSnapshot()) }).collectAsState()
    var clipboard by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val frame = snapshot.frame

    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppText("Embedded mirror", color = colors.tx, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            AppText(
                mirrorStateLabel(snapshot), color = when (snapshot.state) {
                    EmbeddedMirrorState.LIVE -> colors.ok
                    EmbeddedMirrorState.FAILED -> DANGER_RED
                    else -> colors.ts
                }, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            )
            if (snapshot.state == EmbeddedMirrorState.LIVE || snapshot.state == EmbeddedMirrorState.RECONNECTING) {
                AppButton("Disconnect", onDisconnect, ButtonVariant.Ghost, horizontalPadding = 5.dp)
            } else {
                AppButton("Connect", onConnect, ButtonVariant.Secondary, horizontalPadding = 6.dp)
            }
        }
        Box(
            Modifier.fillMaxWidth().height(220.dp)
                .background(Color.Black, RoundedCornerShape(8.dp))
                .border(1.dp, colors.br, RoundedCornerShape(8.dp))
                .then(
                    mirrorSurfaceModifier(
                        handle = handle,
                        frame = frame,
                        focusRequester = focusRequester,
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (frame != null) {
                Image(frame.toComposeBitmap(), "Device mirror", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            } else {
                AppText(
                    snapshot.error ?: if (snapshot.state == EmbeddedMirrorState.DISCONNECTED) {
                        "Connect to show the device"
                    } else {
                        "Waiting for the first frame…"
                    },
                    color = if (snapshot.error != null) DANGER_RED else colors.td,
                    fontSize = 10.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AppButton("Back", { handle?.send(MirrorControlCommand.Back()) }, ButtonVariant.Secondary, enabled = snapshot.state == EmbeddedMirrorState.LIVE, horizontalPadding = 6.dp)
            AppButton("Home", { handle?.sendAndroidKey(3) }, ButtonVariant.Secondary, enabled = snapshot.state == EmbeddedMirrorState.LIVE, horizontalPadding = 6.dp)
            AppButton("Power", { handle?.sendAndroidKey(26) }, ButtonVariant.Secondary, enabled = snapshot.state == EmbeddedMirrorState.LIVE, horizontalPadding = 6.dp)
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
    frame: MirrorFrame?,
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
            } else false
        }
    if (handle == null || frame == null) return interaction
    return interaction.pointerInput(handle, frame.width, frame.height, size) {
        if (size.width <= 0 || size.height <= 0) return@pointerInput
        val mapper = MirrorCoordinateMapper(size.width, size.height, frame.width, frame.height)
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
