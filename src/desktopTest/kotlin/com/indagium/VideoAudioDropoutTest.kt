package com.indagium

import com.indagium.video.alignToFrameBoundary
import com.indagium.video.boundAudioCarryover
import com.indagium.video.computeAudioCarryoverCapBytes
import com.indagium.video.computeAudioLineBufferSizeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the pure pieces behind the three video-audio-dropout fixes in
 * video/VideoPlayerController.kt (`computeAudioLineBufferSizeBytes`, `alignToFrameBoundary`,
 * `computeAudioCarryoverCapBytes`/`boundAudioCarryover`) without touching FFmpeg natives, a real
 * SourceDataLine, or the controller's decode thread — like FrameDropPolicyTest and
 * PlaybackTimeline's own tests, these are plain top-level functions kept independent of both for
 * exactly this reason.
 */
class VideoAudioDropoutTest {
    // ── computeAudioLineBufferSizeBytes ──────────────────────────────

    @Test
    fun bufferSizeTargetsRoughlyThreeHundredMillisecondsForOrdinaryStereoAudio() {
        // 44.1kHz stereo 16-bit: bytesPerSecond = 44100 * 2 * 2 = 176400; 300ms of that is 52920.
        val size = computeAudioLineBufferSizeBytes(sampleRate = 44_100, channels = 2)
        assertTrue(size in 40_000..60_000, "expected roughly 300ms of buffer, got $size bytes")
    }

    @Test
    fun bufferSizeIsAlwaysAWholeMultipleOfTheFrameSize() {
        val frameBytes = 2 * 2 // stereo, 16-bit
        val size = computeAudioLineBufferSizeBytes(sampleRate = 48_000, channels = 2)
        assertEquals(0, size % frameBytes)
    }

    @Test
    fun bufferSizeIsClampedToASaneFloorForAVeryLowSampleRate() {
        // 8kHz mono: 300ms is only ~4800 bytes, well under the underrun-prone floor.
        val size = computeAudioLineBufferSizeBytes(sampleRate = 8_000, channels = 1)
        assertTrue(size >= 8_192, "expected the minimum floor to apply, got $size bytes")
    }

    @Test
    fun bufferSizeIsClampedToASaneCeilingForAPathologicalSampleRate() {
        val size = computeAudioLineBufferSizeBytes(sampleRate = 384_000, channels = 8)
        assertTrue(size <= 1_048_576, "expected the maximum ceiling to apply, got $size bytes")
    }

    @Test
    fun bufferSizeFallsBackToTheFloorForInvalidInputRatherThanCrashing() {
        assertEquals(8_192, computeAudioLineBufferSizeBytes(sampleRate = 0, channels = 2))
        assertEquals(8_192, computeAudioLineBufferSizeBytes(sampleRate = 44_100, channels = 0))
        assertEquals(8_192, computeAudioLineBufferSizeBytes(sampleRate = -1, channels = 2))
    }

    // ── alignToFrameBoundary ──────────────────────────────────────────

    @Test
    fun alignmentRoundsDownToTheNearestWholeFrame() {
        val frameBytes = 4 // stereo, 16-bit
        assertEquals(0, alignToFrameBoundary(0, frameBytes))
        assertEquals(0, alignToFrameBoundary(1, frameBytes))
        assertEquals(0, alignToFrameBoundary(3, frameBytes))
        assertEquals(4, alignToFrameBoundary(4, frameBytes))
        assertEquals(4, alignToFrameBoundary(5, frameBytes))
        assertEquals(4, alignToFrameBoundary(7, frameBytes))
        assertEquals(100, alignToFrameBoundary(103, frameBytes))
    }

    @Test
    fun alignmentHandlesAnOddLineAvailableValueWithoutCuttingMidSample() {
        // This is the exact bug: line.available() is an arbitrary byte count from the platform, not
        // guaranteed to be a multiple of the frame size. Every value below must still align cleanly.
        val frameBytes = 4
        for (available in 0..20) {
            val aligned = alignToFrameBoundary(available, frameBytes)
            assertEquals(0, aligned % frameBytes, "available=$available produced misaligned $aligned")
            assertTrue(aligned <= available, "available=$available: aligned $aligned exceeds input")
        }
    }

