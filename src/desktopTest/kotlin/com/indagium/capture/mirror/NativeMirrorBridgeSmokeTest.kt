package com.indagium.capture.mirror

import kotlin.test.Test

class NativeMirrorBridgeSmokeTest {
    @Test
    fun platformNativeBridgeLoadsAndCreatesCanvasHandle() {
        val osName = System.getProperty("os.name").orEmpty()
        when {
            osName.contains("win", ignoreCase = true) -> WindowsD3D11MirrorNative(NativeMirrorCanvas()).use { }
            osName.contains("linux", ignoreCase = true) -> LinuxVaapiEglMirrorNative(NativeMirrorCanvas()).use { }
            else -> return
        }
    }
}
