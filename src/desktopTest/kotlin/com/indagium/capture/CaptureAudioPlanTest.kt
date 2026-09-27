package com.indagium.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * "Keep sound on the device": [captureAudioPlan] is the pure decision behind both the embedded
 * scrcpy-server args and the external scrcpy CLI args, plus the Android-13+ fallback diagnostic —
 * see that function's own doc. [CaptureRecorder] itself (which actually runs adb to read the SDK
 * level) is not reasonably unit-testable in isolation, the same reasoning
 * `CaptureCoordinatorToolStatusTest`'s own top-level doc gives for a different file.
 */
class CaptureAudioPlanTest {
    @Test
    fun audioOffNeverAddsArgsRegardlessOfKeepDeviceAudioOrSdk() {
        val plan = captureAudioPlan(audio = false, keepDeviceAudio = true, deviceSdk = 34)
        assertEquals(emptyList(), plan.serverArgs)
        assertEquals(emptyList(), plan.cliArgs)
        assertNull(plan.diagnostic)
    }

    @Test
    fun audioOnKeepOffAddsNoExtraArgs() {
        val plan = captureAudioPlan(audio = true, keepDeviceAudio = false, deviceSdk = 34)
        assertEquals(emptyList(), plan.serverArgs)
        assertEquals(emptyList(), plan.cliArgs)
        assertNull(plan.diagnostic)
    }

    @Test
    fun audioOnKeepOnAndSdkAtLeast33AddsPlaybackAndDup() {
        val plan = captureAudioPlan(audio = true, keepDeviceAudio = true, deviceSdk = 33)
        assertEquals(listOf("audio_source=playback", "audio_dup=true"), plan.serverArgs)
        assertEquals(listOf("--audio-source=playback", "--audio-dup"), plan.cliArgs)
        assertNull(plan.diagnostic)

        val newer = captureAudioPlan(audio = true, keepDeviceAudio = true, deviceSdk = 34)
        assertEquals(listOf("audio_source=playback", "audio_dup=true"), newer.serverArgs)
    }

    @Test
    fun audioOnKeepOnAndSdkBelow33FallsBackWithADiagnostic() {
        val plan = captureAudioPlan(audio = true, keepDeviceAudio = true, deviceSdk = 30)
        assertEquals(emptyList(), plan.serverArgs)
        assertEquals(emptyList(), plan.cliArgs)
        assertEquals(
            "Keep sound on the device needs Android 13+; this device runs Android 11, " +
                "so its speaker is muted while audio is captured.",
            plan.diagnostic,
        )
    }

    @Test
    fun audioOnKeepOnAndUnknownSdkFallsBackWithAGenericDiagnostic() {
        val plan = captureAudioPlan(audio = true, keepDeviceAudio = true, deviceSdk = null)
        assertEquals(emptyList(), plan.serverArgs)
        assertEquals(
            "Keep sound on the device needs Android 13+; this device runs Android an unknown version, " +
                "so its speaker is muted while audio is captured.",
            plan.diagnostic,
        )
    }

    @Test
    fun androidVersionLabelMapsKnownSdkLevelsAndFallsBackToTheRawNumber() {
        assertEquals("11", androidVersionLabel(30))
        assertEquals("13", androidVersionLabel(33))
        assertEquals("SDK 99", androidVersionLabel(99))
    }

    @Test
    fun parseAndroidSdkLevelReadsABarePropertyValue() {
        assertEquals(33, parseAndroidSdkLevel("33\n"))
        assertEquals(30, parseAndroidSdkLevel("  30  "))
        assertNull(parseAndroidSdkLevel(""))
        assertNull(parseAndroidSdkLevel("not-a-number"))
    }
}
