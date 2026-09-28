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
import java.io.Closeable

/** Heavyweight AWT drawable used only by the Windows D3D11 and Linux VAAPI/EGL direct paths. */
internal class EmbeddedMirrorGpuSurface private constructor(
    val mode: String,
    val canvas: Canvas,
    private val presenter: HardwareMirrorFramePresenter,
    private val hardwareDeviceType: Int,
    private val hardwarePixelFormat: Int,
) : Closeable {
    fun createDecoder(): DirectH264Decoder = HardwareH264MirrorDecoder(
        hardwareDeviceType = hardwareDeviceType,
        hardwarePixelFormat = hardwarePixelFormat,
        presenter = presenter,
    )

    override fun close() = presenter.close()

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
