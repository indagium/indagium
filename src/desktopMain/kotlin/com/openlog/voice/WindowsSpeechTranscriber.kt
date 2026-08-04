@file:Suppress("MagicNumber", "MaxLineLength", "TooGenericExceptionCaught")

package com.openlog.voice

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Windows' local SpeechRecognitionEngine is accessed through the Windows PowerShell/.NET runtime
 * already installed with Windows. Audio is converted to an in-memory WAV and piped to the child;
 * no file or network request is created. This intentionally uses the installed language pack, so
 * the user gets a clear error instead of a cloud fallback when their selected locale is absent.
 */
class WindowsSpeechTranscriber : VoiceTranscriber {
    override fun transcribe(audio: VoiceAudio, options: VoiceTranscriptionOptions): VoiceTranscriptionResult {
        if (!VoiceRecognitionEngines.isWindows()) return VoiceTranscriptionResult.Failure("Windows Speech is available only on Windows.")
        if (options.translateToEnglish) {
            return VoiceTranscriptionResult.Failure("Windows Speech transcribes locally but does not translate. Turn off EN or choose Whisper.")
        }
        val language = if (options.language == "auto") java.util.Locale.getDefault().toLanguageTag() else localeTag(options.language)
        return try {
            val builder = ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", POWERSHELL_SCRIPT)
                .redirectErrorStream(true)
            builder.environment()["INDAGIUM_SPEECH_LOCALE"] = language
            val process = builder.start()
            BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8)).use { out ->
                out.write(Base64.getEncoder().encodeToString(audio.toWavBytes()))
            }
            if (!process.waitFor(70, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return VoiceTranscriptionResult.Failure("Windows Speech timed out. Try a shorter recording.")
            }
            val output = process.inputStream.bufferedReader().readText().trim()
            when {
                process.exitValue() != 0 -> VoiceTranscriptionResult.Failure(
                    failureMessage(output, language),
                )
                output.isBlank() -> VoiceTranscriptionResult.Failure("No speech was recognized. Try again.")
                else -> VoiceTranscriptionResult.Success(VoiceTranscript(output, language, translatedToEnglish = false))
            }
        } catch (error: Exception) {
            VoiceTranscriptionResult.Failure("Windows Speech is unavailable. Install an offline speech-recognition language pack or choose Whisper.", error)
        }
    }

    private fun localeTag(code: String): String = when (code) {
        "uk" -> "uk-UA"; "en" -> "en-US"; "ru" -> "ru-RU"; else -> code
    }

    private fun VoiceAudio.toWavBytes(): ByteArray {
        val header = ByteArray(44)

        fun writeInt(offset: Int, value: Int) { repeat(4) { index -> header[offset + index] = (value ushr (index * 8)).toByte() } }

        fun writeShort(offset: Int, value: Int) { repeat(2) { index -> header[offset + index] = (value ushr (index * 8)).toByte() } }
        "RIFF".encodeToByteArray().copyInto(header, 0); writeInt(4, 36 + pcm16le.size); "WAVEfmt ".encodeToByteArray().copyInto(header, 8)
        writeInt(16, 16); writeShort(20, 1); writeShort(22, channels); writeInt(24, sampleRateHz)
        writeInt(28, sampleRateHz * channels * VoiceAudio.BYTES_PER_SAMPLE); writeShort(32, channels * VoiceAudio.BYTES_PER_SAMPLE); writeShort(34, 16)
        "data".encodeToByteArray().copyInto(header, 36); writeInt(40, pcm16le.size)
        return header + pcm16le
    }

    companion object {
        private const val LANGUAGE_UNAVAILABLE_MARKER = "INDAGIUM_SPEECH_LANGUAGE_UNAVAILABLE"

        internal fun failureMessage(output: String, language: String): String = when {
            output.contains(LANGUAGE_UNAVAILABLE_MARKER) -> {
                val languageLabel = VoiceLanguageCatalog.label(language.substringBefore('-').lowercase())
                "Windows Speech has no installed offline recognizer for $languageLabel. " +
                    "Choose Local Whisper, or install Basic or Enhanced speech recognition for a supported Windows language."
            }
            output.isBlank() -> "Windows Speech could not recognize this language. Install its offline speech pack or choose Whisper."
            else -> output
        }

        // `$input` is only this process's stdin. SetInputToWaveStream makes System.Speech use the
        // installed local engine rather than any browser/search dictation service.
        private val POWERSHELL_SCRIPT = """
Add-Type -AssemblyName System.Speech
${'$'}bytes = [Convert]::FromBase64String([Console]::In.ReadToEnd())
${'$'}stream = New-Object System.IO.MemoryStream(,${'$'}bytes)
${'$'}culture = [System.Globalization.CultureInfo]::GetCultureInfo(${ '$' }env:INDAGIUM_SPEECH_LOCALE)
${'$'}recognizerInfo = [System.Speech.Recognition.SpeechRecognitionEngine]::InstalledRecognizers() |
    Where-Object { ${'$'}_.Culture.Name -eq ${'$'}culture.Name } |
    Select-Object -First 1
if (${ '$' }null -eq ${'$'}recognizerInfo) {
    [Console]::Error.Write("INDAGIUM_SPEECH_LANGUAGE_UNAVAILABLE")
    exit 2
}
${'$'}recognizer = New-Object System.Speech.Recognition.SpeechRecognitionEngine(${ '$' }recognizerInfo)
${'$'}recognizer.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
${'$'}recognizer.SetInputToWaveStream(${ '$' }stream)
${'$'}result = ${'$'}recognizer.Recognize()
if (${ '$' }null -eq ${'$'}result) { exit 3 }
[Console]::Out.Write(${ '$' }result.Text)
"""
    }
}
