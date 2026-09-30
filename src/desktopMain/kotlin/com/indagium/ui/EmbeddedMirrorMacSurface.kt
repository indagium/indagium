@file:Suppress("TooGenericExceptionCaught")

package com.indagium.ui

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Rect
import com.indagium.capture.mirror.MacVideoToolboxMirrorNative
import com.indagium.capture.mirror.MirrorCanvasOrigin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skiko.GraphicsApi
import java.awt.Canvas
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsListener
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import java.awt.event.KeyEvent as AwtKeyEvent

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

/** Bit0 attached, bit1 below all siblings, bit2 a non-ours sibling exists, bit3 every other
 * sibling is non-opaque — see `nativeUnderlayStatus`'s native doc. The decision needs bits 0, 2 and
 * 3. Bit1 is deliberately excluded: it reflects the ordering we applied ourselves, which is "above"
 * by construction while the fallback is active, so requiring it would make every fallback
 * permanent. */
private const val UNDERLAY_STATUS_REQUIRED_BITS = 0b1101L

internal fun underlayActiveFor(requested: Boolean, status: Long): Boolean =
    requested && (status and UNDERLAY_STATUS_REQUIRED_BITS) == UNDERLAY_STATUS_REQUIRED_BITS

/**
 * Whether Compose itself is blending interop content — the other half of the underlay decision,
 * which the native layer status cannot see. Without blending Compose clears the SwingPanel's whole
 * rectangle in its own layer, through any popup drawn over it, so the video shows *on top of* the
 * popup even though our layer is correctly below and Compose's layer is still non-opaque (observed
 * with -Dcompose.interop.blending=false: identical layer tree, popover cut out at the video's edge).
 * Mirrors ComposeSceneMediator's own rule: the feature flag AND a renderer that supports blending,
 * which on macOS means Metal (a SOFTWARE fallback does not).
 */
internal fun composeInteropBlendingEffective(flagEnabled: Boolean, renderApi: GraphicsApi?): Boolean =
    flagEnabled && renderApi == GraphicsApi.METAL

/** ComposeFeatureFlags is internal to Compose, so read the flag exactly the way Compose 1.11.1
 * does (`Boolean.parseBoolean(System.getProperty("compose.interop.blending"))`, false when unset —
 * Main.kt sets it on macOS). */
