package com.indagium.voice

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real native load/transcription checks are enabled by supplying a local model path. Regular test
 * runs never download multi-gigabyte models. CI supplies Base; Large v3 and Turbo each have their
 * own opt-in variables for maintainers with those models installed.
 */
class WhisperNativeSmokeTest {
    private companion object {
        @Suppress("MagicNumber")
        const val MODEL_HASH_BUFFER_SIZE = 64 * 1024
    }

    @Test
    fun baseModelLoadsFromUnicodePathAndTranscribesAutomaticLanguageFixtureWhenConfigured() {
        val model = configuredModel("INDAGIUM_VOICE_MODEL", "OPENLOG_VOICE_MODEL") ?: return
        transcribeKnownUkrainian(model, translationRequested = false, requireUnicodePath = true)
    }

    @Test
    fun largeV3OptionalIntegrationTranscribesWhenExplicitlyConfigured() {
        val model = configuredModel("INDAGIUM_VOICE_LARGE_V3_MODEL") ?: return
        transcribeKnownUkrainian(
            model,
            translationRequested = false,
            requireUnicodePath = false,
            descriptor = VoiceModelCatalog.largeV3,
        )
    }

    @Test
    fun largeV3TurboOptionalIntegrationNeverMarksOutputAsTranslated() {
        val model = configuredModel("INDAGIUM_VOICE_LARGE_V3_TURBO_MODEL") ?: return
        transcribeKnownUkrainian(
            model,
            translationRequested = true,
            requireUnicodePath = false,
            descriptor = VoiceModelCatalog.largeV3Turbo,
        )
    }

    @Test
    fun nativeModelLoadFailureReturnsAnErrorInsteadOfTerminatingTheJvm() {
        val invalidModel = Files.createTempFile("indagium-invalid-whisper-", ".bin").toFile()
        try {
            val result = WhisperJniVoiceTranscriber { invalidModel }.transcribe(
                VoiceAudio(byteArrayOf(0, 0)),
                VoiceTranscriptionOptions(translateToEnglish = false, language = "auto"),
            )
            val failure = assertIs<VoiceTranscriptionResult.Failure>(result)
            assertNotNull(failure.cause, "native load failure should be surfaced to the caller")
        } finally {
            Files.deleteIfExists(invalidModel.toPath())
        }
    }

    private fun transcribeKnownUkrainian(
        model: File,
        translationRequested: Boolean,
        requireUnicodePath: Boolean,
        descriptor: VoiceModelDescriptor = VoiceModelCatalog.base,
    ) {
        require(model.isFile) { "The configured Whisper model does not exist: $model" }
        assertEquals(descriptor.sizeBytes, model.length(), "configured model size must match the pinned catalog")
        assertEquals(descriptor.sha256, sha256(model), "configured model checksum must match the pinned catalog")
        val temporaryLink = if (requireUnicodePath) unicodeHardLink(model) else null
        try {
            val recognizerPath = temporaryLink ?: model
            val bytes = WhisperNativeSmokeTest::class.java.getResourceAsStream("/voice/whisper-smoke-uk.wav")
                ?.use { stream ->
                    AudioSystem.getAudioInputStream(BufferedInputStream(stream)).use { input ->
                        val targetFormat = AudioFormat(VoiceAudio.SAMPLE_RATE_HZ.toFloat(), 16, VoiceAudio.CHANNELS, true, false)
                        AudioSystem.getAudioInputStream(targetFormat, input).use { it.readBytes() }
                    }
                } ?: error("The synthetic Whisper smoke fixture is missing")
            val supportsTranslation = VoiceRecognitionEngines.supportsTranslation(
                com.indagium.model.VoiceRecognitionEngine.WHISPER,
                descriptor,
            )
            val requestedForEngine = translationRequested && supportsTranslation
            val result = WhisperJniVoiceTranscriber { recognizerPath }.transcribe(
                VoiceAudio(bytes),
                VoiceTranscriptionOptions(translateToEnglish = requestedForEngine, language = "auto"),
            )
            val success = assertIs<VoiceTranscriptionResult.Success>(result)
            assertTrue(success.transcript.text.isNotBlank(), "the native recognizer returned empty text")
            assertTrue(success.transcript.text.contains("української", ignoreCase = true), success.transcript.text)
            assertTrue(success.transcript.text.contains("мови", ignoreCase = true), success.transcript.text)
            assertTrue(
                success.transcript.translatedToEnglish == requestedForEngine,
                "translation metadata must match the effective engine capability",
            )
        } finally {
            temporaryLink?.let { linked ->
                Files.deleteIfExists(linked.toPath())
                Files.deleteIfExists(linked.parentFile.toPath())
            }
        }
    }

    private fun unicodeHardLink(model: File): File {
        val parent = model.canonicalFile.parentFile
        val temporaryRoot = parent.parentFile?.takeIf(File::isDirectory) ?: parent
        val directory = Files.createTempDirectory(temporaryRoot.toPath(), ".whisper-native-test-").toFile()
        val linkedModel = File(directory, "модель-whisper.bin")
        try {
            Files.createLink(linkedModel.toPath(), model.canonicalFile.toPath())
        } catch (failure: IOException) {
            directory.deleteRecursively()
            throw failure
        }
        return linkedModel
    }

    private fun configuredModel(vararg environmentVariables: String): File? =
        environmentVariables.firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }?.let(::File)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(MODEL_HASH_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
