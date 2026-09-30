@file:Suppress("TooGenericExceptionCaught")

package com.indagium.capture

import com.indagium.voice.AppleSpeechNative
import com.indagium.voice.VoiceRecognitionEngines
import java.io.Closeable
import java.io.IOException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread

/** A host microphone discovered through Java Sound (javax.sound.sampled) — the same API
 * [com.indagium.voice.VoiceCapture] already uses for dictation on every desktop platform. On macOS
 * this goes through CoreAudio AUHAL, which is what actually handles a Bluetooth headset switching
 * profile/format right after the input opens (see the FFmpeg-based history below); a bare
 * [Mixer.Info.getName] is kept as [id]'s payload, not an index, so a device list refresh (or a
 * device disconnecting/reconnecting) can't silently point [id] at the wrong hardware. */
data class DesktopMicrophone(val id: String, val label: String)

data class DesktopMicrophoneEnumeration(val devices: List<DesktopMicrophone>, val failure: String? = null)

internal class MicrophonePermissionDeniedException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal fun microphoneFailureIndicatesPermissionDenied(failure: Throwable): Boolean {
    if (generateSequence(failure) { it.cause }.any { it is MicrophonePermissionDeniedException || it is SecurityException }) return true
    return generateSequence(failure) { it.cause }
        .mapNotNull(Throwable::message)
        .joinToString(" ")
        .lowercase()
        .let { message ->
            message.contains("permission denied") ||
                message.contains("not authorized") ||
                message.contains("access is denied") ||
                message.contains("operation not permitted")
        }
}

private const val JAVA_SOUND_ID_PREFIX = "javasound"
private const val ID_SEPARATOR = '\u001f'
private const val JAVA_SOUND_ID_PREFIX_WITH_SEPARATOR = "$JAVA_SOUND_ID_PREFIX$ID_SEPARATOR"

internal const val MIC_SAMPLE_RATE_HZ = 48_000
internal const val MIC_OUTPUT_CHANNELS = 2
private const val MIC_BITS_PER_SAMPLE = 16
internal const val MIC_BYTES_PER_SAMPLE = 2
private const val MILLIS_PER_SECOND = 1_000L
private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8

// 20ms whole-frame reads, matching the muxer's own Opus frame size (see TimelineOpusAudio) so a
// read never needs to straddle more than one encode boundary.
private const val MIC_READ_CHUNK_MS = 20L

// SourceDataLine.open(format, bufferSize) documents bufferSize as a HINT; 220ms sits inside the
// requested 200-250ms range, comfortably larger than the 20ms read chunk so an occasional slow
// read still can't starve the encoder.
private const val MIC_LINE_BUFFER_MS = 220L

private fun javaSoundId(mixerName: String): String = "$JAVA_SOUND_ID_PREFIX_WITH_SEPARATOR$mixerName"

private fun candidateFormat(channels: Int): AudioFormat =
    AudioFormat(MIC_SAMPLE_RATE_HZ.toFloat(), MIC_BITS_PER_SAMPLE, channels, true, false)

/** Enumerates Java Sound mixers that expose a [TargetDataLine] for our capture format at either
 * channel count — Port mixers (pure volume/mute controls, no actual data line) never report
 * support for a [TargetDataLine] and are skipped by construction, no special-casing needed. */
fun enumerateDesktopMicrophones(): DesktopMicrophoneEnumeration = runCatching {
    val devices = AudioSystem.getMixerInfo().mapNotNull { info ->
        val mixer = runCatching { AudioSystem.getMixer(info) }.getOrNull() ?: return@mapNotNull null
        val supportsCapture = (MIC_OUTPUT_CHANNELS downTo 1).any { channels ->
            runCatching { mixer.isLineSupported(DataLine.Info(TargetDataLine::class.java, candidateFormat(channels))) }
                .getOrDefault(false)
        }
        if (!supportsCapture) return@mapNotNull null
        DesktopMicrophone(javaSoundId(info.name), info.name.ifBlank { "Microphone" })
    }
    DesktopMicrophoneEnumeration(devices)
}.getOrElse { failure ->
    DesktopMicrophoneEnumeration(emptyList(), "Could not list microphones: ${failure.message ?: failure::class.simpleName}.")
}

fun availableDesktopMicrophones(): List<DesktopMicrophone> = enumerateDesktopMicrophones().devices

