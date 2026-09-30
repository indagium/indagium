package com.indagium.capture

import com.indagium.capture.mirror.MirrorStreamOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptureVideoPresetTest {
    @Test
    fun everyPresetIsDetectedAfterBeingApplied() {
        for (preset in CaptureVideoPreset.entries) {
            val applied = CaptureSettings().withVideoPreset(preset)
            assertEquals(preset.maxSize, applied.maxSize)
            assertEquals(preset.maxFps, applied.maxFps)
            assertEquals(preset.bitrateMbps, applied.bitrateMbps)
            assertEquals(preset, applied.videoPreset())
        }
    }

    @Test
    fun defaultsAreBalanced() {
        assertEquals(CaptureVideoPreset.BALANCED, CaptureSettings().videoPreset())
        val mirror = MirrorStreamOptions()
        assertEquals(CaptureVideoPreset.BALANCED.maxSize, mirror.maxSize)
        assertEquals(CaptureVideoPreset.BALANCED.maxFps, mirror.maxFps)
        assertEquals(CaptureVideoPreset.BALANCED.bitrateMbps, mirror.bitrateMbps)
    }

    @Test
    fun anyOtherTripleIsCustom() {
        val balanced = CaptureSettings().withVideoPreset(CaptureVideoPreset.BALANCED)
        assertNull(balanced.copy(maxSize = 1080).videoPreset())
        assertNull(balanced.copy(maxFps = 60).videoPreset())
        assertNull(balanced.copy(bitrateMbps = 8).videoPreset())
    }

    @Test
    fun estimateIsHalfTheBitrateCapWithoutAudio() {
        val estimate = captureSizeEstimate(bitrateMbps = 3, withAudio = false)
        assertEquals(11.25, estimate.typicalMbPerMin, 1e-9)
        assertEquals(22.5, estimate.capMbPerMin, 1e-9)
    }

    @Test
    fun audioOrMicrophoneAddsOneMbPerMinuteToBoth() {
        val plain = CaptureSettings().captureSizeEstimate()
        val deviceAudio = CaptureSettings(audio = true).captureSizeEstimate()
        val microphone = CaptureSettings(microphoneDeviceId = MICROPHONE_DEFAULT_ID).captureSizeEstimate()
        assertEquals(plain.typicalMbPerMin + 1.0, deviceAudio.typicalMbPerMin, 1e-9)
        assertEquals(plain.capMbPerMin + 1.0, deviceAudio.capMbPerMin, 1e-9)
        assertEquals(deviceAudio, microphone)
    }

    @Test
    fun formatterRoundsPerMinuteAndThirtyMinuteValues() {
        assertEquals(
            "≈ 11 MB/min (up to 23) · 30 min ≈ 340 MB",
            formatCaptureSizeEstimate(captureSizeEstimate(3, withAudio = false)),
        )
        // Below 10 MB/min one decimal is kept.
        assertEquals(
            "≈ 3.8 MB/min (up to 7.5) · 30 min ≈ 110 MB",
            formatCaptureSizeEstimate(captureSizeEstimate(1, withAudio = false)),
        )
        assertEquals(
            "≈ 23 MB/min (up to 45) · 30 min ≈ 680 MB",
            formatCaptureSizeEstimate(captureSizeEstimate(6, withAudio = false)),
        )
    }

    @Test
    fun formatterSwitchesToGigabytesAtAThousandMegabytes() {
        assertEquals(
            "≈ 45 MB/min (up to 90) · 30 min ≈ 1.4 GB",
            formatCaptureSizeEstimate(captureSizeEstimate(12, withAudio = false)),
        )
        assertEquals(
            "≈ 46 MB/min (up to 91) · 30 min ≈ 1.4 GB",
            formatCaptureSizeEstimate(captureSizeEstimate(12, withAudio = true)),
        )
    }
}
