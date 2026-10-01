@file:Suppress("TooGenericExceptionCaught")

package com.indagium.ui

import com.indagium.capture.mirror.DirectH264Decoder
import com.indagium.capture.mirror.HardwareH264MirrorDecoder
import com.indagium.capture.mirror.HardwareMirrorFramePresenter
import com.indagium.capture.mirror.LinuxVaapiEglMirrorNative
import com.indagium.capture.mirror.NativeMirrorCanvas
import com.indagium.capture.mirror.WindowsD3D11MirrorNative
import org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_D3D11VA
import org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_VAAPI
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_D3D11
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_VAAPI
import java.awt.Canvas
import java.awt.GraphicsEnvironment

/** Heavyweight AWT drawable used only by the Windows D3D11 and Linux VAAPI/EGL direct paths. */
internal class EmbeddedMirrorGpuSurface private constructor(
    override val mode: String,
    val canvas: Canvas,
    private val presenter: HardwareMirrorFramePresenter,
    private val hardwareDeviceType: Int,
    private val hardwarePixelFormat: Int,
) : MirrorNativeSurface {
    @Volatile private var closed = false

    override val isClosed: Boolean get() = closed
    override val isPresentationAttached: Boolean get() = !closed && canvas.isShowing

    override fun describeAttachment(): String =
        "closed=$closed displayable=${canvas.isDisplayable} showing=${canvas.isShowing}"

    fun createDecoder(): DirectH264Decoder = HardwareH264MirrorDecoder(
        hardwareDeviceType = hardwareDeviceType,
        hardwarePixelFormat = hardwarePixelFormat,
        presenter = presenter,
    )

    override fun close() {
        closed = true
        presenter.close()
    }

    companion object {
        fun create(): EmbeddedMirrorGpuSurface {
            check(!GraphicsEnvironment.isHeadless()) { "Native GPU mirror requires an AWT display" }
            val canvas = NativeMirrorCanvas()
            val os = System.getProperty("os.name").orEmpty()
            return if (os.contains("win", ignoreCase = true)) {
                EmbeddedMirrorGpuSurface(
                    mode = "d3d11",
                    canvas = canvas,
                    presenter = WindowsD3D11MirrorNative(canvas),
                    hardwareDeviceType = AV_HWDEVICE_TYPE_D3D11VA,
                    hardwarePixelFormat = AV_PIX_FMT_D3D11,
                )
            } else {
                check(os.contains("linux", ignoreCase = true)) { "Native GPU mirror is supported only on Windows and Linux" }
                EmbeddedMirrorGpuSurface(
                    mode = "vaapi-egl",
                    canvas = canvas,
                    presenter = LinuxVaapiEglMirrorNative(canvas),
                    hardwareDeviceType = AV_HWDEVICE_TYPE_VAAPI,
                    hardwarePixelFormat = AV_PIX_FMT_VAAPI,
                )
            }
        }
    }
}