/** Which Java Sound mixer (if any) a saved [deviceId] resolves to right now, plus a diagnostic when
 * that required silently falling back to the system default rather than failing capture outright.
 *
 * `null` [mixerName] in the result means "system default" throughout — both a genuine
 * [MICROPHONE_DEFAULT_ID] selection and every fallback resolve to the same "let Java Sound pick"
 * behavior, so callers never need to special-case which one happened.
 *
 * Two cases fall back rather than failing:
 *  - A `"javasound\u001f<name>"` id whose mixer isn't in [availableMixerNames] any more (the device
 *    was unplugged, renamed, or this is a different machine's saved settings).
 *  - Any other, unrecognized id — in particular the legacy FFmpeg-backed ids from before
 *    microphone capture moved to Java Sound (`"avfoundation\u001f…"`, `"dshow\u001f…"`,
 *    `"pulse\u001f…"`): those must not break capture just because the setting predates this change.
 *
 * A pure function — like this file's other decision points — so both fallback paths are unit
 * testable without a real audio device.
 */
internal fun resolveMicrophoneSelection(deviceId: String, availableMixerNames: List<String>): MicrophoneSelection = when {
    deviceId == MICROPHONE_DEFAULT_ID -> MicrophoneSelection(mixerName = null)
    deviceId.startsWith(JAVA_SOUND_ID_PREFIX_WITH_SEPARATOR) -> {
        val name = deviceId.removePrefix(JAVA_SOUND_ID_PREFIX_WITH_SEPARATOR)
        if (name in availableMixerNames) {
            MicrophoneSelection(mixerName = name)
        } else {
            MicrophoneSelection(
                mixerName = null,
                diagnostic = "The selected microphone is no longer available; using the system default microphone instead. " +
                    "Re-select a microphone in capture settings.",
            )
        }
    }
    else -> MicrophoneSelection(
        mixerName = null,
        diagnostic = "The saved microphone setting is from an older Indagium version and is no longer valid; using the " +
            "system default microphone instead. Re-select a microphone in capture settings.",
    )
}

internal data class MicrophoneSelection(val mixerName: String?, val diagnostic: String? = null)

internal data class OpenedMicrophoneLine(val line: TargetDataLine, val channels: Int)

/** Opens [mixerName] (or, when null, whatever Java Sound considers the system default input) for
 * 48 kHz 16-bit little-endian capture, preferring stereo and falling back to mono when the device
 * doesn't support it — [DesktopMicrophoneCapture] duplicates mono samples to stereo itself so
 * downstream mixing/encoding always sees [MIC_OUTPUT_CHANNELS]-channel PCM regardless of which was
 * actually opened. Throws if neither format can be opened, or if [mixerName] no longer resolves to
 * a real mixer (the caller is expected to have already resolved that via
 * [resolveMicrophoneSelection] and only pass a name still in the current device list). */
internal fun defaultOpenMicrophoneLine(mixerName: String?): OpenedMicrophoneLine {
    val mixer = resolveMixerOrNull(mixerName)
    var lastFailure: Throwable? = null
    for (channels in intArrayOf(MIC_OUTPUT_CHANNELS, 1)) {
        val format = candidateFormat(channels)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val supported = if (mixer != null) mixer.isLineSupported(info) else AudioSystem.isLineSupported(info)
        if (!supported) continue
        try {
            val line = (if (mixer != null) mixer.getLine(info) else AudioSystem.getLine(info)) as TargetDataLine
            line.open(format, lineBufferSizeBytes(channels))
            return OpenedMicrophoneLine(line, channels)
        } catch (failure: Throwable) {
            lastFailure = failure
        }
    }
    if (VoiceRecognitionEngines.isMac() && AppleSpeechNative.microphoneAccessDenied()) {
        throw MicrophonePermissionDeniedException("macOS has denied microphone access to Indagium.", lastFailure)
    }
    throw lastFailure?.let {
        IOException("Could not open the selected microphone: ${it.message ?: it::class.simpleName}.", it)
    } ?: IOException("No usable microphone is available. Check the input device and try again.")
}

/** Resolves [mixerName] to a live [Mixer], or `null` for "let Java Sound pick the system default".
 * Split out of [defaultOpenMicrophoneLine] purely to keep that function's own throw count within
 * detekt's limit — this one throw covers the "named mixer vanished between resolution and open"
 * race [defaultOpenMicrophoneLine]'s KDoc already calls out as the caller's responsibility to avoid. */
