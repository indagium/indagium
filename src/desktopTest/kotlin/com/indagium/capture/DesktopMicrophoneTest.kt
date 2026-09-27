@file:Suppress("MagicNumber")

package com.indagium.capture

import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.TargetDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopMicrophoneTest {
    // ── resolveMicrophoneSelection: default/named/legacy-fallback resolution ──────────────

    @Test
    fun defaultIdResolvesToTheSystemDefaultWithNoDiagnostic() {
        val selection = resolveMicrophoneSelection(MICROPHONE_DEFAULT_ID, listOf("USB Mic"))
        assertNull(selection.mixerName)
        assertNull(selection.diagnostic)
    }

    @Test
    fun javaSoundIdStillPresentResolvesToItsMixerWithNoDiagnostic() {
        val selection = resolveMicrophoneSelection("javasound\u001fUSB Mic", listOf("Built-in Microphone", "USB Mic"))
        assertEquals("USB Mic", selection.mixerName)
        assertNull(selection.diagnostic)
    }

    @Test
    fun javaSoundIdWhoseMixerDisappearedFallsBackToDefaultWithADiagnostic() {
        val selection = resolveMicrophoneSelection("javasound\u001fUnplugged Mic", listOf("Built-in Microphone"))
        assertNull(selection.mixerName)
        assertNotNull(selection.diagnostic)
        assertTrue(selection.diagnostic.orEmpty().contains("no longer available"))
    }

    @Test
    fun legacyFfmpegBackedIdsFallBackToDefaultWithADiagnosticInsteadOfBreakingCapture() {
        listOf("avfoundation\u001fBuiltInMicrophone", "dshow\u001fUSB Mic", "pulse\u001falsa_input.usb", "").forEach { legacyId ->
            val selection = resolveMicrophoneSelection(legacyId, listOf("Built-in Microphone"))
            assertNull(selection.mixerName, "legacy id '$legacyId' should fall back to the system default")
            assertNotNull(selection.diagnostic, "legacy id '$legacyId' should surface exactly one diagnostic")
        }
    }

    // ── Byte/frame plumbing ────────────────────────────────────────────────────────────────

    @Test
    fun alignDownToWholeFramesRoundsDownAndNeverGoesNegative() {
        assertEquals(8, alignDownToWholeFrames(9, 4))
        assertEquals(8, alignDownToWholeFrames(8, 4))
        assertEquals(0, alignDownToWholeFrames(3, 4))
        assertEquals(0, alignDownToWholeFrames(-5, 4))
        assertEquals(7, alignDownToWholeFrames(7, 0))
    }

    @Test
    fun bytesToShortsLittleEndianDecodesExplicitlyRegardlessOfPlatformByteOrder() {
        // 0x1234 little-endian is bytes [0x34, 0x12]; -32768 (0x8000) is [0x00, 0x80].
        val bytes = byteArrayOf(0x34, 0x12, 0x00.toByte(), 0x80.toByte())
        val shorts = bytesToShortsLittleEndian(bytes)
        assertEquals(listOf(0x1234.toShort(), Short.MIN_VALUE), shorts.toList())
    }

    @Test
    fun bytesToShortsLittleEndianDropsATrailingOddByte() {
        val shorts = bytesToShortsLittleEndian(byteArrayOf(1, 0, 2))
        assertEquals(1, shorts.size)
        assertEquals(1, shorts[0].toInt())
    }

    @Test
    fun duplicateMonoToStereoInterleavesEachSampleTwice() {
        val stereo = duplicateMonoToStereo(shortArrayOf(10, -20, 30))
        assertEquals(listOf<Short>(10, 10, -20, -20, 30, 30), stereo.toList())
    }

    // ── Permission-failure classification ──────────────────────────────────────────────────

    @Test
    fun onlyActualPermissionErrorsSuggestPrivacySettings() {
        assertEquals(false, microphoneFailureIndicatesPermissionDenied(IOException("No usable microphone is available.")))
        assertEquals(false, microphoneFailureIndicatesPermissionDenied(IOException("Could not open the selected microphone: LineUnavailableException.")))
        assertEquals(true, microphoneFailureIndicatesPermissionDenied(MicrophonePermissionDeniedException("macOS denied microphone access")))
        assertEquals(true, microphoneFailureIndicatesPermissionDenied(IOException("DirectShow access is denied")))
    }

    @Test
    fun securityExceptionAlwaysIndicatesPermissionDeniedRegardlessOfItsMessage() {
        assertEquals(true, microphoneFailureIndicatesPermissionDenied(SecurityException()))
        assertEquals(true, microphoneFailureIndicatesPermissionDenied(SecurityException("some opaque platform message")))
        // Wrapped one level down (e.g. defaultOpenMicrophoneLine's IOException(cause = SecurityException)).
        assertEquals(true, microphoneFailureIndicatesPermissionDenied(IOException("Could not open the selected microphone.", SecurityException())))
    }

    // ── Enumeration keeps the existing API shape ────────────────────────────────────────────

    @Test
    fun enumerationReturnsJavaSoundPrefixedIdsWhenAnyMicrophoneIsAvailable() {
        val result = enumerateDesktopMicrophones()
        assertNull(result.failure, result.failure)
        result.devices.forEach { microphone ->
            assertTrue(microphone.id.startsWith("javasound\u001f"))
            assertNotNull(microphone.label.takeIf(String::isNotBlank))
        }
        assertEquals(result.devices, availableDesktopMicrophones())
    }

    // ── DesktopMicrophoneCapture: whole-frame reads, mono->stereo, byte->short, via a fake line ──

    @Test
    fun capturesWholeFrameChunksAcrossPartialReadsAndTimestampsThemMonotonically() {
        // Three stereo frames A=(1,2) B=(3,4) C=(5,6), little-endian, split so frame B straddles
        // two TargetDataLine.read() calls (2 of its 4 bytes land in each read).
        // Chunk 1: frame A whole, plus B's low sample's low byte pair.
        // Chunk 2: rest of B, then frame C whole.
        val line = FakeTargetDataLine(
            chunks = listOf(
                byteArrayOf(1, 0, 2, 0, 3, 0),
                byteArrayOf(4, 0, 5, 0, 6, 0),
            ),
        )
        val received = ArrayBlockingQueue<Pair<Long, ShortArray>>(16)
        val failures = ArrayBlockingQueue<Throwable>(4)
        val capture = DesktopMicrophoneCapture.open(
            deviceId = "javasound\u001fFake Mic",
            elapsedMillis = { 0L },
            onPcm = { startUs, pcm -> received.add(startUs to pcm) },
            onFailure = { failures.add(it) },
            availableMixerNames = { listOf("Fake Mic") },
            lineOpener = { OpenedMicrophoneLine(line, channels = 2) },
        )
        try {
            val first = received.poll(2, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull(first, "expected a PCM chunk from the fake line; failures=$failures")
            // Only frame A (4 whole bytes) was ready after the first read; B's leading 2 bytes are
            // carried over rather than emitted early or dropped.
            assertEquals(listOf<Short>(1, 2), first.second.toList())

            val second = received.poll(2, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull(second, "expected a second PCM chunk once B's carried-over bytes complete")
            // The carried-over 2 bytes plus this read's 6 bytes reassemble frames B and C intact.
            assertEquals(listOf<Short>(3, 4, 5, 6), second.second.toList())

            assertTrue(second.first >= first.first, "timestamps must not go backwards across chunks")
        } finally {
            capture.close()
        }
        assertTrue(failures.isEmpty(), "unexpected capture failures: $failures")
    }

    @Test
    fun monoOnlyDeviceIsDuplicatedToStereoBeforeReachingTheCaller() {
        val line = FakeTargetDataLine(chunks = listOf(byteArrayOf(100, 0, 44, 1)))
        val received = ArrayBlockingQueue<ShortArray>(4)
        val capture = DesktopMicrophoneCapture.open(
            deviceId = MICROPHONE_DEFAULT_ID,
            elapsedMillis = { 0L },
            onPcm = { _, pcm -> received.add(pcm) },
            onFailure = {},
            availableMixerNames = { emptyList() },
            lineOpener = { OpenedMicrophoneLine(line, channels = 1) },
        )
        try {
            val pcm = received.poll(2, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull(pcm)
            // Mono samples 100 and 300, each duplicated across both channels.
            assertEquals(listOf<Short>(100, 100, 300, 300), pcm.toList())
        } finally {
            capture.close()
        }
    }

    @Test
    fun aLineOpenFailureIsReportedAsAFailureRatherThanSilentlyDroppingCapture() {
        val failures = ArrayBlockingQueue<Throwable>(4)
        val capture = DesktopMicrophoneCapture.open(
            deviceId = MICROPHONE_DEFAULT_ID,
            elapsedMillis = { 0L },
            onPcm = { _, _ -> },
            onFailure = { failures.add(it) },
            availableMixerNames = { emptyList() },
            lineOpener = { throw SecurityException("denied") },
        )
        try {
            val failure = failures.poll(2, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull(failure)
            assertTrue(microphoneFailureIndicatesPermissionDenied(failure))
        } finally {
            capture.close()
        }
    }

    // ── MonotonicMicrophoneSampleClock (unchanged; DesktopMicrophoneCapture now always passes
    //    sourceTimestampUs = null since Java Sound exposes no per-buffer device timestamp) ──────

    @Test
    fun microphoneSampleClockIgnoresDelayedAndJitteredReadReturns() {
        val clock = MonotonicMicrophoneSampleClock()
        assertEquals(990_000L, clock.startElapsedUs(readCompletedElapsedMs = 1_000L, sampleFrames = 480))
        assertEquals(1_000_000L, clock.startElapsedUs(readCompletedElapsedMs = 1_025L, sampleFrames = 480))
        assertEquals(1_010_000L, clock.startElapsedUs(readCompletedElapsedMs = 1_087L, sampleFrames = 480))
        // A 170ms late read is below the resync threshold and cannot move later PCM out of order.
        assertEquals(1_020_000L, clock.startElapsedUs(readCompletedElapsedMs = 1_200L, sampleFrames = 480))
    }

    @Test
    fun microphoneSampleClockBoundsLargePhaseCorrections() {
        val clock = MonotonicMicrophoneSampleClock()
        assertEquals(990_000L, clock.startElapsedUs(readCompletedElapsedMs = 1_000L, sampleFrames = 480))
        assertEquals(1_000_000L, clock.startElapsedUs(readCompletedElapsedMs = 1_010L, sampleFrames = 480))
        // A 990ms discrepancy adjusts this buffer by at most 2ms (96 frames), not a full jump.
        assertEquals(1_012_000L, clock.startElapsedUs(readCompletedElapsedMs = 2_000L, sampleFrames = 480))
        assertEquals(1_024_000L, clock.startElapsedUs(readCompletedElapsedMs = 2_010L, sampleFrames = 480))
    }

    /** The mic path's own version of the crackle DeviceAudioContinuityClock fixes for device audio
     *  (see that class's test file): confirms the SAME source can never produce overlapping chunks
     *  into the mixer under realistic read-completion jitter. Simulates 200 real 20ms reads (960
     *  frames at 48kHz — the same chunk size DesktopMicrophoneCapture actually reads) whose
     *  wall-clock read-completion time jitters by a few ms around the true schedule, exactly the
     *  jitter a real blocking TargetDataLine.read() call has, and asserts every chunk's placement is
     *  at or after the previous chunk's own end. */
    @Test
    fun microphoneSampleClockNeverProducesSameSourceOverlapsUnderRealisticReadJitter() {
        val clock = MonotonicMicrophoneSampleClock()
        val frames = 960
        val chunkMs = 20L
        var trueScheduleMs = 0L
        var previousEndUs = -1L
        // Deterministic pseudo-jitter in [-3, 3] ms, covering both directions repeatedly.
        val jitterPatternMs = longArrayOf(0, 2, -1, 3, -3, 1, -2, 0, 3, -1)
        repeat(200) { index ->
            trueScheduleMs += chunkMs
            val readCompletedMs = trueScheduleMs + jitterPatternMs[index % jitterPatternMs.size]
            val startUs = clock.startElapsedUs(readCompletedElapsedMs = readCompletedMs, sampleFrames = frames)
            assertTrue(startUs >= previousEndUs, "chunk $index overlaps the previous one: start=$startUs, previousEnd=$previousEndUs")
            previousEndUs = startUs + chunkMs * 1_000L
        }
    }

    /** A fake [TargetDataLine] whose [read] returns the next queued chunk (one per call, blocking
     *  the reader thread indefinitely once exhausted so the test can close capture deterministically
     *  rather than racing a real device). */
    private class FakeTargetDataLine(chunks: List<ByteArray>) : TargetDataLine {
        private val remaining = ArrayBlockingQueue<ByteArray>(chunks.size + 1).apply { addAll(chunks) }
        private val format = AudioFormat(48_000f, 16, 2, true, false)

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val chunk = remaining.poll(5, java.util.concurrent.TimeUnit.SECONDS) ?: run {
                // Exhausted: block "forever" (bounded) rather than busy-spinning zero-length reads;
                // close() stops/closes the line, which is expected to unblock a real read() the same
                // way — this fake simply re-polls an now-permanently-empty queue.
                Thread.sleep(50)
                return 0
            }
            chunk.copyInto(b, off)
            return chunk.size
        }

        override fun open(format: AudioFormat, bufferSize: Int) = Unit

        override fun open(format: AudioFormat) = Unit

        override fun open() = Unit

        override fun getFormat(): AudioFormat = format

        override fun getBufferSize(): Int = 4_096

        override fun available(): Int = 0

        override fun getFramePosition(): Int = 0

        override fun getLongFramePosition(): Long = 0L

        override fun getMicrosecondPosition(): Long = 0L

        override fun drain() = Unit

        override fun flush() = Unit

        override fun start() = Unit

        override fun stop() = Unit

        override fun close() = Unit

        override fun isOpen(): Boolean = true

        override fun getLevel(): Float = 0f

        override fun getLineInfo(): javax.sound.sampled.Line.Info = DataLineInfoStub

        override fun isActive(): Boolean = true

        override fun isRunning(): Boolean = true

        override fun addLineListener(listener: javax.sound.sampled.LineListener?) = Unit

        override fun removeLineListener(listener: javax.sound.sampled.LineListener?) = Unit

        override fun getControls(): Array<javax.sound.sampled.Control> = emptyArray()

        override fun isControlSupported(control: javax.sound.sampled.Control.Type?): Boolean = false

        override fun getControl(control: javax.sound.sampled.Control.Type?): javax.sound.sampled.Control =
            throw IllegalArgumentException("no controls supported")

        private object DataLineInfoStub : javax.sound.sampled.Line.Info(TargetDataLine::class.java)
    }
}
