@file:Suppress("TooGenericExceptionCaught")

package com.indagium.ui

import com.indagium.capture.mirror.MacVideoToolboxMirrorNative
import androidx.compose.ui.geometry.Rect
import java.awt.Canvas
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.Closeable
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/** Visible portion of the native mirror, expressed as fractions of its full Compose bounds. */
internal data class MirrorClipFractions(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val isVisible get() = right > left && bottom > top
}

internal fun mirrorClipFractions(full: Rect, clipped: Rect): MirrorClipFractions {
    if (full.width <= 0f || full.height <= 0f || clipped.width <= 0f || clipped.height <= 0f) {
        return MirrorClipFractions(0f, 0f, 0f, 0f)
    }
    val left = ((clipped.left - full.left) / full.width).coerceIn(0f, 1f)
    val top = ((clipped.top - full.top) / full.height).coerceIn(0f, 1f)
    val right = ((clipped.right - full.left) / full.width).coerceIn(0f, 1f)
    val bottom = ((clipped.bottom - full.top) / full.height).coerceIn(0f, 1f)
    return if (right > left && bottom > top) {
        MirrorClipFractions(left, top, right, bottom)
    } else {
        MirrorClipFractions(0f, 0f, 0f, 0f)
    }
}

/**
 * Heavyweight Canvas hosted by Compose SwingPanel. The native bridge attaches a CAMetalLayer to
 * this Canvas through macOS JAWT surface layers and presents on its own Metal queue. AWT only
 * reports resize events; neither the packet reader nor VideoToolbox decode waits on it.
 */
internal class EmbeddedMirrorMacSurface(
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    val canvas = Canvas()
    private val native: MacVideoToolboxMirrorNative
    @Volatile private var closed = false
    private var lastNativeHierarchy: String? = null
    private var lastVisibleClip: MirrorClipFractions? = null
    private var lastVisibleClipSize: Pair<Float, Float>? = null
    private val resizeListener = object : ComponentAdapter() {
        override fun componentShown(event: ComponentEvent) { attachAndResize() }
        override fun componentResized(event: ComponentEvent) = resize()
    }

    init {
        check(!GraphicsEnvironment.isHeadless()) { "Metal mirror requires an AWT display; running headless" }
        native = MacVideoToolboxMirrorNative(canvas)
        canvas.addComponentListener(resizeListener)
    }

    fun decode(
        bytes: ByteArray,
        count: Int,
        ptsUs: Long,
        config: Boolean,
        keyFrame: Boolean,
        queueAgeNs: Long,
    ): Pair<Int, Int>? = native.decode(bytes, count, ptsUs, config, keyFrame, queueAgeNs)

    /** Updates the compositor mask when Compose moves or clips the mirror during sidebar scroll. */
    fun setVisibleClip(fullBounds: Rect, clippedBounds: Rect) {
        if (closed) return
        val clip = mirrorClipFractions(fullBounds, clippedBounds)
        val size = fullBounds.width to fullBounds.height
        if (clip == lastVisibleClip && size == lastVisibleClipSize) return
        lastVisibleClip = clip
        lastVisibleClipSize = size
        native.setClip(clip.left, clip.top, clip.right, clip.bottom)
    }

    /** JAWT surface attachment is tied to Swing's component lifecycle and always runs on the EDT. */
    fun requestDisplay() {
        if (EventQueue.isDispatchThread()) attachAndResize() else EventQueue.invokeLater { attachAndResize() }
    }

    private fun attachAndResize() {
        if (closed || !canvas.isDisplayable) return
        val window = SwingUtilities.getWindowAncestor(canvas)
        if (window != null) {
            val origin = SwingUtilities.convertPoint(canvas, 0, 0, window)
            val insets = window.insets
            runCatching {
                // Match LWComponentPeer.localToWindow inputs. Native subtracts the top-level
                // window insets once, as CPlatformComponent.setBounds does before setting the
                // JAWT layer's initial frame.
                val hierarchy = native.attachCanvas(origin.x, origin.y, insets.left, insets.top)
                if (!hierarchy.isNullOrBlank() && hierarchy != lastNativeHierarchy) {
                    lastNativeHierarchy = hierarchy
                    onDiagnostic("Metal mirror AppKit hierarchy $hierarchy")
                }
            }.onFailure { onDiagnostic("Metal mirror attach failed: ${it.message ?: it::class.simpleName}") }
        }
        resize()
    }

    private fun resize() {
        if (closed) return
        val scale = canvas.graphicsConfiguration?.defaultTransform
        val scaleX = scale?.scaleX ?: 1.0
        val scaleY = scale?.scaleY ?: scaleX
        val pixelWidth = (canvas.width * scaleX).roundToInt()
        val pixelHeight = (canvas.height * scaleY).roundToInt()
        runCatching { native.setBounds(canvas.width, canvas.height, pixelWidth, pixelHeight) }
            .onFailure { onDiagnostic("Metal mirror resize failed: ${it.message ?: it::class.simpleName}") }
    }

    override fun close() {
        if (EventQueue.isDispatchThread()) {
            closeOnEdt()
        } else {
            EventQueue.invokeAndWait { closeOnEdt() }
        }
    }

    private fun closeOnEdt() {
        if (closed) return
        closed = true
        canvas.removeComponentListener(resizeListener)
        native.close()
    }

    /** Returns and resets native callback/presentation timings for low-rate diagnostics. */
    fun takeNativeMetrics(): LongArray = native.readMetrics()

    fun reportPerformance(message: String) = onDiagnostic(message)
}