private fun resolveMixerOrNull(mixerName: String?): Mixer? = mixerName?.let { name ->
    val info = AudioSystem.getMixerInfo().firstOrNull { it.name == name }
        ?: throw IOException("The selected microphone is no longer available.")
    AudioSystem.getMixer(info)
}

private fun lineBufferSizeBytes(channels: Int): Int {
    val frameBytes = channels * MIC_BYTES_PER_SAMPLE
    return (MIC_SAMPLE_RATE_HZ * frameBytes * MIC_LINE_BUFFER_MS / MILLIS_PER_SECOND).toInt()
}

/**
 * Rounds [byteCount] DOWN to the nearest whole multiple of [frameBytes] — a [TargetDataLine.read]
 * call can return a byte count that splits a sample frame across two reads; the remainder is kept
 * and prefixed onto the next read rather than discarded (see [DesktopMicrophoneCapture]'s read
 * loop), so every chunk handed to [bytesToShortsLittleEndian] is a whole number of frames.
 */
internal fun alignDownToWholeFrames(byteCount: Int, frameBytes: Int): Int {
    if (frameBytes <= 0) return byteCount.coerceAtLeast(0)
    val n = byteCount.coerceAtLeast(0)
    return n - (n % frameBytes)
}

/** Decodes little-endian 16-bit PCM bytes into shorts, explicitly byte-by-byte — never assumes a
 * [java.nio.ByteBuffer]'s native/default order happens to already be little-endian. [bytes] is
 * expected to already be a whole multiple of [MIC_BYTES_PER_SAMPLE] (see
 * [alignDownToWholeFrames]); a trailing odd byte, if any, is simply dropped. */
internal fun bytesToShortsLittleEndian(bytes: ByteArray): ShortArray {
    val count = bytes.size / MIC_BYTES_PER_SAMPLE
    return ShortArray(count) { i ->
        val lo = bytes[i * MIC_BYTES_PER_SAMPLE].toInt() and BYTE_MASK
        val hi = bytes[i * MIC_BYTES_PER_SAMPLE + 1].toInt()
        ((hi shl BITS_PER_BYTE) or lo).toShort()
    }
}

/** Duplicates each mono sample into an interleaved stereo pair — the fallback used when a device
 * only supports mono capture, so downstream mixing/encoding always sees [MIC_OUTPUT_CHANNELS]-
 * channel PCM regardless of what the hardware actually opened as. */
internal fun duplicateMonoToStereo(mono: ShortArray): ShortArray {
    val stereo = ShortArray(mono.size * MIC_OUTPUT_CHANNELS)
    for (i in mono.indices) {
        stereo[i * MIC_OUTPUT_CHANNELS] = mono[i]
        stereo[i * MIC_OUTPUT_CHANNELS + 1] = mono[i]
    }
    return stereo
}

/**
 * Captures one selected Java Sound input (or the system default) as 48 kHz stereo 16-bit PCM,
 * timestamped on the same monotonic capture clock as the Android video session.
 *
 * This replaced an FFmpeg `avfoundation`/`dshow`/`pulse`-backed implementation: FFmpeg's
 * avfoundation demuxer fixes the sample format/buffer layout from the very first buffer it sees,
 * and a Bluetooth headset (HFP) commonly switches profile/format right after the input opens —
 * every buffer after that point was misinterpreted, heard as loud broadband noise. Java Sound's
 * [TargetDataLine] instead goes through the platform's own audio stack (CoreAudio AUHAL on macOS,
 * WASAPI/DirectSound on Windows, ALSA/PulseAudio on Linux), the same API
 * [com.indagium.voice.VoiceCapture] already relies on for dictation on every desktop platform, and
 * lets the OS handle that device-format negotiation instead of FFmpeg guessing it once upfront.
 */