private fun composeInteropBlendingIn(window: java.awt.Window?): Boolean? {
    val composeWindow = window as? ComposeWindow ?: return null
    val flagEnabled = java.lang.Boolean.parseBoolean(System.getProperty("compose.interop.blending"))
    return composeInteropBlendingEffective(flagEnabled, composeWindow.renderApi)
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
private const val ATTACH_WATCHDOG_INTERVAL_MS = 1_000

internal class EmbeddedMirrorMacSurface(
    private val diagnosticSink: (String) -> Unit = {},
) : Closeable {
    val canvas = Canvas()

    /** Core Animation underlay ordering (the mirror layer sits below Compose's own Metal layer,
     * which paints a transparent hole over it) for same-window Compose overlays. Default true; a
     * developer bisect switch (`-Dindagium.mirror.underlay=false`), not a user-facing setting — a
     * tester who hits trouble already has an escape hatch in Capture settings, the scrcpy external
     * window mirror mode. */
    internal val underlayRequested: Boolean =
        System.getProperty("indagium.mirror.underlay")?.toBooleanStrictOrNull() ?: true

    /** Whether the underlay is genuinely working right now, not merely requested — see
     * [underlayActiveFor]. Starts optimistic (true) so the very first attach orders the layer below
     * its siblings; [refreshUnderlayStatus] corrects it once there is something to sample, and can
     * both downgrade (fall back to the legacy above-siblings ordering) and upgrade. */
    @Volatile private var underlaySupported = true
    val underlayActive: Boolean get() = underlayRequested && underlaySupported
    private val _underlayActiveState = MutableStateFlow(underlayActive)
    val underlayActiveState: StateFlow<Boolean> = _underlayActiveState

    /** A separate opt-in: the underlay itself is on by default, but its diagnostics are noisy
     * enough (an AppKit hierarchy dump per attach) that they stay off stderr unless asked for. */
    private val stderrDiagnosticsEnabled = System.getProperty("indagium.mirror.stderr").toBoolean()
    private val onDiagnostic: (String) -> Unit = { message ->
        diagnosticSink(message)
        if (stderrDiagnosticsEnabled) System.err.println("[embedded-mirror] $message")
    }
    private val native: MacVideoToolboxMirrorNative

    @Volatile private var closed = false
    private var lastNativeHierarchy: String? = null
    private var attachedWindow: java.awt.Window? = null
    private var lastVisibleClip: MirrorClipFractions? = null
    private var lastVisibleClipSize: Pair<Float, Float>? = null
    private val hostOwners = MirrorSurfaceHostOwners()

    @Volatile private var hostMounted = false

    /**
     * While [underlayActive], the layer sits below Compose's own siblings and forwards input back
     * through SwingPanel's Compose interop group instead of being masked. The legacy fallback (not
     * requested, or not supported on this machine — see [underlaySupported]) keeps the layer above
     * Compose and must be masked while a popup is open.
     */
    @Volatile private var overlayOccluded = false
    val isOverlayOccluded: Boolean get() = overlayOccluded
    private val _overlayOccludedState = MutableStateFlow(false)
    val overlayOccludedState: StateFlow<Boolean> = _overlayOccludedState

    @Volatile private var onOverlayOccluded: (() -> Unit)? = null
    private val resizeListener = object : ComponentAdapter() {
        override fun componentShown(event: ComponentEvent) { attachAndResize() }

        override fun componentHidden(event: ComponentEvent) { detachNativeSurface("canvas hidden") }

        override fun componentResized(event: ComponentEvent) = resize()

        override fun componentMoved(event: ComponentEvent) = resize()
    }

    /**
     * A window resize usually moves the Canvas by moving a container above it, which fires no
     * event on the Canvas itself. resize() re-sends the Canvas position with its size, and the
     * native geometry pass re-asserts the layer frame from it — without this the layer could stay
     * where the panel used to be after a maximise (picture shifted, black where it should be).
     */
    private val ancestorBoundsListener = object : HierarchyBoundsListener {
        override fun ancestorMoved(event: HierarchyEvent) = resize()

        override fun ancestorResized(event: HierarchyEvent) = resize()
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
                if (canvas.isShowing) attachAndResize() else detachNativeSurface("canvas not showing after hierarchy change")
            }
        }
    }

    /**
     * Safety net for the detach/return handoff between the sidebar and the detached window: the
     * layer can end up outside every window while the Canvas is showing and this class believes
     * it is attached (an attach that returned normally but left the layer unparented). No further
     * AWT event arrives to correct it, so the mirror stayed black while decoding and touches kept
     * working. Once a second, on the EDT, re-attach when the Canvas should be displaying but the
     * native layer is not in a tree. It does nothing in the normal case: one boolean read.
     */
    private val attachWatchdog = javax.swing.Timer(ATTACH_WATCHDOG_INTERVAL_MS) { healDetachedLayer() }.apply { isRepeats = true }

    init {
        check(!GraphicsEnvironment.isHeadless()) { "Metal mirror requires an AWT display; running headless" }
        native = MacVideoToolboxMirrorNative(canvas, underlayRequested)
        canvas.addComponentListener(resizeListener)
        canvas.addHierarchyListener(hierarchyListener)
        canvas.addHierarchyBoundsListener(ancestorBoundsListener)
        attachWatchdog.start()
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

    /** Applies the requested overlay mode; under the underlay native pixels stay visible beneath it. */
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
     *
     * This forwarding is window-wide, not geometric: while any overlay is open, every point on the
     * mirror goes to Compose, even points outside the overlay's own bounds. That is deliberate — a
     * click on the mirror while a popup/dialog is open must dismiss it (Compose's outside-click
     * handling), not tap the device through it. Per-region routing (forward only points inside the
     * overlay's bounds, let the rest reach the device) was considered and rejected for exactly that
     * reason.
     */
    fun forwardOverlayMouseEvent(event: MouseEvent) {
        if (!underlayActive || !overlayOccluded || closed || !EventQueue.isDispatchThread()) return
        val interopGroup = canvas.parent ?: return
        val forwarded = SwingUtilities.convertMouseEvent(canvas, event, interopGroup)
        interopGroup.dispatchEvent(forwarded)
        event.consume()
    }

    /** ComposeSceneMediator listens for wheel events on SwingPanel's root container. */
    fun forwardOverlayMouseWheelEvent(event: MouseWheelEvent) {
        if (!underlayActive || !overlayOccluded || closed || !EventQueue.isDispatchThread()) return
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

    /** Same reasoning as [forwardOverlayMouseEvent]: Escape and other keys must still reach a
     * Compose dialog/popup over the mirror even though AWT still targets this Canvas while it is
     * occluded. Only meaningful under the underlay — with the legacy fallback the Canvas is
     * disabled/hidden while occluded (see [updateCanvasAvailability]) and receives no key events
     * either way. */
    fun forwardOverlayKeyEvent(event: AwtKeyEvent) {
        if (!underlayActive || !overlayOccluded || closed || !EventQueue.isDispatchThread()) return
        val interopGroup = canvas.parent ?: return
        val forwarded = AwtKeyEvent(
            interopGroup, event.id, event.`when`, event.modifiersEx, event.keyCode, event.keyChar, event.keyLocation,
        )
        interopGroup.dispatchEvent(forwarded)
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
            keepPixelsUnderOverlay = underlayActive,
        )
        native.setClip(clip.left, clip.top, clip.right, clip.bottom)
    }

    private fun shouldShowCanvas(): Boolean = hostMounted && (!overlayOccluded || underlayActive)

    private fun updateCanvasAvailability() {
        val shouldShow = shouldShowCanvas()
        val acceptsInput = mirrorCanvasAcceptsDeviceInput(hostMounted, overlayOccluded)
        val update = {
            if (!closed && shouldShow == shouldShowCanvas() && acceptsInput == mirrorCanvasAcceptsDeviceInput(hostMounted, overlayOccluded)) {
                canvas.isEnabled = shouldShow
                canvas.isFocusable = acceptsInput
                if (!acceptsInput && canvas.isFocusOwner) canvas.transferFocus()
                canvas.isVisible = shouldShow
                if (shouldShow) attachAndResize() else detachNativeSurface("covered by an overlay or host unmounted")
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
        if (!canvasShouldDisplay()) {
            detachNativeSurface("attach preconditions not met")
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
                    onDiagnostic("Metal mirror AppKit hierarchy $hierarchy underlay=$underlayActive")
                }
            }.onFailure { onDiagnostic("Metal mirror attach failed: ${it.message ?: it::class.simpleName}") }
            // There is now something to sample: refine underlaySupported from the actual sibling
            // state instead of trusting the optimistic starting guess.
            refreshUnderlayStatus()
        }
        resize()
    }

    private fun canvasShouldDisplay(): Boolean =
        hostMounted && (!overlayOccluded || underlayActive) && canvas.isDisplayable && canvas.isShowing

    /** Re-reads the native underlay status bitfield and updates [underlaySupported] (and, through
     * it, [underlayActive]) on either a downgrade or an upgrade — see the field's own doc. Only
     * evaluated when bit0 (attached) is set: a not-yet-attached sample carries no sibling evidence
     * either way and must not flip the optimistic starting guess. */
    private fun refreshUnderlayStatus() {
        val status = native.underlayStatus()
        if (status and 0x1L == 0L) return
        // Unknown (not hosted in a ComposeWindow) counts as "not blending": falling back is safe,
        // video drawn over a popup is not.
        val blending = composeInteropBlendingIn(SwingUtilities.getWindowAncestor(canvas)) ?: false
        val supported = underlayActiveFor(true, status) && blending
        if (supported == underlaySupported) return
        underlaySupported = supported
        val active = underlayActive
        onDiagnostic(
            "Metal mirror underlay ${if (active) "upgraded to active" else "fell back"}: " +
                "requested=$underlayRequested supported=$supported status=$status composeBlending=$blending",
        )
        _underlayActiveState.value = active
        runCatching { native.setUnderlayOrdering(active) }
            .onFailure { onDiagnostic("Metal mirror underlay ordering update failed: ${it.message ?: it::class.simpleName}") }
        applyVisibleClip()
        updateCanvasAvailability()
    }

    private fun healDetachedLayer() {
        if (closed || !canvasShouldDisplay()) return
        if (attachedWindow != null && native.isLayerAttached()) {
            // Already healthy: still let the watchdog upgrade/downgrade the underlay, since a
            // sibling can appear or disappear (skiko's own layer attaching later, a window handoff)
            // without ever leaving our layer detached in between.
            refreshUnderlayStatus()
            // And re-send the geometry: a late frame write from a stale position (JAWT's or ours)
            // otherwise has nothing to correct it until the next resize. The native side writes
            // the frame only when it actually differs, so this is free when all is well.
            resize()
            return
        }
        onDiagnostic("Metal mirror layer was outside the window while its canvas is showing; re-attaching")
        attachedWindow = null
        attachAndResize()
    }

    /** Remove only the AppKit presentation layer; the decoder and last decoded surface stay live. */
    private fun detachNativeSurface(reason: String) {
        if (closed) return
        if (attachedWindow != null) onDiagnostic("Metal mirror detached: $reason")
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
        // Sent on every resize (and right after each attach): the native side re-asserts the
        // layer frame from it, which is what recovers a layer JAWT left 0x0 after a round trip
        // through the detached window.
        val origin = SwingUtilities.getWindowAncestor(canvas)?.let { window ->
            val point = SwingUtilities.convertPoint(canvas, 0, 0, window)
            MirrorCanvasOrigin(point.x, point.y, window.insets.left, window.insets.top)
        }
        runCatching { native.setBounds(canvas.width, canvas.height, pixelWidth, pixelHeight, origin) }
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
        attachWatchdog.stop()
        attachedWindow = null
        canvas.removeComponentListener(resizeListener)
        canvas.removeHierarchyListener(hierarchyListener)
        canvas.removeHierarchyBoundsListener(ancestorBoundsListener)
        native.close()
    }

    /** Returns and resets native callback/presentation timings for low-rate diagnostics. */
    fun takeNativeMetrics(): LongArray = native.readMetrics()

    fun reportPerformance(message: String) = onDiagnostic(message)
}
