package com.openlog.voice

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class VoiceModelDescriptor(
    val id: String,
    val version: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val licenseUrl: String,
)

object VoiceModelCatalog {
    /** Multilingual Whisper base, pinned to the current content hash rather than a mutable URL alone. */
    val base = VoiceModelDescriptor(
        id = "whisper-base",
        version = "2026-07-26",
        fileName = "ggml-base.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.bin",
        sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
        sizeBytes = 147_951_465L,
        licenseUrl = "https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE",
    )

    /** More accurate multilingual model, especially for short non-English dictation. */
    val small = VoiceModelDescriptor(
        id = "whisper-small",
        version = "2026-07-26",
        fileName = "ggml-small.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-small.bin",
        sha256 = "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
        sizeBytes = 487_601_967L,
        licenseUrl = "https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE",
    )

    val all = listOf(base, small)

    fun byId(id: String): VoiceModelDescriptor = all.firstOrNull { it.id == id } ?: base
}

fun interface VoiceModelDownloader {
    /** Writes [destination] and reports downloaded bytes. Implementations must not retain audio. */
    fun download(model: VoiceModelDescriptor, destination: File, onProgress: (downloadedBytes: Long) -> Unit)
}

object HttpsVoiceModelDownloader : VoiceModelDownloader {
    override fun download(model: VoiceModelDescriptor, destination: File, onProgress: (Long) -> Unit) {
        val source = URI(model.downloadUrl)
        require(source.scheme.equals("https", ignoreCase = true)) { "Voice model URLs must use HTTPS" }
        val connection = source.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.connect()
        try {
            require(connection.url.protocol.equals("https", ignoreCase = true)) { "Voice model download redirected away from HTTPS" }
            check(connection.responseCode in 200..299) { "Voice model download returned HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}

sealed interface VoiceModelInstallResult {
    data class Installed(val file: File) : VoiceModelInstallResult

    data class AlreadyInstalled(val file: File) : VoiceModelInstallResult

    data class Failure(val message: String, val cause: Throwable? = null) : VoiceModelInstallResult
}

enum class VoiceModelStatus {
    INSTALLED,
    MISSING_OR_INVALID,
}

/** Verifies a downloaded model before atomically making it visible to the recognizer. */
class VoiceModelInstaller(
    private val modelDirectory: File,
    private val model: VoiceModelDescriptor = VoiceModelCatalog.base,
    private val downloader: VoiceModelDownloader = HttpsVoiceModelDownloader,
) {
    val descriptor: VoiceModelDescriptor
        get() = model

    val modelFile: File
        get() = File(modelDirectory, model.fileName)

    fun isInstalled(): Boolean = isExpectedModel(modelFile)

    fun status(): VoiceModelStatus = if (isInstalled()) VoiceModelStatus.INSTALLED else VoiceModelStatus.MISSING_OR_INVALID

    @Synchronized
    fun installBlocking(onProgress: (downloadedBytes: Long) -> Unit = {}): VoiceModelInstallResult {
        val destination = modelFile
        val partial = File(modelDirectory, "${model.fileName}.part")
        return try {
            require(modelDirectory.exists() || modelDirectory.mkdirs()) { "Could not create the local voice-model folder" }
            require(modelDirectory.isDirectory) { "Local voice-model path is not a folder" }
            require(destination.canonicalFile.parentFile == modelDirectory.canonicalFile) { "Unsafe voice model file path" }
            if (isExpectedModel(destination)) return VoiceModelInstallResult.AlreadyInstalled(destination)
            partial.delete()
            downloader.download(model, partial, onProgress)
            require(isExpectedModel(partial)) { "Downloaded voice model did not pass integrity verification" }
            try {
                Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            VoiceModelInstallResult.Installed(destination)
        } catch (error: Exception) {
            partial.delete()
            VoiceModelInstallResult.Failure("Could not install the local voice model: ${error.message ?: "unknown error"}", error)
        }
    }

    /** Call from a coroutine scope; download and checksum verification remain local to this class. */
    suspend fun install(onProgress: (downloadedBytes: Long) -> Unit = {}): VoiceModelInstallResult = installBlocking(onProgress)

    @Synchronized
    fun remove(): Boolean {
        val file = modelFile
        return file.isFile && file.delete()
    }

    private fun isExpectedModel(file: File): Boolean {
        if (!file.isFile || file.length() != model.sizeBytes) return false
        return sha256(file).equals(model.sha256, ignoreCase = true)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