internal class DesktopMicrophoneCapture private constructor(
    private val deviceId: String,
    private val elapsedMillis: () -> Long,
    private val onPcm: (elapsedStartUs: Long, pcm: ShortArray) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val onDiagnostic: (String) -> Unit,
    private val availableMixerNames: () -> List<String>,
    private val lineOpener: (mixerName: String?) -> OpenedMicrophoneLine,
) : Closeable {
    @Volatile private var closed = false

    @Volatile private var line: TargetDataLine? = null
    private val sampleClock = MonotonicMicrophoneSampleClock()
    private val reader = thread(name = "capture-desktop-microphone", isDaemon = true, start = false) { readLoop() }

    init {
        reader.start()
    }

    private fun readLoop() {
        try {
            val selection = resolveMicrophoneSelection(deviceId, availableMixerNames())
            selection.diagnostic?.let(onDiagnostic)
            val opened = lineOpener(selection.mixerName)
            val targetLine = opened.line
            line = targetLine
            targetLine.start()
            readFrom(targetLine, opened.channels)
            if (!closed) throw IOException("The microphone stream ended unexpectedly.")
        } catch (failure: Throwable) {
            if (!closed) onFailure(failure)
        } finally {
            val current = line
            line = null
            runCatching { current?.stop() }
            runCatching { current?.close() }
        }
    }

    private fun readFrom(targetLine: TargetDataLine, deviceChannels: Int) {
        val deviceFrameBytes = deviceChannels * MIC_BYTES_PER_SAMPLE
        val chunkFrames = (MIC_SAMPLE_RATE_HZ * MIC_READ_CHUNK_MS / MILLIS_PER_SECOND).toInt()
        val readBuffer = ByteArray(chunkFrames * deviceFrameBytes)
        var carry = ByteArray(0)
        while (!closed) {
            carry = readOneChunk(targetLine, readBuffer, deviceFrameBytes, deviceChannels, carry)
        }
    }

    /** One [TargetDataLine.read] plus whole-frame bookkeeping, pulled out of [readFrom]'s while
     * loop (detekt's LoopWithTooManyJumpStatements) so every early exit is a plain `return` from
     * this function rather than a `continue` in the loop itself. Returns the updated carry-over
     * bytes — see [alignDownToWholeFrames]'s KDoc for why a read can leave a partial frame behind. */
    private fun readOneChunk(
        targetLine: TargetDataLine,
        readBuffer: ByteArray,
        deviceFrameBytes: Int,
        deviceChannels: Int,
        carry: ByteArray,
    ): ByteArray {
        val read = targetLine.read(readBuffer, 0, readBuffer.size)
        if (read <= 0) return carry
        val combined = if (carry.isEmpty()) readBuffer.copyOf(read) else carry + readBuffer.copyOf(read)
        val wholeBytes = alignDownToWholeFrames(combined.size, deviceFrameBytes)
        if (wholeBytes <= 0) return combined
        val newCarry = if (wholeBytes < combined.size) combined.copyOfRange(wholeBytes, combined.size) else ByteArray(0)
        val deviceSamples = bytesToShortsLittleEndian(combined.copyOf(wholeBytes))
        val stereo = if (deviceChannels == 1) duplicateMonoToStereo(deviceSamples) else deviceSamples
        if (stereo.isEmpty()) return newCarry
        val frames = stereo.size / MIC_OUTPUT_CHANNELS
        val elapsedEndMs = elapsedMillis()
        onPcm(sampleClock.startElapsedUs(elapsedEndMs, frames), stereo)
        return newCarry
    }

    override fun close() {
        closed = true
        val current = line
        // Stopping/closing the line wakes a blocking TargetDataLine.read when a device is
        // unplugged or capture ends. Teardown is also performed by the reader's finally block as a
        // safety net.
        runCatching { current?.stop() }
        runCatching { current?.close() }
        if (reader !== Thread.currentThread()) runCatching { reader.join(MIC_STOP_JOIN_MS) }
    }

    companion object {
        fun open(
            deviceId: String,
            elapsedMillis: () -> Long,
            onPcm: (elapsedStartUs: Long, pcm: ShortArray) -> Unit,
            onFailure: (Throwable) -> Unit,
            onDiagnostic: (String) -> Unit = {},
            availableMixerNames: () -> List<String> = { AudioSystem.getMixerInfo().map { it.name } },
            lineOpener: (mixerName: String?) -> OpenedMicrophoneLine = ::defaultOpenMicrophoneLine,
        ): DesktopMicrophoneCapture =
            DesktopMicrophoneCapture(deviceId, elapsedMillis, onPcm, onFailure, onDiagnostic, availableMixerNames, lineOpener)
    }
}

