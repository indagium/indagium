@file:Suppress("TooGenericExceptionCaught")

package com.indagium.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

/** macOS Speech.framework adapter. The JNI code sets `requiresOnDeviceRecognition`, therefore
 * neither a locale without local assets nor an unavailable recognizer can cause a cloud fallback. */
class AppleSpeechTranscriber : VoiceTranscriber {
    override fun transcribe(audio: VoiceAudio, options: VoiceTranscriptionOptions): VoiceTranscriptionResult {
        if (!VoiceRecognitionEngines.isMac()) return VoiceTranscriptionResult.Failure("Apple Speech is available only on macOS.")
        if (options.translateToEnglish) {
            return VoiceTranscriptionResult.Failure("Apple Speech transcribes locally but does not translate. Turn off EN or choose Whisper.")
        }
        val reply = AppleSpeechNative.transcribe(audio.pcm16le, appleLocale(options.language))
        val objectReply = runCatching { Json.parseToJsonElement(reply).jsonObject }.getOrNull()
            ?: return VoiceTranscriptionResult.Failure("Apple Speech returned an invalid local result.")
        return when (objectReply["status"]?.jsonPrimitive?.content) {
            "success" -> VoiceTranscriptionResult.Success(
                VoiceTranscript(
                    text = objectReply["text"]?.jsonPrimitive?.content.orEmpty(),
                    detectedLanguage = options.language.takeUnless { it == "auto" },
                    translatedToEnglish = false,
                ),
            )
            else -> VoiceTranscriptionResult.Failure(
                objectReply["message"]?.jsonPrimitive?.content
                    ?: "Apple Speech is unavailable for this language. Choose Whisper instead.",
            )
        }
    }

    private fun appleLocale(language: String): String = when (language) {
        "auto" -> java.util.Locale.getDefault().toLanguageTag()
        "uk" -> "uk-UA"
        "en" -> "en-US"
        "ru" -> "ru-RU"
        else -> language
    }
}

/** Loader is deliberately internal: the dylib is bundled with macOS distributions, extracted into
 * the OS temporary directory, then loaded into the signed JVM process where TCC sees Indagium's
 * Info.plist usage descriptions. */
object AppleSpeechNative {
    private const val RESOURCE_PATH = "/native/macos/libindagium_speech.dylib"
    private var loaded = false
    private var loadFailure: String? = null

    @Synchronized
    fun ensureReady(language: String): Boolean {
        if (!load()) return false
        return nativeEnsureReady(locale(language))
    }

    fun availabilityMessage(language: String): String {
        if (!load()) return loadFailure ?: "Apple Speech support is not included in this build."
        return nativeAvailabilityMessage(locale(language))
    }

    fun transcribe(pcm16le: ByteArray, language: String): String {
        if (!load()) return "{\"status\":\"failure\",\"message\":\"${escape(loadFailure ?: "Apple Speech support is unavailable.")}\"}"
        return nativeTranscribe(pcm16le, language)
    }

    @Synchronized
    private fun load(): Boolean {
        if (loaded) return true
        if (loadFailure != null || !VoiceRecognitionEngines.isMac()) return false
        return try {
            AppleSpeechNative::class.java.getResourceAsStream(RESOURCE_PATH)?.use { source ->
                val file = Files.createTempFile("indagium-speech-", ".dylib").toFile()
                file.deleteOnExit()
                file.outputStream().use { output -> source.copyTo(output) }
                System.load(file.absolutePath)
            } ?: error("Bundled Apple Speech library is missing")
            loaded = true
            true
        } catch (error: Throwable) {
            loadFailure = error.message ?: "Could not load Apple Speech support."
            false
        }
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun locale(language: String): String = when (language) {
        "auto" -> java.util.Locale.getDefault().toLanguageTag()
        "uk" -> "uk-UA"
        "en" -> "en-US"
        "ru" -> "ru-RU"
        else -> language
    }

    @JvmStatic private external fun nativeEnsureReady(language: String): Boolean

    @JvmStatic private external fun nativeAvailabilityMessage(language: String): String

    @JvmStatic private external fun nativeTranscribe(pcm16le: ByteArray, language: String): String
}
