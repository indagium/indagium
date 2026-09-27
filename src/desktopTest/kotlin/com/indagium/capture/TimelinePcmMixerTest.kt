package com.indagium.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimelinePcmMixerTest {
    @Test
    fun alignsMicrophoneAndDeviceAudioOnOneTimelineAndSoftLimitsOverlappingPeaks() {
        val mixer = TimelinePcmMixer()
        mixer.offer(0, ShortArray(960 * 2) { 20_000 })
        mixer.offer(0, ShortArray(960 * 2) { 20_000 })
        mixer.offer(20_000, ShortArray(960 * 2) { -2_000 })

        val first = mixer.mix(0, 960)
        val next = mixer.mix(960, 960)

        assertTrue(first.all { it in 24_575..30_000 })
        assertTrue(next.all { sample -> sample.toInt() == -2_000 })
    }

    @Test
    fun singleSourcePcmKeepsItsOriginalLevel() {
        val mixer = TimelinePcmMixer()
        mixer.offer(0, ShortArray(960 * 2) { 20_000 })

        assertTrue(mixer.mix(0, 960).all { it == 20_000.toShort() })
    }

    @Test
    fun dropsAndReportsOnlySamplesThatArriveAfterTheirTimelineWasEmitted() {
        val mixer = TimelinePcmMixer()
        mixer.mix(0, 480)
        val droppedFrames = mixer.offer(0, ShortArray(960 * 2) { 3_000 })

        assertEquals(480L, droppedFrames)
        assertEquals(3_000.toShort(), mixer.mix(480, 480).first())
    }

    @Test
    fun bufferedPlayoutKeepsAChunkThatArrivesLateButBeforeItsTimestampIsEmitted() {
        val mixer = TimelinePcmMixer()
        val anchorMs = 10_000L
        val playoutDelayMs = 250L
        val delayedCaptureChunk = ShortArray(480 * 2) { 5_000 }

        assertEquals(0L, audioFramesReady(anchorMs + 220, anchorMs, playoutDelayMs))
        assertEquals(0L, mixer.offer(100_000, delayedCaptureChunk))

        val framesReady = audioFramesReady(anchorMs + 361, anchorMs, playoutDelayMs)
        assertEquals(5_328L, framesReady)
        val mixed = mixer.mix(0, framesReady.toInt())
        assertEquals(5_000.toShort(), mixed[4_800 * 2])
        assertEquals(0.toShort(), mixed.last())
    }
}
