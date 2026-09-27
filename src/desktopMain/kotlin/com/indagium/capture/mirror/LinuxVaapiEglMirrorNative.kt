@file:Suppress("TooGenericExceptionCaught")

package com.indagium.capture.mirror

import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVHWDeviceContext
import org.bytedeco.ffmpeg.avutil.AVHWFramesContext
import java.awt.Canvas
import java.nio.file.Files

/** EGL presenter that imports the decoder's VAAPI surface as a DRM PRIME EGLImage. */
internal class LinuxVaapiEglMirrorNative(private val canvas: Canvas) : HardwareMirrorFramePresenter {
    private var handle: Long

    init {
        check(System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)) {
            "VAAPI/EGL mirror presentation is available only on Linux"
        }
        check(load()) { loadFailure ?: "Bundled VAAPI/EGL mirror library is unavailable" }
        handle = nativeCreate(canvas)
        check(handle != 0L) { "Could not create the VAAPI/EGL mirror surface" }
    }

    @Synchronized
    override fun present(frame: AVFrame) {
        // SwingPanel may temporarily unmount this Canvas while a tab is hidden or detached. Skip
        // presentation during that interval; the next frame resolves the current X11 drawable.
        if (!canvas.isShowing) return
        val framesRef = frame.hw_frames_ctx()
        check(framesRef != null && !framesRef.isNull) { "VAAPI decoder returned a frame without its hardware context" }
        val frames = AVHWFramesContext(framesRef.data())
        val deviceRef = frames.device_ref()
        check(deviceRef != null && !deviceRef.isNull) { "VAAPI frame has no hardware device reference" }
        val device = AVHWDeviceContext(deviceRef.data())
        val vaapiContext = device.hwctx()
        check(vaapiContext != null && !vaapiContext.isNull) { "FFmpeg returned an empty VAAPI device context" }
        val surfaceId = frame.data(3)?.address() ?: 0L
        check(surfaceId != 0L) { "VAAPI decoder returned an empty surface id" }
        // A positive status means the AWT drawable is temporarily zero-sized during detach or
        // minimize. That is a dropped presentation frame, not a decoder failure.
        check(nativePresent(handle, vaapiContext.address(), surfaceId, frame.width(), frame.height()) >= 0) {
            "VAAPI/EGL mirror presentation failed"
        }
    }

    @Synchronized
    override fun close() {
        val nativeHandle = handle
        handle = 0L
        if (nativeHandle != 0L) runCatching { nativeClose(nativeHandle) }
    }

    companion object {
        private const val RESOURCE_PATH = "/native/linux/libindagium_mirror.so"
        private var loaded = false
        private var loadFailure: String? = null

        @Synchronized private fun load(): Boolean {
            if (loaded) return true
            if (loadFailure != null) return false
            return try {
                LinuxVaapiEglMirrorNative::class.java.getResourceAsStream(RESOURCE_PATH)?.use { source ->
                    val file = Files.createTempFile("indagium-mirror-", ".so").toFile()
                    file.deleteOnExit()
                    file.outputStream().use(source::copyTo)
                    System.load(file.absolutePath)
                } ?: error("Bundled VAAPI/EGL mirror library is missing")
                loaded = true
                true
            } catch (failure: Throwable) {
                loadFailure = failure.message ?: failure::class.simpleName ?: "Could not load VAAPI/EGL mirror support"
                false
            }
        }

        @JvmStatic private external fun nativeCreate(canvas: Canvas): Long

        @JvmStatic private external fun nativePresent(
            handle: Long,
            vaapiDeviceContext: Long,
            surfaceId: Long,
            width: Int,
            height: Int,
        ): Int

        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
