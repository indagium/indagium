package com.indagium.capture.mirror

import java.awt.Canvas
import kotlin.test.Test

class NativeMirrorBridgeSmokeTest {
    @Test
    fun platformNativeBridgeLoadsAndCreatesCanvasHandle() {
        val osName = System.getProperty("os.name").orEmpty()
        when {
            osName.contains("win", ignoreCase = true) -> WindowsD3D11MirrorNative(Canvas()).use { }
            osName.contains("linux", ignoreCase = true) -> LinuxVaapiEglMirrorNative(Canvas()).use { }
            else -> return
        }
    }
}
