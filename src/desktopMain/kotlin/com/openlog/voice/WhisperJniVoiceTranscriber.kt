@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package com.openlog.voice

import io.github.givimad.whisperjni.WhisperFullParams
import io.github.givimad.whisperjni.WhisperJNI
import java.io.File

/**
 * The production recognizer. It reads the locally installed model and never opens a network
 * connection; the only network operation in the voice feature is [VoiceModelInstaller.install].
 */
class WhisperJniVoiceTranscriber(
    private val modelFile: () -> File,
) : VoiceTranscriber {
    override fun transcribe(audio: VoiceAudio, options: VoiceTranscriptionOptions): VoiceTranscriptionResult {
        val model = modelFile()
        if (!model.isFile) return VoiceTranscriptionResult.Failure("Install the local voice model before recording.")
        return try {
            loadLibraryOnce()
            val whisper = WhisperJNI()
            val params = WhisperFullParams().apply {
                translate = options.translateToEnglish
                // In WhisperJNI this flag performs language detection *instead of* transcription.
                // `language = "auto"` is the Whisper mode that detects then continues to decode.
                detectLanguage = false
                language = options.language
                noTimestamps = true
                printProgress = false
                printRealtime = false
                initialPrompt = options.initialPrompt
            }
            val samples = audio.toFloatSamples()
            val context = whisper.init(model.toPath())
                ?: return VoiceTranscriptionResult.Failure("Could not load the local voice model.")
            try {
                val result = whisper.full(context, params, samples, samples.size)
                if (result != 0) return VoiceTranscriptionResult.Failure("Local transcription failed (code $result).")
                val text = buildString {
                    repeat(whisper.fullNSegments(context)) { append(whisper.fullGetSegmentText(context, it)) }
                }.trim()
                VoiceTranscriptionResult.Success(
                    VoiceTranscript(text = text, translatedToEnglish = options.translateToEnglish),
                )
            } finally {
                context.close()
            }
        } catch (error: Exception) {
            VoiceTranscriptionResult.Failure("Local transcription failed. Try again.", error)
        }
    }

    private fun VoiceAudio.toFloatSamples(): FloatArray {
        val result = FloatArray(pcm16le.size / VoiceAudio.BYTES_PER_SAMPLE)
        var byteIndex = 0
        result.indices.forEach { index ->
            val low = pcm16le[byteIndex].toInt() and 0xff
            val high = pcm16le[byteIndex + 1].toInt()
            result[index] = ((high shl 8) or low).toShort() / 32768f
            byteIndex += VoiceAudio.BYTES_PER_SAMPLE
        }
        return result
    }

    private companion object {
        @Volatile private var loaded = false

        @Synchronized
        fun loadLibraryOnce() {
            if (loaded) return
            WhisperJNI.loadLibrary()
            WhisperJNI.setLibraryLogger(null)
            loaded = true
        }
    }
}
