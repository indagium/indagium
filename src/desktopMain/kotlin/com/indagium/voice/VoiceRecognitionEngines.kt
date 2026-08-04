package com.indagium.voice

import com.indagium.model.VoiceRecognitionEngine

/** Platform policy for dictation. Whisper is deliberately available on every desktop target;
 * native engines are optional and never fall back to a network service. */
object VoiceRecognitionEngines {
    private val osName: String get() = System.getProperty("os.name").orEmpty()

    fun isMac(): Boolean = osName.contains("mac", ignoreCase = true)

    fun isWindows(): Boolean = osName.contains("windows", ignoreCase = true)

    fun availableChoices(): List<VoiceRecognitionEngine> = buildList {
        add(VoiceRecognitionEngine.WHISPER)
        if (isMac()) add(VoiceRecognitionEngine.APPLE_SPEECH)
        if (isWindows()) add(VoiceRecognitionEngine.WINDOWS_SPEECH)
    }

    fun supportsTranslation(engine: VoiceRecognitionEngine): Boolean = engine == VoiceRecognitionEngine.WHISPER

    fun description(engine: VoiceRecognitionEngine): String = when (engine) {
        VoiceRecognitionEngine.WHISPER -> "Runs locally on macOS, Windows, and Linux after the selected Whisper model is installed."
        VoiceRecognitionEngine.APPLE_SPEECH -> "Uses Apple's Speech framework in on-device-only mode. It never falls back to network recognition."
        VoiceRecognitionEngine.WINDOWS_SPEECH ->
            "Uses a matching installed legacy Windows Speech recognizer. It never sends audio to a provider, " +
                "but supports fewer languages than Whisper."
    }
}
