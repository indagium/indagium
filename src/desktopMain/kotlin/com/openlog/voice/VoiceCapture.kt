@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package com.openlog.voice

import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

sealed interface VoiceCaptureStartResult {
    data class Started(val session: VoiceCaptureSession) : VoiceCaptureStartResult

    data class Failure(val message: String, val cause: Throwable? = null) : VoiceCaptureStartResult
}

sealed interface VoiceCaptureResult {
    data class Captured(val audio: VoiceAudio) : VoiceCaptureResult

    data object Cancelled : VoiceCaptureResult

    data object TimedOut : VoiceCaptureResult

    data class Failure(val message: String, val cause: Throwable? = null) : VoiceCaptureResult
}

interface VoiceCapture {
    fun start(): VoiceCaptureStartResult
}

interface VoiceCaptureSession {
    /** Stops recording and returns the in-memory 16 kHz, mono, PCM capture. */
    fun stop(): VoiceCaptureResult

    /** Stops recording and securely drops any in-memory samples. */
    fun cancel()
}

/** Java Sound implementation used by desktop platforms; no recording is written to disk. */
class JavaSoundVoiceCapture(
    private val maxDurationMillis: Long = VoiceAudio.MAX_DURATION_MILLIS,
    private val lineProvider: (AudioFormat) -> TargetDataLine = ::openDefaultLine,
) : VoiceCapture {
    init {
        require(maxDurationMillis in 1..VoiceAudio.MAX_DURATION_MILLIS) {
            "Voice recording duration must be between 1 ms and ${VoiceAudio.MAX_DURATION_MILLIS} ms"
        }
    }

    override fun start(): VoiceCaptureStartResult {
        val format = AudioFormat(VoiceAudio.SAMPLE_RATE_HZ.toFloat(), 16, VoiceAudio.CHANNELS, true, false)
        return try {
            val line = lineProvider(format)
            line.open(format)
            line.start()
            VoiceCaptureStartResult.Started(JavaSoundVoiceCaptureSession(line, maxDurationMillis))
        } catch (error: SecurityException) {
            VoiceCaptureStartResult.Failure("Microphone access was denied. Allow openLog to use the microphone and try again.", error)
        } catch (error: Exception) {
            VoiceCaptureStartResult.Failure("No usable microphone is available. Check the input device and try again.", error)
        }
    }

    private companion object {
        fun openDefaultLine(format: AudioFormat): TargetDataLine {
            val info = DataLine.Info(TargetDataLine::class.java, format)
            check(AudioSystem.isLineSupported(info)) { "16 kHz mono microphone capture is not supported" }
            return AudioSystem.getLine(info) as TargetDataLine
        }
    }
}

private class JavaSoundVoiceCaptureSession(
    private val line: TargetDataLine,
    maxDurationMillis: Long,
) : VoiceCaptureSession {
    private val maximumBytes = (VoiceAudio.SAMPLE_RATE_HZ * VoiceAudio.CHANNELS * VoiceAudio.BYTES_PER_SAMPLE * maxDurationMillis / 1_000L).toInt()
    private val samples = ByteArrayOutputStream()
    @Volatile private var recording = true
    @Volatile private var cancelled = false
    @Volatile private var timedOut = false
    @Volatile private var readFailure: Throwable? = null

    private val reader = Thread({ readSamples() }, "openLog-voice-capture").apply {
        isDaemon = true
        start()
    }

    override fun stop(): VoiceCaptureResult {
        finishLine()
        reader.join(2_000)
        if (cancelled) return VoiceCaptureResult.Cancelled
        if (timedOut) return VoiceCaptureResult.TimedOut
        readFailure?.let { return VoiceCaptureResult.Failure("Microphone recording failed. Try again.", it) }
        val bytes = samples.toByteArray()
        samples.reset()
        return if (bytes.isEmpty()) VoiceCaptureResult.Failure("No speech was captured. Check the microphone and try again.")
        else VoiceCaptureResult.Captured(VoiceAudio(bytes))
    }

    override fun cancel() {
        cancelled = true
        finishLine()
        reader.join(2_000)
        samples.reset()
    }

    private fun readSamples() {
        val buffer = ByteArray(4_096)
        try {
            while (recording) {
                val count = line.read(buffer, 0, buffer.size)
                if (count <= 0) continue
                val remaining = maximumBytes - samples.size()
                if (remaining <= 0) {
                    timedOut = true
                    finishLine()
                    return
                }
                val accepted = minOf(count, remaining)
                samples.write(buffer, 0, accepted)
                if (accepted < count || samples.size() >= maximumBytes) {
                    timedOut = true
                    finishLine()
                    return
                }
            }
        } catch (error: Exception) {
            if (recording && !cancelled) readFailure = error
        }
    }

    private fun finishLine() {
        recording = false
        runCatching { line.stop() }
        runCatching { line.close() }
    }
}
