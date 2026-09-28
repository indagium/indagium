@file:Suppress("TooGenericExceptionCaught")

package com.indagium.capture.mirror

import org.bytedeco.ffmpeg.avutil.AVFrame
import java.nio.file.Files

/** Native D3D11 swapchain presenter for D3D11VA decoder textures. */
internal class WindowsD3D11MirrorNative(private val canvas: NativeMirrorCanvas) : HardwareMirrorFramePresenter {
    private var handle: Long

    init {
        check(System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)) {
            "D3D11 mirror presentation is available only on Windows"
        }
        check(load()) { loadFailure ?: "Bundled D3D11 mirror library is unavailable" }
        handle = nativeCreate(canvas)
        check(handle != 0L) { "Could not create the D3D11 mirror surface" }
    }

    @Synchronized
    override fun present(frame: AVFrame) {
        // A SwingPanel host can disappear during tab navigation or detach/return. Keep decoding
        // the bounded stream, then resolve the Canvas's new HWND after it is mounted again.
        if (!canvas.isShowing) return
        val texture = frame.data(0)?.address() ?: 0L
        check(texture != 0L) { "D3D11 decoder returned an empty texture" }
        val subresource = frame.data(1)?.address()?.toInt() ?: 0
        // Positive statuses mean the Canvas is minimized or DXGI dropped this frame because the
        // swapchain was busy. Keep the native stream active; negative statuses indicate failure.
        // [canvas.generation] lets the native side skip re-resolving the HWND through JAWT on every
        // frame — it only does that when this counter has moved (see NativeMirrorCanvas's doc).
        check(nativePresent(handle, texture, subresource, frame.width(), frame.height(), canvas.generation) >= 0) {
            "D3D11 mirror presentation failed"
        }
    }

    @Synchronized
    override fun close() {
        val nativeHandle = handle
        handle = 0L
        if (nativeHandle != 0L) runCatching { nativeClose(nativeHandle) }
    }

    companion object {
        private const val RESOURCE_PATH = "/native/windows/indagium_mirror.dll"
        private var loaded = false
        private var loadFailure: String? = null

        @Synchronized private fun load(): Boolean {
            if (loaded) return true
            if (loadFailure != null) return false
            return try {
                WindowsD3D11MirrorNative::class.java.getResourceAsStream(RESOURCE_PATH)?.use { source ->
                    val file = Files.createTempFile("indagium-mirror-", ".dll").toFile()
                    file.deleteOnExit()
                    file.outputStream().use(source::copyTo)
                    System.load(file.absolutePath)
                } ?: error("Bundled D3D11 mirror library is missing")
                loaded = true
                true
            } catch (failure: Throwable) {
                loadFailure = failure.message ?: failure::class.simpleName ?: "Could not load D3D11 mirror support"
                false
            }
        }

        @JvmStatic private external fun nativeCreate(canvas: NativeMirrorCanvas): Long

        @JvmStatic private external fun nativePresent(
            handle: Long,
            texture: Long,
            subresource: Int,
            width: Int,
            height: Int,
            generation: Int,
        ): Int

        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
