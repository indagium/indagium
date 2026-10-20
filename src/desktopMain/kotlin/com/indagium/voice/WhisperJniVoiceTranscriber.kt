@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package com.indagium.voice

import java.io.File
import java.nio.file.Files

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
            val samples = audio.toFloatSamples()
            val text = WhisperNative.transcribe(
                model.canonicalPath,
                samples,
                options.language,
                options.translateToEnglish,
                options.initialPrompt,
            ).trim()
            VoiceTranscriptionResult.Success(VoiceTranscript(text = text, translatedToEnglish = options.translateToEnglish))
        } catch (error: Throwable) {
            VoiceTranscriptionResult.Failure(error.message?.let { "Local transcription failed: $it" } ?: "Local transcription failed. Try again.", error)
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
        private var loadFailure: String? = null

        @Synchronized
        fun loadLibraryOnce() {
            if (loaded) return
            check(loadFailure == null) { loadFailure ?: "Local Whisper support could not be loaded." }
            val resourcePath = when {
                VoiceRecognitionEngines.isMac() -> "/native/macos/libindagium_whisper.dylib"
                VoiceRecognitionEngines.isWindows() -> "/native/windows/indagium_whisper.dll"
                System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true) -> "/native/linux/libindagium_whisper.so"
                else -> error("Local Whisper is not available on this operating system.")
            }
            try {
                WhisperJniVoiceTranscriber::class.java.getResourceAsStream(resourcePath)?.use { source ->
                    val extension = when {
                        resourcePath.endsWith(".dylib") -> ".dylib"
                        resourcePath.endsWith(".dll") -> ".dll"
                        else -> ".so"
                    }
                    val libraryFile = Files.createTempFile("indagium-whisper-", extension).toFile()
                    libraryFile.deleteOnExit()
                    libraryFile.outputStream().use(source::copyTo)
                    System.load(libraryFile.absolutePath)
                } ?: error("Bundled local Whisper library is missing")
            } catch (failure: Throwable) {
                loadFailure = failure.message ?: failure::class.simpleName ?: "Could not load local Whisper support."
                throw failure
            }
            loaded = true
        }
    }
}
