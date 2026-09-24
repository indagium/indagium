@file:Suppress("TooGenericExceptionCaught")

package com.indagium.ui

import com.indagium.capture.mirror.MacVideoToolboxMirrorNative
import androidx.compose.ui.geometry.Rect
import java.awt.Canvas
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap
import javax.swing.SwingUtilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/** The native layer must expose no pixels while a same-window Compose popup is present. */
internal fun effectiveMirrorClip(
    visibleClip: MirrorClipFractions?,
    overlayOccluded: Boolean,
    hostMounted: Boolean = true,
    keepPixelsUnderOverlay: Boolean = true,
): MirrorClipFractions =
    if (!hostMounted || (overlayOccluded && !keepPixelsUnderOverlay) || visibleClip == null) {
        MirrorClipFractions(0f, 0f, 0f, 0f)
    } else {
        visibleClip
    }

/** The picture stays live behind overlays, while clicks there belong to Compose controls. */
internal fun mirrorCanvasAcceptsDeviceInput(hostMounted: Boolean, overlayOccluded: Boolean): Boolean =
    hostMounted && !overlayOccluded

/** Tracks overlapping Compose hosts during an inline-to-detached window handoff. */
internal class MirrorSurfaceHostOwners {
    private val owners = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    @Synchronized
    fun setMounted(owner: Any, mounted: Boolean): Boolean {
        if (mounted) owners.add(owner) else owners.remove(owner)
        return owners.isNotEmpty()
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
    /** Experimental Core Animation underlay ordering for same-window Compose overlays. */
    internal val overlayLayerExperimentEnabled =
        System.getProperty("indagium.mirror.overlay-experiment").toBoolean()
    private val native: MacVideoToolboxMirrorNative
    @Volatile private var closed = false
    private var lastNativeHierarchy: String? = null
    private var attachedWindow: java.awt.Window? = null
    private var lastVisibleClip: MirrorClipFractions? = null
    private var lastVisibleClipSize: Pair<Float, Float>? = null
    private val hostOwners = MirrorSurfaceHostOwners()
    @Volatile private var hostMounted = false
    /**
     * The default layer order is above Compose and must be masked while a popup is open. The
     * opt-in experiment puts the layer below its Core Animation siblings and forwards input back
     * through SwingPanel's Compose interop group.
     */
    @Volatile private var overlayOccluded = false
    val isOverlayOccluded: Boolean get() = overlayOccluded
    private val _overlayOccludedState = MutableStateFlow(false)
    val overlayOccludedState: StateFlow<Boolean> = _overlayOccludedState
    @Volatile private var onOverlayOccluded: (() -> Unit)? = null
    private val resizeListener = object : ComponentAdapter() {
        override fun componentShown(event: ComponentEvent) { attachAndResize() }
        override fun componentHidden(event: ComponentEvent) { detachNativeSurface() }
        override fun componentResized(event: ComponentEvent) = resize()
    }
    private val hierarchyListener = HierarchyListener { event ->
        if (event.changeFlags and (
                HierarchyEvent.PARENT_CHANGED.toLong() or
                    HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() or
                    HierarchyEvent.SHOWING_CHANGED.toLong()
                ) != 0L
        ) {
            // Wait for the new hierarchy notification, then attach only after the Canvas has a
            // displayable peer in that Window. This is lifecycle-driven, not a timing delay.
            EventQueue.invokeLater {
                if (canvas.isShowing) attachAndResize() else detachNativeSurface()
            }
        }
    }

    init {
        check(!GraphicsEnvironment.isHeadless()) { "Metal mirror requires an AWT display; running headless" }
        native = MacVideoToolboxMirrorNative(canvas, overlayLayerExperimentEnabled)
        canvas.addComponentListener(resizeListener)
        canvas.addHierarchyListener(hierarchyListener)
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
        applyVisibleClip()
    }

    /** Tracks the Compose host that owns this single Canvas. Decoding stays active when unmounted. */
    fun setHostMounted(owner: Any, mounted: Boolean) {
        if (closed) return
        val hasHost = hostOwners.setMounted(owner, mounted)
        if (hostMounted == hasHost) return
        hostMounted = hasHost
        applyVisibleClip()
        if (!hasHost) onOverlayOccluded?.invoke()
        updateCanvasAvailability()
    }

    /** Applies the requested overlay mode; the experiment leaves native pixels visible underneath. */
    fun setOverlayOccluded(occluded: Boolean) {
        if (closed || overlayOccluded == occluded) return
        overlayOccluded = occluded
        _overlayOccludedState.value = occluded
        applyVisibleClip()
        if (occluded) onOverlayOccluded?.invoke()
        updateCanvasAvailability()
    }

    /**
     * A heavyweight Canvas can be the AppKit/AWT mouse target even when Compose draws a popup
     * above it. Compose Desktop registers its blending input bridge on SwingPanel's parent group,
     * so retarget overlay clicks there. The mirror's own mouse listener checks [isOverlayOccluded]
     * first and will not send those gestures to the device.
     */
    fun forwardOverlayMouseEvent(event: MouseEvent) {
        if (!overlayLayerExperimentEnabled || !overlayOccluded || closed || !EventQueue.isDispatchThread()) return
        val interopGroup = canvas.parent ?: return
        val forwarded = SwingUtilities.convertMouseEvent(canvas, event, interopGroup)
        interopGroup.dispatchEvent(forwarded)
        event.consume()
    }

    /** ComposeSceneMediator listens for wheel events on SwingPanel's root container. */
    fun forwardOverlayMouseWheelEvent(event: MouseWheelEvent) {
        if (!overlayLayerExperimentEnabled || !overlayOccluded || closed || !EventQueue.isDispatchThread()) return
        val interopGroup = canvas.parent ?: return
        val target = interopGroup.parent ?: interopGroup
        val point = SwingUtilities.convertPoint(canvas, event.point, target)
        target.dispatchEvent(
            MouseWheelEvent(
                target,
                event.id,
                event.`when`,
                event.modifiersEx,
                point.x,
                point.y,
                event.clickCount,
                event.isPopupTrigger,
                event.scrollType,
                event.scrollAmount,
                event.wheelRotation,
            ),
        )
        event.consume()
    }

    /** Installs the mirror input owner's cleanup for a touch interrupted by an overlay. */
    fun setOverlayOcclusionListener(listener: (() -> Unit)?) {
        onOverlayOccluded = listener
    }

    private fun applyVisibleClip() {
        val clip = effectiveMirrorClip(
            visibleClip = lastVisibleClip,
            overlayOccluded = overlayOccluded,
            hostMounted = hostMounted,
            keepPixelsUnderOverlay = overlayLayerExperimentEnabled,
        )
        native.setClip(clip.left, clip.top, clip.right, clip.bottom)
    }

    private fun updateCanvasAvailability() {
        val shouldShow = hostMounted && (!overlayOccluded || overlayLayerExperimentEnabled)
        val acceptsInput = mirrorCanvasAcceptsDeviceInput(hostMounted, overlayOccluded)
        val update = {
            val currentShouldShow = hostMounted && (!overlayOccluded || overlayLayerExperimentEnabled)
            if (!closed && shouldShow == currentShouldShow && acceptsInput == mirrorCanvasAcceptsDeviceInput(hostMounted, overlayOccluded)) {
                canvas.isEnabled = shouldShow
                canvas.isFocusable = acceptsInput
                if (!acceptsInput && canvas.isFocusOwner) canvas.transferFocus()
                canvas.isVisible = shouldShow
                if (shouldShow) attachAndResize() else detachNativeSurface()
            }
        }
        if (EventQueue.isDispatchThread()) update() else EventQueue.invokeLater(update)
    }

    /** JAWT surface attachment is tied to Swing's component lifecycle and always runs on the EDT. */
    fun requestDisplay() {
        if (EventQueue.isDispatchThread()) attachAndResize() else EventQueue.invokeLater { attachAndResize() }
    }

    private fun attachAndResize() {
        if (closed) return
        if (!hostMounted || (overlayOccluded && !overlayLayerExperimentEnabled) || !canvas.isDisplayable || !canvas.isShowing) {
            detachNativeSurface()
            return
        }
        val window = SwingUtilities.getWindowAncestor(canvas)
        if (window != null && window !== attachedWindow) {
            val origin = SwingUtilities.convertPoint(canvas, 0, 0, window)
            val insets = window.insets
            runCatching {
                // Match LWComponentPeer.localToWindow inputs. Native subtracts the top-level
                // window insets once, as CPlatformComponent.setBounds does before setting the
                // JAWT layer's initial frame.
                val hierarchy = native.attachCanvas(origin.x, origin.y, insets.left, insets.top)
                attachedWindow = window
                if (!hierarchy.isNullOrBlank() && hierarchy != lastNativeHierarchy) {
                    lastNativeHierarchy = hierarchy
                    onDiagnostic(
                        "Metal mirror AppKit hierarchy $hierarchy " +
                            "overlay_layer_experiment=$overlayLayerExperimentEnabled",
                    )
                }
            }.onFailure { onDiagnostic("Metal mirror attach failed: ${it.message ?: it::class.simpleName}") }
        }
        resize()
    }

    /** Remove only the AppKit presentation layer; the decoder and last decoded surface stay live. */
    private fun detachNativeSurface() {
        if (closed) return
        attachedWindow = null
        runCatching { native.detachCanvas() }
            .onFailure { onDiagnostic("Metal mirror detach failed: ${it.message ?: it::class.simpleName}") }
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
        attachedWindow = null
        canvas.removeComponentListener(resizeListener)
        canvas.removeHierarchyListener(hierarchyListener)
        native.close()
    }

    /** Returns and resets native callback/presentation timings for low-rate diagnostics. */
    fun takeNativeMetrics(): LongArray = native.readMetrics()

    fun reportPerformance(message: String) = onDiagnostic(message)
}
