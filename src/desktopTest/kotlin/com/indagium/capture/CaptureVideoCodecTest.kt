package com.indagium.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureVideoCodecTest {
    @Test
    fun h264RemainsDefaultAndCodecSettingRoundTrips() {
        assertEquals(CaptureVideoCodec.H264, CaptureSettings().videoCodec)
        CaptureVideoCodec.entries.forEach { codec ->
            val decoded = assertNotNull(captureSettingsFromJson(captureSettingsToJson(CaptureSettings(videoCodec = codec))))
            assertEquals(codec, decoded.videoCodec)
        }
        assertEquals(CaptureVideoCodec.H264, assertNotNull(captureSettingsFromJson("""{"formatVersion":1}""")).videoCodec)
        assertEquals(
            CaptureVideoCodec.H264,
            assertNotNull(captureSettingsFromJson("""{"formatVersion":1,"videoCodec":"FUTURE"}""")).videoCodec,
        )
    }

    @Test
    fun parsesBundledScrcpyEncoderListAndConservativelyClassifiesMissingTags() {
        val encoders = parseCaptureVideoEncoders(
            """
            --video-codec=h264 --video-encoder=c2.vendor.avc.encoder (hw) [vendor]
            --video-codec=h265 --video-encoder=c2.android.hevc.encoder (sw) [android]
            --video-codec=av1 --video-encoder=c2.vendor.av1.encoder (hybrid) [vendor]
            --video-codec=av1 --video-encoder=legacy.av1.encoder
            """.trimIndent(),
        )
        assertEquals(4, encoders.size)
        assertEquals(CaptureEncoderAcceleration.HARDWARE, encoders[0].acceleration)
        assertEquals(CaptureEncoderAcceleration.SOFTWARE, encoders[1].acceleration)
        assertEquals(CaptureEncoderAcceleration.HYBRID, encoders[2].acceleration)
        assertEquals(CaptureEncoderAcceleration.UNKNOWN, encoders[3].acceleration)
    }

    @Test
    fun onlyExplicitHardwareAdvancedEncoderIsSelectedAndOtherStatesExplainFallback() {
        val hardware = CaptureVideoEncoderCapabilities(
            listOf(CaptureVideoEncoder(CaptureVideoCodec.AV1, "vendor.av1", CaptureEncoderAcceleration.HARDWARE)),
        )
        assertNull(advancedCodecFallbackReason(CaptureVideoCodec.AV1, hardware))
        assertEquals("vendor.av1", hardware.preferredHardwareEncoder(CaptureVideoCodec.AV1)?.name)
        assertNull(advancedCodecFallbackReason(CaptureVideoCodec.H264, null))

        val softwareOnly = CaptureVideoEncoderCapabilities(
            listOf(CaptureVideoEncoder(CaptureVideoCodec.AV1, "android.av1", CaptureEncoderAcceleration.SOFTWARE)),
        )
        assertTrue(advancedCodecFallbackReason(CaptureVideoCodec.AV1, softwareOnly).orEmpty().contains("software"))
        assertTrue(advancedCodecFallbackReason(CaptureVideoCodec.H265, softwareOnly).orEmpty().contains("does not list"))
        assertTrue(advancedCodecFallbackReason(CaptureVideoCodec.AV1, null).orEmpty().contains("unknown"))
        assertFalse(softwareOnly.encoders(CaptureVideoCodec.AV1).isEmpty())
    }

    @Test
    fun capabilitiesAreProbedOnlyForARecordedAdvancedCodecAndKnownResultsAreReused() {
        val known = CaptureVideoEncoderCapabilities(
            listOf(CaptureVideoEncoder(CaptureVideoCodec.AV1, "vendor.av1", CaptureEncoderAcceleration.HARDWARE)),
        )
        val fresh = CaptureVideoEncoderCapabilities(emptyList())
        var probes = 0
        val probe = { probes++; fresh }

        assertNull(resolveVideoEncoderCapabilities(CaptureSettings(videoCodec = CaptureVideoCodec.H264), null, probe))
        assertNull(resolveVideoEncoderCapabilities(CaptureSettings(recordVideo = false, videoCodec = CaptureVideoCodec.AV1), null, probe))
        assertEquals(0, probes, "H.264 and video-less captures never probe")

        assertEquals(known, resolveVideoEncoderCapabilities(CaptureSettings(videoCodec = CaptureVideoCodec.AV1), known, probe))
        assertEquals(0, probes, "an already-known result is reused")

        assertEquals(fresh, resolveVideoEncoderCapabilities(CaptureSettings(videoCodec = CaptureVideoCodec.H265), null, probe))
        assertEquals(1, probes, "without a known result the recorder probes once")
    }

    @Test
    fun onlyAFinishedProbeIsReusable() {
        val ready = CaptureVideoEncoderCapabilities(emptyList())
        assertEquals(ready, CaptureVideoEncoderProbeState.Ready(ready).readyCapabilities())
        assertNull(CaptureVideoEncoderProbeState.Loading.readyCapabilities())
        assertNull(CaptureVideoEncoderProbeState.Failed("x").readyCapabilities())
        assertNull((null as CaptureVideoEncoderProbeState?).readyCapabilities())
    }
}
