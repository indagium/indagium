package com.indagium.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeMediaSupportTest {
    @Test
    fun parseGlibcVersionToleratesPatchAndWhitespace() {
        assertEquals(2 to 31, parseGlibcVersion("glibc 2.31"))
        assertEquals(2 to 35, parseGlibcVersion("glibc 2.35"))
        assertEquals(2 to 39, parseGlibcVersion("glibc 2.39.1"))
        assertEquals(2 to 31, parseGlibcVersion("  glibc   2.31  \n"))
        assertNull(parseGlibcVersion("musl libc"))
        assertNull(parseGlibcVersion(""))
    }

    @Test
    fun nativeMediaSupportForIsAvailableOnNonLinuxRegardlessOfGlibc() {
        assertTrue(nativeMediaSupportFor("Mac OS X", null).available)
        assertTrue(nativeMediaSupportFor("Mac OS X", 2 to 17).available)
        assertTrue(nativeMediaSupportFor("Windows 11", null).available)
    }

    @Test
    fun nativeMediaSupportForIsUnavailableOnLinuxBelowRequiredGlibc() {
        val support = nativeMediaSupportFor("Linux", 2 to 31)
        assertFalse(support.available)
        assertEquals("2.31", support.detectedGlibc)
        assertTrue(support.reason.orEmpty().contains("2.35"))
        assertTrue(support.reason.orEmpty().contains("2.31"))
    }

    @Test
    fun nativeMediaSupportForIsAvailableOnLinuxAtOrAboveRequiredGlibc() {
        assertTrue(nativeMediaSupportFor("Linux", 2 to 35).available)
        assertTrue(nativeMediaSupportFor("Linux", 3 to 0).available)
    }

    @Test
    fun nativeMediaSupportForIsAvailableOnLinuxWithAnInconclusiveProbe() {
        // musl, or a missing/failing getconf — never block a launch on doubt.
        assertTrue(nativeMediaSupportFor("Linux", null).available)
    }

    @Test
    fun adaptedToNativeMediaLeavesAvailableSystemsUnchanged() {
        val settings = CaptureSettings(recordVideo = true).withMirrorMode(CaptureMirrorMode.EMBEDDED)
        val support = NativeMediaSupport(available = true)
        assertEquals(settings, settings.adaptedToNativeMedia(support, scrcpyAvailable = true))
        assertEquals(settings, settings.adaptedToNativeMedia(support, scrcpyAvailable = false))
    }

    @Test
    fun adaptedToNativeMediaFallsBackToExternalWhenScrcpyIsAvailable() {
        val settings = CaptureSettings(recordVideo = true).withMirrorMode(CaptureMirrorMode.EMBEDDED)
        val unavailable = NativeMediaSupport(available = false, detectedGlibc = "2.31")

        val adapted = settings.adaptedToNativeMedia(unavailable, scrcpyAvailable = true)

        assertFalse(adapted.recordVideo)
        assertEquals(CaptureMirrorMode.EXTERNAL, adapted.effectiveMirrorMode)
    }

    @Test
    fun adaptedToNativeMediaDisablesDisplayWhenScrcpyIsUnavailable() {
        val settings = CaptureSettings(recordVideo = true).withMirrorMode(CaptureMirrorMode.EMBEDDED)
        val unavailable = NativeMediaSupport(available = false, detectedGlibc = "2.31")

        val adapted = settings.adaptedToNativeMedia(unavailable, scrcpyAvailable = false)

        assertFalse(adapted.recordVideo)
        assertEquals(CaptureMirrorMode.DISABLED, adapted.effectiveMirrorMode)
    }

    @Test
    fun adaptedToNativeMediaKeepsExternalAndDisabledModesAsIs() {
        val unavailable = NativeMediaSupport(available = false, detectedGlibc = "2.31")

        val external = CaptureSettings(recordVideo = true).withMirrorMode(CaptureMirrorMode.EXTERNAL)
        val adaptedExternal = external.adaptedToNativeMedia(unavailable, scrcpyAvailable = true)
        assertFalse(adaptedExternal.recordVideo)
        assertEquals(CaptureMirrorMode.EXTERNAL, adaptedExternal.effectiveMirrorMode)

        val disabled = CaptureSettings(recordVideo = true).withMirrorMode(CaptureMirrorMode.DISABLED)
        val adaptedDisabled = disabled.adaptedToNativeMedia(unavailable, scrcpyAvailable = true)
        assertFalse(adaptedDisabled.recordVideo)
        assertEquals(CaptureMirrorMode.DISABLED, adaptedDisabled.effectiveMirrorMode)
    }

    @Test
    fun adaptedToNativeMediaKeepsLegacyMirrorFalseDisabled() {
        // mirror=false (the pre-mirrorMode compatibility bit) is already DISABLED via
        // effectiveMirrorMode regardless of the stored mirrorMode — adaptation must not "upgrade"
        // it back to EMBEDDED/EXTERNAL.
        val legacyDisabled = CaptureSettings(recordVideo = true, mirror = false, mirrorMode = CaptureMirrorMode.EMBEDDED)
        val unavailable = NativeMediaSupport(available = false, detectedGlibc = "2.31")

        val adapted = legacyDisabled.adaptedToNativeMedia(unavailable, scrcpyAvailable = true)

        assertFalse(adapted.recordVideo)
        assertEquals(CaptureMirrorMode.DISABLED, adapted.effectiveMirrorMode)
        assertFalse(adapted.mirror)
    }

    @Test
    fun nativeMediaAdaptationNoticeIsNullWhenNothingChanged() {
        val settings = CaptureSettings(recordVideo = true).withMirrorMode(CaptureMirrorMode.EXTERNAL)
        val available = NativeMediaSupport(available = true)
        assertNull(nativeMediaAdaptationNotice(settings, settings.adaptedToNativeMedia(available, true), available, true))
    }

    @Test
    fun nativeMediaAdaptationNoticeExplainsTheScrcpyFallback() {
        val original = CaptureSettings(recordVideo = true).withMirrorMode(CaptureMirrorMode.EMBEDDED)
        val unavailable = NativeMediaSupport(available = false, detectedGlibc = "2.31")
        val adapted = original.adaptedToNativeMedia(unavailable, scrcpyAvailable = true)

        val notice = nativeMediaAdaptationNotice(original, adapted, unavailable, scrcpyAvailable = true)

        assertTrue(notice.orEmpty().contains("2.31"))
        assertTrue(notice.orEmpty().contains("scrcpy window"))
    }

    @Test
    fun nativeMediaAdaptationNoticeExplainsNoDisplayWhenScrcpyIsMissing() {
        val original = CaptureSettings(recordVideo = true).withMirrorMode(CaptureMirrorMode.EMBEDDED)
        val unavailable = NativeMediaSupport(available = false, detectedGlibc = "2.31")
        val adapted = original.adaptedToNativeMedia(unavailable, scrcpyAvailable = false)

        val notice = nativeMediaAdaptationNotice(original, adapted, unavailable, scrcpyAvailable = false)

        assertTrue(notice.orEmpty().contains("no device display is shown"))
    }

    @Test
    fun nativeMediaAdaptationNoticeKeepsDisplayOffWhenOnlyRecordingWasDropped() {
        val original = CaptureSettings(recordVideo = true).withMirrorMode(CaptureMirrorMode.DISABLED)
        val unavailable = NativeMediaSupport(available = false, detectedGlibc = "2.31")
        val adapted = original.adaptedToNativeMedia(unavailable, scrcpyAvailable = true)

        val notice = nativeMediaAdaptationNotice(original, adapted, unavailable, scrcpyAvailable = true)

        assertTrue(notice.orEmpty().contains("the device display is off"))
        assertFalse(notice.orEmpty().contains("scrcpy window"))
    }

    @Test
    fun probeDetectsOnceAndCachesForSubsequentCalls() {
        val runner = FakeCaptureRunner().apply { enqueue(CompletedFakeProcess("glibc 2.31\n")) }
        val probe = NativeMediaSupportProbe(runner, osName = "Linux")

        val first = probe.detect()
        val second = probe.detect()

        assertEquals(first, second)
        assertFalse(first.available)
        assertEquals(1, runner.specs.size)
    }

    @Test
    fun probeNeverRunsOnNonLinux() {
        val runner = FakeCaptureRunner()
        val probe = NativeMediaSupportProbe(runner, osName = "Mac OS X")

        val support = probe.detect()

        assertTrue(support.available)
        assertTrue(runner.specs.isEmpty())
    }
}
