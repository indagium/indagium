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
        assertTrue(diagnostic.contains("Ubuntu 22.04"))
        // No more Flatpak workaround for 20.04 — its bundled flatpak 1.6 can't read Flathub's
        // current summary (see docs); logs/scrcpy staying usable is the actual guidance now.
        assertFalse(diagnostic.contains("org.freedesktop.Platform"))
        assertTrue(diagnostic.contains("scrcpy window still work"))
    }

    @Test
    fun nativeLoaderDiagnosticFindsGlibcMessageOnlyInASuppressedException() {
        // JavaCPP's real UnsatisfiedLinkError often ends up suppressed rather than as the direct
        // cause once it re-throws a cached NoClassDefFoundError on a later attempt.
        val noClassDef = NoClassDefFoundError("Could not initialize class org.bytedeco.ffmpeg.global.avutil")
        val initializerFailure = ExceptionInInitializerError(noClassDef)
        initializerFailure.addSuppressed(UnsatisfiedLinkError("/tmp/libavutil.so: version `GLIBC_2.34' not found"))

        val diagnostic = captureNativeFailureDiagnostic(initializerFailure, osName = "Linux")
        assertTrue(diagnostic.contains("GLIBC_2.34"))
        assertTrue(diagnostic.contains("glibc 2.34 or newer"))
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
