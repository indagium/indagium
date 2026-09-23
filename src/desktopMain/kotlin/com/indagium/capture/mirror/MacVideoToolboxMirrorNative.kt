@file:Suppress("TooGenericExceptionCaught")

package com.indagium.capture.mirror

import java.awt.Canvas
import java.nio.file.Files
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Small Kotlin facade around the packaged macOS VideoToolbox/Metal/JAWT implementation. */
internal class MacVideoToolboxMirrorNative(private val canvas: Canvas) : AutoCloseable {
    private val lifecycle = ReentrantReadWriteLock()
    private var handle: Long

    init {
        check(isMac()) { "VideoToolbox mirror is available only on macOS" }
        check(load()) { loadFailure ?: "Bundled VideoToolbox mirror library is unavailable" }
        handle = nativeCreate(canvas)
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

    fun setBounds(width: Int, height: Int, pixelWidth: Int, pixelHeight: Int) = lifecycle.read {
        if (handle != 0L && width > 0 && height > 0 && pixelWidth > 0 && pixelHeight > 0) {
            nativeSetBounds(handle, width, height, pixelWidth, pixelHeight)
        }
    }

    fun attachCanvas(windowX: Int, windowY: Int, insetLeft: Int, insetTop: Int): String? = lifecycle.read {
        if (handle != 0L) nativeAttach(handle, windowX, windowY, insetLeft, insetTop) else null
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

        @JvmStatic private external fun nativeCreate(canvas: Canvas): Long
        @JvmStatic private external fun nativeDecode(
            handle: Long,
            bytes: ByteArray,
            count: Int,
            ptsUs: Long,
            config: Boolean,
            keyFrame: Boolean,
            ingressNs: Long,
        ): Long
        @JvmStatic private external fun nativeSetBounds(handle: Long, width: Int, height: Int, pixelWidth: Int, pixelHeight: Int)
        @JvmStatic private external fun nativeAttach(handle: Long, windowX: Int, windowY: Int, insetLeft: Int, insetTop: Int): String?
        @JvmStatic private external fun nativeSetClip(handle: Long, left: Float, top: Float, right: Float, bottom: Float)
        @JvmStatic private external fun nativeReadMetrics(handle: Long): LongArray?
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
