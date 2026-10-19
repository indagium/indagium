package com.indagium.voice

import com.indagium.model.VoiceInputSettings
import com.indagium.model.VoiceRecognitionEngine
import kotlinx.coroutines.CoroutineScope
import java.io.File

data class VoiceInputRuntime(
    val controller: VoiceInputController,
    val modelInstaller: VoiceModelInstaller,
    val engine: VoiceRecognitionEngine,
)

/** Builds the same local speech engines and global preferences for every prose editor. */
object VoiceInputControllerFactory {
    fun create(
        settings: () -> VoiceInputSettings,
        modelsDirectory: File,
        scope: CoroutineScope,
    ): VoiceInputRuntime {
        val initialSettings = settings()
        val selectedEngine = initialSettings.recognitionEngine
            .takeIf { it in VoiceRecognitionEngines.availableChoices() }
            ?: VoiceRecognitionEngine.WHISPER
        val installer = VoiceModelInstaller(modelsDirectory, VoiceModelCatalog.byId(initialSettings.modelId))
        val controller = VoiceInputController(
            hasInstalledModel = {
                when (selectedEngine) {
                    VoiceRecognitionEngine.WHISPER -> installer.isInstalled()
                    VoiceRecognitionEngine.APPLE_SPEECH -> AppleSpeechNative.ensureReady(settings().selectedRecognitionLanguageCode)
                    VoiceRecognitionEngine.WINDOWS_SPEECH -> VoiceRecognitionEngines.isWindows()
                }
            },
            unavailableMessage = {
                when (selectedEngine) {
                    VoiceRecognitionEngine.APPLE_SPEECH -> AppleSpeechNative.availabilityMessage(settings().selectedRecognitionLanguageCode)
                    VoiceRecognitionEngine.WINDOWS_SPEECH ->
                        "Windows Speech could not start. Install the selected offline speech-recognition language pack or choose Whisper."
                    VoiceRecognitionEngine.WHISPER -> null
                }
            },
            requiresInstallation = selectedEngine == VoiceRecognitionEngine.WHISPER,
            capture = JavaSoundVoiceCapture(),
            transcriber = when (selectedEngine) {
                VoiceRecognitionEngine.WHISPER -> WhisperJniVoiceTranscriber { installer.modelFile }
                VoiceRecognitionEngine.APPLE_SPEECH -> AppleSpeechTranscriber()
                VoiceRecognitionEngine.WINDOWS_SPEECH -> WindowsSpeechTranscriber()
            },
            scope = scope,
            optionsProvider = {
                val currentSettings = settings()
                val language = currentSettings.selectedRecognitionLanguageCode
                VoiceTranscriptionOptions(
                    translateToEnglish = currentSettings.translateToEnglish && VoiceRecognitionEngines.supportsTranslation(selectedEngine),
                    language = language,
                    initialPrompt = VoiceTranscriptionOptions.technicalPrompt(language),
                )
            },
        )
        return VoiceInputRuntime(controller, installer, selectedEngine)
    }
}