/** Anchors the first captured buffer to the monotonic capture clock, then advances solely by
 * sample count. Small read-return jitter cannot turn into gaps or overlaps. Phase correction is
 * only applied after a large sustained discrepancy, with a strict per-buffer limit. [sourceTimestampUs]
 * is retained for callers that have a device-reported timestamp to reconcile against; Java Sound's
 * [TargetDataLine] exposes none, so [DesktopMicrophoneCapture] always passes `null` and this always
 * falls back to sample count/read completion timing. */
internal class MonotonicMicrophoneSampleClock(
    private val sampleRateHz: Int = MIC_SAMPLE_RATE_HZ,
    private val resyncThresholdMs: Long = 250L,
    private val maxResyncStepMs: Long = 2L,
) {
    private var nextSampleFrame: Long? = null
    private var sourcePtsAnchorUs: Long? = null
    private var sourceElapsedAnchorUs: Long? = null
    private var previousSourcePtsUs: Long? = null
    private var previousSourceFrames = 0

    fun startElapsedUs(
        readCompletedElapsedMs: Long,
        sampleFrames: Int,
        sourceTimestampUs: Long? = null,
    ): Long {
        require(sampleFrames > 0)
        val readCompletionStartFrame = readCompletedElapsedMs.coerceAtLeast(0) * sampleRateHz / MILLIS_PER_SECOND - sampleFrames
        val observedStartFrame = sourceTimestampUs?.takeIf { it >= 0L }
            ?.let { sourceStartFrame(it, readCompletionStartFrame, sampleFrames) }
            ?: readCompletionStartFrame
        val expectedStartFrame = nextSampleFrame
        val startFrame = if (expectedStartFrame == null) {
            observedStartFrame.coerceAtLeast(0)
        } else {
            val phaseError = observedStartFrame - expectedStartFrame
            if (kotlin.math.abs(phaseError) > resyncThresholdMs * sampleRateHz / MILLIS_PER_SECOND) {
                val maxStepFrames = maxResyncStepMs * sampleRateHz / MILLIS_PER_SECOND
                expectedStartFrame + phaseError.coerceIn(-maxStepFrames, maxStepFrames)
            } else {
                expectedStartFrame
            }
        }
        nextSampleFrame = startFrame + sampleFrames
        return startFrame * MICROS_PER_SECOND / sampleRateHz
    }

    /** Device PTS may begin at an arbitrary origin (or be absent). Anchor the first usable PTS to
     * the best monotonic estimate for that chunk; only use later PTS when increments are strictly
     * increasing and plausible for real-time audio. On a discontinuity, discard the source clock
     * and fall back to sample count/read completion rather than shifting audio far from video t=0. */
    private fun sourceStartFrame(sourcePtsUs: Long, readCompletionStartFrame: Long, sampleFrames: Int): Long {
        val previousPts = previousSourcePtsUs
        val deltaUs = previousPts?.let { sourcePtsUs - it }
        val expectedDeltaUs = previousSourceFrames.toLong() * MICROS_PER_SECOND / sampleRateHz
        val minPlausibleDeltaUs = (expectedDeltaUs / 4L).coerceAtLeast(1L)
        val maxPlausibleDeltaUs = maxOf(MAX_SOURCE_PTS_GAP_US, expectedDeltaUs * 5L)
        val usable = previousPts == null || (
            deltaUs != null && deltaUs in minPlausibleDeltaUs..maxPlausibleDeltaUs
        )
        if (!usable) {
            sourcePtsAnchorUs = sourcePtsUs
            sourceElapsedAnchorUs = readCompletionStartFrame * MICROS_PER_SECOND / sampleRateHz
        } else if (previousPts == null) {
            sourcePtsAnchorUs = sourcePtsUs
            sourceElapsedAnchorUs = readCompletionStartFrame * MICROS_PER_SECOND / sampleRateHz
        }
        previousSourcePtsUs = sourcePtsUs.takeIf { usable }
        previousSourceFrames = sampleFrames
        if (!usable) return readCompletionStartFrame
        val anchorPtsUs = sourcePtsAnchorUs ?: return readCompletionStartFrame
        val anchorElapsedUs = sourceElapsedAnchorUs ?: return readCompletionStartFrame
        return (anchorElapsedUs + sourcePtsUs - anchorPtsUs) * sampleRateHz / MICROS_PER_SECOND
    }

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val MAX_SOURCE_PTS_GAP_US = 1_000_000L
    }
}

private const val MIC_STOP_JOIN_MS = 1_000L