    @Test
    fun alignmentLeavesTheValueUnchangedWhenFrameSizeIsUnknown() {
        // frameBytes <= 0 means no audio channel count is known yet — must not divide by zero.
        assertEquals(17, alignToFrameBoundary(17, 0))
        assertEquals(17, alignToFrameBoundary(17, -1))
    }

    @Test
    fun alignmentNeverReturnsNegative() {
        assertEquals(0, alignToFrameBoundary(-5, 4))
    }

    // ── computeAudioCarryoverCapBytes / boundAudioCarryover ────────────

    @Test
    fun carryoverCapTargetsRoughlyOneSecondOfAudio() {
        // 44.1kHz stereo 16-bit: bytesPerSecond = 176400.
        val cap = computeAudioCarryoverCapBytes(sampleRate = 44_100, channels = 2)
        assertTrue(cap in 170_000..180_000, "expected roughly 1s of cap, got $cap bytes")
    }

    @Test
    fun carryoverCapIsAlwaysAWholeMultipleOfTheFrameSize() {
        val frameBytes = 2 * 2
        val cap = computeAudioCarryoverCapBytes(sampleRate = 48_000, channels = 2)
        assertEquals(0, cap % frameBytes)
    }

    @Test
    fun carryoverCapIsZeroForInvalidInput() {
        assertEquals(0, computeAudioCarryoverCapBytes(sampleRate = 0, channels = 2))
        assertEquals(0, computeAudioCarryoverCapBytes(sampleRate = 44_100, channels = 0))
    }

    @Test
    fun boundedCarryoverPassesThroughWhenUnderTheCap() {
        val combined = ByteArray(100) { it.toByte() }
        val result = boundAudioCarryover(combined, capBytes = 200)
        assertEquals(100, result.size)
        assertTrue(combined.contentEquals(result))
    }

    @Test
    fun boundedCarryoverTrimsToTheCapByDroppingTheOldestBytes() {
        // Bytes are 0..99; a cap of 40 must keep the newest 40 (60..99), not the oldest.
        val combined = ByteArray(100) { it.toByte() }
        val result = boundAudioCarryover(combined, capBytes = 40)
        assertEquals(40, result.size)
        assertEquals(60.toByte(), result[0])
        assertEquals(99.toByte(), result[39])
    }

    @Test
    fun boundedCarryoverNeverExceedsTheCapEvenForAHugeBacklog() {
        // Simulates a device that has stopped draining entirely: this must never grow without
        // bound, which is the anti-deadlock safety valve writeAudio's KDoc documents.
        val cap = 1_000
        val combined = ByteArray(5_000_000)
        val result = boundAudioCarryover(combined, cap)
        assertEquals(cap, result.size)
    }

    @Test
    fun boundedCarryoverIsEmptyWhenTheCapIsNotPositive() {
        val combined = ByteArray(10)
        assertEquals(0, boundAudioCarryover(combined, capBytes = 0).size)
        assertEquals(0, boundAudioCarryover(combined, capBytes = -5).size)
    }

    @Test
    fun trimmedCarryoverStaysFrameAlignedWhenBothInputsAreFrameAligned() {
        // computeAudioCarryoverCapBytes always returns a whole multiple of the frame size; a
        // frame-aligned combined array trimmed to that cap must itself remain frame-aligned so a
        // later flush never writes a partial sample.
        val frameBytes = 4
        val cap = computeAudioCarryoverCapBytes(sampleRate = 44_100, channels = 2)
        val combined = ByteArray(cap + frameBytes * 37) // frame-aligned and larger than the cap
        val result = boundAudioCarryover(combined, cap)
        assertEquals(cap, result.size)
        assertEquals(0, result.size % frameBytes)
    }
}
