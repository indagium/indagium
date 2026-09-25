@file:Suppress("TooGenericExceptionCaught")

package com.indagium.capture.mirror

import java.awt.Canvas
import java.nio.file.Files
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Small Kotlin facade around the packaged macOS VideoToolbox/Metal/JAWT implementation. */
internal class MacVideoToolboxMirrorNative(
    private val canvas: Canvas,
    private val underlayRequested: Boolean,
) : AutoCloseable {
    private val lifecycle = ReentrantReadWriteLock()
    private var handle: Long

    init {
        check(isMac()) { "VideoToolbox mirror is available only on macOS" }
        check(load()) { loadFailure ?: "Bundled VideoToolbox mirror library is unavailable" }
        handle = nativeCreate(canvas, underlayRequested)
        check(handle != 0L) { "Could not create VideoToolbox mirror surface" }
    }

    /** Returns dimensions packed by native code after it has accepted a complete access unit. */
    fun decode(
        bytes: ByteArray,
        count: Int,
        ptsUs: Long,
        config: Boolean,
        keyFrame: Boolean,
        queueAgeNs: Long,
    ): Pair<Int, Int>? = lifecycle.read {
        check(count in 0..bytes.size)
        val nativeHandle = handle.takeIf { it != 0L } ?: return@read null
        val packed = nativeDecode(nativeHandle, bytes, count, ptsUs, config, keyFrame, queueAgeNs)
        if (packed < 0) {
            val details = -packed
            val code = (details ushr 32).toInt()
            val status = details.toInt()
            val stage = when (code) {
                1 -> "invalid or empty H.264 access unit"
                2 -> "SPS/PPS format description rejected"
                3 -> "VideoToolbox session creation failed"
                4 -> "CoreMedia block buffer creation failed"
                5 -> "no decoder is ready at the key frame"
                6 -> "CoreMedia sample buffer creation failed"
                7 -> "VideoToolbox frame submission failed"
                8 -> "VideoToolbox asynchronous decode callback failed"
                9 -> "Metal texture or presentation failed"
                else -> "native mirror setup timed out"
            }
            val statusDetail = if (status == 0) "" else " (OSStatus $status)"
            error("$stage$statusDetail")
        }
        val width = (packed ushr 32).toInt()
        val height = packed.toInt()
        if (width > 0 && height > 0) width to height else null
    }

    /** [origin] is the Canvas position in its window as `(windowX, windowY, insetLeft, insetTop)`,
     * the same terms [attachCanvas] takes; when present the native side re-asserts the layer frame
     * from it rather than relying on JAWT alone (see PendingGeometryUpdate.hasOrigin). */
    fun setBounds(width: Int, height: Int, pixelWidth: Int, pixelHeight: Int, origin: MirrorCanvasOrigin? = null) = lifecycle.read {
        if (handle != 0L && width > 0 && height > 0 && pixelWidth > 0 && pixelHeight > 0) {
            nativeSetBounds(
                handle, width, height, pixelWidth, pixelHeight,
                origin != null, origin?.windowX ?: 0, origin?.windowY ?: 0, origin?.insetLeft ?: 0, origin?.insetTop ?: 0,
            )
        }
    }

    fun attachCanvas(windowX: Int, windowY: Int, insetLeft: Int, insetTop: Int): String? = lifecycle.read {
        if (handle != 0L) nativeAttach(handle, windowX, windowY, insetLeft, insetTop) else null
    }

    /** Removes the retained Metal layer from its AppKit window without stopping VideoToolbox. */
    fun isLayerAttached(): Boolean = lifecycle.read { handle != 0L && nativeLayerAttached(handle) }

    /** Packed bitfield (see the native doc on `nativeUnderlayStatus`) the Kotlin side decodes with
     * `underlayActiveFor` to decide whether the underlay ordering is genuinely working right now. */
    fun underlayStatus(): Long = lifecycle.read {
        if (handle == 0L) 0L else nativeUnderlayStatus(handle)
    }

    /** Flips the native z-order between underlay (below Core Animation siblings) and the legacy
     * above-siblings fallback. Safe to call from the EDT: native re-applies it asynchronously. */
    fun setUnderlayOrdering(underlayOrdering: Boolean) = lifecycle.read {
        if (handle != 0L) nativeSetUnderlayOrdering(handle, underlayOrdering)
    }

    fun detachCanvas() = lifecycle.read {
        if (handle != 0L) nativeDetach(handle)
    }

    fun setClip(left: Float, top: Float, right: Float, bottom: Float) = lifecycle.read {
        if (handle != 0L) nativeSetClip(handle, left, top, right, bottom)
    }

    fun readMetrics(): LongArray = lifecycle.read {
        if (handle == 0L) LongArray(49) else nativeReadMetrics(handle) ?: LongArray(49)
    }

    override fun close() {
        lifecycle.write {
            val nativeHandle = handle
            handle = 0L
            if (nativeHandle != 0L) runCatching { nativeClose(nativeHandle) }
        }
    }

    companion object {
        private const val RESOURCE_PATH = "/native/macos/libindagium_mirror.dylib"
        private var loaded = false
        private var loadFailure: String? = null

        private fun isMac() = System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

        @Synchronized private fun load(): Boolean {
            if (loaded) return true
            if (loadFailure != null || !isMac()) return false
            return try {
                MacVideoToolboxMirrorNative::class.java.getResourceAsStream(RESOURCE_PATH)?.use { source ->
                    val file = Files.createTempFile("indagium-mirror-", ".dylib").toFile()
                    file.deleteOnExit()
                    file.outputStream().use(source::copyTo)
                    System.load(file.absolutePath)
                } ?: error("Bundled VideoToolbox mirror library is missing")
                loaded = true
                true
            } catch (failure: Throwable) {
                loadFailure = failure.message ?: failure::class.simpleName ?: "Could not load VideoToolbox mirror support"
                false
            }
        }

        @JvmStatic private external fun nativeCreate(canvas: Canvas, underlayRequested: Boolean): Long
        @JvmStatic private external fun nativeDecode(
            handle: Long,
            bytes: ByteArray,
            count: Int,
            ptsUs: Long,
            config: Boolean,
            keyFrame: Boolean,
            ingressNs: Long,
        ): Long
        @JvmStatic private external fun nativeSetBounds(
            handle: Long,
            width: Int,
            height: Int,
            pixelWidth: Int,
            pixelHeight: Int,
            hasOrigin: Boolean,
            windowX: Int,
            windowY: Int,
            insetLeft: Int,
            insetTop: Int,
        )
        @JvmStatic private external fun nativeAttach(handle: Long, windowX: Int, windowY: Int, insetLeft: Int, insetTop: Int): String?
        @JvmStatic private external fun nativeDetach(handle: Long)
        @JvmStatic private external fun nativeLayerAttached(handle: Long): Boolean

        @JvmStatic private external fun nativeUnderlayStatus(handle: Long): Long

        @JvmStatic private external fun nativeSetUnderlayOrdering(handle: Long, underlayOrdering: Boolean)

        @JvmStatic private external fun nativeSetClip(handle: Long, left: Float, top: Float, right: Float, bottom: Float)
        @JvmStatic private external fun nativeReadMetrics(handle: Long): LongArray?
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}

/** A Canvas's position in its top-level window, in the terms the native attach expects. */
internal data class MirrorCanvasOrigin(val windowX: Int, val windowY: Int, val insetLeft: Int, val insetTop: Int)
