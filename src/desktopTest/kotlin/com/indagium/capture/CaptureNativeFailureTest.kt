package com.indagium.capture

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureNativeFailureTest {
    @Test
    fun nativeLoaderDiagnosticRetainsGlibcCauseAndExplainsUbuntu20Limit() {
        val loaderFailure = UnsatisfiedLinkError("/tmp/libavformat.so: version `GLIBC_2.35' not found")
        val initializerFailure = ExceptionInInitializerError(loaderFailure)

        assertTrue(hasNativeLinkageFailure(initializerFailure))
        val diagnostic = captureNativeFailureDiagnostic(initializerFailure, osName = "Linux")
        assertTrue(diagnostic.contains("GLIBC_2.35"))
        assertTrue(diagnostic.contains("glibc 2.35 or newer"))
        assertTrue(diagnostic.contains("Ubuntu 20.04"))
        assertTrue(diagnostic.contains("org.freedesktop.Platform//24.08"))
    }

    @Test
    fun nativeLoaderDiagnosticDoesNotAddLinuxGuidanceOnOtherPlatforms() {
        val diagnostic = captureNativeFailureDiagnostic(
            UnsatisfiedLinkError("native library missing"),
            osName = "Mac OS X",
        )
        assertTrue(diagnostic.contains("native library missing"))
        assertFalse(diagnostic.contains("Ubuntu 20.04"))
    }

    @Test
    fun appImageRuntimePathsAreRemovedFromChildEnvironmentOnly() {
        val childEnvironment = mutableMapOf(
            "APPDIR" to "/tmp/.mount_indagium",
            "APPIMAGE" to "/home/user/Indagium.AppImage",
            "OWD" to "/home/user",
            "ARGV0" to "Indagium.AppImage",
            "LD_LIBRARY_PATH" to "/usr/lib:/tmp/.mount_indagium/usr/lib:/tmp/.mount_indagium/usr/lib/jvm/lib",
            "ADB" to "/usr/bin/adb",
        )

        sanitizeAppImageRuntimeForChild(childEnvironment)

        assertTrue(childEnvironment["LD_LIBRARY_PATH"] == "/usr/lib")
        assertTrue(childEnvironment["ADB"] == "/usr/bin/adb")
        assertFalse("APPDIR" in childEnvironment)
        assertFalse("APPIMAGE" in childEnvironment)
        assertFalse("OWD" in childEnvironment)
        assertFalse("ARGV0" in childEnvironment)
    }
}
