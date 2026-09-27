package com.indagium.capture.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure, hardware-free tests for [AudioJitterBuffer]'s buffering policy — no FFmpeg, no audio
 *  device. See that class's own doc for the "never block the decode thread; pad silence on
 *  underrun; drop the oldest audio on overflow" contract this exercises. */
class AudioJitterBufferTest {
    @Test
    fun readPadsWithSilenceWhenTheBufferIsEmpty() {
        val buffer = AudioJitterBuffer(maxLatencyMs = 100)
        val out = ShortArray(20).also { it.fill(123) }
        val realFrames = buffer.read(out, frames = 10)
        assertEquals(0, realFrames)
        assertTrue("expected every sample to be silence", out.all { it == 0.toShort() })
    }

    @Test
    fun readReturnsExactlyWhatWasWrittenWhenItFits() {
        val buffer = AudioJitterBuffer(maxLatencyMs = 100)
        val pcm = shortArrayOf(1, 2, 3, 4, 5, 6) // 3 stereo frames
        buffer.write(pcm, frames = 3)
        assertEquals(3, buffer.availableFrames())

        val out = ShortArray(6)
        val realFrames = buffer.read(out, frames = 3)
        assertEquals(3, realFrames)
        assertEquals(listOf<Short>(1, 2, 3, 4, 5, 6), out.toList())
        assertEquals(0, buffer.availableFrames())
    }

    @Test
    fun readPadsTheTailWithSilenceOnAPartialUnderrun() {
        val buffer = AudioJitterBuffer(maxLatencyMs = 100)
        buffer.write(shortArrayOf(10, 20), frames = 1)

        val out = ShortArray(8).also { it.fill(99) }
        val realFrames = buffer.read(out, frames = 4)
        assertEquals(1, realFrames)
        assertEquals(listOf<Short>(10, 20, 0, 0, 0, 0, 0, 0), out.toList())
    }

    @Test
    fun writeDropsTheOldestAudioOnceCapacityOverflows() {
        // 100ms at 48kHz stereo = 4800 frames capacity.
        val buffer = AudioJitterBuffer(maxLatencyMs = 100)
        val capacity = buffer.capacityFrames
        assertEquals(4_800, capacity)

        // Fill to capacity with a distinct value per frame, then push one more frame's worth of
        // audio — the buffer must still hold exactly `capacity` frames, and the FIRST frame written
        // must have been the one dropped (drop oldest, not newest).
        val fillPcm = ShortArray(capacity * 2) { (it / 2).toShort() }
        buffer.write(fillPcm, frames = capacity)
        assertEquals(capacity, buffer.availableFrames())

        buffer.write(shortArrayOf(9999, 9999), frames = 1)
        assertEquals(capacity, buffer.availableFrames()) // still bounded, nothing grows past capacity

        val out = ShortArray(capacity * 2)
        buffer.read(out, frames = capacity)
        // The oldest frame (value 0) was dropped; the buffer now starts at frame index 1's value.
        assertEquals(1, out[0].toInt())
        // The newest write landed at the very end.
        assertEquals(9999, out[(capacity - 1) * 2].toInt())
    }

    @Test
    fun aSingleWriteLargerThanCapacityKeepsOnlyItsOwnTail() {
        val buffer = AudioJitterBuffer(maxLatencyMs = 10) // 480 frames capacity
        val capacity = buffer.capacityFrames
        val huge = ShortArray((capacity + 100) * 2) { (it / 2).toShort() }

        buffer.write(huge, frames = capacity + 100)
        assertEquals(capacity, buffer.availableFrames())

        val out = ShortArray(capacity * 2)
        buffer.read(out, frames = capacity)
        // Only the last `capacity` frames of the oversized write should have survived.
        assertEquals(100, out[0].toInt())
    }

    @Test
    fun closeDiscardsBufferedAudioAndFurtherWritesAreIgnored() {
        val buffer = AudioJitterBuffer(maxLatencyMs = 100)
        buffer.write(shortArrayOf(1, 2), frames = 1)
        buffer.close()
        assertEquals(0, buffer.availableFrames())

        buffer.write(shortArrayOf(3, 4), frames = 1)
        assertEquals(0, buffer.availableFrames())
    }
}
