package com.openlog.voice

/**
 * The small, UI-facing state machine for the composer microphone.  A transcript is deliberately
 * a separate state: callers must explicitly insert it into their editable text field and send it
 * through the normal AI flow themselves.
 */
sealed interface VoiceInputState {
    data object Idle : VoiceInputState

    data object ModelRequired : VoiceInputState

    data class Recording(val startedAtMillis: Long) : VoiceInputState

    data object Transcribing : VoiceInputState

    data class TranscriptReady(val transcript: VoiceTranscript) : VoiceInputState

    data class Failed(val message: String) : VoiceInputState
}

/** Local transcription result. Audio is intentionally not retained here. */
data class VoiceTranscript(
    val text: String,
    val detectedLanguage: String? = null,
    val translatedToEnglish: Boolean = true,
)

data class VoiceAudio(
    val pcm16le: ByteArray,
    val sampleRateHz: Int = SAMPLE_RATE_HZ,
    val channels: Int = CHANNELS,
) {
    val durationMillis: Long
        get() = pcm16le.size.toLong() * 1_000L / (sampleRateHz * channels * BYTES_PER_SAMPLE)

    fun isSilentOrEmpty(): Boolean = pcm16le.size < BYTES_PER_SAMPLE

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNELS = 1
        const val BYTES_PER_SAMPLE = 2
        const val MAX_DURATION_MILLIS = 90_000L
        const val MAX_PCM_BYTES = SAMPLE_RATE_HZ * CHANNELS * BYTES_PER_SAMPLE * 90
    }
}

data class VoiceTranscriptionOptions(
    val translateToEnglish: Boolean = true,
    /** Whisper accepts `auto` for automatic language selection or an ISO language code such as `en`. */
    val language: String = "auto",
    val initialPrompt: String = DEFAULT_INITIAL_PROMPT,
) {
    companion object {
        // Keep this to product and technical proper nouns. A full English phrase biases short
        // Ukrainian/Russian utterances toward English before Whisper has enough context.
        const val DEFAULT_INITIAL_PROMPT = "Indagium, openLog, logcat, Android, Kotlin, Gradle"

        fun technicalPrompt(language: String): String = when (language) {
            "uk" -> "$DEFAULT_INITIAL_PROMPT, стек викликів, помилка"
            "ru" -> "$DEFAULT_INITIAL_PROMPT, стек вызовов, ошибка"
            else -> DEFAULT_INITIAL_PROMPT
        }
    }
}

data class VoiceLanguageOption(val code: String, val label: String)

/** Languages exposed by whisper.cpp's multilingual models. Defaults intentionally lead with the
 * user's requested Automatic / Ukrainian / English choices; Settings can add any listed language. */
object VoiceLanguageCatalog {
    val defaults = listOf(
        VoiceLanguageOption("auto", "Automatic"),
        VoiceLanguageOption("uk", "Ukrainian"),
        VoiceLanguageOption("en", "English"),
    )
    val additional = listOf(
        VoiceLanguageOption("ru", "Russian"), VoiceLanguageOption("pl", "Polish"),
        VoiceLanguageOption("de", "German"), VoiceLanguageOption("fr", "French"),
        VoiceLanguageOption("es", "Spanish"), VoiceLanguageOption("it", "Italian"),
        VoiceLanguageOption("pt", "Portuguese"), VoiceLanguageOption("nl", "Dutch"),
        VoiceLanguageOption("cs", "Czech"), VoiceLanguageOption("tr", "Turkish"),
        VoiceLanguageOption("ja", "Japanese"), VoiceLanguageOption("ko", "Korean"),
        VoiceLanguageOption("zh", "Chinese"), VoiceLanguageOption("ar", "Arabic"),
        VoiceLanguageOption("hi", "Hindi"), VoiceLanguageOption("sv", "Swedish"),
    )
    val all: List<VoiceLanguageOption> = defaults + additional

    fun normalize(codes: List<String>): List<String> {
        val allowed = all.map { it.code }.toSet()
        return (defaults.map { it.code } + codes.map { it.trim().lowercase() })
            .filter { it in allowed }
            .distinct()
    }

    fun label(code: String): String = all.firstOrNull { it.code == code }?.label ?: code
}

sealed interface VoiceTranscriptionResult {
    data class Success(val transcript: VoiceTranscript) : VoiceTranscriptionResult

    data class Failure(val message: String, val cause: Throwable? = null) : VoiceTranscriptionResult
}

/**
 * A recognizer is intentionally an interface. The app adapter may use WhisperJNI, but the
 * recorder/controller remain testable and do not make speech recognition a chat-provider concern.
 */
fun interface VoiceTranscriber {
    fun transcribe(audio: VoiceAudio, options: VoiceTranscriptionOptions): VoiceTranscriptionResult
}

/** Inserts a dictated result without surprising the user by replacing text or sending it. */
fun appendVoiceTranscript(prompt: String, transcript: VoiceTranscript): String {
    val spoken = transcript.text.trim()
    if (spoken.isEmpty()) return prompt
    return when {
        prompt.isBlank() -> spoken
        prompt.last().isWhitespace() -> prompt + spoken
        else -> "$prompt $spoken"
    }
}
